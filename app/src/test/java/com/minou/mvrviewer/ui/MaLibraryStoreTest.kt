package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Bibliothèque MA locale (SPEC-ma.md §2, plan de tests §6.1/§6.2) — import des dossiers
 * d'essai `real/` + `synthetic/` dans un dossier temporaire JVM : bilan EXACT, second
 * import (tout « déjà présent »), recherche insensible à la casse et aux accents, tri,
 * persistance, suppression, arrêt en cours de lot.
 */
class MaLibraryStoreTest {

    private fun fixtures(name: String) = File(javaClass.classLoader!!.getResource("ma/$name")!!.toURI())

    private fun sources(): Pair<List<MaImportSource>, Int> {
        val (a, ia) = MaLibraryStore.collect(fixtures("real"))
        val (b, ib) = MaLibraryStore.collect(fixtures("synthetic"))
        return (a + b) to (ia + ib)
    }

    private fun tempStore(): Pair<MaLibraryStore, File> {
        val dir = Files.createTempDirectory("malib").toFile()
        dir.deleteOnExit()
        return MaLibraryStore(dir) to dir
    }

    @Test fun importRealAndSyntheticFolders() {
        val (store, dir) = tempStore()
        val (src, ignored) = sources()
        var lastProgress = 0 to 0
        val (report, lib) = store.import(src, ignored, onProgress = { d, t -> lastProgress = d to t })
        // 9 ajoutés · 0 déjà présent · 2 ignorés (les deux EXPECTED.json) · 7 erreurs.
        assertEquals(9, report.added)
        assertEquals(0, report.duplicates)
        assertEquals(2, report.ignored)
        assertEquals(7, report.errors.size)
        assertFalse(report.stopped)
        assertEquals(16 to 16, lastProgress)
        val byCode = report.errors.groupBy({ it.second }, { it.first })
        assertEquals(setOf("corrupt.xml", "corrupt.xmlp"), byCode.getValue(MaImportError.Unreadable).toSet())
        assertEquals(setOf("gdtf_no_description.gdtf", "gma3_macro.xml", "other_root.xml"), byCode.getValue(MaImportError.Unknown).toSet())
        assertEquals(listOf("ma2_only_virtual.xml"), byCode.getValue(MaImportError.Empty))
        assertEquals(listOf("protected.pxml"), byCode.getValue(MaImportError.Protected))
        // Le .xml et le .xmlp de la LED Bar ont des octets différents → 2 entrées.
        assertEquals(2, lib.count { it.fixture == "LED Bar 3 RGB PT" })
        // Les deux Color Strike M (auteurs différents) = 2 profils distincts.
        assertEquals(setOf("Babass", "Created by pplcallmelouis"), lib.filter { it.fixture == "Color Strike M 97 channel" }.map { it.author }.toSet())
        // id = SHA-256 des octets bruts (MANIFEST).
        assertTrue(lib.any { it.id == "85bbc9415ebdd26b8fa34e439cf7c257ffd778a79016e97ccb221e519d55caa5" })
        // Persisté et relu à l'identique.
        assertEquals(lib, store.load())
        assertTrue(File(dir, "library.json").isFile)
        // Seuls les GDTF À VRAI MODÈLE 3D gardent leurs octets (le .gdtf d'essai n'en a pas).
        val wash = lib.single { it.format == "gdtf" }
        assertEquals(wash.hasModel3D, store.gdtfFile(wash.id).isFile)

        // Second import identique : 0 ajouté · 9 déjà présents · 2 ignorés · 7 erreurs.
        val (r2, lib2) = store.import(src, ignored)
        assertEquals(0, r2.added)
        assertEquals(9, r2.duplicates)
        assertEquals(2, r2.ignored)
        assertEquals(7, r2.errors.size)
        assertEquals(lib, lib2)
    }

    @Test fun searchIsCaseAndAccentInsensitive() {
        val (store, _) = tempStore()
        val (src, ignored) = sources()
        val lib = store.import(src, ignored).second
        assertEquals(2, MaSearch.search(lib, "strike").size)
        assertEquals(1, MaSearch.search(lib, "CHAUVET colorado").size)
        val ecl = MaSearch.search(lib, "eclairages")
        assertEquals(1, ecl.size)
        assertEquals("Éclairages Test", ecl.single().manufacturer)
        assertEquals(2, MaSearch.search(lib, "led bar").size)
        assertEquals(0, MaSearch.search(lib, "zzz").size)
        assertEquals(9, MaSearch.search(lib, "   ").size)          // requête vide = tout
        // Tri : fabricant, modèle, auteur, date, id (forme « pliée »).
        val sorted = MaSearch.search(lib, "")
        val keys = sorted.map { MaSearch.fold(it.manufacturer) }
        assertEquals(keys.sorted(), keys)
        assertEquals("chauvet professional", keys.first())
        val strike = MaSearch.search(lib, "strike").map { it.author }
        assertEquals(listOf("Babass", "Created by pplcallmelouis"), strike)
    }

