package com.minou.mvrviewer.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.offset as layoutOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.MvrSceneObject
import com.minou.mvrviewer.mvr.Vec2
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

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
    /**
     * PLAN IMPORTÉ (SPEC-menus-android §3.7), hissé dans SceneScreen : la
     * transformée est mutée EN PLACE, [refPlanTransformVersion] (commune aux deux
     * vues) dit quand redessiner ; le panneau l'incrémente par [onRefPlanTouched]
     * (SceneScreen persiste, débouncé, et partage). [onImportRefPlan] lance
     * l'import (Pro, garde dans SceneScreen) ; [importingRefPlan] = lecture en cours.
     */
    refPlanTransformVersion: Int = 0,
    onImportRefPlan: () -> Unit = {},
    importingRefPlan: Boolean = false,
    onRefPlanTouched: () -> Unit = {},
    onRemoveRefPlan: () -> Unit = {},
    /** Panneaux flottants ouverts (Position GPS, Plan importé, Réglage des étiquettes). */
    panels: ScenePanels = ScenePanels(),
    onPanels: (ScenePanels) -> Unit = {},
    /** « Réinitialiser la calibration » (Pro, garde dans SceneScreen). */
    onResetCalibration: () -> Unit = {},
    calibration: com.minou.mvrviewer.mvr.GeoCalibration = remember { com.minou.mvrviewer.mvr.GeoCalibration() },
    projectKey: String? = null,
    satellite: com.minou.mvrviewer.mvr.SatelliteOverlay? = null,
    /** Statut du fond satellite (ligne d'état du menu). */
    satelliteStatus: SatelliteStatus = SatelliteStatus.Idle,
    /** 1re ancre dans les emprises IGN (France) : sinon, réglage désactivé. */
    satelliteCovered: Boolean = true,
    onCalibrationChanged: () -> Unit = {},
    hiddenLayers: Set<String> = emptySet(),
    // ---- Rangement des menus, tranche C (SPEC-menus-android §3.1–3.4) ----
    /**
     * Entrées du menu ⋯ que le plan ne traite pas lui-même (destinations, import du
     * plan, PDF de câblage, fermeture…) : transmises à SceneScreen.
     */
    onMainMenu: (MainMenuItemId) -> Unit = {},
    /** Synchro branchée (section « Équipe » du menu). */
    hasSync: Boolean = false,
    /** Un câblage existe (section « PDF de câblage » du menu). */
    hasCabling: Boolean = false,
    /** Pastille de synchro (projet partagé), à droite sous la rangée du haut ; null = aucune. */
    syncBadge: (@Composable () -> Unit)? = null,
    /**
     * Action à lancer à l'APPARITION du plan (export PDF, calibration 3 points,
     * « je suis ici ») — SPEC-menus-android §4.2. [takeStartAction] la rend UNE
     * fois (puis NONE, cf. [PlanStartRequest]) ; [startTick] change à chaque
     * demande, pour une demande faite alors que le plan est déjà affiché.
     */
    startTick: Int = 0,
    takeStartAction: () -> PlanStartAction = { PlanStartAction.NONE },
    onToggleLayer: (String) -> Unit = {},
    /**
     * Éléments MVR masqués, par IDENTITÉ D'INSTANCE (cf. mvrInstanceKey) —
     * jamais par type de géométrie : masquer un pont ne doit pas faire
     * disparaître tous les ponts du même modèle ailleurs dans le show.
     * Hissé dans SceneScreen pour survivre aux bascules 3D ↔ plan.
     */
    hiddenElements: Set<String> = emptySet(),
    onSetHiddenElements: (Set<String>) -> Unit = {},
    /**
     * Ensemble SOLO — l'INVERSE exact du masquage. Le masquage tient les éléments
     * CACHÉS et le dessin les saute ; le solo tient les éléments À MONTRER SEULS
     * et le dessin ne montre QUE ceux-là (fixtures ET décor), tout le reste
     * disparaît. Même identité d'instance (mvrInstanceKey) que hiddenElements, si
     * bien qu'un même tap ou un même cadre de sélection les alimente tous les deux.
     * VIDE = aucun filtre solo (on montre tout) : un solo vide ne doit jamais
     * donner un plan noir déroutant — le mode ne « prend » qu'une fois une
     * sélection faite. Hissé dans SceneScreen pour survivre aux bascules 3D ↔ plan.
     */
    soloElements: Set<String> = emptySet(),
    onSetSoloElements: (Set<String>) -> Unit = {},
    gdtfOverrides: GdtfOverrides? = null,
    /**
     * Overrides de patch — sert au NOM d'AFFICHAGE effectif (renommage) : étiquettes
     * 2D (champ NAME via PlanFixture.name), recherche par nom et fiche de sélection.
     * Défaut vide = noms bruts du .mvr (aucun changement pour qui ne renomme pas).
     */
    overrides: PatchOverrides = remember { PatchOverrides() },
    /**
     * États de câblage (phase 4) : servent la COLORATION du plan par distributeur
     * et les champs d'étiquette SOCAPEX / DMX_LINE. Réactifs via `.version`. Vides
     * par défaut (aucun câblage → mode Calque seul, sélecteur masqué).
     */
    cabling: PowerCablingState = remember { PowerCablingState() },
    dmxCabling: DmxCablingState = remember { DmxCablingState() },
    /**
     * CIBLE D'AFFECTATION « sur le plan » (E2) : non nulle → la vue plan est en MODE
     * AFFECTATION vers ce circuit / départ. Taper (ou encadrer) un projecteur
     * l'affecte/le retire. Hissée dans SceneScreen (PlanScreen est recréé à chaque
     * bascule CABLING ↔ PLAN, l'état doit survivre). [onAssignDone] = « Terminé »
     * (quitte le mode, revient au câblage).
     */
    assignTarget: CablingAssignTarget? = null,
    onAssignDone: () -> Unit = {},
    /** ◀ ▶ de la barre : voie précédente (−1) / suivante (+1), même famille (SceneScreen). */
    onStepAssign: (Int) -> Unit = {},
    /** Croix de la barre : quitte le mode affectation et RESTE sur le plan. */
    onExitAssign: () -> Unit = {},
    /**
     * Conso par projecteur pour la charge de la barre d'affectation — MÊMES
     * sources que l'écran Câblage (bibliothèque de puissances, spec de géométrie
     * des types custom). null = charge électrique jamais résolue (sablier).
     */
    power: PowerLibraryState? = null,
    customResolver: CustomFixtureResolver = remember { CustomFixtureResolver() },
    /** « Vue 3D » de la pastille (sort aussi d'une affectation, sans rouvrir le Câblage) ; défaut = [onBack]. */
    onShow3D: (() -> Unit)? = null,
    // N12 — APPUI LONG sur un projecteur → ouvre la fiche d'édition de la patch
    // (mêmes champs que la liste de patch). Le TAP simple garde son comportement
    // (sélection). Ne se déclenche qu'en interaction normale (pas rectangle/
    // affectation/masquage/solo/mesure/calibrage).
    onEditFixture: (MvrSceneObject) -> Unit = {},
    // N11 — disposition des barres d'outils ancrables (vue plan). Nommé
    // `toolbarLayout` (cohérence avec Scene3DScreen). Défaut = barre bas-gauche
    // actuelle → aucun changement pour qui ne personnalise pas.
    toolbarLayout: ToolbarLayout = ToolbarLayout.EMPTY,
    onLayoutChange: (ToolbarLayout) -> Unit = {},
    /** « Réinitialiser » du panneau de personnalisation : défaut de l'appareil. */
    onLayoutReset: () -> Unit = {},
    onBack: () -> Unit,
    /** Sélection courante (clés d'INSTANCE) remontée à la scène — « affecter à la sélection ». */
    onSelectionChanged: (List<String>) -> Unit = {},
    // FICHE DE SÉLECTION et BANNIÈRE MULTI (SPEC-menus-android §3.9, tranche D) :
    // construites par SceneScreen, les MÊMES qu'en 3D. null = rien en bas.
    selectionCard: SelectionCardHost? = null,
    // Crochet DEBUG `MVR_AUTO_SELECT=<N°>` : pris une fois, à la scène prête.
    takeAutoSelect: () -> String? = { null },
    modifier: Modifier = Modifier
) {
    val ctxPlan = androidx.compose.ui.platform.LocalContext.current
    // Vue hôte pour le RETOUR HAPTIQUE du mode affectation câblage (E2) : chaque tap
    // qui affecte/retire un projecteur déclenche un retour court, différencié.
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val layerIndex = remember(scene) { LayerColors.index(scene) }
    // Re-clé sur `overrides.version` : PlanFixture.name porte le NOM EFFECTIF, donc
    // un renommage doit reconstruire `data` (étiquettes/recherche/fiche à jour).
    val data = remember(scene, overrides.version) { planData(scene, overrides) }
    // N12 — appui long → fiche d'édition patch : la PlanFixture ne porte qu'une clé
    // d'instance (mvrInstanceKey) ; on retrouve l'objet MVR réel par cette clé
    // (même identité que le masquage/patch partout ailleurs).
    val fixtureByKey = remember(scene) { scene.fixtures.associateBy { mvrInstanceKey(it) } }

    // ---- CÂBLAGE (phase 4) : coloration du plan + champs d'étiquette ----------
    // Tables FIGÉES (immuables) fixtureKey(mvrUUID) → couleur / texte du
    // distributeur, recalculées sur `.version`. Figées = réutilisables telles
    // quelles pour l'écran (réactif : le read de `.version` en clé de remember
    // redéclenche le dessin) ET pour le snapshot PDF (passé hors thread principal).
    // Un projecteur non affecté est ABSENT des tables → gris neutre / champ vide.
    val socaColorByFixture = remember(cabling.version) {
        val byId = cabling.distributors.associateBy { it.id }
        cabling.assignments.values.mapNotNull { a ->
            byId[a.distributor]?.let { d -> a.fixture to Color(d.colorArgb) }
        }.toMap()
    }
    val dmxColorByFixture = remember(dmxCabling.version) {
        val byId = dmxCabling.distributors.associateBy { it.id }
        dmxCabling.assignments.values.mapNotNull { a ->
            byId[a.distributor]?.let { d -> a.fixture to Color(d.colorArgb) }
        }.toMap()
    }
    val socaLabelByFixture = remember(cabling.version) {
        val byId = cabling.distributors.associateBy { it.id }
        cabling.assignments.values.mapNotNull { a ->
            // Convention canonique centralisée (CablingLabels) : « nom · C<circuit> ».
            byId[a.distributor]?.let { d -> a.fixture to CablingLabels.soca(d.name, a.circuit) }
        }.toMap()
    }
    val dmxLabelByFixture = remember(dmxCabling.version) {
        val byId = dmxCabling.distributors.associateBy { it.id }
        dmxCabling.assignments.values.mapNotNull { a ->
            // Convention canonique centralisée (CablingLabels) : « nom · D<cœur> »
            // (base seule si la ligne n'a qu'un départ, GATE sur coreCount).
            byId[a.distributor]?.let { d -> a.fixture to CablingLabels.dmx(d.name, d.coreCount, a.core) }
        }.toMap()
    }
    // Résolveur d'étiquette SOCAPEX / DMX_LINE : pur (ferme sur les tables figées),
    // donc sûr à passer au dessin ET au snapshot PDF (thread de fond).
    val cablingText = remember(socaLabelByFixture, dmxLabelByFixture) {
        { f: PlanFixture, c: LabelContent ->
            when (c) {
                LabelContent.SOCAPEX -> socaLabelByFixture[f.key]
                LabelContent.DMX_LINE -> dmxLabelByFixture[f.key]
                else -> null
            }
        }
    }
    val hasSocaColoring = socaColorByFixture.isNotEmpty()
    val hasDmxColoring = dmxColorByFixture.isNotEmpty()
    // Mode EFFECTIF : un mode dont le câblage a disparu (ou vide) retombe sur le
    // calque — aligné iOS (appearanceFor → couleur de calque si la carte est vide).
    // MODE AFFECTATION (SPEC-cablage-plan §3) : le plan est coloré D'OFFICE selon
    // la famille de la voie (Socapex / Ligne DMX), quel que soit le choix du menu
    // — un projecteur non câblé est gris, même si aucun ne l'est encore. En
    // sortant du mode, le choix du menu revient.
    val effectiveColorMode = CablingAssignColoring.effectiveMode(
        options.planColorMode, assignTarget?.kind, hasSocaColoring, hasDmxColoring)
    val cablingColorMap: Map<String, Color>? = when (effectiveColorMode) {
        PlanColorMode.SOCAPEX -> socaColorByFixture
        PlanColorMode.DMX_LINE -> dmxColorByFixture
        PlanColorMode.LAYER -> null
    }
    // ANNEAU 2e DIMENSION (E3) : l'AUTRE table de couleur que le remplissage —
    // Socapex → anneau DMX, DMX → anneau Socapex. Projecteur absent de la table
    // (non affecté dans l'autre dimension) → pas d'anneau ; aucun en mode Calque.
    // PAS d'anneau de 2e dimension pendant une affectation (une seule lecture).
    val cablingRingColorMap: Map<String, Color>? =
        if (!CablingAssignColoring.showsSecondRing(effectiveColorMode, assignTarget != null)) null
        else when (effectiveColorMode) {
            PlanColorMode.SOCAPEX -> dmxColorByFixture
            PlanColorMode.DMX_LINE -> socaColorByFixture
            PlanColorMode.LAYER -> null
        }
    // Légende câblage : distributeurs RÉELLEMENT utilisés (au moins une affectation)
    // dans le mode courant, dans l'ordre du modèle. Vide hors mode câblage.
    // Spec canonique — légende : UNIQUEMENT les distributeurs ayant au moins une
    // affectation dans le mode courant, dans l'ordre du modèle. PUIS, si et seulement
    // si au moins un projecteur PRÉSENT dans la scène est non affecté dans ce mode
    // (absent de la table de couleur du mode = dessiné en gris non-câblé), on AJOUTE
    // en fin une entrée « Non câblé » de teinte CABLING_UNASSIGNED_GRAY (0xFF737373).
    // SECTION ABSENTE si aucun distributeur de la famille n'est utilisé (iOS) :
    // jamais une légende réduite à « Non câblé » — la légende des calques reste.
    val sUnassigned = stringResource(R.string.cabling_unassigned)
    val sUnnamed = stringResource(R.string.common_unnamed)
    val cablingLegendSection: CablingLegendSection? =
        remember(cabling.version, dmxCabling.version, effectiveColorMode,
                 data, socaColorByFixture, dmxColorByFixture) {
            val modeMap = when (effectiveColorMode) {
                PlanColorMode.SOCAPEX -> socaColorByFixture
                PlanColorMode.DMX_LINE -> dmxColorByFixture
                PlanColorMode.LAYER -> null
            }
            val anyUnassigned = modeMap != null && data.fixtures.any { it.key !in modeMap }
            CablingLegendUi.section(effectiveColorMode, cabling.toDTO(), dmxCabling.toDTO(), anyUnassigned)
        }
    // Lignes « nom → couleur » (écran ET PDF) : nom vide → « (sans nom) ».
    val cablingLegend: List<Pair<String, Color>> =
        remember(cablingLegendSection, sUnassigned, sUnnamed) {
            val s = cablingLegendSection ?: return@remember emptyList()
            val rows = s.entries.map { CablingLegendUi.displayName(it.name, sUnnamed) to Color(it.argb) }
            if (s.showUnassigned) rows + (sUnassigned to CABLING_UNASSIGNED_GRAY) else rows
        }

    val measurer = rememberTextMeasurer()
    // Étiquettes déjà mesurées (le cache interne du TextMeasurer ne retient que
    // 8 entrées → sans ça, chaque étiquette est re-mise en page à chaque frame).
    val labelDensity = androidx.compose.ui.platform.LocalDensity.current
    val labelCache = remember(
        scene, options.labelSize, options.background2D,
        options.labelFields, options.labelDetached,
        labelDensity.density, labelDensity.fontScale
    ) { HashMap<String, androidx.compose.ui.text.TextLayoutResult>() }

    // Décalage MANUEL d'une étiquette, propre à un projecteur (clé d'instance),
    // en pixels écran : il s'ajoute au décalage global `options.labelOffset`.
    // En pixels et non en mm monde parce que le geste corrige un CHEVAUCHEMENT
    // à l'écran — la correction doit garder la même allure quel que soit le zoom.
    //
    // Ni la table ni la liste des zones sensibles ne sont des états observables :
    // pendant le glissé on n'écrit QUE `labelDragVersion`, lu uniquement dans le
    // lambda de dessin → redessin seul, jamais de recomposition de PlanScreen à
    // la fréquence du doigt (régression déjà vue sur le pan/zoom).
    val labelShift = remember(scene) { HashMap<String, Offset>() }
    // La relecture disque des décalages est ASYNCHRONE : tant qu'elle n'a pas
    // abouti, `labelShift` est incomplet et l'enregistrer EFFACERAIT du disque
    // tout ce qui n'a pas encore été relu (la sauvegarde réécrit la table
    // entière). Ce drapeau — un tableau muet, pas un état observable — dit que
    // la table fait autorité. Voir aussi la fusion sans écrasement plus bas.
    val labelsLoaded = remember(scene) { booleanArrayOf(projectKey == null) }
    val labelHits = remember(scene) { ArrayList<LabelHit>() }
    // Disques des symboles de projecteurs RÉELLEMENT dessinés, même tampon muet,
    // même passe : ils portent l'exception d'arbitrage (cf. labelTapTarget). Sans
    // eux, une pastille posée sur le centre d'un projecteur le rendait
    // définitivement insélectionnable.
    val symbolHits = remember(scene) { ArrayList<SymbolHit>() }
    var labelDragVersion by remember(scene) { mutableIntStateOf(0) }

    // MODÈLE D'INTERACTION DES ÉTIQUETTES — décidé après trois vagues de
    // régressions dues à la détection « à la volée » pendant le glissé :
    //   1. par défaut AUCUNE étiquette n'est active → un glissé pane/zoome le
    //      plan, TOUJOURS, sans le moindre test d'accrochage ;
    //   2. un TAP sur une étiquette l'active (retour visuel net) ; seules les
    //      étiquettes armées peuvent être saisies. Si le projecteur touché fait
    //      partie d'une MULTI-SÉLECTION, le même bloc est armé sur TOUS les
    //      projecteurs sélectionnés : un seul glissé les déplace alors du même
    //      vecteur (demande terrain « bouger toutes les étiquettes d'un coup ») ;
    //   3. un glissé qui démarre AILLEURS pane et désactive ;
    //   4. un tap ailleurs, ou sur la même étiquette, désactive ;
    //   5. ARBITRAGE avec la sélection de projecteur : l'étiquette ne gagne le
    //      tap que si le doigt est STRICTEMENT DANS sa pastille dessinée, sans
    //      marge (cf. labelBoxAt) ; sinon le tap va au projecteur. Et un tap
    //      d'étiquette ne modifie JAMAIS `selected` (l'ordre de sélection pilote
    //      l'adressage DMX séquentiel).
    // C'est un état observable (et non un tableau muet) parce qu'il ne change
    // que deux fois par interaction — jamais à la fréquence du doigt — et qu'il
    // doit re-clé le pointerInput de saisie pour que celui-ci n'existe même pas
    // tant qu'aucune étiquette n'est active.
    // (Un ENSEMBLE de clés de bloc depuis la sélection groupée : le glissé les
    // déplace toutes ensemble. Vide = aucune étiquette armée, cas par défaut.)
    var activeLabelKeys by remember(scene) { mutableStateOf<Set<String>>(emptySet()) }
    // Marge d'accrochage convertie une fois : le tap comme le glissé s'en
    // servent, et `toPx()` a besoin d'une densité (indisponible dans le lambda
    // de tap, qui n'est pas un scope Density).
    val labelSlackPx = with(labelDensity) { LABEL_TOUCH_SLACK.toPx() }
    // Rayon d'accrochage de l'outil de mesure, en dp (donc en millimètres
    // d'écran réels, quelle que soit la densité) : au-delà, l'accrochage
    // deviendrait une devinette.
    val snapRadiusPx = with(labelDensity) { MEASURE_SNAP_RADIUS.toPx() }
    // (La désactivation automatique — étiquettes éteintes, mode cadre/masquage/
    // calibrage — est déclarée plus bas, après ces états : on ne peut pas les
    // lire avant leur déclaration.)

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

    // Plan de repère : l'IMPORT, le centrage, la persistance débouncée et le partage
    // du placement vivent dans SceneScreen (SPEC-menus-android §3.7, appelables aussi
    // depuis la 3D). La transformée est mutée EN PLACE : la version commune
    // (refPlanTransformVersion) est lue au dessin pour le redessiner.

    // ---- Tracés PRÉ-CONSTRUITS (cf. PlanPathCache) ----
    // Tout ce qui ne dépend pas du zoom/pan est fabriqué UNE fois, hors thread
    // principal, en coordonnées plan (ou locales pour le DXF). Le dessin n'est
    // plus qu'une matrice appliquée au Canvas : plus aucun calcul par sommet à
    // 60 Hz. C'était la cause n°1 des saccades au pan/zoom.
    // Univers de TOUTES les clés d'éléments dessinables, exactement dans les mêmes
    // espaces de clés que ceux testés par les filtres de dessin et alimentés par les
    // collectes (tap/cadre) : clés de fixtures, ids d'instances de fil de fer, et
    // clés de structures « point » (structureKeys). Sert à calculer le COMPLÉMENT
    // du solo (tout SAUF le solo). Mémoïsé sur (data, wire) : rien à recalculer par
    // frame. Une clé en trop ici est inoffensive (elle finirait dans le caché sans
    // jamais être testée) ; une clé manquante, elle, ferait apparaître à tort un
    // élément non-soloé — d'où l'union des TROIS sources.
    val allElementKeys = remember(data, wire) {
        val s = HashSet<String>(data.fixtures.size + data.structureKeys.size + 64)
        data.fixtures.forEach { s.add(it.key) }
        data.structureKeys.forEach { s.add(it) }
        wire?.instances?.forEach { s.add(it.id) }
        s
    }
    // CACHÉ EFFECTIF = fusion masquage + solo, ramenée au seul jeu de clés que
    // consomment déjà TOUS les points de filtrage/cache du dessin (tracés, points,
    // fixtures, étiquettes, légende, export PDF). En passant ce jeu partout où
    // hiddenElements était consommé, on réutilise l'infrastructure du masquage SANS
    // la modifier — aucun second mécanisme, une seule vérité pour l'écran ET le PDF.
    //   • solo vide → CACHÉ EFFECTIF = masquage seul (on montre tout le reste) ;
    //   • solo non vide → on ajoute au caché TOUT ce qui n'est PAS dans le solo.
    // COMPOSITION avec le masquage (règle demandée) : le masquage est appliqué en
    // UNION PAR-DESSUS le solo, donc un élément soloé MAIS masqué reste caché — le
    // masquage (une décision) l'emporte sur le solo (un filtre d'affichage).
    val effectiveHidden = remember(hiddenElements, soloElements, allElementKeys) {
        if (soloElements.isEmpty()) hiddenElements
        else HashSet(hiddenElements).apply {
            allElementKeys.forEach { if (it !in soloElements) add(it) }
        }
    }
    val structPaths by produceState<StructPaths?>(null, wire, effectiveHidden) {
        val wf = wire
        value = if (wf == null || wf.isEmpty) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildStructurePaths(wf, effectiveHidden)
        }
    }
    // Structures sans fil de fer (type non extractible) → un point ; plus celles
    // que le budget de tracé a écartées ; et le repli « un point par objet »
    // utilisé pendant les gestes.
    val structDots = remember(wire, effectiveHidden, structPaths) {
        dotsPath(structureDots(wire, effectiveHidden) + (structPaths?.overflowDots ?: emptyList()))
    }
    val fallbackDots = remember(data, effectiveHidden) {
        dotsPath(data.structure.filterIndexed { i, _ ->
            data.structureKeys.getOrNull(i)?.let { it !in effectiveHidden } ?: true
        })
    }
    val fixPaths by produceState<FixturePaths?>(null, fixWire, data, effectiveHidden) {
        val fw = fixWire
        value = if (fw == null) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildFixturePaths(data, fw, effectiveHidden)
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
    LaunchedEffect(selected, data) {
        androidx.compose.runtime.snapshotFlow { selected.toList() }.collect { sel ->
            onSelectionChanged(sel.mapNotNull { data.fixtures.getOrNull(it)?.key })
        }
    }
    // Crochet DEBUG `MVR_AUTO_SELECT=<N°>` : sélectionne ce projecteur (N° effectif)
    // dès la scène prête — fiche ou bannière visibles sans toucher l'écran.
    LaunchedEffect(scene) {
        val id = takeAutoSelect()?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val idx = data.fixtures.indices.filter { i ->
            val f = data.fixtures[i]
            ((fixtureByKey[f.key]?.let { overrides.effectiveId(it) }) ?: f.id).equals(id, ignoreCase = true)
        }
        if (idx.isNotEmpty()) { selected.clear(); selected.addAll(idx) }
    }
    var rectMode by remember { mutableStateOf(false) }
    /** Mode « masquer des éléments » : le toucher (ou le cadre) retire. */
    var maskMode by remember(scene) { mutableStateOf(false) }
    // Mode SOLO — miroir de maskMode : le toucher (ou le cadre) AJOUTE à l'ensemble
    // solo au lieu d'y retirer. C'est un pur routage du geste : il n'est PAS ce qui
    // déclenche le filtrage à l'écran. Le filtrage est piloté par soloElements (voir
    // effectiveHidden) — ainsi le solo survit aux allers-retours 3D ↔ plan même si
    // ce toggle local, lui, se réinitialise (exactement comme maskMode se
    // réinitialise pendant que hiddenElements, hissé, persiste).
    var soloMode by remember(scene) { mutableStateOf(false) }
    var rectStart by remember { mutableStateOf<Offset?>(null) }
    var rectEnd by remember { mutableStateOf<Offset?>(null) }

    // MODE AFFECTATION (E2) : dérivé de la cible hissée. Comme rectMode/maskMode, il
    // s'approprie le geste (tap + rectangle) mais N'EFFACE PAS la sélection ni les
    // autres modes — c'est un mode entrant depuis l'écran Câblage, avec son bandeau
    // et son bouton « Terminé ». Le tap/rectangle AFFECTE au lieu de sélectionner.
    val assignMode = assignTarget != null

    // ---- MARQUAGE E2 (mode affectation) : projecteurs affectés à la CIBLE --------
    // Retour VISUEL + compteur du bandeau. RÉACTIF (lit cabling/dmxCabling.version) :
    // l'anneau, le libellé et le compteur se mettent à jour IMMÉDIATEMENT à chaque
    // affectation/retrait, et NE concernent QUE la cible courante (pas les autres
    // circuits/départs). Table clé→PlanFixture pour retrouver la position à l'écran.
    val fixtureByPlanKey = remember(data) { data.fixtures.associateBy { it.key } }
    val assignedToTarget: Set<String> = run {
        val t = assignTarget
        when (t?.kind) {
            CablingAssignTarget.Kind.SOCA -> {
                cabling.version.let { }
                cabling.assignments.values.asSequence()
                    .filter { it.distributor == t.distributorId && it.circuit == t.index }
                    .mapTo(HashSet()) { it.fixture }
            }
            CablingAssignTarget.Kind.DMX -> {
                dmxCabling.version.let { }
                dmxCabling.assignments.values.asSequence()
                    .filter { it.distributor == t.distributorId && it.core == t.index }
                    .mapTo(HashSet()) { it.fixture }
            }
            null -> emptySet()
        }
    }
    // Couleur (colorArgb) du distributeur ciblé — teinte de l'anneau et de la pastille
    // de libellé. Réactif au renommage/recoloration (lit .distributors).
    val assignTargetColor: Color? = run {
        val t = assignTarget ?: return@run null
        when (t.kind) {
            CablingAssignTarget.Kind.SOCA ->
                cabling.distributors.firstOrNull { it.id == t.distributorId }?.colorArgb
            CablingAssignTarget.Kind.DMX ->
                dmxCabling.distributors.firstOrNull { it.id == t.distributorId }?.colorArgb
        }?.let { Color(it) }
    }
    // Libellé de circuit près du projecteur affecté : « C<index> » / « D<index> »
    // (même convention « lettre + index » que le bandeau).
    val assignIndexTag: String = run {
        val t = assignTarget ?: return@run ""
        when (t.kind) {
            CablingAssignTarget.Kind.SOCA -> "C${t.index}"
            CablingAssignTarget.Kind.DMX -> "D${t.index}"
        }
    }

    // ---- BARRE D'AFFECTATION : état de la voie (charge, compte, ◀ ▶) ----------
    // Conso / empreinte par projecteur : MÊMES sources que l'écran Câblage
    // (conso = bibliothèque de puissances via la spec de géométrie ; empreinte =
    // resolveDmxFootprints, hors thread principal). Calculées seulement pour la
    // famille de la voie ; `null` tant que non résolues → sablier dans la barre.
    val assignKind = assignTarget?.kind
    val wattsByFixture: Map<String, Int>? = remember(
        scene, overrides.version, power, power?.version, customResolver.version, assignKind
    ) {
        val pw = power
        if (assignKind != CablingAssignTarget.Kind.SOCA || pw == null) null
        else scene.fixtures.mapNotNull { f ->
            val uuid = f.uuid ?: return@mapNotNull null
            pw.effective(effectiveGeometrySpec(f, overrides, customResolver)).watts?.let { uuid to it }
        }.toMap()
    }
    val footprintByFixture by produceState<Map<String, Int>?>(
        null, scene, mvrBytes, overrides.version, gdtfOverrides?.version, customResolver.version,
        assignKind == CablingAssignTarget.Kind.DMX
    ) {
        if (assignKind == CablingAssignTarget.Kind.DMX) {
            value = resolveDmxFootprints(scene, mvrBytes, overrides, gdtfOverrides ?: GdtfOverrides(), customResolver)
                .mapNotNull { f -> f.channels?.let { f.uuid to it } }.toMap()
        }
    }
    // État de la voie RECALCULÉ à chaque toucher (lecture de `.version`) : charge,
    // surcharge, compte, ◀ ▶. `null` = la voie n'existe plus (« Voie supprimée »).
    val assignStatus: CablingVoieStatus? = remember(
        assignTarget, cabling.version, dmxCabling.version, wattsByFixture, footprintByFixture
    ) {
        val t = assignTarget
        when (t?.kind) {
            CablingAssignTarget.Kind.SOCA -> CablingVoieStatus.power(t.voie, cabling.toDTO(), wattsByFixture)
            CablingAssignTarget.Kind.DMX -> CablingVoieStatus.dmx(t.voie, dmxCabling.toDTO(), footprintByFixture)
            null -> null
        }
    }
    // Cadres (repère racine, px) du plan, de la légende et de la barre : place de
    // la barre (dégagement de la légende) et réserve du cadrage.
    var planBoundsPx by remember { mutableStateOf(Rect.Zero) }
    var legendBoundsPx by remember { mutableStateOf<Rect?>(null) }
    var assignBarTopPx by remember { mutableStateOf(0f) }
    // Hauteur RÉELLE de la barre (px) : bande qu'une légende du haut peut gêner.
    var assignBarHeightPx by remember { mutableStateOf(0f) }
    // Cadre d'ancrage entier de la barre : obstacle de la mention du satellite.
    var assignBarAnchorPx by remember { mutableStateOf(Rect.Zero) }
    // Mention du fond satellite et curseur d'opacité : cadres SANS montée (px).
    var satMentionAnchorPx by remember { mutableStateOf(Rect.Zero) }
    // Cadres (px racine) qui bornent les panneaux du bas-gauche (Position GPS, Plan
    // importé, Réglage des étiquettes — PanelPlacementMath.floatingPanelSlot) et
    // font monter la pile bas-droite : en bas la fiche / bannière et les aides.
    // Habillage (tranche C) : bas de la rangée du haut, pastille « Vue » centrée,
    // bandeau de mode, pastille de synchro.
    var topRowBottomPx by remember { mutableStateOf(0f) }
    // ← et groupe de droite de la rangée : la barre ancrée du haut se pose entre eux.
    var topRowBackPx by remember { mutableStateOf(Rect.Zero) }
    var topRowTrailingPx by remember { mutableStateOf(Rect.Zero) }
    var viewPillPx by remember { mutableStateOf(Rect.Zero) }
    var modeBannerPx by remember { mutableStateOf(Rect.Zero) }
    var syncBadgeWidthPx by remember { mutableStateOf(0f) }
    var selectionBarPx by remember { mutableStateOf(Rect.Zero) }
    var bottomAidPx by remember { mutableStateOf(Rect.Zero) }
    // Cadres AFFICHÉS des panneaux flottants (barre ancrée gauche, §3.13).
    val panelFramesPlan = remember { PanelFrames() }

    // ---- Mesure entre deux points ----
    // Les deux extrémités sont en coordonnées PLAN (mm), pas en pixels : la
    // cote ne doit pas changer quand on déplace ou zoome le plan entre les deux
    // touchers. Écrites au TAP uniquement — jamais pendant un glissé — donc
    // aucun état observable n'est touché par événement tactile.
    var measureMode by remember(scene) { mutableStateOf(false) }
    var measureA by remember(scene) { mutableStateOf<MeasurePoint?>(null) }
    var measureB by remember(scene) { mutableStateOf<MeasurePoint?>(null) }
    // Sommets DXF candidats à l'accrochage, préparés hors thread principal et
    // plafonnés (cf. dxfSnapVertices) : un plan d'architecte en compte des
    // millions, on ne peut pas les balayer tous à chaque toucher.
    // `hiddenLayers` fait partie des clés : un calque éteint ne doit plus
    // aimanter le doigt (cf. dxfSnapVertices).
    val snapVerts by produceState<FloatArray?>(null, referencePlan?.plan, measureMode, hiddenLayers) {
        val plan = referencePlan?.plan
        val hidden = hiddenLayers
        value = if (plan == null || !measureMode) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            dxfSnapVertices(plan, hidden)
        }
    }
    var query by remember(scene) { mutableStateOf("") }

    // ---- Export PDF « en construction » ----
    // L'utilisateur cadre, AJOUTE la vue au document, la nomme, recommence ; le
    // PDF final est la compilation de ces vues. Les captures sont figées (copie
    // des réglages) parce que `options` est partagé et mutable : sans copie,
    // changer une bascule après coup réécrirait les pages déjà composées.
    var exportMode by remember(scene) { mutableStateOf(false) }
    val exportViews = remember(scene) { mutableStateListOf<PlanViewCapture>() }
    var exportName by remember(scene) { mutableStateOf("") }
    var exportBusy by remember(scene) { mutableStateOf(false) }
    var exportFile by remember(scene) { mutableStateOf<java.io.File?>(null) }
    var renamingIndex by remember(scene) { mutableStateOf<Int?>(null) }
    var renamingText by remember(scene) { mutableStateOf("") }
    val exportScope = rememberCoroutineScope()

    // Géolocalisation : position GPS en direct + calibration par ancres (la
    // calibration est HISSÉE dans SceneScreen → survit aux bascules + persistée).
    var showLocation by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var calibVersion by remember { mutableIntStateOf(0) } // force le redraw à l'ajout d'ancre
    // ASSISTANT « CALIBRATION GPS EN 3 POINTS » (SPEC-calibration-3-points) : non
    // nul = ouvert. Seuls son panneau et son calque de dessin OBSERVENT la session
    // (sinon chaque tic de mesure recomposerait tout le plan). Il démarre le GPS
    // lui-même ; quitter la vue plan le ferme (mesure interrompue = à refaire).
    var cal3Session by remember { mutableStateOf<ThreePointCalibrationSession?>(null) }
    val locFeed = rememberUserLocationFeed(showLocation || cal3Session != null)
    val gps by locFeed.location
    DisposableEffect(cal3Session) {
        val s = cal3Session
        locFeed.rawFixHandler = s?.let { ss -> { loc -> ss.onRawFix(loc.toRawFix()) } }
        onDispose {
            locFeed.rawFixHandler = null
            s?.cancel()
        }
    }
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
    // Désactivation de l'étiquette active dès que le contexte ne s'y prête plus :
    // étiquettes éteintes, ou passage dans un mode qui s'approprie le geste
    // (cadre, masquage, calibrage). Sans ça, une étiquette resterait mise en
    // valeur alors que plus aucun glissé ne peut la déplacer. Passé par un effet
    // plutôt qu'écrit en pleine composition (ce que Compose reproche à juste
    // titre). La sortie de la vue plan est, elle, couverte par `remember` :
    // PlanScreen quitte la composition et l'état disparaît avec lui.
    // Le contenu (`labelFields`) et le découpage en blocs (`labelDetached`) en
    // font partie : changer l'un peut vider le texte d'un bloc armé, ou faire
    // disparaître le bloc lui-même (un champ regroupé n'a plus de clé propre) :
    // le bloc n'est alors plus dessiné, donc plus déplaçable, et laisser l'état
    // actif ne ferait qu'entretenir une mise en valeur fantôme.
    // Désactivation inconditionnelle : chacune de ces bascules change le contexte
    // au point qu'une étiquette « armée » n'a plus de sens (y compris le retour
    // au mode normal, où l'utilisateur ne s'attend plus à rien de sélectionné).
    LaunchedEffect(
        options.showLabels, options.labelFields, options.labelDetached,
        rectMode, maskMode, soloMode, calibrating, measureMode, assignMode, cal3Session != null
    ) {
        activeLabelKeys = emptySet()
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

    // Persistance projet de la calibration posée ici (le placement du plan importé,
    // lui, est persisté et partagé par SceneScreen — SPEC-menus-android §3.7, A29).
    if (projectKey != null) {
        LaunchedEffect(calibVersion) {
            if (calibVersion > 0) {
                // Copie sur le thread principal (la calibration est mutée ici même).
                val anchors = calibration.anchors.toList(); val survey = calibration.survey
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.minou.mvrviewer.mvr.ProjectStore.saveCalibration(ctxPlan, projectKey, anchors, survey)
                }
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
        // Étiquettes déplacées à la main : purement LOCAL (pas de synchro cloud,
        // cf. ProjectStore.saveLabelOffsets). Seule la RELECTURE passe par une
        // coroutine du composable ; l'écriture est confiée au fil du store
        // (cf. saveLabelOffsetsAsync) car elle part au relâché du doigt, geste
        // typiquement suivi d'un retour à la 3D qui annulerait la coroutine.
        LaunchedEffect(Unit) {
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.minou.mvrviewer.mvr.ProjectStore.loadLabelOffsets(ctxPlan, projectKey)
            }
            // Un déplacement (ou une remise à zéro) a pu avoir lieu PENDANT la
            // lecture : la table en mémoire est alors plus récente que le
            // disque, on ne la contredit pas.
            if (labelsLoaded[0]) return@LaunchedEffect
            labelsLoaded[0] = true
            if (saved.isNotEmpty()) {
                // Migration des clés d'AVANT les blocs (une étiquette = un
                // projecteur) : sans elle, tous les décalages déjà posés au
                // doigt deviendraient orphelins et les étiquettes reviendraient
                // d'un coup à leur place d'origine à la réouverture du projet.
                for ((k0, v) in saved) {
                    val k = migrateLegacyLabelKey(k0)
                    if (k !in labelShift) labelShift[k] = Offset(v.first, v.second)
                }
                labelDragVersion++
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

    // AFFECTATION EN MODE PLAN (E2) — TOGGLE d'un projecteur (clé = mvrUUID) vers la
    // cible courante. SOCA : si le projecteur est DÉJÀ sur ce circuit → on le retire ;
    // sinon on l'affecte (ce qui le DÉPLACE au besoin depuis un autre circuit). DMX :
    // symétrique via `dmxCabling`. La coloration réactive (lecture de `.version`) donne
    // le retour visuel immédiat. On NE touche PAS à `selected` (indépendant du câblage).
    // Voie disparue (distributeur supprimé ici ou par un coéquipier, index hors
    // bornes) : RIEN n'est écrit — ni sauvegarde, ni synchro (SPEC §6) ; la barre
    // dit « Voie supprimée » et le retour haptique est un avertissement.
    fun toggleAssign(fx: String) {
        val t = assignTarget ?: return
        val result = when (t.kind) {
            CablingAssignTarget.Kind.SOCA -> cabling.toggleOnVoie(fx, t.voie)
            CablingAssignTarget.Kind.DMX -> dmxCabling.toggleOnVoie(fx, t.voie)
        }
        // RETOUR HAPTIQUE, différencié affectation / retrait / refus. N'est appelée
        // QUE quand un projecteur est réellement touché (le hit-test a trouvé une
        // cible) → aucun retour sur un tap dans le vide.
        cablingHaptic(hapticView, CablingHaptic.of(result))
    }

    /**
     * Fige la vue COURANTE pour l'export. Le cadrage est enregistré en
     * coordonnées monde (centre + demi-étendue) et non en pixels : la page PDF
     * n'a ni la taille ni la densité de l'écran, mais elle doit montrer la même
     * portion de plan.
     */
    fun captureCurrentView(name: String): PlanViewCapture? {
        val w = canvas.x; val h = canvas.y
        val bs = baseScale(w, h) * scale
        if (w <= 0f || h <= 0f || bs <= 0f) return null
        val (mx, my) = toPlan(w / 2f, h / 2f, w, h)
        return PlanViewCapture(
            name = name,
            centerX = mx, centerY = my,
            halfW = w / 2f / bs, halfH = h / 2f / bs,
            layerColors = options.layerColors,
            // CÂBLAGE (phase 4) : on fige le mode EFFECTIF + les tables figées (déjà
            // immuables) → le PDF reflète la coloration/étiquettes/légende de l'écran.
            colorMode = effectiveColorMode,
            cablingColor = cablingColorMap,
            cablingRingColor = cablingRingColorMap,
            cablingLegend = cablingLegend,
            cablingText = cablingText,
            showStructure = options.showStructure,
            showLabels = options.showLabels,
            showLegend = options.showLegend,
            labelFields = options.labelFields,
            labelDetached = options.labelDetached,
            labelSize = options.labelSize,
            labelOffset = options.labelOffset,
            background = options.background2D,
            bgDark = BackgroundColorStore.isDark(options.background2D),
            showSatellite = options.showSatellite,
            satelliteOpacity = options.satelliteOpacity,
            // CACHÉ EFFECTIF (masquage + solo) : l'export doit refléter EXACTEMENT
            // ce que l'utilisateur voit — solo compris — donc pas hiddenElements brut.
            hiddenElements = effectiveHidden.toSet(),
            hiddenLayers = hiddenLayers.toSet(),
            labelShift = HashMap(labelShift),
            selected = selected.toSet(),
            screenDensity = labelDensity.density,
            screenPxPerMm = bs,
            // COPIE : la transformée du plan de repère est mutable et réglée en
            // direct aux curseurs. Sans copie, déplacer le plan (ou décocher
            // « Visible ») après coup réécrivait les pages déjà composées.
            refTransform = referencePlan?.transform?.copy()
        )
    }

    // Fond de plan choisi + contraste : sur fond sombre, les tracés/étiquettes
    // dessinés en foncé doivent s'éclaircir pour rester lisibles (le décor par
    // calque et les pastilles restent vifs et passent sur les deux).
    val planBg = options.background2D
    val bgDark = BackgroundColorStore.isDark(planBg)
    val inkColor = if (bgDark) Color(0xFFECECEC) else Color(0xFF222222)
    val dxfColor = if (bgDark) DXF_COLOR_DARK_BG else DXF_COLOR
    // Chaînes localisées utilisées hors contexte composable (Canvas, lambdas).
    val sMeasureStartPlaced = stringResource(R.string.measure_start_placed)
    val sExportViewFmt = stringResource(R.string.plan_export_view_fmt)
    val sPdfDocTitle = stringResource(R.string.plan_pdf_doc_title)

    // N11 — panneau « Personnaliser la barre d'outils… » (mode édition).
    var showCustomize by remember { mutableStateOf(false) }
    // N11 — sélecteur de couleur du fond ouvert par un bouton DOCKÉ (outil BACKGROUND).
    var showBackgroundDialog by remember { mutableStateOf(false) }
    // Panneaux flottants (Position GPS, Plan importé, Réglage des étiquettes) : leur
    // état est hissé dans SceneScreen ([panels]) — menu, outils et crochets les
    // ouvrent de la même façon dans les deux vues.

    // ---- ASSISTANT 3 POINTS : ouverture, fermeture, application ----------------
    // Cadre d'ANCRAGE du panneau (px racine) : sa hauteur est réservée au cadrage.
    var cal3PanelTopPx by remember { mutableStateOf(0f) }
    var cal3PanelHeightPx by remember { mutableStateOf(0f) }
    // Même cadre d'ancrage, entier : obstacle de la mention du fond satellite.
    var cal3PanelAnchorPx by remember { mutableStateOf(Rect.Zero) }
    // Plus grande réserve déjà cadrée (la vue posée par ce cadrage est retenue
    // par `autoFitView` : si l'utilisateur a bougé le plan depuis, on ne recadre plus).
    var cal3FitReserve by remember { mutableStateOf(0f) }
    // Taille du plan (canevas) lors du dernier cadrage : une autre taille =
    // rotation → recadrer même si la réserve a baissé (SPEC §2).
    var cal3FitCanvas by remember { mutableStateOf<Offset?>(null) }
    // Emprise et éléments repères en cours de calcul (hors thread principal).
    var cal3Starting by remember { mutableStateOf(false) }
    val cal3Token = remember { IntArray(1) }
    val cal3Scope = rememberCoroutineScope()
    fun closeThreePoint() {
        cal3Token[0]++
        cal3Starting = false
        cal3Session?.cancel()
        cal3Session = null
        cal3PanelTopPx = 0f; cal3PanelHeightPx = 0f; cal3PanelAnchorPx = Rect.Zero
        cal3FitReserve = 0f; cal3FitCanvas = null
    }
    // MODES DE TOUCHER : état réduit lu/posé ici, décisions dans PlanModeRules
    // (pure, testée). Armer un mode y ferme l'assistant 3 points.
    fun currentTouchModes() = PlanTouchModes(
        rect = rectMode, mask = maskMode, solo = soloMode, measure = measureMode,
        anchorTap = calibrating, threePoint = cal3Session != null || cal3Starting
    )
    fun applyTouchModes(n: PlanTouchModes) {
        rectMode = n.rect; maskMode = n.mask; soloMode = n.solo
        measureMode = n.measure; calibrating = n.anchorTap
        if (!n.threePoint && (cal3Session != null || cal3Starting)) closeThreePoint()
    }
    /** Bascule d'un outil de toucher (barres et menu) ; rend le nouvel état. */
    fun toggleTouchTool(tool: PlanTouchTool): PlanTouchModes =
        PlanModeRules.toggle(currentTouchModes(), tool).also { applyTouchModes(it) }
    // Ouvre l'assistant : GPS démarré, autres modes de toucher désarmés
    // (mesure, masquage, solo, cadre, « je suis ici », affectation câblage).
    fun startThreePoint() {
        if (assignTarget != null) onExitAssign()
        if (measureMode) { measureA = null; measureB = null }
        applyTouchModes(PlanModeRules.startThreePoint())
        showLocation = true
        closeThreePoint()
        val token = cal3Token[0]
        val fixturesWorld = data.fixtures.map { it.key to Vec2(it.px.toDouble(), -it.py.toDouble()) }
        val structurePoints = data.structure.mapIndexed { i, p ->
            (data.structureKeys.getOrNull(i) ?: "") to Vec2(p.first.toDouble(), -p.second.toDouble())
        }
        val wf = wire; val hidden = effectiveHidden.toSet(); val rp = referencePlan; val refHidden = hiddenLayers
        val stored = CalibrationStepLengthStore.load(ctxPlan)
        cal3Starting = true
        cal3Scope.launch {
            val inputs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                ThreePointPlanInputs.compute(fixturesWorld, structurePoints, wf, hidden, rp, refHidden)
            }
            if (token != cal3Token[0]) return@launch // fermé ou relancé entre-temps
            cal3Starting = false
            cal3Session = ThreePointCalibrationSession(
                inputs.bounds, inputs.landmarks, stored,
                clock = ::locationClockSeconds,
                lastRawFix = { locFeed.lastRawFix?.toRawFix() },
                onStepLengthChanged = { CalibrationStepLengthStore.save(ctxPlan, it) }
            )
        }
    }
    // « Appliquer » : la calibration vaut PARTOUT (plan, 3D, fond satellite) via
    // l'objet hissé dans SceneScreen, qui la persiste, la pousse et la journalise
    // (onCalibrationChanged) comme les autres méthodes ; la position s'affiche.
    fun applyThreePoint(anchors: List<com.minou.mvrviewer.mvr.GeoAnchor>, survey: com.minou.mvrviewer.mvr.GeoSurvey) {
        calibration.replace(anchors, survey)
        calibVersion++
        onCalibrationChanged()
        showLocation = true
        closeThreePoint()
    }
    /**
     * Pose « je suis ici » : UNE fonction, partagée par le panneau Position GPS,
     * l'outil `calibrate` et le lancement `PlanStartAction.HERE_ANCHOR`
     * (SPEC-menus-android §3.6). La position s'allume ; le toucher suivant pose
     * le point (repart de zéro après un relevé 3 points, sinon point suivant).
     * La garde Pro est posée par l'appelant.
     */
    fun armHereAnchor() {
        showLocation = true
        if (!calibrating) toggleTouchTool(PlanTouchTool.ANCHOR_TAP)
    }

    // MULTI-SÉLECTION (SPEC-menus-android §9.0, Q2) : toucher un projecteur REMPLACE
    // la sélection ; avec Multi-sélection — ou le cadre armé, qui la force — il
    // ajoute / retire. Couper le multi vide la sélection et coupe le cadre.
    var multiMode by remember(scene) { mutableStateOf(false) }
    fun selectionModes() = SelectionModes(multi = multiMode, rect = rectMode)

    // N11 — DESCRIPTION UNIFIÉE des outils de la vue plan : UNE liste consommée par
    // les 4 barres ancrables (AnchoredToolbars), les boutons permanents de la rangée
    // du haut (cadre, multi), le catalogue « Barre d'outils de cette vue… » et les
    // entrées du menu ⋯ qui font la même chose (mêmes actions, mêmes exclusions).
    // La barre flottante historique est retirée (§9.0, Q1).
    val toolsPlan: List<ToolSpec> = ToolCatalog.ordered(ToolbarView.PLAN, buildList {
        add(ToolSpec(ToolId.RECT, stringResource(R.string.tool_rect_select), Icons.Filled.Crop,
            available = true, checked = rectMode, onInvoke = {
                // Armer le cadre force le multi (SelectionRules.toggleRect).
                if (toggleTouchTool(PlanTouchTool.RECT).rect) multiMode = true
            }))
        add(ToolSpec(ToolId.MULTI_SELECT, stringResource(R.string.perm_multi_select), Icons.Filled.Checklist,
            available = true, checked = multiMode || rectMode, onInvoke = {
                val t = SelectionRules.toggleMulti(selectionModes())
                multiMode = t.modes.multi
                if (rectMode && !t.modes.rect) toggleTouchTool(PlanTouchTool.RECT)
                if (t.clearSelection) selected.clear()
            }))
        add(ToolSpec(ToolId.MASK, stringResource(R.string.tool_mask), Icons.Filled.VisibilityOff,
            available = true, checked = maskMode, onInvoke = {
                if (toggleTouchTool(PlanTouchTool.MASK).mask) selected.clear()
            }))
        add(ToolSpec(ToolId.SOLO, stringResource(R.string.tool_solo), Icons.Filled.CenterFocusStrong,
            available = true, checked = soloMode, onInvoke = {
                if (toggleTouchTool(PlanTouchTool.SOLO).solo) selected.clear()
                else onSetSoloElements(emptySet())
            }))
        add(ToolSpec(ToolId.MEASURE, stringResource(R.string.tool_measure), Icons.Filled.Straighten,
            available = true, checked = measureMode, onInvoke = {
                // Armé ou désarmé : la mesure repart de zéro.
                toggleTouchTool(PlanTouchTool.MEASURE)
                measureA = null; measureB = null
            }))
        add(ToolSpec(ToolId.SHOW_ALL, stringResource(R.string.tool_show_all), Icons.Filled.Visibility,
            available = hiddenElements.isNotEmpty(), checked = null,
            onInvoke = { onSetHiddenElements(emptySet()) }))
        add(ToolSpec(ToolId.CLEAR_SOLO, stringResource(R.string.tool_clear_solo), Icons.Filled.CenterFocusWeak,
            available = soloElements.isNotEmpty(), checked = null,
            onInvoke = { onSetSoloElements(emptySet()) }))
        add(ToolSpec(ToolId.CLEAR_SEL, stringResource(R.string.tool_clear_selection), Icons.Filled.Clear,
            available = selected.isNotEmpty(), checked = null, onInvoke = { selected.clear() }))
        add(ToolSpec(ToolId.GPS, stringResource(R.string.tool_gps), Icons.Filled.MyLocation,
            available = true, checked = showLocation, onInvoke = {
                showLocation = !showLocation; if (!showLocation) calibrating = false
            }))
        // Calibrations : leur place est le panneau Position GPS (SPEC-menus-android
        // §3.6) ; les outils restent ancrables.
        add(ToolSpec(ToolId.CALIBRATE, stringResource(R.string.tool_calibrate), Icons.Filled.Place,
            available = showLocation, checked = calibrating, onInvoke = {
                // Calibration GPS = donnée de show PARTAGÉE (poussée, journalisée,
                // destructive pour l'équipe) : Pro. Désarmer reste libre.
                if (calibrating) toggleTouchTool(PlanTouchTool.ANCHOR_TAP)
                else ProAccess.gate(GeoPanelContent.PRO_REASON) {
                    // Rappel éventuellement différé (achat) : armer, jamais rebasculer.
                    armHereAnchor()
                }
            }))
        // Calibration GUIDÉE EN 3 POINTS (SPEC-calibration-3-points) : même place que
        // « je suis ici ». Disponible sans « Ma position » : l'assistant démarre
        // lui-même le GPS. Fonction Pro (calibration = donnée partagée).
        add(ToolSpec(ToolId.CALIBRATE_3, stringResource(R.string.tool_calibrate_3), Icons.Filled.TrackChanges,
            available = true, checked = cal3Session != null || cal3Starting, onInvoke = {
                if (cal3Session != null || cal3Starting) closeThreePoint()
                else ProAccess.gate(GeoPanelContent.PRO_REASON) { startThreePoint() }
            }))
        // Panneau « Position GPS » (tranche B) : ouvre / referme (actif = jaune) ;
        // l'entrée du ⋯ « Position GPS… » est rendue par le menu (pas de doublon).
        add(ToolSpec(ToolId.GEO_PANEL, stringResource(R.string.geo_panel_title), Icons.Filled.LocationOn,
            available = true, checked = panels.geo,
            onInvoke = { onPanels(panels.toggle(ScenePanel.GEO)) }))
        // Fond IGN = France seulement : hors couverture, Toast et PAS de bascule (le
        // réglage mémorisé est gardé pour un retour en France).
        add(ToolSpec(ToolId.SATELLITE, stringResource(R.string.tool_satellite), Icons.Filled.Public,
            available = calibration.isCalibrated, checked = options.showSatellite && satelliteCovered,
            onInvoke = {
                SatelliteUi.onToolTap(satelliteCovered, options::toggleSatelliteByUser)
                    ?.let { SatelliteUi.toast(ctxPlan, it) }
            }))
        add(ToolSpec(ToolId.EXPORT_PDF, stringResource(R.string.tool_export_pdf), Icons.Filled.PictureAsPdf,
            available = true, checked = exportMode, onInvoke = { exportMode = !exportMode }))
        // Plan importé (tranche E) : deux outils à libellé FIXE — `importDXF` importe
        // (Pro, garde dans SceneScreen), `refPlanPanel` ouvre / referme le panneau
        // (lecture pour tous, décision 6), seulement s'il y a un plan.
        add(ToolSpec(ToolId.DXF, stringResource(R.string.tool_import_ref_plan), Icons.Filled.UploadFile,
            available = true, checked = null, busy = importingRefPlan, onInvoke = onImportRefPlan))
        add(ToolSpec(ToolId.REF_PLAN_PANEL, stringResource(R.string.tool_ref_plan_panel), Icons.Filled.Tune,
            available = referencePlan != null, checked = panels.refPlan,
            onInvoke = { onPanels(panels.toggle(ScenePanel.REF_PLAN)) }))
        // Fenêtres du projet (tranche E, barre préremplie de la tablette) : par-dessus
        // le plan, qui se retrouve intact au retour (mêmes entrées que le ⋯).
        add(ToolSpec(ToolId.LAYERS, stringResource(R.string.menu_layers), Icons.Filled.Layers,
            available = true, checked = null, onInvoke = { onMainMenu(MainMenuItemId.LAYERS) }))
        add(ToolSpec(ToolId.PATCH, stringResource(R.string.nav_patch_list), Icons.AutoMirrored.Filled.List,
            available = true, checked = null, onInvoke = { onMainMenu(MainMenuItemId.PATCH) }))
        add(ToolSpec(ToolId.UNIVERSE, stringResource(R.string.nav_dmx_universe), Icons.Filled.GridView,
            available = true, checked = null, onInvoke = { onMainMenu(MainMenuItemId.UNIVERSE) }))
        add(ToolSpec(ToolId.CABLING, stringResource(R.string.menu_cabling), Icons.Filled.Bolt,
            available = true, checked = null, onInvoke = { onMainMenu(MainMenuItemId.CABLING) }))
        // Extras plaçables (défaut hors barres) — bascule dédiée déjà au menu.
        add(ToolSpec(ToolId.LABELS, stringResource(R.string.opt_labels), Icons.Filled.Label,
            available = true, checked = options.showLabels,
            onInvoke = { options.showLabels = !options.showLabels }))
        // Réglage des étiquettes (parité iOS catalogPlan) : ACTION ponctuelle qui
        // ouvre le panneau « Réglage des étiquettes » (⋯ › Affichage aussi).
        add(ToolSpec(ToolId.LABEL_SETTINGS, stringResource(R.string.tool_label_settings), Icons.Filled.FormatSize,
            available = true, checked = null,
            onInvoke = { onPanels(panels.toggle(ScenePanel.LABELS)) }))
        add(ToolSpec(ToolId.LAYER_COLORS, stringResource(R.string.opt_layer_colors), Icons.Filled.Palette,
            available = true, checked = options.layerColors,
            onInvoke = { options.layerColors = !options.layerColors }))
        add(ToolSpec(ToolId.STRUCTURE, stringResource(R.string.opt_structure), Icons.Filled.Architecture,
            available = true, checked = options.showStructure,
            onInvoke = { options.showStructure = !options.showStructure }))
        add(ToolSpec(ToolId.LEGEND, stringResource(R.string.opt_legend), Icons.Filled.FormatListBulleted,
            available = true, checked = options.showLegend,
            onInvoke = { options.showLegend = !options.showLegend }))
        // Couleur du fond (parité iOS catalogPlan) : ouvre le sélecteur fin ;
        // ⋯ › Affichage › Couleur du fond garde les préréglages.
        add(ToolSpec(ToolId.BACKGROUND, stringResource(R.string.menu_section_background), Icons.Filled.FormatColorFill,
            available = true, checked = null,
            onInvoke = { showBackgroundDialog = true }))
    })

    // LANCEMENT DU PLAN (PlanStartAction) : pris UNE fois à l'apparition (ou à une
    // nouvelle demande) — une recomposition ne le relance jamais. La
    // garde Pro des calibrations est faite AVANT l'ouverture du plan. Posé après
    // la relecture de « Ma position » (effet plus haut) : « je suis ici » allume
    // la position par-dessus la valeur relue.
    LaunchedEffect(startTick) {
        when (takeStartAction()) {
            PlanStartAction.EXPORT_PDF -> exportMode = true
            PlanStartAction.THREE_POINT -> if (cal3Session == null && !cal3Starting) startThreePoint()
            PlanStartAction.HERE_ANCHOR -> armHereAnchor()
            PlanStartAction.NONE -> {}
        }
    }

    // CADRAGE AUTOMATIQUE SOUS LA PASTILLE « VUE » (SPEC-menus-android §3.3,
    // PlanFitRules) : ouverture, affectation, assistant 3 points et Recadrer posent le
    // contenu entre le bas mesuré de la pastille (+ 24 dp) et le haut de la barre du
    // bas. `autoFitView` = dernière vue posée par un cadrage automatique : une autre
    // vue = le plan a été touché, plus aucun recadrage automatique.
    var autoFitView by remember { mutableStateOf<Pair<Float, Offset>?>(null) }
    fun planUntouched(): Boolean = (scale to offset) == (autoFitView ?: (1f to Offset.Zero))
    /** Du haut du plan au bas de la pastille (px) ; 0 tant qu'elle n'est pas mesurée. */
    fun pillReservePx(): Float {
        val pb = planBoundsPx
        return if (viewPillPx.height > 0f && pb.height > 0f) (viewPillPx.bottom - pb.top).coerceAtLeast(0f) else 0f
    }
    fun fitUnderPill(bottomReserve: Float): Pair<Float, Float> = PlanFitRules.underPill(
        canvas.x, canvas.y, data.spanX, data.spanY, pillReservePx(), bottomReserve,
        PlanFitRules.TOP_GAP_DP * labelDensity.density, PlanFitRules.MIN_CONTENT_DP * labelDensity.density,
        labelDensity.density
    )
    fun applyAutoFit(s: Float, dy: Float) {
        scale = s; offset = Offset(0f, dy); autoFitView = s to offset
    }
    /** Place prise EN BAS par la barre d'affectation ou le panneau 3 points (mesurés). */
    fun bottomReservePx(): Float {
        val pb = planBoundsPx
        if (pb.height <= 0f) return 0f
        var r = 0f
        if (assignTarget != null && assignBarTopPx > pb.top && assignBarTopPx < pb.bottom)
            r = maxOf(r, CablingAssignBarPlacement.reserve(pb.top, pb.bottom, assignBarTopPx))
        if (cal3Session != null && cal3PanelTopPx > pb.top && cal3PanelTopPx < pb.bottom)
            r = maxOf(r, CablingAssignBarPlacement.reserve(pb.top, pb.bottom, cal3PanelTopPx))
        return r
    }
    /**
     * RECADRER (pile bas-droite, nouveau dans le plan) : zoom et décalage remis,
     * sous la pastille et au-dessus de la barre d'affectation / du panneau 3 points.
     */
    fun reframePlan() {
        if (canvas.x <= 0f || canvas.y <= 0f) { scale = 1f; offset = Offset.Zero; autoFitView = null; return }
        val (s, dy) = fitUnderPill(bottomReservePx())
        applyAutoFit(s, dy)
    }

    // Ouverture et rotation : le contenu ne passe pas sous la pastille, tant que le
    // plan n'a pas été touché (règle d'origine si la pastille ne gêne pas).
    LaunchedEffect(viewPillPx.bottom, canvas) {
        if (assignTarget != null || cal3Session != null || cal3Starting) return@LaunchedEffect
        if (canvas.x <= 0f || canvas.y <= 0f || !planUntouched()) return@LaunchedEffect
        val (s, dy) = fitUnderPill(0f)
        applyAutoFit(s, dy)
    }

    // CADRAGE en mode affectation : au premier affichage de la barre (une fois
    // qu'elle est mesurée), le plan est recadré AU-DESSUS d'elle — la hauteur
    // qu'elle occupe en bas est retirée de la zone de cadrage, bornée à 60 % — et
    // SOUS la pastille. Refait si la taille du plan ou la pastille change
    // (rotation), seulement si l'utilisateur n'a pas encore bougé le plan.
    var assignFitKey by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(assignTarget != null, assignBarTopPx, planBoundsPx, canvas, viewPillPx.bottom) {
        if (assignTarget == null) { assignFitKey = null; return@LaunchedEffect }
        val pb = planBoundsPx
        if (pb.height <= 0f || canvas.x <= 0f || canvas.y <= 0f) return@LaunchedEffect
        if (assignBarTopPx <= pb.top || assignBarTopPx >= pb.bottom) return@LaunchedEffect
        val key = canvas to viewPillPx.bottom
        if (assignFitKey == key) return@LaunchedEffect
        assignFitKey = key
        if (!planUntouched()) return@LaunchedEffect
        val reserve = CablingAssignBarPlacement.reserve(pb.top, pb.bottom, assignBarTopPx)
        val (s, dy) = fitUnderPill(reserve)
        applyAutoFit(s, dy)
    }

    // CADRAGE pendant l'assistant 3 points : tant que le plan n'a pas été touché,
    // il est recadré AU-DESSUS du panneau (et sous la pastille) quand celui-ci
    // dépasse la plus grande hauteur déjà cadrée (+ 4 dp) — jamais à chaque étape
    // (pas de sautillement) — et après une rotation, même si le panneau y prend
    // moins de place (décisions : ThreePointPlanFit, pure et testée).
    LaunchedEffect(cal3Session != null, cal3PanelTopPx, planBoundsPx, canvas) {
        if (cal3Session == null) return@LaunchedEffect
        val pb = planBoundsPx
        val fitted = cal3FitCanvas
        val fit = ThreePointPlanFit.refit(
            canvas.x, canvas.y, data.spanX, data.spanY, pb.top, pb.bottom, cal3PanelTopPx,
            fittedReserve = cal3FitReserve, untouched = planUntouched(),
            sizeChanged = fitted != null && fitted != canvas,
            slack = 4f * labelDensity.density,
            topReserve = pillReservePx(),
            topGap = PlanFitRules.TOP_GAP_DP * labelDensity.density,
            minContent = PlanFitRules.MIN_CONTENT_DP * labelDensity.density,
            dp = labelDensity.density
        ) ?: return@LaunchedEffect
        cal3FitReserve = fit.reserve
        applyAutoFit(fit.scale, fit.dy)
        cal3FitCanvas = canvas
    }

    // Fiche du bas : 1 projecteur = fiche, plusieurs = bannière — les MÊMES qu'en 3D
    // (SPEC-menus-android §3.9). Masquées pendant l'assistant 3 points,
    // l'affectation, la mesure et l'aide « je suis ici » : le bas leur appartient
    // (iOS selectionBarShown) — sinon elles recouvraient leurs boutons.
    val selectionBar = if (selectionCard == null) PlanSelectionBar.NONE else PlanBottomRules.selectionBar(
        selected.size, threePointOpen = cal3Session != null || cal3Starting, assignOpen = assignTarget != null,
        measuring = measureMode, anchorAid = calibrating
    )
    Box(modifier = modifier.fillMaxSize().clipToBounds().background(planBg)
        .onGloballyPositioned { planBoundsPx = it.boundsInRoot() }) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                // En mode rectangle, le glissé trace le cadre de sélection ; sinon il
                // déplace/zoome le plan (comme le mode dédié iOS). En AFFECTATION, le
                // cadre s'arme par le bouton cadre de la barre (= rectMode) : le plan
                // reste libre au doigt entre deux touchers.
                .pointerInput(scene, rectMode, assignMode, maskMode, soloMode, hiddenElements, soloElements, assignTarget) {
                    if (rectMode) {
                        detectDragGestures(
                            onDragStart = { rectStart = it; rectEnd = it },
                            onDrag = { change, _ -> rectEnd = change.position },
                            onDragEnd = {
                                val a = rectStart; val b = rectEnd
                                if (a != null && b != null) {
                                    val l = min(a.x, b.x); val r = max(a.x, b.x)
                                    val t = min(a.y, b.y); val bo = max(a.y, b.y)
                                    val tgt = assignTarget
                                    if (assignMode && tgt != null) {
                                        // MODE AFFECTATION (E2) : le cadre AFFECTE (add) en
                                        // un lot tous les projecteurs VISIBLES qu'il contient
                                        // à la cible (circuit / départ). Filtré effectiveHidden
                                        // comme la sélection voisine. Ne touche pas au décor.
                                        val enclos = ArrayList<String>()
                                        data.fixtures.forEach { f ->
                                            if (f.key in effectiveHidden) return@forEach
                                            val s = toScreen(f.px, f.py, canvas.x, canvas.y)
                                            if (s.x in l..r && s.y in t..bo) enclos.add(f.key)
                                        }
                                        if (enclos.isNotEmpty()) {
                                            // Jamais de retrait par le cadre ; voie disparue :
                                            // rien n'est écrit (garde du module pur).
                                            val written = when (tgt.kind) {
                                                CablingAssignTarget.Kind.SOCA ->
                                                    cabling.assignFrameOnVoie(enclos, tgt.voie)
                                                CablingAssignTarget.Kind.DMX ->
                                                    dmxCabling.assignFrameOnVoie(enclos, tgt.voie)
                                            }
                                            // Rectangle en mode affectation : UN seul retour
                                            // haptique à la fin (pas un par projecteur).
                                            cablingHaptic(hapticView, CablingHaptic.ofFrame(written))
                                        }
                                    } else if (maskMode) {
                                        // Le cadre MASQUE tout ce qu'il contient
                                        // (projecteurs + décor) : geste de nettoyage.
                                        val add = HashSet(hiddenElements)
                                        data.fixtures.forEach { f ->
                                            val s = toScreen(f.px, f.py, canvas.x, canvas.y)
                                            if (s.x in l..r && s.y in t..bo) add.add(f.key)
                                        }
                                        data.structure.forEachIndexed { i, p ->
                                            val s = toScreen(p.first, p.second, canvas.x, canvas.y)
                                            if (s.x in l..r && s.y in t..bo) {
                                                data.structureKeys.getOrNull(i)?.let { add.add(it) }
                                            }
                                        }
                                        wire?.instances?.forEach { inst ->
                                            val s = toScreen(inst.cx, inst.cy, canvas.x, canvas.y)
                                            if (s.x in l..r && s.y in t..bo) add.add(inst.id)
                                        }
                                        onSetHiddenElements(add)
                                    } else if (soloMode) {
                                        // MIROIR du cadre de masquage : le cadre AJOUTE
                                        // à l'ensemble solo tout ce qu'il contient
                                        // (projecteurs + décor), au lieu de le retirer.
                                        // Une fois l'ensemble non vide, le dessin ne
                                        // montre plus que ça (cf. effectiveHidden).
                                        val add = HashSet(soloElements)
                                        data.fixtures.forEach { f ->
                                            // Comme le tap solo : on saute ce qui est déjà
                                            // masqué (le masquage l'emporte). Sinon un cadre
                                            // sur une zone entièrement masquée peuplerait le
                                            // solo d'éléments invisibles → plan vide.
                                            if (f.key in hiddenElements) return@forEach
                                            val s = toScreen(f.px, f.py, canvas.x, canvas.y)
                                            if (s.x in l..r && s.y in t..bo) add.add(f.key)
                                        }
                                        data.structure.forEachIndexed { i, p ->
                                            val k = data.structureKeys.getOrNull(i) ?: return@forEachIndexed
                                            if (k in hiddenElements) return@forEachIndexed
                                            val s = toScreen(p.first, p.second, canvas.x, canvas.y)
                                            if (s.x in l..r && s.y in t..bo) add.add(k)
                                        }
                                        wire?.instances?.forEach { inst ->
                                            if (inst.id in hiddenElements) return@forEach
                                            val s = toScreen(inst.cx, inst.cy, canvas.x, canvas.y)
                                            if (s.x in l..r && s.y in t..bo) add.add(inst.id)
                                        }
                                        onSetSoloElements(add)
                                    } else {
                                        // Le cadre AJOUTE à la sélection (plusieurs cadres
                                        // cumulent), comme en 3D et sur iOS — Q2, §9.0.
                                        val enclosed = ArrayList<Int>()
                                        data.fixtures.forEachIndexed { i, f ->
                                            // Ne sélectionne que le visible : effectiveHidden
                                            // (pas hiddenElements brut), comme le tap-select
                                            // voisin — sinon le cadre ajoute des projecteurs
                                            // soloés hors écran après un aller-retour 3D↔plan
                                            // (ancre de zoom, armement d'étiquettes et ordre
                                            // d'adressage DMX pollués, sans anneau visible).
                                            if (f.key in effectiveHidden) return@forEachIndexed
                                            val s = toScreen(f.px, f.py, canvas.x, canvas.y)
                                            if (s.x in l..r && s.y in t..bo) enclosed.add(i)
                                        }
                                        val next = SelectionRules.frame(selected.toList(), enclosed)
                                        if (next.size != selected.size) { selected.clear(); selected.addAll(next) }
                                    }
                                }
                                rectStart = null; rectEnd = null
                            }
                        )
                    } else {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // Règle 3 du modèle : un glissé qui n'a PAS démarré
                            // sur l'étiquette active arrive forcément ici (la
                            // saisie d'étiquette consomme l'événement quand elle
                            // s'en empare) → il pane le plan ET désactive.
                            // Test de nullité d'abord : sans lui on écrirait un
                            // état observable à chaque événement tactile.
                            if (activeLabelKeys.isNotEmpty()) activeLabelKeys = emptySet()
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
                .pointerInput(scene, rectMode, assignMode, calibrating, maskMode, soloMode, hiddenElements, soloElements, measureMode, snapVerts, referencePlan, assignTarget) {
                    detectTapGestures(
                    // N12 — APPUI LONG : sélectionne le projecteur visé PUIS ouvre la
                    // fiche d'édition patch. Uniquement en interaction NORMALE — les
                    // modes qui s'approprient déjà le geste (rectangle/affectation/
                    // masquage/solo/mesure/calibrage) l'ignorent, comme le TAP.
                    onLongPress = onLongPress@{ pos ->
                        if (rectMode || assignMode || maskMode || soloMode || measureMode || calibrating ||
                            cal3Session != null) return@onLongPress
                        val lw = canvas.x; val lh = canvas.y
                        var lbest = -1; var lbestD = 44f * 44f
                        data.fixtures.forEachIndexed { i, f ->
                            // Ne vise que le visible (effectiveHidden), comme le tap-select.
                            if (f.key in effectiveHidden) return@forEachIndexed
                            val s = toScreen(f.px, f.py, lw, lh)
                            val dx = s.x - pos.x; val dy = s.y - pos.y
                            val d = dx * dx + dy * dy
                            if (d < lbestD) { lbestD = d; lbest = i }
                        }
                        if (lbest < 0) return@onLongPress
                        // Sélection unique (comme le tap), puis fiche d'édition patch.
                        selected.clear(); selected.add(lbest)
                        fixtureByKey[data.fixtures[lbest].key]?.let { onEditFixture(it) }
                    },
                    onTap = { tap ->
                        val w = canvas.x; val h = canvas.y
                        // ASSISTANT 3 POINTS : PRIORITAIRE — le toucher dit SEULEMENT
                        // « je suis ici » (l'endroit où l'on se tient) ; pan/zoom libres.
                        val cal3 = cal3Session
                        if (cal3 != null) {
                            val (cpx, cpy) = toPlan(tap.x, tap.y, w, h)
                            cal3.tap(Vec2(cpx.toDouble(), -cpy.toDouble()))
                            return@detectTapGestures
                        }
                        // MODE AFFECTATION (E2) : PRIORITAIRE. Le toucher affecte/retire
                        // le projecteur visé (toggle) à la cible courante — il ne
                        // sélectionne pas. Hit-test 34px comme le masquage, filtré
                        // effectiveHidden (ne vise que le visible). Retour visuel
                        // immédiat via la coloration réactive (.version).
                        if (assignMode) {
                            var bestF = -1; var bestFD = 34f * 34f
                            data.fixtures.forEachIndexed { i, f ->
                                if (f.key in effectiveHidden) return@forEachIndexed
                                val s = toScreen(f.px, f.py, w, h)
                                val dx = s.x - tap.x; val dy = s.y - tap.y
                                val d = dx * dx + dy * dy
                                if (d < bestFD) { bestFD = d; bestF = i }
                            }
                            if (bestF >= 0) toggleAssign(data.fixtures[bestF].key)
                            return@detectTapGestures
                        }
                        // MESURE : le 1er toucher pose le départ, le 2e l'arrivée,
                        // le 3e recommence. Prioritaire sur tout le reste — dans
                        // ce mode le toucher ne sélectionne ni ne masque rien.
                        if (measureMode) {
                            val bs = baseScale(w, h) * scale
                            val (tpx, tpy) = toPlan(tap.x, tap.y, w, h)
                            // Rayon d'accrochage exprimé à l'ÉCRAN (le doigt vise
                            // en pixels), converti en mm plan : la projection est
                            // une homothétie, donc un simple quotient.
                            val radius = if (bs > 0f) snapRadiusPx / bs else 0f
                            var pt: MeasurePoint? = null
                            // Priorité au centre de projecteur : c'est l'usage
                            // visé (« distance de centre à centre »), et il doit
                            // gagner contre un sommet de plan qui traînerait à
                            // côté.
                            var bestD = radius * radius
                            data.fixtures.forEach { f ->
                                // Accroche seulement ce qui est RÉELLEMENT visible :
                                // effectiveHidden (masqués ∪ complément du solo), pas
                                // hiddenElements brut — sinon la mesure vise des
                                // projecteurs soloés hors écran.
                                if (f.key in effectiveHidden) return@forEach
                                val dx = f.px - tpx; val dy = f.py - tpy
                                val d = dx * dx + dy * dy
                                if (d < bestD) {
                                    bestD = d
                                    pt = MeasurePoint(f.px, f.py, true, f.id ?: f.name)
                                }
                            }
                            if (pt == null) {
                                val rp = referencePlan
                                val sv = snapVerts
                                if (rp != null && sv != null && rp.transform.visible) {
                                    pt = snapDxfVertex(sv, rp.transform, tpx, tpy, radius)
                                }
                            }
                            // Aucun candidat proche : le point libre, tel quel.
                            val p = pt ?: MeasurePoint(tpx, tpy, false)
                            if (measureA == null || measureB != null) {
                                measureA = p; measureB = null
                            } else {
                                measureB = p
                            }
                            return@detectTapGestures
                        }
                        // MODE MASQUAGE : le toucher retire l'élément visé —
                        // celui-là SEUL (identité d'instance), projecteur ou
                        // décor. Le projecteur l'emporte : il est petit et
                        // presque toujours recouvert par une structure.
                        if (maskMode) {
                            var bestF = -1; var bestFD = 34f * 34f
                            data.fixtures.forEachIndexed { i, f ->
                                if (f.key in hiddenElements) return@forEachIndexed
                                val s = toScreen(f.px, f.py, w, h)
                                val dx = s.x - tap.x; val dy = s.y - tap.y
                                val d = dx * dx + dy * dy
                                if (d < bestFD) { bestFD = d; bestF = i }
                            }
                            if (bestF >= 0) {
                                onSetHiddenElements(hiddenElements + data.fixtures[bestF].key)
                                return@detectTapGestures
                            }
                            var bestKey: String? = null; var bestD = 44f * 44f
                            wire?.instances?.forEach { inst ->
                                if (inst.id in hiddenElements) return@forEach
                                val s = toScreen(inst.cx, inst.cy, w, h)
                                val dx = s.x - tap.x; val dy = s.y - tap.y
                                val d = dx * dx + dy * dy
                                if (d < bestD) { bestD = d; bestKey = inst.id }
                            }
                            data.structure.forEachIndexed { i, p ->
                                val k = data.structureKeys.getOrNull(i) ?: return@forEachIndexed
                                if (k in hiddenElements) return@forEachIndexed
                                val s = toScreen(p.first, p.second, w, h)
                                val dx = s.x - tap.x; val dy = s.y - tap.y
                                val d = dx * dx + dy * dy
                                if (d < bestD) { bestD = d; bestKey = k }
                            }
                            bestKey?.let { onSetHiddenElements(hiddenElements + it) }
                            return@detectTapGestures
                        }
                        // MODE SOLO : miroir exact du masquage — le toucher AJOUTE
                        // l'élément visé (celui-là SEUL) à l'ensemble solo au lieu de
                        // le retirer. Mêmes rayons, même arbitrage (le projecteur, plus
                        // petit et souvent recouvert, l'emporte sur le décor). On saute
                        // ce qui est déjà masqué : le masquage l'emporte, l'ajouter au
                        // solo n'y changerait rien.
                        if (soloMode) {
                            var bestF = -1; var bestFD = 34f * 34f
                            data.fixtures.forEachIndexed { i, f ->
                                if (f.key in hiddenElements) return@forEachIndexed
                                val s = toScreen(f.px, f.py, w, h)
                                val dx = s.x - tap.x; val dy = s.y - tap.y
                                val d = dx * dx + dy * dy
                                if (d < bestFD) { bestFD = d; bestF = i }
                            }
                            if (bestF >= 0) {
                                onSetSoloElements(soloElements + data.fixtures[bestF].key)
                                return@detectTapGestures
                            }
                            var bestKey: String? = null; var bestD = 44f * 44f
                            wire?.instances?.forEach { inst ->
                                if (inst.id in hiddenElements) return@forEach
                                val s = toScreen(inst.cx, inst.cy, w, h)
                                val dx = s.x - tap.x; val dy = s.y - tap.y
                                val d = dx * dx + dy * dy
                                if (d < bestD) { bestD = d; bestKey = inst.id }
                            }
                            data.structure.forEachIndexed { i, p ->
                                val k = data.structureKeys.getOrNull(i) ?: return@forEachIndexed
                                if (k in hiddenElements) return@forEachIndexed
                                val s = toScreen(p.first, p.second, w, h)
                                val dx = s.x - tap.x; val dy = s.y - tap.y
                                val d = dx * dx + dy * dy
                                if (d < bestD) { bestD = d; bestKey = k }
                            }
                            bestKey?.let { onSetSoloElements(soloElements + it) }
                            return@detectTapGestures
                        }
                        // Calibrage « je suis ici » : le tap pose une ancre (point
                        // plan touché ↔ position GPS courante).
                        val g = gps
                        if (calibrating && g != null) {
                            val (px, py) = toPlan(tap.x, tap.y, w, h)
                            val avg = averagedLatLon() ?: (g.latitude to g.longitude)
                            // Après une calibration 3 points, « je suis ici » REPART de
                            // zéro : ses deux ancres sont synthétiques, en remplacer une
                            // seule donnerait un mélange sans précision connue.
                            if (calibration.survey != null) calibration.reset()
                            calibration.addAnchor(
                                com.minou.mvrviewer.mvr.GeoAnchor(px, -py, avg.first, avg.second)
                            )
                            calibVersion++
                            onCalibrationChanged()
                            calibrating = false
                            return@detectTapGestures
                        }
                        // ÉTIQUETTES (règles 2 et 4 du modèle) : le tap est le
                        // SEUL geste qui active une étiquette, et il ne
                        // sélectionne alors PAS le projecteur — ce sont deux
                        // gestes distincts. Re-taper la même la désactive.
                        // Exclu du mode rectangle, où le tap sert à cocher /
                        // décocher des projecteurs dans la sélection multiple.
                        if (!rectMode) {
                            // ARBITRAGE (cf. labelTapTarget) : l'étiquette ne
                            // gagne que si le doigt est STRICTEMENT DANS une
                            // pastille RÉELLEMENT DESSINÉE — le tampon rempli au
                            // dessin fait seul autorité, plus rien n'est
                            // recalculé ici. Seule exception : le disque du
                            // symbole d'un projecteur, qui l'emporte pour que
                            // celui-ci reste sélectionnable en son centre même
                            // recouvert par une pastille.
                            val hitKey = labelTapTarget(labelHits, symbolHits, tap, activeLabelKeys)
                            if (hitKey != null) {
                                // RÈGLE : ce tap ne touche JAMAIS à `selected`.
                                // En multi-sélection l'ordre de sélection pilote
                                // l'adressage DMX séquentiel — le corrompre en
                                // manipulant une étiquette est inacceptable.
                                activeLabelKeys = when {
                                    // Re-taper un bloc armé désarme TOUT le groupe :
                                    // c'est le geste d'annulation attendu, et il
                                    // évite de laisser derrière soi des blocs armés
                                    // invisibles hors du cadre.
                                    hitKey in activeLabelKeys -> emptySet()
                                    // Le projecteur touché fait partie d'une
                                    // multi-sélection → on arme le MÊME bloc sur
                                    // toute la sélection (entrée « d'un coup »).
                                    else -> labelGroupForTap(data, selected, hitKey)
                                }
                                return@detectTapGestures
                            }
                            // Tap hors de toute pastille : on désactive, puis le
                            // tap suit son cours normal (sélection de projecteur).
                            if (activeLabelKeys.isNotEmpty()) activeLabelKeys = emptySet()
                        }
                        var best = -1; var bestD = 40f * 40f
                        data.fixtures.forEachIndexed { i, f ->
                            // Ne sélectionne que le visible : effectiveHidden (pas
                            // hiddenElements brut) — sinon le tap sélectionne un
                            // projecteur soloé hors écran après un aller-retour 3D↔plan.
                            if (f.key in effectiveHidden) return@forEachIndexed
                            val s = toScreen(f.px, f.py, w, h)
                            val dx = s.x - tap.x; val dy = s.y - tap.y
                            val d = dx * dx + dy * dy
                            if (d < bestD) { bestD = d; best = i }
                        }
                        // Toucher = remplace ; en multi (ou cadre armé) = ajoute / retire ;
                        // le vide désélectionne, sauf en multi (SelectionRules, Q2).
                        val next = SelectionRules.tap(selected.toList(), best.takeIf { it >= 0 }, selectionModes())
                        if (next != selected.toList()) { selected.clear(); selected.addAll(next) }
                    }
                    )
                }
                // Déplacement des étiquettes ARMÉES au doigt (règle 3 du modèle).
                // Un glissé sur l'une d'elles les déplace TOUTES du même vecteur.
                //
                // Ce pointerInput N'EXISTE PAS tant qu'aucune étiquette n'est
                // active : la clé `activeLabelKeys` le recrée à l'activation et le
                // détruit à la désactivation. C'est le point central de la
                // correction — plus aucune mesure, plus aucun test de boîte
                // pendant les gestes ordinaires, donc plus de pan volé ni de
                // saccade. DÉCLARÉ EN DERNIER à dessein : le dernier pointerInput
                // reçoit l'événement le premier en passe principale, donc
                // consommer ici annule proprement le pan/zoom
                // (detectTransformGestures s'arrête sur un change consommé).
                .pointerInput(scene, rectMode, assignMode, maskMode, soloMode, calibrating, measureMode, projectKey, activeLabelKeys, cal3Session) {
                    val keys = activeLabelKeys
                    if (keys.isEmpty()) return@pointerInput
                    // soloMode / affectation s'approprient le geste (comme maskMode) :
                    // pas de saisie d'étiquette pendant qu'on constitue le solo ou qu'on
                    // affecte sur le plan.
                    if (rectMode || assignMode || maskMode || soloMode || calibrating || measureMode ||
                        cal3Session != null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Un SEUL test, contre les boîtes des étiquettes ARMÉES :
                        // pas de recherche du plus proche, pas d'arbitrage avec le
                        // symbole du projecteur. Si le doigt se pose ailleurs, on
                        // ressort immédiatement et le geste redevient un pan
                        // ordinaire (qui désactivera l'étiquette, cf. le
                        // detectTransformGestures ci-dessus).
                        //
                        // C'est le SEUL endroit qui garde une marge de confort
                        // (LABEL_TOUCH_SLACK = 6 dp) : l'étiquette est déjà
                        // active, donc il n'y a plus aucune ambiguïté à arbitrer
                        // — la marge ne sert qu'à ne pas rater la saisie d'une
                        // pastille de quelques millimètres. Le TAP, lui, exige la
                        // stricte appartenance à la pastille (cf. labelBoxAt).
                        //
                        // En groupe, il suffit que le doigt se pose sur L'UNE des
                        // étiquettes armées : c'est bien elle que l'utilisateur
                        // saisit, et les autres suivent.
                        val grabbed = labelHits.any {
                            it.key in keys && distanceToBox(it, down.position) <= labelSlackPx
                        }
                        if (!grabbed) return@awaitEachGesture
                        var moved = false
                        val limit = LABEL_OFFSET_LIMIT.toPx()
                        fun shift(d: Offset) {
                            // Amplitude BORNÉE : au-delà, l'étiquette n'a plus de
                            // rapport lisible avec son projecteur, et elle sort du
                            // champ élargi par le culling → elle disparaîtrait. La
                            // borne est calculée sur le groupe ENTIER : sinon
                            // l'étiquette la plus contrainte s'arrêterait pendant
                            // que les autres continuent, et l'alignement que
                            // l'utilisateur venait de poser se déformerait.
                            val dd = clampGroupDelta(d, keys, labelShift, limit)
                            if (dd != Offset.Zero) {
                                for (k in keys) labelShift[k] = (labelShift[k] ?: Offset.Zero) + dd
                            }
                            // Le glissé d'étiquette est un geste comme un autre :
                            // on réarme l'horloge commune pour que le décor lourd
                            // (DXF, fils de fer) reste en détail réduit au lieu
                            // d'être reconstruit à la fréquence du doigt.
                            gestureClock[0] = android.os.SystemClock.uptimeMillis()
                            gesturing = true
                            labelDragVersion++
                        }
                        // DÉCALAGE PARASITE PENDANT UN PINCH — l'équivalent
                        // Compose du défaut corrigé côté iOS. Un pinch commence
                        // TOUJOURS par un seul doigt : si ce premier doigt s'est
                        // posé sur l'étiquette active, le glissé s'armait au
                        // franchissement du seuil (≈ 18 px) puis suivait ce doigt
                        // pendant tout le zoom — l'étiquette partait de plusieurs
                        // centimètres alors que l'utilisateur croyait seulement
                        // zoomer. On surveille donc le nombre de doigts appuyés à
                        // CHAQUE événement : dès qu'il y en a 2, on abandonne
                        // (armé ou non) et le geste redevient un pinch ordinaire.
                        //
                        // (C'est aussi pourquoi on n'utilise plus
                        // awaitTouchSlopOrCancellation + drag : ni l'un ni l'autre
                        // n'offre de point d'observation du nombre de pointeurs.)
                        val slop = viewConfiguration.touchSlop
                        var armed = false
                        var acc = Offset.Zero
                        // ÉCRITURE GARANTIE MÊME SI LE GESTE EST INTERROMPU.
                        // `try/finally` et non un simple enchaînement : ce
                        // pointerInput est détruit dès que l'une de ses clés change
                        // (retour à la 3D, changement de mode, arrivée d'une
                        // synchro), ce qui ANNULE la coroutine en pleine boucle
                        // d'événements. Sans le finally, tout le déplacement déjà
                        // appliqué à l'écran n'était jamais écrit sur disque et
                        // disparaissait à la réouverture du projet.
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } > 1) break
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val d = ch.positionChange()
                                if (!armed) {
                                    // On ne consomme qu'APRÈS le seuil de glissé : en
                                    // deçà c'est un tap, et il doit rester au
                                    // gestionnaire de tap (c'est lui qui désactive
                                    // l'étiquette). Le déplacement consommé par le
                                    // seuil n'est PAS appliqué : l'appliquer d'un coup
                                    // ferait sauter la pastille de ~18 px au démarrage.
                                    acc += d
                                    if (hypot(acc.x, acc.y) < slop) continue
                                    armed = true
                                    ch.consume()
                                } else {
                                    ch.consume(); shift(d); moved = true
                                }
                            }
                        } finally {
                            // Écriture disque au relâché seulement : un enregistrement
                            // par événement tactile ferait un accès fichier à 60 Hz.
                            // Elle part sur le fil du store (et non sur une coroutine
                            // du composable) car ce relâché est typiquement suivi d'un
                            // retour à la 3D, qui annulerait la coroutine en vol.
                            // Tant que la relecture n'a pas abouti, on ne réécrit
                            // pas le fichier : il contient des décalages que la
                            // table en mémoire n'a pas encore.
                            if (moved && projectKey != null && labelsLoaded[0]) {
                                com.minou.mvrviewer.mvr.ProjectStore.saveLabelOffsetsAsync(
                                    ctxPlan, projectKey, labelShift.mapValues { (_, o) -> o.x to o.y }
                                )
                            }
                        }
                    }
                }
        ) {
            canvas = Offset(size.width, size.height)
            val w = size.width; val h = size.height
            val bsPlan = baseScale(w, h) * scale

            // Dépendances de REDESSIN portées par des états mutables NON
            // observables : la transformée du plan DXF et la table des décalages
            // d'étiquettes. Les lire ici suffit à re-déclencher le dessin.
            refPlanTransformVersion.let { }
            labelDragVersion.let { }

            // DESSIN COMMUN écran ↔ PDF. Tout ce qui compose le document (fond
            // satellite, plan DXF, décor, silhouettes, pastilles, étiquettes,
            // barre d'échelle) passe par cette seule fonction : l'export « en
            // construction » ne peut donc pas diverger de ce que l'on voit.
            drawPlanContent(
                PlanRenderSpec(
                    data = data,
                    layerIndex = layerIndex,
                    pxPerMm = bsPlan,
                    centerPx = Offset(w / 2f + offset.x, h / 2f + offset.y),
                    planBg = planBg,
                    bgDark = bgDark,
                    inkColor = inkColor,
                    layerColors = options.layerColors,
                    colorMode = effectiveColorMode,
                    // Pastille pleine sous le symbole : seulement en affectation.
                    assigning = assignMode,
                    cablingColor = cablingColorMap,
                    cablingRingColor = cablingRingColorMap,
                    cablingText = cablingText,
                    showStructure = options.showStructure,
                    showLabels = options.showLabels,
                    labelFields = options.labelFields,
                    labelDetached = options.labelDetached,
                    labelSize = options.labelSize,
                    labelOffset = options.labelOffset,
                    hideLabelsWhenZoomedOut = options.hideLabelsWhenZoomedOut,
                    // CACHÉ EFFECTIF : fixtures, silhouettes, décor, étiquettes et
                    // légende partagent ce seul filtre → le solo agit partout d'un coup.
                    hiddenElements = effectiveHidden,
                    hiddenLayers = hiddenLayers,
                    structPaths = structPaths,
                    structDots = structDots,
                    fallbackDots = fallbackDots,
                    fixWire = fixWire,
                    fixPaths = fixPaths,
                    refTransform = referencePlan?.transform,
                    dxfPaths = dxfPaths,
                    rasterPlan = referencePlan?.raster,
                    satellite = satellite,
                    showSatellite = options.showSatellite,
                    satelliteOpacity = options.satelliteOpacity,
                    selected = selected.toSet(),
                    labelShift = labelShift,
                    labelShiftScale = 1f,
                    activeLabelKeys = activeLabelKeys,
                    measurer = measurer,
                    labelCache = labelCache,
                    lowDetail = gesturing,
                    labelHits = labelHits,
                    symbolHits = symbolHits,
                    // Au-dessus de la fiche / bannière quand elle couvre le coin bas-gauche.
                    scaleBarAnchor = Offset(20f, SelectionCardLayout.scaleBarY(
                        defaultY = h - 172f, barLeft = 20f, barRight = 20f + 150f * labelDensity.density,
                        card = selectionBarPx.takeIf { selectionBar != PlanSelectionBar.NONE }
                            ?.translate(-planBoundsPx.left, -planBoundsPx.top),
                        gap = 16f * labelDensity.density
                    ))
                )
            )

            // Position GPS de l'utilisateur (bleu), après calibrage.
            calibVersion.let { /* redraw à l'ajout d'ancre */ }
            // Repères de calibration : petites cibles ROUGES aux points réellement
            // mesurés (3 points) ou aux ancres posées — sous le point bleu, masqués
            // pendant l'assistant (iOS drawUserLocation, SPEC-calibration §2.6).
            val markers = CalibrationMarkers.points(calibration, showLocation, calibrating, cal3Session != null)
            if (markers.isNotEmpty()) {
                val mr = CalibrationMarkers.RADIUS_DP.dp.toPx()
                val mc = CalibrationMarkers.CROSS_HALF_DP.dp.toPx()
                val mStroke = androidx.compose.ui.graphics.drawscope.Stroke(CalibrationMarkers.STROKE_DP.dp.toPx())
                for ((mx, my) in markers) {
                    val sp = toScreen(mx, -my, w, h)
                    drawCircle(Color.Red, radius = mr, center = sp, style = mStroke)
                    drawLine(Color.Red, Offset(sp.x - mc, sp.y), Offset(sp.x + mc, sp.y), strokeWidth = mStroke.width)
                    drawLine(Color.Red, Offset(sp.x, sp.y - mc), Offset(sp.x, sp.y + mc), strokeWidth = mStroke.width)
                }
            }
            val g = gps
            // Pendant l'assistant 3 points, l'ANCIENNE calibration n'a plus cours :
            // son point bleu induirait en erreur (l'assistant dessine sa position
            // provisoire).
            if (showLocation && g != null && calibration.isCalibrated && cal3Session == null) {
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

            // MARQUAGE E2 — projecteurs affectés à la CIBLE d'affectation courante :
            // ANNEAU de la couleur du distributeur ciblé + petit LIBELLÉ « C<i> » /
            // « D<i> » près de chaque projecteur. Dessiné PAR-DESSUS les symboles, et
            // seulement en mode affectation → aide transitoire, pas dans l'export PDF.
            // assignedToTarget est réactif : l'affecté apparaît, le retiré disparaît
            // immédiatement. Rayon 18 > anneau câblage (~10,5) et sélection (13) : les
            // marquages restent distincts.
            if (assignMode && assignTargetColor != null && assignedToTarget.isNotEmpty()) {
                val ringCol = assignTargetColor
                // Encre du libellé : contraste sur la pastille couleur cible.
                val tagInk = if (0.299f * ringCol.red + 0.587f * ringCol.green +
                        0.114f * ringCol.blue > 0.6f) Color.Black else Color.White
                val tagLayout = if (assignIndexTag.isNotEmpty()) measurer.measure(
                    assignIndexTag,
                    style = TextStyle(
                        fontSize = 11.sp, color = tagInk,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                ) else null
                for (fx in assignedToTarget) {
                    val f = fixtureByPlanKey[fx] ?: continue
                    if (fx in effectiveHidden) continue
                    val p = toScreen(f.px, f.py, w, h)
                    if (p.x !in -24f..w + 24f || p.y !in -24f..h + 24f) continue
                    // Liseré de contraste dessous, anneau couleur cible par-dessus.
                    drawCircle(if (bgDark) Color.Black else Color.White, radius = 18f,
                        center = p, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                    drawCircle(ringCol, radius = 18f, center = p,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    if (tagLayout != null) {
                        val tw = tagLayout.size.width.toFloat()
                        val th = tagLayout.size.height.toFloat()
                        val bx = p.x + 20f; val by = p.y - 24f
                        drawRoundRect(
                            ringCol,
                            topLeft = Offset(bx - 3f, by - 2f),
                            size = androidx.compose.ui.geometry.Size(tw + 6f, th + 4f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                        )
                        drawText(tagLayout, topLeft = Offset(bx, by))
                    }
                }
            }

            // Cadre de sélection en cours (rectangle, ou cadre armé de la barre d'affectation).
            val a = rectStart; val b = rectEnd
            if (rectMode && a != null && b != null) {
                val tl = Offset(min(a.x, b.x), min(a.y, b.y))
                val sz = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
                drawRect(Color(0x33FFC400), topLeft = tl, size = sz)
                drawRect(Color(0xFFFFC400), topLeft = tl, size = sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }

            // ---- Mesure entre deux points ----
            // Dessinée en coordonnées ÉCRAN à partir des points PLAN : la ligne
            // et sa cote suivent donc le pan/zoom sans que la valeur bouge.
            run {
                val ma = measureA
                if (measureMode && ma != null) {
                    val pa = toScreen(ma.px, ma.py, w, h)
                    val mb = measureB
                    val pb = mb?.let { toScreen(it.px, it.py, w, h) }
                    if (pb != null) {
                        // Trait doublé (blanc dessous) : lisible sur fond clair
                        // comme sur un plan DXF dense.
                        drawLine(Color.White.copy(alpha = 0.75f), pa, pb, strokeWidth = 5f)
                        drawLine(MEASURE_COLOR, pa, pb, strokeWidth = 2.5f)
                    }
                    drawMeasureHandle(pa, ma.snapped)
                    if (pb != null) drawMeasureHandle(pb, mb.snapped)
                    // Cote au milieu du trait (ou à côté du 1er point tant qu'il
                    // n'y a pas de second : l'utilisateur voit que c'est armé).
                    val text = if (mb != null)
                        formatPlanDistanceMm(measureDistanceMm(ma, mb))
                    else sMeasureStartPlaced
                    val tl = measurer.measure(
                        text,
                        style = TextStyle(fontSize = 13.sp, color = Color.White)
                    )
                    val mid = if (pb != null) Offset((pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f)
                              else Offset(pa.x, pa.y - 26f)
                    val tw = tl.size.width.toFloat(); val th = tl.size.height.toFloat()
                    val tx = (mid.x - tw / 2f).coerceIn(4f, maxOf(4f, w - tw - 4f))
                    val ty = (mid.y - th - 10f).coerceIn(4f, maxOf(4f, h - th - 4f))
                    drawRoundRect(
                        MEASURE_COLOR,
                        topLeft = Offset(tx - 6f, ty - 3f),
                        size = androidx.compose.ui.geometry.Size(tw + 12f, th + 6f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )
                    drawText(tl, topLeft = Offset(tx, ty))
                }
            }

        }

        // ---- ASSISTANT 3 POINTS : minuterie + calque de dessin (repères, flèche,
        // points, position provisoire). Ils OBSERVENT la session et le GPS à la
        // place de la vue plan, qui n'est donc pas recomposée à chaque fix.
        cal3Session?.let { s ->
            ThreePointCalibrationTimer(s)
            ThreePointCalibrationOverlay(
                session = s, liveState = locFeed.location,
                toScreen = { v, sz -> toScreen(v.x.toFloat(), (-v.y).toFloat(), sz.width, sz.height) },
                pxPerMm = { sz -> baseScale(sz.width, sz.height) * scale }
            )
        }

        // (La barre d'affectation est posée EN BAS, plus loin : CablingAssignBar.)

        // Légende : couleur de chaque calque de projecteurs + compte (comme iOS).
        // La légende ne compte QUE les projecteurs réellement affichés : filtrée
        // par le CACHÉ EFFECTIF (masquage + solo), elle reflète exactement ce que
        // l'utilisateur voit — et donc ce qu'il exporte. Un calque entièrement
        // masqué ou hors-solo disparaît de la légende. Calcul HISSÉ hors du bloc :
        // la barre d'affectation doit savoir si la légende est affichée (dégagement).
        val legend = remember(data, effectiveHidden) {
            data.fixtures.asSequence()
                .filter { it.key !in effectiveHidden }
                .groupingBy { it.layer }.eachCount()
                .toList().sortedByDescending { it.second }
        }
        // Bandeau de mode (§3.14) : masquage ou isolement armé ; la légende passe dessous.
        val modeBanner = PlanModeBanner.of(maskMode, soloMode, rectMode)
        val legendPushPx = if (modeBanner == null || modeBannerPx.height <= 0f) 0 else {
            val naturalTop = planBoundsPx.top + CablingAssignBarPlacement.LEGEND_TOP_ANDROID * labelDensity.density
            kotlin.math.round(modeBannerPx.bottom + 8f * labelDensity.density - naturalTop).coerceAtLeast(0f).toInt()
        }
        // En mode CÂBLAGE (ou d'office en affectation), la légende passe de
        // « couleur → calque » à « couleur → distributeur » (ceux réellement utilisés).
        // Aucun distributeur utilisé (affectation qui commence) : pas de section
        // câblage, la légende des calques reste (iOS garde Types et Calques).
        val legendCablingMode = cablingLegendSection != null
        val legendShown = options.showLegend &&
            (if (legendCablingMode) cablingLegend.isNotEmpty() else legend.isNotEmpty())
        if (options.showLegend) {
            val cablingMode = legendCablingMode
            val show = legendShown
            if (show) {
                Surface(
                    color = Color.White.copy(alpha = 0.92f), contentColor = Color(0xFF222222),
                    shape = RoundedCornerShape(10.dp), shadowElevation = 3.dp,
                    modifier = Modifier.align(Alignment.TopStart)
                        // Place partagée avec la barre d'affectation (dégagement).
                        .padding(top = CablingAssignBarPlacement.LEGEND_TOP_ANDROID.dp,
                                 start = CablingAssignBarPlacement.LEGEND_START_ANDROID.dp)
                        // Un bandeau de mode (masquer / isoler) sous la pastille la pousse
                        // plus bas : jamais l'un sur l'autre.
                        .layoutOffset { androidx.compose.ui.unit.IntOffset(0, legendPushPx) }
                        .onGloballyPositioned { legendBoundsPx = it.boundsInRoot() }
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        if (cablingMode) {
                            // Titre de la famille (iOS « Socapex / lignes » / « Lignes DMX »).
                            cablingLegendSection?.let { sec ->
                                Text(stringResource(sec.titleRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = Color(0xFF666666),
                                    modifier = Modifier.padding(bottom = 2.dp))
                            }
                            cablingLegend.take(12).forEach { (name, col) ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                    androidx.compose.foundation.layout.Box(
                                        Modifier.width(10.dp).height(10.dp).background(
                                            col, androidx.compose.foundation.shape.CircleShape)
                                    ) {}
                                    Text("  $name", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (cablingLegend.size > 12) {
                                Text("  " + (cablingLegend.size - 12).let { n -> pluralStringResource(R.plurals.legend_more, n, n) }, style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            legend.take(10).forEach { (layer, n) ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                    androidx.compose.foundation.layout.Box(
                                        Modifier.width(10.dp).height(10.dp).background(
                                            if (options.layerColors) Color(LayerColors.colorInt(layerIndex, layer))
                                            else NEUTRAL_FIXTURE_GRAY,   // cohérent avec les pastilles
                                            androidx.compose.foundation.shape.CircleShape
                                        )
                                    ) {}
                                    Text("  $layer · $n", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (legend.size > 10) {
                                Text("  " + (legend.size - 10).let { n -> pluralStringResource(R.plurals.legend_more_layers, n, n) }, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        // Actions de la section « Placement sur le plan » du panneau d'étiquettes
        // (SPEC-menus-android §3.8) — elles vivent ici, avec les étiquettes saisies
        // et leurs décalages.
        val labelPlacement = LabelPlacementActions(
            // Sélection GROUPÉE d'étiquettes : les projecteurs déjà sélectionnés
            // (rectangle / multi-sélection), ou toute la famille « même type GDTF
            // sur le même calque » que l'étiquette armée — dans un plan réel, les
            // projecteurs d'un pont. Grisés sans sélection / sans étiquette saisie.
            onLabelsOfSelection = if (selected.isEmpty() || !options.showLabels) null else {
                {
                    activeLabelKeys = labelKeysForFixtures(
                        selected.mapNotNull { data.fixtures.getOrNull(it) },
                        options.labelFields, options.labelDetached, cablingText
                    )
                }
            },
            onLabelsSameType = if (activeLabelKeys.isEmpty()) null else {
                {
                    val origin = labelBlockFixtureKey(activeLabelKeys.first())
                    activeLabelKeys = labelKeysForFixtures(
                        // N'arme que le visible : effectiveHidden (pas hiddenElements
                        // brut) — sinon « étiquettes du même type » embrasse des
                        // projecteurs soloés hors écran.
                        sameTypeSameLayer(data, origin).filter { it.key !in effectiveHidden },
                        options.labelFields, options.labelDetached, cablingText
                    )
                }
            },
            armedCount = activeLabelKeys.size,
            onTurnOff = { activeLabelKeys = emptySet() },
            // Blocs séparés : les étiquettes saisies sont relâchées par l'effet
            // qui surveille `labelDetached`.
            onSetDetached = { options.labelDetached = it },
            // Filet de sécurité : remise à zéro de TOUS les décalages posés au
            // doigt (et effacement immédiat sur disque, sinon la réouverture les
            // ferait revenir).
            onResetOffsets = {
                labelShift.clear()
                // La table vidée fait désormais autorité : une relecture encore
                // en vol ne doit pas ressusciter les décalages.
                labelsLoaded[0] = true
                activeLabelKeys = emptySet()
                labelDragVersion++
                if (projectKey != null) {
                    com.minou.mvrviewer.mvr.ProjectStore.saveLabelOffsetsAsync(ctxPlan, projectKey, emptyMap())
                }
            }
        )
        // Recherche par Fixture ID : SÉLECTIONNE le(s) projecteur(s) et CADRE
        // dessus (comme le bouton loupe iOS — usage terrain « où est le #152 »).
        // Un même N° est souvent porté par PLUSIEURS projecteurs (multicellules,
        // doublons de patch) : on prend TOUT le groupe de N° exact, on le
        // sélectionne en entier (la surbrillance + le cadre de sélection le
        // montrent) et on cadre sur sa boîte englobante. À défaut de N° exact, on
        // retombe sur une correspondance partielle (N° puis nom).
        fun doSearch(): Boolean {
            val q = query.trim()
            if (q.isEmpty()) return false
            val exact = data.fixtures.indices.filter { data.fixtures[it].id.equals(q, true) }
            val matches = when {
                exact.isNotEmpty() -> exact
                else -> data.fixtures.indices
                    .filter { data.fixtures[it].id?.contains(q, true) == true }
                    .ifEmpty { data.fixtures.indices.filter { data.fixtures[it].name.contains(q, true) } }
            }
            if (matches.isEmpty()) return false
            selected.clear(); selected.addAll(matches)
            // Cadre = boîte englobante du groupe (coordonnées plan, mm), centrée.
            val fx = matches.map { data.fixtures[it] }
            val minX = fx.minOf { it.px }; val maxX = fx.maxOf { it.px }
            val minY = fx.minOf { it.py }; val maxY = fx.maxOf { it.py }
            val cX = (minX + maxX) / 2f; val cY = (minY + maxY) / 2f
            val spanX = maxX - minX; val spanY = maxY - minY
            val base = baseScale(canvas.x, canvas.y)
            // Zoom voulu : un projecteur (ou groupe ponctuel) → niveau ABSOLU
            // lisible (≈ 40 px/m) ; un groupe étendu → juste ce qu'il faut pour
            // que sa boîte tienne dans ~60 % du canvas (marge autour). Borné.
            val wanted = if (base <= 0f) 6f else if (spanX < 1f && spanY < 1f) {
                (0.04f / base).coerceIn(1f, 200f)
            } else {
                val fitX = if (spanX > 0f && canvas.x > 0f) (canvas.x * 0.6f) / (base * spanX) else Float.MAX_VALUE
                val fitY = if (spanY > 0f && canvas.y > 0f) (canvas.y * 0.6f) / (base * spanY) else Float.MAX_VALUE
                minOf(fitX, fitY).coerceIn(1f, 200f)
            }
            // Un seul projecteur : ne jamais dézoomer si l'on est déjà plus près.
            // Un groupe : on impose le cadrage (voir tout le groupe prime).
            val z = if (matches.size == 1) max(scale, wanted) else wanted
            val bs = base * z
            scale = z
            offset = Offset(-bs * (cX - data.cx), -bs * (cY - data.cy))
            return true
        }
        // N11 — barres d'outils ANCRABLES (défaut = vide). AnchoredToolbars rend les
        // 4 bords d'après `layout` en filtrant toolsPlan au disponible : rien tant
        // que l'utilisateur n'a pas personnalisé. Une barre vide n'est pas rendue.
        // Barre GAUCHE (préremplie sur tablette, §3.13) : elle évite la légende (en
        // haut à gauche sur Android, §8 D11), le bandeau de mode, la fiche / bannière,
        // les aides, la barre d'affectation, le panneau 3 points et les panneaux
        // flottants ouverts (à leur place affichée) — montée / descente, sinon à droite.
        AnchoredToolbars(layout = toolbarLayout, specs = toolsPlan, leading = LeadingBarClearance(
            occupants = {
                buildList {
                    if (legendShown) legendBoundsPx?.let { add(it) }
                    if (modeBanner != null) add(modeBannerPx)
                    if (selectionBar != PlanSelectionBar.NONE) add(selectionBarPx)
                    if (calibrating || measureMode) add(bottomAidPx)
                    if (assignTarget != null) add(assignBarAnchorPx)
                    if (cal3Session != null) add(cal3PanelAnchorPx)
                    addAll(panelFramesPlan.open(panels, otherOpen = exportMode))
                }
            },
            topLimitPx = { maxOf(planBoundsPx.top, topRowBottomPx) },
            bottomLimitPx = { if (planBoundsPx.height > 0f) planBoundsPx.bottom else Float.POSITIVE_INFINITY }
        ), top = TopBarClearance(
            // Barre du HAUT : DANS la rangée, entre ← et le groupe de droite — jamais
            // sous eux ni sur la pastille « Vue » / le bandeau de mode posés dessous.
            rowOccupants = { listOf(topRowBackPx, topRowTrailingPx) },
            container = { planBoundsPx }
        ))

        // ---- HABILLAGE (SPEC-menus-android §3.1, §3.3, §3.14) : rangée du haut
        // (← « Terminé », cadre, multi, ⋯), pastille « Vue » centrée, pastille de
        // synchro à droite sous la rangée, bandeau de mode sous la pastille. ----
        fun planTool(id: ToolId) = toolsPlan.firstOrNull { it.id == id }?.onInvoke?.invoke()
        val menuCtxPlan = MainMenuContext(
            surface = ProjectSurface.PLAN,
            hasRefPlan = referencePlan != null,
            hasSync = hasSync,
            hasCabling = hasCabling,
            colorModes = MainMenuModel.colorModes(hasSocaColoring, hasDmxColoring),
            hasHiddenItems = hiddenElements.isNotEmpty(),
            soloActive = soloElements.isNotEmpty(),
            hasSelection = selected.isNotEmpty()
        )
        val menuStatePlan = MainMenuState(
            measure = measureMode, labels = options.showLabels, layerColors = options.layerColors,
            structure = options.showStructure, legend = options.showLegend,
            hideItems = maskMode, isolate = soloMode
        )
        // Le plan traite ses entrées (bascules, modes, panneaux, export du plan) ; le
        // reste part à SceneScreen (destinations, import, PDF de câblage, fermeture).
        fun onMenuItemPlan(id: MainMenuItemId) {
            when (id) {
                MainMenuItemId.MEASURE -> planTool(ToolId.MEASURE)
                MainMenuItemId.LABELS -> options.showLabels = !options.showLabels
                MainMenuItemId.LAYER_COLORS -> options.layerColors = !options.layerColors
                MainMenuItemId.STRUCTURE -> options.showStructure = !options.showStructure
                MainMenuItemId.LEGEND -> options.showLegend = !options.showLegend
                MainMenuItemId.LABEL_SETTINGS -> onPanels(panels.open(ScenePanel.LABELS))
                MainMenuItemId.TOOLBAR_THIS_VIEW -> showCustomize = true
                MainMenuItemId.HIDE_ITEMS -> planTool(ToolId.MASK)
                MainMenuItemId.ISOLATE -> planTool(ToolId.SOLO)
                MainMenuItemId.SHOW_ALL -> onSetHiddenElements(emptySet())
                MainMenuItemId.CLEAR_SOLO -> onSetSoloElements(emptySet())
                MainMenuItemId.GPS_POSITION -> onPanels(panels.open(ScenePanel.GEO))
                MainMenuItemId.ADJUST_PLAN -> onPanels(panels.open(ScenePanel.REF_PLAN))
                MainMenuItemId.PLAN_PDF -> exportMode = true
                else -> onMainMenu(id)
            }
        }
        SceneTopRow(
            // Dans le plan, la flèche vaut « Terminé » (décision 2, §8 D19) : retour
            // à la 3D, ou au Câblage pendant une affectation.
            backDescription = stringResource(R.string.common_done),
            onBack = onBack,
            rect = toolsPlan.firstOrNull { it.id == ToolId.RECT }?.takeIf { SceneTopRowRules.showsRect(toolbarLayout) },
            multi = toolsPlan.firstOrNull { it.id == ToolId.MULTI_SELECT }?.takeIf { SceneTopRowRules.showsMulti(toolbarLayout) },
            menu = {
                MainMenuButton(
                    ctx = menuCtxPlan, state = menuStatePlan,
                    values = MainMenuValues(
                        colorMode = options.planColorMode,
                        onColorMode = { options.planColorMode = it },
                        background = options.background2D,
                        backgroundPresets = bg2dPresets(),
                        onBackground = { options.background2D = it },
                        onCustomBackground = { showBackgroundDialog = true }
                    ),
                    onItem = { onMenuItemPlan(it) }
                )
            },
            onRowBottom = { topRowBottomPx = it },
            onLeading = { topRowBackPx = it },
            onTrailing = { topRowTrailingPx = it }
        )
        ViewPill(
            surface = ProjectSurface.PLAN, current = CameraViewpoint.TOP,
            onViewpoint = {}, onShowPlan = {}, onShow3D = { (onShow3D ?: onBack)() },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp)
                .onGloballyPositioned { viewPillPx = it.boundsInRoot() }
        )
        // Pastille de synchro (projet partagé) : à droite sous la rangée, sous la
        // pastille « Vue » si elle la chevaucherait (§8 D21).
        syncBadge?.let { badge ->
            val d = labelDensity.density
            val topPx = PlanSyncBadgeRules.top(
                rowBottom = if (topRowBottomPx > 0f) topRowBottomPx else planBoundsPx.top + 52f * d,
                pill = viewPillPx, badgeWidth = syncBadgeWidthPx, containerRight = planBoundsPx.right,
                margin = 8f * d, gap = 6f * d
            )
            Box(Modifier.align(Alignment.TopEnd)
                .padding(top = ((topPx - planBoundsPx.top) / d).coerceAtLeast(0f).dp, end = 8.dp)
                .onGloballyPositioned { syncBadgeWidthPx = it.size.width.toFloat() }) { badge() }
        }
        modeBanner?.let { b ->
            val d = labelDensity.density
            val top = if (viewPillPx.height > 0f) ((viewPillPx.bottom - planBoundsPx.top) / d + 8f).dp else 100.dp
            PlanModeBannerBar(
                banner = b,
                // L'icône de tête quitte le mode (même chemin que l'outil).
                onExit = { if (maskMode) planTool(ToolId.MASK) else if (soloMode) planTool(ToolId.SOLO) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top, start = 16.dp, end = 16.dp)
                    .onGloballyPositioned { modeBannerPx = it.boundsInRoot() }
            )
        }

        // Compacte sous 480 dp de haut (téléphone en paysage), complète sinon.
        val cardStylePlan = SelectionCardLayout.style(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp)
        // PROJECTEUR GARDÉ VISIBLE (fiche compacte) : s'il tombe sous la fiche (ou à
        // moins de 28 dp), le plan GLISSE vers le haut (≈ 0,3 s) pour le poser juste
        // au-dessus d'elle ; jamais sous la pastille « Vue », la rangée du haut ni un
        // bandeau de mode (sinon rien ne bouge) ; un geste interrompt. Relancé à chaque
        // nouvelle sélection, quand la fiche change de hauteur (« Détails ») et à la
        // rotation (iOS SPEC-menus §8.4). Un plan encore « non touché » le reste : le
        // glissement compte comme un cadrage automatique.
        val revealKeyPlan = if (selectionBar == PlanSelectionBar.CARD)
            selected.singleOrNull()?.let { data.fixtures.getOrNull(it)?.key } else null
        val revealDensityPlan = labelDensity.density
        LaunchedEffect(revealKeyPlan, selectionBarPx, canvas, cardStylePlan) {
            val key = revealKeyPlan ?: return@LaunchedEffect
            if (!SelectionCardLayout.revealsSelection(cardStylePlan, isMultiSelect = false, hasSelection = true)) return@LaunchedEffect
            val card = selectionBarPx
            if (card.height <= 0f || canvas.x <= 0f || canvas.y <= 0f) return@LaunchedEffect
            val f = fixtureByPlanKey[key] ?: return@LaunchedEffect
            val sp = toScreen(f.px, f.py, canvas.x, canvas.y)
            val pRoot = Offset(sp.x + planBoundsPx.left, sp.y + planBoundsPx.top)
            val topLimit = maxOf(
                viewPillPx.bottom, topRowBottomPx, planBoundsPx.top,
                if (modeBanner != null) modeBannerPx.bottom else 0f
            )
            val dy = revealShift(pRoot, card, topLimit, REVEAL_MARGIN_DP * revealDensityPlan)
            if (dy >= 0f) return@LaunchedEffect
            val untouched = planUntouched()
            val from = offset
            val scale0 = scale
            var last = from
            val start = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                // Un geste (pan, pincement) a bougé le plan : il interrompt.
                if (offset != last || scale != scale0) break
                val t = ((now - start) / 300_000_000f).coerceIn(0f, 1f)
                val e = t * t * (3f - 2f * t)
                last = Offset(from.x, from.y + dy * e)
                offset = last
                if (t >= 1f) { if (untouched) autoFitView = scale to offset; break }
            }
        }
        // Mention OBLIGATOIRE de l'imagerie IGN (bas-droite). Elle MONTE au-dessus du
        // panneau 3 points ou de la barre d'affectation (iOS satelliteAttributionLift) :
        // jamais cachée dessous. L'opacité se règle dans le panneau Position GPS
        // SEULEMENT (décision 8) : plus de curseur flottant.
        val satShown = satellite
        val (satLift, satMentionOnTop) = run {
            val dpd = labelDensity.density
            fun Rect.inDp() = Rect(left / dpd, top / dpd, right / dpd, bottom / dpd)
            val satObstacles = buildList {
                if (cal3Session != null) add(cal3PanelAnchorPx.inDp())
                if (assignTarget != null) add(assignBarAnchorPx.inDp())
                // Fiche / bannière de sélection (contre le bas depuis le retrait de la
                // barre flottante, la mention passait dessous — constat émulateur).
                if (selectionBar != PlanSelectionBar.NONE) add(selectionBarPx.inDp())
            }
            val mention = satMentionAnchorPx.inDp()
            val lift = SatelliteUi.overlayLift(
                mention, null, satObstacles,
                topLimit = planBoundsPx.top / dpd + 60f
            )
            lift.dp to SatelliteUi.mentionOnTop(mention, lift, satObstacles)
        }
        val satCredit = SatelliteUi.creditRes(options.showSatellite, satShown != null, satShown?.year)
        satCredit?.let { credit ->
            SatelliteAttribution(credit, toolbarLayout, toolsPlan, lift = satLift,
                onAnchor = { satMentionAnchorPx = it }, onTop = satMentionOnTop)
        }

        // ---- PILE BAS-DROITE (§3.4) : Rechercher puis Recadrer. Elle monte au-dessus
        // de la fiche / bannière, des aides, de la barre d'affectation, du panneau 3
        // points et de la mention IGN (à sa place affichée : sa montée ne dépend pas
        // de la pile, pas de boucle). ----
        SearchReframeStack(
            showReframe = SceneTopRowRules.showsReframe(toolbarLayout),
            onReframe = { reframePlan() },
            onSearch = { q -> query = q; doSearch() },
            obstacles = buildList {
                if (selectionBar != PlanSelectionBar.NONE) add(selectionBarPx)
                if (calibrating || measureMode) add(bottomAidPx)
                if (assignTarget != null) add(assignBarAnchorPx)
                if (cal3Session != null) add(cal3PanelAnchorPx)
                if (satCredit != null) add(satMentionAnchorPx.translate(0f, -satLift.value * labelDensity.density))
            },
            topLimitPx = maxOf(planBoundsPx.top, topRowBottomPx)
        )

        // ---- BARRE D'AFFECTATION (SPEC-cablage-plan §4) ----
        // En BAS, centrée, déplaçable : 84 dp au-dessus du bas en portrait, contre
        // le bas (8 dp) en hauteur compacte. La légende Android est EN HAUT : la
        // barre ne la dégage (à droite, si la place le permet) que si elle
        // descend jusqu'à elle, et ne remonte jamais au-dessus d'elle (la règle
        // iOS, faite pour une légende en bas, l'écrasait) —
        // CablingAssignBarPlacement.assignBarInsetsTopLegend.
        val barTarget = assignTarget
        if (barTarget != null) {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
            val dens = androidx.compose.ui.platform.LocalDensity.current
            fun Rect.toDp() = Rect(left / dens.density, top / dens.density, right / dens.density, bottom / dens.density)
            val insets = CablingAssignBarPlacement.assignBarInsetsTopLegend(
                compact, planBoundsPx.toDp(), CablingAssignBarPlacement.BAR_WIDTH,
                assignBarHeightPx / dens.density,
                if (legendShown) legendBoundsPx?.toDp() else null
            )
            // Pastille d'une voie disparue : couleur du distributeur s'il existe encore.
            val bar = CablingAssignBarUi.display(barTarget, assignStatus, locale,
                CablingAssignBarUi.targetColorArgb(barTarget, cabling.distributors, dmxCabling.distributors))
            CablingAssignBar(
                display = bar,
                frameActive = rectMode,
                onPrevious = { cablingHaptic(hapticView, CablingHaptic.STEP); onStepAssign(-1) },
                onNext = { cablingHaptic(hapticView, CablingHaptic.STEP); onStepAssign(1) },
                onToggleFrame = { rectMode = !rectMode },
                onDone = { rectMode = false; onAssignDone() },
                onExit = { rectMode = false; onExitAssign() },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = insets.bottom.dp)
                    .layoutOffset(x = insets.leadingShift.dp)
                    // Cadre d'ANCRAGE (sans le décalage choisi au doigt) : sert à
                    // réserver sa hauteur au cadrage du plan.
                    .onGloballyPositioned {
                        assignBarTopPx = it.positionInRoot().y
                        assignBarHeightPx = it.size.height.toFloat()
                        assignBarAnchorPx = it.anchorBounds()
                    }
            )
        }

        // ---- ASSISTANT 3 POINTS : panneau en bas (SPEC-calibration-3-points §2) ----
        // Même place et même dégagement de la légende que la barre d'affectation ;
        // en hauteur compacte, textes secondaires et réglage du pas masqués.
        val cal3Open = cal3Session
        if (cal3Open != null) {
            val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
            val dens = androidx.compose.ui.platform.LocalDensity.current
            fun Rect.toDp() = Rect(left / dens.density, top / dens.density, right / dens.density, bottom / dens.density)
            val insets = CablingAssignBarPlacement.assignBarInsetsTopLegend(
                compact, planBoundsPx.toDp(), CablingAssignBarPlacement.BAR_WIDTH,
                cal3PanelHeightPx / dens.density,
                if (legendShown) legendBoundsPx?.toDp() else null
            )
            ThreePointCalibrationPanel(
                session = cal3Open, liveState = locFeed.location, denied = locFeed.denied,
                approximate = locFeed.approximate, compact = compact,
                onApply = { a, sv -> applyThreePoint(a, sv) },
                onClose = { closeThreePoint() },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = insets.bottom.dp)
                    .layoutOffset(x = insets.leadingShift.dp)
                    // Cadre d'ANCRAGE : sa hauteur est réservée au cadrage du plan.
                    .onGloballyPositioned {
                        cal3PanelTopPx = it.positionInRoot().y
                        cal3PanelHeightPx = it.size.height.toFloat()
                        cal3PanelAnchorPx = it.anchorBounds()
                    }
            )
        }

        // ---- Panneau d'export PDF « en construction » ----
        // Volontairement à part des modes de toucher : composer le document ne
        // s'approprie aucun geste, on continue de cadrer le plan librement entre
        // deux ajouts de page.
        if (exportMode) {
            MovablePanel(
                // Bas-GAUCHE (le coin bas-droit revient à la pile Rechercher / Recadrer),
                // nouvelle clé de position (§3.4, §8 D13).
                id = PanelLayoutStore.PanelID.EXPORT_PLAN_LEADING,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 76.dp, start = 12.dp).width(268.dp),
                onDisplayedBounds = { panelFramesPlan.other = it }
            ) {
            Surface(
                color = Color.White, contentColor = Color(0xFF111111),
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.width(268.dp)
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tool_export_pdf), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f))
                        Text(pluralStringResource(R.plurals.plan_export_views, exportViews.size, exportViews.size), style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF666666))
                    }
                    OutlinedTextField(
                        value = exportName,
                        onValueChange = { exportName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.plan_export_view_name), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    androidx.compose.material3.Button(
                        onClick = {
                            val n = exportName.trim().ifEmpty { String.format(sExportViewFmt, exportViews.size + 1) }
                            captureCurrentView(n)?.let {
                                exportViews.add(it)
                                exportName = ""
                                // Le document change : le PDF déjà produit ne le
                                // représente plus.
                                exportFile = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text(stringResource(R.string.plan_export_add_view)) }

                    if (exportViews.isNotEmpty()) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())
                                .padding(top = 6.dp)
                        ) {
                            exportViews.forEachIndexed { i, v ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text("${i + 1}.", style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF888888))
                                    Text(v.name, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                                            .clickable { renamingIndex = i; renamingText = v.name })
                                    IconButton(
                                        onClick = {
                                            if (i > 0) { exportViews.add(i - 1, exportViews.removeAt(i)); exportFile = null }
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.common_move_up), tint = Color(0xFF111111)) }
                                    IconButton(
                                        onClick = {
                                            if (i < exportViews.size - 1) { exportViews.add(i + 1, exportViews.removeAt(i)); exportFile = null }
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.common_move_down), tint = Color(0xFF111111)) }
                                    IconButton(
                                        onClick = { exportViews.removeAt(i); exportFile = null },
                                        modifier = Modifier.size(26.dp)
                                    ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = Color(0xFFC62828)) }
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.Button(
                            enabled = exportViews.isNotEmpty() && !exportBusy,
                            onClick = {
                                exportBusy = true
                                val pages = exportViews.toList()
                                val src = PlanExportSource(
                                    data = data, layerIndex = layerIndex,
                                    wire = wire, fixWire = fixWire, dxfPaths = dxfPaths,
                                    rasterPlan = referencePlan?.raster,
                                    satellite = satellite,
                                    legend = scene.fixtures.groupingBy { it.layerName }.eachCount()
                                        .toList().sortedByDescending { it.second },
                                    documentTitle = sPdfDocTitle
                                )
                                exportScope.launch {
                                    val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        runCatching { buildPlanPdf(ctxPlan, src, pages) }.getOrNull()
                                    }
                                    exportFile = f
                                    exportBusy = false
                                }
                            },
                            // Marge réduite, une ligne : sur 268 dp, la marge par défaut coupait
                            // « Générer » / « Envoyer » / « Imprimer » en deux (constat tranche E).
                            contentPadding = EXPORT_BUTTON_PADDING,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (exportBusy) androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.common_generate), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        val ready = exportFile
                        androidx.compose.material3.OutlinedButton(
                            enabled = ready != null, colors = panelButtonColors(),
                            onClick = { ready?.let { sharePlanPdf(ctxPlan, it) } },
                            contentPadding = EXPORT_BUTTON_PADDING,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.common_send), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                        androidx.compose.material3.OutlinedButton(
                            enabled = ready != null, colors = panelButtonColors(),
                            onClick = { ready?.let { printPlanPdf(ctxPlan, it) } },
                            contentPadding = EXPORT_BUTTON_PADDING,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.common_print), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    }
                    if (exportFile != null) {
                        Text(pluralStringResource(R.plurals.plan_export_ready, exportViews.size, exportViews.size),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                    }
                }
            }
            }
        }

        // Renommage d'une vue déjà ajoutée.
        renamingIndex?.let { idx ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { renamingIndex = null },
                title = { Text(stringResource(R.string.plan_export_rename_view)) },
                text = {
                    OutlinedTextField(
                        value = renamingText, onValueChange = { renamingText = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        exportViews.getOrNull(idx)?.let { old ->
                            // PlanViewCapture.name est mutable : renommer ne
                            // recompose pas la page, seul le titre change.
                            old.name = renamingText.trim().ifEmpty { old.name }
                            // Retrait/réinsertion : la liste observable ne peut
                            // pas voir un champ muté d'un élément déjà présent.
                            exportViews.removeAt(idx)
                            exportViews.add(idx, old)
                            exportFile = null
                        }
                        renamingIndex = null
                    }) { Text(stringResource(R.string.common_rename)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { renamingIndex = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        // Aide de calibrage (armée aussi depuis le panneau Position GPS).
        if (calibrating) {
            Surface(
                color = Color(0xFFFFC400), contentColor = Color(0xFF111111),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp, start = 16.dp, end = 16.dp)
                    .onGloballyPositioned { bottomAidPx = it.boundsInRoot() }
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(
                        if (gps == null) stringResource(R.string.calib_waiting_gps)
                        else if (calibration.anchors.isEmpty() || calibration.survey != null) stringResource(R.string.calib_tap_first)
                        else stringResource(R.string.calib_tap_second),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Position approximative seule (guide C8) : « Je suis ici » marche,
                    // mais le point posé hérite de son imprécision — le dire ici aussi.
                    if (locFeed.approximate) Text(
                        stringResource(R.string.location_approximate_short),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Aide de mesure : rappelle l'étape en cours ET la cote obtenue, en
        // grand — la cote dessinée sur le trait peut se retrouver sous le doigt.
        if (measureMode) {
            val ma = measureA; val mb = measureB
            Surface(
                color = MEASURE_COLOR, contentColor = Color.White,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp, start = 16.dp, end = 16.dp)
                    .onGloballyPositioned { bottomAidPx = it.boundsInRoot() }
            ) {
                Text(
                    when {
                        ma == null -> stringResource(R.string.measure_tap_start)
                        mb == null -> stringResource(R.string.measure_tap_end)
                        else -> formatPlanDistanceMm(measureDistanceMm(ma, mb)) +
                            (if (ma.snapped && mb.snapped) " · " + stringResource(R.string.measure_snapped) else "") +
                            " — " + stringResource(R.string.measure_tap_again)
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // FICHE / BANNIÈRE (§3.9) : construites par SceneScreen, les MÊMES qu'en 3D.
        // Croix = désélection ; toucher la fiche ne fait rien ; toucher le vide
        // désélectionne (inchangé). Au-dessus d'une barre ancrée en bas.
        val cardHost = selectionCard
        if (cardHost != null && selectionBar != PlanSelectionBar.NONE) {
            SelectionCardSlot(bottom = selectionCardBottom(toolbarLayout, toolsPlan), onBounds = { selectionBarPx = it }) {
                val one = fixtureByKey[selected.singleOrNull()?.let { data.fixtures.getOrNull(it) }?.key]
                if (selectionBar == PlanSelectionBar.CARD && one != null) {
                    cardHost.card(one, cardStylePlan) { selected.clear() }
                } else {
                    cardHost.banner(selected.size, cardStylePlan) { selected.clear() }
                }
            }
        }

        // N11 — panneau « Personnaliser la barre d'outils » (vue plan). Agit sur la
        // disposition de CETTE vue uniquement ; onLayoutChange remonte à SceneScreen
        // qui persiste globalement (par appareil).
        if (showCustomize) {
            ToolbarCustomizeSheet(
                title = stringResource(R.string.toolbar_title_plan),
                layout = toolbarLayout,
                catalog = toolsPlan.toCatalog(),
                onLayout = onLayoutChange,
                onReset = onLayoutReset,
                onDismiss = { showCustomize = false }
            )
        }

        // N11 — sélecteur de couleur du fond ouvert par l'outil BACKGROUND docké.
        if (showBackgroundDialog) {
            BackgroundColorDialog(
                title = stringResource(R.string.background_title_2d),
                initial = options.background2D,
                default = BackgroundColorStore.DEFAULT_2D,
                onColorChange = { options.background2D = it },
                onDismiss = { showBackgroundDialog = false }
            )
        }

        // ---- PANNEAUX DU BAS-GAUCHE (SPEC-menus-android §3.6–3.8) : Position GPS,
        // Plan importé, Réglage des étiquettes. Chacun MONTE au-dessus de ce qui
        // occupe le bas de sa colonne (fiche / bannière, aides, barre d'affectation,
        // panneau 3 points, mention IGN lue sans sa montée) et sa hauteur est bornée
        // sous la rangée du haut, la pastille « Vue », le bandeau de mode et la
        // LÉGENDE (haut-gauche, même colonne, §8 D11 : en portrait, le panneau Plan
        // importé la recouvrait) — PanelPlacementMath.floatingPanelSlot. Sans place
        // (paysage), le panneau garde sa hauteur minimale et son haut passe dessus. ----
        val panelTop = listOfNotNull(
            viewPillPx, modeBannerPx.takeIf { modeBanner != null }, legendBoundsPx?.takeIf { legendShown }
        )
        val panelTopLimit = maxOf(planBoundsPx.top, topRowBottomPx)
        val panelBottom = buildList {
            if (selectionBar != PlanSelectionBar.NONE) add(selectionBarPx)
            if (calibrating || measureMode) add(bottomAidPx)
            if (assignTarget != null) add(assignBarAnchorPx)
            if (cal3Session != null) add(cal3PanelAnchorPx)
            if (satCredit != null) add(satMentionAnchorPx)
        }
        if (panels.labels) BottomLeadingPanel(
            PanelLayoutStore.PanelID.LABELS_PLAN, panelTopLimit, panelTop, panelBottom,
            Modifier.zIndex(panels.zIndex(ScenePanel.LABELS)),
            onDisplayedBounds = { panelFramesPlan.labels = it }
        ) { maxH ->
            LabelSettingsPanel(options = options, surface = ProjectSurface.PLAN, placement = labelPlacement,
                onClose = { onPanels(panels.close(ScenePanel.LABELS)) }, maxContentHeight = maxH)
        }
        val rpPanel = referencePlan
        if (panels.refPlan && rpPanel != null) BottomLeadingPanel(
            PanelLayoutStore.PanelID.REF_PLAN_PLAN_LEADING, panelTopLimit, panelTop, panelBottom,
            Modifier.zIndex(panels.zIndex(ScenePanel.REF_PLAN)),
            onDisplayedBounds = { panelFramesPlan.refPlan = it }
        ) { maxH ->
            RefPlanPanel(
                plan = rpPanel, surface = ProjectSurface.PLAN, hiddenLayers = hiddenLayers, bgDark = bgDark,
                onTouched = onRefPlanTouched, onToggleLayer = onToggleLayer, onRemove = onRemoveRefPlan,
                onClose = { onPanels(panels.close(ScenePanel.REF_PLAN)) }, maxContentHeight = maxH
            )
        }
        if (panels.geo) BottomLeadingPanel(
            PanelLayoutStore.PanelID.GEO_POSITION_PLAN, panelTopLimit, panelTop, panelBottom,
            Modifier.zIndex(panels.zIndex(ScenePanel.GEO)),
            onDisplayedBounds = { panelFramesPlan.geo = it }
        ) { maxH ->
            GeoPositionPanel(
                // `calibVersion` lu : la calibration (non observable) vient d'être posée ici.
                content = GeoPanelContent.make(ProjectSurface.PLAN, calibVersion.let { calibration.isCalibrated },
                    options.showSatellite, satelliteCovered, satelliteStatus,
                    approximateLocation = locFeed.approximate),
                showLocation = showLocation,
                onShowLocation = { on -> showLocation = on; if (!on) calibrating = false },
                onToggleSatellite = {
                    SatelliteUi.onToolTap(satelliteCovered, options::toggleSatelliteByUser)
                        ?.let { SatelliteUi.toast(ctxPlan, it) }
                },
                opacity = options.satelliteOpacity, onOpacity = { options.satelliteOpacity = it },
                markerScale = options.gpsMarkerScale, onMarkerScale = { options.gpsMarkerScale = it },
                statusText = {
                    calibVersion.let { }
                    val (t, warning) = GeoPanelContent.status(calibration, locFeed.denied)
                    t.text() to warning
                },
                // Les calibrations ferment le panneau (l'assistant occupe le bas, l'aide
                // d'ancre aussi) puis se lancent ; Pro vérifié avant.
                onThreePoints = {
                    ProAccess.gate(GeoPanelContent.PRO_REASON) {
                        onPanels(panels.close(ScenePanel.GEO))
                        if (cal3Session == null && !cal3Starting) startThreePoint()
                    }
                },
                onHereAnchor = {
                    ProAccess.gate(GeoPanelContent.PRO_REASON) {
                        onPanels(panels.close(ScenePanel.GEO))
                        armHereAnchor()
                    }
                },
                // Garde Pro dans SceneScreen ; si la calibration a bien été vidée, le
                // plan se redessine (repères, point bleu).
                onResetCalibration = { onResetCalibration(); if (!calibration.isCalibrated) calibVersion++ },
                onClose = { onPanels(panels.close(ScenePanel.GEO)) },
                maxContentHeight = maxH
            )
        }
    }
}

/** Couleur d'affichage d'une entité DXF (0xRRGGBB) adaptée au fond : sur fond
 *  sombre on éclaircit les couleurs quasi noires, sur fond clair on assombrit le
 *  blanc — comme l'adaptation de contraste iOS. */
internal fun dxfDisplayColor(rgb: Int, bgDark: Boolean): Color {
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

internal val STRUCT_COLOR = Color(0xFF9AA0A6)
/**
 * Gris neutre d'un projecteur SANS couleur de calque (« couleurs par calque »
 * décoché). Usage HISTORIQUE, indépendant du câblage : ne pas le détourner pour
 * le « non câblé » (spec canonique phase 4 → constante dédiée ci-dessous).
 */
internal val NEUTRAL_FIXTURE_GRAY = Color(0xFF6E6E73)
/**
 * Teinte « NON CÂBLÉ » (spec canonique phase 4) — projecteur non affecté en mode
 * câblage (Socapex / Ligne DMX). Valeur IDENTIQUE iOS/Android = 0xFF737373. Dédiée :
 * distincte de [NEUTRAL_FIXTURE_GRAY] (« sans couleur de calque ») pour que faire
 * évoluer l'une n'entraîne jamais l'autre. Partagée écran ↔ PDF ↔ légende.
 */
internal val CABLING_UNASSIGNED_GRAY = Color(0xFF737373)
private val DXF_COLOR = Color(0xB3384B66)         // bleu-gris (sous-couche, fond clair)
private val DXF_COLOR_DARK_BG = Color(0xB39FC0E4) // bleu-gris clair (fond sombre)

// Presets de couleur de fond de la vue plan (nom, ARGB) — mêmes choix qu'iOS.
@androidx.compose.runtime.Composable
private fun bg2dPresets(): List<Pair<String, Long>> = listOf(
    stringResource(R.string.color_white) to 0xFFFFFFFFL, stringResource(R.string.color_light_grey) to 0xFFE9E9ECL, stringResource(R.string.color_beige) to 0xFFF2ECDDL,
    stringResource(R.string.color_charcoal) to 0xFF1C1C1EL, stringResource(R.string.color_black) to 0xFF000000L
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
internal object PlanWireCache {
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

/**
 * TUILE de tracé : un morceau de dessin (Path) avec sa boîte englobante et le
 * nombre de segments qu'il porte.
 *
 * POURQUOI (parité iOS) : côté iOS la vue plan a été fluidifiée par un DXF
 * « tuilé culé au viewport ». Android dessinait au contraire des Path
 * MONOLITHIQUES couvrant tout le plan, reparcourus INTÉGRALEMENT à chaque frame
 * (Skia clippe mais doit lire tous les verbes) → coût dominant du pan/zoom sur
 * un gros show. En découpant le dessin en tuiles et en ne traçant que celles qui
 * coupent l'écran, le coût par frame suit la surface VISIBLE, pas la taille
 * totale du fichier.
 */
internal class PathTile(
    var minX: Float, var minY: Float, var maxX: Float, var maxY: Float,
    val path: androidx.compose.ui.graphics.Path,
    var segs: Int
) {
    fun grow(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }
    /** Coupe-t-elle le rectangle [vx0,vy0]–[vx1,vy1] ? (test AABB.) */
    fun visible(vx0: Float, vy0: Float, vx1: Float, vy1: Float): Boolean =
        maxX >= vx0 && minX <= vx1 && maxY >= vy0 && minY <= vy1
}

/**
 * Accumulateur de tuiles : range des sous-tracés dans une grille spatiale — la
 * tuile est choisie sur le CENTRE du sous-tracé, et sa bbox est agrégée sur la
 * géométrie réelle (le cull reste donc exact même pour un tracé qui déborde de
 * sa case). `tileSize` est en unités du repère de dessin considéré.
 */
internal class TileBucket(private val tileSize: Float) {
    private val tiles = HashMap<Long, PathTile>()
    private fun key(x: Float, y: Float): Long {
        val tx = Math.floor((x / tileSize).toDouble()).toInt()
        val ty = Math.floor((y / tileSize).toDouble()).toInt()
        return (tx.toLong() shl 32) xor (ty.toLong() and 0xffffffffL)
    }
    fun tileAt(cx: Float, cy: Float): PathTile =
        tiles.getOrPut(key(cx, cy)) {
            PathTile(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                androidx.compose.ui.graphics.Path(), 0)
        }
    fun toList(): List<PathTile> = ArrayList(tiles.values)
}

/**
 * Plafond ADAPTATIF de points DXF conservés. Un SkPath natif coûte ~24 o/point et
 * l'app a un historique d'OOM sur gros show ; le garde-fou fixe (1,5 M) était
 * généreux et aveugle au heap réel. On le borne désormais au heap max de la VM —
 * plus il est petit, plus on plafonne bas — entre 300 k et 1,5 M points.
 */
internal fun dxfPointCap(): Int {
    val maxMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L))
    return (maxMb * 4000L).coerceIn(300_000L, 1_500_000L).toInt()
}

/** Tracés DXF pré-construits, en coordonnées LOCALES du DXF (y déjà inversé). */
internal class DxfPaths(
    /** (calque, couleur) → TUILES de traits : masquer un calque ne reconstruit RIEN. */
    val strokeTiles: Map<Pair<String, Int>, List<PathTile>>,
    /**
     * (calque, couleur, plein) → zone remplie, MONOLITHIQUE : les remplissages
     * (HATCH/SOLID) sont peu nombreux, et l'EvenOdd d'une entité doit rester
     * groupé pour dessiner ses trous — on ne les tuile donc pas.
     */
    val fills: Map<Triple<String, Int, Boolean>, androidx.compose.ui.graphics.Path>,
    val points: Int,
    /** Taille de tuile (unités locales DXF) : sert de marge au cull. */
    val tileSize: Float
) {
    /** Assez léger pour RESTER dessiné pendant un pan/zoom (sinon il s'efface). */
    val light: Boolean get() = points < 60_000
}

/** Fil de fer des structures prêt à dessiner (coordonnées plan), tuilé. */
internal class StructPaths(
    val byLayer: Map<String, List<PathTile>>,
    val edges: Int,
    val tileSize: Float,
    /**
     * Centres (repère plan) des instances que le budget mémoire a écartées du
     * fil de fer. Elles gardent un POINT : un objet écarté par un garde-fou ne
     * doit jamais disparaître sans laisser de trace.
     */
    val overflowDots: List<Pair<Float, Float>> = emptyList()
) {
    val light: Boolean get() = edges < 40_000
}

/** Sépare calque et spec dans les clés de `buildFixturePaths`. */
internal const val PATH_KEY_SEP = '\u0000'

/**
 * Arêtes des structures, transformées en coordonnées PLAN une fois pour toutes
 * (un tracé par calque). Avant, ces multiplications matricielles étaient
 * refaites pour chaque arête à CHAQUE frame.
 */
internal fun buildStructurePaths(
    wf: PlanWireframe.Built, hidden: Set<String>
): StructPaths {
    // Taille de tuile depuis l'étendue des centres d'instance (coordonnées plan).
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (inst in wf.instances) {
        if (inst.id in hidden || wf.edgesByKey[inst.key] == null) continue
        if (inst.cx < minX) minX = inst.cx; if (inst.cx > maxX) maxX = inst.cx
        if (inst.cy < minY) minY = inst.cy; if (inst.cy > maxY) maxY = inst.cy
    }
    val ext = max(maxX - minX, maxY - minY)
    val tileSize = if (!ext.isFinite() || ext <= 0f) 1f else (ext / 32f).coerceAtLeast(1e-3f)

    val buckets = HashMap<String, TileBucket>()
    val overflow = ArrayList<Pair<Float, Float>>()
    var count = 0
    for (inst in wf.instances) {
        if (inst.id in hidden) continue
        val edges = wf.edgesByKey[inst.key] ?: continue
        val world = inst.world
        // Garde-fou mémoire. On CONTINUE la boucle (au lieu de la rompre) pour
        // donner un point à chaque instance restante : depuis que le fil de fer
        // sait lire les .glb, un très gros show peut dépasser ce budget, et une
        // sortie sèche ferait disparaître de l'écran toute la fin de la liste.
        if (count > MAX_STRUCT_PATH_SEGMENTS) { overflow.add(inst.cx to inst.cy); continue }
        // Un tracé par calque, découpé en tuiles : chaque instance atterrit dans
        // la tuile de son centre (inst.cx/cy sont déjà en repère plan).
        val tile = buckets.getOrPut(inst.layer) { TileBucket(tileSize) }.tileAt(inst.cx, inst.cy)
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
            tile.path.moveTo(wax, -way); tile.path.lineTo(wbx, -wby)
            tile.grow(wax, -way); tile.grow(wbx, -wby)
            tile.segs += 1
        }
    }
    return StructPaths(buckets.mapValues { it.value.toList() }, count, tileSize, overflow)
}

/**
 * Structures SANS fil de fer (ex. géométrie .glb) → un point chacune, sous forme
 * de TRACÉ : un sous-chemin de longueur nulle dessiné en bout rond donne un
 * point, et Skia élague ce qui sort de l'écran. (Un `drawPoints` aurait fait un
 * appel natif par point — c'est ce que faisait déjà l'ancienne boucle de
 * `drawCircle`, mais sans profiter de l'élagage.)
 */
internal fun dotsPath(points: List<Pair<Float, Float>>): androidx.compose.ui.graphics.Path {
    val p = androidx.compose.ui.graphics.Path()
    for ((x, y) in points) { p.moveTo(x, y); p.lineTo(x, y) }
    return p
}

internal fun structureDots(wf: PlanWireframe.Built?, hidden: Set<String>): List<Pair<Float, Float>> =
    if (wf == null || wf.isEmpty) emptyList()
    else wf.instances.mapNotNull {
        if (it.id !in hidden && wf.edgesByKey[it.key] == null) it.cx to it.cy else null
    }

/** Silhouettes des projecteurs, prêtes à dessiner (tuilées). */
internal class FixturePaths(
    val byKey: Map<String, List<PathTile>>,
    /** Specs réellement TRACÉES (le budget peut en écarter) → pastille sinon. */
    val specs: Set<String>,
    val segments: Int,
    val tileSize: Float
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
internal fun buildFixturePaths(
    data: PlanData, fw: PlanWireframe.FixtureWire, hidden: Set<String>
): FixturePaths {
    // Taille de tuile depuis l'étendue des centres de projecteurs (repère plan).
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (f in data.fixtures) {
        if (f.px < minX) minX = f.px; if (f.px > maxX) maxX = f.px
        if (f.py < minY) minY = f.py; if (f.py > maxY) maxY = f.py
    }
    val ext = max(maxX - minX, maxY - minY)
    val tileSize = if (!ext.isFinite() || ext <= 0f) 1f else (ext / 32f).coerceAtLeast(1e-3f)

    val buckets = HashMap<String, TileBucket>()
    val specs = HashSet<String>()
    var segments = 0
    for (f in data.fixtures) {
        if (f.key in hidden) continue
        val spec = f.spec?.trim() ?: continue
        val edges = fw.edgesBySpec[spec] ?: continue
        // Budget dépassé : on n'ouvre plus de nouvelle spec, mais on finit
        // celles déjà commencées (sinon des projecteurs identiques seraient
        // dessinés différemment selon leur rang dans la liste).
        if (segments > MAX_FIXTURE_PATH_SEGMENTS && spec !in specs) continue
        val world = f.world
        specs.add(spec)
        // Un tracé par « calque + spec », découpé en tuiles : chaque projecteur
        // atterrit dans la tuile de son centre (f.px/py sont en repère plan).
        val tile = buckets.getOrPut(f.layer + PATH_KEY_SEP + spec) { TileBucket(tileSize) }
            .tileAt(f.px, f.py)
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
            tile.path.moveTo(wax, -way); tile.path.lineTo(wbx, -wby)
            tile.grow(wax, -way); tile.grow(wbx, -wby)
            tile.segs += 1
        }
    }
    return FixturePaths(buckets.mapValues { it.value.toList() }, specs, segments, tileSize)
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
internal fun buildDxfPaths(plan: com.minou.mvrviewer.mvr.DxfPlan): DxfPaths {
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
    // ---- Traits (polylignes) : TUILÉS pour le culling au viewport ----
    // Passe A : étendue globale (coordonnées locales, Y déjà inversé) → tuile.
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (pl in plan.polylines) {
        val pts = pl.points
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = -pts[i + 1]; i += 2
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
    }
    val ext = max(maxX - minX, maxY - minY)
    // ~48 tuiles sur le plus grand côté : assez fin pour bien culer, assez gros
    // pour ne pas exploser le nombre de Path natifs.
    val tileSize = if (!ext.isFinite() || ext <= 0f) 1f else (ext / 48f).coerceAtLeast(1e-3f)

    // Passe B : range chaque polyligne dans la tuile de son CENTRE.
    val cap = dxfPointCap()
    val buckets = HashMap<Pair<String, Int>, TileBucket>()
    var drawn = 0
    loop@ for (pl in plan.polylines) {
        val pts = pl.points
        if (pts.size < 4) continue
        // bbox de la polyligne → centre pour choisir la tuile.
        var pmnx = Float.MAX_VALUE; var pmny = Float.MAX_VALUE
        var pmxx = -Float.MAX_VALUE; var pmxy = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = -pts[i + 1]; i += 2
            if (x < pmnx) pmnx = x; if (x > pmxx) pmxx = x
            if (y < pmny) pmny = y; if (y > pmxy) pmxy = y
        }
        val tile = buckets.getOrPut(pl.layer to pl.color) { TileBucket(tileSize) }
            .tileAt((pmnx + pmxx) * 0.5f, (pmny + pmxy) * 0.5f)
        i = 0
        var first = true
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = -pts[i + 1]; i += 2
            if (first) { tile.path.moveTo(x, y); first = false } else tile.path.lineTo(x, y)
            tile.grow(x, y)
        }
        if (pl.closed && pts.size >= 4) { tile.path.lineTo(pts[0], -pts[1]); tile.grow(pts[0], -pts[1]) }
        val segs = pts.size / 2
        tile.segs += segs
        drawn += segs
        if (drawn > cap) break@loop  // garde-fou mémoire ADAPTATIF (construit UNE fois)
    }
    val strokeTiles = buckets.mapValues { it.value.toList() }
    return DxfPaths(strokeTiles, fills, drawn, tileSize)
}

/**
 * Zone sensible d'une étiquette à l'écran (remplie par le dessin, cf. PASSE 2) :
 * exactement la pastille DESSINÉE, aux mêmes coordonnées que le drawRoundRect.
 * C'est cette identité boîte dessinée ↔ boîte testée qui rend l'arbitrage
 * honnête (cf. labelBoxAt).
 */
internal class LabelHit(
    val key: String, val x: Float, val y: Float, val w: Float, val h: Float
)

/**
 * Disque du symbole d'un projecteur à l'écran, lui aussi rempli par le dessin :
 * c'est la seule exception à « la pastille dessinée gagne » (cf. labelTapTarget).
 */
internal class SymbolHit(val x: Float, val y: Float, val r: Float)

/** Distance du point au rectangle (0 s'il est dedans). */
private fun distanceToBox(hi: LabelHit, p: Offset): Float {
    val dx = max(hi.x - p.x, max(0f, p.x - (hi.x + hi.w)))
    val dy = max(hi.y - p.y, max(0f, p.y - (hi.y + hi.h)))
    return kotlin.math.hypot(dx, dy)
}

/**
 * Étiquette sous le doigt, ou null. Utilisé UNIQUEMENT par le TAP (l'activation
 * / la désactivation) — le glissé, lui, ne teste que la boîte de l'étiquette
 * déjà active, avec une marge de confort (cf. LABEL_TOUCH_SLACK).
 *
 * ARBITRAGE ÉTIQUETTE ↔ PROJECTEUR — sur la GÉOMÉTRIE RÉELLE du doigt, et rien
 * d'autre : l'étiquette ne gagne que si le point est STRICTEMENT DANS sa
 * pastille dessinée, SANS marge de confort. Sinon le tap file à la recherche de
 * projecteur, comportement inchangé.
 *
 * Pourquoi ni l'un ni l'autre des ordres « naturels » ne marche :
 *  - tester les étiquettes D'ABORD avec une marge : au dézoom, les pastilles
 *    (taille écran constante) recouvrent tout le plan et volaient la sélection ;
 *  - tester les projecteurs D'ABORD : au zoom de travail la boîte de
 *    l'étiquette est ENTIÈREMENT dans la cible tactile du projecteur (rayon 40 px
 *    ici, cf. la recherche de fixture, contre une pastille collée au symbole) →
 *    au-delà d'environ 0,265 px/mm plus AUCUNE étiquette n'était activable : la
 *    fonctionnalité était morte sur les 3/4 de la plage de zoom.
 *
 * La pastille est ce que l'utilisateur VOIT et VISE : un tap 10 pt sous un
 * projecteur au dézoom n'est pas dans la pastille (placée au-dessus du symbole
 * par défaut) et sélectionne donc bien le projecteur ; au zoom la pastille est
 * une cible large et précise, donc activable.
 *
 * `preferKeys` (les étiquettes armées) l'emportent quand elles contiennent le
 * point : on doit toujours pouvoir les désactiver, même chevauchées. Sinon
 * on retient la DERNIÈRE boîte contenant le point, c'est-à-dire celle dessinée
 * PAR-DESSUS les autres — donc celle que l'utilisateur voit.
 */
internal fun labelBoxAt(hits: List<LabelHit>, p: Offset, preferKeys: Set<String>): String? {
    var found: String? = null
    for (hi in hits) {
        if (p.x < hi.x || p.x > hi.x + hi.w || p.y < hi.y || p.y > hi.y + hi.h) continue
        if (hi.key in preferKeys) return hi.key
        found = hi.key
    }
    return found
}

/**
 * Arbitrage COMPLET du tap entre étiquettes et projecteurs, en un seul endroit
 * pour qu'il n'y ait plus rien à faire diverger. Les deux tampons proviennent de
 * la passe de dessin : le test tactile ne reconstruit AUCUNE géométrie, ce qui
 * était la cause racine des rustines successives (préfiltre en pixels ignorant
 * le rayon du symbole, culling non répliqué, conditions de dessin absentes du
 * test). Conséquence recherchée : une étiquette non dessinée est intouchable par
 * construction, une étiquette dessinée est toujours touchable.
 *
 * EXCEPTION : si le point tombe dans le disque du SYMBOLE d'un projecteur, le
 * projecteur gagne (retour null). Sinon une pastille recouvrant son centre le
 * rendait définitivement insélectionnable — et l'utilisateur n'a alors aucun
 * moyen de deviner qu'il faut d'abord déplacer l'étiquette.
 */
internal fun labelTapTarget(
    labels: List<LabelHit>, symbols: List<SymbolHit>, p: Offset, preferKeys: Set<String>
): String? {
    for (s in symbols) {
        val dx = p.x - s.x; val dy = p.y - s.y
        if (dx * dx + dy * dy <= s.r * s.r) return null
    }
    return labelBoxAt(labels, p, preferKeys)
}

/** Couleur `src` d'opacité `alpha` composée sur `dst` (source-over opaque). */
internal fun compositeOver(src: Color, alpha: Float, dst: Color): Color = Color(
    src.red * alpha + dst.red * (1f - alpha),
    src.green * alpha + dst.green * (1f - alpha),
    src.blue * alpha + dst.blue * (1f - alpha)
)

/** Luminance relative WCAG (canal linéarisé). */
private fun relativeLuminance(c: Color): Float {
    fun lin(v: Float) = if (v <= 0.03928f) v / 12.92f
                        else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
}

/** Rapport de contraste WCAG entre deux couleurs opaques (1 → 21). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a); val lb = relativeLuminance(b)
    val hi = max(la, lb); val lo = min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * Encre d'étiquette ramenée à un CONTRASTE MINIMUM avec le voile de la pastille.
 *
 * Le défaut corrigé : la palette de calques contient des teintes très claires
 * (jaune, cyan, vert tendre) qui, brutes sur le voile blanc du fond par défaut,
 * ne se lisent plus du tout — et symétriquement des teintes très sombres sur le
 * voile noir d'un fond sombre. Un simple seuil de luminosité (ce qu'on faisait)
 * ne garantit rien : c'est le RAPPORT de contraste avec le voile réel qui
 * compte, et il doit être imposé DANS LES DEUX SENS.
 *
 * D'où la méthode : on essaie les DEUX cibles (noir et blanc) et on garde la
 * meilleure, au lieu de déduire la cible d'un seuil de luminosité du voile. Un
 * seuil se trompe précisément là où ça compte — sur les voiles moyens (un fond
 * de plan gris donne un voile à mi-chemin), où le côté « logique » peut très
 * bien ne pas atteindre 4,5:1 alors que l'autre y arrive. On mélange par paliers
 * et on s'arrête au PREMIER palier qui atteint le seuil : la teinte du calque
 * reste reconnaissable, ce qu'un repli brutal en noir ou blanc perdrait. Si
 * aucune des deux cibles n'atteint le seuil (voile gris moyen, cas physique où
 * rien ne le peut), on garde le meilleur contraste obtenu.
 */
internal fun readableInk(tint: Color, veil: Color): Color {
    var best = tint
    var bestRatio = contrastRatio(tint, veil)
    if (bestRatio >= LABEL_MIN_CONTRAST) return tint
    var bestMix = Float.MAX_VALUE   // quantité de mélange du meilleur candidat retenu
    for (target in arrayOf(Color.Black, Color.White)) {
        for (step in 1..10) {
            val t = step / 10f
            val mixed = Color(
                tint.red + (target.red - tint.red) * t,
                tint.green + (target.green - tint.green) * t,
                tint.blue + (target.blue - tint.blue) * t
            )
            val r = contrastRatio(mixed, veil)
            if (r >= LABEL_MIN_CONTRAST) {
                // Atteint : ne concurrence que les autres candidats atteignant le
                // seuil, et gagne s'il dénature moins la teinte.
                if (bestRatio < LABEL_MIN_CONTRAST || t < bestMix) {
                    best = mixed; bestRatio = r; bestMix = t
                }
                break   // inutile de pousser plus loin vers cette cible
            }
            // Pas atteint : ne sert que de repli, et seulement tant qu'aucun
            // candidat n'a atteint le seuil.
            if (bestRatio < LABEL_MIN_CONTRAST && r > bestRatio) { best = mixed; bestRatio = r }
        }
    }
    return best
}

// Géométrie des étiquettes en dp : en pixels bruts, marges et zone d'accrochage
// variaient du simple au triple entre un écran mdpi et un écran xxhdpi.
internal val LABEL_PAD_X = 3.dp
internal val LABEL_PAD_Y = 2.dp
internal val LABEL_STROKE = 0.7.dp
/** Marge d'accrochage autour de la boîte — courte, cf. labelKeyAt. */
private val LABEL_TOUCH_SLACK = 6.dp

/** Rayon d'accrochage magnétique de l'outil de mesure (≈ 4 mm d'écran). */
private val MEASURE_SNAP_RADIUS = 22.dp

/** Magenta : aucune autre signalétique du plan ne l'utilise. */
private val MEASURE_COLOR = Color(0xFFD500A0)

/**
 * Extrémité de mesure. Le marqueur DIFFÈRE selon l'accrochage : un disque plein
 * cerclé quand le point est collé à un candidat réel (centre de projecteur,
 * sommet du plan), une simple croix sinon. Sans cette distinction, on ne peut
 * pas savoir si la cote porte sur un vrai point ou sur un doigt approximatif.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMeasureHandle(
    p: Offset, snapped: Boolean
) {
    if (snapped) {
        drawCircle(Color.White, radius = 8f, center = p)
        drawCircle(MEASURE_COLOR, radius = 8f, center = p,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
        drawCircle(MEASURE_COLOR, radius = 3.5f, center = p)
    } else {
        val r = 9f
        drawLine(Color.White.copy(alpha = 0.85f), Offset(p.x - r, p.y), Offset(p.x + r, p.y), strokeWidth = 4.5f)
        drawLine(Color.White.copy(alpha = 0.85f), Offset(p.x, p.y - r), Offset(p.x, p.y + r), strokeWidth = 4.5f)
        drawLine(MEASURE_COLOR, Offset(p.x - r, p.y), Offset(p.x + r, p.y), strokeWidth = 2f)
        drawLine(MEASURE_COLOR, Offset(p.x, p.y - r), Offset(p.x, p.y + r), strokeWidth = 2f)
    }
}
/**
 * Contraste minimum encre ↔ voile de la pastille. 4.5:1 = seuil WCAG AA du texte
 * courant ; l'étiquette est petite, donc on ne descend pas au seuil « grand
 * texte » (3:1).
 */
private const val LABEL_MIN_CONTRAST = 4.5f
/**
 * Marge de culling supplémentaire : l'encombrement d'une pastille dont l'origine
 * (le projecteur) est déjà au bord du cadre. En dp, comme le reste de la
 * géométrie d'étiquette — en pixels bruts elle valait trois fois moins de
 * millimètres réels sur un écran dense, et l'étiquette y disparaissait plus tôt.
 */
internal val LABEL_CULL_MARGIN = 160.dp
/**
 * Amplitude maximale d'un décalage manuel. Au-delà, l'étiquette n'a plus de
 * rapport lisible avec le projecteur qu'elle désigne, et elle sortirait de la
 * fenêtre élargie par LABEL_CULL_MARGIN.
 */
private val LABEL_OFFSET_LIMIT = 240.dp

internal class PlanFixture(
    /** Identité stable de l'objet MVR — masquage, patch (cf. mvrInstanceKey). */
    val key: String,
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

// ---- HOMOTHÉTIE : bornes du réglage + formatage de la valeur éditable --------
// Le CURSEUR couvre ×0,1 → ×10 (log10 de −1 à +1, la courbe la plus lisible :
// autant de course pour diviser que pour multiplier). La valeur SAISIE, elle, va
// de ×0,01 à ×100 — mêmes bornes que iOS, pour qu'un même plan se règle à
// l'identique sur les deux plateformes ; le curseur se colle alors à sa borne,
// sans jamais contredire la valeur réellement appliquée.
internal const val HOMOTHETY_MIN = 0.01
internal const val HOMOTHETY_MAX = 100.0

/** Position de curseur (log10, bornée) correspondant à un facteur. */
internal fun homothetySlider(v: Double): Float =
    kotlin.math.log10(v.coerceIn(HOMOTHETY_MIN, HOMOTHETY_MAX)).coerceIn(-1.0, 1.0).toFloat()

/** Facteur affiché dans le champ : 3 décimales, sans zéros inutiles (« 1 », « 0,85 »). */
internal fun formatHomothety(v: Double): String {
    val s = "%.3f".format(java.util.Locale.US, v)
    return s.trimEnd('0').trimEnd('.').ifEmpty { "1" }
}

internal class PlanData(
    val fixtures: List<PlanFixture>,
    val structure: List<Pair<Float, Float>>,
    val structureKeys: List<String>,
    val cx: Float, val cy: Float, val spanX: Float, val spanY: Float
)

/**
 * Projette la scène en plan (top : x, −y en mm) — projecteurs + décor.
 * `overrides` fournit le NOM D'AFFICHAGE EFFECTIF (renommage) porté par
 * `PlanFixture.name` ; défaut vide = nom brut du .mvr.
 */
internal fun planData(scene: MvrScene, overrides: PatchOverrides = PatchOverrides()): PlanData {
    val fixtures = ArrayList<PlanFixture>()
    val structure = ArrayList<Pair<Float, Float>>()
    // Identité (même index que `structure`) : le repli « un point par objet »
    // doit lui aussi pouvoir être masqué élément par élément.
    val structureKeys = ArrayList<String>()
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
                PlanFixture(mvrInstanceKey(o), px, py, o.fixtureId, overrides.effectiveName(o), o.gdtfSpec, o.layerName,
                    o.addresses.joinToString(","), o.gdtfMode, drMat(o.transform.m))
            )
            extend(px, py)
        } else {
            structure.add(px to py)
            structureKeys.add(mvrInstanceKey(o))
            // Le décor élargit aussi le cadrage, mais seulement s'il n'y a pas
            // de projecteurs (sinon un objet lointain écrase le rig).
        }
    }
    if (fixtures.isEmpty()) {
        for (p in structure) extend(p.first, p.second)
    }
    if (minX > maxX) { minX = -1000f; maxX = 1000f; minY = -1000f; maxY = 1000f }
    val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
    return PlanData(fixtures, structure, structureKeys, cx, cy, max(maxX - minX, 1f), max(maxY - minY, 1f))
}

/**
 * Couleurs EXPLICITES des boutons des panneaux à fond BLANC forcé (plan de
 * repère, export PDF) — même pièges/fix que [panelButtonColors] dans
 * `ModelPlacementPanel.kt` : les boutons Material3 tirent leur `contentColor`
 * ambiant du `colorScheme` de l'app (SOMBRE), pas de la `Surface` blanche qui
 * les héberge → libellés clairs sur fond blanc, boutons VIDES en thème sombre.
 */
@Composable
private fun panelButtonColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = Color(0xFF111111)
)

/** Boutons du panneau « PDF du plan » : trois sur 268 dp, sans couper les mots. */
private val EXPORT_BUTTON_PADDING = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp)
