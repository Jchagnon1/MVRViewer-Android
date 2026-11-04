package com.minou.mvrviewer.ui

import com.minou.mvrviewer.LegalLinks
import com.minou.mvrviewer.R
import com.minou.mvrviewer.sync.AccountInfo
import com.minou.mvrviewer.sync.CloudProject
import com.minou.mvrviewer.sync.ContentReport
import com.minou.mvrviewer.sync.ProjectMembers
import com.minou.mvrviewer.sync.SyncException
import com.minou.mvrviewer.sync.syncMessageRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Interface du partage et de la modération (SPEC-partage-moderation §6, C-tous-7 et 8) :
 * membres (ordre, homonymes, « Membre sans nom »), confirmations §6.3, e-mail de
 * signalement §6.5, textes §6.4 EXACTS en français et en anglais (vrais strings.xml),
 * erreurs §7, copie locale §9, et aucune garde Pro sur les actions gratuites du §10.
 */
class ModerationUiTest {

    private val nb = ' '
    private val fr = TestRes.FR
    private val en = TestRes.EN

    private fun project(
        members: List<String>, owner: String = members.first(), names: Map<String, String>? = null,
        banned: List<String>? = null, code: String? = "CQDE22", name: String = "Show"
    ) = CloudProject(
        id = "P-1", contentKey = "k", name = name, ownerUid = owner, memberUids = members,
        mvrVersion = 2, mvrBlobSHA = null, refPlanBlobSHA = null, updatedAtEpoch = 0.0,
        inviteCode = code, bannedUids = banned, memberNames = names
    )

    // MARK: §6.2 Membres

    @Test fun membres_proprietaireDAbordPuisParNom_vousEtSansNom() {
        val p = project(listOf("zz-owner", "bob-1", "ali-2", "x-3"), owner = "zz-owner",
            names = mapOf("zz-owner" to "Zoé", "bob-1" to "bob", "ali-2" to "Élodie"))
        val rows = ProjectMembers.rows(p, me = "bob-1", unnamed = "Membre sans nom")
        assertEquals(listOf("Zoé", "bob", "Élodie", "Membre sans nom"), rows.map { it.label })
        assertEquals(listOf(true, false, false, false), rows.map { it.isOwner })
        assertEquals(listOf(false, true, false, false), rows.map { it.isMe })
        // Une ligne par compte, même si l'uid apparaît deux fois.
        assertEquals(2, ProjectMembers.rows(project(listOf("a", "b", "a")), "a", "?").size)
    }

    @Test fun membres_homonymes_suivisDesQuatrePremiersCaracteresDeLUid() {
        val p = project(listOf("3f9a77", "b0b0b0", "c1c1c1", "d2d2d2"),
            names = mapOf("3f9a77" to "Alice", "b0b0b0" to "Alice", "c1c1c1" to " Bob "))
        val labels = ProjectMembers.rows(p, "3f9a77", "Membre sans nom").associate { it.uid to it.label }
        assertEquals("Alice · 3F9A", labels["3f9a77"])
        assertEquals("Alice · B0B0", labels["b0b0b0"])
        assertEquals("Bob", labels["c1c1c1"])
        assertEquals("Membre sans nom", labels["d2d2d2"])
        assertEquals("Alice · B0B0", ProjectMembers.label("b0b0b0", p, "Membre sans nom"))
        // Deux membres sans nom : distingués aussi.
        val q = project(listOf("aaaa11", "bbbb22"))
        assertEquals(setOf("Unnamed member · AAAA", "Unnamed member · BBBB"),
            ProjectMembers.rows(q, "x", "Unnamed member").map { it.label }.toSet())
    }

    @Test fun personnesRetirees_tableLocale_sinonSansNom_jamaisUnMembre() {
        val p = project(listOf("owner"), banned = listOf("gone-1", "gone-2", "owner", "gone-1"))
        val rows = ProjectMembers.removedRows(p, mapOf("gone-2" to "Bruno"), "Membre sans nom")
        assertEquals(listOf("Bruno", "Membre sans nom"), rows.map { it.label })
        assertTrue(ProjectMembers.removedRows(project(listOf("o")), emptyMap(), "?").isEmpty())
    }

    @Test fun heritier_plusAncien_etProprietaireSeul() {
        val p = project(listOf("o", "first", "second"))
        assertEquals("first", ProjectMembers.heir(p, "o"))
        assertFalse(ProjectMembers.isOwnerAlone(p, "o"))
        assertTrue(ProjectMembers.isOwnerAlone(project(listOf("o")), "o"))
        // Propriétaire seul : pas de « Quitter » ; propriétaire avec d'autres : l'héritier est nommé ; membre : aucun.
        assertNull(ModerationConfirmation.leave(project(listOf("o")), "o", "?"))
        val named = project(listOf("o", "first"), names = mapOf("first" to "Paul"))
        assertEquals("Paul", ModerationConfirmation.leave(named, "o", "?")!!.heirName)
        assertNull(ModerationConfirmation.leave(named, "first", "?")!!.heirName)
    }

