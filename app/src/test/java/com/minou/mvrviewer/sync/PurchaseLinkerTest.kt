package com.minou.mvrviewer.sync

import com.minou.mvrviewer.R
import com.minou.mvrviewer.ui.ProAccess
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * Scénarios clients de l'abonnement reconnu partout (SPEC-purchase.md §6.1, §6.3,
 * §8.3 ; SPEC-abonnement.md §4.1) : [PurchaseLinker] — l'orchestration que le
 * SyncViewModel branche sur ProAccess — exercé sur le stub
 * [LocalSyncService.forTesting] (licence du compte `proGrants` /
 * `proGrantDocuments`), un FAUX serveur (transport injecté) et une fausse preuve.
 * Verrouille : envoi AVANT la relecture de licence, une preuve par PÉRIODE
 * (`orderId`), message seulement pour un refus définitif APRÈS un geste explicite,
 * 401 → un seul nouvel essai avec jeton forcé, déconnexion, réparation une fois par
 * session (licence non Pro OU échue), lecture en échec → rien ne change, backend
 * local sans jeton → aucun appel, garde de course. Plus l'intégration ProAccess
 * (preuve, `obfuscatedAccountId`, message localisé, déconnexion).
 */
class PurchaseLinkerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Journal ordonné des appels (envoi / relecture de licence). */
    private val log = mutableListOf<String>()

    /** Stub local + idToken (le stub n'en a pas : c'est un faux Firebase). */
    private inner class TokenService(val local: LocalSyncService) : SyncService by local {
        val tokenRequests = mutableListOf<Boolean>()
        var tokenAvailable = true
        override suspend fun currentIdToken(forceRefresh: Boolean): String? {
            tokenRequests += forceRefresh
            if (!tokenAvailable) return null
            return if (forceRefresh) "fresh-token" else "cached-token"
        }
        /** Appelé (une fois) PENDANT la prochaine lecture de licence : simule
         *  Play qui répond alors que la lecture Firestore est en vol. */
        var onFetch: (() -> Unit)? = null
        override suspend fun fetchProGrant(): ProGrant? {
            log += "fetch"
            onFetch?.let { onFetch = null; it() }
            return local.fetchProGrant()
        }
    }

    /** Faux serveur : rejoue des réponses ; par défaut 200 `granted`. */
    private inner class FakeTransport : PurchaseLinkTransport {
        val responses = ArrayDeque<() -> Pair<Int, String>>()
        val calls = mutableListOf<Pair<String, String>>() // (corps, jeton)
        var onPost: (() -> Unit)? = null
        override suspend fun post(body: String, idToken: String): Pair<Int, String> {
            log += "post"
            calls += body to idToken
            onPost?.invoke()
            return responses.removeFirstOrNull()?.invoke() ?: (200 to OK)
        }
    }

    private class MemoryStore : PurchaseLinkStore {
        var record: PurchaseLinkRecord? = null
        override fun load() = record
        override fun save(record: PurchaseLinkRecord) { this.record = record }
    }

    private lateinit var local: LocalSyncService
    private lateinit var service: TokenService
    private val transport = FakeTransport()
    private val store = MemoryStore()
    private var proof: PurchaseProof? = PurchaseLink.proofFor(TOKEN, ORDER)
    private var uid: String? = null
    /** Licences appliquées (`pro`, ou null pour une lecture en échec). */
    private val grants = mutableListOf<Boolean?>()
    /** Résolveur de textes d'origine de ProAccess, rétabli après chaque test. */
    private val defaultResolver = ProAccess.messageResolver
    private val refusals = mutableListOf<String?>()

    private fun linker(svc: SyncService = service) = PurchaseLinker(
        service = svc,
        transport = transport,
        store = store,
        proof = { proof },
        currentUid = { uid },
        applyGrant = { _, g -> grants += g?.pro },
        showRefusal = { refusals += it },
        now = { 1_000L }
    )

    /**
     * Câblage IDENTIQUE au SyncViewModel : preuve lue dans ProAccess (donc
     * `null` tant que Play n'a pas répondu), refus posé dans ProAccess.
     * `ProAccess.purchaseLinker` est branché dessus, en synchrone (le
     * ViewModel lance une coroutine ; ici on attend sa fin pour vérifier).
     */
    private fun proAccessLinker(): PurchaseLinker {
        val l = PurchaseLinker(
            service = service,
            transport = transport,
            store = store,
            proof = { ProAccess.currentProof() },
            currentUid = { uid },
            applyGrant = { _, g -> grants += g?.pro },
            showRefusal = { ProAccess.setAccountLinkRefusal(it) },
            now = { 1_000L }
        )
        ProAccess.purchaseLinker = { explicit, done -> runBlocking { l.linkPurchase(explicit) }; done() }
        return l
    }

    /** Abonnement Pro tel que ProAccess le retient d'un `Purchase` Play (PURCHASED,
     *  déjà acquitté : l'ordre preuve/acquittement est testé par ProAccessAckOrderTest).
     *  Pas de vrai `Purchase` ici : son `getOrderId()` appelle `TextUtils`, absent des
     *  tests JVM. */
    private fun snapshot(order: String = ORDER) = PlayPurchaseSnapshot(
        products = listOf(ProAccess.PRO_SUBSCRIPTION_ID), purchased = true, purchaseToken = TOKEN,
        acknowledged = true, autoRenewing = true, orderId = order)

    /** Relecture Play aboutie, suivie du même déclencheur que ProAccess
     *  (connexion du BillingClient : `false` ; abonnement / « Restaurer » : `true`). */
    private fun playAnswers(explicit: Boolean, order: String = ORDER) {
        ProAccess.applyPurchases(listOf(snapshot(order)), explicit)
    }

    /** Mémoire « linked » de CETTE période pour ce compte. */
    private fun linked(account: String, period: String? = ORDER) =
        PurchaseLinkRecord(PurchaseLink.playPurchaseId(TOKEN), account, PurchaseLinkRecord.LINKED, null, 1L, period)

    /** Inscription sur le stub ; le faux serveur ACCORDE la licence à ce compte quand il répond 200. */
    private suspend fun signUp(email: String = "jm@example.com"): String {
        val acc = local.signUp(email, "secret123", "JM")
        uid = acc.uid
        return acc.uid
    }

    private fun serverGrantsOnSuccess(account: String) {
        transport.onPost = { if (transport.responses.isEmpty()) local.proGrants += account }
    }

    @Before fun setUp() {
        local = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))
        service = TokenService(local)
        ProAccess.forceForTests(null)
        ProAccess.resetForTests()
    }

    @After fun tearDown() {
        ProAccess.clearAccount()
        ProAccess.resetForTests() // plus d'abonnement, de preuve ni de licence
        ProAccess.dismissLinkRefusalAlert()
        ProAccess.showPaywall = false
        ProAccess.lastError = null
        ProAccess.messageResolver = defaultResolver
        ProAccess.purchaseLinker = null
        ProAccess.forceForTests(null)
    }

    // MARK: - Ordre : envoi → relecture de licence

    @Test fun signInSendsTheProofBeforeReadingTheGrant() = runBlocking {
        val account = signUp()
        serverGrantsOnSuccess(account)
        val granted = linker().refreshProGrant()
        assertEquals(listOf("post", "fetch"), log)
        assertEquals(true, granted?.pro)
        assertEquals(listOf<Boolean?>(true), grants)
        // Corps EXACT de la preuve, jeton en cache.
        assertEquals(listOf(PurchaseLink.proofFor(TOKEN, ORDER).json to "cached-token"), transport.calls)
        // Mémoire avec la clé de période (`orderId`).
        assertEquals(PurchaseLinkRecord(PurchaseLink.playPurchaseId(TOKEN), account,
            PurchaseLinkRecord.LINKED, null, 1_000L, ORDER), store.record)
        // Succès : message effacé, rien d'autre.
        assertEquals(listOf<String?>(null), refusals)
    }

    @Test fun adminGrantWithoutPurchaseOnlyReadsTheGrant() = runBlocking {
        // Licence posée à la main, aucun achat Play : AUCUN envoi, la licence reste lue telle quelle.
        val account = signUp()
        local.proGrants += account
        proof = null
        assertEquals(true, linker().refreshProGrant()?.pro)
        assertEquals(listOf("fetch"), log)
        assertEquals(listOf<Boolean?>(true), grants)
        assertNull(store.record)
    }

    @Test fun nothingIsSentWhenSignedOut() = runBlocking {
        val l = linker()
        assertNull(l.refreshProGrant())
        assertNull(l.linkPurchase(explicit = true))
        assertNull(l.send(explicit = true))
        assertTrue(transport.calls.isEmpty())
        assertTrue(grants.isEmpty())
        assertTrue(service.tokenRequests.isEmpty())
    }

    // MARK: - Mémoire de l'issue (SPEC-abonnement §4.1.1)

    @Test fun automaticSendIsSkippedOnceSettledForThisPeriodAndAccount() = runBlocking {
        val account = signUp()
        // Licence déjà lue vraie (sinon « linked » + licence fausse = réparation, testée plus bas).
        local.proGrants += account
        val pid = PurchaseLink.playPurchaseId(TOKEN)
        for (settled in listOf(PurchaseLinkRecord.LINKED, PurchaseLinkRecord.REFUSED)) {
            log.clear()
            store.record = PurchaseLinkRecord(pid, account, settled, null, 1L, ORDER)
            linker().refreshProGrant()
            assertEquals(listOf("fetch"), log)
        }
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun retryOtherAccountOtherPurchaseNewPeriodOrOldEntryIsSentAgain() = runBlocking {
        val account = signUp()
        val pid = PurchaseLink.playPurchaseId(TOKEN)
        val cases = listOf(
            PurchaseLinkRecord(pid, account, PurchaseLinkRecord.RETRY, null, 1L, ORDER),
            PurchaseLinkRecord(pid, "another-uid", PurchaseLinkRecord.LINKED, null, 1L, ORDER),
            PurchaseLinkRecord("play_other", account, PurchaseLinkRecord.LINKED, null, 1L, ORDER),
            // Période PRÉCÉDENTE réglée : le renouvellement repart une fois (filet).
            PurchaseLinkRecord(pid, account, PurchaseLinkRecord.LINKED, null, 1L, "GPA.previous"),
            // Entrée de l'achat unique, sans periodKey : ne bloque rien.
            PurchaseLinkRecord(pid, account, PurchaseLinkRecord.LINKED, null, 1L)
        )
        for ((i, r) in cases.withIndex()) {
            store.record = r
            assertEquals(PurchaseLinkOutcome.Linked, linker().send(explicit = false))
            assertEquals(i + 1, transport.calls.size)
        }
    }

    @Test fun explicitActionAlwaysSendsEvenIfAlreadyLinked() = runBlocking {
        val account = signUp()
        store.record = linked(account)
        assertEquals(PurchaseLinkOutcome.Linked, linker().linkPurchase(explicit = true))
        assertEquals(1, transport.calls.size)
    }

    // MARK: - Messages : refus définitif + geste explicite seulement (§6.4)

    @Test fun automatic409ShowsNoMessage() = runBlocking {
        val account = signUp()
        transport.responses += { 409 to err("owned_by_other_account") }
        assertEquals(PurchaseLinkOutcome.Refused("owned_by_other_account"), linker().linkPurchase(explicit = false))
        assertTrue(refusals.isEmpty())
        assertTrue(grants.isEmpty()) // refusé : pas de relecture
        assertEquals(PurchaseLinkRecord(PurchaseLink.playPurchaseId(TOKEN), account,
            PurchaseLinkRecord.REFUSED, "owned_by_other_account", 1_000L, ORDER), store.record)
    }

    /**
     * RÉGRESSION (refus jamais vu là où le geste a été fait) : scénario RÉEL —
     * paywall ouvert par une garde pendant l'édition, Play confirme l'achat
     * (même enchaînement que `onPurchasesUpdated` / `restore()`), le paywall
     * se ferme AVANT l'envoi, le serveur répond 409. Le refus doit être vu tout
     * de suite (alerte) ET rester dans l'écran compte ; `lastError` (paywall
     * fermé) n'est pas un endroit visible.
     */
    @Test fun explicitRefusalAfterAPaywallPurchaseIsShownRightAwayAndKeptInAccount() = runBlocking {
        signUp()
        ProAccess.messageResolver = { id -> "res:$id" }
        val l = proAccessLinker()
        var paywallOpenWhenSending: Boolean? = null
        ProAccess.purchaseLinker = { explicit, done ->
            paywallOpenWhenSending = ProAccess.showPaywall
            runBlocking { l.linkPurchase(explicit) }
            done()
        }
        // Appareil gratuit : la garde ouvre le paywall.
        assertFalse(ProAccess.gate("modifier le patch") {})
        assertTrue(ProAccess.showPaywall)
        transport.responses += { 409 to err("owned_by_other_account") }
        playAnswers(explicit = true)
        // L'achat débloque l'appareil et FERME le paywall avant que la preuve parte.
        assertEquals(false, paywallOpenWhenSending)
        assertTrue(ProAccess.purchased)
        assertFalse(ProAccess.showPaywall)
        assertEquals(1, transport.calls.size)
        val expected = "res:${R.string.pro_link_owned_by_other_account}"
        assertEquals(expected, ProAccess.accountLinkMessage)
        assertEquals(expected, ProAccess.linkRefusalAlert) // vu TOUT DE SUITE
        assertNull(ProAccess.lastError)
        // Le paywall ne se rouvre pas (Pro local) : l'alerte est le seul endroit immédiat.
        assertTrue(ProAccess.gate("modifier le patch") {})
        assertFalse(ProAccess.showPaywall)
        // Fermée par l'utilisateur : le message reste dans l'écran compte.
        ProAccess.dismissLinkRefusalAlert()
        assertNull(ProAccess.linkRefusalAlert)
        assertEquals(expected, ProAccess.accountLinkMessage)
        // Un rattachement réussi ensuite efface tout.
        playAnswers(explicit = true)
        assertNull(ProAccess.accountLinkMessage)
        assertNull(ProAccess.linkRefusalAlert)
    }

    @Test fun refusalWhileTheAccountSectionIsOnScreenRaisesNoExtraAlert() = runBlocking {
        signUp()
        ProAccess.messageResolver = { id -> "res:$id" }
        val l = proAccessLinker()
        val expected = "res:${R.string.pro_link_revoked}"
        // « Restaurer un achat » touché DANS l'écran compte : le refus s'y affiche.
        ProAccess.accountSectionShown(true)
        try {
            transport.responses += { 410 to err("revoked") }
            playAnswers(explicit = true)
            assertEquals(expected, ProAccess.accountLinkMessage)
            assertNull(ProAccess.linkRefusalAlert)
        } finally {
            ProAccess.accountSectionShown(false)
        }
        // Écran compte fermé : le refus suivant passe par l'alerte.
        transport.responses += { 410 to err("revoked") }
        l.linkPurchase(explicit = true)
        assertEquals(expected, ProAccess.linkRefusalAlert)
        // Le compteur ne descend jamais sous zéro (fermetures en trop).
        ProAccess.accountSectionShown(false)
        ProAccess.accountSectionShown(true)
        try {
            transport.responses += { 410 to err("revoked") }
            l.linkPurchase(explicit = true)
            assertNull(ProAccess.linkRefusalAlert)
        } finally {
            ProAccess.accountSectionShown(false)
        }
    }

    @Test fun automaticRefusalNeverRaisesTheAlert() = runBlocking {
        signUp()
        ProAccess.messageResolver = { id -> "res:$id" }
        proAccessLinker()
        transport.responses += { 409 to err("owned_by_other_account") }
        playAnswers(explicit = false) // relecture Play à la connexion du BillingClient
        assertEquals(1, transport.calls.size)
        assertNull(ProAccess.accountLinkMessage)
        assertNull(ProAccess.linkRefusalAlert)
    }

    @Test fun eachDefinitiveCodeSurfacesOnlyWhenExplicit() = runBlocking {
        signUp()
        for (code in listOf("revoked", "invalid_purchase", "bad_request", "test_purchase_refused")) {
            refusals.clear()
            store.record = null // sinon l'envoi automatique serait sauté (refus déjà mémorisé)
            transport.responses.clear()
            val status = mapOf("revoked" to 410, "invalid_purchase" to 422, "bad_request" to 400,
                "test_purchase_refused" to 403).getValue(code)
            transport.responses += { status to err(code) }
            assertEquals(PurchaseLinkOutcome.Refused(code), linker().linkPurchase(explicit = false))
            assertTrue(code, refusals.isEmpty())
            assertEquals(PurchaseLinkRecord.REFUSED, store.record?.outcome)
            transport.responses += { status to err(code) }
            assertEquals(PurchaseLinkOutcome.Refused(code), linker().linkPurchase(explicit = true))
            assertEquals(code, listOf<String?>(code), refusals)
        }
    }

    @Test fun retryableFailuresAreSilentEvenWhenExplicit() = runBlocking {
        signUp()
        val retryables = listOf<() -> Pair<Int, String>>(
            { 202 to """{"ok":false,"code":"pending","retryable":true}""" },
            { 503 to """{"ok":false,"code":"store_unavailable","retryable":true}""" },
            { 503 to """{"ok":false,"code":"not_configured","retryable":true}""" },
            { 500 to "boom" },
            { throw IOException("hors ligne") }
        )
        for (r in retryables) {
            transport.responses += r
            assertEquals(PurchaseLinkOutcome.Retry, linker().linkPurchase(explicit = true))
            assertEquals(PurchaseLinkRecord.RETRY, store.record?.outcome)
        }
        assertTrue(refusals.isEmpty())
        assertTrue(grants.isEmpty())
    }

    // MARK: - 401 → un seul nouvel essai avec un jeton forcé

    @Test fun unauthorizedRetriesOnceWithAForcedToken() = runBlocking {
        val account = signUp()
        serverGrantsOnSuccess(account)
        transport.responses += { 401 to err("sign_in_required") }
        assertEquals(PurchaseLinkOutcome.Linked, linker().linkPurchase(explicit = false))
        assertEquals(listOf("cached-token", "fresh-token"), transport.calls.map { it.second })
        assertEquals(listOf(false, true), service.tokenRequests)
        assertEquals(listOf<Boolean?>(true), grants)
    }

    @Test fun persistentUnauthorizedIsRetryAfterExactlyTwoCalls() = runBlocking {
        signUp()
        transport.responses += { 401 to err("sign_in_required") }
        transport.responses += { 401 to err("sign_in_required") }
        assertEquals(PurchaseLinkOutcome.Retry, linker().linkPurchase(explicit = true))
        assertEquals(2, transport.calls.size)
        assertEquals(PurchaseLinkRecord.RETRY, store.record?.outcome)
        assertTrue(refusals.isEmpty())
    }

    // MARK: - Retour au premier plan

    @Test fun lastOutcomeIsRetryDrivesTheForegroundResend() = runBlocking {
        signUp()
        val l = linker()
        assertFalse(l.lastOutcomeIsRetry())
        transport.responses += { throw IOException("hors ligne") }
        l.linkPurchase(explicit = false)
        assertTrue(l.lastOutcomeIsRetry())
        // Retour au premier plan, réseau revenu : renvoyé, puis plus rien à faire.
        l.linkPurchase(explicit = false)
        assertFalse(l.lastOutcomeIsRetry())
        assertEquals(PurchaseLinkRecord.LINKED, store.record?.outcome)
    }

    // MARK: - Réparation (§6.1)

    @Test fun repairResendsAtMostOncePerSession() = runBlocking {
        val account = signUp()
        // Mémorisé « linked » pour ce compte, mais la licence lue est fausse
        // (document retouché, écriture perdue…) et le serveur ne la rétablit pas.
        store.record = linked(account)
        val l = linker()
        assertEquals(false, l.refreshProGrant()?.pro)
        assertEquals(listOf("fetch", "post", "fetch"), log)
        log.clear()
        assertEquals(false, l.refreshProGrant()?.pro)
        assertEquals(listOf("fetch"), log) // pas de second envoi forcé dans la session
        assertTrue(refusals.none { it != null }) // envoi forcé = automatique : jamais de message
    }

    @Test fun repairThatSucceedsRestoresTheGrant() = runBlocking {
        val account = signUp()
        store.record = linked(account)
        serverGrantsOnSuccess(account)
        assertEquals(true, linker().refreshProGrant()?.pro)
        assertEquals(listOf("fetch", "post", "fetch"), log)
        assertEquals(listOf<Boolean?>(true), grants)
    }

    @Test fun anExpiredLicenceIsRepairedLikeAMissingOne() = runBlocking {
        // §4.1.3 : licence `pro: true` mais d'échéance PASSÉE (renouvellement que le
        // serveur n'a pas vu) alors que l'abonnement de l'appareil est actif.
        val account = signUp()
        store.record = linked(account)
        local.proGrantDocuments[account] = ProGrant(pro = true, expires = 500L, fromServer = true) // now = 1 000
        transport.onPost = { local.proGrantDocuments[account] = ProGrant(pro = true, expires = 9_000L, fromServer = true) }
        val grant = linker().refreshProGrant()
        assertEquals(listOf("fetch", "post", "fetch"), log)
        assertEquals(9_000L, grant?.expires)
    }

    @Test fun aFailedGrantReadChangesNothingAndNeverRepairs() = runBlocking {
        val account = signUp()
        store.record = linked(account)
        local.proGrantFetchFails = true
        assertNull(linker().refreshProGrant())
        // Lecture en échec : appliquée comme `null` (rien ne change), aucun envoi forcé.
        assertEquals(listOf("fetch"), log)
        assertEquals(listOf<Boolean?>(null), grants)
        assertTrue(transport.calls.isEmpty())
    }

    // MARK: - Réparation au démarrage à froid : indépendante de l'ordre d'arrivée

    /**
     * RÉGRESSION (course au démarrage à froid) : session restaurée → licence
     * relue AVANT que Play ait rendu l'achat (pas de preuve) → `false` ; puis
     * Play répond → envoi automatique sauté (« linked »). La réparation doit
     * quand même avoir lieu, à l'arrivée de la preuve.
     */
    @Test fun coldStartRepairHappensWhenPlayAnswersAfterTheGrantRead() = runBlocking {
        val account = signUp()
        store.record = linked(account)
        serverGrantsOnSuccess(account)
        val l = proAccessLinker()
        // (1) Session restaurée, Play pas encore répondu.
        assertNull(ProAccess.currentProof())
        assertEquals(false, l.refreshProGrant()?.pro)
        assertEquals(listOf("fetch"), log)
        // (2) Play répond (relecture à la connexion du BillingClient).
        playAnswers(explicit = false)
        assertEquals(listOf("fetch", "post", "fetch"), log)
        assertEquals(listOf<Boolean?>(false, true), grants)
        assertEquals(PurchaseLinkRecord.LINKED, store.record?.outcome)
        // Envoi forcé = automatique : aucun message.
        assertNull(ProAccess.accountLinkMessage)
        assertNull(ProAccess.linkRefusalAlert)
    }

    @Test fun coldStartRepairIsStillAtMostOncePerSession() = runBlocking {
        val account = signUp()
        store.record = linked(account)
        // Le serveur répond 200 mais la licence reste fausse.
        val l = proAccessLinker()
        assertEquals(false, l.refreshProGrant()?.pro)
        playAnswers(explicit = false)
        assertEquals(listOf("fetch", "post", "fetch"), log)
        assertEquals(listOf<Boolean?>(false, false), grants)
        log.clear()
        // Nouvelles relectures Play et de licence dans la même session : plus d'envoi forcé.
        playAnswers(explicit = false)
        assertEquals(emptyList<String>(), log)
        assertEquals(false, l.refreshProGrant()?.pro)
        assertEquals(listOf("fetch"), log)
        assertEquals(1, transport.calls.size)
    }

    @Test fun playAnsweringDuringTheGrantReadRepairsExactlyOnce() = runBlocking {
        val account = signUp()
        store.record = linked(account)
        serverGrantsOnSuccess(account)
        val l = proAccessLinker()
        // Play répond PENDANT la lecture Firestore (l'autre ordre d'arrivée).
        service.onFetch = { playAnswers(explicit = false) }
        assertEquals(true, l.refreshProGrant()?.pro)
        assertEquals(listOf("fetch", "post", "fetch"), log)
        assertEquals(1, transport.calls.size)
        assertEquals(listOf<Boolean?>(true), grants)
    }

    @Test fun playAnsweringAfterATrueGrantCostsNoReadAndNoSend() = runBlocking {
        val account = signUp()
        local.proGrants += account
        store.record = linked(account)
        val l = proAccessLinker()
        assertEquals(true, l.refreshProGrant()?.pro)
        playAnswers(explicit = false)
        // Cas courant de chaque lancement : rien de plus que la lecture de licence.
        assertEquals(listOf("fetch"), log)
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun proofArrivingBeforeAnyGrantReadDoesNotRepairOnItsOwn() = runBlocking {
        val account = signUp()
        store.record = linked(account)
        proAccessLinker()
        // Aucune licence lue dans la session : la relecture de licence à venir décidera.
        playAnswers(explicit = false)
        assertEquals(emptyList<String>(), log)
        assertTrue(grants.isEmpty())
    }

    // MARK: - Backend local : pas de jeton → aucun appel

    @Test fun localBackendHasNoTokenSoNothingIsPosted() = runBlocking {
        assertNull(local.currentIdToken(false))
        assertNull(local.currentIdToken(true))
        signUp()
        val l = linker(svc = local)
        assertEquals(PurchaseLinkOutcome.Retry, l.linkPurchase(explicit = true))
        assertEquals(false, l.refreshProGrant()?.pro)
        assertTrue(transport.calls.isEmpty())
        assertFalse(local.verifiesPurchases)
        assertTrue(refusals.isEmpty())
    }

    // MARK: - Garde de course

    @Test fun accountChangedDuringTheSendIsIgnored() = runBlocking {
        signUp()
        transport.onPost = { uid = "someone-else" }
        transport.responses += { 409 to err("owned_by_other_account") }
        linker().linkPurchase(explicit = true)
        assertTrue(refusals.isEmpty())
        transport.responses += { 200 to OK }
        uid = "u-1"
        transport.onPost = { uid = "u-2" }
        linker().linkPurchase(explicit = true)
        assertTrue(grants.isEmpty()) // licence relue pour u-1 jamais appliquée à u-2
    }

    // MARK: - Intégration ProAccess

    @Test fun pendingPurchaseNeverYieldsAProof() {
        val pro = listOf(ProAccess.PRO_SUBSCRIPTION_ID)
        ProAccess.rememberPurchases(listOf(PlayPurchaseSnapshot(pro, purchased = false, purchaseToken = "t-pending")))
        assertNull(ProAccess.currentProof())
        ProAccess.rememberPurchases(listOf(PlayPurchaseSnapshot(pro, purchased = true, purchaseToken = TOKEN, orderId = ORDER)))
        val p = ProAccess.currentProof()!!
        assertEquals(PurchaseLink.playPurchaseId(TOKEN), p.purchaseId)
        assertEquals(ORDER, p.periodKey)
        assertEquals(TOKEN, JSONObject(p.json).getString("purchaseToken"))
        assertEquals("fr", JSONObject(p.json).getString("lang")) // sans Context : langue par défaut
        // Relecture Play sans achat (remboursé) : plus de preuve.
        ProAccess.rememberPurchases(emptyList())
        assertNull(ProAccess.currentProof())
    }

    @Test fun purchaseFlowCarriesTheHashedAccountIdOnlyWhenSignedIn() {
        assertNull(ProAccess.purchaseAccountId())
        ProAccess.accountUid = "abc123"
        assertEquals("a06c3e2ac1f894a5b8cb038f923e2893af09abee6573386fcd8d27c206e9b0c5",
            ProAccess.purchaseAccountId())
    }

    @Test fun signOutClearsAccountAndMessageButKeepsTheRecord() = runBlocking {
        val account = signUp()
        ProAccess.accountUid = account
        ProAccess.messageResolver = { id -> "res:$id" }
        transport.responses += { 410 to err("revoked") }
        PurchaseLinker(service, transport, store, { proof }, { uid },
            { _, g -> grants += g?.pro }, { ProAccess.setAccountLinkRefusal(it) }, { 1_000L })
            .linkPurchase(explicit = true)
        assertEquals("res:${R.string.pro_link_revoked}", ProAccess.accountLinkMessage)
        assertEquals("res:${R.string.pro_link_revoked}", ProAccess.linkRefusalAlert)
        assertNull(ProAccess.lastError)
        ProAccess.clearAccount()
        assertNull(ProAccess.accountUid)
        assertNull(ProAccess.accountLinkMessage)
        assertNull(ProAccess.linkRefusalAlert)
        assertNull(ProAccess.purchaseAccountId())
        // La mémoire (indexée par uid) survit à la déconnexion.
        assertEquals(PurchaseLinkRecord.REFUSED, store.record?.outcome)
        assertEquals(account, store.record?.uid)
    }

    @Test fun restoreStillRefreshesTheGrantWithoutPlay() {
        // Sans Play (non initialisé) : le crochet de licence agit, l'envoi
        // explicite attend la relecture Play (qui n'a pas lieu ici).
        var refreshed = 0
        var linked = 0
        ProAccess.grantRefresher = { done -> refreshed++; done() }
        ProAccess.purchaseLinker = { _, done -> linked++; done() }
        try {
            ProAccess.restore(ProAccess.RestoreScreen.ACCOUNT)
            assertEquals(1, refreshed)
            assertEquals(0, linked)
            assertFalse(ProAccess.isBusy)
        } finally {
            ProAccess.grantRefresher = null
        }
    }

    // MARK: - Renouvellement (SPEC-abonnement §4.1.2) : nouvel orderId → nouvel envoi

    @Test fun aRenewalWithANewOrderIdIsSentOnceAutomatically() = runBlocking {
        val account = signUp()
        local.proGrants += account
        proAccessLinker()
        playAnswers(explicit = false)
        assertEquals(1, transport.calls.size)
        // Même période, nouvelles relectures : rien.
        playAnswers(explicit = false)
        assertEquals(1, transport.calls.size)
        // Google renouvelle : nouvel orderId → la preuve repart UNE fois.
        playAnswers(explicit = false, order = "$ORDER..0")
        assertEquals(2, transport.calls.size)
        assertEquals("$ORDER..0", store.record?.periodKey)
        playAnswers(explicit = false, order = "$ORDER..0")
        assertEquals(2, transport.calls.size)
    }

    @Test fun anExpiredSubscriptionLinkedAsInactiveShowsNoMessage() = runBlocking {
        signUp()
        transport.responses += { 200 to """{"ok":true,"status":"inactive","pro":false,"store":"play","test":false}""" }
        assertEquals(PurchaseLinkOutcome.Linked, linker().linkPurchase(explicit = true))
        assertEquals(listOf<String?>(null), refusals) // succès : message effacé, aucun texte
        assertEquals(PurchaseLinkRecord.LINKED, store.record?.outcome)
    }

    private companion object {
        const val TOKEN = "opaque-token-1.AO-J1Oz"
        const val ORDER = "GPA.3346-1234-5678-90123"
        const val OK = """{"ok":true,"status":"granted","pro":true,"store":"play","test":false}"""
        fun err(code: String) = """{"ok":false,"code":"$code","retryable":false,"error":"…"}"""
    }
}
