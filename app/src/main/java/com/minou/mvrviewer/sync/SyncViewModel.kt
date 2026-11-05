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

    /**
     * Message d'APPARTENANCE en attente (accès perdu, projet quitté ou supprimé —
     * SPEC-partage-moderation §5.12, §6.3) ; l'interface l'efface une fois montré
     * ([consumeProjectNotice]).
     */
    private val _projectNotice = MutableStateFlow<ProjectNotice?>(null)
    val projectNotice: StateFlow<ProjectNotice?> = _projectNotice.asStateFlow()
    /** Code courant changé par la vérification (§5.2) ; montré puis effacé par « Partager le projet ». */
    private val _shareCodeNotice = MutableStateFlow<ShareCodeNotice?>(null)
    val shareCodeNotice: StateFlow<ShareCodeNotice?> = _shareCodeNotice.asStateFlow()

    /** Projets que JE quitte ou supprime en ce moment : leur perte d'accès n'est pas « vous en avez été retiré ». */
    private val expectedDepartures = HashSet<String>()
    /** Mise à jour de l'ouverture d'un projet partagé (mon nom, code courant), en tâche de fond. */
    private var openMaintenanceJob: Job? = null
    /** Personnes que j'ai retirées : uid → nom, sur CET appareil seulement (§5.5). */
    private val removedMembers = RemovedMembers(PrefsRemovedMembersStore(ctx))

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

    /** Adresse e-mail du compte connecté VÉRIFIÉE ? (SPEC-abonnement §4.1.6) — relue
     *  à la connexion, au retour au premier plan et à l'ouverture du paywall et de
     *  Compte & réglages ; null : déconnecté ou inconnu. */
    private val _emailVerified = MutableStateFlow<Boolean?>(null)
    val emailVerified: StateFlow<Boolean?> = _emailVerified.asStateFlow()

    /** Le backend fait-il vérifier les achats (`verifyPurchase`) ? (Firebase oui, démo non.) */
    val verifiesPurchases: Boolean get() = service.verifiesPurchases

    // Licence Pro du COMPTE (`entitlements/{uid}`, cf. SyncService.fetchProGrant) :
    // relue après CHAQUE connexion réussie, au retour au premier plan et par
    // « Restaurer mes achats » (crochet ProAccess.grantRefresher, `done` appelé
    // APRÈS la lecture), oubliée à la déconnexion.
    private val grantRefresher: (() -> Unit) -> Unit = { done ->
        if (_auth.value.accountOrNull == null) done()
        else viewModelScope.launch { try { purchaseLinks.refreshProGrant() } finally { done() } }
    }

    // ABONNEMENT PRO RECONNU PARTOUT (SPEC-purchase.md §6.1/§6.3, SPEC-abonnement
    // §4.1) : la preuve de l'abonnement Play part vers la fonction `verifyPurchase`,
    // qui le vérifie, l'acquitte, le rattache au compte et écrit `entitlements/{uid}`
    // (lu par fetchProGrant) → le Pro souscrit ici vaut aussi sur iPhone et sur le
    // web. Déclencheurs : ProAccess (abonnement, « Restaurer mes achats », relecture
    // Play — dont un nouvel `orderId`) via [purchaseLinker], chaque relecture de
    // licence, retour au premier plan ([onForeground]).
    private val purchaseLinks = PurchaseLinker(
        service = service,
        transport = HttpPurchaseLinkTransport(),
        store = PrefsPurchaseLinkStore(ctx),
        proof = { com.minou.mvrviewer.ui.ProAccess.currentProof() },
        currentUid = { _auth.value.accountOrNull?.uid },
        applyGrant = { uid, grant -> com.minou.mvrviewer.ui.ProAccess.applyAccountLicence(grant, uid) },
        showRefusal = { com.minou.mvrviewer.ui.ProAccess.setAccountLinkRefusal(it) }
    )
    /** `done` APRÈS la réponse du serveur, quelle qu'elle soit : c'est ensuite que
     *  ProAccess acquitte un abonnement que le serveur n'aurait pas acquitté (§4.3). */
    private val purchaseLinker: (Boolean, () -> Unit) -> Unit = { explicit, done ->
        viewModelScope.launch { try { purchaseLinks.linkPurchase(explicit) } finally { done() } }
    }

    init {
        com.minou.mvrviewer.ui.ProAccess.grantRefresher = grantRefresher
        com.minou.mvrviewer.ui.ProAccess.purchaseLinker = purchaseLinker
        com.minou.mvrviewer.ui.ProAccess.verifiesPurchases = service.verifiesPurchases
    }

    // MARK: - Auth

    fun restoreSessionIfPossible() {
        viewModelScope.launch {
            val acc = runCatching { service.restoreSession() }.getOrNull()
            if (acc == null) {
                // Session perdue (compte supprimé ailleurs, données effacées) : comme
                // une déconnexion, la part licence du cache Pro est oubliée (§2.4).
                if (_auth.value.accountOrNull == null) com.minou.mvrviewer.ui.ProAccess.clearAccountLicence()
                return@launch
            }
            _auth.value = AuthState.SignedIn(acc)
            bindProAccount(acc)
            refreshProGrant()
            refreshEmailVerification()
            refreshProjects()
        }
    }

    /** Compte connecté → ProAccess : compte de la licence (part du cache), uid pour
     *  l'`obfuscatedAccountId` du flux d'achat SEULEMENT si le backend vérifie les
     *  achats (sinon l'abonnement serait refusé `409` à tout vrai compte), crochet
     *  d'envoi de la preuve (re)branché. */
    private fun bindProAccount(acc: AccountInfo) {
        val pro = com.minou.mvrviewer.ui.ProAccess
        pro.verifiesPurchases = service.verifiesPurchases
        pro.bindAccount(acc.uid)
        pro.accountUid = if (service.verifiesPurchases) acc.uid else null
        pro.purchaseLinker = purchaseLinker
    }

    /**
     * Relit la licence du compte pour la session courante et l'applique à
     * [com.minou.mvrviewer.ui.ProAccess]. No-op si déconnecté. Dans
     * [viewModelScope] (ne retarde pas la connexion). ORDRE (SPEC-purchase
     * §6.1) : envoi automatique de la preuve si nécessaire → `fetchProGrant` →
     * réparation (§4.1.3). Une lecture en ÉCHEC ne change rien. Garde de course :
     * si la session a changé pendant l'envoi ou la lecture, le résultat n'est pas
     * appliqué. Rien ici ne lève.
     */
    private fun refreshProGrant() {
        if (_auth.value.accountOrNull == null) return
        viewModelScope.launch { purchaseLinks.refreshProGrant() }
    }

    /** Envoie la preuve d'abonnement ; `explicit` = geste de l'utilisateur.
     *  Rattaché → licence relue. No-op si déconnecté. */
    fun linkPurchase(explicit: Boolean) {
        viewModelScope.launch { purchaseLinks.linkPurchase(explicit) }
    }

    /**
     * Retour au premier plan (MainActivity.onResume, SPEC-abonnement §4.1.2) : la
     * licence du compte est relue (renouvellement, résiliation, échéance passée
     * app en arrière-plan) — la relecture renvoie d'abord une preuve restée « à
     * réessayer » ou d'une nouvelle période — puis l'adresse e-mail (lien de
     * vérification ouvert dans la messagerie). Google Play est relu par
     * `ProAccess.onForeground`.
     */
    fun onForeground() {
        refreshProGrant()
        refreshEmailVerification()
    }

    /** Relit l'état de vérification de l'adresse du compte connecté et le donne au
     *  paywall (bouton « Vérifiez votre adresse… »). */
    fun refreshEmailVerification() {
        val uid = _auth.value.accountOrNull?.uid ?: return
        viewModelScope.launch {
            val verified = runCatching { service.reloadEmailVerified() }.getOrNull()
            if (_auth.value.accountOrNull?.uid != uid) return@launch
            _emailVerified.value = verified
            com.minou.mvrviewer.ui.ProAccess.emailVerified = verified
        }
    }

    /** « Renvoyer l'e-mail de vérification » (gratuit). */
    suspend fun sendEmailVerification() {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        service.sendEmailVerification()
    }

    suspend fun signIn(email: String, password: String) {
        _auth.value = AuthState.SigningIn
        try {
            val acc = service.signIn(email, password)
            _auth.value = AuthState.SignedIn(acc)
            bindProAccount(acc)
            refreshProGrant()
            refreshEmailVerification()
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
            refreshEmailVerification()
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
            cancelOpenMaintenance()
            _auth.value = AuthState.SignedOut
            _currentProject.value = null
            _cloudProjects.value = emptyList()
            _projectNotice.value = null
            _shareCodeNotice.value = null
            _emailVerified.value = null
            // Licence du compte oubliée, ET sa part du cache Pro (§2.4).
            com.minou.mvrviewer.ui.ProAccess.clearAccountLicence()
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
        cancelOpenMaintenance()
        _auth.value = AuthState.SignedOut
        _currentProject.value = null
        _cloudProjects.value = emptyList()
        _projectNotice.value = null
        _shareCodeNotice.value = null
        _emailVerified.value = null
        com.minou.mvrviewer.ui.ProAccess.clearAccountLicence()
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
            cancelOpenMaintenance()
            _currentProject.value = null
            loadAndMergeAudit() // journal local dispo même sans compte
            if (_auth.value.isSignedIn) {
                runCatching { projectForOpenedDocument(contentKey) }.getOrNull()?.let {
                    setCurrentProject(it)
                    startOpenMaintenance(it)
                    loadAndMergeAudit()
                }
            }
            _status.value = SyncStatus.Idle
        }
    }

    private suspend fun reattachAfterAuth() {
        val key = currentContentKey ?: return
        runCatching { projectForOpenedDocument(key) }.getOrNull()?.let {
            setCurrentProject(it)
            startOpenMaintenance(it)
        }
    }

    /**
     * Ouverture d'un projet partagé (SPEC-partage-moderation §5.11), SANS bloquer
     * l'ouverture : mon nom dans le projet s'il manque ou a changé (§5.8) — pris tout de
     * suite pour signer le journal (§5.14), écrit avant les entrées qui suivront — et,
     * chez le propriétaire, le code courant vérifié (§5.2). Erreurs ignorées. GRATUIT.
     */
    private fun startOpenMaintenance(p: CloudProject) {
        val me = _auth.value.accountOrNull ?: return
        ProjectMembership.nameToWrite(p, me)?.let { name ->
            val cp = _currentProject.value
            if (cp != null && cp.id == p.id) {
                _currentProject.value = cp.copy(memberNames = cp.memberNames.orEmpty() + (me.uid to name))
            }
        }
        openMaintenanceJob?.cancel()
        openMaintenanceJob = viewModelScope.launch {
            ProjectMembership.openMaintenance(service, p, me)?.let { applyCodeResult(it) }
        }
    }

    private fun cancelOpenMaintenance() {
        openMaintenanceJob?.cancel()
        openMaintenanceJob = null
    }

    /** Le code rendu par une vérification ou une rotation devient celui du projet courant ; un changement non demandé est annoncé (§5.2). */
    private fun applyCodeResult(result: ShareCodeResult) {
        val code = result.code
        val cp = _currentProject.value
        if (code != null && cp != null && cp.id == result.projectId && cp.inviteCode != code) {
            _currentProject.value = cp.copy(inviteCode = code)
        }
        val change = result.change
        if (change != null && code != null) _shareCodeNotice.value = ShareCodeNotice(result.projectId, code, change)
    }

    /** Projet cloud du document ouvert : voir [DownloadedProjects]. */
    private suspend fun projectForOpenedDocument(contentKey: String): CloudProject? =
        downloadedProjects.projectFor(service, contentKey, _auth.value.accountOrNull?.uid)

    fun detach() {
        stopObserving()
        cancelOpenMaintenance()
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

    /**
     * Publie le .mvr ouvert (SPEC-partage-moderation §5.1, [SharePublisher]) : NOUVEAU
     * projet = projet + invitation dans un lot attendu ; projet EXISTANT = aucune
     * invitation créée, le code est celui du projet (vérifié chez le propriétaire ; null
     * pour un membre d'un projet ancien : « Demandez le code au propriétaire du
     * projet. »). Garde Pro inchangée.
     */
    suspend fun publishCurrentProject(name: String): ShareCodeResult {
        // Partager = ouvrir SON projet à l'équipe : édition. Rejoindre reste gratuit.
        if (!com.minou.mvrviewer.ui.ProAccess.gate("partager un projet") {}) throw SyncException.Message("Version Pro requise")
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        // L'empreinte est prise AVANT toute suspension : c'est elle qui reconnaît l'écho de
        // notre propre publication (handleInbound → MvrVersionRule).
        val key = currentContentKey; val data = currentMvrBytes; val sha = openedMvrSha()
        if (key == null || data == null || sha == null) throw SyncException.Message(ctx.getString(com.minou.mvrviewer.R.string.sync_err_no_open_project))
        _status.value = SyncStatus.Syncing
        try {
            val existing = runCatching { projectForOpenedDocument(key) }.getOrNull()
            val published = SharePublisher(service).publish(
                existing, key, name, sha, data,
                onProject = { setCurrentProject(it) },
                onUploading = { p -> _status.value = SyncStatus.Uploading(p) },
                onVersion = { project, version ->
                    // Notre version devient celle du projet courant (sinon il resterait à la
                    // version d'avant), si son événement ne l'a pas déjà fait.
                    if (_currentProject.value?.id == project.id) {
                        advanceMvrVersion(version, sha, _auth.value.accountOrNull?.uid ?: "")
                    }
                }
            )
            applyCodeResult(published.code)
            refreshProjects()
            return published.code
        } finally {
            _status.value = SyncStatus.Idle
        }
    }

    /** Rejoint un projet par code (§5.3, gratuit : preuve du code et mon nom dans la même écriture). */
    suspend fun join(code: String): CloudProject {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        val p = service.joinProject(code)
        refreshProjects()
        return p
    }

    // MARK: - Partage et modération (SPEC-partage-moderation §5)
    //
    // GRATUITES (décision du 3/11/2026, §10) : aucune garde Pro, jamais par le filet
    // `push` — elles portent sur l'APPARTENANCE et l'administration du projet, pas sur
    // le contenu du show. Les refus des règles arrivent en [SyncException.ActionRefused],
    // le hors-ligne en [SyncException.NetworkUnavailable] (rien n'est mis en file).

    /** Efface le message d'appartenance une fois montré. */
    fun consumeProjectNotice() { _projectNotice.value = null }

    /** Efface le message de changement de code une fois montré par « Partager le projet ». */
    fun consumeShareCodeNotice(notice: ShareCodeNotice) {
        if (_shareCodeNotice.value == notice) _shareCodeNotice.value = null
    }

    /**
     * Vérifie le code courant (§5.2) — chez le propriétaire, adoption, code disparu ou
     * repris → nouveau code (annoncé par [shareCodeNotice]) ; chez un membre, le code lu
     * (null : projet ancien).
     */
    suspend fun ensureCurrentCode(projectId: String): ShareCodeResult {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        return service.ensureCurrentCode(projectId).also { applyCodeResult(it) }
    }

    /** Nouveau code (§5.2, N2) : l'ancien ne fait plus entrer personne. */
    suspend fun rotateInviteCode(projectId: String): String {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        return committed { service.rotateInviteCode(projectId).also { applyCodeResult(ShareCodeResult(projectId, it)) } }
    }

    /**
     * Une action de modération va jusqu'au bout dans [viewModelScope] : la fenêtre qui
     * l'a lancée peut disparaître (rotation) sans couper une suppression en lots ni
     * laisser l'état du ViewModel à moitié à jour ; l'appelant n'attend que le résultat.
     */
    private suspend fun <T> committed(block: suspend () -> T): T = viewModelScope.async { block() }.await()

    /**
     * Quitter (§5.4) : le projet quitté se détache aussitôt (écouteurs, envois) ; le
     * fichier ouvert reste sur l'appareil. Message « Vous avez quitté le projet… ».
     */
    suspend fun leaveProject(project: CloudProject) {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        committed {
            expectedDepartures += project.id
            try {
                service.leaveProject(project.id)
                if (_currentProject.value?.id == project.id) dropCurrentProject()
            } finally {
                expectedDepartures -= project.id
            }
            _projectNotice.value = ProjectNotice(project.id, project.name, ProjectNotice.Kind.LEFT)
            refreshProjects()
        }
    }

    /** Supprimer le projet pour tout le monde (§5.9) — propriétaire. */
    suspend fun deleteProject(project: CloudProject) {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        committed {
            expectedDepartures += project.id
            try {
                service.deleteProject(project.id)
                if (_currentProject.value?.id == project.id) dropCurrentProject()
            } finally {
                expectedDepartures -= project.id
            }
            _projectNotice.value = ProjectNotice(project.id, project.name, ProjectNotice.Kind.DELETED)
            refreshProjects()
        }
    }

    /**
     * Retirer un membre (§5.5) = retrait + blocage. Son nom quitte le document partagé ;
     * il reste dans une table LOCALE de cet appareil (liste « Personnes retirées »).
     */
    suspend fun removeMember(uid: String, projectId: String) {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        val known = _currentProject.value?.takeIf { it.id == projectId }
            ?: _cloudProjects.value.firstOrNull { it.id == projectId }
        val name = known?.memberNames?.get(uid)
        committed {
            service.removeMember(projectId, uid)
            if (name != null) removedMembers.record(projectId, uid, name)
            val cp = _currentProject.value
            if (cp != null && cp.id == projectId) {
                _currentProject.value = cp.copy(
                    memberUids = cp.memberUids - uid,
                    bannedUids = cp.bannedUids.orEmpty().let { if (uid in it) it else it + uid },
                    memberNames = cp.memberNames?.minus(uid)
                )
            }
            refreshProjects()
        }
    }

    /** Autoriser à revenir (§5.6) : la personne rejoindra avec le code courant. */
    suspend fun allowBack(uid: String, projectId: String) {
        if (!_auth.value.isSignedIn) throw SyncException.NotSignedIn
        committed {
            service.allowBack(projectId, uid)
            val cp = _currentProject.value
            if (cp != null && cp.id == projectId) _currentProject.value = cp.copy(bannedUids = cp.bannedUids?.minus(uid))
            removedMembers.forget(projectId, uid)
            refreshProjects()
        }
    }

    /** Noms des personnes que J'AI retirées de ce projet (table locale, §5.5). */
    fun removedMemberNames(projectId: String): Map<String, String> = removedMembers.names(projectId)

    /**
     * Plus d'accès au projet courant (§5.12) : écouteurs et envois arrêtés, projet courant
     * vidé, message (sauf départ voulu), liste rafraîchie. Le fichier ouvert, ses réglages
     * locaux et le journal local RESTENT.
     */
    private fun handleAccessLost(projectId: String) {
        val lost = _currentProject.value?.takeIf { it.id == projectId } ?: return
        dropCurrentProject()
        if (projectId !in expectedDepartures) {
            _projectNotice.value = ProjectNotice(projectId, lost.name, ProjectNotice.Kind.ACCESS_LOST)
        }
        viewModelScope.launch { refreshProjects() }
    }

    /** Détache le projet courant SANS fermer le document (contrairement à [detach]) : il reste ouvert, comme un fichier local. */
    private fun dropCurrentProject() {
        stopObserving()
        cancelOpenMaintenance()
        if (_pendingMvrVersion.value?.projectId == _currentProject.value?.id) _pendingMvrVersion.value = null
        _currentProject.value = null
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
            // §5.15 : jamais d'écrasement ; un refus suivi d'un fichier présent est un succès.
            ProjectBlobs.putIfAbsent(service, project.id, sha, BlobKind.REF_PLAN, interopBytes)
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
        // §5.15 : appelé après un `exists` « absent » ; un refus suivi d'un fichier présent est un succès.
        upload = { pid, sha, data -> ProjectBlobs.uploadAbsent(service, pid, sha, BlobKind.FIXTURE_3D, data) }
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

    /**
     * Auteur courant (SPEC-partage-moderation §5.14) : connecté, mon uid et MON nom dans
     * le projet partagé (à défaut mon nom affiché, jamais l'e-mail complet) ; sinon
     * « Moi (hors ligne) », entrée locale seulement.
     */
    val currentAuthor: Pair<String, String>
        get() = ProjectMembership.author(_auth.value.accountOrNull, _currentProject.value,
            ctx.getString(com.minou.mvrviewer.R.string.sync_me_offline))

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
            service.observe(projectId).collect { handleInbound(it, projectId) }
        }
    }

    private fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

    private fun handleInbound(event: RemoteEvent, projectId: String) {
        // Un événement encore en route d'un projet détaché (quitté, retiré, autre show
        // ouvert) n'a plus rien à appliquer.
        if (_currentProject.value?.id != projectId) return
        if (ProjectMembership.lostAccess(event, _auth.value.accountOrNull?.uid)) {
            handleAccessLost(projectId)
            return
        }
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
            is RemoteEvent.ProjectUpdated -> {
                val cp = _currentProject.value ?: return
                val merged = ProjectMembership.merged(cp, event.project)
                if (merged != cp) _currentProject.value = merged
            }
            is RemoteEvent.AccessLost -> handleAccessLost(projectId) // déjà traité par lostAccess
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

    /**
     * Crochet de TEST `MVR_SYNC_ACCOUNT` (app débogable, backend LOCAL seulement — jamais
     * le vrai projet Firebase) : se connecter à [email], compte créé au besoin avec le mot
     * de passe d'essai du bouchon ; son nom affiché est la partie avant « @ » (§4). Rend
     * faux si le backend n'est pas le bouchon local.
     */
    suspend fun debugSignInLocal(email: String): Boolean {
        if (BackendSelector.firebaseAvailable(ctx)) return false
        if (_auth.value.accountOrNull?.email == email) return true
        runCatching { service.signOut() }
        stopObserving()
        cancelOpenMaintenance()
        _auth.value = AuthState.SignedOut
        _currentProject.value = null
        _cloudProjects.value = emptyList()
        signInOrSignUp(email, DEBUG_LOCAL_PASSWORD)
        return true
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

/** Mot de passe d'essai des comptes du bouchon LOCAL créés par le crochet `MVR_SYNC_ACCOUNT` (aucun compte réel). */
private const val DEBUG_LOCAL_PASSWORD = "essai-local-1234"

/** Lecture de la taille d'un blob 3D de type MA (hors ligne : échec rapide, pas 10 min). */
private const val BLOB_METADATA_TIMEOUT_MS = 20_000L
/** Téléchargement d'un blob 3D de type MA (≤ 64 Mo). */
private const val BLOB_DOWNLOAD_TIMEOUT_MS = 180_000L
