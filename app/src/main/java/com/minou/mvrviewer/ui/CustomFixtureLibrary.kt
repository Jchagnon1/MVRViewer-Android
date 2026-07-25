package com.minou.mvrviewer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import com.minou.mvrviewer.mvr.MvrSceneObject
import com.minou.mvrviewer.sync.CustomFixtureTypeDTO
import com.minou.mvrviewer.sync.CustomFixturesCodec
import com.minou.mvrviewer.sync.CustomFixturesDTO
import com.minou.mvrviewer.sync.customFixtureIdOf
import java.io.File

/**
 * PROJECTEURS CUSTOM V1 — biblio LOCALE + résolveur central (Android).
 *
 * Le modèle mémoire est le DTO figé [CustomFixtureTypeDTO] lui-même (champs
 * identiques au contrat), ce qui SUPPRIME tout pont de renommage et garantit
 * l'alignement cross-platform.
 */

/**
 * Bibliothèque LOCALE persistante (par APPAREIL, hors projet — réutilisable tous
 * shows). JSON dans `filesDir/customFixtures.json`. Même forme que la section de
 * synchro (`{"types":[…]}`), sans l'enveloppe : réutilise [CustomFixturesCodec].
 */
object CustomFixtureLibraryStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "customFixtures.json")

    fun load(ctx: Context): List<CustomFixtureTypeDTO> {
        val json = runCatching { file(ctx).readText() }.getOrNull() ?: return emptyList()
        return CustomFixturesCodec.fromJsonString(json)?.types ?: emptyList()
    }

    fun save(ctx: Context, types: List<CustomFixtureTypeDTO>) {
        runCatching { file(ctx).writeText(CustomFixturesCodec.toJsonString(CustomFixturesDTO(types))) }
    }
}

/**
 * État réactif de la biblio locale (CRUD). `version` bumpe à chaque MUTATION
 * utilisateur (pas au chargement initial) → un effet peut re-persister/rafraîchir
 * le résolveur sans réécrire au premier semis.
 */
class CustomFixtureLibrary {
    val types = mutableStateListOf<CustomFixtureTypeDTO>()
    var version by mutableIntStateOf(0)
        private set

    /** Semis initial (disque) — NE bumpe PAS `version` (pas une édition). */
    fun seed(list: List<CustomFixtureTypeDTO>) {
        types.clear(); types.addAll(list)
    }

    /** Crée ou remplace un type (même id) — mutation utilisateur. */
    fun upsert(t: CustomFixtureTypeDTO) {
        val i = types.indexOfFirst { it.id == t.id }
        if (i >= 0) types[i] = t else types.add(t)
        version++
    }

    fun remove(id: String) {
        if (types.removeAll { it.id == id }) version++
    }

    /**
     * Duplique un type (parité iOS `CustomFixtureLibraryStore.duplicate`) : NOUVEL
     * id, nom suffixé « (copie) », mêmes GDTF de base / canaux / empreinte. Renvoie
     * la copie (ou null si l'id source est introuvable) — mutation utilisateur.
     */
    fun duplicate(id: String): CustomFixtureTypeDTO? {
        val src = types.firstOrNull { it.id == id } ?: return null
        val copy = src.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = src.name + " (copie)"
        )
        types.add(copy)
        version++
        return copy
    }

    fun get(id: String): CustomFixtureTypeDTO? = types.firstOrNull { it.id == id }
}

/**
 * RÉSOLVEUR CENTRAL : table résoluble = défs du PROJET ∪ biblio LOCALE, le PROJET
 * PRIORITAIRE pour une même id (règle de la spec). Réactif (SnapshotStateMap +
 * `version`) → les sites de lecture (3D, patch, univers, câblage) se recomposent /
 * se reconstruisent quand un type change.
 */
class CustomFixtureResolver {
    /** Défs reçues du projet (section customFixtures synchronisée). */
    val projectTypes: SnapshotStateMap<String, CustomFixtureTypeDTO> = mutableStateMapOf()
    /** Miroir de la biblio locale (semé par SceneScreen à chaque changement). */
    val libraryTypes: SnapshotStateMap<String, CustomFixtureTypeDTO> = mutableStateMapOf()
    var version by mutableIntStateOf(0)
        private set

    /** Projet prioritaire, sinon biblio locale. */
    fun resolve(id: String): CustomFixtureTypeDTO? = projectTypes[id] ?: libraryTypes[id]

    /** Résolution depuis une spec effective (« custom:<id> » → type, sinon null). */
    fun resolveSpec(spec: String?): CustomFixtureTypeDTO? = customFixtureIdOf(spec)?.let { resolve(it) }

    /** Rafraîchit le miroir de la biblio locale. */
    fun setLibrary(list: List<CustomFixtureTypeDTO>) {
        libraryTypes.clear()
        for (t in list) libraryTypes[t.id] = t
        version++
    }

    /** Fusionne des défs de projet (le projet prime : on écrase par id). */
    fun mergeProject(list: List<CustomFixtureTypeDTO>) {
        if (list.isEmpty()) return
        for (t in list) projectTypes[t.id] = t
        version++
    }
}

// ---- Helpers de résolution (au-dessus des overrides + du résolveur) -----------

/**
 * SPEC de GÉOMÉTRIE 3D d'un projecteur : si sa spec effective est « custom:<id> »
 * et que le type est résoluble → `baseGdtfSpec` (le chargement GDTF existant s'en
 * charge) ; sinon la spec effective brute. Un custom NON résoluble retombe sur
 * « custom:<id> » (introuvable dans le zip → silhouette générique de repli, sans
 * crash — non-régression).
 */
fun effectiveGeometrySpec(
    o: MvrSceneObject, overrides: PatchOverrides, resolver: CustomFixtureResolver
): String? {
    val es = overrides.effectiveSpec(o)
    val t = resolver.resolveSpec(es) ?: return es
    return t.baseGdtfSpec.takeIf { it.isNotBlank() } ?: es
}

/** Type custom RÉSOLU d'un projecteur (empreinte/canaux/nom), ou null si non-custom. */
fun customTypeOf(
    o: MvrSceneObject, overrides: PatchOverrides, resolver: CustomFixtureResolver
): CustomFixtureTypeDTO? = resolver.resolveSpec(overrides.effectiveSpec(o))
