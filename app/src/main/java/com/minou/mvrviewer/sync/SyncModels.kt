package com.minou.mvrviewer.sync

/**
 * Modèles de synchronisation cloud — PORTAGE DIRECT des types iOS
 * (`Models/Sync/SyncService.swift` + `ProjectSnapshot.swift`).
 *
 * IMPORTANT — parité cross-platform : iOS et Android partagent le MÊME backend
 * Firebase et le MÊME modèle de données. Les noms de champs ci-dessous doivent
 * rester IDENTIQUES à ceux de Swift, car :
 *   - les docs Firestore `CloudProject` / `AuditEntry` sont mappés par nom de champ ;
 *   - le payload d'une section est un JSON à l'ENVELOPPE Swift `{"<kind>":{"_0": dto}}`
 *     (voir [SectionCodec]) — les clés internes du DTO doivent matcher Swift.
 *
 * Ne PAS renommer un champ sans le renommer aussi côté iOS.
 */

/** Compte connecté (identité minimale — jamais de mot de passe / token ici). */
data class AccountInfo(
    val uid: String,
    val email: String,
    val displayName: String
)

/**
 * Un projet cloud : identité stable d'équipe (`id`) + clé de réunion locale
 * (`contentKey` = empreinte FNV-1a du .mvr, == [ProjectStore.keyFor]) + pointeurs
 * de blobs + membres. Mappé 1:1 sur le doc Firestore `/projects/{id}`.
 *
 * Partage et modération (SPEC-partage-moderation.md §3.1) : [inviteCode],
 * [bannedUids], [memberNames] sont OPTIONNELS — un projet ancien (écrit par un
 * client d'avant) n'en a aucun et se lit inchangé. Le document n'est écrit en
 * entier qu'à sa création (J1, [SectionCodec.projectToMap] omet les nuls) ;
 * ensuite, seules des mises à jour CHAMP PAR CHAMP. `joinProof` n'est lu par
 * aucun client (clé ignorée).
 */
data class CloudProject(
    val id: String,
    val contentKey: String,
    val name: String,
    val ownerUid: String,
    /** ORDRE D'ARRIVÉE (les règles le garantissent) : l'héritier de la propriété est le premier autre membre. */
    val memberUids: List<String>,
    val mvrVersion: Int,
    val mvrBlobSHA: String?,
    val refPlanBlobSHA: String?,
    val updatedAtEpoch: Double,
    /** Code COURANT : présent = projet « strict », seul ce code fait entrer ; absent = projet ancien. */
    val inviteCode: String? = null,
    /** Comptes retirés par le propriétaire : ne peuvent plus revenir. */
    val bannedUids: List<String>? = null,
    /** Nom affiché de chaque membre (uid → nom, 80 unités UTF-16 au plus) ; chacun n'écrit que le sien. */
    val memberNames: Map<String, String>? = null
) {
    /** Projet « strict » (a un code courant). */
    val isStrict: Boolean get() = inviteCode != null
}

/**
 * Code de partage à afficher pour un projet (SPEC-partage-moderation §5.1, §5.2) —
 * rendu par « Publier » et par la vérification du code courant.
 * [code] null = projet ANCIEN vu par un membre non propriétaire (« Demandez le code
 * au propriétaire du projet. ») ; [change] = ce que la vérification vient de changer
 * (message à montrer une fois).
 */
data class ShareCodeResult(
    val projectId: String,
    val code: String?,
    val change: ShareCodeChange? = null
)

enum class ShareCodeChange {
    /** Projet ancien : il a maintenant un code courant (« Ce projet a maintenant un code unique… »). */
    ADOPTED,
    /** Code courant disparu (son auteur a supprimé son compte) ou repris par un autre projet : remplacé. */
    REPLACED
}

