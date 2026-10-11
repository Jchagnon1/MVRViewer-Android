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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * Écran d'accueil : ouvrir un .mvr (Storage Access Framework) + liste des
 * PROJETS RÉCENTS pour rouvrir sans re-naviguer (équivalent de HomeView iOS).
 */
@Composable
fun HomeScreen(
    state: SceneViewModel.UiState,
    onOpen: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    sync: com.minou.mvrviewer.sync.SyncViewModel? = null,
    onOpenCloudProject: (com.minou.mvrviewer.sync.CloudProject) -> Unit = {},
    // Avancement NOMMÉ du chargement (#4). null → ancien indicateur indéterminé.
    progress: LoadProgress? = null,
    // Bibliothèque MA depuis l'accueil (comme iOS), sans show ouvert.
    onOpenMaLibrary: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var showAccount by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
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
            stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            textAlign = TextAlign.Center
        )

        when (state) {
            is SceneViewModel.UiState.Loading -> {
                // Barre DÉTERMINÉE + libellé de l'ÉTAPE en cours + pourcentage
                // GLOBAL (#4). Le nom du fichier passe en 2e ligne discrète.
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                    )
                    Text(
                        "${stringResource((progress.step ?: LoadStep.READ_MVR).labelRes)}  ${progress.percent} %",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    CircularProgressIndicator()
                }
                Text(
                    state.fileName,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            else -> {
                // ACTION PRINCIPALE = surface d'identité : jaune franc,
                // contenu noir (cf. theme/Color.kt — jamais de blanc sur jaune).
                Button(onClick = { picker.launch(arrayOf("*/*")) },
                       colors = com.minou.mvrviewer.ui.theme.accentButtonColors()) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  " + stringResource(R.string.home_open_mvr))
                }
                // Le même bouton ouvre AUSSI un objet 3D seul (SceneViewModel.open
                // détecte le format aux octets de tête) — le dire, sans ajouter de
                // friction sur l'action la plus fréquente.
                Text(
                    stringResource(R.string.home_open_formats_fmt, com.minou.mvrviewer.mvr.SceneModelLoader.SUPPORTED_SUMMARY),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )
                // Bibliothèque MA : TOUJOURS visible (profils importés, recherche GDTF Share).
                TextButton(onClick = onOpenMaLibrary, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.ma_library))
                }
                // Synchro cloud : compte + rejoindre un projet partagé.
                if (sync != null) {
                    val auth by sync.auth.collectAsState()
                    // INVITATION À CRÉER UN COMPTE — pour tout le monde, gratuit
                    // compris : c'est par le compte que l'équipe REÇOIT les
                    // projets partagés par le chef de chantier (Pro), avec ses
                    // modifications en direct. Lire est gratuit.
                    if (!auth.isSignedIn) AccountPromptCard(onClick = { showAccount = true })
                    Row(modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showAccount = true }) {
                            Text(stringResource(if (auth.isSignedIn) R.string.home_my_account else R.string.home_sign_in))
                        }
                        TextButton(onClick = { showJoin = true }) { Text(stringResource(R.string.join_project)) }
                    }
                    // Projets partagés (cloud) : ouverts en un tap (comme iOS RootView).
                    val projects by sync.cloudProjects.collectAsState()
                    if (projects.isNotEmpty()) {
                        Text(stringResource(R.string.home_shared_projects), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                        LazyColumn(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 170.dp)) {
                            items(projects, key = { it.id }) { p ->
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { onOpenCloudProject(p) }
                                        .padding(vertical = 12.dp)) {
                                    Icon(Icons.Filled.CloudDownload, contentDescription = null,
                                        modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(p.name, style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(stringResource(R.string.home_members_version, p.memberUids.size, p.mvrVersion),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
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
                        stringResource(R.string.home_recent_projects),
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
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_remove),
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
                        Text("  " + stringResource(R.string.home_send_diag))
                    }
                }
            }
        }

        // Dialogues de synchro cloud (accueil).
        if (sync != null) {
            if (showAccount) AccountDialog(sync) { showAccount = false }
            if (showJoin) JoinProjectDialog(sync, onDismiss = { showJoin = false }) { project ->
                onOpenCloudProject(project)
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
        putExtra(android.content.Intent.EXTRA_SUBJECT, ctx.getString(R.string.diag_subject))
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(android.content.Intent.createChooser(send, ctx.getString(R.string.diag_share))) }
}


/** Carte d'invitation au compte — touche d'identité (voile jaune), une seule action. */
@Composable
private fun AccountPromptCard(onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        color = com.minou.mvrviewer.ui.theme.Accent.fillLight.copy(alpha = 0.14f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.padding(top = 14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Créez votre compte (gratuit)", style = MaterialTheme.typography.titleSmall)
            Text(
                "C'est par lui que vous recevez les projets partagés par votre chef de chantier, avec ses modifications en direct. Lire est gratuit ; seule l'édition passe en Pro.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            TextButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
                Text("Créer un compte ou se connecter…")
            }
        }
    }
}


/**
 * « Bibliothèque MA » hébergée par l'ACCUEIL, sans show (SPEC-ma-gdtfshare.md §5.5) : même
 * écran que dans un show (`scene = null` : « Aucun show ouvert » au choix de la 3D). La
 * bibliothèque custom locale est relue à chaque ouverture (un show a pu la modifier) ; un
 * type créé ici est enregistré sur l'appareil (aucune sélection à affecter).
 */
@Composable
fun HomeMaLibraryHost(vm: SceneViewModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val gdtfOverrides = remember { GdtfOverrides() }
    val customLibrary = remember { CustomFixtureLibrary().apply { seed(CustomFixtureLibraryStore.load(ctx)) } }
    val customResolver = remember { CustomFixtureResolver().apply { setLibrary(customLibrary.types.toList()) } }
    val fixture3DStore = remember { Fixture3DStore.of(ctx) }
    fun persist() {
        customResolver.setLibrary(customLibrary.types.toList())
        CustomFixtureLibraryStore.save(ctx, customLibrary.types.toList())
    }
    // Retour matériel depuis la LISTE : retour à l'accueil (les pages internes ont le leur,
    // composé plus tard, donc prioritaire).
    androidx.activity.compose.BackHandler { onBack() }
    MaLibraryScreen(
        state = vm.maLibrary,
        scene = null,
        mvrBytes = null,
        gdtfOverrides = gdtfOverrides,
        customLibrary = customLibrary,
        customResolver = customResolver,
        fixture3DStore = fixture3DStore,
        sharedProjectOpen = false,
        selectionCount = 0,
        onSaveType = { t, _ ->
            customLibrary.upsert(t)
            customResolver.setLibrary(customLibrary.types.toList())
            val list = customLibrary.types.toList()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CustomFixtureLibraryStore.save(ctx, list) }
            true
        },
        onCustomLibraryChanged = { persist() },
        onBack = onBack,
        modifier = modifier,
        inbox = vm.maInbox
    )
}
