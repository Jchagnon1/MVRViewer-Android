package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect
import com.minou.mvrviewer.R
import com.minou.mvrviewer.sync.DmxCablingCodec
import com.minou.mvrviewer.sync.DmxCablingDTO
import com.minou.mvrviewer.sync.PowerCablingCodec
import com.minou.mvrviewer.sync.PowerCablingDTO
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * VECTEURS CANONIQUES du câblage sur le plan (`port-vectors/cabling-plan/`),
 * produits par le VRAI code iOS compilé (commit 85a3668, générateur
 * `tests/fixtures/_ios-gen/` du dépôt web). Android doit obtenir EXACTEMENT les
 * mêmes sorties (guide PORTAGE-cablage-calibration.md §1).
 *
 * Tous les groupes sont rejoués : `voieOrder`, `labels`, `sequences` (sur le DTO
 * pur ET sur les états Compose réels), `status` (+ `display` en fr et en en, sur
 * les vrais `strings.xml`), `memory`, `tabOpening`, `returnAnchor`,
 * `listGeometry` (modèle iOS et variante « en-tête permis »), `assignBar`,
 * `colors` et `texts`.
 */
class CablingPlanVectorsTest {

    @Before fun pro() { ProAccess.forceForTests(true) }
    @After fun resetPro() { ProAccess.forceForTests(null) }

    companion object {
        private const val DIR = "port-vectors/cabling-plan"
        private fun file(name: String) =
            File(CablingPlanVectorsTest::class.java.classLoader!!.getResource("$DIR/$name")!!.toURI())
        val root: JSONObject by lazy { JSONObject(file("vectors.json").readText()) }

        private val PLURAL_NAMES: Map<Int, String> by lazy {
            R.plurals::class.java.fields.associate { it.getInt(null) to it.name }
        }
    }

    // ------------------------------------------------------------------ outils

    private fun kindOf(s: String) = when (s) {
        "power" -> CablingAssignTarget.Kind.SOCA
        "dmx" -> CablingAssignTarget.Kind.DMX
        else -> error("famille inconnue : $s")
    }

    private fun voie(o: JSONObject) = CablingVoie(kindOf(o.getString("kind")), o.getString("distId"), o.getInt("index"))

    private fun voieOrNull(v: Any?): CablingVoie? = if (v == null || v == JSONObject.NULL) null else voie(v as JSONObject)

    private fun JSONObject.opt0(k: String): Any? = if (!has(k) || isNull(k)) null else get(k)

    private fun intOrNull(v: Any?): Int? = if (v == null || v == JSONObject.NULL) null else (v as Number).toInt()

    private fun floatOrNull(v: Any?): Float? = if (v == null || v == JSONObject.NULL) null else (v as Number).toFloat()

    private fun unsigned(v: Int): Long = v.toLong() and 0xFFFFFFFFL

