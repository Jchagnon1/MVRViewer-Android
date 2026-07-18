package com.minou.mvrviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
fun PlanScreen(
    scene: MvrScene,
    mvrBytes: ByteArray,
    options: SceneOptions,
    referencePlan: com.minou.mvrviewer.mvr.ReferencePlan? = null,
    onSetReferencePlan: (com.minou.mvrviewer.mvr.ReferencePlan?) -> Unit = {},
    calibration: com.minou.mvrviewer.mvr.GeoCalibration = remember { com.minou.mvrviewer.mvr.GeoCalibration() },
    projectKey: String? = null,
    satellite: com.minou.mvrviewer.mvr.SatelliteOverlay? = null,
    onCalibrationChanged: () -> Unit = {},
    gdtfOverrides: GdtfOverrides? = null,
    onBack: () -> Unit,
    onShowPatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctxPlan = androidx.compose.ui.platform.LocalContext.current
    val layerIndex = remember(scene) { LayerColors.index(scene) }
    val data = remember(scene) { planData(scene) }
    val measurer = rememberTextMeasurer()

    // Fil de fer VECTORIEL des structures (arêtes caractéristiques réelles de la
    // géométrie .3ds, comme iOS). Construit hors thread principal.
    var wire by remember(scene) { mutableStateOf<PlanWireframe.Built?>(null) }
    LaunchedEffect(scene, mvrBytes) {
        wire = null
        wire = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            PlanWireframe.build(scene, mvrBytes)
        }
    }
    // Silhouette fil de fer des PROJECTEURS (modèle GDTF par spec, comme iOS).
    // Reconstruit quand un modèle GDTF Share est appliqué (version bump).
    var fixWire by remember(scene) { mutableStateOf<PlanWireframe.FixtureWire?>(null) }
    LaunchedEffect(scene, mvrBytes, gdtfOverrides?.version ?: 0) {
        fixWire = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            PlanWireframe.buildFixtures(scene, mvrBytes, gdtfOverrides?.map?.toMap() ?: emptyMap())
        }
    }
    // Pendant un geste (pan/zoom), on retombe sur un point par structure (max
    // fluidité) ; le fil de fer complet réapparaît 180 ms après le dernier geste.
    var gesturing by remember { mutableStateOf(false) }
    var gestureTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(gestureTick) {
        if (gestureTick == 0) return@LaunchedEffect
        gesturing = true
        kotlinx.coroutines.delay(180)
        gesturing = false
    }

    // Import d'un plan de repère DXF + réglage de son placement. La transformée
    // est un objet mutable non observable → dxfVersion force le redraw.
    val context = androidx.compose.ui.platform.LocalContext.current
    var dxfVersion by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showDxfPanel by remember { mutableStateOf(false) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) { pickedUri = uri; importing = true } }
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { com.minou.mvrviewer.mvr.DxfParser.parse(it) }
            }.getOrNull()
        }
        importing = false
        pickedUri = null
        if (plan != null && !plan.isEmpty) {
            // Centrer le DXF sur le centre de la scène MVR (repère monde).
            val tf = com.minou.mvrviewer.mvr.ReferencePlanTransform(
                offsetX = (data.cx - plan.centerX).toDouble(),
                offsetY = (-data.cy - plan.centerY).toDouble()
            )
            onSetReferencePlan(com.minou.mvrviewer.mvr.ReferencePlan(plan, tf))
            showDxfPanel = true
            dxfVersion++
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvas by remember { mutableStateOf(Offset.Zero) } // largeur/hauteur du Canvas
    val selected = remember(scene) { mutableStateListOf<Int>() } // indices dans data.fixtures
    var rectMode by remember { mutableStateOf(false) }
    var rectStart by remember { mutableStateOf<Offset?>(null) }
    var rectEnd by remember { mutableStateOf<Offset?>(null) }
    var query by remember(scene) { mutableStateOf("") }

    // Géolocalisation : position GPS en direct + calibration par ancres (la
    // calibration est HISSÉE dans SceneScreen → survit aux bascules + persistée).
    var showLocation by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var calibVersion by remember { mutableIntStateOf(0) } // force le redraw à l'ajout d'ancre
    val gps by rememberUserLocation(showLocation)

    // Persistance projet : réenregistre le placement du plan DXF (glissé/rotation/
    // échelle → dxfVersion) et la calibration, débouncé, quand ça change.
    if (projectKey != null) {
        val rpForSave = referencePlan
        LaunchedEffect(dxfVersion, rpForSave) {
            if (rpForSave != null) {
                kotlinx.coroutines.delay(500)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.minou.mvrviewer.mvr.ProjectStore.saveTransform(ctxPlan, projectKey, rpForSave.transform)
                }
            }
        }
        LaunchedEffect(calibVersion) {
            if (calibVersion > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.minou.mvrviewer.mvr.ProjectStore.saveCalibration(ctxPlan, projectKey, calibration.anchors.toList())
            }
        }
    }

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
    // Inverse écran → coord plan (px, py) = (worldX, −worldY).
    fun toPlan(sx: Float, sy: Float, w: Float, h: Float): Pair<Float, Float> {
        val bs = baseScale(w, h) * scale
        return (sx - w / 2f - offset.x) / bs + data.cx to (sy - h / 2f - offset.y) / bs + data.cy
    }

    // Fond de plan choisi + contraste : sur fond sombre, les tracés/étiquettes
    // dessinés en foncé doivent s'éclaircir pour rester lisibles (le décor par
    // calque et les pastilles restent vifs et passent sur les deux).
    val planBg = options.background2D
    val bgDark = BackgroundColorStore.isDark(planBg)
    val inkColor = if (bgDark) Color(0xFFECECEC) else Color(0xFF222222)
    val dxfColor = if (bgDark) DXF_COLOR_DARK_BG else DXF_COLOR

    Box(modifier = modifier.fillMaxSize().background(planBg)) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                // En mode rectangle, le glissé trace le cadre de sélection ;
                // sinon il déplace/zoome le plan (comme le mode dédié iOS).
                .pointerInput(scene, rectMode) {
                    if (rectMode) {
                        detectDragGestures(
                            onDragStart = { rectStart = it; rectEnd = it },
                            onDrag = { change, _ -> rectEnd = change.position },
                            onDragEnd = {
                                val a = rectStart; val b = rectEnd
                                if (a != null && b != null) {
                                    val l = min(a.x, b.x); val r = max(a.x, b.x)
                                    val t = min(a.y, b.y); val bo = max(a.y, b.y)
                                    selected.clear()
                                    data.fixtures.forEachIndexed { i, f ->
                                        val s = toScreen(f.px, f.py, canvas.x, canvas.y)
                                        if (s.x in l..r && s.y in t..bo) selected.add(i)
                                    }
                                }
                                rectStart = null; rectEnd = null
                            }
                        )
                    } else {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.05f, 200f)
                            offset += pan
                            gestureTick++
                        }
                    }
                }
                .pointerInput(scene, rectMode, calibrating) {
                    detectTapGestures { tap ->
                        val w = canvas.x; val h = canvas.y
                        // Calibrage « je suis ici » : le tap pose une ancre (point
                        // plan touché ↔ position GPS courante).
                        val g = gps
                        if (calibrating && g != null) {
                            val (px, py) = toPlan(tap.x, tap.y, w, h)
                            calibration.addAnchor(
                                com.minou.mvrviewer.mvr.GeoAnchor(px, -py, g.latitude, g.longitude)
                            )
                            calibVersion++
                            onCalibrationChanged()
                            calibrating = false
                            return@detectTapGestures
                        }
                        var best = -1; var bestD = 40f * 40f
                        data.fixtures.forEachIndexed { i, f ->
                            val s = toScreen(f.px, f.py, w, h)
                            val dx = s.x - tap.x; val dy = s.y - tap.y
                            val d = dx * dx + dy * dy
                            if (d < bestD) { bestD = d; best = i }
                        }
                        if (best < 0) { if (!rectMode) selected.clear() }
                        else if (rectMode) { // toggle dans la sélection multiple
                            if (!selected.remove(best)) selected.add(best)
                        } else { selected.clear(); selected.add(best) }
                    }
                }
        ) {
            canvas = Offset(size.width, size.height)
            val w = size.width; val h = size.height

            // ---- Fond satellite géo-référencé, SOUS tout le reste ----
            // L'image porte ses 4 coins en monde MVR ; on la dessine comme un
            // quad via toScreen (même projection que le plan) → alignée sur les
            // projecteurs. Transformée affine à partir de 3 coins (NW, NE, SW).
            val sat = satellite
            if (options.showSatellite && sat != null && !gesturing && sat.bitmap.width > 0) {
                val nw = toScreen(sat.nwX, -sat.nwY, w, h)
                val ne = toScreen(sat.neX, -sat.neY, w, h)
                val sw = toScreen(sat.swX, -sat.swY, w, h)
                val iw = sat.bitmap.width.toFloat(); val ih = sat.bitmap.height.toFloat()
                val a = (ne.x - nw.x) / iw; val b = (ne.y - nw.y) / iw
                val c = (sw.x - nw.x) / ih; val d = (sw.y - nw.y) / ih
                val m = android.graphics.Matrix().apply {
                    setValues(floatArrayOf(a, c, nw.x, b, d, nw.y, 0f, 0f, 1f))
                }
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true; isFilterBitmap = true
                    alpha = (options.satelliteOpacity.coerceIn(0f, 1f) * 255f).toInt()
                }
                drawIntoCanvas { it.nativeCanvas.drawBitmap(sat.bitmap, m, paint) }
            }

            // ---- Plan de repère DXF importé (sous tout le reste) ----
            dxfVersion.let { }  // dépendance de redraw
            val rp = referencePlan
            if (rp != null && rp.transform.visible && !gesturing) {
                val tf = rp.transform
                val sfac = tf.scale.toFloat()
                val rr = Math.toRadians(tf.rotationDeg)
                val cc = kotlin.math.cos(rr).toFloat(); val sn = kotlin.math.sin(rr).toFloat()
                val ox = tf.offsetX.toFloat(); val oy = tf.offsetY.toFloat()
                val path = androidx.compose.ui.graphics.Path()
                var drawn = 0
                loop@ for (pl in rp.plan.polylines) {
                    val pts = pl.points
                    var first = true
                    var i = 0
                    while (i < pts.size) {
                        val lx = pts[i] * sfac; val ly = pts[i + 1] * sfac; i += 2
                        val wx = ox + (lx * cc - ly * sn); val wy = oy + (lx * sn + ly * cc)
                        val s = toScreen(wx, -wy, w, h)
                        if (first) { path.moveTo(s.x, s.y); first = false } else path.lineTo(s.x, s.y)
                    }
                    if (pl.closed && pts.size >= 4) {
                        val lx = pts[0] * sfac; val ly = pts[1] * sfac
                        val wx = ox + (lx * cc - ly * sn); val wy = oy + (lx * sn + ly * cc)
                        val s = toScreen(wx, -wy, w, h); path.lineTo(s.x, s.y)
                    }
                    drawn += pts.size / 2
                    if (drawn > 400_000) break@loop  // garde-fou de dessin
                }
                drawPath(path, dxfColor, style = androidx.compose.ui.graphics.drawscope.Stroke(0.7f))
            }

            // Décor / structure : FIL DE FER VECTORIEL (arêtes caractéristiques
            // réelles de la géométrie 3D). Pendant un geste, ou tant que le fil
            // de fer se construit, on retombe sur un point par structure.
            if (options.showStructure) {
                val wf = wire
                val bs = baseScale(w, h) * scale
                if (wf != null && !wf.isEmpty && !gesturing) {
                    val pathByLayer = HashMap<String, androidx.compose.ui.graphics.Path>()
                    for (inst in wf.instances) {
                        val edges = wf.edgesByKey[inst.key]
                        if (edges == null) {
                            val s = toScreen(inst.cx, inst.cy, w, h)
                            if (s.x in -20f..w + 20f && s.y in -20f..h + 20f) {
                                drawCircle(STRUCT_COLOR, radius = 1.6f, center = s)
                            }
                            continue
                        }
                        // Cull grossier : centre + rayon écran de la structure.
                        val cs = toScreen(inst.cx, inst.cy, w, h)
                        val rPx = (wf.radiusByKey[inst.key] ?: 0f) * bs
                        val margin = 220f + rPx
                        if (cs.x < -margin || cs.x > w + margin || cs.y < -margin || cs.y > h + margin) continue
                        val world = inst.world
                        val path = pathByLayer.getOrPut(inst.layer) { androidx.compose.ui.graphics.Path() }
                        var i = 0
                        while (i < edges.size) {
                            val ax = edges[i]; val ay = edges[i+1]; val az = edges[i+2]
                            val bx = edges[i+3]; val by = edges[i+4]; val bz = edges[i+5]
                            i += 6
                            // Transform monde puis projection top (worldX, −worldY).
                            val wax = world.x.x*ax + world.y.x*ay + world.z.x*az + world.w.x
                            val way = world.x.y*ax + world.y.y*ay + world.z.y*az + world.w.y
                            val wbx = world.x.x*bx + world.y.x*by + world.z.x*bz + world.w.x
                            val wby = world.x.y*bx + world.y.y*by + world.z.y*bz + world.w.y
                            val pa = toScreen(wax, -way, w, h)
                            val pb = toScreen(wbx, -wby, w, h)
                            if ((pa.x < -20f && pb.x < -20f) || (pa.x > w+20f && pb.x > w+20f) ||
                                (pa.y < -20f && pb.y < -20f) || (pa.y > h+20f && pb.y > h+20f)) continue
                            path.moveTo(pa.x, pa.y); path.lineTo(pb.x, pb.y)
                        }
                    }
                    for ((layer, path) in pathByLayer) {
                        val col = if (options.layerColors) Color(LayerColors.colorInt(layerIndex, layer)) else STRUCT_COLOR
                        drawPath(path, col, style = androidx.compose.ui.graphics.drawscope.Stroke(0.8f))
                    }
                } else {
                    for (p in data.structure) {
                        val s = toScreen(p.first, p.second, w, h)
                        if (s.x in -20f..w + 20f && s.y in -20f..h + 20f) {
                            drawCircle(STRUCT_COLOR, radius = 1.6f, center = s)
                        }
                    }
                }
            }

            // Projecteurs : SILHOUETTE FIL DE FER réelle (modèle GDTF projeté,
            // comme iOS) quand elle est lisible à ce zoom, sinon pastille.
            // PASSE 1 : silhouettes (un stroke par calque), avec un cull qui
            // tient compte du RAYON écran — sinon une silhouette zoomée
            // disparaîtrait dès que l'origine du projecteur sort du cadre.
            val showLabels = options.showLabels && baseScale(w, h) * scale > 0.02f
            val fw = fixWire
            val bsNow = baseScale(w, h) * scale
            // Silhouette visible pour f ? (edges + rayon écran lisible)
            fun silhouetteOf(f: PlanFixture): Pair<FloatArray, Float>? {
                if (gesturing || fw == null) return null
                val spec = f.spec?.trim() ?: return null
                val e = fw.edgesBySpec[spec] ?: return null
                val r = (fw.radiusBySpec[spec] ?: 0f) * bsNow
                return if (r > 7f) e to r else null
            }
            if (!gesturing && fw != null) {
                val fixPaths = HashMap<String, Pair<Color, androidx.compose.ui.graphics.Path>>()
                for (f in data.fixtures) {
                    val (edges, rPx) = silhouetteOf(f) ?: continue
                    val s = toScreen(f.px, f.py, w, h)
                    val m = 40f + rPx
                    if (s.x !in -m..w + m || s.y !in -m..h + m) continue
                    val c = if (options.layerColors) Color(LayerColors.colorInt(layerIndex, f.layer)) else Color(0xFF6E6E73)
                    val world = f.world
                    val path = fixPaths.getOrPut(f.layer) { c to androidx.compose.ui.graphics.Path() }.second
                    var k = 0
                    while (k < edges.size) {
                        val ax = edges[k]; val ay = edges[k + 1]; val az = edges[k + 2]
                        val bx = edges[k + 3]; val by = edges[k + 4]; val bz = edges[k + 5]
                        k += 6
                        val wax = world.x.x * ax + world.y.x * ay + world.z.x * az + world.w.x
                        val way = world.x.y * ax + world.y.y * ay + world.z.y * az + world.w.y
                        val wbx = world.x.x * bx + world.y.x * by + world.z.x * bz + world.w.x
                        val wby = world.x.y * bx + world.y.y * by + world.z.y * bz + world.w.y
                        val pa = toScreen(wax, -way, w, h)
                        val pb = toScreen(wbx, -wby, w, h)
                        path.moveTo(pa.x, pa.y); path.lineTo(pb.x, pb.y)
                    }
                }
                for ((_, cp) in fixPaths) {
                    drawPath(cp.second, cp.first, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
                }
            }
            // PASSE 2 : pastilles / anneaux de sélection / étiquettes, PAR-DESSUS
            // les silhouettes (lisibilité).
            data.fixtures.forEachIndexed { i, f ->
                val s = toScreen(f.px, f.py, w, h)
                if (s.x !in -40f..w + 40f || s.y !in -40f..h + 40f) return@forEachIndexed
                val c = if (options.layerColors) Color(LayerColors.colorInt(layerIndex, f.layer)) else Color(0xFF6E6E73)
                if (silhouetteOf(f) != null) {
                    drawCircle(c, radius = 3f, center = s)
                } else {
                    drawCircle(c, radius = 7f, center = s)
                    drawCircle(Color.White, radius = 7f, center = s, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                }
                if (i in selected) {
                    drawCircle(Color(0xFFFFC400), radius = 13f, center = s,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                }
                if (showLabels) {
                    val text = when (options.labelContent) {
                        LabelContent.ID -> f.id?.let { "#$it" }
                        LabelContent.DMX -> f.addr.ifEmpty { null }?.let { com.minou.mvrviewer.mvr.DmxAddress.format(it) }
                        LabelContent.MODE -> f.mode?.ifEmpty { null }
                        LabelContent.NAME -> f.name
                    }
                    if (text != null) {
                        val fs = (9f * options.labelSize)
                        val off = 8f * options.labelOffset
                        val tl = measurer.measure(text, style = TextStyle(fontSize = fs.sp, color = inkColor))
                        drawText(tl, topLeft = Offset(s.x + off, s.y - fs * 0.7f))
                    }
                }
            }

            // Position GPS de l'utilisateur (bleu), après calibrage.
            calibVersion.let { /* redraw à l'ajout d'ancre */ }
            val g = gps
            if (showLocation && g != null && calibration.isCalibrated) {
                val wp = calibration.worldPosition(g.latitude, g.longitude)
                if (wp != null) {
                    val s = toScreen(wp.first, -wp.second, w, h)
                    drawCircle(Color(0x332979FF), radius = 26f, center = s)
                    drawCircle(Color(0xFF2979FF), radius = 9f, center = s)
                    drawCircle(Color.White, radius = 9f, center = s,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
                }
            }

            // Cadre de sélection en cours.
            val a = rectStart; val b = rectEnd
            if (rectMode && a != null && b != null) {
                val tl = Offset(min(a.x, b.x), min(a.y, b.y))
                val sz = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
                drawRect(Color(0x33FFC400), topLeft = tl, size = sz)
                drawRect(Color(0xFFFFC400), topLeft = tl, size = sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
        }

        // Barre du haut : retour + stats. Le voile s'inverse sur fond sombre.
        Surface(
            color = if (bgDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.05f),
            contentColor = inkColor,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(top = 52.dp, start = 56.dp)
        ) {
            Text(
                "Plan · ${scene.fixtures.size} projecteur(s)",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vue 3D", tint = inkColor)
        }

        // Légende : couleur de chaque calque de projecteurs + compte (comme iOS).
        if (options.showLegend) {
            val legend = remember(scene) {
                scene.fixtures.groupingBy { it.layerName }.eachCount()
                    .toList().sortedByDescending { it.second }
            }
            if (legend.isNotEmpty()) {
                Surface(
                    color = Color.White.copy(alpha = 0.92f), contentColor = Color(0xFF222222),
                    shape = RoundedCornerShape(10.dp), shadowElevation = 3.dp,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = 100.dp, start = 8.dp)
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        legend.take(10).forEach { (layer, n) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                androidx.compose.foundation.layout.Box(
                                    Modifier.width(10.dp).height(10.dp).background(
                                        if (options.layerColors) Color(LayerColors.colorInt(layerIndex, layer))
                                        else Color(0xFF6E6E73),   // cohérent avec les pastilles
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                                ) {}
                                Text("  $layer · $n", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (legend.size > 10) {
                            Text("  +${legend.size - 10} autre(s) calque(s)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            SceneOptionsMenu(
                options = options, tint = inkColor,
                onShow3D = onBack, onShowPatch = onShowPatch,
                showLabelsToggle = true, showStructureToggle = true,
                showLegendToggle = true,
                background = options.background2D,
                backgroundDefault = BackgroundColorStore.DEFAULT_2D,
                backgroundPresets = BG_2D_PRESETS,
                onPickBackground = { options.background2D = it }
            )
        }

        // Recherche d'un projecteur par Fixture ID : centre + sélectionne (comme
        // le bouton loupe iOS — usage terrain « où est le #152 »).
        fun doSearch() {
            val q = query.trim()
            if (q.isEmpty()) return
            val i = data.fixtures.indexOfFirst { it.id == q }
                .let { if (it >= 0) it else data.fixtures.indexOfFirst { f -> f.id?.contains(q, true) == true } }
            if (i < 0) return
            selected.clear(); selected.add(i)
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
            // Couleurs pilotées par le fond (le thème ne suit pas la couleur de
            // fond choisie) → sinon le champ est illisible sur fond sombre.
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = inkColor, unfocusedTextColor = inkColor,
                cursorColor = inkColor,
                focusedBorderColor = inkColor.copy(alpha = 0.7f),
                unfocusedBorderColor = inkColor.copy(alpha = 0.4f),
                focusedLeadingIconColor = inkColor, unfocusedLeadingIconColor = inkColor.copy(alpha = 0.7f),
                focusedPlaceholderColor = inkColor.copy(alpha = 0.6f),
                unfocusedPlaceholderColor = inkColor.copy(alpha = 0.6f)
            ),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 8.dp).width(170.dp)
        )

        // Barre d'outils (bas gauche) : rectangle, position GPS, calibrage.
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledIconToggleButton(checked = rectMode, onCheckedChange = { rectMode = it }) {
                Icon(Icons.Filled.Crop, contentDescription = "Sélection rectangle")
            }
            if (selected.isNotEmpty()) {
                FilledIconButton(onClick = { selected.clear() }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Effacer la sélection")
                }
            }
            FilledIconToggleButton(checked = showLocation, onCheckedChange = { showLocation = it; if (!it) calibrating = false }) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Ma position GPS")
            }
            if (showLocation) {
                FilledIconToggleButton(checked = calibrating, onCheckedChange = { calibrating = it }) {
                    Icon(Icons.Filled.Place, contentDescription = "Calibrer : je suis ici")
                }
            }
            // Fond satellite : dispo seulement une fois calibré (géo-référence).
            if (calibration.isCalibrated) {
                FilledIconToggleButton(checked = options.showSatellite, onCheckedChange = { options.showSatellite = it }) {
                    Icon(Icons.Filled.Public, contentDescription = "Fond satellite")
                }
            }
            // Plan de repère DXF : importer, ou basculer le panneau de placement.
            FilledIconToggleButton(
                checked = referencePlan != null && showDxfPanel,
                onCheckedChange = {
                    if (referencePlan == null) importLauncher.launch(arrayOf("*/*"))
                    else showDxfPanel = !showDxfPanel
                }
            ) {
                if (importing) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Layers, contentDescription = "Plan DXF")
            }
        }

        // Opacité du fond satellite : curseur flottant (impossible dans un menu).
        if (options.showSatellite && satellite != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.4f), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 78.dp).width(230.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Icon(Icons.Filled.Public, contentDescription = null, tint = Color.White, modifier = Modifier.width(18.dp))
                    androidx.compose.material3.Slider(
                        value = options.satelliteOpacity,
                        onValueChange = { options.satelliteOpacity = it },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("${(options.satelliteOpacity * 100).toInt()}%",
                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Panneau de placement du plan DXF importé.
        val rpPanel = referencePlan
        if (rpPanel != null && showDxfPanel) {
            val tf = rpPanel.transform
            val step = max(200.0, rpPanel.plan.width * 0.05)
            fun bump() { dxfVersion++ }
            Surface(
                color = Color.White, contentColor = Color(0xFF111111),
                shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 76.dp, end = 12.dp).width(230.dp)
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Plan DXF · ${rpPanel.plan.unitLabel}", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { onSetReferencePlan(null); showDxfPanel = false }) {
                            Text("Retirer", color = Color(0xFFC62828))
                        }
                    }
                    Text("${rpPanel.plan.segmentCount} segments" + if (rpPanel.plan.truncatedSegments > 0) " (+${rpPanel.plan.truncatedSegments} tronqués)" else "",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Visible", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.Switch(checked = tf.visible, onCheckedChange = { tf.visible = it; bump() })
                    }
                    // Déplacement (mm monde) : libellé au-dessus + 4 flèches.
                    val pad = androidx.compose.foundation.layout.PaddingValues(2.dp)
                    Text("Déplacer", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f), onClick = { tf.offsetX -= step; bump() }) { Text("←") }
                        androidx.compose.material3.OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f), onClick = { tf.offsetX += step; bump() }) { Text("→") }
                        androidx.compose.material3.OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f), onClick = { tf.offsetY += step; bump() }) { Text("↑") }
                        androidx.compose.material3.OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f), onClick = { tf.offsetY -= step; bump() }) { Text("↓") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Rotation", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f), onClick = { tf.rotationDeg -= 5; bump() }) { Text("−5°") }
                        androidx.compose.material3.OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f), onClick = { tf.rotationDeg += 5; bump() }) { Text("+5°") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Échelle", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f), onClick = { tf.scale /= 1.1; bump() }) { Text("÷") }
                        androidx.compose.material3.OutlinedButton(contentPadding = pad, modifier = Modifier.weight(1f), onClick = { tf.scale *= 1.1; bump() }) { Text("×") }
                    }
                }
            }
        }
        // Aide de calibrage.
        if (calibrating) {
            Surface(
                color = Color(0xFFFFC400), contentColor = Color(0xFF111111),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp, start = 16.dp, end = 16.dp)
            ) {
                Text(
                    if (gps == null) "En attente du GPS…"
                    else if (calibration.anchors.isEmpty()) "Touchez VOTRE position sur le plan (1er point)"
                    else "Touchez un 2e point (oriente + met à l'échelle)",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Fiche du bas : 1 projecteur = détail ; plusieurs = compteur.
        if (selected.size == 1) {
            val f = data.fixtures[selected.first()]
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
                        if (f.addr.isNotEmpty()) append(" · DMX ${com.minou.mvrviewer.mvr.DmxAddress.format(f.addr)}")
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else if (selected.size > 1) {
            Surface(
                color = Color(0xFFFFC400), contentColor = Color(0xFF111111),
                shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) {
                Text(
                    "${selected.size} projecteurs sélectionnés",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

private val STRUCT_COLOR = Color(0xFF9AA0A6)
private val DXF_COLOR = Color(0xB3384B66)         // bleu-gris (sous-couche, fond clair)
private val DXF_COLOR_DARK_BG = Color(0xB39FC0E4) // bleu-gris clair (fond sombre)

// Presets de couleur de fond de la vue plan (nom, ARGB) — mêmes choix qu'iOS.
private val BG_2D_PRESETS = listOf(
    "Blanc" to 0xFFFFFFFFL, "Gris clair" to 0xFFE9E9ECL, "Beige" to 0xFFF2ECDDL,
    "Anthracite" to 0xFF1C1C1EL, "Noir" to 0xFF000000L
)

private class PlanFixture(
    val px: Float, val py: Float, val id: String?, val name: String,
    val spec: String?, val layer: String, val addr: String, val mode: String?,
    /** Transform MONDE (mm) de l'objet — oriente la silhouette wireframe. */
    val world: dev.romainguy.kotlin.math.Mat4
)

/** MvrModels.Mat4 (col-majeur) → dev.romainguy Mat4. */
private fun drMat(m: FloatArray): dev.romainguy.kotlin.math.Mat4 =
    dev.romainguy.kotlin.math.Mat4(
        dev.romainguy.kotlin.math.Float4(m[0], m[1], m[2], m[3]),
        dev.romainguy.kotlin.math.Float4(m[4], m[5], m[6], m[7]),
        dev.romainguy.kotlin.math.Float4(m[8], m[9], m[10], m[11]),
        dev.romainguy.kotlin.math.Float4(m[12], m[13], m[14], m[15])
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
                    o.addresses.joinToString(","), o.gdtfMode, drMat(o.transform.m))
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
