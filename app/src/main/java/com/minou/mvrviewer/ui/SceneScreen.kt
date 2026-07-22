package com.minou.mvrviewer.ui

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SceneMode { THREE_D, PLAN, PATCH, GDTF_SHARE, UNIVERSE, CABLING }

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
    // Ensemble SOLO (identité d'INSTANCE, comme hiddenElements) : quand il est
    // non vide, la vue plan n'affiche QUE ces éléments — c'est l'inverse exact du
    // masquage. Hissé ici pour la MÊME raison que hiddenElements : PlanScreen est
    // détruit à chaque aller-retour 3D ↔ plan, un état local se perdrait dès la
    // première visite en 3D. En revanche il n'est NI persisté NI synchronisé : le
    // solo est un filtre d'affichage PERSONNEL (une « vue »), pas une modification
    // du show — contrairement au masquage qui, lui, est une décision partagée.
    var soloElements by remember(projectKey) { mutableStateOf<Set<String>>(emptySet()) }
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
    val authState = sync?.auth?.collectAsState()
    val curProject = sync?.currentProject?.collectAsState()

    fun transformSig(t: ReferencePlanTransform) = AuditCoding.encodeTransform(t)
    fun calibSig() = AuditCoding.encodeAnchors(calibration.anchors)

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
                            ctx, projectKey, gdtfOverrides.map.toMap(), gdtfOverrides.manualSpecs.toSet()
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
            else -> {} // layerColors/labelSides/orientations : reçus, non appliqués en v1
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
        if (s == null) { overrides.onCommit = null; return@LaunchedEffect }
        overrides.onCommit = { fixture, old, new ->
            scope.launch(Dispatchers.IO) { PatchStore.save(ctx, projectKey, overrides.toPersistedList()) }
            s.pushPatch(scene, overrides.toPersistedList())
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
                ) else null
            )
            s.recordAudit(audits)
        }
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
        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey), referencePlan?.transform,
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
        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey), referencePlan?.transform,
            hiddenLayers.toList(), options.showSatellite, refPlanSha)
    }

    // Point de passage UNIQUE du placement du plan (glissé débouncé par PlanScreen,
    // ou annulation depuis l'historique) : journalise puis pousse, une seule fois
    // par valeur — l'anti-écho `lastTransformSig` évite de rejouer le distant.
    fun commitTransform(t: ReferencePlanTransform) {
        val s = sync ?: return
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
        val raw = e.oldRaw ?: return
        when (e.fieldKey) {
            AuditFieldKey.FIXTURE_ID, AuditFieldKey.ADDRESS, AuditFieldKey.MODE -> {
                val key = e.objectKey ?: return
                val f = scene.fixtures.firstOrNull { overrides.key(it) == key } ?: return
                overrides.set(
                    f,
                    if (e.fieldKey == AuditFieldKey.FIXTURE_ID) raw else overrides.effectiveId(f),
                    if (e.fieldKey == AuditFieldKey.ADDRESS) raw else overrides.effectiveAddress(f),
                    if (e.fieldKey == AuditFieldKey.MODE) raw.ifBlank { null } else overrides.effectiveMode(f)
                )
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
                referencePlan = ReferencePlan(rp.plan, t)
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

    Box(modifier = Modifier.fillMaxSize()) {
    when (mode) {
        SceneMode.THREE_D -> Scene3DScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            options = options,
            gdtfOverrides = gdtfOverrides,
            referencePlan = referencePlan,
            hiddenLayers = hiddenLayers,
            calibration = calibration,
            satellite = satellite,
            onShowPlan = { mode = SceneMode.PLAN },
            onShowPatch = { mode = SceneMode.PATCH },
            onShowGdtfShare = { mode = SceneMode.GDTF_SHARE },
            onShowUniverse = { mode = SceneMode.UNIVERSE },
            onShowCabling = { mode = SceneMode.CABLING },
            onShowAccount = sync?.let { { showAccount = true } },
            onShareProject = sync?.let { { showShare = true } },
            onShowHistory = sync?.let { { showHistory = true } },
            onJoinProject = sync?.let { { showJoin = true } },
            onClose = onClose,
            modifier = modifier
        )
        SceneMode.PLAN -> PlanScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            options = options,
            hiddenElements = hiddenElements,
            onSetHiddenElements = { hiddenElements = it },
            soloElements = soloElements,
            onSetSoloElements = { soloElements = it },
            referencePlan = referencePlan,
            onSetReferencePlan = { rp ->
                referencePlan = rp
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
                        refPlanSha = if (rp != null)
                            s.uploadRefPlan(RefPlanInterop.encode(rp.plan)) else null
                        rp?.transform?.let { lastTransformSig = transformSig(it) }
                        s.pushManifest(fileName, ProjectStore.dxfName(ctx, projectKey),
                            rp?.transform, hiddenLayers.toList(), options.showSatellite, refPlanSha)
                    }
                }
            },
            calibration = calibration,
            projectKey = projectKey,
            satellite = satellite,
            hiddenLayers = hiddenLayers,
            onToggleLayer = { layer ->
                hiddenLayers = if (layer in hiddenLayers) hiddenLayers - layer else hiddenLayers + layer
            },
            onCalibrationChanged = { calibTick++ },
            // Push + journal du placement, sauf s'il vient d'être APPLIQUÉ à distance.
            onTransformChanged = { t -> commitTransform(t) },
            gdtfOverrides = gdtfOverrides,
            onShowAccount = sync?.let { { showAccount = true } },
            onShareProject = sync?.let { { showShare = true } },
            onShowHistory = sync?.let { { showHistory = true } },
            onJoinProject = sync?.let { { showJoin = true } },
            onBack = { mode = SceneMode.THREE_D },
            onShowPatch = { mode = SceneMode.PATCH },
            modifier = modifier
        )
        SceneMode.PATCH -> PatchListScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            overrides = overrides,
            power = power,
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
        SceneMode.CABLING -> CablingScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            overrides = overrides,
            gdtfOverrides = gdtfOverrides,
            power = power,
            cabling = cabling,
            dmxCabling = dmxCabling,
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
    } // Box
}
