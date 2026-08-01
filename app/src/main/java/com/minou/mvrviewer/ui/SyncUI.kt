package com.minou.mvrviewer.ui

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.DmxAddress
import com.minou.mvrviewer.sync.AuditEntry
import com.minou.mvrviewer.sync.CloudProject
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

// MARK: - Compte (connexion / inscription / déconnexion)

@Composable
fun AccountDialog(sync: SyncViewModel, onDismiss: () -> Unit) {
    val auth by sync.auth.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(auth.accountOrNull?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (auth.isSignedIn) R.string.nav_account else if (isSignUp) R.string.sync_create_account else R.string.sync_sign_in_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val account = auth.accountOrNull
                if (account != null) {
                    Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(account.email, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.sync_backend_fmt, sync.backendLabel), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.sync_email)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.sync_password)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    if (isSignUp) {
                        OutlinedTextField(displayName, { displayName = it },
                            label = { Text(stringResource(R.string.sync_display_name)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.sync_backend_fmt, sync.backendLabel), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val account = auth.accountOrNull
            if (account != null) {
                TextButton(onClick = { sync.signOut(); onDismiss() }) { Text(stringResource(R.string.sync_sign_out)) }
            } else {
                TextButton(enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            if (isSignUp) sync.signUp(email.trim(), password, displayName.trim())
                            else sync.signIn(email.trim(), password)
                            onDismiss()
                        } catch (e: Exception) { error = e.syncMessage(ctx) } finally { busy = false }
                    }
                }) { Text(stringResource(if (isSignUp) R.string.common_create else R.string.home_sign_in)) }
            }
        },
        dismissButton = {
            if (auth.accountOrNull == null) {
                TextButton(onClick = { isSignUp = !isSignUp; error = null }) {
                    Text(stringResource(if (isSignUp) R.string.sync_have_account else R.string.sync_create_account))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        }
    )
}

// MARK: - Partager le projet (publier + code + lien)

@Composable
fun ShareProjectDialog(
    sync: SyncViewModel,
    projectName: String,
    onDismiss: () -> Unit,
    onPublished: () -> Unit = {}
) {
    val auth by sync.auth.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_share_project_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!auth.isSignedIn) {
                    Text(stringResource(R.string.sync_sign_in_to_share))
                } else if (code != null) {
                    Text(stringResource(R.string.sync_share_code_label), style = MaterialTheme.typography.bodySmall)
                    Surface(shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(code!!, style = MaterialTheme.typography.headlineMedium
                            .copy(fontFamily = FontFamily.Monospace),
                            textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp).fillMaxWidth())
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
                code != null -> TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT,
                            ctx.getString(R.string.sync_share_message_fmt, code, code))
                    }
                    ctx.startActivity(Intent.createChooser(share, ctx.getString(R.string.sync_share_code_chooser)))
                }) { Text(stringResource(R.string.sync_share_link)) }
                else -> TextButton(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            code = sync.publishCurrentProject(projectName).code
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
                        AuditRow(e, fmt.format(Date((e.epoch * 1000).toLong())), onUndo)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

@Composable
private fun AuditRow(e: AuditEntry, whenStr: String, onUndo: ((AuditEntry) -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${e.target} · ${e.field}", style = MaterialTheme.typography.bodyMedium)
            val old = e.oldValue.ifBlank { "—" }
            Text("$old  →  ${e.newValue}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Text(stringResource(R.string.sync_audit_by_fmt, e.authorName, whenStr),
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

/** Formatte une adresse pour l'affichage d'audit (u.a). */
internal fun formatAudAddress(raw: String?): String = raw?.let { DmxAddress.format(it) } ?: "—"
