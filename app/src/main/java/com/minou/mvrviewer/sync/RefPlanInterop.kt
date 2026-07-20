package com.minou.mvrviewer.sync

import com.minou.mvrviewer.mvr.DxfFill
import com.minou.mvrviewer.mvr.DxfPlan
import com.minou.mvrviewer.mvr.DxfPolyline
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Codec d'ÉCHANGE cross-platform du plan de repère (`refplan.bin` du cloud).
 *
 * Problème : le codec LOCAL Android (`DxfPlanCodec`, magic "DXP2", BIG-endian via
 * DataOutputStream, polylignes seules) et le codec iOS (`DXFPlanCodec`, magic
 * "DXP1", LITTLE-endian, avec couleurs/remplissages/étiquettes) sont INCOMPATIBLES.
 * Pour que le blob partagé circule dans les deux sens, le format d'échange est
 * TOUJOURS celui d'iOS (**DXP1 little-endian**) : iOS l'écrit/lit nativement,
 * Android le lit/écrit ICI.
 *
 * Pertes acceptées v1 :
 *   - iOS → Android : remplissages/étiquettes/couleurs d'entités ignorés (le modèle
 *     Android n'a que des polylignes monochromes colorées par calque).
 *   - Android → iOS : couleur d'entité écrite à 0xFFFFFF (le défaut iOS, géré par
 *     son adaptation de contraste) ; aucun remplissage/étiquette.
 *
 * Layout DXP1 (little-endian) — voir DXFPlanCodec.swift :
 *   magic "DXP1" (4o) ; headerLen u32 ; header JSON ;
 *   polylignes : { layerIdx u32, color u32, flags u8 (bit0=closed, bit1=dashed),
 *                  weightClass i8, ptCount u32, points[ptCount*2 f32] }
 *   (puis fills / labels — non écrits, ignorés en lecture car les polylignes
 *   viennent en PREMIER).
 */
object RefPlanInterop {

    private const val MAGIC = "DXP1"

    // ---- Lecture : DXP1 (iOS) → DxfPlan (Android) --------------------------

    fun decode(bytes: ByteArray): DxfPlan? = runCatching {
        if (bytes.size < 8) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        if (String(magic, Charsets.US_ASCII) != MAGIC) return null
        val headerLen = buf.int
        if (headerLen <= 0 || headerLen > bytes.size) return null
        val headerBytes = ByteArray(headerLen).also { buf.get(it) }
        val h = JSONObject(String(headerBytes, Charsets.UTF_8))

        val namesArr = h.optJSONArray("layerNames") ?: JSONArray()
        val names = (0 until namesArr.length()).map { namesArr.optString(it) }
        val polyCount = h.optInt("polylineCount", 0)
        if (polyCount < 0 || polyCount > 20_000_000) return null

        val polylines = ArrayList<DxfPolyline>(polyCount)
        repeat(polyCount) {
            val li = buf.int
            val color = buf.int and 0xFFFFFF     // couleur résolue iOS (0xRRGGBB)
            val flags = buf.get().toInt()
            buf.get()            // weightClass (ignoré)
            val ptCount = buf.int
            if (ptCount < 0 || ptCount.toLong() * 8 > (bytes.size - buf.position())) return null
            val pts = FloatArray(ptCount * 2)
            for (i in pts.indices) pts[i] = buf.float
            polylines.add(DxfPolyline(pts, (flags and 1) != 0, names.getOrElse(li) { "0" }, color))
        }

        // Zones remplies (viennent APRÈS les polylignes dans le format iOS).
        val fillCount = h.optInt("fillCount", 0)
        val fills = ArrayList<DxfFill>()
        if (fillCount in 1..2_000_000) {
            repeat(fillCount) {
                val li = buf.int
                val col = buf.int and 0xFFFFFF
                val solid = buf.get().toInt() != 0
                val ringCount = buf.int
                require(ringCount in 0..1_000_000) { "ringCount" }
                val rings = ArrayList<FloatArray>(minOf(ringCount, 1024))
                repeat(ringCount) {
                    val pc = buf.int
                    require(pc in 0..5_000_000) { "ptCount" }
                    val r = FloatArray(pc * 2)
                    for (k in r.indices) r[k] = buf.float
                    if (r.size >= 6) rings.add(r)
                }
                if (rings.isNotEmpty()) fills.add(DxfFill(rings, col, solid, names.getOrElse(li) { "0" }))
            }
        }

        val bmin = h.optJSONArray("boundsMin")
        val bmax = h.optJSONArray("boundsMax")
        val layerCounts = HashMap<String, Int>()
        h.optJSONObject("layerCounts")?.let { lc -> lc.keys().forEach { layerCounts[it] = lc.optInt(it) } }
        val layerColors = HashMap<String, Int>()
        h.optJSONObject("layerColors")?.let { o -> o.keys().forEach { layerColors[it] = o.optInt(it) and 0xFFFFFF } }
        val defaultHidden = HashSet<String>()
        h.optJSONArray("defaultHiddenLayers")?.let { a -> for (i in 0 until a.length()) defaultHidden.add(a.optString(i)) }

        DxfPlan(
            polylines = polylines,
            minX = bmin?.optDouble(0, 0.0)?.toFloat() ?: 0f,
            minY = bmin?.optDouble(1, 0.0)?.toFloat() ?: 0f,
            maxX = bmax?.optDouble(0, 0.0)?.toFloat() ?: 0f,
            maxY = bmax?.optDouble(1, 0.0)?.toFloat() ?: 0f,
            unitLabel = h.optString("unitLabel", "mm"),
            segmentCount = h.optInt("segmentCount", polylines.sumOf { it.points.size / 2 }),
            layerCounts = layerCounts,
            truncatedSegments = h.optInt("truncatedSegments", 0),
            layerColors = layerColors,
            defaultHiddenLayers = defaultHidden,
            fills = fills
        )
    }.getOrNull()

