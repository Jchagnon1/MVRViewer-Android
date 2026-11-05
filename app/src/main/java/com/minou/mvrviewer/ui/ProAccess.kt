package com.minou.mvrviewer.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.minou.mvrviewer.AuthEmailLanguage
import com.minou.mvrviewer.R
import com.minou.mvrviewer.sync.PlayPurchaseSnapshot
import com.minou.mvrviewer.sync.ProGrant
import com.minou.mvrviewer.sync.PurchaseLink
import com.minou.mvrviewer.sync.PurchaseProof
import java.lang.ref.WeakReference

/**
 * ACCÈS « PRO » — parité avec `ProAccess.swift` iOS.
 *
 * UNE app : GRATUIT = tout LIRE ; PRO = tout ce qui ÉCRIT dans le projet.
 *
 * Déblocage par ABONNEMENT ANNUEL à renouvellement automatique (décision du
 * 2026-11-05, SPEC-abonnement.md : 25,99 € TTC par an, prix fixé dans la Play
 * Console seulement) : abonnement [PRO_SUBSCRIPTION_ID], plan de base
 * [PRO_BASE_PLAN_ID]. Il REMPLACE l'achat unique `com.minou.mvrviewer.pro`,
 * abandonné (jamais créé dans une console). À l'échéance : retour au mode
 * gratuit, sans perte de données — les gardes existantes ouvrent le paywall au
 * geste d'écriture suivant.
 *
 * L'état Pro est la fonction PURE [ProState.evaluate] (§2.4) : abonnement Play de
 * l'appareil OU licence datée du compte (`entitlements/{uid}`, écrite par la
 * fonction serveur `verifyPurchase` ou posée par l'administrateur) OU dernier état
 * connu, gardé 72 h hors ligne ([ProCacheRecord], préférence `pro.cache`). Le
 * déblocage de l'appareil ne dépend jamais du serveur.
 *
 * Achat reconnu partout : l'abonnement Play est rattaché au compte connecté
 * ([accountUid] → `obfuscatedAccountId`), sa preuve ([currentProof]) part par
 * [purchaseLinker] AVANT tout acquittement par l'app ([ProofThenAck], §4.3).
 *
 * RÈGLE D'INTERFACE : un utilisateur gratuit VOIT les commandes d'édition ; les
 * toucher ouvre le paywall via [gate] — jamais un bouton qui disparaît.
 *
 * Singleton `object`, comme [ToolbarLayoutStore] : l'état Pro est global à
 * l'appareil, et Compose l'observe par les `mutableStateOf`. Initialisé une
 * fois dans MainActivity.onCreate ; le [BillingClient] se reconnecte seul
 * (`enableAutoServiceReconnection`) et chaque relecture ([refresh], donc chaque
 * retour au premier plan) rétablit d'abord une connexion perdue.
 */
object ProAccess {
    /** Abonnement Pro (Play Console) — IDENTIQUE à [PurchaseLink.PRO_SUBSCRIPTION_ID]. */
    const val PRO_SUBSCRIPTION_ID = PurchaseLink.PRO_SUBSCRIPTION_ID
    /** Plan de base annuel. */
    const val PRO_BASE_PLAN_ID = PurchaseLink.PRO_BASE_PLAN_ID
    private const val PREFS = "mvrviewer.display"

    // MARK: - État Pro (§2.4)

    /** Abonnement de l'appareil tel que Play le rend (entrée `store` de
     *  [ProState]) ; null tant que Play n'a pas été lu. */
    var storeEntitlement by mutableStateOf<ProState.Store?>(null)
        private set
    /** Abonnement Pro de Google Play présent sur l'appareil (null : aucun). */
    var device by mutableStateOf<DeviceSubscription?>(null)
        private set
    /** Dernière licence LUE pour le compte connecté (null : déconnecté ou pas encore
     *  lue) — affichage (ligne d'état, double abonnement…), comme `lastGrant` sur le
     *  site. Une lecture en échec ne l'efface pas, mais rend la licence INCONNUE pour
     *  l'état Pro ([licenceUnknown]) — jamais `pro: false` sur une erreur. */
    var licence by mutableStateOf<ProGrant?>(null)
        private set
    /** La dernière lecture de la licence a ÉCHOUÉ : l'entrée `licence` de [ProState]
     *  vaut null (§2.4 : « lecture en échec » = inconnue) ; le cache daté prend le relais. */
    private var licenceUnknown by mutableStateOf(false)
    /** Instant de la dernière lecture SERVEUR réussie de la licence
     *  ([ProState.licenceReadStillOnline]). */
    private var licenceReadAt: Long? = null
    /** Compte auquel appartient [licence] (et la part `licence` du cache). */
    var licenceUid by mutableStateOf<String?>(null)
        private set
    /** Réponse RÉSEAU de Google Play (détails du produit) depuis le dernier
     *  passage au premier plan… */
    private var productAnswered by mutableStateOf(false)
    /** … et abonnements relus depuis : ensemble, `onlineStore`. */
    private var purchasesAnswered by mutableStateOf(false)
    /** Lecture SERVEUR réussie de la licence depuis le dernier passage au premier
     *  plan — et depuis la dernière lecture (une lecture en échec ou venue du cache
     *  Firestore la fait retomber, comme sur le site). */
    var onlineLicence by mutableStateOf(false)
        private set
    /** Dernier état Pro connu (§2.4), persisté dans `pro.cache`. */
    private var cacheRecord by mutableStateOf<ProCacheRecord?>(null)

