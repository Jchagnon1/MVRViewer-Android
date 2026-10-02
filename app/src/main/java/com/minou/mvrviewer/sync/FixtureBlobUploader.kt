package com.minou.mvrviewer.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ENVOI de la 3D importée d'un type MA vers `projects/{id}/blobs/{sha256}` (SPEC-ma.md §4.3)
 * — hors ViewModel, testable en JVM.
 *
 * « Un échec ne bloque NI l'enregistrement NI le push » : les relances par défaut de
 * Firebase Storage durent jusqu'à 10 min (`getMetadata`, `putBytes`) — attendre l'envoi
 * sans limite gelait l'affectation et le push de `customFixtures`, et l'affectation était
 * PERDUE si l'utilisateur quittait l'écran (scope annulé). Donc :
 *  - l'envoi tourne dans [scope] (celui du ViewModel : il survit aux écrans), borné à
 *    [jobTimeoutMs], et il est DÉDOUBLONNÉ par (projet, sha) ;
 *  - l'appelant n'attend qu'au plus `waitMs` ([ensure]) : au-delà, réponse « pas encore en
 *    ligne » (false), l'envoi continue en fond, nouvel essai au push suivant ;
 *  - un blob confirmé en ligne est mémorisé (plus aucun aller-retour réseau pour lui).
 */
class FixtureBlobUploader(
    private val scope: CoroutineScope,
    private val exists: suspend (projectId: String, sha256: String) -> Boolean,
    private val upload: suspend (projectId: String, sha256: String, bytes: ByteArray) -> Unit,
    private val jobTimeoutMs: Long = JOB_TIMEOUT_MS,
    private val maxBytes: Long = com.minou.mvrviewer.mvr.Fixture3DModel.MAX_BYTES
) {
    private class Box(val value: Boolean?)

    private val lock = Any()
    private val online = HashSet<String>()
    private val inFlight = HashMap<String, Deferred<Box>>()

    /** Blob déjà confirmé dans le Storage de ce projet (sans réseau). */
    fun isKnownOnline(projectId: String, sha256: String): Boolean =
        synchronized(lock) { "$projectId/${sha256.lowercase()}" in online }

    /**
     * S'assure que le blob est dans le Storage du projet ; attend AU PLUS [waitMs].
     * @return true en ligne ; false échec OU pas encore fini dans le délai (l'envoi continue) ;
     *         null rien n'a été tenté (octets absents du cache).
     */
    suspend fun ensure(projectId: String, sha256: String, bytes: suspend () -> ByteArray?, waitMs: Long): Boolean? {
        val sha = sha256.lowercase()
        val key = "$projectId/$sha"
        val job = synchronized(lock) {
            if (key in online) return true
            inFlight[key]?.takeIf { it.isActive }
                ?: scope.async { run(projectId, sha, key, bytes) }.also { inFlight[key] = it }
        }
        // `withTimeoutOrNull` rend null au délai dépassé : le résultat est donc mis en boîte.
        val r = withTimeoutOrNull(waitMs) { job.await() } ?: return false
        return r.value
    }

    private suspend fun run(projectId: String, sha: String, key: String, bytes: suspend () -> ByteArray?): Box {
        val r = withTimeoutOrNull(jobTimeoutMs) {
            try {
                if (exists(projectId, sha)) return@withTimeoutOrNull Box(true)
                val data = bytes() ?: return@withTimeoutOrNull Box(null)
                if (data.size > maxBytes) return@withTimeoutOrNull Box(false)
                if (ContentHash.sha256Hex(data) != sha) return@withTimeoutOrNull Box(false)
                upload(projectId, sha, data)
                Box(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Box(false)
            }
        } ?: Box(false)
        synchronized(lock) {
            if (r.value == true) online.add(key)
            inFlight.remove(key)
        }
        return r
    }

    companion object {
        /** Attente maximale d'un appelant (enregistrement, push) avant de continuer sans l'envoi. */
        const val WAIT_MS = 30_000L
        /** Durée maximale d'un envoi en fond (au-delà : échec, nouvel essai au push suivant). */
        const val JOB_TIMEOUT_MS = 5 * 60_000L
    }
}
