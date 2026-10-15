package com.minou.mvrviewer.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.DxfParser
import com.minou.mvrviewer.mvr.DxfPlan
import com.minou.mvrviewer.mvr.RasterPlan
import com.minou.mvrviewer.mvr.RasterPlanLoader
import com.minou.mvrviewer.mvr.ReferencePlan
import com.minou.mvrviewer.mvr.ReferencePlanTransform
import com.minou.mvrviewer.mvr.emptyDxfPlanFor
import kotlin.math.max

/**
 * Règles PURES du panneau « Plan importé » (SPEC-menus-android §3.7), testées
 * (`RefPlanPanelRulesTest`). Lecture pour tous, chaque modification Pro.
 */
object RefPlanPanelRules {
    /** Raison d'écran Pro de chaque réglage du plan importé. */
    const val PRO_REASON = "régler le plan importé"
    /** Raison des calques DXF (garde existante de SceneScreen). */
    const val LAYERS_PRO_REASON = "modifier les calques du plan importé"
    /** Raison de l'import (existante). */
    const val IMPORT_PRO_REASON = "importer un plan"

    /**
     * Rotation verticale : 3D SEULEMENT — sans effet visible en 2D, elle quitte le
     * panneau du plan (« une fenêtre ne cache jamais ce qu'elle règle »).
     */
    fun showsVerticalRotation(surface: ProjectSurface): Boolean = surface == ProjectSurface.SCENE_3D

    /** Section « Calques » : DXF à PLUS D'UN calque (la même dans les deux panneaux). */
    fun showsLayers(plan: DxfPlan): Boolean = plan.layerCounts.size > 1

    /** Calques du DXF, du plus fourni au moins fourni (puis par nom). */
    fun layerRows(plan: DxfPlan): List<Pair<String, Int>> =
        plan.layerCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /** Pas des flèches de déplacement (mm monde) : 5 % de la largeur du plan, au moins 20 cm. */
    fun moveStep(plan: DxfPlan): Double = max(200.0, plan.width * 0.05)
}

/** Fichier importé, routé selon son CONTENU : DXF vectoriel, ou image / PDF matriciel. */
class ImportedRefPlan(val dxf: DxfPlan? = null, val raster: RasterPlan? = null)

/**
 * IMPORT du plan de repère, HISSÉ dans SceneScreen (SPEC-menus-android §3.7) pour
 * être appelable depuis les deux vues : lecture du fichier (hors thread
 * principal), plan prêt, et placement initial CENTRÉ sur la scène — le même
 * centre que celui du plan ([planData]), testé.
 */
object RefPlanImport {
    /**
     * POINT D'ENTRÉE UNIQUE du plan de repère : le sélecteur est en « *&#47;* »
     * (le DXF n'a pas de type MIME fiable), on route donc selon le CONTENU —
     * image / PDF → plan matriciel, tout le reste → lecteur DXF. À appeler HORS
     * du thread principal. null = fichier illisible.
     */
    fun read(ctx: Context, uri: Uri): ImportedRefPlan? = runCatching {
        val cr = ctx.contentResolver
        val name = RasterPlanLoader.displayName(cr, uri)
        when (val fmt = RasterPlanLoader.sniff(cr, uri, name)) {
            RasterPlanLoader.Format.DXF ->
                cr.openInputStream(uri)?.use { DxfParser.parse(it) }?.let { ImportedRefPlan(dxf = it) }
            // TAILLE D'ARRIVÉE : elle vient du FICHIER (MediaBox du PDF en points,
            // pixels d'origine de l'image à 96 dpi neutre), jamais de l'emprise de
            // la scène — le même document arrive identique quel que soit le show.
            RasterPlanLoader.Format.PDF ->
                RasterPlanLoader.renderPdfFirstPage(cr, uri)?.let { pdf ->
                    ImportedRefPlan(raster = RasterPlanLoader.place(
                        // Le nom part dans le MANIFESTE du projet : jamais une chaîne
                        // traduite. Vide = « pas de nom de fichier », le libellé se
                        // choisit à l'affichage (RasterPlan.displayName).
                        pdf.bitmap, name.orEmpty(), RasterPlan.Kind.PDF,
                        pdf.pageCount, pdf.pointWidth.toFloat(), pdf.pointHeight.toFloat()))
                }
            RasterPlanLoader.Format.PNG, RasterPlanLoader.Format.JPEG -> {
                val png = fmt == RasterPlanLoader.Format.PNG
                RasterPlanLoader.loadImage(cr, uri, png)?.let { img ->
                    ImportedRefPlan(raster = RasterPlanLoader.place(
                        img.bitmap, name.orEmpty(),
                        if (png) RasterPlan.Kind.PNG else RasterPlan.Kind.JPEG,
                        1, img.srcWidthPx.toFloat(), img.srcHeightPx.toFloat()))
                }
            }
        }
    }.getOrNull()

