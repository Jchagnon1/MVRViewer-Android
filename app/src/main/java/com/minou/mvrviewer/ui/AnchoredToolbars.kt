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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

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
 * Ce que la barre ancrée GAUCHE doit éviter (SPEC-menus-android §3.13) : cadres
 * RACINE (px) de ce qui occupe sa colonne — légende du plan, panneaux flottants
 * ouverts (à leur place AFFICHÉE), fiche / bannière, barre d'affectation,
 * panneau 3 points, bloc d'état… — et les bornes de la zone utile. Des
 * FONCTIONS : les cadres sont lus dans la composition de la barre seule (un
 * panneau glissé ne recompose pas toute la vue à chaque image).
 */
class LeadingBarClearance(
    val occupants: () -> List<Rect>,
    val topLimitPx: () -> Float,
    val bottomLimitPx: () -> Float
)

/**
 * Ce qui occupe la RANGÉE DU HAUT, où se pose la barre ancrée du HAUT
 * (SPEC-menus-android §3.13) : cadres RACINE (px) de ← et du groupe de droite
 * `[cadre] [multi] [⋯]`, et cadre RACINE de la vue qui porte les barres. Des
 * FONCTIONS, lues au placement seulement (la vue ne se recompose pas quand la
 * rangée bouge).
 */
class TopBarClearance(
    val rowOccupants: () -> List<Rect>,
    val container: () -> Rect
)

/**
 * Rend les 4 barres ancrées d'après [layout], en filtrant [specs] au disponible.
 * Une barre vide n'est pas rendue (aucun encombrement). Chaque barre n'occupe que
 * la place de ses boutons, ancrée à son bord — le centre reste libre pour les
 * gestes de navigation (patron déjà validé : les overlays Compose ne captent que
 * leur propre zone au-dessus de la SceneView / du Canvas plan). La barre GAUCHE
 * (préremplie sur tablette) se déplace pour ne rien cacher ([leading],
 * [PanelPlacementMath.leadingBarOffset]) ; la barre du HAUT se pose dans la
 * rangée du haut, entre ← et le groupe de droite, et défile si elle est trop
 * longue ([top], [PanelPlacementMath.topBarSlot]).
 */
@Composable
fun BoxScope.AnchoredToolbars(
    layout: ToolbarLayout,
    specs: List<ToolSpec>,
    leading: LeadingBarClearance? = null,
    top: TopBarClearance? = null
) {
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
        if (edge == ToolbarEdge.TOP && top != null) {
            TopBar(items, top)
        } else if (edge == ToolbarEdge.TOP || edge == ToolbarEdge.BOTTOM) {
            Row(
                modifier = mod,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { items.forEach { DockedToolButton(it) } }
        } else if (edge == ToolbarEdge.LEFT && leading != null) {
            LeadingBar(items, leading, mod)
        } else {
            Column(
                modifier = mod,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) { items.forEach { DockedToolButton(it) } }
        }
    }
}

