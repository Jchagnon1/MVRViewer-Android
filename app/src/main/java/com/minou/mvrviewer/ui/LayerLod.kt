package com.minou.mvrviewer.ui

/**
 * RÉGLAGE DU LOD D'INTERACTION PAR CALQUE (lot A #1).
 *
 * Le LOD d'interaction masque du détail PENDANT la navigation (geste caméra)
 * pour rester fluide, puis le rétablit à l'arrêt. Jusqu'ici la décision était
 * entièrement automatique ; l'utilisateur veut pouvoir la forcer par calque
 * (« ce pont doit rester visible quand je tourne », « ce décor peut disparaître »).
 *
 * Ces réglages n'affectent QUE le LOD d'interaction : ils ne changent RIEN à la
 * visibilité au repos. Un masquage (solo, calque masqué) reste prioritaire.
 *
 * Libellés FIGÉS (identiques iOS/Android) — ne pas les reformuler.
 */
enum class LayerLodMode(val label: String, val stored: String) {
    /** Comportement actuel : le LOD décide seul. DÉFAUT de tous les calques. */
    AUTO("Auto", ""),
    /** Jamais dégradé ni masqué par le LOD pendant la navigation. */
    ALWAYS("Toujours visible", "always"),
    /** Toujours masqué pendant le geste ; réapparaît à l'arrêt. */
    HIDE_NAV("Masquer en navigation", "hideNav");

    companion object {
        /** Décode une valeur persistée ; inconnue ou absente → [AUTO]. */
        fun fromStored(raw: String?): LayerLodMode =
            entries.firstOrNull { it != AUTO && it.stored == raw } ?: AUTO
    }
}

/** Mode d'un calque, AUTO par défaut (un projet sans réglage s'ouvre en Auto partout). */
fun Map<String, LayerLodMode>.lodMode(layer: String): LayerLodMode = this[layer] ?: LayerLodMode.AUTO

/**
 * Élément suivi par le LOD, INDÉPENDANT du moteur 3D (donc testable sans
 * Filament). [autoCandidate] = ce que le LOD automatique masquerait de lui-même
 * (petit décor .3ds, silhouette GDTF d'un projecteur) ; les gros décors .glb, eux,
 * ne sont candidats qu'à la demande explicite « Masquer en navigation ».
 */
class LodItem<T>(val node: T, val layer: String, val autoCandidate: Boolean)

/** Les deux listes PLATES consommées telles quelles par la boucle de rendu. */
class LodBuckets<T>(val hideOnMove: List<T>, val showOnMove: List<T>)

/** Un élément est-il masqué pendant le geste, selon le mode de son calque ? */
fun lodHidesOnMove(mode: LayerLodMode, autoCandidate: Boolean): Boolean = when (mode) {
    LayerLodMode.ALWAYS -> false        // jamais dégradé
    LayerLodMode.HIDE_NAV -> true       // toujours masqué en navigation
    LayerLodMode.AUTO -> autoCandidate  // comportement historique
}

/**
 * Le cube de repli d'un projecteur s'affiche-t-il pendant le geste ?
 * Seulement en AUTO : « Toujours visible » garde le vrai détail (le cube ferait
 * doublon), « Masquer en navigation » ne doit RIEN montrer de ce calque.
 */
fun lodShowsProxy(mode: LayerLodMode): Boolean = mode == LayerLodMode.AUTO

/**
 * Répartit les éléments suivis en deux listes plates, selon le mode de chaque
 * calque. Fonction PURE : c'est elle qu'on teste (et elle garantit qu'avec tout
 * en « Auto » on retrouve EXACTEMENT les listes d'avant le réglage).
 */
fun <T> computeLodBuckets(
    entries: List<LodItem<T>>,
    proxies: List<LodItem<T>>,
    modes: Map<String, LayerLodMode>
): LodBuckets<T> {
    val hide = ArrayList<T>(entries.size)
    for (e in entries) if (lodHidesOnMove(modes.lodMode(e.layer), e.autoCandidate)) hide.add(e.node)
    val show = ArrayList<T>(proxies.size)
    for (p in proxies) if (lodShowsProxy(modes.lodMode(p.layer))) show.add(p.node)
    return LodBuckets(hide, show)
}
