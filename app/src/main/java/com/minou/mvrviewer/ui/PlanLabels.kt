package com.minou.mvrviewer.ui

import androidx.compose.ui.geometry.Offset

/**
 * MODÈLE D'ÉTIQUETTE DE LA VUE PLAN — « n blocs par projecteur ».
 *
 * POURQUOI ce fichier : l'étiquette était UN texte (un seul champ au choix) collé
 * à côté du symbole. Le terrain demande deux choses incompatibles avec ça :
 *  - afficher PLUSIEURS champs (n° + patch) sans faire une bande large qui
 *    chevauche les voisines → un bloc peut porter plusieurs LIGNES ;
 *  - pouvoir DÉTACHER un champ (le n° au-dessus de l'icône, le patch en dessous)
 *    → un projecteur peut porter plusieurs BLOCS autonomes, chacun avec son
 *    propre placement et son propre décalage déplaçable au doigt.
 *
 * Tout le reste de la logique déjà éprouvée (activation au tap, glissé, contraste
 * de l'encre, cadre fin, zones sensibles remplies par le dessin) est conservé :
 * elle porte simplement sur une CLÉ DE BLOC au lieu d'une clé de projecteur.
 */

/**
 * Séparateur clé d'instance ↔ identifiant de bloc. Caractère de contrôle : il ne
 * peut pas apparaître dans un nom d'objet MVR, donc `labelBlockFixtureKey` sait
 * toujours retrouver le projecteur, quelle que soit la clé d'instance.
 */
internal const val LABEL_BLOCK_SEP = '\u0001'

/** Identifiant du bloc COMMUN (les champs restés groupés dans une pastille). */
internal const val LABEL_GROUP_BLOCK = "g"

/** Clé d'un bloc : identité du projecteur + identité du bloc. */
internal fun labelBlockKey(fixtureKey: String, blockId: String): String =
    fixtureKey + LABEL_BLOCK_SEP + blockId

/** Projecteur portant ce bloc (tolère les clés héritées, sans séparateur). */
internal fun labelBlockFixtureKey(blockKey: String): String =
    blockKey.substringBefore(LABEL_BLOCK_SEP)

/** Identité du bloc seule (« g », ou le nom du champ détaché). */
internal fun labelBlockId(blockKey: String): String {
    val i = blockKey.indexOf(LABEL_BLOCK_SEP)
    return if (i < 0) LABEL_GROUP_BLOCK else blockKey.substring(i + 1)
}

/**
 * Décalages enregistrés AVANT les blocs : ils étaient indexés par la seule clé
 * d'instance. Les reprendre tels quels les rendrait orphelins (plus aucun bloc
 * ne porte cette clé) et l'utilisateur verrait toutes ses étiquettes revenir à
 * leur place d'origine. On les rattache donc au bloc groupé, qui est bien la
 * pastille qu'il avait déplacée.
 */
internal fun migrateLegacyLabelKey(key: String): String =
    if (key.indexOf(LABEL_BLOCK_SEP) >= 0) key else labelBlockKey(key, LABEL_GROUP_BLOCK)

/** Un bloc à dessiner : son identité et ses lignes de texte (jamais vide). */
internal class LabelBlock(val id: String, val lines: List<String>) {
    /** Texte mis en page tel quel : le saut de ligne est le gain de largeur. */
    val text: String get() = lines.joinToString("\n")
}

/** Texte d'un champ pour un projecteur, ou null s'il n'a rien à montrer. */
internal fun labelFieldText(f: PlanFixture, c: LabelContent): String? = when (c) {
    LabelContent.ID -> f.id?.takeIf { it.isNotBlank() }?.let { "#$it" }
    LabelContent.DMX -> f.addr.ifEmpty { null }?.let { com.minou.mvrviewer.mvr.DmxAddress.format(it) }
    LabelContent.MODE -> f.mode?.takeIf { it.isNotBlank() }
    LabelContent.NAME -> f.name.takeIf { it.isNotBlank() }
}

/**
 * Blocs d'un projecteur, dans l'ordre de dessin.
 *
 * Le bloc GROUPÉ vient toujours en premier (il prend la place historique de
 * l'étiquette, à droite au-dessus du symbole) ; les champs DÉTACHÉS suivent dans
 * l'ordre déclaré de l'énumération, empilés sous le symbole. Un champ sans
 * contenu ne produit rien — pas de pastille vide, donc pas de zone sensible
 * fantôme.
 */
