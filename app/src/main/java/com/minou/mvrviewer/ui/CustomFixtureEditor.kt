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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

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
    // Type MA / à 3D choisie / « Aucune » : la base reste celle du type (souvent vide) —
    // pas de pré-remplissage par le GDTF du projecteur, qui la changerait à l'enregistrement.
    val initial3D = remember(initial) { editor3DInitial(initial, defaultBaseSpec) }
    var baseSpec by remember(initial) { mutableStateOf(initial3D.baseSpec) }
    // (e) « Aucune (formes génériques) » choisie explicitement : ni base ni 3D, et
    // « Enregistrer » reste permis (§4.1 (e)) — y compris pour un type ancien.
    var explicitNone by remember(initial) { mutableStateOf(initial3D.explicitNone) }
    val channels: SnapshotStateList<String> = remember(initial) {
        (initial?.channels ?: emptyList()).toMutableStateList()
    }
    var footprintText by remember(initial) {
        mutableStateOf((initial?.footprint ?: initial?.channels?.size ?: 0).let { if (it > 0) it.toString() else "" })
    }
    var specMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Bibliothèque MA (SPEC-ma.md §5.9) : provenance (lecture seule) + 3D choisie.
    val profile = initial?.profile
    var model3D by remember(initial) { mutableStateOf(initial?.model3D) }
    var choose3D by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fixture3DStore = remember { Fixture3DStore.of(ctx) }

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
                stringResource(if (initial == null) R.string.cfx_new_title else R.string.cfx_edit_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.cfx_type_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )

            // ---- Profil MA (lecture seule) -------------------------------------
            profile?.let { pr ->
                Text(stringResource(R.string.ma_editor_profile), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 2.dp))
                Text("${pr.manufacturer} — ${pr.fixture}", style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.ma_editor_mode, pr.mode.ifEmpty { stringResource(R.string.ma_mode_unnamed) }),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
            }

            // ---- 3D : libellé, statut d'indisponibilité, « Changer la 3D… » ----
            val draft3D = CustomFixtureTypeDTO(id, name, baseSpec.orEmpty(), emptyList(), 1, profile, model3D)
            Text(stringResource(R.string.ma_editor_3d), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 2.dp))
            Text(stringResource(R.string.ma_3d_line, type3DLabel(draft3D)), style = MaterialTheme.typography.bodyMedium)
            Fixture3DStatusLines(draft3D, hasOrigin = !defaultBaseSpec.isNullOrBlank(), store = fixture3DStore)
            OutlinedButton(
                onClick = { ProAccess.gate("créer un type personnalisé") { choose3D = true } },
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            ) { Text(stringResource(R.string.ma_change_3d)) }

            // GDTF de base (fournit la géométrie 3D).
            Text(stringResource(R.string.cfx_base_gdtf), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 4.dp))
            Box {
                OutlinedButton(onClick = { specMenu = true }, enabled = specChoices.isNotEmpty()) {
                    Text(baseSpec ?: stringResource(if (specChoices.isEmpty()) R.string.cfx_no_gdtf else R.string.common_choose))
                }
                DropdownMenu(expanded = specMenu, onDismissRequest = { specMenu = false }) {
                    specChoices.forEach { s ->
                        DropdownMenuItem(text = { Text(s) }, onClick = { baseSpec = s; explicitNone = false; specMenu = false })
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
            ) { Text(stringResource(if (busy) R.string.cfx_reading_gdtf else R.string.cfx_prefill_from_gdtf)) }

            // Liste de canaux : renommer / réordonner / supprimer.
            Text(stringResource(R.string.fx_channels_title_fmt, channels.size), style = MaterialTheme.typography.labelLarge,
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
                        singleLine = true, label = { Text(stringResource(R.string.cfx_channel_fmt, i + 1)) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { if (i > 0) { val t = channels[i - 1]; channels[i - 1] = channels[i]; channels[i] = t } },
                        enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.common_move_up))
                    }
                    IconButton(onClick = { if (i < channels.size - 1) { val t = channels[i + 1]; channels[i + 1] = channels[i]; channels[i] = t } },
                        enabled = i < channels.size - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.common_move_down))
                    }
                    IconButton(onClick = {
                        channels.removeAt(i)
                        // Le footprint suit le nb de canaux tant qu'il n'a pas été forcé
                        // à une valeur différente (repli lisible).
                        if (footprintText.trim().toIntOrNull() == channels.size + 1) footprintText = channels.size.toString()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
                    }
                }
            }
            OutlinedButton(
                onClick = { channels.add(""); footprintText = channels.size.toString() },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cfx_add_channel))
            }

            // Footprint réglable (nb de canaux occupés ; défaut = nb de canaux).
            OutlinedTextField(
                value = footprintText,
                onValueChange = { new -> footprintText = new.filter { it.isDigit() } },
                label = { Text(stringResource(R.string.cfx_footprint)) },
                placeholder = { Text(channels.size.coerceAtLeast(1).toString()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(stringResource(R.string.cfx_footprint_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    TextButton(onClick = { onDelete(id) }) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    // Base FACULTATIVE pour un type MA (profil), un type à 3D choisie, ou
                    // quand « Aucune » a été choisie explicitement.
                    enabled = canSaveCustomType(name, Editor3DState(baseSpec, model3D, explicitNone), profile != null),
                    onClick = {
                        onSave(
                            CustomFixtureTypeDTO(
                                id = id,
                                name = name.trim(),
                                baseGdtfSpec = baseSpec.orEmpty(),
                                channels = if (profile != null) keepHoles(channels.toList()) else channels.toList(),
                                footprint = footprintOrDefault(),
                                profile = profile,
                                model3D = model3D
                            )
                        )
                    }
                ) { Text(stringResource(R.string.cfx_save_type)) }
            }
        }
    }

    // « Changer la 3D… » : même étape que la création depuis la bibliothèque MA.
    if (choose3D) {
        Fixture3DChooserDialog(
            scene = scene, store = fixture3DStore,
            shareQuery = profile?.fixture ?: name,
            onDismiss = { choose3D = false },
            onChosen = { c ->
                val next = editor3DAfterChoice(c)
                model3D = next.model3D
                baseSpec = next.baseSpec
                explicitNone = next.explicitNone
                choose3D = false
            }
        )
    }
}

