package com.minou.mvrviewer

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider

/**
 * « Envoyer le rapport » de l'accueil (guide C9) : un e-mail PRÉREMPLI vers
 * l'assistance (destinataire, objet, corps), le journal de [CrashReporter] en pièce
 * jointe. Le bouton n'apparaît que si ce journal contient un plantage ou un gel
 * ([CrashReporter.hasIncident]). Les morceaux de texte sont des calculs purs,
 * testés en JVM (`DiagnosticReportTest`).
 */
object DiagnosticReport {
    /** Adresse publique de l'assistance (décision du 3 novembre 2026). */
    const val SUPPORT_EMAIL = "support@mvrviewer.com"

    /** « 1.10.0 (21) » ; le seul numéro connu s'il manque l'autre. */
    fun versionLabel(versionName: String?, versionCode: Long?): String {
        val name = versionName?.trim().orEmpty()
        return when {
            name.isNotEmpty() && versionCode != null -> "$name ($versionCode)"
            name.isNotEmpty() -> name
            versionCode != null -> versionCode.toString()
            else -> "?"
        }
    }

    /** « samsung SM-A556B », sans répéter la marque quand le modèle la porte déjà (« Pixel 8 » de Google). */
    fun deviceLabel(manufacturer: String?, model: String?): String {
        val brand = manufacturer?.trim().orEmpty()
        val mdl = model?.trim().orEmpty()
        return when {
            mdl.isEmpty() -> brand.ifEmpty { "?" }
            brand.isEmpty() || mdl.startsWith(brand, ignoreCase = true) -> mdl
            else -> "$brand $mdl"
        }
    }

    /** « 14 (API 34) ». */
    fun androidLabel(release: String?, sdk: Int): String =
        release?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it (API $sdk)" } ?: "API $sdk"

    /** Une activité d'une autre appli (paquet + classe), sans dépendre d'Android (tests JVM). */
    data class Target(val packageName: String, val className: String)

    /** Comment ouvrir l'e-mail. */
    sealed interface Route {
        /** Une seule messagerie : ouverte directement, l'e-mail prêt. */
        data class Direct(val target: Target) : Route
        /** Plusieurs messageries : la feuille de partage, réduite à elles. */
        data class MailOnly(val excluded: List<Target>) : Route
        /** Aucune messagerie : la feuille de partage habituelle. */
        data object Share : Route
    }

    /**
     * Messageries = activités qui ouvrent un `mailto:` ET acceptent l'envoi d'un fichier
     * (ACTION_SEND). On vise ces activités par leur nom plutôt que par un sélecteur
     * `mailto:` posé sur l'envoi : vérifié sur Android 17 (app ciblant l'API 36), un tel
     * sélecteur n'est jamais résolu, ni par startActivity (« intent not resolved ») ni
     * par la feuille de partage (« Aucune application ne peut effectuer cette action »).
     */
    fun route(mailHandlers: List<Target>, sendHandlers: List<Target>): Route {
        val mail = sendHandlers.filter { it in mailHandlers }.distinct()
        return when (mail.size) {
            0 -> Route.Share
            1 -> Route.Direct(mail.single())
            else -> Route.MailOnly(sendHandlers.filter { it !in mail }.distinct())
        }
    }

    /**
     * Ouvre la messagerie avec l'e-mail prérempli ([route]) ; sans messagerie, la
     * feuille de partage (une appli de messagerie choisie là garde destinataire et objet).
     * @return faux si rien n'a pu être ouvert (journal absent, aucune appli).
     */
    fun send(ctx: Context): Boolean {
        val f = CrashReporter.logFile(ctx)
        if (!f.isFile || f.length() == 0L) return false
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        }.getOrNull() ?: return false
        val pm = ctx.packageManager
        val pkg = runCatching { pm.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val version = versionLabel(pkg?.versionName, pkg?.longVersionCode)
        val body = ctx.getString(
            R.string.diag_mail_body_fmt, version,
            deviceLabel(Build.MANUFACTURER, Build.MODEL),
            androidLabel(Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.diag_mail_subject_fmt, version))
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData explicite : createChooser la recopie avec le droit de lecture
            // (sinon la feuille de partage ne peut pas lire le fichier).
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        fun handlers(intent: Intent): List<Target> = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { Target(it.activityInfo.packageName, it.activityInfo.name) }
        }.getOrDefault(emptyList())
        val route = route(
            mailHandlers = handlers(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))),
            sendHandlers = handlers(send)
        )
        val title = ctx.getString(R.string.diag_share)
        if (route is Route.Direct) {
            val direct = Intent(send).setComponent(ComponentName(route.target.packageName, route.target.className))
            if (runCatching { ctx.startActivity(direct) }.isSuccess) return true
        }
        val chooser = Intent.createChooser(send, title)
        if (route is Route.MailOnly && route.excluded.isNotEmpty()) chooser.putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            route.excluded.map { ComponentName(it.packageName, it.className) }.toTypedArray()
        )
        return runCatching { ctx.startActivity(chooser) }.isSuccess
    }
}
