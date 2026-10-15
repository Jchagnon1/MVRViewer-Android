package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Barres ancrées, tranche E (SPEC-menus-android §3.13, §4.6) : défaut selon
 * l'appareil, disposition enregistrée toujours respectée, migration v2 une seule
 * fois, catalogues, barre gauche qui évite ce qui occupe sa colonne.
 */
class ToolbarDefaultsTest {

    /** Préférences en mémoire (les SharedPreferences de l'app, sans Android). */
    private class MemPrefs : ToolbarPrefs {
        val strings = HashMap<String, String>()
        val booleans = HashMap<String, Boolean>()
        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { strings.remove(key) }
        override fun getBoolean(key: String): Boolean = booleans[key] ?: false
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    }

    private val tabletBar = listOf(ToolId.PATCH, ToolId.UNIVERSE, ToolId.CABLING, ToolId.LAYERS)

    // ---- Appareil ----

    @Test fun `tablette = plus petite largeur 600 dp ou crochet`() {
        assertEquals(ToolbarIdiom.PHONE, ToolbarIdiom.of(411))
        assertEquals(ToolbarIdiom.PHONE, ToolbarIdiom.of(599))
        assertEquals(ToolbarIdiom.TABLET, ToolbarIdiom.of(600))
        assertEquals(ToolbarIdiom.TABLET, ToolbarIdiom.of(800))
        assertEquals(ToolbarIdiom.TABLET, ToolbarIdiom.of(411, forceTablet = true))
    }

    // ---- Défauts ----

    @Test fun `tablette sans disposition - barre gauche Patch Univers Cablage Calques dans les deux vues`() {
        val p = MemPrefs()
        for (view in ToolbarView.entries) {
            val l = ToolbarLayoutStore.load(p, view, ToolbarIdiom.TABLET)
            assertEquals(view.name, tabletBar, l.left)
            assertTrue(l.top.isEmpty() && l.bottom.isEmpty() && l.right.isEmpty())
        }
    }

    @Test fun `telephone sans disposition - aucune barre`() {
        val p = MemPrefs()
        for (view in ToolbarView.entries) assertTrue(ToolbarLayoutStore.load(p, view, ToolbarIdiom.PHONE).isEmpty)
    }

    @Test fun `disposition enregistree vide reste vide, meme sur tablette (Tout remettre hors barres)`() {
        val p = MemPrefs()
        ToolbarLayoutStore.load(p, ToolbarView.PLAN, ToolbarIdiom.TABLET) // migration faite
        ToolbarLayoutStore.save(p, ToolbarView.PLAN, ToolbarLayout.EMPTY)
        assertTrue(ToolbarLayoutStore.load(p, ToolbarView.PLAN, ToolbarIdiom.TABLET).isEmpty)
        // « au relancement » : une nouvelle lecture ne rejoue pas la migration.
        assertTrue(ToolbarLayoutStore.load(p, ToolbarView.PLAN, ToolbarIdiom.TABLET).isEmpty)
        // L'autre vue garde son défaut.
        assertEquals(tabletBar, ToolbarLayoutStore.load(p, ToolbarView.SCENE_3D, ToolbarIdiom.TABLET).left)
    }

    @Test fun `disposition personnalisee gardee telle quelle (telephone et tablette)`() {
        val p = MemPrefs()
        val mine = ToolbarLayout(bottom = listOf(ToolId.MASK, ToolId.SOLO))
        p.putString(ToolbarLayoutStore.KEY_PLAN, mine.toJson()) // d'avant la migration
        assertEquals(mine, ToolbarLayoutStore.load(p, ToolbarView.PLAN, ToolbarIdiom.PHONE))
        assertEquals(mine, ToolbarLayoutStore.load(p, ToolbarView.PLAN, ToolbarIdiom.TABLET))
    }

