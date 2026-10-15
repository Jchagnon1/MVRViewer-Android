package com.minou.mvrviewer.ui

import com.minou.mvrviewer.sync.CablingAssignment
import com.minou.mvrviewer.sync.CablingSettings
import com.minou.mvrviewer.sync.Distributor
import com.minou.mvrviewer.sync.DistributorKind
import com.minou.mvrviewer.sync.DmxCablingAssignment
import com.minou.mvrviewer.sync.DmxCablingDTO
import com.minou.mvrviewer.sync.DmxDistributor
import com.minou.mvrviewer.sync.DmxDistributorKind
import com.minou.mvrviewer.sync.PowerCablingDTO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Câblage sur le plan : ce qui est PROPRE À L'ÉCRAN Android (SPEC-cablage-plan,
 * guide de portage §2.3), testé en JVM — pas d'émulateur ici.
 *
 * 1. Les DÉCISIONS de l'interface sont des fonctions pures : lignes de la liste
 *    (clés des lignes repères, ordre d'affichage par phase), position lue sur les
 *    éléments visibles d'une `LazyColumn`, coloration forcée par la famille,
 *    cadrage au-dessus de la barre, libellé figé de la cible, retours haptiques,
 *    rétrécissement du compte, libellés courts des actions.
 * 2. Le parcours complet « voie supprimée pendant l'affectation » est rejoué sur
 *    les VRAIS états (rien n'est écrit, aucune synchro, la barre le dit).
 * 3. Le BRANCHEMENT dans les écrans (Compose) n'est pas exécutable en JVM : des
 *    gardes lisent les sources et échouent si un écran ne passe plus par ces
 *    fonctions.
 */
class CablingPlanScreenTest {

    @Before fun pro() { ProAccess.forceForTests(true) }
    @After fun resetPro() { ProAccess.forceForTests(null) }

    private val SOCA = CablingAssignTarget.Kind.SOCA
    private val DMX = CablingAssignTarget.Kind.DMX

    private fun soca(id: String, name: String, phases: List<Int> = listOf(1, 1, 2, 2, 3, 3), color: Int = 0xFFE53935.toInt()) =
        Distributor(id, name, DistributorKind.SOCA, color, phases)
    private fun solo(id: String, name: String) = Distributor(id, name, DistributorKind.SOLO, 0xFF1E88E5.toInt(), listOf(1))
    private fun multi(id: String, name: String, cores: Int) =
        DmxDistributor(id, name, DmxDistributorKind.MULTI, 0xFF8E24AA.toInt(), cores)
    private fun simple(id: String, name: String) = DmxDistributor(id, name, DmxDistributorKind.SIMPLE, 0xFF00ACC1.toInt(), 1)

    // ------------------------------------------------- lignes de la liste

    @Test fun listeElectriqueGroupeeParPhaseMaisVoiesParNumero() {
        // Phases [3,2,1,3,2,1] : l'écran affiche 3, 6, 2, 5, 1, 4 (SPEC §9) ; ◀ ▶ suivent 1…6.
        val a = soca("A", "Soca A", listOf(3, 2, 1, 3, 2, 1))
        val rows = CablingListRows.power(listOf(a, solo("L", "Ligne A")))
        assertEquals(listOf(
            "hdr#A", "phase#A#1", "power#A#3", "power#A#6", "phase#A#2", "power#A#2", "power#A#5",
            "phase#A#3", "power#A#1", "power#A#4",
            "hdr#L", "phase#L#1", "power#L#1"
        ), rows.map { it.id })
        // Seule la DERNIÈRE ligne affichée de chaque distributeur ferme sa carte.
        assertEquals(listOf("power#A#4", "power#L#1"),
            rows.filterIsInstance<CablingListRow.Voie>().filter { it.last }.map { it.id })
        // ◀ ▶ restent dans l'ordre des numéros.
        val dto = PowerCablingDTO(CablingSettings(), listOf(a), emptyList())
        assertEquals((1..6).map { "power#A#$it" }, CablingVoieNavigator.voies(dto).map { it.rowId })
    }

    @Test fun listeDmxEnteteEtDeparts() {
        val rows = CablingListRows.dmx(listOf(multi("M", "Multi A", 3), simple("S", "DMX B")))
        assertEquals(listOf("hdr#M", "dmx#M#1", "dmx#M#2", "dmx#M#3", "hdr#S", "dmx#S#1"), rows.map { it.id })
        assertEquals(listOf("dmx#M#3", "dmx#S#1"),
            rows.filterIsInstance<CablingListRow.Voie>().filter { it.last }.map { it.id })
    }

    @Test fun chaqueVoieDuParcoursALaLigneDeLaListe() {
        // La reprise vise rowId : toute voie atteignable par ◀ ▶ doit avoir SA ligne.
        val p = PowerCablingDTO(CablingSettings(), listOf(soca("A", "Soca A"), solo("L", "L"), soca("B", "Soca B")))
        val pr = CablingListRows.power(p.distributors).map { it.id }.toSet()
        CablingVoieNavigator.voies(p).forEach { assertTrue(it.rowId, it.rowId in pr) }
        val d = DmxCablingDTO(listOf(multi("M", "M", 4), simple("S", "S")))
        val dr = CablingListRows.dmx(d.distributors).map { it.id }.toSet()
        CablingVoieNavigator.voies(d).forEach { assertTrue(it.rowId, it.rowId in dr) }
        // Clés uniques (LazyColumn).
        assertEquals(pr.size, CablingListRows.power(p.distributors).size)
    }

    // ------------------------------------------------- position lue sur la LazyColumn

    @Test fun positionLueSurLesElementsVisibles() {
        // En-tête de Soca B à −10…30, sa phase L1 à 52…67 : Android reprend l'EN-TÊTE lui-même.
        val v = listOf(Triple("power#A#2", -300f, -10f), Triple("hdr#B", -10f, 30f),
            Triple("phase#B#1", 52f, 67f), Triple("power#B#1", 100f, 260f))
        assertEquals(CablingListPosition.Row("hdr#B"), CablingListRows.positionOf(v, 620f, false, 370f))
        // Une ligne qui affleure (bas à 0, sous la marge) n'est pas en tête.
        val v2 = listOf(Triple("phase#A#1", -40f, 0f), Triple("power#A#1", 0f, 160f))
        assertEquals(CablingListPosition.Row("power#A#1"), CablingListRows.positionOf(v2, 620f, false, 370f))
        // Liste tout en haut : « en haut », pas une voie.
        assertEquals(CablingListPosition.ListTop, CablingListRows.positionOf(v2, 620f, true, 370f))
        // Rien d'affiché : aucune position (la dernière connue n'est pas écrasée).
        assertNull(CablingListRows.positionOf(emptyList(), 620f, true, 370f))
        assertEquals(1, CablingListRows.indexOf(CablingListRows.dmx(listOf(simple("S", "S"))), "dmx#S#1"))
        assertEquals(-1, CablingListRows.indexOf(CablingListRows.dmx(listOf(simple("S", "S"))), "dmx#X#1"))
    }

    // ------------------------------------------------- coloration du plan

    @Test fun colorationForceeParLaFamilleSansAnneau() {
        for (menu in PlanColorMode.entries) for (hs in listOf(true, false)) for (hd in listOf(true, false)) {
            // En affectation : forcé, même sans aucun projecteur câblé (tout gris).
            assertEquals(PlanColorMode.SOCAPEX, CablingAssignColoring.effectiveMode(menu, SOCA, hs, hd))
            assertEquals(PlanColorMode.DMX_LINE, CablingAssignColoring.effectiveMode(menu, DMX, hs, hd))
            assertFalse(CablingAssignColoring.showsSecondRing(CablingAssignColoring.effectiveMode(menu, SOCA, hs, hd), true))
        }
        // Hors affectation : le choix du menu revient (repli calque si son câblage est vide).
        assertEquals(PlanColorMode.SOCAPEX, CablingAssignColoring.effectiveMode(PlanColorMode.SOCAPEX, null, true, false))
        assertEquals(PlanColorMode.LAYER, CablingAssignColoring.effectiveMode(PlanColorMode.SOCAPEX, null, false, true))
        assertEquals(PlanColorMode.DMX_LINE, CablingAssignColoring.effectiveMode(PlanColorMode.DMX_LINE, null, false, true))
        assertEquals(PlanColorMode.LAYER, CablingAssignColoring.effectiveMode(PlanColorMode.LAYER, null, true, true))
        assertTrue(CablingAssignColoring.showsSecondRing(PlanColorMode.SOCAPEX, false))
        assertFalse(CablingAssignColoring.showsSecondRing(PlanColorMode.LAYER, false))
    }

    // ------------------------------------------------- cadrage au-dessus de la barre

    @Test fun cadrageAuDessusDeLaBarre() {
        // Réserve = du haut de la barre au bas du plan, bornée à 60 %.
        assertEquals(200f, CablingAssignBarPlacement.reserve(0f, 1000f, 800f), 0f)
        assertEquals(600f, CablingAssignBarPlacement.reserve(0f, 1000f, 100f), 0f)
        assertEquals(0f, CablingAssignBarPlacement.reserve(0f, 1000f, 1200f), 0f)
        assertEquals(0f, CablingAssignBarPlacement.reserve(0f, 0f, 10f), 0f)
        // Sans réserve : cadrage normal.
        assertEquals(1f to 0f, CablingAssignBarPlacement.fitAbove(400f, 800f, 10f, 20f, 0f))
        // Contenu limité par la HAUTEUR : il rétrécit pour tenir dans la bande libre.
        val (s, dy) = CablingAssignBarPlacement.fitAbove(400f, 800f, 10f, 20f, 200f)
        assertEquals(600f / 800f, s, 1e-6f)
        assertEquals(-100f, dy, 0f)   // centré dans la bande (800 − 200) / 2
        // Contenu limité par la LARGEUR : même échelle, seulement remonté.
        val (s2, dy2) = CablingAssignBarPlacement.fitAbove(400f, 800f, 40f, 10f, 200f)
        assertEquals(1f, s2, 0f)
        assertEquals(-100f, dy2, 0f)
        // Jamais au-delà de 60 % de la hauteur.
        val (_, dy3) = CablingAssignBarPlacement.fitAbove(400f, 800f, 10f, 20f, 790f)
        assertEquals(-240f, dy3, 1e-3f)
    }

    // ------------------------------------------------- cible, haptique, textes

    @Test fun cibleAvecLibelleFige() {
        val power = listOf(soca("A", "Soca A"), solo("L", "  "))
        val dmx = listOf(multi("M", "Multi A", 2), simple("S", "DMX B"))
        assertEquals("Soca A · C3", CablingAssignTarget.forVoie(CablingVoie(SOCA, "A", 3), power, dmx).label)
        assertEquals("Socapex · C1", CablingAssignTarget.forVoie(CablingVoie(SOCA, "L", 1), power, dmx).label)
        assertEquals("Multi A · D2", CablingAssignTarget.forVoie(CablingVoie(DMX, "M", 2), power, dmx).label)
        assertEquals("DMX B", CablingAssignTarget.forVoie(CablingVoie(DMX, "S", 1), power, dmx).label)
        // Distributeur inconnu : libellé de repli (nom vide), jamais de plantage.
        assertEquals("Socapex · C2", CablingAssignTarget.forVoie(CablingVoie(SOCA, "X", 2), power, dmx).label)
        assertEquals("DMX", CablingAssignTarget.forVoie(CablingVoie(DMX, "X", 1), power, dmx).label)
        // La cible porte sa voie.
        val t = CablingAssignTarget.forVoie(CablingVoie(SOCA, "A", 3), power, dmx)
        assertEquals(CablingVoie(SOCA, "A", 3), t.voie)
        assertEquals("power#A#3", t.voie.rowId)
    }

    @Test fun retoursHaptiques() {
        assertEquals(CablingHaptic.ASSIGNED, CablingHaptic.of(CablingToggleResult.ASSIGNED))
        assertEquals(CablingHaptic.REMOVED, CablingHaptic.of(CablingToggleResult.REMOVED))
        assertEquals(CablingHaptic.REFUSED, CablingHaptic.of(CablingToggleResult.VOIE_MISSING))
        assertEquals(CablingHaptic.ASSIGNED, CablingHaptic.ofFrame(true))
        assertEquals(CablingHaptic.REFUSED, CablingHaptic.ofFrame(false))
    }

    @Test fun leCompteRetrecitJusquaTroisQuarts() {
        assertEquals(1f, CablingCountFit.scale(100f, 120f), 0f)
        assertEquals(0.9f, CablingCountFit.scale(100f, 90f), 1e-6f)
        assertEquals(0.75f, CablingCountFit.scale(100f, 40f), 0f)
        assertEquals(1f, CablingCountFit.scale(0f, 40f), 0f)
    }

    @Test fun libellesCourtsSiLaPlaceManque() {
        assertFalse(CablingActionLabels.useCompact(300, 300))
        assertTrue(CablingActionLabels.useCompact(301, 300))
    }

    @Test fun textesDeLEcran() {
        assertEquals("Électrique", TestRes.FR.get("cabling_tab_elec"))
        assertEquals("Electrical", TestRes.EN.get("cabling_tab_elec"))
        assertEquals("Vider le départ", TestRes.FR.get("dmx_clear_core_cd"))
        assertEquals("Clear the core", TestRes.EN.get("dmx_clear_core_cd"))
        // L'ancien bandeau du haut a disparu (remplacé par la barre du bas).
        assertFalse(TestRes.FR.strings.containsKey("assign_banner_fmt"))
        assertFalse(TestRes.EN.strings.containsKey("assign_banner_fmt"))
    }

    // ------------------------------------------------- parcours réel : voie supprimée

    @Test fun voieSupprimeePendantLAffectationRienNEstEcrit() {
        val s = PowerCablingState()
        s.load(PowerCablingDTO(CablingSettings(), listOf(soca("A", "Soca A"), soca("B", "Soca B")),
            listOf(CablingAssignment("f1", "A", 1))))
        var pushes = 0
        s.onChange = { pushes++ }
        // Cible figée AVANT la suppression (Soca B · C2), comme à l'entrée sur le plan.
        val target = CablingAssignTarget.forVoie(CablingVoie(SOCA, "B", 2), s.distributors, emptyList())
        assertEquals(CablingToggleResult.ASSIGNED, s.toggleOnVoie("f2", target.voie))
        assertEquals(1, pushes)
        // Un coéquipier supprime Soca B (arrivée par la synchro = load, sans commit).
        s.load(PowerCablingDTO(CablingSettings(), listOf(soca("A", "Soca A")), listOf(CablingAssignment("f1", "A", 1))))
        pushes = 0
        val v = s.version
        assertEquals(CablingToggleResult.VOIE_MISSING, s.toggleOnVoie("f1", target.voie))
        assertFalse(s.assignFrameOnVoie(listOf("f1", "f3"), target.voie))
        // Câblage INCHANGÉ (f1 reste sur Soca A · C1), rien d'enregistré ni poussé.
        assertEquals(CablingAssignment("f1", "A", 1), s.assignmentOf("f1"))
        assertNull(s.assignmentOf("f3"))
        assertEquals(0, pushes)
        assertEquals(v, s.version)
        // La barre : « Voie supprimée », libellé FIGÉ, ◀ ▶ et cadre éteints.
        val st = CablingVoieStatus.power(target.voie, s.toDTO(), emptyMap())
        assertNull(st)
        val ui = CablingAssignBarUi.display(target, st, Locale.FRENCH)
        assertTrue(ui.voieMissing)
        assertEquals("Soca B · C2", ui.title)
        assertFalse(ui.previousEnabled || ui.nextEnabled || ui.frameEnabled)
        // ◀ ▶ : ni précédente ni suivante.
        assertNull(CablingVoieNavigator.neighbor(target.voie, 1, CablingVoieNavigator.voies(s.toDTO())))
    }

    @Test fun departHorsBornesApresReductionDeLaMultipaire() {
        val d = DmxCablingState()
        d.load(DmxCablingDTO(listOf(multi("M", "Multi", 4)), listOf(DmxCablingAssignment("f1", "M", 1))))
        val voie = CablingVoie(DMX, "M", 3)
        // La multipaire passe à 2 départs (D3 n'existe plus).
        d.load(DmxCablingDTO(listOf(multi("M", "Multi", 2)), listOf(DmxCablingAssignment("f1", "M", 1))))
        var pushes = 0
        d.onChange = { pushes++ }
        assertEquals(CablingToggleResult.VOIE_MISSING, d.toggleOnVoie("f1", voie))
        assertEquals(DmxCablingAssignment("f1", "M", 1), d.assignmentOf("f1"))
        assertEquals(0, pushes)
    }

    @Test fun parcoursMemoireDuRetourDuPlan() {
        // Entrée sur Soca A · C3, ▶ ▶ (C4, C5), « Terminé » : onglet électrique,
        // C5 surligné UNE fois, puis simple reprise de C5.
        val dto = PowerCablingDTO(CablingSettings(), listOf(soca("A", "Soca A")))
        val m = CablingScreenMemoryState(tab = CablingScreenTab.DMX)
        var voie = CablingVoie(SOCA, "A", 3)
        m.rememberWorkedVoie(voie)
        repeat(2) {
            voie = CablingVoieNavigator.neighbor(voie, 1, CablingVoieNavigator.voies(dto))!!
            m.rememberWorkedVoie(voie)
        }
        assertEquals(CablingScreenTab.CABLING, CablingScreenTab.opening(null, m.tab))
        assertEquals(0, CablingScreenTab.opening(null, m.tab).index)
        assertEquals(CablingScrollRestore("power#A#5", true), m.takeRestore(CablingScreenTab.CABLING))
        assertEquals(CablingScrollRestore("power#A#5", false), m.takeRestore(CablingScreenTab.CABLING))
    }

    // ------------------------------------------------- branchement dans les écrans

    private fun src(name: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/ui/$name"), File("app/src/main/java/com/minou/mvrviewer/ui/$name"))
            .first { it.isFile }.readText()

    @Test fun lePlanPasseParLaLogiquePure() {
        val plan = src("PlanScreen.kt")
        for (needle in listOf(
            "cabling.toggleOnVoie(fx, t.voie)", "dmxCabling.toggleOnVoie(fx, t.voie)",
            "cabling.assignFrameOnVoie(enclos, tgt.voie)", "dmxCabling.assignFrameOnVoie(enclos, tgt.voie)",
            "CablingHaptic.of(result)", "CablingHaptic.ofFrame(written)", "CablingHaptic.STEP",
            "CablingAssignColoring.effectiveMode(", "CablingAssignColoring.showsSecondRing(",
            "CablingVoieStatus.power(", "CablingVoieStatus.dmx(", "CablingAssignBarUi.display(",
            "CablingAssignBarPlacement.assignBarInsets(", "CablingAssignBarPlacement.fitAbove(",
            "CablingAssignBar(", "resolveDmxFootprints("
        )) assertTrue("PlanScreen n'appelle plus $needle", plan.contains(needle))
        // L'ancien bandeau (libellé sans point médian) a disparu.
        assertFalse(plan.contains("assign_banner_fmt"))
        assertFalse(plan.contains("\"\$name C\${at.index}\""))
        // Le cadre n'est plus implicite : il s'arme par le bouton de la barre.
        assertFalse(plan.contains("if (rectMode || assignMode) {"))
    }

    @Test fun laSceneRetientLaVoieEtRevientAuCablage() {
        val scene = src("SceneScreen.kt")
        for (needle in listOf(
            "cablingMemory.rememberWorkedVoie(voie)", "CablingVoieNavigator.neighbor(t.voie, step, order)",
            "CablingAssignTarget.forVoie(", "onSelectOnPlan = { t -> setAssignVoie(t.voie); mode = SceneMode.PLAN }",
            "memory = cablingMemory", "onStepAssign = { stepAssign(it) }", "onAssignDone = { finishAssign() }",
            "if (mode == SceneMode.PLAN && assignTarget != null) finishAssign()",
            "onBack = { if (assignTarget != null) finishAssign() else mode = SceneMode.THREE_D }"
        )) assertTrue("SceneScreen n'a plus $needle", scene.contains(needle))
    }

    @Test fun pastilleSousLeSymboleEtBarreReduiteAvecSonTitre() {
        // SPEC §3 : pastille pleine à 85 %, liseré couleur de fond, SOUS la silhouette.
        val render = src("PlanRender.kt")
        assertTrue(render.contains("drawCircle(col.copy(alpha = 0.85f), radius = r, center = p)"))
        assertTrue(render.contains("drawCircle(s.planBg, radius = r, center = p, style = Stroke(1.5f))"))
        assertTrue(render.indexOf("col.copy(alpha = 0.85f)") < render.indexOf("drawPath(t.path, c, style = Stroke(sw))"))
        // SPEC §4 : réduite, la barre garde son en-tête (pastille + voie).
        val bar = src("CablingAssignBar.kt")
        assertTrue(bar.contains("collapsedHeader = {"))
        assertTrue(bar.contains("PanelLayoutStore.PanelID.CABLING_ASSIGN_PLAN"))
        assertEquals("cablingAssignPlan", PanelLayoutStore.PanelID.CABLING_ASSIGN_PLAN.key)
    }

    @Test fun lesListesDuCablageOntLeursLignesRepereEtLeurMemoire() {
        for ((file, tab) in listOf("CablingScreen.kt" to "CablingScreenTab.CABLING", "DmxCablingScreen.kt" to "CablingScreenTab.DMX")) {
            val s = src(file)
            for (needle in listOf(
                "CablingListMemoryEffects(listState, rows, memory, $tab, flash)",
                "itemsIndexed(rows, key = { _, r -> r.id })", "CablingRowActions(", "flash.alphaOf(id)"
            )) assertTrue("$file n'a plus $needle", s.contains(needle))
        }
        assertTrue(src("CablingScreen.kt").contains("CablingScreenTab.opening(null, memory.tab)"))
    }
}
