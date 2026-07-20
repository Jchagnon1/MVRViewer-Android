package com.minou.mvrviewer.mvr

import java.io.BufferedInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Lecteur DXF — ASCII **et BINAIRE** — en STREAMING (portage partiel de
 * DXFParser iOS). Le binaire est le format « DXF ACAD 2018 » qu'exportent
 * beaucoup de logiciels (le fichier V&B fait 437 Mo) : mêmes paires (code,
 * valeur) que l'ASCII, encodées en binaire (rien à voir avec le DWG). Le flux
 * est lu paire par paire via une machine à états ; seuls l'entité courante et
 * les corps de blocs (plafonnés) sont bufferisés → mémoire bornée quel que soit
 * le poids du fichier.
 *
 * LINE, LWPOLYLINE (arcs de bulge), POLYLINE/VERTEX (dont polyface mesh &
 * grille M×N — arêtes réelles, pas de zigzags fantômes), CIRCLE, ARC, ELLIPSE,
 * SPLINE (de Boor), BLOCKS + INSERT, $INSUNITS. Vue de dessus (Z ignoré).
 * 3DFACE/SOLID/HATCH/TEXT et les couleurs par calque = à venir.
 */
object DxfParser {

    private const val MAX_KEPT_SEGMENTS = 5_000_000
    private const val MAX_STORED_BLOCK_PAIRS = 5_000_000
    private val SENTINEL = "AutoCAD Binary DXF\r\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x1A, 0x00)

    /** Paire (code, valeur typée). `num` non-null = valeur numérique. */
    class Pair(val code: Int, val str: String, val num: Double?) {
        val double: Double? get() = num ?: str.toDoubleOrNull()
        val int: Int? get() = num?.toInt() ?: str.trim().toIntOrNull()
    }

    fun parse(input: InputStream): DxfPlan? {
        val bis = BufferedInputStream(input, 1 shl 16)
        bis.mark(64)
        val head = ByteArray(SENTINEL.size)
        val n = readFully(bis, head)
        val isBin = n == SENTINEL.size && head.contentEquals(SENTINEL)
        bis.reset()
        val machine = Machine()
        try {
            if (isBin) tokenizeBinary(bis, machine) else tokenizeAscii(bis, machine)
        } catch (_: Exception) {
            // Flux tronqué/corrompu : on garde ce qui a été lu.
        }
        return machine.finish()
    }

    // ---- Tokenizer ASCII (lignes : code / valeur en alternance) ----

    private fun tokenizeAscii(input: InputStream, machine: Machine) {
        val reader = input.bufferedReader(Charsets.ISO_8859_1)
        var pendingCode: Int? = null
        while (true) {
            val line = reader.readLine() ?: break
            val s = line.trim()
            val pc = pendingCode
            if (pc == null) pendingCode = s.toIntOrNull()
            else { pendingCode = null; machine.consume(Pair(pc, s, null)) }
        }
    }

    // ---- Tokenizer BINAIRE (code 16 bits LE, valeur typée par plage de code) ----

    private enum class Kind { STRING, DOUBLE, I16, I32, I64, BOOL, CHUNK }

    private fun kindFor(code: Int): Kind? = when (code) {
        in 0..9 -> Kind.STRING
        in 10..59 -> Kind.DOUBLE
        in 60..79 -> Kind.I16
        in 90..99 -> Kind.I32
        in 100..102, 105 -> Kind.STRING
        in 110..149 -> Kind.DOUBLE
        in 160..169 -> Kind.I64
        in 170..179 -> Kind.I16
        in 210..239 -> Kind.DOUBLE
        in 270..289 -> Kind.I16
        in 290..299 -> Kind.BOOL
        in 300..309 -> Kind.STRING
        in 310..319 -> Kind.CHUNK
        in 320..369 -> Kind.STRING
        in 370..389 -> Kind.I16
        in 390..399 -> Kind.STRING
        in 400..409 -> Kind.I16
        in 410..419 -> Kind.STRING
        in 420..429 -> Kind.I32
        in 430..439 -> Kind.STRING
        in 440..459 -> Kind.I32
        in 460..469 -> Kind.DOUBLE
        in 470..481 -> Kind.STRING
        999 -> Kind.STRING
        in 1000..1003 -> Kind.STRING
        1004 -> Kind.CHUNK
        in 1005..1009 -> Kind.STRING
        in 1010..1059 -> Kind.DOUBLE
        in 1060..1070 -> Kind.I16
        1071 -> Kind.I32
        else -> null
    }

