package com.minou.mvrviewer.mvr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Fond satellite GÉO-RÉFÉRENCÉ (portage de SatelliteOverlay iOS). On récupère UNE
 * image couvrant la zone du plan, et on retient ses 4 COINS en repère monde MVR
 * (mm). Vue plan ET vue 3D dessinent ensuite l'image comme un quad avec LEUR
 * propre projection monde→écran → l'alignement est automatique et identique.
 *
 * Source : IGN Géoplateforme, BD ORTHO® (photographies aériennes, Licence Ouverte
 * 2.0, sans clé), en France seulement — cf. [ImagerySources] et
 * SPEC-satellite-ign.md. Hors des emprises IGN : PAS de fond (message), aucune
 * requête. La mention « Source : IGN – BD ORTHO®… » est OBLIGATOIRE à l'écran
 * (plan et 3D) et dans le PDF, d'où [year].
 *
 * GÉOMÉTRIE (vérifiée sur le vrai service, 2026-10-13) : en WMS 1.3.0 / EPSG:4326,
 * le serveur rend EXACTEMENT le BBOX demandé, étiré à la taille demandée (il ne
 * l'élargit jamais). L'image est donc linéaire en latitude/longitude et ses 4 coins
 * sont ceux du BBOX : on choisit la taille pour des pixels carrés AU SOL.
 */
class SatelliteOverlay(
    val bitmap: Bitmap,
    // Coins en monde MVR (mm) : Nord-Ouest, Nord-Est, Sud-Est, Sud-Ouest.
    val nwX: Float, val nwY: Float,
    val neX: Float, val neY: Float,
    val seX: Float, val seY: Float,
    val swX: Float, val swY: Float,
    val key: String,
    /** Année de prise de vue (GetFeatureInfo au centre) ; null = inconnue. */
    val year: Int? = null,
    /** URL GetMap qui a produit l'image. */
    val url: String = ""
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
 * Requête GetMap (PURE, sans réseau). [bbox] = emprise ARRONDIE vers l'extérieur au
 * 1e−7° : ce sont EXACTEMENT les nombres de l'URL, donc l'emprise que le serveur
 * rend. [mPerPx] = résolution au sol (m/px), la même sur les deux axes.
 */
data class SatelliteRequest(val bbox: GeoBox, val width: Int, val height: Int, val mPerPx: Double) {
    val url: String get() = IgnImagery.SOURCE.getMapUrl(bbox, width, height)
    /** GetFeatureInfo au centre de la même image (date de prise de vue). */
    val infoUrl: String get() = IgnImagery.SOURCE.featureInfoUrl(bbox, width, height)
}

/**
 * Requête + positions MONDE MVR (mm) des 4 coins de son emprise (ordre image :
 * nord-ouest, nord-est, sud-est, sud-ouest) — ce que plan et 3D plaquent.
 */
data class SatellitePlan(
    val request: SatelliteRequest,
    val nwX: Float, val nwY: Float,
    val neX: Float, val neY: Float,
    val seX: Float, val seY: Float,
    val swX: Float, val swY: Float
)

/** Cause d'un échec de téléchargement (messages : ui/SatelliteUi.kt). */
enum class SatelliteErrorKind { TIMEOUT, NETWORK, HTTP, DECODE }

/**
 * Résultat TYPÉ d'un chargement (au lieu de null) : l'interface en tire le message.
 * - [Ok] : image IGN prête ;
 * - [OutOfCoverage] : 1re ancre hors des emprises IGN, AUCUNE requête ;
 * - [NoImagery] : l'IGN a renvoyé une image blanche (pas d'imagerie ici) ;
 * - [Error] : délai, réseau, HTTP (avec le code) ou image illisible.
 */
sealed interface SatelliteResult<out T> {
    data class Ok<T>(val overlay: T) : SatelliteResult<T>
    data object OutOfCoverage : SatelliteResult<Nothing>
    data object NoImagery : SatelliteResult<Nothing>
    data class Error(val kind: SatelliteErrorKind, val status: Int? = null) : SatelliteResult<Nothing>
}

/** Réponse HTTP minimale (corps lu seulement pour un 200). */
class SatelliteHttpReply(val status: Int, val contentType: String?, val body: ByteArray)

/** Transport HTTP du service (doublure en test JVM). */
fun interface SatelliteHttp {
    /** GET [url]. Lève [SocketTimeoutException] (délai) ou [IOException] (réseau). */
    fun get(url: String, timeoutMs: Int): SatelliteHttpReply
}

/** Image décodée + la requête qui l'a produite + l'année de prise de vue. */
class SatelliteLoaded<I>(val image: I, val plan: SatellitePlan, val year: Int?)

/**
 * Déroulé d'une image (SPEC-satellite-ign.md §5.1), indépendant d'Android (testé
 * en JVM avec une doublure de [http], de [decode] et de [isBlank]) :
 *  1. 1re ancre hors des emprises IGN → [SatelliteResult.OutOfCoverage], AUCUNE requête ;
 *  2. zone + requête (pur) ;
 *  3. mémoires : URL déjà reçue BLANCHE → [SatelliteResult.NoImagery] sans réseau ;
 *     échec sur la même URL il y a moins de 30 s → le même échec, sans réseau —
 *     sauf [force] (geste explicite : l'utilisateur active le réglage) ;
 *  4. GetMap (30 s) : accepté seulement si 200 ET `image/…` ; sinon HTTP(code).
 *     Plus AUCUN essai à une autre taille : l'IGN ne répond pas 500 pour une
 *     résolution trop fine (contrairement à l'ancien service) ;
 *  5. image blanche → [SatelliteResult.NoImagery], URL mémorisée ;
 *  6. GetFeatureInfo (10 s) → année ou null : un échec ne bloque JAMAIS l'image.
 * Une instance par show (ses mémoires vivent avec lui).
 */
class SatelliteService<I : Any>(
    private val http: SatelliteHttp,
    private val decode: (ByteArray) -> I?,
    private val isBlank: (I) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val currentYear: () -> Int = { Calendar.getInstance().get(Calendar.YEAR) }
) {
    private val blankUrls = HashSet<String>()
    private var lastErrorUrl: String? = null
    private var lastErrorAt = 0L
    private var lastError: SatelliteResult.Error? = null

    /**
     * Charge l'image du contenu [minX]…[maxY] (monde, mm). [checkActive] est appelé
     * entre les deux requêtes (annulation de la coroutine appelante).
     */
    fun load(
        calib: GeoCalibration, minX: Float, minY: Float, maxX: Float, maxY: Float,
        force: Boolean, checkActive: () -> Unit = {}
    ): SatelliteResult<SatelliteLoaded<I>> {
        // Pas d'ancre = pas de point à situer : rien à demander (l'appelant ne
        // propose de toute façon le réglage qu'une fois calibré).
        val a = calib.anchors.firstOrNull() ?: return SatelliteResult.OutOfCoverage
        if (!ignCovers(a.latitude, a.longitude)) return SatelliteResult.OutOfCoverage
        val plan = SatelliteFetcher.planFor(calib, minX, minY, maxX, maxY) ?: return SatelliteResult.NoImagery
        val req = plan.request
        val url = req.url
        synchronized(this) {
            if (!force) {
                if (url in blankUrls) return SatelliteResult.NoImagery
                val e = lastError
                if (e != null && lastErrorUrl == url && now() - lastErrorAt < SatelliteFetcher.ERROR_RETRY_MS) return e
            }
        }
        val reply = try {
            http.get(url, SatelliteFetcher.GETMAP_TIMEOUT_MS)
        } catch (_: SocketTimeoutException) {
            return fail(url, SatelliteResult.Error(SatelliteErrorKind.TIMEOUT))
        } catch (_: IOException) {
            return fail(url, SatelliteResult.Error(SatelliteErrorKind.NETWORK))
        }
        val type = reply.contentType?.trim()?.lowercase()
        if (reply.status != 200 || type == null || !type.startsWith("image/")) {
            return fail(url, SatelliteResult.Error(SatelliteErrorKind.HTTP, reply.status))
        }
        val img = runCatching { decode(reply.body) }.getOrNull()
            ?: return fail(url, SatelliteResult.Error(SatelliteErrorKind.DECODE))
        if (isBlank(img)) {
            synchronized(this) { blankUrls += url; clearErrorFor(url) }
            return SatelliteResult.NoImagery
        }
        synchronized(this) { blankUrls -= url; clearErrorFor(url) }
        checkActive()
        val year = try {
            val r = http.get(req.infoUrl, SatelliteFetcher.INFO_TIMEOUT_MS)
            if (r.status == 200) IgnImagery.parseCaptureYear(String(r.body, Charsets.UTF_8), currentYear()) else null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        return SatelliteResult.Ok(SatelliteLoaded(img, plan, year))
    }

    private fun fail(url: String, e: SatelliteResult.Error): SatelliteResult.Error {
        synchronized(this) { lastErrorUrl = url; lastErrorAt = now(); lastError = e }
        return e
    }

    private fun clearErrorFor(url: String) {
        if (lastErrorUrl == url) { lastErrorUrl = null; lastError = null }
    }
}

object SatelliteFetcher {

    /** Marge de contexte autour du contenu, par axe (comme iOS). */
    const val MARGIN = 0.08
    /** Région MINIMALE (m) par axe : en deçà, le contexte manquerait. */
    const val MIN_SPAN_M = 120.0
    /** Résolution visée (m/px AU SOL, les deux axes) : BD ORTHO est à 20 cm ; plus
     *  fin, l'IGN sert une image seulement agrandie (floue). */
    const val TARGET_M_PER_PX = 0.20
    /** Plafond de pixels par côté (mémoire Android : bitmap ARGB 4 Mo + copie à
     *  l'opacité cuite pour la 3D + texture GPU). Au-delà, l'image est plus grossière. */
    const val MAX_PX = 1024
    /** Plancher de pixels par côté (zone très allongée). */
    const val MIN_PX = 16
    /** Mètres par degré de latitude, sur la sphère de [GeoCalibration]. */
    const val M_PER_DEG = 6_371_000.0 * PI / 180.0
    /** Délais (ms) : image, puis date de prise de vue. */
    const val GETMAP_TIMEOUT_MS = 30_000
    const val INFO_TIMEOUT_MS = 10_000
    /** Pas de nouvelle demande de la même image avant ce délai après un échec. */
    const val ERROR_RETRY_MS = 30_000L
    /** Image VIDE si au moins cette part de pixels est blanche (§3.3). */
    const val BLANK_MIN_FRACTION = 0.995
    /** Côté de la grille d'échantillonnage du test « image vide ». */
    const val BLANK_GRID = 64
    /** Corps de réponse lu au plus (un JPEG de 1024² pèse moins de 1 Mo). */
    private const val MAX_BODY_BYTES = 16 * 1024 * 1024

    /** En-tête User-Agent identifiant l'app (§5.1). */
    fun userAgent(versionName: String?): String = "MVRViewer/${versionName?.ifBlank { null } ?: "?"} (Android)"

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

    /** Arrondi VERS LE BAS au 1e−7° ; le +1e−6 garde intactes les valeurs déjà rondes. */
    private fun floor7(v: Double) = floor(v * 1e7 + 1e-6) / 1e7
    /** Arrondi VERS LE HAUT au 1e−7°. */
    private fun ceil7(v: Double) = ceil(v * 1e7 - 1e-6) / 1e7

    /**
     * Requête GetMap couvrant [area] (SPEC-satellite-ign.md §2.2, calcul PUR) :
     *  1. BBOX = [area] arrondie VERS L'EXTÉRIEUR au 1e−7° (≈ 1 cm) : URL
     *     déterministe, zone utile couverte ;
     *  2. étendues au sol nsM (nord-sud) et ewM (est-ouest, × cos(lat0)) ;
     *  3. mPerPx = max([targetMPerPx], grand côté / [maxPx]) : pixels CARRÉS AU SOL ;
     *  4. largeur/hauteur = ⌈étendue / mPerPx⌉ bornées à [[minPx] ; [maxPx]].
     * Le serveur étire l'image au BBOX exact : aucune correction des coins.
     */
    fun requestForArea(
        area: GeoBox,
        targetMPerPx: Double = TARGET_M_PER_PX,
        maxPx: Int = MAX_PX,
        minPx: Int = MIN_PX
    ): SatelliteRequest? {
        with(area) {
            if (!(minLat.isFinite() && maxLat.isFinite() && minLon.isFinite() && maxLon.isFinite())) return null
        }
        if (!(area.dLat > 0 && area.dLon > 0)) return null
        val bbox = GeoBox(floor7(area.minLat), floor7(area.minLon), ceil7(area.maxLat), ceil7(area.maxLon))
        if (abs(bbox.minLat) > 85.0 || abs(bbox.maxLat) > 85.0) return null
        if (bbox.minLon < -180.0 || bbox.maxLon > 180.0) return null
        val lat0 = (bbox.minLat + bbox.maxLat) / 2.0
        val cos0 = cos(lat0 * PI / 180.0)
        if (!(cos0 > 0.02)) return null
        val nsM = bbox.dLat * M_PER_DEG
        val ewM = bbox.dLon * M_PER_DEG * cos0
        val mPerPx = max(targetMPerPx, max(nsM, ewM) / maxPx)
        // −1e-9 : un quotient « entier » à l'arrondi flottant près ne gagne pas 1 px.
        val width = ceil(ewM / mPerPx - 1e-9).toInt().coerceIn(minPx, maxPx)
        val height = ceil(nsM / mPerPx - 1e-9).toInt().coerceIn(minPx, maxPx)
        return SatelliteRequest(bbox, width, height, mPerPx)
    }

    /**
     * Calcul COMPLET (pur, sans réseau) pour les bornes MONDE (mm) du contenu : boîte
     * géographique des 4 coins, zone ([areaFor]), requête ([requestForArea]) et coins
     * MONDE du BBOX ARRONDI (les nombres exacts de l'URL). null si non calibré ou
     * zone invalide.
     */
    fun planFor(
        calib: GeoCalibration, minX: Float, minY: Float, maxX: Float, maxY: Float,
        maxPx: Int = MAX_PX
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
        val req = requestForArea(area, maxPx = maxPx) ?: return null
        val b = req.bbox
        val nw = calib.worldPosition(b.maxLat, b.minLon) ?: return null
        val ne = calib.worldPosition(b.maxLat, b.maxLon) ?: return null
        val se = calib.worldPosition(b.minLat, b.maxLon) ?: return null
        val sw = calib.worldPosition(b.minLat, b.minLon) ?: return null
        return SatellitePlan(req, nw.first, nw.second, ne.first, ne.second, se.first, se.second, sw.first, sw.second)
    }

    /**
     * Image VIDE (§3.3) sur des pixels ARGB [w]×[h] : échantillonnage au plus proche
     * voisin sur une grille [grid]×[grid] ; un pixel est blanc si min(R, G, B) ≥ 250 ;
     * vide si au moins [BLANK_MIN_FRACTION] des échantillons sont blancs (au plus 20
     * non blancs sur 4096). Hors couverture, l'IGN renvoie un JPEG 100 % blanc.
     */
    fun isBlankPixels(argb: IntArray, w: Int, h: Int, grid: Int = BLANK_GRID): Boolean {
        if (w <= 0 || h <= 0 || grid <= 0 || argb.size < w * h) return false
        var white = 0
        for (j in 0 until grid) {
            val y = min(h - 1, ((j + 0.5) * h / grid).toInt())
            for (i in 0 until grid) {
                val x = min(w - 1, ((i + 0.5) * w / grid).toInt())
                val c = argb[y * w + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                if (min(r, min(g, b)) >= 250) white++
            }
        }
        return white >= BLANK_MIN_FRACTION * grid * grid - 1e-9
    }

    /** [isBlankPixels] sur une image Android, réduite à 64 × 64 (plus proche voisin). */
    fun isBlankBitmap(bmp: Bitmap): Boolean {
        val g = BLANK_GRID
        if (bmp.width <= 0 || bmp.height <= 0) return false
        val small = if (bmp.width == g && bmp.height == g) bmp else Bitmap.createScaledBitmap(bmp, g, g, false)
        val px = IntArray(g * g)
        small.getPixels(px, 0, g, 0, 0, g, g)
        if (small !== bmp) small.recycle()
        return isBlankPixels(px, g, g, g)
    }

    /** Transport HTTP réel (HttpURLConnection) avec l'en-tête User-Agent de l'app. */
    class UrlConnectionHttp(private val userAgent: String) : SatelliteHttp {
        override fun get(url: String, timeoutMs: Int): SatelliteHttpReply {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
            }
            try {
                val code = conn.responseCode
                val type = conn.contentType
                val body = if (code == 200) conn.inputStream.use { readCapped(it) } else ByteArray(0)
                return SatelliteHttpReply(code, type, body)
            } finally {
                conn.disconnect()
            }
        }

        private fun readCapped(input: InputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY_BYTES) throw IOException("réponse trop grosse")
            }
            return out.toByteArray()
        }
    }

    /** Service Android : HTTP réel, décodage BitmapFactory, test « vide » sur Bitmap. */
    fun newService(userAgent: String): SatelliteService<Bitmap> = SatelliteService(
        http = UrlConnectionHttp(userAgent),
        decode = { b -> BitmapFactory.decodeByteArray(b, 0, b.size) },
        isBlank = { isBlankBitmap(it) }
    )

    /** Clé de déduplication : ne re-télécharge que si calibration/bornes changent.
     *  La requête est une fonction déterministe de ces mêmes entrées ([planFor]). */
    fun keyFor(calib: GeoCalibration, minX: Float, minY: Float, maxX: Float, maxY: Float): String {
        val a = calib.anchors.joinToString("|") {
            "${(it.worldX).roundToLong()},${(it.worldY).roundToLong()}," +
                "${"%.6f".format(it.latitude)},${"%.6f".format(it.longitude)}"
        }
        fun r(v: Float) = (v / 100f).roundToLong() // arrondi ~10 cm
        return "$a#${r(minX)},${r(minY)},${r(maxX)},${r(maxY)}"
    }

    /**
     * Charge le fond pour le contenu [minX]…[maxY] (monde, mm) — résultat TYPÉ.
     * [force] : l'utilisateur vient d'activer le réglage (ignore les mémoires
     * « vide » et « échec récent »). Hors couverture : aucune requête.
     */
    suspend fun fetch(
        service: SatelliteService<Bitmap>,
        calib: GeoCalibration, minX: Float, minY: Float, maxX: Float, maxY: Float,
        force: Boolean = false
    ): SatelliteResult<SatelliteOverlay> = withContext(Dispatchers.IO) {
        val key = keyFor(calib, minX, minY, maxX, maxY)
        when (val r = service.load(calib, minX, minY, maxX, maxY, force) { ensureActive() }) {
            is SatelliteResult.Ok -> {
                val p = r.overlay.plan
                // Coins = BBOX ARRONDI de la requête : exactement ce que l'IGN a rendu.
                SatelliteResult.Ok(
                    SatelliteOverlay(
                        r.overlay.image, p.nwX, p.nwY, p.neX, p.neY,
                        p.seX, p.seY, p.swX, p.swY, key, r.overlay.year, p.request.url
                    )
                )
            }
            is SatelliteResult.Error -> r
            SatelliteResult.OutOfCoverage -> SatelliteResult.OutOfCoverage
            SatelliteResult.NoImagery -> SatelliteResult.NoImagery
        }
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
