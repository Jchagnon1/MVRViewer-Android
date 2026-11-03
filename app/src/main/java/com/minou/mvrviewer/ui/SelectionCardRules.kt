package com.minou.mvrviewer.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.DmxAddress
import com.minou.mvrviewer.sync.PowerResolution
import com.minou.mvrviewer.sync.PowerSource

// =============================================================================
// FICHE DE SÉLECTION ET BANNIÈRE MULTI — règles PURES (SPEC-menus-android §3.9,
// tranche D ; iOS SPEC-menus §8, `SelectionCardLayout`), testées en JVM. UNE
// fiche et UNE bannière, construites par SceneScreen et posées par la 3D ET le
// plan : ce fichier décide de ce qu'elles disent et de leur forme ; le rendu est
// dans SelectionCard.kt.
// =============================================================================

/** Texte d'une ligne de la fiche : ressource simple ou pluriel, avec ses arguments. */
sealed interface CardText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : CardText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any>) : CardText
}

/**
 * Ligne « Conso » de la fiche (§3.9, 3) : [label] = « 450 W · 3 votes » /
 * « 450 W · GDTF », `null` = rien de connu (« Conso max : à saisir »). [spec] =
 * type sur lequel porte le vote.
 */
data class CardPower(val label: CardText?, val watts: Int?, val spec: String?) {
    /** « C'est bon » : seulement avec une valeur connue ET un type (le vote est par type). */
    val canConfirm: Boolean get() = watts != null && !spec.isNullOrBlank()
}

/** Contenu de la fiche d'UN projecteur. */
data class SelectionCardModel(
    /** Clé d'instance : « Détails » se replie à chaque nouvelle sélection. */
    val key: String,
    /** Nom effectif (renommage du patch compris) : la fin du titre, et le titre court. */
    val name: String,
    /** Calque, N° de projecteur, adresse DMX (localisés, une ligne chacun). */
    val details: List<CardText>,
    val power: CardPower
)

object SelectionCardModels {
    /** Séparateur du titre « Type — Nom » (iOS `selectionInfo`) ; le titre court le retire. */
    const val TITLE_SEPARATOR = " — "

    /**
     * Fiche d'un projecteur. Le titre « Projecteur — Nom » est assemblé au rendu
     * (`card_title_fmt`, `card_kind_fixture`) : seul le nom varie. Détails dans
     * l'ordre iOS (`MVRSceneObject.selectionInfo`) : calque, N°, adresses jointes
     * par « , » au format univers.adresse.
     */
    fun of(
        key: String,
        name: String,
        layer: String,
        fixtureId: String?,
        addresses: List<String>,
        spec: String?,
        resolution: PowerResolution,
        votes: Int?
    ): SelectionCardModel {
        val details = buildList {
            add(CardText.Res(R.string.card_layer_fmt, listOf(layer)))
            fixtureId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(CardText.Res(R.string.card_fixture_number_fmt, listOf(it))) }
            val formatted = addresses.map { it.trim() }.filter { it.isNotEmpty() }.map { DmxAddress.format(it) }
            if (formatted.isNotEmpty()) add(CardText.Res(R.string.card_dmx_fmt, listOf(formatted.joinToString(", "))))
        }
        return SelectionCardModel(
            key = key, name = name, details = details,
            power = CardPower(powerLabel(resolution, votes), resolution.watts, spec?.takeIf { it.isNotBlank() })
        )
    }

    /**
     * « valeur · confiance » — UN libellé pour la fiche (3D et plan) et l'écran
     * Câblage (iOS `ResolvedPower.confidenceLabel`) : « 450 W · 3 votes » (pluriel
     * porté par le NOMBRE DE VOTES), « 450 W · GDTF », `null` sans valeur.
     */
    fun powerLabel(resolution: PowerResolution, votes: Int?): CardText? {
        val w = resolution.watts ?: return null
        return when (resolution.source) {
            PowerSource.LIBRARY -> {
                val n = votes ?: 1
                CardText.Plural(R.plurals.power_watts_votes, n, listOf(w, n))
            }
            PowerSource.GDTF -> CardText.Res(R.string.power_watts_gdtf_fmt, listOf(w))
            PowerSource.NONE -> null
        }
    }

    /**
     * Adresses montrées : l'adresse ÉDITÉE dans le patch si elle existe (le patch
     * n'en édite qu'une), sinon toutes celles du fichier.
     */
    fun addresses(fileAddresses: List<String>, effectiveFirst: String?): List<String> = when {
        effectiveFirst == null -> fileAddresses
        effectiveFirst == fileAddresses.firstOrNull() -> fileAddresses
        else -> listOf(effectiveFirst)
    }
}

/** Ce qui s'affiche en bas pour la sélection (les deux vues). */
enum class PlanSelectionBar { NONE, CARD, BANNER }

/** Forme de la fiche (iOS `SelectionCardLayout`, SPEC-menus §8.4). */
object SelectionCardLayout {
    enum class Style { REGULAR, COMPACT }

    /** Hauteur d'écran (dp) sous laquelle la fiche est COMPACTE : le téléphone en paysage. */
    const val COMPACT_BELOW_HEIGHT_DP = 480

    /** Largeur maximale de la fiche complète et de sa bannière (dp). */
    const val REGULAR_MAX_WIDTH_DP = 520f

    /** Part de la hauteur de l'écran que la fiche compacte ne dépasse pas (détails repliés). */
    const val COMPACT_HEIGHT_BUDGET = 0.25f

    /**
     * Fiche COMPACTE sous 480 dp de haut (le seuil déjà utilisé par le plan pour
     * ses panneaux) ; portrait et tablette gardent la fiche complète. Une hauteur
     * inconnue (≤ 0) ne change rien.
     */
    fun style(screenHeightDp: Int): Style =
        if (screenHeightDp in 1 until COMPACT_BELOW_HEIGHT_DP) Style.COMPACT else Style.REGULAR

