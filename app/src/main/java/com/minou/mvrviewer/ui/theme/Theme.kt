package com.minou.mvrviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Schémas EXPLICITES, `dynamicColor` SUPPRIMÉ — et ce n'est pas un détail :
 * avec Material You, le fond d'écran de l'appareil repeignait toute l'app.
 * Une identité n'existe que si elle est la même chez tout le monde.
 *
 * `primary` = l'ENCRE d'accent (pas le jaune franc) : c'est la couleur que
 * Material3 pose sur les TextButton, les liens, les toggles — du texte, où le
 * jaune franc serait illisible sur fond clair (1,6:1). Les surfaces PLEINES
 * d'identité (action principale, outil actif) demandent explicitement
 * `accentButtonColors()` / `accentToggleColors()` — cf. theme/Color.kt.
 */
private val LightColors = lightColorScheme(
    primary = Accent.inkLight,
    onPrimary = Color.White,
    primaryContainer = Accent.fillLight,
    onPrimaryContainer = Accent.on,
    secondary = Color(0xFF5F5C52),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E2D9),
    onSecondaryContainer = Color(0xFF1C1B16)
)

private val DarkColors = darkColorScheme(
    // En sombre, un seul jaune sert encre ET remplissage : #FFD60A est
    // lisible sur fond sombre (12:1) et porte du noir en surface.
    primary = Accent.inkDark,
    onPrimary = Accent.on,
    primaryContainer = Accent.fillDark,
    onPrimaryContainer = Accent.on,
    secondary = Color(0xFFC9C6BA),
    onSecondary = Color(0xFF32302A),
    secondaryContainer = Color(0xFF49463D),
    onSecondaryContainer = Color(0xFFE5E2D9)
)

@Composable
fun MvrViewerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
