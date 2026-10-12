package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Noms des entrées SANS drapeau UTF-8 : la règle « UTF-8 valide, sinon CP437 » est RÉSERVÉE
 * aux archives `.zip` de profils MA (SPEC-ma-gdtfshare.md §5.2). Les .mvr et .gdtf gardent
 * la lecture Latin-1, comme fflate sur le web et comme Android avant f990cb9.
 */
class ZipEntryNamesTest {

    /** .mvr dont une entrée a un nom Latin-1 (« mod\xE8le.3ds ») sans drapeau UTF-8. */
    private fun latin1Mvr(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.ISO_8859_1).use { z ->
            z.putNextEntry(ZipEntry("GeneralSceneDescription.xml")); z.write("<GeneralSceneDescription/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("modèle.3ds")); z.write(ByteArray(80) { it.toByte() }); z.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * ZipInputStream refuse ce nom (UTF-8 non valide) : MvrParser passe par le répertoire
     * central, qui doit retrouver l'entrée comme avant (et comme le web). Avec la règle CP437,
     * 0xE8 donnait « Φ » : « modΦle.3ds », entrée introuvable.
     */
    @Test fun mvrFallbackKeepsLatin1Names() {
        val bytes = latin1Mvr()
        // Préconditions : pas de drapeau UTF-8, et la lecture séquentielle échoue bien.
        val l = ZipCentralReader.list(bytes) as ZipCentralReader.Listing.Ok
        assertTrue(l.entries.none { it.flags and 0x800 != 0 })
        val sequential = runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (z.nextEntry != null) Unit }
        }
        assertTrue(sequential.isFailure)

        assertEquals(setOf("modèle.3ds"), MvrParser.extractEntries(bytes, setOf("modèle.3ds")).keys)
        assertEquals(80, MvrParser.extractEntry(bytes, "modèle.3ds")?.size)
        assertNotNull(MvrParser.extractEntryIgnoringCase(bytes, "MODÈLE.3DS"))
        assertTrue("modèle.3ds" in MvrParser.listEntries(bytes))
    }

    /** Même archive : Latin-1 par défaut, CP437 seulement sur demande (import MA). */
    @Test fun cp437OnlyForMaArchives() {
        val bytes = latin1Mvr()
        val byDefault = ZipCentralReader.list(bytes) as ZipCentralReader.Listing.Ok
        assertEquals(listOf("GeneralSceneDescription.xml", "modèle.3ds"), byDefault.entries.map { it.name })
        val ma = ZipCentralReader.list(ZipCentralReader.ByteSource(bytes), names = ZipCentralReader.NameRule.UTF8_OR_CP437)
            as ZipCentralReader.Listing.Ok
        assertEquals(listOf("GeneralSceneDescription.xml", "modΦle.3ds"), ma.entries.map { it.name })
        // UTF-8 SANS drapeau : Latin-1 par défaut (comme fflate), UTF-8 pour l'import MA.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.ISO_8859_1).use { z ->
            // Octets UTF-8 de « é » écrits tels quels (nom Latin-1 « Ã© ») : pas de drapeau.
            z.putNextEntry(ZipEntry(String("Rémi.xml".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)))
            z.write("<x/>".toByteArray()); z.closeEntry()
        }
        val utf8NoFlag = out.toByteArray()
        assertEquals(listOf("RÃ©mi.xml"), ZipCentralReader.names(utf8NoFlag))
        val maUtf8 = ZipCentralReader.list(ZipCentralReader.ByteSource(utf8NoFlag), names = ZipCentralReader.NameRule.UTF8_OR_CP437)
            as ZipCentralReader.Listing.Ok
        assertEquals(listOf("Rémi.xml"), maUtf8.entries.map { it.name })
        assertEquals(listOf("x.zip › Rémi.xml"), MaZipImport.plan("x.zip", ZipCentralReader.ByteSource(utf8NoFlag)).candidates.map { it.displayName })
    }
}
