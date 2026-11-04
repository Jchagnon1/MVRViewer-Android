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
import kotlinx.coroutines.launch

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

    @Test fun t1UrlDesDatesWfsEtFiche() {
        val r = nn(SatelliteFetcher.requestForArea(GeoBox(48.8645, 2.3196, 48.8667, 2.3229), maxPx = 2048))
        assertEquals(
            "https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature" +
                "&TYPENAMES=ORTHOIMAGERY.ORTHOPHOTOS.GRAPHE-MOSAIQUAGE:graphe_bdortho&OUTPUTFORMAT=application/json" +
                "&PROPERTYNAME=dep,pva,res,date_vol&COUNT=100&CQL_FILTER=INTERSECTS(geom,POLYGON((" +
                "48.8645000%202.3196000,48.8645000%202.3229000,48.8667000%202.3229000,48.8667000%202.3196000," +
                "48.8645000%202.3196000)))",
            r.datesUrl
        )
        assertEquals(0.3042560, IgnImagery.pmResolution(r.bbox, r.width, r.height), 5e-7)
        assertEquals(20, r.visibleMaxResCm)
        assertEquals(
            "https://data.geopf.fr/csw?SERVICE=CSW&VERSION=2.0.2&REQUEST=GetRecordById&ID=IGNF_BD-ORTHO&ELEMENTSETNAME=summary",
            IgnImagery.DATASET_RECORD_URL
        )
        // Nombres indépendants de la langue, même en français (virgule décimale).
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertFalse(r.datesUrl.contains("48,8645"))
        } finally {
            Locale.setDefault(saved)
        }
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
    /** Réponses ENREGISTRÉES de l'IGN (web `tests/fixtures/ign/`, 2026-11-03) : vecteurs communs. */
    private fun fixture(name: String): String =
        File(SatelliteRequestTest::class.java.classLoader!!.getResource("ign/$name")!!.toURI()).readText()

    /** « Maintenant » des vecteurs T8 : 2026-11-03 (UTC). */
    private val nov3 = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, 10, 3)
    }.timeInMillis

    private fun captures(json: String?, maxResCm: Int? = null) = IgnImagery.parseCaptureDates(json, 2026, maxResCm)
    private fun feature(props: String) = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{$props}}]}"""

    @Test fun t8AnneesDePriseDeVue() {
        assertEquals(ImageryDate.Capture(2024, 2024), captures(fixture("wfs-concorde.json")))
        assertEquals(ImageryDate.Capture(2021, 2024), captures(fixture("wfs-strasbourg-kehl.json")))
        assertEquals(ImageryDate.Capture(2025, 2025), captures(fixture("wfs-reunion-stdenis.json")))
        assertNull(captures(fixture("wfs-mer-large.json")))
        assertNull(captures(fixture("wfs-berlin.json")))
        assertNull(captures(fixture("wfs-error-400.xml")))
        assertNull(captures(""))
        assertNull(captures(null))
        assertNull(captures("{ pas du JSON"))
        // Sans date_vol : pva (entier ou texte de 4 chiffres).
        assertEquals(ImageryDate.Capture(2023, 2023), captures(feature(""""dep":"93","pva":2023""")))
        assertEquals(ImageryDate.Capture(2023, 2023), captures(feature(""""pva":" 2023 """")))
        assertNull(captures(feature(""""pva":"20234"""")))
        // Bornes : 1850 et 2028 écartées, 2027 admise (année courante + 1).
        assertNull(captures(feature(""""date_vol":"1850-05-01Z"""")))
        assertNull(captures(feature(""""date_vol":"2028-01-02Z"""")))
        assertEquals(ImageryDate.Capture(2027, 2027), captures(feature(""""date_vol":"2027-01-02Z"""")))
        // date_vol prime sur pva.
        assertEquals(ImageryDate.Capture(2022, 2022), captures(feature(""""pva":2021,"date_vol":" 2022-10-07Z"""")))
    }

    @Test fun t8DateDeMiseAJourDuJeuDeDonnees() {
        assertEquals(ImageryDate.Updated(2026, 10, 22), IgnImagery.parseDatasetUpdate(fixture("csw-bdortho-summary.xml"), nov3))
        assertNull(IgnImagery.parseDatasetUpdate(fixture("csw-unknown-id.xml"), nov3))
        assertNull(IgnImagery.parseDatasetUpdate(null, nov3))
        val ok = fixture("csw-bdortho-summary.xml")
        // Autre identifiant, 30 février, 1999-12-31, date future (« maintenant » = 2026-10-20) → null.
        assertNull(IgnImagery.parseDatasetUpdate(ok.replace("IGNF_BD-ORTHO", "IGNF_BD-TOPO"), nov3))
        assertNull(IgnImagery.parseDatasetUpdate(ok.replace("2026-10-22", "2026-02-30"), nov3))
        assertNull(IgnImagery.parseDatasetUpdate(ok.replace("2026-10-22", "1999-12-31"), nov3))
        val oct20 = nov3 - 14L * 86_400_000L
        assertNull(IgnImagery.parseDatasetUpdate(ok, oct20))
        // Demain admis (fuseaux), après-demain non.
        assertEquals(ImageryDate.Updated(2026, 10, 22), IgnImagery.parseDatasetUpdate(ok, nov3 - 12L * 86_400_000L))
        // Deux dct:modified → la plus récente.
        val two = ok.replace("<dct:modified>2026-10-22</dct:modified>",
            "<dct:modified>2025-01-05</dct:modified><dct:modified>2026-10-22</dct:modified><dct:modified>2026-03-01</dct:modified>")
        assertEquals(ImageryDate.Updated(2026, 10, 22), IgnImagery.parseDatasetUpdate(two, nov3))
        // Année grégorienne, jamais le calendrier de l'appareil.
        assertEquals(2026, IgnImagery.gregorianYear(nov3))
    }

    @Test fun t8GuyaneZonesVisibles() {
        val apatou = GeoBox(5.055, -54.3463, 5.0586, -54.3427)
        val json = fixture("wfs-guyane-apatou.json")
        // Requête de l'app (1994 × 2002, ≈ 0,20 m/px) : niveau 19, seules les zones de 20 cm.
        assertEquals(0.20097, IgnImagery.pmResolution(apatou, 1994, 2002), 5e-5)
        assertEquals(20, IgnImagery.visibleMaxResCm(apatou, 1994, 2002))
        assertEquals(ImageryDate.Capture(2022, 2022), captures(json, 20))
        assertEquals(ImageryDate.Capture(2018, 2022), captures(json, null))
        // Seuil 0,8 × 0,5971643 = 0,4777314 (mesures §1.9 d).
        assertEquals(0.4777314, IgnImagery.LEVEL_RATIO * IgnImagery.PM_Z18_RES, 5e-8)
        for ((w, h) in listOf(839 to 843, 838 to 846, 840 to 843, 887 to 890, 1994 to 2002)) {
            assertEquals("$w × $h", 20, IgnImagery.visibleMaxResCm(apatou, w, h))
        }
        for ((w, h) in listOf(838 to 842, 834 to 845, 831 to 834, 798 to 801)) {
            assertNull("$w × $h", IgnImagery.visibleMaxResCm(apatou, w, h))
        }
        assertNull("taille inconnue", IgnImagery.visibleMaxResCm(apatou, 0, 0))
        // Image de 1,2 km (0,60 m/px) : aucune limite.
        val big = nn(SatelliteFetcher.requestForArea(GeoBox(5.050, -54.3500, 5.0608, -54.3392), maxPx = 2048))
        assertNull(big.visibleMaxResCm)
        // Entités : `res` « 50 » (texte) écartée à 20 ; `res` absent gardée ; seulement invisibles → null.
        assertNull(captures(feature(""""res":"50","date_vol":"2018-09-07Z""""), 20))
        assertEquals(ImageryDate.Capture(2018, 2018), captures(feature(""""date_vol":"2018-09-07Z""""), 20))
        assertEquals(ImageryDate.Capture(2022, 2022), captures(feature(""""res":20,"date_vol":"2022-10-07Z""""), 20))
    }

    // ---------------------------------------------------------------- T9
    private fun text(lang: TestRes, p: Pair<Int, List<Any>>) = lang.res(p.first, p.second)

    @Test fun t9Mention() {
        // Espace ORDINAIRE (U+0020) avant « : » : texte EXACT de SPEC-satellite-ign §4.1, du
        // web (la référence, §7.5) et d'iOS — même texte à l'écran et dans les PDF des trois
        // plateformes (relecture : Android avait une espace insécable).
        val sp = ' '
        fun fr(d: ImageryDate?) = text(TestRes.FR, SatelliteUi.attributionRes(d))
        fun en(d: ImageryDate?) = text(TestRes.EN, SatelliteUi.attributionRes(d))
        assertEquals("Source$sp: IGN – BD ORTHO®, prise de vue 2024", fr(ImageryDate.Capture(2024, 2024)))
        assertEquals("Source$sp: IGN – BD ORTHO®, prises de vue 2021–2024", fr(ImageryDate.Capture(2021, 2024)))
        assertEquals("Source$sp: IGN – BD ORTHO®, mise à jour du 22/10/2026", fr(ImageryDate.Updated(2026, 10, 22)))
        assertEquals("Source$sp: IGN – BD ORTHO® (cartes.gouv.fr)", fr(null))
        assertEquals("Source: IGN – BD ORTHO®, imagery 2024", en(ImageryDate.Capture(2024, 2024)))
        assertEquals("Source: IGN – BD ORTHO®, imagery 2021–2024", en(ImageryDate.Capture(2021, 2024)))
        assertEquals("Source: IGN – BD ORTHO®, updated 2026-10-22", en(ImageryDate.Updated(2026, 10, 22)))
        assertEquals("Source: IGN – BD ORTHO® (cartes.gouv.fr)", en(null))
        // Bornes dans le désordre, jour et mois sur 2 chiffres, date mal formée → sans date.
        assertEquals("Source: IGN – BD ORTHO®, imagery 2021–2024", en(ImageryDate.Capture(2024, 2021)))
        assertEquals("Source$sp: IGN – BD ORTHO®, mise à jour du 05/03/2026", fr(ImageryDate.Updated(2026, 3, 5)))
        assertEquals("Source: IGN – BD ORTHO®, updated 2026-03-05", en(ImageryDate.Updated(2026, 3, 5)))
        assertEquals("Source: IGN – BD ORTHO® (cartes.gouv.fr)", en(ImageryDate.Updated(2026, 13, 5)))
        assertEquals("Source: IGN – BD ORTHO® (cartes.gouv.fr)", en(ImageryDate.Capture(24, 24)))
        // Jamais « 2 024 » : les années partent en TEXTE, même dans une langue qui groupe.
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertFalse(fr(ImageryDate.Capture(2024, 2024)).contains("2 024"))
            assertFalse(fr(ImageryDate.Capture(2024, 2024)).contains("2 024"))
        } finally {
            Locale.setDefault(saved)
        }
        for (k in listOf("sat_attribution", "sat_attribution_year", "sat_attribution_years", "sat_attribution_updated")) {
            assertTrue(k, TestRes.FR.strings.getValue(k).startsWith("Source\u0020: IGN – BD ORTHO®"))
            assertFalse(k, TestRes.FR.strings.getValue(k).contains('\u00A0'))
        }
        for (k in listOf("sat_attribution_year", "sat_attribution_years", "sat_attribution_updated")) {
            assertFalse(TestRes.FR.strings.getValue(k).contains("\$d"))
            assertFalse(TestRes.EN.strings.getValue(k).contains("\$d"))
        }
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
    /**
     * Doublure du service IGN : GetMap → corps « IMG » / « BLANK » ; WFS (dates) et fiche
     * CSW → réponses enregistrées par défaut. Chaque appel est noté.
     */
    private class FakeIgn {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var getMap: (String) -> SatelliteHttpReply = { SatelliteHttpReply(200, "image/jpeg", "IMG".toByteArray()) }
        @Volatile var wfs: (String) -> SatelliteHttpReply = { SatelliteHttpReply(200, "application/json", WFS_CONCORDE.toByteArray()) }
        @Volatile var csw: (String) -> SatelliteHttpReply = { SatelliteHttpReply(200, "application/xml", CSW_OK.toByteArray()) }
        val http = SatelliteHttp { url, _ ->
            calls += url
            when {
                "/wfs/ows?" in url -> wfs(url)
                "/csw?" in url -> csw(url)
                else -> getMap(url)
            }
        }
        val getMaps get() = calls.count { "REQUEST=GetMap" in it }
        val wfsCalls get() = calls.count { "/wfs/ows?" in it }
        val cswCalls get() = calls.count { "/csw?" in it }

        companion object {
            lateinit var WFS_CONCORDE: String
            lateinit var CSW_OK: String
        }
    }

    private fun reply(status: Int, type: String = "text/html", body: String = "x") = SatelliteHttpReply(status, type, body.toByteArray())
    private val lnd = """<?xml version="1.0"?><ServiceExceptionReport version="1.3.0"><ServiceException code="LayerNotDefined">Layer not defined</ServiceException></ServiceExceptionReport>"""
    private val owsLnd = """<ows:ExceptionReport><ows:Exception exceptionCode="LayerNotDefined"/></ows:ExceptionReport>"""
    private val ipv = """<ServiceExceptionReport><ServiceException code="InvalidParameterValue"/></ServiceExceptionReport>"""
    /** Ne répond jamais (dans la limite qu'on lui donne) : délai. */
    private fun never(timeout: Int): SatelliteHttpReply { Thread.sleep(timeout.toLong()); throw SocketTimeoutException() }

    private var clock = 1_000_000L
    private fun service(f: FakeIgn, infoTimeoutMs: Long = 2_000, retryMs: Long = 20) = SatelliteService(
        http = f.http,
        decode = { b -> String(b).takeIf { it == "IMG" || it == "BLANK" } },
        isBlank = { it == "BLANK" },
        now = { clock },
        currentYear = { 2026 },
        infoTimeoutMs = infoTimeoutMs,
        transientRetryMs = retryMs,
        sleep = { Thread.sleep(it) }
    )
    private fun calibAt(lat: Double, lon: Double) = GeoCalibration().apply { addAnchor(GeoAnchor(0f, 0f, lat, lon)) }
    private val paris = calibAt(48.8656, 2.3212)
    private fun SatelliteService<String>.go(c: GeoCalibration = paris, force: Boolean = false, grow: Float = 0f) =
        load(c, -50_000f, -40_000f, 50_000f + grow, 40_000f, force)
    /** Image publiée puis sa date (déroulé de SceneScreen). */
    private fun SatelliteService<String>.dated(r: SatelliteResult<SatelliteLoaded<String>>): ImageryDate? =
        kotlinx.coroutines.runBlocking { date((r as SatelliteResult.Ok).overlay.plan.request) }
    private fun dateOf(r: SatelliteResult<SatelliteLoaded<String>>) = (r as SatelliteResult.Ok).overlay.date

    private fun fake(): FakeIgn {
        FakeIgn.WFS_CONCORDE = fixture("wfs-concorde.json")
        FakeIgn.CSW_OK = fixture("csw-bdortho-summary.xml")
        return FakeIgn()
    }
    private val y2024 = ImageryDate.Capture(2024, 2024)
    private val updated = ImageryDate.Updated(2026, 10, 22)

    @Test fun t10HorsCouvertureAucuneRequete() {
        val f = fake()
        val r = service(f).go(calibAt(52.5163, 13.3777))   // Berlin
        assertEquals(SatelliteResult.OutOfCoverage, r)
        assertEquals(0, f.calls.size)
        assertEquals(SatelliteResult.OutOfCoverage, service(f).go(GeoCalibration()))
        assertEquals(0, f.calls.size)
    }

    @Test fun t10ImageBlancheMemoriseeRelanceSeulementForcee() {
        val f = fake().apply { getMap = { SatelliteHttpReply(200, "image/jpeg", "BLANK".toByteArray()) } }
        val s = service(f)
        assertEquals(SatelliteResult.NoImagery, s.go())
        assertEquals(1, f.getMaps); assertEquals(0, f.wfsCalls + f.cswCalls)
        clock += 3_600_000
        assertEquals(SatelliteResult.NoImagery, s.go())
        assertEquals("pas de relance sans geste", 1, f.getMaps)
        assertEquals(SatelliteResult.NoImagery, s.go(force = true))
        assertEquals("relance avec force", 2, f.getMaps)
        assertEquals("aucune requête de date pour une image vide", 0, f.wfsCalls + f.cswCalls)
    }

    @Test fun t10ImageNormalePublieeAvantSaDate_puisAnnees() {
        val f = fake()
        val s = service(f)
        val r = s.go()
        assertTrue(r is SatelliteResult.Ok)
        assertEquals("IMG", (r as SatelliteResult.Ok).overlay.image)
        assertNull("publiée AVANT sa date (aucune date encore connue)", r.overlay.date)
        assertEquals("aucune requête de date avant la publication", listOf(1, 0, 0), listOf(f.getMaps, f.wfsCalls, f.cswCalls))
        assertEquals(y2024, s.dated(r))
        assertEquals("1re image : GetMap + WFS + fiche CSW", listOf(1, 1, 1), listOf(f.getMaps, f.wfsCalls, f.cswCalls))
        // WFS sur l'emprise EXACTE de l'image (le BBOX arrondi de l'URL GetMap).
        val bbox = query(f.calls.first { "REQUEST=GetMap" in it })["BBOX"]!!.split(",")
        val wfsUrl = f.calls.first { "/wfs/ows?" in it }
        assertTrue(wfsUrl.contains("POLYGON((${bbox[0]}%20${bbox[1]},${bbox[0]}%20${bbox[3]},${bbox[2]}%20${bbox[3]},${bbox[2]}%20${bbox[1]},${bbox[0]}%20${bbox[1]}))"))
        assertEquals("48.8650604,2.3203797,48.8661396,2.3220203", bbox.joinToString(","))
        // 2e image (autre emprise) : la date de mise à jour est DÉJÀ dans la publication ; GetMap + WFS seulement.
        val r2 = s.go(grow = 30_000f)
        assertEquals(updated, dateOf(r2))
        assertEquals(y2024, s.dated(r2))
        assertEquals(listOf(2, 2, 1), listOf(f.getMaps, f.wfsCalls, f.cswCalls))
    }

    @Test fun t10DateWfsEnEchec_dateDeMiseAJour_memoireDeDeuxMinutes() {
        for (fail in listOf<(String) -> SatelliteHttpReply>(
            { reply(500) }, { reply(429) },
            { throw SocketTimeoutException() }, { throw IOException("réseau") },
            { reply(200, "application/json", "{ illisible") },
            { reply(200, "application/json", fixture("wfs-mer-large.json")) }
        )) {
            val f = fake().apply { wfs = fail }
            val s = service(f)
            assertEquals(updated, s.dated(s.go()))
        }
        // WFS 500 : pas redemandé avant 2 min ; la date de mise à jour (déjà lue) suffit ; après, ses années.
        val f = fake().apply { wfs = { reply(500) } }
        val s = service(f)
        assertEquals(updated, s.dated(s.go()))
        assertEquals(listOf(1, 1), listOf(f.wfsCalls, f.cswCalls))
        f.wfs = { reply(200, "application/json", fixture("wfs-concorde.json")) }
        clock += 60_000
        val r2 = s.go(grow = 20_000f)
        assertEquals(updated, dateOf(r2))
        assertEquals(updated, s.dated(r2))
        assertEquals("1 min après : aucun WFS", listOf(1, 1), listOf(f.wfsCalls, f.cswCalls))
        clock += 120_000
        assertEquals(y2024, s.dated(s.go(grow = 40_000f)))
        assertEquals("2 min plus tard : WFS redemandé, aucune fiche", listOf(2, 1), listOf(f.wfsCalls, f.cswCalls))
        // Un WFS 200 « sans zone » n'est PAS une panne : redemandé à l'image suivante.
        val g = fake().apply { wfs = { reply(200, "application/json", fixture("wfs-berlin.json")) } }
        val t = service(g)
        t.dated(t.go()); t.dated(t.go(grow = 20_000f))
        assertEquals(2, g.wfsCalls)
    }

    @Test fun t10WfsEtFicheEnEchec_aucuneDateInventee_gesteEfface() {
        for ((w, c) in listOf<Pair<(String) -> SatelliteHttpReply, (String) -> SatelliteHttpReply>>(
            Pair({ reply(500) }, { reply(500) }),
            Pair({ reply(500) }, { reply(200, "application/xml", fixture("csw-unknown-id.xml")) }),
            Pair({ throw SocketTimeoutException() }, { throw SocketTimeoutException() })
        )) {
            val f = fake().apply { wfs = w; csw = c }
            val s = service(f)
            val r = s.go()
            assertTrue(r is SatelliteResult.Ok)
            assertNull(s.dated(r))
            assertEquals(listOf(1, 1), listOf(f.wfsCalls, f.cswCalls))
            // Image suivante (< 2 min) : ni l'un ni l'autre redemandé.
            clock += 30_000
            assertNull(s.dated(s.go(grow = 20_000f)))
            assertEquals(listOf(1, 1), listOf(f.wfsCalls, f.cswCalls))
            // Après 2 min : redemandés.
            clock += 120_000
            assertNull(s.dated(s.go(grow = 40_000f)))
            assertEquals(listOf(2, 2), listOf(f.wfsCalls, f.cswCalls))
            // Geste (force) : tout de suite.
            assertNull(s.dated(s.go(grow = 60_000f, force = true)))
            assertEquals(listOf(3, 3), listOf(f.wfsCalls, f.cswCalls))
        }
    }

    @Test fun t10DateLente_imagePublieeTout_deSuite_dateBornee() {
        val f = fake().apply { wfs = { never(5_000) }; csw = { never(5_000) } }
        val s = service(f, infoTimeoutMs = 500)
        val t0 = System.nanoTime()
        val r = s.go()
        assertTrue("image publiée avant la borne", r is SatelliteResult.Ok && (System.nanoTime() - t0) / 1e6 < 400)
        val t1 = System.nanoTime()
        assertNull(s.dated(r))
        val ms = (System.nanoTime() - t1) / 1e6
        assertTrue("date réglée en ≈ 500 ms ($ms)", ms in 400.0..950.0)
        // Image suivante : aucune requête de date, réglée tout de suite.
        val t2 = System.nanoTime()
        assertNull(s.dated(s.go(grow = 20_000f)))
        assertTrue((System.nanoTime() - t2) / 1e6 < 300)
        assertEquals(listOf(1, 1), listOf(f.wfsCalls, f.cswCalls))
        // 2 min plus tard : WFS et fiche redemandés.
        clock += 121_000
        f.wfs = { reply(200, "application/json", fixture("wfs-concorde.json")) }
        assertEquals(y2024, s.dated(s.go(grow = 40_000f)))
        assertEquals(listOf(2, 2), listOf(f.wfsCalls, f.cswCalls))
    }

    @Test fun t10FicheCswDAvance() {
        val f = fake()
        val s = service(f)
        assertEquals(y2024, s.dated(s.go()))
        assertEquals("1re image, WFS et fiche réussis", listOf(1, 1), listOf(f.wfsCalls, f.cswCalls))
        // Puis les deux en panne : la 2e image est publiée AVEC la date de mise à jour, aucune 2e fiche.
        f.wfs = { reply(503) }; f.csw = { reply(503) }
        val r2 = s.go(grow = 20_000f)
        assertEquals(updated, dateOf(r2))
        assertEquals(updated, s.dated(r2))
        assertEquals(1, f.cswCalls)
        // Tout en panne dès le départ : aucune date (jamais inventée).
        val g = fake().apply { wfs = { reply(500) }; csw = { reply(500) } }
        val t = service(g)
        assertNull(t.dated(t.go()))
    }

    @Test fun t10DateAnnulee_ficheEnVolPartagee() = kotlinx.coroutines.runBlocking<Unit> {
        val f = fake().apply { wfs = { Thread.sleep(300); reply(200, "application/json", fixture("wfs-concorde.json")) }
            csw = { Thread.sleep(300); reply(200, "application/xml", fixture("csw-bdortho-summary.xml")) } }
        val s = service(f)
        val r = s.go() as SatelliteResult.Ok
        // Image remplacée pendant sa date : la date est annulée (rien de rendu)…
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { s.date(r.overlay.plan.request); error("date appliquée") }
        kotlinx.coroutines.delay(80)
        job.cancel(); job.join()
        // … mais la fiche en vol continue et sert la date suivante (aucune 2e fiche).
        val r2 = s.go(grow = 20_000f) as SatelliteResult.Ok
        assertEquals(y2024, s.date(r2.overlay.plan.request))
        assertEquals(1, f.cswCalls)
        assertEquals(updated, s.datasetUpdateForExport())
    }

    @Test fun t10PdfFicheRedemandeeAvantLExport() = kotlinx.coroutines.runBlocking<Unit> {
        val f = fake().apply { wfs = { reply(500) }; csw = { reply(500) } }
        val s = service(f)
        assertNull(s.dated(s.go()))
        assertEquals(1, f.cswCalls)
        // Geste « PDF » : redemandée malgré la panne récente.
        f.csw = { reply(200, "application/xml", fixture("csw-bdortho-summary.xml")) }
        assertEquals(updated, s.datasetUpdateForExport(1_000))
        assertEquals(2, f.cswCalls)
        // Connue : plus redemandée.
        assertEquals(updated, s.datasetUpdateForExport(1_000))
        assertEquals(2, f.cswCalls)
        // Toujours en panne : null dans la borne.
        val g = fake().apply { csw = { never(5_000) } }
        val t0 = System.nanoTime()
        assertNull(service(g).datasetUpdateForExport(300))
        assertTrue((System.nanoTime() - t0) / 1e6 < 900)
    }

    @Test fun t10PannePassagereRegle() {
        assertEquals(listOf(true, true, true), listOf(502, 503, 504).map { IgnImagery.isTransientFailure(it, "text/html", "<html>Bad Gateway</html>") })
        assertEquals(listOf(false, false, false, false), listOf(500, 429, 403, 401).map { IgnImagery.isTransientFailure(it, "text/xml", lnd) })
        assertTrue(IgnImagery.isTransientFailure(200, "text/xml", lnd))
        assertTrue(IgnImagery.isTransientFailure(400, "application/xml", owsLnd))
        assertTrue(IgnImagery.isTransientFailure(404, "", lnd))
        assertFalse(IgnImagery.isTransientFailure(400, "text/xml", ipv))
        assertFalse(IgnImagery.isTransientFailure(200, "text/xml", "<ServiceExceptionReport/>"))
        assertFalse(IgnImagery.isTransientFailure(200, "image/jpeg", lnd))
        assertFalse(IgnImagery.isTransientFailure(200, "application/json", lnd))
        assertFalse(IgnImagery.isTransientFailure(200, "text/xml", lnd + " ".repeat(IgnImagery.TRANSIENT_MAX_BODY)))
        assertEquals(1_000L, IgnImagery.TRANSIENT_RETRY_MS)
        // Corps trop gros : jamais lu comme une panne passagère.
        assertFalse(SatelliteHttpReply(200, "text/xml", ByteArray(IgnImagery.TRANSIENT_MAX_BODY + 1) { 'x'.code.toByte() }).isTransient)
    }

    @Test fun t10PannePassagereGetMap() {
        // 503 puis l'image : publiée après la pause, même requête.
        val f1 = fake()
        var n1 = 0
        f1.getMap = { if (n1++ == 0) reply(503) else SatelliteHttpReply(200, "image/jpeg", "IMG".toByteArray()) }
        val s1 = service(f1, retryMs = 150)
        val t0 = System.nanoTime()
        assertTrue(s1.go() is SatelliteResult.Ok)
        assertTrue("après la pause", (System.nanoTime() - t0) / 1e6 >= 145)
        assertEquals(2, f1.getMaps)
        assertEquals(f1.calls[0], f1.calls[1])
        assertEquals(1, s1.stats.transientRetries)
        // LayerNotDefined en 200 (XML), en 400 (OWS) puis l'image.
        for (first in listOf(reply(200, "text/xml", lnd), reply(400, "application/xml", owsLnd))) {
            val f = fake(); var n = 0
            f.getMap = { if (n++ == 0) first else SatelliteHttpReply(200, "image/jpeg", "IMG".toByteArray()) }
            assertTrue(service(f).go() is SatelliteResult.Ok)
            assertEquals(2, f.getMaps)
        }
        // Panne qui dure : DEUX requêtes au plus, puis l'erreur ; pas de relance avant 30 s.
        for (st in listOf(502, 503, 504)) {
            val f = fake().apply { getMap = { reply(st) } }
            val s = service(f)
            assertEquals(SatelliteResult.Error(SatelliteErrorKind.HTTP, st), s.go())
            assertEquals("$st : borné à un nouvel essai", 2, f.getMaps)
            clock += 10_000
            s.go()
            assertEquals("$st : pas de relance avant 30 s", 2, f.getMaps)
        }
        // Jamais sur les autres erreurs.
        for (r in listOf(reply(500), reply(429), reply(400, "text/xml", ipv), reply(200, "text/xml", "<ServiceExceptionReport/>"))) {
            val f = fake().apply { getMap = { r } }
            val s = service(f)
            assertTrue(s.go() is SatelliteResult.Error)
            assertEquals("${r.status} : aucun nouvel essai", 1, f.getMaps)
            assertEquals(0, s.stats.transientRetries)
        }
        // Annulé pendant la pause : aucun second GetMap.
        val f3 = fake().apply { getMap = { reply(503) } }
        val s3 = service(f3, retryMs = 50)
        try {
            s3.load(paris, -50_000f, -40_000f, 50_000f, 40_000f, force = false) {
                throw kotlinx.coroutines.CancellationException("annulé")
            }
            error("annulation attendue")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertEquals(1, f3.getMaps)
    }

    @Test fun t10PannePassagereDate() {
        // WFS 504 puis les années : prise de vue 2024 (et non la seule date de mise à jour).
        val f = fake(); var n = 0
        f.wfs = { if (n++ == 0) reply(504) else reply(200, "application/json", fixture("wfs-concorde.json")) }
        val s = service(f)
        assertEquals(y2024, s.dated(s.go()))
        assertEquals(listOf(2, 1), listOf(f.wfsCalls, f.cswCalls))
        assertEquals(1, s.stats.transientRetries)
        // WFS LayerNotDefined (200) puis les années.
        val g = fake(); var m = 0
        g.wfs = { if (m++ == 0) reply(200, "text/xml", lnd) else reply(200, "application/json", fixture("wfs-concorde.json")) }
        val t = service(g)
        assertEquals(y2024, t.dated(t.go()))
        // CSW 503 puis la fiche (WFS en échec non passager) : date de mise à jour.
        val h = fake(); var k = 0
        h.wfs = { reply(500) }
        h.csw = { if (k++ == 0) reply(503) else reply(200, "application/xml", fixture("csw-bdortho-summary.xml")) }
        val u = service(h)
        assertEquals(updated, u.dated(u.go()))
        assertEquals(listOf(1, 2), listOf(h.wfsCalls, h.cswCalls))
        // Panne qui dure : un nouvel essai chacun, puis mention sans date.
        val i = fake().apply { wfs = { reply(503) }; csw = { reply(502) } }
        val v = service(i)
        assertNull(v.dated(v.go()))
        assertEquals(listOf(2, 2), listOf(i.wfsCalls, i.cswCalls))
        // Dans la borne de la date : moins de 50 ms restantes après la pause → pas de nouvel essai.
        val j = fake().apply { wfs = { reply(503) }; csw = { reply(503) } }
        val w = service(j, infoTimeoutMs = 120, retryMs = 100)
        val t0 = System.nanoTime()
        assertNull(w.dated(w.go()))
        assertEquals(listOf(1, 1), listOf(j.wfsCalls, j.cswCalls))
        assertTrue((System.nanoTime() - t0) / 1e6 < 400)
    }

    @Test fun t10ReponsesEnErreur() {
        // 200 mais pas une image (ServiceException XML) → http(200).
        val xml = fake().apply { getMap = { SatelliteHttpReply(200, "text/xml", "<x/>".toByteArray()) } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.HTTP, 200), service(xml).go())
        assertEquals(1, xml.getMaps)
        // 400 (ServiceExceptionReport) → http(400).
        val bad = fake().apply { getMap = { SatelliteHttpReply(400, "text/xml", ByteArray(0)) } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.HTTP, 400), service(bad).go())
        // Réseau injoignable.
        val net = fake().apply { getMap = { throw IOException("hôte inconnu") } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.NETWORK), service(net).go())
        // Image illisible.
        val junk = fake().apply { getMap = { SatelliteHttpReply(200, "image/jpeg", "???".toByteArray()) } }
        assertEquals(SatelliteResult.Error(SatelliteErrorKind.DECODE), service(junk).go())
        assertEquals(0, junk.wfsCalls + junk.cswCalls)
    }

    @Test fun t10DelaiPasDeRelanceAvant30s() {
        val f = fake().apply { getMap = { throw SocketTimeoutException("30 s") } }
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
