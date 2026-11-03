package com.minou.mvrviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInRoot
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
import com.minou.mvrviewer.mvr.MvrSceneObject
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
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

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
@androidx.compose.runtime.Composable
private fun bg3dPresets(): List<Pair<String, Long>> = listOf(
    stringResource(R.string.color_black) to 0xFF000000L, stringResource(R.string.color_charcoal) to 0xFF1C1C1EL, stringResource(R.string.color_midnight_blue) to 0xFF0B1026L,
    stringResource(R.string.color_grey) to 0xFF8E8E93L, stringResource(R.string.color_white) to 0xFFFFFFFFL
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
    // Éléments suivis, AVEC leur calque : la source de vérité, d'où sont dérivées
    // les listes plates ci-dessous à chaque changement de réglage.
    val entries = ArrayList<LodItem<io.github.sceneview.node.Node>>()
    val proxyEntries = ArrayList<LodItem<io.github.sceneview.node.Node>>()
    // Réglage par calque en vigueur (vide = tout en « Auto »).
    var modes: Map<String, LayerLodMode> = emptyMap()
    // LISTES PLATES consommées par onFrame : aucun accès map ni calcul par frame,
    // le coût reste exactement celui d'avant (deux boucles de visibilité).
    var hideOnMove = ArrayList<io.github.sceneview.node.Node>()
    var showOnMove = ArrayList<io.github.sceneview.node.Node>()
    var lastX = Float.NaN; var lastY = Float.NaN; var lastZ = Float.NaN
    var idle = 0
    var hidden = false
    // Solo actif : le LOD (onFrame) laisse alors la visibilité au filtre solo au
    // lieu de réafficher le détail — sinon il rallumerait le décor masqué.
    var soloActive = false

    fun reset() {
        entries.clear(); proxyEntries.clear()
        hideOnMove = ArrayList(); showOnMove = ArrayList()
        lastX = Float.NaN; idle = 0; hidden = false; soloActive = false
    }

    /**
     * Enregistre un nœud de DÉTAIL (masquable en navigation) avec son calque.
     * L'ajout aux listes plates est fait ici en O(1) — la scène se construit par
     * lots pendant plusieurs secondes, et le LOD doit rester opérant pendant ce
     * temps (l'utilisateur peut déjà naviguer).
     */
    fun addEntry(node: io.github.sceneview.node.Node, layer: String, autoCandidate: Boolean) {
        entries.add(LodItem(node, layer, autoCandidate))
        if (lodHidesOnMove(modes.lodMode(layer), autoCandidate)) hideOnMove.add(node)
    }

    /** Enregistre un cube de repli (affiché à la place du détail) avec son calque. */
    fun addProxy(node: io.github.sceneview.node.Node, layer: String) {
        proxyEntries.add(LodItem(node, layer, true))
        if (lodShowsProxy(modes.lodMode(layer))) showOnMove.add(node)
    }

    /**
     * Applique un nouveau réglage par calque : recalcule les deux listes plates.
     * Si le LOD est en train de masquer (geste en cours), la visibilité est
     * RE-SYNCHRONISÉE immédiatement — on rétablit d'abord l'ancien état, puis on
     * applique le nouveau, sans jamais toucher à quoi que ce soit en mode solo.
     */
    fun applyModes(newModes: Map<String, LayerLodMode>) {
        modes = newModes
        val oldHide = hideOnMove; val oldShow = showOnMove
        val b = computeLodBuckets(entries, proxyEntries, newModes)
        hideOnMove = ArrayList(b.hideOnMove); showOnMove = ArrayList(b.showOnMove)
        if (hidden && !soloActive) {
            runCatching {
                oldHide.forEach { it.isVisible = true }    // repartir de l'état de repos…
                oldShow.forEach { it.isVisible = false }
                hideOnMove.forEach { it.isVisible = false } // …puis réappliquer le geste
                showOnMove.forEach { it.isVisible = true }
            }
        }
    }
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
        lod.proxyEntries.forEach { it.node.isVisible = false }
        lod.hidden = false; lod.idle = 0; lod.soloActive = false
    } else {
        // Solo : masque TOUT geometryRoot (décor + silhouettes GDTF + proxies) ;
        // seuls les cubes des projecteurs soloés (déclaratifs) restent visibles.
        runCatching { geometryRoot.childNodes.forEach { it.isVisible = false } }
        lod.soloActive = true
    }
}

/**
 * Ressources GPU d'un quad texturé 3D (fond satellite, plan de repère image) à
 * libérer à chaque reconstruction — `ModelNode.destroy()` ne libère NI la texture
 * NI l'instance de matériau : sans ça, chaque rebuild fuit plusieurs Mo de VRAM.
 */
private class SatGpu {
    var texture: com.google.android.filament.Texture? = null
    var material: MaterialInstance? = null
}

/**
 * Ressources GPU des TEXTURES `.3ds` de la build courante (une Texture + une
 * MaterialInstance PAR NOM D'IMAGE, partagées par toutes les instances qui la
 * référencent — les 215 arbres d'un export Vectorworks = 1 texture). Libérées à
 * la reconstruction, au même endroit que les FilamentAsset .glb : sinon chaque
 * « appliquer un modèle GDTF » fuirait ~10 Mo de VRAM par image (bug déjà connu
 * du projet pour les .glb). Les Bitmap décodés, eux, restent dans le cache de
 * prep CPU (réutilisés au retour plan→3D).
 */
private class TdsGpu {
    val textures = ArrayList<com.google.android.filament.Texture>()
    val materials = ArrayList<MaterialInstance>()
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
    /// Objet 3D à importer DÈS l'ouverture : cas « fichier 3D choisi à
    /// l'accueil » (la scène est alors vide). Consommé une seule fois.
    initialModelUri: android.net.Uri? = null,
    onInitialModelConsumed: () -> Unit = {},
    /**
     * Ouvre la feuille « Calques » (SPEC-menus-android §3.10) : posée par
     * SceneScreen, partagée avec le plan (sans le réglage LOD depuis le plan).
     * Outil ancrable `layers` ; le menu ⋯ passe par [onMainMenu].
     */
    onShowLayers: () -> Unit = {},
    // ---- Rangement des menus, tranche C (SPEC-menus-android §3.1–3.5) ----
    /**
     * Entrées du menu ⋯ que la 3D ne traite pas elle-même (destinations, import
     * du plan, PDF, fermeture…) : transmises à SceneScreen.
     */
    onMainMenu: (MainMenuItemId) -> Unit = {},
    /** Synchro branchée (section « Équipe » du menu). */
    hasSync: Boolean = false,
    /** Un câblage existe (section « PDF de câblage » du menu). */
    hasCabling: Boolean = false,
    /** Nombre de classes masquées (bloc d'état). */
    hiddenClassCount: Int = 0,
    /** Pastille de synchro (projet partagé), posée dans le bloc d'état ; null = aucune. */
    syncBadge: (@Composable () -> Unit)? = null,
    /**
     * Un écran plein opaque recouvre la 3D (destination en surimpression) : la
     * boucle de rendu SceneView est mise en attente (cycle de vie plafonné à
     * STARTED), puis reprend telle quelle à la fermeture.
     */
    renderPaused: Boolean = false,
    options: SceneOptions,
    gdtfOverrides: GdtfOverrides,
    // Overrides de patch — NOM d'AFFICHAGE effectif (renommage) dans les étiquettes
    // 3D, la recherche et la fiche de sélection. Défaut vide = noms bruts du .mvr.
    overrides: PatchOverrides = remember { PatchOverrides() },
    // Projecteurs custom V1 : la GÉOMÉTRIE d'un projecteur assigné à « custom:<id> »
    // vient de `baseGdtfSpec` du type (résolu ici). Défaut vide = aucun custom.
    customResolver: CustomFixtureResolver = remember { CustomFixtureResolver() },
    referencePlan: com.minou.mvrviewer.mvr.ReferencePlan? = null,
    /**
     * VERSION du placement du plan de repère. La transformée est un objet muté EN
     * PLACE (l'instance du plan ne change pas) : sans ce compteur, un réglage
     * d'homothétie ou de décalage ne se verrait qu'en 2D. C'est donc LUI, et pas
     * l'instance, qui commande la reconstruction du plan de repère en 3D — quad
     * matriciel ET traits DXF (R7).
     */
    refPlanTransformVersion: Int = 0,
    // ---- Modèles 3D importés (chantier #3) : DÉCOR, jamais des projecteurs ----
    // Ils vivent dans leur PROPRE racine (importedRoot), peuplée par son propre
    // effet : le rebuild MVR/GDTF ne les touche pas, et ils ne consomment pas le
    // budget de nœuds/triangles du show.
    importedModels: List<com.minou.mvrviewer.mvr.ImportedModel> = emptyList(),
    /**
     * VERSION du placement des modèles importés. MÊME contrainte que
     * [refPlanTransformVersion] : `SceneModelTransform` est muté EN PLACE (la
     * liste, elle, ne change pas), donc sans ce compteur un réglage ne se verrait
     * JAMAIS en 3D.
     */
    importedModelsVersion: Int = 0,
    /** Remplace la liste (ajout / retrait) : persiste les octets + le manifeste. */
    onSetImportedModels: (List<com.minou.mvrviewer.mvr.ImportedModel>) -> Unit = {},
    /** Un placement a été modifié : bump de version + sauvegarde différée. */
    onImportedModelsTouched: () -> Unit = {},
    hiddenLayers: Set<String> = emptySet(),
    calibration: com.minou.mvrviewer.mvr.GeoCalibration? = null,
    satellite: com.minou.mvrviewer.mvr.SatelliteOverlay? = null,
    /** Statut du fond satellite (ligne d'état du menu). */
    satelliteStatus: SatelliteStatus = SatelliteStatus.Idle,
    /** 1re ancre dans les emprises IGN (France) : sinon, réglage désactivé. */
    satelliteCovered: Boolean = true,
    // ---- Rangement des menus, tranche B (SPEC-menus-android §3.6–3.8) ----
    /** Panneaux flottants ouverts (état hissé dans SceneScreen, commun aux deux vues). */
    panels: ScenePanels = ScenePanels(),
    onPanels: (ScenePanels) -> Unit = {},
    /** Import d'un plan (DXF, image, PDF) — Pro, garde dans SceneScreen. */
    onImportRefPlan: () -> Unit = {},
    importingRefPlan: Boolean = false,
    /** Le panneau « Plan importé » a muté la transformée : version commune +1. */
    onRefPlanTouched: () -> Unit = {},
    onToggleRefPlanLayer: (String) -> Unit = {},
    onRemoveRefPlan: () -> Unit = {},
    /** Calibrations depuis la 3D : ouvrent le plan prêt à poser (Pro vérifié avant). */
    onCalibrateThreePoint: () -> Unit = {},
    onCalibrateHere: () -> Unit = {},
    onResetCalibration: () -> Unit = {},
    // Câblage (phase 4/5) : sert UNIQUEMENT au texte des étiquettes 3D pour les
    // champs Socapex / Ligne DMX (mêmes tables que la vue plan). Hors ces deux
    // champs, la 3D n'utilise pas le câblage.
    cabling: PowerCablingState = remember { PowerCablingState() },
    dmxCabling: DmxCablingState = remember { DmxCablingState() },
    // Outils ancrables de navigation (le menu ⋯ passe par [onMainMenu]).
    onShowPlan: () -> Unit,
    onShowPatch: () -> Unit,
    /** Outil `gdtfMapping` : « Modèles de projecteurs ». */
    onShowFixtureModels: () -> Unit,
    /** Outil `gdtfShare` (sens iOS) : connexion GDTF Share, section Services de Compte & réglages. */
    onShowGdtfShareAccount: () -> Unit = {},
    onShowUniverse: () -> Unit = {},
    onShowCabling: () -> Unit = {},
    onShowAccount: (() -> Unit)? = null,
    onShareProject: (() -> Unit)? = null,
    onShowHistory: (() -> Unit)? = null,
    // Solo (parité iOS / vue plan) : ensemble d'INSTANCES à isoler (mvrInstanceKey).
    // Vide = aucun filtre (on montre tout). Hissé dans SceneScreen et PARTAGÉ avec
    // la vue plan → le solo survit aux bascules 3D ↔ plan.
    soloElements: Set<String> = emptySet(),
    onSetSoloElements: (Set<String>) -> Unit = {},
    // N12 — APPUI LONG sur un projecteur → ouvre la fiche d'édition de la patch
    // (mêmes champs que la liste de patch). Le TAP simple garde son comportement
    // (sélection). Ne se déclenche qu'en interaction normale (pas rectangle/mesure).
    onEditFixture: (MvrSceneObject) -> Unit = {},
    // N11 — disposition des barres d'outils ancrables (vue 3D). Nommé `toolbarLayout`
    // pour ne pas masquer le `val layout` (FixtureLayout) local plus bas. Défaut =
    // barre bas-gauche actuelle → aucun changement pour qui ne personnalise pas.
    toolbarLayout: ToolbarLayout = ToolbarLayout.EMPTY,
    onLayoutChange: (ToolbarLayout) -> Unit = {},
    /** « Réinitialiser » du panneau de personnalisation : défaut de l'appareil. */
    onLayoutReset: () -> Unit = {},
    // LOD d'interaction PAR CALQUE (#1) : « Auto » (absent de la map) = comportement
    // historique. N'affecte QUE la navigation, jamais la visibilité au repos.
    layerLod: Map<String, LayerLodMode> = emptyMap(),
    onSetLayerLod: (Map<String, LayerLodMode>) -> Unit = {},
    // Avancement NOMMÉ du chargement (#4) : la construction 3D est la SUITE de la
    // lecture du .mvr commencée à l'accueil (même pourcentage global 0→100 %).
    // null = pas de progression pilotée (aperçus, tests).
    progress: LoadProgress? = null,
    // Bibliothèque MA (SPEC-ma.md §4.4-4.6) : cache 3D des types « profil MA + 3D
    // choisie » (objets 3D statiques). null = aucun type MA (comportement historique).
    fixture3DStore: Fixture3DStore? = null,
    // Sélection courante (clés d'INSTANCE) remontée à la scène : « Enregistrer et
    // affecter à la sélection » de la bibliothèque MA s'en sert.
    onSelectionChanged: (List<String>) -> Unit = {},
    // FICHE DE SÉLECTION et BANNIÈRE MULTI (SPEC-menus-android §3.9, tranche D) :
    // construites par SceneScreen, les MÊMES dans le plan. null = rien en bas.
    selectionCard: SelectionCardHost? = null,
    // Crochet DEBUG `MVR_AUTO_SELECT=<N°>` : pris une fois, à la scène prête.
    takeAutoSelect: () -> String? = { null },
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val engine = rememberEngine()
    // Cycle de vie du RENDU : celui de l'activité, plafonné sous un écran plein.
    val renderLifecycle = rememberRenderLifecycle(renderPaused)
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
    // Textures + matériaux texturés des .3ds de la build courante (cf. TdsGpu) :
    // libérés à chaque reconstruction, juste après les FilamentAsset .glb.
    val tdsGpu = remember { TdsGpu() }
    // Plan de repère MATRICIEL (image / PDF) posé au sol : même patron que le
    // quad satellite (quad texturé + ressources GPU libérées à chaque rebuild),
    // mais placé par la transformée du plan et non par la géo-référence.
    val rasterRoot = rememberNode(engine)
    val rasterGpu = remember { SatGpu() }
    // Décor 3D IMPORTÉ par l'utilisateur (OBJ / STL / PLY / glTF) : racine DÉDIÉE,
    // à côté de rasterRoot/dxfRoot. Séparée de geometryRoot pour trois raisons de
    // NON-RÉGRESSION : (1) le rebuild MVR/GDTF fait clearChildNodes()+destroy() sur
    // TOUT geometryRoot et détruirait les modèles importés ; (2) le budget de nœuds
    // du show reste intact ; (3) le LOD d'interaction ne les voit pas.
    val importedRoot = rememberNode(engine)
    // Cache de matériaux couleur du décor importé, RÉUTILISÉ d'une reconstruction à
    // l'autre (patron dxfMatByColor) : le MaterialLoader ne libère qu'au dispose,
    // le recréer à chaque réglage fuirait une MaterialInstance par couleur.
    val importedMatByColor = remember(materialLoader) { HashMap<Int, MaterialInstance>() }
    // FilamentAsset des modèles glTF importés : ModelNode.destroy() NE libère PAS
    // l'asset — même protocole que `glbAssets` (destroyModel puis destroyAsset).
    val importedAssets = remember { ArrayList<FilamentAsset>() }
    // Marqueur « ma position » (GPS) en 3D : nœud dédié, mis à jour SANS
    // reconstruire la scène (même principe non destructif que le quad satellite /
    // le plan DXF). Bleu, non éclairé → toujours visible.
    val markerRoot = rememberNode(engine)
    val markerMaterial = remember(materialLoader) { materialLoader.createUnlitColorInstance(0xFF2979FF.toInt()) }
    var showLocation by remember { mutableStateOf(false) }
    // Flux complet (position + refus) : la ligne d'état du panneau Position GPS dit
    // « Localisation refusée » comme dans le plan.
    val locFeed3D = rememberUserLocationFeed(showLocation)
    val gps by locFeed3D.location
    // Chaînes de statut localisées, résolues ICI (contexte composable) puis
    // utilisées dans des effets/coroutines qui, eux, ne peuvent pas appeler stringResource.
    val sModelTooManyFmt = stringResource(R.string.model_too_many_fmt)
    // BLOC D'ÉTAT (SPEC-menus-android §3.5) : objets posés (null = rien encore),
    // plafond atteint (« (tronqué) »), phase des silhouettes GDTF en cours.
    var placedObjects by remember(scene) { mutableStateOf<Int?>(null) }
    var truncatedObjects by remember(scene) { mutableStateOf(false) }
    var gdtfLoading by remember(scene) { mutableStateOf(false) }
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

