package com.minou.mvrviewer.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec CROSS-PLATFORM des sections d'état + des docs Firestore.
 *
 * === Enveloppe de section (le point critique) ===
 * Côté iOS, `pushSection` fait `JSONEncoder().encode(payload)` où `payload` est
 * un `enum ProjectSectionPayload` à valeur associée. Le Codable synthétisé de
 * Swift produit pour un cas à une valeur associée :
 *
 *     {"<caseName>": {"_0": <dto encodé>}}
 *
 * ex. patch → {"patch":{"_0":{"entries":[…]}}}. Android DOIT produire et lire
 * EXACTEMENT cette forme, sinon iOS ne décode pas ce qu'Android pousse (et
 * inversement). Les Optionals nuls sont OMIS (JSONEncoder Swift n'écrit pas les
 * `nil`), donc on n'écrit une clé nullable que si non-nulle.
 *
 * === Docs Firestore ===
 * `CloudProject` et `AuditEntry` sont écrits côté iOS par `setData(from:)`, donc
 * chaque propriété devient un champ du document (mêmes noms). On mappe ici vers/
 * depuis `Map<String, Any?>` (ce que renvoie `DocumentSnapshot.data`).
 */
object SectionCodec {

    // ---- Enveloppe de section : encode/décode ------------------------------

    fun encode(payload: SectionPayload): String {
        val inner: JSONObject = when (payload) {
            is SectionPayload.Manifest -> encodeManifest(payload.dto)
            is SectionPayload.Calibration -> encodeCalibration(payload.dto)
            is SectionPayload.Patch -> encodePatch(payload.dto)
            is SectionPayload.LayerColors -> encodeLayerColors(payload.dto)
            is SectionPayload.LabelSides -> encodeLabelSides(payload.dto)
            is SectionPayload.FixtureOrientations -> encodeOrientations(payload.dto)
            is SectionPayload.GdtfMappings -> encodeGdtfMappings(payload.dto)
            is SectionPayload.PowerCabling -> PowerCablingCodec.toJson(payload.dto)
            is SectionPayload.DmxCabling -> DmxCablingCodec.toJson(payload.dto)
            is SectionPayload.CustomFixtures -> CustomFixturesCodec.toJson(payload.dto)
        }
        val wrapped = JSONObject().put("_0", inner)
        return JSONObject().put(payload.kind.raw, wrapped).toString()
    }

