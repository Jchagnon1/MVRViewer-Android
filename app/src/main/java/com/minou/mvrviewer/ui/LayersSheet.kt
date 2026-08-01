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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/** Un calque MVR tel qu'affiché dans le panneau : nom, nb d'objets, couleur. */
data class LayerRow(val name: String, val objectCount: Int, val colorInt: Int)

/**
 * PANNEAU « CALQUES » DE LA VUE 3D (#1).
 *
 * Une ligne par calque MVR, avec le réglage du LOD D'INTERACTION à 3 états. Le
 * réglage ne touche QUE la navigation (pendant les gestes caméra) : au repos,
 * tout redevient visible, et un masquage (solo, calque masqué) reste prioritaire.
 *
 * C'est aussi l'endroit naturel où viendra se poser un futur masquage de calque
 * MVR (case à cocher), sans rien changer au réglage LOD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(
    layers: List<LayerRow>,
    modes: Map<String, LayerLodMode>,
    onSetMode: (String, LayerLodMode) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)) {
                Text(stringResource(R.string.layers_title_3d), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.layers_lod_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                if (layers.isEmpty()) {
                    Text(
                        stringResource(R.string.layers_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    layers.forEach { l ->
                        LayerLodRow(l, modes[l.name] ?: LayerLodMode.AUTO) { onSetMode(l.name, it) }
                        HorizontalDivider()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onResetAll) { Text(stringResource(R.string.layers_all_auto)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayerLodRow(layer: LayerRow, mode: LayerLodMode, onSetMode: (LayerLodMode) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pastille de couleur : la MÊME que celle du calque en 3D et en plan.
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
