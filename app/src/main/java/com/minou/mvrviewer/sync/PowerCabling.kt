package com.minou.mvrviewer.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * OUTIL DE CÂBLAGE — phase 2 : CÂBLAGE ÉLECTRIQUE (Socapex + lignes solo PC16).
 *
 * S'appuie sur la conso W de la phase 1 ([PowerLibrary]/[resolvePower]) : ce
 * fichier ne connaît PAS la résolution des puissances, il reçoit un `wattsOf`
 * (uuid → W connus ou null) et se contente de RÉPARTIR les projecteurs sur des
 * distributeurs et de CALCULER les charges — le tout de façon pure et testable.
 *
 * CONTRAT DE DONNÉES PARTAGÉ iOS/Android — section de synchro « powerCabling »,
 * miroir EXACT du patch (même enveloppe {"kind":{"_0":dto}}, cf. [SectionCodec]).
 * Le JSON de la section :
 *
 *   {
 *     "settings": { "voltage": 230, "circuitLimitW": 3680, "phaseLimitW": 0 },
 *     "distributors": [
 *       { "id":"<uuid>", "name":"Soca 1", "kind":"soca", "colorArgb":<int ARGB>,
 *         "circuitPhases":[1,1,2,2,3,3] },   // 6 circuits, phase L1|L2|L3 de chacun
 *       { "id":"<uuid>", "name":"Ligne A", "kind":"solo", "colorArgb":<int ARGB>,
 *         "circuitPhases":[1] }              // ligne PC16 = 1 seul circuit
 *     ],
 *     "assignments": [
 *       { "fixture":"<mvrUUID>", "distributor":"<uuid>", "circuit":1 } // circuit 1-based
 *     ]
 *   }
 *
 * Les noms de champs ci-dessus sont un CONTRAT : ne pas les renommer d'un seul
 * côté sous peine que iOS ne décode plus ce qu'Android pousse (et inversement).
 */

/**
 * Réglages du câblage. `voltage` est informatif (230 V) ; `circuitLimitW` est la
 * limite d'un circuit (16 A × 230 V = 3680 W) ; `phaseLimitW` est l'arrivée par
 * phase (0 = non définie → aucune alerte phase tant qu'elle n'est pas réglée).
 */
data class CablingSettings(
    val voltage: Int = 230,
    val circuitLimitW: Int = 3680,
    val phaseLimitW: Int = 0
)

/** Type de distributeur : Socapex (6 circuits) ou ligne solo PC16 (1 circuit). */
enum class DistributorKind(val raw: String) {
    SOCA("soca"), SOLO("solo");

    companion object {
        fun from(raw: String): DistributorKind = entries.firstOrNull { it.raw == raw } ?: SOCA
    }
}

/** Nombre de circuits par défaut selon le type de distributeur. */
fun defaultCircuitCount(kind: DistributorKind): Int = if (kind == DistributorKind.SOCA) 6 else 1

/**
 * Mappage circuit → phase (L1/L2/L3) par défaut. Socapex : 1-2 → L1, 3-4 → L2,
 * 5-6 → L3 (câblage standard). Solo : le circuit unique sur L1. Réglable ensuite.
 */
fun defaultCircuitPhases(kind: DistributorKind): List<Int> =
    if (kind == DistributorKind.SOCA) listOf(1, 1, 2, 2, 3, 3) else listOf(1)

/**
 * Un distributeur (Socapex ou ligne solo). `colorArgb` est un entier ARGB (comme
 * [LayerColors] des deux plateformes). `circuitPhases` porte la phase de CHAQUE
 * circuit : sa taille = le nombre de circuits (6 soca, 1 solo).
 */
data class Distributor(
    val id: String,
    val name: String,
    val kind: DistributorKind,
    val colorArgb: Int,
    val circuitPhases: List<Int>
) {
    val circuitCount: Int get() = circuitPhases.size

    /**
     * Phase (1|2|3) d'un circuit 1-based. Un index hors bornes retombe sur la
     * phase 1 (valeur VALIDE) plutôt que 0 (phase inexistante) — aligné iOS, pour
     * qu'un circuit fantôme transitoire ne casse ni l'équilibrage ni l'affichage.
     */
    fun phaseOf(circuit: Int): Int = circuitPhases.getOrNull(circuit - 1) ?: 1

    /** Un circuit 1-based existe-t-il sur ce distributeur ? */
    fun hasCircuit(circuit: Int): Boolean = circuit in 1..circuitCount
}

/**
 * Affectation d'un projecteur à un circuit. `circuit` est indexé À PARTIR DE 1.
 * Un projecteur est affecté à AU PLUS un circuit (réaffecter le déplace) ; un
 * circuit accueille PLUSIEURS projecteurs.
 */
