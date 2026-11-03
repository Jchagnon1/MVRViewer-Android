// ---------------------------------------------------------------------------
// ENTRÉES DE L'ÉCRAN « MENTIONS ET LICENCES » (guide de mise en vente, C11).
//
// Tâche `:app:releaseLicenseInputs` : écrit app/build/licenses/release-components.json,
// la liste des composants RÉELLEMENT empaquetés dans l'APK de release (artefacts .aar
// et .jar de releaseRuntimeClasspath — pas les BOM ni les modules « racine » Kotlin
// Multiplatform, qui n'apportent aucun fichier), avec, pour chacun, les licences, le
// nom, l'adresse du projet et du code source déclarés dans son POM (en remontant les
// POM parents quand le sien ne les donne pas).
//
// Lue par Tools/generate_licenses.py, qui en tire
// app/src/main/assets/third_party_licenses.json (textes complets, lisibles hors ligne).
// Appliqué par app/build.gradle.kts ; n'intervient dans aucun build.
// ---------------------------------------------------------------------------

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

tasks.register("releaseLicenseInputs") {
    group = "documentation"
    description = "Liste les composants de l'APK de release et les licences de leurs POM (Tools/generate_licenses.py)."
    val output = layout.buildDirectory.file("licenses/release-components.json")
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        val runtime = project.configurations.getByName("releaseRuntimeClasspath")
        // Fichiers EMPAQUETÉS : un composant sans artefact (BOM, module racine KMP
        // qui renvoie vers sa variante -android) n'apparaît pas ici.
        val artifacts = runtime.incoming.artifactView { lenient(false) }.artifacts.artifacts
        // Un même fichier peut revenir par plusieurs variantes : ensemble, pas liste.
        val byComponent = linkedMapOf<ModuleComponentIdentifier, LinkedHashSet<File>>()
        val others = mutableListOf<String>()
        for (a in artifacts) {
            when (val id = a.id.componentIdentifier) {
                is ModuleComponentIdentifier -> byComponent.getOrPut(id) { LinkedHashSet() }.add(a.file)
                else -> others.add(id.displayName)
            }
        }
        if (others.isNotEmpty()) throw GradleException(
            "releaseLicenseInputs : composants non Maven dans l'APK de release (à classer à la main) : $others"
        )

        val pomCache = mutableMapOf<String, File?>()
        fun pomOf(group: String, name: String, version: String): File? = pomCache.getOrPut("$group:$name:$version") {
            val result = project.dependencies.createArtifactResolutionQuery()
                .forModule(group, name, version)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                .execute()
            result.resolvedComponents.firstOrNull()
                ?.getArtifacts(MavenPomArtifact::class.java)
                ?.filterIsInstance<ResolvedArtifactResult>()
                ?.firstOrNull()?.file
        }

        fun Element.child(tag: String): Element? {
            val nodes = childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n is Element && (n.localName ?: n.tagName) == tag) return n
            }
            return null
        }
        fun Element.children(tag: String): List<Element> {
            val out = mutableListOf<Element>()
            val nodes = childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n is Element && (n.localName ?: n.tagName) == tag) out.add(n)
            }
            return out
        }
        fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        fun json(s: String?): String = if (s == null) "null" else buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
            append('"')
        }

        val entries = byComponent.entries.sortedBy { "${it.key.group}:${it.key.module}" }.map { (id, files) ->
            // Licences, nom et adresses : POM du composant, puis ses parents (au plus 6).
            var licenses: List<Pair<String?, String?>> = emptyList()
            var licensesFrom: String? = null
            var name: String? = null
            var url: String? = null
            var scm: String? = null
            var coords: Triple<String, String, String>? = Triple(id.group, id.module, id.version)
            var depth = 0
            while (coords != null && depth < 6) {
                val (g, n, v) = coords
                val pom = pomOf(g, n, v) ?: throw GradleException("releaseLicenseInputs : POM introuvable pour $g:$n:$v")
                val root = factory.newDocumentBuilder().parse(pom).documentElement
                if (depth == 0) name = root.text("name")
                if (url == null) url = root.text("url")
                if (scm == null) scm = root.child("scm")?.text("url")
                if (licenses.isEmpty()) {
                    val found = root.child("licenses")?.children("license").orEmpty()
                        .map { it.text("name") to it.text("url") }
                    if (found.isNotEmpty()) { licenses = found; licensesFrom = "$g:$n:$v" }
                }
                coords = root.child("parent")?.let { p ->
                    val pg = p.text("groupId"); val pn = p.text("artifactId"); val pv = p.text("version")
                    if (pg != null && pn != null && pv != null) Triple(pg, pn, pv) else null
                }
                depth++
            }
            buildString {
                append("    {")
                append("\"group\": ${json(id.group)}, \"name\": ${json(id.module)}, \"version\": ${json(id.version)}, ")
                append("\"files\": [${files.joinToString(", ") { json(it.absolutePath) }}], ")
                append("\"pomName\": ${json(name)}, \"url\": ${json(url)}, \"scm\": ${json(scm)}, ")
                append("\"licensesFrom\": ${json(licensesFrom)}, ")
                append("\"licenses\": [${licenses.joinToString(", ") { "{\"name\": ${json(it.first)}, \"url\": ${json(it.second)}}" }}]")
                append("}")
            }
        }
        val out = output.get().asFile
        out.parentFile.mkdirs()
        out.writeText("{\n  \"components\": [\n" + entries.joinToString(",\n") + "\n  ]\n}\n")
        println("releaseLicenseInputs : ${entries.size} composants → ${out.relativeTo(project.rootDir)}")
    }
}
