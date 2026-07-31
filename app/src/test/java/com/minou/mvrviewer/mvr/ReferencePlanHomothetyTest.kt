package com.minou.mvrviewer.mvr

import com.minou.mvrviewer.sync.AuditCoding
import com.minou.mvrviewer.sync.LocalMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOMOTHÉTIE — le réglage ajouté au plan de repère (image/PDF comme DXF).
 *
 * Ce qui est verrouillé ici :
 *  1. la NON-RÉGRESSION du chemin DXF : sans homothétie, `effScale` vaut `scale`
 *     au bit près, et les chaînes de synchro/audit sont inchangées ;
 *  2. l'application PROPORTIONNELLE (même facteur sur les deux axes, jamais de
 *     déformation) via `worldXY` ;
 *  3. le contrat cloud : le DTO et la chaîne d'audit à SIX champs transportent le
 *     PRODUIT `scale × homothety` — aucun 7e champ, donc `decodeTransform`
 *     (indexé f[0..5]) continue de fonctionner.
 */
class ReferencePlanHomothetyTest {

    @Test fun defautNeChangeRienAuDxf() {
        val t = ReferencePlanTransform(offsetX = 120.0, offsetY = -40.0, rotationDeg = 30.0, scale = 2.5)
        assertEquals(1.0, t.homothety, 0.0)
        // Égalité EXACTE attendue (× 1.0 est neutre en IEEE 754) : c'est ce qui
        // garantit qu'un projet DXF existant se replace au pixel près.
        assertTrue(t.scale == t.effScale)
        assertEquals("120.0,-40.0,30.0,2.5,0.0,true", AuditCoding.encodeTransform(t))
        assertEquals(2.5, LocalMapper.fromTransform(t).scale, 0.0)
    }

    @Test fun produitApplique() {
        val t = ReferencePlanTransform(scale = 2.0, homothety = 3.0)
        assertEquals(6.0, t.effScale, 1e-12)
        // Pas de rotation ni de décalage : le point local est simplement multiplié.
        val (x, y) = t.worldXY(10f, -20f)
        assertEquals(60f, x, 1e-3f)
        assertEquals(-120f, y, 1e-3f)
    }

    @Test fun homothetieProportionnelleSansDeformation() {
        val t = ReferencePlanTransform(homothety = 0.25)
        val (x1, y1) = t.worldXY(400f, 400f)
        assertEquals(100f, x1, 1e-3f)
        assertEquals(100f, y1, 1e-3f)
        // Le RAPPORT largeur/hauteur d'un rectangle local est conservé.
        val (wx, _) = t.worldXY(800f, 0f)
        val (_, hy) = t.worldXY(0f, 200f)
        assertEquals(4.0f, wx / hy, 1e-4f)
    }

    @Test fun copieConserveLHomothetie() {
        val c = ReferencePlanTransform(scale = 1.5, homothety = 0.4, visible = false).copy()
        assertEquals(0.4, c.homothety, 0.0)
        assertEquals(1.5, c.scale, 0.0)
        assertEquals(false, c.visible)
    }

    @Test fun contratCloudSixChampsAvecProduit() {
        val t = ReferencePlanTransform(scale = 2.0, homothety = 4.0)
        val raw = AuditCoding.encodeTransform(t)
        assertEquals(6, raw.split(",").size)
        val back = AuditCoding.decodeTransform(raw)!!
        // Le poste distant reçoit la BONNE taille ; seule la factorisation est perdue.
        assertEquals(8.0, back.scale, 1e-12)
        assertEquals(8.0, back.effScale, 1e-12)
        assertEquals(8.0, LocalMapper.fromTransform(t).scale, 1e-12)
    }

    @Test fun bornesEtFormatageDuReglage() {
        // Curseur : log10 borné à [−1, +1] (×0,1 → ×10) même si le champ va plus loin.
        assertEquals(0f, com.minou.mvrviewer.ui.homothetySlider(1.0), 1e-6f)
        assertEquals(-1f, com.minou.mvrviewer.ui.homothetySlider(0.1), 1e-6f)
        assertEquals(1f, com.minou.mvrviewer.ui.homothetySlider(10.0), 1e-6f)
        assertEquals(1f, com.minou.mvrviewer.ui.homothetySlider(20.0), 1e-6f)
        assertEquals(-1f, com.minou.mvrviewer.ui.homothetySlider(0.001), 1e-6f)
        assertEquals("1", com.minou.mvrviewer.ui.formatHomothety(1.0))
        assertEquals("0.85", com.minou.mvrviewer.ui.formatHomothety(0.85))
    }
}