data class CablingAssignment(
    val fixture: String,      // mvrUUID
    val distributor: String,  // id du distributeur
    val circuit: Int          // 1-based
)

/** DTO complet de la section « powerCabling » — miroir du JSON du contrat. */
data class PowerCablingDTO(
    val settings: CablingSettings = CablingSettings(),
    val distributors: List<Distributor> = emptyList(),
    val assignments: List<CablingAssignment> = emptyList()
) {
    /**
     * ROBUSTESSE AU REPATCH / À LA SUPPRESSION : rejette silencieusement les
     * affectations dont la cible n'existe plus (projecteur, distributeur ou
     * circuit inexistant) et fait respecter « au plus un circuit par projecteur »
     * (la DERNIÈRE affectation gagne). Appelée à CHAQUE chargement (disque ET
     * distant) — sans elle, un projecteur supprimé du show ou un distributeur
     * effacé laisserait une affectation fantôme faussant les charges.
     */
    fun sanitized(validFixtures: Set<String>): PowerCablingDTO {
        // ENSEMBLE VIDE ⇒ ON NE PURGE PAS PAR PROJECTEUR (aligné iOS). Un set de
        // projecteurs connus vide signifie « show pas encore chargé » (moment
        // transitoire pré-chargement) : filtrer ici effacerait toutes les
        // affectations à tort. On garde donc tout côté projecteur dans ce cas, et
        // on ne valide que distributeur + circuit.
        val checkFixtures = validFixtures.isNotEmpty()
        val byId = distributors.associateBy { it.id }
        // On parcourt en gardant la DERNIÈRE affectation par projecteur (un
        // LinkedHashMap réécrit la clé en place → l'ordre d'insertion initial est
        // conservé, la valeur est la plus récente).
        val kept = LinkedHashMap<String, CablingAssignment>()
        for (a in assignments) {
            if (checkFixtures && a.fixture !in validFixtures) continue
            val d = byId[a.distributor] ?: continue
            if (!d.hasCircuit(a.circuit)) continue
            kept[a.fixture] = a
        }
        return copy(assignments = kept.values.toList())
    }
}

/**
 * Codec JSON du DTO (l'objet INTERNE du contrat, sans l'enveloppe de section).
 * Réutilisé par [SectionCodec] (enveloppe cloud) ET par la persistance projet
 * (stocke ce JSON tel quel). Isoler le codec ici garde le round-trip testable.
 */
object PowerCablingCodec {

    fun toJson(dto: PowerCablingDTO): JSONObject {
        val settings = JSONObject()
            .put("voltage", dto.settings.voltage)
            .put("circuitLimitW", dto.settings.circuitLimitW)
            .put("phaseLimitW", dto.settings.phaseLimitW)
        val dists = JSONArray()
        for (d in dto.distributors) {
            dists.put(JSONObject()
                .put("id", d.id)
                .put("name", d.name)
                .put("kind", d.kind.raw)
                .put("colorArgb", d.colorArgb)
                .put("circuitPhases", JSONArray(d.circuitPhases)))
        }
        val assigns = JSONArray()
        for (a in dto.assignments) {
            assigns.put(JSONObject()
                .put("fixture", a.fixture)
                .put("distributor", a.distributor)
                .put("circuit", a.circuit))
        }
        return JSONObject()
            .put("settings", settings)
            .put("distributors", dists)
            .put("assignments", assigns)
    }

    fun fromJson(o: JSONObject): PowerCablingDTO {
        val s = o.optJSONObject("settings") ?: JSONObject()
        val settings = CablingSettings(
            voltage = s.optInt("voltage", 230),
            circuitLimitW = s.optInt("circuitLimitW", 3680),
            phaseLimitW = s.optInt("phaseLimitW", 0)
        )
        val dArr = o.optJSONArray("distributors") ?: JSONArray()
        val distributors = (0 until dArr.length()).mapNotNull { i ->
            val d = dArr.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val phasesArr = d.optJSONArray("circuitPhases") ?: JSONArray()
            val phases = (0 until phasesArr.length()).map { phasesArr.optInt(it, 1) }
            val kind = DistributorKind.from(d.optString("kind"))
            Distributor(
                id = id,
                name = d.optString("name"),
                kind = kind,
                colorArgb = d.optInt("colorArgb"),
                // Un distributeur sans phases (JSON abîmé) retombe sur le défaut de
                // son type plutôt que d'exister sans aucun circuit.
                circuitPhases = phases.ifEmpty { defaultCircuitPhases(kind) }
            )
        }
        val aArr = o.optJSONArray("assignments") ?: JSONArray()
        val assignments = (0 until aArr.length()).mapNotNull { i ->
            val a = aArr.optJSONObject(i) ?: return@mapNotNull null
            val fx = a.optString("fixture").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dist = a.optString("distributor").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CablingAssignment(fx, dist, a.optInt("circuit", 1))
        }
        return PowerCablingDTO(settings, distributors, assignments)
    }

