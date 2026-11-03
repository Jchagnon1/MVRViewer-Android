package com.minou.mvrviewer.mvr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * LECTEUR FBX NATIF (Autodesk FBX, binaire ET ASCII) — Kotlin pur, sans NDK.
 *
 * PORT du `FBXParser.swift` d'iOS : mêmes règles de lecture, mêmes plafonds,
 * mêmes conventions d'unité et d'axe. Un même fichier doit donner le même
 * maillage sur les deux plateformes — c'est le contrat de parité du projet.
 *
 * POURQUOI PAS assimp : le niveau 2 « module natif » supposait NDK + CMake +
 * 4 ABI + une surface de crash native que le [com.minou.mvrviewer.CrashReporter]
 * Kotlin n'intercepte pas. Le format se lit très bien en Kotlin, comme le
 * `.3ds` ([ThreeDSParser]) et le `.glb` ([GlbMeshParser]) le font déjà.
 *
 * DIFFÉRENCE STRUCTURELLE AVEC iOS : un [ModelMesh] ne porte AUCUNE
 * transformation (SceneKit avait un graphe de nœuds, pas Filament ici). Les
 * matrices de `Model` — hiérarchie comprise — sont donc CUITES dans les
 * sommets.
 *
 * ROBUSTESSE : le fichier vient d'un tiers, donc toute lecture est bornée
 * (offsets, longueurs, indices, profondeur, nombre de nœuds, coins). Un fichier
 * tronqué ou piégé doit rendre une erreur, jamais une exception non maîtrisée
 * ni une boucle infinie.
 *
 * PÉRIMÈTRE : maillages (positions, normales, matériaux par polygone),
 * hiérarchie, transformations locales, unité et axe haut déclarés. Hors
 * périmètre (inutile pour du décor) : animations, skinning, UV/textures.
 */
object FbxParser {

    /** Nœuds de l'arbre FBX — plafond de sûreté, pas une limite fonctionnelle. */
    private const val MAX_NODES = 2_000_000
    /** Profondeur d'imbrication (le format est récursif → risque de pile). */
    private const val MAX_DEPTH = 96
    /** Coins d'UN polygone jamais refermé. */
    private const val MAX_POLYGON_CORNERS = 4096
    /**
     * Maillages produits. Aligné sur [SceneModelLoader.MODEL_MAX_NODES] : un
     * export CAO peut contenir des centaines de « Body », et un nœud Filament
     * par corps saturerait le budget du décor. Au-delà, les corps suivants sont
     * FUSIONNÉS dans le dernier maillage plutôt que perdus.
     */
    private const val MAX_MESHES = 250

    class Result(
        val meshes: List<ModelMesh>,
        val triangles: Int,
        /** Le plafond de triangles a été atteint : le modèle est INCOMPLET. */
        val truncated: Boolean,
        /** `UnitScaleFactor` du fichier, en millimètres par unité (cm → 10). */
        val unitScaleToMm: Float,
        /** `GlobalSettings.UpAxis != 2`. Le FBX est Y-haut par défaut. */
        val yUp: Boolean
    )

    /** Le FBX binaire commence par « Kaydara FBX Binary  \0\x1a\0 ». */
    fun isBinary(bytes: ByteArray): Boolean {
        val magic = "Kaydara FBX Binary"
        if (bytes.size <= magic.length) return false
        for (i in magic.indices) if (bytes[i].toInt().toChar() != magic[i]) return false
        return true
    }

    // ---------------------------------------------------------------- arbre

    /**
     * Un enregistrement FBX. Les deux syntaxes (binaire et ASCII) convergent
     * vers cette structure, si bien que l'extraction de géométrie n'est écrite
     * qu'UNE fois.
     */
    class Node(val name: String) {
        val props = ArrayList<Value>(4)
        val children = ArrayList<Node>(4)
        fun child(n: String): Node? = children.firstOrNull { it.name == n }
        fun childrenNamed(n: String): List<Node> = children.filter { it.name == n }
    }

