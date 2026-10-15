package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect
import com.minou.mvrviewer.R
import com.minou.mvrviewer.sync.CablingAssignment
import com.minou.mvrviewer.sync.Distributor
import com.minou.mvrviewer.sync.DistributorKind
import com.minou.mvrviewer.sync.DmxCablingAssignment
import com.minou.mvrviewer.sync.DmxCablingCalc
import com.minou.mvrviewer.sync.DmxCablingDTO
import com.minou.mvrviewer.sync.DmxDistributor
import com.minou.mvrviewer.sync.PowerCablingCalc
import com.minou.mvrviewer.sync.PowerCablingDTO
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// =============================================================================
// CÂBLAGE SUR LE PLAN — voies, voie suivante, coloration, mémoire de l'écran
// =============================================================================
//
// Logique PURE (aucune dépendance à l'interface) du parcours « affecter sur le
// plan » de SPEC-cablage-plan.md (dépôt iOS). Portage À L'IDENTIQUE de
// `MVRViewer/Models/CablingPlanAssist.swift` (iOS, commit 85a3668), verrouillé
// par les vecteurs canoniques `port-vectors/cabling-plan/vectors.json`, qui
// sortent du vrai code iOS compilé (CablingPlanVectorsTest) :
// - une VOIE = un circuit électrique (Socapex / ligne solo) ou un départ DMX
//   (ligne simple / départ d'une multipaire) ;
// - l'ORDRE des voies (◀ ▶ de la barre d'affectation) ;
// - le TOUCHER (affecter / retirer / déplacer) et le CADRE, avec la garde
//   « voie disparue » (rien n'est écrit) ;
// - l'état affiché par la barre (libellé, charge, surcharge) et ses textes ;
// - la MÉMOIRE de l'écran Câblage (onglet, ligne en tête, voie à remettre sous
//   les yeux au retour du plan) ;
// - la place de la barre en bas du plan, et la coloration par famille.

/**
 * Onglets de l'écran Câblage. [raw] = valeurs iOS FIGÉES (`CablingScreenTab`),
 * [index] = position de l'onglet Android (0 « Électrique », 1 « DMX »,
 * 2 « Puissances »).
 */
enum class CablingScreenTab(val raw: String, val index: Int) {
    CABLING("cabling", 0), DMX("dmx", 1), POWERS("powers", 2);

    companion object {
        fun fromRaw(raw: String?): CablingScreenTab? = entries.firstOrNull { it.raw == raw }
        fun fromIndex(index: Int): CablingScreenTab = entries.firstOrNull { it.index == index } ?: CABLING

        /**
         * Onglet à l'ouverture de l'écran : celui imposé par l'entrée, sinon le
         * dernier retenu, sinon « Électrique » (iOS `CablingNavigation.opening`).
         */
        fun opening(requested: CablingScreenTab?, remembered: CablingScreenTab?): CablingScreenTab =
            requested ?: remembered ?: CABLING
    }
}

/**
 * Une VOIE de câblage : ce que la barre du plan montre et fait défiler.
 * - [kind]   : électrique (circuit, `SOCA`) ou DMX (départ) ;
 * - [distId] : id du distributeur (Socapex / ligne solo / ligne DMX) ;
 * - [index]  : circuit (électrique) ou départ (DMX), À PARTIR DE 1.
 */
data class CablingVoie(val kind: CablingAssignTarget.Kind, val distId: String, val index: Int) {
    /** Identifiant de LIGNE dans l'écran Câblage : « power#<dist>#<circuit> » ou « dmx#<ligne>#<départ> ». */
    val rowId: String get() = rowId(kind, distId, index)

    /** Onglet de l'écran Câblage qui montre cette voie. */
    val tab: CablingScreenTab
        get() = if (kind == CablingAssignTarget.Kind.SOCA) CablingScreenTab.CABLING else CablingScreenTab.DMX

    companion object {
        fun rowId(kind: CablingAssignTarget.Kind, distId: String, index: Int): String =
            (if (kind == CablingAssignTarget.Kind.SOCA) "power#" else "dmx#") + distId + "#" + index
    }
}

// MARK: - Ordre des voies (◀ ▶) -------------------------------------------------

object CablingVoieNavigator {
    /**
     * Voies ÉLECTRIQUES dans l'ordre de parcours : distributeurs dans l'ordre du
     * modèle (celui de l'écran Câblage), puis circuits 1 → n PAR NUMÉRO (même si
     * l'écran les groupe par phase). Après le C6 d'une Socapex vient le C1 du
     * distributeur suivant ; une ligne solo compte une voie.
     */
    fun voies(power: PowerCablingDTO): List<CablingVoie> = power.distributors.flatMap { d ->
        (1..max(1, d.circuitCount)).map { CablingVoie(CablingAssignTarget.Kind.SOCA, d.id, it) }
    }

    /** Voies DMX : lignes dans l'ordre du modèle, puis départs 1 → n. */
    fun voies(dmx: DmxCablingDTO): List<CablingVoie> = dmx.distributors.flatMap { d ->
        (1..max(1, d.coreCount)).map { CablingVoie(CablingAssignTarget.Kind.DMX, d.id, it) }
    }

