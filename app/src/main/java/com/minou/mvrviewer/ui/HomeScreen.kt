package com.minou.mvrviewer.ui

import androidx.compose.ui.res.pluralStringResource
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.minou.mvrviewer.sync.syncMessage
import kotlinx.coroutines.launch
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * Écran d'accueil : ouvrir un .mvr (Storage Access Framework) + liste des
 * PROJETS RÉCENTS pour rouvrir sans re-naviguer (équivalent de HomeView iOS).
 */
@OptIn(ExperimentalFoundationApi::class)
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
    onOpenMaLibrary: () -> Unit = {},
    // « Compte & réglages » (écran plein hébergé par App) — SPEC-menus-android §3.11.
    onOpenAccountSettings: () -> Unit = {},
    // « Fermer le projet » pendant la lecture du .mvr (abandon sûr du chargement).
    onCancelLoading: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showJoin by remember { mutableStateOf(false) }
    /** Menu de ligne ouvert (id du projet partagé), confirmation en attente, action en cours, erreur lisible. */
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<ModerationConfirmation?>(null) }
    var moderationBusy by remember { mutableStateOf(false) }
    var moderationError by remember { mutableStateOf<String?>(null) }
    val reporter = rememberContentReporter()
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
                // Sortie pendant la lecture (iOS §11) : le chargement est annulé, son
                // résultat ignoré (SceneViewModel.reset), retour à l'accueil.
                OutlinedButton(
                    onClick = onCancelLoading,
                    modifier = Modifier.padding(top = 16.dp).testTag("loading.closeProject")
                ) { Text(stringResource(R.string.menu_close_project)) }
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
                // INVITATION À CRÉER UN COMPTE — pour tout le monde, gratuit
                // compris : c'est par le compte que l'équipe REÇOIT les projets
                // partagés par le chef de chantier (Pro), avec ses modifications
                // en direct. Lire est gratuit.
                if (sync != null) {
                    val auth by sync.auth.collectAsState()
                    if (!auth.isSignedIn) AccountPromptCard(onClick = onOpenAccountSettings)
                }
                // « Compte & réglages » (compte, Pro, GDTF Share) — toujours présent ;
                // « Rejoindre un projet » reste sur l'accueil (synchro branchée).
                Row(modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenAccountSettings, modifier = Modifier.testTag("home.accountSettings")) {
                        Text(stringResource(R.string.account_settings_title))
                    }
                    if (sync != null) {
                        TextButton(onClick = { showJoin = true }) { Text(stringResource(R.string.join_project)) }
                    }
                }
                // Synchro cloud : projets partagés.
                if (sync != null) {
                    // Projet quitté ou supprimé depuis le menu de ligne, accès perdu
                    // (SPEC-partage-moderation §5.12, §6.3) : le message, une fois.
                    Box(Modifier.widthIn(max = 420.dp)) { ProjectNoticeBanner(sync) }
                    moderationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).testTag("home.moderationError"))
                    }
                    // Projets partagés (cloud) : ouverts en un tap (comme iOS RootView) ;
                    // APPUI LONG → menu de ligne (§6.1) : signaler ou quitter SANS
                    // télécharger le projet (un contenu abusif n'a pas à être ouvert).
                    val projects by sync.cloudProjects.collectAsState()
                    val auth by sync.auth.collectAsState()
                    if (projects.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)) {
                            Text(stringResource(R.string.home_shared_projects), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (moderationBusy) {
                                val label = stringResource(R.string.sync_moderation_progress)
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(start = 8.dp)
                                    .size(16.dp).semantics { contentDescription = label }.testTag("home.moderationBusy"))
                            }
                        }
                        LazyColumn(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 170.dp)) {
                            items(projects, key = { it.id }) { p ->
                                HorizontalDivider()
                                Box {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                            .combinedClickable(
                                                enabled = !moderationBusy,
                                                onClick = { onOpenCloudProject(p) },
                                                onLongClick = { menuFor = p.id }
                                            )
                                            .padding(vertical = 12.dp)
                                            .testTag("home.cloudProject.${p.id}")) {
                                        Icon(Icons.Filled.CloudDownload, contentDescription = null,
                                            modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                            Text(p.name, style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(pluralStringResource(R.plurals.home_members_version, p.memberUids.size, p.memberUids.size, p.mvrVersion),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    val account = auth.accountOrNull
                                    if (account != null) CloudRowMenu(
                                        p, account, expanded = menuFor == p.id, onDismiss = { menuFor = null },
                                        onReport = { menuFor = null; reporter.report(ctx, p, account) },
                                        onConfirm = { menuFor = null; confirmation = it }
                                    )
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
                // Rapport de diagnostic (guide C9) : n'apparaît que si le journal contient
                // un PLANTAGE ou un GEL — pas pour la simple ligne « démarrage » écrite à
                // chaque lancement. Il part par e-mail, prérempli vers l'assistance.
                // Lu hors du thread principal (le journal peut peser 512 Ko).
                val hasIncident by produceState(initialValue = false, state) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.minou.mvrviewer.CrashReporter.hasIncident(ctx)
                    }
                }
                if (hasIncident) {
                    TextButton(onClick = { com.minou.mvrviewer.DiagnosticReport.send(ctx) },
                        modifier = Modifier.padding(top = 20.dp).testTag("home.sendDiag")) {
                        Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  " + stringResource(R.string.home_send_diag))
                    }
                }
            }
        }

        // Dialogue de synchro cloud (accueil).
        if (sync != null) {
            if (showJoin) JoinProjectDialog(sync, onDismiss = { showJoin = false }) { project ->
                onOpenCloudProject(project)
            }
            // Quitter ou supprimer depuis le menu de ligne (GRATUIT, §10) : le message vient
            // du ViewModel (bandeau ci-dessus), l'erreur lisible s'affiche au-dessus (§7).
            confirmation?.let { c ->
                ModerationConfirmDialog(c, onDismiss = { confirmation = null }, onConfirm = {
                    if (!moderationBusy) {
                        moderationBusy = true
                        moderationError = null
                        scope.launch {
                            try {
                                when (c) {
                                    is ModerationConfirmation.Leave -> sync.leaveProject(c.project)
                                    is ModerationConfirmation.Delete -> sync.deleteProject(c.project)
                                    is ModerationConfirmation.NewCode, is ModerationConfirmation.Remove -> Unit
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                moderationError = e.syncMessage(ctx)
                            } finally {
                                moderationBusy = false
                            }
                        }
                    }
                })
            }
            reporter.Fallback()
        }
    }
}


/**
 * Menu de ligne de « Projets partagés » (SPEC-partage-moderation §6.1), dans cet ordre :
 * « Signaler un contenu… » ; « Quitter le projet… » (sauf propriétaire seul) ;
 * « Supprimer le projet… » (propriétaire seulement, rouge). GRATUIT (§10).
 */
@Composable
private fun CloudRowMenu(
    p: com.minou.mvrviewer.sync.CloudProject, account: com.minou.mvrviewer.sync.AccountInfo,
    expanded: Boolean, onDismiss: () -> Unit,
    onReport: () -> Unit, onConfirm: (ModerationConfirmation) -> Unit
) {
    val unnamed = stringResource(R.string.sync_unnamed_member)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sync_report_content)) },
            leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
            onClick = onReport,
            modifier = Modifier.testTag("home.menu.report")
        )
        ModerationConfirmation.leave(p, account.uid, unnamed)?.let { leave ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_leave_project)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                onClick = { onConfirm(leave) },
                modifier = Modifier.testTag("home.menu.leave")
            )
        }
        if (p.ownerUid == account.uid) {
            val red = MaterialTheme.colorScheme.error
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_delete_project), color = red) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = red) },
                onClick = { onConfirm(ModerationConfirmation.Delete(p)) },
                modifier = Modifier.testTag("home.menu.delete")
            )
        }
    }
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
            Text(stringResource(R.string.home_account_prompt_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.home_account_prompt_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            TextButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.home_account_prompt_action))
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