    // MARK: §6.3 Confirmations

    private fun TestRes.of(r: Pair<Int, List<Any>>) = res(r.first, r.second)

    @Test fun confirmations_textesExactsFrEtEn_boutonRougeSaufNouveauCode() {
        val p = project(listOf("o", "m"), names = mapOf("m" to "Paul"))
        val cases = listOf(
            ModerationConfirmation.NewCode("P-1", "CQDE22") to listOf(
                "Créer un nouveau code$nb?", "L'ancien code CQDE22 ne fonctionnera plus. Les membres actuels gardent leur accès.",
                "Créer un nouveau code", "Create a new code?",
                "The old code CQDE22 will stop working. Current members keep their access.", "Create new code"),
            ModerationConfirmation.Remove("P-1", "m", "Paul") to listOf(
                "Retirer Paul du projet$nb?",
                "Paul n'aura plus accès au projet et ne pourra plus le rejoindre, même avec un code. Les fichiers déjà reçus restent sur ses appareils.",
                "Retirer", "Remove Paul from the project?",
                "Paul will lose access to the project and won't be able to join it again, even with a code. Files already received stay on their devices.",
                "Remove"),
            ModerationConfirmation.Leave(p, null) to listOf(
                "Quitter « Show »$nb?",
                "Vous ne recevrez plus les mises à jour de ce projet. Le fichier déjà téléchargé reste sur cet appareil. Pour revenir, il vous faudra un code valide.",
                "Quitter", "Leave “Show”?",
                "You will no longer receive updates for this project. The file already downloaded stays on this device. To come back, you'll need a valid code.",
                "Leave"),
            ModerationConfirmation.Leave(p, "Paul") to listOf(
                "Quitter « Show »$nb?",
                "Vous êtes propriétaire$nb: en quittant, la propriété passe à Paul. Vous ne recevrez plus les mises à jour de ce projet.",
                "Quitter", "Leave “Show”?",
                "You are the owner: if you leave, ownership passes to Paul. You will no longer receive updates for this project.",
                "Leave"),
            ModerationConfirmation.Delete(p) to listOf(
                "Supprimer « Show » pour tout le monde$nb?",
                "Le projet partagé, son historique et ses fichiers dans le cloud seront supprimés pour tous ses membres. Les fichiers déjà téléchargés restent sur leurs appareils. Cette action est définitive.",
                "Supprimer", "Delete “Show” for everyone?",
                "The shared project, its history and its cloud files will be deleted for all its members. Files already downloaded stay on their devices. This cannot be undone.",
                "Delete")
        )
        for ((c, t) in cases) {
            assertEquals(t[0], fr.of(c.title(fr.get("common_unnamed"))))
            assertEquals(t[1], fr.of(c.message))
            assertEquals(t[2], fr.get(fr.nameOf(c.confirmRes)))
            assertEquals(t[3], en.of(c.title(en.get("common_unnamed"))))
            assertEquals(t[4], en.of(c.message))
            assertEquals(t[5], en.get(en.nameOf(c.confirmRes)))
            assertEquals(c !is ModerationConfirmation.NewCode, c.isDestructive)
        }
        // Projet sans nom : le titre le dit.
        val unnamed = ModerationConfirmation.Delete(project(listOf("o"), name = ""))
        assertEquals("Supprimer « (sans nom) » pour tout le monde$nb?", fr.of(unnamed.title(fr.get("common_unnamed"))))
    }

    // MARK: §6.4 Textes

