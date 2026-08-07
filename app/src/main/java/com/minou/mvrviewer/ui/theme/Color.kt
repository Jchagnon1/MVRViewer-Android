package com.minou.mvrviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * IDENTITÉ jaune/gris/blanc/noir — mêmes valeurs que l'iOS (DESIGN_UI.md
 * §2 bis), décision utilisateur : « par petites touches, juste pour montrer
 * une identité, sans kitsch ».
 *
 * DEUX jetons jaunes, et c'est structurel — un seul ne peut pas être à la
 * fois ENCRE et REMPLISSAGE :
 * - un jaune franc est ILLISIBLE en texte sur fond clair (blanc sur #FFC400 =
 *   1,6:1) mais superbe en surface pleine sous un contenu NOIR (13,2:1) ;
 * - une encre lisible sur blanc est forcément un ocre sombre (#7A5600,
 *   6,7:1), qui ferait des surfaces brun-moutarde.
 * En mode sombre, un seul jaune (#FFD60A) sert les deux rôles : il est
 * lisible en encre sur fond sombre (12:1) ET porte du noir en surface.
 *
 * Le jaune est l'IDENTITÉ, jamais une couleur sémantique : le rouge de
 * conflit DMX, les couleurs de calque MVR, les couleurs ACI d'un DXF et
 * l'équilibrage de phases ne passent pas par ici.
 */
object Accent {
    /** Surface PLEINE (action principale, outil actif). Contenu NOIR dessus. */
    val fillLight = Color(0xFFFFC400)
    val fillDark = Color(0xFFFFD60A)

    /** ENCRE : texte ou glyphe d'accent posé sur une surface. */
    val inkLight = Color(0xFF7A5600)
    val inkDark = Color(0xFFFFD60A)

    /** Ce qu'on pose SUR une surface `fill` — noir dans les deux modes. */
    val on = Color(0xFF000000)
}

/** Le remplissage d'identité du mode courant. */
@Composable
fun accentFill(): Color = if (isSystemInDarkTheme()) Accent.fillDark else Accent.fillLight

/**
 * Couleurs d'un bouton d'ACTION PRINCIPALE : jaune franc, contenu noir.
 * `colorScheme.primary` est volontairement l'ENCRE (lisible en TextButton,
 * en icône teintée, en piste de Switch) — les surfaces d'identité, rares,
 * demandent explicitement ce jeu de couleurs. Même architecture qu'iOS :
 * l'asset global porte l'encre, les remplissages sont explicites.
 */
@Composable
fun accentButtonColors() = ButtonDefaults.buttonColors(
    containerColor = accentFill(),
    contentColor = Accent.on
)

/** Couleurs d'un bouton d'outil À BASCULE : actif = jaune plein, glyphe NOIR
 *  (jamais blanc sur jaune — 1,6:1, illisible). */
@Composable
fun accentToggleColors() = IconButtonDefaults.filledIconToggleButtonColors(
    checkedContainerColor = accentFill(),
    checkedContentColor = Accent.on
)
