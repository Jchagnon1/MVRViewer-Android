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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * Réglages d'affichage partagés entre les vues d'un même show (fond, couleurs
 * par calque, étiquettes, décor). Équivalent des @State d'affichage de
 * ContentView iOS, hissés au niveau du show.
 */
/**
 * Contenu affiché dans l'étiquette d'un projecteur (comme iOS LabelField).
 *
 * SOCAPEX et DMX_LINE (phase 4 câblage) dépendent de l'état de câblage RUNTIME
 * (hors PlanFixture) : leur texte est résolu par un `cablingText` injecté dans
 * `labelFieldText` / `labelBlocks`. Un projecteur non affecté n'affiche rien pour
 * ces champs (résolveur → null). Ils apparaissent AUTOMATIQUEMENT dans le picker
 * (boucle `LabelContent.entries`).
 */
enum class LabelContent(@androidx.annotation.StringRes val labelRes: Int) {
    ID(R.string.label_field_id), DMX(R.string.label_field_dmx), MODE(R.string.label_field_mode), NAME(R.string.label_field_name),
    SOCAPEX(R.string.label_field_socapex), DMX_LINE(R.string.label_field_dmx_line)
}

/**
 * Mode de coloration des projecteurs en vue PLAN UNIQUEMENT (phase 4 câblage).
 *
 * ⚠️ DISTINCT de `SceneOptions.layerColors`, qui est PARTAGÉ avec la 3D : le
 * détourner colorerait aussi la 3D. LAYER = comportement historique (couleur par
 * calque si `layerColors`, sinon gris neutre) ; SOCAPEX / DMX_LINE colorent chaque
 * projecteur par la couleur de SON distributeur câblage. Non affecté = gris neutre.
 * Libellés/ordre alignés iOS (CablingColorMode : off / soca / dmx).
 */
enum class PlanColorMode(@androidx.annotation.StringRes val labelRes: Int) {
    LAYER(R.string.facet_layer), SOCAPEX(R.string.label_field_socapex), DMX_LINE(R.string.label_field_dmx_line)
}

/**
 * Outil de la barre flottante EXPOSÉ dans le menu déroulant (regroupement d'accès
 * — N10). Chaque écran décrit ses outils courants (sélection rectangle, mesure,
 * solo, position GPS, satellite, export, plan DXF…) et le menu les rend sous une
 * section « Outils ». Les boutons flottants restent en place : le menu est un
 * point d'accès ALTERNATIF, pas un remplacement (rien n'est cassé).
 */
sealed interface MenuTool {
    val label: String
    val icon: ImageVector
    /** Bascule ON/OFF : le menu RESTE ouvert (on peut en enchaîner plusieurs). */
    data class Toggle(
        override val label: String, override val icon: ImageVector,
        val checked: Boolean, val onToggle: () -> Unit
    ) : MenuTool
    /** Action ponctuelle : referme le menu au clic. */
    data class Action(
        override val label: String, override val icon: ImageVector,
        val onClick: () -> Unit
    ) : MenuTool
}