    @Test fun `Reinitialiser efface le choix - defaut de l'appareil`() {
        val p = MemPrefs()
        ToolbarLayoutStore.save(p, ToolbarView.SCENE_3D, ToolbarLayout(right = listOf(ToolId.MEASURE)))
        ToolbarLayoutStore.clear(p, ToolbarView.SCENE_3D)
        assertEquals(tabletBar, ToolbarLayoutStore.load(p, ToolbarView.SCENE_3D, ToolbarIdiom.TABLET).left)
        assertTrue(ToolbarLayoutStore.load(p, ToolbarView.SCENE_3D, ToolbarIdiom.PHONE).isEmpty)
    }

    @Test fun `crochet MVR_RESET_TOOLBARS - les deux vues repartent du defaut`() {
        val p = MemPrefs()
        ToolbarLayoutStore.save(p, ToolbarView.SCENE_3D, ToolbarLayout.EMPTY)
        ToolbarLayoutStore.save(p, ToolbarView.PLAN, ToolbarLayout.EMPTY)
        ToolbarLayoutStore.reset(p)
        for (view in ToolbarView.entries)
            assertEquals(tabletBar, ToolbarLayoutStore.load(p, view, ToolbarIdiom.TABLET).left)
    }

    // ---- Migration v2 ----

    @Test fun `migration - une disposition vide d'avant compte comme non personnalisee, une fois`() {
        val p = MemPrefs()
        // Avant E, la scène écrivait la disposition vide dès la première ouverture.
        p.putString(ToolbarLayoutStore.KEY_3D, ToolbarLayout.EMPTY.toJson())
        p.putString(ToolbarLayoutStore.KEY_PLAN, ToolbarLayout.EMPTY.toJson())
        assertEquals(tabletBar, ToolbarLayoutStore.load(p, ToolbarView.SCENE_3D, ToolbarIdiom.TABLET).left)
        assertEquals(tabletBar, ToolbarLayoutStore.load(p, ToolbarView.PLAN, ToolbarIdiom.TABLET).left)
        assertTrue(p.getBoolean(ToolbarLayoutStore.KEY_MIGRATED_V2))
        // Une fois seulement : un vide choisi APRÈS reste vide.
        ToolbarLayoutStore.save(p, ToolbarView.PLAN, ToolbarLayout.EMPTY)
        ToolbarLayoutStore.migrate(p)
        assertTrue(ToolbarLayoutStore.load(p, ToolbarView.PLAN, ToolbarIdiom.TABLET).isEmpty)
    }

    @Test fun `migration - illisible d'avant efface`() {
        val p = MemPrefs()
        p.putString(ToolbarLayoutStore.KEY_PLAN, "{pas du json")
        assertEquals(tabletBar, ToolbarLayoutStore.load(p, ToolbarView.PLAN, ToolbarIdiom.TABLET).left)
        assertNull(p.getString(ToolbarLayoutStore.KEY_PLAN))
    }

    @Test fun `migration - gdtfShare enregistre devient gdtfMapping a sa place, une fois`() {
        val p = MemPrefs()
        p.putString(ToolbarLayoutStore.KEY_3D,
            """{"top":[],"bottom":[],"left":["patchList","gdtfShare","cabling"],"right":[]}""")
        val l = ToolbarLayoutStore.load(p, ToolbarView.SCENE_3D, ToolbarIdiom.PHONE)
        assertEquals(listOf(ToolId.PATCH, ToolId.GDTF_MAPPING, ToolId.CABLING), l.left)
        assertTrue(p.getString(ToolbarLayoutStore.KEY_3D)!!.contains("\"gdtfMapping\""))
        // Ensuite, `gdtfShare` est l'outil de connexion (sens iOS) : ancré, il le reste.
        val again = ToolbarLayout(right = listOf(ToolId.GDTF_SHARE))
        ToolbarLayoutStore.save(p, ToolbarView.SCENE_3D, again)
        assertEquals(again, ToolbarLayoutStore.load(p, ToolbarView.SCENE_3D, ToolbarIdiom.PHONE))
    }

