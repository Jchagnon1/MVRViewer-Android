package com.minou.mvrviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minou.mvrviewer.mvr.DmxAddress
import com.minou.mvrviewer.mvr.DmxMode
import com.minou.mvrviewer.mvr.GdtfModes
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * Empreinte d'un projecteur dans un univers : où elle commence, combien de canaux
 * elle occupe, et le paramètre (attribut GDTF) à chaque canal. Portage de
 * `DMXPatch` (iOS DMXUniverseView).
 */
data class DmxPatch(
    val fixtureKey: String,
    val fixtureId: String?,
    val name: String,
    val layerName: String,
    val mode: String?,
    val universe: Int,
    val start: Int,       // 1…512 : premier canal
    val count: Int,       // empreinte
    val attrs: List<String>
) {
    val end: Int get() = minOf(512, start + maxOf(1, count) - 1)
    val rangeLabel: String get() = if (count <= 1) "$start" else "$start → $end"
    fun attribute(ch: Int): String? = attrs.getOrNull(ch - start)?.takeIf { it.isNotEmpty() }
}

/**
 * Vue « par univers » — portage de DMXUniverseView (iOS) : grille des 512 canaux
 * DMX d'un univers, empreinte de chaque projecteur (bloc coloré par calque,
 * luminosité alternée), SURBRILLANCE ROUGE des chevauchements (deux projecteurs
 * sur le même canal), inspecteur de canal et légende. L'empreinte vient du mode
 * GDTF (footprint), résolu une fois par type, hors thread principal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmxUniverseScreen(
    scene: MvrScene,
    mvrBytes: ByteArray,
    overrides: GdtfOverrides,
    // Overrides de patch — NOM d'AFFICHAGE effectif (renommage) des projecteurs.
    // Défaut vide = noms bruts du .mvr (aucun changement pour qui ne renomme pas).
    patchOverrides: PatchOverrides = remember { PatchOverrides() },
    // Projecteurs custom V1 : empreinte + noms de canaux d'un type « custom:<id> ».
    customResolver: CustomFixtureResolver = remember { CustomFixtureResolver() },
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layerIndex = remember(scene) { LayerColors.index(scene) }
    val loadState by produceState<List<DmxPatch>?>(null, scene, mvrBytes, overrides.version, patchOverrides.version, customResolver.version) {
        value = withContext(Dispatchers.Default) { buildDmxPatches(scene, mvrBytes, overrides.map.toMap(), patchOverrides, customResolver) }
    }
    val patches = loadState ?: emptyList()
    val loading = loadState == null

    val universes = remember(patches) { patches.map { it.universe }.distinct().sorted() }
    var selectedUniverse by remember { mutableStateOf(1) }
    var infoChannel by remember(scene) { mutableStateOf<Int?>(null) }
    var selectedKey by remember(scene) { mutableStateOf<String?>(null) }
    LaunchedEffect(universes) {
        if (universes.isNotEmpty() && selectedUniverse !in universes) selectedUniverse = universes.first()
    }
    var universeMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.universe_title_fmt, selectedUniverse), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back_3d))
                }
            },
            actions = {
                if (universes.size > 1 || universes.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { universeMenu = true }) {
                            Text(stringResource(R.string.universe_title_fmt, selectedUniverse))
                            Icon(Icons.Filled.UnfoldMore, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = universeMenu, onDismissRequest = { universeMenu = false }) {
                            universes.forEach { u ->
                                val conf = universeConflictCount(patches, u)
                                val check = if (u == selectedUniverse) "  ✓" else ""
                                val warn = if (conf > 0) "  ⚠" else ""
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.universe_title_fmt, u) + warn + check) },
                                    onClick = { selectedUniverse = u; infoChannel = null; universeMenu = false }
                                )
                            }
                        }
                    }
                }
            }
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.universe_analysing), modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            universes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.universe_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val inUniverse = remember(patches, selectedUniverse) {
                    patches.filter { it.universe == selectedUniverse }.sortedBy { it.start }
                }
                val occ = remember(inUniverse) {
                    val slots = Array(512) { mutableListOf<DmxPatch>() }
                    for (p in inUniverse) {
                        val s = maxOf(1, p.start); val e = minOf(512, p.end)
                        if (s <= e) for (ch in s..e) slots[ch - 1].add(p)
                    }
                    slots
                }
                val conflicts = remember(occ) { (0..511).filter { occ[it].size > 1 }.map { it + 1 }.toSet() }
                val order = remember(inUniverse) {
                    HashMap<String, Int>().apply { inUniverse.forEachIndexed { i, p -> put(patchId(p), i) } }
                }

                SummaryBar(inUniverse.size, occ.count { it.isNotEmpty() }, conflicts.size)
                HorizontalDivider()
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ChannelGrid(occ, conflicts, order, selectedKey, layerIndex) { ch, owner ->
                            infoChannel = ch
                            owner?.let { selectedKey = it.fixtureKey }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        FixtureLegend(inUniverse, conflicts, selectedKey, layerIndex, selectedUniverse) { p ->
                            infoChannel = p.start; selectedKey = p.fixtureKey
                        }
                        Box(Modifier.height(120.dp)) {} // marge pour l'inspecteur
                    }
                    val ch = infoChannel
                    if (ch != null) {
                        ChannelInspector(ch, occ[ch - 1], conflicts.contains(ch), layerIndex,
                            onClose = { infoChannel = null },
                            modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

// ---- Barre de résumé --------------------------------------------------------

@Composable
private fun SummaryBar(count: Int, used: Int, conflicts: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(stringResource(R.string.universe_fixtures_fmt, count), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.universe_used_fmt, used), style = MaterialTheme.typography.labelLarge)
        if (conflicts == 0) {
            Text(stringResource(R.string.universe_no_conflict), style = MaterialTheme.typography.labelLarge, color = Color(0xFF2E7D32))
        } else {
            Text("⚠ " + stringResource(R.string.universe_conflicts_fmt, conflicts),
                style = MaterialTheme.typography.labelLarge, color = Color(0xFFC62828))
        }
    }
}

// ---- Grille des 512 canaux --------------------------------------------------

@Composable
private fun ChannelGrid(
    occ: Array<MutableList<DmxPatch>>,
    conflicts: Set<Int>,
    order: Map<String, Int>,
    selectedKey: String?,
    layerIndex: Map<String, Int>,
    onTap: (Int, DmxPatch?) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        for (rowIdx in 0 until 32) {
            Row(Modifier.fillMaxWidth()) {
                for (colIdx in 0 until 16) {
                    val ch = rowIdx * 16 + colIdx + 1
                    val owner = occ[ch - 1].firstOrNull()
                    val conflict = conflicts.contains(ch)
                    val isStart = owner?.start == ch
                    val selected = owner != null && owner.fixtureKey == selectedKey
                    val alt = owner?.let { order[patchId(it)] }?.let { it % 2 == 1 } ?: false
                    val bg = when {
                        conflict -> Color(0xFFD32F2F)
                        owner != null -> LayerColors.color(layerIndex, owner.layerName).copy(alpha = if (alt) 0.55f else 0.95f)
                        else -> Color(0x1F808080)
                    }
                    val fg = if (owner != null || conflict) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    val borderColor = when {
                        selected -> MaterialTheme.colorScheme.onSurface
                        conflict -> Color(0xFFD32F2F)
                        else -> Color.Transparent
                    }
                    Box(
                        modifier = Modifier.weight(1f).padding(1.dp).height(30.dp)
                            .clip(RoundedCornerShape(3.dp)).background(bg)
                            .border(if (selected) 2.dp else 1.5.dp, borderColor, RoundedCornerShape(3.dp))
                            .clickable { onTap(ch, owner) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$ch", fontSize = 9.sp, color = fg,
                            fontWeight = if (isStart) FontWeight.Bold else FontWeight.Normal)
                        if (isStart) {
                            Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 2.dp)
                                .fillMaxWidth().height(2.dp).background(Color.White))
                        }
                    }
                }
            }
        }
    }
}

// ---- Inspecteur de canal (bas d'écran) --------------------------------------

@Composable
private fun ChannelInspector(
    ch: Int,
    list: List<DmxPatch>,
    conflict: Boolean,
    layerIndex: Map<String, Int>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.universe_channel_fmt, ch), style = MaterialTheme.typography.titleSmall)
                if (conflict) Text("  ⚠ " + stringResource(R.string.universe_overlap), style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC62828))
                Box(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (list.isEmpty()) {
                Text(stringResource(R.string.universe_channel_free), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                list.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape)
                            .background(LayerColors.color(layerIndex, p.layerName)))
                        Text("  " + (p.fixtureId?.let { stringResource(R.string.fixture_id_fmt, it) } ?: "—"),
                            style = MaterialTheme.typography.labelMedium)
                        Text("  ${p.name}", style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        p.attribute(ch)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ---- Légende des projecteurs de l'univers -----------------------------------

@Composable
private fun FixtureLegend(
    inUniverse: List<DmxPatch>,
    conflicts: Set<Int>,
    selectedKey: String?,
    layerIndex: Map<String, Int>,
    universe: Int,
    onSelect: (DmxPatch) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.universe_fixtures_title_fmt, universe), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        inUniverse.forEach { p ->
            val conflict = (maxOf(1, p.start)..minOf(512, p.end)).any { conflicts.contains(it) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .background(if (p.fixtureKey == selectedKey) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                    .background(LayerColors.color(layerIndex, p.layerName)))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text((p.fixtureId?.let { stringResource(R.string.fixture_id_fmt, it) } ?: p.name) + if (conflict) "  ⚠" else "",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${p.name} · ${p.mode ?: "—"}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(p.rangeLabel, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    Text(stringResource(R.string.universe_channels_count_fmt, p.count), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 14.dp))
        }
    }
}

// ---- Construction des empreintes (hors thread principal) --------------------

private fun patchId(p: DmxPatch) = "${p.fixtureKey}:${p.universe}:${p.start}"

private fun universeConflictCount(patches: List<DmxPatch>, u: Int): Int {
    val slots = IntArray(512)
    for (p in patches) if (p.universe == u) {
        for (ch in maxOf(1, p.start)..minOf(512, p.end)) slots[ch - 1]++
    }
    return slots.count { it > 1 }
}

/** Adresse « univers.canal » → (u, ch), via le même formateur que le reste de l'app. */
private fun parseUniverseChannel(raw: String): Pair<Int, Int>? {
    val parts = DmxAddress.format(raw).split(".")
    if (parts.size != 2) return null
    val u = parts[0].toIntOrNull() ?: return null
    val a = parts[1].toIntOrNull() ?: return null
    if (u < 1 || a < 1) return null
    return u to a
}

