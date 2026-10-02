package com.minou.mvrviewer.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bibliothèque MA — extension du DTO partagé `customFixtures` (SPEC-ma.md §3).
 *
 * Contrat d'octets : chaque `sections/…input.json` (entrées volontairement DÉSORDONNÉES :
 * clés mélangées, `rid` en chaîne, sha en majuscules, `1000.0`, clé inconnue…) décodé
 * puis ré-encodé par [SectionCodec] doit donner EXACTEMENT les octets de
 * `*.expected.json` produits par `section_reference.py` — ce que produisent aussi iOS et
 * le web : c'est la garantie qu'aucune plateforme ne fait « bouger » la section des autres.
 */
class CustomFixturesMaTest {

    private fun sectionsDir(): File =
        File(javaClass.classLoader!!.getResource("ma/sections")!!.toURI())

    @Test fun everySectionLiteralRoundTripsByteForByte() {
        val inputs = sectionsDir().listFiles()!!.filter { it.name.endsWith(".input.json") }.sortedBy { it.name }
        assertEquals(5, inputs.size)
        for (input in inputs) {
            val expected = File(input.parentFile, input.name.replace(".input.json", ".expected.json")).readText(Charsets.UTF_8)
            val decoded = SectionCodec.decode(input.readText(Charsets.UTF_8))
            assertTrue("${input.name} : section décodée", decoded is SectionPayload.CustomFixtures)
            val encoded = SectionCodec.encode(decoded!!)
            assertEquals("${input.name} : octets", expected, encoded)
            // Et l'encodage canonique est un point FIXE (ré-encoder ne change rien).
            assertEquals("${input.name} : point fixe", expected, SectionCodec.encode(SectionCodec.decode(encoded)!!))
            // Enveloppe cross-platform : `_0` est un OBJET ; même contenu que la référence.
            assertTrue(JSONObject(encoded).getJSONObject("customFixtures").getJSONObject("_0").has("types"))
            // (pas de `JSONObject.similar` : absent de l'org.json d'Android, contre lequel ce test compile)
        }
    }

    @Test fun realProfilesLiteralsKeepHolesAndFootprints() {
        val a = SectionCodec.decode(File(sectionsDir(), "A_ma3_gdtfShare.input.json").readText()) as SectionPayload.CustomFixtures
        assertEquals(2, a.dto.types.size)
        assertNull(a.dto.types[0].profile)                                  // type ancien : clés omises
        val ta = a.dto.types[1]
        assertEquals(97, ta.footprint)
        assertEquals(97, ta.channels.size)
        assertEquals("ma3", ta.profile!!.format)
        assertEquals(CustomFixtureModel3D.GdtfShare(4821), ta.model3D)   // "4821" (chaîne) → 4821
        val b = SectionCodec.decode(File(sectionsDir(), "B_ma2_modelBlob.input.json").readText()) as SectionPayload.CustomFixtures
        val tb = b.dto.types.single()
        assertEquals(104, tb.footprint)
        val m = tb.model3D as CustomFixtureModel3D.ModelBlob
        assertEquals(m.sha256.lowercase(), m.sha256)                        // sha ramené en minuscules
        assertEquals(1000.0, m.unitScaleToMm, 0.0)
    }

