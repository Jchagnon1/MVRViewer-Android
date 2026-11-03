package com.minou.mvrviewer.mvr

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Chargement d'un plan de repère MATRICIEL (JPEG / PNG / page 1 d'un PDF).
 *
 * MÉMOIRE — le projet a un historique d'OOM sur gros fichiers : tout passe donc
 * par des bornes DURES (côté maximal ET budget en pixels), jamais par la taille
 * native du fichier. Un scan A0 à 600 dpi fait 500 Mpx : le décoder tel quel
 * tuerait l'application avant même l'affichage.
 *
 * FORMAT — on ne se fie PAS à l'extension seule (le sélecteur Android rend un
 * `content://` dont le nom peut manquer) ni au type MIME seul (le générique
 * « tout type » est fréquent) : le type est décidé sur les OCTETS DE TÊTE, avec
 * le MIME et le nom en simple appoint.
 */
object RasterPlanLoader {

    /** Côté maximal d'une IMAGE décodée (px) — au-delà, sous-échantillonnage. */
    private const val IMAGE_MAX_SIDE = 4096
    /** Budget en pixels d'une IMAGE décodée (~12 Mpx ≈ 48 Mo en ARGB_8888). */
    private const val IMAGE_MAX_PIXELS = 12_000_000L
    /** Côté visé au rendu d'une page PDF (px) — « ~2000 px sur le plus grand côté ». */
    private const val PDF_TARGET_SIDE = 2000
    /** Budget en pixels du rendu PDF (~8 Mpx ≈ 32 Mo en ARGB_8888). */
    private const val PDF_MAX_PIXELS = 8_000_000L

    enum class Format { DXF, JPEG, PNG, PDF }

    /** Convention d'arrivée d'un bitmap : 96 dpi NEUTRE (voir `place`). */
    private const val NEUTRAL_DPI = 96.0
    /** Millimètres par pouce. */
    private const val MM_PER_INCH = 25.4
    /** Points PostScript par pouce (unité de la MediaBox d'un PDF). */
    private const val POINTS_PER_INCH = 72.0

    /**
     * Image décodée + dimensions d'ORIGINE en pixels (avant sous-échantillonnage
     * et après rotation EXIF). Ce sont ELLES qui donnent la taille monde : le
     * bitmap, lui, a pu être divisé pour tenir en mémoire.
     */
    class DecodedImage(val bitmap: Bitmap, val srcWidthPx: Int, val srcHeightPx: Int)

    /**
     * Page 1 d'un PDF rendue + nombre de pages + MediaBox en POINTS PostScript.
     * La taille monde vient des points, pas du rendu (dont la résolution n'est
     * qu'un compromis mémoire).
     */
    class RenderedPdf(val bitmap: Bitmap, val pageCount: Int, val pointWidth: Int, val pointHeight: Int)

