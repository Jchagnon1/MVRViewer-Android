package com.minou.mvrviewer.ui

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Copie LOCALE d'un projet partagé téléchargé (SPEC-partage-moderation §5.12, §9) —
 * comme iOS (`CloudDownloads/`) : le .mvr reçu est écrit dans `filesDir/CloudDownloads/`
 * et ajouté aux « Projets récents ». Un membre qui quitte le projet, en est retiré, ou
 * dont le projet est supprimé GARDE ce fichier et le rouvre comme un fichier local (sans
 * mise à jour) : rien n'est effacé à distance — ce serait impossible à garantir (copie
 * exportée, capture) et donnerait une fausse impression de sécurité. Une nouvelle version
 * rouverte remplace la copie de même nom.
 */
object CloudDownloads {
    const val DIR = "CloudDownloads"

    /**
     * Nom du fichier local d'un projet nommé [projectName] (calcul pur) : séparateurs de
     * chemin et caractères de contrôle remplacés (puis points, tirets et blancs ôtés aux
     * bords : jamais de nom caché ni « .. »), « Projet » s'il ne reste rien, toujours
     * terminé par « .mvr » (sans doubler une extension déjà présente), 120 caractères au
     * plus avant l'extension.
     */
    fun fileName(projectName: String): String {
        var base = projectName.map { c -> if (c == '/' || c == '\\' || c == ':' || c.isISOControl()) '-' else c }
            .joinToString("").trim { it == '.' || it == '-' || it.isWhitespace() }
        if (base.endsWith(".mvr", ignoreCase = true)) base = base.dropLast(4).trimEnd()
        if (base.isEmpty()) base = "Projet"
        if (base.length > 120) base = base.take(120).trimEnd()
        return "$base.mvr"
    }

    /** Écrit la copie (atomique : fichier temporaire puis renommage) ; null si l'écriture échoue. */
    fun save(ctx: Context, bytes: ByteArray, projectName: String): File? = runCatching {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val target = File(dir, fileName(projectName))
        val tmp = File(dir, target.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "renommage impossible" }
        }
        target
    }.getOrNull()

    /**
     * Copie écrite ET inscrite aux « Projets récents » (rouverte ensuite comme un fichier
     * local, par son URI `file://` interne à l'app). Rend le nom affiché du fichier, null
     * si l'écriture a échoué (le projet s'ouvre quand même, depuis la mémoire).
     */
    fun keep(ctx: Context, bytes: ByteArray, projectName: String): String? {
        val f = save(ctx, bytes, projectName) ?: return null
        RecentProjects.record(ctx, Uri.fromFile(f).toString(), f.name, System.currentTimeMillis())
        return f.name
    }
}
