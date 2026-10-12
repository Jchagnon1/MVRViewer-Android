package com.minou.mvrviewer.mvr

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Lecteur ZIP par le RÉPERTOIRE CENTRAL (comme `zipfile` de Python, ZIPFoundation sur iOS
 * et fflate sur le web), PUR (testable en JVM).
 *
 * Pourquoi : `java.util.zip.ZipInputStream` lit les en-têtes LOCAUX en séquence et LÈVE
 * sur une entrée STORED écrite « en flux » (bit 3, descripteur de données) — « only
 * DEFLATED entries can have EXT descriptor » — alors que ce zip est valide : la taille
 * compressée n'est connue que du répertoire central. Un `.gdtf` produit ainsi était donc
 * « illisible » sur Android seulement.
 *
 * Deux sources : octets EN MÉMOIRE ([ByteSource], usage historique : .mvr, .gdtf) ou
 * FICHIER à accès direct ([FileSource] : archive `.zip` de profils MA jusqu'à 512 Mo,
 * jamais chargée entière — SPEC-ma-gdtfshare.md §5.2). Le même code lit les deux, sur
 * l'appareil comme en test JVM (contrairement à `java.util.zip.ZipFile`, dont le
 * comportement diffère entre la JVM et Android : entrée chiffrée qui fait refuser toute
 * l'archive, validation des chemins…).
 *
 * Portée volontairement minimale : méthodes STORED (0) et DEFLATED (8), zip64, octets
 * préfixés (décalage « concat » de Python), CRC32 vérifié. Chiffré, autre méthode, CRC
 * faux, répertoire tronqué → [Listing.Broken] / [Read.Unreadable], jamais d'exception.
 */
object ZipCentralReader {

    /** Plafond de décompression d'UNE entrée (garde-fou mémoire contre les bombes zip). */
    const val MAX_ENTRY_BYTES: Long = 256L * 1024 * 1024

    /** Accès direct en lecture seule. */
    interface Source {
        val size: Long
        /** Lit EXACTEMENT `len` octets à `pos` dans `dst[off…]` ; false si hors bornes ou en échec. */
        fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Boolean
    }

    /** Octets en mémoire. */
    class ByteSource(private val data: ByteArray) : Source {
        override val size: Long get() = data.size.toLong()
        override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Boolean {
            if (pos < 0 || len < 0 || pos + len > data.size) return false
            System.arraycopy(data, pos.toInt(), dst, off, len)
            return true
        }
    }

