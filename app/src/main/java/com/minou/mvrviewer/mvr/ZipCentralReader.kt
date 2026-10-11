package com.minou.mvrviewer.mvr

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
        /** Nom tel qu'écrit dans le répertoire central (UTF-8 si bit 11, sinon Latin-1). */
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
     * Lit une entrée en s'arrêtant à `limit` octets décompressés (taille DÉCLARÉE au-delà →
     * [Read.TooLarge] sans rien décompresser ; flux qui dépasse `limit` → [Read.TooLarge]).
     * `stopAtDeclared` : arrêt (illisible) dès que le flux dépasse sa taille déclarée — sinon
     * on ne s'arrête qu'au plafond (une taille déclarée MENSONGÈRE donne alors « trop
     * volumineux », règle de l'import d'archives, SPEC-ma-gdtfshare.md §5.2).
     */
    fun readBounded(src: Source, listing: Listing.Ok, entry: Entry, limit: Long, stopAtDeclared: Boolean = false): Read = try {
        readInternal(src, listing.concat, entry, limit, stopAtDeclared)
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
            val name = if (flags and 0x800 != 0) String(raw, Charsets.UTF_8) else String(raw, Charsets.ISO_8859_1)
            out.add(Entry(name, raw, flags, method, crc, csize, usize, offset))
            p += 46L + varLen + commentLen
        }
        // Comme Python, c'est la TAILLE du répertoire qui borne la lecture, pas le décompte.
        return Listing.Ok(out, concat)
    }

    private fun readInternal(src: Source, concat: Long, e: Entry, limit: Long, stopAtDeclared: Boolean): Read {
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
