package com.minou.mvrviewer.ui

// =============================================================================
// BARRES ANCRÉES : appareil, catalogues, autre place de chaque outil — calcul PUR
// (SPEC-menus-android §3.13, §4.6, tranche E), testé en JVM (ToolbarDefaultsTest,
// ToolHomeTest). Portage du modèle iOS (`ToolbarIdiom`, `catalog3D` /
// `catalogPlan`, `ToolId.home`), adapté aux outils propres à Android (§8 D18).
// =============================================================================

/** Laquelle des deux dispositions indépendantes on lit / écrit. */
enum class ToolbarView { SCENE_3D, PLAN }

/**
 * Famille d'appareil, pour la disposition PAR DÉFAUT seulement (le reste de
 * l'interface est identique) : tablette = plus petite largeur ≥ 600 dp, ou
 * crochet DEBUG `MVR_FORCE_TABLET_LAYOUT` (contrôle sur un téléphone).
 */
enum class ToolbarIdiom {
    PHONE, TABLET;

    companion object {
        /** Seuil Android usuel des tablettes (plus petite largeur, en dp). */
        const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

        fun of(smallestScreenWidthDp: Int, forceTablet: Boolean = false): ToolbarIdiom =
            if (forceTablet || smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP) TABLET else PHONE
    }
}

/**
 * Outils ANCRABLES de chaque vue, dans l'ordre du panneau « Barre d'outils de
 * cette vue… » — ordre iOS (`catalog3D`, `catalogPlan`), les outils propres à
 * Android insérés à côté de leurs voisins. Les écrans construisent leurs
 * [ToolSpec] puis les rangent par [ordered] : un outil hors catalogue n'est
 * jamais proposé. `colorMode`, `search` et `cameraPresets` n'y sont pas (aucun
 * `ToolSpec`, inertes, §6 A23).
 */
object ToolCatalog {
    val SCENE_3D: List<ToolId> = listOf(
        ToolId.RECT, ToolId.MULTI_SELECT, ToolId.CLEAR_SEL, ToolId.MEASURE, ToolId.SOLO, ToolId.CAM_RESET,
        ToolId.LABELS, ToolId.LABEL_SIZE, ToolId.LAYER_COLORS, ToolId.BACKGROUND,
        ToolId.DXF, ToolId.REF_PLAN_PANEL, ToolId.IMPORT_MODEL, ToolId.MODELS_PANEL,
        ToolId.GPS, ToolId.SATELLITE, ToolId.GEO_PANEL, ToolId.GPS_MARKER_SIZE,
        ToolId.GDTF_SHARE, ToolId.GDTF_MAPPING,
        ToolId.PATCH, ToolId.UNIVERSE, ToolId.CABLING, ToolId.PLAN_VIEW, ToolId.LAYERS,
        ToolId.ACCOUNT, ToolId.SHARE_PROJECT, ToolId.HISTORY
    )

    val PLAN: List<ToolId> = listOf(
        ToolId.RECT, ToolId.MULTI_SELECT, ToolId.CLEAR_SEL, ToolId.MEASURE,
        ToolId.LABELS, ToolId.LABEL_SETTINGS, ToolId.LAYER_COLORS, ToolId.LAYERS,
        ToolId.PATCH, ToolId.UNIVERSE, ToolId.CABLING,
        ToolId.MASK, ToolId.SOLO, ToolId.SHOW_ALL, ToolId.CLEAR_SOLO,
        ToolId.GEO_PANEL, ToolId.GPS, ToolId.CALIBRATE, ToolId.CALIBRATE_3, ToolId.SATELLITE,
        ToolId.DXF, ToolId.REF_PLAN_PANEL,
        ToolId.STRUCTURE, ToolId.LEGEND, ToolId.BACKGROUND, ToolId.EXPORT_PDF
    )

    fun of(view: ToolbarView): List<ToolId> = if (view == ToolbarView.SCENE_3D) SCENE_3D else PLAN

    /** [specs] rangés dans l'ordre du catalogue de [view] ; ceux qui n'y sont pas sont écartés. */
    fun ordered(view: ToolbarView, specs: List<ToolSpec>): List<ToolSpec> {
        val rank = of(view).withIndex().associate { (i, id) -> id to i }
        return specs.filter { it.id in rank }.sortedBy { rank.getValue(it.id) }
    }
}

/**
 * AUTRE PLACE d'un outil que sa barre (SPEC-menus-android §4.6, iOS `ToolHome`) :
 * aucun outil n'est joignable par sa seule barre — vider les barres ne retire
 * rien à l'utilisateur. Testée contre `MainMenuModel` et `GeoPanelContent`.
 */
