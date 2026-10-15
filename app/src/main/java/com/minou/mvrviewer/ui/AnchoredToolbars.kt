package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * DESCRIPTION UNIFIÉE d'un outil (N11) : une SEULE source consommée par les 4
 * barres ancrées et par le panneau « Barre d'outils de cette vue… » (catalogue).
 * [checked] non-null ⇒ BASCULE (reflète l'état RÉEL : cadre actif, solo actif…) ;
 * null ⇒ ACTION ponctuelle. [available] porte la visibilité conditionnelle (ex.
 * « Effacer la sélection » n'apparaît qu'avec une sélection). [onInvoke] APPLIQUE
 * l'action (une bascule flippe son état elle-même). Le menu ⋯ (MainMenuModel) a
 * ses propres entrées : un outil n'y est plus recopié (SPEC-menus-android §3.2,
 * la section « Outils » a disparu).
 */
data class ToolSpec(
    val id: ToolId,
    val label: String,
    val icon: ImageVector,
    val available: Boolean,
    val checked: Boolean?,
    val onInvoke: () -> Unit,
    val busy: Boolean = false
)

/** Entrée du catalogue affichée dans le panneau « Personnaliser » (id + libellé + icône). */
fun List<ToolSpec>.toCatalog(): List<ToolCatalogEntry> =
    map { ToolCatalogEntry(it.id, it.label, it.icon) }

/**
 * Rend les 4 barres ancrées d'après [layout], en filtrant [specs] au disponible.
 * Une barre vide n'est pas rendue (aucun encombrement). Chaque barre n'occupe que
 * la place de ses boutons, ancrée à son bord — le centre reste libre pour les
 * gestes de navigation (patron déjà validé : les overlays Compose ne captent que
 * leur propre zone au-dessus de la SceneView / du Canvas plan).
 */
@Composable
fun BoxScope.AnchoredToolbars(layout: ToolbarLayout, specs: List<ToolSpec>) {
    val byId = specs.associateBy { it.id }
    ToolbarEdge.entries.forEach { edge ->
        val items = layout.forEdge(edge).mapNotNull { byId[it] }.filter { it.available }
        if (items.isEmpty()) return@forEach
        val align = when (edge) {
            ToolbarEdge.TOP -> Alignment.TopCenter
            ToolbarEdge.BOTTOM -> Alignment.BottomStart
            ToolbarEdge.LEFT -> Alignment.CenterStart
            ToolbarEdge.RIGHT -> Alignment.CenterEnd
        }
        val mod = Modifier.align(align).padding(12.dp)
        if (edge == ToolbarEdge.TOP || edge == ToolbarEdge.BOTTOM) {
            Row(
                modifier = mod,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { items.forEach { ToolbarButton(it) } }
        } else {
            Column(
                modifier = mod,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) { items.forEach { ToolbarButton(it) } }
        }
    }
}

/**
 * Bouton d'outil rond (barres ancrées, rangée du haut, pile bas-droite) : jaune
 * plein et glyphe NOIR quand une bascule est active, TalkBack = libellé, état
 * coché porté par le bouton bascule.
 */
@Composable
internal fun ToolbarButton(s: ToolSpec, modifier: Modifier = Modifier) {
    val checked = s.checked
    if (checked != null) {
        // La bascule flippe son propre état dans onInvoke → on ignore la valeur
        // passée par onCheckedChange (checked reflète déjà l'état réel).
        // Outil ACTIF = surface d'identité : jaune plein, glyphe NOIR
        // (blanc sur jaune = 1,6:1, illisible — cf. theme/Color.kt).
        FilledIconToggleButton(checked = checked, onCheckedChange = { s.onInvoke() },
                               colors = com.minou.mvrviewer.ui.theme.accentToggleColors(), modifier = modifier) {
            ButtonContent(s)
        }
    } else {
        FilledIconButton(onClick = s.onInvoke, modifier = modifier) { ButtonContent(s) }
    }
}

@Composable
private fun ButtonContent(s: ToolSpec) {
    if (s.busy) CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
    else Icon(s.icon, contentDescription = s.label)
}
