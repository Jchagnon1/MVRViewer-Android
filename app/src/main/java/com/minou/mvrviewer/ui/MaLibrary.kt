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
import com.minou.mvrviewer.mvr.MaProfileReader
import com.minou.mvrviewer.sync.ContentHash
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * BIBLIOTHÈQUE MA LOCALE (SPEC-ma.md §2) — profils grandMA3 / grandMA2 / GDTF importés
 * depuis des FICHIERS (MA Fixture Share n'a aucune API publique : l'app ne s'y connecte
 * jamais). Par APPAREIL, NON synchronisée : seuls les TYPES créés depuis un mode voyagent
 * (section customFixtures). Importer et consulter = GRATUIT (lecture).
 *
 *  - Index : `filesDir/maLibrary/library.json` = `{"version":1,"profiles":[entrée…]}` ;
 *  - octets des GDTF qui ont un vrai modèle 3D : `filesDir/maLibrary/files/<id>.gdtf`
 *    (option « La 3D de ce fichier GDTF ») ; les autres profils ne gardent pas le fichier.
 */

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
    val hasModel3D: Boolean
)

/** Erreur d'import d'UN fichier (les erreurs ne bloquent jamais la suite du lot). */
sealed class MaImportError {
    object Protected : MaImportError()
    object Unreadable : MaImportError()
    object Unknown : MaImportError()
    object Empty : MaImportError()
    /** Fichier > 64 Mo, refusé AVANT lecture ; `megabytes` = taille arrondie. */
    data class TooLarge(val megabytes: Int) : MaImportError()
    object Io : MaImportError()

