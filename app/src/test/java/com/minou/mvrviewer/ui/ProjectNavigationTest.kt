package com.minou.mvrviewer.ui

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Rangement des menus Android, TRANCHE A (SPEC-menus-android §10) : destinations
 * en surimpression, retour système, parcours d'affectation du câblage, lancement
 * du plan, Compte & réglages, sous-volets de Modèles de projecteurs, feuille
 * Calques, pause du rendu 3D, textes fr / en et données d'essai.
 */
class ProjectNavigationTest {

    private fun reduce(s: SceneNavState, e: SceneNavEvent) = SceneNav.reduce(s, e).state

    // ------------------------------------------------------------ destinations

    @Test fun lesIdentifiantsDeDestinationSontStablesEtDistincts() {
        val all = listOf(
            ProjectDestination.PatchList, ProjectDestination.Universe,
            ProjectDestination.Cabling(null), ProjectDestination.Cabling(CablingScreenTab.CABLING),
            ProjectDestination.Cabling(CablingScreenTab.DMX), ProjectDestination.Cabling(CablingScreenTab.POWERS),
            ProjectDestination.Layers(LayersTab.LAYERS), ProjectDestination.Layers(LayersTab.CLASSES),
            ProjectDestination.FixtureModels, ProjectDestination.MaLibrary, ProjectDestination.AccountSettings,
            ProjectDestination.ShareProject, ProjectDestination.History
        )
        assertEquals(all.size, all.map { it.id }.toSet().size)
        // Instantané : un identifiant ne change jamais (étiquettes de test, journal).
        assertEquals(
            listOf("patchList", "universe", "cabling:last", "cabling:cabling", "cabling:dmx", "cabling:powers",
                "layers:layers", "layers:classes", "fixtureModels", "maLibrary", "accountSettings",
                "shareProject", "history"),
            all.map { it.id }
        )
        assertTrue(ProjectDestination.Cabling(CablingScreenTab.DMX).id != ProjectDestination.Cabling(null).id)
        // Écrans pleins opaques vs feuilles et dialogues (la vue de base reste visible autour).
        assertEquals(
            setOf("patchList", "universe", "cabling:last", "cabling:cabling", "cabling:dmx", "cabling:powers",
                "fixtureModels", "maLibrary", "accountSettings"),
            all.filter { it.fullScreen }.map { it.id }.toSet()
        )
    }

    @Test fun leCrochetDeDestinationLitChaqueValeur() {
        val expected = mapOf(
            "patch" to ProjectDestination.PatchList,
            "universe" to ProjectDestination.Universe,
            "cabling" to ProjectDestination.Cabling(null),
            "cabling:electrical" to ProjectDestination.Cabling(CablingScreenTab.CABLING),
            "cabling:dmx" to ProjectDestination.Cabling(CablingScreenTab.DMX),
            "cabling:powers" to ProjectDestination.Cabling(CablingScreenTab.POWERS),
            "layers" to ProjectDestination.Layers(LayersTab.LAYERS),
            "classes" to ProjectDestination.Layers(LayersTab.CLASSES),
            "models" to ProjectDestination.FixtureModels,
            "ma" to ProjectDestination.MaLibrary,
            "account" to ProjectDestination.AccountSettings,
            "share" to ProjectDestination.ShareProject,
            "history" to ProjectDestination.History
        )
        for ((raw, d) in expected) assertEquals(raw, d, ProjectDestination.fromHook(raw))
        assertEquals(ProjectDestination.Cabling(CablingScreenTab.DMX), ProjectDestination.fromHook(" Cabling:DMX "))
        assertNull(ProjectDestination.fromHook("inconnu"))
        assertNull(ProjectDestination.fromHook(""))
        assertNull(ProjectDestination.fromHook(null))
    }

    // ------------------------------------------------------- ouvrir / fermer

