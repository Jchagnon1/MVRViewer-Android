package com.minou.mvrviewer.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.ReferencePlanTransform
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Cerveau de la synchronisation côté Android — PORTAGE de SyncCoordinator iOS,
 * en StateFlow/coroutines. Il :
 *  - détient le [SyncService] choisi par [BackendSelector] ;
 *  - publie l'état auth / projet courant / statut / version en attente / audit ;
 *  - oriente le PUSH (les écrans appellent pushPatch/pushManifest/… après édition) ;
 *  - reçoit les événements distants et les FILTRE (anti-écho + garde-geste) avant
 *    de les ré-émettre sur [events], que l'écran applique champ par champ.
 *
 * No-op complet si déconnecté / projet non partagé → l'app se comporte comme avant.
 */
class SyncViewModel(app: Application) : AndroidViewModel(app) {

    sealed class AuthState {
        data object SignedOut : AuthState()
        data object SigningIn : AuthState()
        data class SignedIn(val account: AccountInfo) : AuthState()

        val accountOrNull: AccountInfo? get() = (this as? SignedIn)?.account
        val isSignedIn: Boolean get() = this is SignedIn
    }

    sealed class SyncStatus {
        data object Idle : SyncStatus()
        data object Syncing : SyncStatus()
        data class Uploading(val progress: Double) : SyncStatus()
        data class Downloading(val progress: Double) : SyncStatus()
        data class Error(val message: String) : SyncStatus()
    }

    data class MvrVersionNotice(
        val projectId: String, val version: Int, val sha256: String, val updatedBy: String
    )

    private val ctx = app.applicationContext
    private val service: SyncService = BackendSelector.makeService(ctx)

    private val _auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()
    private val _currentProject = MutableStateFlow<CloudProject?>(null)
    val currentProject: StateFlow<CloudProject?> = _currentProject.asStateFlow()
    private val _cloudProjects = MutableStateFlow<List<CloudProject>>(emptyList())
    val cloudProjects: StateFlow<List<CloudProject>> = _cloudProjects.asStateFlow()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _pendingMvrVersion = MutableStateFlow<MvrVersionNotice?>(null)
    val pendingMvrVersion: StateFlow<MvrVersionNotice?> = _pendingMvrVersion.asStateFlow()
    private val _auditLog = MutableStateFlow<List<AuditEntry>>(emptyList())
    val auditLog: StateFlow<List<AuditEntry>> = _auditLog.asStateFlow()

    /** Événements distants FILTRÉS à appliquer par l'écran (patch/manifeste/calibration). */
    private val _events = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RemoteEvent> = _events.asSharedFlow()

    val backendLabel: String get() = BackendSelector.activeBackendLabel(ctx)
    val isCurrentProjectShared: Boolean get() = _currentProject.value != null

    private var currentContentKey: String? = null
    private var currentMvrBytes: ByteArray? = null

    // Anti-écho : opId qu'on vient d'émettre (TTL 60 s) → on ignore leur retour.
    private val ourRecentOpIds = HashMap<String, Long>()
    // Garde-geste : pendant un drag, on met en file les événements de transform.
    private var interacting = false
    private val deferredEvents = ArrayList<RemoteEvent>()

    private var observeJob: Job? = null

    // MARK: - Auth

    fun restoreSessionIfPossible() {
        viewModelScope.launch {
            val acc = runCatching { service.restoreSession() }.getOrNull() ?: return@launch
            _auth.value = AuthState.SignedIn(acc)
            refreshProjects()
        }
    }

    suspend fun signIn(email: String, password: String) {
        _auth.value = AuthState.SigningIn
        try {
            val acc = service.signIn(email, password)
            _auth.value = AuthState.SignedIn(acc)
            refreshProjects()
            reattachAfterAuth()
        } catch (e: Exception) {
            _auth.value = AuthState.SignedOut
            throw e
        }
    }

