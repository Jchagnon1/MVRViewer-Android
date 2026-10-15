package com.minou.mvrviewer.ui

import androidx.lifecycle.Lifecycle

// =============================================================================
// NAVIGATION D'UN PROJET OUVERT — logique PURE (SPEC-menus-android §1.3, §4)
// =============================================================================
//
// Un projet a une VUE DE BASE (3D ou plan, seule valeur persistée) et, au plus,
// UNE DESTINATION posée par-dessus (Patch, Univers, Câblage, Calques, Modèles de
// projecteurs, Bibliothèque MA, Compte & réglages, Partager, Historique). Fermer
// la destination rend la vue de base INTACTE (caméra 3D, zoom du plan,
// sélection, outils) : elle reste composée dessous. Équivalent Android de
// « par-dessus le plan sans le fermer » (iOS §1.3, `ContentView.open(_:from:)`).
//
// Tout ce qui décide (ouvrir, fermer, retour système, bascule de vue, parcours
// d'affectation du câblage) passe par [SceneNav.reduce], testé en JVM.

/**
 * Vue de base d'un projet ouvert. Noms FIGÉS : ce sont les valeurs persistées
 * par [ViewStateStore] (anciennement `SceneMode.THREE_D` / `SceneMode.PLAN`).
 */
enum class SceneBase {
    THREE_D, PLAN;

    val surface: ProjectSurface
        get() = if (this == PLAN) ProjectSurface.PLAN else ProjectSurface.SCENE_3D
}

/** Surface d'où une destination est ouverte (iOS `ProjectSurface`). */
enum class ProjectSurface { SCENE_3D, PLAN }

/** Onglet de la feuille « Calques » (iOS `LayersTab`). */
enum class LayersTab { LAYERS, CLASSES }

/**
 * Fenêtres ouvrables depuis les deux vues (iOS `ProjectDestination`). [id] est
 * stable et distinct par cas (`Cabling(DMX)` ≠ `Cabling(null)`). [fullScreen] =
 * écran plein OPAQUE posé par-dessus la vue de base (sinon : feuille ou
 * dialogue, la vue de base reste visible autour).
 */
sealed interface ProjectDestination {
    val id: String
    val fullScreen: Boolean

    data object PatchList : ProjectDestination {
        override val id = "patchList"; override val fullScreen = true
    }
    data object Universe : ProjectDestination {
        override val id = "universe"; override val fullScreen = true
    }
    /** [tab] null = dernier onglet retenu (mémoire de l'écran Câblage). */
    data class Cabling(val tab: CablingScreenTab?) : ProjectDestination {
        override val id get() = "cabling:" + (tab?.raw ?: "last")
        override val fullScreen get() = true
    }
    data class Layers(val tab: LayersTab) : ProjectDestination {
        override val id get() = "layers:" + tab.name.lowercase()
        override val fullScreen get() = false
    }
    data object FixtureModels : ProjectDestination {
        override val id = "fixtureModels"; override val fullScreen = true
    }
    /** Ouverte par un profil reçu ou le crochet ; sinon sous-volet de [FixtureModels]. */
    data object MaLibrary : ProjectDestination {
        override val id = "maLibrary"; override val fullScreen = true
    }
    data object AccountSettings : ProjectDestination {
        override val id = "accountSettings"; override val fullScreen = true
    }
    data object ShareProject : ProjectDestination {
        override val id = "shareProject"; override val fullScreen = false
    }
    data object History : ProjectDestination {
        override val id = "history"; override val fullScreen = false
    }

