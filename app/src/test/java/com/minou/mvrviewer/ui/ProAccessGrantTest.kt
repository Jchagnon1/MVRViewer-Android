package com.minou.mvrviewer.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LICENCE PRO PAR COMPTE côté [ProAccess] (JVM pur, sans Activity ni Play :
 * l'object n'est pas initialisé, [ProAccess.setAccountGrant] doit rester
 * nul-sûr). Contrat : la licence attribuée par l'administrateur suffit à
 * rendre Pro ([ProAccess.isPro]) sans achat, elle ferme le paywall, son retrait
 * (déconnexion) rend gratuit, le forçage DEBUG/tests garde le dernier mot, et
 * « Restaurer un achat » relit aussi la licence ([ProAccess.grantRefresher]).
 */
class ProAccessGrantTest {

    @Before fun noOverride() { ProAccess.forceForTests(null) }
    @After fun reset() {
        ProAccess.setAccountGrant(false)
        ProAccess.grantRefresher = null
        ProAccess.forceForTests(null)
    }

    @Test fun accountGrantMakesPro() {
        assertFalse(ProAccess.isPro)
        ProAccess.setAccountGrant(true)
        assertTrue(ProAccess.grantedByAccount)
        assertFalse(ProAccess.purchased)
        assertTrue(ProAccess.isPro)
    }

    @Test fun removingGrantMakesFreeAgain() {
        ProAccess.setAccountGrant(true)
        assertTrue(ProAccess.isPro)
        ProAccess.setAccountGrant(false)
        assertFalse(ProAccess.grantedByAccount)
        assertFalse(ProAccess.isPro)
    }

    @Test fun grantClosesPaywall() {
        ProAccess.showPaywall = true
        ProAccess.setAccountGrant(true)
        assertFalse(ProAccess.showPaywall)
        // Le retrait ne rouvre rien.
        ProAccess.setAccountGrant(false)
        assertFalse(ProAccess.showPaywall)
    }

    @Test fun debugOverrideWinsOverGrant() {
        ProAccess.setAccountGrant(true)
        ProAccess.forceForTests(false)
        assertFalse(ProAccess.isPro)
        ProAccess.forceForTests(null)
        assertTrue(ProAccess.isPro)
    }

    @Test fun restoreAlsoRefreshesAccountGrant() {
        var called = 0
        ProAccess.grantRefresher = { called++ }
        ProAccess.restore() // sans Play (non initialisé) : seul le crochet agit
        assertEquals(1, called)
    }
}
