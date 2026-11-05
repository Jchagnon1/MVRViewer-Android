package com.minou.mvrviewer.ui

import android.content.Context
import com.minou.mvrviewer.LegalLinks
import com.minou.mvrviewer.R
import com.minou.mvrviewer.sync.ProGrant
import com.minou.mvrviewer.sync.PurchaseLink
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// MARK: - Abonnement Pro : règles d'affichage et textes (SPEC-abonnement §5)
//
// Tout est PUR (ni Play Billing, ni Firebase) : l'écran Pro et Compte & réglages
// donnent les faits (prix et offres de Google Play, pays de la boutique,
// abonnement de l'appareil, licence du compte) et reçoivent les phrases, en
// ressources `values/` (fr) et `values-en/` (en). Le PRIX n'est jamais écrit ici :
// il vient de Google Play. Miroir de `SubscriptionTexts.swift` iOS, sauf la phrase
// sous le bouton (§5.2, propre à Google Play) et l'échéance, que l'appareil
// Android ne connaît pas (§12 : la date vient de la licence du compte).

/** Période ISO 8601 d'une boutique (`P1W`, `P3M`, `P1Y`…), réduite à ce que l'écran dit. */
data class SubscriptionPeriod(val value: Int, val unit: Unit) {
    enum class Unit { DAY, WEEK, MONTH, YEAR }

    /** Période multipliée (nombre de cycles d'une phase d'offre). */
    fun times(count: Int): SubscriptionPeriod = SubscriptionPeriod(value * maxOf(1, count), unit)

    companion object {
        /** `P{n}D`, `P{n}W`, `P{n}M`, `P{n}Y` (vecteurs `offre-texte.json`) ; autre → null. */
        fun parse(text: String?): SubscriptionPeriod? {
            if (text == null || text.length < 3 || !text.startsWith("P")) return null
            val n = text.substring(1, text.length - 1).toIntOrNull()?.takeIf { it > 0 } ?: return null
            val unit = when (text.last()) {
                'D' -> Unit.DAY
                'W' -> Unit.WEEK
                'M' -> Unit.MONTH
                'Y' -> Unit.YEAR
                else -> return null
            }
            return SubscriptionPeriod(n, unit)
        }
    }
}

/** Offre d'introduction rendue par Google Play (§5.4). Aucune n'existe en v1
 *  (§1.3) : l'affichage est prêt. */
data class IntroOffer(
    val kind: Kind,
    /** Durée TOTALE de l'offre. */
    val duration: SubscriptionPeriod,
    /** Période facturée (`payAsYouGo` seulement). */
    val period: SubscriptionPeriod? = null,
    /** Prix de l'offre tel que Google Play l'affiche. */
    val offerPrice: String? = null
) {
    enum class Kind { FREE_TRIAL, PAY_UP_FRONT, PAY_AS_YOU_GO }
}

/** Phase de prix d'une offre Play (`ProductDetails.PricingPhase`). */
data class PlayPricingPhase(
    val priceMicros: Long,
    val formattedPrice: String,
    val billingPeriod: String,
    /** `ProductDetails.RecurrenceMode` : 1 infini, 2 fini, 3 non récurrent. */
    val recurrenceMode: Int,
    val billingCycleCount: Int
)

/** Offre d'un plan de base (`ProductDetails.SubscriptionOfferDetails`). */
data class PlayOffer(
    val basePlanId: String,
    /** `null` : l'offre de base du plan. */
    val offerId: String?,
    val offerToken: String,
    val phases: List<PlayPricingPhase>
)

/** Offre retenue : son `offerToken` (obligatoire au flux d'achat), le prix annuel
 *  de la phase `INFINITE_RECURRING`, et l'offre d'introduction éventuelle. */
data class ChosenOffer(
    val offerToken: String,
    val price: String?,
    val billingPeriod: String?,
    val intro: IntroOffer?
)

object SubscriptionOffers {
    /** Valeurs de `ProductDetails.RecurrenceMode` (vérifiées par OfferTextTest). */
    const val INFINITE_RECURRING = 1
    const val FINITE_RECURRING = 2
    const val NON_RECURRING = 3

