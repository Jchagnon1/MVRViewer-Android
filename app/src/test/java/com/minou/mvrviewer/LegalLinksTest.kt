package com.minou.mvrviewer

import com.minou.mvrviewer.SignUpConsent.Segment
import com.minou.mvrviewer.sync.PurchaseLink
import com.minou.mvrviewer.ui.AccountSettingsSection
import com.minou.mvrviewer.ui.AccountSettingsSections
import com.minou.mvrviewer.ui.TestRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Liens légaux de Compte & réglages › À propos et de « Créer le compte » (guide
 * de mise en vente C3) : UNE base (https://mvrviewer.com), chemins selon la langue
 * DE L'APP, phrase d'inscription aux deux liens, version lue d'un seul tenant.
 */
class LegalLinksTest {

    private fun dir(vararg candidates: String): File = candidates.map(::File).first { it.isDirectory }
    private val main by lazy { dir("src/main", "app/src/main") }

    @Test fun cheminsSelonLaLangueDeLApp() {
        assertEquals("https://mvrviewer.com/confidentialite", LegalLinks.url(LegalPage.PRIVACY, "fr"))
        assertEquals("https://mvrviewer.com/cgu", LegalLinks.url(LegalPage.TERMS, "fr"))
        assertEquals("https://mvrviewer.com/mentions-legales", LegalLinks.url(LegalPage.LEGAL_NOTICE, "fr"))
        assertEquals("https://mvrviewer.com/support", LegalLinks.url(LegalPage.SUPPORT, "fr"))
        assertEquals("https://mvrviewer.com/privacy", LegalLinks.url(LegalPage.PRIVACY, "en"))
        assertEquals("https://mvrviewer.com/terms", LegalLinks.url(LegalPage.TERMS, "en"))
        assertEquals("https://mvrviewer.com/legal-notice", LegalLinks.url(LegalPage.LEGAL_NOTICE, "en"))
        assertEquals("https://mvrviewer.com/support", LegalLinks.url(LegalPage.SUPPORT, "en"))
        // Toute autre langue → anglais (le site n'existe qu'en ces deux langues).
        assertEquals("https://mvrviewer.com/privacy", LegalLinks.url(LegalPage.PRIVACY, "de"))
        for (fr in listOf("fr", "fr-CA", "fr_FR", "FR")) assertTrue(fr, LegalLinks.isFrench(fr))
        for (other in listOf("en", "en-GB", "de", "", "frc")) assertFalse(other, LegalLinks.isFrench(other))
    }

    @Test fun laLangueDeLAppEstCelleDesRessources() {
        // Android résout `app_language` comme les autres textes : « fr » par défaut
        // (values/), « en » pour l'anglais (values-en/).
        assertEquals("fr", TestRes.FR.get("app_language"))
        assertEquals("en", TestRes.EN.get("app_language"))
        assertEquals("fr", AuthEmailLanguage.code(TestRes.FR.get("app_language")))
        assertEquals("en", AuthEmailLanguage.code(TestRes.EN.get("app_language")))
    }

    /** La base et les chemins ne s'écrivent QUE dans LegalLinks.kt ; l'API d'achat garde son adresse. */
    @Test fun uneSeuleBaseEtDesCheminsEcritsAUnSeulEndroit() {
        val legal = File(main, "java/com/minou/mvrviewer/LegalLinks.kt")
        assertTrue(legal.readText().contains("const val BASE_URL = \"https://mvrviewer.com\""))
        val forbidden = listOf("://mvrviewer.com", "://www.mvrviewer.com") +
            listOf("/confidentialite", "/privacy", "/cgu", "/terms", "/mentions-legales", "/legal-notice", "/support")
                .map { "\"$it\"" }
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml", "json") && it.canonicalPath != legal.canonicalPath }
            .flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name} : $it" } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
        assertEquals("https://mvrviewer-app.web.app/api/purchase/verify", PurchaseLink.ENDPOINT)
    }

    @Test fun phraseDInscriptionAvecLesDeuxLiens() {
        for (res in listOf(TestRes.FR, TestRes.EN)) {
            val segments = SignUpConsent.segments(
                res.get("signup_consent_fmt"), res.get("signup_consent_terms"), res.get("signup_consent_privacy")
            )
            val links = segments.filterIsInstance<Segment.Link>()
            assertEquals(listOf(LegalPage.TERMS, LegalPage.PRIVACY), links.map { it.page })
            val sentence = segments.joinToString("") { if (it is Segment.Plain) it.text else (it as Segment.Link).text }
            assertEquals(
                String.format(res.locale, res.get("signup_consent_fmt"), res.get("signup_consent_terms"),
                    res.get("signup_consent_privacy")),
                sentence
            )
        }
        assertEquals(
            "En créant un compte, vous acceptez les conditions d'utilisation et la politique de confidentialité.",
            TestRes.FR.get("signup_consent_fmt", TestRes.FR.get("signup_consent_terms"), TestRes.FR.get("signup_consent_privacy"))
        )
        assertEquals(
            "By creating an account, you agree to the Terms of Use and the Privacy Policy.",
            TestRes.EN.get("signup_consent_fmt", TestRes.EN.get("signup_consent_terms"), TestRes.EN.get("signup_consent_privacy"))
        )
        // Un gabarit qui place les liens dans l'autre ordre reste juste.
        val swapped = SignUpConsent.segments("%2\$s puis %1\$s", "T", "P")
        assertEquals(listOf(Segment.Link("P", LegalPage.PRIVACY), Segment.Plain(" puis "), Segment.Link("T", LegalPage.TERMS)), swapped)
    }

    @Test fun versionLueDUnSeulTenant() {
        assertEquals("1.10.0 (21)", AppVersionInfo.display("1.10.0", 21))
        assertEquals("21", AppVersionInfo.display("21", 21))
        assertEquals("1.10.0", AppVersionInfo.display(" 1.10.0 ", null))
        assertEquals("21", AppVersionInfo.display(null, 21))
        assertEquals("", AppVersionInfo.display(null, null))
        // La version de l'envoi (build.gradle.kts) donne bien « 1.10.0 (21) ».
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText()
        val name = Regex("versionName = \"([^\"]+)\"").find(gradle)!!.groupValues[1]
        val code = Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1].toLong()
        assertEquals("1.10.0 (21)", AppVersionInfo.display(name, code))
        assertEquals("Version 1.10.0 (21)", TestRes.FR.get("about_version_a11y_fmt", "1.10.0 (21)"))
        assertEquals("Version 1.10.0 (21)", TestRes.EN.get("about_version_a11y_fmt", "1.10.0 (21)"))
    }

    @Test fun aProposToujoursPresentEtTextes() {
        for (hasSync in listOf(true, false)) {
            assertEquals(AccountSettingsSection.ABOUT, AccountSettingsSections.of(hasSync).last())
        }
        val expected = mapOf(
            "account_section_about" to ("À propos" to "About"),
            "legal_privacy" to ("Confidentialité" to "Privacy"),
            "legal_terms" to ("Conditions d'utilisation" to "Terms of Use"),
            "legal_notice" to ("Mentions légales" to "Legal"),
            "legal_support" to ("Assistance" to "Support"),
            "about_licenses" to ("Mentions et licences" to "Notices & licences"),
            "licenses_title" to ("Mentions et licences" to "Notices & licences"),
            "about_version" to ("Version" to "Version")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, TestRes.FR.get(k))
            assertEquals("en $k", v.second, TestRes.EN.get(k))
        }
        // Les quatre pages, dans l'ordre d'iOS.
        assertEquals(listOf(LegalPage.PRIVACY, LegalPage.TERMS, LegalPage.LEGAL_NOTICE, LegalPage.SUPPORT), LegalPage.entries)
    }
}
