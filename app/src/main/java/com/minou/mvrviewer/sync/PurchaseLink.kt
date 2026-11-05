package com.minou.mvrviewer.sync

import android.content.Context
import com.minou.mvrviewer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ABONNEMENT PRO RECONNU SUR TOUTES LES PLATEFORMES — côté Android
 * (SPEC-purchase.md §3.3, §6.1, §6.3 ; SPEC-abonnement.md §3.2, §4.1, §4.3 ;
 * miroir de `PurchaseLink.swift` iOS).
 *
 * L'abonnement Play ne vaut, en lui-même, que sur l'appareil. Pour qu'il compte
 * aussi sur iPhone et sur le web, l'app envoie sa PREUVE (le `purchaseToken` Play)
 * à la fonction serveur `verifyPurchase` (`POST /api/purchase/verify`, authentifiée
 * par l'idToken Firebase) ; la fonction la vérifie auprès de Google
 * (`subscriptionsv2.get`), l'ACQUITTE (le serveur d'abord, l'app ensuite seulement,
 * §3.4 étape 5), rattache l'abonnement au COMPTE et écrit `entitlements/{uid}`
 * (licence DATÉE) — le document que [SyncService.fetchProGrant] lit. Ce fichier est
 * PUR (JVM, testable sans Activity ni Play) : dérivations, corps JSON,
 * interprétation de la réponse, décision d'envoi, mémoire de la dernière issue,
 * transport HTTP, et l'orchestration [PurchaseLinker].
 *
 * PAS DE FILE D'ATTENTE à persister (§6.1) : la preuve se relit à tout moment
 * depuis Play (`queryPurchasesAsync` répond aussi hors ligne). On ne mémorise que
 * l'ISSUE de la dernière tentative par (abonnement, PÉRIODE, compte)
 * ([PurchaseLinkRecord], préférence `pro.link`) : chaque renouvellement (nouvel
 * `orderId`) repart une fois vers le serveur. Une issue « retry » (hors ligne, non
 * connecté au serveur, 503…) est renvoyée au déclencheur suivant (connexion,
 * lancement, retour au premier plan).
 *
 * L'état Pro LOCAL ne dépend jamais du serveur : l'abonnement Play débloque
 * l'appareil même si l'envoi échoue ; le serveur n'ajoute QUE la licence du compte.
 */
object PurchaseLink {
    /** Route Hosting STABLE (réécrite vers la fonction `verifyPurchase`, §1.1). */
    const val ENDPOINT = "https://mvrviewer-app.web.app/api/purchase/verify"
    /** Abonnement Pro (Play Console, SPEC-abonnement §1.1) — un produit = le droit Pro. */
    const val PRO_SUBSCRIPTION_ID = "com.minou.mvrviewer.pro.subscription"
    /** Plan de base annuel (renouvellement automatique, `P1Y`). */
    const val PRO_BASE_PLAN_ID = "annual"
    const val PACKAGE_NAME = "com.minou.mvrviewer"
    /** Préférences partagées avec `ProAccess` ; clé de la dernière issue (§6.1). */
    const val PREFS = "mvrviewer.display"
    const val KEY_LINK = "pro.link"

    private const val ACCOUNT_ID_PREFIX = "mvrviewer/obfuscatedAccountId/v1/"
    /** Statuts dont le corps JSON porte un REFUS définitif (§1.2, §6.1). */
    private val REFUSED_STATUSES = setOf(400, 403, 409, 410, 422)

    /**
     * Identifiant de compte passé à Play (`BillingFlowParams.setObfuscatedAccountId`,
     * §3.3) : SHA-256 hexadécimal minuscule de l'uid préfixé — 64 caractères (la
     * limite de Play), à sens unique (aucune donnée personnelle en clair). Le
     * serveur le relit dans `obfuscatedExternalAccountId` et le compare au sien.
     */
    fun obfuscatedAccountId(uid: String): String =
        ContentHash.sha256Hex((ACCOUNT_ID_PREFIX + uid).toByteArray(Charsets.UTF_8))

    /** Id du document serveur `purchases/{id}` d'un achat Play (§3.1). */
    fun playPurchaseId(purchaseToken: String): String =
        "play_" + ContentHash.sha256Hex(purchaseToken.toByteArray(Charsets.UTF_8))