    /**
     * Voie voisine ([step] = +1 suivante, −1 précédente). `null` aux deux bouts
     * (PAS de bouclage : la flèche s'éteint) et quand la voie courante n'existe
     * plus (distributeur supprimé, index hors bornes, autre famille).
     */
    fun neighbor(voie: CablingVoie, step: Int, voies: List<CablingVoie>): CablingVoie? {
        val i = voies.indexOf(voie)
        if (i < 0) return null
        return voies.getOrNull(i + step)
    }
}

// MARK: - Libellés de voie (format FIGÉ, partagé avec les étiquettes) ------------

object CablingVoieLabel {
    /**
     * Nom « vide » au sens d'iOS : fait UNIQUEMENT d'espaces (catégorie Unicode
     * Zs, insécables comprises) ou de tabulations — `CharacterSet.whitespaces`,
     * SANS les retours à la ligne. (`isBlank` de Kotlin compte aussi « \n » :
     * ce n'est pas la même règle.)
     */
    fun isBlankName(name: String): Boolean =
        name.all { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

    /**
     * « Soca A · C3 » — nom du distributeur (« Socapex » s'il est vide), puis
     * espace + point médian U+00B7 + espace, puis « C » + circuit (1-based). Un
     * nom non vide est gardé TEL QUEL (pas de rognage).
     */
    fun power(name: String, circuit: Int): String =
        (if (isBlankName(name)) "Socapex" else name) + " · C" + circuit

    /**
     * « DMX A » pour une ligne à UN départ, « DMX A · D2 » dès que la ligne en a
     * plusieurs (`coreCount > 1` — le critère est le nombre de départs, pas le
     * type) ; « DMX » si le nom est vide.
     */
    fun dmx(name: String, core: Int, coreCount: Int): String {
        val base = if (isBlankName(name)) "DMX" else name
        return if (coreCount > 1) "$base · D$core" else base
    }
}

// MARK: - Toucher / cadre (sémantique des données INCHANGÉE) --------------------

/** Issue d'un toucher en mode affectation. */
enum class CablingToggleResult {
    /** Le projecteur est désormais sur la voie courante (ajouté ou déplacé). */
    ASSIGNED,
    /** Il y était : il en est retiré. */
    REMOVED,
    /**
     * La voie n'existe plus (distributeur supprimé — ici ou par un coéquipier —,
     * ou index hors de 1…n) : RIEN n'est écrit. Sans cette garde, le toucher
     * retirait l'affectation VALIDE du projecteur et la remplaçait par une
     * orpheline, purgée ensuite partout : le projecteur se retrouvait décâblé.
     */
    VOIE_MISSING
}

object CablingPlanAssign {

    /** La voie électrique existe-t-elle encore ? (distributeur présent, circuit dans 1…n). */
    fun existsPower(voie: CablingVoie, distributors: List<Distributor>): Boolean {
        if (voie.kind != CablingAssignTarget.Kind.SOCA) return false
        val d = distributors.firstOrNull { it.id == voie.distId } ?: return false
        return voie.index >= 1 && voie.index <= d.circuitCount
    }

    /** Idem pour un départ DMX (départ dans 1…nombre de départs). */
    fun existsDmx(voie: CablingVoie, distributors: List<DmxDistributor>): Boolean {
        if (voie.kind != CablingAssignTarget.Kind.DMX) return false
        val d = distributors.firstOrNull { it.id == voie.distId } ?: return false
        return voie.index >= 1 && voie.index <= d.coreCount
    }

    fun exists(voie: CablingVoie, power: PowerCablingDTO): Boolean = existsPower(voie, power.distributors)
    fun exists(voie: CablingVoie, dmx: DmxCablingDTO): Boolean = existsDmx(voie, dmx.distributors)

    /**
     * TOUCHER un projecteur en mode affectation :
     * - voie disparue → RIEN ne change ([CablingToggleResult.VOIE_MISSING]) ;
     * - déjà sur la voie courante → RETIRÉ ;
     * - ailleurs ou non câblé → (DÉ)PLACÉ sur la voie courante, EN FIN de liste.
     * « Une alim → un circuit » : l'ancienne affectation disparaît toujours d'abord.
     */
    fun toggle(dto: PowerCablingDTO, fixture: String, voie: CablingVoie): Pair<PowerCablingDTO, CablingToggleResult> {
        if (!exists(voie, dto)) return dto to CablingToggleResult.VOIE_MISSING
        val current = dto.assignments.firstOrNull { it.fixture == fixture }
        val already = current != null && current.distributor == voie.distId && current.circuit == voie.index
        val kept = dto.assignments.filter { it.fixture != fixture }
        if (already) return dto.copy(assignments = kept) to CablingToggleResult.REMOVED
        return dto.copy(assignments = kept + CablingAssignment(fixture, voie.distId, voie.index)) to
            CablingToggleResult.ASSIGNED
    }

