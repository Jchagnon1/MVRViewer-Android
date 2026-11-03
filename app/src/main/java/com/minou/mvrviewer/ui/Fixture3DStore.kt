package com.minou.mvrviewer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.minou.mvrviewer.mvr.Fixture3DModel
import com.minou.mvrviewer.sync.ContentHash
import com.minou.mvrviewer.sync.CustomFixtureModel3D
import com.minou.mvrviewer.sync.CustomFixtureTypeDTO
import java.io.File

/**
 * CACHES 3D des types de projecteurs MA (SPEC-ma.md §4.2-4.5), par APPAREIL :
 *  - `filesDir/customFixtures/blobs/<sha256>`        : `.gdtf` importés (c) et objets 3D (d),
 *    adressés par le SHA-256 de leurs octets EXACTS (même clé que le blob Storage
 *    `projects/{id}/blobs/{sha256}` du projet partagé) ;
 *  - `filesDir/customFixtures/gdtfShare/<rid>.gdtf`  : révisions GDTF Share (b) — jamais
 *    republiées dans le Storage du projet (chaque membre télécharge avec SON compte).
 *
 * Toujours rempli, projet partagé ou non ; pas de purge automatique en v1 (un type de la
 * bibliothèque peut en dépendre).
 *
 * CLÉS DE RENDU LOCALES (`type3d:rid:<rid>` / `type3d:sha:<sha>`) : les octets d'un GDTF
 * (b)(c) sont inscrits sous cette clé dans [GdtfOverrides.map] AVANT la construction 3D,
 * et le projecteur est rendu EXACTEMENT comme un GDTF de show. Jamais sérialisées (ni
 * patch, ni gdtfMappings, ni overrides persistés du projet — cf. [GdtfOverrides.persistable]).
 */
class Fixture3DStore(private val root: File) {

    /** Bumpé à chaque fichier ajouté : recomposition des statuts « 3D indisponible ». */
    var version by mutableIntStateOf(0)
        private set

    /**
     * Raison du dernier échec par SOURCE (clé de rendu, ou `sha:<sha>` d'un objet 3D) —
     * état CALCULÉ par appareil, jamais synchronisé (§4.5).
     */
    val issues: SnapshotStateMap<String, Fixture3DReason> = mutableStateMapOf()

    private fun blobsDir() = File(root, "blobs")
    private fun shareDir() = File(root, "gdtfShare")

    fun blobFile(sha256: String) = File(blobsDir(), sha256.lowercase())
    fun shareFile(rid: Int) = File(shareDir(), "$rid.gdtf")

    fun hasBlob(sha256: String): Boolean = blobFile(sha256).isFile
    fun hasShare(rid: Int): Boolean = shareFile(rid).isFile

    fun readBlob(sha256: String): ByteArray? = runCatching { blobFile(sha256).readBytes() }.getOrNull()
    fun readShare(rid: Int): ByteArray? = runCatching { shareFile(rid).readBytes() }.getOrNull()

    /** Range des octets au cache (écriture atomique) ; renvoie leur SHA-256. */
    fun putBlob(bytes: ByteArray): String {
        val sha = ContentHash.sha256Hex(bytes)
        writeAtomic(blobFile(sha), bytes)
        issues.remove("sha:$sha")
        issues.remove(renderKeyForSha(sha))
        version++
        return sha
    }

    /**
     * Range un blob TÉLÉCHARGÉ seulement si son SHA-256 est celui ATTENDU (§4.3) ; sinon
     * rien n'est écrit et la raison « Fichier 3D corrompu. » est retenue.
     */
    fun putVerifiedBlob(expectedSha: String, bytes: ByteArray): Boolean {
        val sha = ContentHash.sha256Hex(bytes)
        if (sha != expectedSha.lowercase()) {
            markIssue(expectedSha, Fixture3DReason.CORRUPTED)
            return false
        }
        putBlob(bytes)
        return true
    }

    fun putShare(rid: Int, bytes: ByteArray) {
        writeAtomic(shareFile(rid), bytes)
        issues.remove(renderKeyForRid(rid))
        version++
    }

