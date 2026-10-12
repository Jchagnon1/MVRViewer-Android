package com.minou.mvrviewer.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minou.mvrviewer.mvr.GdtfLoader
import com.minou.mvrviewer.mvr.GdtfShareEntry
import com.minou.mvrviewer.mvr.GdtfShareList
import com.minou.mvrviewer.mvr.IncomingFileRouter
import com.minou.mvrviewer.mvr.MaProfileReader
import com.minou.mvrviewer.mvr.MaZipImport
import com.minou.mvrviewer.mvr.ZipCentralReader
import com.minou.mvrviewer.sync.ContentHash
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * BIBLIOTHÈQUE MA LOCALE (SPEC-ma.md §2, SPEC-ma-gdtfshare.md) — profils grandMA3 /
 * grandMA2 / GDTF importés depuis des FICHIERS (un, plusieurs, un dossier, une archive
 * `.zip`) ou révisions GDTF Share téléchargées par l'app (API officielle). MA Fixture Share
 * n'a aucune API pour les tiers : l'app ne s'y connecte jamais. Par APPAREIL, NON
 * synchronisée : seuls les TYPES créés depuis un mode voyagent (section customFixtures).
 * Importer, télécharger et consulter = GRATUIT (lecture).
 *
 *  - Index : `filesDir/maLibrary/library.json` = `{"version":1,"profiles":[entrée…]}` ;
 *  - octets des GDTF qui ont un vrai modèle 3D : `filesDir/maLibrary/files/<id>.gdtf`
 *    (option « La 3D de ce fichier GDTF ») ; les autres profils ne gardent pas le fichier.
 */

/**
 * Origine GDTF Share d'une entrée (clé `share`, LOCALE, jamais synchronisée —
 * SPEC-ma-gdtfshare.md §4.2). `lastModified` = date §3.2 en secondes, 0 si inconnue.
 */
data class MaShareInfo(
    val rid: Int,
    val uuid: String,
    val revision: String,
    /** `"manufacturer"` | `"user"` | `""`. */
    val uploader: String,
    val lastModified: Long
)

/** Une entrée de la bibliothèque (§2.1). `id` = SHA-256 hex minuscule des octets bruts. */
data class MaLibraryEntry(
    val id: String,
    val format: String,
    val manufacturer: String,
    val fixture: String,
    val shortName: String,
    val author: String,
    val modes: List<MaProfileReader.Mode>,
    val fileName: String,
    val fileSize: Long,
    /** Secondes depuis 1970. */
    val importedAt: Double,
    val hasModel3D: Boolean,
    /** Présente seulement pour une révision téléchargée de GDTF Share. */
    val share: MaShareInfo? = null
)

/** Erreur d'import d'UN fichier (les erreurs ne bloquent jamais la suite du lot). */
sealed class MaImportError {
    object Protected : MaImportError()
    object Unreadable : MaImportError()
    object Unknown : MaImportError()
    object Empty : MaImportError()
    /**
     * Objet trop volumineux, refusé AVANT lecture ; `megabytes` = taille arrondie,
     * `maxMegabytes` = plafond de l'objet (64 pour un fichier ou une entrée, 512 pour une archive).
     */
    data class TooLarge(val megabytes: Int, val maxMegabytes: Int = (MaProfileReader.MAX_FILE_BYTES / (1024 * 1024)).toInt()) : MaImportError()
    object Io : MaImportError()
    /** Archive `.zip` illisible ou corrompue. */
    object ZipUnreadable : MaImportError()
    /** Archive sans aucun profil candidat (après développement de ses imbriquées). */
    object ZipEmpty : MaImportError()
    /** Garde-fou d'archive : lecture arrêtée après `count` fichiers. */
    data class ZipLimit(val count: Int) : MaImportError()

    companion object {
        fun of(code: MaProfileReader.ErrorCode): MaImportError = when (code) {
            MaProfileReader.ErrorCode.PROTECTED -> Protected
            MaProfileReader.ErrorCode.UNREADABLE -> Unreadable
            MaProfileReader.ErrorCode.UNKNOWN -> Unknown
            MaProfileReader.ErrorCode.EMPTY -> Empty
        }

        fun of(e: MaZipImport.ArchiveError): MaImportError = when (e) {
            MaZipImport.ArchiveError.Unreadable -> ZipUnreadable
            MaZipImport.ArchiveError.Empty -> ZipEmpty
            is MaZipImport.ArchiveError.Limit -> ZipLimit(e.count)
            is MaZipImport.ArchiveError.TooLarge -> TooLarge(e.megabytes, e.maxMegabytes)
        }
    }
}

/**
 * ORDRE d'enregistrement d'un type créé depuis la bibliothèque MA (§5.7-5.8, §4.3) :
 * (1) [register] bibliothèque custom + table projet ; (2) [assign] affectation à la
 * sélection (patch PUIS customFixtures, poussés par le commit) ; (3) SEULEMENT ENSUITE
 * [awaitUpload], l'attente (bornée) de l'envoi de la 3D. Un envoi lent ou hors ligne ne
 * retarde donc plus l'affectation, et l'annulation de l'attente (écran quitté) ne la perd
 * plus. Renvoie false si l'envoi a échoué / n'a pas fini (note « La 3D n'a pas pu être
 * envoyée… »), true sinon (envoyé, ou rien à envoyer).
 */
suspend fun saveMaTypeOrdered(
    register: () -> Unit,
    assign: (() -> Unit)?,
    awaitUpload: suspend () -> Boolean?
): Boolean {
    register()
    assign?.invoke()
    return awaitUpload() != false
}

