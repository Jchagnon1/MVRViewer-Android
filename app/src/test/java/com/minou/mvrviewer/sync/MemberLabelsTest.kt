package com.minou.mvrviewer.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Libellés des comptes d'un projet (SPEC-partage-moderation §4, §5.14 ; décision C-tous-12
 * R1/R2) : tout nom lu dans `memberNames`, dans la table locale des retraits ou dans
 * l'auteur d'une entrée du journal est découpé par [ProjectNames.trimEdges] ; vide → « Membre
 * sans nom » ; les homonymes se jugent sur les noms DÉCOUPÉS, par la même fonction
 * ([ProjectNames.labels]) dans la liste des membres, les personnes retirées, « Retirer… » et
 * l'historique. Entrées exactes de la relecture (même vecteurs sur iOS et le web).
 */
class MemberLabelsTest {

    private val unnamed = "Membre sans nom"

    private fun project(names: Map<String, String>, uids: List<String> = names.keys.toList()) =
        CloudProject("P", "k", "Show", uids.first(), uids, 2, null, null, 1.0, memberNames = names)

    private fun entry(uid: String, authorName: String = "nom de l'entrée") =
        AuditEntry("E1", 1.0, uid, authorName, "patch", "Projecteur 1", "Adresse", "1.1", "1.2")

    private fun history(e: AuditEntry, p: CloudProject) =
        ProjectNames.authorLabel(e, p.memberUids, p.memberNames.orEmpty(), unnamed)

    private fun list(p: CloudProject) = ProjectMembers.rows(p, "", unnamed).associate { it.uid to it.label }

    /** Un compte qui a un nom dans le projet : le MÊME libellé dans la liste, « Retirer… » et l'historique. */
    private fun assertSameEverywhere(p: CloudProject) {
        val rows = list(p)
        for (uid in p.memberNames.orEmpty().keys) {
            assertEquals(uid, rows[uid], ProjectMembers.label(uid, p, unnamed))
            assertEquals(uid, rows[uid], history(entry(uid), p))
        }
    }

    // MARK: Écart 2 de la relecture — entrées exactes

    @Test fun nomInvisible_membreSansNom_dansLaListeEtLHistorique() {
        val p = project(mapOf("aa110000" to "​"))
        assertEquals(mapOf("aa110000" to "Membre sans nom"), list(p))
        assertEquals("Membre sans nom", history(entry("aa110000"), p))
        assertSameEverywhere(p)
    }

    @Test fun homonymesJugesSurLesNomsDecoupes_dansLaListeEtLHistorique() {
        val p = project(mapOf("aa110000" to "Bob", "bb220000" to "​Bob"))
        assertEquals(mapOf("aa110000" to "Bob · AA11", "bb220000" to "Bob · BB22"), list(p))
        assertEquals("Bob · AA11", history(entry("aa110000"), p))
        assertEquals("Bob · BB22", history(entry("bb220000"), p))
        assertSameEverywhere(p)
    }

    // MARK: Autres bords et cas du journal

    @Test fun bordsDeLaListeExacte_memeLibellePartout() {
        val p = project(mapOf("aa110000" to "﻿Alice\u0085", "bb220000" to "Alice　", "cc330000" to " Carole "))
        assertEquals(mapOf("aa110000" to "Alice · AA11", "bb220000" to "Alice · BB22", "cc330000" to "Carole"), list(p))
        assertSameEverywhere(p)
    }

    /** Deux membres sans nom (l'un avec une clé invisible, l'autre sans clé) : homonymes, partout. */
    @Test fun deuxMembresSansNom_suffixesDansLaListeEtLHistorique() {
        val p = project(mapOf("aa110000" to "​"), uids = listOf("aa110000", "cc330000"))
        assertEquals(mapOf("aa110000" to "Membre sans nom · AA11", "cc330000" to "Membre sans nom · CC33"), list(p))
        assertEquals("Membre sans nom · AA11", history(entry("aa110000"), p))
        assertSameEverywhere(p)
        // Membre SANS clé dans `memberNames` : le nom écrit dans l'entrée (§5.14), découpé.
        assertEquals("Carole", history(entry("cc330000", " Carole "), p))
        assertEquals("Membre sans nom · CC33", history(entry("cc330000", "​"), p))
    }

    /** Auteur PARTI : le nom de l'entrée, découpé ; distingué d'un membre homonyme, qui garde son libellé de la liste. */
    @Test fun auteurParti_nomDeLEntreeDecoupe_homonymesSurLesNomsDecoupes() {
        val p = project(mapOf("aa110000" to "Bob", "bb220000" to "Alice"))
        assertEquals("Bob · EE55", history(entry("ee550000", " Bob　"), p))
        assertEquals("Membre sans nom", history(entry("dd440000", "​"), p))
        assertEquals("Carole", history(entry("dd440000", "﻿Carole"), p))
        assertEquals("Bob", history(entry("aa110000"), p))
        assertSameEverywhere(p)
        // Parti sans nom alors qu'un membre est sans nom : homonymes « Membre sans nom ».
        val q = project(mapOf("aa110000" to "​"))
        assertEquals("Membre sans nom · DD44", history(entry("dd440000", " "), q))
        assertEquals("Membre sans nom", history(entry("aa110000"), q))
    }

    /** Entrée ancienne sans auteur : son nom découpé, jamais de suffixe (pas d'uid). */
    @Test fun entreeSansAuteur_nomDecoupe() {
        val p = project(mapOf("aa110000" to "Alice", "bb220000" to "Alice"))
        assertEquals("Alice", history(entry("", " Alice"), p))
        assertEquals("Membre sans nom", history(entry("", "​"), p))
        assertEquals("Membre sans nom", history(entry("", ""), p))
    }

    /** Personnes retirées (table locale) : même fonction, mêmes règles. */
    @Test fun personnesRetirees_memesRegles() {
        val p = project(mapOf("oo000000" to "Owner")).copy(bannedUids = listOf("aa110000", "bb220000", "cc330000"))
        val rows = ProjectMembers.removedRows(p, mapOf("aa110000" to "Bob", "bb220000" to "​Bob", "cc330000" to "﻿"), unnamed)
            .associate { it.uid to it.label }
        assertEquals(mapOf("aa110000" to "Bob · AA11", "bb220000" to "Bob · BB22", "cc330000" to "Membre sans nom"), rows)
    }

    @Test fun libelles_fonctionCommune() {
        val names = linkedMapOf<String, String?>("aa110000" to "Bob", "bb220000" to "​Bob", "cc330000" to null, "dd440000" to "　")
        assertEquals(
            mapOf("aa110000" to "Bob · AA11", "bb220000" to "Bob · BB22",
                "cc330000" to "Membre sans nom · CC33", "dd440000" to "Membre sans nom · DD44"),
            ProjectNames.labels(names, unnamed)
        )
        assertEquals(mapOf("aa110000" to "Bob"), ProjectNames.labels(mapOf("aa110000" to "Bob​"), unnamed))
    }
}
