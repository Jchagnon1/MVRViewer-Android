package com.minou.mvrviewer.mvr

/**
 * Parseur minimal du format binaire `.3ds` (3D Studio) — portage Kotlin de
 * ThreeDSParser.swift. Les exports MVR Vectorworks stockent souvent le décor /
 * la structure d'un show en `.3ds` (pas en glTF).
 *
 * Format `.3ds` : "chunks" imbriqués — 6 octets d'en-tête (ID sur 2 octets +
 * longueur totale sur 4 octets, en-tête inclus, little-endian) puis contenu.
 * Couvert : sommets (0x4110), coordonnées de texture (0x4140), faces (0x4120),
 * nom d'objet (0x4000), matériaux (nom 0xA000 + couleur diffuse 0xA020 + carte
 * de texture 0xA200 : fichier image 0xA300, échelle/décalage UV 0xA354…0xA35A)
 * et affectation matériau→faces (0x4130).
 *
 * Les exports Vectorworks posent leurs arbres en deux plans croisés texturés
 * (PNG à alpha découpé, référencé par 0xA300) : sans 0x4140 ni 0xA200, ils
 * n'étaient que des « croix grises » dans la couleur diffuse.
 *
 * Convention UV : le `.3ds` a l'origine EN BAS À GAUCHE (v = 0 au sol, v = 1 en
 * haut), comme OpenGL. Les UV sont rendues TELLES QUELLES ; c'est au consommateur
 * de savoir ce qu'attend son moteur — les matériaux SceneView (flipUV=true)
 * retournent déjà V dans leur vertex shader, donc Scene3DScreen les passe brutes.
 */
object ThreeDSParser {

    /** Couleur RGB 0..1. */
    data class Rgb(val r: Float, val g: Float, val b: Float)

    /** Affectation d'un matériau à des triangles (indices 0-based). */
    data class MaterialGroup(val name: String, val triangles: IntArray)

    /**
     * Carte de texture d'un matériau (0xA200) : nom du fichier image tel qu'écrit
     * dans le `.3ds` (0xA300) + échelle/décalage UV (0xA354/0xA356/0xA358/0xA35A,
     * 1/1/0/0 quand absents — cas de tous les exports Vectorworks vus).
     */
    data class TextureMap(
        val fileName: String,
        val uScale: Float = 1f,
        val vScale: Float = 1f,
        val uOffset: Float = 0f,
        val vOffset: Float = 0f
    )

    class Mesh(
        val name: String,
        /** Sommets à plat : x0,y0,z0, x1,y1,z1, … (taille = 3 × nb de sommets). */
        val vertices: FloatArray,
        /** Indices de sommets, groupés par 3 (un triangle par groupe). */
        val faceIndices: IntArray,
        val materialGroups: List<MaterialGroup>,
        /** Couleurs diffuses des matériaux du fichier (nom → RGB). */
        var materials: Map<String, Rgb>,
        /**
         * Coordonnées de texture à plat : u0,v0, u1,v1, … (taille = 2 × nb de
         * sommets quand le fichier est cohérent ; null sans chunk 0x4140).
         * Origine 3DS en bas à gauche — voir l'en-tête.
         */
        val uvs: FloatArray? = null,
        /** Cartes de texture des matériaux du fichier (nom → TextureMap). */
        var materialTextures: Map<String, TextureMap> = emptyMap()
    ) {
        val vertexCount: Int get() = vertices.size / 3
        val triangleCount: Int get() = faceIndices.size / 3
    }

    private const val MAIN3DS = 0x4D4D
    private const val EDIT3DS = 0x3D3D
    private const val EDIT_OBJECT = 0x4000
    private const val OBJ_TRIMESH = 0x4100
    private const val TRI_VERTEX_LIST = 0x4110
    private const val TRI_FACE_LIST = 0x4120
    private const val FACE_MATERIAL = 0x4130
    private const val TRI_MAPPINGCOORS = 0x4140
    private const val EDIT_MATERIAL = 0xAFFF
    private const val MAT_NAME = 0xA000
    private const val MAT_DIFFUSE = 0xA020
    private const val MAT_TEXMAP = 0xA200
    private const val MAT_MAPNAME = 0xA300
    private const val MAT_MAP_USCALE = 0xA354
    private const val MAT_MAP_VSCALE = 0xA356
    private const val MAT_MAP_UOFFSET = 0xA358
    private const val MAT_MAP_VOFFSET = 0xA35A

    /** Matériau lu dans un chunk 0xAFFF. */
    private class MaterialDef(val name: String, val color: Rgb, val texture: TextureMap?)

