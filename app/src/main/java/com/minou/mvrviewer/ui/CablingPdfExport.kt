package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.minou.mvrviewer.sync.CablingSettings
import com.minou.mvrviewer.sync.CircuitLoad
import com.minou.mvrviewer.sync.DmxCablingCalc
import com.minou.mvrviewer.sync.DmxCablingDTO
import com.minou.mvrviewer.sync.DmxCoreLoad
import com.minou.mvrviewer.sync.DmxDistributorKind
import com.minou.mvrviewer.sync.DistributorKind
import com.minou.mvrviewer.sync.PhaseLoad
import com.minou.mvrviewer.sync.PowerCablingCalc
import com.minou.mvrviewer.sync.PowerCablingDTO
import com.minou.mvrviewer.mvr.ReferencePlan
import com.minou.mvrviewer.mvr.SatelliteOverlay
import java.io.File
import androidx.compose.ui.res.stringResource
import com.minou.mvrviewer.R

/**
 * EXPORT PDF DE CÂBLAGE (phase 5).
 *
 * Trois documents, dans UN SEUL PdfDocument (comme [buildPlanPdf]) : tableaux de
 * DISTRIBUTION ÉLEC (Socapex / lignes solo), tableaux de CÂBLAGE DMX (lignes
 * simples / multipaires), et PLANS REPÉRÉS (le plan coloré par câblage, une page
 * par Socapex + une page par ligne DMX). « Dossier complet » = les trois à la
 * suite ; exports séparés = un sous-ensemble.
 *
 * AUCUN RECALCUL : les charges et empreintes viennent EXCLUSIVEMENT des mêmes
 * fonctions pures qu'à l'écran ([PowerCablingCalc], [DmxCablingCalc]) et des
 * résolveurs conso/empreinte déjà branchés (wattsOf / channelsOf / universeOf).
 * Les pages plan réutilisent [renderPlanPagesInto] (moteur de page plan commun) —
 * zéro duplication du rendu du plan ni des sommes de W / d'empreintes DMX.
 */

// Pages tableau : A4 PORTRAIT en points PostScript (un même PdfDocument accepte
// des tailles mixtes — les pages plan restent en paysage).
private const val P_PAGE_W = 595
private const val P_PAGE_H = 842
private const val P_MARGIN = 34f
private const val P_HEADER = 40f
private const val P_FOOTER = 22f

private const val C_RED: Long = 0xFFC62828     // surcharge / dépassement
private const val C_INK: Long = 0xFF222222
private const val C_TITLE: Long = 0xFF111111
private const val C_SUB: Long = 0xFF666666
private const val C_MUTE: Long = 0xFF888888
private const val C_AMBER: Long = 0xFFB26A00   // conso/empreinte inconnue + déséquilibre
private const val C_PHASE: Long = 0xFF3949AB

/** Les trois documents produits ; « complet » = les trois dans l'ordre. */
internal enum class CablingPdfPart { ELEC, DMX, PLANS }

/**
 * Générateur UNIQUE. Les closures (wattsOf/channelsOf/universeOf + libellés) sont
 * bâties par l'appelant depuis les résolveurs EXISTANTS (aucune duplication). Les
 * pages plan ne sont produites que si `planSrc` + la vue correspondante sont
 * fournis (construits hors-main par l'appelant).
 */
