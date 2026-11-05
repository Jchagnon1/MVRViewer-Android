package com.minou.mvrviewer.ui

import com.minou.mvrviewer.sync.ProGrant
import com.minou.mvrviewer.sync.ProGrant.SourceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règles d'affichage de l'abonnement (SPEC-abonnement §4.1.6, §5.2, §5.5, §5.6, §5.7,
 * §5.8) : bouton du paywall, double abonnement, ligne d'état de Compte & réglages,
 * avertissement de suppression, restauration, « TTC », liens de Google Play.
 */
class SubscriptionRulesTest {
    private val now = 1_800_000_000_000L // 2027-01-15T08:00:00Z, comme les vecteurs
    private val day = 86_400_000L
    private val device = DeviceSubscription("tok", "GPA.1234-5678-9012-34567", autoRenewing = true, acknowledged = true)

    // MARK: - Bouton du paywall (§4.1.6)

    @Test fun compteEtAdresseVerifieeExigesSurUnBackendQuiVerifie() {
        assertTrue(SubscriptionRules.SUBSCRIBE_REQUIRES_ACCOUNT)
        assertTrue(SubscriptionRules.SUBSCRIBE_REQUIRES_VERIFIED_EMAIL)
        fun req(verifies: Boolean, signedIn: Boolean, verified: Boolean?) =
            SubscriptionRules.subscribeRequirement(true, true, verifies, signedIn, verified)
        assertEquals(SubscribeRequirement.SIGN_IN, req(true, false, null))
        assertEquals(SubscribeRequirement.VERIFY_EMAIL, req(true, true, null)) // inconnu = non vérifiée
        assertEquals(SubscribeRequirement.VERIFY_EMAIL, req(true, true, false))
        assertEquals(SubscribeRequirement.READY, req(true, true, true))
        // Backend local de démonstration : rien n'est exigé.
        assertEquals(SubscribeRequirement.READY, req(false, false, null))
        // Repli si une boutique objecte : constantes à false, sans autre changement.
        assertEquals(SubscribeRequirement.READY, SubscriptionRules.subscribeRequirement(false, false, true, false, null))
        assertEquals(SubscribeRequirement.READY, SubscriptionRules.subscribeRequirement(true, false, true, true, false))
    }

    // MARK: - Double abonnement (§2.5, §5.6)

    @Test fun abonnementAppStoreActifEtFuturBloqueLAchat() {
        val live = ProGrant(true, now + 30 * day, appstore = SourceView(true, "active", now + 30 * day), fromServer = true)
        assertEquals(now + 30 * day, SubscriptionRules.otherStoreSubscriptionEnd(live, now))
        // Grâce : l'échéance effective est la fin de grâce.
        val grace = ProGrant(true, null, appstore = SourceView(true, "grace", now - day, now + 5 * day), fromServer = true)
        assertEquals(now + 5 * day, SubscriptionRules.otherStoreSubscriptionEnd(grace, now))
        // Active mais ÉCHUE (contrôle pas encore passé) : ne bloque jamais.
        val stale = ProGrant(true, null, appstore = SourceView(true, "active", now - day), fromServer = true)
        assertNull(SubscriptionRules.otherStoreSubscriptionEnd(stale, now))
        // Inactive, ou abonnement de LA MÊME boutique (Play), ou licence admin : rien.
        assertNull(SubscriptionRules.otherStoreSubscriptionEnd(
            ProGrant(false, appstore = SourceView(false, "expired", now + day), fromServer = true), now))
        assertNull(SubscriptionRules.otherStoreSubscriptionEnd(
            ProGrant(true, now + 30 * day, play = SourceView(true, "active", now + 30 * day), fromServer = true), now))
        assertNull(SubscriptionRules.otherStoreSubscriptionEnd(ProGrant(true, null, fromServer = true), now))
        assertNull(SubscriptionRules.otherStoreSubscriptionEnd(null, now))
    }

    // MARK: - Ligne d'état (§5.5)

    private fun line(device: DeviceSubscription?, licence: ProGrant?, isPro: Boolean = false) =
        SubscriptionRules.statusLine(device, licence, isPro, now)

