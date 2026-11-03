package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * PREUVE sur le VRAI service IGN (réseau) — IGNORÉE par défaut (hors ligne, CI).
 * À lancer à la main :
 *   MVR_IGN_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*SatelliteIgnLiveTest'
 *
 * Le code de l'app est utilisé tel quel (zone, requête, URL, transport HTTP avec
 * User-Agent, service, test « image vide », lecture de l'année) ; seul le décodage
 * passe par ImageIO au lieu de BitmapFactory (JVM ; appelé par réflexion, les tests
 * Android compilant contre android.jar qui n'a pas java.awt). JPEG reçus écrits dans
 * build/ign-proof/ pour un contrôle visuel. Une requête par seconde au plus.
 */
class SatelliteIgnLiveTest {

    private val out = File("build/ign-proof").apply { mkdirs() }
    private val calls = mutableListOf<String>()

    companion object {
        /** Instant du dernier appel, PARTAGÉ entre les tests : ≥ 1,1 s entre deux requêtes. */
        @Volatile private var lastCall = 0L
    }

    private var lastBody = ByteArray(0)

    private val http = SatelliteHttp { url, timeout ->
        val wait = 1100 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) Thread.sleep(wait)
        lastCall = System.currentTimeMillis()
        calls += url
        SatelliteFetcher.UrlConnectionHttp(SatelliteFetcher.userAgent("test")).get(url, timeout).also {
            if ("REQUEST=GetMap" in url) lastBody = it.body
        }
    }

    /** Image décodée par javax.imageio (réflexion) : largeur, hauteur, pixels ARGB. */
    private class JvmImage(val width: Int, val height: Int, val argb: IntArray)

    private fun decodeJvm(bytes: ByteArray): JvmImage? {
        val img = Class.forName("javax.imageio.ImageIO")
            .getMethod("read", java.io.InputStream::class.java)
            .invoke(null, bytes.inputStream()) ?: return null
        val c = img.javaClass
        val w = c.getMethod("getWidth").invoke(img) as Int
        val h = c.getMethod("getHeight").invoke(img) as Int
        val px = IntArray(w * h)
        val i = Int::class.javaPrimitiveType!!
        c.getMethod("getRGB", i, i, i, i, IntArray::class.java, i, i).invoke(img, 0, 0, w, h, px, 0, w)
        return JvmImage(w, h, px)
    }

    private fun service() = SatelliteService<JvmImage>(
        http = http,
        decode = { b -> decodeJvm(b) },
        isBlank = { SatelliteFetcher.isBlankPixels(it.argb, it.width, it.height) }
    )

    private fun calibAt(lat: Double, lon: Double) = GeoCalibration().apply { addAnchor(GeoAnchor(0f, 0f, lat, lon)) }

    private fun live() = assumeTrue("MVR_IGN_LIVE=1 pour interroger le vrai service", System.getenv("MVR_IGN_LIVE") == "1")

    /** Charge, vérifie la taille de l'image = celle de la requête, écrit le JPEG. */
    private fun proveImage(name: String, lat: Double, lon: Double, halfW: Float, halfH: Float, w: Int? = null, h: Int? = null) {
        val calib = calibAt(lat, lon)
        val plan = SatelliteFetcher.planFor(calib, -halfW, -halfH, halfW, halfH)!!
        if (w != null) assertEquals("$name : largeur demandée", w, plan.request.width)
        if (h != null) assertEquals("$name : hauteur demandée", h, plan.request.height)
        val before = calls.size
        val r = service().load(calib, -halfW, -halfH, halfW, halfH, force = false)
        assertTrue("$name : $r\n  ${plan.request.url}", r is SatelliteResult.Ok)
        val ok = (r as SatelliteResult.Ok).overlay
        assertEquals("$name : largeur reçue = demandée", plan.request.width, ok.image.width)
        assertEquals("$name : hauteur reçue = demandée", plan.request.height, ok.image.height)
        assertEquals("$name : même URL", plan.request.url, calls[before])
        assertEquals("$name : GetMap + GetFeatureInfo", 2, calls.size - before)
        File(out, "$name.jpg").writeBytes(lastBody)
        println("PREUVE $name : ${ok.image.width}×${ok.image.height} px, ${"%.3f".format(plan.request.mPerPx)} m/px, " +
            "année ${ok.year}\n  ${plan.request.url}")
    }

    @Test fun parisConcorde() {
        live()
        // Show de 100 × 80 m centré sur l'obélisque → zone 120 m → 601 × 601 à 0,20 m/px (T5).
        proveImage("paris-concorde", 48.8656, 2.3212, 50_000f, 40_000f, 601, 601)
    }

    @Test fun lyonBellecourPlafond1024() {
        live()
        // 400 × 250 m : au-delà de 1024 px à 0,20 m/px → plafond Android, pixels carrés au sol.
        val calib = calibAt(45.7578, 4.8320)
        val p = SatelliteFetcher.planFor(calib, -200_000f, -125_000f, 200_000f, 125_000f)!!
        assertEquals(1024, p.request.width)
        proveImage("lyon-bellecour", 45.7578, 4.8320, 200_000f, 125_000f, w = 1024)
    }

    @Test fun fortDeFranceMartinique() {
        live()
        proveImage("fort-de-france", 14.6037, -61.0730, 60_000f, 60_000f)
    }

    @Test fun horsFranceDetecte() {
        live()
        // Berlin : pré-filtre, AUCUNE requête.
        val before = calls.size
        assertEquals(SatelliteResult.OutOfCoverage,
            service().load(calibAt(52.5163, 13.3777), -50_000f, -40_000f, 50_000f, 40_000f, force = false))
        assertEquals(0, calls.size - before)
        // Genève : dans l'emprise métropolitaine élargie, mais l'IGN n'y a pas
        // d'imagerie → image BLANCHE reconnue, pas de GetFeatureInfo.
        assertTrue(ignCovers(46.2044, 6.1432))
        val r = service().load(calibAt(46.2044, 6.1432), -50_000f, -40_000f, 50_000f, 40_000f, force = false)
        assertEquals(SatelliteResult.NoImagery, r)
        assertEquals(1, calls.size - before)
        assertFalse(calls.last().contains("GetFeatureInfo"))
        println("PREUVE hors France : Berlin → hors couverture (0 requête) ; Genève → image blanche → « pas d'imagerie »\n  ${calls.last()}")
    }
}