    /** Fichier (ou descripteur « seekable ») lu par positions, sans tout charger. */
    class FileSource(private val channel: FileChannel, private val owner: Closeable? = null) : Source, Closeable {
        override val size: Long = runCatching { channel.size() }.getOrDefault(-1L)
        override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Boolean {
            if (pos < 0 || len < 0 || size < 0 || pos + len > size) return false
            return try {
                val buf = ByteBuffer.wrap(dst, off, len)
                var p = pos
                while (buf.hasRemaining()) {
                    val n = channel.read(buf, p)
                    if (n <= 0) return false
                    p += n
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        override fun close() {
            runCatching { channel.close() }
            runCatching { owner?.close() }
        }

        companion object {
            fun open(file: File): FileSource {
                val raf = RandomAccessFile(file, "r")
                return FileSource(raf.channel, raf)
            }
        }
    }

    class Entry internal constructor(
        /** Nom tel qu'écrit dans le répertoire central, décodé par [entryName]. */
        val name: String,
        internal val rawName: ByteArray,
        internal val flags: Int,
        internal val method: Int,
        internal val crc: Long,
        internal val compressedSize: Long,
        /** Taille décompressée DÉCLARÉE. */
        val size: Long,
        internal val localOffset: Long
    ) {
        /** Bit 0 du drapeau général : entrée chiffrée (jamais lue). */
        val isEncrypted: Boolean get() = flags and 0x01 != 0
    }

    sealed class Listing {
        /**
         * `truncated` : la lecture s'est arrêtée à `maxEntries` (garde-fou d'archive) —
         * d'autres entrées suivent dans le répertoire, elles n'ont pas été lues.
         */
        class Ok(val entries: List<Entry>, internal val concat: Long, val truncated: Boolean = false) : Listing()
        /** Pas de fin de répertoire central, ou répertoire illisible. */
        object Broken : Listing()
    }

    /** Répertoire central COMPLET (une entrée centrale malformée → [Listing.Broken], comme Python). */
    fun list(data: ByteArray): Listing = list(ByteSource(data))

    /**
     * Répertoire central d'une [Source] ; au plus `maxEntries` entrées (au-delà : liste
     * tronquée, `truncated` vrai — le reste n'est même pas lu).
     */
    fun list(src: Source, maxEntries: Int = Int.MAX_VALUE): Listing = try {
        listInternal(src, maxEntries) ?: Listing.Broken
    } catch (_: Exception) {
        Listing.Broken
    } catch (_: OutOfMemoryError) {
        Listing.Broken
    }

    /** Noms des entrées, ou null si le zip est illisible. */
    fun names(data: ByteArray): List<String>? = (list(data) as? Listing.Ok)?.entries?.map { it.name }

    /**
     * Octets décompressés d'une entrée de [listing] ; null si l'entrée est illisible
     * (chiffrée, méthode inconnue, en-tête local faux, flux tronqué, CRC faux, trop grosse).
     */
    fun read(data: ByteArray, listing: Listing.Ok, entry: Entry): ByteArray? =
        (readBounded(ByteSource(data), listing, entry, MAX_ENTRY_BYTES, stopAtDeclared = true) as? Read.Ok)?.bytes

    /** Issue d'une lecture BORNÉE. */
    sealed class Read {
        class Ok(val bytes: ByteArray) : Read()
        /** Taille déclarée au-delà du plafond, ou décompression arrêtée à plafond + 1 octet. */
        object TooLarge : Read()
        /** Chiffrée, méthode inconnue, en-tête local faux, flux tronqué ou corrompu, CRC faux. */
        object Unreadable : Read()
    }

    /**
     * Compteur des octets RÉELLEMENT produits par les lectures (décompressés, ou lus tels quels
     * pour une entrée STORED), QUELLE QUE SOIT l'issue : une entrée rejetée « trop volumineuse »
     * ou « illisible » après 64 Mo décompressés a coûté ces 64 Mo. C'est ce total qui sert au
     * garde-fou « 2 Gio décompressés par archive » (SPEC-ma-gdtfshare.md §5.2) : compter les
     * seules lectures réussies laissait une archive piégée faire décompresser 50 000 × 64 Mo.
     */
    class Meter {
        var bytes: Long = 0L
            internal set
    }

    /**
     * Lit une entrée en s'arrêtant à `limit` octets décompressés (taille DÉCLARÉE au-delà →
     * [Read.TooLarge] sans rien décompresser ; flux qui dépasse `limit` → [Read.TooLarge]).
     * `stopAtDeclared` : arrêt (illisible) dès que le flux dépasse sa taille déclarée — sinon
     * on ne s'arrête qu'au plafond (une taille déclarée MENSONGÈRE donne alors « trop
     * volumineux », règle de l'import d'archives, SPEC-ma-gdtfshare.md §5.2).
     * `meter` : reçoit les octets réellement produits, même si la lecture échoue.
     */
    fun readBounded(
        src: Source, listing: Listing.Ok, entry: Entry, limit: Long,
        stopAtDeclared: Boolean = false, meter: Meter? = null
    ): Read = try {
        readInternal(src, listing.concat, entry, limit, stopAtDeclared, meter)
    } catch (_: Exception) {
        Read.Unreadable
    } catch (_: OutOfMemoryError) {
        Read.TooLarge
    }

    /** Première entrée (ordre du répertoire central) dont le nom vérifie [match], décompressée. */
    sealed class Lookup {
        class Found(val bytes: ByteArray) : Lookup()
        object Missing : Lookup()
        object Broken : Lookup()
    }

    fun find(data: ByteArray, match: (String) -> Boolean): Lookup {
        val l = list(data) as? Listing.Ok ?: return Lookup.Broken
        val e = l.entries.firstOrNull { match(it.name) } ?: return Lookup.Missing
        return read(data, l, e)?.let { Lookup.Found(it) } ?: Lookup.Broken
    }

    /**
     * Nom d'une entrée à partir de ses octets et du drapeau général (SPEC-ma-gdtfshare.md
     * §5.2, comme le web `zipEntryName` et l'oracle `share_reference.py`) :
     * drapeau UTF-8 (bit 11) → UTF-8 ; SANS lui → UTF-8 s'il est VALIDE (« Compresser » du
     * Finder et `zip` d'Info-ZIP sur macOS écrivent de l'UTF-8 sans poser le drapeau), sinon
     * CP437 (page de code d'origine du format : archives DOS/Windows anciennes). Les noms
     * ASCII sont identiques dans les trois cas. Avant : Latin-1 sans drapeau, d'où
     * « Ã‰clairage@Dimmer@RÃ©mi.xml » (nom ET auteur faux dans la bibliothèque).
     */
    fun entryName(raw: ByteArray, flags: Int): String {
        if (flags and 0x800 != 0) return String(raw, Charsets.UTF_8)
        return try {
            // Décodeur STRICT (un par appel : un CharsetDecoder n'est pas sûr entre threads).
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (_: CharacterCodingException) {
            cp437(raw)
        }
    }

    /**
     * Octets 0x80-0xFF de CP437 (0x00-0x7F = ASCII), en points de code — table embarquée :
     * le jeu « IBM437 » n'est pas garanti sur Android (même table que le web).
     */
    private val CP437_HIGH: CharArray = intArrayOf(
        0x00c7, 0x00fc, 0x00e9, 0x00e2, 0x00e4, 0x00e0, 0x00e5, 0x00e7, 0x00ea, 0x00eb, 0x00e8, 0x00ef, 0x00ee, 0x00ec, 0x00c4, 0x00c5,
        0x00c9, 0x00e6, 0x00c6, 0x00f4, 0x00f6, 0x00f2, 0x00fb, 0x00f9, 0x00ff, 0x00d6, 0x00dc, 0x00a2, 0x00a3, 0x00a5, 0x20a7, 0x0192,
        0x00e1, 0x00ed, 0x00f3, 0x00fa, 0x00f1, 0x00d1, 0x00aa, 0x00ba, 0x00bf, 0x2310, 0x00ac, 0x00bd, 0x00bc, 0x00a1, 0x00ab, 0x00bb,
        0x2591, 0x2592, 0x2593, 0x2502, 0x2524, 0x2561, 0x2562, 0x2556, 0x2555, 0x2563, 0x2551, 0x2557, 0x255d, 0x255c, 0x255b, 0x2510,
        0x2514, 0x2534, 0x252c, 0x251c, 0x2500, 0x253c, 0x255e, 0x255f, 0x255a, 0x2554, 0x2569, 0x2566, 0x2560, 0x2550, 0x256c, 0x2567,
        0x2568, 0x2564, 0x2565, 0x2559, 0x2558, 0x2552, 0x2553, 0x256b, 0x256a, 0x2518, 0x250c, 0x2588, 0x2584, 0x258c, 0x2590, 0x2580,
        0x03b1, 0x00df, 0x0393, 0x03c0, 0x03a3, 0x03c3, 0x00b5, 0x03c4, 0x03a6, 0x0398, 0x03a9, 0x03b4, 0x221e, 0x03c6, 0x03b5, 0x2229,
        0x2261, 0x00b1, 0x2265, 0x2264, 0x2320, 0x2321, 0x00f7, 0x2248, 0x00b0, 0x2219, 0x00b7, 0x221a, 0x207f, 0x00b2, 0x25a0, 0x00a0
    ).let { cps -> CharArray(cps.size) { cps[it].toChar() } }

    private fun cp437(raw: ByteArray): String {
        val sb = StringBuilder(raw.size)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            sb.append(if (v < 0x80) v.toChar() else CP437_HIGH[v - 0x80])
        }
        return sb.toString()
    }

    // ---- Interne -----------------------------------------------------------------------

    private fun u16(d: ByteArray, i: Int): Int = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(d: ByteArray, i: Int): Long =
        (d[i].toLong() and 0xFF) or ((d[i + 1].toLong() and 0xFF) shl 8) or
            ((d[i + 2].toLong() and 0xFF) shl 16) or ((d[i + 3].toLong() and 0xFF) shl 24)
    private fun u64(d: ByteArray, i: Int): Long = u32(d, i) or (u32(d, i + 4) shl 32)

    private fun sig(d: ByteArray, i: Int, b2: Int, b3: Int): Boolean =
        i >= 0 && i + 4 <= d.size && d[i] == 0x50.toByte() && d[i + 1] == 0x4B.toByte() &&
            d[i + 2] == b2.toByte() && d[i + 3] == b3.toByte()

    private fun readBytes(src: Source, pos: Long, len: Int): ByteArray? {
        if (len < 0) return null
        val b = ByteArray(len)
        return if (src.readAt(pos, b, 0, len)) b else null
    }

    private fun listInternal(src: Source, maxEntries: Int): Listing.Ok? {
        val size = src.size
        if (size < 22) return null
        // Fin de répertoire central : dans les 22 + 65 535 derniers octets.
        val tailStart = maxOf(0L, size - 22 - 65_535)
        val tail = readBytes(src, tailStart, (size - tailStart).toInt()) ?: return null
        var e = tail.size - 22
        while (e >= 0 && !sig(tail, e, 0x05, 0x06)) e--
        if (e < 0) return null
        val eocd = tailStart + e
        var cdSize = u32(tail, e + 12)
        var cdOffset = u32(tail, e + 16)
        var cdEnd = eocd                    // le répertoire central se termine juste avant
        // zip64 : localisateur (20 octets) juste avant la fin de répertoire central.
        if (eocd - 20 >= 0) {
            val loc = readBytes(src, eocd - 20, 20)
            if (loc != null && sig(loc, 0, 0x06, 0x07)) {
                val recPos = eocd - 20 - 56
                val rec = if (recPos >= 0) readBytes(src, recPos, 56) else null
                if (rec != null && sig(rec, 0, 0x06, 0x06)) {
                    cdSize = u64(rec, 40)
                    cdOffset = u64(rec, 48)
                    cdEnd = recPos
                }
            }
        }
        // Octets préfixés (auto-extractible…) : décalage constaté, comme Python.
        val concat = cdEnd - cdSize - cdOffset
        if (concat < 0) return null
        var p = cdOffset + concat
        if (p < 0 || p > size) return null
        val out = ArrayList<Entry>()
        val end = p + cdSize
        val head = ByteArray(46)
        while (p < end) {
            if (out.size >= maxEntries) return Listing.Ok(out, concat, truncated = true)
            if (!src.readAt(p, head, 0, 46) || !sig(head, 0, 0x01, 0x02)) return null
            val flags = u16(head, 8)
            val method = u16(head, 10)
            val crc = u32(head, 16)
            var csize = u32(head, 20)
            var usize = u32(head, 24)
            val nameLen = u16(head, 28)
            val extraLen = u16(head, 30)
            val commentLen = u16(head, 32)
            var offset = u32(head, 42)
            val varLen = nameLen + extraLen
            if (p + 46 + varLen + commentLen > size) return null
            val v = readBytes(src, p + 46, varLen) ?: return null
            val raw = v.copyOfRange(0, nameLen)
            // Champ zip64 (0x0001) : valeurs 64 bits, dans l'ordre, des seuls champs saturés.
            var x = nameLen
            val xEnd = x + extraLen
            while (x + 4 <= xEnd) {
                val id = u16(v, x); val len = u16(v, x + 2)
                if (x + 4 + len > xEnd) break
                if (id == 0x0001) {
                    var q = x + 4
                    if (usize == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { usize = u64(v, q); q += 8 }
                    if (csize == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { csize = u64(v, q); q += 8 }
                    if (offset == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { offset = u64(v, q) }
                }
                x += 4 + len
            }
            out.add(Entry(entryName(raw, flags), raw, flags, method, crc, csize, usize, offset))
            p += 46L + varLen + commentLen
        }
        // Comme Python, c'est la TAILLE du répertoire qui borne la lecture, pas le décompte.
        return Listing.Ok(out, concat)
    }

    private fun readInternal(src: Source, concat: Long, e: Entry, limit: Long, stopAtDeclared: Boolean, meter: Meter?): Read {
        if (e.isEncrypted) return Read.Unreadable                    // chiffré
        if (e.size < 0 || e.compressedSize < 0) return Read.Unreadable
        if (e.size > limit) return Read.TooLarge
        val h = e.localOffset + concat
        val lh = if (h >= 0) readBytes(src, h, 30) else null
        if (lh == null || !sig(lh, 0, 0x03, 0x04)) return Read.Unreadable
        val nameLen = u16(lh, 26)
        val extraLen = u16(lh, 28)
        // Nom de l'en-tête local == nom du répertoire central (contrôle de `zipfile`).
        val localName = readBytes(src, h + 30, nameLen) ?: return Read.Unreadable
        if (!localName.contentEquals(e.rawName)) return Read.Unreadable
        val start = h + 30 + nameLen + extraLen
        if (start + e.compressedSize > src.size) return Read.Unreadable
        val bytes: ByteArray = when (e.method) {
            0 -> {
                if (e.compressedSize != e.size) return Read.Unreadable
                meter?.let { it.bytes += e.compressedSize }
                readBytes(src, start, e.compressedSize.toInt()) ?: return Read.Unreadable
            }
            8 -> {
                val inf = Inflater(true)
                try {
                    val out = java.io.ByteArrayOutputStream(minOf(e.size, 1L shl 24).toInt().coerceAtLeast(64))
                    val buf = ByteArray(64 * 1024)
                    val inBuf = ByteArray(64 * 1024)
                    var inPos = start
                    var inLeft = e.compressedSize
                    var total = 0L
                    while (!inf.finished()) {
                        if (inf.needsInput()) {
                            // Flux deflate tronqué : taille compressée épuisée avant la fin.
                            if (inLeft <= 0) return Read.Unreadable
                            val n = minOf(inBuf.size.toLong(), inLeft).toInt()
                            if (!src.readAt(inPos, inBuf, 0, n)) return Read.Unreadable
                            inf.setInput(inBuf, 0, n)
                            inPos += n; inLeft -= n
                        }
                        val n = inf.inflate(buf)
                        if (n > 0) {
                            meter?.let { it.bytes += n }
                            total += n
                            if (total > limit) return Read.TooLarge
                            if (stopAtDeclared && total > e.size) return Read.Unreadable
                            out.write(buf, 0, n)
                        } else if (!inf.finished() && !inf.needsInput()) {
                            // Dictionnaire exigé ou flux bloqué : illisible.
                            return Read.Unreadable
                        }
                    }
                    out.toByteArray()
                } finally {
                    inf.end()
                }
            }
            else -> return Read.Unreadable                            // méthode non prise en charge
        }
        if (bytes.size.toLong() != e.size) return Read.Unreadable
        val crc = CRC32().apply { update(bytes) }.value
        if (crc != e.crc) return Read.Unreadable
        return Read.Ok(bytes)
    }
}
