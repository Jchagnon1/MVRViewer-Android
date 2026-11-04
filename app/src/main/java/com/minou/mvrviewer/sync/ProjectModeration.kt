package com.minou.mvrviewer.sync

import android.content.Context
import androidx.annotation.StringRes
import com.minou.mvrviewer.R
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.UUID

// Partage et modération côté ViewModel — briques HORS ViewModel, testables en JVM
// sur le bouchon [LocalSyncService] (comme [DownloadedProjects], [MvrVersionRule]).
// Contrat : MVRViewerWeb/SPEC-partage-moderation.md §5 ; même comportement qu'iOS
// (SyncCoordinator) et que le site.

/**
 * Message d'APPARTENANCE à montrer une fois (§5.12, §6.3) : accès perdu (retiré, ou
 * projet supprimé par son propriétaire), projet quitté, projet supprimé par moi.
 */
data class ProjectNotice(
    val projectId: String,
    val projectName: String,
    val kind: Kind,
    val id: String = UUID.randomUUID().toString()
) {
    enum class Kind { ACCESS_LOST, LEFT, DELETED }

    @get:StringRes val messageRes: Int
        get() = when (kind) {
            Kind.ACCESS_LOST -> R.string.sync_notice_access_lost_fmt
            Kind.LEFT -> R.string.sync_notice_left_fmt
            Kind.DELETED -> R.string.sync_notice_deleted_fmt
        }

    fun message(ctx: Context): String = ctx.getString(messageRes, projectName)
}

/**
 * Le code courant vient de changer SANS geste de l'utilisateur (§5.2) : adoption d'un
 * projet ancien, ou code disparu / repris remplacé. Message à montrer à la prochaine
 * ouverture de « Partager le projet ».
 */
data class ShareCodeNotice(val projectId: String, val code: String, val change: ShareCodeChange) {
    @get:StringRes val messageRes: Int
        get() = when (change) {
            ShareCodeChange.ADOPTED -> R.string.sync_code_adopted
            ShareCodeChange.REPLACED -> R.string.sync_code_replaced_fmt
        }

    fun message(ctx: Context): String = when (change) {
        ShareCodeChange.ADOPTED -> ctx.getString(messageRes)
        ShareCodeChange.REPLACED -> ctx.getString(messageRes, code)
    }
}

/** Une annulation de la coroutine n'est jamais avalée ; toute autre erreur → null. */
internal suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** FICHIERS : jamais d'écrasement (§5.15). */
object ProjectBlobs {
    /** B1 puis B2 seulement si le fichier est absent. */
    suspend fun putIfAbsent(
        service: SyncService, projectId: String, sha256: String, kind: BlobKind, data: ByteArray,
        progress: ((Double) -> Unit)? = null
    ) {
        if (service.blobExists(projectId, sha256)) return
        uploadAbsent(service, projectId, sha256, kind, data, progress)
    }

    /**
     * B2 d'un fichier jugé absent. Les règles refusent d'écraser un fichier présent, par
     * qui que ce soit : un envoi REFUSÉ est suivi d'une relecture ; le fichier est là (un
     * autre appareil l'a envoyé, ou B1 avait échoué sur le réseau) → succès ; sinon,
     * l'erreur d'origine.
     */
    suspend fun uploadAbsent(
        service: SyncService, projectId: String, sha256: String, kind: BlobKind, data: ByteArray,
        progress: ((Double) -> Unit)? = null
    ) {
        try {
            service.uploadBlob(projectId, sha256, kind, data, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt { service.blobExists(projectId, sha256) } == true) return
            throw e
        }
    }
}

/**
 * PUBLIER (§5.1) : NOUVEAU projet = projet + invitation dans un lot attendu (code
 * courant posé dès la création, nom tronqué à 200 unités UTF-16) ; projet EXISTANT =
 * aucune invitation créée, le code est celui du projet (vérifié chez le propriétaire :
 * adoption d'un projet ancien, code disparu ou repris → nouveau code ; null pour un
 * membre d'un projet ancien). Puis le binaire (dédup SHA-256, jamais d'écrasement) et
 * la version. La garde Pro reste chez l'appelant (inchangée).
 */
class SharePublisher(private val service: SyncService) {

    data class Published(val project: CloudProject, val version: Int, val code: ShareCodeResult)

