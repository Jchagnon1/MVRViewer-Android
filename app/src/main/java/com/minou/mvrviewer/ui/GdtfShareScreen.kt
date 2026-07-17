package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.GdtfShareClient
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modèles GDTF téléchargés depuis GDTF Share, PAR SPEC MVR, appliqués par-dessus
 * les .gdtf embarqués (comme les gdtfOverrides iOS). `version` déclenche la
 * reconstruction de la 3D quand un modèle change.
 */
class GdtfOverrides {
    val map: SnapshotStateMap<String, ByteArray> = mutableStateMapOf()
    var version by mutableIntStateOf(0)
    fun set(spec: String, bytes: ByteArray) { map[spec] = bytes; version++ }
}

/**
 * Écran GDTF Share : connexion (compte gratuit gdtf-share.com) puis
 * « améliorer les modèles » — pour chaque type de projecteur, récupère le
 * meilleur profil fabricant et remplace le modèle 3D. Portage de
 * GDTFShareResolver + GDTFShareSettingsView (iOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GdtfShareScreen(
    scene: MvrScene,
    mvrBytes: ByteArray,
    overrides: GdtfOverrides,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(GdtfShareClient.loggedIn) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("GDTF Share", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour 3D")
                }
            }
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (!loggedIn) {
                Text(
                    "Connecte-toi avec ton compte GDTF Share (gratuit, gdtf-share.com) pour " +
                        "remplacer les modèles 3D des projecteurs par les profils fabricants.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, label = { Text("Identifiant / email") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, label = { Text("Mot de passe") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true; status = "Connexion…"
                            runCatching { GdtfShareClient.login(user.trim(), pass) }.fold(
                                onSuccess = { loggedIn = true; status = "Connecté." },
                                onFailure = { status = it.message ?: "Échec de connexion." }
                            )
                            busy = false
                        }
                    },
                    enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Se connecter") }
            } else {
                Text("Connecté à GDTF Share.", style = MaterialTheme.typography.titleSmall)
                Text(
                    "« Améliorer les modèles » télécharge, pour chaque type de projecteur du show, " +
                        "le meilleur profil fabricant (par FixtureTypeID, sinon fabricant + nom) et remplace " +
                        "le modèle 3D. Reviens en vue 3D pour voir le résultat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val specs = scene.fixtures.mapNotNull { it.gdtfSpec?.trim()?.ifEmpty { null } }.toSet()
                            var done = 0; var applied = 0
                            for (spec in specs) {
                                status = "Résolution ${done + 1}/${specs.size}…"
                                val bytes = withContext(Dispatchers.IO) {
                                    val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
                                    val embedded = cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
                                        ?: return@withContext null
                                    val identity = GdtfShareClient.identity(embedded) ?: return@withContext null
                                    runCatching { GdtfShareClient.downloadBest(identity) }.getOrNull()
                                }
                                if (bytes != null) { overrides.set(spec, bytes); applied++ }
                                done++
                            }
                            status = "$applied / ${specs.size} type(s) remplacé(s). Ouvre la vue 3D."
                            busy = false
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()
                ) { Text("Améliorer les modèles des projecteurs") }
                OutlinedButton(
                    onClick = { GdtfShareClient.logout(); loggedIn = false; status = "" },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Se déconnecter") }
            }

            if (busy) {
                Row3(status)
            } else if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun Row3(status: String) {
    Column(Modifier.padding(top = 16.dp)) {
        CircularProgressIndicator()
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
}
