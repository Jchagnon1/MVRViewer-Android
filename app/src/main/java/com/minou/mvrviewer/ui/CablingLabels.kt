package com.minou.mvrviewer.ui

import androidx.compose.ui.graphics.Color
import com.minou.mvrviewer.sync.DmxCablingDTO
import com.minou.mvrviewer.sync.PowerCablingDTO

/**
 * CONVENTION D'ÉTIQUETTE DE CÂBLAGE (phase 4/5) — source UNIQUE.
 *
 * Le nom court « distributeur · repère » sert à la fois aux étiquettes du plan à
 * l'écran (PlanScreen) et aux pages PDF de câblage : le centraliser ici évite
 * toute dérive entre les deux. Élec = « nom · C<circuit> » ; DMX = « nom · D<cœur> »
 * (base seule quand la ligne n'a qu'un départ).
 */
internal object CablingLabels {
    /** Étiquette Socapex : « <nom, ou "Socapex"> · C<circuit 1-based> ». */
    fun soca(distName: String, circuit: Int): String =
        "${distName.ifBlank { "Socapex" }} · C$circuit"

    /**
     * Étiquette DMX : base = (nom, ou « DMX »). GATE sur `coreCount`, PAS sur le
     * kind : une multipaire réduite à 1 départ redevient une ligne simple → base
     * seule ; multipaire (coreCount > 1) → base + « · D<cœur 1-based> ».
     */
    fun dmx(distName: String, coreCount: Int, core: Int): String {
        val base = distName.ifBlank { "DMX" }
        return if (coreCount > 1) "$base · D$core" else base
    }
}

/**
 * Tables de COLORATION CÂBLAGE (phase 4) figées, prêtes pour les pages plan de
 * l'export PDF. Mêmes règles qu'à l'écran (PlanScreen) : couleur par distributeur,
 * projecteur non affecté ABSENT des tables (→ gris [CABLING_UNASSIGNED_GRAY]),
 * légende = distributeurs réellement utilisés + « Non câblé » si au moins un
 * projecteur présent n'est pas affecté dans ce mode.
 */
internal class CablingPlanColoring(
    val socaColor: Map<String, Color>,
    val dmxColor: Map<String, Color>,
    val socaLegend: List<Pair<String, Color>>,
    val dmxLegend: List<Pair<String, Color>>,
    val cablingText: (PlanFixture, LabelContent) -> String?
)

/**
 * Construit les tables de coloration/étiquettes/légende câblage à partir des DTO
 * (donc utilisable hors thread principal). MÊME construction qu'à l'écran
 * (PlanScreen) : couleur du distributeur par projecteur, étiquettes via
 * [CablingLabels], légende « distributeurs utilisés (+ Non câblé) ».
 */
internal fun buildCablingColoring(
    data: PlanData,
    cabling: PowerCablingDTO,
    dmx: DmxCablingDTO
): CablingPlanColoring {
    val socaById = cabling.distributors.associateBy { it.id }
    val dmxById = dmx.distributors.associateBy { it.id }

    val socaColor = cabling.assignments.mapNotNull { a ->
        socaById[a.distributor]?.let { d -> a.fixture to Color(d.colorArgb) }
    }.toMap()
    val dmxColor = dmx.assignments.mapNotNull { a ->
        dmxById[a.distributor]?.let { d -> a.fixture to Color(d.colorArgb) }
    }.toMap()

    val socaLabel = cabling.assignments.mapNotNull { a ->
        socaById[a.distributor]?.let { d -> a.fixture to CablingLabels.soca(d.name, a.circuit) }
    }.toMap()
    val dmxLabel = dmx.assignments.mapNotNull { a ->
        dmxById[a.distributor]?.let { d -> a.fixture to CablingLabels.dmx(d.name, d.coreCount, a.core) }
    }.toMap()

    val cablingText: (PlanFixture, LabelContent) -> String? = { f, c ->
        when (c) {
            LabelContent.SOCAPEX -> socaLabel[f.key]
            LabelContent.DMX_LINE -> dmxLabel[f.key]
            else -> null
        }
    }

    val socaUsed = cabling.assignments.mapTo(HashSet()) { it.distributor }
    val socaLegend = buildList {
        cabling.distributors.filter { it.id in socaUsed }.forEach { add(it.name to Color(it.colorArgb)) }
        if (data.fixtures.any { it.key !in socaColor }) add("Non câblé" to CABLING_UNASSIGNED_GRAY)
    }
    val dmxUsed = dmx.assignments.mapTo(HashSet()) { it.distributor }
    val dmxLegend = buildList {
        dmx.distributors.filter { it.id in dmxUsed }.forEach { add(it.name to Color(it.colorArgb)) }
        if (data.fixtures.any { it.key !in dmxColor }) add("Non câblé" to CABLING_UNASSIGNED_GRAY)
    }

    return CablingPlanColoring(socaColor, dmxColor, socaLegend, dmxLegend, cablingText)
}