    @Test fun `replacing - jamais de doublon`() {
        val l = ToolbarLayout(left = listOf(ToolId.GDTF_SHARE), right = listOf(ToolId.GDTF_MAPPING))
        val r = l.replacing(ToolId.GDTF_SHARE, ToolId.GDTF_MAPPING)
        assertTrue(r.left.isEmpty())
        assertEquals(listOf(ToolId.GDTF_MAPPING), r.right)
        // Outil absent : inchangé.
        assertEquals(ToolbarLayout(top = listOf(ToolId.MEASURE)),
            ToolbarLayout(top = listOf(ToolId.MEASURE)).replacing(ToolId.GDTF_SHARE, ToolId.GDTF_MAPPING))
    }

    // ---- Catalogues ----

    @Test fun `catalogue du plan - fenetres, multi, Position GPS, Regler le plan importe`() {
        for (id in listOf(ToolId.PATCH, ToolId.UNIVERSE, ToolId.CABLING, ToolId.LAYERS, ToolId.MULTI_SELECT,
            ToolId.GEO_PANEL, ToolId.REF_PLAN_PANEL, ToolId.DXF))
            assertTrue(id.name, id in ToolCatalog.PLAN)
    }

    @Test fun `catalogue 3D - multi, Position GPS, plan importe, modeles, gdtfMapping apres gdtfShare`() {
        for (id in listOf(ToolId.MULTI_SELECT, ToolId.GEO_PANEL, ToolId.REF_PLAN_PANEL, ToolId.DXF,
            ToolId.MODELS_PANEL, ToolId.IMPORT_MODEL, ToolId.GDTF_MAPPING, ToolId.GDTF_SHARE))
            assertTrue(id.name, id in ToolCatalog.SCENE_3D)
        assertEquals(ToolCatalog.SCENE_3D.indexOf(ToolId.GDTF_SHARE) + 1, ToolCatalog.SCENE_3D.indexOf(ToolId.GDTF_MAPPING))
        // Ordre iOS : importer puis régler, de part et d'autre.
        val c = ToolCatalog.SCENE_3D
        assertTrue(c.indexOf(ToolId.DXF) < c.indexOf(ToolId.REF_PLAN_PANEL))
        assertTrue(c.indexOf(ToolId.REF_PLAN_PANEL) < c.indexOf(ToolId.IMPORT_MODEL))
        assertTrue(c.indexOf(ToolId.IMPORT_MODEL) < c.indexOf(ToolId.MODELS_PANEL))
    }

    @Test fun `la barre de la tablette est dans les deux catalogues, sans doublon`() {
        for (c in listOf(ToolCatalog.SCENE_3D, ToolCatalog.PLAN)) {
            assertTrue(c.containsAll(tabletBar))
            assertEquals(c.size, c.toSet().size)
        }
        for (inert in listOf(ToolId.COLOR_MODE, ToolId.SEARCH, ToolId.CAM_PRESETS)) {
            assertFalse(inert in ToolCatalog.SCENE_3D); assertFalse(inert in ToolCatalog.PLAN)
        }
    }

    @Test fun `ordered range dans l'ordre du catalogue et ecarte le reste`() {
        fun spec(id: ToolId) = ToolSpec(id, id.rawId, androidx.compose.ui.graphics.vector.ImageVector.Builder(
            defaultWidth = androidx.compose.ui.unit.Dp(1f), defaultHeight = androidx.compose.ui.unit.Dp(1f),
            viewportWidth = 1f, viewportHeight = 1f).build(), true, null, {})
        val specs = listOf(spec(ToolId.LAYERS), spec(ToolId.SEARCH), spec(ToolId.RECT), spec(ToolId.PATCH))
        assertEquals(listOf(ToolId.RECT, ToolId.LAYERS, ToolId.PATCH),
            ToolCatalog.ordered(ToolbarView.PLAN, specs).map { it.id })
    }