    /** Langue des e-mails de l'abonnement (`lang` du corps, §3.2) : « fr » pour une
     *  app affichée en français, « en » sinon. */
    fun language(displayed: String): String = if (com.minou.mvrviewer.LegalLinks.isFrench(displayed)) "fr" else "en"

    /**
     * Preuve d'un abonnement Play : corps EXACT du §3.2 (forme Android, avec
     * `lang`) ; `periodKey` = `orderId` de la période (§4.1.1).
     */
    fun proofFor(purchaseToken: String, orderId: String? = null, lang: String = "fr"): PurchaseProof = PurchaseProof(
        purchaseId = playPurchaseId(purchaseToken),
        periodKey = orderId,
        json = JSONObject()
            .put("platform", "android")
            .put("purchaseToken", purchaseToken)
            .put("productId", PRO_SUBSCRIPTION_ID)
            .put("packageName", PACKAGE_NAME)
            .put("lang", if (lang == "en") "en" else "fr")
            .toString()
    )

    /**
     * Abonnement de la preuve parmi les achats lus chez Play : le premier
     * abonnement Pro à l'état PURCHASED. Un achat EN ATTENTE (PENDING) ne produit
     * jamais de preuve (§6.3) — le serveur répondrait 202 sans rien écrire.
     */
    fun selectProof(purchases: List<PlayPurchaseSnapshot>): PlayPurchaseSnapshot? =
        purchases.firstOrNull { it.isProSubscription && it.purchased && it.purchaseToken.isNotEmpty() }

    /** Jeton de [selectProof]. */
    fun selectProofToken(purchases: List<PlayPurchaseSnapshot>): String? = selectProof(purchases)?.purchaseToken

    /**
     * Issue d'une réponse de `verifyPurchase` (table §1.2 / §6.1) :
     *  - 200 avec `{"ok":true,…}` → [PurchaseLinkOutcome.Linked] ;
     *  - 400/403/409/410/422 avec `{"ok":false,"code":…}` → [PurchaseLinkOutcome.Refused] ;
     *  - tout le reste (202 en attente, 401 après le nouvel essai, 5xx, 404…) →
     *    [PurchaseLinkOutcome.Retry], silencieux et renvoyé plus tard.
     * PRUDENCE : un corps qui n'est PAS la réponse JSON de la fonction (page HTML
     * d'un intermédiaire, fonction pas encore déployée, 403 d'infrastructure)
     * n'est jamais pris pour un verdict — ni « rattaché », ni « refusé » (ce
     * dernier bloquerait les renvois automatiques et afficherait un faux motif) :
     * → Retry.
     */
    fun outcome(status: Int, body: String?): PurchaseLinkOutcome {
        val json = body?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        return when {
            status == 200 ->
                if (json?.optBoolean("ok", false) == true) PurchaseLinkOutcome.Linked
                else PurchaseLinkOutcome.Retry
            status in REFUSED_STATUSES -> {
                val code = json
                    ?.takeIf { it.has("ok") && !it.optBoolean("ok", true) }
                    ?.optString("code", "")
                    ?.takeIf { it.isNotEmpty() }
                if (code != null) PurchaseLinkOutcome.Refused(code) else PurchaseLinkOutcome.Retry
            }
            else -> PurchaseLinkOutcome.Retry
        }
    }

    /**
     * Faut-il envoyer la preuve (SPEC-abonnement §4.1.1) ? Jamais sans preuve ni
     * compte ; toujours pour un geste EXPLICITE (s'abonner, « Restaurer mes
     * achats » — même si la mémoire dit déjà fait) ; en automatique, SAUTÉ
     * seulement si la dernière issue porte sur CET abonnement, CETTE période
     * (`orderId`) et CE compte, et qu'elle est définitive (« linked » ou
     * « refused »). Une entrée ancienne sans `periodKey` ne bloque rien : chaque
     * renouvellement repart.
     */
    fun shouldSend(
        proofId: String?, periodKey: String?, uid: String?, explicit: Boolean, record: PurchaseLinkRecord?
    ): Boolean {
        if (proofId == null || uid == null) return false
        if (explicit) return true
        if (record == null) return true
        val settled = record.outcome == PurchaseLinkRecord.LINKED || record.outcome == PurchaseLinkRecord.REFUSED
        val samePeriod = record.periodKey != null && record.periodKey == periodKey
        return !(settled && record.purchaseId == proofId && record.uid == uid && samePeriod)
    }

