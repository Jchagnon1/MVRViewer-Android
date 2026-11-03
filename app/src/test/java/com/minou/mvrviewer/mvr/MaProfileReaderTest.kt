package com.minou.mvrviewer.mvr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Bibliothèque MA — lecteur de profils (SPEC-ma.md §1). Chaque fichier d'essai de
 * `ma/real/` (profils RÉELS MA Share fournis par l'utilisateur) et de `ma/synthetic/`
 * doit produire EXACTEMENT la sortie de l'oracle `ma_reference.py` (`EXPECTED.json`) :
 * format, fabricant, modèle, nom court, auteur, et pour chaque mode nom + canaux (trous
 * compris) + empreinte — ou le code d'erreur.
 */
class MaProfileReaderTest {

    private fun dir(name: String): File {
        val url = javaClass.classLoader!!.getResource("ma/$name") ?: error("ressource ma/$name absente")
        return File(url.toURI())
    }

    private fun profileToJson(r: MaProfileReader.Result): JSONObject = when (r) {
        is MaProfileReader.Result.Error -> JSONObject().put("error", r.code.raw)
        is MaProfileReader.Result.Ok -> JSONObject()
            .put("format", r.profile.format)
            .put("manufacturer", r.profile.manufacturer)
            .put("fixture", r.profile.fixture)
            .put("shortName", r.profile.shortName)
            .put("author", r.profile.author)
            .put("modes", JSONArray().apply {
                r.profile.modes.forEach { m ->
                    put(JSONObject().put("name", m.name).put("channels", JSONArray(m.channels)).put("footprint", m.footprint))
                }
            })
    }

    /** Comparaison STRUCTURELLE (clé par clé, tableaux dans l'ordre), message lisible. */
    private fun assertSame(file: String, expected: JSONObject, actual: JSONObject) {
        if (expected.has("error")) {
            assertEquals("$file : erreur", expected.getString("error"), actual.optString("error", "<profil lu>"))
            return
        }
        assertTrue("$file : erreur inattendue ${actual.optString("error")}", !actual.has("error"))
        for (k in listOf("format", "manufacturer", "fixture", "shortName", "author")) {
            assertEquals("$file : $k", expected.getString(k), actual.getString(k))
        }
        val em = expected.getJSONArray("modes")
        val am = actual.getJSONArray("modes")
        assertEquals("$file : nombre de modes", em.length(), am.length())
        for (i in 0 until em.length()) {
            val e = em.getJSONObject(i); val a = am.getJSONObject(i)
            assertEquals("$file : nom du mode $i", e.getString("name"), a.getString("name"))
            assertEquals("$file : empreinte du mode « ${e.getString("name")} »", e.getInt("footprint"), a.getInt("footprint"))
            val ec = e.getJSONArray("channels"); val ac = a.getJSONArray("channels")
            val el = (0 until ec.length()).map { ec.getString(it) }
            val al = (0 until ac.length()).map { ac.getString(it) }
            assertEquals("$file : canaux du mode « ${e.getString("name")} »", el, al)
        }
    }

    private fun checkFolder(name: String): Int {
        val d = dir(name)
        val expected = JSONObject(File(d, "EXPECTED.json").readText())
        val files = d.listFiles()!!.filter { it.isFile && it.name != "EXPECTED.json" }.map { it.name }.sorted()
        // Chaque fichier du dossier a sa sortie attendue, et inversement : rien n'est oublié.
        assertEquals("$name : fichiers couverts", expected.keys().asSequence().toList().sorted(), files)
        for (f in files) {
            val bytes = File(d, f).readBytes()
            assertSame(f, expected.getJSONObject(f), profileToJson(MaProfileReader.read(bytes, f)))
        }
        return files.size
    }

    @Test fun realFilesMatchOracle() {
        assertEquals(3, checkFolder("real"))
    }

    @Test fun syntheticFilesMatchOracle() {
        assertEquals(13, checkFolder("synthetic"))
    }

    @Test fun realFilesKeyFacts() {
        // Points délicats de l'annexe, vérifiés explicitement (en plus de l'égalité oracle).
        val d = dir("real")
        val pxl = MaProfileReader.read(
            File(d, "Chauvet_Professional@COLORado_PXL_Curve_12@Single_Control__Standard_101Ch@12345.xml").readBytes(),
            "Chauvet_Professional@COLORado_PXL_Curve_12@Single_Control__Standard_101Ch@12345.xml"
        ) as MaProfileReader.Result.Ok
        assertEquals("ma2", pxl.profile.format)
        assertEquals(104, pxl.profile.modes.single().footprint)   // « 101Ch » mais offsets → 104
        assertEquals("12345", pxl.profile.author)
        assertTrue("DIM du Body non préfixé (DIM des têtes virtuels)", "DIM" in pxl.profile.modes.single().channels)
        val strike = MaProfileReader.read(
            File(d, "Chauvet_Professional@Color_Strike_M_97_channel@Created_by_pplcallmelouis.xml").readBytes(),
            "Chauvet_Professional@Color_Strike_M_97_channel@Created_by_pplcallmelouis.xml"
        ) as MaProfileReader.Result.Ok
        assertEquals("ma3", strike.profile.format)
        assertEquals(97, strike.profile.modes.single().footprint)
        assertEquals("Created by pplcallmelouis", strike.profile.author)
        assertEquals("Main Dimmer", strike.profile.modes.single().channels[2])
    }

    @Test fun protectedNeverReadsBytes() {
        // .pxml : refusé sur l'extension seule (casse ignorée), même avec un contenu lisible.
        val r = MaProfileReader.read("<GMA3><FixtureType Name=\"x\"/></GMA3>".toByteArray(), "Foo.PXML")
        assertEquals(MaProfileReader.Result.Error(MaProfileReader.ErrorCode.PROTECTED), r)
    }

    @Test fun doctypeIsRefused() {
        // XML venu d'internet : DOCTYPE refusé (entités externes jamais résolues).
        val xml = """<?xml version="1.0"?><!DOCTYPE MA [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
            """<MA><FixtureType name="&x;" mode="m"><Modules><Module name="B"><ChannelType coarse="1" attribute="DIM"/></Module></Modules>""" +
            """<Instances><Instance module_index="0" patch="1"/></Instances></FixtureType></MA>"""
        val r = MaProfileReader.read(xml.toByteArray(), "evil.xml")
        if (r is MaProfileReader.Result.Ok) fail("un DOCTYPE ne doit pas être lu : ${r.profile.fixture}")
    }

    /**
     * RÉGRESSION (annexe 16) : la fabrique DOM d'ANDROID refuse la fonctionnalité
     * `disallow-doctype-decl` ; le repli « entités non développées » acceptait alors le
     * DOCTYPE et le profil était importé sur le téléphone (nom « &e; ») alors que le test
     * JVM (Xerces) le refusait. Le refus ne doit dépendre ni de l'analyseur ni de la plateforme.
     */
    @Test fun doctypeIsRefusedEvenWhenTheParserCannotRefuseIt() {
        val xml = """<!DOCTYPE MA [<!ENTITY e "x">]><MA><FixtureType name="&e;" mode="m"><Modules><Module name="B">""" +
            """<ChannelType coarse="1" attribute="DIM"/></Module></Modules>""" +
            """<Instances><Instance module_index="0" patch="1"/></Instances></FixtureType></MA>"""
        MaProfileReader.simulateAndroidXmlFactory = true
        try {
            assertEquals(MaProfileReader.Result.Error(MaProfileReader.ErrorCode.UNREADABLE),
                MaProfileReader.read(xml.toByteArray(), "evil.xml"))
            assertEquals(MaProfileReader.Result.Error(MaProfileReader.ErrorCode.UNREADABLE),
                MaProfileReader.read(xml.toByteArray(Charsets.UTF_16), "evil16.xml"))
            // Les profils réels (sans DOCTYPE) restent lus à l'identique.
            val real = dir("real").listFiles()!!.first { it.name.endsWith(".xml") }
            assertTrue(MaProfileReader.read(real.readBytes(), real.name) is MaProfileReader.Result.Ok)
        } finally {
            MaProfileReader.simulateAndroidXmlFactory = false
        }
    }

    @Test fun doctypeScanLooksOnlyAtTheProlog() {
        fun has(s: String, cs: java.nio.charset.Charset = Charsets.UTF_8) = MaProfileReader.hasDoctype(s.toByteArray(cs))
        assertTrue(has("<!DOCTYPE MA><MA/>"))
        assertTrue(has("<?xml version=\"1.0\"?>\n<?xml-stylesheet href=\"x\"?>\n<!-- note -->\n<!doctype MA><MA/>"))
        assertTrue(MaProfileReader.hasDoctype(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<!DOCTYPE MA><MA/>".toByteArray()))
        assertTrue(has("<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE MA><MA/>", Charsets.UTF_16))      // BOM FE FF
        assertTrue(has("<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE MA><MA/>", Charsets.UTF_16LE))    // sans BOM
        // Après la racine, ou dans un commentaire du prologue : ce n'est pas une déclaration.
        assertTrue(!has("<MA><t>&lt;!DOCTYPE MA&gt;</t><!-- <!DOCTYPE x> --></MA>"))
        assertTrue(!has("<!-- <!DOCTYPE MA> --><MA/>"))
        assertTrue(!has("<?xml version=\"1.0\"?><GMA3 DataVersion=\"1\"/>"))
        // Prologue plus long que le préfixe décodé d'abord (commentaire géant).
        val big = "x".repeat(300_000)
        assertTrue(has("<!--$big--><!DOCTYPE MA><MA/>"))
        assertTrue(!has("<!--$big--><MA/>"))
    }

    // ---- Zips écrits EN FLUX (STORED + descripteur de données) ----

    private fun zipdd(name: String): ByteArray =
        File(javaClass.classLoader!!.getResource("zipdd/$name")!!.toURI()).readBytes()

    /**
     * RÉGRESSION : `ZipInputStream` (lecture séquentielle des en-têtes locaux) refuse une
     * entrée STORED avec descripteur de données — zip VALIDE écrit en flux (ici par Python,
     * cf. `zipdd/make_zipdd.py`) — et le .gdtf était « illisible » sur Android seulement.
     * Lecture par le répertoire central, comme l'oracle : gdtf, M / T, Mode1 = ["Dim"].
     */
    @Test fun storedEntriesWithDataDescriptorAreReadLikeTheOracle() {
        // Cause racine documentée : l'ancien chemin lève bien sur ces fichiers.
        val legacy = runCatching {
            java.util.zip.ZipInputStream(zipdd("stored_dd_first.gdtf").inputStream()).use { z ->
                while (z.nextEntry != null) z.readBytes()
            }
        }
        assertTrue("ZipInputStream devrait refuser STORED + descripteur", legacy.isFailure)
        for (n in listOf("stored_dd_first.gdtf", "stored_dd_after.gdtf", "deflated_dd.gdtf")) {
            val bytes = zipdd(n)
            val expected = JSONObject("""{"format":"gdtf","manufacturer":"M","fixture":"T","shortName":"","author":"",""" +
                """"modes":[{"name":"Mode1","channels":["Dim"],"footprint":1}]}""")
            assertEquals(n, expected.toString(), profileToJson(MaProfileReader.read(bytes, n)).toString())
            // « Fichier GDTF… » (choix de la 3D) : même lecteur.
            assertTrue(n, MaProfileReader.isGdtfArchive(bytes))
            // Rendu / puissance / modèle 3D : MvrParser retombe sur le répertoire central.
            assertTrue(n, MvrParser.extractEntry(bytes, "description.xml")?.decodeToString()?.contains("Mode1") == true)
            assertTrue(n, MvrParser.extractEntryIgnoringCase(bytes, "DESCRIPTION.XML") != null)
            assertTrue(n, "description.xml" in MvrParser.listEntries(bytes))
            assertTrue(n, MvrParser.extractEntries(bytes, setOf("description.xml")).containsKey("description.xml"))
        }
        // Les contrôles de `zipfile` restent : CRC faux → illisible (le XML altéré, « T » → « U »,
        // serait pourtant valide : seul le CRC peut le refuser).
        val bad = zipdd("stored_dd_first.gdtf").copyOf()
        val at = String(bad, Charsets.ISO_8859_1).indexOf("Name=\"T\"") + 6
        assertTrue(at > 6)
        bad[at] = 'U'.code.toByte()
        assertEquals(MaProfileReader.Result.Error(MaProfileReader.ErrorCode.UNREADABLE), MaProfileReader.read(bad, "bad.gdtf"))
        // Tronqué (plus de répertoire central) → illisible ; sans description.xml → inconnu.
        val cut = zipdd("stored_dd_first.gdtf").copyOf(100)
        assertEquals(MaProfileReader.Result.Error(MaProfileReader.ErrorCode.UNREADABLE), MaProfileReader.read(cut, "cut.gdtf"))
        assertTrue(!MaProfileReader.isGdtfArchive(cut))
    }

    @Test fun authorConvention() {
        assertEquals("Babass", MaProfileReader.authorOf("Chauvet_Professional@Color_Strike_M_97_channel@Babass.xml"))
        assertEquals("12345", MaProfileReader.authorOf("A@B@Mode@12345.xml"))
        assertEquals("", MaProfileReader.authorOf("A@B.xml"))
        assertEquals("", MaProfileReader.authorOf("plain.xml"))
    }

    @Test fun fixturesMatchManifest() {
        // Copie conforme de MVRViewerWeb/tests/fixtures/ma (ne JAMAIS éditer une copie).
        val root = dir("real").parentFile!!
        val manifest = File(root, "MANIFEST.sha256").readLines().filter { it.isNotBlank() }
        assertTrue(manifest.size >= 30)
        for (line in manifest) {
            val (sha, path) = line.split(Regex("\\s+"), limit = 2)
            val bytes = File(root, path.trim()).readBytes()
            val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("empreinte de $path", sha, got)
        }
    }
}
