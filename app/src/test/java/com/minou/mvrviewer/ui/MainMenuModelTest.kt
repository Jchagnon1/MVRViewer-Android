package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Menu ⋯ UNIQUE (SPEC-menus-android §3.2, tranche C) : ordre exact, apparitions
 * selon la vue et les données (jamais selon l'achat), profondeur, routage des
 * entrées « projet », textes fr / en réels.
 */
class MainMenuModelTest {

    private val ctx3D = MainMenuContext(surface = ProjectSurface.SCENE_3D)
    private val ctxPlan = MainMenuContext(surface = ProjectSurface.PLAN)

    private fun ids(page: MainMenuPage, ctx: MainMenuContext) = MainMenuModel.items(page, ctx).map { it.id }
    private fun fr(items: List<MainMenuItem>) = items.map { i ->
        i.labelArg?.let { TestRes.FR.res(i.labelRes, listOf(it)) } ?: TestRes.FR.res(i.labelRes, emptyList())
    }

    // ------------------------------------------------------------ premier niveau

    @Test fun premierNiveauDixLignesIdentiqueDansLesDeuxVues() {
        val expected = listOf(
            MainMenuItemId.MEASURE, MainMenuItemId.LABELS, MainMenuItemId.LAYERS,
            MainMenuItemId.PATCH, MainMenuItemId.UNIVERSE, MainMenuItemId.CABLING,
            MainMenuItemId.DISPLAY, MainMenuItemId.HIDE_ISOLATE, MainMenuItemId.PATCH_CABLING,
            MainMenuItemId.PLAN_SCENERY, MainMenuItemId.GPS_POSITION, MainMenuItemId.SHARE_EXPORT,
            MainMenuItemId.ACCOUNT_SETTINGS, MainMenuItemId.CLOSE_PROJECT
        )
        assertEquals(expected, ids(MainMenuPage.TOP, ctx3D))
        assertEquals(expected, ids(MainMenuPage.TOP, ctxPlan))
        // 10 lignes : deux rangées de trois cases + 8 lignes.
        val sections = MainMenuModel.sections(MainMenuPage.TOP, ctx3D)
        assertEquals(listOf(true, true, false, false), sections.map { it.row })
        assertEquals(10, sections.sumOf { if (it.row) 1 else it.items.size })
        assertEquals(listOf(3, 3), sections.filter { it.row }.map { it.items.size })
        // Séparateurs : avant « Affichage ▸ » et avant « Compte & réglages… ».
        assertEquals(listOf(false, false, true, true), sections.map { it.divider })
        assertEquals(listOf("Mesurer", "Étiquettes", "Calques", "Patch", "Univers", "Câblage",
            "Affichage", "Masquer & isoler", "Patch & câblage", "Plan & décor", "Position GPS…",
            "Partager & exporter", "Compte & réglages…", "Fermer le projet"),
            fr(MainMenuModel.items(MainMenuPage.TOP, ctx3D)))
        // Types : bascules (rangée 1), familles, actions.
        val kinds = MainMenuModel.items(MainMenuPage.TOP, ctxPlan).associate { it.id to it.kind }
        assertEquals(MainMenuKind.TOGGLE, kinds[MainMenuItemId.MEASURE])
        assertEquals(MainMenuKind.TOGGLE, kinds[MainMenuItemId.LABELS])
        assertEquals(MainMenuKind.FAMILY, kinds[MainMenuItemId.DISPLAY])
        assertEquals(MainMenuKind.FAMILY, kinds[MainMenuItemId.SHARE_EXPORT])
        assertEquals(MainMenuKind.ACTION, kinds[MainMenuItemId.GPS_POSITION])
        assertEquals(MainMenuKind.ACTION, kinds[MainMenuItemId.CLOSE_PROJECT])
    }

    @Test fun jamaisSelonLAchat() {
        // Le contexte n'a AUCUN champ Pro : un gratuit et un Pro voient les mêmes entrées.
        val fields = MainMenuContext::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.toString(), fields.any { "pro" in it })
        // Aucune entrée n'est grisée au premier niveau (absence de donnée seulement).
        assertTrue(MainMenuModel.items(MainMenuPage.TOP, ctx3D).all { it.enabled })
    }

    // ------------------------------------------------------------- apparitions

    @Test fun affichageSelonLaVue() {
        assertEquals(listOf(MainMenuItemId.LABEL_SETTINGS, MainMenuItemId.LAYER_COLORS,
            MainMenuItemId.BACKGROUND_COLOR, MainMenuItemId.TOOLBAR_THIS_VIEW),
            ids(MainMenuPage.DISPLAY, ctx3D))
        // Plan sans câblage : un seul mode de coloration → pas de « Colorer par câblage ».
        assertEquals(listOf(MainMenuItemId.LABEL_SETTINGS, MainMenuItemId.LAYER_COLORS,
            MainMenuItemId.BACKGROUND_COLOR, MainMenuItemId.STRUCTURE, MainMenuItemId.LEGEND,
            MainMenuItemId.TOOLBAR_THIS_VIEW), ids(MainMenuPage.DISPLAY, ctxPlan))
        val cabled = ctxPlan.copy(colorModes = MainMenuModel.colorModes(hasSoca = true, hasDmx = false))
        assertEquals(listOf(MainMenuItemId.LABEL_SETTINGS, MainMenuItemId.LAYER_COLORS,
            MainMenuItemId.COLOR_BY_CABLING, MainMenuItemId.BACKGROUND_COLOR, MainMenuItemId.STRUCTURE,
            MainMenuItemId.LEGEND, MainMenuItemId.TOOLBAR_THIS_VIEW), ids(MainMenuPage.DISPLAY, cabled))
        // Jamais en 3D, même avec des modes.
        assertFalse(MainMenuItemId.COLOR_BY_CABLING in ids(MainMenuPage.DISPLAY,
            ctx3D.copy(colorModes = MainMenuModel.colorModes(true, true))))
        // « Réglage des étiquettes… » toujours, en tête.
        for (c in listOf(ctx3D, ctxPlan, cabled)) assertEquals(MainMenuItemId.LABEL_SETTINGS, ids(MainMenuPage.DISPLAY, c).first())
        // Section « Couleurs ».
        val colours = MainMenuModel.sections(MainMenuPage.DISPLAY, ctxPlan)[1]
        assertEquals("Couleurs", TestRes.FR.res(colours.titleRes!!, emptyList()))
        assertEquals("Colours", TestRes.EN.res(colours.titleRes!!, emptyList()))
    }

    @Test fun modesDeColoration() {
        assertEquals(listOf(PlanColorMode.LAYER), MainMenuModel.colorModes(false, false))
        assertEquals(listOf(PlanColorMode.LAYER, PlanColorMode.DMX_LINE), MainMenuModel.colorModes(false, true))
        assertEquals(listOf(PlanColorMode.LAYER, PlanColorMode.SOCAPEX, PlanColorMode.DMX_LINE),
            MainMenuModel.colorModes(true, true))
    }

    @Test fun masquerEtIsoler() {
        // Plan : masquer, isoler ; « Tout réafficher » s'il y a des masqués ; « Vider l'isolement » si solo.
        assertEquals(listOf(MainMenuItemId.HIDE_ITEMS, MainMenuItemId.ISOLATE), ids(MainMenuPage.HIDE_ISOLATE, ctxPlan))
        assertEquals(listOf(MainMenuItemId.HIDE_ITEMS, MainMenuItemId.ISOLATE, MainMenuItemId.SHOW_ALL,
            MainMenuItemId.CLEAR_SOLO),
            ids(MainMenuPage.HIDE_ISOLATE, ctxPlan.copy(hasHiddenItems = true, soloActive = true)))
        // 3D : seulement « Isoler (solo) » (grisé sans sélection ni solo, §8 D23) et « Vider l'isolement ».
        val bare = MainMenuModel.items(MainMenuPage.HIDE_ISOLATE, ctx3D.copy(hasHiddenItems = true))
        assertEquals(listOf(MainMenuItemId.ISOLATE), bare.map { it.id })
        assertFalse(bare.single().enabled)
        assertTrue(MainMenuModel.items(MainMenuPage.HIDE_ISOLATE, ctx3D.copy(hasSelection = true)).single().enabled)
        val solo = MainMenuModel.items(MainMenuPage.HIDE_ISOLATE, ctx3D.copy(soloActive = true))
        assertEquals(listOf(MainMenuItemId.ISOLATE, MainMenuItemId.CLEAR_SOLO), solo.map { it.id })
        assertTrue(solo.all { it.enabled })
        assertEquals(listOf("Masquer des éléments", "Isoler (solo)", "Tout réafficher", "Vider l'isolement"),
            fr(MainMenuModel.items(MainMenuPage.HIDE_ISOLATE, ctxPlan.copy(hasHiddenItems = true, soloActive = true))))
    }

    @Test fun patchEtCablage() {
        assertEquals(listOf(MainMenuItemId.FIXTURE_MODELS, MainMenuItemId.CABLING_ELECTRICAL,
            MainMenuItemId.CABLING_DMX, MainMenuItemId.CABLING_POWERS), ids(MainMenuPage.PATCH_CABLING, ctx3D))
        assertEquals(ids(MainMenuPage.PATCH_CABLING, ctx3D), ids(MainMenuPage.PATCH_CABLING, ctxPlan))
        assertEquals(listOf("Modèles de projecteurs…", "Socapex & lignes PC16", "Lignes & multipaires DMX",
            "Puissances par type"), fr(MainMenuModel.items(MainMenuPage.PATCH_CABLING, ctx3D)))
    }

    @Test fun planEtDecor() {
        // Sans plan : pas de « Régler le plan importé… » ; sans modèle : pas de « Modèles importés ».
        assertEquals(listOf(MainMenuItemId.IMPORT_PLAN, MainMenuItemId.IMPORT_MODEL), ids(MainMenuPage.PLAN_SCENERY, ctx3D))
        assertEquals(listOf(MainMenuItemId.IMPORT_PLAN, MainMenuItemId.ADJUST_PLAN, MainMenuItemId.IMPORT_MODEL,
            MainMenuItemId.IMPORTED_MODELS),
            ids(MainMenuPage.PLAN_SCENERY, ctx3D.copy(hasRefPlan = true, importedModelCount = 2)))
        // Décor 3D en 3D seulement.
        assertEquals(listOf(MainMenuItemId.IMPORT_PLAN, MainMenuItemId.ADJUST_PLAN),
            ids(MainMenuPage.PLAN_SCENERY, ctxPlan.copy(hasRefPlan = true, importedModelCount = 2)))
        val items = MainMenuModel.items(MainMenuPage.PLAN_SCENERY, ctx3D.copy(hasRefPlan = true, importedModelCount = 2))
        assertEquals("Modèles importés (2)…", fr(items).last())
        assertEquals("Imported models (2)…", TestRes.EN.res(items.last().labelRes, listOf(2)))
    }

    @Test fun partagerEtExporter() {
        // Sans synchro : pas de section Équipe ; sans câblage : pas de PDF de câblage.
        assertEquals(listOf(MainMenuItemId.PLAN_PDF), ids(MainMenuPage.SHARE_EXPORT, ctx3D.copy(hasSync = false)))
        assertEquals(listOf(MainMenuItemId.SHARE_PROJECT, MainMenuItemId.HISTORY, MainMenuItemId.PLAN_PDF),
            ids(MainMenuPage.SHARE_EXPORT, ctx3D))
        val all = ctxPlan.copy(hasCabling = true)
        assertEquals(listOf(MainMenuItemId.SHARE_PROJECT, MainMenuItemId.HISTORY, MainMenuItemId.PLAN_PDF,
            MainMenuItemId.CABLING_PDF_FULL, MainMenuItemId.CABLING_PDF_ELEC, MainMenuItemId.CABLING_PDF_DMX,
            MainMenuItemId.CABLING_PDF_PLANS), ids(MainMenuPage.SHARE_EXPORT, all))
        assertEquals(listOf("Partager ce projet…", "Historique des modifications", "PDF du plan (composer les vues)…",
            "Dossier câblage complet", "Distribution élec", "Câblage DMX", "Plans repérés"),
            fr(MainMenuModel.items(MainMenuPage.SHARE_EXPORT, all)))
        assertEquals(listOf("Team", "Export", "Cabling PDF"),
            MainMenuModel.sections(MainMenuPage.SHARE_EXPORT, all).map { TestRes.EN.res(it.titleRes!!, emptyList()) })
    }

    // -------------------------------------------------------------- profondeur

    @Test fun profondeurMenuFamilleValeur() {
        assertEquals(0, MainMenuPage.TOP.depth)
        for (p in listOf(MainMenuPage.DISPLAY, MainMenuPage.HIDE_ISOLATE, MainMenuPage.PATCH_CABLING,
                MainMenuPage.PLAN_SCENERY, MainMenuPage.SHARE_EXPORT)) {
            assertEquals(1, p.depth); assertEquals(MainMenuPage.TOP, p.parent)
        }
        assertEquals(2, MainMenuPage.COLOR_BY_CABLING.depth)
        assertEquals(MainMenuPage.DISPLAY, MainMenuPage.BACKGROUND_COLOR.parent)
        // Aucune ACTION au-delà de la page de famille : seules des listes de valeurs
        // vont au 3e niveau ; une famille n'ouvre jamais une autre famille.
        val rich = MainMenuContext(ProjectSurface.PLAN, hasRefPlan = true, importedModelCount = 1, hasCabling = true,
            colorModes = MainMenuModel.colorModes(true, true), hasHiddenItems = true, soloActive = true, hasSelection = true)
        for (c in listOf(rich, rich.copy(surface = ProjectSurface.SCENE_3D))) {
            for (p in MainMenuPage.entries) for (i in MainMenuModel.items(p, c)) {
                val opened = i.opens ?: continue
                assertEquals("$p › ${i.id}", p.depth + 1, opened.depth)
                if (p.depth == 1) assertEquals(MainMenuKind.VALUE_CHOICE, i.kind)
            }
            assertTrue(MainMenuModel.items(MainMenuPage.COLOR_BY_CABLING, c).isEmpty())
            assertTrue(MainMenuModel.items(MainMenuPage.BACKGROUND_COLOR, c).isEmpty())
        }
        // Titres des pages (ligne « ‹ … »).
        assertEquals("Affichage", TestRes.FR.res(MainMenuPage.DISPLAY.titleRes!!, emptyList()))
        assertEquals("Background colour", TestRes.EN.res(MainMenuPage.BACKGROUND_COLOR.titleRes!!, emptyList()))
        assertNull(MainMenuPage.TOP.titleRes)
    }

    // ------------------------------------------------------------------ routage

    @Test fun entreesProjetVersLaScene() {
        assertEquals(ProjectDestination.PatchList, MainMenuModel.destinationOf(MainMenuItemId.PATCH))
        assertEquals(ProjectDestination.Universe, MainMenuModel.destinationOf(MainMenuItemId.UNIVERSE))
        // Câblage du premier niveau : dernier onglet ; les 3 entrées de famille : leur onglet.
        assertEquals(ProjectDestination.Cabling(null), MainMenuModel.destinationOf(MainMenuItemId.CABLING))
        assertEquals(ProjectDestination.Cabling(CablingScreenTab.CABLING), MainMenuModel.destinationOf(MainMenuItemId.CABLING_ELECTRICAL))
        assertEquals(ProjectDestination.Cabling(CablingScreenTab.DMX), MainMenuModel.destinationOf(MainMenuItemId.CABLING_DMX))
        assertEquals(ProjectDestination.Cabling(CablingScreenTab.POWERS), MainMenuModel.destinationOf(MainMenuItemId.CABLING_POWERS))
        assertEquals(ProjectDestination.Layers(LayersTab.LAYERS), MainMenuModel.destinationOf(MainMenuItemId.LAYERS))
        assertEquals(ProjectDestination.FixtureModels, MainMenuModel.destinationOf(MainMenuItemId.FIXTURE_MODELS))
        assertEquals(ProjectDestination.AccountSettings, MainMenuModel.destinationOf(MainMenuItemId.ACCOUNT_SETTINGS))
        assertEquals(ProjectDestination.ShareProject, MainMenuModel.destinationOf(MainMenuItemId.SHARE_PROJECT))
        assertEquals(ProjectDestination.History, MainMenuModel.destinationOf(MainMenuItemId.HISTORY))
        assertNull(MainMenuModel.destinationOf(MainMenuItemId.MEASURE))
        assertEquals(setOf(CablingPdfPart.ELEC, CablingPdfPart.DMX, CablingPdfPart.PLANS),
            MainMenuModel.cablingPdfOf(MainMenuItemId.CABLING_PDF_FULL))
        assertEquals(setOf(CablingPdfPart.PLANS), MainMenuModel.cablingPdfOf(MainMenuItemId.CABLING_PDF_PLANS))
        assertNull(MainMenuModel.cablingPdfOf(MainMenuItemId.PLAN_PDF))
        // Chaque entrée « projet » a un traitement dans la scène.
        for (id in MainMenuItemId.entries.filter { it.isProjectLevel }) {
            assertTrue("$id", MainMenuModel.destinationOf(id) != null || MainMenuModel.cablingPdfOf(id) != null ||
                id == MainMenuItemId.IMPORT_PLAN || id == MainMenuItemId.CLOSE_PROJECT)
        }
    }

    private fun src(name: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/ui/$name"), File("app/src/main/java/com/minou/mvrviewer/ui/$name"))
            .first { it.isFile }.readText()

    @Test fun branchementDesDeuxVuesEtDeLaScene() {
        // Un seul menu, posé dans les deux vues ; le reste part à la scène.
        val v3 = src("Scene3DScreen.kt"); val plan = src("PlanScreen.kt"); val scene = src("SceneScreen.kt")
        for (v in listOf(v3, plan)) {
            assertTrue(v.contains("MainMenuButton("))
            assertTrue(v.contains("else -> onMainMenu(id)"))
            assertTrue(v.contains("MainMenuItemId.GPS_POSITION -> onPanels(panels.open(ScenePanel.GEO))"))
            assertTrue(v.contains("MainMenuItemId.TOOLBAR_THIS_VIEW -> showCustomize = true"))
        }
        assertTrue(plan.contains("MainMenuItemId.PLAN_PDF -> exportMode = true"))
        assertTrue(scene.contains("MainMenuModel.destinationOf(id)?.let { open(it); return }"))
        assertTrue(scene.contains("MainMenuModel.cablingPdfOf(id)?.let { exportCablingPdf(it); return }"))
        assertTrue(scene.contains("MainMenuItemId.IMPORT_PLAN -> importRefPlan()"))
        assertTrue(scene.contains("MainMenuItemId.PLAN_PDF -> openPlanWith(PlanStartAction.EXPORT_PDF)"))
        assertTrue(scene.contains("MainMenuItemId.CLOSE_PROJECT -> onClose()"))
        // Rejoindre un projet et la Bibliothèque MA ont quitté le ⋯ (accueil ; Modèles de projecteurs).
        assertFalse(scene.contains("JoinProjectDialog("))
        assertFalse(v3.contains("onShowMaLibrary") || plan.contains("onShowMaLibrary"))
        // Les PDF de câblage du ⋯ et de l'écran Câblage : même moteur.
        assertTrue(src("CablingScreen.kt").contains("generateCablingPdf("))
        assertTrue(scene.contains("generateCablingPdf("))
    }

    // ------------------------------------------------------------------- textes

    @Test fun textesFrEn() {
        val expected = mapOf(
            "menu_more_options" to ("Plus d'options" to "More options"),
            "menu_measure" to ("Mesurer" to "Measure"),
            "menu_layers" to ("Calques" to "Layers"),
            "menu_universes" to ("Univers" to "Universes"),
            "menu_cabling" to ("Câblage" to "Cabling"),
            "menu_hide_isolate" to ("Masquer & isoler" to "Hide & isolate"),
            "menu_patch_cabling" to ("Patch & câblage" to "Patch & cabling"),
            "menu_plan_scenery" to ("Plan & décor" to "Plan & scenery"),
            "menu_share_export" to ("Partager & exporter" to "Share & export"),
            "menu_section_colours" to ("Couleurs" to "Colours"),
            "menu_colour_by_cabling" to ("Colorer par câblage" to "Colour by cabling"),
            "menu_toolbar_this_view" to ("Barre d'outils de cette vue…" to "Toolbar for this view…"),
            "menu_fixture_models" to ("Modèles de projecteurs…" to "Fixture models…"),
            "menu_section_cabling" to ("Câblage" to "Cabling"),
            "menu_socapex_pc16" to ("Socapex & lignes PC16" to "Socapex & PC16 lines"),
            "menu_dmx_lines" to ("Lignes & multipaires DMX" to "DMX lines & multicores"),
            "menu_power_per_type" to ("Puissances par type" to "Power per type"),
            "menu_section_imported_plan" to ("Plan importé" to "Imported plan"),
            "menu_section_scenery_3d" to ("Décor 3D" to "3D scenery"),
            "menu_section_team" to ("Équipe" to "Team"),
            "menu_share_project" to ("Partager ce projet…" to "Share this project…"),
            "menu_section_export" to ("Exporter" to "Export"),
            "menu_plan_pdf" to ("PDF du plan (composer les vues)…" to "Plan PDF (compose the views)…"),
            "menu_section_cabling_pdf" to ("PDF de câblage" to "Cabling PDF"),
            "menu_account_settings" to ("Compte & réglages…" to "Account & settings…"),
            "menu_close_project" to ("Fermer le projet" to "Close the project"),
            "cabling_pdf_generating" to ("Génération du PDF…" to "Generating the PDF…"),
            // Textes modifiés (§7.2) repris par le menu.
            "opt_layer_colors" to ("Couleurs par calque" to "Colours per layer"),
            "cabling_export_full_menu" to ("Dossier câblage complet" to "Full cabling pack"),
            "cabling_export_elec_menu" to ("Distribution élec" to "Power distro"),
            "tool_solo" to ("Isoler (solo)" to "Isolate (solo)"),
            "tool_clear_solo" to ("Vider l'isolement" to "Clear the solo"),
            "tool_mask" to ("Masquer des éléments" to "Hide items")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, TestRes.FR.get(k))
            assertEquals("en $k", v.second, TestRes.EN.get(k))
        }
        assertEquals("Modèles importés (3)…", TestRes.FR.get("menu_imported_models_fmt", 3))
    }

    @Test fun clesRetireesAbsentes() {
        val removed = listOf("menu_options", "menu_section_tools", "menu_section_sync", "nav_layers", "tool_classes",
            "nav_customize_toolbar", "nav_gdtf_share_models", "cam_viewpoints", "cam_side", "cam_iso",
            "cam_reset_view", "opt_coloring_fmt", "plan_title_fmt", "nav_projects", "status_objects_3d_fmt",
            "status_objects_3d_loading_fmt", "status_objects_gdtf_fmt", "status_gdtf_suffix_fmt")
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val code = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (k in removed) {
            assertFalse("fr $k", TestRes.FR.strings.containsKey(k))
            assertFalse("en $k", TestRes.EN.strings.containsKey(k))
            assertFalse("usage de R.string.$k", Regex("""R\.string\.$k\b""").containsMatchIn(code))
        }
    }
}
