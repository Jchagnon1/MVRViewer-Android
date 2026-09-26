package com.minou.mvrviewer.mvr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Lecture des TEXTURES d'un `.3ds` : coordonnées UV (0x4140) et carte de
 * texture des matériaux (0xA200 → 0xA300 nom, 0xA354… échelle/décalage). C'est
 * ce qui fait qu'un arbre Vectorworks (deux plans croisés + PNG découpé) n'est
 * plus rendu en « croix grise ». Fichiers synthétiques construits en mémoire,
 * chunks little-endian (en-tête 6 octets : id u16 + longueur u32 incluse).
 */
class ThreeDSParserTest {

    // ---- Constructeur de chunks ----

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun u32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun f32(v: Float) = u32(v.toRawBits())
    private fun cstr(s: String) = s.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }
    private fun chunk(id: Int, vararg body: ByteArray): ByteArray {
        val payload = cat(*body)
        return cat(u16(id), u32(6 + payload.size), payload)
    }

    // Quatre sommets (un quad vertical : sol z=0, sommet z=10) + 2 triangles.
    private val vertexList = chunk(
        0x4110, u16(4),
        f32(0f), f32(0f), f32(0f),
        f32(1f), f32(0f), f32(0f),
        f32(1f), f32(0f), f32(10f),
        f32(0f), f32(0f), f32(10f)
    )
    // UV : origine 3DS en bas à gauche → le sol a v=0, le sommet v=1.
    private val uvList = chunk(
        0x4140, u16(4),
        f32(0f), f32(0f),
        f32(1f), f32(0f),
        f32(1f), f32(1f),
        f32(0f), f32(1f)
    )
    private val faceList = chunk(
        0x4120, u16(2),
        u16(0), u16(1), u16(2), u16(0),
        u16(0), u16(2), u16(3), u16(0),
        chunk(0x4130, cstr("M"), u16(2), u16(0), u16(1))
    )
    private val materialPlain = chunk(
        0xAFFF,
        chunk(0xA000, cstr("M")),
        chunk(0xA020, chunk(0x0010, f32(1f), f32(0.5f), f32(0f)))
    )
    private val materialTextured = chunk(
        0xAFFF,
        chunk(0xA000, cstr("M")),
        chunk(0xA020, chunk(0x0010, f32(1f), f32(0.5f), f32(0f))),
        chunk(0xA200, chunk(0xA300, cstr("tex.png")), chunk(0xA354, f32(2f)))
    )

    private fun file(material: ByteArray, vararg trimesh: ByteArray) = chunk(
        0x4D4D,
        chunk(0x3D3D, material, chunk(0x4000, cstr("o"), chunk(0x4100, *trimesh)))
    )

    private val textured = file(materialTextured, vertexList, uvList, faceList)
    private val plain = file(materialPlain, vertexList, faceList)

    @Test
    fun uvEtTextureLus() {
        val meshes = ThreeDSParser.parse(textured)
        assertEquals(1, meshes.size)
        val m = meshes[0]
        assertEquals("o", m.name)
        assertEquals(4, m.vertexCount)
        assertEquals(2, m.triangleCount)
        val uvs = m.uvs
        assertNotNull(uvs)
        assertEquals(8, uvs!!.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), uvs, 0f)
        assertEquals(ThreeDSParser.TextureMap("tex.png", uScale = 2f), m.materialTextures["M"])
        assertEquals(ThreeDSParser.Rgb(1f, 0.5f, 0f), m.materials["M"])
        assertEquals(1, m.materialGroups.size)
        assertArrayEquals(intArrayOf(0, 1), m.materialGroups[0].triangles)
    }

    @Test
    fun sansUvNiTextureCommeAvant() {
        val meshes = ThreeDSParser.parse(plain)
        assertEquals(1, meshes.size)
        val m = meshes[0]
        assertNull(m.uvs)
        assertTrue(m.materialTextures.isEmpty())
        // Sommets / faces / couleurs identiques au fichier texturé.
        val t = ThreeDSParser.parse(textured)[0]
        assertArrayEquals(t.vertices, m.vertices, 0f)
        assertArrayEquals(t.faceIndices, m.faceIndices)
        assertEquals(t.materials, m.materials)
        assertEquals("M", m.materialGroups[0].name)
        assertArrayEquals(intArrayOf(0, 1), m.materialGroups[0].triangles)
    }

    @Test
    fun echelleDecalageParDefautSansSousChunks() {
        val mat = chunk(
            0xAFFF,
            chunk(0xA000, cstr("M")),
            chunk(0xA200, chunk(0xA300, cstr("00011.png")), u16(0xA351), u32(8), u16(0))
        )
        val m = ThreeDSParser.parse(file(mat, vertexList, uvList, faceList))[0]
        assertEquals(ThreeDSParser.TextureMap("00011.png", 1f, 1f, 0f, 0f), m.materialTextures["M"])
        // Sans 0xA020, la couleur de repli reste celle d'avant.
        assertEquals(ThreeDSParser.Rgb(0.8f, 0.8f, 0.8f), m.materials["M"])
    }

    @Test
    fun fichierTronqueDansLesUvNeLevePasDException() {
        // Coupe au milieu du chunk 0x4140 (l'en-tête 0x4140 est passé, les
        // flottants non) : aucune exception, et tout mesh éventuel a des UV
        // cohérents (null ou pas plus de 2 par sommet).
        val uvStart = indexOf(textured, u16(0x4140))
        assertTrue(uvStart > 0)
        for (cut in listOf(uvStart + 3, uvStart + 8, uvStart + 13, uvStart + 25)) {
            val meshes = ThreeDSParser.parse(textured.copyOf(cut))
            for (m in meshes) {
                val uv = m.uvs
                assertTrue(uv == null || (uv.size % 2 == 0 && uv.size <= 2 * m.vertexCount))
            }
        }
    }

    @Test
    fun compteurUvSurestimeBorneParLeChunk() {
        // Le chunk 0x4140 annonce 100 UV mais n'en contient que 4 (longueur de
        // chunk cohérente) : on lit ce qui est là, sans sortir du chunk.
        val overclaim = chunk(
            0x4140, u16(100),
            f32(0f), f32(0f), f32(1f), f32(0f), f32(1f), f32(1f), f32(0f), f32(1f)
        )
        val meshes = ThreeDSParser.parse(file(materialTextured, vertexList, overclaim, faceList))
        assertEquals(1, meshes.size)
        val uvs = meshes[0].uvs
        assertNotNull(uvs)
        assertEquals(8, uvs!!.size)
        assertEquals(4, meshes[0].vertexCount)
        // Un chunk 0x4140 vide (compteur seul) → pas d'UV du tout.
        val empty = chunk(0x4140, u16(3))
        assertNull(ThreeDSParser.parse(file(materialTextured, vertexList, empty, faceList))[0].uvs)
    }

    @Test
    fun ordreLibreDesChunksEtNomDeTextureVide() {
        // UV AVANT les sommets, et carte de texture sans nom → aucune carte.
        val noName = chunk(
            0xAFFF, chunk(0xA000, cstr("M")),
            chunk(0xA200, chunk(0xA354, f32(2f)))
        )
        val m = ThreeDSParser.parse(file(noName, uvList, vertexList, faceList))[0]
        assertEquals(8, m.uvs!!.size)
        assertTrue(m.materialTextures.isEmpty())
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
