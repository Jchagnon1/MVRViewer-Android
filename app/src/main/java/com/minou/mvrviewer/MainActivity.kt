package com.minou.mvrviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minou.mvrviewer.sync.SyncViewModel
import com.minou.mvrviewer.ui.HomeScreen
import com.minou.mvrviewer.ui.SceneScreen
import com.minou.mvrviewer.ui.SceneViewModel
import com.minou.mvrviewer.ui.theme.MvrViewerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // URI d'un .mvr ouvert DEPUIS une autre appli (gestionnaire de fichiers,
    // Drive, « Ouvrir avec… ») via ACTION_VIEW / ACTION_SEND. Équivalent Android
    // du `onOpenURL` iOS. Consommé une fois par l'écran (LaunchedEffect).
    private val incomingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Journal de diagnostic (plantages + gels) le plus tôt possible, pour
        // capturer même un problème au tout démarrage. Cf. CrashReporter.
        CrashReporter.install(this)
        enableEdgeToEdge()
        incomingUri.value = uriFrom(intent)
        setContent {
            MvrViewerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    App(
                        modifier = Modifier.padding(padding),
                        incomingUri = incomingUri.value,
                        onUriConsumed = { incomingUri.value = null }
                    )
                }
            }
        }
    }

    // L'appli est en singleTop : un nouvel « ouvrir avec » alors qu'elle tourne
    // déjà arrive ici plutôt que de recréer l'activité.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri.value = uriFrom(intent)
    }

    private fun uriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}

@Composable
private fun App(
    modifier: Modifier = Modifier,
    incomingUri: Uri? = null,
    onUriConsumed: () -> Unit = {}
) {
    val vm: SceneViewModel = viewModel()
    // Cerveau de la synchro cloud, partagé accueil ↔ scène. Reconnexion silencieuse
    // au démarrage (session Firebase persistée, ou compte stub local).
    val sync: SyncViewModel = viewModel()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { sync.restoreSessionIfPossible() }

    // Ouverture externe : dès qu'une URI arrive, on la charge (une seule fois).
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            vm.open(incomingUri)
            onUriConsumed()
        }
    }

    val state by vm.state
    when (val s = state) {
        is SceneViewModel.UiState.Home,
        is SceneViewModel.UiState.Loading,
        is SceneViewModel.UiState.Error ->
            HomeScreen(
                state = s, modifier = modifier, onOpen = { uri: Uri -> vm.open(uri) },
                sync = sync,
                onOpenCloudProject = { project ->
                    scope.launch {
                        runCatching { sync.downloadMvr(project) }.getOrNull()?.let {
                            vm.openBytes(it, project.name)
                        }
                    }
                },
                // Étapes nommées + pourcentage global du chargement (#4).
                progress = vm.progress
            )
        is SceneViewModel.UiState.Loaded ->
            SceneScreen(
                scene = s.scene, fileName = s.fileName, mvrBytes = s.bytes, modifier = modifier,
                onClose = { sync.detach(); vm.reset() },
                sync = sync,
                onReopenBytes = { bytes, name -> vm.openBytes(bytes, name) },
                // La suite du chargement (3D, GDTF, plan) continue d'alimenter la
                // MÊME progression : le pourcentage est global, pas par écran.
                progress = vm.progress
            )
    }
}
