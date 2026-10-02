package com.minou.mvrviewer.sync

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

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
 *    NOMS DE CHAMPS EXACTS — plus, depuis la bibliothèque MA (SPEC-ma.md §3), deux clés
 *    OPTIONNELLES `profile` et `model3D` (OMISES si absentes, jamais `null`).
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
 * Provenance MA d'un type (SPEC-ma.md §3.2) : les CINQ clés sont toujours écrites, dans
 * cet ordre. Affichage + détection de doublon seulement — ne change pas la résolution.
 * `format` inconnu GARDÉ tel quel (affiché « ? »).
 */
data class CustomFixtureProfileDTO(
    val format: String,
    val manufacturer: String,
    val fixture: String,
    val mode: String,
    val fileName: String
)

/**
 * Source de la GÉOMÉTRIE 3D d'un type (SPEC-ma.md §3.2, §4). Un `kind` inconnu est
 * IGNORÉ au décodage (donc perdu au ré-encodage, §3.6) — jamais d'échec de section.
 */
sealed class CustomFixtureModel3D {
    /** Révision GDTF Share (chaque membre la télécharge avec SON compte). */
    data class GdtfShare(val rid: Int) : CustomFixtureModel3D()
    /** `.gdtf` importé, voyage en blob Storage `projects/{id}/blobs/{sha256}`. */
    data class GdtfBlob(val sha256: String, val fileName: String) : CustomFixtureModel3D()
    /**
     * Objet 3D importé (blob). `unitScaleToMm` en `Double` (un Float 25.4 deviendrait
     * 25.399999618530273 au ré-encodage) ; `yUp` = axe vertical du fichier.
     */
    data class ModelBlob(
        val sha256: String,
        val fileName: String,
        val format: String,
        val unitScaleToMm: Double,
        val yUp: Boolean
    ) : CustomFixtureModel3D()

    val kind: String get() = when (this) {
        is GdtfShare -> "gdtfShare"
        is GdtfBlob -> "gdtfBlob"
        is ModelBlob -> "modelBlob"
    }

    /** SHA-256 du blob porté (gdtfBlob / modelBlob), sinon null. */
    val blobSha: String? get() = when (this) {
        is GdtfShare -> null
        is GdtfBlob -> sha256
        is ModelBlob -> sha256
    }
}

/**
 * Définition d'un type de projecteur custom — DTO FIGÉ (mémoire ET fil). Réutilisé
 * directement comme modèle mémoire (les champs sont identiques au contrat), ce qui
 * garantit l'alignement cross-platform (aucun pont de renommage possible).
 *   - `id`           : uuid stable ;
 *   - `name`         : nom d'affichage du type ;
 *   - `baseGdtfSpec` : spec GDTF de base (fournit la GÉOMÉTRIE 3D) ;
 *   - `channels`     : noms de canaux ORDONNÉS (réécrits) — pour un type MA, les TROUS
 *                      `""` sont gardés (l'index EST l'offset) ;
 *   - `footprint`    : nb de canaux occupés (défaut = channels.size, réglable ≥ 1) ;
 *   - `profile`      : provenance MA (optionnelle) ;
 *   - `model3D`      : source 3D choisie (optionnelle ; prime sur `baseGdtfSpec` quand
 *                      elle est disponible sur l'appareil, SPEC-ma.md §4.6).
 * Valeurs par défaut : les constructions existantes (5 champs) compilent telles quelles.
 */
data class CustomFixtureTypeDTO(
    val id: String,
    val name: String,
    val baseGdtfSpec: String,
    val channels: List<String>,
    val footprint: Int,
    val profile: CustomFixtureProfileDTO? = null,
    val model3D: CustomFixtureModel3D? = null
)

/**
 * DTO de la SECTION « customFixtures » : enveloppe la liste des types dans un
 * OBJET (champ `types`) — invariant du [SectionCodec] (`_0` = objet, jamais un
 * tableau nu).
 */
data class CustomFixturesDTO(val types: List<CustomFixtureTypeDTO> = emptyList())

/**
 * Codec JSON du DTO de section (l'objet INTERNE du contrat, SANS l'enveloppe de
 * section). Réutilisé par [SectionCodec] (enveloppe cloud), par la persistance
 * projet ([com.minou.mvrviewer.mvr.ProjectStore.saveCustomFixtures]) et par la biblio
 * locale.
 *
 * ENCODAGE CANONIQUE ([toCanonicalString]) — octets IDENTIQUES à la référence
 * `tests/fixtures/ma/section_reference.py` (et donc à iOS et au web) : ordre des clés de
 * la spec, aucun espace, nombres entiers sans décimale (`1000`, jamais `1000.0`), sinon
 * forme décimale la plus courte (`25.4`). Écrit à la main plutôt qu'avec `JSONObject` :
 * la dépendance org.json des tests JVM est une `HashMap` (ordre perdu) et les deux
 * implémentations d'org.json (appareil / test) ne formatent ni les nombres ni `/` de la
 * même façon — ici, appareil et test JVM produisent les MÊMES octets.
 *
 * DÉCODAGE TOLÉRANT ([fromJson], SPEC-ma.md §3.4) : seul `id` est requis ; un `profile`
 * ou un `model3D` invalide ne fait JAMAIS échouer le type ni la section.
 */
