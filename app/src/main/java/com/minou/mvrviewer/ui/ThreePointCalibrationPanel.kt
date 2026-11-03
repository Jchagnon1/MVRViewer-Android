package com.minou.mvrviewer.ui

import android.content.Context
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.GeoSurvey
import com.minou.mvrviewer.mvr.GeoThreePoint
import com.minou.mvrviewer.mvr.Vec2
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ASSISTANT 3 POINTS — panneau, minuterie et dessin sur le plan (iOS
// ThreePointCalibrationPanel / ThreePointCalibrationCanvas / Drawing). La logique
// est dans ThreePointCalibration.kt (testée) ; ici, seulement le rendu.

/** Texte d'une ressource avec ses arguments. */
@Composable
internal fun CalText.text(): String =
    if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())

private val Ink = Color(0xFF111111)
private val Secondary = Color(0xFF666666)
private val Orange = Color(0xFFEF6C00)
private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFD32F2F)
private val Blue = Color(0xFF007AFF)

/** Longueur de pas mémorisée PAR APPAREIL (iOS `MVRViewer.calibrationStepLength`). */
object CalibrationStepLengthStore {
    private const val PREFS = "mvrviewer.display"
    private const val KEY = "calibrationStepLength"

    /** 0 si jamais réglée (la session prend alors le défaut 0,75 m). */
    fun load(ctx: Context): Double = runCatching {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY)) 0.0 else Double.fromBits(p.getLong(KEY, 0L))
    }.getOrDefault(0.0)

    fun save(ctx: Context, v: Double) {
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, v.toRawBits()).apply()
        }
    }
}

/** Minuterie de 0,5 s de la mesure (iOS `Timer`), seulement pendant qu'elle échantillonne. */
@Composable
fun ThreePointCalibrationTimer(session: ThreePointCalibrationSession) {
    val sampling = session.sampling
    LaunchedEffect(session, sampling) {
        if (!sampling) return@LaunchedEffect
        while (session.sampling) {
            delay(500)
            session.tick()
        }
    }
}

/**
 * Panneau DÉPLAÇABLE et RÉDUCTIBLE en bas du plan : il dit quoi faire, montre la
 * précision GPS en direct et l'avancement de la mesure — le plan reste visible et
 * manipulable au-dessus. [live] = position GPS courante ; [denied] = permission
 * refusée ; [compact] = hauteur compacte (textes secondaires et pas masqués).
 */
@Composable
fun ThreePointCalibrationPanel(
    session: ThreePointCalibrationSession,
    liveState: State<Location?>,
    denied: Boolean,
    compact: Boolean,
    onApply: (List<GeoAnchor>, GeoSurvey) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val live = liveState.value
    MovablePanel(
        id = PanelLayoutStore.PanelID.GEO_CALIBRATION_3_PLAN, modifier = modifier,
        collapsedHeader = {
            Icon(Icons.Filled.TrackChanges, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.cal3_title), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp))
        }
    ) {
        Surface(
            color = Color.White, contentColor = Ink,
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.width(CablingAssignBarPlacement.BAR_WIDTH.dp)
        ) {
            Column(
                Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
            ) {
                // ---- En-tête : titre COURT + croix ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.TrackChanges, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.cal3_title), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close),
                            tint = Secondary, modifier = Modifier.size(20.dp))
                    }
                }
                StatusRow(session, live, denied)
                when (val st = session.stage) {
                    is ThreePointStage.Approach -> ApproachContent(session, st.i, live, compact)
                    is ThreePointStage.Measuring -> MeasuringContent(session, compact)
                    is ThreePointStage.Measured -> MeasuredContent(session, st.i)
                    ThreePointStage.Result -> ResultContent(session, compact) {
                        session.calibration(System.currentTimeMillis() / 1000.0)?.let { (a, s) -> onApply(a, s) }
                    }
                }
            }
        }
    }
}

// ---- Ligne d'état : étapes + GPS --------------------------------------------

