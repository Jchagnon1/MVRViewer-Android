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

/** Un mode DMX annoncé par la liste GDTF Share (`modes[]`, SPEC-ma-gdtfshare.md §1.2). */
data class GdtfShareMode(val name: String, val footprint: Int)

/**
 * Une révision de fixture sur GDTF Share, NORMALISÉE (SPEC-ma-gdtfshare.md §1.2). Les champs
 * après `rating` ne servent qu'à la recherche de la bibliothèque MA (chercher, présenter) :
 * les appelants historiques (résolution automatique, « Remplacer le modèle ») les ignorent.
 * `creator` (identifiant d'une personne) n'est jamais lu.
 */
data class GdtfShareEntry(
    val rid: Int,
    val fixture: String,
    val manufacturer: String,
    val uuid: String,
    val rating: Double?,
    /** Nom de la révision, trimé (`""` si absent). */
    val revision: String = "",
    /** Secondes UNIX (millisecondes ramenées en secondes), ou null. */
    val creationDate: Long? = null,
    val lastModified: Long? = null,
    /** `"manufacturer"` | `"user"` | `""`. */
    val uploader: String = "",
    /** Version GDTF (`"1.1"`), `""` si inconnue. */
    val version: String = "",
    /** Octets, ou null. */
    val filesize: Long? = null,
    val modes: List<GdtfShareMode> = emptyList()
) {
    /** Date d'une entrée (§3.2) : `lastModified`, sinon `creationDate`, sinon 0. */
    val date: Long get() = lastModified ?: creationDate ?: 0L
}

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

    /**
     * État de connexion OBSERVABLE (Compose : `collectAsState`) : une connexion faite depuis
     * l'écran GDTF Share (ou une reconnexion silencieuse) relance les téléchargements de 3D
     * des types MA qui attendaient un compte.
     */
    private val _loggedInFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val loggedInFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _loggedInFlow
    val loggedIn: Boolean get() = _loggedInFlow.value
    private var cachedList: List<GdtfShareEntry>? = null

    /**
     * Panne remontée à l'écran. Elle ne porte PAS de texte mais une CLÉ de
     * ressource + ses arguments : le client n'a pas de `Context` et n'a donc pas
     * à choisir une langue — c'est [gdtfShareMessage], à l'affichage, qui met en
     * mots dans la langue du téléphone.
     */
    open class ShareException(
        @androidx.annotation.StringRes val messageRes: Int,
        vararg val args: Any
    ) : Exception()

    /**
     * Session absente ou EXPIRÉE (401/403 sur `getList`/`downloadFile`, ou pas encore de
     * connexion) : `loggedIn` repasse à faux. La recherche de la bibliothèque MA tente alors
     * UNE reconnexion silencieuse puis UNE nouvelle tentative (SPEC-ma-gdtfshare.md §1.4).
     */
    class SessionExpiredException : ShareException(com.minou.mvrviewer.R.string.gdtf_err_not_connected)

    /**
     * CROCHET DE TEST (application débogable seulement, extra `MVR_GDTFSHARE_MOCK_DIR`) :
     * le client sert `getList.json` et `<rid>.gdtf` de ce dossier (un `<rid>.fail` simule
     * une panne réseau) et se dit connecté. Jamais actif dans une version publiée.
     */
    @Volatile var mockDir: java.io.File? = null
        private set

    fun enableMock(dir: java.io.File) {
        mockDir = dir
        cachedList = null
        _loggedInFlow.value = true
    }

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
        if (mockDir != null) { _loggedInFlow.value = true; return@withContext }
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
            cachedList = null
            _loggedInFlow.value = true
        }
    }

    fun logout() {
        _loggedInFlow.value = false
        cachedList = null
        (CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
    }

    /** Issue d'une reconnexion silencieuse. */
    enum class LoginOutcome {
        OK,
        /** Aucun identifiant enregistré sur l'appareil. */
        NO_CREDENTIALS,
        /** Identifiants refusés par le serveur (changés depuis). */
        REJECTED,
        /** Hors ligne / serveur injoignable ou en panne : réessayer en ligne. */
        NETWORK
    }

    /**
     * Classe l'échec d'un [login] : identifiants refusés (401/403, ou message d'erreur du
     * serveur) → [LoginOutcome.REJECTED] ; tout le reste (hors ligne, délai, TLS, 5xx…) →
     * [LoginOutcome.NETWORK]. Une ANNULATION n'est jamais un échec : elle est relancée.
     */
    fun classifyLoginFailure(e: Throwable): LoginOutcome {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val se = e as? ShareException ?: return LoginOutcome.NETWORK
        return when (se.messageRes) {
            com.minou.mvrviewer.R.string.gdtf_err_credentials_fmt -> LoginOutcome.REJECTED
            com.minou.mvrviewer.R.string.gdtf_err_credentials_code_fmt ->
                if ((se.args.firstOrNull() as? Int) in setOf(401, 403)) LoginOutcome.REJECTED else LoginOutcome.NETWORK
            else -> LoginOutcome.NETWORK
        }
    }

    /**
     * Reconnexion SILENCIEUSE depuis les identifiants enregistrés (le cookie de session
     * n'est pas persisté — comme iOS, on refait un login au besoin pour obtenir un cookie
     * frais). Distingue « pas de compte » / « identifiants refusés » d'un échec RÉSEAU ;
     * relance l'annulation (un effet relancé pendant l'appel ne doit pas passer pour un
     * échec de connexion).
     */
    suspend fun ensureLoggedInOutcome(ctx: android.content.Context): LoginOutcome {
        if (mockDir != null) { _loggedInFlow.value = true; return LoginOutcome.OK }
        if (loggedIn) return LoginOutcome.OK
        val creds = GdtfCredentialStore.load(ctx) ?: return LoginOutcome.NO_CREDENTIALS
        return try {
            login(creds.first, creds.second)
            LoginOutcome.OK
        } catch (e: Exception) {
            classifyLoginFailure(e)
        }
    }

    /** Comme [ensureLoggedInOutcome] : true si (déjà) connecté. Échec silencieux → formulaire. */
    suspend fun ensureLoggedIn(ctx: android.content.Context): Boolean =
        ensureLoggedInOutcome(ctx) == LoginOutcome.OK

    suspend fun fixtureList(): List<GdtfShareEntry> = withContext(Dispatchers.IO) {
        cachedList?.let { return@withContext it }
        mockDir?.let { dir ->
            val entries = parseList(runCatching { java.io.File(dir, "getList.json").readText() }.getOrDefault(""))
            cachedList = entries
            return@withContext entries
        }
        if (!loggedIn) throw SessionExpiredException()
        netCall {
            val conn = open("getList.php", "GET")
            val code = conn.responseCode
            val body = readBody(conn)
            conn.disconnect()
            if (code == 401 || code == 403) { sessionExpired(); throw SessionExpiredException() }
            if (code !in 200..299) throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_list_failed_fmt, code)
            val entries = parseList(body)
            cachedList = entries
            entries
        }
    }

    /** Session refusée par le serveur : on n'est plus connecté (le cookie ne vaut plus rien). */
    private fun sessionExpired() {
        _loggedInFlow.value = false
        cachedList = null
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
        mockDir?.let { dir ->
            if (java.io.File(dir, "$rid.fail").exists()) throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_unreachable)
            val f = java.io.File(dir, "$rid.gdtf")
            if (!f.isFile) throw ShareException(com.minou.mvrviewer.R.string.gdtf_err_download_failed_fmt, rid, 404)
            return@withContext f.readBytes()
        }
        if (!loggedIn) throw SessionExpiredException()
        netCall {
            val conn = open("downloadFile.php?rid=$rid", "GET")
            val code = conn.responseCode
            if (code == 401 || code == 403) { conn.disconnect(); sessionExpired(); throw SessionExpiredException() }
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

    /**
     * Liste `getList.php` NORMALISÉE, règle EXACTE commune aux trois clients
     * (SPEC-ma-gdtfshare.md §1.2). PURE (testable en JVM) : enveloppe = tableau à la racine
     * ou objet portant `list` ; tout le reste (corps non JSON, HTML…) → liste vide.
     */
    internal fun parseList(body: String): List<GdtfShareEntry> = GdtfShareList.parse(body)
}

