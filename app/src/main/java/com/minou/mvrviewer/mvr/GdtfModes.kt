package com.minou.mvrviewer.mvr

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import kotlin.math.pow

/** Détail d'un mode DMX GDTF — portage de parseDMXModes (iOS GDTFLoader). */
data class DmxChannelSet(val name: String, val dmxFrom: Int)

data class DmxChannelFunction(
    val name: String,
    val dmxFrom: Int,
    val physicalFrom: Float?,
    val physicalTo: Float?,
    val channelSets: List<DmxChannelSet>
)

data class DmxChannel(
    val attribute: String,
    val offsets: List<Int>,
    val geometry: String?,
    val defaultValue: Int?,
    val functions: List<DmxChannelFunction>
)

data class DmxMode(val name: String, val footprint: Int, val channels: List<DmxChannel>)

/**
 * Extrait les modes DMX d'un .gdtf (octets bruts du zip) avec le détail des
 * canaux : attribut, offsets (1 = 8 bits, 2 = coarse+fine), fonctions et
 * valeurs nommées. Empreinte du mode = plus grand Offset déclaré. Machine à
 * états équivalente au délégué XML iOS.
 */
object GdtfModes {

    fun parse(gdtfBytes: ByteArray): List<DmxMode> {
        val xml = MvrParser.extractEntry(gdtfBytes, "description.xml")
            ?: MvrParser.extractEntry(gdtfBytes, "Description.xml")
            ?: return emptyList()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), null)

        val modes = mutableListOf<DmxMode>()
        var modeName: String? = null
        var maxOffset = 0
        var channels = mutableListOf<DmxChannel>()

        var offsets = mutableListOf<Int>()
        var geometry: String? = null
        var default: Int? = null
        // On ne garde que le PREMIER LogicalChannel de chaque DMXChannel.
        var loggedLogical = false
        var insideTarget = false
        var attribute = "?"
        var functions = mutableListOf<DmxChannelFunction>()
        var curFunctionSets = mutableListOf<DmxChannelSet>()
        var curFunction: DmxChannelFunction? = null

        fun flushFunction() {
            curFunction?.let { functions.add(it.copy(channelSets = curFunctionSets.toList())) }
            curFunction = null
            curFunctionSets = mutableListOf()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "DMXMode" -> {
                        modeName = parser.getAttributeValue(null, "Name") ?: "Mode"
                        maxOffset = 0; channels = mutableListOf()
                    }
                    "DMXChannel" -> {
                        offsets = mutableListOf()
                        geometry = parser.getAttributeValue(null, "Geometry")
                        default = parser.getAttributeValue(null, "Default")?.let(::parseDmxValue)
                        loggedLogical = false
                        val off = parser.getAttributeValue(null, "Offset")
                        if (off != null && !off.equals("none", true)) {
                            for (part in off.split(",")) {
                                part.trim().toIntOrNull()?.let { offsets.add(it); if (it > maxOffset) maxOffset = it }
                            }
                        }
                    }
                    "LogicalChannel" -> if (!loggedLogical) {
                        loggedLogical = true; insideTarget = true
                        attribute = parser.getAttributeValue(null, "Attribute") ?: "?"
                        functions = mutableListOf()
                    }
                    "ChannelFunction" -> if (insideTarget) {
                        flushFunction()
                        curFunction = DmxChannelFunction(
                            name = parser.getAttributeValue(null, "Name") ?: "",
                            dmxFrom = parser.getAttributeValue(null, "DMXFrom")?.let(::parseDmxValue) ?: 0,
                            physicalFrom = parser.getAttributeValue(null, "PhysicalFrom")?.toFloatOrNull(),
                            physicalTo = parser.getAttributeValue(null, "PhysicalTo")?.toFloatOrNull(),
                            channelSets = emptyList()
                        )
                        curFunctionSets = mutableListOf()
                    }
                    "ChannelSet" -> if (insideTarget && curFunction != null) {
                        val name = parser.getAttributeValue(null, "Name")
                        if (!name.isNullOrEmpty()) {
                            curFunctionSets.add(DmxChannelSet(name, parser.getAttributeValue(null, "DMXFrom")?.let(::parseDmxValue) ?: 0))
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "ChannelFunction" -> if (insideTarget) flushFunction()
                    "LogicalChannel" -> if (insideTarget) {
                        channels.add(DmxChannel(attribute, offsets.toList(), geometry, default, functions.toList()))
                        insideTarget = false
                    }
                    "DMXMode" -> modeName?.let {
                        modes.add(DmxMode(it, maxOf(maxOffset, 1), channels.toList()))
                        modeName = null
                    }
                }
            }
            event = parser.next()
        }
        return modes
    }

    /** DMXFrom/Default GDTF = "valeur/nbOctets" (ex. "32768/2") → normalisé 0..255. */
    private fun parseDmxValue(raw: String): Int {
        val parts = raw.split("/")
        val value = parts.firstOrNull()?.toIntOrNull() ?: return 0
        val bytes = parts.getOrNull(1)?.toIntOrNull() ?: return value
        if (bytes <= 1) return value
        val divisor = 256.0.pow(bytes - 1).toInt()
        return if (divisor > 0) value / divisor else value
    }
}
