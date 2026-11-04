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
 *   CloudSim/deletedProjects/<projectId>.json      (pierres tombales)
 *   CloudSim/projects/<projectId>/project.json
 *   CloudSim/projects/<projectId>/sections/<kind>.json
 *   CloudSim/projects/<projectId>/blobs/<sha>.bin
 *   CloudSim/projects/<projectId>/audit.json
 *
 * Il NE synchronise PAS entre appareils, mais c'est un vrai arbre partagé en
 * process : deux observateurs voient les écritures l'un de l'autre via un
 * SharedFlow de diffusion. Un Mutex sérialise tous les accès (comme l'acteur iOS).
 *
 * MÊMES REFUS QUE LES RÈGLES (firestore.rules / storage.rules, contrat
 * SPEC-partage-moderation.md) : lecture et écriture réservées aux membres, banni
 * qui ne revient pas, seul le code COURANT fait entrer un projet strict,
 * `memberUids` en ordre d'arrivée (ajout en fin, retrait), chacun son nom (≤ 80
 * unités UTF-16), auteur du journal signé, jamais d'écrasement d'un fichier,
 * pierre tombale qui interdit de recréer un projet supprimé. Un refus lève
 * [SyncException.ActionRefused] ([SyncException.JoinRefused] pour « Rejoindre »),
 * comme `permission-denied` côté Firebase. Hors ligne ([simulatesOffline]) : les
 * lectures serveur et les transactions du backend réel échouent tout de suite.
 */
