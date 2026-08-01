package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.GdtfLoader
import com.minou.mvrviewer.mvr.GlbMeshParser
import com.minou.mvrviewer.mvr.MvrGeometryRef
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import com.minou.mvrviewer.mvr.MvrSceneObject
import com.minou.mvrviewer.mvr.ThreeDSParser
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4 as RMat4
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fil de fer VECTORIEL de la vue plan — portage fidèle de WireframeEdgeBuilder
 * (iOS). Pour chaque TYPE de structure (décor/pont, groupé par ses références de
 * géométrie), on extrait UNE fois les *arêtes caractéristiques* de sa géométrie
 * `.3ds` : arêtes de bord (une seule face) + arêtes vives (deux faces formant un
 * angle > seuil de pli). Les diagonales des quads plats disparaissent → rendu
 * « dessin technique » et non le fouillis de triangulation. Les arêtes sont
 * stockées dans le repère LOCAL de l'objet ; le placement (rotation/translation)
 * de chaque instance est appliqué au dessin. Un pont répété 50× n'est extrait
 * qu'une fois.
 */
/**
 * Identité d'un objet MVR (projecteur OU décor), stable d'une ouverture à
 * l'autre. UNE SEULE définition : le patch, le masquage et tout futur réglage
 * par objet doivent viser exactement le même objet — iOS a déjà eu un bug réel
 * de clés divergentes entre écrans.
 */
internal fun mvrInstanceKey(o: MvrSceneObject): String = o.uuid ?: "${o.name}|${o.layerName}"

object PlanWireframe {

    private const val MAX_TYPES = 400          // comme iOS maxStructureEdges
    private const val MAX_VERTS_PER_MESH = 120_000
    private const val MAX_SYMDEF_ITEMS = 500
    /** Budget du cache de maillages décodés : 6 M de floats ≈ 24 Mo. */
    private const val MAX_CACHED_MESH_FLOATS = 6_000_000L
    private val CREASE_COS = cos(0.26f)        // pli ~15°

    /** Instance de structure à dessiner : type + placement monde. */
    class Inst(
        val key: String,
        /**
         * Identité de CETTE instance (≠ `key`, qui est la clé de TYPE partagée
         * par tous les clones). Indispensable au masquage : masquer par `key`
         * ferait disparaître tous les ponts d'un même modèle, où qu'ils soient
         * — c'était le bug constaté sur Vega côté iOS.
         */
        val id: String,
        val world: RMat4,        // object.transform (repère monde)
        val layer: String,
        val cx: Float,           // translation projetée (top) pour le cull
        val cy: Float
    )

    class Built(
        /** Arêtes LOCALES par type : [ax,ay,az,bx,by,bz, …] (repère objet). */
        val edgesByKey: Map<String, FloatArray>,
        /** Demi-diagonale locale (mm) par type — marge de cull. */
        val radiusByKey: Map<String, Float>,
        val instances: List<Inst>
    ) {
        val isEmpty: Boolean get() = edgesByKey.isEmpty()
    }

    val EMPTY = Built(emptyMap(), emptyMap(), emptyList())

    /** Clé de type d'une structure = toutes ses réf. de géométrie (comme iOS). */
    private fun structureKey(o: MvrSceneObject): String {
        if (o.geometryRefs.isEmpty()) return "struct:none"
        return "struct:" + o.geometryRefs.joinToString(",") { r ->
            when (r) {
                is MvrGeometryRef.File -> "f:${r.fileName}"
                is MvrGeometryRef.Symbol -> "s:${r.symdefUuid}"
            }
        }
    }

