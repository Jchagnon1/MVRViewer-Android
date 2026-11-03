package com.minou.mvrviewer.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GdtfCredentialStore
import com.minou.mvrviewer.mvr.GdtfShareClient
import com.minou.mvrviewer.mvr.GdtfShareEntry
import com.minou.mvrviewer.mvr.GdtfShareList
import com.minou.mvrviewer.mvr.GdtfShareMode
import com.minou.mvrviewer.mvr.MaProfileReader
import com.minou.mvrviewer.mvr.gdtfShareMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/**
 * « CHERCHER SUR GDTF SHARE » DEPUIS LA BIBLIOTHÈQUE MA (SPEC-ma-gdtfshare.md §1-4) :
 * l'API OFFICIELLE de GDTF Share (compte gratuit de l'utilisateur, connexion persistante
 * existante) sert à trouver une révision GDTF et à l'ajouter à la bibliothèque MA LOCALE.
 * Chercher, télécharger, importer = GRATUIT (lecture, rien n'est écrit dans un projet).
 * L'app ne se connecte JAMAIS au site de partage de MA.
 *
 * Ce fichier porte la partie PURE (filtre, tri, lignes, nom de fichier, déroulé réseau
 * avec reconnexion) — testée en JVM — puis la page Compose.
 */

/** Tri mémorisé par appareil (`maShare.sort`). */
enum class MaShareSort(val raw: String) {
    MANUFACTURER("manufacturer"), RECENT("recent"), RATING("rating");

    companion object {
        fun fromRaw(s: String?): MaShareSort = entries.firstOrNull { it.raw == s } ?: MANUFACTURER
    }
}

object MaShareSearch {

    /** Fichier GDTF Share accepté (comme un fichier importé) : 64 Mo. */
    const val MAX_FILE_BYTES: Long = MaProfileReader.MAX_FILE_BYTES
    const val DISPLAY_CAP = MaSearch.DISPLAY_CAP
    const val MAX_MODES_SHOWN = 4
    private const val MIB = 1024L * 1024

    // ---- Saisie de l'empreinte ----

    /** Chiffres seulement, 4 au plus. */
    fun normalizeFootprintInput(s: String): String = s.filter { it in '0'..'9' }.take(4)

    /** Vide ou `0` = pas de filtre. */
    fun footprintFilter(s: String): Int? = normalizeFootprintInput(s).toIntOrNull()?.takeIf { it > 0 }

    // ---- Nom de fichier (§4.2) ----

    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** Caractères interdits (`/ \ : * ? " < > |`, U+0000–U+001F) → `_`, trim ; vide → `GDTF`. */
    fun sanitize(s: String): String =
        s.map { c -> if (c in FORBIDDEN || c.code < 0x20) '_' else c }.joinToString("").trim().ifEmpty { "GDTF" }

    /** `<M>@<F>@rid<rid>.gdtf`, fabricant et modèle LUS dans le fichier. */
    fun fileName(manufacturer: String, fixture: String, rid: Int): String =
        "${sanitize(manufacturer)}@${sanitize(fixture)}@rid$rid.gdtf"

    // ---- Recherche (§3.2) ----

    /** Liste indexée UNE fois (des milliers de révisions : clés pliées précalculées). */
    class Index(val entries: List<GdtfShareEntry>) {
        private class Keyed(val e: GdtfShareEntry) {
            val m = MaSearch.fold(e.manufacturer)
            val f = MaSearch.fold(e.fixture)
        }
        private val keyed = entries.map { Keyed(it) }

        /** Au moins une entrée annonce des modes ; sinon le filtre d'empreinte est désactivé. */
        val providesFootprints: Boolean = GdtfShareList.providesFootprints(entries)

