package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.DxfFill
import com.minou.mvrviewer.mvr.DxfPlan
import com.minou.mvrviewer.mvr.DxfPolyline
import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.GeoCalibration
import com.minou.mvrviewer.mvr.GeoCalibrationStatus
import com.minou.mvrviewer.mvr.GeoSurvey
import com.minou.mvrviewer.mvr.GeoSurveyPoint
import com.minou.mvrviewer.mvr.GeoThreePoint
import com.minou.mvrviewer.mvr.ReferencePlan
import com.minou.mvrviewer.mvr.ReferencePlanTransform
import com.minou.mvrviewer.mvr.Vec2
import com.minou.mvrviewer.sync.GeoCalibrationDTO
import com.minou.mvrviewer.sync.LocalMapper
import com.minou.mvrviewer.sync.SectionCodec
import com.minou.mvrviewer.sync.SectionPayload
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Assistant 3 POINTS côté écran (pas d'émulateur) : la machine à états
 * transcrite d'iOS (`ThreePointCalibrationSession`), ses règles de temps (10 s,
 * 20 s, 3 s, 30 s, filet du fix immobile), la logique d'affichage du panneau,
 * l'emprise et les éléments repères, le relevé synchronisé et les gardes de
 * branchement dans la vue plan.
 */
class ThreePointSessionTest {

    // ---------------------------------------------------------------- outils

    private class Clock(var t: Double = 1000.0) { fun now() = t }

    private val origin = 45.6975 to 5.8893
    private val theta = 0.5

    /** Position GPS d'un point du plan (mm) pour une rotation θ (plan = R(θ)·ENU). */
    private fun gpsOf(w: Vec2, noise: Vec2 = Vec2.ZERO): Pair<Double, Double> {
        val e = GeoThreePoint.rotate(w / 1000.0, -theta) + noise
        return GeoThreePoint.latLon(e, origin.first, origin.second)
    }

    private var key = 0.0
    private fun fix(c: Clock, w: Vec2, accuracy: Double = 4.0, age: Double = 0.0): RawFix {
        val (lat, lon) = gpsOf(w)
        key += 1
        return RawFix(lat, lon, accuracy, key, c.t - age)
    }

    private fun session(c: Clock, bounds: GeoThreePoint.Bounds? = null, last: () -> RawFix? = { null },
                        step: Double = 0.0, saved: MutableList<Double> = mutableListOf()) =
        ThreePointCalibrationSession(bounds, emptyList(), step, clock = c::now, lastRawFix = last,
            onStepLengthChanged = { saved.add(it) })

    /** Mesure complète au point [w] : 4 fixes distincts puis 10 s. */
    private fun measureAt(s: ThreePointCalibrationSession, c: Clock, w: Vec2, accuracy: Double = 4.0) {
        s.tap(w)
        repeat(4) { s.onRawFix(fix(c, w, accuracy)); c.t += 0.5; s.tick() }
        while (s.stage is ThreePointStage.Measuring) { c.t += 0.5; s.tick() }
    }

    private val big = GeoThreePoint.Bounds(Vec2(-40_000.0, -25_000.0), Vec2(40_000.0, 25_000.0))

    // ------------------------------------------------------------ parcours

    @Test fun parcoursCompletJusquAuResultatEtAuxAncresSynthetiques() {
        val c = Clock(); val s = session(c, big)
        assertEquals(ThreePointStage.Approach(0), s.stage)
        assertEquals(3, s.slotCount)
        val p1 = Vec2(-35_000.0, -20_000.0)
        measureAt(s, c, p1)
        assertEquals(ThreePointStage.Measured(0), s.stage)
        assertEquals(p1.x, s.points[0]!!.worldX, 0.0)
        assertFalse(s.sampling)
        s.next()
        assertEquals(ThreePointStage.Approach(1), s.stage)
        val t2 = s.targets[1]!!
        assertEquals(GeoThreePoint.suggestSecondTarget(p1, big), t2)
        assertEquals(p1, s.departure(1)!!.world)
        measureAt(s, c, t2)
        s.next()
        assertEquals(ThreePointStage.Approach(2), s.stage)
        val t3 = s.targets[2]!!
        assertEquals(GeoThreePoint.suggestThirdTarget(p1, t2, big), t3)
        measureAt(s, c, t3)
        assertEquals(ThreePointStage.Measured(2), s.stage)
        s.next() // « Calculer » au 3e point
        assertEquals(ThreePointStage.Result, s.stage)
        val f = s.fit!!
        assertEquals(3, f.pointCount)
        assertTrue(f.consistent)
        assertEquals(theta, f.rotation, 1e-6)
        assertEquals(f.edgePrecision(big), s.edgePrecision!!, 0.0)
        val (anchors, survey) = s.calibration(1_760_000_000.0)!!
        val (ea, es) = GeoCalibration.fromThreePoint(f, s.measuredPoints, s.edgePrecision, 1_760_000_000.0)
        assertEquals(ea, anchors)
        assertEquals(es, survey)
        assertEquals(GeoSurvey.THREE_POINT_METHOD, survey.method)
        assertEquals(3, survey.points.size)
        assertEquals(2, anchors.size)
        // Le modèle à ancres replace chaque point mesuré à quelques mm.
        val cal = GeoCalibration().apply { replace(anchors, survey) }
        for (p in s.measuredPoints) {
            val w = cal.worldPosition(p.latitude, p.longitude)!!
            assertEquals(p.worldX, w.first.toDouble(), 5.0)
            assertEquals(p.worldY, w.second.toDouble(), 5.0)
        }
        assertEquals(GeoCalibrationStatus.ThreePoints(cal.surveyPrecisionMeters!!, cal.surveyEdgePrecisionMeters),
            GeoCalibrationStatus.of(cal, false))
    }

