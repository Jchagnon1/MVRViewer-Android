package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R
import com.minou.mvrviewer.sync.SyncViewModel
import com.minou.mvrviewer.sync.syncMessage
import kotlinx.coroutines.launch

/**
 * « MOT DE PASSE OUBLIÉ » (guide de mise en vente C6) — sous-volet de Compte &
 * réglages, ouvert par le lien du formulaire de connexion (adresse préremplie).
 * Envoie l'e-mail de réinitialisation du compte, dans la langue de l'app.
 * GRATUIT (aucune garde Pro) : sans lui, un utilisateur qui a oublié son mot de
 * passe ne pouvait ni se reconnecter ni supprimer son compte.
 *
 * Le message de réussite est NEUTRE : il ne dit jamais si un compte existe pour
 * l'adresse saisie. Erreurs lisibles sous le champ : adresse invalide, hors
 * ligne, trop de demandes. Portage de PasswordResetView.swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordResetPane(
    sync: SyncViewModel,
    initialEmail: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var busy by remember { mutableStateOf(false) }
    var didSend by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun send() {
        if (email.isBlank() || busy) return
        keyboard?.hide() // la réponse s'affiche sous le bouton, pas derrière le clavier
        busy = true; error = null; didSend = false
        scope.launch {
            try {
                sync.sendPasswordReset(email)
                didSend = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.syncMessage(ctx)
            } finally {
                busy = false
            }
        }
    }

    Column(modifier.fillMaxSize().testTag("passwordReset")) {
        TopAppBar(
            title = { Text(stringResource(R.string.password_reset_title), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.password_reset_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = email,
                onValueChange = {
                    // Une autre adresse : l'ancien message ne la concerne plus.
                    email = it; didSend = false; error = null
                },
                label = { Text(stringResource(R.string.sync_email)) },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).testTag("passwordReset.email")
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("passwordReset.error").semantics { liveRegion = LiveRegionMode.Polite })
            }
            Button(
                enabled = email.isNotBlank() && !busy,
                onClick = { send() },
                colors = com.minou.mvrviewer.ui.theme.accentButtonColors(),
                modifier = Modifier.testTag("passwordReset.send")
            ) {
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.password_reset_send))
            }
            if (didSend) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.testTag("passwordReset.sent").semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    }
                ) {
                    Icon(Icons.Filled.MarkEmailRead, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.password_reset_sent), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
