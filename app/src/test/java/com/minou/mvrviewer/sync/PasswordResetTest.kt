package com.minou.mvrviewer.sync

import com.minou.mvrviewer.AuthEmailLanguage
import com.minou.mvrviewer.ui.TestRes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * « Mot de passe oublié ? » (guide de mise en vente C6), sur le stub
 * [LocalSyncService] — même contrat que Firebase : réponse NEUTRE (réussite que
 * le compte existe ou non), adresse invalide, hors ligne, trop de demandes ;
 * l'état de connexion n'est jamais touché ; e-mail dans la langue DE L'APP ;
 * aucune garde Pro.
 */
class PasswordResetTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun service() = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))

    @Test fun reponseNeutreQueLeCompteExisteOuNon() = runBlocking {
        val svc = service()
        svc.signUp("jm@example.com", "secret123", "JM")
        svc.signOut()
        // Compte existant : réussite, un e-mail « part ».
        svc.sendPasswordReset("JM@example.com")
        assertEquals(listOf("jm@example.com"), svc.sentPasswordResets)
        // Aucun compte : MÊME réussite (aucune exception), rien ne part.
        svc.sendPasswordReset("inconnu@example.com")
        assertEquals(listOf("jm@example.com"), svc.sentPasswordResets)
        // Ni connexion ni session créées par la demande.
        assertNull(svc.restoreSession())
    }

    @Test fun neTouchePasALaConnexion() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        svc.sendPasswordReset("jm@example.com")
        assertEquals(acc, svc.restoreSession())
        assertTrue(svc.listProjects().isEmpty()) // toujours connecté : les appels authentifiés passent
    }

    @Test fun adresseInvalideHorsLigneEtTropDeDemandes() = runBlocking {
        val svc = service()
        for (bad in listOf("", "jm", "jm@", "@example.com", "jm@example", "jm@example.", "j m@example.com", "a@b@c.d")) {
            assertThrows(bad, SyncException.InvalidEmail::class.java) { runBlocking { svc.sendPasswordReset(bad) } }
        }
        svc.simulatesOffline = true
        assertThrows(SyncException.NetworkUnavailable::class.java) {
            runBlocking { svc.sendPasswordReset("jm@example.com") }
        }
        svc.simulatesOffline = false
        var now = 1_000_000L
        svc.clock = { now }
        repeat(LocalSyncService.PASSWORD_RESET_LIMIT) { svc.sendPasswordReset("x@example.com") }
        // Au-delà de la limite : refus, que le compte existe ou non…
        assertThrows(SyncException.TooManyRequests::class.java) {
            runBlocking { svc.sendPasswordReset("X@example.com") }
        }
        // … par adresse : une autre passe ;
        svc.sendPasswordReset("y@example.com")
        // … et la fenêtre passée, la même adresse repasse.
        now += LocalSyncService.PASSWORD_RESET_WINDOW_MS
        svc.sendPasswordReset("x@example.com")
    }

    @Test fun formeDUneAdresse() {
        for (ok in listOf("jm@example.com", "j.m+tag@sub.example.fr", "a@b.c")) assertTrue(ok, EmailAddress.isPlausible(ok))
        for (bad in listOf("", "jm@example", "jm@.com", "jm@example..com", " jm@example.com", "jm@exam ple.com"))
            assertFalse(bad, EmailAddress.isPlausible(bad))
    }

    @Test fun langueDeLEMailEtMessagesFrEn() {
        assertEquals("fr", AuthEmailLanguage.code("fr"))
        assertEquals("fr", AuthEmailLanguage.code("fr-BE"))
        assertEquals("en", AuthEmailLanguage.code("en"))
        assertEquals("en", AuthEmailLanguage.code("es"))
        val fr = TestRes.FR
        val en = TestRes.EN
        assertEquals("Adresse e-mail invalide.", fr.res(SyncException.InvalidEmail.messageRes!!, emptyList()))
        assertEquals("Invalid email address.", en.res(SyncException.InvalidEmail.messageRes!!, emptyList()))
        assertEquals("Trop de tentatives. Patientez quelques minutes puis réessayez.",
            fr.res(SyncException.TooManyRequests.messageRes!!, emptyList()))
        assertEquals("Too many attempts. Wait a few minutes, then try again.",
            en.res(SyncException.TooManyRequests.messageRes!!, emptyList()))
        // Hors ligne : le message réseau existant, dans les deux langues.
        assertTrue(fr.res(SyncException.NetworkUnavailable.messageRes!!, emptyList()).startsWith("Connexion réseau indisponible"))
        assertTrue(en.res(SyncException.NetworkUnavailable.messageRes!!, emptyList()).startsWith("Network unavailable"))
        val expected = mapOf(
            "password_forgot" to ("Mot de passe oublié ?" to "Forgot your password?"),
            "password_reset_title" to ("Mot de passe oublié" to "Forgot password"),
            "password_reset_send" to ("Envoyer le lien" to "Send the link"),
            "password_reset_sent" to (
                "Si un compte existe pour cette adresse, vous allez recevoir un e-mail avec un lien pour choisir " +
                    "un nouveau mot de passe. Pensez à regarder dans les courriers indésirables." to
                    "If an account exists for this address, you'll receive an email with a link to choose a new " +
                    "password. Remember to check your spam folder.")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, fr.get(k))
            assertEquals("en $k", v.second, en.get(k))
        }
    }

    /** Gratuit : ni l'écran ni le chemin d'envoi ne passent par la garde Pro ; Firebase reçoit la langue de l'app. */
    @Test fun gratuitEtDansLaLangueDeLApp() {
        fun src(path: String) = listOf(File("src/main/java/com/minou/mvrviewer/$path"),
            File("app/src/main/java/com/minou/mvrviewer/$path")).first { it.isFile }.readText()
        assertFalse(src("ui/PasswordResetPane.kt").contains("ProAccess"))
        val vm = src("sync/SyncViewModel.kt")
        val body = vm.substringAfter("suspend fun sendPasswordReset(").substringBefore("\n    }\n")
        assertFalse(body.contains("ProAccess"))
        val firebase = src("sync/FirebaseSyncService.kt")
        assertTrue(firebase.contains("auth.setLanguageCode(AuthEmailLanguage.current(appContext))"))
        assertFalse("plus de langue figée", firebase.contains("setLanguageCode(\"fr\")"))
        assertTrue(firebase.substringAfter("override suspend fun sendPasswordReset(").contains("applyEmailLanguage(auth)"))
    }
}