        /** Requête vide ET pas d'empreinte → aucune ligne (texte d'invite). */
        fun search(query: String, footprint: Int?, sort: MaShareSort): List<GdtfShareEntry> {
            val words = MaSearch.words(query)
            if (words.isEmpty() && footprint == null) return emptyList()
            val hits = keyed.filter { k ->
                words.all { k.m.contains(it) || k.f.contains(it) } &&
                    (footprint == null || k.e.modes.any { it.footprint == footprint })
            }
            val byRidDesc = compareByDescending<Keyed> { it.e.rid }
            val cmp: Comparator<Keyed> = when (sort) {
                MaShareSort.MANUFACTURER -> compareBy<Keyed>({ it.m }, { it.f })
                    .thenByDescending { it.e.date }.then(byRidDesc)
                MaShareSort.RECENT -> compareByDescending<Keyed> { it.e.date }
                    .thenBy { it.m }.thenBy { it.f }.then(byRidDesc)
                MaShareSort.RATING -> compareBy<Keyed> { it.e.rating == null }
                    .thenByDescending { it.e.rating ?: 0.0 }.thenByDescending { it.e.date }.then(byRidDesc)
            }
            return hits.sortedWith(cmp).map { it.e }
        }
    }

    // ---- Une ligne de résultat (§3.3) ----

    data class Row(
        val rid: Int,
        /** `<manufacturer> — <fixture>`. */
        val title: String,
        /** Révision trimée (`""` → « Révision sans nom »). */
        val revision: String,
        /** Date §3.2 en secondes (0 = pas de date). */
        val dateSeconds: Long,
        val uploader: String,
        val rating: Double?,
        val version: String,
        /** Modes affichés (4 au plus, ceux de l'empreinte filtrée d'abord). */
        val modes: List<GdtfShareMode>,
        /** Modes restants (`+N`). */
        val moreModes: Int,
        /** `ceil(filesize / 1 Mio)` si le fichier dépasse 64 Mo (ligne désactivée), sinon null. */
        val tooLargeMegabytes: Int?
    )

    fun row(e: GdtfShareEntry, footprint: Int?): Row {
        val ordered = if (footprint == null) e.modes
        else e.modes.filter { it.footprint == footprint } + e.modes.filter { it.footprint != footprint }
        val big = e.filesize?.takeIf { it > MAX_FILE_BYTES }?.let { ((it + MIB - 1) / MIB).toInt() }
        return Row(
            e.rid, "${e.manufacturer} — ${e.fixture}", e.revision, e.date, e.uploader, e.rating, e.version,
            ordered.take(MAX_MODES_SHOWN), maxOf(0, ordered.size - MAX_MODES_SHOWN), big
        )
    }

    /** Mise en mots (ressources de l'appareil, ou valeurs de `strings.xml` en test). */
    interface Texts {
        fun revision(name: String): String
        val revisionUnnamed: String
        fun date(seconds: Long): String
        val byManufacturer: String
        val byUser: String
        fun rating(value: Double): String
        fun gdtfVersion(v: String): String
        fun modes(list: String): String
        fun modeItem(name: String, footprint: Int): String
        val modeUnnamed: String
        fun modesMore(n: Int): String
        val modesNone: String
    }

    /** Ligne 2 : `Révision : …` / `Révision sans nom`, puis ` · <date>` si connue. */
    fun line2(r: Row, t: Texts): String =
        (if (r.revision.isEmpty()) t.revisionUnnamed else t.revision(r.revision)) +
            (if (r.dateSeconds > 0) " · " + t.date(r.dateSeconds) else "")

    /** Ligne 3 : auteur · note · version (segments non vides) ; null si les trois sont vides. */
    fun line3(r: Row, t: Texts): String? = listOfNotNull(
        when (r.uploader) { "manufacturer" -> t.byManufacturer; "user" -> t.byUser; else -> null },
        r.rating?.let { t.rating(it) },
        r.version.takeIf { it.isNotEmpty() }?.let { t.gdtfVersion(it) }
    ).joinToString(" · ").ifEmpty { null }

