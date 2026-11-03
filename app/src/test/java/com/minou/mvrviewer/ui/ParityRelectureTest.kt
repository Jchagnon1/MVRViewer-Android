package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.GeoCalibration
import com.minou.mvrviewer.mvr.GeoSurvey
import com.minou.mvrviewer.mvr.GeoSurveyPoint
import com.minou.mvrviewer.sync.CablingAssignment
import com.minou.mvrviewer.sync.Distributor
import com.minou.mvrviewer.sync.DistributorKind
import com.minou.mvrviewer.sync.DmxCablingAssignment
import com.minou.mvrviewer.sync.DmxCablingDTO
import com.minou.mvrviewer.sync.DmxDistributor
import com.minou.mvrviewer.sync.DmxDistributorKind
import com.minou.mvrviewer.sync.PowerCablingDTO
import com.minou.mvrviewer.sync.SectionCodec
import com.minou.mvrviewer.sync.SectionPayload
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Écarts de parité relevés par le harnais croisé iOS/web/Android après le
 * portage du câblage sur le plan et de la calibration 3 points. Chaque test
 * a d'abord ÉCHOUÉ sur 04edfdf (reproduction), puis passe après correction.
 */
class ParityRelectureTest {

    @Before fun pro() { ProAccess.forceForTests(true) }
    @After fun resetPro() { ProAccess.forceForTests(null) }

    private fun innerOf(json: String, kind: String) = JSONObject(json).getJSONObject(kind).getJSONObject("_0")

    // ------------------------------------------------ 1. type de distributeur brut

    @Test fun typeInconnuPowerReecritTelQuel() {
        val json = """{"powerCabling":{"_0":{"settings":{"voltage":230,"circuitLimitW":3680,"phaseLimitW":0},
            "distributors":[
              {"id":"X","name":"Type inconnu","kind":"xyz","colorArgb":1,"circuitPhases":[]},
              {"id":"Z","name":"Sans type","colorArgb":2},
              {"id":"N","name":"Type nul","kind":null,"colorArgb":3,"circuitPhases":[1]},
              {"id":"V","name":"Type nombre","kind":5,"colorArgb":4,"circuitPhases":[1]}],
            "assignments":[{"fixture":"f1","distributor":"X","circuit":1}]}}}"""
        val p = SectionCodec.decode(json) as SectionPayload.PowerCabling
        val x = p.dto.distributors[0]
        assertEquals("xyz", x.kindRaw)
        assertEquals(DistributorKind.SOLO, x.kind)       // résolu à l'usage (iOS kindResolved)
        assertEquals(listOf(1), x.circuitPhases)          // défaut du type résolu
        assertEquals("solo", p.dto.distributors[1].kindRaw) // absent → « solo » (iOS)
        assertEquals("solo", p.dto.distributors[2].kindRaw) // nul → « solo » (iOS decodeIfPresent)
        assertEquals("solo", p.dto.distributors[3].kindRaw) // pas une chaîne → « solo » (web)

        val out = innerOf(SectionCodec.encode(p), "powerCabling").getJSONArray("distributors")
        assertEquals("xyz", out.getJSONObject(0).getString("kind"))
        assertEquals("solo", out.getJSONObject(1).getString("kind"))
    }

    @Test fun typeInconnuDmxReecritTelQuel() {
        val json = """{"dmxCabling":{"_0":{"distributors":[
              {"id":"Q","name":"Quad","kind":"quad","colorArgb":1,"cores":4},
              {"id":"M","name":"Multi","kind":"multi","colorArgb":2,"cores":6}],
            "assignments":[]}}}"""
        val p = SectionCodec.decode(json) as SectionPayload.DmxCabling
        val q = p.dto.distributors[0]
        assertEquals("quad", q.kindRaw)
        assertEquals(DmxDistributorKind.SIMPLE, q.kind)
        assertEquals(1, q.cores)                          // résolu simple → 1 départ (iOS)
        val out = innerOf(SectionCodec.encode(p), "dmxCabling").getJSONArray("distributors")
        assertEquals("quad", out.getJSONObject(0).getString("kind"))
        assertEquals("multi", out.getJSONObject(1).getString("kind"))
        assertEquals(6, out.getJSONObject(1).getInt("cores"))
    }

