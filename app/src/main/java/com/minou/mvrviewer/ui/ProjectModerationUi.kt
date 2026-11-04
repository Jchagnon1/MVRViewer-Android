package com.minou.mvrviewer.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.AppVersionInfo
import com.minou.mvrviewer.R
import com.minou.mvrviewer.sync.AccountInfo
import com.minou.mvrviewer.sync.CloudProject
import com.minou.mvrviewer.sync.ContentReport
import com.minou.mvrviewer.sync.ProjectMembers

// Partage et modération — pièces d'interface COMMUNES à la fenêtre « Partager le
// projet » (SyncUI.kt) et au menu de ligne de « Projets partagés » sur l'accueil
// (HomeScreen.kt). Contrat : MVRViewerWeb/SPEC-partage-moderation.md §6 (textes
// §6.3-6.4, e-mail §6.5) ; même comportement qu'iOS (ProjectModerationUI.swift).
// Toutes ces actions sont GRATUITES (§10, décision du 3/11/2026) : aucune garde Pro ici.

/**
 * Une action qui demande confirmation (§6.3 : boîte modale, bouton destructif en
 * rouge). « Autoriser à revenir » n'en demande pas (réversible). Textes = ressources
 * et arguments, choisis ici (calcul pur, testé sur les vrais strings.xml).
 */
sealed interface ModerationConfirmation {
    /** Nouveau code : l'ancien ne fera plus entrer personne. */
    data class NewCode(val projectId: String, val oldCode: String) : ModerationConfirmation
    /** Retirer (et bloquer) un membre ; [name] = son nom affiché (homonymes compris). */
    data class Remove(val projectId: String, val uid: String, val name: String) : ModerationConfirmation
    /** Quitter ; [heirName] non nul = je suis propriétaire (la propriété passe au plus ancien des autres membres). */
    data class Leave(val project: CloudProject, val heirName: String?) : ModerationConfirmation
    /** Supprimer le projet pour tout le monde (propriétaire). */
    data class Delete(val project: CloudProject) : ModerationConfirmation

    /** Titre : ressource + arguments ; [unnamedProject] = nom d'un projet sans nom. */
    fun title(unnamedProject: String): Pair<Int, List<Any>> = when (this) {
        is NewCode -> R.string.sync_new_code_title to emptyList()
        is Remove -> R.string.sync_remove_title_fmt to listOf(name)
        is Leave -> R.string.sync_leave_title_fmt to listOf(project.name.ifBlank { unnamedProject })
        is Delete -> R.string.sync_delete_project_title_fmt to listOf(project.name.ifBlank { unnamedProject })
    }

    val message: Pair<Int, List<Any>>
        get() = when (this) {
            is NewCode -> R.string.sync_new_code_message_fmt to listOf(oldCode)
            is Remove -> R.string.sync_remove_message_fmt to listOf(name)
            is Leave -> if (heirName != null) R.string.sync_leave_owner_message_fmt to listOf(heirName)
            else R.string.sync_leave_member_message to emptyList()
            is Delete -> R.string.sync_delete_project_message to emptyList()
        }

    /** Libellé du bouton qui confirme. */
    @get:StringRes val confirmRes: Int
        get() = when (this) {
            is NewCode -> R.string.sync_new_code_confirm
            is Remove -> R.string.sync_remove_confirm
            is Leave -> R.string.sync_leave_confirm
            is Delete -> R.string.common_delete
        }

    /** Bouton rouge (§6.3) : tout sauf « Créer un nouveau code ». */
    val isDestructive: Boolean get() = this !is NewCode

    companion object {
        /**
         * « Quitter le projet… » de [p] pour le compte [me] : propriétaire avec d'autres
         * membres → le nom de l'héritier (le plus ancien) ; membre → aucun. Null pour le
         * propriétaire SEUL (pas de « Quitter », seulement « Supprimer le projet »).
         */
        fun leave(p: CloudProject, me: String, unnamed: String): Leave? {
            if (ProjectMembers.isOwnerAlone(p, me)) return null
            val heir = if (p.ownerUid == me) ProjectMembers.heir(p, me) else null
            return Leave(p, heir?.let { ProjectMembers.label(it, p, unnamed) })
        }
    }
}

