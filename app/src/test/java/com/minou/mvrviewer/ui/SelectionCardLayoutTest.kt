package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forme de la fiche de sélection (SPEC-menus-android §3.9, tranche D ; iOS
 * `SelectionCardLayoutTests`) : compacte sous 480 dp de haut, budget du quart de
 * la hauteur, titre court, « projecteur gardé visible » (jamais sous la pastille),
 * fiche absente pendant l'assistant 3 points, l'affectation et la mesure. Unités :
 * dp (densité 1), comme les points iOS.
 */
class SelectionCardLayoutTest {

    // ------------------------------------------------------------------ style

    @Test fun compacteSousQuatreCentQuatreVingtsDp() {
        // Medium_Phone en paysage : 411 dp de haut → compacte.
        assertEquals(SelectionCardLayout.Style.COMPACT, SelectionCardLayout.style(411))
        assertEquals(SelectionCardLayout.Style.COMPACT, SelectionCardLayout.style(479))
        // Portrait et tablette : complète, inchangée.
        assertEquals(SelectionCardLayout.Style.REGULAR, SelectionCardLayout.style(480))
        assertEquals(SelectionCardLayout.Style.REGULAR, SelectionCardLayout.style(914))
        assertEquals(SelectionCardLayout.Style.REGULAR, SelectionCardLayout.style(1280))
        // Hauteur inconnue : rien ne change.
        assertEquals(SelectionCardLayout.Style.REGULAR, SelectionCardLayout.style(0))
    }

    @Test fun budgetDuQuartDeLaHauteur() {
        // Une rangée (≈ 56 dp) sur 411 dp de haut : bien sous le quart (102,75 dp).
        assertTrue(SelectionCardLayout.fitsHeightBudget(56f, 411f))
        assertTrue(SelectionCardLayout.fitsHeightBudget(102.75f, 411f))
        assertFalse(SelectionCardLayout.fitsHeightBudget(110f, 411f))
        assertFalse(SelectionCardLayout.fitsHeightBudget(50f, 0f))
        assertFalse(SelectionCardLayout.fitsHeightBudget(Float.NaN, 411f))
    }

    @Test fun titreCourt() {
        assertEquals("Wash 101", SelectionCardLayout.shortTitle("Projecteur — Wash 101"))
        assertEquals("iForte LTX", SelectionCardLayout.shortTitle("Fixture — iForte LTX"))
        // Le nom peut lui-même contenir un tiret : seul le premier séparateur compte.
        assertEquals("Wash — Face", SelectionCardLayout.shortTitle("Projecteur — Wash — Face"))
        assertEquals("Wash 101", SelectionCardLayout.shortTitle("Wash 101"))
        assertEquals("Projecteur — ", SelectionCardLayout.shortTitle("Projecteur — "))
    }

    @Test fun garderVisibleSeulementPourLaFicheCompacteDUnProjecteur() {
        val c = SelectionCardLayout.Style.COMPACT
        val r = SelectionCardLayout.Style.REGULAR
        assertTrue(SelectionCardLayout.revealsSelection(c, isMultiSelect = false, hasSelection = true))
        assertFalse(SelectionCardLayout.revealsSelection(r, isMultiSelect = false, hasSelection = true))
        assertFalse(SelectionCardLayout.revealsSelection(c, isMultiSelect = true, hasSelection = true))
        assertFalse(SelectionCardLayout.revealsSelection(c, isMultiSelect = false, hasSelection = false))
    }

    // ---------------------------------------------------- fiche ou bannière ou rien

    @Test fun ficheBanniereOuRien() {
        assertEquals(PlanSelectionBar.NONE, SelectionCardLayout.presentation(0))
        assertEquals(PlanSelectionBar.CARD, SelectionCardLayout.presentation(1))
        assertEquals(PlanSelectionBar.BANNER, SelectionCardLayout.presentation(2))
        // Pas de fiche pendant l'assistant 3 points, l'affectation, la mesure, l'aide
        // « je suis ici » (règle existante du plan, étendue à la mesure comme la 3D).
        for (n in 1..3) {
            assertEquals(PlanSelectionBar.NONE, SelectionCardLayout.presentation(n, threePointOpen = true))
            assertEquals(PlanSelectionBar.NONE, SelectionCardLayout.presentation(n, assignOpen = true))
            assertEquals(PlanSelectionBar.NONE, SelectionCardLayout.presentation(n, measuring = true))
            assertEquals(PlanSelectionBar.NONE, SelectionCardLayout.presentation(n, anchorAid = true))
        }
    }

    // --------------------------------------------- projecteur gardé visible (revealShift)

