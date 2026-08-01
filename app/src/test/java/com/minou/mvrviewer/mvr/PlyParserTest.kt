package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Parseur PLY : corps ASCII et binaire little-endian, propriétés inconnues sautées. */
class PlyParserTest {

    private val asciiTetra = """
        ply
        format ascii 1.0
        element vertex 4
        property float x
        property float y
        property float z
        element face 4
        property list uchar int vertex_indices
        end_header
        0 0 0
        1 0 0
        0 1 0
        0 0 1
        3 0 1 2
        3 0 1 3
        3 0 2 3
        3 1 2 3
    """.trimIndent()

    @Test fun tetraedreAscii() {
        val r = PlyParser.parse(asciiTetra.toByteArray(), 1000)
        assertEquals(4, r.triangles)
        assertEquals(4, r.meshes[0].vertexCount)
        assertEquals(1f, meshesMaxDimension(r.meshes), 1e-4f)
    }

    @Test fun couleursParSommetMoyennees() {
        val src = """
            ply
            format ascii 1.0
            element vertex 3
            property float x
            property float y
            property float z
            property uchar red
            property uchar green
            property uchar blue
            element face 1
            property list uchar int vertex_indices
            end_header
            0 0 0 255 0 0
            1 0 0 255 0 0
            0 1 0 255 0 0
            3 0 1 2
        """.trimIndent()
        val r = PlyParser.parse(src.toByteArray(), 1000)
        assertEquals(0xFFFF0000.toInt(), r.meshes[0].color)
    }

    @Test fun proprietesInconnuesSautees() {
        // « confidence » (float) doit être consommé, sinon les faces se décalent.
        val src = asciiTetra
            .replace("property float z", "property float z\nproperty float confidence")
            .replace("0 0 0\n", "0 0 0 0.9\n").replace("1 0 0\n", "1 0 0 0.9\n")
            .replace("0 1 0\n", "0 1 0 0.9\n").replace("0 0 1\n", "0 0 1 0.9\n")
        val r = PlyParser.parse(src.toByteArray(), 1000)
        assertEquals(4, r.triangles)
    }

    @Test fun binaireLittleEndian() {
        val out = ByteArrayOutputStream()
        out.write(
            ("ply\nformat binary_little_endian 1.0\nelement vertex 3\n" +
                "property float x\nproperty float y\nproperty float z\n" +
                "element face 1\nproperty list uchar int vertex_indices\nend_header\n").toByteArray()
        )
        fun u32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        for (v in listOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f)) u32(v.toRawBits())
        out.write(3); u32(0); u32(1); u32(2)
        val r = PlyParser.parse(out.toByteArray(), 1000)
        assertEquals(1, r.triangles)
        assertEquals(2f, meshesMaxDimension(r.meshes), 1e-4f)
    }

    @Test fun formatNonGereEtEnTeteAberranteNeJettentPas() {
        assertTrue(PlyParser.parse("ply\nformat binary_big_endian 1.0\nend_header\n".toByteArray(), 10).meshes.isEmpty())
        assertTrue(PlyParser.parse(ByteArray(0), 10).meshes.isEmpty())
        assertTrue(PlyParser.parse("pas du ply du tout".toByteArray(), 10).meshes.isEmpty())
    }

    @Test fun plafondTronque() {
        val r = PlyParser.parse(asciiTetra.toByteArray(), 2)
        assertEquals(2, r.triangles)
        assertTrue(r.truncated)
    }
}
