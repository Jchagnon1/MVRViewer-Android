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

    // HOMOTHÉTIE = RÉGLAGE LOCAL (règle canonique, alignée sur iOS). Le DTO
    // cross-plateforme n'a PAS de champ pour elle, et ce n'est pas une raison
    // pour la fondre dans `scale` : le manifeste partagé transporte l'ÉCHELLE
    // PURE. Chacun garde donc SON homothétie, et un écho distant ne vient plus
    // redimensionner le plan des autres.
    fun fromTransform(t: ReferencePlanTransform) = RefPlanTransformDTO(
        offsetX = t.offsetX, offsetY = t.offsetY, rotationDeg = t.rotationDeg,
        scale = t.scale, heightZ = t.heightZ, visible = t.visible
    )

    /**
     * DTO distant → transformée locale. `localHomothety` est l'homothétie
     * COURANTE de ce poste : elle est PRÉSERVÉE telle quelle (le distant n'en
     * transporte pas), sinon le moindre écho la remettrait à 1.
     */
    fun toTransform(d: RefPlanTransformDTO, localHomothety: Double = 1.0) = ReferencePlanTransform(
        offsetX = d.offsetX, offsetY = d.offsetY, rotationDeg = d.rotationDeg,
        scale = d.scale, heightZ = d.heightZ, visible = d.visible,
        homothety = if (localHomothety.isFinite() && localHomothety > 0.0) localHomothety else 1.0
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
