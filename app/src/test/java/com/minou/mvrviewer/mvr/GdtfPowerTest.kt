package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L'extraction de puissance est la SOURCE du dimensionnement câble : ce test
 * verrouille la règle NUMÉRIQUE du contrat (max des PowerConsumption, arrondi
 * entier, tolérance virgule, rejet 0/non numérique). Le scan XML lui-même n'est
 * pas testable en JVM (`android.util.Xml` non mocké, comme GdtfModes) mais il ne
 * fait que collecter les `Value` bruts et délègue à `reduceWatts`.
 */
class GdtfPowerTest {

    @Test fun maxOfMultipleValuesRounded() {
        // 750.5 → 751 ; c'est bien le maximum des trois.
        assertEquals(751, GdtfPower.reduceWatts(listOf("450.000000", "750.500000", "600")))
    }

    @Test fun commaDecimalSeparatorTolerated() {
        assertEquals(1200, GdtfPower.reduceWatts(listOf("1200,0")))
    }

    @Test fun noValuesReturnsNull() {
        assertNull(GdtfPower.reduceWatts(emptyList()))
    }

    @Test fun zeroNullAndGarbageIgnored() {
        assertNull(GdtfPower.reduceWatts(listOf("0", null, "abc", "  ")))
    }

    @Test fun ignoresBadValuesButKeepsGoodOne() {
        assertEquals(575, GdtfPower.reduceWatts(listOf("abc", "575", "-10", "0")))
    }
}
