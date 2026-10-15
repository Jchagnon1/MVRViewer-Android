package com.minou.mvrviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.minou.mvrviewer.mvr.MvrSceneObject
import com.minou.mvrviewer.mvr.ProjectStore
import com.minou.mvrviewer.mvr.ReferencePlan
import com.minou.mvrviewer.mvr.ReferencePlanTransform
import com.minou.mvrviewer.sync.AuditCoding
import com.minou.mvrviewer.sync.AuditEntry
import com.minou.mvrviewer.sync.AuditFieldKey
import com.minou.mvrviewer.sync.LocalMapper
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.sync.PatchStore
import com.minou.mvrviewer.sync.PowerLibraryStore
import com.minou.mvrviewer.sync.RefPlanInterop
import com.minou.mvrviewer.sync.RemoteEvent
import com.minou.mvrviewer.sync.SectionPayload
import com.minou.mvrviewer.sync.SyncViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SceneMode { THREE_D, PLAN, PATCH, GDTF_SHARE, UNIVERSE, CABLING, MA_LIBRARY }

/**
 * Compteur incrémenté à chaque RETOUR du réseau (réseau par défaut disponible) : sert de
 * clé pour retenter les téléchargements de 3D en échec « hors ligne » (salle sans réseau).
 * Rappels reçus sur le fil principal ; sans effet si le service est indisponible.
 */
