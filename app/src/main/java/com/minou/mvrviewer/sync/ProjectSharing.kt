package com.minou.mvrviewer.sync

import java.security.SecureRandom

// Partage et modération — outils COMMUNS aux deux services (Firebase et bouchon
// local) et au ViewModel. Contrat : MVRViewerWeb/SPEC-partage-moderation.md
// (§4 nom affiché et tailles, §5.1-5.2 codes, §5.13 lots d'écriture, §5.14
// auteur du journal). Référence testée côté règles : rules-tests/lib/clients.mjs
// (`truncateUtf16`, `nomAffiche`, `commitInLots`) ; même code qu'iOS
// (ProjectSharing.swift).

/**
 * Longueurs en unités UTF-16 : l'unité de `size()` des règles Firestore
 * (mesurée dans l'émulateur, §4), `String.length` en Kotlin et en JavaScript.
 */
object Utf16Text {
    /**
     * [s] tronquée à [maxUnits] unités UTF-16, par POINTS DE CODE entiers (jamais au
     * milieu d'une paire de substitution) — la formule Kotlin du §4.
     */
    fun truncated(s: String, maxUnits: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (sb.length + n > maxUnits) break
            sb.appendCodePoint(cp)
            i += n
        }
        return sb.toString()
    }
}

/** Noms (§4). */
object ProjectNames {
    /** Nom affiché dans un projet : 80 unités UTF-16 au plus (règle `nameOk`). */
    const val MAX_DISPLAY_NAME = 80
    /** Nom du projet : les clients à jour tronquent à 200 (la règle tolère 500). */
    const val MAX_PROJECT_NAME = 200

    /**
     * `nomAffiché` : `displayName` sans espaces aux bords ; vide ou ÉGAL à l'e-mail →
     * la partie de l'e-mail avant « @ » ; tronqué à 80 unités UTF-16. Jamais l'e-mail
     * complet (les co-membres n'y ont pas accès). Un nom refusé ferait échouer
     * « Rejoindre » (le nom part dans la même écriture) : la troncature n'est pas
     * facultative.
     *
     * L'égalité avec l'e-mail se juge À LA CASSE PRÈS : Firebase Auth rend l'adresse en
     * minuscules, alors que l'inscription sans nom (ici, sur iOS et sur le web) enregistre
     * comme nom l'adresse TELLE QUE SAISIE (« Casse.Device@Exemple.test »). La partie
     * avant « @ » est alors prise dans ce nom : la casse saisie est gardée, et le nom ne
     * change pas entre la session d'inscription et les suivantes (sinon §5.8 le
     * réécrirait à chaque ouverture).
     */
    fun displayName(displayName: String, email: String): String {
        var name = displayName.trim()
        if (name.isEmpty()) name = email.trim().substringBefore('@')
        else if (name.equals(email.trim(), ignoreCase = true)) name = name.substringBefore('@')
        return Utf16Text.truncated(name, MAX_DISPLAY_NAME)
    }

    fun displayName(account: AccountInfo): String = displayName(account.displayName, account.email)

    /** Nom du projet écrit à la création (J1). */
    fun projectName(name: String): String = Utf16Text.truncated(name, MAX_PROJECT_NAME)

    /**
     * HOMONYMES (§4) : un nom porté aussi par un AUTRE compte du projet est suivi de
     * « · » et des 4 premiers caractères de l'uid, en majuscules (« Alice · 3F9A »),
     * dans la liste des membres comme dans l'historique.
     */
    fun label(uid: String, name: String, among: Map<String, String>): String {
        if (uid.isEmpty() || among.none { it.key != uid && it.value == name }) return name
        return "$name · ${uid.take(4).uppercase()}"
    }

    /**
     * Auteur affiché d'une entrée du journal (§5.14) : son nom DANS LE PROJET
     * (`memberNames[authorUid]`) s'il en a un, sinon le nom écrit dans l'entrée
     * (entrées anciennes, membres partis), avec la règle des homonymes.
     */
    fun authorLabel(entry: AuditEntry, memberNames: Map<String, String>): String {
        val name = if (entry.authorUid.isEmpty()) entry.authorName
        else memberNames[entry.authorUid] ?: entry.authorName
        return label(entry.authorUid, name, memberNames)
    }
}

/**
 * Codes de partage : 6 caractères d'un alphabet sans ambiguïté (ni I, ni O, ni 0, ni 1)
 * — commun aux trois clients et aux règles (`isCode`).
 */
object ShareCodes {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6
    /** Tirages d'un code avant d'abandonner (collision avec un code existant, §5.1). */
    const val MAX_ATTEMPTS = 3

    private val random = SecureRandom()

    /** Tirage cryptographique : la lecture d'un code connu reste ouverte à tout compte (§15). */
    fun generate(): String = buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Saisie nettoyée (§5.3) : sans aucun espace, en MAJUSCULES. */
    fun normalized(input: String): String = input.filter { !it.isWhitespace() }.uppercase()

    /** Forme d'un code (un chemin Firestore ne doit jamais recevoir de « / »). */
    fun isWellFormed(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /** Champ `url` du document `invites/{code}` (inchangé, jamais affiché). */
    fun url(code: String): String = "mvrviewer://join/$code"
}

/**
 * LOTS D'ÉCRITURE (§5.13) : ≤ 450 opérations ET ≤ 10 projets distincts dont des
 * sous-documents, des invitations d'autrui ou la pierre tombale sont écrits — chaque
 * projet touché coûte un `get()` dans les règles, et une requête groupée est
 * ENTIÈREMENT refusée au-delà de 20 accès. L'ordre est conservé (sous-documents avant
 * leur projet) ; une unité (liste d'opérations) est INSÉCABLE : [pierre tombale,
 * projet] partent toujours dans le même lot. Même algorithme que `commitInLots`
 * (clients.mjs) et `WriteLots.plan` (iOS).
 */
object WriteLots {
    const val MAX_OPS = 450
    const val MAX_PROJECTS = 10

    /** [projectKey] : projet dont les règles liront le document (`get`), ou null. */
    data class Group<Op>(val projectKey: String?, val units: List<List<Op>>)

    fun <Op> plan(groups: List<Group<Op>>, maxOps: Int = MAX_OPS, maxProjects: Int = MAX_PROJECTS): List<List<Op>> {
        val lots = ArrayList<List<Op>>()
        var current = ArrayList<Op>()
        val keys = HashSet<String>()
        fun flush() {
            if (current.isNotEmpty()) lots.add(current)
            current = ArrayList()
            keys.clear()
        }
        for (group in groups) {
            val key = group.projectKey
            if (key != null && key !in keys && keys.size >= maxProjects) flush()
            for (unit in group.units) {
                if (current.size + unit.size > maxOps) flush()
                if (key != null) keys.add(key)
                current.addAll(unit)
            }
        }
        flush()
        return lots
    }
}
