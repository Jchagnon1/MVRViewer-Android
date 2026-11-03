package com.minou.mvrviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * « Mentions et licences » : chaque cran de la navigation garde SON défilement.
 * Défauts relevés à l'émulateur : un composant inclus (Draco depuis Filament)
 * s'ouvrait défilé jusqu'à la fin de son texte, le défilement de la fiche parente
 * lui étant transmis ; le retour d'une fiche ramenait la liste des composants en haut.
 */
class LicensesNavigationTest {

    private val screen by lazy {
        listOf(File("src/main/java/com/minou/mvrviewer/ui/LicensesScreen.kt"),
            File("app/src/main/java/com/minou/mvrviewer/ui/LicensesScreen.kt")).first { it.isFile }.readText()
    }

    @Test fun uneCleDEtatParCran() {
        assertEquals("list", LicensesNavigation.stateKey(emptyList()))
        val parent = LicensesNavigation.stateKey(listOf("filament"))
        val child = LicensesNavigation.stateKey(listOf("filament", "draco"))
        val alone = LicensesNavigation.stateKey(listOf("draco"))
        // Le composant inclus n'hérite jamais de l'état de son parent…
        assertNotEquals(parent, child)
        // … ni de celui du même composant ouvert depuis la liste.
        assertNotEquals(alone, child)
        assertEquals(4, setOf("list", parent, child, alone).size)
    }

    @Test fun chaqueCranDansSonEtat() {
        assertTrue(screen.contains("val crans = rememberSaveableStateHolder()"))
        assertTrue(screen.contains("opened != null -> crans.SaveableStateProvider(LicensesNavigation.stateKey(stack)) {"))
        assertTrue(screen.contains("else -> crans.SaveableStateProvider(LicensesNavigation.stateKey(emptyList())) {"))
        // Un cran refermé oublie son état : rouvert plus tard, il repart du haut.
        assertTrue(screen.contains("crans.removeState(LicensesNavigation.stateKey(stack))"))
        val pop = screen.substringAfter("fun pop() {").substringBefore("\n    }")
        assertTrue(pop.indexOf("crans.removeState") < pop.indexOf("stackRaw = stack.dropLast(1)"))
    }
}
