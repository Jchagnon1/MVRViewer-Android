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

    /**
     * MOT DE PASSE OUBLIÉ (guide C6) — droit GRATUIT, jamais gardé Pro : sans lui,
     * qui a oublié son mot de passe ne peut ni se reconnecter ni supprimer son
     * compte. Demande l'envoi de l'e-mail de réinitialisation à [email] (adresse
     * déjà nettoyée et de forme plausible : [SyncViewModel.sendPasswordReset] le
     * vérifie), dans la langue où l'app S'AFFICHE. Réussit AUSSI quand aucun
     * compte n'existe pour cette adresse : la réponse ne doit jamais dire si un
     * compte existe. Erreurs : [SyncException.InvalidEmail],
     * [SyncException.NetworkUnavailable] (hors ligne), [SyncException.TooManyRequests].
     * Ne touche pas à l'état de connexion.
     */
    suspend fun sendPasswordReset(email: String)

    /**
     * Suppression DÉFINITIVE du compte (politique Google Play « suppression de
     * compte », Apple 5.1.1 (v)). Le mot de passe est RE-VÉRIFIÉ avant toute
     * destruction et RIEN n'est écrit tant qu'une étape faillible précède : une
     * erreur AVANT l'écriture (mot de passe faux, réseau, reconnexion exigée…)
     * laisse le compte INTACT — toujours connecté, adhésions conservées — et
     * remonte en [SyncException] lisible. Politique des données : projet dont il
     * était le seul membre → supprimé ; projet partagé → il en est retiré et, s'il
     * en était le propriétaire, la propriété passe au premier autre membre ;
     * invitations qu'il a créées → supprimées ; profil → supprimé. Seule
     * exception au « compte intact » : [SyncException.DeletionIncomplete] (données
     * effacées, compte Auth pas encore) — le service a alors DÉCONNECTÉ
     * l'appareil et l'utilisateur se reconnecte puis recommence. À la réussite,
     * l'utilisateur est déconnecté. JAMAIS de garde Pro : c'est un droit, gratuit.
     */
    suspend fun deleteAccount(password: String)

    /**
     * Licence Pro attribuée au COMPTE connecté par l'administrateur (document
     * Firestore `entitlements/{uid}`, champ `pro: true`, optionnellement
     * `expires` Timestamp dans le futur) ; false si absent, hors ligne ou
     * déconnecté ; ne lève jamais.
     */
    suspend fun fetchProGrant(): Boolean

    /**
     * idToken Firebase du compte connecté, pour authentifier l'envoi de la
     * preuve d'achat à la fonction `verifyPurchase` (`Authorization: Bearer`,
     * SPEC-purchase §6.1). [forceRefresh] : jeton rafraîchi de force (après un
     * 401). `null` si déconnecté, hors ligne ou sans serveur (backend LOCAL :
     * l'envoi est alors sauté, sans message) ; ne lève jamais (seule une
     * annulation de la coroutine appelante remonte).
     */
    suspend fun currentIdToken(forceRefresh: Boolean): String?

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
    /**
     * Taille (octets) d'un blob AVANT de le télécharger — null s'il est ABSENT ; lève en
     * cas d'erreur réseau. Sert au plafond de 64 Mo des 3D de types MA (SPEC-ma.md §4.3 :
     * `getMetadata` avant `getBytes`).
     */
    suspend fun blobSize(projectId: String, sha256: String): Long?

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
