package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.GeoCalibration
import com.minou.mvrviewer.mvr.GeoCalibrationStatus
import com.minou.mvrviewer.mvr.GeoThreePoint
import com.minou.mvrviewer.mvr.Vec2
import com.minou.mvrviewer.sync.LocalMapper
import com.minou.mvrviewer.sync.SectionCodec
import com.minou.mvrviewer.sync.SectionPayload
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/**
 * VECTEURS CANONIQUES de la calibration GPS en 3 points
 * (`port-vectors/calibration-3pts/`), produits par le VRAI code iOS compilé
 * (commit 85a3668, générateur `tests/fixtures/_ios-gen/` du dépôt web, commit
 * 8737a4a). Android doit obtenir les MÊMES sorties aux tolérances du fichier
 * (guide PORTAGE-cablage-calibration.md §1).
 *
 * Groupes rejoués : constantes, géodésie, moyenne, ajustement (tous les champs
 * ET l'écran de résultat en fr/en sur les vrais `strings.xml`), guidage (pas,
 * emprise, repères proposés), guidage en marche, forme synchronisée (arbres ET
 * formes canoniques à l'octet), compatibilité, effacement du relevé, modèle à
 * ancres (antiméridien compris), forme des nombres, textes. Seul `monteCarlo`
 * (facultatif : il faudrait reproduire le générateur aléatoire) n'est pas rejoué.
 */
class Calibration3ptsVectorsTest {

    companion object {
        private const val DIR = "port-vectors/calibration-3pts"
        private fun file(name: String) =
            File(Calibration3ptsVectorsTest::class.java.classLoader!!.getResource("$DIR/$name")!!.toURI())
        val root: JSONObject by lazy { JSONObject(file("vectors.json").readText()) }
        private const val EPOCH = 1_760_000_000.0
    }

    private val fr = TestRes.FR
    private val en = TestRes.EN

    // ------------------------------------------------------------------ outils

    private fun JSONObject.opt0(k: String): Any? = if (!has(k) || isNull(k)) null else get(k)
    private fun num(v: Any?): Double = (v as Number).toDouble()
    private fun vec(a: JSONArray) = Vec2(a.getDouble(0), a.getDouble(1))
    private fun vecOrNull(v: Any?): Vec2? = if (v == null || v == JSONObject.NULL) null else vec(v as JSONArray)

    private fun near(msg: String, expected: Double, actual: Double, tol: Double) {
        if (expected.isInfinite() || actual.isInfinite()) { assertEquals(msg, expected, actual, 0.0); return }
        assertTrue("$msg : attendu $expected, obtenu $actual (tolérance $tol)", abs(expected - actual) <= tol)
    }

    private fun nearRel(msg: String, expected: Double, actual: Double, rel: Double) =
        near(msg, expected, actual, rel * maxOf(1.0, abs(expected)))

    private fun nearVec(msg: String, expected: Vec2, actual: Vec2, tol: Double) {
        near("$msg.x", expected.x, actual.x, tol); near("$msg.y", expected.y, actual.y, tol)
    }

    private fun bounds(v: Any?): GeoThreePoint.Bounds? {
        if (v == null || v == JSONObject.NULL) return null
        val o = v as JSONObject
        return GeoThreePoint.Bounds(vec(o.getJSONArray("min")), vec(o.getJSONArray("max")))
    }

    private fun point(o: JSONObject) = GeoThreePoint.Point(
        o.getDouble("worldX"), o.getDouble("worldY"), o.getDouble("latitude"), o.getDouble("longitude"),
        o.getDouble("accuracy")
    )

    private fun points(a: JSONArray) = (0 until a.length()).map { point(a.getJSONObject(it)) }

    private fun ints(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }

    /** Phrase attendue {key, args, fr, en} : mêmes arguments, mêmes textes fr et en. */
    private fun assertPhrase(msg: String, expected: JSONObject, actual: CalText) {
        if (expected.has("args")) {
            val args = expected.getJSONArray("args")
            assertEquals("$msg (arguments)", (0 until args.length()).map { args.getInt(it) },
                actual.args.map { (it as Number).toInt() })
        }
        assertEquals("$msg (fr)", expected.getString("fr"), fr.res(actual.id, actual.args))
        assertEquals("$msg (en)", expected.getString("en"), en.res(actual.id, actual.args))
    }

