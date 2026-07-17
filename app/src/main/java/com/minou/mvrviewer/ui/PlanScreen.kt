package com.minou.mvrviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.MvrSceneObject
import kotlin.math.max
import kotlin.math.min

/**
 * Vue plan 2D — projection de dessus (top), comme la vue plan iOS : monde
 * (x, −y) en mm. Structure/décor en points gris clair pour le contexte,
 * projecteurs en cercles colorés par calque + Fixture ID. Pan à un/deux doigts,
 * pinch pour zoomer, tap pour sélectionner un projecteur. Fond blanc (document
 * de scénographie).
 */
@Composable
fun PlanScreen(scene: MvrScene, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val layerIndex = remember(scene) { LayerColors.index(scene) }
    val data = remember(scene) { planData(scene) }
    val measurer = rememberTextMeasurer()

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvas by remember { mutableStateOf(Offset.Zero) } // largeur/hauteur du Canvas
    var selected by remember(scene) { mutableStateOf<Int?>(null) } // index dans data.fixtures
    var query by remember(scene) { mutableStateOf("") }

    // Transformée monde→écran : centrée sur le contenu, ajustée au Canvas, puis
    // zoom/pan utilisateur par-dessus.
    fun baseScale(w: Float, h: Float): Float {
        if (data.spanX <= 0f || data.spanY <= 0f) return 1f
        return min(w / data.spanX, h / data.spanY) * 0.9f
    }
    fun toScreen(px: Float, py: Float, w: Float, h: Float): Offset {
        val bs = baseScale(w, h) * scale
        return Offset(w / 2f + offset.x + bs * (px - data.cx), h / 2f + offset.y + bs * (py - data.cy))
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(scene) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.05f, 200f)
                        offset += pan
                    }
                }
                .pointerInput(scene) {
                    detectTapGestures { tap ->
                        val w = canvas.x; val h = canvas.y
                        var best = -1; var bestD = 40f * 40f
                        data.fixtures.forEachIndexed { i, f ->
                            val s = toScreen(f.px, f.py, w, h)
                            val dx = s.x - tap.x; val dy = s.y - tap.y
                            val d = dx * dx + dy * dy
                            if (d < bestD) { bestD = d; best = i }
                        }
                        selected = if (best >= 0) best else null
                    }
                }
        ) {
            canvas = Offset(size.width, size.height)
            val w = size.width; val h = size.height

            // Décor / structure : petits points gris (contexte du plan).
            for (p in data.structure) {
                val s = toScreen(p.first, p.second, w, h)
                if (s.x in -20f..w + 20f && s.y in -20f..h + 20f) {
                    drawCircle(STRUCT_COLOR, radius = 1.6f, center = s)
                }
            }

            // Projecteurs : cercle coloré par calque + ID.
            val showLabels = baseScale(w, h) * scale > 0.02f
            data.fixtures.forEachIndexed { i, f ->
                val s = toScreen(f.px, f.py, w, h)
                if (s.x !in -40f..w + 40f || s.y !in -40f..h + 40f) return@forEachIndexed
                val c = Color(LayerColors.colorInt(layerIndex, f.layer))
                drawCircle(c, radius = 7f, center = s)
                drawCircle(Color.White, radius = 7f, center = s, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                if (i == selected) {
                    drawCircle(Color(0xFF111111), radius = 12f, center = s,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
                }
                if (showLabels && f.id != null) {
                    val tl = measurer.measure("#${f.id}", style = TextStyle(fontSize = 9.sp, color = Color(0xFF222222)))
                    drawText(tl, topLeft = Offset(s.x + 8f, s.y - 6f))
                }
            }
        }

        // Barre du haut : retour + stats.
        Surface(
            color = Color.Black.copy(alpha = 0.05f), contentColor = Color(0xFF222222),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(top = 52.dp, start = 56.dp)
        ) {
            Text(
                "Plan · ${scene.fixtures.size} projecteur(s)",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color(0xFF222222))
        }

        // Recherche d'un projecteur par Fixture ID : centre + sélectionne (comme
        // le bouton loupe iOS — usage terrain « où est le #152 »).
        fun doSearch() {
            val q = query.trim()
            if (q.isEmpty()) return
            val i = data.fixtures.indexOfFirst { it.id == q }
                .let { if (it >= 0) it else data.fixtures.indexOfFirst { f -> f.id?.contains(q, true) == true } }
            if (i < 0) return
            selected = i
            val f = data.fixtures[i]
            val target = max(scale, 6f)
            val bs = baseScale(canvas.x, canvas.y) * target
            scale = target
            offset = Offset(-bs * (f.px - data.cx), -bs * (f.py - data.cy))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("N° projecteur", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 8.dp).width(170.dp)
        )

        // Fiche du projecteur sélectionné (bas).
        selected?.let { i ->
            val f = data.fixtures[i]
            Surface(
                color = Color.White, contentColor = Color(0xFF111111),
                shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) {
                Text(
                    buildString {
                        append(f.id?.let { "#$it  " } ?: "")
                        append(f.name)
                        f.spec?.let { append("\n$it") }
                        append("\n${f.layer}")
                        if (f.addr.isNotEmpty()) append(" · DMX ${f.addr}")
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private val STRUCT_COLOR = Color(0xFF9AA0A6)

private class PlanFixture(
    val px: Float, val py: Float, val id: String?, val name: String,
    val spec: String?, val layer: String, val addr: String
)

private class PlanData(
    val fixtures: List<PlanFixture>,
    val structure: List<Pair<Float, Float>>,
    val cx: Float, val cy: Float, val spanX: Float, val spanY: Float
)

/** Projette la scène en plan (top : x, −y en mm) — projecteurs + décor. */
private fun planData(scene: MvrScene): PlanData {
    val fixtures = ArrayList<PlanFixture>()
    val structure = ArrayList<Pair<Float, Float>>()
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    fun extend(x: Float, y: Float) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
    }
    for (o in scene.allObjects) {
        val t = o.transform.translation
        if (!(t[0].isFinite() && t[1].isFinite())) continue
        val px = t[0]; val py = -t[1]
        if (o.isFixture) {
            fixtures.add(
                PlanFixture(px, py, o.fixtureId, o.name, o.gdtfSpec, o.layerName,
                    o.addresses.joinToString(","))
            )
            extend(px, py)
        } else {
            structure.add(px to py)
            // Le décor élargit aussi le cadrage, mais seulement s'il n'y a pas
            // de projecteurs (sinon un objet lointain écrase le rig).
        }
    }
    if (fixtures.isEmpty()) {
        for (p in structure) extend(p.first, p.second)
    }
    if (minX > maxX) { minX = -1000f; maxX = 1000f; minY = -1000f; maxY = 1000f }
    val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
    return PlanData(fixtures, structure, cx, cy, max(maxX - minX, 1f), max(maxY - minY, 1f))
}
