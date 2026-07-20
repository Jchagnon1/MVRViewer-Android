package com.minou.mvrviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
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
import kotlin.math.pow

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
    onTransformChanged: (com.minou.mvrviewer.mvr.ReferencePlanTransform) -> Unit = {},
    hiddenLayers: Set<String> = emptySet(),
    onToggleLayer: (String) -> Unit = {},
    gdtfOverrides: GdtfOverrides? = null,
    onShowAccount: (() -> Unit)? = null,
    onShareProject: (() -> Unit)? = null,
    onShowHistory: (() -> Unit)? = null,
    onJoinProject: (() -> Unit)? = null,
    onBack: () -> Unit,
    onShowPatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctxPlan = androidx.compose.ui.platform.LocalContext.current
    val layerIndex = remember(scene) { LayerColors.index(scene) }
    val data = remember(scene) { planData(scene) }
    val measurer = rememberTextMeasurer()
    // Étiquettes déjà mesurées (le cache interne du TextMeasurer ne retient que
    // 8 entrées → sans ça, chaque étiquette est re-mise en page à chaque frame).
    val labelDensity = androidx.compose.ui.platform.LocalDensity.current
    val labelCache = remember(
        scene, options.labelSize, options.background2D, options.labelContent,
        labelDensity.density, labelDensity.fontScale
    ) { HashMap<String, androidx.compose.ui.text.TextLayoutResult>() }

    // Fil de fer VECTORIEL des structures (arêtes caractéristiques réelles de la
    // géométrie .3ds, comme iOS). Construit hors thread principal.
    // CACHÉ au niveau processus : sans ça, chaque aller-retour 3D↔plan relançait
    // le dézip + le parse .3ds + l'extraction d'arêtes de TOUT le show (des
    // dizaines de secondes sur un gros fichier) — c'était la « lenteur
    // d'affichage des ponts ». On ne remet PAS à null pendant la reconstruction :
    // l'ancien fil de fer reste à l'écran au lieu de retomber sur des points.
    var wire by remember(scene) { mutableStateOf(PlanWireCache.structures(scene)) }
    LaunchedEffect(scene, mvrBytes) {
        if (wire != null) return@LaunchedEffect
        wire = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            PlanWireCache.buildStructures(scene, mvrBytes)
        }
    }
    // Silhouette fil de fer des PROJECTEURS (modèle GDTF par spec, comme iOS).
    // Reconstruit quand un modèle GDTF Share est appliqué (version bump).
    val ovVersion = gdtfOverrides?.version ?: 0
    var fixWire by remember(scene) { mutableStateOf(PlanWireCache.fixtures(scene, ovVersion)) }
    LaunchedEffect(scene, mvrBytes, ovVersion) {
        if (fixWire != null && PlanWireCache.fixturesFresh(scene, ovVersion)) return@LaunchedEffect
        fixWire = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            PlanWireCache.buildFixtures(scene, mvrBytes, ovVersion, gdtfOverrides?.map?.toMap() ?: emptyMap())
        }
    }
    // Pendant un geste (pan/zoom), on retombe sur un point par structure (max
    // fluidité) ; le fil de fer complet réapparaît 180 ms après le dernier geste.
    //
    // ATTENTION : la version précédente incrémentait un compteur d'état à CHAQUE
    // événement tactile, et ce compteur était lu au niveau du composable (clé de
    // LaunchedEffect) → tout PlanScreen se recomposait à la fréquence du doigt,
    // et une coroutine était annulée/relancée des centaines de fois par seconde.
    // Ici l'horloge du geste est un simple LongArray (pas un état observable) et
    // `gesturing` ne bascule que DEUX fois par geste.
    var gesturing by remember { mutableStateOf(false) }
    val gestureClock = remember { longArrayOf(0L) }
    LaunchedEffect(gesturing) {
        if (!gesturing) return@LaunchedEffect
        while (true) {
            val idle = android.os.SystemClock.uptimeMillis() - gestureClock[0]
            if (idle >= 180L) { gesturing = false; break }
            kotlinx.coroutines.delay(180L - idle)
        }
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

    // ---- Tracés PRÉ-CONSTRUITS (cf. PlanPathCache) ----
    // Tout ce qui ne dépend pas du zoom/pan est fabriqué UNE fois, hors thread
    // principal, en coordonnées plan (ou locales pour le DXF). Le dessin n'est
    // plus qu'une matrice appliquée au Canvas : plus aucun calcul par sommet à
    // 60 Hz. C'était la cause n°1 des saccades au pan/zoom.
    val structPaths by produceState<StructPaths?>(null, wire) {
        val wf = wire
        value = if (wf == null || wf.isEmpty) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildStructurePaths(wf)
        }
    }
    // Structures sans fil de fer (géométrie .glb) → un point ; et le repli
    // « un point par objet » utilisé pendant les gestes.
    val structDots = remember(wire) { dotsPath(structureDots(wire)) }
    val fallbackDots = remember(data) { dotsPath(data.structure) }
    val fixPaths by produceState<FixturePaths?>(null, fixWire, data) {
        val fw = fixWire
        value = if (fw == null) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildFixturePaths(data, fw)
        }
    }
    // Le DXF est tracé dans SES coordonnées locales : placement (décalage /
    // rotation / échelle) et zoom deviennent une matrice → déplacer le plan de
    // repère aux curseurs ne reconstruit plus rien.
    val dxfPaths by produceState<DxfPaths?>(null, referencePlan?.plan) {
        val plan = referencePlan?.plan
        value = if (plan == null) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildDxfPaths(plan)
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
    // Historique court des relevés → moyenne pondérée par 1/précision au calibrage
    // (l'utilisateur est immobile ; ça enlève la gigue GPS de la ligne de base).
    val recentFixes = remember { ArrayList<android.location.Location>() }
    LaunchedEffect(gps) {
        gps?.let { f ->
            if (f.latitude.isFinite() && f.longitude.isFinite()) {
                recentFixes.add(f); if (recentFixes.size > 12) recentFixes.removeAt(0)
            }
        }
    }
    fun averagedLatLon(): Pair<Double, Double>? {
        if (recentFixes.isEmpty()) return null
        var wsum = 0.0; var lat = 0.0; var lon = 0.0
        for (f in recentFixes) {
            val w = 1.0 / maxOf(1.0, f.accuracy.toDouble())
            wsum += w; lat += f.latitude * w; lon += f.longitude * w
        }
        return if (wsum > 0) lat / wsum to lon / wsum else null
    }

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
                // SYNCHRO : après le débounce, pousse le placement au cloud (item 5).
                onTransformChanged(rpForSave.transform)
            }
        }
        LaunchedEffect(calibVersion) {
            if (calibVersion > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.minou.mvrviewer.mvr.ProjectStore.saveCalibration(ctxPlan, projectKey, calibration.anchors.toList())
            }
        }
        // Affichage de la position GPS : persisté par projet (survit à la bascule
        // plan↔3D et à la réouverture), comme le drapeau satellite.
        LaunchedEffect(Unit) {
            showLocation = com.minou.mvrviewer.mvr.ProjectStore.loadShowUserLocation(ctxPlan, projectKey)
        }
        LaunchedEffect(showLocation) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.minou.mvrviewer.mvr.ProjectStore.saveShowUserLocation(ctxPlan, projectKey, showLocation)
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
                            val w = canvas.x; val h = canvas.y
                            val old = scale
                            // Pinch AMPLIFIÉ : le facteur brut demandait beaucoup trop
                            // de course de doigts sur un grand plan.
                            val z = if (zoom > 0f) zoom.pow(ZOOM_SPEED) else 1f
                            val new = (old * z).coerceIn(0.05f, 200f)
                            val bs0 = baseScale(w, h) * old
                            val bs1 = baseScale(w, h) * new
                            if (w > 0f && h > 0f && bs0 > 0f && new != old) {
                                // Le zoom garde un POINT D'ANCRAGE fixe à l'écran : le
                                // projecteur sélectionné, sinon le centre de la fenêtre.
                                // (Avant : ancré sur le centre du CONTENU, qui part hors
                                // cadre dès qu'on déplace le plan → le zoom « fuyait ».)
                                val sel = selected.firstOrNull()?.let { data.fixtures.getOrNull(it) }
                                offset = if (sel != null) {
                                    Offset(offset.x + (bs0 - bs1) * (sel.px - data.cx),
                                           offset.y + (bs0 - bs1) * (sel.py - data.cy))
                                } else {
                                    // Centre fenêtre : l'ancrage se réduit à une homothétie.
                                    Offset(offset.x * (bs1 / bs0), offset.y * (bs1 / bs0))
                                }
                            }
                            scale = new
                            offset += pan
                            // Horloge du geste : un tableau ordinaire, PAS un état
                            // observable → aucun recomposition par événement tactile.
                            // `gesturing` ne change qu'aux deux transitions.
                            gestureClock[0] = android.os.SystemClock.uptimeMillis()
                            gesturing = true
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
                            val avg = averagedLatLon() ?: (g.latitude to g.longitude)
                            calibration.addAnchor(
                                com.minou.mvrviewer.mvr.GeoAnchor(px, -py, avg.first, avg.second)
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
            // Les tracés sont déjà construits (coordonnées locales du DXF) : ici
            // on ne fait qu'empiler placement du DXF + projection plan→écran
            // dans la matrice du Canvas. Rien n'est recalculé par sommet.
            dxfVersion.let { }  // dépendance de redraw (transformée mutable)
            val rp = referencePlan
            val dxf = dxfPaths
            val bsPlan = baseScale(w, h) * scale
            // Un plan léger reste affiché PENDANT le geste (plus de clignotement
            // au pan/zoom) ; un plan lourd s'efface encore, pour tenir 60 fps.
            if (rp != null && dxf != null && rp.transform.visible && bsPlan > 0f &&
                (!gesturing || dxf.light)) {
                val tf = rp.transform
                val sfac = tf.scale.toFloat()
                val ppu = bsPlan * sfac              // pixels par unité locale du DXF
                withTransform({
                    translate(w / 2f + offset.x, h / 2f + offset.y)
                    scale(bsPlan, bsPlan, Offset.Zero)
                    translate(-data.cx, -data.cy)
                    // Repère plan = (x, −y) : la rotation du placement s'y lit
                    // donc à l'envers, et le décalage Y aussi.
                    translate(tf.offsetX.toFloat(), -tf.offsetY.toFloat())
                    rotate(-tf.rotationDeg.toFloat(), Offset.Zero)
                    scale(sfac, sfac, Offset.Zero)
                }) {
                    // Zones remplies (HATCH/SOLID) SOUS les traits, en
                    // semi-transparent pour ne pas masquer projecteurs et décor.
                    for ((key, fp) in dxf.fills) {
                        val (layer, rgb, solid) = key
                        if (layer in hiddenLayers) continue
                        drawPath(fp, dxfDisplayColor(rgb, bgDark).copy(alpha = if (solid) 0.40f else 0.20f))
                    }
                    // Un tracé par couleur d'entité résolue (ACI/true-color) →
                    // couleurs d'AutoCAD, adaptées au fond (contraste).
                    if (ppu > 0f) {
                        val sw = 0.7f / ppu   // épaisseur constante À L'ÉCRAN
                        for ((key, path) in dxf.strokes) {
                            val (layer, rgb) = key
                            if (layer in hiddenLayers) continue
                            drawPath(path, dxfDisplayColor(rgb, bgDark),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                        }
                    }
                }
            }

            // Décor / structure : FIL DE FER VECTORIEL (arêtes caractéristiques
            // réelles de la géométrie 3D). Pendant un geste, ou tant que le fil
            // de fer se construit, on retombe sur un point par structure.
            if (options.showStructure && bsPlan > 0f) {
                val sp = structPaths
                withTransform({
                    translate(w / 2f + offset.x, h / 2f + offset.y)
                    scale(bsPlan, bsPlan, Offset.Zero)
                    translate(-data.cx, -data.cy)
                }) {
                    val wireOk = sp != null && (!gesturing || sp.light)
                    if (wireOk) {
                        val sw = 0.8f / bsPlan
                        for ((layer, path) in sp.byLayer) {
                            val col = if (options.layerColors) Color(LayerColors.colorInt(layerIndex, layer)) else STRUCT_COLOR
                            drawPath(path, col, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                        }
                    }
                    // Structures sans fil de fer (ou repli pendant un geste) : un
                    // point chacune, en UN SEUL tracé (Skia élague hors écran).
                    val dots = if (wireOk) structDots else fallbackDots
                    drawPath(dots, STRUCT_COLOR, style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3.2f / bsPlan, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }

            // Projecteurs : SILHOUETTE FIL DE FER réelle (modèle GDTF projeté,
            // comme iOS) quand elle est lisible à ce zoom, sinon pastille.
            // PASSE 1 : silhouettes (un stroke par calque), avec un cull qui
            // tient compte du RAYON écran — sinon une silhouette zoomée
            // disparaîtrait dès que l'origine du projecteur sort du cadre.
            val showLabels = options.showLabels && bsPlan > 0.02f
            val fw = fixWire
            val bsNow = bsPlan
            val fp = fixPaths
            // Silhouette LISIBLE pour cette spec à ce zoom ? (sinon : pastille)
            // On exige que le TRACÉ existe déjà (il est construit hors thread
            // principal, et le budget mémoire peut écarter une spec) : sinon la
            // pastille serait réduite à 3 px alors que rien n'est dessiné.
            fun silhouetteVisible(spec: String?): Boolean {
                if (gesturing || fw == null || fp == null || spec == null) return false
                val s = spec.trim()
                if (s !in fp.specs || fw.edgesBySpec[s] == null) return false
                return (fw.radiusBySpec[s] ?: 0f) * bsNow > 7f
            }
            if (!gesturing && fp != null && bsPlan > 0f) {
                withTransform({
                    translate(w / 2f + offset.x, h / 2f + offset.y)
                    scale(bsPlan, bsPlan, Offset.Zero)
                    translate(-data.cx, -data.cy)
                }) {
                    val sw = 1.2f / bsPlan
                    for ((key, path) in fp.byKey) {
                        val sep = key.indexOf(PATH_KEY_SEP)
                        if (sep < 0) continue
                        val layer = key.substring(0, sep)
                        if (!silhouetteVisible(key.substring(sep + 1))) continue
                        val c = if (options.layerColors) Color(LayerColors.colorInt(layerIndex, layer)) else Color(0xFF6E6E73)
                        drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                    }
                }
            }
            // PASSE 2 : pastilles / anneaux de sélection / étiquettes, PAR-DESSUS
            // les silhouettes (lisibilité).
            data.fixtures.forEachIndexed { i, f ->
                val s = toScreen(f.px, f.py, w, h)
                if (s.x !in -40f..w + 40f || s.y !in -40f..h + 40f) return@forEachIndexed
                val c = if (options.layerColors) Color(LayerColors.colorInt(layerIndex, f.layer)) else Color(0xFF6E6E73)
                if (silhouetteVisible(f.spec)) {
                    drawCircle(c, radius = 3f, center = s)
                } else {
                    drawCircle(c, radius = 7f, center = s)
                    drawCircle(Color.White, radius = 7f, center = s, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                }
                if (i in selected) {
                    drawCircle(Color(0xFFFFC400), radius = 13f, center = s,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                }
                if (showLabels && !gesturing) {
                    val text = when (options.labelContent) {
                        LabelContent.ID -> f.id?.let { "#$it" }
                        LabelContent.DMX -> f.addr.ifEmpty { null }?.let { com.minou.mvrviewer.mvr.DmxAddress.format(it) }
                        LabelContent.MODE -> f.mode?.ifEmpty { null }
                        LabelContent.NAME -> f.name
                    }
                    if (text != null) {
                        val fs = (9f * options.labelSize)
                        val off = 8f * options.labelOffset
                        // Mesurer un texte est cher : le cache LRU par défaut du
                        // TextMeasurer ne fait que 8 entrées, donc chaque étiquette
                        // était re-composée À CHAQUE FRAME. Ici on mémorise le
                        // résultat par texte (vidé si la taille/l'encre change).
                        val tl = labelCache.getOrPut(text) {
                            measurer.measure(text, style = TextStyle(fontSize = fs.sp, color = inkColor))
                        }
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
                    val onScreen = s.x in 0f..w && s.y in 0f..h
                    if (onScreen) {
                        // Cercle de PRÉCISION dimensionné sur la vraie exactitude GPS.
                        val bsNow = baseScale(w, h) * scale
                        val accPx = (calibration.planMillimeters(g.accuracy.toDouble()) * bsNow)
                            .toFloat().coerceIn(14f, 400f)
                        drawCircle(Color(0x332979FF), radius = accPx, center = s)
                        drawCircle(Color(0xFF2979FF), radius = 9f, center = s)
                        drawCircle(Color.White, radius = 9f, center = s,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
                    } else {
                        // Hors cadre : flèche de bord pointant vers la position.
                        val inset = 30f
                        val cxs = w / 2f; val cys = h / 2f
                        val dx = s.x - cxs; val dy = s.y - cys
                        val rx = w / 2f - inset; val ry = h / 2f - inset
                        val tScale = minOf(
                            if (dx != 0f) rx / kotlin.math.abs(dx) else Float.MAX_VALUE,
                            if (dy != 0f) ry / kotlin.math.abs(dy) else Float.MAX_VALUE
                        )
                        val ex = cxs + dx * tScale; val ey = cys + dy * tScale
                        val ang = kotlin.math.atan2(dy, dx)
                        drawCircle(Color(0xFF2979FF), radius = 12f, center = Offset(ex, ey))
                        drawCircle(Color.White, radius = 12f, center = Offset(ex, ey),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                        val tri = androidx.compose.ui.graphics.Path().apply {
                            moveTo(ex + 17f * kotlin.math.cos(ang), ey + 17f * kotlin.math.sin(ang))
                            lineTo(ex + 9f * kotlin.math.cos(ang + 2.5f), ey + 9f * kotlin.math.sin(ang + 2.5f))
                            lineTo(ex + 9f * kotlin.math.cos(ang - 2.5f), ey + 9f * kotlin.math.sin(ang - 2.5f))
                            close()
                        }
                        drawPath(tri, Color(0xFF2979FF))
                    }
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

            // ---- Barre d'échelle (bas-gauche) : longueur « ronde » à l'échelle courante.
            run {
                val ppm = baseScale(w, h) * scale // pixels par mm
                if (ppm > 1e-6f) {
                    val rawMm = 90f / ppm
                    val mag = Math.pow(10.0, Math.floor(Math.log10(rawMm.toDouble()))).toFloat()
                    val niceMm = when {
                        rawMm / mag < 1.5f -> 1f * mag
                        rawMm / mag < 3.5f -> 2f * mag
                        rawMm / mag < 7.5f -> 5f * mag
                        else -> 10f * mag
                    }
                    val barPx = niceMm * ppm
                    val x0 = 20f; val y0 = h - 172f
                    drawLine(inkColor, Offset(x0, y0), Offset(x0 + barPx, y0), strokeWidth = 2.5f)
                    drawLine(inkColor, Offset(x0, y0 - 5f), Offset(x0, y0 + 5f), strokeWidth = 2.5f)
                    drawLine(inkColor, Offset(x0 + barPx, y0 - 5f), Offset(x0 + barPx, y0 + 5f), strokeWidth = 2.5f)
                    val label = if (niceMm >= 1000f) {
                        val m = niceMm / 1000f
                        (if (m % 1f == 0f) m.toInt().toString() else m.toString()) + " m"
                    } else "${niceMm.toInt()} mm"
                    val tl = measurer.measure(label, style = TextStyle(fontSize = 11.sp, color = inkColor))
                    drawText(tl, topLeft = Offset(x0, y0 - 22f))
                }
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
                onShowAccount = onShowAccount, onShareProject = onShareProject,
                onShowHistory = onShowHistory, onJoinProject = onJoinProject,
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
                    // Calques du plan DXF : masquer/afficher (rend lisible un plan
                    // d'architecte surchargé). L'état est persisté et synchronisé.
                    val dxfLayers = remember(rpPanel) {
                        rpPanel.plan.layerCounts.entries.sortedByDescending { it.value }
                    }
                    if (dxfLayers.isNotEmpty()) {
                        Text("Calques (${dxfLayers.size})", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        androidx.compose.foundation.layout.Column(
                            Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())
                        ) {
                            dxfLayers.forEach { (layer, count) ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { onToggleLayer(layer) }) {
                                    Checkbox(
                                        checked = layer !in hiddenLayers,
                                        onCheckedChange = { onToggleLayer(layer) },
                                        modifier = Modifier.size(30.dp)
                                    )
                                    rpPanel.plan.layerColors[layer]?.let { lc ->
                                        androidx.compose.foundation.layout.Box(
                                            Modifier.size(11.dp).clip(RoundedCornerShape(2.dp))
                                                .background(dxfDisplayColor(lc, bgDark)))
                                    }
                                    Text(layer, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(start = 6.dp))
                                    Text("$count", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
                                }
                            }
                        }
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

/** Couleur d'affichage d'une entité DXF (0xRRGGBB) adaptée au fond : sur fond
 *  sombre on éclaircit les couleurs quasi noires, sur fond clair on assombrit le
 *  blanc — comme l'adaptation de contraste iOS. */
private fun dxfDisplayColor(rgb: Int, bgDark: Boolean): Color {
    val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
    val lum = 0.299 * r + 0.587 * g + 0.114 * b
    return when {
        bgDark && lum < 60 -> Color(
            minOf(255, (r * 2.2f).toInt() + 40),
            minOf(255, (g * 2.2f).toInt() + 40),
            minOf(255, (b * 2.2f).toInt() + 40))
        !bgDark && lum > 205 -> Color((r * 0.55f).toInt(), (g * 0.55f).toInt(), (b * 0.55f).toInt())
        else -> Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))
    }
}

/** Amplification du pinch (1 = brut). 2 = un pincement donne un zoom au carré. */
private const val ZOOM_SPEED = 2.0f

private val STRUCT_COLOR = Color(0xFF9AA0A6)
private val DXF_COLOR = Color(0xB3384B66)         // bleu-gris (sous-couche, fond clair)
private val DXF_COLOR_DARK_BG = Color(0xB39FC0E4) // bleu-gris clair (fond sombre)

// Presets de couleur de fond de la vue plan (nom, ARGB) — mêmes choix qu'iOS.
private val BG_2D_PRESETS = listOf(
    "Blanc" to 0xFFFFFFFFL, "Gris clair" to 0xFFE9E9ECL, "Beige" to 0xFFF2ECDDL,
    "Anthracite" to 0xFF1C1C1EL, "Noir" to 0xFF000000L
)

// ---------------------------------------------------------------------------
// Cache des fils de fer + tracés pré-construits
// ---------------------------------------------------------------------------

/**
 * Fils de fer conservés au niveau PROCESSUS.
 *
 * Le fil de fer des structures coûte un dézip du .mvr + un parse de chaque .3ds
 * + une extraction d'arêtes : des dizaines de secondes sur un gros show. Il
 * était refait à CHAQUE entrée dans la vue plan (donc à chaque aller-retour
 * 3D↔plan), ce qui donnait « les ponts mettent très longtemps à s'afficher ».
 * On garde la dernière scène construite — comme le fait déjà la vue 3D pour sa
 * géométrie (Prepared3DCache).
 */
private object PlanWireCache {
    // Références FAIBLES/SOUPLES : sur un gros show ces tableaux d'arêtes pèsent
    // lourd, et l'app a un historique d'OOM. La scène n'est pas retenue (weak),
    // et le fil de fer est rendu au GC en cas de pression mémoire (soft) — on
    // le reconstruira alors, comme avant.
    private var structScene: java.lang.ref.WeakReference<MvrScene>? = null
    private var structBuilt: java.lang.ref.SoftReference<PlanWireframe.Built>? = null
    private var fixScene: java.lang.ref.WeakReference<MvrScene>? = null
    private var fixVersion: Int = -1
    private var fixBuilt: java.lang.ref.SoftReference<PlanWireframe.FixtureWire>? = null

    @Synchronized
    fun structures(scene: MvrScene): PlanWireframe.Built? =
        if (structScene?.get() === scene) structBuilt?.get() else null

    // @Synchronized sur TOUTE la construction : deux entrées simultanées dans la
    // vue plan (aller-retour rapide 3D↔plan) lançaient sinon deux constructions
    // complètes en parallèle → pic mémoire doublé.
    @Synchronized
    fun buildStructures(scene: MvrScene, mvrBytes: ByteArray): PlanWireframe.Built {
        structures(scene)?.let { return it }
        val built = PlanWireframe.build(scene, mvrBytes)
        structScene = java.lang.ref.WeakReference(scene)
        structBuilt = java.lang.ref.SoftReference(built)
        return built
    }

    @Synchronized
    fun fixturesFresh(scene: MvrScene, version: Int): Boolean =
        fixScene?.get() === scene && fixVersion == version && fixBuilt?.get() != null

    @Synchronized
    fun fixtures(scene: MvrScene, version: Int): PlanWireframe.FixtureWire? =
        if (fixScene?.get() === scene && fixVersion == version) fixBuilt?.get() else null

    @Synchronized
    fun buildFixtures(
        scene: MvrScene, mvrBytes: ByteArray, version: Int, overrides: Map<String, ByteArray>
    ): PlanWireframe.FixtureWire {
        fixtures(scene, version)?.let { return it }
        val built = PlanWireframe.buildFixtures(scene, mvrBytes, overrides)
        fixScene = java.lang.ref.WeakReference(scene)
        fixVersion = version
        fixBuilt = java.lang.ref.SoftReference(built)
        return built
    }
}

/** Tracés DXF pré-construits, en coordonnées LOCALES du DXF (y déjà inversé). */
private class DxfPaths(
    /** (calque, couleur) → tracé : masquer un calque ne reconstruit RIEN. */
    val strokes: Map<Pair<String, Int>, androidx.compose.ui.graphics.Path>,
    /** (calque, couleur, plein) → zone remplie. */
    val fills: Map<Triple<String, Int, Boolean>, androidx.compose.ui.graphics.Path>,
    val points: Int
) {
    /** Assez léger pour RESTER dessiné pendant un pan/zoom (sinon il s'efface). */
    val light: Boolean get() = points < 60_000
}

/** Fil de fer des structures prêt à dessiner (coordonnées plan). */
private class StructPaths(
    val byLayer: Map<String, androidx.compose.ui.graphics.Path>,
    val edges: Int
) {
    val light: Boolean get() = edges < 40_000
}

/** Sépare calque et spec dans les clés de `buildFixturePaths`. */
private const val PATH_KEY_SEP = '\u0000'

/**
 * Arêtes des structures, transformées en coordonnées PLAN une fois pour toutes
 * (un tracé par calque). Avant, ces multiplications matricielles étaient
 * refaites pour chaque arête à CHAQUE frame.
 */
private fun buildStructurePaths(wf: PlanWireframe.Built): StructPaths {
    val out = HashMap<String, androidx.compose.ui.graphics.Path>()
    var count = 0
    for (inst in wf.instances) {
        val edges = wf.edgesByKey[inst.key] ?: continue
        val world = inst.world
        if (count > MAX_STRUCT_PATH_SEGMENTS) break   // garde-fou mémoire
        val path = out.getOrPut(inst.layer) { androidx.compose.ui.graphics.Path() }
        count += edges.size / 6
        var i = 0
        while (i + 5 < edges.size) {
            val ax = edges[i]; val ay = edges[i + 1]; val az = edges[i + 2]
            val bx = edges[i + 3]; val by = edges[i + 4]; val bz = edges[i + 5]
            i += 6
            val wax = world.x.x * ax + world.y.x * ay + world.z.x * az + world.w.x
            val way = world.x.y * ax + world.y.y * ay + world.z.y * az + world.w.y
            val wbx = world.x.x * bx + world.y.x * by + world.z.x * bz + world.w.x
            val wby = world.x.y * bx + world.y.y * by + world.z.y * bz + world.w.y
            path.moveTo(wax, -way); path.lineTo(wbx, -wby)
        }
    }
    return StructPaths(out, count)
}

/**
 * Structures SANS fil de fer (ex. géométrie .glb) → un point chacune, sous forme
 * de TRACÉ : un sous-chemin de longueur nulle dessiné en bout rond donne un
 * point, et Skia élague ce qui sort de l'écran. (Un `drawPoints` aurait fait un
 * appel natif par point — c'est ce que faisait déjà l'ancienne boucle de
 * `drawCircle`, mais sans profiter de l'élagage.)
 */
private fun dotsPath(points: List<Pair<Float, Float>>): androidx.compose.ui.graphics.Path {
    val p = androidx.compose.ui.graphics.Path()
    for ((x, y) in points) { p.moveTo(x, y); p.lineTo(x, y) }
    return p
}

private fun structureDots(wf: PlanWireframe.Built?): List<Pair<Float, Float>> =
    if (wf == null || wf.isEmpty) emptyList()
    else wf.instances.mapNotNull {
        if (wf.edgesByKey[it.key] == null) it.cx to it.cy else null
    }

/** Silhouettes des projecteurs, prêtes à dessiner. */
private class FixturePaths(
    val byKey: Map<String, androidx.compose.ui.graphics.Path>,
    /** Specs réellement TRACÉES (le budget peut en écarter) → pastille sinon. */
    val specs: Set<String>,
    val segments: Int
)

/**
 * Silhouettes des projecteurs en coordonnées PLAN, un tracé par
 * « calque + spec » : la lisibilité de la silhouette dépend du zoom et se
 * décide donc au dessin, spec par spec, sans rien reconstruire.
 *
 * BUDGET : chaque projecteur recopie les arêtes de sa spec ; sur un très gros
 * show cela peut représenter des centaines de Mo de SkPath natif (l'app a un
 * historique d'OOM). Au-delà du budget, les specs restantes ne sont pas
 * tracées — les projecteurs concernés gardent leur pastille.
 */
private fun buildFixturePaths(
    data: PlanData, fw: PlanWireframe.FixtureWire
): FixturePaths {
    val out = HashMap<String, androidx.compose.ui.graphics.Path>()
    val specs = HashSet<String>()
    var segments = 0
    for (f in data.fixtures) {
        val spec = f.spec?.trim() ?: continue
        val edges = fw.edgesBySpec[spec] ?: continue
        // Budget dépassé : on n'ouvre plus de nouvelle spec, mais on finit
        // celles déjà commencées (sinon des projecteurs identiques seraient
        // dessinés différemment selon leur rang dans la liste).
        if (segments > MAX_FIXTURE_PATH_SEGMENTS && spec !in specs) continue
        val world = f.world
        specs.add(spec)
        val path = out.getOrPut(f.layer + PATH_KEY_SEP + spec) { androidx.compose.ui.graphics.Path() }
        segments += edges.size / 6
        var k = 0
        while (k + 5 < edges.size) {
            val ax = edges[k]; val ay = edges[k + 1]; val az = edges[k + 2]
            val bx = edges[k + 3]; val by = edges[k + 4]; val bz = edges[k + 5]
            k += 6
            val wax = world.x.x * ax + world.y.x * ay + world.z.x * az + world.w.x
            val way = world.x.y * ax + world.y.y * ay + world.z.y * az + world.w.y
            val wbx = world.x.x * bx + world.y.x * by + world.z.x * bz + world.w.x
            val wby = world.x.y * bx + world.y.y * by + world.z.y * bz + world.w.y
            path.moveTo(wax, -way); path.lineTo(wbx, -wby)
        }
    }
    return FixturePaths(out, specs, segments)
}

/** ~14 Mo de SkPath au maximum pour les silhouettes de projecteurs. */
private const val MAX_FIXTURE_PATH_SEGMENTS = 800_000

/** Idem pour le fil de fer du décor. */
private const val MAX_STRUCT_PATH_SEGMENTS = 800_000

/**
 * Tracés DXF, dans les coordonnées LOCALES du fichier avec l'axe Y déjà inversé
 * (repère « plan »). Le placement (décalage / rotation / échelle) est appliqué
 * au dessin sous forme de matrice : le régler aux curseurs ne coûte plus rien.
 */
private fun buildDxfPaths(plan: com.minou.mvrviewer.mvr.DxfPlan): DxfPaths {
    val fills = HashMap<Triple<String, Int, Boolean>, androidx.compose.ui.graphics.Path>()
    for (fl in plan.fills) {
        val fp = fills.getOrPut(Triple(fl.layer, fl.color, fl.solid)) {
            androidx.compose.ui.graphics.Path().apply {
                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            }
        }
        for (ring in fl.rings) {
            if (ring.size < 6) continue
            var k = 0
            var first = true
            while (k + 1 < ring.size) {
                val x = ring[k]; val y = -ring[k + 1]; k += 2
                if (first) { fp.moveTo(x, y); first = false } else fp.lineTo(x, y)
            }
            fp.close()
        }
    }
    val strokes = HashMap<Pair<String, Int>, androidx.compose.ui.graphics.Path>()
    var drawn = 0
    loop@ for (pl in plan.polylines) {
        val path = strokes.getOrPut(pl.layer to pl.color) { androidx.compose.ui.graphics.Path() }
        val pts = pl.points
        var i = 0
        var first = true
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = -pts[i + 1]; i += 2
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        if (pl.closed && pts.size >= 4) path.lineTo(pts[0], -pts[1])
        drawn += pts.size / 2
        if (drawn > 1_500_000) break@loop  // garde-fou mémoire (construit UNE fois)
    }
    return DxfPaths(strokes, fills, drawn)
}

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
