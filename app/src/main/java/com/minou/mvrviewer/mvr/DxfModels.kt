package com.minou.mvrviewer.mvr

import kotlin.math.cos
import kotlin.math.sin

/**
 * Plan de repère DXF importé — portage (partiel) de DXFPlan (iOS). Le plan est
 * un ensemble de polylignes 2D (en mm, repère local du fichier), projetées « de
 * dessus », plus les bornes et le calque de chaque tracé. v1 : géométrie
 * monochrome (LINE / LWPOLYLINE / POLYLINE / CIRCLE / ARC / INSERT). Couleurs
 * par calque, splines/ellipses/hachures et rendu 3D = à venir.
 */
class DxfPolyline(
    /** Sommets à plat [x0,y0, x1,y1, …] en mm (repère local DXF). */
    val points: FloatArray,
    val closed: Boolean,
    val layer: String,
    /** Couleur RÉSOLUE de l'entité (0xRRGGBB) : true color (420) sinon ACI (62),
     *  BYLAYER/BYBLOCK résolus. Défaut 0xFFFFFF (blanc → géré par le contraste). */
    val color: Int = 0xFFFFFF
)

/**
 * Zone REMPLIE (HATCH / SOLID) : anneaux de contour (le 1er = extérieur, les
 * suivants = trous), règle even-odd. Points à plat [x0,y0, …] en mm.
 */
class DxfFill(
    val rings: List<FloatArray>,
    val color: Int,
    /** true = aplat (SOLID) ; false = motif hachuré (rendu en aplat translucide). */
    val solid: Boolean,
    val layer: String
)

class DxfPlan(
    val polylines: List<DxfPolyline>,
    val minX: Float, val minY: Float,
    val maxX: Float, val maxY: Float,
    val unitLabel: String,
    val segmentCount: Int,
    val layerCounts: Map<String, Int>,
    /** Segments écartés (plafond mémoire atteint) ; 0 = import complet. */
    val truncatedSegments: Int,
    /** Couleur par calque (0xRRGGBB) — résolution BYLAYER. */
    val layerColors: Map<String, Int> = emptyMap(),
    /** Calques éteints/gelés à l'import → masqués par défaut. */
    val defaultHiddenLayers: Set<String> = emptySet(),
    /** Zones remplies (HATCH/SOLID), dessinées SOUS les traits. */
    val fills: List<DxfFill> = emptyList()
) {
    val isEmpty: Boolean get() = polylines.isEmpty()
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f
}

/**
 * Transformée réglable du plan importé, PAR RAPPORT au repère MVR : décalage au
 * sol (mm), rotation autour de la verticale, échelle (rattrape une unité
 * différente), hauteur, visibilité. Portage de ReferencePlanTransform (iOS).
 */
class ReferencePlanTransform(
    var offsetX: Double = 0.0,
    var offsetY: Double = 0.0,
    var rotationDeg: Double = 0.0,
    var scale: Double = 1.0,
    var heightZ: Double = 0.0,
    var visible: Boolean = true,
    /**
     * HOMOTHÉTIE — facteur de mise à l'échelle PROPORTIONNELLE réglé par
     * l'utilisateur (curseur + valeur éditable), distinct de `scale` qui, lui,
     * rattrape l'unité du fichier / le cadrage initial. Les deux se MULTIPLIENT
     * (cf. `effScale`) : garder deux champs permet de « Réinitialiser »
     * l'homothétie sans perdre l'échelle d'origine du plan importé.
     *
     * Défaut 1.0 → `effScale == scale` au bit près : un projet existant (et tout
     * le chemin DXF) se comporte exactement comme avant.
     */
    var homothety: Double = 1.0
) {
    /**
     * Échelle RÉELLEMENT appliquée au dessin (2D, 3D, mesure, export) : produit
     * de l'échelle de placement et de l'homothétie. C'est la SEULE valeur que
     * doivent lire les rendus — jamais `scale` seul.
     */
    val effScale: Double get() = scale * homothety

    /** Point DXF (mm local) → point monde MVR (mm, X/Y au sol). */
    fun worldXY(x: Float, y: Float): Pair<Float, Float> {
        val s = effScale
        val r = Math.toRadians(rotationDeg)
        val c = cos(r); val sn = sin(r)
        val sx = x * s; val sy = y * s
        return (offsetX + (sx * c - sy * sn)).toFloat() to (offsetY + (sx * sn + sy * c)).toFloat()
    }

    fun copy() = ReferencePlanTransform(offsetX, offsetY, rotationDeg, scale, heightZ, visible, homothety)
}

/**
 * Un plan importé + son placement.
 *
 * Le plan est SOIT vectoriel (DXF → `plan` non vide), SOIT matriciel (JPEG / PNG
 * / page 1 d'un PDF → `raster` non nul). Dans le second cas `plan` est un
 * `DxfPlan` VIDE dont seules les bornes portent le rectangle de l'image (en mm) :
 * tout le code existant qui teste `plan.isEmpty` (passe 3D, tracés 2D) sort donc
 * proprement, sans trait fantôme, et le centrage/les bornes restent partagés.
 */
class ReferencePlan(
    val plan: DxfPlan,
    val transform: ReferencePlanTransform,
    val raster: RasterPlan? = null
)