    // Reconstruit quand un modèle GDTF Share est appliqué (version bump) OU quand un
    // type custom change (customResolver.version) — la géométrie d'un projecteur
    // custom vient de baseGdtfSpec, qui peut changer.
    // Passe « plan de repère en 3D » terminée pour le plan COURANT (rien à faire
    // s'il n'y a pas de plan). Sert UNIQUEMENT à savoir quand le chargement est
    // réellement fini : la construction 3D et la construction du plan sont deux
    // effets CONCURRENTS, le 100 % n'est atteint que lorsque les deux ont fini.
    var planPassDone by remember(referencePlan) { mutableStateOf(referencePlan == null) }

    LaunchedEffect(scene, mvrBytes, gdtfOverrides.version, customResolver.version) {
        // Reconstruction APRÈS un chargement déjà terminé (modèle GDTF appliqué,
        // type custom modifié) : on repart d'une progression neuve plutôt que de
        // rester bloqué à 100 %.
        if (progress != null && !progress.active) progress.restart()
        // Repart d'une scène vide (appliquer un modèle GDTF Share re-déclenche cet
        // effet). ⚠️ On DÉTRUIT les anciens nœuds (removeChildNode NE libère PAS
        // les ressources Filament : VertexBuffer/IndexBuffer/renderable). Sans ça,
        // chaque reconstruction FUYAIT toute la géométrie GPU → saturation mémoire
        // → CRASH après quelques changements de modèle. `destroy()` (via
        // safeDestroyGeometry) est idempotent → géométrie partagée OK.
        // On vide le LOD AVANT de détruire (sinon onFrame toucherait un nœud
        // détruit pendant la reconstruction → crash).
        lod.reset()
        // Réglage LOD par calque en vigueur AVANT tout peuplement : les nœuds
        // ajoutés pendant la construction (plusieurs secondes) sont classés du
        // premier coup, sans attendre la ré-application de fin de build.
        lod.modes = layerLod
        gdtfFixtures = emptySet()
        gdtfLoading = false
        truncatedObjects = false
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
        // Puis les textures/matériaux texturés des .3ds de la build précédente
        // (même protocole que satGpu : destroyMaterialInstance PUIS destroyTexture,
        // idempotent via runCatching — les nœuds qui les référençaient sont déjà
        // détruits ci-dessus). Via MaterialLoader, PAS engine : createInstance
        // enregistre l'instance dans la liste privée du loader, que seul
        // MaterialLoader.destroyMaterialInstance retire (sinon elle y reste,
        // détruite, à chaque reconstruction).
        tdsGpu.materials.forEach { m -> runCatching { materialLoader.destroyMaterialInstance(m) } }
        tdsGpu.materials.clear()
        tdsGpu.textures.forEach { t -> runCatching { engine.destroyTexture(t) } }
        tdsGpu.textures.clear()
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
        progress?.report(LoadStep.GEOMETRY, 0f)
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
                // Textures des .3ds (0xA300) : noms référencés par les sous-meshes
                // → octets dans le zip (résolution tolérante GlbImageIndex) →
                // Bitmap + drapeau « a de la transparence », HORS thread principal.
                // Mis en cache avec le reste de la prep (retour plan→3D).
                val tdsTexNames = HashSet<String>()
                for (list in md.values) for (m in list) for (t in m.textures) if (t != null) tdsTexNames.add(t)
                val tdsTex = decodeTdsTextures(tdsTexNames, images)
                Prepared3DHolder().also {
                    it.refs = r; it.meshDataByFile = md; it.maxDimByFile = dim; it.imageBytes = images
                    it.tdsTextures = tdsTex
                }
            }
            holder.refs = prep.refs; holder.meshDataByFile = prep.meshDataByFile
            holder.maxDimByFile = prep.maxDimByFile; holder.imageBytes = prep.imageBytes
            holder.tdsTextures = prep.tdsTextures
        }
        progress?.report(LoadStep.GEOMETRY, 1f)
        val refs = holder.refs!!
        val meshDataByFile = holder.meshDataByFile!!
        val maxDimByFile = holder.maxDimByFile!!
        val imageBytes = holder.imageBytes ?: emptyMap()
        val tdsTextures = holder.tdsTextures ?: emptyMap()
        // Dénominateur d'avancement de l'étape « Construction de la scène… » :
        // nombre total de références rendables (3ds + glb) à placer.
        val totalRefs = (refs.tds.size + refs.glb.size).coerceAtLeast(1)

        // 2) Thread moteur : budget global de nœuds/triangles partagé 3ds+glb.
        val builtCache = HashMap<String, List<BuiltMesh>>()
        fun material(color: Int) = buildMaterialCache.getOrPut(color) { materialLoader.createColorInstance(color) }
        // Matériau TEXTURÉ d'un sous-mesh .3ds, mémoïsé PAR NOM D'IMAGE (un échec
        // est mémoïsé aussi → jamais deux tentatives GPU pour la même image) :
        // Texture GPU (sRGB + mipmaps, ImageTexture) + matériau ÉCLAIRÉ SceneView
        // opaque_textured / transparent_textured (paramètre « texture »). Avec de
        // la transparence (feuillage découpé) : mélange alpha + DES DEUX CÔTÉS
        // (un plan de feuillage vu de dos serait sinon éliminé par le culling,
        // parité forceDoubleSidedTransparency des .glb). Échelle/décalage UV
        // (0xA354…) : appliqués aux UV des sommets dans prepareMeshData (ces
        // matériaux n'exposent pas de transformée UV). Sampler REPEAT (wrap) +
        // filtrage trilinéaire (la texture a ses mipmaps). Mémoïsé par IMAGE
        // DÉCODÉE (identité) : deux orthographes du même fichier dans les .3ds
        // pointent le même DecodedTexture → une seule texture GPU.
        val texturedByImage = java.util.IdentityHashMap<DecodedTexture, MaterialInstance?>()
        fun texturedMaterial(name: String): MaterialInstance? {
            val dt = tdsTextures[name] ?: return null
            if (texturedByImage.containsKey(dt)) return texturedByImage[dt]
            val built: MaterialInstance? = run {
                val tex = runCatching {
                    io.github.sceneview.texture.ImageTexture.Builder().bitmap(dt.bitmap).build(engine)
                }.getOrNull() ?: return@run null
                tdsGpu.textures.add(tex)
                val mat = runCatching {
                    materialLoader.createTextureInstance(tex, isOpaque = !dt.hasTransparency)
                }.getOrNull() ?: return@run null
                tdsGpu.materials.add(mat)
                val sampler = com.google.android.filament.TextureSampler(
                    com.google.android.filament.TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                    com.google.android.filament.TextureSampler.MagFilter.LINEAR,
                    com.google.android.filament.TextureSampler.WrapMode.REPEAT
                )
                runCatching { mat.setParameter("texture", tex, sampler) }
                if (dt.hasTransparency) {
                    runCatching { mat.setDoubleSided(true) }
                    runCatching { mat.setCullingMode(com.google.android.filament.Material.CullingMode.NONE) }
                }
                mat
            }
            texturedByImage[dt] = built
            return built
        }
        var nodes = 0
        var triangles = 0L
        var placed = 0
        var truncated = false
        val sceneNodeBudget = MAX_NODES - FIXTURE_NODE_RESERVE

        // 0) SOURCE 3D de chaque projecteur (SPEC-ma.md §4.6). Un type MA dont la 3D
        //    choisie est DISPONIBLE ici (GDTF inscrit sous sa clé de rendu `type3d:…`,
        //    ou objet 3D en cache) prime, y compris sur la géométrie PROPRE du projecteur
        //    (choix explicite de l'utilisateur) : ses réfs .3ds/.glb sont alors écartées.
        //    « Disponible » = DÉCODABLE (§4.6) : les objets 3D (d) sont donc décodés /
        //    instanciés ICI, une fois par groupe (fichier, unité, axe, format), AVANT
        //    d'écarter quoi que ce soit ; un objet que cet appareil ne sait pas lire
        //    (PLY big-endian, FBX refusé, glTF non instanciable…) est retenu en échec
        //    dans le store (statut « 3D indisponible » + raison) et ses projecteurs
        //    gardent leur géométrie propre / base / GDTF d'origine.
        val fixtures = scene.fixtures
        class PreparedModel(
            val glb: ArrayDeque<com.google.android.filament.gltfio.FilamentInstance>?,
            val built: List<Pair<Geometry, Int>>
        )
        val preparedModels = HashMap<String, PreparedModel>()
        val fixtureSources = fixture3DSourcesChecked(
            fixtures, overrides, customResolver, fixture3DStore, gdtfOverrides
        ) { key, m, count ->
            val bytes = withContext(Dispatchers.IO) { fixture3DStore?.readBlob(m.sha256) }
                ?: return@fixture3DSourcesChecked Fixture3DReason.MISSING_IN_PROJECT
            if (m.format == "glb" || m.format == "gltf") {
                val instances = runCatching {
                    modelLoader.createInstancedModel(java.nio.ByteBuffer.wrap(bytes), minOf(count, MAX_NODES))
                }.getOrNull()
                if (instances.isNullOrEmpty()) return@fixture3DSourcesChecked Fixture3DReason.FORMAT_UNSUPPORTED
                instances.firstOrNull()?.asset?.let { glbAssets.add(it) }
                forceDoubleSidedTransparency(instances)
                preparedModels[key] = PreparedModel(ArrayDeque(instances), emptyList())
            } else {
                val res = withContext(Dispatchers.Default) { com.minou.mvrviewer.mvr.Fixture3DModel.decode(bytes, m.format) }
                val d = (res as? com.minou.mvrviewer.mvr.Fixture3DModel.DecodeResult.Ok)?.model
                    ?: return@fixture3DSourcesChecked Fixture3DStore.reasonForDecodeFailure(res)
                val prepared = withContext(Dispatchers.Default) { d.meshes.map { meshVertices(it) to it.color } }
                val built = prepared.mapNotNull { (pv, color) ->
                    runCatching {
                        Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
                            .vertices(pv.first).primitivesIndices(listOf(pv.second)).build(engine)
                    }.getOrNull()?.let { it to color }
                }
                if (built.isEmpty()) return@fixture3DSourcesChecked Fixture3DReason.FORMAT_UNSUPPORTED
                preparedModels[key] = PreparedModel(null, built)
            }
            null
        }
        val model3DOwners = HashSet<Int>()
        fixtureSources.forEachIndexed { i, src ->
            if (src is Fixture3DSource.Model ||
                (src is Fixture3DSource.Gdtf && src.spec.startsWith(Fixture3DStore.RENDER_KEY_PREFIX))) model3DOwners.add(i)
        }

        // 2a) .3ds : géométries uniques partagées + un GeometryNode par instance.
        for (r in refs.tds) {
            if (nodes >= sceneNodeBudget || triangles >= MAX_TRIANGLES) { truncated = true; break }
            if (r.owner >= 0 && r.owner in model3DOwners) continue
            val md = meshDataByFile[r.fileName]
            if (md.isNullOrEmpty()) continue
            val built = builtCache.getOrPut(r.fileName) { md.map { BuiltMesh(it.toGeometry(engine), it.colors, it.triangles, it.textures) } }
            // Petit objet (siège, accessoire) → candidat au LOD d'interaction.
            val small = (maxDimByFile[r.fileName] ?: Float.MAX_VALUE) < LOD_SMALL_MM
            for (bm in built) {
                // Sous-mesh texturé → matériau image partagé ; sinon (pas de
                // texture, image absente du MVR ou illisible) → couleur diffuse.
                val mats = bm.colors.indices.map { i ->
                    bm.textures.getOrNull(i)?.let { texturedMaterial(it) } ?: material(bm.colors[i])
                }
                val node = GeometryNode(engine, bm.geometry, mats)
                node.transform = r.world
                enqueueAdd(node)
                // Suivi par le LOD AVEC son calque : en « Auto » seul le petit
                // décor est masqué (comportement historique) ; « Masquer en
                // navigation » peut désormais viser aussi le gros décor.
                lod.addEntry(node, r.layer, autoCandidate = small)
                nodes++; triangles += bm.triangles
            }
            placed++
            if (placed % 40 == 0) {
                placedObjects = placed
                // MÊME cadence que le statut (tous les 40 objets, jamais par
                // objet) : l'écriture d'état est en plus filtrée par le % entier.
                progress?.report(LoadStep.BUILD, placed.toFloat() / totalRefs)
            }
        }

        // 2b) .glb : createInstancedModel (N instances d'un même fichier = 1 seul
        //    parse), extraction du zip PAR LOTS de fichiers uniques.
        if (refs.glb.isNotEmpty() && nodes < sceneNodeBudget) {
            val byFile = LinkedHashMap<String, MutableList<Mat4>>()
            // Calque du fichier (premier objet qui le référence) : un .glb partagé
            // entre calques suit celui de sa première occurrence — suffisant pour
            // un réglage d'affichage, et O(1).
            val layerByFile = HashMap<String, String>()
            for (r in refs.glb) {
                if (r.owner >= 0 && r.owner in model3DOwners) continue
                byFile.getOrPut(r.fileName) { mutableListOf() }.add(r.world)
                layerByFile.getOrPut(r.fileName) { r.layer }
            }
            // Images EXTERNES des .glb (Filament lit lui-même les embarquées) :
            // résolution tolérante (%20, sous-dossier, casse), cf. GlbImageIndex.
            val imageIndex = com.minou.mvrviewer.mvr.GlbImageIndex(imageBytes)
            val resolver: (String) -> java.nio.Buffer? = { uri ->
                imageIndex.resolve(uri)?.let { java.nio.ByteBuffer.wrap(it) }
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
                        forceDoubleSidedTransparency(instances)
                        val glbLayer = layerByFile[name] ?: ""
                        for (k in 0 until minOf(take, instances.size)) {
                            val node = io.github.sceneview.node.ModelNode(instances[k])
                            node.transform = transforms[k]
                            enqueueAdd(node)
                            // autoCandidate = false : le gros décor .glb n'est
                            // JAMAIS masqué en « Auto » (invariant de
                            // non-régression) — seulement sur demande explicite.
                            lod.addEntry(node, glbLayer, autoCandidate = false)
                        }
                        nodes += take
                        placed += take
                        placedObjects = placed
                        // Une fois par FICHIER .glb (cadence existante du statut).
                        progress?.report(LoadStep.BUILD, placed.toFloat() / totalRefs)
                    }
                }
            }
        }

        // 2c) Projecteurs : vraie silhouette GDTF (Base/Yoke/Head assemblés) à
        //    la place du cube. Un spec = UN assemblage préparé, instancié pour
        //    chaque projecteur qui l'utilise (géométries 3ds partagées, glb via
        //    createInstancedModel).
        val bySpec = LinkedHashMap<String, MutableList<Int>>()
        // Objets 3D statiques (d) des types MA, groupés par (fichier, unité, axe).
        val byModel = LinkedHashMap<String, Pair<com.minou.mvrviewer.sync.CustomFixtureModel3D.ModelBlob, MutableList<Int>>>()
        fixtureSources.forEachIndexed { fi2, src ->
            when (src) {
                // Projecteur custom → géométrie de baseGdtfSpec (résolveur), ou GDTF de
                // la 3D choisie (clé `type3d:…`) ; sinon la spec brute. Tout le
                // pipeline d'assemblage reste INCHANGÉ.
                // GDTF = REPLI : on ignore les projecteurs qui ont déjà leur géométrie
                // propre (sinon silhouette GDTF superposée au modèle embarqué) — SAUF
                // quand la 3D vient d'un type MA (elle remplace la géométrie propre).
                is Fixture3DSource.Gdtf ->
                    if (fi2 !in ownGeomFixtures || fi2 in model3DOwners) bySpec.getOrPut(src.spec) { mutableListOf() }.add(fi2)
                is Fixture3DSource.Model -> {
                    val k = fixtureModelGroupKey(src.model)
                    byModel.getOrPut(k) { src.model to mutableListOf() }.second.add(fi2)
                }
                Fixture3DSource.None -> {}
            }
        }
        val gdtfDone = HashSet<Int>()
        // Pastille « Modèles GDTF en cours… » du bloc d'état, le temps de la phase.
        gdtfLoading = bySpec.isNotEmpty() || byModel.isNotEmpty()
        // Dénominateur d'avancement de l'étape « Chargement des GDTF… » : nombre de
        // projecteurs qui attendent une silhouette (tous specs confondus).
        val gdtfTotal = (bySpec.values.sumOf { it.size } + byModel.values.sumOf { it.second.size }).coerceAtLeast(1)
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
                        forceDoubleSidedTransparency(instances)
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
                    val fixLayer = fixtures[fi2].layerName
                    var any = false
                    for (p in asm.placements) {
                        val b = builds[p.modelName] ?: continue
                        val world = worldFix * p.transform * b.adjust
                        if (b.built.isNotEmpty()) {
                            for (bm in b.built) {
                                val node = GeometryNode(engine, bm.geometry, bm.colors.map(::material))
                                node.transform = world
                                enqueueAdd(node)
                                // Détail masqué en navigation (« Auto ») ; le calque
                                // du projecteur pilote désormais la décision.
                                lod.addEntry(node, fixLayer, autoCandidate = true)
                                nodes++
                            }
                            any = true
                        } else {
                            val inst = b.glbQueue.removeFirstOrNull() ?: continue
                            val node = io.github.sceneview.node.ModelNode(inst)
                            node.transform = world
                            enqueueAdd(node)
                            lod.addEntry(node, fixLayer, autoCandidate = true)
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
                            lod.addProxy(proxy, fixLayer)
                        }
                    }
                    if (gdtfDone.size % 40 == 0) {
                        placedObjects = placed
                        progress?.report(LoadStep.GDTF, gdtfDone.size.toFloat() / gdtfTotal)
                    }
                    slice()
                }
                // Fin d'un spec (un TYPE de projecteur) : avancement fiable même
                // si un spec ne produit aucune silhouette (repli sans géométrie).
                progress?.report(LoadStep.GDTF, gdtfDone.size.toFloat() / gdtfTotal)
                break@sourceLoop  // spec rendu — pas besoin du repli
            }
        }

        // 2d) Objets 3D STATIQUES des types MA (SPEC-ma.md §4.4.2) : posés au point
        //     d'accroche du projecteur par `[yUp ? Rx(+90°) : I] · S(unitScaleToMm)`
        //     (valeurs du DTO, jamais recalculées), sans articulation ni faisceau.
        //     Décodés / instanciés UNE fois par groupe à l'étape 0 (seuls les objets
        //     RÉELLEMENT prêts arrivent ici), géométrie PARTAGÉE entre projecteurs. Le
        //     nœud est une PARTIE du projecteur (LOD d'interaction + cube-proxy), pas
        //     un décor importé.
        modelLoop@ for ((key, pair) in byModel) {
            val (m, idxs) = pair
            if (nodes >= MAX_NODES) { truncated = true; break }
            val prep = preparedModels[key] ?: continue
            val adjust = scaleMat(m.unitScaleToMm.toFloat()) * (if (m.yUp) RX90 else IDENTITY4)
            val isGltf = prep.glb != null
            val glbQueue = prep.glb ?: ArrayDeque()
            val built = prep.built
            for (fi2 in idxs) {
                if (nodes >= MAX_NODES) { truncated = true; break@modelLoop }
                val world = conv * drMat(fixtures[fi2].transform.m) * adjust
                val fixLayer = fixtures[fi2].layerName
                var any = false
                if (isGltf) {
                    val inst = glbQueue.removeFirstOrNull() ?: continue
                    val node = io.github.sceneview.node.ModelNode(inst)
                    node.transform = world
                    enqueueAdd(node)
                    lod.addEntry(node, fixLayer, autoCandidate = true)
                    nodes++; any = true
                } else {
                    for ((geom, color) in built) {
                        val node = GeometryNode(engine, geom, listOf(material(color)))
                        node.transform = world
                        enqueueAdd(node)
                        lod.addEntry(node, fixLayer, autoCandidate = true)
                        nodes++; any = true
                    }
                }
                if (any) {
                    gdtfDone.add(fi2)
                    if (fi2 < layout.positions.size && layout.valid[fi2]) {
                        val proxy = io.github.sceneview.node.CubeNode(
                            engine, Float3(layout.cube, layout.cube, layout.cube),
                            layout.positions[fi2], material(layout.colors[fi2])
                        )
                        proxy.isVisible = false
                        enqueueAdd(proxy)
                        lod.addProxy(proxy, fixLayer)
                    }
                }
                slice()
            }
            progress?.report(LoadStep.GDTF, gdtfDone.size.toFloat() / gdtfTotal)
        }
        flushAdds()   // pousse le dernier lot de nœuds en attente
        gdtfFixtures = gdtfDone
        progress?.report(LoadStep.GDTF, 1f)
        // Reste-t-il le plan DXF à préparer ? Si oui, on passe la main à l'étape
        // « Préparation du plan… » ; le 100 % est posé par l'effet de fin, quand
        // les DEUX passes (3D + plan) sont terminées.
        if (referencePlan != null && !planPassDone) progress?.report(LoadStep.PLAN, 0f)
        buildTick++   // scène prête → le solo (s'il est actif) sera ré-appliqué

        placedObjects = placed
        truncatedObjects = truncated
        gdtfLoading = false
    }

    // Solo (3D) : n'afficher QUE les projecteurs soloés — le reste (décor,
    // silhouettes GDTF, cubes des autres projecteurs) est masqué. Filtrage PAR
    // VISIBILITÉ (aucune reconstruction). Re-déclenché quand le solo change OU
    // quand la scène vient d'être (re)construite (buildTick) : un solo hérité de
    // la vue plan est ainsi ré-appliqué dès que la scène est prête.
    LaunchedEffect(soloElements, buildTick, importedModelsVersion) {
        applySolo3D(geometryRoot, lod, soloElements)
        // Le décor IMPORTÉ suit la même règle que celui du show : masqué en solo,
        // rendu au repos (cohérence — sinon un praticable importé resterait seul à
        // l'écran avec les projecteurs isolés).
        runCatching { importedRoot.childNodes.forEach { it.isVisible = soloElements.isEmpty() } }
    }

    // LOD par calque (#1) : recalcule les listes plates consommées par onFrame.
    // Re-déclenché au changement de réglage ET après chaque (re)construction
    // (buildTick) pour que le réglage survive à un rebuild GDTF/custom.
    LaunchedEffect(layerLod, buildTick) {
        lod.applyModes(layerLod)
    }

    // Plan de repère DXF en 3D : lignes au sol, placées par la transformée du plan
    // (offset/rotation/échelle/hauteur), dans le MÊME repère monde que les
    // projecteurs (le retour en 3D recompose → reflète les derniers réglages).
    // R7 — la VERSION du placement fait partie de la clé, exactement comme pour le
    // plan matriciel : la transformée est mutée EN PLACE, sans elle les traits DXF
    // resteraient figés à l'ancien emplacement tant que l'instance ne change pas.
    LaunchedEffect(referencePlan, refPlanTransformVersion, hiddenLayers) {
        dxfRoot.childNodes.toList().forEach {
            dxfRoot.removeChildNode(it)
            runCatching { it.destroy() }   // libère VertexBuffer/IndexBuffer (sinon fuite à chaque ré-import)
        }
        val rp = referencePlan ?: run { planPassDone = true; return@LaunchedEffect }
        if (!rp.transform.visible || rp.plan.isEmpty) { planPassDone = true; return@LaunchedEffect }
        val cx = center.x; val cy = center.y; val cz = center.z
        // Prep HORS THREAD PRINCIPAL : chaque segment DXF devient un fin QUAD posé
        // à plat au sol (2 triangles, double-face). On rend des TRIANGLES — le
        // même chemin que la géométrie 3ds, fiable — plutôt que des primitives
        // LINES que SceneView ne restitue pas correctement (elles se remplissent).
        val prep = withContext(Dispatchers.Default) {
            val tf = rp.transform
            val s = tf.effScale; val r = Math.toRadians(tf.rotationDeg)
            val cc = kotlin.math.cos(r); val sn = kotlin.math.sin(r)
            // Bascule (tilt) : raccourcit l'empreinte au sol le long de Y AVANT la
            // rotation à plat, et fait varier la hauteur du trait avec Y — même
            // ordre que `ReferencePlanTransform.worldXY`/`worldZ`.
            val tiltR = Math.toRadians(tf.tiltDeg)
            val tiltCos = kotlin.math.cos(tiltR); val tiltSin = kotlin.math.sin(tiltR)
            val ox = tf.offsetX; val oy = tf.offsetY; val hz = tf.heightZ.toFloat()
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
                // Sommets projetés en Filament (plan XZ) + hauteur (Y Filament),
                // une fois par polyligne.
                val fxz = FloatArray(n * 2)
                val fy = FloatArray(n)
                for (k in 0 until n) {
                    val sx = pts[k * 2] * s; val sy0 = pts[k * 2 + 1] * s
                    val sy = sy0 * tiltCos
                    val wx = ox + (sx * cc - sy * sn)   // monde mm X
                    val wy = oy + (sx * sn + sy * cc)   // monde mm Y
                    val wz = hz + sy0 * tiltSin          // monde mm Z (bascule)
                    fxz[k * 2] = (wx.toFloat() - cx) / 1000f
                    fxz[k * 2 + 1] = -(wy.toFloat() - cy) / 1000f
                    fy[k] = (wz.toFloat() - cz) / 1000f
                }
                val segCount = (n - 1) + if (pl.closed && n > 2) 1 else 0
                for (e in 0 until segCount) {
                    val a = e; val b = (e + 1) % n
                    val ax = fxz[a * 2]; val az = fxz[a * 2 + 1]; val ay = fy[a]
                    val bx = fxz[b * 2]; val bz = fxz[b * 2 + 1]; val by = fy[b]
                    val dx = bx - ax; val dz = bz - az
                    val len = sqrt(dx * dx + dz * dz)
                    if (len < 1e-6f) continue
                    val px = -dz / len * hw; val pz = dx / len * hw
                    val base = verts.size
                    verts.add(Geometry.Vertex(position = Float3(ax + px, ay, az + pz), normal = up)) // 0 A1
                    verts.add(Geometry.Vertex(position = Float3(ax - px, ay, az - pz), normal = up)) // 1 A2
                    verts.add(Geometry.Vertex(position = Float3(bx + px, by, bz + pz), normal = up)) // 2 B1
                    verts.add(Geometry.Vertex(position = Float3(bx - px, by, bz - pz), normal = up)) // 3 B2
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
        if (vByColor.isEmpty()) { planPassDone = true; return@LaunchedEffect }
        // Build moteur (thread principal) : une géométrie + un matériau par couleur.
        for ((rgb, verts) in vByColor) {
            val idx = iByColor[rgb] ?: continue
            if (verts.size < 3 || idx.isEmpty()) continue
            val geom = Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
                .vertices(verts).primitivesIndices(listOf(idx)).build(engine)
            val mat = dxfMatByColor.getOrPut(rgb) { materialLoader.createUnlitColorInstance(dxf3DColorInt(rgb)) }
            dxfRoot.addChildNode(GeometryNode(engine, geom, listOf(mat)))
        }
        planPassDone = true
    }

    // FIN DU CHARGEMENT (#4) : 100 % seulement quand la scène 3D est construite
    // (buildTick) ET que la passe du plan de repère a terminé — les deux effets
    // sont concurrents, l'ordre d'arrivée n'est pas garanti.
    LaunchedEffect(buildTick, planPassDone) {
        if (buildTick > 0 && planPassDone) progress?.finish()
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
        satGpu.material?.let { m -> runCatching { materialLoader.destroyMaterialInstance(m) } }; satGpu.material = null
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

    // Plan de repère MATRICIEL (JPEG / PNG / page 1 d'un PDF) posé au sol : quad
    // texturé aux 4 coins MONDE de l'image, donc exactement au même endroit qu'en
    // vue plan (mêmes décalage / rotation / échelle / homothétie). Placé 1 cm sous
    // le sol MVR — au-dessus du satellite (−2 cm), sous les traits DXF (z = 0) —
    // pour éviter tout z-fighting entre les trois fonds.
    // La clé est la VERSION du placement (et non l'instance du plan) : la
    // transformée étant mutée EN PLACE, régler l'homothétie ou le décalage doit
    // se voir IMMÉDIATEMENT en 3D comme en 2D.
    LaunchedEffect(referencePlan, refPlanTransformVersion) {
        rasterRoot.childNodes.toList().forEach {
            rasterRoot.removeChildNode(it); runCatching { it.destroy() }
        }
        rasterGpu.material?.let { m -> runCatching { engine.destroyMaterialInstance(m) } }; rasterGpu.material = null
        rasterGpu.texture?.let { t -> runCatching { engine.destroyTexture(t) } }; rasterGpu.texture = null
        val rp = referencePlan ?: return@LaunchedEffect
        val rast = rp.raster ?: return@LaunchedEffect
        if (!rp.transform.visible || rast.bitmap.width <= 0 || rast.bitmap.height <= 0) return@LaunchedEffect
        val tex = runCatching {
            io.github.sceneview.texture.ImageTexture.Builder().bitmap(rast.bitmap).build(engine)
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
        rasterGpu.texture = tex; rasterGpu.material = mat
        val cx = center.x; val cy = center.y; val cz = center.z
        val fy = (rp.transform.heightZ.toFloat() - cz) / 1000f - 0.01f
        fun fp(x: Float, y: Float) = Float3((x - cx) / 1000f, fy, -(y - cy) / 1000f)
        val up = Float3(0f, 1f, 0f)
        // Coins NO / NE / SE / SO en monde mm (Y vers le haut) ; l'image est
        // « nord en haut » comme le quad satellite → mêmes UV.
        val c4 = rast.worldCorners(rp.transform)
        val quad = listOf(
            Geometry.Vertex(position = fp(c4[0], c4[1]), normal = up, uvCoordinate = Float2(0f, 0f)),
            Geometry.Vertex(position = fp(c4[2], c4[3]), normal = up, uvCoordinate = Float2(1f, 0f)),
            Geometry.Vertex(position = fp(c4[4], c4[5]), normal = up, uvCoordinate = Float2(1f, 1f)),
            Geometry.Vertex(position = fp(c4[6], c4[7]), normal = up, uvCoordinate = Float2(0f, 1f))
        )
        val geom = Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
            .vertices(quad).primitivesIndices(listOf(listOf(0, 1, 2, 0, 2, 3))).build(engine)
        rasterRoot.addChildNode(GeometryNode(engine, geom, listOf(mat)))
    }

    // ---- DÉCOR 3D IMPORTÉ (chantier #3) ------------------------------------
    // Effet INDÉPENDANT de la construction du show : sur une liste vide il sort
    // immédiatement, donc un MVR sans modèle importé se charge EXACTEMENT comme
    // avant (invariant de non-régression).
    // Clé = (liste, VERSION) : la transformée étant mutée en place, seule la
    // version signale un changement de placement.
    var modelBusy by remember { mutableStateOf(false) }
    LaunchedEffect(importedModels, importedModelsVersion) {
        // Purge : détacher d'un coup (clearChildNodes = un seul recalcul de Set)
        // puis détruire chaque nœud, puis libérer les assets glTF.
        val oldNodes = importedRoot.childNodes.toList()
        runCatching { importedRoot.clearChildNodes() }
        oldNodes.forEachIndexed { i, n ->
            runCatching { n.destroy() }
            if (i and 63 == 0) yield()
        }
        importedAssets.forEach { asset ->
            runCatching { modelLoader.destroyModel(asset) }
            runCatching { modelLoader.assetLoader.destroyAsset(asset) }
        }
        importedAssets.clear()
        if (importedModels.isEmpty()) { modelBusy = false; return@LaunchedEffect }

        modelBusy = true
        val convM = conversionMatrix(center)
        // Solo actif : le décor importé est masqué comme celui du show (cohérence
        // avec applySolo3D). Lu à l'entrée de l'effet ; l'effet solo re-synchronise.
        val visibleNow = soloElements.isEmpty()
        var lastYieldM = System.nanoTime()
        suspend fun sliceM() {
            val now = System.nanoTime()
            if (now - lastYieldM > 16_000_000L) { yield(); lastYieldM = System.nanoTime() }
        }
        val pendingModels = HashSet<io.github.sceneview.node.Node>(64)
        suspend fun flushModels() {
            if (pendingModels.isEmpty()) return
            runCatching { importedRoot.addChildNodes(HashSet(pendingModels)) }
            pendingModels.clear()
            sliceM()
        }
        var placedNodes = 0
        for (m in importedModels) {
            if (placedNodes >= com.minou.mvrviewer.mvr.SceneModelLoader.MODEL_MAX_NODES) break
            val tf = m.transform
            if (!tf.visible) continue
            // MVR (mm, Z-haut) : T · Rz · Rx(tilt) · S, puis conversion en repère
            // Filament. RX90 seulement si la source est Y-haut (glTF, OBJ) — sinon
            // le modèle arriverait couché.
            val local = translateMat(tf.offsetX.toFloat(), tf.offsetY.toFloat(), tf.offsetZ.toFloat()) *
                rotZMat(tf.rotationDeg) *
                rotXMat(tf.tiltDeg) *
                scaleMat((tf.effScale * m.unitScaleToMm).toFloat())
            val world = convM * local * (if (m.yUp) RX90 else IDENTITY4)
            if (m.format == com.minou.mvrviewer.mvr.SceneModelLoader.Format.GLTF.name) {
                val data = m.sourceBytes ?: continue
                val instances = runCatching {
                    modelLoader.createInstancedModel(java.nio.ByteBuffer.wrap(data), 1)
                }.getOrNull() ?: continue
                instances.firstOrNull()?.asset?.let { importedAssets.add(it) }
                forceDoubleSidedTransparency(instances)
                instances.firstOrNull()?.let { inst ->
                    val node = io.github.sceneview.node.ModelNode(inst)
                    node.transform = world
                    node.isVisible = visibleNow
                    pendingModels.add(node); placedNodes++
                }
            } else {
                for (mesh in m.meshes) {
                    if (placedNodes >= com.minou.mvrviewer.mvr.SceneModelLoader.MODEL_MAX_NODES) break
                    // Conversion buffers plats → sommets Filament HORS thread
                    // principal : c'est le seul coût CPU notable, et il reste borné
                    // à UN maillage à la fois.
                    val prepared = withContext(Dispatchers.Default) { meshVertices(mesh) }
                    val geom = runCatching {
                        Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
                            .vertices(prepared.first).primitivesIndices(listOf(prepared.second)).build(engine)
                    }.getOrNull() ?: continue
                    val mat = importedMatByColor.getOrPut(mesh.color) {
                        materialLoader.createColorInstance(mesh.color)
                    }
                    val node = GeometryNode(engine, geom, listOf(mat))
                    node.transform = world
                    node.isVisible = visibleNow
                    pendingModels.add(node); placedNodes++
                    if (pendingModels.size >= 50) flushModels()
                    sliceM()
                }
            }
        }
        flushModels()
        // NB : AUCUNE inscription au LOD d'interaction. Règle du gros décor .glb —
        // un décor importé explicitement par l'utilisateur ne doit jamais
        // disparaître en navigation.
        modelBusy = false
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
    // Pivot courant, translation « garder visible » comprise (DistanceZoomManipulator.shift).
    fun pivot(): Float3 {
        val s = manipulator.shift
        return Float3(target.x + s.x, target.y + s.y, target.z + s.z)
    }

    // ---- Sélection par cadre (rectangle) dans la vue 3D (comme la vue plan et
    // iOS) : en « mode rectangle », le glissé trace un cadre et sélectionne tous
    // les projecteurs dont la projection écran tombe dedans. Hors ce mode, le
    // glissé pilote la caméra normalement. `selected` = index dans layout/fixtures.
    val selected = remember(scene) { mutableStateListOf<Int>() }
    // Sélection remontée à la scène (clés d'INSTANCE) — bibliothèque MA, « affecter à
    // la sélection ». La dernière valeur survit au passage dans la bibliothèque.
    LaunchedEffect(selected, fixtureKeys) {
        androidx.compose.runtime.snapshotFlow { selected.toList() }.collect { sel ->
            onSelectionChanged(sel.mapNotNull { fixtureKeys.getOrNull(it) })
        }
    }
    var rectMode by remember(scene) { mutableStateOf(false) }
    // MULTI-SÉLECTION (SPEC-menus-android §9.0, Q2) : toucher REMPLACE la sélection
    // (comme le plan et iOS) ; avec Multi-sélection — ou le cadre armé, qui la
    // force — il ajoute / retire. Règles pures : SelectionRules.
    var multiMode by remember(scene) { mutableStateOf(false) }
    fun selectionModes() = SelectionModes(multi = multiMode, rect = rectMode)
    // Point de vue de la pastille « Vue » (§3.3) : « Perspective » à l'ouverture.
    var viewpoint by remember(scene) { mutableStateOf(CameraViewpoint.PERSPECTIVE) }
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
        remember(sceneFixtures, options.labelFields, cabling.version, dmxCabling.version, overrides.version) {
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
                        mvrInstanceKey(o), 0f, 0f, o.fixtureId, overrides.effectiveName(o), o.gdtfSpec,
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
    // Cadres (px racine) qui bornent les panneaux du bas-gauche (tranche B,
    // PanelPlacementMath.floatingPanelSlot) et font monter la pile bas-droite : en
    // haut la rangée du haut, le bloc d'état et la pastille « Vue » (tranche C) ; en
    // bas la pastille de sélection, le bandeau de mesure et la mention IGN.
    var sceneBoxPx by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var statusBlock3DPx by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var viewPill3DPx by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var topRow3DBottomPx by remember { mutableStateOf(0f) }
    // ← et groupe de droite de la rangée : la barre ancrée du haut se pose entre eux.
    var topRow3DBackPx by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var topRow3DTrailingPx by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var selectionCard3DPx by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var measureBand3DPx by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // Cadres AFFICHÉS des panneaux flottants (barre ancrée gauche, §3.13).
    val panelFrames3D = remember { PanelFrames() }
    var satMention3DPx by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    // Recherche d'un projecteur par N° / nom → le sélectionne (la surbrillance le
    // montre s'il est à l'écran ; sinon l'utilisateur oriente l'orbite pour le
    // trouver). Classement : id exact > préfixe > contenu > nom.
    var query by remember(scene) { mutableStateOf("") }
    /** Rend vrai si au moins un projecteur a été trouvé (sinon « Aucun projecteur nº … »). */
    fun doSearch3D(): Boolean {
        val q = query.trim(); if (q.isEmpty()) return false
        val fx = scene.fixtures
        fun usable(i: Int) = i in layout.positions.indices && layout.valid[i]
        // Un même N° est souvent porté par PLUSIEURS projecteurs (multicellules,
        // doublons de patch) : on prend TOUT le groupe de N° exact et on le
        // sélectionne en entier. À défaut de N° exact (saisie partielle), on
        // retombe sur le meilleur classement (préfixe > contenu > nom).
        val exact = fx.indices.filter { usable(it) && fx[it].fixtureId.equals(q, true) }
        val matches: List<Int> = if (exact.isNotEmpty()) exact else {
            var best = -1; var bestRank = Int.MAX_VALUE
            fx.forEachIndexed { i, f ->
                if (!usable(i)) return@forEachIndexed
                val id = f.fixtureId
                val rank = when {
                    id != null && id.startsWith(q, true) -> 1
                    id != null && id.contains(q, true) -> 2
                    overrides.effectiveName(f).contains(q, true) -> 3
                    else -> Int.MAX_VALUE
                }
                if (rank < bestRank) { bestRank = rank; best = i }
            }
            if (best >= 0) listOf(best) else emptyList()
        }
        if (matches.isEmpty()) return false
        selected.clear(); selected.addAll(matches)
        // …et on Y VA : le pivot se pose sur le centroïde du groupe et la caméra
        // se place en CONSERVANT la direction de vue courante (on ne désoriente
        // pas l'utilisateur, on recadre seulement). La surbrillance + le cube de
        // sélection matérialisent chaque projecteur trouvé.
        val ps = matches.map { layout.positions[it] }
        val cx = ps.map { it.x }.average().toFloat()
        val cy = ps.map { it.y }.average().toFloat()
        val cz = ps.map { it.z }.average().toFloat()
        val ctr = Float3(cx, cy, cz)
        // Rayon du groupe = distance max au centroïde (0 pour un seul projecteur).
        var groupR = 0f
        for (p in ps) {
            val d0 = kotlin.math.sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy) + (p.z - cz) * (p.z - cz))
            if (d0 > groupR) groupR = d0
        }
        val cam = cameraNode.worldPosition
        val pv = pivot()
        var vx = cam.x - pv.x; var vy = cam.y - pv.y; var vz = cam.z - pv.z
        val len = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
        if (!len.isFinite() || len < 1e-3f) {   // caméra sur le pivot : vue 3/4 par défaut
            vx = 0f; vy = 0.45f; vz = 1f
            val n = kotlin.math.sqrt(vy * vy + vz * vz); vy /= n; vz /= n
        } else { vx /= len; vy /= len; vz /= len }
        // Assez près pour lire un projecteur seul, assez loin pour tenir tout le
        // groupe (terme ∝ rayon), sans jamais sortir de la scène.
        val near = (layout.cube * 10f).coerceIn(1.5f, layout.radius * 0.5f)
        val d = (near + groupR * 2.2f).coerceAtMost((layout.radius * 3f).coerceAtLeast(near))
        target = ctr
        camEye = Float3(ctr.x + vx * d, ctr.y + vy * d, ctr.z + vz * d)
        return true
    }

    // Projette la position monde (Filament) d'un projecteur en pixels écran de la
    // SceneView (origine coin haut-gauche, comme Compose). null si derrière la
    // caméra ou invalide. La caméra orbite → son axe avant = vers la cible.
    fun projectFixture(i: Int): Offset? {
        if (i !in layout.positions.indices || !layout.valid[i]) return null
        val wp = layout.positions[i]
        val cam = cameraNode.worldPosition
        val pv = pivot()
        val fdx = pv.x - cam.x; val fdy = pv.y - cam.y; val fdz = pv.z - cam.z
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
        val pv = pivot()
        val fdx = pv.x - cam.x; val fdy = pv.y - cam.y; val fdz = pv.z - cam.z
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
    // N12 — lambda d'édition tenue À JOUR (le tapListener est remember(scene) et
    // capturerait sinon la première instance). Lire `.value` au moment de l'appui
    // long donne toujours le dernier `onEditFixture`.
    val currentOnEdit = rememberUpdatedState(onEditFixture)
    // APPUI LONG : sélectionne le projecteur le plus proche (comme le tap) PUIS
    // ouvre la fiche d'édition patch. Ne fait rien dans les modes qui s'approprient
    // déjà le geste (rectangle, mesure) ni si aucun projecteur n'est visé.
    fun handleLongPress(pos: Offset) {
        if (rectMode || measureMode) return
        var best = -1; var bestD = 55f
        for (i in layout.positions.indices) {
            val o = projectFixture(i) ?: continue
            val d = kotlin.math.hypot(o.x - pos.x, o.y - pos.y)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return
        val obj = sceneFixtures.getOrNull(best) ?: return
        // Sélection unique (comme le tap qui ne cible qu'un projecteur), puis fiche.
        selected.clear(); selected.add(best)
        projVersion++
        currentOnEdit.value(obj)
    }

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
        // Toucher = remplace ; en multi (ou cadre armé) = ajoute / retire ; le
        // vide désélectionne, sauf en multi (SelectionRules, Q2).
        val next = SelectionRules.tap(selected.toList(), best.takeIf { it >= 0 }, selectionModes())
        if (next != selected.toList()) { selected.clear(); selected.addAll(next) }
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
            // N12 : appui long → sélection + fiche d'édition patch (mêmes coordonnées
            // que le tap : repère de la SceneView).
            override fun onLongPress(e: android.view.MotionEvent, node: io.github.sceneview.node.Node?) {
                handleLongPress(Offset(e.rawX - scenePos.x, e.rawY - scenePos.y))
            }
        }
    }

    // N11 — panneau « Personnaliser la barre d'outils… » (mode édition).
    var showCustomize by remember { mutableStateOf(false) }
    // N11 — couleur du fond ouvrable depuis un bouton DOCKÉ (outil BACKGROUND) : le
    // menu a déjà ses presets + « Personnalisée… », mais un bouton dans une barre
    // doit pouvoir ouvrir le sélecteur directement.
    var showBackgroundDialog by remember { mutableStateOf(false) }

    // ---- Import d'un modèle 3D de décor (chantier #3) ----
    // Sélecteur en « */* » : les formats 3D n'ont pas de type MIME fiable (même
    // raison que le DXF). Le routage se fait sur les OCTETS DE TÊTE, hors thread
    // principal, dans SceneModelLoader.
    val modelCtx = androidx.compose.ui.platform.LocalContext.current
    var showModelsPanel by remember { mutableStateOf(false) }
    var importingModel by remember { mutableStateOf(false) }
    var pickedModelUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    // Avant de choisir un fichier : rappel des formats lus, pour que l'échec
    // d'un format non pris en charge (SKP, DAE…) ne surprenne pas l'utilisateur.
    var showModelImportFormats by remember { mutableStateOf(false) }
    val modelImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) { pickedModelUri = uri; importingModel = true } }
    // Objet 3D ouvert seul : on déclenche l'import EXACTEMENT comme si
    // l'utilisateur l'avait choisi depuis le menu — même décodeur, mêmes
    // plafonds, même persistance. Rien de parallèle à maintenir.
    LaunchedEffect(initialModelUri) {
        val uri = initialModelUri ?: return@LaunchedEffect
        pickedModelUri = uri
        importingModel = true
        onInitialModelConsumed()
    }
    LaunchedEffect(pickedModelUri) {
        val uri = pickedModelUri ?: return@LaunchedEffect
        val loader = com.minou.mvrviewer.mvr.SceneModelLoader
        if (importedModels.size >= loader.MODEL_MAX_COUNT) {
            importingModel = false; pickedModelUri = null
            modelMessage = sModelTooManyFmt.format(loader.MODEL_MAX_COUNT)
            return@LaunchedEffect
        }
        // Budget de triangles RESTANT (plafond cumulé). Un budget insuffisant REFUSE
        // l'import avec un message chiffré (comportement iOS) : rien n'est rattaché
        // à la scène, rien n'est recopié dans le projet. Les glTF y comptent aussi,
        // via l'estimation dérivée de leur taille.
        val used = importedModels.sumOf { it.triangles }
        val res = withContext(Dispatchers.IO) {
            val cr = modelCtx.contentResolver
            val nm = loader.displayName(cr, uri)
            loader.load(cr, uri, nm, loader.MODEL_TOTAL_TRIANGLES - used)
        }
        importingModel = false
        pickedModelUri = null
        when (res) {
            is com.minou.mvrviewer.mvr.SceneModelLoader.LoadResult.Ok -> {
                onSetImportedModels(importedModels + res.model)
                selectedModelId = res.model.id
                showModelsPanel = true
                modelMessage = null
            }
            // Le décodeur ne rend qu'un MOTIF chiffré : la phrase (donc la langue)
            // se décide ici, à l'affichage.
            is com.minou.mvrviewer.mvr.SceneModelLoader.LoadResult.Unsupported ->
                modelMessage = ModelImportMessages.text(modelCtx, res.reason)
            is com.minou.mvrviewer.mvr.SceneModelLoader.LoadResult.Failed ->
                modelMessage = ModelImportMessages.text(modelCtx, res.reason)
        }
    }
    // N11 — DESCRIPTION UNIFIÉE des outils de la vue 3D : UNE seule liste consommée
    // par les 4 barres ancrables (AnchoredToolbars), les boutons permanents de la
    // rangée du haut (cadre, multi) et le catalogue « Barre d'outils de cette
    // vue… ». La barre flottante historique est retirée (§9.0, Q1) et le menu ⋯ a
    // ses propres entrées (MainMenuModel) : chaque outil a une autre place.
    val ctx3dSat = androidx.compose.ui.platform.LocalContext.current
    // Cadre et multi (rangée du haut, outils ancrés) : armer le cadre force le
    // multi et coupe la mesure ; couper le multi vide la sélection et coupe le cadre.
    fun toggleRectTool() {
        val t = SelectionRules.toggleRect(selectionModes())
        rectMode = t.modes.rect; multiMode = t.modes.multi
        if (rectMode && measureMode) { measureMode = false; measureI = null; measureJ = null }
        if (!rectMode) { rectStart = null; rectEnd = null }
    }
    fun toggleMultiTool() {
        val t = SelectionRules.toggleMulti(selectionModes())
        rectMode = t.modes.rect; multiMode = t.modes.multi
        if (t.clearSelection) selected.clear()
        if (!rectMode) { rectStart = null; rectEnd = null }
    }
    // Mesurer (⋯ › rangée 1, outil ancré) : exclusif du cadre ; repart de zéro.
    fun toggleMeasureTool() {
        measureMode = !measureMode
        if (measureMode) { rectMode = false; rectStart = null; rectEnd = null }
        measureI = null; measureJ = null
    }
    // Isoler (solo) de la sélection / retirer l'isolement (outil SOLO, ⋯ › Masquer & isoler).
    fun toggleSolo3D() {
        if (soloElements.isEmpty()) {
            val keys = selected.mapNotNull { fixtureKeys.getOrNull(it) }.toSet()
            if (keys.isNotEmpty()) onSetSoloElements(keys)
        } else onSetSoloElements(emptySet())
    }
    // Recadrer (pile bas-droite, outil `resetCamera`) : pivot au centre du show,
    // caméra à sa position d'ensemble — la pastille revient à « Perspective ».
    fun reframe3D() {
        target = Float3(0f, 0f, 0f); camEye = camHome; viewpoint = CameraViewpoint.PERSPECTIVE
    }
    // Point de vue de la pastille : autour du pivot courant (après une recherche,
    // autour du projecteur trouvé — l'usage terrain). Retoucher le même y ramène.
    fun applyViewpoint(v: CameraViewpoint) {
        val (x, y, z) = v.eyeOffset(v.recoil(layout.radius * 2f, sceneBoxPx.width, sceneBoxPx.height))
        // Autour du pivot COURANT (décalage « garder visible » compris) : le nouveau
        // manipulateur repart sans décalage.
        val pv = pivot()
        target = pv
        camEye = Float3(pv.x + x, pv.y + y, pv.z + z)
        viewpoint = v
    }
    val tools3D: List<ToolSpec> = ToolCatalog.ordered(ToolbarView.SCENE_3D, buildList {
        // Boutons permanents de la rangée du haut (masqués quand ancrés, §3.1).
        add(ToolSpec(ToolId.RECT, stringResource(R.string.tool_rect_select), Icons.Filled.Crop,
            available = true, checked = rectMode, onInvoke = { toggleRectTool() }))
        add(ToolSpec(ToolId.MULTI_SELECT, stringResource(R.string.perm_multi_select), Icons.Filled.Checklist,
            available = true, checked = multiMode || rectMode, onInvoke = { toggleMultiTool() }))
        add(ToolSpec(ToolId.MEASURE, stringResource(R.string.tool_measure), Icons.Filled.Straighten,
            available = true, checked = measureMode, onInvoke = { toggleMeasureTool() }))
        add(ToolSpec(ToolId.SOLO, stringResource(R.string.tool_solo), Icons.Filled.CenterFocusStrong,
            available = selected.isNotEmpty() || soloElements.isNotEmpty(),
            checked = soloElements.isNotEmpty(), onInvoke = { toggleSolo3D() }))
        add(ToolSpec(ToolId.CLEAR_SEL, stringResource(R.string.tool_clear_selection), Icons.Filled.Close,
            available = selected.isNotEmpty(), checked = null, onInvoke = { selected.clear() }))
        // « Afficher ma position » : même titre que la ligne du panneau Position GPS ;
        // en 3D, le point n'existe que par la calibration (§8 D20).
        add(ToolSpec(ToolId.GPS, stringResource(R.string.tool_gps), Icons.Filled.MyLocation,
            available = calibration?.isCalibrated == true, checked = showLocation,
            onInvoke = { showLocation = !showLocation }))
        // Panneau « Position GPS » (tranche B) : ouvre / referme (actif = jaune).
        add(ToolSpec(ToolId.GEO_PANEL, stringResource(R.string.geo_panel_title), Icons.Filled.LocationOn,
            available = true, checked = panels.geo,
            onInvoke = { onPanels(panels.toggle(ScenePanel.GEO)) }))
        add(ToolSpec(ToolId.GPS_MARKER_SIZE, stringResource(R.string.tool_marker_size), Icons.Filled.PhotoSizeSelectSmall,
            available = calibration?.isCalibrated == true && showLocation, checked = null, onInvoke = {
                options.gpsMarkerScale = when {
                    options.gpsMarkerScale <= 0.7f -> 1f
                    options.gpsMarkerScale >= 1.5f -> 0.6f
                    else -> 1.6f
                }
            }))
        // Plan importé (tranche E) : deux outils à libellé FIXE — `importDXF` importe
        // (Pro, garde dans SceneScreen), `refPlanPanel` ouvre / referme le panneau
        // (lecture pour tous, décision 6), seulement s'il y a un plan.
        add(ToolSpec(ToolId.DXF, stringResource(R.string.tool_import_ref_plan), Icons.Filled.UploadFile,
            available = true, checked = null, busy = importingRefPlan, onInvoke = onImportRefPlan))
        add(ToolSpec(ToolId.REF_PLAN_PANEL, stringResource(R.string.tool_ref_plan_panel), Icons.Filled.Tune,
            available = referencePlan != null, checked = panels.refPlan,
            onInvoke = { onPanels(panels.toggle(ScenePanel.REF_PLAN)) }))
        // Modèles 3D de décor (tranche E) : `importModel` importe (dialogue des
        // formats, Pro), `modelsPanel` ouvre / referme le panneau (Pro à l'entrée,
        // inchangé), seulement s'il y a un modèle ; `busy` pendant le décodage.
        add(ToolSpec(ToolId.IMPORT_MODEL, stringResource(R.string.tool_import_model), Icons.Filled.ViewInAr,
            available = true, checked = null, busy = importingModel || modelBusy,
            onInvoke = { ProAccess.gate("importer un objet 3D") { showModelImportFormats = true } }))
        add(ToolSpec(ToolId.MODELS_PANEL, stringResource(R.string.tool_models_panel), Icons.Filled.Widgets,
            available = importedModels.isNotEmpty(), checked = showModelsPanel,
            onInvoke = {
                if (showModelsPanel) showModelsPanel = false
                else ProAccess.gate("régler les objets 3D importés") { showModelsPanel = true }
            }))
        // Feuille « Calques » (la même que ⋯ › Calques).
        add(ToolSpec(ToolId.LAYERS, stringResource(R.string.menu_layers), Icons.Filled.Layers,
            available = true, checked = null, onInvoke = onShowLayers))
        add(ToolSpec(ToolId.LABELS, stringResource(R.string.opt_labels), Icons.Filled.Label,
            available = true, checked = options.showLabels,
            onInvoke = { options.showLabels = !options.showLabels }))
        add(ToolSpec(ToolId.LAYER_COLORS, stringResource(R.string.opt_layer_colors), Icons.Filled.Palette,
            available = true, checked = options.layerColors,
            onInvoke = { options.layerColors = !options.layerColors }))
        // Fond satellite AUSSI ancrable en 3D, une fois la position calibrée. Hors
        // couverture IGN (France seulement) : Toast, PAS de bascule.
        add(ToolSpec(ToolId.SATELLITE, stringResource(R.string.tool_satellite), Icons.Filled.Public,
            available = calibration?.isCalibrated == true, checked = options.showSatellite && satelliteCovered,
            onInvoke = {
                SatelliteUi.onToolTap(satelliteCovered, options::toggleSatelliteByUser)
                    ?.let { SatelliteUi.toast(ctx3dSat, it) }
            }))
        // « Réglage des étiquettes » (outil `labelSize`, comme iOS) : ouvre / referme le panneau.
        add(ToolSpec(ToolId.LABEL_SIZE, stringResource(R.string.tool_label_size), Icons.Filled.FormatSize,
            available = true, checked = panels.labels,
            onInvoke = { onPanels(panels.toggle(ScenePanel.LABELS)) }))
        // Couleur du fond : ouvre le sélecteur fin (⋯ › Affichage garde les préréglages).
        add(ToolSpec(ToolId.BACKGROUND, stringResource(R.string.menu_section_background), Icons.Filled.FormatColorFill,
            available = true, checked = null,
            onInvoke = { showBackgroundDialog = true }))
        // Recadrer : même icône que le bouton de la pile bas-droite, qu'il masque
        // quand il est ancré (§3.4).
        add(ToolSpec(ToolId.CAM_RESET, stringResource(R.string.tool_recenter_camera), Icons.Filled.ZoomOutMap,
            available = true, checked = null, onInvoke = { reframe3D() }))
        // ---- Navigation, ancrable (ailleurs : pastille « Vue », menu ⋯) ----
        add(ToolSpec(ToolId.PLAN_VIEW, stringResource(R.string.nav_view_plan), Icons.Filled.Map,
            available = true, checked = null, onInvoke = onShowPlan))
        add(ToolSpec(ToolId.PATCH, stringResource(R.string.nav_patch_list), Icons.AutoMirrored.Filled.List,
            available = true, checked = null, onInvoke = onShowPatch))
        add(ToolSpec(ToolId.UNIVERSE, stringResource(R.string.nav_dmx_universe), Icons.Filled.GridView,
            available = true, checked = null, onInvoke = onShowUniverse))
        add(ToolSpec(ToolId.CABLING, stringResource(R.string.menu_cabling), Icons.Filled.Bolt,
            available = true, checked = null, onInvoke = onShowCabling))
        // `gdtfShare` = la CONNEXION GDTF Share (sens iOS, §8 D24) ; `gdtfMapping` =
        // l'écran « Modèles de projecteurs » (que l'ancien `gdtfShare` ouvrait : la
        // migration v2 des dispositions l'a remplacé par celui-ci).
        add(ToolSpec(ToolId.GDTF_SHARE, stringResource(R.string.gdtf_share_title), Icons.Filled.CloudDownload,
            available = true, checked = null, onInvoke = onShowGdtfShareAccount))
        add(ToolSpec(ToolId.GDTF_MAPPING, stringResource(R.string.models_title), Icons.Filled.Lightbulb,
            available = true, checked = null, onInvoke = onShowFixtureModels))
        // « Compte & réglages » : toujours disponible (Pro et GDTF Share y vivent aussi).
        onShowAccount?.let { cb ->
            add(ToolSpec(ToolId.ACCOUNT, stringResource(R.string.account_settings_title), Icons.Filled.AccountCircle,
                available = true, checked = null, onInvoke = cb))
        }
        onShareProject?.let { cb ->
            add(ToolSpec(ToolId.SHARE_PROJECT, stringResource(R.string.nav_share_project), Icons.Filled.Share,
                available = true, checked = null, onInvoke = cb))
        }
        onShowHistory?.let { cb ->
            add(ToolSpec(ToolId.HISTORY, stringResource(R.string.nav_history), Icons.Filled.History,
                available = true, checked = null, onInvoke = cb))
        }
    })

    // Menu ⋯ (SPEC-menus-android §3.2) : contexte (apparitions) et répartiteur. La
    // 3D traite ses entrées (bascules, panneaux, décor importé, barre d'outils) et
    // transmet le reste à SceneScreen (destinations, import du plan, PDF, fermeture).
    val menuCtx3D = MainMenuContext(
        surface = ProjectSurface.SCENE_3D,
        hasRefPlan = referencePlan != null,
        importedModelCount = importedModels.size,
        hasSync = hasSync,
        hasCabling = hasCabling,
        hasHiddenItems = false,
        soloActive = soloElements.isNotEmpty(),
        hasSelection = selected.isNotEmpty()
    )
    val menuState3D = MainMenuState(
        measure = measureMode, labels = options.showLabels, layerColors = options.layerColors,
        isolate = soloElements.isNotEmpty()
    )
    fun onMenuItem3D(id: MainMenuItemId) {
        when (id) {
            MainMenuItemId.MEASURE -> toggleMeasureTool()
            MainMenuItemId.LABELS -> options.showLabels = !options.showLabels
            MainMenuItemId.LAYER_COLORS -> options.layerColors = !options.layerColors
            MainMenuItemId.LABEL_SETTINGS -> onPanels(panels.open(ScenePanel.LABELS))
            MainMenuItemId.TOOLBAR_THIS_VIEW -> showCustomize = true
            MainMenuItemId.ISOLATE -> toggleSolo3D()
            MainMenuItemId.CLEAR_SOLO -> onSetSoloElements(emptySet())
            MainMenuItemId.GPS_POSITION -> onPanels(panels.open(ScenePanel.GEO))
            MainMenuItemId.ADJUST_PLAN -> onPanels(panels.open(ScenePanel.REF_PLAN))
            MainMenuItemId.IMPORT_MODEL -> ProAccess.gate("importer un objet 3D") { showModelImportFormats = true }
            MainMenuItemId.IMPORTED_MODELS -> ProAccess.gate("régler les objets 3D importés") { showModelsPanel = true }
            // Destinations, import du plan, PDF (du plan : ouvert en mode export ; de
            // câblage), fermeture : la scène.
            else -> onMainMenu(id)
        }
    }

    // ---- FICHE DE SÉLECTION / BANNIÈRE MULTI (SPEC-menus-android §3.9, tranche D) ----
    // Même règle que le plan (SelectionCardLayout.presentation) : rien pendant la mesure.
    val selPresentation3D = if (selectionCard == null) PlanSelectionBar.NONE
        else SelectionCardLayout.presentation(selected.size, measuring = measureMode)
    // Compacte sous 480 dp de haut (téléphone en paysage), complète sinon.
    val cardStyle3D = SelectionCardLayout.style(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp)
    val revealDensity3D = androidx.compose.ui.platform.LocalDensity.current.density
    // PROJECTEUR GARDÉ VISIBLE (fiche compacte) : s'il tombe sous la fiche (ou à moins
    // de 28 dp), la caméra GLISSE — translation seule, sans tourner ni zoomer, ≈ 0,35 s
    // — pour le poser juste au-dessus d'elle ; jamais sous la pastille « Vue », le bloc
    // d'état ni la rangée du haut (sinon rien ne bouge) ; un geste interrompt. Relancé
    // à chaque nouvelle sélection, quand la fiche change de hauteur (« Détails ») et à
    // la rotation (iOS SPEC-menus §8.4).
    val revealIndex3D = if (selPresentation3D == PlanSelectionBar.CARD) selected.singleOrNull() ?: -1 else -1
    LaunchedEffect(revealIndex3D, selectionCard3DPx, sceneBoxPx, cardStyle3D) {
        if (revealIndex3D < 0 ||
            !SelectionCardLayout.revealsSelection(cardStyle3D, isMultiSelect = false, hasSelection = true)
        ) return@LaunchedEffect
        val card = selectionCard3DPx
        if (card.height <= 0f || sceneBoxPx.height <= 0f) return@LaunchedEffect
        val i = revealIndex3D
        val p = projectFixture(i) ?: return@LaunchedEffect
        val pRoot = Offset(p.x + sceneBoxPx.left, p.y + sceneBoxPx.top)
        val topLimit = maxOf(viewPill3DPx.bottom, statusBlock3DPx.bottom, topRow3DBottomPx, sceneBoxPx.top)
        val dy = revealShift(pRoot, card, topLimit, REVEAL_MARGIN_DP * revealDensity3D)
        if (dy >= 0f) return@LaunchedEffect
        // Pixels par mètre le long du « haut » de la caméra, à la profondeur du projecteur.
        val m = cameraNode.worldTransform
        val ul = kotlin.math.sqrt(m.y.x * m.y.x + m.y.y * m.y.y + m.y.z * m.y.z)
        if (!(ul > 1e-6f)) return@LaunchedEffect
        val ux = m.y.x / ul; val uy = m.y.y / ul; val uz = m.y.z / ul
        val wp = layout.positions[i]
        val q = projectPoint(wp.x + ux, wp.y + uy, wp.z + uz) ?: return@LaunchedEffect
        val ppm = p.y - q.y
        if (!(ppm > 1e-3f)) return@LaunchedEffect
        // Descendre la caméra de d mètres fait MONTER le projecteur de |dy| px.
        val d = -dy / ppm
        val manip = manipulator
        val from = manip.shift
        val to = Float3(from.x - ux * d, from.y - uy * d, from.z - uz * d)
        val gestures0 = manip.gestures
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            if (manip.gestures != gestures0) break   // un geste de l'utilisateur interrompt
            val t = ((now - start) / 350_000_000f).coerceIn(0f, 1f)
            val e = t * t * (3f - 2f * t)
            manip.shift = Float3(from.x + (to.x - from.x) * e, from.y + (to.y - from.y) * e, from.z + (to.z - from.z) * e)
            if (t >= 1f) break
        }
    }
    // Crochet DEBUG `MVR_AUTO_SELECT=<N°>` : sélectionne ce projecteur (N° effectif)
    // dès la scène prête — fiche ou bannière visibles sans toucher l'écran.
    LaunchedEffect(scene) {
        val id = takeAutoSelect()?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val idx = sceneFixtures.indices.filter { i ->
            layout.valid.getOrElse(i) { false } &&
                (overrides.effectiveId(sceneFixtures[i]) ?: sceneFixtures[i].fixtureId).equals(id, ignoreCase = true)
        }
        if (idx.isNotEmpty()) { selected.clear(); selected.addAll(idx) }
    }

    // Contrôles Compose flottants PAR-DESSUS la SceneView : visibles ET cliquables,
    // et un overlay pointerInput capte le glissé avant la caméra (cadre de sélection).
    // Plus de barre du haut opaque : la scène prend toute la hauteur, l'habillage
    // flotte (rangée du haut, pastille, bloc d'état, pile bas-droite — §3.1).
    Box(modifier = modifier.fillMaxSize()) {
        // Rogné à ses bornes : sans barre du haut opaque, une étiquette de projecteur
        // projetée au-dessus de la vue débordait sur la barre d'état (constat émulateur).
        Box(modifier = Modifier.fillMaxSize().clipToBounds().background(options.background3D)
            .onGloballyPositioned { scenePos = it.positionInWindow(); sceneBoxPx = it.boundsInRoot() }) {
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
                // Sous un écran plein (Patch, Câblage…), la boucle de rendu attend.
                lifecycle = renderLifecycle,
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
                    // Deux listes PLATES déjà filtrées par le réglage de calque
                    // (cf. LodState.applyModes) : aucun calcul par frame ici.
                    if (!lod.soloActive && (lod.hideOnMove.isNotEmpty() || lod.showOnMove.isNotEmpty())) {
                        if (moved) {
                            lod.idle = 0
                            if (!lod.hidden) {
                                // Masque le détail, montre les cubes-proxies.
                                lod.hideOnMove.forEach { it.isVisible = false }
                                lod.showOnMove.forEach { it.isVisible = true }
                                lod.hidden = true
                            }
                        } else {
                            lod.idle++
                            if (lod.hidden && lod.idle > 8) {
                                lod.hideOnMove.forEach { it.isVisible = true }
                                lod.showOnMove.forEach { it.isVisible = false }
                                lod.hidden = false
                            }
                        }
                    }
                }
            ) {
                Node(apply = { addChildNode(satRoot) })
                Node(apply = { addChildNode(rasterRoot) })
                // Décor importé : une racine par « couche », peuplée par son propre
                // effet, indépendante de la reconstruction MVR.
                Node(apply = { addChildNode(importedRoot) })
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
            // Elle capte donc AUSSI le toucher : il est rendu à handleTap (même repère
            // que le cadre et worldToScreenPoint) pour que, cadre armé, toucher un
            // projecteur l'ajoute ou le retire comme dans le plan (Q2, SelectionRules :
            // le cadre force le multi). Le glissé, consommé passé le seuil, annule le
            // toucher : un cadre n'est jamais pris pour un toucher.
            if (rectMode) {
                Box(
                    Modifier.fillMaxSize().pointerInput(scene) {
                        detectTapGestures(onTap = { handleTap(it) })
                    }.pointerInput(scene) {
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
                            drawFixtureLabel(textMeasurer, lines, p, options.labelSize, options.labelOffset)
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

            // N11 — barres d'outils ANCRABLES (défaut = vide). AnchoredToolbars rend
            // les 4 bords d'après `layout` en filtrant tools3D au disponible : rien
            // tant que l'utilisateur n'a pas personnalisé. Une barre vide n'est pas
            // rendue.
            // Barre GAUCHE (préremplie sur tablette, §3.13) : elle évite le bloc d'état,
            // la fiche / bannière, le bandeau de mesure et les panneaux flottants
            // ouverts (à leur place affichée), entre la rangée du haut et le bas.
            AnchoredToolbars(layout = toolbarLayout, specs = tools3D, leading = LeadingBarClearance(
                occupants = {
                    buildList {
                        add(statusBlock3DPx)
                        if (selPresentation3D != PlanSelectionBar.NONE) add(selectionCard3DPx)
                        if (measureMode) add(measureBand3DPx)
                        addAll(panelFrames3D.open(panels, otherOpen = showModelsPanel && importedModels.isNotEmpty()))
                    }
                },
                topLimitPx = { maxOf(sceneBoxPx.top, topRow3DBottomPx) },
                bottomLimitPx = { if (sceneBoxPx.height > 0f) sceneBoxPx.bottom else Float.POSITIVE_INFINITY }
            ), top = TopBarClearance(
                // Barre du HAUT : DANS la rangée, entre ← et le groupe de droite — jamais
                // sous eux ni sur la pastille « Vue » ou le bloc d'état posés dessous.
                rowOccupants = { listOf(topRow3DBackPx, topRow3DTrailingPx) },
                container = { sceneBoxPx }
            ))

            // Mention OBLIGATOIRE de l'imagerie IGN (Licence Ouverte), tant que le
            // quad satellite est affiché : bas-droite, au-dessus des barres du bas. Elle
            // MONTE au-dessus de la pastille de sélection et du bandeau de mesure de sa
            // colonne (contre le bas depuis le retrait de la barre flottante, elle
            // passait dessous — constat émulateur, tranche C).
            val satShown = satellite
            val satCredit3D = SatelliteUi.creditRes(options.showSatellite, satShown != null, satShown?.year)
            val satDens3D = androidx.compose.ui.platform.LocalDensity.current.density
            val (satLift3D, satOnTop3D) = run {
                fun androidx.compose.ui.geometry.Rect.inDp() =
                    androidx.compose.ui.geometry.Rect(left / satDens3D, top / satDens3D, right / satDens3D, bottom / satDens3D)
                val obstacles = buildList {
                    if (selPresentation3D != PlanSelectionBar.NONE) add(selectionCard3DPx.inDp())
                    if (measureMode) add(measureBand3DPx.inDp())
                }
                val mention = satMention3DPx.inDp()
                val lift = SatelliteUi.overlayLift(mention, null, obstacles, topLimit = sceneBoxPx.top / satDens3D + 60f)
                lift.dp to SatelliteUi.mentionOnTop(mention, lift, obstacles)
            }
            satCredit3D?.let { credit ->
                SatelliteAttribution(credit, toolbarLayout, tools3D, lift = satLift3D,
                    onAnchor = { satMention3DPx = it }, onTop = satOnTop3D)
            }

            // Bas-centre : contre le bas, sauf si une barre est ancrée en bas (au-dessus
            // d'elle) — la barre flottante historique n'est plus là (§9.0, Q1).
            val bottomBand3D = if (toolbarLayout.bottom.any { id -> tools3D.any { it.id == id && it.available } }) 76.dp else 16.dp
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
                    stringResource(R.string.measure_between_fmt, na, nb, formatPlanDistanceMm(mm))
                } else if (mi != null) stringResource(R.string.measure_tap_second_fixture)
                else stringResource(R.string.measure_tap_fixture)
                Surface(
                    color = MEASURE_3D_COLOR, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBand3D)
                        .onGloballyPositioned { measureBand3DPx = it.boundsInRoot() }
                ) {
                    Text(
                        txt, color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            // FICHE DE SÉLECTION / BANNIÈRE MULTI (bas-centre, §3.9) : les MÊMES que dans
            // le plan, construites par SceneScreen. Croix = désélection ; toucher la
            // fiche ne fait rien ; toucher le vide désélectionne (inchangé).
            val cardHost3D = selectionCard
            if (cardHost3D != null && selPresentation3D != PlanSelectionBar.NONE) {
                SelectionCardSlot(bottom = bottomBand3D, onBounds = { selectionCard3DPx = it }) {
                    val one = selected.singleOrNull()?.let { sceneFixtures.getOrNull(it) }
                    if (selPresentation3D == PlanSelectionBar.CARD && one != null) {
                        cardHost3D.card(one, cardStyle3D) { selected.clear() }
                    } else {
                        cardHost3D.banner(selected.size, cardStyle3D) { selected.clear() }
                    }
                }
            }

            // ---- PANNEAUX DU BAS-GAUCHE (SPEC-menus-android §3.6–3.8) : Position GPS,
            // Plan importé, Réglage des étiquettes. Chacun MONTE au-dessus de la
            // pastille de sélection, du bandeau de mesure et de la mention IGN de sa
            // colonne ; sa hauteur est bornée sous la rangée du haut, le bloc d'état et
            // la pastille « Vue » (tranche C). ----
            val panelTop3D = listOf(statusBlock3DPx, viewPill3DPx)
            // Jamais au-dessus du bas de la rangée du haut (← et ronds de droite).
            val panelTopLimit3D = maxOf(sceneBoxPx.top, topRow3DBottomPx)
            val panelBottom3D = buildList {
                if (selPresentation3D != PlanSelectionBar.NONE) add(selectionCard3DPx)
                if (measureMode) add(measureBand3DPx)
                if (satCredit3D != null) add(satMention3DPx)
            }
            if (panels.labels) BottomLeadingPanel(
                PanelLayoutStore.PanelID.LABELS_3D, panelTopLimit3D, panelTop3D, panelBottom3D,
                Modifier.zIndex(panels.zIndex(ScenePanel.LABELS)),
                onDisplayedBounds = { panelFrames3D.labels = it }
            ) { maxH ->
                LabelSettingsPanel(options = options, surface = ProjectSurface.SCENE_3D, placement = null,
                    onClose = { onPanels(panels.close(ScenePanel.LABELS)) }, maxContentHeight = maxH)
            }
            val rpPanel3D = referencePlan
            if (panels.refPlan && rpPanel3D != null) BottomLeadingPanel(
                PanelLayoutStore.PanelID.REF_PLAN_3D, panelTopLimit3D, panelTop3D, panelBottom3D,
                Modifier.zIndex(panels.zIndex(ScenePanel.REF_PLAN)),
                onDisplayedBounds = { panelFrames3D.refPlan = it }
            ) { maxH ->
                RefPlanPanel(
                    plan = rpPanel3D, surface = ProjectSurface.SCENE_3D, hiddenLayers = hiddenLayers,
                    bgDark = BackgroundColorStore.isDark(options.background3D),
                    onTouched = onRefPlanTouched, onToggleLayer = onToggleRefPlanLayer, onRemove = onRemoveRefPlan,
                    onClose = { onPanels(panels.close(ScenePanel.REF_PLAN)) }, maxContentHeight = maxH
                )
            }
            if (panels.geo) BottomLeadingPanel(
                PanelLayoutStore.PanelID.GEO_POSITION_3D, panelTopLimit3D, panelTop3D, panelBottom3D,
                Modifier.zIndex(panels.zIndex(ScenePanel.GEO)),
                onDisplayedBounds = { panelFrames3D.geo = it }
            ) { maxH ->
                val calibrated3D = calibration?.isCalibrated == true
                GeoPositionPanel(
                    content = GeoPanelContent.make(ProjectSurface.SCENE_3D, calibrated3D,
                        options.showSatellite, satelliteCovered, satelliteStatus,
                        approximateLocation = locFeed3D.approximate),
                    showLocation = showLocation,
                    onShowLocation = { showLocation = it },
                    onToggleSatellite = {
                        SatelliteUi.onToolTap(satelliteCovered, options::toggleSatelliteByUser)
                            ?.let { SatelliteUi.toast(ctx3dSat, it) }
                    },
                    opacity = options.satelliteOpacity, onOpacity = { options.satelliteOpacity = it },
                    markerScale = options.gpsMarkerScale, onMarkerScale = { options.gpsMarkerScale = it },
                    statusText = {
                        val (t, warning) = GeoPanelContent.status(
                            calibration ?: com.minou.mvrviewer.mvr.GeoCalibration(), locFeed3D.denied)
                        t.text() to warning
                    },
                    // Depuis la 3D : le panneau se ferme, le plan s'ouvre prêt à poser
                    // (Pro vérifié par SceneScreen AVANT l'ouverture du plan).
                    onThreePoints = { onCalibrateThreePoint() },
                    onHereAnchor = { onCalibrateHere() },
                    onResetCalibration = { onResetCalibration() },
                    onClose = { onPanels(panels.close(ScenePanel.GEO)) },
                    maxContentHeight = maxH
                )
            }

            // ---- Décor 3D importé : panneau de placement + messages ----
            if (showModelsPanel && importedModels.isNotEmpty()) {
                // Bas-GAUCHE (le coin bas-droit revient à la pile Rechercher / Recadrer),
                // nouvelle clé de position (§3.4, §8 D13).
                MovablePanel(
                    id = PanelLayoutStore.PanelID.IMPORTED_MODELS_3D_LEADING,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 76.dp, start = 12.dp).width(240.dp),
                    onDisplayedBounds = { panelFrames3D.other = it }
                ) {
                ModelPlacementPanel(
                    models = importedModels,
                    selectedId = selectedModelId,
                    onSelect = { selectedModelId = it },
                    onRemove = { m ->
                        val rest = importedModels.filter { it.id != m.id }
                        if (selectedModelId == m.id) selectedModelId = rest.firstOrNull()?.id
                        onSetImportedModels(rest)
                        if (rest.isEmpty()) showModelsPanel = false
                    },
                    onAdd = { ProAccess.gate("importer un objet 3D") { showModelImportFormats = true } },
                    // Double piège : bump de version (sinon rien ne bouge en 3D) ET
                    // sauvegarde différée (sinon rien n'est persisté).
                    onChanged = onImportedModelsTouched,
                    onClose = { showModelsPanel = false }
                )
                }
            }
            if (showModelImportFormats) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showModelImportFormats = false },
                    title = { Text(stringResource(R.string.model_import_formats_title)) },
                    text = { Text(com.minou.mvrviewer.mvr.SceneModelLoader.SUPPORTED_SUMMARY) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showModelImportFormats = false
                            modelImportLauncher.launch(arrayOf("*/*"))
                        }) { Text(stringResource(R.string.model_import_formats_choose)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showModelImportFormats = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }
            // Chargement d'un modèle importé : état explicite, jamais un gel silencieux.
            // La lecture d'un plan importé a son indicateur au niveau de la scène
            // (SceneScreen, « Lecture du plan… »), commun aux deux vues.
            if (importingModel || modelBusy) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                ) {
                    Text(stringResource(R.string.model_loading),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            modelMessage?.let { msg ->
                Surface(
                    color = Color(0xFF5D4037), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp)
                        .clickable { modelMessage = null }
                ) {
                    Text(msg, color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }

            // ---- HABILLAGE (SPEC-menus-android §3.1–3.5) : rangée du haut, pastille
            // « Vue », bloc d'état, pile bas-droite, fine barre de chargement. ----
            val dens3D = androidx.compose.ui.platform.LocalDensity.current
            SceneTopRow(
                backDescription = stringResource(R.string.menu_close_project),
                onBack = onClose,
                rect = tools3D.firstOrNull { it.id == ToolId.RECT }?.takeIf { SceneTopRowRules.showsRect(toolbarLayout) },
                multi = tools3D.firstOrNull { it.id == ToolId.MULTI_SELECT }?.takeIf { SceneTopRowRules.showsMulti(toolbarLayout) },
                menu = {
                    MainMenuButton(
                        ctx = menuCtx3D, state = menuState3D,
                        values = MainMenuValues(
                            background = options.background3D,
                            backgroundPresets = bg3dPresets(),
                            onBackground = { options.background3D = it },
                            onCustomBackground = { showBackgroundDialog = true }
                        ),
                        onItem = { onMenuItem3D(it) }
                    )
                },
                onRowBottom = { topRow3DBottomPx = it },
                onLeading = { topRow3DBackPx = it },
                onTrailing = { topRow3DTrailingPx = it }
            )
            ViewPill(
                surface = ProjectSurface.SCENE_3D, current = viewpoint,
                onViewpoint = { applyViewpoint(it) }, onShowPlan = onShowPlan, onShow3D = {},
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 58.dp, end = 8.dp)
                    .onGloballyPositioned { viewPill3DPx = it.boundsInRoot() }
            )
            val loading3D = progress?.takeIf { it.active }
            StatusBlock(
                lines = StatusBlockRules.lines(
                    loading = loading3D != null, stepRes = loading3D?.step?.labelRes, percent = loading3D?.percent ?: 0,
                    placed = placedObjects, truncated = truncatedObjects,
                    hiddenClasses = hiddenClassCount, gdtfLoading = gdtfLoading
                ),
                syncBadge = syncBadge,
                // Borné par la pastille « Vue » mesurée : jamais dessous.
                maxWidth = with(dens3D) {
                    if (viewPill3DPx.width > 0f) ((viewPill3DPx.left - sceneBoxPx.left).toDp() - 24.dp).coerceAtLeast(96.dp) else null
                },
                modifier = Modifier.align(Alignment.TopStart).padding(top = 58.dp, start = 8.dp)
                    .onGloballyPositioned { statusBlock3DPx = it.boundsInRoot() }
            )
            // Fine barre DÉTERMINÉE pleine largeur, tout en haut : elle prolonge
            // visuellement celle de l'accueil (même pourcentage global).
            loading3D?.let { p ->
                LinearProgressIndicator(
                    progress = { p.percent / 100f },
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                )
            }
            // Pile bas-droite : Rechercher puis Recadrer ; elle monte au-dessus de la
            // pastille de sélection, du bandeau de mesure et de la mention IGN.
            SearchReframeStack(
                showReframe = SceneTopRowRules.showsReframe(toolbarLayout),
                onReframe = { reframe3D() },
                onSearch = { q -> query = q; doSearch3D() },
                obstacles = buildList {
                    if (selPresentation3D != PlanSelectionBar.NONE) add(selectionCard3DPx)
                    if (measureMode) add(measureBand3DPx)
                    // La mention à sa place AFFICHÉE (sa montée ne dépend pas de la pile).
                    if (satCredit3D != null) add(satMention3DPx.translate(0f, -satLift3D.value * satDens3D))
                },
                topLimitPx = maxOf(sceneBoxPx.top, topRow3DBottomPx)
            )

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
                    runCatching { rasterRoot.clearChildNodes() }
                    runCatching { importedRoot.clearChildNodes() }
                }
            }
        }

        // N11 — panneau « Personnaliser la barre d'outils » (vue 3D). Agit sur la
        // disposition de CETTE vue uniquement ; onLayoutChange remonte à SceneScreen
        // qui persiste globalement (par appareil).
        if (showCustomize) {
            ToolbarCustomizeSheet(
                title = stringResource(R.string.toolbar_title_3d),
                layout = toolbarLayout,
                catalog = tools3D.toCatalog(),
                onLayout = onLayoutChange,
                onReset = onLayoutReset,
                onDismiss = { showCustomize = false }
            )
        }

        // N11 — sélecteur de couleur du fond ouvert par l'outil BACKGROUND docké
        // (le menu garde ses presets ; ceci ouvre le sélecteur fin, comme iOS).
        if (showBackgroundDialog) {
            BackgroundColorDialog(
                title = stringResource(R.string.background_title_3d),
                initial = options.background3D,
                default = BackgroundColorStore.DEFAULT_3D,
                onColorChange = { options.background3D = it },
                onDismiss = { showBackgroundDialog = false }
            )
        }
    }
}

