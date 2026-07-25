package com.minou.mvrviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minou.mvrviewer.sync.CablingAssignment
import com.minou.mvrviewer.sync.CablingSettings
import com.minou.mvrviewer.sync.Distributor
import com.minou.mvrviewer.sync.DistributorKind
import com.minou.mvrviewer.sync.PowerCablingDTO
import com.minou.mvrviewer.sync.defaultCircuitPhases
import java.util.UUID

/**
 * État Compose du CÂBLAGE électrique (phase 2) — équivalent de [PatchOverrides]
 * pour les distributeurs/affectations. Réactif : l'écran câblage se recompose dès
 * qu'un distributeur, une affectation ou un réglage change.
 *
 * [onChange] est invoqué après une modification UTILISATEUR (pas le chargement
 * disque/cloud) avec le DTO complet à jour → l'écran persiste (ProjectStore) et
 * pousse au cloud (SyncViewModel), comme le patch.
 */
class PowerCablingState {
    var settings by mutableStateOf(CablingSettings())
        private set
    val distributors = mutableStateListOf<Distributor>()
    /**
     * Affectations indexées PAR PROJECTEUR (mvrUUID) : un projecteur est affecté
     * à AU PLUS un circuit, donc réaffecter écrase l'entrée = le déplace. Un
     * circuit accueille plusieurs projecteurs (clés distinctes, même valeur).
     */
    val assignments = mutableStateMapOf<String, CablingAssignment>()

    var version by mutableIntStateOf(0)
        private set

    var onChange: ((PowerCablingDTO) -> Unit)? = null

    // Suppression du commit pendant un chargement (disque/cloud) : sinon restaurer
    // l'état déclencherait un push qui se battrait avec le distant (LWW).
    private var loading = false

    fun toDTO(): PowerCablingDTO =
        PowerCablingDTO(settings, distributors.toList(), assignments.values.toList())

    /** Recharge tout l'état depuis un DTO (sanitisé au préalable), SANS commit. */
    fun load(dto: PowerCablingDTO) {
        loading = true
        settings = dto.settings
        distributors.clear(); distributors.addAll(dto.distributors)
        assignments.clear()
        for (a in dto.assignments) assignments[a.fixture] = a
        loading = false
        version++
    }

    private fun commit() {
        version++
        if (!loading) onChange?.invoke(toDTO())
    }

    // ---- Distributeurs ------------------------------------------------------

    /** Ajoute un distributeur auto-nommé (« Soca N » / « Ligne N ») et le renvoie. */
    fun addDistributor(kind: DistributorKind): Distributor {
        val n = distributors.count { it.kind == kind } + 1
        val name = if (kind == DistributorKind.SOCA) "Soca $n" else "Ligne $n"
        val color = CablingPalette.colorAt(distributors.size)
        val d = Distributor(UUID.randomUUID().toString(), name, kind, color, defaultCircuitPhases(kind))
        distributors.add(d)
        commit()
        return d
    }

    private fun replace(id: String, transform: (Distributor) -> Distributor) {
        val i = distributors.indexOfFirst { it.id == id }
        if (i >= 0) { distributors[i] = transform(distributors[i]); commit() }
    }

    fun rename(id: String, name: String) = replace(id) { it.copy(name = name) }
    fun setColor(id: String, argb: Int) = replace(id) { it.copy(colorArgb = argb) }

    /** Change la phase (1|2|3) d'un circuit 1-based d'un distributeur. */
    fun setCircuitPhase(id: String, circuit: Int, phase: Int) = replace(id) { d ->
        if (circuit !in 1..d.circuitCount) d
        else d.copy(circuitPhases = d.circuitPhases.toMutableList().also { it[circuit - 1] = phase })
    }

    /** Supprime un distributeur ET toutes les affectations qui le visaient. */
    fun removeDistributor(id: String) {
        val removed = distributors.removeAll { it.id == id }
        val hadAssign = assignments.values.any { it.distributor == id }
        if (hadAssign) {
            val keys = assignments.filterValues { it.distributor == id }.keys.toList()
            keys.forEach { assignments.remove(it) }
        }
        if (removed || hadAssign) commit()
    }

    // ---- Affectations -------------------------------------------------------

    /** Affecte (ou déplace) plusieurs projecteurs vers un circuit d'un distributeur. */
    fun assign(fixtures: Collection<String>, distributor: String, circuit: Int) {
        if (fixtures.isEmpty()) return
        for (fx in fixtures) assignments[fx] = CablingAssignment(fx, distributor, circuit)
        commit()
    }

    /** Retire un projecteur de son circuit (le laisse non affecté). */
    fun unassign(fixture: String) {
        if (assignments.remove(fixture) != null) commit()
    }

    /**
     * VIDE un circuit : retire d'un coup TOUTES les affectations de CE circuit
     * précis (ni les autres circuits, ni les autres distributeurs). Un seul
     * `commit()` → un seul push cloud + une seule persistance (comme
     * [removeDistributor]). Sans effet (donc sans commit) si le circuit est déjà vide.
     */
    fun clearCircuit(distributor: String, circuit: Int) {
        val keys = assignments.filterValues { it.distributor == distributor && it.circuit == circuit }.keys.toList()
        if (keys.isEmpty()) return
        keys.forEach { assignments.remove(it) }
        commit()
    }

    fun assignmentOf(fixture: String): CablingAssignment? = assignments[fixture]

    // ---- Réglages -----------------------------------------------------------

    fun applySettings(s: CablingSettings) { settings = s; commit() }
}

/**
 * CIBLE d'affectation « en sélectionnant sur le plan » (E2). Depuis l'écran
 * Câblage, une action « Sélectionner sur le plan » sur un CIRCUIT Socapex ou un
 * DÉPART DMX bascule la vue plan en MODE AFFECTATION visant cette cible : taper
 * (ou encadrer) un projecteur l'affecte/le retire.
 *
 * `index` = circuit 1-based (SOCA) ou cœur/départ 1-based (DMX). Le couple
 * (distributorId, index) suffit à router vers `PowerCablingState.assign` ou
 * `DmxCablingState.assign`.
 */
data class CablingAssignTarget(val kind: Kind, val distributorId: String, val index: Int) {
    enum class Kind { SOCA, DMX }
}

/** Palette ARGB des distributeurs (repérage visuel), indexée par ordre d'ajout. */
object CablingPalette {
    private val COLORS = intArrayOf(
        0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
        0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFFFDD835.toInt(), 0xFFEC407A.toInt(),
        0xFF6D4C41.toInt(), 0xFF3949AB.toInt(), 0xFF26A69A.toInt(), 0xFF7CB342.toInt()
    )
    fun colorAt(index: Int): Int = COLORS[index.mod(COLORS.size)]
    val all: IntArray get() = COLORS
}
