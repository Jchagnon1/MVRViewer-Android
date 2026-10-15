package com.minou.mvrviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.sync.CablingSettings
import com.minou.mvrviewer.sync.Distributor
import com.minou.mvrviewer.sync.DistributorKind
import com.minou.mvrviewer.sync.PowerCablingCalc
import com.minou.mvrviewer.sync.PowerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/** Un projecteur affectable + sa conso résolue (pour la liste et le sélecteur). */
private data class CableFixture(
    val uuid: String,
    val label: String,     // « #12 · Mac Aura »
    val spec: String?,
    val watts: Int?,       // conso connue (null = inconnue → 0 W mais signalée)
)

/**
 * ÉCRAN CÂBLAGE ÉLECTRIQUE (phase 2) — Socapex + lignes solo PC16. On y ajoute
 * des distributeurs, on répartit les projecteurs sur leurs circuits (groupés par
 * phase L1/L2/L3), et on lit les charges (circuit / phase / total) avec alerte de
 * surcharge, pour équilibrer. S'appuie sur la conso W de la phase 1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CablingScreen(
    scene: MvrScene,
    mvrBytes: ByteArray,
    overrides: PatchOverrides,
    gdtfOverrides: GdtfOverrides,
    power: PowerLibraryState,
    cabling: PowerCablingState,
    dmxCabling: DmxCablingState,
    // Projecteurs custom V1 : empreinte DMX + puissance résolues via le type
    // (baseGdtfSpec pour la conso, footprint du type pour l'empreinte DMX).
    customResolver: CustomFixtureResolver = remember { CustomFixtureResolver() },
    // Pour les pages « Plans repérés » de l'export PDF : mêmes données que la vue
    // plan (plan de repère DXF, satellite, calques masqués). Vides = pages plan
    // sans DXF/satellite (le PDF reste correct).
    referencePlan: com.minou.mvrviewer.mvr.ReferencePlan? = null,
    satellite: com.minou.mvrviewer.mvr.SatelliteOverlay? = null,
    hiddenLayers: Set<String> = emptySet(),
    // AFFECTATION SUR LE PLAN (E2) : appelé quand l'utilisateur choisit
    // « Sélectionner sur le plan » sur un circuit / départ. SceneScreen bascule alors
    // en vue plan MODE AFFECTATION vers cette cible.
    onSelectOnPlan: (CablingAssignTarget) -> Unit = {},
    /**
     * MÉMOIRE de l'écran (SPEC-cablage-plan §7) : l'écran est refermé à chaque
     * passage par le plan, sa mémoire vit dans SceneScreen (onglet, position par
     * onglet, voie travaillée à remettre sous les yeux au retour).
     */
    memory: CablingScreenMemoryState = remember { CablingScreenMemoryState() },
    /**
     * Onglet imposé par l'entrée (⋯ › Patch & câblage › Socapex / DMX / Puissances,
     * SPEC-menus-android §3.2.4) ; null = dernier onglet retenu ([memory]).
     */
    initialTab: CablingScreenTab? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Projecteurs affectables = ceux à uuid stable (comme le patch synchronisé) ;
    // le libellé prend le N° effectif s'il existe. Re-clé aussi sur `power.version` :
    // un vote de puissance déposé depuis l'onglet « Puissances » (voir plus bas)
    // doit RECALCULER la conso câblée immédiatement, pas seulement à la réouverture.
    val fixtures = remember(scene, overrides.version, power.version, customResolver.version) {
        scene.fixtures.mapNotNull { f ->
            val uuid = f.uuid ?: return@mapNotNull null
            val id = overrides.effectiveId(f)?.trim()?.ifBlank { null }
            // Conso résolue via la spec de GÉOMÉTRIE (baseGdtfSpec pour un custom) :
            // un type custom réutilise la puissance du GDTF de base.
            val powerSpec = effectiveGeometrySpec(f, overrides, customResolver)
            CableFixture(uuid, (id?.let { "#$it · " } ?: "") + overrides.effectiveName(f), f.gdtfSpec, power.effective(powerSpec).watts)
        }
    }
    // Conso par projecteur (uuid → W connus), relue quand la biblio change.
    val wattsOf: (String) -> Int? = remember(fixtures, power.version) {
        val map = fixtures.associate { it.uuid to it.watts }
        ({ uuid: String -> map[uuid] })
    }
    val fixtureLabel = remember(fixtures) { fixtures.associate { it.uuid to it.label } }

    var picker by remember { mutableStateOf<Pair<String, Int>?>(null) } // (distId, circuit)
    var editDist by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Onglet actif : 0 = câblage électrique (distributeurs/charges), 1 = câblage
    // DMX (lignes/multipaires), 2 = puissances par type. On règle la conso SANS
    // quitter l'écran ; revenir au câblage montre les charges recalculées (les
    // votes remontent `power.version`, qui re-clé `fixtures`). L'onglet de
    // départ est le DERNIER ouvert (mémoire hissée) — il était perdu à chaque
    // bascule câblage ↔ plan : on revenait sur « Électrique » en câblant du DMX.
    var tab by remember {
        val opening = CablingScreenTab.opening(initialTab, memory.tab)
        // L'onglet imposé devient le « dernier onglet » (c'est lui qu'on a sous les yeux).
        if (initialTab != null) memory.tab = opening
        mutableIntStateOf(opening.index)
    }
    fun selectTab(i: Int) { tab = i; memory.tab = CablingScreenTab.fromIndex(i) }

    // ---- EXPORT PDF (phase 5) : dossier complet + 3 exports séparés ----------
    val ctxCabling = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var exportMenu by remember { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    // Titres/messages d'export résolus ICI (contexte composable) : les coroutines
    // de l'export ne peuvent pas appeler stringResource.
    val pdfTexts = cablingPdfTexts()
    val sPdfFull = pdfTexts.full
    val sPdfElec = pdfTexts.elec
    val sPdfDmx = pdfTexts.dmx
    val sPdfPlans = pdfTexts.plans
    // Lance la génération HORS thread principal puis partage le PDF : MÊME moteur
    // que le menu ⋯ des vues (CablingPdfShare.kt, SPEC-menus-android §8 D17). Les
    // données sont figées ici, sur le thread principal.
    fun runExport(parts: Set<CablingPdfPart>, title: String) {
        if (exportBusy) return
        exportBusy = true
        val inputs = CablingPdfInputs.freeze(
            scene, mvrBytes, overrides, gdtfOverrides, customResolver, power,
            cabling, dmxCabling, referencePlan, satellite, hiddenLayers
        )
        exportScope.launch {
            val file = generateCablingPdf(ctxCabling, inputs, parts, title, pdfTexts)
            exportBusy = false
            if (file != null) sharePlanPdf(ctxCabling, file)
            else android.widget.Toast.makeText(ctxCabling, pdfTexts.failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.cabling_pdf_title), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                if (tab == 0) IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cabling_settings_title))
                }
                // Menu d'export PDF : dossier complet + 3 exports séparés.
                Box {
                    if (exportBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 6.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { exportMenu = true }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.cabling_export_pdf_cd))
                        }
                    }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cabling_export_full_menu)) },
                            onClick = {
                                exportMenu = false
                                runExport(setOf(CablingPdfPart.ELEC, CablingPdfPart.DMX, CablingPdfPart.PLANS),
                                    sPdfFull)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cabling_export_elec_menu)) },
                            onClick = { exportMenu = false; runExport(setOf(CablingPdfPart.ELEC), sPdfElec) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cabling_export_dmx)) },
                            onClick = { exportMenu = false; runExport(setOf(CablingPdfPart.DMX), sPdfDmx) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cabling_export_plans)) },
                            onClick = { exportMenu = false; runExport(setOf(CablingPdfPart.PLANS), sPdfPlans) }
                        )
                    }
                }
            }
        )
        // Trois onglets : le câblage électrique (Socapex), le câblage DMX (lignes /
        // multipaires), et le réglage des PUISSANCES par type — pour corriger une
        // conso sans sortir de l'écran de câblage.
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { selectTab(0) }, text = { Text(stringResource(R.string.cabling_tab_elec)) })
            Tab(selected = tab == 1, onClick = { selectTab(1) }, text = { Text("DMX") })
            Tab(selected = tab == 2, onClick = { selectTab(2) }, text = { Text(stringResource(R.string.cabling_tab_power)) })
        }

        if (tab == 1) {
            DmxCablingPanel(scene, mvrBytes, overrides, gdtfOverrides, dmxCabling,
                customResolver = customResolver,
                onSelectOnPlan = onSelectOnPlan,
                memory = memory,
                modifier = Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        if (tab == 2) {
            PowerTypesPanel(scene, power, Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        // Boutons d'ajout d'un distributeur.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(onClick = { cabling.addDistributor(DistributorKind.SOCA) }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.label_field_socapex))
            }
            FilledTonalButton(onClick = { cabling.addDistributor(DistributorKind.SOLO) }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cabling_line_pc16))
            }
        }
        // Compteur d'affectation (aide : ce qui reste à câbler).
        val assignedCount = cabling.assignments.size
        Text(
            stringResource(R.string.cabling_assigned_fmt, assignedCount, fixtures.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        // Récapitulatif des réglages persistés (touchez l'engrenage pour changer).
        val s = cabling.settings
        Text(
            stringResource(R.string.cabling_settings_summary_fmt, s.voltage, s.circuitLimitW) + (
                if (s.phaseLimitW > 0) stringResource(R.string.cabling_phase_limit_fmt, s.phaseLimitW) else stringResource(R.string.cabling_phase_unlimited)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { showSettings = true }
                .padding(horizontal = 16.dp, vertical = 2.dp)
        )
        HorizontalDivider()

        if (cabling.distributors.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.cabling_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            // Une LIGNE de liste par ligne repère (en-tête, phase, circuit), clé =
            // identifiant de ligne : la liste sait défiler jusqu'à la voie
            // travaillée sur le plan et retenir la ligne en tête (SPEC §7).
            val dists = cabling.distributors.toList()
            val rows = remember(dists) { CablingListRows.power(dists) }
            val distById = remember(dists) { dists.associateBy { it.id } }
            val listState = rememberLazyListState()
            val flash = remember { CablingFlash() }
            CablingListMemoryEffects(listState, rows, memory, CablingScreenTab.CABLING, flash)
            val cardFill = MaterialTheme.colorScheme.surface
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
            ) {
                itemsIndexed(rows, key = { _, r -> r.id }) { i, row ->
                    when (row) {
                        is CablingListRow.Header -> {
                            val d = distById[row.distId] ?: return@itemsIndexed
                            Box(Modifier.padding(top = if (i == 0) 0.dp else 12.dp).fillMaxWidth()
                                .cardSegment(Color(d.colorArgb), cardFill, CardSeg.TOP)) {
                                DistributorHeader(
                                    dist = d, cabling = cabling, settings = cabling.settings, wattsOf = wattsOf,
                                    onEdit = { editDist = d.id },
                                    onDelete = { cabling.removeDistributor(d.id) }
                                )
                            }
                        }
                        is CablingListRow.Phase -> {
                            val d = distById[row.distId] ?: return@itemsIndexed
                            Box(Modifier.fillMaxWidth().cardSegment(Color(d.colorArgb), cardFill, CardSeg.MIDDLE)) {
                                Text(
                                    stringResource(R.string.cabling_phase_fmt, row.phase),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(d.colorArgb),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp)
                                )
                            }
                        }
                        is CablingListRow.Voie -> {
                            val d = distById[row.voie.distId] ?: return@itemsIndexed
                            val id = row.id
                            Box(Modifier.fillMaxWidth().cardSegment(Color(d.colorArgb), cardFill,
                                if (row.last) CardSeg.BOTTOM else CardSeg.MIDDLE) { flash.alphaOf(id) }) {
                                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = if (row.last) 10.dp else 0.dp)) {
                                    CircuitRow(d, row.voie.index, cabling, cabling.settings, wattsOf, fixtureLabel,
                                        onAssign = { circuit -> picker = d.id to circuit }, onSelectOnPlan = onSelectOnPlan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Sélecteur de projecteurs pour un circuit ----
    picker?.let { (distId, circuit) ->
        val dist = cabling.distributors.firstOrNull { it.id == distId }
        if (dist != null) {
            FixturePickerDialog(
                dist = dist, circuit = circuit, fixtures = fixtures, cabling = cabling,
                onConfirm = { sel -> cabling.assign(sel, distId, circuit); picker = null },
                onDismiss = { picker = null }
            )
        } else picker = null
    }

    // ---- Édition d'un distributeur (nom, couleur, mappage circuit→phase) ----
    editDist?.let { id ->
        val dist = cabling.distributors.firstOrNull { it.id == id }
        if (dist != null) DistributorEditDialog(dist, cabling) { editDist = null }
        else editDist = null
    }

    // ---- Réglages globaux (tension, limite circuit, arrivée phase) ----
    if (showSettings) {
        CablingSettingsDialog(cabling.settings, onApply = { cabling.applySettings(it) }) { showSettings = false }
    }
}

/**
 * En-tête de la carte d'un distributeur (ligne repère `hdr#<id>`) : pastille,
 * nom, total, actions et totaux de phase. Les phases et les circuits suivent,
 * chacun sur sa propre ligne de liste (CablingListRows.power).
 */
@Composable
private fun DistributorHeader(
    dist: Distributor,
    cabling: PowerCablingState,
    settings: CablingSettings,
    wattsOf: (String) -> Int?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val color = Color(dist.colorArgb)
    val assignments = cabling.assignments.values.toList()
    val total = PowerCablingCalc.distributorTotalW(dist, assignments, settings, wattsOf)
    val phaseLoads = PowerCablingCalc.phaseLoads(dist, assignments, settings, wattsOf)

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        // En-tête : pastille couleur, nom, total, actions.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(color)
                    .border(1.dp, Color(0x55000000), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(dist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (dist.kind == DistributorKind.SOCA) R.string.label_field_socapex else R.string.cabling_line_pc16) + (
                        stringResource(R.string.cabling_total_fmt, total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.common_edit)) }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
        // Totaux de phase (aide à l'équilibrage) — 3 pour une soca, sinon les
        // phases réellement utilisées.
        val usedPhases = (1..dist.circuitCount).map { dist.phaseOf(it) }.toSet()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            phaseLoads.filter { it.phase in usedPhases }.forEach { pl ->
                val over = pl.overloaded
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            "L${pl.phase}: ${pl.totalW} W" + if (settings.phaseLimitW > 0) " / ${settings.phaseLimitW}" else "",
                            color = if (over) MaterialTheme.colorScheme.onError else Color.Unspecified
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = if (over) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.surfaceVariant,
                        disabledLabelColor = if (over) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

/** Une ligne de circuit : charge « x / 3680 W » + projecteurs (chips) + bouton affecter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CircuitRow(
    dist: Distributor,
    circuit: Int,
    cabling: PowerCablingState,
    settings: CablingSettings,
    wattsOf: (String) -> Int?,
    fixtureLabel: Map<String, String>,
    onAssign: (Int) -> Unit,
    onSelectOnPlan: (CablingAssignTarget) -> Unit
) {
    val load = PowerCablingCalc.circuitLoad(dist, circuit, cabling.assignments.values.toList(), settings, wattsOf)
    val over = load.overloaded
    // Affectations de CE circuit (réutilisées pour les chips ET le bouton « Vider »).
    val fx = cabling.assignments.values.filter { it.distributor == dist.id && it.circuit == circuit }
    // Confirmation « Vider le circuit ? » locale à la ligne (n'affecte que ce circuit).
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.cabling_circuit_fmt, circuit),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (load.unknownCount > 0) {
                Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.cabling_unknown_power_cd),
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                "${load.totalW} / ${settings.circuitLimitW} W",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (over) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
        // Projecteurs affectés à ce circuit (chips à retirer d'un tap).
        if (fx.isEmpty()) {
            Text("  — " + stringResource(R.string.cabling_no_fixture), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                fx.forEach { a ->
                    InputChip(
                        selected = false,
                        onClick = { cabling.unassign(a.fixture) },
                        label = { Text(fixtureLabel[a.fixture] ?: a.fixture, maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_remove),
                                modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }
        // Trois actions sur UNE ligne, ordre iOS : « Sélectionner sur le plan »
        // (le chemin le plus direct), « Affecter des projecteurs », puis « Vider le
        // circuit » s'il porte un projecteur — libellés courts si la place manque.
        val danger = MaterialTheme.colorScheme.error
        val accent = MaterialTheme.colorScheme.primary
        val sOnPlan = stringResource(R.string.cabling_select_on_plan_cd)
        val sOnPlanShort = stringResource(R.string.cabling_action_on_plan_short)
        val sAssign = stringResource(R.string.cabling_assign_fixtures_cd)
        val sAssignShort = stringResource(R.string.cabling_action_assign_short)
        val sClear = stringResource(R.string.cabling_clear_circuit_cd)
        val sClearShort = stringResource(R.string.common_clear)
        CablingRowActions(buildList {
            // AFFECTER EN SÉLECTIONNANT SUR LE PLAN : bascule en vue plan, mode
            // affectation vers CE circuit. Garde Pro en AMONT (ne pas armer pour rien).
            add(CablingRowAction(Icons.Filled.TouchApp, sOnPlan, sOnPlanShort, accent) {
                ProAccess.gate("affecter le câblage") {
                    onSelectOnPlan(CablingAssignTarget(CablingAssignTarget.Kind.SOCA, dist.id, circuit))
                }
            })
            add(CablingRowAction(Icons.Filled.AddCircleOutline, sAssign, sAssignShort, accent) { onAssign(circuit) })
            // VIDER LE CIRCUIT : retire toutes les affectations de CE circuit (pas
            // les autres). Masqué si le circuit est déjà vide.
            if (fx.isNotEmpty()) add(CablingRowAction(Icons.Filled.Delete, sClear, sClearShort, danger) { confirmClear = true })
        }, Modifier.fillMaxWidth().padding(bottom = 2.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = { cabling.clearCircuit(dist.id, circuit); confirmClear = false }) {
                    Text(stringResource(R.string.common_clear))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
            title = { Text(stringResource(R.string.cabling_clear_circuit_q)) },
            text = {
                Text(stringResource(R.string.cabling_clear_circuit_msg_fmt, fx.size, circuit, dist.name) + (
                    stringResource(R.string.cabling_clear_circuit_msg2)))
            }
        )
    }
}

/** Sélecteur multi de projecteurs : filtrable, conso affichée, grisant les déjà câblés ailleurs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixturePickerDialog(
    dist: Distributor,
    circuit: Int,
    fixtures: List<CableFixture>,
    cabling: PowerCablingState,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val sel = remember { mutableStateListOf<String>() }
    val filtered = fixtures.filter {
        val q = query.trim()
        q.isEmpty() || it.label.contains(q, true) || (it.spec?.contains(q, true) == true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(sel.toSet()) }, enabled = sel.isNotEmpty()) {
            Text(stringResource(R.string.cabling_assign_fmt, sel.size))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = { Text(stringResource(R.string.cabling_dist_circuit_fmt, dist.name, circuit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.cabling_filter_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.uuid }) { f ->
                        val current = cabling.assignmentOf(f.uuid)
                        val elsewhere = current != null &&
                            !(current.distributor == dist.id && current.circuit == circuit)
                        val here = current != null && current.distributor == dist.id && current.circuit == circuit
                        val checked = f.uuid in sel
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (checked) sel.remove(f.uuid) else sel.add(f.uuid)
                            }.padding(vertical = 4.dp)
                        ) {
                            Checkbox(checked = checked || here, onCheckedChange = {
                                if (checked) sel.remove(f.uuid) else sel.add(f.uuid)
                            })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    // Déjà câblé ailleurs → grisé (mais sélectionnable pour DÉPLACER).
                                    color = if (elsewhere) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                val consoTxt = f.watts?.let { "$it W" } ?: stringResource(R.string.cabling_unknown_power)
                                val whereTxt = when {
                                    here -> " · " + stringResource(R.string.cabling_already_here)
                                    elsewhere -> " · " + stringResource(R.string.cabling_already_elsewhere)
                                    else -> ""
                                }
                                Text(
                                    "${f.spec ?: "—"} · $consoTxt$whereTxt",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

/** Édition d'un distributeur : nom, couleur, mappage circuit → phase. */
@Composable
private fun DistributorEditDialog(
    dist: Distributor,
    cabling: PowerCablingState,
    onDismiss: () -> Unit
) {
    var name by remember(dist.id) { mutableStateOf(dist.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            if (name.isNotBlank()) cabling.rename(dist.id, name.trim()); onDismiss()
        }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.cabling_edit_dist_fmt, dist.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.label_field_name)) }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.common_color), style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CablingPalette.all.take(8).forEach { argb ->
                        val c = Color(argb)
                        val active = argb == dist.colorArgb
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c)
                                .border(if (active) 3.dp else 1.dp,
                                    if (active) MaterialTheme.colorScheme.onSurface else Color(0x55000000),
                                    CircleShape)
                                .clickable { cabling.setColor(dist.id, argb) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.cabling_phase_per_circuit), style = MaterialTheme.typography.labelMedium)
                for (c in 1..dist.circuitCount) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(stringResource(R.string.cabling_circuit_fmt, c), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        listOf(1, 2, 3).forEach { p ->
                            val on = dist.phaseOf(c) == p
                            OutlinedButton(
                                onClick = { cabling.setCircuitPhase(dist.id, c, p) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.padding(start = 4.dp),
                                colors = if (on)
                                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary)
                                else androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                            ) { Text("L$p") }
                        }
                    }
                }
            }
        }
    )
}