    /**
     * Plan prêt à poser : un plan matriciel porte un DxfPlan VIDE dont les bornes
     * sont celles de l'image (centrage, flèches et sorties `plan.isEmpty` marchent
     * sans cas particulier). null = rien d'exploitable (DXF sans géométrie).
     */
    fun ready(imported: ImportedRefPlan): Pair<DxfPlan, RasterPlan?>? {
        val raster = imported.raster
        val plan = imported.dxf ?: raster?.let { emptyDxfPlanFor(it) } ?: return null
        return if (raster != null || !plan.isEmpty) plan to raster else null
    }

    /**
     * Placement initial : le centre du plan importé sur le centre de la scène
     * ([sceneCx], [sceneCy] = centre de la vue plan, repère plan (x, −y) en mm) —
     * formule historique de la vue plan, inchangée.
     */
    fun initialTransform(sceneCx: Float, sceneCy: Float, plan: DxfPlan): ReferencePlanTransform =
        ReferencePlanTransform(
            offsetX = (sceneCx - plan.centerX).toDouble(),
            offsetY = (-sceneCy - plan.centerY).toDouble()
        )
}

/**
 * Panneau « Plan importé » (SPEC-menus-android §3.7), le même dans les deux vues
 * (3D : avec la rotation verticale). LECTURE pour tous ; chaque modification est
 * Pro au toucher (boutons : [ProAccess.gate] ; interrupteur, curseur, saisie :
 * [ProAccess.gatedSetter], un seul écran Pro par glissé). La transformée est
 * mutée EN PLACE : [onTouched] incrémente la version commune (SceneScreen), qui
 * redessine les deux vues, persiste (débouncé) et partage le placement.
 */