/** Code d'invitation (partage par code court). */
data class InviteCode(
    val code: String,
    val projectId: String,
    /**
     * `mvrviewer://join/CODE` : n'est plus affiché ni partagé (guide de mise en
     * vente C4 — aucun filtre d'intention ne l'ouvre). Toujours écrit dans le
     * document `invites` (champ `url`) tant que la spec du partage n'a pas
     * changé : ne pas le retirer d'un seul côté (iOS, site, règles).
     */
    val url: String,
    val expiresAtEpoch: Double? = null,
    /** Auteur de l'invitation (champ `createdBy`) : la suppression de compte efface toutes les siennes. */
    val createdBy: String? = null
)

enum class BlobKind(val raw: String) {
    MVR("mvr"), REF_PLAN("refPlan"),
    /** 3D importée d'un type de projecteur MA (SPEC-ma.md §4.3) — usage LOCAL seulement,
     *  jamais sérialisé dans Firestore (le chemin Storage ne dépend que du SHA). */
    FIXTURE_3D("fixture3D");

    companion object {
        fun from(raw: String): BlobKind = entries.firstOrNull { it.raw == raw } ?: MVR
    }
}

/** Référence de blob (adressé par SHA-256, dédupliqué). */
data class BlobRef(
    val sha256: String,
    val kind: BlobKind,
    val size: Int,
    val remotePath: String
)

/**
 * Une entrée du JOURNAL DE MODIFICATIONS (audit) : qui a changé quoi, quand, et
 * l'ancienne → la nouvelle valeur. Append-only. Mappée sur le doc Firestore
 * `/projects/{id}/audit/{id}` — mêmes noms de champs que Swift `AuditEntry`.
 */
data class AuditEntry(
    val id: String,
    val epoch: Double,
    val authorUid: String,
    val authorName: String,
    val kind: String,     // "patch" | "plan" | "calibration" | "layers" | …
    val target: String,   // ex. « Projecteur N° 213 »
    val field: String,    // ex. « Adresse »
    val oldValue: String, // ex. « 1.147 » (— si vide)
    val newValue: String, // ex. « 1.189 »
    // ---- Coordonnées MACHINE de la modification (annulation) ----------------
    // Les 5 champs ci-dessus ne sont que de l'AFFICHAGE : « Projecteur N° 213 »
    // redevient ambigu dès qu'on repatche, et « 1.147 » est déjà formaté. Sans
    // ces coordonnées, on ne peut pas rejouer la valeur d'origine. Optionnels :
    // les entrées écrites avant cette version (et par une version plus ancienne
    // de l'autre plateforme) restent lisibles — simplement non annulables.
    // NOMS PARTAGÉS iOS/Android : ne pas renommer d'un seul côté.
    val objectKey: String? = null, // identité de la cible (clé d'instance MVR / uuid) ; "" = le projet
    val fieldKey: String? = null,  // champ visé : "fixtureId" | "address" | "mode" | AuditFieldKey.*
    val oldRaw: String? = null,    // valeur AVANT, brute (non formatée) — c'est elle qu'on réapplique
    val newRaw: String? = null     // valeur APRÈS, brute
) {
    /**
     * Annulable seulement si l'entrée porte ses coordonnées machine. `oldRaw`
     * peut valoir "" (champ vidé), d'où le test sur la nullité, pas le vide.
     */
    val isUndoable: Boolean get() = fieldKey != null && oldRaw != null
}

/** Clés machine de champ (partagées iOS/Android — valeurs sérialisées telles quelles). */
object AuditFieldKey {
    const val FIXTURE_ID = "fixtureId"
    const val ADDRESS = "address"
    const val MODE = "mode"
    const val NAME = "name"
    const val GEO_ANCHORS = "geoAnchors"
    const val REF_PLAN_TRANSFORM = "refPlanTransform"
    const val REF_PLAN_HIDDEN_LAYERS = "refPlanHiddenLayers"
    // Type de rendu custom assigné à un projecteur (« custom:<id> » ou vide).
    const val SPEC = "spec"
}

