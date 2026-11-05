package com.minou.mvrviewer.ui

import com.android.billingclient.api.ProductDetails
import com.minou.mvrviewer.sync.PurchaseLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * OFFRE D'INTRODUCTION, affichage générique (SPEC-abonnement §5.4 ; aucune offre en
 * v1) : les vecteurs communs O1-O13 du dépôt web (`offre-texte.json`) rejoués avec
 * les VRAIES ressources fr et en ; et le choix de l'offre Play et de son
 * `offerToken` (§4.3).
 */
class OfferTextTest {

    private val vectors: JSONObject by lazy {
        val url = javaClass.classLoader!!.getResource("port-vectors/abonnement/offre-texte.json")!!
        JSONObject(File(url.toURI()).readText())
    }

    @Test fun lesTreizeVecteursCommuns() {
        assertEquals("mvrviewer.vectors.offre-texte/1", vectors.getString("schema"))
        val list = vectors.getJSONArray("vectors")
        assertEquals(13, list.length())
        for (k in 0 until list.length()) {
            val v = list.getJSONObject(k)
            val o = v.getJSONObject("offer")
            val kind = when (o.getString("kind")) {
                "freeTrial" -> IntroOffer.Kind.FREE_TRIAL
                "payUpFront" -> IntroOffer.Kind.PAY_UP_FRONT
                "payAsYouGo" -> IntroOffer.Kind.PAY_AS_YOU_GO
                else -> error("genre inconnu")
            }
            val offer = IntroOffer(
                kind = kind,
                duration = SubscriptionPeriod.parse(o.getString("duration"))!!,
                period = SubscriptionPeriod.parse(o.optString("period", "").ifEmpty { null }),
                offerPrice = o.optString("offerPrice", "").ifEmpty { null }
            )
            val res = if (v.getString("lang") == "fr") TestRes.FR else TestRes.EN
            assertEquals(v.getString("id"), v.getString("expect"),
                SubscriptionTexts.introOffer(offer, v.getString("price"), res.proTexts()))
        }
    }

    @Test fun periodesIso() {
        assertEquals(SubscriptionPeriod(1, SubscriptionPeriod.Unit.WEEK), SubscriptionPeriod.parse("P1W"))
        assertEquals(SubscriptionPeriod(14, SubscriptionPeriod.Unit.DAY), SubscriptionPeriod.parse("P14D"))
        assertEquals(SubscriptionPeriod(3, SubscriptionPeriod.Unit.MONTH), SubscriptionPeriod.parse("P3M"))
        assertEquals(SubscriptionPeriod(1, SubscriptionPeriod.Unit.YEAR), SubscriptionPeriod.parse("P1Y"))
        for (bad in listOf(null, "", "P", "PY", "P0Y", "1Y", "P1H", "P-1M")) assertNull(bad, SubscriptionPeriod.parse(bad))
        assertEquals(SubscriptionPeriod(3, SubscriptionPeriod.Unit.MONTH),
            SubscriptionPeriod(1, SubscriptionPeriod.Unit.MONTH).times(3))
    }

    @Test fun modesDeRecurrenceDePlayBilling() {
        assertEquals(ProductDetails.RecurrenceMode.INFINITE_RECURRING, SubscriptionOffers.INFINITE_RECURRING)
        assertEquals(ProductDetails.RecurrenceMode.FINITE_RECURRING, SubscriptionOffers.FINITE_RECURRING)
        assertEquals(ProductDetails.RecurrenceMode.NON_RECURRING, SubscriptionOffers.NON_RECURRING)
    }

    // MARK: - Choix de l'offre et de l'offerToken (§4.3)

    private val yearly = PlayPricingPhase(25_990_000L, "25,99 €", "P1Y", SubscriptionOffers.INFINITE_RECURRING, 0)
    private val base = PlayOffer(PurchaseLink.PRO_BASE_PLAN_ID, null, "tok-base", listOf(yearly))

    @Test fun offreDeBaseSansIntroduction() {
        val chosen = SubscriptionOffers.choose(listOf(base))!!
        assertEquals("tok-base", chosen.offerToken)
        assertEquals("25,99 €", chosen.price)
        assertEquals("P1Y", chosen.billingPeriod)
        assertNull(chosen.intro)
    }

    @Test fun offreAvecEssaiGratuitRetenueQuandGoogleLaRend() {
        val trial = PlayOffer(PurchaseLink.PRO_BASE_PLAN_ID, "essai-1-semaine", "tok-trial", listOf(
            PlayPricingPhase(0L, "Gratuit", "P1W", SubscriptionOffers.FINITE_RECURRING, 1), yearly))
        val chosen = SubscriptionOffers.choose(listOf(base, trial))!!
        assertEquals("tok-trial", chosen.offerToken)
        assertEquals("25,99 €", chosen.price) // la phase INFINITE_RECURRING
        assertEquals(IntroOffer(IntroOffer.Kind.FREE_TRIAL, SubscriptionPeriod(1, SubscriptionPeriod.Unit.WEEK)), chosen.intro)
        assertEquals("Essai gratuit de 1 semaine, puis 25,99 € par an.",
            SubscriptionTexts.introOffer(chosen.intro!!, chosen.price!!, TestRes.FR.proTexts()))
    }

    @Test fun prixPayeDAvanceEtPrixParPeriode() {
        val upFront = PlayOffer(PurchaseLink.PRO_BASE_PLAN_ID, "lancement", "tok-up", listOf(
            PlayPricingPhase(4_990_000L, "4,99 €", "P3M", SubscriptionOffers.NON_RECURRING, 1), yearly))
        assertEquals(IntroOffer(IntroOffer.Kind.PAY_UP_FRONT, SubscriptionPeriod(3, SubscriptionPeriod.Unit.MONTH),
            offerPrice = "4,99 €"), SubscriptionOffers.introOf(upFront))
        val monthly = PlayOffer(PurchaseLink.PRO_BASE_PLAN_ID, "mois", "tok-m", listOf(
            PlayPricingPhase(990_000L, "0,99 €", "P1M", SubscriptionOffers.FINITE_RECURRING, 3), yearly))
        val intro = SubscriptionOffers.introOf(monthly)!!
        assertEquals(IntroOffer(IntroOffer.Kind.PAY_AS_YOU_GO, SubscriptionPeriod(3, SubscriptionPeriod.Unit.MONTH),
            SubscriptionPeriod(1, SubscriptionPeriod.Unit.MONTH), "0,99 €"), intro)
        assertEquals("0,99 € par mois pendant 3 mois, puis 25,99 € par an.",
            SubscriptionTexts.introOffer(intro, "25,99 €", TestRes.FR.proTexts()))
    }

    @Test fun seulLePlanAnnuelCompte() {
        val monthlyPlan = PlayOffer("monthly", null, "tok-monthly", listOf(
            PlayPricingPhase(2_990_000L, "2,99 €", "P1M", SubscriptionOffers.INFINITE_RECURRING, 0)))
        assertNull(SubscriptionOffers.choose(listOf(monthlyPlan)))
        assertNull(SubscriptionOffers.choose(emptyList()))
        assertEquals("tok-base", SubscriptionOffers.choose(listOf(monthlyPlan, base))!!.offerToken)
        // Une offre (offerId) SANS phase d'introduction n'est pas préférée à l'offre de base.
        val promo = PlayOffer(PurchaseLink.PRO_BASE_PLAN_ID, "tag-seul", "tok-promo", listOf(yearly))
        assertEquals("tok-base", SubscriptionOffers.choose(listOf(promo, base))!!.offerToken)
    }
}