    /**
     * Texte localisé (FR + EN, `strings.xml`) d'un refus DÉFINITIF, affiché
     * seulement après un geste explicite (SPEC-abonnement §5.9). Aucune de ces
     * phrases ne cite une autre plateforme d'achat. Code inconnu → message générique.
     */
    fun refusalMessageRes(code: String): Int = when (code) {
        "owned_by_other_account" -> R.string.pro_link_owned_by_other_account
        "revoked" -> R.string.pro_link_revoked
        "test_purchase_refused" -> R.string.pro_link_test_purchase_refused
        else -> R.string.pro_link_invalid_purchase // invalid_purchase, bad_request
    }
}

/** Ce que l'app retient d'un `Purchase` Play (abonnement) pour en tirer une preuve. */
data class PlayPurchaseSnapshot(
    val products: List<String>,
    /** `Purchase.PurchaseState.PURCHASED` (faux pour PENDING). */
    val purchased: Boolean,
    val purchaseToken: String,
    /** `Purchase.isAcknowledged`. */
    val acknowledged: Boolean = true,
    /** `Purchase.isAutoRenewing`. */
    val autoRenewing: Boolean = true,
    /** `Purchase.orderId` : clé de la période (§4.1.1) ; null pour un achat en attente. */
    val orderId: String? = null
) {
    val isProSubscription: Boolean get() = PurchaseLink.PRO_SUBSCRIPTION_ID in products
    /** Achat EN ATTENTE (paiement Play différé). */
    val pending: Boolean get() = isProSubscription && !purchased
}

/** Preuve prête à envoyer : id serveur de l'abonnement, clé de la période
 *  (`orderId`) et corps JSON du §3.2. */
data class PurchaseProof(val purchaseId: String, val periodKey: String? = null, val json: String)

/** Issue d'un envoi de preuve (§6.1). */
sealed class PurchaseLinkOutcome {
    /** Abonnement rattaché à ce compte (200 `granted`, `already` ou `inactive` :
     *  un abonnement expiré rattaché n'est pas une erreur, aucun message). */
    data object Linked : PurchaseLinkOutcome()
    /** Refus DÉFINITIF (400/403/409/410/422) — `code` du contrat §1.2. */
    data class Refused(val code: String) : PurchaseLinkOutcome()
    /** Échec RÉESSAYABLE (réseau, 202, 401 persistant, 5xx…) : renvoyé plus tard. */
    data object Retry : PurchaseLinkOutcome()
}

/**
 * Dernière issue d'envoi (UNE entrée), indexée par (abonnement, PÉRIODE, compte)
 * — SPEC-abonnement §4.1.1 : `{ purchaseId, periodKey, uid, outcome:
 * "linked"|"refused"|"retry", code, at }`. Persistée en JSON dans la préférence
 * `pro.link`. JAMAIS effacée à la déconnexion (elle porte l'uid : un autre compte
 * n'en hérite pas). Entrée de l'achat unique : sans `periodKey` (elle ne bloque
 * plus aucun envoi).
 */
data class PurchaseLinkRecord(
    val purchaseId: String,
    val uid: String,
    val outcome: String,
    val code: String?,
    /** Instant de la tentative (ms epoch). */
    val at: Long,
    /** `orderId` de la période envoyée ; null : entrée ancienne. */
    val periodKey: String? = null
) {
    fun toJson(): String = JSONObject()
        .put("purchaseId", purchaseId)
        .apply { if (periodKey != null) put("periodKey", periodKey) }
        .put("uid", uid)
        .put("outcome", outcome)
        .apply { if (code != null) put("code", code) }
        .put("at", at)
        .toString()

    companion object {
        const val LINKED = "linked"
        const val REFUSED = "refused"
        const val RETRY = "retry"

        fun of(purchaseId: String, uid: String, outcome: PurchaseLinkOutcome, at: Long, periodKey: String? = null) =
            when (outcome) {
                PurchaseLinkOutcome.Linked -> PurchaseLinkRecord(purchaseId, uid, LINKED, null, at, periodKey)
                is PurchaseLinkOutcome.Refused -> PurchaseLinkRecord(purchaseId, uid, REFUSED, outcome.code, at, periodKey)
                PurchaseLinkOutcome.Retry -> PurchaseLinkRecord(purchaseId, uid, RETRY, null, at, periodKey)
            }

        /** Relit l'entrée ; `null` si absente ou illisible (comme si rien n'avait été tenté). */
        fun fromJson(s: String?): PurchaseLinkRecord? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(s)
                val purchaseId = o.getString("purchaseId")
                val uid = o.getString("uid")
                val outcome = o.getString("outcome")
                if (outcome != LINKED && outcome != REFUSED && outcome != RETRY) return null
                PurchaseLinkRecord(
                    purchaseId, uid, outcome,
                    o.optString("code", "").takeIf { it.isNotEmpty() },
                    o.optLong("at", 0L),
                    o.optString("periodKey", "").takeIf { it.isNotEmpty() }
                )
            }.getOrNull()
        }
    }
}

