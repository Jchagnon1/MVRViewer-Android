package com.minou.mvrviewer.ui

import androidx.compose.ui.semantics.role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.minou.mvrviewer.R

/** États des bascules dessinées par le menu (coche, case surlignée). */
data class MainMenuState(
    val measure: Boolean = false,
    val labels: Boolean = false,
    val layerColors: Boolean = false,
    val structure: Boolean = false,
    val legend: Boolean = false,
    val hideItems: Boolean = false,
    val isolate: Boolean = false
) {
    fun isOn(id: MainMenuItemId): Boolean = when (id) {
        MainMenuItemId.MEASURE -> measure
        MainMenuItemId.LABELS -> labels
        MainMenuItemId.LAYER_COLORS -> layerColors
        MainMenuItemId.STRUCTURE -> structure
        MainMenuItemId.LEGEND -> legend
        MainMenuItemId.HIDE_ITEMS -> hideItems
        MainMenuItemId.ISOLATE -> isolate
        else -> false
    }
}

/** Valeurs des pages de choix (Colorer par câblage, Couleur du fond), propres à la vue. */
class MainMenuValues(
    val colorMode: PlanColorMode = PlanColorMode.LAYER,
    val onColorMode: (PlanColorMode) -> Unit = {},
    val background: Color,
    /** Préréglages de la vue (nom, ARGB). */
    val backgroundPresets: List<Pair<String, Long>>,
    val onBackground: (Color) -> Unit,
    /** « Personnalisée… » : referme le menu et ouvre le sélecteur fin. */
    val onCustomBackground: () -> Unit
)

/** Symbole Material d'une entrée (iOS : symbole SF de la même ligne, §3.2). */
internal fun MainMenuItemId.icon(): ImageVector = when (this) {
    MainMenuItemId.MEASURE -> Icons.Filled.Straighten
    MainMenuItemId.LABELS -> Icons.AutoMirrored.Filled.Label
    MainMenuItemId.LAYERS -> Icons.Filled.Layers
    MainMenuItemId.PATCH -> Icons.AutoMirrored.Filled.List
    MainMenuItemId.UNIVERSE -> Icons.Filled.GridView
    MainMenuItemId.CABLING -> Icons.Filled.Bolt
    MainMenuItemId.DISPLAY -> Icons.Filled.Visibility
    MainMenuItemId.HIDE_ISOLATE -> Icons.Filled.VisibilityOff
    MainMenuItemId.PATCH_CABLING -> Icons.Filled.Cable
    MainMenuItemId.PLAN_SCENERY -> Icons.Outlined.Layers
    MainMenuItemId.GPS_POSITION -> Icons.Filled.LocationOn
    MainMenuItemId.SHARE_EXPORT -> Icons.Filled.Share
    MainMenuItemId.ACCOUNT_SETTINGS -> Icons.Filled.AccountCircle
    MainMenuItemId.CLOSE_PROJECT -> Icons.Filled.Close
    MainMenuItemId.LABEL_SETTINGS -> Icons.Filled.FormatSize
    MainMenuItemId.LAYER_COLORS -> Icons.Filled.Palette
    MainMenuItemId.COLOR_BY_CABLING -> Icons.Filled.Cable
    MainMenuItemId.BACKGROUND_COLOR -> Icons.Filled.FormatColorFill
    MainMenuItemId.STRUCTURE -> Icons.Filled.Architecture
    MainMenuItemId.LEGEND -> Icons.AutoMirrored.Filled.FormatListBulleted
    MainMenuItemId.TOOLBAR_THIS_VIEW -> Icons.Filled.Tune
    MainMenuItemId.HIDE_ITEMS -> Icons.Filled.VisibilityOff
    MainMenuItemId.ISOLATE -> Icons.Filled.CenterFocusStrong
    MainMenuItemId.SHOW_ALL -> Icons.Filled.Visibility
    MainMenuItemId.CLEAR_SOLO -> Icons.Filled.CenterFocusWeak
    MainMenuItemId.FIXTURE_MODELS -> Icons.Filled.Lightbulb
    MainMenuItemId.CABLING_ELECTRICAL -> Icons.Filled.Power
    MainMenuItemId.CABLING_DMX -> Icons.Filled.Cable
    MainMenuItemId.CABLING_POWERS -> Icons.Filled.Bolt
    MainMenuItemId.IMPORT_PLAN -> Icons.Filled.UploadFile
    MainMenuItemId.ADJUST_PLAN -> Icons.Filled.Tune
    MainMenuItemId.IMPORT_MODEL -> Icons.Filled.ViewInAr
    MainMenuItemId.IMPORTED_MODELS -> Icons.Filled.Widgets
    MainMenuItemId.SHARE_PROJECT -> Icons.Filled.Groups
    MainMenuItemId.HISTORY -> Icons.Filled.History
    MainMenuItemId.PLAN_PDF -> Icons.Filled.PictureAsPdf
    MainMenuItemId.CABLING_PDF_FULL -> Icons.Filled.Description
    MainMenuItemId.CABLING_PDF_ELEC -> Icons.Filled.Power
    MainMenuItemId.CABLING_PDF_DMX -> Icons.Filled.Cable
    MainMenuItemId.CABLING_PDF_PLANS -> Icons.Filled.Map
}

