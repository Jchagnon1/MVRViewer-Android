package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GdtfShareClient
import com.minou.mvrviewer.mvr.GdtfShareEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Recherche GDTF Share de la bibliothèque MA (SPEC-ma-gdtfshare.md §3, tests T3-T5, T16,
 * T17, T20) : filtre, tri, lignes (textes RÉELS de `strings.xml`), nom de fichier, déroulé
 * réseau (reconnexion, hors ligne), catalogue des textes FR/EN, garde-fou « aucune adresse
 * du site de partage de MA ».
 */
class MaShareSearchTest {

    private fun res(path: String) = File(javaClass.classLoader!!.getResource("ma/$path")!!.toURI())
    private val sample: List<GdtfShareEntry> by lazy { GdtfShareClient.parseList(res("share/getList_sample.json").readText()) }
    private val index by lazy { MaShareSearch.Index(sample) }

    // ---- T3 : recherche / filtre / tri (table §3.2) ----

    @Test fun searchTable() {
        fun rids(q: String, fp: Int?, sort: MaShareSort = MaShareSort.MANUFACTURER) = index.search(q, fp, sort).map { it.rid }
        assertEquals(listOf(12345, 12346), rids("robe", null))
        assertEquals(listOf(12345, 12346), rids("ROBE mega", null))
        assertEquals(emptyList<Int>(), rids("mega 39", null))
        assertEquals(listOf(12345, 12346), rids("", 34))
        assertEquals(listOf(777), rids("", 20))
        assertEquals(listOf(777), rids("clay", 16))
        assertEquals(listOf(12345), rids("robe", 39))
        assertEquals(listOf(778), rids("eclairage", null))
        assertEquals(listOf(778, 12345, 12346), rids("e", null, MaShareSort.MANUFACTURER))
        assertEquals(listOf(12345, 778, 12346), rids("e", null, MaShareSort.RECENT))
        assertEquals(listOf(778, 12345, 12346), rids("e", null, MaShareSort.RATING))
        // Requête vide ET pas d'empreinte → aucune ligne (texte d'invite).
        assertEquals(emptyList<Int>(), rids("", null))
        assertEquals(emptyList<Int>(), rids("   ", null))
        assertTrue(index.providesFootprints)
    }

    @Test fun footprintInputAndSortValues() {
        assertEquals("1234", MaShareSearch.normalizeFootprintInput("12a34567"))
        assertEquals("", MaShareSearch.normalizeFootprintInput("abc"))
        assertNull(MaShareSearch.footprintFilter(""))
        assertNull(MaShareSearch.footprintFilter("0"))
        assertNull(MaShareSearch.footprintFilter("0000"))
        assertEquals(39, MaShareSearch.footprintFilter("039"))
        assertEquals(MaShareSort.MANUFACTURER, MaShareSort.fromRaw(null))
        assertEquals(MaShareSort.RECENT, MaShareSort.fromRaw("recent"))
        assertEquals(MaShareSort.RATING, MaShareSort.fromRaw("rating"))
        assertEquals(MaShareSort.MANUFACTURER, MaShareSort.fromRaw("zzz"))
    }

    // ---- T4 : lignes (table §3.3, textes FR réels) ----

    private val fr = TestRes.FR
    private val texts = object : MaShareSearch.Texts {
        override fun revision(name: String) = fr.get("ma_share_revision", name)
        override val revisionUnnamed = fr.get("ma_share_revision_unnamed")
        override fun date(seconds: Long) = "<date de $seconds>"
        override val byManufacturer = fr.get("ma_share_by_manufacturer")
        override val byUser = fr.get("ma_share_by_user")
        override fun rating(value: Double) = fr.get("gdtf_rating_fmt", String.format(fr.locale, "%.1f", value))
        override fun gdtfVersion(v: String) = fr.get("ma_share_gdtf_version", v)
        override fun modes(list: String) = fr.get("ma_share_modes", list)
        override fun modeItem(name: String, footprint: Int) = fr.get("ma_share_mode_item", name, footprint)
        override val modeUnnamed = fr.get("ma_mode_unnamed")
        override fun modesMore(n: Int) = fr.get("ma_share_modes_more", n)
        override val modesNone = fr.get("ma_share_modes_none")
    }

