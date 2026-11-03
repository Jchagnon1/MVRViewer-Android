package com.minou.mvrviewer.mvr

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lecteur de SOMMETS d'un glTF binaire (`.glb`) — et du `.gltf` JSON dont les
 * tampons sont embarqués en `data:` URI.
 *
 * POURQUOI CE PARSEUR EXISTE. La vue 3D confie les `.glb` à Filament, qui les
 * décode DANS LE GPU : on n'a alors aucun accès aux triangles. Or la vue plan a
 * besoin des sommets côté CPU pour extraire les *arêtes caractéristiques*
 * ([PlanWireframe]). Jusqu'ici elle ne savait lire que le `.3ds` et IGNORAIT
 * purement et simplement les `.glb` — sur un show 100 % glTF (les exports
 * Vectorworks récents le sont), tous les ponts, praticables et éléments de décor
 * se réduisaient à un POINT sur le plan. C'est le bug corrigé ici, le pendant
 * Android de `GLBFastDecoder` côté iOS.
 *
 * PÉRIMÈTRE ASSUMÉ — on lit la GÉOMÉTRIE, rien d'autre : pas de matériaux, pas
 * d'animations, pas de peaux (skins), pas de textures. Tout ce qu'on ne sait pas
 * lire est SAUTÉ sans exception (le plan retombe alors sur son repère minimal),
 * jamais propagé en erreur : un fichier exotique ne doit pas faire échouer le
 * plan entier.
 *
 * NON SUPPORTÉ, VOLONTAIREMENT : compression Draco / meshopt (déclarées dans
 * `extensionsRequired` → le fichier est refusé en bloc), accesseurs `sparse`,
 * tampons EXTERNES (`uri` pointant un fichier voisin — un MVR embarque toujours
 * ses données dans le `.glb`). Les primitives autres que TRIANGLES (mode 4) sont
 * ignorées.
 *
 * UNITÉS : aucune conversion. On rend les sommets DANS LE REPÈRE DU FICHIER
 * (les `.glb` d'un MVR sont en MÈTRES) ; c'est à l'appelant d'appliquer le ×1000
 * mètres→mm, exactement comme la vue 3D le fait avec `GLB_SCALE`.
 *
 * THREAD : fonction pure sur un `ByteArray`, aucun état global, aucune API
 * Android — appelable de n'importe quel thread et testable en JVM.
 */
object GlbMeshParser {

    /** Plafond de triangles rendus pour UN fichier (garde-fou mémoire). */
    const val MAX_TRIANGLES_PER_FILE = 600_000
    /** Plafond de sommets d'UNE primitive. */
    private const val MAX_VERTS_PER_PRIMITIVE = 400_000
    /** Profondeur maximale de la hiérarchie de nœuds (garde-fou de cycle). */
    private const val MAX_NODE_DEPTH = 16

    private const val MAGIC_GLTF = 0x46546C67          // "glTF" en little-endian
    private const val CHUNK_JSON = 0x4E4F534A          // "JSON"
    private const val CHUNK_BIN = 0x004E4942           // "BIN\0"

    /** Vrai si le nom de fichier désigne un glTF (binaire ou JSON). */
    fun isGltfName(name: String): Boolean =
        name.endsWith(".glb", ignoreCase = true) || name.endsWith(".gltf", ignoreCase = true)

    /**
     * Triangles du fichier, aplatis dans le repère RACINE du glTF (les
     * transformations de nœuds sont appliquées aux sommets). Liste vide si le
     * fichier est illisible, compressé, ou sans géométrie triangulaire.
     */
    fun parse(bytes: ByteArray): List<ThreeDSParser.Mesh> =
        runCatching { parseOrThrow(bytes) }.getOrDefault(emptyList())

    private fun parseOrThrow(bytes: ByteArray): List<ThreeDSParser.Mesh> {
        if (bytes.size < 12) return emptyList()
        val json: JSONObject
        var bin: ByteArray? = null
        if (le32(bytes, 0) == MAGIC_GLTF) {
            // Conteneur binaire : en-tête 12 o puis des chunks (longueur, type).
            var off = 12
            var jsonText: String? = null
            while (off + 8 <= bytes.size) {
                val len = le32(bytes, off)
                val type = le32(bytes, off + 4)
                off += 8
                if (len < 0 || off + len > bytes.size) break
                when (type) {
                    CHUNK_JSON -> if (jsonText == null) jsonText = String(bytes, off, len, Charsets.UTF_8)
                    CHUNK_BIN -> if (bin == null) bin = bytes.copyOfRange(off, off + len)
                }
                off += len
                // Les chunks sont alignés sur 4 octets.
                if (off % 4 != 0) off += 4 - (off % 4)
            }
            json = JSONObject(jsonText ?: return emptyList())
        } else {
            // .gltf JSON : accepté uniquement si ses tampons sont embarqués.
            val text = String(bytes, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
            if (!text.startsWith("{")) return emptyList()
            json = JSONObject(text)
        }

        // Une extension REQUISE qu'on ne sait pas lire (Draco, meshopt…) rend
        // toute la géométrie inexploitable : on renonce proprement.
        json.optJSONArray("extensionsRequired")?.let { req ->
            for (i in 0 until req.length()) {
                val name = req.optString(i)
                if (name.isNotEmpty()) return emptyList()
            }
        }

        val buffersJson = json.optJSONArray("buffers") ?: JSONArray()
        val buffers = ArrayList<ByteArray?>(buffersJson.length())
        for (i in 0 until buffersJson.length()) {
            val b = buffersJson.optJSONObject(i)
            val uri = b?.optString("uri", "").orEmpty()
            buffers.add(
                when {
                    uri.isEmpty() -> bin                     // tampon du chunk BIN
                    uri.startsWith("data:") -> decodeDataUri(uri)
                    else -> null                             // tampon externe : non géré
                }
            )
        }

        val views = json.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = json.optJSONArray("accessors") ?: JSONArray()
        val meshesJson = json.optJSONArray("meshes") ?: JSONArray()
        val nodesJson = json.optJSONArray("nodes") ?: JSONArray()
        if (meshesJson.length() == 0 || nodesJson.length() == 0) return emptyList()

        val ctx = Ctx(buffers, views, accessors)
        val out = ArrayList<ThreeDSParser.Mesh>()
        var triangleBudget = MAX_TRIANGLES_PER_FILE

        // Racines : la scène par défaut si elle existe, sinon les nœuds qui ne
        // sont l'enfant de personne (certains exports omettent `scenes`).
        val roots = sceneRoots(json, nodesJson)
        val visited = HashSet<Int>()
        val stack = ArrayDeque<Pair<Int, FloatArray>>()
        for (r in roots.asReversed()) stack.addLast(r to IDENTITY)
        var depthGuard = 0
        while (stack.isNotEmpty()) {
            if (++depthGuard > nodesJson.length() * MAX_NODE_DEPTH + 64) break
            val (ni, parent) = stack.removeLast()
            if (ni < 0 || ni >= nodesJson.length() || !visited.add(ni)) continue
            val node = nodesJson.optJSONObject(ni) ?: continue
            val world = mul(parent, localMatrix(node))
            node.optJSONArray("children")?.let { ch ->
                for (i in 0 until ch.length()) stack.addLast(ch.optInt(i, -1) to world)
            }
            if (!node.has("mesh")) continue
            val mesh = meshesJson.optJSONObject(node.optInt("mesh", -1)) ?: continue
            val prims = mesh.optJSONArray("primitives") ?: continue
            val name = node.optString("name", mesh.optString("name", "glb$ni"))
            for (pi in 0 until prims.length()) {
                if (triangleBudget <= 0) return out
                val prim = prims.optJSONObject(pi) ?: continue
                if (prim.optInt("mode", 4) != 4) continue     // TRIANGLES seulement
                val posIdx = prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                val positions = ctx.readVec3(posIdx) ?: continue
                val vc = positions.size / 3
                if (vc < 3 || vc > MAX_VERTS_PER_PRIMITIVE) continue
                val indices = if (prim.has("indices")) ctx.readIndices(prim.optInt("indices", -1))
                    ?: continue else IntArray(vc) { it }
                if (indices.size < 3) continue
                val tris = minOf(indices.size / 3, triangleBudget)
                triangleBudget -= tris
                // Sommets aplatis dans le repère racine : la vue plan compose
                // ensuite avec le placement MVR de l'objet.
                transformInPlace(positions, world)
                out.add(
                    ThreeDSParser.Mesh(
                        name = name,
                        vertices = positions,
                        faceIndices = if (tris * 3 == indices.size) indices else indices.copyOf(tris * 3),
                        materialGroups = emptyList(),
                        materials = emptyMap()
                    )
                )
            }
        }
        return out
    }

    private fun sceneRoots(json: JSONObject, nodes: JSONArray): List<Int> {
        val scenes = json.optJSONArray("scenes")
        val si = json.optInt("scene", 0)
        val scene = scenes?.optJSONObject(if (si in 0 until scenes.length()) si else 0)
        scene?.optJSONArray("nodes")?.let { arr ->
            if (arr.length() > 0) return (0 until arr.length()).map { arr.optInt(it, -1) }
        }
        val isChild = HashSet<Int>()
        for (i in 0 until nodes.length()) {
            nodes.optJSONObject(i)?.optJSONArray("children")?.let { ch ->
                for (k in 0 until ch.length()) isChild.add(ch.optInt(k, -1))
            }
        }
        return (0 until nodes.length()).filter { it !in isChild }
    }

    // ---- Accès aux tampons ----

    private class Ctx(
        val buffers: List<ByteArray?>,
        val views: JSONArray,
        val accessors: JSONArray
    ) {
        /** Bloc (octets, offset, longueur, pas) d'un accesseur, ou null. */
        private fun slice(accessorIndex: Int): Slice? {
            if (accessorIndex < 0 || accessorIndex >= accessors.length()) return null
            val acc = accessors.optJSONObject(accessorIndex) ?: return null
            if (acc.has("sparse")) return null                 // non géré
            val vi = acc.optInt("bufferView", -1)
            if (vi < 0 || vi >= views.length()) return null
            val view = views.optJSONObject(vi) ?: return null
            val data = buffers.getOrNull(view.optInt("buffer", 0)) ?: return null
            val viewOff = view.optInt("byteOffset", 0)
            val viewLen = view.optInt("byteLength", 0)
            if (viewOff < 0 || viewLen < 0 || viewOff + viewLen > data.size) return null
            return Slice(
                data = data,
                offset = viewOff + acc.optInt("byteOffset", 0),
                end = viewOff + viewLen,
                stride = view.optInt("byteStride", 0),
                count = acc.optInt("count", 0),
                componentType = acc.optInt("componentType", 0),
                type = acc.optString("type", "")
            )
        }

        /** Positions FLOAT/VEC3 → x0,y0,z0,x1,… (null si type non géré/hors bornes). */
        fun readVec3(accessorIndex: Int): FloatArray? {
            val s = slice(accessorIndex) ?: return null
            if (s.componentType != 5126 || s.type != "VEC3" || s.count <= 0) return null
            val stride = if (s.stride > 0) s.stride else 12
            if (s.offset < 0 || s.offset + (s.count - 1).toLong() * stride + 12 > s.end) return null
            val out = FloatArray(s.count * 3)
            var p = s.offset
            for (i in 0 until s.count) {
                out[i * 3] = Float.fromBits(le32(s.data, p))
                out[i * 3 + 1] = Float.fromBits(le32(s.data, p + 4))
                out[i * 3 + 2] = Float.fromBits(le32(s.data, p + 8))
                p += stride
            }
            return out
        }

        /** Indices SCALAR (u8 / u16 / u32) → IntArray (null si non géré). */
        fun readIndices(accessorIndex: Int): IntArray? {
            val s = slice(accessorIndex) ?: return null
            if (s.type != "SCALAR" || s.count <= 0) return null
            val width = when (s.componentType) {
                5121 -> 1; 5123 -> 2; 5125 -> 4
                else -> return null
            }
            val stride = if (s.stride > 0) s.stride else width
            if (s.offset < 0 || s.offset + (s.count - 1).toLong() * stride + width > s.end) return null
            val out = IntArray(s.count)
            var p = s.offset
            for (i in 0 until s.count) {
                out[i] = when (width) {
                    1 -> s.data[p].toInt() and 0xFF
                    2 -> (s.data[p].toInt() and 0xFF) or ((s.data[p + 1].toInt() and 0xFF) shl 8)
                    else -> le32(s.data, p)
                }
                p += stride
            }
            return out
        }
    }

    private class Slice(
        val data: ByteArray, val offset: Int, val end: Int, val stride: Int,
        val count: Int, val componentType: Int, val type: String
    )

    private fun decodeDataUri(uri: String): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(0, comma)
        if (!meta.contains(";base64")) return null
        return runCatching {
            java.util.Base64.getDecoder().decode(uri.substring(comma + 1).trim())
        }.getOrNull()
    }

    // ---- Petite algèbre 4×4 (colonne-majeur, comme glTF) ----

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f
    )

    private fun localMatrix(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.let { m ->
            if (m.length() == 16) return FloatArray(16) { m.optDouble(it, 0.0).toFloat() }
        }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        if (t == null && r == null && s == null) return IDENTITY
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f
        val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
        // Rotation issue du quaternion, colonnes mises à l'échelle.
        val m = FloatArray(16)
        m[0] = (1 - 2 * (qy * qy + qz * qz)) * sx
        m[1] = (2 * (qx * qy + qz * qw)) * sx
        m[2] = (2 * (qx * qz - qy * qw)) * sx
        m[4] = (2 * (qx * qy - qz * qw)) * sy
        m[5] = (1 - 2 * (qx * qx + qz * qz)) * sy
        m[6] = (2 * (qy * qz + qx * qw)) * sy
        m[8] = (2 * (qx * qz + qy * qw)) * sz
        m[9] = (2 * (qy * qz - qx * qw)) * sz
        m[10] = (1 - 2 * (qx * qx + qy * qy)) * sz
        m[12] = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
        m[13] = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
        m[14] = t?.optDouble(2, 0.0)?.toFloat() ?: 0f
        m[15] = 1f
        return m
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        if (a === IDENTITY) return b
        if (b === IDENTITY) return a
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var v = 0f
            for (k in 0 until 4) v += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = v
        }
        return r
    }

    private fun transformInPlace(v: FloatArray, m: FloatArray) {
        if (m === IDENTITY) return
        var i = 0
        while (i + 2 < v.size) {
            val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
            v[i] = m[0] * x + m[4] * y + m[8] * z + m[12]
            v[i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
            v[i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
            i += 3
        }
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
}
