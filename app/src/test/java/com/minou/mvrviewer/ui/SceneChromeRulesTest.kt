package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * Habillage commun des deux vues (SPEC-menus-android §3.1, §3.3–3.5, §3.14,
 * tranche C) : pastille « Vue », sélection (toucher / cadre / multi, Q2), cadrage
 * du plan sous la pastille, pile bas-droite, pastille de synchro du plan, bloc
 * d'état 3D, bandeaux de mode. Unités : dp (densité 1), comme les points iOS.
 */
class SceneChromeRulesTest {

    // ------------------------------------------------------------ pastille Vue

    @Test fun pastille3DSixPointsDeVuePuisVuePlan() {
        val s = ViewPillModel.sections(ProjectSurface.SCENE_3D, CameraViewpoint.FRONT)
        assertEquals(2, s.size)
        assertEquals("Point de vue", TestRes.FR.res(s[0].titleRes!!, emptyList()))
        assertEquals(listOf("Perspective", "Dessus", "Face", "Arrière", "Gauche", "Droite"),
            s[0].entries.map { TestRes.FR.res(it.labelRes, emptyList()) })
        assertEquals(listOf("Perspective", "Top", "Front", "Back", "Left", "Right"),
            s[0].entries.map { TestRes.EN.res(it.labelRes, emptyList()) })
        // Coche sur le dernier point de vue appliqué.
        assertEquals(listOf(CameraViewpoint.FRONT), s[0].entries.filter { it.checked }.map { it.viewpoint })
        assertTrue(s[0].entries.all { it.action == ViewPillAction.VIEWPOINT })
        assertNull(s[1].titleRes)
        assertEquals(listOf(ViewPillAction.SHOW_PLAN), s[1].entries.map { it.action })
        assertEquals("Vue plan 2D", TestRes.FR.res(s[1].entries.single().labelRes, emptyList()))
        assertEquals("2D plan view", TestRes.EN.res(s[1].entries.single().labelRes, emptyList()))
        // Libellé : « 3D · Face » ; « Perspective » à l'ouverture.
        assertEquals("3D · Face", TestRes.FR.res(ViewPillModel.labelFormatRes(ProjectSurface.SCENE_3D),
            listOf(TestRes.FR.res(ViewPillModel.valueRes(ProjectSurface.SCENE_3D, CameraViewpoint.FRONT), emptyList()))))
        assertEquals("3D · Perspective", TestRes.EN.res(ViewPillModel.labelFormatRes(ProjectSurface.SCENE_3D),
            listOf(TestRes.EN.res(ViewPillModel.valueRes(ProjectSurface.SCENE_3D, CameraViewpoint.PERSPECTIVE), emptyList()))))
    }

    @Test fun pastillePlanDessusPuisVue3D() {
        val s = ViewPillModel.sections(ProjectSurface.PLAN, CameraViewpoint.PERSPECTIVE)
        assertEquals("Projection 2D", TestRes.FR.res(s[0].titleRes!!, emptyList()))
        assertEquals("2D projection", TestRes.EN.res(s[0].titleRes!!, emptyList()))
        assertEquals(listOf(ViewPillAction.PROJECTION_TOP), s[0].entries.map { it.action })
        assertTrue(s[0].entries.single().checked)
        assertEquals(listOf(ViewPillAction.SHOW_3D), s[1].entries.map { it.action })
        assertEquals("Vue 3D", TestRes.FR.res(s[1].entries.single().labelRes, emptyList()))
        assertEquals("Plan · Dessus", TestRes.FR.res(ViewPillModel.labelFormatRes(ProjectSurface.PLAN),
            listOf(TestRes.FR.res(ViewPillModel.valueRes(ProjectSurface.PLAN, CameraViewpoint.LEFT), emptyList()))))
        assertEquals("Plan · Top", TestRes.EN.res(ViewPillModel.labelFormatRes(ProjectSurface.PLAN),
            listOf(TestRes.EN.res(ViewPillModel.valueRes(ProjectSurface.PLAN, CameraViewpoint.LEFT), emptyList()))))
        assertEquals("Vue", TestRes.FR.get("pill_view")); assertEquals("View", TestRes.EN.get("pill_view"))
    }