    /** Construit le fil de fer (À APPELER HORS THREAD PRINCIPAL). */
    fun build(scene: MvrScene, mvrBytes: ByteArray): Built {
        // 1. Instances de structure (non-fixtures) + comptage par type.
        val structs = scene.allObjects.filter { !it.isFixture && it.geometryRefs.isNotEmpty() }
        if (structs.isEmpty()) return EMPTY
        val countByKey = HashMap<String, Int>()
        val repByKey = HashMap<String, MvrSceneObject>()
        for (o in structs) {
            val k = structureKey(o)
            countByKey[k] = (countByKey[k] ?: 0) + 1
            repByKey.putIfAbsent(k, o)
        }
        // 2. Types les plus fréquents d'abord (budget), comme iOS.
        val keptKeys = countByKey.entries.sortedByDescending { it.value }
            .take(MAX_TYPES).map { it.key }.toHashSet()

        // 3. Extraire tous les fichiers de géométrie référencés par les types retenus.
        val neededFiles = HashSet<String>()
        for (k in keptKeys) collectFileNames(repByKey[k]!!, scene, neededFiles, 0)
        val bytesByName =
            if (neededFiles.isEmpty()) emptyMap() else MvrParser.extractEntries(mvrBytes, neededFiles)
        val meshCache = HashMap<String, List<ThreeDSParser.Mesh>>()

        // 4. Arêtes caractéristiques (repère objet) par type retenu.
        val edgesByKey = HashMap<String, FloatArray>()
        val radiusByKey = HashMap<String, Float>()
        for (k in keptKeys) {
            val buf = FloatBuf()
            val bounds = Bounds()
            walkEdges(repByKey[k]!!.geometryRefs, scene, RMat4(), 0, bytesByName, meshCache, buf, bounds)
            // FILET DE SÉCURITÉ : un type qui A de la géométrie mais dont aucune
            // arête n'a pu être extraite (maillage au-delà du plafond de sommets,
            // primitives non triangulaires, fichier exotique) ne doit PAS se
            // réduire à un point muet — on trace au moins le contour de sa boîte
            // englobante RÉELLE, mesurée sur les sommets décodés.
            if (buf.n == 0) bounds.emitBoxEdges(buf)
            if (buf.n > 0) {
                val arr = buf.toArray()
                edgesByKey[k] = arr
                radiusByKey[k] = halfDiagonal(arr)
            }
            // Le cache de maillages n'existe que pour éviter de re-décoder un
            // fichier partagé par plusieurs types ; il ne doit pas retenir toute
            // la géométrie du show (un .glb est bien plus lourd qu'un .3ds, et
            // l'app a un historique d'OOM). Au-delà du budget, on repart à vide.
            if (meshCache.isNotEmpty() &&
                meshCache.values.sumOf { ms -> ms.sumOf { it.vertices.size.toLong() } } > MAX_CACHED_MESH_FLOATS
            ) meshCache.clear()
        }

        // 5. Instances (toutes les structures ; celles sans arêtes → point).
        //    ON LES CONSTRUIT MÊME SANS AUCUNE ARÊTE : sortir en `EMPTY` ici
        //    privait la vue plan de toute trace des structures.
        val instances = ArrayList<Inst>(structs.size)
        for (o in structs) {
            val k = structureKey(o)
            val t = o.transform.translation
            if (!t[0].isFinite() || !t[1].isFinite()) continue
            instances.add(Inst(k, mvrInstanceKey(o), dr(o.transform), o.layerName, t[0], -t[1]))
        }
        return Built(edgesByKey, radiusByKey, instances)
    }

    // ---- Fil de fer des PROJECTEURS (silhouette GDTF projetée, comme iOS) ----

    private const val MAX_FIXTURE_FLOATS = 120_000   // ~20k segments par spec

    /** Échelle m → mm : l'assemblage GDTF (placements) est en MÈTRES. */
    private val MM = RMat4(
        Float4(1000f, 0f, 0f, 0f), Float4(0f, 1000f, 0f, 0f),
        Float4(0f, 0f, 1000f, 0f), Float4(0f, 0f, 0f, 1f)
    )

    /**
     * Rotation +90° autour de X : glTF est Y-HAUT (le format l'impose), le monde
     * MVR est Z-haut. MÊME correction que la vue 3D (`RX90`) — sans elle un
     * praticable de 2 × 1 m se dessinerait comme un trait de 2 m × 22 mm, et le
     * plan contredirait la 3D.
     */
    private val RX90 = RMat4(
        Float4(1f, 0f, 0f, 0f), Float4(0f, 0f, 1f, 0f),
        Float4(0f, -1f, 0f, 0f), Float4(0f, 0f, 0f, 1f)
    )

