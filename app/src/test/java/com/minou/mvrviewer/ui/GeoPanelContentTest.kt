package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.GeoCalibration
import com.minou.mvrviewer.mvr.GeoSurvey
import com.minou.mvrviewer.mvr.SatelliteErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Panneau « Position GPS » (SPEC-menus-android §3.6, tranche B) : contenu selon
 * la vue, la calibration, le satellite et la couverture IGN ; ligne d'état ;
 * outil `geoPanel` ; textes fr / en.
 */
class GeoPanelContentTest {

    private val S3D = ProjectSurface.SCENE_3D
    private val PLAN = ProjectSurface.PLAN

    private fun make(surface: ProjectSurface, calibrated: Boolean, show: Boolean = false,
                     covered: Boolean = true, status: SatelliteStatus = SatelliteStatus.Idle) =
        GeoPanelContent.make(surface, calibrated, show, covered, status)

    // ------------------------------------------------------------ contenu

    @Test fun nonCalibreCalibrezDAbordEtTroisPointsPrincipal() {
        for (surface in ProjectSurface.entries) {
            val c = make(surface, calibrated = false, show = true)
            assertEquals(listOf(GeoPanelRow.SHOW_MY_LOCATION, GeoPanelRow.SATELLITE, GeoPanelRow.CALIBRATE_FIRST_HINT),
                c.display)
            assertEquals(listOf(GeoPanelRow.STATUS, GeoPanelRow.THREE_POINTS, GeoPanelRow.HERE_ANCHOR,
                GeoPanelRow.HERE_ANCHOR_HINT), c.calibration)
            assertTrue("rien à réinitialiser", c.isolated.isEmpty())
            assertTrue("3 points = action principale", c.threePointsPrimary)
            // Satellite lu éteint et désactivé tant que rien n'est géo-référencé,
            // même si le réglage était resté allumé.
            assertFalse(c.satelliteChecked)
            assertFalse(c.satelliteEnabled)
            assertNull(c.satelliteMessage)
        }
        // « Afficher ma position » : toujours dans le plan (il sert à poser « je suis
        // ici »), désactivé en 3D sans calibration (le point en dépend, D20).
        assertTrue(make(PLAN, false).showLocationEnabled)
        assertFalse(make(S3D, false).showLocationEnabled)
    }

    @Test fun calibreReinitialiserEtTroisPointsSecondaire() {
        for (surface in ProjectSurface.entries) {
            val c = make(surface, calibrated = true)
            assertEquals(listOf(GeoPanelRow.RESET_CALIBRATION), c.isolated)
            assertFalse(c.threePointsPrimary)
            assertTrue(c.showLocationEnabled)
            assertTrue(c.satelliteEnabled)
            assertFalse(GeoPanelRow.CALIBRATE_FIRST_HINT in c.display)
        }
    }

    @Test fun tailleDuPointEn3DSeulementEtSiCalibre() {
        assertTrue(GeoPanelRow.MARKER_SIZE in make(S3D, true).display)
        assertFalse(GeoPanelRow.MARKER_SIZE in make(S3D, false).display)
        assertFalse(GeoPanelRow.MARKER_SIZE in make(PLAN, true).display)
        assertEquals(GeoPanelRow.MARKER_SIZE, make(S3D, true, show = true, status = SatelliteStatus.Ready).display.last())
    }

    @Test fun satelliteAfficheOpaciteEtChargement() {
        val ready = make(PLAN, true, show = true, status = SatelliteStatus.Ready)
        assertEquals(listOf(GeoPanelRow.SHOW_MY_LOCATION, GeoPanelRow.SATELLITE, GeoPanelRow.SATELLITE_OPACITY),
            ready.display)
        assertTrue(ready.satelliteChecked)
        val loading = make(PLAN, true, show = true, status = SatelliteStatus.Loading)
        assertEquals(listOf(GeoPanelRow.SHOW_MY_LOCATION, GeoPanelRow.SATELLITE, GeoPanelRow.SATELLITE_LOADING,
            GeoPanelRow.SATELLITE_OPACITY), loading.display)
        // Échec réseau : message sous l'interrupteur, opacité toujours réglable.
        val failed = make(S3D, true, show = true, status = SatelliteStatus.Failed(SatelliteErrorKind.NETWORK))
        assertEquals(listOf(GeoPanelRow.SHOW_MY_LOCATION, GeoPanelRow.SATELLITE, GeoPanelRow.SATELLITE_MESSAGE,
            GeoPanelRow.SATELLITE_OPACITY, GeoPanelRow.MARKER_SIZE), failed.display)
        assertEquals(R.string.sat_error_network to emptyList<Any>(), failed.satelliteMessage)
        // Satellite éteint : ni opacité ni message.
        val off = make(PLAN, true, show = false, status = SatelliteStatus.Idle)
        assertEquals(listOf(GeoPanelRow.SHOW_MY_LOCATION, GeoPanelRow.SATELLITE), off.display)
        assertFalse(off.satelliteChecked)
    }