    @Test fun pointsDeVueMemeCoteQuIos() {
        val r = 10f
        // Face = +Z Filament (−Y MVR) ; Droite = +X (ex-« Côté ») ; Arrière / Gauche = opposés.
        assertEquals(Triple(0f, 0f, r), CameraViewpoint.FRONT.eyeOffset(r))
        assertEquals(Triple(0f, 0f, -r), CameraViewpoint.BACK.eyeOffset(r))
        assertEquals(Triple(r, 0f, 0f), CameraViewpoint.RIGHT.eyeOffset(r))
        assertEquals(Triple(-r, 0f, 0f), CameraViewpoint.LEFT.eyeOffset(r))
        assertEquals(r, CameraViewpoint.TOP.eyeOffset(r).second)
        // Perspective = ex-« Isométrique » (0,7·r sur les trois axes).
        assertEquals(Triple(7f, 7f, 7f), CameraViewpoint.PERSPECTIVE.eyeOffset(r))
        assertEquals(CameraViewpoint.PERSPECTIVE, CameraViewpoint.entries.first())
        // Recul : les vues de côté reculent d'autant que la vue est haute (portrait),
        // marge ×1,35, borné à ×3,5 ; Dessus / Perspective et le paysage gardent la base.
        assertEquals(2f * 2400f / 1080f * 1.35f, CameraViewpoint.LEFT.recoil(2f, 1080f, 2400f), 1e-4f)
        assertEquals(7f, CameraViewpoint.FRONT.recoil(2f, 400f, 2400f), 1e-4f)
        assertEquals(2f, CameraViewpoint.FRONT.recoil(2f, 1000f, 1000f), 0f)
        assertEquals(2f, CameraViewpoint.RIGHT.recoil(2f, 2400f, 1080f), 0f)
        assertEquals(2f, CameraViewpoint.TOP.recoil(2f, 1080f, 2400f), 0f)
        assertEquals(2f, CameraViewpoint.PERSPECTIVE.recoil(2f, 1080f, 2400f), 0f)
        assertEquals(2f, CameraViewpoint.BACK.recoil(2f, 0f, 0f), 0f)
    }

    // ---------------------------------------------------------------- sélection

    @Test fun toucherRemplaceOuAjouteSelonMulti() {
        val none = SelectionModes()
        // Toucher = remplace (Q2) ; vide = désélectionne.
        assertEquals(listOf(4), SelectionRules.tap(listOf(1, 2), 4, none))
        assertEquals(listOf(2), SelectionRules.tap(listOf(2), 2, none))
        assertEquals(emptyList<Int>(), SelectionRules.tap(listOf(1, 2), null, none))
        // Multi : ajoute / retire ; le vide ne perd pas la sélection en cours.
        val multi = SelectionModes(multi = true)
        assertEquals(listOf(1, 2, 4), SelectionRules.tap(listOf(1, 2), 4, multi))
        assertEquals(listOf(1), SelectionRules.tap(listOf(1, 2), 2, multi))
        assertEquals(listOf(1, 2), SelectionRules.tap(listOf(1, 2), null, multi))
        // Cadre armé (force le multi) : même chose.
        assertEquals(listOf(1, 2, 4), SelectionRules.tap(listOf(1, 2), 4, SelectionModes(rect = true)))
    }

    @Test fun cadreForceLeMultiEtAjoute() {
        val armed = SelectionRules.toggleRect(SelectionModes())
        assertEquals(SelectionModes(multi = true, rect = true), armed.modes)
        assertFalse(armed.clearSelection)
        // Désarmer le cadre laisse le multi.
        assertEquals(SelectionModes(multi = true, rect = false), SelectionRules.toggleRect(armed.modes).modes)
        // Le cadre AJOUTE (sans doublon, dans l'ordre : il pilote l'adressage séquentiel).
        assertEquals(listOf(3, 1, 5, 2), SelectionRules.frame(listOf(3, 1), listOf(1, 5, 2, 5)))
        assertEquals(listOf(7), SelectionRules.frame(emptyList(), listOf(7)))
    }