    @Test fun ouvrirPuisFermerRendLaVueDeBaseIntacte() {
        for (base in SceneBase.entries) {
            val start = SceneNavState(base = base)
            for (d in listOf(ProjectDestination.PatchList, ProjectDestination.Universe,
                    ProjectDestination.Cabling(CablingScreenTab.DMX), ProjectDestination.Layers(LayersTab.CLASSES),
                    ProjectDestination.FixtureModels, ProjectDestination.MaLibrary,
                    ProjectDestination.AccountSettings, ProjectDestination.History)) {
                val opened = reduce(start, SceneNavEvent.Open(d, base.surface))
                assertEquals(base, opened.base)
                assertEquals(d, opened.destination)
                val closed = reduce(opened, SceneNavEvent.Close)
                assertEquals("$base / ${d.id}", start.copy(cablingOrigin = closed.cablingOrigin), closed)
                assertEquals(base, closed.base)
                assertNull(closed.destination)
            }
        }
    }

    @Test fun lePatchOuvertDepuisLePlanRevientAuPlan() {
        val plan = SceneNavState(base = SceneBase.PLAN)
        val patch = reduce(plan, SceneNavEvent.Open(ProjectDestination.PatchList, ProjectSurface.PLAN))
        // Retour système : la destination se ferme, le plan reste la base.
        val r = SceneNav.reduce(patch, SceneNavEvent.SystemBack)
        assertFalse(r.closeProject)
        assertEquals(SceneBase.PLAN, r.state.base)
        assertNull(r.state.destination)
    }

    @Test fun retourSystemeDansChaqueEtat() {
        // 3D seule → fermer le projet (accueil), l'état ne bouge pas.
        val r3d = SceneNav.reduce(SceneNavState(base = SceneBase.THREE_D), SceneNavEvent.SystemBack)
        assertTrue(r3d.closeProject)
        assertEquals(SceneNavState(base = SceneBase.THREE_D), r3d.state)
        // Plan → 3D.
        val rPlan = SceneNav.reduce(SceneNavState(base = SceneBase.PLAN), SceneNavEvent.SystemBack)
        assertFalse(rPlan.closeProject)
        assertEquals(SceneBase.THREE_D, rPlan.state.base)
        // Destination ouverte (sur la 3D comme sur le plan) → elle se ferme seule.
        for (base in SceneBase.entries) {
            val s = SceneNavState(base = base, destination = ProjectDestination.Universe)
            val r = SceneNav.reduce(s, SceneNavEvent.SystemBack)
            assertFalse(r.closeProject)
            assertEquals(base, r.state.base)
            assertNull(r.state.destination)
        }
        // Plan en affectation → comportement livré : plan fermé, Câblage rouvert sur la 3D.
        val assigning = SceneNavState(base = SceneBase.PLAN, assigning = true, cablingOrigin = CablingOrigin.PLAN)
        val rAssign = SceneNav.reduce(assigning, SceneNavEvent.SystemBack)
        assertFalse(rAssign.closeProject)
        assertEquals(SceneNavState(SceneBase.THREE_D, ProjectDestination.Cabling(null), false, CablingOrigin.PLAN), rAssign.state)
    }

    @Test fun laBasculeDeVueDesarmeLAffectationEnQuittantLePlan() {
        val assigning = SceneNavState(base = SceneBase.PLAN, assigning = true)
        assertFalse(reduce(assigning, SceneNavEvent.ShowBase(SceneBase.THREE_D)).assigning)
        assertEquals(SceneBase.THREE_D, reduce(assigning, SceneNavEvent.ShowBase(SceneBase.THREE_D)).base)
        // Rester sur le plan ne désarme rien.
        assertTrue(reduce(assigning, SceneNavEvent.ShowBase(SceneBase.PLAN)).assigning)
        // Une fenêtre posée par-dessus le plan laisse l'affectation intacte dessous…
        val patch = reduce(assigning, SceneNavEvent.Open(ProjectDestination.PatchList, ProjectSurface.PLAN))
        assertTrue(patch.assigning)
        assertTrue(reduce(patch, SceneNavEvent.Close).assigning)
        // … rouvrir le Câblage, elle, la désarme (l'écran remet la voie sous les yeux).
        assertFalse(reduce(assigning, SceneNavEvent.Open(ProjectDestination.Cabling(null), ProjectSurface.PLAN)).assigning)
    }

    // ------------------------------------------------- câblage depuis le plan

