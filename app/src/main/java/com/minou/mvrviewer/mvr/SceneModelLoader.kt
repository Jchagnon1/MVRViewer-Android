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
 * des bornes DURES. Un dépassement REFUSE l'import avec un message CHIFFRÉ (même
 * comportement qu'iOS) ; il ne tronque JAMAIS en silence, parce qu'un modèle
 * amputé de la moitié de ses faces ressemble à un import réussi.
 */
object SceneModelLoader {

    /**
     * Taille maximale d'un fichier accepté (au-delà : refus explicite).
     * ALIGNÉ SUR iOS (`ImportedModelDecoder.maxFileBytes`). Vérifié d'abord sur la
     * taille DÉCLARÉE par le fournisseur de documents, donc sans lire le fichier :
     * on ne charge pas 256 Mo pour les refuser ensuite.
     */
    const val MODEL_MAX_BYTES = 256L * 1024 * 1024
    /** Plafond de triangles d'UN modèle — aligné sur iOS (`maxTriangles`). */
    const val MODEL_MAX_TRIANGLES = 1_500_000
    /**
     * Plafond de triangles de TOUS les modèles importés, cumulés. Gardé (iOS n'a
     * que le plafond par modèle) parce que le décor partage la mémoire GPU avec le
     * show : deux modèles au plafond tiennent, un troisième est refusé.
     */
    const val MODEL_TOTAL_TRIANGLES = 2 * MODEL_MAX_TRIANGLES
    /** Plafond de nœuds Filament du décor importé (budget SÉPARÉ de celui du show). */
    const val MODEL_MAX_NODES = 300
    /** Nombre maximal de modèles importés dans un projet. */
    const val MODEL_MAX_COUNT = 20

    /**
     * ESTIMATION de triangles d'un glTF/GLB, en OCTETS PAR TRIANGLE.
     *
     * Un GLB n'est décodé que par Filament, au rendu : on ne connaît pas son
     * nombre de triangles à l'import sans le décoder. On l'ESTIME donc à partir de
     * la taille du fichier, avec un facteur volontairement CONSERVATEUR (il
     * SUR-estime, jamais l'inverse) :
     *
     *  - géométrie indexée typique : position (12 o) + normale (12 o) + UV (8 o)
     *    par sommet, soit 32 o, pour ≈ 0,55 sommet par triangle → ≈ 18 o ;
     *  - plus 3 indices, 4 o chacun → ≈ 12 o.
     *
     * Total ≈ 30 o/triangle pour de la géométrie PURE ; on retient 32. Tout ce que
     * le fichier contient EN PLUS (textures embarquées, animations) gonfle donc
     * l'estimation, ce qui va dans le sens de la prudence — et un GLB compressé
     * (Draco) est de toute façon refusé par Filament sans l'extension.
     */
    const val GLTF_BYTES_PER_TRIANGLE = 32

    /**
     * Message de refus — LITTÉRAL. Jamais de « fichier illisible » générique :
     * l'utilisateur doit savoir quoi faire. NE CITER QUE des formats qu'ANDROID
     * SAIT LIRE : pas d'USDZ ici (c'est le message iOS), sinon on conseille de
     * convertir un .usdz… en .usdz.
     */
    const val UNSUPPORTED_MSG =
        "Format non pris en charge. Convertissez-le en OBJ, STL, PLY ou glTF (par exemple avec Blender)."
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

    // ---- Messages CHIFFRÉS de refus (repris mot pour mot de l'iOS) ----

    /** « 4,2 M » — virgule décimale française, comme `ModelImportError.millions`. */
    internal fun millions(count: Long): String =
        String.format(java.util.Locale.FRANCE, "%.1f M", count / 1_000_000.0)

    internal fun megabytes(bytes: Long): String =
        String.format(java.util.Locale.FRANCE, "%d Mo", bytes / (1024 * 1024))

