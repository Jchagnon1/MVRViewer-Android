package com.minou.mvrviewer.mvr

import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SOURCE d'imagerie aérienne pour le fond satellite (SPEC-satellite-ign.md §8).
 *
 * Registre ORDONNÉ : on prend la PREMIÈRE source dont la couverture contient la
 * 1re ancre de la calibration ; aucune → « hors couverture » (pas de fond, aucune
 * requête). Une seule source aujourd'hui : l'IGN (France). L'ancienne imagerie
 * mondiale sans clé a été RETIRÉE : ses conditions interdisent une app vendue.
 *
 * Chaque source décrit : sa couverture (emprises officielles), son URL WMS 1.3.0
 * (GetMap), la règle « pas de donnée » (ici : image blanche uniforme) et la page du jeu
 * de données (mention). La DATE de la mention vient du WFS du graphe de mosaïquage et
 * de la fiche CSW de l'IGN ([IgnImagery], SPEC-satellite-ign.md §2.4).
 * Les TEXTES de la mention sont des ressources de l'interface (ui/SatelliteUi.kt).
 */
class ImagerySource(
    val id: String,
    /** Emprises officielles (degrés). [covers] les teste ÉLARGIES de [COVERAGE_PAD_DEG]. */
    val coverage: List<GeoBox>,
    val wmsUrl: String,
    val layer: String,
    /** Page du jeu de données : cible du lien de la mention. */
    val datasetUrl: String,
    /** Plafond de pixels par côté accepté par le serveur (GetCapabilities). */
    val serverMaxPx: Int
) {
    /** Pré-filtre SANS réseau : le point est-il dans une emprise élargie de 0,05° ? */
    fun covers(lat: Double, lon: Double): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) return false
        val p = COVERAGE_PAD_DEG
        return coverage.any { b ->
            lat >= b.minLat - p && lat <= b.maxLat + p && lon >= b.minLon - p && lon <= b.maxLon + p
        }
    }

    /**
     * URL GetMap (§2.3) : paramètres dans CET ordre, nombres à 7 décimales au point
     * (Locale.ROOT, jamais la langue de l'appareil ni « 1.2E-4 »). En WMS 1.3.0 avec
     * EPSG:4326, le BBOX est **lat,lon** (minLat,minLon,maxLat,maxLon) : inverser ne
     * lève AUCUNE erreur, le serveur renvoie une image blanche (§1.2).
     */
    fun getMapUrl(bbox: GeoBox, width: Int, height: Int): String =
        "$wmsUrl?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$layer&STYLES=&CRS=EPSG:4326" +
            "&BBOX=${bboxParam(bbox)}&WIDTH=$width&HEIGHT=$height&FORMAT=image/jpeg"

    companion object {
        /** Élargissement des emprises pour le pré-filtre (≈ 5 km) : généreux exprès. */
        const val COVERAGE_PAD_DEG = 0.05

        /** Nombre décimal à 7 chiffres, point décimal, indépendant de la langue. */
        fun num(v: Double): String = String.format(Locale.ROOT, "%.7f", v)

        /** BBOX WMS 1.3.0 / EPSG:4326 : minLat,minLon,maxLat,maxLon (lat D'ABORD). */
        fun bboxParam(b: GeoBox): String =
            "${num(b.minLat)},${num(b.minLon)},${num(b.maxLat)},${num(b.maxLon)}"
    }
}

/** IGN Géoplateforme, BD ORTHO® (Licence Ouverte 2.0, sans clé). */
object IgnImagery {
    const val WMS_URL = "https://data.geopf.fr/wms-r"
    const val LAYER = "ORTHOIMAGERY.ORTHOPHOTOS"
    const val DATASET_URL = "https://cartes.gouv.fr/rechercher-une-donnee/dataset/IGNF_BD-ORTHO"

    /** Emprises des métadonnées IGNF_BD-ORTHO (§3.1). */
    val COVERAGE = listOf(
        GeoBox(41.32, -5.15, 51.10, 9.57),     // France métropolitaine (Corse, Monaco compris)
        GeoBox(15.82, -61.82, 16.52, -60.99),  // Guadeloupe
        GeoBox(14.38, -61.24, 14.89, -60.80),  // Martinique
        GeoBox(2.10, -54.61, 5.76, -51.61),    // Guyane
        GeoBox(-21.40, 55.21, -20.86, 55.85),  // La Réunion
        GeoBox(-13.02, 45.01, -12.63, 45.31),  // Mayotte
        GeoBox(17.87, -62.93, 17.98, -62.78),  // Saint-Barthélemy
        GeoBox(18.04, -63.16, 18.13, -62.90),  // Saint-Martin
        GeoBox(46.74, -56.53, 47.15, -56.07)   // Saint-Pierre-et-Miquelon
    )

