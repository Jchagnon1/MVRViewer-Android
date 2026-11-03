package com.minou.mvrviewer.ui

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import com.minou.mvrviewer.R

// =============================================================================
// HABILLAGE COMMUN DES DEUX VUES — règles PURES (SPEC-menus-android §3.1, §3.3,
// §3.4, §3.5, §3.14), testées en JVM : rangée du haut, pastille « Vue »,
// sélection (toucher / cadre / multi), pile bas-droite, cadrage du plan sous la
// pastille, bloc d'état 3D, bandeaux de mode du plan.
// =============================================================================

// ---------------------------------------------------------------- pastille Vue

/**
 * Points de vue de la 3D (pastille « Vue »), dans l'ordre de la spec. [eyeOffset]
 * = position de la caméra autour du pivot (repère Filament : Y en haut, +Z =
 * −Y MVR = « Face », +X = « Droite », même côté qu'iOS).
 */
enum class CameraViewpoint(@StringRes val labelRes: Int) {
    PERSPECTIVE(R.string.view_perspective),
    TOP(R.string.cam_top),
    FRONT(R.string.cam_front),
    BACK(R.string.view_back),
    LEFT(R.string.view_left),
    RIGHT(R.string.view_right);

    /**
     * Recul pour une vue [viewW] × [viewH] : les vues de côté (Face, Arrière,
     * Gauche, Droite) reculent d'autant que la vue est haute (téléphone en
     * portrait) — sinon les rangées de projecteurs sortaient par les bords
     * (constat émulateur sur `mini.mvr` : « Gauche » / « Droite » noires). Dessus et
     * Perspective gardent [base] (inchangés) ; en paysage (ou carré), rien ne change.
     */
    fun recoil(base: Float, viewW: Float, viewH: Float): Float {
        val side = this == FRONT || this == BACK || this == LEFT || this == RIGHT
        if (!side || !(viewW > 0f) || !(viewH > 0f) || viewH <= viewW) return base
        // Champ horizontal ≈ champ vertical × largeur / hauteur, plus une marge pour
        // que les rangées du bord ne soient pas coupées ; borné (vue très étroite).
        return base * (viewH / viewW * SIDE_MARGIN).coerceIn(1f, 3.5f)
    }

    private companion object { const val SIDE_MARGIN = 1.35f }

    /** (x, y, z) de la caméra par rapport au pivot, pour un recul [r]. */
    fun eyeOffset(r: Float): Triple<Float, Float, Float> = when (this) {
        // Ex-« Isométrique » : même position qu'avant.
        PERSPECTIVE -> Triple(0.7f * r, 0.7f * r, 0.7f * r)
        // Un soupçon de Z : une caméra pile à la verticale n'a pas d'orientation.
        TOP -> Triple(0f, r, 0.001f)
        FRONT -> Triple(0f, 0f, r)
        BACK -> Triple(0f, 0f, -r)
        LEFT -> Triple(-r, 0f, 0f)
        // Ex-« Côté ».
        RIGHT -> Triple(r, 0f, 0f)
    }
}

/** Ce que fait une ligne de la pastille. */
enum class ViewPillAction { VIEWPOINT, PROJECTION_TOP, SHOW_PLAN, SHOW_3D }

data class ViewPillEntry(
    val action: ViewPillAction,
    @StringRes val labelRes: Int,
    val checked: Boolean = false,
    val viewpoint: CameraViewpoint? = null
)

data class ViewPillSection(@StringRes val titleRes: Int?, val entries: List<ViewPillEntry>)