    /** Idem pour le DMX : « une entrée DMX → un départ ». */
    fun toggle(dto: DmxCablingDTO, fixture: String, voie: CablingVoie): Pair<DmxCablingDTO, CablingToggleResult> {
        if (!exists(voie, dto)) return dto to CablingToggleResult.VOIE_MISSING
        val current = dto.assignments.firstOrNull { it.fixture == fixture }
        val already = current != null && current.distributor == voie.distId && current.core == voie.index
        val kept = dto.assignments.filter { it.fixture != fixture }
        if (already) return dto.copy(assignments = kept) to CablingToggleResult.REMOVED
        return dto.copy(assignments = kept + DmxCablingAssignment(fixture, voie.distId, voie.index)) to
            CablingToggleResult.ASSIGNED
    }

    /**
     * CADRE : affecte (ajoute / déplace) tous les projecteurs encadrés sur la
     * voie, sans doublon, en les réécrivant EN FIN de liste — JAMAIS de retrait.
     * Voie disparue : rien n'est écrit, renvoie `null`.
     */
    fun assign(dto: PowerCablingDTO, fixtures: List<String>, voie: CablingVoie): PowerCablingDTO? {
        if (!exists(voie, dto)) return null
        val set = fixtures.toSet()
        val kept = dto.assignments.filter { it.fixture !in set }
        val added = LinkedHashSet(fixtures).map { CablingAssignment(it, voie.distId, voie.index) }
        return dto.copy(assignments = kept + added)
    }

    fun assign(dto: DmxCablingDTO, fixtures: List<String>, voie: CablingVoie): DmxCablingDTO? {
        if (!exists(voie, dto)) return null
        val set = fixtures.toSet()
        val kept = dto.assignments.filter { it.fixture !in set }
        val added = LinkedHashSet(fixtures).map { DmxCablingAssignment(it, voie.distId, voie.index) }
        return dto.copy(assignments = kept + added)
    }

    /** Projecteurs (mvrUUID) actuellement sur une voie électrique. */
    fun fixturesOn(voie: CablingVoie, dto: PowerCablingDTO): Set<String> =
        dto.assignments.filter { it.distributor == voie.distId && it.circuit == voie.index }
            .mapTo(LinkedHashSet()) { it.fixture }

