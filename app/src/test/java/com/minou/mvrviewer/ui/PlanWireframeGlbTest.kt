package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.GlbMeshParser
import com.minou.mvrviewer.mvr.Mat4
import com.minou.mvrviewer.mvr.MvrGeometryRef
import com.minou.mvrviewer.mvr.MvrLayer
import com.minou.mvrviewer.mvr.MvrObjectKind
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.MvrSceneObject
import com.minou.mvrviewer.mvr.MvrSymdef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * NON-RÉGRESSION du bug « les ponts n'apparaissent pas en vue plan » : sur un
 * show 100 % glTF (exports Vectorworks récents), la vue plan ne savait lire que
 * le `.3ds` et réduisait TOUT le décor à un point. On vérifie ici les deux
 * maillons : le lecteur de sommets `.glb` lui-même, puis la chaîne complète
 * `Truss → Symdef → Geometry3D(.glb) → arêtes du plan`, échelle comprise.
 */
class PlanWireframeGlbTest {

    // ---- Lecteur .glb ----

    @Test fun `glb parser reads a box`() {
        val meshes = GlbMeshParser.parse(boxGlb())
        assertEquals(1, meshes.size)
        assertEquals(12, meshes[0].triangleCount)
        assertEquals(8, meshes[0].vertexCount)
        // Boîte de 2 m × 1 m × 0,5 m centrée sur l'origine, unités du FICHIER.
        val (min, max) = bounds(meshes[0].vertices)
        assertEquals(-1f, min[0], 1e-4f); assertEquals(1f, max[0], 1e-4f)
        assertEquals(-0.25f, min[2], 1e-4f); assertEquals(0.25f, max[2], 1e-4f)
    }

    @Test fun `glb parser applies node transforms`() {
        val meshes = GlbMeshParser.parse(boxGlb(translation = floatArrayOf(10f, 0f, 0f)))
        val (min, max) = bounds(meshes[0].vertices)
        assertEquals(9f, min[0], 1e-4f); assertEquals(11f, max[0], 1e-4f)
    }

    @Test fun `glb parser refuses a required extension it cannot read`() {
        // Draco & co. : mieux vaut zéro triangle qu'un maillage faux.
        assertTrue(GlbMeshParser.parse(boxGlb(requiredExtension = "KHR_draco_mesh_compression")).isEmpty())
    }

    @Test fun `glb parser survives garbage`() {
        assertTrue(GlbMeshParser.parse(ByteArray(0)).isEmpty())
        assertTrue(GlbMeshParser.parse(ByteArray(64) { 0x7F }).isEmpty())
    }

    // ---- Chaîne complète de la vue plan ----

    @Test fun `truss with a glb symdef gets real plan edges`() {
        val scene = trussScene()
        val mvr = zipOf("model.glb" to boxGlb())

        val built = PlanWireframe.build(scene, mvr)

        assertEquals(1, built.instances.size)
        val edges = built.edgesByKey.values.firstOrNull()
        assertTrue("aucune arête extraite du .glb — le pont serait invisible", edges != null && edges.isNotEmpty())
        // Échelle ET orientation. Le .glb est en MÈTRES → ×1000 (une boîte de 2 m
        // doit faire 2000 mm, pas 2, sinon elle est invisible). Il est aussi
        // Y-HAUT (le format glTF l'impose) alors que le monde MVR est Z-haut →
        // Rx(+90°) : la dimension Y du fichier (1 m) devient la HAUTEUR Z, et sa
        // dimension Z (0,5 m) devient la profondeur Y du plan. Sans cette
        // rotation un praticable se dessinerait debout.
        val (min, max) = bounds(edges!!)
        assertEquals(2000f, max[0] - min[0], 1f)
        assertEquals(500f, max[1] - min[1], 1f)
        assertEquals(1000f, max[2] - min[2], 1f)
        // Arêtes CARACTÉRISTIQUES : les 12 arêtes du cube, pas les diagonales de
        // triangulation (24 points = 12 segments).
        assertEquals(12, edges.size / 6)
    }