internal fun labelBlocks(
    f: PlanFixture, fields: Set<LabelContent>, detached: Set<LabelContent>
): List<LabelBlock> {
    if (fields.isEmpty()) return emptyList()
    val out = ArrayList<LabelBlock>(3)
    val grouped = ArrayList<String>(2)
    val loose = ArrayList<LabelBlock>(2)
    for (c in LabelContent.entries) {
        if (c !in fields) continue
        val t = labelFieldText(f, c) ?: continue
        if (c in detached) loose.add(LabelBlock(c.name, listOf(t))) else grouped.add(t)
    }
    if (grouped.isNotEmpty()) out.add(LabelBlock(LABEL_GROUP_BLOCK, grouped))
    out.addAll(loose)
    return out
}

/**
 * Blocs à armer quand le doigt tape la pastille `hitKey`.
 *
 * Règle : si le projecteur touché appartient à une MULTI-SÉLECTION, on arme le
 * même bloc sur tous les projecteurs sélectionnés — c'est l'entrée « je
 * sélectionne un pont, puis je bouge toutes ses étiquettes d'un coup » demandée
 * par le terrain, sans nouveau geste à apprendre. Sinon, un seul bloc.
 */
internal fun labelGroupForTap(
    data: PlanData, selected: Collection<Int>, hitKey: String
): Set<String> {
    if (selected.size < 2) return setOf(hitKey)
    val fixKey = labelBlockFixtureKey(hitKey)
    val blockId = labelBlockId(hitKey)
    val keys = HashSet<String>(selected.size * 2)
    var touchedIsSelected = false
    for (i in selected) {
        val f = data.fixtures.getOrNull(i) ?: continue
        if (f.key == fixKey) touchedIsSelected = true
        keys.add(labelBlockKey(f.key, blockId))
    }
    // Si le projecteur touché n'est PAS dans la sélection, l'utilisateur vise
    // manifestement autre chose : on ne s'empare pas de sa sélection.
    return if (touchedIsSelected && keys.isNotEmpty()) keys else setOf(hitKey)
}

/** Tous les blocs (groupé + détachés) des projecteurs donnés. */
internal fun labelKeysForFixtures(
    fixtures: List<PlanFixture>, fields: Set<LabelContent>, detached: Set<LabelContent>
): Set<String> {
    val keys = HashSet<String>(fixtures.size * 2)
    for (f in fixtures) {
        for (b in labelBlocks(f, fields, detached)) keys.add(labelBlockKey(f.key, b.id))
    }
    return keys
}

/**
 * Projecteurs de MÊME TYPE GDTF sur le MÊME CALQUE qu'un projecteur donné —
 * c'est-à-dire, dans un plan réel, la famille d'appareils d'un même pont. Le
 * calque fait partie du critère : sans lui, activer « les Sharpy » armerait les
 * étiquettes de tout le show, y compris celles de l'autre bout de la salle.
 */
internal fun sameTypeSameLayer(data: PlanData, fixtureKey: String): List<PlanFixture> {
    val origin = data.fixtures.firstOrNull { it.key == fixtureKey } ?: return emptyList()
    val spec = origin.spec?.trim()
    return data.fixtures.filter { it.layer == origin.layer && it.spec?.trim() == spec }
}

/**
 * Déplacement RÉELLEMENT applicable à un groupe de blocs, borné pour que le
 * groupe reste RIGIDE.
 *
 * POURQUOI pas un simple `coerceIn` par bloc : quand plusieurs étiquettes sont
 * déplacées ensemble et qu'une seule atteint la limite d'amplitude, borner
 * chacune de son côté ferait DÉRIVER les autres par rapport à elle — la mise en
 * page que l'utilisateur venait d'aligner se déformerait sous son doigt. On
 * réduit donc le vecteur commun à ce que le bloc le plus contraint autorise.
 */
internal fun clampGroupDelta(
    d: Offset, keys: Collection<String>, shift: Map<String, Offset>, limit: Float
): Offset {
    var loX = -Float.MAX_VALUE; var hiX = Float.MAX_VALUE
    var loY = -Float.MAX_VALUE; var hiY = Float.MAX_VALUE
    for (k in keys) {
        val c = shift[k] ?: Offset.Zero
        loX = maxOf(loX, -limit - c.x); hiX = minOf(hiX, limit - c.x)
        loY = maxOf(loY, -limit - c.y); hiY = minOf(hiY, limit - c.y)
    }
    if (loX > hiX || loY > hiY) return Offset.Zero   // jamais atteint : |c| ≤ limit
    return Offset(d.x.coerceIn(loX, hiX), d.y.coerceIn(loY, hiY))
}
