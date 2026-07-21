package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * EXPORT PDF « EN CONSTRUCTION ».
 *
 * L'utilisateur cadre le plan comme il le veut, ajoute la vue courante au
 * document, la nomme, recommence, puis génère UN PDF de toutes ces vues. Une
 * page doit donc reproduire ce qu'il voyait : c'est tout l'intérêt du procédé,
 * et c'est pourquoi le rendu passe par la MÊME fonction de dessin que l'écran
 * (cf. drawPlanContent) au lieu d'un dessinateur PDF parallèle qui finirait
 * fatalement par diverger.
 */

/**
 * Instantané d'une vue plan. On y fige le CADRAGE (en coordonnées monde, pas en
 * pixels : la page n'a ni la taille ni la densité de l'écran) et l'ensemble des
 * réglages d'affichage, car `SceneOptions` est partagé et mutable — sans copie,
 * changer une option après coup réécrirait les vues déjà ajoutées.
 */
internal class PlanViewCapture(
    var name: String,
    /** Centre du cadrage, en coordonnées plan (mm). */
    val centerX: Float, val centerY: Float,
    /** Demi-étendue visible, en mm monde. */
    val halfW: Float, val halfH: Float,
    val layerColors: Boolean,
    val showStructure: Boolean,
    val showLabels: Boolean,
    val showLegend: Boolean,
    /** Champs affichés et champs détachés au moment de la capture. */
    val labelFields: Set<LabelContent>,
    val labelDetached: Set<LabelContent>,
    val labelSize: Float,
    val labelOffset: Float,
    val background: Color,
    val bgDark: Boolean,
    val showSatellite: Boolean,
    val satelliteOpacity: Float,
    val hiddenElements: Set<String>,
    val hiddenLayers: Set<String>,
    val labelShift: Map<String, Offset>,
    val selected: Set<Int>,
    /** Densité de l'écran au moment de la capture (ramène les décalages en points). */
    val screenDensity: Float,
    /**
     * Échelle de l'ÉCRAN au moment de la capture (px/mm). La page a la sienne,
     * bien plus faible ; c'est pourtant celle-ci qui doit décider si les
     * étiquettes et les silhouettes s'affichent, sinon la page perd ce que
     * l'utilisateur voyait (cf. PlanRenderSpec.detailPxPerMm).
     */
    val screenPxPerMm: Float,
    /**
     * COPIE du placement du plan de repère. Sa transformée est mutable et réglée
     * en direct aux curseurs : sans copie, bouger le plan (ou décocher
     * « Visible ») après avoir ajouté des vues réécrivait toutes les pages.
     */
    val refTransform: com.minou.mvrviewer.mvr.ReferencePlanTransform?
)

/**
 * Données lourdes communes à toutes les pages (fils de fer, tracés DXF, satellite).
 * Elles ne dépendent pas du cadrage : on ne les reconstruit donc pas par page.
 */
internal class PlanExportSource(
    val data: PlanData,
    val layerIndex: Map<String, Int>,
    val wire: PlanWireframe.Built?,
    val fixWire: PlanWireframe.FixtureWire?,
    val dxfPaths: DxfPaths?,
    val satellite: com.minou.mvrviewer.mvr.SatelliteOverlay?,
    /** Légende : calque → nombre de projecteurs, déjà triée. */
    val legend: List<Pair<String, Int>>,
    val documentTitle: String
)

// Géométrie de page : A4 paysage en POINTS PostScript (72 dpi), comme attendu
// par PdfDocument. Le dessin s'y fait donc à densité 1 : une pastille de 7 px
// devient un rond de 7 pt ≈ 2,5 mm — l'ordre de grandeur d'un plan tracé.
private const val PAGE_W = 842
private const val PAGE_H = 595
private const val MARGIN = 30f
private const val HEADER_H = 44f
private const val FOOTER_H = 22f

/**
 * Construit le document. Coûteux (reconstruction des tracés par jeu d'éléments
 * masqués) → à appeler hors thread principal.
 */
