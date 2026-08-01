package com.minou.mvrviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * Sélecteur de couleur (TSV) pour le fond d'une vue. Compose n'a pas de
 * ColorPicker natif → on en construit un compact : aperçu + 3 curseurs (teinte,
 * saturation, luminosité) qui mettent à jour la couleur EN DIRECT (comme le
 * ColorPicker iOS), plus « Rétablir le défaut ». Équivalent de BackgroundColorSheet.
 */
@Composable
fun BackgroundColorDialog(
    title: String,
    initial: Color,
    default: Color,
    onColorChange: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val hsv0 = remember(Unit) { initial.toHsv() }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }

    val current = Color.hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Aperçu de la couleur + code hex.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.height(40.dp).clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0x33808080), RoundedCornerShape(6.dp))
                            .background(current)
                            .fillMaxWidth()
                    )
                }
                Text(
                    "#%06X".format(current.toRgbHex()),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                )
                // On recalcule la couleur À PARTIR de la nouvelle valeur du curseur
                // (et non du `current` de la frame précédente, qui serait périmé).
                Text(stringResource(R.string.color_hue), style = MaterialTheme.typography.labelSmall)
                Slider(value = hue, valueRange = 0f..360f,
                    onValueChange = { hue = it; onColorChange(Color.hsv(it.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))) })
                Text(stringResource(R.string.color_saturation), style = MaterialTheme.typography.labelSmall)
                Slider(value = sat, valueRange = 0f..1f,
                    onValueChange = { sat = it; onColorChange(Color.hsv(hue.coerceIn(0f, 360f), it.coerceIn(0f, 1f), value.coerceIn(0f, 1f))) })
                Text(stringResource(R.string.color_brightness), style = MaterialTheme.typography.labelSmall)
                Slider(value = value, valueRange = 0f..1f,
                    onValueChange = { value = it; onColorChange(Color.hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), it.coerceIn(0f, 1f))) })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = {
            TextButton(onClick = {
                val h = default.toHsv()
                hue = h[0]; sat = h[1]; value = h[2]
                onColorChange(default)
            }) { Text(stringResource(R.string.color_restore_default)) }
        }
    )
}

/** Color → [teinte 0..360, saturation 0..1, valeur 0..1]. */
private fun Color.toHsv(): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255f + 0.5f).toInt().coerceIn(0, 255),
        (green * 255f + 0.5f).toInt().coerceIn(0, 255),
        (blue * 255f + 0.5f).toInt().coerceIn(0, 255),
        out
    )
    return out
}

/** 0xRRGGBB pour l'affichage. */
private fun Color.toRgbHex(): Int {
    fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (ch(red) shl 16) or (ch(green) shl 8) or ch(blue)
}
