package com.minou.mvrviewer.sync

import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.GeoSurvey
import com.minou.mvrviewer.mvr.GeoSurveyPoint
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
     * DTO distant → transformée locale. `localHomothety` et `localTiltDeg` sont
     * l'homothétie/bascule COURANTES de ce poste : PRÉSERVÉES telles quelles (le
     * distant n'en transporte pas — champs LOCAUX, cf. `AuditCoding`), sinon le
     * moindre écho les remettrait à leur défaut.
     */
    fun toTransform(d: RefPlanTransformDTO, localHomothety: Double = 1.0, localTiltDeg: Double = 0.0) = ReferencePlanTransform(
        offsetX = d.offsetX, offsetY = d.offsetY, rotationDeg = d.rotationDeg,
        scale = d.scale, heightZ = d.heightZ, visible = d.visible,
        tiltDeg = if (localTiltDeg.isFinite()) localTiltDeg else 0.0,
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

    /**
     * Section `calibration` : ancres + relevé 3 points s'il existe. Le relevé ne
     * part qu'avec EXACTEMENT 2 ancres (il décrit les deux ancres synthétiques) ;
     * republier sans avoir touché aux ancres le renvoie donc TEL QUEL.
     */
    fun calibrationDTO(anchors: List<GeoAnchor>, survey: GeoSurvey? = null) = GeoCalibrationDTO(
        anchors.map { GeoAnchorDTO(it.worldX, it.worldY, it.latitude, it.longitude) },
        if (anchors.size == 2) survey?.let(::surveyDTO) else null
    )

    fun surveyDTO(s: GeoSurvey) = GeoSurveyDTO(
        method = s.method,
        points = s.points.map {
            GeoSurveyPointDTO(it.worldX, it.worldY, it.latitude, it.longitude, it.accuracy, it.residual)
        },
        precision = s.precision, freeScale = s.freeScale, epoch = s.epoch, edgePrecision = s.edgePrecision
    )

    fun toAnchors(d: GeoCalibrationDTO): List<GeoAnchor> =
        d.anchors.map { GeoAnchor(it.worldX, it.worldY, it.latitude, it.longitude) }

    /** Relevé de la section, seulement avec 2 ancres (iOS `GeoCalibration(dto:)`). */
    fun toSurvey(d: GeoCalibrationDTO): GeoSurvey? {
        if (d.anchors.size != 2) return null
        return d.survey?.let(::surveyFromDTO)
    }

    fun surveyFromDTO(s: GeoSurveyDTO) = GeoSurvey(
        method = s.method,
        points = s.points.map {
            GeoSurveyPoint(it.worldX, it.worldY, it.latitude, it.longitude, it.accuracy, it.residual)
        },
        precision = s.precision, freeScale = s.freeScale, epoch = s.epoch, edgePrecision = s.edgePrecision
    )

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
