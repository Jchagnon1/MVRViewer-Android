package com.minou.mvrviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import com.minou.mvrviewer.sync.ProjectMemberRow
import com.minou.mvrviewer.sync.ProjectMembers
import com.minou.mvrviewer.sync.ProjectNotice
import com.minou.mvrviewer.sync.ShareCodeChange
import com.minou.mvrviewer.sync.ShareCodeNotice
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

// MARK: - Partager le projet (publier + fiche du projet partagé)
//
// Partage PAR CODE seulement (guide de mise en vente C4, comme iOS) : l'ancien
// « Partager le lien » envoyait mvrviewer://join/CODE, que rien n'ouvre (aucun
// filtre d'intention pour ce schéma). Le code reste grand, sélectionnable, et
// « Copier le code » le met dans le presse-papiers (« Code copié »).
//
// Fichier NON partagé : « Publier » (Pro, inchangé). Projet PARTAGÉ (ou juste
// publié) : sa fiche (SPEC-partage-moderation §6.2), dans cet ordre —
//   1. « Code de partage » : le code et « Copier le code » ; propriétaire :
//      « Nouveau code… » et sa légende ; membre : « Seul le propriétaire… », ou
//      « Demandez le code au propriétaire du projet. » (projet ancien) ;
//   2. « Membres (n) » : propriétaire d'abord, puis par nom ; icône « Retirer… »
//      sur chaque autre ligne, pour le propriétaire ;
//   3. « Personnes retirées (n) » (propriétaire, s'il y en a) : « Autoriser à revenir » ;
//   4. « Signaler un contenu… » (tout membre) ;
//   5. isolés en bas, en rouge : « Quitter le projet… » (sauf propriétaire seul),
//      « Supprimer le projet… » (propriétaire).
// Ces actions sont GRATUITES (décision du 3/11/2026, §10) : seule la publication
// reste gardée Pro. Pendant une action : roue à la place du bouton, fenêtre non
// fermable. Après coup : ligne d'état (§6.3) ; départ, suppression ou accès perdu :
// le message s'affiche DANS la fenêtre (il ne revient pas en bandeau).

