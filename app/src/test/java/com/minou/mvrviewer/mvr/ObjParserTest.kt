package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parseur OBJ — ce qui compte vraiment : la triangulation des n-gones, les
 * indices NÉGATIFS (relatifs à la fin, un piège classique) et surtout le GARDE
 * D'INDICES : un indice hors du tableau de sommets fait lire le GPU hors du
 * VertexBuffer (abort natif), c'est le précédent documenté du `.3ds`.
 */
class ObjParserTest {

    private fun parse(src: String, max: Int = 100_000) =
        ObjParser.parse(src.byteInputStream(Charsets.UTF_8), max)

    /** Cube unitaire, faces QUADRANGULAIRES (6 quads = 12 triangles). */
    private val cube = """
        # cube
        v 0 0 0
        v 1 0 0
        v 1 1 0
        v 0 1 0
        v 0 0 1
        v 1 0 1
        v 1 1 1
        v 0 1 1
        f 1 2 3 4
        f 5 6 7 8
        f 1 2 6 5
        f 2 3 7 6
        f 3 4 8 7
        f 4 1 5 8
    """.trimIndent()

    @Test fun cubeQuadsTriangules() {
        val r = parse(cube)
        assertEquals(1, r.meshes.size)
        // 6 quads → 6 × 2 triangles.
        assertEquals(12, r.triangles)
        assertEquals(12, r.meshes[0].triangleCount)
        assertEquals(8, r.meshes[0].vertexCount)
        assertTrue(!r.truncated)
    }

    @Test fun normalesCalculeesEtNormalisees() {
        val m = parse(cube).meshes[0]
        assertEquals(m.verts.size, m.normals.size)
        for (v in 0 until m.vertexCount) {
            val x = m.normals[v * 3]; val y = m.normals[v * 3 + 1]; val z = m.normals[v * 3 + 2]
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            assertEquals(1.0, len.toDouble(), 1e-3)
        }
    }

    @Test fun indicesNegatifsRelatifsALaFin() {
        // -1 = dernier sommet, -2 = avant-dernier, -3 = antépénultième.
        val r = parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf -3 -2 -1")
        assertEquals(1, r.triangles)
        val m = r.meshes[0]
        assertEquals(3, m.vertexCount)
        assertEquals(3, m.indices.size)
    }

    @Test fun ngoneTrianguleEnEventail() {
        // Pentagone → 3 triangles.
        val r = parse("v 0 0 0\nv 1 0 0\nv 2 1 0\nv 1 2 0\nv 0 2 0\nf 1 2 3 4 5")
        assertEquals(3, r.triangles)
    }

    @Test fun indiceHorsBornesEstJete() {
        // Le triangle qui pointe sur le sommet 99 (inexistant) doit disparaître —
        // sinon abort natif du GPU au dessin.
        val r = parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\nf 1 2 99")
        assertEquals(1, r.triangles)
    }

    @Test fun materiauxDonnentDesSousMaillages() {
        val src = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            v 0 0 1
            usemtl rouge
            f 1 2 3
            usemtl bleu
            f 1 2 4
        """.trimIndent()
        val r = parse(src)
        assertEquals(2, r.meshes.size)
        // Remappage : chaque sous-maillage ne porte QUE ses propres sommets.
        assertEquals(3, r.meshes[0].vertexCount)
        assertEquals(3, r.meshes[1].vertexCount)
    }

    @Test fun couleurDeMateriauAppliquee() {
        val mtl = ObjParser.parseMtl("newmtl rouge\nKd 1 0 0\n".byteInputStream())
        assertEquals(0xFFFF0000.toInt(), mtl["rouge"])
        val r = ObjParser.parse(
            "v 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl rouge\nf 1 2 3".byteInputStream(),
            1000, mtl
        )
        assertEquals(0xFFFF0000.toInt(), r.meshes[0].color)
    }

    @Test fun plafondDeTrianglesTronqueEtPrevient() {
        val sb = StringBuilder()
        for (i in 0 until 30) sb.append("v $i 0 0\nv $i 1 0\nv $i 0 1\n")
        for (i in 0 until 30) sb.append("f ${i * 3 + 1} ${i * 3 + 2} ${i * 3 + 3}\n")
        val r = parse(sb.toString(), max = 10)
        assertEquals(10, r.triangles)
        assertTrue(r.truncated)
    }

    @Test fun fichierVideOuIllisibleNeJettePas() {
        assertTrue(parse("").meshes.isEmpty())
        assertTrue(parse("bonjour\nceci n'est pas un obj\n").meshes.isEmpty())
        assertNotNull(parse("v a b c\nf 1 1 1"))
    }

    @Test fun triangleDegenereEcarte() {
        // Trois fois le même sommet : aucune surface, et une normale nulle.
        assertEquals(0, parse("v 0 0 0\nf 1 1 1").triangles)
    }
}