    /** Les `ToolSpec` construits par chaque écran = son catalogue (aucun oubli, aucun intrus). */
    @Test fun `chaque ecran construit exactement les outils de son catalogue`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        fun ids(file: String): Set<String> {
            val code = File(root, "com/minou/mvrviewer/ui/$file").readText()
            return Regex("""ToolSpec\(ToolId\.([A-Z_0-9]+),""").findAll(code).map { it.groupValues[1] }.toSet()
        }
        assertEquals(ToolCatalog.SCENE_3D.map { it.name }.toSet(), ids("Scene3DScreen.kt"))
        assertEquals(ToolCatalog.PLAN.map { it.name }.toSet(), ids("PlanScreen.kt"))
    }

    @Test fun `la scene n'enregistre plus la disposition a l'ouverture`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val code = File(root, "com/minou/mvrviewer/ui/SceneScreen.kt").readText()
        assertFalse(Regex("""LaunchedEffect\(layout(3D|Plan)\)""").containsMatchIn(code))
        assertTrue(code.contains("ToolbarLayoutStore.save(ctx, view, l)"))
    }

    // ---- Barre gauche qui évite ce qui occupe sa colonne ----

    private val bar = Rect(12f, 400f, 60f, 640f) // 4 boutons centrés, écran 800 × 1280

    @Test fun `barre gauche - rien dans sa colonne, elle ne bouge pas`() {
        val far = Rect(300f, 380f, 600f, 700f) // fiche centrée, hors colonne
        assertEquals(PanelPlacementMath.LeadingBarOffset.NONE,
            PanelPlacementMath.leadingBarOffset(bar, listOf(far, Rect.Zero), 60f, 1280f, maxX = 800f))
    }

    @Test fun `barre gauche - monte au-dessus d'un panneau du bas-gauche`() {
        val panel = Rect(12f, 560f, 276f, 1200f)
        val o = PanelPlacementMath.leadingBarOffset(bar, listOf(panel), 60f, 1280f, maxX = 800f)
        assertEquals(560f - 8f - 640f, o.dy, 0.01f)
        assertEquals(0f, o.dx, 0f)
    }

    @Test fun `barre gauche - descend sous la legende du haut (Android, D11)`() {
        val legend = Rect(12f, 100f, 200f, 460f)
        val o = PanelPlacementMath.leadingBarOffset(bar, listOf(legend), 60f, 1280f, maxX = 800f)
        assertEquals(460f + 8f - 400f, o.dy, 0.01f)
        assertEquals(0f, o.dx, 0f)
    }

    @Test fun `barre gauche - la place libre la plus proche gagne`() {
        // Obstacle qui couvre le haut de la barre : descendre de 48 bat monter de 260.
        val o = PanelPlacementMath.leadingBarOffset(bar, listOf(Rect(12f, 380f, 200f, 440f)), 0f, 1280f)
        assertEquals(48f, o.dy, 0.01f)
    }

    @Test fun `barre gauche - sans place, a droite de ce qu'elle chevauche`() {
        // Légende longue en haut + panneau ouvert en bas : aucune hauteur libre.
        val legend = Rect(12f, 100f, 200f, 560f)
        val panel = Rect(12f, 600f, 276f, 1200f)
        val o = PanelPlacementMath.leadingBarOffset(bar, listOf(legend, panel), 60f, 1280f, maxX = 800f)
        assertEquals(0f, o.dy, 0f)
        assertEquals(276f + 8f - 12f, o.dx, 0.01f)
    }

    @Test fun `barre gauche - pas de place a droite non plus, elle reste`() {
        val wide = Rect(0f, 0f, 790f, 1280f)
        assertEquals(PanelPlacementMath.LeadingBarOffset.NONE,
            PanelPlacementMath.leadingBarOffset(bar, listOf(wide), 60f, 1280f, maxX = 800f))
    }

    @Test fun `barre gauche - jamais au-dessus de la borne haute`() {
        val panel = Rect(12f, 450f, 276f, 1200f) // il faudrait monter jusqu'à 202
        val o = PanelPlacementMath.leadingBarOffset(bar, listOf(panel), topLimit = 300f, bottomLimit = 1280f, maxX = 800f)
        assertEquals(0f, o.dy, 0f)
        assertEquals(276f + 8f - 12f, o.dx, 0.01f)
    }

    @Test fun `barre gauche - barre non mesuree, rien`() {
        assertEquals(PanelPlacementMath.LeadingBarOffset.NONE,
            PanelPlacementMath.leadingBarOffset(Rect.Zero, listOf(Rect(0f, 0f, 100f, 100f)), 0f, 1000f))
    }
}