/** Sections d'état synchronisées indépendamment (LWW par section). */
enum class ProjectSectionKind(val raw: String) {
    MANIFEST("manifest"),
    CALIBRATION("calibration"),
    PATCH("patch"),
    LAYER_COLORS("layerColors"),
    LABEL_SIDES("labelSides"),
    FIXTURE_ORIENTATIONS("fixtureOrientations"),
    GDTF_MAPPINGS("gdtfMappings"),
    POWER_CABLING("powerCabling"),
    DMX_CABLING("dmxCabling"),
    // Projecteurs custom V1 : la LISTE des DÉFINITIONS de types custom utilisés
    // dans le projet (raw IDENTIQUE iOS/Android). L'assignation fixture→custom,
    // elle, voyage via la section PATCH (cf. PatchEntryDTO.spec).
    CUSTOM_FIXTURES("customFixtures");

    companion object {
        fun from(raw: String): ProjectSectionKind? = entries.firstOrNull { it.raw == raw }
    }
}

// MARK: - DTO de section (forme « cloud » de chaque partie synchronisable) -----
//
// Miroir exact des DTO Swift de ProjectSnapshot.swift. Les valeurs par défaut
// reproduisent celles de Swift (sérialisées seulement si présentes côté iOS via
// l'omission des Optionals nuls — cf. SectionCodec).

/** Placement réglable du plan DXF — miroir de `RefPlanTransformDTO`. */
data class RefPlanTransformDTO(
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val scale: Double = 1.0,
    val heightZ: Double = 0.0,
    val visible: Boolean = true
)

/** Manifeste projet — miroir de `ManifestDTO` (sous-ensemble de ProjectManifest). */
data class ManifestDTO(
    val mvrName: String = "",
    val dxfName: String? = null,
    val refPlanTransform: RefPlanTransformDTO? = null,
    val refPlanHiddenLayers: List<String> = emptyList(),
    val showUserLocation: Boolean? = null,
    val showSatellite: Boolean? = null,
    val refPlanBlobSHA: String? = null
)

/** Une empreinte de patch DMX indexée par `mvrUUID` — miroir de `PatchEntryDTO`. */
data class PatchEntryDTO(
    val mvrUUID: String,
    val fixtureId: String? = null,
    val addresses: List<String> = emptyList(),
    val gdtfModeName: String? = null,
    // Override de NOM d'affichage par instance (clé `name` PARTAGÉE iOS/Android).
    // Optionnel : encodé seulement si non nul (encodeIfPresent côté Swift), donc
    // un projecteur non renommé ne porte pas cette clé.
    val name: String? = null,
    // Override de SPEC de rendu par instance (projecteurs custom V1) : « custom:<id> »
    // quand un type custom est assigné à CE projecteur, sinon absent. Clé `spec`
    // PARTAGÉE iOS/Android, optionnelle (encodeIfPresent) → un projecteur non
    // custom ne porte pas cette clé. C'est le CANAL de l'assignation fixture→custom
    // (la section per-projecteur déjà synchronisée), pas la section gdtfMappings.
    val spec: String? = null
)

data class PatchDTO(val entries: List<PatchEntryDTO> = emptyList())

/** Ancre de calibration GPS — miroir de `GeoAnchorDTO`. */
data class GeoAnchorDTO(
    val worldX: Float,
    val worldY: Float,
    val latitude: Double,
    val longitude: Double
)

/** Point du relevé « 3 points » — miroir de `GeoSurveyPointDTO` (tous les champs requis). */
data class GeoSurveyPointDTO(
    val worldX: Float,
    val worldY: Float,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val residual: Double
)

/**
 * Relevé de la calibration guidée (SPEC-calibration-3-points §5) — miroir de
 * `GeoSurveyDTO`. `edgePrecision` est OMIS quand il est nul (jamais `null`).
 */
data class GeoSurveyDTO(
    val method: String,
    val points: List<GeoSurveyPointDTO>,
    val precision: Double,
    val freeScale: Double,
    val epoch: Double,
    val edgePrecision: Double? = null
)

