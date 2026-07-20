package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.ProjectStore
import com.minou.mvrviewer.mvr.ReferencePlan
import com.minou.mvrviewer.mvr.ReferencePlanTransform
import com.minou.mvrviewer.sync.AuditEntry
import com.minou.mvrviewer.sync.LocalMapper
import com.minou.mvrviewer.sync.PatchStore
import com.minou.mvrviewer.sync.RefPlanInterop
import com.minou.mvrviewer.sync.RemoteEvent
import com.minou.mvrviewer.sync.SectionPayload
import com.minou.mvrviewer.sync.SyncViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SceneMode { THREE_D, PLAN, PATCH, GDTF_SHARE, UNIVERSE }

/**
 * Hôte d'un .mvr ouvert. Comme iOS, la **vue 3D est l'écran principal** ; la
 * vue plan et la liste de patch s'atteignent depuis le menu d'options (⋯). Les
 * réglages d'affichage (fond, couleurs par calque, étiquettes) sont partagés
 * entre les vues.
 */
@Composable
fun SceneScreen(
    scene: MvrScene,
    fileName: String,
    mvrBytes: ByteArray,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sync: SyncViewModel? = null,
    onReopenBytes: (ByteArray, String) -> Unit = { _, _ -> }
) {
    var mode by remember { mutableStateOf(SceneMode.THREE_D) }
    val ctx = LocalContext.current
    // Fil d'Ariane pour le journal de diagnostic : on saura dans quelle vue
    // l'appli était juste avant un plantage/gel (ex. « vue plan »).
    LaunchedEffect(mode) { com.minou.mvrviewer.CrashReporter.note("vue affichée : $mode ($fileName)") }
    // Couleurs de fond semées depuis la préférence globale persistée, puis
    // ré-enregistrées à chaque changement (comme les @State + onChange iOS).
    val options = remember {
        SceneOptions().apply {
            background3D = BackgroundColorStore.scene3D(ctx)
            background2D = BackgroundColorStore.plan2D(ctx)
        }
    }
    // Persistance DÉBOUNCÉE : le sélecteur personnalisé émet une couleur à chaque
    // frame de glissé — sans délai, on écrirait les prefs des dizaines de fois/s.
    // Le délai est annulé/relancé à chaque changement → seule la valeur finale
    // (après 400 ms sans bouger) est écrite. L'aperçu live reste instantané.
    LaunchedEffect(options.background3D) {
        kotlinx.coroutines.delay(400); BackgroundColorStore.setScene3D(ctx, options.background3D)
    }
    LaunchedEffect(options.background2D) {
        kotlinx.coroutines.delay(400); BackgroundColorStore.setPlan2D(ctx, options.background2D)
    }
    val overrides = remember { PatchOverrides() }
    val gdtfOverrides = remember { GdtfOverrides() }
    // Plan de repère DXF importé — hissé ici pour survivre aux allers-retours
    // 3D ↔ plan (PlanScreen est recréé à chaque bascule).
    var referencePlan by remember { mutableStateOf<com.minou.mvrviewer.mvr.ReferencePlan?>(null) }
    // Calibration GPS hissée ici : sinon elle était perdue à chaque bascule
    // plan↔3D (PlanScreen recréé). Persiste aussi avec le projet.
    val calibration = remember(scene) { com.minou.mvrviewer.mvr.GeoCalibration() }
    val scope = rememberCoroutineScope()

    // PERSISTANCE PROJET : clé = empreinte du contenu du .mvr (comme iOS). On
    // restaure le travail à l'ouverture (plan DXF placé, overrides GDTF,
    // calibration), et on ré-enregistre à chaque changement.
    val projectKey = remember(mvrBytes) { ProjectStore.keyFor(mvrBytes) }
    var restored by remember(projectKey) { mutableStateOf(false) }
    LaunchedEffect(projectKey) {
        val rp = withContext(Dispatchers.IO) { ProjectStore.loadReferencePlan(ctx, projectKey) }
        val ov = withContext(Dispatchers.IO) { ProjectStore.loadOverrides(ctx, projectKey) }
        val anchors = withContext(Dispatchers.IO) { ProjectStore.loadCalibration(ctx, projectKey) }
        if (rp != null && referencePlan == null) referencePlan = rp
        ov?.let { (map, manual) ->
            map.forEach { (s, b) -> if (s in manual) gdtfOverrides.setManual(s, b) else gdtfOverrides.set(s, b) }
        }
        if (calibration.anchors.isEmpty()) anchors.forEach { calibration.addAnchor(it) }
        // Fond satellite : drapeau restauré seulement si calibré (sinon rien à géo-référencer).
        if (calibration.isCalibrated) options.showSatellite = ProjectStore.loadShowSatellite(ctx, projectKey)
        restored = true
    }
    // Sauvegarde des modèles GDTF appliqués quand ils changent (action utilisateur).
    LaunchedEffect(gdtfOverrides.version) {
        if (restored && gdtfOverrides.version > 0) withContext(Dispatchers.IO) {
            ProjectStore.saveOverrides(ctx, projectKey, gdtfOverrides.map.toMap(), gdtfOverrides.manualSpecs.toSet())
        }
    }
    LaunchedEffect(options.showSatellite) {
        if (restored) withContext(Dispatchers.IO) { ProjectStore.saveShowSatellite(ctx, projectKey, options.showSatellite) }
    }

    // Fond satellite géo-référencé, partagé plan + 3D. Téléchargé quand activé
    // ET calibré ; re-téléchargé si la calibration (calibTick) ou le plan change.
    val fixturesXY = remember(scene) {
        scene.fixtures.mapNotNull {
            val t = it.transform.translation
            if (t[0].isFinite() && t[1].isFinite()) t[0] to t[1] else null
        }
    }
    var calibTick by remember(scene) { mutableIntStateOf(0) }
    var satellite by remember(scene) { mutableStateOf<com.minou.mvrviewer.mvr.SatelliteOverlay?>(null) }
    LaunchedEffect(options.showSatellite, calibTick, referencePlan) {
        if (!options.showSatellite || !calibration.isCalibrated) { satellite = null; return@LaunchedEffect }
        val b = com.minou.mvrviewer.mvr.SatelliteFetcher.worldBounds(fixturesXY, referencePlan) ?: return@LaunchedEffect
        val key = com.minou.mvrviewer.mvr.SatelliteFetcher.keyFor(calibration, b[0], b[1], b[2], b[3])
        if (satellite?.key == key) return@LaunchedEffect
        com.minou.mvrviewer.mvr.SatelliteFetcher.fetch(calibration, b[0], b[1], b[2], b[3])?.let { satellite = it }
    }

    // ---------------- SYNCHRO CLOUD ----------------
    // Anti-écho : dernière valeur POUSSÉE/REÇUE par section, pour ne pas la
    // ré-émettre (boucle) ni écraser le cloud (LWW) avec une valeur par défaut.
    var refPlanSha by remember(projectKey) { mutableStateOf<String?>(null) }
    var lastCalibSig by remember(projectKey) { mutableStateOf("") }
    var lastSatellitePushed by remember(projectKey) { mutableStateOf<Boolean?>(null) }
    var lastTransformSig by remember(projectKey) { mutableStateOf("") }
    // Calques DXF masqués connus du cloud : Android n'a pas encore d'éditeur de
    // calques, mais on ré-émet cette valeur (au lieu d'une liste vide) → un push
    // manifeste (satellite/plan) n'efface pas la sélection d'un collègue iOS.
    var remoteHiddenLayers by remember(projectKey) { mutableStateOf<List<String>>(emptyList()) }
    val authState = sync?.auth?.collectAsState()
    val curProject = sync?.currentProject?.collectAsState()

    fun transformSig(t: ReferencePlanTransform) =
        "${t.offsetX},${t.offsetY},${t.rotationDeg},${t.scale},${t.heightZ},${t.visible}"

    // Applique une section DISTANTE — partagé par l'instantané d'ouverture ET le
    // flux live (mirroir de applyRemote/applySnapshot iOS, champ par champ).
    suspend fun applySection(p: SectionPayload) {
        when (p) {
            is SectionPayload.Patch -> {
                overrides.applyPersisted(LocalMapper.toEdits(p.dto))
                withContext(Dispatchers.IO) { PatchStore.save(ctx, projectKey, overrides.toPersistedList()) }
            }
            is SectionPayload.Calibration -> {
                val anchors = LocalMapper.toAnchors(p.dto)
                lastCalibSig = anchors.joinToString("|") { "${it.worldX},${it.worldY},${it.latitude},${it.longitude}" }
                calibration.reset(); anchors.forEach { calibration.addAnchor(it) }
                withContext(Dispatchers.IO) { ProjectStore.saveCalibration(ctx, projectKey, calibration.anchors) }
                calibTick++
            }
            is SectionPayload.Manifest -> {
                val m = p.dto
                m.refPlanBlobSHA?.let { refPlanSha = it }
                remoteHiddenLayers = m.refPlanHiddenLayers
                m.showSatellite?.let { lastSatellitePushed = it; options.showSatellite = it }
                m.refPlanTransform?.let { t ->
                    val nt = LocalMapper.toTransform(t)
                    lastTransformSig = transformSig(nt)
                    referencePlan?.let { rp ->
                        referencePlan = ReferencePlan(rp.plan, nt)
                        withContext(Dispatchers.IO) { ProjectStore.saveTransform(ctx, projectKey, nt) }
                    }
                }
                if (referencePlan == null && m.refPlanBlobSHA != null) {
                    val bytes = sync?.downloadRefPlan(m.refPlanBlobSHA)
                    val plan = bytes?.let { RefPlanInterop.decode(it) }
                    if (plan != null) {
                        val t = m.refPlanTransform?.let { LocalMapper.toTransform(it) } ?: ReferencePlanTransform()
                        val rp = ReferencePlan(plan, t)
                        referencePlan = rp
                        withContext(Dispatchers.IO) { ProjectStore.saveReferencePlan(ctx, projectKey, rp, m.dxfName) }
                    }
                }
            }
            else -> {} // layerColors/labelSides/orientations/gdtfMappings : reçus, non appliqués en v1
        }
    }

    // Pousse TOUT l'état local courant (au moment du partage) — mirroir de
    // pushFullStateSnapshot iOS : un collègue qui rejoint voit le patch, la
    // calibration et le placement du plan déjà faits AVANT le partage.
    suspend fun pushAllLocalState() {
        val s = sync ?: return
        if (!s.isCurrentProjectShared) return
        val rp = referencePlan
        val sha = if (rp != null) s.uploadRefPlan(RefPlanInterop.encode(rp.plan)) else refPlanSha
        refPlanSha = sha
        rp?.transform?.let { lastTransformSig = transformSig(it) }
        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey), rp?.transform,
            remoteHiddenLayers, options.showSatellite, sha)
        lastSatellitePushed = options.showSatellite
        if (calibration.isCalibrated) {
            lastCalibSig = calibration.anchors.joinToString("|") { "${it.worldX},${it.worldY},${it.latitude},${it.longitude}" }
            s.pushCalibration(calibration.anchors)
        }
        if (overrides.edits.isNotEmpty()) s.pushPatch(scene, overrides.toPersistedList())
    }

    // Rattache le projet cloud à l'ouverture ET à la connexion (réunion par empreinte).
    LaunchedEffect(projectKey, authState?.value) { sync?.attach(projectKey, mvrBytes) }

    // Item 1 : tirer l'instantané cloud dès que le projet est réuni (le flux live
    // ne rejoue pas le passé — un joiner/rouvreur verrait sinon un état vide).
    LaunchedEffect(curProject?.value?.id) {
        val s = sync ?: return@LaunchedEffect
        if (curProject?.value == null) return@LaunchedEffect
        s.fetchSnapshot()?.let { snap ->
            snap.manifest?.let { applySection(SectionPayload.Manifest(it)) }
            snap.calibration?.let { applySection(SectionPayload.Calibration(it)) }
            snap.patch?.let { applySection(SectionPayload.Patch(it)) }
        }
    }

    // Restaure le patch persisté (survit à la fermeture, même hors-ligne).
    LaunchedEffect(projectKey) {
        val persisted = withContext(Dispatchers.IO) { PatchStore.load(ctx, projectKey) }
        if (persisted.isNotEmpty()) overrides.applyPersisted(persisted)
    }

    // Commit d'une édition de patch UTILISATEUR → audit (qui/quoi/avant→après) +
    // persistance locale + push cloud.
    LaunchedEffect(sync, projectKey) {
        val s = sync
        if (s == null) { overrides.onCommit = null; return@LaunchedEffect }
        overrides.onCommit = { fixture, old, new ->
            scope.launch(Dispatchers.IO) { PatchStore.save(ctx, projectKey, overrides.toPersistedList()) }
            s.pushPatch(scene, overrides.toPersistedList())
            val (uid, name) = s.currentAuthor
            val target = "Projecteur ${new.fixtureId ?: old.fixtureId ?: fixture.name}"
            val audits = buildList {
                if (old.fixtureId != new.fixtureId)
                    add(AuditEntry(s.newAuditId(), s.nowEpoch(), uid, name, "patch", target,
                        "Fixture ID", old.fixtureId ?: "", new.fixtureId ?: ""))
                if (old.address != new.address)
                    add(AuditEntry(s.newAuditId(), s.nowEpoch(), uid, name, "patch", target,
                        "Adresse", formatAudAddress(old.address), formatAudAddress(new.address)))
                if (old.modeName != new.modeName)
                    add(AuditEntry(s.newAuditId(), s.nowEpoch(), uid, name, "patch", target,
                        "Mode", old.modeName ?: "", new.modeName ?: ""))
            }
            s.recordAudit(audits)
        }
    }

    // Applique les changements DISTANTS live (réutilise applySection).
    LaunchedEffect(sync, projectKey) {
        val s = sync ?: return@LaunchedEffect
        s.events.collect { ev -> if (ev is RemoteEvent.Section) applySection(ev.change.payload) }
    }

    // Push de la calibration quand elle change (utilisateur), sauf écho distant.
    LaunchedEffect(calibTick) {
        val s = sync ?: return@LaunchedEffect
        if (!restored || calibTick == 0 || !s.isCurrentProjectShared) return@LaunchedEffect
        val sig = calibration.anchors.joinToString("|") { "${it.worldX},${it.worldY},${it.latitude},${it.longitude}" }
        if (sig == lastCalibSig) return@LaunchedEffect
        lastCalibSig = sig
        s.pushCalibration(calibration.anchors)
    }

    // Push du manifeste quand le satellite change (utilisateur), sauf écho distant.
    LaunchedEffect(options.showSatellite) {
        val s = sync ?: return@LaunchedEffect
        if (!restored || !s.isCurrentProjectShared) return@LaunchedEffect
        if (lastSatellitePushed == options.showSatellite) return@LaunchedEffect
        lastSatellitePushed = options.showSatellite
        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey), referencePlan?.transform,
            remoteHiddenLayers, options.showSatellite, refPlanSha)
    }

    // Dialogues de synchro.
    var showAccount by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var syncMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    when (mode) {
        SceneMode.THREE_D -> Scene3DScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            options = options,
            gdtfOverrides = gdtfOverrides,
            referencePlan = referencePlan,
            calibration = calibration,
            satellite = satellite,
            onShowPlan = { mode = SceneMode.PLAN },
            onShowPatch = { mode = SceneMode.PATCH },
            onShowGdtfShare = { mode = SceneMode.GDTF_SHARE },
            onShowUniverse = { mode = SceneMode.UNIVERSE },
            onClose = onClose,
            modifier = modifier
        )
        SceneMode.PLAN -> PlanScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            options = options,
            referencePlan = referencePlan,
            onSetReferencePlan = { rp ->
                referencePlan = rp
                // Import / retrait par l'utilisateur → persiste la géométrie DXF.
                scope.launch(Dispatchers.IO) {
                    if (rp != null) ProjectStore.saveReferencePlan(ctx, projectKey, rp, null)
                    else ProjectStore.removeReferencePlan(ctx, projectKey)
                }
                // SYNCHRO : téléverse le plan (format d'échange DXP1) + pousse le manifeste.
                sync?.let { s ->
                    scope.launch {
                        refPlanSha = if (rp != null)
                            s.uploadRefPlan(RefPlanInterop.encode(rp.plan)) else null
                        rp?.transform?.let { lastTransformSig = transformSig(it) }
                        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey),
                            rp?.transform, remoteHiddenLayers, options.showSatellite, refPlanSha)
                    }
                }
            },
            calibration = calibration,
            projectKey = projectKey,
            satellite = satellite,
            onCalibrationChanged = { calibTick++ },
            onTransformChanged = { t ->
                // Push live du placement, sauf s'il vient d'être APPLIQUÉ à distance.
                sync?.let { s ->
                    if (s.isCurrentProjectShared) {
                        val sig = transformSig(t)
                        if (sig != lastTransformSig) {
                            lastTransformSig = sig
                            scope.launch {
                                s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey), t,
                                    remoteHiddenLayers, options.showSatellite, refPlanSha)
                            }
                        }
                    }
                }
            },
            gdtfOverrides = gdtfOverrides,
            onBack = { mode = SceneMode.THREE_D },
            onShowPatch = { mode = SceneMode.PATCH },
            modifier = modifier
        )
        SceneMode.PATCH -> PatchListScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            overrides = overrides,
            onBack = { mode = SceneMode.THREE_D },
            modifier = modifier
        )
        SceneMode.GDTF_SHARE -> GdtfShareScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            overrides = gdtfOverrides,
            onBack = { mode = SceneMode.THREE_D },
            modifier = modifier
        )
        SceneMode.UNIVERSE -> DmxUniverseScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            overrides = gdtfOverrides,
            onBack = { mode = SceneMode.THREE_D },
            modifier = modifier
        )
    } // when(mode)

        // ---- Superposition de synchro (visible/cliquable au-dessus de la SurfaceView) ----
        if (sync != null) {
            // Bannière « nouvelle version → rouvrir » (haut).
            Box(Modifier.align(Alignment.TopCenter)) {
                MvrVersionBanner(sync) { notice ->
                    scope.launch {
                        val bytes = runCatching { sync.downloadMvr(notice.sha256, notice.projectId) }.getOrNull()
                        sync.consumePendingVersion()
                        if (bytes != null) onReopenBytes(bytes, fileName)
                    }
                }
            }
            // Pastille de statut + menu synchro (bas-gauche, hors des barres du haut).
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SyncStatusBadge(sync)
                Box {
                    FilledTonalButton(
                        onClick = { syncMenu = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("☁") }
                    DropdownMenu(expanded = syncMenu, onDismissRequest = { syncMenu = false }) {
                        DropdownMenuItem(text = { Text("Compte") },
                            onClick = { syncMenu = false; showAccount = true })
                        DropdownMenuItem(text = { Text("Partager ce projet") },
                            onClick = { syncMenu = false; showShare = true })
                        DropdownMenuItem(text = { Text("Historique des modifications") },
                            onClick = { syncMenu = false; showHistory = true })
                        DropdownMenuItem(text = { Text("Rejoindre un projet") },
                            onClick = { syncMenu = false; showJoin = true })
                    }
                }
            }

            if (showAccount) AccountDialog(sync) { showAccount = false }
            if (showShare) ShareProjectDialog(sync, fileName, onDismiss = { showShare = false },
                onPublished = { scope.launch { pushAllLocalState() } })
            if (showHistory) HistoryDialog(sync) { showHistory = false }
            if (showJoin) JoinProjectDialog(sync, onDismiss = { showJoin = false }) { project ->
                scope.launch {
                    runCatching { sync.downloadMvr(project) }.getOrNull()?.let {
                        onReopenBytes(it, project.name)
                    }
                }
            }
        }
    } // Box
}