/** Mémoire de la dernière issue — abstraite pour les tests JVM. */
interface PurchaseLinkStore {
    fun load(): PurchaseLinkRecord?
    fun save(record: PurchaseLinkRecord)
}

/** Sur l'appareil : préférences `mvrviewer.display`, clé `pro.link` (JSON). */
class PrefsPurchaseLinkStore(context: Context) : PurchaseLinkStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PurchaseLink.PREFS, Context.MODE_PRIVATE)

    override fun load(): PurchaseLinkRecord? =
        PurchaseLinkRecord.fromJson(prefs.getString(PurchaseLink.KEY_LINK, null))

    override fun save(record: PurchaseLinkRecord) {
        prefs.edit().putString(PurchaseLink.KEY_LINK, record.toJson()).apply()
    }
}

/** Transport de la preuve — abstrait pour les tests (faux serveur). */
interface PurchaseLinkTransport {
    /**
     * POST du corps JSON avec `Authorization: Bearer <idToken>` ; rend
     * (statut HTTP, corps). LÈVE une exception (IOException…) si le réseau échoue.
     */
    suspend fun post(body: String, idToken: String): Pair<Int, String>
}

/**
 * Transport RÉEL : [HttpURLConnection] sur [Dispatchers.IO] — AUCUNE dépendance
 * nouvelle (ni OkHttp, ni Firebase Functions). Délais §6.1 : 15 s connexion,
 * 30 s lecture. Corps de réponse borné (la fonction répond quelques centaines
 * d'octets ; une page d'erreur géante n'est pas lue en entier).
 */
