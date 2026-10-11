package com.minou.mvrviewer.ui

import com.minou.mvrviewer.sync.CustomFixtureModel3D
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Types MA créés depuis une révision GDTF Share (SPEC-ma-gdtfshare.md §4.6, §7, tests
 * T13-T14) : la 3D du fichier est proposée EN PREMIER ; pour une révision GDTF Share elle
 * est encodée `gdtfShare(rid)` (cache par rid rempli au choix, AUCUN blob envoyé) ; un
 * fichier importé garde `gdtfBlob`. Deux révisions = deux `profile.fileName` distincts.
 */
class MaTypeShareTest {

    @Before fun pro() = ProAccess.forceForTests(true)
    @After fun reset() = ProAccess.forceForTests(null)

    private fun fixtures(name: String) = File(javaClass.classLoader!!.getResource("ma/$name")!!.toURI())
    private fun tmp(p: String) = Files.createTempDirectory(p).toFile().apply { deleteOnExit() }

    private fun download(store: MaLibraryStore, rid: Int): MaLibraryEntry = runBlocking {
        val b = MockShareBackend()
        val (res, lib) = MaShareFlow.tap(b.entry(rid), store, b, { store.entryForShareRid(it) }, kotlin.coroutines.EmptyCoroutineContext)
        val id = (res as? MaShareTapResult.Added)?.id ?: (res as MaShareTapResult.AlreadyPresent).id
        lib!!.single { it.id == id }
    }

    /** T13 — profil téléchargé : `gdtfShare(12345)`, octets au cache du rid, aucun blob. */
    @Test fun recommendedOwn3DOfShareProfileIsGdtfShare() {
        val store = MaLibraryStore(tmp("malib"))
        val f3d = Fixture3DStore(tmp("f3d"))
        val entry = download(store, 12345)
        assertTrue(entry.hasModel3D)
        val own = OwnGdtf(entry.fileName, entry.share?.rid) { store.gdtfBytes(entry.id) }
        val chosen = chooseOwnGdtf(own, own.bytes()!!, f3d)
        assertEquals(CustomFixtureModel3D.GdtfShare(12345), chosen.model3D)
        assertEquals("", chosen.baseGdtfSpec)
        assertTrue(f3d.hasShare(12345))
        assertTrue(f3d.readShare(12345)!!.contentEquals(store.gdtfBytes(entry.id)!!))
        assertNull(chosen.model3D?.blobSha)                     // rien à envoyer au Storage du projet
        val type = MaDraft(entry, entry.modes.single(), chosen).buildType("t1", " Wash ")
        assertEquals(CustomFixtureModel3D.GdtfShare(12345), type.model3D)
        assertEquals("", type.baseGdtfSpec)
        assertEquals("Wash", type.name)
        assertEquals("MVR Viewer Tests@Test Wash GDTF@rid12345.gdtf", type.profile?.fileName)
        assertEquals("gdtf", type.profile?.format)
        assertEquals("Mode 1 16 bit", type.profile?.mode)
        assertEquals(entry.modes.single().channels, type.channels)
    }

    /** T13 (suite) — le même fichier importé comme FICHIER : `gdtfBlob` (inchangé). */
    @Test fun recommendedOwn3DOfImportedFileStaysGdtfBlob() {
        val store = MaLibraryStore(tmp("malib"))
        val f3d = Fixture3DStore(tmp("f3d"))
        val src = FileImportSource(fixtures("share").resolve("gdtf_test_wash_3d.gdtf"))
        val entry = store.import(listOf(src), 0).second.single()
        assertTrue(entry.hasModel3D)
        assertNull(entry.share)
        val own = OwnGdtf(entry.fileName, entry.share?.rid) { store.gdtfBytes(entry.id) }
        val chosen = chooseOwnGdtf(own, own.bytes()!!, f3d)
        val m = chosen.model3D as CustomFixtureModel3D.GdtfBlob
        assertEquals("gdtf_test_wash_3d.gdtf", m.fileName)
        assertTrue(f3d.hasBlob(m.blobSha!!))
        assertFalse(f3d.hasShare(12345))
    }

    /** T14 — deux révisions du même projecteur : deux `profile.fileName` → pas de dialogue « Un type existe déjà… ». */
    @Test fun twoRevisionsAreTwoDistinctProfiles() {
        val store = MaLibraryStore(tmp("malib"))
        val a = download(store, 12345)
        val b = download(store, 901)
        assertNotEquals(a.id, b.id)
        assertEquals("MVR Viewer Tests@Test Wash GDTF@rid901.gdtf", b.fileName)
        val pa = MaDraft(a, a.modes.single()).profileDTO()
        val pb = MaDraft(b, b.modes.single()).profileDTO()
        assertNotEquals(pa, pb)
        // Le doublon de TYPE compare `profile` : un type de la révision 12345 n'est pas un doublon pour 901.
        val existing = listOf(MaDraft(a, a.modes.single()).buildType("t", "T"))
        assertNull(existing.firstOrNull { it.profile == pb })
        assertEquals("t", existing.firstOrNull { it.profile == pa }?.id)
    }
}
