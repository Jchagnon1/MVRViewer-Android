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
    MASK, SHOW_ALL, CLEAR_SOLO, CALIBRATE, CALIBRATE_3, EXPORT_PDF, DXF, STRUCTURE, LEGEND, COLOR_MODE, LABEL_SETTINGS,
    // Rangement des menus (SPEC-menus-android §4.6) — chacun créé dans sa tranche.
    /** Panneau « Position GPS » (tranche B), 3D et plan. */
    GEO_PANEL,
    /**
     * Multi-sélection (tranche C) : le bouton permanent de la rangée du haut se
     * masque quand l'outil est ancré — l'outil naît donc avec le bouton.
     */
    MULTI_SELECT,
    /**
     * Ouvre / referme le panneau « Plan importé » (tranche E) : l'outil [DXF]
     * (« importDXF ») ne fait plus qu'importer, à libellé fixe.
     */
    REF_PLAN_PANEL,
    /** Ouvre / referme le panneau « Modèles importés » (tranche E) ; [IMPORT_MODEL] n'importe plus que. */
    MODELS_PANEL,
    /**
     * « Modèles de projecteurs » (tranche E, iOS `gdtfMapping`). [GDTF_SHARE]
     * prend le sens iOS (la CONNEXION GDTF Share, dans Compte & réglages) ; la
     * migration v2 des dispositions remplace l'ancien `gdtfShare` Android (qui
     * ouvrait les modèles) par celui-ci (SPEC-menus-android §3.13, §8 D24).
     */
    GDTF_MAPPING;

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
            // Calibration guidée en 3 points : valeur de `PlanStartAction` iOS.
            CALIBRATE_3 -> "threePointCalibration"
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
            // Valeur iOS (`ToolId.geoPanel`).
            GEO_PANEL -> "geoPanel"
            // Valeur iOS (`ToolId.multiSelect`).
            MULTI_SELECT -> "multiSelect"
            // Valeurs iOS (`ToolId.refPlanPanel`, `.modelsPanel`, `.gdtfMapping`).
            REF_PLAN_PANEL -> "refPlanPanel"
            MODELS_PANEL -> "modelsPanel"
            GDTF_MAPPING -> "gdtfMapping"
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

    /** Aucune barre : « Tout remettre hors barres », ou le défaut du téléphone. */
    val isEmpty: Boolean get() = top.isEmpty() && bottom.isEmpty() && left.isEmpty() && right.isEmpty()

    /**
     * Remplace [old] par [new] À SA PLACE (même barre, même rang) ; si [new] est
     * déjà placé ailleurs, [old] est seulement retiré (jamais de doublon). Sert à
     * la migration v2 (`gdtfShare` → `gdtfMapping`).
     */
    fun replacing(old: ToolId, new: ToolId): ToolbarLayout {
        if (edgeOf(old) == null) return this
        val keepNew = edgeOf(new) == null
        fun r(l: List<ToolId>) = l.mapNotNull { if (it == old) new.takeIf { keepNew } else it }
        return ToolbarLayout(r(top), r(bottom), r(left), r(right))
    }

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
        /** Aucune barre (« Tout remettre hors barres », défaut du téléphone). */
        val EMPTY = ToolbarLayout()

        /**
         * Barre TABLETTE préremplie (SPEC-menus-android §3.13, décision 9, Q1) :
         * à GAUCHE, dans cet ordre, la même dans les deux vues (iOS `padDefault`).
         */
        val tabletDefault = ToolbarLayout(
            left = listOf(ToolId.PATCH, ToolId.UNIVERSE, ToolId.CABLING, ToolId.LAYERS)
        )

        /**
         * Disposition par défaut quand RIEN n'est enregistré pour cette vue :
         * tablette → barre gauche préremplie ; téléphone → VIDE. La barre
         * flottante historique est RETIRÉE (§9.0, Q1) : chaque outil a une autre
         * place ([ToolId.home]) et reste ancrable.
         */
        @Suppress("UNUSED_PARAMETER")
        fun defaultLayout(view: ToolbarView, idiom: ToolbarIdiom): ToolbarLayout = when (idiom) {
            ToolbarIdiom.TABLET -> tabletDefault
            ToolbarIdiom.PHONE -> EMPTY
        }

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
