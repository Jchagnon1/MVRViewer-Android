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
                    refresh()
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

    /** Relit les achats depuis Google Play et met le cache à jour. */
    fun refresh() {
        if (debugOverride != null) return
        val c = client ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                applyPurchases(purchases)
            }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        isBusy = false
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> applyPurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> lastError = result.debugMessage.ifEmpty { "L'achat n'a pas abouti." }
        }
    }

    private fun applyPurchases(purchases: List<Purchase>) {
        val app = appContext ?: return
        var entitled = false
        for (p in purchases) {
            if (PRODUCT_ID !in p.products) continue
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            entitled = true
            // Google exige l'acquittement sous 3 jours, sinon REMBOURSEMENT
            // automatique — on acquitte dès qu'on voit l'achat.
            if (!p.isAcknowledged) {
                client?.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(p.purchaseToken).build()
                ) {}
            }
        }
        purchased = entitled
        prefs(app).edit().putBoolean(KEY_ENTITLED, entitled).apply()
        if (entitled) showPaywall = false
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
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd).build()
                ))
                .build()
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
     *  ([grantRefresher], branché par le SyncViewModel). */
    fun restore() {
        lastError = null
        grantRefresher?.invoke()
        ensureConnected { refresh() }
    }
}
