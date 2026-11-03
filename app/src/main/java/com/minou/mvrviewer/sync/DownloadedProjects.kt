package com.minou.mvrviewer.sync

import android.content.Context
import com.minou.mvrviewer.mvr.ProjectStore

/**
 * Projet cloud du document ouvert — hors ViewModel, testable en JVM. MÊME règle qu'iOS
 * (`SyncCoordinator.projectForOpenedDocument`) : celui de même clé de contenu (le fichier
 * publié tel quel), sinon celui d'où ce fichier a été TÉLÉCHARGÉ — une nouvelle version
 * rouverte, ou le fichier courant d'un projet rejoint, n'a plus la clé du projet
 * (`contentKey` reste celle du premier fichier publié) —, si le compte en est toujours membre.
 */
class DownloadedProjects(private val store: Store) {

    /** Clé de contenu ([ProjectStore.keyFor]) de chaque .mvr téléchargé → id de son projet. */
    interface Store {
        fun projectId(contentKey: String): String?
        fun put(contentKey: String, projectId: String)
    }

    /** Ce fichier, une fois ouvert, se rattachera à [projectId] même si sa clé n'est pas celle du projet. */
    fun remember(bytes: ByteArray, projectId: String) = store.put(ProjectStore.keyFor(bytes), projectId)

    suspend fun projectFor(service: SyncService, contentKey: String, uid: String?): CloudProject? {
        service.projectMatchingContentKey(contentKey)?.let { return it }
        if (uid == null) return null
        val id = store.projectId(contentKey) ?: return null
        return runCatching { service.project(id) }.getOrNull()?.takeIf { uid in it.memberUids }
    }
}

/** Sur l'appareil : préférences `mvrviewer.sync.downloads` (clé de contenu → id de projet). */
class PrefsDownloadedProjectsStore(context: Context) : DownloadedProjects.Store {
    private val prefs = context.applicationContext
        .getSharedPreferences("mvrviewer.sync.downloads", Context.MODE_PRIVATE)

    override fun projectId(contentKey: String): String? = prefs.getString(contentKey, null)

    override fun put(contentKey: String, projectId: String) {
        prefs.edit().putString(contentKey, projectId).apply()
    }
}
