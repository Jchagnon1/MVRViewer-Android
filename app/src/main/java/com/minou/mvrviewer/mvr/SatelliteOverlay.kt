package com.minou.mvrviewer.mvr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
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
 *
 * GÉOMÉTRIE DE L'EXPORT (vérifiée au curl, 2026-10-11) : en EPSG:4326
 * (`bboxSR=imageSR=4326`), le serveur rend des pixels CARRÉS EN DEGRÉS. Si
 * `size` n'a pas le rapport Δlon/Δlat EN DEGRÉS, il ÉLARGIT l'emprise rendue
 * (980×920 px demandés pour 0,0025° de latitude → 0,003755° rendus). L'ancien
 * calcul prenait le rapport en MÈTRES (Δlon·cos(lat)/Δlat) : les coins posés sur
 * l'emprise DEMANDÉE ne correspondaient plus à l'image → image étirée nord-sud
 * d'environ 1/cos(lat) (×1,5 à Paris), en plan comme en 3D. Désormais la taille
 * est au rapport exact en degrés et l'emprise est AJUSTÉE à un nombre entier de
 * pixels : le serveur rend exactement l'emprise demandée, et les 4 coins sont
 * calculés sur cette emprise FINALE (cf. [SatelliteFetcher.requestForArea]).
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

/** Boîte géographique (degrés), nord = maxLat, est = maxLon. */
data class GeoBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    val dLat get() = maxLat - minLat
    val dLon get() = maxLon - minLon
    fun contains(o: GeoBox, eps: Double = 1e-12) =
        o.minLat >= minLat - eps && o.maxLat <= maxLat + eps &&
            o.minLon >= minLon - eps && o.maxLon <= maxLon + eps
}

/**
 * Requête d'export ArcGIS (PURE, sans réseau). [bbox] = emprise FINALE, exactement
 * celle que le serveur rend : `width·degPerPx = Δlon` et `height·degPerPx = Δlat`
 * (pixels carrés en degrés). [url] est l'URL à télécharger.
 */
data class SatelliteRequest(val bbox: GeoBox, val width: Int, val height: Int, val degPerPx: Double) {
    val url: String get() = SatelliteFetcher.exportUrl(bbox, width, height)
}

/**
 * Requête + positions MONDE MVR (mm) des 4 coins de son emprise FINALE (ordre
 * image : nord-ouest, nord-est, sud-est, sud-ouest) — ce que plan et 3D plaquent.
 */
data class SatellitePlan(
    val request: SatelliteRequest,
    val nwX: Float, val nwY: Float,
    val neX: Float, val neY: Float,
    val seX: Float, val seY: Float,
    val swX: Float, val swY: Float
)

object SatelliteFetcher {

    const val EXPORT_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/export"
    /** Marge de contexte autour du contenu, par axe (comme iOS). */
    const val MARGIN = 0.08
    /** Région MINIMALE (m) par axe : en deçà, on demanderait une résolution que
     *  l'imagerie ne fournit pas (ArcGIS répond 500) et le contexte manquerait. */
    const val MIN_SPAN_M = 120.0
    /**
     * Résolution visée (m/px) sur l'axe le PLUS FIN, l'axe est-ouest (en pixels carrés
     * en degrés, le pixel fait degPerPx·M·cos(lat) m d'est en ouest et degPerPx·M m du
     * nord au sud). Plus fin (0,3 m/px nord-sud) échoue par endroits (sondé : Finlande
     * rurale 63,5° N → 500). Même 0,3 m/px est-ouest échoue en zone RURALE (Kenya,
     * Maroc, Brésil, Inde : l'imagerie s'y arrête au niveau 18 du cache, ≈ 0,6 m/px à
     * l'équateur) : d'où les essais de plus en plus grossiers ([ATTEMPTS]).
     */
    const val TARGET_M_PER_PX = 0.30
    /** Plafond de pixels par côté (mémoire Android : bitmap ARGB 4 Mo + copie à
     *  l'opacité cuite pour la 3D + texture GPU). Au-delà, l'image est plus grossière.
     *  Il s'applique au grand côté EN MÈTRES (cf. [requestForArea]). */
    const val MAX_PX = 1024
    /**
     * Nombre d'essais d'export : le n-ième demande des pixels 2^n fois plus gros
     * (×1, ×2, ×4), donc un niveau de cache plus grossier à chaque fois. ArcGIS
     * répond 500 (en ≈ 0,2 s) quand on demande plus fin que l'imagerie disponible
     * à cet endroit : renvoyer la MÊME URL ne sert à rien (mesuré : 500 à chaque
     * fois). Un 500 transitoire donne au pire une image un niveau plus grossière.
     */
    const val ATTEMPTS = 3
    /** Plancher de pixels par côté (zone très allongée) : on ÉLARGIT l'emprise. */
    const val MIN_PX = 16
    /** Mètres par degré de latitude, sur la sphère de [GeoCalibration]. */
    const val M_PER_DEG = 6_371_000.0 * PI / 180.0

