package com.minou.mvrviewer.sync

import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.Date

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

    /**
     * Licence par compte : `entitlements/{uid}` — posée par l'administrateur
     * dans la console Firebase, JAMAIS écrite par l'app (la règle Firestore ne
     * doit autoriser que la lecture de son propre document). Source par
     * DÉFAUT : le cache hors ligne convient (la licence change rarement ; une
     * relecture serveur suit dès que le réseau revient). Toute erreur → false ;
     * seule une annulation de la coroutine appelante remonte telle quelle (ce
     * n'est pas une erreur métier, et son résultat ne serait pas appliqué).
     */
    override suspend fun fetchProGrant(): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return try {
            val snap = db.collection("entitlements").document(uid).get().await()
            val pro = snap.getBoolean("pro") == true
            val expires = snap.getTimestamp("expires")
            pro && (expires == null || expires.toDate().after(Date()))
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            false
        }
    }

    /**
     * Suppression DÉFINITIVE du compte (politique Google Play). Ordre imposé par
     * Firebase (tout ce qui touche Firestore doit précéder `user.delete()`, après
     * quoi il n'y a plus d'authentification) — et choisi pour qu'AUCUNE écriture
     * ne soit émise tant qu'une étape faillible peut encore laisser le compte
     * vivant (les étapes (a) à (c) ne font que LIRE) :
     *  (a) RE-AUTHENTIFICATION avec le mot de passe saisi — Firebase exige une
     *      connexion récente pour `delete()` ; un mot de passe faux s'arrête ICI,
     *      RIEN n'est écrit ;
     *  (b) SONDE réseau : lecture FORCÉE serveur (`Source.SERVER`) de
     *      `users/{uid}`. Une lecture ne met rien en file ; hors ligne elle échoue
     *      vite (UNAVAILABLE) → [SyncException.NetworkUnavailable] sans qu'aucune
     *      écriture n'ait été enfilée — avec la persistance hors ligne de
     *      Firestore (active par défaut), une écriture enfilée partirait plus
     *      tard, après que l'utilisateur a lu « Réessayez » ;
     *  (c) LECTURES serveur : projets où `memberUids` contient l'uid, invitations
     *      `invites` où `createdBy == uid`, et pour chaque projet dont il est le
     *      SEUL membre, énumération des sous-collections `sections` et `audit` ;
     *  (d) UN SEUL [WriteBatch] (par lots de ≤ [BATCH_LIMIT] opérations commités
     *      séquentiellement si besoin ; l'atomicité tout-ou-rien vaut par lot,
     *      un compte ordinaire tient dans un seul), dans cet ordre :
     *      - projet dont il est le SEUL membre ET le propriétaire : delete de
     *        `sections/{kind}`, `audit/{id}` puis du projet (règle `delete` du
     *        propriétaire) — sinon il resterait un document sans membre,
     *        illisible par tous ; seul membre SANS être propriétaire (donnée
     *        antérieure au transfert de propriété) : la règle interdit `delete`
     *        → retrait seul, document orphelin (ménage serveur) ;
     *      - projet avec d'autres membres : `memberUids arrayRemove(uid)` et, s'il
     *        en était le propriétaire, `ownerUid = <premier autre membre>` dans la
     *        MÊME mise à jour (TRANSFERT DE PROPRIÉTÉ : le projet garde un
     *        propriétaire capable de le supprimer) ;
     *      - delete de chaque invitation qu'il a créée ;
     *      - delete de `users/{uid}` (e-mail, nom = données personnelles), en
     *        DERNIER.
     *      Le `withTimeout` n'entoure que l'`await` du commit : un TIMEOUT
     *      n'annule PAS le lot (aucune API cliente ne retire une mutation en
     *      file) — il reste en file et partira au retour du réseau. On remonte
     *      alors [SyncException.NetworkUnavailable] ; l'utilisateur, toujours
     *      connecté, recommence : la nouvelle tentative ne trouve plus rien à
     *      écrire et termine par `user.delete()` ;
     *  (e) `user.delete()` (Auth) ;
     *  (f) si (e) échoue APRÈS le commit : déconnexion locale (`signOut`, plus
     *      aucun écouteur — le ViewModel les a retirés AVANT l'appel et ne les
     *      rétablit pas) et [SyncException.DeletionIncomplete] — jamais une app
     *      « connectée sans profil ». L'utilisateur se reconnecte puis
     *      recommence ; seul `user.delete()` reste alors à faire.
     *
     * Une annulation de la coroutine appelante ([CancellationException] hors
     * timeout) n'est JAMAIS avalée : elle remonte telle quelle.
     *
     * NON nettoyés (hors de portée d'un client, rien de personnel — même choix
     * qu'iOS) : les blobs Storage des projets supprimés (orphelins, illisibles
     * par les règles) ; les entrées d'audit des projets conservés (l'uid
     * historique ne résout plus vers aucun compte) ; les votes de puissance
     * `powerLibrary/{docId}/submissions/{uid}` — un nombre + une date, sous un uid qui
     * n'existe plus : l'uid n'est que l'ID du document (aucun champ), et une
     * requête par id de document en groupe de collections n'est pas possible
     * côté client → ménage SERVEUR (console / Admin SDK).
     */
    override suspend fun deleteAccount(password: String) {
        val user = auth.currentUser ?: throw SyncException.NotSignedIn
        val email = user.email ?: throw SyncException.NotSignedIn
        val uid = user.uid
        val usersRef = db.collection("users").document(uid)
        // (a) Mot de passe re-vérifié : faux → exception, RIEN d'écrit.
        try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        } catch (e: Exception) {
            throw mapDeleteError(e)
        }
        // (b) Sonde réseau : lecture forcée serveur, n'enfile rien.
        serverRead { usersRef.get(Source.SERVER).await() }
        // (c) Lectures serveur → liste ORDONNÉE d'opérations (sous-documents
        // avant leur projet, profil en dernier).
        val ops = ArrayList<(WriteBatch) -> Unit>()
        val projects = serverRead {
            db.collection("projects").whereArrayContains("memberUids", uid).get(Source.SERVER).await()
        }
        for (doc in projects.documents) {
            val members = (doc.get("memberUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
            val others = members.filter { it != uid }
            val isOwner = doc.getString("ownerUid") == uid
            when {
                others.isEmpty() && isOwner -> {
                    for (sub in PROJECT_SUBCOLLECTIONS) {
                        val subDocs = serverRead { doc.reference.collection(sub).get(Source.SERVER).await() }
                        for (d in subDocs.documents) ops.add { b -> b.delete(d.reference) }
                    }
                    ops.add { b -> b.delete(doc.reference) }
                }
                others.isEmpty() ->
                    ops.add { b -> b.update(doc.reference, "memberUids", FieldValue.arrayRemove(uid)) }
                else -> {
                    val update = HashMap<String, Any>()
                    update["memberUids"] = FieldValue.arrayRemove(uid)
                    if (isOwner) update["ownerUid"] = others.first()
                    ops.add { b -> b.update(doc.reference, update) }
                }
            }
        }
        val invites = serverRead {
            db.collection("invites").whereEqualTo("createdBy", uid).get(Source.SERVER).await()
        }
        for (inv in invites.documents) ops.add { b -> b.delete(inv.reference) }
        ops.add { b -> b.delete(usersRef) }
        // (d) Commit par lots séquentiels ; le timeout n'entoure QUE l'await du commit.
        for (chunk in ops.chunked(BATCH_LIMIT)) {
            val batch = db.batch()
            for (op in chunk) op(batch)
            try {
                withTimeout(COMMIT_TIMEOUT_MS) { batch.commit().await() }
            } catch (e: Exception) {
                throw mapDeleteError(e)
            }
        }
        // (e) Compte Auth. (f) Échec APRÈS le commit : déconnexion + erreur dédiée.
        try {
            user.delete().await()
        } catch (e: Exception) {
            auth.signOut()
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            throw SyncException.DeletionIncomplete
        }
        auth.signOut()
    }

    /**
     * Lecture FORCÉE serveur, bornée : une lecture ne met rien en file, son
     * annulation n'a donc aucun effet différé ; hors ligne, Firestore échoue vite
     * (UNAVAILABLE) → [SyncException.NetworkUnavailable]. Une annulation de la
     * coroutine appelante remonte telle quelle.
     */
    private suspend fun <T> serverRead(block: suspend () -> T): T = try {
        withTimeout(READ_TIMEOUT_MS) { block() }
    } catch (e: Exception) {
        throw mapDeleteError(e)
    }

    /** Une annulation de la coroutine appelante n'est PAS une erreur métier : on la laisse passer. */
    private fun mapDeleteError(e: Exception): Exception =
        if (e is CancellationException && e !is TimeoutCancellationException) e else mapAuthError(e)

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
        is FirebaseAuthRecentLoginRequiredException -> SyncException.RecentLoginRequired
        is FirebaseNetworkException, is TimeoutCancellationException -> SyncException.NetworkUnavailable
        // Lectures forcées serveur (suppression de compte) : hors ligne, Firestore
        // lève UNAVAILABLE ; on le montre comme un problème réseau, pas un texte brut.
        is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> SyncException.NetworkUnavailable
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> SyncException.RecentLoginRequired
            else -> e.localizedMessage?.let { SyncException.Message(it) } ?: SyncException.AuthFailed
        }
        else -> e.localizedMessage?.let { SyncException.Message(it) } ?: SyncException.AuthFailed
    }

    private companion object {
        /** Borne d'une LECTURE serveur de la suppression de compte (une lecture ne met rien en file). */
        const val READ_TIMEOUT_MS = 20_000L
        /** Borne de l'`await` du commit d'un lot — le lot lui-même reste en file au-delà (voir [deleteAccount]). */
        const val COMMIT_TIMEOUT_MS = 60_000L
        /** Opérations par [WriteBatch] (limite Firestore : 500). */
        const val BATCH_LIMIT = 450
        /** Sous-collections connues d'un projet (mêmes noms qu'iOS), purgées quand le projet est supprimé. */
        val PROJECT_SUBCOLLECTIONS = listOf("sections", "audit")
    }
}
