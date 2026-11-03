package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Partage d'un projet PAR CODE seulement (guide de mise en vente C4, comme iOS) :
 * plus de « Partager le lien » (mvrviewer://join/CODE, que rien n'ouvre : aucun
 * filtre d'intention pour ce schéma) ; le code reste copiable, « Copier le code »
 * devient « Code copié ». Le champ `url` du document `invites` reste écrit (spec
 * du partage inchangée, iOS fait de même).
 */
class ShareByCodeTest {

    private fun file(vararg candidates: String): File = candidates.map(::File).first { it.isFile }
    private val syncUi by lazy {
        file("src/main/java/com/minou/mvrviewer/ui/SyncUI.kt", "app/src/main/java/com/minou/mvrviewer/ui/SyncUI.kt").readText()
    }

    @Test fun plusDeLienDInvitation() {
        val share = syncUi.substringAfter("fun ShareProjectDialog(").substringBefore("fun JoinProjectDialog(")
        assertFalse(share.contains("ACTION_SEND"))
        assertFalse(share.contains("createChooser"))
        assertFalse(share.contains("mvrviewer://"))
        for (k in listOf("sync_share_link", "sync_share_message_fmt", "sync_share_code_chooser")) {
            assertNull("fr $k", TestRes.FR.strings[k])
            assertNull("en $k", TestRes.EN.strings[k])
        }
        // Aucun texte de l'app ne propose plus ce lien.
        for (res in listOf(TestRes.FR, TestRes.EN)) {
            assertFalse(res.strings.values.any { it.contains("mvrviewer://") || it.contains("mvrviewer.app") })
        }
        // Et aucun filtre d'intention ne prétend l'ouvrir.
        val manifest = file("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android:scheme=\"mvrviewer\""))
    }

    @Test fun codeCopiable() {
        val share = syncUi.substringAfter("fun ShareProjectDialog(").substringBefore("fun JoinProjectDialog(")
        assertTrue(share.contains("ClipData.newPlainText("))
        assertTrue(share.contains("if (didCopy) R.string.sync_code_copied else R.string.sync_copy_code"))
        assertTrue(share.contains("SelectionContainer"))
        assertEquals("Copier le code", TestRes.FR.get("sync_copy_code"))
        assertEquals("Code copié", TestRes.FR.get("sync_code_copied"))
        assertEquals("Copy the code", TestRes.EN.get("sync_copy_code"))
        assertEquals("Code copied", TestRes.EN.get("sync_code_copied"))
    }
}