/** Bilan d'un lot (§2.4, SPEC-ma-gdtfshare.md §5.8), toujours affiché. */
data class MaImportReport(
    val added: Int,
    val duplicates: Int,
    val ignored: Int,
    val errors: List<Pair<String, MaImportError>>,
    val stopped: Boolean
) {
    /** Profils protégés `.pxml` rencontrés (ligne « dont N profils protégés (.pxml) »). */
    val protectedCount: Int get() = errors.count { it.second == MaImportError.Protected }
}

/** Un fichier candidat (dossier local en test JVM, document SAF, entrée d'archive). */
interface MaImportSource {
    /** Nom du fichier tel qu'importé (sans dossier). */
    val name: String
    /** Taille DÉCLARÉE (null si inconnue). */
    val size: Long?
    fun read(): ByteArray
    /** Nom affiché dans le bilan (`archive.zip › dossier/fichier.xml` pour une entrée d'archive). */
    val displayName: String get() = name
    /**
     * Appelé par l'import quand il en a FINI avec cette source (lue, refusée ou sautée).
     * Le DERNIER candidat d'une archive y ferme l'archive (voir [ZipEntryImportSource]).
     */
    fun finished() {}
}

/**
 * Échec de lecture QUALIFIÉ d'une source (entrée d'archive) : l'erreur à porter au bilan,
 * éventuellement sous un autre nom (l'archive pour `zipLimit`) ; `skip` = archive déjà
 * arrêtée, source sautée sans bruit.
 */
class MaImportException(
    val error: MaImportError?,
    val displayName: String? = null,
    val skip: Boolean = false
) : java.io.IOException()

/**
 * Lot prêt à importer : candidats (archives développées), fichiers ignorés, erreurs
 * trouvées en préparant le lot (archives illisibles, vides, trop grosses…), et le
 * ménage de fin de lot (fermer les archives, effacer les copies temporaires) — FILET
 * seulement : chaque archive est déjà fermée dès son dernier candidat traité
 * ([MaImportSource.finished]) ; il ne reste que celles d'un import arrêté avant.
 * `cancelled` : préparation interrompue (« Arrêter », écran quitté) — le bilan dit « arrêté ».
 *
 * Un lot de plusieurs fichiers ou archives s'assemble avec [Builder] (listes accumulées,
 * ménage en boucle) : PAS par additions successives de lots, qui recopiaient toute la liste
 * à chaque fichier et chaînaient les ménages (mémoire au carré du nombre de fichiers :
 * OutOfMemoryError vers 16 000 fichiers).
 */
data class MaImportPlan(
    val sources: List<MaImportSource>,
    val ignored: Int,
    val errors: List<Pair<String, MaImportError>> = emptyList(),
    val cleanup: () -> Unit = {},
    val cancelled: Boolean = false
) {
    /** Assemblage LINÉAIRE d'un lot (un fichier, une archive développée, des ignorés à la fois). */
    class Builder {
        private val sources = ArrayList<MaImportSource>()
        private var ignored = 0
        private val errors = ArrayList<Pair<String, MaImportError>>()
        private val cleanups = ArrayList<() -> Unit>()
        private var cancelled = false

        fun addSource(s: MaImportSource) = apply { sources.add(s) }
        fun addIgnored(n: Int = 1) = apply { ignored += n }
        fun addError(name: String, e: MaImportError) = apply { errors.add(name to e) }
        fun markCancelled() = apply { cancelled = true }

        /** Ajoute un lot déjà préparé (archive développée) ; son ménage est GARDÉ (filet de fin de lot). */
        fun add(p: MaImportPlan) = apply {
            sources.addAll(p.sources); ignored += p.ignored; errors.addAll(p.errors)
            cleanups.add(p.cleanup)
            if (p.cancelled) cancelled = true
        }

        fun build(): MaImportPlan {
            val all = cleanups.toTypedArray()
            return MaImportPlan(sources, ignored, errors, { for (c in all) runCatching { c() } }, cancelled)
        }
    }
}

/** Fichier local (tests JVM, dossiers de l'appareil) ; `name` = nom présenté au pipeline. */
class FileImportSource(private val file: File, override val name: String = file.name) : MaImportSource {
    override val size: Long? get() = file.length()
    override fun read(): ByteArray = file.readBytes()
}

/**
 * Entrée candidate d'une archive `.zip` (lue en mémoire par la session de l'archive).
 * `release` (porté par le DERNIER candidat de l'archive seulement) : ménage de l'archive —
 * session fermée (imbriquée, source, table centrale rendues) et copie temporaire effacée —
 * fait dès que ce candidat est traité ([finished]), pas à la fin du lot (SPEC-ma-gdtfshare.md
 * §5.2 : pic mémoire = une archive imbriquée + une entrée, même pour un lot de plusieurs
 * archives). Une fois traitée, la source lâche aussi son candidat (table de l'imbriquée).
 */
class ZipEntryImportSource(
    candidate: MaZipImport.Candidate,
    session: MaZipImport.Session,
    private var release: (() -> Unit)? = null
) : MaImportSource {
    override val name: String = candidate.baseName
    override val size: Long? = candidate.declaredSize
    override val displayName: String = candidate.displayName
    private var candidate: MaZipImport.Candidate? = candidate
    /** Session de l'archive, jusqu'à ce que la source soit traitée (lue par les tests). */
    internal var session: MaZipImport.Session? = session
        private set

    override fun read(): ByteArray {
        val c = candidate
        val s = session
        if (c == null || s == null) throw MaImportException(null, skip = true)
        return when (val r = s.read(c)) {
            is MaZipImport.EntryRead.Ok -> r.bytes
            is MaZipImport.EntryRead.TooLarge -> throw MaImportException(MaImportError.TooLarge(r.megabytes))
            MaZipImport.EntryRead.Unreadable -> throw MaImportException(MaImportError.Unreadable)
            is MaZipImport.EntryRead.Limit -> throw MaImportException(MaImportError.ZipLimit(r.count), s.archiveName)
            MaZipImport.EntryRead.Skipped -> throw MaImportException(null, skip = true)
        }
    }

    override fun finished() {
        candidate = null
        session = null
        val r = release ?: return
        release = null
        runCatching { r() }
    }
}

