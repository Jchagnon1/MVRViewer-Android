package com.minou.mvrviewer.ui

/**
 * GÉOMÉTRIE DES PANNEAUX DÉPLAÇABLES — calcul PUR, testable JUnit, portage
 * exact de `PanelPlacementMath.swift`. Un panneau se déplace « comme une
 * fenêtre » mais ne doit JAMAIS devenir irrécupérable : quoi qu'on ait glissé,
 * quoi que la rotation ait changé, une poignée saisissable reste à l'écran.
 */
object PanelPlacementMath {
    /** Ce qu'on exige de toujours voir : la hauteur d'un en-tête… */
    const val MIN_VISIBLE_HEIGHT = 56f
    /** …et assez de largeur pour le saisir au doigt. */
    const val MIN_VISIBLE_WIDTH = 80f

    /**
     * Borne un décalage (dx, dy) pour que le panneau reste saisissable.
     * `anchor*` = cadre du panneau à décalage NUL dans le repère du conteneur ;
     * `container*` = taille du conteneur. Tout est en pixels.
     *
     * Règle ASYMÉTRIQUE, et c'est voulu : on garde l'EN-TÊTE visible — le HAUT
     * du panneau ne sort jamais ni par le haut ni de plus de
     * [MIN_VISIBLE_HEIGHT] par le bas, mais le BAS peut déborder sous l'écran
     * (état normal d'un grand panneau à moitié tiré hors champ).
     */
    fun clamp(
        dx: Float, dy: Float,
        anchorX: Float, anchorY: Float, anchorW: Float, anchorH: Float,
        containerW: Float, containerH: Float
    ): Pair<Float, Float> {
        if (containerW <= 0f || containerH <= 0f || anchorW <= 0f || anchorH <= 0f ||
            !dx.isFinite() || !dy.isFinite()
        ) return 0f to 0f

        var cx = dx
        var cy = dy

        val minX = -(anchorX + anchorW) + MIN_VISIBLE_WIDTH
        val maxX = containerW - anchorX - MIN_VISIBLE_WIDTH
        // Bornes inversées (panneau dégénéré plus large que l'écran ET écran
        // minuscule) : on le laisse en place plutôt que de choisir un bord.
        cx = if (minX <= maxX) cx.coerceIn(minX, maxX) else 0f

        val minY = -anchorY
        val maxY = containerH - anchorY - MIN_VISIBLE_HEIGHT
        cy = if (minY <= maxY) cy.coerceIn(minY, maxY) else 0f

        return cx to cy
    }
}
