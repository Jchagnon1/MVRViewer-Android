package com.minou.mvrviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GeoCalibration
import com.minou.mvrviewer.mvr.IgnImagery
import com.minou.mvrviewer.mvr.ImageryDate
import com.minou.mvrviewer.mvr.SatelliteErrorKind
import com.minou.mvrviewer.mvr.SatelliteResult
import com.minou.mvrviewer.mvr.ignCovers

/**
 * État du fond satellite vu par l'interface (SPEC-satellite-ign.md §5.1) :
 * `idle | loading | ready | outOfCoverage | unavailable | error`. Hissé dans
 * SceneScreen comme l'image elle-même, partagé plan + 3D.
 */
sealed interface SatelliteStatus {
    data object Idle : SatelliteStatus
    data object Loading : SatelliteStatus
    data object Ready : SatelliteStatus
    /** 1re ancre hors des emprises IGN : aucun fond, aucune requête. */
    data object OutOfCoverage : SatelliteStatus
    /** L'IGN n'a pas d'imagerie ici (image blanche). */
    data object Unavailable : SatelliteStatus
    data class Failed(val kind: SatelliteErrorKind, val status: Int? = null) : SatelliteStatus
}

/**
 * Ligne « Fond satellite » du menu : interrupteur [checked] / [enabled], message
 * d'état ([message] = ressource + arguments ; null = rien) et curseur d'opacité.
 */
data class SatelliteMenuRow(
    val checked: Boolean,
    val enabled: Boolean,
    val message: Pair<Int, List<Any>>?,
    val opacity: Boolean
)

/** Issue d'un passage du chargement : image à afficher, statut, Toast éventuel. */
data class SatelliteRefresh<out T>(val overlay: T?, val status: SatelliteStatus, val toast: SatelliteStatus?)

/**
 * Textes du fond satellite (SPEC-satellite-ign.md §4) — sélection PURE de la
 * ressource et de ses arguments, testée en JVM sur les vrais `strings.xml`.
 */
object SatelliteUi {
    /**
     * Mention obligatoire (Licence Ouverte 2.0, §4.1) selon la date connue : « prise de vue
     * 2024 », « prises de vue 2021–2024 » (image à cheval sur deux campagnes), « mise à
     * jour du 22/10/2026 » (anglais « updated 2026-10-22 »), sinon « (cartes.gouv.fr) ».
     * Années et date passées en TEXTE (chiffres ASCII, jamais groupés « 2 024 ») ; jour et
     * mois sur 2 chiffres ; une date mal formée → mention sans date (jamais inventée).
     */
    fun attributionRes(date: ImageryDate?): Pair<Int, List<Any>> = when (date) {
        is ImageryDate.Capture -> {
            val lo = minOf(date.from, date.to); val hi = maxOf(date.from, date.to)
            if (lo < 1000 || hi > 9999) R.string.sat_attribution to emptyList()
            else if (lo == hi) R.string.sat_attribution_year to listOf(lo.toString())
            else R.string.sat_attribution_years to listOf(lo.toString(), hi.toString())
        }
        is ImageryDate.Updated ->
            if (date.year !in 1000..9999 || date.month !in 1..12 || date.day !in 1..31) R.string.sat_attribution to emptyList()
            else R.string.sat_attribution_updated to listOf(two(date.day), two(date.month), date.year.toString())
        null -> R.string.sat_attribution to emptyList()
    }

    private fun two(v: Int): String = String.format(java.util.Locale.ROOT, "%02d", v)

    /** Message de la ligne d'état / du Toast ; null = rien à dire (prêt, inactif). */
    fun messageRes(status: SatelliteStatus): Pair<Int, List<Any>>? = when (status) {
        SatelliteStatus.Idle, SatelliteStatus.Ready -> null
        SatelliteStatus.Loading -> R.string.sat_loading to emptyList()
        SatelliteStatus.OutOfCoverage -> R.string.sat_out_of_coverage to emptyList()
        SatelliteStatus.Unavailable -> R.string.sat_unavailable to emptyList()
        is SatelliteStatus.Failed -> when (status.kind) {
            SatelliteErrorKind.TIMEOUT -> R.string.sat_error_timeout to emptyList()
            SatelliteErrorKind.NETWORK -> R.string.sat_error_network to emptyList()
            SatelliteErrorKind.HTTP -> R.string.sat_error_http to listOf(status.status ?: 0)
            SatelliteErrorKind.DECODE -> R.string.sat_error_decode to emptyList()
        }
    }

