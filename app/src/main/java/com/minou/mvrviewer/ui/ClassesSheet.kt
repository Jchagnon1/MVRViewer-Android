package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * PANNEAU « CLASSES » — pendant EXACT de [LayersSheet], pour les CLASSES
 * Vectorworks : une ligne par classe, bascule de visibilité, « Tout masquer /
 * Tout afficher ». Même persistance, même effet immédiat en 3D et en vue plan.
 *
 * Pourquoi un panneau séparé plutôt qu'une section des calques : une classe
 * TRAVERSE les calques (« 00 STAGE - TOWER » regroupe des pièces réparties sur
 * plusieurs calques), les deux filtres sont donc orthogonaux et cumulatifs.
 *
 * Pas de couleur ni de LOD ici : ils restent portés par le CALQUE (un objet
 * appartient à un calque ET à une classe — deux sources de couleur concurrentes
 * n'auraient pas de sens). La classe ne pilote que la VISIBILITÉ.
 */
@Composable
fun ClassesSheet(
    classes: List<Pair<String, Int>>,
    hidden: Set<String>,
    onToggle: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)) {
                Text(stringResource(R.string.classes_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.classes_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                if (classes.isEmpty()) {
                    // Beaucoup de MVR ne déclarent aucune classe : le dire, plutôt
                    // qu'afficher une liste vide sans explication.
                    Text(
                        stringResource(R.string.classes_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    TextButton(onClick = { onSetAll(hidden.isEmpty()) }) {
                        Text(
                            stringResource(
                                if (hidden.isEmpty()) R.string.layers_hide_all else R.string.layers_show_all
                            )
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        classes.forEach { (name, count) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name, style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(R.string.classes_objects_fmt, count),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = name !in hidden,
                                    onCheckedChange = { onToggle(name) }
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}
