package com.minou.mvrviewer.sync

import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.ReferencePlanTransform

/**
 * Conversion état LOCAL Android ↔ DTO cloud. Isole les DIVERGENCES de nom/forme
 * entre le modèle Android et le schéma cloud partagé (défini par iOS) :
 *   - `ReferencePlanTransform` (mm/deg/scale) ↔ `RefPlanTransformDTO`
 *   - `GeoAnchor` (worldX/worldY/lat/lon) ↔ `GeoAnchorDTO`
 *   - patch : édits locaux (clé uuid|name|layer, adresse UNIQUE) ↔ `PatchEntryDTO`
 *     (mvrUUID, addresses[]). Seuls les projecteurs à `uuid` non nul se
 *     synchronisent (clé stable cross-platform) ; les autres restent locaux.
 */
object LocalMapper {

    // ---- Transform ----------------------------------------------------------

    // HOMOTHÉTIE : le DTO cross-plateforme (figé par iOS) n'a PAS de champ pour
    // elle — y en ajouter un serait improviser un contrat non aligné. On pousse
    // donc le PRODUIT `scale × homothety` dans `scale` : les autres postes voient
    // le plan à la bonne taille, et seule la factorisation (locale) est perdue.
    // Homothétie = 1 par défaut → valeur strictement inchangée pour un DXF.
    fun fromTransform(t: ReferencePlanTransform) = RefPlanTransformDTO(
        offsetX = t.offsetX, offsetY = t.offsetY, rotationDeg = t.rotationDeg,
        scale = t.effScale, heightZ = t.heightZ, visible = t.visible
    )

    fun toTransform(d: RefPlanTransformDTO) = ReferencePlanTransform(
        offsetX = d.offsetX, offsetY = d.offsetY, rotationDeg = d.rotationDeg,
        scale = d.scale, heightZ = d.heightZ, visible = d.visible
    )

    // ---- Manifeste ----------------------------------------------------------

    fun manifestDTO(
        mvrName: String,
        dxfName: String?,
        transform: ReferencePlanTransform?,
        hiddenLayers: List<String>,
        showSatellite: Boolean?,
        refPlanBlobSHA: String?
    ) = ManifestDTO(
        mvrName = mvrName,
        dxfName = dxfName,
        refPlanTransform = transform?.let { fromTransform(it) },
        refPlanHiddenLayers = hiddenLayers,
        showUserLocation = null,        // Android n'expose pas ce drapeau pour l'instant
        showSatellite = showSatellite,
        refPlanBlobSHA = refPlanBlobSHA
    )

    // ---- Calibration --------------------------------------------------------

    fun calibrationDTO(anchors: List<GeoAnchor>) = GeoCalibrationDTO(
        anchors.map { GeoAnchorDTO(it.worldX, it.worldY, it.latitude, it.longitude) }
    )

    fun toAnchors(d: GeoCalibrationDTO): List<GeoAnchor> =
        d.anchors.map { GeoAnchor(it.worldX, it.worldY, it.latitude, it.longitude) }

    // ---- Patch --------------------------------------------------------------

    /**
     * Construit le PatchDTO à pousser depuis les édits locaux + la scène. Seules
     * les édits dont la CLÉ est l'uuid d'un projecteur de la scène sont incluses
     * (clé stable) ; l'adresse locale unique devient `addresses = [addr]`.
     */
    fun patchDTO(scene: MvrScene, edits: List<PersistedPatchEdit>): PatchDTO {
        val byUuid = scene.fixtures.mapNotNull { f -> f.uuid?.let { it to f } }.toMap()
        val entries = edits.mapNotNull { e ->
            val f = byUuid[e.key] ?: return@mapNotNull null
            PatchEntryDTO(
                mvrUUID = e.key,
                fixtureId = e.fixtureId ?: f.fixtureId,
                addresses = e.address?.let { listOf(it) } ?: f.addresses,
                gdtfModeName = e.modeName ?: f.gdtfMode,
                // L'override SEUL (pas de repli sur f.name) : un nom d'affichage
                // n'est poussé que s'il a été explicitement saisi.
                name = e.name,
                // Idem pour la spec de rendu custom (« custom:<id> ») : override seul,
                // jamais de repli sur f.gdtfSpec — un projecteur non custom ne porte
                // pas cette clé.
                spec = e.spec
            )
        }
        return PatchDTO(entries)
    }

    /**
     * Convertit un PatchDTO distant en édits locaux (clé = mvrUUID, adresse =
     * première du tableau). Le caller fusionne dans son PatchOverrides.
     */
    fun toEdits(d: PatchDTO): List<PersistedPatchEdit> = d.entries.map { e ->
        PersistedPatchEdit(
            key = e.mvrUUID,
            fixtureId = e.fixtureId,
            address = e.addresses.firstOrNull(),
            modeName = e.gdtfModeName,
            name = e.name,
            spec = e.spec
        )
    }
}
