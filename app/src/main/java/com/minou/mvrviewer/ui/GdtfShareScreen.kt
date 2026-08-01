package com.minou.mvrviewer.ui

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.GdtfShareClient
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
}

/**
 * Écran GDTF Share : connexion, puis DEUX façons de remplacer les modèles 3D
 * des projecteurs (comme iOS) :
 *  - « Améliorer tout » : résolution AUTOMATIQUE (par FixtureTypeID puis
 *    fabricant+nom) — rapide mais peut ne pas tout trouver ;
 *  - liste des TYPES de projecteurs → on ouvre une RECHERCHE GDTF Share et on
 *    choisit soi-même le bon modèle (port de GDTFMappingListView + GDTFModelSearchView).
 */
// Plafond du cache de .gdtf préchargés dans la recherche (chaque entrée pèse
// plusieurs Mo) : au-delà, on ne garde plus les octets (re-téléchargement au choix).
private const val PREFETCH_CAP = 40

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GdtfShareScreen(
    scene: MvrScene,
    mvrBytes: ByteArray,
    overrides: GdtfOverrides,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(GdtfShareClient.loggedIn) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var searchSpec by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Messages de statut résolus ICI : les coroutines ci-dessous ne peuvent pas
    // appeler stringResource.
    val sGdtfReconnecting = stringResource(R.string.gdtf_reconnecting)
    val sGdtfConnected = stringResource(R.string.gdtf_connected)
    val sGdtfConnecting = stringResource(R.string.gdtf_connecting)
    val sGdtfLoginFailed = stringResource(R.string.gdtf_login_failed)
    val sGdtfResolvingFmt = stringResource(R.string.gdtf_resolving_fmt)
    val sGdtfResolvedFmt = stringResource(R.string.gdtf_resolved_fmt)

    // Reconnexion automatique depuis les identifiants enregistrés (le compte
    // reste connecté d'un lancement à l'autre — plus besoin de se reconnecter).
    LaunchedEffect(Unit) {
        if (!loggedIn && com.minou.mvrviewer.mvr.GdtfCredentialStore.username(ctx) != null) {
            busy = true; status = sGdtfReconnecting
            loggedIn = GdtfShareClient.ensureLoggedIn(ctx)
            status = if (loggedIn) sGdtfConnected else ""
            busy = false
        }
    }

    // Types de projecteurs (spec → nombre), triés — comme la liste iOS.
    val specSummaries = remember(scene) {
        scene.allObjects.mapNotNull { it.gdtfSpec?.trim()?.ifEmpty { null } }
            .groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }
    }

    // Volet RECHERCHE d'un type précis (plein écran, comme la sheet iOS).
    val sp = searchSpec
    if (loggedIn && sp != null) {
        GdtfSearchPane(
            spec = sp,
            onBack = { searchSpec = null },
            onChosen = { data, rid -> overrides.setManual(sp, data, rid); searchSpec = null }
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.gdtf_share_title), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back_3d))
                }
            }
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (!loggedIn) {
                Text(
                    stringResource(R.string.gdtf_intro) + (
                        ""),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
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
                            busy = true; status = sGdtfConnecting
                            runCatching { GdtfShareClient.login(user.trim(), pass) }.fold(
                                onSuccess = {
                                    // Identifiants mémorisés (chiffrés) pour rester connecté.
                                    com.minou.mvrviewer.mvr.GdtfCredentialStore.save(ctx, user.trim(), pass)
                                    loggedIn = true; status = sGdtfConnected
                                },
                                onFailure = { status = it.message ?: sGdtfLoginFailed }
                            )
                            busy = false
                        }
                    },
                    enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.home_sign_in)) }
            } else {
                Text(stringResource(R.string.gdtf_connected_to_share), style = MaterialTheme.typography.titleSmall)
                // 1) Résolution automatique de tout.
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
                            status = sGdtfResolvedFmt.format(applied, specs.size)
                            busy = false
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text(stringResource(R.string.gdtf_improve_all)) }

                Text(
                    stringResource(R.string.gdtf_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                // 2) Liste des types → recherche manuelle.
                specSummaries.forEach { (spec, count) ->
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !busy) { searchSpec = spec }
                            .padding(vertical = 12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(spec, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                stringResource(R.string.gdtf_type_status_fmt, count, stringResource(statusLabelRes(spec, overrides))),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                OutlinedButton(
                    onClick = { GdtfShareClient.logout(); com.minou.mvrviewer.mvr.GdtfCredentialStore.clear(ctx); loggedIn = false; status = "" },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) { Text(stringResource(R.string.sync_sign_out)) }
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
private fun GdtfSearchPane(spec: String, onBack: () -> Unit, onChosen: (ByteArray, Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GdtfShareEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var downloadingRid by remember { mutableStateOf<Int?>(null) }
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
            onFailure = { error = it.message ?: "Recherche impossible." }
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
                                if (data != null) onChosen(data, entry.rid) else error = "Téléchargement échoué."
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
