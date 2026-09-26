package com.minou.mvrviewer.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Suppression de compte (politique Google Play « suppression de compte ») sur le
 * stub [LocalSyncService], instancié SANS Context (arbre sous un dossier
 * temporaire, session en mémoire). Ces tests verrouillent le CONTRAT partagé
 * avec le backend réel : le mot de passe est re-vérifié AVANT toute écriture,
 * un échec laisse le compte intact et connecté, une réussite déconnecte,
 * efface la session persistée, interdit toute reconnexion, retire l'uid des
 * projets partagés sans les détruire (les autres membres les gardent, la
 * PROPRIÉTÉ passe au premier autre membre), supprime les projets dont il était
 * le seul membre et les invitations qu'il avait créées.
 */
class LocalSyncServiceAccountTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun service() = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))

    @Test fun deleteAccountWithCorrectPasswordSignsOutAndForbidsSignIn() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        svc.createProject("key-1", "Projet")
        assertEquals(acc, svc.restoreSession()) // session persistée avant

        svc.deleteAccount("secret123")

        // Plus connecté : la session persistée est effacée, les appels
        // authentifiés refusent, la reconnexion est impossible.
        assertNull(svc.restoreSession())
        assertThrows(SyncException.NotSignedIn::class.java) { runBlocking { svc.listProjects() } }
        assertThrows(SyncException.InvalidCredentials::class.java) {
            runBlocking { svc.signIn("jm@example.com", "secret123") }
        }
        // Le compte n'existe plus : le même e-mail peut se réinscrire, avec un
        // NOUVEL uid, et ne retrouve pas l'ancien projet.
        val again = svc.signUp("jm@example.com", "secret123", "JM")
        assertNotEquals(acc.uid, again.uid)
        assertTrue(svc.listProjects().isEmpty())
    }

    @Test fun deleteAccountWithWrongPasswordLeavesAccountIntact() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        val p = svc.createProject("key-1", "Projet")

        assertThrows(SyncException.InvalidCredentials::class.java) {
            runBlocking { svc.deleteAccount("mauvais") }
        }

        // Toujours connecté, projet toujours à lui, session persistée intacte.
        assertEquals(listOf(p.id), svc.listProjects().map { it.id })
        assertEquals(acc, svc.restoreSession())
        // Et le compte se reconnecte normalement.
        svc.signOut()
        assertEquals(acc.uid, svc.signIn("jm@example.com", "secret123").uid)
    }

    @Test fun deleteAccountKeepsSharedProjectsForOtherMembers() = runBlocking {
        val svc = service()
        val owner = svc.signUp("owner@example.com", "secret123", "Owner")
        val p = svc.createProject("key-1", "Projet partagé")
        val invite = svc.createInvite(p.id)
        svc.signOut()
        val member = svc.signUp("member@example.com", "secret123", "Membre")
        svc.joinProject(invite.code)
        svc.signOut()

        svc.signIn("owner@example.com", "secret123")
        svc.deleteAccount("secret123")

        // Le projet survit pour l'autre membre, sans l'uid supprimé, et ce
        // membre en devient le propriétaire (transfert de propriété).
        val kept = svc.project(p.id)!!
        assertEquals(listOf(member.uid), kept.memberUids)
        assertEquals(member.uid, kept.ownerUid)
        svc.signIn("member@example.com", "secret123")
        assertEquals(listOf(p.id), svc.listProjects().map { it.id })
        assertTrue(owner.uid !in svc.listProjects().single().memberUids)
    }

    @Test fun deleteAccountTransfersOwnershipToFirstOtherMember() = runBlocking<Unit> {
        val svc = service()
        val owner = svc.signUp("owner@example.com", "secret123", "Owner")
        val p = svc.createProject("key-1", "Projet partagé")
        val invite = svc.createInvite(p.id)
        svc.signOut()
        val first = svc.signUp("first@example.com", "secret123", "Premier")
        svc.joinProject(invite.code)
        svc.signOut()
        val second = svc.signUp("second@example.com", "secret123", "Second")
        svc.joinProject(invite.code)
        svc.signOut()

        svc.signIn("owner@example.com", "secret123")
        svc.deleteAccount("secret123")

        // Propriété au PREMIER autre membre (ordre de memberUids), les autres
        // membres conservés dans l'ordre, l'ancien propriétaire retiré.
        val kept = svc.project(p.id)!!
        assertEquals(first.uid, kept.ownerUid)
        assertEquals(listOf(first.uid, second.uid), kept.memberUids)
        assertTrue(owner.uid !in kept.memberUids)
        // L'invitation créée par le compte supprimé n'existe plus.
        svc.signUp("late@example.com", "secret123", "Retardataire")
        assertThrows(SyncException.InviteInvalidOrExpired::class.java) {
            runBlocking { svc.joinProject(invite.code) }
        }
    }

    @Test fun deleteAccountOfPlainMemberKeepsOwner() = runBlocking {
        val svc = service()
        val owner = svc.signUp("owner@example.com", "secret123", "Owner")
        val p = svc.createProject("key-1", "Projet partagé")
        val invite = svc.createInvite(p.id)
        svc.signOut()
        svc.signUp("member@example.com", "secret123", "Membre")
        svc.joinProject(invite.code)

        svc.deleteAccount("secret123")

        // Un simple membre qui part ne change pas le propriétaire ; l'invitation
        // du propriétaire reste valable.
        val kept = svc.project(p.id)!!
        assertEquals(owner.uid, kept.ownerUid)
        assertEquals(listOf(owner.uid), kept.memberUids)
        svc.signUp("other@example.com", "secret123", "Autre")
        assertEquals(p.id, svc.joinProject(invite.code).id)
    }

    @Test fun deleteAccountRemovesProjectsWhereSoleMember() = runBlocking<Unit> {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        val p = svc.createProject("key-1", "Projet solo")
        val invite = svc.createInvite(p.id)
        svc.appendAudit(p.id, listOf(AuditEntry(
            id = "a1", epoch = 1.0, authorUid = acc.uid, authorName = "JM", kind = "patch",
            target = "Projecteur 1", field = "Adresse", oldValue = "1.1", newValue = "1.2"
        )))

        svc.deleteAccount("secret123")

        // Seul membre ET propriétaire : le projet disparaît entièrement (audit
        // compris) et son code d'invitation ne mène plus nulle part.
        assertNull(svc.project(p.id))
        assertTrue(svc.fetchAudit(p.id).isEmpty())
        svc.signUp("other@example.com", "secret123", "Autre")
        assertThrows(SyncException.InviteInvalidOrExpired::class.java) {
            runBlocking { svc.joinProject(invite.code) }
        }
    }

    @Test fun deleteAccountRequiresSignedInUser() {
        val svc = service()
        assertThrows(SyncException.NotSignedIn::class.java) {
            runBlocking { svc.deleteAccount("secret123") }
        }
    }
}
