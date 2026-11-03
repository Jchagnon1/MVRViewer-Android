package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Place des panneaux du bas-gauche (SPEC-menus-android §3.6, portage de
 * `FloatingPanelSlotTests` iOS : mêmes cadres mesurés au simulateur), et état des
 * panneaux ouverts de la scène ([ScenePanels]).
 */
class FloatingPanelSlotTest {

    private val M = PanelPlacementMath
    private val GAP = M.CORNER_STACK_GAP

    private fun r(x: Float, y: Float, w: Float, h: Float) = Rect(x, y, x + w, y + h)

    /** Cadre du panneau une fois posé (montée et hauteur maximale appliquées). */
    private fun placed(slot: PanelPlacementMath.FloatingPanelSlot, natural: Rect, height: Float): Rect {
        val h = minOf(height, slot.maxHeight ?: height)
        val bottom = natural.bottom - slot.lift
        return Rect(natural.left, bottom - h, natural.right, bottom)
    }

    private val natural3DLand = r(78f, 309f, 300f, 1f)
    private val status3DLand = r(78f, 16f, 303f, 53f)
    private val tools3DLand = r(628f, 16f, 184f, 109f)
    private val card3DLand = r(80f, 306f, 714f, 60f)

    @Test fun paysage3DDegageLeHautEtLaFicheCompacte() {
        val slot = M.floatingPanelSlot(natural3DLand, listOf(status3DLand, tools3DLand), listOf(card3DLand), 0f)
        val panel = placed(slot, natural3DLand, 236f)
        assertFalse(panel.overlaps(status3DLand))
        assertFalse(panel.overlaps(card3DLand))
        assertEquals("juste au-dessus de la fiche", card3DLand.top - GAP, panel.bottom, 0.01f)
        assertEquals("juste sous le haut", status3DLand.bottom + GAP, panel.top, 0.01f)
        assertTrue(panel.height >= M.FLOATING_PANEL_MIN_HEIGHT)
    }

    @Test fun sansFicheSeuleLaHauteurEstBornee() {
        val slot = M.floatingPanelSlot(natural3DLand, listOf(status3DLand, tools3DLand), listOf(Rect.Zero), 0f)
        assertEquals(0f, slot.lift)
        val panel = placed(slot, natural3DLand, 236f)
        assertFalse(panel.overlaps(status3DLand))
        assertEquals(natural3DLand.bottom, panel.bottom, 0.01f)
    }

    @Test fun planPaysageLeHautPasseSousLaRecherche() {
        val natural = r(78f, 309f, 300f, 1f)
        val pill = r(357.3f, 84f, 454.7f, 40.7f)
        val slot = M.floatingPanelSlot(natural, listOf(pill), emptyList(), 52f + GAP)
        val panel = placed(slot, natural, 236f)
        assertFalse(panel.overlaps(pill))
        assertEquals(0f, slot.lift)
    }

    @Test fun portraitLePanneauMonteAuDessusDeLaFiche() {
        val natural = r(16f, 783f, 300f, 1f)
        val card = r(16f, 620f, 370f, 200f)
        val tools = r(210f, 62f, 176f, 110f)
        val status = r(16f, 62f, 190f, 53f)
        val slot = M.floatingPanelSlot(natural, listOf(status, tools), listOf(card), 0f)
        val panel = placed(slot, natural, 380f)
        assertFalse(panel.overlaps(card))
        assertFalse(panel.overlaps(tools))
        assertFalse(panel.overlaps(status))
        assertEquals("assez de place : panneau entier", 380f, panel.height, 0.01f)
        assertEquals(natural.bottom - (card.top - GAP), slot.lift, 0.01f)
    }

    @Test fun horsDeLaColonneIgnore() {
        val natural = r(16f, 783f, 300f, 1f)
        val right = r(330f, 600f, 56f, 120f)
        val slot = M.floatingPanelSlot(natural, listOf(right), listOf(right), 0f)
        assertEquals(0f, slot.lift)
        assertEquals(natural.bottom, slot.maxHeight!!, 0.01f)
    }

    @Test fun sansPlaceGardeLeMinimumAuDessusDeLaFiche() {
        val tall = r(80f, 186f, 714f, 180f)
        val slot = M.floatingPanelSlot(natural3DLand, listOf(status3DLand), listOf(tall), 0f)
        val panel = placed(slot, natural3DLand, 236f)
        assertFalse(panel.overlaps(tall))
        assertEquals(M.FLOATING_PANEL_MIN_HEIGHT, panel.height, 0.01f)
        assertTrue("jamais hors de l'écran", panel.top >= 0f)
    }

    @Test fun rienDeMesureRienNeBouge() {
        val slot = M.floatingPanelSlot(Rect.Zero, listOf(status3DLand), listOf(card3DLand), 0f)
        assertEquals(0f, slot.lift)
        assertNull(slot.maxHeight)
    }

    // ------------------------------------------------------------ panneaux ouverts

    @Test fun leDernierOuvertPasseDevant() {
        var p = ScenePanels().open(ScenePanel.GEO)
        assertEquals(1f, p.zIndex(ScenePanel.GEO))
        p = p.open(ScenePanel.LABELS)
        assertTrue(p.geo && p.labels)
        assertEquals("ouvert en dernier : devant", 1f, p.zIndex(ScenePanel.LABELS))
        assertEquals(0f, p.zIndex(ScenePanel.GEO))
        // Rouvrir un panneau déjà ouvert (menu) le ramène devant.
        p = p.open(ScenePanel.GEO)
        assertEquals(1f, p.zIndex(ScenePanel.GEO))
        // Outil ancré : bascule ; fermer celui de devant ne laisse personne « devant ».
        p = p.toggle(ScenePanel.GEO)
        assertFalse(p.geo)
        assertEquals(null, p.front)
        assertTrue(p.toggle(ScenePanel.REF_PLAN).refPlan)
        assertFalse(p.close(ScenePanel.LABELS).labels)
    }

    @Test fun uneBasculeDeVueRefermeLesPanneaux() {
        assertEquals(ScenePanels(), ScenePanels.afterBaseChange())
        assertEquals(ScenePanels(geo = true, refPlan = false, labels = true),
            ScenePanels.fromHooks(geo = true, refPlan = false, labels = true))
        // Le câblage du réducteur : une bascule de base réinitialise les panneaux.
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val code = File(root, "com/minou/mvrviewer/ui/SceneScreen.kt").readText()
        assertTrue(code.contains("if (r.state.base != nav.base) panels = ScenePanels.afterBaseChange()"))
    }

    @Test fun nouveauxIdentifiantsDePanneauStables() {
        val keys = PanelLayoutStore.PanelID.entries.associate { it.name to it.key }
        assertEquals("geoPosition3D", keys["GEO_POSITION_3D"])
        assertEquals("geoPositionPlan", keys["GEO_POSITION_PLAN"])
        assertEquals("referencePlan3D", keys["REF_PLAN_3D"])
        assertEquals("refPlanPlacementPlanLeading", keys["REF_PLAN_PLAN_LEADING"])
        assertEquals("labels3D", keys["LABELS_3D"])
        // Anciennes clés gardées (jamais renommées).
        assertEquals("refPlanPlacementPlan", keys["REF_PLAN_PLACEMENT_PLAN"])
        assertEquals("labelsPlan", keys["LABELS_PLAN"])
        assertEquals(PanelLayoutStore.PanelID.entries.size, keys.values.toSet().size)
    }
}
