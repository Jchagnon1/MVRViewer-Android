package com.minou.mvrviewer.ui

import android.content.Context

/**
 * ÉTAT D'AFFICHAGE retrouvé à la réouverture de l'app — parité avec le
 * `ViewStateStore` iOS. Retour testeur : « un enregistrement de la vue
 * actuelle, la disposition des étiquettes, le fait qu'elles soient
 * apparentes, quand on quitte l'app ».
 *
 * GLOBAL par appareil, PAS par projet : ce sont des HABITUDES DE TRAVAIL
 * (« je lis mes plans avec l'ID et le patch, étiquettes visibles »), pas des
 * données d'un show — même raisonnement et même fichier de prefs que
 * [BackgroundColorStore] et [ToolbarLayoutStore]. RIEN ici ne passe par la
 * synchronisation cloud : deux personnes sur le même projet partagé lisent
 * leurs plans chacune à leur façon.
 *
 * ⚠️ PIÈGE COUVERT : quatre réglages ont TRUE pour défaut (couleurs par
 * calque, étiquettes, structures, légende). Un `getBoolean(k, false)` naïf
 * les aurait tous éteints à la première ouverture — chaque lecture passe son
 * défaut EXPLICITE, identique à celui déclaré dans [SceneOptions].
 */
object ViewStateStore {
    private const val PREFS = "mvrviewer.display"

    private const val KEY_MODE = "viewState.mode"
    private const val KEY_LAYER_COLORS = "viewState.layerColors"
    private const val KEY_SHOW_LABELS = "viewState.showLabels"
    private const val KEY_SHOW_STRUCTURE = "viewState.showStructure"
    private const val KEY_SHOW_LEGEND = "viewState.showLegend"
    private const val KEY_HIDE_ZOOMED_OUT = "viewState.hideLabelsWhenZoomedOut"
    private const val KEY_LABEL_FIELDS = "viewState.labelFields"
    private const val KEY_LABEL_DETACHED = "viewState.labelDetached"
    private const val KEY_LABEL_SIZE = "viewState.labelSize"
    private const val KEY_LABEL_OFFSET = "viewState.labelOffset"
    private const val KEY_SAT_OPACITY = "viewState.satelliteOpacity"
    private const val KEY_GPS_SCALE = "viewState.gpsMarkerScale"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Vue au dernier abandon. Seules 3D et PLAN sont MÉMORISABLES : rouvrir
     * l'app dans la liste de patch ou le câblage n'aurait pas de sens (ce sont
     * des outils ponctuels), et une valeur inconnue d'une future version
     * retombe sur la 3D au lieu de faire échouer le décodage.
     */
    fun mode(ctx: Context): SceneMode =
        when (prefs(ctx).getString(KEY_MODE, null)) {
            SceneMode.PLAN.name -> SceneMode.PLAN
            else -> SceneMode.THREE_D
        }

    fun setMode(ctx: Context, mode: SceneMode) {
        if (mode == SceneMode.THREE_D || mode == SceneMode.PLAN) {
            prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
        }
    }

    /** Sème les réglages d'affichage persistés dans des [SceneOptions] neuves. */
    fun seed(ctx: Context, o: SceneOptions) {
        val p = prefs(ctx)
        o.layerColors = p.getBoolean(KEY_LAYER_COLORS, true)
        o.showLabels = p.getBoolean(KEY_SHOW_LABELS, true)
        o.showStructure = p.getBoolean(KEY_SHOW_STRUCTURE, true)
        o.showLegend = p.getBoolean(KEY_SHOW_LEGEND, true)
        o.hideLabelsWhenZoomedOut = p.getBoolean(KEY_HIDE_ZOOMED_OUT, false)
        o.labelFields = readFields(p.getStringSet(KEY_LABEL_FIELDS, null),
                                   default = setOf(LabelContent.ID, LabelContent.DMX))
        o.labelDetached = readFields(p.getStringSet(KEY_LABEL_DETACHED, null),
                                     default = emptySet())
        o.labelSize = readFloat(p.getFloat(KEY_LABEL_SIZE, 1f), min = 0.5f, max = 2f)
        o.labelOffset = readFloat(p.getFloat(KEY_LABEL_OFFSET, 1f), min = 0f, max = 6f)
        o.satelliteOpacity = readFloat(p.getFloat(KEY_SAT_OPACITY, 0.55f), min = 0f, max = 1f)
        o.gpsMarkerScale = readFloat(p.getFloat(KEY_GPS_SCALE, 1f), min = 0.3f, max = 3f)
    }

    /** Écrit l'état courant — appelé par les effets de SceneScreen à chaque
     *  changement (UserDefaults-like : pas d'attente de mise en arrière-plan,
     *  une app tuée garde quand même son état). */
    fun save(ctx: Context, o: SceneOptions) {
        prefs(ctx).edit()
            .putBoolean(KEY_LAYER_COLORS, o.layerColors)
            .putBoolean(KEY_SHOW_LABELS, o.showLabels)
            .putBoolean(KEY_SHOW_STRUCTURE, o.showStructure)
            .putBoolean(KEY_SHOW_LEGEND, o.showLegend)
            .putBoolean(KEY_HIDE_ZOOMED_OUT, o.hideLabelsWhenZoomedOut)
            .putStringSet(KEY_LABEL_FIELDS, o.labelFields.map { it.name }.toSet())
            .putStringSet(KEY_LABEL_DETACHED, o.labelDetached.map { it.name }.toSet())
            .putFloat(KEY_LABEL_SIZE, o.labelSize)
            .putFloat(KEY_LABEL_OFFSET, o.labelOffset)
            .putFloat(KEY_SAT_OPACITY, o.satelliteOpacity)
            .putFloat(KEY_GPS_SCALE, o.gpsMarkerScale)
            .apply()
    }

    /** Un champ retiré d'une future version est IGNORÉ, pas fatal — on ne
     *  jette pas toute la liste pour un nom inconnu. Une clé ABSENTE rend le
     *  défaut ; un ensemble VIDE enregistré est une intention légitime
     *  (« aucun champ ») qui doit revenir vide. */
    private fun readFields(raw: Set<String>?, default: Set<LabelContent>): Set<LabelContent> {
        if (raw == null) return default
        return raw.mapNotNull { name ->
            LabelContent.entries.firstOrNull { it.name == name }
        }.toSet()
    }

    /** Une valeur hors bornes (prefs corrompues) ne doit pas rendre les
     *  étiquettes invisibles ou géantes : on ramène dans les bornes. */
    private fun readFloat(v: Float, min: Float, max: Float): Float =
        if (v.isFinite()) v.coerceIn(min, max) else 1f.coerceIn(min, max)
}
