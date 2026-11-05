package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Textes du rangement des menus (SPEC-menus-android §7), lus dans les VRAIS
 * `strings.xml` fr et en : clés présentes dans les deux langues, valeurs de la
 * tranche E (titres d'outils, panneau de personnalisation), pluriels one / other,
 * plus aucun « (s) » fabriqué, plus de « Fixture » en français, clés supprimées
 * absentes.
 */
class LocalizationTest {
    private val fr = TestRes.FR
    private val en = TestRes.EN

    /** Clés du §7.1 (toutes tranches) + nouvelles clés de la tranche E. */
    private val newStrings = listOf(
        "menu_more_options", "menu_measure", "menu_layers", "menu_universes", "menu_cabling", "menu_display",
        "menu_hide_isolate", "menu_patch_cabling", "menu_plan_scenery", "menu_gps_position", "menu_share_export",
        "menu_account_settings", "menu_close_project", "menu_label_settings", "menu_section_colours",
        "menu_colour_by_cabling", "menu_toolbar_this_view", "menu_fixture_models", "menu_section_cabling",
        "menu_socapex_pc16", "menu_dmx_lines", "menu_power_per_type", "menu_section_imported_plan",
        "menu_adjust_imported_plan", "menu_section_scenery_3d", "menu_imported_models_fmt", "menu_section_team",
        "menu_share_project", "menu_section_export", "menu_plan_pdf", "menu_section_cabling_pdf", "pill_view",
        "pill_3d_fmt", "pill_plan_fmt", "pill_section_viewpoint", "view_perspective", "view_back", "view_left",
        "view_right", "pill_plan_view_2d", "pill_section_projection", "perm_multi_select", "search_fixture_cd",
        "search_none_fmt", "reframe_cd", "status_gdtf_loading", "geo_panel_title", "geo_show_my_position",
        "geo_calibrate_first", "geo_opacity", "geo_dot_size", "geo_section_calibration", "geo_here_quick",
        "geo_here_caption", "geo_reset_calibration", "refplan_panel_title", "refplan_show", "refplan_remove",
        "refplan_reading", "labels_section_appearance", "labels_size", "labels_offset", "labels_section_placement",
        "labels_placement_help", "labels_turn_off_fmt", "labels_separate_blocks", "account_settings_title",
        "account_section_account", "account_section_services", "account_signed_in", "account_not_signed_in",
        "pro_status_licence", "pro_pitch", "pro_subscribe_to_pro", "pro_restore", "home_account_prompt_title",
        "home_account_prompt_body", "home_account_prompt_action", "models_title", "models_gdtf_signed_in",
        "models_gdtf_sign_in", "layers_title", "card_kind_fixture", "card_title_fmt", "card_layer_fmt",
        "card_fixture_number_fmt", "card_dmx_fmt", "card_details", "card_power_to_enter", "banner_clear_selection",
        "universe_edit", "universe_edit_cd_fmt", "mode_hide_tap", "mode_hide_box", "mode_isolate_tap",
        "mode_isolate_box", "toolbar_all_off", "cabling_pdf_generating", "tool_ref_plan_panel", "tool_models_panel",
        // Tranche E : libellés du menu (avec « … ») séparés des titres d'outils (sans).
        "menu_import_plan", "menu_import_model", "fx_fixture_number"
    )

    @Test fun `cles du plan presentes en fr et en`() {
        for (k in newStrings) {
            assertNotNull("fr $k", fr.strings[k])
            assertNotNull("en $k", en.strings[k])
        }
        for (k in listOf("status_objects", "status_hidden_classes", "sel_fixtures_selected")) {
            assertNotNull("fr $k", fr.plurals[k]); assertNotNull("en $k", en.plurals[k])
        }
    }

    /** §7.2 : valeurs exactes de la tranche E (titres d'outils iOS, personnalisation). */
    @Test fun `titres d'outils et personnalisation - valeurs exactes fr et en`() {
        val expected = mapOf(
            "tool_rect_select" to ("Sélection par cadre" to "Rectangle selection"),
            "tool_label_size" to ("Réglage des étiquettes" to "Label settings"),
            "tool_recenter_camera" to ("Recadrer" to "Reframe"),
            "tool_calibrate" to ("Je suis ici (pose rapide)" to "I am here (quick)"),
            "nav_view_plan" to ("Vue plan 2D" to "2D plan view"),
            "tool_export_pdf" to ("PDF du plan" to "Plan PDF"),
            "tool_gps" to ("Afficher ma position" to "Show my position"),
            "tool_solo" to ("Isoler (solo)" to "Isolate (solo)"),
            "tool_clear_solo" to ("Vider l'isolement" to "Clear the solo"),
            "tool_clear_selection" to ("Vider la sélection" to "Clear selection"),
            "tool_marker_size" to ("Taille du point GPS" to "GPS dot size"),
            "tool_import_model" to ("Importer un modèle 3D" to "Import a 3D model"),
            "tool_import_ref_plan" to ("Importer un plan (DXF, image, PDF)" to "Import a plan (DXF, image, PDF)"),
            "menu_import_model" to ("Importer un modèle 3D…" to "Import a 3D model…"),
            "menu_import_plan" to ("Importer un plan (DXF, image, PDF)…" to "Import a plan (DXF, image, PDF)…"),
            "tool_ref_plan_panel" to ("Régler le plan importé" to "Adjust the imported plan"),
            "tool_models_panel" to ("Modèles importés" to "Imported models"),
            "menu_cabling" to ("Câblage" to "Cabling"),
            "models_title" to ("Modèles de projecteurs" to "Fixture models"),
            "edge_none" to ("Hors barres" to "Off the bars"),
            "toolbar_all_off" to ("Tout remettre hors barres" to "Put everything back off the bars"),
            "toolbar_customize_help" to (
                "Placez un outil sur un bord de l'écran pour un accès rapide, ou laissez-le « Hors barres » : " +
                    "il reste accessible par son bouton, le menu ⋯ ou un panneau. Les flèches réordonnent les " +
                    "outils d'une même barre." to
                "Put a tool on an edge of the screen for quick access, or leave it “Off the bars”: it stays " +
                    "available from its button, the ⋯ menu or a panel. The arrows reorder the tools within a bar."),
            "fx_fixture_number" to ("N° de projecteur" to "Fixture number"),
            "common_done" to ("Terminé" to "Done")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, fr.get(k))
            assertEquals("en $k", v.second, en.get(k))
        }
        // Titre du panneau Modèles importés (même clé que l'ancien libellé de l'outil).
        assertEquals("Modèles importés (3)", fr.get("tool_models_fmt", 3))
        assertEquals("Imported models (3)", en.get("tool_models_fmt", 3))
    }

    @Test fun `pluriels one et other - export PDF, selection, canaux`() {
        assertEquals("1 vue", fr.plural("plan_export_views", 1, 1))
        assertEquals("2 vues", fr.plural("plan_export_views", 2, 2))
        assertEquals("1 view", en.plural("plan_export_views", 1, 1))
        assertEquals("2 views", en.plural("plan_export_views", 2, 2))
        assertEquals("PDF prêt · 1 page", fr.plural("plan_export_ready", 1, 1))
        assertEquals("PDF prêt · 3 pages", fr.plural("plan_export_ready", 3, 3))
        assertEquals("PDF ready · 3 pages", en.plural("plan_export_ready", 3, 3))
        assertEquals("1 projecteur sélectionné", fr.plural("sel_fixtures_selected", 1, 1))
        assertEquals("2 projecteurs sélectionnés", fr.plural("sel_fixtures_selected", 2, 2))
        assertEquals("1 fixture selected", en.plural("sel_fixtures_selected", 1, 1))
        assertEquals("1 canal", fr.plural("fx_channels", 1, 1))
        assertEquals("12 canaux", fr.plural("fx_channels", 12, 12))
        assertEquals("1 channel", en.plural("fx_channels", 1, 1))
        assertEquals("Spot  ·  1 canal", fr.plural("fx_type_channels", 1, "Spot", 1))
        assertEquals("Spot  ·  16 channels", en.plural("fx_type_channels", 16, "Spot", 16))
        assertEquals("3 / 4 projecteurs câblés", fr.plural("cabling_assigned", 4, 3, 4))
        assertEquals("0 / 1 fixture cabled", en.plural("cabling_assigned", 1, 0, 1))
        assertEquals("1 projecteur à conso inconnue (compté 0 W)", fr.plural("pdf_unknown_watts", 1, 1))
        assertEquals("+2 autres calques", fr.plural("legend_more_layers", 2, 2))
    }

    /** Tous les pluriels ont one ET other, dans les deux langues, et les mêmes clés des deux côtés. */
    @Test fun `pluriels complets et identiques en fr et en`() {
        assertEquals(fr.plurals.keys, en.plurals.keys)
        for ((k, m) in fr.plurals) assertTrue("fr $k", "one" in m && "other" in m)
        for ((k, m) in en.plurals) assertTrue("en $k", "one" in m && "other" in m)
    }

    @Test fun `plus aucun pluriel fabrique (s) ni (aux)`() {
        for (t in listOf(fr, en)) {
            val all = t.strings.entries.map { it.key to it.value } +
                t.plurals.flatMap { (k, m) -> m.values.map { k to it } }
            for ((k, v) in all) {
                assertFalse("${t.locale} $k : « $v »", v.contains("(s)") || v.contains("(aux)"))
            }
        }
    }

    @Test fun `plus de Fixture dans les textes francais`() {
        val all = fr.strings.entries.map { it.key to it.value } +
            fr.plurals.flatMap { (k, m) -> m.values.map { k to it } }
        for ((k, v) in all) assertFalse("fr $k : « $v »", Regex("""\bFixture""").containsMatchIn(v))
    }

    @Test fun `cles supprimees absentes`() {
        val gone = listOf(
            // §7.3 (tranches A à D)
            "menu_options", "menu_section_tools", "menu_section_sync", "nav_account", "nav_layers", "tool_classes",
            "nav_customize_toolbar", "nav_gdtf_share_models", "cam_viewpoints", "cam_side", "cam_iso", "cam_reset_view",
            "opt_coloring_fmt", "opt_satellite_map", "opt_size_fmt", "opt_gap_fmt", "opt_label_detached",
            "opt_label_detached_cap", "plan_title_fmt", "layers_title_3d", "home_my_account", "common_back_3d",
            "sel_fixtures_selected_fmt", "fixture_id_name_fmt",
            // Tranche E
            "tool_ref_plan", "tool_gps_3d", "nav_power_cabling", "plan_export_views_fmt", "plan_export_ready_fmt",
            "fx_type_channels_fmt", "fx_custom_type_fmt", "fx_channels_count", "cabling_assigned_fmt",
            "cabling_clear_circuit_msg_fmt", "dmx_assigned_fmt", "dmx_clear_core_msg_fmt", "gdtf_resolved_fmt",
            "gdtf_type_status_fmt", "home_members_version", "legend_more_fmt", "legend_more_layers_fmt",
            "patch_count_hint", "pdf_unknown_footprint_fmt", "pdf_unknown_watts_fmt", "universe_channels_count_fmt",
            "universe_conflicts_fmt"
        )
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val code = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (k in gone) {
            assertFalse("fr $k", k in fr.strings)
            assertFalse("en $k", k in en.strings)
            assertFalse("code R.string.$k", Regex("""R\.string\.$k\b""").containsMatchIn(code))
        }
        // Devenues des pluriels (l'ancien nom de chaîne n'existe plus nulle part).
        assertTrue("home_members_version" in fr.plurals)
    }

    /** « OK » → « Terminé » (rapport MA, couleur personnalisée) ; plus de texte en dur dans les surfaces touchées. */
    @Test fun `Termine et textes en ressources dans le code`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        fun src(f: String) = File(root, "com/minou/mvrviewer/ui/$f").readText()
        for (f in listOf("MaLibraryScreen.kt", "BackgroundColorPicker.kt"))
            assertFalse("$f : « OK »", src(f).contains("Text(\"OK\")"))
        assertFalse(src("FixtureDetail.kt").contains("Text(\"Fixture ID\")"))
        assertFalse(src("Scene3DScreen.kt").contains("\"GDTF Share\""))
        // Outils à libellé FIXE : plus de libellé qui change selon l'état.
        for (f in listOf("Scene3DScreen.kt", "PlanScreen.kt"))
            assertFalse(f, Regex("""if \((referencePlan == null|importedModels\.isEmpty\(\))\) stringResource""").containsMatchIn(src(f)))
    }
}
