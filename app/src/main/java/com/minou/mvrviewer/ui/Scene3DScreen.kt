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
import com.google.android.filament.gltfio.FilamentAsset
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
// En dessous de cette taille (mm, dimension max du mesh), un objet 3ds est jugé
// « petit » (siège, accessoire, petit décor) et masqué pendant les mouvements
// de caméra. Relevé de 1200 → 3000 pour masquer aussi le décor moyen en nav
// (le gros décor — ponts, scène — reste, pour garder le repère spatial).
private const val LOD_SMALL_MM = 3000f
// Plan de repère DXF en 3D : couleur des lignes (bleu clair, lisible sur fond
// sombre) + plafond de sommets (le vrai DXF V&B fait des millions de segments).
private const val DXF_LINE_COLOR = 0xFF6FB7E8.toInt()
private const val MAX_DXF_VERTS = 500_000

/**
 * État du LOD d'interaction. Pendant que la caméra bouge, on MASQUE le détail
 * coûteux (`nodes` : petits décors + silhouettes GDTF des projecteurs, qui
 * dominent les draw calls) et on affiche à la place des cubes simples
 * (`proxies` : un par projecteur). À l'arrêt : détail réaffiché, cubes masqués.
 * Même principe que le LOD à proxies d'iOS (les projecteurs = ~63% des draw
 * calls sur un gros show → les masquer en nav change tout).
 */