object ViewPillModel {
    /**
     * 3D : « Point de vue » (6 points de vue, coche sur le dernier appliqué) puis
     * « Vue plan 2D ». Plan : « Projection 2D » (Dessus seul, coché, §8 D6) puis
     * « Vue 3D ». Pas de « Mes vues » (n'existe pas sur Android, §8 D5).
     */
    fun sections(surface: ProjectSurface, current: CameraViewpoint): List<ViewPillSection> = when (surface) {
        ProjectSurface.SCENE_3D -> listOf(
            ViewPillSection(R.string.pill_section_viewpoint, CameraViewpoint.entries.map {
                ViewPillEntry(ViewPillAction.VIEWPOINT, it.labelRes, checked = it == current, viewpoint = it)
            }),
            ViewPillSection(null, listOf(ViewPillEntry(ViewPillAction.SHOW_PLAN, R.string.pill_plan_view_2d)))
        )
        ProjectSurface.PLAN -> listOf(
            ViewPillSection(R.string.pill_section_projection,
                listOf(ViewPillEntry(ViewPillAction.PROJECTION_TOP, R.string.cam_top, checked = true))),
            ViewPillSection(null, listOf(ViewPillEntry(ViewPillAction.SHOW_3D, R.string.nav_view_3d)))
        )
    }

    /** Format du libellé : « 3D · %s » / « Plan · %s ». */
    @StringRes
    fun labelFormatRes(surface: ProjectSurface): Int =
        if (surface == ProjectSurface.PLAN) R.string.pill_plan_fmt else R.string.pill_3d_fmt

    /** Valeur du libellé : le point de vue courant (3D), « Dessus » (plan). */
    @StringRes
    fun valueRes(surface: ProjectSurface, current: CameraViewpoint): Int =
        if (surface == ProjectSurface.PLAN) R.string.cam_top else current.labelRes
}

// ------------------------------------------------------------ rangée du haut

object SceneTopRowRules {
    /** Un bouton permanent est MASQUÉ quand son outil est ancré dans une barre (iOS §4.1). */
    fun showsRect(layout: ToolbarLayout): Boolean = ToolId.RECT !in layout.allAssigned()
    fun showsMulti(layout: ToolbarLayout): Boolean = ToolId.MULTI_SELECT !in layout.allAssigned()
    /** Recadrer (pile bas-droite) masqué si `resetCamera` est ancré ; Rechercher, jamais. */
    fun showsReframe(layout: ToolbarLayout): Boolean = ToolId.CAM_RESET !in layout.allAssigned()
}

// -------------------------------------------------------------------- sélection

/** Multi-sélection et cadre (iOS §4.1 : le cadre force le multi). */
data class SelectionModes(val multi: Boolean = false, val rect: Boolean = false) {
    /** Le toucher ajoute / retire au lieu de remplacer. */
    val adds: Boolean get() = multi || rect
}

/** Résultat d'une bascule : nouveaux modes, et faut-il vider la sélection. */
data class SelectionToggle(val modes: SelectionModes, val clearSelection: Boolean = false)

/**
 * Règle de sélection COMMUNE à la 3D et au plan (Q2 tranchée, §9.0) : toucher un
 * projecteur REMPLACE la sélection ; avec Multi-sélection (ou le cadre armé), il
 * l'ajoute ou le retire. Toucher le vide désélectionne, sauf en multi (on
 * compose une sélection, un toucher raté ne la perd pas). Le cadre AJOUTE ce
 * qu'il entoure. Indices génériques (projecteurs de la vue).
 */
object SelectionRules {
    fun <T> tap(selected: List<T>, hit: T?, modes: SelectionModes): List<T> = when {
        hit == null -> if (modes.adds) selected else emptyList()
        modes.adds -> if (hit in selected) selected - hit else selected + hit
        else -> listOf(hit)
    }

    /** Le cadre ajoute, sans doublon, dans l'ordre (l'ordre pilote l'adressage séquentiel). */
    fun <T> frame(selected: List<T>, enclosed: List<T>): List<T> {
        val out = ArrayList(selected)
        for (e in enclosed) if (e !in out) out.add(e)
        return out
    }

