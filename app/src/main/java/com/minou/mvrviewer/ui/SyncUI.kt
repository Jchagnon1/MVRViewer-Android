package com.minou.mvrviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.DmxAddress
import com.minou.mvrviewer.sync.AuditEntry
import com.minou.mvrviewer.sync.CloudProject
import com.minou.mvrviewer.sync.ProjectNames
import com.minou.mvrviewer.sync.ShareCodeResult
import com.minou.mvrviewer.sync.SyncViewModel
import com.minou.mvrviewer.sync.syncMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * UI de synchronisation cloud (portage des vues iOS AccountView / ProjectShareView
 * / JoinProjectView / HistoryView / SyncStatusBadge). Chaque écran est un dialogue
 * piloté par le [SyncViewModel] partagé. Tout est no-op visuel si déconnecté.
 */

// MARK: - Compte : l'écran « Compte & réglages » (AccountSettingsScreen.kt)
// remplace l'ancien dialogue AccountDialog (SPEC-menus-android §3.11).

/**
 * Confirmation de suppression de compte : le mot de passe est re-saisi (le
 * backend le re-vérifie), l'erreur s'affiche dans le dialogue, qui se ferme
 * seul à la réussite. Verrouillé pendant l'opération (pas de double envoi).
 */
@Composable
internal fun DeleteAccountDialog(sync: SyncViewModel, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sync_delete_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.sync_delete_account_warning))
                Text(stringResource(R.string.sync_delete_account_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.sync_password)) },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                if (busy) {
                    // Libellé PROPRE à l'action destructive (pas « Synchro… », réservé
                    // au badge du projet partagé) ; le même texte sert de
                    // contentDescription au spinner pour les lecteurs d'écran.
                    val progressLabel = stringResource(R.string.sync_delete_account_progress)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier
                            .heightIn(max = 18.dp)
                            .semantics { contentDescription = progressLabel })
                        Text(progressLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { sync.deleteAccount(password); onDeleted() }
                        catch (e: Exception) { error = e.syncMessage(ctx) } finally { busy = false }
                    }
                }
            ) { Text(stringResource(R.string.sync_delete_account_confirm)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

// MARK: - Partager le projet (publier + code)
//
// Partage PAR CODE seulement (guide de mise en vente C4, comme iOS) : l'ancien
// « Partager le lien » envoyait mvrviewer://join/CODE, que rien n'ouvre (aucun
// filtre d'intention pour ce schéma). Le code reste grand, sélectionnable, et
// « Copier le code » le met dans le presse-papiers (« Code copié »).

@Composable
fun ShareProjectDialog(
    sync: SyncViewModel,
    projectName: String,
    onDismiss: () -> Unit,
    onPublished: () -> Unit = {}
) {
    val auth by sync.auth.collectAsState()
    val current by sync.currentProject.collectAsState()
    val codeNoticeState by sync.shareCodeNotice.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    /**
     * Résultat de « Publier » (SPEC-partage-moderation §5.1) : le code COURANT du
     * projet, ou un code nul pour un membre d'un projet ancien.
     */
    var published by remember { mutableStateOf<ShareCodeResult?>(null) }
    val code = published?.code
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** « Code copié » : confirmation après « Copier le code ». */
    var didCopy by remember { mutableStateOf(false) }
    /** Changement de code non demandé (§5.2) concernant le projet de cette fenêtre. */
    val codeNotice = codeNoticeState?.takeIf { it.projectId == (published?.projectId ?: current?.id) }

    // §5.2 : chez le propriétaire d'un projet partagé, le code courant est vérifié à
    // l'ouverture de la fenêtre (adoption, code disparu ou repris → nouveau code,
    // annoncé). Sans bloquer ; erreur ignorée.
    LaunchedEffect(Unit) {
        val p = sync.currentProject.value ?: return@LaunchedEffect
        if (p.ownerUid != sync.auth.value.accountOrNull?.uid) return@LaunchedEffect
        runCatching { sync.ensureCurrentCode(p.id) }
    }
    // Le message d'un changement de code n'est montré qu'une fois.
    DisposableEffect(Unit) {
        onDispose { sync.shareCodeNotice.value?.let { sync.consumeShareCodeNotice(it) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_share_project_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                codeNotice?.let {
                    Text(it.message(ctx), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("share.codeNotice"))
                }
                if (!auth.isSignedIn) {
                    Text(stringResource(R.string.sync_sign_in_to_share))
                } else if (published != null && code == null) {
                    // Projet ancien, je ne suis pas propriétaire : aucun code à montrer.
                    Text(stringResource(R.string.sync_share_code_label), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.sync_ask_owner_for_code),
                        modifier = Modifier.testTag("share.askOwner"))
                } else if (code != null) {
                    Text(stringResource(R.string.sync_share_code_label), style = MaterialTheme.typography.bodySmall)
                    Surface(shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(code, style = MaterialTheme.typography.headlineMedium
                                .copy(fontFamily = FontFamily.Monospace),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp).fillMaxWidth().testTag("share.code"))
                        }
                    }
                    Text(stringResource(R.string.sync_share_code_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.sync_publish_hint))
                    if (busy) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.heightIn(max = 18.dp))
                        Text(stringResource(R.string.sync_publishing), style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            when {
                !auth.isSignedIn -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                published != null && code == null -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                code != null -> TextButton(
                    modifier = Modifier.testTag("share.copyCode"),
                    onClick = {
                        val clipboard = ctx.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(ctx.getString(R.string.sync_share_code), code)
                        )
                        didCopy = true
                    }
                ) {
                    Icon(if (didCopy) Icons.Filled.Check else Icons.Filled.ContentCopy, contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(if (didCopy) R.string.sync_code_copied else R.string.sync_copy_code))
                }
                else -> TextButton(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            published = sync.publishCurrentProject(projectName)
                            onPublished() // pousse tout l'état local (patch/calibration/plan)
                        }
                        catch (e: Exception) { error = e.syncMessage(ctx) } finally { busy = false }
                    }
                }) { Text(stringResource(R.string.sync_publish)) }
            }
        },
        dismissButton = { if (code != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

// MARK: - Rejoindre un projet (par code)

@Composable
fun JoinProjectDialog(sync: SyncViewModel, onDismiss: () -> Unit, onJoined: (CloudProject) -> Unit) {
    val auth by sync.auth.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.join_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!auth.isSignedIn) {
                    Text(stringResource(R.string.sync_sign_in_to_join))
                } else {
                    OutlinedTextField(code, { code = it.uppercase() },
                        label = { Text(stringResource(R.string.sync_share_code)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.sync_join_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (!auth.isSignedIn) TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            else TextButton(enabled = !busy && code.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    try { val p = sync.join(code.trim()); onJoined(p); onDismiss() }
                    catch (e: Exception) { error = e.syncMessage(ctx) } finally { busy = false }
                }
            }) { Text(stringResource(R.string.sync_join)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

// MARK: - Historique des modifications (audit)

@Composable
fun HistoryDialog(
    sync: SyncViewModel,
    onUndo: ((AuditEntry) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val log by sync.auditLog.collectAsState()
    val project by sync.currentProject.collectAsState()
    val memberNames = project?.memberNames.orEmpty()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nav_history)) },
        text = {
            if (log.isEmpty()) {
                Text(stringResource(R.string.sync_history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(log) { e ->
                        // Nom DANS LE PROJET de l'auteur s'il en a un, homonymes distingués
                        // (SPEC-partage-moderation §5.14, §4).
                        AuditRow(e, ProjectNames.authorLabel(e, memberNames),
                            fmt.format(Date((e.epoch * 1000).toLong())), onUndo)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

@Composable
private fun AuditRow(e: AuditEntry, author: String, whenStr: String, onUndo: ((AuditEntry) -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${e.target} · ${e.field}", style = MaterialTheme.typography.bodyMedium)
            val old = e.oldValue.ifBlank { "—" }
            Text("$old  →  ${e.newValue}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Text(stringResource(R.string.sync_audit_by_fmt, author, whenStr),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Flèche « annuler » seulement si l'entrée porte ses coordonnées machine :
        // une entrée ancienne n'a pas de quoi rejouer sa valeur d'origine, on ne
        // lui affiche donc AUCUN affordance (plutôt qu'un bouton qui échouerait).
        if (onUndo != null && e.isUndoable) {
            IconButton(onClick = { onUndo(e) }) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.sync_undo_change))
            }
        }
    }
}

// MARK: - Pastille de statut + bannière « nouvelle version »

@Composable
fun SyncStatusBadge(sync: SyncViewModel) {
    val project by sync.currentProject.collectAsState()
    val status by sync.status.collectAsState()
    if (project == null) return
    val label = when (val s = status) {
        is SyncViewModel.SyncStatus.Uploading -> stringResource(R.string.sync_uploading_fmt, (s.progress * 100).toInt())
        is SyncViewModel.SyncStatus.Downloading -> stringResource(R.string.sync_downloading_fmt, (s.progress * 100).toInt())
        SyncViewModel.SyncStatus.Syncing -> stringResource(R.string.sync_syncing)
        is SyncViewModel.SyncStatus.Error -> stringResource(R.string.sync_error)
        else -> stringResource(R.string.sync_shared)
    }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text("☁ $label", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
fun MvrVersionBanner(sync: SyncViewModel, onReopen: (SyncViewModel.MvrVersionNotice) -> Unit) {
    val notice by sync.pendingMvrVersion.collectAsState()
    val n = notice ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sync_new_version_fmt, n.version),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onReopen(n) }) { Text(stringResource(R.string.sync_reopen)) }
            TextButton(onClick = { sync.consumePendingVersion() }) { Text(stringResource(R.string.sync_later)) }
        }
    }
}

/**
 * Bandeau d'APPARTENANCE (SPEC-partage-moderation §5.12, §6.3) : accès perdu (retiré,
 * ou projet supprimé), projet quitté, projet supprimé. Le fichier ouvert reste
 * affiché, sans mise à jour ; le bandeau se ferme d'un toucher.
 */
@Composable
fun ProjectNoticeBanner(sync: SyncViewModel) {
    val notice by sync.projectNotice.collectAsState()
    val n = notice ?: return
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.message(ctx), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).testTag("sync.projectNotice"))
            TextButton(onClick = { sync.consumeProjectNotice() }) { Text(stringResource(R.string.common_close)) }
        }
    }
}

/** Formatte une adresse pour l'affichage d'audit (u.a). */
internal fun formatAudAddress(raw: String?): String = raw?.let { DmxAddress.format(it) } ?: "—"
