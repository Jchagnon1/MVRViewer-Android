package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Ressources RÉELLES de l'app lues en test JVM (pas de Robolectric ici) : `strings.xml`
 * FR (`values/`) et EN (`values-en/`), résolution d'un identifiant `R.string.x` par son nom.
 * Les tests vérifient ainsi les textes que verra l'utilisateur, pas une copie.
 */
class TestRes(lang: String) {
    val strings: Map<String, String>
    val plurals: Map<String, Map<String, String>>
    val locale: Locale = if (lang == "fr") Locale.FRENCH else Locale.ENGLISH

    init {
        val dir = if (lang == "fr") "values" else "values-$lang"
        val f = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml")).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val s = HashMap<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            s[e.getAttribute("name")] = unescape(e.textContent)
        }
        val p = HashMap<String, Map<String, String>>()
        val pl = doc.getElementsByTagName("plurals")
        for (i in 0 until pl.length) {
            val e = pl.item(i) as Element
            val items = e.getElementsByTagName("item")
            p[e.getAttribute("name")] = (0 until items.length).associate {
                val it2 = items.item(it) as Element
                it2.getAttribute("quantity") to unescape(it2.textContent)
            }
        }
        strings = s; plurals = p
    }

    private fun unescape(v: String): String {
        var t = v
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
        return t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
    }

    fun get(name: String, vararg args: Any): String {
        val v = strings[name] ?: error("chaîne absente : $name")
        return if (args.isEmpty()) v else String.format(locale, v, *args)
    }

    /** Pluriel : « one » pour 1 (FR : 0 et 1), « other » sinon. */
    fun plural(name: String, n: Int, vararg args: Any): String {
        val m = plurals[name] ?: error("pluriel absent : $name")
        val q = if (n == 1 || (locale == Locale.FRENCH && n == 0)) "one" else "other"
        return String.format(locale, m.getValue(q), *args)
    }

    /** Nom de ressource d'un identifiant `R.string.x`. */
    fun nameOf(id: Int): String = ID_NAMES[id] ?: error("identifiant inconnu : $id")

    fun res(id: Int, args: List<Any>): String = get(nameOf(id), *args.toTypedArray())

    companion object {
        private val ID_NAMES: Map<Int, String> by lazy {
            R.string::class.java.fields.associate { it.getInt(null) to it.name }
        }
        val FR by lazy { TestRes("fr") }
        val EN by lazy { TestRes("en") }
    }
}
