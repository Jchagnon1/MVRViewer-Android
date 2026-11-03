package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Position approximative (guide C8) : les deux permissions déclarées et demandées
 * ENSEMBLE ; avec l'approximative seule, la position marche en dégradé et l'app le
 * dit (message au retour du dialogue, panneau Position GPS, aide « Je suis ici ») ;
 * un refus garde le message actuel.
 */
class LocationAccessTest {

    private fun file(vararg candidates: String): File = candidates.map(::File).first { it.isFile }
    private fun src(path: String) =
        file("src/main/java/com/minou/mvrviewer/$path", "app/src/main/java/com/minou/mvrviewer/$path").readText()

    @Test fun niveauSelonLesPermissionsAccordees() {
        assertEquals(LocationAccess.PRECISE, LocationAccess.of(fine = true, coarse = true))
        assertEquals(LocationAccess.PRECISE, LocationAccess.of(fine = true, coarse = false))
        assertEquals(LocationAccess.APPROXIMATE, LocationAccess.of(fine = false, coarse = true))
        assertEquals(LocationAccess.NONE, LocationAccess.of(fine = false, coarse = false))
        // L'approximative seule SUFFIT : la position est accordée, en dégradé.
        assertTrue(LocationAccess.APPROXIMATE.granted)
        assertTrue(LocationAccess.APPROXIMATE.isApproximate)
        assertTrue(LocationAccess.PRECISE.granted)
        assertFalse(LocationAccess.PRECISE.isApproximate)
        assertFalse(LocationAccess.NONE.granted)
        assertFalse(LocationAccess.NONE.isApproximate)
    }

    @Test fun lesDeuxPermissionsDemandeesEnsemble() {
        assertArrayEquals(
            arrayOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            LocationAccess.REQUESTED
        )
        val loc = src("ui/UserLocation.kt")
        assertTrue(loc.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        assertTrue(loc.contains("launcher.launch(LocationAccess.REQUESTED)"))
        assertFalse("plus de demande de la seule position précise",
            loc.contains("launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)"))
        val manifest = file("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\" />"))
        assertTrue(manifest.contains("<uses-permission android:name=\"android.permission.ACCESS_COARSE_LOCATION\" />"))
    }

    @Test fun messageSeulementPourLApproximative() {
        assertEquals(R.string.location_approximate_toast, LocationAccess.grantMessage(LocationAccess.APPROXIMATE))
        assertNull("précise : rien à dire", LocationAccess.grantMessage(LocationAccess.PRECISE))
        assertNull("refus : la ligne « Localisation refusée » existante le dit",
            LocationAccess.grantMessage(LocationAccess.NONE))
        // Le refus reste déduit comme avant (accordée = jamais refusée).
        assertFalse(LocationPermissionRule.denied(granted = LocationAccess.APPROXIMATE.granted,
            askedBefore = true, rationale = true))
    }

    @Test fun panneauPositionGpsDitLaPrecisionLimitee() {
        val approx = GeoPanelContent.make(ProjectSurface.PLAN, calibrated = false, showSatellite = false,
            satelliteCovered = true, satelliteStatus = SatelliteStatus.Idle, approximateLocation = true)
        assertEquals(listOf(GeoPanelRow.SHOW_MY_LOCATION, GeoPanelRow.APPROXIMATE_LOCATION,
            GeoPanelRow.SATELLITE, GeoPanelRow.CALIBRATE_FIRST_HINT), approx.display)
        // « Je suis ici » reste proposé (dégradé, pas désactivé).
        assertTrue(GeoPanelRow.HERE_ANCHOR in approx.calibration)
        assertTrue(approx.showLocationEnabled)
        for (surface in ProjectSurface.entries) for (calibrated in listOf(false, true)) {
            assertFalse(GeoPanelRow.APPROXIMATE_LOCATION in GeoPanelContent.make(surface, calibrated, false, true,
                SatelliteStatus.Idle).display)
            assertTrue(GeoPanelRow.APPROXIMATE_LOCATION in GeoPanelContent.make(surface, calibrated, false, true,
                SatelliteStatus.Idle, approximateLocation = true).display)
        }
        // Branché dans les deux vues et dans l'aide de pose « Je suis ici ».
        val plan = src("ui/PlanScreen.kt")
        assertTrue(plan.contains("approximateLocation = locFeed.approximate"))
        assertTrue(plan.contains("if (locFeed.approximate) Text("))
        assertTrue(src("ui/Scene3DScreen.kt").contains("approximateLocation = locFeed3D.approximate"))
    }

