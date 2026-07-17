package com.minou.mvrviewer.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.minou.mvrviewer.mvr.MvrGeometryRef
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.ThreeDSParser
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.Scene
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.sqrt

// Nombre max d'objets à géométrie .3ds rendus (1re tranche : bornage perf/mémoire,
// chaque mesh = un VertexBuffer Filament construit sur le thread moteur).
private const val MAX_GEOMETRY_OBJECTS = 160

/**
 * Vue 3D. Projecteurs = cubes colorés par calque ; STRUCTURE/DÉCOR = vraie
 * géométrie `.3ds` extraite du MVR, parsée (ThreeDSParser) et rendue via
 * Filament (Geometry/GeometryNode). Repère MVR (mm, Z-haut) → Filament (m, Y-haut).
 */
@Composable
fun Scene3DScreen(scene: MvrScene, mvrBytes: ByteArray, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    val center = remember(scene) { sceneCenterMm(scene) }
    val layout = remember(scene, center) { fixtureLayout(scene, center) }

    val fixtureMaterials: Map<Int, MaterialInstance> = remember(materialLoader, layout) {
        layout.colors.toSet().associateWith { materialLoader.createColorInstance(it) }
    }
    // Matériau gris de la structure (comme le gris clair par défaut iOS).
    val structureMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(AndroidColor.rgb(190, 190, 195))
    }

    // Racine persistante qui accueille toute la géométrie .3ds (attachée à la
    // scène via l'échappatoire `apply { addChildNode(...) }` du DSL).
    val geometryRoot = rememberNode(engine)

    var loadedObjects by remember(scene) { mutableIntStateOf(-1) } // -1 = pas commencé

    LaunchedEffect(scene, mvrBytes) {
        val conv = conversionMatrix(center)
        // 1) Hors thread : rassembler les refs .3ds, extraire (1 passe zip), parser.
        val jobs = withContext(Dispatchers.Default) {
            data class Ref(val world: Mat4, val fileName: String)
            val refs = ArrayList<Ref>()
            outer@ for (obj in scene.allObjects) {
                for (g in obj.geometryRefs) {
                    if (g is MvrGeometryRef.File && g.fileName.endsWith(".3ds", ignoreCase = true)) {
                        val world = conv * drMat(obj.transform.m) * drMat(g.transform.m)
                        refs.add(Ref(world, g.fileName))
                        if (refs.size >= MAX_GEOMETRY_OBJECTS) break@outer
                    }
                }
            }
            val bytesByName = MvrParser.extractEntries(mvrBytes, refs.map { it.fileName }.toSet())
            val meshCache = HashMap<String, List<ThreeDSParser.Mesh>>()
            refs.mapNotNull { r ->
                val b = bytesByName[r.fileName] ?: return@mapNotNull null
                val meshes = meshCache.getOrPut(r.fileName) {
                    runCatching { ThreeDSParser.parse(b) }.getOrDefault(emptyList())
                }
                if (meshes.isEmpty()) null else r.world to meshes
            }
        }
        // 2) Sur le thread moteur : construire les GeometryNode + attacher, par lots.
        loadedObjects = 0
        var done = 0
        for ((world, meshes) in jobs) {
            for (mesh in meshes) {
                val geom = buildGeometry(engine, mesh) ?: continue
                val node = GeometryNode(engine, geom, structureMaterial)
                node.transform = world
                geometryRoot.addChildNode(node)
            }
            done++
            if (done % 12 == 0) { loadedObjects = done; yield() }
        }
        loadedObjects = done
    }

    val camHome = Float3(0f, layout.radius * 0.7f, layout.radius * 1.9f)
    val cameraNode = rememberCameraNode(engine) { position = camHome }
    val manipulator = rememberCameraManipulator(orbitHomePosition = camHome, targetPosition = Float3(0f, 0f, 0f))

    Box(modifier = modifier.fillMaxSize()) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            cameraManipulator = manipulator
        ) {
            // Structure .3ds : la racine (remplie hors composition) attachée ici.
            Node(apply = { addChildNode(geometryRoot) })
            // Projecteurs : cubes colorés par calque.
            val s = Float3(layout.cube, layout.cube, layout.cube)
            layout.positions.forEachIndexed { i, p ->
                CubeNode(size = s, materialInstance = fixtureMaterials.getValue(layout.colors[i]), position = p)
            }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(top = 56.dp, start = 12.dp)
        ) {
            val geo = if (loadedObjects >= 0) " · ${loadedObjects} objets 3D" else " · chargement 3D…"
            Text(
                "${scene.layers.size} calque(s) · ${scene.allObjects.size} objet(s) · ${scene.fixtures.size} projecteur(s)$geo",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
        }
    }
}

// ---- Géométrie ----