    /** Armer le cadre force le multi ; le désarmer laisse le multi tel quel. */
    fun toggleRect(m: SelectionModes): SelectionToggle =
        if (m.rect) SelectionToggle(m.copy(rect = false)) else SelectionToggle(m.copy(rect = true, multi = true))

    /** Couper le multi vide la sélection et coupe le cadre. */
    fun toggleMulti(m: SelectionModes): SelectionToggle =
        if (m.multi || m.rect) SelectionToggle(SelectionModes(), clearSelection = true)
        else SelectionToggle(m.copy(multi = true))
}

// ---------------------------------------------------------- cadrage du plan

/**
 * Cadrage AUTOMATIQUE du plan sous la pastille « Vue » (SPEC-menus-android §3.3,
 * portage Android de `PanelPlacementMath.planFitMargins` iOS) : le contenu se pose
 * ENTRE le bas mesuré de la pastille (+ [TOP_GAP_DP]) et le haut de la barre du
 * bas (barre d'affectation, panneau 3 points). Unités : px (gap et minimum en px).
 *
 * Règle d'origine conservée : sans pastille mesurée — ou si le contenu passe
 * déjà sous elle — le cadrage est exactement l'ancien ([CablingAssignBarPlacement.fitAbove],
 * ou la vue d'ouverture sans barre). Sinon, marges d'iOS : en haut le bas de la
 * pastille + [TOP_GAP_DP], en bas la barre + 20 à 50 dp. Rend (échelle utilisateur,
 * décalage vertical) à appliquer au cadrage de base du plan (contenu centré,
 * ajusté à 90 % du canevas).
 */
object PlanFitRules {
    /** Écart entre le bas de la pastille et le contenu (iOS `planFitTopGap`, 24 pt = 24 dp). */
    const val TOP_GAP_DP = 24f
    /** Hauteur minimale laissée au contenu (iOS, 40 pt = 40 dp) : la marge du haut cède d'abord. */
    const val MIN_CONTENT_DP = 40f

    fun underPill(
        canvasW: Float, canvasH: Float, spanX: Float, spanY: Float,
        topReserve: Float, bottomReserve: Float, topGap: Float, minContent: Float,
        /** px par dp : marges iOS (20–50 dp) dans l'unité du canevas. */
        dp: Float = 1f
    ): Pair<Float, Float> {
        if (!(canvasW > 0f && canvasH > 0f && spanX > 0f && spanY > 0f)) return 1f to 0f
        val r = if (bottomReserve.isFinite()) bottomReserve.coerceIn(0f, canvasH * 0.6f) else 0f
        // Règle d'origine (barre du bas seule).
        val (s0, dy0) = if (r > 0f) CablingAssignBarPlacement.fitAbove(canvasW, canvasH, spanX, spanY, r) else (1f to 0f)
        val t = if (topReserve.isFinite()) topReserve.coerceIn(0f, canvasH * 0.4f) else 0f
        if (t <= 0f) return s0 to dy0
        val base = minOf(canvasW / spanX, canvasH / spanY)
        val top0 = canvasH / 2f + dy0 - 0.9f * base * s0 * spanY / 2f
        // Le contenu passe déjà sous la pastille : rien ne change.
        if (top0 >= t + topGap) return s0 to dy0
        // Marges d'iOS (planFitMargins) : sans barre du bas, 50 dp ; avec, le quart
        // de la hauteur restante, entre 20 et 50 dp — les étiquettes de la rangée du
        // bas ne passent ni sous la barre ni sous le bord.
        val u = if (dp.isFinite() && dp > 0f) dp else 1f
        val padding = if (r > 0f) ((canvasH - t - r) * 0.25f).coerceIn(40f * u, 100f * u) else 100f * u
        var topM = maxOf(padding / 2f, t + topGap)
        val bottomM = r + padding / 2f
        // Toujours un minimum de place pour le contenu : la marge du haut cède d'abord.
        if (canvasH - topM - bottomM < minContent) topM = maxOf(padding / 2f, canvasH - bottomM - minContent)
        val contentH = (canvasH - topM - bottomM).coerceAtLeast(1f)
        val wanted = minOf((canvasW - padding).coerceAtLeast(1f) / spanX, contentH / spanY)
        // Jamais agrandi au-delà du cadrage normal (comme fitAbove).
        val s = minOf(1f, wanted / (0.9f * base)).coerceAtLeast(0f)
        // Centre du contenu au milieu de la bande [topM, canvasH − bottomM].
        return s to (topM - bottomM) / 2f
    }
}