@Composable
private fun StatusRow(session: ThreePointCalibrationSession, live: Location?, denied: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until session.slotCount) {
                val fill = when (ThreePointDisplay.dot(session, i)) {
                    ThreePointDisplay.Dot.DISCARDED -> Color(0x73888888)
                    ThreePointDisplay.Dot.MEASURED -> AccentFill
                    ThreePointDisplay.Dot.CURRENT -> AccentFill.copy(alpha = 0.35f)
                    ThreePointDisplay.Dot.EMPTY -> Color(0x33888888)
                }
                Box(Modifier.size(9.dp).clip(CircleShape).background(fill)
                    .border(0.5.dp, Color(0x40000000), CircleShape))
            }
        }
        Text(ThreePointDisplay.stepLabel(session.stage, session.currentIndex).text(),
            style = MaterialTheme.typography.labelMedium, color = Secondary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        GpsBadge(session, live, denied)
    }
}

@Composable
private fun GpsBadge(session: ThreePointCalibrationSession, live: Location?, denied: Boolean) {
    val acc = ThreePointDisplay.currentAccuracy(session, live?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble())
    when {
        denied -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOff, contentDescription = null, tint = Red, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.cal3_location_denied), style = MaterialTheme.typography.labelMedium,
                color = Red, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
        }
        acc != null -> {
            val c = when (ThreePointDisplay.gpsLevel(acc)) {
                ThreePointDisplay.GpsLevel.GOOD -> Green
                ThreePointDisplay.GpsLevel.FAIR -> Orange
                ThreePointDisplay.GpsLevel.POOR -> Red
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = c, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.cal3_gps_accuracy, GeoThreePoint.roundToInt(acc)),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = c, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(stringResource(R.string.cal3_waiting_gps), style = MaterialTheme.typography.labelMedium,
                color = Secondary, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

// ---- Contenu par étape ---------------------------------------------------------

@Composable
private fun ApproachContent(session: ThreePointCalibrationSession, i: Int, live: Location?, compact: Boolean) {
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val target = session.targets[i]
    val from = session.departure(i)
    if (i == 0 && !session.redoing) {
        Text(stringResource(R.string.cal3_first_instruction), style = bodyStyle)
    } else if (session.redoing) {
        Text(stringResource(R.string.cal3_go_back, i + 1), style = bodyStyle, fontWeight = FontWeight.SemiBold)
        LiveGuidance(session, i, live)
    } else if (target != null && from != null) {
        val ap = ThreePointDisplay.approach(target, from.world, i, session.stepLength)
        Text(ap.instruction.text(), style = bodyStyle, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ap.distanceLabel.text(), style = MaterialTheme.typography.labelLarge, color = Secondary)
            Spacer(Modifier.weight(1f))
            LiveGuidance(session, i, live)
        }
        if (!compact) {
            if (i == ThreePointCalibrationSession.CONTROL_SLOT) {
                Text(stringResource(R.string.cal3_control_hint), style = MaterialTheme.typography.bodySmall, color = Secondary)
            }
            Text(stringResource(R.string.cal3_arrived_hint), style = MaterialTheme.typography.bodySmall, color = Secondary)
            StepLengthRow(session)
        }
    }
}

/** « Encore ≈ K pas » (points connus cohérents) ou « Parcouru : ≈ M pas ». */
@Composable
private fun LiveGuidance(session: ThreePointCalibrationSession, i: Int, live: Location?) {
    val l = live ?: return
    if (!l.hasAccuracy() || l.accuracy <= 0f) return
    val g = ThreePointDisplay.liveGuidance(session, i, l.latitude, l.longitude) ?: return
    Text(g.text.text(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StepLengthRow(session: ThreePointCalibrationSession) {
    val locale = LocalConfiguration.current.locales[0]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.cal3_step_length), style = MaterialTheme.typography.bodySmall, color = Secondary)
        Spacer(Modifier.weight(1f))
        Text(ThreePointDisplay.stepLengthText(session.stepLength, locale),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        IconButton(onClick = { session.stepLengthBy(-1) }, modifier = Modifier.size(36.dp),
            enabled = session.stepLength > GeoThreePoint.stepLengthMin + 1e-9) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.cal3_step_decrease))
        }
        IconButton(onClick = { session.stepLengthBy(1) }, modifier = Modifier.size(36.dp),
            enabled = session.stepLength < GeoThreePoint.stepLengthMax - 1e-9) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cal3_step_increase))
        }
    }
}

