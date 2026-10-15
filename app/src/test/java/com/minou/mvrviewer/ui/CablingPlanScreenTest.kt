package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect
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

    @Test fun pastillePleineSeulementEnAffectation() {
        // SPEC §3 / iOS `assignFillFor` (`guard assigningForDraw`) : en affectation,
        // la pastille pleine porte la couleur de la famille de la voie.
        assertTrue(CablingAssignColoring.showsAssignFill(PlanColorMode.SOCAPEX, true))
        assertTrue(CablingAssignColoring.showsAssignFill(PlanColorMode.DMX_LINE, true))
        // Hors affectation (« Colorer par câblage » du menu, PDF du plan) : jamais.
        for (mode in PlanColorMode.entries) assertFalse(mode.name, CablingAssignColoring.showsAssignFill(mode, false))
        assertFalse(CablingAssignColoring.showsAssignFill(PlanColorMode.LAYER, true))
    }

    // ------------------------------------------------- place de la barre (légende Android EN HAUT)

    /** Cadre de la légende Android telle que PlanScreen la pose (TopStart, constantes partagées). */
    private fun androidLegend(container: Rect, width: Float, height: Float): Rect {
        val l = container.left + CablingAssignBarPlacement.LEGEND_START_ANDROID
        val t = container.top + CablingAssignBarPlacement.LEGEND_TOP_ANDROID
        return Rect(l, t, l + width, t + height)
    }

    @Test fun barreEnBasMalgreLaLegendeDuHautEnPortrait() {
        // Défaut de relecture : téléphone en portrait 411 × 891 dp, légende dépliée
        // (« Non câblé » seule) en HAUT à gauche → la règle iOS (légende EN BAS)
        // remontait la barre à 801 dp du bas : il ne restait que 90 dp.
        val portrait = Rect(0f, 0f, 411f, 891f)
        val legend = androidLegend(portrait, 110f, 36f)
        assertEquals(Rect(8f, 100f, 118f, 136f), legend)
        assertEquals(801f, CablingAssignBarPlacement.assignBarInsets(false, portrait, 300f, legend).bottom, 0f)
        for (barHeight in listOf(0f, 160f, 30f)) {   // non mesurée, dépliée, réduite
            val i = CablingAssignBarPlacement.assignBarInsetsTopLegend(false, portrait, 300f, barHeight, legend)
            assertEquals(BottomBarInsets(84f, 0f), i)
        }
        // Légende pleine (12 distributeurs + « … ») : elle s'arrête bien au-dessus de la barre.
        val tall = androidLegend(portrait, 180f, 280f)
        val i = CablingAssignBarPlacement.assignBarInsetsTopLegend(false, portrait, 300f, 160f, tall)
        assertEquals(BottomBarInsets(84f, 0f), i)
        // La barre garde TOUTE sa hauteur au-dessus de sa marge basse.
        assertTrue(portrait.height - i.bottom >= 160f)
        // Petit téléphone 360 × 740 : même chose (avant : 90 dp restants).
        val small = Rect(0f, 0f, 360f, 740f)
        assertEquals(BottomBarInsets(84f, 0f),
            CablingAssignBarPlacement.assignBarInsetsTopLegend(false, small, 300f, 160f, androidLegend(small, 110f, 36f)))
        // Plan sous une barre d'outils (repère racine décalé) : même résultat.
        val shifted = Rect(0f, 56f, 411f, 891f)
        assertEquals(BottomBarInsets(84f, 0f),
            CablingAssignBarPlacement.assignBarInsetsTopLegend(false, shifted, 300f, 160f, androidLegend(shifted, 110f, 36f)))
        // Sans légende : la marge ordinaire.
        assertEquals(BottomBarInsets(84f, 0f),
            CablingAssignBarPlacement.assignBarInsetsTopLegend(false, portrait, 300f, 160f, null))
    }

    @Test fun barreEnPaysageAvecLaLegendeDuHaut() {
        // Hauteur compacte 891 × 380 : barre contre le bas (8), bande 212…372.
        val land = Rect(0f, 0f, 891f, 380f)
        // Légende courte : au-dessus de la bande → rien ne bouge.
        assertEquals(BottomBarInsets(8f, 0f),
            CablingAssignBarPlacement.assignBarInsetsTopLegend(true, land, 300f, 160f, androidLegend(land, 110f, 90f)))
        // Légende haute mais étroite : elle descend dans la bande sans toucher la barre centrée.
        assertEquals(BottomBarInsets(8f, 0f),
            CablingAssignBarPlacement.assignBarInsetsTopLegend(true, land, 300f, 160f, androidLegend(land, 110f, 250f)))
        // Légende haute et large (bord droit 308) : la barre glisse à droite comme sur iOS
        // (308 + 10 − 295,5 = 22,5).
        val wide = CablingAssignBarPlacement.assignBarInsetsTopLegend(true, land, 300f, 160f, androidLegend(land, 300f, 250f))
        assertEquals(8f, wide.bottom, 0f)
        assertEquals(22.5f, wide.leadingShift, 1e-4f)
        // Pas la place à droite : la barre reste à sa marge, elle ne remonte JAMAIS
        // au-dessus d'une légende du haut (elle y serait écrasée).
        val huge = CablingAssignBarPlacement.assignBarInsetsTopLegend(true, land, 300f, 160f, androidLegend(land, 700f, 250f))
        assertEquals(BottomBarInsets(8f, 0f), huge)
        // Barre réduite (30 dp) : la légende ne la gêne plus.
        assertEquals(BottomBarInsets(8f, 0f),
            CablingAssignBarPlacement.assignBarInsetsTopLegend(true, land, 300f, 30f, androidLegend(land, 300f, 220f)))
        // En portrait, une légende qui descendrait jusqu'à la barre ne la fait pas remonter non plus.
        val portrait = Rect(0f, 0f, 411f, 600f)
        assertEquals(BottomBarInsets(84f, 0f),
            CablingAssignBarPlacement.assignBarInsetsTopLegend(false, portrait, 300f, 160f, androidLegend(portrait, 200f, 300f)))
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

    // ------------------------------------------------- centrage de la voie au retour du plan

    /**
     * Modèle de défilement d'une `LazyColumn` : décalage borné à [0, contenu −
     * zone visible] (une liste ne défile jamais au-delà de ses bouts).
     * Rend le haut de la ligne [index] dans la zone visible après
     * `scrollToItem(index)` puis `animateScrollBy(delta(offset))`.
     */
    private fun settle(heights: List<Float>, visible: Float, index: Int, delta: (Float, Float) -> Float): Float {
        val content = heights.sum()
        val maxScroll = maxOf(0f, content - visible)
        val top = heights.take(index).sum()
        var scroll = minOf(top, maxScroll)               // scrollToItem
        val offset = top - scroll
        scroll = (scroll + delta(offset, heights[index])).coerceIn(0f, maxScroll)   // animateScrollBy
        return top - scroll
    }

    @Test fun voieDeRetourCentreeSansSortirDeLEcran() {
        val visible = 620f
        // Dernière Socapex : … C5, C6 (130 dp), la liste finit sur C6.
        val heights = List(12) { 130f }
        val newDelta = { off: Float, size: Float -> CablingScrollRestore.centerScrollDelta(off, size, visible) }
        // Ancienne consigne (défaut de relecture) : la ligne était supposée en haut.
        val oldDelta = { _: Float, size: Float -> -(visible - size) / 2f }
        val last = heights.lastIndex
        // Avant : C6 finissait à 735, sous le bas de la zone visible (620) — cachée.
        assertEquals(735f, settle(heights, visible, last, oldDelta), 1e-3f)
        // Après : C6 reste collée en bas, entièrement visible (comme iOS anchor: .center).
        val after = settle(heights, visible, last, newDelta)
        assertEquals(490f, after, 1e-3f)
        assertTrue(after >= 0f && after + 130f <= visible)
        // Toutes les lignes, partout dans la liste : visibles ; centrées quand la liste le permet.
        for (i in heights.indices) {
            val y = settle(heights, visible, i, newDelta)
            assertTrue("ligne $i visible", y >= 0f && y + heights[i] <= visible)
            val top = heights.take(i).sum()
            val centered = (visible - heights[i]) / 2f
            if (top >= centered && top - centered <= heights.sum() - visible) {
                assertEquals("ligne $i centrée", centered, y, 1e-3f)
            }
        }
        // Lignes de hauteurs variées (en-têtes, phases, voies) : jamais hors de l'écran.
        val mixed = listOf(56f, 28f, 130f, 130f, 28f, 130f, 130f, 28f, 130f, 210f, 56f, 28f, 130f, 340f)
        for (i in mixed.indices) {
            if (mixed[i] + CablingScrollRestore.CENTER_MARGIN > visible) continue   // trop haute : reste en haut
            val y = settle(mixed, visible, i, newDelta)
            assertTrue("ligne $i visible ($y)", y >= -1e-3f && y + mixed[i] <= visible + 1e-3f)
        }
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
        val ui = CablingAssignBarUi.display(target, st, Locale.FRENCH,
            CablingAssignBarUi.targetColorArgb(target, s.distributors, emptyList()))
        assertTrue(ui.voieMissing)
        assertEquals("Soca B · C2", ui.title)
        // Distributeur SUPPRIMÉ : pastille grise « non câblé ».
        assertEquals(CablingAssignBarUi.UNCABLED_ARGB, ui.colorArgb)
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
        // La barre : « Voie supprimée », mais la multipaire EXISTE encore → la
        // pastille garde SA couleur (iOS `status?.colorArgb ?? assignTargetColorArgb`),
        // pas le gris « non câblé » (défaut de relecture : 0xFF737373).
        val target = CablingAssignTarget.forVoie(voie, emptyList(), d.distributors)
        val st = CablingVoieStatus.dmx(voie, d.toDTO(), emptyMap())
        assertNull(st)
        val ui = CablingAssignBarUi.display(target, st, Locale.FRENCH,
            CablingAssignBarUi.targetColorArgb(target, emptyList(), d.distributors))
        assertTrue(ui.voieMissing)
        assertEquals("Multi · D3", ui.title)
        assertEquals(0xFF8E24AA.toInt(), ui.colorArgb)
    }

    @Test fun couleurDeLaCibleTantQueSonDistributeurExiste() {
        val power = listOf(soca("A", "Soca A", color = 0xFFE53935.toInt()))
        val dmx = listOf(multi("M", "Multi A", 2))
        // Circuit hors bornes (C7 d'une Socapex de 6) : couleur de la Socapex.
        val t1 = CablingAssignTarget.forVoie(CablingVoie(SOCA, "A", 7), power, dmx)
        assertEquals(0xFFE53935.toInt(), CablingAssignBarUi.targetColorArgb(t1, power, dmx))
        // Départ hors bornes (D3 d'une multipaire de 2) : couleur de la multipaire.
        val t2 = CablingAssignTarget.forVoie(CablingVoie(DMX, "M", 3), power, dmx)
        assertEquals(0xFF8E24AA.toInt(), CablingAssignBarUi.targetColorArgb(t2, power, dmx))
        // Distributeur supprimé (ou d'une autre famille) : gris.
        val t3 = CablingAssignTarget.forVoie(CablingVoie(SOCA, "B", 1), power, dmx)
        assertEquals(CablingAssignBarUi.UNCABLED_ARGB, CablingAssignBarUi.targetColorArgb(t3, power, dmx))
        val t4 = CablingAssignTarget.forVoie(CablingVoie(DMX, "A", 1), power, dmx)
        assertEquals(CablingAssignBarUi.UNCABLED_ARGB, CablingAssignBarUi.targetColorArgb(t4, power, dmx))
        // Voie présente : la pastille est celle de l'état (le repli n'est pas lu).
        val st = CablingVoieStatus.dmx(CablingVoie(DMX, "M", 2), DmxCablingDTO(dmx), emptyMap())!!
        val ui = CablingAssignBarUi.display(CablingAssignTarget.forVoie(st.voie, power, dmx), st, Locale.FRENCH,
            CablingAssignBarUi.UNCABLED_ARGB)
        assertEquals(0xFF8E24AA.toInt(), ui.colorArgb)
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
            "CablingAssignBarPlacement.assignBarInsetsTopLegend(", "CablingAssignBarPlacement.fitAbove(",
            "CablingAssignBarUi.targetColorArgb(barTarget, cabling.distributors, dmxCabling.distributors)",
            "assigning = assignMode", "assignBarHeightPx / dens.density",
            "padding(top = CablingAssignBarPlacement.LEGEND_TOP_ANDROID.dp",
            "start = CablingAssignBarPlacement.LEGEND_START_ANDROID.dp)",
            "CablingAssignBar(", "resolveDmxFootprints("
        )) assertTrue("PlanScreen n'appelle plus $needle", plan.contains(needle))
        // L'ancien bandeau (libellé sans point médian) a disparu.
        assertFalse(plan.contains("assign_banner_fmt"))
        assertFalse(plan.contains("\"\$name C\${at.index}\""))
        // Le cadre n'est plus implicite : il s'arme par le bouton de la barre.
        assertFalse(plan.contains("if (rectMode || assignMode) {"))
        // La règle iOS (légende EN BAS) n'est plus appliquée telle quelle à la légende du haut.
        assertFalse(plan.contains("CablingAssignBarPlacement.assignBarInsets("))
        assertFalse(plan.contains("padding(top = 100.dp, start = 8.dp)"))
    }

    @Test fun laSceneRetientLaVoieEtRevientAuCablage() {
        val scene = src("SceneScreen.kt")
        for (needle in listOf(
            "cablingMemory.rememberWorkedVoie(voie)", "CablingVoieNavigator.neighbor(t.voie, step, order)",
            "CablingAssignTarget.forVoie(",
            "onSelectOnPlan = { t -> setAssignVoie(t.voie); navigate(SceneNavEvent.Cabling(CablingAssignEvent.SELECT_ON_PLAN)) }",
            "memory = cablingMemory", "onStepAssign = { stepAssign(it) }", "onAssignDone = { finishAssign() }",
            // Rangement des menus (tranche A) : le parcours passe par le réducteur pur
            // (SceneNav / CablingAssignRoute, ProjectNavigationTest) — « Terminé »,
            // ←, retour système et croix.
            "fun finishAssign() = navigate(SceneNavEvent.Cabling(CablingAssignEvent.BAR_DONE))",
            "BackHandler { navigate(SceneNavEvent.SystemBack) }",
            "navigate(if (assignTarget != null) SceneNavEvent.Cabling(CablingAssignEvent.BACK)",
            "onExitAssign = { navigate(SceneNavEvent.Cabling(CablingAssignEvent.EXIT_ASSIGN)) }",
            "if (!r.state.assigning) assignTarget = null",
            "initialTab = dest.tab"
        )) assertTrue("SceneScreen n'a plus $needle", scene.contains(needle))
    }

    @Test fun pastilleSousLeSymboleEtBarreReduiteAvecSonTitre() {
        // SPEC §3 : pastille pleine à 85 %, liseré couleur de fond, SOUS la silhouette.
        val render = src("PlanRender.kt")
        assertTrue(render.contains("drawCircle(col.copy(alpha = 0.85f), radius = r, center = p)"))
        assertTrue(render.contains("drawCircle(s.planBg, radius = r, center = p, style = Stroke(1.5f))"))
        assertTrue(render.indexOf("col.copy(alpha = 0.85f)") < render.indexOf("drawPath(t.path, c, style = Stroke(sw))"))
        // … et SEULEMENT en affectation : jamais hors affectation ni dans le PDF.
        assertTrue(render.contains("CablingAssignColoring.showsAssignFill(s.colorMode, s.assigning)"))
        assertFalse(render.contains("if (s.colorMode != PlanColorMode.LAYER && silhouettesOn && !cablingFill.isNullOrEmpty())"))
        assertTrue(src("PlanPdfExport.kt").contains("assigning = false,"))
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
        assertTrue(src("CablingScreen.kt").contains("CablingScreenTab.opening(initialTab, memory.tab)"))
        // Centrage sur le décalage RÉEL de la ligne (pas « supposée en haut »).
        val mem = src("CablingListMemory.kt")
        assertTrue(mem.contains("CablingScrollRestore.centerScrollDelta("))
        assertFalse(mem.contains("animateScrollBy(-(visible - item.size) / 2f)"))
    }
}
