package com.minou.mvrviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.GeoCalibration
import com.minou.mvrviewer.mvr.GeoCalibrationStatus
import com.minou.mvrviewer.mvr.GeoSurvey
import com.minou.mvrviewer.mvr.GeoThreePoint
import com.minou.mvrviewer.mvr.Vec2
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

// ASSISTANT « CALIBRATION GPS EN TROIS POINTS » (vue plan) — partie PURE, testée
// en JVM. Portage À L'IDENTIQUE de `ThreePointCalibrationSession` et de la
// logique du panneau `ThreePointCalibrationPanel` (iOS,
// ThreePointCalibrationView.swift, commit 85a3668). Parcours, textes et seuils :
// SPEC-calibration-3-points.md. Les maths sont dans [GeoThreePoint].
//
// RÈGLE GPS (comme iOS) : la vue plan n'OBSERVE PAS cette session — seuls le
// panneau et le calque de dessin la lisent, sinon chaque tic de mesure (0,5 s)
// recomposerait tout le plan.

/** Texte d'une ressource avec ses arguments, choisi par la logique pure. */
data class CalText(val id: Int, val args: List<Any> = emptyList())

/**
 * Un fix GPS BRUT (tous les fix des fournisseurs, même identiques).
 * - [key] : clé d'unicité (Android `Location.time`) — un fix compte une fois ;
 * - [time] : horodatage (s) dans la MÊME base que l'horloge de la session ;
 * - [accuracy] : précision horizontale (m), ≤ 0 si inconnue (`hasAccuracy()` faux).
 */
data class RawFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val key: Double,
    val time: Double
)

/** Étape du parcours (emplacements 0, 1, 2 ; 3 = point de contrôle). */
sealed interface ThreePointStage {
    /** Aller au point `i` puis le toucher sur le plan. */
    data class Approach(val i: Int) : ThreePointStage
    /** Endroit touché : moyenne GPS en cours (ne plus bouger). */
    data class Measuring(val i: Int) : ThreePointStage
    /** Point mesuré : attend « Point suivant » / « Calculer ». */
    data class Measured(val i: Int) : ThreePointStage
    /** Les points sont mesurés : précision, alertes, « Appliquer ». */
    data object Result : ThreePointStage
}

/**
 * État du parcours — transcription SANS changement de logique de
 * `ThreePointCalibrationSession` (iOS). Différences de plomberie seulement :
 * - le temps vient de [clock] (secondes, même base que [RawFix.time]) ;
 * - les fixes arrivent par [onRawFix] (iOS : `rawFixHandler`), le dernier fix
 *   connu par [lastRawFix] (iOS : `geo.lastRawFix`) ;
 * - la minuterie de 0,5 s est tenue par l'écran tant que [sampling] est vrai :
 *   il appelle [tick].
 */