    private fun row(rid: Int, fp: Int? = null) = MaShareSearch.row(sample.single { it.rid == rid }, fp)

    @Test fun rowsTable() {
        row(12345).let {
            assertEquals("Robe Lighting — MegaPointe", it.title)
            assertEquals("Révision : V1.6 - DMX 39ch · <date de 1690000000>", MaShareSearch.line2(it, texts))
            assertEquals("Fabricant · Note : 4,1/5 · GDTF 1.1", MaShareSearch.line3(it, texts))
            assertEquals("Modes : Mode 1 - 34ch (34 ch) · Mode 2 - 39ch (39 ch)", MaShareSearch.line4(it, texts))
            assertEquals(1690000000L, it.dateSeconds)
            assertNull(it.tooLargeMegabytes)
        }
        assertEquals("Modes : Mode 2 - 39ch (39 ch) · Mode 1 - 34ch (34 ch)", MaShareSearch.line4(row(12345, 39), texts))
        row(12346).let {
            assertEquals("Révision sans nom · <date de 1580000000>", MaShareSearch.line2(it, texts))
            assertEquals("Utilisateur · Note : 3,0/5 · GDTF 1.0", MaShareSearch.line3(it, texts))
            assertEquals("Modes : Mode 1 (34 ch)", MaShareSearch.line4(it, texts))
        }
        row(777).let {
            assertEquals("Révision : 2.0", MaShareSearch.line2(it, texts))
            assertNull(MaShareSearch.line3(it, texts))
            assertEquals("Modes : Standard (16 ch) · Vector (20 ch) · Shapes (24 ch) · Basic (12 ch) · +1", MaShareSearch.line4(it, texts))
        }
        assertEquals("Modes : Full (30 ch) · Standard (16 ch) · Vector (20 ch) · Shapes (24 ch) · +1", MaShareSearch.line4(row(777, 30), texts))
        row(778).let {
            assertEquals("Révision : r1 · <date de 1600000000>", MaShareSearch.line2(it, texts))
            assertEquals("Modes : Mode sans nom (1 ch)", MaShareSearch.line4(it, texts))
            assertEquals(67, it.tooLargeMegabytes)
            assertEquals("Fichier trop volumineux (67 Mo, maximum 64 Mo).", fr.get("ma_err_too_large", it.tooLargeMegabytes!!, 64))
        }
        row(901).let {
            assertEquals("Révision sans nom", MaShareSearch.line2(it, texts))
            assertNull(MaShareSearch.line3(it, texts))
            assertEquals("Modes : non indiqués", MaShareSearch.line4(it, texts))
        }
    }

    // ---- T5 : nom de fichier ----

    @Test fun shareFileName() {
        assertEquals("MVR Viewer Tests@Test Wash GDTF@rid901.gdtf", MaShareSearch.fileName("MVR Viewer Tests", "Test Wash GDTF", 901))
        assertEquals("AC_DC_ Lights_", MaShareSearch.sanitize("AC/DC: Lights?"))
        assertEquals("GDTF", MaShareSearch.sanitize("   "))
        assertEquals("GDTF@GDTF@rid1.gdtf", MaShareSearch.fileName("", " ", 1))
        assertEquals("a_b_c_d", MaShareSearch.sanitize("a\u0001b<c|d"))
    }

    // ---- T16 / T17 : déroulé réseau (doublure du client) ----

