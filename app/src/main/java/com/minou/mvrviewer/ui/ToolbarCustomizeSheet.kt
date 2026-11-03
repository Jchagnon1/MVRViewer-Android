package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
 * Bas / Gauche / Droite) ou « Hors barres », + Monter/Descendre pour réordonner
 * dans sa barre. Chaque changement s'applique immédiatement via [onLayout] (la
 * barre derrière se met à jour ; l'appelant enregistre : c'est un choix de
 * l'utilisateur). « Tout remettre hors barres » enregistre la disposition VIDE
 * (elle reste vide, même sur tablette) ; « Réinitialiser » ([onReset]) efface le
 * choix et rend le défaut de l'appareil (tablette : barre gauche préremplie).
 * Texte d'aide d'iOS (SPEC-menus §12.1). N'affecte QUE la disposition passée.
 */
@Composable
fun ToolbarCustomizeSheet(
    title: String,
    layout: ToolbarLayout,
    catalog: List<ToolCatalogEntry>,
    onLayout: (ToolbarLayout) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp,
            modifier = Modifier.testTag("toolbarCustomize")) {
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
                    // Destructif (rouge), isolé en fin de liste ; inutile si tout est déjà hors barres.
                    TextButton(
                        onClick = { onLayout(ToolbarLayout.EMPTY) },
                        enabled = !layout.isEmpty,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 4.dp).testTag("toolbarCustomize.allOff")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.toolbar_all_off))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onReset, modifier = Modifier.testTag("toolbarCustomize.reset")) {
                        Text(stringResource(R.string.common_reset))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss, modifier = Modifier.testTag("toolbarCustomize.done")) {
                        Text(stringResource(R.string.common_done))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
        // « Hors barres » en tête, comme le sélecteur iOS ; les puces passent à la
        // ligne si la fenêtre est étroite (téléphone en portrait).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val tag = "toolbarCustomize.${entry.id.rawId}"
            EdgeChip(stringResource(R.string.edge_none), cur == null, "$tag.none") { onLayout(layout.moved(entry.id, null)) }
            EdgeChip(stringResource(R.string.edge_top), cur == ToolbarEdge.TOP, "$tag.top") { onLayout(layout.moved(entry.id, ToolbarEdge.TOP)) }
            EdgeChip(stringResource(R.string.edge_bottom), cur == ToolbarEdge.BOTTOM, "$tag.bottom") { onLayout(layout.moved(entry.id, ToolbarEdge.BOTTOM)) }
            EdgeChip(stringResource(R.string.edge_left), cur == ToolbarEdge.LEFT, "$tag.left") { onLayout(layout.moved(entry.id, ToolbarEdge.LEFT)) }
            EdgeChip(stringResource(R.string.edge_right), cur == ToolbarEdge.RIGHT, "$tag.right") { onLayout(layout.moved(entry.id, ToolbarEdge.RIGHT)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EdgeChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
    )
}
