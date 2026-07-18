package com.minou.mvrviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Réglages d'affichage partagés entre les vues d'un même show (fond, couleurs
 * par calque, étiquettes, décor). Équivalent des @State d'affichage de
 * ContentView iOS, hissés au niveau du show.
 */
/** Contenu affiché dans l'étiquette d'un projecteur (comme iOS LabelField). */
enum class LabelContent(val label: String) { ID("N°"), DMX("Adresse DMX"), MODE("Mode"), NAME("Nom") }

class SceneOptions {
    var backgroundDark by mutableStateOf(true)
    // Couleur de fond choisie par l'utilisateur, par vue (défauts noir / blanc,
    // comme iOS). Semées depuis BackgroundColorStore et persistées dans SceneScreen.
    var background3D by mutableStateOf(BackgroundColorStore.DEFAULT_3D)
    var background2D by mutableStateOf(BackgroundColorStore.DEFAULT_2D)
    var layerColors by mutableStateOf(true)
    var showLabels by mutableStateOf(true)
    var showStructure by mutableStateOf(true)
    var showLegend by mutableStateOf(true)        // légende des calques (vue plan)
    var labelContent by mutableStateOf(LabelContent.ID)
    var labelSize by mutableFloatStateOf(1f)     // 0.7 (S) · 1.0 (M) · 1.4 (L)
    var labelOffset by mutableFloatStateOf(1f)    // écart étiquette ↔ projecteur
    // Fond satellite géo-référencé (sous le plan / en 3D) — nécessite la
    // calibration GPS. Persisté par projet ; opacité de session (défaut 0.55).
    var showSatellite by mutableStateOf(false)
    var satelliteOpacity by mutableFloatStateOf(0.55f)
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
    showLegendToggle: Boolean = false,
    // Bascule du fond satellite (dispo seulement une fois la calibration GPS
    // posée) : la vue 3D n'a pas de contrôle flottant (SurfaceView), donc elle
    // pilote le satellite depuis ce menu, opacité comprise.
    showSatelliteToggle: Boolean = false,
    // Section « Couleur du fond » : activée quand la vue fournit sa couleur
    // courante + un setter. Presets = (nom, ARGB). null → section masquée.
    background: Color? = null,
    backgroundDefault: Color = Color.Black,
    backgroundPresets: List<Pair<String, Long>> = emptyList(),
    onPickBackground: ((Color) -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = tint)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        onShow3D?.let { nav("Vue 3D", Icons.Filled.ViewInAr) { open = false; it() } }
        onShowPlan?.let { nav("Vue plan", Icons.Filled.Map) { open = false; it() } }
        onShowPatch?.let { nav("Liste de patch", Icons.AutoMirrored.Filled.List) { open = false; it() } }
        onShowGdtfShare?.let { nav("GDTF Share (modèles 3D)", Icons.Filled.CloudDownload) { open = false; it() } }
        if (background != null && onPickBackground != null) {
            HorizontalDivider()
            Text("Couleur du fond", style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF888888),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            backgroundPresets.forEach { (name, argb) ->
                val c = Color(argb)
                val active = background.sameRgb(c)
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = {
                        Box(Modifier.width(16.dp).height(16.dp).clip(CircleShape)
                            .background(c).border(1.dp, Color(0x55808080), CircleShape))
                    },
                    trailingIcon = { if (active) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { onPickBackground(c) }
                )
            }
            nav("Personnalisée…", Icons.Filled.Colorize) { open = false; showCustom = true }
        }
        if (showSatelliteToggle) {
            HorizontalDivider()
            check("Fond satellite (carte)", options.showSatellite) { options.showSatellite = !options.showSatellite }
            if (options.showSatellite) {
                // Opacité : curseur compact dans le menu (le glissé horizontal ne
                // gêne pas le défilement vertical du menu).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp)
                ) {
                    Icon(Icons.Filled.Public, contentDescription = null,
                        modifier = Modifier.width(18.dp), tint = Color(0xFF888888))
                    Slider(
                        value = options.satelliteOpacity,
                        onValueChange = { options.satelliteOpacity = it },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.width(120.dp).padding(horizontal = 8.dp)
                    )
                    Text("${(options.satelliteOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
                }
            }
        }
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

    if (showCustom && background != null && onPickBackground != null) {
        BackgroundColorDialog(
            title = "Couleur du fond",
            initial = background,
            default = backgroundDefault,
            onColorChange = onPickBackground,
            onDismiss = { showCustom = false }
        )
    }
}

/** Égalité sur le RGB (ignore l'alpha) — les presets/fonds sont opaques. */
private fun Color.sameRgb(o: Color): Boolean {
    fun ch(v: Float) = (v * 255f + 0.5f).toInt()
    return ch(red) == ch(o.red) && ch(green) == ch(o.green) && ch(blue) == ch(o.blue)
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
