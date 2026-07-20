package com.minou.mvrviewer.mvr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Ancre : un point du plan (monde MVR, mm) attaché à une coordonnée GPS. */
data class GeoAnchor(val worldX: Float, val worldY: Float, val latitude: Double, val longitude: Double)

/**
 * Calibration GPS ↔ monde MVR — portage de GeoCalibration (iOS). 1re ancre =
 * référence ; une 2e donne rotation (nord→plan) + échelle (mm/mètre). Sans 2e
 * ancre : nord = +Y, 1000 mm/m.
 */
class GeoCalibration {
    val anchors = mutableListOf<GeoAnchor>()
    val isCalibrated get() = anchors.isNotEmpty()

    /** Ajoute/remplace une ancre : la 1re est la référence, la 2e est remplaçable. */
    fun addAnchor(a: GeoAnchor) {
        if (anchors.size >= 2) anchors[1] = a else anchors.add(a)
    }

    fun reset() { anchors.clear() }

    private val earthR = 6_371_000.0

    /** (est, nord) en mètres depuis la 1re ancre. */
    private fun enuMeters(lat: Double, lon: Double): Pair<Double, Double>? {
        val a = anchors.firstOrNull() ?: return null
        val dN = (lat - a.latitude) * PI / 180 * earthR
        val dE = (lon - a.longitude) * PI / 180 * earthR * cos(a.latitude * PI / 180)
        return dE to dN
    }

    /** (rotation rad ENU→plan, échelle mm de plan par mètre réel). */
    private fun rotationAndScale(): Pair<Double, Double> {
        if (anchors.size < 2) return 0.0 to 1000.0
        val e2 = enuMeters(anchors[1].latitude, anchors[1].longitude) ?: return 0.0 to 1000.0
        val vx = (anchors[1].worldX - anchors[0].worldX).toDouble()
        val vy = (anchors[1].worldY - anchors[0].worldY).toDouble()
        val lReal = hypot(e2.first, e2.second)
        val lPlan = hypot(vx, vy)
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
     *  latLon(forWorld:)). Sert à géo-référencer le fond satellite. */
    fun latLonOf(worldX: Float, worldY: Float): Pair<Double, Double>? {
        val a = anchors.firstOrNull() ?: return null
        val (rot, s) = rotationAndScale()
        val c = cos(rot); val sn = sin(rot)
        val dx = (worldX - a.worldX).toDouble() / s
        val dy = (worldY - a.worldY).toDouble() / s
        val east = dx * c + dy * sn
        val north = -dx * sn + dy * c
        val lat = a.latitude + (north / earthR) * 180.0 / PI
        val lon = a.longitude + (east / (earthR * cos(a.latitude * PI / 180.0))) * 180.0 / PI
        return lat to lon
    }
}