    @Test fun abonnementDeCetAppareil() {
        val e = now + 200 * day
        val play = { s: String, autoRenew: Boolean?, grace: Long? ->
            ProGrant(true, e + 21_600_000L, play = SourceView(true, s, e, grace, autoRenew), fromServer = true)
        }
        // Date tirée de `expiresAt`, JAMAIS de `expires` (marge Google de 6 h).
        assertEquals(ProStatusLine.Renews(e), line(device, play("active", true, null)))
        assertEquals(ProStatusLine.ActiveNoRenewal(e), line(device, play("active", false, null)))
        assertEquals(ProStatusLine.ActiveNoRenewal(e), line(device.copy(autoRenewing = false), play("active", true, null)))
        assertEquals(ProStatusLine.Grace(now + 5 * day), line(device, play("grace", true, now + 5 * day)))
        // Sans compte (ou preuve pas encore rattachée) : échéance inconnue (§12).
        assertEquals(ProStatusLine.ActiveNoDate, line(device, null))
        // Source Play échue (renouvellement pas encore vu par le serveur) : sans date.
        assertEquals(ProStatusLine.ActiveNoDate, line(device,
            ProGrant(true, now + 3_600_000L, play = SourceView(true, "active", now - 3_600_000L, null, true), fromServer = true)))
    }

    @Test fun sansAbonnementSurLAppareil() {
        // Pro par un abonnement géré ailleurs (iPhone, ou autre compte Google).
        assertEquals(ProStatusLine.OtherDevice(now + 40 * day), line(null,
            ProGrant(true, now + 40 * day, appstore = SourceView(true, "active", now + 40 * day), fromServer = true)))
        assertEquals(ProStatusLine.OtherDevice(now + 10 * day), line(null,
            ProGrant(true, null, play = SourceView(true, "active", now + 10 * day), fromServer = true)))
        // Paiement en échec, suspension, pause (Play).
        for (s in listOf("billing_retry", "on_hold")) assertEquals(ProStatusLine.BillingFailed, line(null,
            ProGrant(false, play = SourceView(false, s, now - day), fromServer = true)))
        assertEquals(ProStatusLine.Paused(now + 9 * day), line(null,
            ProGrant(false, play = SourceView(false, "paused", now + 9 * day), fromServer = true)))
        assertEquals(ProStatusLine.Paused(null), line(null,
            ProGrant(false, play = SourceView(false, "paused", now - day), fromServer = true)))
        // Licence admin, datée ou non.
        assertEquals(ProStatusLine.Licence(null), line(null, ProGrant(true, null, fromServer = true)))
        assertEquals(ProStatusLine.Licence(now + 3 * day), line(null, ProGrant(true, now + 3 * day, fromServer = true)))
        // Pro gardé hors ligne ou forcé.
        assertEquals(ProStatusLine.Licence(null), line(null, null, isPro = true))
        // Ancien abonné : la dernière échéance passée.
        assertEquals(ProStatusLine.Expired(now - 2 * day), line(null, ProGrant(false,
            appstore = SourceView(false, "expired", now - 9 * day),
            play = SourceView(false, "expired", now - 2 * day), fromServer = true)))
        // Jamais abonné.
        assertEquals(ProStatusLine.None, line(null, null))
        assertEquals(ProStatusLine.None, line(null, ProGrant(false, fromServer = true)))
        assertTrue(ProStatusLine.None.offersSubscription)
        assertTrue(ProStatusLine.Expired(1).offersSubscription)
        assertFalse(ProStatusLine.ActiveNoDate.offersSubscription)
        assertFalse(ProStatusLine.BillingFailed.offersSubscription)
    }

    @Test fun paiementEnEchecDeLUneOuLAutreBoutique() {
        // App Store `billing_retry` (Apple retente le paiement, §2.1), aucun abonnement
        // sur l'appareil : « Paiement en échec », jamais « expirée » ni « Se réabonner »
        // (qui pousserait vers un second abonnement pendant qu'Apple retente).
        val appleRetry = ProGrant(false, appstore = SourceView(false, "billing_retry", now - 2 * day, null), fromServer = true)
        assertEquals(ProStatusLine.BillingFailed, line(null, appleRetry))
        assertFalse(line(null, appleRetry).offersSubscription)
        // Google Play `on_hold`.
        assertEquals(ProStatusLine.BillingFailed, line(null,
            ProGrant(false, play = SourceView(false, "on_hold", now - day), fromServer = true)))
        // « Suspendue » seulement si rien d'autre ne donne le Pro.
        assertEquals("abonnement géré ailleurs, vivant", ProStatusLine.OtherDevice(now + 10 * day), line(null,
            ProGrant(true, now + 10 * day, appstore = SourceView(false, "billing_retry", now - 2 * day),
                play = SourceView(true, "active", now + 10 * day), fromServer = true), isPro = true))
        assertEquals("licence admin", ProStatusLine.Licence(null), line(null,
            ProGrant(true, null, appstore = SourceView(false, "billing_retry", now - 2 * day), fromServer = true), isPro = true))
        assertEquals("Pro gardé hors ligne", ProStatusLine.Licence(null), line(null, appleRetry, isPro = true))
    }

