package com.minou.mvrviewer.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.Fixture3DModel
import com.minou.mvrviewer.mvr.GdtfLoader
import com.minou.mvrviewer.mvr.MaProfileReader
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.SceneModelLoader
import com.minou.mvrviewer.sync.CustomFixtureModel3D
import com.minou.mvrviewer.sync.CustomFixtureProfileDTO
import com.minou.mvrviewer.sync.CustomFixtureTypeDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ÉCRAN « Bibliothèque MA » (SPEC-ma.md §5.2-5.8, SPEC-ma-gdtfshare.md) : liste +
 * recherche, import de fichiers / d'un dossier entier / d'archives `.zip` (progression,
 * Arrêter, bilan), recherche GDTF Share intégrée, fiche profil, puis création d'un TYPE
 * « profil MA + 3D choisie » (choix de la 3D → résumé → Enregistrer [et affecter à la
 * sélection]).
 *
 * Pro : importer / chercher sur GDTF Share / télécharger / consulter / supprimer un profil
 * = GRATUIT ; « Créer un type à partir de ce mode » = garde « créer un type personnalisé » ;
 * « Enregistrer et affecter » = garde « modifier le patch ». Toutes les commandes restent
 * VISIBLES pour un gratuit.
 *
 * `scene` null : bibliothèque ouverte depuis l'ACCUEIL (aucun show) ; `inbox` : fichiers
 * reçus du système (« Ouvrir avec », partage), importés un lot après l'autre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaLibraryScreen(
    state: MaLibraryState,
    scene: MvrScene?,
    mvrBytes: ByteArray?,
    gdtfOverrides: GdtfOverrides,
    customLibrary: CustomFixtureLibrary,
    customResolver: CustomFixtureResolver,
    fixture3DStore: Fixture3DStore,
    /** Un projet PARTAGÉ est ouvert (note du résumé). */
    sharedProjectOpen: Boolean,
    /** Nombre de projecteurs sélectionnés (3D ou plan) au moment d'ouvrir la bibliothèque. */
    selectionCount: Int,
    /**
     * Enregistre le type (biblio custom + table projet), envoie la 3D si un projet partagé
     * est ouvert, puis affecte à la sélection si demandé. Renvoie false si l'ENVOI de la
     * 3D a échoué (note §5.7), true sinon.
     */
    onSaveType: suspend (CustomFixtureTypeDTO, Boolean) -> Boolean,
    /** La biblio custom a changé depuis l'éditeur (« Ouvrir l'existant »). */
    onCustomLibraryChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    inbox: MaInbox? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MaLibraryStore.of(ctx) }
    var page by remember { mutableStateOf<MaPage>(MaPage.List) }
    var note by remember { mutableStateOf<String?>(null) }
    // Note d'information en tête de fiche (« Ajouté à la bibliothèque MA. »…).
    var info by remember { mutableStateOf<String?>(null) }
    // Recherche GDTF Share : requête, filtre, tri, défilement gardés en revenant de la fiche.
    val shareState = remember { MaShareSearchState(MaShareSortStore.load(ctx)) }
    var editing by remember { mutableStateOf<CustomFixtureTypeDTO?>(null) }
    var dupPrompt by remember { mutableStateOf<Pair<MaDraft, CustomFixtureTypeDTO>?>(null) }

    // Chargement PARESSEUX à la première ouverture, hors thread principal (§2.3).
    LaunchedEffect(state.loaded) {
        if (!state.loaded) {
            val list = withContext(Dispatchers.IO) { store.load() }
            state.replaceAll(list)
            state.loaded = true
        }
    }

    // ---- Import ----
    fun runImport(collect: suspend (isCancelled: () -> Boolean) -> MaImportPlan) {
        // Boutons désactivés tant que la liste n'est pas chargée (le store, lui, ne
        // dépend plus de cette liste : il relit et COMPLÈTE l'index disque).
        if (state.progress != null || !state.loaded) return
        state.cancelRequested = false
        state.progress = 0 to 0
        scope.launch {
            var finished = false
            try {
                val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                // « Arrêter » ou écran quitté : lu PENDANT la préparation (avant chaque fichier
                // et chaque archive imbriquée) comme pendant l'import.
                val isCancelled = { state.cancelRequested || job?.isActive == false }
                // Préparation (copie des archives reçues, tables centrales) ET import dans le
                // MÊME bloc : écran quitté pendant la préparation → l'import voit l'annulation
                // tout de suite et fait quand même le ménage (archives fermées, copies effacées).
                val (report, lib) = withContext(Dispatchers.IO) {
                    // Copies d'archives laissées par un import interrompu (aucun import ne tourne).
                    MaSafEnumerator.purgeCache(ctx)
                    val plan = collect(isCancelled)
                    store.import(
                        plan,
                        isCancelled = isCancelled,
                        onProgress = { d, t -> scope.launch(Dispatchers.Main) { if (state.progress != null) state.progress = d to t } },
                        onBatch = { partial -> scope.launch(Dispatchers.Main) { state.replaceAll(partial) } }
                    )
                }
                state.replaceAll(lib)
                state.report = report
                finished = true
            } finally {
                state.progress = null
                // Import interrompu par la sortie de l'écran : on relira le disque.
                if (!finished) state.loaded = false
            }
        }
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runImport { cancelled -> MaSafEnumerator.fromDocuments(ctx, uris, isCancelled = cancelled) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) runImport { cancelled -> MaSafEnumerator.fromTree(ctx, tree, cancelled) }
    }
    // Fichiers REÇUS (« Ouvrir avec », partage) : un lot à la fois, le suivant quand le
    // bilan du précédent a été fermé (chaque lot a sa progression et son bilan).
    if (inbox != null) LaunchedEffect(inbox.batches.size, state.loaded, state.progress, state.report) {
        if (state.loaded && state.progress == null && state.report == null) {
            // Documents REÇUS : la décision du routeur est respectée (GDTF renommé .zip → .gdtf).
            inbox.poll()?.let { uris ->
                runImport { cancelled -> MaSafEnumerator.fromDocuments(ctx, uris, received = true, isCancelled = cancelled) }
            }
        }
    }

    // ---- Création d'un type ----
    fun startCreate(entry: MaLibraryEntry, mode: MaProfileReader.Mode) {
        ProAccess.gate("créer un type personnalisé") {
            val draft = MaDraft(entry, mode)
            val wanted = draft.profileDTO()
            val existing = customLibrary.types.firstOrNull { it.profile == wanted }
            info = null      // la note « Ajouté à la bibliothèque MA. » ne vaut qu'à l'arrivée sur la fiche
            if (existing != null) dupPrompt = draft to existing else page = MaPage.Choose3D(draft)
        }
    }

    // Retour matériel : recule d'une page (la scène, elle, ramène à la 3D depuis la liste).
    BackHandler(enabled = page !is MaPage.List) {
        page = when (val p = page) {
            is MaPage.Profile -> if (p.fromSearch) MaPage.ShareSearch else MaPage.List
            MaPage.ShareSearch -> MaPage.List
            is MaPage.Choose3D -> MaPage.Profile(p.draft.entry.id)
            is MaPage.Summary -> MaPage.Choose3D(p.draft)
            MaPage.List -> MaPage.List
        }
    }

    Box(modifier.fillMaxSize()) {
        when (val p = page) {
            MaPage.List -> MaListPage(
                state = state,
                onBack = onBack,
                onImportFiles = { filesPicker.launch(arrayOf("*/*")) },
                onImportFolder = { folderPicker.launch(null) },
                onShareSearch = { page = MaPage.ShareSearch },
                onOpen = { page = MaPage.Profile(it.id); note = null; info = null }
            )
            MaPage.ShareSearch -> MaShareSearchPage(
                state = shareState,
                library = state,
                store = store,
                onBack = { page = MaPage.List },
                onOpenProfile = { id, n -> note = null; info = n; page = MaPage.Profile(id, fromSearch = true) }
            )
            is MaPage.Profile -> {
                val entry = state.get(p.id)
                if (entry == null) LaunchedEffect(p.id) { page = if (p.fromSearch) MaPage.ShareSearch else MaPage.List } else MaProfilePage(
                    entry = entry,
                    note = note,
                    info = info,
                    onBack = { page = if (p.fromSearch) MaPage.ShareSearch else MaPage.List },
                    onCreate = { mode -> startCreate(entry, mode) },
                    onDelete = {
                        scope.launch {
                            // Retrait sur l'index DISQUE relu (sûr même pendant un import).
                            val rest = withContext(Dispatchers.IO) { store.remove(entry.id) }
                            state.replaceAll(rest)
                            page = if (p.fromSearch) MaPage.ShareSearch else MaPage.List
                        }
                    }
                )
            }
            is MaPage.Choose3D -> Fixture3DChooser(
                scene = scene,
                store = fixture3DStore,
                shareQuery = p.draft.entry.fixture,
                ownGdtf = if (p.draft.entry.format == "gdtf" && p.draft.entry.hasModel3D)
                    OwnGdtf(p.draft.entry.fileName, p.draft.entry.share?.rid) { store.gdtfBytes(p.draft.entry.id) } else null,
                onBack = { page = MaPage.Profile(p.draft.entry.id) },
                onChosen = { c -> page = MaPage.Summary(p.draft.copy(chosen = c)) }
            )
            is MaPage.Summary -> MaSummaryPage(
                draft = p.draft,
                sharedProjectOpen = sharedProjectOpen,
                selectionCount = selectionCount,
                onBack = { page = MaPage.Choose3D(p.draft) },
                onSave = { type, assign, setBusy ->
                    scope.launch {
                        setBusy(true)
                        val ok = runCatching { onSaveType(type, assign) }.getOrDefault(false)
                        setBusy(false)
                        note = if (ok) null else ctx.getString(R.string.ma_upload_failed)
                        page = MaPage.Profile(p.draft.entry.id)
                    }
                }
            )
        }

        // Progression d'import (bandeau) — reste visible quelle que soit la page.
        state.progress?.let { (done, total) ->
            Surface(
                tonalElevation = 6.dp, shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ma_importing, done, total), style = MaterialTheme.typography.bodyMedium)
                        if (total > 0) LinearProgressIndicator(
                            progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        ) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    TextButton(onClick = { state.cancelRequested = true }) { Text(stringResource(R.string.ma_stop)) }
                }
            }
        }
    }

    // Bilan (toujours affiché, y compris pour un seul fichier).
    state.report?.let { r -> MaReportDialog(r) { state.report = null } }

    // Doublon : un type existe déjà pour ce mode (§5.5 étape 2).
    dupPrompt?.let { (draft, existing) ->
        AlertDialog(
            onDismissRequest = { dupPrompt = null },
            text = { Text(stringResource(R.string.ma_dup_type, existing.name)) },
            confirmButton = {
                TextButton(onClick = { dupPrompt = null; page = MaPage.Choose3D(draft) }) { Text(stringResource(R.string.ma_dup_create)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { dupPrompt = null; editing = existing }) { Text(stringResource(R.string.ma_dup_open)) }
                    TextButton(onClick = { dupPrompt = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        )
    }

    // « Ouvrir l'existant » : l'éditeur de type habituel.
    editing?.let { t ->
        CustomFixtureEditorSheet(
            initial = t, scene = scene ?: MvrScene(emptyList(), emptyMap()), mvrBytes = mvrBytes ?: ByteArray(0),
            gdtfOverrides = gdtfOverrides,
            defaultBaseSpec = null,
            onSave = { saved ->
                customLibrary.upsert(saved)
                customResolver.setLibrary(customLibrary.types.toList())
                customResolver.mergeProject(listOf(saved))
                onCustomLibraryChanged()
                editing = null
            },
            onDelete = { id ->
                customLibrary.remove(id)
                customResolver.setLibrary(customLibrary.types.toList())
                onCustomLibraryChanged()
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

// ---- Modèle de navigation ------------------------------------------------------------

private sealed class MaPage {
    object List : MaPage()
    /** Recherche GDTF Share (SPEC-ma-gdtfshare.md §3). */
    object ShareSearch : MaPage()
    /** Fiche ; `fromSearch` : ouverte par-dessus la recherche (retour → la recherche). */
    data class Profile(val id: String, val fromSearch: Boolean = false) : MaPage()
    data class Choose3D(val draft: MaDraft) : MaPage()
    data class Summary(val draft: MaDraft) : MaPage()
}

/** 3D choisie (§4.1) : base (a) OU model3D (b)(c)(d), ou rien (e). */
data class Chosen3D(
    val baseGdtfSpec: String,
    val model3D: CustomFixtureModel3D?,
    /** Avertissement non bloquant (GDTF sans modèle 3D, modèle lourd…). */
    val warning: String? = null
)

internal data class MaDraft(
    val entry: MaLibraryEntry,
    val mode: MaProfileReader.Mode,
    val chosen: Chosen3D? = null
) {
    fun profileDTO() = CustomFixtureProfileDTO(entry.format, entry.manufacturer, entry.fixture, mode.name, entry.fileName)

    /** Type construit au résumé (§5.7) : trous "" GARDÉS (l'index EST l'offset). */
    fun buildType(id: String, name: String): CustomFixtureTypeDTO {
        val c = chosen ?: Chosen3D("", null)
        return CustomFixtureTypeDTO(
            id = id, name = name.trim(), baseGdtfSpec = c.baseGdtfSpec, channels = mode.channels,
            footprint = mode.footprint, profile = profileDTO(), model3D = c.model3D
        )
    }
}

/** Profil GDTF à vrai modèle 3D : option « La 3D de ce fichier GDTF » (§4.6). */
internal class OwnGdtf(
    val fileName: String,
    /** Révision GDTF Share d'origine (entrée à `share`), sinon null (fichier importé). */
    val shareRid: Int?,
    val bytes: () -> ByteArray?
)

/**
 * Choix de « La 3D de ce fichier GDTF » (SPEC-ma-gdtfshare.md §4.6) : profil venu de GDTF
 * Share → `gdtfShare(rid)`, octets copiés au cache GDTF Share du rid AU MOMENT DU CHOIX
 * (rien n'est envoyé au Storage du projet : chaque membre retélécharge avec SON compte) ;
 * fichier importé → `gdtfBlob` (inchangé).
 */
internal fun chooseOwnGdtf(own: OwnGdtf, bytes: ByteArray, store: Fixture3DStore): Chosen3D {
    val rid = own.shareRid
    return if (rid != null) {
        store.putShare(rid, bytes)
        Chosen3D("", CustomFixtureModel3D.GdtfShare(rid))
    } else {
        Chosen3D("", CustomFixtureModel3D.GdtfBlob(store.putBlob(bytes), own.fileName))
    }
}

// ---- Libellés partagés -------------------------------------------------------------

/** Ressource + arguments du message d'une erreur d'import (pur : vérifiable en JVM). */
internal fun maImportErrorRes(e: MaImportError): Pair<Int, List<Any>> = when (e) {
    MaImportError.Protected -> R.string.ma_err_protected to emptyList()
    MaImportError.Unreadable -> R.string.ma_err_unreadable to emptyList()
    MaImportError.Unknown -> R.string.ma_err_unknown to emptyList()
    MaImportError.Empty -> R.string.ma_err_empty to emptyList()
    is MaImportError.TooLarge -> R.string.ma_err_too_large to listOf(e.megabytes, e.maxMegabytes)
    MaImportError.Io -> R.string.ma_err_io to emptyList()
    MaImportError.ZipUnreadable -> R.string.ma_err_zip_unreadable to emptyList()
    MaImportError.ZipEmpty -> R.string.ma_err_zip_empty to emptyList()
    is MaImportError.ZipLimit -> R.string.ma_err_zip_limit to listOf(e.count)
}

/** Message d'une erreur d'import, dans la langue du téléphone. */
internal fun maImportErrorString(ctx: Context, e: MaImportError): String =
    maImportErrorRes(e).let { (res, args) -> ctx.getString(res, *args.toTypedArray()) }

@Composable
internal fun maImportErrorText(e: MaImportError): String = maImportErrorString(LocalContext.current, e)

@Composable
private fun modeName(m: MaProfileReader.Mode): String =
    m.name.ifEmpty { stringResource(R.string.ma_mode_unnamed) }

private fun localeOf(ctx: Context): Locale =
    ctx.resources.configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }

// ---- Page : liste --------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaListPage(
    state: MaLibraryState,
    onBack: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onShareSearch: () -> Unit,
    onOpen: (MaLibraryEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val all = state.entries.toList()
    val results = remember(all, query) { MaSearch.search(all, query) }
    val importing = state.progress != null
    // Import possible une fois la liste chargée (chargement paresseux, quelques secondes
    // pour une grosse bibliothèque) et hors import en cours.
    val canImport = !importing && state.loaded
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.ma_library), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                if (all.isEmpty()) stringResource(R.string.ma_count_0)
                else pluralStringResource(R.plurals.ma_count, all.size, all.size),
                style = MaterialTheme.typography.labelLarge
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text(stringResource(R.string.ma_search)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImportFiles, enabled = canImport, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ma_import_files), maxLines = 2)
                }
                OutlinedButton(onClick = onImportFolder, enabled = canImport, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ma_import_folder), maxLines = 2)
                }
            }
            // Recherche GDTF Share intégrée (API officielle) — gratuite, toujours visible ;
            // désactivée comme les imports (il faut connaître les profils déjà présents).
            OutlinedButton(onClick = onShareSearch, enabled = canImport, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.width(18.dp))
                Text("  " + stringResource(R.string.ma_share_search), maxLines = 2)
            }
            Text(
                stringResource(R.string.ma_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                stringResource(R.string.ma_formats), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
            )
        }
        HorizontalDivider()
        when {
            !state.loaded -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            all.isEmpty() -> Text(
                stringResource(R.string.ma_empty), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp)
            )
            results.isEmpty() -> Text(
                stringResource(R.string.ma_no_match, query.trim()), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp)
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (results.size > MaSearch.DISPLAY_CAP) item {
                    Text(
                        stringResource(R.string.ma_too_many, results.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(results.take(MaSearch.DISPLAY_CAP), key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.manufacturer} — ${e.fixture}", style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                            val sub = pluralStringResource(R.plurals.ma_row_modes, e.modes.size, maFormatLabel(e.format), e.modes.size)
                            // Révision GDTF Share : le segment auteur devient la marque « GDTF Share ».
                            val author = if (e.share != null) "GDTF Share" else e.author
                            Text(
                                if (author.isNotEmpty()) "$sub · $author" else sub,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

// ---- Bilan d'import ------------------------------------------------------------------

@Composable
private fun MaReportDialog(r: MaImportReport, onDismiss: () -> Unit) {
    var showErrors by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (r.stopped) R.string.ma_stopped else R.string.ma_done)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (r.added > 0) Text(pluralStringResource(R.plurals.ma_added, r.added, r.added))
                if (r.duplicates > 0) Text(pluralStringResource(R.plurals.ma_dupes, r.duplicates, r.duplicates))
                if (r.ignored > 0) Text(pluralStringResource(R.plurals.ma_ignored, r.ignored, r.ignored))
                if (r.errors.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.ma_errors, r.errors.size, r.errors.size))
                    // Sous la ligne d'erreurs, seulement si au moins un .pxml a été rencontré.
                    if (r.protectedCount > 0) Text(pluralStringResource(R.plurals.ma_protected_count, r.protectedCount, r.protectedCount))
                    TextButton(onClick = { showErrors = !showErrors }) { Text(stringResource(R.string.ma_show_errors)) }
                    if (showErrors) r.errors.take(500).forEach { (file, err) ->
                        Text(
                            stringResource(R.string.ma_error_line, file, maImportErrorText(err)),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

// ---- Page : fiche profil ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaProfilePage(
    entry: MaLibraryEntry,
    note: String?,
    info: String?,
    onBack: () -> Unit,
    onCreate: (MaProfileReader.Mode) -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    var expanded by remember(entry.id) { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("${entry.manufacturer} — ${entry.fixture}", style = MaterialTheme.typography.titleSmall, maxLines = 2) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            info?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            }
            note?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(stringResource(R.string.ma_format, maFormatLabel(entry.format)), style = MaterialTheme.typography.bodyMedium)
            // Révision GDTF Share (§4.5) : source, révision, déposant, date de modification.
            entry.share?.let { s ->
                Text(stringResource(R.string.ma_source_share, s.rid), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (s.revision.isEmpty()) stringResource(R.string.ma_share_revision_unnamed)
                    else stringResource(R.string.ma_share_revision, s.revision),
                    style = MaterialTheme.typography.bodyMedium
                )
                when (s.uploader) {
                    "manufacturer" -> stringResource(R.string.ma_share_by_manufacturer)
                    "user" -> stringResource(R.string.ma_share_by_user)
                    else -> null
                }?.let { Text(stringResource(R.string.ma_uploaded_by, it), style = MaterialTheme.typography.bodyMedium) }
                if (s.lastModified > 0) {
                    val modified = remember(s.lastModified) {
                        DateFormat.getDateInstance(DateFormat.MEDIUM, localeOf(ctx)).format(Date(s.lastModified * 1000))
                    }
                    Text(stringResource(R.string.ma_modified, modified), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (entry.author.isNotEmpty()) Text(stringResource(R.string.ma_author, entry.author), style = MaterialTheme.typography.bodyMedium)
            if (entry.shortName.isNotEmpty() && entry.shortName != entry.fixture)
                Text(stringResource(R.string.ma_short, entry.shortName), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ma_file, entry.fileName), style = MaterialTheme.typography.bodyMedium)
            val date = remember(entry.importedAt) {
                DateFormat.getDateInstance(DateFormat.MEDIUM, localeOf(ctx)).format(Date((entry.importedAt * 1000).toLong()))
            }
            Text(stringResource(R.string.ma_imported, date), style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.ma_modes, entry.modes.size), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            entry.modes.forEachIndexed { i, m ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = if (expanded == i) null else i }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pluralStringResource(R.plurals.ma_mode_row, m.footprint, modeName(m), m.footprint),
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(if (expanded == i) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                if (expanded == i) {
                    val shown = m.channels.take(1024)
                    shown.forEachIndexed { k, c ->
                        Text(
                            if (c.isEmpty()) stringResource(R.string.ma_free_slot, k + 1) else "${k + 1}. $c",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 12.dp, top = 1.dp, bottom = 1.dp)
                        )
                    }
                    if (m.channels.size > shown.size) Text("…", modifier = Modifier.padding(start = 12.dp))
                }
                // Plus d'un univers : DÉSACTIVÉ (raison de données, pas de Pro) + note.
                val tooBig = m.footprint > 512
                OutlinedButton(onClick = { onCreate(m) }, enabled = !tooBig, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(stringResource(R.string.ma_create_type))
                }
                if (tooBig) Text(stringResource(R.string.ma_over_universe, m.footprint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            HorizontalDivider()
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.ma_delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(72.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.ma_delete_confirm)) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } }
    )
}

// ---- Page : résumé + enregistrement ----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaSummaryPage(
    draft: MaDraft,
    sharedProjectOpen: Boolean,
    selectionCount: Int,
    onBack: () -> Unit,
    onSave: (CustomFixtureTypeDTO, Boolean, (Boolean) -> Unit) -> Unit
) {
    val modeLabel = modeName(draft.mode)
    var name by remember(draft) { mutableStateOf("${draft.entry.fixture} — $modeLabel") }
    var busy by remember { mutableStateOf(false) }
    val chosen = draft.chosen ?: Chosen3D("", null)
    val hasBlob = chosen.model3D?.blobSha != null
    // Android : UUID minuscules (iOS et le web écrivent des majuscules — les deux circulent).
    fun build(): CustomFixtureTypeDTO = draft.buildType(UUID.randomUUID().toString(), name)
    val previewType = build()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.ma_new_type), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.ma_type_name)) }, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.ma_profile_line, draft.entry.manufacturer, draft.entry.fixture, maFormatLabel(draft.entry.format)),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            Text(pluralStringResource(R.plurals.ma_mode_line, draft.mode.footprint, modeLabel, draft.mode.footprint),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ma_3d_line, type3DLabel(previewType)), style = MaterialTheme.typography.bodyMedium)
            chosen.warning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                stringResource(if (sharedProjectOpen) R.string.ma_shared_note else R.string.ma_local_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                if (hasBlob && sharedProjectOpen) Text(stringResource(R.string.ma_uploading), modifier = Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = { onSave(build(), false) { busy = it } },
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text(stringResource(R.string.ma_save)) }
            // TOUJOURS visible ; désactivé (avec sa raison) sans sélection.
            OutlinedButton(
                onClick = { ProAccess.gate("modifier le patch") { onSave(build(), true) { busy = it } } },
                enabled = !busy && name.isNotBlank() && selectionCount > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text(stringResource(R.string.ma_save_assign, selectionCount)) }
            if (selectionCount == 0) Text(stringResource(R.string.ma_no_selection), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.fx_channels_title_fmt, draft.mode.footprint), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            draft.mode.channels.take(1024).forEachIndexed { k, c ->
                Text(if (c.isEmpty()) stringResource(R.string.ma_free_slot, k + 1) else "${k + 1}. $c",
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

// ---- Choix de la 3D (réutilisé par l'éditeur : « Changer la 3D… ») ----------------------

/** Choix de la 3D en plein écran (éditeur de type existant, §5.9). */
@Composable
internal fun Fixture3DChooserDialog(
    scene: MvrScene?,
    store: Fixture3DStore,
    shareQuery: String,
    onDismiss: () -> Unit,
    onChosen: (Chosen3D) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Fixture3DChooser(scene, store, shareQuery, null, onDismiss, onChosen)
        }
    }
}

private sealed class ChooserSub {
    object Options : ChooserSub()
    object ShowList : ChooserSub()
    object ShareLogin : ChooserSub()
    object ShareSearch : ChooserSub()
    class Object(val bytes: ByteArray, val fileName: String, val model: Fixture3DModel.Decoded) : ChooserSub()
}

/**
 * Étape « Choisir la 3D » (§5.6) : GDTF du show (a), GDTF Share (b), « La 3D de ce
 * fichier GDTF » / Fichier GDTF… (c), Objet 3D… (d), Aucune (e). Les octets choisis sont
 * rangés AU CACHE de l'appareil dès le choix (blobs adressés par SHA-256 / rid).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Fixture3DChooser(
    scene: MvrScene?,
    store: Fixture3DStore,
    shareQuery: String,
    /** Profil GDTF À vrai modèle 3D : son propre fichier (option recommandée, en PREMIER). */
    ownGdtf: OwnGdtf?,
    onBack: () -> Unit,
    onChosen: (Chosen3D) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sub by remember { mutableStateOf<ChooserSub>(ChooserSub.Options) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val title = stringResource(R.string.ma_choose_3d)
    val sNoModel = stringResource(R.string.ma_3d_gdtf_no_model)

    BackHandler(enabled = sub !is ChooserSub.Options) { sub = ChooserSub.Options }

    val gdtfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { readPicked(ctx, uri) }
            busy = false
            when (r) {
                is Picked.Error -> error = r.message
                is Picked.Ok -> {
                    val ok = withContext(Dispatchers.Default) { gdtfHasDescription(r.bytes) }
                    if (!ok) { error = ctx.getString(R.string.ma_3d_not_gdtf); return@launch }
                    val has3D = withContext(Dispatchers.Default) { runCatching { GdtfLoader.hasThreeDModel(r.bytes) }.getOrDefault(false) }
                    val sha = withContext(Dispatchers.IO) { store.putBlob(r.bytes) }
                    onChosen(Chosen3D("", CustomFixtureModel3D.GdtfBlob(sha, r.name), if (has3D) null else sNoModel))
                }
            }
        }
    }
    val objectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { readPicked(ctx, uri) }
            if (r is Picked.Error) { busy = false; error = r.message; return@launch }
            val ok = r as Picked.Ok
            val fmt = Fixture3DModel.formatOf(ok.name, ok.bytes.copyOf(minOf(32, ok.bytes.size)))
            val res = withContext(Dispatchers.Default) {
                if (fmt == null) Fixture3DModel.DecodeResult.Unsupported(
                    ok.name.substringAfterLast('.', "?").uppercase()
                ) else Fixture3DModel.decode(ok.bytes, fmt)
            }
            busy = false
            when (res) {
                is Fixture3DModel.DecodeResult.Ok -> sub = ChooserSub.Object(ok.bytes, ok.name, res.model)
                is Fixture3DModel.DecodeResult.Unsupported -> error = ctx.getString(R.string.ma_reason_format, res.label)
                is Fixture3DModel.DecodeResult.TooHeavy -> error =
                    if (res.atLeast) ctx.getString(R.string.ma_3d_too_heavy_more, res.triangles, Fixture3DModel.MAX_TRIANGLES)
                    else ctx.getString(R.string.ma_3d_too_heavy, res.triangles, Fixture3DModel.MAX_TRIANGLES)
                Fixture3DModel.DecodeResult.TooLarge -> error = ctx.getString(R.string.ma_err_too_large,
                    (ok.bytes.size / (1024 * 1024)), (Fixture3DModel.MAX_BYTES / (1024 * 1024)).toInt())
                Fixture3DModel.DecodeResult.Unreadable -> error = ctx.getString(R.string.ma_err_unreadable)
                Fixture3DModel.DecodeResult.NoGeometry -> error = ctx.getString(R.string.model_err_no_geometry)
            }
        }
    }

    when (val s = sub) {
        ChooserSub.ShareLogin -> {
            GdtfShareLoginPane(title = title, onBack = { sub = ChooserSub.Options }, onLoggedIn = { sub = ChooserSub.ShareSearch })
            return
        }
        ChooserSub.ShareSearch -> {
            GdtfSearchPane(
                spec = title,
                initialQuery = shareQuery,
                onBack = { sub = ChooserSub.Options },
                onChosen = { data, rid ->
                    scope.launch {
                        withContext(Dispatchers.IO) { store.putShare(rid, data) }
                        val has3D = withContext(Dispatchers.Default) { runCatching { GdtfLoader.hasThreeDModel(data) }.getOrDefault(false) }
                        onChosen(Chosen3D("", CustomFixtureModel3D.GdtfShare(rid), if (has3D) null else sNoModel))
                    }
                }
            )
            return
        }
        is ChooserSub.Object -> {
            Fixture3DObjectPanel(s.fileName, s.model, onBack = { sub = ChooserSub.Options }) { unit, yUp, warning ->
                scope.launch {
                    val sha = withContext(Dispatchers.IO) { store.putBlob(s.bytes) }
                    onChosen(Chosen3D("", CustomFixtureModel3D.ModelBlob(sha, s.fileName, s.model.format, unit, yUp), warning))
                }
            }
            return
        }
        else -> {}
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = { if (sub is ChooserSub.ShowList) sub = ChooserSub.Options else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
            if (sub is ChooserSub.ShowList) {
                val specs = remember(scene) {
                    scene?.allObjects?.mapNotNull { it.gdtfSpec?.trim()?.ifEmpty { null } }?.distinct()?.sortedBy { it.lowercase() }.orEmpty()
                }
                specs.forEach { spec ->
                    HorizontalDivider()
                    Text(
                        spec.removeSuffix(".gdtf"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onChosen(Chosen3D(spec, null)) }.padding(vertical = 12.dp)
                    )
                }
                return@Column
            }
            Text(stringResource(R.string.ma_choose_3d_intro), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp))
            // « La 3D de ce fichier GDTF » en PREMIER, marquée « Recommandé » (SPEC-ma-gdtfshare.md §4.6).
            ownGdtf?.let { own ->
                ChoiceRow(
                    stringResource(R.string.ma_3d_own), stringResource(R.string.ma_3d_own_sub, own.fileName), enabled = !busy,
                    badge = stringResource(R.string.ma_3d_recommended)
                ) {
                    busy = true
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { own.bytes() }
                        if (bytes == null) { busy = false; error = ctx.getString(R.string.ma_err_io); return@launch }
                        val chosen = withContext(Dispatchers.IO) { chooseOwnGdtf(own, bytes, store) }
                        busy = false
                        onChosen(chosen)
                    }
                }
            }
            val hasShow = scene != null && scene.allObjects.any { !it.gdtfSpec.isNullOrBlank() }
            ChoiceRow(
                stringResource(R.string.ma_3d_show),
                if (scene == null) stringResource(R.string.ma_3d_no_show) else stringResource(R.string.ma_3d_show_sub),
                enabled = hasShow && !busy
            ) { sub = ChooserSub.ShowList }
            ChoiceRow(stringResource(R.string.ma_3d_share), stringResource(R.string.ma_3d_share_sub), enabled = !busy) {
                sub = if (com.minou.mvrviewer.mvr.GdtfShareClient.loggedIn) ChooserSub.ShareSearch else ChooserSub.ShareLogin
            }
            ChoiceRow(stringResource(R.string.ma_3d_gdtf_file), stringResource(R.string.ma_3d_gdtf_file_sub), enabled = !busy) {
                gdtfPicker.launch(arrayOf("*/*"))
            }
            ChoiceRow(stringResource(R.string.ma_3d_object), SceneModelLoader.SUPPORTED_SUMMARY, enabled = !busy) {
                objectPicker.launch(arrayOf("*/*"))
            }
            ChoiceRow(stringResource(R.string.ma_3d_none), null, enabled = !busy) { onChosen(Chosen3D("", null)) }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, enabled: Boolean, badge: String? = null, onClick: () -> Unit) {
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val alpha = if (enabled) 1f else 0.45f
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                badge?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    }
}

