package com.minou.mvrviewer.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Clés / valeurs où vivent les dispositions — interface minimale pour que la
 * lecture, l'écriture et la MIGRATION se testent en JVM sans Android
 * (`ToolbarDefaultsTest`) ; dans l'app, ce sont les `SharedPreferences`.
 */
interface ToolbarPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
}

private class SharedToolbarPrefs(private val p: SharedPreferences) : ToolbarPrefs {
    override fun getString(key: String): String? = p.getString(key, null)
    override fun putString(key: String, value: String) { p.edit().putString(key, value).apply() }
    override fun remove(key: String) { p.edit().remove(key).apply() }
    override fun getBoolean(key: String): Boolean = p.getBoolean(key, false)
    override fun putBoolean(key: String, value: Boolean) { p.edit().putBoolean(key, value).apply() }
}

/**
 * Persistance LOCALE et GLOBALE (par appareil) des dispositions de barres N11 —
 * PAS par projet, PAS synchronisée cloud (patron [BackgroundColorStore]). Clés
 * inchangées depuis N11.
 *
 * Règles (SPEC-menus-android §3.13, iOS `ToolbarLayoutStore.load(view:idiom:)`) :
 * - rien d'enregistré pour une vue → défaut de l'APPAREIL (tablette : barre gauche
 *   préremplie ; téléphone : vide) ;
 * - une disposition ENREGISTRÉE est toujours respectée : vidée (« Tout remettre
 *   hors barres »), elle reste vide ;
 * - on n'enregistre plus qu'au changement fait par l'utilisateur ([save]) ;
 * - MIGRATION v2, une fois ([migrate]) : avant la tranche E, la scène
 *   enregistrait la disposition vide dès la première ouverture — une disposition
 *   enregistrée VIDE d'avant compte donc comme « non personnalisée » et est
 *   effacée (la tablette reçoit sa barre) ; et l'ancien outil Android `gdtfShare`
 *   (qui ouvrait les modèles) devient `gdtfMapping` « Modèles de projecteurs ».
 */
object ToolbarLayoutStore {
    private const val PREFS = "mvrviewer.display" // même fichier que BackgroundColorStore
    const val KEY_3D = "toolbar.layout.scene3D"
    const val KEY_PLAN = "toolbar.layout.plan"
    const val KEY_MIGRATED_V2 = "toolbar.layout.v2.done"

    fun key(view: ToolbarView): String = if (view == ToolbarView.SCENE_3D) KEY_3D else KEY_PLAN

    /** Migration v2 (une seule fois, avant toute lecture). */
    fun migrate(p: ToolbarPrefs) {
        if (p.getBoolean(KEY_MIGRATED_V2)) return
        for (view in ToolbarView.entries) {
            val k = key(view)
            val raw = p.getString(k) ?: continue
            val saved = ToolbarLayout.fromJson(raw, ToolbarLayout.EMPTY)
            when {
                // Vide d'avant = enregistrée par la scène elle-même, jamais choisie.
                saved.isEmpty -> p.remove(k)
                ToolId.GDTF_SHARE in saved.allAssigned() ->
                    p.putString(k, saved.replacing(ToolId.GDTF_SHARE, ToolId.GDTF_MAPPING).toJson())
            }
        }
        p.putBoolean(KEY_MIGRATED_V2, true)
    }

    fun load(p: ToolbarPrefs, view: ToolbarView, idiom: ToolbarIdiom): ToolbarLayout {
        migrate(p)
        return ToolbarLayout.fromJson(p.getString(key(view)), ToolbarLayout.defaultLayout(view, idiom))
    }

    /** Changement fait par l'utilisateur (seul moment où l'on enregistre). */
    fun save(p: ToolbarPrefs, view: ToolbarView, layout: ToolbarLayout) = p.putString(key(view), layout.toJson())

    /** « Réinitialiser » : plus rien d'enregistré pour [view] → défaut de l'appareil. */
    fun clear(p: ToolbarPrefs, view: ToolbarView) = p.remove(key(view))

    /** Crochet DEBUG `MVR_RESET_TOOLBARS` : les deux vues repartent du défaut de l'appareil. */
    fun reset(p: ToolbarPrefs) = ToolbarView.entries.forEach { clear(p, it) }

    private fun prefs(ctx: Context): ToolbarPrefs =
        SharedToolbarPrefs(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    fun load(ctx: Context, view: ToolbarView, idiom: ToolbarIdiom): ToolbarLayout = load(prefs(ctx), view, idiom)
    fun save(ctx: Context, view: ToolbarView, layout: ToolbarLayout) = save(prefs(ctx), view, layout)
    fun clear(ctx: Context, view: ToolbarView) = clear(prefs(ctx), view)
    fun reset(ctx: Context) = reset(prefs(ctx))
}
