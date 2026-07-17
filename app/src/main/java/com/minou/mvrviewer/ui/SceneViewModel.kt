package com.minou.mvrviewer.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minou.mvrviewer.mvr.MvrParser
import com.minou.mvrviewer.mvr.MvrScene
import kotlinx.coroutines.Dispatchers
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

    fun reset() { _state.value = UiState.Home }

    fun open(uri: Uri) {
        val name = displayName(uri)
        _state.value = UiState.Loading(name)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Lecture du fichier impossible.")
                    bytes to MvrParser.parse(bytes)
                }
            }
            _state.value = result.fold(
                onSuccess = { (bytes, scene) -> UiState.Loaded(scene, name, bytes) },
                onFailure = { UiState.Error(it.message ?: "Erreur inconnue.") }
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
