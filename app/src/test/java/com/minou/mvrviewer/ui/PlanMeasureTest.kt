package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.DxfPlan
import com.minou.mvrviewer.mvr.DxfPolyline
import com.minou.mvrviewer.mvr.ReferencePlanTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille la COTE. Une erreur de facteur 1000 (mm pris pour des mètres) ne
 * se voit pas à l'écran et ne se découvre qu'au montage — c'est exactement ce
 * que ces cas empêchent.
 */
class PlanMeasureTest {

    @Test
    fun `les coordonnees plan sont des millimetres`() {
        // 5 m dans le repère plan = 5000 unités.
        val a = MeasurePoint(0f, 0f, false)
        val b = MeasurePoint(3000f, 4000f, false)
        assertEquals(5000.0, measureDistanceMm(a, b), 1e-6)
        assertEquals("5,00 m", formatPlanDistanceMm(measureDistanceMm(a, b)))
    }

    @Test
    fun `bascule d'unite mm cm m`() {
        assertEquals("7 mm", formatPlanDistanceMm(7.0))
        assertEquals("45,6 cm", formatPlanDistanceMm(456.0))
        assertEquals("99,9 cm", formatPlanDistanceMm(999.0))
        assertEquals("1,00 m", formatPlanDistanceMm(1000.0))
        assertEquals("12,34 m", formatPlanDistanceMm(12_340.0))
    }

    @Test
    fun `distance nulle et valeurs non finies`() {
        assertEquals("0 mm", formatPlanDistanceMm(0.0))
        assertEquals("—", formatPlanDistanceMm(Double.NaN))
    }

    private fun planOf(vararg pts: Float) = DxfPlan(
        polylines = listOf(DxfPolyline(pts, false, "0")),
        minX = 0f, minY = 0f, maxX = 1f, maxY = 1f,
        unitLabel = "mm", segmentCount = 1, layerCounts = emptyMap(), truncatedSegments = 0
    )

    @Test
    fun `accrochage a un sommet DXF, placement identite`() {
        val plan = planOf(0f, 0f, 1000f, 500f)
        val v = dxfSnapVertices(plan)
        assertEquals(4, v.size)
        // Le repère plan inverse Y : le sommet (1000, 500) est en (1000, −500).
        val p = snapDxfVertex(v, ReferencePlanTransform(), 1010f, -495f, 50f)
        assertNotNull(p)
        assertEquals(1000f, p!!.px, 1e-3f)
        assertEquals(-500f, p.py, 1e-3f)
        assertTrue(p.snapped)
    }

    @Test
    fun `hors rayon, pas d'accrochage`() {
        val plan = planOf(0f, 0f, 1000f, 500f)
        assertNull(snapDxfVertex(dxfSnapVertices(plan), ReferencePlanTransform(), 5000f, 5000f, 50f))
    }

    @Test
    fun `le placement du plan DXF est pris en compte`() {
        val plan = planOf(1000f, 0f)
        // Échelle 2 puis rotation d'un quart de tour : (1000,0) → (0, 2000).
        val tf = ReferencePlanTransform(offsetX = 100.0, offsetY = 0.0, rotationDeg = 90.0, scale = 2.0)
        val p = snapDxfVertex(dxfSnapVertices(plan), tf, 100f, -2000f, 10f)
        assertNotNull(p)
        assertEquals(100f, p!!.px, 1e-2f)
        assertEquals(-2000f, p.py, 1e-2f)
    }

    @Test
    fun `un calque masque n'aimante plus le doigt`() {
        // Deux traits, un par calque ; « MOBILIER » est éteint dans la vue.
        val plan = DxfPlan(
            polylines = listOf(
                DxfPolyline(floatArrayOf(0f, 0f), false, "MURS"),
                DxfPolyline(floatArrayOf(5000f, 0f), false, "MOBILIER")
            ),
            minX = 0f, minY = 0f, maxX = 5000f, maxY = 1f,
            unitLabel = "mm", segmentCount = 2, layerCounts = emptyMap(), truncatedSegments = 0
        )
        // Sans masquage, le sommet du mobilier est bien un candidat.
        assertNotNull(
            snapDxfVertex(dxfSnapVertices(plan), ReferencePlanTransform(), 5010f, 0f, 100f)
        )
        // Calque masqué : plus aucun candidat là où il n'y a plus rien de visible.
        val visible = dxfSnapVertices(plan, hiddenLayers = setOf("MOBILIER"))
        assertEquals(2, visible.size)
        assertNull(snapDxfVertex(visible, ReferencePlanTransform(), 5010f, 0f, 100f))
        // …et le calque resté visible s'accroche toujours.
        assertNotNull(snapDxfVertex(visible, ReferencePlanTransform(), 10f, 0f, 100f))
    }

    @Test
    fun `au dela du plafond les sommets sont sous-echantillonnes, pas tronques`() {
        // 100 sommets répartis de x=0 à x=9900, plafond à 10.
        val pts = FloatArray(200) { i -> if (i % 2 == 0) (i / 2) * 100f else 0f }
        val v = dxfSnapVertices(planOf(*pts), max = 10)
        assertTrue(v.size / 2 <= 10)
        // La couverture va jusqu'au bout du plan (une troncature s'arrêterait
        // aux premiers sommets, laissant l'autre extrémité sans accrochage).
        var maxX = 0f
        var i = 0
        while (i < v.size) { if (v[i] > maxX) maxX = v[i]; i += 2 }
        assertTrue("couverture insuffisante : $maxX", maxX >= 8000f)
    }
}
