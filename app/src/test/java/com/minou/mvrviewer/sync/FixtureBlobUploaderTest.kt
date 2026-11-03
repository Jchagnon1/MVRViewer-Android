package com.minou.mvrviewer.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Envoi de la 3D des types MA (SPEC-ma.md §4.3) : « un échec ne bloque NI l'enregistrement
 * NI le push ». RÉGRESSION : hors ligne, Firebase Storage relance `getMetadata` puis
 * `putBytes` jusqu'à 10 min chacun ; l'enregistrement, l'affectation et le push de
 * `customFixtures` attendaient sans limite, et quitter l'écran annulait tout.
 */
class FixtureBlobUploaderTest {

    private val bytes = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n".toByteArray()
    private val sha = ContentHash.sha256Hex(bytes)
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)   // ≈ viewModelScope

    @After fun stop() = bg.cancel()

    private suspend fun waitUntil(cond: () -> Boolean) = withTimeout(5_000) { while (!cond()) delay(5) }

    @Test fun callerWaitIsBoundedAndTheUploadKeepsGoingInTheBackground() = runBlocking {
        val network = CompletableDeferred<Unit>()      // réseau coupé : rien ne répond
        val uploads = AtomicInteger()
        val up = FixtureBlobUploader(bg, exists = { _, _ -> network.await(); false },
            upload = { _, _, _ -> uploads.incrementAndGet() })
        val t0 = System.nanoTime()
        assertEquals(false, up.ensure("p", sha, { bytes }, waitMs = 100))   // « pas encore en ligne »
        assertTrue("l'appelant n'est pas bloqué", (System.nanoTime() - t0) / 1_000_000 < 3_000)
        assertFalse(up.isKnownOnline("p", sha))
        // Le réseau revient : l'envoi lancé plus tôt aboutit EN FOND, sans nouvel appel.
        network.complete(Unit)
        waitUntil { up.isKnownOnline("p", sha) }
        assertEquals(1, uploads.get())
        // Blob confirmé : réponse immédiate, aucun aller-retour réseau de plus.
        assertEquals(true, up.ensure("p", sha, { error("ne doit pas relire les octets") }, waitMs = 100))
        assertEquals(1, uploads.get())
    }

    @Test fun concurrentCallersShareOneUploadAndLeavingTheScreenDoesNotStopIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val uploads = AtomicInteger()
        val up = FixtureBlobUploader(bg, exists = { _, _ -> false },
            upload = { _, _, _ -> uploads.incrementAndGet(); gate.await() })
        // Écran MA (« Envoi de la 3D… ») ET push de customFixtures demandent le même blob.
        val screen = launch { up.ensure("p", sha, { bytes }, waitMs = 60_000) }
        val push = async { up.ensure("p", sha, { bytes }, waitMs = 60_000) }
        waitUntil { uploads.get() == 1 }
        screen.cancel()                                 // l'utilisateur quitte l'écran
        gate.complete(Unit)
        assertEquals(true, push.await())
        assertEquals(1, uploads.get())                  // un seul envoi, non annulé
        assertTrue(up.isKnownOnline("p", sha))
        // Autre projet : autre envoi.
        assertEquals(true, up.ensure("q", sha, { bytes }, waitMs = 5_000))
        assertEquals(2, uploads.get())
    }

    @Test fun failuresNeverThrowAndAreRetriedOnTheNextPush() = runBlocking {
        var fail = true
        val up = FixtureBlobUploader(bg, exists = { _, _ -> false },
            upload = { _, _, _ -> if (fail) throw java.io.IOException("hors ligne") })
        assertEquals(false, up.ensure("p", sha, { bytes }, waitMs = 5_000))
        assertNull(up.ensure("p", sha, { null }, waitMs = 5_000))                 // plus en cache : rien tenté
        assertEquals(false, up.ensure("p", sha, { "autre".toByteArray() }, waitMs = 5_000))  // SHA faux : refusé
        fail = false
        assertEquals(true, up.ensure("p", sha, { bytes }, waitMs = 5_000))        // push suivant : réussi
    }

    @Test fun aStuckUploadIsAbandonedAfterTheJobTimeout() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val up = FixtureBlobUploader(bg, exists = { _, _ -> calls.incrementAndGet(); never.await(); true },
            upload = { _, _, _ -> }, jobTimeoutMs = 200)
        // L'appelant attend plus que la durée maximale de l'envoi : réponse « échec ».
        assertEquals(false, up.ensure("p", sha, { bytes }, waitMs = 5_000))
        // Le job bloqué n'est pas rejoint indéfiniment : le push suivant relance un essai.
        val all = listOf(async { up.ensure("p", sha, { bytes }, 5_000) }).awaitAll()
        assertEquals(listOf(false), all)
        assertEquals(2, calls.get())
    }
}