    /**
     * Valeur de propriété NORMALISÉE : les 6 types scalaires numériques du
     * format se réduisent à « entier » ou « réel », et les 5 types tableau à
     * « tableau d'entiers » ou « tableau de réels ».
     */
    sealed class Value {
        class I(val v: Long) : Value()
        class D(val v: Double) : Value()
        class S(val v: String) : Value()
        class IA(val v: LongArray) : Value()
        class DA(val v: DoubleArray) : Value()
        object Raw : Value()

        val asDouble: Double?
            get() = when (this) {
                is I -> v.toDouble(); is D -> v; else -> null
            }
        val asLong: Long?
            get() = when (this) {
                is I -> v; is D -> if (v.isFinite()) v.toLong() else null; else -> null
            }
        val asString: String? get() = (this as? S)?.v
    }

    // --------------------------------------------------------------- accès

    /**
     * Tableau de réels porté par un nœud. En binaire il est dans les
     * propriétés ; en ASCII dans le sous-nœud « a » (`Vertices: *24 { a: … }`).
     */
    fun doubles(node: Node?): DoubleArray {
        if (node == null) return DoubleArray(0)
        for (p in node.props) {
            if (p is Value.DA) return p.v
            if (p is Value.IA) return DoubleArray(p.v.size) { p.v[it].toDouble() }
        }
        node.child("a")?.let { return doubles(it) }
        val scalars = node.props.mapNotNull { it.asDouble }
        return if (scalars.size > 1) scalars.toDoubleArray() else DoubleArray(0)
    }

    fun longs(node: Node?): LongArray {
        if (node == null) return LongArray(0)
        for (p in node.props) {
            if (p is Value.IA) return p.v
            if (p is Value.DA) return LongArray(p.v.size) { i ->
                val d = p.v[i]; if (d.isFinite()) d.toLong() else 0L
            }
        }
        node.child("a")?.let { return longs(it) }
        val scalars = node.props.mapNotNull { it.asLong }
        return if (scalars.size > 1) scalars.toLongArray() else LongArray(0)
    }

    fun text(node: Node?): String = node?.props?.firstNotNullOfOrNull { it.asString } ?: ""

    /**
     * Valeurs d'une propriété `Properties70`.
     * FBX 7 : `P: nom, type, type, drapeaux, valeurs…` (4 en-têtes).
     * FBX 6 : `Property: nom, type, drapeaux, valeurs…` (3 en-têtes).
     */
    fun property70(container: Node?, name: String): DoubleArray {
        val block = container?.child("Properties70") ?: container?.child("Properties60")
        ?: return DoubleArray(0)
        for ((tag, headers) in listOf("P" to 4, "Property" to 3)) {
            for (p in block.childrenNamed(tag)) {
                if (p.props.firstOrNull()?.asString != name) continue
                val rest = p.props.drop(headers)
                for (v in rest) {
                    if (v is Value.DA && v.v.isNotEmpty()) return v.v
                    if (v is Value.IA && v.v.isNotEmpty()) return DoubleArray(v.v.size) { v.v[it].toDouble() }
                }
                val values = rest.mapNotNull { it.asDouble }
                if (values.isNotEmpty()) return values.toDoubleArray()
            }
        }
        return DoubleArray(0)
    }

    // ------------------------------------------------------------- binaire

    private class Budget(var nodes: Int = MAX_NODES)

