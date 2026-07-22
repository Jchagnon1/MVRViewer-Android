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
 * DESCRIPTION UNIFIÉE d'un outil (N11) : une SEULE source consommée à la fois par
 * les 4 barres ancrées ET par la section « Outils » du menu déroulant (N10) —
 * supprime l'ancienne duplication barre/menu. [checked] non-null ⇒ BASCULE (reflète
 * l'état RÉEL : rectangle actif, solo actif…) ; null ⇒ ACTION ponctuelle. [available]
 * porte la visibilité conditionnelle actuelle (ex. « Effacer la sélection » n'apparaît
 * qu'avec une sélection). [inMenu] = présent dans la section « Outils » du menu (les
 * outils qui ont DÉJÀ une bascule dédiée ailleurs dans le menu — étiquettes,
 * satellite… — sont à false pour ne pas doublonner). [onInvoke] APPLIQUE l'action
 * (une bascule flippe son état elle-même), donc la barre et le menu partagent le
 * même comportement.
 */
data class ToolSpec(
    val id: ToolId,
    val label: String,
    val icon: ImageVector,
    val available: Boolean,
    val checked: Boolean?,
    val onInvoke: () -> Unit,
    val busy: Boolean = false,
    val inMenu: Boolean = true
)

/** Descripteurs → items de la section « Outils » du menu (ordre préservé). */
fun List<ToolSpec>.toMenuTools(): List<MenuTool> =
    filter { it.available && it.inMenu }.map { s ->
        val c = s.checked
        if (c != null) MenuTool.Toggle(s.label, s.icon, c, s.onInvoke)
        else MenuTool.Action(s.label, s.icon, s.onInvoke)
    }

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
 * BARRE D'OUTILS FLOTTANTE historique (défaut, modèle iOS N11). Rend en bas-gauche
 * — exactement l'ancienne barre bas-gauche remplacée par les barres ancrées — les
 * outils « permanents » [permanentIds] de la vue, dans leur ordre, en FILTRANT :
 *  1. ceux indisponibles (`available == false`) ;
 *  2. ceux DÉJÀ placés dans une barre ancrée ([ToolbarLayout.allAssigned]) — GATING
 *     identique à iOS : un outil docké disparaît de la flottante (jamais en double).
 * Vide → rien n'est rendu. Comme les barres ancrées, elle ne capte que ses boutons
 * (le reste du Box laisse passer les gestes vers la SceneView / le Canvas plan).
 */
@Composable
fun BoxScope.FloatingToolbar(
    permanentIds: List<ToolId>,
    specs: List<ToolSpec>,
    layout: ToolbarLayout
) {
    val byId = specs.associateBy { it.id }
    val assigned = layout.allAssigned()
    val items = permanentIds.mapNotNull { byId[it] }
        .filter { it.available && it.id !in assigned }
    if (items.isEmpty()) return
    Row(
        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { items.forEach { ToolbarButton(it) } }
}

@Composable
private fun ToolbarButton(s: ToolSpec) {
    val checked = s.checked
    if (checked != null) {
        // La bascule flippe son propre état dans onInvoke → on ignore la valeur
        // passée par onCheckedChange (checked reflète déjà l'état réel).
        FilledIconToggleButton(checked = checked, onCheckedChange = { s.onInvoke() }) {
            ButtonContent(s)
        }
    } else {
        FilledIconButton(onClick = s.onInvoke) { ButtonContent(s) }
    }
}

@Composable
private fun ButtonContent(s: ToolSpec) {
    if (s.busy) CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
    else Icon(s.icon, contentDescription = s.label)
}
