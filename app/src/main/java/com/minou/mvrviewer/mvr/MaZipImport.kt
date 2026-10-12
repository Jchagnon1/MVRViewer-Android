package com.minou.mvrviewer.mvr

import java.io.Closeable

/**
 * ARCHIVES `.zip` DE PROFILS MA (SPEC-ma-gdtfshare.md §5.2) — PUR (testable en JVM).
 *
 * Une archive choisie, trouvée dans un dossier ou reçue (« Ouvrir avec ») est développée
 * comme un dossier : ses entrées `.xml .xmlp .gdtf .pxml` sont des CANDIDATS du pipeline
 * habituel de la bibliothèque MA ; une `.zip` dans l'archive (profondeur 0) est développée
 * à son tour (profondeur 1, pas de troisième niveau). Rien n'est jamais écrit sur disque
 * sous le chemin d'une entrée (pas de « zip-slip ») : tout est lu EN MÉMOIRE, entrée par
 * entrée, par le répertoire central ([ZipCentralReader] sur un fichier à accès direct).
 *
 * Garde-fous : archive ≤ 512 Mo (vérifié par l'appelant AVANT lecture), entrée ≤ 64 Mo
 * (déclarée, puis décompression BORNÉE), au plus 50 000 entrées et 2 Gio décompressés par
 * archive (toutes profondeurs) → lecture ARRÊTÉE, erreur `zipLimit`. Les 2 Gio comptent les
 * octets RÉELLEMENT décompressés ([ZipCentralReader.Meter]), y compris ceux des entrées
 * rejetées ensuite (trop volumineuses, illisibles) : sinon une archive piégée de quelques
 * Ko (une donnée de 64 Mo référencée par des milliers d'entrées) faisait décompresser
 * 50 000 × 64 Mo. Pic mémoire : une archive imbriquée + une entrée.
 */
object MaZipImport {

    const val MAX_ARCHIVE_BYTES: Long = 512L * 1024 * 1024
    const val MAX_ENTRY_BYTES: Long = MaProfileReader.MAX_FILE_BYTES
    const val MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 50_000

    /**
     * Plafonds appliqués (ceux de la spec par défaut ; les tests en passent de plus petits
     * pour vérifier les garde-fous sans décompresser des gigaoctets).
     */
    class Limits(
        val entryBytes: Long = MAX_ENTRY_BYTES,
        val totalBytes: Long = MAX_TOTAL_BYTES,
        val entries: Int = MAX_ENTRIES
    )

    private val DEFAULT_LIMITS = Limits()

    /** Séparateur des noms affichés : `ext.zip › dossier/int.zip › fichier.xml`. */
    const val SEP = " › "

    val PROFILE_EXTENSIONS = setOf("xml", "xmlp", "gdtf", "pxml")

    enum class Kind {
        /** Dossier, `__MACOSX`, fichier caché : ignoré EN SILENCE. */
        SILENT,
        CANDIDATE,
        /** `.zip` dans une archive de profondeur 0 : développée. */
        NESTED,
        /** Autre format (ou `.zip` de troisième niveau) : compté « ignoré ». */
        IGNORED
    }

    /** Segments d'un chemin interne (séparateurs `/` ou `\`). */
    private fun segments(path: String): List<String> = path.split('/', '\\')

    fun baseName(path: String): String = segments(path).last()

    /** Classement EXACT d'une entrée (§5.2). */
    fun classify(path: String, depth: Int): Kind {
        if (path.isEmpty() || path.endsWith("/") || path.endsWith("\\")) return Kind.SILENT
        val segs = segments(path)
        if (segs.any { it == "__MACOSX" }) return Kind.SILENT
        val base = segs.last()
        if (base.isEmpty() || base.startsWith(".")) return Kind.SILENT
        val ext = base.substringAfterLast('.', "").lowercase()
        return when {
            ext in PROFILE_EXTENSIONS -> Kind.CANDIDATE
            ext == "zip" -> if (depth == 0) Kind.NESTED else Kind.IGNORED
            else -> Kind.IGNORED
        }
    }