/** Action de modération en cours dans la fenêtre (roue sur SON bouton). */
private sealed interface ShareBusy {
    data object NewCode : ShareBusy
    data object Leave : ShareBusy
    data object Delete : ShareBusy
    data class Remove(val uid: String) : ShareBusy
    data class AllowBack(val uid: String) : ShareBusy
}

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
    val projectNoticeState by sync.projectNotice.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var publishing by remember { mutableStateOf(false) }
    /** « Code copié » : confirmation après « Copier le code ». */
    var didCopy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<ShareBusy?>(null) }
    /** Ligne d'état après une action (§6.3). */
    var status by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<ModerationConfirmation?>(null) }
    val reporter = rememberContentReporter()
    /** Vérification du code courant (adoption d'un projet ancien) en cours. */
    var checkingCode by remember { mutableStateOf(false) }
    /** Projet montré par la fenêtre : son message d'appartenance s'affiche ICI. */
    var shownProjectId by remember { mutableStateOf(sync.currentProject.value?.id) }
    var endedNotice by remember { mutableStateOf<ProjectNotice?>(null) }
    LaunchedEffect(current?.id) { current?.id?.let { shownProjectId = it } }
    // Quitté, supprimé, accès perdu : le message est pris par la fenêtre (pas de bandeau ensuite).
    LaunchedEffect(projectNoticeState, shownProjectId) {
        val n = projectNoticeState ?: return@LaunchedEffect
        if (n.projectId == shownProjectId) { endedNotice = n; sync.consumeProjectNotice() }
    }
    val ended = endedNotice ?: projectNoticeState?.takeIf { it.projectId == shownProjectId }
    /** Changement de code non demandé (§5.2) concernant le projet de cette fenêtre. */
    val codeNotice = codeNoticeState?.takeIf { it.projectId == current?.id }

    // §5.2 : chez le propriétaire d'un projet partagé, le code courant est vérifié à
    // l'ouverture de la fenêtre (adoption, code disparu ou repris → nouveau code,
    // annoncé). Sans bloquer ; un projet ancien sans code montre la roue pendant
    // l'adoption et l'erreur si elle échoue (hors ligne) ; sinon erreur ignorée.
    LaunchedEffect(Unit) {
        val p = sync.currentProject.value ?: return@LaunchedEffect
        if (p.ownerUid != sync.auth.value.accountOrNull?.uid) return@LaunchedEffect
        val adopting = p.inviteCode == null
        if (adopting) checkingCode = true
        try {
            sync.ensureCurrentCode(p.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (adopting) error = e.syncMessage(ctx)
        } finally {
            checkingCode = false
        }
    }
    // Le message d'un changement de code n'est montré qu'une fois.
    DisposableEffect(Unit) {
        onDispose { sync.shareCodeNotice.value?.let { sync.consumeShareCodeNotice(it) } }
    }

    /** Lance une action : roue sur son bouton, fenêtre non fermable ; à la fin, la ligne d'état ou l'erreur lisible (§7). */
    fun run(kind: ShareBusy, action: suspend () -> String?) {
        if (busy != null) return
        busy = kind; error = null; status = null
        scope.launch {
            try { status = action() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.syncMessage(ctx) }
            finally { busy = null }
        }
    }
    /** Nouveau code (§5.2) ; [adopting] : projet ancien sans code courant. */
    fun rotateCode(projectId: String, adopting: Boolean) = run(ShareBusy.NewCode) {
        val code = sync.rotateInviteCode(projectId)
        ShareCodeNotice(projectId, code, if (adopting) ShareCodeChange.ADOPTED else ShareCodeChange.REPLACED).message(ctx)
    }
    fun perform(c: ModerationConfirmation) {
        when (c) {
            is ModerationConfirmation.NewCode -> rotateCode(c.projectId, adopting = false)
            is ModerationConfirmation.Remove -> run(ShareBusy.Remove(c.uid)) {
                sync.removeMember(c.uid, c.projectId)
                ctx.getString(R.string.sync_removed_status_fmt, c.name)
            }
            is ModerationConfirmation.Leave -> run(ShareBusy.Leave) { sync.leaveProject(c.project); null }
            is ModerationConfirmation.Delete -> run(ShareBusy.Delete) { sync.deleteProject(c.project); null }
        }
    }

    val account = auth.accountOrNull
    val project = current
    val idle = busy == null
    AlertDialog(
        onDismissRequest = { if (idle) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = idle, dismissOnClickOutside = idle),
        title = { Text(stringResource(R.string.sync_share_project_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    ended != null -> Text(ended.message(ctx), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("share.ended"))
                    account == null -> Text(stringResource(R.string.sync_sign_in_to_share))
                    project == null -> {
                        Text(stringResource(R.string.sync_publish_hint))
                        if (publishing) Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.heightIn(max = 18.dp))
                            Text(stringResource(R.string.sync_publishing), style = MaterialTheme.typography.bodySmall)
                        }
                        error?.let { ShareError(it) }
                    }
                    else -> {
                        val me = account.uid
                        val isOwner = project.ownerUid == me
                        codeNotice?.let {
                            Text(it.message(ctx), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("share.codeNotice"))
                        }
                        status?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("share.status"))
                        }
                        error?.let { ShareError(it) }
                        // 1. Code de partage.
                        ShareSectionTitle(stringResource(R.string.sync_share_code))
                        val code = project.inviteCode
                        if (code != null) {
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
                            TextButton(
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
                        } else if (isOwner) {
                            // Projet ancien : le code courant se pose à l'ouverture (§5.2).
                            if (checkingCode) CircularProgressIndicator(strokeWidth = 2.dp,
                                modifier = Modifier.heightIn(max = 24.dp).align(Alignment.CenterHorizontally))
                        } else {
                            // Projet ancien, je ne suis pas propriétaire : aucun code à montrer.
                            Text(stringResource(R.string.sync_ask_owner_for_code),
                                modifier = Modifier.testTag("share.askOwner"))
                        }
                        if (isOwner) {
                            BusyTextButton(busy == ShareBusy.NewCode, enabled = idle && !checkingCode, tag = "share.newCode",
                                onClick = {
                                    if (code != null) confirmation = ModerationConfirmation.NewCode(project.id, code)
                                    else rotateCode(project.id, adopting = true)
                                }) {
                                Icon(Icons.Filled.Autorenew, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text(stringResource(R.string.sync_new_code))
                            }
                        }
                        Text(stringResource(if (isOwner) R.string.sync_share_owner_legend else R.string.sync_share_member_legend),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(if (isOwner) "share.ownerLegend" else "share.memberLegend"))
                        // 2. Membres.
                        ShareMembers(project, me, isOwner, busy, idle) { uid, name ->
                            confirmation = ModerationConfirmation.Remove(project.id, uid, name)
                        }
                        // 3. Personnes retirées (table locale de l'appareil, §5.5).
                        if (isOwner) ShareRemoved(project, sync.removedMemberNames(project.id), busy, idle) { row ->
                            run(ShareBusy.AllowBack(row.uid)) {
                                sync.allowBack(row.uid, project.id)
                                ctx.getString(R.string.sync_allowed_back_fmt, row.label)
                            }
                        }
                        // 4. Signaler un contenu (tout membre, gratuit, aucune écriture).
                        HorizontalDivider()
                        TextButton(enabled = idle, onClick = { reporter.report(ctx, project, account) },
                            modifier = Modifier.testTag("share.report")) {
                            Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.sync_report_content))
                        }
                        // 5. Actions DESTRUCTRICES isolées en bas, en rouge (texte ET icône).
                        val leave = ModerationConfirmation.leave(project, me, stringResource(R.string.sync_unnamed_member))
                        if (leave != null || isOwner) HorizontalDivider()
                        if (leave != null) {
                            BusyTextButton(busy == ShareBusy.Leave, enabled = idle, tag = "share.leave", destructive = true,
                                onClick = { confirmation = leave }) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text(stringResource(R.string.sync_leave_project))
                            }
                        }
                        if (isOwner) {
                            BusyTextButton(busy == ShareBusy.Delete, enabled = idle, tag = "share.delete", destructive = true,
                                onClick = { confirmation = ModerationConfirmation.Delete(project) }) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text(stringResource(R.string.sync_delete_project))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (ended == null && account != null && project == null) {
                TextButton(enabled = !publishing, modifier = Modifier.testTag("share.publish"), onClick = {
                    // Partager = ouvrir SON projet à l'équipe : édition, Pro (inchangé).
                    // Le paywall s'ouvre de lui-même ; aucune erreur à afficher.
                    if (!com.minou.mvrviewer.ui.ProAccess.gate("partager un projet") {}) return@TextButton
                    publishing = true; error = null
                    scope.launch {
                        try {
                            sync.publishCurrentProject(projectName)
                            onPublished() // pousse tout l'état local (patch/calibration/plan)
                        }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = e.syncMessage(ctx) } finally { publishing = false }
                    }
                }) { Text(stringResource(R.string.sync_publish)) }
            } else {
                TextButton(enabled = idle, onClick = onDismiss, modifier = Modifier.testTag("share.close")) {
                    Text(stringResource(R.string.common_close))
                }
            }
        },
        dismissButton = {
            if (ended == null && account != null && project == null) {
                TextButton(enabled = !publishing, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
    confirmation?.let { c -> ModerationConfirmDialog(c, onConfirm = { perform(c) }, onDismiss = { confirmation = null }) }
    reporter.Fallback()
}

@Composable
private fun ShareSectionTitle(text: String, tag: String? = null) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
private fun ShareError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("share.error"))
}

