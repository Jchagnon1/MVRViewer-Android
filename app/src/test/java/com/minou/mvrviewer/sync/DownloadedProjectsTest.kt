package com.minou.mvrviewer.sync

import com.minou.mvrviewer.mvr.ProjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Projet du document ouvert (même règle qu'iOS), sur le stub [LocalSyncService] sans Context.
 * RÉGRESSION : « Rouvrir » (nouvelle version) et « Rejoindre » téléchargent un fichier dont la
 * clé de contenu n'est plus celle du projet — rattaché par la clé seule, il ne l'était plus.
 */
class DownloadedProjectsTest {

    @get:Rule val tmp = TemporaryFolder()

    private class MemoryStore : DownloadedProjects.Store {
        val map = HashMap<String, String>()
        override fun projectId(contentKey: String) = map[contentKey]
        override fun put(contentKey: String, projectId: String) { map[contentKey] = projectId }
    }

    @Test fun aDownloadedFileStaysAttachedToItsProjectForMembersOnly() = runBlocking {
        val svc = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))
        val alice = svc.signUp("alice@example.com", "secret123", "Alice")
        val v1 = "show v1".toByteArray()
        val project = svc.createProject(ProjectStore.keyFor(v1), "Show")
        val links = DownloadedProjects(MemoryStore())
        val v2 = "show v2".toByteArray()
        val key2 = ProjectStore.keyFor(v2)

        // Le fichier publié tel quel : retrouvé par sa clé.
        assertEquals(project.id, links.projectFor(svc, ProjectStore.keyFor(v1), alice.uid)?.id)
        // Une autre version, jamais téléchargée ici : aucun projet.
        assertNull(links.projectFor(svc, key2, alice.uid))
        // Téléchargée depuis le projet (« Rouvrir ») : rattachée.
        links.remember(v2, project.id)
        assertEquals(project.id, links.projectFor(svc, key2, alice.uid)?.id)

        // Un compte qui n'en est pas membre (ou déconnecté) : pas rattaché par ce lien.
        val carol = svc.signUp("carol@example.com", "secret123", "Carol")
        assertNull(links.projectFor(svc, key2, carol.uid))
        assertNull(links.projectFor(svc, key2, null))
    }
}