    @Test fun couperLeMultiVideLaSelectionEtCoupeLeCadre() {
        val on = SelectionRules.toggleMulti(SelectionModes())
        assertEquals(SelectionModes(multi = true), on.modes)
        assertFalse(on.clearSelection)
        val off = SelectionRules.toggleMulti(SelectionModes(multi = true, rect = true))
        assertEquals(SelectionModes(), off.modes)
        assertTrue(off.clearSelection)
        // Bouton multi allumé par le seul cadre : le couper coupe aussi le cadre.
        val fromRect = SelectionRules.toggleMulti(SelectionModes(rect = true))
        assertEquals(SelectionModes(), fromRect.modes)
        assertTrue(fromRect.clearSelection)
    }

    private fun src(name: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/ui/$name"), File("app/src/main/java/com/minou/mvrviewer/ui/$name"))
            .first { it.isFile }.readText()

    @Test fun lesDeuxVuesPassentParLaRegle() {
        val v3 = src("Scene3DScreen.kt"); val plan = src("PlanScreen.kt")
        for (v in listOf(v3, plan)) {
            assertTrue(v.contains("SelectionRules.tap(selected.toList(), best.takeIf { it >= 0 }, selectionModes())"))
            assertTrue(v.contains("SelectionRules.toggleMulti(selectionModes())"))
        }
        assertTrue(v3.contains("SelectionRules.toggleRect(selectionModes())"))
        // Le plan arme le cadre par la règle des modes de toucher, puis force le multi.
        assertTrue(plan.contains("if (toggleTouchTool(PlanTouchTool.RECT).rect) multiMode = true"))
        // Le cadre du plan AJOUTE désormais (il remplaçait).
        assertTrue(plan.contains("SelectionRules.frame(selected.toList(), enclosed)"))
        // 3D : l'ancien « toucher = ajoute toujours » a disparu.
        assertFalse(v3.contains("else if (!selected.remove(best)) selected.add(best)"))
    }

    // ------------------------------------------------------- cadrage sous la pastille

    /** Bord haut et bas du contenu (même transformée que PlanScreen.toScreen). */
    private fun contentY(w: Float, h: Float, sx: Float, sy: Float, scale: Float, dy: Float): Pair<Float, Float> {
        val bs = min(w / sx, h / sy) * 0.9f * scale
        val c = h / 2f + dy
        return (c - sy * bs / 2f) to (c + sy * bs / 2f)
    }

    @Test fun affectationEnPaysageCommenceSousLaPastille() {
        // Relevés iOS transposés : plan de 346 dp, pastille jusqu'à 69 dp, barre de 146 dp.
        val (s, dy) = PlanFitRules.underPill(914f, 346f, 30_000f, 20_000f, 69f, 146f, 24f, 40f)
        val (top, bottom) = contentY(914f, 346f, 30_000f, 20_000f, s, dy)
        assertTrue("sous la pastille + 24 dp : $top", top >= 69f + 24f - 0.01f)
        // Marge d'iOS sous le contenu : 20 dp au-dessus de la barre (le quart de la
        // hauteur restante, 40 dp de marge totale au moins).
        assertEquals(346f - 146f - 20f, bottom, 0.01f)
        assertEquals(-36.5f, dy, 0.01f)
        // AVANT (règle d'origine seule) : la rangée du haut passait sous la pastille.
        val (s0, dy0) = CablingAssignBarPlacement.fitAbove(914f, 346f, 30_000f, 20_000f, 146f)
        assertTrue(contentY(914f, 346f, 30_000f, 20_000f, s0, dy0).first < 69f)
    }

    @Test fun regleDOrigineSansPastilleOuSiElleNeGenePas() {
        // Sans pastille mesurée : exactement l'ancien cadrage (fitAbove, ou la vue d'ouverture).
        assertEquals(CablingAssignBarPlacement.fitAbove(914f, 346f, 30_000f, 20_000f, 146f),
            PlanFitRules.underPill(914f, 346f, 30_000f, 20_000f, 0f, 146f, 24f, 40f))
        assertEquals(1f to 0f, PlanFitRules.underPill(411f, 760f, 30_000f, 20_000f, 0f, 0f, 24f, 40f))
        // Portrait sans barre : le contenu passe déjà sous la pastille → rien ne bouge.
        assertEquals(1f to 0f, PlanFitRules.underPill(411f, 760f, 30_000f, 20_000f, 52f, 0f, 24f, 40f))
        // Données invalides : la vue d'ouverture.
        assertEquals(1f to 0f, PlanFitRules.underPill(Float.NaN, 760f, 30_000f, 20_000f, 52f, 0f, 24f, 40f))
        assertEquals(1f to 0f, PlanFitRules.underPill(411f, 760f, 0f, 20_000f, 52f, 0f, 24f, 40f))
    }

    @Test fun paysageSansBarreLaPastilleNeCachePlusLaRangeeDuHaut() {
        // Ouverture en paysage (360 dp) : le contenu montait à 18 dp, sous la pastille (92 dp).
        assertTrue(contentY(914f, 360f, 30_000f, 20_000f, 1f, 0f).first < 92f)
        val (s, dy) = PlanFitRules.underPill(914f, 360f, 30_000f, 20_000f, 92f, 0f, 24f, 40f)
        val (top, bottom) = contentY(914f, 360f, 30_000f, 20_000f, s, dy)
        assertTrue(top >= 116f - 0.01f)
        // Sans barre : 50 dp sous le contenu (les étiquettes du bas ne sont plus coupées
        // par le bord — constat émulateur).
        assertEquals(310f, bottom, 0.01f)
        // Densité : les marges sont en dp.
        val (s2, dy2) = PlanFitRules.underPill(2.625f * 914f, 2.625f * 360f, 30_000f, 20_000f,
            2.625f * 92f, 0f, 2.625f * 24f, 2.625f * 40f, dp = 2.625f)
        assertEquals(s, s2, 1e-4f)
        assertEquals(2.625f * dy, dy2, 0.01f)
    }

    @Test fun ecranMinusculeGardeDeLaPlacePourLeContenu() {
        val (s, dy) = PlanFitRules.underPill(400f, 200f, 30_000f, 20_000f, 120f, 120f, 24f, 40f)
        val (top, bottom) = contentY(400f, 200f, 30_000f, 20_000f, s, dy)
        assertTrue(s > 0f)
        assertTrue("la barre du bas reste dégagée", bottom <= 200f - 120f + 0.01f)
        assertTrue(bottom > top)
    }

    @Test fun troisPointsAussiSousLaPastille() {
        // Le cadrage de l'assistant passe par la même règle (sans pastille : inchangé).
        val withPill = ThreePointPlanFit.refit(914f, 360f, 30_000f, 20_000f, 0f, 360f, 192f,
            fittedReserve = 0f, untouched = true, sizeChanged = false, slack = 4f,
            topReserve = 92f, topGap = 24f, minContent = 40f)!!
        val (top, bottom) = contentY(914f, 360f, 30_000f, 20_000f, withPill.scale, withPill.dy)
        assertTrue(top >= 116f - 0.01f)
        assertTrue(bottom <= 192f + 0.01f)
        val plain = ThreePointPlanFit.refit(914f, 360f, 30_000f, 20_000f, 0f, 360f, 192f,
            fittedReserve = 0f, untouched = true, sizeChanged = false, slack = 4f)!!
        assertEquals(CablingAssignBarPlacement.fitAbove(914f, 360f, 30_000f, 20_000f, 168f), plain.scale to plain.dy)
    }

    @Test fun cadragesBranchesSurLaPastille() {
        val plan = src("PlanScreen.kt")
        assertTrue(plan.contains("PlanFitRules.underPill("))
        assertTrue(plan.contains("topReserve = pillReservePx(),"))
        assertTrue(plan.contains("LaunchedEffect(assignTarget != null, assignBarTopPx, planBoundsPx, canvas, viewPillPx.bottom)"))
        assertTrue(plan.contains("val (s, dy) = fitUnderPill(bottomReservePx())"))
    }

    // ------------------------------------------------------------ pile bas-droite

    /** Téléphone 411 × 820 dp : pile (Rechercher 48 + 12 + Recadrer 48) à 12 dp des bords. */
    private val stack = Rect(351f, 820f - 12f - 108f, 399f, 808f)

    @Test fun pileMonteAuDessusDeLaFicheDeLaBarreEtDeLaMention() {
        // Fiche / bannière en bas (pleine largeur) : la pile passe juste au-dessus.
        val card = Rect(16f, 740f, 395f, 804f)
        val l1 = PanelPlacementMath.clearanceLift(stack, listOf(card), 60f)
        assertEquals(card.top - 8f, stack.bottom - l1, 0.01f)
        // Barre d'affectation (centrée, 300 dp) qui touche sa colonne.
        val bar = Rect(55.5f, 590f, 355.5f, 736f)
        val l2 = PanelPlacementMath.clearanceLift(stack, listOf(bar), 60f)
        assertEquals(bar.top - 8f, stack.bottom - l2, 0.01f)
        // Mention IGN au même coin : jamais recouverte.
        val mention = Rect(163f, 792f, 403f, 808f)
        val l3 = PanelPlacementMath.clearanceLift(stack, listOf(mention), 60f)
        assertFalse(stack.translate(0f, -l3).overlaps(mention))
        // Hors de sa colonne : rien ne bouge.
        assertEquals(0f, PanelPlacementMath.clearanceLift(stack, listOf(Rect(16f, 740f, 300f, 804f)), 60f), 0f)
        // Jamais plus haut que la rangée du haut : sans place, elle reste.
        assertEquals(0f, PanelPlacementMath.clearanceLift(stack, listOf(Rect(16f, 40f, 400f, 808f)), 60f), 0f)
    }

    @Test fun pileBrancheeDansLesDeuxVues() {
        val v3 = src("Scene3DScreen.kt"); val plan = src("PlanScreen.kt"); val chrome = src("SceneChrome.kt")
        for (v in listOf(v3, plan)) {
            assertTrue(v.contains("SearchReframeStack("))
            assertTrue(v.contains("showReframe = SceneTopRowRules.showsReframe(toolbarLayout)"))
            // Plus de champ de recherche permanent en haut.
            assertFalse(v.contains("placeholder = { Text(stringResource(R.string.search_fixture_id_hint)"))
        }
        // La montée est mesurée (cadre SANS montée), jamais une constante.
        assertTrue(chrome.contains("PanelPlacementMath.clearanceLift(anchor, obstacles, topLimitPx"))
        val i = chrome.indexOf(".onGloballyPositioned { anchor = it.anchorBounds() }")
        assertTrue(i > 0 && i < chrome.indexOf(".then(liftOffset)", i))
        // Plan : la mention est lue à sa place AFFICHÉE (sa montée ne dépend pas de la pile).
        assertTrue(plan.contains("add(satMentionAnchorPx.translate(0f, -satLift.value * labelDensity.density))"))
        // Les panneaux qui étaient au coin bas-droit passent en bas-gauche (nouvelles clés).
        assertTrue(v3.contains("id = PanelLayoutStore.PanelID.IMPORTED_MODELS_3D_LEADING"))
        assertTrue(plan.contains("id = PanelLayoutStore.PanelID.EXPORT_PLAN_LEADING"))
        val keys = PanelLayoutStore.PanelID.entries.associate { it.name to it.key }
        assertEquals("importedModels3DLeading", keys["IMPORTED_MODELS_3D_LEADING"])
        assertEquals("exportPlanLeading", keys["EXPORT_PLAN_LEADING"])
        assertEquals("importedModels3D", keys["IMPORTED_MODELS_3D"])
        assertEquals("exportPlan", keys["EXPORT_PLAN"])
        assertEquals(PanelLayoutStore.PanelID.entries.size, keys.values.toSet().size)
    }

    // --------------------------------------------- pastille de synchro du plan

    @Test fun synchroDuPlanADroiteSousLaRangeeJamaisSousLaPastille() {
        val pill = Rect(130f, 58f, 280f, 92f)
        // Assez de place à droite de la pastille : sous la rangée.
        assertEquals(58f, PlanSyncBadgeRules.top(52f, pill, 100f, 411f, 8f, 6f), 0f)
        // Texte long : elle chevaucherait la pastille → sous celle-ci, toujours à droite.
        assertEquals(98f, PlanSyncBadgeRules.top(52f, pill, 150f, 411f, 8f, 6f), 0f)
        // Pas encore mesurée : sous la rangée.
        assertEquals(58f, PlanSyncBadgeRules.top(52f, pill, 0f, 411f, 8f, 6f), 0f)
        assertEquals(58f, PlanSyncBadgeRules.top(52f, Rect.Zero, 150f, 411f, 8f, 6f), 0f)
    }

    @Test fun synchroQuitteLeCoinBasDroit() {
        val scene = src("SceneScreen.kt")
        assertFalse(scene.contains("Modifier.align(Alignment.BottomEnd).padding(12.dp)) {\n                SyncStatusBadge(sync)"))
        assertTrue(scene.contains("val syncBadge: (@Composable () -> Unit)? = sync?.let { s -> { SyncStatusBadge(s) } }"))
        // Bannière « nouvelle version » SOUS la rangée du haut.
        assertTrue(scene.contains("Box(Modifier.align(Alignment.TopCenter).padding(top = 54.dp)) {"))
        assertTrue(src("PlanScreen.kt").contains("PlanSyncBadgeRules.top("))
        assertTrue(src("Scene3DScreen.kt").contains("syncBadge = syncBadge,"))
    }

    // ------------------------------------------------------------- bloc d'état

    @Test fun blocDEtatChaquePastilleSeulementSiElleADuSens() {
        assertEquals(listOf(StatusLine.Loading(7, 40)),
            StatusBlockRules.lines(true, 7, 40, placed = 12, truncated = false, hiddenClasses = 0, gdtfLoading = false))
        assertEquals(listOf(StatusLine.Starting),
            StatusBlockRules.lines(false, null, 0, placed = null, truncated = false, hiddenClasses = 0, gdtfLoading = false))
        assertEquals(listOf(StatusLine.Objects(1200, true), StatusLine.HiddenClasses(2), StatusLine.GdtfLoading),
            StatusBlockRules.lines(false, null, 0, placed = 1200, truncated = true, hiddenClasses = 2, gdtfLoading = true))
        // Textes : pluriels fr / en, « (tronqué) » collé au nombre.
        assertEquals("1 objet", TestRes.FR.plural("status_objects", 1, 1))
        assertEquals("12 objets", TestRes.FR.plural("status_objects", 12, 12))
        assertEquals("1 object", TestRes.EN.plural("status_objects", 1, 1))
        assertEquals("12 objects", TestRes.EN.plural("status_objects", 12, 12))
        assertEquals("1 classe masquée", TestRes.FR.plural("status_hidden_classes", 1, 1))
        assertEquals("3 classes masquées", TestRes.FR.plural("status_hidden_classes", 3, 3))
        assertEquals("1 hidden class", TestRes.EN.plural("status_hidden_classes", 1, 1))
        assertEquals("3 hidden classes", TestRes.EN.plural("status_hidden_classes", 3, 3))
        assertEquals(" (tronqué)", TestRes.FR.get("status_truncated"))
        assertEquals("Modèles GDTF en cours…", TestRes.FR.get("status_gdtf_loading"))
        assertEquals("Loading GDTF models…", TestRes.EN.get("status_gdtf_loading"))
        // « proj. GDTF » retiré du bloc (§8 D14).
        assertFalse(src("Scene3DScreen.kt").contains("status_gdtf_suffix_fmt"))
        assertTrue(src("Scene3DScreen.kt").contains("StatusBlock("))
    }

    // ------------------------------------------------------- bandeaux de mode

    @Test fun bandeauxDeMasquageEtDIsolement() {
        assertEquals(PlanModeBanner.HIDE_TAP, PlanModeBanner.of(mask = true, solo = false, rect = false))
        assertEquals(PlanModeBanner.HIDE_BOX, PlanModeBanner.of(mask = true, solo = false, rect = true))
        assertEquals(PlanModeBanner.ISOLATE_TAP, PlanModeBanner.of(mask = false, solo = true, rect = false))
        assertEquals(PlanModeBanner.ISOLATE_BOX, PlanModeBanner.of(mask = false, solo = true, rect = true))
        assertNull(PlanModeBanner.of(mask = false, solo = false, rect = true))
        val fr = PlanModeBanner.entries.map { TestRes.FR.res(it.textRes, emptyList()) }
        assertEquals(listOf("Touchez un élément à masquer", "Tracez un cadre à masquer",
            "Touchez un élément à isoler", "Tracez un cadre à isoler"), fr)
        val en = PlanModeBanner.entries.map { TestRes.EN.res(it.textRes, emptyList()) }
        assertEquals(listOf("Tap an item to hide", "Drag a box to hide", "Tap an item to isolate", "Drag a box to isolate"), en)
        // L'icône de tête quitte le mode par le MÊME chemin que l'outil.
        val plan = src("PlanScreen.kt")
        assertTrue(plan.contains("onExit = { if (maskMode) planTool(ToolId.MASK) else if (soloMode) planTool(ToolId.SOLO) }"))
    }

    // ------------------------------------------------------------ rangée du haut

    @Test fun rangeeDuHautCommuneEtTextes() {
        val v3 = src("Scene3DScreen.kt"); val plan = src("PlanScreen.kt")
        for (v in listOf(v3, plan)) {
            assertTrue(v.contains("SceneTopRow("))
            assertTrue(v.contains("ViewPill("))
            assertFalse(v.contains("TopAppBar("))
            assertFalse(v.contains("SceneOptionsMenu("))
        }
        // 3D : ← = Fermer le projet ; plan : ← = « Terminé » (décision 2, §8 D19).
        assertTrue(v3.contains("backDescription = stringResource(R.string.menu_close_project)"))
        assertTrue(plan.contains("backDescription = stringResource(R.string.common_done)"))
        // Plus de pastille « Plan · N projecteur(s) » ni de menu « Points de vue ».
        assertFalse(plan.contains("plan_title_fmt"))
        assertFalse(v3.contains("cam_viewpoints"))
        val expected = mapOf(
            "perm_multi_select" to ("Multi-sélection" to "Multi-select"),
            // TalkBack du bouton permanent « cadre » (= titre de l'outil `rectSelect`, valeur iOS).
            "tool_rect_select" to ("Sélection par cadre" to "Rectangle selection"),
            "search_fixture_cd" to ("Rechercher un projecteur par N°" to "Search for a fixture by number"),
            "reframe_cd" to ("Recadrer la vue" to "Reframe the view"),
            "search_fixture_id_hint" to ("N° de projecteur" to "Fixture number"),
            "menu_more_options" to ("Plus d'options" to "More options")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, TestRes.FR.get(k))
            assertEquals("en $k", v.second, TestRes.EN.get(k))
        }
        assertEquals("Aucun projecteur nº 999", TestRes.FR.get("search_none_fmt", "999"))
        assertEquals("No fixture #999", TestRes.EN.get("search_none_fmt", "999"))
    }
}