    @Test fun margeDeRenouvellementGoogleSansLaDateDeExpires() {
        // V21 (§3.7) : abonné Google, appareil SANS l'abonnement (autre compte Google),
        // F < maintenant < F + 6 h — Google a renouvelé, le balayage n'a pas encore relu.
        // Jamais la date de `expires` (F + 6 h, §5.5) : « Version Pro active », sans date.
        val f = now - 3_600_000L
        val v21 = ProGrant(true, f + 21_600_000L, play = SourceView(true, "active", f, null, true), fromServer = true)
        assertEquals(ProStatusLine.Licence(null), line(null, v21, isPro = true))
        assertEquals("Version Pro active", SubscriptionTexts.statusLine(line(null, v21, isPro = true), TestRes.FR.proTexts()))
        // Même chose pour une source App Store active mais échue (notification en retard).
        assertEquals(ProStatusLine.Licence(null), line(null,
            ProGrant(true, now + day, appstore = SourceView(true, "active", now - 60_000L), fromServer = true), isPro = true))
        // Licence admin datée, sans source de boutique active : SA date.
        assertEquals(ProStatusLine.Licence(now + 3 * day), line(null,
            ProGrant(true, now + 3 * day, appstore = SourceView(false, "expired", now - day), fromServer = true)))
    }

    @Test fun textesDesLignesDEtatEnFrancaisEtEnAnglais() {
        val d = 1_805_112_000_000L // 2027-03-15 (UTC)
        val fr = TestRes.FR.proTexts()
        val en = TestRes.EN.proTexts()
        fun both(l: ProStatusLine) = SubscriptionTexts.statusLine(l, fr) to SubscriptionTexts.statusLine(l, en)
        assertEquals("Abonnement actif — renouvellement le 15 mars 2027" to "Subscription active — renews on March 15, 2027",
            both(ProStatusLine.Renews(d)))
        assertEquals("Abonnement actif jusqu'au 15 mars 2027 — renouvellement automatique désactivé" to
            "Subscription active until March 15, 2027 — auto-renew is off", both(ProStatusLine.ActiveNoRenewal(d)))
        assertEquals("Abonnement actif" to "Subscription active", both(ProStatusLine.ActiveNoDate))
        assertEquals("Problème de paiement : mettez à jour votre moyen de paiement avant le 15 mars 2027 pour garder la version Pro." to
            "Payment issue: update your payment method before March 15, 2027 to keep Pro.", both(ProStatusLine.Grace(d)))
        assertEquals("Paiement en échec : la version Pro est suspendue jusqu'au règlement." to
            "Payment failed: Pro is on hold until payment goes through.", both(ProStatusLine.BillingFailed))
        assertEquals("Abonnement en pause : la version Pro reprendra le 15 mars 2027." to
            "Subscription paused: Pro resumes on March 15, 2027.", both(ProStatusLine.Paused(d)))
        assertEquals("Version Pro active jusqu'au 15 mars 2027 — abonnement géré sur un autre appareil" to
            "Pro active until March 15, 2027 — subscription managed on another device", both(ProStatusLine.OtherDevice(d)))
        assertEquals("Version Pro active" to "Pro active", both(ProStatusLine.Licence(null)))
        assertEquals("Version Pro active jusqu'au 15 mars 2027" to "Pro active until March 15, 2027", both(ProStatusLine.Licence(d)))
        assertEquals("Version Pro expirée le 15 mars 2027. Vos fichiers et projets restent lisibles." to
            "Pro expired on March 15, 2027. Your files and projects remain readable.", both(ProStatusLine.Expired(d)))
        assertEquals(null to null, both(ProStatusLine.None))
        assertEquals("Vous êtes déjà abonné à MVR Viewer Pro jusqu'au 15 mars 2027. Cet abonnement se gère sur l'appareil où vous l'avez souscrit.",
            SubscriptionTexts.alreadySubscribed(d, fr))
        assertEquals("You already have MVR Viewer Pro until March 15, 2027. Manage it on the device where you subscribed.",
            SubscriptionTexts.alreadySubscribed(d, en))
    }

