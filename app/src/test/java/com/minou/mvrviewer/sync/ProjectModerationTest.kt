package com.minou.mvrviewer.sync

import com.minou.mvrviewer.ui.TestRes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Briques du ViewModel pour le partage et la modération (SPEC-partage-moderation
 * §5.1 publier, §5.11 ouverture, §5.12 accès perdu, §5.14 auteur, §5.5 table locale
 * des retirés), sur le bouchon [LocalSyncService] ; et les textes §6.4 / §7 dans les
 * VRAIS strings.xml fr et en.
 */
class ProjectModerationTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var svc: LocalSyncService
    private val acc = HashMap<String, AccountInfo>()

    @Before fun setUp() = runBlocking<Unit> {
        svc = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))
        for (n in listOf("alice", "bob", "dan")) {
            acc[n] = svc.signUp("$n@exemple.test", "secret123", n.replaceFirstChar { it.uppercase() })
            svc.signOut()
        }
    }

    private suspend fun asUser(n: String): AccountInfo {
        svc.signOut()
        return svc.signIn("$n@exemple.test", "secret123")
    }

    private val mvr = "show v1".toByteArray()
    private val sha = ContentHash.sha256Hex(mvr)

    private suspend fun inviteCount(projectId: String): Int = svc.peekInvites().count { it.projectId == projectId }

    // MARK: §5.1 Publier

    @Test fun publier_nouveauProjet_codeCourantDuLot_puisFichierEtVersion() = runBlocking<Unit> {
        asUser("alice")
        var current: CloudProject? = null
        val progress = ArrayList<Double>()
        val pub = SharePublisher(svc).publish(null, "key-1", "Show", sha, mvr,
            onProject = { current = it }, onUploading = { progress += it })
        assertEquals(pub.project, current)
        assertEquals(ShareCodeResult(pub.project.id, pub.project.inviteCode), pub.code)
        assertEquals(pub.project.id, svc.peekInvite(pub.code.code!!)!!.projectId)
        assertEquals(2, pub.version)
        assertTrue(svc.peekBlobExists(pub.project.id, sha))
        assertEquals(listOf(0.0, 1.0), progress)
    }

    @Test fun publier_projetExistant_aucuneInvitationCreee_codeDuProjet() = runBlocking<Unit> {
        asUser("alice")
        val first = SharePublisher(svc).publish(null, "key-1", "Show", sha, mvr)
        val existing = svc.projectMatchingContentKey("key-1")
        val again = SharePublisher(svc).publish(existing, "key-1", "Show", sha, mvr)
        assertEquals(first.project.id, again.project.id)
        assertEquals(first.code.code, again.code.code)
        assertNull(again.code.change)
        assertEquals(3, again.version)
        // Toujours la MÊME invitation (rien de créé), le fichier présent n'est pas renvoyé.
        assertEquals(1, inviteCount(first.project.id))
    }

    @Test fun publier_projetAncien_proprietaireAdopte_membreSansCode() = runBlocking<Unit> {
        asUser("alice")
        val old = svc.createLegacyProject("key-old", "Ancien")
        val legacy = svc.legacyCreateInvite(old.id).code
        asUser("bob")
        svc.legacyJoin(legacy)
        // Membre d'un projet ancien : pas de code, rien de créé.
        val byMember = SharePublisher(svc).publish(svc.project(old.id), "key-old", "Ancien", sha, mvr)
        assertEquals(ShareCodeResult(old.id, null), byMember.code)
        assertNull(svc.peekProject(old.id)!!.inviteCode)
        // Propriétaire : adoption, annoncée.
        asUser("alice")
        val byOwner = SharePublisher(svc).publish(svc.project(old.id), "key-old", "Ancien", sha, mvr)
        assertEquals(ShareCodeChange.ADOPTED, byOwner.code.change)
        assertEquals(byOwner.code.code, svc.peekProject(old.id)!!.inviteCode)
    }

    // MARK: §5.11 Ouverture

    @Test fun ouverture_monNomPose_codeVerifieChezLeProprietaireSeulement() = runBlocking<Unit> {
        val alice = asUser("alice")
        val old = svc.createLegacyProject("key-old", "Ancien")
        val legacy = svc.legacyCreateInvite(old.id).code
        val bob = asUser("bob")
        svc.legacyJoin(legacy)
        // Membre : son nom est écrit, aucun code n'est touché.
        assertEquals("Bob", ProjectMembership.nameToWrite(svc.project(old.id)!!, bob))
        assertNull(ProjectMembership.openMaintenance(svc, svc.project(old.id)!!, bob))
        assertEquals("Bob", svc.peekProject(old.id)!!.memberNames?.get(bob.uid))
        assertNull(svc.peekProject(old.id)!!.inviteCode)
        assertNull(ProjectMembership.nameToWrite(svc.project(old.id)!!, bob)) // déjà à jour
        // Propriétaire : nom + adoption.
        asUser("alice")
        val r = ProjectMembership.openMaintenance(svc, svc.project(old.id)!!, alice)!!
        assertEquals(ShareCodeChange.ADOPTED, r.change)
        assertEquals("Alice", svc.peekProject(old.id)!!.memberNames?.get(alice.uid))
        // Erreurs ignorées (hors ligne) : rien ne remonte.
        svc.simulatesOffline = true
        assertNull(ProjectMembership.openMaintenance(svc, svc.project(old.id)!!, alice))
        svc.simulatesOffline = false
    }

    // MARK: §5.12 Accès perdu, §5.14 auteur

    @Test fun appartenance_accesPerduEtEtatRepris() {
        val p = CloudProject("P", "k", "Show", "a", listOf("a", "b"), 2, null, null, 1.0,
            inviteCode = "CQDE22", memberNames = mapOf("a" to "Alice", "b" to "Bob"))
        assertTrue(ProjectMembership.lostAccess(RemoteEvent.AccessLost, "b"))
        assertTrue(ProjectMembership.lostAccess(RemoteEvent.MembershipChanged(listOf("a")), "b"))
        assertFalse(ProjectMembership.lostAccess(RemoteEvent.MembershipChanged(listOf("a", "b")), "b"))
        val removed = p.copy(memberUids = listOf("a"), bannedUids = listOf("b"), memberNames = mapOf("a" to "Alice"))
        assertTrue(ProjectMembership.lostAccess(RemoteEvent.ProjectUpdated(removed), "b"))
        assertFalse(ProjectMembership.lostAccess(RemoteEvent.ProjectUpdated(removed), "a"))
        assertFalse(ProjectMembership.lostAccess(RemoteEvent.Audit(emptyList()), "b"))
        // Nouvel état : membres, propriétaire, noms, bannis, code repris ; la version reste.
        val incoming = removed.copy(ownerUid = "a", inviteCode = "NEWC22", mvrVersion = 9, name = "Show 2")
        val merged = ProjectMembership.merged(p, incoming)
        assertEquals(incoming.copy(mvrVersion = 2), merged)
    }

    @Test fun auteur_monNomDuProjet_sinonNomAffiche_jamaisLEMail() {
        val me = AccountInfo("b", "bob@exemple.test", "bob@exemple.test")
        val p = CloudProject("P", "k", "Show", "a", listOf("a", "b"), 2, null, null, 1.0,
            memberNames = mapOf("a" to "Alice", "b" to "Robert"))
        assertEquals("b" to "Robert", ProjectMembership.author(me, p, "Moi (hors ligne)"))
        assertEquals("b" to "bob", ProjectMembership.author(me, p.copy(memberNames = null), "Moi (hors ligne)"))
        assertEquals("b" to "bob", ProjectMembership.author(me, null, "Moi (hors ligne)"))
        assertEquals("" to "Moi (hors ligne)", ProjectMembership.author(null, p, "Moi (hors ligne)"))
    }

    // MARK: §5.5 Table locale des retirés

    @Test fun tableLocaleDesRetires() {
        var saved: String? = null
        val table = RemovedMembers(object : RemovedMembers.Store {
            override fun load() = saved
            override fun save(json: String) { saved = json }
        })
        table.record("P", "b", "Bob")
        table.record("P", "c", "Carol")
        table.record("Q", "b", "Bobby")
        assertEquals(mapOf("b" to "Bob", "c" to "Carol"), table.names("P"))
        table.forget("P", "b")
        assertEquals(mapOf("c" to "Carol"), table.names("P"))
        table.forget("P", "c")
        assertTrue(table.names("P").isEmpty())
        assertEquals(mapOf("b" to "Bobby"), table.names("Q"))
        saved = "pas du JSON"
        assertTrue(table.names("Q").isEmpty())
    }

    // MARK: Textes (§6.4, §7)

    @Test fun textesFrEtEn() {
        val nb = ' '
        val fr = TestRes.FR
        val en = TestRes.EN
        val expected = mapOf(
            "sync_err_action_refused" to (
                "Action refusée$nb: vous n'êtes peut-être plus propriétaire ou membre de ce projet." to
                    "Action refused: you may no longer be the owner or a member of this project."),
            "sync_err_join_refused" to (
                "Ce code ne fonctionne plus, ou vous n'avez plus accès à ce projet. Demandez un nouveau code au propriétaire." to
                    "This code no longer works, or you no longer have access to this project. Ask the owner for a new code."),
            "sync_code_adopted" to (
                "Ce projet a maintenant un code unique$nb: les codes donnés avant ne fonctionnent plus." to
                    "This project now has a single code: codes shared before no longer work."),
            "sync_ask_owner_for_code" to (
                "Demandez le code au propriétaire du projet." to "Ask the project owner for the code.")
        )
        for ((k, v) in expected) {
            assertEquals(k, v.first, fr.get(k))
            assertEquals(k, v.second, en.get(k))
        }
        assertEquals("Vous n'avez plus accès au projet « Show »$nb: il a été supprimé ou vous en avez été retiré. " +
            "Le fichier reste ouvert sur cet appareil, sans mise à jour.", fr.get("sync_notice_access_lost_fmt", "Show"))
        assertEquals("You no longer have access to the project “Show”: it was deleted or you were removed. " +
            "The file stays open on this device, without updates.", en.get("sync_notice_access_lost_fmt", "Show"))
        assertEquals("Vous avez quitté le projet « Show ».", fr.get("sync_notice_left_fmt", "Show"))
        assertEquals("You left the project “Show”.", en.get("sync_notice_left_fmt", "Show"))
        assertEquals("Projet « Show » supprimé.", fr.get("sync_notice_deleted_fmt", "Show"))
        assertEquals("Project “Show” deleted.", en.get("sync_notice_deleted_fmt", "Show"))
        assertEquals("Nouveau code créé$nb: CQDE22.", fr.get("sync_code_replaced_fmt", "CQDE22"))
        assertEquals("New code created: CQDE22.", en.get("sync_code_replaced_fmt", "CQDE22"))
        // Les erreurs portent leur texte (jamais celui du SDK).
        assertEquals("sync_err_action_refused", fr.nameOf(SyncException.ActionRefused.messageRes!!))
        assertEquals("sync_err_join_refused", fr.nameOf(SyncException.JoinRefused.messageRes!!))
    }
}
