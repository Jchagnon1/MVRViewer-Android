package com.minou.mvrviewer.sync

import com.minou.mvrviewer.R
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Partie PURE de l'achat reconnu partout (SPEC-purchase.md §3.3, §1.2, §6.1,
 * §6.3, §6.4) : vecteurs COMMUNS aux trois plateformes (copiés tels quels de la
 * spec, recoupés Node + Python), corps JSON exact, table des issues, décision
 * d'envoi, mémoire `pro.link`, textes FR/EN, et le transport HTTP réel contre
 * un serveur local (JDK `HttpServer`).
 */
class PurchaseLinkTest {

    /** Clés d'un objet (l'API org.json d'Android n'a pas `keySet()`). */
    private fun keysOf(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    // MARK: - Vecteurs communs (§3.3)

    @Test fun obfuscatedAccountIdMatchesSharedVectors() {
        assertEquals("a06c3e2ac1f894a5b8cb038f923e2893af09abee6573386fcd8d27c206e9b0c5",
            PurchaseLink.obfuscatedAccountId("abc123"))
        assertEquals("b9031c0284e5c2e088eadcc90df22d8c1b755b8ea2142d4b6f6eee8f97150a99",
            PurchaseLink.obfuscatedAccountId("kZ3vQ9xYp2TfL0mN8rW1sA7bC4d2"))
        assertEquals("f5d4389f26b0e4f4f3ef6bbdbe9aebc3b9a3f3153a13b1e69462b6e318f8b087",
            PurchaseLink.obfuscatedAccountId("uid-B"))
    }

    @Test fun obfuscatedAccountIdIs64LowercaseHexChars() {
        for (uid in listOf("abc123", "kZ3vQ9xYp2TfL0mN8rW1sA7bC4d2", "uid-B", "", "é-ü-日本")) {
            val id = PurchaseLink.obfuscatedAccountId(uid)
            assertEquals(64, id.length) // la limite de Play
            assertTrue(id, id.matches(Regex("^[0-9a-f]{64}$")))
        }
    }

    @Test fun playPurchaseIdMatchesSharedVector() {
        assertEquals("play_30c78cf501a1aab050ff1947dbf6a12b1de968594b35a677312952c1f1de84c8",
            PurchaseLink.playPurchaseId("opaque-token-1.AO-J1Oz"))
    }

    // MARK: - Preuve (§1.2 forme Android)

    @Test fun proofBodyIsExactlyTheAndroidContract() {
        val p = PurchaseLink.proofFor("opaque-token-1.AO-J1Oz")
        assertEquals("play_30c78cf501a1aab050ff1947dbf6a12b1de968594b35a677312952c1f1de84c8", p.purchaseId)
        val o = JSONObject(p.json)
        assertEquals(setOf("platform", "purchaseToken", "productId", "packageName"), keysOf(o))
        assertEquals("android", o.getString("platform"))
        assertEquals("opaque-token-1.AO-J1Oz", o.getString("purchaseToken"))
        assertEquals("com.minou.mvrviewer.pro", o.getString("productId"))
        assertEquals("com.minou.mvrviewer", o.getString("packageName"))
    }

    @Test fun productIdIsTheSameAsProAccess() {
        assertEquals(com.minou.mvrviewer.ui.ProAccess.PRODUCT_ID, PurchaseLink.PRODUCT_ID)
    }

    @Test fun onlyAPurchasedProPurchaseYieldsAProof() {
        val pro = listOf(PurchaseLink.PRODUCT_ID)
        assertNull(PurchaseLink.selectProofToken(emptyList()))
        // EN ATTENTE → jamais de preuve.
        assertNull(PurchaseLink.selectProofToken(listOf(PlayPurchaseSnapshot(pro, purchased = false, purchaseToken = "t-pending"))))
        // Autre produit → pas de preuve.
        assertNull(PurchaseLink.selectProofToken(listOf(PlayPurchaseSnapshot(listOf("other"), true, "t-other"))))
        // Jeton vide → pas de preuve.
        assertNull(PurchaseLink.selectProofToken(listOf(PlayPurchaseSnapshot(pro, true, ""))))
        assertEquals("t-ok", PurchaseLink.selectProofToken(listOf(
            PlayPurchaseSnapshot(pro, false, "t-pending"),
            PlayPurchaseSnapshot(listOf("other"), true, "t-other"),
            PlayPurchaseSnapshot(pro, true, "t-ok")
        )))
    }

    // MARK: - Issues (§1.2 / §6.1)

    private fun err(code: String) = """{"ok":false,"code":"$code","retryable":false,"error":"…"}"""

    @Test fun successIsLinked() {
        assertEquals(PurchaseLinkOutcome.Linked, PurchaseLink.outcome(200,
            """{"ok":true,"status":"granted","pro":true,"store":"play","test":false}"""))
        assertEquals(PurchaseLinkOutcome.Linked, PurchaseLink.outcome(200,
            """{"ok":true,"status":"already","pro":true,"store":"play","test":true}"""))
    }

    @Test fun definitiveRefusalsCarryTheirCode() {
        assertEquals(PurchaseLinkOutcome.Refused("bad_request"), PurchaseLink.outcome(400, err("bad_request")))
        assertEquals(PurchaseLinkOutcome.Refused("test_purchase_refused"), PurchaseLink.outcome(403, err("test_purchase_refused")))
        assertEquals(PurchaseLinkOutcome.Refused("owned_by_other_account"), PurchaseLink.outcome(409, err("owned_by_other_account")))
        assertEquals(PurchaseLinkOutcome.Refused("revoked"), PurchaseLink.outcome(410, err("revoked")))
        assertEquals(PurchaseLinkOutcome.Refused("invalid_purchase"), PurchaseLink.outcome(422, err("invalid_purchase")))
    }

    @Test fun retryableResponsesAreRetry() {
        val retry = PurchaseLinkOutcome.Retry
        assertEquals(retry, PurchaseLink.outcome(202,
            """{"ok":false,"code":"pending","retryable":true,"error":"…"}"""))
        assertEquals(retry, PurchaseLink.outcome(500, """{"ok":false,"code":"internal","retryable":true}"""))
        assertEquals(retry, PurchaseLink.outcome(503, """{"ok":false,"code":"not_configured","retryable":true}"""))
        assertEquals(retry, PurchaseLink.outcome(503, """{"ok":false,"code":"store_unavailable","retryable":true}"""))
        // 401 persistant (le nouvel essai est fait par l'appelant) → retry.
        assertEquals(retry, PurchaseLink.outcome(401, err("sign_in_required")))
        // Corps illisible en 5xx → retry.
        assertEquals(retry, PurchaseLink.outcome(502, "<html>Bad Gateway</html>"))
        assertEquals(retry, PurchaseLink.outcome(500, ""))
        assertEquals(retry, PurchaseLink.outcome(503, null))
        // Route absente (fonction pas encore déployée) → retry, jamais un verdict.
        assertEquals(retry, PurchaseLink.outcome(404, "<html>Not Found</html>"))
    }

    @Test fun aBodyThatIsNotTheFunctionsVerdictIsNeverTakenAsOne() {
        val retry = PurchaseLinkOutcome.Retry
        // 200 sans {"ok":true} (page HTML d'un intermédiaire) : PAS « rattaché ».
        assertEquals(retry, PurchaseLink.outcome(200, "<!doctype html><html></html>"))
        assertEquals(retry, PurchaseLink.outcome(200, """{"ok":false}"""))
        assertEquals(retry, PurchaseLink.outcome(200, ""))
        // 403 d'infrastructure (HTML), 409 sans code : PAS « refusé ».
        assertEquals(retry, PurchaseLink.outcome(403, "<html>Forbidden</html>"))
        assertEquals(retry, PurchaseLink.outcome(409, """{"ok":false}"""))
        assertEquals(retry, PurchaseLink.outcome(422, """{"code":"invalid_purchase"}"""))
    }

    // MARK: - Décision d'envoi (§6.1)

    private fun rec(outcome: String, purchaseId: String = "play_a", uid: String = "u1") =
        PurchaseLinkRecord(purchaseId, uid, outcome, null, 1L)

    @Test fun shouldSendTable() {
        val L = PurchaseLinkRecord.LINKED
        val R = PurchaseLinkRecord.REFUSED
        val T = PurchaseLinkRecord.RETRY
        // Pas de preuve / non connecté → jamais.
        assertFalse(PurchaseLink.shouldSend(null, "u1", false, null))
        assertFalse(PurchaseLink.shouldSend("play_a", null, false, null))
        assertFalse(PurchaseLink.shouldSend(null, "u1", true, null))
        assertFalse(PurchaseLink.shouldSend("play_a", null, true, null))
        // Rien de mémorisé → oui.
        assertTrue(PurchaseLink.shouldSend("play_a", "u1", false, null))
        // Même achat + même compte réglé (linked/refused) → automatique SAUTÉ.
        assertFalse(PurchaseLink.shouldSend("play_a", "u1", false, rec(L)))
        assertFalse(PurchaseLink.shouldSend("play_a", "u1", false, rec(R)))
        // … mais un geste explicite envoie toujours.
        assertTrue(PurchaseLink.shouldSend("play_a", "u1", true, rec(L)))
        assertTrue(PurchaseLink.shouldSend("play_a", "u1", true, rec(R)))
        // retry → renvoyé.
        assertTrue(PurchaseLink.shouldSend("play_a", "u1", false, rec(T)))
        // Autre compte, autre achat → oui.
        assertTrue(PurchaseLink.shouldSend("play_a", "u2", false, rec(L)))
        assertTrue(PurchaseLink.shouldSend("play_b", "u1", false, rec(R)))
    }

    // MARK: - Mémoire `pro.link`

    @Test fun recordRoundTripsThroughJson() {
        val a = PurchaseLinkRecord("play_x", "uid-B", PurchaseLinkRecord.REFUSED, "owned_by_other_account", 1_759_400_000_000L)
        assertEquals(a, PurchaseLinkRecord.fromJson(a.toJson()))
        val b = PurchaseLinkRecord("play_y", "abc123", PurchaseLinkRecord.LINKED, null, 42L)
        assertEquals(b, PurchaseLinkRecord.fromJson(b.toJson()))
        val o = JSONObject(a.toJson())
        assertEquals(setOf("purchaseId", "uid", "outcome", "code", "at"), keysOf(o))
    }

    @Test fun recordFromOutcome() {
        assertEquals(PurchaseLinkRecord("p", "u", "linked", null, 5L),
            PurchaseLinkRecord.of("p", "u", PurchaseLinkOutcome.Linked, 5L))
        assertEquals(PurchaseLinkRecord("p", "u", "refused", "revoked", 5L),
            PurchaseLinkRecord.of("p", "u", PurchaseLinkOutcome.Refused("revoked"), 5L))
        assertEquals(PurchaseLinkRecord("p", "u", "retry", null, 5L),
            PurchaseLinkRecord.of("p", "u", PurchaseLinkOutcome.Retry, 5L))
    }

    @Test fun unreadableRecordIsIgnored() {
        assertNull(PurchaseLinkRecord.fromJson(null))
        assertNull(PurchaseLinkRecord.fromJson(""))
        assertNull(PurchaseLinkRecord.fromJson("not json"))
        assertNull(PurchaseLinkRecord.fromJson("""{"purchaseId":"p","uid":"u"}"""))
        assertNull(PurchaseLinkRecord.fromJson("""{"purchaseId":"p","uid":"u","outcome":"weird","at":1}"""))
    }

    // MARK: - Textes (§6.4)

    @Test fun refusalCodesMapToTheirMessages() {
        assertEquals(R.string.pro_link_owned_by_other_account, PurchaseLink.refusalMessageRes("owned_by_other_account"))
        assertEquals(R.string.pro_link_revoked, PurchaseLink.refusalMessageRes("revoked"))
        assertEquals(R.string.pro_link_invalid_purchase, PurchaseLink.refusalMessageRes("invalid_purchase"))
        assertEquals(R.string.pro_link_invalid_purchase, PurchaseLink.refusalMessageRes("bad_request"))
        assertEquals(R.string.pro_link_test_purchase_refused, PurchaseLink.refusalMessageRes("test_purchase_refused"))
        // Code inconnu → message générique (jamais un trou).
        assertEquals(R.string.pro_link_invalid_purchase, PurchaseLink.refusalMessageRes("something_new"))
    }

    /** Lit `res/<dir>/strings.xml` du module (répertoire de travail = app/). */
    private fun strings(dir: String): Map<String, String> {
        val f = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml"))
            .firstOrNull { it.exists() } ?: error("strings.xml introuvable pour $dir")
        val re = Regex("""<string name="(pro_link_[a-z_]+)">(.*?)</string>""")
        return re.findAll(f.readText()).associate { m ->
            m.groupValues[1] to m.groupValues[2].replace("\\'", "'")
        }
    }

    @Test fun messagesAreExactlyTheSpecTextsInFrenchAndEnglish() {
        assertEquals(mapOf(
            "pro_link_owned_by_other_account" to "Cet achat est déjà rattaché à un autre compte MVR Viewer. Connectez-vous avec ce compte pour retrouver la version Pro sur vos autres appareils.",
            "pro_link_revoked" to "Cet achat a été remboursé ou annulé : il ne donne plus accès à la version Pro.",
            "pro_link_invalid_purchase" to "Cet achat n'a pas pu être vérifié.",
            "pro_link_test_purchase_refused" to "Les achats de test ne sont pas acceptés."
        ), strings("values"))
        assertEquals(mapOf(
            "pro_link_owned_by_other_account" to "This purchase is already linked to another MVR Viewer account. Sign in with that account to get Pro on your other devices.",
            "pro_link_revoked" to "This purchase was refunded or cancelled and no longer unlocks Pro.",
            "pro_link_invalid_purchase" to "This purchase could not be verified.",
            "pro_link_test_purchase_refused" to "Test purchases are not accepted."
        ), strings("values-en"))
    }

    @Test fun noMessageMentionsAnotherPurchaseChannel() {
        // Politique Play / Apple 3.1.1 : aucune autre voie d'achat citée.
        val banned = listOf("iphone", "ios", "app store", "apple", "web", "site", "navigateur", "browser")
        for (dir in listOf("values", "values-en")) for ((name, text) in strings(dir)) {
            for (w in banned) assertFalse("$dir/$name cite « $w »", text.lowercase().contains(w))
        }
    }

    // MARK: - Transport HTTP réel (HttpURLConnection) contre un serveur local

    private class Captured {
        @Volatile var method: String? = null
        @Volatile var path: String? = null
        @Volatile var auth: String? = null
        @Volatile var contentType: String? = null
        @Volatile var body: String? = null
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /**
     * Mini serveur HTTP/1.1 d'UNE requête sur 127.0.0.1 (le `HttpServer` du JDK
     * n'est pas sur le classpath de compilation des tests Android) : capture
     * méthode, chemin, en-têtes et corps, répond [status] + [reply].
     */
    private fun <T> withServer(status: Int, reply: String, block: (endpoint: String, Captured) -> T): T {
        val captured = Captured()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                server.accept().use { s ->
                    val input = BufferedInputStream(s.getInputStream())
                    val head = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }.toList()
                    val requestLine = head.first().split(' ')
                    captured.method = requestLine[0]
                    captured.path = requestLine[1]
                    val headers = head.drop(1).associate {
                        it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
                    }
                    captured.auth = headers["authorization"]
                    captured.contentType = headers["content-type"]
                    val len = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(len)
                    var off = 0
                    while (off < len) {
                        val n = input.read(body, off, len - off)
                        if (n < 0) break
                        off += n
                    }
                    captured.body = String(body, 0, off, Charsets.UTF_8)
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    out.write(("HTTP/1.1 $status Status\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    out.write(bytes)
                    out.flush()
                }
            }
        }
        thread.start()
        try {
            return block("http://127.0.0.1:${server.localPort}/api/purchase/verify", captured)
        } finally {
            server.close()
            thread.join(5_000)
        }
    }

    @Test fun transportPostsTheProofWithTheBearerToken() = runBlocking {
        val proof = PurchaseLink.proofFor("opaque-token-1.AO-J1Oz")
        val ok = """{"ok":true,"status":"granted","pro":true,"store":"play","test":false}"""
        withServer(200, ok) { endpoint, c ->
            val (status, body) = runBlocking { HttpPurchaseLinkTransport(endpoint).post(proof.json, "id-token-123") }
            assertEquals(200, status)
            assertEquals(ok, body)
            assertEquals("POST", c.method)
            assertEquals("/api/purchase/verify", c.path)
            assertEquals("Bearer id-token-123", c.auth)
            assertTrue(c.contentType!!.startsWith("application/json"))
            assertEquals(proof.json, c.body)
            assertEquals(PurchaseLinkOutcome.Linked, PurchaseLink.outcome(status, body))
        }
    }

    @Test fun transportReadsTheErrorBodyOfARefusal() = runBlocking {
        val refusal = """{"ok":false,"code":"owned_by_other_account","retryable":false,"error":"Cet achat est déjà rattaché…"}"""
        withServer(409, refusal) { endpoint, _ ->
            val (status, body) = runBlocking { HttpPurchaseLinkTransport(endpoint).post("{}", "t") }
            assertEquals(409, status)
            assertEquals(refusal, body)
            assertEquals(PurchaseLinkOutcome.Refused("owned_by_other_account"), PurchaseLink.outcome(status, body))
        }
    }

    @Test fun transportThrowsWhenTheServerIsUnreachable() = runBlocking {
        // Port libre puis refermé : connexion refusée → exception (→ retry plus haut).
        val port = ServerSocket(0).use { it.localPort }
        try {
            HttpPurchaseLinkTransport("http://127.0.0.1:$port/api/purchase/verify").post("{}", "t")
            fail("une connexion refusée doit lever")
        } catch (e: IOException) {
            // attendu
        }
    }
}
