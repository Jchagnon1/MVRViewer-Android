package com.minou.mvrviewer.mvr

/**
 * ROUTEUR d'un fichier reçu du système (« Ouvrir avec », partage) — SPEC-ma-gdtfshare.md
 * §5.3, ordre EXACT commun aux trois plateformes. PUR sur (nom, octets) : testable en JVM.
 *
 *  1. `.mvr` → règle existante (archive sans description de scène mais avec un modèle 3D →
 *     `MODEL`, sinon `MVR`) : un `.mvr` ne va JAMAIS à la bibliothèque MA ;
 *  2. extension de modèle 3D → `MODEL` ;
 *  3. `.gdtf` → `GDTF` ;
 *  4. `.xmlp` / `.pxml` → `MA_PROFILES` ;
 *  5. `.xml` → racine XML (4 premiers Kio) `GMA3`, `MA` ou `GDTF` → `MA_PROFILES`, sinon `MVR` ;
 *  6. octets zip : description de scène → `MVR` ; `description.xml` racine GDTF → `GDTF` ;
 *     candidats de profils sans modèle 3D → `MA_PROFILES` ; modèle 3D → `MODEL` ; sinon `MVR` ;
 *  7. sinon → `MVR`.
 *
 * Sur Android, `MVR` et `MODEL` gardent le comportement EXISTANT (`SceneViewModel.open`, qui
 * aiguille lui-même vers l'objet 3D seul) ; `GDTF` et `MA_PROFILES` → bibliothèque MA.
 */
object IncomingFileRouter {

    enum class Route { MVR, MODEL, GDTF, MA_PROFILES }

    /**
     * Extensions de modèle 3D connues (liste de référence commune, `formatForExtension` du web :
     * `.skp` compris, son message est actionnable).
     */
    val MODEL_EXTENSIONS = setOf("obj", "stl", "ply", "usd", "usda", "usdc", "usdz", "gltf", "glb", "3ds", "fbx", "dae", "skp")

    private const val HEAD = 4096

    fun extensionOf(name: String?): String =
        name?.substringAfterLast('/')?.substringAfterLast('\\')?.substringAfterLast('.', "")?.lowercase() ?: ""

    /** Octets entiers en main (tests, petits fichiers). */
    fun route(name: String?, bytes: ByteArray): Route =
        route(name, bytes.copyOf(minOf(bytes.size, HEAD))) { ZipCentralReader.ByteSource(bytes) }

    /**
     * `head` = premiers octets (≥ 4 Kio si le fichier est plus grand) ; `zip` ouvre l'archive
     * à accès direct (appelé seulement si nécessaire ; null = illisible).
     */
    fun route(name: String?, head: ByteArray, zip: () -> ZipCentralReader.Source?): Route {
        val isZip = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        when (extensionOf(name)) {
            "mvr" -> {
                if (!isZip) return Route.MVR
                val l = listing(zip) ?: return Route.MVR
                return if (!hasSceneDescription(l.second) && holdsImportableModel(l.second)) Route.MODEL else Route.MVR
            }
            in MODEL_EXTENSIONS -> return Route.MODEL
            "gdtf" -> return Route.GDTF
            "xmlp", "pxml" -> return Route.MA_PROFILES
            "xml" -> return if (xmlRoot(head) in setOf("GMA3", "MA", "GDTF")) Route.MA_PROFILES else Route.MVR
        }
        if (!isZip) return Route.MVR
        val (src, listing) = listing(zip) ?: return Route.MVR
        val entries = listing.entries
        if (hasSceneDescription(listing)) return Route.MVR
        val desc = entries.firstOrNull { it.name.lowercase() == "description.xml" }
        if (desc != null) {
            val r = ZipCentralReader.readBounded(src, listing, desc, MaZipImport.MAX_ENTRY_BYTES)
            if (r is ZipCentralReader.Read.Ok && xmlRoot(r.bytes) == "GDTF") return Route.GDTF
        }
        val model = holdsImportableModel(listing)
        val candidate = entries.any { MaZipImport.classify(it.name, 0) in setOf(MaZipImport.Kind.CANDIDATE, MaZipImport.Kind.NESTED) }
        if (candidate && !model) return Route.MA_PROFILES
        if (model) return Route.MODEL
        return Route.MVR
    }

    private fun listing(zip: () -> ZipCentralReader.Source?): Pair<ZipCentralReader.Source, ZipCentralReader.Listing.Ok>? {
        val src = runCatching { zip() }.getOrNull() ?: return null
        // Mêmes noms que l'import MA : le routeur reconnaît ses candidats avec son classement.
        val l = ZipCentralReader.list(src, MaZipImport.MAX_ENTRIES + 1, MaZipImport.NAMES) as? ZipCentralReader.Listing.Ok ?: return null
        return src to l
    }

    private fun base(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

    /** `GeneralSceneDescription.xml` n'importe où (casse ignorée). */
    fun hasSceneDescription(l: ZipCentralReader.Listing.Ok): Boolean =
        l.entries.any { base(it.name).lowercase() == "generalscenedescription.xml" }

    /** Bruit d'archive (règle `isArchiveNoise` existante du web). */
    private fun isNoise(path: String): Boolean {
        val b = base(path)
        return path.startsWith("__MACOSX/") || b.startsWith("._") || path.endsWith("/") || b.isEmpty()
    }

    /** Une entrée (hors bruit) porte une extension de modèle 3D importable (`.skp` exclu). */
    fun holdsImportableModel(l: ZipCentralReader.Listing.Ok): Boolean =
        l.entries.any { !isNoise(it.name) && extensionOf(it.name).let { e -> e != "skp" && e in MODEL_EXTENSIONS } }

    /**
     * Nom LOCAL de l'élément racine d'un XML d'après ses premiers octets (BOM UTF-8, prologue,
     * commentaires, instructions de traitement, DOCTYPE sautés) ; null si introuvable.
     */
    fun xmlRoot(bytes: ByteArray): String? {
        var s = String(bytes, 0, minOf(bytes.size, HEAD), Charsets.UTF_8)
        if (s.startsWith("﻿")) s = s.substring(1)
        var i = 0
        while (true) {
            i = s.indexOf('<', i)
            if (i < 0 || i + 1 >= s.length) return null
            when {
                s.startsWith("<?", i) -> { i = s.indexOf("?>", i + 2).takeIf { it >= 0 }?.plus(2) ?: return null }
                s.startsWith("<!--", i) -> { i = s.indexOf("-->", i + 4).takeIf { it >= 0 }?.plus(3) ?: return null }
                s.startsWith("<!", i) -> { i = s.indexOf('>', i + 2).takeIf { it >= 0 }?.plus(1) ?: return null }
                else -> {
                    var j = i + 1
                    while (j < s.length && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                    val qname = s.substring(i + 1, j)
                    return qname.substringAfterLast(':').takeIf { it.isNotEmpty() }
                }
            }
        }
    }
}