    /**
     * [onProject] : le projet devient le projet courant AVANT l'envoi (écouteurs) ;
     * [onUploading] : progression de l'envoi du .mvr ; [onVersion] : la version publiée,
     * AVANT la vérification du code (même ordre qu'iOS).
     */
    suspend fun publish(
        existing: CloudProject?, contentKey: String, name: String, sha256: String, data: ByteArray,
        onProject: (CloudProject) -> Unit = {},
        onUploading: (Double) -> Unit = {},
        onVersion: (CloudProject, Int) -> Unit = { _, _ -> }
    ): Published {
        val project = existing ?: service.createProject(contentKey, name)
        onProject(project)
        if (!service.blobExists(project.id, sha256)) {
            onUploading(0.0)
            ProjectBlobs.uploadAbsent(service, project.id, sha256, BlobKind.MVR, data) { onUploading(it) }
        }
        val version = service.publishMVRVersion(project.id, sha256)
        onVersion(project, version)
        val code = if (existing == null) ShareCodeResult(project.id, project.inviteCode)
        else service.ensureCurrentCode(project.id)
        return Published(project, version, code)
    }
}

/**
 * Appartenance au projet ouvert (§5.11, §5.12, §5.14) — règles pures.
 */
object ProjectMembership {
    /**
     * ACCÈS PERDU (§5.12) : écouteur refusé ou document supprimé, ou un état du projet
     * où je ne suis plus membre.
     */
    fun lostAccess(event: RemoteEvent, me: String?): Boolean = when (event) {
        is RemoteEvent.AccessLost -> true
        is RemoteEvent.MembershipChanged -> me != null && me !in event.memberUids
        is RemoteEvent.ProjectUpdated -> me != null && me !in event.project.memberUids
        else -> false
    }

    /**
     * Le projet courant après un nouvel état du document : membres, propriétaire, noms,
     * bannis et code courant sont repris (la version du .mvr suit [MvrVersionRule]).
     */
    fun merged(current: CloudProject, incoming: CloudProject): CloudProject = current.copy(
        name = incoming.name, ownerUid = incoming.ownerUid, memberUids = incoming.memberUids,
        inviteCode = incoming.inviteCode, bannedUids = incoming.bannedUids, memberNames = incoming.memberNames
    )

    /**
     * Auteur d'une modification (§5.14) : connecté, `authorUid` = mon uid (jamais vide)
     * et `authorName` = MON nom dans le projet partagé (tel que lu par l'écouteur), à
     * défaut mon nom affiché (§4 : jamais l'e-mail complet). Sans compte : [offlineName]
     * et un uid vide (l'entrée reste locale).
     */
    fun author(account: AccountInfo?, project: CloudProject?, offlineName: String): Pair<String, String> {
        account ?: return "" to offlineName
        val inProject = project?.memberNames?.get(account.uid)
        return account.uid to (inProject ?: ProjectNames.displayName(account))
    }

    /** Mon nom à écrire dans le projet à l'ouverture (§5.8) ; null s'il est déjà à jour. */
    fun nameToWrite(project: CloudProject, account: AccountInfo): String? {
        val name = ProjectNames.displayName(account)
        return name.takeIf { project.memberNames?.get(account.uid) != it }
    }

    /**
     * OUVERTURE d'un projet partagé (§5.11), sans bloquer l'ouverture : (1) mon nom s'il
     * manque ou a changé (§5.8) ; (2) chez le propriétaire, le code courant vérifié (§5.2 :
     * adoption, code disparu ou repris → nouveau code). Erreurs ignorées : nouvel essai à
     * la prochaine ouverture. GRATUIT (aucune garde Pro : appartenance, pas contenu).
     * Rend le résultat de la vérification (null : membre, ou échec).
     */
    suspend fun openMaintenance(service: SyncService, project: CloudProject, account: AccountInfo): ShareCodeResult? {
        nameToWrite(project, account)?.let { name -> attempt { service.setMyProjectName(project.id, name) } }
        if (project.ownerUid != account.uid) return null
        return attempt { service.ensureCurrentCode(project.id) }
    }
}

/**
 * Table LOCALE « uid → nom » des personnes que J'AI retirées d'un projet (§5.5) : le
 * document partagé n'en garde jamais le nom (politique de confidentialité) ; la liste
 * « Personnes retirées » le lit ici, sinon « Membre sans nom ».
 */