/** Recherche / tri (§2.5) — purs, partagés par l'écran et les tests. */
object MaSearch {
    private val COMBINING = Regex("[\\u0300-\\u036f]")
    private val SPACES = Regex("\\s+")
    const val DISPLAY_CAP = 200

    /** NFD, marques combinantes retirées, minuscules, espaces réduits, trim. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING, "").lowercase()
            .replace(SPACES, " ").trim()

    /** Mots d'une requête (pliée, mots vides retirés). */
    fun words(query: String): List<String> = fold(query).split(" ").filter { it.isNotEmpty() }

    /** Chaque mot de la requête est contenu dans le fabricant OU dans le modèle. */
    fun matches(e: MaLibraryEntry, query: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val m = fold(e.manufacturer); val f = fold(e.fixture)
        return words.all { m.contains(it) || f.contains(it) }
    }

    val ORDER: Comparator<MaLibraryEntry> = compareBy<MaLibraryEntry>(
        { fold(it.manufacturer) }, { fold(it.fixture) }, { fold(it.author) }, { it.importedAt }, { it.id }
    )

    /** Clés pliées calculées UNE fois par entrée (une bibliothèque compte des milliers de profils). */
    private class Keyed(val e: MaLibraryEntry) {
        val m = fold(e.manufacturer); val f = fold(e.fixture); val a = fold(e.author)
    }

    private val KEYED_ORDER: Comparator<Keyed> = compareBy<Keyed>(
        { it.m }, { it.f }, { it.a }, { it.e.importedAt }, { it.e.id }
    )

    /** Filtre (chaque mot dans le fabricant OU le modèle) puis tri §2.5. */
    fun search(all: List<MaLibraryEntry>, query: String): List<MaLibraryEntry> {
        val words = words(query)
        return all.asSequence().map { Keyed(it) }
            .filter { k -> words.all { k.m.contains(it) || k.f.contains(it) } }
            .sortedWith(KEYED_ORDER).map { it.e }.toList()
    }
}

/** Issue d'un téléchargement GDTF Share importé dans la bibliothèque (SPEC-ma-gdtfshare.md §4.3). */
sealed class MaShareImportOutcome {
    data class Added(val id: String) : MaShareImportOutcome()
    data class AlreadyPresent(val id: String) : MaShareImportOutcome()
    data class Failed(val error: MaImportError) : MaShareImportOutcome()
}

/**
 * Stockage + import, PUR côté fichiers (racine injectée → testable en JVM sans
 * `DocumentFile`). Thread : à appeler HORS thread principal.
 */
class MaLibraryStore(private val root: File) {

    private val lock = Any()
    private fun indexFile() = File(root, "library.json")
    private fun filesDir() = File(root, "files")
    fun gdtfFile(id: String) = File(filesDir(), "$id.gdtf")

    // L'INDEX DISQUE est la seule source de vérité : toute écriture le RELIT sous le verrou
    // et le modifie (ajout fusionné par id, ou retrait d'un id) — jamais d'écrasement par un
    // instantané pris en mémoire. Sinon un import lancé avant la fin du chargement paresseux
    // (liste encore vide) réécrivait `library.json` avec ses seuls nouveaux profils, et une
    // suppression faite pendant un import était annulée par le paquet suivant.

    fun load(): List<MaLibraryEntry> = synchronized(lock) { loadLocked() }

