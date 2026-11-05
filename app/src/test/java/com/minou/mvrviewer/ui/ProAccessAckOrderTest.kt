package com.minou.mvrviewer.ui

import com.minou.mvrviewer.sync.LocalSyncService
import com.minou.mvrviewer.sync.PlayPurchaseSnapshot
import com.minou.mvrviewer.sync.PurchaseLink
import com.minou.mvrviewer.sync.PurchaseLinkRecord
import com.minou.mvrviewer.sync.PurchaseLinkStore
import com.minou.mvrviewer.sync.PurchaseLinkTransport
import com.minou.mvrviewer.sync.PurchaseLinker
import com.minou.mvrviewer.sync.SyncService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * ORDRE « PREUVE AU SERVEUR PUIS ACQUITTEMENT PAR L'APP » (SPEC-abonnement §3.4 étape
 * 5, §4.3) : `outOfAppPurchaseContext` disparaît à l'acquittement, le serveur doit
 * donc lire l'achat AVANT. L'app acquitte elle-même APRÈS la réponse du serveur,
 * quelle qu'elle soit (succès, refus, `503`, réseau coupé), si l'achat ne l'est
 * toujours pas ; sans compte ou sur le backend local : acquittement local immédiat.
 */
class ProAccessAckOrderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val log = mutableListOf<String>()

    /** Faux Google Play : relecture (état « acquitté » réglable) et acquittement. */
    private inner class FakePlay(var acknowledgedByServer: Boolean = false, var queryFails: Boolean = false) : PlayAckGateway {
        override fun queryPurchases(then: (List<PlayPurchaseSnapshot>?) -> Unit) {
            log += "query"
            then(if (queryFails) null else listOf(sub(acknowledged = acknowledgedByServer)))
        }
        override fun acknowledge(purchaseToken: String, then: (ok: Boolean) -> Unit) {
            log += "ack:$purchaseToken"
            then(true)
        }
    }

    private fun sub(token: String = TOKEN, acknowledged: Boolean = false) = PlayPurchaseSnapshot(
        products = listOf(PurchaseLink.PRO_SUBSCRIPTION_ID), purchased = true, purchaseToken = token,
        acknowledged = acknowledged, autoRenewing = true, orderId = ORDER)

    // MARK: - Séquence pure

    @Test fun laPreuvePartAvantLAcquittementQuiAttendLaReponse() {
        val play = FakePlay()
        var serverAnswer: (() -> Unit)? = null
        var finished = false
        ProofThenAck(play).run(listOf(TOKEN), proofCanLeave = true,
            sendProof = { done -> log += "proof"; serverAnswer = done }, finished = { finished = true })
        // Le serveur n'a pas encore répondu : RIEN n'est acquitté.
        assertEquals(listOf("proof"), log)
        serverAnswer!!()
        assertEquals(listOf("proof", "query", "ack:$TOKEN"), log)
        assertTrue(finished)
    }

    @Test fun dejaAcquitteParLeServeurLAppNAcquittePas() {
        ProofThenAck(FakePlay(acknowledgedByServer = true)).run(listOf(TOKEN), true,
            sendProof = { done -> log += "proof"; done() })
        assertEquals(listOf("proof", "query"), log)
    }

    @Test fun relectureImpossibleLesJetonsConnusSontAcquittes() {
        ProofThenAck(FakePlay(queryFails = true)).run(listOf(TOKEN, "t2"), true,
            sendProof = { done -> log += "proof"; done() })
        assertEquals(listOf("proof", "query", "ack:$TOKEN", "ack:t2"), log)
    }

    @Test fun sansCompteAcquittementLocalImmediat() {
        ProofThenAck(FakePlay()).run(listOf(TOKEN), proofCanLeave = false,
            sendProof = { done -> log += "proof"; done() })
        assertEquals(listOf("ack:$TOKEN", "proof"), log)
    }

    @Test fun dejaAcquitteLaPreuvePartSimplement() {
        ProofThenAck(FakePlay()).run(emptyList(), true, sendProof = { done -> log += "proof"; done() })
        assertEquals(listOf("proof"), log)
    }

    // MARK: - Intégration : ProAccess + PurchaseLinker réels, faux serveur

    private inner class TokenService(val local: LocalSyncService) : SyncService by local {
        override suspend fun currentIdToken(forceRefresh: Boolean): String = "id-token"
        override val verifiesPurchases: Boolean = true
    }

    private inner class FakeServer(val reply: () -> Pair<Int, String>) : PurchaseLinkTransport {
        override suspend fun post(body: String, idToken: String): Pair<Int, String> {
            log += "post"
            return reply()
        }
    }

    private class MemoryStore : PurchaseLinkStore {
        var record: PurchaseLinkRecord? = null
        override fun load() = record
        override fun save(record: PurchaseLinkRecord) { this.record = record }
    }

    private lateinit var local: LocalSyncService
    private var uid: String? = null
    private val defaultGateway = ProAccess.playGateway

    @Before fun setUp() {
        local = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))
        ProAccess.forceForTests(null)
        ProAccess.resetForTests()
    }

    @After fun tearDown() {
        ProAccess.playGateway = defaultGateway
        ProAccess.purchaseLinker = null
        ProAccess.resetForTests()
        ProAccess.forceForTests(null)
    }

    /** Câblage du SyncViewModel : `done` APRÈS la réponse du serveur. */
    private fun wire(reply: () -> Pair<Int, String>, verifies: Boolean = true) {
        val svc = TokenService(local)
        val linker = PurchaseLinker(svc, FakeServer(reply), MemoryStore(), { ProAccess.currentProof() }, { uid },
            { _, _ -> log += "grant" }, { })
        ProAccess.purchaseLinker = { explicit, done ->
            try { runBlocking { linker.linkPurchase(explicit) } } finally { done() }
        }
        ProAccess.verifiesPurchases = verifies
        ProAccess.accountUid = if (verifies) uid else null
        ProAccess.playGateway = FakePlay()
    }

    private suspend fun signIn() {
        uid = local.signUp("jm@example.com", "secret123", "JM").uid
    }

    @Test fun abonnementConnecteLaPreuvePuisLAcquittement() = runBlocking {
        signIn()
        wire({ 200 to """{"ok":true,"status":"granted","pro":true,"store":"play","test":true}""" })
        ProAccess.applyPurchases(listOf(sub()), explicit = true)
        assertEquals(listOf("post", "grant", "query", "ack:$TOKEN"), log)
        // L'appareil est débloqué tout de suite, sans attendre le serveur.
        assertTrue(ProAccess.purchased)
    }

    @Test fun apresUn503LAppAcquitteQuandMeme() = runBlocking {
        signIn()
        wire({ 503 to """{"ok":false,"code":"store_unavailable","retryable":true}""" })
        ProAccess.applyPurchases(listOf(sub()), explicit = true)
        assertEquals(listOf("post", "query", "ack:$TOKEN"), log)
    }

    @Test fun apresUnRefusOuUnReseauCoupeLAppAcquitteQuandMeme() = runBlocking {
        signIn()
        wire({ 409 to """{"ok":false,"code":"owned_by_other_account","retryable":false}""" })
        ProAccess.applyPurchases(listOf(sub()), explicit = false)
        assertEquals(listOf("post", "query", "ack:$TOKEN"), log)
        log.clear()
        wire({ throw IOException("hors ligne") })
        ProAccess.applyPurchases(listOf(sub()), explicit = false)
        assertEquals(listOf("post", "query", "ack:$TOKEN"), log)
    }

    @Test fun sansCompteAcquittementLocalSansEnvoi() {
        wire({ 200 to "{}" })
        ProAccess.applyPurchases(listOf(sub()), explicit = true)
        assertEquals(listOf("ack:$TOKEN"), log)
        assertNull(ProAccess.accountUid)
    }

    @Test fun backendLocalConnecteAcquittementLocalImmediat() = runBlocking {
        signIn()
        wire({ 200 to "{}" }, verifies = false)
        ProAccess.applyPurchases(listOf(sub()), explicit = true)
        // Acquitté AVANT toute tentative d'envoi.
        assertEquals("ack:$TOKEN", log.first())
    }

    @Test fun achatEnAttenteNiPreuveNiAcquittement() = runBlocking {
        signIn()
        wire({ 200 to "{}" })
        ProAccess.applyPurchases(listOf(sub().copy(purchased = false, orderId = null)), explicit = true)
        assertEquals(emptyList<String>(), log)
        assertNull(ProAccess.currentProof())
    }

    private companion object {
        const val TOKEN = "opaque-token-1.AO-J1Oz"
        const val ORDER = "GPA.3346-1234-5678-90123"
    }
}
