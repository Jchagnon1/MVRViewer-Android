package com.minou.mvrviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.sync.CablingSettings
import com.minou.mvrviewer.sync.DmxCablingDTO
import com.minou.mvrviewer.sync.PowerCablingDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// =============================================================================
// PDF DE CÂBLAGE — génération PARTAGÉE (SPEC-menus-android §3.2.6, §8 D17) :
// l'écran Câblage (son menu d'export) et le menu ⋯ des deux vues (Partager &
// exporter › PDF de câblage) appellent le MÊME moteur. Les entrées sont figées
// sur le thread principal ([CablingPdfInputs.freeze]), la génération part hors du
// thread principal ([generateCablingPdf]), puis l'appelant ouvre la feuille de
// partage Android (`sharePlanPdf`).
// =============================================================================

/** Textes de l'export, résolus dans un contexte composable (les coroutines ne le peuvent pas). */
internal class CablingPdfTexts(
    val cablingTitle: String,
    val planSoca: String,
    val planDmx: String,
    val failed: String,
    val full: String,
    val elec: String,
    val dmx: String,
    val plans: String
) {
    /** Titre du document d'après ses parties (« complet » = les trois). */
    fun titleOf(parts: Set<CablingPdfPart>): String = when {
        parts.size >= 3 -> full
        CablingPdfPart.ELEC in parts -> elec
        CablingPdfPart.DMX in parts -> dmx
        else -> plans
    }
}

@Composable
internal fun cablingPdfTexts(): CablingPdfTexts = CablingPdfTexts(
    cablingTitle = stringResource(R.string.cabling_pdf_title),
    planSoca = stringResource(R.string.cabling_pdf_plan_soca),
    planDmx = stringResource(R.string.cabling_pdf_plan_dmx),
    failed = stringResource(R.string.cabling_pdf_failed),
    full = stringResource(R.string.cabling_export_full),
    elec = stringResource(R.string.cabling_export_elec_doc),
    dmx = stringResource(R.string.cabling_export_dmx),
    plans = stringResource(R.string.cabling_export_plans)
)

/**
 * Entrées FIGÉES d'un export (copies immuables des états réactifs), sûres à
 * passer au thread de fond.
 */
internal class CablingPdfInputs private constructor(
    val scene: MvrScene,
    val mvrBytes: ByteArray,
    val overrides: PatchOverrides,
    val gdtfOverrides: GdtfOverrides,
    val customResolver: CustomFixtureResolver,
    val labelMap: Map<String, String>,
    val specMap: Map<String, String?>,
    val wattsMap: Map<String, Int?>,
    val cablingDto: PowerCablingDTO,
    val dmxDto: DmxCablingDTO,
    val settings: CablingSettings,
    val ovVersion: Int,
    val ovMap: Map<String, ByteArray>,
    val referencePlan: com.minou.mvrviewer.mvr.ReferencePlan?,
    val satellite: com.minou.mvrviewer.mvr.SatelliteOverlay?,
    val hiddenLayers: Set<String>
) {
    companion object {
        /**
         * Fige les entrées (thread principal). Projecteurs câblables = ceux à uuid
         * stable ; libellé « #N° · Nom » effectif, conso via la spec de GÉOMÉTRIE
         * (un type custom réutilise la puissance de son GDTF de base) — mêmes
         * règles que la liste de l'écran Câblage.
         */
        fun freeze(
            scene: MvrScene, mvrBytes: ByteArray, overrides: PatchOverrides, gdtfOverrides: GdtfOverrides,
            customResolver: CustomFixtureResolver, power: PowerLibraryState,
            cabling: PowerCablingState, dmxCabling: DmxCablingState,
            referencePlan: com.minou.mvrviewer.mvr.ReferencePlan?,
            satellite: com.minou.mvrviewer.mvr.SatelliteOverlay?, hiddenLayers: Set<String>
        ): CablingPdfInputs {
            val labels = HashMap<String, String>()
            val specs = HashMap<String, String?>()
            val watts = HashMap<String, Int?>()
            for (f in scene.fixtures) {
                val uuid = f.uuid ?: continue
                val id = overrides.effectiveId(f)?.trim()?.ifBlank { null }
                labels[uuid] = (id?.let { "#$it · " } ?: "") + overrides.effectiveName(f)
                specs[uuid] = f.gdtfSpec
                watts[uuid] = power.effective(effectiveGeometrySpec(f, overrides, customResolver)).watts
            }
            return CablingPdfInputs(
                scene, mvrBytes, overrides, gdtfOverrides, customResolver,
                labels, specs, watts, cabling.toDTO(), dmxCabling.toDTO(), cabling.settings,
                gdtfOverrides.version, gdtfOverrides.map.toMap(), referencePlan, satellite, hiddenLayers
            )
        }
    }
}

