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
 * Catalogue d'outils STABLE. Superset des deux vues ; chaque écran n'expose que
 * son sous-ensemble via ses [ToolSpec]. Volontairement SANS « mode de rendu » :
 * la vue 3D Android n'en a aucun (renderQuality figé à Performance).
 *
 * HARMONISATION iOS/Android (N11) : l'identité PERSISTÉE d'un outil n'est PAS le
 * nom d'énumération Kotlin (`enum.name`, en UPPER_SNAKE) mais une CHAÎNE camelCase
 * canonique — la MÊME que la `rawValue` du `ToolId` Swift (voir [rawId]). Ainsi une
 * disposition encodée sur une plateforme se relit à l'identique sur l'autre. Ne
 * jamais changer une [rawId] existante sans migration.
 */
enum class ToolId {
    // Communs 3D + plan
    RECT, MEASURE, SOLO, CLEAR_SEL, GPS, SATELLITE, LABELS, LAYER_COLORS, BACKGROUND, SEARCH,
    // Spécifiques 3D
    GPS_MARKER_SIZE, CAM_PRESETS, CAM_RESET, LABEL_SIZE, LAYERS, IMPORT_MODEL,
    // Navigation / actions dockables (vue 3D) — présentes aussi au menu
    PATCH, UNIVERSE, CABLING, GDTF_SHARE, PLAN_VIEW, ACCOUNT, SHARE_PROJECT, HISTORY,
    // Spécifiques plan
    MASK, SHOW_ALL, CLEAR_SOLO, CALIBRATE, EXPORT_PDF, DXF, STRUCTURE, LEGEND, COLOR_MODE, LABEL_SETTINGS;

    /**
     * Identifiant STABLE persisté (JSON) — MÊME chaîne camelCase que la `rawValue`
     * du `ToolId` iOS pour les outils COMMUNS, et même convention camelCase pour
     * les outils propres à Android. C'est CETTE valeur qui est écrite/relue, pas
     * [name].
     */
    val rawId: String
        get() = when (this) {
            // Communs (rawValues iOS)
            RECT -> "rectSelect"
            MEASURE -> "measure"
            SOLO -> "solo"
            LABELS -> "labels"
            LAYER_COLORS -> "layerColors"
            BACKGROUND -> "backgroundColor"
            LABEL_SIZE -> "labelSize"
            LABEL_SETTINGS -> "labelSettings"
            MASK -> "mask"
            EXPORT_PDF -> "exportPDF"
            CAM_RESET -> "resetCamera"
            DXF -> "importDXF"
            PATCH -> "patchList"
            UNIVERSE -> "universe"
            CABLING -> "cabling"
            GDTF_SHARE -> "gdtfShare"
            PLAN_VIEW -> "planView"
            ACCOUNT -> "account"
            SHARE_PROJECT -> "shareProject"
            HISTORY -> "history"
            // Communs / propres à Android suivant la même convention camelCase
            GPS -> "myLocation"
            SATELLITE -> "satellite"
            CALIBRATE -> "calibrate"
            SHOW_ALL -> "showAll"
            CLEAR_SOLO -> "clearSolo"
            CLEAR_SEL -> "clearSelection"
            SEARCH -> "search"
            GPS_MARKER_SIZE -> "gpsMarkerSize"
            LAYERS -> "layers"
            CAM_PRESETS -> "cameraPresets"
            // Import d'un modèle 3D de décor (chantier #3) — rawId STABLE : c'est
            // lui qui est persisté dans la disposition des barres.
            IMPORT_MODEL -> "importModel"
            STRUCTURE -> "structure"
            LEGEND -> "legend"
            COLOR_MODE -> "colorMode"
        }

    companion object {
        private val byRawId: Map<String, ToolId> = entries.associateBy { it.rawId }
        /** Décode une [rawId] persistée, ou null si inconnue (robustesse au catalogue). */
        fun fromRawId(raw: String): ToolId? = byRawId[raw]
    }
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

    /**
     * Encodage JSON stable {top:[rawIds],bottom:[…],left:[…],right:[…]} (org.json).
     * Les ids sont les CHAÎNES canoniques camelCase ([ToolId.rawId]) — identiques à
     * iOS — et NON le nom d'énumération Kotlin.
     */
    fun toJson(): String {
        fun arr(l: List<ToolId>) = JSONArray().apply { l.forEach { put(it.rawId) } }
        return JSONObject()
            .put("top", arr(top)).put("bottom", arr(bottom))
            .put("left", arr(left)).put("right", arr(right))
            .toString()
    }

    companion object {
        /**
         * DÉFAUT = VIDE (modèle iOS N11, invariant de non-régression). Au premier
         * lancement AUCUNE barre ancrée n'est affichée : c'est la BARRE FLOTTANTE
         * historique ([floating3D]) qui reste à l'écran, inchangée. Un outil
         * n'apparaît dans une barre ancrée QUE lorsque l'utilisateur l'y place — il
         * disparaît alors de la barre flottante (gating, cf. [allAssigned]).
         */
        val default3D = ToolbarLayout()

        /** Défaut plan = VIDE (idem : la barre flottante [floatingPlan] tient lieu de défaut). */
        val defaultPlan = ToolbarLayout()

        /**
         * BARRE FLOTTANTE historique de la vue 3D (l'ancienne barre bas-gauche).
         * Rendue par défaut ; chaque outil en est masqué s'il a été placé dans une
         * barre ancrée (jamais le même outil en double flottant/ancré).
         */
        val floating3D = listOf(
            ToolId.RECT, ToolId.MEASURE, ToolId.SOLO, ToolId.CLEAR_SEL,
            ToolId.GPS, ToolId.GPS_MARKER_SIZE
        )

        /** BARRE FLOTTANTE historique de la vue plan (l'ancienne barre bas-gauche). */
        val floatingPlan = listOf(
            ToolId.RECT, ToolId.MASK, ToolId.SOLO, ToolId.MEASURE, ToolId.SHOW_ALL,
            ToolId.CLEAR_SOLO, ToolId.CLEAR_SEL, ToolId.GPS, ToolId.CALIBRATE,
            ToolId.SATELLITE, ToolId.EXPORT_PDF, ToolId.DXF
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
                        // rawId canonique (camelCase, commun iOS) — pas enum.name.
                        runCatching { ToolId.fromRawId(a.getString(i)) }.getOrNull()
                    }.filter { seen.add(it) } // pas de doublon inter-barres
                }
                // Ordre de dédoublonnage : haut → bas → gauche → droite.
                ToolbarLayout(ids("top"), ids("bottom"), ids("left"), ids("right"))
            }.getOrDefault(fallback)
        }
    }
}
