package com.minou.mvrviewer.mvr

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Routeur des fichiers reçus (SPEC-ma-gdtfshare.md §5.3, test T12) : chaque fichier de
 * `routing/EXPECTED.json` (chemin relatif à `ma/`, nom présenté, route attendue).
 */
class IncomingFileRouterTest {

    private fun res(path: String) = File(javaClass.classLoader!!.getResource("ma/$path")!!.toURI())

    private fun raw(r: IncomingFileRouter.Route) = when (r) {
        IncomingFileRouter.Route.MVR -> "mvr"
        IncomingFileRouter.Route.MODEL -> "model"
        IncomingFileRouter.Route.GDTF -> "gdtf"
        IncomingFileRouter.Route.MA_PROFILES -> "maProfiles"
    }

    @Test fun routingTable() {
        val table = JSONArray(res("routing/EXPECTED.json").readText())
        check(table.length() >= 13)
        for (i in 0 until table.length()) {
            val row = table.getJSONArray(i)
            val path = row.getString(0)
            val name = if (row.isNull(1)) null else row.getString(1)
            val expected = row.getString(2)
            assertEquals("$path sous le nom $name", expected, raw(IncomingFileRouter.route(name, res(path).readBytes())))
        }
    }

    /** Même décision avec l'archive lue par positions (fichier), comme sur l'appareil. */
    @Test fun fileSourceGivesTheSameRoute() {
        val f = res("zips/ma_profiles.zip")
        val head = f.readBytes().copyOf(4096)
        val r = ZipCentralReader.FileSource.open(f).use { src -> IncomingFileRouter.route(null, head) { src } }
        assertEquals(IncomingFileRouter.Route.MA_PROFILES, r)
    }

    @Test fun xmlRootSkipsPrologCommentsAndBom() {
        val xml = "﻿<?xml version=\"1.0\"?>\n<!-- c -->\n<!DOCTYPE x>\n<ns:GMA3 a=\"1\">".toByteArray()
        assertEquals("GMA3", IncomingFileRouter.xmlRoot(xml))
        assertEquals("MA", IncomingFileRouter.xmlRoot("<MA/>".toByteArray()))
        assertEquals(null, IncomingFileRouter.xmlRoot("pas du xml".toByteArray()))
        // Un .xml d'autre racine reste un show (le lecteur de show dira ce qui ne va pas).
        assertEquals(IncomingFileRouter.Route.MVR, IncomingFileRouter.route("x.xml", "<rss/>".toByteArray()))
        assertEquals(IncomingFileRouter.Route.MA_PROFILES, IncomingFileRouter.route("x.XML", "<GDTF/>".toByteArray()))
        // Ni nom ni zip → show (comportement existant).
        assertEquals(IncomingFileRouter.Route.MVR, IncomingFileRouter.route(null, ByteArray(10)))
    }
}
