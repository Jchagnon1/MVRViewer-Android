package com.minou.mvrviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.mvr.ImportedModel
import com.minou.mvrviewer.mvr.SceneModelLoader
import kotlin.math.max

/**
 * PANNEAU DE PLACEMENT DES MODÈLES 3D IMPORTÉS (chantier #3).
 *
 * Reproduction 1:1 de l'anatomie du « Panneau de placement du plan DXF importé »
 * (PlanScreen) : même Surface blanche ancrée en bas à droite, mêmes flèches de
 * déplacement (pas de curseur XY), même rotation ±5°, même échelle ÷/×1,1 et le
 * MÊME bloc « Homothétie » (curseur log10 + saisie libre) — c'est LE contrôle
 * d'échelle proportionnelle demandé par la spec, réutilisé tel quel.
 *
 * En plus du plan : une LISTE (plusieurs modèles cohabitent, chacun retirable
 * individuellement) et une ligne « Hauteur » sur `offsetZ`.
 *
 * ⚠️ [com.minou.mvrviewer.mvr.SceneModelTransform] est un objet MUTABLE NON
 * OBSERVABLE : chaque réglage le mute EN PLACE puis appelle [onChanged], qui doit
 * incrémenter `importedModelsVersion` (sinon rien ne bouge en 3D) ET déclencher
 * la sauvegarde différée (sinon rien n'est persisté). Double piège documenté.
 */
@Composable
fun ModelPlacementPanel(
    models: List<ImportedModel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onRemove: (ImportedModel) -> Unit,
    onAdd: () -> Unit,
    onChanged: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sel = models.firstOrNull { it.id == selectedId } ?: models.firstOrNull()
    Surface(
        color = Color.White, contentColor = Color(0xFF111111),
        shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp,
        modifier = modifier.width(240.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val pad = PaddingValues(2.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Modèles 3D (${models.size})",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(contentPadding = pad, onClick = onClose) {
                    Text("Fermer", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ---- Liste : sélection + retrait individuel ----
            Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                models.forEach { m ->
                    val on = m.id == sel?.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(m.id) }
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                            Text(
                                m.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (on) Color(0xFF1E88E5) else Color(0xFF111111),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (m.format == SceneModelLoader.Format.GLTF.name)
                                    "glTF · unité détectée : mètre"
                                else "${m.triangles} triangles · unité détectée : " +
                                    if (m.unitScaleToMm > 1f) "mètre" else "millimètre",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onRemove(m) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Retirer",
                                tint = Color(0xFFC62828), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            TextButton(contentPadding = pad, onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Ajouter un modèle…", style = MaterialTheme.typography.labelSmall)
            }

            val model = sel ?: return@Column
            val tf = model.transform
            // Avertissement de TRONCATURE : jamais de simplification muette.
            if (model.truncated) {
                Text(
                    "Modèle simplifié : ${model.triangles} triangles conservés (plafond atteint).",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00)
                )
            }

            // Pas de déplacement ADAPTATIF à la taille du modèle (comme le plan
            // DXF, où il vaut 5 % de la largeur) — un praticable de 2 m et une
            // scène de 30 m ne se déplacent pas au même pas.
            val step = max(100.0, model.sizeMm.toDouble() * 0.05)

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Visible", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = tf.visible, onCheckedChange = { tf.visible = it; onChanged() })
            }
            Text("Déplacer", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.offsetX -= step; onChanged() }) { Text("←") }
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.offsetX += step; onChanged() }) { Text("→") }
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.offsetY += step; onChanged() }) { Text("↑") }
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.offsetY -= step; onChanged() }) { Text("↓") }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hauteur", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.offsetZ -= step; onChanged() }) { Text("−") }
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.offsetZ += step; onChanged() }) { Text("+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Rotation", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.rotationDeg -= 5; onChanged() }) { Text("−5°") }
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.rotationDeg += 5; onChanged() }) { Text("+5°") }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Échelle", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.scale /= 1.1; onChanged() }) { Text("÷") }
                OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f),
                    onClick = { tf.scale *= 1.1; onChanged() }) { Text("×") }
            }

            // ---- HOMOTHÉTIE (mise à l'échelle PROPORTIONNELLE) ----
            // Mêmes helpers que le panneau du plan de repère (log10 sur le
            // curseur, saisie libre bornée) : un seul facteur, jamais de
            // déformation.
            var homoLog by remember(model.id) { mutableFloatStateOf(homothetySlider(tf.homothety)) }
            var homoText by remember(model.id) { mutableStateOf(formatHomothety(tf.homothety)) }
            fun applyHomothety(v: Double, syncSlider: Boolean, syncText: Boolean) {
                val c = v.coerceIn(HOMOTHETY_MIN, HOMOTHETY_MAX)
                tf.homothety = c
                if (syncSlider) homoLog = homothetySlider(c)
                if (syncText) homoText = formatHomothety(c)
                onChanged()
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Homothétie", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(contentPadding = pad,
                    onClick = { applyHomothety(1.0, syncSlider = true, syncText = true) }) {
                    Text("Réinitialiser", style = MaterialTheme.typography.labelSmall)
                }
            }
            Slider(
                value = homoLog,
                onValueChange = { v ->
                    homoLog = v
                    applyHomothety(Math.pow(10.0, v.toDouble()), syncSlider = false, syncText = true)
                },
                valueRange = -1f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("×", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = homoText,
                    onValueChange = { s ->
                        homoText = s
                        s.replace(',', '.').toDoubleOrNull()?.let {
                            if (it > 0.0) applyHomothety(it, syncSlider = true, syncText = false)
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(
                contentPadding = pad,
                onClick = {
                    tf.reset()
                    homoLog = homothetySlider(1.0); homoText = formatHomothety(1.0)
                    onChanged()
                }
            ) { Text("Réinitialiser le placement", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
