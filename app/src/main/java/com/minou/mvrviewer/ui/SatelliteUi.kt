package com.minou.mvrviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.IgnImagery
import com.minou.mvrviewer.mvr.SatelliteErrorKind
import com.minou.mvrviewer.mvr.SatelliteResult

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
 * Textes du fond satellite (SPEC-satellite-ign.md §4) — sélection PURE de la
 * ressource et de ses arguments, testée en JVM sur les vrais `strings.xml`.
 */
object SatelliteUi {
    /** Mention obligatoire (Licence Ouverte) : avec l'année si connue. */
    fun attributionRes(year: Int?): Pair<Int, List<Any>> =
        if (year != null) R.string.sat_attribution_year to listOf(year)
        else R.string.sat_attribution to emptyList()

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

    fun attribution(ctx: android.content.Context, year: Int?): String {
        val (res, args) = attributionRes(year)
        return ctx.getString(res, *args.toTypedArray())
    }

    fun message(ctx: android.content.Context, status: SatelliteStatus): String? =
        messageRes(status)?.let { (res, args) -> ctx.getString(res, *args.toTypedArray()) }

    /** Toast d'un statut qui a un message (sans effet sinon). */
    fun toast(ctx: android.content.Context, status: SatelliteStatus) {
        val msg = message(ctx, status) ?: return
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Ouvre la page du jeu de données IGN (lien de la mention). */
    fun openDataset(ctx: android.content.Context) {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(IgnImagery.DATASET_URL)
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }
}

/** Message d'état courant (ressource résolue), ou null. */
@Composable
fun satelliteStatusText(status: SatelliteStatus): String? =
    SatelliteUi.messageRes(status)?.let { (res, args) -> stringResource(res, *args.toTypedArray()) }

/**
 * MENTION à l'écran (§4.1), en bas à droite de la vue tant que l'image est
 * affichée — plan ET 3D (au-dessus de la SceneView : les overlays Compose y sont
 * visibles et cliquables). Texte toujours ENTIER (retour à la ligne, jamais de
 * « … »), lien vers la page du jeu de données. Posée AU-DESSUS des barres d'outils
 * du bas (flottante à 12 dp ; relevée à 72 dp quand une barre est ancrée en bas).
 */
@Composable
fun BoxScope.SatelliteAttribution(year: Int?, layout: ToolbarLayout, specs: List<ToolSpec>) {
    val ctx = LocalContext.current
    val (res, args) = SatelliteUi.attributionRes(year)
    val label = stringResource(res, *args.toTypedArray())
    Surface(
        color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.align(Alignment.BottomEnd)
            .padding(end = 8.dp, bottom = satelliteAttributionBottom(layout, specs))
            .widthIn(max = 260.dp)
            .clickable(role = Role.Button, onClickLabel = label) { SatelliteUi.openDataset(ctx) }
    ) {
        Text(
            label, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp,
            textDecoration = TextDecoration.Underline, softWrap = true,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Marge basse de la mention : juste au-dessus de la barre flottante du bas (12 dp +
 * bouton 40 dp), ou de la flottante RELEVÉE quand une barre est ancrée en bas
 * (72 dp + 40 dp). Le curseur d'opacité du plan se pose 24 dp plus haut.
 */
fun satelliteAttributionBottom(layout: ToolbarLayout, specs: List<ToolSpec>): Dp {
    val byId = specs.associateBy { it.id }
    val bottomBar = layout.bottom.mapNotNull { byId[it] }.any { it.available }
    return if (bottomBar) 116.dp else 56.dp
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
    val padX = 3f; val padY = 1.5f; val inset = 3f
    val w = tl.size.width + 2 * padX
    val h = tl.size.height + 2 * padY
    val x = right - inset - w
    val y = bottom - inset - h
    drawRect(Color.White.copy(alpha = 0.8f), topLeft = Offset(x, y), size = Size(w, h))
    drawText(tl, topLeft = Offset(x + padX, y + padY))
}
