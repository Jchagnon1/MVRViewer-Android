package com.minou.mvrviewer.ui

import android.content.Intent

/**
 * Crochets de TEST (SPEC-menus-android §4.7), lus par `MainActivity` dans les
 * extras d'intent SEULEMENT si l'app est débogable (modèle de `MVR_PRO`). Chaque
 * crochet est consommé UNE fois ([take…]) : une recomposition, une rotation ou
 * un retour au premier plan ne le rejoue pas.
 *
 *   adb shell am start -n com.minou.mvrviewer/.MainActivity \
 *     -a android.intent.action.VIEW -d file:///data/data/com.minou.mvrviewer/files/mini.mvr \
 *     --ez MVR_AUTO_PLAN true --es MVR_AUTO_DEST cabling:dmx
 *
 * - `MVR_AUTO_PLAN=true` : le projet s'ouvre sur le plan (vue de base) ;
 * - `MVR_AUTO_DEST=<…>` : ouvre la destination ~2 s après l'affichage
 *   ([ProjectDestination.fromHook]) ;
 * - `MVR_AUTO_PLAN_START=<export|cal3|here>` : ouvre le plan avec cette action ;
 * - `MVR_AUTO_HOME_DEST=account` : ouvre « Compte & réglages » depuis l'accueil ;
 * - `MVR_AUTO_GEOPANEL`, `MVR_AUTO_REFPLAN_PANEL`, `MVR_AUTO_LABELS_PANEL` (tranche
 *   B) : ouvrent le panneau dans la vue courante ~2 s après l'affichage (le panneau
 *   Plan importé n'apparaît que s'il y a un plan).
 */
object DebugHooks {
    @Volatile private var autoPlan = false
    @Volatile private var autoDest: String? = null
    @Volatile private var autoPlanStart: String? = null
    @Volatile private var autoHomeDest: String? = null
    @Volatile private var autoPanels: ScenePanels? = null

    fun read(intent: Intent?, debuggable: Boolean) {
        if (!debuggable || intent == null) return
        if (intent.hasExtra("MVR_AUTO_PLAN")) {
            // Accepte --ez (booléen) comme --es « 1 » / « true ».
            autoPlan = intent.getBooleanExtra("MVR_AUTO_PLAN", false) ||
                intent.getStringExtra("MVR_AUTO_PLAN")?.trim()?.let { it == "1" || it.equals("true", true) } == true
        }
        intent.getStringExtra("MVR_AUTO_DEST")?.let { autoDest = it }
        intent.getStringExtra("MVR_AUTO_PLAN_START")?.let { autoPlanStart = it }
        intent.getStringExtra("MVR_AUTO_HOME_DEST")?.let { autoHomeDest = it }
        val geo = flag(intent, "MVR_AUTO_GEOPANEL")
        val ref = flag(intent, "MVR_AUTO_REFPLAN_PANEL")
        val labels = flag(intent, "MVR_AUTO_LABELS_PANEL")
        if (geo || ref || labels) autoPanels = ScenePanels.fromHooks(geo, ref, labels)
    }

    /** Booléen d'intent : `--ez X true` comme `--es X 1` / `true`. */
    private fun flag(intent: Intent, key: String): Boolean =
        intent.hasExtra(key) && (intent.getBooleanExtra(key, false) ||
            intent.getStringExtra(key)?.trim()?.let { it == "1" || it.equals("true", true) } == true)

    fun takeAutoPlan(): Boolean = autoPlan.also { autoPlan = false }
    fun takeAutoDest(): ProjectDestination? = ProjectDestination.fromHook(autoDest).also { autoDest = null }
    fun takeAutoPlanStart(): PlanStartAction = PlanStartAction.fromHook(autoPlanStart).also { autoPlanStart = null }
    fun takeAutoHomeDest(): String? = autoHomeDest.also { autoHomeDest = null }
    fun takeAutoPanels(): ScenePanels? = autoPanels.also { autoPanels = null }
}