    private fun loadLocked(): List<MaLibraryEntry> {
        val text = runCatching { indexFile().readText() }.getOrNull() ?: return emptyList()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = o.optJSONArray("profiles") ?: return emptyList()
        // Tolérant entrée par entrée : une entrée illisible ne vide pas la bibliothèque.
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { decodeEntry(it) } }
    }

    fun save(entries: List<MaLibraryEntry>) = synchronized(lock) { saveLocked(entries) }

    private fun saveLocked(entries: List<MaLibraryEntry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(encodeEntry(e))
        val text = JSONObject().put("version", 1).put("profiles", arr).toString()
        root.mkdirs()
        val tmp = File(root, "library.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(indexFile())) { indexFile().delete(); tmp.renameTo(indexFile()) }
    }

    /**
     * AJOUTE [added] à l'index disque relu sous le verrou (fusion par id : rien n'est
     * jamais retiré ni dupliqué) ; renvoie l'index complet persisté.
     */
    private fun appendToIndex(added: List<MaLibraryEntry>): List<MaLibraryEntry> = synchronized(lock) {
        val disk = loadLocked()
        if (added.isEmpty()) return disk
        val ids = disk.mapTo(HashSet()) { it.id }
        val merged = ArrayList<MaLibraryEntry>(disk.size + added.size).apply { addAll(disk) }
        for (e in added) if (ids.add(e.id)) merged.add(e)
        saveLocked(merged)
        merged
    }

    fun gdtfBytes(id: String): ByteArray? = runCatching { gdtfFile(id).readBytes() }.getOrNull()

    /** Retire une entrée (de l'index DISQUE relu) et ses octets ; renvoie la liste restante persistée. */
    fun remove(id: String): List<MaLibraryEntry> = synchronized(lock) {
        val rest = loadLocked().filter { it.id != id }
        runCatching { gdtfFile(id).delete() }
        saveLocked(rest)
        rest
    }

    /** Entrée déjà téléchargée pour cette révision GDTF Share (§4.3 étape 1), d'après l'index disque. */
    fun entryForShareRid(rid: Int): MaLibraryEntry? = load().firstOrNull { it.share?.rid == rid }

    /**
     * Importe une révision GDTF Share TÉLÉCHARGÉE (§4.1 étapes 5-7, §4.3) EXACTEMENT comme
     * un fichier importé : SHA-256 → déjà présent (un fichier importé avant à la main reçoit
     * la clé `share`, rien d'autre ne change) ; sinon lecteur de profils → entrée nommée
     * `<M>@<F>@rid<rid>.gdtf` (fabricant et modèle LUS dans le fichier). Persistance
     * immédiate ; renvoie l'issue + la bibliothèque complète (index disque).
     */
    fun importShareRevision(
        entry: GdtfShareEntry,
        bytes: ByteArray,
        now: () -> Double = { System.currentTimeMillis() / 1000.0 }
    ): Pair<MaShareImportOutcome, List<MaLibraryEntry>> {
        if (bytes.size > MaProfileReader.MAX_FILE_BYTES)
            return MaShareImportOutcome.Failed(MaImportError.TooLarge(megabytes(bytes.size.toLong()))) to load()
        val id = ContentHash.sha256Hex(bytes)
        val share = MaShareInfo(entry.rid, entry.uuid, entry.revision, entry.uploader, entry.date)
        synchronized(lock) {
            val disk = loadLocked()
            val i = disk.indexOfFirst { it.id == id }
            if (i >= 0) {
                if (disk[i].share != null) return MaShareImportOutcome.AlreadyPresent(id) to disk
                val updated = disk.toMutableList().also { it[i] = it[i].copy(share = share) }
                saveLocked(updated)
                return MaShareImportOutcome.AlreadyPresent(id) to updated
            }
        }
        val p = when (val r = MaProfileReader.read(bytes, "rid${entry.rid}.gdtf")) {
            is MaProfileReader.Result.Error -> return MaShareImportOutcome.Failed(MaImportError.of(r.code)) to load()
            is MaProfileReader.Result.Ok -> r.profile
        }
        val has3D = p.format == "gdtf" && runCatching { GdtfLoader.hasThreeDModel(bytes) }.getOrDefault(false)
        if (has3D) runCatching { filesDir().mkdirs(); gdtfFile(id).writeBytes(bytes) }
        val e = MaLibraryEntry(
            id, p.format, p.manufacturer, p.fixture, p.shortName, p.author, p.modes,
            MaShareSearch.fileName(p.manufacturer, p.fixture, entry.rid), bytes.size.toLong(), now(), has3D, share
        )
        return MaShareImportOutcome.Added(id) to appendToIndex(listOf(e))
    }

    /**
     * [import] d'un lot préparé. Chaque archive est fermée dès son dernier candidat traité ;
     * le ménage de fin de lot n'est qu'un filet (archives d'un import arrêté avant, copies
     * temporaires).
     * Préparation interrompue ([MaImportPlan.cancelled]) : rien n'est lu, le bilan dit « arrêté ».
     */
    fun import(
        plan: MaImportPlan,
        now: () -> Double = { System.currentTimeMillis() / 1000.0 },
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onBatch: (List<MaLibraryEntry>) -> Unit = {}
    ): Pair<MaImportReport, List<MaLibraryEntry>> = try {
        val stop: () -> Boolean = if (plan.cancelled) ({ true }) else isCancelled
        val (report, lib) = import(plan.sources, plan.ignored, now, stop, onProgress, onBatch, plan.errors)
        (if (plan.cancelled) report.copy(stopped = true) else report) to lib
    } finally {
        runCatching { plan.cleanup() }
    }

    /**
     * Importe un LOT (§2.4), en série ; renvoie le bilan + la bibliothèque complète (index
     * disque). Les doublons sont jugés contre l'index DISQUE (relu ici : un appelant n'a pas
     * à avoir fini de charger la liste). Persistance par paquets de 100 entrées et à la fin,
     * en n'AJOUTANT que les nouveaux profils à l'index relu. `isCancelled` est lu ENTRE deux
     * fichiers (arrêt propre : ce qui est importé reste). `preErrors` : erreurs trouvées en
     * préparant le lot (archives), placées en tête du bilan.
     */
    fun import(
        sources: List<MaImportSource>,
        ignoredCount: Int,
        now: () -> Double = { System.currentTimeMillis() / 1000.0 },
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onBatch: (List<MaLibraryEntry>) -> Unit = {},
        preErrors: List<Pair<String, MaImportError>> = emptyList()
    ): Pair<MaImportReport, List<MaLibraryEntry>> {
        val pending = ArrayList<MaLibraryEntry>()
        val seen = load().mapTo(HashSet()) { it.id }
        var added = 0; var dupes = 0
        val errors = ArrayList<Pair<String, MaImportError>>(preErrors)
        var stopped = false
        onProgress(0, sources.size)
        for ((i, src) in sources.withIndex()) {
            if (isCancelled()) { stopped = true; break }
            try {
                val declared = src.size
                if (declared != null && declared > MaProfileReader.MAX_FILE_BYTES) {
                    errors.add(src.displayName to MaImportError.TooLarge(megabytes(declared)))
                } else if (src.name.lowercase().endsWith(".pxml")) {
                    // Protégé : refusé sans lire ni décompresser (le lecteur dirait la même chose).
                    errors.add(src.displayName to MaImportError.Protected)
                } else {
                    var failure: Pair<String, MaImportError>? = null
                    val bytes = try {
                        src.read()
                    } catch (e: MaImportException) {
                        if (!e.skip && e.error != null) failure = (e.displayName ?: src.displayName) to e.error
                        null
                    } catch (e: Exception) {
                        failure = src.displayName to MaImportError.Io
                        null
                    }
                    when {
                        bytes == null -> failure?.let { errors.add(it) }
                        bytes.size > MaProfileReader.MAX_FILE_BYTES ->
                            errors.add(src.displayName to MaImportError.TooLarge(megabytes(bytes.size.toLong())))
                        else -> {
                            val id = ContentHash.sha256Hex(bytes)
                            if (id in seen) dupes++
                            else when (val r = MaProfileReader.read(bytes, src.name)) {
                                is MaProfileReader.Result.Error -> errors.add(src.displayName to MaImportError.of(r.code))
                                is MaProfileReader.Result.Ok -> {
                                    val p = r.profile
                                    val has3D = p.format == "gdtf" && runCatching { GdtfLoader.hasThreeDModel(bytes) }.getOrDefault(false)
                                    if (has3D) runCatching {
                                        filesDir().mkdirs(); gdtfFile(id).writeBytes(bytes)
                                    }
                                    pending.add(MaLibraryEntry(id, p.format, p.manufacturer, p.fixture, p.shortName, p.author,
                                        p.modes, src.name, bytes.size.toLong(), now(), has3D))
                                    seen.add(id); added++
                                    if (pending.size >= 100) { val merged = appendToIndex(pending); pending.clear(); onBatch(merged) }
                                }
                            }
                        }
                    }
                }
            } finally {
                // Source traitée (lue, refusée ou sautée) : le DERNIER candidat d'une archive
                // ferme ici son archive (imbriquée, source, table, copie temporaire), pas à la
                // fin du lot.
                runCatching { src.finished() }
            }
            onProgress(i + 1, sources.size)
        }
        val lib = appendToIndex(pending)
        return MaImportReport(added, dupes, ignoredCount, errors, stopped) to lib
    }

    private fun megabytes(bytes: Long): Int = ((bytes + 1024 * 1024 - 1) / (1024 * 1024)).toInt()

    companion object {
        /** Extensions retenues (casse ignorée) ; les `.pxml` sont gardés pour être COMPTÉS en erreur. */
        val EXTENSIONS = MaZipImport.PROFILE_EXTENSIONS

        /** Fichier caché ou dossier `__MACOSX` : ignoré EN SILENCE. */
        fun isSilentlySkipped(name: String): Boolean = name.startsWith(".") || name == "__MACOSX"

        fun isCandidate(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

        /** Archive `.zip` (casse ignorée) : développée (§5.2). */
        fun isArchive(name: String): Boolean = name.substringAfterLast('.', "").lowercase() == "zip"

        /**
         * Développe une archive (profondeur 0) déjà ouverte : candidats, ignorés, erreurs
         * d'archive (nommées d'après `archiveName`) ; `afterClose` est appelé au ménage
         * (copie temporaire à effacer). Le ménage de l'archive est fait UNE fois, au plus
         * tôt : par son DERNIER candidat dès qu'il est traité ([ZipEntryImportSource]), ou
         * tout de suite si elle n'a aucun candidat (sa source est déjà fermée par la
         * préparation) ; le ménage du lot n'est qu'un filet. `limits` : plafonds (tests).
         */
        fun planArchive(
            archiveName: String,
            src: ZipCentralReader.Source,
            isCancelled: () -> Boolean = { false },
            limits: MaZipImport.Limits = MaZipImport.Limits(),
            afterClose: () -> Unit = {}
        ): MaImportPlan {
            val p = MaZipImport.plan(archiveName, src, isCancelled, limits)
            val session = p.session
            var released = false
            val release: () -> Unit = {
                if (!released) {
                    released = true
                    runCatching { session?.close() }
                    runCatching { afterClose() }
                }
            }
            val sources = if (session == null) emptyList() else {
                val last = p.candidates.lastIndex
                p.candidates.mapIndexed { i, c -> ZipEntryImportSource(c, session, if (i == last) release else null) }
            }
            if (sources.isEmpty()) release()
            return MaImportPlan(
                sources = sources,
                ignored = p.ignored,
                errors = p.errors.map { (n, e) -> n to MaImportError.of(e) },
                cleanup = release,
                cancelled = p.cancelled
            )
        }

        /** Archive locale (tests JVM, fichier déjà copié en cache). */
        fun planArchiveFile(
            file: File,
            archiveName: String = file.name,
            deleteAfter: Boolean = false,
            isCancelled: () -> Boolean = { false },
            limits: MaZipImport.Limits = MaZipImport.Limits()
        ): MaImportPlan {
            val src = runCatching { ZipCentralReader.FileSource.open(file) }.getOrNull()
            if (src == null) {
                if (deleteAfter) file.delete()          // rien à lire : copie effacée tout de suite
                return MaImportPlan(emptyList(), 0, listOf(archiveName to MaImportError.ZipUnreadable))
            }
            return planArchive(archiveName, src, isCancelled, limits) { if (deleteAfter) file.delete() }
        }

        /**
         * Énumère un dossier LOCAL récursivement (tests JVM) : candidats triés, nombre de
         * fichiers « autres formats », archives `.zip` développées à leur place.
         */
        fun collect(dir: File, isCancelled: () -> Boolean = { false }): MaImportPlan {
            val out = ArrayList<File>(); var ignored = 0
            fun walk(d: File) {
                val kids = d.listFiles()?.sortedBy { it.name } ?: return
                for (k in kids) {
                    if (isSilentlySkipped(k.name)) continue
                    if (k.isDirectory) walk(k)
                    else if (isCandidate(k.name) || isArchive(k.name)) out.add(k) else ignored++
                }
            }
            walk(dir)
            val plan = MaImportPlan.Builder().addIgnored(ignored)
            for (f in out) {
                if (isCancelled()) { plan.markCancelled(); break }
                if (!isArchive(f.name)) plan.addSource(FileImportSource(f))
                else if (f.length() > MaZipImport.MAX_ARCHIVE_BYTES) plan.addError(f.name, archiveTooLarge(f.length()))
                else plan.add(planArchiveFile(f, isCancelled = isCancelled))
            }
            return plan.build()
        }

        /**
         * Décision pour UN document choisi ou reçu (pure : testable en JVM, appelée par
         * [MaSafEnumerator.fromDocuments] avec des fonctions sur l'URI) : candidat, archive
         * développée ou ignoré. Pour un document REÇU, la décision du routeur (§5.3) est
         * respectée : un GDTF renommé `.zip` (T12 : aiguillé « gdtf ») est importé comme UN
         * fichier `.gdtf` — avec sa 3D et le même identifiant que le vrai `.gdtf` — et non
         * développé comme une archive (qui n'en gardait que `description.xml`, sans 3D).
         * Nom sans extension : complété d'après le routeur (`.gdtf`, `.zip`, `.xml`).
         */
        fun planDocument(
            name: String,
            received: Boolean,
            route: () -> IncomingFileRouter.Route?,
            startsWithPK: () -> Boolean,
            asSource: (name: String) -> MaImportSource,
            asArchive: (name: String) -> MaImportPlan,
            into: MaImportPlan.Builder
        ) {
            val n = importName(name, received, route, startsWithPK)
            when {
                isSilentlySkipped(n) -> {}
                isArchive(n) -> into.add(asArchive(n))
                isCandidate(n) -> into.addSource(asSource(n))
                else -> into.addIgnored()
            }
        }

        /** Nom sous lequel un document suit le pipeline (voir [planDocument]). */
        fun importName(
            name: String,
            received: Boolean,
            route: () -> IncomingFileRouter.Route?,
            startsWithPK: () -> Boolean
        ): String {
            if (!name.contains('.')) return when (route()) {
                IncomingFileRouter.Route.GDTF -> "$name.gdtf"
                IncomingFileRouter.Route.MA_PROFILES -> if (startsWithPK()) "$name.zip" else "$name.xml"
                else -> name
            }
            if (received && isArchive(name) && route() == IncomingFileRouter.Route.GDTF)
                return name.substringBeforeLast('.') + ".gdtf"
            return name
        }

        /** `Fichier trop volumineux (N Mo, maximum 512 Mo).` pour une archive. */
        fun archiveTooLarge(bytes: Long?): MaImportError.TooLarge = MaImportError.TooLarge(
            if (bytes != null) MaZipImport.megabytes(bytes) else (MaZipImport.MAX_ARCHIVE_BYTES / (1024 * 1024)).toInt() + 1,
            (MaZipImport.MAX_ARCHIVE_BYTES / (1024 * 1024)).toInt()
        )

        fun of(ctx: Context) = MaLibraryStore(File(ctx.filesDir, "maLibrary"))

        private fun encodeEntry(e: MaLibraryEntry): JSONObject = JSONObject()
            .put("id", e.id).put("format", e.format).put("manufacturer", e.manufacturer)
            .put("fixture", e.fixture).put("shortName", e.shortName).put("author", e.author)
            .put("modes", JSONArray().apply {
                e.modes.forEach { m ->
                    put(JSONObject().put("name", m.name).put("channels", JSONArray(m.channels)).put("footprint", m.footprint))
                }
            })
            .put("fileName", e.fileName).put("fileSize", e.fileSize)
            .put("importedAt", e.importedAt).put("hasModel3D", e.hasModel3D)
            .apply {
                e.share?.let { s ->
                    put("share", JSONObject().put("rid", s.rid).put("uuid", s.uuid).put("revision", s.revision)
                        .put("uploader", s.uploader).put("lastModified", s.lastModified))
                }
            }

        private fun decodeEntry(o: JSONObject): MaLibraryEntry? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val modesArr = o.optJSONArray("modes") ?: return null
            val modes = (0 until modesArr.length()).mapNotNull { i ->
                val m = modesArr.optJSONObject(i) ?: return@mapNotNull null
                val ch = m.optJSONArray("channels") ?: JSONArray()
                MaProfileReader.Mode(m.optString("name"), (0 until ch.length()).map { ch.optString(it) }, m.optInt("footprint"))
            }
            return MaLibraryEntry(
                id, o.optString("format"), o.optString("manufacturer"), o.optString("fixture"),
                o.optString("shortName"), o.optString("author"), modes, o.optString("fileName"),
                o.optLong("fileSize"), o.optDouble("importedAt", 0.0), o.optBoolean("hasModel3D", false),
                decodeShare(o.opt("share"))
            )
        }

        /**
         * Décodage TOLÉRANT de `share` (§4.2) : absent ou non objet → null ; `rid` entier > 0,
         * flottant entier ou chaîne de chiffres, sinon null — l'ENTRÉE, elle, est toujours gardée.
         */
        internal fun decodeShare(v: Any?): MaShareInfo? {
            val s = v as? JSONObject ?: return null
            val rid = GdtfShareList.integral(s.opt("rid"))?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
            val uploader = (s.opt("uploader") as? String)?.takeIf { it == "manufacturer" || it == "user" } ?: ""
            val lastModified = (s.opt("lastModified") as? Number)?.let { GdtfShareList.integral(it) }?.takeIf { it >= 0 } ?: 0L
            return MaShareInfo(rid, s.opt("uuid") as? String ?: "", s.opt("revision") as? String ?: "", uploader, lastModified)
        }
    }
}

