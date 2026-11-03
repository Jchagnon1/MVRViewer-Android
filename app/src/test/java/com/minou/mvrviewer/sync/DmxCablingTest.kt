package com.minou.mvrviewer.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le DTO câblage DMX, son codec JSON et le calcul d'empreinte sont un CONTRAT
 * PARTAGÉ iOS/Android (section « dmxCabling », miroir de « powerCabling »). Ces
 * tests le verrouillent : un round-trip qui perd un champ ferait diverger les deux
 * plateformes ; une empreinte cumulée erronée donnerait un faux « ≤ 512 » sur un
 * départ qui déborde d'un univers.
 */
class DmxCablingTest {

    private fun simple(id: String, name: String = "DMX 1") = DmxDistributor(
        id = id, name = name, kind = DmxDistributorKind.SIMPLE,
        colorArgb = 0xFFE53935.toInt(), cores = 1
    )

    private fun multi(id: String, name: String = "Multi 1", cores: Int = 4) = DmxDistributor(
        id = id, name = name, kind = DmxDistributorKind.MULTI,
        colorArgb = 0xFF1E88E5.toInt(), cores = cores
    )

    @Test fun jsonRoundTrip() {
        val dto = DmxCablingDTO(
            distributors = listOf(simple("d1"), multi("d2", cores = 6)),
            assignments = listOf(
                DmxCablingAssignment("fx1", "d1", 1),
                DmxCablingAssignment("fx2", "d2", 3),
                DmxCablingAssignment("fx3", "d2", 6)
            )
        )
        // fromJson(toJson(x)) == x : aucun champ perdu.
        assertEquals(dto, DmxCablingCodec.fromJson(DmxCablingCodec.toJson(dto)))
    }

    @Test fun defaultsMatchContract() {
        // Simple : 1 départ. Multipaire : 4 par défaut.
        assertEquals(1, defaultDmxCores(DmxDistributorKind.SIMPLE))
        assertEquals(4, defaultDmxCores(DmxDistributorKind.MULTI))
        assertEquals(1, simple("d").coreCount)
        assertEquals(4, multi("d").coreCount)
        assertEquals(8, multi("d", cores = 8).coreCount)
    }

    @Test fun simpleAlwaysHasOneCoreEvenIfJsonCorrupt() {
        // Un JSON abîmé donnant cores=5 à une ligne simple ne doit exposer qu'un départ.
        val json = """{"distributors":[{"id":"d1","name":"X","kind":"simple","colorArgb":0,"cores":5}],"assignments":[]}"""
        val dto = DmxCablingCodec.fromJsonString(json)!!
        assertEquals(1, dto.distributors.first().cores)
        assertEquals(1, dto.distributors.first().coreCount)
    }

    @Test fun multiDefaultsCoresWhenFieldMissing() {
        val json = """{"distributors":[{"id":"d1","name":"X","kind":"multi","colorArgb":0}],"assignments":[]}"""
        val dto = DmxCablingCodec.fromJsonString(json)!!
        assertEquals(4, dto.distributors.first().cores)
    }

    @Test fun coreFootprintSumsKnownChannels() {
        val d = multi("d1", cores = 4)
        val assigns = listOf(
            DmxCablingAssignment("a", "d1", 1),
            DmxCablingAssignment("b", "d1", 1)
        )
        val channels = mapOf("a" to 16, "b" to 32)
        val load = DmxCablingCalc.coreLoad(d, 1, assigns, { channels[it] }, { 1 })
        assertEquals(48, load.totalChannels)
        assertEquals(0, load.unknownCount)
        assertFalse(load.over512)
        assertFalse(load.mixedUniverse)
    }

    @Test fun unknownFootprintCountsZeroButFlagged() {
        val d = simple("d1")
        val assigns = listOf(
            DmxCablingAssignment("known", "d1", 1),
            DmxCablingAssignment("mystere", "d1", 1)
        )
        // « mystere » sans empreinte → 0 canal mais unknownCount = 1.
        val load = DmxCablingCalc.coreLoad(d, 1, assigns,
            { if (it == "known") 20 else null }, { null })
        assertEquals(20, load.totalChannels)
        assertEquals(1, load.unknownCount)
    }