    @Test fun tolerantDecodingNeverFails() {
        val c = SectionCodec.decode(File(sectionsDir(), "C_tolerant.input.json").readText()) as SectionPayload.CustomFixtures
        val byId = c.dto.types.associateBy { it.id }
        assertEquals(listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7"), c.dto.types.map { it.id }) // sans id : ignoré
        assertNull(byId.getValue("C1").model3D)        // kind inconnu
        assertNull(byId.getValue("C2").model3D)        // rid invalide
        assertNull(byId.getValue("C3").profile)        // profile non objet
        assertNull(byId.getValue("C4").model3D)        // sha invalide
        val c5 = byId.getValue("C5")
        assertEquals(3, c5.footprint)                  // footprint 0 → max(1, nb de canaux)
        assertEquals(CustomFixtureModel3D.ModelBlob("a".repeat(64), "", "stl", 1.0, false), c5.model3D)
        assertEquals(CustomFixtureModel3D.GdtfShare(77), byId.getValue("C7").model3D)  // 77.0 → 77
        // Un sous-objet totalement illisible n'emporte ni le type ni la section.
        val junk = """{"types":[{"id":"x","profile":[1,2],"model3D":"zzz","channels":[1,true,null]}]}"""
        val t = CustomFixturesCodec.fromJsonString(junk)!!.types.single()
        assertEquals(listOf("1", "true", ""), t.channels)
        assertNull(t.profile); assertNull(t.model3D)
    }

    @Test fun libraryFileWithUnknownModelKeepsOtherTypes() {
        // Biblio locale (`customFixtures.json`) : un model3D au `kind` futur ne vide pas la liste.
        val json = """{"types":[{"id":"a","name":"A","baseGdtfSpec":"","channels":["D"],"footprint":1,"model3D":{"kind":"futur"}},""" +
            """{"id":"b","name":"B","baseGdtfSpec":"G","channels":["D"],"footprint":1}]}"""
        val dto = CustomFixturesCodec.fromJsonString(json)!!
        assertEquals(listOf("a", "b"), dto.types.map { it.id })
    }

    @Test fun numbersAreEncodedLikeTheReference() {
        assertEquals("1000", CustomFixturesCodec.formatNumber(1000.0))
        assertEquals("25.4", CustomFixturesCodec.formatNumber(25.4))
        assertEquals("0.001", CustomFixturesCodec.formatNumber(0.001))
        assertEquals("1e-05", CustomFixturesCodec.formatNumber(0.00001))
        assertEquals("0.1", CustomFixturesCodec.formatNumber(0.1))
        assertEquals("1", CustomFixturesCodec.formatNumber(1.0))
    }

    @Test fun duplicateKeepsProfileAndModel() {
        // §3.7 / annexe 6 : la VRAIE `CustomFixtureLibrary.duplicate` (pas un simple
        // `copy` de data class) conserve la provenance ET la 3D d'un type MA, les trous
        // et l'empreinte — iOS reconstruisait champ par champ et les perdait. Une
        // reconstruction `CustomFixtureTypeDTO(newId, name, base, channels, footprint)`
        // ferait échouer ce test.
        com.minou.mvrviewer.ui.ProAccess.forceForTests(true)   // duplicate() est gardé Pro
        try {
            val share = CustomFixtureTypeDTO("a", "A", "", listOf("Pan", "", "Dim"), 3,
                CustomFixtureProfileDTO("ma2", "M", "F", "Mode", "f.xml"), CustomFixtureModel3D.GdtfShare(1))
            val obj = CustomFixtureTypeDTO("o", "O", "", listOf("Dim", "", "", "Zoom"), 6,
                CustomFixtureProfileDTO("ma3", "M3", "F3", "Mode 3", "g.xml"),
                CustomFixtureModel3D.ModelBlob("c".repeat(64), "bar.obj", "obj", 25.4, false))
            val lib = com.minou.mvrviewer.ui.CustomFixtureLibrary().apply { seed(listOf(share, obj)) }
            for (src in listOf(share, obj)) {
                val copy = lib.duplicate(src.id)!!
                assertTrue("nouvel id", copy.id != src.id)
                assertEquals(src.name + " (copie)", copy.name)
                assertEquals(src.profile, copy.profile)
                assertEquals(src.model3D, copy.model3D)
                assertEquals(src.channels, copy.channels)          // trous "" compris
                assertEquals(src.footprint, copy.footprint)
                assertEquals(src.baseGdtfSpec, copy.baseGdtfSpec)
                assertEquals(copy, lib.get(copy.id))               // bien rangée dans la bibliothèque
            }
            assertEquals(4, lib.types.size)
        } finally {
            com.minou.mvrviewer.ui.ProAccess.forceForTests(null)
        }
    }
}
