package com.minou.mvrviewer.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille la LISIBILITÉ du texte des étiquettes de la vue plan.
 *
 * Le défaut d'origine : l'encre reprenait la couleur brute du calque, et les
 * teintes claires (jaune, cyan) disparaissaient sur le voile clair de la
 * pastille. La correction ne doit pas se contenter d'un seuil clair/sombre —
 * d'où les cas « voile moyen », qui sont exactement ceux où un seuil choisit le
 * mauvais côté.
 */
class LabelInkContrastTest {

    private val MIN = 4.5f

    /** Voile réellement composé pour un fond de plan clair (blanc à 78 %). */
    private val veilLight = Color(0.95f, 0.95f, 0.95f)
    /** Voile d'un fond sombre (noir à 45 % sur gris très foncé). */
    private val veilDark = Color(0.09f, 0.09f, 0.09f)

    private fun assertReadable(tint: Color, veil: Color) {
        val ink = readableInk(tint, veil)
        val r = contrastRatio(ink, veil)
        assertTrue("contraste $r < $MIN pour $tint sur $veil", r >= MIN)
    }

    @Test
    fun `les teintes claires restent lisibles sur voile clair`() {
        listOf(
            Color(1f, 1f, 0f),      // jaune
            Color(0f, 1f, 1f),      // cyan
            Color(0.6f, 1f, 0.6f),  // vert tendre
            Color.White
        ).forEach { assertReadable(it, veilLight) }
    }

    @Test
    fun `les teintes sombres restent lisibles sur voile sombre`() {
        listOf(
            Color(0f, 0f, 0.5f),    // bleu nuit
            Color(0.2f, 0f, 0f),    // bordeaux
            Color.Black
        ).forEach { assertReadable(it, veilDark) }
    }

    @Test
    fun `une teinte deja contrastee n est pas touchee`() {
        val tint = Color(0.1f, 0.1f, 0.6f)
        assertTrue(readableInk(tint, veilLight) == tint)
    }

    @Test
    fun `sur voile moyen on choisit le cote qui marche, pas le cote du seuil`() {
        // Gris à mi-chemin : le blanc contraste peu, le noir beaucoup. Un simple
        // seuil de luminosité peut désigner le mauvais côté ; ici on exige juste
        // que le résultat soit le MEILLEUR des deux, donc au-dessus du contraste
        // de la teinte brute.
        val veil = Color(0.55f, 0.55f, 0.55f)
        val tint = Color(0.5f, 0.5f, 0.5f)
        val ink = readableInk(tint, veil)
        assertTrue(
            "l'encre choisie doit mieux contraster que la teinte brute",
            contrastRatio(ink, veil) > contrastRatio(tint, veil)
        )
        // Sur ce voile, seul le noir peut atteindre le seuil (le blanc plafonne
        // sous 4,5:1) : la correction doit l'avoir trouvé.
        assertTrue(contrastRatio(ink, veil) >= MIN)
    }
}