private fun buildDmxPatches(
    scene: MvrScene, mvrBytes: ByteArray, overrides: Map<String, ByteArray>,
    patchOverrides: PatchOverrides, customResolver: CustomFixtureResolver
): List<DmxPatch> {
    val fixtures = scene.fixtures
    // Empreintes GDTF résolues UNE fois par type (override GDTF Share sinon embarqué).
    val modesBySpec = HashMap<String, List<DmxMode>>()
    for (spec in fixtures.mapNotNull { it.gdtfSpec?.trim() }.filter { it.isNotEmpty() }.toSet()) {
        var data = overrides[spec]
        if (data == null) {
            val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
            data = cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
        }
        if (data != null) modesBySpec[spec] = runCatching { GdtfModes.parse(data) }.getOrDefault(emptyList())
    }
    val out = ArrayList<DmxPatch>()
    for (f in fixtures) {
        val addrs = f.addresses.mapNotNull { parseUniverseChannel(it) }
        if (addrs.isEmpty()) continue
        var footprint = 1
        var attrs: List<String> = emptyList()
        // Type custom résolu → empreinte (footprint) + NOMS de canaux du type ;
        // sinon résolution GDTF classique. Au-delà des canaux nommés, repli "".
        val custom = customTypeOf(f, patchOverrides, customResolver)
        if (custom != null) {
            footprint = maxOf(1, custom.footprint)
            attrs = List(footprint) { i -> custom.channels.getOrElse(i) { "" } }
        } else {
            val spec = f.gdtfSpec?.trim()
            val modes = spec?.let { modesBySpec[it] }
            if (!modes.isNullOrEmpty()) {
                val mode = modes.firstOrNull { it.name == f.gdtfMode } ?: modes.first()
                footprint = maxOf(1, mode.footprint)
                val a = MutableList(footprint) { "" }
                for (ch in mode.channels) for (off in ch.offsets) if (off in 1..footprint) a[off - 1] = ch.attribute
                attrs = a
            }
        }
        val key = f.uuid ?: "${f.name}|${f.layerName}"
        // NOM d'AFFICHAGE effectif (renommage) ; l'identité (key) reste l'originale.
        val displayName = patchOverrides.effectiveName(f)
        for ((u, c) in addrs) {
            out.add(DmxPatch(key, f.fixtureId, displayName, f.layerName, f.gdtfMode, u, c, footprint, attrs))
        }
    }
    return out
}