    /** Fichier au-delà du plafond de TAILLE (`bytes` null = taille non déclarée). */
    internal fun tooLargeMsg(bytes: Long?): String {
        val mesure = if (bytes != null) megabytes(bytes) else "plus de ${megabytes(MODEL_MAX_BYTES)}"
        return "Fichier trop volumineux ($mesure) : le plafond est de " +
            "${megabytes(MODEL_MAX_BYTES)}. Allégez le modèle (décimation, textures séparées) " +
            "avant l'import."
    }

    /**
     * Modèle au-delà du plafond de triangles PAR MODÈLE.
     * @param countLabel le décompte affiché, « Plus de 1,5 M triangles ».
     */
    internal fun tooManyTrianglesMsg(countLabel: String): String =
        "$countLabel : au-delà du plafond de ${millions(MODEL_MAX_TRIANGLES.toLong())} triangles " +
            "par modèle. Allégez le maillage (décimation) avant l'import."

    /** Modèle qui tiendrait seul, mais pas dans le budget CUMULÉ restant. */
    internal fun budgetExceededMsg(countLabel: String, remaining: Int): String =
        "$countLabel : il ne reste que ${millions(remaining.toLong())} sur le plafond cumulé de " +
            "${millions(MODEL_TOTAL_TRIANGLES.toLong())} triangles pour les modèles importés. " +
            "Retirez un modèle avant d'en ajouter un autre, ou allégez le maillage."

    /** Budget cumulé ENTIÈREMENT consommé : plus rien ne peut entrer. */
    internal fun budgetFullMsg(): String =
        "Plafond cumulé de ${millions(MODEL_TOTAL_TRIANGLES.toLong())} triangles atteint pour les " +
            "modèles importés. Retirez un modèle avant d'en ajouter un autre."

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
     * Lecture d'un fichier dont la taille est DÉCLARÉE : une seule allocation, à la
     * bonne taille. [readBounded] passe par un ByteArrayOutputStream, donc par un
     * pic mémoire DOUBLE — inacceptable maintenant que le plafond est à 256 Mo.
     *
     * La déclaration n'est pas une garantie : si le flux contient PLUS que ce qui
     * était annoncé, on repasse sur la lecture croissante bornée plutôt que de
     * tronquer le fichier (ce qui donnerait un maillage silencieusement amputé —
     * exactement ce que ce lot supprime).
     */
    private fun readSized(ins: InputStream, declared: Int, max: Long): ReadOut {
        val b = ByteArray(declared)
        var n = 0
        while (n < declared) {
            val r = ins.read(b, n, declared - n)
            if (r <= 0) break
            n += r
        }
        val extra = ins.read()
        if (extra < 0) return ReadOut(if (n == declared) b else b.copyOf(n), false)
        val out = java.io.ByteArrayOutputStream(declared + (1 shl 16))
        out.write(b, 0, n); out.write(extra)
        val buf = ByteArray(1 shl 16)
        var total = n.toLong() + 1
        while (true) {
            val r = ins.read(buf)
            if (r <= 0) break
            total += r
            if (total > max) return ReadOut(null, true)
            out.write(buf, 0, r)
        }
        return ReadOut(out.toByteArray(), false)
    }

    /** Taille DÉCLARÉE du document, ou null si le fournisseur ne la donne pas. */
    private fun declaredSize(cr: ContentResolver, uri: Uri): Long? = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()?.takeIf { it > 0 }