// ---- Résolution des références de géométrie ----

/** [layer] = calque MVR de l'objet d'origine — nécessaire au LOD par calque (#1). */
private class RenderRef(val world: Mat4, val fileName: String, val layer: String, val owner: Int = -1)
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
    // Projecteur PROPRIÉTAIRE de chaque réf (index dans scene.fixtures, -1 sinon) :
    // un type MA dont la 3D choisie est disponible REMPLACE la géométrie propre du
    // projecteur (SPEC-ma.md §4.6) — ses réfs doivent pouvoir être écartées.
    val fixtureIndex = java.util.IdentityHashMap<MvrSceneObject, Int>()
    scene.fixtures.forEachIndexed { i, f -> fixtureIndex[f] = i }
    var owner = -1
    // `layer` est propagé le long de la descente (y compris à travers les
    // Symdef) : le LOD par calque a besoin du calque MVR de l'objet d'origine.
    fun walk(refs: List<MvrGeometryRef>, parent: Mat4, depth: Int, layer: String) {
        if (depth > 8) return
        for (g in refs) when (g) {
            is MvrGeometryRef.File -> {
                val world = parent * drMat(g.transform.m)
                when {
                    g.fileName.endsWith(".3ds", ignoreCase = true) ->
                        tds.add(RenderRef(world, g.fileName, layer, owner))
                    g.fileName.endsWith(".glb", ignoreCase = true) ||
                        g.fileName.endsWith(".gltf", ignoreCase = true) ->
                        // glTF est Y-HAUT (le format l'impose), le monde MVR est
                        // Z-haut → Rx(+90°), EXACTEMENT comme le décodage des
                        // modèles GDTF (cf. `RX90 * scaleMat(s)` plus haut) et
                        // comme l'import de modèles (`if (m.yUp) RX90`). Sans
                        // cette rotation, chaque pièce du décor tournait de 90°
                        // sur elle-même : les arches devenaient un gril à plat et
                        // les praticables des panneaux DEBOUT — d'où le « plancher
                        // manquant » (on voyait la sous-structure au travers).
                        glb.add(RenderRef(world * GLB_SCALE * RX90, g.fileName, layer, owner))
                }
            }
            is MvrGeometryRef.Symbol -> {
                val sd = scene.symdefs[g.symdefUuid] ?: continue
                walk(sd.items.take(MAX_SYMDEF_ITEMS), parent * drMat(g.transform.m), depth + 1, layer)
            }
        }
    }
    for (obj in scene.allObjects) {
        owner = fixtureIndex[obj] ?: -1
        walk(obj.geometryRefs, conv * drMat(obj.transform.m), 0, obj.layerName)
    }
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

