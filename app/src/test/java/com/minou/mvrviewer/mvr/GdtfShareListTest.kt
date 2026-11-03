package com.minou.mvrviewer.mvr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Liste GDTF Share NORMALISÉE (SPEC-ma-gdtfshare.md §1.2, tests T1-T2) : la liste d'essai
 * (`share/getList_sample.json`) donne EXACTEMENT `share/getList_expected.json` (comparaison
 * structurelle, réels à 1e-9) ; enveloppes acceptées ; corps non JSON → liste vide.
 */
class GdtfShareListTest {

    private fun res(path: String) = File(javaClass.classLoader!!.getResource("ma/$path")!!.toURI())

    private fun assertEntry(exp: JSONObject, got: GdtfShareEntry) {
        val tag = "rid ${exp.getInt("rid")}"
        assertEquals(tag, exp.getInt("rid"), got.rid)
        assertEquals(tag, exp.getString("fixture"), got.fixture)
        assertEquals(tag, exp.getString("manufacturer"), got.manufacturer)
        assertEquals(tag, exp.getString("uuid"), got.uuid)
        if (exp.isNull("rating")) assertNull(tag, got.rating) else assertEquals(tag, exp.getDouble("rating"), got.rating!!, 1e-9)
        assertEquals(tag, exp.getString("revision"), got.revision)
        fun long(k: String): Long? = if (exp.isNull(k)) null else exp.getLong(k)
        assertEquals("$tag creationDate", long("creationDate"), got.creationDate)
        assertEquals("$tag lastModified", long("lastModified"), got.lastModified)
        assertEquals(tag, exp.getString("uploader"), got.uploader)
        assertEquals(tag, exp.getString("version"), got.version)
        assertEquals("$tag filesize", long("filesize"), got.filesize)
        val modes = exp.getJSONArray("modes")
        assertEquals("$tag modes", modes.length(), got.modes.size)
        for (i in 0 until modes.length()) {
            val m = modes.getJSONObject(i)
            assertEquals("$tag mode $i", GdtfShareMode(m.getString("name"), m.getInt("footprint")), got.modes[i])
        }
    }

    /** T1 — normalisation de la liste d'essai, champ par champ. */
    @Test fun sampleListNormalizesExactlyToExpected() {
        val got = GdtfShareClient.parseList(res("share/getList_sample.json").readText())
        val exp = JSONArray(res("share/getList_expected.json").readText())
        assertEquals(exp.length(), got.size)
        for (i in 0 until exp.length()) assertEntry(exp.getJSONObject(i), got[i])
        // Pièges couverts (§1.5) : rid en chaîne, rid 0 / uuid absent ou vide ignorés, dates en
        // chaîne et en ms, version numérique 1.0, modes sous trois formes, « 10 » après « 4 ».
        assertEquals(listOf(12345, 12346, 777, 778, 901), got.map { it.rid })
        assertEquals(listOf("Standard", "Vector", "Shapes", "Basic", "Full"), got[2].modes.map { it.name })
        assertEquals("1.0", got[1].version)
        assertEquals(1600000000L, got[3].lastModified)
        assertTrue(GdtfShareList.providesFootprints(got))
    }

    /** T2 — enveloppes : objet portant `list` OU tableau nu ; tout le reste → vide. */
    @Test fun envelopesAndGarbage() {
        val one = """{"rid":5,"fixture":"F","manufacturer":"M","uuid":"U"}"""
        assertEquals(listOf(5), GdtfShareClient.parseList("""{"result":true,"list":[$one]}""").map { it.rid })
        assertEquals(listOf(5), GdtfShareClient.parseList("  [$one]  ").map { it.rid })
        assertTrue(GdtfShareClient.parseList("<html><body>Login</body></html>").isEmpty())
        assertTrue(GdtfShareClient.parseList("").isEmpty())
        assertTrue(GdtfShareClient.parseList("""{"result":false,"error":"x"}""").isEmpty())
        assertTrue(GdtfShareClient.parseList("[1, \"x\", null]").isEmpty())
        // Aucune entrée n'annonce de mode → filtre d'empreinte désactivé (relais ancien).
        val noModes = GdtfShareClient.parseList("[$one]")
        assertFalse(GdtfShareList.providesFootprints(noModes))
        val e = noModes.single()
        assertEquals("", e.revision); assertNull(e.creationDate); assertEquals("", e.uploader)
        assertEquals("", e.version); assertNull(e.filesize); assertTrue(e.modes.isEmpty()); assertEquals(0L, e.date)
    }

    @Test fun scalarRules() {
        fun one(fields: String) = GdtfShareClient.parseList("""[{"rid":7,"uuid":"U",$fields}]""").single()
        assertEquals("1.1", one(""""version":1.1""").version)
        assertEquals("2.0", one(""""version":2""").version)
        assertEquals("1.2", one(""""version":"  1.2 """").version)
        assertEquals("manufacturer", one(""""uploader":" MANUFACTURER """").uploader)
        assertEquals("", one(""""uploader":"admin"""").uploader)
        assertEquals(null, one(""""rating":"NaN"""").rating)
        assertEquals(12L, one(""""filesize":12.0""").filesize)
        assertEquals(null, one(""""filesize":-3""").filesize)
        assertEquals(null, one(""""creationDate":0""").creationDate)
        // Date = lastModified, sinon creationDate.
        assertEquals(1500000000L, one(""""creationDate":1500000000""").date)
        assertTrue(GdtfShareClient.parseList("""[{"rid":-4,"uuid":"U"},{"rid":"12a","uuid":"U"},{"rid":3,"uuid":"  "}]""").isEmpty())
        // Mode d'empreinte < 1 ignoré ; flottant entier accepté.
        assertEquals(listOf(GdtfShareMode("A", 3)), one(""""modes":[{"name":" A ","dmxfootprint":3.0},{"name":"B","dmxfootprint":0}]""").modes)
    }
}