internal fun buildCablingPdf(
    ctx: android.content.Context,
    parts: Set<CablingPdfPart>,
    cablingDto: PowerCablingDTO,
    dmxDto: DmxCablingDTO,
    settings: CablingSettings,
    wattsOf: (String) -> Int?,
    channelsOf: (String) -> Int?,
    universeOf: (String) -> Int?,
    addressOf: (String) -> String?,
    modeOf: (String) -> String?,
    labelOf: (String) -> String?,
    specOf: (String) -> String?,
    documentTitle: String,
    planSrc: PlanExportSource?,
    socaView: PlanViewCapture?,
    dmxView: PlanViewCapture?
): File {
    val m = pdfTextMeasurer(ctx)
    val left = P_MARGIN
    val right = P_PAGE_W - P_MARGIN
    val contentTop = P_MARGIN + P_HEADER
    val contentBottom = P_PAGE_H - P_MARGIN - P_FOOTER
    val contentH = contentBottom - contentTop

    // 1) Tableaux ÉLEC / DMX : d'abord en LIGNES (mesurées), puis paginés → on
    //    connaît le nombre de pages AVANT le rendu, donc le pied « N/total » est juste.
    val elecPages = if (CablingPdfPart.ELEC in parts)
        paginate(buildElecRows(ctx, cablingDto, settings, wattsOf, labelOf, specOf, m, left, right), contentH)
            .ifEmpty { listOf(emptyList()) }
    else emptyList()
    val dmxPages = if (CablingPdfPart.DMX in parts)
        paginate(buildDmxRows(ctx, dmxDto, channelsOf, universeOf, addressOf, modeOf, labelOf, specOf, m, left, right), contentH)
            .ifEmpty { listOf(emptyList()) }
    else emptyList()
    val planViews = if (CablingPdfPart.PLANS in parts && planSrc != null)
        listOfNotNull(socaView, dmxView) else emptyList()

    val total = elecPages.size + dmxPages.size + planViews.size
    val doc = android.graphics.pdf.PdfDocument()
    var pageNo = 0
    for (rows in elecPages) {
        pageNo++
        renderTablePage(ctx, doc, m, ctx.getString(R.string.cabling_export_elec_doc), ctx.getString(R.string.pdf_no_elec_dist),
            rows, pageNo, total, documentTitle, left, right, contentTop, contentBottom)
    }
    for (rows in dmxPages) {
        pageNo++
        renderTablePage(ctx, doc, m, ctx.getString(R.string.cabling_export_dmx), ctx.getString(R.string.pdf_no_dmx_line),
            rows, pageNo, total, documentTitle, left, right, contentTop, contentBottom)
    }
    if (planViews.isNotEmpty() && planSrc != null) {
        renderPlanPagesInto(ctx, doc, planSrc, planViews, m, pageOffset = pageNo, totalPages = total)
        pageNo += planViews.size
    }
    // Garde-fou : jamais un PDF à 0 page (ex. « Plans repérés » sans géométrie plan).
    if (pageNo == 0) {
        renderTablePage(ctx, doc, m, documentTitle, ctx.getString(R.string.pdf_no_cabling_data),
            emptyList(), 1, 1, documentTitle, left, right, contentTop, contentBottom)
    }
    return writePdfToCache(ctx, doc, documentTitle, fallbackName = "cablage")
}

// ---------------------------------------------------------------------------
// E) Pages PLANS REPÉRÉS — capture PLEIN PLAN colorée par câblage
// ---------------------------------------------------------------------------

/**
 * Construit une [PlanViewCapture] PLEIN PLAN (tout le show) colorée par câblage.
 * `screenPxPerMm` est réglé sur l'échelle RÉELLE de la page paysage
 * ([planPagePxPerMm]) pour que les décisions de détail collent au rendu. Étiquettes
 * masquées (illisibles au plein plan) : la LÉGENDE porte le sens des couleurs.
 */
