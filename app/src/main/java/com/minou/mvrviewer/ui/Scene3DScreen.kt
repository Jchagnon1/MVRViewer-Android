package com.minou.mvrviewer.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
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
import io.github.sceneview.loaders.MaterialLoader
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

// Garde-fous d'instanciation (émulateur/appareil : mémoire + draw calls).
// L'iOS gère les très gros shows par LOD ; ici on borne le TOTAL de triangles
// et de nœuds — au-delà, on tronque et on l'affiche honnêtement.
private const val MAX_TRIANGLES = 2_500_000L
private const val MAX_NODES = 6_000
// Même plafond pragmatique qu'iOS (SceneKitContainerView.maxSymdefItems).
private const val MAX_SYMDEF_ITEMS = 500
private const val GRAY = 0xFFBEBEC3.toInt()

/**
 * Vue 3D : structure/décor en VRAIE géométrie `.3ds` (couleurs matériaux
 * incluses), projecteurs en cubes colorés par calque. Les références Symbol →
 * Symdef sont résolues récursivement (transforms composées), et les géométries
 * PARTAGÉES entre instances (un Symdef référencé 300 fois = 1 seul
 * VertexBuffer). Repère MVR (mm, Z-haut) → Filament (m, Y-haut).
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

    val geometryRoot = rememberNode(engine)
    var status by remember(scene) { mutableStateOf("chargement 3D…") }

    LaunchedEffect(scene, mvrBytes) {
        val conv = conversionMatrix(center)
        // 1) Hors thread : résolution des refs (File + Symbol→Symdef récursif),
        //    extraction zip en 1 passe, parsing .3ds par fichier UNIQUE.
        val (refs, meshesByFile) = withContext(Dispatchers.Default) {
            val refs = collectRenderRefs(scene, conv)
            val names = refs.mapTo(HashSet()) { it.fileName }
            val bytesByName = MvrParser.extractEntries(mvrBytes, names)
            val meshes = HashMap<String, List<ThreeDSParser.Mesh>>(names.size)
            for (n in names) {
                val b = bytesByName[n] ?: continue
                meshes[n] = runCatching { ThreeDSParser.parse(b) }.getOrDefault(emptyList())
            }
            refs to meshes
        }
        // 2) Thread moteur : géométries UNIQUES par fichier (partagées), puis un
        //    GeometryNode par instance — par lots, avec budget global.
        val builtCache = HashMap<String, List<BuiltMesh>>()
        val materialCache = HashMap<Int, MaterialInstance>()
        fun material(color: Int) = materialCache.getOrPut(color) { materialLoader.createColorInstance(color) }
        var nodes = 0
        var triangles = 0L
        var placed = 0
        var truncated = false
        for (r in refs) {
            if (nodes >= MAX_NODES || triangles >= MAX_TRIANGLES) { truncated = true; break }
            val meshes = meshesByFile[r.fileName]
            if (meshes.isNullOrEmpty()) continue
            val built = builtCache.getOrPut(r.fileName) { meshes.mapNotNull { buildMesh(engine, it) } }
            for (bm in built) {
                val node = GeometryNode(engine, bm.geometry, bm.colors.map(::material))
                node.transform = r.world
                geometryRoot.addChildNode(node)
                nodes++; triangles += bm.triangles
            }
            placed++
            if (placed % 25 == 0) {
                status = "$placed objets 3D…"
                yield()
            }
        }
        status = "$placed objets 3D" + if (truncated) " (tronqué)" else ""
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
            Node(apply = { addChildNode(geometryRoot) })
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
            Text(
                "${scene.layers.size} calque(s) · ${scene.allObjects.size} objet(s) · ${scene.fixtures.size} projecteur(s) · $status",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
        }
    }
}

// ---- Résolution des références de géométrie ----

private class RenderRef(val world: Mat4, val fileName: String)

/**
 * Aplati la scène en refs rendables : Geometry3D directs + Symbol→Symdef
 * résolus récursivement (transforms composées obj·symbole·item…), garde-fou de
 * profondeur (cycles) et plafond d'items par symdef (comme iOS).
 */
private fun collectRenderRefs(scene: MvrScene, conv: Mat4): List<RenderRef> {
    val out = ArrayList<RenderRef>()
    fun walk(refs: List<MvrGeometryRef>, parent: Mat4, depth: Int) {
        if (depth > 8) return
        for (g in refs) when (g) {
            is MvrGeometryRef.File ->
                if (g.fileName.endsWith(".3ds", ignoreCase = true)) {
                    out.add(RenderRef(parent * drMat(g.transform.m), g.fileName))
                }
            is MvrGeometryRef.Symbol -> {
                val sd = scene.symdefs[g.symdefUuid] ?: continue
                walk(sd.items.take(MAX_SYMDEF_ITEMS), parent * drMat(g.transform.m), depth + 1)
            }
        }
    }
    for (obj in scene.allObjects) walk(obj.geometryRefs, conv * drMat(obj.transform.m), 0)
    return out
}

