package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Requête d'export du fond satellite (ArcGIS World Imagery, EPSG:4326).
 *
 * Le serveur rend des pixels CARRÉS EN DEGRÉS : si `size` n'a pas le rapport
 * Δlon/Δlat en degrés, il ÉLARGIT l'emprise rendue (vérifié au curl). L'ancien
 * calcul prenait le rapport en mètres → image étirée nord-sud de ≈ 1/cos(lat),
 * puisque les coins étaient posés sur l'emprise DEMANDÉE. [rendu] reproduit ce
 * comportement du serveur ; tout est vérifié à 1 px près.
 */
class SatelliteRequestTest {

    private val m = SatelliteFetcher.M_PER_DEG
    private fun cosd(lat: Double) = cos(lat * PI / 180.0)

    /** Boîte de `ewM` × `nsM` mètres centrée sur (lat, lon). */
    private fun boxM(lat: Double, lon: Double, ewM: Double, nsM: Double) = GeoBox(
        lat - nsM / 2 / m, lon - ewM / 2 / (m * cosd(lat)),
        lat + nsM / 2 / m, lon + ewM / 2 / (m * cosd(lat))
    )

    private fun request(needed: GeoBox): Pair<GeoBox, SatelliteRequest> {
        val area = assertNotNullAnd(SatelliteFetcher.areaFor(needed))
        return area to assertNotNullAnd(SatelliteFetcher.requestForArea(area))
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }

    /** Emprise que le serveur REND pour (bbox, w, h) : pixels carrés en degrés, il
     *  élargit l'axe trop court autour du centre (comportement mesuré au curl). */
    private fun rendu(r: SatelliteRequest): GeoBox {
        val b = r.bbox
        val d = max(b.dLon / r.width, b.dLat / r.height)
        val cLat = (b.minLat + b.maxLat) / 2; val cLon = (b.minLon + b.maxLon) / 2
        return GeoBox(cLat - r.height * d / 2, cLon - r.width * d / 2, cLat + r.height * d / 2, cLon + r.width * d / 2)
    }

    /** Écart en PIXELS entre deux boîtes (pire bord), au pas de la requête. */
    private fun ecartPx(a: GeoBox, b: GeoBox, degPerPx: Double) = listOf(
        a.minLat - b.minLat, a.maxLat - b.maxLat, a.minLon - b.minLon, a.maxLon - b.maxLon
    ).maxOf { abs(it) } / degPerPx

    /** Vérifs communes : rapport en degrés, emprise ⊇ zone à < 1 px par axe, rendu = demandé. */
    private fun verifieGeometrie(label: String, area: GeoBox, r: SatelliteRequest) {
        val b = r.bbox
        // Rapport w/h = Δlon/Δlat EN DEGRÉS (emprise finale), à 1 px près (en fait exact).
        assertTrue("$label : w=${r.width} h=${r.height} rapport ${b.dLon / b.dLat}",
            abs(r.width - r.height * b.dLon / b.dLat) <= 1.0)
        assertEquals("$label : largeur", b.dLon, r.width * r.degPerPx, 1e-9 * b.dLon)
        assertEquals("$label : hauteur", b.dLat, r.height * r.degPerPx, 1e-9 * b.dLat)
        // Chaque axe à moins d'1 px de la zone voulue, et la couvre entièrement.
        assertTrue("$label : couvre la zone", b.contains(area, 1e-12))
        assertTrue("$label : Δlon en px", (b.dLon - area.dLon) / r.degPerPx < 1.0 + 1e-6)
        assertTrue("$label : Δlat en px", (b.dLat - area.dLat) / r.degPerPx < 1.0 + 1e-6)
        // Le serveur rend EXACTEMENT l'emprise demandée.
        val e = ecartPx(rendu(r), b, r.degPerPx)
        assertTrue("$label : rendu ≠ demandé de $e px", e < 1.0)
        assertTrue("$label : taille ${r.width}×${r.height}",
            r.width in SatelliteFetcher.MIN_PX..SatelliteFetcher.MAX_PX &&
                r.height in SatelliteFetcher.MIN_PX..SatelliteFetcher.MAX_PX)
    }

    @Test fun rapportEnDegresAUnPixelPresAToutesLatitudes() {
        // Équateur, Paris, 60° N, Gold Coast (hémisphère sud) ; zones larges et hautes.
        for ((lat, lon) in listOf(0.0 to 37.5, 48.86 to 2.35, 60.0 to 24.94, -28.0 to 153.43)) {
            for ((ew, ns) in listOf(200.0 to 150.0, 80.0 to 300.0, 250.0 to 250.0)) {
                val (area, r) = request(boxM(lat, lon, ew, ns))
                verifieGeometrie("lat=$lat ${ew}×$ns m", area, r)
            }
        }
    }