/** Barre gauche qui évite ce qui occupe sa colonne (montée / descente, sinon à droite). */
@Composable
private fun LeadingBar(items: List<ToolSpec>, leading: LeadingBarClearance, modifier: Modifier) {
    val density = LocalDensity.current
    // Cadre SANS décalage (mesuré avant lui) et largeur de la zone (bord droit).
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var rootWidth by remember { mutableStateOf(Float.POSITIVE_INFINITY) }
    val target = with(density) {
        PanelPlacementMath.leadingBarOffset(
            anchor, leading.occupants(), leading.topLimitPx(), leading.bottomLimitPx(),
            maxX = rootWidth, gap = PanelPlacementMath.CORNER_STACK_GAP.dp.toPx(), edgeMargin = 16.dp.toPx()
        )
    }
    val dy by animateFloatAsState(target.dy, tween(200), label = "leadingBarDy")
    val dx by animateFloatAsState(target.dx, tween(200), label = "leadingBarDx")
    Column(
        modifier = modifier
            .onGloballyPositioned {
                anchor = it.anchorBounds()
                rootWidth = it.findRootCoordinates().size.width.toFloat()
            }
            // Forme LAMBDA : l'animation ne relance que le placement.
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .testTag("dockedBar.left"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) { items.forEach { DockedToolButton(it) } }
}

/**
 * Barre du HAUT posée DANS la rangée du haut ([PanelPlacementMath.topBarSlot]) :
 * alignée sur ses boutons, entre ← et le groupe de droite, centrée si elle tient ;
 * plus longue que la place, elle défile horizontalement. Avant, centrée à 12 dp
 * du haut, elle passait sous le groupe de droite et sur la pastille « Vue »
 * (un outil caché, plus touchable — relecture du rangement). Le placement lit les
 * cadres mesurés dans la phase de PLACEMENT seulement (pas de recomposition).
 */
@Composable
private fun BoxScope.TopBar(items: List<ToolSpec>, top: TopBarClearance) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .layout { measurable, constraints ->
                val box = top.container()
                val width = if (box.width > 0f) box.width else constraints.maxWidth.toFloat()
                val slot = PanelPlacementMath.topBarSlot(
                    containerWidth = width,
                    rowOccupants = top.rowOccupants().map { it.translate(-box.left, -box.top) },
                    gap = PanelPlacementMath.CORNER_STACK_GAP.dp.toPx(),
                    minWidth = SCENE_ROUND.toPx(),
                    margin = 12.dp.toPx()
                )
                val maxW = slot.maxWidth.toInt().coerceIn(0, constraints.maxWidth)
                val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxW, minHeight = 0))
                val x = slot.left(p.width.toFloat(), center = width / 2f).roundToInt()
                val y = slot.top.roundToInt()
                // Boîte transparente aux gestes (aucun pointerInput hors des boutons).
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else x + p.width
                val h = (y + p.height).let { if (constraints.hasBoundedHeight) minOf(it, constraints.maxHeight) else it }
                layout(w, h) { p.place(x, y) }
            }
            .testTag("dockedBar.top")
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { items.forEach { DockedToolButton(it) } }
}

/**
 * Bouton d'une barre ancrée : nommé pour TalkBack par le TITRE de l'outil (même
 * pendant l'indicateur d'attente), état coché porté par la bascule, identifiant
 * de test `dockedTool.<rawId>` (iOS `dockedTool.<rawValue>`, SPEC-menus §12.4).
 */
@Composable
private fun DockedToolButton(s: ToolSpec) =
    // Même gabarit que les boutons permanents (rond 48 dp, §3.1 ; iOS 44 pt).
    ToolbarButton(s, Modifier.size(SCENE_ROUND).testTag("dockedTool.${s.id.rawId}"))

/**
 * Bouton d'outil rond (barres ancrées, rangée du haut, pile bas-droite) : neutre,
 * jaune plein et glyphe NOIR seulement quand une bascule est active, TalkBack = libellé (posé sur
 * le bouton lui-même : l'indicateur d'attente n'a pas de nom), état coché porté
 * par le bouton bascule (case à cocher).
 */
@Composable
internal fun ToolbarButton(s: ToolSpec, modifier: Modifier = Modifier) {
    val checked = s.checked
    val named = modifier.semantics { contentDescription = s.label }
    if (checked != null) {
        // La bascule flippe son propre état dans onInvoke → on ignore la valeur
        // passée par onCheckedChange (checked reflète déjà l'état réel).
        // Outil ACTIF = surface d'identité : jaune plein, glyphe NOIR
        // (blanc sur jaune = 1,6:1, illisible — cf. theme/Color.kt).
        FilledIconToggleButton(checked = checked, onCheckedChange = { s.onInvoke() },
                               colors = com.minou.mvrviewer.ui.theme.accentToggleColors(), modifier = named) {
            ButtonContent(s)
        }
    } else {
        // Action : rond NEUTRE (le jaune est réservé à une bascule active, DESIGN_UI §5).
        FilledIconButton(onClick = s.onInvoke, colors = neutralRoundButtonColors(), modifier = named) { ButtonContent(s) }
    }
}

@Composable
private fun ButtonContent(s: ToolSpec) {
    if (s.busy) CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
    else Icon(s.icon, contentDescription = null)
}