    val SOURCE = ImagerySource(
        id = "ign-fr",
        coverage = COVERAGE,
        wmsUrl = WMS_URL,
        layer = LAYER,
        datasetUrl = DATASET_URL,
        serverMaxPx = 5010
    )

    // ---------------------------------------------------------------- date de la mention (§2.4)

    /** WFS de la Géoplateforme : graphe de mosaïquage de la BD ORTHO® (zones de vol datées). */
    const val WFS_URL = "https://data.geopf.fr/wfs/ows"
    const val GRAPH_LAYER = "ORTHOIMAGERY.ORTHOPHOTOS.GRAPHE-MOSAIQUAGE:graphe_bdortho"
    /** Zones de vol lues au plus par requête (une image en coupe rarement plus de 3). */
    const val GRAPH_MAX_FEATURES = 100
    /** Catalogue CSW : fiche du jeu de données (date de mise à jour, repli de la mention). */
    const val CSW_URL = "https://data.geopf.fr/csw"
    const val METADATA_ID = "IGNF_BD-ORTHO"
    const val DATASET_RECORD_URL =
        "$CSW_URL?SERVICE=CSW&VERSION=2.0.2&REQUEST=GetRecordById&ID=$METADATA_ID&ELEMENTSETNAME=summary"

    /** Rayon de la sphère Web Mercator (EPSG:3857) : la pyramide du WMS-R est en niveaux PM 0…19. */
    const val PM_RADIUS = 6_378_137.0
    /** Résolution du niveau PM 18 (m/px en Web Mercator) : 2π·R / 256 / 2¹⁸ ≈ 0,5971643. */
    val PM_Z18_RES: Double = 2 * PI * PM_RADIUS / 256 / (1 shl 18)
    /**
     * Choix du niveau par le WMS-R (mesuré près d'Apatou, Guyane, §1.9 d) : le plus grossier
     * dont la résolution ne dépasse pas résolution demandée / 0,8 → niveau 19 tant que la
     * résolution demandée < 0,8 × z18 ≈ 0,4777 m.
     */
    const val LEVEL_RATIO = 0.8
    /** Le niveau 19 ne contient que les zones de vol de 20 cm (celles de 50 cm y sont blanches). */
    const val Z19_MAX_RES_CM = 20

    /**
     * Panne PASSAGÈRE de la Géoplateforme (502, 503, 504, exception `LayerNotDefined`) : UN
     * nouvel essai de la même requête après ~1 s — image et date ; jamais sur les autres
     * erreurs (parité web `SAT_TRANSIENT_RETRY_MS`, iOS `IGNImagery.transientRetryDelay`).
     */
    const val TRANSIENT_RETRY_MS = 1_000L
    /** Corps lu pour reconnaître une exception passagère : 64 Kio au plus (un rapport d'exception en fait quelques centaines d'octets). */
    const val TRANSIENT_MAX_BODY = 64 * 1024

    /** Nombre à 7 décimales des URL (même fonction que GetMap, §2.3). */
    private fun f7(v: Double) = ImagerySource.num(if (v == 0.0) 0.0 else v)

    /**
     * URL WFS des zones de vol qui COUPENT l'emprise EXACTE de l'image (§2.4) : filtre
     * `INTERSECTS(geom,POLYGON((lat lon,…)))` — lat PUIS lon comme le BBOX de GetMap (le
     * point inversé ne trouve rien), 7 décimales, espace écrit `%20`, anneau fermé sud-ouest,
     * sud-est, nord-est, nord-ouest, sud-ouest. Sans géométrie (`PROPERTYNAME`) ; `res` sert à
     * écarter les zones que l'image ne montre pas.
     */
    fun captureDatesUrl(bbox: GeoBox): String {
        fun pt(lat: Double, lon: Double) = "${f7(lat)}%20${f7(lon)}"
        val sw = pt(bbox.minLat, bbox.minLon); val se = pt(bbox.minLat, bbox.maxLon)
        val ne = pt(bbox.maxLat, bbox.maxLon); val nw = pt(bbox.maxLat, bbox.minLon)
        return "$WFS_URL?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=$GRAPH_LAYER" +
            "&OUTPUTFORMAT=application/json&PROPERTYNAME=dep,pva,res,date_vol&COUNT=$GRAPH_MAX_FEATURES" +
            "&CQL_FILTER=INTERSECTS(geom,POLYGON(($sw,$se,$ne,$nw,$sw)))"
    }

