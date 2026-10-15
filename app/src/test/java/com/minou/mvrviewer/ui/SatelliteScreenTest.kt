package com.minou.mvrviewer.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.GeoCalibration
import com.minou.mvrviewer.mvr.SatelliteErrorKind
import com.minou.mvrviewer.mvr.SatelliteFetcher
import com.minou.mvrviewer.mvr.SatelliteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fond satellite IGN : ce qui est PROPRE À L'ÉCRAN (SPEC-satellite-ign.md §4, §5.1,
 * §7.4), testé en JVM — pas de Robolectric ni d'émulateur ici.
 *
 * 1. Les DÉCISIONS de l'interface sont des fonctions pures de [SatelliteUi], testées
 *    sur les vrais `strings.xml` : mention (écran + PDF), interrupteur grisé hors
 *    couverture, outil hors couverture (Toast sans bascule), déroulé du chargement
 *    (quel Toast, quelle image), test « image vide » d'Android.
 * 2. Le CÂBLAGE de ces fonctions dans les écrans (Compose, PDF, Toast) n'est pas
 *    exécutable en JVM : des gardes lisent les sources et échouent si un écran ne
 *    les appelle plus (mention retirée du PDF, Toast supprimé…).
 */
class SatelliteScreenTest {

    private fun fr(p: Pair<Int, List<Any>>?) = p?.let { TestRes.FR.res(it.first, it.second) }
    private fun en(p: Pair<Int, List<Any>>?) = p?.let { TestRes.EN.res(it.first, it.second) }

    private fun calibAt(vararg pts: Pair<Double, Double>) = GeoCalibration().apply {
        pts.forEachIndexed { i, (lat, lon) -> addAnchor(GeoAnchor(i * 10_000f, 0f, lat, lon)) }
    }
    private val paris = 48.8656 to 2.3212
    private val berlin = 52.5163 to 13.3777

    // ------------------------------------------------ image vide (chemin Android)

    /**
     * Image [w]×[h] blanche avec un carré noir de [side] px en ([x0], [y0]), lue
     * pixel par pixel comme `Bitmap.getPixel` dans [SatelliteFetcher.isBlankBitmap].
     */
    private fun squareReader(w: Int, h: Int, x0: Int, y0: Int, side: Int, reads: IntArray? = null): (Int, Int) -> Int =
        { x, y ->
            require(x in 0 until w && y in 0 until h) { "pixel hors image : ($x, $y)" }
            reads?.let { it[0]++ }
            if (x in x0 until x0 + side && y in y0 until y0 + side) 0xFF000000.toInt() else -1
        }

    @Test fun imageVideCheminAndroidCarre16Et26AToutesLesPositions() {
        // Image 256 × 256 (cas de la relecture) : un échantillon tous les 4 px. Un
        // carré de 16 px couvre TOUJOURS 4 × 4 = 16 échantillons (≤ 20 → vide) ; un
        // carré de 26 px en couvre au moins 6 × 6 = 36 (> 20 → image gardée), quel
        // que soit son décalage par rapport à la grille.
        for (dy in 0 until 4) for (dx in 0 until 4) {
            val x0 = 96 + dx; val y0 = 101 + dy
            assertTrue("16 px en ($x0, $y0) → vide",
                SatelliteFetcher.isBlankSampled(256, 256, pixelAt = squareReader(256, 256, x0, y0, 16)))
            assertFalse("26 px en ($x0, $y0) → gardée",
                SatelliteFetcher.isBlankSampled(256, 256, pixelAt = squareReader(256, 256, x0, y0, 26)))
        }
        // Taille réelle d'une image IGN (1024 × 1024 et 1024 × 601) : mêmes seuils.
        assertTrue(SatelliteFetcher.isBlankSampled(1024, 1024, pixelAt = squareReader(1024, 1024, 500, 500, 64)))
        assertFalse(SatelliteFetcher.isBlankSampled(1024, 1024, pixelAt = squareReader(1024, 1024, 500, 500, 104)))
        assertFalse(SatelliteFetcher.isBlankSampled(1024, 601, pixelAt = squareReader(1024, 601, 0, 0, 200)))
        assertTrue(SatelliteFetcher.isBlankSampled(1024, 601, pixelAt = squareReader(1024, 601, 0, 0, 0)))
    }