internal fun cablingPlanView(
    name: String,
    data: PlanData,
    colorMode: PlanColorMode,
    cablingColor: Map<String, Color>,
    cablingLegend: List<Pair<String, Color>>,
    cablingText: (PlanFixture, LabelContent) -> String?,
    satellite: SatelliteOverlay?,
    hiddenLayers: Set<String>,
    referencePlan: ReferencePlan?,
    // ANNEAU de la 2e dimension (E3) : DMX en page Socapex, Socapex en page DMX.
    cablingRingColor: Map<String, Color> = emptyMap()
): PlanViewCapture {
    val halfW = data.spanX / 2f * 1.05f
    val halfH = data.spanY / 2f * 1.05f
    return PlanViewCapture(
        name = name,
        centerX = data.cx, centerY = data.cy,
        halfW = halfW, halfH = halfH,
        layerColors = true,
        colorMode = colorMode,
        cablingColor = cablingColor,
        cablingRingColor = cablingRingColor,
        cablingLegend = cablingLegend,
        cablingText = cablingText,
        showStructure = true,
        showLabels = false,
        showLegend = true,
        labelFields = emptySet(),
        labelDetached = emptySet(),
        labelSize = 1f,
        labelOffset = 1f,
        background = Color.White,
        bgDark = false,
        showSatellite = satellite != null,
        satelliteOpacity = 0.55f,
        hiddenElements = emptySet(),
        hiddenLayers = hiddenLayers,
        labelShift = emptyMap(),
        selected = emptySet(),
        screenDensity = 1f,
        screenPxPerMm = planPagePxPerMm(halfW, halfH),
        refTransform = referencePlan?.transform?.copy()
    )
}

// ---------------------------------------------------------------------------
// Flux de lignes paginé (réutilisé par les deux tableaux)
// ---------------------------------------------------------------------------

/** Une ligne de tableau : hauteur + un garde-orphelin + son dessinateur. */
private class Row(
    val height: Float,
    /** Espace qui doit rester APRÈS la ligne sur la page, sinon on saute avant
     *  (évite un en-tête seul en bas de page). */
    val orphanGuard: Float = 0f,
    val draw: (DrawScope, Float) -> Unit
)

/** Remplit des pages en respectant la hauteur utile ; garde-orphelin sur en-têtes. */
private fun paginate(rows: List<Row>, contentH: Float): List<List<Row>> {
    val pages = ArrayList<MutableList<Row>>()
    var cur = ArrayList<Row>()
    var y = 0f
    for (r in rows) {
        if (cur.isNotEmpty() && y + r.height + r.orphanGuard > contentH) {
            pages.add(cur); cur = ArrayList(); y = 0f
        }
        cur.add(r); y += r.height
    }
    if (cur.isNotEmpty()) pages.add(cur)
    return pages
}

/** Rend une page tableau (fond blanc, titre de section, lignes, pied). */
private fun renderTablePage(
    ctx: android.content.Context,
    doc: android.graphics.pdf.PdfDocument,
    m: TextMeasurer,
    sectionTitle: String,
    emptyMessage: String,
    rows: List<Row>,
    pageNo: Int, total: Int, docTitle: String,
    left: Float, right: Float, contentTop: Float, contentBottom: Float
) {
    val page = doc.startPage(
        android.graphics.pdf.PdfDocument.PageInfo.Builder(P_PAGE_W, P_PAGE_H, pageNo).create()
    )
    CanvasDrawScope().draw(
        Density(1f), LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(page.canvas),
        Size(P_PAGE_W.toFloat(), P_PAGE_H.toFloat())
    ) {
        drawRect(Color.White, topLeft = Offset.Zero, size = Size(P_PAGE_W.toFloat(), P_PAGE_H.toFloat()))
        val t = m.measure(sectionTitle, style(16f, C_TITLE, FontWeight.SemiBold))
        drawText(t, topLeft = Offset(left, P_MARGIN + 4f))
        drawLine(Color(0xFFCCCCCC), Offset(left, contentTop - 6f), Offset(right, contentTop - 6f), strokeWidth = 1f)
        if (rows.isEmpty()) {
            val e = m.measure(emptyMessage, style(11f, 0xFF777777))
            drawText(e, topLeft = Offset(left, contentTop + 8f))
        } else {
            var y = contentTop
            for (r in rows) { r.draw(this, y); y += r.height }
        }
        val foot = m.measure("$docTitle · " + ctx.getString(R.string.pdf_page_fmt, pageNo, total), style(8f, C_MUTE))
        drawText(foot, topLeft = Offset(left, contentBottom + 6f))
    }
    doc.finishPage(page)
}

// ---------------------------------------------------------------------------
// C) Tableau DISTRIBUTION ÉLEC
// ---------------------------------------------------------------------------

