package com.minou.mvrviewer.ui

/**
 * Anti-rafale des refus Pro — portage de `ProRefusalThrottle` iOS
 * (SPEC-menus-android §4.5). Un glissé continu sur un curseur gardé émet des
 * dizaines de changements par seconde : seul le PREMIER d'une rafale ouvre
 * l'écran Pro, les suivants sont muets. Fenêtre GLISSANTE : chaque refus
 * repousse la fin de la rafale (un glissé de 3 s n'ouvre l'écran qu'une fois).
 */
class ProRefusalThrottle(val windowMs: Long = 800L) {
    private var last: Long? = null

    /** Vrai si CE refus doit ouvrir l'écran Pro (le premier d'une rafale). */
    @Synchronized
    fun admit(nowMs: Long): Boolean {
        val previous = last
        last = nowMs
        return previous == null || nowMs - previous > windowMs
    }
}

/**
 * Garde Pro d'un RÉGLAGE (curseur, interrupteur, champ) — pur et testé
 * (`ProGateTest`). Lisible par tous ; modifiable en Pro. Pour un gratuit, la
 * valeur ne bouge pas et le refus est signalé une fois par rafale. Jamais
 * `enabled = isPro` : la commande reste vivante, la toucher explique pourquoi.
 */
object ProGate {
    /**
     * Écrit ([set]) si [isPro] ; sinon signale le refus ([refuse], au plus une
     * fois par rafale de [throttle]) et laisse la valeur intacte.
     * @return vrai si la valeur a été écrite.
     */
    fun setter(
        reason: String, isPro: Boolean, throttle: ProRefusalThrottle, nowMs: Long,
        refuse: (String) -> Unit, set: () -> Unit
    ): Boolean {
        if (isPro) {
            set()
            return true
        }
        if (throttle.admit(nowMs)) refuse(reason)
        return false
    }
}