    @Test fun imageVideLitSeulementLaGrilleEtResteDansLImage() {
        // Aucune image réduite : 64 × 64 lectures, jamais hors de l'image, même pour
        // des tailles plus petites que la grille ou très allongées.
        for ((w, h) in listOf(256 to 256, 1024 to 601, 7 to 1023, 1 to 1, 63 to 65)) {
            val reads = IntArray(1)
            assertTrue("$w × $h blanc",
                SatelliteFetcher.isBlankSampled(w, h, pixelAt = squareReader(w, h, -1, -1, 0, reads)))
            assertEquals("$w × $h : lectures", 64 * 64, reads[0])
        }
        assertFalse("image vide de pixels", SatelliteFetcher.isBlankSampled(0, 10) { _, _ -> -1 })
    }

    // ------------------------------------------------ mention (écran + PDF)

    @Test fun mentionPoseeDesQueLImageEstDessinee() {
        assertEquals("Source : IGN – BD ORTHO®, prise de vue 2024", fr(SatelliteUi.creditRes(true, true, 2024)))
        assertEquals("Source: IGN – BD ORTHO®, imagery 2024", en(SatelliteUi.creditRes(true, true, 2024)))
        assertEquals("Source : IGN – BD ORTHO® (cartes.gouv.fr)", fr(SatelliteUi.creditRes(true, true, null)))
        assertEquals("Source: IGN – BD ORTHO® (cartes.gouv.fr)", en(SatelliteUi.creditRes(true, true, null)))
        assertNull("réglage éteint : ni image ni mention", SatelliteUi.creditRes(false, true, 2024))
        assertNull("pas d'image : pas de mention", SatelliteUi.creditRes(true, false, 2024))
        assertNull(SatelliteUi.creditRes(false, false, null))
    }

    @Test fun pastilleDuPdfDansLeCoinBasDroitDeLaZoneImage() {
        // Page A4 paysage du PDF : zone image jusqu'à (right, bottom) = (806, 559).
        val right = 806f; val bottom = 559f
        val b = satelliteCreditBox(textW = 150f, textH = 9f, right = right, bottom = bottom)
        assertEquals(803f, b.right, 1e-4f)          // 3 pt du bord droit
        assertEquals(556f, b.bottom, 1e-4f)         // 3 pt du bord bas
        assertEquals(156f, b.width, 1e-4f)          // texte + 2 × 3 pt
        assertEquals(12f, b.height, 1e-4f)          // texte + 2 × 1,5 pt
        assertTrue("dans la zone", b.left > 0f && b.top > 0f && b.right < right && b.bottom < bottom)
    }

    private val icon = ImageVector.Builder("t", 24.dp, 24.dp, 24f, 24f).build()
    private fun tool(id: ToolId, available: Boolean = true) =
        ToolSpec(id, id.name, icon, available = available, checked = null, onInvoke = {})

    @Test fun mentionEcranAuDessusDesBarresDuBas() {
        val specs = listOf(tool(ToolId.RECT), tool(ToolId.MEASURE, available = false))
        // Plus de barre flottante (SPEC-menus-android §9.0, Q1) : contre le bas (12 dp) ;
        // la pile Rechercher / Recadrer, au même coin, monte au-dessus d'elle.
        assertEquals(12.dp, satelliteAttributionBottom(ToolbarLayout(), specs))
        // Barre ancrée en bas : juste au-dessus d'elle (12 dp + bouton 48 dp + 12 dp).
        assertEquals(72.dp, satelliteAttributionBottom(ToolbarLayout(bottom = listOf(ToolId.RECT)), specs))
        // Barre du bas sans outil disponible = pas rendue.
        assertEquals(12.dp, satelliteAttributionBottom(ToolbarLayout(bottom = listOf(ToolId.MEASURE)), specs))
    }

    // ------------------------------------------------ couverture, outil, menu