/**
 * Parité iOS (GLBFastDecoder, « les arbres Vectorworks ne sont plus des croix
 * grises ») : les matériaux à OPACITÉ (alphaMode MASK/BLEND — feuillages
 * découpés, vitrages) sont rendus DES DEUX CÔTÉS. Filament honore déjà
 * `alphaMode` et les images embarquées ; mais un plan de feuillage vu de dos est
 * ÉLIMINÉ (culling) si l'export n'a pas posé `doubleSided`, et un arbre en deux
 * quads croisés perd alors la moitié de ses faces selon l'angle. Les matériaux
 * opaques ne sont pas touchés. Chaque instance gltfio a ses propres
 * MaterialInstance → on les parcourt toutes.
 */
private fun forceDoubleSidedTransparency(instances: List<com.google.android.filament.gltfio.FilamentInstance>) {
    for (inst in instances) {
        val mis = runCatching { inst.materialInstances }.getOrNull() ?: continue
        for (mi in mis) {
            val mode = runCatching { mi.material.blendingMode }.getOrNull() ?: continue
            if (mode == com.google.android.filament.Material.BlendingMode.OPAQUE) continue
            runCatching { mi.setDoubleSided(true) }
            runCatching { mi.setCullingMode(com.google.android.filament.Material.CullingMode.NONE) }
        }
    }
}