/** Couleurs d'un bouton rond NEUTRE de la scène (← , ⋯, Rechercher, Recadrer) : jamais jaune. */
@Composable
internal fun neutralRoundButtonColors() = IconButtonDefaults.filledIconButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/**
 * Bouton ⋯ et MENU UNIQUE (SPEC-menus-android §3.2) : même composable, même
 * contenu dans la 3D et dans le plan ; [onItem] reçoit l'entrée touchée (la vue
 * traite les siennes et transmet les entrées « projet » à SceneScreen).
 *
 * Familles en PAGES dans le même `DropdownMenu` (M3 n'a pas de sous-menu en
 * cascade, §8 D3) : la ligne « ‹ Famille » et le retour système reviennent à la
 * page parente ; au premier niveau, le retour ferme le menu. Les bascules gardent
 * le menu ouvert, les actions le ferment.
 */
@Composable
fun MainMenuButton(
    ctx: MainMenuContext,
    state: MainMenuState,
    values: MainMenuValues,
    onItem: (MainMenuItemId) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(MainMenuPage.TOP) }
    // La page n'est PAS remise au premier niveau à la fermeture (elle l'est à
    // l'ouverture) : sinon le premier niveau réapparaissait le temps du fondu de
    // fermeture (constat émulateur).
    fun close() { open = false }
    Box(modifier) {
        FilledIconButton(
            onClick = { page = MainMenuPage.TOP; open = true },
            colors = neutralRoundButtonColors(),
            modifier = Modifier.size(48.dp).testTag("mainMenu")
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more_options))
        }
        // Le retour système d'une PAGE remonte d'un niveau ; au premier niveau, il
        // ferme le menu. Android 13+ (retour prédictif, cible 36) : le Popup enregistre
        // son propre rappel de retour À L'ATTACHE et ne le retire pas quand ses
        // propriétés changent — un rappel plus récent, de même priorité, posé sur la
        // fenêtre du menu par la page ([PopupPageBackHandler]) passe devant lui
        // (constat émulateur API 37). Avant 13 : touche Retour reçue par le contenu,
        // qui garde le focus (la fenêtre ne se ferme au retour qu'au premier niveau).
        val focus = remember { FocusRequester() }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { close() },
            properties = PopupProperties(focusable = true, dismissOnBackPress = page == MainMenuPage.TOP),
            modifier = Modifier
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.Back && page != MainMenuPage.TOP) {
                        if (e.type == KeyEventType.KeyUp) page = page.parent ?: MainMenuPage.TOP
                        true
                    } else false
                }
        ) {
            LaunchedEffect(page) { runCatching { focus.requestFocus() } }
            PopupPageBackHandler(enabled = page != MainMenuPage.TOP) { page = page.parent ?: MainMenuPage.TOP }
            MainMenuPageContent(
                page = page, ctx = ctx, state = state, values = values,
                onPage = { page = it },
                onAction = { id -> close(); onItem(id) },
                onToggle = { id -> onItem(id) },
                onCustomBackground = { close(); values.onCustomBackground() }
            )
        }
    }
}

@Composable
private fun MainMenuPageContent(
    page: MainMenuPage,
    ctx: MainMenuContext,
    state: MainMenuState,
    values: MainMenuValues,
    onPage: (MainMenuPage) -> Unit,
    onAction: (MainMenuItemId) -> Unit,
    onToggle: (MainMenuItemId) -> Unit,
    onCustomBackground: () -> Unit
) {
    // En-tête « ‹ Titre » d'une page : revient à la page parente.
    val parent = page.parent
    page.titleRes?.let { title ->
        DropdownMenuItem(
            text = { Text(stringResource(title), fontWeight = FontWeight.SemiBold) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
            onClick = { onPage(parent ?: MainMenuPage.TOP) },
            modifier = Modifier.testTag("menu.back")
        )
        HorizontalDivider()
    }
    when (page) {
        MainMenuPage.COLOR_BY_CABLING -> ctx.colorModes.forEach { m ->
            RadioRow(stringResource(m.labelRes), m == values.colorMode) { values.onColorMode(m) }
        }
        MainMenuPage.BACKGROUND_COLOR -> {
            values.backgroundPresets.forEach { (name, argb) ->
                val c = Color(argb)
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(c)
                            .border(1.dp, Color(0x55808080), CircleShape))
                    },
                    trailingIcon = { RadioButton(selected = values.background.sameRgb(c), onClick = null) },
                    onClick = { values.onBackground(c) }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_custom_color)) },
                leadingIcon = { Icon(Icons.Filled.Colorize, contentDescription = null) },
                onClick = onCustomBackground
            )
        }
        else -> MainMenuModel.sections(page, ctx).forEachIndexed { i, section ->
            if (i > 0 && (section.divider || page != MainMenuPage.TOP)) HorizontalDivider()
            section.titleRes?.let {
                Text(stringResource(it), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp))
            }
            if (section.row) MenuIconRow(section.items, state, onAction, onToggle)
            else section.items.forEach { item -> MenuLine(item, state, onPage, onAction, onToggle) }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { RadioButton(selected = selected, onClick = null) },
        onClick = onClick
    )
}