    private fun parseBinary(bytes: ByteArray): Node {
        require(bytes.size > 27) { "entête FBX tronqué" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.getInt(23)
        // À partir de la 7.5, les trois offsets d'en-tête d'enregistrement
        // passent de 32 à 64 bits — c'est LA rupture binaire du format.
        val wide = version >= 7500
        buf.position(27)
        val root = Node("")
        readNodeList(buf, root, wide, 0, bytes.size, Budget())
        return root
    }

    private fun readNodeList(
        buf: ByteBuffer, parent: Node, wide: Boolean, depth: Int, endOffset: Int, budget: Budget
    ) {
        require(depth <= MAX_DEPTH) { "imbrication FBX trop profonde" }
        while (buf.position() < endOffset) {
            val recordStart = buf.position()
            val end: Int
            val numProps: Int
            val propsLen: Int
            if (wide) {
                end = buf.long.toInt(); numProps = buf.long.toInt(); propsLen = buf.long.toInt()
            } else {
                end = buf.int; numProps = buf.int; propsLen = buf.int
            }
            val nameLen = buf.get().toInt() and 0xFF

            // Enregistrement NUL = fin de la liste d'enfants.
            if (end == 0 && numProps == 0 && propsLen == 0 && nameLen == 0) return
            // Un `end` qui ne progresse pas ferait boucler indéfiniment.
            require(end > recordStart && end <= buf.limit() && end <= endOffset) { "offset FBX invalide" }
            require(budget.nodes-- > 0) { "trop de nœuds FBX" }

            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val node = Node(String(nameBytes, Charsets.UTF_8))
            val propsEnd = buf.position() + propsLen
            require(propsLen >= 0 && propsEnd <= end) { "propriétés FBX invalides" }
            var i = 0
            while (i < numProps && buf.position() < propsEnd) {
                node.props.add(readProperty(buf)); i++
            }
            buf.position(propsEnd)

            if (buf.position() < end) readNodeList(buf, node, wide, depth + 1, end, budget)
            buf.position(end)
            parent.children.add(node)
        }
    }

    private fun readProperty(buf: ByteBuffer): Value = when (val t = buf.get().toInt().toChar()) {
        'Y' -> Value.I(buf.short.toLong())
        'C' -> Value.I(if (buf.get().toInt() == 0) 0L else 1L)
        'I' -> Value.I(buf.int.toLong())
        'F' -> Value.D(buf.float.toDouble())
        'D' -> Value.D(buf.double)
        'L' -> Value.I(buf.long)
        'S', 'R' -> {
            val len = buf.int
            require(len >= 0 && len <= buf.remaining()) { "chaîne FBX invalide" }
            val b = ByteArray(len); buf.get(b)
            // Les noms composés FBX séparent par « \0\1 » (« Geometry::Cube »).
            if (t == 'R') Value.Raw
            else Value.S(String(b, Charsets.UTF_8).replace("\u0000\u0001", "::"))
        }
        'f' -> Value.DA(readArray(buf, 4) { bb, n -> DoubleArray(n) { bb.float.toDouble() } })
        'd' -> Value.DA(readArray(buf, 8) { bb, n -> DoubleArray(n) { bb.double } })
        'l' -> Value.IA(readArrayL(buf, 8) { bb, n -> LongArray(n) { bb.long } })
        'i' -> Value.IA(readArrayL(buf, 4) { bb, n -> LongArray(n) { bb.int.toLong() } })
        'b' -> Value.IA(readArrayL(buf, 1) { bb, n -> LongArray(n) { bb.get().toLong() } })
        // Type inconnu : impossible de savoir combien d'octets sauter.
        else -> throw IllegalArgumentException("type de propriété FBX inconnu : $t")
    }

    /**
     * En-tête commun des 5 types tableau, avec décompression éventuelle.
     * `encoding == 1` ⇒ flux **zlib** complet, que [Inflater] lit tel quel.
     */
    private fun <T> readArrayCommon(
        buf: ByteBuffer, stride: Int, decode: (ByteBuffer, Int) -> T
    ): T {
        val count = buf.int
        val encoding = buf.int
        val compressed = buf.int
        require(count >= 0 && compressed >= 0) { "tableau FBX invalide" }
        // Le nombre d'éléments est ANNONCÉ par le fichier : sans plafond, un
        // fichier piégé ferait allouer des gigaoctets avant toute lecture.
        require(count.toLong() * stride <= 512L * 1024 * 1024) { "tableau FBX démesuré" }
        if (encoding == 0) return decode(buf, count)
        require(encoding == 1) { "encodage de tableau FBX inconnu" }
        val src = ByteArray(compressed)
        buf.get(src)
        val out = ByteArray(count * stride)
        val inflater = Inflater()
        try {
            inflater.setInput(src)
            var done = 0
            while (done < out.size) {
                val n = inflater.inflate(out, done, out.size - done)
                if (n == 0) break
                done += n
            }
            require(done == out.size) { "décompression FBX incomplète" }
        } finally {
            inflater.end()
        }
        return decode(ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN), count)
    }

    private fun readArray(buf: ByteBuffer, stride: Int, decode: (ByteBuffer, Int) -> DoubleArray) =
        readArrayCommon(buf, stride, decode)

