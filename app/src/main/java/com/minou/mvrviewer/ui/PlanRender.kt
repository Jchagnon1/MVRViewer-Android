package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

/**
 * Tout ce dont le DESSIN du plan a besoin, et rien d'autre.
 *
 * POURQUOI cet objet : le rendu à l'écran et le rendu PDF doivent produire
 * exactement la même image, sinon l'export « en construction » ment à
 * l'utilisateur (il compose une page à partir de ce qu'il voit). Une seule
 * fonction de dessin les sert donc tous les deux, et ce paramètre porte l'état
 * qu'elle lisait auparavant dans les fermetures de `PlanScreen`.
 *
 * La projection est exprimée de façon NEUTRE — « pixels par mm » plus le point
 * d'ancrage du centre du modèle — au lieu de (baseScale, scale, offset, taille
 * de fenêtre) : la page PDF n'a ni la taille ni la densité de l'écran, mais elle
 * peut reproduire le même cadrage.
 */
internal class PlanRenderSpec(
    val data: PlanData,
    val layerIndex: Map<String, Int>,
    /** Échelle de projection : pixels (ou points PDF) par millimètre monde. */
    val pxPerMm: Float,
    /**
     * Échelle servant aux DÉCISIONS DE DÉTAIL (étiquettes assez lisibles ?
     * silhouette assez grande ?), quand elle diffère de l'échelle de projection.
     *
     * POURQUOI : une page A4 en points (≈ 780 × 500) est bien plus petite que
     * l'écran en pixels (≈ 1080 × 2100), donc `pxPerMm` y est plusieurs fois
     * plus faible à cadrage égal. Les seuils appliqués à `pxPerMm` répondaient
     * donc « non » sur la page alors qu'ils répondaient « oui » à l'écran : le
     * PDF sortait SANS étiquettes ni silhouettes, alors que l'utilisateur les
     * voyait au moment où il a ajouté la vue. On décide donc avec l'échelle de
     * l'ÉCRAN au moment de la capture, et on dessine avec celle de la page.
     * null (écran) = les deux sont confondues.
     */
    val detailPxPerMm: Float? = null,
    /** Où atterrit le centre du modèle (data.cx, data.cy) dans le repère de dessin. */
    val centerPx: Offset,
    val planBg: Color,
    val bgDark: Boolean,
    val inkColor: Color,
    val layerColors: Boolean,
    /**
     * MODE DE COLORATION des projecteurs (phase 4 câblage). LAYER = comportement
     * historique (couleur par calque si `layerColors`, sinon gris neutre). SOCAPEX /
     * DMX_LINE colorent par distributeur câblage via [cablingColor].
     */
    val colorMode: PlanColorMode = PlanColorMode.LAYER,
    /**
     * fixtureKey (mvrUUID) → couleur de son distributeur, en mode câblage. Un
     * projecteur ABSENT de la table est NON AFFECTÉ → gris neutre. null = pas de
     * table (mode LAYER). Ne colore QUE la pastille (passe 2) : la silhouette
     * (passe 1) est indexée par TYPE, non colorable par instance → gris neutre.
     */
    val cablingColor: Map<String, Color>? = null,
    /**
     * Résout le texte des champs d'étiquette SOCAPEX / DMX_LINE (état câblage
     * runtime). null hors câblage → ces champs ne produisent aucun bloc.
     */
    val cablingText: ((PlanFixture, LabelContent) -> String?)? = null,
    val showStructure: Boolean,
    val showLabels: Boolean,
    /** Champs affichés (une ligne chacun dans la pastille commune). */
    val labelFields: Set<LabelContent>,
    /** Champs sortis de la pastille commune : un bloc autonome chacun. */
    val labelDetached: Set<LabelContent>,
    val labelSize: Float,
    val labelOffset: Float,
    /**
     * Réglage explicite « masquer les étiquettes quand c'est trop dézoomé » —
     * DÉSACTIVÉ par défaut. Par défaut, une étiquette dont l'affichage est activé
     * reste visible quel que soit le zoom : le seuil de lisibilité (det > 0.02)
     * ne la masque PLUS silencieusement. On ne le rebranche que si l'utilisateur
     * le demande, pour un très gros show où des centaines de pastilles dézoomées
     * feraient une bouillie. (L'allègement pendant les GESTES, lui, reste actif :
     * cf. lowDetail — transitoire, ce n'est pas « disparaître au dézoom ».)
     */
    val hideLabelsWhenZoomedOut: Boolean = false,
    val hiddenElements: Set<String>,
    val hiddenLayers: Set<String>,
    val structPaths: StructPaths?,
    val structDots: androidx.compose.ui.graphics.Path,
    val fallbackDots: androidx.compose.ui.graphics.Path,
    val fixWire: PlanWireframe.FixtureWire?,
    val fixPaths: FixturePaths?,
    /**
     * PLACEMENT du plan de repère au moment du dessin. C'est une COPIE côté PDF
     * (et non l'objet vivant de `ReferencePlan`) : sa transformée est mutable et
     * réglée aux curseurs, si bien qu'un placement modifié après coup — ou un
     * simple « Visible » décoché — réécrivait toutes les pages déjà composées.
     */
    val refTransform: com.minou.mvrviewer.mvr.ReferencePlanTransform?,
    val dxfPaths: DxfPaths?,
    val satellite: com.minou.mvrviewer.mvr.SatelliteOverlay?,
    val showSatellite: Boolean,
    val satelliteOpacity: Float,
    val selected: Set<Int>,
    /**
     * Décalages manuels d'étiquettes, en pixels ÉCRAN (cf. labelShiftScale),
     * indexés par CLÉ DE BLOC (cf. labelBlockKey) — un champ détaché a le sien.
     */
    val labelShift: Map<String, Offset>,
    /**
     * Conversion des décalages d'étiquettes vers le repère de dessin courant :
     * ils ont été posés au doigt en pixels d'écran, la page PDF n'a pas la même
     * densité. 1 à l'écran.
     */
    val labelShiftScale: Float,
    /**
     * Blocs ARMÉS (clés de bloc). Plusieurs à la fois : la sélection groupée
     * arme toute une famille d'étiquettes, qu'un seul glissé déplace ensemble.
     */
    val activeLabelKeys: Set<String>,
    val measurer: TextMeasurer,
    val labelCache: MutableMap<String, TextLayoutResult>,
    /** Geste en cours : décor allégé, étiquettes éteintes (jamais vrai en PDF). */
    val lowDetail: Boolean,
    /** Tampons de zones sensibles remplis par le dessin — null en PDF. */
    val labelHits: MutableList<LabelHit>? = null,
    val symbolHits: MutableList<SymbolHit>? = null,
    /** Coin bas-gauche de la barre d'échelle ; null = pas de barre. */
    val scaleBarAnchor: Offset? = null
) {
    /** Projection monde → repère de dessin (identique à l'ancien `toScreen`). */
    fun toDraw(px: Float, py: Float): Offset =
        Offset(centerPx.x + pxPerMm * (px - data.cx), centerPx.y + pxPerMm * (py - data.cy))
}

