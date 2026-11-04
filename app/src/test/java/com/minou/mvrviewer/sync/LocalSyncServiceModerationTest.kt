package com.minou.mvrviewer.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Partage et modération sur le bouchon [LocalSyncService] (SPEC-partage-moderation §5,
 * §13) : UNE séquence ou UN refus par test. Le bouchon applique les mêmes refus que
 * les règles de 51863ef (banni, code courant, ordre de memberUids, noms, journal
 * signé, pas d'écrasement de fichier, pierre tombale) ; les écritures exactes du
 * backend Firebase sont, elles, rejouées contre les émulateurs (rejeu hors app).
 * Comptes : alice (propriétaire), bob, carol, dan — « Alice », « Bob »…
 */
class LocalSyncServiceModerationTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var svc: LocalSyncService
    private val uid = HashMap<String, String>()

    @Before fun setUp() = runBlocking<Unit> {
        svc = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))
        for (n in listOf("alice", "bob", "carol", "dan")) {
            uid[n] = svc.signUp("$n@exemple.test", "secret123", n.replaceFirstChar { it.uppercase() }).uid
            svc.signOut()
        }
    }

    private suspend fun asUser(n: String) {
        svc.signOut()
        svc.signIn("$n@exemple.test", "secret123")
    }

    private inline fun <reified T : Throwable> refuses(noinline block: suspend () -> Unit) {
        assertThrows(T::class.java) { runBlocking { block() } }
    }

    private fun u(n: String) = uid.getValue(n)

    /** Codes imposés au tirage, dans l'ordre (puis tirage normal). */
    private fun forceCodes(vararg codes: String) {
        val queue = ArrayDeque(codes.toList())
        svc.codeGenerator = { queue.removeFirstOrNull() ?: ShareCodes.generate() }
    }

    /** alice publie un projet strict ; bob (et les suivants) le rejoignent avec le code courant. */
    private suspend fun strictTeam(vararg joiners: String, code: String = "CQDE22"): CloudProject {
        forceCodes(code)
        asUser("alice")
        val p = svc.createProject("key-1", "Show")
        for (j in joiners) { asUser(j); svc.joinProject(code) }
        asUser("alice")
        return svc.peekProject(p.id)!!
    }

    private fun entry(id: String, author: String, name: String) =
        AuditEntry(id, 1.0, author, name, "patch", "Projecteur N° 213", "Adresse", "1.147", "1.189")

    // MARK: N1 — Publier un nouveau projet

    @Test fun n1_projetStrictEtInvitationEnsemble() = runBlocking<Unit> {
        asUser("alice")
        val p = svc.createProject("key-1", "Show")
        val code = p.inviteCode!!
        assertTrue(ShareCodes.isWellFormed(code))
        assertEquals(listOf(u("alice")), p.memberUids)
        assertEquals(mapOf(u("alice") to "Alice"), p.memberNames)
        val inv = svc.peekInvite(code)!!
        assertEquals(p.id, inv.projectId)
        assertEquals(u("alice"), inv.createdBy)
        assertEquals(p, svc.project(p.id))
    }

    @Test fun n1_nomDuProjetTronqueA200() = runBlocking<Unit> {
        asUser("alice")
        assertEquals(200, svc.createProject("key-1", "x".repeat(255)).name.length)
    }

    @Test fun n1_codePrisNouveauTirageTroisAuPlus() = runBlocking<Unit> {
        asUser("alice")
        forceCodes("CQDE22")
        svc.createProject("key-1", "Show")
        forceCodes("CQDE22", "ZZZZ22")
        assertEquals("ZZZZ22", svc.createProject("key-2", "Show 2").inviteCode)
        forceCodes("CQDE22", "ZZZZ22", "CQDE22")
        refuses<SyncException.BackendUnavailable> { svc.createProject("key-3", "Show 3") }
        assertEquals(2, svc.listProjects().size)
    }

    @Test fun n1_horsLigneRienNEstCree() = runBlocking<Unit> {
        asUser("alice")
        svc.simulatesOffline = true
        refuses<SyncException.NetworkUnavailable> { svc.createProject("key-1", "Show") }
        svc.simulatesOffline = false
        assertTrue(svc.listProjects().isEmpty())
    }

    // MARK: N3 — Rejoindre

    @Test fun n3_rejoindreAvecLeCodeCourantEnFinDeListeEtAvecMonNom() = runBlocking<Unit> {
        val p = strictTeam()
        asUser("bob")
        val joined = svc.joinProject(" cq de22 ")
        assertEquals(listOf(u("alice"), u("bob")), joined.memberUids)
        assertEquals("Bob", joined.memberNames?.get(u("bob")))
        asUser("carol")
        svc.joinProject("CQDE22")
        assertEquals(listOf(u("alice"), u("bob"), u("carol")), svc.peekProject(p.id)!!.memberUids)
    }

    @Test fun n3_dejaMembreReSaisitLeCodeSansEffetSurLaListe() = runBlocking<Unit> {
        val p = strictTeam("bob")
        asUser("bob")
        svc.joinProject("CQDE22")
        assertEquals(listOf(u("alice"), u("bob")), svc.peekProject(p.id)!!.memberUids)
    }

    @Test fun n3_codeInconnuMalFormeOuHorsLigne() = runBlocking<Unit> {
        strictTeam()
        asUser("bob")
        refuses<SyncException.InviteInvalidOrExpired> { svc.joinProject("ZZZZZZ") }
        refuses<SyncException.InviteInvalidOrExpired> { svc.joinProject("AB") }
        refuses<SyncException.InviteInvalidOrExpired> { svc.joinProject("ABC/EF") }
        svc.simulatesOffline = true
        refuses<SyncException.NetworkUnavailable> { svc.joinProject("CQDE22") }
        svc.simulatesOffline = false
    }

    @Test fun n3_banniNeRevientPasMemeAvecLeCodeCourant() = runBlocking<Unit> {
        val p = strictTeam("bob")
        svc.removeMember(p.id, u("bob"))
        asUser("bob")
        refuses<SyncException.JoinRefused> { svc.joinProject("CQDE22") }
        refuses<SyncException.ActionRefused> { svc.legacyJoin("CQDE22") } // client actuel
        assertEquals(listOf(u("alice")), svc.peekProject(p.id)!!.memberUids)
    }

    @Test fun n3_projetAncienRejointParUnNouveauClient_clientActuelRefuseSurProjetStrict() = runBlocking<Unit> {
        asUser("alice")
        val old = svc.createLegacyProject("key-old", "Ancien")
        val legacyCode = svc.legacyCreateInvite(old.id).code
        asUser("bob")
        val joined = svc.joinProject(legacyCode)
        assertEquals(listOf(u("alice"), u("bob")), joined.memberUids)
        assertEquals("Bob", joined.memberNames?.get(u("bob")))
        // Un client actuel (sans preuve) ne peut PAS entrer dans un projet strict.
        strictTeam()
        asUser("carol")
        refuses<SyncException.ActionRefused> { svc.legacyJoin("CQDE22") }
    }

    // MARK: N4 / N5 — Quitter

    @Test fun n4_membreQuitte_nomEfface_nonBanni_peutRevenir() = runBlocking<Unit> {
        val p = strictTeam("bob")
        asUser("bob")
        svc.leaveProject(p.id)
        val after = svc.peekProject(p.id)!!
        assertEquals(listOf(u("alice")), after.memberUids)
        assertFalse(u("bob") in after.memberNames.orEmpty())
        assertTrue(after.bannedUids.orEmpty().isEmpty())
        refuses<SyncException.ActionRefused> { svc.project(p.id) }
        svc.joinProject("CQDE22")
        assertEquals(listOf(u("alice"), u("bob")), svc.peekProject(p.id)!!.memberUids)
    }

    @Test fun n5_proprietaireQuitte_heritierLePlusAncien() = runBlocking<Unit> {
        val p = strictTeam("bob", "carol")
        svc.leaveProject(p.id)
        val after = svc.peekProject(p.id)!!
        assertEquals(u("bob"), after.ownerUid)
        assertEquals(listOf(u("bob"), u("carol")), after.memberUids)
        assertFalse(u("alice") in after.memberNames.orEmpty())
    }

    @Test fun n5_proprietaireSeulNonMembreEtHorsLigneRefuses() = runBlocking<Unit> {
        val p = strictTeam()
        refuses<SyncException.ActionRefused> { svc.leaveProject(p.id) } // seul : il supprime le projet
        asUser("dan")
        refuses<SyncException.ActionRefused> { svc.leaveProject(p.id) }
        asUser("alice")
        svc.simulatesOffline = true
        refuses<SyncException.NetworkUnavailable> { svc.leaveProject(p.id) }
        svc.simulatesOffline = false
        assertEquals(listOf(u("alice")), svc.peekProject(p.id)!!.memberUids)
    }

    // MARK: N6 / N7 — Retirer, autoriser à revenir

    @Test fun n6_retirerUnMembre_plusAucunAcces() = runBlocking<Unit> {
        val p = strictTeam("bob")
        val sha = "a".repeat(64)
        svc.uploadBlob(p.id, sha, BlobKind.MVR, byteArrayOf(1, 2, 3), null)
        svc.removeMember(p.id, u("bob"))
        val after = svc.peekProject(p.id)!!
        assertEquals(listOf(u("alice")), after.memberUids)
        assertEquals(listOf(u("bob")), after.bannedUids)
        assertFalse(u("bob") in after.memberNames.orEmpty())
        asUser("bob")
        refuses<SyncException.ActionRefused> { svc.project(p.id) }
        refuses<SyncException.ActionRefused> { svc.fetchSnapshot(p.id) }
        refuses<SyncException.ActionRefused> { svc.fetchAudit(p.id) }
        refuses<SyncException.ActionRefused> {
            svc.pushSection(p.id, SectionPayload.LayerColors(LayerColorsDTO()), "op-1")
        }
        refuses<SyncException.ActionRefused> { svc.appendAudit(p.id, listOf(entry("E1", u("bob"), "Bob"))) }
        refuses<SyncException.ActionRefused> { svc.publishMVRVersion(p.id, sha) }
        refuses<SyncException.NotAMember> { svc.downloadBlob(p.id, BlobRef(sha, BlobKind.MVR, 0, ""), null) }
        assertFalse(svc.blobExists(p.id, sha))
        refuses<SyncException.ActionRefused> { svc.uploadBlob(p.id, "b".repeat(64), BlobKind.MVR, byteArrayOf(9), null) }
    }

    @Test fun n6_seulLeProprietaireRetireUnAutreMembre() = runBlocking<Unit> {
        val p = strictTeam("bob", "carol")
        refuses<SyncException.ActionRefused> { svc.removeMember(p.id, u("alice")) } // soi-même
        refuses<SyncException.ActionRefused> { svc.removeMember(p.id, u("dan")) } // pas membre
        asUser("bob")
        refuses<SyncException.ActionRefused> { svc.removeMember(p.id, u("carol")) }
        refuses<SyncException.ActionRefused> { svc.allowBack(p.id, u("carol")) }
        refuses<SyncException.ActionRefused> { svc.rotateInviteCode(p.id) }
        refuses<SyncException.ActionRefused> { svc.deleteProject(p.id) }
        assertEquals(listOf(u("alice"), u("bob"), u("carol")), svc.peekProject(p.id)!!.memberUids)
    }

    @Test fun n7_autoriserARevenir_puisRejoindreAvecLeCodeCourant() = runBlocking<Unit> {
        val p = strictTeam("bob")
        svc.removeMember(p.id, u("bob"))
        svc.allowBack(p.id, u("bob"))
        assertTrue(svc.peekProject(p.id)!!.bannedUids.orEmpty().isEmpty())
        asUser("bob")
        svc.joinProject("CQDE22")
        assertEquals(listOf(u("alice"), u("bob")), svc.peekProject(p.id)!!.memberUids)
        assertEquals("Bob", svc.peekProject(p.id)!!.memberNames?.get(u("bob")))
    }

    @Test fun n6_n7_horsLigneRienNEstEcrit() = runBlocking<Unit> {
        val p = strictTeam("bob")
        svc.simulatesOffline = true
        refuses<SyncException.NetworkUnavailable> { svc.removeMember(p.id, u("bob")) }
        refuses<SyncException.NetworkUnavailable> { svc.allowBack(p.id, u("bob")) }
        refuses<SyncException.NetworkUnavailable> { svc.rotateInviteCode(p.id) }
        refuses<SyncException.NetworkUnavailable> { svc.ensureCurrentCode(p.id) }
        refuses<SyncException.NetworkUnavailable> { svc.deleteProject(p.id) }
        svc.simulatesOffline = false
        assertEquals(p, svc.peekProject(p.id))
    }

    // MARK: N2 — Nouveau code, ensureCurrentCode, adoption

    @Test fun n2_ancienCodeMeurt_nouveauFaitEntrer() = runBlocking<Unit> {
        val p = strictTeam("bob")
        forceCodes("NEWC22")
        assertEquals("NEWC22", svc.rotateInviteCode(p.id))
        assertNull(svc.peekInvite("CQDE22"))
        assertEquals("NEWC22", svc.peekProject(p.id)!!.inviteCode)
        asUser("dan")
        refuses<SyncException.InviteInvalidOrExpired> { svc.joinProject("CQDE22") }
        svc.joinProject("NEWC22")
        // Les membres actuels ne sont pas touchés.
        assertEquals(listOf(u("alice"), u("bob"), u("dan")), svc.peekProject(p.id)!!.memberUids)
    }

    @Test fun n2_adoptionDUnProjetAncien_lesAutresCodesMeurent() = runBlocking<Unit> {
        asUser("alice")
        val old = svc.createLegacyProject("key-old", "Ancien")
        val aliceCode = svc.legacyCreateInvite(old.id).code
        asUser("bob")
        svc.legacyJoin(aliceCode)
        val bobCode = svc.legacyCreateInvite(old.id).code
        // Membre d'un projet ancien : pas de code (« Demandez le code au propriétaire »).
        assertEquals(ShareCodeResult(old.id, null), svc.ensureCurrentCode(old.id))
        asUser("alice")
        forceCodes("ADPT22")
        assertEquals(ShareCodeResult(old.id, "ADPT22", ShareCodeChange.ADOPTED), svc.ensureCurrentCode(old.id))
        assertEquals("ADPT22", svc.peekProject(old.id)!!.inviteCode)
        asUser("dan")
        refuses<SyncException.JoinRefused> { svc.joinProject(bobCode) }
        svc.joinProject("ADPT22")
        // Chez un membre : le code courant, sans écriture.
        assertEquals(ShareCodeResult(old.id, "ADPT22"), svc.ensureCurrentCode(old.id))
    }

    @Test fun n2_codeSainInchange() = runBlocking<Unit> {
        val p = strictTeam()
        assertEquals(ShareCodeResult(p.id, "CQDE22"), svc.ensureCurrentCode(p.id))
        assertEquals("CQDE22", svc.peekProject(p.id)!!.inviteCode)
    }

    @Test fun n2_codeDisparuAvecLeCompteDeSonAuteur_nouveauCode() = runBlocking<Unit> {
        val p = strictTeam("bob")
        svc.leaveProject(p.id) // bob hérite
        svc.deleteAccount("secret123") // l'invitation d'alice part avec son compte
        assertNull(svc.peekInvite("CQDE22"))
        asUser("bob")
        forceCodes("REPL22")
        assertEquals(ShareCodeResult(p.id, "REPL22", ShareCodeChange.REPLACED), svc.ensureCurrentCode(p.id))
        asUser("dan")
        svc.joinProject("REPL22")
    }

    @Test fun n2_codeReprisParUnAutreProjet_rotationSansToucherLInvitationDAutrui() = runBlocking<Unit> {
        val p = strictTeam("bob")
        svc.leaveProject(p.id)
        svc.deleteAccount("secret123")
        asUser("carol")
        forceCodes("CQDE22") // carol reprend le code libéré pour SON projet
        val theirs = svc.createProject("key-c", "Autre")
        asUser("bob")
        forceCodes("REPL22")
        assertEquals(ShareCodeChange.REPLACED, svc.ensureCurrentCode(p.id).change)
        assertEquals(theirs.id, svc.peekInvite("CQDE22")!!.projectId)
        assertEquals("REPL22", svc.peekProject(p.id)!!.inviteCode)
    }

    // MARK: N8 — Mon nom

    @Test fun n8_monNomTronqueA80_membresSeulement() = runBlocking<Unit> {
        val p = strictTeam("bob")
        asUser("bob")
        svc.setMyProjectName(p.id, "Robert")
        assertEquals("Robert", svc.peekProject(p.id)!!.memberNames?.get(u("bob")))
        svc.setMyProjectName(p.id, "😀".repeat(41))
        assertEquals(80, svc.peekProject(p.id)!!.memberNames!!.getValue(u("bob")).length)
        assertEquals("Alice", svc.peekProject(p.id)!!.memberNames?.get(u("alice"))) // jamais le nom d'un autre
        asUser("dan")
        refuses<SyncException.ActionRefused> { svc.setMyProjectName(p.id, "Dan") }
    }

    // MARK: §5.14 — Journal signé

    @Test fun journal_signeParMoiAvecMonNomDuProjet() = runBlocking<Unit> {
        val p = strictTeam("bob")
        asUser("bob")
        svc.appendAudit(p.id, listOf(entry("E1", u("bob"), "Bob")))
        refuses<SyncException.ActionRefused> { svc.appendAudit(p.id, listOf(entry("E2", u("bob"), "Alice"))) }
        refuses<SyncException.ActionRefused> { svc.appendAudit(p.id, listOf(entry("E3", u("alice"), "Alice"))) }
        refuses<SyncException.ActionRefused> { svc.appendAudit(p.id, listOf(entry("E4", "", "Bob"))) }
        // Réécriture à l'identique (nouvel essai) : permise, sans doublon ; modifiée : refusée.
        svc.appendAudit(p.id, listOf(entry("E1", u("bob"), "Bob")))
        refuses<SyncException.ActionRefused> {
            svc.appendAudit(p.id, listOf(entry("E1", u("bob"), "Bob").copy(newValue = "9.999")))
        }
        assertEquals(listOf("E1"), svc.fetchAudit(p.id).map { it.id })
        // Après « Mon nom », les entrées portent le nouveau nom (l'ancien est refusé).
        svc.setMyProjectName(p.id, "Robert")
        svc.appendAudit(p.id, listOf(entry("E5", u("bob"), "Robert")))
        refuses<SyncException.ActionRefused> { svc.appendAudit(p.id, listOf(entry("E6", u("bob"), "Bob"))) }
    }

    @Test fun journal_sansNomDansLeProjet_jamaisLeNomDUnAutreMembre() = runBlocking<Unit> {
        asUser("alice")
        val old = svc.createLegacyProject("key-old", "Ancien")
        val code = svc.legacyCreateInvite(old.id).code
        svc.setMyProjectName(old.id, "Alice")
        asUser("dan")
        svc.legacyJoin(code) // client actuel : aucun nom
        svc.appendAudit(old.id, listOf(entry("E1", u("dan"), "Dan")))
        refuses<SyncException.ActionRefused> { svc.appendAudit(old.id, listOf(entry("E2", u("dan"), "Alice"))) }
    }

    // MARK: N9 — Supprimer le projet

    @Test fun n9_supprimerLeProjet_pierreTombale_toutDisparait() = runBlocking<Unit> {
        val p = strictTeam("bob")
        val sha = "a".repeat(64)
        svc.uploadBlob(p.id, sha, BlobKind.MVR, byteArrayOf(1), null)
        svc.pushSection(p.id, SectionPayload.LayerColors(LayerColorsDTO()), "op-1")
        svc.appendAudit(p.id, listOf(entry("E1", u("alice"), "Alice")))
        // bob regarde le projet : il apprend la perte d'accès.
        val lost = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { svc.observe(p.id).first { it == RemoteEvent.AccessLost } }
        }
        svc.deleteProject(p.id)
        assertEquals(RemoteEvent.AccessLost, lost.await())
        assertNull(svc.peekProject(p.id))
        assertNull(svc.peekInvite("CQDE22"))
        assertTrue(svc.hasTombstone(p.id))
        assertFalse(svc.peekBlobExists(p.id, sha))
        // Personne ne recrée l'identifiant, pas même l'ancien propriétaire ni un membre.
        refuses<SyncException.ActionRefused> { svc.createLegacyProject("key-1", "Recréé", id = p.id) }
        asUser("bob")
        refuses<SyncException.ActionRefused> { svc.createLegacyProject("key-1", "Recréé", id = p.id) }
        refuses<SyncException.InviteInvalidOrExpired> { svc.joinProject("CQDE22") }
    }

    @Test fun n9_invitationsDesAutresMembresRestentInutilisables() = runBlocking<Unit> {
        asUser("alice")
        val old = svc.createLegacyProject("key-old", "Ancien")
        val aliceCode = svc.legacyCreateInvite(old.id).code
        asUser("bob")
        svc.legacyJoin(aliceCode)
        val bobCode = svc.legacyCreateInvite(old.id).code
        asUser("alice")
        svc.deleteProject(old.id)
        assertNull(svc.peekInvite(aliceCode))
        assertNotNull(svc.peekInvite(bobCode)) // pas à moi : la purge serveur l'efface
        asUser("dan")
        refuses<SyncException.JoinRefused> { svc.joinProject(bobCode) }
    }

    @Test fun n9_codeReprisParUnAutreProjet_laSuppressionLaisseLInvitationDAutrui() = runBlocking<Unit> {
        val p = strictTeam("bob")
        svc.leaveProject(p.id)
        svc.deleteAccount("secret123")
        asUser("carol")
        forceCodes("CQDE22")
        val theirs = svc.createProject("key-c", "Autre")
        asUser("bob")
        svc.deleteProject(p.id)
        assertTrue(svc.hasTombstone(p.id))
        assertEquals(theirs.id, svc.peekInvite("CQDE22")!!.projectId)
    }

    @Test fun n9_unMembreNeSupprimePas() = runBlocking<Unit> {
        val p = strictTeam("bob")
        asUser("bob")
        refuses<SyncException.ActionRefused> { svc.deleteProject(p.id) }
        assertNotNull(svc.peekProject(p.id))
        assertFalse(svc.hasTombstone(p.id))
    }

    // MARK: N10 — Supprimer son compte

    @Test fun n10_proprietaireAvecMembres_transfertNomRetireToutesSesInvitations() = runBlocking<Unit> {
        val p = strictTeam("bob", "carol")
        forceCodes("SKLV22")
        val solo = svc.createProject("key-solo", "Solo")
        assertEquals("SKLV22", solo.inviteCode)
        svc.deleteAccount("secret123")
        val kept = svc.peekProject(p.id)!!
        assertEquals(u("bob"), kept.ownerUid)
        assertEquals(listOf(u("bob"), u("carol")), kept.memberUids)
        assertFalse(u("alice") in kept.memberNames.orEmpty())
        assertNull(svc.peekInvite("CQDE22")) // toutes ses invitations, code courant compris
        assertNull(svc.peekProject(solo.id))
        assertNull(svc.peekInvite("SKLV22"))
        assertTrue(svc.hasTombstone(solo.id))
        // Le nouveau propriétaire recrée un code à l'ouverture (§5.2).
        asUser("bob")
        assertEquals(ShareCodeChange.REPLACED, svc.ensureCurrentCode(p.id).change)
    }

    @Test fun n10_membre_retraitEtNomRetire() = runBlocking<Unit> {
        val p = strictTeam("bob")
        asUser("bob")
        svc.deleteAccount("secret123")
        val kept = svc.peekProject(p.id)!!
        assertEquals(u("alice"), kept.ownerUid)
        assertEquals(listOf(u("alice")), kept.memberUids)
        assertFalse(u("bob") in kept.memberNames.orEmpty())
        assertNotNull(svc.peekInvite("CQDE22"))
    }

    @Test fun n10_vingtCinqProjetsSoloStricts() = runBlocking<Unit> {
        asUser("alice")
        val ids = (1..25).map { svc.createProject("key-$it", "Solo $it").id }
        svc.deleteAccount("secret123")
        for (id in ids) {
            assertNull(svc.peekProject(id))
            assertTrue(svc.hasTombstone(id))
        }
    }

    @Test fun n10_horsLigneLeCompteResteIntact() = runBlocking<Unit> {
        val p = strictTeam("bob")
        svc.simulatesOffline = true
        refuses<SyncException.NetworkUnavailable> { svc.deleteAccount("secret123") }
        svc.simulatesOffline = false
        assertEquals(p, svc.peekProject(p.id))
        assertEquals(u("alice"), svc.restoreSession()?.uid)
    }

    // MARK: §5.15 — Fichiers : jamais d'écrasement

    @Test fun fichiers_reenvoiRefuse_traiteCommeDejaPresent() = runBlocking<Unit> {
        val p = strictTeam("bob")
        val sha = "a".repeat(64)
        svc.uploadBlob(p.id, sha, BlobKind.MVR, byteArrayOf(1, 2, 3), null)
        // Un membre ne peut pas « supprimer » le .mvr en l'écrasant (0 octet).
        asUser("bob")
        refuses<SyncException.ActionRefused> { svc.uploadBlob(p.id, sha, BlobKind.MVR, ByteArray(0), null) }
        // B1 en échec réseau → B2 refusé → relecture : déjà présent = succès.
        svc.failNextBlobExistsChecks = 1
        ProjectBlobs.putIfAbsent(svc, p.id, sha, BlobKind.MVR, ByteArray(0))
        assertEquals(3, svc.downloadBlob(p.id, BlobRef(sha, BlobKind.MVR, 0, ""), null).size)
    }

    @Test fun fichiers_nonMembre_leRefusEstGarde() = runBlocking<Unit> {
        val p = strictTeam()
        asUser("dan")
        refuses<SyncException.ActionRefused> {
            ProjectBlobs.uploadAbsent(svc, p.id, "c".repeat(64), BlobKind.MVR, byteArrayOf(1))
        }
        assertFalse(svc.peekBlobExists(p.id, "c".repeat(64)))
    }

    // MARK: Écouteur (§5.12)

    @Test fun ecouteur_retireRecoitUnEtatSansLui() = runBlocking<Unit> {
        val p = strictTeam("bob")
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { svc.observe(p.id).first { it is RemoteEvent.ProjectUpdated } }
        }
        svc.removeMember(p.id, u("bob"))
        val ev = update.await()
        assertTrue(ProjectMembership.lostAccess(ev, u("bob")))
        assertFalse(ProjectMembership.lostAccess(ev, u("alice")))
        assertNotEquals(p.memberUids, (ev as RemoteEvent.ProjectUpdated).project.memberUids)
    }
}
