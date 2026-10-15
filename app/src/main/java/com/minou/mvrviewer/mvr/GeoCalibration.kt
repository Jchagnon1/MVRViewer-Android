package com.minou.mvrviewer.mvr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Ancre : un point du plan (monde MVR, mm) attaché à une coordonnée GPS. */
data class GeoAnchor(val worldX: Float, val worldY: Float, val latitude: Double, val longitude: Double)

/**
 * Un point du relevé « 3 points » : l'endroit touché (mm monde, float comme les
 * ancres), le GPS moyenné à cet endroit, sa précision et son résidu (m).
 * Miroir de `GeoSurveyPoint` (iOS).
 */
data class GeoSurveyPoint(
    val worldX: Float,
    val worldY: Float,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val residual: Double
)

/**
 * Relevé d'une calibration guidée (SPEC-calibration-3-points §5) — pour
 * l'AFFICHAGE seulement : la transformation est entièrement portée par les deux
 * ancres synthétiques. Miroir de `GeoSurvey` (iOS).
 */
data class GeoSurvey(
    /** « threePoint » — seule méthode connue ; une méthode inconnue est gardée telle quelle. */
    val method: String,
    val points: List<GeoSurveyPoint>,
    /** Précision estimée PRÈS DES POINTS (m). */
    val precision: Double,
    /** Échelle libre mesurée (contrôle ; 1 = plan à l'échelle). */
    val freeScale: Double,
    /** Date de la calibration (secondes depuis 1970). */
    val epoch: Double,
    /** Erreur prévue la plus forte sur l'emprise du plan (m) ; absente des relevés antérieurs. */
    val edgePrecision: Double? = null
) {
    companion object {
        const val THREE_POINT_METHOD = "threePoint"
    }
}

/**
 * Calibration GPS ↔ monde MVR — portage de GeoCalibration (iOS). 1re ancre =
 * référence ; une 2e donne rotation (nord→plan) + échelle (mm/mètre). Sans 2e
 * ancre : nord = +Y, 1000 mm/m.
 *
 * [survey] : relevé de la calibration GUIDÉE EN TROIS POINTS qui a produit les
 * deux ancres (affichage seulement). Il n'a de sens qu'avec ces DEUX ancres :
 * toute autre méthode qui touche aux ancres l'efface ([addAnchor], [reset]).
 */
class GeoCalibration {
    val anchors = mutableListOf<GeoAnchor>()
    var survey: GeoSurvey? = null
        private set
    val isCalibrated get() = anchors.isNotEmpty()

    /** Ajoute/remplace une ancre : la 1re est la référence, la 2e est remplaçable. Efface le relevé. */
    fun addAnchor(a: GeoAnchor) {
        survey = null
        if (anchors.size >= 2) anchors[1] = a else anchors.add(a)
    }

    fun reset() { anchors.clear(); survey = null }

    /**
     * Remplace TOUT (calibration reçue, relue du disque, ou issue de l'assistant
     * 3 points) : le relevé n'est gardé qu'avec EXACTEMENT 2 ancres (iOS
     * `init(dto:)`).
     */
    fun replace(newAnchors: List<GeoAnchor>, newSurvey: GeoSurvey?) {
        anchors.clear(); anchors.addAll(newAnchors)
        survey = if (newAnchors.size == 2) newSurvey else null
    }

    /**
     * Repères rouges du plan (SPEC-calibration §2.6, iOS `drawUserLocation`, web
     * `markerPoints`) : avec un relevé, les points RÉELLEMENT mesurés (les deux
     * ancres synthétiques ne sont aucun lieu réel) ; sinon les ancres posées.
     * (x, y) en mm monde.
     */
    val markerPoints: List<Pair<Float, Float>>
        get() = survey?.points?.map { it.worldX to it.worldY }
            ?: anchors.map { it.worldX to it.worldY }

    /** Précision affichable (m, arrondie, ≥ 1) d'une calibration 3 points, près des points. */
    val surveyPrecisionMeters: Int?
        get() {
            val s = survey ?: return null
            if (s.method != GeoSurvey.THREE_POINT_METHOD) return null
            return maxOf(1, GeoThreePoint.roundToInt(s.precision))
        }

    /** Précision aux bords (m, arrondie), seulement si elle est moins bonne que près des points. */
    val surveyEdgePrecisionMeters: Int?
        get() {
            val near = surveyPrecisionMeters ?: return null
            val e = survey?.edgePrecision ?: return null
            val edge = GeoThreePoint.roundToInt(e)
            return if (edge > near) edge else null
        }

    private val earthR = GeoThreePoint.earthRadius

    /** (est, nord) en mètres depuis la 1re ancre ; Δλ ramené dans [−180°, 180°] (antiméridien). */
    private fun enuMeters(lat: Double, lon: Double): Pair<Double, Double>? {
        val a = anchors.firstOrNull() ?: return null
        val dN = (lat - a.latitude) * PI / 180 * earthR
        val dE = GeoThreePoint.longitudeDelta(lon, a.longitude) * PI / 180 * earthR * cos(a.latitude * PI / 180)
        return dE to dN
    }