    private fun scaleR(s: Float) = RMat4(
        Float4(s, 0f, 0f, 0f), Float4(0f, s, 0f, 0f),
        Float4(0f, 0f, s, 0f), Float4(0f, 0f, 0f, 1f)
    )

    /**
     * Arêtes caractéristiques des projecteurs, PAR SPEC GDTF, dans le repère
     * OBJET (mm) : Base/Yoke/Head assemblés (placements + échelle déclarée),
     * mêmes conventions que la vue 3D. Un spec = une extraction, partagée par
     * toutes ses instances. Les modèles glb (pas de lecteur de sommets) sont
     * ignorés → l'instance garde sa pastille.
     */
    class FixtureWire(val edgesBySpec: Map<String, FloatArray>, val radiusBySpec: Map<String, Float>)

    val EMPTY_FIXTURES = FixtureWire(emptyMap(), emptyMap())

    /** À APPELER HORS THREAD PRINCIPAL. `overrides` = modèles GDTF Share par spec. */
    fun buildFixtures(scene: MvrScene, mvrBytes: ByteArray, overrides: Map<String, ByteArray>): FixtureWire {
        val specs = scene.fixtures.mapNotNull { it.gdtfSpec?.trim()?.ifEmpty { null } }.toHashSet()
        if (specs.isEmpty()) return EMPTY_FIXTURES
        val edges = HashMap<String, FloatArray>()
        val radius = HashMap<String, Float>()
        for (spec in specs) {
            val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
            // Même priorité que la 3D : modèle GDTF Share, sinon l'embarqué —
            // et REPLI sur l'embarqué si le téléchargé n'a pas de 3ds lisible.
            val sources = listOfNotNull(
                overrides[spec],
                cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
            )
            for (gd in sources) {
                val asm = GdtfLoader.parseAssembly(gd) ?: continue
                val buf = FloatBuf()
                val byFile = HashMap<String, Triple<List<ThreeDSParser.Mesh>, Float, Boolean>>()
                for (p in asm.placements) {
                    val info = asm.models[p.modelName] ?: continue
                    val (meshes, rawMax, isGltf) = byFile.getOrPut(info.file) {
                        // prefer3ds : à géométrie égale on garde le .3ds (moins de
                        // sommets, arêtes plus propres) — mais un GDTF qui n'a QUE
                        // des modèles glTF n'est plus perdu pour autant.
                        val fb = GdtfLoader.extractModelBytes(gd, info.file, prefer3ds = true)
                        val parsed = when {
                            fb == null -> emptyList()
                            fb.second == "3ds" -> runCatching { ThreeDSParser.parse(fb.first) }.getOrDefault(emptyList())
                            else -> GlbMeshParser.parse(fb.first)
                        }
                        Triple(parsed, meshesMaxDim(parsed), fb != null && fb.second != "3ds")
                    }
                    if (meshes.isEmpty()) continue
                    val s = when {
                        info.maxDeclaredDimension != null && rawMax > 0f -> info.maxDeclaredDimension!! / rawMax
                        rawMax > 10f -> 0.001f // pas de dimensions déclarées : mm supposés
                        else -> 1f
                    }
                    // mesh-local → objet-local mm : S(1000, m→mm) · placement ·
                    // [Rx(+90°) si le modèle est un glTF, Y-haut] · S(échelle).
                    val mat = MM * p.transform * (if (isGltf) RX90 else RMat4()) * scaleR(s)
                    // Cellule de soudure = 1 mm RÉEL : 1000·s mm par unité brute
                    // → quantum brut = 1/(1000·s). Sans ça, un mesh en mètres
                    // (s≈1) serait soudé par cellules d'un MÈTRE et s'effondrerait.
                    val q = (1f / (1000f * s)).coerceIn(1e-5f, 10f)
                    for (m in meshes) featureEdges(m, mat, buf, q)
                    if (buf.n > MAX_FIXTURE_FLOATS) break
                }
                if (buf.n > 0) {
                    val arr = buf.toArray()
                    edges[spec] = arr
                    radius[spec] = halfDiagonal(arr)
                    break // source suivante inutile
                }
            }
        }
        return FixtureWire(edges, radius)
    }