    // ---------------------------------------------------------- règles de temps

    @Test fun troisFixesDistinctsTerminentA10sDepuisLePremierFixRetenu() {
        val c = Clock(); val s = session(c)
        s.tap(Vec2.ZERO)
        assertEquals(ThreePointStage.Measuring(0), s.stage)
        assertTrue(s.sampling)
        c.t += 1.0; s.tick()
        assertEquals(0.0, s.progress, 0.0) // aucun fix retenu : le temps ne compte pas
        val firstGood = c.t
        s.onRawFix(fix(c, Vec2.ZERO)); s.onRawFix(fix(c, Vec2.ZERO)); s.onRawFix(fix(c, Vec2.ZERO))
        assertEquals(3, s.fixCount)
        c.t = firstGood + 9.5; s.tick()
        assertEquals(ThreePointStage.Measuring(0), s.stage)
        assertEquals(0.95, s.progress, 1e-9)
        c.t = firstGood + 10.0; s.tick()
        assertEquals(ThreePointStage.Measured(0), s.stage)
        assertEquals(1.0, s.progress, 0.0)
    }

    @Test fun moinsDeTroisFixesDistinctsTerminentA20s() {
        val c = Clock(); val s = session(c)
        s.tap(Vec2.ZERO)
        val f = fix(c, Vec2.ZERO)
        s.onRawFix(f); s.onRawFix(f); s.onRawFix(f) // même clé : compté une fois
        assertEquals(1, s.fixCount)
        val good = c.t
        c.t = good + 10.0; s.tick()
        assertEquals(ThreePointStage.Measuring(0), s.stage)
        assertEquals(0.5, s.progress, 1e-9) // la barre suit 20 s
        c.t = good + 19.5; s.tick()
        assertEquals(ThreePointStage.Measuring(0), s.stage)
        c.t = good + 20.0; s.tick()
        assertEquals(ThreePointStage.Measured(0), s.stage)
    }

    @Test fun unFixAnterieurAuToucherEstIgnoreSaufLeFiletDuFixImmobile() {
        val c = Clock()
        var last: RawFix? = null
        val s = session(c, last = { last })
        last = fix(c, Vec2.ZERO, age = 10.0) // 10 s avant le toucher
        s.tap(Vec2.ZERO)
        // Le dernier fix connu, antérieur de plus de 2 s, n'est pas pris au toucher.
        assertEquals(0, s.fixCount)
        c.t += 2.5; s.tick()
        assertEquals(0, s.fixCount)
        assertFalse(s.waitingForFix)
        // Passé 3 s sans aucun fix : le dernier connu compte (une fois) — il a < 30 s.
        c.t += 0.5; s.tick()
        assertEquals(1, s.fixCount)
        assertFalse(s.waitingForFix)
        c.t += 0.5; s.tick()
        assertEquals(1, s.fixCount)
    }

    @Test fun unFixTropVieuxNeSertPasDeFiletEtOnAttendLeGps() {
        val c = Clock()
        var last: RawFix? = null
        val s = session(c, last = { last })
        last = fix(c, Vec2.ZERO, age = 40.0)
        s.tap(Vec2.ZERO)
        c.t += 3.0; s.tick()
        assertEquals(0, s.fixCount)
        assertTrue("« En attente du GPS… »", s.waitingForFix)
        // Un fix récent arrive : l'attente cesse.
        s.onRawFix(fix(c, Vec2.ZERO))
        c.t += 0.5; s.tick()
        assertFalse(s.waitingForFix)
        assertEquals(1, s.fixCount)
    }

