package com.minou.mvrviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Skybox
import com.google.android.filament.gltfio.FilamentAsset
import com.minou.mvrviewer.mvr.GdtfLoader
import com.minou.mvrviewer.mvr.MvrGeometryRef
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.ThreeDSParser
import dev.romainguy.kotlin.math.Float2
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
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

// Étiquettes 3D : bornes pour rester fluide sur un gros show pendant la nav.
//  - MAX_LABELS_3D      : nombre d'étiquettes RÉELLEMENT dessinées par frame.
//  - MAX_LABEL_PROJECTIONS : plafond de projections monde→écran tentées par frame
//    (worldToScreenPoint est le coût dominant ; ce plafond borne le pire cas d'un
//    show à des milliers de projecteurs quand peu sont dans le cadre).
//  - LABEL_CULL_PX      : marge de viewport (px) au-delà de laquelle on ne dessine pas.
private const val MAX_LABELS_3D = 200
private const val MAX_LABEL_PROJECTIONS = 4000
private const val LABEL_CULL_PX = 220f

// Presets de couleur de fond de la vue 3D (nom, ARGB) — mêmes choix qu'iOS.
private val BG_3D_PRESETS = listOf(
    "Noir" to 0xFF000000L, "Anthracite" to 0xFF1C1C1EL, "Bleu nuit" to 0xFF0B1026L,
    "Gris" to 0xFF8E8E93L, "Blanc" to 0xFFFFFFFFL
)

/** Couleur DXF (0xRRGGBB) → ARGB pour un matériau non éclairé, en éclaircissant
 *  les couleurs quasi noires (le fond 3D est en général sombre). */
private fun dxf3DColorInt(rgb: Int): Int {
    val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
    val lum = 0.299 * r + 0.587 * g + 0.114 * b
    return if (lum >= 50) 0xFF000000.toInt() or (rgb and 0xFFFFFF)
    else 0xFF000000.toInt() or
        (minOf(255, (r * 2.2).toInt() + 50) shl 16) or
        (minOf(255, (g * 2.2).toInt() + 50) shl 8) or
        minOf(255, (b * 2.2).toInt() + 50)
}

/** sRGB [0,1] → linéaire (la clearColor Filament est en espace linéaire). */
private fun srgbToLinear(s: Float): Double {
    val v = s.toDouble()
    return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
}

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
    // Solo actif : le LOD (onFrame) laisse alors la visibilité au filtre solo au
    // lieu de réafficher le détail — sinon il rallumerait le décor masqué.
    var soloActive = false
    fun reset() { nodes.clear(); proxies.clear(); lastX = Float.NaN; idle = 0; hidden = false; soloActive = false }
}

/**
 * Applique (ou retire) le filtre SOLO à la scène 3D par VISIBILITÉ, sans rien
 * reconstruire. Dans SceneView, `Node.isVisible` ne se propage PAS aux enfants
 * (chaque RenderableNode lit sa PROPRE visibilité), donc on bascule chaque enfant
 * de `geometryRoot`. Les projecteurs soloés restent montrés par le bloc de cubes
 * déclaratif (hors geometryRoot).
 */
private fun applySolo3D(
    geometryRoot: io.github.sceneview.node.Node,
    lod: LodState,
    solo: Set<String>
) {
    if (solo.isEmpty()) {
        // Retour à l'état de repos : tout le décor/silhouettes visible, les
        // cubes-proxies masqués (ils ne servent qu'en navigation), LOD réarmé.
        runCatching { geometryRoot.childNodes.forEach { it.isVisible = true } }
        lod.proxies.forEach { it.isVisible = false }
        lod.hidden = false; lod.idle = 0; lod.soloActive = false
    } else {
        // Solo : masque TOUT geometryRoot (décor + silhouettes GDTF + proxies) ;
        // seuls les cubes des projecteurs soloés (déclaratifs) restent visibles.
        runCatching { geometryRoot.childNodes.forEach { it.isVisible = false } }
        lod.soloActive = true
    }
}

/** Ressources GPU du quad satellite 3D à libérer à chaque reconstruction. */
private class SatGpu {
    var texture: com.google.android.filament.Texture? = null
    var material: MaterialInstance? = null
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
    hiddenLayers: Set<String> = emptySet(),
    calibration: com.minou.mvrviewer.mvr.GeoCalibration? = null,
    satellite: com.minou.mvrviewer.mvr.SatelliteOverlay? = null,
    // Câblage (phase 4/5) : sert UNIQUEMENT au texte des étiquettes 3D pour les
    // champs Socapex / Ligne DMX (mêmes tables que la vue plan). Hors ces deux
    // champs, la 3D n'utilise pas le câblage.
    cabling: PowerCablingState = remember { PowerCablingState() },
    dmxCabling: DmxCablingState = remember { DmxCablingState() },
    onShowPlan: () -> Unit,
    onShowPatch: () -> Unit,
    onShowGdtfShare: () -> Unit,
    onShowUniverse: () -> Unit = {},
    onShowCabling: () -> Unit = {},
    onShowAccount: (() -> Unit)? = null,
    onShareProject: (() -> Unit)? = null,
    onShowHistory: (() -> Unit)? = null,
    onJoinProject: (() -> Unit)? = null,
    // Solo (parité iOS / vue plan) : ensemble d'INSTANCES à isoler (mvrInstanceKey).
    // Vide = aucun filtre (on montre tout). Hissé dans SceneScreen et PARTAGÉ avec
    // la vue plan → le solo survit aux bascules 3D ↔ plan.
    soloElements: Set<String> = emptySet(),
    onSetSoloElements: (Set<String>) -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Couleur de fond 3D = SKYBOX de la scène Filament (SceneView pose
    // scene.skybox = environment.skybox ; c'est CE qui remplit le fond, pas le
    // clearColor). On garde l'IBL de l'environnement par défaut (éclairage
    // inchangé) et on n'échange que le skybox. UN seul skybox, dont on met la
    // couleur à jour en direct (setColor) → aucune fuite. Filament attend du
    // LINÉAIRE → conversion depuis le sRGB du sélecteur.
    // baseEnv = environnement PAR DÉFAUT via le loader (le MÊME que Scene utilise
    // en interne) : il charge l'IBL KTX embarqué qui éclaire la scène. On garde
    // son IBL + ses harmoniques et on n'échange QUE le skybox (copy) — sinon
    // l'éclairage s'assombrit (rememberEnvironment(engine) n'a pas d'IBL).
    val environmentLoader = rememberEnvironmentLoader(engine)
    val baseEnv = rememberEnvironment(environmentLoader)
    val skybox = remember(engine) { Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine) }
    val bgEnv = remember(baseEnv, skybox) { baseEnv.copy(skybox = skybox) }
    LaunchedEffect(skybox, options.background3D) {
        val c = options.background3D
        skybox.setColor(srgbToLinear(c.red).toFloat(), srgbToLinear(c.green).toFloat(), srgbToLinear(c.blue).toFloat(), 1f)
    }