/**
 * Section `calibration`. `survey` est OMIS quand il est nul : une calibration
 * ordinaire garde exactement sa forme historique. Il n'est lu ET réécrit
 * qu'avec EXACTEMENT 2 ancres (les deux ancres synthétiques qu'il a produites).
 */
data class GeoCalibrationDTO(
    val anchors: List<GeoAnchorDTO> = emptyList(),
    val survey: GeoSurveyDTO? = null
)

/** Overrides de couleur de calque `[layerName: "#RRGGBB"]` — miroir de `LayerColorsDTO`. */
data class LayerColorsDTO(val overrides: Map<String, String> = emptyMap())

/** Côté d'étiquette par projecteur `[stableKey: side]` — miroir de `LabelSidesDTO`. */
data class LabelSidesDTO(val sides: Map<String, String> = emptyMap())

/** Retournements Pan/Tilt par projecteur `[stableKey: [pan180, tilt180]]`. */
data class FixtureOrientationsDTO(val orientations: Map<String, List<Boolean>> = emptyMap())

/** Choix manuel de modèle GDTF Share `[specName: rid]` — miroir de `GDTFMappingsDTO`. */
data class GDTFMappingsDTO(val mappings: Map<String, Int> = emptyMap())

/**
 * Payload typé d'une section (porte le DTO correspondant). Miroir du
 * `enum ProjectSectionPayload` Swift. L'encodage JSON produit l'enveloppe
 * `{"<kind>":{"_0": dto}}` (cf. [SectionCodec]).
 */
sealed class SectionPayload {
    abstract val kind: ProjectSectionKind

    data class Manifest(val dto: ManifestDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.MANIFEST
    }
    data class Calibration(val dto: GeoCalibrationDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.CALIBRATION
    }
    data class Patch(val dto: PatchDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.PATCH
    }
    data class LayerColors(val dto: LayerColorsDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.LAYER_COLORS
    }
    data class LabelSides(val dto: LabelSidesDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.LABEL_SIDES
    }
    data class FixtureOrientations(val dto: FixtureOrientationsDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.FIXTURE_ORIENTATIONS
    }
    data class GdtfMappings(val dto: GDTFMappingsDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.GDTF_MAPPINGS
    }
    data class PowerCabling(val dto: PowerCablingDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.POWER_CABLING
    }
    data class DmxCabling(val dto: DmxCablingDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.DMX_CABLING
    }
    data class CustomFixtures(val dto: CustomFixturesDTO) : SectionPayload() {
        override val kind get() = ProjectSectionKind.CUSTOM_FIXTURES
    }
}

/**
 * Instantané complet d'un projet (pull-au-ouverture) — miroir de
 * `ProjectSnapshot`. Chaque section est optionnelle (absente = jamais poussée).
 * Fusionnée CHAMP PAR CHAMP côté ViewModel, jamais en bloc.
 */
data class ProjectSnapshot(
    var manifest: ManifestDTO? = null,
    var calibration: GeoCalibrationDTO? = null,
    var patch: PatchDTO? = null,
    var layerColors: LayerColorsDTO? = null,
    var labelSides: LabelSidesDTO? = null,
    var fixtureOrientations: FixtureOrientationsDTO? = null,
    var gdtfMappings: GDTFMappingsDTO? = null,
    var powerCabling: PowerCablingDTO? = null,
    var dmxCabling: DmxCablingDTO? = null,
    var customFixtures: CustomFixturesDTO? = null
) {
    /** Applique un payload dans l'instantané (reconstruction section-par-section). */
    fun apply(p: SectionPayload) {
        when (p) {
            is SectionPayload.Manifest -> manifest = p.dto
            is SectionPayload.Calibration -> calibration = p.dto
            is SectionPayload.Patch -> patch = p.dto
            is SectionPayload.LayerColors -> layerColors = p.dto
            is SectionPayload.LabelSides -> labelSides = p.dto
            is SectionPayload.FixtureOrientations -> fixtureOrientations = p.dto
            is SectionPayload.GdtfMappings -> gdtfMappings = p.dto
            is SectionPayload.PowerCabling -> powerCabling = p.dto
            is SectionPayload.DmxCabling -> dmxCabling = p.dto
            is SectionPayload.CustomFixtures -> customFixtures = p.dto
        }
    }
}