class ThreePointCalibrationSession(
    /** Emprise du plan (mm monde) : les repères proposés restent dedans. */
    val bounds: GeoThreePoint.Bounds?,
    /** Éléments RECONNAISSABLES du plan (mm monde). */
    val landmarks: List<Vec2> = emptyList(),
    initialStepLength: Double = GeoThreePoint.defaultStepLength,
    private val clock: () -> Double,
    private val lastRawFix: () -> RawFix? = { null },
    private val onStepLengthChanged: (Double) -> Unit = {}
) {
    companion object {
        /** Emplacement du point de contrôle (4e point, facultatif). */
        const val CONTROL_SLOT = 3

        /** Longueur de pas mémorisée : défaut explicite hors bornes (clé absente = 0). */
        fun storedStepLength(stored: Double): Double =
            if (stored >= GeoThreePoint.stepLengthMin && stored <= GeoThreePoint.stepLengthMax) stored
            else GeoThreePoint.defaultStepLength
    }

    var stage by mutableStateOf<ThreePointStage>(ThreePointStage.Approach(0)); private set
    /** Points mesurés par EMPLACEMENT (0, 1, 2 ; 3 = contrôle). */
    val points = mutableStateListOf<GeoThreePoint.Point?>(null, null, null, null)
    /** Endroit touché pour le point en cours (mm monde). */
    var pin by mutableStateOf<Vec2?>(null); private set
    /** Repères proposés (mm monde), par emplacement. */
    val targets = mutableStateListOf<Vec2?>(null, null, null, null)
    /** Un point de contrôle a été demandé (4 emplacements au lieu de 3). */
    var hasControl by mutableStateOf(false); private set
    /** Emplacement ÉCARTÉ du calcul. */
    var discarded by mutableStateOf<Int?>(null); private set
    /** Erreur prévue la plus forte sur l'emprise (m). */
    var edgePrecision by mutableStateOf<Double?>(null); private set
    /** Avancement de la mesure en cours (0…1). */
    var progress by mutableStateOf(0.0); private set
    var fixCount by mutableStateOf(0); private set
    /** Précision du dernier fix reçu pendant la mesure (m). */
    var lastAccuracy by mutableStateOf<Double?>(null); private set
    /** Aucun fix assez précis pour l'instant (> 20 m). */
    var lowAccuracy by mutableStateOf(false); private set
    /** Aucun fix du tout depuis le toucher (passé 3 s). */
    var waitingForFix by mutableStateOf(false); private set
    /** « Utiliser quand même » disponible. */
    var canForce by mutableStateOf(false); private set
    var fit by mutableStateOf<GeoThreePoint.Fit?>(null); private set
    /** On REFAIT un point : après sa mesure, on recalcule directement. */
    var redoing by mutableStateOf(false); private set
    /** La mesure écoute les fixes et attend les tics de 0,5 s. */
    var sampling by mutableStateOf(false); private set

    /** Longueur de pas (m) — réglage personnel, mémorisé par appareil, borné 0,50–1,00. */
    var stepLength by mutableStateOf(storedStepLength(initialStepLength)); private set

    fun updateStepLength(v: Double) {
        val c = v.coerceIn(GeoThreePoint.stepLengthMin, GeoThreePoint.stepLengthMax)
        stepLength = c
        onStepLengthChanged(c)
    }

    /** Stepper iOS : ±0,05 dans les bornes. */
    fun stepLengthBy(direction: Int) {
        val next = stepLength + direction * GeoThreePoint.stepLengthIncrement
        if (next < GeoThreePoint.stepLengthMin - 1e-9 || next > GeoThreePoint.stepLengthMax + 1e-9) return
        updateStepLength(next)
    }

    private val samples = ArrayList<GeoThreePoint.Sample>()
    /** Tous les fixes ≤ 100 m (repli de « Utiliser quand même »). */
    private val looseSamples = ArrayList<GeoThreePoint.Sample>()
    private val seenFixes = HashSet<Double>()
    private var startDate: Double? = null
    private var firstGoodDate: Double? = null
    /** Dernier emplacement mesuré : là où se tient l'utilisateur. */
    private var lastMeasured: Int? = null

    /** Emplacement concerné par l'étape courante. */
    val currentIndex: Int
        get() = when (val s = stage) {
            is ThreePointStage.Approach -> s.i
            is ThreePointStage.Measuring -> s.i
            is ThreePointStage.Measured -> s.i
            ThreePointStage.Result -> usedSlots.lastOrNull() ?: 2
        }

    /** Nombre d'emplacements du parcours (3, ou 4 avec le contrôle). */
    val slotCount: Int get() = if (hasControl) 4 else 3

    /** Emplacements qui entrent dans le calcul (mesurés, non écartés), dans l'ordre. */
    val usedSlots: List<Int> get() = points.indices.filter { points[it] != null && it != discarded }

    val measuredPoints: List<GeoThreePoint.Point> get() = usedSlots.mapNotNull { points[it] }

    /** Emplacement correspondant à l'indice [i] d'un résultat de [fit]. */
    fun slot(forFitIndex: Int): Int {
        val used = usedSlots
        return if (forFitIndex in used.indices) used[forFitIndex] else forFitIndex
    }

    /** Emplacements des points désignés comme faux au résultat. */
    val suspectSlots: Set<Int>
        get() {
            if (stage != ThreePointStage.Result) return emptySet()
            val o = fit?.outlier ?: return emptySet()
            val idx = if (o.suspects.isEmpty()) (if (o.certain) listOf(o.index) else emptyList()) else o.suspects
            return idx.map { slot(it) }.toSet()
        }

    // ---- Gestes ---------------------------------------------------------------

    /** Toucher sur le plan (vue de dessus), en mm monde. */
    fun tap(world: Vec2) {
        when (val s = stage) {
            is ThreePointStage.Approach -> { pin = world; startMeasuring(s.i) }
            // Corriger l'endroit : la mesure GPS continue (on n'a pas bougé).
            is ThreePointStage.Measuring -> pin = world
            is ThreePointStage.Measured -> {
                pin = world
                points[s.i] = points[s.i]?.copy(worldX = world.x, worldY = world.y)
            }
            ThreePointStage.Result -> Unit
        }
    }

    /** « Point suivant » / « Calculer ». */
    fun next() {
        val s = stage as? ThreePointStage.Measured ?: return
        val i = s.i
        if (redoing || i >= 2) { computeResult(); return }
        val n = i + 1
        pin = null
        val p0 = points[0]; val p1 = points[1]
        if (n == 1 && p0 != null) {
            targets[1] = GeoThreePoint.suggestSecondTarget(p0.world, bounds, landmarks)
        } else if (n == 2 && p0 != null && p1 != null) {
            targets[2] = GeoThreePoint.suggestThirdTarget(p0.world, p1.world, bounds, landmarks)
        }
        stage = ThreePointStage.Approach(n)
    }

    /** « Ajouter un point de contrôle » depuis le résultat. */
    fun addControlPoint() {
        if (stage != ThreePointStage.Result || hasControl) return
        val slot = CONTROL_SLOT
        val pts = points.take(slot).filterNotNull().map { it.world }
        if (pts.size != 3) return
        val here = lastMeasured?.let { points[it] }?.world ?: pts[2]
        hasControl = true
        discarded = null
        targets[slot] = GeoThreePoint.suggestControlTarget(pts, here, bounds, landmarks)
        points[slot] = null
        pin = null
        fit = null
        edgePrecision = null
        redoing = false
        stage = ThreePointStage.Approach(slot)
    }

    /** « Écarter le point n » (4 points mesurés) : recalcul avec les trois autres. */
    fun discard(slot: Int) {
        if (stage != ThreePointStage.Result || slot !in points.indices || points[slot] == null ||
            usedSlots.size < 4) return
        discarded = slot
        computeResult()
    }

    /** « Refaire la mesure » du point courant (même endroit touché). */
    fun remeasure() {
        val s = stage as? ThreePointStage.Measured ?: return
        points[s.i] = null
        startMeasuring(s.i)
    }

    /** « Refaire le point i » depuis le résultat : le repère est l'endroit touché la 1re fois. */
    fun redo(i: Int) {
        if (i !in points.indices || i >= slotCount) return
        stopSampling()
        points[i]?.let { targets[i] = it.world }
        points[i] = null
        if (discarded == i) discarded = null
        pin = null
        fit = null
        edgePrecision = null
        redoing = true
        stage = ThreePointStage.Approach(i)
    }

    /** « Recommencer » : on repart du point 1. */
    fun restart() {
        stopSampling()
        for (k in points.indices) { points[k] = null; targets[k] = null }
        pin = null
        fit = null
        edgePrecision = null
        hasControl = false
        discarded = null
        lastMeasured = null
        redoing = false
        stage = ThreePointStage.Approach(0)
    }

    /** « Utiliser quand même » : termine avec les fixes peu précis. */
    fun forceFinish() {
        val s = stage as? ThreePointStage.Measuring ?: return
        finishMeasuring(s.i, if (samples.isEmpty()) looseSamples.toList() else samples.toList())
    }

    /**
     * Fermeture du panneau ou disparition du plan : plus aucun fix écouté. Une
     * mesure interrompue est à refaire (retour à « toucher l'endroit »).
     */
    fun cancel() {
        stopSampling()
        val s = stage
        if (s is ThreePointStage.Measuring) {
            pin = null
            progress = 0.0
            stage = ThreePointStage.Approach(s.i)
        }
    }

    /** La calibration à appliquer (deux ancres synthétiques + relevé), ou null. */
    fun calibration(epochSeconds: Double): Pair<List<GeoAnchor>, GeoSurvey>? {
        val f = fit ?: return null
        val pts = measuredPoints
        if (pts.size < 3 || pts.size != f.pointCount) return null
        return GeoCalibration.fromThreePoint(f, pts, edgePrecision, epochSeconds)
    }

    // ---- Guidage live -------------------------------------------------------

    /** Ajustement PROVISOIRE des points déjà mesurés hors [excluded] (au moins deux). */
    fun provisionalFit(excluded: Int): GeoThreePoint.Fit? {
        val pts = usedSlots.filter { it != excluded }.mapNotNull { points[it] }
        if (pts.size < 2) return null
        return GeoThreePoint.fit(pts)
    }

    /** Ajustement provisoire seulement s'il est COHÉRENT. */
    fun reliableProvisionalFit(excluded: Int): GeoThreePoint.Fit? =
        provisionalFit(excluded)?.takeIf { it.consistent }

    /** D'où l'on part vers le repère [i] : le point mesuré juste avant (contrôle : le dernier mesuré). */
    fun departure(i: Int): GeoThreePoint.Point? {
        if (i < 1) return null
        if (i == CONTROL_SLOT) return lastMeasured?.let { points[it] } ?: points[2]
        return points[i - 1]
    }

    // ---- Mesure -------------------------------------------------------------

    private fun startMeasuring(i: Int) {
        stopSampling()
        stage = ThreePointStage.Measuring(i)
        samples.clear(); looseSamples.clear(); seenFixes.clear()
        progress = 0.0
        fixCount = 0
        lastAccuracy = null
        lowAccuracy = false
        waitingForFix = false
        canForce = false
        startDate = clock()
        firstGoodDate = null
        lastRawFix()?.let { ingest(it) }
        sampling = true
    }

    private fun stopSampling() { sampling = false }

    /** Fix brut reçu des fournisseurs : compté seulement pendant une mesure. */
    fun onRawFix(fix: RawFix) {
        if (!sampling) return
        ingest(fix)
    }

    /** [allowEarlier] : accepter un fix ANTÉRIEUR au toucher (filet du fix immobile). */
    private fun ingest(l: RawFix, allowEarlier: Boolean = false) {
        val start = startDate ?: return
        if (!(l.accuracy > 0)) return
        if (!allowEarlier && l.time < start - 2) return
        if (!seenFixes.add(l.key)) return
        lastAccuracy = l.accuracy
        val s = GeoThreePoint.Sample(l.latitude, l.longitude, l.accuracy)
        if (l.accuracy <= GeoThreePoint.looseSampleAccuracy) looseSamples.add(s)
        if (l.accuracy <= GeoThreePoint.maxSampleAccuracy) {
            samples.add(s)
            if (firstGoodDate == null) firstGoodDate = clock()
        }
        fixCount = samples.size
    }

    /** Tic de 0,5 s pendant la mesure. */
    fun tick() {
        val s = stage as? ThreePointStage.Measuring ?: return
        if (!sampling) return
        val start = startDate ?: return
        val now = clock()
        // Un fix arrivé sans passer par le rappel compte s'il est nouveau.
        lastRawFix()?.let { ingest(it) }
        // Appareil IMMOBILE qui ne re-livre aucun fix : passé 3 s, le dernier fix
        // connu compte — une fois — s'il a moins de 30 s.
        val last = lastRawFix()
        if (seenFixes.isEmpty() && now - start >= GeoThreePoint.staleFixDelay && last != null &&
            now - last.time <= GeoThreePoint.staleFixMaxAge) {
            ingest(last, allowEarlier = true)
        }
        lowAccuracy = samples.isEmpty() && (lastAccuracy ?: 0.0) > GeoThreePoint.maxSampleAccuracy
        waitingForFix = seenFixes.isEmpty() && now - start >= GeoThreePoint.staleFixDelay
        canForce = samples.isEmpty() && looseSamples.isNotEmpty() &&
            now - start >= GeoThreePoint.forceAfterSeconds
        val good = firstGoodDate
        if (good == null) { progress = 0.0; return }
        // Le temps compte depuis le PREMIER fix assez précis ; 10 s avec ≥ 3 fixes
        // distincts, 20 s sinon — la barre suit la durée qui s'applique.
        val elapsed = now - good
        val target = if (samples.size >= GeoThreePoint.minDistinctFixes)
            GeoThreePoint.minMeasureSeconds else GeoThreePoint.maxMeasureSeconds
        progress = minOf(1.0, elapsed / target)
        if (elapsed >= target) finishMeasuring(s.i, samples.toList())
    }

    private fun finishMeasuring(i: Int, list: List<GeoThreePoint.Sample>) {
        stopSampling()
        val p = pin ?: return
        val avg = GeoThreePoint.average(list) ?: return
        points[i] = GeoThreePoint.Point(p.x, p.y, avg.latitude, avg.longitude, avg.accuracy, avg.used)
        lastMeasured = i
        progress = 1.0
        stage = ThreePointStage.Measured(i)
    }

    private fun computeResult() {
        stopSampling()
        redoing = false
        val f = GeoThreePoint.fit(measuredPoints)
        fit = f
        edgePrecision = f?.edgePrecision(bounds)
        stage = ThreePointStage.Result
    }
}