// ---- Énumération SAF (appareil) ---------------------------------------------------------

/** Document choisi par le sélecteur (OpenMultipleDocuments / OpenDocumentTree) ou reçu. */
class UriImportSource(
    private val ctx: Context, val uri: Uri, override val name: String, override val size: Long?
) : MaImportSource {
    override fun read(): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException("flux nul")
}

object MaSafEnumerator {

    /** Copies temporaires des archives reçues : `cacheDir/maImport/<uuid>.zip`, effacées à la fin. */
    private fun importCacheDir(ctx: Context) = File(ctx.cacheDir, "maImport")

    /** Ménage des copies laissées par un import interrompu (aucun import ne tourne alors). */
    fun purgeCache(ctx: Context) {
        importCacheDir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** Nom et taille déclarée d'un document. */
    fun describe(ctx: Context, u: Uri): Pair<String, Long?> {
        var name: String? = null; var size: Long? = null
        runCatching {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return (name ?: u.lastPathSegment?.substringAfterLast('/') ?: "fichier") to size
    }

    /** Issue d'une copie bornée d'un document en cache. */
    sealed class Copied {
        class Ok(val file: File) : Copied()
        object TooLarge : Copied()
        object Failed : Copied()
    }

    /** Copie le flux d'un document dans `cacheDir/maImport/<uuid>.zip`, au plus `max` octets. */
    fun copyToCache(ctx: Context, uri: Uri, max: Long = MaZipImport.MAX_ARCHIVE_BYTES): Copied {
        val dir = importCacheDir(ctx).apply { mkdirs() }
        val tmp = File(dir, java.util.UUID.randomUUID().toString() + ".zip")
        val copied = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > max) return@runCatching false
                        out.write(buf, 0, n)
                    }
                }
                true
            } ?: throw java.io.IOException("flux nul")
        }
        return when (copied.getOrNull()) {
            true -> Copied.Ok(tmp)
            false -> { tmp.delete(); Copied.TooLarge }
            null -> { tmp.delete(); Copied.Failed }
        }
    }

    /**
     * Archive `.zip` d'un document : copie du flux SAF en cache (≤ 512 Mo, refus AVANT
     * lecture si la taille déclarée dépasse), puis lecture par le répertoire central.
     */
    fun planArchive(
        ctx: Context, uri: Uri, name: String, declared: Long?,
        isCancelled: () -> Boolean = { false }
    ): MaImportPlan {
        if (declared != null && declared > MaZipImport.MAX_ARCHIVE_BYTES)
            return MaImportPlan(emptyList(), 0, listOf(name to MaLibraryStore.archiveTooLarge(declared)))
        return when (val c = copyToCache(ctx, uri)) {
            is Copied.Ok -> MaLibraryStore.planArchiveFile(c.file, name, deleteAfter = true, isCancelled = isCancelled)
            Copied.TooLarge -> MaImportPlan(emptyList(), 0, listOf(name to MaLibraryStore.archiveTooLarge(null)))
            Copied.Failed -> MaImportPlan(emptyList(), 0, listOf(name to MaImportError.Io))
        }
    }

    /** Les deux premiers octets d'un document sont `PK` (zip). */
    private fun startsWithPK(ctx: Context, u: Uri): Boolean {
        val head = runCatching {
            ctx.contentResolver.openInputStream(u)?.use { ins -> ByteArray(2).also { ins.read(it) } }
        }.getOrNull()
        return head != null && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }

    /**
     * Fichiers choisis un par un, ou REÇUS (`received` : « Ouvrir avec », partage — la
     * décision du routeur est alors respectée, voir [MaLibraryStore.planDocument]) :
     * candidats, archives développées, ignorés.
     */
    fun fromDocuments(
        ctx: Context, uris: List<Uri>,
        received: Boolean = false,
        isCancelled: () -> Boolean = { false }
    ): MaImportPlan {
        val plan = MaImportPlan.Builder()
        for (u in uris) {
            if (isCancelled()) { plan.markCancelled(); break }
            val (n0, size) = describe(ctx, u)
            MaLibraryStore.planDocument(
                name = n0,
                received = received,
                route = { runCatching { IncomingFiles.route(ctx, u) }.getOrNull() },
                startsWithPK = { startsWithPK(ctx, u) },
                asSource = { n -> UriImportSource(ctx, u, n, size) },
                asArchive = { n -> planArchive(ctx, u, n, size, isCancelled) },
                into = plan
            )
        }
        return plan.build()
    }

    /**
     * Dossier ENTIER (OpenDocumentTree) parcouru RÉCURSIVEMENT via DocumentsContract
     * (fichiers cachés et `__MACOSX` ignorés en silence ; `.zip` développées). Hors thread
     * principal.
     */
    fun fromTree(ctx: Context, treeUri: Uri, isCancelled: () -> Boolean = { false }): MaImportPlan {
        class Found(val path: String, val name: String, val uri: Uri, val size: Long?)
        val out = ArrayList<Found>(); var ignored = 0
        val cr = ctx.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return MaImportPlan(emptyList(), 0)
        val stack = ArrayDeque<Pair<String, String>>()   // (documentId, chemin)
        stack.addLast(rootId to "")
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE
        )
        while (stack.isNotEmpty() && !isCancelled()) {
            val (docId, path) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            runCatching {
                cr.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (MaLibraryStore.isSilentlySkipped(name)) continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(id to "$path/$name")
                        } else if (MaLibraryStore.isCandidate(name) || MaLibraryStore.isArchive(name)) {
                            val size = if (c.isNull(3)) null else c.getLong(3)
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            out.add(Found("$path/$name", name, uri, size))
                        } else ignored++
                    }
                }
            }
        }
        val plan = MaImportPlan.Builder().addIgnored(ignored)
        if (isCancelled()) plan.markCancelled()          // parcours du dossier interrompu
        for (f in out.sortedBy { it.path.lowercase() }) {
            if (isCancelled()) { plan.markCancelled(); break }
            if (MaLibraryStore.isArchive(f.name)) plan.add(planArchive(ctx, f.uri, f.name, f.size, isCancelled))
            else plan.addSource(UriImportSource(ctx, f.uri, f.name, f.size))
        }
        return plan.build()
    }
}

