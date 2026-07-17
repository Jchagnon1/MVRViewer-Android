package com.minou.mvrviewer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
class SceneOptions {
    var backgroundDark by mutableStateOf(true)
    var layerColors by mutableStateOf(true)
    var showLabels by mutableStateOf(true)
    var showStructure by mutableStateOf(true)
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
    showLabelsToggle: Boolean = false,
    showStructureToggle: Boolean = false
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = tint)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        onShow3D?.let { nav("Vue 3D", Icons.Filled.ViewInAr) { open = false; it() } }
        onShowPlan?.let { nav("Vue plan", Icons.Filled.Map) { open = false; it() } }
        onShowPatch?.let { nav("Liste de patch", Icons.AutoMirrored.Filled.List) { open = false; it() } }
        HorizontalDivider()
        check("Couleurs par calque", options.layerColors) { options.layerColors = !options.layerColors }
        if (showLabelsToggle) check("Étiquettes (ID)", options.showLabels) { options.showLabels = !options.showLabels }
        if (showStructureToggle) check("Décor / structure", options.showStructure) { options.showStructure = !options.showStructure }
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