    /** Ligne 4 : `Modes : A (n ch) · B (m ch) · +N` ou `Modes : non indiqués`. */
    fun line4(r: Row, t: Texts): String {
        if (r.modes.isEmpty()) return t.modes(t.modesNone)
        val items = r.modes.joinToString(" · ") { t.modeItem(it.name.ifEmpty { t.modeUnnamed }, it.footprint) }
        return t.modes(if (r.moreModes > 0) items + " · " + t.modesMore(r.moreModes) else items)
    }

    /** Textes de l'appareil (langue du téléphone). */
    fun deviceTexts(ctx: Context): Texts {
        val locale = ctx.resources.configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
        return object : Texts {
            override fun revision(name: String) = ctx.getString(R.string.ma_share_revision, name)
            override val revisionUnnamed = ctx.getString(R.string.ma_share_revision_unnamed)
            override fun date(seconds: Long): String = df.format(Date(seconds * 1000))
            override val byManufacturer = ctx.getString(R.string.ma_share_by_manufacturer)
            override val byUser = ctx.getString(R.string.ma_share_by_user)
            override fun rating(value: Double) = ctx.getString(R.string.gdtf_rating_fmt, String.format(locale, "%.1f", value))
            override fun gdtfVersion(v: String) = ctx.getString(R.string.ma_share_gdtf_version, v)
            override fun modes(list: String) = ctx.getString(R.string.ma_share_modes, list)
            override fun modeItem(name: String, footprint: Int) = ctx.getString(R.string.ma_share_mode_item, name, footprint)
            override val modeUnnamed = ctx.getString(R.string.ma_mode_unnamed)
            override fun modesMore(n: Int) = ctx.getString(R.string.ma_share_modes_more, n)
            override val modesNone = ctx.getString(R.string.ma_share_modes_none)
        }
    }
}

// ---- Déroulé réseau (pur : le réseau est injecté) ------------------------------------------

/** Accès GDTF Share de la recherche (le vrai client sur l'appareil, une doublure en test). */
interface MaShareBackend {
    suspend fun list(): List<GdtfShareEntry>
    suspend fun download(rid: Int): ByteArray
    /** Reconnexion SILENCIEUSE depuis les identifiants gardés. */
    suspend fun relogin(): GdtfShareClient.LoginOutcome
    /** Un réseau actif annonce l'accès Internet (vérifié AVANT l'appel). */
    fun hasInternet(): Boolean
    /** Un réseau VALIDÉ existe (un hôte introuvable sans lui = hors ligne). */
    fun hasValidatedNetwork(): Boolean
}

sealed class MaShareListResult {
    class Ok(val entries: List<GdtfShareEntry>) : MaShareListResult()
    /** Pas d'identifiants, ou identifiants refusés : écran de connexion. */
    object NeedLogin : MaShareListResult()
    object Offline : MaShareListResult()
    /** Toute autre panne ; `cause` porte le message EXISTANT du client. */
    class Error(val cause: Throwable) : MaShareListResult()
}

sealed class MaShareTapResult {
    /** Révision déjà dans la bibliothèque : fiche ouverte, AUCUN appel réseau. */
    data class InLibrary(val id: String) : MaShareTapResult()
    data class TooLarge(val megabytes: Int) : MaShareTapResult()
    data class Added(val id: String) : MaShareTapResult()
    data class AlreadyPresent(val id: String) : MaShareTapResult()
    /** `Téléchargement impossible. Réessayez.` */
    object DownloadFailed : MaShareTapResult()
    data class NotAdded(val error: MaImportError) : MaShareTapResult()
    object NeedLogin : MaShareTapResult()
}

object MaShareFlow {

    private fun isUnreachable(e: Throwable) =
        (e as? GdtfShareClient.ShareException)?.messageRes == R.string.gdtf_err_unreachable

    private fun classify(e: Throwable, b: MaShareBackend): MaShareListResult =
        if (isUnreachable(e) && !b.hasValidatedNetwork()) MaShareListResult.Offline else MaShareListResult.Error(e)

