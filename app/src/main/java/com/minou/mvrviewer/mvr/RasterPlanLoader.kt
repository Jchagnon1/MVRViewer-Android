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
        if (head.size >= 4) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            val b2 = head[2].toInt() and 0xFF
            val b3 = head[3].toInt() and 0xFF
            if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) return Format.PDF   // %PDF
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return Format.PNG   // ‰PNG
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return Format.JPEG                // SOI
        }
        val mime = runCatching { cr.getType(uri) }.getOrNull()?.lowercase()
        when {
            mime == "application/pdf" -> return Format.PDF
            mime == "image/png" -> return Format.PNG
            mime != null && mime.startsWith("image/") -> return Format.JPEG
        }
        val ext = name?.substringAfterLast('.', "")?.lowercase()
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
    fun loadImage(cr: ContentResolver, uri: Uri, png: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            .getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, IMAGE_MAX_SIDE, IMAGE_MAX_PIXELS)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        if (png) return bmp   // un PNG ne porte pas d'orientation EXIF
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
        return applyExif(bmp, rot)
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

    /** Rendu de la page 1 d'un PDF + nombre total de pages (null si illisible). */
    fun renderPdfFirstPage(cr: ContentResolver, uri: Uri): Pair<Bitmap, Int>? = runCatching {
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
                    bmp to pages
                }
            }
        }
    }.getOrNull()

    /**
     * Construit le plan matriciel placé : l'image est dimensionnée pour que son
     * PLUS GRAND côté couvre le plus grand côté de la scène MVR (aucun JPEG ne
     * porte d'échelle réelle), en conservant strictement ses proportions.
     * L'utilisateur ajuste ensuite à l'« Homothétie ».
     *
     * @param sceneSpanX/@param sceneSpanY étendue de la scène en mm.
     */
    fun place(
        bitmap: Bitmap, name: String, kind: RasterPlan.Kind, pageCount: Int,
        sceneSpanX: Float, sceneSpanY: Float
    ): RasterPlan {
        val iw = bitmap.width.coerceAtLeast(1).toFloat()
        val ih = bitmap.height.coerceAtLeast(1).toFloat()
        // Scène dégénérée (un seul projecteur, ou pas de décor) → repli 20 m.
        val span = maxOf(sceneSpanX, sceneSpanY).let { if (it.isFinite() && it > 1f) it else 20_000f }
        val f = span / maxOf(iw, ih)
        return RasterPlan(bitmap, iw * f, ih * f, name, kind, pageCount)
    }
}
