package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * Première tranche : liste des projecteurs du .mvr chargé. La 3D (SceneView) et
 * la vue plan 2D viendront s'ajouter comme onglets/écrans par-dessus ceci.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneScreen(
    scene: MvrScene,
    fileName: String,
    mvrBytes: ByteArray,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var show3D by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(false) }
    if (show3D) {
        Scene3DScreen(scene = scene, mvrBytes = mvrBytes, onBack = { show3D = false }, modifier = modifier)
        return
    }
    if (showPlan) {
        PlanScreen(scene = scene, onBack = { showPlan = false }, modifier = modifier)
        return
    }

    val fixtures = scene.fixtures
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(fileName, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer")
                }
            },
            actions = {
                IconButton(onClick = { showPlan = true }) {
                    Icon(Icons.Filled.Map, contentDescription = "Vue plan")
                }
                IconButton(onClick = { show3D = true }) {
                    Icon(Icons.Filled.ViewInAr, contentDescription = "Vue 3D")
                }
            }
        )
        Text(
            "${scene.layers.size} calque(s) · ${scene.allObjects.size} objet(s) · ${fixtures.size} projecteur(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(fixtures) { f -> FixtureRow(f) }
        }
    }
}

@Composable
private fun FixtureRow(f: MvrSceneObject) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val id = f.fixtureId?.let { "#$it  " } ?: ""
        Text("$id${f.name}", style = MaterialTheme.typography.bodyLarge)
        val spec = f.gdtfSpec ?: "—"
        val addr = if (f.addresses.isNotEmpty()) f.addresses.joinToString(",") else "—"
        Text(
            "$spec · ${f.layerName} · DMX $addr",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
