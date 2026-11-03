package com.minou.mvrviewer.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compte & réglages : ouvrir un sous-volet (« Mot de passe oublié », « Mentions et
 * licences », GDTF Share) puis revenir garde l'adresse saisie et la position de
 * l'écran (défaut relevé à l'émulateur : champ E-mail vidé, écran revenu en haut).
 */
class AccountSettingsStateTest {

    private fun file(vararg candidates: String): File = candidates.map(::File).first { it.isFile }
    private val screen by lazy {
        file("src/main/java/com/minou/mvrviewer/ui/AccountSettingsScreen.kt",
            "app/src/main/java/com/minou/mvrviewer/ui/AccountSettingsScreen.kt").readText()
    }
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    @Test fun formulaireEnregistreSansLeMotDePasse() {
        val form = AccountFormState(email = "jean@example.com").apply {
            password = "secret-de-test"; displayName = "Jean"; isSignUp = true
        }
        val saved = with(AccountFormState.Saver) { scope.save(form) }!!
        assertFalse("le mot de passe ne va jamais dans l'état enregistré", saved.toString().contains("secret-de-test"))
        val back = AccountFormState.Saver.restore(saved)!!
        assertEquals("jean@example.com", back.email)
        assertEquals("Jean", back.displayName)
        assertTrue(back.isSignUp)
        assertEquals("", back.password)
    }

    @Test fun etatTenuAuDessusDesSousVolets() {
        val body = screen.substringAfter("fun AccountSettingsScreen(").substringBefore("@Composable\nprivate fun SectionHeader")
        val firstSubPane = body.indexOf("if (gdtfLogin) {")
        assertTrue(firstSubPane > 0)
        // Défilement et formulaire déclarés AVANT le premier sous-volet (qui sort par `return`).
        val scroll = body.indexOf("val scroll = rememberScrollState()")
        val form = body.indexOf("val form = rememberSaveable(saver = AccountFormState.Saver)")
        assertTrue(scroll in 0 until firstSubPane)
        assertTrue(form in 0 until firstSubPane)
        assertEquals("un seul état de défilement", 1, Regex("rememberScrollState\\(\\)").findAll(body).count())
        assertTrue(body.contains("AccountSection(it, form, onForgotPassword"))
        // La section lit le formulaire tenu au-dessus, plus de `remember` local pour les champs.
        val section = screen.substringAfter("private fun AccountSection(").substringBefore("// MARK: - Version Pro")
        for (f in listOf("email", "password", "displayName", "isSignUp")) {
            assertTrue(f, section.contains("var $f by form::$f"))
        }
        assertFalse(section.contains("var email by remember"))
    }
}
