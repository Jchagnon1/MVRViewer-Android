package com.minou.mvrviewer.sync

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * BIBLIOTHÈQUE DE PUISSANCES — outil de câblage, phase 1.
 *
 * Base COMMUNAUTAIRE, hors projet : une puissance saisie à la main pour un TYPE
 * de projecteur est mémorisée une fois et profite à tous les projets et à tous
 * les utilisateurs. Elle vit dans une collection Firestore RACINE `powerLibrary`,
 * un document par type. Ce fichier définit le CONTRAT PARTAGÉ iOS/Android :
 * identité du document, forme de l'entrée, règle de résolution.
 *
 * Un document = une entrée [PowerEntry]. Fusion « dernier écrivain gagne » sur
 * [PowerEntry.updatedAtMillis].
 */

/** Une entrée de la bibliothèque de puissances (miroir du doc Firestore `powerLibrary/{id}`). */
data class PowerEntry(
    val spec: String,          // spec GDTF d'ORIGINE, non normalisée (affichage/débogage)
    val watts: Int,            // puissance MAX saisie, en W
    val updatedBy: String,     // uid de l'auteur, ou "" (saisie hors ligne / stub)
    val updatedAtMillis: Long  // epoch en MILLISECONDES (⚠ ms, pas s) — clé LWW
)

/**
 * Identité du document dans `powerLibrary` = spec GDTF NORMALISÉE. Règle
 * IDENTIQUE des deux plateformes (iOS a la même fonction) : minuscules, espaces
 * de début/fin retirés, puis tout caractère INTERDIT par Firestore dans un id
 * (`/ . # $ [ ]`) remplacé par « _ ». Les espaces internes sont conservés
 * (Firestore les accepte). Ne JAMAIS changer d'un seul côté : ce serait deux
 * bibliothèques disjointes.
 */
fun powerLibraryDocId(spec: String): String {
    var s = spec.lowercase().trim()
    for (c in charArrayOf('/', '.', '#', '$', '[', ']')) s = s.replace(c, '_')
    return s
}

/** Provenance de la puissance effective d'un projecteur (pour le marquage visuel). */
enum class PowerSource { LIBRARY, GDTF, NONE }

/** Puissance effective résolue + sa provenance. `watts == null` ⇔ source NONE. */
data class PowerResolution(val watts: Int?, val source: PowerSource)

// ============================================================================
// CONSENSUS PAR VOTES (phase 2) — remplace le LWW mono-valeur de la phase 1.
// ============================================================================

/**
 * Un VOTE de puissance : la valeur donnée par UN utilisateur (doc Firestore
 * `powerLibrary/{docId}/submissions/{uid}`). Personne n'écrase le vote d'un
 * autre ; la valeur affichée est le CONSENSUS de tous les votes.
 */
data class PowerVote(val watts: Int, val updatedAtMillis: Long)

/**
 * Résultat du CONSENSUS : valeur retenue (médiane du cluster gagnant, arrondie),
 * taille du cluster gagnant (= la CONFIANCE, « … · N votes ») et total des
 * votes. `null` ⇔ aucun vote.
 */
data class PowerConsensus(val watts: Int, val winningClusterSize: Int, val totalVotes: Int)

/**
 * Deux votes tombent dans le MÊME cluster s'ils sont proches à ±10 % (relatif au
 * plus grand) OU à ±20 W — on prend le seuil le PLUS PERMISSIF des deux (ce qui
 * regroupe aussi bien les grosses machines, où 10 % > 20 W, que les petites, où
 * 20 W > 10 %). Symétrique et déterministe.
 */
private fun powerVotesClose(a: Int, b: Int): Boolean {
    val larger = maxOf(a, b)
    val tolerance = maxOf(0.10 * larger, 20.0)
    return kotlin.math.abs(a - b) <= tolerance
}

/** Médiane d'une liste TRIÉE croissante, arrondie à l'entier (arrondi au plus proche). */
private fun powerMedianRounded(sortedCluster: List<Int>): Int {
    val n = sortedCluster.size
    val mid = n / 2
    return if (n % 2 == 1) sortedCluster[mid]
    else Math.round((sortedCluster[mid - 1] + sortedCluster[mid]) / 2.0).toInt()
}

/**
 * ALGORITHME DE CONSENSUS — PUR, DÉTERMINISTE, PARTAGÉ iOS/Android.
 *
 * 1) 0 vote → `null`.
 * 2) CLUSTERS : trier croissant ; ouvrir un cluster sur le 1er vote ; y ajouter
 *    le vote suivant tant qu'il est proche du REPRÉSENTANT COURANT (le dernier
 *    vote ajouté au cluster), sinon ouvrir un nouveau cluster.
 * 3) Garder le PLUS GROS cluster ; à égalité de taille, la médiane la PLUS BASSE
 *    (choix prudent : sous-estimer la puissance n'est jamais dangereux
 *    électriquement). Valeur retenue = médiane du cluster gagnant, arrondie.
 */