    /** Retient la raison d'un échec pour la source portant ce SHA (GDTF ou objet). */
    fun markIssue(sha256: String, reason: Fixture3DReason) {
        val s = sha256.lowercase()
        issues["sha:$s"] = reason
        issues[renderKeyForSha(s)] = reason
    }

    fun markShareIssue(rid: Int, reason: Fixture3DReason) { issues[renderKeyForRid(rid)] = reason }

    /**
     * Connexion GDTF Share RÉUSSIE (ou retrouvée) : les raisons « Connectez-vous… » des
     * révisions deviennent fausses — on les oublie (le téléchargement va être retenté).
     */
    fun clearShareSignInIssues() {
        val stale = issues.filter { (k, v) -> k.startsWith("${RENDER_KEY_PREFIX}rid:") && v == Fixture3DReason.SIGN_IN_SHARE }.keys
        for (k in stale) issues.remove(k)
    }

    /**
     * Objets 3D (d) en cache mais que CET appareil n'a pas su décoder / instancier (PLY
     * big-endian, FBX refusé par le parseur, glTF que Filament n'instancie pas…), par
     * `sha|format` → raison. Mémoire de session (on ne redécode pas à chaque reconstruction) ;
     * un tel objet n'est PAS « disponible » (§4.6) : le projecteur retombe sur la règle
     * actuelle (géométrie propre, base, GDTF d'origine) avec le statut §4.5.
     */
    private val undecodable = mutableStateMapOf<String, Fixture3DReason>()

    private fun decodeKey(m: CustomFixtureModel3D.ModelBlob) = "${m.sha256.lowercase()}|${m.format.trim().lowercase()}"

    /** Retient l'échec de décodage / d'instanciation d'un objet 3D (cf. [undecodable]). */
    fun markUndecodable(m: CustomFixtureModel3D.ModelBlob, reason: Fixture3DReason) {
        undecodable[decodeKey(m)] = reason
        issues["sha:${m.sha256.lowercase()}"] = reason
        version++
    }

    fun isUndecodable(m: CustomFixtureModel3D.ModelBlob): Boolean = undecodable.containsKey(decodeKey(m))