    /**
     * Zone à couvrir pour une emprise géographique [needed] : marge 8 % par axe puis
     * au moins [MIN_SPAN_M] de côté (mètres réels). null si coordonnée invalide, trop
     * près d'un pôle ou hors [-180 ; 180] en longitude.
     */
    fun areaFor(needed: GeoBox, margin: Double = MARGIN, minSpanM: Double = MIN_SPAN_M): GeoBox? {
        with(needed) {
            if (!(minLat.isFinite() && maxLat.isFinite() && minLon.isFinite() && maxLon.isFinite())) return null
            if (minLat > maxLat || minLon > maxLon) return null
        }
        val padLat = needed.dLat * margin + 1e-6
        val padLon = needed.dLon * margin + 1e-6
        var minLat = needed.minLat - padLat; var maxLat = needed.maxLat + padLat
        var minLon = needed.minLon - padLon; var maxLon = needed.maxLon + padLon
        val cos0 = cos((minLat + maxLat) / 2.0 * PI / 180.0)
        if (!(cos0 > 0.02) || abs(minLat) > 85.0 || abs(maxLat) > 85.0) return null
        val latSpanM = (maxLat - minLat) * M_PER_DEG
        val lonSpanM = (maxLon - minLon) * M_PER_DEG * cos0
        if (latSpanM < minSpanM) {
            val g = (minSpanM - latSpanM) / 2.0 / M_PER_DEG; minLat -= g; maxLat += g
        }
        if (lonSpanM < minSpanM) {
            val g = (minSpanM - lonSpanM) / 2.0 / (M_PER_DEG * cos0); minLon -= g; maxLon += g
        }
        if (minLon < -180.0 || maxLon > 180.0) return null
        return GeoBox(minLat, minLon, maxLat, maxLon)
    }

    /**
     * Requête d'export couvrant [area] : pixels CARRÉS EN DEGRÉS ([degPerPx] identique
     * sur les deux axes), [TARGET_M_PER_PX] d'est en ouest, au plus [MAX_PX] px par
     * côté, au moins [MIN_PX]. Le nombre de pixels est arrondi au-dessus et l'emprise
     * ÉLARGIE autour du centre à ce nombre entier : rapport w/h = Δlon/Δlat exact, et
     * aucun plancher n'étire l'image (il agrandit l'emprise dans la direction courte).
     *
     * Le plafond porte sur le grand côté EN MÈTRES, ramené à l'est-ouest :
     * `max(cible, grandCôtéM / maxPx)` m/px d'est en ouest. C'est l'échelle que le
     * serveur rendait avec l'ancien calcul (il retenait degPerPx = max(Δlon/w, Δlat/h)),
     * à la troncature d'un pixel près (≤ 0,4 %) ; un plafond en DEGRÉS (max(Δlat, Δlon)/maxPx)
     * était plus fin d'un facteur cos(lat) pour une zone haute, et ArcGIS répondait 500
     * en zone rurale (sondé : Australie −30°, Maroc, Inde, 200 × 480 m). Conséquence :
     * une zone haute plafonnée a ≈ maxPx·cos(lat) px de haut, pas maxPx. Jamais plus
     * fin que l'ancien calcul (au pixel tronqué près) : même niveau de cache ou plus
     * grossier, donc une zone qui marchait ne passe pas en 500.
     *
     * [coarsen] : n-ième essai de secours, pixels 2^n fois plus gros (cf. [ATTEMPTS]).
     */
    fun requestForArea(
        area: GeoBox,
        targetMPerPx: Double = TARGET_M_PER_PX,
        maxPx: Int = MAX_PX,
        minPx: Int = MIN_PX,
        coarsen: Int = 0
    ): SatelliteRequest? {
        if (!(area.dLat > 0 && area.dLon > 0) || coarsen !in 0..16) return null
        val cLat = (area.minLat + area.maxLat) / 2.0
        val cLon = (area.minLon + area.maxLon) / 2.0
        val cos0 = cos(cLat * PI / 180.0)
        if (!(cos0 > 0.02)) return null
        val majorM = max(area.dLat * M_PER_DEG, area.dLon * M_PER_DEG * cos0)
        val ewMPerPx = max(targetMPerPx, majorM / maxPx)
        val degPerPx = ewMPerPx / (M_PER_DEG * cos0) * (1 shl coarsen)
        // −1e-9 : un quotient « entier » à l'arrondi flottant près ne gagne pas 1 px.
        val width = ceil(area.dLon / degPerPx - 1e-9).toInt().coerceIn(minPx, maxPx)
        val height = ceil(area.dLat / degPerPx - 1e-9).toInt().coerceIn(minPx, maxPx)
        val hw = width * degPerPx / 2.0
        val hh = height * degPerPx / 2.0
        val bbox = GeoBox(cLat - hh, cLon - hw, cLat + hh, cLon + hw)
        if (bbox.minLon < -180.0 || bbox.maxLon > 180.0) return null
        return SatelliteRequest(bbox, width, height, degPerPx)
    }

