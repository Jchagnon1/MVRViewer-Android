package com.minou.mvrviewer.mvr

import android.util.Xml
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lecteur de géométrie .gdtf — portage Kotlin de GDTFLoader (iOS). Un .gdtf est
 * un zip : description.xml décrit des <Model> (fichier 3D + dimensions
 * physiques déclarées en mètres) et un arbre <Geometries> qui les positionne
 * (Base/Yoke/Head…, <GeometryReference> pour les composants répétés).
 *
 * Le résultat est un ASSEMBLAGE À PLAT : liste de (modèle, transform) dans le
 * repère fixture, en MÈTRES, Z-haut. L'appelant met à l'échelle mm (×1000) et
 * compose avec la transform monde du projecteur.
 */
object GdtfLoader {

    class ModelInfo(val file: String, val maxDeclaredDimension: Float?)
    class Placement(val modelName: String, val transform: Mat4)
    class Assembly(val models: Map<String, ModelInfo>, val placements: List<Placement>)

    /**
     * Vrai si le .gdtf embarque un VRAI modèle 3D (fichier sous models/gltf/ ou
     * models/3ds/), pas seulement des données 2D/photométriques. Port de
     * GDTFLoader.hasThreeDModel (iOS) : la seule façon fiable de savoir si une
     * révision GDTF Share a une géométrie est d'inspecter son contenu.
     */
    fun hasThreeDModel(gdtfBytes: ByteArray): Boolean =
        MvrParser.listEntries(gdtfBytes).any { e ->
            val p = e.lowercase()
            (p.startsWith("models/gltf/") || p.startsWith("models/3ds/")) &&
                (p.endsWith(".glb") || p.endsWith(".gltf") || p.endsWith(".3ds"))
        }

    private class Item(
        val name: String,
        val element: String,
        val model: String?,
        val referencedGeometryName: String?,
        val position: Mat4
    ) {
        val children = mutableListOf<Item>()
    }