@Composable
private fun MeasuringContent(session: ThreePointCalibrationSession, compact: Boolean) {
    Text(stringResource(R.string.cal3_hold_still), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    LinearProgressIndicator(
        progress = { session.progress.toFloat() }, color = AccentFill,
        trackColor = Color(0x22000000), modifier = Modifier.fillMaxWidth()
    )
    val acc = session.lastAccuracy
    if (session.waitingForFix) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.cal3_waiting_gps), style = MaterialTheme.typography.bodySmall,
                color = Secondary, modifier = Modifier.padding(start = 6.dp))
        }
    } else if (session.lowAccuracy && acc != null) {
        WarningLine(CalText(R.string.cal3_low_accuracy, listOf(GeoThreePoint.roundToInt(acc))))
    } else if (!compact) {
        Text(stringResource(R.string.cal3_tap_again), style = MaterialTheme.typography.bodySmall, color = Secondary)
    }
    if (session.canForce) {
        SecondaryAction(stringResource(R.string.cal3_use_anyway), null) { session.forceFinish() }
    }
}

@Composable
private fun MeasuredContent(session: ThreePointCalibrationSession, i: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
        Text(ThreePointDisplay.measuredTitle(i, session.points[i]?.accuracy).text(),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Green,
            modifier = Modifier.padding(start = 6.dp))
    }
    PrimaryAction(ThreePointDisplay.measuredPrimary(i, session.redoing).text(), null) { session.next() }
    SecondaryAction(stringResource(R.string.cal3_measure_again), Icons.Filled.Refresh) { session.remeasure() }
}