sealed interface ToolHome {
    /** Entrée du menu ⋯, à toute profondeur. */
    data class Menu(val item: MainMenuItemId) : ToolHome
    /** Ligne du panneau « Position GPS » (ouvert par ⋯ › Position GPS…). */
    data class GeoPanel(val row: GeoPanelRow) : ToolHome
    /** Section « Services » de Compte & réglages (connexion GDTF Share, §8 D24). */
    data object AccountServices : ToolHome
    /** Bouton permanent de la vue (cadre, multi, recadrer). */
    data object PermanentButton : ToolHome
    /** Pastille « Vue » (bascule vers le plan). */
    data object ViewPill : ToolHome
    /** ✕ de la fiche de sélection (« Fermer ») et de la bannière multi (« Vider la sélection »). */
    data object SelectionClose : ToolHome
}

/**
 * Autre place de l'outil ; null pour les trois identifiants inertes (`colorMode`,
 * `search`, `cameraPresets`), qui n'ont pas de `ToolSpec` et ne sont dans aucun
 * catalogue (§6 A23).
 */
val ToolId.home: ToolHome?
    get() = when (this) {
        ToolId.RECT, ToolId.MULTI_SELECT, ToolId.CAM_RESET -> ToolHome.PermanentButton
        ToolId.PLAN_VIEW -> ToolHome.ViewPill
        ToolId.CLEAR_SEL -> ToolHome.SelectionClose
        ToolId.MEASURE -> ToolHome.Menu(MainMenuItemId.MEASURE)
        ToolId.LABELS -> ToolHome.Menu(MainMenuItemId.LABELS)
        ToolId.LABEL_SIZE, ToolId.LABEL_SETTINGS -> ToolHome.Menu(MainMenuItemId.LABEL_SETTINGS)
        ToolId.LAYER_COLORS -> ToolHome.Menu(MainMenuItemId.LAYER_COLORS)
        ToolId.BACKGROUND -> ToolHome.Menu(MainMenuItemId.BACKGROUND_COLOR)
        ToolId.STRUCTURE -> ToolHome.Menu(MainMenuItemId.STRUCTURE)
        ToolId.LEGEND -> ToolHome.Menu(MainMenuItemId.LEGEND)
        ToolId.LAYERS -> ToolHome.Menu(MainMenuItemId.LAYERS)
        ToolId.PATCH -> ToolHome.Menu(MainMenuItemId.PATCH)
        ToolId.UNIVERSE -> ToolHome.Menu(MainMenuItemId.UNIVERSE)
        ToolId.CABLING -> ToolHome.Menu(MainMenuItemId.CABLING)
        ToolId.GDTF_MAPPING -> ToolHome.Menu(MainMenuItemId.FIXTURE_MODELS)
        ToolId.GDTF_SHARE -> ToolHome.AccountServices
        ToolId.ACCOUNT -> ToolHome.Menu(MainMenuItemId.ACCOUNT_SETTINGS)
        ToolId.SHARE_PROJECT -> ToolHome.Menu(MainMenuItemId.SHARE_PROJECT)
        ToolId.HISTORY -> ToolHome.Menu(MainMenuItemId.HISTORY)
        ToolId.EXPORT_PDF -> ToolHome.Menu(MainMenuItemId.PLAN_PDF)
        ToolId.MASK -> ToolHome.Menu(MainMenuItemId.HIDE_ITEMS)
        ToolId.SOLO -> ToolHome.Menu(MainMenuItemId.ISOLATE)
        ToolId.SHOW_ALL -> ToolHome.Menu(MainMenuItemId.SHOW_ALL)
        ToolId.CLEAR_SOLO -> ToolHome.Menu(MainMenuItemId.CLEAR_SOLO)
        ToolId.DXF -> ToolHome.Menu(MainMenuItemId.IMPORT_PLAN)
        ToolId.REF_PLAN_PANEL -> ToolHome.Menu(MainMenuItemId.ADJUST_PLAN)
        ToolId.IMPORT_MODEL -> ToolHome.Menu(MainMenuItemId.IMPORT_MODEL)
        ToolId.MODELS_PANEL -> ToolHome.Menu(MainMenuItemId.IMPORTED_MODELS)
        ToolId.GEO_PANEL -> ToolHome.Menu(MainMenuItemId.GPS_POSITION)
        ToolId.GPS -> ToolHome.GeoPanel(GeoPanelRow.SHOW_MY_LOCATION)
        ToolId.SATELLITE -> ToolHome.GeoPanel(GeoPanelRow.SATELLITE)
        ToolId.CALIBRATE -> ToolHome.GeoPanel(GeoPanelRow.HERE_ANCHOR)
        ToolId.CALIBRATE_3 -> ToolHome.GeoPanel(GeoPanelRow.THREE_POINTS)
        ToolId.GPS_MARKER_SIZE -> ToolHome.GeoPanel(GeoPanelRow.MARKER_SIZE)
        ToolId.COLOR_MODE, ToolId.SEARCH, ToolId.CAM_PRESETS -> null
    }
