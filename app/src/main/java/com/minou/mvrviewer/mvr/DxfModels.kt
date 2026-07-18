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
    val truncatedSegments: Int
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
    var visible: Boolean = true
) {
    /** Point DXF (mm local) → point monde MVR (mm, X/Y au sol). */
    fun worldXY(x: Float, y: Float): Pair<Float, Float> {
        val s = scale
        val r = Math.toRadians(rotationDeg)
        val c = cos(r); val sn = sin(r)
        val sx = x * s; val sy = y * s
        return (offsetX + (sx * c - sy * sn)).toFloat() to (offsetY + (sx * sn + sy * c)).toFloat()
    }

    fun copy() = ReferencePlanTransform(offsetX, offsetY, rotationDeg, scale, heightZ, visible)
}

/** Un plan importé + son placement. */
class ReferencePlan(val plan: DxfPlan, val transform: ReferencePlanTransform)