    private fun tokenizeBinary(input: InputStream, machine: Machine) {
        // Sauter la sentinelle.
        val skip = ByteArray(SENTINEL.size); readFully(input, skip)
        val buf = ByteArray(8)
        while (true) {
            val c0 = input.read(); if (c0 < 0) return
            val c1 = input.read(); if (c1 < 0) return
            val code = c0 or (c1 shl 8)
            val kind = kindFor(code) ?: return   // code illégal → flux corrompu
            when (kind) {
                Kind.STRING -> {
                    val s = readCString(input) ?: return
                    machine.consume(Pair(code, s, null))
                }
                Kind.DOUBLE -> {
                    if (readFully(input, buf, 8) != 8) return
                    val bits = leLong(buf, 8)
                    machine.consume(Pair(code, "", Double.fromBits(bits)))
                }
                Kind.I16 -> { if (readFully(input, buf, 2) != 2) return; machine.consume(Pair(code, "", leLong(buf, 2).toShort().toDouble())) }
                Kind.I32 -> { if (readFully(input, buf, 4) != 4) return; machine.consume(Pair(code, "", leLong(buf, 4).toInt().toDouble())) }
                Kind.I64 -> { if (readFully(input, buf, 8) != 8) return; machine.consume(Pair(code, "", leLong(buf, 8).toDouble())) }
                Kind.BOOL -> { val b = input.read(); if (b < 0) return; machine.consume(Pair(code, "", b.toDouble())) }
                Kind.CHUNK -> {
                    val len = input.read(); if (len < 0) return
                    val chunk = ByteArray(len); if (readFully(input, chunk, len) != len) return
                    machine.consume(Pair(code, "", null))
                }
            }
        }
    }

