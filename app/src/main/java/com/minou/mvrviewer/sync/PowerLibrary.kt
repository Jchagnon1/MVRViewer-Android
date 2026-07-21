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
