package com.minou.mvrviewer.mvr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vecteur 2D (double) — équivalent de `SIMD2<Double>` iOS. Les opérations
 * suivent l'ordre de `simd` composante par composante, pour retrouver les
 * MÊMES nombres que le code iOS (vecteurs canoniques au 1e-6 mm près).
 */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    operator fun div(k: Double) = Vec2(x / k, y / k)
    operator fun unaryMinus() = Vec2(-x, -y)

    /** `simd_length_squared` : x·x + y·y. */
    fun length2(): Double = x * x + y * y

    /** `simd_length` : √(x² + y²), sans `hypot` (mêmes bits qu'iOS). */
    fun length(): Double = sqrt(length2())

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        /** `simd_distance(a, b)` = |a − b|. */
        fun distance(a: Vec2, b: Vec2): Double = (a - b).length()
        fun dot(a: Vec2, b: Vec2): Double = a.x * b.x + a.y * b.y
    }
}

/** `k * v` (iOS `w[i] * pts[i]`). */
operator fun Double.times(v: Vec2) = Vec2(this * v.x, this * v.y)

/**
 * CALIBRATION GPS EN TROIS POINTS, GUIDÉE PAR UN NOMBRE DE PAS — portage À
 * L'IDENTIQUE de `GeoThreePoint` (iOS, `Models/GeoThreePointCalibration.swift`,
 * commit 85a3668). Spec canonique : `SPEC-calibration-3-points.md` (dépôt iOS).
 * Rejoué par les vecteurs `port-vectors/calibration-3pts/` (GeoThreePointVectorsTest).
 *
 * Repères :
 * - PLAN = repère MONDE MVR, en millimètres côté app, en MÈTRES dans les
 *   calculs (÷ 1000) — le MVR/DXF est en vraies unités, d'où une ÉCHELLE FIXÉE
 *   à 1 (1 m réel = 1000 mm de plan) ;
 * - ENU = mètres Est/Nord, projection équirectangulaire locale autour d'une
 *   origine (exactement la même formule que [GeoCalibration]).
 *
 * Le résultat se range dans le format synchronisé EXISTANT (deux ancres
 * synthétiques, cf. [syntheticAnchors]) + le relevé `survey`.
 *
 * Égalités : comme `Sequence.max(by:)` iOS, le PREMIER candidat gagne.
 */
object GeoThreePoint {

    // ---- Seuils (SPEC §6) ----------------------------------------------------

    const val earthRadius = 6_371_000.0
    /** Durée minimale d'immobilité par point (s). */
    const val minMeasureSeconds = 10.0
    /** Au-delà, on termine même avec peu de fixes distincts (s). */
    const val maxMeasureSeconds = 20.0
    /** Délai après lequel « Utiliser quand même » est proposé (s). */
    const val forceAfterSeconds = 30.0
    /** Un fix moins précis que ceci (m) n'entre pas dans la moyenne. */
    const val maxSampleAccuracy = 20.0
    /** Fixes ≤ 100 m gardés à part pour « Utiliser quand même » (iOS : `ingest`). */
    const val looseSampleAccuracy = 100.0
    /** Fixes distincts visés avant [maxMeasureSeconds]. */
    const val minDistinctFixes = 3
    /** Aucun fix nouveau depuis le toucher pendant ce délai (s) : le dernier fix connu compte… */
    const val staleFixDelay = 3.0
    /** …s'il a moins de cet âge (s). */
    const val staleFixMaxAge = 30.0
    /** Longueur de pas par défaut et bornes du réglage (m). */
    const val defaultStepLength = 0.75
    const val stepLengthMin = 0.5
    const val stepLengthMax = 1.0
    const val stepLengthIncrement = 0.05
    /** Base visée entre deux points (m), selon la taille du plan. */
    const val baseMin = 25.0
    const val baseMax = 40.0
    /** Base visée sans emprise connue (m). */
    const val baseDefault = 30.0
    /** Plancher des tolérances de cohérence (m). */
    const val minTolerance = 4.0
    /** En dessous de cette base (m) : « points trop proches ». */
    const val minBase = 10.0
    /** En dessous de cet angle (degrés) : « points presque alignés ». */
    const val minAngleWarning = 15.0
    /** Écart d'échelle toléré au minimum (fraction). */
    const val minScaleTolerance = 0.10
    /** Écart entre les deux ancres synthétiques (m). */
    const val syntheticAnchorSpan = 20.0
    /** Plus grand résidu (m) au-delà duquel un point de contrôle est proposé. */
    const val residualCaution = 4.0
    /** Bords jugés faibles au-delà de ce multiple de la précision près des points. */
    const val weakEdgeRatio = 2.0
    /** Repères 3 et contrôle : un élément du plan est préféré à ≥ 85 % du meilleur score… */
    const val landmarkScoreRatio = 0.85
    /** …repère 2 : s'il ne fait pas plus de 3 m moins bien. */
    const val landmarkSlack = 3.0
    /** Plafond du nombre d'éléments du plan examinés (échantillonnage). */
    const val maxLandmarks = 20_000