    @Test fun fixImprecisPrecisionInsuffisantePuisUtiliserQuandMemeA30s() {
        val c = Clock(); val s = session(c)
        s.tap(Vec2(1000.0, 2000.0))
        s.onRawFix(fix(c, Vec2(1000.0, 2000.0), accuracy = 35.0))
        c.t += 0.5; s.tick()
        assertTrue(s.lowAccuracy)
        assertEquals(35.0, s.lastAccuracy!!, 0.0)
        assertEquals(0, s.fixCount)
        assertFalse(s.canForce)
        c.t += 29.0; s.tick()
        assertFalse(s.canForce) // 29,5 s
        c.t += 0.5; s.tick()
        assertTrue(s.canForce) // 30 s depuis le toucher
        s.forceFinish()
        assertEquals(ThreePointStage.Measured(0), s.stage)
        assertEquals(35.0, s.points[0]!!.accuracy, 1e-9)
        assertEquals(1000.0, s.points[0]!!.worldX, 0.0)
    }

    @Test fun auDelaDe100mRienNeSertMemePasUtiliserQuandMeme() {
        val c = Clock(); val s = session(c)
        s.tap(Vec2.ZERO)
        s.onRawFix(fix(c, Vec2.ZERO, accuracy = 150.0))
        c.t += 31.0; s.tick()
        assertTrue(s.lowAccuracy)
        assertFalse(s.canForce)
        // Sans précision connue (hasAccuracy faux → 0) : ignoré entièrement.
        s.onRawFix(fix(c, Vec2.ZERO, accuracy = 0.0))
        c.t += 0.5; s.tick()
        assertEquals(150.0, s.lastAccuracy!!, 0.0)
    }

    // ------------------------------------------------------------------ gestes

    @Test fun retoucherPendantLaMesureDeplaceLaPastilleSansRelancer() {
        val c = Clock(); val s = session(c)
        s.tap(Vec2.ZERO)
        s.onRawFix(fix(c, Vec2.ZERO)); s.onRawFix(fix(c, Vec2.ZERO)); s.onRawFix(fix(c, Vec2.ZERO))
        val good = c.t
        c.t += 5.0; s.tick()
        s.tap(Vec2(500.0, 600.0))
        assertEquals(Vec2(500.0, 600.0), s.pin)
        assertEquals(3, s.fixCount) // mesure continuée
        c.t = good + 10.0; s.tick()
        assertEquals(ThreePointStage.Measured(0), s.stage)
        assertEquals(500.0, s.points[0]!!.worldX, 0.0)
        // Point mesuré : un toucher corrige encore l'endroit.
        s.tap(Vec2(700.0, 800.0))
        assertEquals(700.0, s.points[0]!!.worldX, 0.0)
        assertEquals(800.0, s.points[0]!!.worldY, 0.0)
        // « Refaire la mesure » : même endroit, nouvelle moyenne.
        s.remeasure()
        assertEquals(ThreePointStage.Measuring(0), s.stage)
        assertNull(s.points[0])
        assertEquals(0, s.fixCount)
    }

    @Test fun annulerUneMesureRevientAToucherLEndroitEtNEcoutePlusLesFixes() {
        val c = Clock(); val s = session(c)
        s.tap(Vec2.ZERO)
        s.cancel()
        assertEquals(ThreePointStage.Approach(0), s.stage)
        assertNull(s.pin)
        assertFalse(s.sampling)
        s.onRawFix(fix(c, Vec2.ZERO))
        assertEquals(0, s.fixCount)
        s.tick()
        assertEquals(ThreePointStage.Approach(0), s.stage)
    }

    private fun resultWithThreePoints(c: Clock, wrong: Int? = null): ThreePointCalibrationSession {
        val s = session(c)
        val pts = listOf(Vec2(0.0, 0.0), Vec2(35_000.0, 0.0), Vec2(17_500.0, 30_311.0))
        val centroid = Vec2(17_500.0, 30_311.0 / 3)
        for ((i, p) in pts.withIndex()) {
            // Point faux : touché à 15 m de sa vraie place, à l'opposé du centre
            // (ses DEUX distances sont fausses → coupable certain), GPS juste.
            val away = (p - centroid) / (p - centroid).length() * 15_000.0
            s.tap(if (i == wrong) p + away else p)
            repeat(4) { s.onRawFix(fix(c, p, 3.0)); c.t += 0.5; s.tick() }
            while (s.stage is ThreePointStage.Measuring) { c.t += 0.5; s.tick() }
            s.next()
        }
        return s
    }

