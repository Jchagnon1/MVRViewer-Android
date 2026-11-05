package com.minou.mvrviewer.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

/**
 * LICENCE PRO DU COMPTE ([SyncService.fetchProGrant] → [ProGrant], SPEC-abonnement
 * §3.7, §4.1.4) sur le stub [LocalSyncService], instancié SANS Context. Verrouille le
 * CONTRAT partagé avec le backend réel : `null` (INCONNU) si déconnecté ou si la
 * lecture échoue — jamais `pro: false` sur une erreur —, `pro: false` sans
 * document, la licence suit le COMPTE ; la lecture d'un document
 * `entitlements/{uid}` daté avec ses sources ; et la vérification de l'adresse
 * e-mail (§4.1.6).
 */
class ProGrantTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun service() = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))

    @Test fun fetchProGrantIsUnknownWhenSignedOut() = runBlocking {
        val svc = service()
        assertNull(svc.fetchProGrant())
        // Même si des licences existent : sans compte, personne n'en bénéficie.
        svc.proGrants += "someone"
        assertNull(svc.fetchProGrant())
    }

    @Test fun fetchProGrantIsFalseForAccountWithoutDocument() = runBlocking {
        val svc = service()
        svc.signUp("jm@example.com", "secret123", "JM")
        assertEquals(ProGrant(pro = false, fromServer = true), svc.fetchProGrant())
    }

    @Test fun aFailedReadIsUnknownNeverFalse() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        svc.proGrants += acc.uid
        svc.proGrantFetchFails = true
        assertNull(svc.fetchProGrant())
        svc.proGrantFetchFails = false
        assertEquals(true, svc.fetchProGrant()?.pro)
    }

    @Test fun proGrantFollowsTheAccountNotTheDevice() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        svc.proGrants += acc.uid
        assertTrue(svc.fetchProGrant()!!.isActive(System.currentTimeMillis()))
        svc.signOut()
        assertNull(svc.fetchProGrant())
        svc.signUp("other@example.com", "secret123", "Autre")
        assertEquals(false, svc.fetchProGrant()?.pro)
        svc.signOut()
        svc.signIn("jm@example.com", "secret123")
        assertEquals(true, svc.fetchProGrant()?.pro)
    }

    @Test fun datedDocumentWithSources() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        val grant = ProGrant(true, 1_808_661_600_000L,
            play = ProGrant.SourceView(true, "active", 1_808_640_000_000L, null, true), fromServer = false)
        svc.proGrantDocuments[acc.uid] = grant
        // Le stub n'a pas de cache hors ligne : toujours une lecture « serveur ».
        assertEquals(grant.copy(fromServer = true), svc.fetchProGrant())
    }

    // MARK: - Document `entitlements/{uid}` (FirebaseSyncService → valeurs simples)

    @Test fun fromDocumentReadsExpiresAndSources() {
        val g = ProGrant.fromDocument(mapOf(
            "pro" to true,
            "expires" to 1_808_661_600_000L,
            "sources" to mapOf(
                "appstore" to mapOf("active" to false, "status" to "expired", "expiresAt" to Date(1_790_000_000_000L),
                    "graceUntil" to null, "autoRenew" to false),
                "play" to mapOf("active" to true, "status" to "grace", "expiresAt" to 1_808_640_000_000L,
                    "graceUntil" to 1_809_000_000_000L, "autoRenew" to true),
                "admin" to mapOf("active" to false, "origin" to "manual")
            )
        ), fromServer = true)
        assertTrue(g.pro)
        assertEquals(1_808_661_600_000L, g.expires)
        assertEquals(ProGrant.SourceView(false, "expired", 1_790_000_000_000L, null, false), g.appstore)
        assertEquals(ProGrant.SourceView(true, "grace", 1_808_640_000_000L, 1_809_000_000_000L, true), g.play)
        assertEquals(1_809_000_000_000L, g.play!!.effectiveEnd)
        assertTrue(g.fromServer)
    }

    @Test fun fromDocumentKeepsTheOldRules() {
        val now = 1_800_000_000_000L
        // Licence admin sans échéance.
        assertTrue(ProGrant.fromDocument(mapOf("pro" to true), true).isActive(now))
        // Échéance passée : plus active.
        assertFalse(ProGrant.fromDocument(mapOf("pro" to true, "expires" to now - 1), true).isActive(now))
        // `expires` d'une autre forme (RawValue admin recopiée telle quelle) : ignorée.
        assertTrue(ProGrant.fromDocument(mapOf("pro" to true, "expires" to "2027-12-31"), true).isActive(now))
        // `pro` absent ou d'un autre type : non Pro.
        assertFalse(ProGrant.fromDocument(mapOf("pro" to "true"), true).pro)
        assertFalse(ProGrant.fromDocument(emptyMap(), true).pro)
    }

    @Test fun aSourceIsLiveOnlyIfActiveAndInTheFuture() {
        val now = 1_800_000_000_000L
        assertTrue(ProGrant.SourceView(true, "active", now + 1).isLive(now))
        assertFalse(ProGrant.SourceView(true, "active", now - 1).isLive(now)) // active mais échue
        assertTrue(ProGrant.SourceView(true, "grace", now - 1, now + 1).isLive(now))
        assertFalse(ProGrant.SourceView(false, "expired", now + 1).isLive(now))
        assertFalse(ProGrant.SourceView(true, "active").isLive(now))
    }

    // MARK: - Adresse e-mail vérifiée (§4.1.6)

    @Test fun emailVerificationOnTheStub() = runBlocking {
        val svc = service()
        assertFalse(svc.verifiesPurchases)
        assertNull(svc.reloadEmailVerified())
        try { svc.sendEmailVerification(); fail("déconnecté") } catch (e: SyncException.NotSignedIn) { }
        svc.signUp("JM@example.com", "secret123", "JM")
        assertEquals(false, svc.reloadEmailVerified())
        svc.sendEmailVerification()
        assertEquals(listOf("jm@example.com"), svc.sentEmailVerifications)
        svc.verifiedEmails += "jm@example.com"
        assertEquals(true, svc.reloadEmailVerified())
        svc.simulatesOffline = true
        try { svc.sendEmailVerification(); fail("hors ligne") } catch (e: SyncException.NetworkUnavailable) { }
    }
}
