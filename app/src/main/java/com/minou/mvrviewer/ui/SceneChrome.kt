package com.minou.mvrviewer.ui

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R
import kotlin.math.roundToInt

// =============================================================================
// HABILLAGE COMMUN DES DEUX VUES (SPEC-menus-android §3.1, §3.3, §3.4, §3.5,
// §3.14) : rangée du haut, pastille « Vue », pile bas-droite Rechercher /
// Recadrer, bloc d'état 3D, bandeau de mode du plan. Les décisions sont dans
// SceneChromeRules (pur, testé).
// =============================================================================

/** Taille des ronds de la scène (rangée du haut, pile bas-droite). */
internal val SCENE_ROUND = 48.dp

/** Bord des ronds (marge à l'écran). */
private val EDGE = 4.dp

/**
 * RANGÉE DU HAUT, identique dans les deux vues : ← à gauche ; à droite
 * `[Sélection par cadre] [Multi-sélection] [⋯]`. Un bouton permanent dont l'outil
 * est ancré est passé à null (masqué, [SceneTopRowRules]). [onRowBottom] reçoit le
 * bas de la rangée (px racine) ; [onTrailing] le cadre du groupe de droite.
 */
@Composable
fun BoxScope.SceneTopRow(
    backDescription: String,
    onBack: () -> Unit,
    rect: ToolSpec?,
    multi: ToolSpec?,
    menu: @Composable () -> Unit,
    onRowBottom: (Float) -> Unit = {},
    onTrailing: (Rect) -> Unit = {}
) {
    FilledIconButton(
        onClick = onBack,
        colors = neutralRoundButtonColors(),
        modifier = Modifier.align(Alignment.TopStart).padding(EDGE).size(SCENE_ROUND).testTag("topRow.back")
            .onGloballyPositioned { onRowBottom(it.boundsInRoot().bottom) }
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription) }
    Row(
        Modifier.align(Alignment.TopEnd).padding(EDGE).onGloballyPositioned { onTrailing(it.boundsInRoot()) },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        rect?.let { ToolbarButton(it, Modifier.size(SCENE_ROUND).testTag("topRow.rect")) }
        multi?.let { ToolbarButton(it, Modifier.size(SCENE_ROUND).testTag("topRow.multi")) }
        menu()
    }
}

/**
 * PASTILLE « VUE » (§3.3) : capsule, libellé « 3D · Face » / « Plan · Dessus »,
 * ▾ ; toucher → menu des points de vue de la vue courante + la bascule 3D ↔ plan.
 * TalkBack : « Vue », état = le libellé affiché. Étiquette de test `viewPill`.
 */
@Composable
fun ViewPill(
    surface: ProjectSurface,
    current: CameraViewpoint,
    onViewpoint: (CameraViewpoint) -> Unit,
    onShowPlan: () -> Unit,
    onShow3D: () -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(ViewPillModel.labelFormatRes(surface), stringResource(ViewPillModel.valueRes(surface, current)))
    val cd = stringResource(R.string.pill_view)
    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(50), shadowElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("viewPill")
                // TalkBack : bouton « Vue », état = le libellé affiché (« 3D · Face »).
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = cd; stateDescription = label }
        ) {
            Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (surface == ProjectSurface.PLAN) Icons.Filled.VerticalAlignBottom else Icons.Filled.ViewInAr,
                    contentDescription = null, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ViewPillModel.sections(surface, current).forEachIndexed { i, section ->
                if (i > 0) HorizontalDivider()
                section.titleRes?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp))
                }
                section.entries.forEach { e ->
                    DropdownMenuItem(
                        text = { Text(stringResource(e.labelRes)) },
                        trailingIcon = if (e.checked) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                        onClick = {
                            open = false
                            when (e.action) {
                                ViewPillAction.VIEWPOINT -> e.viewpoint?.let(onViewpoint)
                                ViewPillAction.PROJECTION_TOP -> {}
                                ViewPillAction.SHOW_PLAN -> onShowPlan()
                                ViewPillAction.SHOW_3D -> onShow3D()
                            }
                        },
                        modifier = Modifier.testTag("viewPill.${e.action.name.lowercase()}" +
                            (e.viewpoint?.let { ".${it.name.lowercase()}" } ?: ""))
                            // Le point de vue courant est annoncé « sélectionné ».
                            .semantics { selected = e.checked }
                    )
                }
            }
        }
    }
}

/**
 * PILE BAS-DROITE (§3.4) : [Rechercher] puis [Recadrer] dessous, même gabarit. La
 * loupe ouvre un champ « N° de projecteur » à gauche de la pile ; un résultat vide
 * affiche « Aucun projecteur nº %s » sous le champ. La pile MONTE au-dessus de ce
 * qui occupe le bas de sa colonne ([obstacles], cadres racine SANS montée) d'une
 * hauteur MESURÉE ([PanelPlacementMath.clearanceLift], cadre de la pile mesuré
 * avant sa montée : pas de boucle), jamais plus haut que [topLimitPx].
 * [onSearch] rend vrai si un projecteur a été trouvé.
 */
