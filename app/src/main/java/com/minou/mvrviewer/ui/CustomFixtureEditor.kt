package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.minou.mvrviewer.mvr.GdtfModes
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.sync.CustomFixtureTypeDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ÉDITEUR RAPIDE de type custom (projecteurs custom V1) : nom → GDTF de base
 * (géométrie 3D) → liste de canaux réécrits (ajout / renommer / réordonner /
 * supprimer) + footprint réglable, avec option « pré-remplir depuis le mode du
 * GDTF de base ». Produit un [CustomFixtureTypeDTO] (contrat FIGÉ) via `onSave`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFixtureEditorSheet(
    initial: CustomFixtureTypeDTO?,
    scene: MvrScene,
    mvrBytes: ByteArray,
    gdtfOverrides: GdtfOverrides,
    defaultBaseSpec: String?,
    onSave: (CustomFixtureTypeDTO) -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val id = remember(initial) { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var baseSpec by remember(initial) {
        mutableStateOf(initial?.baseGdtfSpec?.takeIf { it.isNotBlank() } ?: defaultBaseSpec)
    }
    val channels: SnapshotStateList<String> = remember(initial) {
        (initial?.channels ?: emptyList()).toMutableStateList()
    }
    var footprintText by remember(initial) {
        mutableStateOf((initial?.footprint ?: initial?.channels?.size ?: 0).let { if (it > 0) it.toString() else "" })
    }
    var specMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Types GDTF présents dans la scène (base de géométrie possible).
    val specChoices = remember(scene) {
        scene.allObjects.mapNotNull { it.gdtfSpec?.trim()?.ifEmpty { null } }.distinct().sorted()
    }

    fun footprintOrDefault(): Int =
        footprintText.trim().toIntOrNull()?.coerceAtLeast(1) ?: channels.size.coerceAtLeast(1)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                if (initial == null) "Nouveau type custom" else "Éditer le type",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nom du type") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )

            // GDTF de base (fournit la géométrie 3D).
            Text("GDTF de base (géométrie 3D)", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 4.dp))
            Box {
                OutlinedButton(onClick = { specMenu = true }, enabled = specChoices.isNotEmpty()) {
                    Text(baseSpec ?: if (specChoices.isEmpty()) "Aucun GDTF dans la scène" else "Choisir…")
                }
                DropdownMenu(expanded = specMenu, onDismissRequest = { specMenu = false }) {
                    specChoices.forEach { s ->
                        DropdownMenuItem(text = { Text(s) }, onClick = { baseSpec = s; specMenu = false })
                    }
                }
            }

            // Pré-remplir les canaux depuis le mode du GDTF de base.
            OutlinedButton(
                onClick = {
                    val spec = baseSpec ?: return@OutlinedButton
                    busy = true
                    scope.launch {
                        val list = withContext(Dispatchers.IO) { baseModeChannelNames(spec, mvrBytes, gdtfOverrides) }
                        if (list.isNotEmpty()) {
                            channels.clear(); channels.addAll(list); footprintText = list.size.toString()
                        }
                        busy = false
                    }
                },
                enabled = baseSpec != null && !busy,
                modifier = Modifier.padding(top = 8.dp)
            ) { Text(if (busy) "Lecture du GDTF…" else "Pré-remplir depuis le mode GDTF") }

            // Liste de canaux : renommer / réordonner / supprimer.
            Text("Canaux (${channels.size})", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            HorizontalDivider()
            channels.forEachIndexed { i, ch ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${i + 1}", modifier = Modifier.width(28.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = ch, onValueChange = { channels[i] = it },
                        singleLine = true, label = { Text("Canal ${i + 1}") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { if (i > 0) { val t = channels[i - 1]; channels[i - 1] = channels[i]; channels[i] = t } },
                        enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Monter")
                    }
                    IconButton(onClick = { if (i < channels.size - 1) { val t = channels[i + 1]; channels[i + 1] = channels[i]; channels[i] = t } },
                        enabled = i < channels.size - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descendre")
                    }
                    IconButton(onClick = {
                        channels.removeAt(i)
                        // Le footprint suit le nb de canaux tant qu'il n'a pas été forcé
                        // à une valeur différente (repli lisible).
                        if (footprintText.trim().toIntOrNull() == channels.size + 1) footprintText = channels.size.toString()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                    }
                }
            }
            OutlinedButton(
                onClick = { channels.add(""); footprintText = channels.size.toString() },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp)); Text("Ajouter un canal")
            }

            // Footprint réglable (nb de canaux occupés ; défaut = nb de canaux).
            OutlinedTextField(
                value = footprintText,
                onValueChange = { new -> footprintText = new.filter { it.isDigit() } },
                label = { Text("Empreinte DMX (nb de canaux)") },
                placeholder = { Text(channels.size.coerceAtLeast(1).toString()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Défaut : nb de canaux. Réglable ≥ 1.") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    TextButton(onClick = { onDelete(id) }) {
                        Text("Supprimer", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = name.isNotBlank() && !baseSpec.isNullOrBlank(),
                    onClick = {
                        onSave(
                            CustomFixtureTypeDTO(
                                id = id,
                                name = name.trim(),
                                baseGdtfSpec = baseSpec.orEmpty(),
                                channels = channels.toList(),
                                footprint = footprintOrDefault()
                            )
                        )
                    }
                ) { Text("Enregistrer le type") }
            }
        }
    }
}

/**
 * Noms de canaux du PREMIER mode d'un GDTF de base, ordonnés par offset (repli ""
 * pour les offsets non couverts). Sert au pré-remplissage de l'éditeur. Hors-main.
 */
private fun baseModeChannelNames(
    spec: String, mvrBytes: ByteArray, gdtfOverrides: GdtfOverrides
): List<String> {
    val bytes = gdtfOverrides.map[spec] ?: run {
        val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
        cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
    } ?: return emptyList()
    val modes = runCatching { GdtfModes.parse(bytes) }.getOrDefault(emptyList())
    val mode = modes.firstOrNull() ?: return emptyList()
    val fp = maxOf(1, mode.footprint)
    val arr = MutableList(fp) { "" }
    for (ch in mode.channels) for (off in ch.offsets) if (off in 1..fp) arr[off - 1] = ch.attribute
    return arr
}