@Composable
fun RefPlanPanel(
    plan: ReferencePlan,
    surface: ProjectSurface,
    hiddenLayers: Set<String>,
    bgDark: Boolean,
    onTouched: () -> Unit,
    onToggleLayer: (String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    maxContentHeight: Dp?
) {
    val tf = plan.transform
    val step = RefPlanPanelRules.moveStep(plan.plan)
    val reason = RefPlanPanelRules.PRO_REASON
    /** Bouton : un toucher = un écran Pro pour un gratuit, rien ne bouge. */
    fun edit(change: () -> Unit) { ProAccess.gate(reason) { change(); onTouched() } }
    val pad = PaddingValues(2.dp)
    FloatingPanelCard(
        title = stringResource(R.string.refplan_panel_title), onClose = onClose,
        maxContentHeight = maxContentHeight, tag = "refPlanPanel"
    ) {
        // ---- Lecture : nature, taille, pages, segments, troncature ----
        val rast = plan.raster
        Text(
            if (rast != null) stringResource(R.string.refplan_kind_fmt, stringResource(rast.labelRes))
            else stringResource(R.string.refplan_dxf_fmt, plan.plan.unitLabel),
            style = MaterialTheme.typography.labelLarge
        )
        if (rast != null) {
            Text("${rast.bitmap.width} × ${rast.bitmap.height} px", style = MaterialTheme.typography.bodySmall,
                color = PANEL_SECONDARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Un PDF multi-pages n'utilise QUE la page 1 : le dire.
            if (rast.pageCount > 1) Text(stringResource(R.string.refplan_pdf_pages_fmt, rast.pageCount),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
        } else {
            Text(stringResource(R.string.refplan_segments_fmt, plan.plan.segmentCount) +
                if (plan.plan.truncatedSegments > 0) stringResource(R.string.refplan_truncated_fmt, plan.plan.truncatedSegments) else "",
                style = MaterialTheme.typography.bodySmall, color = PANEL_SECONDARY)
        }

        // ---- Calques (DXF à plus d'un calque) : lisibles, modifier = Pro ----
        if (RefPlanPanelRules.showsLayers(plan.plan)) {
            val rows = remember(plan) { RefPlanPanelRules.layerRows(plan.plan) }
            PanelSectionTitle(stringResource(R.string.refplan_layers_fmt, rows.size))
            rows.forEach { (layer, count) ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onToggleLayer(layer) }) {
                    Checkbox(checked = layer !in hiddenLayers, onCheckedChange = { onToggleLayer(layer) },
                        colors = CheckboxDefaults.colors(checkedColor = PANEL_ACCENT, uncheckedColor = PANEL_SECONDARY),
                        modifier = Modifier.size(32.dp))
                    plan.plan.layerColors[layer]?.let { lc ->
                        Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(dxfDisplayColor(lc, bgDark)))
                    }
                    Text(layer, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp))
                    Text("$count", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
                }
            }
            PanelDivider()
        }

        PanelSwitchRow(stringResource(R.string.refplan_show), checked = tf.visible) { v ->
            ProAccess.gatedSetter(reason) { tf.visible = v; onTouched() }
        }
        // Déplacement (mm monde) : libellé au-dessus + 4 flèches.
        Text(stringResource(R.string.common_move), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("←" to { tf.offsetX -= step }, "→" to { tf.offsetX += step },
                "↑" to { tf.offsetY += step }, "↓" to { tf.offsetY -= step }).forEach { (glyph, change) ->
                OutlinedButton(contentPadding = pad, colors = panelOutlinedColors(), modifier = Modifier.weight(1f),
                    onClick = { edit(change) }) { Text(glyph) }
            }
        }
        StepRow(stringResource(R.string.common_rotation), "−5°" to { edit { tf.rotationDeg -= 5 } },
            "+5°" to { edit { tf.rotationDeg += 5 } })
        if (RefPlanPanelRules.showsVerticalRotation(surface)) {
            StepRow(stringResource(R.string.common_rotation_vertical),
                "−5°" to { edit { tf.tiltDeg = (tf.tiltDeg - 5).coerceIn(-90.0, 90.0) } },
                "+5°" to { edit { tf.tiltDeg = (tf.tiltDeg + 5).coerceIn(-90.0, 90.0) } })
        }
        StepRow(stringResource(R.string.common_scale), "÷" to { edit { tf.scale /= 1.1 } },
            "×" to { edit { tf.scale *= 1.1 } })

        // ---- HOMOTHÉTIE (mise à l'échelle PROPORTIONNELLE) ----
        // Un SEUL facteur sur les deux axes ; curseur en log10 (×0,1 → ×10),
        // saisie libre dans toute la plage ; le rendu lit `effScale = scale × homothety`.
        var homoLog by remember(plan) { mutableFloatStateOf(homothetySlider(tf.homothety)) }
        var homoText by remember(plan) { mutableStateOf(formatHomothety(tf.homothety)) }
        fun applyHomothety(v: Double, syncSlider: Boolean, syncText: Boolean) {
            val c = v.coerceIn(HOMOTHETY_MIN, HOMOTHETY_MAX)
            tf.homothety = c
            if (syncSlider) homoLog = homothetySlider(c)
            if (syncText) homoText = formatHomothety(c)
            onTouched()
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(stringResource(R.string.common_uniform_scale), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            TextButton(contentPadding = pad, colors = ButtonDefaults.textButtonColors(contentColor = PANEL_ACCENT),
                onClick = { ProAccess.gate(reason) { applyHomothety(1.0, syncSlider = true, syncText = true) } }
            ) { Text(stringResource(R.string.common_reset), style = MaterialTheme.typography.labelSmall) }
        }
        Slider(
            value = homoLog,
            onValueChange = { v ->
                ProAccess.gatedSetter(reason) {
                    homoLog = v
                    applyHomothety(Math.pow(10.0, v.toDouble()), syncSlider = false, syncText = true)
                }
            },
            valueRange = -1f..1f, colors = panelSliderColors(), modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("×", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = homoText,
                onValueChange = { s ->
                    ProAccess.gatedSetter(reason) {
                        homoText = s
                        // Saisie libre : appliquée seulement quand le texte est un
                        // nombre valide (« 1, » n'est pas encore une valeur).
                        s.replace(',', '.').toDoubleOrNull()?.let {
                            if (it > 0.0) applyHomothety(it, syncSlider = true, syncText = false)
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = PANEL_INK, unfocusedTextColor = PANEL_INK,
                    cursorColor = PANEL_INK, focusedBorderColor = PANEL_ACCENT, unfocusedBorderColor = Color(0xFFBDBDBD)),
                modifier = Modifier.weight(1f)
            )
        }

        // ---- Retirer le plan : rouge, isolé ----
        PanelDivider()
        TextButton(onClick = { ProAccess.gate(reason) { onRemove() } }, colors = panelDestructiveColors()) {
            Text(stringResource(R.string.refplan_remove))
        }
    }
}

/** Ligne « libellé + deux boutons » (rotation, échelle). */
@Composable
private fun StepRow(label: String, a: Pair<String, () -> Unit>, b: Pair<String, () -> Unit>) {
    val pad = PaddingValues(2.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 2.dp)) {
        Text(label, modifier = Modifier.width(84.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(contentPadding = pad, colors = panelOutlinedColors(), modifier = Modifier.weight(1f),
            onClick = a.second) { Text(a.first) }
        OutlinedButton(contentPadding = pad, colors = panelOutlinedColors(), modifier = Modifier.weight(1f),
            onClick = b.second) { Text(b.first) }
    }
}