    /** Projecteurs (mvrUUID) actuellement sur un départ DMX. */
    fun fixturesOn(voie: CablingVoie, dto: DmxCablingDTO): Set<String> =
        dto.assignments.filter { it.distributor == voie.distId && it.core == voie.index }
            .mapTo(LinkedHashSet()) { it.fixture }
}

// MARK: - État d'une voie, tel que la barre l'affiche ---------------------------

/**
 * Ce que montre la barre du plan pour la voie courante. Les sommes passent par
 * les MÊMES calculateurs que l'écran Câblage ([PowerCablingCalc], [DmxCablingCalc]).
 * `null` (fonctions de fabrique) = la voie n'existe plus.
 */
data class CablingVoieStatus(
    val voie: CablingVoie,
    /** « Soca A · C3 » / « DMX A · D2 ». */
    val label: String,
    /** Couleur du distributeur (ARGB). */
    val colorArgb: Int,
    /** Phase L1/L2/L3 du circuit d'une Socapex ; `null` pour une ligne solo ou le DMX. */
    val phase: Int?,
    val fixtureCount: Int,
    /** Charge (W) ou empreinte (canaux) ; `null` tant que les valeurs ne sont pas résolues. */
    val load: Int?,
    /** Limite de circuit (W, réglage) ou 512 canaux. */
    val limit: Int,
    val isOver: Boolean,
    /** Projecteurs de la voie à conso / empreinte INCONNUE (comptés 0). */
    val unknownCount: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean
) {
    companion object {
        /** Voie ÉLECTRIQUE. [wattsByFixture] = conso RÉSOLUE (absent = inconnue) ; `null` = pas encore résolu. */
        fun power(voie: CablingVoie, dto: PowerCablingDTO, wattsByFixture: Map<String, Int>?): CablingVoieStatus? {
            if (voie.kind != CablingAssignTarget.Kind.SOCA) return null
            val d = dto.distributors.firstOrNull { it.id == voie.distId } ?: return null
            if (voie.index < 1 || voie.index > d.circuitCount) return null
            val on = CablingPlanAssign.fixturesOn(voie, dto)
            var load: Int? = null
            var unknown = 0
            if (wattsByFixture != null) {
                load = PowerCablingCalc.circuitLoad(d, voie.index, dto.assignments, dto.settings) {
                    wattsByFixture[it]
                }.totalW
                unknown = on.count { wattsByFixture[it] == null }
            }
            val order = CablingVoieNavigator.voies(dto)
            val limit = dto.settings.circuitLimitW
            return CablingVoieStatus(
                voie = voie,
                label = CablingVoieLabel.power(d.name, voie.index),
                colorArgb = d.colorArgb,
                phase = if (d.kind == DistributorKind.SOCA) d.phaseOf(voie.index) else null,
                fixtureCount = on.size,
                load = load,
                limit = limit,
                isOver = load != null && limit > 0 && load > limit,
                unknownCount = unknown,
                hasPrevious = CablingVoieNavigator.neighbor(voie, -1, order) != null,
                hasNext = CablingVoieNavigator.neighbor(voie, 1, order) != null
            )
        }

        /** Voie DMX. [footprintByFixture] = empreinte RÉSOLUE (absent = inconnue) ; `null` = pas encore résolu. */
        fun dmx(voie: CablingVoie, dto: DmxCablingDTO, footprintByFixture: Map<String, Int>?): CablingVoieStatus? {
            if (voie.kind != CablingAssignTarget.Kind.DMX) return null
            val d = dto.distributors.firstOrNull { it.id == voie.distId } ?: return null
            if (voie.index < 1 || voie.index > d.coreCount) return null
            val on = CablingPlanAssign.fixturesOn(voie, dto)
            var load: Int? = null
            var unknown = 0
            if (footprintByFixture != null) {
                load = DmxCablingCalc.coreLoad(d, voie.index, dto.assignments,
                    channelsOf = { footprintByFixture[it] }, universeOf = { null }).totalChannels
                unknown = on.count { footprintByFixture[it] == null }
            }
            val order = CablingVoieNavigator.voies(dto)
            return CablingVoieStatus(
                voie = voie,
                label = CablingVoieLabel.dmx(d.name, voie.index, d.coreCount),
                colorArgb = d.colorArgb,
                phase = null,
                fixtureCount = on.size,
                load = load,
                limit = DmxCablingCalc.UNIVERSE_CHANNELS,
                isOver = load != null && load > DmxCablingCalc.UNIVERSE_CHANNELS,
                unknownCount = unknown,
                hasPrevious = CablingVoieNavigator.neighbor(voie, -1, order) != null,
                hasNext = CablingVoieNavigator.neighbor(voie, 1, order) != null
            )
        }
    }
}

// MARK: - Textes de la barre (sélection PURE des ressources) --------------------

/** Texte à afficher : une chaîne ou un pluriel des ressources, avec ses arguments. */
sealed interface CablingText {
    data class Res(val id: Int, val args: List<Any> = emptyList()) : CablingText
    data class Plural(val id: Int, val count: Int, val args: List<Any>) : CablingText
}

/**
 * Contenu de la barre d'affectation (SPEC-cablage-plan §4), transcription de
 * `PlanView.assignPanel` iOS. [title] = libellé FIGÉ de la voie.
 */
data class CablingAssignBarDisplay(
    val title: String,
    val colorArgb: Int,
    /** La voie n'existe plus : « ⚠ Voie supprimée » (orange) au lieu de la charge. */
    val voieMissing: Boolean,
    /** « 2 900 / 3 680 W » ou « 37 / 512 canaux » ; `null` = rien (ou sablier si [loadPending]). */
    val load: CablingText?,
    /** Valeurs pas encore résolues : sablier. */
    val loadPending: Boolean,
    /** Surcharge : charge en ROUGE + triangle. */
    val overloadMark: Boolean,
    /** « ? » orange (conso / empreinte inconnue). */
    val unknownMark: CablingText?,
    /** « L2 » (circuit de Socapex). */
    val phaseText: String?,
    /** « 4 projecteurs » ou « Touchez pour affecter ». */
    val count: CablingText?,
    val previousEnabled: Boolean,
    val nextEnabled: Boolean,
    /** Bouton cadre actif (éteint si la voie a disparu). */
    val frameEnabled: Boolean
)

object CablingAssignBarUi {
    /** Couleur « non câblé » du plan (SPEC §3) : gris 0xFF737373. */
    const val UNCABLED_ARGB: Int = 0xFF737373.toInt()

    /** Entier au format de la langue (« 3 800 » en français, « 3,800 » en anglais). */
    fun formatInt(n: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(n.toLong())

    /**
     * Contenu de la barre pour [target] dans l'état [status] (`null` = voie
     * disparue). Les nombres sont mis en forme dans [locale].
     */
    fun display(target: CablingAssignTarget, status: CablingVoieStatus?, locale: Locale): CablingAssignBarDisplay {
        val title = status?.label ?: target.label.ifEmpty {
            if (target.kind == CablingAssignTarget.Kind.SOCA) CablingVoieLabel.power("", target.index)
            else CablingVoieLabel.dmx("", target.index, 2)
        }
        if (status == null) {
            return CablingAssignBarDisplay(
                title = title, colorArgb = UNCABLED_ARGB, voieMissing = true,
                load = null, loadPending = false, overloadMark = false, unknownMark = null,
                phaseText = null, count = null,
                previousEnabled = false, nextEnabled = false, frameEnabled = false
            )
        }
        val isPower = status.voie.kind == CablingAssignTarget.Kind.SOCA
        val load = status.load?.let {
            val args = listOf<Any>(formatInt(it, locale), formatInt(status.limit, locale))
            CablingText.Res(if (isPower) R.string.cabling_bar_load_w else R.string.cabling_bar_load_channels, args)
        }
        val unknown = if (status.load != null && status.unknownCount > 0)
            CablingText.Res(if (isPower) R.string.cabling_unknown_power_cd else R.string.dmx_unknown_footprint_cd)
        else null
        val count = if (status.fixtureCount == 0) CablingText.Res(R.string.cabling_tap_to_assign)
            else CablingText.Plural(R.plurals.cabling_bar_fixtures, status.fixtureCount, listOf(status.fixtureCount))
        return CablingAssignBarDisplay(
            title = title,
            colorArgb = status.colorArgb,
            voieMissing = false,
            load = load,
            loadPending = status.load == null,
            overloadMark = status.load != null && status.isOver,
            unknownMark = unknown,
            phaseText = status.phase?.let { "L$it" },
            count = count,
            previousEnabled = status.hasPrevious,
            nextEnabled = status.hasNext,
            frameEnabled = true
        )
    }
}

// MARK: - Mémoire de l'écran Câblage -----------------------------------------

/** Identifiants des lignes REPÈRES d'une liste du câblage (toute ligne qui peut être en tête). */
object CablingListRowId {
    const val SUMMARY = "summary"
    const val SUMMARY_UNKNOWN = "summary#unknown"
    fun header(distId: String): String = "hdr#$distId"
    fun isHeader(id: String): Boolean = id.startsWith("hdr#")
    fun phase(distId: String, phase: Int): String = "phase#$distId#$phase"
}

/** Position retenue pour une liste : tout en haut, ou une ligne repère en tête. */
sealed interface CablingListPosition {
    /** Liste en haut (décalage nul) : on la rouvre telle quelle, SANS défiler. */
    data object ListTop : CablingListPosition
    /** Cette ligne repère était en tête : on la remet en haut. */
    data class Row(val id: String) : CablingListPosition
}

/**
 * Consigne de défilement à l'apparition d'une liste du câblage. [highlight] =
 * voie de retour du plan : mise sous les yeux et brièvement surlignée ; sinon
 * simple reprise de la ligne en tête (ancrée en haut).
 */
data class CablingScrollRestore(val rowId: String, val highlight: Boolean) {
    enum class Anchor { TOP, CENTER }