    /**
     * §4.3 : parmi les offres du plan `annual`, celle qui porte une phase
     * d'introduction si Google en rend une (Google ne rend que les offres
     * auxquelles l'utilisateur a droit), sinon l'offre de base ; prix = phase
     * `INFINITE_RECURRING`. Aucune offre du plan → null (produit indisponible).
     */
    fun choose(offers: List<PlayOffer>): ChosenOffer? {
        val plan = offers.filter { it.basePlanId == PurchaseLink.PRO_BASE_PLAN_ID }
        if (plan.isEmpty()) return null
        val withIntro = plan.firstOrNull { it.offerId != null && introOf(it) != null }
        val chosen = withIntro ?: plan.firstOrNull { it.offerId == null } ?: plan.first()
        val recurring = chosen.phases.lastOrNull { it.recurrenceMode == INFINITE_RECURRING } ?: chosen.phases.lastOrNull()
        return ChosenOffer(chosen.offerToken, recurring?.formattedPrice, recurring?.billingPeriod,
            if (withIntro != null) introOf(chosen) else null)
    }

    /**
     * Offre d'introduction d'une offre Play : sa PREMIÈRE phase non infinie.
     * Prix nul → essai gratuit ; phase unique non récurrente → prix payé
     * d'avance ; phase `FINITE_RECURRING` → prix par période (§5.4).
     */
    fun introOf(offer: PlayOffer): IntroOffer? {
        val phase = offer.phases.firstOrNull { it.recurrenceMode != INFINITE_RECURRING } ?: return null
        val period = SubscriptionPeriod.parse(phase.billingPeriod) ?: return null
        return when {
            phase.priceMicros == 0L -> IntroOffer(IntroOffer.Kind.FREE_TRIAL, period.times(phase.billingCycleCount))
            phase.recurrenceMode == NON_RECURRING ->
                IntroOffer(IntroOffer.Kind.PAY_UP_FRONT, period, offerPrice = phase.formattedPrice)
            else -> IntroOffer(IntroOffer.Kind.PAY_AS_YOU_GO, period.times(phase.billingCycleCount), period,
                phase.formattedPrice)
        }
    }
}

/**
 * Abonnement Pro de Google Play présent sur CET appareil (`queryPurchasesAsync`
 * ne rend que les abonnements en cours, période de grâce comprise). Android n'en
 * connaît PAS l'échéance.
 */
data class DeviceSubscription(
    val purchaseToken: String,
    /** Clé de la période (§4.1.1) ; nulle seulement pour un achat en attente. */
    val orderId: String?,
    val autoRenewing: Boolean,
    val acknowledged: Boolean
)

/** Ce que la section « MVR Viewer Pro » de Compte & réglages dit (§5.5). */
sealed interface ProStatusLine {
    /** Abonnement de cet appareil, renouvellement actif. */
    data class Renews(val on: Long) : ProStatusLine
    /** Abonnement de cet appareil, renouvellement coupé. */
    data class ActiveNoRenewal(val until: Long) : ProStatusLine
    /** Abonnement de cet appareil, échéance inconnue (sans compte, §12). */
    data object ActiveNoDate : ProStatusLine
    /** Période de grâce (paiement en échec, Pro gardé). */
    data class Grace(val until: Long) : ProStatusLine
    /** Paiement en échec hors grâce, suspension : Pro coupé. */
    data object BillingFailed : ProStatusLine
    /** Pause (Android, si activée) ; date de reprise inconnue → null. */
    data class Paused(val until: Long?) : ProStatusLine
    /** Pro par un abonnement géré ailleurs (autre appareil, autre boutique). */
    data class OtherDevice(val until: Long) : ProStatusLine
    /** Licence du compte (admin), datée ou non. */
    data class Licence(val until: Long?) : ProStatusLine
    /** Ancien abonné. */
    data class Expired(val on: Long) : ProStatusLine
    /** Jamais abonné : aucune ligne d'état. */
    data object None : ProStatusLine

    /** Boutons « Se réabonner » / « S'abonner à la version Pro ». */
    val offersSubscription: Boolean get() = this is Expired || this == None
}

/** Ce qu'exige le bouton du paywall avant de pouvoir s'abonner (§4.1.6). */
enum class SubscribeRequirement {
    /** Rien : « S'abonner — {prix} par an ». */
    READY,
    /** Sans compte : « Se connecter pour s'abonner ». */
    SIGN_IN,
    /** Compte connecté, adresse non vérifiée : « Renvoyer l'e-mail de vérification ». */
    VERIFY_EMAIL
}

/** Issue de « Restaurer mes achats » (§5.8), décidée APRÈS la relecture. */
sealed interface RestoreOutcome {
    /** Le Pro est actif : rien à dire. */
    data object Restored : RestoreOutcome
    /** Google Play joint, aucun abonnement pour ce compte Google. */
    data object NothingFound : RestoreOutcome
    /** Abonnement retrouvé (par la licence du compte), mais échu. */
    data class Expired(val on: Long) : RestoreOutcome
    /** Google Play injoignable et aucun droit retrouvé. */
    data object Failed : RestoreOutcome
}

