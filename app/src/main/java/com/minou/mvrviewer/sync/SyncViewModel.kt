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
import kotlinx.coroutines.async
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
    /** SHA-256 de [currentMvrBytes], calculé au plus une fois par document ([openedMvrSha]). */
    private var currentMvrSha: String? = null
    /** Lien « .mvr téléchargé → projet » ([projectForOpenedDocument]). */
    private val downloadedProjects = DownloadedProjects(PrefsDownloadedProjectsStore(ctx))

    // Anti-écho : opId qu'on vient d'émettre (TTL 60 s) → on ignore leur retour.
    private val ourRecentOpIds = HashMap<String, Long>()
    // Garde-geste : pendant un drag, on met en file les événements de transform.
    private var interacting = false
    private val deferredEvents = ArrayList<RemoteEvent>()

    private var observeJob: Job? = null

    // Licence Pro PAR COMPTE (posée par l'admin, cf. SyncService.fetchProGrant) :
    // relue après CHAQUE connexion réussie et par « Restaurer un achat »
    // (crochet ProAccess.grantRefresher), retirée à la déconnexion.
    private val grantRefresher: () -> Unit = { refreshProGrant() }

    // ACHAT PRO RECONNU PARTOUT (SPEC-purchase.md §6.1/§6.3) : la preuve de
    // l'achat Play part vers la fonction `verifyPurchase`, qui rattache l'achat
    // au compte et écrit `entitlements/{uid}` (lu par fetchProGrant, inchangé)
    // → le Pro acheté ici vaut aussi sur iPhone et sur le web. Déclencheurs :
    // ProAccess (achat, « Restaurer un achat », relecture Play au lancement)
    // via [purchaseLinker], chaque relecture de licence, retour au premier plan
    // après un échec réessayable ([onForeground]).
    private val purchaseLinks = PurchaseLinker(
        service = service,
        transport = HttpPurchaseLinkTransport(),
        store = PrefsPurchaseLinkStore(ctx),
        proof = { com.minou.mvrviewer.ui.ProAccess.currentProof() },
        currentUid = { _auth.value.accountOrNull?.uid },
        applyGrant = { com.minou.mvrviewer.ui.ProAccess.setAccountGrant(it) },
        showRefusal = { com.minou.mvrviewer.ui.ProAccess.setAccountLinkRefusal(it) }
    )
    private val purchaseLinker: (Boolean) -> Unit = { explicit -> linkPurchase(explicit) }

    init {
        com.minou.mvrviewer.ui.ProAccess.grantRefresher = grantRefresher
        com.minou.mvrviewer.ui.ProAccess.purchaseLinker = purchaseLinker
    }

    // MARK: - Auth

    fun restoreSessionIfPossible() {
        viewModelScope.launch {
            val acc = runCatching { service.restoreSession() }.getOrNull() ?: return@launch
            _auth.value = AuthState.SignedIn(acc)
            bindProAccount(acc)
            refreshProGrant()
            refreshProjects()
        }
    }

    /** Compte connecté → ProAccess : uid pour l'`obfuscatedAccountId` du flux
     *  d'achat, crochet d'envoi de la preuve (re)branché. */
    private fun bindProAccount(acc: AccountInfo) {
        com.minou.mvrviewer.ui.ProAccess.accountUid = acc.uid
        com.minou.mvrviewer.ui.ProAccess.purchaseLinker = purchaseLinker
    }

    /**
     * Relit la licence par compte pour la session courante et l'applique à
     * [com.minou.mvrviewer.ui.ProAccess]. No-op si déconnecté. Dans
     * [viewModelScope] (ne retarde pas la connexion). ORDRE (SPEC-purchase
     * §6.1) : envoi automatique de la preuve d'achat si nécessaire →
     * `fetchProGrant` → réparation (licence fausse alors que l'achat est
     * mémorisé rattaché : un envoi forcé par session). Garde de course : si la
     * session a changé pendant l'envoi ou la lecture (déconnexion, autre
     * compte), le résultat n'est pas appliqué. Rien ici ne lève.
     */
    private fun refreshProGrant() {
        if (_auth.value.accountOrNull == null) return
        viewModelScope.launch { purchaseLinks.refreshProGrant() }
    }

    /** Envoie la preuve d'achat (déclencheurs ProAccess) ; `explicit` = geste
     *  de l'utilisateur. Rattaché → licence relue. No-op si déconnecté. */
    fun linkPurchase(explicit: Boolean) {
        viewModelScope.launch { purchaseLinks.linkPurchase(explicit) }
    }

    /** Retour au premier plan (MainActivity.onResume) : renvoi automatique,
     *  SEULEMENT si la dernière tentative a échoué de façon réessayable. */
    fun onForeground() {
        if (purchaseLinks.lastOutcomeIsRetry()) linkPurchase(explicit = false)
    }

    suspend fun signIn(email: String, password: String) {
        _auth.value = AuthState.SigningIn
        try {
            val acc = service.signIn(email, password)
            _auth.value = AuthState.SignedIn(acc)
            bindProAccount(acc)
            refreshProGrant()
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
            bindProAccount(acc)
            refreshProGrant()
            refreshProjects()
            reattachAfterAuth()
        } catch (e: Exception) {
            _auth.value = AuthState.SignedOut
            throw e
        }
    }

    /**
     * MOT DE PASSE OUBLIÉ (guide C6) — GRATUIT, aucune garde Pro, ne touche pas à
     * l'état de connexion. L'adresse est nettoyée et sa forme vérifiée ICI, avant
     * tout appel réseau ([SyncException.InvalidEmail]). Réussir ne dit pas qu'un
     * compte existe (voir [SyncService.sendPasswordReset]).
     */
    suspend fun sendPasswordReset(email: String) {
        val address = email.trim()
        if (!EmailAddress.isPlausible(address)) throw SyncException.InvalidEmail
        service.sendPasswordReset(address)
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { service.signOut() }
            stopObserving()
            _auth.value = AuthState.SignedOut
            _currentProject.value = null
            _cloudProjects.value = emptyList()
            com.minou.mvrviewer.ui.ProAccess.setAccountGrant(false)
            com.minou.mvrviewer.ui.ProAccess.clearAccount()
        }
    }

    /**
     * Suppression DÉFINITIVE du compte (politique Google Play « suppression de
     * compte »). JAMAIS de garde Pro : c'est un droit, gratuit. Le service
     * re-vérifie le mot de passe et n'ÉCRIT qu'après une sonde réseau ; en cas
     * d'échec AVANT le commit (mot de passe faux, réseau, reconnexion exigée…)
     * l'exception remonte à l'UI et le compte reste INTACT — l'observation du
     * projet courant, coupée avant l'appel pour que plus aucun écouteur
     * Firestore ne survive à la suppression, est rétablie.
     * [SyncException.DeletionIncomplete] (données effacées, compte Auth encore
     * vivant, service déjà déconnecté) : on passe à l'état déconnecté SANS rien
     * rétablir ; l'utilisateur se reconnecte puis recommence.
     * À la réussite : même nettoyage que [signOut] (identifiants chiffrés de
     * reconnexion silencieuse effacés, état, projets cloud).
     *
     * Exécutée dans [viewModelScope] : la portée du dialogue peut être annulée
     * (rotation, fermeture) sans interrompre une suppression IRRÉVERSIBLE déjà
     * engagée ni laisser l'état du ViewModel incohérent ; l'appelant n'attend
     * que le résultat.
     */
    suspend fun deleteAccount(password: String) {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        viewModelScope.async {
            val project = _currentProject.value
            stopObserving()
            try {
                service.deleteAccount(password)
            } catch (e: SyncException.DeletionIncomplete) {
                clearSignedInState()
                throw e
            } catch (e: Exception) {
                project?.let { startObserving(it.id) }
                throw e
            }
            clearSignedInState()
        }.await()
    }

    /** État local « déconnecté » : identifiants de reconnexion silencieuse effacés, projets cloud oubliés. */
    private fun clearSignedInState() {
        runCatching { SyncCredentialStore.clear(ctx) }
        _auth.value = AuthState.SignedOut
        _currentProject.value = null
        _cloudProjects.value = emptyList()
        com.minou.mvrviewer.ui.ProAccess.setAccountGrant(false)
        com.minou.mvrviewer.ui.ProAccess.clearAccount()
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
            currentMvrSha = null
            stopObserving()
            _currentProject.value = null
            loadAndMergeAudit() // journal local dispo même sans compte
            if (_auth.value.isSignedIn) {
                runCatching { projectForOpenedDocument(contentKey) }.getOrNull()?.let {
                    setCurrentProject(it)
                    loadAndMergeAudit()
                }
            }
            _status.value = SyncStatus.Idle
        }
    }

    private suspend fun reattachAfterAuth() {
        val key = currentContentKey ?: return
        runCatching { projectForOpenedDocument(key) }.getOrNull()?.let { setCurrentProject(it) }
    }

    /** Projet cloud du document ouvert : voir [DownloadedProjects]. */
    private suspend fun projectForOpenedDocument(contentKey: String): CloudProject? =
        downloadedProjects.projectFor(service, contentKey, _auth.value.accountOrNull?.uid)

    fun detach() {
        stopObserving()
        _currentProject.value = null
        currentContentKey = null
        currentMvrBytes = null
        currentMvrSha = null
    }

    /**
     * Empreinte du .mvr ouvert, mise en cache : elle ne se calcule qu'à la publication ou à
     * l'annonce d'une version plus récente (pas à chaque ouverture d'un gros show).
     */
    private fun openedMvrSha(): String? {
        currentMvrSha?.let { return it }
        val data = currentMvrBytes ?: return null
        return ContentHash.sha256Hex(data).also { currentMvrSha = it }
    }

    // MARK: - Publier / rejoindre

    suspend fun publishCurrentProject(name: String): InviteCode {
        // Partager = ouvrir SON projet à l'équipe : édition. Rejoindre reste gratuit.
        if (!com.minou.mvrviewer.ui.ProAccess.gate("partager un projet") {}) throw SyncException.Message("Version Pro requise")
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        // L'empreinte est prise AVANT toute suspension : c'est elle qui reconnaît l'écho de
        // notre propre publication (handleInbound → MvrVersionRule).
        val key = currentContentKey; val data = currentMvrBytes; val sha = openedMvrSha()
        if (key == null || data == null || sha == null) throw SyncException.Message(ctx.getString(com.minou.mvrviewer.R.string.sync_err_no_open_project))
        _status.value = SyncStatus.Syncing
        try {
            val project = runCatching { projectForOpenedDocument(key) }.getOrNull()
                ?: service.createProject(key, name)
            setCurrentProject(project)
            if (!service.blobExists(project.id, sha)) {
                _status.value = SyncStatus.Uploading(0.0)
                service.uploadBlob(project.id, sha, BlobKind.MVR, data) { p ->
                    _status.value = SyncStatus.Uploading(p)
                }
            }
            val version = service.publishMVRVersion(project.id, sha)
            // Notre version devient celle du projet courant (sinon il resterait à la version
            // d'avant), si son événement ne l'a pas déjà fait.
            if (_currentProject.value?.id == project.id) {
                advanceMvrVersion(version, sha, _auth.value.accountOrNull?.uid ?: "")
            }
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
        }.also {
            downloadedProjects.remember(it, pid)
            _status.value = SyncStatus.Idle
        }
    }

    /** Téléverse le plan DXF (`refplan.bin`, format d'échange DXP1) ; renvoie le SHA. */
    suspend fun uploadRefPlan(interopBytes: ByteArray): String? {
        if (!com.minou.mvrviewer.ui.ProAccess.isPro) return null
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

    /**
     * Envois de la 3D des types MA : dans [viewModelScope] (survivent à l'écran qui les a
     * demandés), dédoublonnés, bornés — cf. [FixtureBlobUploader].
     */
    private val fixtureUploader = FixtureBlobUploader(
        scope = viewModelScope,
        exists = { pid, sha -> service.blobExists(pid, sha) },
        upload = { pid, sha, data -> service.uploadBlob(pid, sha, BlobKind.FIXTURE_3D, data, null) }
    )

    /**
     * S'assure que le blob `sha256` (déjà en cache local) est dans le Storage du projet :
     * `blobExists`, sinon lecture paresseuse des octets puis envoi (SPEC-ma.md §4.3 règles
     * (1) et (2)). L'envoi continue EN FOND au-delà de [waitMs] : l'appelant n'est jamais
     * bloqué plus longtemps (« un échec ne bloque NI l'enregistrement NI le push »).
     * Renvoie null si rien n'a été tenté (filet silencieux : gratuit, non connecté, projet
     * non partagé, blob absent du cache), true si le blob est en ligne, false si l'envoi a
     * échoué OU n'a pas fini dans le délai (nouvel essai au push suivant).
     */
    suspend fun ensureFixtureBlobUploaded(
        sha256: String,
        waitMs: Long = FixtureBlobUploader.WAIT_MS,
        bytes: suspend () -> ByteArray?
    ): Boolean? {
        if (!com.minou.mvrviewer.ui.ProAccess.isPro) return null
        val project = _currentProject.value ?: return null
        if (!_auth.value.isSignedIn) return null
        return fixtureUploader.ensure(project.id, sha256, bytes, waitMs)
    }

    /** Issue d'un téléchargement de 3D de type MA (la vérification du SHA est faite par l'appelant). */
    sealed class FixtureBlobDownload {
        class Ok(val bytes: ByteArray) : FixtureBlobDownload()
        /** Absent du Storage du projet (ou pas de projet partagé ouvert). */
        object Missing : FixtureBlobDownload()
        /** Objet Storage > 64 Mo : refusé AVANT téléchargement. */
        object TooLarge : FixtureBlobDownload()
        /** Hors ligne / erreur réseau : nouvel essai plus tard. */
        object Failed : FixtureBlobDownload()
    }

    /**
     * Télécharge une 3D de type MA (SPEC-ma.md §4.3). JAMAIS gardé Pro (réception) ;
     * taille lue AVANT `getBytes` (plafond 64 Mo).
     */
    suspend fun downloadFixtureBlob(sha256: String): FixtureBlobDownload {
        val project = _currentProject.value ?: return FixtureBlobDownload.Missing
        // Délais BORNÉS : hors ligne, Firebase Storage relance `getMetadata` / `getBytes`
        // jusqu'à 10 min ; « Téléchargement impossible » doit apparaître bien avant.
        val size = try {
            kotlinx.coroutines.withTimeout(BLOB_METADATA_TIMEOUT_MS) { service.blobSize(project.id, sha256) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return FixtureBlobDownload.Failed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return FixtureBlobDownload.Failed
        } ?: return FixtureBlobDownload.Missing
        if (size > com.minou.mvrviewer.mvr.Fixture3DModel.MAX_BYTES) return FixtureBlobDownload.TooLarge
        return try {
            kotlinx.coroutines.withTimeout(BLOB_DOWNLOAD_TIMEOUT_MS) {
                FixtureBlobDownload.Ok(service.downloadBlob(project.id, BlobRef(sha256, BlobKind.FIXTURE_3D, 0, ""), null))
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            FixtureBlobDownload.Failed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SyncException.BlobMissing) {
            FixtureBlobDownload.Missing
        } catch (e: Exception) {
            FixtureBlobDownload.Failed
        }
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
        // FILET PRO, SILENCIEUX (jamais de paywall depuis un effet de fond) :
        // un gratuit n'écrit jamais dans le projet partagé, quel que soit le
        // chemin — ses aides de lecture restent locales.
        if (!com.minou.mvrviewer.ui.ProAccess.isPro) return
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

    /** Ancres + relevé 3 points (gardé seulement avec 2 ancres, renvoyé tel quel). */
    fun pushCalibration(anchors: List<GeoAnchor>, survey: com.minou.mvrviewer.mvr.GeoSurvey? = null) =
        push(SectionPayload.Calibration(LocalMapper.calibrationDTO(anchors, survey)))

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
        if (!com.minou.mvrviewer.ui.ProAccess.isPro) return null
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
            is RemoteEvent.MvrVersion -> advanceMvrVersion(event.version, event.sha256, event.updatedBy)
            is RemoteEvent.MembershipChanged -> viewModelScope.launch { refreshProjects() }
            is RemoteEvent.Audit -> {
                val key = currentContentKey
                _auditLog.value =
                    if (key != null) AuditStore.append(ctx, key, event.entries).sortedByDescending { it.epoch }
                    else mergeAuditInMemory(event.entries)
            }
        }
    }

    /** Version du .mvr publiée ici ou reçue : voir [MvrVersionRule]. */
    private fun advanceMvrVersion(version: Int, sha256: String, updatedBy: String) {
        val r = MvrVersionRule.advance(
            _currentProject.value, _pendingMvrVersion.value, version, sha256, updatedBy, ::openedMvrSha
        ) ?: return
        _currentProject.value = r.project
        _pendingMvrVersion.value = r.notice
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
        // Ne pas laisser le singleton ProAccess retenir ce ViewModel.
        if (com.minou.mvrviewer.ui.ProAccess.grantRefresher === grantRefresher) {
            com.minou.mvrviewer.ui.ProAccess.grantRefresher = null
        }
        if (com.minou.mvrviewer.ui.ProAccess.purchaseLinker === purchaseLinker) {
            com.minou.mvrviewer.ui.ProAccess.purchaseLinker = null
        }
    }
}

/** Lecture de la taille d'un blob 3D de type MA (hors ligne : échec rapide, pas 10 min). */
private const val BLOB_METADATA_TIMEOUT_MS = 20_000L
/** Téléchargement d'un blob 3D de type MA (≤ 64 Mo). */
private const val BLOB_DOWNLOAD_TIMEOUT_MS = 180_000L
