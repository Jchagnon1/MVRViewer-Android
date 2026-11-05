package com.minou.mvrviewer.ui

import com.minou.mvrviewer.sync.ProGrant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ÉTAT PRO SUR L'APPAREIL (SPEC-abonnement §2.4) : la fonction pure [ProState.evaluate]
 * rejoue TELS QUELS les vecteurs communs P1-P15 du dépôt web
 * (`tests/fixtures/abonnement/pro-state.json`, copiés dans
 * `src/test/resources/port-vectors/abonnement/`), comme iOS et le web ; plus la
 * mémoire du cache (`pro.cache`, anciennes clés) et son branchement dans [ProAccess].
 */
class ProStateTest {

    private val vectors: JSONObject by lazy {
        val url = javaClass.classLoader!!.getResource("port-vectors/abonnement/pro-state.json")!!
        JSONObject(File(url.toURI()).readText())
    }

    private fun longOrNull(o: JSONObject, key: String): Long? = if (o.isNull(key)) null else o.getLong(key)

    private fun cacheOf(o: JSONObject?): ProState.Cache? {
        if (o == null) return null
        val store = o.optJSONObject("store")?.let { ProState.StorePart(it.getLong("until")) }
        val licence = o.optJSONObject("licence")?.let {
            ProState.LicencePart(longOrNull(it, "until"), it.getBoolean("perpetual"))
        }
        return ProState.Cache(store, licence)
    }

    private fun inputOf(i: JSONObject) = ProState.Input(
        now = i.getLong("now"),
        override = if (i.isNull("override")) null else i.getBoolean("override"),
        store = i.optJSONObject("store")?.let { ProState.Store(it.getBoolean("active"), longOrNull(it, "until")) },
        licence = i.optJSONObject("licence")?.let { ProState.Licence(it.getBoolean("pro"), longOrNull(it, "expires")) },
        onlineStore = i.getBoolean("onlineStore"),
        onlineLicence = i.getBoolean("onlineLicence"),
        cache = cacheOf(i.optJSONObject("cache"))
    )

    @Test fun lesQuinzeVecteursCommunsP1aP15() {
        assertEquals("mvrviewer.vectors.pro-state/1", vectors.getString("schema"))
        assertEquals(vectors.getJSONObject("constants").getLong("MARGE_HORS_LIGNE_MS"), ProState.OFFLINE_MARGIN_MS)
        val list = vectors.getJSONArray("vectors")
        assertEquals((1..15).map { "P$it" }, (0 until list.length()).map { list.getJSONObject(it).getString("id") })
        for (k in 0 until list.length()) {
            val v = list.getJSONObject(k)
            val id = v.getString("id")
            val out = ProState.evaluate(inputOf(v.getJSONObject("input")))
            val expect = v.getJSONObject("expect")
            assertEquals("$id pro", expect.getBoolean("pro"), out.pro)
            assertEquals("$id raison", expect.getString("reason"), out.reason.code)
            assertEquals("$id cache après", cacheOf(expect.getJSONObject("cache")), out.cache)
        }
    }

    @Test fun lesRaisonsOntLesCodesDeLaSpec() {
        assertEquals(listOf("override", "store", "licence", "hors-ligne", "aucun"), ProState.Reason.entries.map { it.code })
        assertEquals(72L * 3_600_000L, ProState.OFFLINE_MARGIN_MS)
    }

    // MARK: - Mémoire `pro.cache`

    @Test fun cacheEnregistreEnJsonUidStoreLicence() {
        val a = ProCacheRecord("uid-1", ProState.StorePart(1_802_592_000_000L), ProState.LicencePart(1_817_280_000_000L, false))
        assertEquals(a, ProCacheRecord.fromJson(a.toJson()))
        val o = JSONObject(a.toJson())
        assertEquals(setOf("uid", "store", "licence"), o.keys().asSequence().toSet())
        val perpetual = ProCacheRecord(null, null, ProState.LicencePart(null, true))
        assertEquals(perpetual, ProCacheRecord.fromJson(perpetual.toJson()))
        val empty = ProCacheRecord(null, ProState.Cache.EMPTY)
        assertEquals(empty, ProCacheRecord.fromJson(empty.toJson()))
        assertNull(ProCacheRecord.fromJson(null))
        assertNull(ProCacheRecord.fromJson(""))
        assertNull(ProCacheRecord.fromJson("pas du json"))
        assertNull(ProCacheRecord.fromJson("""{"uid":"u","store":{"until":"x"}}"""))
    }