    // ---- Arrondis iOS ----------------------------------------------------------

    /** `Double.rounded()` Swift : au plus proche, demi vers l'EXTÉRIEUR (≠ `kotlin.math.round`). */
    fun roundHalfAway(x: Double): Double {
        if (!x.isFinite()) return x
        if (x < 0) return -roundHalfAway(-x)
        val f = floor(x)
        return if (x - f >= 0.5) f + 1 else f
    }

    /** `Int(x.rounded())` Swift. */
    fun roundToInt(x: Double): Int = roundHalfAway(x).toInt()

    // ---- Géodésie locale (même formule que GeoCalibration) ---------------------

    /** Écart de longitude ramené dans [−180°, 180°] (site à cheval sur l'antiméridien). */
    fun longitudeDelta(longitude: Double, origin: Double): Double {
        var d = (longitude - origin) % 360.0
        if (d > 180) d -= 360 else if (d < -180) d += 360
        return d
    }

    /** Longitude ramenée dans ]−180°, 180°]. */
    fun normalizedLongitude(longitude: Double): Double {
        var l = longitude % 360.0
        if (l > 180) l -= 360 else if (l <= -180) l += 360
        return l
    }

    /** GPS → mètres Est/Nord autour de l'origine. */
    fun enu(latitude: Double, longitude: Double, originLatitude: Double, originLongitude: Double): Vec2 {
        val dN = (latitude - originLatitude) * PI / 180 * earthRadius
        val dE = longitudeDelta(longitude, originLongitude) * PI / 180 * earthRadius *
            cos(originLatitude * PI / 180)
        return Vec2(dE, dN)
    }

    /** Inverse exact de [enu] (longitude ramenée dans ]−180°, 180°]). */
    fun latLon(e: Vec2, originLatitude: Double, originLongitude: Double): Pair<Double, Double> {
        val lat = originLatitude + e.y / earthRadius * 180 / PI
        val lon = originLongitude + e.x / (earthRadius * cos(originLatitude * PI / 180)) * 180 / PI
        return lat to normalizedLongitude(lon)
    }

