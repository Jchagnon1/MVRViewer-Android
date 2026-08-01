package com.minou.mvrviewer.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Adaptateur RÉEL — PORTAGE de FirebaseSyncService iOS. Firebase Auth + Firestore
 * (état par section, quasi temps réel) + Cloud Storage (blobs .mvr / refplan.bin,
 * adressés SHA-256). Partage EXACTEMENT le schéma cloud d'iOS (mêmes collections,
 * mêmes noms de champs, même enveloppe de section) → un projet publié depuis iOS
 * est rejoignable/modifiable depuis Android et inversement.
 *
 * Sélectionné par [BackendSelector] dès qu'un `FirebaseApp` par défaut existe
 * (google-services.json présent). Sinon on ne l'instancie jamais.
 */
class FirebaseSyncService(context: Context) : SyncService {

    @Suppress("unused")
    private val appContext = context.applicationContext
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private val storage get() = FirebaseStorage.getInstance()

    private fun nowEpoch(): Double = System.currentTimeMillis() / 1000.0
    private fun requireUid(): String = auth.currentUser?.uid ?: throw SyncException.NotSignedIn

    // MARK: Auth

    override suspend fun restoreSession(): AccountInfo? {
        val u = auth.currentUser ?: return null
        return AccountInfo(u.uid, u.email ?: "", u.displayName ?: (u.email ?: ""))
    }

    override suspend fun signUp(email: String, password: String, displayName: String): AccountInfo {
        try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: throw SyncException.SignUpFailed
            val name = displayName.ifBlank { email }
            // Profil + doc utilisateur : NON bloquants (ne pas retarder la connexion).
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
            db.collection("users").document(user.uid).set(mapOf("email" to email, "displayName" to name))
            return AccountInfo(user.uid, email, name)
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
    }