@Composable
private fun ResultContent(session: ThreePointCalibrationSession, compact: Boolean, onApply: () -> Unit) {
    val fit = session.fit
    val d = ThreePointDisplay.result(fit, session.edgePrecision, session.hasControl, session.usedSlots, compact)
    d.precisionLines.forEachIndexed { k, line ->
        if (k == 0) Text(line.text(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        else Text(line.text(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
    d.residuals?.let { r ->
        val locale = LocalConfiguration.current.locales[0]
        Text(stringResource(R.string.cal3_deviations, ThreePointDisplay.residualsText(r, locale)),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = Secondary)
    }
    for (w in d.warnings) {
        if (w.caption) Text(w.text.text(), style = MaterialTheme.typography.bodySmall, color = Secondary)
        else WarningLine(w.text)
    }
    fun perform(a: CalAction) {
        when (a.kind) {
            CalAction.Kind.APPLY, CalAction.Kind.APPLY_ANYWAY -> onApply()
            CalAction.Kind.DISCARD -> a.slot?.let { session.discard(it) }
            CalAction.Kind.REDO -> a.slot?.let { session.redo(it) }
            CalAction.Kind.ADD_CONTROL -> session.addControlPoint()
            CalAction.Kind.RESTART -> session.restart()
        }
    }
    PrimaryAction(d.primary.text.text(), iconOf(d.primary.kind)) { perform(d.primary) }
    for (s in d.secondary) {
        SecondaryAction(s.text.text(), iconOf(s.kind)) { perform(s) }
    }
}

private fun iconOf(kind: CalAction.Kind): ImageVector? = when (kind) {
    CalAction.Kind.APPLY -> Icons.Filled.Check
    CalAction.Kind.APPLY_ANYWAY -> null
    CalAction.Kind.DISCARD -> Icons.Filled.RemoveCircleOutline
    CalAction.Kind.REDO -> Icons.Filled.Replay
    CalAction.Kind.ADD_CONTROL -> Icons.Filled.AddCircleOutline
    CalAction.Kind.RESTART -> Icons.Filled.Refresh
}

@Composable
private fun WarningLine(t: CalText) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Orange, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(t.text(), style = MaterialTheme.typography.bodySmall, color = Orange, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Action principale : jaune d'identité, encre NOIRE (jamais de blanc sur le jaune). */
@Composable
private fun PrimaryAction(label: String, icon: ImageVector?, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = AccentFill, contentColor = Color.Black)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryAction(label: String, icon: ImageVector?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
        Text(label)
    }
}

// ---- Dessin sur le plan ----------------------------------------------------------

/**
 * Calque de dessin de l'assistant (iOS ThreePointCalibrationCanvas) : observe la
 * session et le GPS sans recomposer la vue plan. Ne reçoit aucun toucher.
 * [toScreen] : mm monde → pixel (vue de dessus courante) ; [pxPerMm] : échelle.
 */
@Composable
fun ThreePointCalibrationOverlay(
    session: ThreePointCalibrationSession,
    liveState: State<Location?>,
    toScreen: (Vec2, Size) -> Offset,
    pxPerMm: (Size) -> Float,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val live = liveState.value
    val stage = session.stage
    // Étiquette « ≈ N pas » de la flèche (texte localisé, résolu ici).
    val arrow: Pair<Vec2, String>? = (stage as? ThreePointStage.Approach)?.let { st ->
        val t = session.targets[st.i]; val from = session.departure(st.i)
        if (t != null && from != null && !session.redoing)
            from.world to ThreePointDisplay.approach(t, from.world, st.i, session.stepLength).arrowLabel.text()
        else null
    }
    // Lecture des états observés (re-dessin quand ils changent).
    val pointsNow = session.points.toList()
    val targetsNow = session.targets.toList()
    val pin = session.pin
    val fit = session.fit
    val suspects = session.suspectSlots
    val discarded = session.discarded
    val provisional = (stage as? ThreePointStage.Approach)?.let { st ->
        if (live != null && live.hasAccuracy() && live.accuracy > 0f) session.reliableProvisionalFit(st.i) else null
    }
    val usedPoints = session.measuredPoints
    val usedSlots = session.usedSlots

    Canvas(modifier.fillMaxSize()) {
        val dp = density
        val sz = size
        fun s(v: Vec2) = toScreen(v, sz)
        val ppm = pxPerMm(sz)
        val ink = Color.Black
        val fill = AccentFill

        // Résultat : vecteurs d'écart (endroit touché → position GPS ajustée).
        if (stage == ThreePointStage.Result && fit != null) {
            for ((i, p) in usedPoints.withIndex()) {
                if (i >= fit.fittedWorld.size) break
                val a = s(p.world); val b = s(fit.fittedWorld[i])
                val slot = usedSlots.getOrElse(i) { i }
                val bad = slot in suspects
                val c = if (bad) Red else Color(0x99555555)
                drawLine(c, a, b, strokeWidth = (if (bad) 2f else 1.2f) * dp)
                drawCircle(c, radius = 4 * dp, center = b, style = Stroke(1.2f * dp))
            }
        }

        // Repère proposé + flèche depuis le point de départ.
        var capsule: Triple<Offset, Offset, String>? = null
        if (stage is ThreePointStage.Approach) {
            val target = targetsNow.getOrNull(stage.i)
            if (target != null) {
                val t = s(target)
                // Rayon ≈ 2 m à l'échelle, borné 14–40 dp : une cible, pas une zone.
                val r = max(14 * dp, min(40 * dp, 2_000f * ppm))
                drawCircle(fill.copy(alpha = 0.18f), radius = r, center = t)
                drawCircle(fill, radius = r, center = t,
                    style = Stroke(2.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7 * dp, 5 * dp))))
                drawLine(ink, Offset(t.x - 7 * dp, t.y), Offset(t.x + 7 * dp, t.y), strokeWidth = 1.5f * dp)
                drawLine(ink, Offset(t.x, t.y - 7 * dp), Offset(t.x, t.y + 7 * dp), strokeWidth = 1.5f * dp)
                badge(measurer, "${stage.i + 1}", Offset(t.x + r * 0.72f, t.y - r * 0.72f), fill, ink, false)
                if (arrow != null) {
                    val a = s(arrow.first)
                    arrowLine(a, t, stopShort = r + 4 * dp, startShort = 14 * dp)
                    capsule = Triple(a, t, arrow.second)
                }
            }
        }

        // Points mesurés ; un point écarté reste visible, grisé.
        for ((i, p) in pointsNow.withIndex()) {
            if (p == null) continue
            badge(measurer, "${i + 1}", s(p.world), if (discarded == i) Color(0xFFBFBFBF) else fill, ink, i in suspects)
        }
        if (pin != null && stage is ThreePointStage.Measuring) {
            val c = s(pin)
            drawCircle(fill, radius = 20 * dp, center = c,
                style = Stroke(3f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * dp, 4 * dp))))
            badge(measurer, "${stage.i + 1}", c, fill, ink, false)
        }

        // Position PROVISOIRE (points connus COHÉRENTS) pendant la marche.
        if (provisional != null && live != null) {
            val c = s(provisional.world(live.latitude, live.longitude))
            val rr = max(10 * dp, min(120 * dp, live.accuracy * 1000f * ppm))
            drawCircle(Blue.copy(alpha = 0.10f), radius = rr, center = c)
            drawCircle(Color.White, radius = 7 * dp, center = c)
            drawCircle(Blue, radius = 5 * dp, center = c)
        }

        capsule?.let { (a, t, label) -> stepCapsule(measurer, label, a, t, fill) }
    }
}

/** Pastille numérotée : jaune d'identité, chiffre NOIR, liseré blanc (rouge si suspect). */
private fun DrawScope.badge(
    measurer: androidx.compose.ui.text.TextMeasurer, text: String, c: Offset, fill: Color, ink: Color, bad: Boolean
) {
    val r = 11 * density
    drawCircle(if (bad) Red else Color.White, radius = r + 1.5f * density, center = c)
    drawCircle(fill, radius = r, center = c)
    val layout = measurer.measure(text, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink))
    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
}

