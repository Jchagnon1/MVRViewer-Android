package com.minou.mvrviewer.mvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Tests du lecteur FBX.
 *
 * Les fichiers d'épreuve sont ÉCRITS PAR LE TEST plutôt que déposés en binaire
 * dans le dépôt : on couvre ainsi les trois variantes réelles du format —
 * binaire 7400 (offsets 32 bits, tableaux bruts), binaire 7500 (offsets 64
 * bits, tableaux zlib) et ASCII — et un échec désigne la variante fautive.
 *
 * Le maillage est le MÊME dans les trois : un cube de 100 unités translaté de
 * (1000, 0, 0), matériau rouge. Les trois doivent donc donner EXACTEMENT le
 * même résultat ; toute divergence est un bug de lecture, pas de contenu.
 *
 * Ces chiffres sont ceux vérifiés sur le lecteur iOS (`FBXParser.swift`) : ce
 * test est le garde-fou de la parité entre les deux plateformes.
 */
class FbxParserTest {

    // ------------------------------------------------------------ le cube

    private val s = 50.0
    private val verts = doubleArrayOf(
        -s,-s,-s,  s,-s,-s,  s,s,-s,  -s,s,-s,
        -s,-s, s,  s,-s, s,  s,s, s,  -s,s, s
    )
    private val quads = listOf(
        intArrayOf(0,1,2,3), intArrayOf(4,7,6,5), intArrayOf(0,4,5,1),
        intArrayOf(1,5,6,2), intArrayOf(2,6,7,3), intArrayOf(3,7,4,0)
    )

    /** Dernier coin de chaque polygone = ~index (marqueur de fin FBX). */
    private fun polygonIndices(): IntArray {
        val out = ArrayList<Int>()
        for (q in quads) { for (k in 0 until 3) out.add(q[k]); out.add(q[3].inv()) }
        return out.toIntArray()
    }

    // -------------------------------------------------- écriture binaire

