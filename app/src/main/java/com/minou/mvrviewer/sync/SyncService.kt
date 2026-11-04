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
     * remonte en [SyncException] lisible. Politique des données
     * (SPEC-partage-moderation §5.10) : projet dont il était le seul membre et le
     * propriétaire → supprimé comme « Supprimer le projet » (fichiers, sections,
     * journal, invitations qui pointent vers lui, pierre tombale dans le même lot
     * que le projet) ; projet partagé → il en est retiré, son nom effacé et, s'il
     * en était le propriétaire, la propriété passe au premier autre membre (le
     * plus ancien) ; TOUTES les invitations qu'il a créées → supprimées ; profil →
     * supprimé ; écritures en lots de 10 projets au plus (§5.13). Seule
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
    /**
     * PUBLIER UN NOUVEAU PROJET (SPEC-partage-moderation §5.1, N1) : UN lot, attendu
     * (accusé du serveur) avant de rendre la main — le projet (J1, nom tronqué à 200
     * unités UTF-16, `inviteCode` = code tiré, `memberNames` = mon nom affiché) ET son
     * invitation `invites/{code}`. Code déjà pris : nouveau tirage, 3 essais au plus.
     * Le projet rendu porte son code courant.
     */
    suspend fun createProject(contentKey: String, name: String): CloudProject
    suspend fun listProjects(): List<CloudProject>
    /** Null si le projet n'existe pas ; refusé ([SyncException.ActionRefused]) si je n'en suis pas membre. */
    suspend fun project(id: String): CloudProject?
    suspend fun projectMatchingContentKey(contentKey: String): CloudProject?

    // — Rejoindre (code court) —
    /**
     * REJOINDRE (§5.3, N3) — gratuit : code nettoyé (sans espaces, en majuscules),
     * lecture SERVEUR de l'invitation (absente → [SyncException.InviteInvalidOrExpired] ;
     * hors ligne → [SyncException.NetworkUnavailable], rien d'écrit), puis UNE mise à
     * jour : `memberUids` += moi (en fin de liste), mon nom affiché, `joinProof` daté
     * par le serveur. Refus des règles (code ancien, retiré, projet supprimé) →
     * [SyncException.JoinRefused]. Déjà membre : passe, sans effet sur la liste.
     */
    suspend fun joinProject(code: String): CloudProject

    // — Partage et modération (SPEC-partage-moderation §5) —
    // GRATUITS (décision du 3/11/2026, §10) : jamais de garde Pro, jamais par le filet
    // `push` du ViewModel. Les actions qui changent l'appartenance ou le code sont des
    // TRANSACTIONS (rien n'est mis en file hors ligne : [SyncException.NetworkUnavailable]) ;
    // refus des règles → [SyncException.ActionRefused].

    /**
     * QUITTER (§5.4, N4/N5) : je me retire et j'efface mon nom ; propriétaire avec
     * d'autres membres → la propriété passe au premier autre membre (le plus ancien).
     * Propriétaire SEUL → [SyncException.ActionRefused] (il supprime le projet). Un
     * membre parti n'est pas banni.
     */
    suspend fun leaveProject(projectId: String)
    /** RETIRER UN MEMBRE (§5.5, N6) — propriétaire : retrait + bannissement + son nom effacé. */
    suspend fun removeMember(projectId: String, uid: String)
    /** AUTORISER À REVENIR (§5.6, N7) — propriétaire : `bannedUids` −= uid (transaction). */
    suspend fun allowBack(projectId: String, uid: String)
    /**
     * NOUVEAU CODE (§5.2, N2) — propriétaire, aussi l'« adoption » d'un projet ancien :
     * invitation neuve + `inviteCode` ; l'ancienne invitation n'est supprimée QUE si elle
     * existe et pointe vers ce projet. Rend le nouveau code.
     */
    suspend fun rotateInviteCode(projectId: String): String
    /**
     * VÉRIFIER LE CODE COURANT (§5.2, `ensureCurrentCode`) — lectures serveur : chez le
     * propriétaire, code absent (adoption), disparu ou repris par un autre projet →
     * nouveau code aussitôt ([ShareCodeResult.change] dit lequel) ; chez un membre, rien
     * n'est écrit ([ShareCodeResult.code] null pour un projet ancien).
     */
    suspend fun ensureCurrentCode(projectId: String): ShareCodeResult
    /**
     * SUPPRIMER LE PROJET (§5.9, N9) — propriétaire : lecture serveur ; fichiers Storage
     * (au mieux) ; puis lots (§5.13) : sections, journal, invitations qui existent et
     * pointent vers lui, et EN DERNIER, dans le même lot, la pierre tombale
     * `deletedProjects/{id}` et le projet.
     */
    suspend fun deleteProject(projectId: String)
    /**
     * MON NOM DANS LE PROJET (§5.8, N8) : `memberNames.<moi>` = [name] (tronqué à 80
     * unités UTF-16). Mis en file hors ligne (sans enjeu) : rend la main sans attendre
     * le serveur ; l'écriture part AVANT les entrées du journal envoyées ensuite.
     * [name] est le nom affiché ([ProjectNames.displayName], bords déjà retirés par
     * `trimEdges` AVANT la troncature) : ne pas le redécouper ici. Une troncature qui
     * finit sur une espace la garde, et c'est cette valeur que [ProjectMembership.nameToWrite]
     * compare au nom écrit (sinon §5.8 le réécrirait à chaque ouverture).
     */
    suspend fun setMyProjectName(projectId: String, name: String)

    // — Blobs (adressés SHA-256, dédupliqués) —
    /** B1 : toute erreur (réseau, refus) = « absent » ; une annulation remonte. */
    suspend fun blobExists(projectId: String, sha256: String): Boolean
    /**
     * B2 : membres seulement ; JAMAIS d'écrasement — réenvoyer un fichier présent est
     * REFUSÉ (règle Storage `resource == null`). L'appelant envoie après un B1 « absent »
     * et traite un refus suivi d'un fichier présent comme un succès (§5.15,
     * [ProjectBlobs.uploadAbsent]).
     */
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