    /**
     * Charge un modèle depuis un `content://`. À appeler HORS THREAD PRINCIPAL.
     *
     * @param remainingTriangles budget de triangles encore disponible (plafond
     *   CUMULÉ de tous les modèles). Un budget insuffisant REFUSE l'import avec un
     *   message chiffré — il ne rend jamais un modèle amputé.
     */
    fun load(cr: ContentResolver, uri: Uri, name: String?, remainingTriangles: Int): LoadResult {
        val fmt = sniff(cr, uri, name)
        when (fmt) {
            Format.FBX -> return LoadResult.Unsupported(UNSUPPORTED_FBX_MSG)
            Format.SKP, Format.UNKNOWN -> return LoadResult.Unsupported(UNSUPPORTED_MSG)
            else -> {}
        }
        // Refus AVANT toute lecture quand la taille est déclarée (patron iOS).
        val declared = declaredSize(cr, uri)
        if (declared != null && declared > MODEL_MAX_BYTES) return LoadResult.Failed(tooLargeMsg(declared))
        val read = runCatching {
            cr.openInputStream(uri)?.use {
                if (declared != null) readSized(it, declared.toInt(), MODEL_MAX_BYTES)
                else readBounded(it, MODEL_MAX_BYTES)
            }
        }.getOrNull() ?: return LoadResult.Failed("Fichier illisible.")
        if (read.tooBig) return LoadResult.Failed(tooLargeMsg(null))
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
        if (bytes.size > MODEL_MAX_BYTES) return LoadResult.Failed(tooLargeMsg(bytes.size.toLong()))
        val remaining = remainingTriangles.coerceAtLeast(0)

        if (fmt == Format.GLTF) {
            // Décodé par Filament au moment du rendu : ici on ne fait que porter
            // les octets. Un glTF est en MÈTRES et Y-HAUT (×1000 + Rx(+90°)).
            //
            // Un GLB participe AU MÊME TITRE que les autres au budget de triangles
            // — sinon on empilerait sans limite des modèles lourds. Faute de
            // décodage à l'import, on lui impute son ESTIMATION conservatrice
            // (cf. [GLTF_BYTES_PER_TRIANGLE]).
            val estimated = estimatedGltfTriangles(bytes.size)
            val label = "Environ ${millions(estimated.toLong())} triangles " +
                "(estimés d'après la taille du fichier)"
            if (estimated > MODEL_MAX_TRIANGLES) return LoadResult.Failed(tooManyTrianglesMsg(label))
            if (estimated > remaining) return LoadResult.Failed(
                if (remaining <= 0) budgetFullMsg() else budgetExceededMsg(label, remaining)
            )
            return LoadResult.Ok(
                ImportedModel(
                    id = newId(), name = name, format = Format.GLTF.name,
                    meshes = emptyList(), transform = SceneModelTransform(),
                    triangles = estimated, unitScaleToMm = 1000f, yUp = true,
                    sizeMm = 0f, sourceBytes = bytes
                )
            )
        }

        // Plafond EFFECTIF de ce modèle : le plus contraignant des deux (le sien et
        // ce qu'il reste du cumul). Les parseurs s'arrêtent là ; s'ils signalent une
        // troncature, c'est que le fichier dépasse → on REFUSE (comportement iOS),
        // on ne rend pas un modèle amputé qui passerait pour un import réussi.
        val cap = minOf(MODEL_MAX_TRIANGLES, remaining)
        if (cap <= 0) return LoadResult.Failed(budgetFullMsg())

        val meshes: List<ModelMesh>
        val truncated: Boolean
        when (fmt) {
            Format.OBJ -> {
                val r = ObjParser.parse(bytes.inputStream(), cap)
                meshes = r.meshes; truncated = r.truncated
            }
            Format.STL -> {
                val r = StlParser.parse(bytes, cap)
                meshes = r.meshes; truncated = r.truncated
            }
            Format.PLY -> {
                val r = PlyParser.parse(bytes, cap)
                meshes = r.meshes; truncated = r.truncated
            }
            else -> return LoadResult.Unsupported(UNSUPPORTED_MSG)
        }
        if (truncated) {
            // Le maillage construit est jeté ici même : rien n'est rattaché à la
            // scène, rien n'est écrit sur disque.
            val label = "Plus de ${millions(cap.toLong())}"
            return LoadResult.Failed(
                if (cap < MODEL_MAX_TRIANGLES) budgetExceededMsg(label, remaining)
                else tooManyTrianglesMsg(label)
            )
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
                sourceBytes = bytes
            )
        )
    }

    /** Estimation conservatrice du nombre de triangles d'un glTF/GLB. */
    internal fun estimatedGltfTriangles(sizeBytes: Int): Int =
        (sizeBytes.toLong() / GLTF_BYTES_PER_TRIANGLE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

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
