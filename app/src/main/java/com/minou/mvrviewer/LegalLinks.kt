package com.minou.mvrviewer

import android.content.Context

// Liens légaux, langue des e-mails de compte et numéro de version (guide de
// mise en vente C3 et C6) — PORTAGE de LegalLinks.swift (iOS).
//
// Google (règle « Données utilisateur ») et Apple (5.1.1(i)) exigent un lien
// vers la politique de confidentialité DANS l'app, facile à trouver. Il vit
// dans Compte & réglages › À propos (avec les conditions, les mentions légales
// et l'assistance) et sous « Créer le compte ». Calculs purs, testés :
// LegalLinksTest.

/** Une page légale du site. */
enum class LegalPage {
    PRIVACY, TERMS, LEGAL_NOTICE, SUPPORT;

    /**
     * Chemin de la page : en français pour une app affichée en français, en
     * anglais pour TOUTE autre langue (le site n'existe qu'en ces deux langues).
     */
    fun path(french: Boolean): String = when (this) {
        PRIVACY -> if (french) "/confidentialite" else "/privacy"
        TERMS -> if (french) "/cgu" else "/terms"
        LEGAL_NOTICE -> if (french) "/mentions-legales" else "/legal-notice"
        SUPPORT -> "/support"
    }
}

object LegalLinks {
    /**
     * SEULE base des pages légales : le domaine définitif (décision du
     * 3 novembre 2026). Écrite ICI et nulle part ailleurs (LegalLinksTest
     * vérifie qu'aucun autre fichier ne l'écrit, ni les chemins). Les appels de
     * l'app au serveur (vérification d'achat, [com.minou.mvrviewer.sync.PurchaseLink.ENDPOINT])
     * gardent leur propre adresse : elle ne change pas avec le domaine des pages.
     *
     * En ligne le 3 novembre 2026 : /mentions-legales, /legal-notice, /support
     * (et /licences). /confidentialite, /privacy, /cgu et /terms le seront AVANT
     * la soumission (guide J4) : les liens restent affichés, c'est voulu.
     */
    const val BASE_URL = "https://mvrviewer.com"

    /**
     * Vrai si [language] (« fr », « fr-CA », « fr_FR »…) est du français.
     */
    fun isFrench(language: String): Boolean =
        language.lowercase().split('-', '_').firstOrNull() == "fr"

    fun url(page: LegalPage, language: String): String = BASE_URL + page.path(isFrench(language))

    /**
     * Langue dans laquelle l'app S'AFFICHE (« fr » ou « en » : celles de ses
     * ressources), et non la première langue du téléphone : un téléphone réglé
     * sur [allemand, anglais] affiche l'app en anglais ; réglé sur l'espagnol
     * seul, l'app retombe sur ses textes par défaut, en français. La ressource
     * `app_language` vaut « fr » dans values/ et « en » dans values-en/ :
     * Android la résout comme tous les autres textes.
     */
    fun appLanguage(context: Context): String = context.getString(R.string.app_language)

    /** Adresse de la page dans la langue de l'app. */
    fun url(page: LegalPage, context: Context): String = url(page, appLanguage(context))
}

/**
 * Langue des e-mails de Firebase Auth (« Mot de passe oublié »…, guide C6) :
 * celle dans laquelle l'app S'AFFICHE, comme les pages légales — jamais la
 * première langue du téléphone (`useAppLanguage()` de Firebase prend la langue
 * de l'appareil). Calcul pur, testé : PasswordResetTest.
 */
object AuthEmailLanguage {
    /** « fr » pour une app affichée en français, « en » sinon (l'app n'existe qu'en ces deux langues). */
    fun code(displayed: String): String = if (LegalLinks.isFrench(displayed)) "fr" else "en"

    /** Code pour la langue affichée par l'app en ce moment. */
    fun current(context: Context): String = code(LegalLinks.appLanguage(context))
}

/**
 * Phrase posée sous « Créer le compte » (information au moment de la
 * collecte) : le gabarit traduit porte deux emplacements, `%1$s` (conditions
 * d'utilisation) et `%2$s` (politique de confidentialité), remplacés par les
 * libellés des liens. [segments] découpe la phrase dans l'ordre de lecture —
 * l'anglais peut placer les liens autrement que le français.
 */
object SignUpConsent {
    sealed interface Segment {
        data class Plain(val text: String) : Segment
        data class Link(val text: String, val page: LegalPage) : Segment
    }

    fun segments(template: String, termsLabel: String, privacyLabel: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var rest = template
        val marks = listOf("%1\$s" to (termsLabel to LegalPage.TERMS), "%2\$s" to (privacyLabel to LegalPage.PRIVACY))
        while (rest.isNotEmpty()) {
            val next = marks.mapNotNull { (mark, link) -> rest.indexOf(mark).takeIf { it >= 0 }?.let { Triple(it, mark, link) } }
                .minByOrNull { it.first }
            if (next == null) { out.add(Segment.Plain(rest)); break }
            val (at, mark, link) = next
            if (at > 0) out.add(Segment.Plain(rest.substring(0, at)))
            out.add(Segment.Link(link.first, link.second))
            rest = rest.substring(at + mark.length)
        }
        return out
    }
}

/** Numéro de version affiché dans « À propos » : « 1.10.0 (21) ». */
object AppVersionInfo {
    fun display(versionName: String?, versionCode: Long?): String {
        val short = versionName?.trim().orEmpty()
        val build = versionCode?.toString().orEmpty()
        return when {
            short.isNotEmpty() && build.isNotEmpty() -> if (short == build) short else "$short ($build)"
            short.isNotEmpty() -> short
            else -> build
        }
    }

    fun current(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return display(info?.versionName, info?.longVersionCode)
    }
}
