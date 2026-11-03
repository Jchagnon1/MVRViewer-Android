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
        if (!ProAccess.gate("créer un projecteur personnalisé") {}) return
        val i = types.indexOfFirst { it.id == t.id }
        if (i >= 0) types[i] = t else types.add(t)
        version++
    }

    fun remove(id: String) {
        if (!ProAccess.gate("modifier un projecteur personnalisé") {}) return
        if (types.removeAll { it.id == id }) version++
    }

    /**
     * Duplique un type (parité iOS `CustomFixtureLibraryStore.duplicate`) : NOUVEL
     * id, nom suffixé « (copie) », mêmes GDTF de base / canaux / empreinte. Renvoie
     * la copie (ou null si l'id source est introuvable) — mutation utilisateur.
     */
    fun duplicate(id: String): CustomFixtureTypeDTO? {
        if (!ProAccess.gate("créer un projecteur personnalisé") {}) return null
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
 * charge) ; base VIDE (type MA sans GDTF du show) → la spec GDTF d'ORIGINE du
 * projecteur, comme iOS `renderSpec` (SPEC-ma.md §4.6) ; sinon la spec effective
 * brute. Un custom NON résoluble retombe sur « custom:<id> » (introuvable dans le zip
 * → silhouette générique de repli, sans crash — non-régression).
 *
 * Utilisé tel quel par le câblage (consommation : base / origine, `model3D` ignoré) ;
 * la 3D passe par [fixture3DSource], qui fait primer un `model3D` disponible.
 */
fun effectiveGeometrySpec(
    o: MvrSceneObject, overrides: PatchOverrides, resolver: CustomFixtureResolver
): String? {
    val es = overrides.effectiveSpec(o)
    val t = resolver.resolveSpec(es) ?: return es
    return t.baseGdtfSpec.takeIf { it.isNotBlank() } ?: o.gdtfSpec
}

/** Source de la géométrie 3D d'un projecteur (SPEC-ma.md §4.6). */
sealed class Fixture3DSource {
    /** Un GDTF à assembler : clé dans [GdtfOverrides.map] (clé de rendu `type3d:…`) ou spec du show. */
    data class Gdtf(val spec: String) : Fixture3DSource()
    /** Objet 3D statique (d) dont les octets sont dans le cache de l'appareil. */
    data class Model(val model: com.minou.mvrviewer.sync.CustomFixtureModel3D.ModelBlob) : Fixture3DSource()
    /** Rien à assembler : cube / formes génériques. */
    object None : Fixture3DSource()
}

/**
 * Priorité de la géométrie d'un projecteur (SPEC-ma.md §4.6) :
 *  1. type custom résolu avec un `model3D` DISPONIBLE ici — GDTF (b)(c) inscrit sous sa
 *     clé de rendu dans [GdtfOverrides.map], ou objet 3D (d) en cache et lu par Android ;
 *     il prime aussi sur la géométrie PROPRE de l'objet MVR (choix explicite) ;
 *  2. sinon la règle actuelle ([effectiveGeometrySpec]).
 * `memo` (par id de type) évite de retester le disque pour chaque projecteur.
 */
fun fixture3DSource(
    o: MvrSceneObject,
    overrides: PatchOverrides,
    resolver: CustomFixtureResolver,
    store: Fixture3DStore?,
    gdtfOverrides: GdtfOverrides,
    memo: MutableMap<String, Fixture3DSource?> = HashMap()
): Fixture3DSource {
    val t = resolver.resolveSpec(overrides.effectiveSpec(o))
    val m = t?.model3D
    if (t != null && m != null && store != null) {
        val hit = memo.getOrPut(t.id) {
            when (m) {
                is com.minou.mvrviewer.sync.CustomFixtureModel3D.ModelBlob ->
                    if (store.isAvailable(m)) Fixture3DSource.Model(m) else null
                else -> Fixture3DStore.renderKey(m)
                    ?.takeIf { gdtfOverrides.map.containsKey(it) }?.let { Fixture3DSource.Gdtf(it) }
            }
        }
        if (hit != null) return hit
    }
    val s = effectiveGeometrySpec(o, overrides, resolver)?.trim()
    return if (s.isNullOrEmpty()) Fixture3DSource.None else Fixture3DSource.Gdtf(s)
}

/** Groupe de rendu d'un objet 3D (d) : même fichier, même unité, même axe, même format. */
fun fixtureModelGroupKey(m: com.minou.mvrviewer.sync.CustomFixtureModel3D.ModelBlob): String =
    "${m.sha256}|${m.unitScaleToMm}|${m.yUp}|${m.format}"