// ---- Écran : logique d'affichage (transcription du panneau iOS) ---------------

/** Action d'un bouton du résultat. */
data class CalAction(val kind: Kind, val text: CalText, val slot: Int? = null) {
    enum class Kind { APPLY, APPLY_ANYWAY, DISCARD, REDO, ADD_CONTROL, RESTART }
}

/** Alerte (orange, triangle) ou légende (gris secondaire). */
data class CalWarning(val text: CalText, val caption: Boolean = false)

/** Écran de RÉSULTAT (iOS `result`, `precisionLines`, `warnings`, `resultActions`). */
data class CalResultDisplay(
    val precisionNearMeters: Int?,
    val edgeMeters: Int?,
    val weakEdges: Boolean,
    val precisionLines: List<CalText>,
    /** Résidus à afficher (« Écarts : … m ») ; null = points confondus. */
    val residuals: List<Double>?,
    /** Numéros (1-based) des EMPLACEMENTS dessinés en rouge. */
    val suspectPoints: List<Int>,
    val warnings: List<CalWarning>,
    val primary: CalAction,
    val secondary: List<CalAction>
)

/** Guidage en marche : texte + mode (« Encore » ou « Parcouru »). */
data class CalLiveGuidance(
    val remaining: Boolean,
    /** Position provisoire (mm monde) quand l'ajustement est cohérent. */
    val provisionalWorld: Vec2?,
    val meters: Double,
    val text: CalText
)

