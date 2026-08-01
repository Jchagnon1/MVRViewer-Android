package com.minou.mvrviewer.mvr

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * AIGUILLAGE D'IMPORT d'un modèle 3D de décor — calqué sur [RasterPlanLoader].
 *
 * NIVEAU 1 (celui-ci) : aucune dépendance nouvelle. OBJ / STL / PLY parsés en
 * Kotlin pur ([ObjParser], [StlParser], [PlyParser]) ; glTF / GLB confiés au
 * décodeur Filament déjà présent (SceneView). Tout le reste — FBX, SKP… — est
 * refusé PROPREMENT, avec le message de conversion.
 *
 * NIVEAU 2 (FBX via assimp) : NON FAIT, et volontairement. Le projet n'a
 * aujourd'hui ni NDK, ni CMake, ni `externalNativeBuild` ; l'ajouter demande un
 * lot dédié (socle natif + JNI + 4 ABI + une surface de crash native que le
 * CrashReporter Kotlin n'intercepte pas). [LoadResult] est le POINT D'INSERTION
 * prévu : le jour où un module natif existe, `Format.FBX` cesse de tomber dans
 * `Unsupported` et rien d'autre ne bouge — même patron que `#if canImport` côté
 * iOS.
 *
 * FORMAT — on ne se fie PAS à l'extension seule (un `content://` peut n'avoir
 * aucun nom) ni au MIME (les formats 3D n'en ont pas de fiable) : la décision
 * part des OCTETS DE TÊTE, l'extension ne servant que d'appoint.
 *
 * MÉMOIRE — le projet a un historique d'OOM sur gros fichiers : tout passe par
 * des bornes DURES, et un dépassement TRONQUE en prévenant, il ne plante jamais.
 */
object SceneModelLoader {

    /** Taille maximale d'un fichier accepté (au-delà : refus explicite). */
    const val MODEL_MAX_BYTES = 64L * 1024 * 1024
    /** Plafond de triangles d'UN modèle. */
    const val MODEL_MAX_TRIANGLES = 400_000
    /** Plafond de triangles de TOUS les modèles importés, cumulés. */
    const val MODEL_TOTAL_TRIANGLES = 1_000_000
    /** Plafond de nœuds Filament du décor importé (budget SÉPARÉ de celui du show). */
    const val MODEL_MAX_NODES = 300
    /** Nombre maximal de modèles importés dans un projet. */
    const val MODEL_MAX_COUNT = 20

    /**
     * Message de refus — LITTÉRAL et commun aux deux plateformes. Jamais de
     * « fichier illisible » générique : l'utilisateur doit savoir quoi faire.
     */
    const val UNSUPPORTED_MSG =
        "Format non pris en charge. Convertissez-le en OBJ, USDZ ou glTF (par exemple avec Blender)."
    /** Complément propre au FBX : le niveau 2 (module natif) n'est pas livré. */
    const val UNSUPPORTED_FBX_MSG = "$UNSUPPORTED_MSG (FBX : ajoutez le module Assimp)"

    enum class Format { OBJ, STL, PLY, GLTF, FBX, SKP, UNKNOWN }

    sealed class LoadResult {
        class Ok(val model: ImportedModel) : LoadResult()
        /** Format connu mais non géré (FBX/SKP/…) — message de conversion. */
        class Unsupported(val message: String) : LoadResult()
        /** Format géré mais fichier illisible / trop gros / vide. */
        class Failed(val message: String) : LoadResult()
    }

    /** Nom d'affichage du document choisi (« praticable.obj »), ou null. */
    fun displayName(cr: ContentResolver, uri: Uri): String? = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Type du fichier : octets de tête d'abord, extension en appoint. */
    fun sniff(cr: ContentResolver, uri: Uri, name: String?): Format {
        val head = runCatching {
            cr.openInputStream(uri)?.use { ins ->
                val b = ByteArray(32)
                var n = 0
                while (n < b.size) {
                    val r = ins.read(b, n, b.size - n)
                    if (r <= 0) break
                    n += r
                }
                b.copyOf(n)
            }
        }.getOrNull() ?: ByteArray(0)
        return formatFor(head, name)
    }

    /**
     * Décision de format, PURE (donc testable en JVM). Ordre : signatures
     * binaires sûres, puis extension.
     */
    internal fun formatFor(head: ByteArray, name: String?): Format {
        fun starts(s: String): Boolean {
            if (head.size < s.length) return false
            for (i in s.indices) if (head[i].toInt().toChar() != s[i]) return false
            return true
        }
        // glTF binaire : magic « glTF ».
        if (starts("glTF")) return Format.GLTF
        // FBX binaire : « Kaydara FBX Binary  » en tête.
        if (starts("Kaydara FBX Binary")) return Format.FBX
        // PLY : « ply » suivi d'une fin de ligne.
        if (starts("ply") && head.size > 3 && (head[3].toInt() == 0x0A || head[3].toInt() == 0x0D)) return Format.PLY

        val ext = name?.substringAfterLast('.', "")?.lowercase()
        when (ext) {
            "obj" -> return Format.OBJ
            "stl" -> return Format.STL
            "ply" -> return Format.PLY
            "glb", "gltf" -> return Format.GLTF
            "fbx" -> return Format.FBX
            "skp" -> return Format.SKP
        }
        // STL ASCII sans extension : « solid » en tête (indice FAIBLE — un STL
        // binaire peut aussi commencer par « solid », mais [StlParser] tranche par
        // la taille, pas par ce mot).
        if (starts("solid ")) return Format.STL
        // Un glTF au format JSON commence par « { » ; sans extension on ne peut
        // pas l'affirmer, on ne devine donc pas.
        return Format.UNKNOWN
    }

    /**
     * HEURISTIQUE D'UNITÉ — OBJ / STL / PLY ne déclarent AUCUNE unité, et le monde
     * MVR est en millimètres. Règle documentée : si la plus grande dimension du
     * modèle est inférieure à 100, on suppose des MÈTRES (×1000) ; au-delà, des
     * MILLIMÈTRES (×1). Même esprit que la règle GDTF `rawMax > 10f -> 0.001f`.
     * L'utilisateur corrige ensuite à l'Homothétie — c'est écrit dans la spec.
     */
    internal fun unitScaleFor(rawMax: Float): Float =
        if (rawMax > 0f && rawMax < 100f) 1000f else 1f

    // ---- Chargement ----

    private class ReadOut(val bytes: ByteArray?, val tooBig: Boolean)

    private fun readBounded(ins: InputStream, max: Long): ReadOut {
        val out = java.io.ByteArrayOutputStream(1 shl 16)
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val r = ins.read(buf)
            if (r <= 0) break
            total += r
            if (total > max) return ReadOut(null, true)
            out.write(buf, 0, r)
        }
        return ReadOut(out.toByteArray(), false)
    }

    /**
     * Charge un modèle depuis un `content://`. À appeler HORS THREAD PRINCIPAL.
     *
     * @param remainingTriangles budget de triangles encore disponible (plafond
     *   CUMULÉ de tous les modèles). Un budget épuisé n'échoue pas : le modèle
     *   arrive simplement tronqué, avec l'avertissement.
     */
    fun load(cr: ContentResolver, uri: Uri, name: String?, remainingTriangles: Int): LoadResult {
        val fmt = sniff(cr, uri, name)
        when (fmt) {
            Format.FBX -> return LoadResult.Unsupported(UNSUPPORTED_FBX_MSG)
            Format.SKP, Format.UNKNOWN -> return LoadResult.Unsupported(UNSUPPORTED_MSG)
            else -> {}
        }
        val read = runCatching { cr.openInputStream(uri)?.use { readBounded(it, MODEL_MAX_BYTES) } }.getOrNull()
            ?: return LoadResult.Failed("Fichier illisible.")
        if (read.tooBig) return LoadResult.Failed(
            "Fichier trop volumineux (plus de ${MODEL_MAX_BYTES / (1024 * 1024)} Mo). " +
                "Allégez le modèle avant de l'importer."
        )
        val bytes = read.bytes ?: return LoadResult.Failed("Fichier illisible.")
        return parseBytes(bytes, fmt, name ?: "Modèle", remainingTriangles)
    }

    /**
     * Parse des octets DÉJÀ en mémoire — même chemin à l'import et au
     * RECHARGEMENT depuis le projet (patron `loadRaster` : « le fichier a beau
     * venir de nous, il a pu être écrit par une version antérieure aux bornes
     * actuelles » → les plafonds sont re-vérifiés ici, pas seulement à l'import).
     * PURE (aucun appel Android) → testable en JVM.
     */
    fun parseBytes(bytes: ByteArray, fmt: Format, name: String, remainingTriangles: Int): LoadResult {
        if (bytes.isEmpty()) return LoadResult.Failed("Fichier vide.")
        if (bytes.size > MODEL_MAX_BYTES) return LoadResult.Failed("Fichier trop volumineux.")
        val budget = remainingTriangles.coerceIn(0, MODEL_MAX_TRIANGLES)
        // Budget CUMULÉ épuisé : le dire clairement plutôt que de laisser croire à
        // un fichier illisible (le parseur rendrait un modèle vide).
        if (budget <= 0 && fmt != Format.GLTF) return LoadResult.Failed(
            "Plafond de $MODEL_TOTAL_TRIANGLES triangles atteint pour les modèles importés. " +
                "Retirez un modèle avant d'en ajouter un autre."
        )

        if (fmt == Format.GLTF) {
            // Décodé par Filament au moment du rendu : ici on ne fait que porter
            // les octets. Un glTF est en MÈTRES et Y-HAUT (×1000 + Rx(+90°)).
            return LoadResult.Ok(
                ImportedModel(
                    id = newId(), name = name, format = Format.GLTF.name,
                    meshes = emptyList(), transform = SceneModelTransform(),
                    triangles = 0, unitScaleToMm = 1000f, yUp = true,
                    sizeMm = 0f, truncated = false, sourceBytes = bytes
                )
            )
        }

        val meshes: List<ModelMesh>
        val truncated: Boolean
        when (fmt) {
            Format.OBJ -> {
                val r = ObjParser.parse(bytes.inputStream(), budget)
                meshes = r.meshes; truncated = r.truncated
            }
            Format.STL -> {
                val r = StlParser.parse(bytes, budget)
                meshes = r.meshes; truncated = r.truncated
            }
            Format.PLY -> {
                val r = PlyParser.parse(bytes, budget)
                meshes = r.meshes; truncated = r.truncated
            }
            else -> return LoadResult.Unsupported(UNSUPPORTED_MSG)
        }
        if (meshes.isEmpty()) return LoadResult.Failed(
            "Aucune géométrie lisible dans ce fichier."
        )
        val rawMax = meshesMaxDimension(meshes)
        val unit = unitScaleFor(rawMax)
        // OBJ : Y-HAUT (convention Wavefront, et export Blender par défaut).
        // STL / PLY : issus de la CAO, déjà Z-haut comme le monde MVR.
        val yUp = fmt == Format.OBJ
        return LoadResult.Ok(
            ImportedModel(
                id = newId(), name = name, format = fmt.name,
                meshes = meshes, transform = SceneModelTransform(),
                triangles = meshes.sumOf { it.triangleCount },
                unitScaleToMm = unit, yUp = yUp, sizeMm = rawMax * unit,
                truncated = truncated, sourceBytes = bytes
            )
        )
    }

    /** Extension de fichier à utiliser pour la copie persistée. */
    fun extensionFor(format: String): String = when (format) {
        Format.GLTF.name -> "glb"
        Format.OBJ.name -> "obj"
        Format.STL.name -> "stl"
        Format.PLY.name -> "ply"
        else -> "bin"
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString().take(12)
}