    /**
     * Résolution Web Mercator (m/px) que le serveur attribue à une image GetMap [bbox] de
     * [width] × [height] : √(rx · ry), rx = Δlon·R/width, ry = Δy/height, y = R·ln tan(π/4 + φ/2).
     * NaN si l'emprise ou la taille est invalide.
     */
    fun pmResolution(bbox: GeoBox, width: Int, height: Int): Double {
        if (width <= 0 || height <= 0) return Double.NaN
        fun y(lat: Double) = PM_RADIUS * ln(tan(PI / 4 + lat * PI / 360))
        val rx = (bbox.maxLon - bbox.minLon) * PI / 180 * PM_RADIUS / width
        val ry = (y(bbox.maxLat) - y(bbox.minLat)) / height
        return if (rx > 0 && ry > 0) sqrt(rx * ry) else Double.NaN
    }

    /**
     * Résolution native maximale (cm) des zones VISIBLES sur l'image (§1.9 d) : 20 quand le
     * serveur sert le niveau 19 (l'app à 0,20 m/px), null sinon (toutes les zones sont dessinées).
     */
    fun visibleMaxResCm(bbox: GeoBox, width: Int, height: Int): Int? {
        val r = pmResolution(bbox, width, height)
        return if (r.isFinite() && r < LEVEL_RATIO * PM_Z18_RES) Z19_MAX_RES_CM else null
    }

    /** Année du calendrier GRÉGORIEN (jamais `Calendar.getInstance()` : bouddhiste en th_TH). */
    fun gregorianYear(nowMs: Long = System.currentTimeMillis()): Int =
        GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply { timeInMillis = nowMs }.get(java.util.Calendar.YEAR)

    private val DATE_VOL = Regex("""^(\d{4})-\d{2}-\d{2}""")
    private val FOUR_DIGITS = Regex("""^\d{4}$""")

    /**
     * Années de prise de vue lues dans la réponse JSON du WFS (§2.4) : pour chaque zone,
     * l'année de `date_vol` (« AAAA-MM-JJ… »), à défaut `pva` (entier ou texte de 4
     * chiffres) ; années hors [2000 ; [currentYear] + 1] écartées ; zone dont `res` (nombre
     * ou texte) dépasse [maxResCm] écartée — invisible à cette résolution —, `res` absent :
     * zone gardée. → [ImageryDate.Capture] (plus ancienne, plus récente), ou null (aucune
     * zone, JSON illisible, XML d'erreur, seulement des zones invisibles).
     */
    fun parseCaptureDates(json: String?, currentYear: Int, maxResCm: Int?): ImageryDate.Capture? {
        if (json.isNullOrBlank()) return null
        val features = try {
            org.json.JSONObject(json).optJSONArray("features")
        } catch (_: org.json.JSONException) {
            return null
        } ?: return null
        var from = Int.MAX_VALUE; var to = Int.MIN_VALUE
        for (i in 0 until features.length()) {
            val p = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
            val res = when (val r = p.opt("res")) {
                is Number -> r.toDouble()
                is String -> r.trim().toDoubleOrNull()
                else -> null
            }
            if (maxResCm != null && res != null && res.isFinite() && res > maxResCm) continue
            val dateVol = (p.opt("date_vol") as? String)?.trim()
            val y = dateVol?.let { DATE_VOL.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: when (val pva = p.opt("pva")) {
                    is Int -> pva
                    is Long -> pva.toInt()
                    is Number -> pva.toDouble().takeIf { it == Math.floor(it) && it.isFinite() }?.toInt()
                    is String -> pva.trim().takeIf { FOUR_DIGITS.matches(it) }?.toInt()
                    else -> null
                }
                ?: continue
            // Deux comparaisons (jamais une plage) : borne haute calculée.
            if (y < 2000 || y > currentYear + 1) continue
            if (y < from) from = y
            if (y > to) to = y
        }
        return if (from <= to) ImageryDate.Capture(from, to) else null
    }

    private val IDENTIFIER = Regex("""<(?:\w+:)?identifier>\s*${Regex.escape(METADATA_ID)}\s*</(?:\w+:)?identifier>""")
    private val MODIFIED = Regex("""<(?:\w+:)?modified>\s*(\d{4})-(\d{2})-(\d{2})\s*</(?:\w+:)?modified>""")

    /**
     * Date de dernière mise à jour du jeu de données lue dans la fiche CSW résumée (§2.4,
     * repli) : la fiche doit être celle de `IGNF_BD-ORTHO` ; chaque `dct:modified`
     * AAAA-MM-JJ qui est une vraie date du calendrier grégorien (UTC, non lénient),
     * ≥ 2000-01-01 et ≤ demain (marge de fuseau) ; la plus récente. Sinon null.
     */
    fun parseDatasetUpdate(xml: String?, nowMs: Long): ImageryDate.Updated? {
        if (xml.isNullOrEmpty() || !IDENTIFIER.containsMatchIn(xml)) return null
        val limit = nowMs + 86_400_000L
        var best: ImageryDate.Updated? = null
        for (m in MODIFIED.findAll(xml)) {
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
            if (y < 2000 || mo !in 1..12 || d !in 1..31) continue
            val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                isLenient = false
                clear()
                set(y, mo - 1, d)
            }
            val ms = try { cal.timeInMillis } catch (_: IllegalArgumentException) { continue }
            if (ms > limit) continue
            val u = ImageryDate.Updated(y, mo, d)
            if (best == null || u.isAfter(best)) best = u
        }
        return best
    }

