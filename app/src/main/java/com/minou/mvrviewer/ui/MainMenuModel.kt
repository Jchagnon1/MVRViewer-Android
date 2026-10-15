package com.minou.mvrviewer.ui

import androidx.annotation.StringRes
import com.minou.mvrviewer.R

// =============================================================================
// MENU ⋯ UNIQUE — modèle PUR (SPEC-menus-android §3.2, §4.4), testé en JVM.
// =============================================================================
//
// UN SEUL menu, identique en 3D et dans le plan : même place (haut-droite), même
// premier niveau (10 lignes), mêmes familles. Seules changent les entrées qui
// n'ont pas de sens dans une vue (Décor 3D dans le plan, Masquer des éléments en
// 3D…) ou dont la donnée manque (pas de plan importé, pas de câblage…). Jamais
// selon l'achat Pro : le contexte n'a PAS de champ « isPro » (§1.2).
//
// Les familles s'ouvrent en PAGES dans le même menu (« ‹ Affichage » revient au
// premier niveau) ; un choix de valeur (Couleur du fond, Colorer par câblage)
// ouvre une 3e page. Jamais d'action au-delà de la page de famille (§2.8 iOS).

/** Pages du menu ⋯. */
enum class MainMenuPage {
    TOP, DISPLAY, HIDE_ISOLATE, PATCH_CABLING, PLAN_SCENERY, SHARE_EXPORT,
    /** Choix de valeur (3e niveau) : Colorer par câblage. */
    COLOR_BY_CABLING,
    /** Choix de valeur (3e niveau) : Couleur du fond. */
    BACKGROUND_COLOR;

    /** Page parente (en-tête « ‹ … » et retour système) ; null au premier niveau. */
    val parent: MainMenuPage?
        get() = when (this) {
            TOP -> null
            COLOR_BY_CABLING, BACKGROUND_COLOR -> DISPLAY
            else -> TOP
        }

    /** 0 = premier niveau, 1 = famille, 2 = choix de valeur. */
    val depth: Int get() = parent?.let { it.depth + 1 } ?: 0

    /** Titre de la page (ligne « ‹ Titre » qui ramène à la page parente). */
    @get:StringRes
    val titleRes: Int?
        get() = when (this) {
            TOP -> null
            DISPLAY -> R.string.menu_display
            HIDE_ISOLATE -> R.string.menu_hide_isolate
            PATCH_CABLING -> R.string.menu_patch_cabling
            PLAN_SCENERY -> R.string.menu_plan_scenery
            SHARE_EXPORT -> R.string.menu_share_export
            COLOR_BY_CABLING -> R.string.menu_colour_by_cabling
            BACKGROUND_COLOR -> R.string.menu_section_background
        }
}

/**
 * Identité STABLE de chaque entrée du menu : c'est elle que la vue reçoit au
 * toucher (`onItem`), jamais un libellé.
 */
enum class MainMenuItemId {
    // Premier niveau, rangées d'icônes
    MEASURE, LABELS, LAYERS, PATCH, UNIVERSE, CABLING,
    // Premier niveau, lignes
    DISPLAY, HIDE_ISOLATE, PATCH_CABLING, PLAN_SCENERY, GPS_POSITION, SHARE_EXPORT,
    ACCOUNT_SETTINGS, CLOSE_PROJECT,
    // ⋯ › Affichage
    LABEL_SETTINGS, LAYER_COLORS, COLOR_BY_CABLING, BACKGROUND_COLOR, STRUCTURE, LEGEND, TOOLBAR_THIS_VIEW,
    // ⋯ › Masquer & isoler
    HIDE_ITEMS, ISOLATE, SHOW_ALL, CLEAR_SOLO,
    // ⋯ › Patch & câblage
    FIXTURE_MODELS, CABLING_ELECTRICAL, CABLING_DMX, CABLING_POWERS,
    // ⋯ › Plan & décor
    IMPORT_PLAN, ADJUST_PLAN, IMPORT_MODEL, IMPORTED_MODELS,
    // ⋯ › Partager & exporter
    SHARE_PROJECT, HISTORY, PLAN_PDF, CABLING_PDF_FULL, CABLING_PDF_ELEC, CABLING_PDF_DMX, CABLING_PDF_PLANS;

    /**
     * Entrée traitée par la SCÈNE (SceneScreen : destinations, import du plan,
     * fermeture, PDF de câblage) ; les autres sont traitées par la vue courante
     * (bascules, panneaux, mode export du plan…).
     */
    val isProjectLevel: Boolean
        get() = this in PROJECT_LEVEL

