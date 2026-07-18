package com.minou.mvrviewer.mvr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistance PAR PROJET (portage de ProjectStore iOS) : rouvrir un .mvr
 * retrouve le travail fait dessus — plan DXF importé + son placement, calibration
 * GPS, modèles GDTF Share appliqués. Clé = EMPREINTE du contenu du .mvr (pas son
 * chemin, qui change) → le même show rouvert retrouve tout. Dossier par projet :
 *   filesDir/projects/<clé>/manifest.json  (transform, calibration, mapping overrides)
 *   filesDir/projects/<clé>/refplan.bin     (géométrie DXF, DxfPlanCodec)
 *   filesDir/projects/<clé>/ov_<n>.gdtf      (octets d'un modèle GDTF appliqué)
 */
object ProjectStore {

    /** Empreinte FNV-1a 64 bits sur les 64 premiers Ko + taille (comme iOS). */
    fun keyFor(bytes: ByteArray): String {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 (offset basis)
        val n = minOf(bytes.size, 65536)
        for (i in 0 until n) { h = h xor (bytes[i].toLong() and 0xff); h *= 0x100000001b3L }
        return "%016x-%d".format(h, bytes.size)
    }

    private fun dir(ctx: Context, key: String): File =
        File(ctx.filesDir, "projects/$key").apply { mkdirs() }

    private fun manifestFile(ctx: Context, key: String) = File(dir(ctx, key), "manifest.json")
    private fun planFile(ctx: Context, key: String) = File(dir(ctx, key), "refplan.bin")

    private fun readManifest(ctx: Context, key: String): JSONObject =
        runCatching { JSONObject(manifestFile(ctx, key).readText()) }.getOrDefault(JSONObject())

    private fun writeManifest(ctx: Context, key: String, m: JSONObject) {
        runCatching { manifestFile(ctx, key).writeText(m.toString()) }
    }

    // ---- Plan DXF de repère (géométrie + transform + nom) ----

    fun saveReferencePlan(ctx: Context, key: String, rp: ReferencePlan, name: String?) {
        runCatching { planFile(ctx, key).writeBytes(DxfPlanCodec.encode(rp.plan)) }
        val m = readManifest(ctx, key)
        m.put("dxfName", name ?: JSONObject.NULL)
        m.put("refTransform", transformJson(rp.transform))
        writeManifest(ctx, key, m)
    }

    /** Met à jour SEULEMENT le placement (glissé/rotation/échelle) — pas la géométrie. */
    fun saveTransform(ctx: Context, key: String, t: ReferencePlanTransform) {
        if (!planFile(ctx, key).exists()) return
        val m = readManifest(ctx, key)
        m.put("refTransform", transformJson(t))
        writeManifest(ctx, key, m)
    }

    fun loadReferencePlan(ctx: Context, key: String): ReferencePlan? {
        val f = planFile(ctx, key)
        if (!f.exists()) return null
        val plan = runCatching { DxfPlanCodec.decode(f.readBytes()) }.getOrNull() ?: return null
        val t = readManifest(ctx, key).optJSONObject("refTransform")?.let(::transformFrom)
            ?: ReferencePlanTransform()
        return ReferencePlan(plan, t)
    }

    fun removeReferencePlan(ctx: Context, key: String) {
        runCatching { planFile(ctx, key).delete() }
        val m = readManifest(ctx, key)
        m.remove("refTransform"); m.remove("dxfName")
        writeManifest(ctx, key, m)
    }

    fun dxfName(ctx: Context, key: String): String? =
        readManifest(ctx, key).optString("dxfName").takeIf { it.isNotBlank() }

    private fun transformJson(t: ReferencePlanTransform) = JSONObject()
        .put("offsetX", t.offsetX).put("offsetY", t.offsetY).put("rotationDeg", t.rotationDeg)
        .put("scale", t.scale).put("heightZ", t.heightZ).put("visible", t.visible)

    private fun transformFrom(o: JSONObject) = ReferencePlanTransform(
        offsetX = o.optDouble("offsetX", 0.0), offsetY = o.optDouble("offsetY", 0.0),
        rotationDeg = o.optDouble("rotationDeg", 0.0), scale = o.optDouble("scale", 1.0),
        heightZ = o.optDouble("heightZ", 0.0), visible = o.optBoolean("visible", true)
    )

    // ---- Calibration GPS ----

    fun saveCalibration(ctx: Context, key: String, anchors: List<GeoAnchor>) {
        val m = readManifest(ctx, key)
        if (anchors.isEmpty()) { m.remove("anchors"); writeManifest(ctx, key, m); return }
        val arr = JSONArray()
        for (a in anchors) arr.put(JSONObject()
            .put("wx", a.worldX.toDouble()).put("wy", a.worldY.toDouble())
            .put("lat", a.latitude).put("lon", a.longitude))
        m.put("anchors", arr); writeManifest(ctx, key, m)
    }

    fun loadCalibration(ctx: Context, key: String): List<GeoAnchor> {
        val arr = readManifest(ctx, key).optJSONArray("anchors") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GeoAnchor(o.getDouble("wx").toFloat(), o.getDouble("wy").toFloat(), o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    // ---- Modèles GDTF Share appliqués (octets sur disque + mapping) ----

    fun saveOverrides(ctx: Context, key: String, map: Map<String, ByteArray>, manual: Set<String>) {
        val d = dir(ctx, key)
        runCatching { d.listFiles { f -> f.name.startsWith("ov_") }?.forEach { it.delete() } }
        val arr = JSONArray()
        map.entries.forEachIndexed { i, (spec, bytes) ->
            val fname = "ov_$i.gdtf"
            runCatching { File(d, fname).writeBytes(bytes) }
            arr.put(JSONObject().put("spec", spec).put("file", fname).put("manual", spec in manual))
        }
        val m = readManifest(ctx, key)
        if (arr.length() == 0) m.remove("overrides") else m.put("overrides", arr)
        writeManifest(ctx, key, m)
    }

    /** (spec → octets, specs manuels), ou null si aucun. */
    fun loadOverrides(ctx: Context, key: String): Pair<Map<String, ByteArray>, Set<String>>? {
        val arr = readManifest(ctx, key).optJSONArray("overrides") ?: return null
        val d = dir(ctx, key)
        val map = LinkedHashMap<String, ByteArray>()
        val manual = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val spec = o.getString("spec")
            val bytes = runCatching { File(d, o.getString("file")).readBytes() }.getOrNull() ?: continue
            map[spec] = bytes
            if (o.optBoolean("manual")) manual.add(spec)
        }
        return if (map.isEmpty()) null else map to manual
    }
}
