package com.minou.mvrviewer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minou.mvrviewer.ui.HomeScreen
import com.minou.mvrviewer.ui.SceneScreen
import com.minou.mvrviewer.ui.SceneViewModel
import com.minou.mvrviewer.ui.theme.MvrViewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MvrViewerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    App(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun App(modifier: Modifier = Modifier) {
    val vm: SceneViewModel = viewModel()
    val state by vm.state
    when (val s = state) {
        is SceneViewModel.UiState.Home,
        is SceneViewModel.UiState.Loading,
        is SceneViewModel.UiState.Error ->
            HomeScreen(state = s, modifier = modifier, onOpen = { uri: Uri -> vm.open(uri) })
        is SceneViewModel.UiState.Loaded ->
            SceneScreen(scene = s.scene, fileName = s.fileName, modifier = modifier, onClose = vm::reset)
    }
}