    fun parse(data: ByteArray): List<Mesh> {
        if (data.isEmpty()) return emptyList()
        val meshes = ArrayList<Mesh>()
        val materials = HashMap<String, Rgb>()
        val textures = HashMap<String, TextureMap>()
        parseChunks(data, 0, data.size, null, meshes, materials, textures)
        for (m in meshes) { m.materials = materials; m.materialTextures = textures }
        return meshes
    }

    private fun parseChunks(
        b: ByteArray, start: Int, end: Int,
        currentObjectName: String?, meshes: MutableList<Mesh>,
        materials: MutableMap<String, Rgb>, textures: MutableMap<String, TextureMap>
    ) {
        var offset = start
        while (offset < end) {
            if (offset + 6 > end) break
            val id = readU16(b, offset)
            val length = readU32(b, offset + 2)
            if (length < 6 || offset + length > end) break
            val cs = offset + 6
            val ce = offset + length

            when (id) {
                MAIN3DS, EDIT3DS ->
                    parseChunks(b, cs, ce, currentObjectName, meshes, materials, textures)

                EDIT_MATERIAL ->
                    parseMaterial(b, cs, ce)?.let { m ->
                        materials[m.name] = m.color
                        m.texture?.let { textures[m.name] = it }
                    }

                EDIT_OBJECT -> {
                    var nameEnd = cs
                    while (nameEnd < ce && b[nameEnd].toInt() != 0) nameEnd++
                    val name = String(b, cs, nameEnd - cs, Charsets.UTF_8)
                    val afterName = minOf(nameEnd + 1, ce)
                    parseChunks(b, afterName, ce, name, meshes, materials, textures)
                }

                OBJ_TRIMESH -> {
                    val verts = ArrayList<Float>()
                    val faces = ArrayList<Int>()
                    val groups = ArrayList<MaterialGroup>()
                    val uvs = ArrayList<Float>()
                    parseTrimesh(b, cs, ce, verts, faces, groups, uvs)
                    if (verts.isNotEmpty() && faces.isNotEmpty()) {
                        meshes.add(
                            Mesh(
                                name = currentObjectName ?: "mesh",
                                vertices = verts.toFloatArray(),
                                faceIndices = faces.toIntArray(),
                                materialGroups = groups,
                                materials = emptyMap(),
                                uvs = if (uvs.isEmpty()) null else uvs.toFloatArray()
                            )
                        )
                    }
                }
            }
            offset += length
        }
    }

    private fun parseMaterial(b: ByteArray, start: Int, end: Int): MaterialDef? {
        var name: String? = null
        var color: Rgb? = null
        var texture: TextureMap? = null
        var offset = start
        while (offset + 6 <= end) {
            val id = readU16(b, offset)
            val length = readU32(b, offset + 2)
            if (length < 6 || offset + length > end) break
            val cs = offset + 6
            val ce = offset + length
            when (id) {
                MAT_NAME -> {
                    var ne = cs
                    while (ne < ce && b[ne].toInt() != 0) ne++
                    name = String(b, cs, ne - cs, Charsets.UTF_8)
                }
                MAT_DIFFUSE -> color = readColor(b, cs, ce)
                // Première carte de texture (diffuse) seulement : les autres
                // (bump, réflexion…) ne sont pas rendues.
                MAT_TEXMAP -> if (texture == null) texture = readTexMap(b, cs, ce)
            }
            offset += length
        }
        val n = name ?: return null
        return MaterialDef(n, color ?: Rgb(0.8f, 0.8f, 0.8f), texture)
    }

    /**
     * Contenu d'un 0xA200 : nom de fichier (0xA300, chaîne C) et, s'ils sont
     * présents, échelle/décalage UV (float32). Tout est BORNÉ par la fin du
     * chunk ; sans nom, pas de carte.
     */
    private fun readTexMap(b: ByteArray, start: Int, end: Int): TextureMap? {
        var fileName: String? = null
        var uScale = 1f; var vScale = 1f; var uOffset = 0f; var vOffset = 0f
        var offset = start
        while (offset + 6 <= end) {
            val id = readU16(b, offset)
            val length = readU32(b, offset + 2)
            if (length < 6 || offset + length > end) break
            val cs = offset + 6
            val ce = offset + length
            when (id) {
                MAT_MAPNAME -> {
                    var ne = cs
                    while (ne < ce && b[ne].toInt() != 0) ne++
                    fileName = String(b, cs, ne - cs, Charsets.UTF_8)
                }
                MAT_MAP_USCALE, MAT_MAP_VSCALE, MAT_MAP_UOFFSET, MAT_MAP_VOFFSET -> {
                    if (cs + 4 <= ce) {
                        val v = readF32(b, cs)
                        if (v.isFinite()) when (id) {
                            MAT_MAP_USCALE -> if (v != 0f) uScale = v
                            MAT_MAP_VSCALE -> if (v != 0f) vScale = v
                            MAT_MAP_UOFFSET -> uOffset = v
                            MAT_MAP_VOFFSET -> vOffset = v
                        }
                    }
                }
            }
            offset += length
        }
        val f = fileName?.trim() ?: return null
        if (f.isEmpty()) return null
        return TextureMap(f, uScale, vScale, uOffset, vOffset)
    }