    @Test fun refaireUnPointRetourneAuMemeEndroitPuisRecalculeDirectement() {
        val c = Clock(); val s = resultWithThreePoints(c, wrong = 0)
        assertEquals(ThreePointStage.Result, s.stage)
        val o = s.fit!!.outlier!!
        assertTrue(o.certain)
        assertEquals(0, o.index)
        assertEquals(setOf(0), s.suspectSlots)
        val d = ThreePointDisplay.result(s.fit, s.edgePrecision, s.hasControl, s.usedSlots)
        assertEquals(CalAction.Kind.REDO, d.primary.kind)
        assertEquals(0, d.primary.slot)
        val touched = s.points[0]!!.world
        s.redo(0)
        assertTrue(s.redoing)
        assertEquals(ThreePointStage.Approach(0), s.stage)
        assertEquals(touched, s.targets[0])
        assertTrue(s.suspectSlots.isEmpty())
        // Le bon endroit cette fois, puis « Calculer » recalcule tout de suite.
        s.tap(Vec2.ZERO)
        repeat(4) { s.onRawFix(fix(c, Vec2.ZERO, 3.0)); c.t += 0.5; s.tick() }
        while (s.stage is ThreePointStage.Measuring) { c.t += 0.5; s.tick() }
        assertEquals(CalText(R.string.cal3_compute), ThreePointDisplay.measuredPrimary(0, s.redoing))
        s.next()
        assertEquals(ThreePointStage.Result, s.stage)
        assertFalse(s.redoing)
        assertNull(s.fit!!.outlier)
    }

    @Test fun pointDeControlePuisEcarterLePointFaux() {
        val c = Clock(); val s = resultWithThreePoints(c, wrong = 1)
        val here = s.points[2]!!.world
        s.addControlPoint()
        assertTrue(s.hasControl)
        assertEquals(4, s.slotCount)
        assertEquals(ThreePointStage.Approach(ThreePointCalibrationSession.CONTROL_SLOT), s.stage)
        val pts = (0..2).map { s.points[it]!!.world }
        assertEquals(GeoThreePoint.suggestControlTarget(pts, here, null), s.targets[3])
        assertEquals(here, s.departure(3)!!.world)
        assertEquals(CalText(R.string.cal3_control_point), ThreePointDisplay.stepLabel(s.stage, s.currentIndex))
        // Un 2e ajout est refusé tant qu'on n'est pas au résultat.
        s.addControlPoint()
        assertEquals(ThreePointStage.Approach(3), s.stage)
        val t = s.targets[3]!!
        s.tap(t)
        repeat(4) { s.onRawFix(fix(c, t, 3.0)); c.t += 0.5; s.tick() }
        while (s.stage is ThreePointStage.Measuring) { c.t += 0.5; s.tick() }
        assertEquals(ThreePointStage.Measured(3), s.stage)
        // « Calculer » au point de contrôle (SPEC §2, 5 bis).
        assertEquals(CalText(R.string.cal3_compute), ThreePointDisplay.measuredPrimary(3, s.redoing))
        s.next()
        assertEquals(ThreePointStage.Result, s.stage)
        assertEquals(4, s.fit!!.pointCount)
        val o = s.fit!!.outlier!!
        assertTrue(o.certain)
        assertEquals(1, s.slot(o.index))
        val d = ThreePointDisplay.result(s.fit, s.edgePrecision, s.hasControl, s.usedSlots)
        assertEquals(CalAction.Kind.DISCARD, d.primary.kind)
        assertEquals(1, d.primary.slot)
        s.discard(1)
        assertEquals(1, s.discarded)
        assertEquals(listOf(0, 2, 3), s.usedSlots)
        assertEquals(3, s.fit!!.pointCount)
        assertNull(s.fit!!.outlier)
        assertEquals(3, s.slot(2)) // l'indice 2 du résultat = le point de contrôle
        assertEquals(ThreePointDisplay.Dot.DISCARDED, ThreePointDisplay.dot(s, 1))
        // La calibration écrit les 3 points RETENUS.
        val (_, survey) = s.calibration(1.0)!!
        assertEquals(3, survey.points.size)
        // Écarter ne vaut qu'avec 4 points retenus.
        s.discard(0)
        assertEquals(1, s.discarded)
    }

    @Test fun recommencerRepartDuPoint1() {
        val c = Clock(); val s = resultWithThreePoints(c)
        s.addControlPoint()
        s.restart()
        assertEquals(ThreePointStage.Approach(0), s.stage)
        assertFalse(s.hasControl)
        assertNull(s.discarded)
        assertTrue(s.points.all { it == null })
        assertTrue(s.targets.all { it == null })
        assertNull(s.fit)
        assertNull(s.edgePrecision)
        assertEquals(CalText(R.string.cal3_point_of_3, listOf(1)), ThreePointDisplay.stepLabel(s.stage, s.currentIndex))
    }