@Composable
private fun MenuLine(
    item: MainMenuItem,
    state: MainMenuState,
    onPage: (MainMenuPage) -> Unit,
    onAction: (MainMenuItemId) -> Unit,
    onToggle: (MainMenuItemId) -> Unit
) {
    val label = item.labelArg?.let { stringResource(item.labelRes, it) } ?: stringResource(item.labelRes)
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(item.id.icon(), contentDescription = null) },
        trailingIcon = when (item.kind) {
            MainMenuKind.TOGGLE -> ({ if (state.isOn(item.id)) Icon(Icons.Filled.Check, contentDescription = null) })
            MainMenuKind.FAMILY, MainMenuKind.VALUE_CHOICE ->
                ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) })
            MainMenuKind.ACTION -> null
        },
        enabled = item.enabled,
        onClick = {
            when (item.kind) {
                MainMenuKind.ACTION -> onAction(item.id)
                MainMenuKind.TOGGLE -> onToggle(item.id)
                MainMenuKind.FAMILY, MainMenuKind.VALUE_CHOICE -> item.opens?.let(onPage)
            }
        },
        modifier = Modifier.testTag("menu.${item.id.name.lowercase()}")
            // TalkBack : une bascule est une case cochée / non cochée (le menu reste ouvert).
            .then(if (item.kind == MainMenuKind.TOGGLE) Modifier.semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(state.isOn(item.id))
            } else Modifier)
    )
}

/**
 * Rangées 1 et 2 du premier niveau (`MenuIconRow`) : trois cases icône + libellé
 * de largeur égale. Une bascule active a le fond `secondaryContainer` (jamais le
 * jaune d'outil, §3.2) ; TalkBack : bouton, ou case cochée / non cochée.
 */
@Composable
private fun MenuIconRow(
    items: List<MainMenuItem>,
    state: MainMenuState,
    onAction: (MainMenuItemId) -> Unit,
    onToggle: (MainMenuItemId) -> Unit
) {
    Row(
        Modifier.widthIn(min = 252.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            val on = item.kind == MainMenuKind.TOGGLE && state.isOn(item.id)
            val label = stringResource(item.labelRes)
            val base = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .testTag("menu.${item.id.name.lowercase()}")
            val interaction = if (item.kind == MainMenuKind.TOGGLE) {
                base.toggleable(value = on, role = Role.Checkbox) { onToggle(item.id) }
            } else {
                base.clickable(role = Role.Button) { onAction(item.id) }
            }
            Column(
                interaction.padding(vertical = 8.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(item.id.icon(), contentDescription = null,
                    tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp).height(32.dp))
            }
        }
    }
}

/**
 * Retour système (Android 13+) reçu par la FENÊTRE du menu tant que [enabled] :
 * rappel enregistré sur le dispatcher de la vue du Popup, priorité « superposition »,
 * après celui du Popup (le plus récent de même priorité est appelé en premier).
 */
@Composable
private fun PopupPageBackHandler(enabled: Boolean, onBack: () -> Unit) {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val view = androidx.compose.ui.platform.LocalView.current
    val current = androidx.compose.runtime.rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(view, enabled) {
        val unregister = if (enabled) PopupBack33.register(view) { current.value() } else null
        onDispose { unregister?.invoke() }
    }
}

@androidx.annotation.RequiresApi(33)
private object PopupBack33 {
    /** Enregistre [onBack] sur la fenêtre de [view] ; rend la désinscription (null : pas de fenêtre). */
    fun register(view: android.view.View, onBack: () -> Unit): (() -> Unit)? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = android.window.OnBackInvokedCallback { onBack() }
        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        return { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

/** Égalité sur le RGB (ignore l'alpha) — les préréglages et fonds sont opaques. */
internal fun Color.sameRgb(o: Color): Boolean {
    fun ch(v: Float) = (v * 255f + 0.5f).toInt()
    return ch(red) == ch(o.red) && ch(green) == ch(o.green) && ch(blue) == ch(o.blue)
}