    private companion object {
        val PROJECT_LEVEL = setOf(
            LAYERS, PATCH, UNIVERSE, CABLING, ACCOUNT_SETTINGS, CLOSE_PROJECT,
            FIXTURE_MODELS, CABLING_ELECTRICAL, CABLING_DMX, CABLING_POWERS,
            IMPORT_PLAN, SHARE_PROJECT, HISTORY,
            CABLING_PDF_FULL, CABLING_PDF_ELEC, CABLING_PDF_DMX, CABLING_PDF_PLANS
        )
    }
}

/** Nature d'une entrée : ce que fait son toucher. */
enum class MainMenuKind {
    /** Action : referme le menu. */
    ACTION,
    /** Bascule : le menu RESTE ouvert (comportement Android). */
    TOGGLE,
    /** Famille : remplace le contenu du menu par sa page. */
    FAMILY,
    /** Choix de valeur : ouvre la page de valeurs (boutons radio). */
    VALUE_CHOICE
}

/**
 * Une entrée du menu. [labelRes] est une ressource RÉELLE (vérifiée en fr / en
 * par les tests) ; [labelArg] remplit un « %d » éventuel ; [opens] = page ouverte
 * par une famille ou un choix de valeur ; [enabled] = faux seulement par absence
 * de donnée (jamais selon Pro).
 */
data class MainMenuItem(
    val id: MainMenuItemId,
    @StringRes val labelRes: Int,
    val kind: MainMenuKind,
    val opens: MainMenuPage? = null,
    val enabled: Boolean = true,
    val labelArg: Int? = null
)

/**
 * Une section : titre facultatif, entrées ; [row] = rangée de trois cases icône +
 * libellé (`MenuIconRow`, rangées 1 et 2 du premier niveau) ; [divider] = filet
 * au-dessus.
 */
data class MainMenuSection(
    @StringRes val titleRes: Int? = null,
    val items: List<MainMenuItem>,
    val row: Boolean = false,
    val divider: Boolean = false
)

/**
 * Ce qui décide des apparitions (§4.4). PAS de « isPro » : un gratuit et un Pro
 * voient exactement les mêmes entrées.
 */
data class MainMenuContext(
    val surface: ProjectSurface,
    val hasRefPlan: Boolean = false,
    val importedModelCount: Int = 0,
    /** Synchro branchée (toujours dans l'app ; faux dans les tests sans service). */
    val hasSync: Boolean = true,
    /** Au moins un distributeur électrique ou une ligne DMX. */
    val hasCabling: Boolean = false,
    /** Modes de coloration ayant des données (Calque toujours présent). */
    val colorModes: List<PlanColorMode> = listOf(PlanColorMode.LAYER),
    val hasHiddenItems: Boolean = false,
    val soloActive: Boolean = false,
    val hasSelection: Boolean = false
)

object MainMenuModel {
    private fun action(id: MainMenuItemId, res: Int, enabled: Boolean = true, arg: Int? = null) =
        MainMenuItem(id, res, MainMenuKind.ACTION, enabled = enabled, labelArg = arg)
    private fun toggle(id: MainMenuItemId, res: Int, enabled: Boolean = true) =
        MainMenuItem(id, res, MainMenuKind.TOGGLE, enabled = enabled)
    private fun family(id: MainMenuItemId, res: Int, page: MainMenuPage) =
        MainMenuItem(id, res, MainMenuKind.FAMILY, opens = page)
    private fun value(id: MainMenuItemId, res: Int, page: MainMenuPage) =
        MainMenuItem(id, res, MainMenuKind.VALUE_CHOICE, opens = page)

    /** Modes de coloration proposés : Calque toujours ; Socapex / Ligne DMX s'ils ont des données. */
    fun colorModes(hasSoca: Boolean, hasDmx: Boolean): List<PlanColorMode> = buildList {
        add(PlanColorMode.LAYER)
        if (hasSoca) add(PlanColorMode.SOCAPEX)
        if (hasDmx) add(PlanColorMode.DMX_LINE)
    }

    /** Sections de [page], dans l'ordre exact de la spec. */
    fun sections(page: MainMenuPage, ctx: MainMenuContext): List<MainMenuSection> = when (page) {
        MainMenuPage.TOP -> top()
        MainMenuPage.DISPLAY -> display(ctx)
        MainMenuPage.HIDE_ISOLATE -> hideIsolate(ctx)
        MainMenuPage.PATCH_CABLING -> patchCabling()
        MainMenuPage.PLAN_SCENERY -> planScenery(ctx)
        MainMenuPage.SHARE_EXPORT -> shareExport(ctx)
        // Pages de valeurs : leur contenu (modes, préréglages de la vue) est fourni
        // par la vue ; aucune entrée d'action à ce niveau.
        MainMenuPage.COLOR_BY_CABLING, MainMenuPage.BACKGROUND_COLOR -> emptyList()
    }