    /** (rotation rad ENU→plan, échelle mm de plan par mètre réel). */
    private fun rotationAndScale(): Pair<Double, Double> {
        if (anchors.size < 2) return 0.0 to 1000.0
        val e2 = enuMeters(anchors[1].latitude, anchors[1].longitude) ?: return 0.0 to 1000.0
        val vx = (anchors[1].worldX - anchors[0].worldX).toDouble()
        val vy = (anchors[1].worldY - anchors[0].worldY).toDouble()
        val lReal = sqrt(e2.first * e2.first + e2.second * e2.second)
        val lPlan = sqrt(vx * vx + vy * vy)
        if (lReal <= 3 || lPlan <= 1) return 0.0 to 1000.0
        return (atan2(vy, vx) - atan2(e2.second, e2.first)) to (lPlan / lReal)
    }

    /** Convertit une distance réelle (mètres) en mm de plan à l'échelle calibrée
     *  (sert à dimensionner le cercle de précision GPS). */
    fun planMillimeters(meters: Double): Double = meters * rotationAndScale().second

    /** Position GPS → point monde MVR (mm), ou null si non calibré. */
    fun worldPosition(lat: Double, lon: Double): Pair<Float, Float>? {
        val a = anchors.firstOrNull() ?: return null
        val e = enuMeters(lat, lon) ?: return null
        val (rot, s) = rotationAndScale()
        val c = cos(rot); val sn = sin(rot)
        val x = (e.first * c - e.second * sn) * s + a.worldX
        val y = (e.first * sn + e.second * c) * s + a.worldY
        return x.toFloat() to y.toFloat()
    }

    /** Point monde MVR (mm) → (lat, lon) — INVERSE de worldPosition (comme iOS
     *  latLon(forWorld:)). Longitude ramenée dans ]−180°, 180°]. */
    fun latLonOf(worldX: Float, worldY: Float): Pair<Double, Double>? {
        val a = anchors.firstOrNull() ?: return null
        val (rot, s) = rotationAndScale()
        if (!(s > 0)) return null
        val c = cos(rot); val sn = sin(rot)
        val dx = (worldX - a.worldX).toDouble() / s
        val dy = (worldY - a.worldY).toDouble() / s
        val east = dx * c + dy * sn
        val north = -dx * sn + dy * c
        val lat = a.latitude + (north / earthR) * 180.0 / PI
        val lon = a.longitude + (east / (earthR * cos(a.latitude * PI / 180.0))) * 180.0 / PI
        return lat to GeoThreePoint.normalizedLongitude(lon)
    }

    /** Cap boussole (degrés depuis le nord, horaire) → direction dans le plan (iOS `worldDirection`). */
    fun worldDirection(headingDegrees: Double): Pair<Float, Float>? {
        if (!isCalibrated) return null
        val h = headingDegrees * PI / 180
        val ex = sin(h); val ey = cos(h)
        val (rot, _) = rotationAndScale()
        val c = cos(rot); val sn = sin(rot)
        return (ex * c - ey * sn).toFloat() to (ex * sn + ey * c).toFloat()
    }

    companion object {
        /**
         * Calibration issue de l'assistant 3 points : deux ancres synthétiques
         * (format historique, lu tel quel par les anciennes versions) + le relevé
         * (iOS `GeoCalibration(threePointFit:points:edgePrecision:date:)`).
         */
        fun fromThreePoint(
            fit: GeoThreePoint.Fit, points: List<GeoThreePoint.Point>,
            edgePrecision: Double?, epochSeconds: Double
        ): Pair<List<GeoAnchor>, GeoSurvey> {
            val anchors = GeoThreePoint.syntheticAnchors(fit, points).map {
                GeoAnchor(it.worldX, it.worldY, it.latitude, it.longitude)
            }
            val surveyPoints = points.indices.map { i ->
                val p = points[i]
                GeoSurveyPoint(
                    p.worldX.toFloat(), p.worldY.toFloat(), p.latitude, p.longitude, p.accuracy,
                    if (i < fit.residuals.size) fit.residuals[i] else 0.0
                )
            }
            return anchors to GeoSurvey(
                GeoSurvey.THREE_POINT_METHOD, surveyPoints, fit.precision, fit.freeScale,
                epochSeconds, edgePrecision
            )
        }
    }
}

/**
 * Ligne d'état « Calibration » — portage de `GeoCalibrationStatus` (iOS,
 * `GeoPanelModel.swift`) : localisation refusée, relevé 3 points (précision près
 * des points et, si moins bonne, aux bords), une ancre, plusieurs, aucune.
 */
sealed interface GeoCalibrationStatus {
    data object Denied : GeoCalibrationStatus
    data class ThreePoints(val precision: Int, val edge: Int?) : GeoCalibrationStatus
    data object OneAnchor : GeoCalibrationStatus
    data class Anchors(val count: Int) : GeoCalibrationStatus
    data object Uncalibrated : GeoCalibrationStatus

    /** Ligne en orange (et non en gris secondaire). */
    val isWarning: Boolean get() = this == Denied

    companion object {
        fun of(calibration: GeoCalibration, locationDenied: Boolean): GeoCalibrationStatus {
            if (locationDenied) return Denied
            calibration.surveyPrecisionMeters?.let { return ThreePoints(it, calibration.surveyEdgePrecisionMeters) }
            return when (val n = calibration.anchors.size) {
                0 -> Uncalibrated
                1 -> OneAnchor
                else -> Anchors(n)
            }
        }
    }
}
