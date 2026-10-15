package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.DxfParser
import com.minou.mvrviewer.mvr.DxfPlan
import com.minou.mvrviewer.mvr.Mat4
import com.minou.mvrviewer.mvr.MvrLayer
import com.minou.mvrviewer.mvr.MvrObjectKind
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.MvrSceneObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Panneau « Plan importé » (SPEC-menus-android §3.7, tranche B) : rotation
 * verticale en 3D seulement, section Calques à plus d'un calque, import hissé
 * dans SceneScreen avec le MÊME centrage que la vue plan (mini.mvr + mini.dxf).
 */
class RefPlanPanelRulesTest {

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("menus/$name")!!.toURI())

    private fun miniDxf(): DxfPlan = DxfParser.parse(fixture("mini.dxf").inputStream())!!

    /**
     * mini.mvr lu SANS `android.util.Xml` (absent en JVM) : mêmes objets, mêmes
     * matrices (`MvrParser.parseMatrix`) que le vrai lecteur.
     */
    private fun miniScene(): MvrScene {
        val xml = ZipInputStream(fixture("mini.mvr").inputStream()).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "GeneralSceneDescription.xml" }
            z.readBytes()
        }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
        val layers = doc.getElementsByTagName("Layer").let { n -> (0 until n.length).map { n.item(it) as Element } }
        return MvrScene(layers.map { l ->
            val child = l.getElementsByTagName("ChildList").item(0) as Element
            val objs = (0 until child.childNodes.length).mapNotNull { child.childNodes.item(it) as? Element }.map { o ->
                fun text(tag: String) = o.getElementsByTagName(tag).item(0)?.textContent
                MvrSceneObject(
                    uuid = o.getAttribute("uuid"), name = o.getAttribute("name"),
                    kind = MvrObjectKind.from(o.tagName),
                    transform = MvrParser.parseMatrix(text("Matrix")!!) ?: Mat4(),
                    geometryRefs = emptyList(), gdtfSpec = text("GDTFSpec"), gdtfMode = text("GDTFMode"),
                    fixtureId = text("FixtureID"), addresses = emptyList(), layerName = l.getAttribute("name")
                )
            }
            MvrLayer(l.getAttribute("name"), objs)
        }, emptyMap())
    }

    @Test fun rotationVerticaleEn3DSeulement() {
        assertTrue(RefPlanPanelRules.showsVerticalRotation(ProjectSurface.SCENE_3D))
        assertFalse("sans effet visible en 2D", RefPlanPanelRules.showsVerticalRotation(ProjectSurface.PLAN))
    }

    @Test fun sectionCalquesAPlusDUnCalque() {
        val dxf = miniDxf()
        assertEquals(2, dxf.layerCounts.size)
        assertTrue(RefPlanPanelRules.showsLayers(dxf))
        val rows = RefPlanPanelRules.layerRows(dxf)
        assertEquals(dxf.layerCounts.keys, rows.map { it.first }.toSet())
        // Du plus fourni au moins fourni.
        assertEquals(rows.map { it.second }.sortedDescending(), rows.map { it.second })
        // Un seul calque (ou une image : DxfPlan vide) → pas de section.
        val one = DxfPlan(dxf.polylines, dxf.minX, dxf.minY, dxf.maxX, dxf.maxY, dxf.unitLabel,
            dxf.segmentCount, mapOf("0" to 3), dxf.truncatedSegments)
        assertFalse(RefPlanPanelRules.showsLayers(one))
        assertFalse(RefPlanPanelRules.showsLayers(com.minou.mvrviewer.mvr.emptyDxfPlanFor(1000f, 500f)))
    }

    @Test fun raisonsProEtPas() {
        assertEquals("régler le plan importé", RefPlanPanelRules.PRO_REASON)
        assertEquals("modifier les calques du plan importé", RefPlanPanelRules.LAYERS_PRO_REASON)
        assertEquals("importer un plan", RefPlanPanelRules.IMPORT_PRO_REASON)
        val dxf = miniDxf()
        assertEquals(maxOf(200.0, dxf.width * 0.05), RefPlanPanelRules.moveStep(dxf), 1e-9)
    }

    @Test fun planPretDxfOuImage() {
        val dxf = miniDxf()
        val ready = RefPlanImport.ready(ImportedRefPlan(dxf = dxf))
        assertNotNull(ready)
        assertTrue(ready!!.first === dxf && ready.second == null)
        // DXF sans géométrie : rien à poser.
        val empty = com.minou.mvrviewer.mvr.emptyDxfPlanFor(0f, 0f)
        assertNull(RefPlanImport.ready(ImportedRefPlan(dxf = empty)))
        assertNull(RefPlanImport.ready(ImportedRefPlan()))
    }

    /**
     * Centrage calculé par SceneScreen (planData de la scène rendue) = centrage
     * historique de la vue plan : le centre du DXF tombe au centre des projecteurs.
     */
    @Test fun centrageDuPlanImporteIdentiqueAuPlan() {
        val scene = miniScene()
        assertEquals(6, scene.fixtures.size)
        val d = planData(scene)
        // Projecteurs : x de −3000 à 2000, y MVR de −4000 à 3000 → repère plan (x, −y).
        assertEquals(-500f, d.cx, 1e-3f)
        assertEquals(500f, d.cy, 1e-3f)
        val dxf = miniDxf()
        val t = RefPlanImport.initialTransform(d.cx, d.cy, dxf)
        // Formule historique (PlanScreen avant la tranche B), inchangée.
        assertEquals((d.cx - dxf.centerX).toDouble(), t.offsetX, 1e-6)
        assertEquals((-d.cy - dxf.centerY).toDouble(), t.offsetY, 1e-6)
        // Le centre du plan arrive au centre du rig, en coordonnées monde MVR.
        assertEquals(-500.0, dxf.centerX + t.offsetX, 1e-3)
        assertEquals(-500.0, dxf.centerY + t.offsetY, 1e-3)
        assertEquals(0.0, t.rotationDeg, 0.0)
        assertEquals(1.0, t.scale, 0.0)
        assertEquals(1.0, t.homothety, 0.0)
        assertTrue(t.visible)
    }

    @Test fun textesFrEn() {
        val expected = mapOf(
            "refplan_panel_title" to ("Plan importé" to "Imported plan"),
            "refplan_show" to ("Afficher le plan" to "Show the plan"),
            "refplan_remove" to ("Retirer le plan" to "Remove the plan"),
            "menu_adjust_imported_plan" to ("Régler le plan importé…" to "Adjust the imported plan…"),
            "tool_import_ref_plan" to ("Importer un plan (DXF, image, PDF)…" to "Import a plan (DXF, image, PDF)…"),
            "refplan_reading" to ("Lecture du plan…" to "Reading the plan…")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, TestRes.FR.get(k))
            assertEquals("en $k", v.second, TestRes.EN.get(k))
        }
    }
}