    fun fitsHeightBudget(cardHeight: Float, screenHeight: Float): Boolean {
        if (!(screenHeight > 0f) || !cardHeight.isFinite()) return false
        return cardHeight <= screenHeight * COMPACT_HEIGHT_BUDGET + 0.5f
    }

    /**
     * Titre COURT de la fiche compacte : le nom, sans le genre d'objet
     * (« Projecteur — Wash 101 » → « Wash 101 »). TalkBack lit le titre complet.
     */
    fun shortTitle(title: String): String {
        val i = title.indexOf(SelectionCardModels.TITLE_SEPARATOR)
        if (i < 0) return title
        val rest = title.substring(i + SelectionCardModels.TITLE_SEPARATOR.length).trim()
        return rest.ifEmpty { title }
    }

    /**
     * La vue se décale-t-elle pour garder visible le projecteur choisi sous la
     * fiche ? Seulement pour la fiche COMPACTE d'un projecteur unique (la bannière
     * multi n'a pas « un » projecteur à montrer).
     */
    fun revealsSelection(style: Style, isMultiSelect: Boolean, hasSelection: Boolean): Boolean =
        style == Style.COMPACT && !isMultiSelect && hasSelection

    /**
     * Ordonnée de la barre d'échelle du plan (coin bas-gauche) : à sa place
     * ([defaultY]), ou juste au-dessus de la fiche / bannière ([card], même repère)
     * quand celle-ci couvre sa colonne ([barLeft]…[barRight]) — la fiche pleine
     * largeur du portrait la cachait (constat émulateur, tranche D).
     */
    fun scaleBarY(defaultY: Float, barLeft: Float, barRight: Float, card: Rect?, gap: Float): Float {
        if (card == null || !(card.width > 0f) || !(card.height > 0f) || !card.top.isFinite()) return defaultY
        val sameColumn = card.right > barLeft && card.left < barRight
        if (!sameColumn) return defaultY
        return minOf(defaultY, card.top - gap)
    }

    /**
     * Fiche (1 projecteur) ou bannière (plusieurs), dans les DEUX vues. Rien pendant
     * une mesure (la 3D le faisait déjà ; le bandeau de mesure est en bas), pendant
     * l'aide « je suis ici » du plan, l'assistant 3 points ou une affectation : le
     * bas de l'écran leur appartient (iOS `selectionBarShown`). La sélection, elle,
     * est gardée et réapparaît ensuite.
     */
    fun presentation(
        selectedCount: Int,
        measuring: Boolean = false,
        threePointOpen: Boolean = false,
        assignOpen: Boolean = false,
        anchorAid: Boolean = false
    ): PlanSelectionBar = when {
        measuring || threePointOpen || assignOpen || anchorAid -> PlanSelectionBar.NONE
        selectedCount == 1 -> PlanSelectionBar.CARD
        selectedCount > 1 -> PlanSelectionBar.BANNER
        else -> PlanSelectionBar.NONE
    }
}

/** « Éditer » de l'inspecteur de l'univers DMX (§3.10, tranche D). */
object UniverseEditRules {
    /** Une entrée par projecteur listé (deux au même canal = deux boutons, sans ambiguïté). */
    data class Target(val fixtureKey: String, val label: String)

    /**
     * Projecteurs à éditer au canal inspecté : un par projecteur (une même instance
     * listée deux fois ne fait qu'un bouton), dans l'ordre de la liste. [label] =
     * ce que lit TalkBack (« Éditer le patch du projecteur %s ») : le N°, sinon le nom.
     */
    fun targets(list: List<DmxPatch>): List<Target> =
        list.distinctBy { it.fixtureKey }.map { p ->
            Target(p.fixtureKey, p.fixtureId?.trim()?.takeIf { it.isNotEmpty() } ?: p.name)
        }
}

/** Écart (dp) entre le projecteur choisi et le haut de la fiche qui l'aurait caché (iOS 28 pt). */
const val REVEAL_MARGIN_DP = 28f

/**
 * De combien décaler la vue VERTICALEMENT (≤ 0 : vers le haut) pour que le
 * projecteur choisi, s'il tombe SOUS la fiche (ou à moins de [margin] d'elle),
 * réapparaisse juste au-dessus d'elle — sans passer sous ce qui occupe le haut
 * ([topLimit] : bas de la pastille « Vue » et de la rangée du haut). Portage exact
 * de `PanelPlacementMath.revealShift` iOS (SPEC-menus §8.4). 0 si le projecteur
 * est déjà visible ou s'il n'y a pas de place au-dessus. Unités libres mais
 * communes (px racine dans l'app).
 */
fun revealShift(point: Offset, obstacle: Rect, topLimit: Float, margin: Float): Float {
    if (!point.x.isFinite() || !point.y.isFinite() ||
        !(obstacle.width > 0f) || !(obstacle.height > 0f) ||
        !obstacle.left.isFinite() || !obstacle.top.isFinite() ||
        !obstacle.right.isFinite() || !obstacle.bottom.isFinite()
    ) return 0f
    val grown = Rect(obstacle.left - margin, obstacle.top - margin, obstacle.right + margin, obstacle.bottom + margin)
    // Bornes incluses à gauche / en haut, exclues à droite / en bas (CGRect.contains).
    val hidden = point.x >= grown.left && point.x < grown.right && point.y >= grown.top && point.y < grown.bottom
    if (!hidden) return 0f
    val target = obstacle.top - margin
    // Pas de place entre le haut et la fiche : on ne bouge rien.
    if (topLimit.isFinite() && target < topLimit) return 0f
    return minOf(0f, target - point.y)
}
