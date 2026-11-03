package com.minou.mvrviewer.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Projecteurs custom V1 — le DTO figé, son codec JSON et l'enveloppe de section
 * sont un CONTRAT PARTAGÉ iOS/Android. Ces tests le verrouillent : un round-trip
 * qui perd un champ ferait diverger les plateformes ; un `_0` en tableau (au lieu
 * d'un objet) casserait le décodage cross-platform (cf. SectionCodec).
 */
class CustomFixturesTest {

    private fun type(id: String, fp: Int = 3) = CustomFixtureTypeDTO(
        id = id, name = "Ma barre", baseGdtfSpec = "Generic@LEDBar@Std",
        channels = listOf("Dimmer", "Red", "Green"), footprint = fp
    )

    @Test fun jsonRoundTrip() {
        val dto = CustomFixturesDTO(listOf(type("a", 3), type("b", 8)))
        // fromJson(toJson(x)) == x : aucun champ perdu (id/name/baseGdtfSpec/channels/footprint).
        assertEquals(dto, CustomFixturesCodec.fromJson(CustomFixturesCodec.toJson(dto)))
    }

    @Test fun sectionEnvelopeRoundTripsThroughCodec() {
        // L'enveloppe cross-platform {"customFixtures":{"_0":{"types":[…]}}} doit se relire.
        val dto = CustomFixturesDTO(listOf(type("a")))
        val json = SectionCodec.encode(SectionPayload.CustomFixtures(dto))
        val decoded = SectionCodec.decode(json)
        assertTrue(decoded is SectionPayload.CustomFixtures)
        assertEquals(dto, (decoded as SectionPayload.CustomFixtures).dto)
    }

    @Test fun envelopeWrapsListInObjectNotArray() {
        // INVARIANT SectionCodec : `_0` DOIT être un OBJET enveloppant `types`,
        // jamais un tableau nu — sinon iOS ne décode plus (root.optJSONObject("_0")).
        val json = SectionCodec.encode(SectionPayload.CustomFixtures(CustomFixturesDTO(listOf(type("a")))))
        val root = JSONObject(json)
        val inner = root.getJSONObject("customFixtures").getJSONObject("_0")
        assertTrue(inner.has("types"))
        assertEquals(1, inner.getJSONArray("types").length())
    }

    @Test fun specConventionHelpers() {
        // Convention EXACTE « custom:<id> ».
        assertEquals("custom:abc", customFixtureSpec("abc"))
        assertEquals("abc", customFixtureIdOf("custom:abc"))
        assertNull(customFixtureIdOf("Generic@LEDBar@Std")) // spec GDTF normale
        assertNull(customFixtureIdOf("custom:"))            // id vide → pas un custom
        assertNull(customFixtureIdOf(null))
    }

    @Test fun footprintDefaultsToChannelCountWhenMissing() {
        val json = """{"types":[{"id":"a","name":"X","baseGdtfSpec":"G","channels":["D","R"]}]}"""
        val dto = CustomFixturesCodec.fromJsonString(json)!!
        assertEquals(2, dto.types.first().footprint) // défaut = nb de canaux
    }

    @Test fun patchSectionCarriesCustomSpec() {
        // L'ASSIGNATION fixture→custom voyage via la section PATCH (champ `spec`).
        val dto = PatchDTO(listOf(
            PatchEntryDTO(mvrUUID = "u1", spec = "custom:abc"),
            PatchEntryDTO(mvrUUID = "u2") // non custom → pas de spec
        ))
        val decoded = SectionCodec.decode(SectionCodec.encode(SectionPayload.Patch(dto)))
        assertTrue(decoded is SectionPayload.Patch)
        val entries = (decoded as SectionPayload.Patch).dto.entries
        assertEquals("custom:abc", entries.first { it.mvrUUID == "u1" }.spec)
        assertNull(entries.first { it.mvrUUID == "u2" }.spec)
    }
}
