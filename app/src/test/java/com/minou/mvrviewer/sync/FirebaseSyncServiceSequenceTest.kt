package com.minou.mvrviewer.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Forme des séquences du VRAI service ([FirebaseSyncService]), que la JVM ne peut pas
 * exécuter (SDK Firebase) — le bouchon [LocalSyncService] échoue hors ligne sans délai
 * et ne voit donc pas ces écarts. Défauts reproduits sur les émulateurs Firebase locaux
 * (projet demo-mvrviewer, règles du dépôt iOS 51863ef), appareil en mode avion.
 */
class FirebaseSyncServiceSequenceTest {

    private val code: String by lazy {
        listOf(
            File("src/main/java/com/minou/mvrviewer/sync/FirebaseSyncService.kt"),
            File("app/src/main/java/com/minou/mvrviewer/sync/FirebaseSyncService.kt")
        ).first { it.isFile }.readText()
    }

    /** Corps d'une fonction : de sa déclaration à la déclaration `override` / `private` suivante. */
    private fun body(decl: String): String {
        val i = code.indexOf(decl)
        assertTrue("introuvable : $decl", i >= 0)
        val next = Regex("\n    (override |private |/\\*\\*)").find(code, i + decl.length)?.range?.first ?: code.length
        return code.substring(i, next)
    }

    private fun assertBefore(text: String, first: String, then: String) {
        val a = text.indexOf(first)
        val b = text.indexOf(then)
        assertTrue("introuvable : $first", a >= 0)
        assertTrue("introuvable : $then", b >= 0)
        assertTrue("« $first » doit précéder « $then »", a < b)
    }

    /**
     * SPEC-partage-moderation §8 : hors ligne, quitter, retirer, autoriser à revenir et
     * nouveau code échouent TOUT DE SUITE. `runTransaction` du SDK rejoue pourtant la
     * transaction sur UNAVAILABLE (5 essais, attente croissante) : 8 à 13 s mesurées,
     * fenêtre « Partager le projet » bloquée sur sa roue. Chaque transaction commence
     * donc par une lecture SERVEUR du projet, bornée, qui échoue en 3 s environ sans
     * rien mettre en file (même schéma que la suppression du projet et que iOS ; mesuré
     * après correctif : 3,1 à 3,6 s, suppression du projet 3,8 s).
     */
    @Test fun chaqueTransactionDeModerationCommenceParUneLectureServeurDuProjet() {
        val helper = body("private suspend fun <T> transaction(")
        assertTrue(helper, helper.contains("reading: DocumentReference"))
        assertBefore(helper, "reading.get(Source.SERVER).await()", "db.runTransaction")
        assertTrue("lecture et transaction dans la même borne", helper.contains("bounded {"))
        for (decl in listOf(
            "override suspend fun leaveProject(", "override suspend fun removeMember(",
            "override suspend fun allowBack(", "override suspend fun rotateInviteCode("
        )) {
            val b = body(decl)
            assertTrue("$decl : transaction(reading = ref)", b.contains("transaction(reading = ref) { txn ->"))
            assertFalse("$decl : aucune transaction sans lecture serveur", b.contains("transaction { txn ->"))
        }
        // Un seul appel direct au SDK pour la modération : celui de l'aide (publishMVRVersion,
        // Pro, a le sien).
        assertEquals(2, Regex("db\\.runTransaction").findAll(code).count())
        assertTrue(body("override suspend fun publishMVRVersion(").contains("db.runTransaction"))
    }

    /**
     * SPEC-partage-moderation §5.9 (N9) et §5.10 (N10, « exactement les étapes 2 à 4 de
     * §5.9 ») : 1. lecture serveur du projet ; 2. fichiers Storage ; 3. lectures des
     * sections, du journal et des invitations ; 4. lots. Même ordre que le web
     * (`sync.js`) et iOS (`FirebaseSyncService.swift`) — §5 : « identiques sur les trois
     * plateformes ». Android lisait avant de supprimer les fichiers.
     */
    @Test fun supprimerLeProjetFichiersAvantLesLecturesDesSousDocuments() {
        val n9 = body("override suspend fun deleteProject(")
        assertBefore(n9, "bounded { ref.get(Source.SERVER).await() }", "deleteBlobsBestEffort(projectId)")
        assertBefore(n9, "deleteBlobsBestEffort(projectId)", "projectDeletionUnits(")
        assertBefore(n9, "projectDeletionUnits(", "commitInLots(")
        val n10 = body("override suspend fun deleteAccount(")
        assertBefore(n10, "deleteBlobsBestEffort(doc.id)", "projectDeletionUnits(doc.reference")
        assertBefore(n10, "projectDeletionUnits(doc.reference", "commitInLots(groups)")
        assertEquals("fichiers supprimés une seule fois par projet solo", 1,
            Regex("deleteBlobsBestEffort\\(").findAll(n10).count())
    }
}