/**
 * Sources 3D de TOUS les projecteurs (§4.6), objets 3D (d) VÉRIFIÉS : chaque groupe
 * ([fixtureModelGroupKey]) est préparé UNE fois par [prepare] (décodage + géométrie, ou
 * instanciation glTF) AVANT que l'appelant n'écarte la géométrie propre des projecteurs.
 * Un échec (raison renvoyée par [prepare]) est retenu dans le store
 * ([Fixture3DStore.markUndecodable] → statut « 3D indisponible » + raison) et ses
 * projecteurs RETOMBENT sur la règle actuelle (géométrie propre, base, GDTF d'origine) —
 * au lieu de perdre leur géométrie pour un cube, voire de devenir invisibles.
 */
suspend fun fixture3DSourcesChecked(
    fixtures: List<MvrSceneObject>,
    overrides: PatchOverrides,
    resolver: CustomFixtureResolver,
    store: Fixture3DStore?,
    gdtfOverrides: GdtfOverrides,
    /** (clé de groupe, objet, nb de projecteurs) → null si prêt, sinon la raison de l'échec. */
    prepare: suspend (String, com.minou.mvrviewer.sync.CustomFixtureModel3D.ModelBlob, Int) -> Fixture3DReason?
): List<Fixture3DSource> {
    val memo = HashMap<String, Fixture3DSource?>()
    val first = fixtures.map { fixture3DSource(it, overrides, resolver, store, gdtfOverrides, memo) }
    val groups = LinkedHashMap<String, Pair<com.minou.mvrviewer.sync.CustomFixtureModel3D.ModelBlob, Int>>()
    for (s in first) if (s is Fixture3DSource.Model) {
        val k = fixtureModelGroupKey(s.model)
        groups[k] = s.model to ((groups[k]?.second ?: 0) + 1)
    }
    var failed = false
    for ((k, p) in groups) {
        val reason = prepare(k, p.first, p.second) ?: continue
        store?.markUndecodable(p.first, reason)
        failed = true
    }
    if (!failed) return first
    // Les objets refusés ne sont plus « disponibles » : on réévalue (repli §4.6).
    memo.clear()
    return fixtures.map { fixture3DSource(it, overrides, resolver, store, gdtfOverrides, memo) }
}

/** Libellé de la 3D d'un type (§5.10) : GDTF Share (rid N) / GDTF importé (f) / Objet 3D (f) / GDTF du show (s) / formes génériques. */
@androidx.compose.runtime.Composable
fun type3DLabel(t: CustomFixtureTypeDTO): String = when (val m = t.model3D) {
    is com.minou.mvrviewer.sync.CustomFixtureModel3D.GdtfShare ->
        androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_3d_label_share, m.rid)
    is com.minou.mvrviewer.sync.CustomFixtureModel3D.GdtfBlob ->
        androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_3d_label_gdtf, m.fileName)
    is com.minou.mvrviewer.sync.CustomFixtureModel3D.ModelBlob ->
        androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_3d_label_object, m.fileName)
    null -> if (t.baseGdtfSpec.isNotBlank())
        androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_3d_label_show, t.baseGdtfSpec.removeSuffix(".gdtf"))
    else androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_3d_label_none)
}

/** Libellé lisible d'un format MA (`grandMA3`, `grandMA2`, `GDTF`, sinon « ? »). */
@androidx.compose.runtime.Composable
fun maFormatLabel(format: String): String = when (format) {
    "ma3" -> androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_fmt_ma3)
    "ma2" -> androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_fmt_ma2)
    "gdtf" -> androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_fmt_gdtf)
    else -> "?"
}

/**
 * Sous-titre d'un type dans les listes (§5.9) : sans `profile` ni `model3D` → null
 * (affichage existant inchangé) ; sinon `%1$d ch · %2$@ · 3D : %3$@` (format MA) ou
 * `%1$d ch · 3D : %2$@`.
 */
@androidx.compose.runtime.Composable
fun maTypeSubtitle(t: CustomFixtureTypeDTO): String? {
    if (t.profile == null && t.model3D == null) return null
    val fp = maxOf(1, t.footprint)
    val label = type3DLabel(t)
    return t.profile?.let {
        androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_type_sub, fp, maFormatLabel(it.format), label)
    } ?: androidx.compose.ui.res.stringResource(com.minou.mvrviewer.R.string.ma_type_sub_noprofile, fp, label)
}

/** Type custom RÉSOLU d'un projecteur (empreinte/canaux/nom), ou null si non-custom. */
fun customTypeOf(
    o: MvrSceneObject, overrides: PatchOverrides, resolver: CustomFixtureResolver
): CustomFixtureTypeDTO? = resolver.resolveSpec(overrides.effectiveSpec(o))
