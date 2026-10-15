package com.minou.mvrviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * ÉCRAN PLEIN posé PAR-DESSUS la vue de base (SPEC-menus-android §1.3) : surface
 * OPAQUE qui cache entièrement la 3D ou le plan et capte tous les touchers
 * (la `Surface` Material bloque le passage du toucher vers la `SceneView`).
 *
 * Son retour système ferme la destination ; il est composé AVANT le contenu,
 * donc les sous-volets de l'écran (recherche GDTF Share, pages de la
 * bibliothèque MA…) gardent la priorité, et APRÈS la vue de base, donc il passe
 * avant tout retour posé par celle-ci.
 */
@Composable
fun FullScreenDestination(
    destination: ProjectDestination,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    BackHandler { onClose() }
    Surface(
        modifier = Modifier.fillMaxSize().testTag("destination.${destination.id}"),
        color = MaterialTheme.colorScheme.background
    ) { content() }
}

/**
 * Vue de base, rendue muette pour l'accessibilité quand un écran plein la
 * recouvre (TalkBack ne doit pas parcourir la 3D cachée), et rognée à ses
 * bornes : le dessin du plan (étiquettes, ombres) déborde sinon dans la marge
 * de la barre de navigation, SOUS l'écran plein qui, lui, s'arrête à ces bornes
 * (constaté à l'émulateur en paysage).
 */
@Composable
fun SceneBaseLayer(covered: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().then(if (covered) Modifier.clipToBounds().clearAndSetSemantics { } else Modifier)
    ) { content() }
}

/**
 * Propriétaire de cycle de vie du RENDU 3D : suit l'activité, plafonné à
 * STARTED quand [covered] (règle pure [RenderLifecycleRule]). Passé à la `Scene`
 * de SceneView, il met sa boucle de rendu en attente sous un écran plein.
 */
@Composable
fun rememberRenderLifecycle(covered: Boolean): Lifecycle {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember(parent) { CappedLifecycleOwner(parent) }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    LaunchedEffect(owner, covered) { owner.covered = covered }
    return owner.lifecycle
}

private class CappedLifecycleOwner(private val parent: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    var covered: Boolean = false
        set(v) { field = v; sync() }

    private val observer = LifecycleEventObserver { _, _ -> sync() }

    init {
        parent.addObserver(observer)
        sync()
    }

    private fun sync() {
        val target = RenderLifecycleRule.target(parent.currentState, covered)
        val cur = registry.currentState
        // Jamais de DESTROYED depuis INITIALIZED (interdit par LifecycleRegistry),
        // jamais de retour depuis DESTROYED.
        if (cur == Lifecycle.State.DESTROYED) return
        if (target == Lifecycle.State.DESTROYED && cur == Lifecycle.State.INITIALIZED) return
        if (target != cur) registry.currentState = target
    }

    fun dispose() { parent.removeObserver(observer) }
}
