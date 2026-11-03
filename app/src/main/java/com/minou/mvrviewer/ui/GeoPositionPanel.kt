package com.minou.mvrviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GeoCalibration
import com.minou.mvrviewer.mvr.GeoCalibrationStatus
import java.util.Locale

/**
 * Une ligne du panneau « Position GPS », dans l'ordre de la spec
 * (SPEC-menus-android §3.6, iOS `GeoPanelRow`).
 */
enum class GeoPanelRow {
    // Section « Affichage » — gratuit
    SHOW_MY_LOCATION,
    /**
     * « Position approximative : précision limitée… » (en orange) sous « Afficher ma
     * position », seulement si l'appareil n'a accordé que l'approximative (guide C8).
     */
    APPROXIMATE_LOCATION,
    SATELLITE,
    /** « Calibrez d'abord (ci-dessous)… » — seulement si non calibré. */
    CALIBRATE_FIRST_HINT,
    /** « Téléchargement du satellite… » + indicateur pendant le chargement. */
    SATELLITE_LOADING,
    /** Message du fond satellite : hors de France, pas d'imagerie, échec réseau… */
    SATELLITE_MESSAGE,
    /** Curseur d'opacité — LE SEUL endroit où elle se règle (décision 8). */
    SATELLITE_OPACITY,
    /** Taille du point GPS — 3D seulement, si calibré. */
    MARKER_SIZE,
    // Section « Calibration » — Pro au toucher (sauf la ligne d'état)
    STATUS,
    THREE_POINTS,
    HERE_ANCHOR,
    /** Légende de « Je suis ici (pose rapide)… ». */
    HERE_ANCHOR_HINT,
    // Isolé sous un séparateur
    RESET_CALIBRATION
}

/**
 * Contenu du panneau « Position GPS » — calcul PUR, testé (`GeoPanelContentTest`).
 * La vue ([GeoPositionPanel]) ne fait que dessiner ce qu'on lui rend.
 */
data class GeoPanelContent(
    val display: List<GeoPanelRow>,
    val calibration: List<GeoPanelRow>,
    /** Destructeur isolé (sous un séparateur) ; vide = pas de séparateur. */
    val isolated: List<GeoPanelRow>,
    /** « Afficher ma position » utilisable : en 3D, seulement calibré (le point en dépend, §8 D20). */
    val showLocationEnabled: Boolean,
    /** Interrupteur « Fond satellite » : lu éteint tant que la scène n'est pas calibrée. */
    val satelliteChecked: Boolean,
    /** Désactivé sans calibration (rien à géo-référencer) ou hors de France (§8 D8). */
    val satelliteEnabled: Boolean,
    /** Message de [GeoPanelRow.SATELLITE_MESSAGE] (ressource + arguments), null sinon. */
    val satelliteMessage: Pair<Int, List<Any>>?,
    /** « Calibrer en 3 points » = action PRINCIPALE tant que la scène n'est pas calibrée. */
    val threePointsPrimary: Boolean
) {
    companion object {
        /** Raison d'écran Pro des trois actions de calibration (existante). */
        const val PRO_REASON = "calibrer la position GPS"
        /** Bornes du curseur « Taille du point GPS » (×0,3–×3) et de l'opacité (5–100 %). */
        const val MARKER_MIN = 0.3f
        const val MARKER_MAX = 3f
        const val OPACITY_MIN = 0.05f
        const val OPACITY_MAX = 1f

        fun make(
            surface: ProjectSurface, calibrated: Boolean, showSatellite: Boolean,
            satelliteCovered: Boolean, satelliteStatus: SatelliteStatus,
            /** Seule la position approximative est accordée (`UserLocationFeed.approximate`). */
            approximateLocation: Boolean = false
        ): GeoPanelContent {
            val display = mutableListOf(GeoPanelRow.SHOW_MY_LOCATION)
            if (approximateLocation) display += GeoPanelRow.APPROXIMATE_LOCATION
            display += GeoPanelRow.SATELLITE
            var message: Pair<Int, List<Any>>? = null
            if (!calibrated) {
                display += GeoPanelRow.CALIBRATE_FIRST_HINT
            } else if (!satelliteCovered) {
                // Hors de France : le message dit TOUJOURS pourquoi l'interrupteur est grisé.
                display += GeoPanelRow.SATELLITE_MESSAGE
                message = R.string.sat_out_of_coverage to emptyList()
            } else if (showSatellite) {
                if (satelliteStatus == SatelliteStatus.Loading) {
                    display += GeoPanelRow.SATELLITE_LOADING
                } else {
                    SatelliteUi.messageRes(satelliteStatus)?.let {
                        display += GeoPanelRow.SATELLITE_MESSAGE
                        message = it
                    }
                }
                display += GeoPanelRow.SATELLITE_OPACITY
            }
            if (surface == ProjectSurface.SCENE_3D && calibrated) display += GeoPanelRow.MARKER_SIZE
            return GeoPanelContent(
                display = display,
                calibration = listOf(GeoPanelRow.STATUS, GeoPanelRow.THREE_POINTS,
                    GeoPanelRow.HERE_ANCHOR, GeoPanelRow.HERE_ANCHOR_HINT),
                isolated = if (calibrated) listOf(GeoPanelRow.RESET_CALIBRATION) else emptyList(),
                showLocationEnabled = surface == ProjectSurface.PLAN || calibrated,
                satelliteChecked = calibrated && satelliteCovered && showSatellite,
                satelliteEnabled = calibrated && satelliteCovered,
                satelliteMessage = message,
                threePointsPrimary = !calibrated
            )
        }

        /** Ligne d'état de la calibration (textes existants) et sa couleur (orange si refus). */
        fun status(calibration: GeoCalibration, locationDenied: Boolean): Pair<CalText, Boolean> {
            val st = GeoCalibrationStatus.of(calibration, locationDenied)
            return ThreePointDisplay.statusText(st) to st.isWarning
        }

        /** Valeur affichée du curseur de taille : « ×1,5 » (une décimale, séparateur de la langue). */
        fun markerScaleText(v: Float, locale: Locale): String = "×" + String.format(locale, "%.1f", v)

        /** Valeur affichée de l'opacité : « 55 % » (espace insécable comme iOS « %.0f %% »). */
        fun opacityText(v: Float): String = "${Math.round(v * 100f)} %"
    }
}

