package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaque outil ancrable a une AUTRE place que sa barre (SPEC-menus-android §4.6,
 * iOS `ToolId.home`) : vider les barres ne retire rien. La table est vérifiée
 * contre le menu ⋯ (`MainMenuModel`), le panneau Position GPS
 * (`GeoPanelContent`), Compte & réglages, les boutons permanents, la pastille et
 * la fiche — pour chaque outil de chaque catalogue. Et aucun `rawId` (persisté,
 * commun avec iOS) n'est renommé.
 */
class ToolHomeTest {

    /** Instantané des identifiants PERSISTÉS : les renommer casserait les dispositions enregistrées. */
    @Test fun `instantane des rawId - aucun renomme`() {
        val expected = mapOf(
            ToolId.RECT to "rectSelect", ToolId.MEASURE to "measure", ToolId.SOLO to "solo",
            ToolId.CLEAR_SEL to "clearSelection", ToolId.GPS to "myLocation", ToolId.SATELLITE to "satellite",
            ToolId.LABELS to "labels", ToolId.LAYER_COLORS to "layerColors", ToolId.BACKGROUND to "backgroundColor",
            ToolId.SEARCH to "search", ToolId.GPS_MARKER_SIZE to "gpsMarkerSize", ToolId.CAM_PRESETS to "cameraPresets",
            ToolId.CAM_RESET to "resetCamera", ToolId.LABEL_SIZE to "labelSize", ToolId.LAYERS to "layers",
            ToolId.IMPORT_MODEL to "importModel", ToolId.PATCH to "patchList", ToolId.UNIVERSE to "universe",
            ToolId.CABLING to "cabling", ToolId.GDTF_SHARE to "gdtfShare", ToolId.PLAN_VIEW to "planView",
            ToolId.ACCOUNT to "account", ToolId.SHARE_PROJECT to "shareProject", ToolId.HISTORY to "history",
            ToolId.MASK to "mask", ToolId.SHOW_ALL to "showAll", ToolId.CLEAR_SOLO to "clearSolo",
            ToolId.CALIBRATE to "calibrate", ToolId.CALIBRATE_3 to "threePointCalibration",
            ToolId.EXPORT_PDF to "exportPDF", ToolId.DXF to "importDXF", ToolId.STRUCTURE to "structure",
            ToolId.LEGEND to "legend", ToolId.COLOR_MODE to "colorMode", ToolId.LABEL_SETTINGS to "labelSettings",
            ToolId.GEO_PANEL to "geoPanel", ToolId.MULTI_SELECT to "multiSelect",
            ToolId.REF_PLAN_PANEL to "refPlanPanel", ToolId.MODELS_PANEL to "modelsPanel",
            ToolId.GDTF_MAPPING to "gdtfMapping"
        )
        assertEquals("un ToolId sans instantané : l'ajouter ici", ToolId.entries.toSet(), expected.keys)
        for ((id, raw) in expected) {
            assertEquals(id.name, raw, id.rawId)
            assertEquals(id, ToolId.fromRawId(raw))
        }
        assertEquals(ToolId.entries.size, ToolId.entries.map { it.rawId }.toSet().size)
    }

    /** Contexte où TOUT ce qui peut apparaître apparaît (données présentes). */
    private fun richCtx(surface: ProjectSurface) = MainMenuContext(
        surface = surface, hasRefPlan = true, importedModelCount = 2, hasSync = true, hasCabling = true,
        colorModes = listOf(PlanColorMode.LAYER, PlanColorMode.SOCAPEX), hasHiddenItems = true,
        soloActive = true, hasSelection = true
    )

    private fun menuItems(surface: ProjectSurface): Set<MainMenuItemId> {
        val ctx = richCtx(surface)
        return MainMenuPage.entries.flatMap { MainMenuModel.items(it, ctx) }.map { it.id }.toSet()
    }

    private fun geoRows(surface: ProjectSurface): Set<GeoPanelRow> =
        GeoPanelContent.make(surface, calibrated = true, showSatellite = true, satelliteCovered = true,
            satelliteStatus = SatelliteStatus.Idle).let { (it.display + it.calibration + it.isolated).toSet() }