    fun parseAssembly(gdtfBytes: ByteArray): Assembly? {
        val xml = MvrParser.extractEntry(gdtfBytes, "description.xml") ?: return null

        val models = HashMap<String, ModelInfo>()
        val roots = mutableListOf<Item>()
        val named = HashMap<String, Item>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), null)
        var insideModels = false
        var insideGeometries = false
        val stack = ArrayDeque<Item>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Models" -> insideModels = true
                    "Geometries" -> insideGeometries = true
                    "Model" -> if (insideModels) {
                        val name = parser.getAttributeValue(null, "Name")
                        val file = parser.getAttributeValue(null, "File")
                        if (name != null && !file.isNullOrEmpty()) {
                            val dims = listOf("Length", "Width", "Height")
                                .mapNotNull { parser.getAttributeValue(null, it)?.toFloatOrNull() }
                                .filter { it > 0f }
                            models[name] = ModelInfo(file, dims.maxOrNull())
                        }
                    }
                    else -> if (insideGeometries) {
                        val item = Item(
                            name = parser.getAttributeValue(null, "Name") ?: parser.name,
                            element = parser.name,
                            model = parser.getAttributeValue(null, "Model"),
                            referencedGeometryName = parser.getAttributeValue(null, "Geometry"),
                            position = parser.getAttributeValue(null, "Position")
                                ?.let { parseGdtfMatrix(it) } ?: Mat4.identity()
                        )
                        stack.addLast(item)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Models" -> insideModels = false
                    "Geometries" -> insideGeometries = false
                    else -> if (insideGeometries && stack.isNotEmpty()) {
                        val finished = stack.removeLast()
                        named[finished.name] = finished
                        if (stack.isEmpty()) roots.add(finished)
                        else stack.last().children.add(finished)
                    }
                }
            }
            event = parser.next()
        }
        if (models.isEmpty()) return null

        // Unité des translations de l'arbre : un offset interne de fixture ne
        // dépasse jamais ~10 m ; au-delà, c'est du mm → conversion en mètres.
        val maxOffset = maxTranslation(roots)
        val offsetScale = if (maxOffset > 10f) 0.001f else 1f

        val placements = ArrayList<Placement>()
        fun walk(item: Item, parent: Mat4, depth: Int) {
            if (depth > 12) return // anti-boucle (GeometryReference circulaire)
            val local = parent * scaledTranslation(item.position, offsetScale)
            if (item.element == "GeometryReference") {
                val target = named[item.referencedGeometryName ?: item.name]
                if (target != null) walk(target, local, depth + 1)
            } else if (item.model != null && models.containsKey(item.model)) {
                placements.add(Placement(item.model, local))
            }
            for (c in item.children) walk(c, local, depth + 1)
        }
        for (r in roots) walk(r, Mat4.identity(), 0)
        // Pas d'arbre de géométries : poser chaque modèle à l'identité (comme iOS).
        if (placements.isEmpty()) {
            for (name in models.keys) placements.add(Placement(name, Mat4.identity()))
        }
        // Dé-doublonnage : certains exports (Vectorworks) empilent des DIZAINES de
        // géométries « Dummy » AU MÊME point (mêmes modèle + transformée) — 42 sur
        // un Esprite. Rendues telles quelles : autant de cylindres superposés,
        // invisibles à l'œil (ils coïncident) mais qui saturent le budget de nœuds
        // → des projecteurs entiers finissent tronqués (« éléments mélangés /
        // mauvaises géométries »). On n'en garde qu'un : rendu IDENTIQUE à l'œil.
        val seen = HashSet<String>(placements.size)
        val deduped = ArrayList<Placement>(placements.size)
        for (p in placements) if (seen.add(p.modelName + "|" + placementKey(p.transform))) deduped.add(p)
        return Assembly(models, deduped)
    }

    /**
     * Octets d'un fichier modèle (référencé SANS extension dans le XML) :
     * emplacements standard glTF puis 3DS. Retourne (octets, extension).
     */
    fun extractModelBytes(
        gdtfBytes: ByteArray,
        fileBase: String,
        /**
         * Vrai = sonder models/3ds/ EN PREMIER. Beaucoup de .gdtf embarquent les
         * DEUX formats : la 3D (Filament) préfère le glb, mais le wireframe plan
         * ne sait lire que le 3ds — sans cette priorité, le glb « masquerait »
         * un 3ds pourtant présent et la silhouette serait perdue.
         */
        prefer3ds: Boolean = false
    ): Pair<ByteArray, String>? {
        val probes = listOf(
            "models/gltf/$fileBase.glb" to "glb",
            "models/gltf/$fileBase.gltf" to "gltf",
            "models/3ds/$fileBase.3ds" to "3ds"
        ).let { if (prefer3ds) it.sortedByDescending { p -> p.second == "3ds" } else it }
        for ((path, ext) in probes) {
            val data = MvrParser.extractEntry(gdtfBytes, path)
            if (data != null) return data to ext
        }
        return null
    }

    /**
     * Matrice GDTF — deux conventions selon les exports :
     *  • "{u}{v}{w}{tx,ty,tz}" (type MVR : 4 rangées, translation en dernière
     *    RANGÉE, colonne-majeur) ;
     *  • 4x4 row-major "{r,r,r,tx}{r,r,r,ty}{r,r,r,tz}{0,0,0,1}" (translation
     *    en dernière COLONNE) → transposer la rotation.
     * Détection : où sont les valeurs non nulles. (Port de parseGDTFMatrix iOS.)
     */
    fun parseGdtfMatrix(raw: String): Mat4? {
        val rows = raw.trim().split("}")
            .map { it.trim().trimStart('{') }
            .filter { it.isNotEmpty() }
        if (rows.size != 4) return null
        val vals = rows.map { r -> r.split(",").mapNotNull { it.trim().toFloatOrNull() } }
        if (vals.any { it.size !in 3..4 }) return null

        if (vals.all { it.size == 4 }) {
            val colLen = sqrt(vals[0][3] * vals[0][3] + vals[1][3] * vals[1][3] + vals[2][3] * vals[2][3])
            val rowLen = sqrt(vals[3][0] * vals[3][0] + vals[3][1] * vals[3][1] + vals[3][2] * vals[3][2])
            if (rowLen < 1e-6f && colLen > 1e-6f) {
                // Row-major : transposer la rotation, translation = 4es valeurs.
                return Mat4(
                    Float4(vals[0][0], vals[1][0], vals[2][0], 0f),
                    Float4(vals[0][1], vals[1][1], vals[2][1], 0f),
                    Float4(vals[0][2], vals[1][2], vals[2][2], 0f),
                    Float4(vals[0][3], vals[1][3], vals[2][3], 1f)
                )
            }
        }
        return Mat4(
            Float4(vals[0][0], vals[0][1], vals[0][2], 0f),
            Float4(vals[1][0], vals[1][1], vals[1][2], 0f),
            Float4(vals[2][0], vals[2][1], vals[2][2], 0f),
            Float4(vals[3][0], vals[3][1], vals[3][2], 1f)
        )
    }

    /** Clé d'une transformée (rotation 3×3 + translation, arrondie) pour dé-doublonner. */
    private fun placementKey(m: Mat4): String {
        fun r(v: Float) = Math.round(v * 1e4f)
        return "${r(m.x.x)},${r(m.x.y)},${r(m.x.z)}," +
            "${r(m.y.x)},${r(m.y.y)},${r(m.y.z)}," +
            "${r(m.z.x)},${r(m.z.y)},${r(m.z.z)}," +
            "${r(m.w.x)},${r(m.w.y)},${r(m.w.z)}"
    }

    /** Multiplie la seule TRANSLATION (rotation/échelle préservées). */
    private fun scaledTranslation(m: Mat4, factor: Float): Mat4 {
        if (factor == 1f) return m
        return Mat4(m.x, m.y, m.z, Float4(m.w.x * factor, m.w.y * factor, m.w.z * factor, 1f))
    }

    private fun maxTranslation(items: List<Item>): Float {
        var v = 0f
        fun visit(i: Item) {
            v = max(v, max(abs(i.position.w.x), max(abs(i.position.w.y), abs(i.position.w.z))))
            i.children.forEach(::visit)
        }
        items.forEach(::visit)
        return v
    }
}