    /** Fiche compacte d'un téléphone en paysage (914 × 411 dp) : une rangée en bas. */
    private val card = Rect(300f, 339f, 614f, 395f)
    private val margin = REVEAL_MARGIN_DP
    /** Bas de la pastille « Vue » / de la rangée du haut. */
    private val pillBottom = 90f

    @Test fun projecteurSousLaFicheRemonteJusteAuDessus() {
        val p = Offset(450f, 370f)
        val dy = revealShift(p, card, pillBottom, margin)
        // Posé juste au-dessus de la fiche, à la marge près.
        assertEquals(card.top - margin - p.y, dy, 1e-4f)
        assertEquals(card.top - margin, p.y + dy, 1e-4f)
        assertTrue(dy < 0f)
    }

    @Test fun projecteurDansLaMargeAuDessusDeLaFicheRemonteAussi() {
        // À 10 dp au-dessus de la fiche (< 28 dp) : trop près, on le remonte.
        val p = Offset(450f, card.top - 10f)
        assertEquals(-(margin - 10f), revealShift(p, card, pillBottom, margin), 1e-4f)
    }

    @Test fun projecteurDejaVisibleNeBougePas() {
        assertEquals(0f, revealShift(Offset(450f, 200f), card, pillBottom, margin), 0f)
        // À côté de la fiche (elle est étroite en paysage) : visible, rien ne bouge.
        assertEquals(0f, revealShift(Offset(100f, 370f), card, pillBottom, margin), 0f)
        assertEquals(0f, revealShift(Offset(700f, 370f), card, pillBottom, margin), 0f)
    }

    @Test fun jamaisSousLaPastille() {
        // Fiche dépliée (« Détails ») haute : la place au-dessus d'elle passerait sous
        // la pastille → rien ne bouge.
        val tall = Rect(300f, 100f, 614f, 395f)
        assertEquals(0f, revealShift(Offset(450f, 300f), tall, pillBottom, margin), 0f)
        // Juste assez de place : la cible est pile au bas de la pastille.
        val fits = Rect(300f, pillBottom + margin, 614f, 395f)
        assertEquals(pillBottom - 300f, revealShift(Offset(450f, 300f), fits, pillBottom, margin), 1e-4f)
        // Sans borne mesurée (∞) : on remonte quand même.
        assertTrue(revealShift(Offset(450f, 370f), card, Float.NEGATIVE_INFINITY, margin) < 0f)
    }

    @Test fun cadresInvalidesNeBougentRien() {
        assertEquals(0f, revealShift(Offset(450f, 370f), Rect.Zero, pillBottom, margin), 0f)
        assertEquals(0f, revealShift(Offset(Float.NaN, 370f), card, pillBottom, margin), 0f)
        assertEquals(0f, revealShift(Offset(450f, 370f), Rect(300f, Float.NaN, 614f, 395f), pillBottom, margin), 0f)
    }

    @Test fun barreDEchelleDuPlanAuDessusDeLaFiche() {
        // Portrait (px) : fiche pleine largeur dont le haut est à 1738 ; la barre
        // (place d'origine h − 172 = 2000) remonte juste au-dessus d'elle.
        val full = Rect(42f, 1738f, 1038f, 2295f)
        assertEquals(1738f - 42f, SelectionCardLayout.scaleBarY(2000f, 20f, 414f, full, 42f), 0f)
        // Fiche compacte centrée (paysage) : pas dans la colonne de la barre.
        val centered = Rect(820f, 830f, 1580f, 975f)
        assertEquals(870f, SelectionCardLayout.scaleBarY(870f, 20f, 414f, centered, 42f), 0f)
        // Pas de fiche, ou fiche plus bas que la barre : place d'origine.
        assertEquals(2000f, SelectionCardLayout.scaleBarY(2000f, 20f, 414f, null, 42f), 0f)
        assertEquals(2000f, SelectionCardLayout.scaleBarY(2000f, 20f, 414f, Rect(42f, 2100f, 1038f, 2295f), 42f), 0f)
        assertEquals(2000f, SelectionCardLayout.scaleBarY(2000f, 20f, 414f, Rect.Zero, 42f), 0f)
    }

    @Test fun laPileBasDroiteMonteAuDessusDeLaFiche() {
        // Portrait (411 × 914 dp) : fiche complète pleine largeur en bas ; la pile
        // Rechercher / Recadrer (bas-droite) monte juste au-dessus d'elle.
        val regular = Rect(16f, 730f, 395f, 898f)
        val stack = Rect(351f, 794f, 403f, 898f)
        val lift = PanelPlacementMath.clearanceLift(stack, listOf(regular), topLimit = 56f)
        assertEquals(stack.bottom - (regular.top - PanelPlacementMath.CORNER_STACK_GAP), lift, 1e-4f)
    }
}