    suspend fun signUp(email: String, password: String, displayName: String) {
        _auth.value = AuthState.SigningIn
        try {
            val acc = service.signUp(email, password, displayName)
            _auth.value = AuthState.SignedIn(acc)
            refreshProjects()
            reattachAfterAuth()
        } catch (e: Exception) {
            _auth.value = AuthState.SignedOut
            throw e
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { service.signOut() }
            stopObserving()
            _auth.value = AuthState.SignedOut
            _currentProject.value = null
            _cloudProjects.value = emptyList()
        }
    }

    private suspend fun refreshProjects() {
        if (!_auth.value.isSignedIn) return
        _cloudProjects.value = runCatching { service.listProjects() }.getOrDefault(emptyList())
    }

    // MARK: - Document ouvert / réunion

    /** À l'ouverture d'un .mvr : mémorise le contexte + rattache le projet cloud. */
    fun attach(contentKey: String, mvrBytes: ByteArray) {
        viewModelScope.launch {
            currentContentKey = contentKey
            currentMvrBytes = mvrBytes
            stopObserving()
            _currentProject.value = null
            loadAndMergeAudit() // journal local dispo même sans compte
            if (_auth.value.isSignedIn) {
                runCatching { service.projectMatchingContentKey(contentKey) }.getOrNull()?.let {
                    setCurrentProject(it)
                    loadAndMergeAudit()
                }
            }
            _status.value = SyncStatus.Idle
        }
    }

    private suspend fun reattachAfterAuth() {
        val key = currentContentKey ?: return
        runCatching { service.projectMatchingContentKey(key) }.getOrNull()?.let { setCurrentProject(it) }
    }

    fun detach() {
        stopObserving()
        _currentProject.value = null
        currentContentKey = null
        currentMvrBytes = null
    }

    // MARK: - Publier / rejoindre

    suspend fun publishCurrentProject(name: String): InviteCode {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        val key = currentContentKey; val data = currentMvrBytes
        if (key == null || data == null) throw SyncException.Message(ctx.getString(com.minou.mvrviewer.R.string.sync_err_no_open_project))
        _status.value = SyncStatus.Syncing
        try {
            val project = runCatching { service.projectMatchingContentKey(key) }.getOrNull()
                ?: service.createProject(key, name)
            setCurrentProject(project)
            val sha = ContentHash.sha256Hex(data)
            if (!service.blobExists(project.id, sha)) {
                _status.value = SyncStatus.Uploading(0.0)
                service.uploadBlob(project.id, sha, BlobKind.MVR, data) { p ->
                    _status.value = SyncStatus.Uploading(p)
                }
            }
            service.publishMVRVersion(project.id, sha)
            val invite = service.createInvite(project.id)
            refreshProjects()
            return invite
        } finally {
            _status.value = SyncStatus.Idle
        }
    }

    suspend fun join(code: String): CloudProject {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        val p = service.joinProject(code)
        refreshProjects()
        return p
    }

    suspend fun downloadMvr(project: CloudProject): ByteArray {
        val sha = project.mvrBlobSHA ?: throw SyncException.BlobMissing
        return downloadMvr(sha, project.id)
    }

    suspend fun downloadMvr(sha256: String, projectId: String? = null): ByteArray {
        val pid = projectId ?: _currentProject.value?.id ?: throw SyncException.ProjectNotFound
        return service.downloadBlob(pid, BlobRef(sha256, BlobKind.MVR, 0, "")) { p ->
            _status.value = SyncStatus.Downloading(p)
        }.also { _status.value = SyncStatus.Idle }
    }

    /** Téléverse le plan DXF (`refplan.bin`, format d'échange DXP1) ; renvoie le SHA. */
    suspend fun uploadRefPlan(interopBytes: ByteArray): String? {
        val project = _currentProject.value ?: return null
        if (!_auth.value.isSignedIn) return null
        val sha = ContentHash.sha256Hex(interopBytes)
        return runCatching {
            if (!service.blobExists(project.id, sha)) {
                service.uploadBlob(project.id, sha, BlobKind.REF_PLAN, interopBytes, null)
            }
            sha
        }.getOrNull()
    }