/**
 * Géométrie uploadée + un matériau par sous-mesh : `colors` (couleur diffuse) et
 * `textures` (nom de l'image .3ds, ou null) sont PARALLÈLES. Par défaut aucune
 * texture (modèles GDTF .3ds : leurs images ne sont pas dans le MVR) → même rendu
 * couleur qu'avant.
 */
private class BuiltMesh(
    val geometry: Geometry,
    val colors: List<Int>,
    val triangles: Int,
    val textures: List<String?> = List(colors.size) { null }
)

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
    val triangles: Int,
    /** Nom de fichier image (0xA300) par sous-mesh, parallèle à `colors` ; null sans texture. */
    val textures: List<String?>
)

private fun MeshData.toGeometry(engine: Engine): Geometry =
    Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
        .vertices(verts).primitivesIndices(prims).build(engine)

/**
 * Image de texture .3ds décodée (hors thread principal) : Bitmap ARGB_8888 +
 * « au moins un pixel d'alpha < 255 » (→ matériau transparent, double face).
 */
private class DecodedTexture(val bitmap: android.graphics.Bitmap, val hasTransparency: Boolean)

/** Cache de préparation CPU d'une scène (survit aux bascules 3D↔plan). */
private class Prepared3DHolder {
    var refs: RenderRefs? = null
    var meshDataByFile: Map<String, List<MeshData>>? = null
    var maxDimByFile: Map<String, Float>? = null
    var imageBytes: Map<String, ByteArray>? = null
    /** Textures .3ds décodées, clé = nom de fichier tel qu'écrit dans le .3ds (0xA300). */
    var tdsTextures: Map<String, DecodedTexture>? = null
    val ready: Boolean get() = refs != null
}

