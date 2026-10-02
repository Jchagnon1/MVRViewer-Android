package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objet 3D d'un type MA (SPEC-ma.md §4.4.5) : refus au-delà de 500 000 triangles SANS
 * décompte inventé.
 */
class Fixture3DModelTest {

    private fun objWithFaces(faces: Int): ByteArray {
        val sb = StringBuilder(faces * 8 + 64)
        sb.append("v 0 0 0\nv 1 0 0\nv 0 1 0\n")
        repeat(faces) { sb.append("f 1 2 3\n") }
        return sb.toString().toByteArray()
    }

    /**
     * RÉGRESSION : le lecteur s'arrête au plafond (500 001) ; le refus annonçait alors
     * « 500001 triangles (maximum 500000) » pour un fichier qui en compte bien plus — comme
     * s'il suffisait d'en retirer un. Le décompte tronqué est désormais « au moins ».
     */
    @Test fun truncatedCountIsReportedAsMoreThanTheMaximum() {
        val r = Fixture3DModel.decode(objWithFaces(Fixture3DModel.MAX_TRIANGLES + 50_000), "obj")
        val heavy = r as Fixture3DModel.DecodeResult.TooHeavy
        assertTrue("décompte tronqué = « plus de »", heavy.atLeast)
        assertEquals(Fixture3DModel.MAX_TRIANGLES, heavy.triangles)   // « plus de 500000 », jamais 500001
    }

    @Test fun exactCountIsKeptWhenTheFileWasReadToTheEnd() {
        // Exactement MAX + 1 faces : le lecteur a tout lu, le décompte est exact.
        val r = Fixture3DModel.decode(objWithFaces(Fixture3DModel.MAX_TRIANGLES + 1), "obj")
        val heavy = r as Fixture3DModel.DecodeResult.TooHeavy
        assertFalse(heavy.atLeast)
        assertEquals(Fixture3DModel.MAX_TRIANGLES + 1, heavy.triangles)
        // Au plafond exact : accepté (avertissement « lourd » côté interface, pas un refus).
        assertTrue(Fixture3DModel.decode(objWithFaces(Fixture3DModel.MAX_TRIANGLES), "obj") is Fixture3DModel.DecodeResult.Ok)
    }

    @Test fun truncatedBinaryStlIsAlsoMoreThan() {
        // STL binaire de 600 000 triangles (le lecteur borne `count` au plafond).
        val n = 600_000
        val buf = java.nio.ByteBuffer.allocate(84 + 50 * n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.position(80); buf.putInt(n)
        for (i in 0 until n) {
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(1f)
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f)
            buf.putFloat(1f); buf.putFloat(0f); buf.putFloat(0f)
            buf.putFloat(0f); buf.putFloat(1f); buf.putFloat(0f)
            buf.putShort(0)
        }
        val heavy = Fixture3DModel.decode(buf.array(), "stl") as Fixture3DModel.DecodeResult.TooHeavy
        assertTrue(heavy.atLeast)
        assertEquals(Fixture3DModel.MAX_TRIANGLES, heavy.triangles)
    }
}
