package com.minou.mvrviewer.mvr

import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

/**
 * LECTEUR DE PROFILS MA (bibliothèque MA, SPEC-ma.md §1) — grandMA3 `.xml`, grandMA2
 * `.xml` / `.xmlp`, GDTF `.gdtf`.
 *
 * Portage LIGNE À LIGNE de l'oracle `tests/fixtures/ma/ma_reference.py` (il FAIT FOI :
 * si ce code et l'oracle divergent, c'est ce code qui a tort). PUR : aucun appel
 * Android (javax.xml + java.util.zip existent sur l'appareil ET en test JVM, contrairement
 * à `android.util.Xml`) → couvert par `MaProfileReaderTest` sur tous les fichiers d'essai.
 *
 * Points délicats (annexe de la spec) :
 *  - on suit TOUJOURS les offsets, jamais le nom du mode (PXL Curve « 101Ch » = 104) ;
 *  - un canal sans Coarse/coarse est VIRTUEL (ni empreinte, ni ambiguïté de nom) ;
 *  - `.xmlp` : on CHERCHE `1F 8B`, on ne suppose pas un en-tête de 19 octets ;
 *  - XML venu d'internet : DOCTYPE refusé, entités externes jamais résolues.
 */
object MaProfileReader {

    /** Plafond de taille d'un fichier importé (vérifié AVANT lecture par l'appelant). */
    const val MAX_FILE_BYTES: Long = 64L * 1024 * 1024

    /**
     * Garde-fou mémoire (hors oracle) : une adresse absurde (> 128 univers) ne doit pas
     * allouer un tableau de canaux géant sur l'appareil. Aucun profil réel n'en approche.
     */
    private const val MAX_ABSOLUTE = 65_536

    /** Types de géométrie GDTF 1.2 (GeometryReference exclue : c'est une INSTANCE). */
    private val GEOMETRY_TAGS = setOf(
        "Geometry", "Axis", "FilterBeam", "FilterColor", "FilterGobo", "FilterShaper", "Beam",
        "MediaServerLayer", "MediaServerCamera", "MediaServerMaster", "Display", "Laser",
        "WiringObject", "Inventory", "Structure", "Support", "Magnet"
    )

    private val SUFFIX = listOf("", " fine", " ultra", " uber")

    // ---- Résultat --------------------------------------------------------------------

    data class Mode(val name: String, val channels: List<String>, val footprint: Int)

    data class Profile(
        /** "ma3" | "ma2" | "gdtf". */
        val format: String,
        val manufacturer: String,
        val fixture: String,
        val shortName: String,
        val author: String,
        val modes: List<Mode>
    )

    /** Codes d'erreur de l'oracle (§1.1) ; `raw` = valeur JSON exacte. */
    enum class ErrorCode(val raw: String) {
        PROTECTED("protected"), UNREADABLE("unreadable"), UNKNOWN("unknown"), EMPTY("empty")
    }

    sealed class Result {
        data class Ok(val profile: Profile) : Result()
        data class Error(val code: ErrorCode) : Result()
    }

    // ---- Point d'entrée ----------------------------------------------------------------

    /** `readMaProfile(bytes, fileName)` de la spec (§1.10). Ne lève jamais. */
    fun read(bytes: ByteArray, fileName: String): Result = try {
        readInternal(bytes, fileName)
    } catch (_: OutOfMemoryError) {
        Result.Error(ErrorCode.UNREADABLE)
    } catch (_: Exception) {
        Result.Error(ErrorCode.UNREADABLE)
    }