private fun buildElecRows(
    ctx: android.content.Context,
    dto: PowerCablingDTO,
    settings: CablingSettings,
    wattsOf: (String) -> Int?,
    labelOf: (String) -> String?,
    specOf: (String) -> String?,
    m: TextMeasurer, left: Float, right: Float
): List<Row> {
    val rows = ArrayList<Row>()
    dto.distributors.forEachIndexed { di, dist ->
        if (di > 0) rows.add(separatorRow(left, right))
        val type = if (dist.kind == DistributorKind.SOCA) ctx.getString(R.string.pdf_socapex_circuits_fmt, dist.circuitCount) else ctx.getString(R.string.pdf_solo_pc16)
        rows.add(distHeaderRow(m, left, right, dist.name, type, dist.colorArgb))

        val circuitLoads = PowerCablingCalc.circuitLoads(dist, dto.assignments, settings, wattsOf)
        val phaseLoads = PowerCablingCalc.phaseLoads(dist, dto.assignments, settings, wattsOf)
        val usedPhases = (1..dist.circuitCount).map { dist.phaseOf(it) }.toSortedSet()

        for (phase in usedPhases) {
            rows.add(phaseHeaderRow(m, left, right, ctx.getString(R.string.cabling_phase_fmt, phase)))
            for (cl in circuitLoads.filter { it.phase == phase }) {
                rows.add(circuitHeaderRow(m, left, right, cl, settings.circuitLimitW))
                if (cl.unknownCount > 0)
                    rows.add(noteRow(m, left, right, "⚠ " + ctx.getString(R.string.pdf_unknown_watts_fmt, cl.unknownCount)))
                if (cl.fixtures.isEmpty())
                    rows.add(fixtureLineRow(m, left, right, "— " + ctx.getString(R.string.cabling_no_fixture), C_MUTE))
                else for (u in cl.fixtures) {
                    val w = wattsOf(u)
                    // Colonnes alignées : ID | type GDTF | W (à droite). Ambre si conso inconnue.
                    rows.add(fixtureColumnsRow(
                        m, left, right,
                        fixtureId = labelOf(u) ?: u,
                        gdtfType = specOf(u) ?: "—",
                        detail = null,
                        value = if (w == null) "? W" else "$w W",
                        unknown = w == null
                    ))
                }
            }
        }
        // Pied : la ligne d'équilibrage L1/L2/L3 n'a de sens QUE pour un
        // distributeur MULTI-PHASES (Socapex). Une ligne SOLO (une seule phase
        // utilisée) n'affiche que son total — pas de ligne d'équilibrage (aligné iOS).
        if (usedPhases.size >= 2)
            rows.add(phaseBalanceRow(ctx, m, left, right, phaseLoads, usedPhases))
        val total = PowerCablingCalc.distributorTotalW(dist, dto.assignments, settings, wattsOf)
        rows.add(totalRow(m, left, right, ctx.getString(R.string.pdf_total_distributor), "$total W"))
    }
    return rows
}

// ---------------------------------------------------------------------------
// D) Tableau CÂBLAGE DMX
// ---------------------------------------------------------------------------