    fun decode(json: String): SectionPayload? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val key = root.keys().asSequence().firstOrNull() ?: return null
        val kind = ProjectSectionKind.from(key) ?: return null
        val inner = root.optJSONObject(key)?.optJSONObject("_0") ?: return null
        return when (kind) {
            ProjectSectionKind.MANIFEST -> SectionPayload.Manifest(decodeManifest(inner))
            ProjectSectionKind.CALIBRATION -> SectionPayload.Calibration(decodeCalibration(inner))
            ProjectSectionKind.PATCH -> SectionPayload.Patch(decodePatch(inner))
            ProjectSectionKind.LAYER_COLORS -> SectionPayload.LayerColors(decodeLayerColors(inner))
            ProjectSectionKind.LABEL_SIDES -> SectionPayload.LabelSides(decodeLabelSides(inner))
            ProjectSectionKind.FIXTURE_ORIENTATIONS -> SectionPayload.FixtureOrientations(decodeOrientations(inner))
            ProjectSectionKind.GDTF_MAPPINGS -> SectionPayload.GdtfMappings(decodeGdtfMappings(inner))
            ProjectSectionKind.POWER_CABLING -> SectionPayload.PowerCabling(PowerCablingCodec.fromJson(inner))
            ProjectSectionKind.DMX_CABLING -> SectionPayload.DmxCabling(DmxCablingCodec.fromJson(inner))
            ProjectSectionKind.CUSTOM_FIXTURES -> SectionPayload.CustomFixtures(CustomFixturesCodec.fromJson(inner))
        }
    }

    // ---- DTO encoders (Optionals nuls omis, comme Swift) -------------------

    private fun encodeTransform(t: RefPlanTransformDTO) = JSONObject()
        .put("offsetX", t.offsetX).put("offsetY", t.offsetY)
        .put("rotationDeg", t.rotationDeg).put("scale", t.scale)
        .put("heightZ", t.heightZ).put("visible", t.visible)

    private fun encodeManifest(d: ManifestDTO) = JSONObject().apply {
        put("mvrName", d.mvrName)
        d.dxfName?.let { put("dxfName", it) }
        d.refPlanTransform?.let { put("refPlanTransform", encodeTransform(it)) }
        put("refPlanHiddenLayers", JSONArray(d.refPlanHiddenLayers))
        d.showUserLocation?.let { put("showUserLocation", it) }
        d.showSatellite?.let { put("showSatellite", it) }
        d.refPlanBlobSHA?.let { put("refPlanBlobSHA", it) }
    }

    private fun encodePatch(d: PatchDTO) = JSONObject().apply {
        val arr = JSONArray()
        for (e in d.entries) {
            val o = JSONObject().put("mvrUUID", e.mvrUUID)
            e.fixtureId?.let { o.put("fixtureId", it) }
            o.put("addresses", JSONArray(e.addresses))
            e.gdtfModeName?.let { o.put("gdtfModeName", it) }
            e.name?.let { o.put("name", it) }
            e.spec?.let { o.put("spec", it) }
            arr.put(o)
        }
        put("entries", arr)
    }

    private fun encodeCalibration(d: GeoCalibrationDTO) = JSONObject().apply {
        val arr = JSONArray()
        for (a in d.anchors) {
            arr.put(JSONObject()
                .put("worldX", a.worldX.toDouble()).put("worldY", a.worldY.toDouble())
                .put("latitude", a.latitude).put("longitude", a.longitude))
        }
        put("anchors", arr)
    }

    private fun encodeLayerColors(d: LayerColorsDTO) =
        JSONObject().put("overrides", mapToJson(d.overrides))

    private fun encodeLabelSides(d: LabelSidesDTO) =
        JSONObject().put("sides", mapToJson(d.sides))

    private fun encodeOrientations(d: FixtureOrientationsDTO) = JSONObject().apply {
        val o = JSONObject()
        for ((k, v) in d.orientations) o.put(k, JSONArray(v))
        put("orientations", o)
    }

    private fun encodeGdtfMappings(d: GDTFMappingsDTO) = JSONObject().apply {
        val o = JSONObject()
        for ((k, v) in d.mappings) o.put(k, v)
        put("mappings", o)
    }

    // ---- DTO decoders ------------------------------------------------------

    private fun decodeTransform(o: JSONObject) = RefPlanTransformDTO(
        offsetX = o.optDouble("offsetX", 0.0), offsetY = o.optDouble("offsetY", 0.0),
        rotationDeg = o.optDouble("rotationDeg", 0.0), scale = o.optDouble("scale", 1.0),
        heightZ = o.optDouble("heightZ", 0.0), visible = o.optBoolean("visible", true)
    )

    private fun decodeManifest(o: JSONObject) = ManifestDTO(
        mvrName = o.optString("mvrName", ""),
        dxfName = o.optStringOrNull("dxfName"),
        refPlanTransform = o.optJSONObject("refPlanTransform")?.let(::decodeTransform),
        refPlanHiddenLayers = o.optJSONArray("refPlanHiddenLayers").toStringList(),
        showUserLocation = o.optBooleanOrNull("showUserLocation"),
        showSatellite = o.optBooleanOrNull("showSatellite"),
        refPlanBlobSHA = o.optStringOrNull("refPlanBlobSHA")
    )

    private fun decodePatch(o: JSONObject): PatchDTO {
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            PatchEntryDTO(
                mvrUUID = e.optString("mvrUUID", ""),
                fixtureId = e.optStringOrNull("fixtureId"),
                addresses = e.optJSONArray("addresses").toStringList(),
                gdtfModeName = e.optStringOrNull("gdtfModeName"),
                name = e.optStringOrNull("name"),
                spec = e.optStringOrNull("spec")
            )
        }
        return PatchDTO(entries)
    }

    private fun decodeCalibration(o: JSONObject): GeoCalibrationDTO {
        val arr = o.optJSONArray("anchors") ?: JSONArray()
        val anchors = (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            GeoAnchorDTO(
                worldX = a.optDouble("worldX", 0.0).toFloat(),
                worldY = a.optDouble("worldY", 0.0).toFloat(),
                latitude = a.optDouble("latitude", 0.0),
                longitude = a.optDouble("longitude", 0.0)
            )
        }
        return GeoCalibrationDTO(anchors)
    }

    private fun decodeLayerColors(o: JSONObject) =
        LayerColorsDTO(o.optJSONObject("overrides").toStringMap())

    private fun decodeLabelSides(o: JSONObject) =
        LabelSidesDTO(o.optJSONObject("sides").toStringMap())

    private fun decodeOrientations(o: JSONObject): FixtureOrientationsDTO {
        val src = o.optJSONObject("orientations") ?: JSONObject()
        val m = LinkedHashMap<String, List<Boolean>>()
        src.keys().forEach { k ->
            val arr = src.optJSONArray(k) ?: JSONArray()
            m[k] = (0 until arr.length()).map { arr.optBoolean(it) }
        }
        return FixtureOrientationsDTO(m)
    }

    private fun decodeGdtfMappings(o: JSONObject): GDTFMappingsDTO {
        val src = o.optJSONObject("mappings") ?: JSONObject()
        val m = LinkedHashMap<String, Int>()
        src.keys().forEach { k -> m[k] = src.optInt(k) }
        return GDTFMappingsDTO(m)
    }

    // ---- Docs Firestore : CloudProject / AuditEntry ↔ Map ------------------

    fun projectToMap(p: CloudProject): Map<String, Any?> = buildMap {
        put("id", p.id)
        put("contentKey", p.contentKey)
        put("name", p.name)
        put("ownerUid", p.ownerUid)
        put("memberUids", p.memberUids)
        put("mvrVersion", p.mvrVersion.toLong()) // Firestore stocke les entiers en Long
        p.mvrBlobSHA?.let { put("mvrBlobSHA", it) }
        p.refPlanBlobSHA?.let { put("refPlanBlobSHA", it) }
        put("updatedAtEpoch", p.updatedAtEpoch)
    }

    fun projectFromMap(map: Map<String, Any?>?): CloudProject? {
        map ?: return null
        val id = map["id"] as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val members = (map["memberUids"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return CloudProject(
            id = id,
            contentKey = map["contentKey"] as? String ?: "",
            name = map["name"] as? String ?: "",
            ownerUid = map["ownerUid"] as? String ?: "",
            memberUids = members,
            mvrVersion = (map["mvrVersion"] as? Number)?.toInt() ?: 1,
            mvrBlobSHA = map["mvrBlobSHA"] as? String,
            refPlanBlobSHA = map["refPlanBlobSHA"] as? String,
            updatedAtEpoch = (map["updatedAtEpoch"] as? Number)?.toDouble() ?: 0.0
        )
    }

    // Les coordonnées machine sont OMISES si nulles : une entrée ancienne relue
    // puis réécrite ne gagne pas de clés vides, et iOS (Optionals) produit la
    // même forme de document.
    fun auditToMap(e: AuditEntry): Map<String, Any?> = buildMap {
        put("id", e.id); put("epoch", e.epoch); put("authorUid", e.authorUid)
        put("authorName", e.authorName); put("kind", e.kind); put("target", e.target)
        put("field", e.field); put("oldValue", e.oldValue); put("newValue", e.newValue)
        e.objectKey?.let { put("objectKey", it) }
        e.fieldKey?.let { put("fieldKey", it) }
        e.oldRaw?.let { put("oldRaw", it) }
        e.newRaw?.let { put("newRaw", it) }
    }

    fun auditFromMap(map: Map<String, Any?>?): AuditEntry? {
        map ?: return null
        val id = map["id"] as? String ?: return null
        return AuditEntry(
            id = id,
            epoch = (map["epoch"] as? Number)?.toDouble() ?: 0.0,
            authorUid = map["authorUid"] as? String ?: "",
            authorName = map["authorName"] as? String ?: "",
            kind = map["kind"] as? String ?: "",
            target = map["target"] as? String ?: "",
            field = map["field"] as? String ?: "",
            oldValue = map["oldValue"] as? String ?: "",
            newValue = map["newValue"] as? String ?: "",
            objectKey = map["objectKey"] as? String,
            fieldKey = map["fieldKey"] as? String,
            oldRaw = map["oldRaw"] as? String,
            newRaw = map["newRaw"] as? String
        )
    }

    // ---- Doc Firestore : PowerEntry ↔ Map (bibliothèque de puissances) -----
    //
    // Collection RACINE `powerLibrary`, un doc par type. Mêmes noms de champs
    // que iOS. `updatedAt` est en MILLISECONDES (Number). Ne pas renommer d'un
    // seul côté sous peine de deux bibliothèques disjointes.

    fun powerToMap(e: PowerEntry): Map<String, Any?> = buildMap {
        put("spec", e.spec)
        put("watts", e.watts.toLong()) // Firestore stocke les entiers en Long
        put("updatedBy", e.updatedBy)
        put("updatedAt", e.updatedAtMillis)
    }

    fun powerFromMap(map: Map<String, Any?>?): PowerEntry? {
        map ?: return null
        val watts = (map["watts"] as? Number)?.toInt() ?: return null
        return PowerEntry(
            spec = map["spec"] as? String ?: "",
            watts = watts,
            updatedBy = map["updatedBy"] as? String ?: "",
            updatedAtMillis = (map["updatedAt"] as? Number)?.toLong() ?: 0L
        )
    }

    // ---- Doc Firestore : PowerVote ↔ Map (submissions/{uid}) ---------------
    //
    // Un doc de vote = { watts:Int, updatedAt:Number(epoch ms) }. Mêmes noms de
    // champs que iOS. L'uid n'est PAS un champ : c'est l'ID du document.

    fun powerVoteToMap(v: PowerVote): Map<String, Any?> = buildMap {
        put("watts", v.watts.toLong()) // Firestore stocke les entiers en Long
        put("updatedAt", v.updatedAtMillis)
    }

    fun powerVoteFromMap(map: Map<String, Any?>?): PowerVote? {
        map ?: return null
        val watts = (map["watts"] as? Number)?.toInt() ?: return null
        return PowerVote(watts, (map["updatedAt"] as? Number)?.toLong() ?: 0L)
    }

    // ---- helpers -----------------------------------------------------------

    private fun mapToJson(m: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }
}

// Extensions org.json : distinguer « absent/null » de « chaîne vide », lister/mapper.

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

private fun JSONArray?.toStringList(): List<String> {
    this ?: return emptyList()
    return (0 until length()).map { optString(it) }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    this ?: return emptyMap()
    val m = LinkedHashMap<String, String>()
    keys().forEach { k -> m[k] = optString(k) }
    return m
}