    override suspend fun signIn(email: String, password: String): AccountInfo {
        try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val u = result.user ?: throw SyncException.InvalidCredentials
            return AccountInfo(u.uid, u.email ?: email, u.displayName ?: email)
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    // MARK: Projets

    override suspend fun createProject(contentKey: String, name: String): CloudProject {
        val uid = requireUid()
        val p = CloudProject(
            id = java.util.UUID.randomUUID().toString(), contentKey = contentKey, name = name,
            ownerUid = uid, memberUids = listOf(uid), mvrVersion = 1,
            mvrBlobSHA = null, refPlanBlobSHA = null, updatedAtEpoch = nowEpoch()
        )
        db.collection("projects").document(p.id).set(SectionCodec.projectToMap(p)).await()
        return p
    }

    override suspend fun listProjects(): List<CloudProject> {
        val uid = requireUid()
        val snap = db.collection("projects").whereArrayContains("memberUids", uid).get().await()
        return snap.documents.mapNotNull { SectionCodec.projectFromMap(it.data) }
            .sortedByDescending { it.updatedAtEpoch }
    }

    override suspend fun project(id: String): CloudProject? {
        val snap = db.collection("projects").document(id).get().await()
        return SectionCodec.projectFromMap(snap.data)
    }

    override suspend fun projectMatchingContentKey(contentKey: String): CloudProject? {
        // IMPORTANT : filtrer par `memberUids arrayContains uid` (pas par contentKey
        // seul) pour que la requête soit AUTORISÉE par la règle de lecture (isMember).
        // Le contentKey est filtré côté client (comme iOS).
        val uid = requireUid()
        val snap = db.collection("projects").whereArrayContains("memberUids", uid).get().await()
        return snap.documents.mapNotNull { SectionCodec.projectFromMap(it.data) }
            .firstOrNull { it.contentKey == contentKey }
    }

    // MARK: Invitation / rejoindre

    override suspend fun createInvite(projectId: String): InviteCode {
        val uid = requireUid()
        val code = shortInviteCode()
        val invite = InviteCode(code, projectId, "mvrviewer://join/$code", null)
        db.collection("invites").document(code)
            .set(mapOf("projectId" to projectId, "createdBy" to uid, "url" to invite.url)).await()
        return invite
    }

    override suspend fun joinProject(code: String): CloudProject {
        val uid = requireUid()
        val clean = code.trim().uppercase()
        val inviteSnap = db.collection("invites").document(clean).get().await()
        val projectId = inviteSnap.getString("projectId")
            ?: throw SyncException.InviteInvalidOrExpired
        db.collection("projects").document(projectId)
            .update("memberUids", FieldValue.arrayUnion(uid)).await()
        return project(projectId) ?: throw SyncException.ProjectNotFound
    }

    // MARK: Blobs

    private fun blobRef(projectId: String, sha: String) =
        storage.reference.child("projects/$projectId/blobs/$sha")

    override suspend fun blobExists(projectId: String, sha256: String): Boolean =
        try { blobRef(projectId, sha256).metadata.await(); true } catch (e: Exception) { false }

    override suspend fun uploadBlob(
        projectId: String, sha256: String, kind: BlobKind,
        data: ByteArray, progress: ((Double) -> Unit)?
    ): BlobRef {
        requireUid()
        val ref = blobRef(projectId, sha256)
        val meta = StorageMetadata.Builder().setContentType("application/octet-stream").build()
        val task = ref.putBytes(data, meta)
        if (progress != null) {
            task.addOnProgressListener { s ->
                if (s.totalByteCount > 0) progress(s.bytesTransferred.toDouble() / s.totalByteCount)
            }
        }
        task.await()
        return BlobRef(sha256, kind, data.size, ref.path)
    }

    override suspend fun downloadBlob(
        projectId: String, ref: BlobRef, progress: ((Double) -> Unit)?
    ): ByteArray {
        val data = blobRef(projectId, ref.sha256).getBytes(1L shl 30).await() // plafond 1 Go
        progress?.invoke(1.0)
        return data
    }

    // MARK: État par section

    override suspend fun pushSection(projectId: String, payload: SectionPayload, opId: String) {
        val uid = requireUid()
        db.collection("projects").document(projectId)
            .collection("sections").document(payload.kind.raw)
            .set(mapOf(
                "payloadJSON" to SectionCodec.encode(payload),
                "opId" to opId, "updatedBy" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )).await()
        db.collection("projects").document(projectId)
            .update("updatedAtEpoch", nowEpoch()) // fire-and-forget (== iOS try?)
    }

    override suspend fun fetchSnapshot(projectId: String): ProjectSnapshot {
        val snap = ProjectSnapshot()
        val docs = db.collection("projects").document(projectId).collection("sections").get().await()
        for (doc in docs.documents) {
            val json = doc.getString("payloadJSON") ?: continue
            SectionCodec.decode(json)?.let { snap.apply(it) }
        }
        return snap
    }

    // MARK: Journal de modifications (audit)

    override suspend fun appendAudit(projectId: String, entries: List<AuditEntry>) {
        requireUid()
        val col = db.collection("projects").document(projectId).collection("audit")
        for (e in entries) col.document(e.id).set(SectionCodec.auditToMap(e)) // fire-and-forget
    }

    override suspend fun fetchAudit(projectId: String): List<AuditEntry> {
        val snap = db.collection("projects").document(projectId)
            .collection("audit").orderBy("epoch").get().await()
        return snap.documents.mapNotNull { SectionCodec.auditFromMap(it.data) }
    }

    override suspend fun publishMVRVersion(projectId: String, sha256: String): Int {
        requireUid()
        val ref = db.collection("projects").document(projectId)
        val next = db.runTransaction { txn ->
            val snap = txn.get(ref)
            val current = snap.getLong("mvrVersion") ?: 1L
            val n = current + 1
            txn.update(ref, mapOf(
                "mvrVersion" to n, "mvrBlobSHA" to sha256, "updatedAtEpoch" to nowEpoch()
            ))
            n
        }.await()
        return next.toInt()
    }

    // MARK: Bibliothèque de puissances (collection racine `powerLibrary`, VOTES)

    override suspend fun fetchPowerVotes(spec: String): List<PowerVote> {
        if (spec.isBlank()) return emptyList()
        requireUid() // base communautaire : lecture réservée aux authentifiés
        val docRef = db.collection("powerLibrary").document(powerLibraryDocId(spec))
        val subs = docRef.collection("submissions").get().await()
        val votes = subs.documents.mapNotNull { SectionCodec.powerVoteFromMap(it.data) }
        if (votes.isNotEmpty()) return votes
        // MIGRATION indolore : un ancien doc mono-valeur { watts } sans
        // sous-collection est lu comme UN vote. On ne réécrit pas ce champ.
        val legacy = SectionCodec.powerFromMap(docRef.get().await().data)
        return legacy?.let { listOf(PowerVote(it.watts, it.updatedAtMillis)) } ?: emptyList()
    }

    override suspend fun putPowerVote(spec: String, watts: Int, uid: String): PowerVote {
        // La règle Firestore exige request.auth.uid == uid : on écrit TOUJOURS à
        // son propre uid (l'argument est ignoré s'il diffère).
        val myUid = requireUid()
        val vote = PowerVote(watts, System.currentTimeMillis())
        val docRef = db.collection("powerLibrary").document(powerLibraryDocId(spec))
        // `spec` sur le doc parent : non requis au calcul, pratique au débogage
        // et rend la collection lisible. Merge pour ne pas écraser d'autres champs.
        docRef.set(mapOf("spec" to spec), com.google.firebase.firestore.SetOptions.merge())
        docRef.collection("submissions").document(myUid)
            .set(SectionCodec.powerVoteToMap(vote)).await()
        return vote
    }

    // MARK: Observation (Firestore snapshot listeners → Flow)

    override fun observe(projectId: String): Flow<RemoteEvent> = callbackFlow {
        val proj = db.collection("projects").document(projectId)

        val sectionsReg = proj.collection("sections").addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            for (ch in snap.documentChanges) {
                if (ch.type != DocumentChange.Type.ADDED && ch.type != DocumentChange.Type.MODIFIED) continue
                val d = ch.document
                val json = d.getString("payloadJSON") ?: continue
                val payload = SectionCodec.decode(json) ?: continue
                val ts = d.getTimestamp("updatedAt")?.seconds?.toDouble() ?: nowEpoch()
                trySend(RemoteEvent.Section(RemoteChange(
                    payload, d.getString("opId") ?: "", d.getString("updatedBy") ?: "", ts)))
            }
        }

        val projectReg = proj.addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            val version = snap.getLong("mvrVersion")
            val sha = snap.getString("mvrBlobSHA")
            if (version != null && sha != null) {
                trySend(RemoteEvent.MvrVersion(version.toInt(), sha, snap.getString("ownerUid") ?: ""))
            }
            @Suppress("UNCHECKED_CAST")
            val members = snap.get("memberUids") as? List<String>
            if (members != null) trySend(RemoteEvent.MembershipChanged(members))
        }

        val auditReg = proj.collection("audit").addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            val added = snap.documentChanges
                .filter { it.type == DocumentChange.Type.ADDED }
                .mapNotNull { SectionCodec.auditFromMap(it.document.data) }
            if (added.isNotEmpty()) trySend(RemoteEvent.Audit(added))
        }

        awaitClose {
            sectionsReg.remove(); projectReg.remove(); auditReg.remove()
        }
    }

    // MARK: Utilitaires

    private fun mapAuthError(e: Exception): SyncException = when (e) {
        is SyncException -> e
        is FirebaseAuthWeakPasswordException -> SyncException.WeakPassword
        is FirebaseAuthUserCollisionException -> SyncException.EmailInUse
        is FirebaseAuthInvalidCredentialsException -> SyncException.InvalidCredentials
        is FirebaseAuthInvalidUserException -> SyncException.InvalidCredentials
        else -> e.localizedMessage?.let { SyncException.Message(it) } ?: SyncException.AuthFailed
    }
}