object ThreePointDisplay {

    /** Résultat — [slots] : emplacement de chaque indice de `fit` ; [compact] : hauteur compacte. */
    fun result(
        fit: GeoThreePoint.Fit?, edgePrecision: Double?, hasControl: Boolean, slots: List<Int>,
        compact: Boolean = false
    ): CalResultDisplay {
        val restart = CalAction(CalAction.Kind.RESTART, CalText(R.string.cal3_restart))
        if (fit == null) {
            return CalResultDisplay(
                null, null, false, emptyList(), null, emptyList(),
                listOf(CalWarning(CalText(R.string.cal3_coincident))), restart, emptyList()
            )
        }
        fun slot(i: Int) = if (i in slots.indices) slots[i] else i
        val near = maxOf(1, GeoThreePoint.roundToInt(fit.precision))
        val edgeM = edgePrecision?.let { e -> GeoThreePoint.roundToInt(e).takeIf { it > near } }
        val weak = edgePrecision?.let { it > GeoThreePoint.weakEdgeRatio * maxOf(1.0, fit.precision) } ?: false
        val lines = if (edgeM != null) listOf(
            CalText(R.string.cal3_precision_near, listOf(near)),
            CalText(R.string.cal3_precision_edges, listOf(edgeM))
        ) else listOf(CalText(R.string.cal3_precision_estimated, listOf(near)))

        val o = fit.outlier
        val red = if (o == null) emptyList() else {
            val idx = if (o.suspects.isEmpty()) (if (o.certain) listOf(o.index) else emptyList()) else o.suspects
            idx.map { slot(it) + 1 }.sorted()
        }

        val warnings = ArrayList<CalWarning>()
        if (o != null) {
            val d = maxOf(1, GeoThreePoint.roundToInt(o.offset))
            val names = o.suspects.map { slot(it) + 1 }
            if (o.certain) {
                warnings.add(CalWarning(CalText(R.string.cal3_point_wrong, listOf(slot(o.index) + 1, d))))
            } else if (names.size == 2) {
                warnings.add(CalWarning(CalText(R.string.cal3_two_suspects, listOf(names[0], names[1], d))))
                if (fit.pointCount >= 4) {
                    warnings.add(CalWarning(CalText(R.string.cal3_most_likely, listOf(slot(o.index) + 1)), caption = true))
                }
            } else {
                warnings.add(CalWarning(CalText(R.string.cal3_several_wrong)))
            }
        } else if (fit.largeResiduals && !fit.scaleSuspicious) {
            warnings.add(CalWarning(CalText(R.string.cal3_large_residuals,
                listOf(GeoThreePoint.roundToInt(fit.detectionLimit)))))
        }
        if (fit.scaleSuspicious) {
            warnings.add(CalWarning(CalText(R.string.cal3_scale_warning,
                listOf(GeoThreePoint.roundToInt(kotlin.math.abs(fit.scaleDeviationPercent))))))
        }
        if (fit.pointsTooClose) {
            warnings.add(CalWarning(CalText(R.string.cal3_too_close)))
        } else if (fit.nearlyAligned) {
            warnings.add(CalWarning(CalText(R.string.cal3_nearly_aligned)))
        }
        if (o == null && !fit.largeResiduals && !hasControl && weak && !compact) {
            warnings.add(CalWarning(CalText(R.string.cal3_weak_edges), caption = true))
        }

        // Actions : la principale n'est « Appliquer » que si rien ne s'y oppose.
        val canControl = !hasControl
        val applyAnyway = CalAction(CalAction.Kind.APPLY_ANYWAY, CalText(R.string.cal3_apply_anyway))
        val addControl = CalAction(CalAction.Kind.ADD_CONTROL, CalText(R.string.cal3_add_control))
        fun discard(s: Int) = CalAction(CalAction.Kind.DISCARD, CalText(R.string.cal3_discard_point, listOf(s + 1)), s)
        val primary: CalAction
        val secondary: List<CalAction>
        if (o != null && o.certain) {
            val s = slot(o.index)
            primary = if (fit.pointCount >= 4) discard(s)
            else CalAction(CalAction.Kind.REDO, CalText(R.string.cal3_redo_point, listOf(s + 1)), s)
            secondary = listOf(applyAnyway)
        } else if (o != null) {
            primary = when {
                canControl -> addControl
                fit.pointCount >= 4 && o.suspects.size == 2 -> discard(slot(o.index))
                else -> restart
            }
            secondary = listOf(applyAnyway)
        } else if (fit.pointsTooClose) {
            primary = restart
            secondary = listOf(applyAnyway)
        } else if (canControl && fit.largeResiduals && !fit.scaleSuspicious) {
            primary = addControl
            secondary = listOf(applyAnyway)
        } else {
            primary = CalAction(CalAction.Kind.APPLY, CalText(R.string.cal3_apply))
            secondary = listOf(if (canControl && weak) addControl else restart)
        }
        return CalResultDisplay(near, edgeM, weak, lines, fit.residuals, red, warnings, primary, secondary)
    }

