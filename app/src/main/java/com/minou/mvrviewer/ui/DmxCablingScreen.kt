package com.minou.mvrviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.minou.mvrviewer.sync.DmxCablingCalc
import com.minou.mvrviewer.sync.DmxDistributor
import com.minou.mvrviewer.sync.DmxDistributorKind
import com.minou.mvrviewer.sync.defaultDmxCores

/**
 * ONGLET CÂBLAGE DMX (phase 3) — même principe que la Socapex (phase 2) mais pour
 * le DMX. On ajoute des lignes DMX « simples » (1 départ) ou « multi »
 * (multipaire, N paires réglable), renommables et colorées ; on répartit les
 * projecteurs sur les départs ; et on lit l'EMPREINTE cumulée par départ (somme
 * des canaux du mode de chaque projecteur, via les modes GDTF déjà parsés). Deux
 * alertes DISCRÈTES, sans blocage : un départ qui dépasse 512 canaux (une ligne
 * DMX = un univers) OU qui mélange des univers de patch différents.
 */
@Composable
fun DmxCablingPanel(
    scene: MvrScene,
    mvrBytes: ByteArray,
    overrides: PatchOverrides,
    gdtfOverrides: GdtfOverrides,
    dmx: DmxCablingState,
    // AFFECTATION SUR LE PLAN (E2) : bascule la vue plan en mode affectation vers le
    // départ DMX choisi. Voir CablingScreen / PlanScreen.
    onSelectOnPlan: (CablingAssignTarget) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Résolution des empreintes GDTF HORS thread principal (parse des .gdtf) via la
    // fonction suspend PARTAGÉE avec l'export PDF : une seule source de vérité pour
    // l'empreinte, l'univers et l'adresse. Nul tant que non résolu → loader.
    val resolved by produceState<List<DmxCableFixture>?>(
        null, scene, overrides.version, mvrBytes, gdtfOverrides.version
    ) {
        value = resolveDmxFootprints(scene, mvrBytes, overrides, gdtfOverrides)
    }
    val fixtures = resolved ?: emptyList()
    val channelsOf: (String) -> Int? = remember(fixtures) {
        val m = fixtures.associate { it.uuid to it.channels }; ({ uuid: String -> m[uuid] })
    }
    val universeOf: (String) -> Int? = remember(fixtures) {
        val m = fixtures.associate { it.uuid to it.universe }; ({ uuid: String -> m[uuid] })
    }
    val fixtureLabel = remember(fixtures) { fixtures.associate { it.uuid to it.label } }

    var picker by remember { mutableStateOf<Pair<String, Int>?>(null) } // (distId, core)
    var editDist by remember { mutableStateOf<String?>(null) }
    var showAddMulti by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        // Boutons d'ajout : ligne DMX simple, ou multipaire (N paires réglable).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(onClick = { dmx.addDistributor(DmxDistributorKind.SIMPLE) }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("DMX simple")
            }
            FilledTonalButton(onClick = { showAddMulti = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Multipaire")
            }
        }
        val assignedCount = dmx.assignments.size
        Text(
            "$assignedCount / ${fixtures.size} projecteur(s) câblé(s) en DMX",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        HorizontalDivider()

        when {
            resolved == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Analyse des empreintes DMX…", modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            dmx.distributors.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Ajoutez une ligne DMX simple ou une multipaire pour commencer à câbler le DMX.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(dmx.distributors, key = { it.id }) { d ->
                    DmxDistributorCard(
                        dist = d, dmx = dmx, channelsOf = channelsOf, universeOf = universeOf,
                        fixtureLabel = fixtureLabel,
                        onAssign = { core -> picker = d.id to core },
                        onSelectOnPlan = onSelectOnPlan,
                        onEdit = { editDist = d.id },
                        onDelete = { dmx.removeDistributor(d.id) }
                    )
                }
            }
        }
    }

    // ---- Sélecteur de projecteurs pour un départ ----
    picker?.let { (distId, core) ->
        val dist = dmx.distributors.firstOrNull { it.id == distId }
        if (dist != null) {
            DmxFixturePickerDialog(
                dist = dist, core = core, fixtures = fixtures, dmx = dmx,
                onConfirm = { sel -> dmx.assign(sel, distId, core); picker = null },
                onDismiss = { picker = null }
            )
        } else picker = null
    }

    // ---- Édition d'une ligne (nom, couleur, nombre de paires si multi) ----
    editDist?.let { id ->
        val dist = dmx.distributors.firstOrNull { it.id == id }
        if (dist != null) DmxDistributorEditDialog(dist, dmx) { editDist = null }
        else editDist = null
    }

    // ---- Création d'une multipaire : choix du nombre de paires ----
    if (showAddMulti) {
        AddMultiDialog(
            onConfirm = { n -> dmx.addDistributor(DmxDistributorKind.MULTI, n); showAddMulti = false },
            onDismiss = { showAddMulti = false }
        )
    }
}