    /** Toutes les entrées de [page], à plat (ordre d'affichage). */
    fun items(page: MainMenuPage, ctx: MainMenuContext): List<MainMenuItem> =
        sections(page, ctx).flatMap { it.items }

    /** Premier niveau : 10 lignes (deux rangées d'icônes + 8 lignes), identique dans les deux vues. */
    private fun top() = listOf(
        MainMenuSection(row = true, items = listOf(
            toggle(MainMenuItemId.MEASURE, R.string.menu_measure),
            toggle(MainMenuItemId.LABELS, R.string.opt_labels),
            action(MainMenuItemId.LAYERS, R.string.menu_layers)
        )),
        MainMenuSection(row = true, items = listOf(
            action(MainMenuItemId.PATCH, R.string.patch_title),
            action(MainMenuItemId.UNIVERSE, R.string.menu_universes),
            action(MainMenuItemId.CABLING, R.string.menu_cabling)
        )),
        MainMenuSection(divider = true, items = listOf(
            family(MainMenuItemId.DISPLAY, R.string.menu_display, MainMenuPage.DISPLAY),
            // Toujours, dans les deux vues (3D : l'isolement de la sélection, §8 D4).
            family(MainMenuItemId.HIDE_ISOLATE, R.string.menu_hide_isolate, MainMenuPage.HIDE_ISOLATE),
            family(MainMenuItemId.PATCH_CABLING, R.string.menu_patch_cabling, MainMenuPage.PATCH_CABLING),
            family(MainMenuItemId.PLAN_SCENERY, R.string.menu_plan_scenery, MainMenuPage.PLAN_SCENERY),
            action(MainMenuItemId.GPS_POSITION, R.string.menu_gps_position),
            family(MainMenuItemId.SHARE_EXPORT, R.string.menu_share_export, MainMenuPage.SHARE_EXPORT)
        )),
        MainMenuSection(divider = true, items = listOf(
            action(MainMenuItemId.ACCOUNT_SETTINGS, R.string.menu_account_settings),
            action(MainMenuItemId.CLOSE_PROJECT, R.string.menu_close_project)
        ))
    )

    private fun display(ctx: MainMenuContext): List<MainMenuSection> = buildList {
        val plan = ctx.surface == ProjectSurface.PLAN
        // Toujours, même étiquettes éteintes.
        add(MainMenuSection(items = listOf(action(MainMenuItemId.LABEL_SETTINGS, R.string.menu_label_settings))))
        add(MainMenuSection(titleRes = R.string.menu_section_colours, items = buildList {
            add(toggle(MainMenuItemId.LAYER_COLORS, R.string.opt_layer_colors))
            if (plan && ctx.colorModes.size > 1) {
                add(value(MainMenuItemId.COLOR_BY_CABLING, R.string.menu_colour_by_cabling, MainMenuPage.COLOR_BY_CABLING))
            }
            add(value(MainMenuItemId.BACKGROUND_COLOR, R.string.menu_section_background, MainMenuPage.BACKGROUND_COLOR))
        }))
        // Réglages propres au plan Android (§8 D7).
        if (plan) add(MainMenuSection(items = listOf(
            toggle(MainMenuItemId.STRUCTURE, R.string.opt_structure),
            toggle(MainMenuItemId.LEGEND, R.string.opt_legend)
        )))
        add(MainMenuSection(items = listOf(action(MainMenuItemId.TOOLBAR_THIS_VIEW, R.string.menu_toolbar_this_view))))
    }

    private fun hideIsolate(ctx: MainMenuContext): List<MainMenuSection> = listOf(MainMenuSection(items = buildList {
        val plan = ctx.surface == ProjectSurface.PLAN
        if (plan) add(toggle(MainMenuItemId.HIDE_ITEMS, R.string.tool_mask))
        // Plan : bascule du mode solo. 3D : isole la sélection / retire l'isolement,
        // grisée sans sélection ni solo (absence de donnée, §8 D23).
        add(toggle(MainMenuItemId.ISOLATE, R.string.tool_solo,
            enabled = plan || ctx.hasSelection || ctx.soloActive))
        if (plan && ctx.hasHiddenItems) add(action(MainMenuItemId.SHOW_ALL, R.string.tool_show_all))
        if (ctx.soloActive) add(action(MainMenuItemId.CLEAR_SOLO, R.string.tool_clear_solo))
    }))