/** Flèche pointillée de [from] vers [to], raccourcie aux deux bouts. */
private fun DrawScope.arrowLine(from: Offset, to: Offset, stopShort: Float, startShort: Float) {
    val dx = to.x - from.x; val dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len <= stopShort + startShort + 10 * density) return
    val ux = dx / len; val uy = dy / len
    val a = Offset(from.x + ux * startShort, from.y + uy * startShort)
    val b = Offset(to.x - ux * stopShort, to.y - uy * stopShort)
    drawLine(Color.White, a, b, strokeWidth = 5 * density, cap = StrokeCap.Round)
    drawLine(Color.Black, a, b, strokeWidth = 2.5f * density, cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * density, 6 * density)))
    val sx = -uy; val sy = ux
    val head = Path().apply {
        moveTo(b.x + ux * 4 * density, b.y + uy * 4 * density)
        lineTo(b.x - ux * 12 * density + sx * 7 * density, b.y - uy * 12 * density + sy * 7 * density)
        lineTo(b.x - ux * 12 * density - sx * 7 * density, b.y - uy * 12 * density - sy * 7 * density)
        close()
    }
    drawPath(head, Color.Black)
}

/**
 * Étiquette « ≈ N pas » sur capsule jaune, posée À CÔTÉ de la flèche (décalée
 * perpendiculairement de sa demi-étendue + 10 dp), côté haut de l'écran sauf si
 * elle déborde davantage de la zone sûre (marges 12 dp, 70 dp en haut), dessinée
 * en dernier ; masquée si la flèche est trop courte pour la porter.
 */