    private fun readArrayL(buf: ByteBuffer, stride: Int, decode: (ByteBuffer, Int) -> LongArray) =
        readArrayCommon(buf, stride, decode)

    // --------------------------------------------------------------- ASCII

    /**
     * FBX texte : `Nom: prop, prop {` … `}`, commentaires en « ; », tableaux
     * sous la forme `Vertices: *8 { a: 1,2,3 }`.
     */
    private fun parseASCII(bytes: ByteArray): Node {
        val text = String(bytes, Charsets.UTF_8)
        val s = AsciiScanner(text)
        val root = Node("")
        s.parseNodes(root, 0, Budget())
        require(root.children.isNotEmpty()) { "FBX ASCII vide" }
        return root
    }

    private class AsciiScanner(val t: String) {
        var i = 0

        fun skipTrivia() {
            while (i < t.length) {
                val c = t[i]
                when {
                    c == ';' -> while (i < t.length && t[i] != '\n') i++
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' -> i++
                    else -> return
                }
            }
        }

        fun parseNodes(parent: Node, depth: Int, budget: Budget) {
            require(depth <= MAX_DEPTH) { "imbrication FBX trop profonde" }
            while (true) {
                skipTrivia()
                if (i >= t.length) return
                if (t[i] == '}') { i++; return }
                val name = readName()
                if (name == null) {
                    // Jeton inattendu : on avance d'un caractère pour garantir la
                    // progression (jamais de boucle infinie sur un fichier tordu).
                    i++; continue
                }
                require(budget.nodes-- > 0) { "trop de nœuds FBX" }
                val node = Node(name)
                readProperties(node)
                skipTrivia()
                if (i < t.length && t[i] == '{') { i++; parseNodes(node, depth + 1, budget) }
                parent.children.add(node)
            }
        }

        /** Nom suivi de « : ». Un jeton sans « : » n'est pas un nœud. */
        fun readName(): String? {
            val start = i
            val sb = StringBuilder()
            while (i < t.length) {
                val c = t[i]
                if (c == ':') { i++; return if (sb.isEmpty()) null else sb.toString() }
                if (c == '\n' || c == '{' || c == '}') break
                sb.append(c); i++
            }
            i = start
            return null
        }

        /**
         * ⚠️ L'ORDRE des propriétés est significatif — `Geometry: 1001, "…",
         * "Mesh"` s'identifie par sa PREMIÈRE propriété, et `P: nom, type,
         * type, drapeaux, x, y, z` par ses quatre premières. Les valeurs sont
         * donc empilées au fil de la lecture, jamais regroupées par type.
         */
        fun readProperties(node: Node) {
            val numbers = ArrayList<Double>()
            var sawDecimal = false
            var sawString = false
            loop@ while (i < t.length) {
                val c = t[i]
                when {
                    c == '\n' || c == '{' || c == '}' -> break@loop
                    c == ' ' || c == '\t' || c == '\r' || c == ',' -> { i++; continue@loop }
                    c == ';' -> { while (i < t.length && t[i] != '\n') i++; break@loop }
                    c == '"' -> {
                        i++
                        val sb = StringBuilder()
                        while (i < t.length && t[i] != '"') { sb.append(t[i]); i++ }
                        if (i < t.length) i++
                        node.props.add(Value.S(sb.toString().replace("\u0000\u0001", "::")))
                        sawString = true
                        continue@loop
                    }
                    // Taille de tableau ANNONCÉE (`*24`) : ignorée, on se fie au
                    // nombre réel de valeurs lues, qui seul est fiable.
                    c == '*' -> { i++; while (i < t.length && t[i].isDigit()) i++; continue@loop }
                }
                val n = readNumber()
                if (n != null) {
                    if (n.second) sawDecimal = true
                    numbers.add(n.first)
                    node.props.add(
                        if (n.first == Math.floor(n.first) && Math.abs(n.first) < 9e15)
                            Value.I(n.first.toLong()) else Value.D(n.first)
                    )
                    continue@loop
                }
                // Mot-clé nu (`T`, `W`…) : ignoré, mais on avance.
                i++
            }
            // Longue suite de nombres SANS aucune chaîne (`a: 1,2,3…`) : repliée
            // en UN tableau, sinon un maillage ASCII coûterait un objet PAR
            // COORDONNÉE.
            if (!sawString && numbers.size > 8) {
                node.props.clear()
                node.props.add(
                    if (sawDecimal) Value.DA(numbers.toDoubleArray())
                    else Value.IA(LongArray(numbers.size) { numbers[it].toLong() })
                )
            }
        }

