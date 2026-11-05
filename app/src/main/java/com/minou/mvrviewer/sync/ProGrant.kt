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
        /**
         * Document `entitlements/{uid}` déjà converti en valeurs simples (les
         * `Timestamp` Firestore en millisecondes, cf. `FirebaseSyncService`). Une
         * date peut aussi être un [Date] ou un nombre de millisecondes (backend
         * local, tests). Une `expires` d'une autre forme (RawValue admin recopiée
         * telle quelle, §3.7) est ignorée, comme avant : la licence vaut alors
         * sans échéance.
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

        fun date(raw: Any?): Long? = when (raw) {
            is Date -> raw.time
            is Long -> raw
            is Int -> raw.toLong()
            is Double -> raw.toLong()
            else -> null
        }
    }
}