object SubscriptionRules {
    /** Compte gratuit EXIGÉ pour s'abonner (Q1, décidée le 05/11) — sur un backend
     *  qui fait vérifier les achats. Repli si une boutique objecte : `false`. */
    const val SUBSCRIBE_REQUIRES_ACCOUNT = true
    /** Adresse e-mail VÉRIFIÉE exigée (Q1) : elle reçoit la confirmation et le
     *  rappel avant reconduction. Repli : `false`. */
    const val SUBSCRIBE_REQUIRES_VERIFIED_EMAIL = true

    /** Gestion de l'abonnement Pro dans Google Play (§4.3). */
    const val MANAGE_SUBSCRIPTION_URL = "https://play.google.com/store/account/subscriptions?sku=" +
        PurchaseLink.PRO_SUBSCRIPTION_ID + "&package=" + PurchaseLink.PACKAGE_NAME
    /** Gestion de tous les abonnements (aucun abonnement connu sur l'appareil). */
    const val MANAGE_ALL_URL = "https://play.google.com/store/account/subscriptions"
    /** « Demander un remboursement » : l'historique des commandes Google Play. */
    const val REFUND_URL = "https://play.google.com/store/account/orderhistory"

    fun manageUrl(hasDeviceSubscription: Boolean): String =
        if (hasDeviceSubscription) MANAGE_SUBSCRIPTION_URL else MANAGE_ALL_URL

    /**
     * Pays (ISO 3166-1 alpha-2, `BillingConfig.countryCode`) dont Google Play
     * affiche des prix TTC : EEE (UE + Islande, Liechtenstein, Norvège),
     * Royaume-Uni, Suisse (§5.2).
     */
    val VAT_INCLUDED_COUNTRIES: Set<String> = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
        "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        "IS", "LI", "NO", "GB", "CH"
    )

    /** « TTC » / « VAT included » seulement là où la boutique affiche des prix TTC ;
     *  pays inconnu → rien n'est affirmé. */
    fun includesVat(storeCountry: String?): Boolean =
        storeCountry?.uppercase(Locale.ROOT)?.let { it in VAT_INCLUDED_COUNTRIES } ?: false

    /** Règle pure du bouton (§4.1.6). Le backend local de démonstration n'exige
     *  rien ; une adresse dont l'état n'est pas encore connu n'est pas vérifiée. */
    fun subscribeRequirement(
        requiresAccount: Boolean, requiresVerifiedEmail: Boolean, verifiesPurchases: Boolean,
        signedIn: Boolean, emailVerified: Boolean?
    ): SubscribeRequirement {
        if (!verifiesPurchases) return SubscribeRequirement.READY
        if (!signedIn) return if (requiresAccount) SubscribeRequirement.SIGN_IN else SubscribeRequirement.READY
        if (requiresVerifiedEmail && emailVerified != true) return SubscribeRequirement.VERIFY_EMAIL
        return SubscribeRequirement.READY
    }

    /**
     * DOUBLE ABONNEMENT (§2.5, §5.6) : la licence montre un abonnement de l'AUTRE
     * boutique (App Store) actif ET d'échéance effective future → cette échéance ;
     * sinon null (paywall normal). Une source active mais échue ne bloque jamais.
     * La licence admin n'est jamais évoquée.
     */
    fun otherStoreSubscriptionEnd(licence: ProGrant?, now: Long): Long? =
        licence?.appstore?.takeIf { it.isLive(now) }?.effectiveEnd

    /** Abonnement à RENOUVELLEMENT en cours, sur l'appareil ou d'après la licence
     *  (§5.7) : supprimer le compte ne le résilie pas. Renouvellement inconnu :
     *  compté (prudence). */
    fun hasRenewingSubscription(device: DeviceSubscription?, licence: ProGrant?): Boolean {
        if (device != null && device.autoRenewing) return true
        return listOfNotNull(licence?.appstore, licence?.play).any { it.active && it.autoRenew != false }
    }

    /**
     * Ligne d'état de la section Pro (§5.5). Les dates viennent des sources de la
     * licence (`expiresAt`, `graceUntil`), jamais de `expires` (marge Google,
     * §3.7) : `expires` ne date que la licence admin, sans source de boutique
     * active. Abonnement de CET appareil : son état vient de la source Play de la
     * licence (grâce, échéance, renouvellement) ; sans elle (pas de compte, preuve
     * pas encore rattachée), « Abonnement actif » sans date (§12). Paiement en
     * échec (App Store `billing_retry`, Google Play `on_hold`), de l'une OU l'autre
     * boutique : « suspendue » seulement si rien d'autre ne donne le Pro.
     */
    fun statusLine(device: DeviceSubscription?, licence: ProGrant?, isPro: Boolean, now: Long): ProStatusLine {
        val play = licence?.play
        if (device != null) {
            if (play?.status == "grace") {
                (play.graceUntil ?: play.expiresAt)?.takeIf { it > now }?.let { return ProStatusLine.Grace(it) }
            }
            val end = play?.takeIf { it.status == "active" || it.status == "grace" }?.expiresAt?.takeIf { it > now }
            if (end != null) {
                return if (!device.autoRenewing || play.autoRenew == false) ProStatusLine.ActiveNoRenewal(end)
                else ProStatusLine.Renews(end)
            }
            return ProStatusLine.ActiveNoDate
        }
        val sources = listOfNotNull(licence?.appstore, play)
        sources.filter { it.isLive(now) }.mapNotNull { it.effectiveEnd }.maxOrNull()?.let {
            return ProStatusLine.OtherDevice(it)
        }
        // `billing_retry` n'existe que chez Apple, `on_hold` que chez Google (§2.1).
        if (!isPro && sources.any { it.status == "billing_retry" || it.status == "on_hold" }) {
            return ProStatusLine.BillingFailed
        }
        if (play?.status == "paused") return ProStatusLine.Paused(play.expiresAt?.takeIf { it > now })
        if (licence != null && licence.isActive(now)) {
            // Source de boutique active mais échue (renouvellement Google pas encore
            // relu : `expires` porte la marge de 6 h, V21) : « Version Pro active »,
            // sans date — jamais celle de `expires` (§5.5).
            if (sources.any { it.active }) return ProStatusLine.Licence(null)
            return ProStatusLine.Licence(licence.expires)
        }
        // Pro gardé hors ligne, ou forcé (`MVR_PRO`) : « Version Pro active ».
        if (isPro) return ProStatusLine.Licence(null)
        sources.filter { !it.isLive(now) }.mapNotNull { it.effectiveEnd }.filter { it <= now }.maxOrNull()?.let {
            return ProStatusLine.Expired(it)
        }
        return ProStatusLine.None
    }

    /** Échéance d'un ancien abonnement Play d'après la licence (« expiré le »), null sinon. */
    fun expiredPlayEnd(licence: ProGrant?, now: Long): Long? =
        licence?.play?.takeIf { !it.isLive(now) }?.effectiveEnd?.takeIf { it <= now }

    /**
     * Règle pure de « Restaurer mes achats » : un droit retrouvé l'emporte sur
     * toute erreur ; Google Play injoignable, on ne conclut rien ; Google Play
     * joint, un abonnement échu le dit, avec sa date.
     */
    fun restoreOutcome(isPro: Boolean, playFailed: Boolean, expiredOn: Long?): RestoreOutcome = when {
        isPro -> RestoreOutcome.Restored
        playFailed -> RestoreOutcome.Failed
        expiredOn != null -> RestoreOutcome.Expired(expiredOn)
        else -> RestoreOutcome.NothingFound
    }
}

