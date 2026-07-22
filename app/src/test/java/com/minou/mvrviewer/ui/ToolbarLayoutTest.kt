package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le modèle N11 : défauts = barre bas-gauche actuelle (non-régression),
 * round-trip JSON exact, tolérance aux ids inconnus (le catalogue peut évoluer),
 * et les helpers d'édition (moved / reordered / edgeOf).
 */
class ToolbarLayoutTest {

    @Test
    fun `defaut 3D reproduit la barre bas-gauche actuelle`() {
        val d = ToolbarLayout.default3D
        assertEquals(
            listOf(
                ToolId.RECT, ToolId.MEASURE, ToolId.SOLO, ToolId.CLEAR_SEL,
                ToolId.GPS, ToolId.GPS_MARKER_SIZE
            ),
            d.bottom
        )
        assertTrue(d.top.isEmpty() && d.left.isEmpty() && d.right.isEmpty())
    }

    @Test
    fun `defaut plan reproduit la barre bas-gauche actuelle`() {
        val d = ToolbarLayout.defaultPlan
        assertEquals(
            listOf(
                ToolId.RECT, ToolId.MASK, ToolId.SOLO, ToolId.MEASURE, ToolId.SHOW_ALL,
                ToolId.CLEAR_SOLO, ToolId.CLEAR_SEL, ToolId.GPS, ToolId.CALIBRATE,
                ToolId.SATELLITE, ToolId.EXPORT_PDF, ToolId.DXF
            ),
            d.bottom
        )
        assertTrue(d.top.isEmpty() && d.left.isEmpty() && d.right.isEmpty())
    }

    @Test
    fun `round-trip JSON preserve les 4 bords`() {
        val l = ToolbarLayout(
            top = listOf(ToolId.SEARCH),
            bottom = listOf(ToolId.RECT, ToolId.MEASURE),
            left = listOf(ToolId.GPS),
            right = listOf(ToolId.LABELS, ToolId.LAYER_COLORS)
        )
        val back = ToolbarLayout.fromJson(l.toJson(), ToolbarLayout.default3D)
        assertEquals(l, back)
    }

    @Test
    fun `fromJson tolere les ids inconnus (ignores)`() {
        val raw = """{"top":[],"bottom":["RECT","PAS_UN_OUTIL","MEASURE"],"left":[],"right":[]}"""
        val l = ToolbarLayout.fromJson(raw, ToolbarLayout.default3D)
        assertEquals(listOf(ToolId.RECT, ToolId.MEASURE), l.bottom)
    }

    @Test
    fun `fromJson vide ou illisible retombe sur le defaut`() {
        assertEquals(ToolbarLayout.defaultPlan, ToolbarLayout.fromJson(null, ToolbarLayout.defaultPlan))
        assertEquals(ToolbarLayout.defaultPlan, ToolbarLayout.fromJson("", ToolbarLayout.defaultPlan))
        assertEquals(ToolbarLayout.defaultPlan, ToolbarLayout.fromJson("{pas du json", ToolbarLayout.defaultPlan))
    }

    @Test
    fun `fromJson dedoublonne un id present dans deux barres`() {
        val raw = """{"top":["RECT"],"bottom":["RECT","MEASURE"],"left":[],"right":[]}"""
        val l = ToolbarLayout.fromJson(raw, ToolbarLayout.default3D)
        // Première occurrence gardée (haut → bas → gauche → droite).
        assertEquals(listOf(ToolId.RECT), l.top)
        assertEquals(listOf(ToolId.MEASURE), l.bottom)
    }

    @Test
    fun `moved retire de tous les bords avant d'ajouter`() {
        val l = ToolbarLayout(bottom = listOf(ToolId.RECT, ToolId.MEASURE))
        val moved = l.moved(ToolId.RECT, ToolbarEdge.LEFT)
        assertEquals(listOf(ToolId.MEASURE), moved.bottom)
        assertEquals(listOf(ToolId.RECT), moved.left)
        // Jamais dans deux barres à la fois.
        assertEquals(1, moved.allAssigned().count { it == ToolId.RECT })
    }

    @Test
    fun `moved vers null retire des barres (hors barres)`() {
        val l = ToolbarLayout(bottom = listOf(ToolId.RECT, ToolId.MEASURE))
        val off = l.moved(ToolId.RECT, null)
        assertNull(off.edgeOf(ToolId.RECT))
        assertEquals(listOf(ToolId.MEASURE), off.bottom)
    }

    @Test
    fun `reordered echange avec le voisin et respecte les butees`() {
        val l = ToolbarLayout(bottom = listOf(ToolId.RECT, ToolId.MEASURE, ToolId.SOLO))
        // Descendre RECT → passe après MEASURE.
        assertEquals(listOf(ToolId.MEASURE, ToolId.RECT, ToolId.SOLO), l.reordered(ToolId.RECT, +1).bottom)
        // Monter RECT (déjà en tête) → inchangé.
        assertEquals(l, l.reordered(ToolId.RECT, -1))
        // Descendre SOLO (déjà en queue) → inchangé.
        assertEquals(l, l.reordered(ToolId.SOLO, +1))
        // Un outil hors barres → inchangé.
        assertEquals(l, l.reordered(ToolId.LABELS, +1))
    }

    @Test
    fun `edgeOf localise l'outil ou renvoie null`() {
        val l = ToolbarLayout(top = listOf(ToolId.SEARCH), bottom = listOf(ToolId.RECT))
        assertEquals(ToolbarEdge.TOP, l.edgeOf(ToolId.SEARCH))
        assertEquals(ToolbarEdge.BOTTOM, l.edgeOf(ToolId.RECT))
        assertNull(l.edgeOf(ToolId.DXF))
    }
}
