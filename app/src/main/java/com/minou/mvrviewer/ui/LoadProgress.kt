package com.minou.mvrviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ÉTAPES NOMMÉES DU CHARGEMENT D'UN .mvr (lot A #4).
 *
 * Jusqu'ici l'ouverture n'affichait qu'un message générique : l'utilisateur ne
 * savait pas CE QUI chargeait. Chaque étape porte maintenant un LIBELLÉ FIGÉ
 * (vocabulaire commun iOS/Android — ne pas en inventer d'autres) et une TRANCHE
 * du pourcentage GLOBAL : le % va de 0 à 100 sur TOUT le chargement, il ne
 * repart jamais de zéro d'une étape à l'autre.
 *
 * Découpage des tranches = poids observé du pipeline Android réel :
 * lecture/parse XML (court) → prep CPU des géométries → construction du graphe →
 * silhouettes GDTF (le plus long sur un gros show) → plan DXF (optionnel).
 */
enum class LoadStep(@androidx.annotation.StringRes val labelRes: Int, val from: Float, val to: Float) {
    /** Ouverture/décompression de l'archive + parse du XML de scène. */
    READ_MVR(com.minou.mvrviewer.R.string.load_read_mvr, 0f, 0.10f),
    /** Décodage des géométries : .3ds, .glb/glTF, textures. */
    GEOMETRY(com.minou.mvrviewer.R.string.load_geometry, 0.10f, 0.40f),
    /** Assemblage du graphe, matériaux, LOD, préparation du rendu. */
    BUILD(com.minou.mvrviewer.R.string.load_build, 0.40f, 0.65f),
    /** Extraction/parse des .gdtf : géométries de projecteurs. */
    GDTF(com.minou.mvrviewer.R.string.load_gdtf, 0.65f, 0.90f),
    /** Préparation de la vue plan / du DXF de repère (si le projet en a un). */
    PLAN(com.minou.mvrviewer.R.string.load_plan, 0.90f, 1.00f)
}

/**
 * Avancement OBSERVABLE du chargement, partagé entre l'écran d'accueil (phase
 * lecture/parse) et la vue 3D (phase construction) — deux surfaces d'affichage,
 * UN seul pourcentage global.
 *
 * PERFORMANCE : [report] n'écrit l'état Compose que si le LIBELLÉ ou le
 * pourcentage ENTIER change. Les appelants peuvent donc le solliciter à la
 * cadence existante des messages de statut (tous les 40 objets, une fois par
 * lot .glb…) sans provoquer de recomposition par objet — la règle reste de ne
 * JAMAIS l'appeler par objet.
 *
 * MONOTONIE : le pourcentage ne redescend jamais (`coerceIn(percent, 100)`) ;
 * seul [restart] le remet à zéro, pour un NOUVEAU chargement.
 */
class LoadProgress {
    /** Étape en cours, ou null = aucun chargement en cours (repos / terminé). */
    var step by mutableStateOf<LoadStep?>(null)
        private set

    /** Pourcentage GLOBAL 0..100 (toutes étapes confondues). */
    var percent by mutableIntStateOf(0)
        private set

    /** Un chargement est en cours → les vues affichent barre + libellé d'étape. */
    val active: Boolean get() = step != null

    /**
     * Signale l'avancement : [s] = étape en cours, [frac] = avancement DANS
     * cette étape (0..1). Le pourcentage global est interpolé dans la tranche
     * de l'étape, puis borné vers le bas par la valeur courante.
     */
    fun report(s: LoadStep, frac: Float = 0f) {
        val p = ((s.from + (s.to - s.from) * frac.coerceIn(0f, 1f)) * 100f)
            .toInt().coerceIn(percent, 100)
        if (s != step) step = s
        if (p != percent) percent = p
    }

    /** Chargement terminé : 100 % puis plus d'étape (les vues repassent au titre normal). */
    fun finish() {
        percent = 100
        step = null
    }

    /** Nouveau chargement : remet le compteur à zéro (seul cas de retour en arrière). */
    fun restart() {
        percent = 0
        step = null
    }
}