    companion object {
        /**
         * Valeur du crochet DEBUG `MVR_AUTO_DEST` (SPEC-menus-android §4.7) :
         * `patch`, `universe`, `cabling[:electrical|dmx|powers]`, `layers`,
         * `classes`, `models`, `ma`, `account`, `share`, `history`. Inconnue → null.
         */
        fun fromHook(raw: String?): ProjectDestination? {
            val v = raw?.trim()?.lowercase()?.ifEmpty { null } ?: return null
            return when (v) {
                "patch" -> PatchList
                "universe" -> Universe
                "cabling" -> Cabling(null)
                "cabling:electrical" -> Cabling(CablingScreenTab.CABLING)
                "cabling:dmx" -> Cabling(CablingScreenTab.DMX)
                "cabling:powers" -> Cabling(CablingScreenTab.POWERS)
                "layers" -> Layers(LayersTab.LAYERS)
                "classes" -> Layers(LayersTab.CLASSES)
                "models" -> FixtureModels
                "ma" -> MaLibrary
                "account" -> AccountSettings
                "share" -> ShareProject
                "history" -> History
                else -> null
            }
        }
    }
}

/** D'où l'écran Câblage a été ouvert : décide où ramène la barre d'affectation. */
enum class CablingOrigin {
    SCENE_3D, PLAN;

    companion object {
        fun of(surface: ProjectSurface): CablingOrigin =
            if (surface == ProjectSurface.PLAN) PLAN else SCENE_3D
    }
}

/** Événements du parcours « affecter sur le plan » (SPEC-cablage-plan, iOS §15.4). */
enum class CablingAssignEvent {
    /** « Sur le plan » d'une voie de l'écran Câblage. */
    SELECT_ON_PLAN,
    /** « Terminé » de la barre d'affectation. */
    BAR_DONE,
    /** ← du plan (TalkBack « Terminé ») ou retour système pendant l'affectation. */
    BACK,
    /** « Vue 3D » (pastille de la tranche C, entrée « Vue 3D » du menu actuel). */
    VIEW_3D,
    /** Croix « Quitter l'affectation » de la barre : on reste sur le plan. */
    EXIT_ASSIGN
}

/**
 * Résultat d'un événement d'affectation : vue de base, affectation armée ou
 * non, et réouverture de l'écran Câblage (sur sa dernière voie, mémoire
 * inchangée) par-dessus la vue de base.
 */
data class CablingRoute(val base: SceneBase, val armed: Boolean, val reopenCabling: Boolean)

/**
 * Table de SPEC-menus-android §4.3 (miroir d'iOS §15.4), 5 événements × 2 origines.
 */
object CablingAssignRoute {
    fun route(event: CablingAssignEvent, origin: CablingOrigin): CablingRoute = when (event) {
        // Origine 3D : le Câblage se ferme, l'affectation s'arme, le plan devient la base.
        // Origine plan : même chose, sur le plan déjà ouvert (pas de second plan).
        CablingAssignEvent.SELECT_ON_PLAN -> CablingRoute(SceneBase.PLAN, armed = true, reopenCabling = false)
        // Origine 3D : retour à la 3D, Câblage rouvert ; origine plan : Câblage
        // rouvert PAR-DESSUS le plan, qui reste la base.
        CablingAssignEvent.BAR_DONE -> CablingRoute(
            if (origin == CablingOrigin.PLAN) SceneBase.PLAN else SceneBase.THREE_D,
            armed = false, reopenCabling = true
        )
        // ← / retour système : comportement livré (SPEC-cablage-plan §2.3), quelle
        // que soit l'origine : le plan se ferme, le Câblage rouvre sur la 3D.
        CablingAssignEvent.BACK -> CablingRoute(SceneBase.THREE_D, armed = false, reopenCabling = true)
        // « Vue 3D » : désarme et ferme le plan, SANS rouvrir le Câblage.
        CablingAssignEvent.VIEW_3D -> CablingRoute(SceneBase.THREE_D, armed = false, reopenCabling = false)
        // Croix : désarme, reste sur le plan.
        CablingAssignEvent.EXIT_ASSIGN -> CablingRoute(SceneBase.PLAN, armed = false, reopenCabling = false)
    }
}

/**
 * État de navigation d'un projet ouvert. [assigning] = la vue plan est en mode
 * affectation (la cible elle-même, avec son libellé figé, vit dans SceneScreen).
 */
data class SceneNavState(
    val base: SceneBase = SceneBase.THREE_D,
    val destination: ProjectDestination? = null,
    val assigning: Boolean = false,
    val cablingOrigin: CablingOrigin = CablingOrigin.SCENE_3D
)