    suspend fun downloadRefPlan(sha256: String): ByteArray? {
        val project = _currentProject.value ?: return null
        return runCatching {
            service.downloadBlob(project.id, BlobRef(sha256, BlobKind.REF_PLAN, 0, ""), null)
        }.getOrNull()
    }

    suspend fun fetchSnapshot(): ProjectSnapshot? {
        val project = _currentProject.value ?: return null
        return runCatching { service.fetchSnapshot(project.id) }.getOrNull()
    }

    fun consumePendingVersion() { _pendingMvrVersion.value = null }

    // MARK: - Push (appelé par les écrans après édition locale)

    private fun push(payload: SectionPayload) {
        if (!_auth.value.isSignedIn) return
        val project = _currentProject.value ?: return
        val opId = UUID.randomUUID().toString()
        ourRecentOpIds[opId] = System.currentTimeMillis()
        pruneOpIds()
        viewModelScope.launch { runCatching { service.pushSection(project.id, payload, opId) } }
    }

    fun pushPatch(scene: MvrScene, edits: List<PersistedPatchEdit>) =
        push(SectionPayload.Patch(LocalMapper.patchDTO(scene, edits)))

    fun pushManifest(
        mvrName: String, dxfName: String?, transform: ReferencePlanTransform?,
        hiddenLayers: List<String>, showSatellite: Boolean?, refPlanBlobSHA: String?
    ) = push(SectionPayload.Manifest(
        LocalMapper.manifestDTO(mvrName, dxfName, transform, hiddenLayers, showSatellite, refPlanBlobSHA)))

    fun pushCalibration(anchors: List<GeoAnchor>) =
        push(SectionPayload.Calibration(LocalMapper.calibrationDTO(anchors)))

    /** Câblage électrique (distributeurs + affectations + réglages). */
    fun pushPowerCabling(dto: PowerCablingDTO) =
        push(SectionPayload.PowerCabling(dto))

    /** Câblage DMX (lignes simples/multipaires + affectations). */
    fun pushDmxCabling(dto: DmxCablingDTO) =
        push(SectionPayload.DmxCabling(dto))

    /** Projecteurs custom (liste des DÉFINITIONS de types utilisés dans le projet). */
    fun pushCustomFixtures(dto: CustomFixturesDTO) =
        push(SectionPayload.CustomFixtures(dto))

    // MARK: - Bibliothèque de puissances (base communautaire GLOBALE)

    /**
     * Lit TOUS les votes cloud pour une spec (vide si aucun / non connecté / hors
     * ligne). Ne LÈVE jamais : la résolution locale prend le relais. Migre au
     * passage un ancien doc mono-valeur en un vote unique (côté service).
     */
    suspend fun fetchPowerVotes(spec: String): List<PowerVote> =
        runCatching { service.fetchPowerVotes(spec) }.getOrDefault(emptyList())

    /**
     * Dépose (ou actualise) MON vote dans la biblio partagée puis renvoie le
     * CONSENSUS fraîchement recalculé sur l'ENSEMBLE des votes (mon vote inclus).
     * Renvoie `null` si non connecté / hors ligne — l'appelant retombe alors sur
     * un consensus local de son seul vote. Personne n'écrase le vote d'un autre.
     */
    suspend fun submitPowerVote(spec: String, watts: Int): PowerConsensus? {
        if (spec.isBlank()) return null
        val by = _auth.value.accountOrNull?.uid ?: ""
        return runCatching {
            service.putPowerVote(spec, watts, by)
            powerConsensus(service.fetchPowerVotes(spec).map { it.watts })
        }.getOrNull()
    }

    // MARK: - Journal de modifications (audit)