/** Source des textes : ressources de l'app, ou celles lues par les tests JVM. */
interface ProTexts {
    fun string(id: Int, vararg args: Any): String
    fun plural(id: Int, count: Int, vararg args: Any): String
    /** Date longue (« 5 novembre 2026 » / « November 5, 2026 »). */
    fun date(ms: Long): String
}

/** Textes de l'app : langue d'affichage (`app_language`) + région du téléphone,
 *  pour qu'une phrase anglaise garde des dates anglaises. */
class ContextProTexts(private val context: Context) : ProTexts {
    override fun string(id: Int, vararg args: Any): String = context.getString(id, *args)
    override fun plural(id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)
    override fun date(ms: Long): String = SubscriptionTexts.date(ms, SubscriptionTexts.displayLocale(context))
}

/** Phrases de l'abonnement (§5.2, §5.4, §5.5, §5.6, §5.8, §5.9). */
object SubscriptionTexts {
    fun displayLocale(context: Context): Locale {
        val language = if (LegalLinks.isFrench(LegalLinks.appLanguage(context))) "fr" else "en"
        val region = Locale.getDefault().country
        return Locale.forLanguageTag(if (region.isNullOrEmpty()) language else "$language-$region")
    }

    fun date(ms: Long, locale: Locale): String =
        DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(ms))

    /** Ligne 8 : PHRASE EXACTE sous le bouton, Google Play (§5.2, décidée le
     *  05/11 : courte, AVEC renonciation, sans délai de 24 h). « TTC » seulement
     *  là où la boutique affiche des prix TTC. */
    fun terms(price: String, vatIncluded: Boolean, t: ProTexts): String =
        t.string(if (vatIncluded) R.string.pro_terms_vat_fmt else R.string.pro_terms_fmt, price)

    // MARK: Offre d'introduction (§5.4)

    /** « {n} jour(s) / semaine(s) / mois / an(s) » — pluriels des ressources. */
    fun duration(p: SubscriptionPeriod, t: ProTexts): String = t.plural(
        when (p.unit) {
            SubscriptionPeriod.Unit.DAY -> R.plurals.pro_duration_days
            SubscriptionPeriod.Unit.WEEK -> R.plurals.pro_duration_weeks
            SubscriptionPeriod.Unit.MONTH -> R.plurals.pro_duration_months
            SubscriptionPeriod.Unit.YEAR -> R.plurals.pro_duration_years
        }, p.value, p.value
    )

    /** « par {période} » : le nom seul pour une unité (« par mois »), la durée sinon. */
    fun periodName(p: SubscriptionPeriod, t: ProTexts): String {
        if (p.value != 1) return duration(p, t)
        return t.string(when (p.unit) {
            SubscriptionPeriod.Unit.DAY -> R.string.pro_period_day
            SubscriptionPeriod.Unit.WEEK -> R.string.pro_period_week
            SubscriptionPeriod.Unit.MONTH -> R.string.pro_period_month
            SubscriptionPeriod.Unit.YEAR -> R.string.pro_period_year
        })
    }

    /** Ligne 3 du paywall : durée, prix de l'offre, puis prix payé ensuite. Jamais
     *  de prix barré ni de pourcentage. */
    fun introOffer(offer: IntroOffer, price: String, t: ProTexts): String {
        val total = duration(offer.duration, t)
        return when (offer.kind) {
            IntroOffer.Kind.FREE_TRIAL -> t.string(R.string.pro_offer_free_trial_fmt, total, price)
            IntroOffer.Kind.PAY_UP_FRONT -> t.string(R.string.pro_offer_pay_up_front_fmt, offer.offerPrice ?: "", total, price)
            IntroOffer.Kind.PAY_AS_YOU_GO -> t.string(R.string.pro_offer_pay_as_you_go_fmt, offer.offerPrice ?: "",
                periodName(offer.period ?: offer.duration, t), total, price)
        }
    }

    // MARK: Compte & réglages (§5.5)

    fun statusLine(line: ProStatusLine, t: ProTexts): String? = when (line) {
        is ProStatusLine.Renews -> t.string(R.string.pro_status_renews_fmt, t.date(line.on))
        is ProStatusLine.ActiveNoRenewal -> t.string(R.string.pro_status_active_no_renewal_fmt, t.date(line.until))
        ProStatusLine.ActiveNoDate -> t.string(R.string.pro_status_active)
        is ProStatusLine.Grace -> t.string(R.string.pro_status_grace_fmt, t.date(line.until))
        ProStatusLine.BillingFailed -> t.string(R.string.pro_status_billing_failed)
        is ProStatusLine.Paused -> line.until?.let { t.string(R.string.pro_status_paused_fmt, t.date(it)) }
            ?: t.string(R.string.pro_status_paused)
        is ProStatusLine.OtherDevice -> t.string(R.string.pro_status_other_device_fmt, t.date(line.until))
        is ProStatusLine.Licence -> line.until?.let { t.string(R.string.pro_status_licence_until_fmt, t.date(it)) }
            ?: t.string(R.string.pro_status_licence)
        is ProStatusLine.Expired -> expired(line.on, t)
        ProStatusLine.None -> null
    }

    /** Ligne 4 du paywall et état « Expiré » de Compte & réglages. */
    fun expired(on: Long, t: ProTexts): String = t.string(R.string.pro_expired_fmt, t.date(on))

    /** Paywall, abonné par l'autre boutique (§5.6). */
    fun alreadySubscribed(until: Long, t: ProTexts): String =
        t.string(R.string.pro_already_subscribed_fmt, t.date(until))

    /** Message de « Restaurer mes achats » (§5.8), null : rien à dire. */
    fun restore(outcome: RestoreOutcome, t: ProTexts): String? = when (outcome) {
        RestoreOutcome.Restored -> null
        RestoreOutcome.NothingFound -> t.string(R.string.pro_restore_nothing)
        is RestoreOutcome.Expired -> t.string(R.string.pro_restore_expired_fmt, t.date(outcome.on))
        RestoreOutcome.Failed -> t.string(R.string.pro_error_play_unavailable)
    }
}
