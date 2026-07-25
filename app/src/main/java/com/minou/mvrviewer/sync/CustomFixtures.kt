package com.minou.mvrviewer.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * PROJECTEURS CUSTOM V1 — contrat de données PARTAGÉ iOS/Android.
 *
 * Un TYPE de projecteur custom réutilise la GÉOMÉTRIE 3D d'un GDTF existant
 * (`baseGdtfSpec`) mais réécrit ses CANAUX (liste rapide + empreinte). On lui
 * donne une SPEC SYNTHÉTIQUE de forme EXACTE « custom:<id> » ; assigner un type à
 * un projecteur = donner à ce projecteur la spec effective « custom:<id> » (portée
 * par la section PATCH per-projecteur, cf. [PatchEntryDTO.spec]).
 *
 * === Contrat FIGÉ (identique iOS/Android — ne renommer d'un seul côté sous peine
 * de non-synchro) ===
 *  - Convention de spec : « custom:<id> » (préfixe [CUSTOM_SPEC_PREFIX]).
 *  - DTO d'un type : { id, name, baseGdtfSpec, channels:[String], footprint:Int } —
 *    NOMS DE CHAMPS EXACTS.
 *  - Section « customFixtures » = la LISTE des DÉFINITIONS utilisées dans le
 *    projet, enveloppée dans un OBJET `{"types":[...]}` (le champ wrapper est
 *    `types`). Enveloppe cross-platform (cf. [SectionCodec]) :
 *      {"customFixtures":{"_0":{"types":[{id,name,baseGdtfSpec,channels,footprint},…]}}}
 *    Le `_0` DOIT être un OBJET (pas un tableau) — comme PatchDTO enveloppe
 *    `entries` et DmxCablingDTO enveloppe `distributors`.
 */

/** Préfixe EXACT de la spec synthétique d'un type custom : « custom:<id> ». */
const val CUSTOM_SPEC_PREFIX = "custom:"

/** id d'un type custom porté par une spec « custom:<id> », ou null si non-custom. */
fun customFixtureIdOf(spec: String?): String? =
    spec?.takeIf { it.startsWith(CUSTOM_SPEC_PREFIX) }
        ?.substring(CUSTOM_SPEC_PREFIX.length)
        ?.takeIf { it.isNotBlank() }

/** Spec synthétique d'un type custom à partir de son id (« custom:<id> »). */
fun customFixtureSpec(id: String): String = CUSTOM_SPEC_PREFIX + id

/**
 * Définition d'un type de projecteur custom — DTO FIGÉ (mémoire ET fil). Réutilisé
 * directement comme modèle mémoire (les champs sont identiques au contrat), ce qui
 * garantit l'alignement cross-platform (aucun pont de renommage possible).
 *   - `id`           : uuid stable ;
 *   - `name`         : nom d'affichage du type ;
 *   - `baseGdtfSpec` : spec GDTF de base (fournit la GÉOMÉTRIE 3D) ;
 *   - `channels`     : noms de canaux ORDONNÉS (réécrits) ;
 *   - `footprint`    : nb de canaux occupés (défaut = channels.size, réglable ≥ 1).
 */
data class CustomFixtureTypeDTO(
    val id: String,
    val name: String,
    val baseGdtfSpec: String,
    val channels: List<String>,
    val footprint: Int
)

/**
 * DTO de la SECTION « customFixtures » : enveloppe la liste des types dans un
 * OBJET (champ `types`) — invariant du [SectionCodec] (`_0` = objet, jamais un
 * tableau nu).
 */
data class CustomFixturesDTO(val types: List<CustomFixtureTypeDTO> = emptyList())

/**
 * Codec JSON du DTO de section (l'objet INTERNE du contrat, SANS l'enveloppe de
 * section). Réutilisé par [SectionCodec] (enveloppe cloud) ET par la persistance
 * projet ([com.minou.mvrviewer.mvr.ProjectStore.saveCustomFixtures]).
 */
object CustomFixturesCodec {

    fun toJson(dto: CustomFixturesDTO): JSONObject {
        val arr = JSONArray()
        for (t in dto.types) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("baseGdtfSpec", t.baseGdtfSpec)
                    .put("channels", JSONArray(t.channels))
                    .put("footprint", t.footprint)
            )
        }
        return JSONObject().put("types", arr)
    }

    fun fromJson(o: JSONObject): CustomFixturesDTO {
        val arr = o.optJSONArray("types") ?: JSONArray()
        val types = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chArr = t.optJSONArray("channels") ?: JSONArray()
            val channels = (0 until chArr.length()).map { chArr.optString(it) }
            // footprint : au moins 1 ; à défaut du champ, le nb de canaux (≥ 1).
            val footprint = maxOf(1, t.optInt("footprint", channels.size.coerceAtLeast(1)))
            CustomFixtureTypeDTO(
                id = id,
                name = t.optString("name"),
                baseGdtfSpec = t.optString("baseGdtfSpec"),
                channels = channels,
                footprint = footprint
            )
        }
        return CustomFixturesDTO(types)
    }

    fun toJsonString(dto: CustomFixturesDTO): String = toJson(dto).toString()

    fun fromJsonString(json: String): CustomFixturesDTO? =
        runCatching { fromJson(JSONObject(json)) }.getOrNull()
}