    @Test fun parisLeRapportNEstPasCeluiDesMetres() {
        // 250 × 250 m à Paris : carré en mètres, mais PAS en degrés (Δlon = Δlat / cos).
        val (_, r) = request(boxM(48.86, 2.35, 250.0, 250.0))
        val ratio = r.width.toDouble() / r.height
        assertEquals(1.0 / cosd(48.86), ratio, 0.01)
        assertTrue("l'image doit être plus large que haute (w=${r.width} h=${r.height})", r.width > r.height * 1.4)
    }

    @Test fun resolutionSureEstOuestEtJamaisPlusFine() {
        for (lat in listOf(0.0, 48.86, 60.0, -28.0)) {
            for ((ew, ns) in listOf(5.0 to 5.0, 200.0 to 150.0, 2000.0 to 1500.0, 50.0 to 4000.0)) {
                val (_, r) = request(boxM(lat, 10.0, ew, ns))
                val c = cosd((r.bbox.minLat + r.bbox.maxLat) / 2)
                val ewMpx = r.degPerPx * m * c
                val nsMpx = r.degPerPx * m
                assertTrue("lat=$lat ${ew}×$ns : $ewMpx m/px E-O", ewMpx >= SatelliteFetcher.TARGET_M_PER_PX - 1e-9)
                assertTrue("N-S jamais plus fin que E-O", nsMpx >= ewMpx - 1e-12)
            }
        }
    }

    @Test fun petiteZoneEtendueA120Metres() {
        val (area, r) = request(boxM(48.86, 2.35, 5.0, 5.0))
        verifieGeometrie("5×5 m", area, r)
        val c = cosd(48.86)
        assertTrue(area.dLat * m >= 120.0 - 1e-6)
        assertTrue(area.dLon * m * c >= 120.0 - 1e-6)
        // 120 m à 0,3 m/px d'est en ouest = 400 px ; nord-sud : 120·cos/0,3 ≈ 264 px.
        assertTrue("w=${r.width}", r.width in 400..401)
        assertTrue("h=${r.height}", r.height in 263..265)
        assertEquals(0.30, r.degPerPx * m * cosd((r.bbox.minLat + r.bbox.maxLat) / 2), 1e-9)
    }

    @Test fun plafondDePixelsRespecte() {
        val (area, r) = request(boxM(48.86, 2.35, 2000.0, 1500.0))
        verifieGeometrie("2000×1500 m", area, r)
        assertEquals(SatelliteFetcher.MAX_PX, max(r.width, r.height))
        // Au plafond : la résolution se dégrade, l'emprise est conservée.
        assertEquals(max(area.dLat, area.dLon) / SatelliteFetcher.MAX_PX, r.degPerPx, 1e-15)
    }

    @Test fun zoneTresAllongeeEstOuestSansEtirement() {
        // 3 km × 60 m : l'ancien plancher à 256 px déformait le rapport.
        val (area, r) = request(boxM(48.86, 2.35, 3000.0, 60.0))
        verifieGeometrie("3000×60 m", area, r)
        assertEquals(SatelliteFetcher.MAX_PX, r.width)
        assertTrue("h=${r.height}", r.height < 256)
    }

    @Test fun zoneTresAllongeeNordSudSansEtirement() {
        val (area, r) = request(boxM(60.0, 24.94, 60.0, 3000.0))
        verifieGeometrie("60×3000 m", area, r)
        // Plafond sur le grand côté EN MÈTRES ramené à l'est-ouest (échelle de
        // l'ancien calcul) : une zone haute a ≈ MAX_PX·cos(lat) px de haut.
        val attendu = SatelliteFetcher.MAX_PX * cosd((area.minLat + area.maxLat) / 2)
        assertTrue("h=${r.height} ≈ $attendu", abs(r.height - attendu) <= 1.0)
        assertTrue("w=${r.width}", r.width < 256)
    }

    // ---------------------------------------------------------------------------
    // Régressions du correctif 7b6ced7 (relecture), vérifiées sur le vrai serveur.
    // ---------------------------------------------------------------------------

