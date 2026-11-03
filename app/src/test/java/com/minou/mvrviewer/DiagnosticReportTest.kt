package com.minou.mvrviewer

import com.minou.mvrviewer.ui.TestRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * « Envoyer le rapport » (guide C9) : e-mail prérempli vers l'assistance — adresse,
 * objet et corps lisibles en français et en anglais, bouton conditionné à un
 * incident, pièce jointe conservée.
 */
class DiagnosticReportTest {

    private fun src(path: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/$path"), File("app/src/main/java/com/minou/mvrviewer/$path"))
            .first { it.isFile }.readText()

    @Test fun adresseDeLAssistance() {
        assertEquals("support@mvrviewer.com", DiagnosticReport.SUPPORT_EMAIL)
    }

    @Test fun libellesVersionAppareilAndroid() {
        assertEquals("1.10.0 (21)", DiagnosticReport.versionLabel("1.10.0", 21L))
        assertEquals("1.10.0", DiagnosticReport.versionLabel(" 1.10.0 ", null))
        assertEquals("21", DiagnosticReport.versionLabel(null, 21L))
        assertEquals("?", DiagnosticReport.versionLabel("", null))
        assertEquals("samsung SM-A556B", DiagnosticReport.deviceLabel("samsung", "SM-A556B"))
        // La marque n'est pas répétée quand le modèle la porte déjà.
        assertEquals("Google Pixel 8", DiagnosticReport.deviceLabel("Google", "Google Pixel 8"))
        assertEquals("Pixel 8", DiagnosticReport.deviceLabel("", "Pixel 8"))
        assertEquals("Xiaomi", DiagnosticReport.deviceLabel("Xiaomi", null))
        assertEquals("14 (API 34)", DiagnosticReport.androidLabel("14", 34))
        assertEquals("API 34", DiagnosticReport.androidLabel(null, 34))
    }

    @Test fun objetEtCorpsFrEn() {
        val v = DiagnosticReport.versionLabel("1.10.0", 21L)
        assertEquals("MVR Viewer 1.10.0 (21) (Android) — rapport de plantage ou de gel",
            TestRes.FR.get("diag_mail_subject_fmt", v))
        assertEquals("MVR Viewer 1.10.0 (21) (Android) — crash or freeze report",
            TestRes.EN.get("diag_mail_subject_fmt", v))
        val fr = TestRes.FR.get("diag_mail_body_fmt", v, "samsung SM-A556B", "14 (API 34)")
        assertTrue(fr, fr.startsWith("Bonjour,\n\nMVR Viewer s'est fermé de façon inattendue"))
        assertTrue(fr, fr.contains("Le journal de diagnostic est joint à ce message."))
        assertTrue(fr, fr.endsWith("Version : 1.10.0 (21)\nAppareil : samsung SM-A556B\nAndroid : 14 (API 34)"))
        val en = TestRes.EN.get("diag_mail_body_fmt", v, "samsung SM-A556B", "14 (API 34)")
        assertTrue(en, en.startsWith("Hello,\n\nMVR Viewer closed unexpectedly or stopped responding."))
        assertTrue(en, en.endsWith("Version: 1.10.0 (21)\nDevice: samsung SM-A556B\nAndroid: 14 (API 34)"))
        // Jamais la société dans l'app (décision du 3 novembre 2026).
        for (t in listOf(fr, en)) assertFalse(t.contains("Confess", ignoreCase = true))
    }

    private val gmail = DiagnosticReport.Target("com.google.android.gm", "com.google.android.gm.ComposeActivityGmailExternal")
    private val chat = DiagnosticReport.Target("com.google.android.gm", "com.google.android.apps.dynamite.DynamiteDeepLinkMultiple")
    private val drive = DiagnosticReport.Target("com.google.android.apps.docs", "UploadMenuActivity")
    private val outlook = DiagnosticReport.Target("com.microsoft.office.outlook", "ComposeLauncherActivity")

    @Test fun messagerieDirecteSiUneSeule() {
        // Relevé sur l'émulateur Android 17 : Gmail ouvre mailto:, et son paquet porte
        // aussi Google Chat, qui accepte l'envoi mais n'est PAS une messagerie.
        assertEquals(DiagnosticReport.Route.Direct(gmail),
            DiagnosticReport.route(mailHandlers = listOf(gmail), sendHandlers = listOf(drive, chat, gmail)))
    }

    @Test fun plusieursMessageriesFeuilleReduiteAElles() {
        assertEquals(DiagnosticReport.Route.MailOnly(listOf(drive, chat)),
            DiagnosticReport.route(listOf(gmail, outlook), listOf(drive, chat, gmail, outlook, gmail)))
    }

    @Test fun aucuneMessagerieFeuilleDePartage() {
        assertEquals(DiagnosticReport.Route.Share, DiagnosticReport.route(emptyList(), listOf(drive, chat)))
        // Une appli qui ouvre mailto: sans accepter de fichier n'est pas retenue.
        assertEquals(DiagnosticReport.Route.Share, DiagnosticReport.route(listOf(outlook), listOf(drive)))
    }

    @Test fun boutonSeulementApresUnIncidentEtEmailPrerempli() {
        val home = src("ui/HomeScreen.kt")
        assertTrue("bouton conditionné à un plantage ou un gel",
            home.contains("com.minou.mvrviewer.CrashReporter.hasIncident(ctx)"))
        assertTrue(home.contains("if (hasIncident)"))
        assertFalse("plus de simple test « fichier non vide »", home.contains("it.exists() && it.length() > 0L"))
        assertTrue(home.contains("com.minou.mvrviewer.DiagnosticReport.send(ctx)"))
        val send = src("DiagnosticReport.kt")
        for (k in listOf("Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL)", "Intent.EXTRA_SUBJECT", "Intent.EXTRA_TEXT",
            "Intent.EXTRA_STREAM, uri", "clipData = ClipData.newRawUri(\"\", uri)", "FLAG_GRANT_READ_URI_PERMISSION")) {
            assertTrue("absent : $k", send.contains(k))
        }
        // Messagerie visée par son NOM d'activité, jamais par un sélecteur mailto: posé
        // sur l'envoi (Android 17 : jamais résolu, ni direct ni par la feuille de partage).
        assertFalse(send.contains("selector ="))
        assertTrue(send.contains("Intent(send).setComponent(ComponentName("))
        assertTrue(send.contains("Intent.EXTRA_EXCLUDE_COMPONENTS"))
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }.readText()
        assertTrue(manifest.contains("<action android:name=\"android.intent.action.SENDTO\" />"))
        assertTrue(manifest.contains("<data android:scheme=\"mailto\" />"))
    }
}
