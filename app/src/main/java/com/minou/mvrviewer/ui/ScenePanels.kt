package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R
import kotlin.math.roundToInt

/**
 * Panneaux flottants de la scène ouverts (SPEC-menus-android §3.6–3.8) : Position
 * GPS, Plan importé, Réglage des étiquettes. Hissés dans SceneScreen pour que le
 * menu ⋯, les outils, les crochets DEBUG et la fin d'un import les ouvrent de la
 * même façon dans la 3D comme dans le plan. Chaque vue a SES panneaux : une
 * bascule 3D ↔ plan les referme ([ScenePanels.afterBaseChange]).
 */
/** Les trois panneaux flottants du bas-gauche. */
enum class ScenePanel { GEO, REF_PLAN, LABELS }

data class ScenePanels(
    val geo: Boolean = false,
    val refPlan: Boolean = false,
    val labels: Boolean = false,
    /**
     * Dernier panneau OUVERT : dessiné au premier plan. Les trois partagent le
     * coin bas-gauche ; sans cela, un panneau ouvert après un autre pouvait
     * s'ouvrir DERRIÈRE lui (ordre de composition fixe) et sembler ne pas réagir.
     */
    val front: ScenePanel? = null
) {
    fun isOpen(p: ScenePanel): Boolean = when (p) {
        ScenePanel.GEO -> geo
        ScenePanel.REF_PLAN -> refPlan
        ScenePanel.LABELS -> labels
    }

    private fun with(p: ScenePanel, open: Boolean): ScenePanels = when (p) {
        ScenePanel.GEO -> copy(geo = open)
        ScenePanel.REF_PLAN -> copy(refPlan = open)
        ScenePanel.LABELS -> copy(labels = open)
    }

    /** Ouvre [p] (ou le ramène devant s'il l'était déjà). */
    fun open(p: ScenePanel): ScenePanels = with(p, true).copy(front = p)

    fun close(p: ScenePanel): ScenePanels = with(p, false).let { if (it.front == p) it.copy(front = null) else it }

    /** Outil ancré : ouvre / referme (actif = jaune). */
    fun toggle(p: ScenePanel): ScenePanels = if (isOpen(p)) close(p) else open(p)

    /** Ordre d'empilement : le dernier ouvert devant les autres. */
    fun zIndex(p: ScenePanel): Float = if (front == p) 1f else 0f

    companion object {
        /** La vue de base change : les panneaux de l'ancienne vue se ferment (comme iOS, un état par vue). */
        fun afterBaseChange(): ScenePanels = ScenePanels()

        /** Crochets DEBUG `MVR_AUTO_GEOPANEL`, `MVR_AUTO_REFPLAN_PANEL`, `MVR_AUTO_LABELS_PANEL`. */
        fun fromHooks(geo: Boolean, refPlan: Boolean, labels: Boolean): ScenePanels =
            ScenePanels(geo = geo, refPlan = refPlan, labels = labels)
    }
}

/**
 * Cadres AFFICHÉS des panneaux flottants d'une vue (px racine), lus par la barre
 * ancrée gauche ([LeadingBarClearance]). Un état par panneau : seule la barre,
 * qui les lit, se recompose quand un panneau glisse.
 */
class PanelFrames {
    var geo by mutableStateOf(Rect.Zero)
    var refPlan by mutableStateOf(Rect.Zero)
    var labels by mutableStateOf(Rect.Zero)
    /** Modèles importés (3D) ou Export PDF (plan) : le 4e panneau du bas-gauche. */
    var other by mutableStateOf(Rect.Zero)

    /** Cadres des panneaux OUVERTS (un panneau fermé garde son dernier cadre, ignoré). */
    fun open(panels: ScenePanels, otherOpen: Boolean): List<Rect> = buildList {
        if (panels.geo) add(geo)
        if (panels.refPlan) add(refPlan)
        if (panels.labels) add(labels)
        if (otherOpen) add(other)
    }
}

// ---------------------------------------------------------------------------
// Place d'un panneau du bas-gauche (iOS `.mvrBottomLeadingPanelSlot`)
// ---------------------------------------------------------------------------

/** Hauteur de la barre de préhension de [MovablePanel] (comptée dans la borne). */
private val MOVABLE_BAR_HEIGHT = 30.dp

/**
 * Largeur des panneaux du bas-gauche. Imposée AUSSI à la colonne de [MovablePanel] :
 * sans elle, la barre de préhension (`fillMaxWidth`) s'étirait jusqu'au bord de
 * l'écran, bien au-delà du panneau (constat émulateur, tranche B).
 */
val FLOATING_PANEL_WIDTH = 264.dp

/**
 * Pose un panneau flottant DÉPLAÇABLE au coin bas-gauche, à sa place calculée
 * ([PanelPlacementMath.floatingPanelSlot]) : il MONTE au-dessus de ce qui occupe
 * le bas de sa colonne ([bottomObstacles] : fiche, bannière, barre d'affectation,
 * panneau 3 points, mention IGN lue sans sa montée…) et sa hauteur est BORNÉE sous
 * ce qui occupe le haut ([topObstacles], jamais au-dessus de [topLimitPx]). Tous
 * les cadres sont en px RACINE. La place naturelle est lue AVANT la montée (pas de
 * boucle) ; le décalage choisi au doigt ([MovablePanel]) s'ajoute par-dessus.
 * [content] reçoit la hauteur maximale de son contenu (null = pas de borne).
 */