/**
 * Message AFFICHABLE d'une panne GDTF Share, dans la langue du téléphone
 * (null si l'erreur ne vient pas du client Share — l'appelant garde son repli).
 */
fun Throwable.gdtfShareMessage(ctx: android.content.Context): String? =
    (this as? GdtfShareClient.ShareException)?.let { ctx.getString(it.messageRes, *it.args) }

/**
 * Normalisation de la liste GDTF Share (SPEC-ma-gdtfshare.md §1.2). L'API annonce des types
 * (Integer, Long, Float) mais sa doc montre des chaînes ; `modes` a trois formes plausibles :
 * tout est accepté, rien n'est deviné au-delà de la règle.
 */
internal object GdtfShareList {

    fun parse(body: String): List<GdtfShareEntry> {
        val arr: JSONArray = try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("list") ?: return emptyList()
        } catch (e: Exception) { return emptyList() }
        val out = ArrayList<GdtfShareEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.opt(i) as? JSONObject ?: continue
            entry(o)?.let { out.add(it) }
        }
        return out
    }

    /** Au moins UNE entrée annonce au moins un mode : sinon le filtre d'empreinte est désactivé. */
    fun providesFootprints(entries: List<GdtfShareEntry>): Boolean = entries.any { it.modes.isNotEmpty() }

    private fun entry(o: JSONObject): GdtfShareEntry? {
        val rid = integral(o.opt("rid"))?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
        val uuid = (o.opt("uuid") as? String)?.takeIf { it.trim().isNotEmpty() } ?: return null
        return GdtfShareEntry(
            rid = rid,
            fixture = o.opt("fixture") as? String ?: "?",
            manufacturer = o.opt("manufacturer") as? String ?: "?",
            uuid = uuid,
            rating = finite(o.opt("rating")),
            revision = (o.opt("revision") as? String)?.trim() ?: "",
            creationDate = date(o.opt("creationDate")),
            lastModified = date(o.opt("lastModified")),
            uploader = uploader(o.opt("uploader")),
            version = version(o.opt("version")),
            filesize = integral(o.opt("filesize"))?.takeIf { it >= 0 },
            modes = modes(o.opt("modes"))
        )
    }

    /** Entier, flottant entier (`12.0`) ou chaîne de chiffres ; sinon null. */
    fun integral(v: Any?): Long? = when (v) {
        is Int, is Long, is Short, is Byte -> (v as Number).toLong()
        is java.math.BigInteger -> if (v.bitLength() < 63) v.toLong() else null
        is java.math.BigDecimal -> try {
            v.toBigIntegerExact().let { if (it.bitLength() < 63) it.toLong() else null }
        } catch (_: ArithmeticException) { null }
        is Double, is Float -> {
            val d = (v as Number).toDouble()
            if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 9.0e15) d.toLong() else null
        }
        is String -> if (v.isNotEmpty() && v.all { it in '0'..'9' }) v.toLongOrNull() else null
        else -> null
    }

    /** Nombre fini, ou chaîne qui se lit comme un nombre fini ; sinon null. */
    private fun finite(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    /** Secondes > 0 ; une valeur > 100 000 000 000 est en MILLISECONDES (division entière). */
    private fun date(v: Any?): Long? {
        val n = integral(v)?.takeIf { it > 0 } ?: return null
        return if (n > 100_000_000_000L) n / 1000 else n
    }

    private fun uploader(v: Any?): String {
        val s = (v as? String)?.trim()?.lowercase(java.util.Locale.ROOT) ?: return ""
        return when {
            s.startsWith("manuf") -> "manufacturer"
            s == "user" -> "user"
            else -> ""
        }
    }

    /** Chaîne trimée ; nombre entier → `"<n>.0"` (1 et 1.0 confondus, comme en JavaScript). */
    private fun version(v: Any?): String = when (v) {
        is String -> v.trim()
        is Number -> {
            val d = v.toDouble()
            when {
                !d.isFinite() -> ""
                d == Math.floor(d) && Math.abs(d) < 9.0e15 -> "${d.toLong()}.0"
                else -> d.toString()
            }
        }
        else -> ""
    }

    // ---- modes ----

    private fun isModeObject(v: Any?): Boolean = v is JSONObject && (v.has("name") || v.has("dmxfootprint"))

    /** Clés entières d'abord (croissantes NUMÉRIQUEMENT : « 10 » après « 4 »), puis les autres (ordre lexical). */
    fun sortedKeys(o: JSONObject): List<String> {
        val keys = o.keys().asSequence().toList()
        val (num, other) = keys.partition { k -> k.isNotEmpty() && k.all { it in '0'..'9' } }
        return num.sortedWith(compareBy<String>({ java.math.BigInteger(it) }, { it })) + other.sorted()
    }

    /** Un élément : objet-mode → retenu ; autre objet → ses VALEURS objets-mode ; tableau → ses éléments objets-mode. */
    private fun collect(el: Any?, out: MutableList<JSONObject>) {
        when {
            isModeObject(el) -> out.add(el as JSONObject)
            el is JSONObject -> for (k in sortedKeys(el)) { val v = el.opt(k); if (isModeObject(v)) out.add(v as JSONObject) }
            el is JSONArray -> for (i in 0 until el.length()) { val v = el.opt(i); if (isModeObject(v)) out.add(v as JSONObject) }
        }
    }

    fun modes(v: Any?): List<GdtfShareMode> {
        val objs = ArrayList<JSONObject>()
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i), objs)
            is JSONObject -> for (k in sortedKeys(v)) collect(v.opt(k), objs)
            else -> {}
        }
        return objs.mapNotNull { m ->
            val fp = integral(m.opt("dmxfootprint"))?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return@mapNotNull null
            GdtfShareMode((m.opt("name") as? String)?.trim() ?: "", fp.toInt())
        }
    }
}
