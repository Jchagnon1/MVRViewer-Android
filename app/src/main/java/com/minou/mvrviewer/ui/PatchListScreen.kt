package com.minou.mvrviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.DmxAddress
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.MvrSceneObject

/**
 * Liste de patch — projecteurs du .mvr avec recherche/filtre (ID, nom, GDTF,
 * calque, adresse DMX). Toucher une ligne ouvre la fiche d'édition (ID /
 * adresse / mode + détail des canaux). Équivalent PatchListView (iOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchListScreen(
    scene: MvrScene,
    mvrBytes: ByteArray,
    overrides: PatchOverrides,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember(scene) { mutableStateOf("") }
    var detail by remember { mutableStateOf<MvrSceneObject?>(null) }
    val fixtures = scene.fixtures

    // Filtres à FACETTES multi-sélection (calque / type GDTF / mode / univers),
    // combinés avec la recherche texte — comme PatchListView iOS.
    var selLayers by remember(scene) { mutableStateOf(emptySet<String>()) }
    var selTypes by remember(scene) { mutableStateOf(emptySet<String>()) }
    var selModes by remember(scene) { mutableStateOf(emptySet<String>()) }
    var selUniverses by remember(scene) { mutableStateOf(emptySet<String>()) }
    val layers = remember(scene) { fixtures.map { it.layerName }.distinct().sorted() }
    val types = remember(scene) { fixtures.mapNotNull { it.gdtfSpec }.distinct().sorted() }
    val modes = remember(scene) { fixtures.mapNotNull { it.gdtfMode }.distinct().sorted() }
    val universes = remember(scene) {
        fixtures.flatMap { it.addresses }.mapNotNull { universeOf(it) }.distinct().sortedBy { it.toIntOrNull() ?: 0 }
    }

    val filtered = remember(scene, query, selLayers, selTypes, selModes, selUniverses) {
        val q = query.trim()
        fixtures.filter { f ->
            (q.isEmpty() ||
                f.fixtureId?.contains(q, true) == true || f.name.contains(q, true) ||
                f.gdtfSpec?.contains(q, true) == true || f.layerName.contains(q, true) ||
                f.addresses.any { it.contains(q, true) }) &&
            (selLayers.isEmpty() || f.layerName in selLayers) &&
            (selTypes.isEmpty() || f.gdtfSpec in selTypes) &&
            (selModes.isEmpty() || f.gdtfMode in selModes) &&
            (selUniverses.isEmpty() || f.addresses.any { universeOf(it) in selUniverses })
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Patch", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour 3D")
                }
            }
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Filtrer (ID, GDTF, calque, DMX…)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FacetChip("Calque", layers, selLayers) { selLayers = it }
            FacetChip("Type", types, selTypes) { selTypes = it }
            FacetChip("Mode", modes, selModes) { selModes = it }
            FacetChip("Univers", universes, selUniverses) { selUniverses = it }
            val active = selLayers.size + selTypes.size + selModes.size + selUniverses.size
            if (active > 0) {
                Text("Réinit.", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable {
                        selLayers = emptySet(); selTypes = emptySet()
                        selModes = emptySet(); selUniverses = emptySet()
                    }.padding(8.dp))
            }
        }
        Text(
            "${filtered.size} / ${fixtures.size} projecteur(s) · touchez pour éditer",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        // Deux projecteurs ne peuvent pas porter le même N° : sur un vrai
        // plateau c'est une erreur de patch (le pupitre en piloterait deux, ou
        // aucun). On la signale ici plutôt que de la découvrir en montage.
        val dupIds = remember(fixtures, overrides.version) {
            fixtures.mapNotNull { overrides.effectiveId(it)?.trim()?.ifBlank { null } }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        }
        if (dupIds.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "N° en double : " + dupIds.sorted().joinToString(", ") { "#" + it },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered) { f ->
                FixtureRow(f, overrides, overrides.effectiveId(f)?.trim() in dupIds) { detail = f }
            }
        }
    }

    detail?.let { f ->
        FixtureDetailSheet(fixture = f, mvrBytes = mvrBytes, overrides = overrides, onDismiss = { detail = null })
    }
}

/** Univers DMX d'une adresse (partie avant le « . » de « u.a »), ou null. */
private fun universeOf(addr: String): String? {
    val u = DmxAddress.format(addr).substringBefore(".", "")
    return u.ifBlank { null }
}

/** Puce de filtre à facettes : ouvre un menu de valeurs cochables (multi-sélection). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FacetChip(label: String, options: List<String>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    if (options.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(end = 6.dp)) {
        FilterChip(
            selected = selected.isNotEmpty(),
            onClick = { open = true },
            label = { Text(if (selected.isEmpty()) label else "$label (${selected.size})") }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                val on = opt in selected
                DropdownMenuItem(
                    text = { Text(opt) },
                    trailingIcon = { if (on) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { onChange(if (on) selected - opt else selected + opt) }
                )
            }
        }
    }
}

@Composable
private fun FixtureRow(
    f: MvrSceneObject, overrides: PatchOverrides, duplicateId: Boolean = false, onClick: () -> Unit
) {
    val edited = overrides.isEdited(f)
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val id = overrides.effectiveId(f)?.let { "#$it  " } ?: ""
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (duplicateId) {
                Icon(Icons.Filled.Warning, contentDescription = "N° en double",
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 6.dp))
            }
            Text(
                "$id${f.name}" + if (edited) "  ✎" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = if (duplicateId) MaterialTheme.colorScheme.error else Color.Unspecified
            )
        }
        val spec = f.gdtfSpec ?: "—"
        val mode = overrides.effectiveMode(f) ?: "—"
        val addr = overrides.effectiveAddress(f)?.let { com.minou.mvrviewer.mvr.DmxAddress.format(it) } ?: "—"
        Text(
            "$spec · $mode · DMX $addr",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
