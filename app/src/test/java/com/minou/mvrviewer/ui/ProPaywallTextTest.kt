package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.SceneModelLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Écran Pro (abonnement annuel, SPEC-abonnement §5) EN DEUX LANGUES : plus aucun
 * texte écrit en dur dans ProPaywall.kt ; chaque ligne existe en français (values/)
 * ET en anglais (values-en/), à la lettre de la spec ; phrase Google Play du §5.2
 * sous le bouton ; identité de l'éditeur ; chaque raison passée à la garde Pro a sa
 * traduction (sinon « To modifier le patch, you need… » en anglais).
 */
class ProPaywallTextTest {
    private val fr = TestRes.FR
    private val en = TestRes.EN

    private fun dir(vararg candidates: String): File = candidates.map(::File).first { it.isDirectory }
    private val javaDir by lazy { dir("src/main/java/com/minou/mvrviewer", "app/src/main/java/com/minou/mvrviewer") }
    private fun src(path: String) = File(javaDir, path).readText()

    @Test fun lignesEtBoutonsDansLesDeuxLangues() {
        val lines = ProPaywallText.FEATURES.map { (id, args) -> fr.res(id, args) to en.res(id, args) }
        assertEquals(7, lines.size)
        for ((f, e) in lines) assertNotEquals("traduite : $f", f, e)
        assertEquals("Modifier le patch : adresses, univers, modes, noms", lines[0].first)
        assertEquals("Edit the patch: addresses, universes, modes, names", lines[0].second)
        assertEquals("Import 3D objects (${SceneModelLoader.SUPPORTED_SUMMARY})", lines[4].second)
        // §5.1 et §5.9 : textes EXACTS, prix de la boutique jamais écrit dans le code.
        assertEquals("S'abonner — 25,99\u00A0€ par an", fr.get("pro_subscribe_fmt", "25,99\u00A0€"))
        assertEquals("Subscribe — €25.99 per year", en.get("pro_subscribe_fmt", "€25.99"))
        assertEquals("25,99\u00A0€ par an", fr.get("pro_price_per_year_fmt", "25,99\u00A0€"))
        assertEquals("€25.99 per year", en.get("pro_price_per_year_fmt", "€25.99"))
        assertEquals("Restaurer mes achats" to "Restore Purchases", fr.get("pro_restore") to en.get("pro_restore"))
        assertEquals("Close", en.get("common_close"))
        for (k in listOf("pro_error_play_unavailable", "pro_error_purchase_failed", "pro_error_product_unavailable")) {
            assertNotNull(fr.strings[k]); assertNotEquals(k, fr.get(k), en.get(k))
        }
    }

