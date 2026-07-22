package com.minou.mvrviewer.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * N11 — BARRES D'OUTILS ANCRABLES.
 *
 * Une DISPOSITION = 4 barres ancrées aux bords (haut / bas / gauche / droite),
 * chacune une liste ORDONNÉE d'identifiants d'outils. Deux dispositions
 * INDÉPENDANTES (vue 3D / vue plan), mémorisées séparément (cf.
 * [ToolbarLayoutStore]). Le catalogue [ToolId] est STABLE et couvre les outils
 * actionnables des deux vues — un id inconnu à la relecture est simplement ignoré
 * (robustesse aux évolutions du catalogue). Modèle identique iOS/Android ; seule
 * l'UI diffère.
 */
enum class ToolbarEdge { TOP, BOTTOM, LEFT, RIGHT }

/**
 * Catalogue d'outils STABLE (les noms servent de clés persistées — NE PAS
 * renommer sans migration). Superset des deux vues ; chaque écran n'expose que
 * son sous-ensemble via ses [ToolSpec]. Volontairement SANS « mode de rendu » :
 * la vue 3D Android n'en a aucun (renderQuality figé à Performance).
 */
enum class ToolId {
    // Communs 3D + plan
    RECT, MEASURE, SOLO, CLEAR_SEL, GPS, SATELLITE, LABELS, LAYER_COLORS, BACKGROUND, SEARCH,
    // Spécifiques 3D
    GPS_MARKER_SIZE, CAM_PRESETS, CAM_RESET,
    // Spécifiques plan
    MASK, SHOW_ALL, CLEAR_SOLO, CALIBRATE, EXPORT_PDF, DXF, STRUCTURE, LEGEND, COLOR_MODE
}

/**
 * Disposition des 4 barres. Immuable : chaque édition renvoie une COPIE (les
 * helpers [moved] / [reordered] retirent d'abord l'outil de tous les bords, si
 * bien qu'un outil n'est jamais dans deux barres). Défauts = reproduisent la
 * barre bas-gauche actuelle → aucune régression sans personnalisation.
 */
data class ToolbarLayout(
    val top: List<ToolId> = emptyList(),
    val bottom: List<ToolId> = emptyList(),
    val left: List<ToolId> = emptyList(),
    val right: List<ToolId> = emptyList()
) {
    fun forEdge(e: ToolbarEdge): List<ToolId> = when (e) {
        ToolbarEdge.TOP -> top
        ToolbarEdge.BOTTOM -> bottom
        ToolbarEdge.LEFT -> left
        ToolbarEdge.RIGHT -> right
    }

    /** Bord où se trouve [id], ou null = « hors barres » (accessible au menu seul). */
    fun edgeOf(id: ToolId): ToolbarEdge? = ToolbarEdge.entries.firstOrNull { id in forEdge(it) }

    fun allAssigned(): Set<ToolId> = (top + bottom + left + right).toSet()

    private fun withEdge(e: ToolbarEdge, list: List<ToolId>): ToolbarLayout = when (e) {
        ToolbarEdge.TOP -> copy(top = list)
        ToolbarEdge.BOTTOM -> copy(bottom = list)
        ToolbarEdge.LEFT -> copy(left = list)
        ToolbarEdge.RIGHT -> copy(right = list)
    }

    /**
     * Déplace [id] vers [edge] (null = hors barres). Retiré d'abord de TOUS les
     * bords (jamais de doublon inter-barres), puis ajouté en fin du bord cible
     * (préserve l'ordre des autres).
     */
    fun moved(id: ToolId, edge: ToolbarEdge?): ToolbarLayout {
        val cleared = ToolbarLayout(top - id, bottom - id, left - id, right - id)
        return if (edge == null) cleared else cleared.withEdge(edge, cleared.forEdge(edge) + id)
    }

    /**
     * Réordonne [id] DANS sa barre de ±1 (delta = −1 monter, +1 descendre).
     * No-op si [id] est hors barres ou déjà en butée.
     */
    fun reordered(id: ToolId, delta: Int): ToolbarLayout {
        val e = edgeOf(id) ?: return this
        val list = forEdge(e).toMutableList()
        val i = list.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return this
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        return withEdge(e, list)
    }

    /** Encodage JSON stable {top:[names],bottom:[…],left:[…],right:[…]} (org.json). */
    fun toJson(): String {
        fun arr(l: List<ToolId>) = JSONArray().apply { l.forEach { put(it.name) } }
        return JSONObject()
            .put("top", arr(top)).put("bottom", arr(bottom))
            .put("left", arr(left)).put("right", arr(right))
            .toString()
    }

    companion object {
        /** Défaut 3D = barre bas-gauche actuelle (rien ne casse). */
        val default3D = ToolbarLayout(
            bottom = listOf(
                ToolId.RECT, ToolId.MEASURE, ToolId.SOLO, ToolId.CLEAR_SEL,
                ToolId.GPS, ToolId.GPS_MARKER_SIZE
            )
        )

        /** Défaut plan = barre bas-gauche actuelle (rien ne casse). */
        val defaultPlan = ToolbarLayout(
            bottom = listOf(
                ToolId.RECT, ToolId.MASK, ToolId.SOLO, ToolId.MEASURE, ToolId.SHOW_ALL,
                ToolId.CLEAR_SOLO, ToolId.CLEAR_SEL, ToolId.GPS, ToolId.CALIBRATE,
                ToolId.SATELLITE, ToolId.EXPORT_PDF, ToolId.DXF
            )
        )

        /**
         * Décode une disposition JSON, en TOLÉRANT les ids inconnus (ignorés) et
         * les doublons inter-barres (première occurrence gardée). Retourne
         * [fallback] si la chaîne est vide/illisible.
         */
        fun fromJson(raw: String?, fallback: ToolbarLayout): ToolbarLayout {
            if (raw.isNullOrBlank()) return fallback
            return runCatching {
                val o = JSONObject(raw)
                val seen = HashSet<ToolId>()
                fun ids(name: String): List<ToolId> {
                    val a = o.optJSONArray(name) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { i ->
                        runCatching { ToolId.valueOf(a.getString(i)) }.getOrNull()
                    }.filter { seen.add(it) } // pas de doublon inter-barres
                }
                // Ordre de dédoublonnage : haut → bas → gauche → droite.
                ToolbarLayout(ids("top"), ids("bottom"), ids("left"), ids("right"))
            }.getOrDefault(fallback)
        }
    }
}