    @Test fun `structures still get an instance when no edge can be extracted`() {
        // Fichier de géométrie absent de l'archive : plus aucune arête possible.
        // L'objet doit RESTER dans les instances (il garde son point sur le plan)
        // — sortir en `EMPTY` privait la vue plan de toute trace des structures.
        val built = PlanWireframe.build(trussScene(), zipOf("autre.glb" to boxGlb()))
        assertEquals(1, built.instances.size)
        assertTrue(built.edgesByKey.isEmpty())
    }

    // ---- Fabrique de fichiers de test ----

    private fun trussScene(): MvrScene {
        val symdef = MvrSymdef(listOf(MvrGeometryRef.File("model.glb", Mat4())))
        val truss = MvrSceneObject(
            uuid = "T1", name = "Pont 1", kind = MvrObjectKind.TRUSS, transform = Mat4(),
            geometryRefs = listOf(MvrGeometryRef.Symbol("SYM", Mat4())),
            gdtfSpec = null, gdtfMode = null, fixtureId = null, addresses = emptyList(),
            layerName = "Structure"
        )
        return MvrScene(listOf(MvrLayer("Structure", listOf(truss))), mapOf("SYM" to symdef))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun bounds(v: FloatArray): Pair<FloatArray, FloatArray> {
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i + 2 < v.size) {
            for (k in 0..2) {
                if (v[i + k] < min[k]) min[k] = v[i + k]
                if (v[i + k] > max[k]) max[k] = v[i + k]
            }
            i += 3
        }
        return min to max
    }

    /**
     * Un .glb minimal mais RÉEL (conteneur binaire + chunk JSON + chunk BIN),
     * bâti comme les exports Vectorworks : indices UNSIGNED_INT, POSITION
     * FLOAT/VEC3, une primitive en mode TRIANGLES.
     */
    private fun boxGlb(
        translation: FloatArray? = null,
        requiredExtension: String? = null
    ): ByteArray {
        val hx = 1f; val hy = 0.5f; val hz = 0.25f
        val pos = floatArrayOf(
            -hx, -hy, -hz, hx, -hy, -hz, hx, hy, -hz, -hx, hy, -hz,
            -hx, -hy, hz, hx, -hy, hz, hx, hy, hz, -hx, hy, hz
        )
        val idx = intArrayOf(
            0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
            1, 5, 6, 1, 6, 2, 2, 6, 7, 2, 7, 3, 3, 7, 4, 3, 4, 0
        )
        val bin = ByteBuffer.allocate(pos.size * 4 + idx.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        pos.forEach { bin.putFloat(it) }
        idx.forEach { bin.putInt(it) }
        val binBytes = bin.array()
        val posLen = pos.size * 4

        val node = StringBuilder("""{"mesh":0""")
        if (translation != null) node.append(""","translation":[${translation.joinToString(",")}]""")
        node.append("}")
        val req = if (requiredExtension == null) "" else ""","extensionsRequired":["$requiredExtension"]"""
        val json = """
            {"asset":{"version":"2.0"}$req,
             "scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[$node],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1,"mode":4}]}],
             "accessors":[
               {"bufferView":0,"componentType":5126,"count":${pos.size / 3},"type":"VEC3"},
               {"bufferView":1,"componentType":5125,"count":${idx.size},"type":"SCALAR"}],
             "bufferViews":[
               {"buffer":0,"byteOffset":0,"byteLength":$posLen},
               {"buffer":0,"byteOffset":$posLen,"byteLength":${idx.size * 4}}],
             "buffers":[{"byteLength":${binBytes.size}}]}
        """.trimIndent().replace("\n", "").toByteArray(Charsets.UTF_8)
        val jsonPadded = json + ByteArray((4 - json.size % 4) % 4) { ' '.code.toByte() }
        val binPadded = binBytes + ByteArray((4 - binBytes.size % 4) % 4)

        val total = 12 + 8 + jsonPadded.size + 8 + binPadded.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67); buf.putInt(2); buf.putInt(total)
        buf.putInt(jsonPadded.size); buf.putInt(0x4E4F534A); buf.put(jsonPadded)
        buf.putInt(binPadded.size); buf.putInt(0x004E4942); buf.put(binPadded)
        return buf.array()
    }
}