    /** Un coéquipier Android qui modifie puis republie n'écrase pas le type d'une version plus récente. */
    @Test fun republierApresModificationGardeLeTypeInconnu() {
        val power = PowerCablingState()
        power.load(PowerCablingDTO(distributors = listOf(Distributor("X", "Ligne X", "xyz", 1, listOf(1)))))
        power.rename("X", "Ligne Y")
        power.assign(setOf("f1"), "X", 1)
        val pd = power.toDTO()
        assertEquals("xyz", pd.distributors.single().kindRaw)
        assertEquals("Ligne Y", pd.distributors.single().name)
        assertEquals(listOf(CablingAssignment("f1", "X", 1)), pd.assignments)

        val dmx = DmxCablingState()
        dmx.load(DmxCablingDTO(distributors = listOf(DmxDistributor("Q", "Quad", "quad", 1, 1))))
        dmx.rename("Q", "Quad 2")
        dmx.assign(setOf("f1"), "Q", 1)
        val dd = dmx.toDTO()
        assertEquals("quad", dd.distributors.single().kindRaw)
        assertEquals(listOf(DmxCablingAssignment("f1", "Q", 1)), dd.assignments)
    }

    @Test fun constructionEnCodeEcritLeTypeConnu() {
        assertEquals("soca", Distributor("a", "Soca 1", DistributorKind.SOCA, 0, listOf(1, 1, 2, 2, 3, 3)).kindRaw)
        assertEquals("multi", DmxDistributor("b", "Multi 1", DmxDistributorKind.MULTI, 0, 4).kindRaw)
    }

    // ------------------------------------------------ 2. nombres hors bornes reçus

    private val twoAnchors = """[{"worldX":0,"worldY":0,"latitude":48.0,"longitude":2.0},
        {"worldX":20000,"worldY":0,"latitude":48.0,"longitude":2.0003}]"""

    private fun survey(point: String, extra: String = "") =
        """{"method":"threePoint","points":[$point],"precision":2,"freeScale":1,"epoch":1760000000$extra}"""

    private val goodPoint = """{"worldX":10,"worldY":20,"latitude":48,"longitude":2,"accuracy":3,"residual":1}"""

    @Test fun releveHorsFloatIgnoreSansPlanter() {
        val bad = """{"worldX":1e39,"worldY":0,"latitude":48,"longitude":2,"accuracy":3,"residual":1}"""
        val dto = SectionCodec.decodeCalibration(JSONObject("""{"anchors":$twoAnchors,"survey":${survey(bad)}}"""))
        assertNull("relevé écarté (web normalizeSurvey)", dto.survey)
        assertEquals("ancres gardées", 2, dto.anchors.size)
        val out = innerOf(SectionCodec.encode(SectionPayload.Calibration(dto)), "calibration")
        assertFalse(out.has("survey"))
        assertEquals(2, out.getJSONArray("anchors").length())
    }

    @Test fun releveNombreInfiniOuEdgeInvalideIgnore() {
        val inf = SectionCodec.decodeCalibration(JSONObject(
            """{"anchors":$twoAnchors,"survey":{"method":"threePoint","points":[$goodPoint],"precision":1e400,"freeScale":1,"epoch":1}}"""))
        assertNull(inf.survey)
        val edgeTxt = SectionCodec.decodeCalibration(JSONObject(
            """{"anchors":$twoAnchors,"survey":${survey(goodPoint, ",\"edgePrecision\":\"x\"")}}"""))
        assertNull(edgeTxt.survey)
        // Témoins : relevé valide gardé, edgePrecision nul → absent.
        val ok = SectionCodec.decodeCalibration(JSONObject(
            """{"anchors":$twoAnchors,"survey":${survey(goodPoint, ",\"edgePrecision\":null")}}"""))
        assertNotNull(ok.survey)
        assertNull(ok.survey!!.edgePrecision)
        assertEquals(10f, ok.survey!!.points.single().worldX)
    }

    @Test fun ancreHorsFloatSectionRejetee() {
        val json = """{"calibration":{"_0":{"anchors":[{"worldX":1e39,"worldY":0,"latitude":48,"longitude":2},
            {"worldX":20000,"worldY":0,"latitude":48,"longitude":2.0003}]}}}"""
        assertNull("section rejetée comme iOS (débordement de Float)", SectionCodec.decode(json))
        val good = """{"calibration":{"_0":{"anchors":$twoAnchors}}}"""
        assertNotNull(SectionCodec.decode(good))
    }

    // ------------------------------------------------ 3. repères rouges du plan