    @Test fun pointsConfondusRecommencer() {
        val c = Clock(); val s = session(c)
        repeat(3) {
            s.tap(Vec2.ZERO)
            repeat(4) { s.onRawFix(fix(c, Vec2.ZERO, 3.0)); c.t += 0.5; s.tick() }
            while (s.stage is ThreePointStage.Measuring) { c.t += 0.5; s.tick() }
            s.next()
        }
        assertEquals(ThreePointStage.Result, s.stage)
        assertNull(s.fit)
        assertNull(s.calibration(1.0))
        val d = ThreePointDisplay.result(s.fit, s.edgePrecision, s.hasControl, s.usedSlots)
        assertEquals(CalAction.Kind.RESTART, d.primary.kind)
        assertEquals(listOf(CalWarning(CalText(R.string.cal3_coincident))), d.warnings)
    }

    // ------------------------------------------------------------ longueur de pas

    @Test fun longueurDePasBorneeMemoriseeEtDefaut() {
        val c = Clock(); val saved = mutableListOf<Double>()
        assertEquals(0.75, session(c, step = 0.0).stepLength, 0.0) // clé absente
        assertEquals(0.75, session(c, step = 1.5).stepLength, 0.0) // hors bornes
        val s = session(c, step = 0.95, saved = saved)
        assertEquals(0.95, s.stepLength, 0.0)
        s.stepLengthBy(1)
        assertEquals(1.0, s.stepLength, 1e-12)
        s.stepLengthBy(1) // au bout : sans effet
        assertEquals(1.0, s.stepLength, 1e-12)
        s.updateStepLength(0.2)
        assertEquals(0.5, s.stepLength, 0.0)
        assertEquals(listOf(0.5), saved.drop(1).takeLast(1))
        s.stepLengthBy(-1)
        assertEquals(0.5, s.stepLength, 0.0)
        assertEquals("0,50 m", ThreePointDisplay.stepLengthText(s.stepLength, Locale.FRENCH))
        assertEquals("0.75 m", ThreePointDisplay.stepLengthText(0.75, Locale.ENGLISH))
    }

    // ---------------------------------------------------------- affichage

    @Test fun pastilleGpsEtLigneDEtat() {
        assertEquals(ThreePointDisplay.GpsLevel.GOOD, ThreePointDisplay.gpsLevel(10.0))
        assertEquals(ThreePointDisplay.GpsLevel.FAIR, ThreePointDisplay.gpsLevel(10.01))
        assertEquals(ThreePointDisplay.GpsLevel.FAIR, ThreePointDisplay.gpsLevel(20.0))
        assertEquals(ThreePointDisplay.GpsLevel.POOR, ThreePointDisplay.gpsLevel(20.5))
        val c = Clock(); val s = session(c)
        assertEquals(7.0, ThreePointDisplay.currentAccuracy(s, 7.0)!!, 0.0)
        assertNull(ThreePointDisplay.currentAccuracy(s, 0.0))
        assertNull(ThreePointDisplay.currentAccuracy(s, null))
        s.tap(Vec2.ZERO)
        s.onRawFix(fix(c, Vec2.ZERO, accuracy = 12.0))
        assertEquals("pendant la mesure : le dernier fix reçu", 12.0, ThreePointDisplay.currentAccuracy(s, 3.0)!!, 0.0)
        assertEquals(ThreePointDisplay.Dot.CURRENT, ThreePointDisplay.dot(s, 0))
        assertEquals(ThreePointDisplay.Dot.EMPTY, ThreePointDisplay.dot(s, 1))
        assertEquals("GPS ± 12 m", TestRes.FR.get("cal3_gps_accuracy", 12))
        // « Point n mesuré · ± N m » : précision arrondie, au moins 1.
        assertEquals(CalText(R.string.cal3_point_measured, listOf(2, 1)), ThreePointDisplay.measuredTitle(1, 0.4))
        assertEquals(CalText(R.string.cal3_point_measured, listOf(1, 4)), ThreePointDisplay.measuredTitle(0, 3.5))
        assertEquals(CalText(R.string.cal3_next_point), ThreePointDisplay.measuredPrimary(1, false))
    }