private fun buildDmxRows(
    ctx: android.content.Context,
    dto: DmxCablingDTO,
    channelsOf: (String) -> Int?,
    universeOf: (String) -> Int?,
    addressOf: (String) -> String?,
    modeOf: (String) -> String?,
    labelOf: (String) -> String?,
    specOf: (String) -> String?,
    m: TextMeasurer, left: Float, right: Float
): List<Row> {
    val rows = ArrayList<Row>()
    dto.distributors.forEachIndexed { di, dist ->
        if (di > 0) rows.add(separatorRow(left, right))
        val type = if (dist.kind == DmxDistributorKind.MULTI) ctx.getString(R.string.pdf_multicore_cores_fmt, dist.coreCount) else ctx.getString(R.string.dmx_single_line_label)
        rows.add(distHeaderRow(m, left, right, dist.name, type, dist.colorArgb))

        val coreLoads = DmxCablingCalc.coreLoads(dist, dto.assignments, channelsOf, universeOf)
        for (cl in coreLoads) {
            val coreLabel = if (dist.kind == DmxDistributorKind.MULTI) ctx.getString(R.string.pdf_core_fmt, cl.core) else ctx.getString(R.string.dmx_line)
            rows.add(coreHeaderRow(m, left, right, coreLabel, cl))
            if (cl.unknownCount > 0)
                rows.add(noteRow(m, left, right, "⚠ " + ctx.getString(R.string.pdf_unknown_footprint_fmt, cl.unknownCount)))
            if (cl.mixedUniverse)
                rows.add(noteRow(m, left, right, "⚠ " + ctx.getString(R.string.dmx_mixed_universes_fmt, cl.universes.joinToString(", "))))
            if (cl.fixtures.isEmpty())
                rows.add(fixtureLineRow(m, left, right, "— " + ctx.getString(R.string.cabling_no_fixture), C_MUTE))
            else for (u in cl.fixtures) {
                val ch = channelsOf(u)
                val addr = addressOf(u) ?: "—"   // déjà formaté « Univers.Adresse »
                val mode = modeOf(u) ?: "—"
                // Colonnes alignées : ID | TYPE GDTF (+ « Univers.Adresse · mode »
                // grisé) | canaux (à droite). Ambre si empreinte inconnue.
                rows.add(fixtureColumnsRow(
                    m, left, right,
                    fixtureId = labelOf(u) ?: u,
                    gdtfType = specOf(u) ?: "—",
                    detail = "$addr · $mode",
                    value = if (ch == null) ctx.getString(R.string.pdf_unknown_channels) else ctx.getString(R.string.fx_channels_count, ch),
                    unknown = ch == null
                ))
            }
        }
        val total = DmxCablingCalc.distributorTotalChannels(dist, dto.assignments, channelsOf)
        rows.add(totalRow(m, left, right, ctx.getString(R.string.pdf_total_line), ctx.getString(R.string.fx_channels_count, total)))
    }
    return rows
}

// ---------------------------------------------------------------------------
// Fabriques de lignes
// ---------------------------------------------------------------------------

private fun style(size: Float, color: Long, weight: FontWeight = FontWeight.Normal, mono: Boolean = false) =
    TextStyle(
        fontSize = size.sp, color = Color(color), fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else null
    )

/** Mesure avec RETOUR À LA LIGNE borné à la largeur utile (évite les débordements). */
private fun TextMeasurer.wrap(text: String, style: TextStyle, maxW: Float): TextLayoutResult =
    measure(text, style, softWrap = true, constraints = Constraints(maxWidth = maxW.toInt().coerceAtLeast(8)))

private fun separatorRow(left: Float, right: Float): Row =
    Row(12f) { ds, top -> ds.drawLine(Color(0xFFDDDDDD), Offset(left, top + 6f), Offset(right, top + 6f), strokeWidth = 0.8f) }

/** En-tête d'un distributeur : pastille couleur + nom + type. */
private fun distHeaderRow(m: TextMeasurer, left: Float, right: Float, name: String, type: String, colorArgb: Int): Row {
    val nameTl = m.wrap(name, style(13f, C_TITLE, FontWeight.SemiBold), right - left - 18f)
    val typeTl = m.wrap(type, style(9f, C_SUB), right - left - 18f)
    val h = nameTl.size.height + typeTl.size.height + 14f
    return Row(h, orphanGuard = 44f) { ds, top ->
        ds.drawRect(Color(colorArgb), topLeft = Offset(left, top + 3f), size = Size(11f, nameTl.size.height.toFloat()))
        ds.drawText(nameTl, topLeft = Offset(left + 18f, top + 2f))
        ds.drawText(typeTl, topLeft = Offset(left + 18f, top + 2f + nameTl.size.height))
    }
}