    val onlineStore: Boolean get() = productAnswered && purchasesAnswered

    /** Vrai si Pro, évalué À L'INSTANT (une échéance passée app ouverte compte
     *  aussitôt). Forçage DEBUG/tests prioritaire. Propriété calculée sur des
     *  états Compose : l'observation fonctionne. */
    val isPro: Boolean get() = evaluation(clock()).pro
    /** Raison du dernier état (tests, journal). */
    val proReason: ProState.Reason get() = evaluation(clock()).reason
    /** Abonnement Play actif sur l'appareil. */
    val purchased: Boolean get() = storeEntitlement?.active == true
    /** Licence du compte active (sans échéance ou d'échéance future). */
    val grantedByAccount: Boolean get() = licence?.isActive(clock()) == true

    // MARK: - Boutique

    /** Prix ANNUEL formaté par Google Play (« 25,99 € ») — jamais codé en dur. */
    var price by mutableStateOf<String?>(null)
        private set
    /** Offre retenue du plan `annual` (`offerToken`, offre d'introduction). */
    var offer by mutableStateOf<ChosenOffer?>(null)
        private set
    /** Pays de la boutique (alpha-2, `getBillingConfigAsync`) : « TTC » seulement
     *  dans l'EEE, au Royaume-Uni et en Suisse (§5.2). */
    var storeCountry by mutableStateOf<String?>(null)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
    /** Achat en attente (paiement Play différé) : dit sous le bouton. */
    var pendingMessage by mutableStateOf<String?>(null)
        private set
    /** Issue de la dernière « Restaurer mes achats » quand elle n'a rien débloqué —
     *  affichée SEULEMENT dans l'écran où on l'a touché ([restoreMessageShown]). */
    var restoreMessage by mutableStateOf<String?>(null)
        private set
    private var restoreMessageScreen by mutableStateOf<RestoreScreen?>(null)

    /** Les deux écrans qui ont un bouton « Restaurer mes achats ». */
    enum class RestoreScreen { PAYWALL, ACCOUNT }

    /** Paywall demandé, avec ce que l'utilisateur essayait de faire. */
    var paywallReason by mutableStateOf<String?>(null)
        private set
    var showPaywall by mutableStateOf(false)

    private var appContext: Context? = null
    private var client: BillingClient? = null
    private var productDetails: ProductDetails? = null
    /** Activité de lancement : messages de facturation de Google Play (une fois). */
    private var inAppMessagesActivity: WeakReference<Activity>? = null
    /** Crochet de test : `adb shell am start … --ez MVR_PRO true|false`
     *  (DEBUG uniquement, cf. MainActivity). Ne touche JAMAIS au cache. */
    private var debugOverride by mutableStateOf<Boolean?>(null)
    /** Crochet DEBUG `MVR_SUBSCRIBE_STATE` (signIn|verifyEmail|ready|otherStore) :
     *  force ce que le bouton du paywall exige (captures, sans Firebase). */
    private var debugSubscribeState: String? = null
    /** Crochet DEBUG `MVR_PAYWALL_PRICE` : prix montré tant que Google Play ne
     *  répond pas (émulateur sans produit). Il vient de l'intent, jamais du code. */
    var debugPaywallPrice by mutableStateOf<String?>(null)
        private set
    /** Textes (ressources de l'app) ; remplaçable par les tests JVM. */
    internal var texts: ProTexts? = null
    /** Résout un texte localisé (`R.string`) — Context de l'app ; remplaçable
     *  par les tests JVM (pas de Context). */
    internal var messageResolver: (Int) -> String? = { id -> appContext?.getString(id) }

    // MARK: - Compte (SPEC-abonnement §4.1.6)