/** Un changement distant reçu par un observateur (miroir de `RemoteChange`). */
data class RemoteChange(
    val payload: SectionPayload,
    val opId: String,
    val updatedBy: String,
    val updatedAtEpoch: Double
) {
    val kind get() = payload.kind
}

/** Événement distant diffusé par `observe` (miroir de `RemoteEvent`). */
sealed class RemoteEvent {
    data class Section(val change: RemoteChange) : RemoteEvent()
    data class MvrVersion(val version: Int, val sha256: String, val updatedBy: String) : RemoteEvent()
    data class MembershipChanged(val memberUids: List<String>) : RemoteEvent()
    data class Audit(val entries: List<AuditEntry>) : RemoteEvent()
    /**
     * Nouvel état du document projet (écouteur J4) : membres, propriétaire, noms,
     * bannis, code courant. Le ViewModel y lit son nom du projet (auteur du journal,
     * §5.14) et y voit s'il n'est plus membre (§5.12).
     */
    data class ProjectUpdated(val project: CloudProject) : RemoteEvent()
    /**
     * Accès au projet perdu (§5.12) : écouteur refusé (`permission-denied`) ou
     * document supprimé — retiré, ou projet supprimé.
     */
    data object AccessLost : RemoteEvent()
}

/**
 * Erreurs de synchronisation (miroir de `SyncError`).
 *
 * Chaque cas porte une CLÉ DE RESSOURCE ([messageRes]) : c'est elle qui est
 * affichée à l'utilisateur (via [syncMessage]), dans la langue du téléphone. Le
 * `message` de l'exception reste en français — il ne sert qu'aux journaux.
 */
