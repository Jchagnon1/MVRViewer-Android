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
import androidx.compose.ui.Alignment
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

/**
 * Modification de patch d'un projecteur (ID / adresse DMX / mode / nom / spec de
 * rendu). `spec` = override de SPEC (« custom:<id> » d'un type custom assigné à CE
 * projecteur, cf. projecteurs custom V1), null = aucun override (spec brute du .mvr).
 */
data class PatchEdit(
    val fixtureId: String?, val address: String?, val modeName: String?,
    val name: String?, val spec: String? = null
)

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
    /**
     * Nom d'AFFICHAGE effectif : l'override de nom s'il est non vide, sinon le nom
     * d'origine du .mvr. Un OVERRIDE D'AFFICHAGE, jamais une identité — la [key]
     * reste `mvrInstanceKey`, donc sélection / masquage / patch ne bougent pas.
     */
    fun effectiveName(o: MvrSceneObject): String = edits[key(o)]?.name?.takeIf { it.isNotBlank() } ?: o.name
    /**
     * SPEC de rendu EFFECTIVE : l'override « custom:<id> » s'il est présent, sinon
     * la spec brute du .mvr. C'est le point d'entrée UNIQUE des projecteurs custom :
     * partout où la résolution GDTF/empreinte lisait `f.gdtfSpec`, on lit désormais
     * `effectiveSpec(f)` (custom → résolveur ; non-custom → inchangé).
     */
    fun effectiveSpec(o: MvrSceneObject): String? = edits[key(o)]?.spec?.takeIf { it.isNotBlank() } ?: o.gdtfSpec
    fun isEdited(o: MvrSceneObject) = edits.containsKey(key(o))

    fun set(o: MvrSceneObject, id: String?, address: String?, mode: String?, name: String?) {
        val old = PatchEdit(effectiveId(o), effectiveAddress(o), effectiveMode(o), effectiveName(o), effectiveSpec(o))
        // On PRÉSERVE l'override de spec de rendu existant (l'édition ID/adresse/mode/
        // nom ne doit pas effacer l'assignation d'un type custom).
        val new = PatchEdit(id?.ifBlank { null }, address?.ifBlank { null }, mode, name?.ifBlank { null }, edits[key(o)]?.spec)
        edits[key(o)] = new
        version++
        onCommit?.invoke(o, old, new)
    }

    /**
     * Assigne (ou retire) une SPEC de rendu custom à un projecteur (« custom:<id> »,
     * ou null pour revenir au GDTF standard). Préserve ID/adresse/mode/nom. Passe par
     * le MÊME `onCommit` que le reste du patch → persistance patch + push cloud + audit.
     */
    fun assignSpec(o: MvrSceneObject, spec: String?) {
        val cur = edits[key(o)]
        val old = PatchEdit(effectiveId(o), effectiveAddress(o), effectiveMode(o), effectiveName(o), effectiveSpec(o))
        val new = PatchEdit(cur?.fixtureId, cur?.address, cur?.modeName, cur?.name, spec?.ifBlank { null })
        edits[key(o)] = new
        version++
        onCommit?.invoke(o, old, new)
    }

    /** État persistable (clé → édit) pour PatchStore / mapping cloud. */
    fun toPersistedList(): List<com.minou.mvrviewer.sync.PersistedPatchEdit> =
        edits.map { (k, e) ->
            com.minou.mvrviewer.sync.PersistedPatchEdit(k, e.fixtureId, e.address, e.modeName, e.name, e.spec)
        }

    /** Applique des édits (restauration disque OU distant) SANS déclencher onCommit. */
    fun applyPersisted(list: List<com.minou.mvrviewer.sync.PersistedPatchEdit>) {
        for (e in list) edits[e.key] = PatchEdit(e.fixtureId, e.address, e.modeName, e.name, e.spec)
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
    // Projecteurs custom V1 : biblio (édition/création de types) + résolveur
    // (assignation depuis cette fiche) + scène/gdtfOverrides (choix du GDTF de base
    // et pré-remplissage des canaux depuis son mode). Défauts vides = fiche
    // standard, aucune régression pour qui n'utilise pas les types custom.
    scene: com.minou.mvrviewer.mvr.MvrScene? = null,
    gdtfOverrides: GdtfOverrides = remember { GdtfOverrides() },
    customLibrary: CustomFixtureLibrary = remember { CustomFixtureLibrary() },
    customResolver: CustomFixtureResolver = remember { CustomFixtureResolver() },
    // Appelé quand la biblio de types custom change (création/édition/suppression) —
    // SceneScreen y persiste la section customFixtures et la pousse au cloud.
    onCustomLibraryChanged: () -> Unit = {},
    onDismiss: () -> Unit
) {
    // Type custom résolu de CE projecteur (empreinte/canaux/nom réécrits), ou null.
    val customType = customTypeOf(fixture, overrides, customResolver)
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
    // Nom d'AFFICHAGE éditable (renommage) : initialisé sur le nom effectif. Un
    // override d'affichage, pas d'identité — le keying (mvrInstanceKey) ne change pas.
    var nameText by remember(fixture) { mutableStateOf(overrides.effectiveName(fixture)) }
    // Projecteurs custom V1 : menu d'assignation + éditeur de type (création/édition).
    var typeMenu by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editorInitial by remember(fixture) { mutableStateOf<com.minou.mvrviewer.sync.CustomFixtureTypeDTO?>(null) }

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
            Text(overrides.effectiveName(fixture), style = MaterialTheme.typography.headlineSmall)
            Text(
                "${fixture.gdtfSpec ?: "—"} · ${fixture.layerName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Nom d'affichage renommable — synchronisé par la MÊME section de patch.
            OutlinedTextField(
                value = nameText, onValueChange = { nameText = it },
                label = { Text("Nom") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
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

            // ---- Type de rendu (projecteurs custom V1) ----------------------
            // Assigner un TYPE custom = donner à ce projecteur la spec « custom:<id> »
            // (portée par la section patch, déjà synchronisée). GÉOMÉTRIE = baseGdtfSpec,
            // empreinte/canaux = ceux du type. « Standard » revient au GDTF d'origine.
            Text("Type de rendu", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            OutlinedButton(onClick = { typeMenu = true }) {
                Text(customType?.let { "${it.name}  ·  ${maxOf(1, it.footprint)} canaux (custom)" }
                    ?: "Standard : ${fixture.gdtfSpec ?: "—"}")
            }
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Standard : ${fixture.gdtfSpec ?: "—"}") },
                    onClick = { overrides.assignSpec(fixture, null); typeMenu = false }
                )
                if (customLibrary.types.isNotEmpty()) HorizontalDivider()
                customLibrary.types.forEach { t ->
                    DropdownMenuItem(
                        text = { Text("${t.name}  ·  ${maxOf(1, t.footprint)} canaux") },
                        onClick = {
                            overrides.assignSpec(fixture, com.minou.mvrviewer.sync.customFixtureSpec(t.id))
                            typeMenu = false
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("＋ Nouveau type custom…") },
                    onClick = { editorInitial = null; showEditor = true; typeMenu = false }
                )
                customType?.let { ct ->
                    DropdownMenuItem(
                        text = { Text("✎ Éditer « ${ct.name} »") },
                        onClick = { editorInitial = ct; showEditor = true; typeMenu = false }
                    )
                    // Dupliquer (parité iOS) : nouvel id + nom « (copie) », puis on
                    // assigne la copie à ce projecteur (miroir immédiat du résolveur).
                    DropdownMenuItem(
                        text = { Text("⧉ Dupliquer « ${ct.name} »") },
                        onClick = {
                            typeMenu = false
                            customLibrary.duplicate(ct.id)?.let { copy ->
                                customResolver.setLibrary(customLibrary.types.toList())
                                customResolver.mergeProject(listOf(copy))
                                overrides.assignSpec(fixture, com.minou.mvrviewer.sync.customFixtureSpec(copy.id))
                                onCustomLibraryChanged()
                            }
                        }
                    )
                }
            }

            // Choix du mode — MASQUÉ pour un type custom (l'empreinte et les noms de
            // canaux viennent du type, pas d'un mode GDTF).
            if (customType == null) {
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
            } else {
                // Canaux RÉÉCRITS du type custom (empreinte = footprint du type).
                Text("Canaux custom (${maxOf(1, customType.footprint)})",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                HorizontalDivider()
                val fp = maxOf(1, customType.footprint)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(fp) { i ->
                        val nm = customType.channels.getOrElse(i) { "" }.ifBlank { "—" }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${i + 1}", modifier = Modifier.width(36.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(nm, style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }

            // ---- Consommation électrique (consensus communautaire par votes) --
            // La valeur affichée est le CONSENSUS des votes du type (biblio
            // partagée), sinon le GDTF, sinon rien. « ✓ C'est bon » dépose MON
            // vote égal à la valeur affichée sans la retaper ; saisir une autre
            // valeur = mon vote à cette valeur. Personne n'écrase le vote d'un autre.
            if (fixture.gdtfSpec != null) {
                val wattsInt = wattsText.trim().toIntOrNull()
                val wattsOk = wattsText.isBlank() || (wattsInt != null && wattsInt > 0)
                val votes = power.consensusVotes(fixture.gdtfSpec)
                val effWatts = resolution.watts
                Text("Consommation", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

                // Consensus + confiance + bouton « ✓ C'est bon ».
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (effWatts != null) "$effWatts W" else "À saisir",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (effWatts != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            when (resolution.source) {
                                PowerSource.LIBRARY -> {
                                    val n = votes ?: 1
                                    "consensus · $n vote${if (n > 1) "s" else ""}"
                                }
                                PowerSource.GDTF -> "d'après le GDTF · aucun vote"
                                PowerSource.NONE -> "aucune valeur — votez la puissance max"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Confirmer la valeur affichée = déposer mon vote sans retaper.
                    if (effWatts != null) {
                        OutlinedButton(onClick = {
                            fixture.gdtfSpec?.let { spec ->
                                power.set(spec, effWatts)
                                wattsText = effWatts.toString()
                            }
                        }) { Text("✓ C'est bon") }
                    }
                }

                OutlinedTextField(
                    value = wattsText,
                    onValueChange = { new -> wattsText = new.filter { it.isDigit() } },
                    label = { Text("Ma valeur (W)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !wattsOk,
                    supportingText = {
                        Text(
                            "Votez une autre valeur si besoin ; le consensus se recalcule.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    overrides.set(fixture, id, addr, modeName, nameText)
                    // Dépose MON vote SAUF s'il est strictement redondant avec un
                    // consensus DÉJÀ établi (même règle qu'iOS `shouldWriteWatts`).
                    // Conséquence : retaper la valeur GDTF puis « Enregistrer »
                    // DÉPOSE bien un vote (sinon la conso « n'est pas gardée ») ;
                    // seule la ressaisie d'une valeur déjà issue du consensus est
                    // ignorée, car « ✓ C'est bon » la confirme sans doublon.
                    val spec = fixture.gdtfSpec
                    val parsed = wattsText.trim().toIntOrNull()
                    val redundantWithConsensus =
                        resolution.source == PowerSource.LIBRARY && parsed == resolution.watts
                    if (spec != null && parsed != null && parsed > 0 && !redundantWithConsensus) {
                        power.set(spec, parsed)
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("Enregistrer") }
        }
    }

    // Éditeur de type custom (création / édition). Nécessite la scène (choix du
    // GDTF de base + pré-remplissage des canaux depuis son mode).
    if (showEditor && scene != null) {
        CustomFixtureEditorSheet(
            initial = editorInitial,
            scene = scene,
            mvrBytes = mvrBytes,
            gdtfOverrides = gdtfOverrides,
            defaultBaseSpec = editorInitial?.baseGdtfSpec?.takeIf { it.isNotBlank() } ?: fixture.gdtfSpec,
            onSave = { t ->
                customLibrary.upsert(t)
                // Miroir immédiat dans le résolveur (biblio + projet) → l'assignation
                // ci-dessous résout tout de suite, sans attendre l'effet de SceneScreen.
                customResolver.setLibrary(customLibrary.types.toList())
                customResolver.mergeProject(listOf(t))
                overrides.assignSpec(fixture, com.minou.mvrviewer.sync.customFixtureSpec(t.id))
                onCustomLibraryChanged()
                showEditor = false
            },
            onDelete = editorInitial?.let { { id ->
                customLibrary.remove(id)
                customResolver.setLibrary(customLibrary.types.toList())
                onCustomLibraryChanged()
                showEditor = false
            } },
            onDismiss = { showEditor = false }
        )
    }
}
