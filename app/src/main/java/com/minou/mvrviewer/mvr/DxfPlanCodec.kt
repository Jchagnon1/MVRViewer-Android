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
    // Écrit DXP4 (couleurs + remplissages) ; DXP3 (couleurs) et DXP2 restent lisibles.
    private const val MAGIC = "DXP4"

    fun encode(plan: DxfPlan): ByteArray {
        val layers = ArrayList<String>()
        val layerIndex = HashMap<String, Int>()
        fun idx(name: String) = layerIndex.getOrPut(name) { layers.add(name); layers.size - 1 }
        // Pré-interne les calques dans l'ordre des polylignes PUIS des remplissages
        // (la table de calques part dans l'en-tête, donc avant l'écriture du corps).
        for (p in plan.polylines) idx(p.layer)
        for (f in plan.fills) idx(f.layer)

        val header = JSONObject()
            .put("unitLabel", plan.unitLabel)
            .put("segmentCount", plan.segmentCount)
            .put("truncatedSegments", plan.truncatedSegments)
            .put("minX", plan.minX.toDouble()).put("minY", plan.minY.toDouble())
            .put("maxX", plan.maxX.toDouble()).put("maxY", plan.maxY.toDouble())
            .put("layers", JSONArray(layers))
            .put("layerCounts", JSONObject(plan.layerCounts as Map<*, *>))
            .put("layerColors", JSONObject(plan.layerColors as Map<*, *>))
            .put("defaultHiddenLayers", JSONArray(plan.defaultHiddenLayers.toList()))
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
            out.writeInt(p.color)
            out.writeInt(p.points.size / 2)
            for (f in p.points) out.writeFloat(f)
        }
        // Zones remplies (DXP4+) : nb, puis {calque, couleur, aplat, nb anneaux,
        // anneaux : nb points + points}.
        out.writeInt(plan.fills.size)
        for (fl in plan.fills) {
            out.writeInt(layerIndex[fl.layer] ?: 0)
            out.writeInt(fl.color)
            out.writeByte(if (fl.solid) 1 else 0)
            out.writeInt(fl.rings.size)
            for (ring in fl.rings) {
                out.writeInt(ring.size / 2)
                for (v in ring) out.writeFloat(v)
            }
        }
        out.flush()
        return bos.toByteArray()
    }

    /** Décode, ou null si le format/les données sont invalides. */
    fun decode(bytes: ByteArray): DxfPlan? = runCatching {
        val ins = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(4).also { ins.readFully(it) }
        val mg = String(magic)
        if (mg != "DXP4" && mg != "DXP3" && mg != "DXP2") return null
        val hasColor = mg == "DXP3" || mg == "DXP4"
        val hasFills = mg == "DXP4"
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
            val color = if (hasColor) ins.readInt() else 0xFFFFFF
            val ptCount = ins.readInt()
            if (ptCount < 0 || ptCount > 50_000_000) return null
            val pts = FloatArray(ptCount * 2)
            for (i in pts.indices) pts[i] = ins.readFloat()
            val layer = layers.getOrElse(li) { "0" }
            polylines.add(DxfPolyline(pts, closed, layer, color))
        }
        val fills = ArrayList<DxfFill>()
        if (hasFills) {
            val fillCount = ins.readInt()
            if (fillCount < 0 || fillCount > 5_000_000) return null
            repeat(fillCount) {
                val li = ins.readInt()
                val color = ins.readInt()
                val solid = ins.readByte().toInt() != 0
                val ringCount = ins.readInt()
                if (ringCount < 0 || ringCount > 1_000_000) return null
                val rings = ArrayList<FloatArray>(ringCount)
                repeat(ringCount) {
                    val pc = ins.readInt()
                    if (pc < 0 || pc > 10_000_000) return null
                    val r = FloatArray(pc * 2)
                    for (k in r.indices) r[k] = ins.readFloat()
                    rings.add(r)
                }
                fills.add(DxfFill(rings, color, solid, layers.getOrElse(li) { "0" }))
            }
        }
        val layerCounts = HashMap<String, Int>()
        h.optJSONObject("layerCounts")?.let { o -> for (k in o.keys()) layerCounts[k] = o.getInt(k) }
        val layerColors = HashMap<String, Int>()
        h.optJSONObject("layerColors")?.let { o -> for (k in o.keys()) layerColors[k] = o.getInt(k) }
        val defaultHidden = HashSet<String>()
        h.optJSONArray("defaultHiddenLayers")?.let { a -> for (i in 0 until a.length()) defaultHidden.add(a.getString(i)) }
        DxfPlan(
            polylines = polylines,
            minX = h.getDouble("minX").toFloat(), minY = h.getDouble("minY").toFloat(),
            maxX = h.getDouble("maxX").toFloat(), maxY = h.getDouble("maxY").toFloat(),
            unitLabel = h.optString("unitLabel", "mm"),
            segmentCount = h.optInt("segmentCount", polylines.sumOf { it.points.size / 2 }),
            layerCounts = layerCounts,
            truncatedSegments = h.optInt("truncatedSegments", 0),
            layerColors = layerColors,
            defaultHiddenLayers = defaultHidden,
            fills = fills
        )
    }.getOrNull()
}