    private fun readInternal(input: ByteArray, fileName: String): Result {
        var data = input
        val low = fileName.lowercase()
        if (low.endsWith(".pxml")) return Result.Error(ErrorCode.PROTECTED)
        if (data.size >= 2 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            data = when (val z = zipDescription(data)) {
                is ZipOut.Found -> z.bytes
                ZipOut.Missing -> return Result.Error(ErrorCode.UNKNOWN)
                ZipOut.Broken -> return Result.Error(ErrorCode.UNREADABLE)
            }
        } else {
            val gz = indexOfGzipMagic(data)
            if (low.endsWith(".xmlp") || (gz in 0..64 && startsWithMaData(data))) {
                if (gz < 0) return Result.Error(ErrorCode.UNREADABLE)
                data = gunzip(data, gz) ?: return Result.Error(ErrorCode.UNREADABLE)
            }
        }
        val root = parseXml(data) ?: return Result.Error(ErrorCode.UNREADABLE)
        val author = authorOf(fileName)
        var out: Result = when (root.name) {
            "GMA3", "GDTF" -> {
                val r = parseMa3(root, author)
                if (root.name == "GDTF" && r is Result.Ok) Result.Ok(r.profile.copy(format = "gdtf", author = "")) else r
            }
            "MA" -> parseMa2(root, author)
            else -> return Result.Error(ErrorCode.UNKNOWN)
        }
        if (out is Result.Ok) {
            val kept = out.profile.modes.filter { it.footprint >= 1 }
            if (kept.isEmpty()) return Result.Error(ErrorCode.EMPTY)
            out = Result.Ok(out.profile.copy(modes = kept))
        }
        return out
    }

    // ---- Auteur (convention MA Share) --------------------------------------------------

    /**
     * DERNIER segment « @ » du nom sans extension s'il y en a au moins 3
     * (« Fabricant@Modèle@Auteur.xml » ou « Fabricant@Modèle@Mode@Auteur.xml »), « _ » → espace.
     */
    fun authorOf(fileName: String): String {
        val last = fileName.substringAfterLast('/')
        val base = if (last.contains('.')) last.substringBeforeLast('.') else last
        val parts = base.split('@')
        return if (parts.size >= 3) parts.last().replace('_', ' ') else ""
    }

    // ---- Mini-arbre XML ----------------------------------------------------------------