fun powerConsensus(votes: List<Int>): PowerConsensus? {
    if (votes.isEmpty()) return null
    val sorted = votes.sorted()
    val clusters = ArrayList<MutableList<Int>>()
    var current = mutableListOf(sorted.first())
    for (i in 1 until sorted.size) {
        val v = sorted[i]
        if (powerVotesClose(current.last(), v)) current.add(v)
        else { clusters.add(current); current = mutableListOf(v) }
    }
    clusters.add(current)

    var best = clusters.first()
    var bestMedian = powerMedianRounded(best)
    for (c in clusters.drop(1)) {
        val m = powerMedianRounded(c)
        // Plus gros gagne ; à taille égale, médiane la plus basse (prudent).
        if (c.size > best.size || (c.size == best.size && m < bestMedian)) {
            best = c; bestMedian = m
        }
    }
    return PowerConsensus(bestMedian, best.size, votes.size)
}

/**
 * Règle de RÉSOLUTION effective (phase 2) : consensus des votes[spec] (si ≥1),
 * sinon puissance GDTF, sinon rien. La provenance reste [PowerSource.LIBRARY]
 * dès qu'un consensus communautaire existe.
 */
fun resolvePowerFromVotes(votes: List<Int>, gdtfWatts: Int?): PowerResolution =
    resolvePower(powerConsensus(votes)?.watts, gdtfWatts)

/**
 * Règle de RÉSOLUTION du contrat (identique iOS) :
 *   watts_effectifs = bibliothèque[spec]  (saisie utilisateur, PRIORITAIRE —
 *                     permet de corriger un GDTF faux)
 *                   sinon  puissance extraite du GDTF
 *                   sinon  aucune (à saisir).
 */
fun resolvePower(libraryWatts: Int?, gdtfWatts: Int?): PowerResolution = when {
    libraryWatts != null -> PowerResolution(libraryWatts, PowerSource.LIBRARY)
    gdtfWatts != null -> PowerResolution(gdtfWatts, PowerSource.GDTF)
    else -> PowerResolution(null, PowerSource.NONE)
}

/**
 * CACHE LOCAL de la bibliothèque, indépendant du backend et de la connexion :
 * un unique fichier GLOBAL `powerLibrary.json` dans filesDir (hors dossier
 * projet — la biblio est partagée par TOUS les projets). Il garantit que la
 * résolution est instantanée et fonctionne HORS LIGNE ; le cloud n'est qu'une
 * source supplémentaire fusionnée par-dessus (LWW). Clé = docId normalisé.
 */
object PowerLibraryStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "powerLibrary.json")

    /** Tout le cache, indexé par docId normalisé. */
    fun load(ctx: Context): Map<String, PowerEntry> {
        val obj = runCatching { JSONObject(file(ctx).readText()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, PowerEntry>()
        obj.keys().forEach { id ->
            val o = obj.optJSONObject(id) ?: return@forEach
            out[id] = PowerEntry(
                spec = o.optString("spec"),
                watts = o.optInt("watts"),
                updatedBy = o.optString("updatedBy"),
                updatedAtMillis = o.optLong("updatedAt")
            )
        }
        return out
    }

    /** Puissance en cache pour une spec (null si jamais saisie). */
    fun watts(ctx: Context, spec: String): Int? =
        load(ctx)[powerLibraryDocId(spec)]?.watts

    /**
     * Fusionne une entrée (LWW sur updatedAtMillis) et renvoie le cache complet.
     * Une entrée plus ANCIENNE que celle en place est ignorée : c'est ce qui rend
     * la fusion cloud↔local sûre quel que soit l'ordre d'arrivée.
     */
    fun upsert(ctx: Context, entry: PowerEntry): Map<String, PowerEntry> {
        val id = powerLibraryDocId(entry.spec)
        val current = load(ctx).toMutableMap()
        val existing = current[id]
        if (existing == null || entry.updatedAtMillis >= existing.updatedAtMillis) {
            current[id] = entry
            val obj = JSONObject()
            for ((k, e) in current) {
                obj.put(k, JSONObject()
                    .put("spec", e.spec).put("watts", e.watts)
                    .put("updatedBy", e.updatedBy).put("updatedAt", e.updatedAtMillis))
            }
            runCatching { file(ctx).writeText(obj.toString()) }
        }
        return current
    }
}