    /**
     * Copie FIDÈLE de l'ancien calcul (575783b) : degPerPx que le serveur RENDAIT,
     * soit max(Δlon/w, Δlat/h) (il élargit l'axe court, pixels carrés en degrés).
     */
    private fun ancienDegPerPxRendu(needed: GeoBox): Double {
        var minLat = needed.minLat; var maxLat = needed.maxLat
        var minLon = needed.minLon; var maxLon = needed.maxLon
        val padLat = (maxLat - minLat) * 0.08 + 1e-6
        val padLon = (maxLon - minLon) * 0.08 + 1e-6
        minLat -= padLat; maxLat += padLat; minLon -= padLon; maxLon += padLon
        val cosLat = cos((minLat + maxLat) / 2.0 * PI / 180.0)
        val minSpanM = 120.0
        var latSpanM = (maxLat - minLat) * 111_320.0
        var lonSpanM = (maxLon - minLon) * 111_320.0 * cosLat
        if (latSpanM < minSpanM) {
            val g = (minSpanM - latSpanM) / 2.0 / 111_320.0; minLat -= g; maxLat += g; latSpanM = minSpanM
        }
        if (lonSpanM < minSpanM) {
            val g = (minSpanM - lonSpanM) / 2.0 / (111_320.0 * cosLat); minLon -= g; maxLon += g; lonSpanM = minSpanM
        }
        val majorM = max(latSpanM, lonSpanM)
        val maxDim = (majorM / 0.30).coerceIn(256.0, 1024.0)
        val aspect = lonSpanM / max(latSpanM, 1e-9)
        val w = (if (aspect >= 1) maxDim else maxDim * aspect).coerceAtLeast(256.0).toInt()
        val h = (if (aspect >= 1) maxDim / aspect else maxDim).coerceAtLeast(256.0).toInt()
        return max((maxLon - minLon) / w, (maxLat - minLat) / h)
    }

    /**
     * Défaut 1 : pour une zone HAUTE plafonnée (> 307 m nord-sud), 7b6ced7 demandait
     * cos(lat) fois plus fin que l'ancien calcul. Mesuré au curl (200 × 480 m) :
     * Australie (−30, 135) ancien 0,63 m/px → 200, 7b6ced7 0,544 → 500 ; idem
     * Maroc (31,6, −7) et Inde (22, 78). Règle : JAMAIS plus fin que l'ancien.
     */
    @Test fun jamaisPlusFinQueLAncienCalcul() {
        val lats = listOf(0.0, 22.0, -30.0, 31.6, 48.86, 60.0, 63.5, -45.0)
        val tailles = listOf(
            5.0 to 5.0, 100.0 to 80.0, 200.0 to 480.0, 80.0 to 300.0, 60.0 to 3000.0, 50.0 to 4000.0,
            300.0 to 1200.0, 2000.0 to 1500.0, 3000.0 to 60.0, 480.0 to 200.0, 1000.0 to 1000.0, 400.0 to 500.0
        )
        for (lat in lats) for ((ew, ns) in tailles) {
            val needed = boxM(lat, 10.0, ew, ns)
            val (_, r) = request(needed)
            val ancien = ancienDegPerPxRendu(needed)
            // Tolérance : l'ancien code TRONQUAIT w et h (toInt, ≥ 256 px), ce qui le
            // rendait plus grossier d'au plus 1 px sur 256 (0,4 %) — bien moins que
            // l'écart d'un niveau de cache (×2) ou que l'erreur de 7b6ced7 (cos(lat)).
            assertTrue("lat=$lat ${ew}×$ns : ${r.degPerPx * m} < ancien ${ancien * m} m/px",
                r.degPerPx >= ancien * (1 - 1.0 / 256))
        }
    }

    @Test fun zoneHauteRuraleMemeEchelleQueLAncien() {
        // Le cas mesuré : 200 × 480 m en Australie rurale. Ancien = 0,629 m/px (200).
        val needed = boxM(-30.0, 135.0, 200.0, 480.0)
        val (area, r) = request(needed)
        verifieGeometrie("Australie 200×480 m", area, r)
        val ancien = ancienDegPerPxRendu(needed)
        assertEquals(0.629, ancien * m, 0.002)
        // Même échelle à 0,2 % près (constantes m/° : 111 320 avant, sphère de 6 371 km
        // maintenant) ; 7b6ced7 était à 0,544 m/px (−13 %), soit cos(30°) fois plus fin.
        assertEquals(ancien, r.degPerPx, ancien * 2e-3)
        assertTrue("au-dessus du niveau 18 (≈ 0,597 m/px) : ${r.degPerPx * m}", r.degPerPx * m > 0.6)
        assertTrue(r.height <= SatelliteFetcher.MAX_PX && r.width <= SatelliteFetcher.MAX_PX)
    }