class HttpPurchaseLinkTransport(
    private val endpoint: String = PurchaseLink.ENDPOINT
) : PurchaseLinkTransport {

    override suspend fun post(body: String, idToken: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $idToken")
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            status to (stream?.use { readBounded(it) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun readBounded(input: InputStream, limit: Int = 64 * 1024): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

/**
 * ORCHESTRATION de l'envoi (SPEC-purchase §6.1, SPEC-abonnement §4.1) — sans
 * Android : le `SyncViewModel` la branche sur le vrai service, le vrai transport et
 * `ProAccess` ; les tests JVM sur `LocalSyncService.forTesting`, un faux transport
 * et une fausse preuve.
 *
 *  - [linkPurchase] : déclencheurs `ProAccess` (abonnement, « Restaurer mes
 *    achats », relecture Play — dont un NOUVEL `orderId`, §4.1.2) et retour au
 *    premier plan ; il ne rend la main qu'APRÈS la réponse du serveur (ou tout de
 *    suite si rien n'est envoyé) : c'est ce qui permet à `ProAccess` d'acquitter
 *    l'achat APRÈS la preuve (§4.3) ;
 *  - [refreshProGrant] : relecture de licence (connexion, inscription, session
 *    restaurée, retour au premier plan, « Restaurer mes achats ») — ORDRE (1)
 *    envoi automatique si nécessaire → (2) `fetchProGrant` → (3) RÉPARATION
 *    (§4.1.3) : licence non Pro alors que cet abonnement ACTIF est mémorisé
 *    « linked » pour ce compte → UN envoi forcé par session, puis nouvelle lecture.
 *
 * Une lecture de licence EN ÉCHEC (`null`, §4.1.4) ne déclenche aucune réparation
 * et ne change rien (ni licence, ni cache) : [applyGrant] reçoit `null`.
 *
 * RÉPARATION INDÉPENDANTE DE L'ORDRE D'ARRIVÉE : elle exige DEUX faits — la
 * licence lue non Pro (Firestore) ET la preuve locale mémorisée « linked »
 * (Play). Au démarrage à froid, la session restaurée relit la licence AVANT que
 * Play ait rendu l'abonnement (`queryPurchasesAsync` est asynchrone) : la condition
 * est donc évaluée là où arrive CHACUN des deux faits — après la lecture de
 * licence ([refreshProGrant], avec la preuve du moment) ET à l'arrivée de la
 * preuve ([linkPurchase] automatique sauté car « linked », avec la dernière
 * licence lue pour ce compte dans la session, [lastGrant]). Toujours au plus
 * une fois par compte et par session ([claimRepair], atomique).
 *
 * Messages : seulement un refus DÉFINITIF après un geste EXPLICITE
 * ([showRefusal] avec le `code`) ; un succès efface le message ([showRefusal]
 * avec `null`) ; « retry » est silencieux. Garde de course : si le compte change
 * pendant l'envoi ou la lecture, le résultat n'est appliqué ni à la licence ni
 * au message. Un [Mutex] sérialise les envois : deux déclencheurs simultanés
 * (session restaurée + relecture Play) ne doublent pas la requête — le second
 * trouve l'issue « linked » et s'abstient.
 */
class PurchaseLinker(
    private val service: SyncService,
    private val transport: PurchaseLinkTransport,
    private val store: PurchaseLinkStore,
    /** Preuve de l'abonnement local VALIDE, `null` s'il n'y en a pas (`ProAccess.currentProof`). */
    private val proof: () -> PurchaseProof?,
    /** uid du compte connecté, `null` si déconnecté. */
    private val currentUid: () -> String?,
    /** Applique la licence relue pour ce compte (`ProAccess.applyAccountLicence`) ;
     *  `null` = lecture en échec (rien ne change). */
    private val applyGrant: (uid: String, grant: ProGrant?) -> Unit,
    /** Pose (code) ou efface (`null`) le message de refus (`ProAccess.setAccountLinkRefusal`). */
    private val showRefusal: (String?) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    /** Comptes déjà « réparés » pendant cette session de l'app (§4.1.3, au plus une fois). */
    private val repairedUids = HashSet<String>()
    /** Dernière licence LUE dans CETTE session (uid → licence) ; `null` tant
     *  qu'aucune lecture n'a abouti. Sert à évaluer la réparation quand la
     *  preuve Play arrive APRÈS la lecture de licence (démarrage à froid). */
    @Volatile private var lastGrant: Pair<String, ProGrant>? = null

    /** Dernière tentative en échec RÉESSAYABLE ? (retour au premier plan, §6.1). */
    fun lastOutcomeIsRetry(): Boolean = store.load()?.outcome == PurchaseLinkRecord.RETRY

    /**
     * Envoie la preuve si [PurchaseLink.shouldSend] le veut (ou [force] :
     * réparation) ; rend l'issue, `null` si rien n'a été envoyé (pas de compte,
     * pas de preuve, déjà réglé pour cette période, ou compte connecté ≠
     * [expectedUid] une fois le verrou obtenu). Mémorise l'issue (avec la clé de
     * période) et pose / efface le message.
     */
    suspend fun send(
        explicit: Boolean,
        force: Boolean = false,
        expectedUid: String? = null
    ): PurchaseLinkOutcome? = mutex.withLock {
        val uid = currentUid() ?: return null
        if (expectedUid != null && uid != expectedUid) return null
        val p = proof() ?: return null
        if (!PurchaseLink.shouldSend(p.purchaseId, p.periodKey, uid, explicit || force, store.load())) return null
        val outcome = post(p)
        store.save(PurchaseLinkRecord.of(p.purchaseId, uid, outcome, now(), p.periodKey))
        if (currentUid() == uid) {
            when (outcome) {
                PurchaseLinkOutcome.Linked -> showRefusal(null)
                is PurchaseLinkOutcome.Refused -> if (explicit) showRefusal(outcome.code)
                PurchaseLinkOutcome.Retry -> Unit
            }
        }
        outcome
    }

    /**
     * Appel HTTP (§6.1) : idToken en cache ; réponse 401 ⇒ UN nouvel essai avec
     * un idToken rafraîchi de force ; encore 401 ⇒ retry. Pas d'idToken (backend
     * LOCAL sans serveur, ou rafraîchissement impossible hors ligne) ⇒ rien
     * n'est posté, issue retry silencieuse. Toute erreur réseau ⇒ retry ; seule
     * l'annulation de la coroutine remonte.
     */
    private suspend fun post(p: PurchaseProof): PurchaseLinkOutcome {
        val token = service.currentIdToken(forceRefresh = false) ?: return PurchaseLinkOutcome.Retry
        var response = attempt(p, token) ?: return PurchaseLinkOutcome.Retry
        if (response.first == 401) {
            val fresh = service.currentIdToken(forceRefresh = true) ?: return PurchaseLinkOutcome.Retry
            response = attempt(p, fresh) ?: return PurchaseLinkOutcome.Retry
        }
        return PurchaseLink.outcome(response.first, response.second)
    }

    private suspend fun attempt(p: PurchaseProof, token: String): Pair<Int, String>? = try {
        transport.post(p.json, token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Déclencheur d'envoi ; si l'abonnement vient d'être rattaché (ou déclaré
     * remboursé), relit la licence. Envoi automatique SAUTÉ (période déjà
     * « linked » pour ce compte) alors que la dernière licence lue dans la session
     * pour ce compte n'est pas Pro : c'est la preuve qui arrive APRÈS la lecture
     * de licence (démarrage à froid) → la réparation du §4.1.3 est évaluée ICI.
     * Rend la main APRÈS la réponse du serveur.
     */
    suspend fun linkPurchase(explicit: Boolean): PurchaseLinkOutcome? {
        val uid = currentUid() ?: return null
        val outcome = send(explicit)
        if (outcome == PurchaseLinkOutcome.Linked || outcome == PurchaseLinkOutcome.Refused("revoked")) {
            val grant = readGrant(uid)
            if (currentUid() == uid) applyGrant(uid, grant)
        } else if (outcome == null) {
            val last = lastGrant
            if (last != null && last.first == uid && !last.second.isActive(now())) {
                val repaired = repairIfNeeded(uid)
                if (repaired != null && currentUid() == uid) applyGrant(uid, repaired)
            }
        }
        return outcome
    }

    /**
     * Relecture de la licence du compte, dans l'ordre du §6.1. Rend la licence
     * appliquée ; `null` si déconnecté, si la lecture a échoué (rien n'est alors
     * changé) ou si le compte a changé entre-temps (résultat ignoré). L'envoi ne
     * relance JAMAIS la relecture Play (pas de récursion avec `ProAccess.refresh`).
     */
    suspend fun refreshProGrant(): ProGrant? {
        val uid = currentUid() ?: return null
        send(explicit = false)
        var grant = readGrant(uid)
        if (grant != null && !grant.isActive(now())) grant = repairIfNeeded(uid) ?: grant
        if (currentUid() != uid) return null
        applyGrant(uid, grant)
        return grant
    }

    /** `fetchProGrant` + mémoire de la dernière licence LUE pour ce compte
     *  (seulement si la lecture a réussi et que le compte n'a pas changé). */
    private suspend fun readGrant(uid: String): ProGrant? {
        val grant = service.fetchProGrant()
        if (grant != null && currentUid() == uid) lastGrant = uid to grant
        return grant
    }

    /**
     * RÉPARATION (§4.1.3), appelée quand la licence lue pour [uid] n'est pas Pro :
     * si l'abonnement local ACTIF est mémorisé « linked » pour ce compte et que la
     * réparation n'a pas encore été faite dans la session → UN envoi forcé
     * (automatique : jamais de message), puis relecture. Rend la licence relue,
     * `null` si aucune réparation n'a eu lieu, si l'envoi n'a pas abouti à
     * « linked » ou si la relecture a échoué.
     */
    private suspend fun repairIfNeeded(uid: String): ProGrant? {
        if (!claimRepair(uid)) return null
        if (send(explicit = false, force = true, expectedUid = uid) != PurchaseLinkOutcome.Linked) return null
        return readGrant(uid)
    }

    /** Vérifie la condition de réparation ET marque le compte en une seule
     *  étape : deux déclencheurs concurrents (lecture de licence, arrivée de
     *  la preuve) ne réparent jamais deux fois. */
    @Synchronized
    private fun claimRepair(uid: String): Boolean {
        if (uid in repairedUids) return false
        val p = proof() ?: return false
        val r = store.load() ?: return false
        if (r.outcome != PurchaseLinkRecord.LINKED || r.uid != uid || r.purchaseId != p.purchaseId) return false
        repairedUids += uid
        return true
    }
}