/** Bouton de la fenêtre, ou une roue pendant SON action ; [destructive] : rouge. */
@Composable
private fun BusyTextButton(
    spinning: Boolean, enabled: Boolean, tag: String, destructive: Boolean = false,
    onClick: () -> Unit, content: @Composable RowScope.() -> Unit
) {
    if (spinning) {
        val label = stringResource(R.string.sync_moderation_progress)
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp)
                .semantics { contentDescription = label }.testTag("$tag.busy"))
        }
    } else {
        TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag),
            colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.textButtonColors(), content = content)
    }
}

/** « Membres (n) » : propriétaire d'abord, puis par nom ; « Retirer… » pour le propriétaire. */
@Composable
private fun ShareMembers(
    project: CloudProject, me: String, isOwner: Boolean, busy: ShareBusy?, idle: Boolean,
    onRemove: (uid: String, name: String) -> Unit
) {
    val rows = ProjectMembers.rows(project, me, stringResource(R.string.sync_unnamed_member))
    ShareSectionTitle(pluralStringResource(R.plurals.sync_members_header, rows.size, rows.size), tag = "share.membersHeader")
    for (row in rows) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("share.member.${row.uid}"),
            verticalAlignment = Alignment.CenterVertically) {
            // Nom puis pastille, sur toute la largeur restante : le nom ne passe à la ligne
            // que s'il ne tient pas à côté de la pastille.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (row.isMe) stringResource(R.string.sync_member_you_fmt, row.label) else row.label,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (row.isOwner) OwnerBadge()
            }
            if (isOwner && !row.isMe) {
                if (busy == ShareBusy.Remove(row.uid)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    val cd = stringResource(R.string.sync_remove_member_cd_fmt, row.label)
                    IconButton(enabled = idle, onClick = { onRemove(row.uid, row.label) },
                        modifier = Modifier.testTag("share.remove.${row.uid}")) {
                        Icon(Icons.Filled.PersonRemove, contentDescription = cd,
                            tint = if (idle) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** « Personnes retirées (n) » (propriétaire, seulement s'il y en a) : « Autoriser à revenir ». */
@Composable
private fun ShareRemoved(
    project: CloudProject, localNames: Map<String, String>, busy: ShareBusy?, idle: Boolean,
    onAllowBack: (ProjectMemberRow) -> Unit
) {
    val rows = ProjectMembers.removedRows(project, localNames, stringResource(R.string.sync_unnamed_member))
    if (rows.isEmpty()) return
    ShareSectionTitle(pluralStringResource(R.plurals.sync_removed_header, rows.size, rows.size), tag = "share.removedHeader")
    for (row in rows) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (busy == ShareBusy.AllowBack(row.uid)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                TextButton(enabled = idle, onClick = { onAllowBack(row) }, modifier = Modifier.testTag("share.allowBack.${row.uid}")) {
                    Text(stringResource(R.string.sync_allow_back))
                }
            }
        }
    }
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
    val memberUids = project?.memberUids.orEmpty()
    val memberNames = project?.memberNames.orEmpty()
    val unnamed = stringResource(R.string.sync_unnamed_member)
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
                        // Nom DANS LE PROJET de l'auteur s'il en a un, découpé, homonymes
                        // distingués comme dans la liste des membres (§5.14, §4).
                        AuditRow(e, ProjectNames.authorLabel(e, memberUids, memberNames, unnamed),
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