sealed interface SceneNavEvent {
    /** Ouvre une destination par-dessus la vue de base ([from] = surface d'origine). */
    data class Open(val destination: ProjectDestination, val from: ProjectSurface) : SceneNavEvent
    /** Ferme la destination ouverte (← ou retour système de l'écran). */
    data object Close : SceneNavEvent
    /** Bascule de vue de base (3D ↔ plan) : un remplacement, pas une surimpression. */
    data class ShowBase(val base: SceneBase) : SceneNavEvent
    /** Retour système au niveau de la scène (les sous-volets ont le leur, prioritaire). */
    data object SystemBack : SceneNavEvent
    /** Parcours d'affectation du câblage sur le plan. */
    data class Cabling(val event: CablingAssignEvent) : SceneNavEvent
}

/** [closeProject] : le retour système depuis la 3D ferme le projet (accueil). */
data class SceneNavResult(val state: SceneNavState, val closeProject: Boolean = false)

object SceneNav {
    fun reduce(s: SceneNavState, e: SceneNavEvent): SceneNavResult = when (e) {
        is SceneNavEvent.Open -> {
            val cabling = e.destination is ProjectDestination.Cabling
            SceneNavResult(
                s.copy(
                    destination = e.destination,
                    // L'origine n'est retenue qu'à l'ouverture du Câblage : c'est elle
                    // (et non la base courante) qui décide où ramène « Terminé ».
                    cablingOrigin = if (cabling) CablingOrigin.of(e.from) else s.cablingOrigin,
                    // Rouvrir le Câblage pendant une affectation la désarme (l'écran
                    // remet la voie travaillée sous les yeux). Les autres fenêtres
                    // laissent le plan, affectation comprise, intact dessous.
                    assigning = if (cabling) false else s.assigning
                )
            )
        }
        SceneNavEvent.Close -> SceneNavResult(s.copy(destination = null))
        is SceneNavEvent.ShowBase -> SceneNavResult(
            // Quitter le plan désarme TOUJOURS l'affectation : une réouverture normale
            // ne doit pas hériter de la cible.
            s.copy(base = e.base, assigning = s.assigning && e.base == SceneBase.PLAN)
        )
        SceneNavEvent.SystemBack -> when {
            s.destination != null -> reduce(s, SceneNavEvent.Close)
            s.base == SceneBase.PLAN && s.assigning -> reduce(s, SceneNavEvent.Cabling(CablingAssignEvent.BACK))
            s.base == SceneBase.PLAN -> reduce(s, SceneNavEvent.ShowBase(SceneBase.THREE_D))
            else -> SceneNavResult(s, closeProject = true)
        }
        is SceneNavEvent.Cabling -> {
            val r = CablingAssignRoute.route(e.event, s.cablingOrigin)
            val destination = when {
                r.reopenCabling -> ProjectDestination.Cabling(null)
                e.event == CablingAssignEvent.SELECT_ON_PLAN -> null
                else -> s.destination
            }
            SceneNavResult(s.copy(base = r.base, assigning = r.armed, destination = destination))
        }
    }
}

/**
 * Action à lancer à l'APPARITION du plan (iOS `PlanStartAction`), consommée une
 * seule fois puis remise à [NONE]. La garde Pro (calibrations) est faite AVANT
 * l'ouverture du plan.
 */
enum class PlanStartAction {
    NONE, EXPORT_PDF, THREE_POINT, HERE_ANCHOR;

    companion object {
        /** Valeur du crochet DEBUG `MVR_AUTO_PLAN_START` : `export`, `cal3`, `here`. */
        fun fromHook(raw: String?): PlanStartAction = when (raw?.trim()?.lowercase()) {
            "export" -> EXPORT_PDF
            "cal3" -> THREE_POINT
            "here" -> HERE_ANCHOR
            else -> NONE
        }

        /** Les calibrations écrivent une donnée partagée : Pro, vérifié avant l'ouverture. */
        fun needsPro(a: PlanStartAction): Boolean = a == THREE_POINT || a == HERE_ANCHOR
    }
}