    @Test fun couvertureJugeeSurLaPremiereAncre() {
        assertTrue(SatelliteUi.covered(calibAt(paris)))
        assertFalse(SatelliteUi.covered(calibAt(berlin)))
        assertFalse("1re ancre à Berlin", SatelliteUi.covered(calibAt(berlin, paris)))
        assertTrue("1re ancre à Paris", SatelliteUi.covered(calibAt(paris, berlin)))
        assertFalse("pas de calibration", SatelliteUi.covered(GeoCalibration()))
        assertTrue("Fort-de-France (DROM)", SatelliteUi.covered(calibAt(14.6037 to -61.0662)))
    }

    @Test fun outilHorsCouvertureToastSansBascule() {
        var toggles = 0
        assertEquals(SatelliteStatus.OutOfCoverage, SatelliteUi.onToolTap(covered = false) { toggles++ })
        assertEquals("hors couverture : pas de bascule", 0, toggles)
        assertEquals("Fond satellite disponible en France uniquement (imagerie IGN).",
            fr(SatelliteUi.messageRes(SatelliteStatus.OutOfCoverage)))
        assertNull(SatelliteUi.onToolTap(covered = true) { toggles++ })
        assertEquals(1, toggles)
    }

    @Test fun basculeParGesteCompteLesActivations() {
        // Seule une ACTIVATION par geste compte (elle force la demande et autorise
        // le Toast) ; une valeur restaurée avec le projet ne compte pas.
        val o = SceneOptions()
        o.showSatellite = true
        assertEquals("valeur restaurée : silencieuse", 0, o.satelliteActivations)
        o.showSatellite = false
        assertNull(SatelliteUi.onToolTap(true, o::toggleSatelliteByUser))
        assertTrue(o.showSatellite); assertEquals(1, o.satelliteActivations)
        SatelliteUi.onToolTap(true, o::toggleSatelliteByUser)
        assertFalse(o.showSatellite); assertEquals("extinction : pas d'activation", 1, o.satelliteActivations)
        SatelliteUi.onToolTap(false, o::toggleSatelliteByUser)
        assertFalse("hors couverture : réglage inchangé", o.showSatellite)
        assertEquals(1, o.satelliteActivations)
        SatelliteUi.onToolTap(true, o::toggleSatelliteByUser)
        assertEquals(2, o.satelliteActivations)
    }

    @Test fun menuGriseHorsCouvertureAvecLeMessage() {
        for (show in listOf(true, false)) for (st in listOf(SatelliteStatus.Idle, SatelliteStatus.Ready)) {
            val row = SatelliteUi.menuRow(covered = false, show = show, status = st)
            assertFalse("grisé", row.enabled)
            assertFalse("jamais coché hors couverture", row.checked)
            assertFalse("pas de curseur d'opacité", row.opacity)
            assertEquals("Fond satellite disponible en France uniquement (imagerie IGN).", fr(row.message))
            assertEquals("Satellite background available in France only (IGN imagery).", en(row.message))
        }
        val loading = SatelliteUi.menuRow(covered = true, show = true, status = SatelliteStatus.Loading)
        assertTrue(loading.enabled && loading.checked && loading.opacity)
        assertEquals("Téléchargement du satellite…", fr(loading.message))
        val off = SatelliteUi.menuRow(covered = true, show = false, status = SatelliteStatus.Idle)
        assertTrue(off.enabled); assertFalse(off.checked); assertFalse(off.opacity); assertNull(off.message)
        val failed = SatelliteUi.menuRow(true, true, SatelliteStatus.Failed(SatelliteErrorKind.HTTP, 502))
        assertEquals("Satellite indisponible (erreur 502 du serveur de l'IGN).", fr(failed.message))
    }

    // ------------------------------------------------ déroulé du chargement + Toast

    /** Doublure : compte les téléchargements et retient l'argument « forcer ». */
    private class Loader(val result: SatelliteResult<String>) {
        val forces = mutableListOf<Boolean>()
        var loadingSeen = false
        suspend fun load(@Suppress("UNUSED_PARAMETER") c: String, force: Boolean): SatelliteResult<String> {
            forces += force; return result
        }
    }