    private fun strList(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun intList(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }

    /** Séparateur de milliers : U+202F (iOS) ou U+00A0 (ICU) sont permis. */
    private fun norm(s: String) = s.replace(' ', ' ')

    private fun res(r: TestRes, t: CablingText?): String? = when (t) {
        null -> null
        is CablingText.Res -> r.res(t.id, t.args)
        is CablingText.Plural -> r.plural(PLURAL_NAMES.getValue(t.id), t.count, *t.args.toTypedArray())
    }

    private fun powerDto(o: JSONObject): PowerCablingDTO = PowerCablingCodec.fromJson(o)
    private fun dmxDto(o: JSONObject): DmxCablingDTO = DmxCablingCodec.fromJson(o)

    // ---------------------------------------------------------- copie conforme

    @Test fun laCopieEstCelleDuManifeste() {
        val parts = file("MANIFEST.sha256").readText().trim().split(Regex("\\s+"))
        assertEquals("vectors.json", parts[1])
        val sha = MessageDigest.getInstance("SHA-256").digest(file("vectors.json").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("vectors.json doit être la copie EXACTE du dépôt web (recopier après régénération)", parts[0], sha)
        assertEquals("mvrviewer.port-vectors.cabling-plan/1", root.getString("schema"))
        assertEquals("85a3668", root.getJSONObject("source").getString("commit").take(7))
    }

    // ---------------------------------------------------------- voies et ◀ ▶

    @Test fun voieOrder() {
        val arr = root.getJSONArray("voieOrder")
        var checks = 0
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val id = v.getString("id")
            val ex = v.getJSONObject("expect")
            val power = v.getString("family") == "power"
            val p = if (power) powerDto(v.getJSONObject("input")) else null
            val d = if (!power) dmxDto(v.getJSONObject("input")) else null

            val dec = ex.getJSONArray("decodedDistributors")
            for (k in 0 until dec.length()) {
                val e = dec.getJSONObject(k)
                if (p != null) {
                    val dist = p.distributors[k]
                    assertEquals("$id id", e.getString("id"), dist.id)
                    // Chaîne BRUTE gardée telle quelle (« xyz » compris), comme iOS et le web…
                    assertEquals("$id kind ${dist.id}", e.getString("kind"), dist.kindRaw)
                    // … et résolue à l'usage (iOS `kindResolved`) : inconnu → solo.
                    val resolved = if (e.getString("kind") == "soca") "soca" else "solo"
                    assertEquals("$id kind résolu ${dist.id}", resolved, dist.kind.raw)
                    assertEquals("$id phases ${dist.id}", intList(e.getJSONArray("circuitPhases")), dist.circuitPhases)
                    assertEquals("$id circuits ${dist.id}", e.getInt("circuitCount"), dist.circuitCount)
                } else {
                    val dist = d!!.distributors[k]
                    assertEquals("$id id", e.getString("id"), dist.id)
                    assertEquals("$id kind ${dist.id}", e.getString("kind"), dist.kindRaw)
                    val resolved = if (e.getString("kind") == "multi") "multi" else "simple"
                    assertEquals("$id kind résolu ${dist.id}", resolved, dist.kind.raw)
                    assertEquals("$id cores ${dist.id}", e.getInt("cores"), dist.cores)
                    assertEquals("$id coreCount ${dist.id}", e.getInt("coreCount"), dist.coreCount)
                }
                checks++
            }
            assertEquals(id, dec.length(), p?.distributors?.size ?: d!!.distributors.size)

            val voies = if (p != null) CablingVoieNavigator.voies(p) else CablingVoieNavigator.voies(d!!)
            val ev = ex.getJSONArray("voies")
            assertEquals("$id nombre de voies", ev.length(), voies.size)
            for (k in 0 until ev.length()) {
                val e = ev.getJSONObject(k)
                assertEquals("$id voie $k", voie(e), voies[k])
                assertEquals("$id rowId $k", e.getString("rowId"), voies[k].rowId)
                assertEquals("$id onglet $k", e.getString("tab"), voies[k].tab.raw)
                checks++
            }

            val nb = ex.getJSONArray("neighbors")
            for (k in 0 until nb.length()) {
                val e = nb.getJSONObject(k)
                val from = voie(e.getJSONObject("from"))
                val got = CablingVoieNavigator.neighbor(from, e.getInt("step"), voies)
                assertEquals("$id voisine de $from (${e.getInt("step")})", voieOrNull(e.opt0("expect")), got)
                checks++
            }

            val exs = ex.getJSONArray("exists")
            for (k in 0 until exs.length()) {
                val e = exs.getJSONObject(k)
                val vo = voie(e.getJSONObject("voie"))
                val got = if (p != null) CablingPlanAssign.exists(vo, p) else CablingPlanAssign.exists(vo, d!!)
                assertEquals("$id existe $vo", e.getBoolean("exists"), got)
                checks++
            }
        }
        assertTrue(checks > 100)
    }

    // ---------------------------------------------------------- libellés figés

    @Test fun labels() {
        val arr = root.getJSONArray("labels")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val got = when (v.getString("fn")) {
                "power" -> CablingVoieLabel.power(v.getString("name"), v.getInt("circuit"))
                else -> CablingVoieLabel.dmx(v.getString("name"), v.getInt("core"), v.getInt("coreCount"))
            }
            assertEquals("libellé $v", v.getString("expect"), got)
            // L'étiquette du plan et du PDF passe par le même libellé.
            val viaLabels = when (v.getString("fn")) {
                "power" -> CablingLabels.soca(v.getString("name"), v.getInt("circuit"))
                else -> CablingLabels.dmx(v.getString("name"), v.getInt("coreCount"), v.getInt("core"))
            }
            assertEquals("étiquette $v", v.getString("expect"), viaLabels)
        }
    }

    // ------------------------------------------------- toucher, cadre, garde

    private data class Asg(val fixture: String, val distributor: String, val index: Int)