/**
 * Boîte à usage unique d'une [PlanStartAction] : [take] rend l'action en attente
 * et la remet à [PlanStartAction.NONE] (une recomposition ou un second
 * affichage du plan ne la relance jamais).
 */
class PlanStartRequest(initial: PlanStartAction = PlanStartAction.NONE) {
    var pending: PlanStartAction = initial
        private set

    fun post(a: PlanStartAction) { pending = a }

    fun take(): PlanStartAction = pending.also { pending = PlanStartAction.NONE }
}

/** Sections de « Compte & réglages », dans l'ordre (iOS §10 ; pas de « Débogage » sur Android). */
enum class AccountSettingsSection { ACCOUNT, PRO, SERVICES }

object AccountSettingsSections {
    /** « Compte » n'existe que si la synchro est branchée (toujours vrai dans l'app). */
    fun of(hasSync: Boolean): List<AccountSettingsSection> = buildList {
        if (hasSync) add(AccountSettingsSection.ACCOUNT)
        add(AccountSettingsSection.PRO)
        add(AccountSettingsSection.SERVICES)
    }
}

/**
 * Sous-volets INTERNES de « Modèles de projecteurs » (SPEC-menus-android §3.10) :
 * leur ← et le retour système ramènent à la liste des types, jamais à la vue de
 * base. La scène ne porte qu'une destination (pas de pile).
 */
sealed interface FixtureModelsPane {
    data object List : FixtureModelsPane
    /** Connexion GDTF Share, puis recherche de [thenSearch] si non nul. */
    data class Login(val thenSearch: String?) : FixtureModelsPane
    data class Search(val spec: String) : FixtureModelsPane
    data object MaLibrary : FixtureModelsPane
}

object FixtureModelsNav {
    /** Toucher un type : recherche s'il est connecté, sinon connexion PUIS recherche. */
    fun tapType(spec: String, signedIn: Boolean): FixtureModelsPane =
        if (signedIn) FixtureModelsPane.Search(spec) else FixtureModelsPane.Login(spec)

    fun afterLogin(p: FixtureModelsPane.Login): FixtureModelsPane =
        p.thenSearch?.let { FixtureModelsPane.Search(it) } ?: FixtureModelsPane.List

    /** Retour depuis n'importe quel sous-volet : la liste des types. */
    @Suppress("UNUSED_PARAMETER")
    fun back(p: FixtureModelsPane): FixtureModelsPane = FixtureModelsPane.List
}

/** Feuille « Calques » : onglets proposés et onglet réellement affiché. */
object LayersSheetTabs {
    /** Sélecteur « Calques | Classes » seulement si le fichier déclare des classes. */
    fun tabs(hasClasses: Boolean): List<LayersTab> =
        if (hasClasses) listOf(LayersTab.LAYERS, LayersTab.CLASSES) else listOf(LayersTab.LAYERS)

    fun effective(requested: LayersTab, hasClasses: Boolean): LayersTab =
        if (requested == LayersTab.CLASSES && !hasClasses) LayersTab.LAYERS else requested

    /** Réglage « fluidité en navigation » (LOD) : en 3D seulement (D10). */
    fun showsLod(from: ProjectSurface): Boolean = from == ProjectSurface.SCENE_3D
}

/**
 * Cycle de vie du RENDU 3D : celui de l'activité, plafonné à STARTED quand un
 * écran plein opaque recouvre la 3D. SceneView ne dessine qu'à l'état RESUMED
 * (boucle de rendu en attente sinon) : la 3D cachée ne consomme plus rien, et
 * reprend telle quelle (caméra, sélection) à la fermeture de l'écran.
 */
object RenderLifecycleRule {
    fun target(parent: Lifecycle.State, covered: Boolean): Lifecycle.State =
        if (covered && parent.isAtLeast(Lifecycle.State.RESUMED)) Lifecycle.State.STARTED else parent
}
