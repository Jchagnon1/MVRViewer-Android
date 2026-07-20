package com.minou.mvrviewer.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistances LOCALES liées à la synchro — PORTAGE de `PatchStore` et
 * `AuditStore` iOS. Même dossier projet que ProjectStore
 * (`filesDir/projects/<clé>/`), clé = empreinte de contenu du .mvr. N'écrivent
 * JAMAIS dans le .mvr d'origine.
 */

/** Édit de patch persisté (clé = uuid du projecteur, repli `name|layer`). */
data class PersistedPatchEdit(
    val key: String,
    val fixtureId: String?,
    val address: String?,
    val modeName: String?
)

private fun projectDir(ctx: Context, key: String): File =
    File(ctx.filesDir, "projects/$key").apply { mkdirs() }

/**
 * Patch DMX persisté localement (fixtureId / adresse / mode par clé de projecteur).
 * NOUVEAU comme sur iOS : jusqu'ici le patch ne vivait qu'en mémoire (perdu à la
 * fermeture). La synchro exige de le sérialiser ; effet de bord bénéfique : le
 * patch survit désormais à la réouverture, même hors-ligne.
 */
object PatchStore {
    private fun file(ctx: Context, key: String) = File(projectDir(ctx, key), "patch.json")

    fun load(ctx: Context, key: String): List<PersistedPatchEdit> {
        val arr = runCatching { JSONArray(file(ctx, key).readText()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PersistedPatchEdit(
                key = o.optString("key"),
                fixtureId = o.optStringOrNullP("fixtureId"),
                address = o.optStringOrNullP("address"),
                modeName = o.optStringOrNullP("modeName")
            )
        }
    }

    fun save(ctx: Context, key: String, edits: List<PersistedPatchEdit>) {
        val arr = JSONArray()
        for (e in edits) {
            val o = JSONObject().put("key", e.key)
            e.fixtureId?.let { o.put("fixtureId", it) }
            e.address?.let { o.put("address", it) }
            e.modeName?.let { o.put("modeName", it) }
            arr.put(o)
        }
        runCatching { file(ctx, key).writeText(arr.toString()) }
    }
}

/**
 * Journal de modifications LOCAL (audit, append-only) — qui a changé quoi.
 * `audit.json` dans le dossier projet. Persiste hors ligne ET indépendamment du
 * cloud (si connecté, aussi poussé/rapatrié). Dédupliqué par id.
 */
object AuditStore {
    private fun file(ctx: Context, key: String) = File(projectDir(ctx, key), "audit.json")

    fun load(ctx: Context, key: String): List<AuditEntry> {
        val arr = runCatching { JSONArray(file(ctx, key).readText()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            SectionCodec.auditFromMap(jsonObjToMap(arr.getJSONObject(i)))
        }
    }

    /** Ajoute (dédupliqué par id) et renvoie le journal complet. */
    fun append(ctx: Context, key: String, entries: List<AuditEntry>): List<AuditEntry> {
        if (entries.isEmpty()) return load(ctx, key)
        val all = load(ctx, key).toMutableList()
        val known = all.map { it.id }.toHashSet()
        for (e in entries) if (e.id !in known) { all.add(e); known.add(e.id) }
        val arr = JSONArray()
        for (e in all) arr.put(JSONObject(SectionCodec.auditToMap(e)))
        runCatching { file(ctx, key).writeText(arr.toString()) }
        return all
    }
}

private fun JSONObject.optStringOrNullP(k: String): String? =
    if (!has(k) || isNull(k)) null else optString(k)

private fun jsonObjToMap(o: JSONObject): Map<String, Any?> = buildMap {
    o.keys().forEach { k -> put(k, if (o.isNull(k)) null else o.get(k)) }
}
