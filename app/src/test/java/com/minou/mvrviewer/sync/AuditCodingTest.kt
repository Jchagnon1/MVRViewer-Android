package com.minou.mvrviewer.sync

import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.ReferencePlanTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les valeurs brutes d'audit sont un FORMAT D'ÉCHANGE (relu par l'autre
 * plateforme et par une version future) : ces tests verrouillent l'aller-retour
 * et le comportement sur entrée corrompue — une entrée illisible doit rester
 * inerte, jamais réappliquer un état faux.
 */
class AuditCodingTest {

    @Test fun anchorsRoundTrip() {
        val a = listOf(GeoAnchor(1f, 2f, 48.85, 2.35), GeoAnchor(-3.5f, 4f, 48.86, 2.36))
        assertEquals(a, AuditCoding.decodeAnchors(AuditCoding.encodeAnchors(a)))
        assertTrue(AuditCoding.decodeAnchors("").isEmpty())
        assertTrue(AuditCoding.decodeAnchors("n'importe quoi").isEmpty())
    }

    @Test fun transformRoundTrip() {
        val t = ReferencePlanTransform(12.0, -4.0, 30.0, 1.25, 2.0, false)
        val d = AuditCoding.decodeTransform(AuditCoding.encodeTransform(t))!!
        assertEquals(t.offsetX, d.offsetX, 1e-9)
        assertEquals(t.rotationDeg, d.rotationDeg, 1e-9)
        assertEquals(t.scale, d.scale, 1e-9)
        assertEquals(t.visible, d.visible)
        assertNull(AuditCoding.decodeTransform(""))
        assertNull(AuditCoding.decodeTransform("1,2,3"))
    }

    @Test fun layersRoundTrip() {
        val s = setOf("MURS", "COTES", "TEXTE")
        assertEquals(s, AuditCoding.decodeLayers(AuditCoding.encodeLayers(s)))
        assertTrue(AuditCoding.decodeLayers("").isEmpty())
    }

    /** Une entrée sans coordonnées machine (ancienne, ou d'une autre version) n'est pas annulable. */
    @Test fun legacyEntryIsNotUndoable() {
        val legacy = AuditEntry("1", 0.0, "", "Moi", "patch", "Projecteur N° 213", "Adresse", "1.147", "1.189")
        assertFalse(legacy.isUndoable)
        // Un champ vidé (oldRaw == "") reste annulable : c'est une valeur, pas une absence.
        assertTrue(legacy.copy(fieldKey = AuditFieldKey.ADDRESS, oldRaw = "").isUndoable)
    }
}
