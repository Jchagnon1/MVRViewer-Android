package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R

/** Sections du panneau « Réglage des étiquettes », dans l'ordre (SPEC-menus-android §3.8). */
enum class LabelSettingsSection { SHOW, CONTENT, APPEARANCE, PLACEMENT }

/** Lignes de la section « Apparence ». */
enum class LabelAppearanceRow { SIZE, OFFSET, HIDE_WHEN_ZOOMED_OUT }

/**
 * Règles PURES du panneau d'étiquettes, testées (`LabelSettingsSectionsTest`).
 * « Placement sur le plan », « Blocs séparés » et « Masquer les étiquettes trop
 * dézoomées » n'existent que dans le PLAN : sans effet en 3D (ils n'y sont lus
 * par aucun rendu, §2.3), ils quittent la 3D (A27).
 */
object LabelSettingsSections {
    fun of(surface: ProjectSurface): List<LabelSettingsSection> = buildList {
        add(LabelSettingsSection.SHOW)
        add(LabelSettingsSection.CONTENT)
        add(LabelSettingsSection.APPEARANCE)
        if (surface == ProjectSurface.PLAN) add(LabelSettingsSection.PLACEMENT)
    }

    fun appearance(surface: ProjectSurface): List<LabelAppearanceRow> = buildList {
        add(LabelAppearanceRow.SIZE)
        add(LabelAppearanceRow.OFFSET)
        if (surface == ProjectSurface.PLAN) add(LabelAppearanceRow.HIDE_WHEN_ZOOMED_OUT)
    }

    /**
     * Crans PERSISTÉS et synchronisés (§8 D9) : taille 0,7 · 1 · 1,4 et écart
     * 0,5 · 1 · 2 — les valeurs des anciens cycles du menu, inchangées.
     */
    val SIZE_STEPS = listOf(0.7f, 1f, 1.4f)
    val OFFSET_STEPS = listOf(0.5f, 1f, 2f)

    /** Cran affiché pour une valeur relue (mêmes seuils que les anciens cycles). */
    fun sizeIndex(v: Float): Int = when {
        v <= 0.75f -> 0
        v >= 1.3f -> 2
        else -> 1
    }

    fun offsetIndex(v: Float): Int = when {
        v <= 0.6f -> 0
        v >= 1.6f -> 2
        else -> 1
    }

    /** « Blocs séparés » : une case par champ AFFICHÉ, dans l'ordre des champs. */
    fun separateBlockFields(fields: Set<LabelContent>): List<LabelContent> =
        LabelContent.entries.filter { it in fields }

    /**
     * Coche / décoche un champ. Un champ retiré ne laisse pas derrière lui un
     * « bloc séparé » invisible qui ressusciterait au recochage.
     * @return (champs, champs détachés)
     */
    fun toggleField(
        fields: Set<LabelContent>, detached: Set<LabelContent>, c: LabelContent
    ): Pair<Set<LabelContent>, Set<LabelContent>> =
        if (c in fields) (fields - c) to (detached - c) else (fields + c) to detached
}

/**
 * Actions propres au PLAN (section « Placement sur le plan »), fournies par la vue
 * plan qui détient les étiquettes saisies et leurs décalages. null = 3D.
 */
class LabelPlacementActions(
    /** « Étiquettes de la sélection » ; null = grisé (pas de sélection). */
    val onLabelsOfSelection: (() -> Unit)?,
    /** « Étiquettes du même type (ce calque) » ; null = grisé (aucune étiquette saisie). */
    val onLabelsSameType: (() -> Unit)?,
    /** Nombre d'étiquettes saisies : « Désactiver (%d) » s'il y en a. */
    val armedCount: Int,
    val onTurnOff: () -> Unit,
    /** « Blocs séparés » : la vue relâche les étiquettes saisies au changement. */
    val onSetDetached: (Set<LabelContent>) -> Unit,
    val onResetOffsets: () -> Unit
)