    /** Rotation d'angle [a] (radians, sens trigonométrique). */
    fun rotate(v: Vec2, a: Double): Vec2 {
        val c = cos(a); val s = sin(a)
        return Vec2(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    // ---- Moyenne GPS d'un point (SPEC §3.2) -------------------------------------

    /** Un fix GPS : position + précision horizontale (m, rayon ~68 %). */
    data class Sample(val latitude: Double, val longitude: Double, val accuracy: Double)

    data class Averaged(
        val latitude: Double,
        val longitude: Double,
        /** Précision retenue (m) — ne diminue PAS avec le nombre de fixes. */
        val accuracy: Double,
        val used: Int,
        val rejected: Int
    )

    /**
     * Moyenne ROBUSTE pondérée par la précision : poids 1/max(a, 1)² ; rejet des
     * fixes à plus de max(3 m, 2·a) de la moyenne (s'il en reste ≥ 3) ; précision
     * = max(moyenne harmonique quadratique, dispersion pondérée).
     */
    fun average(samples: List<Sample>): Averaged? {
        val good = samples.filter { it.accuracy > 0 && it.latitude.isFinite() && it.longitude.isFinite() }
        val ref = good.firstOrNull() ?: return null
        val pts = good.map { enu(it.latitude, it.longitude, ref.latitude, ref.longitude) }
        val w = good.map { val a = maxOf(it.accuracy, 1.0); 1 / (a * a) }

        fun mean(idx: List<Int>): Vec2 {
            var s = Vec2.ZERO; var sw = 0.0
            for (i in idx) { s += w[i] * pts[i]; sw += w[i] }
            return if (sw > 0) s / sw else pts[idx[0]]
        }
        var keep = good.indices.toList()
        var m = mean(keep)
        val inliers = keep.filter { Vec2.distance(pts[it], m) <= maxOf(3.0, 2 * good[it].accuracy) }
        if (inliers.size >= 3 && inliers.size < keep.size) {
            keep = inliers
            m = mean(keep)
        }
        val sw = keep.fold(0.0) { acc, i -> acc + w[i] }
        val harmonic = sqrt(keep.size.toDouble() / sw)
        val scatter = sqrt(keep.fold(0.0) { acc, i -> acc + w[i] * (pts[i] - m).length2() } / sw)
        val (lat, lon) = latLon(m, ref.latitude, ref.longitude)
        return Averaged(lat, lon, maxOf(harmonic, scatter), keep.size, good.size - keep.size)
    }

    // ---- Ajustement (SPEC §4) ------------------------------------------------------

    /** Un point mesuré : l'endroit TOUCHÉ (mm monde) et le GPS MOYENNÉ à cet endroit. */
    data class Point(
        val worldX: Double,
        val worldY: Double,
        val latitude: Double,
        val longitude: Double,
        /** Précision de la moyenne (m). */
        val accuracy: Double,
        val samples: Int = 0
    ) {
        val worldMeters: Vec2 get() = Vec2(worldX, worldY) / 1000.0
        val world: Vec2 get() = Vec2(worldX, worldY)
    }

    /** Point jugé aberrant (indices = positions dans la liste passée à [fit]). */
    data class Outlier(
        /** Point le plus suspect (le seul quand [certain]). */
        val index: Int,
        /** Écart (m) entre l'endroit touché et la position que lui prédisent les AUTRES points. */
        val offset: Double,
        /** Un seul point explique toutes les distances fausses. */
        val certain: Boolean,
        /** Points dont CHACUN expliquerait les distances fausses (croissants). */
        val suspects: List<Int>
    )

    data class Fit(
        val originLatitude: Double,
        val originLongitude: Double,
        /** plan(m) = R(rotation)·ENU(m) + translation — échelle FIXÉE à 1. */
        val rotation: Double,
        val translation: Vec2,
        /** Échelle libre — CONTRÔLE seulement. */
        val freeScale: Double,
        val residuals: List<Double>,
        /** Position GPS ajustée de chaque point, en mm monde. */
        val fittedWorld: List<Vec2>,
        /** Précision PRÈS DES POINTS (m). */
        val precision: Double,
        val precisionFromResiduals: Double,
        /** Précision GPS des points (moyenne quadratique, m) : plancher. */
        val precisionFromGPS: Double,
        val noiseScale2: Double,
        val weightSum: Double,
        val rotationInformation: Double,
        val center: Vec2,
        /** Plus grande distance entre deux points sur le plan (m). */
        val baseLength: Double,
        /** Plus petit angle (degrés) ; au-delà de 3 points, celui du MIEUX formé ; 180 pour 2 points. */
        val minAngleDegrees: Double,
        val outlier: Outlier?,
        val scaleSuspicious: Boolean,
        val pointsTooClose: Boolean,
        val nearlyAligned: Boolean,
        /** Aucune distance ni aucun résidu hors tolérance. */
        val consistent: Boolean,
        /** Plus grand résidu > [residualCaution]. */
        val largeResiduals: Boolean,
        /** Plus grande tolérance de paire (m). */
        val detectionLimit: Double
    ) {
        /** Écart d'échelle en pourcentage (signé). */
        val scaleDeviationPercent: Double get() = (freeScale - 1) * 100
        val pointCount: Int get() = residuals.size

        /** Position GPS → mm monde selon CET ajustement. */
        fun world(latitude: Double, longitude: Double): Vec2 {
            val e = enu(latitude, longitude, originLatitude, originLongitude)
            return (rotate(e, rotation) + translation) * 1000.0
        }

        /** Erreur PRÉVUE (m) à l'endroit [w] du plan (mm monde) — SPEC §4.2. */
        fun predictedError(w: Vec2): Double {
            val d2 = (w / 1000.0 - center).length2()
            val lever = if (rotationInformation > 1e-9) d2 / rotationInformation else 0.0
            val ms = noiseScale2 * (2 / maxOf(weightSum, 1e-9) + lever)
            return sqrt(maxOf(ms, precisionFromGPS * precisionFromGPS))
        }

        /** Plus grande erreur prévue aux 4 coins de l'emprise ; sans emprise, la précision. */
        fun edgePrecision(bounds: Bounds?): Double {
            val b = bounds
            if (b == null || !b.isUsable) return precision
            val corners = listOf(b.min, Vec2(b.max.x, b.min.y), b.max, Vec2(b.min.x, b.max.y))
            return corners.fold(precision) { acc, c -> maxOf(acc, predictedError(c)) }
        }
    }

    private class Rigid(val rot: Double, val t: Vec2, val scale: Double)

    /** Ajustement RIGIDE pondéré (Procrustes 2D) — forme fermée. */
    private fun rigid(e: List<Vec2>, p: List<Vec2>, w: List<Double>): Rigid? {
        val sw = w.fold(0.0) { a, b -> a + b }
        if (e.size != p.size || e.size < 2 || !(sw > 0)) return null
        var ec = Vec2.ZERO; var pc = Vec2.ZERO
        for (i in e.indices) { ec += w[i] * e[i]; pc += w[i] * p[i] }
        ec /= sw; pc /= sw
        var sc = 0.0; var ss = 0.0; var see = 0.0
        for (i in e.indices) {
            val a = e[i] - ec; val b = p[i] - pc
            sc += w[i] * (a.x * b.x + a.y * b.y)
            ss += w[i] * (a.x * b.y - a.y * b.x)
            see += w[i] * a.length2()
        }
        if (!(see > 1e-9) || !(sc * sc + ss * ss > 1e-18)) return null
        val rot = atan2(ss, sc)
        return Rigid(rot, pc - rotate(ec, rot), sqrt(sc * sc + ss * ss) / see)
    }

    /** Plus petit angle (degrés) du triangle abc. */
    fun minAngleDegrees(a: Vec2, b: Vec2, c: Vec2): Double {
        fun angle(o: Vec2, u: Vec2, v: Vec2): Double {
            val x = u - o; val y = v - o
            val lx = x.length(); val ly = y.length()
            if (!(lx > 1e-9) || !(ly > 1e-9)) return 0.0
            return acos(maxOf(-1.0, minOf(1.0, Vec2.dot(x, y) / (lx * ly)))) * 180 / PI
        }
        return minOf(angle(a, b, c), angle(b, c, a), angle(c, a, b))
    }

    /** Qualité de forme d'un triangle : 4√3·aire / (a² + b² + c²), 1 = équilatéral. */
    fun triangleQuality(a: Vec2, b: Vec2, c: Vec2): Double {
        val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2
        val s2 = (b - a).length2() + (c - b).length2() + (a - c).length2()
        return if (s2 > 0) 4 * sqrt(3.0) * area / s2 else 0.0
    }

    /** Premier maximum strict (`Sequence.max(by:)` iOS). */
    private inline fun <T> firstMaxBy(items: List<T>, less: (T, T) -> Boolean): T? {
        if (items.isEmpty()) return null
        var best = items[0]
        for (i in 1 until items.size) if (less(best, items[i])) best = items[i]
        return best
    }

    /**
     * Ajuste les points mesurés (2 ou plus ; 3 pour la calibration, 4 avec le
     * contrôle, 2 pour la position provisoire). `null` si les points sont confondus.
     */
    fun fit(points: List<Point>): Fit? {
        if (points.size < 2) return null
        val o = points[0]
        val n = points.size
        val e = points.map { enu(it.latitude, it.longitude, o.latitude, o.longitude) }
        val p = points.map { it.worldMeters }
        val acc = points.map { maxOf(it.accuracy, 0.5) }
        val w = acc.map { val a = maxOf(it, 1.0); 1 / (a * a) }
        val core = rigid(e, p, w) ?: return null

        val fitted = e.map { rotate(it, core.rot) + core.t }
        val residuals = fitted.indices.map { Vec2.distance(fitted[it], p[it]) }

        // ── Précision (SPEC §4.2) ──
        val ssr = residuals.fold(0.0) { a, r -> a + r * r }
        val precRes = if (n >= 3) sqrt(3 / n.toDouble() * ssr / (2 * n - 3).toDouble()) else 0.0
        val accRMS = sqrt(acc.fold(0.0) { a, x -> a + x * x } / n.toDouble())
        val sw = w.fold(0.0) { a, b -> a + b }
        var pcSum = Vec2.ZERO
        for (i in w.indices) pcSum += w[i] * p[i]
        val pc = pcSum / sw
        var rotInfo = 0.0
        for (i in w.indices) rotInfo += w[i] * (p[i] - pc).length2()
        var wssr = 0.0
        for (i in w.indices) wssr += w[i] * residuals[i] * residuals[i]
        val s0sq = maxOf(0.5, if (n >= 3) wssr / (2 * n - 3).toDouble() else 0.0)
        var msSum = 0.0
        for (q in p) msSum += s0sq * (2 / sw + (if (rotInfo > 1e-9) (q - pc).length2() / rotInfo else 0.0))
        val msAtPoints = msSum / n.toDouble()
        val precision = sqrt(maxOf(msAtPoints, accRMS * accRMS))

        var base = 0.0
        for (j in 0 until n) for (k in j + 1 until n) base = maxOf(base, Vec2.distance(p[j], p[k]))
        var minAngle = 180.0
        if (n >= 3) {
            minAngle = 0.0
            for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) {
                minAngle = maxOf(minAngle, minAngleDegrees(p[a], p[b], p[c]))
            }
        }

        // ── Cohérence (SPEC §4.3) ──
        val pairErr = Array(n) { DoubleArray(n) }
        val failing = ArrayList<Pair<Int, Int>>()
        var scaleErr = 0.0
        var detection = 0.0
        val pairScore = DoubleArray(n)
        for (j in 0 until n) {
            for (k in j + 1 until n) {
                val dp = Vec2.distance(p[j], p[k]); val de = Vec2.distance(e[j], e[k])
                val tol = maxOf(minTolerance, 2.5 * sqrt((acc[j] * acc[j] + acc[k] * acc[k]) / 2))
                val err = abs(dp - de) / tol
                pairErr[j][k] = err
                pairErr[k][j] = err
                detection = maxOf(detection, tol)
                if (err > 1) failing.add(j to k)
                scaleErr = maxOf(scaleErr, abs(dp - core.scale * de) / tol)
                pairScore[j] += err
                pairScore[k] += err
            }
        }
        val resTol = acc.map { maxOf(minTolerance, 2.5 * it) }
        val resFail = points.indices.filter { residuals[it] > resTol[it] }

        // Contrôle d'échelle : σ(échelle) ≈ σ_axe / √(Σ |e'|²).
        var ecSum = Vec2.ZERO
        for (q in e) ecSum += q
        val ec = ecSum / n.toDouble()
        val spread = sqrt(e.fold(0.0) { a, q -> a + (q - ec).length2() })
        val sigmaScale = if (spread > 0) (accRMS / sqrt(2.0)) / spread else Double.POSITIVE_INFINITY
        val scaleTol = maxOf(minScaleTolerance, 2.5 * sigmaScale)
        val scaleOff = abs(core.scale - 1) > scaleTol

        var outlier: Outlier? = null
        var scaleSuspicious = false
        if (n >= 3) {
            if (failing.isEmpty()) {
                scaleSuspicious = scaleOff
            } else {
                val errIf = DoubleArray(n)
                val implicated = BooleanArray(n)
                for ((j, k) in failing) { implicated[j] = true; implicated[k] = true }
                for (i in 0 until n) {
                    var m = 0.0
                    for (j in 0 until n) {
                        if (j == i) continue
                        for (k in j + 1 until n) {
                            if (k == i) continue
                            m = maxOf(m, pairErr[j][k])
                        }
                    }
                    errIf[i] = m
                }
                val plausible = (0 until n).filter { implicated[it] && errIf[it] <= 1 }
                val bestPointErr = plausible.map { errIf[it] }.let { l ->
                    if (l.isEmpty()) Double.POSITIVE_INFINITY else l.reduce { a, b -> if (b < a) b else a }
                }
                if (scaleOff && scaleErr <= 1 && scaleErr < bestPointErr) {
                    scaleSuspicious = true
                } else {
                    val pool = if (plausible.isEmpty()) (0 until n).filter { implicated[it] } else plausible
                    val i: Int = if (n >= 4 && pool.size >= 2) {
                        val loo = pool.map { (leaveOneOutOffset(it, e, p, w) ?: 0.0) / resTol[it] }
                        pool[firstMaxBy(loo.indices.toList()) { a, b -> loo[a] < loo[b] }!!]
                    } else {
                        firstMaxBy(pool) { a, b ->
                            pairScore[a] < pairScore[b] || (pairScore[a] == pairScore[b] && acc[a] < acc[b])
                        }!!
                    }
                    outlier = Outlier(
                        index = i,
                        offset = leaveOneOutOffset(i, e, p, w) ?: residuals[i],
                        certain = plausible.size == 1, suspects = plausible
                    )
                }
            }
        }

        val maxResidual = residuals.let { r -> if (r.isEmpty()) 0.0 else r.reduce { a, b -> if (a < b) b else a } }
        return Fit(
            originLatitude = o.latitude, originLongitude = o.longitude,
            rotation = core.rot, translation = core.t, freeScale = core.scale,
            residuals = residuals, fittedWorld = fitted.map { it * 1000.0 },
            precision = precision,
            precisionFromResiduals = precRes, precisionFromGPS = accRMS,
            noiseScale2 = s0sq, weightSum = sw, rotationInformation = rotInfo, center = pc,
            baseLength = base, minAngleDegrees = minAngle,
            outlier = outlier, scaleSuspicious = scaleSuspicious,
            pointsTooClose = base < minBase,
            nearlyAligned = n >= 3 && minAngle < minAngleWarning,
            consistent = failing.isEmpty() && resFail.isEmpty(),
            largeResiduals = maxResidual > residualCaution,
            detectionLimit = detection
        )
    }

    /** Écart (m) du point [i] à la position que lui prédit l'ajustement des AUTRES points. */
    private fun leaveOneOutOffset(i: Int, e: List<Vec2>, p: List<Vec2>, w: List<Double>): Double? {
        val others = e.indices.filter { it != i }
        val core = rigid(others.map { e[it] }, others.map { p[it] }, others.map { w[it] }) ?: return null
        return Vec2.distance(rotate(e[i], core.rot) + core.t, p[i])
    }

    // ---- Rangement dans le format synchronisé (SPEC §5) -------------------------

    /** Ancre synthétique : monde en FLOAT (comme les ancres stockées), GPS en double. */
    data class SyntheticAnchor(val worldX: Float, val worldY: Float, val latitude: Double, val longitude: Double)

    /**
     * Deux ancres SYNTHÉTIQUES qui reproduisent exactement l'ajustement avec le
     * modèle historique à deux ancres : ancre 0 = barycentre des points touchés
     * (arrondi Float) épinglé à sa position GPS d'après l'ajustement ; ancre 1 =
     * 20 m plus loin sur +X, GPS décalé de R(−θ)·(20 m, 0) autour de l'ancre 0.
     */
    fun syntheticAnchors(fit: Fit, points: List<Point>): List<SyntheticAnchor> {
        var s = Vec2.ZERO
        for (q in points) s += Vec2(q.worldX, q.worldY)
        val c = s / maxOf(points.size, 1).toDouble()
        val w0x = c.x.toFloat(); val w0y = c.y.toFloat()
        val w1x = w0x + (syntheticAnchorSpan * 1000).toFloat(); val w1y = w0y
        val p0 = Vec2(w0x.toDouble(), w0y.toDouble()) / 1000.0
        val e0 = rotate(p0 - fit.translation, -fit.rotation)
        val g0 = latLon(e0, fit.originLatitude, fit.originLongitude)
        val d = Vec2(w1x.toDouble() - w0x.toDouble(), w1y.toDouble() - w0y.toDouble()) / 1000.0
        val g1 = latLon(rotate(d, -fit.rotation), g0.first, g0.second)
        return listOf(
            SyntheticAnchor(w0x, w0y, g0.first, g0.second),
            SyntheticAnchor(w1x, w1y, g1.first, g1.second)
        )
    }

    // ---- Guidage (SPEC §2, §4.4) -------------------------------------------------

    /** Emprise du plan, en mm monde. */
    data class Bounds(val min: Vec2, val max: Vec2) {
        val size: Vec2 get() = max - min
        val center: Vec2 get() = (min + max) / 2.0
        val isUsable: Boolean
            get() { val s = size; return s.x > 1_000 && s.y > 1_000 && s.x.isFinite() && s.y.isFinite() }

        /** Emprise RÉTRÉCIE de min(2 m, 10 % du plus petit côté). */
        val inset: Bounds
            get() {
                val s = size
                val m = minOf(2_000.0, 0.1 * minOf(s.x, s.y))
                return Bounds(min + Vec2(m, m), max - Vec2(m, m))
            }

        fun contains(q: Vec2, tolerance: Double = 1.0): Boolean =
            q.x >= min.x - tolerance && q.x <= max.x + tolerance &&
                q.y >= min.y - tolerance && q.y <= max.y + tolerance
    }

    /** Base visée (m) = 50 % du grand côté, bornée à 25…40 m ; 30 m sans emprise. */
    fun desiredBase(bounds: Bounds?): Double {
        val b = bounds
        if (b == null || !b.isUsable) return baseDefault
        return minOf(baseMax, maxOf(baseMin, 0.5 * maxOf(b.size.x, b.size.y) / 1000))
    }

    /** Nombre de pas pour une distance (m) — au moins 1 (0 si incalculable). */
    fun steps(meters: Double, stepLength: Double): Int {
        if (!meters.isFinite() || !(stepLength > 0)) return 0
        return maxOf(1, roundToInt(meters / stepLength))
    }

    /** « Parcouru » : arrondi de distance / pas, SANS minimum. */
    fun walkedSteps(meters: Double, stepLength: Double): Int = roundToInt(meters / stepLength)

    private fun area(bounds: Bounds?, around: Vec2, base: Double): Bounds {
        if (bounds != null && bounds.isUsable) return bounds.inset
        val h = 1.1 * base * 1000
        return Bounds(around - Vec2(h, h), around + Vec2(h, h))
    }

    /** Candidats : grille 41 × 41 (i en x à l'extérieur, j en y à l'intérieur). */
    private fun grid(area: Bounds): List<Vec2> {
        val n = 40
        val out = ArrayList<Vec2>((n + 1) * (n + 1))
        val size = area.size
        for (i in 0..n) for (j in 0..n) {
            out.add(area.min + Vec2(size.x * (i.toDouble() / n.toDouble()), size.y * (j.toDouble() / n.toDouble())))
        }
        return out
    }

    private fun usableLandmarks(landmarks: List<Vec2>, bounds: Bounds?, area: Bounds): List<Vec2> {
        val zone = if (bounds != null && bounds.isUsable) bounds else area
        val inside = landmarks.filter { it.x.isFinite() && it.y.isFinite() && zone.contains(it) }
        if (inside.size <= maxLandmarks) return inside
        val stride = inside.size.toDouble() / maxLandmarks.toDouble()
        return (0 until maxLandmarks).map { inside[minOf(inside.size - 1, (it.toDouble() * stride).toInt())] }
    }

    private fun pick(
        grid: List<Vec2>, landmarks: List<Vec2>,
        tiers: List<(Vec2) -> Boolean>,
        score: (Vec2) -> Double,
        accept: (Double, Double) -> Boolean
    ): Vec2 {
        for (feasible in tiers + listOf<(Vec2) -> Boolean>({ true })) {
            val g = grid.filter(feasible); val l = landmarks.filter(feasible)
            if (g.isEmpty() && l.isEmpty()) continue
            val bestGrid = firstMaxBy(g.map { it to score(it) }) { a, b -> a.second < b.second }
            val bestLandmark = firstMaxBy(l.map { it to score(it) }) { a, b -> a.second < b.second }
            if (bestLandmark != null) {
                if (bestGrid == null) return bestLandmark.first
                if (accept(bestLandmark.second, bestGrid.second)) return bestLandmark.first
            }
            return bestGrid!!.first
        }
        return grid[0]
    }

    /** Repère du POINT 2 : distance au point 1 la plus proche de la base visée. */
    fun suggestSecondTarget(from: Vec2, bounds: Bounds?, landmarks: List<Vec2> = emptyList()): Vec2 {
        val p1 = from
        val base = desiredBase(bounds)
        val zone = area(bounds, p1, base)
        val center = if (bounds != null && bounds.isUsable) bounds.center else p1
        val score = { c: Vec2 ->
            -abs(Vec2.distance(c, p1) / 1000 - base) - 0.1 * Vec2.distance(c, center) / 1000
        }
        return pick(grid(zone), usableLandmarks(landmarks, bounds, zone), emptyList(), score) { lm, gr ->
            lm >= gr - landmarkSlack
        }
    }

    /** Repère du POINT 3 : triangle le mieux formé avec les points 1 et 2 RÉELLEMENT mesurés. */
    fun suggestThirdTarget(p1: Vec2, p2: Vec2, bounds: Bounds?, landmarks: List<Vec2> = emptyList()): Vec2 {
        val base = desiredBase(bounds)
        val mid = (p1 + p2) / 2.0
        val zone = area(bounds, mid, base)
        val center = if (bounds != null && bounds.isUsable) bounds.center else mid
        fun longest(c: Vec2): Double =
            maxOf(Vec2.distance(p1, p2), Vec2.distance(c, p1), Vec2.distance(c, p2)) / 1000
        fun tier(minB: Double, minA: Double): (Vec2) -> Boolean =
            { c -> longest(c) >= minB && minAngleDegrees(p1, p2, c) >= minA }
        val score = { c: Vec2 ->
            val d1 = Vec2.distance(c, p1) / 1000; val d2 = Vec2.distance(c, p2) / 1000
            var s = triangleQuality(p1, p2, c) * minOf(1.0, minOf(d1, d2) / base)
            if (d2 > 1.5 * base) s *= 1.5 * base / d2
            s - 0.02 * Vec2.distance(c, center) / 1000 / base
        }
        return pick(
            grid(zone), usableLandmarks(landmarks, bounds, zone),
            listOf(tier(1.2 * minBase, minAngleWarning + 5), tier(minBase, minAngleWarning)),
            score
        ) { lm, gr -> lm >= landmarkScoreRatio * gr }
    }

    /**
     * Repère du POINT DE CONTRÔLE (4e) : loin des trois points, jamais aligné avec
     * deux d'entre eux, placé pour RÉDUIRE l'erreur aux bords, sans marche
     * excessive depuis [here] (le dernier point mesuré).
     */
    fun suggestControlTarget(points: List<Vec2>, here: Vec2, bounds: Bounds?, landmarks: List<Vec2> = emptyList()): Vec2 {
        val base = desiredBase(bounds)
        var sum = Vec2.ZERO
        for (q in points) sum += q
        val centroid = sum / maxOf(points.size, 1).toDouble()
        val zone = area(bounds, centroid, base)
        val corners = if (bounds != null && bounds.isUsable)
            listOf(bounds.min, Vec2(bounds.max.x, bounds.min.y), bounds.max, Vec2(bounds.min.x, bounds.max.y))
        else emptyList()
        fun lever(pts: List<Vec2>): Double {
            if (corners.isEmpty()) return 1.0
            var cs = Vec2.ZERO
            for (q in pts) cs += q
            val c = cs / pts.size.toDouble()
            val s = pts.fold(0.0) { a, q -> a + ((q - c) / 1000.0).length2() }
            if (!(s > 1e-9)) return 1.0
            return corners.map { sqrt(2 / pts.size.toDouble() + ((it - c) / 1000.0).length2() / s) }
                .reduce { a, b -> if (a < b) b else a }
        }
        val lever0 = lever(points)
        fun minDist(c: Vec2): Double =
            points.map { Vec2.distance(it, c) / 1000 }.let { l -> if (l.isEmpty()) 0.0 else l.reduce { a, b -> if (b < a) b else a } }
        fun shapes(c: Vec2): Pair<Double, Double> {
            var q = 1.0; var a = 180.0
            for (i in points.indices) for (j in i + 1 until points.size) {
                q = minOf(q, triangleQuality(points[i], points[j], c))
                a = minOf(a, minAngleDegrees(points[i], points[j], c))
            }
            return q to a
        }
        fun tier(minD: Double, minA: Double): (Vec2) -> Boolean =
            { c -> minDist(c) >= minD && shapes(c).second >= minA }
        val score = { c: Vec2 ->
            val walk = Vec2.distance(c, here) / 1000
            var s = sqrt(shapes(c).first) * minOf(1.0, minDist(c) / base)
            if (walk > 1.5 * base) s *= 1.5 * base / walk
            s * lever0 / lever(points + c)
        }
        return pick(
            grid(zone), usableLandmarks(landmarks, bounds, zone),
            listOf(tier(maxOf(minBase, 0.4 * base), minAngleWarning + 5), tier(minBase / 2, minAngleWarning)),
            score
        ) { lm, gr -> lm >= landmarkScoreRatio * gr }
    }
}
