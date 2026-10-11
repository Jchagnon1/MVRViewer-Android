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

    // ==== SPEC-ma-gdtfshare.md : téléchargements GDTF Share (T6-T10), archives (T11, T18) ====

    private val fr = TestRes.FR
    private fun line(name: String, e: MaImportError): String =
        fr.get("ma_error_line", name, maImportErrorRes(e).let { (r, a) -> fr.res(r, a) })

    private fun tap(store: MaLibraryStore, b: MockShareBackend, rid: Int) = kotlinx.coroutines.runBlocking {
        MaShareFlow.tap(b.entry(rid), store, b, inLibrary = { store.entryForShareRid(it) },
            io = kotlin.coroutines.EmptyCoroutineContext)
    }

    /** T6 — rid 12345 sur bibliothèque vide : une entrée GDTF à 3D, clé `share`, modes du FICHIER. */
    @Test fun downloadAddsEntryWithShareKey() {
        val (store, dir) = tempStore()
        val b = MockShareBackend()
        val (res, lib) = tap(store, b, 12345)
        assertTrue(res is MaShareTapResult.Added)
        val e = lib!!.single()
        assertEquals((res as MaShareTapResult.Added).id, e.id)
        assertEquals("gdtf", e.format)
        assertTrue(e.hasModel3D)
        assertTrue(store.gdtfFile(e.id).isFile)                // octets gardés (vrai modèle 3D)
        assertEquals(MaShareInfo(12345, "AAAAAAAA-0001-4C7C-ACA2-AAAAAAAAAAAA", "V1.6 - DMX 39ch", "manufacturer", 1690000000L), e.share)
        assertEquals("MVR Viewer Tests@Test Wash GDTF@rid12345.gdtf", e.fileName)
        // Les modes affichés sont ceux LUS dans le fichier (7 canaux), pas ceux de la liste (34/39).
        assertEquals(listOf("Mode 1 16 bit"), e.modes.map { it.name })
        assertEquals(7, e.modes.single().footprint)
        assertEquals(e, MaLibraryStore(dir).load().single())   // persisté, relu à l'identique
    }

    /** T7 — retoucher rid 12345 : AUCUN appel réseau, « Dans la bibliothèque ». */
    @Test fun secondTapOpensWithoutNetwork() {
        val (store, _) = tempStore()
        val b = MockShareBackend()
        val first = tap(store, b, 12345).first as MaShareTapResult.Added
        val (again, lib) = tap(store, b, 12345)
        assertEquals(MaShareTapResult.InLibrary(first.id), again)
        assertEquals(null, lib)
        assertEquals(listOf(12345), b.downloads)
    }

    /** T8 — fichier importé avant à la main, puis rid 901 (mêmes octets) : il reçoit `share`, rien d'autre ne change. */
    @Test fun downloadOfAlreadyImportedFileAttachesShare() {
        val (store, _) = tempStore()
        val wash = FileImportSource(fixtures("synthetic").resolve("gdtf_test_wash.gdtf"))
        val before = store.import(listOf(wash), 0, now = { 1000.0 }).second.single()
        assertEquals(null, before.share)
        val b = MockShareBackend()
        val (res, lib) = tap(store, b, 901)
        assertEquals(MaShareTapResult.AlreadyPresent(before.id), res)
        val after = lib!!.single()
        assertEquals(901, after.share?.rid)
        assertEquals("EEEE", after.share?.uuid)
        assertEquals(before.copy(share = after.share), after)  // fileName, importedAt… inchangés
        assertEquals("gdtf_test_wash.gdtf", after.fileName)
        assertEquals(1000.0, after.importedAt, 0.0)
        assertEquals(after.id, store.entryForShareRid(901)?.id) // la ligne 901 passe « Dans la bibliothèque »
        // Mêmes octets sous un AUTRE rid (cas rare) : rien ne change.
        val again = store.importShareRevision(b.entry(12346), fixtures("synthetic").resolve("gdtf_test_wash.gdtf").readBytes())
        assertEquals(MaShareImportOutcome.AlreadyPresent(before.id), again.first)
        assertEquals(901, store.load().single().share?.rid)
    }

    /** T9 — rid 12346 (pas un GDTF lisible) : « Non ajouté : … » ; rid 777 (panne) : « Téléchargement impossible. » */
    @Test fun failedDownloadsAddNothing() {
        val (store, _) = tempStore()
        val b = MockShareBackend()
        val (r1, _) = tap(store, b, 12346)
        assertEquals(MaShareTapResult.NotAdded(MaImportError.Unknown), r1)
        assertEquals("Non ajouté : Ce fichier n'est pas un profil de projecteur MA ni un GDTF.",
            fr.get("ma_share_not_added", maImportErrorRes(MaImportError.Unknown).let { (r, a) -> fr.res(r, a) }))
        val (r2, _) = tap(store, b, 777)
        assertEquals(MaShareTapResult.DownloadFailed, r2)
        assertEquals("Téléchargement impossible. Réessayez.", fr.get("ma_share_download_failed"))
        assertTrue(store.load().isEmpty())
        // 778 (70 Mo annoncés) : jamais demandé.
        assertEquals(MaShareTapResult.TooLarge(67), tap(store, b, 778).first)
        assertEquals(listOf(12346, 777), b.downloads)
    }

    /** T16 (téléchargement) — 401 : UNE reconnexion puis UNE nouvelle tentative ; refus → connexion. */
    @Test fun downloadReloginsOnExpiredSession() {
        val (store, _) = tempStore()
        val b = MockShareBackend().apply { expiredDownloads = 1 }
        assertTrue(tap(store, b, 12345).first is MaShareTapResult.Added)
        assertEquals(listOf(12345, 12345), b.downloads)
        assertEquals(1, b.reloginCalls)
        val rejected = MockShareBackend().apply { expiredDownloads = 1; reloginOutcome = com.minou.mvrviewer.mvr.GdtfShareClient.LoginOutcome.REJECTED }
        assertEquals(MaShareTapResult.NeedLogin, tap(tempStore().first, rejected, 901).first)
        assertEquals(listOf(901), rejected.downloads)
    }

    /** T10 — décodage TOLÉRANT de `share` : une clé invalide ne fait jamais perdre l'entrée. */
    @Test fun shareKeyDecodingIsTolerant() {
        val (_, dir) = tempStore()
        fun entry(id: String, share: String?) =
            """{"id":"$id","format":"gdtf","manufacturer":"M","fixture":"F","shortName":"","author":"",""" +
                """"modes":[{"name":"m","channels":["Dim"],"footprint":1}],"fileName":"f.gdtf","fileSize":1,""" +
                """"importedAt":1.0,"hasModel3D":false""" + (share?.let { ""","share":$it""" } ?: "") + "}"
        File(dir, "library.json").writeText(
            """{"version":1,"profiles":[""" + listOf(
                entry("a", """{"rid":"12"}"""),
                entry("b", """{"rid":0}"""),
                entry("c", "\"x\""),
                entry("d", null),
                entry("e", """{"rid":5.0,"uuid":3,"revision":null,"uploader":"admin","lastModified":-1}"""),
                entry("f", """{"rid":7,"uuid":"U","revision":"R","uploader":"user","lastModified":1700000000}""")
            ).joinToString(",") + "]}"
        )
        val lib = MaLibraryStore(dir).load()
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), lib.map { it.id })
        assertEquals(MaShareInfo(12, "", "", "", 0L), lib[0].share)
        assertEquals(null, lib[1].share)
        assertEquals(null, lib[2].share)
        assertEquals(null, lib[3].share)
        assertEquals(MaShareInfo(5, "", "", "", 0L), lib[4].share)
        assertEquals(MaShareInfo(7, "U", "R", "user", 1700000000L), lib[5].share)
        // Bibliothèque mêlant entrées avec et sans `share` : réécrite puis relue sans perte.
        val store = MaLibraryStore(dir)
        store.save(lib)
        assertEquals(lib, MaLibraryStore(dir).load())
    }

    private fun zipExpected() = org.json.JSONObject(fixtures("zips").resolve("EXPECTED.json").readText())

    private fun importZip(store: MaLibraryStore, name: String): Triple<MaImportReport, List<MaLibraryEntry>, Pair<Int, Int>> {
        var last = 0 to 0
        val (report, lib) = store.import(MaLibraryStore.planArchiveFile(fixtures("zips").resolve(name)), onProgress = { d, t -> last = d to t })
        return Triple(report, lib, last)
    }

    /** T11 + T18 — `ma_profiles.zip` : bilan EXACT, noms « archive › chemin », total de progression 17. */
    @Test fun importProfilesArchive() {
        val (store, _) = tempStore()
        val exp = zipExpected().getJSONObject("ma_profiles.zip")
        val (r, lib, progress) = importZip(store, "ma_profiles.zip")
        assertEquals(exp.getInt("added"), r.added)
        assertEquals(exp.getInt("duplicates"), r.duplicates)
        assertEquals(exp.getInt("ignored"), r.ignored)
        assertEquals(exp.getInt("errors"), r.errors.size)
        assertEquals(exp.getInt("protected"), r.protectedCount)
        assertEquals(17 to 17, progress)
        assertEquals(9, lib.size)
        val names = exp.getJSONObject("errorNames")
        val byCode = r.errors.groupBy({ it.second }, { it.first })
        fun set(k: String) = names.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        assertEquals(set("unreadable"), byCode.getValue(MaImportError.Unreadable).toSet())
        assertEquals(set("unknown"), byCode.getValue(MaImportError.Unknown).toSet())
        assertEquals(set("empty"), byCode.getValue(MaImportError.Empty).toSet())
        assertEquals(set("protected"), byCode.getValue(MaImportError.Protected).toSet())
        assertEquals("ma_profiles.zip › synthetic/corrupt.xml — Fichier illisible ou corrompu.",
            line("ma_profiles.zip › synthetic/corrupt.xml", MaImportError.Unreadable))
        // Nom de l'entrée de bibliothèque = nom de BASE ; taille = taille décompressée.
        val dimmer = lib.single { it.fileName == "ma2_dimmer.xml" }
        assertEquals(fixtures("synthetic").resolve("ma2_dimmer.xml").length(), dimmer.fileSize)
        // Bilan : « dont 1 profil protégé (.pxml) ».
        assertEquals("dont 1 profil protégé (.pxml)", fr.plural("ma_protected_count", r.protectedCount, r.protectedCount))

        // Une 2ᵉ fois : tout « déjà présent ».
        val exp2 = zipExpected().getJSONObject("ma_profiles.zip#2")
        val (r2, lib2, _) = importZip(store, "ma_profiles.zip")
        assertEquals(exp2.getInt("added"), r2.added)
        assertEquals(exp2.getInt("duplicates"), r2.duplicates)
        assertEquals(exp2.getInt("ignored"), r2.ignored)
        assertEquals(exp2.getInt("errors"), r2.errors.size)
        assertEquals(lib, lib2)
    }

    /** T11 + T18 — archives sans profil, corrompue, entrée trop grosse : une erreur nommée chacune. */
    @Test fun importBrokenArchives() {
        val all = zipExpected()
        for (name in listOf("no_profiles.zip", "corrupt.zip", "huge_entry.zip")) {
            val (store, _) = tempStore()
            val exp = all.getJSONObject(name)
            val (r, lib, _) = importZip(store, name)
            assertEquals(name, exp.getInt("added"), r.added)
            assertEquals(name, exp.getInt("duplicates"), r.duplicates)
            assertEquals(name, exp.getInt("ignored"), r.ignored)
            assertEquals(name, exp.getInt("errors"), r.errors.size)
            assertEquals(name, 0, r.protectedCount)              // pas de ligne « dont N protégés »
            assertTrue(lib.isEmpty())
            val lines = exp.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(name, lines, r.errors.map { (n, e) -> line(n, e) })
        }
    }

    /** Une `.zip` trouvée dans un DOSSIER est développée (profondeur 0), à sa place. */
    @Test fun archiveInsideFolderIsExpanded() {
        val dir = Files.createTempDirectory("mazipdir").toFile()
        fixtures("zips").resolve("ma_profiles.zip").copyTo(File(dir, "sub/profils.ZIP"))
        fixtures("synthetic").resolve("ma2_break2.xml").copyTo(File(dir, "a.xml"))
        File(dir, "notes.txt").writeText("x")
        val plan = MaLibraryStore.collect(dir)
        assertEquals(18, plan.sources.size)                     // 1 + 17 candidats de l'archive
        assertEquals(1 + 3, plan.ignored)
        val (store, _) = tempStore()
        val (r, _) = store.import(plan)
        assertEquals(9, r.added)                                // ma2_break2 du dossier = celui de l'archive
        assertEquals(2, r.duplicates)
        assertTrue(r.errors.all { it.first.startsWith("profils.ZIP › ") })
    }

    /** T15 — gratuit : télécharger et importer une archive n'ouvrent JAMAIS la fenêtre Pro. */
    @Test fun freeUserDownloadsAndImportsWithoutPaywall() {
        ProAccess.forceForTests(false)
        try {
            ProAccess.showPaywall = false
            val (store, _) = tempStore()
            assertTrue(tap(store, MockShareBackend(), 12345).first is MaShareTapResult.Added)
            importZip(store, "ma_profiles.zip")
            assertFalse(ProAccess.showPaywall)
            // Créer un type à partir d'un mode reste Pro (garde existante).
            assertFalse(ProAccess.gate("créer un type personnalisé") {})
            assertTrue(ProAccess.showPaywall)
            assertEquals("créer un type personnalisé", ProAccess.paywallReason)
        } finally {
            ProAccess.showPaywall = false
            ProAccess.forceForTests(null)
        }
    }
}
