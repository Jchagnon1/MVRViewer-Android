package com.minou.mvrviewer.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le DTO câblage, son codec JSON et le calcul de charge sont un CONTRAT PARTAGÉ
 * iOS/Android (section « powerCabling »). Ces tests le verrouillent : un
 * round-trip qui perd un champ ferait diverger les deux plateformes ; un calcul
 * de charge/phase erroné donnerait de faux « OK » sur un circuit surchargé.
 */
class PowerCablingTest {

    private fun soca(id: String, name: String = "Soca 1") = Distributor(
        id = id, name = name, kind = DistributorKind.SOCA,
        colorArgb = 0xFFE53935.toInt(), circuitPhases = defaultCircuitPhases(DistributorKind.SOCA)
    )

    private fun solo(id: String, name: String = "Ligne A") = Distributor(
        id = id, name = name, kind = DistributorKind.SOLO,
        colorArgb = 0xFF1E88E5.toInt(), circuitPhases = defaultCircuitPhases(DistributorKind.SOLO)
    )

    @Test fun jsonRoundTrip() {
        val dto = PowerCablingDTO(
            settings = CablingSettings(voltage = 230, circuitLimitW = 3680, phaseLimitW = 7000),
            distributors = listOf(soca("d1"), solo("d2")),
            assignments = listOf(
                CablingAssignment("fx1", "d1", 1),
                CablingAssignment("fx2", "d1", 3),
                CablingAssignment("fx3", "d2", 1)
            )
        )
        // fromJson(toJson(x)) == x : aucun champ perdu.
        assertEquals(dto, PowerCablingCodec.fromJson(PowerCablingCodec.toJson(dto)))
    }

    @Test fun defaultsMatchContract() {
        // Socapex : 6 circuits, 1-2→L1, 3-4→L2, 5-6→L3.
        assertEquals(listOf(1, 1, 2, 2, 3, 3), defaultCircuitPhases(DistributorKind.SOCA))
        assertEquals(6, defaultCircuitCount(DistributorKind.SOCA))
        // Solo PC16 : 1 seul circuit.
        assertEquals(listOf(1), defaultCircuitPhases(DistributorKind.SOLO))
        assertEquals(1, defaultCircuitCount(DistributorKind.SOLO))
    }

    @Test fun circuitLoadSumsKnownWatts() {
        val d = soca("d1")
        val settings = CablingSettings()
        val assigns = listOf(
            CablingAssignment("a", "d1", 1),
            CablingAssignment("b", "d1", 1)
        )
        val watts = mapOf("a" to 700, "b" to 1000)
        val cl = PowerCablingCalc.circuitLoad(d, 1, assigns, settings) { watts[it] }
        assertEquals(1700, cl.totalW)
        assertEquals(0, cl.unknownCount)
        assertFalse(cl.overloaded)
        assertEquals(1, cl.phase) // circuit 1 → L1
    }

    @Test fun circuitOverloadDetected() {
        val d = soca("d1")
        val settings = CablingSettings(circuitLimitW = 3680)
        // 3 × 1500 = 4500 > 3680.
        val assigns = (1..3).map { CablingAssignment("f$it", "d1", 2) }
        val cl = PowerCablingCalc.circuitLoad(d, 2, assigns, settings) { 1500 }
        assertEquals(4500, cl.totalW)
        assertTrue(cl.overloaded)
    }

    @Test fun unknownConsumptionCountsZeroButFlagged() {
        val d = soca("d1")
        val settings = CablingSettings()
        val assigns = listOf(
            CablingAssignment("known", "d1", 1),
            CablingAssignment("mystere", "d1", 1)
        )
        // « mystere » sans conso → 0 W mais unknownCount = 1 (badge « conso inconnue »).
        val cl = PowerCablingCalc.circuitLoad(d, 1, assigns, settings) {
            if (it == "known") 900 else null
        }
        assertEquals(900, cl.totalW)
        assertEquals(1, cl.unknownCount)
    }

