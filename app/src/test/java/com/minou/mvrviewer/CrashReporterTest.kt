package com.minou.mvrviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bouton « Envoyer le rapport » de l'accueil (guide C9) : visible SEULEMENT si le
 * journal de diagnostic contient un plantage ou un gel — le journal note aussi chaque
 * démarrage, il n'est donc jamais vide. Entrées construites avec le MÊME code que
 * celui qui les écrit ([CrashReporter.entry]).
 */
class CrashReporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val banner = "\n===== 2026-11-03 20:00:00 | samsung SM-A556B | Android 14 (API 34) ====="
    private fun startup() = CrashReporter.entry(banner, "démarrage de l'appli", null)
    private fun note(msg: String) = CrashReporter.entry(banner, "· $msg", null)
    private fun crash() = CrashReporter.entry(banner, CrashReporter.crashTag("main"),
        "java.lang.IllegalStateException: boum\n\tat com.minou.mvrviewer.X.y(X.kt:1)")
    private fun freeze() = CrashReporter.entry(banner, CrashReporter.FREEZE_TAG,
        "Pile du thread principal :\n    at android.os.MessageQueue.nativePollOnce(Native Method)")

    @Test fun journalVideOuDemarragesSeulsPasDeBouton() {
        assertFalse(CrashReporter.containsIncident(""))
        assertFalse(CrashReporter.containsIncident(startup()))
        assertFalse(CrashReporter.containsIncident(startup() + note("ouverture vue plan") + startup()))
    }

    @Test fun plantageOuGelBouton() {
        assertTrue(CrashReporter.containsIncident(startup() + crash()))
        assertTrue(CrashReporter.containsIncident(startup() + freeze() + startup()))
        assertTrue(CrashReporter.containsIncident(crash() + freeze()))
    }

    @Test fun enTetesIdentiquesAuxEntreesHistoriques() {
        // Journaux déjà sur les appareils des testeurs (avant ce changement) : mêmes en-têtes.
        assertEquals("PLANTAGE (exception non rattrapée) — thread « main »", CrashReporter.crashTag("main"))
        assertEquals("GEL / ANR : thread principal bloqué > 6 s", CrashReporter.FREEZE_TAG)
    }

    @Test fun lesMotsDansUneNoteNeComptentPas() {
        // Seul un EN-TÊTE (début de ligne) compte, pas une note qui cite les mots.
        assertFalse(CrashReporter.containsIncident(note("test GEL / ANR et PLANTAGE (exception non rattrapée)")))
    }

    @Test fun fichier() {
        val f = File(tmp.root, "mvrviewer_diag.log")
        assertFalse("absent", CrashReporter.hasIncident(f))
        f.writeText("")
        assertFalse("vide", CrashReporter.hasIncident(f))
        f.writeText(startup() + startup())
        assertFalse("démarrages seuls", CrashReporter.hasIncident(f))
        f.appendText(freeze())
        assertTrue("gel", CrashReporter.hasIncident(f))
    }
}