    private fun refreshWith(
        loader: Loader, current: String? = null, currentKey: String? = null,
        currentStatus: SatelliteStatus = SatelliteStatus.Idle,
        show: Boolean = true, calibrated: Boolean = true, covered: Boolean = true,
        userAsked: Boolean, content: String? = "zone"
    ): SatelliteRefresh<String> = runBlocking {
        SatelliteUi.refresh(
            current = current, currentKey = currentKey, currentStatus = currentStatus,
            show = show, calibrated = calibrated, covered = covered, userAsked = userAsked,
            content = { content }, keyOf = { "clé:$it" },
            load = { c, f -> loader.load(c, f) },
            onLoading = { loader.loadingSeen = true }
        )
    }

    @Test fun reglageEteintOuSansCalibrationAucuneImage() {
        val l = Loader(SatelliteResult.Ok("IMG"))
        for ((show, calibrated) in listOf(false to true, true to false)) {
            val r = refreshWith(l, current = "ANCIENNE", currentKey = "clé:zone", show = show, calibrated = calibrated, userAsked = true)
            assertNull(r.overlay); assertEquals(SatelliteStatus.Idle, r.status); assertNull(r.toast)
        }
        assertTrue("aucun téléchargement", l.forces.isEmpty())
    }

    @Test fun horsCouvertureAucuneRequeteImageRetireeToastSurGeste() {
        val l = Loader(SatelliteResult.Ok("IMG"))
        val asked = refreshWith(l, current = "ANCIENNE", currentKey = "clé:zone", covered = false, userAsked = true)
        assertNull("image d'un autre lieu retirée", asked.overlay)
        assertEquals(SatelliteStatus.OutOfCoverage, asked.status)
        assertEquals("Toast après le geste", SatelliteStatus.OutOfCoverage, asked.toast)
        val restored = refreshWith(l, covered = false, userAsked = false)
        assertEquals(SatelliteStatus.OutOfCoverage, restored.status)
        assertNull("valeur restaurée : pas de Toast", restored.toast)
        assertTrue("AUCUNE requête hors couverture", l.forces.isEmpty())
        assertFalse(l.loadingSeen)
    }

    @Test fun memeContenuQueLImageAfficheePretSansReseau() {
        val l = Loader(SatelliteResult.Ok("NOUVELLE"))
        val r = refreshWith(l, current = "IMG", currentKey = "clé:zone", userAsked = true)
        assertEquals("IMG", r.overlay); assertEquals(SatelliteStatus.Ready, r.status); assertNull(r.toast)
        assertTrue(l.forces.isEmpty())
        // Rien à couvrir (pas de bornes) : image et statut inchangés, pas de réseau.
        val none = refreshWith(l, current = "IMG", currentKey = "clé:autre", currentStatus = SatelliteStatus.Loading,
            userAsked = true, content = null)
        assertEquals("IMG", none.overlay); assertEquals(SatelliteStatus.Loading, none.status); assertNull(none.toast)
        assertTrue(l.forces.isEmpty())
    }

    @Test fun imageRecueAfficheeSansToast() {
        val l = Loader(SatelliteResult.Ok("IMG"))
        val r = refreshWith(l, current = "ANCIENNE", currentKey = "clé:avant", userAsked = true)
        assertEquals("IMG", r.overlay); assertEquals(SatelliteStatus.Ready, r.status); assertNull(r.toast)
        assertEquals("geste → demande forcée", listOf(true), l.forces)
        assertTrue("statut « téléchargement » publié avant", l.loadingSeen)
        val silent = Loader(SatelliteResult.Ok("IMG"))
        refreshWith(silent, userAsked = false)
        assertEquals("valeur restaurée → mémoires respectées", listOf(false), silent.forces)
    }

    @Test fun pasDImagerieImageRetireeToastSeulementApresUnGeste() {
        val asked = refreshWith(Loader(SatelliteResult.NoImagery), current = "ANCIENNE", currentKey = "clé:avant", userAsked = true)
        assertNull(asked.overlay)
        assertEquals(SatelliteStatus.Unavailable, asked.status)
        assertEquals(SatelliteStatus.Unavailable, asked.toast)
        assertEquals("Pas d'imagerie IGN pour cette zone.", fr(SatelliteUi.messageRes(asked.toast!!)))
        val restored = refreshWith(Loader(SatelliteResult.NoImagery), current = "ANCIENNE", currentKey = "clé:avant", userAsked = false)
        assertNull(restored.overlay); assertNull(restored.toast)
    }