    /** Élément : nom LOCAL, attributs (nom qualifié → valeur), enfants, texte avant le 1er enfant. */
    private class XNode(val name: String, val attrs: Map<String, String>, val text: String) {
        val children = ArrayList<XNode>()
        fun get(attr: String): String? = attrs[attr]
        /** Parcours en PROFONDEUR, ordre du document, l'élément lui-même compris (ET `iter()`). */
        fun iter(): Sequence<XNode> = sequence {
            val stack = ArrayDeque<XNode>()
            stack.addLast(this@XNode)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                yield(n)
                for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
            }
        }
        fun kids(name: String): List<XNode> = children.filter { it.name == name }
        fun firstKid(name: String): XNode? = children.firstOrNull { it.name == name }
    }

    private fun localName(qname: String): String = qname.substringAfterLast(':')

    /**
     * Tests seulement : se comporter comme le `DocumentBuilderFactory` d'ANDROID (harmony /
     * KXmlParser), qui REFUSE la fonctionnalité `disallow-doctype-decl` et ACCEPTE donc un
     * DOCTYPE — pour vérifier en JVM que le refus ne dépend pas de l'analyseur.
     */
    @Volatile internal var simulateAndroidXmlFactory = false

    /**
     * Le PROLOGUE (tout ce qui précède l'élément racine) contient-il une déclaration
     * `<!DOCTYPE` ? Balayage indépendant de l'analyseur XML : sur l'appareil, la fabrique
     * DOM d'Android ne sait pas refuser un DOCTYPE (annexe 16 « refuser DOCTYPE ») — sans ce
     * contrôle, un même fichier était refusé en test JVM mais importé sur le téléphone.
     * Encodage : BOM UTF-8/UTF-16, UTF-16 sans BOM (`<?` sur 2 octets), sinon un codage
     * compatible ASCII (UTF-8, Latin-1…) — le balisage du prologue est en ASCII.
     */
    internal fun hasDoctype(bytes: ByteArray): Boolean {
        fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        val (charset, skip) = when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> Charsets.UTF_8 to 3
            b(0) == 0xFE && b(1) == 0xFF -> Charsets.UTF_16BE to 2
            b(0) == 0xFF && b(1) == 0xFE -> Charsets.UTF_16LE to 2
            b(0) == 0x00 && b(1) == 0x3C && b(2) == 0x00 && b(3) == 0x3F -> Charsets.UTF_16BE to 0
            b(0) == 0x3C && b(1) == 0x00 && b(2) == 0x3F && b(3) == 0x00 -> Charsets.UTF_16LE to 0
            else -> Charsets.ISO_8859_1 to 0
        }
        // Le prologue est court : on décode d'abord un préfixe, et tout le fichier
        // seulement si le prologue (commentaires géants…) déborde de ce préfixe.
        var len = minOf(bytes.size - skip, 256 * 1024).coerceAtLeast(0)
        while (true) {
            val text = String(bytes, skip, len, charset)
            val r = scanPrologForDoctype(text, complete = skip + len >= bytes.size)
            if (r != null) return r
            if (skip + len >= bytes.size) return false
            len = bytes.size - skip
        }
    }

    /** true/false si le prologue est tranché dans [s] ; null s'il déborde de [s] (incomplet). */
    private fun scanPrologForDoctype(s: String, complete: Boolean): Boolean? {
        var i = 0
        while (true) {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n' || s[i] == '﻿')) i++
            if (i >= s.length) return if (complete) false else null
            when {
                s.startsWith("<?", i) -> {                       // déclaration XML / instruction
                    val e = s.indexOf("?>", i + 2)
                    if (e < 0) return if (complete) false else null
                    i = e + 2
                }
                s.startsWith("<!--", i) -> {                     // commentaire
                    val e = s.indexOf("-->", i + 4)
                    if (e < 0) return if (complete) false else null
                    i = e + 3
                }
                s.regionMatches(i, "<!DOCTYPE", 0, 9, ignoreCase = true) -> return true
                // Partie de « <!DOCTYPE » coupée par la fin du préfixe : à relire en entier.
                !complete && s.length - i < 9 && "<!DOCTYPE".startsWith(s.substring(i), ignoreCase = true) -> return null
                else -> return false                              // élément racine (ou texte)
            }
        }
    }

    private fun parseXml(bytes: ByteArray): XNode? {
        // DOCTYPE refusé AVANT toute analyse, à l'identique sur l'appareil et en test JVM.
        if (hasDoctype(bytes)) return null
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        // Défense en profondeur : DOCTYPE refusé aussi par l'analyseur quand il le permet
        // (Xerces en test JVM) ; sinon (Android) les entités ne sont pas développées.
        val doctypeOff = !simulateAndroidXmlFactory && runCatching {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.isSuccess
        if (!doctypeOff) f.isExpandEntityReferences = false
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { f.isXIncludeAware = false }
        val doc = runCatching {
            val b = f.newDocumentBuilder()
            // Sans gestionnaire, Xerces imprime « [Fatal Error] … » sur stderr.
            b.setErrorHandler(object : ErrorHandler {
                override fun warning(e: SAXParseException) {}
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
            b.parse(ByteArrayInputStream(bytes))
        }.getOrNull() ?: return null
        val rootEl = doc.documentElement ?: return null
        return convert(rootEl)
    }

    /** DOM → mini-arbre, ITÉRATIF (un XML très imbriqué ne doit pas faire déborder la pile). */
    private fun convert(rootEl: Element): XNode {
        fun make(el: Element): XNode {
            val attrs = HashMap<String, String>()
            val nm = el.attributes
            for (i in 0 until nm.length) {
                val a = nm.item(i)
                attrs[a.nodeName] = a.nodeValue ?: ""
            }
            // ET `.text` : le texte AVANT le premier élément enfant (commentaires ignorés).
            val sb = StringBuilder()
            var c = el.firstChild
            while (c != null && c.nodeType != Node.ELEMENT_NODE) {
                if (c.nodeType == Node.TEXT_NODE || c.nodeType == Node.CDATA_SECTION_NODE) sb.append(c.nodeValue)
                c = c.nextSibling
            }
            return XNode(localName(el.tagName), attrs, sb.toString())
        }
        val root = make(rootEl)
        val stack = ArrayDeque<Pair<Element, XNode>>()
        stack.addLast(rootEl to root)
        while (stack.isNotEmpty()) {
            val (el, xn) = stack.removeLast()
            var c = el.firstChild
            while (c != null) {
                if (c.nodeType == Node.ELEMENT_NODE) {
                    val ce = c as Element
                    val cx = make(ce)
                    xn.children.add(cx)
                    stack.addLast(ce to cx)
                }
                c = c.nextSibling
            }
        }
        return root
    }

    // ---- Entiers à la Python (`int(str(v).strip())`) ---------------------------------

    private val PY_INT = Regex("^[+-]?[0-9]+(_[0-9]+)*$")

    /** `ints()` de l'oracle : entier ou null (bornes Int ; au-delà → null). */
    private fun ints(v: String?): Int? {
        if (v == null) return null
        val s = v.trim()
        if (!PY_INT.matches(s)) return null
        return s.replace("_", "").toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    /** Vérité Python d'un entier optionnel (`if not x` → null ou 0 = faux). */
    private fun truthy(i: Int?): Boolean = i != null && i != 0

    // ---- Octets d'un canal -------------------------------------------------------------

    /** Octets d'un canal dans l'ordre (octet 1 = coarse) ; vide = canal VIRTUEL. */
    private fun byteOffsets(el: XNode, keys: List<String>, offsetAttr: String?): List<Int> {
        if (el.get(keys[0]) == null) {
            val off = offsetAttr?.let { el.get(it) }
            if (offsetAttr != null && keys.all { el.get(it) == null } && !off.isNullOrEmpty()) {
                val parts = off.split(',').filter { it.isNotBlank() }.map { ints(it) }
                if (parts.isEmpty() || !truthy(parts[0])) return emptyList()
                return listOf(parts[0]!!) + parts.drop(1).filter { truthy(it) }.map { it!! }
            }
            return emptyList()
        }
        val first = ints(el.get(keys[0]))
        if (!truthy(first)) return emptyList()
        val rest = keys.drop(1).filter { el.get(it) != null }.map { ints(el.get(it)) }.filter { truthy(it) }.map { it!! }
        return listOf(first!!) + rest
    }

    // ---- Noms + empreinte --------------------------------------------------------------

    /** (absolu, attribut, suffixe, groupe) dans l'ordre du document. */
    private class Entry(val abs: Int, val base: String, val suffix: String, val group: String)

    private fun finish(name: String, entries: List<Entry>): Mode {
        if (entries.isEmpty()) return Mode(name, emptyList(), 0)
        // Ambiguïté comptée par ATTRIBUT sur les canaux LOGIQUES (octet 1) : l'octet fin
        // suit toujours son coarse (préfixés ensemble ou pas du tout).
        val count = HashMap<String, Int>()
        for (e in entries) if (e.suffix.isEmpty()) count[e.base] = (count[e.base] ?: 0) + 1
        val named = entries.map { e ->
            val n = if ((count[e.base] ?: 0) <= 1 || e.group.isEmpty()) e.base else "${e.group} ${e.base}"
            e.abs to n + e.suffix
        }
        val fp = named.maxOf { it.first }
        val size = fp.coerceIn(0, MAX_ABSOLUTE)
        val ch = MutableList(size) { "" }
        val seen = HashSet<Int>()
        for ((a, n) in named) {
            if (a < 1 || a > size || a in seen) continue
            seen.add(a); ch[a - 1] = n
        }
        return Mode(name, ch, if (fp > MAX_ABSOLUTE) MAX_ABSOLUTE else fp)
    }

    // ---- grandMA3 / GDTF ---------------------------------------------------------------

    private fun parseMa3(root: XNode, author: String): Result {
        val ft = root.iter().firstOrNull { it.name == "FixtureType" } ?: return Result.Error(ErrorCode.UNKNOWN)
        val geoms = HashMap<String, XNode>()
        val groot = ft.iter().firstOrNull { it.name == "Geometries" }
        groot?.iter()?.forEach { g ->
            val n = g.get("Name")
            if (g.name in GEOMETRY_TAGS && n != null && n !in geoms) geoms[n] = g
        }
        val modes = ArrayList<Mode>()
        for (m in ft.iter()) {
            if (m.name != "DMXMode") continue
            val rootGeo = m.get("Geometry")?.let { geoms[it] }
            // Références groupées par géométrie CIBLE (clé nullable comme l'oracle ;
            // ordre d'insertion = ordre du document).
            val refs = LinkedHashMap<String?, MutableList<Pair<String, Int>>>()
            val scope = rootGeo ?: ft
            for (r in scope.iter()) {
                if (r.name != "GeometryReference") continue
                val br = r.firstKid("Break")
                val off = if (br != null && br.get("DMXOffset") != null) ints(br.get("DMXOffset")) else 1
                refs.getOrPut(r.get("Geometry")) { ArrayList() }.add((r.get("Name") ?: "") to (if (truthy(off)) off!! else 1))
            }
            // géométrie → cible référencée qui la contient (elle-même comprise).
            val template = HashMap<String?, String?>()
            for (target in refs.keys) {
                if (!template.containsKey(target)) template[target] = target
                val tg = target?.let { geoms[it] }
                tg?.iter()?.forEach { e ->
                    val n = e.get("Name")
                    if (e.name in GEOMETRY_TAGS && n != null && !template.containsKey(n)) template[n] = target
                }
            }
            val entries = ArrayList<Entry>()
            for (ch in m.iter()) {
                if (ch.name != "DMXChannel") continue
                val offs = byteOffsets(ch, listOf("Coarse", "Fine", "Ultra", "Uber"), "Offset")
                if (offs.isEmpty()) continue
                val lc = ch.iter().firstOrNull { it.name == "LogicalChannel" }
                val base = lc?.get("Attribute")?.ifEmpty { null } ?: ch.get("Name")?.ifEmpty { null } ?: ""
                val geo = ch.get("Geometry")?.ifEmpty { null } ?: ""
                if (template.containsKey(geo) && template[geo] != null) {
                    val tpl = template[geo]
                    for ((rname, roff) in refs[tpl].orEmpty()) {
                        offs.forEachIndexed { i, o -> entries.add(Entry(roff + o - 1, base, SUFFIX[minOf(i, 3)], rname)) }
                    }
                } else {
                    offs.forEachIndexed { i, o -> entries.add(Entry(o, base, SUFFIX[minOf(i, 3)], geo)) }
                }
            }
            modes.add(finish(m.get("Name") ?: "", entries))
        }
        return Result.Ok(
            Profile(
                format = "ma3",
                manufacturer = ft.get("Manufacturer") ?: "",
                fixture = ft.get("Name") ?: "",
                shortName = ft.get("ShortName") ?: "",
                author = author,
                modes = modes
            )
        )
    }

    // ---- grandMA2 ----------------------------------------------------------------------

    private fun parseMa2(root: XNode, author: String): Result {
        val ft = root.iter().firstOrNull { it.name == "FixtureType" } ?: return Result.Error(ErrorCode.UNKNOWN)
        fun text(tag: String): String = ft.firstKid(tag)?.text?.trim() ?: ""
        // index = @index (sinon position 0-based) ; doublon : le DERNIER gagne.
        val byIndex = HashMap<Int, XNode>()
        ft.firstKid("Modules")?.kids("Module")?.forEachIndexed { pos, mo ->
            byIndex[ints(mo.get("index")) ?: pos] = mo
        }
        val insts = ft.firstKid("Instances")?.kids("Instance").orEmpty()
        val perModule = HashMap<Int?, Int>()
        for (it in insts) {
            val k = ints(it.get("module_index"))
            perModule[k] = (perModule[k] ?: 0) + 1
        }
        val kOf = HashMap<Int, Int>()
        val entries = ArrayList<Entry>()
        for (it in insts) {
            val mi = ints(it.get("module_index")) ?: continue
            val mo = byIndex[mi] ?: continue
            kOf[mi] = (kOf[mi] ?: 0) + 1
            val p = ints(it.get("patch"))
            val patch = if (truthy(p)) p!! else 1
            var g = mo.get("name")?.ifEmpty { null } ?: "Module $mi"
            if ((perModule[mi] ?: 0) > 1) g = "$g #${kOf[mi]}"
            for (ct in mo.kids("ChannelType")) {
                val offs = byteOffsets(ct, listOf("coarse", "fine", "ultra", "extra_fine"), null)
                if (offs.isEmpty()) continue
                val base = ct.get("attribute") ?: ""
                offs.forEachIndexed { i, o -> entries.add(Entry(patch + o - 1, base, SUFFIX[minOf(i, 3)], g)) }
            }
        }
        return Result.Ok(
            Profile(
                format = "ma2",
                manufacturer = text("manufacturer"),
                fixture = ft.get("name") ?: "",
                shortName = text("short_name"),
                author = author,
                modes = listOf(finish(ft.get("mode") ?: "", entries))
            )
        )
    }

    // ---- zip (.gdtf) -------------------------------------------------------------------

    private sealed class ZipOut {
        class Found(val bytes: ByteArray) : ZipOut()
        object Missing : ZipOut()
        object Broken : ZipOut()
    }

    /**
     * Entrée RACINE « description.xml » (casse ignorée), PREMIÈRE dans l'ordre du
     * RÉPERTOIRE CENTRAL — exactement `zipfile` de l'oracle (`namelist()` puis `read`).
     * Pas de `ZipInputStream` : il lit les en-têtes locaux en séquence et refuse une
     * entrée STORED avec descripteur de données (zip valide écrit en flux) que l'oracle,
     * iOS et le web lisent (cf. [ZipCentralReader]).
     */
    private fun zipDescription(data: ByteArray): ZipOut =
        when (val r = ZipCentralReader.find(data) { it.lowercase() == "description.xml" }) {
            is ZipCentralReader.Lookup.Found -> ZipOut.Found(r.bytes)
            ZipCentralReader.Lookup.Missing -> ZipOut.Missing
            ZipCentralReader.Lookup.Broken -> ZipOut.Broken
        }

    /** Zip lisible contenant une entrée racine « description.xml » (= GDTF lisible). */
    fun isGdtfArchive(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() &&
            zipDescription(data) is ZipOut.Found

    // ---- gzip (.xmlp) ------------------------------------------------------------------

    private fun startsWithMaData(d: ByteArray): Boolean {
        val tag = "MA DATA"
        if (d.size < tag.length) return false
        for (i in tag.indices) if (d[i] != tag[i].code.toByte()) return false
        return true
    }

    private fun indexOfGzipMagic(d: ByteArray): Int {
        for (i in 0 until d.size - 1) if (d[i] == 0x1F.toByte() && d[i + 1] == 0x8B.toByte()) return i
        return -1
    }

    /**
     * `gzip.decompress(data[start:])` de Python, membre par membre : en-tête RFC 1952
     * (FEXTRA/FNAME/FCOMMENT/FHCRC), deflate BRUT, puis CRC32 + ISIZE lus JUSTE APRÈS la
     * fin du flux deflate ; zéros de remplissage ignorés entre membres ; tout le reste
     * (méthode inconnue, flux tronqué, CRC faux, déchet final) → null (= « illisible »).
     */
    internal fun gunzip(src: ByteArray, start: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(maxOf(1024, (src.size - start) * 4))
        var pos = start
        while (true) {
            if (pos >= src.size) return out.toByteArray()          // plus de membre
            if (src.size - pos < 2) return null
            if (src[pos] != 0x1F.toByte() || src[pos + 1] != 0x8B.toByte()) return null
            if (src.size - pos < 10) return null
            if (src[pos + 2].toInt() and 0xFF != 8) return null    // méthode ≠ deflate
            val flg = src[pos + 3].toInt() and 0xFF
            var p = pos + 10
            if (flg and 0x04 != 0) {                                // FEXTRA
                if (p + 2 > src.size) return null
                val xlen = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
                p += 2 + xlen
                if (p > src.size) return null
            }
            if (flg and 0x08 != 0) {                                // FNAME
                while (p < src.size && src[p] != 0.toByte()) p++
                if (p >= src.size) return null
                p++
            }
            if (flg and 0x10 != 0) {                                // FCOMMENT
                while (p < src.size && src[p] != 0.toByte()) p++
                if (p >= src.size) return null
                p++
            }
            if (flg and 0x02 != 0) {                                // FHCRC
                p += 2
                if (p > src.size) return null
            }
            val inf = Inflater(true)
            val member = java.io.ByteArrayOutputStream()
            val consumed: Int
            try {
                inf.setInput(src, p, src.size - p)
                val buf = ByteArray(64 * 1024)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n > 0) member.write(buf, 0, n)
                    else if (!inf.finished()) return null  // flux tronqué (ou dictionnaire exigé)
                }
                consumed = (src.size - p) - inf.remaining
            } catch (_: Exception) {
                return null
            } finally {
                inf.end()
            }
            val tail = p + consumed
            if (src.size - tail < 8) return null
            fun le32(i: Int): Long = ((src[i].toLong() and 0xFF) or ((src[i + 1].toLong() and 0xFF) shl 8) or
                ((src[i + 2].toLong() and 0xFF) shl 16) or ((src[i + 3].toLong() and 0xFF) shl 24))
            val bytes = member.toByteArray()
            val crc = CRC32().apply { update(bytes) }.value
            if (crc != le32(tail)) return null
            if (le32(tail + 4) != (bytes.size.toLong() and 0xFFFFFFFFL)) return null
            out.write(bytes)
            pos = tail + 8
            while (pos < src.size && src[pos] == 0.toByte()) pos++  // remplissage
        }
    }
}
