package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.DxfPlan
import com.minou.mvrviewer.mvr.ReferencePlanTransform
import java.util.Locale

/**
 * Outil de mesure entre deux points de la vue plan.
 *
 * UNITÉS : tout ce fichier travaille en coordonnées PLAN, c'est-à-dire
 * (X monde, −Y monde) en MILLIMÈTRES — c'est l'unité du MVR, confirmée par la
 * barre d'échelle (« pixels par mm ») et par GeoCalibration.planMillimeters().
 * Une cote fausse d'un facteur 1000 passerait inaperçue jusqu'au montage : le
 * facteur mm→m est donc isolé ici, dans une fonction testée.
 */

/** Une extrémité de mesure, en coordonnées plan (mm). */
internal class MeasurePoint(
    val px: Float,
    val py: Float,
    /** Vrai si le point s'est ACCROCHÉ à un candidat réel (centre, sommet). */
    val snapped: Boolean,
    /** Ce à quoi il s'est accroché, pour l'afficher (nom du projecteur…). */
    val label: String? = null
)

/**
 * Cote lisible sur un chantier : le mètre en dessous du centimètre n'a aucun
 * sens (« 0,04 m »), et le millimètre au-delà du mètre non plus. On bascule
 * donc d'unité, avec la virgule décimale française.
 */
internal fun formatPlanDistanceMm(mm: Double): String {
    if (!mm.isFinite() || mm < 0.0) return "—"
    return when {
        // Sous le centimètre, seule la précision brute a du sens.
        mm < 10.0 -> String.format(Locale.FRANCE, "%.0f mm", mm)
        // Entre 1 cm et 1 m : centimètres, un chiffre après la virgule.
        mm < 1000.0 -> String.format(Locale.FRANCE, "%.1f cm", mm / 10.0)
        // Au-delà : mètres au centimètre près (la tolérance d'un montage).
        else -> String.format(Locale.FRANCE, "%.2f m", mm / 1000.0)
    }
}

/** Distance entre deux points de mesure, en millimètres. */
internal fun measureDistanceMm(a: MeasurePoint, b: MeasurePoint): Double {
    val dx = (b.px - a.px).toDouble()
    val dy = (b.py - a.py).toDouble()
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/**
 * Nombre maximal de sommets DXF retenus pour l'accrochage. Un plan
 * d'architecte peut en compter des millions ; on ne peut pas en garder un
 * tableau complet (mémoire) ni le balayer à chaque toucher.
 */
internal const val MAX_SNAP_VERTICES = 200_000

/**
 * Sommets du plan DXF utilisables pour l'accrochage, à plat [x0,y0, x1,y1, …]
 * dans les coordonnées LOCALES du fichier (le placement est appliqué au moment
 * du toucher, il change en direct).
 *
 * Au-delà du plafond on SOUS-ÉCHANTILLONNE au pas constant plutôt que de
 * tronquer : tronquer ne garderait que le début du fichier, donc un coin du
 * plan, et l'accrochage serait mort ailleurs. Le pas fait perdre des sommets,
 * jamais des régions.
 *
 * Les CALQUES MASQUÉS sont exclus : un plan d'architecte se lit en éteignant
 * les calques dont on ne veut pas, et s'accrocher à un trait invisible donne
 * une cote juste sur une géométrie que l'utilisateur ne voit pas — donc, de son
 * point de vue, une cote fausse et inexplicable.
 */
internal fun dxfSnapVertices(
    plan: DxfPlan,
    hiddenLayers: Set<String> = emptySet(),
    max: Int = MAX_SNAP_VERTICES
): FloatArray {
    var total = 0
    for (pl in plan.polylines) if (pl.layer !in hiddenLayers) total += pl.points.size / 2
    for (fl in plan.fills) if (fl.layer !in hiddenLayers) for (r in fl.rings) total += r.size / 2
    if (total == 0) return FloatArray(0)
    val stride = if (total > max) (total + max - 1) / max else 1
    val out = FloatArray(minOf(total, max) * 2)
    var seen = 0
    var n = 0
    fun push(pts: FloatArray) {
        var i = 0
        while (i + 1 < pts.size) {
            if (seen % stride == 0 && n + 1 < out.size) {
                out[n] = pts[i]; out[n + 1] = pts[i + 1]; n += 2
            }
            seen++
            i += 2
        }
    }
    for (pl in plan.polylines) if (pl.layer !in hiddenLayers) push(pl.points)
    for (fl in plan.fills) if (fl.layer !in hiddenLayers) for (r in fl.rings) push(r)
    return if (n == out.size) out else out.copyOf(n)
}

/**
 * Sommet DXF le plus proche du point touché, dans un rayon donné (tout en
 * coordonnées plan, mm : le rayon écran est converti par l'appelant, la
 * projection plan→écran étant une simple homothétie).
 */
internal fun snapDxfVertex(
    local: FloatArray,
    tf: ReferencePlanTransform,
    tapX: Float,
    tapY: Float,
    radius: Float
): MeasurePoint? {
    if (local.isEmpty() || radius <= 0f) return null
    // Placement déplié à la main : worldXY() par sommet ferait deux appels
    // trigonométriques et une paire allouée pour chacun des 200 000 points.
    val s = tf.effScale
    val r = Math.toRadians(tf.rotationDeg)
    val c = kotlin.math.cos(r)
    val sn = kotlin.math.sin(r)
    var best = radius * radius
    var bx = 0f
    var by = 0f
    var found = false
    var i = 0
    while (i + 1 < local.size) {
        val lx = local[i] * s
        val ly = local[i + 1] * s
        i += 2
        val wx = (tf.offsetX + (lx * c - ly * sn)).toFloat()
        val wy = (tf.offsetY + (lx * sn + ly * c)).toFloat()
        // Repère plan = (X monde, −Y monde).
        val py = -wy
        val dx = wx - tapX
        val dy = py - tapY
        val d = dx * dx + dy * dy
        if (d < best) { best = d; bx = wx; by = py; found = true }
    }
    return if (found) MeasurePoint(bx, by, snapped = true, label = "sommet plan") else null
}