/**
 * Génère le PDF de câblage [parts] hors du thread principal ; null en cas
 * d'échec (l'appelant affiche [CablingPdfTexts.failed]). Les calculs viennent
 * EXCLUSIVEMENT des fonctions déjà à l'écran (PowerCablingCalc / DmxCablingCalc,
 * resolveDmxFootprints partagé) ; les pages plan réutilisent le moteur du PDF du plan.
 */
internal suspend fun generateCablingPdf(
    ctx: android.content.Context, inputs: CablingPdfInputs, parts: Set<CablingPdfPart>,
    title: String, texts: CablingPdfTexts
): File? = withContext(Dispatchers.Default) {
    runCatching {
        val labelOf = { u: String -> inputs.labelMap[u] }
        val specOf = { u: String -> inputs.specMap[u] }
        val wattsOf = { u: String -> inputs.wattsMap[u] }
        var channelsOf: (String) -> Int? = { null }
        var universeOf: (String) -> Int? = { null }
        var addressOf: (String) -> String? = { null }
        var modeOf: (String) -> String? = { null }
        if (CablingPdfPart.DMX in parts) {
            val resolved = resolveDmxFootprints(inputs.scene, inputs.mvrBytes, inputs.overrides,
                inputs.gdtfOverrides, inputs.customResolver)
            val chMap = resolved.associate { it.uuid to it.channels }
            val uniMap = resolved.associate { it.uuid to it.universe }
            val addrMap = resolved.associate { it.uuid to it.address }
            val modeMap = resolved.associate { it.uuid to it.mode }
            channelsOf = { chMap[it] }
            universeOf = { uniMap[it] }
            addressOf = { addrMap[it] }
            modeOf = { modeMap[it] }
        }
        var planSrc: PlanExportSource? = null
        var socaView: PlanViewCapture? = null
        var dmxView: PlanViewCapture? = null
        if (CablingPdfPart.PLANS in parts) {
            val data = planData(inputs.scene, inputs.overrides)
            val coloring = buildCablingColoring(ctx, data, inputs.cablingDto, inputs.dmxDto)
            val refPlan = inputs.referencePlan
            val sat = inputs.satellite
            planSrc = PlanExportSource(
                data = data,
                layerIndex = LayerColors.index(inputs.scene),
                wire = PlanWireCache.buildStructures(inputs.scene, inputs.mvrBytes),
                fixWire = PlanWireCache.buildFixtures(inputs.scene, inputs.mvrBytes, inputs.ovVersion, inputs.ovMap),
                dxfPaths = refPlan?.plan?.let { buildDxfPaths(it) },
                rasterPlan = refPlan?.raster,
                satellite = sat,
                legend = inputs.scene.fixtures.groupingBy { it.layerName }.eachCount()
                    .toList().sortedByDescending { it.second },
                documentTitle = texts.cablingTitle
            )
            // ANNEAU 2e dimension (E3) : la page Socapex porte l'anneau DMX, la
            // page DMX porte l'anneau Socapex.
            socaView = cablingPlanView(texts.planSoca, data, PlanColorMode.SOCAPEX,
                coloring.socaColor, coloring.socaLegend, coloring.cablingText, sat, inputs.hiddenLayers, refPlan,
                cablingRingColor = coloring.dmxColor)
            dmxView = cablingPlanView(texts.planDmx, data, PlanColorMode.DMX_LINE,
                coloring.dmxColor, coloring.dmxLegend, coloring.cablingText, sat, inputs.hiddenLayers, refPlan,
                cablingRingColor = coloring.socaColor)
        }
        buildCablingPdf(
            ctx, parts, inputs.cablingDto, inputs.dmxDto, inputs.settings,
            wattsOf, channelsOf, universeOf, addressOf, modeOf, labelOf, specOf,
            title, planSrc, socaView, dmxView
        )
    }.getOrNull()
}