sealed class SyncException(
    message: String,
    @androidx.annotation.StringRes val messageRes: Int? = null
) : Exception(message) {
    object NotSignedIn : SyncException("Vous n'êtes pas connecté.", com.minou.mvrviewer.R.string.sync_err_not_signed_in)
    object InvalidCredentials : SyncException("E-mail ou mot de passe incorrect.", com.minou.mvrviewer.R.string.sync_err_invalid_credentials)
    object EmailInUse : SyncException("Cet e-mail est déjà utilisé.", com.minou.mvrviewer.R.string.sync_err_email_in_use)
    object WeakPassword : SyncException("Mot de passe trop court (6 caractères minimum).", com.minou.mvrviewer.R.string.sync_err_weak_password)
    object ProjectNotFound : SyncException("Projet introuvable.", com.minou.mvrviewer.R.string.sync_err_project_not_found)
    object InviteInvalidOrExpired : SyncException("Code de partage invalide ou expiré.", com.minou.mvrviewer.R.string.sync_err_invite_invalid)
    object NotAMember : SyncException("Vous n'avez pas accès à ce projet.", com.minou.mvrviewer.R.string.sync_err_not_a_member)
    object BlobMissing : SyncException("Fichier absent du cloud.", com.minou.mvrviewer.R.string.sync_err_blob_missing)
    object BackendUnavailable : SyncException("Service de synchronisation indisponible.", com.minou.mvrviewer.R.string.sync_err_backend_unavailable)
    object SignUpFailed : SyncException("Création de compte impossible.", com.minou.mvrviewer.R.string.sync_err_signup_failed)
    object AuthFailed : SyncException("Erreur d'authentification.", com.minou.mvrviewer.R.string.sync_err_auth_failed)
    object NetworkUnavailable : SyncException("Connexion réseau indisponible. Réessayez.", com.minou.mvrviewer.R.string.sync_err_network)
    object RecentLoginRequired : SyncException("Par sécurité, reconnectez-vous puis réessayez.", com.minou.mvrviewer.R.string.sync_err_recent_login)
    /**
     * Suppression de compte INCOMPLÈTE : les données Firestore (profil,
     * adhésions, invitations) sont déjà effacées mais le compte Auth n'a pas pu
     * l'être. Le service a DÉCONNECTÉ l'appareil pour ne jamais laisser l'app
     * « connectée sans profil » ; l'utilisateur se reconnecte puis recommence.
     */
    object DeletionIncomplete : SyncException("Suppression incomplète : reconnectez-vous puis réessayez.", com.minou.mvrviewer.R.string.sync_err_delete_incomplete)
    /** Adresse e-mail mal formée (mot de passe oublié, guide C6). */
    object InvalidEmail : SyncException("Adresse e-mail invalide.", com.minou.mvrviewer.R.string.sync_err_invalid_email)
    /** Trop de demandes rapprochées (Firebase les limite) : patienter. */
    object TooManyRequests : SyncException("Trop de tentatives. Patientez quelques minutes puis réessayez.", com.minou.mvrviewer.R.string.sync_err_too_many_requests)
    /**
     * Action de partage ou de modération refusée par les règles (`permission-denied`) :
     * plus propriétaire, plus membre… (SPEC-partage-moderation §7). Jamais le texte
     * brut du SDK.
     */
    object ActionRefused : SyncException("Action refusée : vous n'êtes peut-être plus propriétaire ou membre de ce projet.", com.minou.mvrviewer.R.string.sync_err_action_refused)
    /**
     * « Rejoindre » refusé (§5.3) : code ancien, compte retiré ou projet supprimé —
     * indiscernables, et c'est voulu.
     */
    object JoinRefused : SyncException("Ce code ne fonctionne plus, ou vous n'avez plus accès à ce projet. Demandez un nouveau code au propriétaire.", com.minou.mvrviewer.R.string.sync_err_join_refused)
    class Message(msg: String) : SyncException(msg)
}

/**
 * Forme PLAUSIBLE d'une adresse e-mail (« a@b.c ») — vérifiée AVANT tout appel
 * réseau (mot de passe oublié) ; le serveur reste juge. Même règle qu'iOS
 * (`EmailAddress.isPlausible`) : aucun blanc, une seule « @ », une partie
 * locale non vide, un domaine d'au moins deux étiquettes non vides.
 */
object EmailAddress {
    fun isPlausible(address: String): Boolean {
        if (address.any { it.isWhitespace() }) return false
        val parts = address.split('@')
        if (parts.size != 2 || parts[0].isEmpty()) return false
        val labels = parts[1].split('.')
        return labels.size >= 2 && labels.all { it.isNotEmpty() }
    }
}

/**
 * Message AFFICHABLE d'une erreur de synchro, dans la langue de l'app : le texte d'une
 * [SyncException] ; une erreur réseau → le message réseau existant ; toute autre erreur →
 * « Service de synchronisation indisponible. » — JAMAIS le texte brut d'un SDK
 * (« Missing or insufficient permissions. », SPEC-partage-moderation §7).
 */
fun Throwable.syncMessage(ctx: android.content.Context): String = ctx.stringOr(syncMessageRes()) { message }

/** Ressource du message de [syncMessage] (null : le texte propre d'une [SyncException.Message]). */
internal fun Throwable.syncMessageRes(): Int? = when (this) {
    is SyncException -> messageRes
    is java.io.IOException -> com.minou.mvrviewer.R.string.sync_err_network
    else -> com.minou.mvrviewer.R.string.sync_err_backend_unavailable
}

private inline fun android.content.Context.stringOr(res: Int?, fallback: () -> String?): String =
    if (res != null) getString(res) else fallback() ?: getString(com.minou.mvrviewer.R.string.err_unknown)
