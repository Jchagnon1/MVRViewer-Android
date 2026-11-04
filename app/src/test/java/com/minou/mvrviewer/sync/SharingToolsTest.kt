package com.minou.mvrviewer.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outils communs du partage (SPEC-partage-moderation §4, §5.1, §5.13) — mêmes
 * vecteurs que rules-tests/test/03-moderation (`nomAffiche`, `truncateUtf16`) et
 * qu'iOS (SharingToolsTests).
 */
class SharingToolsTest {

    private val emoji = "😀" // U+1F600, 2 unités UTF-16

    @Test fun troncatureEnUnitesUtf16SansCouperUnPointDeCode() {
        assertEquals("a", Utf16Text.truncated("a$emoji", 2))
        assertEquals("a$emoji", Utf16Text.truncated("a$emoji", 3))
        assertEquals("é".repeat(80), Utf16Text.truncated("é".repeat(100), 80))
        // 41 émojis = 82 unités : 40 restent (80 unités, la limite de la règle).
        val forty = Utf16Text.truncated(emoji.repeat(41), 80)
        assertEquals(80, forty.length)
        assertEquals(40, forty.codePointCount(0, forty.length))
        // « e + accent combinant » : deux points de code d'une unité chacun.
        assertEquals("é".repeat(40), Utf16Text.truncated("é".repeat(41), 80))
        // Famille 👨‍👩‍👧‍👦 = 11 unités : coupée ENTRE points de code, jamais au milieu d'une paire.
        val family = "👨‍👩‍👧‍👦"
        assertEquals(11, family.length)
        val cut = Utf16Text.truncated(family, 4)
        assertEquals("👨‍", cut)
        assertFalse(Character.isHighSurrogate(cut.last()))
        assertEquals("", Utf16Text.truncated(emoji, 1))
    }

    @Test fun nomAfficheJamaisLEMailComplet() {
        val long = ProjectNames.displayName(emoji.repeat(100), "bob@exemple.test")
        assertEquals(80, long.length)
        assertEquals(40, long.codePointCount(0, long.length))
        assertEquals("bob", ProjectNames.displayName("  bob@exemple.test ", "bob@exemple.test"))
        assertEquals("bob", ProjectNames.displayName("", "bob@exemple.test"))
        assertEquals("bob", ProjectNames.displayName("   ", "bob@exemple.test"))
        assertEquals("Bob Martin", ProjectNames.displayName("  Bob Martin  ", "bob@exemple.test"))
        // Inscription Android sans nom : displayName = e-mail → partie avant « @ ».
        val acc = AccountInfo("uid-1", "jm@example.com", "jm@example.com")
        assertEquals("jm", ProjectNames.displayName(acc))
    }

    /**
     * Relecture : Firebase Auth rend l'e-mail en MINUSCULES, alors que l'inscription sans
     * nom enregistre l'adresse TELLE QUE SAISIE comme nom. Comparée à la lettre, elle
     * devenait le nom du compte dans le projet (liste des membres, journal, signalement)
     * dès la reprise de session. L'égalité se juge à la casse près, et la partie avant
     * « @ » garde la casse saisie : même nom dans la session d'inscription et après.
     */
    @Test fun nomAfficheEMailALaCassePres() {
        assertEquals("Bob", ProjectNames.displayName("Bob@Example.com", "bob@example.com"))
        assertEquals("Casse.Device", ProjectNames.displayName("Casse.Device@Exemple.test", "casse.device@exemple.test"))
        assertEquals("BOB", ProjectNames.displayName(" BOB@EXAMPLE.COM ", "bob@example.com"))
        assertEquals("bob", ProjectNames.displayName("bob@example.com", " bob@example.com "))
        // Session d'inscription (adresse saisie) puis reprise (adresse rendue par Firebase) :
        // le MÊME nom, donc aucune réécriture de memberNames à chaque ouverture (§5.8).
        val inscription = AccountInfo("u-1", "Casse.Device@Exemple.test", "Casse.Device@Exemple.test")
        val reprise = AccountInfo("u-1", "casse.device@exemple.test", "Casse.Device@Exemple.test")
        assertEquals("Casse.Device", ProjectNames.displayName(inscription))
        assertEquals("Casse.Device", ProjectNames.displayName(reprise))
        // Connexion sans profil Firebase (displayName absent) : l'adresse RENDUE par Firebase
        // sert de nom, comme à la reprise et sur le web — jamais l'adresse saisie, qui donnait
        // « Casse.Device » à la connexion puis « casse.device » après relance (§5.8 réécrivait).
        val connexion = AccountInfo.fromAuth("u-1", "casse.device@exemple.test", null, typedEmail = "Casse.Device@Exemple.test")
        assertEquals(AccountInfo.fromAuth("u-1", "casse.device@exemple.test", null), connexion)
        assertEquals("casse.device", ProjectNames.displayName(connexion))
        // Un vrai nom n'est jamais touché, même s'il ressemble à la partie avant « @ ».
        assertEquals("Casse Device", ProjectNames.displayName("Casse Device", "casse.device@exemple.test"))
        for (acc in listOf(inscription, reprise, connexion)) assertFalse(ProjectNames.displayName(acc).contains('@'))
    }

