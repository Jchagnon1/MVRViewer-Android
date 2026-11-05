package com.minou.mvrviewer.ui

import com.minou.mvrviewer.sync.PlayPurchaseSnapshot

/** Ce dont l'ordre « preuve puis acquittement » a besoin de Google Play — abstrait
 *  pour les tests JVM (faux Play). */
interface PlayAckGateway {
    /** Relit les abonnements (`queryPurchasesAsync(SUBS)`) ; `null` si Play échoue. */
    fun queryPurchases(then: (List<PlayPurchaseSnapshot>?) -> Unit)
    /** Acquitte l'achat de ce jeton (`acknowledgePurchase`) ; `ok` : Play l'a accepté. */
    fun acknowledge(purchaseToken: String, then: (ok: Boolean) -> Unit)
}

/**
 * ORDRE « PREUVE AU SERVEUR PUIS ACQUITTEMENT PAR L'APP » (SPEC-abonnement §3.4
 * étape 5, §4.3) pour un abonnement Play non acquitté. Aujourd'hui l'app
 * acquittait d'abord ; or `outOfAppPurchaseContext` (règle B0 de rattachement)
 * DISPARAÎT à l'acquittement : c'est le SERVEUR qui doit lire l'achat, l'enregistrer
 * et l'acquitter le premier. L'appareil est débloqué tout de suite (avant ceci).
 *
 * 1. compte connecté et backend qui vérifie les achats ([run] `proofCanLeave`) →
 *    la preuve part d'abord ;
 * 2. APRÈS la réponse du serveur, QUELLE QU'ELLE SOIT (succès, refus, `503`, délai
 *    dépassé, rien envoyé), l'app relit l'achat et l'acquitte elle-même s'il ne l'est
 *    toujours pas : filet contre le remboursement automatique de Google à 3 jours.
 *    Relecture impossible : les jetons connus sont acquittés quand même ;
 * 3. sans compte connecté ou sans vérification des achats (backend local) :
 *    acquittement local IMMÉDIAT, comme avant (aucune preuve ne peut partir).
 *
 * Achats déjà acquittés : la preuve part simplement (déclencheur habituel).
 */
class ProofThenAck(private val play: PlayAckGateway) {

    /**
     * @param unacknowledged jetons des abonnements Pro PURCHASED non acquittés ;
     * @param proofCanLeave compte connecté ET backend qui vérifie les achats ;
     * @param sendProof envoie la preuve ; appelle `done` APRÈS la réponse du serveur
     *   (ou tout de suite si rien n'est envoyé) ;
     * @param finished appelé une fois tout terminé.
     */
    fun run(
        unacknowledged: List<String>,
        proofCanLeave: Boolean,
        sendProof: (done: () -> Unit) -> Unit,
        finished: (() -> Unit)? = null
    ) {
        if (unacknowledged.isEmpty()) {
            sendProof { finished?.invoke() }
            return
        }
        if (!proofCanLeave) {
            acknowledgeAll(unacknowledged) { sendProof { finished?.invoke() } }
            return
        }
        sendProof {
            play.queryPurchases { fresh ->
                val still = fresh
                    ?.filter { it.isProSubscription && it.purchased && !it.acknowledged && it.purchaseToken.isNotEmpty() }
                    ?.map { it.purchaseToken }
                    ?: unacknowledged
                acknowledgeAll(still) { finished?.invoke() }
            }
        }
    }

    /** Acquitte chaque jeton, l'un après l'autre ; un échec n'arrête pas les autres
     *  (le prochain lancement réessaie : l'achat reste non acquitté chez Play). */
    private fun acknowledgeAll(tokens: List<String>, then: () -> Unit) {
        if (tokens.isEmpty()) { then(); return }
        play.acknowledge(tokens.first()) { acknowledgeAll(tokens.drop(1), then) }
    }
}
