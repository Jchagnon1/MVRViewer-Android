package com.minou.mvrviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.minou.mvrviewer.mvr.MvrScene

enum class SceneMode { THREE_D, PLAN, PATCH }

/**
 * Hôte d'un .mvr ouvert. Comme iOS, la **vue 3D est l'écran principal** ; la
 * vue plan et la liste de patch s'atteignent depuis le menu d'options (⋯). Les
 * réglages d'affichage (fond, couleurs par calque, étiquettes) sont partagés
 * entre les vues.
 */
@Composable
fun SceneScreen(
    scene: MvrScene,
    fileName: String,
    mvrBytes: ByteArray,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(SceneMode.THREE_D) }
    val options = remember { SceneOptions() }

    when (mode) {
        SceneMode.THREE_D -> Scene3DScreen(
            scene = scene,
            mvrBytes = mvrBytes,
            options = options,
            onShowPlan = { mode = SceneMode.PLAN },
            onShowPatch = { mode = SceneMode.PATCH },
            onClose = onClose,
            modifier = modifier
        )
        SceneMode.PLAN -> PlanScreen(
            scene = scene,
            options = options,
            onBack = { mode = SceneMode.THREE_D },
            onShowPatch = { mode = SceneMode.PATCH },
            modifier = modifier
        )
        SceneMode.PATCH -> PatchListScreen(
            scene = scene,
            onBack = { mode = SceneMode.THREE_D },
            modifier = modifier
        )
    }
}