    @Test fun over512DetectedNotBlocking() {
        val d = simple("d1")
        // 20 projecteurs × 30 canaux = 600 > 512.
        val assigns = (1..20).map { DmxCablingAssignment("f$it", "d1", 1) }
        val load = DmxCablingCalc.coreLoad(d, 1, assigns, { 30 }, { 1 })
        assertEquals(600, load.totalChannels)
        assertTrue(load.over512)
    }

    @Test fun mixedUniverseDetected() {
        val d = simple("d1")
        val assigns = listOf(
            DmxCablingAssignment("a", "d1", 1),
            DmxCablingAssignment("b", "d1", 1),
            DmxCablingAssignment("c", "d1", 1)
        )
        val universes = mapOf("a" to 1, "b" to 1, "c" to 2)
        val load = DmxCablingCalc.coreLoad(d, 1, assigns, { 10 }, { universes[it] })
        assertEquals(listOf(1, 2), load.universes)
        assertTrue(load.mixedUniverse)
        // Un seul univers → pas d'alerte.
        val same = DmxCablingCalc.coreLoad(d, 1, assigns, { 10 }, { 1 })
        assertFalse(same.mixedUniverse)
    }

    @Test fun distributorTotalSumsAllCores() {
        val d = multi("d1", cores = 4)
        val assigns = listOf(
            DmxCablingAssignment("a", "d1", 1),
            DmxCablingAssignment("b", "d1", 2),
            DmxCablingAssignment("c", "d1", 4)
        )
        val total = DmxCablingCalc.distributorTotalChannels(d, assigns) { 12 }
        assertEquals(36, total)
    }

    @Test fun sanitizeDropsInvalidAssignments() {
        val d = multi("d1", cores = 4)
        val dto = DmxCablingDTO(
            distributors = listOf(d),
            assignments = listOf(
                DmxCablingAssignment("fx1", "d1", 1),   // valide
                DmxCablingAssignment("ghost", "d1", 1), // projecteur inexistant → rejeté
                DmxCablingAssignment("fx2", "nope", 1), // ligne inexistante → rejeté
                DmxCablingAssignment("fx3", "d1", 9)    // départ hors bornes → rejeté
            )
        )
        val clean = dto.sanitized(validFixtures = setOf("fx1", "fx2", "fx3"))
        assertEquals(listOf(DmxCablingAssignment("fx1", "d1", 1)), clean.assignments)
    }

    @Test fun sanitizeKeepsLastCorePerFixture() {
        // Un projecteur est affecté à AU PLUS un départ : la dernière gagne (dédup/déplacement).
        val d = multi("d1", cores = 4)
        val dto = DmxCablingDTO(
            distributors = listOf(d),
            assignments = listOf(
                DmxCablingAssignment("fx1", "d1", 1),
                DmxCablingAssignment("fx1", "d1", 3) // déplacement
            )
        )
        val clean = dto.sanitized(validFixtures = setOf("fx1"))
        assertEquals(listOf(DmxCablingAssignment("fx1", "d1", 3)), clean.assignments)
    }

    @Test fun sanitizeKeepsEverythingWhenFixtureSetEmpty() {
        // ENSEMBLE VIDE = show pas encore chargé : on ne purge pas par projecteur
        // (aligné iOS). Seuls ligne/départ inexistants restent filtrés.
        val d = simple("d1")
        val dto = DmxCablingDTO(
            distributors = listOf(d),
            assignments = listOf(
                DmxCablingAssignment("fx1", "d1", 1),
                DmxCablingAssignment("fx2", "d1", 1),
                DmxCablingAssignment("fx3", "nope", 1) // ligne inexistante → toujours rejeté
            )
        )
        val clean = dto.sanitized(validFixtures = emptySet())
        assertEquals(
            listOf(
                DmxCablingAssignment("fx1", "d1", 1),
                DmxCablingAssignment("fx2", "d1", 1)
            ),
            clean.assignments
        )
    }

    @Test fun sectionEnvelopeRoundTripsThroughCodec() {
        // L'enveloppe cross-platform {"dmxCabling":{"_0":dto}} doit se relire.
        val dto = DmxCablingDTO(distributors = listOf(multi("d1")))
        val json = SectionCodec.encode(SectionPayload.DmxCabling(dto))
        val decoded = SectionCodec.decode(json)
        assertTrue(decoded is SectionPayload.DmxCabling)
        assertEquals(dto, (decoded as SectionPayload.DmxCabling).dto)
    }
}
