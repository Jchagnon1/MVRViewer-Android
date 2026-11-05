package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.SceneModelLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Écran d'achat Pro EN DEUX LANGUES : plus aucun texte écrit en dur dans
 * ProPaywall.kt ; chaque ligne existe en français (values/) ET en anglais
 * (values-en/) ; chaque raison passée à la garde Pro a sa traduction (sinon
 * « To modifier le patch, you need… » en anglais).
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
        assertEquals("Débloquer la version Pro — 29,99 €", fr.get("pro_unlock_price_fmt", "29,99 €"))
        assertEquals("Unlock Pro — €29.99", en.get("pro_unlock_price_fmt", "€29.99"))
        assertEquals("Unlock Pro", en.get("pro_unlock"))
        assertEquals("Restore a purchase", en.get("pro_restore"))
        assertEquals("Close", en.get("common_close"))
        assertTrue(fr.get("pro_one_time_purchase").startsWith("Achat unique, sans abonnement."))
        assertTrue(en.get("pro_one_time_purchase").startsWith("One-time purchase, no subscription."))
        assertTrue(en.get("pro_paywall_pitch").startsWith("Reading a show stays completely free"))
        for (k in listOf("pro_error_play_unavailable", "pro_error_purchase_failed", "pro_error_product_unavailable")) {
            assertNotNull(fr.strings[k]); assertNotEquals(k, fr.get(k), en.get(k))
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
     * Renonciation au droit de rétractation (CGU §6.7) : la phrase EXACTE, commune à
     * iOS et Android, en français ET en anglais, posée JUSTE SOUS le bouton qui lance
     * Google Play (rien entre les deux, avant « Restaurer un achat ») ; et ce bouton est
     * le SEUL endroit de l'app qui lance un achat (sinon il faudrait la phrase ailleurs).
     */
    @Test fun renonciationAuDroitDeRetractationSousLeBoutonDAchat() {
        assertEquals("En confirmant l'achat, vous demandez l'accès immédiat à la version Pro et " +
            "renoncez expressément à votre droit de rétractation.", fr.get("pro_purchase_withdrawal_waiver"))
        assertEquals("By confirming the purchase, you request immediate access to the Pro version and " +
            "expressly waive your right of withdrawal.", en.get("pro_purchase_withdrawal_waiver"))

        val paywall = src("ui/ProPaywall.kt")
        val buy = paywall.indexOf("ProAccess.purchase(it)")
        val buyEnd = paywall.indexOf("stringResource(R.string.pro_unlock))", buy)
        val waiver = paywall.indexOf("stringResource(R.string.pro_purchase_withdrawal_waiver)")
        val restore = paywall.indexOf("ProAccess.restore()")
        assertTrue("bouton d'achat", buy >= 0 && buyEnd > buy)
        assertTrue("phrase après le bouton d'achat", waiver > buyEnd)
        assertTrue("phrase avant « Restaurer un achat »", restore > waiver)
        // Entre la fin du bouton d'achat et la phrase : le seul Text de la phrase.
        val between = paywall.substring(buyEnd, waiver)
        assertEquals(1, Regex("""\bText\(""").findAll(between).count())
        assertFalse(between.contains("Button(") || between.contains("Spacer(") || between.contains("Column("))
        assertFalse("écrite en dur", paywall.contains("En confirmant l") || paywall.contains("By confirming"))

        val sources = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(javaDir).path to it.readText() }
        assertEquals(setOf("ui/ProPaywall.kt"), sources.filterValues { it.contains("ProAccess.purchase(") }.keys)
        assertEquals(setOf("ui/ProAccess.kt"), sources.filterValues { it.contains("launchBillingFlow") }.keys)
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
        for (s in listOf("\"MVR Viewer Pro\"", "\"Fermer\"", "Pour \$reason", "Lire un show", "\"Modifier le patch",
            "Débloquer la version Pro", "\"Restaurer un achat\"", "Achat unique")) {
            assertFalse("écrit en dur : $s", paywall.contains(s))
        }
        val access = src("ui/ProAccess.kt")
        for (s in listOf("\"Google Play indisponible.\"", "n'a pas abouti", "Le produit n'est pas disponible")) {
            assertFalse("écrit en dur : $s", access.contains(s))
        }
    }
}
