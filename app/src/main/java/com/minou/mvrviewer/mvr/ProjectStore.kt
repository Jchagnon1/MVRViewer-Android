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

    /**
     * Le manifeste est un SEUL fichier écrit par plusieurs producteurs
     * indépendants (placement DXF, calibration GPS, drapeaux d'affichage,
     * étiquettes déplacées), chacun en lire-modifier-écrire depuis son propre
     * thread. Sans exclusion mutuelle, deux enregistrements simultanés partent
     * du même contenu lu et le dernier écrase la section de l'autre. Le verrou
     * couvre TOUT le cycle lire→modifier→écrire, pas seulement l'écriture.
     */
    private val manifestLock = Any()

    /** Fil d'écriture propre au processus : une sauvegarde déclenchée juste avant
     *  de quitter un écran doit aboutir même si le composable est détruit (une
     *  coroutine liée au cycle de vie serait annulée en vol → décalage perdu). */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ProjectStore-writer").apply { isDaemon = true }
    }

    private fun readManifest(ctx: Context, key: String): JSONObject = synchronized(manifestLock) {
        runCatching { JSONObject(manifestFile(ctx, key).readText()) }.getOrDefault(JSONObject())
    }

    /**
     * Écriture ATOMIQUE (fichier temporaire puis renommage).
     *
     * POURQUOI : `writeText` tronque d'abord le fichier puis écrit. Tué au
     * milieu (l'OS tue volontiers une appli passée en arrière-plan), il reste un
     * manifeste incomplet — donc du JSON invalide, donc `readManifest` qui
     * repart d'un objet vide : placement du plan DXF, calibration GPS, calques
     * masqués, décalages d'étiquettes et mapping GDTF perdus D'UN COUP, sans le
     * moindre message. Le risque a nettement grossi avec les décalages
     * d'étiquettes, enregistrés à chaque relâché de doigt. Un renommage est
     * atomique sur le même volume : soit l'ancien manifeste, soit le nouveau,
     * jamais un fichier à moitié écrit.
     */
    private fun writeManifest(ctx: Context, key: String, m: JSONObject) = synchronized(manifestLock) {
        val dst = manifestFile(ctx, key)
        val tmp = File(dst.parentFile, "manifest.json.tmp")
        runCatching {
            tmp.writeText(m.toString())
            if (!tmp.renameTo(dst)) {
                // Renommage refusé (cas rare) : repli sur l'écriture directe,
                // qui reste préférable à ne rien enregistrer du tout.
                dst.writeText(m.toString())
                tmp.delete()
            }
        }.onFailure { runCatching { tmp.delete() } }
        Unit
    }

    // ---- Plan DXF de repère (géométrie + transform + nom) ----

    fun saveReferencePlan(ctx: Context, key: String, rp: ReferencePlan, name: String?) = synchronized(manifestLock) {
        runCatching { planFile(ctx, key).writeBytes(DxfPlanCodec.encode(rp.plan)) }
        val m = readManifest(ctx, key)
        m.put("dxfName", name ?: JSONObject.NULL)
        m.put("refTransform", transformJson(rp.transform))
        writeManifest(ctx, key, m)
    }

    /** Met à jour SEULEMENT le placement (glissé/rotation/échelle) — pas la géométrie. */
    fun saveTransform(ctx: Context, key: String, t: ReferencePlanTransform) = synchronized(manifestLock) {
        if (planFile(ctx, key).exists()) {
            val m = readManifest(ctx, key)
            m.put("refTransform", transformJson(t))
            writeManifest(ctx, key, m)
        }
    }

    fun loadReferencePlan(ctx: Context, key: String): ReferencePlan? {
        val f = planFile(ctx, key)
        if (!f.exists()) return null
        val plan = runCatching { DxfPlanCodec.decode(f.readBytes()) }.getOrNull() ?: return null
        val t = readManifest(ctx, key).optJSONObject("refTransform")?.let(::transformFrom)
            ?: ReferencePlanTransform()
        return ReferencePlan(plan, t)
    }

    fun removeReferencePlan(ctx: Context, key: String) = synchronized(manifestLock) {
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

    fun saveCalibration(ctx: Context, key: String, anchors: List<GeoAnchor>) = synchronized(manifestLock) {
        val m = readManifest(ctx, key)
        if (anchors.isEmpty()) { m.remove("anchors"); writeManifest(ctx, key, m) }
        else {
            val arr = JSONArray()
            for (a in anchors) arr.put(JSONObject()
                .put("wx", a.worldX.toDouble()).put("wy", a.worldY.toDouble())
                .put("lat", a.latitude).put("lon", a.longitude))
            m.put("anchors", arr); writeManifest(ctx, key, m)
        }
    }

    fun loadCalibration(ctx: Context, key: String): List<GeoAnchor> {
        val arr = readManifest(ctx, key).optJSONArray("anchors") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GeoAnchor(o.getDouble("wx").toFloat(), o.getDouble("wy").toFloat(), o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    // ---- Fond satellite (juste le drapeau on/off, comme iOS) ----

    fun saveShowSatellite(ctx: Context, key: String, on: Boolean) = synchronized(manifestLock) {
        val m = readManifest(ctx, key); m.put("showSatellite", on); writeManifest(ctx, key, m)
    }

    fun loadShowSatellite(ctx: Context, key: String): Boolean =
        readManifest(ctx, key).optBoolean("showSatellite", false)

    // ---- Position GPS affichée (drapeau on/off, persistant par projet) ----

    fun saveShowUserLocation(ctx: Context, key: String, on: Boolean) = synchronized(manifestLock) {
        val m = readManifest(ctx, key); m.put("showUserLocation", on); writeManifest(ctx, key, m)
    }

    fun loadShowUserLocation(ctx: Context, key: String): Boolean =
        readManifest(ctx, key).optBoolean("showUserLocation", false)

    // ---- Calques DXF masqués (visibilité par calque du plan de repère) ----

    fun saveRefPlanHiddenLayers(ctx: Context, key: String, layers: Set<String>) = synchronized(manifestLock) {
        val m = readManifest(ctx, key)
        if (layers.isEmpty()) m.remove("refPlanHiddenLayers")
        else m.put("refPlanHiddenLayers", JSONArray(layers.toList()))
        writeManifest(ctx, key, m)
    }

    fun loadRefPlanHiddenLayers(ctx: Context, key: String): Set<String> {
        val arr = readManifest(ctx, key).optJSONArray("refPlanHiddenLayers") ?: return emptySet()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
    }

    // ---- Décalage manuel des étiquettes en vue plan (par projecteur) ----
    // Volontairement HORS synchro cloud : c'est un confort de lecture propre à
    // l'appareil (et au zoom auquel on travaille), pas une donnée de show ;
    // l'imposer aux autres postes déplacerait leurs étiquettes sans préavis.

    /** Enregistrement DIFFÉRÉ sur le fil du processus : appelée au relâché du
     *  doigt, souvent juste avant que l'utilisateur quitte la vue plan. */
    fun saveLabelOffsetsAsync(ctx: Context, key: String, offsets: Map<String, Pair<Float, Float>>) {
        val appCtx = ctx.applicationContext
        val snapshot = HashMap(offsets)
        writer.execute { saveLabelOffsets(appCtx, key, snapshot) }
    }

    fun saveLabelOffsets(ctx: Context, key: String, offsets: Map<String, Pair<Float, Float>>) = synchronized(manifestLock) {
        val m = readManifest(ctx, key)
        if (offsets.isEmpty()) { m.remove("labelOffsets"); writeManifest(ctx, key, m) }
        else {
            val o = JSONObject()
            for ((k, v) in offsets) o.put(k, JSONArray().put(v.first.toDouble()).put(v.second.toDouble()))
            m.put("labelOffsets", o); writeManifest(ctx, key, m)
        }
    }

    fun loadLabelOffsets(ctx: Context, key: String): Map<String, Pair<Float, Float>> {
        val o = readManifest(ctx, key).optJSONObject("labelOffsets") ?: return emptyMap()
        val out = HashMap<String, Pair<Float, Float>>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val a = o.optJSONArray(k) ?: continue
            if (a.length() < 2) continue
            out[k] = a.optDouble(0, 0.0).toFloat() to a.optDouble(1, 0.0).toFloat()
        }
        return out
    }

    // ---- Câblage électrique (section « powerCabling », phase 2) ----
    // Persisté DANS le manifeste sous forme du JSON du DTO (le même que la
    // section de synchro, sans l'enveloppe) : rouvrir le .mvr retrouve les
    // distributeurs, les affectations et les réglages, même hors ligne.

    fun savePowerCabling(ctx: Context, key: String, json: String?) = synchronized(manifestLock) {
        val m = readManifest(ctx, key)
        if (json.isNullOrBlank()) m.remove("powerCabling")
        else runCatching { m.put("powerCabling", JSONObject(json)) }
        writeManifest(ctx, key, m)
    }

    /** JSON du DTO câblage persisté (null si jamais enregistré). */
    fun loadPowerCabling(ctx: Context, key: String): String? =
        readManifest(ctx, key).optJSONObject("powerCabling")?.toString()

    // ---- Câblage DMX (section « dmxCabling », phase 3) ----
    // Même principe que le câblage électrique : le JSON du DTO (sans enveloppe)
    // est rangé dans le manifeste ; rouvrir le .mvr retrouve les lignes DMX et
    // leurs affectations, même hors ligne.

    fun saveDmxCabling(ctx: Context, key: String, json: String?) = synchronized(manifestLock) {
        val m = readManifest(ctx, key)
        if (json.isNullOrBlank()) m.remove("dmxCabling")
        else runCatching { m.put("dmxCabling", JSONObject(json)) }
        writeManifest(ctx, key, m)
    }

    /** JSON du DTO câblage DMX persisté (null si jamais enregistré). */
    fun loadDmxCabling(ctx: Context, key: String): String? =
        readManifest(ctx, key).optJSONObject("dmxCabling")?.toString()

    // ---- Modèles GDTF Share appliqués (octets sur disque + mapping) ----

    fun saveOverrides(ctx: Context, key: String, map: Map<String, ByteArray>, manual: Set<String>) = synchronized(manifestLock) {
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