    private class FakeBackend(
        var internet: Boolean = true,
        var validated: Boolean = true,
        var listFailures: MutableList<Exception> = mutableListOf(),
        var relogin: GdtfShareClient.LoginOutcome = GdtfShareClient.LoginOutcome.OK
    ) : MaShareBackend {
        var listCalls = 0; var reloginCalls = 0
        override suspend fun list(): List<GdtfShareEntry> {
            listCalls++
            if (listFailures.isNotEmpty()) throw listFailures.removeAt(0)
            return listOf(GdtfShareEntry(1, "F", "M", "U", null))
        }
        override suspend fun download(rid: Int): ByteArray = error("pas de téléchargement ici")
        override suspend fun relogin(): GdtfShareClient.LoginOutcome { reloginCalls++; return relogin }
        override fun hasInternet() = internet
        override fun hasValidatedNetwork() = validated
    }

    @Test fun expiredSessionReloginsOnceAndRetriesOnce() = runBlocking {
        val b = FakeBackend(listFailures = mutableListOf(GdtfShareClient.SessionExpiredException()))
        val r = MaShareFlow.loadList(b)
        assertTrue(r is MaShareListResult.Ok)
        assertEquals(2, b.listCalls)
        assertEquals(1, b.reloginCalls)
        // Identifiants refusés → écran de connexion, sans nouvelle tentative.
        val rej = FakeBackend(listFailures = mutableListOf(GdtfShareClient.SessionExpiredException()),
            relogin = GdtfShareClient.LoginOutcome.REJECTED)
        assertTrue(MaShareFlow.loadList(rej) === MaShareListResult.NeedLogin)
        assertEquals(1, rej.listCalls); assertEquals(1, rej.reloginCalls)
        // Pas d'identifiants → connexion.
        val none = FakeBackend(listFailures = mutableListOf(GdtfShareClient.SessionExpiredException()),
            relogin = GdtfShareClient.LoginOutcome.NO_CREDENTIALS)
        assertTrue(MaShareFlow.loadList(none) === MaShareListResult.NeedLogin)
        // Session refusée DEUX fois : connexion (jamais de boucle).
        val twice = FakeBackend(listFailures = mutableListOf(GdtfShareClient.SessionExpiredException(), GdtfShareClient.SessionExpiredException()))
        assertTrue(MaShareFlow.loadList(twice) === MaShareListResult.NeedLogin)
        assertEquals(2, twice.listCalls); assertEquals(1, twice.reloginCalls)
    }

    @Test fun offlineAndErrorStates() = runBlocking {
        // Aucun réseau Internet AVANT l'appel : hors ligne, rien n'est demandé.
        val off = FakeBackend(internet = false)
        assertTrue(MaShareFlow.loadList(off) === MaShareListResult.Offline)
        assertEquals(0, off.listCalls)
        // Hôte introuvable sans réseau validé → hors ligne ; avec réseau validé → erreur.
        val unreachable = { GdtfShareClient.ShareException(R.string.gdtf_err_unreachable) }
        assertTrue(MaShareFlow.loadList(FakeBackend(validated = false, listFailures = mutableListOf(unreachable()))) === MaShareListResult.Offline)
        val err = MaShareFlow.loadList(FakeBackend(validated = true, listFailures = mutableListOf(unreachable())))
        assertTrue(err is MaShareListResult.Error)
        // Le message existant du client sert de 2ᵉ ligne.
        val cause = (err as MaShareListResult.Error).cause as GdtfShareClient.ShareException
        assertEquals("Impossible de joindre gdtf-share.com. Vérifie ta connexion Internet.", fr.res(cause.messageRes, cause.args.toList()))
        val http = MaShareFlow.loadList(FakeBackend(listFailures = mutableListOf(GdtfShareClient.ShareException(R.string.gdtf_err_list_failed_fmt, 500))))
        assertTrue(http is MaShareListResult.Error)
        assertEquals("Hors ligne : la recherche GDTF Share demande une connexion Internet.", fr.get("ma_share_offline"))
        assertEquals("Réessayer", fr.get("ma_share_retry"))
    }

    // ---- Catalogue des textes (§6.3) : FR et EN EXACTS dans res/values et res/values-en ----