    @Test fun routeDAffectationLesDixCas() {
        val p = CablingOrigin.PLAN
        val d = CablingOrigin.SCENE_3D
        val r = CablingAssignRoute::route
        // « Sur le plan »
        assertEquals(CablingRoute(SceneBase.PLAN, armed = true, reopenCabling = false), r(CablingAssignEvent.SELECT_ON_PLAN, d))
        assertEquals(CablingRoute(SceneBase.PLAN, armed = true, reopenCabling = false), r(CablingAssignEvent.SELECT_ON_PLAN, p))
        // « Terminé » de la barre
        assertEquals(CablingRoute(SceneBase.THREE_D, armed = false, reopenCabling = true), r(CablingAssignEvent.BAR_DONE, d))
        assertEquals(CablingRoute(SceneBase.PLAN, armed = false, reopenCabling = true), r(CablingAssignEvent.BAR_DONE, p))
        // ← du plan / retour système
        assertEquals(CablingRoute(SceneBase.THREE_D, armed = false, reopenCabling = true), r(CablingAssignEvent.BACK, d))
        assertEquals(CablingRoute(SceneBase.THREE_D, armed = false, reopenCabling = true), r(CablingAssignEvent.BACK, p))
        // « Vue 3D »
        assertEquals(CablingRoute(SceneBase.THREE_D, armed = false, reopenCabling = false), r(CablingAssignEvent.VIEW_3D, d))
        assertEquals(CablingRoute(SceneBase.THREE_D, armed = false, reopenCabling = false), r(CablingAssignEvent.VIEW_3D, p))
        // Croix « Quitter l'affectation »
        assertEquals(CablingRoute(SceneBase.PLAN, armed = false, reopenCabling = false), r(CablingAssignEvent.EXIT_ASSIGN, d))
        assertEquals(CablingRoute(SceneBase.PLAN, armed = false, reopenCabling = false), r(CablingAssignEvent.EXIT_ASSIGN, p))
    }

    @Test fun parcoursCablageOuvertParDessusLePlan() {
        var s = SceneNavState(base = SceneBase.PLAN)
        s = reduce(s, SceneNavEvent.Open(ProjectDestination.Cabling(null), ProjectSurface.PLAN))
        assertEquals(CablingOrigin.PLAN, s.cablingOrigin)
        // « Sur le plan » : le Câblage se ferme, l'affectation s'arme sur le MÊME plan.
        s = reduce(s, SceneNavEvent.Cabling(CablingAssignEvent.SELECT_ON_PLAN))
        assertEquals(SceneNavState(SceneBase.PLAN, null, true, CablingOrigin.PLAN), s)
        // « Terminé » : le Câblage rouvre PAR-DESSUS le plan.
        s = reduce(s, SceneNavEvent.Cabling(CablingAssignEvent.BAR_DONE))
        assertEquals(SceneNavState(SceneBase.PLAN, ProjectDestination.Cabling(null), false, CablingOrigin.PLAN), s)
        // Le fermer ramène au plan.
        s = reduce(s, SceneNavEvent.Close)
        assertEquals(SceneBase.PLAN, s.base)
        assertNull(s.destination)
    }

    @Test fun parcoursCablageDepuisLa3DInchange() {
        var s = SceneNavState(base = SceneBase.THREE_D)
        s = reduce(s, SceneNavEvent.Open(ProjectDestination.Cabling(CablingScreenTab.DMX), ProjectSurface.SCENE_3D))
        s = reduce(s, SceneNavEvent.Cabling(CablingAssignEvent.SELECT_ON_PLAN))
        assertEquals(SceneNavState(SceneBase.PLAN, null, true, CablingOrigin.SCENE_3D), s)
        // ◀ ▶ ne passent pas par la navigation ; « Terminé » : retour 3D + Câblage.
        val done = reduce(s, SceneNavEvent.Cabling(CablingAssignEvent.BAR_DONE))
        assertEquals(SceneNavState(SceneBase.THREE_D, ProjectDestination.Cabling(null), false, CablingOrigin.SCENE_3D), done)
        // Croix : on reste sur le plan, désarmé, rien ne s'ouvre.
        val exit = reduce(s, SceneNavEvent.Cabling(CablingAssignEvent.EXIT_ASSIGN))
        assertEquals(SceneNavState(SceneBase.PLAN, null, false, CablingOrigin.SCENE_3D), exit)
        // « Vue 3D » : désarmé, 3D, Câblage NON rouvert.
        val v3d = reduce(s, SceneNavEvent.Cabling(CablingAssignEvent.VIEW_3D))
        assertEquals(SceneNavState(SceneBase.THREE_D, null, false, CablingOrigin.SCENE_3D), v3d)
    }