    private fun pt(x: Float, y: Float) = GeoSurveyPoint(x, y, 48.0, 2.0, 3.0, 1.0)

    @Test fun reperesPointsMesuresSinonAncres() {
        val cal = GeoCalibration()
        cal.addAnchor(GeoAnchor(100f, 200f, 48.0, 2.0))
        cal.addAnchor(GeoAnchor(5000f, 200f, 48.0, 2.0001))
        assertEquals(listOf(100f to 200f, 5000f to 200f), cal.markerPoints)

        val survey = GeoSurvey(GeoSurvey.THREE_POINT_METHOD, listOf(pt(1f, 2f), pt(3f, 4f), pt(5f, 6f)), 2.0, 1.0, 1.76e9)
        cal.replace(listOf(GeoAnchor(0f, 0f, 48.0, 2.0), GeoAnchor(20000f, 0f, 48.0, 2.0003)), survey)
        assertEquals("les 3 endroits réels, pas les ancres synthétiques",
            listOf(1f to 2f, 3f to 4f, 5f to 6f), cal.markerPoints)
        assertTrue(GeoCalibration().markerPoints.isEmpty())
    }

    @Test fun reperesVisiblesCommeIos() {
        // (position affichée || pose d'ancre armée) && pas d'assistant.
        assertTrue(CalibrationMarkers.visible(showLocation = true, anchorTapArmed = false, assistantActive = false))
        assertTrue(CalibrationMarkers.visible(showLocation = false, anchorTapArmed = true, assistantActive = false))
        assertFalse(CalibrationMarkers.visible(showLocation = false, anchorTapArmed = false, assistantActive = false))
        assertFalse(CalibrationMarkers.visible(showLocation = true, anchorTapArmed = true, assistantActive = true))
        val cal = GeoCalibration().apply { addAnchor(GeoAnchor(1f, 2f, 48.0, 2.0)) }
        assertEquals(listOf(1f to 2f), CalibrationMarkers.points(cal, true, false, false))
        assertTrue(CalibrationMarkers.points(cal, true, false, true).isEmpty())
        assertEquals(5f, CalibrationMarkers.RADIUS_DP)
        assertEquals(9f, CalibrationMarkers.CROSS_HALF_DP)
        assertEquals(1.5f, CalibrationMarkers.STROKE_DP)
    }

    // ------------------------------------------------ 4. légende de câblage

    private val power = PowerCablingDTO(
        distributors = listOf(
            Distributor("S1", "Soca 1", DistributorKind.SOCA, 0xFFE53935.toInt(), listOf(1, 1, 2, 2, 3, 3)),
            Distributor("L0", "", DistributorKind.SOLO, 0xFF1E88E5.toInt(), listOf(1)),
            Distributor("L1", "Ligne B", DistributorKind.SOLO, 0xFF43A047.toInt(), listOf(1))),
        assignments = listOf(CablingAssignment("f2", "L1", 1), CablingAssignment("f1", "L0", 1)))
    private val dmx = DmxCablingDTO(
        distributors = listOf(DmxDistributor("D1", "DMX 1", DmxDistributorKind.SIMPLE, 0xFF8E24AA.toInt(), 1)))

    @Test fun legendeTitreEtDistributeursUtilises() {
        val s = CablingLegendUi.section(PlanColorMode.SOCAPEX, power, dmx, anyUnassignedPresent = true)!!
        assertEquals(R.string.cabling_legend_soca, s.titleRes)
        assertEquals("ordre du modèle, seulement les utilisés", listOf("L0", "L1"), s.entries.map { it.id })
        assertTrue(s.showUnassigned)
        assertEquals("Socapex / lignes", TestRes.FR.res(s.titleRes, emptyList()))
        assertEquals("Socapex / lines", TestRes.EN.res(s.titleRes, emptyList()))

        val withDmx = CablingLegendUi.section(PlanColorMode.DMX_LINE, power,
            dmx.copy(assignments = listOf(DmxCablingAssignment("f1", "D1", 1))), anyUnassignedPresent = false)!!
        assertEquals("Lignes DMX", TestRes.FR.res(withDmx.titleRes, emptyList()))
        assertEquals("DMX lines", TestRes.EN.res(withDmx.titleRes, emptyList()))
        assertFalse(withDmx.showUnassigned)
    }