class RemovedMembers(private val store: Store) {
    interface Store {
        fun load(): String?
        fun save(json: String)
    }

    private fun all(): JSONObject = store.load()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun names(projectId: String): Map<String, String> {
        val o = all().optJSONObject(projectId) ?: return emptyMap()
        return buildMap { o.keys().forEach { k -> (o.opt(k) as? String)?.let { put(k, it) } } }
    }

    fun record(projectId: String, uid: String, name: String) {
        val root = all()
        val o = root.optJSONObject(projectId) ?: JSONObject()
        o.put(uid, name)
        root.put(projectId, o)
        store.save(root.toString())
    }

    fun forget(projectId: String, uid: String) {
        val root = all()
        val o = root.optJSONObject(projectId) ?: return
        o.remove(uid)
        if (o.length() == 0) root.remove(projectId) else root.put(projectId, o)
        store.save(root.toString())
    }
}

/** Une ligne de la liste des membres ou des personnes retirées (§6.2). */
data class ProjectMemberRow(
    val uid: String,
    /** Nom affiché (§4) ; « Membre sans nom » à défaut ; homonymes suivis de « · » et des 4 premiers caractères de l'uid. */
    val label: String,
    val isMe: Boolean,
    val isOwner: Boolean
)

/**
 * Membres d'un projet partagé tels que la fenêtre « Partager le projet » et le menu de
 * ligne de « Projets partagés » les montrent (§6.1-6.3) — calcul PUR, même règle qu'iOS
 * (`ProjectMembers`). Le nom vient de `memberNames` (jamais du profil d'un autre compte).
 * [unnamed] = « Membre sans nom » dans la langue de l'interface.
 */
object ProjectMembers {
    /** Membres de `memberUids` : propriétaire d'abord, puis par nom. */
    fun rows(p: CloudProject, me: String, unnamed: String): List<ProjectMemberRow> {
        val uids = p.memberUids.distinct()
        val names = p.memberNames.orEmpty()
        val labels = labelled(uids, unnamed) { names[it] }
        return uids.map { ProjectMemberRow(it, labels[it] ?: unnamed, it == me, it == p.ownerUid) }
            .sortedWith(compareBy<ProjectMemberRow> { !it.isOwner }.then(byLabel))
    }

    /**
     * Personnes retirées (`bannedUids`) : le nom vient de la table LOCALE de l'appareil qui
     * a fait le retrait (§5.5), sinon « Membre sans nom ».
     */
    fun removedRows(p: CloudProject, localNames: Map<String, String>, unnamed: String): List<ProjectMemberRow> {
        val uids = p.bannedUids.orEmpty().filter { it !in p.memberUids }.distinct()
        val labels = labelled(uids, unnamed) { localNames[it] }
        return uids.map { ProjectMemberRow(it, labels[it] ?: unnamed, isMe = false, isOwner = false) }.sortedWith(byLabel)
    }

    /** Nom affiché d'un membre (homonymes compris) — titre de « Retirer… », héritier, propriétaire du signalement. */
    fun label(uid: String, p: CloudProject, unnamed: String): String =
        rows(p, "", unnamed).firstOrNull { it.uid == uid }?.label
            ?: p.memberNames?.get(uid)?.trim()?.takeIf { it.isNotEmpty() } ?: unnamed

    /** HÉRITIER de la propriété si le propriétaire [me] quitte (§5.4) : le premier autre membre, donc le plus ancien. */
    fun heir(p: CloudProject, me: String): String? = p.memberUids.firstOrNull { it != me }

    /** Propriétaire SEUL : pas de « Quitter », seulement « Supprimer le projet ». */
    fun isOwnerAlone(p: CloudProject, me: String): Boolean = p.ownerUid == me && heir(p, me) == null

    /** Ordre « par nom » : sans tenir compte de la casse ni des accents, puis l'uid (ordre stable). */
    private val byLabel: Comparator<ProjectMemberRow> = run {
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        Comparator<ProjectMemberRow> { a, b -> collator.compare(a.label, b.label) }.thenBy { it.uid }
    }

