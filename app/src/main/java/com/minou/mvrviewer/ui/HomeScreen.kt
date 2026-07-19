package com.minou.mvrviewer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Écran d'accueil : ouvrir un .mvr (Storage Access Framework) + liste des
 * PROJETS RÉCENTS pour rouvrir sans re-naviguer (équivalent de HomeView iOS).
 */
@Composable
fun HomeScreen(
    state: SceneViewModel.UiState,
    onOpen: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    // Le MVR n'a pas de type MIME standard : on ouvre en "*/*".
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onOpen) }
    // Rechargé à chaque passage sur l'accueil (l'état change en revenant ici).
    var recents by remember(state) { mutableStateOf(RecentProjects.list(ctx)) }

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
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
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
                if (recents.isNotEmpty()) {
                    Text(
                        "Projets récents",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 28.dp, bottom = 4.dp)
                    )
                    LazyColumn(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                        items(recents, key = { it.uri }) { r ->
                            HorizontalDivider()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { runCatching { onOpen(Uri.parse(r.uri)) } }
                                    .padding(vertical = 12.dp)
                            ) {
                                Icon(Icons.Filled.Description, contentDescription = null,
                                    modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "  ${r.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    RecentProjects.remove(ctx, r.uri)
                                    recents = RecentProjects.list(ctx)
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Retirer",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                // Rapport de diagnostic : n'apparaît que si un plantage/gel a été
                // journalisé. Le testeur (Stéphane) peut ainsi nous l'envoyer.
                val hasDiag = remember(state) {
                    com.minou.mvrviewer.CrashReporter.logFile(ctx).let { it.exists() && it.length() > 0L }
                }
                if (hasDiag) {
                    TextButton(onClick = { shareDiag(ctx) }, modifier = Modifier.padding(top = 20.dp)) {
                        Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Envoyer le rapport de diagnostic")
                    }
                }
            }
        }
    }
}

/** Partage le journal de diagnostic (plantages + gels) via ACTION_SEND. */
private fun shareDiag(ctx: android.content.Context) {
    val f = com.minou.mvrviewer.CrashReporter.logFile(ctx)
    if (!f.exists() || f.length() == 0L) return
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }.getOrNull() ?: return
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "MVR Viewer — rapport de diagnostic")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Envoyer le rapport")) }
}