    /** Plus grande dimension (axes) d'un ensemble de meshes 3ds. */
    private fun meshesMaxDim(meshes: List<ThreeDSParser.Mesh>): Float {
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

    /**
     * Noms de fichiers de géométrie référencés (récursif via symdefs) : `.3ds`
     * ET `.glb`/`.gltf`. Les exports Vectorworks récents sont 100 % glTF — les
     * ignorer ici réduisait tout le décor de ces shows à un point sur le plan.
     */
    private fun collectFileNames(o: MvrSceneObject, scene: MvrScene, out: HashSet<String>, depth: Int) {
        fun walk(refs: List<MvrGeometryRef>, d: Int) {
            if (d > 8) return
            for (g in refs) when (g) {
                is MvrGeometryRef.File ->
                    if (g.fileName.endsWith(".3ds", true) || GlbMeshParser.isGltfName(g.fileName)) out.add(g.fileName)
                is MvrGeometryRef.Symbol -> scene.symdefs[g.symdefUuid]?.let { walk(it.items.take(MAX_SYMDEF_ITEMS), d + 1) }
            }
        }
        walk(o.geometryRefs, depth)
    }

    /** Parcourt les réf. de géométrie, accumule les transforms (SANS object.transform). */
    private fun walkEdges(
        refs: List<MvrGeometryRef>, scene: MvrScene, parent: RMat4, depth: Int,
        bytesByName: Map<String, ByteArray>, meshCache: HashMap<String, List<ThreeDSParser.Mesh>>,
        out: FloatBuf, bounds: Bounds
    ) {
        if (depth > 8) return
        for (g in refs) when (g) {
            is MvrGeometryRef.File -> {
                val isGltf = GlbMeshParser.isGltfName(g.fileName)
                if (!isGltf && !g.fileName.endsWith(".3ds", true)) continue
                val meshes = meshCache.getOrPut(g.fileName) {
                    val b = bytesByName[g.fileName] ?: return@getOrPut emptyList()
                    if (isGltf) GlbMeshParser.parse(b)
                    else runCatching { ThreeDSParser.parse(b) }.getOrDefault(emptyList())
                }
                // Un .glb MVR est en MÈTRES et Y-HAUT : ×1000 puis Rx(+90°),
                // exactement comme la vue 3D (GLB_SCALE puis RX90). Sans le
                // ×1000 un pont de 3 m ferait 3 mm ; sans la rotation il serait
                // couché. La cellule de soudure suit (1 mm réel = 0,001 unité
                // brute) — sinon tout le maillage s'effondre dans une cellule
                // d'un mètre.
                val mat = if (isGltf) parent * dr(g.transform) * MM * RX90 else parent * dr(g.transform)
                val q = if (isGltf) 0.001f else 1f
                for (m in meshes) {
                    bounds.accumulate(m, mat)
                    featureEdges(m, mat, out, q)
                }
            }
            is MvrGeometryRef.Symbol -> {
                val sd = scene.symdefs[g.symdefUuid] ?: continue
                walkEdges(sd.items.take(MAX_SYMDEF_ITEMS), scene, parent * dr(g.transform), depth + 1, bytesByName, meshCache, out, bounds)
            }
        }
    }

    /**
     * Arêtes caractéristiques d'un mesh (soudure 1mm + normales de face + pli),
     * transformées par `mat` (mesh-local → objet-local) et poussées dans `out`.
     */
    private fun featureEdges(mesh: ThreeDSParser.Mesh, mat: RMat4, out: FloatBuf, quantum: Float = 1f) {
        val pos = mesh.vertices
        val vc = mesh.vertexCount
        if (vc < 3 || vc > MAX_VERTS_PER_MESH) return

        // Soudure par position quantifiée : cellule = `quantum` en UNITÉS BRUTES
        // du mesh (1 unité = 1 mm pour les .3ds MVR ; pour un modèle GDTF en
        // MÈTRES, l'appelant passe ~0.001 pour souder au mm réel — sinon toute
        // la géométrie s'effondrerait dans une seule cellule d'un mètre).
        val canonical = IntArray(vc)
        val lookup = HashMap<Long, Int>(vc * 2)
        val wpos = FloatArray(vc * 3)
        var wc = 0
        for (i in 0 until vc) {
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
            val key = packKey(x / quantum, y / quantum, z / quantum)
            val existing = lookup[key]
            if (existing != null) canonical[i] = existing
            else {
                val id = wc
                lookup[key] = id
                wpos[id * 3] = x; wpos[id * 3 + 1] = y; wpos[id * 3 + 2] = z
                canonical[i] = id; wc++
            }
        }

        // Table d'arêtes : première normale + compteur + drapeau « vive ».
        val edges = HashMap<Long, EdgeInfo>()
        val fi = mesh.faceIndices
        val tc = mesh.triangleCount
        var t = 0
        while (t < tc) {
            val i0 = fi[t * 3]; val i1 = fi[t * 3 + 1]; val i2 = fi[t * 3 + 2]
            t++
            if (i0 !in 0 until vc || i1 !in 0 until vc || i2 !in 0 until vc) continue
            val a = canonical[i0]; val b = canonical[i1]; val c = canonical[i2]
            if (a == b || b == c || a == c) continue
            val pax = wpos[a*3]; val pay = wpos[a*3+1]; val paz = wpos[a*3+2]
            val pbx = wpos[b*3]; val pby = wpos[b*3+1]; val pbz = wpos[b*3+2]
            val pcx = wpos[c*3]; val pcy = wpos[c*3+1]; val pcz = wpos[c*3+2]
            // normale = (pb-pa) × (pc-pa), normalisée
            val ux = pbx-pax; val uy = pby-pay; val uz = pbz-paz
            val vx = pcx-pax; val vy = pcy-pay; val vz = pcz-paz
            var nx = uy*vz - uz*vy; var ny = uz*vx - ux*vz; var nz = ux*vy - uy*vx
            val len = sqrt(nx*nx + ny*ny + nz*nz)
            if (len > 1e-6f) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 0f; nz = 1f }
            accumulateEdge(edges, a, b, nx, ny, nz)
            accumulateEdge(edges, b, c, nx, ny, nz)
            accumulateEdge(edges, c, a, nx, ny, nz)
        }

        // Émettre : arêtes de bord (count<2) OU vives, transformées par mat.
        val cx = mat.x; val cy = mat.y; val cz = mat.z; val cw = mat.w
        for ((key, info) in edges) {
            if (info.count >= 2 && !info.crease) continue
            val a = (key ushr 32).toInt(); val b = (key and 0xffffffffL).toInt()
            emit(out, wpos, a, cx, cy, cz, cw)
            emit(out, wpos, b, cx, cy, cz, cw)
        }
    }

