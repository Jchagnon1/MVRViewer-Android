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
import org.junit.Assert.assertFalse
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

    /** Câblage du SyncViewModel (init) : `done` APRÈS la réponse du serveur. */
    private fun wireLinker(reply: () -> Pair<Int, String>, verifies: Boolean = true) {
        val svc = TokenService(local)
        val linker = PurchaseLinker(svc, FakeServer(reply), MemoryStore(), { ProAccess.currentProof() }, { uid },
            { _, _ -> log += "grant" }, { })
        ProAccess.purchaseLinker = { explicit, done ->
            try { runBlocking { linker.linkPurchase(explicit) } } finally { done() }
        }
        ProAccess.verifiesPurchases = verifies
        ProAccess.playGateway = FakePlay()
    }

    /** Câblage complet, compte de la session CONNU (reprise de session terminée). */
    private fun wire(reply: () -> Pair<Int, String>, verifies: Boolean = true) {
        wireLinker(reply, verifies)
        ProAccess.accountUid = if (verifies) uid else null
        ProAccess.markAccountResolved()
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

    // MARK: - Démarrage à froid : Google Play répond AVANT la reprise de la session

    /** Faux minuteurs : rien ne part tout seul ; [fire] déclenche ceux qui restent. */
    private class FakeTimers : ProTimers {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            val entry = delayMs to action
            pending += entry
            return { pending.remove(entry) }
        }
        fun fire() { pending.toList().forEach { pending.remove(it); it.second() } }
    }

    @Test fun achatNonAcquitteLuAvantLaRepriseDeLaSessionAttendLeCompte() = runBlocking {
        // Session Firebase persistée : le compte EST connecté, mais pas encore
        // rattaché à ProAccess (`bindProAccount` vient après la première composition).
        signIn()
        wireLinker({ 200 to """{"ok":true,"status":"granted","pro":true,"store":"play","test":true}""" })
        var finished = false
        ProAccess.applyPurchases(listOf(sub()), explicit = false) { finished = true }
        // Rien n'est acquitté « sans compte » : la séquence attend.
        assertEquals(emptyList<String>(), log)
        assertFalse(finished)
        // L'appareil est débloqué tout de suite quand même.
        assertTrue(ProAccess.purchased)
        // Reprise terminée (SyncViewModel.bindProAccount) : la preuve part AVANT l'acquittement.
        ProAccess.bindAccount(uid!!)
        ProAccess.accountUid = uid
        ProAccess.markAccountResolved()
        assertEquals(listOf("post", "grant", "query", "ack:$TOKEN"), log)
        assertTrue(finished)
        // Une seule fois.
        ProAccess.markAccountResolved()
        assertEquals(4, log.size)
    }

    @Test fun repriseSansCompteAcquittementLocal() {
        wireLinker({ 200 to "{}" })
        ProAccess.applyPurchases(listOf(sub()), explicit = false)
        assertEquals(emptyList<String>(), log)
        ProAccess.markAccountResolved() // aucune session à reprendre
        assertEquals(listOf("ack:$TOKEN"), log)
        // Compte connu : un nouveau relevé n'attend plus.
        log.clear()
        ProAccess.applyPurchases(listOf(sub()), explicit = false)
        assertEquals(listOf("ack:$TOKEN"), log)
    }

    @Test fun compteJamaisConnuAcquittementApresLeDelaiMaximal() {
        val timers = FakeTimers()
        ProAccess.timers = timers
        wireLinker({ 200 to "{}" })
        ProAccess.applyPurchases(listOf(sub()), explicit = false)
        ProAccess.applyPurchases(listOf(sub()), explicit = false) // un seul minuteur
        assertEquals(listOf(ProAccess.ACCOUNT_WAIT_MAX_MS), timers.pending.map { it.first })
        assertEquals(emptyList<String>(), log)
        timers.fire()
        assertEquals(listOf("ack:$TOKEN"), log)
        assertTrue(timers.pending.isEmpty())
    }

    @Test fun abonnementDejaAcquitteNAttendPas() {
        wireLinker({ 200 to "{}" })
        ProAccess.applyPurchases(listOf(sub(acknowledged = true)), explicit = false)
        // Rien à acquitter : la preuve suit son déclencheur habituel (ici, sans compte : rien).
        assertEquals(emptyList<String>(), log)
        assertTrue(ProAccess.purchased)
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
