package com.minou.mvrviewer.sync

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backend de synchronisation LOCAL (stub) — PORTAGE de LocalSyncService iOS.
 * Fait fonctionner et tester TOUS les flux (inscription, connexion, créer/publier
 * un projet, code de partage, rejoindre, push, observe, audit) dans un SEUL APK,
 * sans Firebase. Le « cloud » est un arbre sur disque sous filesDir :
 *
 *   CloudSim/accounts.json
 *   CloudSim/invites/<code>.json
 *   CloudSim/projects/<projectId>/project.json
 *   CloudSim/projects/<projectId>/sections/<kind>.json
 *   CloudSim/projects/<projectId>/blobs/<sha>.bin
 *   CloudSim/projects/<projectId>/audit.json
 *
 * Il NE synchronise PAS entre appareils, mais c'est un vrai arbre partagé en
 * process : deux observateurs voient les écritures l'un de l'autre via un
 * SharedFlow de diffusion. Un Mutex sérialise tous les accès (comme l'acteur iOS).
 */
class LocalSyncService private constructor(appContext: Context) : SyncService {

    private val ctx = appContext.applicationContext
    private val lock = Mutex()
    private var currentUser: AccountInfo? = null

    /** Diffusion en process pour observe() : (projectId, event). */
    private val bus = MutableSharedFlow<Pair<String, RemoteEvent>>(extraBufferCapacity = 128)

    companion object {
        @Volatile private var instance: LocalSyncService? = null
        fun shared(context: Context): LocalSyncService =
            instance ?: synchronized(this) {
                instance ?: LocalSyncService(context).also { instance = it }
            }
    }

    // MARK: Arborescence

    private val root: File get() = File(ctx.filesDir, "CloudSim").apply { mkdirs() }
    private val accountsFile get() = File(root, "accounts.json")
    private fun inviteFile(code: String) = File(File(root, "invites").apply { mkdirs() }, "$code.json")
    private fun projectDir(id: String) = File(File(root, "projects"), id).apply { mkdirs() }
    private fun projectFile(id: String) = File(projectDir(id), "project.json")
    private fun sectionFile(id: String, kind: ProjectSectionKind) =
        File(File(projectDir(id), "sections").apply { mkdirs() }, "${kind.raw}.json")
    private fun blobFile(id: String, sha: String) =
        File(File(projectDir(id), "blobs").apply { mkdirs() }, "$sha.bin")
    private fun auditFile(id: String) = File(projectDir(id), "audit.json")

    private fun readObj(f: File): JSONObject? =
        runCatching { JSONObject(f.readText()) }.getOrNull()
    private fun readArr(f: File): JSONArray? =
        runCatching { JSONArray(f.readText()) }.getOrNull()
    private fun writeText(f: File, s: String) { runCatching { f.writeText(s) } }

    // MARK: Comptes (stub)

    private fun accounts(): JSONObject = readObj(accountsFile) ?: JSONObject()

    override suspend fun restoreSession(): AccountInfo? = lock.withLock {
        val (email, secret) = SyncCredentialStore.load(ctx) ?: return null
        val acc = accounts().optJSONObject(email.lowercase()) ?: return null
        if (acc.optString("password") != secret) return null
        AccountInfo(acc.optString("uid"), acc.optString("email"), acc.optString("displayName")).also {
            currentUser = it
        }
    }

    override suspend fun signUp(email: String, password: String, displayName: String): AccountInfo = lock.withLock {
        if (password.length < 6) throw SyncException.WeakPassword
        val key = email.lowercase()
        val all = accounts()
        if (all.has(key)) throw SyncException.EmailInUse
        val uid = java.util.UUID.randomUUID().toString()
        val name = displayName.ifBlank { email }
        all.put(key, JSONObject()
            .put("uid", uid).put("email", email)
            .put("displayName", name).put("password", password))
        writeText(accountsFile, all.toString())
        SyncCredentialStore.save(ctx, email, password)
        AccountInfo(uid, email, name).also { currentUser = it }
    }

    override suspend fun signIn(email: String, password: String): AccountInfo = lock.withLock {
        val acc = accounts().optJSONObject(email.lowercase()) ?: throw SyncException.InvalidCredentials
        if (acc.optString("password") != password) throw SyncException.InvalidCredentials
        SyncCredentialStore.save(ctx, email, password)
        AccountInfo(acc.optString("uid"), acc.optString("email"), acc.optString("displayName")).also {
            currentUser = it
        }
    }

    override suspend fun signOut() = lock.withLock {
        currentUser = null
        SyncCredentialStore.clear(ctx)
    }

    private fun requireUser(): AccountInfo = currentUser ?: throw SyncException.NotSignedIn

    // MARK: Projets

    private fun readProject(id: String): CloudProject? =
        readObj(projectFile(id))?.let { SectionCodec.projectFromMap(jsonToMap(it)) }
    private fun writeProject(p: CloudProject) =
        writeText(projectFile(p.id), JSONObject(SectionCodec.projectToMap(p)).toString())

    override suspend fun createProject(contentKey: String, name: String): CloudProject = lock.withLock {
        val u = requireUser()
        val p = CloudProject(
            id = java.util.UUID.randomUUID().toString(), contentKey = contentKey, name = name,
            ownerUid = u.uid, memberUids = listOf(u.uid), mvrVersion = 1,
            mvrBlobSHA = null, refPlanBlobSHA = null, updatedAtEpoch = nowEpoch()
        )
        writeProject(p)
        p
    }

    private fun allProjects(): List<CloudProject> {
        val dir = File(root, "projects")
        val subs = dir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return subs.mapNotNull { readProject(it.name) }
    }

    override suspend fun listProjects(): List<CloudProject> = lock.withLock {
        val u = requireUser()
        allProjects().filter { u.uid in it.memberUids }.sortedByDescending { it.updatedAtEpoch }
    }

    override suspend fun project(id: String): CloudProject? = lock.withLock { readProject(id) }

    override suspend fun projectMatchingContentKey(contentKey: String): CloudProject? = lock.withLock {
        val u = requireUser()
        allProjects().firstOrNull { it.contentKey == contentKey && u.uid in it.memberUids }
    }

    // MARK: Invitation / rejoindre

    override suspend fun createInvite(projectId: String): InviteCode = lock.withLock {
        requireUser()
        if (readProject(projectId) == null) throw SyncException.ProjectNotFound
        val code = shortInviteCode()
        val invite = InviteCode(code, projectId, "mvrviewer://join/$code", null)
        writeText(inviteFile(code), JSONObject()
            .put("code", code).put("projectId", projectId).put("url", invite.url).toString())
        invite
    }

    override suspend fun joinProject(code: String): CloudProject = lock.withLock {
        val u = requireUser()
        val clean = code.trim().uppercase()
        val inv = readObj(inviteFile(clean)) ?: throw SyncException.InviteInvalidOrExpired
        val pid = inv.optString("projectId").takeIf { it.isNotBlank() } ?: throw SyncException.InviteInvalidOrExpired
        var p = readProject(pid) ?: throw SyncException.InviteInvalidOrExpired
        if (u.uid !in p.memberUids) {
            p = p.copy(memberUids = p.memberUids + u.uid, updatedAtEpoch = nowEpoch())
            writeProject(p)
            bus.tryEmit(p.id to RemoteEvent.MembershipChanged(p.memberUids))
        }
        p
    }

    // MARK: Blobs

    override suspend fun blobExists(projectId: String, sha256: String): Boolean = lock.withLock {
        blobFile(projectId, sha256).exists()
    }

    override suspend fun uploadBlob(
        projectId: String, sha256: String, kind: BlobKind,
        data: ByteArray, progress: ((Double) -> Unit)?
    ): BlobRef = lock.withLock {
        requireUser()
        val f = blobFile(projectId, sha256)
        if (!f.exists()) runCatching { f.writeBytes(data) }
        progress?.invoke(1.0)
        BlobRef(sha256, kind, data.size, f.absolutePath)
    }

    override suspend fun downloadBlob(
        projectId: String, ref: BlobRef, progress: ((Double) -> Unit)?
    ): ByteArray = lock.withLock {
        val f = blobFile(projectId, ref.sha256)
        if (!f.exists()) throw SyncException.BlobMissing
        f.readBytes().also { progress?.invoke(1.0) }
    }

    // MARK: État par section

    override suspend fun pushSection(projectId: String, payload: SectionPayload, opId: String) {
        lock.withLock {
            val u = requireUser()
            val now = nowEpoch()
            writeText(sectionFile(projectId, payload.kind), JSONObject()
                .put("payloadJSON", SectionCodec.encode(payload))
                .put("opId", opId).put("updatedBy", u.uid).put("updatedAtEpoch", now).toString())
            readProject(projectId)?.let { writeProject(it.copy(updatedAtEpoch = now)) }
            bus.tryEmit(projectId to RemoteEvent.Section(RemoteChange(payload, opId, u.uid, now)))
        }
    }

    override suspend fun fetchSnapshot(projectId: String): ProjectSnapshot = lock.withLock {
        val snap = ProjectSnapshot()
        for (kind in ProjectSectionKind.entries) {
            val o = readObj(sectionFile(projectId, kind)) ?: continue
            val json = o.optString("payloadJSON").takeIf { it.isNotBlank() } ?: continue
            SectionCodec.decode(json)?.let { snap.apply(it) }
        }
        snap
    }

    // MARK: Journal de modifications (audit)

    override suspend fun appendAudit(projectId: String, entries: List<AuditEntry>) {
        lock.withLock {
            requireUser()
            if (entries.isEmpty()) return@withLock
            val arr = readArr(auditFile(projectId)) ?: JSONArray()
            for (e in entries) arr.put(JSONObject(SectionCodec.auditToMap(e)))
            writeText(auditFile(projectId), arr.toString())
            bus.tryEmit(projectId to RemoteEvent.Audit(entries))
        }
    }

    override suspend fun fetchAudit(projectId: String): List<AuditEntry> = lock.withLock {
        val arr = readArr(auditFile(projectId)) ?: return emptyList()
        (0 until arr.length()).mapNotNull { SectionCodec.auditFromMap(jsonToMap(arr.getJSONObject(it))) }
    }

    override suspend fun publishMVRVersion(projectId: String, sha256: String): Int = lock.withLock {
        val u = requireUser()
        val p = readProject(projectId) ?: throw SyncException.ProjectNotFound
        val next = p.mvrVersion + 1
        writeProject(p.copy(mvrVersion = next, mvrBlobSHA = sha256, updatedAtEpoch = nowEpoch()))
        bus.tryEmit(projectId to RemoteEvent.MvrVersion(next, sha256, u.uid))
        next
    }

    // MARK: Bibliothèque de puissances (stub disque, hors projet, par VOTES)
    //
    // Reproduit la sous-collection `submissions/{uid}` du cloud sous
    // CloudSim/powerLibrary/<docId>/submissions/<uid>.json. Volontairement
    // LENIENT (pas de requireUser) : la biblio doit rester lisible et testable
    // HORS LIGNE / sans compte, contrairement au vrai backend où la règle
    // Firestore exige l'authentification. L'ancien fichier mono-valeur
    // <docId>.json (phase 1) est migré en UN vote.

    private fun powerSubmissionsDir(docId: String) =
        File(File(File(root, "powerLibrary"), docId), "submissions").apply { mkdirs() }
    private fun powerLegacyFile(docId: String) =
        File(File(root, "powerLibrary").apply { mkdirs() }, "$docId.json")

    override suspend fun fetchPowerVotes(spec: String): List<PowerVote> = lock.withLock {
        if (spec.isBlank()) return emptyList()
        val docId = powerLibraryDocId(spec)
        val votes = (powerSubmissionsDir(docId).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { readObj(it)?.let { o -> SectionCodec.powerVoteFromMap(jsonToMap(o)) } }
        if (votes.isNotEmpty()) return votes
        // Migration : ancien fichier mono-valeur = UN vote.
        val legacy = readObj(powerLegacyFile(docId))?.let { SectionCodec.powerFromMap(jsonToMap(it)) }
        legacy?.let { listOf(PowerVote(it.watts, it.updatedAtMillis)) } ?: emptyList()
    }

    override suspend fun putPowerVote(spec: String, watts: Int, uid: String): PowerVote = lock.withLock {
        val vote = PowerVote(watts, System.currentTimeMillis())
        val docId = powerLibraryDocId(spec)
        // Stub LENIENT : sans compte, uid vide → un doc de vote « local ».
        val key = uid.ifBlank { "local" }
        writeText(File(powerSubmissionsDir(docId), "$key.json"),
            JSONObject(SectionCodec.powerVoteToMap(vote)).toString())
        vote
    }

    // MARK: Observation (diffusion en process)

    override fun observe(projectId: String): Flow<RemoteEvent> =
        bus.filter { it.first == projectId }.map { it.second }

    // MARK: Utilitaires

    private fun nowEpoch(): Double = System.currentTimeMillis() / 1000.0

    /** JSONObject de premier niveau → Map (JSONArray→List, NULL→null). */
    private fun jsonToMap(o: JSONObject): Map<String, Any?> = buildMap {
        o.keys().forEach { k ->
            put(k, when (val v = o.get(k)) {
                is JSONArray -> (0 until v.length()).map { v.get(it) }
                JSONObject.NULL -> null
                else -> v
            })
        }
    }
}
