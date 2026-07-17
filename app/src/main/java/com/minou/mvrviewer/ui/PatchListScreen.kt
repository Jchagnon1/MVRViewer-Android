package com.minou.mvrviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
    val filtered = remember(scene, query) {
        val q = query.trim()
        if (q.isEmpty()) fixtures
        else fixtures.filter { f ->
            f.fixtureId?.contains(q, true) == true ||
                f.name.contains(q, true) ||
                f.gdtfSpec?.contains(q, true) == true ||
                f.layerName.contains(q, true) ||
                f.addresses.any { it.contains(q, true) }
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
        Text(
            "${filtered.size} / ${fixtures.size} projecteur(s) · touchez pour éditer",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered) { f -> FixtureRow(f, overrides) { detail = f } }
        }
    }

    detail?.let { f ->
        FixtureDetailSheet(fixture = f, mvrBytes = mvrBytes, overrides = overrides, onDismiss = { detail = null })
    }
}

@Composable
private fun FixtureRow(f: MvrSceneObject, overrides: PatchOverrides, onClick: () -> Unit) {
    val edited = overrides.isEdited(f)
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val id = overrides.effectiveId(f)?.let { "#$it  " } ?: ""
        Text("$id${f.name}" + if (edited) "  ✎" else "", style = MaterialTheme.typography.bodyLarge)
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