    private fun readColor(b: ByteArray, start: Int, end: Int): Rgb? {
        var offset = start
        while (offset + 6 <= end) {
            val id = readU16(b, offset)
            val length = readU32(b, offset + 2)
            if (length < 6 || offset + length > end) break
            val cs = offset + 6
            when (id) {
                0x0011, 0x0012 -> { // RGB octets 0..255
                    if (cs + 3 > end) return null
                    return Rgb((b[cs].toInt() and 0xFF) / 255f, (b[cs + 1].toInt() and 0xFF) / 255f, (b[cs + 2].toInt() and 0xFF) / 255f)
                }
                0x0010, 0x0013 -> { // RGB float 0..1
                    if (cs + 12 > end) return null
                    return Rgb(readF32(b, cs), readF32(b, cs + 4), readF32(b, cs + 8))
                }
            }
            offset += length
        }
        return null
    }

    private fun parseTrimesh(
        b: ByteArray, start: Int, end: Int,
        vertices: MutableList<Float>, faceIndices: MutableList<Int>, groups: MutableList<MaterialGroup>,
        uvs: MutableList<Float>
    ) {
        var offset = start
        while (offset < end) {
            if (offset + 6 > end) break
            val id = readU16(b, offset)
            val length = readU32(b, offset + 2)
            if (length < 6 || offset + length > end) break
            val cs = offset + 6
            val ce = offset + length

            when (id) {
                TRI_VERTEX_LIST -> {
                    val count = readU16(b, cs)
                    var p = cs + 2
                    for (i in 0 until count) {
                        if (p + 12 > end) break
                        vertices.add(readF32(b, p)); vertices.add(readF32(b, p + 4)); vertices.add(readF32(b, p + 8))
                        p += 12
                    }
                }
                TRI_MAPPINGCOORS -> {
                    // u16 count puis count × (u, v) float32 — BORNÉ par la fin du
                    // chunk : un compteur surestimé ou un fichier tronqué donne une
                    // liste partielle (le consommateur vérifie 2 × nb de sommets).
                    val count = readU16(b, cs)
                    var p = cs + 2
                    for (i in 0 until count) {
                        if (p + 8 > ce) break
                        uvs.add(readF32(b, p)); uvs.add(readF32(b, p + 4))
                        p += 8
                    }
                }
                TRI_FACE_LIST -> {
                    val count = readU16(b, cs)
                    var p = cs + 2
                    for (i in 0 until count) {
                        if (p + 8 > end) break
                        faceIndices.add(readU16(b, p)); faceIndices.add(readU16(b, p + 2)); faceIndices.add(readU16(b, p + 4))
                        p += 8
                    }
                    // Sous-chunks 0x4130 (matériau→faces) APRÈS la liste des faces.
                    val facesEnd = cs + 2 + count * 8
                    var so = facesEnd
                    while (so + 6 <= ce) {
                        val sid = readU16(b, so)
                        val slen = readU32(b, so + 2)
                        if (slen < 6 || so + slen > ce) break
                        if (sid == FACE_MATERIAL) {
                            val mcs = so + 6
                            var ne = mcs
                            while (ne < so + slen && b[ne].toInt() != 0) ne++
                            val matName = String(b, mcs, ne - mcs, Charsets.UTF_8)
                            var q = ne + 1
                            if (q + 2 <= so + slen) {
                                val nFaces = readU16(b, q); q += 2
                                val tris = ArrayList<Int>(nFaces)
                                for (i in 0 until nFaces) {
                                    if (q + 2 > so + slen) break
                                    tris.add(readU16(b, q)); q += 2
                                }
                                if (tris.isNotEmpty()) groups.add(MaterialGroup(matName, tris.toIntArray()))
                            }
                        }
                        so += slen
                    }
                }
            }
            offset += length
        }
    }

    // --- Lecteurs bas niveau (little-endian, BORNÉS : 0 hors limite) ---

    private fun readU16(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 2 > b.size) return 0
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 4 > b.size) return 0
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }

    private fun readF32(b: ByteArray, o: Int): Float = Float.fromBits(readU32(b, o))
}