    @Test fun guidageEnMarcheDeLaSession() {
        val c = Clock(); val s = session(c)
        val p1 = Vec2.ZERO
        measureAt(s, c, p1)
        s.next()
        // Point 1 seul : « Parcouru » depuis le point 1, sans minimum.
        val (lat, lon) = gpsOf(Vec2(7_500.0, 0.0))
        val g = ThreePointDisplay.liveGuidance(s, 1, lat, lon)!!
        assertFalse(g.remaining)
        assertEquals(CalText(R.string.cal3_walked, listOf(10)), g.text)
        // Point 2 mesuré : les deux points cohérents situent l'utilisateur.
        measureAt(s, c, s.targets[1]!!)
        s.next()
        val t3 = s.targets[2]!!
        val (la, lo) = gpsOf(t3 + Vec2(0.0, 7_500.0))
        val g2 = ThreePointDisplay.liveGuidance(s, 2, la, lo)!!
        assertTrue(g2.remaining)
        assertEquals(CalText(R.string.cal3_remaining, listOf(10)), g2.text)
        // En refaisant un point sans ajustement fiable : rien (pas de « Parcouru »).
        assertNull(ThreePointDisplay.liveGuidance(emptyList(), t3, s.points[1], true, la, lo, 0.75))
    }

    @Test fun ecartsUneDecimaleArrondiCommeIcu() {
        assertEquals("0,2 · 0,2 · 2,0 · 2,4", ThreePointDisplay.residualsText(listOf(0.25, 0.15, 2.0, 2.35), Locale.FRENCH))
        assertEquals("0.0 · 10.7", ThreePointDisplay.residualsText(listOf(9.7e-12, 10.74), Locale.ENGLISH))
        assertEquals("Écarts : 0,0 · 10,7 m",
            TestRes.FR.get("cal3_deviations", ThreePointDisplay.residualsText(listOf(9.7e-12, 10.74), Locale.FRENCH)))
    }

    @Test fun ligneDEtatDeLaCalibration() {
        val cal = GeoCalibration()
        assertEquals(GeoCalibrationStatus.Uncalibrated, GeoCalibrationStatus.of(cal, false))
        assertEquals(GeoCalibrationStatus.Denied, GeoCalibrationStatus.of(cal, true))
        assertTrue(GeoCalibrationStatus.Denied.isWarning)
        assertEquals("Non calibré", TestRes.FR.res(ThreePointDisplay.statusText(GeoCalibrationStatus.Uncalibrated).id, emptyList()))
        assertEquals("Location denied — Settings > Privacy",
            TestRes.EN.res(ThreePointDisplay.statusText(GeoCalibrationStatus.Denied).id, emptyList()))
        cal.addAnchor(GeoAnchor(0f, 0f, 45.7, 5.9))
        assertEquals(GeoCalibrationStatus.OneAnchor, GeoCalibrationStatus.of(cal, false))
    }

    // -------------------------------------------- emprise et éléments repères

    @Test fun empriseRobusteEtCoinsDuPlanImporte() {
        // 30 projecteurs groupés + un aberrant à 1 km : écarté (au-delà de 20 points).
        val fx = (0 until 30).map { Vec2((it % 6) * 5_000.0, (it / 6) * 4_000.0) } + Vec2(1_000_000.0, 0.0)
        val b = ThreePointPlanData.bounds(fx, emptyList(), null)!!
        assertEquals(25_000.0, b.max.x, 0.0)
        assertEquals(16_000.0, b.max.y, 0.0)
        // Coins du plan importé : toujours ajoutés (après le filtre).
        val withPlan = ThreePointPlanData.bounds(fx, emptyList(), listOf(Vec2(-10_000.0, -10_000.0), Vec2(60_000.0, 30_000.0)))!!
        assertEquals(-10_000.0, withPlan.min.x, 0.0)
        assertEquals(60_000.0, withPlan.max.x, 0.0)
        // Moins de 20 points : pas de filtre ; côté ≤ 1 m : inutilisable.
        assertEquals(3, ThreePointPlanData.robustBounds(listOf(Vec2.ZERO, Vec2(1.0, 1.0), Vec2(1e6, 0.0))).size)
        assertNull(ThreePointPlanData.bounds(listOf(Vec2.ZERO, Vec2(800.0, 50_000.0)), emptyList(), null))
        assertNull(ThreePointPlanData.bounds(emptyList(), emptyList(), null))
    }

    private fun translation(x: Float, y: Float, z: Float = 0f) = Mat4(
        Float4(1f, 0f, 0f, 0f), Float4(0f, 1f, 0f, 0f), Float4(0f, 0f, 1f, 0f), Float4(x, y, z, 1f)
    )

