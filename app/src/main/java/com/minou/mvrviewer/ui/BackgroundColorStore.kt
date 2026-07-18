package com.minou.mvrviewer.ui

import android.content.Context
import androidx.compose.ui.graphics.Color

/**
 * Couleur de fond des vues, choisie par l'utilisateur et PERSISTÉE GLOBALEMENT
 * (préférence d'affichage, pas par projet — comme BackgroundColorStore iOS sur
 * UserDefaults). Deux couleurs indépendantes : la vue 3D (défaut noir) et la
 * vue plan (défaut blanc). Stockées en ARGB opaque dans les SharedPreferences.
 */
object BackgroundColorStore {
    private const val PREFS = "mvrviewer.display"
    private const val KEY_3D = "bgColor.scene3D"
    private const val KEY_2D = "bgColor.plan2D"

    val DEFAULT_3D = Color(0xFF000000)  // noir
    val DEFAULT_2D = Color(0xFFFFFFFF)  // blanc

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun scene3D(ctx: Context): Color =
        prefs(ctx).let { if (it.contains(KEY_3D)) Color(it.getInt(KEY_3D, 0)) else DEFAULT_3D }

    fun plan2D(ctx: Context): Color =
        prefs(ctx).let { if (it.contains(KEY_2D)) Color(it.getInt(KEY_2D, 0)) else DEFAULT_2D }

    fun setScene3D(ctx: Context, c: Color) =
        prefs(ctx).edit().putInt(KEY_3D, c.toArgbOpaque()).apply()

    fun setPlan2D(ctx: Context, c: Color) =
        prefs(ctx).edit().putInt(KEY_2D, c.toArgbOpaque()).apply()

    /** Luminance relative Rec.709 (0 = noir, 1 = blanc). */
    fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** Fond « sombre » : les éléments dessinés en foncé doivent s'éclaircir. */
    fun isDark(c: Color): Boolean = luminance(c) < 0.5f
}

/** ARGB opaque (alpha forcé à 255) — le fond est toujours plein. */
private fun Color.toArgbOpaque(): Int {
    fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(red) shl 16) or (ch(green) shl 8) or ch(blue)
}
