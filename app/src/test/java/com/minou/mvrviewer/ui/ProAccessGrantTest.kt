package com.minou.mvrviewer.ui

import com.minou.mvrviewer.sync.ProGrant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LICENCE DU COMPTE côté [ProAccess] (JVM pur, sans Activity ni Play : l'object
 * n'est pas initialisé et doit rester nul-sûr). Contrat : une licence active suffit
 * à rendre Pro ([ProAccess.isPro]) sans abonnement sur l'appareil, elle ferme le
 * paywall, une licence échue ou retirée rend gratuit, le forçage DEBUG/tests garde
 * le dernier mot, « Restaurer mes achats » relit aussi la licence
 * ([ProAccess.grantRefresher]) ; et ce qu'exige le bouton du paywall (§4.1.6).
 */
class ProAccessGrantTest {

    @Before fun noOverride() {
        ProAccess.forceForTests(null)
        ProAccess.resetForTests()
    }

    @After fun reset() {
        ProAccess.resetForTests()
        ProAccess.grantRefresher = null
        ProAccess.purchaseLinker = null
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

    @Test fun anExpiredSubscriptionLicenceIsNotPro() {
        val now = System.currentTimeMillis()
        ProAccess.applyAccountLicence(ProGrant(true, now - 60_000L, fromServer = true), "u1")
        assertFalse(ProAccess.isPro)
        ProAccess.applyAccountLicence(ProGrant(true, now + 60_000L, fromServer = true), "u1")
        assertTrue(ProAccess.isPro)
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
        ProAccess.grantRefresher = { done -> called++; done() }
        ProAccess.restore(ProAccess.RestoreScreen.PAYWALL) // sans Play (non initialisé) : seul le crochet agit
        assertEquals(1, called)
        assertFalse(ProAccess.isBusy)
    }

    @Test fun restoreMessageOnlyWhereItWasAskedAndNeverOncePro() {
        ProAccess.texts = TestRes.FR.proTexts()
        try {
            ProAccess.restore(ProAccess.RestoreScreen.ACCOUNT)
            // Sans Play : « Google Play indisponible. », dans Compte & réglages seulement.
            assertEquals("Google Play indisponible.", ProAccess.restoreMessageShown(ProAccess.RestoreScreen.ACCOUNT))
            assertNull(ProAccess.restoreMessageShown(ProAccess.RestoreScreen.PAYWALL))
            ProAccess.setAccountGrant(true)
            assertNull(ProAccess.restoreMessageShown(ProAccess.RestoreScreen.ACCOUNT))
            ProAccess.setAccountGrant(false)
            ProAccess.accountScreenDidOpen()
            assertNull(ProAccess.restoreMessageShown(ProAccess.RestoreScreen.ACCOUNT))
        } finally {
            ProAccess.texts = null
        }
    }

    @Test fun subscribeRequirementFollowsTheAccount() {
        // Backend local (ne vérifie pas les achats) : rien n'est exigé.
        assertEquals(SubscribeRequirement.READY, ProAccess.subscribeRequirement)
        ProAccess.verifiesPurchases = true
        assertEquals(SubscribeRequirement.SIGN_IN, ProAccess.subscribeRequirement)
        ProAccess.bindAccount("u1")
        assertEquals(SubscribeRequirement.VERIFY_EMAIL, ProAccess.subscribeRequirement)
        ProAccess.emailVerified = true
        assertEquals(SubscribeRequirement.READY, ProAccess.subscribeRequirement)
        ProAccess.clearAccountLicence()
        assertEquals(SubscribeRequirement.SIGN_IN, ProAccess.subscribeRequirement)
        assertNull(ProAccess.emailVerified)
    }

    @Test fun anAppStoreSubscriptionBlocksASecondOne() {
        val now = System.currentTimeMillis()
        ProAccess.applyAccountLicence(ProGrant(true, now + 30L * 86_400_000L,
            appstore = ProGrant.SourceView(true, "active", now + 30L * 86_400_000L, null, true), fromServer = true), "u1")
        assertEquals(now + 30L * 86_400_000L, ProAccess.otherStoreSubscriptionEnd)
        assertTrue(ProAccess.hasRenewingSubscription)
        assertEquals(ProStatusLine.OtherDevice(now + 30L * 86_400_000L), ProAccess.statusLine)
        // `purchase` ne lance rien tant que l'autre boutique couvre l'utilisateur.
        assertFalse(ProAccess.isBusy)
    }

    @Test fun manageLinkDependsOnASubscriptionOnThisDevice() {
        assertEquals(SubscriptionRules.MANAGE_ALL_URL, ProAccess.manageUrl)
        ProAccess.applyStoreState(listOf(com.minou.mvrviewer.sync.PlayPurchaseSnapshot(
            listOf(ProAccess.PRO_SUBSCRIPTION_ID), true, "tok", true, false, "GPA.1")))
        assertTrue(ProAccess.hasDeviceSubscription)
        assertEquals(SubscriptionRules.MANAGE_SUBSCRIPTION_URL, ProAccess.manageUrl)
        assertEquals(ProStatusLine.ActiveNoDate, ProAccess.statusLine)
        assertFalse("renouvellement coupé sur l'appareil", ProAccess.hasRenewingSubscription)
        assertTrue(ProAccess.isPro)
    }
}
