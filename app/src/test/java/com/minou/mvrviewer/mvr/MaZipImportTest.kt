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

    // ---- Garde-fou « 2 Gio décompressés par archive » (§5.2) ---------------------------------

    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
    private fun le32(o: ByteArrayOutputStream, v: Long) { for (k in 0..3) o.write(((v shr (8 * k)) and 0xFF).toInt()) }

    /** `n` octets nuls compressés (deflate brut), sans tout allouer d'un coup. */
    private fun deflatedZeros(n: Long): Pair<ByteArray, Long> {
        val def = java.util.zip.Deflater(9, true)
        val crc = java.util.zip.CRC32()
        val chunk = ByteArray(1 shl 20)
        val out = ByteArrayOutputStream(); val buf = ByteArray(1 shl 16)
        var left = n
        while (left > 0) {
            val k = minOf(left, chunk.size.toLong()).toInt()
            crc.update(chunk, 0, k)
            def.setInput(chunk, 0, k)
            while (!def.needsInput()) { val c = def.deflate(buf); out.write(buf, 0, c) }
            left -= k
        }
        def.finish()
        while (!def.finished()) { val c = def.deflate(buf); out.write(buf, 0, c) }
        def.end()
        return out.toByteArray() to crc.value
    }

    /**
     * Archive PIÉGÉE : UNE donnée (méthode `method`) référencée par `k` entrées centrales de même
     * nom, taille décompressée DÉCLARÉE `declared`, CRC `crc`. Quelques Ko pour `k` × la donnée.
     */
    private fun overlapping(name: String, k: Int, data: ByteArray, method: Int, crc: Long, declared: Long): ByteArray {
        val nb = name.toByteArray()
        val o = ByteArrayOutputStream()
        le32(o, 0x04034b50); le16(o, 20); le16(o, 0); le16(o, method); le16(o, 0); le16(o, 0)
        le32(o, crc); le32(o, data.size.toLong()); le32(o, declared); le16(o, nb.size); le16(o, 0); o.write(nb); o.write(data)
        val cdOff = o.size().toLong()
        repeat(k) {
            le32(o, 0x02014b50); le16(o, 20); le16(o, 20); le16(o, 0); le16(o, method); le16(o, 0); le16(o, 0)
            le32(o, crc); le32(o, data.size.toLong()); le32(o, declared); le16(o, nb.size); le16(o, 0); le16(o, 0)
            le16(o, 0); le16(o, 0); le32(o, 0); le32(o, 0); o.write(nb)
        }
        val cdSize = o.size() - cdOff
        le32(o, 0x06054b50); le16(o, 0); le16(o, 0); le16(o, k); le16(o, k); le32(o, cdSize); le32(o, cdOff); le16(o, 0)
        return o.toByteArray()
    }

    /**
     * Relecture (défaut 2) : 40 entrées de taille déclarée 100 octets qui décompressent chacune
     * 65 Mo (archive de ~68 Ko). Chaque entrée est rejetée « trop volumineuse » APRÈS 64 Mo
     * décompressés — ces octets comptent : la lecture s'ARRÊTE (`zipLimit`) vers 2 Gio, au lieu
     * de décompresser les 40 (et 50 000 dans une archive réelle). Plafonds RÉELS de la spec.
     */
    @Test fun rejectedEntriesCountTowardTheTwoGibibyteCap() {
        val mib = 1024L * 1024
        val (data, crc) = deflatedZeros(65 * mib)
        val zip = overlapping("n.xml", 40, data, 8, crc, 100)
        assertTrue(zip.size < 100_000)
        val plan = MaZipImport.plan("bombe.zip", ZipCentralReader.ByteSource(zip))
        assertEquals(40, plan.candidates.size)
        val session = plan.session!!
        val results = plan.candidates.map { session.read(it) }
        val tooLarge = results.count { it is MaZipImport.EntryRead.TooLarge }
        // 32 × (64 Mo + un tampon) dépassent 2 Gio : la 33ᵉ n'est plus lue.
        assertEquals(32, tooLarge)
        assertEquals(MaZipImport.EntryRead.Limit(32), results[32])
        assertTrue(results.drop(33).all { it == MaZipImport.EntryRead.Skipped })
        assertTrue(session.decompressed <= MaZipImport.MAX_TOTAL_BYTES + MaZipImport.MAX_ENTRY_BYTES + 64 * 1024)
        session.close()
    }

    /** Même garde-fou pour des entrées « illisibles » (CRC faux) après lecture complète. */
    @Test fun unreadableEntriesCountTowardTheCap() {
        val data = ByteArray(1 shl 20) { 7 }
        val zip = overlapping("s.xml", 20, data, 0, 0xDEADBEEFL, data.size.toLong())   // STORED, CRC faux
        val limits = MaZipImport.Limits(entryBytes = 1L shl 20, totalBytes = 4L shl 20)
        val plan = MaZipImport.plan("crc.zip", ZipCentralReader.ByteSource(zip), limits = limits)
        val results = plan.candidates.map { plan.session!!.read(it) }
        assertEquals(List(4) { MaZipImport.EntryRead.Unreadable }, results.take(4))
        assertEquals(MaZipImport.EntryRead.Limit(4), results[4])
        assertTrue(results.drop(5).all { it == MaZipImport.EntryRead.Skipped })
    }

    /**
     * Archives IMBRIQUÉES rejetées (décompressées puis trop grosses) : comptées aussi dans
     * plan(), qui s'arrête au plafond au lieu de toutes les lire (plafonds réduits).
     */
    @Test fun rejectedNestedArchivesCountTowardTheCapInPlan() {
        val (data, crc) = deflatedZeros(3L shl 20)                      // 3 Mo > plafond d'entrée (1 Mo)
        val zip = overlapping("n.zip", 40, data, 8, crc, 100)
        val limits = MaZipImport.Limits(entryBytes = 1L shl 20, totalBytes = 8L shl 20)
        val plan = MaZipImport.plan("bombe2.zip", ZipCentralReader.ByteSource(zip), limits = limits)
        val tooLarge = plan.errors.count { it.second is MaZipImport.ArchiveError.TooLarge }
        assertTrue("imbriquées lues : $tooLarge", tooLarge in 7..8)
        assertEquals("bombe2.zip" to MaZipImport.ArchiveError.Limit(tooLarge + 1), plan.errors.last())
        assertNull(plan.session)
    }

    /** « Arrêter » pendant la préparation : plan() s'arrête avant l'imbriquée suivante. */
    @Test fun planStopsWhenCancelled() {
        val (data, crc) = deflatedZeros(3L shl 20)
        val zip = overlapping("n.zip", 40, data, 8, crc, 100)
        var asked = 0
        var closed = false
        val src = object : ZipCentralReader.Source, java.io.Closeable {
            val inner = ZipCentralReader.ByteSource(zip)
            override val size get() = inner.size
            override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int) = inner.readAt(pos, dst, off, len)
            override fun close() { closed = true }
        }
        val plan = MaZipImport.plan("arret.zip", src, isCancelled = { ++asked > 3 },
            limits = MaZipImport.Limits(entryBytes = 1L shl 20))
        assertTrue(plan.cancelled)
        assertEquals(4, asked)                                          // 3 imbriquées lues, puis arrêt
        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.errors.isEmpty())
        assertNull(plan.session)
        assertTrue(closed)
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

    // ---- Écarts relevés par la relecture du web (SPEC-ma-gdtfshare.md §5.2, « à aligner ») ----

    /**
     * Noms des entrées : drapeau UTF-8 (bit 11) → UTF-8 ; SANS lui → UTF-8 s'il est VALIDE
     * (« Compresser » du Finder, `zip` d'Info-ZIP sur macOS), sinon CP437 — comme le web et
     * l'oracle. Avant : Latin-1 sans drapeau, « Ã‰clairage@Dimmer@RÃ©mi.xml ».
     */
    @Test fun entryNamesUtf8WhenValidElseCp437() {
        val l = ZipCentralReader.list(res("zips/accented_names.zip").readBytes()) as ZipCentralReader.Listing.Ok
        assertEquals(
            listOf(
                "Profils/Éclairage@Dimmer@Rémi.xml" to 0,      // UTF-8 SANS drapeau
                "Profils/Café@Break@Noël.xml" to 0,            // CP437 sans drapeau
                "Profils/Zoé@LED Bar@Hélène.xml" to 1          // UTF-8 avec drapeau
            ),
            l.entries.map { it.name to (if (it.flags and 0x800 != 0) 1 else 0) }
        )
        assertEquals("Rémi.xml", ZipCentralReader.entryName("Rémi.xml".toByteArray(), 0))
        assertEquals("Noëlé", ZipCentralReader.entryName(byteArrayOf(0x4e, 0x6f, 0x89.toByte(), 0x6c, 0x82.toByte()), 0))
        assertEquals("a.xml", ZipCentralReader.entryName("a.xml".toByteArray(), 0))
        assertEquals("Zoé", ZipCentralReader.entryName("Zoé".toByteArray(), 0x800))
        // UTF-8 tronqué (dernier octet manquant) : invalide → CP437, jamais d'exception.
        assertEquals("R\u251c", ZipCentralReader.entryName(byteArrayOf(0x52, 0xC3.toByte()), 0))
        // Table CP437 entière (0x80-0xFF : jamais de l'UTF-8 valide), contre le jeu de la JVM
        // quand il existe (il n'est pas garanti sur Android, d'où la table embarquée).
        val high = ByteArray(128) { (it + 0x80).toByte() }
        val ibm437 = runCatching { java.nio.charset.Charset.forName("IBM437") }.getOrNull()
        if (ibm437 != null) assertEquals(String(high, ibm437), ZipCentralReader.entryName(high, 0))
        assertEquals('\u00c7', ZipCentralReader.entryName(high, 0).first())
        assertEquals('\u00a0', ZipCentralReader.entryName(high, 0).last())
    }

    /**
     * UNE seule erreur `zipLimit` par archive : plafond d'entrées signalé à la préparation,
     * puis plafond décompressé atteint à la lecture → arrêt SANS seconde erreur (avant : une
     * 2ᵉ erreur « après 1 fichiers »). Même scénario que le web (5 entrées, 1000 octets).
     */
    @Test fun oneZipLimitErrorPerArchive() {
        val d = res("zips/ma_profiles.zip").readBytes()
        val limits = MaZipImport.Limits(totalBytes = 1000, entries = 5)
        val p = MaZipImport.plan("ma_profiles.zip", ZipCentralReader.ByteSource(d), limits = limits)
        assertEquals(listOf("ma_profiles.zip" to MaZipImport.ArchiveError.Limit(5)), p.errors)
        assertEquals(3, p.candidates.size)                              // 3 profils réels retenus
        val session = p.session!!
        assertEquals(List(3) { MaZipImport.EntryRead.Skipped }, p.candidates.map { session.read(it) })
        // Jamais signalée (préparation sans arrêt) : `zipLimit` UNE fois, puis sauté.
        val flat = zip("a.xml" to dimmer, "b.xml" to res("synthetic/ma2_break2.xml").readBytes())
        val q = MaZipImport.plan("flat.zip", ZipCentralReader.ByteSource(flat), limits = MaZipImport.Limits(totalBytes = 1000))
        assertTrue(q.errors.isEmpty())
        val qs = q.session!!
        assertEquals(MaZipImport.EntryRead.Limit(0), qs.read(q.candidates[0]))
        assertEquals(MaZipImport.EntryRead.Skipped, qs.read(q.candidates[1]))
    }

    /** Session fermée : imbriquée, source et table rendues ; tout candidat est ensuite sauté. */
    @Test fun closedSessionReleasesEverything() {
        val inner = zip("p.xml" to dimmer)
        var srcClosed = false
        val bytes = zip("in.zip" to inner, "z.xml" to res("synthetic/ma2_break2.xml").readBytes())
        val src = object : ZipCentralReader.Source, java.io.Closeable {
            val inMemory = ZipCentralReader.ByteSource(bytes)
            override val size get() = inMemory.size
            override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int) = inMemory.readAt(pos, dst, off, len)
            override fun close() { srcClosed = true }
        }
        val p = MaZipImport.plan("x.zip", src)
        val s = p.session!!
        assertTrue(s.read(p.candidates[0]) is MaZipImport.EntryRead.Ok)   // p.xml de l'imbriquée
        assertTrue(s.holdsNested)
        s.close()
        assertTrue(s.closed); assertTrue(srcClosed); assertTrue(!s.holdsNested)
        assertEquals(MaZipImport.EntryRead.Skipped, s.read(p.candidates[1]))
        s.close()                                                       // idempotent
    }
}