    @Test fun textesDeLaFenetreFrEtEn() {
        val expected = mapOf(
            "sync_new_code" to ("Nouveau code…" to "New code…"),
            "sync_share_owner_legend" to ("Les personnes qui ont ce code peuvent rejoindre le projet. « Nouveau code » rend l'ancien inutilisable$nb; les membres actuels ne sont pas touchés." to
                "Anyone with this code can join the project. “New code” makes the old one unusable; current members are not affected."),
            "sync_share_member_legend" to ("Seul le propriétaire du projet peut changer le code ou retirer un membre." to
                "Only the project owner can change the code or remove a member."),
            "sync_owner_badge" to ("Propriétaire" to "Owner"),
            "sync_unnamed_member" to ("Membre sans nom" to "Unnamed member"),
            "sync_remove_member" to ("Retirer…" to "Remove…"),
            "sync_allow_back" to ("Autoriser à revenir" to "Allow back"),
            "sync_report_content" to ("Signaler un contenu…" to "Report content…"),
            "sync_leave_project" to ("Quitter le projet…" to "Leave project…"),
            "sync_delete_project" to ("Supprimer le projet…" to "Delete project…"),
            "sync_report_copy_address" to ("Copier l'adresse" to "Copy address"),
            "sync_report_copy_report" to ("Copier le signalement" to "Copy report"),
            "sync_share_code" to ("Code de partage" to "Share code"),
            "sync_err_invite_invalid" to ("Code de partage invalide ou expiré." to "Invalid or expired share code.")
        )
        for ((k, v) in expected) {
            assertEquals(k, v.first, fr.get(k))
            assertEquals(k, v.second, en.get(k))
        }
        assertEquals("Paul (vous)", fr.get("sync_member_you_fmt", "Paul"))
        assertEquals("Paul (you)", en.get("sync_member_you_fmt", "Paul"))
        assertEquals("Membres (1)", fr.plural("sync_members_header", 1, 1))
        assertEquals("Membres (3)", fr.plural("sync_members_header", 3, 3))
        assertEquals("Members (1)", en.plural("sync_members_header", 1, 1))
        assertEquals("Members (3)", en.plural("sync_members_header", 3, 3))
        assertEquals("Personnes retirées (2)", fr.plural("sync_removed_header", 2, 2))
        assertEquals("Removed people (1)", en.plural("sync_removed_header", 1, 1))
        assertEquals("Paul ne fait plus partie du projet. Pensez à créer un nouveau code si Paul a pu le transmettre.",
            fr.get("sync_removed_status_fmt", "Paul"))
        assertEquals("Paul is no longer part of the project. Consider creating a new code if Paul may have passed it on.",
            en.get("sync_removed_status_fmt", "Paul"))
        assertEquals("Paul peut de nouveau rejoindre le projet avec le code.", fr.get("sync_allowed_back_fmt", "Paul"))
        assertEquals("Paul can join the project again with the code.", en.get("sync_allowed_back_fmt", "Paul"))
        assertEquals("Aucune application de messagerie. Écrivez à support@mvrviewer.com en indiquant le nom et l'identifiant du projet.",
            fr.get("sync_report_no_mail_app_fmt", ContentReport.ADDRESS))
        assertEquals("No email app. Write to support@mvrviewer.com with the project's name and ID.",
            en.get("sync_report_no_mail_app_fmt", ContentReport.ADDRESS))
        // Aucune espace ORDINAIRE avant « : ; ? ! » dans les textes français du partage et de la modération.
        val keys = fr.strings.keys.filter { it.startsWith("sync_") || it.startsWith("sat_attribution") }
        assertTrue(keys.size >= 90)
        for (k in keys) {
            val v = fr.strings.getValue(k)
            assertFalse("fr $k : « $v »", Regex(" [:;?!]").containsMatchIn(v))
        }
    }

    // MARK: §6.5 E-mail de signalement

    private val reporter = AccountInfo("u-42", "lea@example.com", "Léa")

    private fun report(t: TestRes, p: CloudProject = project(listOf("o", "u-42"), names = mapOf("o" to "Olivier", "u-42" to "Léa"))) =
        ContentReport.make(p, reporter, "1.10.0 (21)", t.get("sync_unnamed_member"), t.get("common_unnamed")) { id, args -> t.res(id, args) }

    @Test fun signalement_objetEtCorpsDuParagraphe65_enFrancais() {
        val r = report(fr)
        assertEquals("Signalement — projet « Show » (P-1)", r.subject)
        assertEquals(listOf(
            "Projet$nb: Show",
            "Identifiant du projet$nb: P-1",
            "Propriétaire$nb: Olivier",
            "Nom$nb: Léa",
            "Mon compte$nb: lea@example.com (u-42)",
            "Application$nb: MVR Viewer 1.10.0 (21) (Android)",
            "",
            "Raisons du signalement et contenu concerné (membre, date, emplacement)$nb:",
            "",
            "",
            "Je déclare de bonne foi que les informations de ce signalement sont exactes et complètes."
        ).joinToString("\n"), r.body)
        assertEquals("support@mvrviewer.com", ContentReport.ADDRESS)
        assertEquals(LegalLinks.SUPPORT_EMAIL, ContentReport.ADDRESS)
        assertEquals("${r.subject}\n\n${r.body}", r.clipboardText)
    }

