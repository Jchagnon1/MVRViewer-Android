package com.minou.mvrviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.minou.mvrviewer.sync.PowerResolution
import com.minou.mvrviewer.sync.powerLibraryDocId
import com.minou.mvrviewer.sync.resolvePower

/**
 * État Compose de la bibliothèque de puissances pour l'UI — équivalent de
 * [PatchOverrides]/[GdtfOverrides] pour la conso électrique. Réactif : les
 * fiches et la liste de patch se recomposent dès qu'une puissance change (cache
 * local, extraction GDTF ou fusion cloud).
 *
 * Cartes indexées par docId NORMALISÉ (cf. [powerLibraryDocId]) :
 *  - [library]      : valeur de CONSENSUS communautaire (prioritaire) ;
 *  - [libraryVotes] : nombre de votes de ce consensus (la CONFIANCE affichée) ;
 *  - [gdtf]         : puissances EXTRAITES du .gdtf (repli), remplies en
 *                     arrière-plan à l'ouverture.
 *
 * [onCommit] est appelé après une saisie/vote UTILISATEUR (pas les seeds/cloud) →
 * l'écran dépose le vote au cloud, met à jour le consensus, le cache et journalise.
 */
class PowerLibraryState {
    val library: SnapshotStateMap<String, Int> = mutableStateMapOf()
    val libraryVotes: SnapshotStateMap<String, Int> = mutableStateMapOf()
    val gdtf: SnapshotStateMap<String, Int> = mutableStateMapOf()
    var version by mutableIntStateOf(0)
        private set

    /** (spec, ancienne valeur de consensus ou null, nouvelle valeur votée) — vote utilisateur. */
    var onCommit: ((String, Int?, Int) -> Unit)? = null

    fun libraryWatts(spec: String?): Int? =
        spec?.let { library[powerLibraryDocId(it)] }
    fun gdtfWatts(spec: String?): Int? =
        spec?.let { gdtf[powerLibraryDocId(it)] }

    /** Nombre de votes du consensus courant pour une spec (null si aucun). */
    fun consensusVotes(spec: String?): Int? =
        spec?.let { libraryVotes[powerLibraryDocId(it)] }

    /** Puissance effective + provenance selon la règle du contrat. */
    fun effective(spec: String?): PowerResolution =
        resolvePower(libraryWatts(spec), gdtfWatts(spec))

    /** Renseigne la puissance extraite d'un type (aucun commit). */
    fun setGdtf(spec: String, watts: Int) {
        gdtf[powerLibraryDocId(spec)] = watts
        version++
    }

    /** Sème le consensus (restauration disque / fetch cloud, aucun commit). Clé = docId. */
    fun seedConsensus(docId: String, watts: Int, votes: Int) {
        library[docId] = watts
        libraryVotes[docId] = votes
        version++
    }

    /**
     * VOTE UTILISATEUR : met à jour le consensus de façon OPTIMISTE (valeur =
     * mon vote) et déclenche onCommit, qui déposera le vote au cloud puis
     * corrigera library/libraryVotes avec le consensus réel via [seedConsensus].
     */
    fun set(spec: String, watts: Int) {
        val id = powerLibraryDocId(spec)
        val old = library[id]
        library[id] = watts
        version++
        onCommit?.invoke(spec, old, watts)
    }
}