    /**
     * Ancrage FINAL, sur la hauteur RÉELLE de la ligne et la hauteur RÉELLEMENT
     * visible de la liste (mesurées après un premier défilement ancré en haut) :
     * la voie de retour est CENTRÉE si elle tient (hauteur + 24 ≤ zone visible),
     * sinon elle reste EN HAUT, titre visible. Hauteurs inconnues ou nulles, ou
     * simple reprise : en haut.
     */
    fun settleAnchor(rowHeight: Float?, visibleHeight: Float?): Anchor {
        if (!highlight || rowHeight == null || visibleHeight == null || rowHeight <= 0f || visibleHeight <= 0f) {
            return Anchor.TOP
        }
        return if (rowHeight + CENTER_MARGIN <= visibleHeight) Anchor.CENTER else Anchor.TOP
    }

    companion object {
        /** Marge sous laquelle une ligne « tient » à l'écran (dp). */
        const val CENTER_MARGIN = 24f
    }
}

/**
 * Où l'écran Câblage doit se rouvrir. L'écran est refermé à chaque passage par
 * le plan : sa mémoire vit AU-DESSUS de lui (SceneScreen), le temps de la
 * session du projet. Objet MUTABLE et NON observé à dessein : il est écrit à
 * chaque défilement, et n'est relu qu'à l'apparition d'une liste.
 * - [tab] : le dernier onglet ouvert ;
 * - [positionByTab] : la position de chaque onglet (en haut, ou la ligne en tête) ;
 * - [pendingFocus] : la voie travaillée sur le plan, à remettre sous les yeux et
 *   à mettre en évidence UNE fois au retour.
 */
class CablingScreenMemoryState(
    var tab: CablingScreenTab = CablingScreenTab.CABLING,
    val positionByTab: MutableMap<CablingScreenTab, CablingListPosition> = mutableMapOf(),
    var pendingFocus: CablingVoie? = null
) {
    /** Une voie est travaillée sur le plan (entrée, ◀, ▶) : c'est la DERNIÈRE voie active. */
    fun rememberWorkedVoie(voie: CablingVoie) {
        tab = voie.tab
        positionByTab[voie.tab] = CablingListPosition.Row(voie.rowId)
        pendingFocus = voie
    }

    /** Position d'un onglet. Une ligne vide ne remplace jamais une position connue. */
    fun remember(position: CablingListPosition, tab: CablingScreenTab) {
        if (position is CablingListPosition.Row && position.id.isEmpty()) return
        positionByTab[tab] = position
    }

    /**
     * Où aller quand la liste de [tab] apparaît. La voie travaillée prime (mise
     * en évidence) et n'est rendue qu'UNE fois ; sinon la ligne qui était en
     * tête (sans mise en évidence). Liste laissée en haut, ou jamais vue : `null`.
     */
    fun takeRestore(tab: CablingScreenTab): CablingScrollRestore? {
        val f = pendingFocus
        if (f != null && f.tab == tab) {
            pendingFocus = null
            return CablingScrollRestore(f.rowId, highlight = true)
        }
        val p = positionByTab[tab]
        if (p is CablingListPosition.Row) return CablingScrollRestore(p.id, highlight = false)
        return null
    }
}

/** Mesure d'une ligne repère (position qui change au défilement). */
data class CablingRowFrame(val minY: Float, val maxY: Float, val layoutWidth: Float) {
    val height: Float get() = maxY - minY
}

/** Zone visible d'une liste (même repère que les lignes). */
data class CablingScrollMetrics(val visibleMinY: Float, val visibleMaxY: Float, val isAtListTop: Boolean) {
    val visibleHeight: Float get() = visibleMaxY - visibleMinY
}

/**
 * Où en est une liste du câblage, d'après la VRAIE position de ses lignes
 * (portage de `CablingListGeometry` iOS). La ligne en tête est la plus haute
 * qui dépasse d'au moins 1 sous le bord haut de la zone visible et commence
 * au-dessus de son bas, parmi les mesures de la mise en page COURANTE : la
 * première mesure à une nouvelle largeur ouvre une nouvelle « époque » ; une
 * ligne pas re-mesurée depuis est périmée.
 */
class CablingListGeometry {
    private data class Entry(val frame: CablingRowFrame, val epoch: Int)