class SceneOptions {
    var backgroundDark by mutableStateOf(true)
    // Couleur de fond choisie par l'utilisateur, par vue (défauts noir / blanc,
    // comme iOS). Semées depuis BackgroundColorStore et persistées dans SceneScreen.
    var background3D by mutableStateOf(BackgroundColorStore.DEFAULT_3D)
    var background2D by mutableStateOf(BackgroundColorStore.DEFAULT_2D)
    var layerColors by mutableStateOf(true)
    /**
     * Mode de coloration PLAN (calque / Socapex / ligne DMX) — état PLAN-ONLY,
     * volontairement séparé de `layerColors` (partagé 3D). Voir [PlanColorMode].
     */
    var planColorMode by mutableStateOf(PlanColorMode.LAYER)
    var showLabels by mutableStateOf(true)
    var showStructure by mutableStateOf(true)
    var showLegend by mutableStateOf(true)        // légende des calques (vue plan)
    /**
     * Champs AFFICHÉS dans l'étiquette. Plusieurs à la fois : le n° seul ne suffit
     * pas au terrain (« n° + patch »). Ils sont mis en page une LIGNE PAR CHAMP —
     * une étiquette large chevauche ses voisines, une étiquette haute non.
     */
    var labelFields by mutableStateOf(setOf(LabelContent.ID, LabelContent.DMX))
    /**
     * Champs DÉTACHÉS de la pastille commune : chacun devient un bloc autonome,
     * avec son propre placement et son propre décalage déplaçable au doigt (le
     * n° au-dessus de l'icône, le patch en dessous). Les autres restent groupés.
     */
    var labelDetached by mutableStateOf(emptySet<LabelContent>())
    var labelSize by mutableFloatStateOf(1f)     // 0.7 (S) · 1.0 (M) · 1.4 (L)
    var labelOffset by mutableFloatStateOf(1f)    // écart étiquette ↔ projecteur
    /**
     * Option EXPLICITE « masquer les étiquettes quand c'est trop dézoomé »,
     * DÉSACTIVÉE par défaut : par défaut, une étiquette affichée reste visible
     * à tout niveau de zoom. On ne rebranche le seuil de lisibilité que si
     * l'utilisateur le coche (filet pour un très gros show illisible dézoomé).
     */
    var hideLabelsWhenZoomedOut by mutableStateOf(false)
    // Fond satellite géo-référencé (sous le plan / en 3D) — nécessite la
    // calibration GPS. Persisté par projet ; opacité de session (défaut 0.55).
    var showSatellite by mutableStateOf(false)
    var satelliteOpacity by mutableFloatStateOf(0.55f)
    // Taille du marqueur « ma position » en 3D (multiplicateur, S 0.6 · M 1 · L 1.6).
    var gpsMarkerScale by mutableFloatStateOf(1f)
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
    onShowUniverse: (() -> Unit)? = null,
    onShowCabling: (() -> Unit)? = null,
    onShowGdtfShare: (() -> Unit)? = null,
    /**
     * Outils de la vue (barre flottante) exposés AUSSI dans le menu — N10. Section
     * « Outils » rendue en tête si non vide. Voir [MenuTool].
     */
    tools: List<MenuTool> = emptyList(),
    /**
     * N11 — point d'entrée du MODE PERSONNALISER (« Personnaliser la barre
     * d'outils… ») rendu en fin de section « Outils ». null → entrée masquée
     * (vues sans barres personnalisables).
     */
    onCustomizeToolbar: (() -> Unit)? = null,
    /**
     * Panneau « Calques… » de la vue 3D (#1) : réglage du LOD d'interaction par
     * calque. null → entrée masquée (vues sans panneau de calques).
     */
    onShowLayers: (() -> Unit)? = null,
    // Synchro cloud : entrées de menu (au lieu d'un bouton flottant séparé).
    onShowAccount: (() -> Unit)? = null,
    onShareProject: (() -> Unit)? = null,
    onShowHistory: (() -> Unit)? = null,
    onJoinProject: (() -> Unit)? = null,
    showLabelsToggle: Boolean = false,
    /**
     * « Replacer les étiquettes » : remet à zéro les décalages posés au doigt.
     * Indispensable comme filet de sécurité — une étiquette traînée très loin de
     * son projecteur, ou posée pile sur son symbole, resterait sinon pénible à
     * récupérer. `null` → entrée masquée (vues sans étiquettes déplaçables).
     */
    onResetLabelOffsets: (() -> Unit)? = null,
    /**
     * Arme les étiquettes de TOUS les projecteurs sélectionnés (rectangle ou
     * multi-sélection) : un glissé sur l'une d'elles les déplace toutes. `null`
     * quand la sélection ne s'y prête pas (vide).
     */
    onSelectLabelsOfSelection: (() -> Unit)? = null,
    /**
     * Même chose pour tous les projecteurs de MÊME TYPE GDTF sur le MÊME CALQUE
     * que l'étiquette active (les projecteurs d'un pont, en pratique). `null`
     * tant qu'aucune étiquette n'est active — le critère n'a alors pas d'origine.
     */
    onSelectLabelsSameType: (() -> Unit)? = null,
    showStructureToggle: Boolean = false,
    showLegendToggle: Boolean = false,
    /**
     * Sélecteur de MODE DE COLORATION (phase 4 câblage) — vue plan uniquement.
     * Affiché seulement s'il existe au moins un câblage colorable (`colorModeHasSoca`
     * ou `colorModeHasDmx`) : sans câblage, il n'y aurait rien à colorer. Le nav
     * cycle entre les modes RÉELLEMENT disponibles (Calque toujours présent).
     */
    showColorModeSelector: Boolean = false,
    colorModeHasSoca: Boolean = false,
    colorModeHasDmx: Boolean = false,
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
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_options), tint = tint)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        onShow3D?.let { nav(stringResource(R.string.nav_view_3d), Icons.Filled.ViewInAr) { open = false; it() } }
        onShowPlan?.let { nav(stringResource(R.string.nav_view_plan), Icons.Filled.Map) { open = false; it() } }
        onShowPatch?.let { nav(stringResource(R.string.nav_patch_list), Icons.AutoMirrored.Filled.List) { open = false; it() } }
        onShowUniverse?.let { nav(stringResource(R.string.nav_dmx_universe), Icons.Filled.GridView) { open = false; it() } }
        onShowCabling?.let { nav(stringResource(R.string.nav_power_cabling), Icons.Filled.Bolt) { open = false; it() } }
        onShowGdtfShare?.let { nav(stringResource(R.string.nav_gdtf_share_models), Icons.Filled.CloudDownload) { open = false; it() } }
        // ---- Outils de la vue (N10) : mêmes actions que la barre flottante,
        // accessibles ici. Les bascules gardent le menu ouvert ; les actions le
        // referment. Rendus dans l'ordre fourni par l'écran.
        if (tools.isNotEmpty() || onCustomizeToolbar != null || onShowLayers != null) {
            HorizontalDivider()
            Text(stringResource(R.string.menu_section_tools), style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF888888),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            // Panneau des calques (réglage du LOD d'interaction par calque).
            onShowLayers?.let { nav(stringResource(R.string.nav_layers), Icons.Filled.Layers) { open = false; it() } }
            tools.forEach { t ->
                when (t) {
                    is MenuTool.Toggle -> toolToggle(t.label, t.icon, t.checked) { t.onToggle() }
                    is MenuTool.Action -> nav(t.label, t.icon) { open = false; t.onClick() }
                }
            }
            // N11 — accès au panneau de personnalisation des barres (les outils
            // ci-dessus restent TOUJOURS ici, qu'ils soient dans une barre ou non).
            onCustomizeToolbar?.let {
                nav(stringResource(R.string.nav_customize_toolbar), Icons.Filled.Tune) { open = false; it() }
            }
        }
        // ---- Synchro cloud (compte / partage / historique / rejoindre) ----
        if (onShowAccount != null || onShareProject != null || onShowHistory != null || onJoinProject != null) {
            HorizontalDivider()
            Text(stringResource(R.string.menu_section_sync), style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF888888),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            onShowAccount?.let { nav(stringResource(R.string.nav_account), Icons.Filled.AccountCircle) { open = false; it() } }
            onShareProject?.let { nav(stringResource(R.string.nav_share_project), Icons.Filled.Share) { open = false; it() } }
            onShowHistory?.let { nav(stringResource(R.string.nav_history), Icons.Filled.History) { open = false; it() } }
            onJoinProject?.let { nav(stringResource(R.string.join_project), Icons.Filled.GroupAdd) { open = false; it() } }
        }
        if (background != null && onPickBackground != null) {
            HorizontalDivider()
            Text(stringResource(R.string.menu_section_background), style = MaterialTheme.typography.labelSmall,
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
            nav(stringResource(R.string.nav_custom_color), Icons.Filled.Colorize) { open = false; showCustom = true }
        }
        if (showSatelliteToggle) {
            HorizontalDivider()
            check(stringResource(R.string.opt_satellite_map), options.showSatellite) { options.showSatellite = !options.showSatellite }
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
        check(stringResource(R.string.opt_layer_colors), options.layerColors) { options.layerColors = !options.layerColors }
        // Mode de coloration CÂBLAGE (phase 4), vue plan uniquement : nav qui cycle
        // entre les modes réellement disponibles (Calque toujours ; Socapex / Ligne
        // DMX seulement si un câblage de ce type existe). Ne détourne PAS layerColors.
        if (showColorModeSelector) {
            nav(stringResource(R.string.opt_coloring_fmt, stringResource(options.planColorMode.labelRes)), Icons.Filled.Colorize) {
                options.planColorMode =
                    nextPlanColorMode(options.planColorMode, colorModeHasSoca, colorModeHasDmx)
            }
        }
        if (showStructureToggle) check(stringResource(R.string.opt_structure), options.showStructure) { options.showStructure = !options.showStructure }
        if (showLegendToggle) check(stringResource(R.string.opt_legend), options.showLegend) { options.showLegend = !options.showLegend }
        if (showLabelsToggle) {
            check(stringResource(R.string.opt_labels), options.showLabels) { options.showLabels = !options.showLabels }
            if (options.showLabels) {
                // Contenu de l'étiquette : CASES À COCHER (plusieurs champs), et
                // pour chaque champ retenu, le choix « groupé / détaché ». Un
                // champ détaché devient une pastille autonome, déplaçable
                // séparément — c'est ce qui permet le n° au-dessus de l'icône et
                // le patch en dessous.
                LabelContent.entries.forEach { c ->
                    val on = c in options.labelFields
                    check("  " + stringResource(c.labelRes), on) {
                        options.labelFields =
                            if (on) options.labelFields - c else options.labelFields + c
                        // Un champ retiré ne doit pas laisser derrière lui un
                        // « détaché » invisible qui ressusciterait au recochage.
                        if (on) options.labelDetached = options.labelDetached - c
                    }
                    if (on) {
                        val loose = c in options.labelDetached
                        check("      ↳ " + stringResource(R.string.opt_label_detached), loose) {
                            options.labelDetached =
                                if (loose) options.labelDetached - c else options.labelDetached + c
                        }
                    }
                }
                // Sélection GROUPÉE d'étiquettes : plusieurs actives à la fois,
                // qu'un seul glissé déplace toutes du même vecteur.
                onSelectLabelsOfSelection?.let {
                    nav("  " + stringResource(R.string.opt_labels_of_selection), Icons.Filled.SelectAll) { open = false; it() }
                }
                onSelectLabelsSameType?.let {
                    nav("  " + stringResource(R.string.opt_labels_same_type), Icons.Filled.SelectAll) { open = false; it() }
                }
                // Taille (cycle S · M · L).
                val sizeName = when {
                    options.labelSize <= 0.75f -> stringResource(R.string.size_small)
                    options.labelSize >= 1.3f -> stringResource(R.string.size_large)
                    else -> stringResource(R.string.size_medium)
                }
                nav("  " + stringResource(R.string.opt_size_fmt, sizeName), Icons.Filled.Check) {
                    options.labelSize = when {
                        options.labelSize <= 0.75f -> 1f
                        options.labelSize >= 1.3f -> 0.7f
                        else -> 1.4f
                    }
                }
                // Hauteur / écart (cycle).
                val offName = when {
                    options.labelOffset <= 0.6f -> stringResource(R.string.gap_near)
                    options.labelOffset >= 1.6f -> stringResource(R.string.gap_far)
                    else -> stringResource(R.string.gap_normal)
                }
                nav("  " + stringResource(R.string.opt_gap_fmt, offName), Icons.Filled.Check) {
                    options.labelOffset = when {
                        options.labelOffset <= 0.6f -> 1f
                        options.labelOffset >= 1.6f -> 0.5f
                        else -> 2f
                    }
                }
                // Filet OPTIONNEL (décoché par défaut) : par défaut les étiquettes
                // affichées restent visibles à tout zoom ; cochée, celle-ci rebranche
                // le masquage sous un certain dézoom, utile sur un très gros show.
                check("  " + stringResource(R.string.opt_hide_when_zoomed_out), options.hideLabelsWhenZoomedOut) {
                    options.hideLabelsWhenZoomedOut = !options.hideLabelsWhenZoomedOut
                }
                onResetLabelOffsets?.let {
                    nav("  " + stringResource(R.string.opt_reset_label_offsets), Icons.Filled.Refresh) { open = false; it() }
                }
            }
        }
    }

    if (showCustom && background != null && onPickBackground != null) {
        BackgroundColorDialog(
            title = stringResource(R.string.menu_section_background),
            initial = background,
            default = backgroundDefault,
            onColorChange = onPickBackground,
            onDismiss = { showCustom = false }
        )
    }
}

/**
 * Mode suivant dans le cycle de coloration, en ne proposant QUE les modes
 * disponibles : Calque est toujours présent ; Socapex et Ligne DMX ne le sont que
 * si un câblage de ce type existe. Un mode courant devenu indisponible (câblage
 * supprimé) retombe naturellement sur le premier disponible.
 */
private fun nextPlanColorMode(cur: PlanColorMode, hasSoca: Boolean, hasDmx: Boolean): PlanColorMode {
    val avail = buildList {
        add(PlanColorMode.LAYER)
        if (hasSoca) add(PlanColorMode.SOCAPEX)
        if (hasDmx) add(PlanColorMode.DMX_LINE)
    }
    val i = avail.indexOf(cur)
    return avail[((if (i < 0) 0 else i) + 1) % avail.size]
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

/** Bascule d'outil : icône à gauche (comme les entrées de navigation) + coche à
 *  droite quand elle est active. Ne referme PAS le menu. */
@Composable
private fun toolToggle(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = { if (on) Icon(Icons.Filled.Check, contentDescription = null) },
        onClick = onClick
    )
}
