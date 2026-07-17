package com.minou.mvrviewer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Écran d'accueil : ouvrir un .mvr (Storage Access Framework). Équivalent
 * simplifié de HomeView (iOS). Les « projets récents » viendront plus tard.
 */
@Composable
fun HomeScreen(
    state: SceneViewModel.UiState,
    onOpen: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    // Le MVR n'a pas de type MIME standard : on ouvre en "*/*".
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onOpen) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("MVR Viewer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Visualiseur de plans lumière MVR / GDTF",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
            textAlign = TextAlign.Center
        )

        when (state) {
            is SceneViewModel.UiState.Loading -> {
                CircularProgressIndicator()
                Text(
                    "Lecture de ${state.fileName}…",
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Ouvrir un fichier .mvr")
                }
                if (state is SceneViewModel.UiState.Error) {
                    Text(
                        "⚠️ ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 20.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
