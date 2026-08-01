package com.minou.mvrviewer.mvr

/**
 * Parseur PLY (Stanford) — Kotlin PUR. En-tête TOUJOURS ASCII, corps `ascii` ou
 * `binary_little_endian` (le `binary_big_endian`, quasi inexistant, est refusé
 * proprement plutôt que lu de travers).
 *
 * Couvert : `element vertex` avec les propriétés `x/y/z` (+ `red/green/blue`
 * optionnelles) et `element face` avec une liste d'indices. Les propriétés
 * inconnues sont SAUTÉES à leur taille déclarée — c'est ce qui permet de lire un
 * nuage scanné (normales, confiance, intensité…) sans le comprendre.
 *
 * Le modèle de rendu n'a qu'UNE couleur par maillage : quand le fichier porte des
 * couleurs par sommet, on prend leur MOYENNE. C'est une dégradation assumée et
 * visible, préférable à un gris qui ferait croire à une perte.
 */
object PlyParser {

    class Result(val meshes: List<ModelMesh>, val triangles: Int, val truncated: Boolean)

    private enum class Body { ASCII, LE, UNSUPPORTED }

    private class Prop(val name: String, val type: String, val list: Boolean, val countType: String)

    private class Element(val name: String, val count: Int, val props: MutableList<Prop> = mutableListOf())

    /** Taille en octets d'un type scalaire PLY (0 = inconnu). */
    private fun sizeOf(t: String): Int = when (t) {
        "char", "uchar", "int8", "uint8" -> 1
        "short", "ushort", "int16", "uint16" -> 2
        "int", "uint", "int32", "uint32", "float", "float32" -> 4
        "double", "float64" -> 8
        else -> 0
    }

    fun parse(data: ByteArray, maxTriangles: Int): Result {
        // ---- En-tête ASCII jusqu'à « end_header » ----
        var p = 0
        val header = ArrayList<String>()
        val sb = StringBuilder(128)
        var bodyStart = -1
        while (p < data.size) {
            val c = data[p].toInt().toChar()
            p++
            if (c == '\n') {
                val line = sb.toString().trim()
                sb.setLength(0)
                if (line.isNotEmpty()) header.add(line)
                if (line.equals("end_header", true)) { bodyStart = p; break }
                if (header.size > 512) return empty()   // en-tête aberrant
            } else if (c != '\r') {
                if (sb.length < 512) sb.append(c)
            }
        }
        if (bodyStart < 0 || header.isEmpty()) return empty()
        if (!header[0].equals("ply", true)) return empty()

        var body = Body.UNSUPPORTED
        val elements = ArrayList<Element>()
        for (line in header) {
            val t = line.split(' ', '\t').filter { it.isNotEmpty() }
            when (t.getOrNull(0)) {
                "format" -> body = when (t.getOrNull(1)) {
                    "ascii" -> Body.ASCII
                    "binary_little_endian" -> Body.LE
                    else -> Body.UNSUPPORTED
                }
                "element" -> elements.add(Element(t.getOrNull(1).orEmpty(), t.getOrNull(2)?.toIntOrNull() ?: 0))
                "property" -> {
                    val e = elements.lastOrNull() ?: continue
                    if (t.getOrNull(1) == "list") {
                        e.props.add(Prop(t.getOrNull(4).orEmpty(), t.getOrNull(3).orEmpty(), true, t.getOrNull(2).orEmpty()))
                    } else {
                        e.props.add(Prop(t.getOrNull(2).orEmpty(), t.getOrNull(1).orEmpty(), false, ""))
                    }
                }
            }
        }
        if (body == Body.UNSUPPORTED) return empty()

        val pos = FloatBuf(4096)
        val idx = IntBuf(4096)
        var rSum = 0.0; var gSum = 0.0; var bSum = 0.0; var colorCount = 0
        var tris = 0
        var truncated = false

        val cur = Cursor(data, bodyStart)
        val ascii = if (body == Body.ASCII) AsciiTokens(data, bodyStart) else null

        for (e in elements) {
            val isVertex = e.name.equals("vertex", true)
            val isFace = e.name.equals("face", true)
            for (n in 0 until e.count) {
                if (body == Body.ASCII) {
                    if (!ascii!!.nextLine()) return finish(pos, idx, rSum, gSum, bSum, colorCount, truncated)
                } else if (cur.eof()) {
                    return finish(pos, idx, rSum, gSum, bSum, colorCount, truncated)
                }
                var x = 0f; var y = 0f; var z = 0f
                var vr = -1; var vg = -1; var vb = -1
                val faceIdx = IntBuf(8)
                for (prop in e.props) {
                    if (prop.list) {
                        val cnt = if (body == Body.ASCII) (ascii!!.next()?.toIntOrNull() ?: 0)
                                  else cur.readScalar(prop.countType).toInt()
                        val take = cnt.coerceIn(0, 1024)
                        for (k in 0 until cnt) {
                            val v = if (body == Body.ASCII) (ascii!!.next()?.toIntOrNull() ?: 0)
                                    else cur.readScalar(prop.type).toInt()
                            if (k < take && isFace) faceIdx.add(v)
                        }
                    } else {
                        val v = if (body == Body.ASCII) (ascii!!.next()?.toDoubleOrNull() ?: 0.0)
                                else cur.readScalar(prop.type)
                        if (isVertex) when (prop.name) {
                            "x" -> x = v.toFloat()
                            "y" -> y = v.toFloat()
                            "z" -> z = v.toFloat()
                            "red", "r" -> vr = v.toInt()
                            "green", "g" -> vg = v.toInt()
                            "blue", "b" -> vb = v.toInt()
                        }
                    }
                }
                if (isVertex) {
                    pos.add(if (x.isFinite()) x else 0f)
                    pos.add(if (y.isFinite()) y else 0f)
                    pos.add(if (z.isFinite()) z else 0f)
                    if (vr >= 0 && vg >= 0 && vb >= 0) {
                        rSum += vr; gSum += vg; bSum += vb; colorCount++
                    }
                } else if (isFace && faceIdx.n >= 3) {
                    // Triangulation en éventail (les faces PLY sont convexes).
                    var t = 1
                    while (t < faceIdx.n - 1) {
                        if (tris >= maxTriangles) { truncated = true; break }
                        idx.add(faceIdx.get(0)); idx.add(faceIdx.get(t)); idx.add(faceIdx.get(t + 1))
                        tris++
                        t++
                    }
                }
            }
        }
        return finish(pos, idx, rSum, gSum, bSum, colorCount, truncated)
    }