    private val catalogue: List<Triple<String, String, String>> = listOf(
        Triple("ma_empty", "Aucun profil MA importé. Importez les profils exportés de votre console, ou trouvez un GDTF avec « Chercher sur GDTF Share ».",
            "No MA profiles yet. Import the profiles exported from your console, or find a GDTF with “Search GDTF Share”."),
        Triple("ma_help", "Exportez vos profils depuis grandMA3 / grandMA3 onPC (.xml ou GDTF) ou grandMA2 onPC (.xml / .xmlp), puis importez-les ici : fichiers, dossier ou .zip.",
            "Export your profiles from grandMA3 / grandMA3 onPC (.xml or GDTF) or grandMA2 onPC (.xml / .xmlp), then import them here: files, a folder or a .zip."),
        Triple("ma_formats", "Formats : .xml (grandMA3 ou grandMA2), .xmlp (grandMA2), .gdtf, et les .zip qui en contiennent",
            "Formats: .xml (grandMA3 or grandMA2), .xmlp (grandMA2), .gdtf, and .zip archives containing them"),
        Triple("ma_share_search", "Chercher sur GDTF Share", "Search GDTF Share"),
        Triple("ma_share_title", "Chercher sur GDTF Share", "Search GDTF Share"),
        Triple("ma_share_intro", "Les GDTF publiés sur GDTF Share (compte gratuit gdtf-share.com). Le fichier choisi est ajouté à votre bibliothèque MA, sur cet appareil.",
            "GDTF files published on GDTF Share (free gdtf-share.com account). The file you pick is added to your MA library, on this device."),
        Triple("ma_share_footprint", "Canaux (empreinte DMX)", "Channels (DMX footprint)"),
        Triple("ma_share_footprint_any", "Tous", "Any"),
        Triple("ma_share_no_footprints", "Empreintes non fournies par GDTF Share pour l'instant : filtre indisponible.", "Footprints not provided by GDTF Share right now: filter unavailable."),
        Triple("ma_share_sort", "Trier", "Sort"),
        Triple("ma_share_sort_manufacturer", "Fabricant", "Manufacturer"),
        Triple("ma_share_sort_recent", "Plus récentes", "Newest"),
        Triple("ma_share_sort_rating", "Mieux notées", "Top rated"),
        Triple("ma_share_hint", "Tapez un fabricant ou un modèle, ou un nombre de canaux.", "Type a manufacturer or a model, or a number of channels."),
        Triple("ma_share_loading", "Chargement de la liste GDTF Share…", "Loading the GDTF Share list…"),
        Triple("ma_share_no_match", "Aucune révision ne correspond à « %1\$s ».", "No revision matches “%1\$s”."),
        Triple("ma_share_no_match_fp", "Aucune révision n'a de mode d'empreinte %1\$d.", "No revision has a mode with a footprint of %1\$d."),
        Triple("ma_share_no_match_both", "Aucune révision ne correspond à « %1\$s » avec un mode d'empreinte %2\$d.", "No revision matches “%1\$s” with a mode with a footprint of %2\$d."),
        Triple("ma_share_offline", "Hors ligne : la recherche GDTF Share demande une connexion Internet.", "Offline: searching GDTF Share requires an Internet connection."),
        Triple("ma_share_error", "La liste GDTF Share n'a pas pu être chargée.", "The GDTF Share list could not be loaded."),
        Triple("ma_share_retry", "Réessayer", "Try again"),
        Triple("ma_share_revision", "Révision : %1\$s", "Revision: %1\$s"),
        Triple("ma_share_revision_unnamed", "Révision sans nom", "Unnamed revision"),
        Triple("ma_share_by_manufacturer", "Fabricant", "Manufacturer"),
        Triple("ma_share_by_user", "Utilisateur", "User"),
        Triple("ma_share_gdtf_version", "GDTF %1\$s", "GDTF %1\$s"),
        Triple("ma_share_modes", "Modes : %1\$s", "Modes: %1\$s"),
        Triple("ma_share_mode_item", "%1\$s (%2\$d ch)", "%1\$s (%2\$d ch)"),
        Triple("ma_share_modes_more", "+%1\$d", "+%1\$d"),
        Triple("ma_share_modes_none", "non indiqués", "not listed"),
        Triple("ma_share_in_library", "Dans la bibliothèque", "In the library"),
        Triple("ma_downloading", "Téléchargement…", "Downloading…"),
        Triple("ma_share_download_failed", "Téléchargement impossible. Réessayez.", "Download failed. Try again."),
        Triple("ma_share_not_added", "Non ajouté : %1\$s", "Not added: %1\$s"),
        Triple("ma_share_added", "Ajouté à la bibliothèque MA.", "Added to the MA library."),
        Triple("ma_share_already", "Déjà dans la bibliothèque MA.", "Already in the MA library."),
        Triple("ma_source_share", "Source : GDTF Share (rid %1\$d)", "Source: GDTF Share (rid %1\$d)"),
        Triple("ma_uploaded_by", "Mise en ligne par : %1\$s", "Uploaded by: %1\$s"),
        Triple("ma_modified", "Modifié le %1\$s", "Modified %1\$s"),
        Triple("ma_3d_recommended", "Recommandé", "Recommended"),
        Triple("ma_err_zip_unreadable", "Archive .zip illisible ou corrompue.", "Unreadable or corrupted .zip archive."),
        Triple("ma_err_zip_empty", "Aucun profil MA ni GDTF dans cette archive.", "No MA profile or GDTF in this archive."),
        Triple("ma_err_zip_limit", "Archive trop grande : lecture arrêtée après %1\$d fichiers.", "Archive too large: reading stopped after %1\$d files."),
        Triple("gdtf_signup", "Pas encore de compte ? Créer un compte gratuit sur gdtf-share.com ↗", "No account yet? Create a free account on gdtf-share.com ↗"),
        Triple("gdtf_reconnecting", "Reconnexion…", "Reconnecting…"),
    )

