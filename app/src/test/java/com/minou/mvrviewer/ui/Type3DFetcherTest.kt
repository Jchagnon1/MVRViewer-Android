package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.GdtfShareClient
import com.minou.mvrviewer.mvr.GdtfShareClient.LoginOutcome
import com.minou.mvrviewer.sync.ContentHash
import com.minou.mvrviewer.sync.CustomFixtureModel3D
import com.minou.mvrviewer.sync.CustomFixtureTypeDTO
import com.minou.mvrviewer.sync.SyncViewModel.FixtureBlobDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Réception de la 3D des types MA (SPEC-ma.md §4.2-4.3, §4.5) — [Type3DFetcher].
 *
 * RÉGRESSIONS couvertes (première version : téléchargements DANS l'effet de la scène) :
 *  - la reconnexion GDTF Share avalait l'annulation (runCatching) quand l'effet était
 *    relancé : « Connectez-vous… » retenu à tort, révision JAMAIS retéléchargée de la session ;
 *  - hors ligne : mauvaise raison (« Connectez-vous… ») et aucun nouvel essai au retour du
 *    réseau ; connexion manuelle ultérieure : aucun nouvel essai ;
 *  - un blob lent retardait l'inscription des GDTF déjà en cache ; chaque modification du
 *    patch annulait puis recommençait un gros téléchargement.
 */
class Type3DFetcherTest {

    private fun store() = Fixture3DStore(Files.createTempDirectory("f3d").toFile().apply { deleteOnExit() })

    private val share = CustomFixtureTypeDTO("s", "Color Strike M", "", listOf("Dim"), 1, null, CustomFixtureModel3D.GdtfShare(4821))
    private val shareModel = share.model3D!!

    private class FakeBackend : Type3DFetcher.Backend {
        var login: suspend () -> LoginOutcome = { LoginOutcome.OK }
        var shareBytes: suspend (Int) -> ByteArray? = { "gdtf-$it".toByteArray() }
        var shared = true
        var blob: suspend (String) -> FixtureBlobDownload = { FixtureBlobDownload.Missing }
        val logins = AtomicInteger()
        val shareCalls = AtomicInteger()
        val blobCalls = AtomicInteger()
        override suspend fun shareLogin(): LoginOutcome { logins.incrementAndGet(); return login() }
        override suspend fun shareDownload(rid: Int): ByteArray? { shareCalls.incrementAndGet(); return shareBytes(rid) }
        override fun projectShared() = shared
        override suspend fun blobDownload(sha256: String): FixtureBlobDownload { blobCalls.incrementAndGet(); return blob(sha256) }
    }

    private suspend fun idle(f: Type3DFetcher, vararg ms: CustomFixtureModel3D) =
        withTimeout(5_000) { while (ms.any { f.isFetching(it) }) delay(1) }

    @Test fun relaunchDuringTheSilentLoginNeitherCancelsNorRestartsIt() = runBlocking {
        val st = store(); val be = FakeBackend(); val gate = CompletableDeferred<Unit>()
        be.login = { gate.await(); LoginOutcome.OK }
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Type3DFetcher(scope, st, be, io = EmptyCoroutineContext)
        val arrived = ArrayList<CustomFixtureModel3D>()
        f.fetchMissing(listOf(share), "sig") { arrived += it }
        yield()
        // Ouverture d'un projet partagé : patch restauré, section reçue, instantané… = relances.
        repeat(3) { f.fetchMissing(listOf(share), "sig") { arrived += it }; yield() }
        assertTrue(f.isFetching(shareModel))
        gate.complete(Unit)
        idle(f, shareModel)
        assertEquals(1, be.logins.get())
        assertEquals(1, be.shareCalls.get())
        assertTrue(st.hasShare(4821))
        assertEquals(listOf(shareModel), arrived)
        assertNull(st.issues[Fixture3DStore.renderKeyForRid(4821)])
        scope.cancel()
    }

    @Test fun anInterruptedAttemptIsNeverRememberedAsASignInFailure() = runBlocking {
        val st = store(); val be = FakeBackend(); val gate = CompletableDeferred<Unit>()
        // Comme l'ancien `runCatching { login() }` : l'annulation est AVALÉE et maquillée en échec.
        be.login = { try { gate.await(); LoginOutcome.OK } catch (e: CancellationException) { LoginOutcome.NO_CREDENTIALS } }
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Type3DFetcher(scope, st, be, io = EmptyCoroutineContext)
        f.fetchMissing(listOf(share), "sig") {}
        yield()
        scope.cancel()          // scène quittée pendant la reconnexion
        delay(20)
        assertFalse("tentative interrompue retenue", f.hasAttempted(shareModel))
        assertNull(st.issues[Fixture3DStore.renderKeyForRid(4821)])
    }

    @Test fun offlineIsADownloadFailureRetriedWhenTheNetworkComesBack() = runBlocking {
        val st = store(); val be = FakeBackend()
        be.login = { LoginOutcome.NETWORK }      // identifiants enregistrés, salle sans réseau
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Type3DFetcher(scope, st, be, io = EmptyCoroutineContext)
        fun sig(net: Int) = listOf("projet", true, false, net)
        f.fetchMissing(listOf(share), sig(0)) {}
        idle(f, shareModel)
        val u = st.unavailability(share, hasOrigin = true, shareSignedIn = false, shareCredentialsStored = true)!!
        assertEquals(Fixture3DReason.DOWNLOAD_FAILED, u.reason)      // PAS « Connectez-vous… »
        // Même signature : pas de boucle de nouveaux essais.
        f.fetchMissing(listOf(share), sig(0)) {}
        idle(f, shareModel)
        assertEquals(1, be.logins.get())
        // Le réseau revient (nouvelle signature) : nouvel essai, réussi.
        be.login = { LoginOutcome.OK }
        f.fetchMissing(listOf(share), sig(1)) {}
        idle(f, shareModel)
        assertEquals(2, be.logins.get())
        assertTrue(st.hasShare(4821))
        assertNull(st.unavailability(share, hasOrigin = true, shareSignedIn = true))
        scope.cancel()
    }

    @Test fun noAccountThenManualSignInRetriesAndClearsTheReason() = runBlocking {
        val st = store(); val be = FakeBackend()
        be.login = { LoginOutcome.NO_CREDENTIALS }
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Type3DFetcher(scope, st, be, io = EmptyCoroutineContext)
        f.fetchMissing(listOf(share), listOf("projet", false)) {}
        idle(f, shareModel)
        assertEquals(Fixture3DReason.SIGN_IN_SHARE, st.unavailability(share, true, shareSignedIn = false)!!.reason)
        // Connexion manuelle depuis l'écran GDTF Share : la raison périmée disparaît…
        st.clearShareSignInIssues()
        assertNull(st.unavailability(share, true, shareSignedIn = true)!!.reason)
        // …et la nouvelle signature (shareSignedIn = true) relance le téléchargement.
        be.login = { LoginOutcome.OK }
        f.fetchMissing(listOf(share), listOf("projet", true)) {}
        idle(f, shareModel)
        assertTrue(st.hasShare(4821))
        scope.cancel()
    }

    @Test fun cachedGdtfIsRegisteredWithoutWaitingForASlowDownload() = runBlocking {
        val st = store(); val be = FakeBackend(); val never = CompletableDeferred<Unit>()
        st.putShare(77, "gdtf-77".toByteArray())
        val cached = CustomFixtureTypeDTO("a", "A", "", listOf("Dim"), 1, null, CustomFixtureModel3D.GdtfShare(77))
        val slow = CustomFixtureTypeDTO("b", "B", "", listOf("Dim"), 1, null,
            CustomFixtureModel3D.ModelBlob("b".repeat(64), "bar.obj", "obj", 1000.0, true))
        be.blob = { never.await(); FixtureBlobDownload.Failed }        // hors ligne : getMetadata relance…
        val gdtf = GdtfOverrides()
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Type3DFetcher(scope, st, be, io = EmptyCoroutineContext)
        // Ordre de la scène : inscription d'abord (immédiate), téléchargements ensuite, sans attendre.
        assertTrue(Type3DFetcher.registerCached(listOf(cached, slow), st, gdtf, EmptyCoroutineContext))
        assertTrue(gdtf.map.containsKey("type3d:rid:77"))
        f.fetchMissing(listOf(cached, slow), "sig") {}
        yield()
        assertTrue(f.isFetching(slow.model3D!!))
        // Modifications du patch (relances de l'effet) : le téléchargement n'est ni annulé ni recommencé.
        repeat(5) {
            assertFalse(Type3DFetcher.registerCached(listOf(cached, slow), st, gdtf, EmptyCoroutineContext))  // rien à reconstruire
            f.fetchMissing(listOf(cached, slow), "sig") {}
            yield()
        }
        assertEquals(1, be.blobCalls.get())
        assertTrue(f.isFetching(slow.model3D!!))
        scope.cancel()
    }

    @Test fun atMostTwoDownloadsAtOnceDedupedBySha() = runBlocking {
        val st = store(); val be = FakeBackend(); val gate = CompletableDeferred<Unit>()
        val payloads = (1..3).map { "objet $it".toByteArray() }
        val bySha = payloads.associateBy { ContentHash.sha256Hex(it) }
        val shas = bySha.keys.toList()
        val types = shas.mapIndexed { i, s ->
            CustomFixtureTypeDTO("t$i", "T$i", "", listOf("Dim"), 1, null, CustomFixtureModel3D.ModelBlob(s, "m$i.obj", "obj", 1000.0, true))
        } + CustomFixtureTypeDTO("dup", "Dup", "", listOf("Dim"), 1, null,
            CustomFixtureModel3D.ModelBlob(shas[0], "autre.obj", "obj", 10.0, false))   // même blob
        val active = AtomicInteger(); var maxActive = 0
        be.blob = { sha ->
            maxActive = maxOf(maxActive, active.incrementAndGet())
            gate.await()
            active.decrementAndGet()
            FixtureBlobDownload.Ok(bySha.getValue(sha))
        }
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Type3DFetcher(scope, st, be, io = EmptyCoroutineContext)
        var arrivals = 0
        f.fetchMissing(types, "sig") { arrivals++ }
        repeat(10) { yield() }
        assertEquals(2, be.blobCalls.get())               // 2 simultanés au plus
        gate.complete(Unit)
        idle(f, *types.map { it.model3D!! }.toTypedArray())
        assertEquals(3, be.blobCalls.get())               // dédoublonné par sha
        assertEquals(2, maxActive)
        assertEquals(3, arrivals)
        assertTrue(shas.all { st.hasBlob(it) })
        scope.cancel()
    }

    @Test fun blobOutcomesAndCorruption() = runBlocking {
        val st = store(); val be = FakeBackend()
        val sha = "c".repeat(64)
        val t = CustomFixtureTypeDTO("c", "C", "", listOf("Dim"), 1, null, CustomFixtureModel3D.GdtfBlob(sha, "x.gdtf"))
        val scope = CoroutineScope(coroutineContext + Job())
        val f = Type3DFetcher(scope, st, be, io = EmptyCoroutineContext)
        // Projet non partagé : rien à télécharger, « Fichier 3D absent du projet partagé. », rien de retenu.
        be.shared = false
        f.fetchMissing(listOf(t), 1) {}
        assertEquals(0, be.blobCalls.get())
        assertEquals(Fixture3DReason.MISSING_IN_PROJECT, st.issues["sha:$sha"])
        assertFalse(f.hasAttempted(t.model3D!!))
        // Octets au SHA faux : jetés, « Fichier 3D corrompu. », pas de nouvel essai en boucle.
        be.shared = true
        be.blob = { FixtureBlobDownload.Ok("pas le bon".toByteArray()) }
        f.fetchMissing(listOf(t), 2) {}
        idle(f, t.model3D!!)
        assertEquals(Fixture3DReason.CORRUPTED, st.issues["sha:$sha"])
        assertFalse(st.hasBlob(sha))
        f.fetchMissing(listOf(t), 2) { fail("pas de nouvel essai") }
        idle(f, t.model3D!!)
        assertEquals(1, be.blobCalls.get())
        // Format non lu ici (COLLADA) : jamais téléchargé (raison « Format non lu… »).
        val dae = CustomFixtureTypeDTO("d", "D", "", listOf("Dim"), 1, null,
            CustomFixtureModel3D.ModelBlob("d".repeat(64), "x.dae", "dae", 25.4, false))
        f.fetchMissing(listOf(dae), 3) {}
        idle(f, dae.model3D!!)
        assertEquals(1, be.blobCalls.get())
        scope.cancel()
    }

    @Test fun loginFailuresAreClassifiedAndCancellationIsNeverAFailure() {
        val credentials = GdtfShareClient.ShareException(com.minou.mvrviewer.R.string.gdtf_err_credentials_fmt, "Wrong password")
        assertEquals(LoginOutcome.REJECTED, GdtfShareClient.classifyLoginFailure(credentials))
        assertEquals(LoginOutcome.REJECTED, GdtfShareClient.classifyLoginFailure(
            GdtfShareClient.ShareException(com.minou.mvrviewer.R.string.gdtf_err_credentials_code_fmt, 401)))
        assertEquals(LoginOutcome.NETWORK, GdtfShareClient.classifyLoginFailure(
            GdtfShareClient.ShareException(com.minou.mvrviewer.R.string.gdtf_err_credentials_code_fmt, 503)))
        assertEquals(LoginOutcome.NETWORK, GdtfShareClient.classifyLoginFailure(
            GdtfShareClient.ShareException(com.minou.mvrviewer.R.string.gdtf_err_unreachable)))
        assertEquals(LoginOutcome.NETWORK, GdtfShareClient.classifyLoginFailure(java.io.IOException("reset")))
        try {
            GdtfShareClient.classifyLoginFailure(CancellationException("effet relancé"))
            fail("l'annulation doit être relancée")
        } catch (_: CancellationException) {}
    }
}
