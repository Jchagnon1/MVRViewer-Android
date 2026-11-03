package com.minou.mvrviewer.mvr

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * OBJET 3D d'un type de projecteur MA (source (d) « Objet 3D… », SPEC-ma.md §4.4.2-4.4.5).
 *
 * Décodage PUR (testable en JVM) d'un `modelBlob` À PARTIR DE SES SEULS OCTETS — ce que
 * voit le créateur est ce que verra l'équipe : les fichiers frères (textures, `.mtl`,
 * `.bin`) ne voyagent pas. On RÉUTILISE les lecteurs du décor importé ([ObjParser],
 * [StlParser], [PlyParser], [FbxParser], [GlbMeshParser]) puis on REMPLACE l'unité et
 * l'axe qu'ils auraient choisis par ceux du DTO.
 *
 * Pose commune aux trois plateformes, repère du projecteur (mm, Z vers le haut) :
 * ```
 * p_projecteur = [yUp ? Rx(+90°) : I] · S(unitScaleToMm) · p          Rx(+90°) : (x, y, z) → (x, −z, y)
 * ```
 * L'origine du fichier est posée sur le point d'accroche (comme un modèle GDTF), sans
 * recentrage, sans articulation.
 */
object Fixture3DModel {

    /** Plafond par fichier (refus à la création ET à la réception). */
    const val MAX_BYTES: Long = 64L * 1024 * 1024
    /** Au-delà : REFUS (le modèle est répété pour chaque projecteur). */
    const val MAX_TRIANGLES = 500_000
    /** Au-delà : avertissement « Modèle lourd ». */
    const val HEAVY_TRIANGLES = 100_000

    /** Formats lus par l'app ANDROID (table §4.4.4) — les autres : repli + message. */
    val ANDROID_FORMATS = setOf("glb", "gltf", "obj", "stl", "ply", "fbx")
    /** Formats qu'une plateforme au moins sait lire (valeurs permises de `modelBlob.format`). */
    val KNOWN_FORMATS = setOf("glb", "gltf", "obj", "stl", "ply", "fbx", "dae", "usdz", "3ds", "zip")

    fun isReadableHere(format: String): Boolean = format.trim().lowercase() in ANDROID_FORMATS

    /** Nom lisible d'un format (`Format non lu sur cet appareil (COLLADA).`). */
    fun formatLabel(format: String): String = when (format.trim().lowercase()) {
        "dae" -> "COLLADA"
        "usdz" -> "USDZ"
        "3ds" -> "3DS"
        "zip" -> "ZIP"
        "glb" -> "GLB"
        "gltf" -> "glTF"
        "obj" -> "OBJ"
        "stl" -> "STL"
        "ply" -> "PLY"
        "fbx" -> "FBX"
        "" -> "?"
        else -> format.trim().uppercase()
    }

