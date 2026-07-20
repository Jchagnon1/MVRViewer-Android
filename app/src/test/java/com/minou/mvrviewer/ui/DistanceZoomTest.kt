package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le calcul du zoom 3D. Le câblage natif (SceneView → Filament) est
 * vérifié par ailleurs ; ce qui casse silencieusement, c'est le SIGNE et
 * l'AMPLITUDE — d'où ces cas.
 */
class DistanceZoomTest {

    private val MIN = 0.1f
    private val MAX = 10_000f

    private fun step(d: Float, dpx: Float, density: Float = 1f) =
        DistanceZoomManipulator.step(d, dpx, density, MIN, MAX)

    @Test
    fun `ecarter les doigts rapproche la camera`() {
        val after = step(100f, +50f)
        assertNotNull(after)
        assertTrue("écarter doit RÉDUIRE la distance", after!! < 100f)
    }

    @Test
    fun `rapprocher les doigts eloigne la camera`() {
        val after = step(100f, -50f)!!
        assertTrue("pincer doit AUGMENTER la distance", after > 100f)
    }

    @Test
    fun `la vitesse est proportionnelle au mouvement`() {
        // Deux fois plus de doigt = deux fois plus de rapprochement (en mètres).
        val small = 100f - step(100f, +20f)!!
        val big = 100f - step(100f, +40f)!!
        assertEquals(2f, big / small, 0.001f)
    }

    @Test
    fun `le zoom est proportionnel a la distance`() {
        // Le FACTEUR est identique de près comme de loin (approche exponentielle).
        val near = step(10f, +30f)!! / 10f
        val far = step(1000f, +30f)!! / 1000f
        assertEquals(near, far, 0.0001f)
    }

    @Test
    fun `un pincement de 300 dp donne environ x6`() {
        // 300 dp délivrés en 30 événements de 10 dp (cadence tactile réaliste).
        var d = 1000f
        repeat(30) { d = step(d, +10f)!! }
        val factor = 1000f / d
        assertTrue("facteur attendu ~6, obtenu $factor", factor in 5f..7f)
    }

    @Test
    fun `la densite d ecran ne change pas le ressenti`() {
        // Même mouvement PHYSIQUE (300 dp) sur deux densités très différentes.
        var a = 1000f
        repeat(30) { a = step(a, +10f * 1.0f, density = 1.0f)!! }   // mdpi
        var b = 1000f
        repeat(30) { b = step(b, +10f * 3.5f, density = 3.5f)!! }   // xxxhdpi
        assertEquals(a, b, a * 0.01f)
    }

    @Test
    fun `un saut d evenement ne traverse jamais le pivot`() {
        val after = step(100f, +100_000f)!!
        assertTrue("la caméra doit rester devant le pivot", after > 0f)
        assertEquals(40f, after, 0.01f)   // frac borné à 0,6
    }

    @Test
    fun `les bornes sont respectees et le retour arriere reste possible`() {
        var d = 100f
        repeat(200) { d = step(d, +50f)!! }
        assertEquals(MIN, d, 1e-4f)
        // Collé au minimum, on doit pouvoir ressortir.
        val out = step(d, -50f)!!
        assertTrue("on doit pouvoir dézoomer depuis la distance minimale", out > d)
    }

    @Test
    fun `entrees degenerees ignorees`() {
        assertNull(step(100f, 0f))
        assertNull(step(100f, Float.NaN))
        // Densité absurde : on retombe sur 1 plutôt que de diviser par zéro.
        assertNotNull(step(100f, +10f, density = 0f))
        assertTrue(step(100f, +10f, density = 0f)!!.isFinite())
    }

    @Test
    fun `distance initiale invalide bornee`() {
        // Une distance de départ absurde (0, NaN) retombe sur 1 m, puis est
        // bornée : le zoom reste utilisable au lieu de se figer.
        assertEquals(1f, DistanceZoomManipulator.clampStart(0f, MIN, MAX), 1e-6f)
        assertEquals(1f, DistanceZoomManipulator.clampStart(Float.NaN, MIN, MAX), 1e-6f)
        assertEquals(MAX, DistanceZoomManipulator.clampStart(1e9f, MIN, MAX), 1e-6f)
        // …et le repli est bien borné quand l'intervalle ne contient pas 1 m.
        assertEquals(50f, DistanceZoomManipulator.clampStart(Float.NaN, 50f, MAX), 1e-6f)
    }
}
