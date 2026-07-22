package com.minou.mvrviewer.ui

import android.content.Context

/**
 * Persistance LOCALE et GLOBALE (par appareil) des dispositions de barres N11 —
 * PAS par projet, PAS synchronisée cloud. Même patron que [BackgroundColorStore]
 * / RecentProjects : object singleton sur SharedPreferences, valeur JSON via
 * org.json, DÉFAUT explicite quand la clé est absente (= barre bas-gauche
 * actuelle → rien ne casse pour qui ne personnalise pas).
 */
object ToolbarLayoutStore {
    private const val PREFS = "mvrviewer.display" // même fichier que BackgroundColorStore
    private const val KEY_3D = "toolbar.layout.scene3D"
    private const val KEY_PLAN = "toolbar.layout.plan"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load3D(ctx: Context): ToolbarLayout =
        ToolbarLayout.fromJson(prefs(ctx).getString(KEY_3D, null), ToolbarLayout.default3D)

    fun loadPlan(ctx: Context): ToolbarLayout =
        ToolbarLayout.fromJson(prefs(ctx).getString(KEY_PLAN, null), ToolbarLayout.defaultPlan)

    fun save3D(ctx: Context, layout: ToolbarLayout) =
        prefs(ctx).edit().putString(KEY_3D, layout.toJson()).apply()

    fun savePlan(ctx: Context, layout: ToolbarLayout) =
        prefs(ctx).edit().putString(KEY_PLAN, layout.toJson()).apply()
}
