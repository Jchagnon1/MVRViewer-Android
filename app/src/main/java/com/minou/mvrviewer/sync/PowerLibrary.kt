package com.minou.mvrviewer.sync

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * BIBLIOTHÈQUE DE PUISSANCES — outil de câblage.
 *
 * Base COMMUNAUTAIRE, hors projet : une puissance saisie pour un TYPE de
 * projecteur profite à tous les projets et à tous les utilisateurs. Elle vit
 * dans une collection Firestore RACINE `powerLibrary`, un document par type. Ce
 * fichier définit le CONTRAT PARTAGÉ iOS/Android : identité du document, forme
 * des votes, algorithme de consensus, règle de résolution.
 *
 * PHASE 2 — VOTES + CONSENSUS (remplace le LWW mono-valeur de la phase 1) :
 * chaque utilisateur dépose SON vote dans la sous-collection
 * `powerLibrary/{docId}/submissions/{uid}` = [PowerVote] ; personne n'écrase le
 * vote d'un autre. La valeur retenue est le CONSENSUS ([powerConsensus]) de tous
 * les votes. MIGRATION indolore : un ancien doc mono-valeur `{ watts }` (sans
 * sous-collection) est relu comme UN vote.
 */

/**
 * ANCIENNE entrée mono-valeur (phase 1), conservée UNIQUEMENT pour lire un doc
 * hérité `powerLibrary/{id}` avec un champ `watts` à la racine et le migrer en
 * UN vote. On n'écrit plus jamais cette forme (les écritures vont dans
 * `submissions/{uid}`).
 */
data class PowerEntry(
    val spec: String,          // spec GDTF d'ORIGINE, non normalisée (affichage/débogage)
    val watts: Int,            // puissance MAX saisie, en W
    val updatedBy: String,     // uid de l'auteur, ou "" (saisie hors ligne / stub)
    val updatedAtMillis: Long  // epoch en MILLISECONDES (⚠ ms, pas s)
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

/** Un instantané de CONSENSUS mis en cache pour une spec (affichage/repli hors ligne). */
data class PowerCacheEntry(val watts: Int, val votes: Int, val updatedAtMillis: Long)

/**
 * CACHE LOCAL de la bibliothèque, indépendant du backend et de la connexion :
 * un unique fichier GLOBAL `powerLibrary.json` dans filesDir (hors dossier
 * projet — la biblio est partagée par TOUS les projets). Il garantit que la
 * résolution est instantanée et fonctionne HORS LIGNE.
 *
 * PHASE 2 : on ne cache plus une entrée LWW mono-valeur mais le dernier
 * CONSENSUS connu (valeur + nombre de votes) par docId. Le cloud (somme des
 * votes) reste la source de vérité ; ce cache n'est qu'un instantané d'affichage
 * et de repli. Compat ascendante : un ancien cache mono-valeur (champ `watts`
 * sans `votes`) est relu comme un consensus de 1 vote.
 */
object PowerLibraryStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "powerLibrary.json")

    /** Tout le cache, indexé par docId normalisé. */
    fun load(ctx: Context): Map<String, PowerCacheEntry> {
        val obj = runCatching { JSONObject(file(ctx).readText()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, PowerCacheEntry>()
        obj.keys().forEach { id ->
            val o = obj.optJSONObject(id) ?: return@forEach
            if (!o.has("watts")) return@forEach
            out[id] = PowerCacheEntry(
                watts = o.optInt("watts"),
                // Ancien format sans `votes` → une seule valeur = 1 vote.
                votes = if (o.has("votes")) o.optInt("votes") else 1,
                updatedAtMillis = o.optLong("updatedAt")
            )
        }
        return out
    }

    /** Consensus en cache pour une spec (null si jamais renseigné). */
    fun entry(ctx: Context, spec: String): PowerCacheEntry? =
        load(ctx)[powerLibraryDocId(spec)]

    /**
     * Écrit (remplace) l'instantané de consensus d'un docId et renvoie le cache
     * complet. Le consensus étant recalculé sur l'ENSEMBLE des votes cloud à
     * chaque fetch, on écrase simplement : pas de fusion LWW à faire ici.
     */
    fun put(ctx: Context, docId: String, watts: Int, votes: Int): Map<String, PowerCacheEntry> {
        val current = load(ctx).toMutableMap()
        current[docId] = PowerCacheEntry(watts, votes, System.currentTimeMillis())
        val obj = JSONObject()
        for ((k, e) in current) {
            obj.put(k, JSONObject()
                .put("watts", e.watts).put("votes", e.votes).put("updatedAt", e.updatedAtMillis))
        }
        runCatching { file(ctx).writeText(obj.toString()) }
        return current
    }
}