    private fun readCString(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun leLong(b: ByteArray, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = v or ((b[k].toLong() and 0xFF) shl (8 * k))
        return v
    }

    private fun readFully(input: InputStream, dst: ByteArray, len: Int = dst.size): Int {
        var off = 0
        while (off < len) {
            val r = input.read(dst, off, len - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    // ---- Transformée affine 2D (INSERT / unités) ----

    private class Affine(
        val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0,
        val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0
    ) {
        fun apply(x: Double, y: Double) = doubleArrayOf(a * x + c * y + e, b * x + d * y + f)
        /** R.apply(p) == other.apply(this.apply(p)) : this d'abord, puis other. */
        fun then(o: Affine) = Affine(
            o.a * a + o.c * b, o.b * a + o.d * b,
            o.a * c + o.c * d, o.b * c + o.d * d,
            o.a * e + o.c * f + o.e, o.b * e + o.d * f + o.f
        )
        companion object {
            fun translation(x: Double, y: Double) = Affine(1.0, 0.0, 0.0, 1.0, x, y)
            fun scale(sx: Double, sy: Double) = Affine(sx, 0.0, 0.0, sy, 0.0, 0.0)
            fun rotation(rad: Double) = Affine(cos(rad), sin(rad), -sin(rad), cos(rad), 0.0, 0.0)
        }
    }

    private class Out {
        val polylines = ArrayList<DxfPolyline>()
        var kept = 0
        var skipped = 0
        val layerCounts = HashMap<String, Int>()
        val layerColors = HashMap<String, Int>()   // calque → 0xRRGGBB (BYLAYER)
        val hiddenLayers = HashSet<String>()        // calques éteints/gelés
    }

    // ---- Machine à états (streaming) ----

    private class Block(val baseX: Double, val baseY: Double, val pairs: List<Pair>)

    /** Sommet POLYLINE brut : position + flags (70) + bulge (42) + indices de face. */
    private class RawVertex(val x: Double?, val y: Double?, val flags: Int, val bulge: Double, val face: IntArray)

    private class Machine {
        private var section = 0 // 0 none,1 header,2 tables,3 blocks,4 entities,5 skip
        private var awaitingSectionName = false
        private var awaitingInsunits = false
        private var unitScale = 1.0
        private var unitLabel = "inconnue (mm supposés)"

        private val blocks = HashMap<String, Block>()
        private var storedBlockPairs = 0
        private var curBlockName = ""
        private var curBlockBaseX = 0.0
        private var curBlockBaseY = 0.0
        private var curBlockPairs = ArrayList<Pair>()
        private var inBlockBody = false

        // Table LAYER (section TABLES) : couleurs BYLAYER + calques éteints/gelés.
        private var awaitingTableName = false
        private var inLayerTable = false
        private var inLayerEntry = false
        private var curLayerName = ""
        private var curLayerAci: Int? = null
        private var curLayerTrue: Int? = null
        private var curLayerFlags = 0

        private val chunk = ArrayList<Pair>()
        private val out = Out()
        private var sawAny = false

        private val structural = hashSetOf("SECTION", "ENDSEC", "TABLE", "ENDTAB",
            "BLOCK", "ENDBLK", "EOF", "SEQEND", "VERTEX", "CLASS", "DICTIONARY", "BLOCK_RECORD")

        fun consume(p: Pair) {
            sawAny = true
            if (p.code == 0) {
                val t = p.str
                if (t == "SECTION") { flushChunk(); finalizeBlock(); awaitingSectionName = true; section = 5; return }
                if (t == "ENDSEC") { flushChunk(); finalizeBlock(); section = 0; return }
                if (t == "EOF") { flushChunk(); finalizeBlock(); return }
            }
            if (awaitingSectionName) {
                if (p.code == 2) {
                    section = when (p.str) {
                        "HEADER" -> 1; "TABLES" -> 2; "BLOCKS" -> 3; "ENTITIES" -> 4; else -> 5
                    }
                    awaitingSectionName = false
                }
                return
            }
            when (section) {
                1 -> {
                    if (p.code == 9) awaitingInsunits = (p.str == "\$INSUNITS")
                    else if (awaitingInsunits && p.code == 70) {
                        when (p.int) {
                            1 -> { unitScale = 25.4; unitLabel = "pouces" }
                            2 -> { unitScale = 304.8; unitLabel = "pieds" }
                            4 -> { unitScale = 1.0; unitLabel = "mm" }
                            5 -> { unitScale = 10.0; unitLabel = "cm" }
                            6 -> { unitScale = 1000.0; unitLabel = "m" }
                            13 -> { unitScale = 0.001; unitLabel = "micromètres" }
                        }
                        awaitingInsunits = false
                    }
                }
                2 -> consumeInTables(p)
                3 -> consumeInBlocks(p)
                4 -> consumeInEntities(p)
            }
        }

        // Table LAYER : accumule nom (2) + ACI (62) + true color (420) + flags (70).
        private fun consumeInTables(p: Pair) {
            if (p.code == 0) {
                when (p.str) {
                    "TABLE" -> { flushLayer(); awaitingTableName = true; inLayerTable = false }
                    "LAYER" -> if (inLayerTable) {
                        flushLayer()
                        inLayerEntry = true; curLayerName = ""
                        curLayerAci = null; curLayerTrue = null; curLayerFlags = 0
                    }
                    "ENDTAB" -> { flushLayer(); inLayerTable = false }
                }
                return
            }
            if (awaitingTableName && p.code == 2) { inLayerTable = (p.str == "LAYER"); awaitingTableName = false; return }
            if (inLayerEntry) when (p.code) {
                2 -> if (curLayerName.isEmpty()) curLayerName = p.str
                62 -> curLayerAci = p.int
                420 -> curLayerTrue = p.int
                70 -> curLayerFlags = p.int ?: 0
            }
        }

        private fun flushLayer() {
            if (inLayerEntry && curLayerName.isNotEmpty()) {
                out.layerColors[curLayerName] = layerColorFrom(curLayerAci, curLayerTrue)
                // ACI négatif = calque éteint ; bit 1 des flags = gelé → masqué par défaut.
                if ((curLayerAci ?: 7) < 0 || (curLayerFlags and 1) != 0) out.hiddenLayers.add(curLayerName)
            }
            inLayerEntry = false
            curLayerName = ""; curLayerAci = null; curLayerTrue = null; curLayerFlags = 0
        }

        private fun consumeInBlocks(p: Pair) {
            if (p.code == 0) {
                when (p.str) {
                    "BLOCK" -> { finalizeBlock(); inBlockBody = true; curBlockName = ""; curBlockBaseX = 0.0; curBlockBaseY = 0.0; curBlockPairs = ArrayList() }
                    "ENDBLK" -> finalizeBlock()
                    else -> if (inBlockBody && storedBlockPairs < MAX_STORED_BLOCK_PAIRS) { curBlockPairs.add(p); storedBlockPairs++ }
                }
                return
            }
            if (!inBlockBody) return
            when (p.code) {
                2 -> if (curBlockName.isEmpty()) curBlockName = p.str
                10 -> curBlockBaseX = p.double ?: 0.0
                20 -> curBlockBaseY = p.double ?: 0.0
            }
            if (curBlockPairs.isNotEmpty() || curBlockName.isNotEmpty()) {
                if (storedBlockPairs < MAX_STORED_BLOCK_PAIRS) { curBlockPairs.add(p); storedBlockPairs++ }
            }
        }

        private fun finalizeBlock() {
            if (inBlockBody && curBlockName.isNotEmpty()) {
                blocks[curBlockName] = Block(curBlockBaseX, curBlockBaseY, curBlockPairs)
            }
            inBlockBody = false
            curBlockPairs = ArrayList()
            curBlockName = ""
        }

        private fun consumeInEntities(p: Pair) {
            if (p.code == 0) {
                val t = p.str
                if (t == "VERTEX" || t == "SEQEND") { chunk.add(p); return }
                flushChunk(); chunk.add(p); return
            }
            if (chunk.isNotEmpty()) chunk.add(p)
        }

        private fun flushChunk() {
            if (chunk.isEmpty()) return
            emit(chunk, Affine.scale(unitScale, unitScale), 0)
            chunk.clear()
        }

        fun finish(): DxfPlan? {
            flushChunk(); finalizeBlock(); flushLayer()
            if (out.polylines.isEmpty()) return null
            var mnX = Float.MAX_VALUE; var mnY = Float.MAX_VALUE
            var mxX = -Float.MAX_VALUE; var mxY = -Float.MAX_VALUE
            for (pl in out.polylines) {
                val pts = pl.points; var i = 0
                while (i < pts.size) {
                    val x = pts[i]; val y = pts[i + 1]; i += 2
                    if (x < mnX) mnX = x; if (x > mxX) mxX = x
                    if (y < mnY) mnY = y; if (y > mxY) mxY = y
                }
            }
            if (!mnX.isFinite() || !mxX.isFinite()) return null
            return DxfPlan(out.polylines, mnX, mnY, mxX, mxY, unitLabel,
                out.kept, out.layerCounts, out.skipped,
                layerColors = out.layerColors, defaultHiddenLayers = out.hiddenLayers)
        }

        // ---- Émission des entités ----

        private fun emit(pairs: List<Pair>, t: Affine, depth: Int,
                         blockColor: Int? = null, inheritedLayer: String? = null) {
            if (depth >= 8) return
            var i = 0
            while (i < pairs.size) {
                if (pairs[i].code != 0) { i++; continue }
                val type = pairs[i].str
                var j = i + 1
                val g = ArrayList<Pair>()
                while (j < pairs.size && pairs[j].code != 0) { g.add(pairs[j]); j++ }
                val layer = str(g, 8) ?: ""
                if (int(g, 67) == 1 || int(g, 60) == 1) { i = j; continue }
                val color = resolveColor(g, layer, blockColor, inheritedLayer, out.layerColors)
                if (color == null) { i = j; continue } // entité éteinte (ACI négatif)
                when (type) {
                    "LINE" -> {
                        val x1 = dbl(g, 10); val y1 = dbl(g, 20); val x2 = dbl(g, 11); val y2 = dbl(g, 21)
                        if (x1 != null && y1 != null && x2 != null && y2 != null)
                            addPolyline(doubleArrayOf(x1, y1, x2, y2), false, layer, t, color)
                    }
                    "LWPOLYLINE" -> {
                        val closed = (int(g, 70) ?: 0) and 1 == 1
                        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val bulges = ArrayList<Double>()
                        var pendingX: Double? = null
                        for (pair in g) when (pair.code) {
                            10 -> pendingX = pair.double
                            20 -> { val x = pendingX; val y = pair.double; if (x != null && y != null) { xs.add(x); ys.add(y); bulges.add(0.0); pendingX = null } }
                            42 -> if (bulges.isNotEmpty()) pair.double?.let { bulges[bulges.size - 1] = it }
                        }
                        addPolyline(tessellateBulges(xs, ys, bulges, closed), closed, layer, t, color)
                    }
                    "POLYLINE" -> {
                        // Le code 70 du POLYLINE change RADICALEMENT la sémantique :
                        // bit 64 = polyface mesh (les sommets ne sont PAS une suite à
                        // relier — des FACE records donnent la connectivité), bit 16 =
                        // grille M×N. Tout relier fabriquait des zigzags fantômes.
                        val plFlags = int(g, 70) ?: 0
                        // Sommets VERTEX suivants (jusqu'au SEQEND) : pos + flags (70)
                        // + bulge (42) + indices de face (71-74).
                        val verts = ArrayList<RawVertex>()
                        var k = j
                        while (k < pairs.size && pairs[k].code == 0 && pairs[k].str == "VERTEX") {
                            var m = k + 1
                            var vx: Double? = null; var vy: Double? = null
                            var vf = 0; var vb = 0.0; val face = ArrayList<Int>()
                            while (m < pairs.size && pairs[m].code != 0) {
                                when (pairs[m].code) {
                                    10 -> vx = pairs[m].double
                                    20 -> vy = pairs[m].double
                                    42 -> vb = pairs[m].double ?: 0.0
                                    70 -> vf = pairs[m].int ?: 0
                                    71, 72, 73, 74 -> pairs[m].int?.let { if (it != 0) face.add(it) }
                                }
                                m++
                            }
                            verts.add(RawVertex(vx, vy, vf, vb, face.toIntArray()))
                            k = m
                        }
                        if (k < pairs.size && pairs[k].code == 0 && pairs[k].str == "SEQEND") {
                            var m = k + 1
                            while (m < pairs.size && pairs[m].code != 0) m++
                            k = m
                        }

                        if (plFlags and 64 != 0) {
                            // POLYFACE MESH : sommets géométrie (flag 64) + faces
                            // (flag 128, coords sans sens). Indices 1-based ;
                            // NÉGATIF = arête invisible. Arêtes dé-doublonnées.
                            val geo = verts.filter { it.flags and 64 != 0 && it.x != null && it.y != null }
                            val seen = HashSet<Long>()
                            for (v in verts) {
                                if (v.flags and 128 == 0 || v.flags and 64 != 0 || v.face.isEmpty()) continue
                                val c = v.face
                                for (e in c.indices) {
                                    val raw1 = c[e]; val raw2 = c[(e + 1) % c.size]
                                    if (raw1 <= 0) continue // arête invisible / nulle
                                    val i1 = abs(raw1) - 1; val i2 = abs(raw2) - 1
                                    if (i1 < 0 || i1 >= geo.size || i2 < 0 || i2 >= geo.size || i1 == i2) continue
                                    val key = (minOf(i1, i2).toLong() shl 32) or maxOf(i1, i2).toLong()
                                    if (!seen.add(key)) continue
                                    val a = geo[i1]; val b = geo[i2]
                                    addPolyline(doubleArrayOf(a.x!!, a.y!!, b.x!!, b.y!!), false, layer, t, color)
                                }
                            }
                        } else if (plFlags and 16 != 0) {
                            // GRILLE M×N : lignes + colonnes (fermeture selon bits 1/32).
                            val mCount = int(g, 71) ?: 0; val nCount = int(g, 72) ?: 0
                            val geo = verts.mapNotNull { if (it.x != null && it.y != null) doubleArrayOf(it.x, it.y) else null }
                            if (mCount >= 2 && nCount >= 2 && geo.size >= mCount * nCount) {
                                for (row in 0 until mCount) {
                                    val flat = DoubleArray(nCount * 2)
                                    for (cIdx in 0 until nCount) { val p = geo[row * nCount + cIdx]; flat[cIdx * 2] = p[0]; flat[cIdx * 2 + 1] = p[1] }
                                    addPolyline(flat, plFlags and 32 != 0, layer, t, color)
                                }
                                for (col in 0 until nCount) {
                                    val flat = DoubleArray(mCount * 2)
                                    for (rIdx in 0 until mCount) { val p = geo[rIdx * nCount + col]; flat[rIdx * 2] = p[0]; flat[rIdx * 2 + 1] = p[1] }
                                    addPolyline(flat, plFlags and 1 != 0, layer, t, color)
                                }
                            }
                        } else {
                            // Classique : écarter la charpente de spline (flag 16) et
                            // les FACE records orphelins (flag 128 sans flag 64).
                            val closed = plFlags and 1 == 1
                            val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val bulges = ArrayList<Double>()
                            for (v in verts) {
                                if (v.flags and 16 != 0) continue
                                if (v.flags and 128 != 0 && v.flags and 64 == 0) continue
                                if (v.x != null && v.y != null) { xs.add(v.x); ys.add(v.y); bulges.add(v.bulge) }
                            }
                            addPolyline(tessellateBulges(xs, ys, bulges, closed), closed, layer, t, color)
                        }
                        j = k
                    }
                    "CIRCLE" -> {
                        val cx = dbl(g, 10); val cy = dbl(g, 20); val r = dbl(g, 40)
                        if (cx != null && cy != null && r != null && r > 0) addPolyline(circlePoints(cx, cy, r), true, layer, t, color)
                    }
                    "ARC" -> {
                        val cx = dbl(g, 10); val cy = dbl(g, 20); val r = dbl(g, 40)
                        if (cx != null && cy != null && r != null && r > 0)
                            addPolyline(arcPoints(cx, cy, r, dbl(g, 50) ?: 0.0, dbl(g, 51) ?: 360.0), false, layer, t, color)
                    }
                    "ELLIPSE" -> {
                        val cx = dbl(g, 10); val cy = dbl(g, 20)
                        val mx = dbl(g, 11); val my = dbl(g, 21); val ratio = dbl(g, 40)
                        if (cx != null && cy != null && mx != null && my != null && ratio != null) {
                            val a0 = dbl(g, 41) ?: 0.0; val a1 = dbl(g, 42) ?: (2 * Math.PI)
                            addPolyline(ellipsePoints(cx, cy, mx, my, ratio, a0, a1),
                                abs((a1 - a0) - 2 * Math.PI) < 1e-6, layer, t, color)
                        }
                    }
                    "SPLINE" -> {
                        // Points de LISSAGE (11/21) si présents ; sinon ÉVALUATION de
                        // la B-spline (de Boor) depuis points de contrôle (10/20) +
                        // nœuds (40) + degré (71). Relier les points de contrôle
                        // dessinait le polygone de contrôle, pas la courbe.
                        val fitX = ArrayList<Double>(); val fitY = ArrayList<Double>()
                        val ctrlX = ArrayList<Double>(); val ctrlY = ArrayList<Double>()
                        val knots = ArrayList<Double>(); val weights = ArrayList<Double>()
                        var pxp: Double? = null; var cxp: Double? = null
                        for (pair in g) when (pair.code) {
                            11 -> pxp = pair.double
                            21 -> { val x = pxp; val y = pair.double; if (x != null && y != null) { fitX.add(x); fitY.add(y); pxp = null } }
                            10 -> cxp = pair.double
                            20 -> { val x = cxp; val y = pair.double; if (x != null && y != null) { ctrlX.add(x); ctrlY.add(y); cxp = null } }
                            40 -> pair.double?.let { knots.add(it) }
                            41 -> pair.double?.let { weights.add(it) }
                        }
                        val closed = (int(g, 70) ?: 0) and 1 == 1
                        val degree = int(g, 71) ?: 3
                        val pts = if (fitX.isNotEmpty()) {
                            val flat = DoubleArray(fitX.size * 2)
                            for (v in fitX.indices) { flat[v * 2] = fitX[v]; flat[v * 2 + 1] = fitY[v] }
                            flat
                        } else {
                            sampleBSpline(ctrlX.toDoubleArray(), ctrlY.toDoubleArray(), knots.toDoubleArray(),
                                degree, if (weights.size == ctrlX.size) weights.toDoubleArray() else null)
                        }
                        addPolyline(pts, closed, layer, t, color)
                    }
                    "INSERT" -> {
                        val name = str(g, 2); val block = if (name != null) blocks[name] else null
                        if (block != null) {
                            val ix = dbl(g, 10) ?: 0.0; val iy = dbl(g, 20) ?: 0.0
                            val sx = dbl(g, 41) ?: 1.0; val sy = dbl(g, 42) ?: 1.0
                            val rot = Math.toRadians(dbl(g, 50) ?: 0.0)
                            val cols = maxOf(1, int(g, 70) ?: 1); val rows = maxOf(1, int(g, 71) ?: 1)
                            val colSp = dbl(g, 44) ?: 0.0; val rowSp = dbl(g, 45) ?: 0.0
                            for (cc in 0 until cols) for (rr in 0 until rows) {
                                var l = Affine.translation(-block.baseX, -block.baseY)
                                l = l.then(Affine.scale(sx, sy))
                                l = l.then(Affine.rotation(rot))
                                l = l.then(Affine.translation(ix + cc * colSp, iy + rr * rowSp))
                                emit(block.pairs, l.then(t), depth + 1, color, layer)
                            }
                        }
                    }
                }
                i = j
            }
        }

        private fun addPolyline(pts: DoubleArray, closed: Boolean, layer: String, t: Affine, color: Int = 0xFFFFFF) {
            val n = pts.size / 2
            if (n < 2) return
            val segments = n - 1 + if (closed && n > 2) 1 else 0
            out.layerCounts[layer] = (out.layerCounts[layer] ?: 0) + segments
            if (out.kept + segments > MAX_KEPT_SEGMENTS) { out.skipped += segments; return }
            val flat = FloatArray(n * 2)
            var i = 0
            while (i < n) {
                val w = t.apply(pts[i * 2], pts[i * 2 + 1])
                val fx = w[0].toFloat(); val fy = w[1].toFloat()
                if (!fx.isFinite() || !fy.isFinite()) return
                flat[i * 2] = fx; flat[i * 2 + 1] = fy; i++
            }
            out.polylines.add(DxfPolyline(flat, closed, layer, color))
            out.kept += segments
        }
    }

    // ---- Géométrie ----

    private fun tessellateBulges(xs: List<Double>, ys: List<Double>, bulges: List<Double>, closed: Boolean): DoubleArray {
        val n = xs.size
        if (n < 2 || bulges.none { it != 0.0 }) {
            val flat = DoubleArray(n * 2)
            for (i in 0 until n) { flat[i * 2] = xs[i]; flat[i * 2 + 1] = ys[i] }
            return flat
        }
        val out = ArrayList<Double>(n * 4)
        out.add(xs[0]); out.add(ys[0])
        val edges = if (closed) n else n - 1
        for (e in 0 until edges) {
            val i1 = e; val i2 = (e + 1) % n
            val p1x = xs[i1]; val p1y = ys[i1]; val p2x = xs[i2]; val p2y = ys[i2]
            val b = if (e < bulges.size) bulges[e] else 0.0
            val closingEdge = closed && e == edges - 1
            if (b == 0.0 || (p1x == p2x && p1y == p2y)) { if (!closingEdge) { out.add(p2x); out.add(p2y) }; continue }
            val theta = 4 * atan(b)
            val chx = p2x - p1x; val chy = p2y - p1y
            val c = hypot(chx, chy)
            val midx = (p1x + p2x) / 2; val midy = (p1y + p2y) / 2
            val nx = -chy / c; val ny = chx / c
            val cx = midx + nx * (c / 2) / tan(theta / 2)
            val cy = midy + ny * (c / 2) / tan(theta / 2)
            val r = c / (2 * abs(sin(theta / 2)))
            val a1 = atan2(p1y - cy, p1x - cx)
            val steps = minOf(48, maxOf(2, ceil(abs(theta) / 0.13).toInt()))
            for (s in 1..steps) {
                if (s == steps) { if (!closingEdge) { out.add(p2x); out.add(p2y) } }
                else { val a = a1 + theta * s / steps; out.add(cx + r * cos(a)); out.add(cy + r * sin(a)) }
            }
        }
        return out.toDoubleArray()
    }

    /** Ellipse : `maj` = extrémité du grand axe RELATIVE au centre, `ratio` =
     *  petit/grand axe, `t0/t1` = paramètres (radians, depuis le grand axe). */
    private fun ellipsePoints(cx: Double, cy: Double, majX: Double, majY: Double,
                              ratio: Double, t0: Double, t1: Double): DoubleArray {
        val phi = atan2(majY, majX)
        val majorLen = hypot(majX, majY)
        val minorLen = majorLen * ratio
        var sweep = t1 - t0
        while (sweep <= 1e-9) sweep += 2 * Math.PI
        val n = maxOf(4, (sweep / (2 * Math.PI) * 72).toInt())
        val cphi = cos(phi); val sphi = sin(phi)
        val out = DoubleArray((n + 1) * 2)
        for (k in 0..n) {
            val th = t0 + sweep * k / n
            val ex = majorLen * cos(th); val ey = minorLen * sin(th)
            out[k * 2] = cx + ex * cphi - ey * sphi
            out[k * 2 + 1] = cy + ex * sphi + ey * cphi
        }
        return out
    }

    /** Échantillonne une B-spline (de Boor), rationnelle si `weights` fourni.
     *  Renvoie les points de contrôle à plat en repli si l'entrée est incohérente. */
    private fun sampleBSpline(ctrlX: DoubleArray, ctrlY: DoubleArray, knots: DoubleArray,
                             degree: Int, weights: DoubleArray?): DoubleArray {
        val p = maxOf(1, degree)
        val n = ctrlX.size
        fun ctrlFlat(): DoubleArray {
            val f = DoubleArray(n * 2)
            for (i in 0 until n) { f[i * 2] = ctrlX[i]; f[i * 2 + 1] = ctrlY[i] }
            return f
        }
        if (n <= p || knots.size != n + p + 1) return ctrlFlat()
        val uMin = knots[p]; val uMax = knots[n]
        if (uMax <= uMin) return ctrlFlat()
        val hx = DoubleArray(n); val hy = DoubleArray(n); val hw = DoubleArray(n)
        for (i in 0 until n) { val w = weights?.get(i) ?: 1.0; hx[i] = ctrlX[i] * w; hy[i] = ctrlY[i] * w; hw[i] = w }
        val sampleCount = minOf(96, maxOf(16, n * 6))
        val out = ArrayList<Double>((sampleCount + 1) * 2)
        for (s in 0..sampleCount) {
            val u = uMin + (uMax - uMin) * s / sampleCount
            var k = p
            while (k < n - 1 && u >= knots[k + 1]) k++
            val dx = DoubleArray(p + 1); val dy = DoubleArray(p + 1); val dw = DoubleArray(p + 1)
            for (a in 0..p) { dx[a] = hx[k - p + a]; dy[a] = hy[k - p + a]; dw[a] = hw[k - p + a] }
            for (r in 1..p) {
                var jj = p
                while (jj >= r) {
                    val i = k - p + jj
                    val denom = knots[i + p - r + 1] - knots[i]
                    val alpha = if (denom > 0) (u - knots[i]) / denom else 0.0
                    dx[jj] = dx[jj - 1] * (1 - alpha) + dx[jj] * alpha
                    dy[jj] = dy[jj - 1] * (1 - alpha) + dy[jj] * alpha
                    dw[jj] = dw[jj - 1] * (1 - alpha) + dw[jj] * alpha
                    jj--
                }
            }
            val w = dw[p]
            if (w != 0.0 && dx[p].isFinite() && dy[p].isFinite()) { out.add(dx[p] / w); out.add(dy[p] / w) }
        }
        return if (out.size >= 4) out.toDoubleArray() else ctrlFlat()
    }

    private fun circlePoints(cx: Double, cy: Double, r: Double): DoubleArray = arcPoints(cx, cy, r, 0.0, 360.0)

    private fun arcPoints(cx: Double, cy: Double, r: Double, startDeg: Double, endDeg: Double): DoubleArray {
        var sweep = endDeg - startDeg
        if (sweep <= 0) sweep += 360.0
        val steps = minOf(180, maxOf(4, ceil(sweep / 5.0).toInt()))
        val out = DoubleArray((steps + 1) * 2)
        for (s in 0..steps) {
            val a = Math.toRadians(startDeg + sweep * s / steps)
            out[s * 2] = cx + r * cos(a); out[s * 2 + 1] = cy + r * sin(a)
        }
        return out
    }

    // ---- Couleurs DXF (ACI + résolution BYLAYER/BYBLOCK) ----

    private fun aciRGB(idx: Int): Int = if (idx in ACI_PALETTE.indices) ACI_PALETTE[idx] else 0x808080

    /** Couleur d'un calque depuis son ACI/true-color (BYLAYER). */
    private fun layerColorFrom(aci: Int?, trueColor: Int?): Int {
        if (trueColor != null) return trueColor and 0xFFFFFF
        return aciRGB(kotlin.math.abs(aci ?: 7))
    }

    /** Couleur RÉSOLUE d'une entité : true color (420) > ACI (62) > BYLAYER.
     *  0 = BYBLOCK (couleur de l'INSERT), 256 = BYLAYER, négatif = éteint (null). */
    private fun resolveColor(g: List<Pair>, layer: String, blockColor: Int?,
                             inheritedLayer: String?, layerColors: Map<String, Int>): Int? {
        fun layerColor(): Int =
            layerColors[layer] ?: inheritedLayer?.let { layerColors[it] } ?: aciRGB(7)
        val tc = int(g, 420)
        if (tc != null) return tc and 0xFFFFFF
        val aci = int(g, 62) ?: return layerColor()
        return when {
            aci == 0 -> blockColor ?: layerColor()
            aci == 256 -> layerColor()
            aci in 1..255 -> aciRGB(aci)
            else -> null // négatif : entité éteinte
        }
    }

    /** Palette ACI AutoCAD standard (index 0-255), extraite d'ezdxf (fidèle à AutoCAD). */
    private val ACI_PALETTE = intArrayOf(
        0x000000, 0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF,
        0x808080, 0xC0C0C0, 0xFF0000, 0xFF7F7F, 0xA50000, 0xA55252, 0x7F0000, 0x7F3F3F,
        0x4C0000, 0x4C2626, 0x260000, 0x261313, 0xFF3F00, 0xFF9F7F, 0xA52900, 0xA56752,
        0x7F1F00, 0x7F4F3F, 0x4C1300, 0x4C2F26, 0x260900, 0x261713, 0xFF7F00, 0xFFBF7F,
        0xA55200, 0xA57C52, 0x7F3F00, 0x7F5F3F, 0x4C2600, 0x4C3926, 0x261300, 0x261C13,
        0xFFBF00, 0xFFDF7F, 0xA57C00, 0xA59152, 0x7F5F00, 0x7F6F3F, 0x4C3900, 0x4C4226,
        0x261C00, 0x262113, 0xFFFF00, 0xFFFF7F, 0xA5A500, 0xA5A552, 0x7F7F00, 0x7F7F3F,
        0x4C4C00, 0x4C4C26, 0x262600, 0x262613, 0xBFFF00, 0xDFFF7F, 0x7CA500, 0x91A552,
        0x5F7F00, 0x6F7F3F, 0x394C00, 0x424C26, 0x1C2600, 0x212613, 0x7FFF00, 0xBFFF7F,
        0x52A500, 0x7CA552, 0x3F7F00, 0x5F7F3F, 0x264C00, 0x394C26, 0x132600, 0x1C2613,
        0x3FFF00, 0x9FFF7F, 0x29A500, 0x67A552, 0x1F7F00, 0x4F7F3F, 0x134C00, 0x2F4C26,
        0x092600, 0x172613, 0x00FF00, 0x7FFF7F, 0x00A500, 0x52A552, 0x007F00, 0x3F7F3F,
        0x004C00, 0x264C26, 0x002600, 0x132613, 0x00FF3F, 0x7FFF9F, 0x00A529, 0x52A567,
        0x007F1F, 0x3F7F4F, 0x004C13, 0x264C2F, 0x002609, 0x135817, 0x00FF7F, 0x7FFFBF,
        0x00A552, 0x52A57C, 0x007F3F, 0x3F7F5F, 0x004C26, 0x264C39, 0x002613, 0x13581C,
        0x00FFBF, 0x7FFFDF, 0x00A57C, 0x52A591, 0x007F5F, 0x3F7F6F, 0x004C39, 0x264C42,
        0x00261C, 0x135858, 0x00FFFF, 0x7FFFFF, 0x00A5A5, 0x52A5A5, 0x007F7F, 0x3F7F7F,
        0x004C4C, 0x264C4C, 0x002626, 0x135858, 0x00BFFF, 0x7FDFFF, 0x007CA5, 0x5291A5,
        0x005F7F, 0x3F6F7F, 0x00394C, 0x26427E, 0x001C26, 0x135858, 0x007FFF, 0x7FBFFF,
        0x0052A5, 0x527CA5, 0x003F7F, 0x3F5F7F, 0x00264C, 0x26397E, 0x001326, 0x131C58,
        0x003FFF, 0x7F9FFF, 0x0029A5, 0x5267A5, 0x001F7F, 0x3F4F7F, 0x00134C, 0x262F7E,
        0x000926, 0x131758, 0x0000FF, 0x7F7FFF, 0x0000A5, 0x5252A5, 0x00007F, 0x3F3F7F,
        0x00004C, 0x26267E, 0x000026, 0x131358, 0x3F00FF, 0x9F7FFF, 0x2900A5, 0x6752A5,
        0x1F007F, 0x4F3F7F, 0x13004C, 0x2F267E, 0x090026, 0x171358, 0x7F00FF, 0xBF7FFF,
        0x5200A5, 0x7C52A5, 0x3F007F, 0x5F3F7F, 0x26004C, 0x39267E, 0x130026, 0x1C1358,
        0xBF00FF, 0xDF7FFF, 0x7C00A5, 0x9152A5, 0x5F007F, 0x6F3F7F, 0x39004C, 0x42264C,
        0x1C0026, 0x581358, 0xFF00FF, 0xFF7FFF, 0xA500A5, 0xA552A5, 0x7F007F, 0x7F3F7F,
        0x4C004C, 0x4C264C, 0x260026, 0x581358, 0xFF00BF, 0xFF7FDF, 0xA5007C, 0xA55291,
        0x7F005F, 0x7F3F6F, 0x4C0039, 0x4C2642, 0x26001C, 0x581358, 0xFF007F, 0xFF7FBF,
        0xA50052, 0xA5527C, 0x7F003F, 0x7F3F5F, 0x4C0026, 0x4C2639, 0x260013, 0x58131C,
        0xFF003F, 0xFF7F9F, 0xA50029, 0xA55267, 0x7F001F, 0x7F3F4F, 0x4C0013, 0x4C262F,
        0x260009, 0x581317, 0x000000, 0x656565, 0x666666, 0x999999, 0xCCCCCC, 0xFFFFFF
    )

    // ---- Accès groupe ----
    private fun dbl(g: List<Pair>, code: Int): Double? = g.firstOrNull { it.code == code }?.double
    private fun int(g: List<Pair>, code: Int): Int? = g.firstOrNull { it.code == code }?.int
    private fun str(g: List<Pair>, code: Int): String? = g.firstOrNull { it.code == code }?.str
}
