package com.minou.mvrviewer.sync

import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.minou.mvrviewer.AuthEmailLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    private val appContext = context.applicationContext
    // E-mails Firebase Auth (mot de passe oublié…) dans la langue où l'app
    // S'AFFICHE (français ou anglais, [AuthEmailLanguage]) — jamais la première
    // langue du téléphone ; reposée avant chaque envoi ([applyEmailLanguage]).
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance().also { applyEmailLanguage(it) } }

    private fun applyEmailLanguage(auth: FirebaseAuth) {
        auth.setLanguageCode(AuthEmailLanguage.current(appContext))
    }
    private val db get() = FirebaseFirestore.getInstance()
    private val storage get() = FirebaseStorage.getInstance()

    private val projects get() = db.collection("projects")
    private val invites get() = db.collection("invites")

    private fun nowEpoch(): Double = System.currentTimeMillis() / 1000.0
    private fun requireUid(): String = auth.currentUser?.uid ?: throw SyncException.NotSignedIn

    /**
     * Compte de la session (inscription, connexion, reprise) : son nom choisi à
     * l'inscription est connu AVANT que `updateProfile` (non attendu) n'atteigne
     * Firebase Auth — c'est lui que portent les projets ([myDisplayName]).
     */
    @Volatile private var account: AccountInfo? = null

    /** Mon nom affiché dans les projets (SPEC-partage-moderation §4) : jamais l'e-mail complet. */
    private fun myDisplayName(uid: String): String {
        account?.takeIf { it.uid == uid }?.let { return ProjectNames.displayName(it) }
        val u = auth.currentUser
        return ProjectNames.displayName(u?.displayName ?: "", u?.email ?: "")
    }

    // MARK: Auth

    override suspend fun restoreSession(): AccountInfo? {
        val u = auth.currentUser ?: return null
        return AccountInfo.fromAuth(u.uid, u.email, u.displayName).also { account = it }
    }

    override suspend fun signUp(email: String, password: String, displayName: String): AccountInfo {
        try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: throw SyncException.SignUpFailed
            val name = ProjectNames.signUpName(displayName, email)
            // Profil + doc utilisateur : NON bloquants (ne pas retarder la connexion).
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
            db.collection("users").document(user.uid).set(mapOf("email" to email, "displayName" to name))
            // Vérification de l'adresse dès l'inscription (SPEC-abonnement §4.1.6) :
            // une adresse fausse ferait échouer, sans que personne le sache, la
            // confirmation et le rappel avant reconduction. NON bloquant ;
            // « Renvoyer l'e-mail de vérification » existe.
            applyEmailLanguage(auth)
            user.sendEmailVerification()
            return AccountInfo(user.uid, email, name).also { account = it }
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
    }

    override suspend fun signIn(email: String, password: String): AccountInfo {
        try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val u = result.user ?: throw SyncException.InvalidCredentials
            // Sans displayName Firebase : l'adresse RENDUE par Firebase, comme la reprise (§4).
            return AccountInfo.fromAuth(u.uid, u.email, u.displayName, typedEmail = email).also { account = it }
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
    }

    override suspend fun signOut() {
        auth.signOut()
        account = null
    }

    /**
     * Mot de passe oublié (guide C6) : e-mail de réinitialisation Firebase, dans
     * la langue AFFICHÉE par l'app (reposée ici : la langue du téléphone a pu
     * changer depuis le lancement). « Aucun compte pour cette adresse » (et
     * compte désactivé) est traité comme une RÉUSSITE : la réponse ne dit jamais
     * si un compte existe (avec la protection contre l'énumération d'e-mails de
     * Firebase, le serveur ne renvoie de toute façon pas cette erreur).
     */
    override suspend fun sendPasswordReset(email: String) {
        val auth = auth
        applyEmailLanguage(auth)
        try {
            auth.sendPasswordResetEmail(email).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthInvalidUserException) {
            return
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            throw SyncException.InvalidEmail
        } catch (e: FirebaseTooManyRequestsException) {
            throw SyncException.TooManyRequests
        } catch (e: FirebaseAuthException) {
            throw when (e.errorCode) {
                "ERROR_INVALID_EMAIL", "ERROR_MISSING_EMAIL", "ERROR_INVALID_RECIPIENT_EMAIL" -> SyncException.InvalidEmail
                "ERROR_TOO_MANY_REQUESTS" -> SyncException.TooManyRequests
                "ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED" -> return
                else -> mapAuthError(e)
            }
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
    }

    /**
     * Licence Pro du compte : `entitlements/{uid}` = { pro, expires?, sources? }
     * (SPEC-abonnement §3.7), JAMAIS écrite par l'app. Lecture par défaut : le
     * serveur, sinon le cache hors ligne de Firestore — [ProGrant.fromServer] le
     * dit (une licence lue en cache ne remplace pas une lecture serveur pour
     * `onlineLicence`). Document absent → `pro: false`. Toute ERREUR → `null`
     * (inconnu), jamais `pro: false` (§4.1.4) ; seule une annulation de la
     * coroutine appelante remonte telle quelle.
     */
    override suspend fun fetchProGrant(): ProGrant? {
        val uid = auth.currentUser?.uid ?: return null
        return try {
            val snap = db.collection("entitlements").document(uid).get().await()
            val fromServer = !snap.metadata.isFromCache
            val data = snap.data ?: return ProGrant(pro = false, fromServer = fromServer)
            ProGrant.fromDocument(plainValues(data), fromServer)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            null
        }
    }

    /** `Timestamp` → millisecondes, en profondeur (sources), pour [ProGrant]. */
    private fun plainValues(data: Map<String, Any?>): Map<String, Any?> = data.mapValues { (_, v) ->
        when (v) {
            is com.google.firebase.Timestamp -> v.toDate().time
            is Map<*, *> -> plainValues(v.entries.associate { (k, x) -> k.toString() to x })
            else -> v
        }
    }

    /**
     * Adresse vérifiée ? `reload()` relit le compte (le lien de vérification a pu
     * être ouvert sur un autre appareil) ; en échec (hors ligne), le dernier état
     * connu de la session.
     */
    override suspend fun reloadEmailVerified(): Boolean? {
        val user = auth.currentUser ?: return null
        try {
            user.reload().await()
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
        }
        return (auth.currentUser ?: user).isEmailVerified
    }

    /** E-mail de vérification, dans la langue affichée par l'app. */
    override suspend fun sendEmailVerification() {
        val user = auth.currentUser ?: throw SyncException.NotSignedIn
        applyEmailLanguage(auth)
        try {
            user.sendEmailVerification().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseTooManyRequestsException) {
            throw SyncException.TooManyRequests
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
    }

    /** Firebase fait vérifier les achats (`verifyPurchase`, même projet). */
    override val verifiesPurchases: Boolean = true

    /**
     * idToken du compte connecté pour l'envoi de la preuve d'achat à la
     * fonction `verifyPurchase` (SPEC-purchase §6.1) — Firebase Auth déjà
     * présent, AUCUNE dépendance nouvelle. Hors forçage, Firebase rend le jeton
     * en cache tant qu'il est valide (≈ 1 h) ; [forceRefresh] le renouvelle
     * (après un 401). Toute erreur (hors ligne, session révoquée) → null ;
     * l'annulation de la coroutine remonte, comme dans [fetchProGrant].
     */
    override suspend fun currentIdToken(forceRefresh: Boolean): String? {
        val user = auth.currentUser ?: return null
        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            null
        }
    }

    /**
     * Suppression DÉFINITIVE du compte (politique Google Play). Ordre imposé par
     * Firebase (tout ce qui touche Firestore doit précéder `user.delete()`, après
     * quoi il n'y a plus d'authentification) — et choisi pour qu'AUCUNE écriture
     * Firestore ne soit émise tant qu'une étape faillible peut encore laisser le
     * compte vivant (les étapes (a) à (c) ne touchent pas à Firestore autrement
     * qu'en LISANT ; seuls les fichiers Storage des projets solo y sont supprimés,
     * comme le veut l'ordre du SPEC) :
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
     *      `invites` où `createdBy == uid` ; puis, pour chaque projet dont il est
     *      le SEUL membre et le propriétaire, « exactement les étapes 2 à 4 de
     *      §5.9 » (§5.10), dans l'ordre du SPEC et des deux autres plateformes :
     *      ses fichiers Storage supprimés AU MIEUX (le propriétaire en a le droit
     *      tant que le projet existe ; un reste est purgé côté serveur, et la
     *      pierre tombale empêche de recréer le projet pour le relire), PUIS
     *      l'énumération des sous-collections `sections` et `audit` et la lecture
     *      de l'invitation du code courant (gardée seulement si elle existe et
     *      pointe vers ce projet). Une lecture qui échoue ensuite (coupure en
     *      cours) laisse le compte vivant et ces projets sans fichiers : le nouvel
     *      essai les termine ;
     *  (d) écritures en LOTS (§5.13 : ≤ 450 opérations ET ≤ 10 projets dont les
     *      règles lisent le document — au-delà de 20 accès, une requête groupée
     *      est refusée EN ENTIER), commités séquentiellement dans cet ordre
     *      (l'atomicité tout-ou-rien vaut par lot) :
     *      - projet dont il est le SEUL membre ET le propriétaire : delete de
     *        `sections/{kind}`, `audit/{id}`, des invitations de ce projet, puis
     *        l'unité INSÉCABLE [pierre tombale `deletedProjects/{id}`, projet] —
     *        même lot, sinon la règle de la pierre tombale (qui exige le projet)
     *        la refuserait ; seul membre SANS être propriétaire (donnée antérieure
     *        au transfert de propriété) : la règle interdit `delete` → retrait
     *        seul, document orphelin (ménage serveur) ;
     *      - projet avec d'autres membres : `memberUids arrayRemove(uid)`, son nom
     *        effacé de `memberNames` et, s'il en était le propriétaire,
     *        `ownerUid = <premier autre membre>` (le plus ancien : les règles
     *        gardent l'ordre d'arrivée) dans la MÊME mise à jour (TRANSFERT DE
     *        PROPRIÉTÉ : le projet garde un propriétaire capable de le supprimer) ;
     *      - dernier lot : ses AUTRES invitations (toutes, code courant d'un
     *        projet gardé compris : le nouveau propriétaire en recrée un, §5.2) et
     *        `users/{uid}` (e-mail, nom = données personnelles), en DERNIER.
     *      Le `withTimeout` n'entoure que l'`await` du commit : un TIMEOUT
     *      n'annule PAS le lot (aucune API cliente ne retire une mutation en
     *      file) — il reste en file et partira au retour du réseau. On remonte
     *      alors [SyncException.NetworkUnavailable] ; l'utilisateur, toujours
     *      connecté, recommence : la nouvelle tentative ne trouve plus que ce
     *      qui reste à écrire (supprimer une invitation déjà absente est permis)
     *      et termine par `user.delete()` ;
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
     * qu'iOS) : un fichier Storage dont la suppression a échoué (purge serveur,
     * illisible : pierre tombale) ; les entrées d'audit des projets conservés (l'uid
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
        // (c) Lectures serveur → groupes ORDONNÉS (sous-documents avant leur
        // projet, [pierre tombale, projet] insécables, profil en dernier).
        val groups = ArrayList<WriteLots.Group<BatchOp>>()
        try {
            val memberOf = bounded {
                projects.whereArrayContains("memberUids", uid).get(Source.SERVER).await()
            }
            val myInvites = bounded {
                invites.whereEqualTo("createdBy", uid).get(Source.SERVER).await()
            }.documents
            val deletedCodes = HashSet<String>()
            for (doc in memberOf.documents) {
                val data = doc.data ?: continue
                val others = memberList(data).filter { it != uid }
                val isOwner = data["ownerUid"] == uid
                if (others.isEmpty() && isOwner) {
                    // §5.10 : « exactement les étapes 2 à 4 de §5.9 » — fichiers, puis lectures.
                    deleteBlobsBestEffort(doc.id)
                    val deletion = projectDeletionUnits(doc.reference, data, uid, myInvites)
                    deletedCodes += deletion.codes
                    groups += WriteLots.Group(doc.id, deletion.units)
                } else {
                    // Seul membre mais pas propriétaire (données antérieures au
                    // transfert de propriété) : la règle `delete` refuserait et
                    // ferait échouer TOUT le lot → on se retire seulement.
                    val fields = arrayListOf<Pair<FieldPath, Any?>>(
                        FieldPath.of("memberUids") to FieldValue.arrayRemove(uid),
                        FieldPath.of("memberNames", uid) to FieldValue.delete()
                    )
                    if (isOwner) others.firstOrNull()?.let { fields += FieldPath.of("ownerUid") to it }
                    groups += WriteLots.Group(null, listOf(listOf(BatchOp.Update(doc.reference, fields))))
                }
            }
            val last = myInvites.filter { it.id !in deletedCodes }
                .map { listOf<BatchOp>(BatchOp.Delete(it.reference)) } + listOf(listOf(BatchOp.Delete(usersRef)))
            groups += WriteLots.Group(null, last)
        } catch (e: Exception) {
            throw mapDeleteError(e)
        }
        // (d) Commit par lots séquentiels ; le timeout n'entoure QUE l'await de chaque commit.
        try {
            commitInLots(groups)
        } catch (e: Exception) {
            throw mapDeleteError(e)
        }
        // (e) Compte Auth. (f) Échec APRÈS le commit : déconnexion + erreur dédiée.
        try {
            user.delete().await()
        } catch (e: Exception) {
            auth.signOut()
            account = null
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            throw SyncException.DeletionIncomplete
        }
        auth.signOut()
        account = null
    }

    // MARK: Lots d'écriture (SPEC-partage-moderation §5.13)

    /** Une opération d'un lot (suppressions de projet et de compte). */
    private sealed class BatchOp {
        class Delete(val ref: DocumentReference) : BatchOp()
        /** Mise à jour par CHEMINS (FieldPath) : `memberNames.<uid>` sans dépendre d'un « . ». */
        class Update(val ref: DocumentReference, val fields: List<Pair<FieldPath, Any?>>) : BatchOp()
        /** `deletedProjects/{id}` = { deletedAt: heure du serveur }. */
        class Tombstone(val ref: DocumentReference) : BatchOp()
    }

    /**
     * Commit des lots de [WriteLots.plan], un par un, dans l'ordre ; chaque `await`
     * est borné (un lot non accusé reste en file et partira au retour du réseau).
     */
    private suspend fun commitInLots(groups: List<WriteLots.Group<BatchOp>>) {
        for (lot in WriteLots.plan(groups)) {
            val batch = db.batch()
            for (op in lot) when (op) {
                is BatchOp.Delete -> batch.delete(op.ref)
                is BatchOp.Update -> batch.updatePaths(op.ref, op.fields)
                is BatchOp.Tombstone -> batch.set(op.ref, mapOf("deletedAt" to FieldValue.serverTimestamp()))
            }
            withTimeout(COMMIT_TIMEOUT_MS) { batch.commit().await() }
        }
    }

    private fun WriteBatch.updatePaths(ref: DocumentReference, fields: List<Pair<FieldPath, Any?>>) {
        val (field, value) = fields.first()
        update(ref, field, value, *fields.drop(1).flatMap { listOf(it.first, it.second) }.toTypedArray())
    }

    private fun Transaction.updatePaths(ref: DocumentReference, fields: List<Pair<FieldPath, Any?>>) {
        val (field, value) = fields.first()
        update(ref, field, value, *fields.drop(1).flatMap { listOf(it.first, it.second) }.toTypedArray())
    }

    /**
     * Fichiers d'un projet, AVANT ses documents (la règle Storage du propriétaire lit
     * le projet) ; toute erreur est ignorée et l'ensemble est borné (Storage relance
     * jusqu'à 10 min hors ligne) : la pierre tombale empêche de recréer le projet pour
     * relire un reste, et la purge serveur l'efface (§5.9, étape 2).
     */
    private suspend fun deleteBlobsBestEffort(projectId: String) {
        withTimeoutOrNull(BLOB_DELETE_TIMEOUT_MS) {
            val listing = try {
                storage.reference.child("projects/$projectId/blobs").listAll().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withTimeoutOrNull
            }
            for (item in listing.items) {
                try { item.delete().await() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            }
        }
    }

    private class Deletion(val units: List<List<BatchOp>>, val codes: Set<String>)

    /**
     * Opérations de suppression d'un projet (N9, projets solo de N10) : sections,
     * journal, invitations de CE projet — les miennes, plus le code courant SEULEMENT
     * s'il existe et pointe vers lui (disparu ou repris par un autre projet : on n'y
     * touche pas, sinon tout le lot serait refusé) — puis l'unité INSÉCABLE [pierre
     * tombale, projet]. Rend aussi les codes supprimés. [myInvites] : mes invitations,
     * déjà lues (N10), ou null. Lectures SERVEUR bornées ; erreurs brutes (l'appelant
     * les traduit).
     */
    private suspend fun projectDeletionUnits(
        ref: DocumentReference, data: Map<String, Any?>, uid: String, myInvites: List<DocumentSnapshot>?
    ): Deletion {
        val projectId = ref.id
        val units = ArrayList<List<BatchOp>>()
        for (sub in PROJECT_SUBCOLLECTIONS) {
            val docs = bounded { ref.collection(sub).get(Source.SERVER).await() }
            for (d in docs.documents) units += listOf(BatchOp.Delete(d.reference))
        }
        val mine = myInvites ?: bounded {
            invites.whereEqualTo("createdBy", uid).get(Source.SERVER).await()
        }.documents
        val codes = sortedSetOf<String>()
        mine.filter { it.getString("projectId") == projectId }.forEach { codes += it.id }
        val current = data["inviteCode"] as? String
        if (current != null && ShareCodes.isWellFormed(current) && current !in codes) {
            val invite = bounded { invites.document(current).get(Source.SERVER).await() }
            if (invite.exists() && invite.getString("projectId") == projectId) codes += current
        }
        for (code in codes) units += listOf(BatchOp.Delete(invites.document(code)))
        units += listOf(BatchOp.Tombstone(db.collection("deletedProjects").document(projectId)), BatchOp.Delete(ref))
        return Deletion(units, codes)
    }

    /** Lecture serveur (ou transaction) BORNÉE : une lecture ne met rien en file. */
    private suspend fun <T> bounded(block: suspend () -> T): T = withTimeout(READ_TIMEOUT_MS) { block() }

    private fun memberList(data: Map<String, Any?>): List<String> =
        (data["memberUids"] as? List<*>)?.filterIsInstance<String>().orEmpty()

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

    /**
     * N1 (SPEC-partage-moderation §5.1) : projet ET invitation dans UN lot, ATTENDU
     * avant tout téléversement (la règle Storage lit le projet). Le code est d'abord lu
     * côté SERVEUR : pris → nouveau tirage ; hors ligne → échec immédiat, rien en file.
     * Un lot refusé (code pris entre la lecture et l'envoi) → nouveau tirage.
     */
    override suspend fun createProject(contentKey: String, name: String): CloudProject {
        val uid = requireUid()
        val myName = myDisplayName(uid)
        repeat(ShareCodes.MAX_ATTEMPTS) {
            val code = ShareCodes.generate()
            val inviteRef = invites.document(code)
            val taken = try {
                bounded { inviteRef.get(Source.SERVER).await() }.exists()
            } catch (e: Exception) {
                throw mapFirestore(e)
            }
            if (!taken) {
                val p = CloudProject(
                    id = java.util.UUID.randomUUID().toString(), contentKey = contentKey,
                    name = ProjectNames.projectName(name),
                    ownerUid = uid, memberUids = listOf(uid), mvrVersion = 1,
                    mvrBlobSHA = null, refPlanBlobSHA = null, updatedAtEpoch = nowEpoch(),
                    inviteCode = code, memberNames = mapOf(uid to myName)
                )
                val batch = db.batch()
                batch.set(projects.document(p.id), SectionCodec.projectToMap(p))
                batch.set(inviteRef, mapOf("projectId" to p.id, "createdBy" to uid, "url" to ShareCodes.url(code)))
                try {
                    withTimeout(COMMIT_TIMEOUT_MS) { batch.commit().await() }
                    return p
                } catch (e: Exception) {
                    if (!isPermissionDenied(e)) throw mapFirestore(e)
                    // Code pris entre la lecture et l'envoi : nouveau tirage.
                }
            }
        }
        throw SyncException.BackendUnavailable
    }

    override suspend fun listProjects(): List<CloudProject> {
        val uid = requireUid()
        val snap = db.collection("projects").whereArrayContains("memberUids", uid).get().await()
        return snap.documents.mapNotNull { SectionCodec.projectFromMap(it.data) }
            .sortedByDescending { it.updatedAtEpoch }
    }

    override suspend fun project(id: String): CloudProject? {
        if (!isDocumentId(id)) return null
        val snap = try {
            projects.document(id).get().await()
        } catch (e: Exception) {
            throw mapFirestore(e)
        }
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

    // MARK: Rejoindre

    /**
     * N3 (§5.3) : lecture SERVEUR de l'invitation, puis UNE mise à jour — moi en fin de
     * `memberUids` (`arrayUnion`), mon nom, la preuve du code datée par le serveur.
     * Projet strict : seule la preuve du code COURANT passe ; refus (code ancien,
     * banni, projet supprimé) → [SyncException.JoinRefused].
     */
    override suspend fun joinProject(code: String): CloudProject {
        val uid = requireUid()
        val clean = ShareCodes.normalized(code)
        if (!ShareCodes.isWellFormed(clean)) throw SyncException.InviteInvalidOrExpired
        val inviteSnap = try {
            bounded { invites.document(clean).get(Source.SERVER).await() }
        } catch (e: Exception) {
            throw mapFirestore(e)
        }
        val projectId = inviteSnap.getString("projectId")
            ?.takeIf { inviteSnap.exists() && isDocumentId(it) }
            ?: throw SyncException.InviteInvalidOrExpired
        try {
            withTimeout(COMMIT_TIMEOUT_MS) {
                projects.document(projectId).update(
                    FieldPath.of("memberUids"), FieldValue.arrayUnion(uid),
                    FieldPath.of("memberNames", uid), myDisplayName(uid),
                    FieldPath.of("joinProof"),
                    mapOf("uid" to uid, "code" to clean, "at" to FieldValue.serverTimestamp())
                ).await()
            }
        } catch (e: Exception) {
            throw mapFirestore(e, SyncException.JoinRefused)
        }
        val joined = try {
            project(projectId)
        } catch (e: Exception) {
            throw mapFirestore(e, SyncException.JoinRefused)
        }
        return joined ?: throw SyncException.ProjectNotFound
    }

    // MARK: Partage et modération (§5.2, §5.4-5.9) — GRATUITS, jamais gardés Pro

    /** Refus décidé par le CLIENT dans une transaction (plus propriétaire, cible invalide…). */
    private class TxnRefused : RuntimeException()
    /** Code tiré déjà pris (lu dans la transaction) : nouveau tirage. */
    private class CodeTaken : RuntimeException()

    /**
     * Transaction BORNÉE : rien n'est mis en file hors ligne (une transaction exige le
     * serveur) ; Firestore la rejoue si un document lu change avant l'envoi.
     *
     * Elle commence par une lecture SERVEUR de [reading] (le projet) : hors ligne,
     * `runTransaction` ne lâche qu'après ses 5 essais sur UNAVAILABLE (8 à 13 s mesurés
     * sur les émulateurs Firebase, fenêtre « Partager le projet » bloquée sur sa roue),
     * alors que §8 veut un échec TOUT DE SUITE ; la lecture échoue en 3 s environ (3,1 à
     * 3,6 s mesurés, autant que la suppression du projet) sans rien mettre en file, comme
     * la suppression du projet et la vérification du code (même schéma qu'iOS). La
     * transaction garde ensuite l'atomicité.
     */
    private suspend fun <T> transaction(reading: DocumentReference, body: (Transaction) -> T): T = bounded {
        reading.get(Source.SERVER).await()
        db.runTransaction { txn -> body(txn) }.await()
    }

    /** N4 / N5 : je me retire et j'efface mon nom ; propriétaire → le premier autre membre (le plus ancien) hérite. */
    override suspend fun leaveProject(projectId: String) {
        val uid = requireUid()
        val ref = projects.document(projectId)
        try {
            transaction(reading = ref) { txn ->
                val data = txn.get(ref).data ?: throw TxnRefused()
                val members = memberList(data)
                if (uid !in members) throw TxnRefused()
                val fields = arrayListOf<Pair<FieldPath, Any?>>(
                    FieldPath.of("memberUids") to FieldValue.arrayRemove(uid),
                    FieldPath.of("memberNames", uid) to FieldValue.delete()
                )
                if (data["ownerUid"] == uid) {
                    val heir = members.firstOrNull { it != uid } ?: throw TxnRefused()
                    fields += FieldPath.of("ownerUid") to heir
                }
                txn.updatePaths(ref, fields)
            }
        } catch (e: Exception) {
            throw mapFirestore(e)
        }
    }

    /** N6 : retrait + bannissement + nom effacé (propriétaire ; cible = un AUTRE membre). */
    override suspend fun removeMember(projectId: String, uid: String) {
        val me = requireUid()
        val ref = projects.document(projectId)
        try {
            transaction(reading = ref) { txn ->
                val data = txn.get(ref).data ?: throw TxnRefused()
                val members = memberList(data)
                if (data["ownerUid"] != me || me !in members || uid == me || uid !in members) throw TxnRefused()
                txn.updatePaths(ref, listOf(
                    FieldPath.of("memberUids") to FieldValue.arrayRemove(uid),
                    FieldPath.of("bannedUids") to FieldValue.arrayUnion(uid),
                    FieldPath.of("memberNames", uid) to FieldValue.delete()
                ))
            }
        } catch (e: Exception) {
            throw mapFirestore(e)
        }
    }

    /** N7 : `bannedUids` −= cible (propriétaire), en transaction. */
    override suspend fun allowBack(projectId: String, uid: String) {
        val me = requireUid()
        val ref = projects.document(projectId)
        try {
            transaction(reading = ref) { txn ->
                val data = txn.get(ref).data ?: throw TxnRefused()
                if (data["ownerUid"] != me || me !in memberList(data)) throw TxnRefused()
                txn.update(ref, "bannedUids", FieldValue.arrayRemove(uid))
            }
        } catch (e: Exception) {
            throw mapFirestore(e)
        }
    }

    /**
     * N2 (et adoption) : invitation neuve + `inviteCode` ; l'ancienne invitation n'est
     * supprimée que si elle existe ET pointe vers ce projet. Le nouveau code est lu dans
     * la transaction : déjà pris → nouveau tirage (3 au plus).
     */
    override suspend fun rotateInviteCode(projectId: String): String {
        val uid = requireUid()
        val ref = projects.document(projectId)
        repeat(ShareCodes.MAX_ATTEMPTS) {
            val code = ShareCodes.generate()
            val newRef = invites.document(code)
            try {
                transaction(reading = ref) { txn ->
                    val data = txn.get(ref).data ?: throw TxnRefused()
                    if (data["ownerUid"] != uid || uid !in memberList(data)) throw TxnRefused()
                    var oldRef: DocumentReference? = null
                    val old = data["inviteCode"] as? String
                    if (old != null && old != code && ShareCodes.isWellFormed(old)) {
                        val candidate = invites.document(old)
                        val snap = txn.get(candidate)
                        if (snap.exists() && snap.getString("projectId") == projectId) oldRef = candidate
                    }
                    if (txn.get(newRef).exists()) throw CodeTaken()
                    txn.set(newRef, mapOf("projectId" to projectId, "createdBy" to uid, "url" to ShareCodes.url(code)))
                    txn.update(ref, "inviteCode", code)
                    oldRef?.let { txn.delete(it) }
                }
                return code
            } catch (e: CodeTaken) {
                // Nouveau tirage.
            } catch (e: Exception) {
                throw mapFirestore(e)
            }
        }
        throw SyncException.BackendUnavailable
    }

    /** §5.2 (`ensureCurrentCode`) : lectures SERVEUR ; chez le propriétaire, code absent, disparu ou repris → N2. */
    override suspend fun ensureCurrentCode(projectId: String): ShareCodeResult {
        val uid = requireUid()
        val data = try {
            bounded { projects.document(projectId).get(Source.SERVER).await() }.data
        } catch (e: Exception) {
            throw mapFirestore(e)
        } ?: throw SyncException.ActionRefused
        val current = data["inviteCode"] as? String
        if (data["ownerUid"] != uid || uid !in memberList(data)) return ShareCodeResult(projectId, current)
        if (current != null && ShareCodes.isWellFormed(current)) {
            val invite = try {
                bounded { invites.document(current).get(Source.SERVER).await() }
            } catch (e: Exception) {
                throw mapFirestore(e)
            }
            if (invite.exists() && invite.getString("projectId") == projectId) return ShareCodeResult(projectId, current)
        }
        val code = rotateInviteCode(projectId)
        return ShareCodeResult(projectId, code,
            if (current == null) ShareCodeChange.ADOPTED else ShareCodeChange.REPLACED)
    }

    /**
     * N9 (§5.9), dans l'ordre du SPEC et des deux autres plateformes (§5 : séquences
     * identiques) : 1. lecture SERVEUR du projet (hors ligne → rien n'est fait) ;
     * 2. fichiers (au mieux, tant que le projet existe : la règle Storage le lit) ;
     * 3. lectures des sections, du journal et des invitations ; 4. lots — sous-documents,
     * invitations, et [pierre tombale, projet] ensemble, en dernier. Une lecture qui
     * échoue à l'étape 3 (coupure en cours) laisse le projet sans fichiers : un nouvel
     * essai le termine (plus rien à supprimer dans Storage).
     */
    override suspend fun deleteProject(projectId: String) {
        val uid = requireUid()
        val ref = projects.document(projectId)
        val data = try {
            bounded { ref.get(Source.SERVER).await() }.data
        } catch (e: Exception) {
            throw mapFirestore(e)
        } ?: throw SyncException.ActionRefused
        if (data["ownerUid"] != uid || uid !in memberList(data)) throw SyncException.ActionRefused
        try {
            deleteBlobsBestEffort(projectId)
            val deletion = projectDeletionUnits(ref, data, uid, null)
            commitInLots(listOf(WriteLots.Group(projectId, deletion.units)))
        } catch (e: Exception) {
            throw mapFirestore(e)
        }
    }

    /**
     * N8 (§5.8) : mis en file SANS attendre le serveur — l'écriture entre dans la file du
     * SDK AVANT les entrées du journal envoyées ensuite (ordre du client) ; une erreur
     * est ignorée (nouvel essai à la prochaine ouverture).
     */
    override suspend fun setMyProjectName(projectId: String, name: String) {
        val uid = requireUid()
        if (!isDocumentId(projectId)) return
        val clean = Utf16Text.truncated(name, ProjectNames.MAX_DISPLAY_NAME)
        projects.document(projectId).update(FieldPath.of("memberNames", uid), clean)
    }

    // MARK: Blobs

    private fun blobRef(projectId: String, sha: String) =
        storage.reference.child("projects/$projectId/blobs/$sha")

    override suspend fun blobExists(projectId: String, sha256: String): Boolean =
        try { blobRef(projectId, sha256).metadata.await(); true }
        // Une annulation (délai d'attente de l'appelant, écran quitté) n'est pas « absent ».
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { false }

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
        try {
            task.await()
        } catch (e: StorageException) {
            // Envoi REFUSÉ (non-membre, ou fichier déjà présent : jamais d'écrasement,
            // §5.15 — l'appelant relit alors getMetadata) → message lisible, pas le texte du SDK.
            throw when (e.errorCode) {
                StorageException.ERROR_NOT_AUTHORIZED -> SyncException.ActionRefused
                StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> SyncException.NetworkUnavailable
                else -> e
            }
        }
        return BlobRef(sha256, kind, data.size, ref.path)
    }

    override suspend fun downloadBlob(
        projectId: String, ref: BlobRef, progress: ((Double) -> Unit)?
    ): ByteArray {
        val data = try {
            blobRef(projectId, ref.sha256).getBytes(1L shl 30).await() // plafond 1 Go
        } catch (e: StorageException) {
            // Lecture REFUSÉE par la règle Storage (compte retiré, projet quitté ou
            // supprimé) → message lisible, jamais le texte du SDK ; objet absent → « absent ».
            throw when (e.errorCode) {
                StorageException.ERROR_NOT_AUTHORIZED -> SyncException.NotAMember
                StorageException.ERROR_OBJECT_NOT_FOUND -> SyncException.BlobMissing
                else -> e
            }
        }
        progress?.invoke(1.0)
        return data
    }

    override suspend fun blobSize(projectId: String, sha256: String): Long? = try {
        blobRef(projectId, sha256).metadata.await().sizeBytes
    } catch (e: com.google.firebase.storage.StorageException) {
        if (e.errorCode == com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND) null else throw e
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
        val next = try {
            db.runTransaction { txn ->
                val snap = txn.get(ref)
                val current = snap.getLong("mvrVersion") ?: 1L
                val n = current + 1
                txn.update(ref, mapOf(
                    "mvrVersion" to n, "mvrBlobSHA" to sha256, "updatedAtEpoch" to nowEpoch()
                ))
                n
            }.await()
        } catch (e: Exception) {
            throw mapFirestore(e) // plus membre (retiré, projet supprimé) : jamais le texte du SDK
        }
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

    /**
     * Écouteurs du projet. ACCÈS PERDU (SPEC-partage-moderation §5.12) : un écouteur
     * refusé (`permission-denied` : retiré, projet supprimé) ou un document projet absent
     * SUR LE SERVEUR (jamais sur la foi du seul cache) → [RemoteEvent.AccessLost].
     */
    override fun observe(projectId: String): Flow<RemoteEvent> = callbackFlow {
        val proj = projects.document(projectId)
        val accessLost = { e: FirebaseFirestoreException? ->
            val denied = e?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            if (denied) trySend(RemoteEvent.AccessLost)
            denied
        }

        val sectionsReg = proj.collection("sections").addSnapshotListener { snap, e ->
            if (accessLost(e)) return@addSnapshotListener
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

        val projectReg = proj.addSnapshotListener { snap, e ->
            if (accessLost(e)) return@addSnapshotListener
            snap ?: return@addSnapshotListener
            val data = snap.data
            if (data == null) {
                if (!snap.metadata.isFromCache) trySend(RemoteEvent.AccessLost)
                return@addSnapshotListener
            }
            SectionCodec.projectFromMap(data)?.let { trySend(RemoteEvent.ProjectUpdated(it)) }
            val version = snap.getLong("mvrVersion")
            val sha = snap.getString("mvrBlobSHA")
            if (version != null && sha != null) {
                trySend(RemoteEvent.MvrVersion(version.toInt(), sha, snap.getString("ownerUid") ?: ""))
            }
            @Suppress("UNCHECKED_CAST")
            val members = snap.get("memberUids") as? List<String>
            if (members != null) trySend(RemoteEvent.MembershipChanged(members))
        }

        val auditReg = proj.collection("audit").addSnapshotListener { snap, e ->
            if (accessLost(e)) return@addSnapshotListener
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
        is FirebaseTooManyRequestsException -> SyncException.TooManyRequests
        is FirebaseNetworkException, is TimeoutCancellationException -> SyncException.NetworkUnavailable
        // Lectures forcées serveur (suppression de compte) : hors ligne, Firestore
        // lève UNAVAILABLE ; on le montre comme un problème réseau, pas un texte brut.
        // Refus des règles : jamais « Missing or insufficient permissions. » (§7).
        is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> SyncException.NetworkUnavailable
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> SyncException.RecentLoginRequired
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.NOT_FOUND -> SyncException.ActionRefused
            else -> SyncException.BackendUnavailable
        }
        else -> e.localizedMessage?.let { SyncException.Message(it) } ?: SyncException.AuthFailed
    }

    /**
     * Erreur d'une action de partage ou de modération → message lisible (§7) ; JAMAIS
     * le texte brut du SDK. [refused] : ce que devient un refus des règles
     * (`permission-denied`, document disparu, refus décidé dans la transaction) —
     * [SyncException.ActionRefused], ou [SyncException.JoinRefused] pour I3. Une
     * annulation de la coroutine appelante remonte telle quelle.
     */
    private fun mapFirestore(e: Exception, refused: SyncException = SyncException.ActionRefused): Exception = when {
        e is CancellationException && e !is TimeoutCancellationException -> e
        e is SyncException -> e
        e is TxnRefused -> refused
        e is TimeoutCancellationException || e is FirebaseNetworkException -> SyncException.NetworkUnavailable
        e is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.NOT_FOUND -> refused
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> SyncException.NetworkUnavailable
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> SyncException.NotSignedIn
            else -> SyncException.BackendUnavailable
        }
        else -> SyncException.BackendUnavailable
    }

    private fun isPermissionDenied(e: Exception): Boolean =
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED

    /** Identifiant de document utilisable dans un chemin (jamais de « / », qui changerait de collection). */
    private fun isDocumentId(id: String): Boolean = id.isNotEmpty() && '/' !in id

    private companion object {
        /** Borne d'une LECTURE serveur de la suppression de compte (une lecture ne met rien en file). */
        const val READ_TIMEOUT_MS = 20_000L
        /** Borne de l'`await` du commit d'un lot — le lot lui-même reste en file au-delà (voir [deleteAccount]). */
        const val COMMIT_TIMEOUT_MS = 60_000L
        /** Suppression au mieux des fichiers d'un projet (Storage relance jusqu'à 10 min hors ligne). */
        const val BLOB_DELETE_TIMEOUT_MS = 60_000L
        /** Sous-collections connues d'un projet (mêmes noms qu'iOS), purgées quand le projet est supprimé. */
        val PROJECT_SUBCOLLECTIONS = listOf("sections", "audit")
    }
}