    /** Erreur d'ARCHIVE (comptée dans « N erreurs » ; nom affiché = l'archive seule). */
    sealed class ArchiveError {
        object Unreadable : ArchiveError()
        object Empty : ArchiveError()
        /** Lecture arrêtée après `count` entrées lues. */
        data class Limit(val count: Int) : ArchiveError()
        /** Archive (ou archive imbriquée) trop volumineuse. */
        data class TooLarge(val megabytes: Int, val maxMegabytes: Int) : ArchiveError()
    }

    internal class Nested(
        val entry: ZipCentralReader.Entry,
        val displayName: String,
        val listing: ZipCentralReader.Listing.Ok
    )

    /** Une entrée candidate planifiée. */
    class Candidate internal constructor(
        /** `archive.zip › dossier/fichier.xml` (bilan). */
        val displayName: String,
        /** Nom de base (`fichier.xml`) : nom du profil dans la bibliothèque. */
        val baseName: String,
        /** Taille décompressée DÉCLARÉE. */
        val declaredSize: Long,
        /** Nombre d'entrées lues AVANT celle-ci (message `zipLimit` d'un arrêt en cours d'import). */
        internal val ordinal: Int,
        internal val entry: ZipCentralReader.Entry,
        internal val nested: Nested?
    )

    /**
     * Plan d'une archive : candidats (dans l'ordre du répertoire central, imbriquées
     * développées à leur place), ignorés, erreurs d'archive, et la [Session] qui lira les
     * candidats (à fermer dès le dernier candidat traité).
     */
    class Plan(
        val archiveName: String,
        val candidates: List<Candidate>,
        val ignored: Int,
        val errors: List<Pair<String, ArchiveError>>,
        val session: Session?,
        /** Préparation interrompue par l'utilisateur (« Arrêter », écran quitté) : plan vide. */
        val cancelled: Boolean = false
    )

    /** Issue de la lecture d'un candidat. */
    sealed class EntryRead {
        class Ok(val bytes: ByteArray) : EntryRead()
        /** Décompression arrêtée (`megabytes` = taille déclarée arrondie si elle dépasse, sinon 65). */
        data class TooLarge(val megabytes: Int) : EntryRead()
        object Unreadable : EntryRead()
        /** Garde-fou 2 Gio atteint : lecture de l'archive ARRÊTÉE (erreur à nommer d'après l'archive). */
        data class Limit(val count: Int) : EntryRead()
        /** Archive déjà arrêtée (garde-fou, déjà signalé) ou fermée : candidat sauté sans bruit. */
        object Skipped : EntryRead()
    }