        /** Rend (valeur, estDécimal) ou null. */
        fun readNumber(): Pair<Double, Boolean>? {
            val start = i
            val sb = StringBuilder()
            var isDecimal = false
            while (i < t.length) {
                val c = t[i]
                when {
                    c.isDigit() || c == '-' || c == '+' -> { sb.append(c); i++ }
                    c == '.' || c == 'e' || c == 'E' -> { isDecimal = true; sb.append(c); i++ }
                    else -> break
                }
            }
            val v = sb.toString().toDoubleOrNull()
            if (v == null || !v.isFinite()) { i = start; return null }
            return v to isDecimal
        }
    }

    // ------------------------------------------------------- construction

    /**
     * Point d'entrée. [maxTriangles] borne le maillage produit ; s'il est
     * atteint, `truncated` est vrai et l'appelant REFUSE l'import (jamais un
     * modèle silencieusement amputé — règle du projet).
     */
    fun parse(bytes: ByteArray, maxTriangles: Int): Result {
        val root = if (isBinary(bytes)) parseBinary(bytes) else parseASCII(bytes)
        val objects = root.child("Objects") ?: return Result(emptyList(), 0, false, 1f, true)

        // Unité et axe haut DÉCLARÉS. Le FBX compte en centimètres multipliés
        // par `UnitScaleFactor` (1 = cm, 2.54 = pouce, 100 = mètre).
        val settings = root.child("GlobalSettings")
        val factor = property70(settings, "UnitScaleFactor").firstOrNull()
        val unitScaleToMm =
            if (factor != null && factor.isFinite() && factor > 0) (factor * 10.0).toFloat() else 10f
        val yUp = (property70(settings, "UpAxis").firstOrNull()?.toInt() ?: 1) != 2

        val geometries = HashMap<Long, Node>()
        val models = HashMap<Long, Node>()
        val materialColors = HashMap<Long, Int>()
        for (o in objects.children) {
            val id = o.props.firstOrNull()?.asLong ?: continue
            when (o.name) {
                "Geometry" -> geometries[id] = o
                "Model" -> models[id] = o
                "Material" -> materialColors[id] = materialColor(o)
            }
        }
        if (geometries.isEmpty()) return Result(emptyList(), 0, false, unitScaleToMm, yUp)

        val geometryOfModel = HashMap<Long, Node>()
        val materialsOfModel = HashMap<Long, MutableList<Int>>()
        val parentOfModel = HashMap<Long, Long>()
        root.child("Connections")?.let { block ->
            for (c in block.childrenNamed("C") + block.childrenNamed("Connect")) {
                val ids = c.props.mapNotNull { it.asLong }
                if (ids.size < 2) continue
                val child = ids[0]; val parent = ids[1]
                val g = geometries[child]
                when {
                    g != null && models.containsKey(parent) -> geometryOfModel[parent] = g
                    materialColors.containsKey(child) && models.containsKey(parent) ->
                        materialsOfModel.getOrPut(parent) { ArrayList() }.add(materialColors[child]!!)
                    models.containsKey(child) && models.containsKey(parent) -> parentOfModel[child] = parent
                }
            }
        }

        val out = ArrayList<ModelMesh>()
        var triangles = 0
        var truncated = false
        val handled = HashSet<Node>()
        val worldCache = HashMap<Long, FloatArray>()

        for ((id, model) in models) {
            val geometry = geometryOfModel[id] ?: continue
            handled.add(geometry)
            val world = worldTransform(id, models, parentOfModel, worldCache, HashSet())
            val built = buildMeshes(
                geometry, world, materialsOfModel[id] ?: emptyList(),
                maxTriangles - triangles
            )
            out.addAll(built.meshes)
            triangles += built.triangles
            if (built.truncated) { truncated = true; break }
            if (out.size > MAX_MESHES) break
        }

        // REPLI — géométries qu'aucun Model ne réclame (FBX 6 sans
        // identifiants, `Connections` absent). Sans lui, un fichier
        // parfaitement lisible rendrait une scène vide.
        if (!truncated) {
            for (g in geometries.values) {
                if (g in handled) continue
                val built = buildMeshes(g, identity(), emptyList(), maxTriangles - triangles)
                out.addAll(built.meshes)
                triangles += built.triangles
                if (built.truncated) { truncated = true; break }
                if (out.size > MAX_MESHES) break
            }
        }
        return Result(out, triangles, truncated, unitScaleToMm, yUp)
    }