    /** Statut correspondant au résultat d'un chargement. */
    fun statusOf(result: SatelliteResult<*>): SatelliteStatus = when (result) {
        is SatelliteResult.Ok -> SatelliteStatus.Ready
        SatelliteResult.OutOfCoverage -> SatelliteStatus.OutOfCoverage
        SatelliteResult.NoImagery -> SatelliteStatus.Unavailable
        is SatelliteResult.Error -> SatelliteStatus.Failed(result.kind, result.status)
    }

    /**
     * MENTION à poser (écran plan, écran 3D, page plan des PDF) : celle de
     * [attributionRes] dès que l'image IGN est DESSINÉE ([show] et [imageDrawn]),
     * null sinon. Règle UNIQUE : l'image ne part jamais sans sa mention.
     */
    fun creditRes(show: Boolean, imageDrawn: Boolean, date: ImageryDate?): Pair<Int, List<Any>>? =
        if (show && imageDrawn) attributionRes(date) else null

    /**
     * PDF sans date connue (§7.5, relecture) : Android ne sait pas poser de lien dans un PDF
     * (`PdfDocument`) — l'adresse de la fiche IGN est alors ÉCRITE en clair sous la pastille
     * (texte réel du PDF, que les lecteurs rendent cliquable). Null : une date est connue
     * (la mention la porte), ou aucune image n'est dessinée.
     */
    fun pdfSourceUrl(show: Boolean, imageDrawn: Boolean, date: ImageryDate?): String? =
        if (show && imageDrawn && attributionRes(date).first == R.string.sat_attribution) IgnImagery.DATASET_URL else null

    /** 1re ancre de la calibration dans les emprises IGN (France) — pré-filtre sans réseau. */
    fun covered(calib: GeoCalibration): Boolean =
        calib.anchors.firstOrNull()?.let { ignCovers(it.latitude, it.longitude) } == true

    /**
     * Appui sur l'OUTIL « Fond satellite » (plan, 3D) : en France, bascule par geste
     * ([toggle]) et rien à annoncer ; hors couverture, PAS de bascule (le réglage
     * mémorisé est gardé pour un retour en France) et le statut à annoncer (Toast).
     */
    fun onToolTap(covered: Boolean, toggle: () -> Unit): SatelliteStatus? =
        if (covered) { toggle(); null } else SatelliteStatus.OutOfCoverage

    /**
     * Ligne « Fond satellite » du menu (plan, 3D). Hors couverture : interrupteur
     * VISIBLE mais grisé, jamais coché, et le message le dit TOUJOURS (même réglage
     * éteint : c'est lui qui explique le grisé), sans curseur d'opacité.
     */
    fun menuRow(covered: Boolean, show: Boolean, status: SatelliteStatus): SatelliteMenuRow =
        if (!covered) SatelliteMenuRow(
            checked = false, enabled = false,
            message = R.string.sat_out_of_coverage to emptyList(), opacity = false
        )
        else SatelliteMenuRow(checked = show, enabled = true, message = messageRes(status), opacity = show)

