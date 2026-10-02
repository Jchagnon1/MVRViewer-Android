package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.Mat4
import com.minou.mvrviewer.mvr.MvrObjectKind
import com.minou.mvrviewer.mvr.MvrSceneObject
import com.minou.mvrviewer.sync.ContentHash
import com.minou.mvrviewer.sync.CustomFixtureModel3D
import com.minou.mvrviewer.sync.CustomFixtureProfileDTO
import com.minou.mvrviewer.sync.CustomFixtureTypeDTO
import com.minou.mvrviewer.sync.customFixtureSpec
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Types « profil MA + 3D choisie » côté résolution (SPEC-ma.md §3.7, §4.3, §4.6) :
 * trous gardés à l'enregistrement, cache 3D (SHA vérifié, clés de rendu), priorité de la
 * géométrie (model3D disponible > géométrie propre > base > GDTF d'origine).
 */
class MaTypeResolutionTest {

    @Before fun pro() = ProAccess.forceForTests(true)
    @After fun reset() = ProAccess.forceForTests(null)

    private fun fixture(uuid: String, spec: String?) = MvrSceneObject(
        uuid, "F $uuid", MvrObjectKind.FIXTURE, Mat4(), emptyList(), spec, null, "1", listOf("1.001"), "Calque"
    )

    private fun store() = Fixture3DStore(Files.createTempDirectory("f3d").toFile().apply { deleteOnExit() })

    @Test fun savingAProfileTypeKeepsMiddleHolesOnly() {
        assertEquals(listOf("Pan", "", "", "Dim"), keepHoles(listOf(" Pan ", " ", "", "Dim", "", "  ")))
        assertEquals(emptyList<String>(), keepHoles(listOf("", " ")))
    }

    @Test fun cacheVerifiesShaAndExposesRenderKeys() {
        val st = store()
        val bytes = "hello".toByteArray()
        val sha = ContentHash.sha256Hex(bytes)
        // Téléchargement au SHA faux : jeté, raison « Fichier 3D corrompu. ».
        assertFalse(st.putVerifiedBlob("0".repeat(64), bytes))
        assertEquals(Fixture3DReason.CORRUPTED, st.issues["sha:" + "0".repeat(64)])
        assertFalse(st.hasBlob("0".repeat(64)))
        assertTrue(st.putVerifiedBlob(sha.uppercase(), bytes))
        assertTrue(st.hasBlob(sha))
        assertEquals("type3d:sha:$sha", Fixture3DStore.renderKey(CustomFixtureModel3D.GdtfBlob(sha, "x.gdtf")))
        assertEquals("type3d:rid:4821", Fixture3DStore.renderKey(CustomFixtureModel3D.GdtfShare(4821)))
        assertNull(Fixture3DStore.renderKey(CustomFixtureModel3D.ModelBlob(sha, "a.obj", "obj", 1000.0, true)))
        // Un objet COLLADA présent au cache reste INDISPONIBLE sur Android (format non lu).
        val dae = CustomFixtureModel3D.ModelBlob(sha, "b.dae", "dae", 25.4, false)
        assertFalse(st.isAvailable(dae))
        val t = CustomFixtureTypeDTO("t", "T", "", listOf("Dim"), 1, null, dae)
        val u = st.unavailability(t, hasOrigin = true, shareSignedIn = true)!!
        assertEquals(Fixture3DReason.FORMAT_UNSUPPORTED, u.reason)
        assertEquals("COLLADA", u.formatLabel)
        assertTrue(u.originShown)
        assertTrue(st.isAvailable(CustomFixtureModel3D.ModelBlob(sha, "a.obj", "obj", 1000.0, true)))
    }

    @Test fun geometryPriority() {
        val st = store()
        val overrides = PatchOverrides()
        val resolver = CustomFixtureResolver()
        val gdtf = GdtfOverrides()
        val objBytes = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n".toByteArray()
        val objSha = st.putBlob(objBytes)
        val profile = CustomFixtureProfileDTO("ma2", "M", "F", "Mode", "f.xml")
        val tModel = CustomFixtureTypeDTO("m", "Obj", "", listOf("Dim"), 1, profile,
            CustomFixtureModel3D.ModelBlob(objSha, "a.obj", "obj", 1000.0, true))
        val tShare = CustomFixtureTypeDTO("s", "Share", "", listOf("Dim"), 1, profile, CustomFixtureModel3D.GdtfShare(77))
        val tNone = CustomFixtureTypeDTO("n", "None", "", listOf("Dim"), 1, profile, null)
        val tBase = CustomFixtureTypeDTO("b", "Base", "Robe@Robin.gdtf", listOf("Dim"), 1, null, null)
        resolver.setLibrary(listOf(tModel, tShare, tNone, tBase))
        val fModel = fixture("1", "Orig@A.gdtf")
        val fShare = fixture("2", "Orig@B.gdtf")
        val fNone = fixture("3", "Orig@C.gdtf")
        val fBase = fixture("4", "Orig@D.gdtf")
        overrides.assignSpec(fModel, customFixtureSpec("m"))
        overrides.assignSpec(fShare, customFixtureSpec("s"))
        overrides.assignSpec(fNone, customFixtureSpec("n"))
        overrides.assignSpec(fBase, customFixtureSpec("b"))
        // (d) objet en cache et lu par Android → objet statique.
        assertTrue(fixture3DSource(fModel, overrides, resolver, st, gdtf) is Fixture3DSource.Model)
        // (b) GDTF Share pas encore inscrit → repli : base vide → GDTF d'ORIGINE (plus « custom:<id> »).
        assertEquals(Fixture3DSource.Gdtf("Orig@B.gdtf"), fixture3DSource(fShare, overrides, resolver, st, gdtf))
        gdtf.map["type3d:rid:77"] = byteArrayOf(1)
        assertEquals(Fixture3DSource.Gdtf("type3d:rid:77"), fixture3DSource(fShare, overrides, resolver, st, gdtf))
        // (e) sans 3D, base vide → GDTF d'origine ; base renseignée → la base.
        assertEquals(Fixture3DSource.Gdtf("Orig@C.gdtf"), fixture3DSource(fNone, overrides, resolver, st, gdtf))
        assertEquals(Fixture3DSource.Gdtf("Robe@Robin.gdtf"), fixture3DSource(fBase, overrides, resolver, st, gdtf))
        // Le câblage garde la règle base / origine (model3D ignoré).
        assertEquals("Orig@A.gdtf", effectiveGeometrySpec(fModel, overrides, resolver))
        // Les clés de rendu ne sont JAMAIS persistées avec le projet.
        gdtf.set("Orig@B.gdtf", byteArrayOf(2))
        assertEquals(setOf("Orig@B.gdtf"), gdtf.persistable().keys)
    }

    private fun ply(order: java.nio.ByteOrder): ByteArray {
        val fmt = if (order == java.nio.ByteOrder.BIG_ENDIAN) "binary_big_endian" else "binary_little_endian"
        val header = "ply\nformat $fmt 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n" +
            "element face 1\nproperty list uchar int vertex_indices\nend_header\n"
        val body = java.nio.ByteBuffer.allocate(3 * 12 + 1 + 12).order(order)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { body.putFloat(it) }
        body.put(3.toByte()); body.putInt(0); body.putInt(1); body.putInt(2)
        return header.toByteArray(Charsets.US_ASCII) + body.array()
    }

    /**
     * RÉGRESSION (§4.6 « disponible = octets présents, DÉCODABLES, format lu ») : un PLY
     * big-endian (lu par le web) est en cache et d'un format « lu par Android », mais le
     * lecteur Android ne sait pas le décoder. Avant : le projecteur perdait sa géométrie
     * propre ET son GDTF d'origine (cube, voire invisible), sans statut. Maintenant : il
     * retombe sur la règle actuelle et le type affiche « 3D indisponible » + raison.
     */
    @Test fun undecodableObjectFallsBackToTheCurrentRuleWithAStatus() = kotlinx.coroutines.runBlocking {
        val st = store()
        val overrides = PatchOverrides()
        val resolver = CustomFixtureResolver()
        val gdtf = GdtfOverrides()
        val profile = CustomFixtureProfileDTO("ma2", "M", "F", "Mode", "f.xml")
        val beSha = st.putBlob(ply(java.nio.ByteOrder.BIG_ENDIAN))
        val leSha = st.putBlob(ply(java.nio.ByteOrder.LITTLE_ENDIAN))
        val tBad = CustomFixtureTypeDTO("bad", "Bad", "", listOf("Dim"), 1, profile,
            CustomFixtureModel3D.ModelBlob(beSha, "be.ply", "ply", 1000.0, true))
        val tGood = CustomFixtureTypeDTO("good", "Good", "", listOf("Dim"), 1, profile,
            CustomFixtureModel3D.ModelBlob(leSha, "le.ply", "ply", 1000.0, true))
        resolver.setLibrary(listOf(tBad, tGood))
        val f1 = fixture("1", "Orig@A.gdtf"); val f2 = fixture("2", "Orig@A.gdtf"); val f3 = fixture("3", "Orig@B.gdtf")
        overrides.assignSpec(f1, customFixtureSpec("bad"))
        overrides.assignSpec(f2, customFixtureSpec("bad"))
        overrides.assignSpec(f3, customFixtureSpec("good"))
        // Avant décodage, les deux semblent « disponibles » (format lu + octets en cache).
        assertTrue(st.isAvailable(tBad.model3D!!))
        val prepared = ArrayList<Pair<String, Int>>()
        val sources = fixture3DSourcesChecked(listOf(f1, f2, f3), overrides, resolver, st, gdtf) { key, m, count ->
            prepared += key to count
            val res = com.minou.mvrviewer.mvr.Fixture3DModel.decode(st.readBlob(m.sha256)!!, m.format)
            if (res is com.minou.mvrviewer.mvr.Fixture3DModel.DecodeResult.Ok) null
            else Fixture3DStore.reasonForDecodeFailure(res)
        }
        // Décodé UNE fois par groupe (2 projecteurs « bad » = 1 décodage).
        assertEquals(listOf(fixtureModelGroupKey(tBad.model3D as CustomFixtureModel3D.ModelBlob) to 2,
            fixtureModelGroupKey(tGood.model3D as CustomFixtureModel3D.ModelBlob) to 1), prepared)
        // Repli : GDTF d'origine (le type n'a pas de base) — plus de cube ni de projecteur invisible.
        assertEquals(Fixture3DSource.Gdtf("Orig@A.gdtf"), sources[0])
        assertEquals(Fixture3DSource.Gdtf("Orig@A.gdtf"), sources[1])
        assertTrue(sources[2] is Fixture3DSource.Model)
        // Statut §4.5 : « 3D indisponible — modèle d'origine affiché » + « Format non lu… (PLY). »
        assertFalse(st.isAvailable(tBad.model3D!!))
        val u = st.unavailability(tBad, hasOrigin = true, shareSignedIn = false)!!
        assertTrue(u.originShown)
        assertEquals(Fixture3DReason.FORMAT_UNSUPPORTED, u.reason)
        assertEquals("PLY", u.formatLabel)
        // Mémorisé : la reconstruction suivante ne redécode pas l'objet refusé.
        prepared.clear()
        val again = fixture3DSourcesChecked(listOf(f1, f2, f3), overrides, resolver, st, gdtf) { key, _, count ->
            prepared += key to count; null
        }
        assertEquals(listOf(fixtureModelGroupKey(tGood.model3D as CustomFixtureModel3D.ModelBlob) to 1), prepared)
        assertEquals(sources, again)
        // Un blob présent mais non décodable n'est PAS retéléchargé (le problème n'est pas l'absence).
        assertTrue(st.hasBytes(tBad.model3D!!))
    }

    @Test fun staleSignInReasonDisappearsOnceSignedIn() {
        val st = store()
        val t = CustomFixtureTypeDTO("s", "S", "", listOf("Dim"), 1, null, CustomFixtureModel3D.GdtfShare(4821))
        st.markShareIssue(4821, Fixture3DReason.SIGN_IN_SHARE)
        assertEquals(Fixture3DReason.SIGN_IN_SHARE, st.unavailability(t, true, shareSignedIn = false)!!.reason)
        // Identifiants enregistrés mais REFUSÉS : la raison reste (on n'est pas connecté).
        assertEquals(Fixture3DReason.SIGN_IN_SHARE,
            st.unavailability(t, true, shareSignedIn = false, shareCredentialsStored = true)!!.reason)
        // Connecté (écran GDTF Share) : plus de « Connectez-vous… » (téléchargement en cours).
        assertNull(st.unavailability(t, true, shareSignedIn = true)!!.reason)
        st.clearShareSignInIssues()
        assertNull(st.issues["type3d:rid:4821"])
        // Une vraie panne réseau, elle, reste affichée.
        st.markShareIssue(4821, Fixture3DReason.DOWNLOAD_FAILED)
        st.clearShareSignInIssues()
        assertEquals(Fixture3DReason.DOWNLOAD_FAILED, st.unavailability(t, true, shareSignedIn = true)!!.reason)
    }

    /**
     * RÉGRESSION (§4.3 « un échec ne bloque NI l'enregistrement NI le push ») : l'affectation
     * attendait la fin de l'envoi de la 3D (jusqu'à 20 min hors ligne) et était PERDUE si
     * l'utilisateur quittait l'écran pendant « Envoi de la 3D… ».
     */
    @Test fun savingAssignsBeforeWaitingForTheUpload() = kotlinx.coroutines.runBlocking {
        val order = ArrayList<String>()
        val uploading = kotlinx.coroutines.CompletableDeferred<Unit>()
        val save = launch {
            saveMaTypeOrdered(
                register = { order += "register" },
                assign = { order += "assign" },
                awaitUpload = { order += "upload"; uploading.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            )
        }
        uploading.await()
        save.cancel()     // l'utilisateur revient à la scène pendant l'envoi
        save.join()
        assertEquals(listOf("register", "assign", "upload"), order)
        // Résultat (note §5.7) : rien à envoyer / envoyé → pas de note ; échec ou délai → note.
        assertTrue(saveMaTypeOrdered({}, null) { null })
        assertTrue(saveMaTypeOrdered({}, null) { true })
        assertFalse(saveMaTypeOrdered({}, null) { false })
    }

    /**
     * RÉGRESSION (§4.1 (e)) : « Changer la 3D… » → « Aucune (formes génériques) » sur un type
     * ANCIEN (sans profil) gardait sa base — le choix n'avait aucun effet.
     */
    @Test fun choosingNoneClearsTheBaseOfALegacyType() {
        val legacy = CustomFixtureTypeDTO("l", "Sharpy pan/tilt", "Clay Paky@Sharpy.gdtf", listOf("Pan", "Tilt"), 2)
        val start = editor3DInitial(legacy, "Orig@A.gdtf")
        assertEquals("Clay Paky@Sharpy.gdtf", start.baseSpec)
        val none = editor3DAfterChoice(Chosen3D("", null))
        assertNull(none.baseSpec)
        assertNull(none.model3D)
        assertTrue(none.explicitNone)
        // « Enregistrer » reste permis sans base, et le type enregistré n'a plus de base.
        assertTrue(canSaveCustomType("Sharpy pan/tilt", none, hasProfile = false))
        val saved = legacy.copy(baseGdtfSpec = none.baseSpec.orEmpty(), model3D = none.model3D)
        assertEquals("", saved.baseGdtfSpec)
        // Rouvert : il reste « Aucune » (pas de pré-remplissage par le GDTF du projecteur).
        val reopened = editor3DInitial(saved, "Orig@A.gdtf")
        assertNull(reopened.baseSpec)
        assertTrue(reopened.explicitNone)
        // Les autres choix : (a) base ; (b) 3D sans base ; nouveau type pré-rempli ; rien → refus.
        assertEquals("Robe@Robin.gdtf", editor3DAfterChoice(Chosen3D("Robe@Robin.gdtf", null)).baseSpec)
        val share = editor3DAfterChoice(Chosen3D("", CustomFixtureModel3D.GdtfShare(9)))
        assertNull(share.baseSpec); assertFalse(share.explicitNone)
        assertEquals("Orig@A.gdtf", editor3DInitial(null, "Orig@A.gdtf").baseSpec)
        assertFalse(canSaveCustomType("X", Editor3DState(null, null, false), hasProfile = false))
        assertFalse(canSaveCustomType(" ", none, hasProfile = false))
    }

    @Test fun batchAssignmentCommitsOnce() {
        val overrides = PatchOverrides()
        var batches = 0; var singles = 0
        overrides.onCommit = { _, _, _ -> singles++ }
        overrides.onCommitMany = { changes -> batches++; assertEquals(3, changes.size) }
        val list = listOf(fixture("a", "X"), fixture("b", "X"), fixture("c", "X"))
        overrides.assignSpecToAll(list, customFixtureSpec("t"))
        assertEquals(1, batches); assertEquals(0, singles)
        assertTrue(list.all { overrides.effectiveSpec(it) == "custom:t" })
        // Gratuit : la garde « modifier le patch » refuse, rien n'est écrit.
        ProAccess.forceForTests(false)
        overrides.assignSpecToAll(list, null)
        assertTrue(list.all { overrides.effectiveSpec(it) == "custom:t" })
    }
}
