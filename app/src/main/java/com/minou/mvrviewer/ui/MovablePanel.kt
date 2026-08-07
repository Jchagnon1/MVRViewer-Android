package com.minou.mvrviewer.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Rend un panneau flottant DÉPLAÇABLE et RÉDUCTIBLE « comme une fenêtre » —
 * parité `.mvrMovablePanel` iOS (retour testeur : « les menus qui prennent
 * beaucoup de place, il faudrait pouvoir les déplacer et les réduire »).
 *
 * Une BARRE DE PRÉHENSION coiffe le panneau : poignée à gauche, chevron de
 * repli à droite. Réduit, il ne reste que cette barre. Position et repli sont
 * PERSISTÉS par appareil ([PanelLayoutStore]), et le décalage est BORNÉ à
 * chaque placement ([PanelPlacementMath]) : une rotation d'écran ou une
 * préférence corrompue ramène le panneau dans l'écran d'elle-même.
 *
 * Décisions qui comptent (leçons du portage iOS) :
 * - le geste de déplacement vit sur la BARRE seulement — posé sur tout le
 *   panneau il volerait les curseurs et le défilement du contenu ; le chevron
 *   est un enfant cliquable de la barre, il reçoit ses taps AVANT le drag ;
 * - `change.consume()` dans le drag : sans lui, le pan/zoom du plan (ou de la
 *   SceneView) sous le panneau reconnaîtrait le même glissé ;
 * - `Modifier.offset` en forme LAMBDA : la position vive change à chaque
 *   frame de glissé, la forme valeur re-composerait tout le panneau à chaque
 *   événement tactile (piège Compose documenté du projet) ;
 * - l'ancre est mesurée par `onGloballyPositioned` posé AVANT l'offset dans la
 *   chaîne : il rapporte la position SANS décalage, aucune circularité.
 */
@Composable
fun MovablePanel(
    id: PanelLayoutStore.PanelID,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    var placement by remember { mutableStateOf(PanelLayoutStore.load(ctx, id)) }
    var live by remember { mutableStateOf(Offset.Zero) }
    var anchor by remember { mutableStateOf(Offset.Zero) }
    var panelSize by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(Offset.Zero) }
    // Bord HAUT du panneau DÉPLIÉ. Au repli, un panneau ancré en BAS voit son
    // layout remonter le bord bas (hauteur réduite à la barre) : la barre
    // « descendait ». On compense pour qu'elle reste où elle était — le
    // panneau se replie vers le HAUT quel que soit son ancrage (parité iOS,
    // retour testeur).
    var expandedTop by remember { mutableStateOf(0f) }

    fun clamped(dx: Float, dy: Float): Pair<Float, Float> {
        val shift = if (placement.collapsed) expandedTop - anchor.y else 0f
        return PanelPlacementMath.clamp(
            dx, dy + shift,
            anchor.x, anchor.y, panelSize.x, panelSize.y,
            container.x, container.y
        )
    }

    Column(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val root = coords.findRootCoordinates()
                container = Offset(root.size.width.toFloat(), root.size.height.toFloat())
                panelSize = Offset(coords.size.width.toFloat(), coords.size.height.toFloat())
                // Position SANS le décalage appliqué : l'offset est posé APRÈS
                // ce modificateur dans la chaîne, il ne change pas ce repère.
                anchor = coords.positionInRoot()
                if (!placement.collapsed) expandedTop = anchor.y
            }
            .offset {
                val (dx, dy) = clamped(placement.dx + live.x, placement.dy + live.y)
                IntOffset(dx.roundToInt(), dy.roundToInt())
            }
    ) {
        // ---- Barre de préhension ----
        Surface(
            color = Color.White, contentColor = Color(0xFF111111),
            shape = if (placement.collapsed) RoundedCornerShape(12.dp)
                    else RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            shadowElevation = 8.dp,
            // Replié, la colonne n'a plus que la barre : une largeur plancher
            // garde une poignée digne de ce nom.
            modifier = Modifier.fillMaxWidth().widthIn(min = 170.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(30.dp)
                    .pointerInput(id) {
                        detectDragGestures(
                            onDrag = { change, amount ->
                                change.consume()
                                live += Offset(amount.x, amount.y)
                            },
                            onDragEnd = {
                                val (dx, dy) = clamped(placement.dx + live.x,
                                                       placement.dy + live.y)
                                placement = placement.copy(dx = dx, dy = dy)
                                live = Offset.Zero
                                PanelLayoutStore.save(ctx, id, placement)
                            },
                            // Le système peut ANNULER un geste (appel entrant,
                            // rotation en plein glissé) : on replie la
                            // translation vive comme un lâcher normal, sinon
                            // le panneau resterait déporté sans être persisté.
                            onDragCancel = {
                                val (dx, dy) = clamped(placement.dx + live.x,
                                                       placement.dy + live.y)
                                placement = placement.copy(dx = dx, dy = dy)
                                live = Offset.Zero
                                PanelLayoutStore.save(ctx, id, placement)
                            }
                        )
                    }
            ) {
                Icon(
                    Icons.Filled.DragHandle, contentDescription = null,
                    tint = Color(0xFF9E9E9E),
                    modifier = Modifier.padding(start = 10.dp).size(16.dp)
                )
                Box(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        placement = placement.copy(collapsed = !placement.collapsed)
                        PanelLayoutStore.save(ctx, id, placement)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (placement.collapsed) Icons.Filled.ExpandMore
                        else Icons.Filled.ExpandLess,
                        contentDescription = null, tint = Color(0xFF9E9E9E),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        // ---- Le panneau lui-même (chrome existant inchangé) ----
        if (!placement.collapsed) content()
    }
}
