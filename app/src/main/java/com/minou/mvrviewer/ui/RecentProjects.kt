package com.minou.mvrviewer.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un .mvr ouvert récemment (pour le rouvrir sans re-naviguer). */
data class RecentProject(val uri: String, val name: String, val lastMs: Long)

/**
 * Liste des projets récents, persistée dans les SharedPreferences (équivalent
 * du « projets récents » iOS). On mémorise l'URI (permission SAF rendue
 * persistante par ailleurs), le nom affiché et la date de dernière ouverture.
 */
object RecentProjects {
    private const val PREFS = "mvrviewer.recents"
    private const val KEY = "list"
    private const val MAX = 12

    fun list(ctx: Context): List<RecentProject> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentProject(o.getString("uri"), o.getString("name"), o.optLong("last"))
            }.sortedByDescending { it.lastMs }
        }.getOrDefault(emptyList())
    }

    /** Enregistre/rafraîchit une ouverture (dédupliqué par URI, plafonné à MAX). */
    fun record(ctx: Context, uri: String, name: String, nowMs: Long) {
        val cur = list(ctx).filter { it.uri != uri }
        val updated = (listOf(RecentProject(uri, name, nowMs)) + cur).take(MAX)
        save(ctx, updated)
    }

    fun remove(ctx: Context, uri: String) = save(ctx, list(ctx).filter { it.uri != uri })

    private fun save(ctx: Context, items: List<RecentProject>) {
        val arr = JSONArray()
        for (p in items) arr.put(JSONObject().put("uri", p.uri).put("name", p.name).put("last", p.lastMs))
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
