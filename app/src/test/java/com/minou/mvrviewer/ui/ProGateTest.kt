package com.minou.mvrviewer.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde Pro des RÉGLAGES (SPEC-menus-android §1.2, §4.5 ; iOS `ProGate` /
 * `ProRefusalThrottle`) : un gratuit lit tout, la valeur ne bouge pas, un seul
 * écran Pro par rafale de 0,8 s glissantes ; un Pro écrit.
 */
class ProGateTest {

    @After fun resetPro() {
        ProAccess.forceForTests(null)
        ProAccess.showPaywall = false
    }

    @Test fun gratuitValeurInchangeeEtUnSeulRefusParRafale() {
        val throttle = ProRefusalThrottle()
        val refusals = mutableListOf<String>()
        var value = 1
        // Un glissé : 30 changements espacés de 16 ms (60 i/s).
        for (i in 0 until 30) {
            val written = ProGate.setter("régler le plan importé", isPro = false, throttle = throttle,
                nowMs = 1_000L + i * 16L, refuse = { refusals += it }, set = { value = 2 })
            assertFalse(written)
        }
        assertEquals("la valeur ne bouge pas", 1, value)
        assertEquals("un seul écran Pro pour tout le glissé", listOf("régler le plan importé"), refusals)
    }

    @Test fun laFenetreEstGlissante() {
        val throttle = ProRefusalThrottle(windowMs = 800L)
        assertTrue(throttle.admit(0L))
        // Chaque refus repousse la fin de la rafale : 3 s de glissé continu = un seul écran.
        for (t in 500L..3_000L step 500L) assertFalse("t=$t", throttle.admit(t))
        // Après une vraie pause (> 0,8 s), un nouveau geste rouvre l'écran Pro.
        assertTrue(throttle.admit(3_801L))
        assertFalse(throttle.admit(3_900L))
    }

    @Test fun deuxGestesSeparesOuvrentDeuxEcrans() {
        val throttle = ProRefusalThrottle()
        var n = 0
        ProGate.setter("x", false, throttle, 0L, { n++ }) {}
        ProGate.setter("x", false, throttle, 2_000L, { n++ }) {}
        assertEquals(2, n)
    }

    @Test fun proEcritSansRefus() {
        val throttle = ProRefusalThrottle()
        var value = 1
        var refused = false
        assertTrue(ProGate.setter("x", isPro = true, throttle = throttle, nowMs = 0L,
            refuse = { refused = true }, set = { value = 2 }))
        assertEquals(2, value)
        assertFalse(refused)
    }

    @Test fun gatedSetterSuitLEtatPro() {
        ProAccess.forceForTests(false)
        ProAccess.showPaywall = false
        var v = 0f
        assertFalse(ProAccess.gatedSetter("régler le plan importé") { v = 0.5f })
        assertEquals(0f, v)
        assertTrue("le refus ouvre l'écran Pro", ProAccess.showPaywall)
        assertEquals("régler le plan importé", ProAccess.paywallReason)
        ProAccess.forceForTests(true)
        assertTrue(ProAccess.gatedSetter("régler le plan importé") { v = 0.5f })
        assertEquals(0.5f, v)
    }
}
