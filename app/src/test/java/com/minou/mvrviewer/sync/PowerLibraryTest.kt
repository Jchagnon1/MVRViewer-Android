package com.minou.mvrviewer.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le docId et la règle de résolution sont un CONTRAT PARTAGÉ iOS/Android : ces
 * tests les verrouillent. Une divergence de docId scinderait la bibliothèque
 * communautaire en deux ; une divergence de résolution donnerait des puissances
 * de câblage différentes selon la plateforme.
 */
class PowerLibraryTest {

    @Test fun docIdNormalizes() {
        // minuscules + trim + remplacement des caractères interdits Firestore.
        assertEquals("robe_esprite_wash", powerLibraryDocId("Robe/Esprite.Wash"))
        assertEquals("mac_aura_xip_", powerLibraryDocId("  MAC#Aura[XIP]  ")) // ] final → _
        assertEquals("ayrton khamsin", powerLibraryDocId("Ayrton Khamsin")) // espace interne conservé
        assertEquals("a_b_c", powerLibraryDocId("a\$b.c"))
    }

    @Test fun docIdStableAcrossEquivalentSpecs() {
        // Deux écritures d'un même type (casse/espaces) tombent sur le MÊME doc.
        assertEquals(powerLibraryDocId("Clay Paky Sharpy"), powerLibraryDocId("clay paky sharpy "))
    }

    @Test fun resolutionPrefersLibraryOverGdtf() {
        // La saisie utilisateur corrige un GDTF faux → elle est prioritaire.
        assertEquals(PowerResolution(600, PowerSource.LIBRARY), resolvePower(600, 750))
    }

    @Test fun resolutionFallsBackToGdtf() {
        assertEquals(PowerResolution(750, PowerSource.GDTF), resolvePower(null, 750))
    }

    @Test fun resolutionNoneWhenNeither() {
        assertEquals(PowerResolution(null, PowerSource.NONE), resolvePower(null, null))
    }

    @Test fun powerMapRoundTrip() {
        val e = PowerEntry("Robe Esprite", 750, "uid123", 1_700_000_000_000L)
        assertEquals(e, SectionCodec.powerFromMap(SectionCodec.powerToMap(e)))
    }

    @Test fun powerFromMapNullWithoutWatts() {
        assertNull(SectionCodec.powerFromMap(mapOf("spec" to "x")))
        assertNull(SectionCodec.powerFromMap(null))
    }

    // ---- CONSENSUS PAR VOTES (phase 2) -------------------------------------
    // L'algorithme est un CONTRAT PARTAGÉ iOS/Android : une divergence donnerait
    // des puissances de câblage différentes selon la plateforme. Ces tests le
    // verrouillent (clusters, cluster gagnant, médiane, égalité→médiane basse,
    // 1 vote, votes dispersés, seuils ±10 %/±20 W, compat ancien mono-valeur).

    @Test fun consensusNoVotes() {
        assertNull(powerConsensus(emptyList()))
    }

    @Test fun consensusSingleVote() {
        // Un seul vote (= aussi le cas d'un ancien doc mono-valeur relu comme
        // UN vote) : sa valeur, confiance 1, total 1.
        assertEquals(PowerConsensus(750, 1, 1), powerConsensus(listOf(750)))
    }

    @Test fun consensusOneClusterMedian() {
        // Trois votes proches (±10 %) → un seul cluster, médiane retenue.
        assertEquals(PowerConsensus(1050, 3, 3), powerConsensus(listOf(1000, 1050, 1100)))
    }

    @Test fun consensusBiggestClusterWins() {
        // Deux petits votes isolés + un gros amas de 3 → l'amas gagne, sa médiane.
        assertEquals(PowerConsensus(2010, 3, 5), powerConsensus(listOf(1000, 1050, 2000, 2010, 2020)))
    }

    @Test fun consensusTiePrefersLowestMedian() {
        // Deux clusters de même taille (2 et 2) → on retient la médiane la PLUS
        // BASSE (choix prudent). [100,110]→105 vs [300,310]→305 ⇒ 105.
        assertEquals(PowerConsensus(105, 2, 4), powerConsensus(listOf(100, 110, 300, 310)))
    }

    @Test fun consensusDispersedVotesPicksLowest() {
        // Trois votes trop éloignés → trois clusters de 1 : égalité de taille,
        // on retient le plus bas (prudent).
        assertEquals(PowerConsensus(100, 1, 3), powerConsensus(listOf(100, 500, 1000)))
    }

    @Test fun consensusTwentyWattFloorGroupsSmallValues() {
        // Petites valeurs : 10 % de 25 = 2,5 W (trop serré) mais le plancher de
        // ±20 W regroupe 10 et 25 → un cluster de 2 (médiane 18).
        assertEquals(PowerConsensus(18, 2, 2), powerConsensus(listOf(10, 25)))
    }

    @Test fun consensusTenPercentGroupsLargeValues() {
        // Grosses valeurs : ±20 W ne suffirait pas mais 10 % de 1090 = 109 W
        // regroupe 1000 et 1090 → cluster de 2 (médiane 1045).
        assertEquals(PowerConsensus(1045, 2, 2), powerConsensus(listOf(1000, 1090)))
    }

    @Test fun resolveFromVotesPrefersConsensusOverGdtf() {
        // Le consensus communautaire prime sur le GDTF, provenance LIBRARY.
        assertEquals(
            PowerResolution(1050, PowerSource.LIBRARY),
            resolvePowerFromVotes(listOf(1000, 1050, 1100), 750)
        )
    }

    @Test fun resolveFromVotesFallsBackToGdtf() {
        // Aucun vote → repli sur le GDTF.
        assertEquals(PowerResolution(750, PowerSource.GDTF), resolvePowerFromVotes(emptyList(), 750))
    }
}
