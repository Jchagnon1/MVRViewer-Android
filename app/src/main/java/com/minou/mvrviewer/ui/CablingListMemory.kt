package com.minou.mvrviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Surlignage BREF d'une voie au retour du plan (SPEC-cablage-plan §2.4) : jaune
 * d'identité à 30 %, 1,8 s, puis fondu. L'opacité n'est lue qu'au DESSIN.
 */
@Stable
class CablingFlash {
    var rowId by mutableStateOf<String?>(null)
        private set
    val alpha = Animatable(0f)

    suspend fun flash(id: String) {
        rowId = id
        alpha.snapTo(0f)
        alpha.animateTo(0.3f, tween(200))
        delay(1800)
        alpha.animateTo(0f, tween(600))
        if (rowId == id) rowId = null
    }

    /** Opacité du surlignage de la ligne [id] (0 = rien). */
    fun alphaOf(id: String): Float = if (rowId == id) alpha.value else 0f
}

/** Morceau de la carte d'un distributeur : haut (en-tête), milieu, bas (dernière voie). */
internal enum class CardSeg { TOP, MIDDLE, BOTTOM }

/**
 * La carte d'un distributeur est découpée en LIGNES de liste (en-tête, phases,
 * voies) pour que chaque ligne repère ait sa clé et que la liste puisse défiler
 * jusqu'à elle (SPEC §7). Chaque morceau dessine sa part de la carte : fond,
 * bord de la couleur du distributeur (haut arrondi pour l'en-tête, bas arrondi
 * pour la dernière voie, côtés seuls au milieu), et le surlignage éventuel.
 */
internal fun Modifier.cardSegment(border: Color, fill: Color, seg: CardSeg, flash: () -> Float = { 0f }): Modifier =
    drawWithContent {
        val r = 14.dp.toPx()
        val sw = 2.dp.toPx()
        val ext = r + sw
        val top = if (seg == CardSeg.TOP) sw / 2f else -ext
        val bottom = if (seg == CardSeg.BOTTOM) size.height - sw / 2f else size.height + ext
        val tl = Offset(sw / 2f, top)
        val sz = Size(size.width - sw, bottom - top)
        val cr = CornerRadius(r, r)
        clipRect {
            drawRoundRect(fill, tl, sz, cr)
            val a = flash()
            if (a > 0f) drawRoundRect(AccentFill.copy(alpha = a), tl, sz, cr)
        }
        drawContent()
        clipRect { drawRoundRect(border, tl, sz, cr, style = Stroke(sw)) }
    }

/**
 * MÉMOIRE d'une liste du câblage (SPEC §2.4 et §7), branchée sur une `LazyColumn`
 * dont chaque ligne repère a pour CLÉ son identifiant ([CablingListRows]) :
 * 1. à l'APPARITION de la liste, la consigne de [CablingScreenMemoryState.takeRestore] :
 *    la voie travaillée sur le plan est ancrée en haut, puis — sur sa hauteur
 *    RÉELLE et la hauteur RÉELLEMENT visible — centrée si elle tient (hauteur
 *    + 24 dp ≤ zone visible ; en fin de liste, elle reste collée en bas,
 *    visible), et surlignée une fois ; une simple reprise remet
 *    la ligne repère en haut, sans surlignage ;
 * 2. au DÉFILEMENT, la position (tout en haut, ou la ligne en tête — un
 *    en-tête peut être repris lui-même sur Android) est retenue, jamais pendant
 *    une reprise. Le démontage de la liste n'efface rien.
 */
@Composable
internal fun CablingListMemoryEffects(
    listState: LazyListState,
    rows: List<CablingListRow>,
    memory: CablingScreenMemoryState,
    tab: CablingScreenTab,
    flash: CablingFlash
) {
    val density = LocalDensity.current
    val restoring = remember { BooleanArray(1) }
    val rowsNow by rememberUpdatedState(rows)

    LaunchedEffect(listState) {
        val r = memory.takeRestore(tab) ?: return@LaunchedEffect
        val index = CablingListRows.indexOf(rowsNow, r.rowId)
        if (index < 0) return@LaunchedEffect
        restoring[0] = true
        try {
            // 1er temps : ancrée EN HAUT (la ligne existe, son titre est visible).
            listState.scrollToItem(index)
            // 2e temps (~0,15 s) : hauteurs RÉELLES, puis centrage si elle tient.
            delay(150)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == r.rowId }
            val visible = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
            val anchor = r.settleAnchor(
                item?.size?.let { it / density.density },
                visible.takeIf { it > 0 }?.let { it / density.density }
            )
            if (anchor == CablingScrollRestore.Anchor.CENTER && item != null) {
                // Décalage RÉEL de la ligne : sur le dernier écran elle n'est pas
                // en haut ; la liste s'arrête à sa fin, la ligne reste visible.
                listState.animateScrollBy(CablingScrollRestore.centerScrollDelta(
                    item.offset.toFloat(), item.size.toFloat(), visible.toFloat()))
            }
        } finally {
            restoring[0] = false
        }
        if (r.highlight) flash.flash(r.rowId)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (first, off) ->
                if (restoring[0]) return@collect
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo.mapNotNull { it ->
                    val key = it.key as? String ?: return@mapNotNull null
                    Triple(key, it.offset.toFloat(), (it.offset + it.size).toFloat())
                }
                val h = (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding).toFloat()
                val pos = CablingListRows.positionOf(
                    visible, h, isAtListTop = first == 0 && off <= density.density,
                    layoutWidth = info.viewportSize.width.toFloat()
                )
                if (pos != null) memory.remember(pos, tab)
            }
    }
}

/** Une action d'une voie : symbole + libellé (complet ou court). */
internal data class CablingRowAction(
    val icon: ImageVector,
    val full: String,
    val short: String,
    val tint: Color,
    val onClick: () -> Unit
)

/**
 * Les actions d'une voie sur UNE ligne, dans l'ordre iOS (« Sélectionner sur le
 * plan », « Affecter des projecteurs », « Vider le circuit / le départ ») : les
 * libellés COMPLETS s'ils tiennent sur la largeur, sinon les COURTS (« Sur le
 * plan », « Affecter », « Vider »), mêmes symboles, même ordre (`ViewThatFits`).
 */
@Composable
internal fun CablingRowActions(actions: List<CablingRowAction>, modifier: Modifier = Modifier) {
    @Composable
    fun line(compact: Boolean) = Row(verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { a ->
            TextButton(onClick = a.onClick,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Icon(a.icon, contentDescription = null, tint = a.tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (compact) a.short else a.full, color = a.tint, maxLines = 1, softWrap = false)
            }
        }
    }
    SubcomposeLayout(modifier) { c ->
        val full = subcompose("full") { line(false) }.map { it.measure(Constraints()) }
        val fullW = full.maxOfOrNull { it.width } ?: 0
        val chosen = if (!CablingActionLabels.useCompact(fullW, c.maxWidth)) full
            else subcompose("short") { line(true) }.map { it.measure(c.copy(minWidth = 0)) }
        val w = (chosen.maxOfOrNull { it.width } ?: 0).coerceIn(c.minWidth, c.maxWidth)
        val h = (chosen.maxOfOrNull { it.height } ?: 0).coerceIn(c.minHeight, c.maxHeight)
        layout(w, h) { chosen.forEach { it.placeRelative(0, 0) } }
    }
}
