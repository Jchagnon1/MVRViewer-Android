package com.minou.mvrviewer.mvr

import android.graphics.Bitmap

/**
 * Plan de repère MATRICIEL : une image (JPEG / PNG) ou la PAGE 1 d'un PDF déjà
 * rendue en bitmap. Pendant vectoriel du DXF — même placement, même position
 * dans l'empilement de dessin (sous le décor et les projecteurs), mêmes
 * persistances par projet.
 *
 * L'image porte sa TAILLE PHYSIQUE en mm dans le repère LOCAL du plan (comme les
 * bornes d'un DXF) : elle est centrée sur l'origine locale, donc ses coins vont
 * de (−widthMm/2, −heightMm/2) à (+widthMm/2, +heightMm/2). Le placement
 * (décalage / rotation / échelle / homothétie) s'applique ensuite exactement
 * comme pour un DXF, via `ReferencePlanTransform.worldXY`.
 */
class RasterPlan(
    val bitmap: Bitmap,
    val widthMm: Float,
    val heightMm: Float,
    /**
     * Nom du FICHIER d'origine, tel que le fournisseur de documents l'a donné —
     * et VIDE quand il n'en donne aucun.
     *
     * Cette valeur est ÉCRITE DANS LE MANIFESTE du projet : elle ne doit donc
     * JAMAIS dépendre de la langue du téléphone (sinon un même projet
     * s'enregistrerait « Plan PDF » ici et « PDF plan » là). Le libellé de repli
     * affiché quand elle est vide se choisit à l'AFFICHAGE, via [displayName].
     */
    val sourceName: String,
    val kind: Kind,
    /** Nombre de pages du PDF source (1 pour une image) — sert à prévenir l'utilisateur. */
    val pageCount: Int = 1
) {
    enum class Kind { JPEG, PNG, PDF }

    val halfW: Float get() = widthMm / 2f
    val halfH: Float get() = heightMm / 2f

    /** Bornes LOCALES (mm), dans le même repère que celles d'un `DxfPlan`. */
    val minX: Float get() = -halfW
    val maxX: Float get() = halfW
    val minY: Float get() = -halfH
    val maxY: Float get() = halfH

    /** Extension de fichier utilisée pour la copie persistée du bitmap. */
    val fileExt: String get() = if (kind == Kind.JPEG) "jpg" else "png"

    @get:androidx.annotation.StringRes val labelRes: Int get() = when (kind) {
        Kind.JPEG -> com.minou.mvrviewer.R.string.raster_jpeg
        Kind.PNG -> com.minou.mvrviewer.R.string.raster_png
        Kind.PDF -> com.minou.mvrviewer.R.string.raster_pdf
    }

    /**
     * Nom AFFICHABLE : celui du fichier s'il en a un, sinon un libellé générique
     * traduit. Le repli est calculé ici, à l'affichage — jamais enregistré.
     */
    fun displayName(ctx: android.content.Context): String = sourceName.ifBlank {
        ctx.getString(
            if (kind == Kind.PDF) com.minou.mvrviewer.R.string.plan_pdf_default_name
            else com.minou.mvrviewer.R.string.plan_image_default_name
        )
    }

    /**
     * Les 4 coins de l'image en repère MONDE MVR (mm), dans l'ordre
     * NO, NE, SE, SO (x0,y0, x1,y1, x2,y2, x3,y3). Y est vers le HAUT, comme
     * partout dans le monde MVR — c'est au dessin de le retourner s'il le faut.
     *
     * Passe par `worldXY`, donc décalage + rotation + échelle + HOMOTHÉTIE sont
     * appliqués d'un coup, exactement comme aux sommets d'un DXF.
     */
    fun worldCorners(tf: ReferencePlanTransform): FloatArray =
        rasterWorldCorners(halfW, halfH, tf)
}

/**
 * Coins NO/NE/SE/SO d'un rectangle centré (demi-largeur/demi-hauteur, mm local)
 * placé par `tf`. Fonction LIBRE : la géométrie du placement se teste ainsi
 * unitairement, sans `Bitmap` (non instanciable hors appareil).
 */
internal fun rasterWorldCorners(halfW: Float, halfH: Float, tf: ReferencePlanTransform): FloatArray {
    val nw = tf.worldXY(-halfW, halfH)
    val ne = tf.worldXY(halfW, halfH)
    val se = tf.worldXY(halfW, -halfH)
    val sw = tf.worldXY(-halfW, -halfH)
    return floatArrayOf(nw.first, nw.second, ne.first, ne.second,
        se.first, se.second, sw.first, sw.second)
}

/**
 * `DxfPlan` VIDE portant les bornes de l'image : c'est le porteur passé à
 * `ReferencePlan` pour un plan matriciel. `isEmpty` reste vrai (aucune
 * polyligne) → la passe DXF 3D et les tracés 2D sortent d'eux-mêmes ; seules les
 * bornes, le centre et la largeur servent (centrage à l'import, pas des flèches
 * de déplacement du panneau).
 */
fun emptyDxfPlanFor(r: RasterPlan) = emptyDxfPlanFor(r.widthMm, r.heightMm)

fun emptyDxfPlanFor(widthMm: Float, heightMm: Float) = DxfPlan(
    polylines = emptyList(),
    minX = -widthMm / 2f, minY = -heightMm / 2f, maxX = widthMm / 2f, maxY = heightMm / 2f,
    unitLabel = "mm", segmentCount = 0, layerCounts = emptyMap(), truncatedSegments = 0
)
