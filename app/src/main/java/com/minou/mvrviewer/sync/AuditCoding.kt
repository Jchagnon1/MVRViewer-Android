package com.minou.mvrviewer.sync

import com.minou.mvrviewer.mvr.GeoAnchor
import com.minou.mvrviewer.mvr.ReferencePlanTransform

/**
 * Sérialisation des valeurs BRUTES portées par une entrée d'audit (`oldRaw` /
 * `newRaw`) pour les champs qui ne sont pas une simple chaîne.
 *
 * Le format est volontairement plat et stable : c'est un format d'ÉCHANGE (les
 * documents d'audit sont lus par iOS comme par Android), et il sert aussi de
 * signature de comparaison anti-écho. Ne pas changer sans changer l'autre
 * plateforme — une entrée illisible redevient simplement non annulable.
 */
object AuditCoding {

    // Ancres GPS : "x,y,lat,lon|x,y,lat,lon…" (vide = aucune calibration).
    fun encodeAnchors(anchors: List<GeoAnchor>): String =
        anchors.joinToString("|") { "${it.worldX},${it.worldY},${it.latitude},${it.longitude}" }

    fun decodeAnchors(raw: String): List<GeoAnchor> =
        raw.split("|").mapNotNull { part ->
            val f = part.split(",")
            if (f.size != 4) return@mapNotNull null
            val x = f[0].toFloatOrNull(); val y = f[1].toFloatOrNull()
            val la = f[2].toDoubleOrNull(); val lo = f[3].toDoubleOrNull()
            if (x == null || y == null || la == null || lo == null) null
            else GeoAnchor(x, y, la, lo)
        }

    // Placement du plan DXF : "offsetX,offsetY,rotationDeg,scale,heightZ,visible".
    // La chaîne reste à SIX champs (contrat cross-plateforme, décodé par index) :
    // l'HOMOTHÉTIE n'y ajoute pas de colonne, elle est fondue dans `scale` via
    // `effScale`. Homothétie = 1 (défaut, tout le chemin DXF) → chaîne identique
    // au bit près. La factorisation scale/homothety, elle, reste locale.
    fun encodeTransform(t: ReferencePlanTransform): String =
        "${t.offsetX},${t.offsetY},${t.rotationDeg},${t.effScale},${t.heightZ},${t.visible}"

    fun decodeTransform(raw: String): ReferencePlanTransform? {
        val f = raw.split(",")
        if (f.size != 6) return null
        return ReferencePlanTransform(
            offsetX = f[0].toDoubleOrNull() ?: return null,
            offsetY = f[1].toDoubleOrNull() ?: return null,
            rotationDeg = f[2].toDoubleOrNull() ?: return null,
            scale = f[3].toDoubleOrNull() ?: return null,
            heightZ = f[4].toDoubleOrNull() ?: return null,
            visible = f[5].toBooleanStrictOrNull() ?: true
        )
    }

    // Calques masqués : noms triés joints par "|" (l'ordre ne porte pas de sens).
    fun encodeLayers(layers: Set<String>): String = layers.sorted().joinToString("|")

    fun decodeLayers(raw: String): Set<String> =
        if (raw.isBlank()) emptySet() else raw.split("|").filter { it.isNotBlank() }.toSet()

    // ---- Libellés d'affichage (colonne « avant → après » de l'historique) ----

    fun describeAnchors(raw: String): String {
        val n = decodeAnchors(raw).size
        return if (n == 0) "aucune ancre" else "$n ancre${if (n > 1) "s" else ""}"
    }

    fun describeTransform(raw: String): String {
        val t = decodeTransform(raw) ?: return "—"
        return "x %.0f · y %.0f · %.1f° · ×%.3f".format(t.offsetX, t.offsetY, t.rotationDeg, t.scale)
    }

    fun describeLayers(raw: String): String {
        val n = decodeLayers(raw).size
        return if (n == 0) "aucun masqué" else "$n masqué${if (n > 1) "s" else ""}"
    }
}