    /**
     * Libellés uid → nom, avec la règle des HOMONYMES (§4) : un nom porté par plusieurs
     * comptes de la liste (« Membre sans nom » compris) est suivi de « · » et des 4
     * premiers caractères de l'uid, en majuscules.
     */
    private fun labelled(uids: List<String>, unnamed: String, name: (String) -> String?): Map<String, String> {
        val base = uids.associateWith { uid -> name(uid)?.trim()?.takeIf { it.isNotEmpty() } ?: unnamed }
        val count = base.values.groupingBy { it }.eachCount()
        return base.mapValues { (uid, n) -> if ((count[n] ?: 0) > 1) "$n · ${uid.take(4).uppercase()}" else n }
    }
}

/**
 * SIGNALER UN CONTENU (SPEC-partage-moderation §5.7, §6.5) — gratuit, tout membre, sans
 * aucune écriture : un e-mail PRÉREMPLI vers l'assistance, jamais envoyé par l'app
 * (l'utilisateur l'envoie, le corrige ou l'abandonne dans sa messagerie). Calcul pur,
 * testé en JVM sur les vrais strings.xml : [make] reçoit le résolveur de textes.
 */
data class ContentReport(
    val projectName: String,
    val projectId: String,
    val subject: String,
    val body: String
) {
    /** `mailto:` (RFC 6068) : objet et corps encodés en entier (seuls les caractères non réservés ASCII restent tels quels — un « & » ou un « = » dans le nom du projet ne coupe jamais l'adresse), lignes en CRLF. */
    val mailto: String
        get() {
            val crlf = body.replace("\r\n", "\n").replace("\n", "\r\n")
            return "mailto:$ADDRESS?subject=${encode(subject)}&body=${encode(crlf)}"
        }

    /** « Copier le signalement » (pas de messagerie) : l'objet puis le corps. */
    val clipboardText: String get() = "$subject\n\n$body"

    companion object {
        /** Adresse de l'assistance : la SEULE du code ([com.minou.mvrviewer.LegalLinks.SUPPORT_EMAIL]). */
        const val ADDRESS = com.minou.mvrviewer.LegalLinks.SUPPORT_EMAIL
        /** Plateforme écrite dans le corps (« Application : MVR Viewer 1.10.0 (21) (Android) »). */
        const val PLATFORM = "Android"

        /**
         * Le signalement du projet [p] par le compte [account], dans la langue de
         * l'interface ([text] résout une ressource et ses arguments) : objet et corps du
         * §6.5. Nom du signaleur = §4 (jamais l'e-mail complet ; il peut le corriger avant
         * d'envoyer). [unnamedProject] = nom d'un projet sans nom.
         */
        fun make(
            p: CloudProject, account: AccountInfo, version: String,
            unnamedMember: String, unnamedProject: String,
            text: (id: Int, args: List<Any>) -> String
        ): ContentReport {
            val name = p.name.ifBlank { unnamedProject }
            val owner = ProjectMembers.label(p.ownerUid, p, unnamedMember)
            val subject = text(R.string.sync_report_subject_fmt, listOf(name, p.id))
            val lines = listOf(
                text(R.string.sync_report_project_fmt, listOf(name)),
                text(R.string.sync_report_project_id_fmt, listOf(p.id)),
                text(R.string.sync_report_owner_fmt, listOf(owner)),
                text(R.string.sync_report_name_fmt, listOf(ProjectNames.displayName(account))),
                text(R.string.sync_report_account_fmt, listOf(account.email, account.uid)),
                text(R.string.sync_report_app_fmt, listOf(version, PLATFORM)),
                "",
                text(R.string.sync_report_reasons, emptyList()),
                "",
                "",
                text(R.string.sync_report_good_faith, emptyList())
            )
            return ContentReport(name, p.id, subject, lines.joinToString("\n"))
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** Encodage « pourcent » UTF-8 de tout sauf les caractères non réservés (RFC 3986). */
        fun encode(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF)
                if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) sb.append(c.toChar())
                else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
            return sb.toString()
        }
    }
}

/** Sur l'appareil : préférences `mvrviewer.sync.removedMembers`. */
class PrefsRemovedMembersStore(context: Context) : RemovedMembers.Store {
    private val prefs = context.applicationContext
        .getSharedPreferences("mvrviewer.sync.removedMembers", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(json: String) { prefs.edit().putString(KEY, json).apply() }

    private companion object { const val KEY = "byProject" }
}