    // ------------------------------------------------------ lancement du plan

    @Test fun lActionDeLancementDuPlanNeSeConsommeQuUneFois() {
        val r = PlanStartRequest()
        assertEquals(PlanStartAction.NONE, r.take())
        r.post(PlanStartAction.EXPORT_PDF)
        assertEquals(PlanStartAction.EXPORT_PDF, r.take())
        // Une recomposition ou un second affichage du plan ne la relance jamais.
        assertEquals(PlanStartAction.NONE, r.take())
        assertEquals(PlanStartAction.NONE, r.pending)
        r.post(PlanStartAction.HERE_ANCHOR)
        r.post(PlanStartAction.THREE_POINT) // la dernière demande l'emporte
        assertEquals(PlanStartAction.THREE_POINT, r.take())
        assertEquals(PlanStartAction.NONE, r.take())
    }

    @Test fun crochetDeLancementEtGardePro() {
        assertEquals(PlanStartAction.EXPORT_PDF, PlanStartAction.fromHook("export"))
        assertEquals(PlanStartAction.THREE_POINT, PlanStartAction.fromHook("cal3"))
        assertEquals(PlanStartAction.HERE_ANCHOR, PlanStartAction.fromHook(" HERE "))
        assertEquals(PlanStartAction.NONE, PlanStartAction.fromHook("autre"))
        assertEquals(PlanStartAction.NONE, PlanStartAction.fromHook(null))
        // Calibrer écrit une donnée partagée : Pro, vérifié AVANT l'ouverture du plan.
        assertTrue(PlanStartAction.needsPro(PlanStartAction.THREE_POINT))
        assertTrue(PlanStartAction.needsPro(PlanStartAction.HERE_ANCHOR))
        assertFalse(PlanStartAction.needsPro(PlanStartAction.EXPORT_PDF))
        assertFalse(PlanStartAction.needsPro(PlanStartAction.NONE))
    }

    @Test fun laVueDeBasePersisteeGardeSesValeurs() {
        assertEquals(SceneBase.PLAN, ViewStateStore.baseFrom("PLAN"))
        assertEquals(SceneBase.THREE_D, ViewStateStore.baseFrom("THREE_D"))
        // Valeurs de l'ancien `SceneMode` jamais mémorisées, ou inconnues → 3D.
        assertEquals(SceneBase.THREE_D, ViewStateStore.baseFrom("PATCH"))
        assertEquals(SceneBase.THREE_D, ViewStateStore.baseFrom(null))
        assertEquals(listOf("THREE_D", "PLAN"), SceneBase.entries.map { it.name })
        assertEquals(ProjectSurface.PLAN, SceneBase.PLAN.surface)
        assertEquals(ProjectSurface.SCENE_3D, SceneBase.THREE_D.surface)
    }

    // ------------------------------------------------------ compte & réglages

    @Test fun sectionsDeCompteEtReglages() {
        assertEquals(
            listOf(AccountSettingsSection.ACCOUNT, AccountSettingsSection.PRO, AccountSettingsSection.SERVICES,
                AccountSettingsSection.ABOUT),
            AccountSettingsSections.of(hasSync = true)
        )
        // Sans synchro : pas de « Compte », le reste demeure — « À propos » compris
        // (pages légales, licences, version : guide de mise en vente C3).
        assertEquals(
            listOf(AccountSettingsSection.PRO, AccountSettingsSection.SERVICES, AccountSettingsSection.ABOUT),
            AccountSettingsSections.of(hasSync = false)
        )
        // Jamais de section « Débogage » sur Android (D5).
        assertEquals(4, AccountSettingsSection.entries.size)
    }

    // ------------------------------------- sous-volets de Modèles de projecteurs

