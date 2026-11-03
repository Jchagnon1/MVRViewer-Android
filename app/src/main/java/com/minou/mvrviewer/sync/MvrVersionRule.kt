package com.minou.mvrviewer.sync

/**
 * Bannière « nouvelle version du fichier » ([SyncViewModel.pendingMvrVersion]) — hors
 * ViewModel, testable en JVM. MÊME règle qu'iOS (`SyncCoordinator.advanceMVRVersion`) et
 * que le site web.
 *
 * Une version du .mvr plus récente que celle du projet courant est connue (publiée ici ou
 * reçue) : le projet la suit DÈS l'annonce — Firebase renvoie la version à chaque
 * instantané du document projet (un membre qui rejoint…), elle ne revient donc pas après
 * « Plus tard ». La bannière ne l'annonce que si c'est un AUTRE fichier que celui ouvert ;
 * sinon (notre propre publication, dont l'événement peut arriver avant que
 * `publishMVRVersion` rende la main, ou le même fichier republié) il n'y a rien à
 * rouvrir, et une annonce plus ancienne est caduque. Pas de filtre sur l'auteur : Firebase
 * rapporte `ownerUid`, pas la personne qui publie, et un même compte peut publier depuis
 * un autre appareil.
 */
object MvrVersionRule {

    class Result(val project: CloudProject, val notice: SyncViewModel.MvrVersionNotice?)

    /**
     * `openedSha` : empreinte du .mvr ouvert, demandée seulement pour une version plus
     * récente. Rend null quand rien ne change (pas de projet, version déjà connue).
     */
    fun advance(
        project: CloudProject?,
        pending: SyncViewModel.MvrVersionNotice?,
        version: Int,
        sha256: String,
        updatedBy: String,
        openedSha: () -> String?
    ): Result? {
        if (project == null || version <= project.mvrVersion) return null
        val next = project.copy(mvrVersion = version, mvrBlobSHA = sha256)
        val notice = when {
            sha256 != openedSha() -> SyncViewModel.MvrVersionNotice(project.id, version, sha256, updatedBy)
            pending?.projectId == project.id -> null
            else -> pending
        }
        return Result(next, notice)
    }
}