    private val entries = HashMap<String, Entry>()
    var layoutWidth: Float? = null
        private set
    private var epoch = 0
    var metrics: CablingScrollMetrics? = null
        private set

    fun report(id: String, frame: CablingRowFrame) {
        val w = layoutWidth
        if (w != null && abs(frame.layoutWidth - w) >= 0.5f) epoch++
        layoutWidth = frame.layoutWidth
        entries[id] = Entry(frame, epoch)
    }

    fun remove(id: String) { entries.remove(id) }

    fun scrolled(m: CablingScrollMetrics) { metrics = m }

    /** Liste démontée (changement d'onglet, fermeture) : plus rien n'est sûr. */
    fun reset() {
        entries.clear()
        layoutWidth = null
        epoch++
        metrics = null
    }

    /** Mesure à jour d'une ligne (prise dans la mise en page courante). */
    fun current(id: String): CablingRowFrame? {
        val e = entries[id] ?: return null
        return if (e.epoch == epoch) e.frame else null
    }

    /** Ligne repère EN TÊTE ; `null` si rien n'est affiché. Égalités : le plus petit id. */
    fun topRow(): String? {
        val m = metrics ?: return null
        if (m.visibleMaxY <= m.visibleMinY) return null
        var bestId: String? = null
        var bestMinY = 0f
        for (id in entries.keys) {
            val f = current(id) ?: continue
            if (!(f.maxY > m.visibleMinY + 1f && f.minY < m.visibleMaxY - 1f)) continue
            val b = bestId
            if (b != null && (f.minY > bestMinY || (f.minY == bestMinY && id >= b))) continue
            bestId = id
            bestMinY = f.minY
        }
        return bestId
    }

    /**
     * Position à retenir, modèle iOS : tout en haut si la liste l'est, sinon la
     * ligne en tête — un EN-TÊTE en tête vise la ligne repère juste dessous (une
     * `List` iOS ne défile pas jusqu'à un en-tête). `null` tant que rien n'est
     * affiché : une liste qui disparaît n'efface pas la dernière position.
     */
    fun position(): CablingListPosition? {
        val m = metrics ?: return null
        val top = topRow() ?: return null
        if (m.isAtListTop) return CablingListPosition.ListTop
        val header = if (CablingListRowId.isHeader(top)) current(top) else null
        if (header != null) {
            var nextId: String? = null
            var nextMinY = 0f
            for (id in entries.keys) {
                if (id == top) continue
                val f = current(id) ?: continue
                if (f.minY <= header.minY) continue
                val n = nextId
                if (n != null && (f.minY > nextMinY || (f.minY == nextMinY && id >= n))) continue
                nextId = id
                nextMinY = f.minY
            }
            if (nextId != null) return CablingListPosition.Row(nextId)
        }
        return CablingListPosition.Row(top)
    }

    /**
     * Variante PERMISE à Android (SPEC §7, vecteur `positionHeaderAllowed`) : une
     * `LazyColumn` défile jusqu'à n'importe quel élément, un en-tête en tête est
     * donc repris lui-même.
     */
    fun positionHeaderAllowed(): CablingListPosition? {
        val m = metrics ?: return null
        val top = topRow() ?: return null
        return if (m.isAtListTop) CablingListPosition.ListTop else CablingListPosition.Row(top)
    }
}

// MARK: - Lignes de la liste Câblage (ordre d'affichage) -----------------------

/** Une ligne de la liste de l'écran Câblage : en-tête, phase ou voie. */
sealed class CablingListRow(val id: String) {
    class Header(val distId: String) : CablingListRow(CablingListRowId.header(distId))
    class Phase(val distId: String, val phase: Int) : CablingListRow(CablingListRowId.phase(distId, phase))
    /** [last] = dernière ligne du distributeur (bas de sa carte). */
    class Voie(val voie: CablingVoie, val last: Boolean) : CablingListRow(voie.rowId)
}

object CablingListRows {
    /**
     * Onglet « Électrique » : pour chaque distributeur, l'en-tête, puis chaque
     * phase utilisée (L1, L2, L3) suivie de ses circuits — l'écran GROUPE par
     * phase (phases [3,2,1,3,2,1] → circuits 3, 6, 2, 5, 1, 4), alors que ◀ ▶
     * suivent les numéros.
     */
    fun power(distributors: List<Distributor>): List<CablingListRow> = buildList {
        for (d in distributors) {
            add(CablingListRow.Header(d.id))
            val n = max(1, d.circuitCount)
            val phases = (1..n).map { d.phaseOf(it) }.distinct().sorted()
            for (p in phases) {
                add(CablingListRow.Phase(d.id, p))
                for (c in 1..n) if (d.phaseOf(c) == p) {
                    add(CablingListRow.Voie(CablingVoie(CablingAssignTarget.Kind.SOCA, d.id, c), last = false))
                }
            }
            // La dernière ligne affichée ferme la carte du distributeur.
            val lastIdx = indexOfLast { it is CablingListRow.Voie }
            if (lastIdx >= 0) {
                val v = this[lastIdx] as CablingListRow.Voie
                this[lastIdx] = CablingListRow.Voie(v.voie, last = true)
            }
        }
    }