    @Test fun sousVoletsDeModelesDeProjecteurs() {
        // Connecté : un type touché ouvre sa recherche.
        assertEquals(FixtureModelsPane.Search("Acme@Wash"), FixtureModelsNav.tapType("Acme@Wash", signedIn = true))
        // Déconnecté : la connexion d'abord, PUIS la recherche de ce type.
        val login = FixtureModelsNav.tapType("Acme@Wash", signedIn = false)
        assertEquals(FixtureModelsPane.Login("Acme@Wash"), login)
        assertEquals(FixtureModelsPane.Search("Acme@Wash"), FixtureModelsNav.afterLogin(login as FixtureModelsPane.Login))
        // « Se connecter à GDTF Share… » seul : retour à la liste après connexion.
        assertEquals(FixtureModelsPane.List, FixtureModelsNav.afterLogin(FixtureModelsPane.Login(null)))
        // Retour depuis un sous-volet (Bibliothèque MA comprise) : la liste, jamais la vue de base.
        for (p in listOf(FixtureModelsPane.MaLibrary, FixtureModelsPane.Login(null),
                FixtureModelsPane.Login("x"), FixtureModelsPane.Search("x"))) {
            assertEquals(FixtureModelsPane.List, FixtureModelsNav.back(p))
        }
    }

    // ----------------------------------------------------------- feuille Calques

    @Test fun feuilleCalquesOngletsEtLod() {
        assertEquals(listOf(LayersTab.LAYERS, LayersTab.CLASSES), LayersSheetTabs.tabs(hasClasses = true))
        assertEquals(listOf(LayersTab.LAYERS), LayersSheetTabs.tabs(hasClasses = false))
        assertEquals(LayersTab.CLASSES, LayersSheetTabs.effective(LayersTab.CLASSES, hasClasses = true))
        // Sans classes, l'onglet Classes demandé retombe sur Calques (pas de liste vide).
        assertEquals(LayersTab.LAYERS, LayersSheetTabs.effective(LayersTab.CLASSES, hasClasses = false))
        assertEquals(LayersTab.LAYERS, LayersSheetTabs.effective(LayersTab.LAYERS, hasClasses = true))
        // Le réglage « fluidité en navigation » n'a de sens qu'en 3D (D10).
        assertTrue(LayersSheetTabs.showsLod(ProjectSurface.SCENE_3D))
        assertFalse(LayersSheetTabs.showsLod(ProjectSurface.PLAN))
    }

    // ------------------------------------------------------- pause du rendu 3D

    @Test fun leRendu3DAttendSousUnEcranPlein() {
        val S = Lifecycle.State.entries
        for (st in S) {
            // Non recouverte : le rendu suit l'activité.
            assertEquals(st, RenderLifecycleRule.target(st, covered = false))
        }
        // Recouverte et activité au premier plan : plafond STARTED (SceneView ne dessine qu'à RESUMED).
        assertEquals(Lifecycle.State.STARTED, RenderLifecycleRule.target(Lifecycle.State.RESUMED, covered = true))
        // Recouverte mais activité déjà en arrière-plan : on ne remonte jamais au-dessus d'elle.
        assertEquals(Lifecycle.State.STARTED, RenderLifecycleRule.target(Lifecycle.State.STARTED, covered = true))
        assertEquals(Lifecycle.State.CREATED, RenderLifecycleRule.target(Lifecycle.State.CREATED, covered = true))
        assertEquals(Lifecycle.State.DESTROYED, RenderLifecycleRule.target(Lifecycle.State.DESTROYED, covered = true))
    }

    // ------------------------------------------------------------------- textes

    private val newTexts = mapOf(
        "menu_close_project" to ("Fermer le projet" to "Close the project"),
        "menu_account_settings" to ("Compte & réglages…" to "Account & settings…"),
        "account_settings_title" to ("Compte & réglages" to "Account & settings"),
        "account_section_account" to ("Compte" to "Account"),
        "account_section_services" to ("Services" to "Services"),
        "account_signed_in" to ("Connecté" to "Signed in"),
        "account_not_signed_in" to ("Non connecté" to "Not signed in"),
        "pro_section_title" to ("MVR Viewer Pro" to "MVR Viewer Pro"),
        "pro_active" to ("Version Pro active — édition débloquée" to "Pro version active — editing unlocked"),
        "pro_pitch" to ("La lecture est gratuite ; l'édition (patch, câblage, imports…) passe en Pro." to
            "Reading is free; editing (patch, cabling, imports…) requires Pro."),
        "pro_unlock" to ("Débloquer la version Pro" to "Unlock Pro"),
        "pro_restore" to ("Restaurer un achat" to "Restore a purchase"),
        "home_account_prompt_title" to ("Créez votre compte (gratuit)" to "Create your account (free)"),
        "home_account_prompt_body" to ("C'est par lui que vous recevez les projets partagés par votre chef de chantier, avec ses modifications en direct. Lire est gratuit ; seule l'édition passe en Pro." to
            "It's how you receive projects shared by your crew chief, with their changes live. Reading is free; only editing requires Pro."),
        "home_account_prompt_action" to ("Créer un compte ou se connecter…" to "Create an account or sign in…"),
        "models_title" to ("Modèles de projecteurs" to "Fixture models"),
        "models_gdtf_signed_in" to ("GDTF Share : connecté" to "GDTF Share: signed in"),
        "models_gdtf_sign_in" to ("Se connecter à GDTF Share…" to "Sign in to GDTF Share…"),
        "layers_title" to ("Calques" to "Layers"),
        "common_back" to ("Retour" to "Back"),
        "sync_sign_out" to ("Se déconnecter" to "Sign out")
    )