    /** Auteur courant (compte connecté, sinon local hors-ligne). */
    val currentAuthor: Pair<String, String>
        get() = _auth.value.accountOrNull?.let { it.uid to it.displayName } ?: ("" to ctx.getString(com.minou.mvrviewer.R.string.sync_me_offline))

    fun newAuditId(): String = UUID.randomUUID().toString()
    fun nowEpoch(): Double = System.currentTimeMillis() / 1000.0

    /** Enregistre des entrées : local (persistant) + cloud si connecté. */
    fun recordAudit(entries: List<AuditEntry>) {
        if (entries.isEmpty()) return
        val key = currentContentKey
        _auditLog.value =
            if (key != null) AuditStore.append(ctx, key, entries).sortedByDescending { it.epoch }
            else (_auditLog.value + entries).sortedByDescending { it.epoch }
        if (_auth.value.isSignedIn) {
            val project = _currentProject.value
            if (project != null) viewModelScope.launch {
                runCatching { service.appendAudit(project.id, entries) }
            }
        }
    }

    private suspend fun loadAndMergeAudit() {
        val key = currentContentKey
        if (key != null) _auditLog.value = AuditStore.load(ctx, key).sortedByDescending { it.epoch }
        if (!_auth.value.isSignedIn) return
        val project = _currentProject.value ?: return
        val remote = runCatching { service.fetchAudit(project.id) }.getOrNull() ?: return
        _auditLog.value =
            if (key != null) AuditStore.append(ctx, key, remote).sortedByDescending { it.epoch }
            else mergeAuditInMemory(remote)
    }

    private fun mergeAuditInMemory(incoming: List<AuditEntry>): List<AuditEntry> {
        val ids = _auditLog.value.map { it.id }.toHashSet()
        return (_auditLog.value + incoming.filter { it.id !in ids }).sortedByDescending { it.epoch }
    }

    // MARK: - Garde-geste

    fun beginInteraction() { interacting = true }
    fun endInteraction() {
        interacting = false
        val buffered = deferredEvents.toList()
        deferredEvents.clear()
        for (e in buffered) _events.tryEmit(e)
    }

    // MARK: - Observation

    private fun setCurrentProject(p: CloudProject) {
        _currentProject.value = p
        startObserving(p.id)
    }

    private fun startObserving(projectId: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            service.observe(projectId).collect { handleInbound(it) }
        }
    }

    private fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

    private fun handleInbound(event: RemoteEvent) {
        when (event) {
            is RemoteEvent.Section -> {
                if (ourRecentOpIds.containsKey(event.change.opId)) return // notre écho
                if (interacting && event.change.kind == ProjectSectionKind.MANIFEST) {
                    deferredEvents.add(event) // ne pas bousculer le geste
                    return
                }
                _events.tryEmit(event)
            }
            is RemoteEvent.MvrVersion -> {
                val cp = _currentProject.value
                if (cp != null && event.version > cp.mvrVersion) {
                    _pendingMvrVersion.value =
                        MvrVersionNotice(cp.id, event.version, event.sha256, event.updatedBy)
                }
            }
            is RemoteEvent.MembershipChanged -> viewModelScope.launch { refreshProjects() }
            is RemoteEvent.Audit -> {
                val key = currentContentKey
                _auditLog.value =
                    if (key != null) AuditStore.append(ctx, key, event.entries).sortedByDescending { it.epoch }
                    else mergeAuditInMemory(event.entries)
            }
        }
    }

    private fun pruneOpIds() {
        val cutoff = System.currentTimeMillis() - 60_000
        ourRecentOpIds.entries.removeAll { it.value < cutoff }
    }

    // MARK: - Test / démo

    /** Connexion (ou inscription si le compte n'existe pas) — flux de test cross-platform. */
    suspend fun signInOrSignUp(email: String, password: String) {
        try { signIn(email, password) }
        catch (e: Exception) { signUp(email, password, email) }
    }

    override fun onCleared() {
        super.onCleared()
        stopObserving()
    }
}
