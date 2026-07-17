package com.minou.mvrviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.minou.mvrviewer.mvr.GdtfLoader
import com.minou.mvrviewer.mvr.MvrGeometryRef
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.ThreeDSParser
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.RenderQuality
import io.github.sceneview.Scene
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
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
private const val MAX_NODES = 10_000
// Part du budget RÉSERVÉE aux silhouettes GDTF des projecteurs : le décor
// s'arrête avant, pour que les projecteurs (cœur de l'app) soient TOUJOURS
// rendus même sur un gros show (sinon le décor mangeait tout le budget).
private const val FIXTURE_NODE_RESERVE = 2_500
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Scene3DScreen(
    scene: MvrScene,
    mvrBytes: ByteArray,
    options: SceneOptions,
    onShowPlan: () -> Unit,
    onShowPatch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    val center = remember(scene) { sceneCenterMm(scene) }
    val layout = remember(scene, center) { fixtureLayout(scene, center) }

    // Matériau gris uniforme quand « couleurs par calque » est désactivé.
    val grayMaterial = remember(materialLoader) { materialLoader.createColorInstance(GRAY) }
    val fixtureMaterials: Map<Int, MaterialInstance> = remember(materialLoader, layout) {
        layout.colors.toSet().associateWith { materialLoader.createColorInstance(it) }
    }

    val geometryRoot = rememberNode(engine)
    var status by remember(scene) { mutableStateOf("chargement 3D…") }
    // Indices (dans scene.fixtures) des projecteurs rendus avec leur VRAIE
    // silhouette GDTF — leurs cubes de repli sont masqués.
    var gdtfFixtures by remember(scene) { mutableStateOf(emptySet<Int>()) }

    LaunchedEffect(scene, mvrBytes) {
        val conv = conversionMatrix(center)
        // 1) Hors thread : résolution des refs (File + Symbol→Symdef récursif),
        //    extraction zip en 1 passe des .3ds, parsing par fichier UNIQUE.
        //    (Les .glb sont extraits PAR LOTS plus bas — un show peut en avoir
        //    14 000, tout garder en mémoire d'un coup ferait un OOM.)
        val prepared = withContext(Dispatchers.Default) {
            val refs = collectRenderRefs(scene, conv)
            val tdsNames = refs.tds.mapTo(HashSet()) { it.fileName }
            val bytesByName = MvrParser.extractEntries(mvrBytes, tdsNames)
            val meshes = HashMap<String, List<ThreeDSParser.Mesh>>(tdsNames.size)
            for (n in tdsNames) {
                val b = bytesByName[n] ?: continue
                meshes[n] = runCatching { ThreeDSParser.parse(b) }.getOrDefault(emptyList())
            }
            // Textures externes potentielles des glb (le MVR embarque des .png) :
            // servies au chargeur via resourceResolver, comme le fix iOS des
            // textures manquantes.
            val imageNames = MvrParser.listEntries(mvrBytes)
                .filter { it.endsWith(".png", true) || it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
                .mapTo(HashSet()) { it.substringAfterLast('/') }
            val images = if (imageNames.isEmpty()) emptyMap()
                         else MvrParser.extractEntries(mvrBytes, imageNames)
            Triple(refs, meshes, images)
        }
        val (refs, meshesByFile, imageBytes) = prepared

        // 2) Thread moteur : budget global de nœuds/triangles partagé 3ds+glb.
        val builtCache = HashMap<String, List<BuiltMesh>>()
        val materialCache = HashMap<Int, MaterialInstance>()
        fun material(color: Int) = materialCache.getOrPut(color) { materialLoader.createColorInstance(color) }
        var nodes = 0
        var triangles = 0L
        var placed = 0
        var truncated = false
        val sceneNodeBudget = MAX_NODES - FIXTURE_NODE_RESERVE

        // 2a) .3ds : géométries uniques partagées + un GeometryNode par instance.
        for (r in refs.tds) {
            if (nodes >= sceneNodeBudget || triangles >= MAX_TRIANGLES) { truncated = true; break }
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
            if (placed % 25 == 0) { status = "$placed objets 3D…"; yield() }
        }

        // 2b) .glb : createInstancedModel (N instances d'un même fichier = 1 seul
        //    parse), extraction du zip PAR LOTS de fichiers uniques.
        if (refs.glb.isNotEmpty() && nodes < sceneNodeBudget) {
            val byFile = LinkedHashMap<String, MutableList<Mat4>>()
            for (r in refs.glb) byFile.getOrPut(r.fileName) { mutableListOf() }.add(r.world)
            val resolver: (String) -> java.nio.Buffer? = { uri ->
                imageBytes[uri.substringAfterLast('/')]?.let { java.nio.ByteBuffer.wrap(it) }
            }
            val names = byFile.keys.toList()
            var i = 0
            outer@ while (i < names.size) {
                val chunk = names.subList(i, minOf(i + 250, names.size))
                val bytesMap = withContext(Dispatchers.IO) { MvrParser.extractEntries(mvrBytes, chunk.toSet()) }
                for (name in chunk) {
                    val remaining = sceneNodeBudget - nodes
                    if (remaining <= 0) { truncated = true; break@outer }
                    val transforms = byFile.getValue(name)
                    val take = minOf(transforms.size, remaining)
                    if (take < transforms.size) truncated = true
                    val data = bytesMap[name] ?: continue
                    val instances = runCatching {
                        modelLoader.createInstancedModel(java.nio.ByteBuffer.wrap(data), take, resolver)
                    }.getOrNull() ?: continue
                    for (k in 0 until minOf(take, instances.size)) {
                        val node = io.github.sceneview.node.ModelNode(instances[k])
                        node.transform = transforms[k]
                        geometryRoot.addChildNode(node)
                    }
                    nodes += take
                    placed += take
                    if (placed % 100 < take) { status = "$placed objets 3D…"; yield() }
                }
                i += 250
            }
        }

        // 2c) Projecteurs : vraie silhouette GDTF (Base/Yoke/Head assemblés) à
        //    la place du cube. Un spec = UN assemblage préparé, instancié pour
        //    chaque projecteur qui l'utilise (géométries 3ds partagées, glb via
        //    createInstancedModel).
        val fixtures = scene.fixtures
        val bySpec = LinkedHashMap<String, MutableList<Int>>()
        fixtures.forEachIndexed { fi2, f ->
            val s = f.gdtfSpec?.trim()
            if (!s.isNullOrEmpty()) bySpec.getOrPut(s) { mutableListOf() }.add(fi2)
        }
        val gdtfDone = HashSet<Int>()
        specLoop@ for ((spec, idxs) in bySpec) {
            if (nodes >= MAX_NODES) { truncated = true; break }
            // Préparation hors moteur : extraction du .gdtf, parse de l'arbre,
            // octets des modèles (+ parse .3ds).
            val prep = withContext(Dispatchers.Default) {
                val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
                val gd = cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
                    ?: return@withContext null
                val asm = GdtfLoader.parseAssembly(gd) ?: return@withContext null
                val files = HashMap<String, Triple<ByteArray, String, List<ThreeDSParser.Mesh>>>()
                for ((mName, mInfo) in asm.models) {
                    val fb = GdtfLoader.extractModelBytes(gd, mInfo.file) ?: continue
                    val meshes = if (fb.second == "3ds")
                        runCatching { ThreeDSParser.parse(fb.first) }.getOrDefault(emptyList())
                    else emptyList()
                    files[mName] = Triple(fb.first, fb.second, meshes)
                }
                asm to files
            } ?: continue
            val (asm, files) = prep
            val placementsByModel = asm.placements.groupBy { it.modelName }

            // Pré-construit chaque modèle du spec : soit des géométries 3ds
            // partagées, soit une file d'instances glb.
            class ModelBuild(val built: List<BuiltMesh>, val glbQueue: ArrayDeque<com.google.android.filament.gltfio.FilamentInstance>, val adjust: Mat4)
            val builds = HashMap<String, ModelBuild>()
            for ((mName, pls) in placementsByModel) {
                val info = asm.models[mName] ?: continue
                val (bytes, ext, meshes) = files[mName] ?: continue
                if (ext == "3ds") {
                    if (meshes.isEmpty()) continue
                    val built = meshes.mapNotNull { buildMesh(engine, it) }
                    if (built.isEmpty()) continue
                    val rawMax = meshesMaxDimension(meshes)
                    val s = when {
                        info.maxDeclaredDimension != null && rawMax > 0f -> info.maxDeclaredDimension!! / rawMax
                        rawMax > 10f -> 0.001f // pas de dimensions déclarées : mm supposés
                        else -> 1f
                    }
                    builds[mName] = ModelBuild(built, ArrayDeque(), scaleMat(s))
                } else {
                    // glb/gltf : une instance par (placement × projecteur).
                    val count = pls.size * idxs.size
                    val instances = runCatching {
                        modelLoader.createInstancedModel(java.nio.ByteBuffer.wrap(bytes), count)
                    }.getOrNull() ?: continue
                    val box = instances.firstOrNull()?.asset?.boundingBox
                    val rawMax = box?.halfExtent?.let { 2f * maxOf(it[0], it[1], it[2]) } ?: 0f
                    val s = when {
                        info.maxDeclaredDimension != null && rawMax > 0f -> info.maxDeclaredDimension!! / rawMax
                        rawMax > 10f -> 0.001f
                        else -> 1f
                    }
                    // glTF est Y-haut, l'assemblage GDTF est Z-haut → Rx(+90°).
                    builds[mName] = ModelBuild(emptyList(), ArrayDeque(instances), RX90 * scaleMat(s))
                }
            }
            if (builds.isEmpty()) continue

            for (fi2 in idxs) {
                if (nodes >= MAX_NODES) { truncated = true; break@specLoop }
                val worldFix = conv * drMat(fixtures[fi2].transform.m) * GLB_SCALE // assemblage m → mm
                var any = false
                for (p in asm.placements) {
                    val b = builds[p.modelName] ?: continue
                    val world = worldFix * p.transform * b.adjust
                    if (b.built.isNotEmpty()) {
                        for (bm in b.built) {
                            val node = GeometryNode(engine, bm.geometry, bm.colors.map(::material))
                            node.transform = world
                            geometryRoot.addChildNode(node)
                            nodes++
                        }
                        any = true
                    } else {
                        val inst = b.glbQueue.removeFirstOrNull() ?: continue
                        val node = io.github.sceneview.node.ModelNode(inst)
                        node.transform = world
                        geometryRoot.addChildNode(node)
                        nodes++
                        any = true
                    }
                }
                if (any) gdtfDone.add(fi2)
                if (gdtfDone.size % 40 == 0) { status = "$placed objets · ${gdtfDone.size} proj. GDTF…"; yield() }
            }
        }
        gdtfFixtures = gdtfDone

        status = "$placed objets 3D" +
            (if (gdtfDone.isNotEmpty()) " · ${gdtfDone.size} proj. GDTF" else "") +
            if (truncated) " (tronqué)" else ""
    }

    val camHome = Float3(0f, layout.radius * 0.7f, layout.radius * 1.9f)
    val cameraNode = rememberCameraNode(engine) { position = camHome }
    val manipulator = rememberCameraManipulator(orbitHomePosition = camHome, targetPosition = Float3(0f, 0f, 0f))

    // Barre du haut EN DEHORS de la zone SceneView : celle-ci consomme tous les
    // touchers, donc des contrôles flottants PAR-DESSUS (façon iOS) ne
    // recevraient jamais les taps. Une vraie TopAppBar au-dessus reste cliquable.
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    if (status.isBlank()) "Vue 3D" else "3D · $status",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Projets")
                }
            },
            actions = {
                SceneOptionsMenu(
                    options = options, tint = LocalContentColor.current,
                    onShowPlan = onShowPlan, onShowPatch = onShowPatch
                )
            }
        )
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B0D))) {
            Scene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                cameraNode = cameraNode,
                cameraManipulator = manipulator,
                // Perf : pas d'ombres (des milliers d'objets = coût énorme) et
                // qualité « Performance » (MSAA/post-process réduits). La
                // fluidité de fond viendra surtout de la fusion des draw calls.
                renderQuality = RenderQuality.Performance,
                mainLightNode = rememberMainLightNode(engine) { isShadowCaster = false }
            ) {
                Node(apply = { addChildNode(geometryRoot) })
                val s = Float3(layout.cube, layout.cube, layout.cube)
                layout.positions.forEachIndexed { i, p ->
                    // Cube de repli masqué dès que le projecteur a sa silhouette GDTF.
                    if (i !in gdtfFixtures) {
                        val mat = if (options.layerColors) fixtureMaterials.getValue(layout.colors[i]) else grayMaterial
                        CubeNode(size = s, materialInstance = mat, position = p)
                    }
                }
            }
        }
    }
}

