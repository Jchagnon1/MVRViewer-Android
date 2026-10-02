package com.minou.mvrviewer.mvr

import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Lecteur ZIP par le RÉPERTOIRE CENTRAL (comme `zipfile` de Python, ZIPFoundation sur iOS
 * et fflate sur le web), en mémoire, PUR (testable en JVM).
 *
 * Pourquoi : `java.util.zip.ZipInputStream` lit les en-têtes LOCAUX en séquence et LÈVE
 * sur une entrée STORED écrite « en flux » (bit 3, descripteur de données) — « only
 * DEFLATED entries can have EXT descriptor » — alors que ce zip est valide : la taille
 * compressée n'est connue que du répertoire central. Un `.gdtf` produit ainsi était donc
 * « illisible » sur Android seulement.
 *
 * Portée volontairement minimale : méthodes STORED (0) et DEFLATED (8), zip64, octets
 * préfixés (décalage « concat » de Python), CRC32 vérifié. Chiffré, autre méthode, CRC
 * faux, répertoire tronqué → [Broken] (= « illisible »), jamais d'exception.
 */
object ZipCentralReader {

    /** Plafond de décompression d'UNE entrée (garde-fou mémoire contre les bombes zip). */
    const val MAX_ENTRY_BYTES: Long = 256L * 1024 * 1024

    class Entry internal constructor(
        /** Nom tel qu'écrit dans le répertoire central (UTF-8 si bit 11, sinon Latin-1). */
        val name: String,
        internal val rawName: ByteArray,
        internal val flags: Int,
        internal val method: Int,
        internal val crc: Long,
        internal val compressedSize: Long,
        internal val size: Long,
        internal val localOffset: Long
    )

    sealed class Listing {
        class Ok(val entries: List<Entry>, internal val concat: Long) : Listing()
        /** Pas de fin de répertoire central, ou répertoire illisible. */
        object Broken : Listing()
    }

    /** Répertoire central COMPLET (une entrée centrale malformée → [Listing.Broken], comme Python). */
    fun list(data: ByteArray): Listing = try {
        listInternal(data) ?: Listing.Broken
    } catch (_: Exception) {
        Listing.Broken
    }

    /** Noms des entrées, ou null si le zip est illisible. */
    fun names(data: ByteArray): List<String>? = (list(data) as? Listing.Ok)?.entries?.map { it.name }