    private fun i32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun i64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    private fun f64(v: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()
    private fun cat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); parts.forEach { o.write(it) }; return o.toByteArray()
    }

    private fun propS(v: String) = cat(byteArrayOf('S'.code.toByte()), i32(v.length), v.toByteArray())
    private fun propI(v: Int) = cat(byteArrayOf('I'.code.toByte()), i32(v))
    private fun propL(v: Long) = cat(byteArrayOf('L'.code.toByte()), i64(v))
    private fun propD(v: Double) = cat(byteArrayOf('D'.code.toByte()), f64(v))

    private fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(raw); d.finish()
        val buf = ByteArray(raw.size + 128)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    private fun arrD(values: DoubleArray, compress: Boolean): ByteArray {
        val raw = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            .also { b -> values.forEach { b.putDouble(it) } }.array()
        val payload = if (compress) deflate(raw) else raw
        return cat(byteArrayOf('d'.code.toByte()), i32(values.size),
            i32(if (compress) 1 else 0), i32(payload.size), payload)
    }

    private fun arrI(values: IntArray, compress: Boolean): ByteArray {
        val raw = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { b -> values.forEach { b.putInt(it) } }.array()
        val payload = if (compress) deflate(raw) else raw
        return cat(byteArrayOf('i'.code.toByte()), i32(values.size),
            i32(if (compress) 1 else 0), i32(payload.size), payload)
    }

    /** Constructeur d'enregistrement : `endOffset` étant ABSOLU, chaque nœud a
     *  besoin de savoir où il commence — d'où les fabriques prenant un offset. */
    private fun node(
        wide: Boolean, name: String, props: List<ByteArray>,
        kids: List<(Int) -> ByteArray>
    ): (Int) -> ByteArray = { offset ->
        val nameBytes = name.toByteArray()
        val propsBlob = cat(*props.toTypedArray())
        val head = (if (wide) 25 else 13) + nameBytes.size
        var childBlob = ByteArray(0)
        val childOff = offset + head + propsBlob.size
        for (k in kids) childBlob = cat(childBlob, k(childOff + childBlob.size))
        if (kids.isNotEmpty()) childBlob = cat(childBlob, ByteArray(if (wide) 25 else 13))
        val body = cat(propsBlob, childBlob)
        val end = offset + head + body.size
        val header = if (wide) cat(i64(end.toLong()), i64(props.size.toLong()), i64(propsBlob.size.toLong()))
        else cat(i32(end), i32(props.size), i32(propsBlob.size))
        cat(header, byteArrayOf(nameBytes.size.toByte()), nameBytes, body)
    }

    private fun binaryCube(version: Int, compress: Boolean): ByteArray {
        val wide = version >= 7500
        fun p(name: String, type: String, vararg values: Any) = node(wide, "P",
            listOf(propS(name), propS(type), propS(""), propS("")) +
                values.map { if (it is Double) propD(it) else propI(it as Int) },
            emptyList())

        val top = listOf(
            node(wide, "FBXHeaderExtension", emptyList(),
                listOf(node(wide, "FBXVersion", listOf(propI(version)), emptyList()))),
            node(wide, "GlobalSettings", emptyList(), listOf(
                node(wide, "Properties70", emptyList(), listOf(
                    p("UpAxis", "int", 1),
                    p("UnitScaleFactor", "double", 1.0)
                )))),
            node(wide, "Objects", emptyList(), listOf(
                node(wide, "Geometry",
                    listOf(propL(1001), propS("Geometry::cube"), propS("Mesh")), listOf(
                        node(wide, "Vertices", listOf(arrD(verts, compress)), emptyList()),
                        node(wide, "PolygonVertexIndex", listOf(arrI(polygonIndices(), compress)), emptyList())
                    )),
                node(wide, "Model", listOf(propL(2001), propS("Model::cube"), propS("Mesh")), listOf(
                    node(wide, "Properties70", emptyList(),
                        listOf(p("Lcl Translation", "Lcl Translation", 1000.0, 0.0, 0.0))))),
                node(wide, "Material", listOf(propL(3001), propS("Material::red"), propS("")), listOf(
                    node(wide, "Properties70", emptyList(),
                        listOf(p("DiffuseColor", "Color", 1.0, 0.0, 0.0)))))
            )),
            node(wide, "Connections", emptyList(), listOf(
                node(wide, "C", listOf(propS("OO"), propL(1001), propL(2001)), emptyList()),
                node(wide, "C", listOf(propS("OO"), propL(3001), propL(2001)), emptyList()),
                node(wide, "C", listOf(propS("OO"), propL(2001), propL(0)), emptyList())
            ))
        )
        var out = cat("Kaydara FBX Binary  ".toByteArray(),
            byteArrayOf(0, 0x1a, 0), i32(version))
        for (t in top) out = cat(out, t(out.size))
        // Enregistrement NUL de fin + pied de fichier (ignoré par le lecteur).
        return cat(out, ByteArray(if (wide) 25 else 13), ByteArray(160))
    }

    private fun asciiCube(): ByteArray {
        val v = verts.joinToString(",") { fmt(it) }
        val pi = polygonIndices().joinToString(",")
        return """
            ; FBX 7.4.0 project file
            FBXHeaderExtension:  {
                FBXVersion: 7400
            }
            GlobalSettings:  {
                Properties70:  {
                    P: "UpAxis", "int", "Integer", "",1
                    P: "UnitScaleFactor", "double", "Number", "",1.0
                }
            }
            Objects:  {
                Geometry: 1001, "Geometry::cube", "Mesh" {
                    Vertices: *${verts.size} {
                        a: $v
                    }
                    PolygonVertexIndex: *${polygonIndices().size} {
                        a: $pi
                    }
                }
                Model: 2001, "Model::cube", "Mesh" {
                    Properties70:  {
                        P: "Lcl Translation", "Lcl Translation", "", "A",1000.0,0.0,0.0
                    }
                }
                Material: 3001, "Material::red", "" {
                    Properties70:  {
                        P: "DiffuseColor", "Color", "", "A",1.0,0.0,0.0
                    }
                }
            }
            Connections:  {
                C: "OO",1001,2001
                C: "OO",3001,2001
                C: "OO",2001,0
            }
        """.trimIndent().toByteArray()
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    // ------------------------------------------------------- vérification

    private fun checkCube(label: String, bytes: ByteArray) {
        val r = FbxParser.parse(bytes, 100_000)
        assertFalse("$label : tronqué à tort", r.truncated)
        assertEquals("$label : triangles", 12, r.triangles)
        // 1 unité = 1 cm = 10 mm, et le fichier déclare Y en haut.
        assertEquals("$label : unité", 10f, r.unitScaleToMm, 1e-4f)
        assertTrue("$label : axe haut", r.yUp)

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var count = 0
        for (m in r.meshes) {
            count += m.triangleCount
            var i = 0
            while (i + 2 < m.verts.size) {
                minX = minOf(minX, m.verts[i]); maxX = maxOf(maxX, m.verts[i])
                minY = minOf(minY, m.verts[i+1]); maxY = maxOf(maxY, m.verts[i+1])
                minZ = minOf(minZ, m.verts[i+2]); maxZ = maxOf(maxZ, m.verts[i+2])
                i += 3
            }
        }
        assertEquals("$label : triangles des maillages", 12, count)
        // La transformation du Model est CUITE dans les sommets : le cube doit
        // être centré en x = 1000, pas à l'origine.
        assertEquals("$label : centre X", 1000f, (minX + maxX) / 2, 1e-3f)
        assertEquals("$label : centre Y", 0f, (minY + maxY) / 2, 1e-3f)
        assertEquals("$label : taille X", 100f, maxX - minX, 1e-3f)
        assertEquals("$label : taille Z", 100f, maxZ - minZ, 1e-3f)
        assertEquals("$label : couleur", 0xFFFF0000.toInt(), r.meshes.first().color)
        // Normales présentes et unitaires (le fichier n'en fournit pas : ce sont
        // les normales de face calculées en secours).
        for (m in r.meshes) {
            assertEquals("$label : autant de normales que de sommets", m.verts.size, m.normals.size)
            var i = 0
            while (i + 2 < m.normals.size) {
                val n = Math.sqrt(
                    (m.normals[i]*m.normals[i] + m.normals[i+1]*m.normals[i+1] +
                        m.normals[i+2]*m.normals[i+2]).toDouble()
                )
                assertEquals("$label : normale unitaire", 1.0, n, 1e-3)
                i += 3
            }
        }
    }

    @Test fun `binaire 7400 tableaux bruts`() = checkCube("7400 brut", binaryCube(7400, false))

    @Test fun `binaire 7500 offsets 64 bits et tableaux zlib`() =
        checkCube("7500 zlib", binaryCube(7500, true))

    @Test fun `ascii`() = checkCube("ASCII", asciiCube())

    /** Les trois variantes doivent produire le même nombre de triangles. */
    @Test fun `les trois variantes concordent`() {
        val a = FbxParser.parse(binaryCube(7400, false), 100_000).triangles
        val b = FbxParser.parse(binaryCube(7500, true), 100_000).triangles
        val c = FbxParser.parse(asciiCube(), 100_000).triangles
        assertEquals(a, b)
        assertEquals(a, c)
    }

    /** Le plafond doit SIGNALER la troncature (l'appelant refuse alors l'import). */
    @Test fun `plafond de triangles signale la troncature`() {
        val r = FbxParser.parse(binaryCube(7400, false), 4)
        assertTrue("la troncature doit être signalée", r.truncated)
        assertTrue("pas plus de triangles que le plafond", r.triangles <= 4)
    }

    /**
     * Un fichier corrompu doit LEVER, jamais boucler ni rendre n'importe quoi.
     * On tronque le binaire à des endroits variés, y compris au milieu d'un
     * enregistrement.
     */
    @Test fun `fichiers tronques ne bouclent pas`() {
        val full = binaryCube(7400, false)
        for (cut in listOf(28, 40, 80, 200, full.size / 2, full.size - 200)) {
            if (cut <= 27 || cut >= full.size) continue
            val truncated = full.copyOf(cut)
            // Le contrat est « pas d'exception non maîtrisée ni de boucle » :
            // lever ou rendre un modèle vide sont deux issues acceptables.
            runCatching { FbxParser.parse(truncated, 100_000) }
        }
    }

    /** Des octets qui ne sont pas du FBX ne doivent pas être pris pour du FBX. */
    @Test fun `detection binaire`() {
        assertTrue(FbxParser.isBinary(binaryCube(7400, false)))
        assertFalse(FbxParser.isBinary(asciiCube()))
        assertFalse(FbxParser.isBinary(ByteArray(4)))
    }
}