    /**
     * Lecture des candidats d'UNE archive ; garde UNE archive imbriquée en mémoire, jusqu'à
     * [close] — que l'import fait dès le DERNIER candidat de l'archive traité : dans un lot
     * de plusieurs archives, le pic reste « une archive imbriquée + une entrée » (§5.2) au
     * lieu de croître d'une imbriquée (jusqu'à 64 Mo) par archive jusqu'à la fin du lot.
     * `limitReported` : la préparation ([plan]) a déjà signalé `zipLimit` pour cette
     * archive — UNE seule erreur d'archive : le plafond de 2 Gio atteint ensuite arrête la
     * lecture SANS nouvelle erreur.
     */
    class Session internal constructor(
        val archiveName: String,
        src: ZipCentralReader.Source,
        listing: ZipCentralReader.Listing.Ok,
        decompressedSoFar: Long,
        private val limits: Limits = DEFAULT_LIMITS,
        limitReported: Boolean = false
    ) : Closeable {
        // Rendues par close() (source, table centrale, imbriquée) : plus rien ne les retient.
        private var src: ZipCentralReader.Source? = src
        private var listing: ZipCentralReader.Listing.Ok? = listing
        /** Octets RÉELLEMENT décompressés pour cette archive (tables d'imbriquées comprises), quelle que soit l'issue. */
        private val meter = ZipCentralReader.Meter().also { it.bytes = decompressedSoFar }
        private var stopped = false
        private var limitReported = limitReported
        private var currentNested: Nested? = null
        private var nestedSource: ZipCentralReader.ByteSource? = null

        /** Total décompressé jusqu'ici (garde-fou 2 Gio ; lu par les tests). */
        val decompressed: Long get() = meter.bytes

        /** Fermée : source, table centrale et imbriquée rendues (lu par les tests). */
        var closed = false
            private set

        /** Une archive imbriquée est-elle gardée en mémoire ? (lu par les tests) */
        val holdsNested: Boolean get() = nestedSource != null

        fun read(c: Candidate): EntryRead {
            val src = this.src
            val listing = this.listing
            if (stopped || src == null || listing == null) return EntryRead.Skipped
            if (meter.bytes + minOf(c.declaredSize, limits.entryBytes) > limits.totalBytes) {
                stopped = true
                if (limitReported) return EntryRead.Skipped     // déjà signalée à la préparation
                limitReported = true
                return EntryRead.Limit(c.ordinal)
            }
            val (s, l) = if (c.nested == null) src to listing else {
                val n = c.nested
                if (currentNested !== n) {
                    currentNested = null; nestedSource = null
                    // Relecture de l'imbriquée : déjà comptée UNE fois par plan() (ses candidats
                    // se suivent, elle n'est relue qu'une fois) — pas de second décompte.
                    val r = ZipCentralReader.readBounded(src, listing, n.entry, limits.entryBytes)
                    val bytes = (r as? ZipCentralReader.Read.Ok)?.bytes ?: return EntryRead.Unreadable
                    currentNested = n; nestedSource = ZipCentralReader.ByteSource(bytes)
                }
                nestedSource!! to n.listing
            }
            // Le compteur avance de ce qui a été décompressé, MÊME si l'entrée est rejetée.
            return when (val r = ZipCentralReader.readBounded(s, l, c.entry, limits.entryBytes, meter = meter)) {
                is ZipCentralReader.Read.Ok -> EntryRead.Ok(r.bytes)
                ZipCentralReader.Read.TooLarge -> EntryRead.TooLarge(tooLargeMegabytes(c.declaredSize))
                ZipCentralReader.Read.Unreadable -> EntryRead.Unreadable
            }
        }

        /** Rend l'imbriquée, la source (fichier fermé) et la table centrale ; idempotent. */
        override fun close() {
            val s = src
            closed = true
            src = null; listing = null; currentNested = null; nestedSource = null
            (s as? Closeable)?.let { runCatching { it.close() } }
        }
    }

    /** `ceil(déclarée / 1 Mio)` si elle dépasse 64 Mo, sinon 65 (arrêt à 64 Mo + 1 octet). */
    fun tooLargeMegabytes(declared: Long): Int =
        if (declared > MAX_ENTRY_BYTES) megabytes(declared) else (MAX_ENTRY_BYTES / MIB).toInt() + 1

    private const val MIB = 1024L * 1024
    fun megabytes(bytes: Long): Int = ((bytes + MIB - 1) / MIB).toInt()

    private fun display(prefix: String, path: String) = prefix + SEP + path.replace('\\', '/')