// ------------------------------------------------- pastille de synchro (plan)

object PlanSyncBadgeRules {
    /**
     * Haut (px racine) de la pastille de synchro du plan (§8 D21) : à droite sous
     * la rangée du haut, alignée à droite ; si elle chevaucherait la pastille
     * « Vue » centrée (écran étroit, texte long), elle passe SOUS celle-ci,
     * toujours à droite. [badgeWidth] ≤ 0 (pas encore mesurée) : sous la rangée.
     */
    fun top(rowBottom: Float, pill: Rect, badgeWidth: Float, containerRight: Float, margin: Float, gap: Float): Float {
        val under = rowBottom + gap
        if (badgeWidth <= 0f || pill.width <= 0f || pill.height <= 0f) return under
        val left = containerRight - margin - badgeWidth
        val overlaps = left < pill.right + gap && under < pill.bottom
        return if (overlaps) pill.bottom + gap else under
    }
}

// ---------------------------------------------------------- bloc d'état (3D)

/** Une pastille du bloc d'état (§3.5), chacune seulement si elle a quelque chose à dire. */
sealed interface StatusLine {
    /** Pendant le chargement : étape + pourcentage global. */
    data class Loading(@StringRes val stepRes: Int?, val percent: Int) : StatusLine
    /** Rien de posé encore, sans progression pilotée : « chargement 3D… ». */
    data object Starting : StatusLine
    /** « %d objets » (pluriel) + « (tronqué) » si le plafond d'objets est atteint (§8 D14). */
    data class Objects(val count: Int, val truncated: Boolean) : StatusLine
    /** « %d classes masquées » (pluriel). */
    data class HiddenClasses(val count: Int) : StatusLine
    /** « Modèles GDTF en cours… » + petit indicateur. */
    data object GdtfLoading : StatusLine
}

object StatusBlockRules {
    fun lines(
        loading: Boolean, @StringRes stepRes: Int?, percent: Int,
        placed: Int?, truncated: Boolean, hiddenClasses: Int, gdtfLoading: Boolean
    ): List<StatusLine> = buildList {
        add(when {
            loading -> StatusLine.Loading(stepRes, percent)
            placed == null -> StatusLine.Starting
            else -> StatusLine.Objects(placed, truncated)
        })
        if (hiddenClasses > 0) add(StatusLine.HiddenClasses(hiddenClasses))
        if (gdtfLoading) add(StatusLine.GdtfLoading)
    }
}

// --------------------------------------------------- bandeaux de mode (plan)

/** Bandeau en haut du plan, sous la pastille, dont l'icône de tête quitte le mode (§3.14). */
enum class PlanModeBanner(@StringRes val textRes: Int) {
    HIDE_TAP(R.string.mode_hide_tap),
    HIDE_BOX(R.string.mode_hide_box),
    ISOLATE_TAP(R.string.mode_isolate_tap),
    ISOLATE_BOX(R.string.mode_isolate_box);

    companion object {
        /** Masquage ou solo armé (exclusifs) ; « cadre » si la sélection par cadre l'est aussi. */
        fun of(mask: Boolean, solo: Boolean, rect: Boolean): PlanModeBanner? = when {
            mask -> if (rect) HIDE_BOX else HIDE_TAP
            solo -> if (rect) ISOLATE_BOX else ISOLATE_TAP
            else -> null
        }
    }
}