    @Test fun phaseLoadSumsCircuitsOnThatPhase() {
        val d = soca("d1") // 1,2→L1 ; 3,4→L2 ; 5,6→L3
        val settings = CablingSettings(phaseLimitW = 3000)
        val assigns = listOf(
            CablingAssignment("a", "d1", 1), // L1
            CablingAssignment("b", "d1", 2), // L1
            CablingAssignment("c", "d1", 3)  // L2
        )
        val watts = mapOf("a" to 1000, "b" to 1500, "c" to 800)
        val l1 = PowerCablingCalc.phaseLoad(d, 1, assigns, settings) { watts[it] }
        val l2 = PowerCablingCalc.phaseLoad(d, 2, assigns, settings) { watts[it] }
        val l3 = PowerCablingCalc.phaseLoad(d, 3, assigns, settings) { watts[it] }
        assertEquals(2500, l1.totalW) // 1000 + 1500 (circuits 1 & 2)
        assertEquals(800, l2.totalW)
        assertEquals(0, l3.totalW)
    }

    @Test fun phaseOverloadOnlyWhenLimitSet() {
        val d = soca("d1")
        val assigns = listOf(CablingAssignment("a", "d1", 1))
        val watts = { _: String -> 5000 }
        // phaseLimitW = 0 → jamais d'alerte, même à 5000 W.
        val noLimit = PowerCablingCalc.phaseLoad(d, 1, assigns, CablingSettings(phaseLimitW = 0), watts)
        assertFalse(noLimit.overloaded)
        // phaseLimitW = 3680 → 5000 > 3680 → alerte.
        val withLimit = PowerCablingCalc.phaseLoad(d, 1, assigns, CablingSettings(phaseLimitW = 3680), watts)
        assertTrue(withLimit.overloaded)
    }

    @Test fun distributorTotalSumsAllCircuits() {
        val d = soca("d1")
        val settings = CablingSettings()
        val assigns = listOf(
            CablingAssignment("a", "d1", 1),
            CablingAssignment("b", "d1", 4),
            CablingAssignment("c", "d1", 6)
        )
        val total = PowerCablingCalc.distributorTotalW(d, assigns, settings) { 600 }
        assertEquals(1800, total)
    }

    @Test fun sanitizeDropsInvalidAssignments() {
        val d = soca("d1")
        val dto = PowerCablingDTO(
            distributors = listOf(d),
            assignments = listOf(
                CablingAssignment("fx1", "d1", 1),   // valide
                CablingAssignment("ghost", "d1", 1), // projecteur inexistant → rejeté
                CablingAssignment("fx2", "nope", 1), // distributeur inexistant → rejeté
                CablingAssignment("fx3", "d1", 9)    // circuit hors bornes → rejeté
            )
        )
        val clean = dto.sanitized(validFixtures = setOf("fx1", "fx2", "fx3"))
        assertEquals(listOf(CablingAssignment("fx1", "d1", 1)), clean.assignments)
    }

    @Test fun sanitizeKeepsLastCircuitPerFixture() {
        // Un projecteur est affecté à AU PLUS un circuit : la dernière gagne.
        val d = soca("d1")
        val dto = PowerCablingDTO(
            distributors = listOf(d),
            assignments = listOf(
                CablingAssignment("fx1", "d1", 1),
                CablingAssignment("fx1", "d1", 4) // déplacement
            )
        )
        val clean = dto.sanitized(validFixtures = setOf("fx1"))
        assertEquals(listOf(CablingAssignment("fx1", "d1", 4)), clean.assignments)
    }

    @Test fun sectionEnvelopeRoundTripsThroughCodec() {
        // L'enveloppe cross-platform {"powerCabling":{"_0":dto}} doit se relire.
        val dto = PowerCablingDTO(distributors = listOf(soca("d1")))
        val json = SectionCodec.encode(SectionPayload.PowerCabling(dto))
        val decoded = SectionCodec.decode(json)
        assertTrue(decoded is SectionPayload.PowerCabling)
        assertEquals(dto, (decoded as SectionPayload.PowerCabling).dto)
    }
}