private class LodState {
    val nodes = ArrayList<io.github.sceneview.node.Node>()
    val proxies = ArrayList<io.github.sceneview.node.Node>()
    var lastX = Float.NaN; var lastY = Float.NaN; var lastZ = Float.NaN
    var idle = 0
    var hidden = false
    fun reset() { nodes.clear(); proxies.clear(); lastX = Float.NaN; idle = 0; hidden = false }
}

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
    gdtfOverrides: GdtfOverrides,
    referencePlan: com.minou.mvrviewer.mvr.ReferencePlan? = null,
    onShowPlan: () -> Unit,
    onShowPatch: () -> Unit,
    onShowGdtfShare: () -> Unit,
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
    // Assets glTF (.glb) créés par createInstancedModel, gardés pour être LIBÉRÉS
    // à la reconstruction : ModelNode.destroy() NE libère PAS le FilamentAsset
    // (entités + VertexBuffer/IndexBuffer/textures) — seul assetLoader.destroyAsset
    // le fait. Persiste au-delà des version bumps (durée de vie du moteur), vidé
    // au purge. Sans ça, chaque « appliquer un modèle GDTF » sur un show à .glb
    // fuyait toute la mémoire GPU glTF → crash.
    val glbAssets = remember { ArrayList<FilamentAsset>() }
    // Cache des matériaux couleur de la build 3D, RÉUTILISÉ d'une reconstruction
    // à l'autre : le MaterialLoader retient chaque createColorInstance et ne le
    // libère qu'au dispose — le recréer à chaque « appliquer GDTF » (version
    // bump) fuyait les instances de la build précédente.
    val buildMaterialCache = remember(materialLoader) { HashMap<Int, MaterialInstance>() }
    // Sous-couche du plan de repère DXF (lignes), placée au sol par sa transformée.
    val dxfRoot = rememberNode(engine)
    val dxfMaterial = remember(materialLoader) { materialLoader.createUnlitColorInstance(DXF_LINE_COLOR) }
    var status by remember(scene) { mutableStateOf("chargement 3D…") }
    // LOD d'interaction : les petits objets (sièges, accessoires) sont masqués
    // pendant que la caméra bouge, réaffichés à l'arrêt — divise les draw calls
    // en navigation (comme iOS), sans toucher au rendu au repos.
    val lod = remember(scene) { LodState() }
    // Indices (dans scene.fixtures) des projecteurs rendus avec leur VRAIE
    // silhouette GDTF — leurs cubes de repli sont masqués.
    var gdtfFixtures by remember(scene) { mutableStateOf(emptySet<Int>()) }

    // Reconstruit quand un modèle GDTF Share est appliqué (version bump).
    LaunchedEffect(scene, mvrBytes, gdtfOverrides.version) {
        // Repart d'une scène vide (appliquer un modèle GDTF Share re-déclenche cet
        // effet). ⚠️ On DÉTRUIT les anciens nœuds (removeChildNode NE libère PAS
        // les ressources Filament : VertexBuffer/IndexBuffer/renderable). Sans ça,
        // chaque reconstruction FUYAIT toute la géométrie GPU → saturation mémoire
        // → CRASH après quelques changements de modèle. `destroy()` (via
        // safeDestroyGeometry) est idempotent → géométrie partagée OK.
        // On vide le LOD AVANT de détruire (sinon onFrame toucherait un nœud
        // détruit pendant la reconstruction → crash).
        lod.reset()
        gdtfFixtures = emptySet()
        geometryRoot.childNodes.toList().forEach {
            geometryRoot.removeChildNode(it)
            runCatching { it.destroy() }
        }
        // Les nœuds détruits, on libère les FilamentAsset .glb de la build
        // précédente. destroyModel EN PREMIER (retire de `models` pour que le
        // dispose ne repasse pas dessus + releaseSourceData tant que l'asset est
        // valide), puis destroyAsset (libère entités + buffers GPU). RenderableNode
        // .destroy() n'a fait que retirer le COMPOSANT renderable des entités —
        // pas de double-free. Idempotent (runCatching).
        glbAssets.forEach { asset ->
            runCatching { modelLoader.destroyModel(asset) }
            runCatching { modelLoader.assetLoader.destroyAsset(asset) }
        }
        glbAssets.clear()
        val conv = conversionMatrix(center)

        // Découpe temporelle : les appels Filament (build Geometry, GeometryNode,
        // addChildNode, createInstancedModel) DOIVENT rester sur le thread moteur,
        // mais on rend la main toutes les ~6 ms pour ne JAMAIS bloquer l'UI plus
        // d'une trame — c'était la cause de l'ANR au chargement d'un gros show.
        var lastYield = System.nanoTime()
        suspend fun slice() {
            val now = System.nanoTime()
            if (now - lastYield > 6_000_000L) { yield(); lastYield = System.nanoTime() }
        }

        // 1) Prep CPU HORS THREAD PRINCIPAL (résolution des refs, extraction zip
        //    des .3ds, parse + normales), MISE EN CACHE par scène : un retour en
        //    3D après un détour par le plan réutilise tout (plus de re-parse ~30s).
        //    Les .glb sont extraits PAR LOTS plus bas (un show peut en avoir 14 000).
        val holder = Prepared3DCache.get(scene)
        if (!holder.ready) {
            val prep = withContext(Dispatchers.Default) {
                val r = collectRenderRefs(scene, conv)
                val tdsNames = r.tds.mapTo(HashSet()) { it.fileName }
                val bytesByName = MvrParser.extractEntries(mvrBytes, tdsNames)
                val md = HashMap<String, List<MeshData>>(tdsNames.size)
                val dim = HashMap<String, Float>(tdsNames.size)
                for (n in tdsNames) {
                    val b = bytesByName[n] ?: continue
                    val meshes = runCatching { ThreeDSParser.parse(b) }.getOrDefault(emptyList())
                    md[n] = meshes.mapNotNull { prepareMeshData(it) }
                    dim[n] = meshesMaxDimension(meshes)
                }
                // Textures externes potentielles des glb (le MVR embarque des .png).
                val imageNames = MvrParser.listEntries(mvrBytes)
                    .filter { it.endsWith(".png", true) || it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
                    .mapTo(HashSet()) { it.substringAfterLast('/') }
                val images = if (imageNames.isEmpty()) emptyMap()
                             else MvrParser.extractEntries(mvrBytes, imageNames)
                Prepared3DHolder().also {
                    it.refs = r; it.meshDataByFile = md; it.maxDimByFile = dim; it.imageBytes = images
                }
            }
            holder.refs = prep.refs; holder.meshDataByFile = prep.meshDataByFile
            holder.maxDimByFile = prep.maxDimByFile; holder.imageBytes = prep.imageBytes
        }
        val refs = holder.refs!!
        val meshDataByFile = holder.meshDataByFile!!
        val maxDimByFile = holder.maxDimByFile!!
        val imageBytes = holder.imageBytes ?: emptyMap()

        // 2) Thread moteur : budget global de nœuds/triangles partagé 3ds+glb.
        val builtCache = HashMap<String, List<BuiltMesh>>()
        fun material(color: Int) = buildMaterialCache.getOrPut(color) { materialLoader.createColorInstance(color) }
        var nodes = 0
        var triangles = 0L
        var placed = 0
        var truncated = false
        val sceneNodeBudget = MAX_NODES - FIXTURE_NODE_RESERVE

        // 2a) .3ds : géométries uniques partagées + un GeometryNode par instance.
        for (r in refs.tds) {
            if (nodes >= sceneNodeBudget || triangles >= MAX_TRIANGLES) { truncated = true; break }
            val md = meshDataByFile[r.fileName]
            if (md.isNullOrEmpty()) continue
            val built = builtCache.getOrPut(r.fileName) { md.map { BuiltMesh(it.toGeometry(engine), it.colors, it.triangles) } }
            // Petit objet (siège, accessoire) → candidat au LOD d'interaction.
            val small = (maxDimByFile[r.fileName] ?: Float.MAX_VALUE) < LOD_SMALL_MM
            for (bm in built) {
                val node = GeometryNode(engine, bm.geometry, bm.colors.map(::material))
                node.transform = r.world
                geometryRoot.addChildNode(node)
                if (small) lod.nodes.add(node)
                nodes++; triangles += bm.triangles
            }
            placed++
            if (placed % 40 == 0) status = "$placed objets 3D…"
            slice()
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
                    instances.firstOrNull()?.asset?.let { glbAssets.add(it) }
                    for (k in 0 until minOf(take, instances.size)) {
                        val node = io.github.sceneview.node.ModelNode(instances[k])
                        node.transform = transforms[k]
                        geometryRoot.addChildNode(node)
                        slice()
                    }
                    nodes += take
                    placed += take
                    status = "$placed objets 3D…"
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
        // Modèle GDTF préparé HORS THREAD MOTEUR : octets + ext + MeshData (déjà
        // normalisés) + dimension brute (pour l'échelle), au lieu de meshes bruts.
        class GdtfModelPrep(val ext: String, val bytes: ByteArray, val meshData: List<MeshData>, val rawMax: Float)
        specLoop@ for ((spec, idxs) in bySpec) {
            if (nodes >= MAX_NODES) { truncated = true; break }
            // Sources candidates : le modèle GDTF Share (choisi/auto) PUIS le
            // .gdtf embarqué du MVR. Si le téléchargé ne produit AUCUNE
            // géométrie (profil photométrique sans modèle 3D, glb illisible…),
            // on retombe sur l'embarqué au lieu de perdre la silhouette.
            val sources = withContext(Dispatchers.Default) {
                val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
                val embedded = cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
                // Un override sans AUCUN fichier de modèle 3D (profil photométrique)
                // est écarté d'emblée : il masquerait la silhouette embarquée.
                val over = gdtfOverrides.map[spec]?.takeIf { GdtfLoader.hasThreeDModel(it) }
                listOfNotNull(over, embedded.takeIf { it !== over })
            }
            sourceLoop@ for (gd in sources) {
                // Préparation hors moteur : parse de l'arbre GDTF, octets des
                // modèles + parse .3ds + normales (tout le CPU).
                val prep = withContext(Dispatchers.Default) {
                    val asm = GdtfLoader.parseAssembly(gd) ?: return@withContext null
                    val files = HashMap<String, GdtfModelPrep>()
                    for ((mName, mInfo) in asm.models) {
                        val fb = GdtfLoader.extractModelBytes(gd, mInfo.file) ?: continue
                        if (fb.second == "3ds") {
                            val meshes = runCatching { ThreeDSParser.parse(fb.first) }.getOrDefault(emptyList())
                            files[mName] = GdtfModelPrep("3ds", fb.first, meshes.mapNotNull { prepareMeshData(it) }, meshesMaxDimension(meshes))
                        } else {
                            files[mName] = GdtfModelPrep(fb.second, fb.first, emptyList(), 0f)
                        }
                    }
                    asm to files
                } ?: continue@sourceLoop
                val (asm, files) = prep
                val placementsByModel = asm.placements.groupBy { it.modelName }

                // Pré-construit chaque modèle du spec : soit des géométries 3ds
                // partagées, soit une file d'instances glb.
                class ModelBuild(val built: List<BuiltMesh>, val glbQueue: ArrayDeque<com.google.android.filament.gltfio.FilamentInstance>, val adjust: Mat4)
                val builds = HashMap<String, ModelBuild>()
                for ((mName, pls) in placementsByModel) {
                    val info = asm.models[mName] ?: continue
                    val fp = files[mName] ?: continue
                    if (fp.ext == "3ds") {
                        if (fp.meshData.isEmpty()) continue
                        val built = fp.meshData.map { BuiltMesh(it.toGeometry(engine), it.colors, it.triangles) }
                        val rawMax = fp.rawMax
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
                            modelLoader.createInstancedModel(java.nio.ByteBuffer.wrap(fp.bytes), count)
                        }.getOrNull() ?: continue
                        instances.firstOrNull()?.asset?.let { glbAssets.add(it) }
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
                    slice()
                }
                if (builds.isEmpty()) continue@sourceLoop  // source suivante (repli embarqué)

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
                                lod.nodes.add(node)   // détail masqué en navigation
                                nodes++
                            }
                            any = true
                        } else {
                            val inst = b.glbQueue.removeFirstOrNull() ?: continue
                            val node = io.github.sceneview.node.ModelNode(inst)
                            node.transform = world
                            geometryRoot.addChildNode(node)
                            lod.nodes.add(node)
                            nodes++
                            any = true
                        }
                    }
                    if (any) {
                        gdtfDone.add(fi2)
                        // Cube-proxy (1 par projecteur) affiché À LA PLACE du détail
                        // pendant les mouvements — commence masqué (on est au repos).
                        if (fi2 < layout.positions.size && layout.valid[fi2]) {
                            val cubeMat = material(layout.colors[fi2])
                            val proxy = io.github.sceneview.node.CubeNode(
                                engine, Float3(layout.cube, layout.cube, layout.cube),
                                layout.positions[fi2], cubeMat
                            )
                            proxy.isVisible = false
                            geometryRoot.addChildNode(proxy)
                            lod.proxies.add(proxy)
                        }
                    }
                    if (gdtfDone.size % 40 == 0) status = "$placed objets · ${gdtfDone.size} proj. GDTF…"
                    slice()
                }
                break@sourceLoop  // spec rendu — pas besoin du repli
            }
        }
        gdtfFixtures = gdtfDone

        status = "$placed objets 3D" +
            (if (gdtfDone.isNotEmpty()) " · ${gdtfDone.size} proj. GDTF" else "") +
            if (truncated) " (tronqué)" else ""
    }

    // Plan de repère DXF en 3D : lignes au sol, placées par la transformée du plan
    // (offset/rotation/échelle/hauteur), dans le MÊME repère monde que les
    // projecteurs (le retour en 3D recompose → reflète les derniers réglages).
    LaunchedEffect(referencePlan) {
        dxfRoot.childNodes.toList().forEach {
            dxfRoot.removeChildNode(it)
            runCatching { it.destroy() }   // libère VertexBuffer/IndexBuffer (sinon fuite à chaque ré-import)
        }
        val rp = referencePlan ?: return@LaunchedEffect
        if (!rp.transform.visible || rp.plan.isEmpty) return@LaunchedEffect
        val cx = center.x; val cy = center.y; val cz = center.z
        // Prep HORS THREAD PRINCIPAL : chaque segment DXF devient un fin QUAD posé
        // à plat au sol (2 triangles, double-face). On rend des TRIANGLES — le
        // même chemin que la géométrie 3ds, fiable — plutôt que des primitives
        // LINES que SceneView ne restitue pas correctement (elles se remplissent).
        val prep = withContext(Dispatchers.Default) {
            val tf = rp.transform
            val s = tf.scale; val r = Math.toRadians(tf.rotationDeg)
            val cc = kotlin.math.cos(r); val sn = kotlin.math.sin(r)
            val ox = tf.offsetX; val oy = tf.offsetY; val hz = tf.heightZ.toFloat()
            val fy = (hz - cz) / 1000f      // hauteur Filament (sol), constante
            val hw = 0.03f                  // demi-largeur du trait (m) → ~6 cm
            val verts = ArrayList<Geometry.Vertex>()
            val idx = ArrayList<Int>()
            val up = Float3(0f, 1f, 0f)
            outer@ for (pl in rp.plan.polylines) {
                val pts = pl.points; val n = pts.size / 2
                if (n < 2) continue
                // Sommets projetés en Filament (plan XZ), une fois par polyligne.
                val fxz = FloatArray(n * 2)
                for (k in 0 until n) {
                    val sx = pts[k * 2] * s; val sy = pts[k * 2 + 1] * s
                    val wx = ox + (sx * cc - sy * sn)   // monde mm X
                    val wy = oy + (sx * sn + sy * cc)   // monde mm Y
                    fxz[k * 2] = (wx.toFloat() - cx) / 1000f
                    fxz[k * 2 + 1] = -(wy.toFloat() - cy) / 1000f
                }
                val segCount = (n - 1) + if (pl.closed && n > 2) 1 else 0
                for (e in 0 until segCount) {
                    val a = e; val b = (e + 1) % n
                    val ax = fxz[a * 2]; val az = fxz[a * 2 + 1]
                    val bx = fxz[b * 2]; val bz = fxz[b * 2 + 1]
                    val dx = bx - ax; val dz = bz - az
                    val len = sqrt(dx * dx + dz * dz)
                    if (len < 1e-6f) continue
                    val px = -dz / len * hw; val pz = dx / len * hw
                    val base = verts.size
                    verts.add(Geometry.Vertex(position = Float3(ax + px, fy, az + pz), normal = up)) // 0 A1
                    verts.add(Geometry.Vertex(position = Float3(ax - px, fy, az - pz), normal = up)) // 1 A2
                    verts.add(Geometry.Vertex(position = Float3(bx + px, fy, bz + pz), normal = up)) // 2 B1
                    verts.add(Geometry.Vertex(position = Float3(bx - px, fy, bz - pz), normal = up)) // 3 B2
                    // 2 triangles + leurs inverses (visible des deux côtés).
                    idx.add(base); idx.add(base + 1); idx.add(base + 3)
                    idx.add(base); idx.add(base + 3); idx.add(base + 2)
                    idx.add(base); idx.add(base + 3); idx.add(base + 1)
                    idx.add(base); idx.add(base + 2); idx.add(base + 3)
                }
                if (verts.size >= MAX_DXF_VERTS) break@outer
            }
            verts to idx
        }
        val (verts, idx) = prep
        if (verts.size < 3 || idx.isEmpty()) return@LaunchedEffect
        // Build moteur (thread principal) : 1 géométrie TRIANGLES, 1 nœud.
        val geom = Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
            .vertices(verts).primitivesIndices(listOf(idx)).build(engine)
        dxfRoot.addChildNode(GeometryNode(engine, geom, listOf(dxfMaterial)))
    }

    val camHome = Float3(0f, layout.radius * 0.7f, layout.radius * 1.9f)
    val cameraNode = rememberCameraNode(engine) { position = camHome }
    // Vitesse de pinch-zoom PROPORTIONNELLE à la taille de la scène : le zoom
    // orbite Filament déplace la caméra d'un pas en unités MONDE (mètres), donc
    // sur un gros show (ACF, Vega…) le pas par défaut (0,0555) fait avancer d'un
    // rien → il fallait pincer 10 fois. On l'échelonne sur le rayon.
    val target = remember { Float3(0f, 0f, 0f) }
    val zoomSpeed = remember(layout) { (layout.radius * 0.006f).coerceIn(0.0555f, 12f) }
    val manipulator = rememberCameraManipulator(orbitHomePosition = camHome, targetPosition = target) {
        io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator(
            orbitHomePosition = camHome, targetPosition = target, pinchZoomSpeed = zoomSpeed
        )
    }

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
                    onShowPlan = onShowPlan, onShowPatch = onShowPatch,
                    onShowGdtfShare = onShowGdtfShare
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
                // NE PAS recadrer la caméra sur le contenu : sinon un plan DXF
                // importé plus grand que la scène recadre tout et « cache » le
                // décor. La caméra reste pilotée par notre rig (camHome + orbite).
                autoCenterContent = false,
                autoFitContent = false,
                // Perf : pas d'ombres (des milliers d'objets = coût énorme) et
                // qualité « Performance » (MSAA/post-process réduits).
                renderQuality = RenderQuality.Performance,
                mainLightNode = rememberMainLightNode(engine) { isShadowCaster = false },
                // LOD d'interaction : détecte le mouvement caméra (position monde
                // qui change) → masque les petits objets ; réaffiche après ~10
                // frames stables.
                onFrame = {
                    val p = cameraNode.worldPosition
                    val moved = lod.lastX.isNaN() ||
                        (kotlin.math.abs(p.x - lod.lastX) + kotlin.math.abs(p.y - lod.lastY) + kotlin.math.abs(p.z - lod.lastZ)) > 0.02f
                    lod.lastX = p.x; lod.lastY = p.y; lod.lastZ = p.z
                    if (lod.nodes.isNotEmpty() || lod.proxies.isNotEmpty()) {
                        if (moved) {
                            lod.idle = 0
                            if (!lod.hidden) {
                                // Masque le détail, montre les cubes-proxies.
                                lod.nodes.forEach { it.isVisible = false }
                                lod.proxies.forEach { it.isVisible = true }
                                lod.hidden = true
                            }
                        } else {
                            lod.idle++
                            if (lod.hidden && lod.idle > 8) {
                                lod.nodes.forEach { it.isVisible = true }
                                lod.proxies.forEach { it.isVisible = false }
                                lod.hidden = false
                            }
                        }
                    }
                }
            ) {
                Node(apply = { addChildNode(geometryRoot) })
                Node(apply = { addChildNode(dxfRoot) })
                val s = Float3(layout.cube, layout.cube, layout.cube)
                layout.positions.forEachIndexed { i, p ->
                    // Cube de repli masqué dès que le projecteur a sa silhouette GDTF,
                    // ou si sa position est invalide (translation non finie).
                    if (i !in gdtfFixtures && layout.valid[i]) {
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

/**
 * Données de mesh PRÊTES au moteur, calculées HORS THREAD PRINCIPAL (normales,
 * listes de sommets/indices, couleurs). Ne touche PAS l'Engine Filament → peut
 * être construit sur `Dispatchers.Default` et mis en cache. Le seul appel moteur
 * (`toGeometry`) est trivial (upload GPU) et reste sur le thread moteur.
 */
private class MeshData(
    val verts: List<Geometry.Vertex>,
    val prims: List<List<Int>>,
    val colors: List<Int>,
    val triangles: Int
)

private fun MeshData.toGeometry(engine: Engine): Geometry =
    Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
        .vertices(verts).primitivesIndices(prims).build(engine)

/** Cache de préparation CPU d'une scène (survit aux bascules 3D↔plan). */
private class Prepared3DHolder {
    var refs: RenderRefs? = null
    var meshDataByFile: Map<String, List<MeshData>>? = null
    var maxDimByFile: Map<String, Float>? = null
    var imageBytes: Map<String, ByteArray>? = null
    val ready: Boolean get() = refs != null
}

/**
 * Cache mono-entrée par identité de scène : rouvrir la MÊME scène (retour en 3D
 * après un détour par le plan/patch) réutilise la prep CPU (parse .3ds +
 * normales), au lieu de tout recalculer (~30 s). Une nouvelle scène remplace
 * l'entrée (mémoire bornée à un show).
 */
private object Prepared3DCache {
    private var keyScene: MvrScene? = null
    private var holder: Prepared3DHolder? = null
    fun get(scene: MvrScene): Prepared3DHolder {
        val h = holder
        if (keyScene !== scene || h == null) {
            keyScene = scene
            return Prepared3DHolder().also { holder = it }
        }
        return h
    }
}

private fun ThreeDSParser.Rgb.toColorInt(): Int {
    fun c(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

/**
 * Mesh 3ds → MeshData (positions + normales lissées, et UN sous-mesh PAR groupe
 * matériau avec sa couleur — faces sans matériau en gris clair). PUR CPU, aucun
 * appel Engine → à exécuter hors thread principal. Fidèle à Mesh.toSCNNode iOS.
 */
private fun prepareMeshData(mesh: ThreeDSParser.Mesh): MeshData? {
    val vc = mesh.vertexCount
    val tc = mesh.triangleCount
    if (vc == 0 || tc == 0) return null
    val vs = mesh.vertices
    val fi = mesh.faceIndices

    // Le .3ds stocke les indices de face en U16 bruts, SANS garantie qu'ils
    // pointent dans le tableau de sommets (fichier corrompu / mal exporté). Un
    // indice ≥ vc chargé tel quel fait lire le GPU HORS du VertexBuffer au dessin
    // → corruption ou abort natif (surtout sur pilotes sans robust-buffer-access).
    // On ne garde que les triangles dont les 3 indices sont valides.
    fun okTri(t: Int): Boolean {
        val o = t * 3
        if (o + 2 >= fi.size) return false
        val a = fi[o]; val b = fi[o + 1]; val c = fi[o + 2]
        return a in 0 until vc && b in 0 until vc && c in 0 until vc
    }

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
    return MeshData(verts, prims, colors, tc)
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

private class FixtureLayout(val positions: List<Float3>, val colors: List<Int>, val valid: List<Boolean>, val radius: Float, val cube: Float)

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
    // Aligné 1:1 sur `src` (= scene.fixtures quand non vide) : la build GDTF
    // indexe layout.positions/colors par l'index PLEIN du projecteur (fi2) et
    // gdtfFixtures stocke ces mêmes index. Un projecteur à translation non finie
    // GARDE son entrée (valid=false, jamais dessinée) pour ne PAS décaler les
    // index des suivants — sinon proxy/cube tombaient sur le mauvais projecteur.
    val positions = ArrayList<Float3>(src.size); val colors = ArrayList<Int>(src.size)
    val valid = ArrayList<Boolean>(src.size)
    for (o in src) {
        val t = o.transform.translation
        val ok = t[0].isFinite() && t[1].isFinite() && t[2].isFinite()
        valid.add(ok)
        positions.add(
            if (ok) Float3((t[0] - center.x) / 1000f, (t[2] - center.z) / 1000f, -(t[1] - center.y) / 1000f)
            else Float3(0f, 0f, 0f)
        )
        colors.add(LAYER_PALETTE[(layerIndex[o.layerName] ?: 0) % LAYER_PALETTE.size])
    }
    var r = 0f
    for (i in positions.indices) if (valid[i]) {
        val p = positions[i]; val d = sqrt(p.x * p.x + p.y * p.y + p.z * p.z); if (d > r) r = d
    }
    if (r <= 0f || !r.isFinite()) r = 5f
    return FixtureLayout(positions, colors, valid, r, (r * 0.03f).coerceIn(0.1f, 1.5f))
}