    private fun materialColor(node: Node): Int {
        val rgb = property70(node, "DiffuseColor")
        if (rgb.size < 3) return 0xFFB8B8B8.toInt()
        fun ch(d: Double) = (d.coerceIn(0.0, 1.0) * 255.0).toInt() and 0xFF
        return (0xFF shl 24) or (ch(rgb[0]) shl 16) or (ch(rgb[1]) shl 8) or ch(rgb[2])
    }

    // ---------------------------------------------------------- matrices

    /** Matrices 4×4 en colonnes (mêmes conventions que `simd_float4x4` iOS). */
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0..3) for (row in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    private fun translation(v: FloatArray) = identity().also {
        it[12] = v[0]; it[13] = v[1]; it[14] = v[2]
    }

    private fun scaleMat(v: FloatArray) = identity().also {
        // Une échelle nulle rendrait le maillage invisible sans le dire.
        it[0] = if (v[0] == 0f) 1f else v[0]
        it[5] = if (v[1] == 0f) 1f else v[1]
        it[10] = if (v[2] == 0f) 1f else v[2]
    }

    /**
     * Angles d'Euler FBX, en DEGRÉS, ordre par défaut `eEulerXYZ` : on tourne
     * d'abord autour de X, puis Y, puis Z — soit le produit `Rz · Ry · Rx`.
     */
    private fun euler(d: FloatArray): FloatArray {
        val k = (Math.PI / 180.0).toFloat()
        fun rot(axis: Int, a: Float): FloatArray {
            val c = Math.cos((a * k).toDouble()).toFloat()
            val s = Math.sin((a * k).toDouble()).toFloat()
            val m = identity()
            when (axis) {
                0 -> { m[5] = c; m[9] = -s; m[6] = s; m[10] = c }
                1 -> { m[0] = c; m[8] = s; m[2] = -s; m[10] = c }
                else -> { m[0] = c; m[4] = -s; m[1] = s; m[5] = c }
            }
            return m
        }
        return mul(mul(rot(2, d[2]), rot(1, d[1])), rot(0, d[0]))
    }

    private fun vec(v: DoubleArray, def: Float = 0f): FloatArray {
        if (v.size < 3) return floatArrayOf(def, def, def)
        return FloatArray(3) { if (v[it].isFinite()) v[it].toFloat() else def }
    }

    /** `T · Rpre · R · S`, la composition qui couvre les exports réels. */
    private fun localTransform(model: Node): FloatArray = mul(
        mul(
            mul(translation(vec(property70(model, "Lcl Translation"))),
                euler(vec(property70(model, "PreRotation")))),
            euler(vec(property70(model, "Lcl Rotation")))
        ),
        scaleMat(vec(property70(model, "Lcl Scaling"), 1f))
    )

    /** La transformation « géométrique » ne s'applique QU'AU maillage. */
    private fun geometricTransform(model: Node): FloatArray = mul(
        mul(translation(vec(property70(model, "GeometricTranslation"))),
            euler(vec(property70(model, "GeometricRotation")))),
        scaleMat(vec(property70(model, "GeometricScaling"), 1f))
    )

    /**
     * Matrice MONDE d'un Model : chaîne des parents, mémoïsée. `seen` coupe les
     * cycles — un fichier tordu peut se déclarer son propre parent.
     */
    private fun worldTransform(
        id: Long, models: Map<Long, Node>, parents: Map<Long, Long>,
        cache: HashMap<Long, FloatArray>, seen: MutableSet<Long>
    ): FloatArray {
        cache[id]?.let { return it }
        val model = models[id] ?: return identity()
        if (!seen.add(id)) return identity()
        val local = localTransform(model)
        val parentId = parents[id]
        val world = if (parentId != null && models.containsKey(parentId))
            mul(worldTransform(parentId, models, parents, cache, seen), local) else local
        val full = mul(world, geometricTransform(model))
        cache[id] = full
        return full
    }

    // ---------------------------------------------------------- maillage

    private class Built(val meshes: List<ModelMesh>, val triangles: Int, val truncated: Boolean)

    /** Tampon FloatArray croissant (pas de liste boxée sur des millions de valeurs). */
    private class FloatBuf {
        var a = FloatArray(1024); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun toArray(): FloatArray = a.copyOf(n)
    }
    private class IntBuf {
        var a = IntArray(1024); var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun toArray(): IntArray = a.copyOf(n)
    }

    /**
     * Construit les maillages d'un nœud `Geometry`, transformation CUITE.
     *
     * Les sommets sont DÉDOUBLÉS par coin de polygone : c'est ce que le format
     * impose dès que les normales sont `ByPolygonVertex` (cas ordinaire), et
     * cela évite toute une classe de bugs d'indexation.
     */
    private fun buildMeshes(
        node: Node, world: FloatArray, materials: List<Int>, remainingTriangles: Int
    ): Built {
        val raw = doubles(node.child("Vertices"))
        val polygonIndices = longs(node.child("PolygonVertexIndex"))
        if (raw.size < 9 || polygonIndices.size < 3) return Built(emptyList(), 0, false)
        if (remainingTriangles <= 0) return Built(emptyList(), 0, true)
        val vertexCount = raw.size / 3

        val normalLayer = node.child("LayerElementNormal")
        val normalValues = doubles(normalLayer?.child("Normals"))
        val normalIndices = longs(normalLayer?.child("NormalsIndex"))
        val normalMapping = text(normalLayer?.child("MappingInformationType"))
        val normalReference = text(normalLayer?.child("ReferenceInformationType"))
        val hasNormals = normalValues.size >= 3

        val materialLayer = node.child("LayerElementMaterial")
        val materialIndices = longs(materialLayer?.child("Materials"))
        val materialByPolygon = text(materialLayer?.child("MappingInformationType")) == "ByPolygon"

        // Un groupe par index de matériau : Filament veut une couleur par maillage.
        val verts = HashMap<Int, FloatBuf>()
        val norms = HashMap<Int, FloatBuf>()
        val idx = HashMap<Int, IntBuf>()
        var triangles = 0
        var truncated = false

        fun xf(x: Double, y: Double, z: Double): FloatArray {
            val fx = x.toFloat(); val fy = y.toFloat(); val fz = z.toFloat()
            return floatArrayOf(
                world[0]*fx + world[4]*fy + world[8]*fz + world[12],
                world[1]*fx + world[5]*fy + world[9]*fz + world[13],
                world[2]*fx + world[6]*fy + world[10]*fz + world[14]
            )
        }
        // Les normales ignorent la translation. (Une échelle NON UNIFORME les
        // biaiserait ; les exports de décor n'en ont pas en pratique, et la
        // renormalisation rattrape l'échelle uniforme.)
        fun xfDir(x: Double, y: Double, z: Double): FloatArray {
            val fx = x.toFloat(); val fy = y.toFloat(); val fz = z.toFloat()
            val v = floatArrayOf(
                world[0]*fx + world[4]*fy + world[8]*fz,
                world[1]*fx + world[5]*fy + world[9]*fz,
                world[2]*fx + world[6]*fy + world[10]*fz
            )
            val l = Math.sqrt((v[0]*v[0] + v[1]*v[1] + v[2]*v[2]).toDouble()).toFloat()
            return if (l > 1e-9f) floatArrayOf(v[0]/l, v[1]/l, v[2]/l) else floatArrayOf(0f, 0f, 1f)
        }

        fun normalAt(corner: Int, vertexIndex: Int): FloatArray? {
            if (!hasNormals) return null
            var slot = when (normalMapping) {
                "ByVertex", "ByVertice", "ByVertexIndex" -> vertexIndex
                "AllSame" -> 0
                else -> corner            // ByPolygonVertex (défaut de fait)
            }
            if (normalReference == "IndexToDirect") {
                if (slot < 0 || slot >= normalIndices.size) return null
                slot = normalIndices[slot].toInt()
            }
            val b = slot * 3
            if (b < 0 || b + 2 >= normalValues.size) return null
            return xfDir(normalValues[b], normalValues[b + 1], normalValues[b + 2])
        }

        val polygon = ArrayList<IntArray>()   // (vertexIndex, corner)
        var polygonIndex = 0

        fun flushPolygon() {
            if (polygon.size >= 3 && !truncated) {
                val slot = when {
                    materialByPolygon && polygonIndex < materialIndices.size ->
                        materialIndices[polygonIndex].toInt()
                    !materialByPolygon && materialIndices.isNotEmpty() -> materialIndices[0].toInt()
                    else -> 0
                }
                val vb = verts.getOrPut(slot) { FloatBuf() }
                val nb = norms.getOrPut(slot) { FloatBuf() }
                val ib = idx.getOrPut(slot) { IntBuf() }
                for (k in 1 until polygon.size - 1) {
                    if (triangles >= remainingTriangles) { truncated = true; break }
                    val trio = arrayOf(polygon[0], polygon[k], polygon[k + 1])
                    val pos = arrayOfNulls<FloatArray>(3)
                    var ok = true
                    for ((o, c) in trio.withIndex()) {
                        val vi = c[0]
                        if (vi < 0 || vi >= vertexCount) { ok = false; break }
                        pos[o] = xf(raw[vi * 3], raw[vi * 3 + 1], raw[vi * 3 + 2])
                    }
                    if (!ok) break
                    // Normale de face en secours : sans elle, un maillage sans
                    // normales serait rendu tout noir.
                    val face = faceNormal(pos[0]!!, pos[1]!!, pos[2]!!)
                    for ((o, c) in trio.withIndex()) {
                        val p = pos[o]!!
                        vb.add(p[0]); vb.add(p[1]); vb.add(p[2])
                        val nrm = normalAt(c[1], c[0]) ?: face
                        nb.add(nrm[0]); nb.add(nrm[1]); nb.add(nrm[2])
                        ib.add(vb.n / 3 - 1)
                    }
                    triangles++
                }
            }
            polygon.clear()
        }

        for (corner in polygonIndices.indices) {
            val value = polygonIndices[corner]
            // Un indice NÉGATIF marque le DERNIER coin du polygone ; l'indice
            // réel est son complément à un (~v).
            val isLast = value < 0
            val vertexIndex = (if (isLast) value.inv() else value).toInt()
            polygon.add(intArrayOf(vertexIndex, corner))
            if (isLast) { flushPolygon(); polygonIndex++ }
            else if (polygon.size > MAX_POLYGON_CORNERS) {
                // Polygone jamais refermé : on abandonne CE polygone plutôt que
                // de laisser grossir la liste sans fin.
                polygon.clear(); polygonIndex++
            }
            if (truncated) break
        }
        flushPolygon()

        val meshes = ArrayList<ModelMesh>()
        for (slot in verts.keys.sorted()) {
            val vb = verts[slot] ?: continue
            val ib = idx[slot] ?: continue
            if (vb.n < 9 || ib.n < 3) continue
            val color = if (slot >= 0 && slot < materials.size) materials[slot] else 0xFFB8B8B8.toInt()
            meshes.add(ModelMesh(vb.toArray(), norms[slot]!!.toArray(), ib.toArray(), color))
        }
        return Built(meshes, triangles, truncated)
    }

    private fun faceNormal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val u = floatArrayOf(b[0]-a[0], b[1]-a[1], b[2]-a[2])
        val w = floatArrayOf(c[0]-a[0], c[1]-a[1], c[2]-a[2])
        val n = floatArrayOf(
            u[1]*w[2] - u[2]*w[1], u[2]*w[0] - u[0]*w[2], u[0]*w[1] - u[1]*w[0]
        )
        val l = Math.sqrt((n[0]*n[0] + n[1]*n[1] + n[2]*n[2]).toDouble()).toFloat()
        return if (l > 1e-9f) floatArrayOf(n[0]/l, n[1]/l, n[2]/l) else floatArrayOf(0f, 0f, 1f)
    }
}
