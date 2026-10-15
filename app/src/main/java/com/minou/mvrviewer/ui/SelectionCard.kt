package com.minou.mvrviewer.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.MvrSceneObject

// =============================================================================
// FICHE DE SÉLECTION ET BANNIÈRE MULTI (SPEC-menus-android §3.9, tranche D) — UNE
// fiche et UNE bannière, posées en bas de la 3D ET du plan. Construites par
// SceneScreen (qui détient le patch, la conso et la fiche patch) et fournies aux
// deux vues par [SelectionCardHost] : aucune n'est écrite deux fois. Ce qu'elles
// disent et leur forme sont décidés dans SelectionCardRules (pur, testé).
// =============================================================================

/**
 * Ce que la scène fournit aux deux vues : la fiche d'UN projecteur et la bannière
 * de la multi-sélection. [card] : `onClose` désélectionne ; [banner] : `onClear`
 * vide la sélection.
 */
class SelectionCardHost(
    val card: @Composable (obj: MvrSceneObject, style: SelectionCardLayout.Style, onClose: () -> Unit) -> Unit,
    val banner: @Composable (count: Int, style: SelectionCardLayout.Style, onClear: () -> Unit) -> Unit
)

/** Texte d'une ligne de fiche (ressource simple ou pluriel). */
@Composable
internal fun CardText.text(): String = when (this) {
    is CardText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is CardText.Plural -> pluralStringResource(id, count, *args.toTypedArray())
}

private val CARD_SHAPE = RoundedCornerShape(14.dp)
private val CARD_CLOSE_TINT = Color(0xFF8A8A8E)
/** Conso connue : orange foncé, lisible sur le blanc de la fiche (iOS : orange). */
private val CARD_POWER_INK = Color(0xFFB45309)
/** Jeton principal « Patch » : jaune d'identité, encre NOIRE (jamais de blanc sur jaune). */
private val CARD_ACCENT_FILL = Color(0xFFFFC400)

/**
 * Pose la fiche / la bannière en bas, centrée, au-dessus d'une barre ancrée en
 * bas ([bottom]). [onBounds] reçoit son cadre (px racine) : obstacle de la pile
 * bas-droite, des panneaux du bas-gauche et de la mention IGN, et repère du
 * « projecteur gardé visible ».
 */
@Composable
fun BoxScope.SelectionCardSlot(bottom: Dp, onBounds: (Rect) -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = bottom)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) },
        contentAlignment = Alignment.BottomCenter
    ) { content() }
}

/** Marge basse de la fiche : contre le bas, ou au-dessus d'une barre ancrée en bas. */
fun selectionCardBottom(layout: ToolbarLayout, specs: List<ToolSpec>): Dp {
    val byId = specs.associateBy { it.id }
    return if (layout.bottom.mapNotNull { byId[it] }.any { it.available }) 76.dp else 16.dp
}

/** Corps commun : fond blanc forcé (comme les panneaux), ombre, touchers ABSORBÉS. */
@Composable
private fun CardSurface(modifier: Modifier, tag: String, content: @Composable () -> Unit) {
    Surface(
        color = Color.White, contentColor = PANEL_INK, shape = CARD_SHAPE, shadowElevation = 6.dp,
        // Toucher la fiche ne fait RIEN : ni fermeture, ni sélection du projecteur
        // dessous (le plan et la 3D ne reçoivent pas le geste).
        modifier = modifier.testTag(tag).pointerInput(Unit) { detectTapGestures { } }
    ) { content() }
}

/**
 * Fiche d'UN projecteur (§3.9) : en-tête « Projecteur — Nom » + croix « Fermer »,
 * détails (calque, N°, adresse DMX), conso + « C'est bon », jeton principal
 * « Patch ». Pan / Tilt 180°, Étiquette ▾ et la portée sont ABSENTS tant que ces
 * fonctions n'existent pas sur Android (Q3, §8 D5). Version COMPACTE en paysage :
 * une rangée (titre court · Patch · Détails · croix), « Détails » déplie au-dessus.
 */