    /** Onglet « DMX » : pour chaque ligne, l'en-tête puis ses départs 1 → n. */
    fun dmx(distributors: List<DmxDistributor>): List<CablingListRow> = buildList {
        for (d in distributors) {
            add(CablingListRow.Header(d.id))
            val n = max(1, d.coreCount)
            for (c in 1..n) {
                add(CablingListRow.Voie(CablingVoie(CablingAssignTarget.Kind.DMX, d.id, c), last = c == n))
            }
        }
    }

    /** Index de la ligne [rowId] dans [rows], ou −1. */
    fun indexOf(rows: List<CablingListRow>, rowId: String): Int = rows.indexOfFirst { it.id == rowId }

    /**
     * Position d'une liste lue sur ses éléments VISIBLES ([visible] = (id, haut,
     * bas) dans le repère du viewport, la zone utile commençant à 0) : on
     * alimente le modèle de lignes iOS, variante « en-tête permis ».
     */
    fun positionOf(visible: List<Triple<String, Float, Float>>, visibleHeight: Float, isAtListTop: Boolean,
                   layoutWidth: Float): CablingListPosition? {
        val g = CablingListGeometry()
        for ((id, top, bottom) in visible) g.report(id, CablingRowFrame(top, bottom, layoutWidth))
        g.scrolled(CablingScrollMetrics(0f, visibleHeight, isAtListTop))
        return g.positionHeaderAllowed()
    }
}

/**
 * Actions d'une voie sur UNE ligne : libellés complets s'ils tiennent, sinon
 * courts (« Sur le plan », « Affecter », « Vider ») — `ViewThatFits` iOS.
 */
object CablingActionLabels {
    fun useCompact(fullWidth: Int, available: Int): Boolean = fullWidth > available
}

// MARK: - Place de la barre d'affectation en bas du plan -----------------------

/** Marges de la barre du bas : distance au bas du plan et décalage vers la droite. */
data class BottomBarInsets(val bottom: Float, val leadingShift: Float)

object CablingAssignBarPlacement {
    /** Portrait : au-dessus de la légende repliée et des outils du bas. */
    const val BOTTOM_REGULAR = 84f
    /** Hauteur compacte (téléphone en paysage) : contre le bas. */
    const val BOTTOM_COMPACT = 8f
    /** Écart avec la légende dépliée. */
    const val LEGEND_GAP = 10f
    /** Largeur de la barre (dp). */
    const val BAR_WIDTH = 300f

    /**
     * Portage de `PanelPlacementMath.assignBarInsets` iOS (unités : pt iOS = dp).
     * [container] = cadre du plan, [legend] = cadre de la légende dépliée (même
     * repère), `null` si elle est masquée. Si la légende touche la barre centrée :
     * en hauteur compacte, la barre glisse à droite si la place le permet (marge
     * droite 16) ; sinon elle remonte au-dessus de la légende. Jamais sous la
     * marge ordinaire.
     */
    fun assignBarInsets(compactHeight: Boolean, container: Rect, barWidth: Float, legend: Rect?): BottomBarInsets {
        val base = BottomBarInsets(if (compactHeight) BOTTOM_COMPACT else BOTTOM_REGULAR, 0f)
        if (legend == null || legend.width <= 0f || legend.height <= 0f ||
            container.width <= 0f || container.height <= 0f) return base
        val barMinX = container.center.x - barWidth / 2f
        val barMaxX = barMinX + barWidth
        // La légende part du bord gauche : seul son bord droit peut gêner.
        if (!(legend.right + LEGEND_GAP > barMinX && legend.left < barMaxX)) return base
        val shift = legend.right + LEGEND_GAP - barMinX
        if (compactHeight && barMaxX + shift <= container.right - 16f) {
            return BottomBarInsets(base.bottom, shift)
        }
        val raised = container.bottom - legend.top + LEGEND_GAP
        return BottomBarInsets(max(base.bottom, raised), 0f)
    }

    /** Place réservée en bas du plan par la barre (de son bord haut au bas du plan), bornée à 60 %. */
    fun reserve(planTop: Float, planBottom: Float, barTop: Float): Float {
        val h = planBottom - planTop
        if (h <= 0f) return 0f
        return (planBottom - barTop).coerceIn(0f, h * 0.6f)
    }