object CustomFixturesCodec {

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val DIGITS = Regex("^\\s*[0-9]+\\s*$")
    private val PY_FLOAT = Regex("^[+-]?(([0-9]+(\\.[0-9]*)?)|(\\.[0-9]+))([eE][+-]?[0-9]+)?$")

    // ---- Encodage canonique ----------------------------------------------------------

    fun toCanonicalString(dto: CustomFixturesDTO): String {
        val sb = StringBuilder(256)
        sb.append("{\"types\":[")
        dto.types.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            writeType(sb, t)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun writeType(sb: StringBuilder, t: CustomFixtureTypeDTO) {
        sb.append("{\"id\":"); str(sb, t.id)
        sb.append(",\"name\":"); str(sb, t.name)
        sb.append(",\"baseGdtfSpec\":"); str(sb, t.baseGdtfSpec)
        sb.append(",\"channels\":[")
        t.channels.forEachIndexed { i, c -> if (i > 0) sb.append(','); str(sb, c) }
        sb.append("],\"footprint\":").append(t.footprint)
        t.profile?.let { p ->
            sb.append(",\"profile\":{\"format\":"); str(sb, p.format)
            sb.append(",\"manufacturer\":"); str(sb, p.manufacturer)
            sb.append(",\"fixture\":"); str(sb, p.fixture)
            sb.append(",\"mode\":"); str(sb, p.mode)
            sb.append(",\"fileName\":"); str(sb, p.fileName)
            sb.append('}')
        }
        t.model3D?.let { m ->
            sb.append(",\"model3D\":{\"kind\":"); str(sb, m.kind)
            when (m) {
                is CustomFixtureModel3D.GdtfShare -> sb.append(",\"rid\":").append(m.rid)
                is CustomFixtureModel3D.GdtfBlob -> {
                    sb.append(",\"sha256\":"); str(sb, m.sha256)
                    sb.append(",\"fileName\":"); str(sb, m.fileName)
                }
                is CustomFixtureModel3D.ModelBlob -> {
                    sb.append(",\"sha256\":"); str(sb, m.sha256)
                    sb.append(",\"fileName\":"); str(sb, m.fileName)
                    sb.append(",\"format\":"); str(sb, m.format)
                    sb.append(",\"unitScaleToMm\":").append(formatNumber(m.unitScaleToMm))
                    sb.append(",\"yUp\":").append(if (m.yUp) "true" else "false")
                }
            }
            sb.append('}')
        }
        sb.append('}')
    }

    /** Chaîne JSON comme `json.dumps(…, ensure_ascii=False)` de Python. */
    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /**
     * Nombre comme Python : entier si la valeur est entière (`1000`), sinon `repr` (la
     * plus courte écriture qui se relit à l'identique : `25.4`, `5e-05`).
     */
    internal fun formatNumber(d: Double): String {
        if (!d.isFinite()) return "null"
        if (d == Math.rint(d)) {
            return if (kotlin.math.abs(d) < 1e18) d.toLong().toString() else BigDecimal(d).toBigInteger().toString()
        }
        val exact = BigDecimal(d)
        var digits = ""
        var decpt = 0
        for (p in 1..17) {
            val r = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (r.toDouble() == d) {
                // valeur = 0.<chiffres> × 10^decpt
                digits = r.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
                decpt = r.precision() - r.scale()
                break
            }
        }
        if (digits.isEmpty()) return d.toString()
        val sign = if (d < 0) "-" else ""
        return if (decpt <= -4 || decpt > 16) {
            val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val e = decpt - 1
            sign + mant + "e" + (if (e < 0) "-" else "+") + kotlin.math.abs(e).toString().padStart(2, '0')
        } else if (decpt <= 0) {
            sign + "0." + "0".repeat(-decpt) + digits
        } else if (decpt < digits.length) {
            sign + digits.substring(0, decpt) + "." + digits.substring(decpt)
        } else {
            sign + digits + "0".repeat(decpt - digits.length) + ".0"
        }
    }

    // ---- Décodage tolérant -----------------------------------------------------------

    /** `str_()` de la référence : booléen → "true"/"false", nombre → entier ou repr, chaîne → elle-même, sinon "". */
    private fun pyStr(v: Any?): String = when (v) {
        is String -> v
        is Boolean -> if (v) "true" else "false"
        is Number -> formatNumber(v.toDouble())
        else -> ""
    }

    private fun posInt(v: Any?): Int? = when (v) {
        is Boolean -> null
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite() && d == Math.rint(d) && d > 0 && d <= Int.MAX_VALUE) d.toInt() else null
        }
        is String -> if (DIGITS.matches(v)) v.trim().toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() else null
        else -> null
    }

    private fun posNum(v: Any?): Double? {
        val x: Double = when (v) {
            is Boolean -> return null
            is Number -> v.toDouble()
            is String -> v.trim().let { s -> if (PY_FLOAT.matches(s)) s.toDoubleOrNull() else null } ?: return null
            else -> return null
        }
        return if (x.isFinite() && x > 0) x else null
    }

    private fun sha(v: Any?): String? = (v as? String)?.takeIf { HEX64.matches(it) }?.lowercase()

    private fun JSONObject.raw(key: String): Any? = if (has(key)) opt(key)?.takeIf { it != JSONObject.NULL } else null

    fun decodeProfile(v: Any?): CustomFixtureProfileDTO? {
        val p = v as? JSONObject ?: return null
        return CustomFixtureProfileDTO(
            format = pyStr(p.raw("format")),
            manufacturer = pyStr(p.raw("manufacturer")),
            fixture = pyStr(p.raw("fixture")),
            mode = pyStr(p.raw("mode")),
            fileName = pyStr(p.raw("fileName"))
        )
    }

    fun decodeModel3D(v: Any?): CustomFixtureModel3D? {
        val m = v as? JSONObject ?: return null
        return when (m.raw("kind")) {
            "gdtfShare" -> posInt(m.raw("rid"))?.let { CustomFixtureModel3D.GdtfShare(it) }
            "gdtfBlob" -> sha(m.raw("sha256"))?.let { CustomFixtureModel3D.GdtfBlob(it, pyStr(m.raw("fileName"))) }
            "modelBlob" -> {
                val s = sha(m.raw("sha256")) ?: return null
                val fmt = pyStr(m.raw("format")).trim().lowercase()
                val unit = posNum(m.raw("unitScaleToMm")) ?: 1.0
                val yUp = (m.raw("yUp") as? Boolean) ?: (fmt != "stl" && fmt != "3ds")
                CustomFixtureModel3D.ModelBlob(s, pyStr(m.raw("fileName")), fmt, unit, yUp)
            }
            else -> null
        }
    }

    private fun decodeType(t: JSONObject): CustomFixtureTypeDTO? {
        val id = pyStr(t.raw("id")).trim().takeIf { it.isNotEmpty() } ?: return null
        val chArr = t.raw("channels") as? JSONArray
        val channels = if (chArr == null) emptyList()
            else (0 until chArr.length()).map { pyStr(chArr.opt(it)?.takeIf { v -> v != JSONObject.NULL }) }
        // footprint : nombre entier ≥ 1, sinon max(1, nb de canaux).
        val footprint = (t.raw("footprint") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it == Math.rint(it) && it >= 1 && it <= Int.MAX_VALUE }?.toInt()
            ?: maxOf(1, channels.size)
        return CustomFixtureTypeDTO(
            id = id,
            name = pyStr(t.raw("name")),
            baseGdtfSpec = pyStr(t.raw("baseGdtfSpec")),
            channels = channels,
            footprint = footprint,
            profile = decodeProfile(t.raw("profile")),
            model3D = decodeModel3D(t.raw("model3D"))
        )
    }

    /** Objet INTERNE (`{"types":[…]}`) → DTO, tolérant. */
    fun fromJson(o: JSONObject): CustomFixturesDTO {
        val arr = o.raw("types") as? JSONArray ?: return CustomFixturesDTO(emptyList())
        val types = (0 until arr.length()).mapNotNull { i ->
            (arr.opt(i) as? JSONObject)?.let { decodeType(it) }
        }
        return CustomFixturesDTO(types)
    }

    /** Objet INTERNE sous forme d'org.json (construit depuis la forme canonique). */
    fun toJson(dto: CustomFixturesDTO): JSONObject = JSONObject(toCanonicalString(dto))

    /** Forme persistée (projet, biblio locale) = forme canonique. */
    fun toJsonString(dto: CustomFixturesDTO): String = toCanonicalString(dto)

    fun fromJsonString(json: String): CustomFixturesDTO? =
        runCatching { fromJson(JSONObject(json)) }.getOrNull()
}