/**
 * FONCTION DE DESSIN UNIQUE du plan (fond satellite, plan DXF, décor, silhouettes
 * de projecteurs, pastilles, étiquettes, barre d'échelle).
 *
 * Appelée par le Canvas de `PlanScreen` ET par le rendu PDF : c'est la garantie
 * que la page imprimée reflète l'écran. Tout ce qui est purement TRANSITOIRE
 * (cadre de sélection en cours, position GPS, cote de mesure) reste en dehors —
 * ça n'a pas de sens sur un document.
 */
internal fun DrawScope.drawPlanContent(s: PlanRenderSpec) {
    val w = size.width
    val h = size.height
    val bs = s.pxPerMm
    if (bs <= 0f) return
    // Échelle de DÉCISION (cf. detailPxPerMm) : identique à `bs` à l'écran.
    val det = s.detailPxPerMm?.takeIf { it > 0f } ?: bs

    // ---- Fond satellite géo-référencé, SOUS tout le reste ----
    // L'image porte ses 4 coins en monde MVR ; on la dessine comme un quad via la
    // même projection que le plan → alignée sur les projecteurs.
    val sat = s.satellite
    if (s.showSatellite && sat != null && !s.lowDetail && sat.bitmap.width > 0) {
        val nw = s.toDraw(sat.nwX, -sat.nwY)
        val ne = s.toDraw(sat.neX, -sat.neY)
        val sw = s.toDraw(sat.swX, -sat.swY)
        val iw = sat.bitmap.width.toFloat(); val ih = sat.bitmap.height.toFloat()
        val a = (ne.x - nw.x) / iw; val b = (ne.y - nw.y) / iw
        val c = (sw.x - nw.x) / ih; val d = (sw.y - nw.y) / ih
        val m = android.graphics.Matrix().apply {
            setValues(floatArrayOf(a, c, nw.x, b, d, nw.y, 0f, 0f, 1f))
        }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true; isFilterBitmap = true
            alpha = (s.satelliteOpacity.coerceIn(0f, 1f) * 255f).toInt()
        }
        drawIntoCanvas { it.nativeCanvas.drawBitmap(sat.bitmap, m, paint) }
    }

    // ---- Plan de repère DXF importé (sous tout le reste) ----
    // Les tracés sont déjà construits (coordonnées locales du DXF) : ici on ne
    // fait qu'empiler placement du DXF + projection dans la matrice du Canvas.
    val tfRef = s.refTransform
    val dxf = s.dxfPaths
    if (tfRef != null && dxf != null && tfRef.visible && (!s.lowDetail || dxf.light)) {
        val tf = tfRef
        val sfac = tf.scale.toFloat()
        val ppu = bs * sfac              // pixels par unité locale du DXF
        withTransform({
            translate(s.centerPx.x, s.centerPx.y)
            scale(bs, bs, Offset.Zero)
            translate(-s.data.cx, -s.data.cy)
            // Repère plan = (x, −y) : la rotation du placement s'y lit donc à
            // l'envers, et le décalage Y aussi.
            translate(tf.offsetX.toFloat(), -tf.offsetY.toFloat())
            rotate(-tf.rotationDeg.toFloat(), Offset.Zero)
            scale(sfac, sfac, Offset.Zero)
        }) {
            // Zones remplies (HATCH/SOLID) SOUS les traits, en semi-transparent
            // pour ne pas masquer projecteurs et décor.
            for ((key, fp) in dxf.fills) {
                val (layer, rgb, solid) = key
                if (layer in s.hiddenLayers) continue
                drawPath(fp, dxfDisplayColor(rgb, s.bgDark).copy(alpha = if (solid) 0.40f else 0.20f))
            }
            if (ppu > 0f) {
                val sw = 0.7f / ppu   // épaisseur constante à l'écran / sur la page
                for ((key, path) in dxf.strokes) {
                    val (layer, rgb) = key
                    if (layer in s.hiddenLayers) continue
                    drawPath(path, dxfDisplayColor(rgb, s.bgDark), style = Stroke(sw))
                }
            }
        }
    }

    // ---- Décor / structure : fil de fer vectoriel ----
    if (s.showStructure) {
        val sp = s.structPaths
        withTransform({
            translate(s.centerPx.x, s.centerPx.y)
            scale(bs, bs, Offset.Zero)
            translate(-s.data.cx, -s.data.cy)
        }) {
            val wireOk = sp != null && (!s.lowDetail || sp.light)
            if (wireOk) {
                val sw = 0.8f / bs
                for ((layer, path) in sp.byLayer) {
                    val col = if (s.layerColors) Color(LayerColors.colorInt(s.layerIndex, layer)) else STRUCT_COLOR
                    drawPath(path, col, style = Stroke(sw))
                }
            }
            // Structures sans fil de fer (ou repli pendant un geste) : un point
            // chacune, en UN SEUL tracé (Skia élague hors écran).
            val dots = if (wireOk) s.structDots else s.fallbackDots
            drawPath(dots, STRUCT_COLOR, style = Stroke(width = 3.2f / bs, cap = StrokeCap.Round))
        }
    }

    // ---- Projecteurs, passe 1 : silhouettes fil de fer ----
    // Une étiquette affichée reste visible quel que soit le zoom : les
    // étiquettes ne « disparaissent » PLUS au dézoom (retour de test terrain).
    // Le seuil de lisibilité (det > 0.02) n'est appliqué QUE si l'utilisateur a
    // explicitement coché « masquer les étiquettes quand c'est trop dézoomé »
    // (défaut : décoché) — filet optionnel pour un très gros show. L'allègement
    // pendant les GESTES reste distinct (lowDetail), tout comme les étiquettes
    // ARMÉES / SÉLECTIONNÉES qui ignorent de toute façon ces deux gardes.
    val labelsZoomOk = s.showLabels && (!s.hideLabelsWhenZoomedOut || det > 0.02f)
    val fw = s.fixWire
    val fp = s.fixPaths
    fun silhouetteVisible(spec: String?): Boolean {
        if (s.lowDetail || fw == null || fp == null || spec == null) return false
        val t = spec.trim()
        if (t !in fp.specs || fw.edgesBySpec[t] == null) return false
        return (fw.radiusBySpec[t] ?: 0f) * det > 7f
    }
    if (!s.lowDetail && fp != null) {
        withTransform({
            translate(s.centerPx.x, s.centerPx.y)
            scale(bs, bs, Offset.Zero)
            translate(-s.data.cx, -s.data.cy)
        }) {
            val sw = 1.2f / bs
            for ((key, path) in fp.byKey) {
                val sep = key.indexOf(PATH_KEY_SEP)
                if (sep < 0) continue
                val layer = key.substring(0, sep)
                if (!silhouetteVisible(key.substring(sep + 1))) continue
                // Passe indexée par TYPE (dessinée une fois pour tous les clones) :
                // en mode câblage (par INSTANCE), on ne peut pas la colorer par
                // projecteur → gris neutre. La pastille (passe 2) porte la couleur.
                val c = if (s.colorMode == PlanColorMode.LAYER && s.layerColors)
                    Color(LayerColors.colorInt(s.layerIndex, layer)) else NEUTRAL_FIXTURE_GRAY
                drawPath(path, c, style = Stroke(sw))
            }
        }
    }

    // ---- Passe 2 : pastilles, anneaux de sélection, étiquettes ----
    s.labelHits?.clear()
    s.symbolHits?.clear()
    // Projecteurs portant au moins un bloc armé : calculé UNE fois (coût
    // proportionnel au nombre de blocs armés, pas au nombre de projecteurs).
    val activeFixtures: Set<String> =
        if (s.activeLabelKeys.isEmpty()) emptySet()
        else s.activeLabelKeys.mapTo(HashSet()) { labelBlockFixtureKey(it) }
    // Décalage manuel maximal PAR PROJECTEUR, agrégé une seule fois : le culling
    // en a besoin pour chaque projecteur, et balayer la table des décalages à
    // l'intérieur de la boucle en ferait un produit (projecteurs × décalages).
    val maxShiftByFixture = HashMap<String, Offset>(s.labelShift.size * 2)
    for ((k, v) in s.labelShift) {
        val fk = labelBlockFixtureKey(k)
        val cur = maxShiftByFixture[fk]
        maxShiftByFixture[fk] =
            if (cur == null) Offset(abs(v.x), abs(v.y))
            else Offset(max(cur.x, abs(v.x)), max(cur.y, abs(v.y)))
    }
    val labelPadX = LABEL_PAD_X.toPx()
    val labelPadY = LABEL_PAD_Y.toPx()
    val labelStroke = LABEL_STROKE.toPx()
    val labelCullPx = LABEL_CULL_MARGIN.toPx()
    // Couleur RÉELLE du voile de la pastille une fois composée sur le fond : c'est
    // elle, et non le fond nu, qui décide de la lisibilité de l'encre.
    val labelVeil = if (s.bgDark) compositeOver(Color.Black, 0.45f, s.planBg)
                    else compositeOver(Color.White, 0.78f, s.planBg)
    s.data.fixtures.forEachIndexed { i, f ->
        if (f.key in s.hiddenElements) return@forEachIndexed
        val p = s.toDraw(f.px, f.py)
        // Culling : le décalage manuel LE PLUS GRAND des blocs de ce projecteur
        // (chacun a le sien) — sinon un bloc traîné loin disparaîtrait dès que
        // son projecteur sort du cadre, alors qu'il est encore à l'écran.
        val maxSh = maxShiftByFixture[f.key] ?: Offset.Zero
        val cullX = 40f + abs(maxSh.x) * s.labelShiftScale + labelCullPx
        val cullY = 40f + abs(maxSh.y) * s.labelShiftScale + labelCullPx
        if (p.x !in -cullX..w + cullX || p.y !in -cullY..h + cullY) return@forEachIndexed
        // Couleur de la PASTILLE : en mode câblage, la couleur du distributeur de ce
        // projecteur (non affecté = gris neutre) ; sinon comportement historique
        // (calque si activé, sinon gris neutre). Seul site indexé par INSTANCE.
        val c = if (s.colorMode != PlanColorMode.LAYER)
            s.cablingColor?.get(f.key) ?: CABLING_UNASSIGNED_GRAY   // non affecté = gris câblage dédié
        else if (s.layerColors) Color(LayerColors.colorInt(s.layerIndex, f.layer))
        else NEUTRAL_FIXTURE_GRAY
        val symRadius = if (silhouetteVisible(f.spec)) {
            drawCircle(c, radius = 3f, center = p)
            3f
        } else {
            drawCircle(c, radius = 7f, center = p)
            drawCircle(Color.White, radius = 7f, center = p, style = Stroke(1.5f))
            7f
        }
        s.symbolHits?.add(SymbolHit(p.x, p.y, max(symRadius, 7f)))
        if (i in s.selected) {
            drawCircle(Color(0xFFFFC400), radius = 13f, center = p, style = Stroke(3f))
        }
        // Une étiquette DÉSIGNÉE (bloc armé, ou projecteur sélectionné) est
        // toujours tracée : ni le seuil de zoom ni l'allègement de geste ne la
        // font disparaître (cf. labelsZoomOk).
        val forced = f.key in activeFixtures || i in s.selected
        if (s.showLabels && (labelsZoomOk || forced) && (!s.lowDetail || forced)) {
            val blocks = labelBlocks(f, s.labelFields, s.labelDetached, s.cablingText)
            val fs = (9f * s.labelSize)
            val off = 8f * s.labelOffset
            // Encre dérivée de la même teinte que la pastille : en câblage `c` porte
            // la couleur du distributeur (ou gris neutre), sinon la couleur de calque.
            val tinted = s.colorMode != PlanColorMode.LAYER || s.layerColors
            val labelInk = readableInk(if (tinted) c else s.inkColor, labelVeil)
            // Empilement des blocs DÉTACHÉS sous le symbole : le premier bloc
            // garde la place historique (à droite, au-dessus du centre), les
            // suivants descendent — d'où « le n° en haut, le patch en bas » sans
            // le moindre réglage. L'utilisateur affine ensuite au doigt.
            var stackY = p.y + off * 0.5f
            blocks.forEachIndexed { bi, block ->
                val bkey = labelBlockKey(f.key, block.id)
                val sh = (s.labelShift[bkey] ?: Offset.Zero) * s.labelShiftScale
                val text = block.text
                // Mesurer un texte est cher et le cache par défaut du TextMeasurer
                // ne fait que 8 entrées → on mémorise par texte (sauts de ligne
                // compris). La couleur n'est PAS mise en page ici (elle varie par
                // calque).
                val tl = s.labelCache.getOrPut(text) {
                    s.measurer.measure(text, style = TextStyle(fontSize = fs.sp, color = s.inkColor))
                }
                val bw = tl.size.width + labelPadX * 2f
                val bh = tl.size.height + labelPadY * 2f
                val tx = p.x + off + sh.x
                val ty = (if (bi == 0) p.y - fs * 0.7f else stackY) + sh.y
                if (bi > 0) stackY += bh + labelPadY
                val boxTL = Offset(tx - labelPadX, ty - labelPadY)
                s.labelHits?.add(LabelHit(bkey, boxTL.x, boxTL.y, bw, bh))
                val boxSize = Size(bw, bh)
                val radius = CornerRadius(bh * 0.32f, bh * 0.32f)
                drawRoundRect(
                    if (s.bgDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.78f),
                    topLeft = boxTL, size = boxSize, cornerRadius = radius
                )
                val isActive = bkey in s.activeLabelKeys
                if (isActive) {
                    // Retour visuel de l'étiquette ACTIVE : halo ambré débordant.
                    drawRoundRect(
                        Color(0x55FFC400),
                        topLeft = Offset(boxTL.x - labelStroke * 3f, boxTL.y - labelStroke * 3f),
                        size = Size(bw + labelStroke * 6f, bh + labelStroke * 6f),
                        cornerRadius = radius,
                        style = Stroke(labelStroke * 5f)
                    )
                }
                drawRoundRect(
                    if (isActive) Color(0xFFFFC400) else labelInk.copy(alpha = 0.85f),
                    topLeft = boxTL, size = boxSize, cornerRadius = radius,
                    style = Stroke(if (isActive) labelStroke * 2.5f else labelStroke)
                )
                drawText(tl, color = labelInk, topLeft = Offset(tx, ty))
            }
        }
    }

    // ---- Barre d'échelle : longueur « ronde » à l'échelle courante ----
    val anchor = s.scaleBarAnchor
    if (anchor != null) {
        val barPx = niceScaleBarMm(bs) * bs
        val x0 = anchor.x; val y0 = anchor.y
        drawLine(s.inkColor, Offset(x0, y0), Offset(x0 + barPx, y0), strokeWidth = 2.5f)
        drawLine(s.inkColor, Offset(x0, y0 - 5f), Offset(x0, y0 + 5f), strokeWidth = 2.5f)
        drawLine(s.inkColor, Offset(x0 + barPx, y0 - 5f), Offset(x0 + barPx, y0 + 5f), strokeWidth = 2.5f)
        val tl = s.measurer.measure(
            scaleBarLabel(niceScaleBarMm(bs)),
            style = TextStyle(fontSize = 11.sp, color = s.inkColor)
        )
        drawText(tl, topLeft = Offset(x0, y0 - 22f))
    }
}

/** Longueur « ronde » (mm monde) tenant dans ~90 px à l'échelle donnée. */
internal fun niceScaleBarMm(pxPerMm: Float): Float {
    if (pxPerMm <= 1e-6f) return 1f
    val raw = 90f / pxPerMm
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble()))).toFloat()
    return when {
        raw / mag < 1.5f -> 1f * mag
        raw / mag < 3.5f -> 2f * mag
        raw / mag < 7.5f -> 5f * mag
        else -> 10f * mag
    }
}

internal fun scaleBarLabel(niceMm: Float): String =
    if (niceMm >= 1000f) {
        val m = niceMm / 1000f
        (if (m % 1f == 0f) m.toInt().toString() else m.toString()) + " m"
    } else "${niceMm.toInt()} mm"
