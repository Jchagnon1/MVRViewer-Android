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

    /**
     * Panne remontée à l'écran. Elle ne porte PAS de texte mais une CLÉ de
     * ressource + ses arguments : le client n'a pas de `Context` et n'a donc pas
     * à choisir une langue — c'est [gdtfShareMessage], à l'affichage, qui met en
     * mots dans la langue du téléphone.
     */
    class ShareException(
        @androidx.annotation.StringRes val messageRes: Int,
        vararg val args: Any
    ) : Exception()

    /** Traduit les pannes réseau en message clair (au lieu de « Unable to resolve host… »). */
    private inline fun <T> netCall(block: () -> T): T = try {
        block()
    } catch (e: java.net.UnknownHostException) {
        throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_unreachable)
    } catch (e: java.net.ConnectException) {
        throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_connect)
    } catch (e: java.net.SocketTimeoutException) {
        throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_timeout)
    } catch (e: javax.net.ssl.SSLException) {
        throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_tls)
    }

    suspend fun login(user: String, password: String) = withContext(Dispatchers.IO) {
        netCall {
            val conn = open("login.php", "POST")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("user", user).put("password", password).toString().toByteArray()) }
            val code = conn.responseCode
            val body = readBody(conn)
            conn.disconnect()
            if (code !in 200..299) {
                // L'API renvoie 401 + {"result":false,"error":"…"} sur mauvais identifiants.
                // Le texte du serveur (non traduisible) est cité tel quel ; sinon on
                // ne dispose que du code HTTP, et la phrase entière est localisée.
                val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                throw if (msg != null)
                    ShareException(com.minou.mvrviewer.R.string.gdtf_err_credentials_fmt, msg)
                else ShareException(com.minou.mvrviewer.R.string.gdtf_err_credentials_code_fmt, code)
            }
            loggedIn = true
            cachedList = null
        }
    }

    fun logout() {
        loggedIn = false
        cachedList = null
        (CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
    }

    /**
     * Reconnexion SILENCIEUSE depuis les identifiants enregistrés (le cookie de
     * session n'est pas persisté — comme iOS, on refait un login au besoin pour
     * obtenir un cookie frais). Renvoie true si (déjà) connecté. Échec silencieux
     * (hors ligne, identifiants changés) → l'utilisateur voit le formulaire.
     */
    suspend fun ensureLoggedIn(ctx: android.content.Context): Boolean {
        if (loggedIn) return true
        val creds = GdtfCredentialStore.load(ctx) ?: return false
        return runCatching { login(creds.first, creds.second) }.isSuccess
    }

    suspend fun fixtureList(): List<GdtfShareEntry> = withContext(Dispatchers.IO) {
        cachedList?.let { return@withContext it }
        if (!loggedIn) throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_not_connected)
        netCall {
            val conn = open("getList.php", "GET")
            val code = conn.responseCode
            val body = readBody(conn)
            conn.disconnect()
            if (code !in 200..299) throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_list_failed_fmt, code)
            val entries = parseList(body)
            cachedList = entries
            entries
        }
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
        if (!loggedIn) throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_not_connected)
        netCall {
            val conn = open("downloadFile.php?rid=$rid", "GET")
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_download_failed_fmt, rid, code) }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            bytes
        }
    }

    /**
     * Meilleure révision correspondant à une identité GDTF, téléchargée — en ne
     * retenant qu'une révision qui embarque un VRAI modèle 3D. Beaucoup de
     * révisions GDTF Share n'ont que la photométrie : les appliquer ferait
     * PERDRE la silhouette du .gdtf embarqué. On essaie donc les meilleures
     * candidates (par FixtureTypeID d'abord — le plus fiable — puis
     * fabricant+nom, tolérant aux suffixes Spot/Wash/Beam…), et on retourne la
     * première avec un modèle 3D ; sinon null → l'embarqué est conservé.
     */
    suspend fun downloadBest(identity: GdtfIdentity): ByteArray? {
        val entries = fixtureList()
        val byId = identity.fixtureTypeId?.let { uuid ->
            entries.filter { it.uuid.equals(uuid, true) }
        } ?: emptyList()
        val manu = identity.manufacturer?.lowercase(); val name = identity.name?.lowercase()
        val byName = if (manu != null && name != null) entries.filter { e ->
            e.manufacturer.lowercase() == manu &&
                (e.fixture.lowercase() == name || e.fixture.lowercase().startsWith(name) || name.startsWith(e.fixture.lowercase()))
        } else emptyList()
        val candidates = (byId.sortedByDescending { it.rating ?: 0.0 } +
            byName.sortedByDescending { it.rating ?: 0.0 }).distinctBy { it.rid }
        for (e in candidates.take(4)) {
            val data = runCatching { download(e.rid) }.getOrNull() ?: continue
            if (GdtfLoader.hasThreeDModel(data)) return data
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

/**
 * Message AFFICHABLE d'une panne GDTF Share, dans la langue du téléphone
 * (null si l'erreur ne vient pas du client Share — l'appelant garde son repli).
 */
fun Throwable.gdtfShareMessage(ctx: android.content.Context): String? =
    (this as? GdtfShareClient.ShareException)?.let { ctx.getString(it.messageRes, *it.args) }
