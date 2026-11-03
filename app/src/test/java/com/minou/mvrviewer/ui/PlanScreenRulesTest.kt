package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect
import com.minou.mvrviewer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * Relecture du portage « calibration 3 points » (af503cd) : ce qui se passe
 * AUTOUR de l'assistant dans la vue plan, testé sans émulateur par les règles
 * pures que PlanScreen applique — et le branchement de chaque règle, outil par
 * outil. Unités : dp (densité 1), comme les points iOS.
 */
class PlanScreenRulesTest {

    // ------------------------------------------------------------ outils

    private fun src(path: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/$path"), File("app/src/main/java/com/minou/mvrviewer/$path"))
            .first { it.isFile }.readText()

    /** Corps (accolades appariées) qui suit la première occurrence de [decl]. */
    private fun block(source: String, decl: String): String {
        val i = source.indexOf(decl)
        assertTrue("absent : $decl", i >= 0)
        val open = source.indexOf('{', i)
        var depth = 0
        for (k in open until source.length) {
            when (source[k]) { '{' -> depth++; '}' -> if (--depth == 0) return source.substring(open, k + 1) }
        }
        error("accolades non appariées : $decl")
    }

    /** Déclaration `add(ToolSpec(ToolId.X, …))` complète (jusqu'au prochain `add(`). */
    private fun toolSpec(plan: String, id: String): String {
        val start = plan.indexOf("add(ToolSpec(ToolId.$id,")
        assertTrue("outil absent : $id", start >= 0)
        val next = plan.indexOf("add(ToolSpec(", start + 1)
        return plan.substring(start, if (next < 0) plan.length else next)
    }

    // ------------------------------- 1. le bas de l'écran appartient à l'assistant

    @Test fun ficheEtBanniereMasqueesPendantLAssistantEtLAffectation() {
        // Hors assistant : 1 projecteur = fiche, plusieurs = bannière jaune.
        assertEquals(PlanSelectionBar.NONE, PlanBottomRules.selectionBar(0, false, false))
        assertEquals(PlanSelectionBar.CARD, PlanBottomRules.selectionBar(1, false, false))
        assertEquals(PlanSelectionBar.BANNER, PlanBottomRules.selectionBar(5, false, false))
        // Assistant ouvert (sélection gardée d'avant) : rien en bas — elle ne
        // recouvre plus « Appliquer », « Refaire la mesure », « Utiliser quand même ».
        for (n in 0..5) {
            assertEquals(PlanSelectionBar.NONE, PlanBottomRules.selectionBar(n, threePointOpen = true, assignOpen = false))
            assertEquals(PlanSelectionBar.NONE, PlanBottomRules.selectionBar(n, threePointOpen = false, assignOpen = true))
        }
    }

    @Test fun ficheDuBasBrancheeSurLaRegle() {
        val plan = src("ui/PlanScreen.kt")
        assertTrue(plan.contains("PlanBottomRules.selectionBar("))
        assertTrue(plan.contains("threePointOpen = cal3Session != null || cal3Starting, assignOpen = assignTarget != null"))
        // Tranche D : mesure et aide « je suis ici » prennent aussi le bas.
        assertTrue(plan.contains("measuring = measureMode, anchorAid = calibrating"))
        // Fiche (1) ou bannière (plusieurs) : celles de SceneScreen, communes à la 3D.
        assertTrue(plan.contains("if (selectionBar == PlanSelectionBar.CARD && one != null) {"))
        assertTrue(plan.contains("cardHost.card(one, cardStylePlan) { selected.clear() }"))
        assertTrue(plan.contains("cardHost.banner(selected.size, cardStylePlan) { selected.clear() }"))
        // Une seule évaluation de la règle : les obstacles relisent la même valeur.
        assertEquals(1, Regex("PlanBottomRules\\.selectionBar\\(").findAll(plan).count())
        // L'ancienne condition, qui ignorait l'assistant, a disparu.
        assertFalse(plan.contains("if (selected.size == 1) {"))
        assertFalse(plan.contains("} else if (selected.size > 1) {"))
    }

    @Test fun ficheMasqueePendantLaMesureEtLAideJeSuisIci() {
        for (n in 0..3) {
            assertEquals(PlanSelectionBar.NONE, PlanBottomRules.selectionBar(n, false, false, measuring = true))
            assertEquals(PlanSelectionBar.NONE, PlanBottomRules.selectionBar(n, false, false, anchorAid = true))
        }
        // Même règle que la 3D (une seule fonction).
        assertEquals(SelectionCardLayout.presentation(1), PlanBottomRules.selectionBar(1, false, false))
        assertEquals(SelectionCardLayout.presentation(4), PlanBottomRules.selectionBar(4, false, false))
    }

    // ------------------------------------------- 2. recadrage après une rotation

    /** Bord haut et bas (dp) du contenu une fois posé (même transformée que PlanScreen.toScreen). */
    private fun contentY(canvasW: Float, canvasH: Float, spanX: Float, spanY: Float, scale: Float, dy: Float): Pair<Float, Float> {
        val bs = min(canvasW / spanX, canvasH / spanY) * 0.9f * scale
        val c = canvasH / 2f + dy
        return (c - spanY * bs / 2f) to (c + spanY * bs / 2f)
    }

    @Test fun rotationPortraitPaysageRecadreAuDessusDuPanneau() {
        // Pixel 6a : 411 × 820 dp puis 914 × 360 dp ; plan 30 × 20 m ; panneau
        // 230 dp à 84 dp du bas en portrait, 160 dp à 8 dp en paysage (compact).
        val spanX = 30_000f; val spanY = 20_000f
        val portraitTop = 820f - 84f - 230f
        val first = ThreePointPlanFit.refit(411f, 820f, spanX, spanY, 0f, 820f, portraitTop,
            fittedReserve = 0f, untouched = true, sizeChanged = false, slack = 4f)
        assertNotNull(first); first!!
        assertEquals(314f, first.reserve, 1e-3f)
        assertEquals(1f, first.scale, 1e-6f)
        assertEquals(-157f, first.dy, 1e-3f)

        val landTop = 360f - 8f - 160f
        // AVANT : la réserve baisse (168 ≤ 314 + 4) → rien ne bougeait et le plan
        // gardait la vue du portrait : 43 % hors écran par le haut.
        val (staleTop, staleBottom) = contentY(914f, 360f, spanX, spanY, first.scale, first.dy)
        assertEquals(-139f, staleTop, 0.5f)
        assertEquals(185f, staleBottom, 0.5f)
        assertNull("sans rotation, une réserve qui baisse ne recadre pas (pas de sautillement)",
            ThreePointPlanFit.refit(914f, 360f, spanX, spanY, 0f, 360f, landTop,
                fittedReserve = first.reserve, untouched = true, sizeChanged = false, slack = 4f))

        // APRÈS : la taille a changé → recadrage avec la réserve COURANTE.
        val rotated = ThreePointPlanFit.refit(914f, 360f, spanX, spanY, 0f, 360f, landTop,
            fittedReserve = first.reserve, untouched = true, sizeChanged = true, slack = 4f)
        assertNotNull(rotated); rotated!!
        assertEquals(168f, rotated.reserve, 1e-3f)
        val (top, bottom) = contentY(914f, 360f, spanX, spanY, rotated.scale, rotated.dy)
        assertEquals(9.6f, top, 0.5f)
        assertEquals(182.4f, bottom, 0.5f)
        assertTrue("tout le plan au-dessus du panneau", top >= 0f && bottom <= landTop)
    }

    @Test fun recadrageSeulementSiLePlanNAPasEteTouche() {
        // Plan bougé par l'utilisateur : ni rotation ni panneau qui grandit ne recadrent.
        assertNull(ThreePointPlanFit.refit(914f, 360f, 30_000f, 20_000f, 0f, 360f, 192f,
            fittedReserve = 314f, untouched = false, sizeChanged = true, slack = 4f))
        assertNull(ThreePointPlanFit.refit(411f, 820f, 30_000f, 20_000f, 0f, 820f, 300f,
            fittedReserve = 314f, untouched = false, sizeChanged = false, slack = 4f))
        // Panneau qui grandit de plus de 4 dp : recadré ; de 4 dp ou moins : non.
        assertNotNull(ThreePointPlanFit.refit(411f, 820f, 30_000f, 20_000f, 0f, 820f, 500f,
            fittedReserve = 314f, untouched = true, sizeChanged = false, slack = 4f))
        assertNull(ThreePointPlanFit.refit(411f, 820f, 30_000f, 20_000f, 0f, 820f, 503f,
            fittedReserve = 314f, untouched = true, sizeChanged = false, slack = 4f))
        // Panneau pas encore mesuré (ou mesuré hors du plan) : on attend.
        assertNull(ThreePointPlanFit.refit(914f, 360f, 30_000f, 20_000f, 0f, 360f, 506f,
            fittedReserve = 314f, untouched = true, sizeChanged = true, slack = 4f))
        assertNull(ThreePointPlanFit.refit(914f, 360f, 30_000f, 20_000f, 0f, 360f, 0f,
            fittedReserve = 0f, untouched = true, sizeChanged = false, slack = 4f))
    }

    @Test fun recadrageBrancheSurLaRegle() {
        val plan = src("ui/PlanScreen.kt")
        val effect = block(plan, "LaunchedEffect(cal3Session != null, cal3PanelTopPx, planBoundsPx, canvas)")
        assertTrue(effect.contains("ThreePointPlanFit.refit("))
        assertTrue(effect.contains("sizeChanged = fitted != null && fitted != canvas"))
        assertTrue("la taille cadrée est retenue", effect.contains("cal3FitCanvas = canvas"))
        assertTrue("la fermeture l'oublie", block(plan, "fun closeThreePoint()").contains("cal3FitCanvas = null"))
    }

    // --------------------------- 3. mention IGN et curseur au-dessus du panneau

    // Cas iOS (SatelliteIGNFixTests) : iPhone portrait, mention 260 pt, panneau 300 pt.
    private val iosMention = Rect(126f, 671f, 126f + 260f, 671f + 34f)
    private val iosPanel = Rect(16f, 470f, 16f + 300f, 470f + 370f)

    @Test fun clearanceLiftPorteTelQuelDIos() {
        val lift = PanelPlacementMath.clearanceLift(iosMention, listOf(iosPanel), 120f)
        val shown = iosMention.translate(0f, -lift)
        assertTrue(lift > 0f)
        assertFalse(shown.overlaps(iosPanel))
        assertEquals(iosPanel.top - PanelPlacementMath.CORNER_STACK_GAP, shown.bottom, 0.01f)
        // Rien dans la colonne, panneau au-dessus, cadres vides : 0.
        assertEquals(0f, PanelPlacementMath.clearanceLift(iosMention, emptyList(), 120f), 0f)
        assertEquals(0f, PanelPlacementMath.clearanceLift(Rect(740f, 1240f, 1000f, 1274f), listOf(iosPanel), 120f), 0f)
        assertEquals(0f, PanelPlacementMath.clearanceLift(iosMention, listOf(Rect(16f, 200f, 316f, 500f)), 120f), 0f)
        assertEquals(0f, PanelPlacementMath.clearanceLift(iosMention, listOf(Rect.Zero), 120f), 0f)
        assertEquals(0f, PanelPlacementMath.clearanceLift(Rect.Zero, listOf(iosPanel), 120f), 0f)
        // Deux panneaux empilés : au-dessus des deux.
        val upper = Rect(100f, 300f, 400f, 460f)
        val l2 = PanelPlacementMath.clearanceLift(iosMention, listOf(iosPanel, upper), 100f)
        assertEquals(upper.top - 8f, iosMention.translate(0f, -l2).bottom, 0.01f)
        // Un trou assez grand entre deux panneaux : la place la plus proche.
        val high = Rect(100f, 200f, 400f, 300f)
        val l3 = PanelPlacementMath.clearanceLift(iosMention, listOf(iosPanel, high), 100f)
        assertEquals(iosPanel.top - 8f, iosMention.translate(0f, -l3).bottom, 0.01f)
        // Aucune place : elle reste en place.
        assertEquals(0f, PanelPlacementMath.clearanceLift(iosMention, listOf(Rect(16f, 110f, 396f, 810f)), 120f), 0f)
    }

    /** Téléphone 411 × 820 dp, panneau 3 points 300 × 230 dp centré, 84 dp du bas. */
    private val panel = Rect(55.5f, 506f, 355.5f, 736f)

    /** Cadres (sans montée) de la mention (≈ 240 × 16) et du curseur (230 × 48) pour une marge basse [base]. */
    private fun mentionAt(base: Float) = Rect(403f - 240f, 820f - base - 16f, 403f, 820f - base)
    private fun sliderAt(base: Float) = Rect(90.5f, 820f - base - 24f - 48f, 320.5f, 820f - base - 24f)

    @Test fun mentionEtCurseurMontentAuDessusDuPanneau() {
        for (base in listOf(116f, 56f)) { // barre ancrée en bas, ou pas
            val m = mentionAt(base); val s = sliderAt(base)
            // AVANT (aucune montée) : recouverts par le panneau.
            assertTrue("base $base : mention ou curseur sous le panneau", m.overlaps(panel) || s.overlaps(panel))
            val lift = SatelliteUi.overlayLift(m, s, listOf(panel), topLimit = 60f)
            assertTrue(lift > 0f)
            val m2 = m.translate(0f, -lift); val s2 = s.translate(0f, -lift)
            assertFalse("base $base : mention dégagée", m2.overlaps(panel))
            assertFalse("base $base : curseur dégagé", s2.overlaps(panel))
            assertEquals("juste au-dessus du panneau", panel.top - 8f, m2.bottom, 0.01f)
            assertTrue("curseur toujours au-dessus de la mention", s2.bottom <= m2.top)
        }
        // Assistant fermé (pas d'obstacle) : rien ne bouge.
        assertEquals(0f, SatelliteUi.overlayLift(mentionAt(56f), sliderAt(56f), emptyList(), 60f), 0f)
        // Mention dégagée : elle reste à son rang (pas devant le panneau).
        val lifted = SatelliteUi.overlayLift(mentionAt(116f), sliderAt(116f), listOf(panel), 60f)
        assertFalse(SatelliteUi.mentionOnTop(mentionAt(116f), lifted, listOf(panel)))
        // Panneau tiré presque jusqu'en haut : aucune place → elle reste en place
        // mais passe DEVANT (lisible et entière, comme iOS).
        val tall = Rect(55.5f, 110f, 355.5f, 736f)
        val none = SatelliteUi.overlayLift(mentionAt(116f), sliderAt(116f), listOf(tall), 60f)
        assertEquals(0f, none, 0f)
        assertTrue(SatelliteUi.mentionOnTop(mentionAt(116f), none, listOf(tall)))
        // Rien ne la touche : pas devant.
        assertFalse(SatelliteUi.mentionOnTop(mentionAt(116f), 0f, emptyList()))
        // Curseur pas encore mesuré : la mention seule suffit à décider.
        assertTrue(SatelliteUi.overlayLift(mentionAt(116f), Rect.Zero, listOf(panel), 60f) > 0f)
        assertEquals(0f, SatelliteUi.overlayLift(Rect.Zero, null, listOf(panel), 60f), 0f)
    }

    @Test fun mentionEtCurseurBranchesSurLaMontee() {
        val plan = src("ui/PlanScreen.kt")
        assertTrue(plan.contains("if (cal3Session != null) add(cal3PanelAnchorPx.inDp())"))
        assertTrue(plan.contains("if (assignTarget != null) add(assignBarAnchorPx.inDp())"))
        assertTrue(plan.contains("SatelliteUi.overlayLift("))
        assertTrue(plan.contains("SatelliteAttribution(credit, toolbarLayout, toolsPlan, lift = satLift,"))
        assertTrue(plan.contains("onAnchor = { satMentionAnchorPx = it }, onTop = satMentionOnTop)"))
        assertTrue(plan.contains("lift.dp to SatelliteUi.mentionOnTop(mention, lift, satObstacles)"))
        // Le curseur d'opacité flottant a quitté le plan (SPEC-menus-android, décision 8 :
        // l'opacité se règle dans le panneau Position GPS SEULEMENT) ; la mention monte seule.
        assertFalse(plan.contains("satSliderAnchorPx"))
        assertTrue(plan.contains("mention, null, satObstacles,"))
        assertTrue(plan.contains("cal3PanelAnchorPx = it.anchorBounds()"))
        assertTrue(plan.contains("assignBarAnchorPx = it.anchorBounds()"))
        // La mention mesure son cadre AVANT de monter (sinon elle se poursuivrait).
        val att = src("ui/SatelliteUi.kt")
        val i = att.indexOf("Modifier.onGloballyPositioned { onAnchor(it.anchorBounds()) }")
        assertTrue(i > 0 && i < att.indexOf(".offset(y = -lift)", i))
        assertTrue(att.contains(".zIndex(if (onTop) 1f else 0f)"))
    }

    // ------------------------------------------------ 4. localisation refusée

    @Test fun refusDeduitDeLEtatSysteme() {
        // Accordée : jamais refusée.
        assertFalse(LocationPermissionRule.denied(granted = true, askedBefore = true, rationale = true))
        // Jamais demandée : pas encore refusée.
        assertFalse(LocationPermissionRule.denied(granted = false, askedBefore = false, rationale = false))
        // Refusée lors d'une session PRÉCÉDENTE (ou retirée dans les Réglages) :
        // refusée dès l'ouverture, sans attendre une nouvelle demande.
        assertTrue(LocationPermissionRule.denied(granted = false, askedBefore = true, rationale = false))
        assertTrue(LocationPermissionRule.denied(granted = false, askedBefore = false, rationale = true))
    }

    @Test fun refusLuAuDemarrageEtAuRetourDesReglages() {
        val loc = src("ui/UserLocation.kt")
        assertTrue("état système dès la création",
            loc.contains("UserLocationFeed().also { it.denied = locationDeniedBySystem(context, granted) }"))
        assertTrue(loc.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue("demande retenue", loc.contains("LocationPermissionStore.markAsked(context)"))
        assertFalse("plus de refus « de cette visite » seulement", loc.contains("feed.denied = !ok"))
        val plan = src("ui/PlanScreen.kt")
        // Ligne d'état du panneau Position GPS (tranche B des menus), dans les deux vues.
        assertTrue(plan.contains("GeoPanelContent.status(calibration, locFeed.denied)"))
        assertTrue(src("ui/Scene3DScreen.kt").contains("locFeed3D.denied)"))
        val st = src("ui/GeoPositionPanel.kt")
        assertTrue(st.contains("val st = GeoCalibrationStatus.of(calibration, locationDenied)"))
    }

    @Test fun texteDuRefusIdentiqueAIos() {
        // Clé iOS : « Localisation refusée — Réglages > Confidentialité ».
        assertEquals("Localisation refusée — Réglages > Confidentialité",
            TestRes.FR.res(R.string.calib_status_denied, emptyList()))
        assertEquals("Location denied — Settings > Privacy",
            TestRes.EN.res(R.string.calib_status_denied, emptyList()))
    }

    // ----------------------------- 5. bouton principal après un point mesuré

    @Test fun calculerAuTroisiemePointAuControleEtEnRefaisant() {
        // SPEC §2 (5 bis) : « Calculer » ajuste les 4 points. Android suit la spec ;
        // iOS (ThreePointCalibrationView l. 638, `i == 2`) affiche « Point suivant » au contrôle.
        assertEquals(CalText(R.string.cal3_next_point), ThreePointDisplay.measuredPrimary(0, false))
        assertEquals(CalText(R.string.cal3_next_point), ThreePointDisplay.measuredPrimary(1, false))
        assertEquals(CalText(R.string.cal3_compute), ThreePointDisplay.measuredPrimary(2, false))
        assertEquals(CalText(R.string.cal3_compute), ThreePointDisplay.measuredPrimary(3, false))
        assertEquals(CalText(R.string.cal3_compute), ThreePointDisplay.measuredPrimary(0, true))
    }

    // ------------------------------- 6. armer un autre mode ferme l'assistant

    private val open = PlanTouchModes(threePoint = true)

    @Test fun armerChaqueOutilFermeLAssistant() {
        for (tool in PlanTouchTool.entries) {
            val n = PlanModeRules.toggle(open, tool)
            assertFalse("$tool armé : assistant fermé", n.threePoint)
        }
        assertTrue(PlanModeRules.toggle(open, PlanTouchTool.RECT).rect)
        assertTrue(PlanModeRules.toggle(open, PlanTouchTool.MASK).mask)
        assertTrue(PlanModeRules.toggle(open, PlanTouchTool.SOLO).solo)
        assertTrue(PlanModeRules.toggle(open, PlanTouchTool.MEASURE).measure)
        assertTrue(PlanModeRules.toggle(open, PlanTouchTool.ANCHOR_TAP).anchorTap)
    }

    @Test fun ouvrirLAssistantDesarmeTout() {
        // iOS startThreePointCalibration : cadre, masquage, solo, mesure et
        // « je suis ici » désarmés, seul l'assistant reste.
        val m = PlanModeRules.startThreePoint()
        assertTrue(m.threePoint)
        assertFalse(m.rect || m.mask || m.solo || m.measure || m.anchorTap)
    }

    @Test fun exclusionsInchangees() {
        // Désarmer ne touche qu'à l'outil lui-même.
        val on = PlanTouchModes(rect = true, mask = true, solo = true, measure = true, anchorTap = true)
        assertEquals(on.copy(rect = false), PlanModeRules.toggle(on, PlanTouchTool.RECT))
        assertEquals(on.copy(mask = false), PlanModeRules.toggle(on, PlanTouchTool.MASK))
        assertEquals(on.copy(solo = false), PlanModeRules.toggle(on, PlanTouchTool.SOLO))
        assertEquals(on.copy(measure = false), PlanModeRules.toggle(on, PlanTouchTool.MEASURE))
        assertEquals(on.copy(anchorTap = false), PlanModeRules.toggle(on, PlanTouchTool.ANCHOR_TAP))
        // Armer : mêmes exclusions qu'avant la mise en règle.
        val off = PlanTouchModes(rect = true, mask = true, solo = true, measure = true, anchorTap = true)
        assertEquals(PlanTouchModes(rect = true, mask = true, solo = true, anchorTap = true),
            PlanModeRules.toggle(off.copy(rect = false), PlanTouchTool.RECT))
        assertEquals(PlanTouchModes(rect = true, mask = true, anchorTap = true),
            PlanModeRules.toggle(off.copy(mask = false), PlanTouchTool.MASK))
        assertEquals(PlanTouchModes(rect = true, solo = true, anchorTap = true),
            PlanModeRules.toggle(off.copy(solo = false), PlanTouchTool.SOLO))
        assertEquals(PlanTouchModes(measure = true),
            PlanModeRules.toggle(off.copy(measure = false), PlanTouchTool.MEASURE))
        assertEquals(PlanTouchModes(rect = true, mask = true, solo = true, anchorTap = true),
            PlanModeRules.toggle(off.copy(anchorTap = false), PlanTouchTool.ANCHOR_TAP))
    }

    @Test fun chaqueOutilPasseParLaRegle() {
        val plan = src("ui/PlanScreen.kt")
        val expected = mapOf(
            "RECT" to "PlanTouchTool.RECT", "MASK" to "PlanTouchTool.MASK", "SOLO" to "PlanTouchTool.SOLO",
            "MEASURE" to "PlanTouchTool.MEASURE", "CALIBRATE" to "PlanTouchTool.ANCHOR_TAP"
        )
        for ((id, tool) in expected) {
            val spec = toolSpec(plan, id)
            assertTrue("$id : passe par toggleTouchTool($tool)", spec.contains("toggleTouchTool($tool)"))
            // Plus aucune pose directe qui contournerait la fermeture de l'assistant.
            for (direct in listOf("rectMode = ", "maskMode = ", "soloMode = ", "measureMode = ", "calibrating = "))
                assertFalse("$id : pose directe « $direct »", spec.contains(direct))
        }
        // La pose de l'état ferme l'assistant quand la règle le dit.
        val apply = block(plan, "fun applyTouchModes(n: PlanTouchModes)")
        assertTrue(apply.contains("if (!n.threePoint && (cal3Session != null || cal3Starting)) closeThreePoint()"))
        assertTrue(apply.contains("rectMode = n.rect; maskMode = n.mask; soloMode = n.solo"))
        assertTrue(apply.contains("measureMode = n.measure; calibrating = n.anchorTap"))
        val toggle = plan.substringAfter("fun toggleTouchTool(tool: PlanTouchTool): PlanTouchModes =").substringBefore("\n\n")
        assertTrue(toggle.contains("PlanModeRules.toggle(currentTouchModes(), tool).also { applyTouchModes(it) }"))
        assertTrue(block(plan, "fun startThreePoint()").contains("applyTouchModes(PlanModeRules.startThreePoint())"))
        val current = plan.substringAfter("fun currentTouchModes() = PlanTouchModes(").substringBefore(")\n")
        assertTrue(current.contains("anchorTap = calibrating, threePoint = cal3Session != null || cal3Starting"))
    }
}