private fun phaseHeaderRow(m: TextMeasurer, left: Float, right: Float, text: String): Row {
    val tl = m.wrap(text, style(10f, C_PHASE, FontWeight.Bold), right - left)
    return Row(tl.size.height + 9f, orphanGuard = 26f) { ds, top -> ds.drawText(tl, topLeft = Offset(left + 4f, top + 5f)) }
}

/** En-tête de circuit : « Circuit n · L{phase} » + charge « total / limite W » (ROUGE si surcharge). */
private fun circuitHeaderRow(m: TextMeasurer, left: Float, right: Float, cl: CircuitLoad, limit: Int): Row {
    val over = cl.overloaded
    val leftTl = m.wrap("Circuit ${cl.circuit} · L${cl.phase}", style(11f, if (over) C_RED else C_INK, FontWeight.Medium), (right - left) * 0.55f)
    val rightTl = m.measure("${cl.totalW} / $limit W", style(11f, if (over) C_RED else C_INK, if (over) FontWeight.Bold else FontWeight.Normal, mono = true))
    val h = maxOf(leftTl.size.height, rightTl.size.height) + 8f
    return Row(h, orphanGuard = 22f) { ds, top ->
        if (over) ds.drawRoundRect(Color(0x22C62828), topLeft = Offset(left - 4f, top), size = Size(right - left + 8f, h - 2f), cornerRadius = CornerRadius(3f, 3f))
        ds.drawText(leftTl, topLeft = Offset(left + 8f, top + 4f))
        ds.drawText(rightTl, topLeft = Offset(right - rightTl.size.width, top + 4f))
    }
}

/** En-tête de cœur DMX : « Cœur k » / « Ligne » + empreinte « total / 512 » (ROUGE si > 512). */
private fun coreHeaderRow(m: TextMeasurer, left: Float, right: Float, coreLabel: String, cl: DmxCoreLoad): Row {
    val over = cl.over512
    val leftTl = m.wrap(coreLabel, style(11f, if (over) C_RED else C_INK, FontWeight.Medium), (right - left) * 0.55f)
    val rightTl = m.measure("${cl.totalChannels} / ${DmxCablingCalc.UNIVERSE_CHANNELS}", style(11f, if (over) C_RED else C_INK, if (over) FontWeight.Bold else FontWeight.Normal, mono = true))
    val h = maxOf(leftTl.size.height, rightTl.size.height) + 8f
    return Row(h, orphanGuard = 22f) { ds, top ->
        if (over) ds.drawRoundRect(Color(0x22C62828), topLeft = Offset(left - 4f, top), size = Size(right - left + 8f, h - 2f), cornerRadius = CornerRadius(3f, 3f))
        ds.drawText(leftTl, topLeft = Offset(left + 8f, top + 4f))
        ds.drawText(rightTl, topLeft = Offset(right - rightTl.size.width, top + 4f))
    }
}

private fun fixtureLineRow(m: TextMeasurer, left: Float, right: Float, text: String, color: Long): Row {
    val tl = m.wrap(text, style(10f, color), right - left - 24f)
    return Row(tl.size.height + 5f) { ds, top -> ds.drawText(tl, topLeft = Offset(left + 20f, top + 2f)) }
}

/**
 * Ligne projecteur en COLONNES ALIGNÉES (point 5 de l'harmonisation, modèle iOS) :
 *   GAUCHE = Fixture ID · MILIEU = type GDTF (+ détail DMX « Univers.Adresse · mode »
 *   grisé) · DROITE = valeur (W élec / canaux DMX), alignée à DROITE.
 * `unknown` teinte la VALEUR en ambre (conso/empreinte inconnue). La surcharge, elle,
 * rougit l'en-tête de circuit/cœur (une ligne projecteur isolée n'est jamais « en
 * surcharge », la surcharge étant une propriété du circuit/cœur).
 */