    private fun anchorsOf(a: JSONArray) = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        GeoAnchor(o.getDouble("worldX").toFloat(), o.getDouble("worldY").toFloat(),
            o.getDouble("latitude"), o.getDouble("longitude"))
    }

    // ------------------------------------------------- comparaison d'arbres JSON

    /** Arbres JSON : mêmes clés, mêmes valeurs ; worldX/worldY égaux en float32. */
    private fun assertTree(path: String, expected: Any?, actual: Any?, key: String? = null) {
        when (expected) {
            is JSONObject -> {
                assertTrue("$path : objet attendu", actual is JSONObject)
                val a = actual as JSONObject
                assertEquals("$path : clés", expected.keys().asSequence().toSortedSet(), a.keys().asSequence().toSortedSet())
                for (k in expected.keys()) assertTree("$path.$k", expected.get(k), a.get(k), k)
            }
            is JSONArray -> {
                assertTrue("$path : tableau attendu", actual is JSONArray)
                val a = actual as JSONArray
                assertEquals("$path : longueur", expected.length(), a.length())
                for (i in 0 until expected.length()) assertTree("$path[$i]", expected.get(i), a.get(i), key)
            }
            is Number -> {
                assertTrue("$path : nombre attendu, obtenu $actual", actual is Number)
                val e = expected.toDouble(); val v = (actual as Number).toDouble()
                if (key == "worldX" || key == "worldY") assertEquals("$path (float32)", e.toFloat(), v.toFloat())
                else assertEquals("$path", e, v, 0.0)
            }
            else -> assertEquals(path, expected, actual)
        }
    }

    // ------------------------------------------- forme canonique (guide §3.5)

    /** Texte d'un nombre comme JSONEncoder iOS (port du sérialiseur JS du guide). */
    private fun swiftNumber(x: Double): String {
        if (x == 0.0) return if (1.0 / x < 0) "-0" else "0"
        val a = abs(x)
        val bd = BigDecimal(java.lang.Double.toString(x)).stripTrailingZeros()
        if (a >= 1e-4 && a <= 9007199254740992.0) return bd.toPlainString()
        val u = bd.unscaledValue().abs().toString()
        val n = (u.length - 1) - bd.scale()
        val mant = if (u.length > 1) u[0] + "." + u.substring(1) else u
        val sign = if (x < 0) "-" else ""
        return sign + mant + "e" + (if (n < 0) "-" else "+") + abs(n).toString().padStart(2, '0')
    }

    /** Chiffres les plus courts du FLOAT (« 10103.667 »). */
    private fun shortestFloat32(x: Double): Double = java.lang.Float.toString(x.toFloat()).toDouble()

    private fun canonical(v: Any?, key: String? = null): String = when (v) {
        is Number -> {
            val d = v.toDouble()
            swiftNumber(if (key == "worldX" || key == "worldY") shortestFloat32(d) else d)
        }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it), key) }
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(v.get(it), it)
        }
        is String -> JSONObject.quote(v)
        is Boolean -> v.toString()
        else -> "null"
    }

    // ---------------------------------------------------------- copie conforme

    @Test fun laCopieEstCelleDuManifeste() {
        val parts = file("MANIFEST.sha256").readText().trim().split(Regex("\\s+"))
        assertEquals("vectors.json", parts[1])
        val sha = MessageDigest.getInstance("SHA-256").digest(file("vectors.json").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("SHA-256 de la copie = celui du manifeste", parts[0], sha)
        assertEquals("mvrviewer.port-vectors.calibration-3pts/1", root.getString("schema"))
        assertEquals("85a3668", root.getJSONObject("source").getString("commit").take(7))
    }

    // ----------------------------------------------------------------- constantes

    @Test fun constantes() {
        val c = root.getJSONObject("constants")
        val t = GeoThreePoint
        val expected = mapOf(
            "earthRadius" to t.earthRadius, "minMeasureSeconds" to t.minMeasureSeconds,
            "maxMeasureSeconds" to t.maxMeasureSeconds, "forceAfterSeconds" to t.forceAfterSeconds,
            "maxSampleAccuracy" to t.maxSampleAccuracy, "staleFixDelay" to t.staleFixDelay,
            "staleFixMaxAge" to t.staleFixMaxAge, "defaultStepLength" to t.defaultStepLength,
            "stepLengthIncrement" to t.stepLengthIncrement, "baseMin" to t.baseMin, "baseMax" to t.baseMax,
            "baseDefault" to t.baseDefault, "minTolerance" to t.minTolerance, "minBase" to t.minBase,
            "minAngleWarning" to t.minAngleWarning, "minScaleTolerance" to t.minScaleTolerance,
            "syntheticAnchorSpan" to t.syntheticAnchorSpan, "residualCaution" to t.residualCaution,
            "weakEdgeRatio" to t.weakEdgeRatio, "landmarkScoreRatio" to t.landmarkScoreRatio,
            "landmarkSlack" to t.landmarkSlack, "looseSampleAccuracy" to t.looseSampleAccuracy
        )
        for ((k, v) in expected) assertEquals(k, c.getDouble(k), v, 0.0)
        assertEquals(c.getInt("minDistinctFixes"), t.minDistinctFixes)
        assertEquals(c.getInt("maxLandmarks"), t.maxLandmarks)
        val r = c.getJSONArray("stepLengthRange")
        assertEquals(r.getDouble(0), t.stepLengthMin, 0.0)
        assertEquals(r.getDouble(1), t.stepLengthMax, 0.0)
    }

    // ------------------------------------------------------------------ géodésie

    @Test fun geodesie() {
        val g = root.getJSONObject("geodesy")
        g.getJSONArray("longitudeDelta").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                near("longitudeDelta[$i]", o.getDouble("expect"),
                    GeoThreePoint.longitudeDelta(o.getDouble("longitude"), o.getDouble("origin")), 1e-10)
            }
        }
        g.getJSONArray("normalizedLongitude").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                near("normalizedLongitude[$i]", o.getDouble("expect"),
                    GeoThreePoint.normalizedLongitude(o.getDouble("longitude")), 1e-10)
            }
        }
        g.getJSONArray("enu").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                nearVec("enu[$i]", vec(o.getJSONArray("expect")),
                    GeoThreePoint.enu(o.getDouble("latitude"), o.getDouble("longitude"),
                        o.getDouble("originLatitude"), o.getDouble("originLongitude")), 1e-6)
            }
        }
        g.getJSONArray("latLon").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val (lat, lon) = GeoThreePoint.latLon(vec(o.getJSONArray("enu")),
                    o.getDouble("originLatitude"), o.getDouble("originLongitude"))
                val e = o.getJSONObject("expect")
                near("latLon[$i].lat", e.getDouble("latitude"), lat, 1e-10)
                near("latLon[$i].lon", e.getDouble("longitude"), lon, 1e-10)
            }
        }
        g.getJSONArray("rotate").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                nearVec("rotate[$i]", vec(o.getJSONArray("expect")),
                    GeoThreePoint.rotate(vec(o.getJSONArray("v")), o.getDouble("angle")), 1e-12)
            }
        }
    }

    // ------------------------------------------------------------------- moyenne

    @Test fun moyenneRobuste() {
        val a = root.getJSONArray("average")
        assertEquals(9, a.length())
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val id = c.getString("id")
            val input = c.getJSONArray("input")
            val samples = (0 until input.length()).map {
                val s = input.getJSONObject(it)
                GeoThreePoint.Sample(s.getDouble("latitude"), s.getDouble("longitude"), s.getDouble("accuracy"))
            }
            val r = GeoThreePoint.average(samples)
            val e = c.opt0("expect") as JSONObject?
            if (e == null) { assertNull(id, r); continue }
            assertNotNull(id, r!!)
            near("$id.lat", e.getDouble("latitude"), r.latitude, 1e-10)
            near("$id.lon", e.getDouble("longitude"), r.longitude, 1e-10)
            near("$id.accuracy", e.getDouble("accuracy"), r.accuracy, 1e-6)
            assertEquals("$id.used", e.getInt("used"), r.used)
            assertEquals("$id.rejected", e.getInt("rejected"), r.rejected)
        }
    }

    // ---------------------------------------------------------------- ajustement

    private fun checkFit(id: String, e: JSONObject, f: GeoThreePoint.Fit, b: GeoThreePoint.Bounds?, pts: List<GeoThreePoint.Point>) {
        near("$id.originLatitude", e.getDouble("originLatitude"), f.originLatitude, 0.0)
        near("$id.originLongitude", e.getDouble("originLongitude"), f.originLongitude, 0.0)
        near("$id.rotation", e.getDouble("rotation"), f.rotation, 1e-9)
        nearVec("$id.translation", vec(e.getJSONArray("translation")), f.translation, 1e-6)
        near("$id.freeScale", e.getDouble("freeScale"), f.freeScale, 1e-9)
        near("$id.scaleDeviationPercent", e.getDouble("scaleDeviationPercent"), f.scaleDeviationPercent, 1e-7)
        val res = e.getJSONArray("residuals")
        assertEquals("$id.residuals", res.length(), f.residuals.size)
        for (k in 0 until res.length()) near("$id.residuals[$k]", res.getDouble(k), f.residuals[k], 1e-6)
        val fw = e.getJSONArray("fittedWorld")
        for (k in 0 until fw.length()) nearVec("$id.fittedWorld[$k]", vec(fw.getJSONArray(k)), f.fittedWorld[k], 1e-3)
        near("$id.precision", e.getDouble("precision"), f.precision, 1e-6)
        near("$id.precisionFromResiduals", e.getDouble("precisionFromResiduals"), f.precisionFromResiduals, 1e-6)
        near("$id.precisionFromGPS", e.getDouble("precisionFromGPS"), f.precisionFromGPS, 1e-6)
        nearRel("$id.noiseScale2", e.getDouble("noiseScale2"), f.noiseScale2, 1e-9)
        nearRel("$id.weightSum", e.getDouble("weightSum"), f.weightSum, 1e-9)
        nearRel("$id.rotationInformation", e.getDouble("rotationInformation"), f.rotationInformation, 1e-9)
        nearVec("$id.center", vec(e.getJSONArray("center")), f.center, 1e-6)
        near("$id.baseLength", e.getDouble("baseLength"), f.baseLength, 1e-6)
        near("$id.minAngleDegrees", e.getDouble("minAngleDegrees"), f.minAngleDegrees, 1e-7)
        val o = e.opt0("outlier") as JSONObject?
        if (o == null) assertNull("$id.outlier", f.outlier) else {
            val fo = f.outlier
            assertNotNull("$id.outlier", fo)
            assertEquals("$id.outlier.index", o.getInt("index"), fo!!.index)
            near("$id.outlier.offset", o.getDouble("offset"), fo.offset, 1e-6)
            assertEquals("$id.outlier.certain", o.getBoolean("certain"), fo.certain)
            assertEquals("$id.outlier.suspects", ints(o.getJSONArray("suspects")), fo.suspects)
        }
        assertEquals("$id.scaleSuspicious", e.getBoolean("scaleSuspicious"), f.scaleSuspicious)
        assertEquals("$id.pointsTooClose", e.getBoolean("pointsTooClose"), f.pointsTooClose)
        assertEquals("$id.nearlyAligned", e.getBoolean("nearlyAligned"), f.nearlyAligned)
        assertEquals("$id.consistent", e.getBoolean("consistent"), f.consistent)
        assertEquals("$id.largeResiduals", e.getBoolean("largeResiduals"), f.largeResiduals)
        near("$id.detectionLimit", e.getDouble("detectionLimit"), f.detectionLimit, 1e-6)
        assertEquals("$id.pointCount", e.getInt("pointCount"), f.pointCount)
        near("$id.edgePrecision", e.getDouble("edgePrecision"), f.edgePrecision(b), 1e-6)
        val pe = e.getJSONArray("predictedError")
        for (k in 0 until pe.length()) {
            val q = pe.getJSONObject(k)
            near("$id.predictedError[$k]", q.getDouble("value"), f.predictedError(vec(q.getJSONArray("at"))), 1e-6)
        }
        val sa = e.getJSONArray("syntheticAnchors")
        val anchors = GeoThreePoint.syntheticAnchors(f, pts)
        assertEquals("$id.syntheticAnchors", sa.length(), anchors.size)
        for (k in 0 until sa.length()) {
            val q = sa.getJSONObject(k)
            assertEquals("$id.anchor[$k].worldX", q.getDouble("worldX").toFloat(), anchors[k].worldX)
            assertEquals("$id.anchor[$k].worldY", q.getDouble("worldY").toFloat(), anchors[k].worldY)
            near("$id.anchor[$k].lat", q.getDouble("latitude"), anchors[k].latitude, 1e-10)
            near("$id.anchor[$k].lon", q.getDouble("longitude"), anchors[k].longitude, 1e-10)
        }
    }

    private fun actionKind(s: String) = when (s) {
        "apply" -> CalAction.Kind.APPLY
        "applyAnyway" -> CalAction.Kind.APPLY_ANYWAY
        "discard" -> CalAction.Kind.DISCARD
        "redo" -> CalAction.Kind.REDO
        "addControl" -> CalAction.Kind.ADD_CONTROL
        "restart" -> CalAction.Kind.RESTART
        else -> error("action inconnue : $s")
    }

    private fun assertAction(msg: String, expected: JSONObject, actual: CalAction) {
        assertEquals("$msg (action)", actionKind(expected.getString("action")), actual.kind)
        if (expected.has("point")) assertEquals("$msg (point)", expected.getInt("point"), actual.slot!! + 1)
        else assertNull("$msg (point)", actual.slot)
        assertPhrase(msg, expected.getJSONObject("text"), actual.text)
    }

    private fun assertStatus(msg: String, expected: JSONObject, cal: GeoCalibration) {
        assertEquals("$msg.surveyPrecisionMeters", (expected.opt0("surveyPrecisionMeters") as Number?)?.toInt(), cal.surveyPrecisionMeters)
        assertEquals("$msg.surveyEdgePrecisionMeters", (expected.opt0("surveyEdgePrecisionMeters") as Number?)?.toInt(), cal.surveyEdgePrecisionMeters)
        assertPhrase("$msg.text", expected.getJSONObject("text"),
            ThreePointDisplay.statusText(GeoCalibrationStatus.of(cal, locationDenied = false)))
    }

    private fun checkDisplay(id: String, d: JSONObject, f: GeoThreePoint.Fit?, b: GeoThreePoint.Bounds?,
                             pts: List<GeoThreePoint.Point>) {
        val ctx = d.getJSONObject("context")
        val hasControl = ctx.getBoolean("hasControl")
        val slots = ints(ctx.getJSONArray("slots"))
        val r = ThreePointDisplay.result(f, f?.edgePrecision(b), hasControl, slots)
        if (f == null) {
            assertNull("$id.residuals", r.residuals)
        } else {
            assertEquals("$id.precisionNearMeters", d.getInt("precisionNearMeters"), r.precisionNearMeters)
            assertEquals("$id.edgeMeters", (d.opt0("edgeMeters") as Number?)?.toInt(), r.edgeMeters)
            assertEquals("$id.weakEdges", d.getBoolean("weakEdges"), r.weakEdges)
            val lines = d.getJSONArray("precisionLines")
            assertEquals("$id.precisionLines", lines.length(), r.precisionLines.size)
            for (k in 0 until lines.length()) assertPhrase("$id.precisionLines[$k]", lines.getJSONObject(k), r.precisionLines[k])
            val dev = d.getJSONObject("deviations")
            assertEquals("$id.deviations (fr)", dev.getString("fr"),
                fr.get("cal3_deviations", ThreePointDisplay.residualsText(r.residuals!!, Locale.FRENCH)))
            assertEquals("$id.deviations (en)", dev.getString("en"),
                en.get("cal3_deviations", ThreePointDisplay.residualsText(r.residuals, Locale.ENGLISH)))
            assertEquals("$id.suspectPoints", ints(d.getJSONArray("suspectPoints")), r.suspectPoints)
        }
        val w = d.getJSONArray("warnings")
        assertEquals("$id.warnings ${r.warnings}", w.length(), r.warnings.size)
        for (k in 0 until w.length()) assertPhrase("$id.warnings[$k]", w.getJSONObject(k), r.warnings[k].text)
        assertAction("$id.primary", d.getJSONObject("primary"), r.primary)
        val sec = d.getJSONArray("secondary")
        assertEquals("$id.secondary", sec.length(), r.secondary.size)
        for (k in 0 until sec.length()) assertAction("$id.secondary[$k]", sec.getJSONObject(k), r.secondary[k])
        val applied = d.opt0("appliedStatus") as JSONObject?
        if (applied != null && f != null) {
            val (anchors, survey) = GeoCalibration.fromThreePoint(f, pts, f.edgePrecision(b), EPOCH)
            val cal = GeoCalibration().apply { replace(anchors, survey) }
            assertStatus("$id.appliedStatus", applied, cal)
        }
    }

    @Test fun ajustementEtEcranDeResultat() {
        val a = root.getJSONArray("fit")
        assertEquals(35, a.length())
        var displays = 0
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val id = c.getString("id")
            val input = c.getJSONObject("input")
            val pts = points(input.getJSONArray("points"))
            val b = bounds(input.opt("bounds"))
            val f = GeoThreePoint.fit(pts)
            val ex = c.getJSONObject("expect")
            val ef = ex.opt0("fit") as JSONObject?
            if (ef == null) assertNull(id, f) else checkFit(id, ef, f!!, b, pts)
            (ex.opt0("display") as JSONObject?)?.let { checkDisplay(id, it, f, b, pts); displays++ }
        }
        assertEquals("écrans de résultat rejoués", 33, displays)
    }

    // -------------------------------------------------------------------- guidage

    private fun landmarks(v: Any?): List<Vec2> {
        if (v == null || v == JSONObject.NULL) return emptyList()
        val o = v as JSONObject
        if (o.has("points")) { val a = o.getJSONArray("points"); return (0 until a.length()).map { vec(a.getJSONArray(it)) } }
        val gen = o.getString("generator")
        val out = ArrayList<Vec2>()
        if (gen.startsWith("pour x")) {
            var x = -120_000.0
            while (x <= 140_000) {
                var y = -110_000.0
                while (y <= 50_000) { out.add(Vec2(x + 1_234, y + 777)); y += 9_000 }
                x += 7_000
            }
        } else if (gen.startsWith("i = 0")) {
            for (i in 0L until 25_000L) {
                out.add(Vec2((-120_000 + (i * 7_919) % 260_000).toDouble(), (-110_000 + (i * 104_729) % 160_000).toDouble()))
            }
        } else fail("générateur inconnu : $gen")
        assertEquals("nombre d'éléments générés", o.getInt("count"), out.size)
        return out
    }

    @Test fun guidagePasEmpriseEtReperes() {
        val g = root.getJSONObject("guidance")
        val steps = g.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val o = steps.getJSONObject(i)
            assertEquals("steps[$i]", o.getInt("expect"), GeoThreePoint.steps(o.getDouble("meters"), o.getDouble("stepLength")))
        }
        val bs = g.getJSONArray("bounds")
        for (i in 0 until bs.length()) {
            val o = bs.getJSONObject(i)
            val b = bounds(o.opt("bounds"))
            val e = o.getJSONObject("expect")
            assertEquals("desiredBase[$i]", e.getDouble("desiredBase"), GeoThreePoint.desiredBase(b), 0.0)
            if (b == null) continue
            assertEquals("isUsable[$i]", e.getBoolean("isUsable"), b.isUsable)
            val ins = bounds(e.get("inset"))!!
            nearVec("inset.min[$i]", ins.min, b.inset.min, 1e-9)
            nearVec("inset.max[$i]", ins.max, b.inset.max, 1e-9)
        }
        val ts = g.getJSONArray("targets")
        assertEquals(13, ts.length())
        for (i in 0 until ts.length()) {
            val o = ts.getJSONObject(i)
            val id = o.getString("id")
            val b = bounds(o.opt("bounds"))
            val inp = o.getJSONObject("input")
            val marks = landmarks(o.opt("landmarks"))
            val t = when (o.getString("kind")) {
                "second" -> GeoThreePoint.suggestSecondTarget(vec(inp.getJSONArray("p1")), b, marks)
                "third" -> GeoThreePoint.suggestThirdTarget(vec(inp.getJSONArray("p1")), vec(inp.getJSONArray("p2")), b, marks)
                "control" -> {
                    val pa = inp.getJSONArray("points")
                    GeoThreePoint.suggestControlTarget((0 until pa.length()).map { vec(pa.getJSONArray(it)) },
                        vec(inp.getJSONArray("from")), b, marks)
                }
                else -> error(id)
            }
            val e = o.getJSONObject("expect")
            nearVec("$id.target", vec(e.getJSONArray("target")), t, 1e-6)
            val dep = vec(o.getJSONArray("departure"))
            val marker = o.getInt("marker")
            val ap = ThreePointDisplay.approach(t, dep, marker - 1, GeoThreePoint.defaultStepLength)
            near("$id.distance", e.getDouble("distanceFromDepartureM"), ap.meters, 1e-6)
            assertEquals("$id.steps", e.getInt("stepsAtDefaultLength"), ap.steps)
            assertEquals("$id.approxMeters", e.getInt("approxMeters"), GeoThreePoint.roundToInt(ap.meters))
            (e.opt0("insideInset") as Boolean?)?.let { assertEquals("$id.insideInset", it, b!!.inset.contains(t)) }
            assertEquals("$id.isLandmark", e.getBoolean("isLandmark"), marks.contains(t))
            assertPhrase("$id.instruction", e.getJSONObject("instruction"), ap.instruction)
            assertPhrase("$id.arrowLabel", e.getJSONObject("arrowLabel"), ap.arrowLabel)
            assertPhrase("$id.distanceLabel", e.getJSONObject("distanceLabel"), ap.distanceLabel)
            if (e.has("minAngleWithP1P2")) {
                near("$id.minAngle", e.getDouble("minAngleWithP1P2"),
                    GeoThreePoint.minAngleDegrees(vec(inp.getJSONArray("p1")), vec(inp.getJSONArray("p2")), t), 1e-7)
            }
        }
    }

    @Test fun guidageEnMarche() {
        val cases = root.getJSONObject("liveGuidance").getJSONArray("cases")
        assertEquals(4, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val inp = c.getJSONObject("input")
            val measured = points(inp.getJSONArray("measured"))
            val fix = inp.getJSONObject("fix")
            val g = ThreePointDisplay.liveGuidance(
                measured, vec(inp.getJSONArray("target")), point(inp.getJSONObject("departure")), false,
                fix.getDouble("latitude"), fix.getDouble("longitude"), inp.getDouble("stepLength")
            )!!
            val e = c.getJSONObject("expect")
            val prov = if (measured.size >= 2) GeoThreePoint.fit(measured) else null
            assertEquals("$id.provisionalConsistent", e.opt0("provisionalConsistent") as Boolean?, prov?.consistent)
            when (e.getString("mode")) {
                "remaining" -> {
                    assertTrue(id, g.remaining)
                    nearVec("$id.provisionalWorld", vec(e.getJSONArray("provisionalWorld")), g.provisionalWorld!!, 1e-3)
                    near("$id.remainingMeters", e.getDouble("remainingMeters"), g.meters, 1e-6)
                }
                "walked" -> {
                    assertFalse(id, g.remaining)
                    assertNull(id, g.provisionalWorld)
                    near("$id.walkedMeters", e.getDouble("walkedMeters"), g.meters, 1e-6)
                }
                else -> fail(id)
            }
            assertPhrase("$id.text", e.getJSONObject("text"), g.text)
        }
    }

    // ------------------------------------------------------- forme synchronisée

    @Test fun formeSynchroniseeAncresSynthetiquesEtReleve() {
        val cases = root.getJSONObject("sync").getJSONArray("cases")
        assertEquals(5, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val inp = c.getJSONObject("input")
            val pts = points(inp.getJSONArray("points"))
            val b = bounds(inp.opt("bounds"))
            val f = GeoThreePoint.fit(pts)!!
            val (anchors, survey) = GeoCalibration.fromThreePoint(f, pts, f.edgePrecision(b), inp.getDouble("epoch"))
            val e = c.getJSONObject("expect")
            val ea = e.getJSONArray("anchors")
            for (k in 0 until ea.length()) {
                val q = ea.getJSONObject(k)
                assertEquals("$id.anchor[$k].worldX", q.getDouble("worldX").toFloat(), anchors[k].worldX)
                assertEquals("$id.anchor[$k].worldY", q.getDouble("worldY").toFloat(), anchors[k].worldY)
                near("$id.anchor[$k].lat", q.getDouble("latitude"), anchors[k].latitude, 1e-10)
                near("$id.anchor[$k].lon", q.getDouble("longitude"), anchors[k].longitude, 1e-10)
                val txt = e.getJSONArray("anchorsFloat32Text").getJSONObject(k)
                assertEquals("$id.anchor[$k].worldX (texte)", txt.getString("worldX"), anchors[k].worldX.toString())
                assertEquals("$id.anchor[$k].worldY (texte)", txt.getString("worldY"), anchors[k].worldY.toString())
            }
            // Section : arbre attendu (les lat/lon et résidus calculés ici sont
            // comparés à la tolérance, puis on compare l'arbre de l'ENCODEUR Android
            // à celui d'iOS en réinjectant les valeurs attendues — c'est la FORME
            // qui est testée ici, les nombres l'ont été ci-dessus).
            val dto = LocalMapper.calibrationDTO(anchors, survey)
            val section = JSONObject(SectionCodec.encodeCalibration(dto).toString())
            val expectedSection = e.getJSONObject("section")
            assertSameNumbers("$id.section", expectedSection, section)
            val exactDto = SectionCodec.decodeCalibration(expectedSection)
            val rewritten = JSONObject(SectionCodec.encodeCalibration(exactDto).toString())
            assertTree("$id.section", expectedSection, rewritten)
            assertEquals("$id.sectionCanonical", e.getString("sectionCanonical"), canonical(rewritten))
            // Enveloppe Firestore {"calibration":{"_0":…}}.
            val payload = JSONObject(SectionCodec.encode(SectionPayload.Calibration(exactDto)))
            assertEquals("$id.payloadJSONCanonical", e.getString("payloadJSONCanonical"), canonical(payload))
            // Ligne d'état une fois appliqué.
            val cal = GeoCalibration().apply { replace(anchors, survey) }
            assertStatus("$id.status", e.getJSONObject("status"), cal)
        }
    }

    /** Mêmes clés et mêmes nombres à la tolérance (lat/lon 1e-10, mètres 1e-6, float32 exacts). */
    private fun assertSameNumbers(path: String, expected: Any?, actual: Any?, key: String? = null) {
        when (expected) {
            is JSONObject -> {
                val a = actual as JSONObject
                assertEquals("$path : clés", expected.keys().asSequence().toSortedSet(), a.keys().asSequence().toSortedSet())
                for (k in expected.keys()) assertSameNumbers("$path.$k", expected.get(k), a.get(k), k)
            }
            is JSONArray -> {
                val a = actual as JSONArray
                assertEquals("$path : longueur", expected.length(), a.length())
                for (i in 0 until expected.length()) assertSameNumbers("$path[$i]", expected.get(i), a.get(i), key)
            }
            is Number -> {
                val e = expected.toDouble(); val v = num(actual)
                when (key) {
                    "worldX", "worldY" -> assertEquals(path, e.toFloat(), v.toFloat())
                    "latitude", "longitude" -> near(path, e, v, 1e-10)
                    "epoch" -> assertEquals(path, e, v, 0.0)
                    "freeScale" -> near(path, e, v, 1e-9)
                    else -> near(path, e, v, 1e-6)
                }
            }
            else -> assertEquals(path, expected, actual)
        }
    }

    @Test fun compatibiliteLectureEtReecriture() {
        val a = root.getJSONArray("compat")
        assertEquals(10, a.length())
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val id = c.getString("id")
            val e = c.getJSONObject("expect")
            val dto = SectionCodec.decodeCalibration(JSONObject(c.getString("input")))
            val cal = GeoCalibration().apply { replace(LocalMapper.toAnchors(dto), LocalMapper.toSurvey(dto)) }
            if (!e.getBoolean("decodeOk")) {
                // iOS rejette toute la section ; Android est PLUS TOLÉRANT (écart permis,
                // guide §3.5) : ancres gardées, relevé incomplet ignoré, jamais réécrit.
                assertEquals("$id : ancres gardées", 2, cal.anchors.size)
                assertNull("$id : relevé incomplet ignoré", cal.survey)
                val rw = JSONObject(SectionCodec.encodeCalibration(LocalMapper.calibrationDTO(cal.anchors, cal.survey)).toString())
                assertFalse("$id : jamais réécrit", rw.has("survey"))
                continue
            }
            assertEquals("$id.anchorCount", e.getInt("anchorCount"), cal.anchors.size)
            assertEquals("$id.surveyKept", e.getBoolean("surveyKept"), cal.survey != null)
            assertEquals("$id.surveyMethod", e.opt0("surveyMethod") as String?, cal.survey?.method)
            val rw = JSONObject(SectionCodec.encodeCalibration(LocalMapper.calibrationDTO(cal.anchors, cal.survey)).toString())
            assertTree("$id.rewritten", e.getJSONObject("rewritten"), rw)
            assertEquals("$id.rewrittenCanonical", e.getString("rewrittenCanonical"), canonical(rw))
            assertStatus("$id.status", e.getJSONObject("status"), cal)
        }
    }

    @Test fun uneAutreMethodeEffaceLeReleve() {
        val o = root.getJSONObject("otherMethodClearsSurvey")
        val dto = SectionCodec.decodeCalibration(o.getJSONObject("before"))
        val cal = GeoCalibration().apply { replace(LocalMapper.toAnchors(dto), LocalMapper.toSurvey(dto)) }
        assertNotNull("relevé relu", cal.survey)
        val add = o.getJSONObject("addAnchor")
        cal.addAnchor(GeoAnchor(add.getDouble("worldX").toFloat(), add.getDouble("worldY").toFloat(),
            add.getDouble("latitude"), add.getDouble("longitude")))
        val rw = JSONObject(SectionCodec.encodeCalibration(LocalMapper.calibrationDTO(cal.anchors, cal.survey)).toString())
        assertTree("otherMethod", o.getJSONObject("expect"), rw)
        assertEquals(o.getString("expectCanonical"), canonical(rw))
        assertStatus("otherMethod.status", o.getJSONObject("expectStatus"), cal)
        // Réinitialiser efface aussi le relevé.
        cal.replace(LocalMapper.toAnchors(dto), LocalMapper.toSurvey(dto))
        cal.reset()
        assertNull(cal.survey)
    }

    // ------------------------------------------------------------ modèle à ancres

    @Test fun modeleAAncresAntimeridienCompris() {
        val a = root.getJSONArray("anchorModel")
        assertEquals(5, a.length())
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val id = c.getString("id")
            val cal = GeoCalibration().apply { anchorsOf(c.getJSONArray("anchors")).forEach { addAnchor(it) } }
            val wp = c.getJSONArray("worldPosition")
            for (k in 0 until wp.length()) {
                val q = wp.getJSONObject(k)
                val r = cal.worldPosition(q.getDouble("latitude"), q.getDouble("longitude"))!!
                val ex = q.getJSONArray("expect")
                near("$id.worldPosition[$k].x", ex.getDouble(0), r.first.toDouble(), 0.01)
                near("$id.worldPosition[$k].y", ex.getDouble(1), r.second.toDouble(), 0.01)
            }
            val ll = c.getJSONArray("latLonForWorld")
            for (k in 0 until ll.length()) {
                val q = ll.getJSONObject(k)
                val w = q.getJSONArray("world")
                val r = cal.latLonOf(w.getDouble(0).toFloat(), w.getDouble(1).toFloat())!!
                val ex = q.getJSONObject("expect")
                near("$id.latLon[$k].lat", ex.getDouble("latitude"), r.first, 1e-9)
                near("$id.latLon[$k].lon", ex.getDouble("longitude"), r.second, 1e-9)
            }
            near("$id.planMillimetersPerMeter", c.getDouble("planMillimetersPerMeter"),
                cal.planMillimeters(1.0).toFloat().toDouble(), 1e-3)
            val dir = c.getJSONArray("worldDirectionHeading90")
            val d = cal.worldDirection(90.0)!!
            near("$id.worldDirection.x", dir.getDouble(0), d.first.toDouble(), 1e-6)
            near("$id.worldDirection.y", dir.getDouble(1), d.second.toDouble(), 1e-6)
        }
    }

    // --------------------------------------------------------- forme des nombres

    @Test fun formeDesNombresCommeJSONEncoder() {
        val nf = root.getJSONObject("numberFormat")
        val d = nf.getJSONArray("double")
        for (i in 0 until d.length()) {
            val o = d.getJSONObject(i)
            assertEquals("double[$i]", o.getString("ios"), swiftNumber(o.getDouble("value")))
        }
        val f = nf.getJSONArray("float32")
        for (i in 0 until f.length()) {
            val o = f.getJSONObject(i)
            assertEquals("float32[$i]", o.getString("ios"), swiftNumber(shortestFloat32(o.getDouble("value"))))
        }
    }

    // --------------------------------------------------------------------- textes

    /** Clés iOS (ordre du fichier) → ressources Android. */
    private val textResources = listOf(
        "tool_calibrate_3", "cal3_title", "cal3_point_of_3", "cal3_result", "cal3_gps_accuracy",
        "cal3_waiting_gps", "cal3_location_denied", "cal3_first_instruction", "cal3_walk_steps",
        "cal3_approx_meters", "cal3_arrow_steps", "cal3_walked", "cal3_remaining", "cal3_arrived_hint",
        "cal3_step_length", "cal3_go_back", "cal3_hold_still", "cal3_tap_again", "cal3_low_accuracy",
        "cal3_use_anyway", "cal3_point_measured", "cal3_next_point", "cal3_compute", "cal3_measure_again",
        "cal3_precision_estimated", "cal3_precision_near", "cal3_precision_edges", "cal3_deviations",
        "cal3_point_wrong", "cal3_two_suspects", "cal3_several_wrong", "cal3_most_likely",
        "cal3_large_residuals", "cal3_weak_edges", "cal3_add_control", "cal3_discard_point",
        "cal3_control_point", "cal3_control_hint", "cal3_scale_warning", "cal3_too_close",
        "cal3_nearly_aligned", "cal3_coincident", "cal3_apply", "cal3_apply_anyway", "cal3_redo_point",
        "cal3_restart", "cal3_status_three", "cal3_status_three_edges"
    )

    /** Gabarit Android → forme iOS : `%1$d` → `%lld`, `%1$s` → `%@`. */
    private fun iosTemplate(s: String) =
        s.replace(Regex("%[0-9]\\\$d"), "%lld").replace(Regex("%[0-9]\\\$s"), "%@")

    @Test fun textesFrEnIdentiquesAuCatalogueIos() {
        val t = root.getJSONArray("texts")
        assertEquals(textResources.size, t.length())
        for (i in 0 until t.length()) {
            val o = t.getJSONObject(i)
            val name = textResources[i]
            assertEquals("$name (fr)", o.getString("fr"), iosTemplate(fr.strings.getValue(name)))
            assertEquals("$name (en)", o.getString("en"), iosTemplate(en.strings.getValue(name)))
        }
        // Les textes du panneau d'état déjà connus d'iOS (compat) existent aussi.
        assertEquals("Non calibré", fr.get("calib_status_none"))
        assertEquals("Not calibrated", en.get("calib_status_none"))
        assertEquals(R.string.cal3_apply, CalText(R.string.cal3_apply).id)
    }
}
