package com.minou.mvrviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    // Source : IGN, en France seulement (SPEC-satellite-ign.md).
    var showSatellite by mutableStateOf(false)
    var satelliteOpacity by mutableFloatStateOf(0.55f)
    /**
     * Compteur d'ACTIVATIONS par l'utilisateur (outil, menu) : distingue un geste
     * explicite — qui force une nouvelle demande et affiche un Toast en cas d'échec
     * — d'une valeur restaurée avec le projet ou reçue du partage (silencieuses).
     */
    var satelliteActivations by mutableIntStateOf(0)

    /** Bascule du fond satellite par un GESTE de l'utilisateur. */
    fun toggleSatelliteByUser() {
        showSatellite = !showSatellite
        if (showSatellite) satelliteActivations++
    }
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
    /** Bibliothèque MA (profils grandMA importés → types « profil MA + 3D »). Gratuit. */
    onShowMaLibrary: (() -> Unit)? = null,
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
    /**
     * Panneau « Classes… » — visibilité par CLASSE Vectorworks. null → entrée
     * masquée, ce qui couvre les (nombreux) fichiers sans aucune classe : une
     * entrée qui ouvre une liste vide ne rend service à personne.
     */
    onShowClasses: (() -> Unit)? = null,
    // Synchro cloud : entrées de menu (au lieu d'un bouton flottant séparé).
    onShowAccount: (() -> Unit)? = null,
    onShareProject: (() -> Unit)? = null,
    onShowHistory: (() -> Unit)? = null,
    onJoinProject: (() -> Unit)? = null,
    showLabelsToggle: Boolean = false,
    /**
     * « Réglage des étiquettes… » (SPEC-menus-android §3.8) : ouvre le panneau de
     * la vue (champs, taille, écart ; dans le plan, placement, blocs séparés,
     * replacer). Entrée provisoire jusqu'à la tranche C, sous la bascule
     * « Étiquettes ». null → absente.
     */
    onShowLabelSettings: (() -> Unit)? = null,
    /**
     * « Position GPS… » (§3.6) : ouvre le panneau de la vue — afficher ma
     * position, satellite et son opacité, calibrations. Remplace la bascule du
     * satellite, son curseur et la ligne d'état de la calibration. null → absente.
     */
    onShowGeoPanel: (() -> Unit)? = null,
    /**
     * 3D (entrées provisoires jusqu'à C, §10 tranche B) : « Importer un plan
     * (DXF, image, PDF)… » (Pro au toucher, garde posée par l'appelant) et, s'il
     * y a un plan, « Régler le plan importé… » (gratuit). null → absentes.
     */
    onImportRefPlan: (() -> Unit)? = null,
    onAdjustRefPlan: (() -> Unit)? = null,
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
        onShowMaLibrary?.let { nav(stringResource(R.string.ma_library), Icons.AutoMirrored.Filled.LibraryBooks) { open = false; it() } }
        // ---- Outils de la vue (N10) : mêmes actions que la barre flottante,
        // accessibles ici. Les bascules gardent le menu ouvert ; les actions le
        // referment. Rendus dans l'ordre fourni par l'écran.
        if (tools.isNotEmpty() || onCustomizeToolbar != null || onShowLayers != null || onShowClasses != null ||
            onImportRefPlan != null || onAdjustRefPlan != null) {
            HorizontalDivider()
            Text(stringResource(R.string.menu_section_tools), style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF888888),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            // Panneau des calques (réglage du LOD d'interaction par calque).
            onShowLayers?.let { nav(stringResource(R.string.nav_layers), Icons.Filled.Layers) { open = false; it() } }
            // Visibilité par CLASSE Vectorworks, juste sous les calques : les deux
            // filtres se cumulent, ils se lisent donc côte à côte.
            onShowClasses?.let { nav(stringResource(R.string.tool_classes), Icons.Filled.Layers) { open = false; it() } }
            tools.forEach { t ->
                when (t) {
                    is MenuTool.Toggle -> toolToggle(t.label, t.icon, t.checked) { t.onToggle() }
                    is MenuTool.Action -> nav(t.label, t.icon) { open = false; t.onClick() }
                }
            }
            // Plan importé depuis la 3D (tranche B) : import Pro au toucher, réglage gratuit (lecture).
            onImportRefPlan?.let { nav(stringResource(R.string.tool_import_ref_plan), Icons.Filled.Map) { open = false; it() } }
            onAdjustRefPlan?.let { nav(stringResource(R.string.menu_adjust_imported_plan), Icons.Filled.Tune) { open = false; it() } }
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
            // « Compte & réglages… » (compte, Pro, GDTF Share) — écran plein (SPEC-menus-android §3.11).
            onShowAccount?.let { nav(stringResource(R.string.menu_account_settings), Icons.Filled.AccountCircle) { open = false; it() } }
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
        // « Position GPS… » : afficher ma position, satellite et opacité (décision 8 :
        // plus de curseur dans un menu), calibrations — tout vit dans le panneau.
        onShowGeoPanel?.let {
            HorizontalDivider()
            nav(stringResource(R.string.menu_gps_position), Icons.Filled.LocationOn) { open = false; it() }
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
        }
        // Champs, blocs séparés, taille, écart, dézoom, saisie groupée, replacer :
        // tout passe dans le panneau « Réglage des étiquettes » (entrée toujours visible).
        onShowLabelSettings?.let {
            nav(stringResource(R.string.menu_label_settings), Icons.Filled.Tune) { open = false; it() }
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
private fun check(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { if (on) Icon(Icons.Filled.Check, contentDescription = null) },
        enabled = enabled,
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