    @Test fun nomDuProjetTronqueA200Unites() {
        assertEquals(200, ProjectNames.projectName("x".repeat(255)).length)
        assertEquals("Show.mvr", ProjectNames.projectName("Show.mvr"))
        val emojis = ProjectNames.projectName(emoji.repeat(150))
        assertEquals(200, emojis.length)
    }

    @Test fun codesDePartage() {
        repeat(500) {
            val c = ShareCodes.generate()
            assertTrue(c, ShareCodes.isWellFormed(c))
        }
        assertEquals("ABC2DE", ShareCodes.normalized(" ab c\t2de\n"))
        for (bad in listOf("", "ABCDE", "ABCDEFG", "ABCDE1", "ABCDE0", "ABCDEI", "ABCDEO", "abcdef", "ABC/EF", "ABC EF")) {
            assertFalse(bad, ShareCodes.isWellFormed(bad))
        }
        assertTrue(ShareCodes.isWellFormed("CQDE22"))
        assertEquals("mvrviewer://join/CQDE22", ShareCodes.url("CQDE22"))
    }

    @Test fun homonymesDistinguesParLUid() {
        val names = mapOf("3f9a77" to "Alice", "b2c4d6" to "Alice", "c0ffee" to "Bob")
        assertEquals("Alice · 3F9A", ProjectNames.label("3f9a77", "Alice", names))
        assertEquals("Alice · B2C4", ProjectNames.label("b2c4d6", "Alice", names))
        assertEquals("Bob", ProjectNames.label("c0ffee", "Bob", names))
        // Auteur du journal : son nom DANS LE PROJET d'abord, sinon celui de l'entrée.
        val e = AuditEntry("E1", 1.0, "c0ffee", "ancien nom", "patch", "Projecteur 1", "Adresse", "1.1", "1.2")
        assertEquals("Bob", ProjectNames.authorLabel(e, names))
        assertEquals("ancien nom", ProjectNames.authorLabel(e.copy(authorUid = "parti"), names))
        assertEquals("Alice · 3F9A", ProjectNames.authorLabel(e.copy(authorUid = "3f9a77"), names))
        // Entrée ancienne sans auteur : son nom tel quel.
        assertEquals("Alice", ProjectNames.authorLabel(e.copy(authorUid = "", authorName = "Alice"), names))
    }

    /** Opération fictive d'un lot : (projet, libellé). */
    private data class Op(val project: String?, val label: String)

