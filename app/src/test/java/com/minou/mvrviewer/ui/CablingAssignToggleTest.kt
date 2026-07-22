package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CONTRAT DU TOGGLE D'AFFECTATION « sur le plan » (E2). En vue plan, taper un
 * projecteur en mode affectation appelle `toggleAssign`, dont la décision est :
 *   • déjà sur la cible (distributeur + circuit/cœur identiques) → `unassign` ;
 *   • sinon → `assign` (ce qui DÉPLACE au besoin depuis une autre cible).
 *
 * Ces tests exécutent CETTE décision contre les vrais états [PowerCablingState] /
 * [DmxCablingState] (mêmes méthodes que `toggleAssign`) pour verrouiller le
 * comportement attendu : affecter, retirer, et déplacer sans jamais laisser un
 * projecteur affecté à deux cibles.
 */
class CablingAssignToggleTest {

    /** Reproduit exactement la décision de `PlanScreen.toggleAssign` (branche SOCA). */
    private fun togglePower(state: PowerCablingState, fx: String, dist: String, circuit: Int) {
        val cur = state.assignmentOf(fx)
        if (cur != null && cur.distributor == dist && cur.circuit == circuit) state.unassign(fx)
        else state.assign(setOf(fx), dist, circuit)
    }

    /** Reproduit exactement la décision de `PlanScreen.toggleAssign` (branche DMX). */
    private fun toggleDmx(state: DmxCablingState, fx: String, dist: String, core: Int) {
        val cur = state.assignmentOf(fx)
        if (cur != null && cur.distributor == dist && cur.core == core) state.unassign(fx)
        else state.assign(setOf(fx), dist, core)
    }

    @Test fun powerToggleAssignsThenRemoves() {
        val s = PowerCablingState()
        // 1er tap : non affecté → affecté.
        togglePower(s, "fx1", "d1", 2)
        assertEquals("d1", s.assignmentOf("fx1")?.distributor)
        assertEquals(2, s.assignmentOf("fx1")?.circuit)
        // 2e tap sur la MÊME cible : retiré.
        togglePower(s, "fx1", "d1", 2)
        assertNull(s.assignmentOf("fx1"))
    }

    @Test fun powerToggleMovesWhenAssignedElsewhere() {
        val s = PowerCablingState()
        togglePower(s, "fx1", "d1", 1)
        // Tap sur une AUTRE cible : déplacement (pas de retrait), une seule affectation.
        togglePower(s, "fx1", "d2", 5)
        assertEquals("d2", s.assignmentOf("fx1")?.distributor)
        assertEquals(5, s.assignmentOf("fx1")?.circuit)
        assertEquals(1, s.assignments.size)
    }

    @Test fun dmxToggleAssignsThenRemoves() {
        val s = DmxCablingState()
        toggleDmx(s, "fx1", "l1", 3)
        assertEquals("l1", s.assignmentOf("fx1")?.distributor)
        assertEquals(3, s.assignmentOf("fx1")?.core)
        toggleDmx(s, "fx1", "l1", 3)
        assertNull(s.assignmentOf("fx1"))
    }

    @Test fun dmxToggleMovesWhenAssignedElsewhere() {
        val s = DmxCablingState()
        toggleDmx(s, "fx1", "l1", 1)
        toggleDmx(s, "fx1", "l2", 2)
        assertEquals("l2", s.assignmentOf("fx1")?.distributor)
        assertEquals(2, s.assignmentOf("fx1")?.core)
        assertEquals(1, s.assignments.size)
    }
}