    /** Résidus « 2,1 · 2,7 · 1,9 » : une décimale au format de la langue (arrondi ICU, au pair). */
    fun residualsText(residuals: List<Double>, locale: Locale): String =
        residuals.joinToString(" · ") { oneDecimal(it, locale) }

    /** Une décimale comme `formatted(.number.precision(.fractionLength(1)))` (ICU : demi au pair). */
    fun oneDecimal(x: Double, locale: Locale): String = decimals(x, 1, locale)

    /** Longueur de pas « 0,75 m » (deux décimales, U+00A0 avant l'unité). */
    fun stepLengthText(m: Double, locale: Locale): String = decimals(m, 2, locale) + " m"

    private fun decimals(x: Double, digits: Int, locale: Locale): String {
        if (!x.isFinite()) return x.toString()
        // Chiffres les plus courts du double, puis arrondi au pair — comme ICU.
        val bd = BigDecimal(java.lang.Double.toString(x)).setScale(digits, RoundingMode.HALF_EVEN)
        // Mise en forme de la langue AVEC séparateur de milliers, comme iOS
        // (`formatted`) et le web (Intl) : « 1 234,5 » / « 1,234.5 ». La valeur est
        // déjà arrondie : le format ne fait que l'écrire.
        val nf = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = true
            minimumFractionDigits = digits
            maximumFractionDigits = digits
            roundingMode = RoundingMode.HALF_EVEN
        }
        val body = nf.format(bd.abs())
        return if (bd.signum() < 0) "-$body" else body
    }

    /** Ligne d'état « Calibration » (iOS `GeoCalibrationStatus.text`). */
    fun statusText(status: GeoCalibrationStatus): CalText = when (status) {
        GeoCalibrationStatus.Denied -> CalText(R.string.calib_status_denied)
        is GeoCalibrationStatus.ThreePoints ->
            if (status.edge != null) CalText(R.string.cal3_status_three_edges, listOf(status.precision, status.edge))
            else CalText(R.string.cal3_status_three, listOf(status.precision))
        GeoCalibrationStatus.OneAnchor -> CalText(R.string.calib_status_one)
        is GeoCalibrationStatus.Anchors -> CalText(R.string.calib_status_anchors, listOf(status.count))
        GeoCalibrationStatus.Uncalibrated -> CalText(R.string.calib_status_none)
    }

    /**
     * Guidage en marche (iOS `liveGuidance`) : « Encore ≈ K pas » si les points
     * déjà mesurés (hors repère visé) forment un ajustement COHÉRENT, sinon
     * « Parcouru : ≈ M pas » depuis le point de départ (GPS seul, sans minimum) ;
     * rien en refaisant un point sans ajustement fiable.
     * [provisional] = points mesurés hors l'emplacement visé (≥ 2 pour un ajustement).
     */
    fun liveGuidance(
        provisional: List<GeoThreePoint.Point>, target: Vec2?, departure: GeoThreePoint.Point?,
        redoing: Boolean, fixLatitude: Double, fixLongitude: Double, stepLength: Double
    ): CalLiveGuidance? {
        val f = if (provisional.size >= 2) GeoThreePoint.fit(provisional) else null
        if (f != null && f.consistent && target != null) {
            val here = f.world(fixLatitude, fixLongitude)
            val left = Vec2.distance(here, target) / 1000
            return CalLiveGuidance(true, here, left,
                CalText(R.string.cal3_remaining, listOf(GeoThreePoint.steps(left, stepLength))))
        }
        if (!redoing && departure != null) {
            val walked = GeoThreePoint.enu(fixLatitude, fixLongitude, departure.latitude, departure.longitude).length()
            return CalLiveGuidance(false, null, walked,
                CalText(R.string.cal3_walked, listOf(GeoThreePoint.walkedSteps(walked, stepLength))))
        }
        return null
    }

    /** Guidage en marche de la session vers l'emplacement [i] (fix live valide). */
    fun liveGuidance(session: ThreePointCalibrationSession, i: Int, fixLatitude: Double, fixLongitude: Double):
        CalLiveGuidance? {
        val provisional = session.usedSlots.filter { it != i }.mapNotNull { session.points[it] }
        return liveGuidance(provisional, session.targets[i], session.departure(i), session.redoing,
            fixLatitude, fixLongitude, session.stepLength)
    }

    /** Consigne d'approche d'un repère proposé : pas, distance, flèche. */
    data class Approach(val steps: Int, val meters: Double, val instruction: CalText,
                        val distanceLabel: CalText, val arrowLabel: CalText)

    /** Repère [i] (0-based) depuis [departure] — « Marchez environ N pas vers le repère i+1. ». */
    fun approach(target: Vec2, departure: Vec2, i: Int, stepLength: Double): Approach {
        val meters = Vec2.distance(target, departure) / 1000
        val n = GeoThreePoint.steps(meters, stepLength)
        return Approach(
            n, meters,
            CalText(R.string.cal3_walk_steps, listOf(n, i + 1)),
            CalText(R.string.cal3_approx_meters, listOf(GeoThreePoint.roundToInt(meters))),
            CalText(R.string.cal3_arrow_steps, listOf(n))
        )
    }

    /** « Point n mesuré · ± N m » (précision ≥ 1). */
    fun measuredTitle(i: Int, accuracy: Double?): CalText =
        CalText(R.string.cal3_point_measured, listOf(i + 1, accuracy?.let { maxOf(1, GeoThreePoint.roundToInt(it)) } ?: 0))

    /** « Calculer » au 3e point, au contrôle et en refaisant un point ; sinon « Point suivant ». */
    fun measuredPrimary(i: Int, redoing: Boolean): CalText =
        if (i >= 2 || redoing) CalText(R.string.cal3_compute) else CalText(R.string.cal3_next_point)

    /** Libellé de la ligne d'état : « Résultat », « Point de contrôle » ou « Point n sur 3 ». */
    fun stepLabel(stage: ThreePointStage, currentIndex: Int): CalText = when {
        stage == ThreePointStage.Result -> CalText(R.string.cal3_result)
        currentIndex == ThreePointCalibrationSession.CONTROL_SLOT -> CalText(R.string.cal3_control_point)
        else -> CalText(R.string.cal3_point_of_3, listOf(currentIndex + 1))
    }

    /** Pastille GPS : couleur selon la précision (vert ≤ 10 m, orange ≤ 20 m, rouge au-delà). */
    enum class GpsLevel { GOOD, FAIR, POOR }

    fun gpsLevel(accuracy: Double): GpsLevel = when {
        accuracy <= 10 -> GpsLevel.GOOD
        accuracy <= GeoThreePoint.maxSampleAccuracy -> GpsLevel.FAIR
        else -> GpsLevel.POOR
    }

    /**
     * Position APPROXIMATIVE seule (guide C8, `UserLocationFeed.approximate`) : Android
     * la floute à ~2 km, donc aucun fix n'atteint les 20 m d'une mesure ni les 100 m de
     * « Utiliser quand même » — la mesure attendrait pour toujours. Le panneau le dit
     * dès l'ouverture (à toutes les étapes) et propose les paramètres de l'app.
     */
    fun approximateNotice(approximate: Boolean): CalText? =
        if (approximate) CalText(R.string.cal3_approximate_location) else null

    /**
     * « Précision GPS insuffisante… restez à découvert, patientez » : seulement quand
     * attendre peut aboutir — jamais en position approximative ([approximateNotice]).
     */
    fun showsLowAccuracy(lowAccuracy: Boolean, approximate: Boolean): Boolean = lowAccuracy && !approximate

    /** Précision affichée : pendant la mesure le dernier fix reçu, sinon la position live (> 0). */
    fun currentAccuracy(session: ThreePointCalibrationSession, liveAccuracy: Double?): Double? {
        if (session.stage is ThreePointStage.Measuring) session.lastAccuracy?.let { return it }
        return liveAccuracy?.takeIf { it > 0 }
    }

    /** Pastilles d'étape : remplissage de l'emplacement [i]. */
    enum class Dot { DISCARDED, MEASURED, CURRENT, EMPTY }

    fun dot(session: ThreePointCalibrationSession, i: Int): Dot = when {
        session.discarded == i -> Dot.DISCARDED
        session.points.getOrNull(i) != null -> Dot.MEASURED
        session.stage != ThreePointStage.Result && i == session.currentIndex -> Dot.CURRENT
        else -> Dot.EMPTY
    }
}