internal fun buildPlanPdf(
    ctx: android.content.Context,
    src: PlanExportSource,
    views: List<PlanViewCapture>
): File {
    val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(ctx),
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr
    )
    val doc = android.graphics.pdf.PdfDocument()
    // Les tracés dépendent du jeu d'éléments masqués (il est filtré à la
    // construction) : on les mutualise par jeu identique, cas courant où toutes
    // les vues partagent le même masquage.
    val pathCache = HashMap<Set<String>, PdfPagePaths>()

    views.forEachIndexed { index, v ->
        val page = doc.startPage(
            android.graphics.pdf.PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
        )
        val paths = pathCache.getOrPut(v.hiddenElements) {
            val wf = src.wire
            PdfPagePaths(
                struct = if (wf == null || wf.isEmpty) null else buildStructurePaths(wf, v.hiddenElements),
                fixtures = src.fixWire?.let { buildFixturePaths(src.data, it, v.hiddenElements) },
                dots = dotsPath(structureDots(wf, v.hiddenElements)),
                // Repli « un point par objet » quand aucun fil de fer n'existe :
                // il doit lui aussi respecter le masquage de la vue capturée.
                fallback = dotsPath(src.data.structure.filterIndexed { i, _ ->
                    src.data.structureKeys.getOrNull(i)?.let { it !in v.hiddenElements } ?: true
                })
            )
        }
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(page.canvas),
            Size(PAGE_W.toFloat(), PAGE_H.toFloat())
        ) {
            drawPdfPage(src, v, index + 1, views.size, measurer, paths)
        }
        doc.finishPage(page)
    }

    val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
    // Nom stable et lisible : c'est lui que verra le destinataire du partage.
    val safe = src.documentTitle.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(48).trim('_')
    val out = File(dir, (if (safe.isEmpty()) "plan" else safe) + ".pdf")
    // `close()` dans un finally : une page très chargée peut faire échouer
    // l'écriture (disque plein, mémoire), et un PdfDocument non fermé retient
    // ses bitmaps de page. L'appelant relance alors l'export sur un tas propre.
    try {
        java.io.FileOutputStream(out).use { doc.writeTo(it) }
    } finally {
        doc.close()
    }
    return out
}

/** Tracés d'une page, mutualisés entre vues partageant le même masquage. */
private class PdfPagePaths(
    val struct: StructPaths?,
    val fixtures: FixturePaths?,
    val dots: androidx.compose.ui.graphics.Path,
    val fallback: androidx.compose.ui.graphics.Path
)

/** Une page : cartouche + plan cadré comme à l'écran + légende. */
private fun DrawScope.drawPdfPage(
    src: PlanExportSource,
    v: PlanViewCapture,
    pageNo: Int, pageCount: Int,
    measurer: TextMeasurer,
    paths: PdfPagePaths
) {
    val ink = if (v.bgDark) Color(0xFFECECEC) else Color(0xFF222222)
    val left = MARGIN
    val top = MARGIN + HEADER_H
    val right = PAGE_W - MARGIN
    val bottom = PAGE_H - MARGIN - FOOTER_H
    val cw = right - left
    val ch = bottom - top

    // Page blanche (le PDF part transparent) : le cartouche reste toujours lisible,
    // seul le cadre du plan prend la couleur de fond choisie par l'utilisateur.
    drawRect(Color.White, topLeft = Offset.Zero, size = Size(PAGE_W.toFloat(), PAGE_H.toFloat()))
    drawRect(v.background, topLeft = Offset(left, top), size = Size(cw, ch))

    // Projection : on fait tenir le RECTANGLE VU à l'écran dans le cadre de la
    // page (« fit »), en gardant le même centre — donc le même cadrage, à la
    // différence de proportions près entre écran et page.
    val pxPerMm = minOf(cw / (2f * maxOf(v.halfW, 1e-3f)), ch / (2f * maxOf(v.halfH, 1e-3f)))
    val centerPx = Offset(
        left + cw / 2f - pxPerMm * (v.centerX - src.data.cx),
        top + ch / 2f - pxPerMm * (v.centerY - src.data.cy)
    )

    clipRect(left, top, right, bottom) {
        drawPlanContent(
            PlanRenderSpec(
                data = src.data,
                layerIndex = src.layerIndex,
                pxPerMm = pxPerMm,
                // Décisions de détail prises à l'échelle de l'ÉCRAN capturé.
                detailPxPerMm = v.screenPxPerMm,
                centerPx = centerPx,
                planBg = v.background,
                bgDark = v.bgDark,
                inkColor = ink,
                layerColors = v.layerColors,
                showStructure = v.showStructure,
                showLabels = v.showLabels,
                labelFields = v.labelFields,
                labelDetached = v.labelDetached,
                labelSize = v.labelSize,
                labelOffset = v.labelOffset,
                hiddenElements = v.hiddenElements,
                hiddenLayers = v.hiddenLayers,
                structPaths = paths.struct,
                structDots = paths.dots,
                fallbackDots = paths.fallback,
                fixPaths = paths.fixtures,
                fixWire = src.fixWire,
                refTransform = v.refTransform,
                dxfPaths = src.dxfPaths,
                satellite = src.satellite,
                showSatellite = v.showSatellite,
                satelliteOpacity = v.satelliteOpacity,
                selected = v.selected,
                labelShift = v.labelShift,
                // Les décalages ont été posés en pixels d'écran ; la page est en
                // points (densité 1) — sans cette conversion, une étiquette
                // déplacée partirait trois fois trop loin sur un écran dense.
                labelShiftScale = if (v.screenDensity > 0f) 1f / v.screenDensity else 1f,
                activeLabelKeys = emptySet(),
                measurer = measurer,
                labelCache = HashMap(),
                lowDetail = false,
                scaleBarAnchor = Offset(left + 14f, bottom - 16f)
            )
        )
        // Légende par-dessus le plan, comme à l'écran.
        if (v.showLegend && src.legend.isNotEmpty()) {
            drawLegend(src, v, measurer, Offset(left + 10f, top + 10f))
        }
    }
    drawRect(Color(0xFF888888), topLeft = Offset(left, top), size = Size(cw, ch), style = Stroke(0.8f))

    // ---- Cartouche ----
    val title = measurer.measure(
        v.name.ifBlank { "Vue $pageNo" },
        style = TextStyle(fontSize = 15.sp, color = Color(0xFF111111), fontWeight = FontWeight.SemiBold)
    )
    drawText(title, topLeft = Offset(left, MARGIN + 4f))
    // Échelle : 1 pt = 25,4/72 mm de papier → rapport plan/papier.
    val ratio = if (pxPerMm > 0f) (72f / 25.4f) / pxPerMm else 0f
    val scaleText = if (ratio > 0f) "Échelle ≈ 1:${Math.round(ratio)}" else ""
    val sub = measurer.measure(
        scaleText + "   ·   " + scaleBarLabel(niceScaleBarMm(pxPerMm)) + " (barre)",
        style = TextStyle(fontSize = 9.sp, color = Color(0xFF666666))
    )
    drawText(sub, topLeft = Offset(right - sub.size.width, MARGIN + 10f))

    val foot = measurer.measure(
        "${src.documentTitle}   ·   page $pageNo/$pageCount",
        style = TextStyle(fontSize = 8.sp, color = Color(0xFF888888))
    )
    drawText(foot, topLeft = Offset(left, bottom + 6f))
}