    /**
     * Défaut relevé à l'émulateur : avec la seule position approximative, l'assistant
     * 3 points restait sur « restez à découvert, patientez » pour toujours. Les fixes
     * floutés (~2 km) ne sont JAMAIS retenus (≤ 20 m), ni « Utiliser quand même »
     * (≤ 100 m) : le panneau doit le dire, et non promettre que l'attente aboutira.
     */
    @Test fun troisPointsEnPositionApproximative() {
        var t = 1000.0
        val s = ThreePointCalibrationSession(null, clock = { t })
        s.tap(com.minou.mvrviewer.mvr.Vec2.ZERO)
        var key = 0.0
        repeat(120) { // 60 s de fixes approximatifs
            key += 1
            s.onRawFix(RawFix(45.69, 5.88, 2000.0, key, t)); t += 0.5; s.tick()
        }
        assertEquals(ThreePointStage.Measuring(0), s.stage)
        assertTrue(s.lowAccuracy)
        assertFalse("aucun repli possible", s.canForce)
        assertEquals(0.0, s.progress, 0.0)
        // Le panneau : la consigne propre remplace « Précision GPS insuffisante… patientez ».
        assertEquals(CalText(R.string.cal3_approximate_location), ThreePointDisplay.approximateNotice(true))
        assertNull(ThreePointDisplay.approximateNotice(false))
        assertFalse(ThreePointDisplay.showsLowAccuracy(lowAccuracy = s.lowAccuracy, approximate = true))
        assertTrue(ThreePointDisplay.showsLowAccuracy(lowAccuracy = s.lowAccuracy, approximate = false))
        // Branché : la vue plan passe l'état, le panneau l'affiche et ouvre les paramètres de l'app.
        assertTrue(src("ui/PlanScreen.kt").contains("approximate = locFeed.approximate, compact = compact,"))
        val panel = src("ui/ThreePointCalibrationPanel.kt")
        assertTrue(panel.contains("ThreePointDisplay.approximateNotice(approximate)?.let { notice ->"))
        assertTrue(panel.contains("openAppSettings(ctx)"))
        assertTrue(panel.contains("ThreePointDisplay.showsLowAccuracy(session.lowAccuracy, approximate)"))
        assertFalse(panel.contains("session.lowAccuracy && acc != null"))
        val loc = src("ui/UserLocation.kt")
        assertTrue(loc.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        // Revenu des Paramètres avec la position exacte : on se réinscrit (la requête
        // ouverte en approximative restait « COARSE », un fix toutes les 10 min).
        assertTrue(loc.contains("DisposableEffect(active, granted, feed.approximate) {"))
        // Textes : cause + chemin, en français et en anglais ; le panneau Position GPS le dit aussi.
        assertEquals("Position approximative : la calibration en 3 points demande la position exacte " +
            "(Paramètres › Applications › MVR Viewer › Autorisations › Position).",
            TestRes.FR.get("cal3_approximate_location"))
        assertEquals("Approximate location: 3-point calibration needs your precise location " +
            "(Settings › Apps › MVR Viewer › Permissions › Location).", TestRes.EN.get("cal3_approximate_location"))
        assertEquals("Ouvrir les paramètres", TestRes.FR.get("cal3_open_settings"))
        assertEquals("Open settings", TestRes.EN.get("cal3_open_settings"))
        assertTrue(TestRes.FR.get("location_approximate_panel").contains("la calibration en 3 points, elle, demande la position exacte"))
        assertTrue(TestRes.EN.get("location_approximate_panel").contains("3-point calibration needs your precise location"))
    }

    @Test fun textesFrEn() {
        assertEquals("Position approximative : précision limitée", TestRes.FR.get("location_approximate_short"))
        assertEquals("Approximate location: limited accuracy", TestRes.EN.get("location_approximate_short"))
        for (k in listOf("location_approximate_toast", "location_approximate_panel")) {
            assertTrue(TestRes.FR.get(k).contains("précision limitée"))
            assertTrue(TestRes.EN.get(k).contains("limited accuracy"))
        }
        // Le toast tient sur DEUX lignes (Android 12+ coupe au-delà : vu sur l'émulateur).
        assertTrue(TestRes.FR.get("location_approximate_toast").length <= 60)
        assertTrue(TestRes.EN.get("location_approximate_toast").length <= 60)
        // Le panneau dit comment retrouver la position exacte.
        assertTrue(TestRes.FR.get("location_approximate_panel").contains("Position exacte : Paramètres › Applications"))
        assertTrue(TestRes.EN.get("location_approximate_panel").contains("Precise location: Settings › Apps"))
    }
}
