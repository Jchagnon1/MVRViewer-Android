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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minou.mvrviewer.sync.SyncViewModel
import com.minou.mvrviewer.mvr.IncomingFileRouter
import com.minou.mvrviewer.ui.HomeMaLibraryHost
import com.minou.mvrviewer.ui.HomeScreen
import com.minou.mvrviewer.ui.IncomingFiles
import com.minou.mvrviewer.ui.SceneScreen
import com.minou.mvrviewer.ui.SceneViewModel
import com.minou.mvrviewer.ui.theme.MvrViewerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Fichiers ouverts DEPUIS une autre appli (gestionnaire de fichiers, Drive,
    // « Ouvrir avec… ») via ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE.
    // Équivalent Android du `onOpenURL` iOS. Consommés une fois par l'écran, puis
    // AIGUILLÉS (show, objet 3D ou bibliothèque MA — SPEC-ma-gdtfshare.md §5.3).
    private val incomingUris = mutableStateOf<List<Uri>>(emptyList())

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Journal de diagnostic (plantages + gels) le plus tôt possible, pour
        // capturer même un problème au tout démarrage. Cf. CrashReporter.
        CrashReporter.install(this)
        // Accès Pro (achat intégré). Crochet de test DEBUG :
        //   adb shell am start -n com.minou.mvrviewer/.MainActivity --ez MVR_PRO true
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val proOverride = if (isDebuggable && intent.hasExtra("MVR_PRO"))
            intent.getBooleanExtra("MVR_PRO", false) else null
        com.minou.mvrviewer.ui.ProAccess.init(this, proOverride)
        // GDTF Share simulé (DEBUG seulement) : dossier `getList.json` + `<rid>.gdtf`
        // (`<rid>.fail` = panne), poussé par adb dans le stockage propre à l'app :
        //   adb shell am start -n com.minou.mvrviewer/.MainActivity \
        //     --es MVR_GDTFSHARE_MOCK_DIR /sdcard/Android/data/com.minou.mvrviewer/files/gdtfmock
        if (isDebuggable) intent.getStringExtra("MVR_GDTFSHARE_MOCK_DIR")?.let {
            com.minou.mvrviewer.mvr.GdtfShareClient.enableMock(java.io.File(it))
        }
        // Crochets d'essai du rangement des menus (MVR_AUTO_PLAN, MVR_AUTO_DEST,
        // MVR_AUTO_PLAN_START, MVR_AUTO_HOME_DEST) — débogable seulement.
        com.minou.mvrviewer.ui.DebugHooks.read(intent, isDebuggable)
        // MVR_RESET_TOOLBARS : avant toute vue (les dispositions sont lues à l'ouverture d'un projet).
        if (com.minou.mvrviewer.ui.DebugHooks.takeResetToolbars()) com.minou.mvrviewer.ui.ToolbarLayoutStore.reset(this)
        enableEdgeToEdge()
        incomingUris.value = urisFrom(intent)
        setContent {
            MvrViewerTheme {
                // En DEBUG, les étiquettes de test deviennent des identifiants de
                // ressource : `uiautomator dump` les lit (contrôles à l'écran).
                val rootModifier = if (isDebuggable) {
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
                } else Modifier.fillMaxSize()
                Scaffold(modifier = rootModifier) { padding ->
                    App(
                        modifier = Modifier.padding(padding),
                        incomingUris = incomingUris.value,
                        onUrisConsumed = { incomingUris.value = emptyList() }
                    )
                    // Paywall posé UNE fois, par-dessus tout écran.
                    com.minou.mvrviewer.ui.ProPaywallHost()
                    // Refus du rattachement de l'achat au compte (après un geste
                    // fait dans le paywall, déjà fermé) — SPEC-purchase §6.4.
                    com.minou.mvrviewer.ui.ProLinkRefusalHost()
                }
            }
        }
    }

    // L'appli est en singleTop : un nouvel « ouvrir avec » alors qu'elle tourne
    // déjà arrive ici plutôt que de recréer l'activité.
    override fun onResume() {
        super.onResume()
        // Achat validé hors app, remboursement : relire les droits au retour.
        com.minou.mvrviewer.ui.ProAccess.refresh()
        // Preuve d'achat restée en échec RÉESSAYABLE (hors ligne, 503…) :
        // renvoyée une fois par retour au premier plan (SPEC-purchase §6.1).
        // MÊME instance que le `viewModel()` de l'écran (magasin de l'activité,
        // clé par défaut).
        ViewModelProvider(this)[SyncViewModel::class.java].onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.minou.mvrviewer.ui.DebugHooks.read(
            intent, (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )
        if (com.minou.mvrviewer.ui.DebugHooks.takeResetToolbars()) com.minou.mvrviewer.ui.ToolbarLayoutStore.reset(this)
        incomingUris.value = urisFrom(intent)
    }

    private fun urisFrom(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}

@Composable
private fun App(
    modifier: Modifier = Modifier,
    incomingUris: List<Uri> = emptyList(),
    onUrisConsumed: () -> Unit = {}
) {
    val vm: SceneViewModel = viewModel()
    // Cerveau de la synchro cloud, partagé accueil ↔ scène. Reconnexion silencieuse
    // au démarrage (session Firebase persistée, ou compte stub local).
    val sync: SyncViewModel = viewModel()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { sync.restoreSessionIfPossible() }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Bibliothèque MA ouverte depuis l'ACCUEIL (bouton, ou profil reçu sans show).
    var homeMaOpen by remember { mutableStateOf(false) }
    // « Compte & réglages » ouvert depuis l'ACCUEIL (bouton, carte) — écran plein
    // hébergé ici, avec son propre retour système vers l'accueil (§3.11). Crochet
    // DEBUG `MVR_AUTO_HOME_DEST=account`.
    var homeAccountOpen by remember {
        mutableStateOf(com.minou.mvrviewer.ui.DebugHooks.takeAutoHomeDest()?.trim()?.lowercase() == "account")
    }
    // Ouverture externe : dès que des URI arrivent, on les AIGUILLE (une seule fois).
    // Un fichier seul : show / objet 3D (comportement existant) ou bibliothèque MA ;
    // plusieurs fichiers partagés : UN lot dans la bibliothèque MA (un .mvr compte ignoré).
    LaunchedEffect(incomingUris) {
        if (incomingUris.isEmpty()) return@LaunchedEffect
        val uris = incomingUris
        onUrisConsumed()
        scope.launch {
            val toLibrary = uris.size > 1 || when (withContext(Dispatchers.IO) {
                runCatching { IncomingFiles.route(ctx, uris[0]) }.getOrDefault(IncomingFileRouter.Route.MVR)
            }) {
                IncomingFileRouter.Route.MVR, IncomingFileRouter.Route.MODEL -> false
                IncomingFileRouter.Route.GDTF, IncomingFileRouter.Route.MA_PROFILES -> true
            }
            if (!toLibrary) { vm.open(uris[0]); return@launch }
            // Recevoir un profil ne ferme JAMAIS le show : la bibliothèque s'ouvre par-dessus
            // (SceneScreen surveille la file), sinon depuis l'accueil.
            vm.maInbox.enqueue(uris)
            if (vm.state.value !is SceneViewModel.UiState.Loaded) homeMaOpen = true
        }
    }

    val state by vm.state
    // Un show ouvert reprend la main : au retour à l'accueil, c'est l'accueil qui s'affiche.
    LaunchedEffect(state) {
        if (state is SceneViewModel.UiState.Loaded) { homeMaOpen = false; homeAccountOpen = false }
    }
    when (val s = state) {
        is SceneViewModel.UiState.Home,
        is SceneViewModel.UiState.Error ->
            if (homeMaOpen) HomeMaLibraryHost(vm, modifier) { homeMaOpen = false }
            else if (homeAccountOpen) {
                androidx.activity.compose.BackHandler { homeAccountOpen = false }
                com.minou.mvrviewer.ui.AccountSettingsScreen(sync = sync, onBack = { homeAccountOpen = false }, modifier = modifier)
            }
            else HomeScreen(
                state = s, modifier = modifier, onOpen = { uri: Uri -> vm.open(uri) },
                onOpenMaLibrary = { homeMaOpen = true },
                onOpenAccountSettings = { homeAccountOpen = true },
                sync = sync,
                onOpenCloudProject = { project ->
                    scope.launch {
                        runCatching { sync.downloadMvr(project) }.getOrNull()?.let {
                            vm.openBytes(it, project.name)
                        }
                    }
                },
                progress = vm.progress
            )
        is SceneViewModel.UiState.Loading ->
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
                progress = vm.progress,
                // « Fermer le projet » pendant la lecture : chargement annulé, accueil.
                onCancelLoading = { vm.reset() }
            )
        is SceneViewModel.UiState.Loaded ->
            SceneScreen(
                scene = s.scene, fileName = s.fileName, mvrBytes = s.bytes, modifier = modifier,
                onClose = { sync.detach(); vm.reset() },
                sync = sync,
                maLibrary = vm.maLibrary,
                maInbox = vm.maInbox,
                onReopenBytes = { bytes, name -> vm.openBytes(bytes, name) },
                // OBJET 3D ouvert seul depuis l'accueil : la scène est vide, la
                // vue 3D importe ce modèle au premier affichage.
                initialModelUri = vm.pendingModelUri.value,
                onInitialModelConsumed = { vm.consumePendingModelUri() },
                // La suite du chargement (3D, GDTF, plan) continue d'alimenter la
                // MÊME progression : le pourcentage est global, pas par écran.
                progress = vm.progress
            )
    }
}
