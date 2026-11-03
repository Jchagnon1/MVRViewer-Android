package com.minou.mvrviewer.mvr

import com.minou.mvrviewer.ui.SatelliteStatus
import com.minou.mvrviewer.ui.SatelliteUi
import com.minou.mvrviewer.ui.TestRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale

/**
 * Fond satellite IGN (BD ORTHO®, WMS 1.3.0, EPSG:4326) — vecteurs COMMUNS T1-T10 de
 * SPEC-satellite-ign.md §9, mêmes valeurs que le web et iOS. Constante de la
 * sphère : M_PER_DEG = π/180 × 6 371 000. Tolérance des coins : ±0,5 mm.
 *
 * Le piège (§1.2) : en WMS 1.3.0 avec EPSG:4326, le BBOX est lat,lon. L'inverser ne
 * lève aucune erreur — l'IGN renvoie une image BLANCHE (mer au large de la Somalie).
 */
class SatelliteRequestTest {

    private val m = SatelliteFetcher.M_PER_DEG

    private fun <T> nn(v: T?): T { assertNotNull(v); return v!! }

    /** Paramètres de requête d'une URL (clés en majuscules, valeurs brutes). */
    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }

    /** BBOX d'une URL WMS relu dans l'ordre lat,lon (équivalent de parseWmsUrl). */
    private fun parseBbox(url: String): GeoBox {
        val n = query(url).getValue("BBOX").split(",").map { it.toDouble() }
        return GeoBox(n[0], n[1], n[2], n[3])
    }

    // ---------------------------------------------------------------- T1
    @Test fun t1UrlExacteConcorde() {
        val area = GeoBox(48.8645, 2.3196, 48.8667, 2.3229)
        val r = nn(SatelliteFetcher.requestForArea(area, maxPx = 2048))
        assertEquals(1207, r.width)
        assertEquals(1224, r.height)
        assertEquals(0.2, r.mPerPx, 1e-12)
        val lat0 = (r.bbox.minLat + r.bbox.maxLat) / 2
        assertEquals(244.629, r.bbox.dLat * m, 1e-3)
        assertEquals(241.385, r.bbox.dLon * m * Math.cos(Math.toRadians(lat0)), 1e-3)
        assertEquals(
            "https://data.geopf.fr/wms-r?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=ORTHOIMAGERY.ORTHOPHOTOS" +
                "&STYLES=&CRS=EPSG:4326&BBOX=48.8645000,2.3196000,48.8667000,2.3229000&WIDTH=1207&HEIGHT=1224" +
                "&FORMAT=image/jpeg",
            r.url
        )
    }

    @Test fun t1PlafondAndroidA1024() {
        // Même zone avec le plafond Android (1024) : le grand côté (nord-sud) est
        // plafonné, la résolution suit, les pixels restent carrés au sol.
        val r = nn(SatelliteFetcher.requestForArea(GeoBox(48.8645, 2.3196, 48.8667, 2.3229)))
        assertEquals(1024, SatelliteFetcher.MAX_PX)
        assertEquals(1024, r.height)
        assertEquals(244.629 / 1024, r.mPerPx, 1e-5)
        assertEquals(1011, r.width)
    }

    // ---------------------------------------------------------------- T2
    @Test fun t2OrdreDesAxesLatPuisLon() {
        val zones = listOf(
            GeoBox(48.8645, 2.3196, 48.8667, 2.3229),       // Paris
            GeoBox(43.2950, 5.3690, 43.2990, 5.3760),       // Marseille
            GeoBox(41.9180, 8.7370, 41.9200, 8.7400),       // Ajaccio
            GeoBox(50.6290, 3.0560, 50.6320, 3.0610),       // Lille
            GeoBox(47.2160, -1.5560, 47.2190, -1.5510)      // Nantes (longitude négative)
        )
        for (z in zones) {
            val r = nn(SatelliteFetcher.requestForArea(z))
            val n = query(r.url).getValue("BBOX").split(",").map { it.toDouble() }
            assertTrue("1er nombre = latitude : ${n[0]}", n[0] in 41.0..52.0)
            assertTrue("2e nombre = longitude : ${n[1]}", n[1] in -6.0..10.0)
            assertTrue("3e nombre = latitude : ${n[2]}", n[2] in 41.0..52.0)
            assertTrue("4e nombre = longitude : ${n[3]}", n[3] in -6.0..10.0)
            assertEquals(r.bbox, parseBbox(r.url))
            assertEquals("EPSG:4326", query(r.url)["CRS"])
            assertEquals("1.3.0", query(r.url)["VERSION"])
            assertTrue(r.bbox.contains(z))
        }
    }

    // ---------------------------------------------------------------- T3
    @Test fun t3Plafond() {
        val area = GeoBox(48.9200, 2.3500, 48.9290, 2.3700)
        val web = nn(SatelliteFetcher.requestForArea(area, maxPx = 2048))
        assertEquals(2048, web.width); assertEquals(1403, web.height)
        assertEquals(0.713486, web.mPerPx, 1e-6)
        val android = nn(SatelliteFetcher.requestForArea(area))
        assertEquals(1024, android.width); assertEquals(702, android.height)
        assertEquals(1.426972, android.mPerPx, 1e-6)
    }

    // ---------------------------------------------------------------- T4
    @Test fun t4ArrondiVersLExterieurHemisphereSud() {
        val area = GeoBox(-20.88000004, 55.44700001, -20.87779996, 55.44919999)
        val r = nn(SatelliteFetcher.requestForArea(area, maxPx = 2048))
        val q = query(r.url)
        assertEquals("-20.8800001,55.4470000,-20.8777999,55.4492000", q["BBOX"])
        assertEquals("1143", q["WIDTH"]); assertEquals("1224", q["HEIGHT"])
        assertTrue("zone couverte", r.bbox.contains(area))
        // Le BBOX ne dépend pas du plafond (seule la taille en dépend).
        assertEquals(r.bbox, nn(SatelliteFetcher.requestForArea(area)).bbox)
    }

    @Test fun arrondiLaisseIntactesLesValeursRondes() {
        val r = nn(SatelliteFetcher.requestForArea(GeoBox(48.1, 2.1, 48.1012, 2.1018)))
        assertEquals("48.1000000,2.1000000,48.1012000,2.1018000", query(r.url)["BBOX"])
    }

    // ---------------------------------------------------------------- T5
    @Test fun t5DeLaCalibrationAuxCoins() {
        val calib = GeoCalibration().apply { addAnchor(GeoAnchor(0f, 0f, 48.8656, 2.3212)) }
        val p = nn(SatelliteFetcher.planFor(calib, -50_000f, -40_000f, 50_000f, 40_000f))
        val q = query(p.request.url)
        assertEquals("48.8650604,2.3203797,48.8661396,2.3220203", q["BBOX"])
        assertEquals("601", q["WIDTH"]); assertEquals("601", q["HEIGHT"])
        val tol = 0.5f
        assertEquals(-60_002.6f, p.nwX, tol); assertEquals(60_000.8f, p.nwY, tol)
        assertEquals(60_002.6f, p.neX, tol); assertEquals(60_000.8f, p.neY, tol)
        assertEquals(60_002.6f, p.seX, tol); assertEquals(-60_000.8f, p.seY, tol)
        assertEquals(-60_002.6f, p.swX, tol); assertEquals(-60_000.8f, p.swY, tol)
        val g = nn(calib.latLonOf(p.nwX, p.nwY))
        assertEquals(48.8661396, g.first, 1e-9)
        assertEquals(2.3203797, g.second, 1e-9)
    }

    @Test fun coinsSontCeuxDuBboxArrondiAvecDeuxAncres() {
        // 2 ancres : rotation ET échelle non triviales.
        val calib = GeoCalibration().apply {
            addAnchor(GeoAnchor(0f, 0f, 48.8656, 2.3212))
            addAnchor(GeoAnchor(50_000f, 20_000f, 48.8658, 2.3218))
        }
        val p = nn(SatelliteFetcher.planFor(calib, -40_000f, -25_000f, 90_000f, 60_000f))
        val b = parseBbox(p.request.url)
        assertEquals(p.request.bbox, b)
        val nw = nn(calib.worldPosition(b.maxLat, b.minLon))
        val se = nn(calib.worldPosition(b.minLat, b.maxLon))
        assertEquals(nw.first, p.nwX, 0f); assertEquals(nw.second, p.nwY, 0f)
        assertEquals(se.first, p.seX, 0f); assertEquals(se.second, p.seY, 0f)
        // Le contenu tient dans l'image.
        for ((x, y) in listOf(-40_000f to -25_000f, 90_000f to -25_000f, 90_000f to 60_000f, -40_000f to 60_000f)) {
            val ll = nn(calib.latLonOf(x, y))
            assertTrue(ll.first in b.minLat..b.maxLat && ll.second in b.minLon..b.maxLon)
        }
        // Pixels CARRÉS AU SOL : un pixel vaut la même longueur sur les deux côtés.
        val ewMm = Math.hypot((p.neX - p.nwX).toDouble(), (p.neY - p.nwY).toDouble()) / p.request.width
        val nsMm = Math.hypot((p.swX - p.nwX).toDouble(), (p.swY - p.nwY).toDouble()) / p.request.height
        assertEquals(1.0, ewMm / nsMm, 0.01)
    }

    @Test fun petiteZoneEtendueA120MetresA20Cm() {
        val calib = GeoCalibration().apply { addAnchor(GeoAnchor(0f, 0f, 45.7640, 4.8357)) } // Lyon
        val p = nn(SatelliteFetcher.planFor(calib, -2_500f, -2_500f, 2_500f, 2_500f))
        val r = p.request
        assertEquals(0.20, r.mPerPx, 1e-12)
        assertTrue("w=${r.width}", r.width in 600..602)
        assertTrue("h=${r.height}", r.height in 600..602)
    }

    @Test fun urlDecimaleIndependanteDeLaLangue() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            // Près de Greenwich : petite longitude (Double.toString donnerait « 1.0E-4 »).
            val r = nn(SatelliteFetcher.requestForArea(GeoBox(49.0000, 0.0001, 49.0010, 0.0015)))
            val url = r.url
            assertFalse("pas de notation exponentielle : $url", url.contains("E-") || url.contains("E+"))
            val bb = query(url).getValue("BBOX")
            assertEquals("49.0000000,0.0001000,49.0010000,0.0015000", bb)
            assertFalse("pas de virgule décimale", bb.split(",").size != 4)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun urlGetFeatureInfoAuCentre() {
        val r = nn(SatelliteFetcher.requestForArea(GeoBox(48.8645, 2.3196, 48.8667, 2.3229), maxPx = 2048))
        val q = query(r.infoUrl)
        assertTrue(r.infoUrl.startsWith("https://data.geopf.fr/wms-r?"))
        assertEquals("GetFeatureInfo", q["REQUEST"])
        assertEquals("ORTHOIMAGERY.ORTHOPHOTOS", q["LAYERS"])
        assertEquals("ORTHOIMAGERY.ORTHOPHOTOS", q["QUERY_LAYERS"])
        assertEquals("image/jpeg", q["FORMAT"])        // obligatoire, sinon 400
        assertEquals("text/plain", q["INFO_FORMAT"])
        assertEquals("1", q["FEATURE_COUNT"])
        assertEquals(q["BBOX"], query(r.url)["BBOX"])
        assertEquals("603", q["I"]); assertEquals("612", q["J"])
        assertEquals("1207", q["WIDTH"]); assertEquals("1224", q["HEIGHT"])
    }

    // ---------------------------------------------------------------- T6
    @Test fun t6PreFiltreDeCouverture() {
        val dedans = listOf(
            48.8566 to 2.3522,      // Paris
            41.9192 to 8.7386,      // Ajaccio
            14.6037 to -61.0730,    // Fort-de-France
            -20.8789 to 55.4481,    // Saint-Denis de La Réunion
            -12.7806 to 45.2279,    // Mamoudzou
            4.9372 to -52.3260,     // Cayenne
            46.7811 to -56.1764,    // Saint-Pierre
            43.7384 to 7.4246,      // Monaco
            46.2044 to 6.1432       // Genève (emprise métropolitaine : l'image décidera)
        )
        val dehors = listOf(
            52.5163 to 13.3777,     // Berlin
            -22.2758 to 166.4580,   // Nouméa
            -17.5350 to -149.5696,  // Papeete
            51.5074 to -0.1278,     // Londres
            40.7128 to -74.0060     // New York
        )
        for ((lat, lon) in dedans) assertTrue("$lat,$lon couvert", ignCovers(lat, lon))
        for ((lat, lon) in dehors) assertFalse("$lat,$lon hors couverture", ignCovers(lat, lon))
        assertFalse(ignCovers(Double.NaN, 2.0))
        assertEquals("ign-fr", ImagerySources.forPoint(48.8566, 2.3522)?.id)
        assertNull(ImagerySources.forPoint(52.5163, 13.3777))
    }

    // ---------------------------------------------------------------- T7
    private fun image(w: Int = 256, h: Int = 256, bg: Int = 0xFFFFFFFF.toInt()) = IntArray(w * h) { bg }
    private fun IntArray.fill(x0: Int, y0: Int, size: Int, c: Int = 0xFF000000.toInt(), w: Int = 256) {
        for (y in y0 until y0 + size) for (x in x0 until x0 + size) this[y * w + x] = c
    }

    @Test fun t7ImageVide() {
        assertTrue("blanc pur", SatelliteFetcher.isBlankPixels(image(), 256, 256))
        val petit = image().apply { fill(96, 96, 16) }      // 16 échantillons / 4096
        assertTrue("carré 16×16 → vide", SatelliteFetcher.isBlankPixels(petit, 256, 256))
        val grand = image().apply { fill(100, 100, 26) }    // ≈ 1 %
        assertFalse("carré 26×26 → gardée", SatelliteFetcher.isBlankPixels(grand, 256, 256))
        val cote = image().apply { for (y in 0 until 256) for (x in 0 until 128) this[y * 256 + x] = 0xFF000000.toInt() }
        assertFalse("moitié noire (côte) → gardée", SatelliteFetcher.isBlankPixels(cote, 256, 256))
        assertFalse("fond 249 → gardée (seuil 250)",
            SatelliteFetcher.isBlankPixels(image(bg = 0xFFF9F9F9.toInt()), 256, 256))
        assertTrue("fond 250 → vide", SatelliteFetcher.isBlankPixels(image(bg = 0xFFFAFAFA.toInt()), 256, 256))
        val quadrants = IntArray(256 * 256) { i ->
            val x = i % 256; val y = i / 256
            when {
                x < 128 && y < 128 -> 0xFFCC3333.toInt()
                x >= 128 && y < 128 -> 0xFF33CC33.toInt()
                x < 128 -> 0xFF3333CC.toInt()
                else -> 0xFFCCCC33.toInt()
            }
        }
        assertFalse("quadrants de la doublure → gardée", SatelliteFetcher.isBlankPixels(quadrants, 256, 256))
        // Déjà à 64 × 64 (cas Android après createScaledBitmap) : 20 non blancs = vide, 21 = gardée.
        val g20 = IntArray(64 * 64) { if (it < 20) 0xFF000000.toInt() else -1 }
        val g21 = IntArray(64 * 64) { if (it < 21) 0xFF000000.toInt() else -1 }
        assertTrue(SatelliteFetcher.isBlankPixels(g20, 64, 64))
        assertFalse(SatelliteFetcher.isBlankPixels(g21, 64, 64))
    }

    // ---------------------------------------------------------------- T8
    private val featureInfoConcorde = """
        Results for FeatureType 'http://ORTHOIMAGERY.ORTHOPHOTOS.GRAPHE-MOSAIQUAGE:graphe_bdortho_75':
        --------------------------------------------
        dep = 75
        pva = 2024
        res = 20
        echelle = 50
        date_vol = 2024-08-22Z
        ordre_debu = 719
        ordre_fin = 9999
        geom = [GEOMETRY (Polygon) with 4316 points]
        --------------------------------------------
    """.trimIndent()

    @Test fun t8AnneeDePriseDeVue() {
        assertEquals(2024, IgnImagery.parseCaptureYear(featureInfoConcorde, 2026))
        val sansDate = featureInfoConcorde.lines().filterNot { it.contains("date_vol") }
            .joinToString("\n").replace("pva = 2024", "pva = 2023")
        assertEquals(2023, IgnImagery.parseCaptureYear(sansDate, 2026))
        assertNull(IgnImagery.parseCaptureYear("no features were found", 2026))
        assertNull(IgnImagery.parseCaptureYear("date_vol = 1850-01-01Z", 2026))
        assertNull(IgnImagery.parseCaptureYear("", 2026))
        assertNull(IgnImagery.parseCaptureYear(null, 2026))
        // Fins de ligne Windows, année future proche admise, trop lointaine refusée.
        assertEquals(2023, IgnImagery.parseCaptureYear("dep = 93\r\npva = 2023\r\n", 2026))
        assertEquals(2027, IgnImagery.parseCaptureYear("date_vol = 2027-01-02Z", 2026))
        assertNull(IgnImagery.parseCaptureYear("date_vol = 2028-01-02Z", 2026))
    }

    // ---------------------------------------------------------------- T9
    private fun text(lang: TestRes, p: Pair<Int, List<Any>>) = lang.res(p.first, p.second)

    @Test fun t9Mention() {
        assertEquals("Source : IGN – BD ORTHO®, prise de vue 2024", text(TestRes.FR, SatelliteUi.attributionRes(2024)))
        assertEquals("Source: IGN – BD ORTHO®, imagery 2024", text(TestRes.EN, SatelliteUi.attributionRes(2024)))
        assertEquals("Source : IGN – BD ORTHO® (cartes.gouv.fr)", text(TestRes.FR, SatelliteUi.attributionRes(null)))
        assertEquals("Source: IGN – BD ORTHO® (cartes.gouv.fr)", text(TestRes.EN, SatelliteUi.attributionRes(null)))
        assertEquals("https://cartes.gouv.fr/rechercher-une-donnee/dataset/IGNF_BD-ORTHO", IgnImagery.DATASET_URL)
    }

    @Test fun messagesDuReglageExacts() {
        fun fr(s: SatelliteStatus) = SatelliteUi.messageRes(s)?.let { text(TestRes.FR, it) }
        fun en(s: SatelliteStatus) = SatelliteUi.messageRes(s)?.let { text(TestRes.EN, it) }
        assertNull(fr(SatelliteStatus.Idle)); assertNull(fr(SatelliteStatus.Ready))
        assertEquals("Téléchargement du satellite…", fr(SatelliteStatus.Loading))
        assertEquals("Downloading satellite imagery…", en(SatelliteStatus.Loading))
        assertEquals("Fond satellite disponible en France uniquement (imagerie IGN).", fr(SatelliteStatus.OutOfCoverage))
        assertEquals("Satellite background available in France only (IGN imagery).", en(SatelliteStatus.OutOfCoverage))
        assertEquals("Pas d'imagerie IGN pour cette zone.", fr(SatelliteStatus.Unavailable))
        assertEquals("No IGN imagery for this area.", en(SatelliteStatus.Unavailable))
        val timeout = SatelliteStatus.Failed(SatelliteErrorKind.TIMEOUT)
        assertEquals("Satellite indisponible : le serveur de l'IGN ne répond pas. Réessayez plus tard.", fr(timeout))
        assertEquals("Satellite unavailable: the IGN server isn't responding. Try again later.", en(timeout))
        val net = SatelliteStatus.Failed(SatelliteErrorKind.NETWORK)
        assertEquals("Satellite indisponible : réseau injoignable.", fr(net))
        assertEquals("Satellite unavailable: network unreachable.", en(net))
        val http = SatelliteStatus.Failed(SatelliteErrorKind.HTTP, 502)
        assertEquals("Satellite indisponible (erreur 502 du serveur de l'IGN).", fr(http))
        assertEquals("Satellite unavailable (IGN server error 502).", en(http))
        val dec = SatelliteStatus.Failed(SatelliteErrorKind.DECODE)
        assertEquals("Satellite indisponible : image illisible.", fr(dec))
        assertEquals("Satellite unavailable: unreadable image.", en(dec))
        // Statut tiré du résultat typé.
        assertEquals(SatelliteStatus.OutOfCoverage, SatelliteUi.statusOf(SatelliteResult.OutOfCoverage))
        assertEquals(SatelliteStatus.Unavailable, SatelliteUi.statusOf(SatelliteResult.NoImagery))
        assertEquals(http, SatelliteUi.statusOf(SatelliteResult.Error(SatelliteErrorKind.HTTP, 502)))
        assertEquals(SatelliteStatus.Ready, SatelliteUi.statusOf(SatelliteResult.Ok("x")))
    }

    // ---------------------------------------------------------------- T10
    /** Doublure du service IGN : GetMap → corps « IMG » / « BLANK », GetFeatureInfo → texte. */
    private class FakeIgn {
        val calls = mutableListOf<String>()
        var getMap: (String) -> SatelliteHttpReply = { SatelliteHttpReply(200, "image/jpeg", "IMG".toByteArray()) }
        var featureInfo: (String) -> SatelliteHttpReply = { SatelliteHttpReply(200, "text/plain;charset=UTF-8", ByteArray(0)) }
        val http = SatelliteHttp { url, _ ->
            calls += url
            if ("REQUEST=GetFeatureInfo" in url) featureInfo(url) else getMap(url)
        }
        val getMaps get() = calls.count { "REQUEST=GetMap" in it }
        val infos get() = calls.count { "REQUEST=GetFeatureInfo" in it }
    }

    private var clock = 1_000_000L
    private fun service(f: FakeIgn) = SatelliteService(
        http = f.http,
        decode = { b -> String(b).takeIf { it == "IMG" || it == "BLANK" } },
        isBlank = { it == "BLANK" },
        now = { clock },
        currentYear = { 2026 }
    )
    private fun calibAt(lat: Double, lon: Double) = GeoCalibration().apply { addAnchor(GeoAnchor(0f, 0f, lat, lon)) }
    private val paris = calibAt(48.8656, 2.3212)
    private fun SatelliteService<String>.go(c: GeoCalibration = paris, force: Boolean = false) =
        load(c, -50_000f, -40_000f, 50_000f, 40_000f, force)

    @Test fun t10HorsCouvertureAucuneRequete() {
        val f = FakeIgn()
        val r = service(f).go(calibAt(52.5163, 13.3777))   // Berlin
        assertEquals(SatelliteResult.OutOfCoverage, r)
        assertEquals(0, f.calls.size)
        assertEquals(SatelliteResult.OutOfCoverage, service(f).go(GeoCalibration()))
        assertEquals(0, f.calls.size)
    }

    @Test fun t10ImageBlancheMemoriseeRelanceSeulementForcee() {
        val f = FakeIgn().apply { getMap = { SatelliteHttpReply(200, "image/jpeg", "BLANK".toByteArray()) } }
        val s = service(f)
        assertEquals(SatelliteResult.NoImagery, s.go())
        assertEquals(1, f.getMaps); assertEquals(0, f.infos)
        clock += 3_600_000
        assertEquals(SatelliteResult.NoImagery, s.go())
        assertEquals("pas de relance sans geste", 1, f.getMaps)
        assertEquals(SatelliteResult.NoImagery, s.go(force = true))
        assertEquals("relance avec force", 2, f.getMaps)
        assertEquals(0, f.infos)
    }

    @Test fun t10ImageNormaleAvecAnnee() {
        val f = FakeIgn().apply {
            featureInfo = { SatelliteHttpReply(200, "text/plain", featureInfoConcorde.toByteArray()) }
        }
        val r = service(f).go()
        assertTrue(r is SatelliteResult.Ok)
        val ok = (r as SatelliteResult.Ok).overlay
        assertEquals("IMG", ok.image)
        assertEquals(2024, ok.year)
        assertEquals(1, f.getMaps); assertEquals(1, f.infos)
        assertTrue("GetMap d'abord", "REQUEST=GetMap" in f.calls[0])
        assertEquals(query(f.calls[0])["BBOX"], query(f.calls[1])["BBOX"])
        assertEquals("48.8650604,2.3203797,48.8661396,2.3220203", query(f.calls[0])["BBOX"])
    }

    @Test fun t10GetFeatureInfoEnEchecNeBloquePasLImage() {
        for (fail in listOf<(String) -> SatelliteHttpReply>(
            { throw SocketTimeoutException() },
            { throw IOException("réseau") },
            { SatelliteHttpReply(400, "text/xml", ByteArray(0)) },
            { SatelliteHttpReply(200, "text/plain", "no features were found".toByteArray()) }
        )) {
            val f = FakeIgn().apply { featureInfo = fail }
            val r = service(f).go()
            assertTrue(r is SatelliteResult.Ok)
            assertNull((r as SatelliteResult.Ok).overlay.year)
        }
    }

    @Test fun t10ReponsesEnErreur() {
        // 200 mais pas une image (ServiceException XML) → http(200).
        val xml = FakeIgn().apply { getMap = { SatelliteHttpReply(200, "text/xml", "<x/>".toByteArray()) } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.HTTP, 200), service(xml).go())
        // 502 → erreur, AUCUNE autre taille demandée.
        val gw = FakeIgn().apply { getMap = { SatelliteHttpReply(502, "text/html", ByteArray(0)) } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.HTTP, 502), service(gw).go())
        assertEquals(1, gw.calls.size)
        // 400 (ServiceExceptionReport) → http(400).
        val bad = FakeIgn().apply { getMap = { SatelliteHttpReply(400, "text/xml", ByteArray(0)) } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.HTTP, 400), service(bad).go())
        // Réseau injoignable.
        val net = FakeIgn().apply { getMap = { throw IOException("hôte inconnu") } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.NETWORK), service(net).go())
        // Image illisible.
        val junk = FakeIgn().apply { getMap = { SatelliteHttpReply(200, "image/jpeg", "???".toByteArray()) } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.DECODE), service(junk).go())
        assertEquals(0, junk.infos)
    }

    @Test fun t10DelaiPasDeRelanceAvant30s() {
        val f = FakeIgn().apply { getMap = { throw SocketTimeoutException("30 s") } }
        val s = service(f)
        val timeout = SatelliteResult.Error(SatelliteErrorKind.TIMEOUT)
        assertEquals(timeout, s.go())
        assertEquals(1, f.calls.size)
        clock += 10_000
        assertEquals(timeout, s.go())
        assertEquals("pas de relance avant 30 s", 1, f.calls.size)
        assertEquals(timeout, s.go(force = true))
        assertEquals("un geste explicite relance", 2, f.calls.size)
        clock += 31_000
        assertEquals(timeout, s.go())
        assertEquals(3, f.calls.size)
        // Le serveur revient : l'image passe et la mémoire d'échec est effacée.
        clock += 31_000
        f.getMap = { SatelliteHttpReply(200, "image/jpeg", "IMG".toByteArray()) }
        assertTrue(s.go() is SatelliteResult.Ok)
    }

    // ---------------------------------------------------------------- divers
    @Test fun userAgentIdentifieLApp() {
        assertEquals("MVRViewer/1.9.0 (Android)", SatelliteFetcher.userAgent("1.9.0"))
        assertEquals("MVRViewer/? (Android)", SatelliteFetcher.userAgent(null))
    }

    @Test fun cleDeCacheCoherenteAvecLaRequete() {
        val calib = GeoCalibration().apply {
            addAnchor(GeoAnchor(0f, 0f, 48.8656, 2.3212))
            addAnchor(GeoAnchor(50_000f, 20_000f, 48.8658, 2.3218))
        }
        val k1 = SatelliteFetcher.keyFor(calib, -40_000f, -25_000f, 90_000f, 60_000f)
        val k2 = SatelliteFetcher.keyFor(calib, -40_000f, -25_000f, 90_000f, 60_000f)
        val u1 = SatelliteFetcher.planFor(calib, -40_000f, -25_000f, 90_000f, 60_000f)!!.request.url
        val u2 = SatelliteFetcher.planFor(calib, -40_000f, -25_000f, 90_000f, 60_000f)!!.request.url
        assertEquals(k1, k2); assertEquals(u1, u2)
        // Contenu agrandi de 20 m : nouvelle clé ET nouvelle requête.
        val k3 = SatelliteFetcher.keyFor(calib, -40_000f, -25_000f, 110_000f, 60_000f)
        val u3 = SatelliteFetcher.planFor(calib, -40_000f, -25_000f, 110_000f, 60_000f)!!.request.url
        assertNotEquals(k1, k3); assertNotEquals(u1, u3)
    }

    @Test fun casInvalidesRendentNull() {
        assertNull(SatelliteFetcher.planFor(GeoCalibration(), 0f, 0f, 1000f, 1000f))
        assertNull(SatelliteFetcher.areaFor(GeoBox(87.9, 0.0, 88.1, 0.1)))
        assertNull(SatelliteFetcher.areaFor(GeoBox(Double.NaN, 0.0, 1.0, 1.0)))
        assertNull(SatelliteFetcher.areaFor(GeoBox(10.0, 179.9990, 10.001, 179.99999)))
        assertNull(SatelliteFetcher.requestForArea(GeoBox(48.0, 2.0, 48.0, 2.1)))
        assertNull(SatelliteFetcher.requestForArea(GeoBox(48.0, 2.0, 48.1, Double.NaN)))
    }

    /**
     * Plus AUCUNE trace de l'ancienne imagerie mondiale sans clé dans le code (ni
     * URL, ni texte) : ses conditions interdisent une app vendue. Les mots sont
     * reconstitués ici pour que ce test ne se trouve pas lui-même.
     */
    @Test fun aucuneTraceDeLAncienneSource() {
        val root = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }
        val forbidden = Regex(listOf("arc" + "gis", "es" + "ri", "World" + "_Imagery").joinToString("|"), RegexOption.IGNORE_CASE)
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml", "java", "json", "txt", "md") }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (forbidden.containsMatchIn(l)) "${f.path}:${i + 1}: $l" else null } }
            .toList()
        assertTrue("traces restantes :\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