    @Test fun echecReseauImageCouranteGardeeToastSurGeste() {
        val timeout = SatelliteResult.Error(SatelliteErrorKind.TIMEOUT)
        val asked = refreshWith(Loader(timeout), current = "ANCIENNE", currentKey = "clé:avant", userAsked = true)
        assertEquals("l'image courante reste", "ANCIENNE", asked.overlay)
        assertEquals(SatelliteStatus.Failed(SatelliteErrorKind.TIMEOUT), asked.status)
        assertEquals(SatelliteStatus.Failed(SatelliteErrorKind.TIMEOUT), asked.toast)
        assertEquals("Satellite indisponible : le serveur de l'IGN ne répond pas. Réessayez plus tard.",
            fr(SatelliteUi.messageRes(asked.toast!!)))
        val restored = refreshWith(Loader(SatelliteResult.Error(SatelliteErrorKind.HTTP, 502)), current = "ANCIENNE",
            currentKey = "clé:avant", userAsked = false)
        assertEquals("ANCIENNE", restored.overlay); assertNull(restored.toast)
        assertEquals(SatelliteStatus.Failed(SatelliteErrorKind.HTTP, 502), restored.status)
    }

    @Test fun refreshRendLaMemeInstanceDImage() {
        val l = Loader(SatelliteResult.Ok("IMG"))
        val cur = String(charArrayOf('A'))
        assertSame(cur, refreshWith(l, current = cur, currentKey = "clé:zone", userAsked = false).overlay)
    }

    // ------------------------------------------------ câblage dans les écrans (sources)

    private val srcRoot: File = listOf(File("src/main/java/com/minou/mvrviewer"), File("app/src/main/java/com/minou/mvrviewer"))
        .first { it.isDirectory }

