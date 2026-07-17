package com.minou.mvrviewer.mvr

/**
 * Parseur minimal du format binaire `.3ds` (3D Studio) — portage Kotlin de
 * ThreeDSParser.swift. Les exports MVR Vectorworks stockent souvent le décor /
 * la structure d'un show en `.3ds` (pas en glTF).
 *
 * Format `.3ds` : "chunks" imbriqués — 6 octets d'en-tête (ID sur 2 octets +
 * longueur totale sur 4 octets, en-tête inclus, little-endian) puis contenu.
 * Couvert : sommets (0x4110), faces (0x4120), nom d'objet (0x4000), matériaux
 * (nom 0xA000 + couleur diffuse 0xA020) et affectation matériau→faces (0x4130).
 */
object ThreeDSParser {

    /** Couleur RGB 0..1. */
    data class Rgb(val r: Float, val g: Float, val b: Float)

    /** Affectation d'un matériau à des triangles (indices 0-based). */
    data class MaterialGroup(val name: String, val triangles: IntArray)

    class Mesh(
        val name: String,
        /** Sommets à plat : x0,y0,z0, x1,y1,z1, … (taille = 3 × nb de sommets). */
        val vertices: FloatArray,
        /** Indices de sommets, groupés par 3 (un triangle par groupe). */
        val faceIndices: IntArray,
        val materialGroups: List<MaterialGroup>,
        /** Couleurs diffuses des matériaux du fichier (nom → RGB). */
        var materials: Map<String, Rgb>
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
    private const val EDIT_MATERIAL = 0xAFFF
    private const val MAT_NAME = 0xA000
    private const val MAT_DIFFUSE = 0xA020

    fun parse(data: ByteArray): List<Mesh> {
        if (data.isEmpty()) return emptyList()
        val meshes = ArrayList<Mesh>()
        val materials = HashMap<String, Rgb>()
        parseChunks(data, 0, data.size, null, meshes, materials)
        for (m in meshes) m.materials = materials
        return meshes
    }

    private fun parseChunks(
        b: ByteArray, start: Int, end: Int,
        currentObjectName: String?, meshes: MutableList<Mesh>, materials: MutableMap<String, Rgb>
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
                    parseChunks(b, cs, ce, currentObjectName, meshes, materials)

                EDIT_MATERIAL ->
                    parseMaterial(b, cs, ce)?.let { (name, color) -> materials[name] = color }

                EDIT_OBJECT -> {
                    var nameEnd = cs
                    while (nameEnd < ce && b[nameEnd].toInt() != 0) nameEnd++
                    val name = String(b, cs, nameEnd - cs, Charsets.UTF_8)
                    val afterName = minOf(nameEnd + 1, ce)
                    parseChunks(b, afterName, ce, name, meshes, materials)
                }

                OBJ_TRIMESH -> {
                    val verts = ArrayList<Float>()
                    val faces = ArrayList<Int>()
                    val groups = ArrayList<MaterialGroup>()
                    parseTrimesh(b, cs, ce, verts, faces, groups)
                    if (verts.isNotEmpty() && faces.isNotEmpty()) {
                        meshes.add(
                            Mesh(
                                name = currentObjectName ?: "mesh",
                                vertices = verts.toFloatArray(),
                                faceIndices = faces.toIntArray(),
                                materialGroups = groups,
                                materials = emptyMap()
                            )
                        )
                    }
                }
            }
            offset += length
        }
    }

    private fun parseMaterial(b: ByteArray, start: Int, end: Int): Pair<String, Rgb>? {
        var name: String? = null
        var color: Rgb? = null
        var offset = start
        while (offset + 6 <= end) {
            val id = readU16(b, offset)
            val length = readU32(b, offset + 2)
            if (length < 6 || offset + length > end) break
            val cs = offset + 6
            val ce = offset + length
            if (id == MAT_NAME) {
                var ne = cs
                while (ne < ce && b[ne].toInt() != 0) ne++
                name = String(b, cs, ne - cs, Charsets.UTF_8)
            } else if (id == MAT_DIFFUSE) {
                color = readColor(b, cs, ce)
            }
            offset += length
        }
        val n = name ?: return null
        return n to (color ?: Rgb(0.8f, 0.8f, 0.8f))
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
        vertices: MutableList<Float>, faceIndices: MutableList<Int>, groups: MutableList<MaterialGroup>
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
