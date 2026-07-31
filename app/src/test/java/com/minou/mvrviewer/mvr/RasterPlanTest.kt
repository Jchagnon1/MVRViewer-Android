package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan de repère MATRICIEL (JPEG / PNG / page 1 d'un PDF).
 *
 * Ce qui est testable en JVM (sans `Bitmap`, non mocké par le runner) : la règle
 * de SOUS-ÉCHANTILLONNAGE — la seule barrière entre un scan A0 à 600 dpi et un
 * OOM, précédent documenté du projet — et la géométrie du placement (bornes
 * locales, coins monde). Le décodage lui-même est un appel direct à
 * `BitmapFactory`, sans logique propre.
 */
class RasterPlanTest {

    private fun sample(w: Int, h: Int, maxSide: Int = 4096, maxPx: Long = 12_000_000L) =
        RasterPlanLoader.sampleSizeFor(w, h, maxSide, maxPx)

    @Test fun petiteImageNonSousEchantillonnee() {
        assertEquals(1, sample(1920, 1080))
        assertEquals(1, sample(4096, 2000))
    }

    @Test fun cotesTropGrandsSontDivises() {
        // 8192 → 4096 en une division ; puissance de 2 exigée par BitmapFactory.
        assertEquals(2, sample(8192, 1000))
        assertEquals(4, sample(16384, 1000))
    }

    @Test fun budgetPixelsRespecteMemeSansCoteExcessif() {
        // 4000 × 4000 = 16 Mpx : sous la borne de côté, AU-DESSUS du budget.
        val s = sample(4000, 4000)
        assertTrue("attendu un sous-échantillonnage, obtenu $s", s >= 2)
        assertTrue((4000L / s) * (4000L / s) <= 12_000_000L)
    }

    @Test fun scanEnormeResteBorne() {
        // ~500 Mpx (A0 à 600 dpi) : les DEUX bornes doivent tenir après division.
        val w = 28080; val h = 19860
        val s = sample(w, h)
        assertTrue(w / s <= 4096 && h / s <= 4096)
        assertTrue((w / s).toLong() * (h / s).toLong() <= 12_000_000L)
        assertEquals(0, s and (s - 1))   // puissance de 2
    }

    @Test fun dimensionsInvalidesRenvoientUn() {
        assertEquals(1, sample(0, 0))
        assertEquals(1, sample(-5, 100))
    }

    @Test fun bornesLocalesCentreesSurLOrigine() {
        // Le DxfPlan porteur est VIDE (aucune polyligne) mais garde les bornes de
        // l'image : c'est ce qui fait sortir proprement la passe DXF 3D et les
        // tracés 2D, tout en gardant le centrage à l'import commun aux deux plans.
        val p = emptyDxfPlanFor(4000f, 2000f)
        assertTrue(p.isEmpty)
        assertEquals(4000f, p.width, 0f)
        assertEquals(2000f, p.height, 0f)
        assertEquals(0f, p.centerX, 0f)
        assertEquals(0f, p.centerY, 0f)
        assertEquals(-2000f, p.minX, 0f); assertEquals(1000f, p.maxY, 0f)
    }

    @Test fun coinsMondeSuiventPlacementEtHomothetie() {
        val tf = ReferencePlanTransform(offsetX = 1000.0, offsetY = 500.0, homothety = 2.0)
        val c = rasterWorldCorners(2000f, 1000f, tf)
        // NO, NE, SE, SO — image doublée (homothétie ×2), puis décalée.
        assertEquals(-3000f, c[0], 1e-2f); assertEquals(2500f, c[1], 1e-2f)   // NO
        assertEquals(5000f, c[2], 1e-2f); assertEquals(2500f, c[3], 1e-2f)    // NE
        assertEquals(5000f, c[4], 1e-2f); assertEquals(-1500f, c[5], 1e-2f)   // SE
        assertEquals(-3000f, c[6], 1e-2f); assertEquals(-1500f, c[7], 1e-2f)  // SO
    }

    @Test fun rotationDeQuatreVingtDixDegres() {
        // Rotation pure : le coin NO part sur la droite, sans déformation
        // (les 4 côtés gardent leurs longueurs 4000 × 2000).
        val c = rasterWorldCorners(2000f, 1000f, ReferencePlanTransform(rotationDeg = 90.0))
        fun dist(i: Int, j: Int) = kotlin.math.hypot(c[i] - c[j], c[i + 1] - c[j + 1])
        assertEquals(4000f, dist(0, 2), 1e-2f)   // NO→NE
        assertEquals(2000f, dist(2, 4), 1e-2f)   // NE→SE
        assertEquals(-1000f, c[0], 1e-2f); assertEquals(-2000f, c[1], 1e-2f)
    }
}