    private fun patchCabling() = listOf(
        MainMenuSection(items = listOf(action(MainMenuItemId.FIXTURE_MODELS, R.string.menu_fixture_models))),
        MainMenuSection(titleRes = R.string.menu_section_cabling, items = listOf(
            action(MainMenuItemId.CABLING_ELECTRICAL, R.string.menu_socapex_pc16),
            action(MainMenuItemId.CABLING_DMX, R.string.menu_dmx_lines),
            action(MainMenuItemId.CABLING_POWERS, R.string.menu_power_per_type)
        ))
    )

    private fun planScenery(ctx: MainMenuContext): List<MainMenuSection> = buildList {
        add(MainMenuSection(titleRes = R.string.menu_section_imported_plan, items = buildList {
            add(action(MainMenuItemId.IMPORT_PLAN, R.string.tool_import_ref_plan))
            if (ctx.hasRefPlan) add(action(MainMenuItemId.ADJUST_PLAN, R.string.menu_adjust_imported_plan))
        }))
        if (ctx.surface == ProjectSurface.SCENE_3D) {
            add(MainMenuSection(titleRes = R.string.menu_section_scenery_3d, items = buildList {
                add(action(MainMenuItemId.IMPORT_MODEL, R.string.tool_import_model))
                if (ctx.importedModelCount > 0) {
                    add(action(MainMenuItemId.IMPORTED_MODELS, R.string.menu_imported_models_fmt,
                        arg = ctx.importedModelCount))
                }
            }))
        }
    }

    private fun shareExport(ctx: MainMenuContext): List<MainMenuSection> = buildList {
        if (ctx.hasSync) add(MainMenuSection(titleRes = R.string.menu_section_team, items = listOf(
            action(MainMenuItemId.SHARE_PROJECT, R.string.menu_share_project),
            action(MainMenuItemId.HISTORY, R.string.nav_history)
        )))
        add(MainMenuSection(titleRes = R.string.menu_section_export, items = listOf(
            action(MainMenuItemId.PLAN_PDF, R.string.menu_plan_pdf)
        )))
        if (ctx.hasCabling) add(MainMenuSection(titleRes = R.string.menu_section_cabling_pdf, items = listOf(
            action(MainMenuItemId.CABLING_PDF_FULL, R.string.cabling_export_full_menu),
            action(MainMenuItemId.CABLING_PDF_ELEC, R.string.cabling_export_elec_menu),
            action(MainMenuItemId.CABLING_PDF_DMX, R.string.cabling_export_dmx),
            action(MainMenuItemId.CABLING_PDF_PLANS, R.string.cabling_export_plans)
        )))
    }

    /** Destination ouverte par une entrée « projet » (null : pas une fenêtre). */
    fun destinationOf(id: MainMenuItemId): ProjectDestination? = when (id) {
        MainMenuItemId.LAYERS -> ProjectDestination.Layers(LayersTab.LAYERS)
        MainMenuItemId.PATCH -> ProjectDestination.PatchList
        MainMenuItemId.UNIVERSE -> ProjectDestination.Universe
        MainMenuItemId.CABLING -> ProjectDestination.Cabling(null)
        MainMenuItemId.CABLING_ELECTRICAL -> ProjectDestination.Cabling(CablingScreenTab.CABLING)
        MainMenuItemId.CABLING_DMX -> ProjectDestination.Cabling(CablingScreenTab.DMX)
        MainMenuItemId.CABLING_POWERS -> ProjectDestination.Cabling(CablingScreenTab.POWERS)
        MainMenuItemId.FIXTURE_MODELS -> ProjectDestination.FixtureModels
        MainMenuItemId.ACCOUNT_SETTINGS -> ProjectDestination.AccountSettings
        MainMenuItemId.SHARE_PROJECT -> ProjectDestination.ShareProject
        MainMenuItemId.HISTORY -> ProjectDestination.History
        else -> null
    }

    /** PDF de câblage demandé par une entrée (null : pas un PDF de câblage). */
    internal fun cablingPdfOf(id: MainMenuItemId): Set<CablingPdfPart>? = when (id) {
        MainMenuItemId.CABLING_PDF_FULL -> setOf(CablingPdfPart.ELEC, CablingPdfPart.DMX, CablingPdfPart.PLANS)
        MainMenuItemId.CABLING_PDF_ELEC -> setOf(CablingPdfPart.ELEC)
        MainMenuItemId.CABLING_PDF_DMX -> setOf(CablingPdfPart.DMX)
        MainMenuItemId.CABLING_PDF_PLANS -> setOf(CablingPdfPart.PLANS)
        else -> null
    }
}