    companion object {
        fun of(code: MaProfileReader.ErrorCode): MaImportError = when (code) {
            MaProfileReader.ErrorCode.PROTECTED -> Protected
            MaProfileReader.ErrorCode.UNREADABLE -> Unreadable
            MaProfileReader.ErrorCode.UNKNOWN -> Unknown
            MaProfileReader.ErrorCode.EMPTY -> Empty
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

/** Bilan d'un lot (§2.4), toujours affiché. */
data class MaImportReport(
    val added: Int,
    val duplicates: Int,
    val ignored: Int,
    val errors: List<Pair<String, MaImportError>>,
    val stopped: Boolean
)

/** Un fichier candidat (dossier local en test JVM, document SAF sur l'appareil). */
interface MaImportSource {
    /** Nom du fichier tel qu'importé (sans dossier). */
    val name: String
    /** Taille DÉCLARÉE (null si inconnue). */
    val size: Long?
    fun read(): ByteArray
}

/** Fichier local (tests JVM, dossiers de l'appareil). */
class FileImportSource(private val file: File) : MaImportSource {
    override val name: String get() = file.name
    override val size: Long? get() = file.length()
    override fun read(): ByteArray = file.readBytes()
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

    /** Chaque mot de la requête est contenu dans le fabricant OU dans le modèle. */
    fun matches(e: MaLibraryEntry, query: String): Boolean {
        val words = fold(query).split(" ").filter { it.isNotEmpty() }
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
        val words = fold(query).split(" ").filter { it.isNotEmpty() }
        return all.asSequence().map { Keyed(it) }
            .filter { k -> words.all { k.m.contains(it) || k.f.contains(it) } }
            .sortedWith(KEYED_ORDER).map { it.e }.toList()
    }
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

    /**
     * Importe un LOT (§2.4), en série ; renvoie le bilan + la bibliothèque complète (index
     * disque). Les doublons sont jugés contre l'index DISQUE (relu ici : un appelant n'a pas
     * à avoir fini de charger la liste). Persistance par paquets de 100 entrées et à la fin,
     * en n'AJOUTANT que les nouveaux profils à l'index relu. `isCancelled` est lu ENTRE deux
     * fichiers (arrêt propre : ce qui est importé reste).
     */
    fun import(
        sources: List<MaImportSource>,
        ignoredCount: Int,
        now: () -> Double = { System.currentTimeMillis() / 1000.0 },
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onBatch: (List<MaLibraryEntry>) -> Unit = {}
    ): Pair<MaImportReport, List<MaLibraryEntry>> {
        val pending = ArrayList<MaLibraryEntry>()
        val seen = load().mapTo(HashSet()) { it.id }
        var added = 0; var dupes = 0
        val errors = ArrayList<Pair<String, MaImportError>>()
        var stopped = false
        onProgress(0, sources.size)
        for ((i, src) in sources.withIndex()) {
            if (isCancelled()) { stopped = true; break }
            val declared = src.size
            if (declared != null && declared > MaProfileReader.MAX_FILE_BYTES) {
                errors.add(src.name to MaImportError.TooLarge(megabytes(declared)))
            } else {
                val bytes = runCatching { src.read() }.getOrNull()
                when {
                    bytes == null -> errors.add(src.name to MaImportError.Io)
                    bytes.size > MaProfileReader.MAX_FILE_BYTES ->
                        errors.add(src.name to MaImportError.TooLarge(megabytes(bytes.size.toLong())))
                    else -> {
                        val id = ContentHash.sha256Hex(bytes)
                        if (id in seen) dupes++
                        else when (val r = MaProfileReader.read(bytes, src.name)) {
                            is MaProfileReader.Result.Error -> errors.add(src.name to MaImportError.of(r.code))
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
            onProgress(i + 1, sources.size)
        }
        val lib = appendToIndex(pending)
        return MaImportReport(added, dupes, ignoredCount, errors, stopped) to lib
    }

    private fun megabytes(bytes: Long): Int = ((bytes + 1024 * 1024 - 1) / (1024 * 1024)).toInt()

    companion object {
        /** Extensions retenues (casse ignorée) ; les `.pxml` sont gardés pour être COMPTÉS en erreur. */
        val EXTENSIONS = setOf("xml", "xmlp", "gdtf", "pxml")

        /** Fichier caché ou dossier `__MACOSX` : ignoré EN SILENCE. */
        fun isSilentlySkipped(name: String): Boolean = name.startsWith(".") || name == "__MACOSX"

        fun isCandidate(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

        /**
         * Énumère un dossier LOCAL récursivement (tests JVM) : renvoie (candidats triés,
         * nombre de fichiers « autres formats »).
         */
        fun collect(dir: File): Pair<List<MaImportSource>, Int> {
            val out = ArrayList<File>(); var ignored = 0
            fun walk(d: File) {
                val kids = d.listFiles()?.sortedBy { it.name } ?: return
                for (k in kids) {
                    if (isSilentlySkipped(k.name)) continue
                    if (k.isDirectory) walk(k)
                    else if (isCandidate(k.name)) out.add(k) else ignored++
                }
            }
            walk(dir)
            return out.map { FileImportSource(it) } to ignored
        }

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
                o.optLong("fileSize"), o.optDouble("importedAt", 0.0), o.optBoolean("hasModel3D", false)
            )
        }
    }
}

// ---- Énumération SAF (appareil) ---------------------------------------------------------

/** Document choisi par le sélecteur (OpenMultipleDocuments / OpenDocumentTree). */
class UriImportSource(
    private val ctx: Context, val uri: Uri, override val name: String, override val size: Long?
) : MaImportSource {
    override fun read(): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException("flux nul")
}

object MaSafEnumerator {

    /** Fichiers choisis un par un : (candidats, ignorés). */
    fun fromDocuments(ctx: Context, uris: List<Uri>): Pair<List<MaImportSource>, Int> {
        val out = ArrayList<MaImportSource>(); var ignored = 0
        for (u in uris) {
            var name: String? = null; var size: Long? = null
            runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val n = name ?: u.lastPathSegment?.substringAfterLast('/') ?: "fichier"
            if (MaLibraryStore.isSilentlySkipped(n)) continue
            if (MaLibraryStore.isCandidate(n)) out.add(UriImportSource(ctx, u, n, size)) else ignored++
        }
        return out to ignored
    }

    /**
     * Dossier ENTIER (OpenDocumentTree) parcouru RÉCURSIVEMENT via DocumentsContract
     * (fichiers cachés et `__MACOSX` ignorés en silence). Hors thread principal.
     */
    fun fromTree(ctx: Context, treeUri: Uri, isCancelled: () -> Boolean = { false }): Pair<List<MaImportSource>, Int> {
        val out = ArrayList<Pair<String, MaImportSource>>(); var ignored = 0
        val cr = ctx.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList<MaImportSource>() to 0
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
                        } else if (MaLibraryStore.isCandidate(name)) {
                            val size = if (c.isNull(3)) null else c.getLong(3)
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            out.add("$path/$name" to UriImportSource(ctx, uri, name, size))
                        } else ignored++
                    }
                }
            }
        }
        return out.sortedBy { it.first.lowercase() }.map { it.second } to ignored
    }
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
}
