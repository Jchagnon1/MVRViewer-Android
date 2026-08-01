package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/** Entrée du catalogue de personnalisation : un outil assignable à un bord. */
data class ToolCatalogEntry(val id: ToolId, val label: String, val icon: ImageVector)

/**
 * MODE PERSONNALISER (N11 §4) — panneau clair plutôt qu'un drag fragile (choix
 * recommandé par la spec). Pour chaque outil de la vue COURANTE : un bord (Haut /
 * Bas / Gauche / Droite / Hors barres) + Monter/Descendre pour réordonner dans sa
 * barre. « Réinitialiser » revient au défaut de la vue ; « Terminé » ferme. Chaque
 * changement applique immédiatement via [onLayout] (la barre derrière se met à jour ;
 * la persistance est gérée par l'appelant). N'affecte QUE la disposition passée.
 */
@Composable
fun ToolbarCustomizeSheet(
    title: String,
    layout: ToolbarLayout,
    catalog: List<ToolCatalogEntry>,
    default: ToolbarLayout,
    onLayout: (ToolbarLayout) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.toolbar_customize_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                Column(
                    modifier = Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    catalog.forEach { entry ->
                        ToolRow(entry, layout, onLayout)
                        HorizontalDivider()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onLayout(default) }) { Text(stringResource(R.string.common_reset)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(
    entry: ToolCatalogEntry,
    layout: ToolbarLayout,
    onLayout: (ToolbarLayout) -> Unit
) {
    val cur = layout.edgeOf(entry.id)
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(entry.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onLayout(layout.reordered(entry.id, -1)) },
                enabled = cur != null
            ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.common_move_up)) }
            IconButton(
                onClick = { onLayout(layout.reordered(entry.id, +1)) },
                enabled = cur != null
            ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.common_move_down)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EdgeChip(stringResource(R.string.edge_top), cur == ToolbarEdge.TOP) { onLayout(layout.moved(entry.id, ToolbarEdge.TOP)) }
            EdgeChip(stringResource(R.string.edge_bottom), cur == ToolbarEdge.BOTTOM) { onLayout(layout.moved(entry.id, ToolbarEdge.BOTTOM)) }
            EdgeChip(stringResource(R.string.edge_left), cur == ToolbarEdge.LEFT) { onLayout(layout.moved(entry.id, ToolbarEdge.LEFT)) }
            EdgeChip(stringResource(R.string.edge_right), cur == ToolbarEdge.RIGHT) { onLayout(layout.moved(entry.id, ToolbarEdge.RIGHT)) }
            EdgeChip(stringResource(R.string.edge_none), cur == null) { onLayout(layout.moved(entry.id, null)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EdgeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
    )
}