    val center = remember(scene) { sceneCenterMm(scene) }
    val layout = remember(scene, center) { fixtureLayout(scene, center) }
    // scene.fixtures est un GETTER CALCULÉ (allObjects.filter{…}) : chaque accès
    // refiltre toute la liste. On le fige UNE fois (sinon le lire dans la boucle de
    // dessin des étiquettes, à chaque frame de mouvement caméra, serait O(n²)).
    // Aligné 1:1 avec layout.positions quand la scène a des projecteurs.
    val sceneFixtures = remember(scene) { scene.fixtures }
    // Clés d'INSTANCE (mvrInstanceKey) alignées 1:1 sur sceneFixtures / layout —
    // servent au filtrage solo (3D) et au marquage des étiquettes soloées.
    val fixtureKeys = remember(sceneFixtures) { sceneFixtures.map { mvrInstanceKey(it) } }

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
    // Matériaux DXF 3D par couleur d'entité résolue (cache réutilisé entre
    // reconstructions → pas de fuite de MaterialInstance ; ~qq dizaines de couleurs).
    val dxfMatByColor = remember(materialLoader) { HashMap<Int, MaterialInstance>() }
    // Fond satellite géo-référencé posé au sol (quad texturé) — même image et
    // mêmes coins monde-mm que la vue plan, donc l'alignement est identique.
    val satRoot = rememberNode(engine)
    // Texture + instance de matériau du quad satellite courant, gardées pour être
    // LIBÉRÉES à la reconstruction (changement d'image ou d'opacité) : sinon
    // chaque rebuild fuyait une texture GPU (l'image fait ~1–3 Mo).
    val satGpu = remember { SatGpu() }
    // Marqueur « ma position » (GPS) en 3D : nœud dédié, mis à jour SANS
    // reconstruire la scène (même principe non destructif que le quad satellite /
    // le plan DXF). Bleu, non éclairé → toujours visible.
    val markerRoot = rememberNode(engine)
    val markerMaterial = remember(materialLoader) { materialLoader.createUnlitColorInstance(0xFF2979FF.toInt()) }
    var showLocation by remember { mutableStateOf(false) }
    val gps by rememberUserLocation(showLocation)
    var status by remember(scene) { mutableStateOf("chargement 3D…") }
    // LOD d'interaction : les petits objets (sièges, accessoires) sont masqués
    // pendant que la caméra bouge, réaffichés à l'arrêt — divise les draw calls
    // en navigation (comme iOS), sans toucher au rendu au repos.
    val lod = remember(scene) { LodState() }
    // Indices (dans scene.fixtures) des projecteurs rendus avec leur VRAIE
    // silhouette GDTF — leurs cubes de repli sont masqués.
    var gdtfFixtures by remember(scene) { mutableStateOf(emptySet<Int>()) }
    // Bumpé à la FIN de chaque (re)construction 3D → signal FIABLE de « scène
    // prête » pour ré-appliquer le filtre solo (indépendant de la présence de
    // silhouettes GDTF, contrairement à gdtfFixtures qui peut rester vide).
    var buildTick by remember(scene) { mutableIntStateOf(0) }
    // Projecteurs qui ont DÉJÀ leur propre géométrie 3D (rendue par 2a/2b) : ni
    // silhouette GDTF (évite la superposition), ni cube de repli.
    val ownGeomFixtures = remember(scene) { fixturesWithOwnGeometry(scene) }

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
        // Détacher TOUT d'un coup : removeChildNode() recalcule le Set d'enfants à
        // CHAQUE appel (O(n)) → détacher des milliers de nœuds un par un = O(n²) et
        // gelait le thread principal au rebuild GDTF. clearChildNodes() = un seul
        // recalcul ; on détruit ensuite les ressources Filament de chacun.
        val oldChildren = geometryRoot.childNodes.toList()
        runCatching { geometryRoot.clearChildNodes() }
        // destroy() par nœud = un appel natif (TransformManager.nDestroy) → détruire
        // des MILLIERS de nœuds d'affilée gèlerait le thread principal (> 6 s = ANR)
        // au rebuild GDTF d'un gros show. On rend la main tous les 128 nœuds.
        oldChildren.forEachIndexed { idx, n ->
            runCatching { n.destroy() }
            if (idx and 127 == 0) yield()
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
        // NB : on ne masque PLUS la géométrie pendant la construction. Tout
        // révéler d'un coup à la fin concentrait le tout premier rendu de la
        // scène complète (des milliers de nœuds) dans UNE seule trame → gros
        // blocage du thread principal (ANR « MVR Viewer ne répond pas », signalé
        // par Samsung Device Care). En construisant à vue, chaque trame ne rend
        // qu'un incrément → le coût est étalé et l'appli reste réactive.
        val conv = conversionMatrix(center)

        // Découpe temporelle : les appels Filament (build Geometry, GeometryNode,
        // addChildNode, createInstancedModel) DOIVENT rester sur le thread moteur,
        // mais on rend la main toutes les ~6 ms pour ne JAMAIS bloquer l'UI plus
        // d'une trame — c'était la cause de l'ANR au chargement d'un gros show.
        // On rend la main TRÈS souvent (~8 ms) pour ne jamais bloquer l'UI : un
        // seuil trop haut (40 ms) laissait le thread principal enchaîner trop de
        // travail Filament d'affilée → sur un appareil lent, ça franchissait le
        // seuil d'ANR (5 s) et déclenchait « l'appli ne répond pas ». La réactivité
        // prime sur la vitesse brute de chargement.
        var lastYield = System.nanoTime()
        suspend fun slice() {
            val now = System.nanoTime()
            if (now - lastYield > 16_000_000L) { yield(); lastYield = System.nanoTime() }
        }

        // AJOUT DES NŒUDS PAR LOTS — correctif du gel confirmé par le journal :
        // Node.addChildNode() recalcule tout le Set d'enfants du parent à CHAQUE
        // appel (setChildNodes fait un diff de Set). Ajouter des milliers d'objets
        // un par un à geometryRoot était donc O(n²) → le thread principal partait
        // dans des millions d'opérations HashSet (pile bloquée dans
        // Node.setChildNodes). On accumule et on pousse le lot en UN SEUL
        // addChildNodes(Set) (un seul recalcul), toutes les ~200 entrées.
        val pendingAdd = HashSet<io.github.sceneview.node.Node>(400)
        suspend fun flushAdds() {
            if (pendingAdd.isEmpty()) return
            runCatching { geometryRoot.addChildNodes(HashSet(pendingAdd)) }
            pendingAdd.clear()
            slice()
        }
        suspend fun enqueueAdd(node: io.github.sceneview.node.Node) {
            pendingAdd.add(node)
            if (pendingAdd.size >= 200) flushAdds()
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
                enqueueAdd(node)
                if (small) lod.nodes.add(node)
                nodes++; triangles += bm.triangles
            }
            placed++
            if (placed % 40 == 0) status = "$placed objets 3D…"
        }