    /**
     * Source sans chaînes ni commentaires (petit lexeur) : un appel cité dans un
     * commentaire ou une chaîne ne compte pas, et un commentaire qui cite le type
     * MIME « tout fichier » (étoile, barre, étoile) n'ouvre pas un faux bloc.
     */
    private fun code(path: String): String {
        val s = File(srcRoot, path).readText()
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                s.startsWith("/*", i) -> {            // blocs imbriqués (règle Kotlin)
                    var depth = 0
                    while (i < s.length) {
                        if (s.startsWith("/*", i)) { depth++; i += 2 }
                        else if (s.startsWith("*/", i)) { depth--; i += 2; if (depth == 0) break }
                        else i++
                    }
                    out.append(' ')
                }
                s.startsWith("\"\"\"", i) -> {
                    val end = s.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) s.length else end + 3
                    out.append("\"\"")
                }
                c == '"' -> {
                    i++
                    while (i < s.length && s[i] != '"' && s[i] != '\n') { if (s[i] == '\\') i++; i++ }
                    i++
                    out.append("\"\"")
                }
                c == '\'' -> {                          // littéral caractère : 'x', '\n', '\u0041'
                    val end = s.indexOf('\'', if (s.getOrNull(i + 1) == '\\') i + 3 else i + 2)
                    i = if (end < 0) s.length else end + 1
                    out.append("' '")
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Fonction à corps-expression [decl] : jusqu'à la première ligne vide. */
    private fun expr(source: String, decl: String): String {
        assertTrue("déclaration absente : $decl", source.contains(decl))
        return source.substringAfter(decl).substringBefore("\n\n")
    }

    /** Corps de la fonction déclarée par [decl] (accolades appariées). */
    private fun body(source: String, decl: String): String {
        val i = source.indexOf(decl)
        assertTrue("déclaration absente : $decl", i >= 0)
        val open = source.indexOf('{', i)
        var depth = 0
        for (k in open until source.length) {
            when (source[k]) { '{' -> depth++; '}' -> if (--depth == 0) return source.substring(open, k + 1) }
        }
        error("accolades non appariées : $decl")
    }

    private fun assertCalls(where: String, text: String, vararg needles: String) {
        for (n in needles) assertTrue("$where n'appelle plus « $n »", text.contains(n))
    }

    @Test fun cablageMentionDansLaPagePlanDesPdf() {
        val page = body(code("ui/PlanPdfExport.kt"), "fun DrawScope.drawPdfPage(")
        assertCalls("drawPdfPage (PDF du plan)", page, "SatelliteUi.creditRes(", "drawSatelliteAttribution(")
        // Les pages plan du PDF de câblage passent par la même page.
        assertCalls("renderPlanPagesInto", body(code("ui/PlanPdfExport.kt"), "fun renderPlanPagesInto("), "drawPdfPage(")
        assertCalls("PDF de câblage", code("ui/CablingPdfExport.kt"), "renderPlanPagesInto(")
        assertCalls("drawSatelliteAttribution", body(code("ui/SatelliteUi.kt"), "fun DrawScope.drawSatelliteAttribution("),
            "satelliteCreditBox(", "drawText(")
    }

    @Test fun cablageMentionALEcranPlanEt3D() {
        for (f in listOf("ui/PlanScreen.kt", "ui/Scene3DScreen.kt")) {
            val c = code(f)
            // La mention est lue dans une valeur (elle sert aussi d'obstacle aux
            // panneaux du bas-gauche, tranche B des menus), puis posée.
            val v = Regex("""val (satCredit\w*) = SatelliteUi\.creditRes\(\s*options\.showSatellite[^)]*\)""").find(c)
            assertTrue("$f : mention calculée", v != null)
            assertTrue("$f : mention à l'écran",
                Regex("""${v!!.groupValues[1]}\?\.let\s*\{\s*credit\s*->\s*SatelliteAttribution\(credit""")
                    .containsMatchIn(c))
        }
    }

    @Test fun cablageOutilEtMenuHorsCouverture() {
        for (f in listOf("ui/PlanScreen.kt", "ui/Scene3DScreen.kt")) {
            val c = code(f)
            assertTrue("$f : outil → onToolTap + Toast",
                Regex("""SatelliteUi\.onToolTap\(\s*satelliteCovered\s*,\s*options::toggleSatelliteByUser\s*\)\s*\?\.let\s*\{\s*SatelliteUi\.toast\(""")
                    .containsMatchIn(c))
            // Le satellite a quitté le ⋯ pour le panneau Position GPS (tranche B des
            // menus) : même couverture, même statut.
            assertCalls(f, c, "options.showSatellite, satelliteCovered, satelliteStatus)")
        }
        val panel = code("ui/GeoPositionPanel.kt")
        assertCalls("panneau Position GPS", panel, "checked = content.satelliteChecked, enabled = content.satelliteEnabled",
            "content.satelliteMessage", "GeoPanelRow.SATELLITE_OPACITY ->", "message = R.string.sat_out_of_coverage to emptyList()")
        assertFalse("plus de satellite dans le ⋯", code("ui/SceneOptions.kt").contains("SatelliteUi.menuRow("))
    }

    @Test fun cablageChargementEtToastDansSceneScreen() {
        val c = code("ui/SceneScreen.kt")
        assertCalls("SceneScreen", c, "SatelliteUi.refresh(", "covered = SatelliteUi.covered(calibration)",
            "userAsked = activation != satActivationHandled", "satellite = r.overlay", "satelliteStatus = r.status")
        assertTrue("SceneScreen : Toast du résultat",
            Regex("""r\.toast\?\.let\s*\{\s*SatelliteUi\.toast\(ctx""").containsMatchIn(c))
        assertTrue("SceneScreen : couverture du menu et des outils",
            Regex("""val satelliteCovered = [^\n]*SatelliteUi\.covered\(calibration\)""").containsMatchIn(c))
    }

    @Test fun cablageImageVideAndroid() {
        val c = code("mvr/SatelliteOverlay.kt")
        val bmp = expr(c, "fun isBlankBitmap(bmp: Bitmap): Boolean =")
        assertCalls("isBlankBitmap", bmp, "isBlankSampled(bmp.width, bmp.height)", "bmp.getPixel(x, y)")
        assertFalse("isBlankBitmap : plus d'image réduite", c.contains("createScaledBitmap"))
        assertCalls("service Android", expr(c, "fun newService("), "isBlank = { isBlankBitmap(it) }")
    }
}
