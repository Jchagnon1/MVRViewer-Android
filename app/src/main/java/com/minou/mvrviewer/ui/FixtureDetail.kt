package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
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
import com.minou.mvrviewer.mvr.GdtfPower
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrSceneObject
import com.minou.mvrviewer.sync.PowerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Modification de patch d'un projecteur (ID / adresse DMX / mode). */
data class PatchEdit(val fixtureId: String?, val address: String?, val modeName: String?)

/**
 * Une adresse DMX s'écrit « univers.adresse » : QUE des chiffres et UN seul
 * point. On filtre la saisie (des lettres n'ont aucun sens ici) et on convertit
 * la virgule — le clavier décimal français en produit une.
 */
internal fun sanitizeDmxAddress(raw: String): String {
    val sb = StringBuilder()
    var dotUsed = false
    for (ch in raw) {
        when {
            ch.isDigit() -> sb.append(ch)
            (ch == '.' || ch == ',') && !dotUsed && sb.isNotEmpty() -> { sb.append('.'); dotUsed = true }
        }
    }
    return sb.toString()
}

/** Vide ou en cours de frappe = accepté ; sinon univers ≥ 1 et adresse 1..512. */
internal fun isPlausibleDmxAddress(s: String): Boolean {
    if (s.isBlank() || s.endsWith(".")) return true          // saisie en cours
    val parts = s.split(".")
    return when (parts.size) {
        1 -> (parts[0].toIntOrNull() ?: 0) >= 1               // adresse absolue
        2 -> {
            val u = parts[0].toIntOrNull(); val a = parts[1].toIntOrNull()
            u != null && a != null && u >= 1 && a in 1..512
        }
        else -> false
    }
}

/** Plages de valeurs DMX d'un canal : ChannelSet nommés (« 0–7 Fermé »), sinon
 *  plage physique continue (Pan/Tilt/Dimmer). Miroir de displayRanges iOS. */
private fun channelRanges(ch: com.minou.mvrviewer.mvr.DmxChannel): List<kotlin.Pair<String, String>> {
    val sets = ch.functions.flatMap { it.channelSets }.sortedBy { it.dmxFrom }
    if (sets.isNotEmpty()) {
        return sets.mapIndexed { i, s ->
            val to = if (i + 1 < sets.size) sets[i + 1].dmxFrom - 1 else 255
            "${s.dmxFrom}–$to" to s.name
        }
    }
    val f = ch.functions.firstOrNull()
    if (f?.physicalFrom != null && f.physicalTo != null) {
        return listOf("0–255" to "${fmtPhys(f.physicalFrom)} → ${fmtPhys(f.physicalTo)}")
    }
    return emptyList()
}

