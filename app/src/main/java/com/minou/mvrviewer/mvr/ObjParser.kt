package com.minou.mvrviewer.mvr

import java.io.BufferedReader
import java.io.InputStream

/**
 * Parseur OBJ (Wavefront) — Kotlin PUR, aucune dépendance, sur le modèle exact de
 * [ThreeDSParser] : lecture bornée, indices vérifiés, jamais d'exception jetée à
 * l'appelant sur un fichier douteux.
 *
 * Couvert : `v` (sommets), `vn` / `vt` (lus et IGNORÉS au rendu v1 — les normales
 * sont recalculées lissées, comme pour le `.3ds`), `f` (polygones n-gones
 * triangulés en ÉVENTAIL, indices négatifs = relatifs à la fin), `usemtl` (un
 * sous-maillage par matériau) et `mtllib` (ignoré : un `content://` ne permet pas
 * d'atteindre le fichier frère — les couleurs viennent d'un `mtlColors` fourni,
 * sinon gris).
 *
 * Lecture en STREAMING ligne à ligne : un OBJ de plusieurs dizaines de Mo n'est
 * jamais matérialisé en une seule chaîne.
 */
object ObjParser {

    /** Gris par défaut — même valeur que le décor `.3ds` sans matériau. */
    const val GRAY = 0xFFBEBEC3.toInt()

    class Result(val meshes: List<ModelMesh>, val triangles: Int, val truncated: Boolean)

    /**
     * @param maxTriangles plafond DUR de triangles conservés ; au-delà on TRONQUE
     *   (et `truncated` passe à vrai) plutôt que de saturer la mémoire.
     * @param mtlColors couleurs diffuses par nom de matériau (ARGB), si connues.
     */
    fun parse(input: InputStream, maxTriangles: Int, mtlColors: Map<String, Int> = emptyMap()): Result {
        val pos = FloatBuf(4096)
        // Un IntBuf d'indices par matériau, dans l'ordre d'apparition.
        val groups = LinkedHashMap<String, IntBuf>()
        var current = ""
        var tris = 0
        var truncated = false

        fun idxFor(token: String): Int? {
            // « 12 », « 12/3 », « 12/3/4 », « 12//4 » → on ne garde que l'indice de sommet.
            val s = token.substringBefore('/')
            val v = s.toIntOrNull() ?: return null
            // Indices OBJ : 1-based ; NÉGATIFS = relatifs à la fin de la liste.
            return if (v > 0) v - 1 else if (v < 0) (pos.n / 3) + v else null
        }

        BufferedReader(input.reader(Charsets.UTF_8), 1 shl 16).use { r ->
            val face = IntArray(64)
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                val c0 = line[0]
                if (c0 == '#') continue
                // Découpage manuel : split(Regex) sur des millions de lignes coûte
                // bien plus cher que ce balayage.
                val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                when (parts[0]) {
                    "v" -> if (parts.size >= 4) {
                        val x = parts[1].toFloatOrNull(); val y = parts[2].toFloatOrNull(); val z = parts[3].toFloatOrNull()
                        if (x != null && y != null && z != null && x.isFinite() && y.isFinite() && z.isFinite()) {
                            pos.add(x); pos.add(y); pos.add(z)
                        } else { pos.add(0f); pos.add(0f); pos.add(0f) }  // garde l'alignement des indices
                    }
                    "f" -> {
                        if (tris >= maxTriangles) { truncated = true; continue }
                        var m = 0
                        var k = 1
                        while (k < parts.size && m < face.size) {
                            val i = idxFor(parts[k]) ?: break
                            face[m++] = i
                            k++
                        }
                        if (m < 3) continue
                        val g = groups.getOrPut(current) { IntBuf(1024) }
                        // Triangulation en ÉVENTAIL (convexe : le cas normal d'un OBJ).
                        var t = 1
                        while (t < m - 1) {
                            if (tris >= maxTriangles) { truncated = true; break }
                            g.add(face[0]); g.add(face[t]); g.add(face[t + 1])
                            tris++
                            t++
                        }
                    }
                    "usemtl" -> current = parts.getOrNull(1).orEmpty()
                    // "vn", "vt", "mtllib", "o", "g", "s" : lus et ignorés (v1).
                }
            }
        }

        val positions = pos.toArray()
        val meshes = ArrayList<ModelMesh>(groups.size)
        for ((name, idx) in groups) {
            val color = mtlColors[name] ?: GRAY
            buildMesh(positions, idx, color)?.let { meshes.add(it) }
        }
        return Result(meshes, meshes.sumOf { it.triangleCount }, truncated)
    }

    /**
     * Couleurs diffuses (`newmtl` + `Kd`) d'un fichier `.mtl`. Non utilisé par
     * l'import SAF (le fichier frère est hors d'atteinte d'un `content://`), mais
     * conservé : le jour où un import d'archive arrive, tout est là.
     */
    fun parseMtl(input: InputStream): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        var name: String? = null
        BufferedReader(input.reader(Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                val p = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
                if (p.isEmpty()) continue
                when (p[0]) {
                    "newmtl" -> name = p.getOrNull(1)
                    "Kd" -> if (p.size >= 4 && name != null) {
                        val r0 = p[1].toFloatOrNull(); val g0 = p[2].toFloatOrNull(); val b0 = p[3].toFloatOrNull()
                        if (r0 != null && g0 != null && b0 != null) {
                            fun c(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
                            out[name!!] = (0xFF shl 24) or (c(r0) shl 16) or (c(g0) shl 8) or c(b0)
                        }
                    }
                }
            }
        }
        return out
    }
}