@Composable
fun BoxScope.BottomLeadingPanel(
    id: PanelLayoutStore.PanelID,
    topLimitPx: Float,
    topObstacles: List<Rect>,
    bottomObstacles: List<Rect>,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 76.dp,
    /** Cadre racine AFFICHÉ (montée et glissé compris), pour la barre ancrée gauche. */
    onDisplayedBounds: ((Rect) -> Unit)? = null,
    content: @Composable (maxContentHeight: Dp?) -> Unit
) {
    val density = LocalDensity.current
    var natural by remember { mutableStateOf(Rect.Zero) }
    val slot = with(density) {
        // Jamais collé au haut de la zone utile : l'écart habituel en dessous.
        PanelPlacementMath.floatingPanelSlot(
            natural, topObstacles, bottomObstacles, topLimitPx + PanelPlacementMath.CORNER_STACK_GAP.dp.toPx(),
            gap = PanelPlacementMath.CORNER_STACK_GAP.dp.toPx(),
            minHeight = PanelPlacementMath.FLOATING_PANEL_MIN_HEIGHT.dp.toPx()
        )
    }
    val maxContent: Dp? = slot.maxHeight?.let { h ->
        with(density) { (h.toDp() - MOVABLE_BAR_HEIGHT).coerceAtLeast(48.dp) }
    }
    val lift = slot.lift
    MovablePanel(
        id = id,
        modifier = modifier.align(Alignment.BottomStart)
            .padding(start = 12.dp, bottom = bottomPadding)
            .width(FLOATING_PANEL_WIDTH)
            // Place NATURELLE (sans montée) : mesurée avant le décalage.
            .onGloballyPositioned { natural = it.anchorBounds() }
            .offset { IntOffset(0, -lift.roundToInt()) },
        onDisplayedBounds = onDisplayedBounds
    ) { content(maxContent) }
}

// ---------------------------------------------------------------------------
// Habillage commun des panneaux (fond blanc forcé, comme les panneaux existants)
// ---------------------------------------------------------------------------

internal val PANEL_INK = Color(0xFF111111)
internal val PANEL_SECONDARY = Color(0xFF666666)
internal val PANEL_WARNING = Color(0xFFEF6C00)
internal val PANEL_DESTRUCTIVE = Color(0xFFC62828)
internal val PANEL_ACCENT = Color(0xFF1E88E5)

/**
 * Corps d'un panneau flottant : en-tête (titre + croix discrète « Fermer »), puis
 * contenu qui DÉFILE ; la carte entière tient dans [maxContentHeight] (le panneau
 * ne mange jamais la vue qu'il règle). Fond blanc forcé : les couleurs des commandes sont explicites
 * (le thème de l'app est sombre, cf. `panelButtonColors`).
 */
@Composable
fun FloatingPanelCard(
    title: String,
    onClose: () -> Unit,
    maxContentHeight: Dp?,
    modifier: Modifier = Modifier,
    width: Dp = FLOATING_PANEL_WIDTH,
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = Color.White, contentColor = PANEL_INK,
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        shadowElevation = 8.dp,
        // Borne sur la carte ENTIÈRE (en-tête compris) : seul le contenu défile.
        modifier = modifier.width(width)
            .then(if (maxContentHeight != null) Modifier.heightIn(max = maxContentHeight) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close),
                        tint = Color(0xFF8A8A8E), modifier = Modifier.size(18.dp))
                }
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().padding(end = 8.dp)
                    .verticalScroll(rememberScrollState()),
                content = content
            )
        }
    }
}

/** Titre de section d'un panneau (« Affichage », « Calibration », « Apparence »…). */
@Composable
fun PanelSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = PANEL_SECONDARY,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp))
}

/** Séparateur d'un panneau (isole une action destructive). */
@Composable
fun PanelDivider() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0x22000000))
}

/** Bouton secondaire (contour) lisible sur fond blanc. */
@Composable
internal fun panelOutlinedColors() = ButtonDefaults.outlinedButtonColors(contentColor = PANEL_INK)

/** Bouton principal (plein) : une seule action principale par panneau. */
@Composable
internal fun panelPrimaryColors() = ButtonDefaults.buttonColors(
    containerColor = PANEL_ACCENT, contentColor = Color.White
)

/** Bouton texte destructif (rouge). */
@Composable
internal fun panelDestructiveColors() = ButtonDefaults.textButtonColors(contentColor = PANEL_DESTRUCTIVE)

/** Interrupteur lisible sur fond blanc (le thème sombre le rendrait pâle). */
@Composable
internal fun panelSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = PANEL_ACCENT, checkedThumbColor = Color.White,
    uncheckedTrackColor = Color(0xFFE0E0E0), uncheckedThumbColor = Color(0xFF8A8A8E),
    uncheckedBorderColor = Color(0xFFBDBDBD)
)

/** Curseur lisible sur fond blanc. */
@Composable
internal fun panelSliderColors() = SliderDefaults.colors(
    thumbColor = PANEL_ACCENT, activeTrackColor = PANEL_ACCENT, inactiveTrackColor = Color(0xFFD6D6D6)
)