    private fun assertHomeExists(id: ToolId, surface: ProjectSurface) {
        val home = id.home
        assertNotNull("${id.rawId} (${surface.name}) : aucune autre place", home)
        val where = "${id.rawId} (${surface.name}) → $home"
        when (home!!) {
            is ToolHome.Menu -> assertTrue(where, (home as ToolHome.Menu).item in menuItems(surface))
            is ToolHome.GeoPanel -> {
                // Le panneau s'ouvre lui-même par le menu, dans les deux vues.
                assertTrue(where, MainMenuItemId.GPS_POSITION in menuItems(surface))
                assertTrue(where, (home as ToolHome.GeoPanel).row in geoRows(surface))
            }
            ToolHome.AccountServices -> {
                assertTrue(where, MainMenuItemId.ACCOUNT_SETTINGS in menuItems(surface))
                assertTrue(where, AccountSettingsSection.SERVICES in AccountSettingsSections.of(hasSync = false))
                assertTrue(where, AccountSettingsSection.SERVICES in AccountSettingsSections.of(hasSync = true))
            }
            ToolHome.PermanentButton -> {
                val none = ToolbarLayout.EMPTY
                val shown = when (id) {
                    ToolId.RECT -> SceneTopRowRules.showsRect(none)
                    ToolId.MULTI_SELECT -> SceneTopRowRules.showsMulti(none)
                    ToolId.CAM_RESET -> SceneTopRowRules.showsReframe(none)
                    else -> false
                }
                assertTrue(where, shown)
            }
            ToolHome.ViewPill -> assertTrue(where, ViewPillModel.sections(surface, CameraViewpoint.PERSPECTIVE)
                .flatMap { it.entries }.any { it.action == ViewPillAction.SHOW_PLAN })
            ToolHome.SelectionClose -> {
                // Une sélection a toujours sa fiche (✕ « Fermer ») ou sa bannière (✕ « Vider la sélection »).
                assertEquals(where, PlanSelectionBar.CARD, SelectionCardLayout.presentation(1))
                assertEquals(where, PlanSelectionBar.BANNER, SelectionCardLayout.presentation(3))
            }
        }
    }

    @Test fun `chaque outil du catalogue 3D a une autre place dans la 3D`() {
        ToolCatalog.SCENE_3D.forEach { assertHomeExists(it, ProjectSurface.SCENE_3D) }
    }

    @Test fun `chaque outil du catalogue du plan a une autre place dans le plan`() {
        ToolCatalog.PLAN.forEach { assertHomeExists(it, ProjectSurface.PLAN) }
    }

    @Test fun `places propres a Android (table du plan, §4-6)`() {
        assertEquals(ToolHome.SelectionClose, ToolId.CLEAR_SEL.home)
        assertEquals(ToolHome.GeoPanel(GeoPanelRow.MARKER_SIZE), ToolId.GPS_MARKER_SIZE.home)
        assertEquals(ToolHome.GeoPanel(GeoPanelRow.THREE_POINTS), ToolId.CALIBRATE_3.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.STRUCTURE), ToolId.STRUCTURE.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.LEGEND), ToolId.LEGEND.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.LABEL_SETTINGS), ToolId.LABEL_SIZE.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.ISOLATE), ToolId.SOLO.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.SHOW_ALL), ToolId.SHOW_ALL.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.CLEAR_SOLO), ToolId.CLEAR_SOLO.home)
        assertEquals(ToolHome.AccountServices, ToolId.GDTF_SHARE.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.FIXTURE_MODELS), ToolId.GDTF_MAPPING.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.ADJUST_PLAN), ToolId.REF_PLAN_PANEL.home)
        assertEquals(ToolHome.Menu(MainMenuItemId.IMPORTED_MODELS), ToolId.MODELS_PANEL.home)
        assertEquals(ToolHome.ViewPill, ToolId.PLAN_VIEW.home)
    }

    @Test fun `identifiants inertes - hors table, hors catalogues`() {
        for (id in listOf(ToolId.COLOR_MODE, ToolId.SEARCH, ToolId.CAM_PRESETS)) {
            assertNull(id.home)
            assertTrue(id !in ToolCatalog.SCENE_3D && id !in ToolCatalog.PLAN)
        }
        // Tous les autres ont une place.
        ToolId.entries.filter { it !in setOf(ToolId.COLOR_MODE, ToolId.SEARCH, ToolId.CAM_PRESETS) }
            .forEach { assertNotNull(it.rawId, it.home) }
    }
}
