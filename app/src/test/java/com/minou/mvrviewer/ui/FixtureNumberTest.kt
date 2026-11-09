package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.FixtureNumber
import com.minou.mvrviewer.mvr.Mat4
import com.minou.mvrviewer.mvr.MvrObjectKind
import com.minou.mvrviewer.mvr.MvrSceneObject
import com.minou.mvrviewer.sync.LocalMapper
import com.minou.mvrviewer.sync.PatchDTO
import com.minou.mvrviewer.sync.PatchEntryDTO
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * N° de projecteur « sans numéro » — régression du bandeau « N° en double : #201, …, #Aucun »
 * (« Duplicate IDs:#201, … #Aucun ») vu sur « MVR Viewer - Exemple.mvr » (export Vectorworks FR).
 *
 * Vecteurs COMMUNS `port-vectors/numero-projecteur/vectors.json` (copie telle quelle de
 * MVRViewerWeb/tests/fixtures/numero-projecteur/, rejoués aussi par le web et iOS) :
 * normalisation, doublons et texte du bandeau ; plus le N° EFFECTIF d'un patch reçu d'une version
 * d'avant la règle, et la ressource `patch_duplicate_ids` telle qu'aapt la compile.
 * (La lecture du .mvr passe par `android.util.Xml`, absent des tests JVM : MvrParser appelle
 * `FixtureNumber.normalize`, testé ici.)
 */
class FixtureNumberTest {

    private val vectors: JSONObject by lazy {
        val url = javaClass.classLoader!!.getResource("port-vectors/numero-projecteur/vectors.json")!!
        JSONObject(url.readText())
    }

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else getString(k)
    private fun JSONArray.strings(): List<String?> = (0 until length()).map { if (isNull(it)) null else getString(it) }

    @Test fun `vecteurs communs - normalisation`() {
        assertEquals("mvrviewer.vectors.numero-projecteur/1", vectors.getString("schema"))
        assertEquals(vectors.getJSONArray("placeholders").strings(), FixtureNumber.PLACEHOLDERS)
        val cases = vectors.getJSONArray("normalize")
        assertTrue(cases.length() >= 19)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val raw = c.optStringOrNull("raw")
            assertEquals("${JSONObject.quote(raw)} — ${c.getString("why")}", c.optStringOrNull("expected"), FixtureNumber.normalize(raw))
        }
    }

    @Test fun `vecteurs communs - doublons tries numeriquement, marqueurs jamais en double`() {
        val cases = vectors.getJSONArray("duplicates")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("why"), c.getJSONArray("expected").strings(), FixtureNumber.duplicates(c.getJSONArray("ids").strings()))
        }
        val exemple = (0 until cases.length()).map { cases.getJSONObject(it) }.first { it.getJSONArray("ids").length() == 83 }
        assertFalse("plus de « #Aucun »", "Aucun" in FixtureNumber.duplicates(exemple.getJSONArray("ids").strings()))
    }

    /**
     * Valeur d'une `<string>` telle qu'aapt la compile : hors guillemets, les blancs de bord sont
     * RETIRÉS et les suites de blancs réduites à une espace — l'espace finale de l'ancienne
     * valeur « Duplicate IDs: » était perdue, d'où « IDs:#201 » à l'écran.
     */
    private fun compiledString(lang: String, name: String): String {
        val dir = if (lang == "fr") "values" else "values-$lang"
        val f = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml")).first { it.isFile }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        val raw = (0 until nodes.length).map { nodes.item(it) as Element }.first { it.getAttribute("name") == name }.textContent
        return if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw.substring(1, raw.length - 1)
        else raw.trim().replace(Regex("\\s+"), " ")
    }

    @Test fun `vecteurs communs - texte du bandeau avec une espace apres les deux-points (FR et EN)`() {
        val cases = vectors.getJSONArray("banner")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val list = FixtureNumber.bannerList(c.getJSONArray("ids").strings().map { it!! })
            assertEquals(c.getString("fr"), String.format(TestRes.FR.locale, compiledString("fr", "patch_duplicate_ids"), list))
            assertEquals(c.getString("en"), String.format(TestRes.EN.locale, compiledString("en", "patch_duplicate_ids"), list))
        }
    }

    private fun fixture(uuid: String, id: String?) = MvrSceneObject(
        uuid, "Projecteur $uuid", MvrObjectKind.FIXTURE, Mat4(), emptyList(), "Generic@Spot.gdtf", "Standard", id, listOf("1"), "Lumière"
    )

    @Test fun `patch recu d'une ancienne version - Aucun ou None ne s'affichent ni ne comptent`() {
        // MvrParser a déjà lu « Aucun » comme sans numéro (null) ; une version d'avant la règle a
        // poussé le N° LU dans la section patch du projet partagé.
        val scene = listOf(fixture("u1", null), fixture("u2", null), fixture("u3", "201"), fixture("u4", "201"), fixture("u5", "202"))
        val overrides = PatchOverrides()
        overrides.applyPersisted(LocalMapper.toEdits(PatchDTO(listOf(
            PatchEntryDTO("u1", fixtureId = "Aucun", addresses = listOf("11")),
            PatchEntryDTO("u2", fixtureId = " none ", addresses = listOf("21")),
            PatchEntryDTO("u5", fixtureId = "Aucun", addresses = listOf("51")),
        ))))
        assertNull(overrides.effectiveId(scene[0]))
        assertNull(overrides.effectiveId(scene[1]))
        assertEquals("201", overrides.effectiveId(scene[2]))
        assertNull("un « Aucun » explicite = sans numéro", overrides.effectiveId(scene[4]))
        // Calcul du bandeau de PatchListScreen : TOUT le projet, N° effectifs.
        assertEquals(listOf("201"), FixtureNumber.duplicates(scene.map { overrides.effectiveId(it) }))
    }

    @Test fun `ordre numerique des N° coherent avec le web et iOS`() {
        assertEquals(listOf("2", "9", "10", "100", "A1", "B9", "B10"),
            listOf("B10", "100", "A1", "9", "B9", "10", "2").sortedWith(FixtureNumber.ORDER))
        assertEquals(listOf("007", "8"), listOf("8", "007").sortedWith(FixtureNumber.ORDER))
    }
}
