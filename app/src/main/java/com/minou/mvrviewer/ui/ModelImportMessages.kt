package com.minou.mvrviewer.ui

import android.content.Context
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.SceneModelLoader
import java.util.Locale

/**
 * MISE EN MOTS des refus d'import de modèle 3D.
 *
 * [SceneModelLoader] reste PUR (aucun `Context`, testable en JVM) : il ne rend
 * qu'un [SceneModelLoader.Reason] CHIFFRÉ. C'est ici, à l'AFFICHAGE, que le
 * motif devient une phrase — donc dans la langue du téléphone, exactement comme
 * le reste de l'interface.
 *
 * Les nombres passent par la locale des RESSOURCES (et non `Locale.getDefault()`)
 * pour rester cohérents avec le texte : « 1,5 M » en français, « 1.5 M » en
 * anglais.
 */
object ModelImportMessages {

    private fun locale(ctx: Context): Locale =
        ctx.resources.configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }

    /** « 1,5 M » / « 1.5 M » — même forme que `ModelImportError.millions` (iOS). */
    private fun millions(ctx: Context, count: Long): String =
        String.format(locale(ctx), "%.1f M", count / 1_000_000.0)

    private fun megabytes(ctx: Context, bytes: Long): String =
        ctx.getString(R.string.model_err_megabytes_fmt, bytes / (1024 * 1024))

    /** « OBJ, STL, PLY ou glTF » — la liste vient du décodeur, le « ou » de la langue. */
    private fun formatList(ctx: Context): String {
        val f = SceneModelLoader.CONVERTIBLE_FORMATS
        if (f.size < 2) return f.joinToString(", ")
        return f.dropLast(1).joinToString(", ") + ctx.getString(R.string.model_err_format_or) + f.last()
    }

    /** Décompte affichable : « Environ 4,2 M triangles (estimés…) » / « Plus de 1,5 M ». */
    private fun countLabel(ctx: Context, c: SceneModelLoader.TriangleCount): String = when (c) {
        is SceneModelLoader.TriangleCount.Estimated ->
            ctx.getString(R.string.model_err_count_estimated_fmt, millions(ctx, c.triangles.toLong()))
        is SceneModelLoader.TriangleCount.AtLeast ->
            ctx.getString(R.string.model_err_count_at_least_fmt, millions(ctx, c.triangles.toLong()))
    }

    fun text(ctx: Context, reason: SceneModelLoader.Reason): String = when (reason) {
        is SceneModelLoader.Reason.Unsupported ->
            ctx.getString(R.string.model_err_unsupported_fmt, formatList(ctx))
        is SceneModelLoader.Reason.TooLarge -> {
            val mesure = if (reason.bytes != null) megabytes(ctx, reason.bytes)
            else ctx.getString(
                R.string.model_err_size_more_than_fmt, megabytes(ctx, reason.maxBytes)
            )
            ctx.getString(R.string.model_err_too_large_fmt, mesure, megabytes(ctx, reason.maxBytes))
        }
        is SceneModelLoader.Reason.Unreadable -> ctx.getString(R.string.model_err_unreadable)
        is SceneModelLoader.Reason.Empty -> ctx.getString(R.string.model_err_empty)
        is SceneModelLoader.Reason.NoGeometry -> ctx.getString(R.string.model_err_no_geometry)
        is SceneModelLoader.Reason.TooManyTriangles -> ctx.getString(
            R.string.model_err_too_many_triangles_fmt,
            countLabel(ctx, reason.count), millions(ctx, reason.maxTriangles.toLong())
        )
        is SceneModelLoader.Reason.BudgetExceeded -> ctx.getString(
            R.string.model_err_budget_exceeded_fmt,
            countLabel(ctx, reason.count),
            millions(ctx, reason.remaining.toLong()),
            millions(ctx, reason.totalTriangles.toLong())
        )
        is SceneModelLoader.Reason.BudgetFull -> ctx.getString(
            R.string.model_err_budget_full_fmt, millions(ctx, reason.totalTriangles.toLong())
        )
    }
}
