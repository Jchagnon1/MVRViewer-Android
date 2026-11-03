package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Panneau « Réglage des étiquettes » (SPEC-menus-android §3.8, tranche B) :
 * mêmes réglages dans les deux vues ; Placement, Blocs séparés et dézoom dans le
 * plan seulement ; crans persistés inchangés ; ancien ⋯ allégé ; textes fr / en.
 */
class LabelSettingsSectionsTest {

    @Test fun placementEtDezoomDansLePlanSeulement() {
        assertEquals(listOf(LabelSettingsSection.SHOW, LabelSettingsSection.CONTENT, LabelSettingsSection.APPEARANCE),
            LabelSettingsSections.of(ProjectSurface.SCENE_3D))
        assertEquals(listOf(LabelSettingsSection.SHOW, LabelSettingsSection.CONTENT, LabelSettingsSection.APPEARANCE,
            LabelSettingsSection.PLACEMENT), LabelSettingsSections.of(ProjectSurface.PLAN))
        assertEquals(listOf(LabelAppearanceRow.SIZE, LabelAppearanceRow.OFFSET),
            LabelSettingsSections.appearance(ProjectSurface.SCENE_3D))
        assertEquals(listOf(LabelAppearanceRow.SIZE, LabelAppearanceRow.OFFSET, LabelAppearanceRow.HIDE_WHEN_ZOOMED_OUT),
            LabelSettingsSections.appearance(ProjectSurface.PLAN))
    }

    @Test fun cransPersistesInchanges() {
        // Valeurs des anciens cycles du menu (§8 D9) : rien ne change pour un projet existant.
        assertEquals(listOf(0.7f, 1f, 1.4f), LabelSettingsSections.SIZE_STEPS)
        assertEquals(listOf(0.5f, 1f, 2f), LabelSettingsSections.OFFSET_STEPS)
        LabelSettingsSections.SIZE_STEPS.forEachIndexed { i, v -> assertEquals(i, LabelSettingsSections.sizeIndex(v)) }
        LabelSettingsSections.OFFSET_STEPS.forEachIndexed { i, v -> assertEquals(i, LabelSettingsSections.offsetIndex(v)) }
        // Valeurs relues hors cran (anciens projets, autre plateforme) : cran le plus proche.
        assertEquals(0, LabelSettingsSections.sizeIndex(0.6f))
        assertEquals(1, LabelSettingsSections.sizeIndex(1.1f))
        assertEquals(2, LabelSettingsSections.sizeIndex(1.8f))
        assertEquals(0, LabelSettingsSections.offsetIndex(0.3f))
        assertEquals(1, LabelSettingsSections.offsetIndex(1.2f))
        assertEquals(2, LabelSettingsSections.offsetIndex(2.5f))
    }

    @Test fun blocsSeparesUneCaseParChampAffiche() {
        val fields = setOf(LabelContent.DMX, LabelContent.ID, LabelContent.SOCAPEX)
        assertEquals(listOf(LabelContent.ID, LabelContent.DMX, LabelContent.SOCAPEX),
            LabelSettingsSections.separateBlockFields(fields))
        assertTrue(LabelSettingsSections.separateBlockFields(emptySet()).isEmpty())
    }

    @Test fun decocherUnChampRetireSonBlocSepare() {
        val (f1, d1) = LabelSettingsSections.toggleField(
            setOf(LabelContent.ID, LabelContent.DMX), setOf(LabelContent.DMX), LabelContent.DMX)
        assertEquals(setOf(LabelContent.ID), f1)
        assertEquals("pas de « détaché » invisible qui ressusciterait", emptySet<LabelContent>(), d1)
        val (f2, d2) = LabelSettingsSections.toggleField(f1, d1, LabelContent.NAME)
        assertEquals(setOf(LabelContent.ID, LabelContent.NAME), f2)
        assertEquals(emptySet<LabelContent>(), d2)
    }

    @Test fun ancienMenuAllegeEtCodeSansClesRetirees() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        // Le ⋯ (tranche C : modèle + composable du menu unique). Les options
        // d'étiquettes, le satellite, son curseur et la ligne d'état de la
        // calibration en restent absents (panneaux) ; « Étiquettes » reste.
        val menu = listOf("MainMenuModel.kt", "MainMenu.kt").joinToString("\n") {
            File(root, "com/minou/mvrviewer/ui/$it").readText()
        }
        for (gone in listOf("opt_size_fmt", "opt_gap_fmt", "opt_label_detached", "opt_hide_when_zoomed_out",
                "opt_reset_label_offsets", "opt_labels_of_selection", "opt_satellite_map", "Slider(",
                "calibrationStatus")) assertFalse("⋯ : $gone", menu.contains(gone))
        for (kept in listOf("R.string.opt_labels)", "R.string.menu_label_settings", "R.string.menu_gps_position"))
            assertTrue("⋯ : $kept", menu.contains(kept))
        // Clés devenues inutiles : absentes des deux langues et du code.
        val code = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (k in listOf("opt_size_fmt", "opt_gap_fmt", "opt_label_detached", "opt_label_detached_cap",
                "opt_satellite_map")) {
            assertFalse("fr $k", TestRes.FR.strings.containsKey(k))
            assertFalse("en $k", TestRes.EN.strings.containsKey(k))
            assertFalse("usage de R.string.$k", Regex("""R\.string\.$k\b""").containsMatchIn(code))
        }
        // Plus de curseur d'opacité flottant dans le plan (décision 8).
        val plan = File(root, "com/minou/mvrviewer/ui/PlanScreen.kt").readText()
        assertFalse(plan.contains("onValueChange = { options.satelliteOpacity = it }"))
    }

    @Test fun textesFrEn() {
        val expected = mapOf(
            "tool_label_settings" to ("Réglage des étiquettes" to "Label settings"),
            "tool_label_size" to ("Réglage des étiquettes" to "Label settings"),
            "menu_label_settings" to ("Réglage des étiquettes…" to "Label settings…"),
            "labels_section_appearance" to ("Apparence" to "Appearance"),
            "labels_size" to ("Taille" to "Size"),
            "labels_offset" to ("Écart au projecteur" to "Offset from the fixture"),
            "labels_section_placement" to ("Placement sur le plan" to "Placement on the plan"),
            "labels_placement_help" to ("Touchez une étiquette pour la saisir, puis faites-la glisser ; retouchez-la pour la relâcher." to
                "Tap a label to pick it up, then drag it; tap it again to let go."),
            "labels_separate_blocks" to ("Blocs séparés" to "Separate blocks"),
            "opt_hide_when_zoomed_out" to ("Masquer les étiquettes trop dézoomées" to "Hide labels when zoomed too far out"),
            "size_small" to ("Petite" to "Small"), "size_medium" to ("Moyenne" to "Medium"),
            "size_large" to ("Grande" to "Large"),
            "gap_near" to ("Proche" to "Near"), "gap_normal" to ("Normal" to "Normal"), "gap_far" to ("Loin" to "Far"),
            "opt_label_content" to ("Contenu" to "Content")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, TestRes.FR.get(k))
            assertEquals("en $k", v.second, TestRes.EN.get(k))
        }
        assertEquals("Désactiver (3)", TestRes.FR.get("labels_turn_off_fmt", 3))
        assertEquals("Turn off (3)", TestRes.EN.get("labels_turn_off_fmt", 3))
    }
}
