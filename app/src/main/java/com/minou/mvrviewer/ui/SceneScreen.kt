package com.minou.mvrviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SceneMode { THREE_D, PLAN, PATCH, GDTF_SHARE }

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
    modifier: Modifier = Modifier
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
            },
            calibration = calibration,
            projectKey = projectKey,
            satellite = satellite,
            onCalibrationChanged = { calibTick++ },
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
    }
}
