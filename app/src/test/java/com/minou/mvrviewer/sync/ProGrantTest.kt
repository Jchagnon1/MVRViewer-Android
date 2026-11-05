package com.minou.mvrviewer.sync

import com.google.firebase.Timestamp
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

/**
 * LICENCE PRO DU COMPTE ([SyncService.fetchProGrant] → [ProGrant], SPEC-abonnement
 * §3.7, §4.1.4) sur le stub [LocalSyncService], instancié SANS Context. Verrouille le
 * CONTRAT partagé avec le backend réel : `null` (INCONNU) si déconnecté ou si la
 * lecture échoue — jamais `pro: false` sur une erreur —, `pro: false` sans
 * document, la licence suit le COMPTE ; la lecture d'un document
 * `entitlements/{uid}` daté avec ses sources ; la règle D8 (§4.1.5 : seul un
 * Timestamp est une date), rejouée sur les vecteurs communs `licence.json › lecture` ;
 * et la vérification de l'adresse e-mail (§4.1.6).
 */
class ProGrantTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun service() = LocalSyncService.forTesting(tmp.newFolder("CloudSim"))

    @Test fun fetchProGrantIsUnknownWhenSignedOut() = runBlocking {
        val svc = service()
        assertNull(svc.fetchProGrant())
        // Même si des licences existent : sans compte, personne n'en bénéficie.
        svc.proGrants += "someone"
        assertNull(svc.fetchProGrant())
    }

    @Test fun fetchProGrantIsFalseForAccountWithoutDocument() = runBlocking {
        val svc = service()
        svc.signUp("jm@example.com", "secret123", "JM")
        assertEquals(ProGrant(pro = false, fromServer = true), svc.fetchProGrant())
    }

    @Test fun aFailedReadIsUnknownNeverFalse() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        svc.proGrants += acc.uid
        svc.proGrantFetchFails = true
        assertNull(svc.fetchProGrant())
        svc.proGrantFetchFails = false
        assertEquals(true, svc.fetchProGrant()?.pro)
    }

    @Test fun proGrantFollowsTheAccountNotTheDevice() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        svc.proGrants += acc.uid
        assertTrue(svc.fetchProGrant()!!.isActive(System.currentTimeMillis()))
        svc.signOut()
        assertNull(svc.fetchProGrant())
        svc.signUp("other@example.com", "secret123", "Autre")
        assertEquals(false, svc.fetchProGrant()?.pro)
        svc.signOut()
        svc.signIn("jm@example.com", "secret123")
        assertEquals(true, svc.fetchProGrant()?.pro)
    }

    @Test fun datedDocumentWithSources() = runBlocking {
        val svc = service()
        val acc = svc.signUp("jm@example.com", "secret123", "JM")
        val grant = ProGrant(true, 1_808_661_600_000L,
            play = ProGrant.SourceView(true, "active", 1_808_640_000_000L, null, true), fromServer = false)
        svc.proGrantDocuments[acc.uid] = grant
        // Le stub n'a pas de cache hors ligne : toujours une lecture « serveur ».
        assertEquals(grant.copy(fromServer = true), svc.fetchProGrant())
    }

    // MARK: - Document `entitlements/{uid}` (FirebaseSyncService → valeurs simples)

    @Test fun fromDocumentReadsExpiresAndSources() {
        // Valeurs simples de `plainDocument` : un Timestamp y est devenu une Date.
        val g = ProGrant.fromDocument(mapOf(
            "pro" to true,
            "expires" to Date(1_808_661_600_000L),
            "sources" to mapOf(
                "appstore" to mapOf("active" to false, "status" to "expired", "expiresAt" to Date(1_790_000_000_000L),
                    "graceUntil" to null, "autoRenew" to false),
                "play" to mapOf("active" to true, "status" to "grace", "expiresAt" to Date(1_808_640_000_000L),
                    "graceUntil" to Date(1_809_000_000_000L), "autoRenew" to true),
                "admin" to mapOf("active" to false, "origin" to "manual")
            )
        ), fromServer = true)
        assertTrue(g.pro)
        assertEquals(1_808_661_600_000L, g.expires)
        assertEquals(ProGrant.SourceView(false, "expired", 1_790_000_000_000L, null, false), g.appstore)
        assertEquals(ProGrant.SourceView(true, "grace", 1_808_640_000_000L, 1_809_000_000_000L, true), g.play)
        assertEquals(1_809_000_000_000L, g.play!!.effectiveEnd)
        assertTrue(g.fromServer)
    }

    @Test fun fromDocumentKeepsTheOldRules() {
        val now = 1_800_000_000_000L
        // Licence admin sans échéance.
        assertTrue(ProGrant.fromDocument(mapOf("pro" to true), true).isActive(now))
        // Échéance passée : plus active.
        assertFalse(ProGrant.fromDocument(mapOf("pro" to true, "expires" to Date(now - 1)), true).isActive(now))
        // `expires` d'une autre forme (RawValue admin recopiée telle quelle) : ignorée.
        assertTrue(ProGrant.fromDocument(mapOf("pro" to true, "expires" to "2027-12-31"), true).isActive(now))
        // D8, même sans `plainDocument` : un nombre n'est jamais une date (ni ms, ni secondes).
        for (n in listOf<Any>(now - 1, 1_700_000_000, 1_830_000_000_000.0)) {
            val g = ProGrant.fromDocument(mapOf("pro" to true, "expires" to n,
                "sources" to mapOf("play" to mapOf("active" to true, "expiresAt" to n, "graceUntil" to n))), true)
            assertNull("$n", g.expires)
            assertNull("$n", g.play?.expiresAt)
            assertNull("$n", g.play?.graceUntil)
        }
        assertNull(ProGrant.date(now))
        assertEquals(now, ProGrant.date(Date(now)))
        // `pro` absent ou d'un autre type : non Pro.
        assertFalse(ProGrant.fromDocument(mapOf("pro" to "true"), true).pro)
        assertFalse(ProGrant.fromDocument(emptyMap(), true).pro)
    }

    /**
     * Document Firestore → valeurs simples ([ProGrant.plainDocument], appelé par
     * `FirebaseSyncService.plainValues`) : sous une clé de temps, SEUL un Timestamp
     * est une date ; un nombre, un texte ou une table saisis à la main (RawValue que
     * le serveur recopie, §3.7) sont opaques — retirés, jamais lus comme des
     * millisecondes. Avant : `{pro: true, expires: 1700000000}` (secondes saisies dans
     * la console) donnait un Pro expiré en 1970 sur Android et sans échéance sur le
     * site. Mêmes entrées que `ProGrantTests.testNonTimestampTimeValuesAreOpaque` (iOS).
     */
    @Test fun nonTimestampTimeValuesAreOpaque() {
        class FakeTimestamp(val ms: Long)
        val stamp = 1_825_521_600_000L
        val raw: Map<String, Any?> = mapOf(
            "pro" to true,
            "expires" to 1_700_000_000L,
            "sources" to mapOf(
                "play" to mapOf("active" to true, "status" to "active", "expiresAt" to FakeTimestamp(stamp),
                    "graceUntil" to "2027-01-01", "autoRenew" to true, "basePlanId" to "annual"),
                "appstore" to mapOf("active" to false, "expiresAt" to 1_830_000_000_000.0,
                    "revokedAt" to mapOf("seconds" to 1_800_000_000L)),
                "admin" to mapOf("active" to true, "origin" to "manual", "count" to 3L)
            )
        )
        val plain = ProGrant.plainDocument(raw) { (it as? FakeTimestamp)?.let { t -> Date(t.ms) } }
        assertFalse("nombre sous `expires` : opaque", plain.containsKey("expires"))
        assertEquals(true, plain["pro"])
        val sources = plain["sources"] as Map<*, *>
        val play = sources["play"] as Map<*, *>
        assertEquals("Timestamp → Date", Date(stamp), play["expiresAt"])
        assertFalse("texte sous une clé de temps : opaque", play.containsKey("graceUntil"))
        assertEquals("annual", play["basePlanId"])
        val appstore = sources["appstore"] as Map<*, *>
        assertFalse("nombre (ms) sous une clé de temps : opaque", appstore.containsKey("expiresAt"))
        assertFalse("table { seconds } : opaque", appstore.containsKey("revokedAt"))
        assertEquals("hors clé de temps : inchangé", 3L, (sources["admin"] as Map<*, *>)["count"])

        val grant = ProGrant.fromDocument(plain, fromServer = true)
        assertTrue(grant.pro)
        assertNull("licence sans échéance, comme sur le site", grant.expires)
        assertTrue(grant.isActive(1_800_000_000_000L))
        assertEquals(stamp, grant.play?.expiresAt)
        assertNull(grant.play?.graceUntil)
        assertNull(grant.appstore?.expiresAt)

        // Entier ou flottant écrit dans Firestore : jamais une échéance.
        for (n in listOf<Any>(1_700_000_000, 1_830_000_000_000L, 1_830_000_000_000.0)) {
            assertNull("$n", ProGrant.fromDocument(ProGrant.plainDocument(mapOf("pro" to true, "expires" to n)) { null }, true).expires)
        }
    }

    // MARK: - Vecteurs communs `licence.json › lecture` (D8, §4.1.5)

    /** Vecteurs communs du dépôt web (`tests/fixtures/abonnement/licence.json`, copiés
     *  dans `src/test/resources/port-vectors/abonnement/`). */
    private val licenceVectors: JSONObject by lazy {
        val url = javaClass.classLoader!!.getResource("port-vectors/abonnement/licence.json")!!
        JSONObject(File(url.toURI()).readText())
    }

    private fun longOrNull(o: JSONObject, key: String): Long? = if (o.isNull(key)) null else o.getLong(key)

    /** Valeur du vecteur → valeur telle que le SDK Firestore Android la rend :
     *  `{ "$timestamp": ms }` = un VRAI [Timestamp] ; entiers en Long, décimaux en
     *  Double ; une table `{ seconds }` reste une table. */
    private fun firestoreValue(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject ->
            if (v.has("\$timestamp")) Timestamp(Date(v.getLong("\$timestamp")))
            else v.keys().asSequence().associateWith { firestoreValue(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { firestoreValue(v.get(it)) }
        is Int -> v.toLong()
        is Long -> v
        is Number -> v.toDouble()
        else -> v
    }

    /** LD1-LD9 contre la lecture RÉELLE de `FirebaseSyncService` ([proGrantFromFirestore],
     *  vrais Timestamp) : seul un Timestamp est une date ; nombre, chaîne, table
     *  `{ seconds }` → absent. `revokedAt` n'est pas exposé par [ProGrant.SourceView] :
     *  ignoré, comme le permet le champ `form`. */
    @Test fun lesVecteursCommunsLectureLD1aLD9() {
        assertEquals("mvrviewer.vectors.licence/1", licenceVectors.getString("schema"))
        val list = licenceVectors.getJSONObject("lecture").getJSONArray("vectors")
        val vectors = (0 until list.length()).map { list.getJSONObject(it) }
        assertEquals((1..9).map { "LD$it" }, vectors.map { it.getString("id") })
        for (v in vectors) {
            val id = v.getString("id") + " — " + v.getString("note")
            @Suppress("UNCHECKED_CAST")
            val document = firestoreValue(v.get("document")) as Map<String, Any?>?
            val g = proGrantFromFirestore(document, fromServer = true)
            val expect = v.getJSONObject("expect")
            assertEquals("$id : pro", expect.getBoolean("pro"), g.pro)
            assertEquals("$id : expires", longOrNull(expect, "expires"), g.expires)
            val sources = expect.optJSONObject("sources") ?: continue
            for (store in sources.keys()) {
                val view = when (store) {
                    "appstore" -> g.appstore
                    "play" -> g.play
                    else -> throw AssertionError("$id : boutique inconnue $store")
                }
                val keys = sources.getJSONObject(store)
                for (key in keys.keys()) {
                    val actual = when (key) {
                        "expiresAt" -> view?.expiresAt
                        "graceUntil" -> view?.graceUntil
                        else -> continue
                    }
                    assertEquals("$id : sources.$store.$key", longOrNull(keys, key), actual)
                }
            }
        }
    }

    @Test fun aSourceIsLiveOnlyIfActiveAndInTheFuture() {
        val now = 1_800_000_000_000L
        assertTrue(ProGrant.SourceView(true, "active", now + 1).isLive(now))
        assertFalse(ProGrant.SourceView(true, "active", now - 1).isLive(now)) // active mais échue
        assertTrue(ProGrant.SourceView(true, "grace", now - 1, now + 1).isLive(now))
        assertFalse(ProGrant.SourceView(false, "expired", now + 1).isLive(now))
        assertFalse(ProGrant.SourceView(true, "active").isLive(now))
    }

    // MARK: - Adresse e-mail vérifiée (§4.1.6)

    @Test fun emailVerificationOnTheStub() = runBlocking {
        val svc = service()
        assertFalse(svc.verifiesPurchases)
        assertNull(svc.reloadEmailVerified())
        try { svc.sendEmailVerification(); fail("déconnecté") } catch (e: SyncException.NotSignedIn) { }
        svc.signUp("JM@example.com", "secret123", "JM")
        assertEquals(false, svc.reloadEmailVerified())
        svc.sendEmailVerification()
        assertEquals(listOf("jm@example.com"), svc.sentEmailVerifications)
        svc.verifiedEmails += "jm@example.com"
        assertEquals(true, svc.reloadEmailVerified())
        svc.simulatesOffline = true
        try { svc.sendEmailVerification(); fail("hors ligne") } catch (e: SyncException.NetworkUnavailable) { }
    }
}