    // ---- Écriture : DxfPlan (Android) → DXP1 (iOS) -------------------------

    fun encode(plan: DxfPlan): ByteArray {
        val layerNames = ArrayList<String>()
        val layerIndex = HashMap<String, Int>()
        fun idx(name: String) = layerIndex.getOrPut(name) { layerNames.add(name); layerNames.size - 1 }
        for (p in plan.polylines) idx(p.layer)
        for (f in plan.fills) idx(f.layer)

        val layerCounts = JSONObject().apply { plan.layerCounts.forEach { (k, v) -> put(k, v) } }
        val layerColors = JSONObject().apply { plan.layerColors.forEach { (k, v) -> put(k, v and 0xFFFFFF) } }
        val header = JSONObject()
            .put("unitLabel", plan.unitLabel)
            .put("segmentCount", plan.segmentCount)
            .put("truncatedSegments", plan.truncatedSegments)
            .put("blocksTruncated", false)
            .put("boundsMin", JSONArray(listOf(plan.minX.toDouble(), plan.minY.toDouble())))
            .put("boundsMax", JSONArray(listOf(plan.maxX.toDouble(), plan.maxY.toDouble())))
            .put("layerNames", JSONArray(layerNames))
            .put("layerCounts", layerCounts)
            .put("layerColors", layerColors)
            .put("defaultHiddenLayers", JSONArray(plan.defaultHiddenLayers.toList()))
            .put("polylineCount", plan.polylines.size)
            .put("fillCount", plan.fills.size)
            .put("labelCount", 0)
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream(headerBytes.size + plan.segmentCount * 8 + 64)
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        writeU32LE(out, headerBytes.size)
        out.write(headerBytes)
        for (p in plan.polylines) {
            writeU32LE(out, layerIndex.getValue(p.layer))
            writeU32LE(out, p.color and 0xFFFFFF)  // couleur résolue (0xRRGGBB)
            out.write(if (p.closed) 1 else 0)      // flags (bit0=closed)
            out.write(1)                           // weightClass = normal
            writeU32LE(out, p.points.size / 2)     // ptCount (paires)
            for (f in p.points) writeF32LE(out, f)
        }
        // Zones remplies, dans l'ordre attendu par iOS (après les polylignes).
        for (fl in plan.fills) {
            writeU32LE(out, layerIndex.getValue(fl.layer))
            writeU32LE(out, fl.color and 0xFFFFFF)
            out.write(if (fl.solid) 1 else 0)
            writeU32LE(out, fl.rings.size)
            for (ring in fl.rings) {
                writeU32LE(out, ring.size / 2)
                for (v in ring) writeF32LE(out, v)
            }
        }
        return out.toByteArray()
    }

    // ---- helpers little-endian ---------------------------------------------

    private fun writeU32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeF32LE(out: ByteArrayOutputStream, f: Float) {
        writeU32LE(out, java.lang.Float.floatToIntBits(f))
    }
}