    /**
     * Liste (§1.4, §3.4) : hors ligne AVANT l'appel → [MaShareListResult.Offline] ; session
     * expirée (401/403) → UNE reconnexion silencieuse puis UNE nouvelle tentative ;
     * identifiants refusés → connexion ; le reste → hors ligne (hôte introuvable sans
     * réseau validé) ou erreur.
     */
    suspend fun loadList(b: MaShareBackend): MaShareListResult {
        if (!b.hasInternet()) return MaShareListResult.Offline
        return try {
            MaShareListResult.Ok(b.list())
        } catch (e: CancellationException) {
            throw e
        } catch (e: GdtfShareClient.SessionExpiredException) {
            when (b.relogin()) {
                GdtfShareClient.LoginOutcome.OK -> try {
                    MaShareListResult.Ok(b.list())
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: GdtfShareClient.SessionExpiredException) {
                    MaShareListResult.NeedLogin
                } catch (e2: Exception) {
                    classify(e2, b)
                }
                GdtfShareClient.LoginOutcome.NO_CREDENTIALS, GdtfShareClient.LoginOutcome.REJECTED -> MaShareListResult.NeedLogin
                GdtfShareClient.LoginOutcome.NETWORK ->
                    if (!b.hasInternet() || !b.hasValidatedNetwork()) MaShareListResult.Offline
                    else MaShareListResult.Error(GdtfShareClient.ShareException(R.string.gdtf_err_unreachable))
            }
        } catch (e: Exception) {
            classify(e, b)
        }
    }

    private sealed class Got {
        class Bytes(val b: ByteArray) : Got()
        object Failed : Got()
        object NeedLogin : Got()
    }

    private suspend fun fetch(b: MaShareBackend, rid: Int): Got = try {
        Got.Bytes(b.download(rid))
    } catch (e: CancellationException) {
        throw e
    } catch (e: GdtfShareClient.SessionExpiredException) {
        when (b.relogin()) {
            GdtfShareClient.LoginOutcome.OK -> try {
                Got.Bytes(b.download(rid))
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: GdtfShareClient.SessionExpiredException) {
                Got.NeedLogin
            } catch (e2: Exception) {
                Got.Failed
            }
            GdtfShareClient.LoginOutcome.NO_CREDENTIALS, GdtfShareClient.LoginOutcome.REJECTED -> Got.NeedLogin
            GdtfShareClient.LoginOutcome.NETWORK -> Got.Failed
        }
    } catch (e: Exception) {
        Got.Failed
    }

    /**
     * Toucher une ligne (§4.1) : déjà en bibliothèque pour ce `rid` → fiche, sans réseau ;
     * trop volumineux → rien ; sinon téléchargement (reconnexion comme la liste), puis import
     * EXACTEMENT comme un fichier. Renvoie l'issue + la bibliothèque à jour (null si inchangée).
     */
    suspend fun tap(
        entry: GdtfShareEntry,
        store: MaLibraryStore,
        backend: MaShareBackend,
        inLibrary: (Int) -> MaLibraryEntry?,
        io: CoroutineContext = Dispatchers.IO
    ): Pair<MaShareTapResult, List<MaLibraryEntry>?> {
        inLibrary(entry.rid)?.let { return MaShareTapResult.InLibrary(it.id) to null }
        MaShareSearch.row(entry, null).tooLargeMegabytes?.let { return MaShareTapResult.TooLarge(it) to null }
        val bytes = when (val g = fetch(backend, entry.rid)) {
            is Got.Bytes -> g.b
            Got.Failed -> return MaShareTapResult.DownloadFailed to null
            Got.NeedLogin -> return MaShareTapResult.NeedLogin to null
        }
        if (bytes.size > MaShareSearch.MAX_FILE_BYTES)
            return MaShareTapResult.NotAdded(MaImportError.TooLarge(((bytes.size + 1048575L) / 1048576L).toInt())) to null
        // Écran quitté pendant le téléchargement : rien n'est ajouté.
        currentCoroutineContext().ensureActive()
        val (outcome, lib) = withContext(io) { store.importShareRevision(entry, bytes) }
        return when (outcome) {
            is MaShareImportOutcome.Added -> MaShareTapResult.Added(outcome.id)
            is MaShareImportOutcome.AlreadyPresent -> MaShareTapResult.AlreadyPresent(outcome.id)
            is MaShareImportOutcome.Failed -> MaShareTapResult.NotAdded(outcome.error)
        } to lib
    }
}