/**
 * Panneau « Position GPS » (SPEC-menus-android §3.6), le même dans les deux vues.
 * Affichage gratuit ; les trois actions de calibration sont Pro au toucher (la
 * vue qui pose le panneau passe des actions déjà gardées). [statusText] est lu
 * ici seulement : le refus de localisation n'abonne que cette ligne.
 */
@Composable
fun GeoPositionPanel(
    content: GeoPanelContent,
    showLocation: Boolean,
    onShowLocation: (Boolean) -> Unit,
    onToggleSatellite: () -> Unit,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    markerScale: Float,
    onMarkerScale: (Float) -> Unit,
    statusText: @Composable () -> Pair<String, Boolean>,
    onThreePoints: () -> Unit,
    onHereAnchor: () -> Unit,
    onResetCalibration: () -> Unit,
    onClose: () -> Unit,
    maxContentHeight: Dp?
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    FloatingPanelCard(
        title = stringResource(R.string.geo_panel_title), onClose = onClose,
        maxContentHeight = maxContentHeight, tag = "geoPanel"
    ) {
        PanelSectionTitle(stringResource(R.string.menu_display))
        for (row in content.display) when (row) {
            GeoPanelRow.SHOW_MY_LOCATION -> PanelSwitchRow(
                stringResource(R.string.geo_show_my_position),
                checked = showLocation && content.showLocationEnabled,
                enabled = content.showLocationEnabled, onChange = onShowLocation
            )
            GeoPanelRow.SATELLITE -> PanelSwitchRow(
                stringResource(R.string.tool_satellite),
                checked = content.satelliteChecked, enabled = content.satelliteEnabled,
                onChange = { onToggleSatellite() }
            )
            GeoPanelRow.APPROXIMATE_LOCATION -> Text(
                stringResource(R.string.location_approximate_panel),
                style = MaterialTheme.typography.bodySmall, color = PANEL_WARNING,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            GeoPanelRow.CALIBRATE_FIRST_HINT -> PanelCaption(stringResource(R.string.geo_calibrate_first))
            GeoPanelRow.SATELLITE_LOADING -> Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = PANEL_SECONDARY)
                Text(stringResource(R.string.sat_loading), style = MaterialTheme.typography.bodySmall,
                    color = PANEL_SECONDARY, modifier = Modifier.padding(start = 8.dp))
            }
            GeoPanelRow.SATELLITE_MESSAGE -> content.satelliteMessage?.let { (res, args) ->
                PanelCaption(stringResource(res, *args.toTypedArray()))
            }
            GeoPanelRow.SATELLITE_OPACITY -> PanelSliderRow(
                stringResource(R.string.geo_opacity), GeoPanelContent.opacityText(opacity),
                value = opacity, range = GeoPanelContent.OPACITY_MIN..GeoPanelContent.OPACITY_MAX,
                onChange = onOpacity
            )
            GeoPanelRow.MARKER_SIZE -> PanelSliderRow(
                stringResource(R.string.geo_dot_size), GeoPanelContent.markerScaleText(markerScale, locale),
                value = markerScale, range = GeoPanelContent.MARKER_MIN..GeoPanelContent.MARKER_MAX,
                onChange = onMarkerScale
            )
            else -> {}
        }
        PanelSectionTitle(stringResource(R.string.geo_section_calibration))
        for (row in content.calibration) when (row) {
            GeoPanelRow.STATUS -> {
                val (text, warning) = statusText()
                Text(text, style = MaterialTheme.typography.bodySmall,
                    color = if (warning) PANEL_WARNING else PANEL_SECONDARY,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            GeoPanelRow.THREE_POINTS -> {
                val label = stringResource(R.string.tool_calibrate_3)
                if (content.threePointsPrimary) Button(onClick = onThreePoints, colors = panelPrimaryColors(),
                    modifier = Modifier.fillMaxWidth()) { Text(label) }
                else OutlinedButton(onClick = onThreePoints, colors = panelOutlinedColors(),
                    modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            GeoPanelRow.HERE_ANCHOR -> OutlinedButton(onClick = onHereAnchor, colors = panelOutlinedColors(),
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.geo_here_quick)) }
            GeoPanelRow.HERE_ANCHOR_HINT -> PanelCaption(stringResource(R.string.geo_here_caption))
            else -> {}
        }
        if (content.isolated.isNotEmpty()) {
            PanelDivider()
            if (GeoPanelRow.RESET_CALIBRATION in content.isolated) {
                TextButton(onClick = onResetCalibration, colors = panelDestructiveColors()) {
                    Text(stringResource(R.string.geo_reset_calibration))
                }
            }
        }
    }
}

// ---- Briques de ligne des panneaux (partagées avec le panneau Plan importé) ----

@Composable
internal fun PanelSwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) PANEL_INK else PANEL_SECONDARY, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = panelSwitchColors())
    }
}

@Composable
internal fun PanelCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = PANEL_SECONDARY,
        modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
internal fun PanelSliderRow(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = PANEL_SECONDARY,
            modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, colors = panelSliderColors(),
        modifier = Modifier.fillMaxWidth())
}
