package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille les DEUX garanties de l'avancement nommé (#4) : les tranches des
 * étapes (le % est GLOBAL, il ne repart pas de zéro à chaque étape) et la
 * MONOTONIE (jamais de retour en arrière, sauf `restart`).
 */
class LoadProgressTest {

    @Test
    fun `les tranches se suivent sans trou de 0 a 100`() {
        val steps = LoadStep.entries
        assertEquals(0f, steps.first().from, 0f)
        assertEquals(1f, steps.last().to, 0f)
        steps.zipWithNext().forEach { (a, b) ->
            assertEquals("tranches contiguës : ${a.name} → ${b.name}", a.to, b.from, 0f)
            assertTrue("tranche non vide : ${a.name}", a.to > a.from)
        }
    }

    @Test
    fun `le pourcentage suit la tranche de l etape`() {
        val p = LoadProgress()
        p.report(LoadStep.READ_MVR, 0f)
        assertEquals(0, p.percent)
        p.report(LoadStep.READ_MVR, 1f)
        assertEquals(10, p.percent)
        p.report(LoadStep.GEOMETRY, 0.5f)
        assertEquals(25, p.percent)   // 10 + (40-10) * 0.5
        p.report(LoadStep.GDTF, 1f)
        assertEquals(90, p.percent)
        assertEquals(LoadStep.GDTF, p.step)
    }

    @Test
    fun `le pourcentage ne redescend jamais`() {
        val p = LoadProgress()
        p.report(LoadStep.GDTF, 1f)
        assertEquals(90, p.percent)
        // Un rapport « en retard » (étape antérieure) ne doit pas faire reculer
        // la barre — seul le libellé change.
        p.report(LoadStep.BUILD, 0f)
        assertEquals(90, p.percent)
        assertEquals(LoadStep.BUILD, p.step)
    }

    @Test
    fun `frac hors bornes est borne`() {
        val p = LoadProgress()
        p.report(LoadStep.GEOMETRY, 5f)
        assertEquals(40, p.percent)
        val q = LoadProgress()
        q.report(LoadStep.GEOMETRY, -3f)
        assertEquals(10, q.percent)
    }

    @Test
    fun `finish termine a 100 et restart repart de zero`() {
        val p = LoadProgress()
        p.report(LoadStep.BUILD, 0.5f)
        assertTrue(p.active)
        p.finish()
        assertEquals(100, p.percent)
        assertNull(p.step)
        assertFalse(p.active)
        p.restart()
        assertEquals(0, p.percent)
        assertFalse(p.active)
    }
}
