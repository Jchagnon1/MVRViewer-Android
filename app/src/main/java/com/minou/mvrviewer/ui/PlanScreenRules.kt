package com.minou.mvrviewer.ui

/**
 * Règles PURES de la vue plan, testées en JVM (pas d'émulateur) : PlanScreen les
 * applique telles quelles. Elles décident de ce qui s'arme, de ce qui se ferme et
 * de ce qui occupe le bas de l'écran ; l'écran ne fait que poser l'état.
 */

/**
 * Modes de toucher de la vue plan qui s'approprient le geste. Un seul « vrai »
 * mode à la fois, avec les mêmes exclusions qu'avant la mise en règle (le cadre
 * n'éteint ni le masquage ni le solo, par exemple).
 */
data class PlanTouchModes(
    val rect: Boolean = false,
    val mask: Boolean = false,
    val solo: Boolean = false,
    val measure: Boolean = false,
    /** « Je suis ici » (calibration par ancres) armé. */
    val anchorTap: Boolean = false,
    /** Assistant 3 points ouvert (ou en cours d'ouverture). */
    val threePoint: Boolean = false
)

/** Outils qu'on arme ou désarme d'un toucher, dans une barre ou dans le menu. */
enum class PlanTouchTool { RECT, MASK, SOLO, MEASURE, ANCHOR_TAP }

object PlanModeRules {
    /**
     * Bascule de [tool]. Désarmer ne touche qu'à lui. ARMER éteint ce qui lui
     * prendrait le geste et FERME l'assistant 3 points (iOS : armer un autre
     * mode de toucher quitte l'assistant, SPEC-calibration-3-points §2).
     */
    fun toggle(m: PlanTouchModes, tool: PlanTouchTool): PlanTouchModes = when (tool) {
        PlanTouchTool.RECT ->
            if (m.rect) m.copy(rect = false)
            else m.copy(rect = true, measure = false, threePoint = false)
        PlanTouchTool.MASK ->
            if (m.mask) m.copy(mask = false)
            else m.copy(mask = true, measure = false, solo = false, threePoint = false)
        PlanTouchTool.SOLO ->
            if (m.solo) m.copy(solo = false)
            else m.copy(solo = true, measure = false, mask = false, threePoint = false)
        PlanTouchTool.MEASURE ->
            if (m.measure) m.copy(measure = false)
            else m.copy(measure = true, rect = false, mask = false, solo = false, anchorTap = false, threePoint = false)
        PlanTouchTool.ANCHOR_TAP ->
            if (m.anchorTap) m.copy(anchorTap = false)
            else m.copy(anchorTap = true, measure = false, threePoint = false)
    }

    /**
     * Ouvrir l'assistant 3 points désarme TOUS les autres modes de toucher
     * (iOS `startThreePointCalibration` : cadre, masquage, solo, mesure,
     * « je suis ici »). L'affectation de câblage est quittée à part.
     */
    fun startThreePoint(): PlanTouchModes = PlanTouchModes(threePoint = true)
}

/** Ce qui s'affiche en bas du plan pour la sélection. */
enum class PlanSelectionBar { NONE, CARD, BANNER }

object PlanBottomRules {
    /**
     * Fiche du bas (1 projecteur) ou bannière jaune (plusieurs). Masquée pendant
     * l'assistant 3 points et pendant une affectation de câblage : le bas de
     * l'écran leur appartient (iOS PlanView `selectionBarShown`). La sélection,
     * elle, est gardée et réapparaît à la fermeture.
     */
    fun selectionBar(selectedCount: Int, threePointOpen: Boolean, assignOpen: Boolean): PlanSelectionBar = when {
        threePointOpen || assignOpen -> PlanSelectionBar.NONE
        selectedCount == 1 -> PlanSelectionBar.CARD
        selectedCount > 1 -> PlanSelectionBar.BANNER
        else -> PlanSelectionBar.NONE
    }
}

/**
 * CADRAGE du plan pendant l'assistant 3 points (SPEC-calibration-3-points §2) :
 * le plan est recadré AU-DESSUS du panneau quand il s'ouvre, quand le panneau
 * grandit (au-delà de la plus grande hauteur déjà cadrée + [slack]) « et après
 * une rotation » — tant que l'utilisateur n'a pas bougé le plan. Jamais à
 * chaque étape (pas de sautillement). Portage de PlanView iOS (`onChange(of:
 * size)` : `cal3FitReserve = cal3PanelReserve ; fitToContent`).
 */
object ThreePointPlanFit {
    /** Nouvelle vue : échelle utilisateur, décalage vertical (px) et réserve cadrée. */
    data class Fit(val scale: Float, val dy: Float, val reserve: Float)

    /**
     * @param planTop,planBottom bords du plan (px racine).
     * @param panelTop haut du cadre d'ANCRAGE du panneau (px racine).
     * @param fittedReserve plus grande réserve déjà cadrée (0 = jamais).
     * @param untouched le plan n'a pas bougé depuis le dernier cadrage (ou depuis
     *   l'ouverture s'il n'y en a pas encore eu).
     * @param sizeChanged la taille du plan a changé depuis le dernier cadrage
     *   (rotation) : on recadre même si la réserve a BAISSÉ.
     * @return la vue à poser, ou null pour ne rien changer.
     */
    fun refit(
        canvasW: Float, canvasH: Float, spanX: Float, spanY: Float,
        planTop: Float, planBottom: Float, panelTop: Float,
        fittedReserve: Float, untouched: Boolean, sizeChanged: Boolean, slack: Float
    ): Fit? {
        if (planBottom - planTop <= 0f || canvasW <= 0f || canvasH <= 0f) return null
        // Panneau pas encore mesuré, ou mesuré dans l'ancienne taille.
        if (panelTop <= planTop || panelTop >= planBottom) return null
        if (!untouched) return null
        val reserve = CablingAssignBarPlacement.reserve(planTop, planBottom, panelTop)
        if (!sizeChanged && reserve <= fittedReserve + slack) return null
        val (s, dy) = CablingAssignBarPlacement.fitAbove(canvasW, canvasH, spanX, spanY, reserve)
        return Fit(s, dy, reserve)
    }
}
