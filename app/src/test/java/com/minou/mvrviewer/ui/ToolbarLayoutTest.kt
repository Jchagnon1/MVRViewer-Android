package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le modèle N11 harmonisé iOS/Android : défauts = VIDE (la barre
 * flottante historique tient lieu de défaut, invariant de non-régression),
 * identifiants persistés = CHAÎNES camelCase canoniques (mêmes rawValues qu'iOS),
 * round-trip JSON exact, tolérance aux ids inconnus (le catalogue peut évoluer),
 * et les helpers d'édition (moved / reordered / edgeOf).
 */
class ToolbarLayoutTest {

    @Test
    fun `defaut 3D est vide (barre flottante par defaut)`() {
        val d = ToolbarLayout.default3D
        assertTrue(d.top.isEmpty() && d.bottom.isEmpty() && d.left.isEmpty() && d.right.isEmpty())
        assertTrue(d.allAssigned().isEmpty())
    }

    @Test
    fun `defaut plan est vide (barre flottante par defaut)`() {
        val d = ToolbarLayout.defaultPlan
        assertTrue(d.top.isEmpty() && d.bottom.isEmpty() && d.left.isEmpty() && d.right.isEmpty())
        assertTrue(d.allAssigned().isEmpty())
    }

    @Test
    fun `barre flottante 3D = ancienne barre bas-gauche`() {
        assertEquals(
            listOf(
                ToolId.RECT, ToolId.MEASURE, ToolId.SOLO, ToolId.CLEAR_SEL,
                ToolId.GPS, ToolId.GPS_MARKER_SIZE
            ),
            ToolbarLayout.floating3D
        )
    }

    @Test
    fun `barre flottante plan = ancienne barre bas-gauche`() {
        assertEquals(
            listOf(
                ToolId.RECT, ToolId.MASK, ToolId.SOLO, ToolId.MEASURE, ToolId.SHOW_ALL,
                ToolId.CLEAR_SOLO, ToolId.CLEAR_SEL, ToolId.GPS, ToolId.CALIBRATE,
                ToolId.SATELLITE, ToolId.EXPORT_PDF, ToolId.DXF
            ),
            ToolbarLayout.floatingPlan
        )
    }

    @Test
    fun `rawId = rawValues iOS canoniques (camelCase, pas enum name)`() {
        // Communs iOS/Android : la chaîne persistée est la rawValue Swift.
        assertEquals("rectSelect", ToolId.RECT.rawId)
        assertEquals("measure", ToolId.MEASURE.rawId)
        assertEquals("solo", ToolId.SOLO.rawId)
        assertEquals("mask", ToolId.MASK.rawId)
        assertEquals("labels", ToolId.LABELS.rawId)
        assertEquals("labelSettings", ToolId.LABEL_SETTINGS.rawId)
        assertEquals("satellite", ToolId.SATELLITE.rawId)
        assertEquals("layerColors", ToolId.LAYER_COLORS.rawId)
        assertEquals("exportPDF", ToolId.EXPORT_PDF.rawId)
        assertEquals("backgroundColor", ToolId.BACKGROUND.rawId)
        assertEquals("resetCamera", ToolId.CAM_RESET.rawId)
        assertEquals("patchList", ToolId.PATCH.rawId)
        assertEquals("universe", ToolId.UNIVERSE.rawId)
        assertEquals("cabling", ToolId.CABLING.rawId)
        // AUCUN id persisté n'est un enum.name UPPER_SNAKE.
        ToolId.entries.forEach { id ->
            assertTrue("rawId ne doit pas être enum.name : ${id.rawId}", id.rawId != id.name)
        }
    }

    @Test
    fun `rawId round-trip via fromRawId pour tout le catalogue`() {
        ToolId.entries.forEach { id ->
            assertEquals(id, ToolId.fromRawId(id.rawId))
        }
        assertNull(ToolId.fromRawId("pasUnOutil"))
    }

    @Test
    fun `toJson ecrit les chaines canoniques camelCase`() {
        val l = ToolbarLayout(bottom = listOf(ToolId.RECT, ToolId.EXPORT_PDF))
        val json = l.toJson()
        assertTrue(json.contains("rectSelect"))
        assertTrue(json.contains("exportPDF"))
        // Jamais le nom d'énumération Kotlin.
        assertTrue(!json.contains("EXPORT_PDF"))
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
        val raw = """{"top":[],"bottom":["rectSelect","pasUnOutil","measure"],"left":[],"right":[]}"""
        val l = ToolbarLayout.fromJson(raw, ToolbarLayout.default3D)
        assertEquals(listOf(ToolId.RECT, ToolId.MEASURE), l.bottom)
    }

    @Test
    fun `fromJson decode les rawValues iOS (interop cross-plateforme)`() {
        // Une disposition telle qu'iOS l'écrirait (rawValues Swift) se relit ici.
        val raw = """{"top":["labels"],"bottom":["rectSelect","exportPDF"],"left":[],"right":["patchList"]}"""
        val l = ToolbarLayout.fromJson(raw, ToolbarLayout.defaultPlan)
        assertEquals(listOf(ToolId.LABELS), l.top)
        assertEquals(listOf(ToolId.RECT, ToolId.EXPORT_PDF), l.bottom)
        assertEquals(listOf(ToolId.PATCH), l.right)
    }

    @Test
    fun `fromJson vide ou illisible retombe sur le defaut`() {
        assertEquals(ToolbarLayout.defaultPlan, ToolbarLayout.fromJson(null, ToolbarLayout.defaultPlan))
        assertEquals(ToolbarLayout.defaultPlan, ToolbarLayout.fromJson("", ToolbarLayout.defaultPlan))
        assertEquals(ToolbarLayout.defaultPlan, ToolbarLayout.fromJson("{pas du json", ToolbarLayout.defaultPlan))
    }

    @Test
    fun `fromJson dedoublonne un id present dans deux barres`() {
        val raw = """{"top":["rectSelect"],"bottom":["rectSelect","measure"],"left":[],"right":[]}"""
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