// Bornes du décodage des textures .3ds (un MVR Vectorworks en a ~15 ; le cache
// de prep les garde en mémoire, et chaque Bitmap devient une texture GPU
// mipmappée) : budget TOTAL en pixels — ARGB_8888 = 4 o/px, donc 48 Mpx ≈
// 192 Mio de Bitmap — plutôt qu'un simple nombre d'images (32 images de 2048²
// feraient 512 Mio). Une image est d'abord bornée à MAX_TDS_TEXTURE_SIDE (une
// toile de fond 3400 px n'a pas besoin de plus de 2048 px), puis sous-
// échantillonnée (puissances de 2) tant que le budget restant ne la contient
// pas ; en dessous de MIN_TDS_TEXTURE_SIDE ou budget épuisé, elle est sautée
// (repli couleur diffuse).
private const val MAX_TDS_TEXTURE_PIXELS = 48_000_000L
private const val MAX_TDS_TEXTURE_SIDE = 2048
private const val MIN_TDS_TEXTURE_SIDE = 64

/**
 * Décode les images référencées par les matériaux .3ds (noms 0xA300) depuis les
 * images du MVR — résolution tolérante nom de base/casse via GlbImageIndex —
 * en Bitmap ARGB_8888, et détecte la transparence (alpha < 255, balayage par
 * ligne pour ne pas allouer toute l'image en IntArray). PUR CPU, hors thread
 * principal ; une image illisible est simplement absente du résultat (le
 * sous-mesh retombe sur sa couleur diffuse).
 *
 * Une entrée zip n'est décodée qu'UNE fois quelle que soit l'orthographe du nom
 * dans les .3ds (`00011.png` / `00011.PNG` / `tex\00011.png` → même clé
 * canonique, même [DecodedTexture] partagé → une seule texture GPU en aval).
 * Parcours en ordre trié : le budget tombe toujours sur les mêmes images.
 */