    /**
     * Cadrage AU-DESSUS de la barre (premier affichage) : (échelle, décalage
     * vertical) à appliquer au cadrage de base du plan (contenu centré, ajusté à
     * 90 % du canevas). Le contenu se centre dans la bande libre et n'est jamais
     * agrandi au-delà du cadrage normal.
     */
    fun fitAbove(canvasW: Float, canvasH: Float, spanX: Float, spanY: Float, reserve: Float): Pair<Float, Float> {
        if (canvasW <= 0f || canvasH <= 0f || spanX <= 0f || spanY <= 0f || reserve <= 0f) return 1f to 0f
        val r = min(reserve, canvasH * 0.6f)
        val base = min(canvasW / spanX, canvasH / spanY)
        val band = min(canvasW / spanX, (canvasH - r) / spanY)
        return min(1f, band / base) to -r / 2f
    }
}

// MARK: - Coloration par câblage (mode affectation) ----------------------------

/** Une entrée de légende de câblage : un distributeur et sa couleur. */
data class CablingLegendEntry(val id: String, val name: String, val argb: Int)

/**
 * Résolution PURE des couleurs et libellés de câblage par projecteur (mvrUUID).
 * Un projecteur non câblé (ou câblé sur un distributeur inconnu) est ABSENT des
 * tables ; le plan le dessine en gris [CablingAssignBarUi.UNCABLED_ARGB].
 */
object CablingColorResolver {
    fun argbByFixture(power: PowerCablingDTO): Map<String, Int> {
        val byId = power.distributors.associate { it.id to it.colorArgb }
        val out = LinkedHashMap<String, Int>()
        for (a in power.assignments) byId[a.distributor]?.let { out[a.fixture] = it }
        return out
    }

    fun argbByFixture(dmx: DmxCablingDTO): Map<String, Int> {
        val byId = dmx.distributors.associate { it.id to it.colorArgb }
        val out = LinkedHashMap<String, Int>()
        for (a in dmx.assignments) byId[a.distributor]?.let { out[a.fixture] = it }
        return out
    }

    /** Étiquette « Socapex » : « Soca A · C3 ». */
    fun labelByFixture(power: PowerCablingDTO): Map<String, String> {
        val byId = power.distributors.associateBy { it.id }
        val out = LinkedHashMap<String, String>()
        for (a in power.assignments) byId[a.distributor]?.let { out[a.fixture] = CablingVoieLabel.power(it.name, a.circuit) }
        return out
    }

    /** Étiquette « Ligne DMX » : « Multi A · D2 », « DMX B ». */
    fun labelByFixture(dmx: DmxCablingDTO): Map<String, String> {
        val byId = dmx.distributors.associateBy { it.id }
        val out = LinkedHashMap<String, String>()
        for (a in dmx.assignments) byId[a.distributor]?.let {
            out[a.fixture] = CablingVoieLabel.dmx(it.name, a.core, it.coreCount)
        }
        return out
    }

    /** Légende : les distributeurs ayant au moins une affectation, dans l'ordre du modèle. */
    fun legend(power: PowerCablingDTO): List<CablingLegendEntry> {
        val used = power.assignments.mapTo(HashSet()) { it.distributor }
        return power.distributors.filter { it.id in used }.map { CablingLegendEntry(it.id, it.name, it.colorArgb) }
    }

    fun legend(dmx: DmxCablingDTO): List<CablingLegendEntry> {
        val used = dmx.assignments.mapTo(HashSet()) { it.distributor }
        return dmx.distributors.filter { it.id in used }.map { CablingLegendEntry(it.id, it.name, it.colorArgb) }
    }

    /** Couleur d'un projecteur sur le plan : celle de son distributeur, sinon gris « non câblé ». */
    fun planArgb(byFixture: Map<String, Int>, fixture: String): Int =
        byFixture[fixture] ?: CablingAssignBarUi.UNCABLED_ARGB
}

/**
 * Mode de coloration EFFECTIF du plan (SPEC §3) : pendant une affectation, il
 * est FORCÉ par la famille de la voie (Socapex pour un circuit, Ligne DMX pour un
 * départ), quel que soit le choix du menu — et sans anneau de 2e dimension.
 * Hors affectation, le choix du menu, retombant sur le calque si son câblage
 * est vide (règle historique d'Android, alignée iOS).
 */
object CablingAssignColoring {
    fun effectiveMode(menu: PlanColorMode, assign: CablingAssignTarget.Kind?,
                      hasSoca: Boolean, hasDmx: Boolean): PlanColorMode = when (assign) {
        CablingAssignTarget.Kind.SOCA -> PlanColorMode.SOCAPEX
        CablingAssignTarget.Kind.DMX -> PlanColorMode.DMX_LINE
        null -> when (menu) {
            PlanColorMode.SOCAPEX -> if (hasSoca) PlanColorMode.SOCAPEX else PlanColorMode.LAYER
            PlanColorMode.DMX_LINE -> if (hasDmx) PlanColorMode.DMX_LINE else PlanColorMode.LAYER
            PlanColorMode.LAYER -> PlanColorMode.LAYER
        }
    }

    /** L'anneau de 2e dimension n'est dessiné QUE hors affectation (une seule lecture). */
    fun showsSecondRing(mode: PlanColorMode, assigning: Boolean): Boolean =
        !assigning && mode != PlanColorMode.LAYER
}