    @Test fun legendeAbsenteSansDistributeurUtilise() {
        // Affectation qui commence : aucune ligne DMX utilisée → pas de section
        // (jamais une légende réduite à « Non câblé »).
        assertNull(CablingLegendUi.section(PlanColorMode.DMX_LINE, power, dmx, anyUnassignedPresent = true))
        assertNull(CablingLegendUi.section(PlanColorMode.SOCAPEX, PowerCablingDTO(), dmx, true))
        assertNull(CablingLegendUi.section(PlanColorMode.LAYER, power, dmx, true))
    }

    @Test fun legendeNomVideSansNom() {
        assertEquals("(sans nom)", CablingLegendUi.displayName("", TestRes.FR.get("common_unnamed")))
        assertEquals("(untitled)", CablingLegendUi.displayName("", TestRes.EN.get("common_unnamed")))
        assertEquals("Ligne B", CablingLegendUi.displayName("Ligne B", "(sans nom)"))
        assertEquals("comme iOS isEmpty : un blanc n'est pas vide", " ", CablingLegendUi.displayName(" ", "(sans nom)"))
        assertEquals("Non câblé", TestRes.FR.get("cabling_unassigned"))
        assertEquals("Not cabled", TestRes.EN.get("cabling_unassigned"))
    }

    // ------------------------------------------------ 5. résidus ≥ 1000 m

    private fun nb(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    @Test fun residusAvecSeparateurDeMilliers() {
        assertEquals("1 234,5", nb(ThreePointDisplay.residualsText(listOf(1234.5), Locale.FRENCH)))
        assertEquals("1,234.5", ThreePointDisplay.residualsText(listOf(1234.5), Locale.ENGLISH))
        assertEquals("arrondi au pair conservé", "1 234,2 · 12 345 678,9",
            nb(ThreePointDisplay.residualsText(listOf(1234.25, 12345678.94), Locale.FRENCH)))
        assertEquals("2.2 · 2.0 · 0.0", ThreePointDisplay.residualsText(listOf(2.15, 2.05, 0.04), Locale.ENGLISH))
        assertEquals("Écarts : 1 234,5 · 2,0 m",
            nb(TestRes.FR.get("cal3_deviations", ThreePointDisplay.residualsText(listOf(1234.5, 2.0), Locale.FRENCH))))
    }

    // ------------------------------------------------ 6. onglet « Power »

    @Test fun ongletsDuCablageEnAnglais() {
        assertEquals("Power", TestRes.EN.get("cabling_tab_power"))
        assertEquals("Electrical", TestRes.EN.get("cabling_tab_elec"))
        assertEquals("Puissances", TestRes.FR.get("cabling_tab_power"))
        assertEquals("Électrique", TestRes.FR.get("cabling_tab_elec"))
    }

    // ------------------------------------------------ 7. noms par défaut

    @Test fun lignesSoloEnLettres() {
        val s = PowerCablingState()
        assertEquals("Ligne A", s.addDistributor(DistributorKind.SOLO).name)
        assertEquals("Soca 1", s.addDistributor(DistributorKind.SOCA).name)
        assertEquals("Ligne B", s.addDistributor(DistributorKind.SOLO).name)
        assertEquals("Soca 2", s.addDistributor(DistributorKind.SOCA).name)
        repeat(24) { s.addDistributor(DistributorKind.SOLO) }   // … jusqu'à « Ligne Z »
        assertEquals("Ligne Z", s.distributors.last().name)
        assertEquals("au-delà de l'alphabet", "Ligne 27", s.addDistributor(DistributorKind.SOLO).name)
        // La barre affiche « Ligne A · C1 » (exemple de la SPEC).
        assertEquals("Ligne A · C1", CablingVoieLabel.power(s.distributors.first().name, 1))
    }

    @Test fun typeInconnuCompteCommeSolo() {
        val s = PowerCablingState()
        s.load(PowerCablingDTO(distributors = listOf(Distributor("X", "?", "xyz", 1, listOf(1)))))
        assertEquals("Ligne B", s.addDistributor(DistributorKind.SOLO).name)
    }

    @Test fun lignesDmxNumeroteesAuTotal() {
        val d = DmxCablingState()
        assertEquals("DMX 1", d.addDistributor(DmxDistributorKind.SIMPLE).name)
        assertEquals("Multi 2", d.addDistributor(DmxDistributorKind.MULTI).name)
        assertEquals("DMX 3", d.addDistributor(DmxDistributorKind.SIMPLE).name)
        assertEquals("Multi 4", d.addDistributor(DmxDistributorKind.MULTI, 6).name)
    }
}
