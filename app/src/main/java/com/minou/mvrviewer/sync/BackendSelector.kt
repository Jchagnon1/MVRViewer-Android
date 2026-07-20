package com.minou.mvrviewer.sync

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * Choisit l'implémentation de [SyncService] au lancement — PORTAGE de
 * BackendSelector iOS.
 *
 * Règle : une app Firebase par défaut est initialisée (donc `google-services.json`
 * présent + plugin appliqué) ET pas de forçage local → [FirebaseSyncService] ;
 * sinon → [LocalSyncService] (stub de démo/test).
 *
 * SANS `google-services.json` ni le plugin google-services, aucun `FirebaseApp`
 * par défaut n'est auto-initialisé (le `FirebaseInitProvider` échoue silencieusement)
 * → `FirebaseApp.getApps()` est vide → backend LOCAL. Le SDK Firebase est bien
 * compilé, mais reste inerte. Voir ANDROID_FIREBASE_SETUP.md.
 */
object BackendSelector {

    /** Forçage local (tests / démo). Réglé par les hooks de test avant makeService. */
    @Volatile var forceLocal: Boolean = false

    fun firebaseAvailable(context: Context): Boolean {
        if (forceLocal) return false
        return runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }

    fun activeBackendLabel(context: Context): String =
        if (firebaseAvailable(context)) "Firebase" else "Local (démo)"

    fun makeService(context: Context): SyncService =
        if (firebaseAvailable(context)) FirebaseSyncService(context)
        else LocalSyncService.shared(context)
}