/** Choix de 3D en cours d'édition : base (GDTF du show), 3D choisie, « Aucune » explicite. */
internal data class Editor3DState(
    val baseSpec: String?,
    val model3D: com.minou.mvrviewer.sync.CustomFixtureModel3D?,
    val explicitNone: Boolean
)

/**
 * État initial de l'éditeur. Nouveau type : base pré-remplie avec le GDTF du projecteur.
 * Type existant : sa base telle quelle — un type MA / à 3D choisie n'est pas pré-rempli, et
 * un type enregistré avec « Aucune » (ni base, ni 3D, ni profil) le reste.
 */
internal fun editor3DInitial(initial: CustomFixtureTypeDTO?, defaultBaseSpec: String?): Editor3DState {
    if (initial == null) return Editor3DState(defaultBaseSpec, null, false)
    val base = initial.baseGdtfSpec.takeIf { it.isNotBlank() }
    if (base != null) return Editor3DState(base, initial.model3D, false)
    if (initial.profile != null || initial.model3D != null) return Editor3DState(null, initial.model3D, false)
    return Editor3DState(null, null, explicitNone = true)
}

/**
 * Résultat de « Changer la 3D… » (§4.1) : (a) GDTF du show → base ; (b)(c)(d) → `model3D`
 * sans base ; (e) « Aucune » → NI base NI 3D, QUEL QUE SOIT le type (avant : l'ancienne base
 * d'un type sans profil était conservée, et le choix n'avait aucun effet).
 */
internal fun editor3DAfterChoice(c: Chosen3D): Editor3DState {
    val base = c.baseGdtfSpec.takeIf { it.isNotBlank() }
    return Editor3DState(base, c.model3D, explicitNone = base == null && c.model3D == null)
}

/** « Enregistrer » : un nom, et une base OU une 3D choisie OU un profil MA OU « Aucune » explicite. */
internal fun canSaveCustomType(name: String, s: Editor3DState, hasProfile: Boolean): Boolean =
    name.isNotBlank() && (!s.baseSpec.isNullOrBlank() || s.model3D != null || hasProfile || s.explicitNone)

/**
 * Canaux d'un type À `profile` (SPEC-ma.md §3.7) : noms `trim()`, trous `""` du MILIEU
 * GARDÉS (l'index est l'offset), `""` de FIN retirés.
 */
internal fun keepHoles(list: List<String>): List<String> {
    val trimmed = list.map { it.trim() }
    var end = trimmed.size
    while (end > 0 && trimmed[end - 1].isEmpty()) end--
    return trimmed.subList(0, end)
}

/** Statut « 3D indisponible » + raison (§4.5), dans l'éditeur et la fiche projecteur. */
@Composable
internal fun Fixture3DStatusLines(t: CustomFixtureTypeDTO, hasOrigin: Boolean, store: Fixture3DStore) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = store.version  // recompose quand le cache change
    // Connexion OBSERVABLE : une connexion GDTF Share efface aussitôt « Connectez-vous… ».
    val signedIn by com.minou.mvrviewer.mvr.GdtfShareClient.loggedInFlow.collectAsState()
    val credsStored = remember { com.minou.mvrviewer.mvr.GdtfCredentialStore.username(ctx) != null }
    val u = store.unavailability(t, hasOrigin, signedIn, credsStored) ?: return
    Text(
        stringResource(if (u.originShown) R.string.ma_3d_unavail_origin else R.string.ma_3d_unavail_generic),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
    )
    val reason = when (u.reason) {
        Fixture3DReason.SIGN_IN_SHARE -> stringResource(R.string.ma_reason_sign_in)
        Fixture3DReason.FORMAT_UNSUPPORTED -> stringResource(R.string.ma_reason_format, u.formatLabel ?: "?")
        Fixture3DReason.MISSING_IN_PROJECT -> stringResource(R.string.ma_reason_missing)
        Fixture3DReason.DOWNLOAD_FAILED -> stringResource(R.string.ma_reason_download)
        Fixture3DReason.CORRUPTED -> stringResource(R.string.ma_reason_corrupt)
        null -> null
    }
    reason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
