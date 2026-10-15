package com.minou.mvrviewer.ui

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.minou.mvrviewer.sync.PlayPurchaseSnapshot
import com.minou.mvrviewer.sync.PurchaseLink
import com.minou.mvrviewer.sync.PurchaseProof
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ACCÈS « PRO » — parité exacte avec `ProAccess.swift` iOS.
 *
 * UNE app : GRATUIT = tout LIRE ; PRO = tout ce qui ÉCRIT dans le projet.
 * Deux chemins de déblocage, [isPro] = l'un OU l'autre :
 *  - ACHAT UNIQUE ([purchased] : produit in-app Play Billing, MÊME identifiant
 *    que l'App Store) ;
 *  - LICENCE PAR COMPTE ([grantedByAccount]) : posée par l'ADMINISTRATEUR dans
 *    la console Firebase (`entitlements/{uid}`, cf. `SyncService.fetchProGrant`),
 *    JAMAIS par l'utilisateur ; relue par le SyncViewModel à chaque connexion et
 *    par « Restaurer un achat » ([restore] → [grantRefresher]) ; retirée à la
 *    déconnexion. Le paywall n'en parle pas — c'est le canal du propriétaire et
 *    de ses testeurs tant que le produit n'est pas en ligne.
 *    Elle est AUSSI écrite par la fonction serveur `verifyPurchase` quand une
 *    app lui envoie la preuve d'un achat vérifié (SPEC-purchase.md) : l'achat
 *    Play est rattaché au compte connecté ([accountUid] → `obfuscatedAccountId`
 *    au lancement du flux), sa preuve ([currentProof]) part par
 *    [purchaseLinker] — et l'achat compte alors sur iPhone et sur le web.
 * RÈGLE D'INTERFACE : un utilisateur gratuit VOIT les commandes d'édition ; les
 * toucher ouvre le paywall via [gate] — jamais un bouton qui disparaît.
 *
 * Singleton `object`, comme [ToolbarLayoutStore] : l'état Pro est global à
 * l'appareil, et Compose l'observe par les `mutableStateOf`. Initialisé une
 * fois dans MainActivity.onCreate ; le [BillingClient] se reconnecte seul.
 */
object ProAccess {
    /** Identifiant du produit — IDENTIQUE côté iOS (App Store Connect). */
    const val PRODUCT_ID = "com.minou.mvrviewer.pro"
    private const val PREFS = "mvrviewer.display"
    private const val KEY_ENTITLED = "pro.entitled"
    private const val KEY_GRANT = "pro.grant"

    /** ACHAT Play vérifié. Semé du cache local pour ne pas clignoter au
     *  lancement, puis confirmé par Play Billing. */
    var purchased by mutableStateOf(false)
        private set
    /** LICENCE PAR COMPTE (admin, Firestore). Semée du cache local, puis
     *  relue à chaque connexion — cf. [setAccountGrant]. */
    var grantedByAccount by mutableStateOf(false)
        private set
    /** Vrai si Pro par l'un OU l'autre chemin (ou forcé en DEBUG/tests).
     *  Propriété calculée sur des états Compose : l'observation fonctionne. */
    val isPro: Boolean get() = debugOverride ?: (purchased || grantedByAccount)
    /** Prix formaté par Google Play (« 29,99 € ») — jamais codé en dur. */
    var price by mutableStateOf<String?>(null)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
    /** Paywall demandé, avec ce que l'utilisateur essayait de faire. */
    var paywallReason by mutableStateOf<String?>(null)
        private set
    var showPaywall by mutableStateOf(false)

    private var appContext: Context? = null
    private var client: BillingClient? = null
    private var productDetails: ProductDetails? = null
    /** Crochet de test : `adb shell am start … --ez MVR_PRO true|false`
     *  (DEBUG uniquement, cf. MainActivity). État Compose pour que [isPro]
     *  reste observable quel que soit le chemin qui le fait changer. */
    private var debugOverride by mutableStateOf<Boolean?>(null)
    /** Branché par le SyncViewModel : relit la licence par compte. Appelé au
     *  début de [restore] — « Restaurer un achat » couvre aussi la licence. */
    var grantRefresher: (() -> Unit)? = null

    // MARK: - Achat reconnu sur les autres plateformes (SPEC-purchase §6.3)

    /** uid du compte connecté (posé par le SyncViewModel à la connexion /
     *  session restaurée, `null` à la déconnexion). Connu : le flux d'achat
     *  porte `setObfuscatedAccountId` (hachage de l'uid) ; inconnu : achat SANS
     *  identifiant (permis — le premier compte qui présentera la preuve l'aura). */
    var accountUid: String? = null
    /** Branché par le SyncViewModel : envoie la preuve d'achat au serveur.
     *  `explicit` = geste de l'utilisateur (acheter, « Restaurer un achat ») :
     *  envoi même si déjà fait, et message en cas de refus définitif. Appelé
     *  après l'achat (et son acquittement local), après la relecture Play de
     *  [restore], après la relecture Play faite à la connexion du BillingClient. */
    var purchaseLinker: ((explicit: Boolean) -> Unit)? = null
    /** Refus DÉFINITIF du serveur après un geste explicite (achat ou
     *  « Restaurer un achat ») — affiché dans le bloc Pro de l'écran compte.
     *  Effacé par un rattachement réussi et à la déconnexion. Jamais de texte
     *  pour un succès ni pour un échec réessayable (silencieux, renvoyé plus
     *  tard). */
    var accountLinkMessage by mutableStateOf<String?>(null)
        private set
    /** Le MÊME refus, à montrer TOUT DE SUITE là où se trouve l'utilisateur
     *  (alerte posée à la racine, `ProLinkRefusalHost`) quand le bloc Pro de
     *  l'écran compte n'est pas à l'écran. Pourquoi pas le paywall
     *  ([lastError]) : sur Android il est DÉJÀ FERMÉ quand le serveur répond
     *  — [applyPurchases] le ferme dès que Play confirme l'achat, AVANT
     *  l'envoi de la preuve, et [gate] ne le rouvre plus (l'appareil est Pro).
     *  Fermée par l'utilisateur ([dismissLinkRefusalAlert]), effacée par un
     *  rattachement réussi et à la déconnexion. */
    var linkRefusalAlert by mutableStateOf<String?>(null)
        private set
    /** Blocs Pro de l'écran compte actuellement composés (`ProAccountSection`
     *  le signale) : s'il y en a un, le refus y est déjà visible — pas
     *  d'alerte en double. */
    private val accountSectionsShown = java.util.concurrent.atomic.AtomicInteger(0)
    /** Jeton du `Purchase` PURCHASED du produit lu en dernier chez Play (jamais
     *  un PENDING) — la preuve envoyée au serveur. Non persisté : Play le rend
     *  à chaque relecture, y compris hors ligne. */
    @Volatile private var lastPurchaseToken: String? = null
    /** Résout un texte localisé (`R.string`) — Context de l'app ; remplaçable
     *  par les tests JVM (pas de Context). */
    internal var messageResolver: (Int) -> String? = { id -> appContext?.getString(id) }

    /** TESTS UNITAIRES (JVM, sans Activity ni Play) : force l'état ; `null`
     *  rend la main aux états réels (achat / licence). Les tests qui exercent
     *  une MUTATION gardée doivent se déclarer Pro, sinon la garde refuse —
     *  c'est précisément ce qu'elle doit faire en production. */
    fun forceForTests(pro: Boolean?) {
        debugOverride = pro
    }

    fun init(ctx: Context, debugOverride: Boolean? = null) {
        if (appContext != null) return
        val app = ctx.applicationContext
        appContext = app
        this.debugOverride = debugOverride
        val p = prefs(app)
        purchased = p.getBoolean(KEY_ENTITLED, false)
        grantedByAccount = p.getBoolean(KEY_GRANT, false)
        if (debugOverride != null) return
        connect()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
        paywallReason = reason
        showPaywall = true
        return false
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
            refuse = { r -> paywallReason = r; showPaywall = true }, set = set)

    // MARK: - Licence par compte

    /**
     * Applique la LICENCE PAR COMPTE lue par `SyncService.fetchProGrant` —
     * appelée par le SyncViewModel après chaque connexion réussie (et avec
     * `false` à la déconnexion). Persistée pour ne pas clignoter au lancement
     * (session restaurée, puis licence relue). Nul-sûr sans [init] (tests JVM).
     */
    fun setAccountGrant(granted: Boolean) {
        grantedByAccount = granted
        appContext?.let { prefs(it).edit().putBoolean(KEY_GRANT, granted).apply() }
        if (granted) showPaywall = false
    }

    // MARK: - Preuve d'achat (rattachement au compte, SPEC-purchase §6.3)

    /** Preuve de l'achat Play VALIDE (PURCHASED), `null` s'il n'y en a pas. */
    fun currentProof(): PurchaseProof? = lastPurchaseToken?.let { PurchaseLink.proofFor(it) }

    /** Retient le jeton de la preuve parmi les achats lus chez Play ; un achat
     *  EN ATTENTE n'en produit jamais. Appelé par [applyPurchases] (et les tests). */
    internal fun rememberPurchases(snapshots: List<PlayPurchaseSnapshot>) {
        lastPurchaseToken = PurchaseLink.selectProofToken(snapshots)
    }

    /** `obfuscatedAccountId` passé au flux d'achat : hachage de [accountUid]
     *  (64 caractères hexadécimaux, sans donnée personnelle), `null` si
     *  déconnecté. */
    internal fun purchaseAccountId(): String? = accountUid?.let { PurchaseLink.obfuscatedAccountId(it) }

    /**
     * Pose (refus définitif, `code` du contrat serveur) ou efface (`null`) le
     * message de rattachement — appelée par le PurchaseLinker du SyncViewModel,
     * seulement après un geste explicite pour un refus. Texte localisé (FR/EN,
     * `strings.xml`) : dans le bloc Pro de l'écran compte ([accountLinkMessage])
     * et, si ce bloc n'est pas à l'écran, en alerte immédiate
     * ([linkRefusalAlert]) — le geste a été fait dans le paywall, déjà fermé.
     */
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

    // MARK: - Play Billing

    private fun connect() {
        val app = appContext ?: return
        val c = BillingClient.newBuilder(app)
            .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    // Relecture au lancement : la preuve d'un achat déjà possédé
                    // part AUTOMATIQUEMENT (sautée si déjà rattachée à ce compte —
                    // sauf RÉPARATION si la licence relue plus tôt, avant que Play
                    // ait répondu, était fausse : cf. PurchaseLinker.linkPurchase).
                    refresh { purchaseLinker?.invoke(false) }
                }
            }
            // Le client se reconnecte à la prochaine action ([purchase],
            // [restore]) : pas de boucle de reconnexion agressive.
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun ensureConnected(then: () -> Unit) {
        val c = client
        if (c == null) { connect(); return }
        if (c.isReady) { then(); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) then()
                else lastError = result.debugMessage.ifEmpty { "Google Play indisponible." }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun queryProduct() {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ))
            .build()
        // Play Billing 8 : la réponse est un QueryProductDetailsResult — les
        // produits trouvés dans `productDetailsList`, ceux que Play n'a pas pu
        // servir dans `unfetchedProductList` (produit non publié, région…) ; dans
        // ce cas `productDetails` reste nul et [purchase] le signale comme avant.
        c.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val pd = details.productDetailsList.firstOrNull()
                productDetails = pd
                // 8.x : un produit ponctuel peut porter PLUSIEURS options d'achat
                // (`oneTimePurchaseOfferDetailsList`) ; l'offre par défaut reste
                // exposée par `oneTimePurchaseOfferDetails`, avec repli sur la
                // première de la liste. Prix formaté par Google Play, jamais codé en dur.
                val offer = pd?.oneTimePurchaseOfferDetails
                    ?: pd?.oneTimePurchaseOfferDetailsList?.firstOrNull()
                price = offer?.formattedPrice
            }
        }
    }

    /** Relit les achats depuis Google Play et met le cache à jour. [then] :
     *  appelé une fois la relecture RÉUSSIE appliquée (et l'acquittement local
     *  éventuel terminé) — c'est là que part la preuve d'achat. */
    fun refresh(then: (() -> Unit)? = null) {
        if (debugOverride != null) return
        val c = client ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                applyPurchases(purchases, then)
            }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        // Flux lancé par [purchase] (isBusy) = geste EXPLICITE ; sinon (achat en
        // attente confirmé plus tard, achat fait hors de l'app) = AUTOMATIQUE.
        val initiated = isBusy
        isBusy = false
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                applyPurchases(purchases.orEmpty()) { purchaseLinker?.invoke(initiated) }
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh { purchaseLinker?.invoke(true) }
            else -> lastError = result.debugMessage.ifEmpty { "L'achat n'a pas abouti." }
        }
    }

    /** [then] : appelé UNE fois, après l'acquittement local s'il y en a un à
     *  faire (réussi ou non : le serveur acquitte aussi, en ceinture), sinon
     *  tout de suite. Un achat valide FERME le paywall AVANT [then] (donc avant
     *  l'envoi de la preuve) : l'appareil est débloqué sans attendre le
     *  serveur. Nul-sûr sans [init] (tests JVM : ni cache, ni acquittement). */
    internal fun applyPurchases(purchases: List<Purchase>, then: (() -> Unit)? = null) {
        rememberPurchases(purchases.map {
            PlayPurchaseSnapshot(
                products = it.products,
                purchased = it.purchaseState == Purchase.PurchaseState.PURCHASED,
                purchaseToken = it.purchaseToken
            )
        })
        val fired = AtomicBoolean(false)
        val fire = { if (fired.compareAndSet(false, true)) then?.invoke() }
        var acknowledging = false
        var entitled = false
        for (p in purchases) {
            if (PRODUCT_ID !in p.products) continue
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            entitled = true
            // Google exige l'acquittement sous 3 jours, sinon REMBOURSEMENT
            // automatique — on acquitte dès qu'on voit l'achat.
            if (!p.isAcknowledged) {
                val c = client ?: continue
                acknowledging = true
                c.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(p.purchaseToken).build()
                ) { fire() }
            }
        }
        purchased = entitled
        appContext?.let { prefs(it).edit().putBoolean(KEY_ENTITLED, entitled).apply() }
        if (entitled) showPaywall = false
        if (!acknowledging) fire()
    }

    fun purchase(activity: Activity) {
        if (debugOverride != null) return
        lastError = null
        ensureConnected {
            val pd = productDetails
            if (pd == null) {
                queryProduct()
                lastError = "Le produit n'est pas disponible pour le moment. Vérifiez votre connexion et réessayez."
                return@ensureConnected
            }
            val builder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd).build()
                ))
            // Connecté : l'achat porte le hachage de l'uid (relu par le serveur
            // dans `obfuscatedExternalAccountId`) → il ne pourra être rattaché
            // qu'à CE compte (SPEC-purchase §3.3/§3.4).
            purchaseAccountId()?.let { builder.setObfuscatedAccountId(it) }
            val params = builder.build()
            isBusy = true
            val r = client?.launchBillingFlow(activity, params)
            if (r == null || r.responseCode != BillingClient.BillingResponseCode.OK) {
                isBusy = false
                lastError = r?.debugMessage?.ifEmpty { null } ?: "Google Play indisponible."
            }
        }
    }

    /** « Restaurer un achat » : les achats Google Play suivent le compte,
     *  il suffit de les relire — et on relit AUSSI la licence par compte
     *  ([grantRefresher], branché par le SyncViewModel). Après la relecture
     *  Play, la preuve repart EXPLICITEMENT (même si déjà rattachée). */
    fun restore() {
        lastError = null
        grantRefresher?.invoke()
        ensureConnected { refresh { purchaseLinker?.invoke(true) } }
    }
}