    /**
     * `modelBlob.format` d'un fichier choisi : signatures binaires sûres d'abord, puis
     * l'extension (minuscules). null = ni lu ici ni connu ailleurs.
     */
    fun formatOf(fileName: String, head: ByteArray): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        when (SceneModelLoader.formatFor(head, fileName)) {
            SceneModelLoader.Format.GLTF -> return if (ext == "gltf") "gltf" else "glb"
            SceneModelLoader.Format.FBX -> return "fbx"
            SceneModelLoader.Format.PLY -> return "ply"
            SceneModelLoader.Format.OBJ -> return "obj"
            SceneModelLoader.Format.STL -> return "stl"
            else -> {}
        }
        return ext.takeIf { it in KNOWN_FORMATS }
    }

    /**
     * Valeur ENREGISTRÉE de `unitScaleToMm` : 6 chiffres significatifs, bornée à
     * [0,001 ; 1 000 000] (§3.2). Les valeurs enregistrées s'imposent à tous.
     */
    fun roundUnit(u: Double): Double {
        if (!u.isFinite() || u <= 0) return 1.0
        val r = BigDecimal(u).round(MathContext(6, RoundingMode.HALF_EVEN)).toDouble()
        return r.coerceIn(0.001, 1_000_000.0)
    }

    /** Heuristique d'unité de CE flux : plus grande dimension brute < 50 → mètres (×1000), sinon mm. */
    fun heuristicUnit(rawMaxDim: Float): Double = if (rawMaxDim > 0f && rawMaxDim < 50f) 1000.0 else 1.0

    /** Axe par défaut du format (ce flux suit iOS : Y-up sauf STL et 3DS). */
    fun defaultYUp(format: String): Boolean = format.trim().lowercase().let { it != "stl" && it != "3ds" }

    /** Résultat du décodage par le créateur. */
    class Decoded(
        val format: String,
        /** Maillages natifs (OBJ/STL/PLY/FBX) ; vides pour le glTF (rendu par Filament). */
        val meshes: List<ModelMesh>,
        val triangles: Int,
        /** Boîte englobante en unités NATIVES du fichier (x, y, z). */
        val rawMin: FloatArray,
        val rawMax: FloatArray,
        /** Unité proposée (déclarée sinon heuristique) et si elle est DÉCLARÉE. */
        val unitScaleToMm: Double,
        val unitDeclared: Boolean,
        /** Axe proposé (déclaré sinon défaut du format) ; glTF : imposé (pas de choix). */
        val yUp: Boolean,
        val axisChoice: Boolean
    ) {
        val rawMaxDimension: Float get() = maxOf(rawMax[0] - rawMin[0], rawMax[1] - rawMin[1], rawMax[2] - rawMin[2])
    }

    sealed class DecodeResult {
        class Ok(val model: Decoded) : DecodeResult()
        /** Format non lu par l'app Android (libellé lisible : « COLLADA »). */
        class Unsupported(val label: String) : DecodeResult()
        /**
         * Au-delà du refus. `atLeast` : le lecteur s'est ARRÊTÉ au plafond (fichier non lu
         * jusqu'au bout) — on sait seulement qu'il y a PLUS de `triangles` triangles ; le
         * décompte exact est inconnu et ne doit pas être inventé (« plus de 500000 »).
         */
        class TooHeavy(val triangles: Int, val atLeast: Boolean = false) : DecodeResult()
        object TooLarge : DecodeResult()
        object Unreadable : DecodeResult()
        object NoGeometry : DecodeResult()
    }

    /** Décode un objet 3D depuis ses seuls octets (hors thread principal). */
    fun decode(bytes: ByteArray, format: String): DecodeResult {
        val fmt = format.trim().lowercase()
        if (fmt !in ANDROID_FORMATS) return DecodeResult.Unsupported(formatLabel(fmt))
        if (bytes.size > MAX_BYTES) return DecodeResult.TooLarge
        if (bytes.isEmpty()) return DecodeResult.Unreadable
        // Le décodeur s'arrête juste au-delà du refus : un fichier énorme n'est pas
        // entièrement monté en mémoire pour être refusé ensuite.
        val cap = MAX_TRIANGLES + 1
        return when (fmt) {
            "glb", "gltf" -> {
                val meshes = GlbMeshParser.parse(bytes)
                if (meshes.isEmpty()) return DecodeResult.NoGeometry
                val tris = meshes.sumOf { it.faceIndices.size / 3 }
                if (tris > MAX_TRIANGLES) return DecodeResult.TooHeavy(tris)
                val (lo, hi) = bounds(meshes.map { it.vertices })
                    ?: return DecodeResult.NoGeometry
                // glTF : mètres et Y-haut imposés par le format (choix d'axe non proposé).
                DecodeResult.Ok(Decoded(fmt, emptyList(), tris, lo, hi, 1000.0, true, true, false))
            }
            else -> {
                var declaredUnit: Double? = null
                var declaredYUp: Boolean? = null
                val meshes: List<ModelMesh>
                val truncated: Boolean
                when (fmt) {
                    "obj" -> ObjParser.parse(bytes.inputStream(), cap).let { meshes = it.meshes; truncated = it.truncated }
                    "stl" -> StlParser.parse(bytes, cap).let { meshes = it.meshes; truncated = it.truncated }
                    "ply" -> PlyParser.parse(bytes, cap).let { meshes = it.meshes; truncated = it.truncated }
                    else -> {
                        val r = runCatching { FbxParser.parse(bytes, cap) }.getOrNull() ?: return DecodeResult.Unreadable
                        meshes = r.meshes; truncated = r.truncated
                        declaredUnit = r.unitScaleToMm.toDouble(); declaredYUp = r.yUp
                    }
                }
                val tris = meshes.sumOf { it.triangleCount }
                // Lecteur arrêté au plafond : le vrai décompte est INCONNU (seulement
                // « plus de MAX ») — ne pas afficher le plafond interne comme un décompte.
                if (truncated) return DecodeResult.TooHeavy(MAX_TRIANGLES, atLeast = true)
                if (tris > MAX_TRIANGLES) return DecodeResult.TooHeavy(tris)
                if (meshes.isEmpty() || tris == 0) return DecodeResult.NoGeometry
                val (lo, hi) = bounds(meshes.map { it.verts }) ?: return DecodeResult.NoGeometry
                val rawMaxDim = maxOf(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2])
                val unit = roundUnit(declaredUnit ?: heuristicUnit(rawMaxDim))
                DecodeResult.Ok(
                    Decoded(fmt, meshes, tris, lo, hi, unit, declaredUnit != null,
                        declaredYUp ?: defaultYUp(fmt), true)
                )
            }
        }
    }

    /** Boîte englobante de sommets plats (x,y,z,…) ; null si aucun sommet fini. */
    internal fun bounds(vertexArrays: List<FloatArray>): Pair<FloatArray, FloatArray>? {
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var seen = false
        for (v in vertexArrays) {
            var i = 0
            while (i + 2 < v.size) {
                val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
                if (x.isFinite() && y.isFinite() && z.isFinite()) {
                    seen = true
                    if (x < lo[0]) lo[0] = x; if (x > hi[0]) hi[0] = x
                    if (y < lo[1]) lo[1] = y; if (y > hi[1]) hi[1] = y
                    if (z < lo[2]) lo[2] = z; if (z > hi[2]) hi[2] = z
                }
                i += 3
            }
        }
        return if (seen) lo to hi else null
    }

    /**
     * Pose d'un point NATIF dans le repère du projecteur (mm, Z-haut) : formule commune
     * `[yUp ? Rx(+90°) : I] · S(u) · p`.
     */
    fun pose(x: Double, y: Double, z: Double, unitScaleToMm: Double, yUp: Boolean): DoubleArray {
        val sx = x * unitScaleToMm; val sy = y * unitScaleToMm; val sz = z * unitScaleToMm
        return if (yUp) doubleArrayOf(sx, -sz, sy) else doubleArrayOf(sx, sy, sz)
    }

    /**
     * Boîte englobante POSÉE (mm, repère du projecteur) d'une boîte native : les 8 coins
     * passés par [pose]. Sert au panneau « Taille : … mm » et aux tests de pose.
     */
    fun posedBounds(rawMin: FloatArray, rawMax: FloatArray, unitScaleToMm: Double, yUp: Boolean): Pair<DoubleArray, DoubleArray> {
        val lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (ix in 0..1) for (iy in 0..1) for (iz in 0..1) {
            val p = pose(
                (if (ix == 0) rawMin[0] else rawMax[0]).toDouble(),
                (if (iy == 0) rawMin[1] else rawMax[1]).toDouble(),
                (if (iz == 0) rawMin[2] else rawMax[2]).toDouble(),
                unitScaleToMm, yUp
            )
            for (k in 0..2) { if (p[k] < lo[k]) lo[k] = p[k]; if (p[k] > hi[k]) hi[k] = p[k] }
        }
        // -0.0 → 0.0 (affichage et comparaisons)
        for (k in 0..2) { lo[k] += 0.0; hi[k] += 0.0 }
        return lo to hi
    }
}
