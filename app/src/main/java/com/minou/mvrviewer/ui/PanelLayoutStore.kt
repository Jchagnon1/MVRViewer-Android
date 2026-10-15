package com.minou.mvrviewer.ui

import android.content.Context
import org.json.JSONObject

/**
 * POSITION ET REPLI des panneaux flottants — persistés pour qu'aménager ses
 * fenêtres soit un réglage durable, pas un geste à refaire. GLOBAL par
 * appareil (même fichier de prefs que [BackgroundColorStore] /
 * [ViewStateStore]), parité `PanelLayoutStore` iOS.
 */
object PanelLayoutStore {
    private const val PREFS = "mvrviewer.display"

    /** Identités STABLES — mêmes rawValues qu'iOS ; on n'en renomme jamais,
     *  on en ajoute. `exportPlan` est un ajout Android (le composeur d'export
     *  PDF n'existe pas en panneau côté iOS). */
    enum class PanelID(val key: String) {
        IMPORTED_MODELS_3D("importedModels3D"),
        REF_PLAN_PLACEMENT_PLAN("refPlanPlacementPlan"),
        LABELS_PLAN("labelsPlan"),
        EXPORT_PLAN("exportPlan"),
        /** Barre d'affectation du câblage sur le plan (clé iOS `cablingAssignPlan`). */
        CABLING_ASSIGN_PLAN("cablingAssignPlan"),
    }

    data class Placement(val dx: Float = 0f, val dy: Float = 0f, val collapsed: Boolean = false) {
        val isZero: Boolean get() = dx == 0f && dy == 0f && !collapsed
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(id: PanelID) = "panel.layout.${id.key}"

    fun load(ctx: Context, id: PanelID): Placement {
        val raw = prefs(ctx).getString(key(id), null) ?: return Placement()
        return try {
            val o = JSONObject(raw)
            val dx = o.optDouble("dx", 0.0).toFloat()
            val dy = o.optDouble("dy", 0.0).toFloat()
            Placement(
                if (dx.isFinite()) dx else 0f,
                if (dy.isFinite()) dy else 0f,
                o.optBoolean("collapsed", false)
            )
        } catch (_: Exception) {
            Placement()
        }
    }

    fun save(ctx: Context, id: PanelID, p: Placement) {
        val e = prefs(ctx).edit()
        // Position d'origine et déplié = état neuf : on efface l'entrée.
        if (p.isZero) e.remove(key(id))
        else e.putString(key(id), JSONObject()
            .put("dx", p.dx.toDouble()).put("dy", p.dy.toDouble())
            .put("collapsed", p.collapsed).toString())
        e.apply()
    }
}