    private fun expectedAssignments(a: JSONArray, power: Boolean) = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Asg(o.getString("fixture"), o.getString("distributor"), o.getInt(if (power) "circuit" else "core"))
    }

    private fun mapOfInts(o: JSONObject): Map<String, Long> =
        o.keys().asSequence().associateWith { o.getLong(it) }

    private fun mapOfStrings(o: JSONObject): Map<String, String> =
        o.keys().asSequence().associateWith { o.getString(it) }

    @Test fun sequences() {
        val arr = root.getJSONArray("sequences")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val id = v.getString("id")
            val power = v.getString("family") == "power"
            var p = if (power) powerDto(v.getJSONObject("input")) else null
            var d = if (!power) dmxDto(v.getJSONObject("input")) else null
            // Les MÊMES gestes rejoués sur les états réels (ceux que touche le plan).
            val ps = PowerCablingState().also { s -> p?.let { s.load(it) } }
            val ds = DmxCablingState().also { s -> d?.let { s.load(it) } }

            val steps = v.getJSONArray("steps")
            for (k in 0 until steps.length()) {
                val st = steps.getJSONObject(k)
                val op = st.getJSONObject("do")
                val ex = st.getJSONObject("expect")
                val vo = voie(op.getJSONObject("voie"))
                val tag = "$id pas $k"
                val beforeP = p; val beforeD = d
                val psVersion = ps.version; val dsVersion = ds.version
                when (op.getString("op")) {
                    "toggle" -> {
                        val fx = op.getString("fixture")
                        val result = if (power) {
                            val (n, r) = CablingPlanAssign.toggle(p!!, fx, vo); p = n
                            assertEquals("$tag état réel", r, ps.toggleOnVoie(fx, vo)); r
                        } else {
                            val (n, r) = CablingPlanAssign.toggle(d!!, fx, vo); d = n
                            assertEquals("$tag état réel", r, ds.toggleOnVoie(fx, vo)); r
                        }
                        val want = when (ex.getString("result")) {
                            "assigned" -> CablingToggleResult.ASSIGNED
                            "removed" -> CablingToggleResult.REMOVED
                            "voieMissing" -> CablingToggleResult.VOIE_MISSING
                            else -> error(ex.getString("result"))
                        }
                        assertEquals(tag, want, result)
                    }
                    "frame" -> {
                        val fxs = strList(op.getJSONArray("fixtures"))
                        val ok = if (power) {
                            val n = CablingPlanAssign.assign(p!!, fxs, vo); if (n != null) p = n
                            assertEquals("$tag état réel", n != null, ps.assignFrameOnVoie(fxs, vo)); n != null
                        } else {
                            val n = CablingPlanAssign.assign(d!!, fxs, vo); if (n != null) d = n
                            assertEquals("$tag état réel", n != null, ds.assignFrameOnVoie(fxs, vo)); n != null
                        }
                        assertEquals(tag, ex.getBoolean("result"), ok)
                    }
                    else -> error(op.getString("op"))
                }

                val unchanged = if (power) beforeP == p else beforeD == d
                assertEquals("$tag inchangé", ex.getBoolean("unchanged"), unchanged)
                if (ex.getBoolean("unchanged")) {
                    // Rien d'écrit : ni sauvegarde ni synchro (aucun commit de l'état réel).
                    assertEquals("$tag aucun commit", if (power) psVersion else dsVersion,
                        if (power) ps.version else ds.version)
                }

                val want = expectedAssignments(ex.getJSONArray("assignments"), power)
                val got = if (power) p!!.assignments.map { Asg(it.fixture, it.distributor, it.circuit) }
                          else d!!.assignments.map { Asg(it.fixture, it.distributor, it.core) }
                // Ordre iOS reproduit par le module pur (recommandé) ; ENSEMBLE exigé pour l'état réel.
                assertEquals("$tag affectations (ordre iOS)", want, got)
                val gotState = if (power) ps.assignments.values.map { Asg(it.fixture, it.distributor, it.circuit) }
                               else ds.assignments.values.map { Asg(it.fixture, it.distributor, it.core) }
                assertEquals("$tag affectations (état réel)", want.toSet(), gotState.toSet())

                val onVoie = ex.opt0("fixturesOnVoie")
                if (onVoie == null) {
                    // Voie de l'AUTRE famille : sans objet.
                    assertTrue(tag, (vo.kind == CablingAssignTarget.Kind.SOCA) != power)
                } else {
                    val gotOn = if (power) CablingPlanAssign.fixturesOn(vo, p!!) else CablingPlanAssign.fixturesOn(vo, d!!)
                    assertEquals("$tag sur la voie", strList(onVoie as JSONArray).toSet(), gotOn)
                }

                val colors = if (power) CablingColorResolver.argbByFixture(p!!) else CablingColorResolver.argbByFixture(d!!)
                assertEquals("$tag couleurs", mapOfInts(ex.getJSONObject("colorByFixture")),
                    colors.mapValues { unsigned(it.value) })
                val labels = if (power) CablingColorResolver.labelByFixture(p!!) else CablingColorResolver.labelByFixture(d!!)
                assertEquals("$tag étiquettes", mapOfStrings(ex.getJSONObject("labelByFixture")), labels)
            }
        }
    }

    // ------------------------------------------------- état et textes de la barre

    @Test fun status() {
        val arr = root.getJSONArray("status")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val id = v.getString("id")
            val power = v.getString("family") == "power"
            val vo = voie(v.getJSONObject("voie"))
            val metrics: Map<String, Int>? = (v.opt0("metricsByFixture") as JSONObject?)?.let { m ->
                m.keys().asSequence().associateWith { m.getInt(it) }
            }
            val p = if (power) powerDto(v.getJSONObject("input")) else null
            val d = if (!power) dmxDto(v.getJSONObject("input")) else null
            val st = if (power) CablingVoieStatus.power(vo, p!!, metrics) else CablingVoieStatus.dmx(vo, d!!, metrics)

            val ex = v.opt0("expect") as JSONObject?
            if (ex == null) {
                assertNull("$id : voie disparue, pas d'état", st)
            } else {
                assertNotNull(id, st); st!!
                assertEquals("$id label", ex.getString("label"), st.label)
                assertEquals("$id couleur", ex.getLong("colorArgb"), unsigned(st.colorArgb))
                assertEquals("$id phase", intOrNull(ex.opt0("phase")), st.phase)
                assertEquals("$id compte", ex.getInt("fixtureCount"), st.fixtureCount)
                assertEquals("$id charge", intOrNull(ex.opt0("load")), st.load)
                assertEquals("$id limite", ex.getInt("limit"), st.limit)
                assertEquals("$id surcharge", ex.getBoolean("isOver"), st.isOver)
                assertEquals("$id inconnus", ex.getInt("unknownCount"), st.unknownCount)
                assertEquals("$id précédente", ex.getBoolean("hasPrevious"), st.hasPrevious)
                assertEquals("$id suivante", ex.getBoolean("hasNext"), st.hasNext)
            }

            // Textes de la barre (transcription de PlanView.assignPanel), fr ET en.
            val target = CablingAssignTarget.forVoie(vo, p?.distributors ?: emptyList(), d?.distributors ?: emptyList())
            val targetArgb = CablingAssignBarUi.targetColorArgb(target, p?.distributors ?: emptyList(), d?.distributors ?: emptyList())
            val disp = v.getJSONObject("display")
            for ((lang, r) in listOf("fr" to TestRes.FR, "en" to TestRes.EN)) {
                val locale = if (lang == "fr") Locale.FRENCH else Locale.ENGLISH
                val ui = CablingAssignBarUi.display(target, st, locale, targetArgb)
                val tag = "$id [$lang]"
                assertEquals("$tag voie disparue", disp.getBoolean("voieMissing"), ui.voieMissing)
                if (disp.getBoolean("voieMissing")) {
                    assertEquals(tag, disp.getJSONObject("missingText").getString(lang), r.get("cabling_voie_deleted"))
                    assertFalse(tag, ui.previousEnabled || ui.nextEnabled || ui.frameEnabled)
                    // La barre garde le libellé FIGÉ de la voie.
                    assertEquals(tag, target.label, ui.title)
                    // Pastille (iOS `status?.colorArgb ?? assignTargetColorArgb`) : couleur
                    // du distributeur de l'entrée s'il existe encore, gris s'il a disparu.
                    val dists = v.getJSONObject("input").getJSONArray("distributors")
                    val distId = v.getJSONObject("voie").getString("distId")
                    val expectArgb = (0 until dists.length()).map { dists.getJSONObject(it) }
                        .firstOrNull { it.getString("id") == distId }?.getLong("colorArgb") ?: 0xFF737373L
                    assertEquals("$tag pastille", expectArgb, unsigned(ui.colorArgb))
                    continue
                }
                assertEquals("$tag pastille", unsigned(st!!.colorArgb), unsigned(ui.colorArgb))
                val lt = disp.opt0("loadText") as JSONObject?
                assertEquals("$tag charge", lt?.getString(lang)?.let(::norm), res(r, ui.load)?.let(::norm))
                if (disp.has("loadPending")) assertEquals("$tag sablier", disp.getBoolean("loadPending"), ui.loadPending)
                else assertFalse("$tag sablier", ui.loadPending)
                assertEquals("$tag surcharge", disp.getBoolean("overloadMark"), ui.overloadMark)
                val um = disp.opt0("unknownMark") as JSONObject?
                assertEquals("$tag « ? »", um?.getString(lang), res(r, ui.unknownMark))
                assertEquals("$tag phase", disp.opt0("phaseText") as String?, ui.phaseText)
                assertEquals("$tag compte", disp.getJSONObject("countText").getString(lang), res(r, ui.count))
                assertEquals("$tag ◀", disp.getBoolean("previousEnabled"), ui.previousEnabled)
                assertEquals("$tag ▶", disp.getBoolean("nextEnabled"), ui.nextEnabled)
                assertEquals("$tag titre", ex!!.getString("label"), ui.title)
            }
        }
    }

    // ------------------------------------------------- mémoire de l'écran

    private fun position(o: JSONObject?): CablingListPosition? = when (o?.getString("type")) {
        null -> null
        "listTop" -> CablingListPosition.ListTop
        "row" -> CablingListPosition.Row(o.getString("rowId"))
        else -> error(o.toString())
    }

    private fun assertState(tag: String, want: JSONObject, got: CablingScreenMemoryState) {
        assertEquals("$tag onglet", want.getString("tab"), got.tab.raw)
        val pos = want.getJSONObject("positionByTab")
        val wantMap = pos.keys().asSequence().associate {
            CablingScreenTab.fromRaw(it)!! to position(pos.getJSONObject(it))!!
        }
        assertEquals("$tag positions", wantMap, got.positionByTab.toMap())
        assertEquals("$tag voie en attente", voieOrNull(want.opt0("pendingFocus")), got.pendingFocus)
    }

    @Test fun memory() {
        val arr = root.getJSONArray("memory")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val id = v.getString("id")
            val init = v.getJSONObject("initialState")
            val m = CablingScreenMemoryState(tab = CablingScreenTab.fromRaw(init.getString("tab"))!!)
            assertState("$id initial", init, m)
            val steps = v.getJSONArray("steps")
            for (k in 0 until steps.length()) {
                val st = steps.getJSONObject(k)
                val op = st.getJSONObject("do")
                val tag = "$id pas $k"
                when (op.getString("op")) {
                    "rememberWorkedVoie" -> m.rememberWorkedVoie(voie(op.getJSONObject("voie")))
                    "remember" -> m.remember(position(op.getJSONObject("position"))!!,
                        CablingScreenTab.fromRaw(op.getString("tab"))!!)
                    "takeRestore" -> {
                        val got = m.takeRestore(CablingScreenTab.fromRaw(op.getString("tab"))!!)
                        val want = st.opt0("expect") as JSONObject?
                        assertEquals(tag, want?.let { CablingScrollRestore(it.getString("rowId"), it.getBoolean("highlight")) }, got)
                    }
                    else -> error(op.toString())
                }
                assertState(tag, st.getJSONObject("expectState"), m)
            }
        }
    }

    @Test fun tabOpening() {
        val arr = root.getJSONArray("tabOpening")
        assertEquals(16, arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val got = CablingScreenTab.opening(
                CablingScreenTab.fromRaw(v.opt0("requested") as String?),
                CablingScreenTab.fromRaw(v.opt0("remembered") as String?)
            )
            assertEquals(v.toString(), v.getString("expect"), got.raw)
        }
        // Correspondance des onglets Android (0, 1, 2).
        assertEquals(listOf("cabling", "dmx", "powers"), (0..2).map { CablingScreenTab.fromIndex(it).raw })
    }

    @Test fun returnAnchor() {
        val arr = root.getJSONArray("returnAnchor")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val r = CablingScrollRestore("power#A#1", v.getBoolean("highlight"))
            val got = r.settleAnchor(floatOrNull(v.opt0("rowHeight")), floatOrNull(v.opt0("visibleHeight")))
            assertEquals(v.getString("note"), v.getString("expect"), got.name.lowercase())
        }
    }

    @Test fun listGeometry() {
        val seqs = root.getJSONObject("listGeometry").getJSONArray("sequences")
        for (i in 0 until seqs.length()) {
            val s = seqs.getJSONObject(i)
            val id = s.getString("id")
            val g = CablingListGeometry()
            val steps = s.getJSONArray("steps")
            for (k in 0 until steps.length()) {
                val st = steps.getJSONObject(k)
                val op = st.getJSONObject("do")
                val ex = st.getJSONObject("expect")
                val tag = "$id pas $k"
                when (op.getString("op")) {
                    "report" -> g.report(op.getString("rowId"), CablingRowFrame(
                        op.getDouble("minY").toFloat(), op.getDouble("maxY").toFloat(), op.getDouble("layoutWidth").toFloat()))
                    "scrolled" -> g.scrolled(CablingScrollMetrics(
                        op.getDouble("visibleMinY").toFloat(), op.getDouble("visibleMaxY").toFloat(), op.getBoolean("isAtListTop")))
                    "reset" -> g.reset()
                    "remove" -> g.remove(op.getString("rowId"))
                    "current" -> {
                        val f = g.current(op.getString("rowId"))
                        val w = ex.opt0("frame") as JSONObject?
                        if (w == null) assertNull(tag, f) else {
                            assertNotNull(tag, f)
                            assertEquals(tag, w.getDouble("minY").toFloat(), f!!.minY, 0f)
                            assertEquals(tag, w.getDouble("maxY").toFloat(), f.maxY, 0f)
                            assertEquals(tag, w.getDouble("layoutWidth").toFloat(), f.layoutWidth, 0f)
                            assertEquals(tag, w.getDouble("height").toFloat(), f.height, 0f)
                        }
                        continue
                    }
                    else -> error(op.toString())
                }
                assertEquals("$tag ligne en tête", ex.opt0("topRow") as String?, g.topRow())
                assertEquals("$tag position (iOS)", position(ex.opt0("position") as JSONObject?), g.position())
                assertEquals("$tag position (en-tête permis)",
                    position(ex.opt0("positionHeaderAllowed") as JSONObject?), g.positionHeaderAllowed())
            }
        }
    }

    // ------------------------------------------------- place de la barre

    private fun rect(o: JSONObject?) = o?.let {
        val x = it.getDouble("x").toFloat(); val y = it.getDouble("y").toFloat()
        Rect(x, y, x + it.getDouble("width").toFloat(), y + it.getDouble("height").toFloat())
    }

    @Test fun assignBar() {
        val cases = root.getJSONObject("assignBar").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = CablingAssignBarPlacement.assignBarInsets(
                c.getBoolean("compactHeight"), rect(c.getJSONObject("container"))!!,
                c.getDouble("barWidth").toFloat(), rect(c.opt0("legend") as JSONObject?)
            )
            val ex = c.getJSONObject("expect")
            assertEquals(c.getString("id"), ex.getDouble("bottom").toFloat(), got.bottom, 1e-4f)
            assertEquals(c.getString("id"), ex.getDouble("leadingShift").toFloat(), got.leadingShift, 1e-4f)
        }
        assertEquals(300f, CablingAssignBarPlacement.BAR_WIDTH)
    }

    // ------------------------------------------------- couleurs et légende

    @Test fun colors() {
        val arr = root.getJSONArray("colors")
        assertEquals(0xFF737373L, root.getJSONObject("conventions").getLong("uncabledArgb"))
        assertEquals(0xFF737373L, unsigned(CablingAssignBarUi.UNCABLED_ARGB))
        // Le gris du dessin du plan est bien celui des vecteurs.
        assertEquals(0xFF737373L, unsigned(argbOf(CABLING_UNASSIGNED_GRAY)))
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val id = v.getString("id")
            val power = v.getString("family") == "power"
            val p = if (power) powerDto(v.getJSONObject("input")) else null
            val d = if (!power) dmxDto(v.getJSONObject("input")) else null
            val ex = v.getJSONObject("expect")
            val colors = if (p != null) CablingColorResolver.argbByFixture(p) else CablingColorResolver.argbByFixture(d!!)
            assertEquals("$id couleurs", mapOfInts(ex.getJSONObject("colorByFixture")), colors.mapValues { unsigned(it.value) })
            val labels = if (p != null) CablingColorResolver.labelByFixture(p) else CablingColorResolver.labelByFixture(d!!)
            assertEquals("$id étiquettes", mapOfStrings(ex.getJSONObject("labelByFixture")), labels)
            val legend = if (p != null) CablingColorResolver.legend(p) else CablingColorResolver.legend(d!!)
            val wl = ex.getJSONArray("legend")
            assertEquals("$id légende", (0 until wl.length()).map {
                val o = wl.getJSONObject(it); Triple(o.getString("id"), o.getString("name"), o.getLong("argb"))
            }, legend.map { Triple(it.id, it.name, unsigned(it.argb)) })
            val plan = ex.getJSONObject("planColorByFixture")
            for (fx in strList(v.getJSONArray("fixtures"))) {
                assertEquals("$id plan $fx", plan.getLong(fx), unsigned(CablingColorResolver.planArgb(colors, fx)))
            }
        }
    }

    private fun argbOf(c: androidx.compose.ui.graphics.Color): Int =
        ((c.alpha * 255f + 0.5f).toInt() shl 24) or ((c.red * 255f + 0.5f).toInt() shl 16) or
            ((c.green * 255f + 0.5f).toInt() shl 8) or (c.blue * 255f + 0.5f).toInt()

    // ------------------------------------------------- textes (catalogue)

    /** Clé iOS → ressource Android. Un texte nouveau dans les vecteurs fait échouer ce test. */
    private val TEXT_KEYS = mapOf(
        "Électrique" to "cabling_tab_elec",
        "Sélectionner sur le plan" to "cabling_select_on_plan_cd",
        "Sur le plan" to "cabling_action_on_plan_short",
        "Affecter des projecteurs" to "cabling_assign_fixtures_cd",
        "Affecter" to "cabling_action_assign_short",
        "Vider le circuit" to "cabling_clear_circuit_cd",
        "Vider le départ" to "dmx_clear_core_cd",
        "Vider" to "common_clear",
        "Quitter l'affectation" to "cabling_exit_assign",
        "Voie précédente" to "cabling_prev_voie",
        "Voie suivante" to "cabling_next_voie",
        "Sélection par cadre" to "cabling_frame_select",
        "Touchez pour affecter" to "cabling_tap_to_assign",
        "%lld projecteurs" to "plurals:cabling_bar_fixtures",
        "%@ / %@ canaux" to "cabling_bar_load_channels",
        "%lld canaux" to "plurals:cabling_channels",
        "Surcharge" to "cabling_overload",
        "Conso inconnue" to "cabling_unknown_power_cd",
        "Empreinte inconnue" to "dmx_unknown_footprint_cd",
        "Terminé" to "common_done",
        "Voie supprimée" to "cabling_voie_deleted"
    )

    /** Rend un format iOS (%lld, %@, %1$@) avec des arguments donnés. */
    private fun ios(fmt: String, vararg args: Any): String {
        var out = fmt
        args.forEachIndexed { i, a ->
            out = out.replace("%${i + 1}\$@", a.toString())
        }
        for (a in args) {
            out = out.replaceFirst("%lld", a.toString()).replaceFirst("%@", a.toString())
        }
        return out
    }

    @Test fun texts() {
        val arr = root.getJSONArray("texts")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val key = v.getString("fr")
            val res = TEXT_KEYS[key] ?: error("texte des vecteurs sans ressource Android : $key")
            for ((lang, r) in listOf("fr" to TestRes.FR, "en" to TestRes.EN)) {
                if (res.startsWith("plurals:")) {
                    val name = res.removePrefix("plurals:")
                    val pl = v.getJSONObject(if (lang == "fr") "frPlural" else "enPlural")
                    assertEquals("$key [$lang] one", ios(pl.getString("one"), 1), r.plural(name, 1, 1))
                    assertEquals("$key [$lang] other", ios(pl.getString("other"), 7), r.plural(name, 7, 7))
                } else if (key.contains("%@")) {
                    assertEquals("$key [$lang]", ios(v.getString(lang), "2 741", "512"), r.get(res, "2 741", "512"))
                } else {
                    assertEquals("$key [$lang]", v.getString(lang), r.get(res))
                }
            }
        }
    }
}