    private fun accumulateEdge(edges: HashMap<Long, EdgeInfo>, u: Int, v: Int, nx: Float, ny: Float, nz: Float) {
        val lo = minOf(u, v).toLong(); val hi = maxOf(u, v).toLong()
        val key = (lo shl 32) or hi
        val info = edges[key]
        if (info == null) edges[key] = EdgeInfo(nx, ny, nz, 1, false)
        else {
            val dot = info.nx*nx + info.ny*ny + info.nz*nz
            if (abs(dot) < CREASE_COS) info.crease = true
            info.count++
        }
    }

    private fun emit(out: FloatBuf, wpos: FloatArray, i: Int, cx: Float4, cy: Float4, cz: Float4, cw: Float4) {
        val x = wpos[i*3]; val y = wpos[i*3+1]; val z = wpos[i*3+2]
        out.add(cx.x*x + cy.x*y + cz.x*z + cw.x)
        out.add(cx.y*x + cy.y*y + cz.y*z + cw.y)
        out.add(cx.z*x + cy.z*y + cz.z*z + cw.z)
    }

    /** Clé de soudure : quantifie chaque coord au mm, empaquetée sur 21 bits. */
    private fun packKey(x: Float, y: Float, z: Float): Long {
        val kx = (x.roundToInt() + 0x100000).toLong() and 0x1FFFFF
        val ky = (y.roundToInt() + 0x100000).toLong() and 0x1FFFFF
        val kz = (z.roundToInt() + 0x100000).toLong() and 0x1FFFFF
        return (kx shl 42) or (ky shl 21) or kz
    }