    /** Calibration à 1 ancre au Kenya rural (0, 37,5) : nord = +Y, 1000 mm/m. */
    private fun calibKenya() = GeoCalibration().apply { addAnchor(GeoAnchor(0f, 0f, 0.0, 37.5)) }

    /** Résolution du niveau 18 du cache (deg/px) : seuil mesuré en zone rurale. */
    private val niveau18 = 360.0 / (256.0 * (1 shl 18))

    /**
     * Défaut 2 : contenu de 100 × 80 m au Kenya rural → 0,30 m/px → ArcGIS répond
     * 500 (l'imagerie s'arrête au niveau 18) ; la 2e tentative renvoyait la MÊME URL.
     * Désormais chaque essai est 2× plus grossier et a SES coins.
     */
    @Test fun essaisDeSecoursDePlusEnPlusGrossiersAvecLeursCoins() {
        val calib = calibKenya()
        val plans = SatelliteFetcher.plansFor(calib, -50_000f, -40_000f, 50_000f, 40_000f)
        assertEquals(SatelliteFetcher.ATTEMPTS, plans.size)
        assertEquals(plans.size, plans.map { it.request.url }.toSet().size)
        assertEquals(plans[0].request.url, SatelliteFetcher.planFor(calib, -50_000f, -40_000f, 50_000f, 40_000f)!!.request.url)
        for ((i, p) in plans.withIndex()) {
            val r = p.request
            assertEquals("essai $i : ×${1 shl i}", plans[0].request.degPerPx * (1 shl i), r.degPerPx, 1e-15)
            // Coins = emprise de CET essai (pas celle du premier).
            val b = r.bbox
            val nw = calib.worldPosition(b.maxLat, b.minLon)!!
            val se = calib.worldPosition(b.minLat, b.maxLon)!!
            assertEquals(nw.first, p.nwX, 0f); assertEquals(nw.second, p.nwY, 0f)
            assertEquals(se.first, p.seX, 0f); assertEquals(se.second, p.seY, 0f)
            // Géométrie toujours exacte (rapport en degrés, rendu = demandé) et contenu couvert.
            assertTrue(abs(r.width * r.degPerPx - b.dLon) < 1e-12 && abs(r.height * r.degPerPx - b.dLat) < 1e-12)
            assertTrue(p.nwX <= -50_000f && p.seX >= 50_000f && p.seY <= -40_000f && p.nwY >= 40_000f)
        }
        // Le 1er essai est sous le niveau 18 (500 en zone rurale), le 2e au-dessus.
        assertTrue(plans[0].request.degPerPx < niveau18 * 0.9)
        assertTrue(plans[1].request.degPerPx > niveau18)
    }

    @Test fun premierEssaiReussiEtJamaisDeuxFoisLaMemeUrl() {
        val plans = SatelliteFetcher.plansFor(calibKenya(), -50_000f, -40_000f, 50_000f, 40_000f)
        // Serveur simulé d'après les mesures (seuil entre 0,90 et 0,99 × niveau 18) :
        // 500 plus fin que 0,95 × niveau 18, image sinon.
        val demandees = mutableListOf<String>()
        val rural = SatelliteFetcher.firstSuccess<String>(plans) { p ->
            demandees += p.request.url
            if (p.request.degPerPx < niveau18 * 0.95) null else "image"
        }
        assertNotNull(rural)
        assertEquals(plans[1], rural!!.first)
        assertEquals(listOf(plans[0].request.url, plans[1].request.url), demandees)
        // En ville (imagerie fine) : un seul appel, le plus fin.
        demandees.clear()
        val ville = SatelliteFetcher.firstSuccess<String>(plans) { p -> demandees += p.request.url; "image" }
        assertEquals(plans[0], ville!!.first)
        assertEquals(1, demandees.size)
        // Rien nulle part : null après ATTEMPTS URL toutes DIFFÉRENTES.
        demandees.clear()
        assertNull(SatelliteFetcher.firstSuccess<String>(plans) { p -> demandees += p.request.url; null })
        assertEquals(SatelliteFetcher.ATTEMPTS, demandees.size)
        assertEquals(demandees.size, demandees.toSet().size)
    }

    @Test fun planchersAgrandissentLEmpriseAuLieuDEtirer() {
        // 30 km × 50 m : la hauteur calculée tombe sous MIN_PX → emprise ÉLARGIE
        // nord-sud (16 px), rapport toujours exact.
        val (area, r) = request(boxM(48.86, 2.35, 30_000.0, 50.0))
        assertEquals(SatelliteFetcher.MIN_PX, r.height)
        assertTrue("élargie nord-sud", r.bbox.dLat > area.dLat * 1.5)
        assertTrue(r.bbox.contains(area))
        assertTrue(ecartPx(rendu(r), r.bbox, r.degPerPx) < 1.0)
        assertEquals(r.bbox.dLon / r.bbox.dLat, r.width.toDouble() / r.height, 1e-9)
    }