/** Réglages globaux : tension (230 V), limite par circuit, arrivée par phase. */
@Composable
private fun CablingSettingsDialog(
    settings: CablingSettings,
    onApply: (CablingSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var voltage by remember { mutableStateOf(settings.voltage.toString()) }
    var circuitLimit by remember { mutableStateOf(settings.circuitLimitW.toString()) }
    var phaseLimit by remember { mutableStateOf(settings.phaseLimitW.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            onApply(CablingSettings(
                voltage = voltage.toIntOrNull()?.coerceIn(1, 1000) ?: settings.voltage,
                circuitLimitW = circuitLimit.toIntOrNull()?.coerceAtLeast(0) ?: settings.circuitLimitW,
                phaseLimitW = phaseLimit.toIntOrNull()?.coerceAtLeast(0) ?: 0
            ))
            onDismiss()
        }) { Text(stringResource(R.string.common_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = { Text(stringResource(R.string.cabling_settings_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = voltage, onValueChange = { voltage = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.cabling_voltage)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = circuitLimit, onValueChange = { circuitLimit = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.cabling_circuit_limit)) }, singleLine = true,
                    supportingText = { Text("16 A × 230 V = 3680 W") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phaseLimit, onValueChange = { phaseLimit = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.cabling_phase_supply)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.cabling_phase_supply_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

/**
 * PANNEAU « PUISSANCES » — régler la conso PAR TYPE (spec GDTF) sans quitter le
 * câblage. On liste les TYPES présents dans le show (specs GDTF distinctes) plutôt
 * que projecteur par projecteur : une valeur saisie profite à TOUS les projecteurs
 * de ce type ET remonte à la bibliothèque partagée. Le mécanisme de vote est
 * EXACTEMENT celui de la fiche projecteur ([PowerLibraryState.set] → onCommit →
 * submitPowerVote), donc aucune duplication du store.
 */
@Composable
private fun PowerTypesPanel(
    scene: MvrScene,
    power: PowerLibraryState,
    modifier: Modifier = Modifier
) {
    // Types = specs GDTF distinctes + nombre de projecteurs de chacune. L'ensemble
    // des types est STABLE (dépend du show, pas des votes) → clé sur `scene` seul ;
    // les valeurs de conso, elles, sont relues live dans chaque ligne.
    val types = remember(scene) {
        scene.fixtures
            .mapNotNull { it.gdtfSpec?.trim()?.ifBlank { null } }
            .groupingBy { it }.eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    if (types.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.power_no_type),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    Column(modifier) {
        Text(
            stringResource(R.string.power_tab_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(types, key = { it.first }) { (spec, count) ->
                PowerTypeRow(spec = spec, count = count, power = power)
            }
        }
    }
}

/**
 * Une ligne de TYPE éditable sur place : nom du type, nombre de projecteurs, conso
 * effective (consensus + confiance / GDTF / à saisir), champ de saisie + bouton
 * « ✓ ». Le « ✓ » dépose MON vote : la valeur saisie si l'utilisateur en a tapé
 * une, sinon la valeur affichée (confirmer sans retaper). Personne n'écrase le
 * vote d'un autre — c'est le consensus qui est recalculé côté cloud.
 */
@Composable
private fun PowerTypeRow(
    spec: String,
    count: Int,
    power: PowerLibraryState
) {
    // Lecture LIVE : `effective`/`consensusVotes` lisent les SnapshotStateMap de la
    // biblio → la ligne se recompose dès qu'un vote change la valeur.
    val resolution = power.effective(spec)
    val effWatts = resolution.watts
    val votes = power.consensusVotes(spec)
    // Nom lisible du type = spec sans l'extension « .gdtf ».
    val typeName = if (spec.endsWith(".gdtf", true)) spec.dropLast(5) else spec
    var myValue by remember(spec) { mutableStateOf("") }

    val typed = myValue.trim().toIntOrNull()
    val typedOk = myValue.isBlank() || (typed != null && typed > 0)
    // On peut voter si l'utilisateur a saisi une valeur valide, OU s'il confirme une
    // valeur déjà affichée (consensus/GDTF).
    val canVote = (typed != null && typed > 0) || effWatts != null

    // UN libellé « valeur · confiance » partagé avec la fiche de sélection (3D et
    // plan) : pluriel porté par le NOMBRE DE VOTES (pas par les watts).
    val statusText = SelectionCardModels.powerLabel(resolution, votes)?.text()
        ?: stringResource(R.string.power_to_enter)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(typeName, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(
                        pluralStringResource(R.plurals.power_fixtures_status, count, count, statusText),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resolution.source == PowerSource.LIBRARY)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = myValue,
                    onValueChange = { new -> myValue = new.filter { it.isDigit() } },
                    label = { Text(stringResource(R.string.fx_power_my_value)) },
                    placeholder = { effWatts?.let { Text("$it") } },
                    singleLine = true,
                    isError = !typedOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                // ✓ : dépose mon vote (valeur saisie sinon valeur affichée), puis vide
                // le champ — la valeur devient le consensus affiché sur la ligne.
                FilledTonalButton(
                    enabled = canVote,
                    onClick = {
                        val v = if (typed != null && typed > 0) typed else effWatts
                        if (v != null && v > 0) {
                            power.set(spec, v)
                            myValue = ""
                        }
                    }
                ) { Text("✓") }
            }
        }
    }
}
