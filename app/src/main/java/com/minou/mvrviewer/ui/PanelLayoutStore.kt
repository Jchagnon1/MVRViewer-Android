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
        /** Assistant de calibration GPS en 3 points (clé iOS `geoCalibration3Plan`). */
        GEO_CALIBRATION_3_PLAN("geoCalibration3Plan"),
        // ---- Rangement des menus, tranche B (SPEC-menus-android §3.6–3.8) ----
        /** Panneau « Position GPS » de la 3D (clé iOS `geoPosition3D`). */
        GEO_POSITION_3D("geoPosition3D"),
        /** Panneau « Position GPS » du plan (clé iOS `geoPositionPlan`). */
        GEO_POSITION_PLAN("geoPositionPlan"),
        /** Panneau « Plan importé » de la 3D (clé iOS `referencePlan3D`). */
        REF_PLAN_3D("referencePlan3D"),
        /**
         * Panneau « Plan importé » du plan, désormais en BAS-GAUCHE : nouvelle clé
         * (propre à Android, §8 D13) — l'ancienne [REF_PLAN_PLACEMENT_PLAN], mesurée
         * depuis le coin bas-droit, reste dans l'énumération sans usage.
         */
        REF_PLAN_PLAN_LEADING("refPlanPlacementPlanLeading"),
        /** Panneau « Réglage des étiquettes » de la 3D (clé iOS `labels3D`). */
        LABELS_3D("labels3D"),
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