@Composable
fun BoxScope.SearchReframeStack(
    showReframe: Boolean,
    onReframe: () -> Unit,
    onSearch: (String) -> Boolean,
    obstacles: List<Rect>,
    topLimitPx: Float,
    bottomPadding: Dp = 12.dp
) {
    val density = LocalDensity.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var notFound by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val lift = with(density) {
        PanelPlacementMath.clearanceLift(anchor, obstacles, topLimitPx, PanelPlacementMath.CORNER_STACK_GAP.dp.toPx())
    }
    val liftOffset = Modifier.offset { IntOffset(0, -lift.roundToInt()) }
    Column(
        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomPadding)
            // Cadre SANS montée : mesuré avant le décalage.
            .onGloballyPositioned { anchor = it.anchorBounds() }
            .then(liftOffset),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledIconButton(
            onClick = { searchOpen = !searchOpen; notFound = null; if (!searchOpen) keyboard?.hide() },
            colors = neutralRoundButtonColors(),
            modifier = Modifier.size(SCENE_ROUND).testTag("search")
        ) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_fixture_cd)) }
        if (showReframe) FilledIconButton(
            onClick = onReframe,
            colors = neutralRoundButtonColors(),
            modifier = Modifier.size(SCENE_ROUND).testTag("reframe")
        ) { Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.reframe_cd)) }
    }
    if (searchOpen) {
        // Bas de la loupe, mesuré depuis le bas de la vue.
        val searchBottom = bottomPadding + if (showReframe) SCENE_ROUND + 12.dp else 0.dp
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        fun run() {
            val q = query.trim()
            if (q.isEmpty()) return
            if (onSearch(q)) {
                notFound = null; searchOpen = false; keyboard?.hide()
            } else {
                // Le clavier se range : le message, SOUS le champ, serait dessous
                // (constat émulateur) ; le champ reste ouvert pour corriger.
                notFound = q; keyboard?.hide()
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp), shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 12.dp + SCENE_ROUND + 8.dp, bottom = searchBottom)
                .then(liftOffset)
                .width(200.dp)
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it; notFound = null }, singleLine = true,
                placeholder = { Text(stringResource(R.string.search_fixture_id_hint), style = MaterialTheme.typography.bodySmall) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
                trailingIcon = {
                    IconButton(onClick = { run() }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_fixture_cd))
                    }
                },
                modifier = Modifier.focusRequester(focus).testTag("searchField")
                    // Clavier physique (tablette, Chromebook) : Entrée lance aussi la recherche.
                    .onPreviewKeyEvent { e ->
                        if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyUp) { run(); true }
                        else false
                    }
            )
        }
        notFound?.let { q ->
            Surface(
                shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 12.dp + SCENE_ROUND + 8.dp, bottom = searchBottom)
                    .then(liftOffset)
                    // Posé SOUS le champ : décalé vers le bas de sa propre hauteur + 4 dp.
                    .layout { m, c ->
                        val p = m.measure(c)
                        layout(p.width, p.height) { p.place(0, p.height + 4.dp.roundToPx()) }
                    }
                    .widthIn(max = 200.dp)
                    .testTag("searchNone")
            ) {
                Text(stringResource(R.string.search_none_fmt, q), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

/** Capsule du bloc d'état (`MvrBadge`) : surface, `bodySmall`, une ligne. */
@Composable
private fun StatusBadge(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50), shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { content() }
    }
}

/**
 * BLOC D'ÉTAT de la 3D (§3.5), sous ← : une colonne de pastilles, chacune
 * seulement si elle a quelque chose à dire ([StatusBlockRules]), puis la pastille
 * de synchro (projet partagé). Bornée en largeur ([maxWidth], par le cadre mesuré
 * de la pastille « Vue »).
 */
@Composable
fun StatusBlock(
    lines: List<StatusLine>,
    syncBadge: (@Composable () -> Unit)?,
    maxWidth: Dp?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier).testTag("statusBlock"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { line ->
            StatusBadge {
                when (line) {
                    is StatusLine.Loading -> Text(
                        (line.stepRes?.let { stringResource(it) + " " } ?: "") + "${line.percent} %",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusLine.Starting -> Text(stringResource(R.string.status_loading_3d),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    is StatusLine.Objects -> Text(
                        pluralStringResource(R.plurals.status_objects, line.count, line.count) +
                            (if (line.truncated) stringResource(R.string.status_truncated) else ""),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    is StatusLine.HiddenClasses -> Text(
                        pluralStringResource(R.plurals.status_hidden_classes, line.count, line.count),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusLine.GdtfLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.status_gdtf_loading), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        CircularProgressIndicator(Modifier.padding(start = 6.dp).size(12.dp), strokeWidth = 1.5.dp)
                    }
                }
            }
        }
        syncBadge?.invoke()
    }
}

/**
 * BANDEAU DE MODE du plan (§3.14), en haut sous la pastille : rappelle que le
 * masquage ou l'isolement est armé ; l'icône de tête quitte le mode.
 */
@Composable
fun PlanModeBannerBar(banner: PlanModeBanner, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFFFFC400), contentColor = Color(0xFF111111),
        shape = RoundedCornerShape(10.dp), shadowElevation = 3.dp,
        modifier = modifier.testTag("modeBanner")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            IconButton(onClick = onExit, modifier = Modifier.size(40.dp).testTag("modeBanner.exit")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
            }
            Text(stringResource(banner.textRes), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
        }
    }
}

/** Indicateur « Génération du PDF… » posé au-dessus de la vue courante (PDF de câblage du ⋯). */
@Composable
fun BusyBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = RoundedCornerShape(12.dp),
        modifier = modifier.testTag("busyBanner")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp))
        }
    }
}