private fun decodeTdsTextures(names: Set<String>, images: Map<String, ByteArray>): Map<String, DecodedTexture> {
    if (names.isEmpty() || images.isEmpty()) return emptyMap()
    val index = com.minou.mvrviewer.mvr.GlbImageIndex(images)
    val out = HashMap<String, DecodedTexture>(names.size)
    val byKey = HashMap<String, DecodedTexture?>()
    var remaining = MAX_TDS_TEXTURE_PIXELS
    for (name in names.sorted()) {
        val key = index.canonicalKey(name) ?: continue
        if (byKey.containsKey(key)) {
            byKey[key]?.let { out[name] = it }
            continue
        }
        val bytes = images[key] ?: continue
        val decoded = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth; val h = bounds.outHeight
            if (w <= 0 || h <= 0) return@runCatching null
            var sample = 1
            while (maxOf(w, h) / sample > MAX_TDS_TEXTURE_SIDE) sample *= 2
            fun pixels() = (w.toLong() / sample) * (h.toLong() / sample)
            while (pixels() > remaining && maxOf(w, h) / sample > MIN_TDS_TEXTURE_SIDE) sample *= 2
            if (pixels() > remaining) return@runCatching null
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return@runCatching null
            if (bmp.width <= 0 || bmp.height <= 0) return@runCatching null
            DecodedTexture(bmp, bitmapHasTransparency(bmp))
        }.getOrNull()
        byKey[key] = decoded
        if (decoded != null) {
            out[name] = decoded
            remaining -= decoded.bitmap.width.toLong() * decoded.bitmap.height
        }
    }
    return out
}

