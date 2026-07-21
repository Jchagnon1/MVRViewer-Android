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
 * Deux cartes indexées par docId NORMALISÉ (cf. [powerLibraryDocId]) :
 *  - [library] : puissances SAISIES (le cache utilisateur, prioritaires) ;
 *  - [gdtf]    : puissances EXTRAITES du .gdtf (source de repli), remplies en
 *                arrière-plan à l'ouverture.
 *
 * [onCommit] est appelé après une saisie UTILISATEUR (pas les seeds/cloud) →
 * l'écran persiste dans le cache disque, pousse au cloud et journalise.
 */
class PowerLibraryState {
    val library: SnapshotStateMap<String, Int> = mutableStateMapOf()
    val gdtf: SnapshotStateMap<String, Int> = mutableStateMapOf()
    var version by mutableIntStateOf(0)
        private set

    /** (spec, ancienne puissance saisie ou null, nouvelle) — écriture utilisateur. */
    var onCommit: ((String, Int?, Int) -> Unit)? = null

    fun libraryWatts(spec: String?): Int? =
        spec?.let { library[powerLibraryDocId(it)] }
    fun gdtfWatts(spec: String?): Int? =
        spec?.let { gdtf[powerLibraryDocId(it)] }

    /** Puissance effective + provenance selon la règle du contrat. */
    fun effective(spec: String?): PowerResolution =
        resolvePower(libraryWatts(spec), gdtfWatts(spec))

    /** Renseigne la puissance extraite d'un type (aucun commit). */
    fun setGdtf(spec: String, watts: Int) {
        gdtf[powerLibraryDocId(spec)] = watts
        version++
    }

    /** Sème le cache saisi (restauration disque / fusion cloud, aucun commit). Clé = docId. */
    fun seedLibrary(docId: String, watts: Int) {
        library[docId] = watts
        version++
    }

    /** Saisie UTILISATEUR : met à jour le cache + déclenche la persistance/push via onCommit. */
    fun set(spec: String, watts: Int) {
        val id = powerLibraryDocId(spec)
        val old = library[id]
        library[id] = watts
        version++
        onCommit?.invoke(spec, old, watts)
    }
}