/** Le vrai client GDTF Share + l'état du réseau de l'appareil. */
fun deviceShareBackend(ctx: Context): MaShareBackend = object : MaShareBackend {
    private val app = ctx.applicationContext
    override suspend fun list() = GdtfShareClient.fixtureList()
    override suspend fun download(rid: Int) = GdtfShareClient.download(rid)
    override suspend fun relogin() = GdtfShareClient.ensureLoggedInOutcome(app)
    private fun caps(): android.net.NetworkCapabilities? = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    }.getOrNull()
    override fun hasInternet(): Boolean = GdtfShareClient.mockDir != null ||
        caps()?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    override fun hasValidatedNetwork(): Boolean = GdtfShareClient.mockDir != null ||
        caps()?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}

/** Tri mémorisé PAR APPAREIL. */
object MaShareSortStore {
    private const val PREFS = "maShare"
    private const val KEY = "maShare.sort"
    fun load(ctx: Context): MaShareSort =
        MaShareSort.fromRaw(runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull())
    fun save(ctx: Context, s: MaShareSort) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s.raw).apply() }
    }
}

// ---- État de la page (hissé : requête, filtre, tri et défilement survivent à la fiche) ----

sealed class MaSharePhase {
    object Start : MaSharePhase()
    object Connecting : MaSharePhase()
    object NeedLogin : MaSharePhase()
    object Loading : MaSharePhase()
    object Ready : MaSharePhase()
    object Offline : MaSharePhase()
    class Error(val detail: String?) : MaSharePhase()
}

class MaShareSearchState(initialSort: MaShareSort) {
    var query by mutableStateOf("")
    var footprint by mutableStateOf("")
    var sort by mutableStateOf(initialSort)
    var phase by mutableStateOf<MaSharePhase>(MaSharePhase.Start)
    var index by mutableStateOf<MaShareSearch.Index?>(null)
    var results by mutableStateOf<List<GdtfShareEntry>>(emptyList())
    var downloadingRid by mutableStateOf<Int?>(null)
    /** 5ᵉ ligne (en rouge) d'une ligne dont le téléchargement a échoué. */
    val rowErrors = mutableStateMapOf<Int, String>()
    val listState = LazyListState()
}

