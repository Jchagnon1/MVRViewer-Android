package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Relecture du rangement Android (commit 9224e11, SPEC-menus-android §13) : les
 * cinq défauts reproduits à l'émulateur, chacun verrouillé par sa règle pure et
 * par son branchement. Unités : dp (densité 1), cadres relevés sur l'émulateur
 * Medium_Phone (411 dp de large), repère de la vue.
 */
class MenusReviewFixesTest {

    private val M = PanelPlacementMath

    private fun src(name: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/ui/$name"), File("app/src/main/java/com/minou/mvrviewer/ui/$name"))
            .first { it.isFile }.readText()

    /** Extrait le corps d'une fonction (de sa déclaration à la suivante). */
    private fun body(code: String, decl: String): String {
        val i = code.indexOf(decl)
        assertTrue("introuvable : $decl", i >= 0)
        val next = code.indexOf("\n@Composable", i + decl.length)
        return if (next < 0) code.substring(i) else code.substring(i, next)
    }

    // ------------------------------------------------ 1. barre ancrée du HAUT

    // Rangée du haut, téléphone portrait : ← (48 dp à 4 dp du bord), groupe de
    // droite [cadre] [multi] [⋯] (3 × 48 + 2 × 6 = 156 dp) ; pastille « Vue » du
    // plan centrée dessous (58 dp), bloc d'état et pastille 3D dessous aussi.
    private val W = 411f
    private val back = Rect(4f, 4f, 52f, 52f)
    private val trailing = Rect(251f, 4f, 407f, 52f)
    private val menuOnly = Rect(359f, 4f, 407f, 52f)
    private val planPill = Rect(137f, 58f, 274f, 90f)
    private val pill3D = Rect(252f, 58f, 403f, 90f)
    private val status3D = Rect(8f, 58f, 66f, 82f)

    /** Largeur d'une barre de [n] outils (48 dp, écart 8 dp). */
    private fun barWidth(n: Int) = n * 48f + (n - 1) * 8f

    /** Cadre de la barre posée (largeur bornée : au-delà, elle défile). */
    private fun placed(slot: PanelPlacementMath.TopBarSlot, n: Int, viewWidth: Float = W): Rect {
        val w = minOf(barWidth(n), slot.maxWidth)
        val l = slot.left(w, viewWidth / 2f)
        return Rect(l, slot.top, l + w, slot.top + 48f)
    }

    @Test fun barreDuHautDansLaRangeeEntreLaFlecheEtLeGroupeDeDroite() {
        val slot = M.topBarSlot(W, listOf(back, trailing), gap = 8f, minWidth = 48f, margin = 12f)
        // Avant : centrée à 12 dp du haut, 3 outils couvraient le bouton cadre
        // (125,5 → 285,5 contre 251) et la pastille du plan (12 → 60 contre 50).
        val old = Rect((W - barWidth(3)) / 2f, 12f, (W + barWidth(3)) / 2f, 60f)
        assertTrue(old.overlaps(trailing) && old.overlaps(planPill))
        for (n in 1..6) {
            val bar = placed(slot, n)
            for (o in listOf(back, trailing, planPill, pill3D, status3D))
                assertFalse("$n outil(s) sur $o : $bar", bar.overlaps(o))
            // Alignée sur la rangée, chaque bouton visible ou atteignable en défilant.
            assertEquals(back.top, bar.top, 0f)
            assertTrue(bar.left >= back.right + 8f - 0.01f && bar.right <= trailing.left - 8f + 0.01f)
        }
        // 3 outils tiennent (183 dp libres) : poussés contre le groupe de droite.
        assertEquals(183f, slot.maxWidth, 0.01f)
        assertEquals(trailing.left - 8f - barWidth(3), placed(slot, 3).left, 0.01f)
        // 4 outils (208 dp) : la barre prend toute la place et défile.
        assertEquals(183f, placed(slot, 4).width, 0.01f)
    }

    @Test fun barreDuHautCentreeQuandElleTient() {
        // Cadre et multi ancrés : le groupe de droite n'a plus que ⋯.
        val slot = M.topBarSlot(W, listOf(back, menuOnly), gap = 8f, minWidth = 48f, margin = 12f)
        val bar = placed(slot, 5)
        assertEquals(W / 2f, bar.center.x, 0.01f)
        assertFalse(bar.overlaps(menuOnly) || bar.overlaps(back))
        // Paysage (891 dp) : centrée sur la vue.
        val landTrailing = Rect(731f, 4f, 887f, 52f)
        val land = M.topBarSlot(891f, listOf(back, landTrailing), gap = 8f, minWidth = 48f, margin = 12f)
        assertEquals(891f / 2f, placed(land, 3, viewWidth = 891f).center.x, 0.01f)
    }

    @Test fun barreDuHautSensDeLectureInverseEtCasLimites() {
        // Droite-à-gauche : ← à droite, groupe à gauche — même bande libre au centre.
        val rtl = M.topBarSlot(W, listOf(Rect(359f, 4f, 407f, 52f), Rect(4f, 4f, 160f, 52f)), gap = 8f, minWidth = 48f, margin = 12f)
        assertEquals(168f, rtl.freeLeft, 0.01f)
        assertEquals(351f, rtl.freeRight, 0.01f)
        // Écran minuscule : pas la place d'un bouton → SOUS la rangée, pleine largeur.
        val tiny = M.topBarSlot(260f, listOf(back, Rect(100f, 4f, 256f, 52f)), gap = 8f, minWidth = 48f, margin = 12f)
        assertEquals(60f, tiny.top, 0.01f)
        assertEquals(12f, tiny.freeLeft, 0.01f)
        assertEquals(248f, tiny.freeRight, 0.01f)
        // Rangée pas encore mesurée : à 12 dp du haut, pleine largeur ; cadres vides ignorés.
        val none = M.topBarSlot(W, listOf(Rect.Zero, Rect.Zero), gap = 8f, minWidth = 48f, margin = 12f)
        assertEquals(PanelPlacementMath.TopBarSlot(12f, W - 12f, 12f), none)
        // Vue non mesurée : aucune place.
        assertEquals(0f, M.topBarSlot(0f, listOf(back, trailing)).maxWidth, 0f)
    }

    @Test fun barreDuHautBrancheeDansLesDeuxVues() {
        val v3 = src("Scene3DScreen.kt"); val plan = src("PlanScreen.kt")
        val bars = src("AnchoredToolbars.kt"); val chrome = src("SceneChrome.kt")
        assertTrue(plan.contains("rowOccupants = { listOf(topRowBackPx, topRowTrailingPx) },"))
        assertTrue(plan.contains("container = { planBoundsPx }"))
        assertTrue(plan.contains("onLeading = { topRowBackPx = it },"))
        assertTrue(plan.contains("onTrailing = { topRowTrailingPx = it }"))
        assertTrue(v3.contains("rowOccupants = { listOf(topRow3DBackPx, topRow3DTrailingPx) },"))
        assertTrue(v3.contains("container = { sceneBoxPx }"))
        assertTrue(v3.contains("onLeading = { topRow3DBackPx = it },"))
        assertTrue(v3.contains("onTrailing = { topRow3DTrailingPx = it }"))
        // La rangée rend le cadre de ← (en plus de son bas).
        assertTrue(chrome.contains("onRowBottom(b.bottom); onLeading(b)"))
        // La barre du haut passe par la règle et défile ; plus de TopCenter à 12 dp.
        val top = body(bars, "private fun BoxScope.TopBar(")
        assertTrue(top.contains("PanelPlacementMath.topBarSlot("))
        assertTrue(top.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(bars.contains("if (edge == ToolbarEdge.TOP && top != null) {"))
    }

    // ------------------------------------------- 2. cadre armé + toucher (3D)

    @Test fun cadreArmeToucherAjouteOuRetireMemeRegleQueLePlan() {
        // Armer le cadre force le multi (les deux vues) : le toucher ajoute / retire.
        val armed = SelectionRules.toggleRect(SelectionModes()).modes
        assertEquals(listOf(105, 104), SelectionRules.tap(listOf(105), 104, armed))
        assertEquals(listOf(104), SelectionRules.tap(listOf(105, 104), 105, armed))
        // Toucher le vide ne perd pas la sélection en cours.
        assertEquals(listOf(105), SelectionRules.tap(listOf(105), null, armed))
    }

    @Test fun cadreArmeLeToucherAtteintHandleTapEn3D() {
        val v3 = src("Scene3DScreen.kt")
        // La couche du cadre capte tous les gestes : le toucher y est rendu à
        // handleTap (qui applique SelectionRules.tap), le glissé trace le cadre.
        val i = v3.indexOf("if (rectMode) {\n                Box(")
        assertTrue(i > 0)
        val tap = v3.indexOf("detectTapGestures(onTap = { handleTap(it) })", i)
        val drag = v3.indexOf("detectDragGestures(", i)
        assertTrue(tap in (i + 1) until drag)
        assertTrue(v3.contains("val next = SelectionRules.tap(selected.toList(), best.takeIf { it >= 0 }, selectionModes())"))
    }

    // ------------------------------------------------ 3. attente de l'import

    @Test fun lectureDuPlanAnnonceeDansLesDeuxVues() {
        val scene = src("SceneScreen.kt"); val v3 = src("Scene3DScreen.kt")
        // Indicateur commun (posé par la scène, au-dessus de la 3D comme du plan).
        assertTrue(scene.contains("if (importingRefPlan) BusyBanner(\n            androidx.compose.ui.res.stringResource(R.string.refplan_reading),"))
        // Pas de relance pendant la lecture.
        assertTrue(scene.contains("if (importingRefPlan) return"))
        // La 3D n'a plus son propre texte (sinon deux indicateurs à la fois).
        assertFalse(v3.contains("R.string.refplan_reading"))
        assertEquals("Lecture du plan…", TestRes.FR.get("refplan_reading"))
        assertEquals("Reading the plan…", TestRes.EN.get("refplan_reading"))
    }

    // --------------------------------- 4. retour système, champ « N° » ouvert

    @Test fun retourSystemeRefermeDAbordLeChampDeRecherche() {
        val stack = body(src("SceneChrome.kt"), "fun BoxScope.SearchReframeStack(")
        assertTrue(stack.contains("BackHandler(enabled = searchOpen) { searchOpen = false; notFound = null; keyboard?.hide() }"))
        // Le retour de la scène reste en place (composé avant la vue : le champ passe devant).
        assertTrue(src("SceneScreen.kt").contains("BackHandler { navigate(SceneNavEvent.SystemBack) }"))
    }

    // ------------------------------------- 5. légende et panneaux du bas-gauche

    @Test fun panneauDuBasGaucheSousLaLegendeEnPortrait() {
        // Plan portrait : légende haut-gauche (100 dp), panneau Plan importé dans la
        // même colonne, son bas posé à 790 dp.
        val legend = Rect(12f, 100f, 95f, 150f)
        val natural = Rect(12f, 789f, 276f, 790f)
        val placedTop = { tops: List<Rect> ->
            val slot = M.floatingPanelSlot(natural, tops, emptyList(), 52f + M.CORNER_STACK_GAP)
            natural.bottom - slot.lift - (slot.maxHeight ?: 0f)
        }
        // Avant : borné par la seule pastille, le haut du panneau passait sur la légende.
        assertTrue(placedTop(listOf(planPill)) < legend.bottom)
        // Avec la légende : sous elle, à l'écart près.
        assertEquals(legend.bottom + M.CORNER_STACK_GAP, placedTop(listOf(planPill, legend)), 0.01f)
        val plan = src("PlanScreen.kt")
        assertTrue(plan.contains("viewPillPx, modeBannerPx.takeIf { modeBanner != null }, legendBoundsPx?.takeIf { legendShown }"))
    }
}
