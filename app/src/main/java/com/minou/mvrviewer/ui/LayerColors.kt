package com.minou.mvrviewer.ui

import androidx.compose.ui.graphics.Color
import com.minou.mvrviewer.mvr.MvrScene

/**
 * Couleur par calque — partagée entre la vue 3D (teinte des projecteurs) et la
 * vue plan (glyphes), pour qu'un projecteur ait la MÊME couleur partout, comme
 * côté iOS (LayerColorStore). Palette de teintes distinctes indexée par l'ordre
 * d'apparition du calque.
 */
object LayerColors {
    val PALETTE = intArrayOf(
        0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF3949AB.toInt(), 0xFF1E88E5.toInt(),
        0xFF00ACC1.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(), 0xFFFB8C00.toInt(),
        0xFF6D4C41.toInt(), 0xFFEC407A.toInt(), 0xFF26A69A.toInt(), 0xFF7CB342.toInt()
    )

    /** Table calque → index d'apparition (à mémoïser par scène). */
    fun index(scene: MvrScene): Map<String, Int> =
        scene.layers.withIndex().associate { (i, l) -> l.name to i }

    fun colorInt(index: Map<String, Int>, layerName: String): Int =
        PALETTE[(index[layerName] ?: 0).mod(PALETTE.size)]

    fun color(index: Map<String, Int>, layerName: String): Color =
        Color(colorInt(index, layerName))
}
