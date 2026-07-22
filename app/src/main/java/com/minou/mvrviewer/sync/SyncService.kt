package com.minou.mvrviewer.sync

import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/**
 * Surface backend-agnostique de la synchronisation — PORTAGE du `protocol
 * SyncService` iOS. Deux implémentations : [LocalSyncService] (stub sur disque,
 * démo/test dans un seul APK) et [FirebaseSyncService] (réel, actif dès que
 * `google-services.json` est présent). Toutes les E/S réseau/disque sont des
 * `suspend`, appelées depuis le `SyncViewModel` via des coroutines.
 */
interface SyncService {
    // — Auth —
    suspend fun restoreSession(): AccountInfo?
    suspend fun signUp(email: String, password: String, displayName: String): AccountInfo
    suspend fun signIn(email: String, password: String): AccountInfo
    suspend fun signOut()

    // — Projets / réunion —
    suspend fun createProject(contentKey: String, name: String): CloudProject
    suspend fun listProjects(): List<CloudProject>
    suspend fun project(id: String): CloudProject?
    suspend fun projectMatchingContentKey(contentKey: String): CloudProject?

    // — Invitation / rejoindre (code court + lien) —
    suspend fun createInvite(projectId: String): InviteCode
    suspend fun joinProject(code: String): CloudProject

    // — Blobs (adressés SHA-256, dédupliqués) —
    suspend fun blobExists(projectId: String, sha256: String): Boolean
    suspend fun uploadBlob(
        projectId: String, sha256: String, kind: BlobKind,
        data: ByteArray, progress: ((Double) -> Unit)? = null
    ): BlobRef
    suspend fun downloadBlob(
        projectId: String, ref: BlobRef, progress: ((Double) -> Unit)? = null
    ): ByteArray

    // — État par section (dernière écriture gagne) —
    suspend fun pushSection(projectId: String, payload: SectionPayload, opId: String)
    suspend fun fetchSnapshot(projectId: String): ProjectSnapshot

    // — Journal de modifications (audit, append-only) —
    suspend fun appendAudit(projectId: String, entries: List<AuditEntry>)
    suspend fun fetchAudit(projectId: String): List<AuditEntry>

    // — Bibliothèque de puissances (collection RACINE `powerLibrary`, GLOBALE) —
    // Base communautaire par VOTES : chaque utilisateur dépose son vote dans
    // `powerLibrary/{docId}/submissions/{uid}` ; la valeur retenue est le
    // CONSENSUS de tous les votes. Lecture/écriture réservées aux AUTHENTIFIÉS
    // (règle Firestore), l'écriture d'un vote à son propre uid seulement.
    // fetchPowerVotes migre un ancien doc mono-valeur en UN vote.
    suspend fun fetchPowerVotes(spec: String): List<PowerVote>
    suspend fun putPowerVote(spec: String, watts: Int, uid: String): PowerVote

    // — Observation temps réel —
    fun observe(projectId: String): Flow<RemoteEvent>

    // — .mvr versionné (« nouvelle version → rouvrir ») —
    suspend fun publishMVRVersion(projectId: String, sha256: String): Int
}

/**
 * SHA-256 hexadécimal complet — l'IDENTITÉ des blobs (dédup). À NE PAS confondre
 * avec [com.minou.mvrviewer.mvr.ProjectStore.keyFor] (empreinte FNV-1a 64 Ko +
 * taille) qui est la clé de RÉUNION d'un projet. Miroir de Swift `ContentHash`.
 */
object ContentHash {
    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }
}

/** Code de partage court (6 caractères, alphabet sans ambiguïté 0/O, 1/I). */
internal fun shortInviteCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return buildString { repeat(6) { append(alphabet.random()) } }
}