// ---- Résolution des références de géométrie ----

private class RenderRef(val world: Mat4, val fileName: String)
private class RenderRefs(val tds: List<RenderRef>, val glb: List<RenderRef>)

/** Les .glb MVR sont en MÈTRES, le monde MVR en mm → ×1000 (même fix qu'iOS). */
private val GLB_SCALE = Mat4(
    Float4(1000f, 0f, 0f, 0f), Float4(0f, 1000f, 0f, 0f),
    Float4(0f, 0f, 1000f, 0f), Float4(0f, 0f, 0f, 1f)
)

/**
 * Aplati la scène en refs rendables (.3ds et .glb séparés — chargeurs
 * différents) : Geometry3D directs + Symbol→Symdef résolus récursivement
 * (transforms composées obj·symbole·item…), garde-fou de profondeur (cycles)
 * et plafond d'items par symdef (comme iOS).
 */
private fun collectRenderRefs(scene: MvrScene, conv: Mat4): RenderRefs {
    val tds = ArrayList<RenderRef>()
    val glb = ArrayList<RenderRef>()
    fun walk(refs: List<MvrGeometryRef>, parent: Mat4, depth: Int) {
        if (depth > 8) return
        for (g in refs) when (g) {
            is MvrGeometryRef.File -> {
                val world = parent * drMat(g.transform.m)
                when {
                    g.fileName.endsWith(".3ds", ignoreCase = true) ->
                        tds.add(RenderRef(world, g.fileName))
                    g.fileName.endsWith(".glb", ignoreCase = true) ||
                        g.fileName.endsWith(".gltf", ignoreCase = true) ->
                        glb.add(RenderRef(world * GLB_SCALE, g.fileName))
                }
            }
            is MvrGeometryRef.Symbol -> {
                val sd = scene.symdefs[g.symdefUuid] ?: continue
                walk(sd.items.take(MAX_SYMDEF_ITEMS), parent * drMat(g.transform.m), depth + 1)
            }
        }
    }
    for (obj in scene.allObjects) walk(obj.geometryRefs, conv * drMat(obj.transform.m), 0)
    return RenderRefs(tds, glb)
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

/** Échelle uniforme. */
private fun scaleMat(s: Float): Mat4 = Mat4(
    Float4(s, 0f, 0f, 0f), Float4(0f, s, 0f, 0f),
    Float4(0f, 0f, s, 0f), Float4(0f, 0f, 0f, 1f)
)

/** Rotation +90° autour de X (glTF Y-haut → repère GDTF Z-haut), col-majeur. */
private val RX90 = Mat4(
    Float4(1f, 0f, 0f, 0f), Float4(0f, 0f, 1f, 0f),
    Float4(0f, -1f, 0f, 0f), Float4(0f, 0f, 0f, 1f)
)

/** Plus grande dimension (axes) de l'ensemble des meshes d'un fichier 3ds. */
private fun meshesMaxDimension(meshes: List<ThreeDSParser.Mesh>): Float {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    var seen = false
    for (m in meshes) {
        val v = m.vertices
        var i = 0
        while (i + 2 < v.size) {
            val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                seen = true
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            }
            i += 3
        }
    }
    if (!seen) return 0f
    return maxOf(maxX - minX, maxY - minY, maxZ - minZ)
}

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