/**
 * Panneau « Réglage des étiquettes » (SPEC-menus-android §3.8), même titre dans
 * les deux vues. Tout est gratuit. Agit directement sur le [SceneOptions] partagé :
 * chaque case se voit tout de suite sur les étiquettes derrière (une fenêtre ne
 * cache jamais ce qu'elle règle).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelSettingsPanel(
    options: SceneOptions,
    surface: ProjectSurface,
    placement: LabelPlacementActions?,
    onClose: () -> Unit,
    maxContentHeight: Dp?
) {
    FloatingPanelCard(
        title = stringResource(R.string.tool_label_settings), onClose = onClose,
        maxContentHeight = maxContentHeight, tag = "labelsPanel"
    ) {
        for (section in LabelSettingsSections.of(surface)) when (section) {
            LabelSettingsSection.SHOW ->
                PanelSwitchRow(stringResource(R.string.opt_show_labels), checked = options.showLabels) {
                    options.showLabels = it
                }
            LabelSettingsSection.CONTENT -> {
                PanelSectionTitle(stringResource(R.string.opt_label_content))
                LabelContent.entries.forEach { c ->
                    PanelCheckRow(stringResource(c.labelRes), c in options.labelFields) {
                        val (f, d) = LabelSettingsSections.toggleField(options.labelFields, options.labelDetached, c)
                        options.labelFields = f
                        options.labelDetached = d
                    }
                }
            }
            LabelSettingsSection.APPEARANCE -> {
                PanelSectionTitle(stringResource(R.string.labels_section_appearance))
                for (row in LabelSettingsSections.appearance(surface)) when (row) {
                    LabelAppearanceRow.SIZE -> StepSegments(
                        stringResource(R.string.labels_size),
                        listOf(stringResource(R.string.size_small), stringResource(R.string.size_medium),
                            stringResource(R.string.size_large)),
                        LabelSettingsSections.sizeIndex(options.labelSize)
                    ) { options.labelSize = LabelSettingsSections.SIZE_STEPS[it] }
                    LabelAppearanceRow.OFFSET -> StepSegments(
                        stringResource(R.string.labels_offset),
                        listOf(stringResource(R.string.gap_near), stringResource(R.string.gap_normal),
                            stringResource(R.string.gap_far)),
                        LabelSettingsSections.offsetIndex(options.labelOffset)
                    ) { options.labelOffset = LabelSettingsSections.OFFSET_STEPS[it] }
                    LabelAppearanceRow.HIDE_WHEN_ZOOMED_OUT ->
                        PanelCheckRow(stringResource(R.string.opt_hide_when_zoomed_out), options.hideLabelsWhenZoomedOut) {
                            options.hideLabelsWhenZoomedOut = !options.hideLabelsWhenZoomedOut
                        }
                }
            }
            LabelSettingsSection.PLACEMENT -> if (placement != null) {
                PanelSectionTitle(stringResource(R.string.labels_section_placement))
                PanelCaption(stringResource(R.string.labels_placement_help))
                PanelActionButton(stringResource(R.string.opt_labels_of_selection), placement.onLabelsOfSelection)
                PanelActionButton(stringResource(R.string.opt_labels_same_type), placement.onLabelsSameType)
                if (placement.armedCount > 0) PanelActionButton(
                    stringResource(R.string.labels_turn_off_fmt, placement.armedCount), placement.onTurnOff)
                val blockFields = LabelSettingsSections.separateBlockFields(options.labelFields)
                if (blockFields.isNotEmpty()) {
                    PanelSectionTitle(stringResource(R.string.labels_separate_blocks))
                    // Grille de deux colonnes, une case par champ affiché.
                    blockFields.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { c ->
                                val on = c in options.labelDetached
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Checkbox(checked = on, onCheckedChange = {
                                        placement.onSetDetached(if (on) options.labelDetached - c else options.labelDetached + c)
                                    }, colors = panelCheckboxColors(), modifier = Modifier.size(32.dp))
                                    Text(stringResource(c.labelRes), style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (pair.size == 1) Row(Modifier.weight(1f)) {}
                        }
                    }
                }
                PanelActionButton(stringResource(R.string.opt_reset_label_offsets), placement.onResetOffsets)
            }
        }
    }
}

@Composable
internal fun panelCheckboxColors() = CheckboxDefaults.colors(checkedColor = PANEL_ACCENT, uncheckedColor = PANEL_SECONDARY)

@Composable
internal fun PanelCheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, colors = panelCheckboxColors(),
            modifier = Modifier.size(36.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Bouton secondaire pleine largeur ; [onClick] null = grisé (absence de donnée, jamais Pro). */
@Composable
internal fun PanelActionButton(label: String, onClick: (() -> Unit)?) {
    OutlinedButton(onClick = { onClick?.invoke() }, enabled = onClick != null, colors = panelOutlinedColors(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, maxLines = 2, style = MaterialTheme.typography.labelLarge)
    }
}

/** Libellé + boutons segmentés à trois crans (taille, écart). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepSegments(label: String, names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        names.forEachIndexed { i, name ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = names.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color(0xFFDCE7F7), activeContentColor = PANEL_INK,
                    activeBorderColor = Color(0xFF9E9E9E),
                    inactiveContainerColor = Color.White, inactiveContentColor = PANEL_INK,
                    inactiveBorderColor = Color(0xFF9E9E9E)
                ),
                icon = {}
            ) { Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        }
    }
}