    private val TRANSIENT_CODE = Regex("""(?:code|exceptionCode)\s*=\s*["']LayerNotDefined["']""")

    /**
     * Réponse d'une panne PASSAGÈRE de la Géoplateforme, qui mérite UN nouvel essai immédiat
     * (parité web `isTransientIgnFailure`, iOS `IGNImagery.isTransientFailure`) : 502, 503 ou
     * 504 (passerelle, service surchargé, délai du serveur), ou un rapport d'exception
     * `LayerNotDefined` (`code="…"` ou `exceptionCode="…"`, en 200, 400 ou 404, type XML ou
     * texte, 64 Kio au plus) — que le service renvoie parfois, le temps d'un instant, pour une
     * couche qui existe. JAMAIS sur les autres erreurs : 400 (requête fausse), 404, 429 (trop
     * de requêtes : réessayer aggraverait), 500, ou un autre code d'exception.
     */
    fun isTransientFailure(status: Int, contentType: String?, body: String?): Boolean {
        if (status == 502 || status == 503 || status == 504) return true
        if (status != 200 && status != 400 && status != 404) return false
        val type = contentType.orEmpty().lowercase(Locale.ROOT)
        if (type.isNotEmpty() && !type.contains("xml") && !type.startsWith("text/")) return false
        val text = body.orEmpty()
        if (text.length > TRANSIENT_MAX_BODY) return false
        if (!text.contains("ServiceException") && !text.contains("ExceptionReport")) return false
        return TRANSIENT_CODE.containsMatchIn(text)
    }
}

/**
 * Date de la mention (§2.4, §4.1) : années de prise de vue des zones VISIBLES de l'image
 * (WFS), sinon date de mise à jour du jeu de données (fiche CSW). Aucune → mention sans
 * date, jamais une date inventée.
 */
sealed interface ImageryDate {
    data class Capture(val from: Int, val to: Int) : ImageryDate
    data class Updated(val year: Int, val month: Int, val day: Int) : ImageryDate {
        fun isAfter(o: Updated): Boolean =
            compareValuesBy(this, o, Updated::year, Updated::month, Updated::day) > 0
    }
}

/** Registre ordonné des sources (§8). */
object ImagerySources {
    val ALL: List<ImagerySource> = listOf(IgnImagery.SOURCE)

    /** Première source qui couvre le point, ou null (hors couverture). */
    fun forPoint(lat: Double, lon: Double): ImagerySource? = ALL.firstOrNull { it.covers(lat, lon) }
}

/** Pré-filtre IGN (§3.2) — le point testé est la 1re ancre de la calibration. */
fun ignCovers(lat: Double, lon: Double): Boolean = IgnImagery.SOURCE.covers(lat, lon)
