package com.minou.mvrviewer.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.minou.mvrviewer.LegalLinks
import com.minou.mvrviewer.LegalPage
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.SceneModelLoader
import com.minou.mvrviewer.sync.SyncViewModel
import com.minou.mvrviewer.sync.syncMessage
import com.minou.mvrviewer.ui.theme.Accent
import com.minou.mvrviewer.ui.theme.accentButtonColors
import kotlinx.coroutines.launch

/**
 * PAYWALL — parité `ProPaywallView` iOS. Posé UNE fois à la racine
 * (MainActivity) : il s'affiche par-dessus n'importe quel écran dès que
 * [ProAccess.showPaywall] passe à vrai, d'où qu'ait été appelée la garde.
 *
 * ABONNEMENT ANNUEL (SPEC-abonnement §5.1, de haut en bas) : titre et durée,
 * prix ANNUEL de Google Play (élément de prix le plus visible, jamais mensualisé,
 * jamais barré), offre d'introduction s'il y en a une à laquelle on a droit,
 * « expirée le » pour un ancien abonné, fonctions Pro, bouton, PHRASE EXACTE
 * Google Play du §5.2 DIRECTEMENT sous le bouton (avec renonciation), liens
 * Conditions et Confidentialité, identité de l'éditeur, « Restaurer mes
 * achats », « Gérer ou résilier mon abonnement », croix.
 *
 * Fond BLANC forcé comme les autres panneaux (les boutons Material3 sont
 * recolorés explicitement — cf. la leçon des boutons « morts » en thème
 * sombre, ModelPlacementPanel) ; texte gris #555555 sur blanc (≈ 7,5:1),
 * lisible en thème clair comme sombre. TOUT défile : la phrase du §5.2 et les
 * liens légaux restent lisibles en paysage.
 *
 * [sync] : « Se connecter pour s'abonner » ouvre Compte & réglages PAR-DESSUS le
 * paywall ; refermé, le paywall montre le nouvel état (adresse relue).
 */
