package com.minou.mvrviewer.mvr

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Extraction de la CONSOMMATION ÉLECTRIQUE d'un type de projecteur depuis son
 * .gdtf (phase 1 de l'outil de câblage). Le GDTF déclare la puissance dans
 * `PhysicalDescriptions/Properties/PowerConsumption`, attribut `Value` en WATTS.
 *
 * POURQUOI le MAXIMUM : un même profil peut porter PLUSIEURS <PowerConsumption>
 * (une par « connecteur »/mode d'alimentation). Pour le dimensionnement câble/
 * disjoncteur, seul le pire cas compte → on retient la valeur la plus élevée,
 * arrondie à l'entier (« puissance max du type »). Aucune balise = pas de valeur.
 *
 * Contrat PARTAGÉ iOS/Android : même règle d'extraction des deux côtés.
 */
object GdtfPower {

    /** Puissance MAX déclarée (W, entier), ou null si le .gdtf n'en déclare aucune. */
    fun maxWatts(gdtfBytes: ByteArray): Int? {
        val xml = MvrParser.extractEntry(gdtfBytes, "description.xml")
            ?: MvrParser.extractEntry(gdtfBytes, "Description.xml")
            ?: return null

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), null)

        // On COLLECTE les attributs Value bruts au fil du scan XML, puis on
        // applique la règle numérique du contrat (fonction pure, testable hors
        // Android — `android.util.Xml` n'existe pas en test JVM, cf. GdtfModes).
        val values = ArrayList<String?>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "PowerConsumption") {
                values.add(parser.getAttributeValue(null, "Value"))
            }
            event = parser.next()
        }
        return reduceWatts(values)
    }

    /**
     * Règle du contrat : la puissance max du type = MAXIMUM des `Value` valides,
     * arrondi à l'entier ; null si aucune valeur exploitable. Tolère la virgule
     * décimale (certains exports l'emploient) et ignore 0 / valeurs non numériques.
     * PARTAGÉE iOS/Android — même comportement des deux côtés.
     */
    internal fun reduceWatts(values: Iterable<String?>): Int? {
        var best: Double? = null
        for (raw in values) {
            val w = raw?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: continue
            if (w.isFinite() && w > 0.0 && (best == null || w > best!!)) best = w
        }
        return best?.let { Math.round(it).toInt() }
    }
}