    /**
     * Lit les tables centrales (archive puis imbriquées) AVANT tout import : le total de la
     * progression compte ainsi tous les candidats. `src` est fermée par la session (ou ici en
     * cas d'erreur d'archive). `isCancelled` est lu avant chaque archive imbriquée (seule
     * étape coûteuse : jusqu'à 64 Mo décompressés) : « Arrêter » ou l'écran quitté arrête la
     * préparation, et le plan rendu est vide (`cancelled`).
     */
    fun plan(
        archiveName: String,
        src: ZipCentralReader.Source,
        isCancelled: () -> Boolean = { false },
        limits: Limits = DEFAULT_LIMITS
    ): Plan {
        fun closeSrc() { (src as? Closeable)?.let { runCatching { it.close() } } }
        fun failed(err: ArchiveError): Plan {
            closeSrc()
            return Plan(archiveName, emptyList(), 0, listOf(archiveName to err), null)
        }
        if (src.size > MAX_ARCHIVE_BYTES) return failed(ArchiveError.TooLarge(megabytes(src.size), (MAX_ARCHIVE_BYTES / MIB).toInt()))
        if (src.size < 2) return failed(ArchiveError.Unreadable)
        val magic = ByteArray(2)
        if (!src.readAt(0, magic, 0, 2) || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte())
            return failed(ArchiveError.Unreadable)
        val listing = ZipCentralReader.list(src, limits.entries + 1) as? ZipCentralReader.Listing.Ok
            ?: return failed(ArchiveError.Unreadable)

        val candidates = ArrayList<Candidate>()
        val errors = ArrayList<Pair<String, ArchiveError>>()
        var ignored = 0
        var count = 0
        var limit = false
        // Octets RÉELLEMENT décompressés (imbriquées lues ici), même pour une imbriquée rejetée.
        val meter = ZipCentralReader.Meter()
        outer@ for (e in listing.entries) {
            if (count >= limits.entries) { limit = true; break }
            count++
            when (classify(e.name, 0)) {
                Kind.SILENT -> {}
                Kind.IGNORED -> ignored++
                Kind.CANDIDATE -> candidates.add(Candidate(display(archiveName, e.name), baseName(e.name), e.size, count - 1, e, null))
                Kind.NESTED -> {
                    if (isCancelled()) {
                        closeSrc()
                        return Plan(archiveName, emptyList(), ignored, emptyList(), null, cancelled = true)
                    }
                    val nd = display(archiveName, e.name)
                    if (meter.bytes + minOf(e.size, limits.entryBytes) > limits.totalBytes) { limit = true; break@outer }
                    when (val r = ZipCentralReader.readBounded(src, listing, e, limits.entryBytes, meter = meter)) {
                        ZipCentralReader.Read.TooLarge ->
                            errors.add(nd to ArchiveError.TooLarge(tooLargeMegabytes(e.size), (MAX_ENTRY_BYTES / MIB).toInt()))
                        ZipCentralReader.Read.Unreadable -> errors.add(nd to ArchiveError.Unreadable)
                        is ZipCentralReader.Read.Ok -> {
                            val nl = if (r.bytes.size >= 2 && r.bytes[0] == 'P'.code.toByte() && r.bytes[1] == 'K'.code.toByte())
                                ZipCentralReader.list(ZipCentralReader.ByteSource(r.bytes), limits.entries - count + 1) as? ZipCentralReader.Listing.Ok
                            else null
                            if (nl == null) errors.add(nd to ArchiveError.Unreadable)
                            else {
                                val ref = Nested(e, nd, nl)
                                var found = 0
                                for (ne in nl.entries) {
                                    if (count >= limits.entries) { limit = true; break@outer }
                                    count++
                                    when (classify(ne.name, 1)) {
                                        Kind.SILENT -> {}
                                        Kind.CANDIDATE -> {
                                            candidates.add(Candidate(display(nd, ne.name), baseName(ne.name), ne.size, count - 1, ne, ref))
                                            found++
                                        }
                                        else -> ignored++
                                    }
                                }
                                if (found == 0) errors.add(nd to ArchiveError.Empty)
                            }
                        }
                    }
                }
            }
        }
        if (limit) errors.add(archiveName to ArchiveError.Limit(count))
        else if (candidates.isEmpty()) errors.add(archiveName to ArchiveError.Empty)
        if (candidates.isEmpty()) {
            closeSrc()
            return Plan(archiveName, candidates, ignored, errors, null)
        }
        return Plan(archiveName, candidates, ignored, errors,
            Session(archiveName, src, listing, meter.bytes, limits, limitReported = limit))
    }
}