    /**
     * UN passage du chargement du fond (LaunchedEffect de SceneScreen), PUR et testé
     * en JVM. [current] = image affichée (clé [currentKey]), [currentStatus] = statut.
     * [content] calcule le contenu à couvrir (null = rien à couvrir), [keyOf] sa clé,
     * [load] le télécharge (argument = forcer). [userAsked] : une activation par
     * GESTE n'est pas encore traitée → demande forcée et Toast si elle n'aboutit
     * pas ; une valeur restaurée ou reçue du partage reste silencieuse.
     *  - réglage éteint ou pas de calibration → aucune image, statut inactif ;
     *  - hors couverture → AUCUNE requête, image retirée (autre lieu) ;
     *  - même contenu que l'image affichée → prête, sans réseau ;
     *  - sinon [onLoading], puis : image reçue → affichée ; pas d'imagerie → retirée ;
     *    échec réseau/serveur → l'image courante reste.
     */
    suspend fun <C : Any, T : Any> refresh(
        current: T?, currentKey: String?, currentStatus: SatelliteStatus,
        show: Boolean, calibrated: Boolean, covered: Boolean, userAsked: Boolean,
        content: () -> C?, keyOf: (C) -> String,
        load: suspend (content: C, force: Boolean) -> SatelliteResult<T>,
        onLoading: () -> Unit = {}
    ): SatelliteRefresh<T> {
        if (!show || !calibrated) return SatelliteRefresh(null, SatelliteStatus.Idle, toast = null)
        if (!covered) return SatelliteRefresh(
            null, SatelliteStatus.OutOfCoverage, toast = SatelliteStatus.OutOfCoverage.takeIf { userAsked }
        )
        val c = content() ?: return SatelliteRefresh(current, currentStatus, toast = null)
        if (current != null && currentKey == keyOf(c)) return SatelliteRefresh(current, SatelliteStatus.Ready, toast = null)
        onLoading()
        val r = load(c, userAsked)
        val overlay = when (r) {
            is SatelliteResult.Ok -> r.overlay
            SatelliteResult.NoImagery, SatelliteResult.OutOfCoverage -> null
            is SatelliteResult.Error -> current
        }
        val st = statusOf(r)
        return SatelliteRefresh(overlay, st, toast = st.takeIf { userAsked && it != SatelliteStatus.Ready })
    }

    fun message(ctx: android.content.Context, status: SatelliteStatus): String? =
        messageRes(status)?.let { (res, args) -> ctx.getString(res, *args.toTypedArray()) }

