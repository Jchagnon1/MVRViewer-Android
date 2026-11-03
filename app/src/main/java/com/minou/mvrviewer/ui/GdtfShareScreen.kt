package com.minou.mvrviewer.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.GdtfShareClient
import com.minou.mvrviewer.mvr.gdtfShareMessage
import com.minou.mvrviewer.mvr.GdtfShareEntry
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * Modèles GDTF téléchargés depuis GDTF Share, PAR SPEC MVR, appliqués par-dessus
 * les .gdtf embarqués (comme les gdtfOverrides iOS). `version` déclenche la
 * reconstruction de la 3D quand un modèle change. `manualSpecs` = types dont le
 * modèle a été choisi À LA MAIN (vs résolus automatiquement).
 */
class GdtfOverrides {
    val map: SnapshotStateMap<String, ByteArray> = mutableStateMapOf()
    val manualSpecs = mutableStateListOf<String>()
    /**
     * Révision GDTF Share du modèle choisi, par spec. C'est CE qui se
     * synchronise (iOS pousse une table « spec → rid ») : les octets, eux, ne
     * traversent pas le cloud. Sans ça, un projet partagé s'ouvrait sans ses
     * modèles GDTF chez l'autre membre.
     */
    val rids: SnapshotStateMap<String, Int> = mutableStateMapOf()
    var version by mutableIntStateOf(0)
    fun set(spec: String, bytes: ByteArray, rid: Int? = null) {
        map[spec] = bytes
        if (rid != null) rids[spec] = rid
        version++
    }
    fun setManual(spec: String, bytes: ByteArray, rid: Int? = null) {
        map[spec] = bytes
        if (rid != null) rids[spec] = rid
        if (spec !in manualSpecs) manualSpecs.add(spec)
        version++
    }

    /**
     * Entrées à PERSISTER avec le projet : sans les clés de rendu LOCALES des types MA
     * (`type3d:…`, cf. [Fixture3DStore]) — leurs octets vivent déjà dans le cache 3D de
     * l'appareil et ces clés ne doivent jamais être sérialisées.
     */
    fun persistable(): Map<String, ByteArray> =
        map.filterKeys { !it.startsWith(Fixture3DStore.RENDER_KEY_PREFIX) }
}

/**
 * « MODÈLES DE PROJECTEURS » (SPEC-menus-android §3.10, iOS §9.4) — ex-écran
 * « GDTF Share (modèles 3D) ». La liste des TYPES de projecteurs (état Générique
 * / Auto / Manuel) s'affiche TOUJOURS, connecté ou non. En tête :
 *  - une ligne d'état « GDTF Share : connecté » avec, au bout, un bouton « Se
 *    déconnecter » (jamais une déconnexion au simple toucher de la ligne), ou le
 *    bouton « Se connecter à GDTF Share… » (sous-volet de connexion) ;
 *  - la ligne « Bibliothèque MA » (sous-volet [maLibraryPane]).
 * « Améliorer tous » (résolution AUTOMATIQUE par FixtureTypeID puis fabricant +
 * nom) est réservé à l'état connecté. Toucher un type ouvre la RECHERCHE GDTF
 * Share de ce type (port de GDTFModelSearchView) ; déconnecté, la connexion
 * d'abord, PUIS la recherche (même enchaînement que la bibliothèque MA).
 *
 * Les sous-volets ([FixtureModelsPane]) sont internes : leur ← et le retour
 * système ramènent à la liste, jamais à la vue de base.
 */