private fun DrawScope.stepCapsule(
    measurer: androidx.compose.ui.text.TextMeasurer, label: String, a: Offset, t: Offset, fill: Color
) {
    val d = density
    val layout = measurer.measure(label, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Black))
    val w = layout.size.width + 16 * d; val h = layout.size.height + 8 * d
    val dx = t.x - a.x; val dy = t.y - a.y
    val len = hypot(dx, dy)
    if (len <= w + 60 * d) return
    var nx = -dy / len; var ny = dx / len
    if (ny > 0 || (ny == 0f && nx < 0)) { nx = -nx; ny = -ny }
    val off = abs(nx) * w / 2 + abs(ny) * h / 2 + 10 * d
    fun rect(side: Float): Rect {
        val cx = (a.x + t.x) / 2 + side * nx * off; val cy = (a.y + t.y) / 2 + side * ny * off
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
    val safe = Rect(12 * d, 70 * d, size.width - 12 * d, size.height - 12 * d)
    fun overflow(r: Rect) = max(0f, safe.left - r.left) + max(0f, r.right - safe.right) +
        max(0f, safe.top - r.top) + max(0f, r.bottom - safe.bottom)
    var box = rect(1f)
    if (size.width > 0 && overflow(rect(-1f)) < overflow(box)) box = rect(-1f)
    drawRoundRect(Color.White, topLeft = Offset(box.left - 1 * d, box.top - 1 * d),
        size = Size(box.width + 2 * d, box.height + 2 * d), cornerRadius = CornerRadius((h + 2 * d) / 2))
    drawRoundRect(fill, topLeft = box.topLeft, size = box.size, cornerRadius = CornerRadius(h / 2))
    drawText(layout, topLeft = Offset(box.center.x - layout.size.width / 2f, box.center.y - layout.size.height / 2f))
}

// ---- Emprise et éléments repères, depuis la vue plan ---------------------------

/**
 * Entrées de l'assistant calculées depuis la vue plan (À APPELER HORS THREAD
 * PRINCIPAL) : emprise (iOS `threePointBounds`) et éléments reconnaissables
 * (`threePointLandmarks`). Les structures prennent l'emprise au sol de leur fil
 * de fer (boîte locale des arêtes, placée par l'instance) ; sans fil de fer, leur
 * origine seule.
 */
internal object ThreePointPlanInputs {

    class Inputs(val bounds: GeoThreePoint.Bounds?, val landmarks: List<Vec2>)

    private class Box3(val min: FloatArray, val max: FloatArray)