    @Test fun removeKeepsTheRest() {
        val (store, _) = tempStore()
        val (src, ignored) = sources()
        val lib = store.import(src, ignored).second
        val victim = lib.first()
        val rest = store.remove(victim.id)
        assertEquals(8, rest.size)
        assertEquals(rest, store.load())
    }

    /**
     * RÉGRESSION : un import lancé AVANT la fin du chargement paresseux (liste de l'écran
     * encore vide) réécrivait `library.json` avec ses seuls nouveaux profils — toute la
     * bibliothèque était perdue à la réouverture. Le store relit et COMPLÈTE l'index disque.
     */
    @Test fun importNeverDropsProfilesAlreadyOnDisk() {
        val (store, dir) = tempStore()
        val (src, ignored) = sources()
        // Bibliothèque déjà sur le disque (tous les profils sauf le Color Strike de Babass).
        val babass = src.single { it.name.contains("Babass") }
        val before = store.import(src - babass, ignored).second
        assertEquals(8, before.size)
        // Écran rouvert : `MaLibraryState` neuf (vide) ; un import part tout de suite.
        val fresh = MaLibraryStore(dir)
        val (report, lib) = fresh.import(listOf(babass), 0)
        assertEquals(1, report.added)
        assertEquals(9, lib.size)
        assertEquals(before.map { it.id }.toSet() + lib.last().id, lib.map { it.id }.toSet())
        assertEquals(lib, MaLibraryStore(dir).load())               // réouverture : rien de perdu
        // Doublons jugés contre le DISQUE, pas contre la liste (vide) de l'appelant.
        val again = fresh.import(src, ignored).first
        assertEquals(0, again.added)
        assertEquals(9, again.duplicates)
    }

    /**
     * RÉGRESSION : une suppression faite PENDANT un import (liste toujours cliquable) était
     * annulée par le paquet suivant, sauvegardé depuis l'instantané pris au début.
     */
    @Test fun removeDuringImportIsNotUndoneByLaterBatches() {
        val (store, dir) = tempStore()
        val (src, ignored) = sources()
        val initial = store.import(src.take(4), 0).second
        assertTrue(initial.size >= 3)
        val victim = initial.first()
        // 150 profils synthétiques distincts : un paquet persisté à 100, puis la fin.
        val many = (1..150).map { k ->
            val xml = "<MA><FixtureType name=\"Bulk $k\" mode=\"m\"><Modules><Module name=\"B\">" +
                "<ChannelType coarse=\"1\" attribute=\"DIM\"/></Module></Modules>" +
                "<Instances><Instance module_index=\"0\" patch=\"1\"/></Instances></FixtureType></MA>"
            object : MaImportSource {
                override val name = "bulk$k.xml"
                override val size: Long? = null
                override fun read() = xml.toByteArray()
            }
        }
        var removed = false
        val (report, lib) = store.import(many, 0, onBatch = { partial ->
            // Au premier paquet persisté, l'utilisateur supprime un profil depuis sa fiche.
            if (!removed) {
                assertTrue(partial.any { it.id == victim.id })
                store.remove(victim.id); removed = true
            }
        })
        assertTrue(removed)
        assertEquals(150, report.added)
        assertFalse("supprimé puis ressuscité", lib.any { it.id == victim.id })
        assertFalse(MaLibraryStore(dir).load().any { it.id == victim.id })
        assertEquals(initial.size - 1 + 150, MaLibraryStore(dir).load().size)
    }

    @Test fun stopBetweenFilesKeepsWhatWasImported() {
        val (store, _) = tempStore()
        val (src, ignored) = sources()
        var calls = 0
        val (report, lib) = store.import(src, ignored, isCancelled = { calls++ >= 2 })
        assertTrue(report.stopped)
        assertEquals(lib.size, report.added)
        assertTrue(report.added <= 2)
        assertEquals(lib, store.load().takeIf { report.added > 0 } ?: lib)
    }

    @Test fun hiddenAndMacosxAreSkippedSilently() {
        val dir = Files.createTempDirectory("macollect").toFile()
        File(dir, ".hidden.xml").writeText("<MA/>")
        File(dir, "__MACOSX").mkdirs(); File(dir, "__MACOSX/a.xml").writeText("<MA/>")
        File(dir, "sub").mkdirs(); File(dir, "sub/b.XMLP").writeBytes(byteArrayOf(1, 2))
        File(dir, "notes.txt").writeText("x")
        val (src, ignored) = MaLibraryStore.collect(dir)
        assertEquals(listOf("b.XMLP"), src.map { it.name })
        assertEquals(1, ignored)
    }
}
