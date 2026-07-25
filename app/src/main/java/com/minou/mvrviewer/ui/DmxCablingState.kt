package com.minou.mvrviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.minou.mvrviewer.sync.DmxCablingAssignment
import com.minou.mvrviewer.sync.DmxCablingDTO
import com.minou.mvrviewer.sync.DmxDistributor
import com.minou.mvrviewer.sync.DmxDistributorKind
import com.minou.mvrviewer.sync.defaultDmxCores
import java.util.UUID

/**
 * État Compose du CÂBLAGE DMX (phase 3) — équivalent de [PowerCablingState] pour
 * les lignes DMX (simples / multipaires) et leurs affectations. Réactif : l'onglet
 * DMX se recompose dès qu'une ligne ou une affectation change.
 *
 * [onChange] est invoqué après une modification UTILISATEUR (pas le chargement
 * disque/cloud) avec le DTO complet à jour → l'écran persiste (ProjectStore) et
 * pousse au cloud (SyncViewModel), comme le patch et la Socapex.
 */
class DmxCablingState {
    val distributors = mutableStateListOf<DmxDistributor>()
    /**
     * Affectations indexées PAR PROJECTEUR (mvrUUID) : un projecteur est affecté
     * à AU PLUS un départ, donc réaffecter écrase l'entrée = le déplace. Un départ
     * accueille plusieurs projecteurs (clés distinctes, même valeur).
     */
    val assignments = mutableStateMapOf<String, DmxCablingAssignment>()

    var version by mutableIntStateOf(0)
        private set

    var onChange: ((DmxCablingDTO) -> Unit)? = null

    // Suppression du commit pendant un chargement (disque/cloud) : sinon restaurer
    // l'état déclencherait un push qui se battrait avec le distant (LWW).
    private var loading = false

    fun toDTO(): DmxCablingDTO =
        DmxCablingDTO(distributors.toList(), assignments.values.toList())

    /** Recharge tout l'état depuis un DTO (sanitisé au préalable), SANS commit. */
    fun load(dto: DmxCablingDTO) {
        loading = true
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

    // ---- Lignes DMX ---------------------------------------------------------

    /**
     * Ajoute une ligne DMX auto-nommée (« DMX N » / « Multi N ») et la renvoie.
     * `cores` n'est utilisé que pour une multipaire (nombre de paires réglable à
     * la création) ; une ligne simple est toujours à 1 départ.
     */
    fun addDistributor(kind: DmxDistributorKind, cores: Int = defaultDmxCores(kind)): DmxDistributor {
        val n = distributors.count { it.kind == kind } + 1
        val name = if (kind == DmxDistributorKind.MULTI) "Multi $n" else "DMX $n"
        val color = CablingPalette.colorAt(distributors.size)
        val c = if (kind == DmxDistributorKind.SIMPLE) 1 else maxOf(1, cores)
        val d = DmxDistributor(UUID.randomUUID().toString(), name, kind, color, c)
        distributors.add(d)
        commit()
        return d
    }

    private fun replace(id: String, transform: (DmxDistributor) -> DmxDistributor) {
        val i = distributors.indexOfFirst { it.id == id }
        if (i >= 0) { distributors[i] = transform(distributors[i]); commit() }
    }

    fun rename(id: String, name: String) = replace(id) { it.copy(name = name) }
    fun setColor(id: String, argb: Int) = replace(id) { it.copy(colorArgb = argb) }

    /**
     * Change le nombre de paires d'une multipaire. Si l'on RÉDUIT le nombre de
     * départs, les affectations vers les départs disparus sont retirées ici même
     * (sinon elles resteraient fantômes jusqu'au prochain sanitize au chargement).
     * Sans effet sur une ligne simple (toujours 1 départ).
     */
    fun setCores(id: String, cores: Int) {
        val dist = distributors.firstOrNull { it.id == id } ?: return
        if (dist.kind == DmxDistributorKind.SIMPLE) return
        val newCores = maxOf(1, cores)
        val i = distributors.indexOfFirst { it.id == id }
        if (i < 0) return
        distributors[i] = dist.copy(cores = newCores)
        // Purge des affectations sur les départs supprimés.
        val orphans = assignments.filterValues { it.distributor == id && it.core > newCores }.keys.toList()
        orphans.forEach { assignments.remove(it) }
        commit()
    }

    /** Supprime une ligne ET toutes les affectations qui la visaient. */
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

    /** Affecte (ou déplace) plusieurs projecteurs vers un départ d'une ligne. */
    fun assign(fixtures: Collection<String>, distributor: String, core: Int) {
        if (fixtures.isEmpty()) return
        for (fx in fixtures) assignments[fx] = DmxCablingAssignment(fx, distributor, core)
        commit()
    }

    /** Retire un projecteur de son départ (le laisse non affecté). */
    fun unassign(fixture: String) {
        if (assignments.remove(fixture) != null) commit()
    }

    /**
     * VIDE un départ : retire d'un coup TOUTES les affectations de CE départ
     * précis (ni les autres départs, ni les autres lignes). Un seul `commit()` →
     * un seul push cloud + une seule persistance (comme [removeDistributor]).
     * Sans effet (donc sans commit) si le départ est déjà vide.
     */
    fun clearCore(distributor: String, core: Int) {
        val keys = assignments.filterValues { it.distributor == distributor && it.core == core }.keys.toList()
        if (keys.isEmpty()) return
        keys.forEach { assignments.remove(it) }
        commit()
    }

    fun assignmentOf(fixture: String): DmxCablingAssignment? = assignments[fixture]
}