    private fun writeAtomic(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Octets GDTF d'une source (b)(c) s'ils sont en cache, sinon null. */
    fun gdtfBytes(m: CustomFixtureModel3D): ByteArray? = when (m) {
        is CustomFixtureModel3D.GdtfShare -> readShare(m.rid)
        is CustomFixtureModel3D.GdtfBlob -> readBlob(m.sha256)
        is CustomFixtureModel3D.ModelBlob -> null
    }

    /**
     * Les OCTETS de la source sont-ils au cache (format lu ici pour un objet 3D) ? Décide
     * s'il faut TÉLÉCHARGER — pas s'il faut afficher (voir [isAvailable]).
     */
    fun hasBytes(m: CustomFixtureModel3D): Boolean = when (m) {
        is CustomFixtureModel3D.GdtfShare -> hasShare(m.rid)
        is CustomFixtureModel3D.GdtfBlob -> hasBlob(m.sha256)
        is CustomFixtureModel3D.ModelBlob -> Fixture3DModel.isReadableHere(m.format) && hasBlob(m.sha256)
    }

    /**
     * La source est-elle DISPONIBLE ici (§4.6 « octets présents, décodables, format lu ») ?
     * Un objet 3D en cache dont le décodage a échoué sur cet appareil ne l'est pas.
     */
    fun isAvailable(m: CustomFixtureModel3D): Boolean =
        hasBytes(m) && !(m is CustomFixtureModel3D.ModelBlob && isUndecodable(m))

    /**
     * Statut « 3D indisponible » d'un type sur CET appareil (§4.5), ou null si la 3D
     * choisie est disponible (ou si le type n'a pas de `model3D`).
     * @param hasOrigin un GDTF de repli existe (base non vide ou GDTF d'origine du projecteur).
     * @param shareSignedIn compte GDTF Share réellement CONNECTÉ.
     * @param shareCredentialsStored identifiants enregistrés (reconnexion silencieuse possible).
     */
    fun unavailability(
        t: CustomFixtureTypeDTO, hasOrigin: Boolean, shareSignedIn: Boolean,
        shareCredentialsStored: Boolean = false
    ): Fixture3DUnavailable? {
        val m = t.model3D ?: return null
        if (isAvailable(m)) return null
        val reason: Fixture3DReason? = when (m) {
            is CustomFixtureModel3D.GdtfShare -> {
                val issue = issues[renderKeyForRid(m.rid)]
                when {
                    // « Connectez-vous… » PÉRIMÉ : connecté depuis → téléchargement en cours.
                    issue == Fixture3DReason.SIGN_IN_SHARE -> if (shareSignedIn) null else issue
                    issue != null -> issue
                    // Pas encore tenté : en attente si connecté / reconnectable.
                    shareSignedIn || shareCredentialsStored -> null
                    else -> Fixture3DReason.SIGN_IN_SHARE
                }
            }
            is CustomFixtureModel3D.GdtfBlob -> issues["sha:${m.sha256}"] ?: Fixture3DReason.MISSING_IN_PROJECT
            is CustomFixtureModel3D.ModelBlob ->
                if (!Fixture3DModel.isReadableHere(m.format)) Fixture3DReason.FORMAT_UNSUPPORTED
                else undecodable[decodeKey(m)] ?: issues["sha:${m.sha256}"] ?: Fixture3DReason.MISSING_IN_PROJECT
        }
        val label = (m as? CustomFixtureModel3D.ModelBlob)?.let { Fixture3DModel.formatLabel(it.format) }
        return Fixture3DUnavailable(originShown = hasOrigin || t.baseGdtfSpec.isNotBlank(), reason = reason, formatLabel = label)
    }

    companion object {
        const val RENDER_KEY_PREFIX = "type3d:"

        fun renderKeyForRid(rid: Int) = "${RENDER_KEY_PREFIX}rid:$rid"
        fun renderKeyForSha(sha: String) = "${RENDER_KEY_PREFIX}sha:${sha.lowercase()}"

        /**
         * Raison §4.5 d'un objet 3D en cache (SHA vérifié, donc intact) que le lecteur de
         * CET appareil refuse : structure illisible → « Fichier 3D corrompu. » ; sinon
         * (variante non prise en charge, aucune géométrie lue, trop lourd ici…) → « Format
         * non lu sur cet appareil (…). ».
         */
        fun reasonForDecodeFailure(r: Fixture3DModel.DecodeResult): Fixture3DReason = when (r) {
            Fixture3DModel.DecodeResult.Unreadable -> Fixture3DReason.CORRUPTED
            else -> Fixture3DReason.FORMAT_UNSUPPORTED
        }

        /** Clé de rendu d'une source GDTF (b)(c) ; null pour un objet 3D (d). */
        fun renderKey(m: CustomFixtureModel3D?): String? = when (m) {
            is CustomFixtureModel3D.GdtfShare -> renderKeyForRid(m.rid)
            is CustomFixtureModel3D.GdtfBlob -> renderKeyForSha(m.sha256)
            else -> null
        }

        @Volatile private var shared: Fixture3DStore? = null

        /** Instance de l'appareil (`filesDir/customFixtures`). */
        fun of(ctx: Context): Fixture3DStore =
            shared ?: synchronized(this) {
                shared ?: Fixture3DStore(File(ctx.applicationContext.filesDir, "customFixtures")).also { shared = it }
            }
    }
}

/** Raisons affichées sous « 3D indisponible » (§4.5). */
enum class Fixture3DReason { SIGN_IN_SHARE, FORMAT_UNSUPPORTED, MISSING_IN_PROJECT, DOWNLOAD_FAILED, CORRUPTED }

/** Statut d'indisponibilité : repli affiché + raison (null = téléchargement en cours / inconnu). */
data class Fixture3DUnavailable(val originShown: Boolean, val reason: Fixture3DReason?, val formatLabel: String?)
