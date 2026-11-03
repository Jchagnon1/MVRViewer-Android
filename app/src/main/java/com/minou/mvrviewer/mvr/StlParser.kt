package com.minou.mvrviewer.mvr

/**
 * Parseur STL — binaire ET ASCII, Kotlin PUR, lecteurs BORNÉS sur le modèle de
 * [ThreeDSParser] (`readU32` / `readF32` rendent 0 hors limites plutôt que de
 * jeter).
 *
 * DÉTECTION binaire / ASCII : la SEULE règle fiable est la TAILLE — un STL
 * binaire fait exactement `84 + 50 × n` octets (80 d'en-tête + 4 pour le nombre
 * de triangles + 50 par triangle). On ne se fie PAS au mot « solid » en tête :
 * beaucoup d'exporteurs binaires l'écrivent quand même, et un fichier ASCII lu
 * comme binaire donne des millions de triangles de bruit.
 *
 * Un STL ne porte AUCUNE couleur standard ni matériau → tout part en gris ; il
 * n'a pas non plus de sommets partagés (3 sommets par triangle), d'où la
 * DÉDUPLICATION ci-dessous : sans elle un cube de 12 triangles ferait 36
 * sommets, et un maillage de 400 000 triangles en ferait 1,2 million.
 */
object StlParser {

    class Result(val meshes: List<ModelMesh>, val triangles: Int, val truncated: Boolean)

    /** Vrai si la taille du fichier correspond EXACTEMENT à l'en-tête binaire. */
    internal fun isBinary(data: ByteArray): Boolean {
        if (data.size < 84) return false
        val n = readU32(data, 80)
        if (n < 0) return false
        return 84L + 50L * n.toLong() == data.size.toLong()
    }

    fun parse(data: ByteArray, maxTriangles: Int): Result =
        if (isBinary(data)) parseBinary(data, maxTriangles) else parseAscii(data, maxTriangles)

    // ---- Binaire ----

    private fun parseBinary(data: ByteArray, maxTriangles: Int): Result {
        val declared = readU32(data, 80)
        val fits = ((data.size - 84) / 50).coerceAtLeast(0)
        val available = minOf(declared, fits)
        val count = minOf(available, maxTriangles)
        val truncated = available > count
        val dedup = VertexDedup(count * 3)
        val idx = IntBuf(count * 3 + 3)
        var o = 84
        for (t in 0 until count) {
            // 12 octets de normale de face (ignorée : on recalcule des normales
            // lissées, comme partout ailleurs), puis 3 × 12 octets de sommet.
            var p = o + 12
            for (k in 0 until 3) {
                idx.add(dedup.add(readF32(data, p), readF32(data, p + 4), readF32(data, p + 8)))
                p += 12
            }
            o += 50   // + 2 octets d'attribut, sautés
        }
        return finish(dedup, idx, truncated)
    }

    // ---- ASCII ----

    private fun parseAscii(data: ByteArray, maxTriangles: Int): Result {
        val dedup = VertexDedup(4096)
        val idx = IntBuf(4096)
        var tris = 0
        var truncated = false
        var pending = 0            // sommets accumulés dans la facette courante
        val fx = FloatArray(9)
        // Lecture ligne à ligne SUR LES OCTETS (pas de String géante en mémoire).
        var i = 0
        val n = data.size
        val sb = StringBuilder(128)
        while (i <= n) {
            val end = i >= n
            val c = if (end) '\n' else data[i].toInt().toChar()
            i++
            if (c != '\n' && c != '\r') { if (sb.length < 512) sb.append(c); if (!end) continue }
            val line = sb.toString().trim()
            sb.setLength(0)
            if (line.isEmpty()) { if (end) break else continue }
            if (line.startsWith("vertex", ignoreCase = true)) {
                val p = line.split(' ', '\t').filter { it.isNotEmpty() }
                if (p.size >= 4) {
                    val x = p[1].toFloatOrNull(); val y = p[2].toFloatOrNull(); val z = p[3].toFloatOrNull()
                    if (x != null && y != null && z != null && pending < 3) {
                        fx[pending * 3] = x; fx[pending * 3 + 1] = y; fx[pending * 3 + 2] = z
                        pending++
                    }
                }
                if (pending == 3) {
                    if (tris >= maxTriangles) { truncated = true }
                    else {
                        for (k in 0 until 3) idx.add(dedup.add(fx[k * 3], fx[k * 3 + 1], fx[k * 3 + 2]))
                        tris++
                    }
                    pending = 0
                }
            } else if (line.startsWith("endfacet", ignoreCase = true) ||
                line.startsWith("facet", ignoreCase = true)) {
                pending = 0   // facette mal formée : on repart proprement
            }
            if (end) break
        }
        return finish(dedup, idx, truncated)
    }

    private fun finish(dedup: VertexDedup, idx: IntBuf, truncated: Boolean): Result {
        val mesh = buildMesh(dedup.positions(), idx, ObjParser.GRAY)
        val meshes = if (mesh == null) emptyList() else listOf(mesh)
        return Result(meshes, meshes.sumOf { it.triangleCount }, truncated)
    }

    // ---- Déduplication de sommets ----

    /** Clé exacte sur les BITS des trois flottants : deux sommets identiques au
     *  bit près (le cas normal d'un STL, où les faces partagent leurs coins)
     *  fusionnent ; aucune tolérance, donc aucun risque de souder à tort. */
    private data class VKey(val x: Int, val y: Int, val z: Int)

    private class VertexDedup(hint: Int) {
        private val map = HashMap<VKey, Int>(hint.coerceIn(16, 1 shl 20))
        private val buf = FloatBuf(hint.coerceAtLeast(64) * 3)
        fun add(x: Float, y: Float, z: Float): Int {
            val k = VKey(x.toRawBits(), y.toRawBits(), z.toRawBits())
            map[k]?.let { return it }
            val id = buf.n / 3
            buf.add(x); buf.add(y); buf.add(z)
            map[k] = id
            return id
        }
        fun positions(): FloatArray = buf.toArray()
    }

    // ---- Lecteurs bas niveau (little-endian, BORNÉS : 0 hors limite) ----

    private fun readU32(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 4 > b.size) return 0
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }

    private fun readF32(b: ByteArray, o: Int): Float {
        val f = Float.fromBits(readU32(b, o))
        return if (f.isFinite()) f else 0f
    }
}