    @Test fun elementsReconnaissablesDansLOrdreIos() {
        // Un pont de 6 m × 0,4 m (coins ajoutés), un objet ponctuel (centre seul),
        // un masqué, et une structure DÉMESURÉE (sol de 500 m) écartée.
        val beam = floatArrayOf(-3000f, -200f, 0f, 3000f, 200f, 0f)
        val dot = floatArrayOf(0f, 0f, 0f, 100f, 100f, 0f)
        val floor = floatArrayOf(-250_000f, -250_000f, 0f, 250_000f, 250_000f, 0f)
        val wire = PlanWireframe.Built(
            mapOf("beam" to beam, "dot" to dot, "floor" to floor), emptyMap(),
            listOf(
                PlanWireframe.Inst("beam", "b1", translation(10_000f, 5_000f), "L", 10_000f, -5_000f),
                PlanWireframe.Inst("dot", "d1", translation(-4_000f, 2_000f), "L", -4_000f, -2_000f),
                PlanWireframe.Inst("beam", "hidden", translation(0f, 0f), "L", 0f, 0f),
                PlanWireframe.Inst("floor", "f1", translation(0f, 0f), "L", 0f, 0f),
                PlanWireframe.Inst("beam", "b2", translation(30_000f, 30_000f), "L", 30_000f, -30_000f),
            )
        )
        val fixtures = listOf("fx1" to Vec2(1_000.0, 1_000.0), "fx2" to Vec2(2_000.0, 2_000.0))
        val inputs = ThreePointPlanInputs.compute(fixtures, emptyList(), wire, setOf("hidden", "fx2"), null, emptySet())
        val l = inputs.landmarks
        assertEquals(Vec2(1_000.0, 1_000.0), l[0]) // projecteurs visibles d'abord
        assertEquals(Vec2(10_000.0, 5_000.0), l[1]) // centre du pont
        assertEquals(Vec2(7_000.0, 4_800.0), l[2]) // puis ses 4 coins
        assertEquals(Vec2(13_000.0, 5_200.0), l[4])
        assertEquals(Vec2(-3_950.0, 2_050.0), l[6]) // objet ponctuel : centre seul (≤ 30 cm)
        assertEquals(1 + 5 + 1 + 5, l.size) // ni masqué, ni démesuré
        assertTrue(l.none { it == Vec2(0.0, 0.0) })
        assertNotNull(inputs.bounds)
    }

    @Test fun sommetsDuPlanImporteEchantillonnesAuDelaDe15000() {
        val n = 40_000
        val pts = FloatArray(n * 2) { if (it % 2 == 0) (it / 2).toFloat() else 0f }
        val plan = DxfPlan(
            listOf(DxfPolyline(pts, false, "A"), DxfPolyline(floatArrayOf(5f, 5f), false, "HIDDEN")),
            0f, 0f, n.toFloat(), 2_000f, "mm", n, emptyMap(), 0,
            fills = listOf(DxfFill(listOf(floatArrayOf(1f, 2f, 3f, 4f)), 0, true, "A"))
        )
        val rp = ReferencePlan(plan, ReferencePlanTransform(offsetX = 100.0, offsetY = 0.0))
        val inputs = ThreePointPlanInputs.compute(emptyList(), emptyList(), null, emptySet(), rp, setOf("HIDDEN"))
        // total = 40 000 + 2 (remplissage) ; pas = 40 002 / 15 000 = 2 → un sommet sur deux.
        assertEquals(20_001, inputs.landmarks.size)
        assertEquals(Vec2(100.0, 0.0), inputs.landmarks[0])
        assertEquals(Vec2(102.0, 0.0), inputs.landmarks[1])
        assertEquals(Vec2(101.0, 2.0), inputs.landmarks.last()) // 1er sommet du remplissage (k = 40 000)
        // Plan masqué : ni coins ni sommets.
        rp.transform.visible = false
        val hidden = ThreePointPlanInputs.compute(emptyList(), emptyList(), null, emptySet(), rp, emptySet())
        assertTrue(hidden.landmarks.isEmpty())
        assertNull(hidden.bounds)
    }

    // ------------------------------------------------------ relevé synchronisé

    private val survey = GeoSurvey(
        GeoSurvey.THREE_POINT_METHOD,
        List(3) { GeoSurveyPoint(1000f, 2000f, 45.7, 5.9, 3.5, 0.4) }, 3.2, 1.01, 1_760_000_000.0, 7.6
    )
    private val twoAnchors = listOf(GeoAnchor(0f, 0f, 45.7, 5.9), GeoAnchor(20_000f, 0f, 45.7001, 5.9002))