// ---- Repères rouges des points de calibration (iOS drawUserLocation) ----------

/**
 * Cibles rouges posées sur le plan à chaque point de calibration (SPEC §2.6 et
 * §8) : les points RÉELLEMENT mesurés d'une calibration 3 points, sinon les
 * ancres ([GeoCalibration.markerPoints]). Dessin iOS : cercle de 10 pt + croix
 * de 18 pt, trait rouge de 1,5 pt.
 */
object CalibrationMarkers {
    const val RADIUS_DP = 5f
    const val CROSS_HALF_DP = 9f
    const val STROKE_DP = 1.5f

    /**
     * Visibles quand la position est affichée OU que la pose d'ancre est armée ;
     * jamais pendant l'assistant 3 points (l'ancienne calibration n'a plus cours).
     */
    fun visible(showLocation: Boolean, anchorTapArmed: Boolean, assistantActive: Boolean): Boolean =
        !assistantActive && (showLocation || anchorTapArmed)

    /** Points (mm monde) à marquer, ou aucun si les repères sont masqués. */
    fun points(calibration: GeoCalibration, showLocation: Boolean, anchorTapArmed: Boolean,
               assistantActive: Boolean): List<Pair<Float, Float>> =
        if (visible(showLocation, anchorTapArmed, assistantActive)) calibration.markerPoints else emptyList()
}

