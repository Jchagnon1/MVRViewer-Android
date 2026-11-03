package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Parseur STL — binaire ET ASCII. Deux points sensibles :
 *  - la DÉTECTION : elle se fait sur la TAILLE (84 + 50 n), jamais sur le mot
 *    « solid », que des exporteurs binaires écrivent aussi en tête ;
 *  - la DÉDUPLICATION : un STL stocke 3 sommets par triangle, sans partage. Sans
 *    fusion, un cube de 12 triangles ferait 36 sommets.
 */
class StlParserTest {

    /** Cube [0,1]³ en 12 triangles (8 sommets distincts une fois dédupliqués). */
    private val cubeTris: List<FloatArray> = buildList {
        val p = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 1f, 1f)
        )
        val quads = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(4, 5, 6, 7), intArrayOf(0, 1, 5, 4),
            intArrayOf(1, 2, 6, 5), intArrayOf(2, 3, 7, 6), intArrayOf(3, 0, 4, 7)
        )
        for (q in quads) {
            add(floatArrayOf(*p[q[0]], *p[q[1]], *p[q[2]]))
            add(floatArrayOf(*p[q[0]], *p[q[2]], *p[q[3]]))
        }
    }

    private fun binary(tris: List<FloatArray>, header: String = "binaire"): ByteArray {
        val out = ByteArrayOutputStream()
        val h = ByteArray(80)
        header.toByteArray().copyInto(h, 0, 0, minOf(80, header.length))
        out.write(h)
        fun u32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun f32(v: Float) = u32(v.toRawBits())
        u32(tris.size)
        for (t in tris) {
            f32(0f); f32(0f); f32(1f)           // normale de face (ignorée au parse)
            for (k in 0 until 9) f32(t[k])
            out.write(0); out.write(0)          // attribut
        }
        return out.toByteArray()
    }

    private fun ascii(tris: List<FloatArray>): ByteArray {
        val sb = StringBuilder("solid cube\n")
        for (t in tris) {
            sb.append("  facet normal 0 0 1\n    outer loop\n")
            for (k in 0 until 3) sb.append("      vertex ${t[k * 3]} ${t[k * 3 + 1]} ${t[k * 3 + 2]}\n")
            sb.append("    endloop\n  endfacet\n")
        }
        sb.append("endsolid cube\n")
        return sb.toString().toByteArray()
    }

    @Test fun detectionParLaTaillePasParLeMotSolid() {
        // Un binaire dont l'en-tête commence par « solid » : la taille tranche.
        assertTrue(StlParser.isBinary(binary(cubeTris, header = "solid piege")))
        assertFalse(StlParser.isBinary(ascii(cubeTris)))
    }

    @Test fun cubeBinaire() {
        val r = StlParser.parse(binary(cubeTris), 100_000)
        assertEquals(12, r.triangles)
        assertFalse(r.truncated)
        // DÉDUPLICATION : 36 sommets bruts → 8 distincts.
        assertEquals(8, r.meshes[0].vertexCount)
    }

    @Test fun cubeAsciiDonneLesMemesBornes() {
        val b = StlParser.parse(binary(cubeTris), 100_000).meshes[0]
        val a = StlParser.parse(ascii(cubeTris), 100_000).meshes[0]
        assertEquals(b.triangleCount, a.triangleCount)
        assertEquals(b.vertexCount, a.vertexCount)
        assertEquals(meshesMaxDimension(listOf(b)), meshesMaxDimension(listOf(a)), 1e-4f)
        assertEquals(1f, meshesMaxDimension(listOf(a)), 1e-4f)
    }

    @Test fun plafondTronqueEtPrevient() {
        val rb = StlParser.parse(binary(cubeTris), 5)
        assertEquals(5, rb.triangles)
        assertTrue(rb.truncated)
        val ra = StlParser.parse(ascii(cubeTris), 5)
        assertEquals(5, ra.triangles)
        assertTrue(ra.truncated)
    }

    @Test fun fichierTronqueOuVideNeJettePas() {
        assertTrue(StlParser.parse(ByteArray(0), 100).meshes.isEmpty())
        // En-tête binaire annonçant 1000 triangles mais fichier coupé : la taille
        // ne colle plus → lu en ASCII, qui ne trouve rien. Aucune exception.
        val short = binary(cubeTris).copyOf(200)
        assertTrue(StlParser.parse(short, 100).meshes.size <= 1)
    }
}
