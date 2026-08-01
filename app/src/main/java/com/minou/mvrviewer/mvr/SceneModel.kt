package com.minou.mvrviewer.mvr

/**
 * MODÈLES 3D IMPORTÉS DANS LA SCÈNE (chantier #3) — décor, praticables,
 * structures, éléments de scéno posés PAR-DESSUS le show MVR.
 *
 * Un modèle importé est du DÉCOR et rien d'autre : il n'entre ni dans le patch,
 * ni dans le câblage, ni dans les univers DMX. C'est une conséquence de la
 * construction, pas d'un filtre : la sélection/le tap de la vue 3D travaillent
 * sur `layout.positions`, indexé sur `scene.fixtures`, où un nœud de décor
 * importé n'entre jamais.
 */

/**
 * Placement réglable d'un modèle importé, PAR RAPPORT au repère MVR (mm, Z-haut).
 * CALQUÉ sur [ReferencePlanTransform] (mêmes noms de champs, même `effScale`,
 * même `copy()`) pour que le panneau de placement du plan de repère se reproduise
 * à l'identique.
 *
 * ⚠️ Objet MUTABLE et NON OBSERVABLE, exactement comme [ReferencePlanTransform] :
 * le muter ne déclenche AUCUNE recomposition. Tout réglage doit être suivi d'un
 * incrément du compteur `importedModelsVersion` (le pendant de
 * `refPlanTransformVersion`), sinon rien ne change à l'écran.
 */
class SceneModelTransform(
    /** Décalage au sol, en mm monde MVR. */
    var offsetX: Double = 0.0,
    var offsetY: Double = 0.0,
    /** Hauteur, en mm monde MVR (Z). */
    var offsetZ: Double = 0.0,
    /** Rotation autour de la verticale (Z), en degrés. */
    var rotationDeg: Double = 0.0,
    /** Échelle de placement (rattrape l'unité / le cadrage initial). */
    var scale: Double = 1.0,
    /**
     * HOMOTHÉTIE réglée par l'utilisateur (curseur + valeur éditable) — facteur
     * PROPORTIONNEL, jamais de déformation. Distincte de [scale] pour pouvoir la
     * « Réinitialiser » sans perdre l'échelle d'origine. Défaut 1.0 → `effScale
     * == scale` au bit près.
     */
    var homothety: Double = 1.0,
    var visible: Boolean = true
) {
    /** Échelle RÉELLEMENT appliquée : la SEULE valeur que doit lire le rendu. */
    val effScale: Double get() = scale * homothety

    fun copy() = SceneModelTransform(offsetX, offsetY, offsetZ, rotationDeg, scale, homothety, visible)

    /** Remet les valeurs par défaut (bouton « Réinitialiser » du panneau). */
    fun reset() {
        offsetX = 0.0; offsetY = 0.0; offsetZ = 0.0
        rotationDeg = 0.0; scale = 1.0; homothety = 1.0; visible = true
    }
}

/**
 * Un sous-maillage prêt au moteur : buffers PLATS (pas de `List<Vertex>`, qui
 * ferait un objet par sommet — inacceptable à 400 000 triangles). La conversion
 * en sommets Filament est faite au dernier moment, maillage par maillage, pour
 * que le pic mémoire reste celui d'UN maillage.
 *
 * INVARIANT : tous les indices sont DANS [0, verts.size/3[ — c'est le rôle du
 * garde `okTri` des parseurs. Un indice hors bornes fait lire le GPU hors du
 * VertexBuffer → corruption ou abort natif (précédent documenté du `.3ds`).
 */
class ModelMesh(
    /** x0,y0,z0, x1,y1,z1, … */
    val verts: FloatArray,
    /** Normales lissées, même taille que [verts]. */
    val normals: FloatArray,
    /** Indices groupés par 3 (un triangle par groupe). */
    val indices: IntArray,
    /** Couleur ARGB du sous-maillage (matériau résolu, sinon gris). */
    val color: Int
) {
    val vertexCount: Int get() = verts.size / 3
    val triangleCount: Int get() = indices.size / 3
}

/**
 * Un modèle importé + son placement.
 *
 * `meshes` est renseigné pour les formats parsés en Kotlin pur (OBJ / STL / PLY) ;
 * pour le glTF/GLB c'est FILAMENT qui décode, donc [sourceBytes] est conservé et
 * `meshes` reste vide.
 */
class ImportedModel(
    /** Identifiant stable (sert de nom de fichier et de clé de sélection). */
    val id: String,
    val name: String,
    /** [SceneModelLoader.Format] sous forme de chaîne (persistée telle quelle). */
    val format: String,
    val meshes: List<ModelMesh>,
    val transform: SceneModelTransform,
    val triangles: Int,
    /**
     * Facteur unité du fichier → MILLIMÈTRES (le monde MVR est en mm). OBJ / STL /
     * PLY ne déclarent aucune unité : cf. l'heuristique documentée dans
     * [SceneModelLoader.unitScaleFor]. L'utilisateur corrige à l'Homothétie.
     */
    val unitScaleToMm: Float,
    /**
     * Vrai si la source est Y-HAUT (glTF, OBJ exporté Blender) : il faut alors une
     * rotation de +90° autour de X pour passer en Z-haut MVR. STL/PLY viennent en
     * général de la CAO, déjà en Z-haut.
     */
    val yUp: Boolean,
    /** Plus grande dimension du modèle en mm (pas de déplacement adapté au panneau). */
    val sizeMm: Float,
    // PAS de champ « tronqué » : un modèle qui dépasse les plafonds est REFUSÉ à
    // l'import (message chiffré, comportement iOS), il n'entre jamais amputé dans
    // la liste. Il n'existe donc aucun modèle « partiellement importé » à signaler.
    /**
     * Octets du fichier source. Conservés jusqu'à l'écriture sur disque, puis
     * relâchés — SAUF pour le glTF, dont Filament a besoin à chaque
     * reconstruction. `var` : c'est [ProjectStore] qui les libère.
     */
    var sourceBytes: ByteArray? = null,
    /** Nom du fichier persisté dans le dossier du projet (null = pas encore écrit). */
    var file: String? = null
)