// ---- Emprise et éléments reconnaissables (iOS threePointBounds / Landmarks) ----

object ThreePointPlanData {

    /**
     * Ignore les points isolés très loin du gros de la troupe (médiane + rayon
     * couvrant 95 %) — portage de `PlanView.robustBounds` (iOS), même filtre que
     * le cadrage : au-delà de 20 points seulement.
     */
    fun robustBounds(points: List<Vec2>): List<Vec2> {
        if (points.size <= 20) return points
        val xs = points.map { it.x }.sorted(); val ys = points.map { it.y }.sorted()
        val cx = xs[xs.size / 2]; val cy = ys[ys.size / 2]
        val dists = points.map { kotlin.math.hypot(it.x - cx, it.y - cy) }.sorted()
        val cutoff = dists[((dists.size - 1).toDouble() * 0.95).toInt()]
        if (!(cutoff > 0)) return points
        val kept = points.filter { kotlin.math.hypot(it.x - cx, it.y - cy) <= cutoff }
        return kept.ifEmpty { points }
    }

    /**
     * Emprise MONDE (mm) où proposer les repères : gros de la scène (positions des
     * projecteurs + centres des structures, hors points isolés) + les 4 coins du
     * plan importé s'il est affiché. `null` si inutilisable (un côté ≤ 1 m).
     */
    fun bounds(fixtures: List<Vec2>, structureCenters: List<Vec2>, refPlanCorners: List<Vec2>?): GeoThreePoint.Bounds? {
        val kept = robustBounds(fixtures + structureCenters) + (refPlanCorners ?: emptyList())
        val first = kept.firstOrNull() ?: return null
        var loX = first.x; var loY = first.y; var hiX = first.x; var hiY = first.y
        for (p in kept) {
            loX = minOf(loX, p.x); loY = minOf(loY, p.y)
            hiX = maxOf(hiX, p.x); hiY = maxOf(hiY, p.y)
        }
        val b = GeoThreePoint.Bounds(Vec2(loX, loY), Vec2(hiX, hiY))
        return if (b.isUsable) b else null
    }