// ---- Construction de géométrie (avec couleurs matériaux, comme toSCNNode iOS) ----

private class BuiltMesh(val geometry: Geometry, val colors: List<Int>, val triangles: Int)

private fun ThreeDSParser.Rgb.toColorInt(): Int {
    fun c(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

/**
 * Mesh 3ds → Geometry Filament : positions + normales lissées, et UN sous-mesh
 * (primitive) PAR groupe matériau avec sa couleur — les faces sans matériau en
 * gris clair. Sans groupe : un seul sous-mesh (couleur de l'unique matériau du
 * fichier si présent, sinon gris). Fidèle à Mesh.toSCNNode côté iOS.
 */
private fun buildMesh(engine: Engine, mesh: ThreeDSParser.Mesh): BuiltMesh? {
    val vc = mesh.vertexCount
    val tc = mesh.triangleCount
    if (vc == 0 || tc == 0) return null
    val vs = mesh.vertices
    val fi = mesh.faceIndices

    // Normales lissées par sommet (le .3ds n'en stocke pas).
    val nx = FloatArray(vc); val ny = FloatArray(vc); val nz = FloatArray(vc)
    var i = 0
    while (i + 2 < fi.size) {
        val a = fi[i]; val b = fi[i + 1]; val c = fi[i + 2]
        if (a in 0 until vc && b in 0 until vc && c in 0 until vc) {
            val ax = vs[a * 3]; val ay = vs[a * 3 + 1]; val az = vs[a * 3 + 2]
            val ux = vs[b * 3] - ax; val uy = vs[b * 3 + 1] - ay; val uz = vs[b * 3 + 2] - az
            val wx = vs[c * 3] - ax; val wy = vs[c * 3 + 1] - ay; val wz = vs[c * 3 + 2] - az
            val fx = uy * wz - uz * wy; val fy = uz * wx - ux * wz; val fz = ux * wy - uy * wx
            nx[a] += fx; ny[a] += fy; nz[a] += fz
            nx[b] += fx; ny[b] += fy; nz[b] += fz
            nx[c] += fx; ny[c] += fy; nz[c] += fz
        }
        i += 3
    }
    val verts = ArrayList<Geometry.Vertex>(vc)
    for (v in 0 until vc) {
        val len = sqrt(nx[v] * nx[v] + ny[v] * ny[v] + nz[v] * nz[v])
        val n = if (len > 1e-6f) Float3(nx[v] / len, ny[v] / len, nz[v] / len) else Float3(0f, 0f, 1f)
        verts.add(Geometry.Vertex(position = Float3(vs[v * 3], vs[v * 3 + 1], vs[v * 3 + 2]), normal = n))
    }

    // Sous-meshes par matériau.
    val prims = ArrayList<List<Int>>()
    val colors = ArrayList<Int>()
    if (mesh.materialGroups.isEmpty()) {
        prims.add(fi.toList())
        colors.add(if (mesh.materials.size == 1) mesh.materials.values.first().toColorInt() else GRAY)
    } else {
        val assigned = BooleanArray(tc)
        for (g in mesh.materialGroups) {
            val idx = ArrayList<Int>(g.triangles.size * 3)
            for (t in g.triangles) if (t in 0 until tc) {
                assigned[t] = true
                idx.add(fi[t * 3]); idx.add(fi[t * 3 + 1]); idx.add(fi[t * 3 + 2])
            }
            if (idx.isNotEmpty()) {
                prims.add(idx)
                colors.add(mesh.materials[g.name]?.toColorInt() ?: GRAY)
            }
        }
        val leftover = ArrayList<Int>()
        for (t in 0 until tc) if (!assigned[t]) {
            leftover.add(fi[t * 3]); leftover.add(fi[t * 3 + 1]); leftover.add(fi[t * 3 + 2])
        }
        if (leftover.isNotEmpty()) { prims.add(leftover); colors.add(GRAY) }
        if (prims.isEmpty()) { prims.add(fi.toList()); colors.add(GRAY) }
    }

    val geom = Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
        .vertices(verts).primitivesIndices(prims).build(engine)
    return BuiltMesh(geom, colors, tc)
}

// ---- Repère / matrices ----

/** MvrModels.Mat4 (col-majeur) → dev.romainguy Mat4. */
private fun drMat(m: FloatArray): Mat4 = Mat4(
    Float4(m[0], m[1], m[2], m[3]),
    Float4(m[4], m[5], m[6], m[7]),
    Float4(m[8], m[9], m[10], m[11]),
    Float4(m[12], m[13], m[14], m[15])
)

/** MVR (mm, Z-haut, centré sur `center`) → Filament (m, Y-haut) : Rx(−90°)·S(0.001)·T(−center). */
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
