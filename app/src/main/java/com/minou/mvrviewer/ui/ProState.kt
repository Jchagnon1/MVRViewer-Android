package com.minou.mvrviewer.ui

import org.json.JSONObject

/**
 * ÉTAT PRO SUR L'APPAREIL (SPEC-abonnement §2.4) — fonction PURE, IDENTIQUE sur
 * iOS (`ProState.swift`), Android et le web, rejouée sur les vecteurs communs
 * P1-P15 (`tests/fixtures/abonnement/pro-state.json` du dépôt web, copiés dans
 * `src/test/resources/port-vectors/abonnement/`).
 *
 * Trois sources, chacune relue auprès de SON autorité :
 * - `store` : l'abonnement Google Play de CET appareil (`queryPurchasesAsync(SUBS)`) ;
 *   `until` toujours `null` sur Android (la bibliothèque ne donne pas l'échéance) ;
 * - `licence` : `entitlements/{uid}` du compte connecté — `null` si non connecté,
 *   jamais lue OU LECTURE EN ÉCHEC (une erreur ne donne JAMAIS `pro: false`, §4.1.4) ;
 * - `cache` : le dernier état Pro connu, en deux parts, qui ne vaut que
 *   [OFFLINE_MARGIN_MS] (72 h) au-delà de sa dernière échéance, et seulement tant
 *   que SA source n'a pas répondu depuis le dernier passage au premier plan
 *   (`onlineStore`, `onlineLicence`).
 *
 * Temps en MILLISECONDES depuis 1970 (comme les vecteurs et le web).
 */
object ProState {
    /** Marge hors ligne au-delà de la dernière échéance connue (Q4 : 72 h). */
    const val OFFLINE_MARGIN_MS: Long = 72L * 3_600_000L

    /** Abonnement de la boutique de l'appareil ; `until` null : échéance inconnue (Android). */
    data class Store(val active: Boolean, val until: Long?)

    /** Licence du compte ; `expires` null : sans échéance (licence admin). */
    data class Licence(val pro: Boolean, val expires: Long?)

    /** Part du cache venue de la boutique. */
    data class StorePart(val until: Long)

    /** Part du cache venue de la licence ; `perpetual` : licence sans échéance. */
    data class LicencePart(val until: Long?, val perpetual: Boolean)

    /** Dernier état Pro connu pour CET appareil et CE compte. Chaque part n'est
     *  effacée que par SA source, relue auprès de SON autorité. */
    data class Cache(val store: StorePart?, val licence: LicencePart?) {
        companion object {
            val EMPTY = Cache(null, null)
        }
    }

    enum class Reason(val code: String) {
        OVERRIDE("override"), STORE("store"), LICENCE("licence"), OFFLINE("hors-ligne"), NONE("aucun")
    }

    data class Input(
        val now: Long,
        /** `MVR_PRO` (DEBUG) : prioritaire, et ne touche JAMAIS au cache. */
        val override: Boolean?,
        val store: Store?,
        val licence: Licence?,
        /** Une réponse RÉSEAU de Google Play (`queryProductDetailsAsync`) depuis le
         *  dernier passage au premier plan. */
        val onlineStore: Boolean,
        /** Une lecture SERVEUR réussie de la licence (pas le cache Firestore)
         *  depuis le dernier passage au premier plan. */
        val onlineLicence: Boolean,
        val cache: Cache?
    )

    data class Output(val pro: Boolean, val reason: Reason, val cache: Cache)

    fun evaluate(input: Input): Output {
        val now = input.now
        val store = input.store
        val licence = input.licence
        val storeOK = store != null && store.active && (store.until == null || store.until > now)
        val licenceOK = licence != null && licence.pro && (licence.expires == null || licence.expires > now)

        fun keepsStore(part: StorePart?) = part != null && part.until + OFFLINE_MARGIN_MS > now
        // `until` absent sans « perpétuelle » (forme impossible à l'écriture) : 0 + marge,
        // comme `null + MARGE` en JavaScript.
        fun keepsLicence(part: LicencePart?) =
            part != null && (part.perpetual || (part.until ?: 0L) + OFFLINE_MARGIN_MS > now)
        val carried = (!input.onlineStore && keepsStore(input.cache?.store)) ||
            (!input.onlineLicence && keepsLicence(input.cache?.licence))

        val pro = input.override ?: (storeOK || licenceOK || carried)
        val reason = when {
            input.override != null -> Reason.OVERRIDE
            storeOK -> Reason.STORE
            licenceOK -> Reason.LICENCE
            carried -> Reason.OFFLINE
            else -> Reason.NONE
        }
        // Crochet de test : il ne touche jamais au cache.
        if (input.override != null) return Output(pro, reason, input.cache ?: Cache.EMPTY)

        var cache = input.cache ?: Cache.EMPTY
        if (storeOK) cache = cache.copy(store = StorePart(store?.until ?: now))
        else if (input.onlineStore) cache = cache.copy(store = null)
        if (licenceOK) {
            val expires = licence?.expires
            cache = cache.copy(licence = LicencePart(expires, expires == null))
        } else if (input.onlineLicence) {
            // Le Pro du compte a VRAIMENT pris fin (lecture serveur).
            cache = cache.copy(licence = null)
        }
        return Output(pro, reason, cache)
    }