private fun fixtureColumnsRow(
    m: TextMeasurer, left: Float, right: Float,
    fixtureId: String, gdtfType: String, detail: String?,
    value: String, unknown: Boolean
): Row {
    val indent = 20f
    val idStart = left + indent
    val idColW = ((right - left) * 0.30f).coerceAtLeast(60f)
    val valueStyle = style(
        10f, if (unknown) C_AMBER else C_INK,
        if (unknown) FontWeight.Medium else FontWeight.Normal, mono = true
    )
    val valueTl = m.measure(value, valueStyle)
    val midStart = idStart + idColW + 6f
    // Colonne du milieu = tout l'espace entre l'ID et la valeur (bornée pour ne
    // jamais mordre sur la valeur alignée à droite).
    val midW = (right - 10f - valueTl.size.width - midStart).coerceAtLeast(24f)
    val idTl = m.wrap(fixtureId, style(10f, C_INK), idColW - 4f)
    val typeTl = m.wrap(gdtfType, style(10f, C_SUB), midW)
    val detailTl = detail?.let { m.wrap(it, style(8.5f, C_MUTE), midW) }
    val midH = typeTl.size.height + (detailTl?.size?.height ?: 0)
    val h = maxOf(idTl.size.height, midH, valueTl.size.height) + 5f
    return Row(h) { ds, top ->
        ds.drawText(idTl, topLeft = Offset(idStart, top + 2f))
        ds.drawText(typeTl, topLeft = Offset(midStart, top + 2f))
        detailTl?.let { ds.drawText(it, topLeft = Offset(midStart, top + 2f + typeTl.size.height)) }
        ds.drawText(valueTl, topLeft = Offset(right - valueTl.size.width, top + 2f))
    }
}

private fun noteRow(m: TextMeasurer, left: Float, right: Float, text: String): Row {
    val tl = m.wrap(text, style(9f, C_AMBER, FontWeight.Medium), right - left - 24f)
    return Row(tl.size.height + 4f) { ds, top -> ds.drawText(tl, topLeft = Offset(left + 20f, top + 2f)) }
}

/** Pied du distributeur élec : totaux L1/L2/L3 + marqueur de déséquilibre. */
private fun phaseBalanceRow(ctx: android.content.Context, m: TextMeasurer, left: Float, right: Float, phaseLoads: List<PhaseLoad>, usedPhases: Set<Int>): Row {
    val used = phaseLoads.filter { it.phase in usedPhases }
    val parts = used.joinToString("   ·   ") { "L${it.phase} ${it.totalW} W" }
    val totals = used.map { it.totalW }
    // DÉCISION PARTAGÉE : le seuil de déséquilibre vit dans le calculateur PUR
    // (PowerCablingCalc.isPhaseImbalanced), plus jamais codé ici. Δ n'est calculé
    // que pour l'AFFICHAGE.
    val delta = (totals.maxOrNull() ?: 0) - (totals.minOrNull() ?: 0)
    val imbalance = PowerCablingCalc.isPhaseImbalanced(totals)
    val txt = ctx.getString(R.string.pdf_balance_fmt, parts) + if (imbalance) "    ⚠ " + ctx.getString(R.string.pdf_imbalance_fmt, delta) else ""
    val tl = m.wrap(txt, style(10f, if (imbalance) C_AMBER else 0xFF444444, FontWeight.Medium), right - left - 8f)
    return Row(tl.size.height + 9f) { ds, top -> ds.drawText(tl, topLeft = Offset(left + 4f, top + 5f)) }
}

private fun totalRow(m: TextMeasurer, left: Float, right: Float, label: String, value: String): Row {
    val labelTl = m.measure(label, style(11f, C_TITLE, FontWeight.SemiBold))
    val valueTl = m.measure(value, style(11f, C_TITLE, FontWeight.Bold, mono = true))
    val h = maxOf(labelTl.size.height, valueTl.size.height) + 10f
    return Row(h) { ds, top ->
        ds.drawText(labelTl, topLeft = Offset(left + 4f, top + 5f))
        ds.drawText(valueTl, topLeft = Offset(right - valueTl.size.width, top + 5f))
    }
}