    private fun halfDiagonal(seg: FloatArray): Float {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < seg.size) {
            val x = seg[i]; val y = seg[i+1]; val z = seg[i+2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        val dx = maxX-minX; val dy = maxY-minY; val dz = maxZ-minZ
        return sqrt(dx*dx + dy*dy + dz*dz) * 0.5f
    }

    private class EdgeInfo(val nx: Float, val ny: Float, val nz: Float, var count: Int, var crease: Boolean)

    /**
     * Boîte englobante RÉELLE d'un type, accumulée sur les sommets DÉCODÉS
     * (repère objet). Elle ne sert que de FILET : quand aucune arête n'a pu être
     * extraite, on trace ce contour plutôt que de laisser l'objet se réduire à un
     * point. Elle est mesurée sur la géométrie, PAS sur les translations — une
     * boîte tirée des seules origines de symboles est plate par construction et
     * c'est exactement ce qui faisait disparaître des ponts côté iOS.
     */
    private class Bounds {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var seen = false

        fun accumulate(mesh: ThreeDSParser.Mesh, mat: RMat4) {
            val v = mesh.vertices
            val cx = mat.x; val cy = mat.y; val cz = mat.z; val cw = mat.w
            var i = 0
            while (i + 2 < v.size) {
                val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
                i += 3
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
                val tx = cx.x * x + cy.x * y + cz.x * z + cw.x
                val ty = cx.y * x + cy.y * y + cz.y * z + cw.y
                val tz = cx.z * x + cy.z * y + cz.z * z + cw.z
                seen = true
                if (tx < minX) minX = tx; if (tx > maxX) maxX = tx
                if (ty < minY) minY = ty; if (ty > maxY) maxY = ty
                if (tz < minZ) minZ = tz; if (tz > maxZ) maxZ = tz
            }
        }

        /** Contour de la boîte (12 arêtes), avec un plancher de 200 mm. */
        fun emitBoxEdges(out: FloatBuf) {
            if (!seen) return
            val fx = ((200f - (maxX - minX)) * 0.5f).coerceAtLeast(0f)
            val fy = ((200f - (maxY - minY)) * 0.5f).coerceAtLeast(0f)
            val fz = ((200f - (maxZ - minZ)) * 0.5f).coerceAtLeast(0f)
            val x0 = minX - fx; val x1 = maxX + fx
            val y0 = minY - fy; val y1 = maxY + fy
            val z0 = minZ - fz; val z1 = maxZ + fz
            fun seg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
                out.add(ax); out.add(ay); out.add(az); out.add(bx); out.add(by); out.add(bz)
            }
            for (z in floatArrayOf(z0, z1)) {
                seg(x0, y0, z, x1, y0, z); seg(x1, y0, z, x1, y1, z)
                seg(x1, y1, z, x0, y1, z); seg(x0, y1, z, x0, y0, z)
            }
            seg(x0, y0, z0, x0, y0, z1); seg(x1, y0, z0, x1, y0, z1)
            seg(x1, y1, z0, x1, y1, z1); seg(x0, y1, z0, x0, y1, z1)
        }
    }

    /** Tampon float croissant sans boxing. */
    private class FloatBuf {
        var a = FloatArray(2048); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
}

/** MvrModels.Mat4 (col-majeur) → dev.romainguy Mat4. */
private fun dr(m: com.minou.mvrviewer.mvr.Mat4): RMat4 =
    RMat4(
        Float4(m.m[0], m.m[1], m.m[2], m.m[3]),
        Float4(m.m[4], m.m[5], m.m[6], m.m[7]),
        Float4(m.m[8], m.m[9], m.m[10], m.m[11]),
        Float4(m.m[12], m.m[13], m.m[14], m.m[15])
    )
