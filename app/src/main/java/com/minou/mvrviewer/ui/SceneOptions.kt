package com.minou.mvrviewer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Réglages d'affichage partagés entre les vues d'un même show (fond, couleurs
 * par calque, étiquettes, décor). Équivalent des @State d'affichage de
 * ContentView iOS, hissés au niveau du show.
 */
/** Contenu affiché dans l'étiquette d'un projecteur (comme iOS LabelField). */
enum class LabelContent(val label: String) { ID("N°"), DMX("Adresse DMX"), MODE("Mode"), NAME("Nom") }

class SceneOptions {
    var backgroundDark by mutableStateOf(true)
    var layerColors by mutableStateOf(true)
    var showLabels by mutableStateOf(true)
    var showStructure by mutableStateOf(true)
    var showLegend by mutableStateOf(true)        // légende des calques (vue plan)
    var labelContent by mutableStateOf(LabelContent.ID)
    var labelSize by mutableFloatStateOf(1f)     // 0.7 (S) · 1.0 (M) · 1.4 (L)
    var labelOffset by mutableFloatStateOf(1f)    // écart étiquette ↔ projecteur
}

/**
 * Menu d'options (⋯) commun aux vues — comme le menu unique de la vue 3D iOS :
 * navigation entre les vues + bascules d'affichage. Les items non pertinents
 * pour la vue courante sont masqués (callbacks `null`).
 */
@Composable
fun SceneOptionsMenu(
    options: SceneOptions,
    tint: Color,
    onShow3D: (() -> Unit)? = null,
    onShowPlan: (() -> Unit)? = null,
    onShowPatch: (() -> Unit)? = null,
    onShowGdtfShare: (() -> Unit)? = null,
    showLabelsToggle: Boolean = false,
    showStructureToggle: Boolean = false,
    showLegendToggle: Boolean = false
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = tint)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        onShow3D?.let { nav("Vue 3D", Icons.Filled.ViewInAr) { open = false; it() } }
        onShowPlan?.let { nav("Vue plan", Icons.Filled.Map) { open = false; it() } }
        onShowPatch?.let { nav("Liste de patch", Icons.AutoMirrored.Filled.List) { open = false; it() } }
        onShowGdtfShare?.let { nav("GDTF Share (modèles 3D)", Icons.Filled.CloudDownload) { open = false; it() } }
        HorizontalDivider()
        check("Couleurs par calque", options.layerColors) { options.layerColors = !options.layerColors }
        if (showStructureToggle) check("Décor / structure", options.showStructure) { options.showStructure = !options.showStructure }
        if (showLegendToggle) check("Légende", options.showLegend) { options.showLegend = !options.showLegend }
        if (showLabelsToggle) {
            check("Étiquettes", options.showLabels) { options.showLabels = !options.showLabels }
            if (options.showLabels) {
                // Contenu de l'étiquette (radio).
                LabelContent.entries.forEach { c ->
                    check("  ${c.label}", options.labelContent == c) { options.labelContent = c }
                }
                // Taille (cycle S · M · L).
                val sizeName = when {
                    options.labelSize <= 0.75f -> "petite"
                    options.labelSize >= 1.3f -> "grande"
                    else -> "moyenne"
                }
                nav("  Taille : $sizeName", Icons.Filled.Check) {
                    options.labelSize = when {
                        options.labelSize <= 0.75f -> 1f
                        options.labelSize >= 1.3f -> 0.7f
                        else -> 1.4f
                    }
                }
                // Hauteur / écart (cycle).
                val offName = when {
                    options.labelOffset <= 0.6f -> "proche"
                    options.labelOffset >= 1.6f -> "loin"
                    else -> "normal"
                }
                nav("  Écart : $offName", Icons.Filled.Check) {
                    options.labelOffset = when {
                        options.labelOffset <= 0.6f -> 1f
                        options.labelOffset >= 1.6f -> 0.5f
                        else -> 2f
                    }
                }
            }
        }
    }
}

@Composable
private fun nav(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
private fun check(label: String, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { if (on) Icon(Icons.Filled.Check, contentDescription = null) },
        onClick = onClick
    )
}