    @Test fun horsDeFranceDesactiveAvecMessage() {
        for (show in listOf(true, false)) {
            val c = make(PLAN, true, show = show, covered = false, status = SatelliteStatus.OutOfCoverage)
            assertFalse(c.satelliteEnabled)
            assertFalse("jamais coché hors couverture", c.satelliteChecked)
            assertEquals(R.string.sat_out_of_coverage to emptyList<Any>(), c.satelliteMessage)
            assertTrue(GeoPanelRow.SATELLITE_MESSAGE in c.display)
            assertFalse("pas d'opacité sans image", GeoPanelRow.SATELLITE_OPACITY in c.display)
        }
        assertEquals("Fond satellite disponible en France uniquement (imagerie IGN).",
            TestRes.FR.get("sat_out_of_coverage"))
    }

    // ------------------------------------------------------------ statut

    private fun anchor(i: Int) = GeoAnchor(i * 1000f, 0f, 48.85 + i * 0.001, 2.35)

    @Test fun statutRefusPrioritaireTroisPointsUnNNonCalibre() {
        val fr = TestRes.FR
        fun text(cal: GeoCalibration, denied: Boolean) =
            GeoPanelContent.status(cal, denied).let { (t, w) -> fr.res(t.id, t.args) to w }

        val none = GeoCalibration()
        assertEquals("Non calibré" to false, text(none, false))
        // Refus : prioritaire sur tout le reste, en orange.
        val two = GeoCalibration().apply { addAnchor(anchor(0)); addAnchor(anchor(1)) }
        assertEquals("Localisation refusée — Réglages > Confidentialité" to true, text(two, true))
        val one = GeoCalibration().apply { addAnchor(anchor(0)) }
        assertTrue(text(one, false).first.startsWith("1 point posé."))
        assertEquals("Calibré (2 points : position + orientation + échelle)" to false, text(two, false))
        val surveyed = GeoCalibration().apply {
            replace(listOf(anchor(0), anchor(1)),
                GeoSurvey(GeoSurvey.THREE_POINT_METHOD, emptyList(), 2.0, 1.0, 0.0, null))
        }
        val st = GeoPanelContent.status(surveyed, false).first
        assertEquals(R.string.cal3_status_three, st.id)
    }

    @Test fun valeursAffichees() {
        assertEquals("×1,5", GeoPanelContent.markerScaleText(1.5f, Locale.FRENCH))
        assertEquals("×1.5", GeoPanelContent.markerScaleText(1.5f, Locale.ENGLISH))
        assertEquals("55 %", GeoPanelContent.opacityText(0.55f))
        assertEquals(0.3f, GeoPanelContent.MARKER_MIN)
        assertEquals(3f, GeoPanelContent.MARKER_MAX)
        assertEquals(0.05f, GeoPanelContent.OPACITY_MIN)
    }

    // ------------------------------------------------------------ outil

    @Test fun outilGeoPanelDansLesDeuxCatalogues() {
        assertEquals("geoPanel", ToolId.GEO_PANEL.rawId)
        assertEquals(ToolId.GEO_PANEL, ToolId.fromRawId("geoPanel"))
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        for (f in listOf("Scene3DScreen.kt", "PlanScreen.kt")) {
            val code = File(root, "com/minou/mvrviewer/ui/$f").readText()
            assertTrue("$f : outil geoPanel au catalogue", code.contains("ToolSpec(ToolId.GEO_PANEL,"))
        }
    }

    // ------------------------------------------------------------ textes

    @Test fun textesFrEn() {
        val expected = mapOf(
            "geo_panel_title" to ("Position GPS" to "GPS position"),
            "geo_show_my_position" to ("Afficher ma position" to "Show my position"),
            "geo_calibrate_first" to ("Calibrez d'abord (ci-dessous) pour afficher votre position et le satellite." to
                "Calibrate first (below) to show your position and the satellite."),
            "geo_opacity" to ("Opacité" to "Opacity"),
            "geo_dot_size" to ("Taille du point GPS" to "GPS dot size"),
            "geo_section_calibration" to ("Calibration" to "Calibration"),
            "geo_here_quick" to ("Je suis ici (pose rapide)…" to "I am here (quick)…"),
            "geo_here_caption" to ("Touchez le plan là où vous êtes. Un 2e point corrige l'orientation et l'échelle." to
                "Tap the plan where you stand. A second point corrects orientation and scale."),
            "geo_reset_calibration" to ("Réinitialiser la calibration" to "Reset the calibration"),
            "menu_display" to ("Affichage" to "Display"),
            "menu_gps_position" to ("Position GPS…" to "GPS position…"),
            "tool_satellite" to ("Fond satellite" to "Satellite background"),
            "tool_calibrate_3" to ("Calibrer en 3 points (guidé)…" to "Calibrate with 3 points (guided)…")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, TestRes.FR.get(k))
            assertEquals("en $k", v.second, TestRes.EN.get(k))
        }
    }
}