    fun toJsonString(dto: PowerCablingDTO): String = toJson(dto).toString()

    fun fromJsonString(json: String): PowerCablingDTO? =
        runCatching { fromJson(JSONObject(json)) }.getOrNull()
}

// MARK: - Calcul de charge (IDENTIQUE iOS) ------------------------------------

/** Charge résolue d'un circuit d'un distributeur. */
data class CircuitLoad(
    val distributorId: String,
    val circuit: Int,               // 1-based
    val phase: Int,                 // 1|2|3
    val fixtures: List<String>,     // mvrUUIDs affectés (ordre stable)
    val totalW: Int,                // somme des conso CONNUES (les inconnues comptent 0)
    val unknownCount: Int,          // projecteurs sans conso connue (badge « conso inconnue »)
    val overloaded: Boolean         // totalW > circuitLimitW
)

/** Charge d'une phase (L1/L2/L3) d'un distributeur = somme de ses circuits. */
data class PhaseLoad(
    val phase: Int,                 // 1|2|3
    val totalW: Int,
    val unknownCount: Int,
    val overloaded: Boolean         // seulement si phaseLimitW > 0
)

/**
 * Calcul de charge, PUR et testable. `wattsOf` renvoie la conso connue d'un
 * projecteur (par son uuid) ou null (conso inconnue → compte 0 W mais signalée).
 */
object PowerCablingCalc {

    /** Charge d'un circuit précis (1-based) d'un distributeur. */
    fun circuitLoad(
        dist: Distributor,
        circuit: Int,
        assignments: List<CablingAssignment>,
        settings: CablingSettings,
        wattsOf: (String) -> Int?
    ): CircuitLoad {
        val fixtures = assignments
            .filter { it.distributor == dist.id && it.circuit == circuit }
            .map { it.fixture }
        var total = 0
        var unknown = 0
        for (fx in fixtures) {
            val w = wattsOf(fx)
            if (w == null) unknown++ else total += w
        }
        val overloaded = settings.circuitLimitW > 0 && total > settings.circuitLimitW
        return CircuitLoad(dist.id, circuit, dist.phaseOf(circuit), fixtures, total, unknown, overloaded)
    }

    /** Les charges des N circuits d'un distributeur, dans l'ordre 1..N. */
    fun circuitLoads(
        dist: Distributor,
        assignments: List<CablingAssignment>,
        settings: CablingSettings,
        wattsOf: (String) -> Int?
    ): List<CircuitLoad> =
        (1..dist.circuitCount).map { circuitLoad(dist, it, assignments, settings, wattsOf) }

    /**
     * Charge d'une phase = somme des charges des circuits de cette phase. C'est
     * l'aide à l'ÉQUILIBRAGE : l'électro compare L1/L2/L3 et rééquilibre.
     */
    fun phaseLoad(
        dist: Distributor,
        phase: Int,
        assignments: List<CablingAssignment>,
        settings: CablingSettings,
        wattsOf: (String) -> Int?
    ): PhaseLoad {
        var total = 0
        var unknown = 0
        for (c in 1..dist.circuitCount) {
            if (dist.phaseOf(c) != phase) continue
            val cl = circuitLoad(dist, c, assignments, settings, wattsOf)
            total += cl.totalW
            unknown += cl.unknownCount
        }
        val overloaded = settings.phaseLimitW > 0 && total > settings.phaseLimitW
        return PhaseLoad(phase, total, unknown, overloaded)
    }

    /** Les 3 charges de phase L1/L2/L3 d'un distributeur (toujours 3 entrées). */
    fun phaseLoads(
        dist: Distributor,
        assignments: List<CablingAssignment>,
        settings: CablingSettings,
        wattsOf: (String) -> Int?
    ): List<PhaseLoad> =
        listOf(1, 2, 3).map { phaseLoad(dist, it, assignments, settings, wattsOf) }

    /** Total d'un distributeur = somme de tous ses circuits. */
    fun distributorTotalW(
        dist: Distributor,
        assignments: List<CablingAssignment>,
        settings: CablingSettings,
        wattsOf: (String) -> Int?
    ): Int = circuitLoads(dist, assignments, settings, wattsOf).sumOf { it.totalW }
}