    @Test fun textesDeLaTrancheAEnFrancaisEtEnAnglais() {
        for ((key, v) in newTexts) {
            assertEquals("fr $key", v.first, TestRes.FR.get(key))
            assertEquals("en $key", v.second, TestRes.EN.get(key))
        }
    }

    @Test fun nombreDObjetsDesClassesEnPluriel() {
        assertEquals("1 objet", TestRes.FR.plural("classes_objects", 1, 1))
        assertEquals("0 objet", TestRes.FR.plural("classes_objects", 0, 0))
        assertEquals("6 objets", TestRes.FR.plural("classes_objects", 6, 6))
        assertEquals("1 object", TestRes.EN.plural("classes_objects", 1, 1))
        assertEquals("6 objects", TestRes.EN.plural("classes_objects", 6, 6))
    }

    @Test fun clesRetireesAbsentesDesDeuxLanguesEtDuCode() {
        val removed = listOf("common_back_3d", "home_my_account", "sync_sign_in_title", "nav_account",
            "layers_title_3d", "classes_none", "gdtf_connected_to_share", "classes_objects_fmt")
        for (k in removed) {
            assertFalse("fr $k", TestRes.FR.strings.containsKey(k))
            assertFalse("en $k", TestRes.EN.strings.containsKey(k))
        }
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val code = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (k in removed) assertFalse("usage restant de R.string.$k", Regex("""R\.string\.$k\b""").containsMatchIn(code))
        // Plus de texte en dur dans la carte de l'accueil ni dans le bloc Pro du compte
        // (le paywall, hors chantier, garde les siens).
        for (f in listOf("ui/HomeScreen.kt", "ui/AccountSettingsScreen.kt", "ui/SyncUI.kt")) {
            val c = File(root, "com/minou/mvrviewer/$f").readText()
            for (t in listOf("Créez votre compte (gratuit)", "Débloquer la version Pro", "Restaurer un achat",
                    "Créer un compte ou se connecter…")) assertFalse("$f : « $t » en dur", c.contains("\"$t\""))
        }
        assertFalse(code.contains("enum class SceneMode"))
    }

    // ------------------------------------------------------- données d'essai

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("menus/$name")!!.toURI())

    @Test fun donneesDEssaiDuRangement() {
        // mini.mvr : une vraie archive MVR, 6 projecteurs N° 101–106, 2 calques, 2 classes.
        val xml = ZipInputStream(fixture("mini.mvr").inputStream()).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "GeneralSceneDescription.xml" }
            z.readBytes()
        }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
        val ids = doc.getElementsByTagName("FixtureID").let { n -> (0 until n.length).map { n.item(it).textContent } }
        assertEquals(listOf("101", "102", "103", "104", "105", "106"), ids)
        assertEquals(2, doc.getElementsByTagName("Layer").length)
        assertEquals(2, doc.getElementsByTagName("Class").length)
        // mini.dxf : lu par le vrai lecteur DXF (2 calques).
        val dxf = com.minou.mvrviewer.mvr.DxfParser.parse(fixture("mini.dxf").inputStream())
        assertTrue(dxf != null && dxf.layerCounts.size == 2)
        // bad.mvr : pas une archive (lecture impossible).
        val head = fixture("bad.mvr").readBytes().take(2)
        assertFalse(head == listOf('P'.code.toByte(), 'K'.code.toByte()))
    }
}