    private fun empty() = Result(emptyList(), 0, false)

    private fun finish(
        pos: FloatBuf, idx: IntBuf,
        rSum: Double, gSum: Double, bSum: Double, colorCount: Int,
        truncated: Boolean
    ): Result {
        val color = if (colorCount > 0) {
            fun c(v: Double) = (v / colorCount).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (c(rSum) shl 16) or (c(gSum) shl 8) or c(bSum)
        } else ObjParser.GRAY
        val mesh = buildMesh(pos.toArray(), idx, color)
        val meshes = if (mesh == null) emptyList() else listOf(mesh)
        return Result(meshes, meshes.sumOf { it.triangleCount }, truncated)
    }

    /** Lecteur binaire little-endian BORNÉ (0 hors limites, jamais d'exception). */
    private class Cursor(val b: ByteArray, var o: Int) {
        fun eof() = o >= b.size
        private fun u8(): Int { if (o >= b.size) { o++; return 0 }; return b[o++].toInt() and 0xFF }
        private fun u16(): Int = u8() or (u8() shl 8)
        private fun u32(): Int = u16() or (u16() shl 16)
        fun readScalar(type: String): Double = when (type) {
            "char", "int8" -> u8().toByte().toDouble()
            "uchar", "uint8" -> u8().toDouble()
            "short", "int16" -> u16().toShort().toDouble()
            "ushort", "uint16" -> u16().toDouble()
            "int", "int32" -> u32().toDouble()
            "uint", "uint32" -> (u32().toLong() and 0xFFFFFFFFL).toDouble()
            "float", "float32" -> Float.fromBits(u32()).let { if (it.isFinite()) it.toDouble() else 0.0 }
            "double", "float64" -> {
                var bits = 0L
                for (k in 0 until 8) bits = bits or (u8().toLong() shl (8 * k))
                Double.fromBits(bits).let { if (it.isFinite()) it else 0.0 }
            }
            // Type inconnu : on ne peut pas avancer à l'aveugle — on saute la
            // taille annoncée si elle est connue, sinon rien (le fichier sera
            // simplement incomplet, jamais un crash).
            else -> { repeat(sizeOf(type)) { u8() }; 0.0 }
        }
    }

    /** Découpage en jetons ligne par ligne pour le corps ASCII. */
    private class AsciiTokens(val b: ByteArray, var o: Int) {
        private var tokens: List<String> = emptyList()
        private var ti = 0
        fun nextLine(): Boolean {
            while (o < b.size) {
                val sb = StringBuilder(64)
                while (o < b.size) {
                    val c = b[o].toInt().toChar()
                    o++
                    if (c == '\n') break
                    if (c != '\r' && sb.length < 4096) sb.append(c)
                }
                val line = sb.toString().trim()
                if (line.isEmpty()) continue
                tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
                ti = 0
                return true
            }
            return false
        }
        fun next(): String? = tokens.getOrNull(ti++)
    }
}
