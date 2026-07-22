package com.minou.mvrviewer.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * OUTIL DE CÂBLAGE — phase 3 : CÂBLAGE DMX (lignes DMX simples + multipaires).
 *
 * Miroir EXACT de la Socapex (phase 2, [PowerCabling]) mais pour le DMX : ce
 * fichier ne connaît PAS la résolution des modes GDTF, il reçoit un `channelsOf`
 * (uuid → empreinte DMX connue, ou null) et un `universeOf` (uuid → univers de
 * patch, ou null) et se contente de RÉPARTIR les projecteurs sur des lignes/paires
 * et de CALCULER les empreintes cumulées — le tout de façon pure et testable.
 *
 * CONTRAT DE DONNÉES PARTAGÉ iOS/Android — section de synchro « dmxCabling »,
 * miroir EXACT du patch (même enveloppe {"kind":{"_0":dto}}, cf. [SectionCodec]).
 * Le JSON de la section :
 *
 *   {
 *     "distributors": [
 *       { "id":"<uuid>", "name":"DMX 1", "kind":"simple"|"multi",
 *         "colorArgb":<int ARGB>, "cores":<int> }
 *         // simple → cores = 1 ; multi (multipaire DMX) → cores = N (défaut 4)
 *     ],
 *     "assignments": [
 *       { "fixture":"<mvrUUID>", "distributor":"<uuid>", "core":1 } // core 1-based
 *     ]
 *   }
 *
 * Les noms de champs ci-dessus sont un CONTRAT : ne pas les renommer d'un seul
 * côté sous peine que iOS ne décode plus ce qu'Android pousse (et inversement).
 */

/** Type de ligne DMX : simple (1 départ) ou multipaire DMX (N paires). */
enum class DmxDistributorKind(val raw: String) {
    SIMPLE("simple"), MULTI("multi");

    companion object {
        fun from(raw: String): DmxDistributorKind = entries.firstOrNull { it.raw == raw } ?: SIMPLE
    }
}

/** Nombre de départs (paires) par défaut : simple = 1, multipaire = 4 (réglable). */
fun defaultDmxCores(kind: DmxDistributorKind): Int = if (kind == DmxDistributorKind.MULTI) 4 else 1

/**
 * Une ligne DMX (simple ou multipaire). `colorArgb` est un entier ARGB (comme
 * [LayerColors] des deux plateformes). `cores` = nombre de départs : TOUJOURS 1
 * pour une ligne simple, N (≥ 1) pour une multipaire. On expose [coreCount] plutôt
 * que `cores` directement pour qu'une ligne simple ne montre jamais qu'un départ,
 * même si un JSON abîmé lui donnait un `cores` incohérent.
 */
data class DmxDistributor(
    val id: String,
    val name: String,
    val kind: DmxDistributorKind,
    val colorArgb: Int,
    val cores: Int
) {
    /** Nombre effectif de départs : 1 forcé pour une ligne simple, sinon `cores`. */
    val coreCount: Int get() = if (kind == DmxDistributorKind.SIMPLE) 1 else maxOf(1, cores)

    /** Un départ 1-based existe-t-il sur cette ligne ? */
    fun hasCore(core: Int): Boolean = core in 1..coreCount
}

/**
 * Affectation d'un projecteur à un départ. `core` est indexé À PARTIR DE 1.
 * Un projecteur est affecté à AU PLUS un départ (réaffecter le déplace) ; un
 * départ accueille PLUSIEURS projecteurs.
 */
data class DmxCablingAssignment(
    val fixture: String,      // mvrUUID
    val distributor: String,  // id de la ligne
    val core: Int             // 1-based
)

/** DTO complet de la section « dmxCabling » — miroir du JSON du contrat. */
data class DmxCablingDTO(
    val distributors: List<DmxDistributor> = emptyList(),
    val assignments: List<DmxCablingAssignment> = emptyList()
) {
    /**
     * ROBUSTESSE AU REPATCH / À LA SUPPRESSION : rejette silencieusement les
     * affectations dont la cible n'existe plus (projecteur, ligne ou départ
     * inexistant) et fait respecter « au plus un départ par projecteur » (la
     * DERNIÈRE affectation gagne). Appelée à CHAQUE chargement (disque ET
     * distant). Mêmes règles que [PowerCablingDTO.sanitized].
     */
    fun sanitized(validFixtures: Set<String>): DmxCablingDTO {
        // ENSEMBLE VIDE ⇒ ON NE PURGE PAS PAR PROJECTEUR (aligné iOS) : un set vide
        // = « show pas encore chargé » ; filtrer ici effacerait tout à tort.
        val checkFixtures = validFixtures.isNotEmpty()
        val byId = distributors.associateBy { it.id }
        // LinkedHashMap réécrit la clé en place → la DERNIÈRE affectation par
        // projecteur gagne, l'ordre d'insertion initial est conservé.
        val kept = LinkedHashMap<String, DmxCablingAssignment>()
        for (a in assignments) {
            if (checkFixtures && a.fixture !in validFixtures) continue
            val d = byId[a.distributor] ?: continue
            if (!d.hasCore(a.core)) continue
            kept[a.fixture] = a
        }
        return copy(assignments = kept.values.toList())
    }
}

/**
 * Codec JSON du DTO (l'objet INTERNE du contrat, sans l'enveloppe de section).
 * Réutilisé par [SectionCodec] (enveloppe cloud) ET par la persistance projet.
 */
