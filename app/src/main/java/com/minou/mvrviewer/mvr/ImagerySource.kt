package com.minou.mvrviewer.mvr

import java.util.Calendar
import java.util.Locale

/**
 * SOURCE d'imagerie aérienne pour le fond satellite (SPEC-satellite-ign.md §8).
 *
 * Registre ORDONNÉ : on prend la PREMIÈRE source dont la couverture contient la
 * 1re ancre de la calibration ; aucune → « hors couverture » (pas de fond, aucune
 * requête). Une seule source aujourd'hui : l'IGN (France). L'ancienne imagerie
 * mondiale sans clé a été RETIRÉE : ses conditions interdisent une app vendue.
 *
 * Chaque source décrit : sa couverture (emprises officielles), ses URL WMS 1.3.0
 * (GetMap, GetFeatureInfo), la lecture de la date de prise de vue, la règle « pas de
 * donnée » (ici : image blanche uniforme) et la page du jeu de données (mention).
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

    /**
     * URL GetFeatureInfo au CENTRE de l'image (§2.4) : renvoie le « graphe de
     * mosaïquage » en texte (date_vol, pva…). `FORMAT` est obligatoire même ici.
     */
    fun featureInfoUrl(bbox: GeoBox, width: Int, height: Int): String =
        "$wmsUrl?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetFeatureInfo&LAYERS=$layer&QUERY_LAYERS=$layer" +
            "&STYLES=&CRS=EPSG:4326&BBOX=${bboxParam(bbox)}&WIDTH=$width&HEIGHT=$height" +
            "&FORMAT=image/jpeg&INFO_FORMAT=text/plain&FEATURE_COUNT=1&I=${width / 2}&J=${height / 2}"

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

    private val DATE_VOL = Regex("""^\s*date_vol\s*=\s*(\d{4})-\d{2}-\d{2}""", RegexOption.MULTILINE)
    private val PVA = Regex("""^\s*pva\s*=\s*(\d{4})\s*$""", RegexOption.MULTILINE)

    /**
     * Année de prise de vue lue dans la réponse texte de GetFeatureInfo (§2.4) :
     * `date_vol = AAAA-MM-JJ` d'abord, sinon `pva = AAAA`, sinon null (dont « no
     * features were found »). Hors de [2000 ; année courante + 1] → null.
     */
    fun parseCaptureYear(
        text: String?,
        currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    ): Int? {
        if (text.isNullOrEmpty()) return null
        val y = (DATE_VOL.find(text) ?: PVA.find(text))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (y in 2000..(currentYear + 1)) y else null
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