private fun fmtPhys(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else "%.2f".format(v)

/**
 * Modifications de patch en mémoire, appliquées PAR-DESSUS le MVR d'origine
 * (comme les overrides iOS). Clé = uuid du projecteur (repli nom|calque).
 *
 * SYNCHRO : `onCommit` est appelé après une édition UTILISATEUR (pas les
 * applications distantes), avec l'ancienne et la nouvelle valeur → sert à
 * journaliser (audit qui/quoi/avant→après), persister et pousser au cloud.
 */
class PatchOverrides {
    val edits: SnapshotStateMap<String, PatchEdit> = mutableStateMapOf()
    /** Bumpé à chaque édition (utilisateur OU distante) — pour re-déclencher un effet. */
    var version by mutableStateOf(0)
        private set
    /** Hook de commit UTILISATEUR : (projecteur, ancien édit effectif, nouvel édit). */
    var onCommit: ((MvrSceneObject, PatchEdit, PatchEdit) -> Unit)? = null

    /** Même identité que le masquage et le fil de fer (cf. mvrInstanceKey). */
    fun key(o: MvrSceneObject) = mvrInstanceKey(o)
    fun effectiveId(o: MvrSceneObject) = edits[key(o)]?.fixtureId ?: o.fixtureId
    fun effectiveAddress(o: MvrSceneObject) = edits[key(o)]?.address ?: o.addresses.firstOrNull()
    fun effectiveMode(o: MvrSceneObject) = edits[key(o)]?.modeName ?: o.gdtfMode
    fun isEdited(o: MvrSceneObject) = edits.containsKey(key(o))

    fun set(o: MvrSceneObject, id: String?, address: String?, mode: String?) {
        val old = PatchEdit(effectiveId(o), effectiveAddress(o), effectiveMode(o))
        val new = PatchEdit(id?.ifBlank { null }, address?.ifBlank { null }, mode)
        edits[key(o)] = new
        version++
        onCommit?.invoke(o, old, new)
    }

    /** État persistable (clé → édit) pour PatchStore / mapping cloud. */
    fun toPersistedList(): List<com.minou.mvrviewer.sync.PersistedPatchEdit> =
        edits.map { (k, e) ->
            com.minou.mvrviewer.sync.PersistedPatchEdit(k, e.fixtureId, e.address, e.modeName)
        }

    /** Applique des édits (restauration disque OU distant) SANS déclencher onCommit. */
    fun applyPersisted(list: List<com.minou.mvrviewer.sync.PersistedPatchEdit>) {
        for (e in list) edits[e.key] = PatchEdit(e.fixtureId, e.address, e.modeName)
        version++
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
    power: PowerLibraryState,
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

    // Puissance extraite du GDTF pour CE type si elle n'est pas déjà en cache
    // (repli robuste : SceneScreen remplit déjà toutes les specs à l'ouverture,
    // mais la fiche reste correcte même ouverte avant la fin de l'extraction).
    LaunchedEffect(fixture) {
        val spec = fixture.gdtfSpec ?: return@LaunchedEffect
        if (power.gdtfWatts(spec) != null) return@LaunchedEffect
        val w = withContext(Dispatchers.IO) {
            val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
            val gd = cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
                ?: return@withContext null
            runCatching { GdtfPower.maxWatts(gd) }.getOrNull()
        }
        if (w != null) power.setGdtf(spec, w)
    }
    // Puissance effective (biblio prioritaire, sinon GDTF) + provenance.
    val resolution = power.effective(fixture.gdtfSpec)
    var wattsText by remember(fixture) { mutableStateOf(resolution.watts?.toString() ?: "") }
    // Pré-remplissage QUAND la valeur arrive (extraction/cloud asynchrones) : on
    // ne remplace pas une saisie en cours — seulement un champ resté vide.
    LaunchedEffect(resolution.watts) {
        if (wattsText.isBlank() && resolution.watts != null) wattsText = resolution.watts.toString()
    }

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
                    value = addr,
                    onValueChange = { addr = sanitizeDmxAddress(it) },
                    label = { Text("Adresse DMX") },
                    placeholder = { Text("univers.adresse") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !isPlausibleDmxAddress(addr),
                    supportingText = if (!isPlausibleDmxAddress(addr)) {
                        { Text("Format : univers.adresse (1–512)") }
                    } else null,
                    modifier = Modifier.weight(1f)
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
                                    (ch.geometry?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Plages de valeurs DMX nommées (ChannelSet) ou plage
                            // physique continue — comme DMXModeSummaryView iOS.
                            val ranges = channelRanges(ch)
                            ranges.take(10).forEach { (range, label) ->
                                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                                    Text(range, modifier = Modifier.width(66.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(label, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                            if (ranges.size > 10) Text("  +${ranges.size - 10}…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }

            // ---- Consommation électrique (outil de câblage, phase 1) ----------
            // Puissance MAX du type : lue du GDTF si présente, sinon saisie à la
            // main. Une saisie est mémorisée dans la BIBLIOTHÈQUE PARTAGÉE (clé =
            // spec) → jamais à ressaisir, pour tous les projets et utilisateurs.
            if (fixture.gdtfSpec != null) {
                val wattsInt = wattsText.trim().toIntOrNull()
                val wattsOk = wattsText.isBlank() || (wattsInt != null && wattsInt > 0)
                Text("Consommation", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                OutlinedTextField(
                    value = wattsText,
                    onValueChange = { new -> wattsText = new.filter { it.isDigit() } },
                    label = { Text("Consommation max (W)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !wattsOk,
                    supportingText = {
                        Text(
                            when (resolution.source) {
                                PowerSource.LIBRARY -> "Source : saisie (bibliothèque partagée)"
                                PowerSource.GDTF -> "Source : GDTF (modifiable)"
                                PowerSource.NONE -> "Aucune valeur GDTF — à saisir"
                            },
                            color = if (resolution.source == PowerSource.NONE)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Adresse vide (= inchangée) ou complète et valide ; « 1. » est une
            // saisie en cours, on n'enregistre pas.
            val addrOk = addr.isBlank() || (!addr.endsWith(".") && isPlausibleDmxAddress(addr))
            val wattsSaveOk = wattsText.isBlank() || (wattsText.trim().toIntOrNull()?.let { it > 0 } == true)
            Button(
                enabled = addrOk && wattsSaveOk,
                onClick = {
                    overrides.set(fixture, id, addr, modeName)
                    // Écrit la puissance dans la biblio partagée UNIQUEMENT si
                    // l'utilisateur a changé la valeur effective (ne pas « promouvoir »
                    // en saisie une valeur GDTF laissée telle quelle).
                    val spec = fixture.gdtfSpec
                    val parsed = wattsText.trim().toIntOrNull()
                    if (spec != null && parsed != null && parsed > 0 && parsed != resolution.watts) {
                        power.set(spec, parsed)
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("Enregistrer") }
        }
    }
}