    private fun calibParis() = GeoCalibration().apply {
        // 2 ancres : rotation ET échelle non triviales.
        addAnchor(GeoAnchor(0f, 0f, 48.8656, 2.3212))
        addAnchor(GeoAnchor(50_000f, 20_000f, 48.8658, 2.3218))
    }

    @Test fun coinsCalculesSurLEmpriseFinale() {
        val calib = calibParis()
        val p = assertNotNullAnd(SatelliteFetcher.planFor(calib, -40_000f, -25_000f, 90_000f, 60_000f))
        val b = p.request.bbox
        val nw = calib.worldPosition(b.maxLat, b.minLon)!!
        val ne = calib.worldPosition(b.maxLat, b.maxLon)!!
        val se = calib.worldPosition(b.minLat, b.maxLon)!!
        val sw = calib.worldPosition(b.minLat, b.minLon)!!
        assertEquals(nw.first, p.nwX, 0f); assertEquals(nw.second, p.nwY, 0f)
        assertEquals(ne.first, p.neX, 0f); assertEquals(ne.second, p.neY, 0f)
        assertEquals(se.first, p.seX, 0f); assertEquals(se.second, p.seY, 0f)
        assertEquals(sw.first, p.swX, 0f); assertEquals(sw.second, p.swY, 0f)
        // Retour en lat/lon : les coins SONT ceux de l'emprise finale (≈ 1 mm près).
        val g = calib.latLonOf(p.nwX, p.nwY)!!
        assertEquals(b.maxLat, g.first, 1e-8); assertEquals(b.minLon, g.second, 1e-8)
        val h = calib.latLonOf(p.seX, p.seY)!!
        assertEquals(b.minLat, h.first, 1e-8); assertEquals(b.maxLon, h.second, 1e-8)
        // Le contenu tient dans l'image.
        for ((x, y) in listOf(-40_000f to -25_000f, 90_000f to -25_000f, 90_000f to 60_000f, -40_000f to 60_000f)) {
            val ll = calib.latLonOf(x, y)!!
            assertTrue(ll.first in b.minLat..b.maxLat && ll.second in b.minLon..b.maxLon)
        }
        // Image ↔ monde : pixels carrés EN DEGRÉS → en mètres, un pixel E-O vaut
        // cos(lat) pixel N-S (côté NO→NE sur w px, côté NO→SO sur h px).
        val ewMmPerPx = Math.hypot((p.neX - p.nwX).toDouble(), (p.neY - p.nwY).toDouble()) / p.request.width
        val nsMmPerPx = Math.hypot((p.swX - p.nwX).toDouble(), (p.swY - p.nwY).toDouble()) / p.request.height
        assertEquals(cosd(48.8656), ewMmPerPx / nsMmPerPx, 1e-4)
    }

    @Test fun urlDecimaleIndependanteDeLaLangue() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            // Près de Greenwich : petites longitudes (Double.toString donnerait « 1.0E-4 »).
            val (_, r) = request(boxM(51.4779, 0.0001, 100.0, 100.0))
            val url = r.url
            assertTrue(url.startsWith(SatelliteFetcher.EXPORT_URL + "?"))
            assertFalse("pas de notation exponentielle : $url", url.contains("E-") || url.contains("E+"))
            val q = URI(url).rawQuery.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
            val bb = q.getValue("bbox").split(",").map { it.toDouble() }
            assertEquals(listOf(r.bbox.minLon, r.bbox.minLat, r.bbox.maxLon, r.bbox.maxLat), bb)
            assertEquals("${r.width},${r.height}", q["size"])
            assertEquals("4326", q["bboxSR"]); assertEquals("4326", q["imageSR"])
            assertEquals("image", q["f"])
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun cleDeCacheCoherenteAvecLaRequete() {
        val calib = calibParis()
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
        assertNull(SatelliteFetcher.areaFor(boxM(88.0, 0.0, 100.0, 100.0)))
        assertNull(SatelliteFetcher.areaFor(GeoBox(Double.NaN, 0.0, 1.0, 1.0)))
        assertNull(SatelliteFetcher.areaFor(boxM(10.0, 179.9999, 200.0, 200.0)))
    }
}
