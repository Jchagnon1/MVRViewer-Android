package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le LOD d'interaction PAR CALQUE (#1) — sans moteur 3D : la règle
 * de répartition est une fonction pure, c'est elle qui décide ce qui disparaît
 * pendant un geste caméra.
 *
 * L'exigence n°1 est la NON-RÉGRESSION : tout en « Auto » doit reproduire
 * EXACTEMENT l'ancien comportement (petits décors .3ds + silhouettes GDTF
 * masqués, cubes de repli montrés, gros décor .glb jamais masqué).
 */
class LayerLodTest {

    // Nœuds simulés par des chaînes : seule la répartition est testée.
    private val petitDecor = LodItem("siège", "DÉCOR", autoCandidate = true)
    private val grosDecor = LodItem("pont.glb", "STRUCTURE", autoCandidate = false)
    private val silhouette = LodItem("proj-silhouette", "FACE", autoCandidate = true)
    private val entries = listOf(petitDecor, grosDecor, silhouette)
    private val proxies = listOf(LodItem("cube-proj", "FACE", true))

    @Test
    fun `tout en Auto reproduit l ancien comportement`() {
        val b = computeLodBuckets(entries, proxies, emptyMap())
        assertEquals(listOf("siège", "proj-silhouette"), b.hideOnMove)
        assertEquals(listOf("cube-proj"), b.showOnMove)
    }

    @Test
    fun `Toujours visible n est jamais degrade`() {
        val b = computeLodBuckets(entries, proxies, mapOf("FACE" to LayerLodMode.ALWAYS))
        // La silhouette du projecteur reste affichée pendant le geste…
        assertTrue("proj-silhouette" !in b.hideOnMove)
        // …et son cube de repli ne s'affiche donc pas (il ferait doublon).
        assertTrue(b.showOnMove.isEmpty())
        // Le reste garde le comportement Auto.
        assertEquals(listOf("siège"), b.hideOnMove)
    }

    @Test
    fun `Masquer en navigation masque tout le calque, meme le gros decor glb`() {
        val b = computeLodBuckets(entries, proxies, mapOf("STRUCTURE" to LayerLodMode.HIDE_NAV))
        assertTrue("pont.glb" in b.hideOnMove)
        // Le gros décor .glb n'aurait JAMAIS été masqué en Auto.
        assertTrue("pont.glb" !in computeLodBuckets(entries, proxies, emptyMap()).hideOnMove)
    }

    @Test
    fun `Masquer en navigation ne montre aucun cube de repli`() {
        val b = computeLodBuckets(entries, proxies, mapOf("FACE" to LayerLodMode.HIDE_NAV))
        assertTrue("proj-silhouette" in b.hideOnMove)
        assertTrue("rien ne doit être montré pour ce calque", b.showOnMove.isEmpty())
    }

    @Test
    fun `un calque non regle reste en Auto`() {
        val modes = mapOf("STRUCTURE" to LayerLodMode.HIDE_NAV)
        assertEquals(LayerLodMode.AUTO, modes.lodMode("DÉCOR"))
        assertEquals(LayerLodMode.HIDE_NAV, modes.lodMode("STRUCTURE"))
    }

    @Test
    fun `valeurs persistees rondes`() {
        LayerLodMode.entries.forEach { m ->
            assertEquals(m, LayerLodMode.fromStored(m.stored))
        }
        assertEquals(LayerLodMode.AUTO, LayerLodMode.fromStored(null))
        assertEquals(LayerLodMode.AUTO, LayerLodMode.fromStored("valeur inconnue"))
        // Libellés FIGÉS (vocabulaire commun iOS/Android).
        assertEquals("Auto", LayerLodMode.AUTO.label)
        assertEquals("Toujours visible", LayerLodMode.ALWAYS.label)
        assertEquals("Masquer en navigation", LayerLodMode.HIDE_NAV.label)
    }
}