/**
 * Aiguillage d'un document REÇU sur l'appareil (« Ouvrir avec », partage) : le routeur pur
 * ([IncomingFileRouter]) sur le nom, l'en-tête et, pour un zip, la table centrale lue par
 * positions (descripteur « seekable ») ou, à défaut, sur une copie en cache (≤ 512 Mo).
 * Hors thread principal.
 */
object IncomingFiles {

    fun route(ctx: Context, uri: Uri): IncomingFileRouter.Route {
        val cr = ctx.contentResolver
        var name: String? = null
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        val ext = IncomingFileRouter.extensionOf(name)
        // Tranché par le nom : aucune lecture (un .mvr part au chargement de show existant).
        when {
            ext == "mvr" -> return IncomingFileRouter.Route.MVR
            ext in IncomingFileRouter.MODEL_EXTENSIONS -> return IncomingFileRouter.Route.MODEL
            ext == "gdtf" -> return IncomingFileRouter.Route.GDTF
            ext == "xmlp" || ext == "pxml" -> return IncomingFileRouter.Route.MA_PROFILES
        }
        val head = runCatching {
            cr.openInputStream(uri)?.use { ins ->
                val b = ByteArray(4096); var n = 0
                while (n < b.size) { val r = ins.read(b, n, b.size - n); if (r <= 0) break; n += r }
                b.copyOf(n)
            }
        }.getOrNull() ?: return IncomingFileRouter.Route.MVR
        var closer: java.io.Closeable? = null
        var tmp: File? = null
        try {
            return IncomingFileRouter.route(name, head) {
                openSeekable(ctx, uri)?.also { closer = it }
                    ?: (MaSafEnumerator.copyToCache(ctx, uri) as? MaSafEnumerator.Copied.Ok)?.file?.let { f ->
                        tmp = f
                        ZipCentralReader.FileSource.open(f).also { closer = it }
                    }
            }
        } finally {
            runCatching { closer?.close() }
            tmp?.delete()
        }
    }

