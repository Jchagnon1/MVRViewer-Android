package com.minou.mvrviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.minou.mvrviewer.R

/** Un calque MVR tel qu'affiché dans la feuille : nom, nb d'objets, couleur. */
data class LayerRow(val name: String, val objectCount: Int, val colorInt: Int)

/**
 * Réglage LOD d'interaction par calque (« fluidité en navigation »), présent
 * seulement quand la feuille est ouverte depuis la 3D (SPEC-menus-android D10).
 */
class LayerLodControls(
    val modes: Map<String, LayerLodMode>,
    val onSetMode: (String, LayerLodMode) -> Unit,
    val onResetAll: () -> Unit
)

/**
 * FEUILLE « CALQUES » (SPEC-menus-android §3.10, iOS §9.5) — fusion des anciens
 * panneaux « Calques · Vue 3D » (LOD) et « Classes » :
 *
 * - si le fichier déclare des CLASSES Vectorworks, un sélecteur segmenté
 *   « Calques | Classes » en première ligne (sinon : pas de sélecteur) ;
 * - onglet Calques : une ligne par calque MVR (pastille de couleur, nom, nombre
 *   d'objets) + en 3D le réglage du LOD d'interaction à 3 états (il ne touche
 *   QUE la navigation : au repos tout redevient visible). Depuis le plan : la
 *   liste en lecture, sans LOD (le masquage des calques MVR n'existe pas sur
 *   Android, D10) ;
 * - onglet Classes : une bascule de visibilité par classe + « Tout masquer /
 *   Tout afficher ». Une classe TRAVERSE les calques : les deux filtres se
 *   cumulent, la classe ne pilote que la visibilité.
 *
 * Titre = l'onglet affiché. Fermée par « Terminé » ou le retour système.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(
    initialTab: LayersTab,
    layers: List<LayerRow>,
    /** null = sans réglage LOD (feuille ouverte depuis le plan). */
    lod: LayerLodControls?,
    classes: List<Pair<String, Int>>,
    hiddenClasses: Set<String>,
    onToggleClass: (String) -> Unit,
    onSetAllClasses: (hideAll: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val hasClasses = classes.isNotEmpty()
    val tabs = LayersSheetTabs.tabs(hasClasses)
    var tab by remember(initialTab, hasClasses) { mutableStateOf(LayersSheetTabs.effective(initialTab, hasClasses)) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, modifier = Modifier.testTag("layersSheet")) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)) {
                Text(
                    stringResource(if (tab == LayersTab.CLASSES) R.string.classes_title else R.string.layers_title),
                    style = MaterialTheme.typography.titleMedium
                )
                if (tabs.size > 1) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        tabs.forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = tab == t,
                                onClick = { tab = t },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = tabs.size),
                                label = {
                                    Text(stringResource(if (t == LayersTab.CLASSES) R.string.classes_title else R.string.layers_title))
                                }
                            )
                        }
                    }
                }
                when (tab) {
                    LayersTab.LAYERS -> LayersTabContent(layers, lod, Modifier.weight(1f, fill = false))
                    LayersTab.CLASSES -> ClassesTabContent(
                        classes, hiddenClasses, onToggleClass, onSetAllClasses, Modifier.weight(1f, fill = false)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tab == LayersTab.LAYERS && lod != null) {
                        TextButton(onClick = lod.onResetAll) { Text(stringResource(R.string.layers_all_auto)) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

@Composable
private fun LayersTabContent(layers: List<LayerRow>, lod: LayerLodControls?, modifier: Modifier) {
    Column(modifier) {
        if (lod != null) {
            Text(
                stringResource(R.string.layers_lod_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }
        if (layers.isEmpty()) {
            Text(
                stringResource(R.string.layers_none),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = if (lod == null) 8.dp else 0.dp)) {
            layers.forEach { l ->
                if (lod != null) {
                    LayerLodRow(l, lod.modes[l.name] ?: LayerLodMode.AUTO) { lod.onSetMode(l.name, it) }
                } else {
                    Box(Modifier.padding(vertical = 10.dp)) { LayerHeader(l) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ClassesTabContent(
    classes: List<Pair<String, Int>>,
    hidden: Set<String>,
    onToggle: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        Text(
            stringResource(R.string.classes_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        TextButton(onClick = { onSetAll(hidden.isEmpty()) }) {
            Text(stringResource(if (hidden.isEmpty()) R.string.layers_hide_all else R.string.layers_show_all))
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            classes.forEach { (name, count) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            androidx.compose.ui.res.pluralStringResource(R.plurals.classes_objects, count, count),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = name !in hidden, onCheckedChange = { onToggle(name) })
                }
                HorizontalDivider()
            }
        }
    }
}

/** Pastille de couleur (la MÊME que celle du calque en 3D et en plan) + nom + nombre d'objets. */
@Composable
private fun LayerHeader(layer: LayerRow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(14.dp).height(14.dp).clip(CircleShape)
                .background(Color(layer.colorInt))
                .border(1.dp, Color(0x55808080), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            layer.name.ifBlank { stringResource(R.string.common_unnamed) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${layer.objectCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayerLodRow(layer: LayerRow, mode: LayerLodMode, onSetMode: (LayerLodMode) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        LayerHeader(layer)
        // Libellés EXACTS (vocabulaire figé, commun iOS/Android).
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            LayerLodMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onSetMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = LayerLodMode.entries.size),
                    label = {
                        Text(stringResource(m.labelRes), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                )
            }
        }
    }
}
