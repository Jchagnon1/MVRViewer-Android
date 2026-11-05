package com.minou.mvrviewer.ui

import com.minou.mvrviewer.sync.ProGrant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ÉCHÉANCE DE LA LICENCE APP OUVERTE (SPEC-abonnement §2.4, §3.7, §4.1.4-4.1.5,
 * recette R10 du §11.3 ; miroir de `ProAccessLifecycleTests` iOS et de `onDeadline`
 * du site) : une lecture serveur faite AVANT l'échéance ne vaut plus `onlineLicence`
 * après elle ; un minuteur à l'échéance (plafond 24 h, réarmé) fait retomber
 * `onlineLicence` et relit la licence. Une lecture en échec rend la licence INCONNUE
 * (jamais `pro: false`) ; une lecture en échec ou venue du cache Firestore fait
 * retomber `onlineLicence`. Horloge et minuteurs factices (JVM, sans Looper).
 */
class ProAccessLifecycleTest {

    private var now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    /** Faux minuteurs : échéances relatives à l'horloge factice, déclenchées par [advance]. */
    private inner class FakeTimers : ProTimers {
        inner class Entry(val at: Long, val delay: Long, val action: () -> Unit) { var cancelled = false }
        val entries = mutableListOf<Entry>()
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            val e = Entry(now + delayMs, delayMs, action)
            entries += e
            return { e.cancelled = true }
        }
        val pending: List<Entry> get() = entries.filter { !it.cancelled }
        /** Avance l'horloge et déclenche les minuteurs échus, dans l'ordre. */
        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = pending.filter { it.at <= now }.minByOrNull { it.at } ?: break
                due.cancelled = true
                due.action()
            }
        }
    }

    private lateinit var timers: FakeTimers
    private var refreshes = 0
    /** Ce que rend la relecture de la licence demandée par le minuteur (null : rien appliqué). */
    private var serverAnswer: ProGrant? = null

    @Before fun setUp() {
        ProAccess.forceForTests(null)
        ProAccess.resetForTests()
        timers = FakeTimers()
        ProAccess.timers = timers
        ProAccess.clock = { now }
        ProAccess.grantRefresher = { done ->
            refreshes++
            serverAnswer?.let { ProAccess.applyAccountLicence(it, UID) }
            done()
        }
    }

    @After fun tearDown() {
        ProAccess.resetForTests()
        ProAccess.grantRefresher = null
        ProAccess.forceForTests(null)
    }

    // MARK: - Règles pures

    @Test fun uneLectureFaiteAvantLEcheanceNeVautPlusApresElle() {
        val e = now
        val l = ProState.Licence(true, e)
        assertFalse("pas en ligne", ProState.licenceReadStillOnline(false, l, e - hour, e + minute))
        assertTrue("avant l'échéance", ProState.licenceReadStillOnline(true, l, e - hour, e - minute))
        assertFalse("lue avant, évaluée après", ProState.licenceReadStillOnline(true, l, e - hour, e + minute))
        assertTrue("lue après l'échéance (P6)", ProState.licenceReadStillOnline(true, l, e + 1, e + minute))
        assertTrue("lue à l'échéance", ProState.licenceReadStillOnline(true, l, e, e + minute))
        assertTrue("licence sans échéance", ProState.licenceReadStillOnline(true, ProState.Licence(true, null), e - hour, e + day))
        assertTrue("licence non Pro", ProState.licenceReadStillOnline(true, ProState.Licence(false, e), e - hour, e + minute))
        assertTrue("licence inconnue", ProState.licenceReadStillOnline(true, null, null, e))
    }

    @Test fun delaiDuMinuteurPlafonneA24h() {
        assertEquals(10 * minute + 1, ProState.licenceDeadlineDelay(ProState.Licence(true, now + 10 * minute), now))
        assertEquals(ProState.LICENCE_DEADLINE_CAP_MS + 1, ProState.licenceDeadlineDelay(ProState.Licence(true, now + 3 * day), now))
        assertNull(ProState.licenceDeadlineDelay(ProState.Licence(true, now), now))
        assertNull(ProState.licenceDeadlineDelay(ProState.Licence(true, null), now))
        assertNull(ProState.licenceDeadlineDelay(ProState.Licence(false, now + day), now))
        assertNull(ProState.licenceDeadlineDelay(null, now))
    }

    // MARK: - ProAccess

    @Test fun echeanceAppOuverteLeProResteParLeCacheJusquALaRelecture() {
        // Abonné App Store : expires = E exactement (pas de marge Apple), lu au serveur.
        val e = now + 10 * minute
        ProAccess.applyAccountLicence(ProGrant(true, e, fromServer = true), UID)
        assertEquals(ProState.Reason.LICENCE, ProAccess.proReason)
        assertEquals(listOf(10 * minute + 1), timers.pending.map { it.delay })
        // E + 1 min, AVANT même le minuteur (veille, retard) : le Pro reste, part licence gardée.
        now = e + minute
        assertTrue(ProAccess.isPro)
        assertEquals(ProState.Reason.OFFLINE, ProAccess.proReason)
        // Le minuteur échoit : onlineLicence retombe, cache intact, licence relue.
        now = e
        timers.advance(minute)
        assertEquals(1, refreshes)
        assertFalse(ProAccess.onlineLicence)
        assertTrue(ProAccess.isPro)
        assertEquals(ProState.Reason.OFFLINE, ProAccess.proReason)
        assertEquals(ProState.LicencePart(e, false), ProAccess.cacheForTests()?.licence)
        // Le serveur a déjà avancé la licence (DID_RENEW) : Pro sans interruption.
        val e2 = e + 365 * day
        ProAccess.applyAccountLicence(ProGrant(true, e2, fromServer = true), UID)
        assertEquals(ProState.Reason.LICENCE, ProAccess.proReason)
        assertEquals(ProState.LicencePart(e2, false), ProAccess.cacheForTests()?.licence)
        assertEquals(listOf(ProState.LICENCE_DEADLINE_CAP_MS + 1), timers.pending.map { it.delay })
    }

    @Test fun echeanceAppOuverteLicenceVraimentTermineeRelueApresElle() {
        val e = now + 10 * minute
        ProAccess.applyAccountLicence(ProGrant(true, e, fromServer = true), UID)
        // La relecture, faite APRÈS l'échéance, rend la même licence échue : c'est fini (P6).
        serverAnswer = ProGrant(true, e, fromServer = true)
        timers.advance(10 * minute + 1)
        assertEquals(1, refreshes)
        assertFalse(ProAccess.isPro)
        assertEquals(ProState.Reason.NONE, ProAccess.proReason)
        assertNull(ProAccess.cacheForTests()?.licence)
        assertTrue("plus rien à attendre", timers.pending.isEmpty())
    }

    @Test fun echeanceAppOuverteServeurInjoignableMargeDe72h() {
        val e = now + 10 * minute
        ProAccess.applyAccountLicence(ProGrant(true, e, fromServer = true), UID)
        timers.advance(10 * minute + 1)
        // Relecture en échec.
        ProAccess.applyAccountLicence(null, UID)
        assertTrue(ProAccess.isPro)
        assertEquals(ProState.Reason.OFFLINE, ProAccess.proReason)
        now = e + 71 * hour
        assertTrue(ProAccess.isPro)
        now = e + 73 * hour
        assertFalse(ProAccess.isPro)
    }

    @Test fun plafondDe24hAtteintMinuteurReArmeSansRelecture() {
        ProAccess.applyAccountLicence(ProGrant(true, now + 3 * day, fromServer = true), UID)
        timers.advance(ProState.LICENCE_DEADLINE_CAP_MS + 1)
        assertEquals(0, refreshes)
        assertTrue(ProAccess.onlineLicence)
        assertEquals(1, timers.pending.size)
        assertEquals(ProState.Reason.LICENCE, ProAccess.proReason)
    }

    @Test fun lectureEnEchecLicenceInconnueJamaisProFalse() {
        ProAccess.applyAccountLicence(ProGrant(true, now + day, fromServer = true), UID)
        ProAccess.applyAccountLicence(null, UID)
        assertFalse(ProAccess.onlineLicence)
        // Le cache daté garde le Pro ; la dernière licence lue reste affichée.
        assertTrue(ProAccess.isPro)
        assertEquals(ProState.Reason.OFFLINE, ProAccess.proReason)
        assertTrue(ProAccess.grantedByAccount)
        // Licence inconnue : aucun minuteur sur une échéance qu'on ne connaît plus.
        assertTrue(timers.pending.isEmpty())
        // Lecture serveur suivante : pro false — le Pro du compte a vraiment pris fin.
        ProAccess.applyAccountLicence(ProGrant(false, fromServer = true), UID)
        assertFalse(ProAccess.isPro)
        assertNull(ProAccess.cacheForTests()?.licence)
    }

    @Test fun lectureDuCacheFirestoreFaitRetomberOnlineLicence() {
        ProAccess.applyAccountLicence(ProGrant(true, now + day, fromServer = true), UID)
        assertTrue(ProAccess.onlineLicence)
        ProAccess.applyAccountLicence(ProGrant(false, fromServer = false), UID)
        assertFalse(ProAccess.onlineLicence)
        assertTrue("part licence gardée", ProAccess.isPro)
        assertEquals(ProState.Reason.OFFLINE, ProAccess.proReason)
    }

    @Test fun forcageDebugAucunMinuteur() {
        ProAccess.forceForTests(true)
        ProAccess.applyAccountLicence(ProGrant(true, now + hour, fromServer = true), UID)
        assertTrue(timers.pending.isEmpty())
    }

    private companion object {
        const val UID = "u-lifecycle"
    }
}
