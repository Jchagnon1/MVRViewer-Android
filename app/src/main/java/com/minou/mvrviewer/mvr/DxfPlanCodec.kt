package com.minou.mvrviewer.mvr

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Encodage BINAIRE compact d'un DxfPlan (portage de DXFPlanCodec iOS). Un gros
 * plan = des centaines de milliers de segments : le JSON/Serializable serait
 * énorme et lent. Ici : en-tête JSON (métadonnées + table de calques internés)
 * puis les polylignes en Float32 bruts. On stocke le PLAN PARSÉ (pas le .dxf
 * d'origine, qui pèse des centaines de Mo) → un projet rouvert retrouve son plan
 * sans le fichier source. Magic "DXP2".
 */
object DxfPlanCodec {
    private const val MAGIC = "DXP2"

    fun encode(plan: DxfPlan): ByteArray {
        val layers = ArrayList<String>()
        val layerIndex = HashMap<String, Int>()
        fun idx(name: String) = layerIndex.getOrPut(name) { layers.add(name); layers.size - 1 }
        // Pré-interne les calques dans l'ordre des polylignes.
        for (p in plan.polylines) idx(p.layer)

        val header = JSONObject()
            .put("unitLabel", plan.unitLabel)
            .put("segmentCount", plan.segmentCount)
            .put("truncatedSegments", plan.truncatedSegments)
            .put("minX", plan.minX.toDouble()).put("minY", plan.minY.toDouble())
            .put("maxX", plan.maxX.toDouble()).put("maxY", plan.maxY.toDouble())
            .put("layers", JSONArray(layers))
            .put("layerCounts", JSONObject(plan.layerCounts as Map<*, *>))
            .put("polylineCount", plan.polylines.size)
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)

        val bos = ByteArrayOutputStream(headerBytes.size + plan.segmentCount * 8 + 1024)
        val out = DataOutputStream(bos)
        out.writeBytes(MAGIC)
        out.writeInt(headerBytes.size)
        out.write(headerBytes)
        for (p in plan.polylines) {
            out.writeInt(layerIndex.getValue(p.layer))
            out.writeByte(if (p.closed) 1 else 0)
            out.writeInt(p.points.size / 2)
            for (f in p.points) out.writeFloat(f)
        }
        out.flush()
        return bos.toByteArray()
    }

    /** Décode, ou null si le format/les données sont invalides. */
    fun decode(bytes: ByteArray): DxfPlan? = runCatching {
        val ins = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(4).also { ins.readFully(it) }
        if (String(magic) != MAGIC) return null
        val headerLen = ins.readInt()
        if (headerLen <= 0 || headerLen > bytes.size) return null
        val headerBytes = ByteArray(headerLen).also { ins.readFully(it) }
        val h = JSONObject(String(headerBytes, Charsets.UTF_8))
        val layersArr = h.getJSONArray("layers")
        val layers = (0 until layersArr.length()).map { layersArr.getString(it) }
        val polyCount = h.getInt("polylineCount")
        if (polyCount < 0 || polyCount > 20_000_000) return null

        val polylines = ArrayList<DxfPolyline>(polyCount)
        repeat(polyCount) {
            val li = ins.readInt()
            val closed = ins.readByte().toInt() != 0
            val ptCount = ins.readInt()
            if (ptCount < 0 || ptCount > 50_000_000) return null
            val pts = FloatArray(ptCount * 2)
            for (i in pts.indices) pts[i] = ins.readFloat()
            val layer = layers.getOrElse(li) { "0" }
            polylines.add(DxfPolyline(pts, closed, layer))
        }
        val layerCounts = HashMap<String, Int>()
        val lc = h.optJSONObject("layerCounts")
        if (lc != null) for (k in lc.keys()) layerCounts[k] = lc.getInt(k)
        DxfPlan(
            polylines = polylines,
            minX = h.getDouble("minX").toFloat(), minY = h.getDouble("minY").toFloat(),
            maxX = h.getDouble("maxX").toFloat(), maxY = h.getDouble("maxY").toFloat(),
            unitLabel = h.optString("unitLabel", "mm"),
            segmentCount = h.optInt("segmentCount", polylines.sumOf { it.points.size / 2 }),
            layerCounts = layerCounts,
            truncatedSegments = h.optInt("truncatedSegments", 0)
        )
    }.getOrNull()
}