private fun Context.text(res: Pair<Int, List<Any>>): String = getString(res.first, *res.second.toTypedArray())

/** Boîte de confirmation (§6.3) : bouton de l'action (rouge s'il est destructif) et « Annuler ». */
@Composable
fun ModerationConfirmDialog(c: ModerationConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val unnamedProject = stringResource(R.string.common_unnamed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.text(c.title(unnamedProject))) },
        text = { Text(ctx.text(c.message)) },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm() },
                colors = if (c.isDestructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
                modifier = Modifier.testTag("moderation.confirm")
            ) { Text(stringResource(c.confirmRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("moderation.cancel")) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * « Signaler un contenu… » (§6.5) : ouvre la messagerie avec l'e-mail PRÉREMPLI vers
 * l'assistance — jamais envoyé par l'app (l'utilisateur l'envoie, le corrige ou
 * l'abandonne). Sans messagerie : la boîte « Aucune application de messagerie… » avec
 * « Copier l'adresse » et « Copier le signalement ». Hors ligne : marche (la messagerie
 * garde l'envoi). [Fallback] doit être posé une fois par l'écran qui s'en sert.
 */
@Stable
class ContentReporter {
    /** Signalement dont l'e-mail n'a pas pu s'ouvrir (boîte de repli affichée). */
    var fallback by mutableStateOf<ContentReport?>(null)
        private set

    /** Prépare le signalement de [p] par [account] dans la langue de l'interface, puis l'ouvre. */
    fun report(ctx: Context, p: CloudProject, account: AccountInfo) {
        val r = ContentReport.make(
            p, account, AppVersionInfo.current(ctx),
            unnamedMember = ctx.getString(R.string.sync_unnamed_member),
            unnamedProject = ctx.getString(R.string.common_unnamed)
        ) { id, args -> ctx.getString(id, *args.toTypedArray()) }
        open(ctx, r)
    }

    fun open(ctx: Context, r: ContentReport) {
        if ((ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.i("MVR_REPORT", "signalement préparé : ${r.subject} — ${r.mailto}")
        }
        // ACTION_SENDTO + mailto: (seules les messageries répondent) ; objet et corps à la
        // fois dans l'adresse (RFC 6068) et dans les extras (lus par la plupart des apps).
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(r.mailto)).apply {
            putExtra(Intent.EXTRA_SUBJECT, r.subject)
            putExtra(Intent.EXTRA_TEXT, r.body)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            fallback = r
        } catch (_: SecurityException) {
            fallback = r
        }
    }

    fun dismissFallback() { fallback = null }

    /** Boîte de repli : pas de messagerie. */
    @Composable
    fun Fallback() {
        val r = fallback ?: return
        val ctx = LocalContext.current
        fun copy(label: String, value: String) {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
        }
        AlertDialog(
            onDismissRequest = { dismissFallback() },
            text = {
                // Les deux copies l'une sous l'autre : leurs libellés ne tiennent pas sur
                // une ligne de boîte à la largeur d'un téléphone.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.sync_report_no_mail_app_fmt, ContentReport.ADDRESS),
                        modifier = Modifier.testTag("report.noMailApp").padding(bottom = 6.dp))
                    TextButton(onClick = {
                        copy(ctx.getString(R.string.sync_report_copy_address), ContentReport.ADDRESS); dismissFallback()
                    }, modifier = Modifier.testTag("report.copyAddress")) { Text(stringResource(R.string.sync_report_copy_address)) }
                    TextButton(onClick = {
                        copy(ctx.getString(R.string.sync_report_copy_report), r.clipboardText); dismissFallback()
                    }, modifier = Modifier.testTag("report.copyReport")) { Text(stringResource(R.string.sync_report_copy_report)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { dismissFallback() }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
fun rememberContentReporter(): ContentReporter = remember { ContentReporter() }

/** Pastille « Propriétaire » d'une ligne de membre : gris discret (jamais de blanc sur jaune). */
@Composable
fun OwnerBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("share.ownerBadge")
    ) {
        Text(stringResource(R.string.sync_owner_badge), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
