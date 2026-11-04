package com.minou.mvrviewer.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * NOM AFFICHÉ (SPEC-partage-moderation §4, C-tous-12) : les cas COMMUNS aux trois
 * clients, `tests/fixtures/nom-affiche/vectors.json` du dépôt web, copiés tels quels
 * (même blob git d0a0bfd) dans `port-vectors/nom-affiche/`. Attendu d'Android = le
 * champ commun `expected` (celui du web aussi), JAMAIS le champ `ios` (écart connu et
 * accepté sur certaines lettres non ASCII, §4).
 */
class DisplayNameVectorsTest {

    private val root: JSONObject by lazy {
        val url = javaClass.classLoader!!.getResource("port-vectors/nom-affiche/vectors.json")!!
        JSONObject(File(url.toURI()).readText(Charsets.UTF_8))
    }

    private val cases: List<JSONObject> by lazy {
        val a: JSONArray = root.getJSONArray("cases")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** Points de code d'une chaîne, lisibles dans un message d'échec (« U+200B Bob »). */
    private fun visible(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0x20..0x7E) append(cp.toChar()) else append("[U+%04X]".format(cp))
            i += Character.charCount(cp)
        }
    }

    private fun codePoints(key: String): List<Int> {
        val a = root.getJSONArray(key)
        return (0 until a.length()).map { a.getString(it).removePrefix("U+").toInt(16) }
    }

    @Test fun lesTrenteQuatreCasCommunsDuWeb() {
        assertEquals("mvrviewer.vectors.nom-affiche/1", root.getString("schema"))
        assertEquals(34, cases.size)
        val failures = cases.mapIndexedNotNull { i, c ->
            val name = c.getString("name")
            val email = c.getString("email")
            val expected = c.getString("expected")
            val got = ProjectNames.displayName(name, email)
            if (got == expected) null
            else "cas ${i + 1} (${c.getString("why")}) : nom « ${visible(name)} », e-mail « ${visible(email)} » → " +
                "« ${visible(got)} », attendu « ${visible(expected)} »"
        }
        assertEquals("cas en échec :\n" + failures.joinToString("\n"), 0, failures.size)
    }

    /** La liste `edgeSpaces` du fichier commun = celle de `trimEdges`, à l'unité près (31 points). */
    @Test fun laListeDesBordsEstCelleDuFichierCommun() {
        val listed = codePoints("edgeSpaces")
        assertEquals(31, listed.size)
        assertEquals(listed.toSet(), ProjectNames.edgeSpaces)
        assertEquals(31, ProjectNames.edgeSpaces.size)
        for (kept in codePoints("keptAtEdges")) {
            val c = kept.toChar()
            assertEquals("U+%04X gardé aux bords".format(kept), "${c}Bob$c", ProjectNames.trimEdges("${c}Bob$c"))
        }
    }

    /**
     * Tout le plan de base (hors moitiés de paires de substitution) : `trimEdges` retire
     * aux bords EXACTEMENT les points de la liste, un par un, et rien d'autre ; jamais à
     * l'intérieur. Kotlin `trim()` gardait U+0085, U+200B et U+FEFF.
     */
    @Test fun trimEdgesRetireExactementLaListeSurToutLePlanDeBase() {
        val listed = codePoints("edgeSpaces").toSet()
        for (code in 0..0xFFFF) {
            if (code in 0xD800..0xDFFF) continue
            val c = code.toChar()
            val trimmed = ProjectNames.trimEdges("$c$c" + "B${c}b" + "$c")
            val expected = if (code in listed) "B${c}b" else "$c${c}B${c}b$c"
            assertEquals("U+%04X".format(code), expected, trimmed)
        }
        assertEquals("", ProjectNames.trimEdges("​"))
        assertEquals("", ProjectNames.trimEdges(listed.joinToString("") { it.toChar().toString() }))
        // Point de code par point de code, jamais par graphème : seule l'espace part.
        assertEquals("́Bob", ProjectNames.trimEdges(" ́Bob"))
        // Une paire de substitution aux bords n'est jamais entamée.
        assertEquals("😀Bob😀", ProjectNames.trimEdges("　😀Bob😀﻿"))
    }
}
