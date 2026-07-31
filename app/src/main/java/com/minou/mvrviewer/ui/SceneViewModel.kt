package com.minou.mvrviewer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Charge un .mvr choisi par l'utilisateur : lit les octets et parse HORS du
 * thread principal (un gros show pèse), puis publie la scène. Équivalent
 * simplifié de RootView.open(url:) côté iOS.
 */
class SceneViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface UiState {
        data object Home : UiState
        data class Loading(val fileName: String) : UiState
        // `bytes` = octets bruts du .mvr, conservés pour extraire la géométrie
        // (.3ds/.glb) à la demande lors du rendu 3D.
        data class Loaded(val scene: MvrScene, val fileName: String, val bytes: ByteArray) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = mutableStateOf<UiState>(UiState.Home)
    val state: State<UiState> = _state

    /**
     * Avancement NOMMÉ du chargement (lot A #4), partagé par l'accueil (lecture
     * + parse) et la vue 3D (construction de la scène). Porté par le ViewModel :
     * il survit aux recréations d'activité comme le reste du chargement.
     */
    val progress = LoadProgress()

    // Fichier en cours (chargé ou en chargement) : rend `open` IDEMPOTENT.
    // Le ViewModel survit aux recréations d'activité (rotation, mode sombre,
    // retour depuis le multitâche) → si la même URI est re-livrée, on NE
    // recharge PAS. Le job tourne dans `viewModelScope` → le chargement CONTINUE
    // quand l'appli passe en arrière-plan (il n'est annulé qu'à la fermeture).
    private var currentUri: Uri? = null
    private var loadJob: Job? = null

    fun reset() {
        loadJob?.cancel()
        currentUri = null
        _state.value = UiState.Home
    }

    fun open(uri: Uri) {
        // Même fichier déjà chargé / en cours → ne rien refaire (anti-rechargement).
        if (uri == currentUri && (_state.value is UiState.Loaded || _state.value is UiState.Loading)) return
        currentUri = uri
        loadJob?.cancel()
        val name = displayName(uri)
        // Rendre la permission SAF PERSISTANTE : sans ça, l'URI d'un .mvr choisi
        // via le sélecteur n'est plus lisible après un redémarrage → « projet perdu ».
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        _state.value = UiState.Loading(name)
        progress.restart()
        progress.report(LoadStep.READ_MVR, 0f)
        loadJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Lecture du fichier impossible.")
                    // Octets lus : la moitié de l'étape « Lecture du fichier MVR… »
                    // est faite, le parse du XML de scène est l'autre moitié.
                    progress.report(LoadStep.READ_MVR, 0.5f)
                    bytes to MvrParser.parse(bytes)
                }
            }
            _state.value = result.fold(
                onSuccess = { (bytes, scene) ->
                    // Projet ouvert avec succès → mémorisé dans les récents.
                    runCatching { RecentProjects.record(getApplication(), uri.toString(), name, System.currentTimeMillis()) }
                    progress.report(LoadStep.READ_MVR, 1f)
                    UiState.Loaded(scene, name, bytes)
                },
                onFailure = {
                    // URI périmée (permission perdue, fichier déplacé) → retirée des récents.
                    runCatching { RecentProjects.remove(getApplication(), uri.toString()) }
                    progress.finish()   // plus de chargement en cours
                    UiState.Error(it.message ?: "Erreur inconnue.")
                }
            )
        }
    }

    /**
     * Ouvre un .mvr fourni en OCTETS (pas d'URI) — pour rejoindre un projet cloud
     * ou rouvrir une nouvelle version téléchargée. Jamais mis dans les récents
     * (pas d'URI SAF re-résoluble).
     */
    fun openBytes(bytes: ByteArray, name: String) {
        loadJob?.cancel()
        currentUri = null
        _state.value = UiState.Loading(name)
        progress.restart()
        progress.report(LoadStep.READ_MVR, 0f)
        loadJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // Octets déjà en main (projet cloud) : il ne reste que le parse.
                    progress.report(LoadStep.READ_MVR, 0.5f)
                    bytes to MvrParser.parse(bytes)
                }
            }
            _state.value = result.fold(
                onSuccess = { (b, scene) ->
                    progress.report(LoadStep.READ_MVR, 1f)
                    UiState.Loaded(scene, name, b)
                },
                onFailure = {
                    progress.finish()
                    UiState.Error(it.message ?: "Erreur inconnue.")
                }
            )
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "fichier.mvr"
    }
}
