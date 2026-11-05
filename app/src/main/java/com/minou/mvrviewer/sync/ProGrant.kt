package com.minou.mvrviewer.sync

import java.util.Date

/**
 * LICENCE PRO DU COMPTE telle que lue dans `entitlements/{uid}`
 * (SPEC-abonnement §3.7, §4.1.4 ; miroir de `ProGrant.swift` iOS) : `pro` et
 * `expires` (échéance d'abonnement, marge de renouvellement Google comprise, OU
 * échéance d'une licence admin), plus les sources des boutiques pour
 * l'AFFICHAGE (§5.5, §5.6) — dont les dates viennent de `expiresAt` /
 * `graceUntil`, jamais de `expires`.
 *
 * [fromServer] : lecture faite au SERVEUR (pas le cache hors ligne de
 * Firestore) — alimente `onlineLicence` de `ProState`. Une lecture EN ÉCHEC
 * n'est jamais un `ProGrant` : [SyncService.fetchProGrant] rend alors `null`.
 * Temps en millisecondes depuis 1970.
 */
data class ProGrant(
    val pro: Boolean,
    val expires: Long? = null,
    val appstore: SourceView? = null,
    val play: SourceView? = null,
    val fromServer: Boolean
) {
    /** Règle inchangée : `pro` et `expires` absent ou futur. */
    fun isActive(now: Long): Boolean = pro && (expires == null || expires > now)

    /** Une source d'abonnement d'une boutique (`sources.appstore` / `sources.play`),
     *  réduite à ce que les écrans montrent. */
    data class SourceView(
        val active: Boolean,
        val status: String? = null,
        val expiresAt: Long? = null,
        val graceUntil: Long? = null,
        val autoRenew: Boolean? = null
    ) {
        /** Échéance effective : `max(expiresAt, graceUntil)`. */
        val effectiveEnd: Long?
            get() = listOfNotNull(expiresAt, graceUntil).maxOrNull()

        /** Active ET d'échéance effective future (§2.5) : une source active mais
         *  échue (contrôle pas encore passé) ne compte jamais. */
        fun isLive(now: Long): Boolean = active && (effectiveEnd?.let { it > now } ?: false)
    }

    companion object {
        /** Clés de temps du document (`expires`, et celles des sources). */
        val TIME_KEYS: Set<String> = setOf("expires", "expiresAt", "graceUntil", "revokedAt")

        /**
         * Document Firestore → valeurs simples pour [fromDocument], en profondeur
         * (sources). [timestampDate] reconnaît un `Timestamp` Firestore et rend sa
         * [Date] — la SEULE forme de date que [fromDocument] lit. Sous une clé de
         * temps ([TIME_KEYS]), une valeur qui N'EST PAS un Timestamp (nombre, texte,
         * table `{ seconds }` saisis à la main dans la console : la `RawValue` que le
         * serveur recopie telle quelle, §3.7) est OPAQUE et retirée — traitée comme
         * ABSENTE (règle D8, §4.1.5), jamais lue comme des secondes ni des
         * millisecondes. Une `expires` ainsi retirée laisse une licence sans
         * échéance, comme sur le site (`proGrant.js`), sur iOS
         * (`ProGrant.plainDocument`) et au serveur (`repo.fromFirestore`). Hors clés
         * de temps : valeur inchangée.
         */
        fun plainDocument(data: Map<String, Any?>, timestampDate: (Any?) -> Date?): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((key, value) in data) {
                val date = timestampDate(value)
                when {
                    date != null -> out[key] = date
                    key in TIME_KEYS -> Unit
                    value is Map<*, *> ->
                        out[key] = plainDocument(value.entries.associate { (k, v) -> k.toString() to v }, timestampDate)
                    else -> out[key] = value
                }
            }
            return out
        }

        /**
         * Document `entitlements/{uid}` déjà converti en valeurs simples
         * ([plainDocument]). D8 (§4.1.5) : SEULE une [Date] — un `Timestamp`
         * Firestore converti — est une date ; un nombre (secondes ou
         * millisecondes), un texte, une table sont traités comme ABSENTS, même
         * passés ici sans [plainDocument]. Une `expires` absente : la licence vaut
         * sans échéance (licence admin perpétuelle).
         */
        fun fromDocument(data: Map<String, Any?>, fromServer: Boolean): ProGrant {
            val sources = data["sources"] as? Map<*, *>
            return ProGrant(
                pro = data["pro"] == true,
                expires = date(data["expires"]),
                appstore = source(sources?.get("appstore")),
                play = source(sources?.get("play")),
                fromServer = fromServer
            )
        }

        private fun source(raw: Any?): SourceView? {
            val map = raw as? Map<*, *> ?: return null
            return SourceView(
                active = map["active"] == true,
                status = map["status"] as? String,
                expiresAt = date(map["expiresAt"]),
                graceUntil = date(map["graceUntil"]),
                autoRenew = map["autoRenew"] as? Boolean
            )
        }

        /** Date (ms) d'une valeur de [plainDocument] : une [Date], rien d'autre (D8) —
         *  un nombre n'est JAMAIS lu comme des millisecondes. */
        fun date(raw: Any?): Long? = (raw as? Date)?.time
    }
}
