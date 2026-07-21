package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le modèle « n blocs par projecteur » : ce qui est mis en page
 * ensemble, ce qui est détaché, l'identité des décalages, et la RIGIDITÉ d'un
 * déplacement de groupe.
 *
 * Ces propriétés-là ne se voient pas à l'œil sur un plan chargé (des centaines
 * d'étiquettes), mais une régression sur l'une d'elles se paie très cher : des
 * décalages orphelins, c'est tout le placement manuel du plan perdu.
 */
class PlanLabelBlocksTest {

    private fun fix(key: String, id: String?, addr: String, spec: String?, layer: String) =
        PlanFixture(
            key = key, px = 0f, py = 0f, id = id, name = "Proj $key",
            spec = spec, layer = layer, addr = addr, mode = "Mode1",
            world = dev.romainguy.kotlin.math.Mat4()
        )

    @Test
    fun `les champs groupes tiennent sur des lignes distinctes`() {
        val f = fix("a", "12", "1,1", "Sharpy", "Pont 1")
        val blocks = labelBlocks(f, setOf(LabelContent.ID, LabelContent.DMX), emptySet())
        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].lines.size)
        // Le saut de ligne EST la demande : moins de largeur, moins de chevauchement.
        assertTrue(blocks[0].text.contains("\n"))
        assertEquals("#12", blocks[0].lines[0])
    }

    @Test
    fun `un champ detache devient un bloc autonome`() {
        val f = fix("a", "12", "1,1", "Sharpy", "Pont 1")
        val blocks = labelBlocks(
            f, setOf(LabelContent.ID, LabelContent.DMX), setOf(LabelContent.DMX)
        )
        assertEquals(2, blocks.size)
        assertEquals(LABEL_GROUP_BLOCK, blocks[0].id)
        assertEquals(LabelContent.DMX.name, blocks[1].id)
        // Deux blocs = deux décalages indépendants.
        assertTrue(labelBlockKey(f.key, blocks[0].id) != labelBlockKey(f.key, blocks[1].id))
    }

    /** Un champ vide ne doit pas produire de pastille — donc pas de zone sensible. */
    @Test
    fun `un champ sans contenu ne produit pas de bloc`() {
        val f = fix("a", null, "", "Sharpy", "Pont 1")
        assertTrue(labelBlocks(f, setOf(LabelContent.ID, LabelContent.DMX), setOf(LabelContent.DMX)).isEmpty())
    }

    @Test
    fun `la cle de bloc retrouve toujours son projecteur`() {
        val key = "layer/objet#3"
        val bk = labelBlockKey(key, LabelContent.DMX.name)
        assertEquals(key, labelBlockFixtureKey(bk))
        assertEquals(LabelContent.DMX.name, labelBlockId(bk))
    }

    /** Les décalages d'avant les blocs se rattachent à la pastille groupée. */
    @Test
    fun `les cles heritees sont migrees vers le bloc groupe`() {
        assertEquals(labelBlockKey("a", LABEL_GROUP_BLOCK), migrateLegacyLabelKey("a"))
        val already = labelBlockKey("a", LabelContent.ID.name)
        assertEquals(already, migrateLegacyLabelKey(already))
    }

    @Test
    fun `taper une etiquette d une multi-selection arme tout le groupe`() {
        val data = PlanData(
            listOf(
                fix("a", "1", "1,1", "Sharpy", "Pont 1"),
                fix("b", "2", "1,20", "Sharpy", "Pont 1"),
                fix("c", "3", "1,40", "Wash", "Pont 2")
            ),
            emptyList(), emptyList(), 0f, 0f, 1f, 1f
        )
        val hit = labelBlockKey("a", LABEL_GROUP_BLOCK)
        val keys = labelGroupForTap(data, listOf(0, 1), hit)
        assertEquals(
            setOf(labelBlockKey("a", LABEL_GROUP_BLOCK), labelBlockKey("b", LABEL_GROUP_BLOCK)),
            keys
        )
        // Étiquette hors sélection : on ne s'empare pas de la sélection d'autrui.
        assertEquals(
            setOf(labelBlockKey("c", LABEL_GROUP_BLOCK)),
            labelGroupForTap(data, listOf(0, 1), labelBlockKey("c", LABEL_GROUP_BLOCK))
        )
    }

    @Test
    fun `meme type et meme calque seulement`() {
        val data = PlanData(
            listOf(
                fix("a", "1", "1,1", "Sharpy", "Pont 1"),
                fix("b", "2", "1,20", "Sharpy", "Pont 1"),
                fix("c", "3", "1,40", "Sharpy", "Pont 2"),
                fix("d", "4", "1,60", "Wash", "Pont 1")
            ),
            emptyList(), emptyList(), 0f, 0f, 1f, 1f
        )
        assertEquals(listOf("a", "b"), sameTypeSameLayer(data, "a").map { it.key })
    }

    /**
     * Cœur du déplacement groupé : le vecteur commun est réduit à ce que le bloc
     * le PLUS CONTRAINT autorise, sinon le groupe se déforme au lieu de glisser.
     */
    @Test
    fun `le deplacement groupe reste rigide au bord`() {
        val keys = listOf("k1", "k2")
        val shift = mapOf("k1" to Offset(0f, 0f), "k2" to Offset(90f, 0f))
        val d = clampGroupDelta(Offset(30f, 0f), keys, shift, 100f)
        assertEquals(10f, d.x, 1e-4f)
        assertEquals(0f, d.y, 1e-4f)
        // Sans contrainte atteinte, le vecteur passe tel quel.
        assertEquals(
            Offset(5f, -7f),
            clampGroupDelta(Offset(5f, -7f), keys, shift, 100f)
        )
    }
}