    /** Toast d'un statut qui a un message (sans effet sinon). */
    fun toast(ctx: android.content.Context, status: SatelliteStatus) {
        val msg = message(ctx, status) ?: return
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Montée COMMUNE de la mention et du curseur d'opacité du plan (posé juste
     * au-dessus d'elle) pour qu'aucun panneau du bas ne les recouvre — iOS
     * `satelliteAttributionLift` (l'assistant 3 points et la barre d'affectation
     * y sont des obstacles). Les deux montent ensemble : leur bloc (union des
     * cadres SANS montée, vides ignorés) passe juste au-dessus de l'obstacle le
     * plus proche, jamais plus haut que [topLimit] (sinon 0 : ils restent en
     * place). Unités libres mais communes (la vue plan travaille en dp, comme
     * les points iOS).
     */
    fun overlayLift(mention: Rect, slider: Rect?, obstacles: List<Rect>, topLimit: Float,
                    gap: Float = PanelPlacementMath.CORNER_STACK_GAP): Float {
        val parts = listOfNotNull(mention, slider).filter { it.width > 0f && it.height > 0f }
        if (parts.isEmpty()) return 0f
        val block = parts.reduce { a, b ->
            Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        }
        return PanelPlacementMath.clearanceLift(block, obstacles, topLimit, gap)
    }

    /**
     * Aucune place au-dessus ([lift] nul alors qu'un obstacle la touche) : la
     * mention reste en place mais passe DEVANT le panneau, pour rester lisible et
     * entière (iOS : « dessinée par-dessus le panneau »). Le curseur, lui, reste
     * dessous (les boutons du panneau gardent la main).
     */
    fun mentionOnTop(mention: Rect, lift: Float, obstacles: List<Rect>): Boolean =
        lift == 0f && mention.width > 0f && mention.height > 0f &&
            obstacles.any { it.width > 0f && it.height > 0f && it.overlaps(mention) }

    /** Ouvre la page du jeu de données IGN (lien de la mention). */
    fun openDataset(ctx: android.content.Context) {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(IgnImagery.DATASET_URL)
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }
}

/**
 * MENTION à l'écran (§4.1), en bas à droite de la vue tant que l'image est
 * affichée — plan ET 3D (au-dessus de la SceneView : les overlays Compose y sont
 * visibles et cliquables). Texte toujours ENTIER (retour à la ligne, jamais de
 * « … »), lien vers la page du jeu de données. Posée AU-DESSUS des barres d'outils
 * du bas (flottante à 12 dp ; relevée à 72 dp quand une barre est ancrée en bas).
 * [lift] la monte encore au-dessus des panneaux du bas (plan : assistant 3 points,
 * barre d'affectation — cf. [SatelliteUi.overlayLift]) ; [onAnchor] reçoit son
 * cadre SANS cette montée (px racine), mesuré avant le décalage. [onTop] la passe
 * devant les autres éléments (aucune place au-dessus, cf. [SatelliteUi.mentionOnTop]).
 */
@Composable
fun BoxScope.SatelliteAttribution(
    credit: Pair<Int, List<Any>>, layout: ToolbarLayout, specs: List<ToolSpec>,
    lift: Dp = 0.dp, onAnchor: ((Rect) -> Unit)? = null, onTop: Boolean = false
) {
    val ctx = LocalContext.current
    val (res, args) = credit
    val label = stringResource(res, *args.toTypedArray())
    Surface(
        color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.align(Alignment.BottomEnd)
            .zIndex(if (onTop) 1f else 0f)
            .padding(end = 8.dp, bottom = satelliteAttributionBottom(layout, specs))
            .widthIn(max = 260.dp)
            .then(if (onAnchor != null) Modifier.onGloballyPositioned { onAnchor(it.anchorBounds()) } else Modifier)
            .offset(y = -lift)
            .clickable(role = Role.Button, onClickLabel = label) { SatelliteUi.openDataset(ctx) }
    ) {
        Text(
            label, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp,
            textDecoration = TextDecoration.Underline, softWrap = true,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** Cadre (px racine, non rogné) de l'élément à cet endroit de la chaîne de modificateurs. */
internal fun LayoutCoordinates.anchorBounds(): Rect = Rect(positionInRoot(), size.toSize())

/**
 * Marge basse de la mention : contre le bas (12 dp) — la barre flottante historique
 * est retirée (SPEC-menus-android §9.0, Q1) — ou juste au-dessus d'une barre ancrée
 * en bas (12 dp + bouton 48 dp + 12 dp). La pile Rechercher / Recadrer, au même
 * coin, monte au-dessus d'elle (§3.4) : la mention n'est jamais recouverte.
 */
fun satelliteAttributionBottom(layout: ToolbarLayout, specs: List<ToolSpec>): Dp {
    val byId = specs.associateBy { it.id }
    val bottomBar = layout.bottom.mapNotNull { byId[it] }.any { it.available }
    return if (bottomBar) 72.dp else 12.dp
}

/**
 * MENTION dans une image exportée (PDF du plan, pages plan du PDF de câblage) :
 * pastille blanche 80 % en bas à droite de la zone image ([right], [bottom]),
 * texte noir 7 pt (§4.1). Dessinée en points (densité 1).
 */
internal fun DrawScope.drawSatelliteAttribution(
    measurer: TextMeasurer, label: String, right: Float, bottom: Float
) {
    val tl = measurer.measure(label, style = TextStyle(fontSize = 7.sp, color = Color.Black))
    val box = satelliteCreditBox(tl.size.width.toFloat(), tl.size.height.toFloat(), right, bottom)
    drawRect(Color.White.copy(alpha = 0.8f), topLeft = box.topLeft, size = box.size)
    drawText(tl, topLeft = Offset(box.left + CREDIT_PAD_X, box.top + CREDIT_PAD_Y))
}

/**
 * Adresse de la fiche IGN en clair SOUS la pastille ([SatelliteUi.pdfSourceUrl]) : 7 pt,
 * alignée à droite sur [right], son haut à [top] (sous la zone image, au niveau du pied).
 */
internal fun DrawScope.drawSatelliteSourceUrl(measurer: TextMeasurer, url: String, right: Float, top: Float) {
    val tl = measurer.measure(url, style = TextStyle(fontSize = 7.sp, color = Color(0xFF1A4F8B)))
    drawText(tl, topLeft = Offset(right - tl.size.width, top))
}

private const val CREDIT_PAD_X = 3f
private const val CREDIT_PAD_Y = 1.5f
private const val CREDIT_INSET = 3f

/**
 * Pastille de la mention exportée (PUR, en points) pour un texte [textW]×[textH] :
 * marges intérieures 3 × 1,5 pt, collée à 3 pt du coin bas-droit ([right], [bottom])
 * de la zone image — donc toujours DANS la zone, par-dessus le fond.
 */
internal fun satelliteCreditBox(textW: Float, textH: Float, right: Float, bottom: Float): Rect {
    val w = textW + 2 * CREDIT_PAD_X
    val h = textH + 2 * CREDIT_PAD_Y
    return Rect(right - CREDIT_INSET - w, bottom - CREDIT_INSET - h, right - CREDIT_INSET, bottom - CREDIT_INSET)
}
