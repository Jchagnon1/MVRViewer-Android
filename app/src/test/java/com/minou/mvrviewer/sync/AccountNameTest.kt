package com.minou.mvrviewer.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * NOM DU COMPTE à l'inscription et à la connexion, et noms des membres
 * (SPEC-partage-moderation §4, C-tous-12). Le nom affiché dans un projet en part : il
 * doit être le MÊME d'une session à l'autre (sinon §5.8 le réécrit à chaque ouverture)
 * et le même qu'iOS (AccountNameTests, `AccountInfo.fromAuth`) et le web (`signUp`,
 * `accountOf` de src/cloud/sync.js). Une seule découpe des bords : `trimEdges`.
 */
class AccountNameTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun src(path: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/$path"), File("app/src/main/java/com/minou/mvrviewer/$path"))
            .first { it.isFile }.readText()

    // MARK: Inscription : un nom fait seulement de caractères retirés n'est pas un nom

    @Test fun nomEnregistreALInscription() {
        assertEquals("Casse.Device@Exemple.test", ProjectNames.signUpName("   ", "Casse.Device@Exemple.test"))
        assertEquals("bob@exemple.test", ProjectNames.signUpName("", "bob@exemple.test"))
        assertEquals("Zoé (régie)", ProjectNames.signUpName("  Zoé (régie) \n", "zoe@exemple.test"))
        // `ifBlank` gardait ces noms (U+200B, U+FEFF, U+0085 ne sont pas « blancs » pour Kotlin) :
        // le libellé du compte restait invisible.
        for (blank in listOf("​", "﻿", "\u0085", "　​ ", "\u001F ")) {
            assertEquals("bob@exemple.test", ProjectNames.signUpName(blank, "bob@exemple.test"))
        }
        assertEquals("Bob", ProjectNames.signUpName("​Bob﻿", "bob@exemple.test"))
        // Même découpe que le nom affiché : un nom enregistré n'est jamais vide pour §4.
        for (blank in listOf("   ", "　", "​", " \n", "﻿\u0085")) {
            assertFalse(ProjectNames.trimEdges(ProjectNames.signUpName(blank, "Casse.Device@Exemple.test")).isEmpty())
        }
    }

    @Test fun inscriptionDuBouchon_nomInvisible_adresseSaisieEtNomStable() = runBlocking<Unit> {
        val svc = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))
        val typed = "Casse.Device@Exemple.test"
        val info = svc.signUp(typed, "secret123", "​")
        assertEquals(typed, info.displayName)
        assertEquals("Casse.Device", ProjectNames.displayName(info))
        // Session suivante : le nom enregistré est relu, toujours le même.
        svc.signOut()
        val again = svc.signIn(typed.lowercase(), "secret123")
        assertEquals(typed, again.displayName)
        assertEquals("Casse.Device", ProjectNames.displayName(again))
        // Et c'est lui qui part dans le projet (memberNames, §4).
        val p = svc.createProject("key-nom", "Show")
        assertEquals("Casse.Device", p.memberNames?.get(again.uid))
        // Un nom entouré de caractères retirés est enregistré sans eux.
        svc.signOut()
        assertEquals("Alice", svc.signUp("alice@exemple.test", "secret123", "﻿ Alice\u0085").displayName)
    }

    // MARK: Connexion : le nom part de l'adresse RENDUE par Firebase

    @Test fun connexionSansNomFirebase_memeCompteQueLaReprise() {
        val firebaseEmail = "casse.device@exemple.test"
        val signIn = AccountInfo.fromAuth("c", firebaseEmail, null, typedEmail = "Casse.Device@Exemple.test")
        val restored = AccountInfo.fromAuth("c", firebaseEmail, null)
        assertEquals(AccountInfo("c", firebaseEmail, firebaseEmail), signIn)
        assertEquals("connexion et reprise : le même compte", restored, signIn)
        assertEquals("comme le web", "casse.device", ProjectNames.displayName(signIn))
        assertEquals("casse.device", ProjectNames.displayName(restored))
    }

    @Test fun connexion_nomFirebaseGarde_adresseSaisieSeulementSansAdresseFirebase() {
        val named = AccountInfo.fromAuth("c", "casse.device@exemple.test", "Casse.Device@Exemple.test",
            typedEmail = "CASSE.DEVICE@EXEMPLE.TEST")
        assertEquals("le nom enregistré par Firebase, tel quel", "Casse.Device@Exemple.test", named.displayName)
        assertEquals("Casse.Device", ProjectNames.displayName(named))
        assertEquals(AccountInfo("c", "bob@exemple.test", "bob@exemple.test"),
            AccountInfo.fromAuth("c", null, null, typedEmail = "bob@exemple.test"))
        assertEquals("reprise sans adresse (inchangé)", AccountInfo("c", "", ""), AccountInfo.fromAuth("c", null, null))
    }

    /** Le SDK Firebase ne tourne pas en JVM : la forme du code du service est vérifiée. */
    @Test fun serviceFirebaseEtFormulaire_memeRegle() {
        val firebase = src("sync/FirebaseSyncService.kt")
        val restore = firebase.substringAfter("override suspend fun restoreSession()").substringBefore("override suspend fun signUp(")
        val signUp = firebase.substringAfter("override suspend fun signUp(").substringBefore("override suspend fun signIn(")
        val signIn = firebase.substringAfter("override suspend fun signIn(").substringBefore("override suspend fun signOut()")
        assertTrue(restore, restore.contains("AccountInfo.fromAuth(u.uid, u.email, u.displayName)"))
        assertTrue(signIn, signIn.contains("AccountInfo.fromAuth(u.uid, u.email, u.displayName, typedEmail = email)"))
        assertFalse(signIn, signIn.contains("u.displayName ?: email"))
        assertTrue(signUp, signUp.contains("ProjectNames.signUpName(displayName, email)"))
        assertFalse(signUp, signUp.contains("ifBlank"))
        val local = src("sync/LocalSyncService.kt")
        assertTrue(local.contains("ProjectNames.signUpName(displayName, email)"))
        assertFalse(local.contains("displayName.ifBlank"))
        val screen = src("ui/AccountSettingsScreen.kt")
        assertTrue(screen.contains("sync.signUp(email.trim(), password, ProjectNames.trimEdges(displayName))"))
        assertFalse(screen.contains("displayName.trim()"))
        // Plus aucun trim() standard dans le calcul du nom affiché.
        val sharing = src("sync/ProjectSharing.kt")
        val displayName = sharing.substringAfter("fun displayName(displayName: String, email: String): String {")
            .substringBefore("fun displayName(account: AccountInfo)")
        assertFalse(displayName, displayName.contains(".trim()"))
    }

    // MARK: Noms des membres (liste, « Retirer… »)

    @Test fun nomsDesMembres_memesBordsRetires_jamaisUnLibelleInvisible() {
        val p = CloudProject("P", "k", "Show", "a", listOf("a", "b", "c", "d"), 2, null, null, 1.0,
            memberNames = mapOf("a" to "﻿Alice\u0085", "b" to "​", "c" to "Bob", "d" to "​Bob"))
        val rows = ProjectMembers.rows(p, "a", "Membre sans nom").associate { it.uid to it.label }
        assertEquals("Alice", rows["a"])
        assertEquals("Membre sans nom", rows["b"])
        // « U+200B Bob » et « Bob » : le même nom, donc des homonymes.
        assertEquals("Bob · C", rows["c"])
        assertEquals("Bob · D", rows["d"])
        val alone = p.copy(memberUids = listOf("a", "b"))
        assertEquals("Membre sans nom", ProjectMembers.label("b", alone, "Membre sans nom"))
        assertEquals("Alice", ProjectMembers.label("a", alone, "Membre sans nom"))
    }

    // MARK: §5.8 — le nom écrit à l'ouverture est un point fixe

    /**
     * Un nom de plus de 80 unités dont la troncature finit sur une espace : le nom affiché
     * la garde (troncature APRÈS `trimEdges`, comme le web et iOS), et `setMyProjectName`
     * l'écrit telle quelle. Redécoupée à l'écriture, la valeur écrite différerait de
     * celle que [ProjectMembership.nameToWrite] compare : réécriture à chaque ouverture.
     */
    @Test fun nomTronqueSurUneEspace_ecritUneFois() = runBlocking<Unit> {
        val svc = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))
        val long = "a".repeat(79) + " suite"
        val me = svc.signUp("long@exemple.test", "secret123", long)
        assertEquals("a".repeat(79) + " ", ProjectNames.displayName(me))
        val p = svc.createLegacyProject("key-long", "Show")
        assertEquals(ProjectNames.displayName(me), ProjectMembership.nameToWrite(p, me))
        ProjectMembership.openMaintenance(svc, svc.project(p.id)!!, me)
        assertEquals(ProjectNames.displayName(me), svc.peekProject(p.id)!!.memberNames?.get(me.uid))
        assertNull("déjà à jour : aucune réécriture", ProjectMembership.nameToWrite(svc.project(p.id)!!, me))
    }
}