object DmxCablingCodec {

    fun toJson(dto: DmxCablingDTO): JSONObject {
        val dists = JSONArray()
        for (d in dto.distributors) {
            dists.put(JSONObject()
                .put("id", d.id)
                .put("name", d.name)
                .put("kind", d.kind.raw)
                .put("colorArgb", d.colorArgb)
                .put("cores", d.cores))
        }
        val assigns = JSONArray()
        for (a in dto.assignments) {
            assigns.put(JSONObject()
                .put("fixture", a.fixture)
                .put("distributor", a.distributor)
                .put("core", a.core))
        }
        return JSONObject()
            .put("distributors", dists)
            .put("assignments", assigns)
    }

    fun fromJson(o: JSONObject): DmxCablingDTO {
        val dArr = o.optJSONArray("distributors") ?: JSONArray()
        val distributors = (0 until dArr.length()).mapNotNull { i ->
            val d = dArr.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = DmxDistributorKind.from(d.optString("kind"))
            // Une ligne simple est FORCÉE à 1 départ ; une multipaire garde son N
            // (≥ 1), défaut 4 si le champ manque (JSON abîmé).
            val cores = if (kind == DmxDistributorKind.SIMPLE) 1
                else maxOf(1, d.optInt("cores", defaultDmxCores(kind)))
            DmxDistributor(id, d.optString("name"), kind, d.optInt("colorArgb"), cores)
        }
        val aArr = o.optJSONArray("assignments") ?: JSONArray()
        val assignments = (0 until aArr.length()).mapNotNull { i ->
            val a = aArr.optJSONObject(i) ?: return@mapNotNull null
            val fx = a.optString("fixture").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dist = a.optString("distributor").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DmxCablingAssignment(fx, dist, a.optInt("core", 1))
        }
        return DmxCablingDTO(distributors, assignments)
    }

    fun toJsonString(dto: DmxCablingDTO): String = toJson(dto).toString()

    fun fromJsonString(json: String): DmxCablingDTO? =
        runCatching { fromJson(JSONObject(json)) }.getOrNull()
}

// MARK: - Calcul d'empreinte DMX (PUR et testable) ----------------------------

/**
 * Empreinte cumulée d'un départ. `totalChannels` = somme des empreintes DMX
 * CONNUES des projecteurs affectés ; `unknownCount` compte ceux dont l'empreinte
 * n'a pu être résolue (0 canal mais signalé). `universes` = univers de patch
 * DISTINCTS présents sur ce départ. Deux alertes DISCRÈTES (sans blocage) :
 *   - [over512] : le départ dépasse 512 canaux (une ligne DMX = un univers) ;
 *   - [mixedUniverse] : le départ mélange des univers de patch différents.
 */
data class DmxCoreLoad(
    val distributorId: String,
    val core: Int,                  // 1-based
    val fixtures: List<String>,     // mvrUUIDs affectés (ordre stable)
    val totalChannels: Int,         // somme des empreintes CONNUES
    val unknownCount: Int,          // projecteurs sans empreinte connue
    val universes: List<Int>,       // univers de patch distincts (triés)
    val over512: Boolean,           // totalChannels > 512
    val mixedUniverse: Boolean      // universes.size > 1
)

/**
 * Calcul d'empreinte, PUR et testable. `channelsOf` renvoie l'empreinte DMX
 * connue d'un projecteur (par son uuid) ou null (empreinte inconnue → 0 canal
 * mais signalée) ; `universeOf` son univers de patch (ou null si non patché).
 */
object DmxCablingCalc {

    /** Une ligne DMX = un univers = 512 canaux. */
    const val UNIVERSE_CHANNELS = 512

    /** Empreinte cumulée d'un départ précis (1-based) d'une ligne. */
    fun coreLoad(
        dist: DmxDistributor,
        core: Int,
        assignments: List<DmxCablingAssignment>,
        channelsOf: (String) -> Int?,
        universeOf: (String) -> Int?
    ): DmxCoreLoad {
        val fixtures = assignments
            .filter { it.distributor == dist.id && it.core == core }
            .map { it.fixture }
        var total = 0
        var unknown = 0
        val unis = sortedSetOf<Int>()
        for (fx in fixtures) {
            val c = channelsOf(fx)
            if (c == null) unknown++ else total += c
            universeOf(fx)?.let { unis.add(it) }
        }
        return DmxCoreLoad(
            distributorId = dist.id,
            core = core,
            fixtures = fixtures,
            totalChannels = total,
            unknownCount = unknown,
            universes = unis.toList(),
            over512 = total > UNIVERSE_CHANNELS,
            mixedUniverse = unis.size > 1
        )
    }

    /** Les empreintes des N départs d'une ligne, dans l'ordre 1..N. */
    fun coreLoads(
        dist: DmxDistributor,
        assignments: List<DmxCablingAssignment>,
        channelsOf: (String) -> Int?,
        universeOf: (String) -> Int?
    ): List<DmxCoreLoad> =
        (1..dist.coreCount).map { coreLoad(dist, it, assignments, channelsOf, universeOf) }

    /** Total d'une ligne = somme des empreintes connues de tous ses départs. */
    fun distributorTotalChannels(
        dist: DmxDistributor,
        assignments: List<DmxCablingAssignment>,
        channelsOf: (String) -> Int?
    ): Int = assignments
        .filter { it.distributor == dist.id }
        .sumOf { channelsOf(it.fixture) ?: 0 }
}
