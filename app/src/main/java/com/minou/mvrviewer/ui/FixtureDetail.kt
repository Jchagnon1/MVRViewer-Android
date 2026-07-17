package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.minou.mvrviewer.mvr.DmxMode
import com.minou.mvrviewer.mvr.GdtfModes
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrSceneObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Modification de patch d'un projecteur (ID / adresse DMX / mode). */
data class PatchEdit(val fixtureId: String?, val address: String?, val modeName: String?)

/**
 * Modifications de patch en mémoire, appliquées PAR-DESSUS le MVR d'origine
 * (comme les overrides iOS). Clé = uuid du projecteur (repli nom|calque).
 */
class PatchOverrides {
    val edits: SnapshotStateMap<String, PatchEdit> = mutableStateMapOf()
    private fun key(o: MvrSceneObject) = o.uuid ?: "${o.name}|${o.layerName}"
    fun effectiveId(o: MvrSceneObject) = edits[key(o)]?.fixtureId ?: o.fixtureId
    fun effectiveAddress(o: MvrSceneObject) = edits[key(o)]?.address ?: o.addresses.firstOrNull()
    fun effectiveMode(o: MvrSceneObject) = edits[key(o)]?.modeName ?: o.gdtfMode
    fun isEdited(o: MvrSceneObject) = edits.containsKey(key(o))
    fun set(o: MvrSceneObject, id: String?, address: String?, mode: String?) {
        edits[key(o)] = PatchEdit(id?.ifBlank { null }, address?.ifBlank { null }, mode)
    }
}

/**
 * Fiche projecteur : édition ID / adresse DMX / mode + détail des canaux du
 * mode sélectionné (parsé du .gdtf embarqué). Équivalent PatchEditView +
 * DMXModeSummaryView iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixtureDetailSheet(
    fixture: MvrSceneObject,
    mvrBytes: ByteArray,
    overrides: PatchOverrides,
    onDismiss: () -> Unit
) {
    val modes by produceState(initialValue = emptyList<DmxMode>(), fixture) {
        value = withContext(Dispatchers.IO) {
            val spec = fixture.gdtfSpec ?: return@withContext emptyList()
            val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
            val gd = cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
                ?: return@withContext emptyList()
            runCatching { GdtfModes.parse(gd) }.getOrDefault(emptyList())
        }
    }

    var id by remember(fixture) { mutableStateOf(overrides.effectiveId(fixture) ?: "") }
    var addr by remember(fixture) {
        mutableStateOf(overrides.effectiveAddress(fixture)?.let { com.minou.mvrviewer.mvr.DmxAddress.format(it) } ?: "")
    }
    var modeName by remember(fixture) { mutableStateOf(overrides.effectiveMode(fixture)) }
    var modeMenu by remember { mutableStateOf(false) }

    val selectedMode = modes.firstOrNull { it.name == modeName } ?: modes.firstOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(fixture.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${fixture.gdtfSpec ?: "—"} · ${fixture.layerName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = id, onValueChange = { id = it }, label = { Text("Fixture ID") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = addr, onValueChange = { addr = it }, label = { Text("Adresse DMX") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
            }

            // Choix du mode.
            Text("Mode", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            OutlinedButton(onClick = { modeMenu = true }, enabled = modes.isNotEmpty()) {
                Text(
                    when {
                        modes.isEmpty() -> "Modes indisponibles"
                        selectedMode != null -> "${selectedMode.name}  ·  ${selectedMode.footprint} canaux"
                        else -> "Choisir…"
                    }
                )
            }
            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                modes.forEach { m ->
                    DropdownMenuItem(
                        text = { Text("${m.name}  ·  ${m.footprint} canaux") },
                        onClick = { modeName = m.name; modeMenu = false }
                    )
                }
            }

            // Détail des canaux du mode sélectionné.
            if (selectedMode != null && selectedMode.channels.isNotEmpty()) {
                Text(
                    "Canaux (${selectedMode.channels.size})",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(selectedMode.channels) { ch ->
                        val addrOff = ch.offsets.joinToString("+") { it.toString() }.ifEmpty { "virtuel" }
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(ch.attribute, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "offset $addrOff" + (ch.defaultValue?.let { " · défaut $it" } ?: "") +
                                    (ch.functions.firstOrNull()?.let { " · ${it.name}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }

            Button(
                onClick = { overrides.set(fixture, id, addr, modeName); onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("Enregistrer le patch") }
        }
    }
}
