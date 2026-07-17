package com.minou.mvrviewer.mvr

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

/** Une révision de fixture sur GDTF Share. */
data class GdtfShareEntry(
    val rid: Int,
    val fixture: String,
    val manufacturer: String,
    val uuid: String,
    val rating: Double?
)

/** Identité d'un profil GDTF embarqué (pour le matching Share). */
data class GdtfIdentity(val fixtureTypeId: String?, val manufacturer: String?, val name: String?)

/**
 * Client de l'API publique GDTF Share — portage de GDTFShareClient (iOS).
 * Compte gratuit requis (pas d'accès anonyme). Flux : login (POST) → cookie de
 * session (géré par un CookieManager global) → getList.php → downloadFile.php.
 */
object GdtfShareClient {
    private const val BASE = "https://gdtf-share.com/apis/public/"

    init {
        // Cookies de session partagés par tous les appels (comme URLSession.shared iOS).
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    @Volatile var loggedIn = false; private set
    private var cachedList: List<GdtfShareEntry>? = null

    class ShareException(message: String) : Exception(message)

    suspend fun login(user: String, password: String) = withContext(Dispatchers.IO) {
        val conn = open("login.php", "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(JSONObject().put("user", user).put("password", password).toString().toByteArray()) }
        val code = conn.responseCode
        val body = readBody(conn)
        conn.disconnect()
        if (code !in 200..299) throw ShareException("Échec de connexion (${code}) : ${body.take(200)}")
        loggedIn = true
        cachedList = null
    }

    fun logout() {
        loggedIn = false
        cachedList = null
        (CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
    }

    suspend fun fixtureList(): List<GdtfShareEntry> = withContext(Dispatchers.IO) {
        cachedList?.let { return@withContext it }
        if (!loggedIn) throw ShareException("Non connecté à GDTF Share.")
        val conn = open("getList.php", "GET")
        val code = conn.responseCode
        val body = readBody(conn)
        conn.disconnect()
        if (code !in 200..299) throw ShareException("getList.php a échoué (${code}).")
        val entries = parseList(body)
        cachedList = entries
        entries
    }

    suspend fun search(query: String, limit: Int = 60): List<GdtfShareEntry> {
        val entries = fixtureList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries.take(limit)
        return entries.filter {
            it.fixture.lowercase().contains(q) || it.manufacturer.lowercase().contains(q)
        }.take(limit)
    }

    suspend fun download(rid: Int): ByteArray = withContext(Dispatchers.IO) {
        if (!loggedIn) throw ShareException("Non connecté à GDTF Share.")
        val conn = open("downloadFile.php?rid=$rid", "GET")
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); throw ShareException("Téléchargement échoué (rid $rid, code $code).") }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        bytes
    }

    /** Meilleure révision correspondant à une identité GDTF, téléchargée (ou null). */
    suspend fun downloadBest(identity: GdtfIdentity): ByteArray? {
        val entries = fixtureList()
        // 1. Par FixtureTypeID (le plus fiable).
        identity.fixtureTypeId?.let { uuid ->
            val m = entries.filter { it.uuid.equals(uuid, true) }
            (m.maxByOrNull { it.rating ?: 0.0 } ?: m.firstOrNull())?.let { return download(it.rid) }
        }
        // 2. Repli fabricant + nom (tolérant aux suffixes Spot/Wash/Beam…).
        val manu = identity.manufacturer?.lowercase(); val name = identity.name?.lowercase()
        if (manu != null && name != null) {
            val c = entries.filter { e ->
                e.manufacturer.lowercase() == manu &&
                    (e.fixture.lowercase() == name || e.fixture.lowercase().startsWith(name) || name.startsWith(e.fixture.lowercase()))
            }
            (c.maxByOrNull { it.rating ?: 0.0 } ?: c.firstOrNull())?.let { return download(it.rid) }
        }
        return null
    }

    // ---- Identité d'un .gdtf embarqué (FixtureType) ----

    fun identity(gdtfBytes: ByteArray): GdtfIdentity? {
        val xml = MvrParser.extractEntry(gdtfBytes, "description.xml")
            ?: MvrParser.extractEntry(gdtfBytes, "Description.xml") ?: return null
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "FixtureType") {
                return GdtfIdentity(
                    parser.getAttributeValue(null, "FixtureTypeID"),
                    parser.getAttributeValue(null, "Manufacturer"),
                    parser.getAttributeValue(null, "Name")
                )
            }
            event = parser.next()
        }
        return null
    }

    // ---- Interne ----

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.use { String(it.readBytes()) } ?: ""
    }

    private fun parseList(body: String): List<GdtfShareEntry> {
        val arr: JSONArray = try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("list") ?: return emptyList()
        } catch (e: Exception) { return emptyList() }
        val out = ArrayList<GdtfShareEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uuid = o.optString("uuid").ifEmpty { continue }
            val rid = when {
                o.has("rid") && o.get("rid") is Int -> o.getInt("rid")
                else -> o.optString("rid").toIntOrNull()
            } ?: continue
            val rating = o.optString("rating").toDoubleOrNull() ?: o.optDouble("rating").takeIf { !it.isNaN() }
            out.add(GdtfShareEntry(rid, o.optString("fixture", "?"), o.optString("manufacturer", "?"), uuid, rating))
        }
        return out
    }
}
