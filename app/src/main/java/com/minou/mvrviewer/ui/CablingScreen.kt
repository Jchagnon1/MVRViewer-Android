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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.sync.CablingSettings
import com.minou.mvrviewer.sync.Distributor
import com.minou.mvrviewer.sync.DistributorKind
import com.minou.mvrviewer.sync.PowerCablingCalc
import com.minou.mvrviewer.sync.PowerSource

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
    overrides: PatchOverrides,
    power: PowerLibraryState,
    cabling: PowerCablingState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Projecteurs affectables = ceux à uuid stable (comme le patch synchronisé) ;
    // le libellé prend le N° effectif s'il existe. Recalculé si le patch change.
    val fixtures = remember(scene, overrides.version) {
        scene.fixtures.mapNotNull { f ->
            val uuid = f.uuid ?: return@mapNotNull null
            val id = overrides.effectiveId(f)?.trim()?.ifBlank { null }
            CableFixture(uuid, (id?.let { "#$it · " } ?: "") + f.name, f.gdtfSpec, power.effective(f.gdtfSpec).watts)
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

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Câblage", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour 3D")
                }
            },
            actions = {
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Réglages du câblage")
                }
            }
        )
        // Boutons d'ajout d'un distributeur.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(onClick = { cabling.addDistributor(DistributorKind.SOCA) }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Socapex")
            }
            FilledTonalButton(onClick = { cabling.addDistributor(DistributorKind.SOLO) }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Ligne PC16")
            }
        }
        // Compteur d'affectation (aide : ce qui reste à câbler).
        val assignedCount = cabling.assignments.size
        Text(
            "$assignedCount / ${fixtures.size} projecteur(s) câblé(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        HorizontalDivider()

        if (cabling.distributors.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Ajoutez une Socapex ou une ligne PC16 pour commencer à câbler.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(cabling.distributors, key = { it.id }) { d ->
                    DistributorCard(
                        dist = d, cabling = cabling, settings = cabling.settings,
                        wattsOf = wattsOf, fixtureLabel = fixtureLabel,
                        onAssign = { circuit -> picker = d.id to circuit },
                        onEdit = { editDist = d.id },
                        onDelete = { cabling.removeDistributor(d.id) }
                    )
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

/** Carte d'un distributeur : circuits groupés par phase + charges + total. */
@Composable
private fun DistributorCard(
    dist: Distributor,
    cabling: PowerCablingState,
    settings: CablingSettings,
    wattsOf: (String) -> Int?,
    fixtureLabel: Map<String, String>,
    onAssign: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val color = Color(dist.colorArgb)
    val assignments = cabling.assignments.values.toList()
    val total = PowerCablingCalc.distributorTotalW(dist, assignments, settings, wattsOf)
    val phaseLoads = PowerCablingCalc.phaseLoads(dist, assignments, settings, wattsOf)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(2.dp, color),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
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
                        (if (dist.kind == DistributorKind.SOCA) "Socapex" else "Ligne PC16") +
                            " · total $total W",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Modifier") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer",
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
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // Circuits GROUPÉS PAR PHASE.
            for (phase in usedPhases.sorted()) {
                Text(
                    "Phase L$phase",
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
                for (c in 1..dist.circuitCount) {
                    if (dist.phaseOf(c) != phase) continue
                    CircuitRow(dist, c, cabling, settings, wattsOf, fixtureLabel, onAssign)
                }
            }
        }
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
    onAssign: (Int) -> Unit
) {
    val load = PowerCablingCalc.circuitLoad(dist, circuit, cabling.assignments.values.toList(), settings, wattsOf)
    val over = load.overloaded
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Circuit $circuit",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (load.unknownCount > 0) {
                Icon(Icons.Filled.Warning, contentDescription = "Conso inconnue",
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                "${load.totalW} / ${settings.circuitLimitW} W",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (over) FontWeight.Bold else FontWeight.Normal
            )
            IconButton(onClick = { onAssign(circuit) }) {
                Icon(Icons.Filled.Add, contentDescription = "Affecter des projecteurs")
            }
        }
        // Projecteurs affectés à ce circuit (chips à retirer d'un tap).
        val fx = cabling.assignments.values.filter { it.distributor == dist.id && it.circuit == circuit }
        if (fx.isEmpty()) {
            Text("  — aucun projecteur", style = MaterialTheme.typography.bodySmall,
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
                            Icon(Icons.Filled.Close, contentDescription = "Retirer",
                                modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }
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
            Text("Affecter (${sel.size})")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        title = { Text("${dist.name} · circuit $circuit") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Filtrer (N°, nom, type…)") },
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
                                val consoTxt = f.watts?.let { "$it W" } ?: "conso inconnue"
                                val whereTxt = when {
                                    here -> " · déjà sur ce circuit"
                                    elsewhere -> " · déjà câblé ailleurs → déplacer"
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        title = { Text("Modifier « ${dist.name} »") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Nom") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Couleur", style = MaterialTheme.typography.labelMedium)
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
                Text("Phase de chaque circuit", style = MaterialTheme.typography.labelMedium)
                for (c in 1..dist.circuitCount) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text("Circuit $c", modifier = Modifier.weight(1f),
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
        }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        title = { Text("Réglages du câblage") },
        text = {
            Column {
                OutlinedTextField(
                    value = voltage, onValueChange = { voltage = it.filter(Char::isDigit) },
                    label = { Text("Tension (V)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = circuitLimit, onValueChange = { circuitLimit = it.filter(Char::isDigit) },
                    label = { Text("Limite par circuit (W)") }, singleLine = true,
                    supportingText = { Text("16 A × 230 V = 3680 W") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phaseLimit, onValueChange = { phaseLimit = it.filter(Char::isDigit) },
                    label = { Text("Arrivée par phase (W)") }, singleLine = true,
                    supportingText = { Text("0 = non définie (aucune alerte phase)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}