    @Test fun signalement_enAnglais_nomAfficheJamaisLEmail_proprietaireSansNom() {
        val noNames = project(listOf("o", "u-42"), names = mapOf("u-42" to "lea"))
        val r = ContentReport.make(noNames, AccountInfo("u-42", "lea@example.com", "lea@example.com"), "2.0",
            en.get("sync_unnamed_member"), en.get("common_unnamed")) { id, args -> en.res(id, args) }
        assertEquals("Report — project “Show” (P-1)", r.subject)
        assertEquals(listOf(
            "Project: Show",
            "Project ID: P-1",
            "Owner: Unnamed member",
            "Name: lea",
            "My account: lea@example.com (u-42)",
            "App: MVR Viewer 2.0 (Android)",
            "",
            "Reasons for the report and content concerned (member, date, location):",
            "",
            "",
            "I declare in good faith that the information in this report is accurate and complete."
        ).joinToString("\n"), r.body)
    }

    @Test fun signalement_mailto_encodeEnEntier_lignesEnCRLF() {
        val p = project(listOf("o"), name = "A&B = C?é")
        val r = report(fr, p)
        val m = r.mailto
        assertTrue(m.startsWith("mailto:support@mvrviewer.com?subject="))
        val query = m.substringAfter("?")
        // Un « & » ou un « = » du nom ne coupe jamais l'adresse : deux paramètres exactement.
        assertEquals(listOf("subject", "body"), query.split("&").map { it.substringBefore("=") })
        assertTrue(m.contains("A%26B%20%3D%20C%3F%C3%A9"))
        assertTrue(m.contains("%0D%0A"))
        assertFalse(m.contains("\n"))
        assertFalse(m.contains(" "))
        assertEquals("-._~AZaz09%20%2F%C2%A0", ContentReport.encode("-._~AZaz09 / "))
        // Décodé, le corps reprend le texte, lignes en CRLF.
        val body = java.net.URLDecoder.decode(query.substringAfter("&body=").replace("+", "%2B"), "UTF-8")
        assertEquals(r.body.replace("\n", "\r\n"), body)
    }

    // MARK: §7 Erreurs

    @Test fun erreurs_jamaisLeTexteBrutDuSdk() {
        assertEquals(R.string.sync_err_action_refused, SyncException.ActionRefused.syncMessageRes())
        assertEquals(R.string.sync_err_network, IOException("Unable to resolve host").syncMessageRes())
        assertEquals(R.string.sync_err_backend_unavailable,
            RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions.").syncMessageRes())
        assertNull(SyncException.Message("texte propre").syncMessageRes())
    }

    // MARK: §9 Copie locale

    @Test fun copieLocale_nomDeFichier() {
        assertEquals("Show.mvr", CloudDownloads.fileName("Show"))
        assertEquals("Show.mvr", CloudDownloads.fileName("Show.mvr"))
        assertEquals("Show.mvr", CloudDownloads.fileName("Show.MVR"))
        assertEquals("a-b-c.mvr", CloudDownloads.fileName("a/b\\c"))
        assertEquals("Projet.mvr", CloudDownloads.fileName("  "))
        assertEquals("Projet.mvr", CloudDownloads.fileName("../"))
        assertEquals("show.mvr", CloudDownloads.fileName(".show"))
        assertEquals(124, CloudDownloads.fileName("x".repeat(300)).length)
    }

    // MARK: §10 Gratuit / Pro

    private fun src(path: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/$path"), File("app/src/main/java/com/minou/mvrviewer/$path"))
            .first { it.isFile }.readText()

    @Test fun actionsGratuites_aucuneGardePro_publierResteGarde() {
        // Interface de modération : aucune garde Pro.
        assertFalse(src("ui/ProjectModerationUi.kt").contains("ProAccess"))
        assertFalse(src("ui/HomeScreen.kt").contains("ProAccess"))
        // Fenêtre « Partager le projet » : la SEULE garde est celle de « Publier ».
        val share = src("ui/SyncUI.kt").substringAfter("fun ShareProjectDialog(").substringBefore("fun JoinProjectDialog(")
        assertEquals(1, Regex("""ProAccess\.(gate|isPro)""").findAll(share).count())
        assertTrue(share.contains("ProAccess.gate(\"partager un projet\")"))
        assertTrue(share.indexOf("ProAccess.gate(") > share.indexOf("project == null) {"))
        // ViewModel : quitter, retirer, autoriser, nouveau code, supprimer ne passent ni par la garde ni par push.
        val vm = src("sync/SyncViewModel.kt")
        for (f in listOf("leaveProject", "deleteProject", "removeMember", "allowBack", "rotateInviteCode", "ensureCurrentCode")) {
            val body = vm.substringAfter("suspend fun $f(").substringBefore("\n    }\n")
            assertFalse(f, body.contains("ProAccess"))
            assertFalse(f, body.contains("push("))
        }
        assertTrue(vm.substringAfter("suspend fun publishCurrentProject(").substringBefore("\n    }\n").contains("ProAccess.gate("))
    }
}