// ---- Page Compose --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaShareSearchPage(
    state: MaShareSearchState,
    library: MaLibraryState,
    store: MaLibraryStore,
    onBack: () -> Unit,
    /** Ouvre la fiche d'une entrée, avec une note d'en-tête (ajouté / déjà présent). */
    onOpenProfile: (id: String, note: String?) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val backend = remember { deviceShareBackend(ctx) }
    val texts = remember { MaShareSearch.deviceTexts(ctx) }

    // Connexion puis liste (§2.2, §3.4) — la liste reste en mémoire pour la session.
    LaunchedEffect(state.phase) {
        when (state.phase) {
            MaSharePhase.Start -> {
                state.phase = when {
                    state.index != null && GdtfShareClient.loggedIn -> MaSharePhase.Ready
                    GdtfShareClient.loggedIn || GdtfShareClient.mockDir != null -> MaSharePhase.Loading
                    GdtfCredentialStore.username(ctx) != null -> MaSharePhase.Connecting
                    else -> MaSharePhase.NeedLogin
                }
            }
            MaSharePhase.Connecting -> {
                state.phase = when (GdtfShareClient.ensureLoggedInOutcome(ctx)) {
                    GdtfShareClient.LoginOutcome.OK -> MaSharePhase.Loading
                    GdtfShareClient.LoginOutcome.NO_CREDENTIALS, GdtfShareClient.LoginOutcome.REJECTED -> MaSharePhase.NeedLogin
                    GdtfShareClient.LoginOutcome.NETWORK ->
                        if (!backend.hasInternet() || !backend.hasValidatedNetwork()) MaSharePhase.Offline
                        else MaSharePhase.Error(ctx.getString(R.string.gdtf_err_unreachable))
                }
            }
            MaSharePhase.Loading -> {
                state.phase = when (val r = MaShareFlow.loadList(backend)) {
                    is MaShareListResult.Ok -> {
                        state.index = withContext(Dispatchers.Default) { MaShareSearch.Index(r.entries) }
                        MaSharePhase.Ready
                    }
                    MaShareListResult.NeedLogin -> MaSharePhase.NeedLogin
                    MaShareListResult.Offline -> MaSharePhase.Offline
                    is MaShareListResult.Error -> MaSharePhase.Error(r.cause.gdtfShareMessage(ctx))
                }
            }
            else -> {}
        }
    }

    if (state.phase == MaSharePhase.NeedLogin) {
        // L'écran de connexion EXISTANT ; fermé sans connexion → retour à la bibliothèque.
        GdtfShareLoginPane(
            title = stringResource(R.string.ma_share_title),
            onBack = { state.phase = MaSharePhase.Start; onBack() },
            onLoggedIn = { state.phase = MaSharePhase.Loading }
        )
        return
    }

    // Résultats recalculés 250 ms après la dernière frappe.
    val fp = MaShareSearch.footprintFilter(state.footprint)
    LaunchedEffect(state.query, state.footprint, state.sort, state.index) {
        val idx = state.index ?: return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        val q = state.query; val f = MaShareSearch.footprintFilter(state.footprint); val s = state.sort
        state.results = withContext(Dispatchers.Default) { idx.search(q, if (idx.providesFootprints) f else null, s) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.ma_share_title), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.ma_share_intro), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.query, onValueChange = { state.query = it },
                label = { Text(stringResource(R.string.ma_search)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            val fpEnabled = state.index?.providesFootprints != false
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.footprint,
                    onValueChange = { state.footprint = MaShareSearch.normalizeFootprintInput(it) },
                    label = { Text(stringResource(R.string.ma_share_footprint)) },
                    placeholder = { Text(stringResource(R.string.ma_share_footprint_any)) },
                    singleLine = true, enabled = fpEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                )
                SortMenu(state.sort) { s -> state.sort = s; MaShareSortStore.save(ctx, s) }
            }
            if (!fpEnabled) Text(stringResource(R.string.ma_share_no_footprints), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        HorizontalDivider()
        when (val ph = state.phase) {
            MaSharePhase.Start, MaSharePhase.Connecting -> Busy(stringResource(R.string.gdtf_reconnecting))
            MaSharePhase.Loading -> Busy(stringResource(R.string.ma_share_loading))
            MaSharePhase.Offline -> Problem(stringResource(R.string.ma_share_offline), null) { state.phase = MaSharePhase.Start }
            is MaSharePhase.Error -> Problem(stringResource(R.string.ma_share_error), ph.detail) { state.phase = MaSharePhase.Start }
            else -> {
                val q = state.query.trim()
                val activeFp = if (state.index?.providesFootprints == true) fp else null
                when {
                    MaSearch.words(q).isEmpty() && activeFp == null -> Message(stringResource(R.string.ma_share_hint))
                    state.results.isEmpty() -> Message(
                        when {
                            activeFp == null -> stringResource(R.string.ma_share_no_match, q)
                            MaSearch.words(q).isEmpty() -> stringResource(R.string.ma_share_no_match_fp, activeFp)
                            else -> stringResource(R.string.ma_share_no_match_both, q, activeFp)
                        }
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), state = state.listState) {
                        items(state.results.take(MaShareSearch.DISPLAY_CAP), key = { it.rid }) { e ->
                            ResultRow(
                                row = MaShareSearch.row(e, activeFp),
                                texts = texts,
                                inLibrary = library.entryForShareRid(e.rid) != null,
                                downloading = state.downloadingRid == e.rid,
                                otherBusy = state.downloadingRid != null && state.downloadingRid != e.rid,
                                error = state.rowErrors[e.rid]
                            ) {
                                scope.launch {
                                    state.downloadingRid = e.rid; state.rowErrors.remove(e.rid)
                                    val (res, lib) = try {
                                        MaShareFlow.tap(e, store, backend, inLibrary = { library.entryForShareRid(it) })
                                    } finally {
                                        state.downloadingRid = null
                                    }
                                    lib?.let { library.replaceAll(it) }
                                    when (res) {
                                        is MaShareTapResult.InLibrary -> onOpenProfile(res.id, null)
                                        is MaShareTapResult.Added -> onOpenProfile(res.id, ctx.getString(R.string.ma_share_added))
                                        is MaShareTapResult.AlreadyPresent -> onOpenProfile(res.id, ctx.getString(R.string.ma_share_already))
                                        MaShareTapResult.DownloadFailed -> state.rowErrors[e.rid] = ctx.getString(R.string.ma_share_download_failed)
                                        is MaShareTapResult.NotAdded ->
                                            state.rowErrors[e.rid] = ctx.getString(R.string.ma_share_not_added, maImportErrorString(ctx, res.error))
                                        is MaShareTapResult.TooLarge -> {}
                                        MaShareTapResult.NeedLogin -> state.phase = MaSharePhase.NeedLogin
                                    }
                                }
                            }
                        }
                        if (state.results.size > MaShareSearch.DISPLAY_CAP) item {
                            Text(
                                stringResource(R.string.ma_too_many, state.results.size),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        item { Spacer(Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenu(sort: MaShareSort, onPick: (MaShareSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(
        MaShareSort.MANUFACTURER to stringResource(R.string.ma_share_sort_manufacturer),
        MaShareSort.RECENT to stringResource(R.string.ma_share_sort_recent),
        MaShareSort.RATING to stringResource(R.string.ma_share_sort_rating)
    )
    Box {
        TextButton(onClick = { open = true }) {
            Column {
                Text(stringResource(R.string.ma_share_sort), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(labels.getValue(sort), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MaShareSort.entries.forEach { s ->
                DropdownMenuItem(text = { Text(labels.getValue(s)) }, onClick = { open = false; onPick(s) })
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.width(22.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
}

@Composable
private fun Problem(text: String, detail: String?, onRetry: () -> Unit) {
    Column(Modifier.padding(20.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.ma_share_retry))
        }
    }
}

@Composable
private fun ResultRow(
    row: MaShareSearch.Row,
    texts: MaShareSearch.Texts,
    inLibrary: Boolean,
    downloading: Boolean,
    otherBusy: Boolean,
    error: String?,
    onTap: () -> Unit
) {
    val tooLargeMb: Int? = if (inLibrary) null else row.tooLargeMegabytes
    val tooLarge = tooLargeMb != null
    val enabled = !tooLarge && !otherBusy && !downloading
    val alpha = if (tooLarge || otherBusy) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onTap).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            val on = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            val sub = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = on, maxLines = 2)
            Text(MaShareSearch.line2(row, texts), style = MaterialTheme.typography.bodySmall, color = sub)
            MaShareSearch.line3(row, texts)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sub) }
            Text(MaShareSearch.line4(row, texts), style = MaterialTheme.typography.bodySmall, color = sub)
            tooLargeMb?.let { mb ->
                Text(
                    stringResource(R.string.ma_err_too_large, mb, (MaShareSearch.MAX_FILE_BYTES / (1024 * 1024)).toInt()),
                    style = MaterialTheme.typography.bodySmall, color = sub
                )
            }
            if (downloading) Text(stringResource(R.string.ma_downloading), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        when {
            downloading -> CircularProgressIndicator(Modifier.width(22.dp), strokeWidth = 2.dp)
            inLibrary -> Text(stringResource(R.string.ma_share_in_library), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
    HorizontalDivider()
}
