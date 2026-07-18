package com.minou.mvrviewer.mvr

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Lecteur .mvr — portage Kotlin de MVRParser (iOS). Un .mvr est un ZIP : on en
 * extrait `GeneralSceneDescription.xml` et on parse la hiérarchie
 * Layers > SceneObjects + les Symdef (dans AUXData). Voir MVRParser.swift.
 */
object MvrParser {

    fun parse(mvrBytes: ByteArray): MvrScene {
        val xml = extractEntry(mvrBytes, "GeneralSceneDescription.xml")
            ?: throw MvrParseException("GeneralSceneDescription.xml introuvable dans le .mvr.")
        return parseSceneXml(xml)
    }

    /** Extrait les octets d'une entrée nommée du ZIP (null si absente). */
    fun extractEntry(mvrBytes: ByteArray, name: String): ByteArray? {
        // Le ZIP peut être corrompu/tronqué (un .gdtf téléchargé de GDTF Share
        // via un CDN/proxy fautif, un transfert coupé) : on ATTRAPE l'erreur au
        // lieu de la laisser remonter — sinon elle traversait la coroutine de
        // reconstruction et FAISAIT CRASHER l'app (« charger une fixture GDTF »).
        try {
            ZipInputStream(ByteArrayInputStream(mvrBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // Le MVR peut ranger les fichiers dans des sous-dossiers : on
                    // compare le nom simple aussi.
                    if (entry.name == name || entry.name.substringAfterLast('/') == name) {
                        return zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) { /* ZIP illisible → traité comme entrée absente */ }
        return null
    }

    /**
     * Extrait plusieurs entrées en UN SEUL parcours du ZIP (évite le O(n²) d'un
     * extractEntry par fichier sur un show à des milliers de géométries).
     * Les clés du résultat sont les noms DEMANDÉS (pas les chemins internes).
     */
    fun extractEntries(mvrBytes: ByteArray, names: Set<String>): Map<String, ByteArray> {
        if (names.isEmpty()) return emptyMap()
        val out = HashMap<String, ByteArray>(names.size)
        // ZIP corrompu → on renvoie ce qu'on a déjà extrait (jamais d'exception
        // remontante : cf. extractEntry).
        try {
            ZipInputStream(ByteArrayInputStream(mvrBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val full = entry.name
                    val base = full.substringAfterLast('/')
                    val key = when {
                        names.contains(full) -> full
                        names.contains(base) -> base
                        else -> null
                    }
                    if (key != null && !out.containsKey(key)) {
                        out[key] = zip.readBytes()
                        // Tout trouvé → stop : évite de décompresser le reste du zip
                        // (57 lots de glb sur un gros show = 57 parcours sinon).
                        if (out.size == names.size) return out
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) { /* ZIP illisible → on renvoie l'extrait partiel */ }
        return out
    }

    /** Liste les entrées du ZIP (diagnostic). */
    fun listEntries(mvrBytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        // ZIP corrompu → liste partielle (jamais d'exception : hasThreeDModel
        // s'appuie dessus sur des octets GDTF Share potentiellement fautifs).
        try {
            ZipInputStream(ByteArrayInputStream(mvrBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) { out.add(entry.name); entry = zip.nextEntry }
            }
        } catch (_: Exception) { /* ZIP illisible → liste partielle */ }
        return out
    }

    // ---- Parsing XML (machine à états, équivalent du delegate iOS) ----

    private fun parseSceneXml(xmlBytes: ByteArray): MvrScene {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val layers = mutableListOf<MvrLayer>()
        val symdefs = HashMap<String, MvrSymdef>()

        var layerName: String? = null
        var layerObjects = mutableListOf<MvrSceneObject>()

        var objName: String? = null
        var objUuid: String? = null
        var objKind: MvrObjectKind? = null
        var objTransform: Mat4? = null
        var geometryRefs = mutableListOf<MvrGeometryRef>()
        var gdtfSpec: String? = null
        var gdtfMode: String? = null
        var fixtureId: String? = null
        var addresses = mutableListOf<String>()

        var symdefUuid: String? = null
        var symdefItems = mutableListOf<MvrGeometryRef>()

        var pendingGeomFile: String? = null
        var pendingSymbolUuid: String? = null
        var pendingItemMatrix: Mat4? = null

        val text = StringBuilder()
        var collecting = false
        var matrixTargetItem = false
        val insideObject: () -> Boolean = { objKind != null }

        fun appendRef(ref: MvrGeometryRef) {
            when {
                symdefUuid != null -> symdefItems.add(ref)
                insideObject() -> geometryRefs.add(ref)
                else -> { /* orpheline : ignorée */ }
            }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Layer" -> {
                        layerName = parser.getAttributeValue(null, "name") ?: "Layer"
                        layerObjects = mutableListOf()
                    }
                    "Symdef" -> {
                        symdefUuid = parser.getAttributeValue(null, "uuid")
                        symdefItems = mutableListOf()
                    }
                    "Fixture", "Truss", "SceneObject", "Support", "VideoScreen", "Projector" -> {
                        objName = parser.getAttributeValue(null, "name") ?: parser.name
                        objUuid = parser.getAttributeValue(null, "uuid")
                        objKind = MvrObjectKind.from(parser.name)
                        objTransform = null
                        geometryRefs = mutableListOf()
                        gdtfSpec = null; gdtfMode = null; fixtureId = null
                        addresses = mutableListOf()
                    }
                    "Matrix" -> {
                        collecting = true; text.setLength(0)
                        matrixTargetItem = pendingGeomFile != null || pendingSymbolUuid != null
                    }
                    "GDTFSpec", "GDTFMode", "FixtureID", "Address" -> {
                        collecting = true; text.setLength(0)
                    }
                    "Geometry3D" -> {
                        pendingGeomFile = parser.getAttributeValue(null, "fileName")
                        pendingItemMatrix = null
                    }
                    "Symbol" -> {
                        pendingSymbolUuid = parser.getAttributeValue(null, "symdef")
                        pendingItemMatrix = null
                    }
                }

                XmlPullParser.TEXT -> if (collecting) text.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.name) {
                    "Matrix" -> {
                        collecting = false
                        val mat = parseMatrix(text.toString())
                        if (matrixTargetItem) pendingItemMatrix = mat
                        else if (mat != null) objTransform = mat
                    }
                    "GDTFSpec" -> { collecting = false; gdtfSpec = text.toString().trim().ifEmpty { gdtfSpec } }
                    "GDTFMode" -> { collecting = false; gdtfMode = text.toString().trim().ifEmpty { gdtfMode } }
                    "FixtureID" -> { collecting = false; fixtureId = text.toString().trim().ifEmpty { fixtureId } }
                    "Address" -> {
                        collecting = false
                        val a = text.toString().trim(); if (a.isNotEmpty()) addresses.add(a)
                    }
                    "Geometry3D" -> {
                        pendingGeomFile?.let { appendRef(MvrGeometryRef.File(it, pendingItemMatrix ?: Mat4.IDENTITY)) }
                        pendingGeomFile = null; pendingItemMatrix = null
                    }
                    "Symbol" -> {
                        pendingSymbolUuid?.let { appendRef(MvrGeometryRef.Symbol(it, pendingItemMatrix ?: Mat4.IDENTITY)) }
                        pendingSymbolUuid = null; pendingItemMatrix = null
                    }
                    "Symdef" -> {
                        symdefUuid?.let { symdefs[it] = MvrSymdef(symdefItems.toList()) }
                        symdefUuid = null; symdefItems = mutableListOf()
                    }
                    "Fixture", "Truss", "SceneObject", "Support", "VideoScreen", "Projector" -> {
                        val name = objName; val kind = objKind
                        if (name != null && kind != null) {
                            layerObjects.add(
                                MvrSceneObject(
                                    uuid = objUuid, name = name, kind = kind,
                                    transform = objTransform ?: Mat4.IDENTITY,
                                    geometryRefs = geometryRefs.toList(),
                                    gdtfSpec = gdtfSpec, gdtfMode = gdtfMode,
                                    fixtureId = fixtureId, addresses = addresses.toList(),
                                    layerName = layerName ?: "Layer"
                                )
                            )
                        }
                        objName = null; objUuid = null; objKind = null; objTransform = null
                        geometryRefs = mutableListOf(); gdtfSpec = null; gdtfMode = null
                    }
                    "Layer" -> {
                        layers.add(MvrLayer(layerName ?: "Layer", layerObjects.toList()))
                        layerName = null; layerObjects = mutableListOf()
                    }
                }
            }
            event = parser.next()
        }
        return MvrScene(layers.toList(), symdefs)
    }

    /**
     * Matrice MVR/GDTF : "{r00,r01,r02}{r10,r11,r12}{r20,r21,r22}{tx,ty,tz}"
     * → 4x4 colonne-majeur (colonne homogène [0,0,0,1] implicite).
     */
    fun parseMatrix(raw: String): Mat4? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val rows = trimmed.split("}")
            .map { it.trim().trimStart('{') }
            .filter { it.isNotEmpty() }
        if (rows.size != 4) return null
        val r = ArrayList<FloatArray>(4)
        for (row in rows) {
            val vals = row.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (vals.size != 3) return null
            r.add(floatArrayOf(vals[0], vals[1], vals[2]))
        }
        return Mat4(
            floatArrayOf(
                r[0][0], r[0][1], r[0][2], 0f,
                r[1][0], r[1][1], r[1][2], 0f,
                r[2][0], r[2][1], r[2][2], 0f,
                r[3][0], r[3][1], r[3][2], 1f
            )
        )
    }
}