    @Test fun catalogueTextsAreExactInBothLanguages() {
        for ((key, f, e) in catalogue) {
            assertEquals("FR $key", f, TestRes.FR.strings[key])
            assertEquals("EN $key", e, TestRes.EN.strings[key])
        }
        assertEquals("dont 1 profil protégé (.pxml)", TestRes.FR.plural("ma_protected_count", 1, 1))
        assertEquals("dont 3 profils protégés (.pxml)", TestRes.FR.plural("ma_protected_count", 3, 3))
        assertEquals("including 1 protected profile (.pxml)", TestRes.EN.plural("ma_protected_count", 1, 1))
        assertEquals("including 3 protected profiles (.pxml)", TestRes.EN.plural("ma_protected_count", 3, 3))
        // Textes réutilisés tels quels.
        assertEquals("Fabricant ou modèle", TestRes.FR.strings["ma_search"])
        assertEquals("%1\$d résultats — affinez la recherche (200 affichés).", TestRes.FR.strings["ma_too_many"])
    }

    // ---- T20 : garde-fou (aucune adresse du site de partage de MA dans l'app) ----

    @Test fun noMaSharingSiteAnywhereInTheApp() {
        val root = listOf(File("src"), File("app/src")).first { it.isDirectory }
        val needle = "fixture" + "share"            // jamais écrit d'un bloc, même ici
        val offenders = root.walkTopDown().filter { it.isFile }
            .filterNot { it.invariantSeparatorsPath.contains("/test/resources/ma/real/") }
            .filter { f -> runCatching { f.readText() }.getOrDefault("").contains(needle, ignoreCase = true) }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
        // Catalogues de chaînes : ni l'adresse ni le nom du site.
        val name = "Fixture" + " Share"
        for (lang in listOf("values", "values-en")) {
            val f = File(root, "main/res/$lang/strings.xml")
            assertFalse("$lang/strings.xml", f.readText().contains(name, ignoreCase = true))
        }
        // Le lien d'inscription pointe vers GDTF Share, rien d'autre.
        assertEquals("https://gdtf-share.com/signup", GDTF_SIGNUP_URL)
    }
}
