package com.minou.mvrviewer.ui

import com.minou.mvrviewer.mvr.DmxAddress
import com.minou.mvrviewer.mvr.DmxMode
import com.minou.mvrviewer.mvr.GdtfModes
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RÉSOLUTION PARTAGÉE DES EMPREINTES DMX (phase 5).
 *
 * L'empreinte (nombre de canaux) d'un projecteur, son univers de patch et son
 * adresse sont résolus en parsant les modes GDTF — une opération HORS thread
 * principal. Ce bloc n'existait que dans le corps composable de `DmxCablingPanel`
 * (via `produceState`) ; il est ici extrait en une fonction suspend UNIQUE, que
 * consomment À LA FOIS l'onglet DMX à l'écran ET l'export PDF de câblage → une
 * seule source de vérité pour l'empreinte, l'univers et l'adresse.
 */

/**
 * Un projecteur affectable au DMX + son empreinte (canaux du mode) et son univers
 * de patch — servant la liste, le sélecteur, le calcul d'empreinte cumulée ET
 * l'export PDF. `channels`/`universe` null = inconnu (compté 0, marqué « ? »).
 */
internal data class DmxCableFixture(
    val uuid: String,
    val label: String,      // « #12 · Mac Aura »
    val spec: String?,
    val mode: String?,
    val channels: Int?,     // empreinte DMX (footprint du mode GDTF) — null = inconnue
    val universe: Int?,     // univers de patch — null = non patché
    val address: String?    // adresse formatée « u.a » (affichage)
)

/** Base légère (labels + mode + patch) avant résolution des empreintes GDTF. */
private data class DmxBaseFx(
    val uuid: String, val label: String, val spec: String?, val mode: String?,
    val universe: Int?, val address: String?
)

/**
 * Résout l'empreinte DMX, l'univers et l'adresse de CHAQUE projecteur (hors
 * thread principal). Pour chaque spec distincte, les octets .gdtf sont pris dans
 * `gdtfOverrides` sinon extraits du .mvr, puis `GdtfModes.parse` en donne les
 * modes ; le mode effectif du projecteur (`overrides.effectiveMode`) fixe
 * l'empreinte (`maxOf(1, footprint)`), null si le GDTF est introuvable.
 */
internal suspend fun resolveDmxFootprints(
    scene: MvrScene,
    mvrBytes: ByteArray,
    overrides: PatchOverrides,
    gdtfOverrides: GdtfOverrides
): List<DmxCableFixture> = withContext(Dispatchers.Default) {
    // Base : labels, mode effectif, patch (univers.adresse). Lectures d'état
    // snapshot sûres hors-main (le système de snapshot autorise les lectures
    // concurrentes) ; on fige ici en objets simples.
    val base = scene.fixtures.mapNotNull { f ->
        val uuid = f.uuid ?: return@mapNotNull null
        val id = overrides.effectiveId(f)?.trim()?.ifBlank { null }
        val label = (id?.let { "#$it · " } ?: "") + overrides.effectiveName(f)
        val rawAddr = overrides.effectiveAddress(f)
        val ua = rawAddr?.let { parseUniverseAddress(it) }
        DmxBaseFx(uuid, label, f.gdtfSpec?.trim(), overrides.effectiveMode(f),
            ua?.first, ua?.let { "${it.first}.${it.second}" })
    }
    // Parse d'un .gdtf par spec distincte (comme la vue Univers DMX / l'onglet DMX).
    val gmap = gdtfOverrides.map.toMap()
    val modesBySpec = HashMap<String, List<DmxMode>>()
    for (spec in base.mapNotNull { it.spec }.filter { it.isNotEmpty() }.toSet()) {
        var data = gmap[spec]
        if (data == null) {
            val cands = if (spec.endsWith(".gdtf", true)) listOf(spec) else listOf("$spec.gdtf", spec)
            data = cands.firstNotNullOfOrNull { MvrParser.extractEntry(mvrBytes, it) }
        }
        if (data != null) modesBySpec[spec] = runCatching { GdtfModes.parse(data) }.getOrDefault(emptyList())
    }
    base.map { b ->
        val modes = b.spec?.let { modesBySpec[it] }
        val channels = if (!modes.isNullOrEmpty()) {
            val mode = modes.firstOrNull { it.name == b.mode } ?: modes.first()
            maxOf(1, mode.footprint)
        } else null
        DmxCableFixture(b.uuid, b.label, b.spec, b.mode, channels, b.universe, b.address)
    }
}

/** Adresse DMX brute → (univers, canal) via le formateur commun de l'app. */
internal fun parseUniverseAddress(raw: String): Pair<Int, Int>? {
    val parts = DmxAddress.format(raw).split(".")
    if (parts.size != 2) return null
    val u = parts[0].toIntOrNull() ?: return null
    val a = parts[1].toIntOrNull() ?: return null
    if (u < 1 || a < 1) return null
    return u to a
}
