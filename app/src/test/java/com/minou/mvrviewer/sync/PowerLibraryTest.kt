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
}