    /**
     * Facteur de sous-échantillonnage (puissance de 2, ≥ 1) tel que l'image
     * décodée tienne SOUS les deux bornes : côté maximal et budget de pixels.
     * Exposé (internal) pour être testé unitairement.
     */
    internal fun sampleSizeFor(w: Int, h: Int, maxSide: Int, maxPixels: Long): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        // 16 doublements = facteur 65 536 : borne de sûreté, jamais atteinte en pratique.
        while (s < 65_536) {
            val cw = w / s
            val ch = h / s
            if (cw <= maxSide && ch <= maxSide && cw.toLong() * ch.toLong() <= maxPixels) return s
            s *= 2
        }
        return s
    }

    /** Nom d'affichage du document choisi (« plan-salle.pdf »), ou null. */
    fun displayName(cr: ContentResolver, uri: Uri): String? = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /**
     * Types MIME sous lesquels un VRAI DXF est enregistré. Le piège est
     * `image/vnd.dxf` : c'est le type officiellement enregistré du DXF (IANA), il
     * commence par « image/ » et les fournisseurs de documents le renvoient tel
     * quel. Sans cette liste, la règle générique des types « image/… » enverrait
     * un dessin vectoriel à `BitmapFactory`, qui échoue — plan « illisible ».
     */
    private val DXF_MIMES = setOf(
        "image/vnd.dxf", "image/x-dxf", "image/dxf",
        "application/dxf", "application/x-dxf", "application/vnd.dxf",
        "drawing/x-dxf", "drawing/dxf"
    )

    /**
     * Type du document : octets de tête d'abord (seule source fiable), MIME puis
     * extension en appoint. Tout ce qui n'est ni image ni PDF part au parseur DXF
     * — c'est le comportement historique, donc l'import DXF est inchangé.
     */
    fun sniff(cr: ContentResolver, uri: Uri, name: String?): Format {
        val head = runCatching {
            cr.openInputStream(uri)?.use { ins ->
                val b = ByteArray(8)
                var n = 0
                while (n < b.size) {
                    val r = ins.read(b, n, b.size - n)
                    if (r <= 0) break
                    n += r
                }
                b.copyOf(n)
            }
        }.getOrNull() ?: ByteArray(0)
        val mime = runCatching { cr.getType(uri) }.getOrNull()
        return formatFor(head, mime, name)
    }

    /**
     * Décision de format, PURE (donc testable en JVM) : octets de tête, puis
     * exclusion explicite du DXF, puis MIME, puis extension.
     *
     * R5 — un fichier d'extension `.dxf` ou de MIME DXF part TOUJOURS au parseur
     * DXF, avant même d'examiner la règle générique des types « image/… ».
     */
    internal fun formatFor(head: ByteArray, mime: String?, name: String?): Format {
        if (head.size >= 4) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            val b2 = head[2].toInt() and 0xFF
            val b3 = head[3].toInt() and 0xFF
            if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) return Format.PDF   // %PDF
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return Format.PNG   // ‰PNG
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return Format.JPEG                // SOI
        }
        // « image/vnd.dxf; charset=… » → on ne garde que le type.
        val m = mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        // R5 : le DXF d'abord, sinon la règle « image/* » ci-dessous l'avalerait.
        if (ext == "dxf" || (m != null && m in DXF_MIMES)) return Format.DXF
        when {
            m == "application/pdf" -> return Format.PDF
            m == "image/png" -> return Format.PNG
            m != null && m.startsWith("image/") -> return Format.JPEG
        }
        return when (ext) {
            "pdf" -> Format.PDF
            "png" -> Format.PNG
            "jpg", "jpeg" -> Format.JPEG
            else -> Format.DXF
        }
    }

    /**
     * Décode une image bornée en mémoire, orientation EXIF appliquée (sinon les
     * photos de plan arrivent couchées). Appeler HORS thread principal.
     */
    fun loadImage(cr: ContentResolver, uri: Uri, png: Boolean): DecodedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            .getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(srcW, srcH, IMAGE_MAX_SIDE, IMAGE_MAX_PIXELS)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        if (png) return DecodedImage(bmp, srcW, srcH)   // un PNG ne porte pas d'orientation EXIF
        // ExifInterface du FRAMEWORK (déprécié mais présent depuis l'API 24, donc
        // toujours au-dessus de notre minSdk 28) : évite d'ajouter une dépendance
        // pour la seule lecture d'un entier d'orientation.
        @Suppress("DEPRECATION")
        val rot = runCatching {
            cr.openInputStream(uri)?.use { ins ->
                android.media.ExifInterface(ins).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        }.getOrNull() ?: android.media.ExifInterface.ORIENTATION_NORMAL
        // Une rotation d'un quart de tour ÉCHANGE aussi les dimensions d'origine :
        // sans ça, une photo couchée arriverait au format de son voisin.
        val swap = rot == android.media.ExifInterface.ORIENTATION_ROTATE_90 ||
            rot == android.media.ExifInterface.ORIENTATION_ROTATE_270 ||
            rot == android.media.ExifInterface.ORIENTATION_TRANSPOSE ||
            rot == android.media.ExifInterface.ORIENTATION_TRANSVERSE
        return DecodedImage(applyExif(bmp, rot), if (swap) srcH else srcW, if (swap) srcW else srcH)
    }

    @Suppress("DEPRECATION")
    private fun applyExif(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return runCatching {
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            out
        }.getOrDefault(src)
    }

    /** Rendu de la page 1 d'un PDF + pages + MediaBox en points (null si illisible). */
    fun renderPdfFirstPage(cr: ContentResolver, uri: Uri): RenderedPdf? = runCatching {
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                val pages = renderer.pageCount
                if (pages <= 0) return@use null
                renderer.openPage(0).use { page ->
                    val pw = page.width.coerceAtLeast(1)      // points PostScript (1/72")
                    val ph = page.height.coerceAtLeast(1)
                    var f = PDF_TARGET_SIDE.toFloat() / maxOf(pw, ph).toFloat()
                    // Budget mémoire : on redescend si la page visée est trop lourde.
                    while (f > 0.05f && (pw * f).toLong() * (ph * f).toLong() > PDF_MAX_PIXELS) f *= 0.8f
                    val w = (pw * f).toInt().coerceIn(1, 8192)
                    val h = (ph * f).toInt().coerceIn(1, 8192)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // Une page PDF a un fond TRANSPARENT : sans ce blanc, un plan
                    // noir sur transparent devient invisible sur fond sombre.
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    RenderedPdf(bmp, pages, pw, ph)
                }
            }
        }
    }.getOrNull()

    /**
     * Taille MONDE (mm) d'un plan matriciel — convention d'arrivée COMMUNE aux
     * deux plateformes, DÉTERMINISTE et indépendante du show :
     *
     *  - bitmap (JPEG/PNG) : pixels d'ORIGINE ÷ 96 × 25,4. La base 96 dpi est
     *    NEUTRE et volontairement constante : le dpi déclaré dans un fichier est
     *    faux bien trop souvent pour qu'on ose s'y fier ;
     *  - PDF : MediaBox en points × 25,4 ÷ 72 — un PDF tracé à l'échelle arrive
     *    donc à sa TAILLE PHYSIQUE réelle.
     *
     * On n'étire SURTOUT PAS le plan sur l'emprise de la scène MVR : la taille
     * d'arrivée ne doit pas dépendre du show ouvert, sinon le même fichier
     * n'arrive pas pareil chez deux personnes. L'utilisateur ajuste ensuite à
     * l'« Homothétie ».
     *
     * @param srcWidth/@param srcHeight dimensions SOURCE : pixels d'origine pour
     *   une image, points PostScript de la MediaBox pour un PDF.
     */
    fun worldSizeMm(kind: RasterPlan.Kind, srcWidth: Float, srcHeight: Float): Pair<Float, Float> {
        val w = if (srcWidth.isFinite() && srcWidth > 0f) srcWidth.toDouble() else 1.0
        val h = if (srcHeight.isFinite() && srcHeight > 0f) srcHeight.toDouble() else 1.0
        val perUnit =
            if (kind == RasterPlan.Kind.PDF) MM_PER_INCH / POINTS_PER_INCH
            else MM_PER_INCH / NEUTRAL_DPI
        return (w * perUnit).toFloat() to (h * perUnit).toFloat()
    }

    /** Construit le plan matriciel placé à sa taille d'arrivée (cf. `worldSizeMm`). */
    fun place(
        bitmap: Bitmap, name: String, kind: RasterPlan.Kind, pageCount: Int,
        srcWidth: Float, srcHeight: Float
    ): RasterPlan {
        val (mmW, mmH) = worldSizeMm(kind, srcWidth, srcHeight)
        return RasterPlan(bitmap, mmW, mmH, name, kind, pageCount)
    }
}