@Composable
fun ProPaywallHost(sync: SyncViewModel? = null) {
    if (!ProAccess.showPaywall) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAccount by remember { mutableStateOf(false) }
    var verificationSent by remember { mutableStateOf(false) }
    var verificationError by remember { mutableStateOf<String?>(null) }
    val texts = remember(ctx) { ContextProTexts(ctx) }
    val secondary = Color(0xFF555555)
    // L'adresse a pu être vérifiée (lien ouvert ailleurs) depuis la dernière
    // lecture : relue à l'ouverture, et au retour de la connexion.
    LaunchedEffect(showAccount) { if (!showAccount) sync?.refreshEmailVerification() }

    Dialog(onDismissRequest = { ProAccess.showPaywall = false }) {
        Surface(
            color = Color.White, contentColor = Color(0xFF111111),
            shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Ligne 12 : fermeture visible.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null,
                        tint = Accent.inkLight, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pro_section_title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { ProAccess.showPaywall = false },
                               modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close),
                            tint = Color(0xFF9E9E9E), modifier = Modifier.size(18.dp))
                    }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("pro.paywall")) {
                    ProAccess.paywallReason?.let { reason ->
                        Spacer(Modifier.height(8.dp))
                        val what = ProPaywallText.reasonRes(reason)?.let { stringResource(it) } ?: reason
                        Text(stringResource(R.string.pro_paywall_reason_fmt, what),
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    val price = ProAccess.price ?: ProAccess.debugPaywallPrice
                    OfferBox(price, texts, secondary)
                    // Ligne 4 : ancien abonné.
                    (ProAccess.statusLine as? ProStatusLine.Expired)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(SubscriptionTexts.expired(it.on, texts), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("pro.expired"))
                    }
                    // Ligne 5 : ce que l'on obtient.
                    Spacer(Modifier.height(10.dp))
                    for ((res, args) in ProPaywallText.FEATURES) {
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("•", color = Accent.inkLight, modifier = Modifier.width(16.dp))
                            Text(stringResource(res, *args.toTypedArray()), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.pro_free_note), style = MaterialTheme.typography.bodySmall,
                        color = secondary, modifier = Modifier.padding(top = 4.dp))
                    // Ligne 6 : erreur ; achat en attente.
                    ProAccess.lastError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                    }
                    ProAccess.pendingMessage?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro.pending"))
                    }
                    // Lignes 7 et 8 : le bouton, puis la phrase DIRECTEMENT dessous.
                    Spacer(Modifier.height(12.dp))
                    val otherStoreEnd = ProAccess.otherStoreSubscriptionEnd
                    if (otherStoreEnd != null) {
                        // Déjà abonné par l'autre boutique (§5.6) : pas de second abonnement.
                        Text(SubscriptionTexts.alreadySubscribed(otherStoreEnd, texts),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro.otherStore"))
                        Spacer(Modifier.height(6.dp))
                        SubscribeButton(price, enabled = false)
                    } else when (ProAccess.subscribeRequirement) {
                        SubscribeRequirement.SIGN_IN -> {
                            Text(stringResource(R.string.pro_account_needed), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("pro.accountNeeded"))
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = { showAccount = true }, enabled = sync != null,
                                colors = accentButtonColors(), modifier = Modifier.fillMaxWidth().testTag("pro.purchase")) {
                                Text(stringResource(R.string.pro_sign_in_to_subscribe))
                            }
                        }
                        SubscribeRequirement.VERIFY_EMAIL -> {
                            Text(stringResource(R.string.pro_verify_email_to_subscribe),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro.verifyEmail"))
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    verificationSent = false; verificationError = null
                                    scope.launch {
                                        try { sync?.sendEmailVerification(); verificationSent = sync != null }
                                        catch (e: Exception) { verificationError = e.syncMessage(ctx) }
                                    }
                                },
                                colors = accentButtonColors(), modifier = Modifier.fillMaxWidth().testTag("pro.purchase")
                            ) { Text(stringResource(R.string.pro_resend_verification)) }
                            if (verificationSent) Text(stringResource(R.string.pro_verification_sent),
                                style = MaterialTheme.typography.bodySmall)
                            verificationError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828)) }
                        }
                        SubscribeRequirement.READY -> SubscribeButton(
                            price, enabled = !ProAccess.isBusy && price != null,
                            onClick = { (ctx as? Activity)?.let { ProAccess.purchase(it) } }
                        )
                    }
                    // PHRASE EXACTE Google Play (§5.2), DIRECTEMENT sous le bouton, lue avant
                    // la feuille d'achat de Google Play, sans case à cocher.
                    if (price != null) Text(
                        SubscriptionTexts.terms(price, SubscriptionRules.includesVat(ProAccess.storeCountry), texts),
                        style = MaterialTheme.typography.bodySmall, color = secondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("pro.subscriptionTerms")
                    )
                    // Lignes 9 et 9 bis : liens légaux, éditeur.
                    Spacer(Modifier.height(8.dp))
                    Text(termsAndPrivacyLine(ctx), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("pro.legalLinks"))
                    Text(publisherLine(ctx), style = MaterialTheme.typography.bodySmall, color = secondary,
                        modifier = Modifier.padding(top = 2.dp).testTag("pro.publisher"))
                    // Lignes 10 et 11 : restaurer, gérer.
                    OutlinedButton(
                        onClick = { ProAccess.restore(ProAccess.RestoreScreen.PAYWALL) },
                        enabled = !ProAccess.isBusy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pro.restore")
                    ) { Text(stringResource(R.string.pro_restore), color = Color(0xFF111111)) }
                    ProAccess.restoreMessageShown(ProAccess.RestoreScreen.PAYWALL)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro.restoreMessage"))
                    }
                    if (ProAccess.hasDeviceSubscription) OutlinedButton(
                        onClick = { openExternalUrl(ctx, ProAccess.manageUrl) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("pro.manage")
                    ) { Text(stringResource(R.string.pro_manage), color = Color(0xFF111111)) }
                }
            }
        }
    }
    // Connexion / inscription PAR-DESSUS le paywall (« Se connecter pour s'abonner »).
    if (showAccount && sync != null) {
        Dialog(onDismissRequest = { showAccount = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize()) {
                AccountSettingsScreen(sync = sync, onBack = { showAccount = false })
            }
        }
    }
}