// ---- Tampons croissants primitifs (pas de boxing sur des millions de valeurs) ----

internal class FloatBuf(initial: Int = 1024) {
    var a = FloatArray(initial.coerceAtLeast(4)); private set
    var n = 0; private set
    fun add(v: Float) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    fun get(i: Int): Float = a[i]
    fun toArray(): FloatArray = a.copyOf(n)
}

internal class IntBuf(initial: Int = 1024) {
    var a = IntArray(initial.coerceAtLeast(4)); private set
    var n = 0; private set
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    fun get(i: Int): Int = a[i]
    fun toArray(): IntArray = a.copyOf(n)
}

/**
 * Construit un [ModelMesh] à partir de positions PARTAGÉES et d'une liste
 * d'indices, en NE GARDANT que les sommets réellement référencés (remappage) et
 * en calculant les normales lissées par sommet.
 *
 * Le garde d'indices est ici, en un seul endroit, pour TOUS les parseurs : c'est
 * la reproduction exacte de `okTri` du chemin `.3ds` (Scene3DScreen).
 */
internal fun buildMesh(positions: FloatArray, indices: IntBuf, color: Int): ModelMesh? {
    val vc = positions.size / 3
    if (vc == 0 || indices.n < 3) return null
    val remap = HashMap<Int, Int>(indices.n)
    val outPos = FloatBuf(indices.n * 3)
    val outIdx = IntBuf(indices.n)
    var t = 0
    while (t + 2 < indices.n) {
        val a = indices.get(t); val b = indices.get(t + 1); val c = indices.get(t + 2)
        t += 3
        // okTri : les 3 indices DOIVENT pointer dans le tableau de sommets.
        if (a !in 0 until vc || b !in 0 until vc || c !in 0 until vc) continue
        if (a == b || b == c || a == c) continue   // triangle dégénéré
        for (v in intArrayOf(a, b, c)) {
            val nv = remap.getOrPut(v) {
                val id = outPos.n / 3
                outPos.add(positions[v * 3]); outPos.add(positions[v * 3 + 1]); outPos.add(positions[v * 3 + 2])
                id
            }
            outIdx.add(nv)
        }
    }
    if (outIdx.n < 3) return null
    val verts = outPos.toArray()
    val idx = outIdx.toArray()
    return ModelMesh(verts, smoothNormals(verts, idx), idx, color)
}

/**
 * Normales lissées par sommet (somme des normales de face, puis normalisation) —
 * même calcul que `prepareMeshData` pour le `.3ds`, pour que le décor importé
 * s'éclaire exactement comme celui du show.
 */
internal fun smoothNormals(verts: FloatArray, indices: IntArray): FloatArray {
    val vc = verts.size / 3
    val n = FloatArray(verts.size)
    var i = 0
    while (i + 2 < indices.size) {
        val a = indices[i]; val b = indices[i + 1]; val c = indices[i + 2]
        i += 3
        if (a !in 0 until vc || b !in 0 until vc || c !in 0 until vc) continue
        val ax = verts[a * 3]; val ay = verts[a * 3 + 1]; val az = verts[a * 3 + 2]
        val ux = verts[b * 3] - ax; val uy = verts[b * 3 + 1] - ay; val uz = verts[b * 3 + 2] - az
        val wx = verts[c * 3] - ax; val wy = verts[c * 3 + 1] - ay; val wz = verts[c * 3 + 2] - az
        val fx = uy * wz - uz * wy; val fy = uz * wx - ux * wz; val fz = ux * wy - uy * wx
        n[a * 3] += fx; n[a * 3 + 1] += fy; n[a * 3 + 2] += fz
        n[b * 3] += fx; n[b * 3 + 1] += fy; n[b * 3 + 2] += fz
        n[c * 3] += fx; n[c * 3 + 1] += fy; n[c * 3 + 2] += fz
    }
    for (v in 0 until vc) {
        val x = n[v * 3]; val y = n[v * 3 + 1]; val z = n[v * 3 + 2]
        val len = kotlin.math.sqrt(x * x + y * y + z * z)
        if (len > 1e-9f) { n[v * 3] = x / len; n[v * 3 + 1] = y / len; n[v * 3 + 2] = z / len }
        else { n[v * 3] = 0f; n[v * 3 + 1] = 0f; n[v * 3 + 2] = 1f }
    }
    return n
}

/** Plus grande dimension (axes) de l'ensemble des maillages, en unités du fichier. */
internal fun meshesMaxDimension(meshes: List<ModelMesh>): Float {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    var seen = false
    for (m in meshes) {
        val v = m.verts
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