    @Test fun anciennesClesRemplaceesParProCache() {
        assertEquals("pro.cache", ProCacheRecord.KEY)
        assertEquals(listOf("pro.entitled", "pro.grant"), ProCacheRecord.LEGACY_KEYS)
        // Le code ne les lit plus nulle part : seulement pour les effacer.
        val dir = listOf(File("src/main/java/com/minou/mvrviewer"), File("app/src/main/java/com/minou/mvrviewer")).first { it.isDirectory }
        val readers = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("\"pro.entitled\"") || it.contains("\"pro.grant\"") } }
            .map { it.name }.toList()
        assertEquals(listOf("ProState.kt"), readers)
    }

    // MARK: - Branchement dans ProAccess

    @Before fun neutral() {
        ProAccess.forceForTests(null)
        ProAccess.resetForTests()
    }

    @After fun reset() {
        ProAccess.resetForTests()
        ProAccess.forceForTests(null)
    }

    private val day = 86_400_000L

    @Test fun licenceDateeDonneProJusquaSonEcheanceEtSeMetEnCache() {
        val now = System.currentTimeMillis()
        val expires = now + 200 * day
        ProAccess.applyAccountLicence(ProGrant(pro = true, expires = expires, fromServer = true), "u1")
        assertTrue(ProAccess.isPro)
        assertEquals(ProState.Reason.LICENCE, ProAccess.proReason)
        assertEquals(ProCacheRecord("u1", null, ProState.LicencePart(expires, false)), ProAccess.cacheForTests())
        // Licence ÉCHUE relue au serveur : plus Pro, part licence effacée.
        ProAccess.applyAccountLicence(ProGrant(pro = true, expires = now - 3_600_000L, fromServer = true), "u1")
        assertFalse(ProAccess.isPro)
        assertNull(ProAccess.cacheForTests()?.licence)
    }

    @Test fun lectureEnEchecNeChangeRien() {
        val expires = System.currentTimeMillis() + 100 * day
        ProAccess.applyAccountLicence(ProGrant(pro = true, expires = expires, fromServer = true), "u1")
        val before = ProAccess.cacheForTests()
        ProAccess.applyAccountLicence(null, "u1")
        assertTrue(ProAccess.isPro)
        assertEquals(before, ProAccess.cacheForTests())
    }

    @Test fun licenceLueEnCacheFirestoreNEffacePasLaPartLicence() {
        val expires = System.currentTimeMillis() + 100 * day
        ProAccess.applyAccountLicence(ProGrant(pro = true, expires = expires, fromServer = true), "u1")
        ProAccess.onForeground() // sans Play : seuls les drapeaux « en ligne » retombent
        assertFalse(ProAccess.onlineLicence)
        // Lecture du cache hors ligne de Firestore, document absent : pas une lecture serveur.
        ProAccess.applyAccountLicence(ProGrant(pro = false, fromServer = false), "u1")
        assertEquals(expires, ProAccess.cacheForTests()?.licence?.until)
        assertTrue("gardé hors ligne", ProAccess.isPro)
        assertEquals(ProState.Reason.OFFLINE, ProAccess.proReason)
    }

    @Test fun lePartLicenceNAppartientQuASonCompte() {
        val expires = System.currentTimeMillis() + 100 * day
        ProAccess.applyAccountLicence(ProGrant(pro = true, expires = expires, fromServer = true), "u1")
        ProAccess.bindAccount("u2")
        assertNull(ProAccess.licence)
        assertNull(ProAccess.cacheForTests()?.licence)
        assertEquals("u2", ProAccess.cacheForTests()?.uid)
        assertFalse(ProAccess.isPro)
    }

    @Test fun deconnexionOublieLaLicenceEtSaPartDuCache() {
        val expires = System.currentTimeMillis() + 100 * day
        ProAccess.applyAccountLicence(ProGrant(pro = true, expires = expires, fromServer = true), "u1")
        ProAccess.clearAccountLicence()
        assertNull(ProAccess.licence)
        assertNull(ProAccess.licenceUid)
        assertEquals(ProCacheRecord(null, null, null), ProAccess.cacheForTests())
        assertFalse(ProAccess.isPro)
    }

    @Test fun forcageDeTestNeTouchePasAuCache() {
        ProAccess.forceForTests(true)
        ProAccess.applyAccountLicence(ProGrant(pro = true, expires = null, fromServer = true), "u1")
        assertEquals(ProState.Reason.OVERRIDE, ProAccess.proReason)
        assertNull(ProAccess.cacheForTests()?.licence)
    }
}
