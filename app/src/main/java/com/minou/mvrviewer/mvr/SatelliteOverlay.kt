package com.minou.mvrviewer.mvr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Fond satellite GÉO-RÉFÉRENCÉ (portage de SatelliteOverlay iOS). On récupère UNE
 * image couvrant la zone du plan, et on retient ses 4 COINS en repère monde MVR
 * (mm). Vue plan ET vue 3D dessinent ensuite l'image comme un quad avec LEUR
 * propre projection monde→écran → l'alignement est automatique et identique.
 * Source : ArcGIS World Imagery (satellite, sans clé API ; attribution Esri).
 */
class SatelliteOverlay(
    val bitmap: Bitmap,
    // Coins en monde MVR (mm) : Nord-Ouest, Nord-Est, Sud-Est, Sud-Ouest.
    val nwX: Float, val nwY: Float,
    val neX: Float, val neY: Float,
    val seX: Float, val seY: Float,
    val swX: Float, val swY: Float,
    val key: String
)

object SatelliteFetcher {

    /** Clé de déduplication : ne re-télécharge que si calibration/bornes changent. */
    fun keyFor(calib: GeoCalibration, minX: Float, minY: Float, maxX: Float, maxY: Float): String {
        val a = calib.anchors.joinToString("|") {
            "${(it.worldX).roundToLong()},${(it.worldY).roundToLong()}," +
                "${"%.6f".format(it.latitude)},${"%.6f".format(it.longitude)}"
        }
        fun r(v: Float) = (v / 100f).roundToLong() // arrondi ~10 cm
        return "$a#${r(minX)},${r(minY)},${r(maxX)},${r(maxY)}"
    }

    suspend fun fetch(
        calib: GeoCalibration, minX: Float, minY: Float, maxX: Float, maxY: Float
    ): SatelliteOverlay? = withContext(Dispatchers.IO) {
        if (!calib.isCalibrated) return@withContext null
        // 4 coins monde → lat/lon.
        val c1 = calib.latLonOf(minX, minY) ?: return@withContext null
        val c2 = calib.latLonOf(maxX, minY) ?: return@withContext null
        val c3 = calib.latLonOf(maxX, maxY) ?: return@withContext null
        val c4 = calib.latLonOf(minX, maxY) ?: return@withContext null
        var minLat = minOf(c1.first, c2.first, c3.first, c4.first)
        var maxLat = maxOf(c1.first, c2.first, c3.first, c4.first)
        var minLon = minOf(c1.second, c2.second, c3.second, c4.second)
        var maxLon = maxOf(c1.second, c2.second, c3.second, c4.second)
        // Marge 8 % pour le contexte (comme iOS).
        val padLat = (maxLat - minLat) * 0.08 + 1e-6
        val padLon = (maxLon - minLon) * 0.08 + 1e-6
        minLat -= padLat; maxLat += padLat; minLon -= padLon; maxLon += padLon
        if (maxLat - minLat < 1e-7 || maxLon - minLon < 1e-7) return@withContext null

        var centerLat = (minLat + maxLat) / 2.0
        val cosLat = cos(centerLat * Math.PI / 180.0)
        // Région MINIMALE ~120 m : sinon on demande une résolution que l'imagerie
        // ne fournit pas (ArcGIS répond 500) et on manque de contexte autour du rig.
        val minSpanM = 120.0
        var latSpanM = (maxLat - minLat) * 111_320.0
        var lonSpanM = (maxLon - minLon) * 111_320.0 * cosLat
        if (latSpanM < minSpanM) {
            val g = (minSpanM - latSpanM) / 2.0 / 111_320.0; minLat -= g; maxLat += g; latSpanM = minSpanM
        }
        if (lonSpanM < minSpanM) {
            val g = (minSpanM - lonSpanM) / 2.0 / (111_320.0 * cosLat); minLon -= g; maxLon += g; lonSpanM = minSpanM
        }
        centerLat = (minLat + maxLat) / 2.0
        // Pixels calés sur une résolution SÛRE (~0,3 m/px) : demander plus fin que
        // l'imagerie disponible fait échouer l'export. Plafonné à 1024.
        val majorM = max(latSpanM, lonSpanM)
        val maxDim = (majorM / 0.30).coerceIn(256.0, 1024.0)
        val aspect = lonSpanM / max(latSpanM, 1e-9)
        val w = (if (aspect >= 1) maxDim else maxDim * aspect).coerceAtLeast(256.0).toInt()
        val h = (if (aspect >= 1) maxDim / aspect else maxDim).coerceAtLeast(256.0).toInt()

        val url = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/export" +
            "?bbox=$minLon,$minLat,$maxLon,$maxLat&bboxSR=4326&imageSR=4326&size=$w,$h" +
            "&format=jpg&transparent=false&f=image"
        // Une nouvelle tentative si l'export échoue (le serveur ArcGIS répond
        // parfois 500 transitoire sous charge).
        var bmp: Bitmap? = null
        for (attempt in 0 until 2) {
            bmp = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000; readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MVRViewer-Android")
                }
                try {
                    if (conn.responseCode !in 200..299) null
                    else conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally { conn.disconnect() }
            }.getOrNull()
            if (bmp != null) break
            if (attempt == 0) kotlinx.coroutines.delay(1500)
        }
        if (bmp == null) return@withContext null

        // 4 coins de la bbox géographique → repère monde (mm), pour le placement.
        val nw = calib.worldPosition(maxLat, minLon) ?: return@withContext null
        val ne = calib.worldPosition(maxLat, maxLon) ?: return@withContext null
        val se = calib.worldPosition(minLat, maxLon) ?: return@withContext null
        val sw = calib.worldPosition(minLat, minLon) ?: return@withContext null
        SatelliteOverlay(
            bmp, nw.first, nw.second, ne.first, ne.second,
            se.first, se.second, sw.first, sw.second,
            keyFor(calib, minX, minY, maxX, maxY)
        )
    }

    /** Bornes monde (mm) du contenu : projecteurs (+ plan DXF si présent). */
    fun worldBounds(fixturesXY: List<Pair<Float, Float>>, plan: ReferencePlan?): FloatArray? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((x, y) in fixturesXY) {
            if (!x.isFinite() || !y.isFinite()) continue
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        if (plan != null && !plan.plan.isEmpty) {
            val t = plan.transform
            for (pl in plan.plan.polylines) {
                var i = 0
                while (i + 1 < pl.points.size) {
                    val (wx, wy) = t.worldXY(pl.points[i], pl.points[i + 1]); i += 2
                    minX = min(minX, wx); maxX = max(maxX, wx); minY = min(minY, wy); maxY = max(maxY, wy)
                }
            }
        }
        if (minX > maxX) return null
        // Zone minimale ~40 m si tout est quasi ponctuel.
        if (abs(maxX - minX) < 40_000f) { val c = (minX + maxX) / 2; minX = c - 20_000f; maxX = c + 20_000f }
        if (abs(maxY - minY) < 40_000f) { val c = (minY + maxY) / 2; minY = c - 20_000f; maxY = c + 20_000f }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