    /** §5.9 : chaque texte, en français ET en anglais, à la lettre. */
    @Test fun textesDeLAbonnementALaLettre() {
        val n = "\u00A0"
        val expected = mapOf(
            "pro_kind_annual" to ("Abonnement annuel" to "Annual subscription"),
            "pro_free_note" to ("La version gratuite permet toujours d'ouvrir et de consulter tous vos fichiers et projets." to
                "The free version still lets you open and view all your files and projects."),
            "pro_loading_price" to ("Chargement du prix…" to "Loading price…"),
            "pro_sign_in_to_subscribe" to ("Se connecter pour s'abonner" to "Sign in to subscribe"),
            "pro_account_needed" to ("Un compte gratuit est nécessaire pour s'abonner${n}: il porte la version Pro sur tous vos appareils et sur le site, et nous permet de vous envoyer la confirmation et le rappel avant chaque renouvellement." to
                "A free account is required to subscribe: it carries Pro to all your devices and the website, and lets us send your confirmation and a reminder before each renewal."),
            "pro_manage" to ("Gérer ou résilier mon abonnement" to "Manage or cancel subscription"),
            "pro_refund" to ("Demander un remboursement" to "Request a refund"),
            "pro_terms_of_use" to ("Conditions d'utilisation" to "Terms of Use"),
            "pro_privacy_policy" to ("Confidentialité" to "Privacy Policy"),
            "pro_verify_email_to_subscribe" to ("Vérifiez votre adresse e-mail pour vous abonner${n}: elle reçoit la confirmation et le rappel avant chaque renouvellement." to
                "Verify your email address to subscribe: it receives your confirmation and the reminder before each renewal."),
            "pro_resend_verification" to ("Renvoyer l'e-mail de vérification" to "Resend verification email"),
            "account_email_unverified" to ("Adresse e-mail non vérifiée${n}: vous risquez de ne pas recevoir la confirmation et le rappel avant reconduction." to
                "Email address not verified: you may not receive your confirmation and renewal reminder."),
            "pro_pending" to ("Achat en attente d'approbation${n}: la version Pro s'activera dès sa confirmation." to
                "Purchase pending approval: Pro will activate once it is confirmed."),
            "pro_link_owned_by_other_account" to ("Cet abonnement est déjà rattaché à un autre compte MVR Viewer. Connectez-vous avec ce compte pour retrouver la version Pro sur vos autres appareils." to
                "This subscription is already linked to another MVR Viewer account. Sign in with that account to get Pro on your other devices."),
            "pro_link_revoked" to ("Cet abonnement a été remboursé ou révoqué${n}: il ne donne plus accès à la version Pro." to
                "This subscription was refunded or revoked and no longer unlocks Pro."),
            "pro_link_invalid_purchase" to ("Cet abonnement n'a pas pu être vérifié." to "This subscription could not be verified."),
            "pro_link_test_purchase_refused" to ("Les achats de test ne sont pas acceptés." to "Test purchases are not accepted."),
            "pro_subscribe_to_pro" to ("S'abonner à la version Pro" to "Subscribe to Pro"),
            "pro_subscribe_again" to ("Se réabonner" to "Subscribe again"),
            "account_delete_subscription_warning" to ("Supprimer votre compte ne résilie pas votre abonnement MVR Viewer Pro${n}: la boutique où vous l'avez souscrit continuera de le facturer. Résiliez-le d'abord avec « Gérer ou résilier mon abonnement ». Si vous supprimez votre compte sans résilier, nous gardons seulement votre adresse e-mail, jusqu'à la fin de l'abonnement, pour vous envoyer les messages que la loi impose (rappel avant reconduction, accusé de résiliation)." to
                "Deleting your account does not cancel your MVR Viewer Pro subscription: the store where you subscribed will keep billing it. Cancel it first with “Manage or cancel subscription”. If you delete your account without cancelling, we keep only your email address, until the subscription ends, to send you the messages required by law (renewal reminder, cancellation acknowledgement).")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, fr.get(k))
            assertEquals("en $k", v.second, en.get(k))
        }
    }

    /** Typographie française : espace insécable avant « : ; ? ! » dans les nouveaux textes. */
    @Test fun espacesInsecablesDansLesTextesFrancaisDeLAbonnement() {
        val keys = fr.strings.keys.filter {
            (it.startsWith("pro_") && it !in setOf("pro_pitch", "pro_feature_patch")) ||
                it in setOf("account_email_unverified", "account_delete_subscription_warning")
        }
        assertTrue(keys.size > 40)
        for (k in keys) {
            val v = fr.get(k)
            assertFalse("$k : espace ordinaire avant une ponctuation double", Regex(" [:;?!]").containsMatchIn(v))
            assertFalse("$k : ponctuation double collée", Regex("[^\\s\u00A0][:;?!](\\s|$)").containsMatchIn(v))
        }
    }
    @Test fun phraseDeLaRaisonTraduite() {
        val id = ProPaywallText.reasonRes("modifier le patch")!!
        assertEquals("Pour modifier le patch, il faut la version Pro.",
            fr.get("pro_paywall_reason_fmt", fr.res(id, emptyList())))
        assertEquals("To edit the patch, you need the Pro version.",
            en.get("pro_paywall_reason_fmt", en.res(id, emptyList())))
        assertEquals("To calibrate the GPS position, you need the Pro version.",
            en.get("pro_paywall_reason_fmt", en.res(ProPaywallText.reasonRes(GeoPanelContent.PRO_REASON)!!, emptyList())))
    }

    /**
     * Toute raison écrite dans le code (littéral passé à `gate` / `gatedSetter`, ou
     * constante `…PRO_REASON`) a sa traduction ; son texte français EST la clé.
     */
    @Test fun chaqueRaisonDuCodeEstTraduite() {
        val sources = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        val call = Regex("""(?:ProAccess\.gate|ProAccess\.gatedSetter|\bgatedSetter)\(\s*([^,){]+)""")
        val literal = Regex("^\"([^\"]+)\"$")
        val reasons = HashSet<String>()
        val symbols = HashSet<String>()
        for (s in sources) for (m in call.findAll(s)) {
            val arg = m.groupValues[1].trim()
            if (arg.startsWith("reason: String")) continue // déclarations
            val text = literal.find(arg)?.groupValues?.get(1)
            if (text != null) reasons += text else symbols += arg
        }
        for (s in sources) for (m in Regex("""const val \w*PRO_REASON = "([^"]+)"""").findAll(s)) reasons += m.groupValues[1]
        // Arguments non littéraux : uniquement les constantes ci-dessus (ou `reason` = RefPlanPanelRules.PRO_REASON).
        assertEquals(setOf("reason", "GeoPanelContent.PRO_REASON", "RefPlanPanelRules.IMPORT_PRO_REASON",
            "RefPlanPanelRules.LAYERS_PRO_REASON"), symbols)
        assertTrue(src("ui/RefPlanPanel.kt").contains("val reason = RefPlanPanelRules.PRO_REASON"))
        assertTrue("au moins les 17 raisons connues", reasons.size >= 17)
        for (r in reasons) {
            val id = ProPaywallText.reasonRes(r)
            assertNotNull("raison sans traduction : « $r »", id)
            assertEquals("la clé est le texte français", r, fr.res(id!!, emptyList()))
            assertNotEquals("traduite en anglais : « $r »", r, en.res(id, emptyList()))
        }
    }

    /**
     * PHRASE EXACTE sous le bouton, Google Play (SPEC-abonnement §5.2, décidée le
     * 05/11) : courte, AVEC renonciation, « avant l'échéance » (pas de délai de 24 h,
     * propre à Apple) ; « TTC » / « VAT included » seulement dans l'EEE, au
     * Royaume-Uni et en Suisse ; posée DIRECTEMENT sous le bouton d'abonnement,
     * avant les liens légaux et « Restaurer mes achats » ; et ce bouton est le SEUL
     * endroit de l'app qui lance un achat.
     */
    @Test fun phraseAndroidSousLeBoutonALaLettre() {
        val n = "\u00A0"
        val price = "25,99${n}€"
        assertEquals("Abonnement annuel à renouvellement automatique${n}: 25,99${n}€${n}TTC par an. Sauf " +
            "résiliation avant l'échéance, il est reconduit pour un an au prix alors en vigueur, toute hausse " +
            "vous étant annoncée à l'avance. Résiliable à tout moment dans Google Play › Paiements et " +
            "abonnements › Abonnements${n}; l'accès Pro reste ouvert jusqu'à la fin de l'année payée. En vous " +
            "abonnant, vous demandez l'accès immédiat à MVR Viewer Pro et reconnaissez perdre votre droit de " +
            "rétractation de 14 jours dès son activation.",
            SubscriptionTexts.terms(price, vatIncluded = true, t = fr.proTexts()))
        assertEquals("Abonnement annuel à renouvellement automatique${n}: 25,99${n}€ par an. Sauf résiliation avant",
            SubscriptionTexts.terms(price, vatIncluded = false, t = fr.proTexts()).substringBefore(" l'échéance"))
        assertEquals("Auto-renewing annual subscription: €25.99 per year, VAT included. Unless cancelled before " +
            "the end of the current period, it renews for one year at the then-current price; any increase is " +
            "announced in advance. Cancel anytime in Google Play › Payments & subscriptions › Subscriptions; Pro " +
            "access remains until the end of the paid year. By subscribing, you request immediate access to MVR " +
            "Viewer Pro and acknowledge that you lose your 14-day right of withdrawal once it is activated.",
            SubscriptionTexts.terms("€25.99", vatIncluded = true, t = en.proTexts()))
        assertTrue(SubscriptionTexts.terms("€25.99", vatIncluded = false, t = en.proTexts())
            .startsWith("Auto-renewing annual subscription: €25.99 per year. Unless cancelled before the end"))
        for (r in listOf(fr, en)) for (k in listOf("pro_terms_vat_fmt", "pro_terms_fmt")) {
            val v = r.get(k)
            assertFalse("$k : pas de délai de 24 h sur Google Play", v.contains("24"))
            assertFalse("$k : jamais Apple", v.contains("Apple") || v.contains("Réglages") || v.contains("Settings"))
        }

        val paywall = src("ui/ProPaywall.kt")
        val ready = paywall.indexOf("SubscribeRequirement.READY -> SubscribeButton(")
        val terms = paywall.indexOf("SubscriptionTexts.terms(")
        val legal = paywall.indexOf("termsAndPrivacyLine(ctx), style")
        val restore = paywall.indexOf("ProAccess.restore(ProAccess.RestoreScreen.PAYWALL)")
        assertTrue("bouton d'abonnement", ready >= 0 && paywall.indexOf("ProAccess.purchase(it)", ready) in ready until terms)
        assertTrue("phrase avant les liens légaux", legal > terms)
        assertTrue("phrase avant « Restaurer mes achats »", restore > terms)
        // Entre le bouton et la phrase : le seul Text de la phrase, rien d'autre.
        val between = paywall.substring(ready, terms)
        assertEquals(1, Regex("""\bText\(""").findAll(between).count())
        assertFalse("rien entre le bouton et la phrase", between.contains("Spacer(") || between.contains("Column(") ||
            Regex("""\bButton\(""").containsMatchIn(between))
        assertFalse("écrite en dur", paywall.contains("renouvellement automatique") || paywall.contains("Auto-renewing"))

        val sources = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(javaDir).path to it.readText() }
        assertEquals(setOf("ui/ProPaywall.kt"), sources.filterValues { it.contains("ProAccess.purchase(") }.keys)
        assertEquals(setOf("ui/ProAccess.kt"), sources.filterValues { it.contains("launchBillingFlow") }.keys)
    }

    /** §5.1 ligne 9 bis : identité de l'éditeur près des liens légaux — SEULE mention
     *  de la société dans l'app (« Confess Design seulement là où la loi l'impose »). */
    @Test fun identiteDeLEditeurSurLEcranPro() {
        assertEquals("Éditeur\u00A0: %1\$s · %2\$s · %3\$s", fr.get("pro_publisher_fmt"))
        assertEquals("Publisher: %1\$s · %2\$s · %3\$s", en.get("pro_publisher_fmt"))
        assertEquals("Mentions légales" to "Legal notice", fr.get("pro_publisher_legal_notice") to en.get("pro_publisher_legal_notice"))
        assertEquals("Éditeur\u00A0: Confess Design (SASU) · support@mvrviewer.com · Mentions légales",
            fr.get("pro_publisher_fmt", com.minou.mvrviewer.LegalLinks.PUBLISHER_NAME,
                com.minou.mvrviewer.LegalLinks.SUPPORT_EMAIL, fr.get("pro_publisher_legal_notice")))
        val main = javaDir.parentFile.parentFile.parentFile.parentFile // src/main
        val mentions = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }
            .filter { it.readText().contains("Confess") }.map { it.name }.toList()
        assertEquals(listOf("LegalLinks.kt"), mentions)
        assertTrue(src("ui/ProPaywall.kt").contains("publisherLine(ctx)"))
    }

    /** Plus aucune trace de l'achat unique (§5.1 « Retirés »). */
    @Test fun plusAucunAchatUnique() {
        for (r in listOf(fr, en)) for ((k, v) in r.strings) {
            for (w in listOf("Achat unique", "One-time purchase", "Débloquer la version Pro", "Unlock Pro", "à vie",
                "lifetime", "prix de lancement", "launch price", "29,99", "29.99")) {
                assertFalse("$k : « $w »", v.contains(w, ignoreCase = true))
            }
        }
        for (k in listOf("pro_unlock", "pro_unlock_price_fmt", "pro_one_time_purchase", "pro_purchase_withdrawal_waiver",
            "pro_active")) {
            assertNull("ancienne clé $k", fr.strings[k])
            assertNull("ancienne clé $k", en.strings[k])
        }
    }

    /** Jamais de prix barré ni d'« ancien prix » (pratique commerciale trompeuse). */
    @Test fun aucunPrixBarre() {
        val sources = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }
        for (s in sources) {
            assertFalse("texte barré", s.contains("LineThrough") || s.contains("StrikethroughSpan"))
        }
        for (r in listOf(fr, en)) for ((k, v) in r.strings) if (k.startsWith("pro_")) {
            for (w in listOf("au lieu de", "ancien prix", "prix normal", "instead of", "regular price", "usual price")) {
                assertFalse("$k : « $w »", v.contains(w, ignoreCase = true))
            }
        }
    }

    @Test fun plusAucunTexteEnDurDansLEcranDAchat() {
        val paywall = src("ui/ProPaywall.kt")
        for (s in listOf("\"MVR Viewer Pro\"", "\"Fermer\"", "Pour \$reason", "\"Modifier le patch", "par an\"",
            "\"S'abonner", "\"Restaurer mes achats\"", "\"Abonnement annuel\"", "Confess")) {
            assertFalse("écrit en dur : $s", paywall.contains(s))
        }
        val access = src("ui/ProAccess.kt")
        for (s in listOf("\"Google Play indisponible.\"", "n'a pas abouti", "Le produit n'est pas disponible")) {
            assertFalse("écrit en dur : $s", access.contains(s))
        }
    }
}