/** Lignes 1 à 3 : durée, prix annuel (le plus visible), offre d'introduction. */
@Composable
private fun OfferBox(price: String?, texts: ProTexts, secondary: Color) {
    Column(
        Modifier.fillMaxWidth()
            .background(Accent.fillLight.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(stringResource(R.string.pro_kind_annual), style = MaterialTheme.typography.bodySmall, color = secondary,
            modifier = Modifier.testTag("pro.kind"))
        if (price != null) {
            Text(stringResource(R.string.pro_price_per_year_fmt, price), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("pro.price"))
            ProAccess.offer?.intro?.let { intro ->
                Text(SubscriptionTexts.introOffer(intro, price, texts), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("pro.introOffer"))
            }
        } else {
            Text(stringResource(R.string.pro_loading_price), style = MaterialTheme.typography.headlineSmall,
                color = secondary, modifier = Modifier.testTag("pro.price"))
        }
    }
}

/** « S'abonner — {prix} par an » ; « Chargement du prix… » tant que Google Play
 *  n'a pas répondu (bouton désactivé). */
@Composable
private fun SubscribeButton(price: String?, enabled: Boolean, onClick: () -> Unit = {}) {
    Button(onClick = onClick, enabled = enabled, colors = accentButtonColors(),
        modifier = Modifier.fillMaxWidth().testTag("pro.purchase")) {
        // Prix formaté par Google Play (devise et langue du compte), jamais codé en dur.
        Text(if (price != null) stringResource(R.string.pro_subscribe_fmt, price)
             else stringResource(R.string.pro_loading_price))
    }
}

private fun linkStyles() = TextLinkStyles(SpanStyle(color = Accent.inkLight, textDecoration = TextDecoration.Underline))

/** « Conditions d'utilisation · Confidentialité », en liens (§5.1 ligne 9). */
private fun termsAndPrivacyLine(ctx: android.content.Context): AnnotatedString = buildAnnotatedString {
    withLink(LinkAnnotation.Url(LegalLinks.url(LegalPage.TERMS, ctx), linkStyles())) {
        append(ctx.getString(R.string.pro_terms_of_use))
    }
    append(" · ")
    withLink(LinkAnnotation.Url(LegalLinks.url(LegalPage.PRIVACY, ctx), linkStyles())) {
        append(ctx.getString(R.string.pro_privacy_policy))
    }
}

/** « Éditeur : {LegalLinks.PUBLISHER_NAME} · {adresse d'assistance} · Mentions légales »,
 *  l'adresse et les mentions légales en liens (§5.1 ligne 9 bis). */
private fun publisherLine(ctx: android.content.Context): AnnotatedString {
    val template = ctx.getString(R.string.pro_publisher_fmt)
    val parts = listOf(
        "%1\$s" to (LegalLinks.PUBLISHER_NAME to null),
        "%2\$s" to (LegalLinks.SUPPORT_EMAIL to "mailto:" + LegalLinks.SUPPORT_EMAIL),
        "%3\$s" to (ctx.getString(R.string.pro_publisher_legal_notice) to LegalLinks.url(LegalPage.LEGAL_NOTICE, ctx))
    )
    return buildAnnotatedString {
        var rest = template
        while (rest.isNotEmpty()) {
            val next = parts.mapNotNull { (mark, value) -> rest.indexOf(mark).takeIf { it >= 0 }?.let { Triple(it, mark, value) } }
                .minByOrNull { it.first }
            if (next == null) { append(rest); break }
            val (at, mark, value) = next
            append(rest.substring(0, at))
            val (text, url) = value
            if (url == null) append(text) else withLink(LinkAnnotation.Url(url, linkStyles())) { append(text) }
            rest = rest.substring(at + mark.length)
        }
    }
}

/**
 * REFUS DU RATTACHEMENT de l'abonnement au compte (SPEC-abonnement §5.9), montré
 * tout de suite après un abonnement ou « Restaurer mes achats » faits depuis le
 * paywall — qui est déjà FERMÉ quand le serveur répond (l'abonnement Play
 * débloque l'appareil d'abord). Posé UNE fois à la racine, à côté de
 * [ProPaywallHost]. Rien d'autre que le texte localisé ; le même message reste ensuite dans
 * le bloc Pro de l'écran compte. Pas d'alerte si ce bloc est déjà à l'écran
 * (cf. [ProAccess.setAccountLinkRefusal]).
 */
@Composable
fun ProLinkRefusalHost() {
    val message = ProAccess.linkRefusalAlert ?: return
    AlertDialog(
        onDismissRequest = { ProAccess.dismissLinkRefusalAlert() },
        title = { Text(stringResource(R.string.pro_section_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { ProAccess.dismissLinkRefusalAlert() }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/**
 * Textes de l'écran d'achat, en ressources (values/ et values-en/) comme le reste de
 * l'app — testés (`ProPaywallTextTest`).
 */
internal object ProPaywallText {
    /** Lignes « • » : ressource et arguments. */
    val FEATURES: List<Pair<Int, List<Any>>> = listOf(
        R.string.pro_feature_patch to emptyList(),
        R.string.pro_feature_cabling to emptyList(),
        R.string.pro_feature_orient to emptyList(),
        R.string.pro_feature_plans to emptyList(),
        // Formats RÉELLEMENT lus par l'app Android (moins qu'iOS, qui lit aussi le .dae) :
        // la même liste que l'accueil et le choix d'import (guide C9).
        R.string.pro_feature_models_fmt to listOf(SceneModelLoader.SUPPORTED_SUMMARY),
        R.string.pro_feature_custom_types to emptyList(),
        R.string.pro_feature_share to emptyList(),
    )

    /**
     * Raison passée à [ProAccess.gate] / [ProAccess.gatedSetter] (phrase française au
     * nominatif, la clé, comme les `String(localized:)` d'iOS) → son texte dans la
     * langue de l'app. Toute raison écrite dans le code doit être ici (test) ; une
     * raison inconnue s'afficherait telle quelle.
     */
    private val REASONS: Map<String, Int> = mapOf(
        "modifier le patch" to R.string.pro_reason_edit_patch,
        "importer un objet 3D" to R.string.pro_reason_import_model,
        "régler les objets 3D importés" to R.string.pro_reason_adjust_models,
        "modifier le câblage" to R.string.pro_reason_edit_cabling,
        "affecter le câblage" to R.string.pro_reason_assign_cabling,
        "créer un type personnalisé" to R.string.pro_reason_create_custom_type,
        "créer un projecteur personnalisé" to R.string.pro_reason_create_custom_fixture,
        "modifier un projecteur personnalisé" to R.string.pro_reason_edit_custom_fixture,
        "utiliser un projecteur personnalisé" to R.string.pro_reason_use_custom_fixture,
        "renseigner une consommation" to R.string.pro_reason_enter_power,
        "partager un projet" to R.string.pro_reason_share_project,
        "débloquer l'édition" to R.string.pro_reason_unlock_editing,
        GeoPanelContent.PRO_REASON to R.string.pro_reason_calibrate_gps,
        "annuler une modification" to R.string.pro_reason_undo,
        RefPlanPanelRules.IMPORT_PRO_REASON to R.string.pro_reason_import_plan,
        RefPlanPanelRules.PRO_REASON to R.string.pro_reason_adjust_plan,
        RefPlanPanelRules.LAYERS_PRO_REASON to R.string.pro_reason_plan_layers,
    )

    fun reasonRes(reason: String): Int? = REASONS[reason]
}