    // MARK: - Suppression du compte (§5.7)

    @Test fun abonnementARenouvellementAvantLaSuppression() {
        assertTrue(SubscriptionRules.hasRenewingSubscription(device, null))
        assertFalse(SubscriptionRules.hasRenewingSubscription(device.copy(autoRenewing = false), null))
        assertTrue(SubscriptionRules.hasRenewingSubscription(null,
            ProGrant(true, play = SourceView(true, "active", now + day, autoRenew = true), fromServer = true)))
        // Renouvellement inconnu : compté (prudence).
        assertTrue(SubscriptionRules.hasRenewingSubscription(null,
            ProGrant(true, appstore = SourceView(true, "active", now + day, autoRenew = null), fromServer = true)))
        assertFalse(SubscriptionRules.hasRenewingSubscription(null,
            ProGrant(true, appstore = SourceView(true, "active", now + day, autoRenew = false), fromServer = true)))
        assertFalse(SubscriptionRules.hasRenewingSubscription(null,
            ProGrant(false, play = SourceView(false, "expired", now - day, autoRenew = true), fromServer = true)))
        assertFalse(SubscriptionRules.hasRenewingSubscription(null, ProGrant(true, null, fromServer = true)))
        assertFalse(SubscriptionRules.hasRenewingSubscription(null, null))
    }

    // MARK: - Restauration (§5.8)

    @Test fun issuesDeLaRestauration() {
        assertEquals(RestoreOutcome.Restored, SubscriptionRules.restoreOutcome(true, playFailed = true, expiredOn = now - day))
        assertEquals(RestoreOutcome.Failed, SubscriptionRules.restoreOutcome(false, playFailed = true, expiredOn = now - day))
        assertEquals(RestoreOutcome.Expired(now - day), SubscriptionRules.restoreOutcome(false, false, now - day))
        assertEquals(RestoreOutcome.NothingFound, SubscriptionRules.restoreOutcome(false, false, null))
        assertEquals(now - day, SubscriptionRules.expiredPlayEnd(
            ProGrant(false, play = SourceView(false, "expired", now - day), fromServer = true), now))
        assertNull(SubscriptionRules.expiredPlayEnd(
            ProGrant(true, play = SourceView(true, "active", now + day), fromServer = true), now))
        val fr = TestRes.FR.proTexts()
        val en = TestRes.EN.proTexts()
        assertEquals("Aucun abonnement à restaurer pour ce compte Google.", SubscriptionTexts.restore(RestoreOutcome.NothingFound, fr))
        assertEquals("No subscription to restore for this Google account.", SubscriptionTexts.restore(RestoreOutcome.NothingFound, en))
        assertEquals("Votre abonnement a expiré le 15 mars 2027.", SubscriptionTexts.restore(RestoreOutcome.Expired(1_805_112_000_000L), fr))
        assertEquals("Your subscription expired on March 15, 2027.", SubscriptionTexts.restore(RestoreOutcome.Expired(1_805_112_000_000L), en))
        assertEquals("Google Play indisponible.", SubscriptionTexts.restore(RestoreOutcome.Failed, fr))
        assertNull(SubscriptionTexts.restore(RestoreOutcome.Restored, fr))
    }

    // MARK: - « TTC » et liens de Google Play

    @Test fun ttcSeulementDansLEeeAuRoyaumeUniEtEnSuisse() {
        for (c in listOf("FR", "fr", "DE", "BE", "GR", "NO", "IS", "LI", "GB", "CH")) assertTrue(c, SubscriptionRules.includesVat(c))
        for (c in listOf("US", "CA", "JP", "AU", "EL", "", null)) assertFalse("$c", SubscriptionRules.includesVat(c))
        assertEquals(32, SubscriptionRules.VAT_INCLUDED_COUNTRIES.size) // 27 UE + 3 EEE + GB + CH
    }

    @Test fun liensDeGestionEtDeRemboursement() {
        assertEquals("https://play.google.com/store/account/subscriptions?sku=com.minou.mvrviewer.pro.subscription&package=com.minou.mvrviewer",
            SubscriptionRules.manageUrl(hasDeviceSubscription = true))
        assertEquals("https://play.google.com/store/account/subscriptions", SubscriptionRules.manageUrl(false))
        assertEquals("https://play.google.com/store/account/orderhistory", SubscriptionRules.REFUND_URL)
    }
}
