package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrouille l'ARBITRAGE du tap de la vue plan entre étiquettes et projecteurs.
 *
 * Ce qui est protégé ici n'est pas une formule mais une propriété : le test
 * tactile ne consulte QUE ce que le dessin a enregistré. Trois régressions
 * successives sont venues de tests qui reconstruisaient la géométrie des
 * étiquettes avec des conditions différentes de celles du dessin — d'où des
 * pastilles visibles mais insensibles, et des zones sensibles invisibles.
 */
class LabelTapTargetTest {

    private fun box(key: String, x: Float, y: Float) = LabelHit(key, x, y, 60f, 20f)

    @Test
    fun `une pastille dessinee est touchable`() {
        val hits = listOf(box("A", 100f, 100f))
        assertEquals("A", labelTapTarget(hits, emptyList(), Offset(130f, 110f), null))
    }

    @Test
    fun `hors de toute pastille le tap va au projecteur`() {
        val hits = listOf(box("A", 100f, 100f))
        assertNull(labelTapTarget(hits, emptyList(), Offset(90f, 110f), null))
    }

    /** Une étiquette non dessinée n'entre pas dans le tampon : intouchable. */
    @Test
    fun `une etiquette non dessinee est intouchable`() {
        assertNull(labelTapTarget(emptyList(), emptyList(), Offset(130f, 110f), null))
    }

    /**
     * L'exception : la pastille recouvre le centre du projecteur, et pourtant
     * celui-ci reste sélectionnable — sans quoi l'utilisateur n'aurait aucun
     * moyen de le récupérer.
     */
    @Test
    fun `le disque du symbole l emporte sur la pastille`() {
        val hits = listOf(box("A", 100f, 100f))
        val syms = listOf(SymbolHit(130f, 110f, 7f))
        assertNull(labelTapTarget(hits, syms, Offset(132f, 112f), null))
        // Juste en dehors du disque, l'étiquette reprend la main.
        assertEquals("A", labelTapTarget(hits, syms, Offset(150f, 112f), null))
    }

    /** On doit toujours pouvoir DÉSACTIVER l'étiquette active, même chevauchée. */
    @Test
    fun `l etiquette active est prioritaire en cas de chevauchement`() {
        val hits = listOf(box("A", 100f, 100f), box("B", 110f, 105f))
        assertEquals("A", labelTapTarget(hits, emptyList(), Offset(130f, 110f), "A"))
        // Sans préférence, c'est la DERNIÈRE dessinée (donc celle du dessus).
        assertEquals("B", labelTapTarget(hits, emptyList(), Offset(130f, 110f), null))
    }

    /** Le disque prime même sur l'étiquette active : le projecteur reste visable. */
    @Test
    fun `le disque l emporte aussi sur l etiquette active`() {
        val hits = listOf(box("A", 100f, 100f))
        val syms = listOf(SymbolHit(130f, 110f, 7f))
        assertNull(labelTapTarget(hits, syms, Offset(130f, 110f), "A"))
    }
}