/** Construit une Geometry Filament (positions + normales lissées + indices). */
private fun buildGeometry(engine: com.google.android.filament.Engine, mesh: ThreeDSParser.Mesh): Geometry? {
    val vc = mesh.vertexCount
    if (vc == 0 || mesh.triangleCount == 0) return null
    val vs = mesh.vertices
    val fi = mesh.faceIndices
    val nx = FloatArray(vc); val ny = FloatArray(vc); val nz = FloatArray(vc)
    var i = 0
    while (i + 2 < fi.size) {
        val a = fi[i]; val b = fi[i + 1]; val c = fi[i + 2]
        if (a in 0 until vc && b in 0 until vc && c in 0 until vc) {
            val ax = vs[a * 3]; val ay = vs[a * 3 + 1]; val az = vs[a * 3 + 2]
            val bx = vs[b * 3]; val by = vs[b * 3 + 1]; val bz = vs[b * 3 + 2]
            val cx = vs[c * 3]; val cy = vs[c * 3 + 1]; val cz = vs[c * 3 + 2]
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val wx = cx - ax; val wy = cy - ay; val wz = cz - az
            val fnx = uy * wz - uz * wy; val fny = uz * wx - ux * wz; val fnz = ux * wy - uy * wx
            nx[a] += fnx; ny[a] += fny; nz[a] += fnz
            nx[b] += fnx; ny[b] += fny; nz[b] += fnz
            nx[c] += fnx; ny[c] += fny; nz[c] += fnz
        }
        i += 3
    }
    val verts = ArrayList<Geometry.Vertex>(vc)
    for (v in 0 until vc) {
        val len = sqrt(nx[v] * nx[v] + ny[v] * ny[v] + nz[v] * nz[v])
        val n = if (len > 1e-6f) Float3(nx[v] / len, ny[v] / len, nz[v] / len) else Float3(0f, 0f, 1f)
        verts.add(Geometry.Vertex(position = Float3(vs[v * 3], vs[v * 3 + 1], vs[v * 3 + 2]), normal = n))
    }
    val idx = ArrayList<Int>(fi.size)
    for (f in fi) idx.add(f)
    return Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
        .vertices(verts).indices(idx).build(engine)
}

// ---- Repère / matrices ----

/** MvrModels.Mat4 (col-majeur) → dev.romainguy Mat4 (colonnes x,y,z,w). */
private fun drMat(m: FloatArray): Mat4 = Mat4(
    Float4(m[0], m[1], m[2], m[3]),
    Float4(m[4], m[5], m[6], m[7]),
    Float4(m[8], m[9], m[10], m[11]),
    Float4(m[12], m[13], m[14], m[15])
)

/**
 * Conversion MVR (mm, Z-haut, centré sur `center`) → Filament (m, Y-haut).
 * C·p = Rx(−90°) · échelle(0.001) · (p − center). Colonnes ci-dessous.
 */
private fun conversionMatrix(center: Float3): Mat4 {
    val cx = center.x; val cy = center.y; val cz = center.z
    return Mat4(
        Float4(0.001f, 0f, 0f, 0f),
        Float4(0f, 0f, -0.001f, 0f),
        Float4(0f, 0.001f, 0f, 0f),
        Float4(-0.001f * cx, -0.001f * cz, 0.001f * cy, 1f)
    )
}

// ---- Projecteurs (cubes) ----

private class FixtureLayout(val positions: List<Float3>, val colors: List<Int>, val radius: Float, val cube: Float)

private val LAYER_PALETTE = intArrayOf(
    0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF3949AB.toInt(), 0xFF1E88E5.toInt(),
    0xFF00ACC1.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(), 0xFFFB8C00.toInt(),
    0xFF6D4C41.toInt(), 0xFFEC407A.toInt(), 0xFF26A69A.toInt(), 0xFF7CB342.toInt()
)

/** Barycentre (mm) des projecteurs (repli : tous les objets). */
private fun sceneCenterMm(scene: MvrScene): Float3 {
    val src = scene.fixtures.ifEmpty { scene.allObjects }
    var cx = 0f; var cy = 0f; var cz = 0f; var n = 0
    for (o in src) {
        val t = o.transform.translation
        if (t[0].isFinite() && t[1].isFinite() && t[2].isFinite()) { cx += t[0]; cy += t[1]; cz += t[2]; n++ }
    }
    return if (n == 0) Float3(0f, 0f, 0f) else Float3(cx / n, cy / n, cz / n)
}

private fun fixtureLayout(scene: MvrScene, center: Float3): FixtureLayout {
    val layerIndex = scene.layers.withIndex().associate { (i, l) -> l.name to i }
    val src = scene.fixtures.ifEmpty { scene.allObjects.take(2000) }
    val positions = ArrayList<Float3>(); val colors = ArrayList<Int>()
    for (o in src) {
        val t = o.transform.translation
        if (!(t[0].isFinite() && t[1].isFinite() && t[2].isFinite())) continue
        positions.add(Float3((t[0] - center.x) / 1000f, (t[2] - center.z) / 1000f, -(t[1] - center.y) / 1000f))
        colors.add(LAYER_PALETTE[(layerIndex[o.layerName] ?: 0) % LAYER_PALETTE.size])
    }
    var r = 0f
    for (p in positions) { val d = sqrt(p.x * p.x + p.y * p.y + p.z * p.z); if (d > r) r = d }
    if (r <= 0f || !r.isFinite()) r = 5f
    return FixtureLayout(positions, colors, r, (r * 0.03f).coerceIn(0.1f, 1.5f))
}