/** Carte d'une ligne DMX : ses départs, l'empreinte cumulée + alertes discrètes. */
@Composable
private fun DmxDistributorCard(
    dist: DmxDistributor,
    dmx: DmxCablingState,
    channelsOf: (String) -> Int?,
    universeOf: (String) -> Int?,
    fixtureLabel: Map<String, String>,
    onAssign: (Int) -> Unit,
    onSelectOnPlan: (CablingAssignTarget) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val color = Color(dist.colorArgb)
    val assignments = dmx.assignments.values.toList()
    val total = DmxCablingCalc.distributorTotalChannels(dist, assignments, channelsOf)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, color),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(color)
                        .border(1.dp, Color(0x55000000), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(dist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val kindTxt = if (dist.kind == DmxDistributorKind.MULTI)
                        "Multipaire · ${dist.coreCount} paires" else "Ligne DMX simple"
                    Text(
                        "$kindTxt · total $total canaux",
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
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            for (core in 1..dist.coreCount) {
                DmxCoreRow(dist, core, dmx, channelsOf, universeOf, fixtureLabel, color, onAssign, onSelectOnPlan)
            }
        }
    }
}

/** Une ligne de départ DMX : empreinte cumulée + alertes + projecteurs (chips). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DmxCoreRow(
    dist: DmxDistributor,
    core: Int,
    dmx: DmxCablingState,
    channelsOf: (String) -> Int?,
    universeOf: (String) -> Int?,
    fixtureLabel: Map<String, String>,
    color: Color,
    onAssign: (Int) -> Unit,
    onSelectOnPlan: (CablingAssignTarget) -> Unit
) {
    val load = DmxCablingCalc.coreLoad(dist, core, dmx.assignments.values.toList(), channelsOf, universeOf)
    // Libellé du départ : « Départ k » pour une multipaire, « Ligne » pour une simple.
    val coreLabel = if (dist.kind == DmxDistributorKind.MULTI) "Paire $core" else "Ligne"
    // Affectations de CE départ (réutilisées pour les chips ET le bouton « Vider »).
    val fx = dmx.assignments.values.filter { it.distributor == dist.id && it.core == core }
    // Confirmation « Vider le départ ? » locale à la ligne (n'affecte que ce départ).
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(coreLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = color, modifier = Modifier.weight(1f))
            if (load.unknownCount > 0) {
                Icon(Icons.Filled.Warning, contentDescription = "Empreinte inconnue",
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            // Empreinte cumulée « N / 512 » ; rouge si dépassement d'un univers.
            Text(
                "${load.totalChannels} / ${DmxCablingCalc.UNIVERSE_CHANNELS}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (load.over512) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (load.over512) FontWeight.Bold else FontWeight.Normal
            )
            IconButton(onClick = { onAssign(core) }) {
                Icon(Icons.Filled.Add, contentDescription = "Affecter des projecteurs")
            }
            // AFFECTER EN SÉLECTIONNANT SUR LE PLAN (E2) : bascule en vue plan, mode
            // affectation vers CE départ. Conserve l'affectation par la liste (bouton +).
            IconButton(onClick = {
                onSelectOnPlan(CablingAssignTarget(CablingAssignTarget.Kind.DMX, dist.id, core))
            }) {
                Icon(Icons.Filled.Map, contentDescription = "Sélectionner sur le plan")
            }
            // VIDER LE DÉPART : retire toutes les affectations de CE départ (pas les
            // autres). Masqué si le départ est déjà vide.
            if (fx.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Vider le départ",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        // Alertes DISCRÈTES (sans blocage) : dépassement de 512, univers mélangés.
        if (load.over512) {
            Text("⚠ dépasse 512 canaux (un départ = un univers)",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (load.mixedUniverse) {
            Text("⚠ univers de patch mélangés : ${load.universes.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
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
                        onClick = { dmx.unassign(a.fixture) },
                        label = { Text(fixtureLabel[a.fixture] ?: a.fixture, maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Retirer", modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }
    }

    if (confirmClear) {
        val departLabel = if (dist.kind == DmxDistributorKind.MULTI) "la paire $core" else "la ligne"
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = { dmx.clearCore(dist.id, core); confirmClear = false }) {
                    Text("Vider")
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
            title = { Text("Vider le départ ?") },
            text = {
                Text("Retirer les ${fx.size} projecteur(s) de $departLabel de « ${dist.name} » ? " +
                    "Les autres départs ne sont pas touchés.")
            }
        )
    }
}

/** Sélecteur multi : filtrable, empreinte/patch affichés, grisant les déjà câblés ailleurs. */
@Composable
private fun DmxFixturePickerDialog(
    dist: DmxDistributor,
    core: Int,
    fixtures: List<DmxCableFixture>,
    dmx: DmxCablingState,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val sel = remember { mutableStateListOf<String>() }
    val filtered = fixtures.filter {
        val q = query.trim()
        q.isEmpty() || it.label.contains(q, true) || (it.spec?.contains(q, true) == true) ||
            (it.address?.contains(q, true) == true)
    }
    val coreLabel = if (dist.kind == DmxDistributorKind.MULTI) "paire $core" else "ligne"
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(sel.toSet()) }, enabled = sel.isNotEmpty()) {
            Text("Affecter (${sel.size})")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        title = { Text("${dist.name} · $coreLabel") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Filtrer (N°, nom, type, patch…)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.uuid }) { f ->
                        val current = dmx.assignmentOf(f.uuid)
                        val elsewhere = current != null &&
                            !(current.distributor == dist.id && current.core == core)
                        val here = current != null && current.distributor == dist.id && current.core == core
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
                                    color = if (elsewhere) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                val empreinte = f.channels?.let { "$it canaux" } ?: "empreinte inconnue"
                                val patch = f.address?.let { " · patch $it" } ?: ""
                                val whereTxt = when {
                                    here -> " · déjà sur ce départ"
                                    elsewhere -> " · déjà câblé ailleurs → déplacer"
                                    else -> ""
                                }
                                Text(
                                    "${f.spec ?: "—"} · $empreinte$patch$whereTxt",
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

/** Édition d'une ligne DMX : nom, couleur, et nombre de paires (multipaire). */
@Composable
private fun DmxDistributorEditDialog(
    dist: DmxDistributor,
    dmx: DmxCablingState,
    onDismiss: () -> Unit
) {
    var name by remember(dist.id) { mutableStateOf(dist.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            if (name.isNotBlank()) dmx.rename(dist.id, name.trim()); onDismiss()
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
                                .clickable { dmx.setColor(dist.id, argb) }
                        )
                    }
                }
                // Nombre de paires : seulement pour une multipaire.
                if (dist.kind == DmxDistributorKind.MULTI) {
                    Spacer(Modifier.height(12.dp))
                    Text("Nombre de paires", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedButton(
                            onClick = { dmx.setCores(dist.id, dist.coreCount - 1) },
                            enabled = dist.coreCount > 1,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) { Icon(Icons.Filled.Remove, contentDescription = "Une paire de moins", modifier = Modifier.size(18.dp)) }
                        Text("${dist.coreCount}", modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            onClick = { dmx.setCores(dist.id, dist.coreCount + 1) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) { Icon(Icons.Filled.Add, contentDescription = "Une paire de plus", modifier = Modifier.size(18.dp)) }
                    }
                    Text("Réduire retire les projecteurs câblés sur les paires supprimées.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    )
}

/** Petit dialogue de création d'une multipaire : nombre de paires (défaut 4). */
@Composable
private fun AddMultiDialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var cores by remember { mutableStateOf(defaultDmxCores(DmxDistributorKind.MULTI)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(cores) }) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        title = { Text("Nouvelle multipaire DMX") },
        text = {
            Column {
                Text("Combien de paires ?", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = { if (cores > 1) cores-- }, enabled = cores > 1,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) { Icon(Icons.Filled.Remove, contentDescription = "Moins", modifier = Modifier.size(18.dp)) }
                    Text("$cores", modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { cores++ },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) { Icon(Icons.Filled.Add, contentDescription = "Plus", modifier = Modifier.size(18.dp)) }
                }
            }
        }
    )
}