    /**
     * Calcul COMPLET (pur, sans réseau) pour les bornes MONDE (mm) du contenu : boîte
     * géographique des 4 coins, zone ([areaFor]), requête ([requestForArea]) et coins
     * MONDE de l'emprise FINALE. null si non calibré ou zone invalide. [coarsen] :
     * essai de secours (pixels 2^n fois plus gros) — l'emprise, donc les coins, en
     * dépend : chaque essai a les SIENS.
     */
    fun planFor(
        calib: GeoCalibration, minX: Float, minY: Float, maxX: Float, maxY: Float, coarsen: Int = 0
    ): SatellitePlan? {
        if (!calib.isCalibrated) return null
        val c1 = calib.latLonOf(minX, minY) ?: return null
        val c2 = calib.latLonOf(maxX, minY) ?: return null
        val c3 = calib.latLonOf(maxX, maxY) ?: return null
        val c4 = calib.latLonOf(minX, maxY) ?: return null
        val needed = GeoBox(
            minOf(c1.first, c2.first, c3.first, c4.first), minOf(c1.second, c2.second, c3.second, c4.second),
            maxOf(c1.first, c2.first, c3.first, c4.first), maxOf(c1.second, c2.second, c3.second, c4.second)
        )
        val area = areaFor(needed) ?: return null
        val req = requestForArea(area, coarsen = coarsen) ?: return null
        val b = req.bbox
        val nw = calib.worldPosition(b.maxLat, b.minLon) ?: return null
        val ne = calib.worldPosition(b.maxLat, b.maxLon) ?: return null
        val se = calib.worldPosition(b.minLat, b.maxLon) ?: return null
        val sw = calib.worldPosition(b.minLat, b.minLon) ?: return null
        return SatellitePlan(req, nw.first, nw.second, ne.first, ne.second, se.first, se.second, sw.first, sw.second)
    }

    /** Les [ATTEMPTS] essais, du plus fin au plus grossier (URL toutes distinctes). */
    fun plansFor(calib: GeoCalibration, minX: Float, minY: Float, maxX: Float, maxY: Float): List<SatellitePlan> =
        (0 until ATTEMPTS).mapNotNull { planFor(calib, minX, minY, maxX, maxY, coarsen = it) }
            .distinctBy { it.request.url }

    /**
     * Premier essai qui aboutit, dans l'ordre de [plans] (du plus fin au plus
     * grossier) : [download] renvoie null en cas d'échec (HTTP ≠ 2xx, image non
     * décodable, réseau). Le plan renvoyé est celui de l'essai RÉUSSI : ses coins
     * correspondent à l'emprise que le serveur a réellement rendue.
     */
    inline fun <T : Any> firstSuccess(plans: List<SatellitePlan>, download: (SatellitePlan) -> T?): Pair<SatellitePlan, T>? {
        for (plan in plans) {
            val result = download(plan) ?: continue
            return plan to result
        }
        return null
    }

    /** Télécharge et décode une image ; null si HTTP ≠ 2xx, image illisible ou erreur réseau. */
    private fun download(url: String): Bitmap? = runCatching {
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

    /** URL d'export (JPEG, 4326 → 4326, image brute). Nombres en notation décimale
     *  pleine, indépendante de la langue (jamais « 1.2E-4 » ni de virgule). */
    fun exportUrl(bbox: GeoBox, width: Int, height: Int): String {
        fun n(v: Double) = BigDecimal.valueOf(v).toPlainString()
        return "$EXPORT_URL?bbox=${n(bbox.minLon)},${n(bbox.minLat)},${n(bbox.maxLon)},${n(bbox.maxLat)}" +
            "&bboxSR=4326&imageSR=4326&size=$width,$height&format=jpg&transparent=false&f=image"
    }

    /** Clé de déduplication : ne re-télécharge que si calibration/bornes changent.
     *  Les requêtes sont une fonction déterministe de ces mêmes entrées ([plansFor]). */
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
        // Emprise, taille et coins : calcul PUR (testé en JVM), une seule source.
        // Essais de plus en plus grossiers : en zone rurale, ArcGIS répond 500 quand
        // on demande plus fin que l'imagerie disponible ; répéter la même URL échoue
        // à l'identique, un niveau plus grossier aboutit (cf. [ATTEMPTS]).
        val plans = plansFor(calib, minX, minY, maxX, maxY)
        val (plan, bmp) = firstSuccess(plans) { ensureActive(); download(it.request.url) }
            ?: return@withContext null

        // Coins = emprise FINALE de la requête RÉUSSIE (celle que le serveur a rendue).
        SatelliteOverlay(
            bmp, plan.nwX, plan.nwY, plan.neX, plan.neY,
            plan.seX, plan.seY, plan.swX, plan.swY,
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
