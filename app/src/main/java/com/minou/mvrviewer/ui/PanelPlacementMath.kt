package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect

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

    /** Écart entre deux éléments empilés du bas (iOS `cornerStackGap`, 8 pt = 8 dp). */
    const val CORNER_STACK_GAP = 8f

    /** Plus petite hauteur utile d'un panneau flottant : l'en-tête et deux lignes (iOS, 120 pt = 120 dp). */
    const val FLOATING_PANEL_MIN_HEIGHT = 120f

    /**
     * Place d'un panneau flottant du bas-gauche : [lift] = de combien il monte
     * au-dessus de sa place naturelle, [maxHeight] = sa hauteur maximale (null :
     * rien de mesuré, aucune borne).
     */
    data class FloatingPanelSlot(val lift: Float, val maxHeight: Float?)

    /**
     * Place d'un panneau flottant du bas-gauche (Position GPS, Plan importé,
     * Réglage des étiquettes) ENTRE ce qui occupe le haut de sa colonne et ce qui
     * occupe le bas — portage exact de `PanelPlacementMath.floatingPanelSlot` iOS
     * (SPEC-menus §5.5, SPEC-menus-android §3.6) : une fenêtre ne cache jamais ce
     * qu'elle règle, et un panneau ne chevauche pas les éléments permanents.
     *
     * [natural] = repère de la place NATURELLE du panneau (sa colonne en largeur,
     * son bas), mesuré SANS la montée : la place ne se nourrit jamais d'elle-même.
     * Seuls les obstacles qui croisent la colonne comptent (vides ignorés). Le
     * panneau ne couvre JAMAIS ce qui occupe le bas ; sans place (paysage, fiche
     * ouverte), il garde [minHeight] et c'est son haut qui passe sur le haut de
     * l'écran (on le replie ou on le tire d'un geste). Unités libres mais communes.
     */
    fun floatingPanelSlot(
        natural: Rect, topObstacles: List<Rect>, bottomObstacles: List<Rect>,
        topLimit: Float, gap: Float = CORNER_STACK_GAP, minHeight: Float = FLOATING_PANEL_MIN_HEIGHT
    ): FloatingPanelSlot {
        if (natural.width <= 0f || !natural.left.isFinite() || !natural.right.isFinite() ||
            !natural.bottom.isFinite()
        ) return FloatingPanelSlot(0f, null)
        fun usable(r: Rect) = r.width > 0f && r.height > 0f &&
            r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite() &&
            // Même colonne : chevauchement horizontal strict.
            r.right > natural.left && r.left < natural.right
        val naturalBottom = natural.bottom
        // Bas : au-dessus de ce qui occupe le bas de la colonne.
        var bottom = naturalBottom
        for (o in bottomObstacles) if (usable(o) && o.top < bottom + gap) bottom = minOf(bottom, o.top - gap)
        // Haut : sous ce qui occupe le haut de la colonne.
        var top = if (topLimit.isFinite()) maxOf(0f, topLimit) else 0f
        for (o in topObstacles) if (usable(o) && o.bottom + gap > top) top = o.bottom + gap
        // Jamais hors de l'écran par le haut, même sans place.
        bottom = maxOf(bottom, minHeight)
        val room = bottom - top
        return FloatingPanelSlot(lift = maxOf(0f, naturalBottom - bottom), maxHeight = maxOf(room, minHeight))
    }

    /**
     * De combien un élément ([anchor], cadre SANS montée) doit MONTER pour
     * qu'aucun [obstacles] de sa colonne ne le recouvre — portage exact de
     * `PanelPlacementMath.clearanceLift` iOS (mention du fond satellite) : la
     * place libre LA PLUS PROCHE au-dessus, juste au-dessus d'un obstacle sans en
     * toucher aucun, jamais plus haut que [topLimit]. 0 si rien ne gêne, ou si
     * aucune place n'existe (l'élément reste alors à sa place). Cadres vides
     * ignorés. Unités libres mais communes ([gap] et la tolérance d'un demi-point
     * sont en points iOS = dp).
     */
    fun clearanceLift(anchor: Rect, obstacles: List<Rect>, topLimit: Float, gap: Float = CORNER_STACK_GAP): Float {
        if (anchor.width <= 0f || anchor.height <= 0f ||
            !anchor.top.isFinite() || !anchor.bottom.isFinite() ||
            !anchor.left.isFinite() || !anchor.right.isFinite()
        ) return 0f
        // Obstacles de la COLONNE (chevauchement horizontal strict).
        val column = obstacles.filter {
            it.width > 0f && it.height > 0f &&
                it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
                it.right > anchor.left && it.left < anchor.right
        }
        // Demi-point de tolérance : posé « pile à gap » d'un obstacle n'est pas un contact.
        val tolerance = 0.5f
        fun blocked(top: Float): Boolean {
            val bottom = top + anchor.height
            return column.any { it.top < bottom + gap - tolerance && it.bottom + gap - tolerance > top }
        }
        if (!blocked(anchor.top)) return 0f
        // Places candidates : juste au-dessus de chaque obstacle, la plus proche d'abord.
        val candidates = column
            .map { it.top - gap - anchor.height }
            .filter { it < anchor.top && it >= topLimit }
            .sortedDescending()
        for (top in candidates) if (!blocked(top)) return anchor.top - top
        return 0f
    }
}