        // 2b) .glb : createInstancedModel (N instances d'un même fichier = 1 seul
        //    parse), extraction du zip PAR LOTS de fichiers uniques.
        if (refs.glb.isNotEmpty() && nodes < sceneNodeBudget) {
            val byFile = LinkedHashMap<String, MutableList<Mat4>>()
            for (r in refs.glb) byFile.getOrPut(r.fileName) { mutableListOf() }.add(r.world)
            val resolver: (String) -> java.nio.Buffer? = { uri ->
                imageBytes[uri.substringAfterLast('/')]?.let { java.nio.ByteBuffer.wrap(it) }
            }
            // PIPELINE : on décompresse le PROCHAIN lot de .glb en arrière-plan
            // (Dispatchers.IO) PENDANT que le lot courant est construit sur le
            // thread moteur (createInstancedModel). L'extraction (CPU) et le build
            // (GPU) se recouvrent → gros gain sur un show à 14 000 .glb (comme le
            // décodeur parallèle iOS). Extraction EN MÉMOIRE (le zip est déjà chargé).
            val chunks = byFile.keys.chunked(250)
            kotlinx.coroutines.coroutineScope {
                var pending = chunks.firstOrNull()?.let {
                    async(Dispatchers.IO) { MvrParser.extractEntries(mvrBytes, it.toSet()) }
                }
                loop@ for (ci in chunks.indices) {
                    val bytesMap = pending?.await() ?: emptyMap()
                    // Extraction du lot suivant lancée AVANT de construire celui-ci.
                    pending = chunks.getOrNull(ci + 1)?.let {
                        async(Dispatchers.IO) { MvrParser.extractEntries(mvrBytes, it.toSet()) }
                    }
                    for (name in chunks[ci]) {
                        val remaining = sceneNodeBudget - nodes
                        if (remaining <= 0) { truncated = true; pending?.cancel(); break@loop }
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
                            enqueueAdd(node)
                        }
                        nodes += take
                        placed += take
                        status = "$placed objets 3D…"
                    }
                }
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
            // GDTF = REPLI : on ignore les projecteurs qui ont déjà leur géométrie
            // propre (sinon silhouette GDTF superposée au modèle embarqué).
            if (!s.isNullOrEmpty() && fi2 !in ownGeomFixtures) bySpec.getOrPut(s) { mutableListOf() }.add(fi2)
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
                                enqueueAdd(node)
                                lod.nodes.add(node)   // détail masqué en navigation
                                nodes++
                            }
                            any = true
                        } else {
                            val inst = b.glbQueue.removeFirstOrNull() ?: continue
                            val node = io.github.sceneview.node.ModelNode(inst)
                            node.transform = world
                            enqueueAdd(node)
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
                            enqueueAdd(proxy)
                            lod.proxies.add(proxy)
                        }
                    }
                    if (gdtfDone.size % 40 == 0) status = "$placed objets · ${gdtfDone.size} proj. GDTF…"
                    slice()
                }
                break@sourceLoop  // spec rendu — pas besoin du repli
            }
        }
        flushAdds()   // pousse le dernier lot de nœuds en attente
        gdtfFixtures = gdtfDone
        buildTick++   // scène prête → le solo (s'il est actif) sera ré-appliqué

        status = "$placed objets 3D" +
            (if (gdtfDone.isNotEmpty()) " · ${gdtfDone.size} proj. GDTF" else "") +
            if (truncated) " (tronqué)" else ""
    }

    // Solo (3D) : n'afficher QUE les projecteurs soloés — le reste (décor,
    // silhouettes GDTF, cubes des autres projecteurs) est masqué. Filtrage PAR
    // VISIBILITÉ (aucune reconstruction). Re-déclenché quand le solo change OU
    // quand la scène vient d'être (re)construite (buildTick) : un solo hérité de
    // la vue plan est ainsi ré-appliqué dès que la scène est prête.
    LaunchedEffect(soloElements, buildTick) {
        applySolo3D(geometryRoot, lod, soloElements)
    }

    // Plan de repère DXF en 3D : lignes au sol, placées par la transformée du plan
    // (offset/rotation/échelle/hauteur), dans le MÊME repère monde que les
    // projecteurs (le retour en 3D recompose → reflète les derniers réglages).
    LaunchedEffect(referencePlan, hiddenLayers) {
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
            val up = Float3(0f, 1f, 0f)
            // Sommets/index groupés PAR COULEUR d'entité → un maillage par couleur.
            val vByColor = HashMap<Int, ArrayList<Geometry.Vertex>>()
            val iByColor = HashMap<Int, ArrayList<Int>>()
            var total = 0
            outer@ for (pl in rp.plan.polylines) {
                if (pl.layer in hiddenLayers) continue@outer
                val pts = pl.points; val n = pts.size / 2
                if (n < 2) continue
                val verts = vByColor.getOrPut(pl.color) { ArrayList() }
                val idx = iByColor.getOrPut(pl.color) { ArrayList() }
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
                    total += 4
                }
                if (total >= MAX_DXF_VERTS) break@outer
            }
            vByColor to iByColor
        }
        val (vByColor, iByColor) = prep
        if (vByColor.isEmpty()) return@LaunchedEffect
        // Build moteur (thread principal) : une géométrie + un matériau par couleur.
        for ((rgb, verts) in vByColor) {
            val idx = iByColor[rgb] ?: continue
            if (verts.size < 3 || idx.isEmpty()) continue
            val geom = Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
                .vertices(verts).primitivesIndices(listOf(idx)).build(engine)
            val mat = dxfMatByColor.getOrPut(rgb) { materialLoader.createUnlitColorInstance(dxf3DColorInt(rgb)) }
            dxfRoot.addChildNode(GeometryNode(engine, geom, listOf(mat)))
        }
    }

    // Fond satellite en 3D : un quad texturé posé au sol, aux MÊMES 4 coins
    // monde-mm que la vue plan (donc parfaitement aligné avec elle et avec les
    // projecteurs). Reconstruit quand l'image OU l'opacité change. L'opacité est
    // CUITE dans l'alpha du bitmap : le matériau « image » de SceneView n'expose
    // que le paramètre `texture` (pas d'alpha réglable) → on la peint à l'alpha
    // voulu, puis on téléverse. Débounce léger pour absorber le glissé du curseur.
    LaunchedEffect(satellite, options.showSatellite, options.satelliteOpacity) {
        // Purge de l'ancien quad + ses ressources GPU (idempotent).
        satRoot.childNodes.toList().forEach {
            satRoot.removeChildNode(it); runCatching { it.destroy() }
        }
        satGpu.material?.let { m -> runCatching { engine.destroyMaterialInstance(m) } }; satGpu.material = null
        satGpu.texture?.let { t -> runCatching { engine.destroyTexture(t) } }; satGpu.texture = null
        val sat = satellite
        if (!options.showSatellite || sat == null || sat.bitmap.width <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(120)   // absorbe les frames de glissé du curseur d'opacité
        // Alpha cuite dans le bitmap (hors thread principal).
        val op = options.satelliteOpacity.coerceIn(0.05f, 1f)
        val baked = withContext(Dispatchers.Default) {
            runCatching {
                val src = sat.bitmap
                val out = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
                val cnv = android.graphics.Canvas(out)
                val paint = android.graphics.Paint().apply {
                    isFilterBitmap = true
                    alpha = (op * 255f).toInt().coerceIn(0, 255)
                }
                cnv.drawBitmap(src, 0f, 0f, paint)
                out
            }.getOrNull()
        } ?: return@LaunchedEffect
        // Texture + matériau image (unlit, texturé, mélangé). CLAMP pour ne pas
        // répéter les bords, filtrage linéaire. Sans culling → visible aussi de
        // dessous ; sans écriture de profondeur → ne masque rien au-dessus.
        val tex = runCatching {
            io.github.sceneview.texture.ImageTexture.Builder().bitmap(baked).build(engine)
        }.getOrNull() ?: return@LaunchedEffect
        val sampler = com.google.android.filament.TextureSampler(
            com.google.android.filament.TextureSampler.MinFilter.LINEAR,
            com.google.android.filament.TextureSampler.MagFilter.LINEAR,
            com.google.android.filament.TextureSampler.WrapMode.CLAMP_TO_EDGE
        )
        val mat = materialLoader.createImageInstance(tex, sampler).apply {
            runCatching { setCullingMode(com.google.android.filament.Material.CullingMode.NONE) }
            runCatching { setDepthWrite(false) }
        }
        satGpu.texture = tex; satGpu.material = mat
        // Quad aux 4 coins monde-mm, projetés en Filament comme la géométrie 3ds,
        // 2 cm SOUS le sol MVR (z=0) pour passer sous le plan DXF sans z-fighting.
        val cx = center.x; val cy = center.y; val cz = center.z
        val groundFy = -cz / 1000f - 0.02f
        fun fp(x: Float, y: Float) = Float3((x - cx) / 1000f, groundFy, -(y - cy) / 1000f)
        val up = Float3(0f, 1f, 0f)
        // NO(0,0) NE(1,0) SE(1,1) SO(0,1) : l'image est nord-en-haut (bbox maxLat
        // en première ligne), comme la vue plan.
        val quad = listOf(
            Geometry.Vertex(position = fp(sat.nwX, sat.nwY), normal = up, uvCoordinate = Float2(0f, 0f)),
            Geometry.Vertex(position = fp(sat.neX, sat.neY), normal = up, uvCoordinate = Float2(1f, 0f)),
            Geometry.Vertex(position = fp(sat.seX, sat.seY), normal = up, uvCoordinate = Float2(1f, 1f)),
            Geometry.Vertex(position = fp(sat.swX, sat.swY), normal = up, uvCoordinate = Float2(0f, 1f))
        )
        val satGeom = Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
            .vertices(quad).primitivesIndices(listOf(listOf(0, 1, 2, 0, 2, 3))).build(engine)
        satRoot.addChildNode(GeometryNode(engine, satGeom, listOf(mat)))
    }

    // Marqueur GPS : reconstruit à chaque relevé (peu fréquent) tant que
    // « ma position » est actif ET que la scène est calibrée. Un poteau + une
    // tête, à la position monde de l'utilisateur convertie en repère Filament.
    LaunchedEffect(gps, showLocation, options.gpsMarkerScale, calibration?.isCalibrated, layout) {
        markerRoot.childNodes.toList().forEach {
            markerRoot.removeChildNode(it); runCatching { it.destroy() }
        }
        val g = gps
        val cal = calibration
        if (!showLocation || cal == null || !cal.isCalibrated || g == null) return@LaunchedEffect
        val wp = cal.worldPosition(g.latitude, g.longitude) ?: return@LaunchedEffect
        val cx = center.x; val cy = center.y; val cz = center.z
        val groundFy = -cz / 1000f
        val fx = (wp.first - cx) / 1000f
        val fz = -(wp.second - cy) / 1000f
        // Unité proportionnelle à la scène (mètres Filament), bornée et réglable.
        val u = (layout.radius * 0.02f).coerceIn(0.15f, 4f) * options.gpsMarkerScale
        val poleH = u * 5f
        val pole = io.github.sceneview.node.CubeNode(
            engine, Float3(u * 0.35f, poleH, u * 0.35f),
            Float3(fx, groundFy + poleH / 2f, fz), markerMaterial
        )
        val head = io.github.sceneview.node.CubeNode(
            engine, Float3(u * 1.6f, u * 1.6f, u * 1.6f),
            Float3(fx, groundFy + poleH + u * 0.8f, fz), markerMaterial
        )
        markerRoot.addChildNode(pole)
        markerRoot.addChildNode(head)
    }

    val camHome = Float3(0f, layout.radius * 0.7f, layout.radius * 1.9f)
    val cameraNode = rememberCameraNode(engine) { position = camHome }
    // PIVOT de l'orbite. Mutable : une recherche de projecteur y amène la caméra
    // (et les presets de vue « Dessus / Face / Côté » s'appliquent alors autour
    // du projecteur trouvé, ce qui est exactement l'usage terrain).
    var target by remember(layout) {
        mutableStateOf(Float3(0f, 0f, 0f), androidx.compose.runtime.neverEqualPolicy())
    }
    // Point de vue courant (State) : le CHANGER re-crée le manipulateur (remember
    // keyé ci-dessous) → SceneView ré-attache le manipulateur au détecteur de
    // gestes et re-sème le viewport, donc la caméra SAUTE au preset ET l'orbite
    // continue de fonctionner. (rememberCameraManipulator, lui, n'est PAS re-keyé
    // sur orbitHomePosition quand on passe son propre créateur → on hisse le
    // nôtre.) Vitesse de pinch-zoom échelonnée sur le rayon de la scène.
    // neverEqualPolicy : re-choisir le MÊME preset (ou « réinitialiser la vue »
    // après avoir seulement tourné autour) doit re-créer le manipulateur.
    // Avec l'égalité structurelle par défaut, Float3 étant une data class,
    // réécrire la même valeur n'était pas un changement → bouton sans effet.
    var camEye by remember(layout) {
        mutableStateOf(camHome, androidx.compose.runtime.neverEqualPolicy())
    }
    val zoomDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val manipulator = remember(camEye, target, layout, zoomDensity) {
        // On construit NOUS-MÊMES le manipulateur Filament pour pouvoir piloter le
        // zoom (cf. DistanceZoomManipulator). zoomSpeed = 1 → une unité de
        // `scroll` = un mètre, ce qui rend notre calcul lisible.
        val fm = com.google.android.filament.utils.Manipulator.Builder()
            .orbitHomePosition(camEye.x, camEye.y, camEye.z)
            .targetPosition(target.x, target.y, target.z)
            .orbitSpeed(0.003f, 0.003f)   // mêmes valeurs que le manipulateur par défaut
            .zoomSpeed(1f)
            .build(com.google.android.filament.utils.Manipulator.Mode.ORBIT)
        val dx = camEye.x - target.x; val dy = camEye.y - target.y; val dz = camEye.z - target.z
        DistanceZoomManipulator(
            fm = fm,
            startDistance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz),
            minDistance = (layout.radius * 0.002f).coerceAtLeast(0.05f),
            maxDistance = (layout.radius * 60f).coerceAtLeast(50f),
            density = if (zoomDensity > 0f) zoomDensity else 1f,
            inner = io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator(fm),
        )
    }

    // ---- Sélection par cadre (rectangle) dans la vue 3D (comme la vue plan et
    // iOS) : en « mode rectangle », le glissé trace un cadre et sélectionne tous
    // les projecteurs dont la projection écran tombe dedans. Hors ce mode, le
    // glissé pilote la caméra normalement. `selected` = index dans layout/fixtures.
    val selected = remember(scene) { mutableStateListOf<Int>() }
    var rectMode by remember(scene) { mutableStateOf(false) }
    var rectStart by remember { mutableStateOf<Offset?>(null) }
    var rectEnd by remember { mutableStateOf<Offset?>(null) }
    // Bumpé quand la caméra bouge → force le re-calcul des projections écran des
    // surbrillances (les positions monde ne sont pas des états Compose).
    var projVersion by remember(scene) { mutableIntStateOf(0) }

    // ---- ÉTIQUETTES DE PROJECTEURS EN 3D (parité vue plan) ------------------
    // Contenu = les MÊMES champs que le plan (options.labelFields), assemblés avec
    // le résolveur d'étiquette PARTAGÉ (labelFieldText) — ID / DMX / MODE / NAME et,
    // pour Socapex / Ligne DMX, le texte issu du câblage (mêmes tables que le plan,
    // via CablingLabels). On pré-calcule UNE liste de lignes par projecteur, refaite
    // seulement quand les champs choisis ou le câblage changent (pas à chaque frame).
    val textMeasurer = rememberTextMeasurer()
    val labelLinesByFixture: List<List<String>> =
        remember(sceneFixtures, options.labelFields, cabling.version, dmxCabling.version) {
            val fields = options.labelFields
            if (fields.isEmpty()) List(sceneFixtures.size) { emptyList() }
            else {
                // Tables câblage figées fixtureKey → texte (mêmes règles que PlanScreen).
                val socaById = cabling.distributors.associateBy { it.id }
                val dmxById = dmxCabling.distributors.associateBy { it.id }
                val socaLabel = cabling.assignments.values.mapNotNull { a ->
                    socaById[a.distributor]?.let { d -> a.fixture to CablingLabels.soca(d.name, a.circuit) }
                }.toMap()
                val dmxLabel = dmxCabling.assignments.values.mapNotNull { a ->
                    dmxById[a.distributor]?.let { d -> a.fixture to CablingLabels.dmx(d.name, d.coreCount, a.core) }
                }.toMap()
                val cablingText: (PlanFixture, LabelContent) -> String? = { f, c ->
                    when (c) {
                        LabelContent.SOCAPEX -> socaLabel[f.key]
                        LabelContent.DMX_LINE -> dmxLabel[f.key]
                        else -> null
                    }
                }
                val idMat = Mat4()   // transform inutile pour le texte (seul le monde compte)
                sceneFixtures.map { o ->
                    val pf = PlanFixture(
                        mvrInstanceKey(o), 0f, 0f, o.fixtureId, o.name, o.gdtfSpec,
                        o.layerName, o.addresses.joinToString(","), o.gdtfMode, idMat
                    )
                    val lines = ArrayList<String>(fields.size)
                    // Ordre de l'énumération (comme labelBlocks) → 1re ligne = N°.
                    for (c in LabelContent.entries) {
                        if (c !in fields) continue
                        labelFieldText(pf, c, cablingText)?.let { lines.add(it) }
                    }
                    lines
                }
            }
        }
    // Y a-t-il au moins une étiquette à montrer ? (évite un scan O(n) dans onFrame.)
    val hasAnyLabel = remember(labelLinesByFixture) { labelLinesByFixture.any { it.isNotEmpty() } }

    // ---- Mesure entre deux projecteurs (3D) ----
    // On ne mémorise que des INDICES de projecteur, pas des points : en 3D un
    // toucher est un RAYON, pas un point — un « point libre » n'aurait pas de
    // profondeur définie. La mesure 3D est donc, par construction, toujours
    // accrochée à un centre réel. Les positions monde Filament sont en mètres
    // (= (mm − centre) / 1000 à la construction du layout).
    var measureMode by remember(scene) { mutableStateOf(false) }
    var measureI by remember(scene) { mutableStateOf<Int?>(null) }
    var measureJ by remember(scene) { mutableStateOf<Int?>(null) }
    // Position (fenêtre) du coin haut-gauche de la zone SceneView. Sert à
    // convertir les coordonnées ÉCRAN-ABSOLUES d'un MotionEvent (e.rawX/rawY, seul
    // repère fiable — e.x/e.y sont dans un repère décalé) vers le repère de la
    // SceneView = celui de worldToScreenPoint (comme la position d'un geste Compose).
    var scenePos by remember { mutableStateOf(Offset.Zero) }

    // Recherche d'un projecteur par N° / nom → le sélectionne (la surbrillance le
    // montre s'il est à l'écran ; sinon l'utilisateur oriente l'orbite pour le
    // trouver). Classement : id exact > préfixe > contenu > nom.
    var query by remember(scene) { mutableStateOf("") }
    fun doSearch3D() {
        val q = query.trim(); if (q.isEmpty()) return
        val fx = scene.fixtures
        var best = -1; var bestRank = Int.MAX_VALUE
        fx.forEachIndexed { i, f ->
            if (i !in layout.positions.indices || !layout.valid[i]) return@forEachIndexed
            val id = f.fixtureId
            val rank = when {
                id != null && id.equals(q, true) -> 0
                id != null && id.startsWith(q, true) -> 1
                id != null && id.contains(q, true) -> 2
                f.name.contains(q, true) -> 3
                else -> Int.MAX_VALUE
            }
            if (rank < bestRank) { bestRank = rank; best = i }
        }
        if (best < 0) return
        selected.clear(); selected.add(best)
        // …et on Y VA : le pivot se pose sur le projecteur et la caméra se place
        // à courte distance, en CONSERVANT la direction de vue courante (on ne
        // désoriente pas l'utilisateur, on se rapproche seulement).
        val p = layout.positions[best]
        val cam = cameraNode.worldPosition
        var vx = cam.x - target.x; var vy = cam.y - target.y; var vz = cam.z - target.z
        val len = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
        if (!len.isFinite() || len < 1e-3f) {   // caméra sur le pivot : vue 3/4 par défaut
            vx = 0f; vy = 0.45f; vz = 1f
            val n = kotlin.math.sqrt(vy * vy + vz * vz); vy /= n; vz /= n
        } else { vx /= len; vy /= len; vz /= len }
        // Assez près pour lire le projecteur, assez loin pour garder du contexte.
        val d = (layout.cube * 10f).coerceIn(1.5f, layout.radius * 0.5f)
        target = p
        camEye = Float3(p.x + vx * d, p.y + vy * d, p.z + vz * d)
    }

    // Projette la position monde (Filament) d'un projecteur en pixels écran de la
    // SceneView (origine coin haut-gauche, comme Compose). null si derrière la
    // caméra ou invalide. La caméra orbite → son axe avant = vers la cible.
    fun projectFixture(i: Int): Offset? {
        if (i !in layout.positions.indices || !layout.valid[i]) return null
        val wp = layout.positions[i]
        val cam = cameraNode.worldPosition
        val fdx = target.x - cam.x; val fdy = target.y - cam.y; val fdz = target.z - cam.z
        val dx = wp.x - cam.x; val dy = wp.y - cam.y; val dz = wp.z - cam.z
        if (fdx * dx + fdy * dy + fdz * dz <= 0f) return null // derrière la caméra
        val sp = runCatching {
            cameraNode.worldToScreenPoint(io.github.sceneview.collision.Vector3(wp.x, wp.y, wp.z))
        }.getOrNull() ?: return null
        if (!sp.x.isFinite() || !sp.y.isFinite()) return null
        return Offset(sp.x, sp.y)
    }

    // Comme projectFixture, mais pour un point monde ARBITRAIRE (les 8 coins du
    // cube de sélection). null si derrière la caméra ou non fini.
    fun projectPoint(x: Float, y: Float, z: Float): Offset? {
        val cam = cameraNode.worldPosition
        val fdx = target.x - cam.x; val fdy = target.y - cam.y; val fdz = target.z - cam.z
        val dx = x - cam.x; val dy = y - cam.y; val dz = z - cam.z
        if (fdx * dx + fdy * dy + fdz * dz <= 0f) return null // derrière la caméra
        val sp = runCatching {
            cameraNode.worldToScreenPoint(io.github.sceneview.collision.Vector3(x, y, z))
        }.getOrNull() ?: return null
        if (!sp.x.isFinite() || !sp.y.isFinite()) return null
        return Offset(sp.x, sp.y)
    }

    // TAP : sélectionne le projecteur le plus proche du point touché (≤ ~55 px)
    // → l'AJOUTE (ou le retire s'il y est déjà) ; toucher dans le vide / sur un
    // élément qui n'est PAS un projecteur → désélectionne TOUT. On travaille dans
    // le repère de l'overlay Compose (mêmes coordonnées que worldToScreenPoint —
    // le geste natif de SceneView, lui, décalait de la hauteur de la barre du haut).
    fun handleTap(pos: Offset) {
        var best = -1; var bestD = 55f
        for (i in layout.positions.indices) {
            val o = projectFixture(i) ?: continue
            val d = kotlin.math.hypot(o.x - pos.x, o.y - pos.y)
            if (d < bestD) { bestD = d; best = i }
        }
        // MESURE : le toucher ne sert qu'à désigner les deux projecteurs, et
        // toucher le vide n'efface rien (en 3D on tourne beaucoup autour de la
        // scène entre les deux points ; perdre la mesure au moindre tap raté
        // serait insupportable). Un 3e toucher démarre une nouvelle mesure.
        if (measureMode) {
            if (best < 0) return
            if (measureI == null || measureJ != null) { measureI = best; measureJ = null }
            else if (best != measureI) measureJ = best
            projVersion++
            return
        }
        if (best < 0) selected.clear()
        else if (!selected.remove(best)) selected.add(best)
    }

    // Tap via le détecteur natif de SceneView (il FIRE bien, contrairement à
    // detectTapGestures/awaitEachGesture d'un overlay Compose que la SceneView
    // court-circuite). Les coordonnées MotionEvent sont dans le repère de la
    // SceneView = même repère que worldToScreenPoint → handleTap(Offset(e.x,e.y)).
    val tapListener = remember(scene) {
        object : io.github.sceneview.gesture.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent, node: io.github.sceneview.node.Node?) {
                handleTap(Offset(e.rawX - scenePos.x, e.rawY - scenePos.y))
            }
        }
    }

    // Barre du haut dans une vraie TopAppBar (au-dessus de la SceneView). NB :
    // contrairement à ce qu'on croyait, des contrôles Compose flottants PAR-DESSUS
    // la SceneView SONT bien visibles ET cliquables, et un overlay pointerInput
    // capte le glissé avant la caméra — c'est ce qui rend possible la barre d'outils
    // de sélection et le cadre de sélection plus bas.
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
                // Points de vue caméra (dessus / face / côté / iso / reset) : on
                // change camEye → le manipulateur est re-créé (remember keyé) et
                // SceneView re-sème la caméra à ce point de vue, orbite préservée.
                var camMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { camMenu = true }) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Points de vue",
                            tint = LocalContentColor.current)
                    }
                    DropdownMenu(expanded = camMenu, onDismissRequest = { camMenu = false }) {
                        val r = layout.radius * 2f
                        fun set(x: Float, y: Float, z: Float) {
                            camEye = Float3(target.x + x, target.y + y, target.z + z); camMenu = false
                        }
                        DropdownMenuItem(text = { Text("Dessus") }, onClick = { set(0f, r, 0.001f) })
                        DropdownMenuItem(text = { Text("Face") }, onClick = { set(0f, 0f, r) })
                        DropdownMenuItem(text = { Text("Côté") }, onClick = { set(r, 0f, 0f) })
                        DropdownMenuItem(text = { Text("Isométrique") }, onClick = { set(0.7f * r, 0.7f * r, 0.7f * r) })
                        DropdownMenuItem(text = { Text("Réinitialiser la vue") },
                            // Remet AUSSI le pivot au centre du show : sinon, après
                            // une recherche, on « réinitialisait » autour du
                            // projecteur trouvé.
                            onClick = { target = Float3(0f, 0f, 0f); camEye = camHome; camMenu = false })
                    }
                }
                SceneOptionsMenu(
                    options = options, tint = LocalContentColor.current,
                    onShowPlan = onShowPlan, onShowPatch = onShowPatch,
                    onShowGdtfShare = onShowGdtfShare, onShowUniverse = onShowUniverse,
                    onShowCabling = onShowCabling,
                    onShowAccount = onShowAccount, onShareProject = onShareProject,
                    onShowHistory = onShowHistory, onJoinProject = onJoinProject,
                    // Étiquettes de projecteurs aussi en 3D : même bascule + mêmes
                    // champs/taille que le plan (SceneOptions partagé).
                    showLabelsToggle = true,
                    showSatelliteToggle = calibration?.isCalibrated == true,
                    background = options.background3D,
                    backgroundDefault = BackgroundColorStore.DEFAULT_3D,
                    backgroundPresets = BG_3D_PRESETS,
                    onPickBackground = { options.background3D = it }
                )
            }
        )
        Box(modifier = Modifier.fillMaxSize().background(options.background3D)
            .onGloballyPositioned { scenePos = it.positionInWindow() }) {
            Scene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                cameraNode = cameraNode,
                cameraManipulator = manipulator,
                onGestureListener = tapListener,
                // Environnement à skybox coloré = couleur de fond choisie.
                environment = bgEnv,
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
                    // La caméra a bougé → re-projeter les surbrillances de sélection
                    // ET les étiquettes de projecteurs (elles suivent la caméra).
                    if (moved && (selected.isNotEmpty() || measureI != null ||
                            (options.showLabels && hasAnyLabel))) projVersion++
                    // En mode solo, le filtre de visibilité prime : le LOD ne doit
                    // PAS réafficher le décor masqué (il rallumerait geometryRoot).
                    if (!lod.soloActive && (lod.nodes.isNotEmpty() || lod.proxies.isNotEmpty())) {
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
                Node(apply = { addChildNode(satRoot) })
                Node(apply = { addChildNode(geometryRoot) })
                Node(apply = { addChildNode(dxfRoot) })
                Node(apply = { addChildNode(markerRoot) })
                val s = Float3(layout.cube, layout.cube, layout.cube)
                val soloOn = soloElements.isNotEmpty()
                layout.positions.forEachIndexed { i, p ->
                    if (!layout.valid[i]) return@forEachIndexed
                    // Solo actif → un cube pour CHAQUE projecteur soloé : applySolo3D
                    // masque le décor et les silhouettes GDTF, donc on matérialise le
                    // soloé par son cube coloré. Sinon comportement normal : cube de
                    // repli seulement pour un projecteur « nu » (ni géométrie propre
                    // 2a/2b, ni silhouette GDTF).
                    val show = if (soloOn) {
                        val key = fixtureKeys.getOrNull(i); key != null && key in soloElements
                    } else (i !in gdtfFixtures && i !in ownGeomFixtures)
                    if (show) {
                        val mat = if (options.layerColors) fixtureMaterials.getValue(layout.colors[i]) else grayMaterial
                        CubeNode(size = s, materialInstance = mat, position = p)
                    }
                }
            }

            // Couche de sélection : présente UNIQUEMENT en mode rectangle, où elle
            // CAPTE le glissé (la SceneView en dessous ne bouge donc pas la caméra) et
            // trace le cadre. IMPORTANT : hors de ce mode on ne met AUCUN overlay
            // pointerInput — même inerte, il capterait le tap et empêcherait le
            // détecteur natif de la SceneView (tapListener) de le recevoir.
            if (rectMode) {
                Box(
                    Modifier.fillMaxSize().pointerInput(scene) {
                        detectDragGestures(
                            onDragStart = { rectStart = it; rectEnd = it },
                            onDrag = { change, _ -> change.consume(); rectEnd = change.position },
                            onDragEnd = {
                                val a = rectStart; val b = rectEnd
                                if (a != null && b != null) {
                                    val l = minOf(a.x, b.x); val r = maxOf(a.x, b.x)
                                    val t = minOf(a.y, b.y); val bo = maxOf(a.y, b.y)
                                    if (r - l > 8f && bo - t > 8f) {
                                        // ADDITIF : le cadre AJOUTE à la sélection existante
                                        // (plusieurs cadres cumulent), sans doublon.
                                        for (i in layout.positions.indices) {
                                            val o = projectFixture(i) ?: continue
                                            if (o.x in l..r && o.y in t..bo && i !in selected) selected.add(i)
                                        }
                                    }
                                }
                                rectStart = null; rectEnd = null
                            },
                            onDragCancel = { rectStart = null; rectEnd = null }
                        )
                    }
                )
            }
            // Étiquettes de projecteurs : dessinées dans un Canvas (comme les
            // surbrillances), donc elles NE captent PAS le tap et NE bloquent PAS
            // les gestes caméra. Redessinées via projVersion (bumpé pendant que la
            // caméra bouge) → elles suivent les projecteurs. BORNÉES : cullées au
            // viewport, un plafond de projections tentées et un plafond dessiné,
            // pour ne pas ramer sur un gros show pendant la navigation.
            if (options.showLabels && hasAnyLabel) {
                Canvas(Modifier.fillMaxSize()) {
                    projVersion // dépendance de redraw quand la caméra bouge
                    val vw = size.width; val vh = size.height
                    val soloOnLabels = soloElements.isNotEmpty()
                    var drawn = 0
                    var projected = 0
                    val n = minOf(labelLinesByFixture.size, sceneFixtures.size)
                    var i = 0
                    while (i < n && drawn < MAX_LABELS_3D && projected < MAX_LABEL_PROJECTIONS) {
                        val lines = labelLinesByFixture[i]
                        if (lines.isEmpty()) { i++; continue }
                        // Calque masqué (repère DXF/MVR) → pas d'étiquette.
                        if (sceneFixtures[i].layerName in hiddenLayers) { i++; continue }
                        // Solo actif → seules les étiquettes des projecteurs soloés.
                        if (soloOnLabels) {
                            val key = fixtureKeys.getOrNull(i)
                            if (key == null || key !in soloElements) { i++; continue }
                        }
                        projected++
                        val p = projectFixture(i)
                        if (p != null &&
                            p.x >= -LABEL_CULL_PX && p.x <= vw + LABEL_CULL_PX &&
                            p.y >= -LABEL_CULL_PX && p.y <= vh + LABEL_CULL_PX) {
                            drawFixtureLabel(textMeasurer, lines, p, options.labelSize)
                            drawn++
                        }
                        i++
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                projVersion // dépendance de redraw quand la caméra bouge
                // Cube en fil de fer autour de chaque projecteur sélectionné : les 8
                // coins d'une boîte (taille ∝ échelle scène) projetés un par un, puis
                // les 12 arêtes reliées. Suit la caméra via projVersion. Cyan bien
                // visible (souligné de noir pour tout fond), distinct du cercle jaune.
                selected.forEach { i ->
                    if (i !in layout.positions.indices || !layout.valid[i]) return@forEach
                    val p = layout.positions[i]
                    val hx = layout.cube * 1.15f
                    val c = arrayOfNulls<Offset>(8)
                    var k = 0
                    for (sx in SEL_CUBE_SIGNS) for (sy in SEL_CUBE_SIGNS) for (sz in SEL_CUBE_SIGNS) {
                        c[k++] = projectPoint(p.x + sx * hx, p.y + sy * hx, p.z + sz * hx)
                    }
                    for (e in SEL_CUBE_EDGES) {
                        val a = c[e.first]; val b = c[e.second]
                        if (a != null && b != null) {
                            drawLine(Color.Black.copy(alpha = 0.45f), a, b, strokeWidth = 4f)
                            drawLine(SELECTION_CUBE_COLOR, a, b, strokeWidth = 2f)
                        }
                    }
                }
                // Surbrillances des projecteurs sélectionnés.
                selected.forEach { i ->
                    val o = projectFixture(i) ?: return@forEach
                    drawCircle(Color(0xFFFFC400), radius = 26f, center = o, style = Stroke(width = 4f))
                }
                // Mesure : trait entre les deux projecteurs + anneaux distinctifs.
                // (La cote chiffrée est dans le bandeau du bas : pas de mesure de
                // texte à faire dans ce Canvas, qui est redessiné à chaque frame
                // de mouvement caméra.)
                val mi = measureI; val mj = measureJ
                if (measureMode && mi != null) {
                    val oa = projectFixture(mi)
                    val ob = mj?.let { projectFixture(it) }
                    if (oa != null && ob != null) {
                        drawLine(Color.Black.copy(alpha = 0.5f), oa, ob, strokeWidth = 6f)
                        drawLine(MEASURE_3D_COLOR, oa, ob, strokeWidth = 2.5f)
                    }
                    listOfNotNull(oa, ob).forEach { o ->
                        drawCircle(MEASURE_3D_COLOR, radius = 18f, center = o, style = Stroke(width = 3.5f))
                        drawCircle(MEASURE_3D_COLOR, radius = 4f, center = o)
                    }
                }
                // Cadre en cours de tracé.
                val a = rectStart; val b = rectEnd
                if (rectMode && a != null && b != null) {
                    val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                    val sz = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
                    drawRect(Color(0x33FFC400), topLeft = tl, size = sz)
                    drawRect(Color(0xFFFFC400), topLeft = tl, size = sz, style = Stroke(width = 2f))
                }
            }

            // Recherche d'un projecteur par N° (haut-droite) — le sélectionne.
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("N° projecteur") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch3D() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Black.copy(alpha = 0.45f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.35f),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                    focusedBorderColor = Color.White.copy(alpha = 0.7f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                    focusedLeadingIconColor = Color.White,
                    unfocusedLeadingIconColor = Color.White.copy(alpha = 0.7f),
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).width(180.dp)
            )

            // Barre d'outils flottante (bas-gauche) : bascule du mode rectangle +
            // effacer la sélection + position GPS (si calibré). Même emplacement que la vue plan.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
            ) {
                FilledIconToggleButton(
                    checked = rectMode,
                    onCheckedChange = {
                        rectMode = it
                        // Le cadre pose un overlay qui CAPTE le glissé : les deux
                        // modes ne peuvent pas cohabiter.
                        if (it) measureMode = false
                        if (!it) { rectStart = null; rectEnd = null }
                    }
                ) { Icon(Icons.Filled.Crop, contentDescription = "Sélection rectangle") }
                FilledIconToggleButton(
                    checked = measureMode,
                    onCheckedChange = {
                        measureMode = it
                        if (it) { rectMode = false; rectStart = null; rectEnd = null }
                        measureI = null; measureJ = null
                    }
                ) { Icon(Icons.Filled.Straighten, contentDescription = "Mesurer une distance") }
                // Solo : n'afficher QUE les projecteurs sélectionnés (le reste
                // masqué), en 3D ET en plan (soloElements partagé, hissé dans
                // SceneScreen). Visible dès qu'il y a une sélection ou qu'un solo est
                // déjà actif — activer isole la sélection courante, désactiver rétablit
                // tout (le solo est une VUE, pas une modification du show).
                if (selected.isNotEmpty() || soloElements.isNotEmpty()) {
                    FilledIconToggleButton(
                        checked = soloElements.isNotEmpty(),
                        onCheckedChange = { on ->
                            if (on) {
                                val keys = selected.mapNotNull { fixtureKeys.getOrNull(it) }.toSet()
                                if (keys.isNotEmpty()) onSetSoloElements(keys)
                            } else onSetSoloElements(emptySet())
                        }
                    ) { Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Solo : n'afficher que la sélection") }
                }
                if (selected.isNotEmpty()) {
                    FilledIconButton(onClick = { selected.clear() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Effacer la sélection")
                    }
                }
                // « Ma position » en 3D : dispo une fois la scène calibrée (comme le satellite).
                if (calibration?.isCalibrated == true) {
                    FilledIconToggleButton(checked = showLocation, onCheckedChange = { showLocation = it }) {
                        Icon(Icons.Filled.MyLocation, contentDescription = "Ma position (3D)")
                    }
                    if (showLocation) {
                        FilledIconButton(onClick = {
                            options.gpsMarkerScale = when {
                                options.gpsMarkerScale <= 0.7f -> 1f
                                options.gpsMarkerScale >= 1.5f -> 0.6f
                                else -> 1.6f
                            }
                        }) { Icon(Icons.Filled.PhotoSizeSelectSmall, contentDescription = "Taille du marqueur") }
                    }
                }
            }

            // Cote de la mesure (bas-centre) : le chiffre est ici plutôt que sur
            // le trait, pour ne pas mesurer du texte dans le Canvas redessiné à
            // chaque frame de mouvement caméra.
            if (measureMode) {
                val mi = measureI; val mj = measureJ
                val txt = if (mi != null && mj != null &&
                    mi in layout.positions.indices && mj in layout.positions.indices) {
                    val a = layout.positions[mi]; val b = layout.positions[mj]
                    // Filament = mètres → millimètres, l'unité de la cote.
                    val mm = kotlin.math.sqrt(
                        ((b.x - a.x).toDouble() * (b.x - a.x) +
                         (b.y - a.y).toDouble() * (b.y - a.y) +
                         (b.z - a.z).toDouble() * (b.z - a.z))
                    ) * 1000.0
                    val na = scene.fixtures.getOrNull(mi)?.fixtureId ?: "?"
                    val nb = scene.fixtures.getOrNull(mj)?.fixtureId ?: "?"
                    "N° $na → N° $nb : ${formatPlanDistanceMm(mm)}"
                } else if (mi != null) "Touchez le 2e projecteur"
                else "Mesure : touchez un projecteur"
                Surface(
                    color = MEASURE_3D_COLOR, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)
                ) {
                    Text(
                        txt, color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            // Panneau d'info de sélection (bas-centre).
            if (!measureMode && selected.isNotEmpty()) {
                val label = if (selected.size == 1 && selected.first() in scene.fixtures.indices) {
                    val f = scene.fixtures[selected.first()]
                    "N° ${f.fixtureId ?: "?"} · ${f.name}"
                } else "${selected.size} projecteurs sélectionnés"
                Surface(
                    color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)
                ) {
                    Text(
                        label, color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            // LIBÉRATION À LA SORTIE — correctif du GEL confirmé par le journal de
            // Stéphane (Samsung A55, gros show Vega) : en quittant/fermant la vue 3D,
            // SceneView DÉTRUIT les nœuds UN PAR UN (TransformManager.nDestroy natif)
            // récursivement → des milliers de nœuds = > 6 s sur le thread principal
            // → ANR. Ici on DÉTACHE geometryRoot/dxfRoot/satRoot d'un coup
            // (clearChildNodes = léger, aucun destroy natif) AVANT que SceneView n'y
            // descende ; le dispose du moteur (rememberEngine) libère ensuite TOUTES
            // les ressources en bloc. Placé en dernier dans le Box → sa libération
            // s'exécute AVANT celle de la scène (ordre inverse de composition).
            DisposableEffect(Unit) {
                onDispose {
                    lod.reset()
                    runCatching { geometryRoot.clearChildNodes() }
                    runCatching { dxfRoot.clearChildNodes() }
                    runCatching { satRoot.clearChildNodes() }
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

/**
 * Indices (dans scene.fixtures) des projecteurs qui possèdent DÉJÀ leur propre
 * géométrie 3D (une réf .3ds/.glb/.gltf, directe ou via Symbol→Symdef). Ceux-là
 * sont rendus par les passes décor (2a/2b) ; ils NE doivent PAS recevoir en plus
 * la silhouette GDTF, sinon les deux maillages se superposent au même point
 * (« mauvaises géométries / éléments mélangés »). Miroir de la règle iOS
 * `if !hasContent { nodeForGDTF }` (SceneKitContainerView : GDTF = REPLI).
 */
private fun fixturesWithOwnGeometry(scene: MvrScene): Set<Int> {
    fun hasGeom(refs: List<MvrGeometryRef>, depth: Int): Boolean {
        if (depth > 8) return false
        for (g in refs) when (g) {
            is MvrGeometryRef.File ->
                if (g.fileName.endsWith(".3ds", true) || g.fileName.endsWith(".glb", true) ||
                    g.fileName.endsWith(".gltf", true)) return true
            is MvrGeometryRef.Symbol -> {
                val sd = scene.symdefs[g.symdefUuid]
                if (sd != null && hasGeom(sd.items.take(MAX_SYMDEF_ITEMS), depth + 1)) return true
            }
        }
        return false
    }
    val out = HashSet<Int>()
    scene.fixtures.forEachIndexed { i, f -> if (hasGeom(f.geometryRefs, 0)) out.add(i) }
    return out
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

    // Émet les 3 indices d'un triangle SEULEMENT s'ils sont tous valides (okTri).
    fun emit(out: ArrayList<Int>, t: Int) {
        val o = t * 3; out.add(fi[o]); out.add(fi[o + 1]); out.add(fi[o + 2])
    }
    fun allValidTris(): ArrayList<Int> {
        val out = ArrayList<Int>(tc * 3)
        for (t in 0 until tc) if (okTri(t)) emit(out, t)
        return out
    }

    // Sous-meshes par matériau.
    val prims = ArrayList<List<Int>>()
    val colors = ArrayList<Int>()
    if (mesh.materialGroups.isEmpty()) {
        val all = allValidTris()
        if (all.isNotEmpty()) {
            prims.add(all)
            colors.add(if (mesh.materials.size == 1) mesh.materials.values.first().toColorInt() else GRAY)
        }
    } else {
        val assigned = BooleanArray(tc)
        for (g in mesh.materialGroups) {
            val idx = ArrayList<Int>(g.triangles.size * 3)
            for (t in g.triangles) if (t in 0 until tc && okTri(t)) {
                assigned[t] = true
                emit(idx, t)
            }
            if (idx.isNotEmpty()) {
                prims.add(idx)
                colors.add(mesh.materials[g.name]?.toColorInt() ?: GRAY)
            }
        }
        val leftover = ArrayList<Int>()
        for (t in 0 until tc) if (!assigned[t] && okTri(t)) emit(leftover, t)
        if (leftover.isNotEmpty()) { prims.add(leftover); colors.add(GRAY) }
    }
    if (prims.isEmpty()) return null
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

// ---- Zoom caméra : proportionnel à la distance ET au mouvement des doigts ----

/**
 * Le zoom d'origine était inutilisable sur un vrai show, pour deux raisons
 * vérifiées dans le bytecode de SceneView 4.22 / Filament 1.71 :
 *
 * 1. Le manipulateur ORBIT de Filament fait une translation d'un nombre FIXE de
 *    MÈTRES par unité de scroll (`eye += gaze * zoomSpeed * -delta`) — aucun
 *    terme de distance. À 100 m du rig, un pincement plein écran n'avançait que
 *    de quelques mètres ; collé à un projecteur, le même geste traversait tout.
 * 2. SceneView compressait en plus l'écart des doigts par `|Δpx|^0.7`, ce qui
 *    PÉNALISE les gestes amples : plus on pinçait vite, moins ça payait.
 *
 * Ici chaque pixel d'écartement déplace la caméra d'une FRACTION de la distance
 * courante, linéairement en Δpx : la vitesse suit le mouvement, et l'approche
 * est exponentielle (jamais lente au loin, jamais brutale au près).
 *
 * La distance est suivie côté Kotlin : elle n'est pas lisible depuis le
 * manipulateur, car `scroll` translate l'œil ET la cible (|œil − cible| est
 * invariant) tandis que le PIVOT d'orbite, lui, ne bouge pas. Ni l'orbite ni le
 * panoramique ne changent la distance au pivot → notre suivi reste exact.
 */
internal class DistanceZoomManipulator(
    private val fm: com.google.android.filament.utils.Manipulator,
    startDistance: Float,
    private val minDistance: Float,
    private val maxDistance: Float,
    /** px physiques par dp — le geste est mesuré en pixels bruts par SceneView. */
    private val density: Float,
    private val inner: io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator,
) : io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator by inner {

    var distance = clampStart(startDistance, minDistance, maxDistance)
        private set

    /**
     * N1 — Remappage des gestes : 1 DOIGT = déplacement (pan), 2 DOIGTS = orbite.
     * Le détecteur SceneView appelle `grabBegin(strafe=false)` pour 1 doigt (orbite
     * par défaut Filament) et `grabBegin(strafe=true)` pour 2 doigts en translation
     * (pan). On INVERSE le drapeau `strafe` : 1 doigt devient pan, 2 doigts orbite.
     * Le mode reste ORBIT (il honore `strafe`), et le pincement/zoom (scrollUpdate)
     * n'est pas touché → zoom conservé.
     */
    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        inner.grabBegin(x, y, !strafe)
    }

    override fun scrollUpdate(x: Int, y: Int, previousSeparation: Float, currentSeparation: Float) {
        // Écartement des doigts en pixels : positif = on écarte = zoom AVANT.
        val dpx = currentSeparation - previousSeparation
        val wanted = step(distance, dpx, density, minDistance, maxDistance) ?: return
        val forward = distance - wanted            // mètres à avancer (>0 = vers la scène)
        if (forward == 0f) return
        distance = wanted
        // zoomSpeed du manipulateur = 1 → `scroll(delta)` avance de −delta mètres.
        fm.scroll(x, y, -forward)
    }

    /**
     * Cœur du calcul, isolé du natif pour être testable (cf. DistanceZoomTest).
     * Rend la NOUVELLE distance au pivot, ou `null` s'il n'y a rien à faire.
     */
    companion object {
        /** 0,6 % de la distance par dp : un pincement de ~300 dp ≈ ×6. */
        const val RATE_PER_DP = 0.006f

        fun clampStart(start: Float, min: Float, max: Float): Float =
            (if (start.isFinite() && start > 0f) start else 1f).coerceIn(min, max)

        fun step(distance: Float, dpx: Float, density: Float, min: Float, max: Float): Float? {
            if (!dpx.isFinite() || dpx == 0f) return null
            val d = if (density.isFinite() && density > 0f) density else 1f
            // Fraction de la distance courante par dp parcouru — LINÉAIRE, donc
            // un geste deux fois plus ample zoome deux fois plus. Bornée pour
            // qu'un saut d'événement (doigt reposé) ne téléporte pas la caméra.
            val frac = (RATE_PER_DP * dpx / d).coerceIn(-0.6f, 0.6f)
            return (distance * (1f - frac)).coerceIn(min, max)
        }
    }
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

/** Magenta de l'outil de mesure — même code couleur que la vue plan. */
private val MEASURE_3D_COLOR = Color(0xFFD500A0)

/** Cube de sélection : cyan bien visible. */
private val SELECTION_CUBE_COLOR = Color(0xFF00E5FF)
/** Les deux signes ±1 (Float) balayés pour engendrer les 8 coins du cube. */
private val SEL_CUBE_SIGNS = floatArrayOf(-1f, 1f)
/**
 * Les 12 arêtes du cube. Ordre d'itération des coins : sx (extérieur), sy, sz
 * (intérieur) → index de coin = bit2·sx | bit1·sy | bit0·sz. Une arête relie deux
 * coins qui ne diffèrent QUE d'un axe.
 */
private val SEL_CUBE_EDGES = arrayOf(
    0 to 4, 1 to 5, 2 to 6, 3 to 7,   // axe X (bit 2)
    0 to 2, 1 to 3, 4 to 6, 5 to 7,   // axe Y (bit 1)
    0 to 1, 2 to 3, 4 to 5, 6 to 7    // axe Z (bit 0)
)

/**
 * Dessine l'étiquette d'un projecteur dans le Canvas d'overlay, ANCRÉE au-dessus
 * du point écran `anchor` (la projection du projecteur). Style proche de la 3D
 * iOS : petit cadre à fond sombre semi-opaque, texte blanc lisible sur tout fond,
 * 1re ligne (le N°) en gras et légèrement plus grande. Un fin trait relie le cadre
 * au projecteur. `sizeScale` = options.labelSize (0.7 / 1.0 / 1.4).
 */
private fun DrawScope.drawFixtureLabel(
    measurer: TextMeasurer,
    lines: List<String>,
    anchor: Offset,
    sizeScale: Float
) {
    if (lines.isEmpty()) return
    val fontPx = 12f * sizeScale
    val ann = buildAnnotatedString {
        lines.forEachIndexed { idx, line ->
            if (idx > 0) append("\n")
            if (idx == 0) withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (fontPx * 1.1f).sp)) {
                append(line)
            } else append(line)
        }
    }
    val layout = measurer.measure(ann, style = TextStyle(color = Color.White, fontSize = fontPx.sp))
    val w = layout.size.width.toFloat(); val h = layout.size.height.toFloat()
    val padX = 6f * sizeScale; val padY = 3f * sizeScale
    val boxW = w + padX * 2f; val boxH = h + padY * 2f
    val gap = 12f * sizeScale
    val left = anchor.x - boxW / 2f
    val top = anchor.y - gap - boxH
    val radius = CornerRadius(5f * sizeScale, 5f * sizeScale)
    // Trait de rappel du cadre vers le projecteur.
    drawLine(Color(0x99FFFFFF), Offset(anchor.x, top + boxH), anchor, strokeWidth = 1.5f)
    drawRoundRect(Color(0xCC000000), topLeft = Offset(left, top), size = Size(boxW, boxH), cornerRadius = radius)
    drawRoundRect(Color(0x66FFFFFF), topLeft = Offset(left, top), size = Size(boxW, boxH),
        cornerRadius = radius, style = Stroke(width = 1f))
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}