    /** Structure visible : emprise au sol (mm monde) ; `min == max` si inconnue. */
    data class StructureFootprint(val min: Vec2, val max: Vec2)

    /**
     * Éléments RECONNAISSABLES (mm monde), dans l'ordre iOS (égalités,
     * échantillonnage) : projecteurs visibles ; structures visibles (centre, et
     * les 4 coins si l'emprise dépasse 30 cm) ; sommets des calques visibles du
     * plan importé, un sur max(1, total / 15 000) — [dxfVertices] parcourt TOUS
     * les sommets visibles dans l'ordre polylignes puis remplissages, avec un
     * compteur global k, et n'émet (en monde) que ceux où k % stride == 0.
     */
    fun landmarks(
        fixtures: List<Vec2>, structures: List<StructureFootprint>,
        dxfVertexCount: Int, dxfVertices: (stride: Int, emit: (Vec2) -> Unit) -> Unit
    ): List<Vec2> {
        val out = ArrayList<Vec2>(fixtures.size + structures.size * 5)
        out.addAll(fixtures)
        for (st in structures) {
            val lo = st.min; val hi = st.max
            if (!lo.x.isFinite() || !lo.y.isFinite() || !hi.x.isFinite() || !hi.y.isFinite()) continue
            out.add(Vec2((lo.x + hi.x) / 2, (lo.y + hi.y) / 2))
            if (hi.x - lo.x > 300 || hi.y - lo.y > 300) {
                out.add(Vec2(lo.x, lo.y)); out.add(Vec2(hi.x, lo.y))
                out.add(Vec2(hi.x, hi.y)); out.add(Vec2(lo.x, hi.y))
            }
        }
        if (dxfVertexCount > 0) dxfVertices(maxOf(1, dxfVertexCount / 15_000)) { out.add(it) }
        return out
    }
}