    /** Descripteur à accès direct (fichier local) ; null pour un flux (tube) non « seekable ». */
    private fun openSeekable(ctx: Context, uri: Uri): ZipCentralReader.FileSource? = runCatching {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val fis = java.io.FileInputStream(pfd.fileDescriptor)
        val ch = fis.channel
        val ok = runCatching { ch.size() > 0 && ch.position(0) != null }.getOrDefault(false)
        if (!ok) { runCatching { fis.close() }; runCatching { pfd.close() }; return null }
        ZipCentralReader.FileSource(ch, java.io.Closeable { runCatching { fis.close() }; runCatching { pfd.close() } })
    }.getOrNull()
}

// ---- État réactif ------------------------------------------------------------------------

/**
 * État de l'écran « Bibliothèque MA » : entrées chargées PARESSEUSEMENT (première
 * ouverture, hors thread principal), progression d'import, bilan.
 */
class MaLibraryState {
    val entries = mutableStateListOf<MaLibraryEntry>()
    var loaded by mutableStateOf(false)
    /** (fait, total) pendant un import ; null hors import. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var report by mutableStateOf<MaImportReport?>(null)
    @Volatile var cancelRequested: Boolean = false

    fun replaceAll(list: List<MaLibraryEntry>) {
        entries.clear(); entries.addAll(list)
    }

    fun get(id: String): MaLibraryEntry? = entries.firstOrNull { it.id == id }

    /** Entrée déjà téléchargée pour cette révision (marque « Dans la bibliothèque »). */
    fun entryForShareRid(rid: Int): MaLibraryEntry? = entries.firstOrNull { it.share?.rid == rid }
}

/**
 * Fichiers REÇUS du système (« Ouvrir avec », partage) à importer dans la bibliothèque MA,
 * en FILE D'ATTENTE : un lot par réception, servis l'un après l'autre (chaque lot a sa
 * progression et son bilan — SPEC-ma-gdtfshare.md §5.1).
 */
class MaInbox {
    val batches = mutableStateListOf<List<Uri>>()
    fun enqueue(uris: List<Uri>) { if (uris.isNotEmpty()) batches.add(uris) }
    fun poll(): List<Uri>? = if (batches.isEmpty()) null else batches.removeAt(0)
    val isEmpty: Boolean get() = batches.isEmpty()
}