    /** Branché par le SyncViewModel : relit la licence du compte puis appelle `done`
     *  (tout de suite si déconnecté). Appelé par [restore] et au premier plan. */
    var grantRefresher: ((done: () -> Unit) -> Unit)? = null
    /** uid du compte connecté (posé par le SyncViewModel), SEULEMENT sur un backend
     *  qui fait vérifier les achats (démo locale : null — son uid n'existera jamais
     *  côté serveur). Connu : le flux d'achat porte `setObfuscatedAccountId`. */
    var accountUid: String? = null
    /** Le backend fait-il vérifier les achats (`verifyPurchase`) ? Seulement alors un
     *  compte (et une adresse vérifiée) est exigé pour s'abonner, et la preuve part
     *  avant l'acquittement. */
    var verifiesPurchases by mutableStateOf(false)
    /** Adresse e-mail du compte connecté vérifiée ? (null : inconnu) */
    var emailVerified by mutableStateOf<Boolean?>(null)
    /** Branché par le SyncViewModel : envoie la preuve d'abonnement au serveur ;
     *  `explicit` = geste de l'utilisateur (s'abonner, « Restaurer mes achats ») :
     *  envoi même si déjà fait, et message en cas de refus définitif. `done` est
     *  appelé APRÈS la réponse du serveur, quelle qu'elle soit, ou tout de suite si
     *  rien n'est envoyé — c'est après lui que l'app acquitte ([ProofThenAck]). */
    var purchaseLinker: ((explicit: Boolean, done: () -> Unit) -> Unit)? = null
    /** Refus DÉFINITIF du serveur après un geste explicite — affiché dans le bloc
     *  Pro de l'écran compte. Effacé par un rattachement réussi et à la
     *  déconnexion. Jamais de texte pour un succès (`inactive` compris) ni pour un
     *  échec réessayable. */
    var accountLinkMessage by mutableStateOf<String?>(null)
        private set
    /** Le MÊME refus, à montrer TOUT DE SUITE là où se trouve l'utilisateur
     *  (alerte posée à la racine, `ProLinkRefusalHost`) quand le bloc Pro de
     *  l'écran compte n'est pas à l'écran : le paywall est DÉJÀ FERMÉ quand le
     *  serveur répond (l'abonnement Play débloque l'appareil d'abord). */
    var linkRefusalAlert by mutableStateOf<String?>(null)
        private set
    /** Blocs Pro de l'écran compte actuellement composés. */
    private val accountSectionsShown = java.util.concurrent.atomic.AtomicInteger(0)
    /** Abonnement Pro PURCHASED lu en dernier chez Play (jamais un PENDING) — la
     *  preuve envoyée au serveur. Non persisté : Play le rend à chaque relecture. */
    @Volatile private var lastProof: PlayPurchaseSnapshot? = null
    /** Ordre « preuve puis acquittement » (§4.3) ; Play remplaçable par les tests. */
    internal var playGateway: PlayAckGateway = object : PlayAckGateway {
        override fun queryPurchases(then: (List<PlayPurchaseSnapshot>?) -> Unit) {
            val c = client ?: return then(null)
            c.queryPurchasesAsync(subsQuery()) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val snapshots = snapshotsOf(purchases)
                    applyStoreState(snapshots)
                    then(snapshots)
                } else then(null)
            }
        }

        override fun acknowledge(purchaseToken: String, then: (ok: Boolean) -> Unit) {
            val c = client ?: return then(false)
            c.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
            ) { result -> then(result.responseCode == BillingClient.BillingResponseCode.OK) }
        }
    }
    /** Minuteurs (échéance de la licence, attente du compte) : fil principal dans
     *  l'app ([init]) ; null dans les tests JVM qui n'en posent pas (pas de Looper). */
    internal var timers: ProTimers? = null
    /** Horloge (ms depuis 1970) ; remplaçable par les tests JVM. */
    internal var clock: () -> Long = { System.currentTimeMillis() }
    /** Annule le minuteur d'échéance de la licence armé ([armLicenceDeadline]). */
    private var licenceDeadlineCancel: (() -> Unit)? = null

    // MARK: - Compte de la session (§4.3, point 1)

    /** Délai maximal d'attente du compte avant d'acquitter quand même (filet : la
     *  reprise de session Firebase est locale, elle répond bien avant). */
    const val ACCOUNT_WAIT_MAX_MS: Long = 30_000L
    /** Le compte de la session est CONNU (reprise de session terminée, connecté ou
     *  non) : posé par le SyncViewModel ([markAccountResolved]). Avant, un abonnement
     *  non acquitté ATTEND — au démarrage à froid, Google Play peut répondre avant la
     *  reprise de la session Firebase, et l'app l'acquitterait « sans compte » alors
     *  que la preuve doit partir d'abord. */
    private var accountResolved = false
    /** Séquence preuve/acquittement en attente du compte (jetons du dernier relevé). */
    private var pendingAck: PendingAck? = null
    private var accountWaitCancel: (() -> Unit)? = null

    private class PendingAck(val tokens: List<String>, val explicit: Boolean, val finished: List<() -> Unit>)

    /** TESTS UNITAIRES (JVM, sans Activity ni Play) : force l'état ; `null` rend la
     *  main aux états réels. Les tests qui exercent une MUTATION gardée doivent se
     *  déclarer Pro, sinon la garde refuse — c'est ce qu'elle doit faire. */
    fun forceForTests(pro: Boolean?) {
        debugOverride = pro
    }

    /** TESTS : repart d'un état neutre (ni boutique lue, ni licence, ni cache). */
    internal fun resetForTests() {
        licenceDeadlineCancel?.invoke()
        licenceDeadlineCancel = null
        accountWaitCancel?.invoke()
        accountWaitCancel = null
        timers = null
        clock = { System.currentTimeMillis() }
        accountResolved = false
        pendingAck = null
        storeEntitlement = null
        device = null
        licence = null
        licenceUnknown = false
        licenceReadAt = null
        licenceUid = null
        productAnswered = false
        purchasesAnswered = false
        onlineLicence = false
        cacheRecord = null
        lastProof = null
        price = null
        offer = null
        storeCountry = null
        pendingMessage = null
        restoreMessage = null
        restoreMessageScreen = null
        lastError = null
        emailVerified = null
        verifiesPurchases = false
        accountUid = null
        debugSubscribeState = null
        debugPaywallPrice = null
        isBusy = false
    }

    fun init(ctx: Context, debugOverride: Boolean? = null) {
        if (appContext != null) return
        val app = ctx.applicationContext
        appContext = app
        texts = ContextProTexts(app)
        timers = MainLooperTimers
        this.debugOverride = debugOverride
        (ctx as? Activity)?.let { inAppMessagesActivity = WeakReference(it) }
        val p = prefs(app)
        // Anciennes clés (achat unique) effacées ; leur valeur n'est pas reprise.
        val legacy = ProCacheRecord.LEGACY_KEYS.filter { p.contains(it) }
        if (legacy.isNotEmpty()) p.edit().apply { legacy.forEach { remove(it) } }.apply()
        // Lancement : le cache SEUL décide, sans clignotement (§4.1.5).
        cacheRecord = ProCacheRecord.fromJson(p.getString(ProCacheRecord.KEY, null))
        if (debugOverride != null) return
        connect()
    }

    /** Crochets DEBUG de l'écran Pro (captures sans Google Play ni Firebase). */
    fun setDebugPaywall(price: String?, subscribeState: String?, storeCountry: String?) {
        debugPaywallPrice = price?.takeIf { it.isNotBlank() }
        debugSubscribeState = subscribeState?.takeIf { it.isNotBlank() }
        storeCountry?.takeIf { it.isNotBlank() }?.let { this.storeCountry = it }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // MARK: - Évaluation

    /** Entrée `licence` de [ProState] : null si la dernière lecture a échoué. */
    private fun stateLicence(): ProState.Licence? =
        if (licenceUnknown) null else licence?.let { ProState.Licence(it.pro, it.expires) }

    private fun evaluation(now: Long): ProState.Output {
        val stateLicence = stateLicence()
        return ProState.evaluate(ProState.Input(
            now = now,
            override = debugOverride,
            store = storeEntitlement,
            licence = stateLicence,
            onlineStore = onlineStore,
            onlineLicence = ProState.licenceReadStillOnline(onlineLicence, stateLicence, licenceReadAt, now),
            cache = cacheRecord?.cache
        ))
    }

    /** Réévalue l'état Pro (après chaque relecture de Play ou de la licence, et au
     *  premier plan) et mémorise le cache — jamais sous `MVR_PRO`. Ferme le paywall
     *  si le Pro est là. Réarme le minuteur d'échéance de la licence. */
    private fun reevaluate() {
        val output = evaluation(clock())
        if (debugOverride == null) {
            val record = ProCacheRecord(cacheRecord?.uid ?: licenceUid, output.cache)
            if (record != cacheRecord) saveCache(record)
        }
        armLicenceDeadline()
        if (output.pro) showPaywall = false
    }

    // MARK: - Échéance de la licence, app ouverte (§4.1.5, comme `onDeadline` du site)

    /** (Ré)arme le minuteur sur l'échéance de la licence lue, plafonné à 24 h — jamais
     *  sous `MVR_PRO` ni sans minuteurs (tests JVM). */
    private fun armLicenceDeadline() {
        licenceDeadlineCancel?.invoke()
        licenceDeadlineCancel = null
        val t = timers ?: return
        if (debugOverride != null) return
        val delay = ProState.licenceDeadlineDelay(stateLicence(), clock()) ?: return
        licenceDeadlineCancel = t.schedule(delay) {
            licenceDeadlineCancel = null
            licenceDeadlineReached()
        }
    }

    /**
     * Minuteur échu. Échéance de la licence passée : comme au retour au premier plan,
     * [onlineLicence] retombe AVANT toute évaluation (le Pro reste, par la part
     * licence du cache, jusqu'à 72 h), puis la licence est relue ([grantRefresher]) —
     * le serveur tranche (renouvelée → Pro sans interruption ; terminée → plus de
     * Pro). Plafond de 24 h atteint : réarmé.
     */
    private fun licenceDeadlineReached() {
        val expires = stateLicence()?.takeIf { it.pro }?.expires
        if (expires == null || expires > clock()) {
            armLicenceDeadline()
            return
        }
        onlineLicence = false
        reevaluate()
        refreshLicence { }
    }

    private fun saveCache(record: ProCacheRecord) {
        cacheRecord = record
        appContext?.let { prefs(it).edit().putString(ProCacheRecord.KEY, record.toJson()).apply() }
    }

    /** Cache mémorisé (tests). */
    internal fun cacheForTests(): ProCacheRecord? = cacheRecord

    // MARK: - Garde

    /**
     * Exécute `action` si Pro ; sinon ouvre le paywall et rend `false`.
     * `reason` : phrase courte au nominatif (« modifier le patch »).
     */
    fun gate(reason: String, action: () -> Unit): Boolean {
        if (isPro) {
            action()
            return true
        }
        openPaywall(reason)
        return false
    }

    /** Ouvre le paywall ; une restauration ou un achat en attente passés ne
     *  disent plus rien de l'état présent. */
    fun openPaywall(reason: String? = null) {
        if (!showPaywall) {
            clearRestoreMessage()
            pendingMessage = null
        }
        paywallReason = reason
        showPaywall = true
    }

    /** Anti-rafale partagé par tous les réglages gardés de l'app (iOS `refusalThrottle`). */
    val refusalThrottle = ProRefusalThrottle()

    /**
     * RÉGLAGE Pro (curseur, interrupteur, champ) — SPEC-menus-android §1.2 / §4.5 :
     * écrit ([set]) si Pro ; pour un gratuit, la valeur ne bouge pas et le PREMIER
     * changement d'une rafale ouvre l'écran Pro (les suivants, dans les 0,8 s
     * glissantes, sont muets : un glissé n'ouvre jamais deux écrans).
     * @return vrai si la valeur a été écrite.
     */
    fun gatedSetter(reason: String, set: () -> Unit): Boolean =
        ProGate.setter(reason, isPro, refusalThrottle, System.nanoTime() / 1_000_000L,
            refuse = { r -> openPaywall(r) }, set = set)

    // MARK: - Licence du compte (§4.1.4)

    /** Compte connecté (ou restauré) : la part `licence` du cache n'appartient qu'à
     *  SON compte — celle d'un autre compte est oubliée. */
    fun bindAccount(uid: String) {
        if (licenceUid != uid) {
            licence = null
            licenceUnknown = false
            licenceReadAt = null
            onlineLicence = false
        }
        licenceUid = uid
        if (cacheRecord?.uid != uid) {
            val record = ProCacheRecord(uid, cacheRecord?.store, null)
            if (debugOverride == null) saveCache(record) else cacheRecord = record
        }
        reevaluate()
    }

    /** Licence lue pour le compte [uid] (§4.1.4). `null` = lecture EN ÉCHEC : licence
     *  INCONNUE pour l'état Pro (jamais `pro: false`) — la part licence du cache n'est
     *  pas effacée, la dernière licence lue reste affichée. Une lecture en échec ou
     *  venue du cache Firestore (`fromServer` faux) fait retomber [onlineLicence],
     *  comme sur le site (§4.1.5). Ferme le paywall si le Pro est là. */
    fun applyAccountLicence(grant: ProGrant?, uid: String) {
        if (licenceUid != uid) bindAccount(uid)
        if (grant == null) {
            licenceUnknown = true
            onlineLicence = false
            reevaluate()
            return
        }
        licence = grant
        licenceUnknown = false
        onlineLicence = grant.fromServer
        if (grant.fromServer) licenceReadAt = clock()
        reevaluate()
    }

    /** Déconnexion (ou session perdue) : la licence du compte et sa part du cache
     *  sont oubliées. */
    fun clearAccountLicence() {
        licence = null
        licenceUnknown = false
        licenceReadAt = null
        licenceUid = null
        onlineLicence = false
        emailVerified = null
        val record = ProCacheRecord(null, cacheRecord?.store, null)
        if (record != cacheRecord) {
            if (debugOverride == null) saveCache(record) else cacheRecord = record
        }
        reevaluate()
    }

    /** Raccourci (tests) : licence lue au serveur, admin sans échéance (`true`) ou
     *  absente (`false`). Nul-sûr sans [init]. */
    fun setAccountGrant(granted: Boolean) {
        licence = ProGrant(pro = granted, fromServer = true)
        licenceUnknown = false
        licenceReadAt = clock()
        onlineLicence = true
        reevaluate()
    }

    // MARK: - Preuve d'abonnement (SPEC-abonnement §3.2, §4.1.1)

    /** Preuve de l'abonnement Play VALIDE (PURCHASED), `null` s'il n'y en a pas.
     *  `periodKey` = `orderId` : chaque renouvellement repart une fois. */
    fun currentProof(): PurchaseProof? = lastProof?.let {
        PurchaseLink.proofFor(it.purchaseToken, it.orderId, proofLanguage())
    }

    private fun proofLanguage(): String = appContext?.let { AuthEmailLanguage.current(it) } ?: "fr"

    /** Retient l'abonnement de la preuve parmi les achats lus chez Play ; un achat
     *  EN ATTENTE n'en produit jamais. */
    internal fun rememberPurchases(snapshots: List<PlayPurchaseSnapshot>) {
        lastProof = PurchaseLink.selectProof(snapshots)
    }

    /** `obfuscatedAccountId` passé au flux d'achat : hachage de [accountUid]
     *  (64 caractères hexadécimaux, sans donnée personnelle), `null` si déconnecté. */
    internal fun purchaseAccountId(): String? = accountUid?.let { PurchaseLink.obfuscatedAccountId(it) }

    /** Pose (refus définitif, `code` du contrat serveur) ou efface (`null`) le
     *  message de rattachement — dans le bloc Pro de l'écran compte et, si ce bloc
     *  n'est pas à l'écran, en alerte immédiate. */
    fun setAccountLinkRefusal(code: String?) {
        val message = code?.let { messageResolver(PurchaseLink.refusalMessageRes(it)) }
        accountLinkMessage = message
        linkRefusalAlert = if (message != null && accountSectionsShown.get() == 0) message else null
    }

    /** L'utilisateur ferme l'alerte ; le message reste dans l'écran compte. */
    fun dismissLinkRefusalAlert() {
        linkRefusalAlert = null
    }

    /** `ProAccountSection` entre (`true`) ou sort (`false`) de l'écran. */
    fun accountSectionShown(visible: Boolean) {
        if (visible) accountSectionsShown.incrementAndGet()
        else accountSectionsShown.updateAndGet { maxOf(0, it - 1) }
    }

    /** Déconnexion : plus de compte pour le flux d'achat, message et alerte
     *  effacés. La mémoire de la dernière issue (`pro.link`, indexée par uid)
     *  est CONSERVÉE. */
    fun clearAccount() {
        accountUid = null
        accountLinkMessage = null
        linkRefusalAlert = null
    }

    /**
     * Le compte de la session est connu (SyncViewModel : reprise de session terminée,
     * connexion, inscription, déconnexion) — [accountUid] et [verifiesPurchases] sont
     * posés. Lance la séquence preuve/acquittement restée en attente ([applyPurchases]) :
     * compte connecté → la preuve part AVANT l'acquittement (§4.3 point 1, recette
     * R8) ; sinon acquittement local. Idempotent.
     */
    fun markAccountResolved() {
        accountWaitCancel?.invoke()
        accountWaitCancel = null
        accountResolved = true
        val pending = pendingAck ?: return
        pendingAck = null
        runProofThenAck(pending.tokens, pending.explicit,
            finished = if (pending.finished.isEmpty()) null else ({ pending.finished.forEach { it() } }))
    }

    // MARK: - Souscrire (§4.1.6)

    /** Ce qu'exige le bouton du paywall. */
    val subscribeRequirement: SubscribeRequirement
        get() {
            when (debugSubscribeState) {
                "signIn" -> return SubscribeRequirement.SIGN_IN
                "verifyEmail" -> return SubscribeRequirement.VERIFY_EMAIL
                "ready" -> return SubscribeRequirement.READY
            }
            return SubscriptionRules.subscribeRequirement(
                requiresAccount = SubscriptionRules.SUBSCRIBE_REQUIRES_ACCOUNT,
                requiresVerifiedEmail = SubscriptionRules.SUBSCRIBE_REQUIRES_VERIFIED_EMAIL,
                verifiesPurchases = verifiesPurchases,
                signedIn = licenceUid != null,
                emailVerified = emailVerified
            )
        }

    /** Abonné par l'AUTRE boutique (§5.6) : échéance, sinon null. */
    val otherStoreSubscriptionEnd: Long?
        get() {
            if (debugSubscribeState == "otherStore") return clock() + 200L * 86_400_000L
            return SubscriptionRules.otherStoreSubscriptionEnd(licence, clock())
        }

    /** Ligne d'état de la section Pro de Compte & réglages (§5.5). */
    val statusLine: ProStatusLine
        get() = SubscriptionRules.statusLine(device, licence, isPro, clock())

    /** Un abonnement Play existe sur l'appareil (« Gérer » du paywall, « Demander un
     *  remboursement », lien de gestion précis). */
    val hasDeviceSubscription: Boolean get() = device != null

    /** Abonné (ou ancien abonné) : sur l'appareil ou d'après la licence. */
    val isSubscriber: Boolean get() = device != null || licence?.appstore != null || licence?.play != null

    /** Abonnement à renouvellement en cours (avertissement de suppression, §5.7). */
    val hasRenewingSubscription: Boolean
        get() = SubscriptionRules.hasRenewingSubscription(device, licence)

    /** « Gérer ou résilier mon abonnement » (§4.3). */
    val manageUrl: String get() = SubscriptionRules.manageUrl(hasDeviceSubscription)

    // MARK: - Play Billing

    private fun subsQuery() = QueryPurchasesParams.newBuilder()
        .setProductType(BillingClient.ProductType.SUBS)
        .build()

    private fun connect() {
        val app = appContext ?: return
        val c = BillingClient.newBuilder(app)
            .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            // Billing 8 : une connexion perdue (mise à jour du Play Store…) est
            // rétablie par la bibliothèque à l'appel suivant.
            .enableAutoServiceReconnection()
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    showInAppMessagesOnce()
                    // Relecture au lancement : la preuve d'un abonnement déjà
                    // possédé part AUTOMATIQUEMENT (sautée si cette période est déjà
                    // rattachée à ce compte — sauf RÉPARATION, §4.1.3).
                    refresh()
                }
            }
            // Pas de boucle de reconnexion : la prochaine relecture ([refresh], au
            // plus tard au retour au premier plan) ou action ([purchase],
            // [restore]) rétablit la connexion AVANT de lire.
            override fun onBillingServiceDisconnected() = billingDisconnected()
        })
    }

    /** Service de facturation perdu : les abonnements devront être RELUS avant de
     *  compter comme une réponse de Google Play (`onlineStore`, §2.4). */
    private fun billingDisconnected() {
        purchasesAnswered = false
    }

    private fun ensureConnected(onFailure: (() -> Unit)? = null, then: () -> Unit) {
        val c = client
        if (c == null) {
            connect()
            onFailure?.invoke()
            return
        }
        if (c.isReady) { then(); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) then()
                else if (onFailure != null) onFailure()
                else lastError = result.debugMessage.ifEmpty { null } ?: messageResolver(R.string.pro_error_play_unavailable)
            }
            override fun onBillingServiceDisconnected() = billingDisconnected()
        })
    }

    /** Messages de facturation de Google Play (moyen de paiement refusé…) : au
     *  lancement, catégorie TRANSACTIONAL (recommandé, §4.3). */
    private fun showInAppMessagesOnce() {
        val activity = inAppMessagesActivity?.get() ?: return
        inAppMessagesActivity = null
        val params = InAppMessageParams.newBuilder()
            .addInAppMessageCategoryToShow(InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL)
            .build()
        runCatching {
            client?.showInAppMessages(activity, params) { result ->
                if (result.responseCode == InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED) refresh()
            }
        }
    }

    private fun queryProduct() {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRO_SUBSCRIPTION_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            ))
            .build()
        // Play Billing 8 : un QueryProductDetailsResult — produits trouvés dans
        // `productDetailsList`, ceux que Play n'a pas pu servir dans
        // `unfetchedProductList` (non publié, région…) : `productDetails` reste nul.
        c.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                // Une réponse RÉSEAU de Google Play depuis le premier plan (§2.4).
                productAnswered = true
                val pd = details.productDetailsList.firstOrNull { it.productId == PRO_SUBSCRIPTION_ID }
                productDetails = pd
                val chosen = SubscriptionOffers.choose(pd?.subscriptionOfferDetails.orEmpty().map { o ->
                    PlayOffer(
                        basePlanId = o.basePlanId,
                        offerId = o.offerId,
                        offerToken = o.offerToken,
                        phases = o.pricingPhases.pricingPhaseList.map { ph ->
                            PlayPricingPhase(ph.priceAmountMicros, ph.formattedPrice, ph.billingPeriod,
                                ph.recurrenceMode, ph.billingCycleCount)
                        }
                    )
                })
                offer = chosen
                // Prix ANNUEL formaté par Google Play, jamais codé en dur.
                price = chosen?.price
                reevaluate()
            }
        }
    }

    /** Pays de la boutique (alpha-2) pour la mention « TTC » (§5.2). */
    private fun queryStoreCountry() {
        val c = client ?: return
        runCatching {
            c.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { result, config ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    config?.countryCode?.takeIf { it.isNotBlank() }?.let { storeCountry = it }
                }
            }
        }
    }

    /** Retour au premier plan (§4.1.2) : Google Play ET la licence doivent
     *  répondre à nouveau pour effacer leur part du cache ; Play est relu ici (la
     *  connexion perdue d'abord rétablie, [refresh]), la licence par le
     *  SyncViewModel (`onForeground`). */
    fun onForeground() {
        productAnswered = false
        purchasesAnswered = false
        onlineLicence = false
        refresh()
    }

    /**
     * Relit le produit, le pays et les abonnements chez Google Play et met l'état
     * à jour ; la preuve part ensuite (automatique, ou [explicit]) AVANT tout
     * acquittement par l'app. [finished] : `true` une fois la relecture appliquée
     * et la séquence preuve/acquittement terminée ; `false` si Play n'a pas répondu.
     * Service déconnecté (Play Store mis à jour, connexion du lancement en échec) :
     * la connexion est RÉTABLIE avant de relire — sinon un abonnement expiré ou
     * remboursé resterait Pro tant que le processus vit (§4.1.2).
     */
    fun refresh(explicit: Boolean = false, finished: ((ok: Boolean) -> Unit)? = null) {
        if (debugOverride != null) { finished?.invoke(true); return }
        if (client == null) { finished?.invoke(false); return }
        ensureConnected(onFailure = { finished?.invoke(false) }) {
            val c = client ?: run { finished?.invoke(false); return@ensureConnected }
            queryProduct()
            queryStoreCountry()
            c.queryPurchasesAsync(subsQuery()) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    applyPurchases(snapshotsOf(purchases), explicit) { finished?.invoke(true) }
                } else finished?.invoke(false)
            }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        // Flux lancé par [purchase] (isBusy) = geste EXPLICITE ; sinon (achat en
        // attente confirmé plus tard, achat fait hors de l'app) = AUTOMATIQUE.
        val initiated = isBusy
        isBusy = false
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> applyPurchases(snapshotsOf(purchases.orEmpty()), initiated)
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh(explicit = true)
            else -> lastError = result.debugMessage.ifEmpty { null } ?: messageResolver(R.string.pro_error_purchase_failed)
        }
    }

    private fun snapshotsOf(purchases: List<Purchase>): List<PlayPurchaseSnapshot> = purchases.map {
        PlayPurchaseSnapshot(
            products = it.products,
            purchased = it.purchaseState == Purchase.PurchaseState.PURCHASED,
            purchaseToken = it.purchaseToken,
            acknowledged = it.isAcknowledged,
            autoRenewing = it.isAutoRenewing,
            orderId = it.orderId
        )
    }

    /** Applique les abonnements lus chez Play SANS rien envoyer ni acquitter :
     *  état de l'appareil, preuve retenue, achat en attente, cache. Un abonnement
     *  valide FERME le paywall (l'appareil est débloqué sans attendre le serveur). */
    internal fun applyStoreState(snapshots: List<PlayPurchaseSnapshot>) {
        rememberPurchases(snapshots)
        val proof = lastProof
        storeEntitlement = ProState.Store(active = proof != null, until = null)
        device = proof?.let { DeviceSubscription(it.purchaseToken, it.orderId, it.autoRenewing, it.acknowledged) }
        purchasesAnswered = true
        if (proof != null) pendingMessage = null
        else if (snapshots.any { it.pending }) pendingMessage = texts?.string(R.string.pro_pending)
        reevaluate()
    }

    /**
     * Abonnements lus chez Play : état appliqué TOUT DE SUITE ([applyStoreState]),
     * puis ORDRE « preuve PUIS acquittement » ([ProofThenAck], §4.3) pour un
     * abonnement non acquitté ; sinon la preuve part simplement. Abonnement non
     * acquitté lu AVANT que le compte de la session soit connu (démarrage à froid :
     * Play répond avant la reprise de la session) : la séquence attend
     * [markAccountResolved] — au plus [ACCOUNT_WAIT_MAX_MS], le délai de 3 jours
     * de Google laisse toute la marge. [finished] : appelé une fois tout terminé.
     * Nul-sûr sans [init] (tests JVM).
     */
    internal fun applyPurchases(
        snapshots: List<PlayPurchaseSnapshot>, explicit: Boolean, finished: (() -> Unit)? = null
    ) {
        applyStoreState(snapshots)
        val unacknowledged = snapshots
            .filter { it.isProSubscription && it.purchased && !it.acknowledged && it.purchaseToken.isNotEmpty() }
            .map { it.purchaseToken }
        if (unacknowledged.isNotEmpty() && !accountResolved) {
            val previous = pendingAck
            pendingAck = PendingAck(unacknowledged, explicit || previous?.explicit == true,
                previous?.finished.orEmpty() + listOfNotNull(finished))
            if (accountWaitCancel == null) {
                accountWaitCancel = timers?.schedule(ACCOUNT_WAIT_MAX_MS) {
                    accountWaitCancel = null
                    markAccountResolved()
                }
            }
            return
        }
        runProofThenAck(unacknowledged, explicit, finished)
    }

    private fun runProofThenAck(unacknowledged: List<String>, explicit: Boolean, finished: (() -> Unit)?) {
        val linker = purchaseLinker
        ProofThenAck(playGateway).run(
            unacknowledged = unacknowledged,
            proofCanLeave = linker != null && accountUid != null && verifiesPurchases,
            sendProof = { done -> linker?.invoke(explicit, done) ?: done() },
            finished = finished
        )
    }

    fun purchase(activity: Activity) {
        if (debugOverride != null) return
        lastError = null
        pendingMessage = null
        clearRestoreMessage()
        if (subscribeRequirement != SubscribeRequirement.READY || otherStoreSubscriptionEnd != null) return
        ensureConnected {
            val pd = productDetails
            val token = offer?.offerToken
            if (pd == null || token == null) {
                queryProduct()
                lastError = messageResolver(R.string.pro_error_product_unavailable)
                return@ensureConnected
            }
            val builder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd)
                        // Abonnement : l'offre (plan `annual`) est OBLIGATOIRE.
                        .setOfferToken(token)
                        .build()
                ))
            // Connecté : l'abonnement porte le hachage de l'uid (relu par le serveur
            // dans `obfuscatedExternalAccountId`) → il ne pourra être rattaché
            // qu'à CE compte (SPEC-purchase §3.3/§3.4).
            purchaseAccountId()?.let { builder.setObfuscatedAccountId(it) }
            isBusy = true
            val r = client?.launchBillingFlow(activity, builder.build())
            if (r == null || r.responseCode != BillingClient.BillingResponseCode.OK) {
                isBusy = false
                lastError = r?.debugMessage?.ifEmpty { null } ?: messageResolver(R.string.pro_error_play_unavailable)
            }
        }
    }

    // MARK: - Restaurer (§5.8)

    /**
     * « Restaurer mes achats » : les abonnements Google Play suivent le compte
     * Google, il suffit de les relire (la preuve repart EXPLICITEMENT, même si déjà
     * rattachée) — puis la licence du compte ([grantRefresher]). Sans Pro retrouvé,
     * un message dit pourquoi, dans l'écran [from] et là seulement.
     */
    fun restore(from: RestoreScreen) {
        lastError = null
        pendingMessage = null
        clearRestoreMessage()
        isBusy = true
        val afterPlay: (Boolean) -> Unit = { playOk ->
            refreshLicence { finishRestore(from, playFailed = !playOk) }
        }
        if (debugOverride != null || client == null) {
            afterPlay(debugOverride != null)
            return
        }
        ensureConnected(onFailure = { afterPlay(false) }) { refresh(explicit = true) { ok -> afterPlay(ok) } }
    }

    private fun refreshLicence(done: () -> Unit) {
        val refresher = grantRefresher
        if (refresher == null) done() else refresher(done)
    }

    private fun finishRestore(from: RestoreScreen, playFailed: Boolean) {
        isBusy = false
        val now = clock()
        val outcome = SubscriptionRules.restoreOutcome(isPro, playFailed, SubscriptionRules.expiredPlayEnd(licence, now))
        val message = texts?.let { SubscriptionTexts.restore(outcome, it) }
        restoreMessage = message
        restoreMessageScreen = if (message == null) null else from
        if (isPro) showPaywall = false
    }

    /** Message de restauration à afficher dans [screen] (null : rien) — jamais une
     *  fois le Pro actif. */
    fun restoreMessageShown(screen: RestoreScreen): String? =
        restoreMessage?.takeIf { !isPro && restoreMessageScreen == screen }

    /** Ouverture de Compte & réglages : on repart sans message de restauration. */
    fun accountScreenDidOpen() {
        clearRestoreMessage()
    }

    private fun clearRestoreMessage() {
        if (restoreMessage != null) restoreMessage = null
        if (restoreMessageScreen != null) restoreMessageScreen = null
    }
}

/** Minuteurs de [ProAccess] (échéance de la licence, attente du compte) — abstraits
 *  pour les tests JVM (pas de Looper). */
internal interface ProTimers {
    /** Programme [action] sur le fil principal dans [delayMs] ; rend son annulation. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/** Minuteurs de l'app : `Handler` du fil principal (l'état Pro est un état Compose). */
internal object MainLooperTimers : ProTimers {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}