/**
 * Panneau d'objet 3D (§5.6) : taille posée (après unité et axe), unité du fichier
 * (Auto = valeur déclarée ou heuristique 50), axe vertical (absent pour glTF/GLB), note
 * d'origine, avertissement « Modèle lourd ».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Fixture3DObjectPanel(
    fileName: String,
    model: Fixture3DModel.Decoded,
    onBack: () -> Unit,
    onUse: (unit: Double, yUp: Boolean, warning: String?) -> Unit
) {
    val ctx = LocalContext.current
    val locale = localeOf(ctx)
    val nf = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    val autoUnit = model.unitScaleToMm
    var unit by remember(model) { mutableStateOf<Double?>(null) }   // null = Auto
    var yUp by remember(model) { mutableStateOf(model.yUp) }
    val effUnit = unit ?: autoUnit
    val (lo, hi) = Fixture3DModel.posedBounds(model.rawMin, model.rawMax, effUnit, yUp)
    val sInch = stringResource(R.string.ma_3d_unit_inch)
    fun unitLabel(u: Double): String = when (u) {
        1.0 -> "mm"; 10.0 -> "cm"; 1000.0 -> "m"; 25.4 -> sInch
        else -> "×" + nf.format(u)
    }
    val heavy = model.triangles > Fixture3DModel.HEAVY_TRIANGLES
    val heavyText = if (heavy) stringResource(R.string.ma_3d_heavy, model.triangles) else null
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.ma_3d_size, nf.format(hi[0] - lo[0]), nf.format(hi[1] - lo[1]), nf.format(hi[2] - lo[2])),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(stringResource(R.string.ma_3d_unit), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(selected = unit == null, onClick = { unit = null },
                    label = { Text(stringResource(R.string.ma_3d_unit_auto, unitLabel(autoUnit))) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.0, 10.0, 1000.0, 25.4).forEach { u ->
                    FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(unitLabel(u)) })
                }
            }
            if (model.axisChoice) {
                Text(stringResource(R.string.ma_3d_axis), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FilterChip(selected = yUp, onClick = { yUp = true }, label = { Text("Y") })
                    FilterChip(selected = !yUp, onClick = { yUp = false }, label = { Text("Z") })
                }
            }
            Text(stringResource(R.string.ma_3d_origin_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            heavyText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = { onUse(Fixture3DModel.roundUnit(effUnit), if (model.axisChoice) yUp else true, heavyText) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text(stringResource(R.string.common_apply)) }
        }
    }
}

// ---- Lecture d'un document choisi ----------------------------------------------------

private sealed class Picked {
    class Ok(val name: String, val bytes: ByteArray) : Picked()
    class Error(val message: String) : Picked()
}

/** Lit un document (≤ 64 Mo, refusé AVANT lecture si la taille est déclarée). */
private fun readPicked(ctx: Context, uri: Uri): Picked {
    var name: String? = null; var size: Long? = null
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    val max = Fixture3DModel.MAX_BYTES
    val maxMb = (max / (1024 * 1024)).toInt()
    size?.let { if (it > max) return Picked.Error(ctx.getString(R.string.ma_err_too_large, ((it + 1048575) / 1048576).toInt(), maxMb)) }
    val bytes = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                total += n
                if (total > max) return Picked.Error(ctx.getString(R.string.ma_err_too_large, ((total + 1048575) / 1048576).toInt(), maxMb))
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull() ?: return Picked.Error(ctx.getString(R.string.ma_err_io))
    return Picked.Ok(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "fichier", bytes)
}

/**
 * Zip contenant une entrée RACINE « description.xml » (casse ignorée) = GDTF lisible.
 * Même lecteur (répertoire central) que la bibliothèque MA : un `.gdtf` STORED écrit en
 * flux (descripteur de données) est accepté ici comme à l'import (cf. [ZipCentralReader]).
 */
internal fun gdtfHasDescription(bytes: ByteArray): Boolean = MaProfileReader.isGdtfArchive(bytes)
