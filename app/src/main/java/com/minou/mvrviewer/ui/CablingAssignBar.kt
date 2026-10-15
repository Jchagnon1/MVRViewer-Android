package com.minou.mvrviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R

/** Retours haptiques du mode affectation (iOS `AssignHaptics`). */
enum class CablingHaptic {
    /** Impact franc : un projecteur vient d'être affecté (ou un cadre écrit). */
    ASSIGNED,
    /** Impact léger : un projecteur vient d'être retiré. */
    REMOVED,
    /** Avertissement : la voie n'existe plus, rien n'a été écrit. */
    REFUSED,
    /** Cran : ◀ ▶ (voie précédente / suivante). */
    STEP;

    companion object {
        /** Retour d'un toucher selon son issue. */
        fun of(result: CablingToggleResult): CablingHaptic = when (result) {
            CablingToggleResult.ASSIGNED -> ASSIGNED
            CablingToggleResult.REMOVED -> REMOVED
            CablingToggleResult.VOIE_MISSING -> REFUSED
        }

        /** Retour d'un cadre : écrit → affectation ; voie disparue → refus. */
        fun ofFrame(written: Boolean): CablingHaptic = if (written) ASSIGNED else REFUSED
    }
}

/** Joue un retour haptique du mode affectation sur la vue hôte. */
internal fun cablingHaptic(view: android.view.View, kind: CablingHaptic) {
    val api30 = android.os.Build.VERSION.SDK_INT >= 30
    val constant = when (kind) {
        CablingHaptic.ASSIGNED ->
            if (api30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.LONG_PRESS
        CablingHaptic.REMOVED -> android.view.HapticFeedbackConstants.CONTEXT_CLICK
        CablingHaptic.REFUSED ->
            if (api30) android.view.HapticFeedbackConstants.REJECT else android.view.HapticFeedbackConstants.LONG_PRESS
        CablingHaptic.STEP -> android.view.HapticFeedbackConstants.CLOCK_TICK
    }
    view.performHapticFeedback(constant)
}

/** Texte des ressources (chaîne ou pluriel) choisi par la logique pure. */
@Composable
internal fun CablingText.text(): String = when (this) {
    is CablingText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is CablingText.Plural -> pluralStringResource(id, count, *args.toTypedArray())
}

private val BarInk = Color(0xFF111111)
private val BarSecondary = Color(0xFF666666)
private val BarRed = Color(0xFFD32F2F)
private val BarOrange = Color(0xFFEF6C00)
/** Jaune d'identité (`accentFill`) et son encre (`accentInk`) — jamais de blanc sur jaune. */
internal val AccentFill = Color(0xFFFFC400)
internal val AccentInk = Color(0xFF7A5600)

/**
 * BARRE D'AFFECTATION du câblage sur le plan (SPEC-cablage-plan §4), en bas du
 * plan, déplaçable et réductible ([MovablePanel]) :
 * - en-tête : pastille de la couleur du distributeur + libellé FIGÉ de la voie ;
 *   la croix QUITTE le mode et laisse sur le plan ;
 * - ligne 2 : charge « 2 900 / 3 680 W » ou « 37 / 512 canaux » (rouge + triangle
 *   dès la surcharge, « ? » orange si une valeur est inconnue, sablier tant
 *   qu'elles se calculent) ; à droite la phase « L2 » et le compte, qui
 *   rétrécit (jusqu'à 75 %) pour que la charge tienne TOUJOURS sur une ligne ;
 *   voie disparue : « ⚠ Voie supprimée » en orange ;
 * - ligne 3 : ◀, ▶, cadre (jaune quand il est armé), puis « Terminé ».
 */
@Composable
internal fun CablingAssignBar(
    display: CablingAssignBarDisplay,
    frameActive: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleFrame: () -> Unit,
    onDone: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    MovablePanel(
        id = PanelLayoutStore.PanelID.CABLING_ASSIGN_PLAN, modifier = modifier,
        // Réduite : il ne reste que l'en-tête — pastille + voie, à sa taille normale.
        collapsedHeader = {
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(Color(display.colorArgb))
                    .border(1.dp, Color(0x33000000), CircleShape)
            )
            Text(
                display.title,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = BarInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    ) {
        Surface(
            color = Color.White, contentColor = BarInk,
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.width(CablingAssignBarPlacement.BAR_WIDTH.dp)
        ) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
                // ---- En-tête : pastille + voie ; croix « Quitter l'affectation » ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape).background(Color(display.colorArgb))
                            .border(1.dp, Color(0x33000000), CircleShape)
                    )
                    Text(
                        display.title,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    IconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cabling_exit_assign),
                            tint = BarSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                // ---- Ligne 2 : charge + compte (ou « Voie supprimée ») ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 2.dp, bottom = 8.dp)
                ) {
                    if (display.voieMissing) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = BarOrange,
                            modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.cabling_voie_deleted),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            color = BarOrange, maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    } else {
                        LoadView(display)
                        Spacer(Modifier.width(8.dp))
                        CountView(display, Modifier.weight(1f))
                    }
                }
                // ---- Ligne 3 : ◀ ▶ cadre … Terminé ----
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp)) {
                    RoundToolButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        stringResource(R.string.cabling_prev_voie), active = false,
                        enabled = display.previousEnabled, onClick = onPrevious)
                    RoundToolButton(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        stringResource(R.string.cabling_next_voie), active = false,
                        enabled = display.nextEnabled, onClick = onNext)
                    RoundToolButton(Icons.Filled.Crop, stringResource(R.string.cabling_frame_select),
                        active = frameActive && display.frameEnabled,
                        enabled = display.frameEnabled, onClick = onToggleFrame)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDone) {
                        Text(stringResource(R.string.common_done), color = AccentInk,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** « 2 900 / 3 680 W » : UNE ligne, jamais coupée ; rouge + triangle si surcharge ; sablier sinon. */
@Composable
private fun LoadView(display: CablingAssignBarDisplay) {
    val load = display.load
    if (load == null) {
        if (display.loadPending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        return
    }
    val color = if (display.overloadMark) BarRed else BarInk
    val overload = stringResource(R.string.cabling_overload)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (display.overloadMark) {
            Icon(Icons.Filled.Warning, contentDescription = overload, tint = BarRed,
                modifier = Modifier.size(16.dp).padding(end = 2.dp))
        }
        Text(
            load.text(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold, color = color,
            maxLines = 1, softWrap = false
        )
        display.unknownMark?.let { mark ->
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = mark.text(), tint = BarOrange,
                modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
    }
}

/** « L2 · 4 projecteurs » ou « Touchez pour affecter » ; rétrécit jusqu'à 75 % pour laisser la charge entière. */
@Composable
private fun CountView(display: CablingAssignBarDisplay, modifier: Modifier) {
    val count = display.count?.text() ?: return
    val phase = display.phaseText
    val full = if (phase != null) "$phase  $count" else count
    val base = MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterEnd) {
        val avail = with(density) { maxWidth.toPx() }
        val w = remember(full, base, density) {
            measurer.measure(full, style = base, maxLines = 1, softWrap = false).size.width.toFloat()
        }
        val scale = CablingCountFit.scale(w, avail)
        Text(
            buildCountText(phase, count),
            style = base.copy(fontSize = base.fontSize * scale),
            color = BarSecondary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
        )
    }
}

private fun buildCountText(phase: String?, count: String) =
    androidx.compose.ui.text.buildAnnotatedString {
        if (phase != null) {
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold))
            append(phase)
            pop()
            append("  ")
        }
        append(count)
    }

/** Bouton rond de 44 dp : actif = jaune d'identité + symbole noir ; éteint (35 %) au bout du parcours. */
@Composable
private fun RoundToolButton(icon: ImageVector, label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(if (active) AccentFill else BarInk.copy(alpha = 0.07f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Color.Black else BarInk,
            modifier = Modifier.size(22.dp))
    }
}

/** Le compte cède la place à la charge : échelle de police, jamais sous 75 %. */
object CablingCountFit {
    fun scale(textWidth: Float, available: Float): Float {
        if (textWidth <= 0f || available <= 0f || textWidth <= available) return 1f
        return (available / textWidth).coerceIn(0.75f, 1f)
    }
}