@Composable
private fun rememberNetworkEpoch(): Int {
    val ctx = LocalContext.current
    var epoch by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(ctx) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { epoch++ }
        }
        val registered = cm != null && runCatching {
            cm.registerDefaultNetworkCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
        }.isSuccess
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return epoch
}

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
    onReopenBytes: (ByteArray, String) -> Unit = { _, _ -> },
    /// Objet 3D à importer dès l'ouverture (fichier 3D choisi à l'accueil).
    initialModelUri: android.net.Uri? = null,
    onInitialModelConsumed: () -> Unit = {},
    // Avancement NOMMÉ du chargement (#4) : la vue 3D poursuit la progression
    // commencée à l'accueil (lecture/parse) jusqu'à 100 %.
    progress: LoadProgress? = null,
    // Bibliothèque MA partagée avec l'accueil + file des profils reçus du système.
    maLibrary: MaLibraryState = remember { MaLibraryState() },
    maInbox: MaInbox? = null
) {
    val ctx = LocalContext.current
    // Vue SEMÉE depuis l'état persisté : on rouvre l'app sur la vue qu'on
    // avait sous les yeux en la quittant (parité ViewStateStore iOS).
    var mode by remember { mutableStateOf(ViewStateStore.mode(ctx)) }
    // Fil d'Ariane pour le journal de diagnostic : on saura dans quelle vue
    // l'appli était juste avant un plantage/gel (ex. « vue plan »).
    LaunchedEffect(mode) { com.minou.mvrviewer.CrashReporter.note("vue affichée : $mode ($fileName)") }
    // Couleurs de fond semées depuis la préférence globale persistée, puis
    // ré-enregistrées à chaque changement (comme les @State + onChange iOS).
    val options = remember {
        SceneOptions().apply {
            background3D = BackgroundColorStore.scene3D(ctx)
            background2D = BackgroundColorStore.plan2D(ctx)
            // Réglages d'affichage retrouvés à la réouverture — semés ICI,
            // de façon SYNCHRONE : une restauration différée (LaunchedEffect)
            // serait écrasée par les effets d'écriture ci-dessous, qui
            // partiraient avec les défauts.
            ViewStateStore.seed(ctx, this)
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
    // ÉTAT DE VUE persisté à CHAQUE changement (pas à la mise en arrière-plan :
    // une app tuée doit quand même retrouver son écran). Un seul effet qui lit
    // tous les champs suivis : chaque changement le relance, l'écriture est
    // débouncée pour absorber les rafales (curseur d'opacité satellite).
    LaunchedEffect(mode) { if (mode == SceneMode.THREE_D || mode == SceneMode.PLAN) {
        withContext(Dispatchers.IO) { ViewStateStore.setMode(ctx, mode) } } }
    LaunchedEffect(options.layerColors, options.showLabels, options.showStructure,
                   options.showLegend, options.hideLabelsWhenZoomedOut,
                   options.labelFields, options.labelDetached,
                   options.labelSize, options.labelOffset,
                   options.satelliteOpacity, options.gpsMarkerScale) {
        kotlinx.coroutines.delay(400)
        withContext(Dispatchers.IO) { ViewStateStore.save(ctx, options) }
    }
    // N11 — dispositions des barres d'outils ancrables (vue 3D / vue plan). Semées
    // depuis le store GLOBAL persisté (par appareil, hors projet/cloud), ré-écrites
    // à chaque changement. Pas de debounce : les changements de disposition sont
    // rares (contrairement aux couleurs, émises à chaque frame de glissé).
    var layout3D by remember { mutableStateOf(ToolbarLayoutStore.load3D(ctx)) }
    var layoutPlan by remember { mutableStateOf(ToolbarLayoutStore.loadPlan(ctx)) }
    LaunchedEffect(layout3D) { withContext(Dispatchers.IO) { ToolbarLayoutStore.save3D(ctx, layout3D) } }
    LaunchedEffect(layoutPlan) { withContext(Dispatchers.IO) { ToolbarLayoutStore.savePlan(ctx, layoutPlan) } }
    val overrides = remember { PatchOverrides() }
    // N12 — fiche d'édition patch ouverte par APPUI LONG sur un projecteur (en 3D
    // OU en plan). Hissée ici : la MÊME FixtureDetailSheet que la liste de patch,
    // et `overrides`/`power` (dont elle dépend) vivent déjà à ce niveau. Non nulle
    // = la fiche est présentée par-dessus la vue courante.
    var editFixture by remember { mutableStateOf<MvrSceneObject?>(null) }
    val gdtfOverrides = remember { GdtfOverrides() }
    // Projecteurs custom V1 : biblio LOCALE (par appareil, hors projet) + résolveur
    // central (défs du PROJET ∪ biblio LOCALE, projet prioritaire). L'assignation
    // fixture→custom voyage via la section PATCH ; les DÉFINITIONS via customFixtures.
    val customLibrary = remember { CustomFixtureLibrary() }
    val customResolver = remember { CustomFixtureResolver() }
    // Bibliothèque MA (SPEC-ma.md) : profils importés (locaux, chargés à la première
    // ouverture de l'écran, état `maLibrary` reçu en paramètre) + cache 3D des types
    // « profil MA + 3D choisie ».
    val fixture3DStore = remember { Fixture3DStore.of(ctx) }
    // Profil REÇU (« Ouvrir avec », partage) : la bibliothèque s'ouvre PAR-DESSUS le show,
    // qui reste ouvert ; l'import démarre aussitôt (SPEC-ma-gdtfshare.md §5.1).
    if (maInbox != null) LaunchedEffect(maInbox.batches.size) {
        if (!maInbox.isEmpty) mode = SceneMode.MA_LIBRARY
    }
    // Dernière sélection de projecteurs (clés d'INSTANCE) en 3D ou en plan — sert à
    // « Enregistrer et affecter à la sélection » depuis la bibliothèque MA.
    var selectionKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    // Bibliothèque de puissances (outil de câblage) : cache réactif de la conso
    // par type. Semée du cache disque global, remplie par extraction GDTF +
    // fusion cloud (cf. LaunchedEffect plus bas).
    val power = remember { PowerLibraryState() }
    // Câblage électrique (phase 2) : distributeurs + affectations + réglages.
    // Réactif ; restauré du projet, poussé/appliqué comme le patch.
    val cabling = remember { PowerCablingState() }
    // Câblage DMX (phase 3) : lignes DMX simples/multipaires + affectations.
    // Même cycle de vie que la Socapex : restauré, poussé/appliqué comme le patch.
    val dmxCabling = remember { DmxCablingState() }
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
    // Projecteurs à identité stable = affectables au câblage (comme le patch).
    val validFixtureUuids = remember(scene) { scene.fixtures.mapNotNull { it.uuid }.toSet() }
    var restored by remember(projectKey) { mutableStateOf(false) }
    // Calques DXF masqués (visibilité par calque) — déclaré tôt car chargé dans la
    // restauration ci-dessous ; partagé plan/3D, persisté par projet, synchronisé.
    var hiddenLayers by remember(projectKey) { mutableStateOf<Set<String>>(emptySet()) }
    // Éléments MVR masqués (identité d'INSTANCE, cf. mvrInstanceKey). Hissé ici
    // pour survivre aux bascules 3D ↔ plan — PlanScreen est détruit à chaque
    // aller-retour, un état local se perdrait à la première visite en 3D.
    var hiddenElements by remember(projectKey) { mutableStateOf<Set<String>>(emptySet()) }
    // CLASSES Vectorworks masquées — filtre SÉPARÉ et cumulatif avec les calques
    // (une classe traverse les calques). Réglage d'AFFICHAGE LOCAL, persisté par
    // projet, volontairement HORS synchro (comme le LOD par calque).
    var hiddenClasses by remember(projectKey) { mutableStateOf<Set<String>>(emptySet()) }
    var showClasses by remember { mutableStateOf(false) }
    // Liste des classes + effectifs : `classCounts()` parcourt TOUS les objets
    // (11 000+ sur un gros show) — mémoïsé par scène, jamais recalculé au fil des
    // recompositions.
    val classRows = remember(scene) { scene.classCounts() }
    // Scène RÉELLEMENT rendue : privée des classes masquées. Filtrer ici, à la
    // source, fait hériter du masquage la vue 3D ET la vue plan sans toucher à
    // leurs moteurs de rendu (cf. MvrScene.hidingClasses).
    val visibleScene = remember(scene, hiddenClasses) { scene.hidingClasses(hiddenClasses) }
    // LOD d'interaction par calque MVR (#1) : réglage d'AFFICHAGE 3D, persisté
    // LOCALEMENT avec le projet. Volontairement HORS synchro cloud (la fluidité
    // dépend de l'appareil) : aucun DTO ni contrat de synchro n'est touché.
    // Map vide = tous les calques en « Auto » (comportement historique).
    var layerLod by remember(projectKey) { mutableStateOf<Map<String, LayerLodMode>>(emptyMap()) }
    // Ensemble SOLO (identité d'INSTANCE, comme hiddenElements) : quand il est
    // non vide, la vue plan n'affiche QUE ces éléments — c'est l'inverse exact du
    // masquage. Hissé ici pour la MÊME raison que hiddenElements : PlanScreen est
    // détruit à chaque aller-retour 3D ↔ plan, un état local se perdrait dès la
    // première visite en 3D. En revanche il n'est NI persisté NI synchronisé : le
    // solo est un filtre d'affichage PERSONNEL (une « vue »), pas une modification
    // du show — contrairement au masquage qui, lui, est une décision partagée.
    var soloElements by remember(projectKey) { mutableStateOf<Set<String>>(emptySet()) }
    // Modèles 3D importés (chantier #3) : DÉCOR posé par-dessus le show. Hissé ici
    // (comme le plan de repère) pour survivre aux bascules 3D ↔ plan, et persisté
    // LOCALEMENT avec le projet — volontairement HORS synchro cloud, même décision
    // que le plan de repère MATRICIEL du chantier #2 (cf. isRasterPlan /
    // manifestTransform plus bas) et que layerLod / labelOffsets.
    var importedModels by remember(projectKey) {
        mutableStateOf<List<com.minou.mvrviewer.mvr.ImportedModel>>(emptyList())
    }
    // Le placement d'un modèle est un objet MUTABLE non observable : cette version
    // s'incrémente à chaque réglage et sert de clé de reconstruction à la 3D.
    var importedModelsVersion by remember(projectKey) { mutableIntStateOf(0) }
    // CIBLE D'AFFECTATION « sur le plan » (E2) : hissée ici car PlanScreen est recréé à
    // chaque bascule CABLING ↔ PLAN — un état local ne survivrait pas au passage par le
    // câblage. Non nulle = la vue plan est en mode affectation vers ce circuit / départ.
    var assignTarget by remember(projectKey) { mutableStateOf<CablingAssignTarget?>(null) }
    // MÉMOIRE de l'écran Câblage (SPEC-cablage-plan §7) : l'écran est refermé à
    // chaque passage par le plan ; onglet, position par onglet et voie travaillée
    // vivent ICI, le temps de la session du projet (objet non observé : écrit au
    // défilement, relu seulement à l'apparition d'une liste).
    val cablingMemory = remember(projectKey) { CablingScreenMemoryState() }
    // SEUL point d'écriture de la cible : chaque voie ouverte sur le plan (entrée,
    // ◀, ▶) devient la « dernière voie active », celle que l'écran Câblage remettra
    // sous les yeux ; son libellé est FIGÉ ici (gardé si la voie disparaît).
    fun setAssignVoie(voie: CablingVoie) {
        assignTarget = CablingAssignTarget.forVoie(voie, cabling.distributors, dmxCabling.distributors)
        cablingMemory.rememberWorkedVoie(voie)
    }
    // ◀ ▶ de la barre : voie précédente / suivante DANS LA MÊME FAMILLE, sans
    // bouclage ; sans effet au bout ou si la voie a disparu.
    fun stepAssign(step: Int) {
        val t = assignTarget ?: return
        val order = when (t.kind) {
            CablingAssignTarget.Kind.SOCA -> CablingVoieNavigator.voies(cabling.toDTO())
            CablingAssignTarget.Kind.DMX -> CablingVoieNavigator.voies(dmxCabling.toDTO())
        }
        CablingVoieNavigator.neighbor(t.voie, step, order)?.let { setAssignVoie(it) }
    }
    // « Terminé » (barre, flèche du plan, retour système) : quitte le mode et
    // rouvre l'écran Câblage SUR LA DERNIÈRE VOIE ACTIVE (bon onglet, voie mise
    // sous les yeux et surlignée une fois).
    fun finishAssign() {
        assignTarget = null
        mode = SceneMode.CABLING
    }
    // Le plan quitté par un autre chemin (menu, autre vue) : le mode affectation
    // est TOUJOURS désarmé — sinon une réouverture normale hériterait de la cible.
    LaunchedEffect(mode) { if (mode != SceneMode.PLAN) assignTarget = null }
    var lastHiddenSig by remember(projectKey) { mutableStateOf("") }
    fun hiddenSig(s: Set<String>) = AuditCoding.encodeLayers(s)
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
        val hidden = withContext(Dispatchers.IO) { ProjectStore.loadRefPlanHiddenLayers(ctx, projectKey) }
        if (hidden.isNotEmpty()) { hiddenLayers = hidden; lastHiddenSig = hiddenSig(hidden) }
        // LOD par calque : clé absente = « Auto » partout (projet existant inchangé).
        val lod = withContext(Dispatchers.IO) { ProjectStore.loadLayerLod(ctx, projectKey) }
        if (lod.isNotEmpty()) {
            layerLod = lod.mapValues { (_, v) -> LayerLodMode.fromStored(v) }
                .filterValues { it != LayerLodMode.AUTO }
        }
        // Classes masquées : même contrat que le LOD (local, hors synchro).
        val hc = withContext(Dispatchers.IO) { ProjectStore.loadHiddenClasses(ctx, projectKey) }
        if (hc.isNotEmpty()) hiddenClasses = hc
        // Modèles 3D importés : octets relus + RE-PARSÉS, plafonds re-vérifiés.
        val ims = withContext(Dispatchers.IO) { ProjectStore.loadImportedModels(ctx, projectKey) }
        if (ims.isNotEmpty() && importedModels.isEmpty()) {
            importedModels = ims
            importedModelsVersion++
        }
        restored = true
    }
    // Sauvegarde LOCALE du réglage LOD par calque : ni audit, ni push cloud —
    // c'est un réglage d'affichage propre à l'appareil.
    LaunchedEffect(layerLod) {
        if (!restored) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            ProjectStore.saveLayerLod(ctx, projectKey, layerLod.mapValues { (_, m) -> m.stored })
        }
    }
    // Idem pour les classes masquées : un seul point d'écriture, pour tous les
    // endroits qui touchent l'état partagé (bascule par ligne, « Tout masquer »).
    LaunchedEffect(hiddenClasses) {
        if (!restored) return@LaunchedEffect
        withContext(Dispatchers.IO) { ProjectStore.saveHiddenClasses(ctx, projectKey, hiddenClasses) }
    }
    // Sauvegarde des modèles GDTF appliqués quand ils changent (action utilisateur).
    LaunchedEffect(gdtfOverrides.version) {
        if (restored && gdtfOverrides.version > 0) withContext(Dispatchers.IO) {
            ProjectStore.saveOverrides(ctx, projectKey, gdtfOverrides.persistable(), gdtfOverrides.manualSpecs.toSet())
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
    // Statut du fond (SPEC-satellite-ign.md §5.1), hissé comme l'image : il nourrit
    // la ligne d'état du menu (plan et 3D) et les Toasts.
    var satelliteStatus by remember(scene) { mutableStateOf<SatelliteStatus>(SatelliteStatus.Idle) }
    // Source IGN = France seulement : couverture jugée sur la 1re ancre (pré-filtre
    // sans réseau). Les ancres ne sont pas observables : la lecture de calibTick
    // (chaque changement de calibration) et de `restored` (ancres relues du projet)
    // abonne ce calcul aux deux moments où elles changent.
    val satelliteCovered = (calibTick to restored).let { SatelliteUi.covered(calibration) }
    // Service par show : ses mémoires (URL « vide », échec récent) vivent avec lui.
    val satService = remember(scene) {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        com.minou.mvrviewer.mvr.SatelliteFetcher.newService(com.minou.mvrviewer.mvr.SatelliteFetcher.userAgent(version))
    }
    // Dernière activation TRAITÉE : une activation non encore traitée = geste de
    // l'utilisateur → demande forcée + Toast si ça n'aboutit pas.
    var satActivationHandled by remember(scene) { mutableIntStateOf(options.satelliteActivations) }
    LaunchedEffect(options.showSatellite, calibTick, referencePlan, options.satelliteActivations) {
        val activation = options.satelliteActivations
        // Déroulé PUR (SatelliteUi.refresh, testé en JVM) : hors France AUCUNE
        // requête et l'image d'un autre lieu part (le réglage mémorisé reste : revenu
        // en France, l'image revient) ; pas d'imagerie → image retirée ; échec
        // réseau/serveur → l'image courante reste (comportement historique).
        val r = SatelliteUi.refresh(
            current = satellite, currentKey = satellite?.key, currentStatus = satelliteStatus,
            show = options.showSatellite, calibrated = calibration.isCalibrated,
            covered = SatelliteUi.covered(calibration),
            userAsked = activation != satActivationHandled,
            content = { com.minou.mvrviewer.mvr.SatelliteFetcher.worldBounds(fixturesXY, referencePlan) },
            keyOf = { b -> com.minou.mvrviewer.mvr.SatelliteFetcher.keyFor(calibration, b[0], b[1], b[2], b[3]) },
            load = { b, force ->
                com.minou.mvrviewer.mvr.SatelliteFetcher.fetch(satService, calibration, b[0], b[1], b[2], b[3], force)
            },
            onLoading = { satelliteStatus = SatelliteStatus.Loading }
        )
        satellite = r.overlay
        satelliteStatus = r.status
        satActivationHandled = activation
        r.toast?.let { SatelliteUi.toast(ctx, it) }
    }

    // ---------------- SYNCHRO CLOUD ----------------
    // Anti-écho : dernière valeur POUSSÉE/REÇUE par section, pour ne pas la
    // ré-émettre (boucle) ni écraser le cloud (LWW) avec une valeur par défaut.
    var refPlanSha by remember(projectKey) { mutableStateOf<String?>(null) }
    var lastCalibSig by remember(projectKey) { mutableStateOf("") }
    var lastSatellitePushed by remember(projectKey) { mutableStateOf<Boolean?>(null) }
    var lastTransformSig by remember(projectKey) { mutableStateOf("") }
    // Dernier placement CONNU DU PARTAGÉ (poussé ou reçu). Sert quand le plan
    // courant est MATRICIEL : on ré-émet celui-là au lieu du nôtre (cf. plus bas),
    // pour ne pas déplacer le DXF de l'équipe ni l'effacer du manifeste.
    var sharedTransform by remember(projectKey) { mutableStateOf<ReferencePlanTransform?>(null) }
    val authState = sync?.auth?.collectAsState()
    val curProject = sync?.currentProject?.collectAsState()

    fun transformSig(t: ReferencePlanTransform) = AuditCoding.encodeTransform(t)
    fun calibSig() = AuditCoding.encodeAnchors(calibration.anchors)

    // R7 — le placement est un objet MUTABLE non observable : cette VERSION
    // s'incrémente à chaque réglage (homothétie, décalage, rotation…) et sert de
    // clé de reconstruction au quad matriciel 3D, qui sinon ne verrait rien
    // changer (l'instance du plan, elle, reste la même).
    var refPlanTransformVersion by remember(projectKey) { mutableIntStateOf(0) }

    // Le plan courant est-il MATRICIEL (image / PDF) ? Un tel plan est LOCAL.
    fun isRasterPlan() = referencePlan?.raster != null

    /**
     * Placement à écrire dans le manifeste PARTAGÉ. Tant que le plan courant est
     * matriciel, on ne pousse plus le nôtre — il ferait « sauter » le DXF des
     * coéquipiers — mais on ré-émet le dernier placement partagé connu pour ne
     * pas l'effacer non plus. Sur un plan vectoriel, rien ne change.
     */
    fun manifestTransform(local: ReferencePlanTransform?): ReferencePlanTransform? {
        if (isRasterPlan()) return sharedTransform
        if (local != null) sharedTransform = local.copy()
        return local
    }

    // Fabrique une entrée d'audit AVEC ses coordonnées machine — sans elles,
    // l'entrée est un simple texte que l'historique ne saurait pas rejouer.
    fun auditEntry(
        kind: String, target: String, field: String,
        oldDisplay: String, newDisplay: String,
        objectKey: String, fieldKey: String, oldRaw: String, newRaw: String
    ): AuditEntry? {
        val s = sync ?: return null
        val (uid, name) = s.currentAuthor
        return AuditEntry(
            s.newAuditId(), s.nowEpoch(), uid, name, kind, target, field,
            oldDisplay, newDisplay, objectKey, fieldKey, oldRaw, newRaw
        )
    }

    // Libellé d'affichage d'une spec de rendu (audit) : « Standard » si aucune,
    // sinon le nom du type custom résolu, sinon la spec brute.
    fun specLabel(spec: String?): String =
        if (spec.isNullOrBlank()) "Standard" else customResolver.resolveSpec(spec)?.name ?: spec

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
                lastCalibSig = AuditCoding.encodeAnchors(anchors)
                calibration.reset(); anchors.forEach { calibration.addAnchor(it) }
                withContext(Dispatchers.IO) { ProjectStore.saveCalibration(ctx, projectKey, calibration.anchors) }
                calibTick++
            }
            is SectionPayload.Manifest -> {
                val m = p.dto
                m.refPlanBlobSHA?.let { refPlanSha = it }
                val remoteHidden = m.refPlanHiddenLayers.toSet()
                hiddenLayers = remoteHidden
                lastHiddenSig = hiddenSig(remoteHidden)
                withContext(Dispatchers.IO) { ProjectStore.saveRefPlanHiddenLayers(ctx, projectKey, remoteHidden) }
                m.showSatellite?.let { lastSatellitePushed = it; options.showSatellite = it }
                m.refPlanTransform?.let { t ->
                    // HOMOTHÉTIE + BASCULE (tilt) = réglages LOCAUX : on les
                    // PRÉSERVE tels quels, sinon un simple écho distant les
                    // remettrait à leur défaut sous les yeux de son propriétaire.
                    val nt = LocalMapper.toTransform(
                        t, referencePlan?.transform?.homothety ?: 1.0, referencePlan?.transform?.tiltDeg ?: 0.0
                    )
                    lastTransformSig = transformSig(nt)
                    sharedTransform = nt.copy()
                    referencePlan?.let { rp ->
                        // `rp.raster` reporté : un placement venu du cloud ne doit
                        // pas faire disparaître le plan image chargé localement.
                        referencePlan = ReferencePlan(rp.plan, nt, rp.raster)
                        refPlanTransformVersion++
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
            // Modèles GDTF choisis par un autre membre : le cloud transporte
            // « spec → révision GDTF Share », pas les octets. On les télécharge
            // donc ici — sans ça, un projet partagé s'ouvrait SANS ses modèles
            // GDTF à la première ouverture.
            is SectionPayload.GdtfMappings -> {
                val wanted = p.dto.mappings.filterKeys { it !in gdtfOverrides.map }
                if (wanted.isNotEmpty()) {
                    for ((spec, rid) in wanted) {
                        val data: ByteArray? = withContext(Dispatchers.IO) {
                            runCatching { com.minou.mvrviewer.mvr.GdtfShareClient.download(rid) }.getOrNull()
                        }
                        if (data != null) gdtfOverrides.set(spec, data, rid)
                    }
                    // Persiste comme un choix local : la prochaine ouverture
                    // n'aura pas besoin du réseau.
                    withContext(Dispatchers.IO) {
                        ProjectStore.saveOverrides(
                            ctx, projectKey, gdtfOverrides.persistable(), gdtfOverrides.manualSpecs.toSet()
                        )
                    }
                }
            }
            // Câblage électrique distant : on SANITISE contre les projecteurs
            // réellement présents (un projecteur disparu du show ne doit pas
            // laisser d'affectation fantôme) puis on charge + persiste localement.
            is SectionPayload.PowerCabling -> {
                val clean = p.dto.sanitized(validFixtureUuids)
                cabling.load(clean)
                withContext(Dispatchers.IO) {
                    ProjectStore.savePowerCabling(ctx, projectKey,
                        com.minou.mvrviewer.sync.PowerCablingCodec.toJsonString(clean))
                }
            }
            // Câblage DMX distant : même traitement que la Socapex — sanitisé
            // contre les projecteurs présents (pas d'affectation fantôme), chargé
            // puis persisté localement.
            is SectionPayload.DmxCabling -> {
                val clean = p.dto.sanitized(validFixtureUuids)
                dmxCabling.load(clean)
                withContext(Dispatchers.IO) {
                    ProjectStore.saveDmxCabling(ctx, projectKey,
                        com.minou.mvrviewer.sync.DmxCablingCodec.toJsonString(clean))
                }
            }
            // Défs de types custom reçues : on FUSIONNE dans la table résoluble
            // (projet ∪ biblio locale, projet prioritaire) puis on persiste
            // localement — un projet partagé s'ouvre avec ses types custom.
            is SectionPayload.CustomFixtures -> {
                customResolver.mergeProject(p.dto.types)
                withContext(Dispatchers.IO) {
                    ProjectStore.saveCustomFixtures(ctx, projectKey,
                        if (p.dto.types.isEmpty()) null
                        else com.minou.mvrviewer.sync.CustomFixturesCodec.toJsonString(p.dto))
                }
            }
            else -> {} // layerColors/labelSides/orientations : reçus, non appliqués en v1
        }
    }

    // Collecte les DÉFINITIONS de types custom UTILISÉS dans le projet (ids
    // référencées par une assignation patch), les rend PROJET (mergeProject),
    // persiste la section + pousse au cloud. Appelée après une assignation ou une
    // édition de type, et depuis pushAllLocalState.
    suspend fun syncCustomFixtures() {
        val ids = overrides.edits.values.mapNotNull { com.minou.mvrviewer.sync.customFixtureIdOf(it.spec) }.toSet()
        val types = ids.mapNotNull { customResolver.resolve(it) }
        customResolver.mergeProject(types)
        withContext(Dispatchers.IO) {
            ProjectStore.saveCustomFixtures(ctx, projectKey,
                if (types.isEmpty()) null
                else com.minou.mvrviewer.sync.CustomFixturesCodec.toJsonString(
                    com.minou.mvrviewer.sync.CustomFixturesDTO(types)))
        }
        // Bibliothèque MA (§4.3 règle (2)) : la 3D importée des types poussés doit être
        // dans le Storage du projet AVANT la section qui la référence. Filet silencieux ;
        // envois en PARALLÈLE et attente BORNÉE (FixtureBlobUploader.WAIT_MS) : la section
        // part même si un envoi n'a pas abouti (hors ligne, réseau lent) — il continue en
        // fond, nouvel essai au push suivant. « Un échec ne bloque NI l'enregistrement NI
        // le push. »
        sync?.let { s ->
            if (s.isCurrentProjectShared) {
                val shas = types.mapNotNull { it.model3D?.blobSha }.distinct().filter { fixture3DStore.hasBlob(it) }
                if (shas.isNotEmpty()) kotlinx.coroutines.coroutineScope {
                    shas.map { sha ->
                        async { s.ensureFixtureBlobUploaded(sha) { withContext(Dispatchers.IO) { fixture3DStore.readBlob(sha) } } }
                    }.awaitAll()
                }
            }
        }
        sync?.pushCustomFixtures(com.minou.mvrviewer.sync.CustomFixturesDTO(types))
    }

    // ---- Bibliothèque MA : 3D des types « profil MA + 3D choisie » (SPEC-ma.md §4) ----
    // Types RÉFÉRENCÉS par la scène (patch) : seuls eux sont rendus, donc téléchargés.
    fun referencedCustomTypes(): List<com.minou.mvrviewer.sync.CustomFixtureTypeDTO> =
        overrides.edits.values.mapNotNull { com.minou.mvrviewer.sync.customFixtureIdOf(it.spec) }.toSet()
            .mapNotNull { customResolver.resolve(it) }

    // RÉCEPTION (jamais gardée Pro) : connexion GDTF Share OBSERVABLE et retour du réseau
    // relancent les 3D en attente ; les téléchargements vivent dans des jobs DÉDIÉS du
    // scope de la scène (une relance de l'effet ne les annule ni ne les recommence).
    val shareSignedIn by com.minou.mvrviewer.mvr.GdtfShareClient.loggedInFlow.collectAsState()
    val networkEpoch = rememberNetworkEpoch()
    val syncRef = androidx.compose.runtime.rememberUpdatedState(sync)
    val type3DFetcher = remember(fixture3DStore) {
        Type3DFetcher(scope, fixture3DStore, object : Type3DFetcher.Backend {
            override suspend fun shareLogin() = com.minou.mvrviewer.mvr.GdtfShareClient.ensureLoggedInOutcome(ctx)
            override suspend fun shareDownload(rid: Int): ByteArray? = withContext(Dispatchers.IO) {
                try { com.minou.mvrviewer.mvr.GdtfShareClient.download(rid) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { null }
            }
            override fun projectShared() = syncRef.value?.isCurrentProjectShared == true
            override suspend fun blobDownload(sha256: String) =
                syncRef.value?.downloadFixtureBlob(sha256) ?: SyncViewModel.FixtureBlobDownload.Missing
        })
    }

    /**
     * INSCRIPTION des GDTF (b)(c) DÉJÀ au cache sous leur clé de rendu `type3d:…` (jamais
     * sérialisée), AVANT et indépendamment des téléchargements : un blob lent ou en échec
     * ne retarde plus l'affichage des autres types. Reconstruit la 3D si une clé est
     * ajoutée (ou si un objet 3D vient d'arriver : [forceRebuild]).
     */
    suspend fun registerCachedType3D(forceRebuild: Boolean = false) {
        val added = Type3DFetcher.registerCached(referencedCustomTypes(), fixture3DStore, gdtfOverrides)
        if (added || forceRebuild) gdtfOverrides.version++
    }

    /**
     * Enregistre un type créé depuis la bibliothèque MA (§5.7-5.8) : biblio custom locale
     * (upsert) + table projet du résolveur, PUIS affectation PAR INSTANCE à la sélection si
     * demandée (patch PUIS customFixtures, poussés par le commit), et SEULEMENT ENSUITE
     * l'attente BORNÉE de l'envoi de la 3D (« Envoi de la 3D… »). L'envoi tourne dans le
     * scope du ViewModel : quitter l'écran ne perd ni l'affectation ni l'envoi.
     * Renvoie false si l'envoi a échoué ou n'a pas fini dans le délai (note §5.7).
     */
    suspend fun saveMaType(t: com.minou.mvrviewer.sync.CustomFixtureTypeDTO, assign: Boolean): Boolean =
        saveMaTypeOrdered(
            register = {
                customLibrary.upsert(t)
                customResolver.setLibrary(customLibrary.types.toList())
                customResolver.mergeProject(listOf(t))
            },
            assign = if (!assign) null else ({
                val keys = selectionKeys.toSet()
                val targets = scene.fixtures.filter { overrides.key(it) in keys }
                overrides.assignSpecToAll(targets, com.minou.mvrviewer.sync.customFixtureSpec(t.id))
            }),
            awaitUpload = {
                val sha = t.model3D?.blobSha
                val s = sync
                if (sha != null && s != null && s.isCurrentProjectShared)
                    s.ensureFixtureBlobUploaded(sha) { withContext(Dispatchers.IO) { fixture3DStore.readBlob(sha) } }
                else null
            }
        )

    // Pousse TOUT l'état local courant (au moment du partage) — mirroir de
    // pushFullStateSnapshot iOS : un collègue qui rejoint voit le patch, la
    // calibration et le placement du plan déjà faits AVANT le partage.
    suspend fun pushAllLocalState() {
        val s = sync ?: return
        if (!s.isCurrentProjectShared) return
        val rp = referencePlan
        // ⚠ Le blob partagé est VECTORIEL (DXP1, contrat figé par iOS). Un plan
        // MATRICIEL porte un DxfPlan vide : le téléverser écraserait par un blob
        // vide le DXF de toute l'équipe. On ne pousse donc que de la géométrie
        // réelle ; l'image reste locale (cf. décision de synchro v1).
        val sha = if (rp != null && !rp.plan.isEmpty) s.uploadRefPlan(RefPlanInterop.encode(rp.plan)) else refPlanSha
        refPlanSha = sha
        if (rp?.raster == null) rp?.transform?.let { lastTransformSig = transformSig(it) }
        // Placement : celui de l'équipe si notre plan courant est une image (local).
        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey), manifestTransform(rp?.transform),
            hiddenLayers.toList(), options.showSatellite, sha)
        lastSatellitePushed = options.showSatellite
        lastHiddenSig = hiddenSig(hiddenLayers)
        if (calibration.isCalibrated) {
            lastCalibSig = calibSig()
            s.pushCalibration(calibration.anchors)
        }
        if (overrides.edits.isNotEmpty()) s.pushPatch(scene, overrides.toPersistedList())
        if (cabling.distributors.isNotEmpty() || cabling.assignments.isNotEmpty()) s.pushPowerCabling(cabling.toDTO())
        if (dmxCabling.distributors.isNotEmpty() || dmxCabling.assignments.isNotEmpty()) s.pushDmxCabling(dmxCabling.toDTO())
        // Définitions des types custom UTILISÉS dans le projet (si assignation).
        if (overrides.edits.values.any { it.spec != null }) syncCustomFixtures()
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
            snap.powerCabling?.let { applySection(SectionPayload.PowerCabling(it)) }
            snap.dmxCabling?.let { applySection(SectionPayload.DmxCabling(it)) }
            snap.customFixtures?.let { applySection(SectionPayload.CustomFixtures(it)) }
        }
    }

    // Restaure le patch persisté (survit à la fermeture, même hors-ligne).
    LaunchedEffect(projectKey) {
        val persisted = withContext(Dispatchers.IO) { PatchStore.load(ctx, projectKey) }
        if (persisted.isNotEmpty()) overrides.applyPersisted(persisted)
    }

    // Restaure le CÂBLAGE persisté (distributeurs + affectations + réglages),
    // sanitisé contre les projecteurs présents. Survit à la fermeture hors-ligne.
    LaunchedEffect(projectKey) {
        val json = withContext(Dispatchers.IO) { ProjectStore.loadPowerCabling(ctx, projectKey) } ?: return@LaunchedEffect
        val dto = com.minou.mvrviewer.sync.PowerCablingCodec.fromJsonString(json) ?: return@LaunchedEffect
        cabling.load(dto.sanitized(validFixtureUuids))
    }

    // Restaure le CÂBLAGE DMX persisté (lignes + affectations), sanitisé contre
    // les projecteurs présents. Survit à la fermeture hors-ligne (comme la Socapex).
    LaunchedEffect(projectKey) {
        val json = withContext(Dispatchers.IO) { ProjectStore.loadDmxCabling(ctx, projectKey) } ?: return@LaunchedEffect
        val dto = com.minou.mvrviewer.sync.DmxCablingCodec.fromJsonString(json) ?: return@LaunchedEffect
        dmxCabling.load(dto.sanitized(validFixtureUuids))
    }

    // ---- Projecteurs custom V1 : biblio locale + section projet ----
    // 1) Semis de la biblio LOCALE (par appareil, hors projet) + miroir résolveur.
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { CustomFixtureLibraryStore.load(ctx) }
        customLibrary.seed(list)
        customResolver.setLibrary(list)
    }
    // 2) À chaque ÉDITION de la biblio (création/édition/suppression) : re-miroir du
    //    résolveur + persistance disque locale (semis initial exclu : version == 0).
    LaunchedEffect(customLibrary.version) {
        if (customLibrary.version == 0) return@LaunchedEffect
        customResolver.setLibrary(customLibrary.types.toList())
        withContext(Dispatchers.IO) { CustomFixtureLibraryStore.save(ctx, customLibrary.types.toList()) }
    }
    // 3) Restaure les DÉFINITIONS de types custom du PROJET (fusion résoluble).
    LaunchedEffect(projectKey) {
        val json = withContext(Dispatchers.IO) { ProjectStore.loadCustomFixtures(ctx, projectKey) } ?: return@LaunchedEffect
        com.minou.mvrviewer.sync.CustomFixturesCodec.fromJsonString(json)?.let { customResolver.mergeProject(it.types) }
    }

    // Bibliothèque MA : à l'ouverture du show, à chaque section customFixtures appliquée
    // (résolveur), à chaque affectation (patch), à la (re)connexion (compte, GDTF Share) et
    // au retour du réseau → (1) inscription IMMÉDIATE des clés de rendu déjà au cache,
    // (2) téléchargements manquants lancés SANS ATTENDRE (jobs dédiés, dédoublonnés).
    // Les échecs retenus sont oubliés quand la signature change (nouvel essai).
    LaunchedEffect(customResolver.version, overrides.version, curProject?.value?.id,
                   authState?.value?.isSignedIn, shareSignedIn, networkEpoch) {
        registerCachedType3D()
        type3DFetcher.fetchMissing(
            referencedCustomTypes(),
            signature = listOf(projectKey, curProject?.value?.id, authState?.value?.isSignedIn,
                shareSignedIn, networkEpoch, customResolver.version)
        ) { m -> registerCachedType3D(forceRebuild = m is com.minou.mvrviewer.sync.CustomFixtureModel3D.ModelBlob) }
    }
    // Connexion GDTF Share réussie : les raisons « Connectez-vous… » sont périmées.
    LaunchedEffect(shareSignedIn) { if (shareSignedIn) fixture3DStore.clearShareSignInIssues() }

    // Commit d'une modification UTILISATEUR du câblage → persistance locale + push
    // cloud. Le chargement disque/distant NE déclenche PAS onChange (garde-fou LWW).
    LaunchedEffect(sync, projectKey) {
        cabling.onChange = { dto ->
            scope.launch(Dispatchers.IO) {
                ProjectStore.savePowerCabling(ctx, projectKey,
                    com.minou.mvrviewer.sync.PowerCablingCodec.toJsonString(dto))
            }
            sync?.pushPowerCabling(dto)
        }
    }

    // Idem pour le câblage DMX : persistance locale + push cloud sur édition
    // UTILISATEUR (le chargement disque/distant ne déclenche pas onChange).
    LaunchedEffect(sync, projectKey) {
        dmxCabling.onChange = { dto ->
            scope.launch(Dispatchers.IO) {
                ProjectStore.saveDmxCabling(ctx, projectKey,
                    com.minou.mvrviewer.sync.DmxCablingCodec.toJsonString(dto))
            }
            sync?.pushDmxCabling(dto)
        }
    }

    // Commit d'une édition de patch UTILISATEUR → audit (qui/quoi/avant→après) +
    // persistance locale + push cloud.
    LaunchedEffect(sync, projectKey) {
        val s = sync
        if (s == null) { overrides.onCommit = null; overrides.onCommitMany = null; return@LaunchedEffect }
        // Entrées d'audit d'UN changement (qui/quoi/avant→après).
        fun auditsOf(fixture: MvrSceneObject, old: PatchEdit, new: PatchEdit): List<AuditEntry> {
            val target = "Projecteur ${new.fixtureId ?: old.fixtureId ?: fixture.name}"
            // La clé d'INSTANCE est la seule identité stable du projecteur : le
            // libellé « Projecteur N° 213 » redevient ambigu dès le repatch suivant.
            val objKey = overrides.key(fixture)
            val audits = listOfNotNull(
                if (old.fixtureId != new.fixtureId) auditEntry(
                    "patch", target, "Fixture ID", old.fixtureId ?: "", new.fixtureId ?: "",
                    objKey, AuditFieldKey.FIXTURE_ID, old.fixtureId ?: "", new.fixtureId ?: ""
                ) else null,
                if (old.address != new.address) auditEntry(
                    "patch", target, "Adresse",
                    formatAudAddress(old.address), formatAudAddress(new.address),
                    objKey, AuditFieldKey.ADDRESS, old.address ?: "", new.address ?: ""
                ) else null,
                if (old.modeName != new.modeName) auditEntry(
                    "patch", target, "Mode", old.modeName ?: "", new.modeName ?: "",
                    objKey, AuditFieldKey.MODE, old.modeName ?: "", new.modeName ?: ""
                ) else null,
                if (old.name != new.name) auditEntry(
                    "patch", target, "Nom", old.name ?: "", new.name ?: "",
                    objKey, AuditFieldKey.NAME, old.name ?: "", new.name ?: ""
                ) else null,
                // Type de rendu custom (« custom:<id> ») assigné/retiré.
                if (old.spec != new.spec) auditEntry(
                    "patch", target, "Type de rendu", specLabel(old.spec), specLabel(new.spec),
                    objKey, AuditFieldKey.SPEC, old.spec ?: "", new.spec ?: ""
                ) else null
            )
            return audits
        }
        // UNE persistance + UN push de patch pour tout le lot (affectation d'un type à
        // une sélection), PUIS la section customFixtures si une spec a changé.
        fun commit(changes: List<Triple<MvrSceneObject, PatchEdit, PatchEdit>>) {
            if (changes.isEmpty()) return
            scope.launch(Dispatchers.IO) { PatchStore.save(ctx, projectKey, overrides.toPersistedList()) }
            s.pushPatch(scene, overrides.toPersistedList())
            s.recordAudit(changes.flatMap { (f, o, n) -> auditsOf(f, o, n) })
            // Une (dé)assignation de type custom garantit la présence de sa
            // définition dans la section customFixtures du projet + pousse.
            if (changes.any { it.second.spec != it.third.spec }) scope.launch { syncCustomFixtures() }
        }
        overrides.onCommit = { fixture, old, new -> commit(listOf(Triple(fixture, old, new))) }
        overrides.onCommitMany = { commit(it) }
    }

    // Applique les changements DISTANTS live (réutilise applySection).
    LaunchedEffect(sync, projectKey) {
        val s = sync ?: return@LaunchedEffect
        s.events.collect { ev -> if (ev is RemoteEvent.Section) applySection(ev.change.payload) }
    }

    // ---- Bibliothèque de puissances : semis + extraction GDTF + consensus cloud
    // 1) Semis du cache disque GLOBAL (hors projet, partagé par tous les projets).
    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { PowerLibraryStore.load(ctx) }
        cached.forEach { (id, e) -> power.seedConsensus(id, e.watts, e.votes) }
    }
    // 2) Extraction des puissances GDTF de tous les types du show (repli). Dépend
    //    des overrides GDTF : un modèle choisi à la main peut porter la conso que
    //    le .gdtf embarqué n'avait pas.
    LaunchedEffect(scene, gdtfOverrides.version) {
        val specs = scene.allObjects.mapNotNull { it.gdtfSpec?.trim()?.ifEmpty { null } }.distinct()
        withContext(Dispatchers.IO) {
            for (spec in specs) {
                if (power.gdtfWatts(spec) != null) continue
                val bytes = gdtfOverrides.map[spec] ?: run {
                    val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
                    cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
                } ?: continue
                val w = runCatching { com.minou.mvrviewer.mvr.GdtfPower.maxWatts(bytes) }.getOrNull()
                if (w != null) withContext(Dispatchers.Main) { power.setGdtf(spec, w) }
            }
        }
    }
    // 3) CONSENSUS CLOUD : pour chaque type, on rapatrie TOUS les votes, on
    //    calcule le consensus et on le sème dans l'état + le cache disque. No-op
    //    si non connecté (le cache disque tient alors lieu de repli).
    LaunchedEffect(sync, scene, authState?.value) {
        val s = sync ?: return@LaunchedEffect
        if (authState?.value?.isSignedIn != true) return@LaunchedEffect
        val specs = scene.allObjects.mapNotNull { it.gdtfSpec?.trim()?.ifEmpty { null } }.distinct()
        for (spec in specs) {
            val consensus = com.minou.mvrviewer.sync.powerConsensus(s.fetchPowerVotes(spec).map { it.watts })
                ?: continue
            val id = com.minou.mvrviewer.sync.powerLibraryDocId(spec)
            withContext(Dispatchers.IO) { PowerLibraryStore.put(ctx, id, consensus.watts, consensus.totalVotes) }
            power.seedConsensus(id, consensus.watts, consensus.totalVotes)
        }
    }
    // VOTE d'une puissance par l'utilisateur → dépose mon vote au cloud, récupère
    // le CONSENSUS réel (mon vote inclus), corrige l'état + le cache disque. Hors
    // ligne : consensus local de mon seul vote (bonus quand on est connecté).
    LaunchedEffect(sync, projectKey) {
        power.onCommit = { spec, _, watts ->
            scope.launch {
                val consensus = sync?.submitPowerVote(spec, watts)
                val id = com.minou.mvrviewer.sync.powerLibraryDocId(spec)
                val value = consensus?.watts ?: watts
                val votes = consensus?.totalVotes ?: 1
                withContext(Dispatchers.IO) { PowerLibraryStore.put(ctx, id, value, votes) }
                power.seedConsensus(id, value, votes)
            }
        }
    }

    // Base de comparaison après restauration disque : sans elle, la première
    // modification serait journalisée comme partant de « rien », et son
    // annulation ramènerait à un état vide au lieu de l'état rouvert.
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        if (lastCalibSig.isEmpty()) lastCalibSig = calibSig()
        if (lastTransformSig.isEmpty()) referencePlan?.transform?.let { lastTransformSig = transformSig(it) }
    }

    // Journalise + pousse la calibration quand elle change (utilisateur), sauf
    // écho distant. Le journal est tenu même hors ligne / projet non partagé.
    LaunchedEffect(calibTick) {
        val s = sync ?: return@LaunchedEffect
        if (!restored || calibTick == 0) return@LaunchedEffect
        val sig = calibSig()
        if (sig == lastCalibSig) return@LaunchedEffect
        val before = lastCalibSig
        lastCalibSig = sig
        auditEntry("calibration", "Calibration GPS", "Ancres",
            AuditCoding.describeAnchors(before), AuditCoding.describeAnchors(sig),
            "", AuditFieldKey.GEO_ANCHORS, before, sig
        )?.let { s.recordAudit(listOf(it)) }
        if (s.isCurrentProjectShared) s.pushCalibration(calibration.anchors)
    }

    // Push du manifeste quand le satellite change (utilisateur), sauf écho distant.
    LaunchedEffect(options.showSatellite) {
        val s = sync ?: return@LaunchedEffect
        if (!restored || !s.isCurrentProjectShared) return@LaunchedEffect
        if (lastSatellitePushed == options.showSatellite) return@LaunchedEffect
        lastSatellitePushed = options.showSatellite
        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey),
            manifestTransform(referencePlan?.transform),
            hiddenLayers.toList(), options.showSatellite, refPlanSha)
    }

    // Visibilité des calques DXF : persiste (par projet) + pousse le manifeste
    // quand l'utilisateur masque/affiche un calque, sauf écho distant.
    LaunchedEffect(hiddenLayers) {
        if (!restored) return@LaunchedEffect
        withContext(Dispatchers.IO) { ProjectStore.saveRefPlanHiddenLayers(ctx, projectKey, hiddenLayers) }
        val s = sync ?: return@LaunchedEffect
        val sig = hiddenSig(hiddenLayers)
        if (sig == lastHiddenSig) return@LaunchedEffect
        val before = lastHiddenSig
        lastHiddenSig = sig
        auditEntry("layers", "Plan de repère", "Calques masqués",
            AuditCoding.describeLayers(before), AuditCoding.describeLayers(sig),
            "", AuditFieldKey.REF_PLAN_HIDDEN_LAYERS, before, sig
        )?.let { s.recordAudit(listOf(it)) }
        if (!s.isCurrentProjectShared) return@LaunchedEffect
        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey),
            manifestTransform(referencePlan?.transform),
            hiddenLayers.toList(), options.showSatellite, refPlanSha)
    }

    // Point de passage UNIQUE du placement du plan (glissé débouncé par PlanScreen,
    // ou annulation depuis l'historique) : journalise puis pousse, une seule fois
    // par valeur — l'anti-écho `lastTransformSig` évite de rejouer le distant.
    fun commitTransform(t: ReferencePlanTransform) {
        val s = sync ?: return
        // R2 — plan MATRICIEL courant : son placement est purement LOCAL. Ni
        // journal partagé ni manifeste : le plan de l'équipe ne doit pas bouger
        // parce qu'un membre a glissé son image de repérage. (La persistance
        // locale, elle, est faite par la vue plan, indépendamment d'ici.)
        if (isRasterPlan()) return
        val sig = transformSig(t)
        if (sig == lastTransformSig) return
        val before = lastTransformSig
        lastTransformSig = sig
        // Pas de valeur de départ connue (plan jamais placé) : rien à annuler,
        // on n'écrit donc pas d'entrée plutôt qu'une entrée trompeuse.
        if (AuditCoding.decodeTransform(before) != null) {
            auditEntry("plan", "Plan de repère", "Placement",
                AuditCoding.describeTransform(before), AuditCoding.describeTransform(sig),
                "", AuditFieldKey.REF_PLAN_TRANSFORM, before, sig
            )?.let { s.recordAudit(listOf(it)) }
        }
        if (!s.isCurrentProjectShared) return
        sharedTransform = t.copy()
        scope.launch {
            s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey), t,
                hiddenLayers.toList(), options.showSatellite, refPlanSha)
        }
    }

    // Annulation d'une entrée d'historique : on réapplique la valeur d'origine
    // par le CHEMIN NORMAL (même code que l'édition utilisateur) — il persiste,
    // pousse au cloud et journalise l'annulation comme une entrée de plus. Le
    // journal reste ainsi strictement en ajout seul.
    fun undoAudit(e: AuditEntry) {
        // Consulter l'historique est gratuit ; ANNULER une modification en est une.
        if (!ProAccess.gate("annuler une modification") {}) return
        val raw = e.oldRaw ?: return
        when (e.fieldKey) {
            AuditFieldKey.FIXTURE_ID, AuditFieldKey.ADDRESS, AuditFieldKey.MODE, AuditFieldKey.NAME -> {
                val key = e.objectKey ?: return
                val f = scene.fixtures.firstOrNull { overrides.key(it) == key } ?: return
                overrides.set(
                    f,
                    if (e.fieldKey == AuditFieldKey.FIXTURE_ID) raw else overrides.effectiveId(f),
                    if (e.fieldKey == AuditFieldKey.ADDRESS) raw else overrides.effectiveAddress(f),
                    if (e.fieldKey == AuditFieldKey.MODE) raw.ifBlank { null } else overrides.effectiveMode(f),
                    if (e.fieldKey == AuditFieldKey.NAME) raw else overrides.effectiveName(f)
                )
            }
            // Type de rendu custom : réassigne la spec d'origine (vide = Standard).
            // Passe par assignSpec → onCommit (persiste + pousse + re-journalise).
            AuditFieldKey.SPEC -> {
                val key = e.objectKey ?: return
                val f = scene.fixtures.firstOrNull { overrides.key(it) == key } ?: return
                overrides.assignSpec(f, raw.ifBlank { null })
            }
            AuditFieldKey.GEO_ANCHORS -> {
                val anchors = AuditCoding.decodeAnchors(raw)
                calibration.reset(); anchors.forEach { calibration.addAnchor(it) }
                scope.launch(Dispatchers.IO) {
                    ProjectStore.saveCalibration(ctx, projectKey, calibration.anchors.toList())
                }
                calibTick++ // l'effet de calibration journalise et pousse
            }
            // L'effet sur hiddenLayers fait le reste (persistance, journal, push).
            AuditFieldKey.REF_PLAN_HIDDEN_LAYERS -> hiddenLayers = AuditCoding.decodeLayers(raw)
            AuditFieldKey.REF_PLAN_TRANSFORM -> {
                val t = AuditCoding.decodeTransform(raw) ?: return
                val rp = referencePlan ?: return
                // Le journal transporte l'ÉCHELLE PURE : l'homothétie, réglage
                // LOCAL, est reportée telle quelle (annuler un déplacement ne doit
                // pas redimensionner le plan).
                t.homothety = rp.transform.homothety
                referencePlan = ReferencePlan(rp.plan, t, rp.raster)
                refPlanTransformVersion++
                scope.launch(Dispatchers.IO) { ProjectStore.saveTransform(ctx, projectKey, t) }
                commitTransform(t)
            }
        }
    }

    // Dialogues de synchro.
    var showAccount by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }

    // N8 — Gestion du bouton/geste RETOUR système : reculer DANS l'appli au lieu
    // de la réduire. Les BackHandler sont évalués du DERNIER composé au premier
    // (le dernier actif gagne), donc on les pose du cas le moins prioritaire au
    // plus prioritaire :
    //   1. Vue 3D (racine de la scène) → fermer le projet, retour à l'Accueil.
    //   2. Sous-vue (plan/patch/GDTF/univers/câblage) → revenir à la vue 3D.
    //   3. Un dialogue de synchro ouvert → le fermer d'abord (priorité maximale).
    // L'Accueil (HomeScreen) n'a AUCUN handler : seul lui laisse l'OS réduire l'appli.
    BackHandler(enabled = mode == SceneMode.THREE_D) { onClose() }
    // Retour matériel depuis une sous-vue → 3D, et sortie du mode affectation (E2).
    // Pendant une AFFECTATION sur le plan, le retour vaut « Terminé » : retour à
    // l'écran Câblage sur la dernière voie (SPEC-cablage-plan §2.3).
    BackHandler(enabled = mode != SceneMode.THREE_D) {
        if (mode == SceneMode.PLAN && assignTarget != null) finishAssign()
        else { assignTarget = null; mode = SceneMode.THREE_D }
    }
    BackHandler(enabled = showAccount || showShare || showHistory || showJoin) {
        showAccount = false; showShare = false; showHistory = false; showJoin = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
    when (mode) {
        SceneMode.THREE_D -> Scene3DScreen(
            scene = visibleScene,
            mvrBytes = mvrBytes,
            onShowClasses = if (classRows.isEmpty()) null else ({ showClasses = true }),
            initialModelUri = initialModelUri,
            onInitialModelConsumed = onInitialModelConsumed,
            options = options,
            gdtfOverrides = gdtfOverrides,
            // Overrides de patch : sert au NOM effectif des étiquettes 3D, de la
            // recherche et de la fiche de sélection (renommage).
            overrides = overrides,
            // Projecteurs custom V1 : géométrie 3D résolue via baseGdtfSpec.
            customResolver = customResolver,
            referencePlan = referencePlan,
            // R7 : la transformée du plan est mutée EN PLACE ; cette version dit à
            // la 3D quand refaire le quad matriciel (homothétie/décalage immédiats).
            refPlanTransformVersion = refPlanTransformVersion,
            // Décor 3D importé (chantier #3) : LOCAL SEUL — ni section de synchro,
            // ni entrée d'audit (même décision que le plan de repère image).
            importedModels = importedModels,
            importedModelsVersion = importedModelsVersion,
            onSetImportedModels = { list ->
                importedModels = list
                importedModelsVersion++
                // Écriture des OCTETS + du manifeste : hors thread principal.
                scope.launch(Dispatchers.IO) { ProjectStore.saveImportedModels(ctx, projectKey, list) }
            },
            onImportedModelsTouched = {
                importedModelsVersion++
                // Réglage fait juste avant de quitter l'écran → fil du processus
                // (le JSON est figé ici même, sur le thread appelant).
                ProjectStore.saveImportedModelsAsync(ctx, projectKey, importedModels)
            },
            hiddenLayers = hiddenLayers,
            calibration = calibration,
            satellite = satellite,
            satelliteStatus = satelliteStatus,
            satelliteCovered = satelliteCovered,
            // Câblage : uniquement pour le texte des étiquettes Socapex / Ligne DMX
            // en 3D (mêmes tables que la vue plan).
            cabling = cabling,
            dmxCabling = dmxCabling,
            onShowPlan = { mode = SceneMode.PLAN },
            onShowPatch = { mode = SceneMode.PATCH },
            onShowGdtfShare = { mode = SceneMode.GDTF_SHARE },
            onShowUniverse = { mode = SceneMode.UNIVERSE },
            onShowCabling = { mode = SceneMode.CABLING },
            onShowAccount = sync?.let { { showAccount = true } },
            onShareProject = sync?.let { { ProAccess.gate("partager un projet") { showShare = true } } },
            onShowHistory = sync?.let { { showHistory = true } },
            onJoinProject = sync?.let { { showJoin = true } },
            // Solo partagé avec la vue plan (survit aux bascules 3D ↔ plan).
            soloElements = soloElements,
            onSetSoloElements = { soloElements = it },
            // N12 : appui long sur un projecteur → ouvre la fiche d'édition patch.
            onEditFixture = { editFixture = it },
            // N11 — disposition des barres d'outils 3D (persistée globalement).
            toolbarLayout = layout3D,
            onLayoutChange = { layout3D = it },
            // LOD d'interaction par calque (persisté localement avec le projet).
            layerLod = layerLod,
            onSetLayerLod = { layerLod = it },
            // Étapes nommées + pourcentage global : la construction 3D poursuit la
            // progression commencée à l'accueil (#4).
            progress = progress,
            // Bibliothèque MA : objets 3D statiques des types MA + sélection remontée.
            fixture3DStore = fixture3DStore,
            onSelectionChanged = { selectionKeys = it },
            onShowMaLibrary = { mode = SceneMode.MA_LIBRARY },
            onClose = onClose,
            modifier = modifier
        )
        SceneMode.PLAN -> PlanScreen(
            scene = visibleScene,
            mvrBytes = mvrBytes,
            onShowClasses = if (classRows.isEmpty()) null else ({ showClasses = true }),
            options = options,
            hiddenElements = hiddenElements,
            onSetHiddenElements = { hiddenElements = it },
            soloElements = soloElements,
            onSetSoloElements = { soloElements = it },
            referencePlan = referencePlan,
            onSetReferencePlan = { rp ->
                referencePlan = rp
                refPlanTransformVersion++
                // À l'import : masquer d'emblée les calques éteints/gelés du DXF.
                if (rp != null && hiddenLayers.isEmpty() && rp.plan.defaultHiddenLayers.isNotEmpty()) {
                    hiddenLayers = rp.plan.defaultHiddenLayers.toSet()
                }
                // Import / retrait par l'utilisateur → persiste la géométrie DXF.
                scope.launch(Dispatchers.IO) {
                    if (rp != null) ProjectStore.saveReferencePlan(ctx, projectKey, rp, null)
                    else ProjectStore.removeReferencePlan(ctx, projectKey)
                }
                // SYNCHRO : téléverse le plan (format d'échange DXP1) + pousse le manifeste.
                sync?.let { s ->
                    scope.launch {
                        // Même garde qu'en pushAllLocalState : un plan IMAGE (DxfPlan
                        // vide) ne doit ni téléverser un blob vide, ni effacer le SHA
                        // du DXF vectoriel déjà partagé. Retrait explicite (rp == null)
                        // = seul cas où l'on remet le SHA à null.
                        refPlanSha = when {
                            rp == null -> null
                            !rp.plan.isEmpty -> s.uploadRefPlan(RefPlanInterop.encode(rp.plan))
                            else -> refPlanSha
                        }
                        // R2 — l'import d'une IMAGE ne doit jamais envoyer un
                        // placement recalculé : il ferait « sauter » le DXF chez
                        // les coéquipiers. On garde donc l'anti-écho ET le champ
                        // du manifeste sur le dernier placement PARTAGÉ.
                        if (rp?.raster == null) rp?.transform?.let { lastTransformSig = transformSig(it) }
                        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey),
                            manifestTransform(rp?.transform),
                            hiddenLayers.toList(), options.showSatellite, refPlanSha)
                    }
                }
            },
            calibration = calibration,
            projectKey = projectKey,
            satellite = satellite,
            satelliteStatus = satelliteStatus,
            satelliteCovered = satelliteCovered,
            hiddenLayers = hiddenLayers,
            onToggleLayer = { layer ->
                // Calque DXF masqué = donnée PARTAGÉE (manifeste + audit,
                // appliquée chez tous) : édition, donc Pro. Un gratuit continue
                // de RECEVOIR et voir les calques masqués par l'équipe.
                ProAccess.gate("modifier les calques du plan importé") {
                    hiddenLayers = if (layer in hiddenLayers) hiddenLayers - layer else hiddenLayers + layer
                }
            },
            onCalibrationChanged = { calibTick++ },
            // Push + journal du placement, sauf s'il vient d'être APPLIQUÉ à
            // distance. DÉBOUNCÉ (persistance) — ce n'est donc PAS lui qui
            // rafraîchit l'affichage.
            onTransformChanged = { t -> commitTransform(t) },
            // R7 — rafraîchissement IMMÉDIAT : la version est incrémentée dès la
            // retouche, sans délai ni condition de projet. C'est elle qui fait
            // reconstruire le plan de repère en 3D (quad matriciel ET traits DXF),
            // la transformée étant un objet muté EN PLACE que Compose n'observe pas.
            onTransformTouched = { refPlanTransformVersion++ },
            gdtfOverrides = gdtfOverrides,
            // Overrides de patch : NOM effectif des étiquettes 2D, de la recherche
            // plan et de la fiche de sélection (renommage).
            overrides = overrides,
            // Câblage (phase 4) : coloration du plan par distributeur + champs
            // d'étiquette Socapex / Ligne DMX. Objets déjà hissés ici (L87/L90).
            cabling = cabling,
            dmxCabling = dmxCabling,
            // AFFECTATION SUR LE PLAN (SPEC-cablage-plan) : cible hissée, « Terminé »
            // (revient au câblage sur la dernière voie), ◀ ▶, croix (reste sur le
            // plan), charge de la barre avec les mêmes sources que l'écran Câblage.
            assignTarget = assignTarget,
            onAssignDone = { finishAssign() },
            onStepAssign = { stepAssign(it) },
            onExitAssign = { assignTarget = null },
            power = power,
            customResolver = customResolver,
            onShow3D = { assignTarget = null; mode = SceneMode.THREE_D },
            onShowAccount = sync?.let { { showAccount = true } },
            onShareProject = sync?.let { { ProAccess.gate("partager un projet") { showShare = true } } },
            onShowHistory = sync?.let { { showHistory = true } },
            onJoinProject = sync?.let { { showJoin = true } },
            // Flèche haut-gauche : pendant une affectation, elle vaut « Terminé »
            // (retour à l'écran Câblage) ; sinon retour à la 3D.
            onBack = { if (assignTarget != null) finishAssign() else mode = SceneMode.THREE_D },
            onShowPatch = { mode = SceneMode.PATCH },
            // N12 : appui long sur un projecteur → ouvre la fiche d'édition patch.
            onEditFixture = { editFixture = it },
            // N11 — disposition des barres d'outils plan (persistée globalement).
            toolbarLayout = layoutPlan,
            onLayoutChange = { layoutPlan = it },
            // Bibliothèque MA : entrée de menu + sélection remontée (affectation).
            onShowMaLibrary = { mode = SceneMode.MA_LIBRARY },
            onSelectionChanged = { selectionKeys = it },
            modifier = modifier
        )
        SceneMode.PATCH -> PatchListScreen(
            // La liste de patch REFLÈTE le masquage par classes, comme iOS.
            // Univers/Câblage/GDTF Share restent volontairement sur la scène
            // COMPLÈTE : ils référencent des instances (affectations soca,
            // lignes DMX) — masquer une classe ne doit pas faire disparaître
            // des affectations existantes.
            scene = visibleScene,
            mvrBytes = mvrBytes,
            overrides = overrides,
            power = power,
            gdtfOverrides = gdtfOverrides,
            customLibrary = customLibrary,
            customResolver = customResolver,
            onBack = { mode = SceneMode.THREE_D },
            modifier = modifier
        )
        SceneMode.MA_LIBRARY -> MaLibraryScreen(
            state = maLibrary,
            scene = scene,
            mvrBytes = mvrBytes,
            gdtfOverrides = gdtfOverrides,
            customLibrary = customLibrary,
            customResolver = customResolver,
            fixture3DStore = fixture3DStore,
            sharedProjectOpen = sync?.isCurrentProjectShared == true && curProject?.value != null,
            selectionCount = run {
                val keys = selectionKeys.toSet()
                scene.fixtures.count { overrides.key(it) in keys }
            },
            onSaveType = { t, assign -> saveMaType(t, assign) },
            onCustomLibraryChanged = { scope.launch { syncCustomFixtures() } },
            onBack = { mode = SceneMode.THREE_D },
            modifier = modifier,
            inbox = maInbox
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
            // Overrides de patch : NOM effectif des projecteurs dans la vue Univers.
            patchOverrides = overrides,
            // Projecteurs custom V1 : empreinte + noms de canaux du type.
            customResolver = customResolver,
            onBack = { mode = SceneMode.THREE_D },
            modifier = modifier
        )
        SceneMode.CABLING -> CablingScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            overrides = overrides,
            gdtfOverrides = gdtfOverrides,
            power = power,
            cabling = cabling,
            dmxCabling = dmxCabling,
            // Projecteurs custom V1 : empreinte DMX + puissance résolues via le type.
            customResolver = customResolver,
            // Pages « Plans repérés » de l'export PDF : mêmes données que la vue plan
            // (plan de repère DXF, satellite géo-référencé, calques masqués), hissées
            // ici pour survivre aux bascules 3D ↔ plan.
            referencePlan = referencePlan,
            satellite = satellite,
            hiddenLayers = hiddenLayers,
            // AFFECTATION SUR LE PLAN (E2) : un « Sélectionner sur le plan » sur un
            // circuit / départ bascule en vue plan MODE AFFECTATION vers cette cible.
            onSelectOnPlan = { t -> setAssignVoie(t.voie); mode = SceneMode.PLAN },
            memory = cablingMemory,
            onBack = { mode = SceneMode.THREE_D },
            modifier = modifier
        )
    } // when(mode)

        // Panneau des CLASSES — rendu ICI, hors du `when(mode)` : le filtre vaut
        // pour la 3D ET pour le plan, il doit donc rester ouvert quel que soit le
        // mode (et survivre à une bascule entre les deux).
        if (showClasses) {
            ClassesSheet(
                classes = classRows,
                hidden = hiddenClasses,
                onToggle = { name ->
                    hiddenClasses =
                        if (name in hiddenClasses) hiddenClasses - name else hiddenClasses + name
                },
                onSetAll = { hideAll ->
                    hiddenClasses = if (hideAll) classRows.map { it.first }.toSet() else emptySet()
                },
                onDismiss = { showClasses = false }
            )
        }

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
            // Pastille de statut seule (les actions compte/partage/historique/
            // rejoindre vivent dans le menu ⋯, pas dans un bouton flottant).
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                SyncStatusBadge(sync)
            }

            if (showAccount) AccountDialog(sync) { showAccount = false }
            if (showShare) ShareProjectDialog(sync, fileName, onDismiss = { showShare = false },
                onPublished = { scope.launch { pushAllLocalState() } })
            if (showHistory) HistoryDialog(sync, onUndo = { e -> undoAudit(e) }) { showHistory = false }
            if (showJoin) JoinProjectDialog(sync, onDismiss = { showJoin = false }) { project ->
                scope.launch {
                    runCatching { sync.downloadMvr(project) }.getOrNull()?.let {
                        onReopenBytes(it, project.name)
                    }
                }
            }
        }

        // N12 — FICHE D'ÉDITION PATCH ouverte par appui long (3D ou plan). La MÊME
        // FixtureDetailSheet que la liste de patch (édite Fixture ID / adresse DMX /
        // mode / conso). Rendue au niveau de la scène → s'affiche par-dessus quelle
        // que soit la vue active, et les édits passent par `overrides` (audit +
        // persistance + push cloud via overrides.onCommit, comme depuis la liste).
        editFixture?.let { f ->
            FixtureDetailSheet(
                fixture = f, mvrBytes = mvrBytes, overrides = overrides, power = power,
                scene = scene, gdtfOverrides = gdtfOverrides,
                customLibrary = customLibrary, customResolver = customResolver,
                onCustomLibraryChanged = { scope.launch { syncCustomFixtures() } },
                onDismiss = { editFixture = null }
            )
        }
    } // Box
}
