package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Archives `.zip` de profils MA (SPEC-ma-gdtfshare.md §5.2) — règles EXACTES hors fichiers
 * d'essai : classement des entrées, taille déclarée MENSONGÈRE (décompression bornée →
 * 65 Mo), entrée chiffrée, garde-fou 50 000 entrées, imbriquée vide, chemins hostiles
 * (jamais écrits sur disque : seul le nom de base sert).
 */
class MaZipImportTest {

    private fun res(path: String) = File(javaClass.classLoader!!.getResource("ma/$path")!!.toURI())

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun u16(d: ByteArray, i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
    private fun put32(d: ByteArray, i: Int, v: Long) { for (k in 0..3) d[i + k] = ((v shr (8 * k)) and 0xFF).toByte() }

    /** Positions des en-têtes CENTRAUX (signature PK\1\2). */
    private fun centralHeaders(d: ByteArray): List<Int> = (0 until d.size - 4).filter {
        d[it] == 0x50.toByte() && d[it + 1] == 0x4B.toByte() && d[it + 2] == 0x01.toByte() && d[it + 3] == 0x02.toByte()
    }

    private val dimmer by lazy { res("synthetic/ma2_dimmer.xml").readBytes() }

    @Test fun classification() {
        assertEquals(MaZipImport.Kind.SILENT, MaZipImport.classify("real/", 0))
        assertEquals(MaZipImport.Kind.SILENT, MaZipImport.classify("__MACOSX/real/._x.xml", 0))
        assertEquals(MaZipImport.Kind.SILENT, MaZipImport.classify("a/__MACOSX/b.xml", 0))
        assertEquals(MaZipImport.Kind.SILENT, MaZipImport.classify("synthetic/.DS_Store", 0))
        assertEquals(MaZipImport.Kind.SILENT, MaZipImport.classify("dir\\.hidden.xml", 0))
        assertEquals(MaZipImport.Kind.CANDIDATE, MaZipImport.classify("A/B.XMLP", 1))
        assertEquals(MaZipImport.Kind.CANDIDATE, MaZipImport.classify("p.pxml", 0))
        assertEquals(MaZipImport.Kind.NESTED, MaZipImport.classify("nested/inner.ZIP", 0))
        assertEquals(MaZipImport.Kind.IGNORED, MaZipImport.classify("nested/deeper.zip", 1))
        assertEquals(MaZipImport.Kind.IGNORED, MaZipImport.classify("readme.txt", 0))
        assertEquals("b.xml", MaZipImport.baseName("dir\\sub\\b.xml"))
    }

    /** Taille déclarée petite mais flux énorme : décompression arrêtée à 64 Mo + 1 → « 65 Mo ». */
    @Test fun lyingDeclaredSizeStopsAt64MegabytesPlusOne() {
        val d = res("zips/huge_entry.zip").readBytes()
        val cd = centralHeaders(d).single()
        put32(d, cd + 24, 100)                                  // taille décompressée DÉCLARÉE : 100 octets
        val plan = MaZipImport.plan("menteur.zip", ZipCentralReader.ByteSource(d))
        val c = plan.candidates.single()
        assertEquals(100L, c.declaredSize)
        assertEquals("menteur.zip › huge.xml", c.displayName)
        assertEquals(MaZipImport.EntryRead.TooLarge(65), plan.session!!.read(c))
        // L'usage historique (lecture stricte) refuse dès que le flux dépasse sa taille.
        val l = ZipCentralReader.list(d) as ZipCentralReader.Listing.Ok
        assertNull(ZipCentralReader.read(d, l, l.entries.single()))
        assertEquals(66, MaZipImport.tooLargeMegabytes(65L * 1048576 + 1))
    }

    /** Entrée chiffrée (bit 0) : « illisible », les autres entrées restent lues. */
    @Test fun encryptedEntryIsUnreadableOthersStillRead() {
        val d = zip("a.xml" to dimmer, "b.xml" to res("synthetic/ma2_break2.xml").readBytes())
        val cd = centralHeaders(d).first()
        d[cd + 8] = (d[cd + 8].toInt() or 1).toByte()
        val local = ((d[cd + 42].toInt() and 0xFF) or ((d[cd + 43].toInt() and 0xFF) shl 8))
        d[local + 6] = (d[local + 6].toInt() or 1).toByte()
        val plan = MaZipImport.plan("c.zip", ZipCentralReader.ByteSource(d))
        assertEquals(2, plan.candidates.size)
        assertEquals(MaZipImport.EntryRead.Unreadable, plan.session!!.read(plan.candidates[0]))
        val ok = plan.session!!.read(plan.candidates[1]) as MaZipImport.EntryRead.Ok
        assertTrue(ok.bytes.contentEquals(res("synthetic/ma2_break2.xml").readBytes()))
        assertTrue(plan.errors.isEmpty())
    }

    /** Plus de 50 000 entrées : lecture ARRÊTÉE, `zipLimit` avec le nombre d'entrées lues. */
    @Test fun entryLimitStopsReading() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.setLevel(0)
            for (i in 0..MaZipImport.MAX_ENTRIES) { z.putNextEntry(ZipEntry("f$i.txt")); z.closeEntry() }
        }
        val plan = MaZipImport.plan("big.zip", ZipCentralReader.ByteSource(out.toByteArray()))
        assertEquals(listOf("big.zip" to MaZipImport.ArchiveError.Limit(MaZipImport.MAX_ENTRIES)), plan.errors)
        assertEquals(MaZipImport.MAX_ENTRIES, plan.ignored)
        assertTrue(plan.candidates.isEmpty())
    }

    /** Imbriquée sans candidat → `zipEmpty` à SON nom ; l'archive, elle, a un candidat. */
    @Test fun emptyNestedArchiveAndHostilePaths() {
        val inner = zip("readme.txt" to "x".toByteArray())
        val d = zip("../../etc/evil.xml" to dimmer, "dir\\sub\\b.xml" to dimmer, "in/inner.zip" to inner)
        val plan = MaZipImport.plan("x.zip", ZipCentralReader.ByteSource(d))
        assertEquals(listOf("x.zip › in/inner.zip" to MaZipImport.ArchiveError.Empty), plan.errors)
        assertEquals(1, plan.ignored)
        assertEquals(listOf("x.zip › ../../etc/evil.xml", "x.zip › dir/sub/b.xml"), plan.candidates.map { it.displayName })
        // Seul le NOM DE BASE sert (nom du profil) : rien n'est écrit sous le chemin de l'entrée.
        assertEquals(listOf("evil.xml", "b.xml"), plan.candidates.map { it.baseName })
    }

    @Test fun notAZipAndTooLargeArchive() {
        assertEquals(listOf("t.zip" to MaZipImport.ArchiveError.Unreadable),
            MaZipImport.plan("t.zip", ZipCentralReader.ByteSource("pas un zip".toByteArray())).errors)
        val big = object : ZipCentralReader.Source {
            override val size = MaZipImport.MAX_ARCHIVE_BYTES + 1
            override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int) = false
        }
        assertEquals(listOf("g.zip" to MaZipImport.ArchiveError.TooLarge(513, 512)), MaZipImport.plan("g.zip", big).errors)
    }

    /** Le lecteur par positions (fichier) lit comme le lecteur en mémoire. */
    @Test fun fileSourceMatchesByteSource() {
        val f = res("zips/ma_profiles.zip")
        val a = ZipCentralReader.list(f.readBytes()) as ZipCentralReader.Listing.Ok
        ZipCentralReader.FileSource.open(f).use { src ->
            val b = ZipCentralReader.list(src) as ZipCentralReader.Listing.Ok
            assertEquals(a.entries.map { it.name }, b.entries.map { it.name })
            val e = b.entries.first { it.name.endsWith("ma2_dimmer.xml") }
            val r = ZipCentralReader.readBounded(src, b, e, MaZipImport.MAX_ENTRY_BYTES) as ZipCentralReader.Read.Ok
            assertTrue(r.bytes.contentEquals(dimmer))
        }
        assertEquals(0, u16(byteArrayOf(0, 0), 0))
    }
}