@Composable
fun SelectionCard(
    model: SelectionCardModel,
    style: SelectionCardLayout.Style,
    onClose: () -> Unit,
    onPatch: () -> Unit,
    onConfirmPower: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(R.string.card_title_fmt, stringResource(R.string.card_kind_fixture), model.name)
    when (style) {
        SelectionCardLayout.Style.REGULAR -> CardSurface(
            modifier.widthIn(max = SelectionCardLayout.REGULAR_MAX_WIDTH_DP.dp).fillMaxWidth(), "selectionCard"
        ) {
            Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    CloseButton(onClose, R.string.common_close, "selectionCard.close")
                }
                Column(Modifier.padding(end = 10.dp).testTag("selectionCard.details")) {
                    model.details.forEach { line ->
                        Text(line.text(), style = MaterialTheme.typography.bodySmall, color = PANEL_SECONDARY)
                    }
                }
                PowerRow(model.power, onConfirmPower, Modifier.padding(top = 2.dp, end = 10.dp))
                Row(Modifier.padding(top = 2.dp)) { PatchChip(onPatch, compact = false) }
            }
        }
        SelectionCardLayout.Style.COMPACT -> {
            // Replié à chaque nouvelle sélection (clé d'instance).
            var showDetails by remember(model.key) { mutableStateOf(false) }
            CardSurface(modifier, "selectionCard") {
                // Largeur = la plus large des rangées : dépliés, les détails élargissent la
                // fiche et la croix reste au bout de la rangée.
                Column(Modifier.width(IntrinsicSize.Max).padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)) {
                    if (showDetails) {
                        Column(Modifier.padding(top = 4.dp, end = 10.dp, bottom = 2.dp)) {
                            if (model.details.isNotEmpty()) {
                                Text(model.details.map { it.text() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = PANEL_SECONDARY,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            PowerRow(model.power, onConfirmPower, Modifier.padding(top = 2.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(SelectionCardLayout.shortTitle(title), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            // TalkBack lit le titre complet (« Projecteur — Wash 101 »).
                            modifier = Modifier.weight(1f).semantics { contentDescription = title })
                        Spacer(Modifier.width(10.dp))
                        PatchChip(onPatch, compact = true)
                        TextButton(
                            onClick = { showDetails = !showDetails },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = PANEL_INK),
                            modifier = Modifier.testTag("selectionCard.details").semantics { selected = showDetails }
                        ) {
                            Text(stringResource(R.string.card_details), style = MaterialTheme.typography.labelLarge)
                            Icon(if (showDetails) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        CloseButton(onClose, R.string.common_close, "selectionCard.close")
                    }
                }
            }
        }
    }
}

/**
 * Bannière de la multi-sélection, la MÊME dans la 3D et le plan : « n projecteurs
 * sélectionnés » · ✕ « Vider la sélection ». (« Éditer le patch » et
 * « Étiquette ▾ » d'iOS sont absents : ni patch multiple ni côté d'étiquette sur
 * Android, Q3.) Compacte en paysage : elle épouse son contenu.
 */
@Composable
fun MultiSelectionBanner(
    count: Int,
    style: SelectionCardLayout.Style,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val width = if (style == SelectionCardLayout.Style.REGULAR)
        Modifier.widthIn(max = SelectionCardLayout.REGULAR_MAX_WIDTH_DP.dp).fillMaxWidth() else Modifier
    CardSurface(modifier.then(width), "multiSelectionBanner") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(start = 14.dp, end = 2.dp)
        ) {
            Text(pluralStringResource(R.plurals.sel_fixtures_selected, count, count),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            CloseButton(onClear, R.string.banner_clear_selection, "multiSelectionBanner.clear")
        }
    }
}

/** Croix discrète (TalkBack : [descriptionRes]). */
@Composable
private fun CloseButton(onClick: () -> Unit, descriptionRes: Int, tag: String) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp).testTag(tag)) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(descriptionRes),
            tint = CARD_CLOSE_TINT, modifier = Modifier.size(18.dp))
    }
}

/** Jeton principal « Patch » : ouvre la fiche patch de CE projecteur (enregistrer : Pro). */
@Composable
private fun PatchChip(onClick: () -> Unit, compact: Boolean) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = CARD_ACCENT_FILL, contentColor = PANEL_INK),
        contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                         else PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        modifier = Modifier.heightIn(min = if (compact) 34.dp else 40.dp).testTag("selectionCard.patch")
    ) {
        Icon(Icons.Filled.Tag, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.patch_title), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Ligne « Conso » : « 450 W · 3 votes » / « 450 W · GDTF » + « ✓ C'est bon » (vote
 * de la valeur affichée sans la retaper ; Pro, garde « renseigner une
 * consommation »), ou « Conso max : à saisir ».
 */
@Composable
private fun PowerRow(power: CardPower, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val label = power.label
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.heightIn(min = 32.dp)) {
        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(15.dp),
            tint = if (label != null) CARD_POWER_INK else PANEL_SECONDARY)
        Spacer(Modifier.width(4.dp))
        Text(
            label?.text() ?: stringResource(R.string.card_power_to_enter),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (label != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (label != null) CARD_POWER_INK else PANEL_SECONDARY,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (power.canConfirm) {
            TextButton(
                onClick = onConfirm,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = PANEL_ACCENT),
                modifier = Modifier.heightIn(min = 32.dp).testTag("selectionCard.powerConfirm")
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.fx_power_confirm), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