/** Vrai dès qu'un pixel a un alpha < 255 (balayage ligne par ligne, arrêt au premier). */
private fun bitmapHasTransparency(bmp: android.graphics.Bitmap): Boolean {
    if (!bmp.hasAlpha()) return false
    val w = bmp.width; val h = bmp.height
    val row = IntArray(w)
    for (y in 0 until h) {
        bmp.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) if ((row[x] ushr 24) != 0xFF) return true
    }
    return false
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
    // Coordonnées de texture (0x4140) : seulement si le fichier en a exactement
    // une par sommet ; sinon aucun UV n'est émis et le mesh reste en couleur
    // (voir `textureOf` : sans UV, un matériau texturé n'étirerait qu'un texel).
    // CONVENTION : le .3ds a l'origine UV EN BAS À GAUCHE (v = 0 au sol). Les
    // matériaux SceneView (opaque_textured / transparent_textured) sont compilés
    // avec flipUV=true : leur vertex shader fait DÉJÀ `1.0 - mesh_uv0.y`
    // (vérifié dans les .filamat de l'AAR) → V est passé TEL QUEL. Un second
    // retournement ici mettrait les arbres tête en bas.
    // Échelle/décalage UV du matériau (0xA354…, u' = u·s + o, sampler REPEAT)
    // appliqués ici, sur les sommets, quand toutes les cartes du mesh partagent
    // la même transformée (cas courant : un seul matériau texturé). Des cartes
    // aux transformées différentes sur un même mesh (UV partagées) ne sont pas
    // départagées : transformée identité.
    val uvs = mesh.uvs?.takeIf { it.size == 2 * vc }
    val maps = mesh.materialTextures.values
    val uvXf = maps.firstOrNull()?.takeIf { f ->
        maps.all { it.uScale == f.uScale && it.vScale == f.vScale && it.uOffset == f.uOffset && it.vOffset == f.vOffset }
    }
    val uSc = uvXf?.uScale ?: 1f; val vSc = uvXf?.vScale ?: 1f
    val uOf = uvXf?.uOffset ?: 0f; val vOf = uvXf?.vOffset ?: 0f
    val verts = ArrayList<Geometry.Vertex>(vc)
    for (v in 0 until vc) {
        val len = sqrt(nx[v] * nx[v] + ny[v] * ny[v] + nz[v] * nz[v])
        val n = if (len > 1e-6f) Float3(nx[v] / len, ny[v] / len, nz[v] / len) else Float3(0f, 0f, 1f)
        val pos = Float3(vs[v * 3], vs[v * 3 + 1], vs[v * 3 + 2])
        if (uvs != null) {
            val uv = Float2(uvs[v * 2] * uSc + uOf, uvs[v * 2 + 1] * vSc + vOf)
            verts.add(Geometry.Vertex(position = pos, normal = n, uvCoordinate = uv))
        } else {
            verts.add(Geometry.Vertex(position = pos, normal = n))
        }
    }
    // Texture d'un groupe matériau : seulement avec des UV cohérents (sans UV, un
    // matériau texturé n'aurait qu'un seul texel étiré → on garde la couleur).
    fun textureOf(materialName: String?): String? =
        if (uvs == null || materialName == null) null else mesh.materialTextures[materialName]?.fileName
    // Faces sans groupe / restantes : la texture de l'UNIQUE matériau du fichier
    // s'il n'y en a qu'un (comme la couleur), sinon aucune.
    val soleMaterial = if (mesh.materials.size == 1) mesh.materials.keys.first() else null

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
    val textures = ArrayList<String?>()
    if (mesh.materialGroups.isEmpty()) {
        val all = allValidTris()
        if (all.isNotEmpty()) {
            prims.add(all)
            colors.add(if (mesh.materials.size == 1) mesh.materials.values.first().toColorInt() else GRAY)
            textures.add(textureOf(soleMaterial))
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
                textures.add(textureOf(g.name))
            }
        }
        val leftover = ArrayList<Int>()
        for (t in 0 until tc) if (!assigned[t] && okTri(t)) emit(leftover, t)
        if (leftover.isNotEmpty()) { prims.add(leftover); colors.add(GRAY); textures.add(textureOf(soleMaterial)) }
    }
    if (prims.isEmpty()) return null
    return MeshData(verts, prims, colors, tc, textures)
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

/** Identité (col-majeur) — repli quand aucune rotation d'axe n'est nécessaire. */
private val IDENTITY4 = Mat4(
    Float4(1f, 0f, 0f, 0f), Float4(0f, 1f, 0f, 0f),
    Float4(0f, 0f, 1f, 0f), Float4(0f, 0f, 0f, 1f)
)

/** Translation (col-majeur) — en mm dans le repère MONDE MVR. */
private fun translateMat(x: Float, y: Float, z: Float): Mat4 = Mat4(
    Float4(1f, 0f, 0f, 0f), Float4(0f, 1f, 0f, 0f),
    Float4(0f, 0f, 1f, 0f), Float4(x, y, z, 1f)
)

/** Rotation autour de la VERTICALE du monde MVR (axe Z), col-majeur. */
private fun rotZMat(deg: Double): Mat4 {
    val r = Math.toRadians(deg)
    val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
    return Mat4(
        Float4(c, s, 0f, 0f), Float4(-s, c, 0f, 0f),
        Float4(0f, 0f, 1f, 0f), Float4(0f, 0f, 0f, 1f)
    )
}

/** Bascule autour de l'HORIZONTALE du monde MVR (axe X — le « tilt »), col-majeur. */
private fun rotXMat(deg: Double): Mat4 {
    val r = Math.toRadians(deg)
    val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
    return Mat4(
        Float4(1f, 0f, 0f, 0f), Float4(0f, c, s, 0f),
        Float4(0f, -s, c, 0f), Float4(0f, 0f, 0f, 1f)
    )
}

/**
 * Buffers plats d'un [com.minou.mvrviewer.mvr.ModelMesh] → sommets Filament +
 * indices. PUR CPU (aucun appel Engine) : à exécuter hors thread principal, un
 * maillage à la fois, pour que le pic mémoire reste borné.
 */
private fun meshVertices(m: com.minou.mvrviewer.mvr.ModelMesh): Pair<List<Geometry.Vertex>, List<Int>> {
    val vc = m.vertexCount
    val verts = ArrayList<Geometry.Vertex>(vc)
    for (v in 0 until vc) {
        verts.add(
            Geometry.Vertex(
                position = Float3(m.verts[v * 3], m.verts[v * 3 + 1], m.verts[v * 3 + 2]),
                normal = Float3(m.normals[v * 3], m.normals[v * 3 + 1], m.normals[v * 3 + 2])
            )
        )
    }
    return verts to m.indices.toList()
}

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
     * TRANSLATION MONDE (m) ajoutée à la caméra — « projecteur gardé visible » sous
     * la fiche compacte (SPEC-menus-android §3.9). Une translation pure de la
     * caméra équivaut à translater tout le rig (pivot compris) : orbite, pan et
     * zoom continuent autour du pivot DÉCALÉ d'autant. Lue à chaque image par
     * SceneView ([getTransform]) ; un nouveau manipulateur (point de vue, Recadrer,
     * recherche) repart de zéro.
     */
    var shift: Float3 = Float3(0f, 0f, 0f)

    /** Compteur de gestes caméra (doigt posé, pincement) : un geste interrompt le glissement. */
    var gestures: Int = 0
        private set

    override fun getTransform(): dev.romainguy.kotlin.math.Mat4 {
        val t = inner.getTransform()
        val s = shift
        if (s.x == 0f && s.y == 0f && s.z == 0f) return t
        return dev.romainguy.kotlin.math.Mat4(
            t.x, t.y, t.z,
            dev.romainguy.kotlin.math.Float4(t.w.x + s.x, t.w.y + s.y, t.w.z + s.z, t.w.w)
        )
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        gestures++
        inner.scrollBegin(x, y, separation)
    }

    /**
     * N1 — Remappage des gestes : 1 DOIGT = déplacement (pan), 2 DOIGTS = orbite.
     * Le détecteur SceneView appelle `grabBegin(strafe=false)` pour 1 doigt (orbite
     * par défaut Filament) et `grabBegin(strafe=true)` pour 2 doigts en translation
     * (pan). On INVERSE le drapeau `strafe` : 1 doigt devient pan, 2 doigts orbite.
     * Le mode reste ORBIT (il honore `strafe`), et le pincement/zoom (scrollUpdate)
     * n'est pas touché → zoom conservé.
     */
    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        gestures++
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
 * au projecteur. `sizeScale` = options.labelSize (0.7 / 1.0 / 1.4) ;
 * `offsetScale` = options.labelOffset — il était IGNORÉ (gap figé), le
 * réglage « écart au projecteur » n'avait donc aucun effet en 3D.
 */
private fun DrawScope.drawFixtureLabel(
    measurer: TextMeasurer,
    lines: List<String>,
    anchor: Offset,
    sizeScale: Float,
    offsetScale: Float = 1f
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
    val gap = 12f * sizeScale * offsetScale
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
