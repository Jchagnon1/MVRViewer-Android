package com.minou.mvrviewer.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * LICENCE PRO PAR COMPTE ([SyncService.fetchProGrant]) sur le stub
 * [LocalSyncService], instancié SANS Context. Verrouille le CONTRAT partagé avec
 * le backend réel (`entitlements/{uid}` posé par l'administrateur) : false si
 * déconnecté, false si le compte connecté n'a pas de licence, true dès que son
 * uid est titulaire, et la licence suit le COMPTE (pas l'appareil) — un autre
 * compte sur le même stub ne l'a pas, la déconnexion la retire.
 */
class ProGrantTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun service() = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))

    @Test fun fetchProGrantIsFalseWhenSignedOut() = runBlocking {
        val svc = service()
        assertFalse(svc.fetchProGrant())
        // Même si des licences existent : sans compte, personne n'en bénéficie.
        svc.proGrants += "someone"
        assertFalse(svc.fetchProGrant())
    }

    @Test fun fetchProGrantIsFalseForAccountWithoutGrant() = runBlocking {
        val svc = service()
        svc.signUp("jm@example.com", "secret123", "JM")
        assertFalse(svc.fetchProGrant())
    }

    @Test fun fetchProGrantIsTrueOnceAdminGrantsTheUid() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        svc.proGrants += acc.uid
        assertTrue(svc.fetchProGrant())
    }

    @Test fun proGrantFollowsTheAccountNotTheDevice() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        svc.proGrants += acc.uid
        assertTrue(svc.fetchProGrant())
        // Déconnecté → plus de licence ; un AUTRE compte n'en a pas ; le
        // titulaire la retrouve en se reconnectant.
        svc.signOut()
        assertFalse(svc.fetchProGrant())
        svc.signUp("other@example.com", "secret123", "Autre")
        assertFalse(svc.fetchProGrant())
        svc.signOut()
        svc.signIn("jm@example.com", "secret123")
        assertTrue(svc.fetchProGrant())
    }
}
