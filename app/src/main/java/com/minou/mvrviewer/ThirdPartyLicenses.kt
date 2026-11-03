package com.minou.mvrviewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

// Mentions et licences (guide de mise en vente C11) — PORTAGE de
// ThirdPartyLicenses.swift (iOS).
//
// Les composants embarqués dans l'APK de release et le texte COMPLET de leur
// licence, lus HORS LIGNE dans `assets/third_party_licenses.json`. Ce fichier
// n'est jamais écrit à la main : `Tools/generate_licenses.py` le régénère
// depuis les artefacts de releaseRuntimeClasspath et leurs POM. Testé :
// LicensesCatalogTest.

class ThirdPartyLicenseCatalog(
    val components: List<Component>,
    private val texts: Map<String, String>
) {
    /** Un texte de licence ; [from] : projets dont l'AAR le fournit, quand le même avis a plusieurs textes. */
    data class TextRef(val textId: String, val from: List<String>)

    data class Component(
        val id: String,
        val name: String,
        /** Version affichée ; null pour un composant compilé DANS un autre, ou de versions multiples. */
        val version: String?,
        /** Nom court de la licence (« MIT », « Apache 2.0 »…), null pour un avis fourni par Google. */
        val license: String?,
        /** Adresse du code source ([sourceAvailable]) ou, pour un SDK fermé, de sa page. */
        val url: String?,
        val sourceAvailable: Boolean,
        /** Bibliothèques Maven empaquetées (« groupe:nom:version »). */
        val artifacts: List<String>,
        /** Composants (id) dans lesquels celui-ci est compilé ; vide = composant de premier niveau. */
        val includedIn: List<String>,
        val texts: List<TextRef>
    ) {
        val isTopLevel: Boolean get() = includedIn.isEmpty()
    }

    private val byId = components.associateBy { it.id }

    fun component(id: String): Component? = byId[id]

    fun text(id: String): String? = texts[id]

    /** Ids de texte référencés mais absents (ou vides) — doit être vide. */
    fun missingTexts(): List<String> =
        components.flatMap { c -> c.texts.map { it.textId } }.filter { texts[it].isNullOrBlank() }.distinct()

    fun parents(of: Component): List<Component> = of.includedIn.mapNotNull { byId[it] }

    fun children(of: Component): List<Component> =
        components.filter { of.id in it.includedIn }.sortedWith(NAME_ORDER)

    /** Premier niveau, trié par nom : la liste de l'écran sans recherche. */
    fun topLevel(): List<Component> = components.filter { it.isTopLevel }.sortedWith(NAME_ORDER)

    /**
     * Liste affichée : sans recherche, le premier niveau ; avec [query], TOUS les
     * composants (inclus compris) dont le nom, la licence, une bibliothèque ou un
     * parent la contient — casse et accents ignorés. Ordre : d'abord ceux dont le
     * NOM correspond, puis par licence ou bibliothèque, puis par parent (un SDK de
     * Google apporte des dizaines d'avis : chercher « billing » doit montrer la
     * bibliothèque elle-même en tête) ; par nom dans chaque groupe.
     */
    fun displayed(query: String): List<Component> {
        val q = fold(query.trim())
        if (q.isEmpty()) return topLevel()
        fun rank(c: Component): Int? = when {
            fold(c.name).contains(q) -> 0
            (listOf(c.license.orEmpty()) + c.artifacts).any { fold(it).contains(q) } -> 1
            parents(c).any { fold(it.name).contains(q) } -> 2
            else -> null
        }
        return components.mapNotNull { c -> rank(c)?.let { it to c } }
            .sortedWith(compareBy<Pair<Int, Component>> { it.first }.thenBy(NAME_ORDER) { it.second })
            .map { it.second }
    }

    companion object {
        const val ASSET = "third_party_licenses.json"

        private val NAME_ORDER = compareBy<Component>({ fold(it.name) }, { it.id })

        /** Minuscules sans accents, pour la recherche et le tri. */
        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

        fun parse(json: String): ThirdPartyLicenseCatalog {
            val root = JSONObject(json)
            require(root.optInt("format") == 1) { "format de catalogue inconnu" }
            val list = root.getJSONArray("components")
            val components = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val refs = o.getJSONArray("texts")
                Component(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    version = o.optStringOrNull("version"),
                    license = o.optStringOrNull("license"),
                    url = o.optStringOrNull("url"),
                    sourceAvailable = o.optBoolean("sourceAvailable"),
                    artifacts = o.getJSONArray("artifacts").strings(),
                    includedIn = o.getJSONArray("includedIn").strings(),
                    texts = (0 until refs.length()).map { j ->
                        val r = refs.getJSONObject(j)
                        TextRef(r.getString("textId"), r.getJSONArray("from").strings())
                    }
                )
            }
            val t = root.getJSONObject("texts")
            val texts = t.keys().asSequence().associateWith { t.getString(it) }
            return ThirdPartyLicenseCatalog(components, texts)
        }

        /** Catalogue embarqué ; null s'il est illisible (l'écran le dit). */
        fun load(context: Context): ThirdPartyLicenseCatalog? = runCatching {
            context.assets.open(ASSET).use { parse(it.readBytes().toString(Charsets.UTF_8)) }
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}

/**
 * Mise en page d'un texte de licence pour un écran de téléphone (même règle
 * qu'iOS). Les fichiers LICENSE sont coupés à ~80 colonnes : affichés tels quels,
 * chaque ligne se replie en deux et le texte devient illisible. On recolle les
 * lignes d'un même paragraphe (séparés par une ligne vide) ; une puce, une
 * clause numérotée ou un filet commencent toujours une nouvelle ligne. Aucun mot
 * n'est retiré, ajouté ni déplacé (testé).
 */
object LicenseTextLayout {
    private val CLAUSE = Regex("""^\(?[0-9a-zA-Z]{1,3}[.)]\s""")

    fun reflow(text: String): String = paragraphs(text).joinToString("\n\n")

    /** Paragraphes recollés (l'écran en fait un élément de liste chacun). */
    fun paragraphs(text: String): List<String> {
        val out = mutableListOf<String>()
        val lines = mutableListOf<String>()
        fun flush() {
            if (lines.isNotEmpty()) out.add(lines.joinToString("\n"))
            lines.clear()
        }
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                lines.isEmpty() || startsNewLine(line) || isRule(lines.last()) -> lines.add(line)
                else -> lines[lines.size - 1] = lines.last() + " " + line
            }
        }
        flush()
        return out
    }

    /** Puce (« * », « - », « • »), clause « (a) », « 1. », « 2) », ou filet. */
    private fun startsNewLine(line: String): Boolean =
        isRule(line) || line.startsWith("* ") || line.startsWith("- ") || line.startsWith("• ") ||
            CLAUSE.containsMatchIn(line)

    /** Ligne de séparation (« ----- », « ===== »). */
    private fun isRule(line: String): Boolean = line.length >= 3 && line.all { it == '-' || it == '=' || it == '_' }
}