class LocalSyncService private constructor(
    private val rootDir: File,
    private val session: SessionStore
) : SyncService {

    /**
     * Persistance de la SESSION (reconnexion silencieuse) — abstraite pour que le
     * stub soit testable en JVM pur : sur l'appareil c'est [SyncCredentialStore]
     * (AndroidKeystore), en test un simple couple en mémoire.
     */
    interface SessionStore {
        fun load(): Pair<String, String>?
        fun save(email: String, secret: String)
        fun clear()
    }

    private val lock = Mutex()
    private var currentUser: AccountInfo? = null

    /**
     * Crochet de test / démo : uids titulaires d'une LICENCE PRO PAR COMPTE —
     * l'équivalent local du document Firestore `entitlements/{uid}` que
     * l'administrateur pose dans la console (cf. [fetchProGrant]).
     */
    val proGrants: MutableSet<String> = mutableSetOf()

    /** Diffusion en process pour observe() : (projectId, event). */
    private val bus = MutableSharedFlow<Pair<String, RemoteEvent>>(extraBufferCapacity = 128)

    companion object {
        /**
         * « Mot de passe oublié » : demandes acceptées par adresse dans
         * [PASSWORD_RESET_WINDOW_MS] (Firebase limite aussi les envois ; ses
         * seuils ne sont pas publiés). Mêmes valeurs qu'iOS.
         */
        const val PASSWORD_RESET_LIMIT = 3
        const val PASSWORD_RESET_WINDOW_MS = 600_000L

        @Volatile private var instance: LocalSyncService? = null
        fun shared(context: Context): LocalSyncService =
            instance ?: synchronized(this) {
                instance ?: fromContext(context).also { instance = it }
            }

        private fun fromContext(context: Context): LocalSyncService {
            val ctx = context.applicationContext
            return LocalSyncService(File(ctx.filesDir, "CloudSim"), object : SessionStore {
                override fun load() = SyncCredentialStore.load(ctx)
                override fun save(email: String, secret: String) = SyncCredentialStore.save(ctx, email, secret)
                override fun clear() = SyncCredentialStore.clear(ctx)
            })
        }

        /** Instance ISOLÉE pour les tests JVM : arbre sous [root], session en mémoire, sans Context. */
        internal fun forTesting(root: File): LocalSyncService = LocalSyncService(root, InMemorySession())
    }

    private class InMemorySession : SessionStore {
        private var pair: Pair<String, String>? = null
        override fun load() = pair
        override fun save(email: String, secret: String) { pair = email to secret }
        override fun clear() { pair = null }
    }

    // MARK: Arborescence

    private val root: File get() = rootDir.apply { mkdirs() }
    private val accountsFile get() = File(root, "accounts.json")
    private fun inviteFile(code: String) = File(File(root, "invites").apply { mkdirs() }, "$code.json")
    private fun projectDir(id: String) = File(File(root, "projects"), id).apply { mkdirs() }
    private fun projectFile(id: String) = File(projectDir(id), "project.json")
    private fun sectionFile(id: String, kind: ProjectSectionKind) =
        File(File(projectDir(id), "sections").apply { mkdirs() }, "${kind.raw}.json")
    private fun blobFile(id: String, sha: String) =
        File(File(projectDir(id), "blobs").apply { mkdirs() }, "$sha.bin")
    private fun auditFile(id: String) = File(projectDir(id), "audit.json")
    private fun tombstoneFile(id: String) =
        File(File(root, "deletedProjects").apply { mkdirs() }, "$id.json")

    private fun readObj(f: File): JSONObject? =
        runCatching { JSONObject(f.readText()) }.getOrNull()
    private fun readArr(f: File): JSONArray? =
        runCatching { JSONArray(f.readText()) }.getOrNull()
    private fun writeText(f: File, s: String) { runCatching { f.writeText(s) } }

    // MARK: Comptes (stub)

    private fun accounts(): JSONObject = readObj(accountsFile) ?: JSONObject()

    override suspend fun restoreSession(): AccountInfo? = lock.withLock {
        val (email, secret) = session.load() ?: return null
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
        session.save(email, password)
        AccountInfo(uid, email, name).also { currentUser = it }
    }

    override suspend fun signIn(email: String, password: String): AccountInfo = lock.withLock {
        val acc = accounts().optJSONObject(email.lowercase()) ?: throw SyncException.InvalidCredentials
        if (acc.optString("password") != password) throw SyncException.InvalidCredentials
        session.save(email, password)
        AccountInfo(acc.optString("uid"), acc.optString("email"), acc.optString("displayName")).also {
            currentUser = it
        }
    }

    override suspend fun signOut() = lock.withLock {
        currentUser = null
        session.clear()
    }

    // MARK: Mot de passe oublié (stub)

    /**
     * Adresses (en minuscules) pour lesquelles un e-mail serait PARTI — les
     * comptes existants seulement (crochet de test ; rien n'est envoyé).
     */
    val sentPasswordResets: MutableList<String> = mutableListOf()
    private val passwordResetRequests = mutableMapOf<String, List<Long>>()

    /** Crochet de test : simule l'absence de réseau. */
    @Volatile var simulatesOffline: Boolean = false

    /** Horloge (ms) — remplaçable en test pour franchir la fenêtre de limitation. */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Même contrat que Firebase ([SyncService.sendPasswordReset]) : adresse mal
     * formée → [SyncException.InvalidEmail] ; hors ligne →
     * [SyncException.NetworkUnavailable] ; au-delà de [PASSWORD_RESET_LIMIT]
     * demandes en [PASSWORD_RESET_WINDOW_MS] pour une même adresse →
     * [SyncException.TooManyRequests] (que le compte existe ou non) ; sinon
     * RÉUSSITE, compte existant ou pas.
     */
    override suspend fun sendPasswordReset(email: String) = lock.withLock {
        if (!EmailAddress.isPlausible(email)) throw SyncException.InvalidEmail
        if (simulatesOffline) throw SyncException.NetworkUnavailable
        val key = email.lowercase()
        val now = clock()
        val recent = passwordResetRequests[key].orEmpty().filter { now - it < PASSWORD_RESET_WINDOW_MS }
        if (recent.size >= PASSWORD_RESET_LIMIT) {
            passwordResetRequests[key] = recent
            throw SyncException.TooManyRequests
        }
        passwordResetRequests[key] = recent + now
        if (accounts().has(key)) sentPasswordResets.add(key)
    }

    override suspend fun fetchProGrant(): Boolean = lock.withLock {
        val uid = currentUser?.uid ?: return false
        uid in proGrants
    }

    /** Pas de serveur derrière le stub : aucun idToken → l'envoi de la preuve
     *  d'achat est sauté, sans message (SPEC-purchase §6.3). */
    override suspend fun currentIdToken(forceRefresh: Boolean): String? = null

    /**
     * Suppression du compte (stub) — MÊME POLITIQUE que le backend réel
     * ([FirebaseSyncService.deleteAccount], SPEC-partage-moderation §5.10) : hors
     * ligne → [SyncException.NetworkUnavailable], rien d'écrit ; mot de passe
     * RE-VÉRIFIÉ (faux → s'arrête AVANT toute écriture, compte intact, toujours
     * connecté) ; projet dont il est le SEUL membre et le propriétaire → supprimé
     * comme « Supprimer le projet » (fichiers, sections, journal, invitations qui
     * pointent vers lui, pierre tombale) ; seul membre sans être propriétaire →
     * uid retiré (orphelin, comme la règle Firestore l'impose) ; projet avec
     * d'autres membres → uid et nom retirés et, s'il en était le propriétaire,
     * PROPRIÉTÉ TRANSFÉRÉE au premier autre membre (le plus ancien) ; TOUTES ses
     * invitations effacées ; compte effacé ; session persistée effacée. Les votes
     * de puissance restent (comme côté Firebase : ménage serveur).
     */
    override suspend fun deleteAccount(password: String) = lock.withLock {
        val u = requireUser()
        requireOnline()
        val key = u.email.lowercase()
        val all = accounts()
        val acc = all.optJSONObject(key) ?: throw SyncException.NotSignedIn
        if (acc.optString("password") != password) throw SyncException.InvalidCredentials
        val deletedCodes = HashSet<String>()
        for (p in allProjects()) {
            if (u.uid !in p.memberUids) continue
            val others = p.memberUids - u.uid
            if (others.isEmpty() && p.ownerUid == u.uid) {
                deletedCodes += deleteProjectTree(p, u.uid)
                continue
            }
            val left = p.copy(
                memberUids = others,
                ownerUid = if (p.ownerUid == u.uid && others.isNotEmpty()) others.first() else p.ownerUid,
                memberNames = p.memberNames?.minus(u.uid)
            )
            saveProject(left, membershipChanged = true)
        }
        for (invite in allInvites()) {
            if (invite.createdBy == u.uid && invite.code !in deletedCodes) deleteInvite(invite.code)
        }
        all.remove(key)
        writeText(accountsFile, all.toString())
        currentUser = null
        session.clear()
    }

    private fun requireUser(): AccountInfo = currentUser ?: throw SyncException.NotSignedIn

    /**
     * Lecture « serveur » ou transaction : hors ligne, échec immédiat, rien n'est
     * écrit (SPEC-partage-moderation §8).
     */
    private fun requireOnline() {
        if (simulatesOffline) throw SyncException.NetworkUnavailable
    }

    // MARK: Projets (lecture et écriture du document)

    private fun readProject(id: String): CloudProject? {
        if (!isDocumentId(id)) return null
        return readObj(projectFile(id))?.let { SectionCodec.projectFromMap(jsonToMap(it)) }
    }

    /**
     * Écrit le document projet et le diffuse comme un instantané de l'écouteur J4
     * ([RemoteEvent.ProjectUpdated]), plus [RemoteEvent.MembershipChanged] si la liste
     * des membres a bougé.
     */
    private fun saveProject(p: CloudProject, membershipChanged: Boolean = false) {
        writeText(projectFile(p.id), JSONObject(SectionCodec.projectToMap(p)).toString())
        bus.tryEmit(p.id to RemoteEvent.ProjectUpdated(p))
        if (membershipChanged) bus.tryEmit(p.id to RemoteEvent.MembershipChanged(p.memberUids))
    }

    private fun isMember(p: CloudProject, uid: String) = uid in p.memberUids
    /** Propriétaire ET membre (un ancien propriétaire sorti des membres ne garde aucun pouvoir — règle `isOwner`). */
    private fun isOwner(p: CloudProject, uid: String) = isMember(p, uid) && p.ownerUid == uid

    /** Projet lisible par le compte courant (règle `allow read: if isMember()`). */
    private fun memberProject(id: String): CloudProject {
        val u = requireUser()
        val p = readProject(id) ?: throw SyncException.ActionRefused
        if (!isMember(p, u.uid)) throw SyncException.ActionRefused
        return p
    }

    /** Tirage des codes — remplaçable en test (collisions forcées). */
    internal var codeGenerator: () -> String = ShareCodes::generate

    private fun nameOk(name: String) = name.length <= ProjectNames.MAX_DISPLAY_NAME

    /**
     * Règle de CRÉATION d'un projet : propriétaire et seul membre, nom ≤ 500 unités
     * UTF-16, mon nom seulement, aucun banni, code courant éventuel bien formé, jamais
     * l'identifiant d'un projet supprimé (pierre tombale) ni d'un projet existant.
     */
    private fun checkCreate(p: CloudProject, uid: String) {
        val names = p.memberNames.orEmpty()
        val ok = isDocumentId(p.id) && !tombstoneFile(p.id).exists() && readProject(p.id) == null &&
            p.ownerUid == uid && p.memberUids == listOf(uid) && p.name.length <= 500 &&
            p.bannedUids.orEmpty().isEmpty() &&
            names.keys.all { it == uid } && names.values.all(::nameOk) &&
            (p.inviteCode == null || ShareCodes.isWellFormed(p.inviteCode))
        if (!ok) throw SyncException.ActionRefused
    }

    /**
     * N1 (§5.1) : le projet (code courant + mon nom) ET son invitation, ensemble ; un
     * code déjà pris → nouveau tirage (3 au plus).
     */
    override suspend fun createProject(contentKey: String, name: String): CloudProject = lock.withLock {
        val u = requireUser()
        requireOnline()
        repeat(ShareCodes.MAX_ATTEMPTS) {
            val code = codeGenerator()
            if (ShareCodes.isWellFormed(code) && readInvite(code) == null) {
                val p = CloudProject(
                    id = java.util.UUID.randomUUID().toString(), contentKey = contentKey,
                    name = ProjectNames.projectName(name),
                    ownerUid = u.uid, memberUids = listOf(u.uid), mvrVersion = 1,
                    mvrBlobSHA = null, refPlanBlobSHA = null, updatedAtEpoch = nowEpoch(),
                    inviteCode = code, memberNames = mapOf(u.uid to ProjectNames.displayName(u))
                )
                checkCreate(p, u.uid)
                writeInvite(code, p.id, u.uid)
                writeText(projectFile(p.id), JSONObject(SectionCodec.projectToMap(p)).toString())
                return@withLock p
            }
        }
        throw SyncException.BackendUnavailable
    }

    /**
     * CROCHET DE TEST — un projet ANCIEN, tel que l'écrivent les clients d'avant (J1 :
     * ni code courant, ni noms), sous un identifiant choisi ou neuf. Même règle de
     * création (pierre tombale comprise).
     */
    internal suspend fun createLegacyProject(contentKey: String, name: String, id: String? = null): CloudProject =
        lock.withLock {
            val u = requireUser()
            val p = CloudProject(
                id = id ?: java.util.UUID.randomUUID().toString(), contentKey = contentKey, name = name,
                ownerUid = u.uid, memberUids = listOf(u.uid), mvrVersion = 1,
                mvrBlobSHA = null, refPlanBlobSHA = null, updatedAtEpoch = nowEpoch()
            )
            checkCreate(p, u.uid)
            writeText(projectFile(p.id), JSONObject(SectionCodec.projectToMap(p)).toString())
            p
        }

    /** CROCHETS DE TEST — lectures SANS règles (comme `peek` de rules-tests) : projet, invitation, pierre tombale. */
    internal suspend fun peekProject(id: String): CloudProject? = lock.withLock { readProject(id) }
    internal suspend fun peekInvite(code: String): InviteCode? = lock.withLock { readInvite(code) }
    internal suspend fun peekInvites(): List<InviteCode> = lock.withLock { allInvites() }
    internal suspend fun hasTombstone(id: String): Boolean = lock.withLock { tombstoneFile(id).exists() }
    internal suspend fun peekBlobExists(projectId: String, sha256: String): Boolean =
        lock.withLock { blobFile(projectId, sha256).exists() }

    private fun allProjects(): List<CloudProject> {
        val dir = File(root, "projects")
        val subs = dir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return subs.mapNotNull { readProject(it.name) }
    }

    override suspend fun listProjects(): List<CloudProject> = lock.withLock {
        val u = requireUser()
        allProjects().filter { u.uid in it.memberUids }.sortedByDescending { it.updatedAtEpoch }
    }

    /** Null si le projet n'existe pas ; refusé si je n'en suis pas membre (règle de lecture), comme le `get` Firestore. */
    override suspend fun project(id: String): CloudProject? = lock.withLock {
        if (readProject(id) == null) return@withLock null
        memberProject(id)
    }

    override suspend fun projectMatchingContentKey(contentKey: String): CloudProject? = lock.withLock {
        val u = requireUser()
        allProjects().firstOrNull { it.contentKey == contentKey && u.uid in it.memberUids }
    }

    // MARK: Invitations / rejoindre

    private fun readInvite(code: String): InviteCode? {
        if (!ShareCodes.isWellFormed(code)) return null
        val o = readObj(inviteFile(code)) ?: return null
        val pid = o.optString("projectId").takeIf { it.isNotBlank() } ?: return null
        return InviteCode(code, pid, ShareCodes.url(code), null, o.optString("createdBy").takeIf { it.isNotBlank() })
    }

    private fun writeInvite(code: String, projectId: String, createdBy: String) {
        // `createdBy` comme dans le cloud : effacée avec le compte de son auteur.
        writeText(inviteFile(code), JSONObject()
            .put("code", code).put("projectId", projectId).put("url", ShareCodes.url(code))
            .put("createdBy", createdBy).toString())
    }

    private fun deleteInvite(code: String) {
        if (ShareCodes.isWellFormed(code)) inviteFile(code).delete()
    }

    private fun allInvites(): List<InviteCode> =
        File(root, "invites").listFiles { f -> f.extension == "json" }
            ?.mapNotNull { readInvite(it.nameWithoutExtension) }.orEmpty()

    /**
     * CROCHET DE TEST — I1 des clients d'avant : une invitation créée par un MEMBRE.
     * Projet strict : refusée (seul le code courant, posé avec `inviteCode` par le
     * propriétaire, y est permis).
     */
    internal suspend fun legacyCreateInvite(projectId: String): InviteCode = lock.withLock {
        val u = requireUser()
        val p = memberProject(projectId)
        if (p.isStrict) throw SyncException.ActionRefused
        val code = codeGenerator()
        if (!ShareCodes.isWellFormed(code) || readInvite(code) != null) throw SyncException.ActionRefused
        writeInvite(code, projectId, u.uid)
        InviteCode(code, projectId, ShareCodes.url(code), null, u.uid)
    }

    /**
     * CROCHET DE TEST — I3 des clients d'avant : `memberUids` += moi, SEUL champ envoyé
     * (aucune preuve de code, aucun nom). Projet strict ou compte banni : refusé.
     */
    internal suspend fun legacyJoin(code: String): CloudProject = lock.withLock {
        val u = requireUser()
        val invite = readInvite(ShareCodes.normalized(code)) ?: throw SyncException.InviteInvalidOrExpired
        val p = readProject(invite.projectId) ?: throw SyncException.ActionRefused
        if (isMember(p, u.uid)) return@withLock p
        if (p.isStrict || u.uid in p.bannedUids.orEmpty()) throw SyncException.ActionRefused
        val joined = p.copy(memberUids = p.memberUids + u.uid)
        saveProject(joined, membershipChanged = true)
        joined
    }

    /**
     * N3 (§5.3) — mêmes refus que la règle `joinOk` : non banni, ajout EN FIN de liste,
     * projet strict = le code saisi est le code COURANT ; projet ancien = l'invitation
     * mène à CE projet ; mon nom affiché posé dans la même écriture. Déjà membre : mon
     * nom seulement.
     */
    override suspend fun joinProject(code: String): CloudProject = lock.withLock {
        val u = requireUser()
        val clean = ShareCodes.normalized(code)
        if (!ShareCodes.isWellFormed(clean)) throw SyncException.InviteInvalidOrExpired
        requireOnline()
        val invite = readInvite(clean) ?: throw SyncException.InviteInvalidOrExpired
        val p = readProject(invite.projectId) ?: throw SyncException.JoinRefused
        val names = p.memberNames.orEmpty() + (u.uid to ProjectNames.displayName(u))
        if (isMember(p, u.uid)) {
            val renamed = p.copy(memberNames = names)
            saveProject(renamed)
            return@withLock renamed
        }
        if (u.uid in p.bannedUids.orEmpty()) throw SyncException.JoinRefused
        if (p.inviteCode != null) {
            if (clean != p.inviteCode) throw SyncException.JoinRefused
        } else if (invite.projectId != p.id) {
            throw SyncException.JoinRefused
        }
        val joined = p.copy(memberUids = p.memberUids + u.uid, memberNames = names)
        saveProject(joined, membershipChanged = true)
        joined
    }

    // MARK: Partage et modération (§5.2, §5.4-5.9)

    /** N4 / N5 : je me retire, mon nom part ; propriétaire → le premier autre membre hérite ; seul → refusé. */
    override suspend fun leaveProject(projectId: String) = lock.withLock {
        val u = requireUser()
        requireOnline()
        val p = memberProject(projectId)
        val others = p.memberUids - u.uid
        val owner = if (p.ownerUid == u.uid) others.firstOrNull() ?: throw SyncException.ActionRefused else p.ownerUid
        saveProject(p.copy(memberUids = others, ownerUid = owner, memberNames = p.memberNames?.minus(u.uid)),
            membershipChanged = true)
    }

    /** N6 : retrait + bannissement + nom effacé ; propriétaire seulement, la cible doit être un AUTRE membre. */
    override suspend fun removeMember(projectId: String, uid: String) = lock.withLock {
        val u = requireUser()
        requireOnline()
        val p = readProject(projectId)
        if (p == null || !isOwner(p, u.uid) || uid == u.uid || !isMember(p, uid)) throw SyncException.ActionRefused
        val banned = p.bannedUids.orEmpty().let { if (uid in it) it else it + uid }
        saveProject(p.copy(memberUids = p.memberUids - uid, bannedUids = banned,
            memberNames = p.memberNames?.minus(uid)), membershipChanged = true)
    }

    /** N7 : propriétaire seulement. */
    override suspend fun allowBack(projectId: String, uid: String) = lock.withLock {
        val u = requireUser()
        requireOnline()
        val p = readProject(projectId)
        if (p == null || !isOwner(p, u.uid)) throw SyncException.ActionRefused
        saveProject(p.copy(bannedUids = p.bannedUids?.minus(uid)))
    }

    /**
     * N2 : propriétaire seulement ; l'ancienne invitation n'est supprimée que si elle
     * existe et pointe vers ce projet (sinon elle est à quelqu'un d'autre : on n'y
     * touche pas).
     */
    override suspend fun rotateInviteCode(projectId: String): String = lock.withLock {
        val u = requireUser()
        requireOnline()
        rotateLocked(projectId, u)
    }

    private fun rotateLocked(projectId: String, u: AccountInfo): String {
        val p = readProject(projectId)
        if (p == null || !isOwner(p, u.uid)) throw SyncException.ActionRefused
        repeat(ShareCodes.MAX_ATTEMPTS) {
            val code = codeGenerator()
            if (ShareCodes.isWellFormed(code) && readInvite(code) == null) {
                val old = p.inviteCode
                writeInvite(code, p.id, u.uid)
                saveProject(p.copy(inviteCode = code))
                if (old != null && old != code && readInvite(old)?.projectId == p.id) deleteInvite(old)
                return code
            }
        }
        throw SyncException.BackendUnavailable
    }

    /** §5.2 : chez le propriétaire, code absent (adoption), disparu ou repris → nouveau code ; chez un membre, lecture seule. */
    override suspend fun ensureCurrentCode(projectId: String): ShareCodeResult = lock.withLock {
        val u = requireUser()
        requireOnline()
        val p = memberProject(projectId)
        if (p.ownerUid != u.uid) return@withLock ShareCodeResult(projectId, p.inviteCode)
        val current = p.inviteCode
        if (current != null && readInvite(current)?.projectId == projectId) {
            return@withLock ShareCodeResult(projectId, current)
        }
        val code = rotateLocked(projectId, u)
        ShareCodeResult(projectId, code, if (current == null) ShareCodeChange.ADOPTED else ShareCodeChange.REPLACED)
    }

    /** N9 : propriétaire seulement. */
    override suspend fun deleteProject(projectId: String) = lock.withLock {
        val u = requireUser()
        requireOnline()
        val p = readProject(projectId)
        if (p == null || !isOwner(p, u.uid)) throw SyncException.ActionRefused
        deleteProjectTree(p, u.uid)
        Unit
    }

    /**
     * Suppression d'un projet (N9, et projets solo de N10) : fichiers, sections,
     * journal, invitations de CE projet (les miennes + le code courant s'il pointe
     * encore vers lui ; celles des autres membres restent, inutilisables), puis pierre
     * tombale et projet. Rend les codes supprimés.
     */
    private fun deleteProjectTree(p: CloudProject, uid: String): Set<String> {
        val codes = allInvites().filter { it.createdBy == uid && it.projectId == p.id }.map { it.code }.toMutableSet()
        p.inviteCode?.let { if (readInvite(it)?.projectId == p.id) codes += it }
        for (code in codes) deleteInvite(code)
        writeText(tombstoneFile(p.id), JSONObject().put("deletedAt", nowEpoch()).toString())
        projectDir(p.id).deleteRecursively()
        bus.tryEmit(p.id to RemoteEvent.AccessLost)
        return codes
    }

    /** N8 : membre seulement ; nom tronqué à 80 unités UTF-16 (comme le backend réel). Pas de contrôle réseau : mis en file. */
    override suspend fun setMyProjectName(projectId: String, name: String) = lock.withLock {
        val u = requireUser()
        val p = memberProject(projectId)
        val clean = Utf16Text.truncated(name, ProjectNames.MAX_DISPLAY_NAME)
        saveProject(p.copy(memberNames = p.memberNames.orEmpty() + (u.uid to clean)))
    }

    // MARK: Blobs

    /**
     * Crochet de test : les `n` prochains [blobExists] répondent « absent », comme
     * Firebase quand getMetadata échoue sur le réseau (B1, §5.15).
     */
    @Volatile var failNextBlobExistsChecks: Int = 0

    /** Toute erreur = « absent » (comme Firebase) : non-membre compris. */
    override suspend fun blobExists(projectId: String, sha256: String): Boolean = lock.withLock {
        if (failNextBlobExistsChecks > 0) { failNextBlobExistsChecks--; return@withLock false }
        val u = currentUser ?: return@withLock false
        val p = readProject(projectId) ?: return@withLock false
        if (!isMember(p, u.uid)) return@withLock false
        blobFile(projectId, sha256).exists()
    }

    /**
     * Membres seulement ; JAMAIS d'écrasement d'un fichier présent (règle Storage
     * `resource == null`) : refusé, comme côté Firebase.
     */
    override suspend fun uploadBlob(
        projectId: String, sha256: String, kind: BlobKind,
        data: ByteArray, progress: ((Double) -> Unit)?
    ): BlobRef = lock.withLock {
        memberProject(projectId)
        val f = blobFile(projectId, sha256)
        if (f.exists()) throw SyncException.ActionRefused
        f.writeBytes(data)
        progress?.invoke(1.0)
        BlobRef(sha256, kind, data.size, f.absolutePath)
    }

    /** Lecture d'un fichier : membres seulement — refus = [SyncException.NotAMember], comme la règle Storage. */
    override suspend fun downloadBlob(
        projectId: String, ref: BlobRef, progress: ((Double) -> Unit)?
    ): ByteArray = lock.withLock {
        try { memberProject(projectId) } catch (e: SyncException) { throw SyncException.NotAMember }
        val f = blobFile(projectId, ref.sha256)
        if (!f.exists()) throw SyncException.BlobMissing
        f.readBytes().also { progress?.invoke(1.0) }
    }

    override suspend fun blobSize(projectId: String, sha256: String): Long? = lock.withLock {
        try { memberProject(projectId) } catch (e: SyncException) { throw SyncException.NotAMember }
        blobFile(projectId, sha256).takeIf { it.exists() }?.length()
    }

    // MARK: État par section

    override suspend fun pushSection(projectId: String, payload: SectionPayload, opId: String) {
        lock.withLock {
            val u = requireUser()
            val p = memberProject(projectId)
            val now = nowEpoch()
            writeText(sectionFile(projectId, payload.kind), JSONObject()
                .put("payloadJSON", SectionCodec.encode(payload))
                .put("opId", opId).put("updatedBy", u.uid).put("updatedAtEpoch", now).toString())
            saveProject(p.copy(updatedAtEpoch = now))
            bus.tryEmit(projectId to RemoteEvent.Section(RemoteChange(payload, opId, u.uid, now)))
        }
    }

    override suspend fun fetchSnapshot(projectId: String): ProjectSnapshot = lock.withLock {
        memberProject(projectId)
        val snap = ProjectSnapshot()
        for (kind in ProjectSectionKind.entries) {
            val o = readObj(sectionFile(projectId, kind)) ?: continue
            val json = o.optString("payloadJSON").takeIf { it.isNotBlank() } ?: continue
            SectionCodec.decode(json)?.let { snap.apply(it) }
        }
        snap
    }

    // MARK: Journal de modifications (audit)

    /**
     * Règle du journal (§5.14) : entrée signée par son auteur réel (`authorUid` = moi,
     * jamais vide) ; `authorName` = MON nom du projet si j'en ai un, sinon jamais le nom
     * d'un autre membre ; réécrire une entrée à l'identique est permis (sans effet), la
     * modifier non. Les entrées conformes passent, chacune pour soi (comme des
     * écritures Firestore séparées) ; une seule refusée suffit à lever
     * [SyncException.ActionRefused].
     */
    override suspend fun appendAudit(projectId: String, entries: List<AuditEntry>) {
        lock.withLock {
            val u = requireUser()
            val p = memberProject(projectId)
            if (entries.isEmpty()) return@withLock
            val arr = readArr(auditFile(projectId)) ?: JSONArray()
            val byId = HashMap<String, AuditEntry>()
            for (i in 0 until arr.length()) {
                SectionCodec.auditFromMap(jsonToMap(arr.getJSONObject(i)))?.let { byId.putIfAbsent(it.id, it) }
            }
            val names = p.memberNames.orEmpty()
            val accepted = ArrayList<AuditEntry>()
            var refused = false
            for (e in entries) {
                val existing = byId[e.id]
                if (existing != null) {
                    if (existing != e) refused = true
                    continue
                }
                val mine = names[u.uid]
                val nameOk = if (mine != null) e.authorName == mine else e.authorName !in names.values
                if (e.authorUid != u.uid || !nameOk) { refused = true; continue }
                accepted += e
                byId[e.id] = e
            }
            if (accepted.isNotEmpty()) {
                for (e in accepted) arr.put(JSONObject(SectionCodec.auditToMap(e)))
                writeText(auditFile(projectId), arr.toString())
                bus.tryEmit(projectId to RemoteEvent.Audit(accepted))
            }
            if (refused) throw SyncException.ActionRefused
        }
    }

    override suspend fun fetchAudit(projectId: String): List<AuditEntry> = lock.withLock {
        memberProject(projectId)
        val arr = readArr(auditFile(projectId)) ?: return@withLock emptyList()
        (0 until arr.length()).mapNotNull { SectionCodec.auditFromMap(jsonToMap(arr.getJSONObject(it))) }
    }

    override suspend fun publishMVRVersion(projectId: String, sha256: String): Int = lock.withLock {
        val u = requireUser()
        val p = memberProject(projectId)
        val next = p.mvrVersion + 1
        saveProject(p.copy(mvrVersion = next, mvrBlobSHA = sha256, updatedAtEpoch = nowEpoch()))
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

    /** JSONObject → Map, en PROFONDEUR (JSONObject→Map, JSONArray→List, NULL→null) : `memberNames` est une carte. */
    private fun jsonToMap(o: JSONObject): Map<String, Any?> = buildMap {
        o.keys().forEach { k -> put(k, jsonValue(o.get(k))) }
    }

    private fun jsonValue(v: Any?): Any? = when (v) {
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { jsonValue(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    /** Identifiant de document utilisable dans un chemin (jamais de « / » ni de « .. »). */
    private fun isDocumentId(id: String): Boolean = id.isNotEmpty() && '/' !in id && id != "." && id != ".."
}
