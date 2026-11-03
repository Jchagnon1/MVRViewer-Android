package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.Fixture3DModel
import com.minou.mvrviewer.mvr.GdtfShareClient
import com.minou.mvrviewer.sync.CustomFixtureModel3D
import com.minou.mvrviewer.sync.CustomFixtureTypeDTO
import com.minou.mvrviewer.sync.SyncViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * RÉCEPTION de la 3D des types « profil MA + 3D choisie » (SPEC-ma.md §4.2-4.3) — hors
 * Compose, donc testable en JVM. Jamais gardée Pro.
 *
 * Règles (corrigent les blocages de la première version, où les téléchargements vivaient
 * DANS l'effet de la scène et étaient annulés à chaque modification du patch) :
 *  - chaque téléchargement tourne dans un JOB DÉDIÉ de [scope] (celui de la scène) :
 *    une relance (patch, section reçue…) ne l'annule pas et ne le recommence pas ;
 *    dédoublonné par source (`rid:N` / `sha:X`), 2 simultanés au plus ;
 *  - une tentative n'est RETENUE (« ne pas retenter ») qu'une fois allée au bout — une
 *    tentative interrompue (job annulé, même si un appel a avalé l'annulation) n'y entre
 *    pas ; et l'ensemble des tentatives est oublié dès que la `signature` change (projet,
 *    compte, connexion GDTF Share, retour du réseau, nouvelle section `customFixtures`) ;
 *  - GDTF Share : pas d'identifiants ou identifiants refusés → « Connectez-vous… » ; échec
 *    RÉSEAU (hors ligne en salle…) → « Téléchargement de la 3D impossible. Réessayez en
 *    ligne. » ;
 *  - l'inscription des GDTF déjà en cache ([registerCached]) ne dépend d'aucun
 *    téléchargement : un blob lent ou en échec ne retarde plus l'affichage des autres types.
 */
class Type3DFetcher(
    private val scope: CoroutineScope,
    private val store: Fixture3DStore,
    private val backend: Backend,
    maxConcurrent: Int = 2,
    /** Contexte des écritures disque (tests : contexte vide = même fil). */
    private val io: CoroutineContext = Dispatchers.IO
) {
    interface Backend {
        /** Connexion GDTF Share (reconnexion silencieuse depuis les identifiants enregistrés). */
        suspend fun shareLogin(): GdtfShareClient.LoginOutcome
        /** Octets de la révision, ou null (échec réseau / serveur). */
        suspend fun shareDownload(rid: Int): ByteArray?
        /** Un projet PARTAGÉ est-il ouvert ? (sinon aucun blob n'est téléchargeable) */
        fun projectShared(): Boolean
        /** Téléchargement d'un blob du projet (taille vérifiée avant ; SHA vérifié ici). */
        suspend fun blobDownload(sha256: String): SyncViewModel.FixtureBlobDownload
    }

    private val attempts = HashSet<String>()
    private var attemptsSignature: Any? = Any()
    private val inFlight = HashMap<String, Job>()
    private val permits = Semaphore(maxConcurrent)

    /** Source déjà tentée JUSQU'AU BOUT pour la signature courante (diagnostic / tests). */
    fun hasAttempted(m: CustomFixtureModel3D): Boolean = sourceKey(m) in attempts

    /** Téléchargement en cours pour cette source ? */
    fun isFetching(m: CustomFixtureModel3D): Boolean = inFlight[sourceKey(m)]?.isActive == true

    /**
     * Lance SANS ATTENDRE le téléchargement des 3D manquantes des [types] (types RÉFÉRENCÉS
     * par la scène). [signature] : quand elle change, les échecs retenus sont oubliés et
     * retentés. [onArrived] est appelé dans [scope] dès que des octets sont au cache
     * (l'appelant inscrit la clé de rendu / reconstruit la 3D).
     */
    fun fetchMissing(
        types: List<CustomFixtureTypeDTO>,
        signature: Any?,
        onArrived: suspend (CustomFixtureModel3D) -> Unit
    ) {
        if (signature != attemptsSignature) {
            attempts.clear()
            attemptsSignature = signature
        }
        for (t in types) {
            val m = t.model3D ?: continue
            if (store.hasBytes(m)) continue
            // Objet 3D d'un format non lu ici : rien à télécharger (raison « Format non lu… »).
            if (m is CustomFixtureModel3D.ModelBlob && !Fixture3DModel.isReadableHere(m.format)) continue
            val key = sourceKey(m)
            if (key in attempts || inFlight[key]?.isActive == true) continue
            if (m !is CustomFixtureModel3D.GdtfShare && !backend.projectShared()) {
                // Projet non partagé : cache local seulement (retenté quand le projet le devient).
                m.blobSha?.let { store.markIssue(it, Fixture3DReason.MISSING_IN_PROJECT) }
                continue
            }
            val job = scope.launch {
                permits.withPermit { fetchOne(key, m, onArrived) }
            }
            inFlight[key] = job
            job.invokeOnCompletion { scope.launch { if (inFlight[key] === job) inFlight.remove(key) } }
        }
    }

    /** Retient l'échec DÉFINITIF d'une tentative allée au bout (jamais d'une tentative annulée). */
    private suspend fun giveUp(key: String, mark: () -> Unit) {
        currentCoroutineContext().ensureActive()
        mark()
        attempts.add(key)
    }

    private suspend fun fetchOne(key: String, m: CustomFixtureModel3D, onArrived: suspend (CustomFixtureModel3D) -> Unit) {
        when (m) {
            is CustomFixtureModel3D.GdtfShare -> {
                val login = backend.shareLogin()
                // Un appel qui avale l'annulation (runCatching…) ne doit pas faire retenir un
                // faux « Connectez-vous… » : une tentative interrompue n'est pas un échec.
                currentCoroutineContext().ensureActive()
                when (login) {
                    GdtfShareClient.LoginOutcome.OK -> {}
                    GdtfShareClient.LoginOutcome.NO_CREDENTIALS, GdtfShareClient.LoginOutcome.REJECTED ->
                        return giveUp(key) { store.markShareIssue(m.rid, Fixture3DReason.SIGN_IN_SHARE) }
                    GdtfShareClient.LoginOutcome.NETWORK ->
                        return giveUp(key) { store.markShareIssue(m.rid, Fixture3DReason.DOWNLOAD_FAILED) }
                }
                val data = backend.shareDownload(m.rid)
                currentCoroutineContext().ensureActive()
                if (data == null) return giveUp(key) { store.markShareIssue(m.rid, Fixture3DReason.DOWNLOAD_FAILED) }
                withContext(io) { store.putShare(m.rid, data) }
                onArrived(m)
            }
            is CustomFixtureModel3D.GdtfBlob, is CustomFixtureModel3D.ModelBlob -> {
                val sha = m.blobSha ?: return
                val r = backend.blobDownload(sha)
                currentCoroutineContext().ensureActive()
                when (r) {
                    is SyncViewModel.FixtureBlobDownload.Ok -> {
                        val ok = withContext(io) { store.putVerifiedBlob(sha, r.bytes) }
                        if (ok) onArrived(m) else giveUp(key) {}   // SHA faux : « Fichier 3D corrompu. » déjà retenu
                    }
                    SyncViewModel.FixtureBlobDownload.Missing ->
                        giveUp(key) { store.markIssue(sha, Fixture3DReason.MISSING_IN_PROJECT) }
                    SyncViewModel.FixtureBlobDownload.TooLarge,
                    SyncViewModel.FixtureBlobDownload.Failed ->
                        giveUp(key) { store.markIssue(sha, Fixture3DReason.DOWNLOAD_FAILED) }
                }
            }
        }
    }

    companion object {
        /** Identité de dédoublonnage d'une source : `rid:N` (GDTF Share) ou `sha:X` (blob). */
        fun sourceKey(m: CustomFixtureModel3D): String = when (m) {
            is CustomFixtureModel3D.GdtfShare -> "rid:${m.rid}"
            is CustomFixtureModel3D.GdtfBlob -> "sha:${m.sha256.lowercase()}"
            is CustomFixtureModel3D.ModelBlob -> "sha:${m.sha256.lowercase()}"
        }

        /**
         * Inscrit les GDTF (b)(c) DÉJÀ au cache sous leur clé de rendu LOCALE (`type3d:…`,
         * jamais sérialisée) dans [gdtfOverrides] — à faire AVANT (et indépendamment de)
         * tout téléchargement. Renvoie true si une clé a été ajoutée (3D à reconstruire).
         */
        suspend fun registerCached(
            types: List<CustomFixtureTypeDTO>,
            store: Fixture3DStore,
            gdtfOverrides: GdtfOverrides,
            io: CoroutineContext = Dispatchers.IO
        ): Boolean {
            var added = false
            for (t in types) {
                val m = t.model3D ?: continue
                val key = Fixture3DStore.renderKey(m) ?: continue
                if (gdtfOverrides.map.containsKey(key)) continue
                val bytes = withContext(io) { store.gdtfBytes(m) } ?: continue
                gdtfOverrides.map[key] = bytes
                added = true
            }
            return added
        }
    }
}