// Plafond du cache de .gdtf préchargés dans la recherche (chaque entrée pèse
// plusieurs Mo) : au-delà, on ne garde plus les octets (re-téléchargement au choix).
private const val PREFETCH_CAP = 40

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixtureModelsScreen(
    scene: MvrScene,
    mvrBytes: ByteArray,
    overrides: GdtfOverrides,
    onBack: () -> Unit,
    /** Bibliothèque MA en sous-volet ; reçoit le retour vers la liste des types. */
    maLibraryPane: @Composable (onBack: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loggedIn by GdtfShareClient.loggedInFlow.collectAsState()
    var pane by remember { mutableStateOf<FixtureModelsPane>(FixtureModelsPane.List) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Messages de statut résolus ICI : les coroutines ci-dessous ne peuvent pas
    // appeler stringResource.
    val sGdtfReconnecting = stringResource(R.string.gdtf_reconnecting)
    val sGdtfConnected = stringResource(R.string.gdtf_connected)
    val sGdtfResolvingFmt = stringResource(R.string.gdtf_resolving_fmt)
    // « n / m types trouvés » : pluriel choisi au moment du résultat (hors composition).
    val resGdtf = androidx.compose.ui.platform.LocalContext.current.resources

    // Reconnexion automatique depuis les identifiants enregistrés (le compte
    // reste connecté d'un lancement à l'autre — plus besoin de se reconnecter).
    LaunchedEffect(Unit) {
        if (!GdtfShareClient.loggedIn && com.minou.mvrviewer.mvr.GdtfCredentialStore.username(ctx) != null) {
            busy = true; status = sGdtfReconnecting
            val ok = GdtfShareClient.ensureLoggedIn(ctx)
            status = if (ok) sGdtfConnected else ""
            busy = false
        }
    }

    // Types de projecteurs (spec → nombre), triés — comme la liste iOS.
    val specSummaries = remember(scene) {
        scene.allObjects.mapNotNull { it.gdtfSpec?.trim()?.ifEmpty { null } }
            .groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }
    }

    // ---- Sous-volets internes (plein écran, comme les sheets iOS) ----
    when (val p = pane) {
        is FixtureModelsPane.Search -> {
            BackHandler { pane = FixtureModelsNav.back(p) }
            GdtfSearchPane(
                spec = p.spec,
                onBack = { pane = FixtureModelsNav.back(p) },
                onChosen = { data, rid -> overrides.setManual(p.spec, data, rid); pane = FixtureModelsPane.List }
            )
            return
        }
        is FixtureModelsPane.Login -> {
            BackHandler { pane = FixtureModelsNav.back(p) }
            GdtfShareLoginPane(
                title = stringResource(R.string.gdtf_share_title),
                onBack = { pane = FixtureModelsNav.back(p) },
                onLoggedIn = { pane = FixtureModelsNav.afterLogin(p) }
            )
            return
        }
        FixtureModelsPane.MaLibrary -> {
            // Retour depuis la LISTE de la bibliothèque : vers les types (ses pages
            // internes ont leur propre retour, composé après, donc prioritaire).
            BackHandler { pane = FixtureModelsPane.List }
            maLibraryPane { pane = FixtureModelsPane.List }
            return
        }
        FixtureModelsPane.List -> {}
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.models_title), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            // 1) Ligne d'état GDTF Share.
            if (loggedIn) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("models.gdtfStatus")
                ) {
                    Icon(Icons.Filled.CloudDone, contentDescription = null, tint = Color(0xFF2E7D32))
                    Text(
                        stringResource(R.string.models_gdtf_signed_in),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(start = 10.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            GdtfShareClient.logout()
                            com.minou.mvrviewer.mvr.GdtfCredentialStore.clear(ctx)
                            status = ""
                        },
                        enabled = !busy
                    ) { Text(stringResource(R.string.sync_sign_out)) }
                }
            } else {
                OutlinedButton(
                    onClick = { pane = FixtureModelsPane.Login(null) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("models.gdtfSignIn")
                ) {
                    Icon(Icons.Filled.Cloud, contentDescription = null)
                    Text("  " + stringResource(R.string.models_gdtf_sign_in))
                }
            }
            // 2) Bibliothèque MA (sous-volet).
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !busy) { pane = FixtureModelsPane.MaLibrary }
                    .padding(vertical = 14.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ma_library), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = 10.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()

            // 3) Résolution automatique de tout : connecté seulement.
            if (loggedIn) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val specs = specSummaries.map { it.first }.toSet()
                            var done = 0; var applied = 0
                            for (spec in specs) {
                                status = sGdtfResolvingFmt.format(done + 1, specs.size)
                                val bytes = withContext(Dispatchers.IO) {
                                    val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
                                    val embedded = cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
                                        ?: return@withContext null
                                    val identity = GdtfShareClient.identity(embedded) ?: return@withContext null
                                    runCatching { GdtfShareClient.downloadBest(identity) }.getOrNull()
                                }
                                if (bytes != null) { overrides.set(spec, bytes); applied++ }
                                done++
                            }
                            status = resGdtf.getQuantityString(R.plurals.gdtf_resolved, specs.size, applied, specs.size)
                            busy = false
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text(stringResource(R.string.gdtf_improve_all)) }
            }

            // « …ou choisis toi-même… » prolonge « Améliorer tous » : sans lui
            // (déconnecté), la liste suit directement.
            if (loggedIn) Text(
                stringResource(R.string.gdtf_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            // 4) Liste des types → recherche manuelle (connexion d'abord si besoin).
            specSummaries.forEachIndexed { i, (spec, count) ->
                // Déconnecté, la ligne « Bibliothèque MA » a déjà son trait en dessous.
                if (i > 0 || loggedIn) HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !busy) { pane = FixtureModelsNav.tapType(spec, loggedIn) }
                        .padding(vertical = 12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(spec, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            pluralStringResource(R.plurals.gdtf_type_status, count, count, stringResource(statusLabelRes(spec, overrides))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (busy) {
                Column(Modifier.padding(top = 16.dp)) {
                    CircularProgressIndicator()
                    Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
            } else if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

/**
 * Connexion GDTF Share autonome (même logique que [GdtfShareScreen]) : reconnexion
 * silencieuse depuis les identifiants chiffrés, sinon formulaire. Réutilisée par la
 * bibliothèque MA (choix de la 3D « GDTF Share ») avant la recherche.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GdtfShareLoginPane(title: String, onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val sReconnecting = stringResource(R.string.gdtf_reconnecting)
    val sConnecting = stringResource(R.string.gdtf_connecting)
    val sLoginFailed = stringResource(R.string.gdtf_login_failed)
    LaunchedEffect(Unit) {
        if (GdtfShareClient.loggedIn) { onLoggedIn(); return@LaunchedEffect }
        if (com.minou.mvrviewer.mvr.GdtfCredentialStore.username(ctx) != null) {
            busy = true; status = sReconnecting
            val ok = GdtfShareClient.ensureLoggedIn(ctx)
            busy = false; status = ""
            if (ok) onLoggedIn()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.gdtf_intro), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp))
            OutlinedTextField(
                value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.gdtf_username)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, label = { Text(stringResource(R.string.sync_password)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
            Button(
                onClick = {
                    scope.launch {
                        busy = true; status = sConnecting
                        runCatching { GdtfShareClient.login(user.trim(), pass) }.fold(
                            onSuccess = {
                                com.minou.mvrviewer.mvr.GdtfCredentialStore.save(ctx, user.trim(), pass)
                                status = ""; busy = false; onLoggedIn()
                            },
                            onFailure = { status = it.gdtfShareMessage(ctx) ?: sLoginFailed; busy = false }
                        )
                    }
                },
                enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.home_sign_in)) }
            GdtfSignupLink()
            if (busy) CircularProgressIndicator(Modifier.padding(top = 16.dp))
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/**
 * Lien d'inscription sous « Se connecter » (la doc officielle de l'API demande de diriger
 * un utilisateur sans compte vers l'inscription) : ouvre gdtf-share.com/signup dans le
 * navigateur du système.
 */
@Composable
private fun GdtfSignupLink() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.TextButton(
        onClick = {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GDTF_SIGNUP_URL))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        modifier = Modifier.padding(top = 4.dp)
    ) { Text(stringResource(R.string.gdtf_signup), style = MaterialTheme.typography.bodySmall) }
}

internal const val GDTF_SIGNUP_URL = "https://gdtf-share.com/signup"

@androidx.annotation.StringRes
private fun statusLabelRes(spec: String, overrides: GdtfOverrides): Int = when {
    spec in overrides.manualSpecs -> R.string.gdtf_status_manual
    overrides.map.containsKey(spec) -> R.string.gdtf_status_auto
    else -> R.string.gdtf_status_generic
}

/**
 * Recherche GDTF Share pour UN type de projecteur (port de GDTFModelSearchView).
 * Champ de recherche + liste des révisions (fabricant — modèle, note). Toucher
 * une ligne télécharge le .gdtf et l'applique à ce type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GdtfSearchPane(
    spec: String,
    onBack: () -> Unit,
    onChosen: (ByteArray, Int) -> Unit,
    /** Requête pré-remplie (bibliothèque MA : `<fixture>` du profil, SPEC-ma.md §4.2). */
    initialQuery: String = ""
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<GdtfShareEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var downloadingRid by remember { mutableStateOf<Int?>(null) }
    val searchCtx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // Disponibilité d'un VRAI modèle 3D par révision (rid) : absent = pas encore
    // vérifié (spinner). Le .gdtf téléchargé pour la vérif est réutilisé au choix.
    val modelAvail = remember { mutableStateMapOf<Int, Boolean>() }
    // Cache des .gdtf préchargés (réutilisés au choix d'une ligne). BORNÉ : chaque
    // .gdtf pèse plusieurs Mo → sans plafond ni purge, faire défiler/rechercher
    // faisait exploser la mémoire. Vidé à chaque nouvelle requête.
    val prefetched = remember { HashMap<Int, ByteArray>() }
    val checking = remember { mutableStateMapOf<Int, Boolean>() }
    // Limite les téléchargements de sonde simultanés (défilement rapide).
    val probeGate = remember { Semaphore(4) }

    // Recherche initiale + à chaque frappe (léger anti-rebond).
    LaunchedEffect(query) {
        loading = true
        kotlinx.coroutines.delay(250)
        // Nouvelle requête → on oublie les .gdtf préchargés de la précédente
        // (sinon la map grossit sans borne au fil des recherches).
        prefetched.clear(); modelAvail.clear(); checking.clear()
        runCatching { GdtfShareClient.search(query) }.fold(
            onSuccess = { results = it; error = null },
            onFailure = { error = it.gdtfShareMessage(searchCtx) ?: searchCtx.getString(R.string.gdtf_search_failed) }
        )
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(spec, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(stringResource(R.string.gdtf_search_hint)) }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (loading && results.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.rid }) { entry ->
                // Vérifie en arrière-plan (à l'affichage de la ligne) si cette
                // révision embarque un vrai modèle 3D — throttlé aux lignes visibles.
                LaunchedEffect(entry.rid) {
                    if (modelAvail.containsKey(entry.rid) || checking.containsKey(entry.rid)) return@LaunchedEffect
                    checking[entry.rid] = true
                    val data = probeGate.withPermit {
                        runCatching { GdtfShareClient.download(entry.rid) }.getOrNull()
                    }
                    checking.remove(entry.rid)
                    if (data == null) { modelAvail[entry.rid] = false; return@LaunchedEffect }
                    // Ne garde le .gdtf que si le cache n'est pas déjà plein (borne
                    // mémoire) : au-delà, le choix de la ligne re-téléchargera.
                    if (prefetched.size < PREFETCH_CAP) prefetched[entry.rid] = data
                    modelAvail[entry.rid] = withContext(Dispatchers.Default) {
                        com.minou.mvrviewer.mvr.GdtfLoader.hasThreeDModel(data)
                    }
                }
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = downloadingRid == null) {
                            scope.launch {
                                downloadingRid = entry.rid; error = null
                                val data = prefetched[entry.rid]
                                    ?: runCatching { GdtfShareClient.download(entry.rid) }.getOrNull()
                                downloadingRid = null
                                if (data != null) onChosen(data, entry.rid) else error = searchCtx.getString(R.string.gdtf_download_failed)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Pastille « vrai modèle 3D » : vert = oui, orange = non, spinner = en cours.
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(28.dp), contentAlignment = Alignment.Center
                    ) {
                        when (modelAvail[entry.rid]) {
                            true -> Icon(Icons.Filled.ViewInAr, stringResource(R.string.gdtf_model_available),
                                tint = Color(0xFF2E7D32), modifier = Modifier.width(22.dp))
                            false -> Icon(Icons.Outlined.ViewInAr, stringResource(R.string.gdtf_model_missing),
                                tint = Color(0xFFEF6C00), modifier = Modifier.width(22.dp))
                            null -> CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("${entry.manufacturer} — ${entry.fixture}",
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        entry.rating?.let {
                            Text(stringResource(R.string.gdtf_rating_fmt, "%.1f".format(it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (downloadingRid == entry.rid) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}
