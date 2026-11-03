package com.minou.mvrviewer.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bannière « nouvelle version du fichier » (même règle qu'iOS et le web). RÉGRESSION : juste
 * après « Publier et obtenir un code », la personne qui publiait se voyait annoncer SA propre
 * version (la version publiée était jetée, l'événement filtré sur le seul numéro) ; et sur
 * Firebase, une annonce écartée (« Plus tard ») revenait au premier instantané suivant du
 * document projet (un membre qui rejoint).
 */
class MvrVersionRuleTest {

    private val opened = ContentHash.sha256Hex("show v1".toByteArray())
    private val other = ContentHash.sha256Hex("show v2".toByteArray())
    private val project = CloudProject(
        id = "P1", contentKey = "key", name = "Show", ownerUid = "alice", memberUids = listOf("alice", "bob"),
        mvrVersion = 1, mvrBlobSHA = null, refPlanBlobSHA = null, updatedAtEpoch = 0.0
    )

    private fun advance(
        p: CloudProject?, pending: SyncViewModel.MvrVersionNotice?, version: Int, sha: String, by: String = "alice"
    ) = MvrVersionRule.advance(p, pending, version, sha, by) { opened }

    @Test fun ownPublicationIsNotAnnouncedAndTheProjectFollowsIt() {
        val r = checkNotNull(advance(project, null, 2, opened))
        assertNull(r.notice)
        assertEquals(2, r.project.mvrVersion)
        assertEquals(opened, r.project.mvrBlobSHA)
    }

    @Test fun anotherFileIsAnnounced() {
        val r = checkNotNull(advance(project, null, 2, other, by = "bob"))
        assertEquals(SyncViewModel.MvrVersionNotice("P1", 2, other, "bob"), r.notice)
        assertEquals(2, r.project.mvrVersion)
    }

    @Test fun aDismissedNoticeDoesNotComeBackWithTheSameVersion() {
        // Annonce de la v2, puis « Plus tard » ; le projet courant est déjà en v2.
        val seen = checkNotNull(advance(project, null, 2, other)).project
        // Firebase renvoie la v2 (un membre rejoint) : rien ne change.
        assertNull(advance(seen, null, 2, other))
        // Contrôle : la version suivante est annoncée.
        val sha3 = ContentHash.sha256Hex("show v3".toByteArray())
        assertEquals(3, checkNotNull(advance(seen, null, 3, sha3)).notice?.version)
    }

    @Test fun anOlderNoticeIsDroppedWhenTheOpenFileBecomesTheLatest() {
        val pending = SyncViewModel.MvrVersionNotice("P1", 2, other, "bob")
        val r = checkNotNull(advance(project.copy(mvrVersion = 2), pending, 3, opened))
        assertNull(r.notice)
        assertEquals(3, r.project.mvrVersion)
    }

    @Test fun knownVersionsDoNotHashTheOpenFile() {
        var hashed = 0
        val r = MvrVersionRule.advance(project.copy(mvrVersion = 2), null, 2, other, "bob") { hashed++; opened }
        assertNull(r)
        assertEquals(0, hashed)
        assertNull(MvrVersionRule.advance(null, null, 5, other, "bob") { hashed++; opened })
        assertEquals(0, hashed)
    }
}