    @Test fun lotsDeDixProjetsAuPlusSansCouperPierreTombaleEtProjet() {
        // 25 projets solo stricts : sections, journal, invitation, puis [pierre tombale, projet].
        val groups = (1..25).map { i ->
            val p = "P$i"
            WriteLots.Group(p, listOf(
                listOf(Op(p, "section")), listOf(Op(p, "audit")), listOf(Op(p, "invite")),
                listOf(Op(p, "tombstone"), Op(p, "project"))
            ))
        } + WriteLots.Group(null, listOf(listOf(Op(null, "myInvite")), listOf(Op(null, "profile"))))
        val lots = WriteLots.plan(groups)
        assertEquals(3, lots.size)
        for (lot in lots) assertTrue(lot.mapNotNull { it.project }.toSet().size <= 10)
        // Ordre conservé, rien de perdu, profil en dernier.
        assertEquals(groups.flatMap { g -> g.units.flatten() }, lots.flatten())
        assertEquals("profile", lots.last().last().label)
        // [pierre tombale, projet] toujours dans le même lot.
        for (lot in lots) for ((i, op) in lot.withIndex()) {
            if (op.label == "tombstone") assertEquals("project", lot[i + 1].label)
        }
    }

    @Test fun gros_journal_plusieursLotsEtUniteInsecableEnDernier() {
        // 460 entrées de journal (> 450) : la pierre tombale et le projet partent ensemble, à la fin.
        val p = "BIG"
        val units = (1..460).map { listOf(Op(p, "audit$it")) } + listOf(listOf(Op(p, "tombstone"), Op(p, "project")))
        val lots = WriteLots.plan(listOf(WriteLots.Group(p, units)))
        assertEquals(2, lots.size)
        for (lot in lots) assertTrue(lot.size <= WriteLots.MAX_OPS)
        assertEquals(listOf("tombstone", "project"), lots.last().takeLast(2).map { it.label })
        // Unité de 2 qui ne tient plus : elle passe ENTIÈRE au lot suivant.
        val tight = (1..449).map { listOf(Op(p, "a$it")) } + listOf(listOf(Op(p, "tombstone"), Op(p, "project")))
        val two = WriteLots.plan(listOf(WriteLots.Group(p, tight)))
        assertEquals(listOf(449, 2), two.map { it.size })
    }

    /** C-tous-4 : projet ancien lu tel quel ; champs du partage lus tolérants ; nuls omis à la création. */
    @Test fun documentProjet_lectureToleranteEtEcritureSansNuls() {
        val legacy = mapOf<String, Any?>("id" to "P", "contentKey" to "k", "name" to "Show", "ownerUid" to "a",
            "memberUids" to listOf("a", "b"), "mvrVersion" to 2L, "mvrBlobSHA" to "a".repeat(64), "updatedAtEpoch" to 1.5)
        val p = SectionCodec.projectFromMap(legacy)!!
        assertEquals(null, p.inviteCode)
        assertEquals(null, p.bannedUids)
        assertEquals(null, p.memberNames)
        assertFalse(p.isStrict)
        val strict = legacy + mapOf("inviteCode" to "CQDE22", "bannedUids" to listOf("c"),
            "memberNames" to mapOf("a" to "Alice", "b" to 42), "joinProof" to mapOf("uid" to "b"))
        val s = SectionCodec.projectFromMap(strict)!!
        assertTrue(s.isStrict)
        assertEquals(listOf("c"), s.bannedUids)
        assertEquals(mapOf("a" to "Alice"), s.memberNames) // valeur non textuelle ignorée
        // Écriture ENTIÈRE (création seulement) : les champs nuls sont omis, jamais `null`.
        assertEquals(setOf("id", "contentKey", "name", "ownerUid", "memberUids", "mvrVersion", "mvrBlobSHA", "updatedAtEpoch"),
            SectionCodec.projectToMap(p).keys)
        val created = SectionCodec.projectToMap(s.copy(bannedUids = null, mvrBlobSHA = null))
        assertEquals(setOf("id", "contentKey", "name", "ownerUid", "memberUids", "mvrVersion", "updatedAtEpoch",
            "inviteCode", "memberNames"), created.keys)
        assertEquals(2L, created["mvrVersion"])
    }

    @Test fun groupesSansProjetNeComptentPasDansLaLimite() {
        val groups = (1..30).map { WriteLots.Group<Op>(null, listOf(listOf(Op(null, "update$it")))) }
        assertEquals(1, WriteLots.plan(groups).size)
    }
}