/** Légende des calques (même contenu que le panneau de la vue plan). */
private fun DrawScope.drawLegend(
    src: PlanExportSource, v: PlanViewCapture, measurer: TextMeasurer, at: Offset
) {
    // Légende recomptée PAR PAGE depuis le CACHÉ EFFECTIF de la capture
    // (v.hiddenElements = masquage + solo au moment de l'ajout de la vue) : la
    // légende de la page reflète EXACTEMENT les projecteurs qui y sont dessinés —
    // solo compris — au lieu du décompte global du show. Si le solo a tout filtré,
    // il n'y a rien à légender : on sort.
    val rows = src.data.fixtures.asSequence()
        .filter { it.key !in v.hiddenElements }
        .groupingBy { it.layer }.eachCount()
        .toList().sortedByDescending { it.second }
        .take(12)
    if (rows.isEmpty()) return
    val entries = rows.map { (layer, n) ->
        layer to measurer.measure("$layer · $n", style = TextStyle(fontSize = 8.sp, color = Color(0xFF222222)))
    }
    val lineH = 11f
    val boxW = (entries.maxOf { it.second.size.width } + 30f)
    val boxH = entries.size * lineH + 10f
    drawRect(Color.White.copy(alpha = 0.92f), topLeft = at, size = Size(boxW, boxH))
    drawRect(Color(0xFFBBBBBB), topLeft = at, size = Size(boxW, boxH), style = Stroke(0.6f))
    entries.forEachIndexed { i, (layer, tl) ->
        val y = at.y + 5f + i * lineH
        val c = if (v.layerColors) Color(LayerColors.colorInt(src.layerIndex, layer)) else Color(0xFF6E6E73)
        drawCircle(c, radius = 3f, center = Offset(at.x + 9f, y + 4.5f))
        drawText(tl, topLeft = Offset(at.x + 17f, y))
    }
}

/** Partage le PDF (ACTION_SEND) — même mécanisme que le rapport de diagnostic. */
internal fun sharePlanPdf(ctx: android.content.Context, file: File) {
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }.getOrNull() ?: return
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Envoyer le PDF")) }
}

/** Impression par le service système (aperçu, choix d'imprimante, PDF virtuel). */
internal fun printPlanPdf(ctx: android.content.Context, file: File) {
    val pm = ctx.getSystemService(android.content.Context.PRINT_SERVICE) as? android.print.PrintManager ?: return
    runCatching { pm.print(file.nameWithoutExtension, PdfFilePrintAdapter(file), null) }
}

/**
 * Adaptateur d'impression minimal : le document est DÉJÀ un PDF, il n'y a donc
 * qu'à le recopier vers la destination choisie par le service d'impression.
 */
private class PdfFilePrintAdapter(private val file: File) : android.print.PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: android.print.PrintAttributes?,
        newAttributes: android.print.PrintAttributes?,
        cancellationSignal: android.os.CancellationSignal?,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
        val info = android.print.PrintDocumentInfo.Builder(file.name)
            .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out android.print.PageRange>?,
        destination: android.os.ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal?,
        callback: WriteResultCallback
    ) {
        runCatching {
            java.io.FileInputStream(file).use { input ->
                java.io.FileOutputStream(destination.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
        }.onFailure { callback.onWriteFailed(it.message); return }
        callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
    }
}