    /**
     * Octets décompressés d'une entrée de [listing] ; null si l'entrée est illisible
     * (chiffrée, méthode inconnue, en-tête local faux, flux tronqué, CRC faux, trop grosse).
     */
    fun read(data: ByteArray, listing: Listing.Ok, entry: Entry): ByteArray? = try {
        readInternal(data, listing.concat, entry)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
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

    private fun findEocd(d: ByteArray): Int {
        val stop = maxOf(0, d.size - 22 - 65_535)
        var i = d.size - 22
        while (i >= stop) {
            if (sig(d, i, 0x05, 0x06)) return i
            i--
        }
        return -1
    }

    private fun listInternal(d: ByteArray): Listing.Ok? {
        val eocd = findEocd(d)
        if (eocd < 0) return null
        var cdSize = u32(d, eocd + 12)
        var cdOffset = u32(d, eocd + 16)
        var cdEnd = eocd.toLong()          // le répertoire central se termine juste avant
        // zip64 : localisateur (20 octets) juste avant la fin de répertoire central.
        val loc = eocd - 20
        if (sig(d, loc, 0x06, 0x07)) {
            val rec = loc - 56
            if (sig(d, rec, 0x06, 0x06)) {
                cdSize = u64(d, rec + 40)
                cdOffset = u64(d, rec + 48)
                cdEnd = rec.toLong()
            }
        }
        // Octets préfixés (auto-extractible…) : décalage constaté, comme Python.
        val concat = cdEnd - cdSize - cdOffset
        if (concat < 0) return null
        var p = cdOffset + concat
        if (p < 0 || p > d.size) return null
        val out = ArrayList<Entry>()
        val end = p + cdSize
        while (p < end) {
            val i = p.toInt()
            if (i + 46 > d.size || !sig(d, i, 0x01, 0x02)) return null
            val flags = u16(d, i + 8)
            val method = u16(d, i + 10)
            val crc = u32(d, i + 16)
            var csize = u32(d, i + 20)
            var usize = u32(d, i + 24)
            val nameLen = u16(d, i + 28)
            val extraLen = u16(d, i + 30)
            val commentLen = u16(d, i + 32)
            var offset = u32(d, i + 42)
            val nameStart = i + 46
            if (nameStart + nameLen + extraLen + commentLen > d.size) return null
            val raw = d.copyOfRange(nameStart, nameStart + nameLen)
            // Champ zip64 (0x0001) : valeurs 64 bits, dans l'ordre, des seuls champs saturés.
            var x = nameStart + nameLen
            val xEnd = x + extraLen
            while (x + 4 <= xEnd) {
                val id = u16(d, x); val len = u16(d, x + 2)
                if (x + 4 + len > xEnd) break
                if (id == 0x0001) {
                    var q = x + 4
                    if (usize == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { usize = u64(d, q); q += 8 }
                    if (csize == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { csize = u64(d, q); q += 8 }
                    if (offset == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { offset = u64(d, q) }
                }
                x += 4 + len
            }
            val name = if (flags and 0x800 != 0) String(raw, Charsets.UTF_8) else String(raw, Charsets.ISO_8859_1)
            out.add(Entry(name, raw, flags, method, crc, csize, usize, offset))
            p = (xEnd + commentLen).toLong()
        }
        // Comme Python, c'est la TAILLE du répertoire qui borne la lecture, pas le décompte.
        return Listing.Ok(out, concat)
    }

    private fun readInternal(d: ByteArray, concat: Long, e: Entry): ByteArray? {
        if (e.flags and 0x01 != 0) return null                       // chiffré
        if (e.size > MAX_ENTRY_BYTES || e.size < 0 || e.compressedSize < 0) return null
        val h = e.localOffset + concat
        if (h < 0 || h + 30 > d.size) return null
        val hi = h.toInt()
        if (!sig(d, hi, 0x03, 0x04)) return null
        val nameLen = u16(d, hi + 26)
        val extraLen = u16(d, hi + 28)
        if (hi + 30 + nameLen > d.size) return null
        // Nom de l'en-tête local == nom du répertoire central (contrôle de `zipfile`).
        if (!d.copyOfRange(hi + 30, hi + 30 + nameLen).contentEquals(e.rawName)) return null
        val start = hi.toLong() + 30 + nameLen + extraLen
        if (start + e.compressedSize > d.size) return null
        val s = start.toInt()
        val csize = e.compressedSize.toInt()
        val bytes: ByteArray = when (e.method) {
            0 -> {
                if (e.compressedSize != e.size) return null
                d.copyOfRange(s, s + csize)
            }
            8 -> {
                val inf = Inflater(true)
                try {
                    inf.setInput(d, s, csize)
                    val out = java.io.ByteArrayOutputStream(minOf(e.size, 1L shl 24).toInt().coerceAtLeast(64))
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (!inf.finished()) {
                        val n = inf.inflate(buf)
                        if (n > 0) {
                            total += n
                            if (total > e.size || total > MAX_ENTRY_BYTES) return null
                            out.write(buf, 0, n)
                        } else if (!inf.finished()) {
                            // Flux deflate tronqué (taille compressée épuisée) ou dictionnaire exigé.
                            return null
                        }
                    }
                    out.toByteArray()
                } finally {
                    inf.end()
                }
            }
            else -> return null                                       // méthode non prise en charge
        }
        if (bytes.size.toLong() != e.size) return null
        val crc = CRC32().apply { update(bytes) }.value
        if (crc != e.crc) return null
        return bytes
    }
}