    private fun localBox(edges: FloatArray): Box3? {
        if (edges.size < 3) return null
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i + 2 < edges.size) {
            for (k in 0..2) { val v = edges[i + k]; if (v < mn[k]) mn[k] = v; if (v > mx[k]) mx[k] = v }
            i += 3
        }
        return Box3(mn, mx)
    }

    /** Boîte MONDE (3D) des 8 coins de la boîte locale placée par [m]. */
    private fun worldBox(b: Box3, m: dev.romainguy.kotlin.math.Mat4): Box3 {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            val x = if (cx == 0) b.min[0] else b.max[0]
            val y = if (cy == 0) b.min[1] else b.max[1]
            val z = if (cz == 0) b.min[2] else b.max[2]
            val w = floatArrayOf(
                m.x.x * x + m.y.x * y + m.z.x * z + m.w.x,
                m.x.y * x + m.y.y * y + m.z.y * z + m.w.y,
                m.x.z * x + m.y.z * y + m.z.z * z + m.w.z
            )
            for (k in 0..2) { if (w[k] < mn[k]) mn[k] = w[k]; if (w[k] > mx[k]) mx[k] = w[k] }
        }
        return Box3(mn, mx)
    }

    fun compute(
        fixturesWorld: List<Pair<String, Vec2>>,
        structurePoints: List<Pair<String, Vec2>>,
        wire: PlanWireframe.Built?,
        hidden: Set<String>,
        referencePlan: com.minou.mvrviewer.mvr.ReferencePlan?,
        hiddenRefLayers: Set<String>
    ): Inputs {
        // Structures : emprise au sol par instance (fil de fer) ou origine.
        class St(val key: String, val min: Vec2, val max: Vec2, val diag3: Double, val center3: DoubleArray)
        val structs = ArrayList<St>()
        if (wire != null && wire.instances.isNotEmpty()) {
            val boxes = HashMap<String, Box3?>()
            for (inst in wire.instances) {
                val local = boxes.getOrPut(inst.key) { wire.edgesByKey[inst.key]?.let(::localBox) }
                if (local != null) {
                    val wb = worldBox(local, inst.world)
                    val dx = (wb.max[0] - wb.min[0]).toDouble(); val dy = (wb.max[1] - wb.min[1]).toDouble()
                    val dz = (wb.max[2] - wb.min[2]).toDouble()
                    structs.add(St(inst.id, Vec2(wb.min[0].toDouble(), wb.min[1].toDouble()),
                        Vec2(wb.max[0].toDouble(), wb.max[1].toDouble()), kotlin.math.sqrt(dx * dx + dy * dy + dz * dz),
                        doubleArrayOf((wb.min[0] + wb.max[0]) / 2.0, (wb.min[1] + wb.max[1]) / 2.0, (wb.min[2] + wb.max[2]) / 2.0)))
                } else {
                    val o = Vec2(inst.cx.toDouble(), -inst.cy.toDouble())
                    structs.add(St(inst.id, o, o, 0.0, doubleArrayOf(o.x, o.y, inst.world.w.z.toDouble())))
                }
            }
        } else {
            for ((k, p) in structurePoints) structs.add(St(k, p, p, 0.0, doubleArrayOf(p.x, p.y, 0.0)))
        }
        // Structures DÉMESURÉES (iOS) : boîte > 55 % de la diagonale de la scène (centres).
        val oversized = HashSet<String>()
        if (structs.isNotEmpty()) {
            val mn = DoubleArray(3) { Double.MAX_VALUE }; val mx = DoubleArray(3) { -Double.MAX_VALUE }
            for (s in structs) for (k in 0..2) { mn[k] = minOf(mn[k], s.center3[k]); mx[k] = maxOf(mx[k], s.center3[k]) }
            val sceneDiag = kotlin.math.sqrt((0..2).sumOf { (mx[it] - mn[it]) * (mx[it] - mn[it]) })
            if (sceneDiag > 1) {
                val limit = sceneDiag * 0.55
                for (s in structs) if (s.diag3.isFinite() && s.diag3 > limit) oversized.add(s.key)
            }
        }
        val centers = structs.map { Vec2((it.min.x + it.max.x) / 2, (it.min.y + it.max.y) / 2) }

        // Plan importé (affiché) : coins pour l'emprise, sommets pour les repères.
        val rp = referencePlan?.takeIf { it.transform.visible }
        val corners = rp?.let { r ->
            val pl = r.plan; val t = r.transform
            listOf(t.worldXY(pl.minX, pl.minY), t.worldXY(pl.maxX, pl.minY),
                t.worldXY(pl.maxX, pl.maxY), t.worldXY(pl.minX, pl.maxY))
                .map { Vec2(it.first.toDouble(), it.second.toDouble()) }
        }
        val bounds = ThreePointPlanData.bounds(fixturesWorld.map { it.second }, centers, corners)

        val visibleFixtures = fixturesWorld.filter { it.first !in hidden }.map { it.second }
        val visibleStructs = structs.filter { it.key !in hidden && it.key !in oversized }
            .map { ThreePointPlanData.StructureFootprint(it.min, it.max) }
        val plan = rp?.plan?.takeIf { !it.isEmpty }
        var total = 0
        if (plan != null) {
            for (pl in plan.polylines) if (pl.layer !in hiddenRefLayers) total += pl.points.size / 2
            for (fl in plan.fills) if (fl.layer !in hiddenRefLayers) total += fl.rings.sumOf { it.size / 2 }
        }
        val landmarks = ThreePointPlanData.landmarks(visibleFixtures, visibleStructs, total) { stride, emit ->
            if (plan != null) {
                val t = rp.transform
                var k = 0
                fun each(arr: FloatArray) {
                    var i = 0
                    while (i + 1 < arr.size) {
                        if (k % stride == 0) {
                            val w = t.worldXY(arr[i], arr[i + 1])
                            emit(Vec2(w.first.toDouble(), w.second.toDouble()))
                        }
                        k++
                        i += 2
                    }
                }
                for (pl in plan.polylines) if (pl.layer !in hiddenRefLayers) each(pl.points)
                for (fl in plan.fills) if (fl.layer !in hiddenRefLayers) for (ring in fl.rings) each(ring)
            }
        }
        return Inputs(bounds, landmarks)
    }
}