    @Test fun leReleveNaDeSensQuAvecDeuxAncres() {
        val cal = GeoCalibration()
        cal.replace(twoAnchors, survey)
        assertEquals(survey, cal.survey)
        assertEquals(3, cal.surveyPrecisionMeters)
        assertEquals(8, cal.surveyEdgePrecisionMeters)
        cal.replace(twoAnchors.take(1), survey)
        assertNull(cal.survey)
        cal.replace(twoAnchors, survey)
        cal.addAnchor(GeoAnchor(1f, 1f, 45.0, 6.0)) // « je suis ici »
        assertNull(cal.survey)
        cal.replace(twoAnchors, survey.copy(method = "laserScan"))
        assertNull("méthode inconnue : pas de ligne « 3 points »", cal.surveyPrecisionMeters)
        assertEquals(GeoCalibrationStatus.Anchors(2), GeoCalibrationStatus.of(cal, false))
        // Jamais écrit sans ses 2 ancres.
        assertNull(LocalMapper.calibrationDTO(twoAnchors.take(1), survey).survey)
        val enc = SectionCodec.encodeCalibration(GeoCalibrationDTO(LocalMapper.calibrationDTO(twoAnchors).anchors.take(1),
            LocalMapper.surveyDTO(survey)))
        assertFalse(enc.has("survey"))
    }

    @Test fun republierRenvoieLeReleveTelQuelDansLEnveloppe() {
        val payload = SectionPayload.Calibration(LocalMapper.calibrationDTO(twoAnchors, survey))
        val json = SectionCodec.encode(payload)
        val back = SectionCodec.decode(json) as SectionPayload.Calibration
        assertEquals(survey, LocalMapper.toSurvey(back.dto))
        assertEquals(twoAnchors, LocalMapper.toAnchors(back.dto))
        val inner = JSONObject(json).getJSONObject("calibration").getJSONObject("_0")
        assertTrue(inner.getJSONObject("survey").has("edgePrecision"))
        // Sans edgePrecision : la clé est OMISE (jamais `null`).
        val noEdge = SectionCodec.encodeCalibration(LocalMapper.calibrationDTO(twoAnchors, survey.copy(edgePrecision = null)))
        assertFalse(noEdge.getJSONObject("survey").has("edgePrecision"))
        // Relevé incomplet : ancres gardées, relevé ignoré (plus tolérant qu'iOS).
        val broken = JSONObject(SectionCodec.encodeCalibration(payload.dto).toString())
        broken.getJSONObject("survey").getJSONArray("points").getJSONObject(1).remove("residual")
        val dto = SectionCodec.decodeCalibration(broken)
        assertEquals(2, dto.anchors.size)
        assertNull(dto.survey)
    }

    // -------------------------------------------------- gardes de branchement

    private fun source(name: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/$name"), File("app/src/main/java/com/minou/mvrviewer/$name"))
            .first { it.isFile }.readText()

    @Test fun branchementDansLaVuePlan() {
        val plan = source("ui/PlanScreen.kt")
        // Le toucher va À L'ASSISTANT avant tout autre mode.
        val onTap = plan.indexOf("onTap = { tap ->")
        assertTrue(onTap > 0)
        assertTrue(plan.indexOf("cal3.tap(", onTap) in onTap until plan.indexOf("if (assignMode)", onTap))
        // Armer un autre mode ferme l'assistant (cadre, masquage, solo, mesure, « je suis ici »).
        assertTrue(Regex("closeThreePoint\\(\\)").findAll(plan).count() >= 7)
        // « Je suis ici » après un relevé : on repart de zéro.
        assertTrue(plan.contains("if (calibration.survey != null) calibration.reset()"))
        // Appliquer : persisté (relevé compris), poussé et journalisé comme les autres.
        assertTrue(plan.contains("saveCalibration(ctxPlan, projectKey, anchors, survey)"))
        assertTrue(plan.contains("calibration.replace(anchors, survey)"))
        assertEquals("threePointCalibration", ToolId.CALIBRATE_3.rawId)
        assertEquals(ToolId.CALIBRATE_3, ToolId.fromRawId("threePointCalibration"))
        assertEquals("geoCalibration3Plan", PanelLayoutStore.PanelID.GEO_CALIBRATION_3_PLAN.key)
        val scene = source("ui/SceneScreen.kt")
        assertTrue(scene.contains("calibration.replace(anchors, LocalMapper.toSurvey(p.dto))"))
        assertEquals(2, Regex("pushCalibration\\(calibration.anchors, calibration.survey\\)").findAll(scene).count())
        assertTrue(scene.contains("loadCalibrationWithSurvey"))
    }

    @Test fun rotationDuGenerateurDeTest() {
        // Sanité de l'outil : plan = R(θ)·ENU.
        val (lat, lon) = gpsOf(Vec2(10_000.0, 0.0))
        val e = GeoThreePoint.enu(lat, lon, origin.first, origin.second)
        val w = GeoThreePoint.rotate(e, theta) * 1000.0
        assertEquals(10_000.0, w.x, 1e-6)
        assertEquals(0.0, w.y, 1e-6)
        assertEquals(cos(theta), cos(theta), 0.0); assertEquals(sin(theta), sin(theta), 0.0)
    }
}