    // MARK: - Échéance de la licence, app ouverte (§4.1.5, comme `src/ui/pro.js`)

    /** Plafond du minuteur d'échéance de la licence (réarmé au-delà), comme sur le site. */
    const val LICENCE_DEADLINE_CAP_MS: Long = 24L * 3_600_000L

    /**
     * Une lecture SERVEUR faite AVANT l'échéance de la licence lue ne dit rien de
     * l'état APRÈS elle (renouvelée ? marge Google de 6 h passée ?) : elle ne vaut
     * plus `onlineLicence`. Sans cela, l'app restée au premier plan perdait le Pro à
     * l'échéance ET effaçait la part licence du cache, là où le site le garde (marge
     * de 72 h, recette R10) jusqu'à la relecture. Une lecture faite APRÈS
     * l'échéance (licence vraiment terminée, P6) vaut. [readAt] : instant de la
     * dernière lecture serveur réussie (null : inconnu, la lecture vaut).
     */
    fun licenceReadStillOnline(online: Boolean, licence: Licence?, readAt: Long?, now: Long): Boolean {
        if (!online) return false
        val expires = licence?.takeIf { it.pro }?.expires ?: return true
        if (expires > now || readAt == null) return true
        return readAt >= expires
    }

    /** Délai (ms) avant l'échéance de la licence lue, plafonné à 24 h ; null sans
     *  licence Pro datée à venir. +1 ms : le minuteur échoit APRÈS l'échéance. */
    fun licenceDeadlineDelay(licence: Licence?, now: Long): Long? {
        val expires = licence?.takeIf { it.pro }?.expires ?: return null
        if (expires <= now) return null
        return minOf(expires - now, LICENCE_DEADLINE_CAP_MS) + 1
    }
}

/**
 * Mémoire du cache `proState` (§2.4) : préférence `pro.cache`, JSON
 * `{uid, store, licence}` (ms depuis 1970). Remplace les booléens `pro.entitled`
 * et `pro.grant`, effacés au premier lancement ([LEGACY_KEYS]). `uid` : compte
 * auquel appartient la part `licence` (null : déconnecté) — la part `licence`
 * d'un autre compte n'est jamais reprise.
 */
data class ProCacheRecord(
    val uid: String?,
    val store: ProState.StorePart?,
    val licence: ProState.LicencePart?
) {
    constructor(uid: String?, cache: ProState.Cache) : this(uid, cache.store, cache.licence)

    val cache: ProState.Cache get() = ProState.Cache(store, licence)

    fun toJson(): String = JSONObject()
        .put("uid", uid ?: JSONObject.NULL)
        .put("store", store?.let { JSONObject().put("until", it.until) } ?: JSONObject.NULL)
        .put("licence", licence?.let {
            JSONObject().put("until", it.until ?: JSONObject.NULL).put("perpetual", it.perpetual)
        } ?: JSONObject.NULL)
        .toString()

    companion object {
        const val KEY = "pro.cache"
        /**
         * Anciennes clés (achat unique, licence booléenne sans échéance). Leur valeur
         * n'est PAS reprise : un booléen sans échéance garderait le Pro indéfiniment
         * hors ligne ; la boutique et la licence sont relues dès le lancement.
         */
        val LEGACY_KEYS = listOf("pro.entitled", "pro.grant")

        /** Relit l'entrée ; `null` si absente ou illisible. */
        fun fromJson(s: String?): ProCacheRecord? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(s)
                val uid = if (o.isNull("uid")) null else o.getString("uid")
                val store = o.optJSONObject("store")?.let { ProState.StorePart(it.getLong("until")) }
                val licence = o.optJSONObject("licence")?.let {
                    ProState.LicencePart(if (it.isNull("until")) null else it.getLong("until"),
                        it.optBoolean("perpetual", false))
                }
                ProCacheRecord(uid, store, licence)
            }.getOrNull()
        }
    }
}
