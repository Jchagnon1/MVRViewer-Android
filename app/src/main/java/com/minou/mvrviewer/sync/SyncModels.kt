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
 */
data class CloudProject(
    val id: String,
    val contentKey: String,
    val name: String,
    val ownerUid: String,
    val memberUids: List<String>,
    val mvrVersion: Int,
    val mvrBlobSHA: String?,
    val refPlanBlobSHA: String?,
    val updatedAtEpoch: Double
)

/** Code d'invitation (partage par code court + lien). */
data class InviteCode(
    val code: String,
    val projectId: String,
    val url: String,
    val expiresAtEpoch: Double? = null
)

enum class BlobKind(val raw: String) {
    MVR("mvr"), REF_PLAN("refPlan");

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
    DMX_CABLING("dmxCabling");

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
    val name: String? = null
)

data class PatchDTO(val entries: List<PatchEntryDTO> = emptyList())

/** Ancre de calibration GPS — miroir de `GeoAnchorDTO`. */
data class GeoAnchorDTO(
    val worldX: Float,
    val worldY: Float,
    val latitude: Double,
    val longitude: Double
)

data class GeoCalibrationDTO(val anchors: List<GeoAnchorDTO> = emptyList())

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
    var dmxCabling: DmxCablingDTO? = null
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
}

/** Erreurs de synchronisation (miroir de `SyncError`), avec message FR. */
sealed class SyncException(message: String) : Exception(message) {
    object NotSignedIn : SyncException("Vous n'êtes pas connecté.")
    object InvalidCredentials : SyncException("E-mail ou mot de passe incorrect.")
    object EmailInUse : SyncException("Cet e-mail est déjà utilisé.")
    object WeakPassword : SyncException("Mot de passe trop court (6 caractères minimum).")
    object ProjectNotFound : SyncException("Projet introuvable.")
    object InviteInvalidOrExpired : SyncException("Code de partage invalide ou expiré.")
    object NotAMember : SyncException("Vous n'avez pas accès à ce projet.")
    object BlobMissing : SyncException("Fichier absent du cloud.")
    object BackendUnavailable : SyncException("Service de synchronisation indisponible.")
    class Message(msg: String) : SyncException(msg)
}
