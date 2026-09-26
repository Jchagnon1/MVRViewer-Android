package com.minou.mvrviewer.mvr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Résolution des images externes d'un .glb vers les entrées du MVR : c'est ce
 * qui fait qu'un arbre Vectorworks (plans de feuillage texturés, alpha MASK)
 * trouve sa texture au lieu de retomber en croix grise.
 */
class GlbImageIndexTest {

    private val feuille = byteArrayOf(1, 2, 3)
    private val ecorce = byteArrayOf(9, 8)
    private val index = GlbImageIndex(mapOf("Feuille Chene.png" to feuille, "ecorce.jpg" to ecorce))

    @Test
    fun nomExact() {
        assertArrayEquals(feuille, index.resolve("Feuille Chene.png"))
    }

    @Test
    fun espacesEncodesEtSousDossier() {
        // Export avec `%20` et un sous-dossier : on ne garde que le nom de base décodé.
        assertArrayEquals(feuille, index.resolve("textures/Feuille%20Chene.png"))
        assertArrayEquals(ecorce, index.resolve("..\\img\\ecorce.jpg"))
    }

    @Test
    fun casseIgnoreeEnRepli() {
        assertArrayEquals(feuille, index.resolve("FEUILLE CHENE.PNG"))
        assertArrayEquals(ecorce, index.resolve("Ecorce.JPG"))
    }

    @Test
    fun cleCanoniqueIdentiquePourToutesLesOrthographes() {
        // Trois orthographes du même fichier → la même clé (celle de l'entrée zip).
        assertEquals("Feuille Chene.png", index.canonicalKey("Feuille Chene.png"))
        assertEquals("Feuille Chene.png", index.canonicalKey("textures/Feuille%20Chene.png"))
        assertEquals("Feuille Chene.png", index.canonicalKey("C:\\TEX\\FEUILLE CHENE.PNG"))
        assertEquals("ecorce.jpg", index.canonicalKey("Ecorce.JPG"))
        assertNull(index.canonicalKey("tronc.png"))
    }

    @Test
    fun inconnuEtDataUri() {
        assertNull(index.resolve("tronc.png"))
        // Les `data:` URI sont décodées par gltfio lui-même.
        assertNull(index.resolve("data:image/png;base64,AAAA"))
        assertNull(GlbImageIndex(emptyMap()).resolve("Feuille Chene.png"))
    }

    @Test
    fun percentDecodeNeTouchePasAuPlusNiAuxSequencesInvalides() {
        assertEquals("a+b.png", GlbImageIndex.percentDecode("a+b.png"))
        assertEquals("100%.png", GlbImageIndex.percentDecode("100%.png"))
        assertEquals("é.png", GlbImageIndex.percentDecode("%C3%A9.png"))
        assertEquals("x%ZZ", GlbImageIndex.percentDecode("x%ZZ"))
    }
}
