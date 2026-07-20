// Plugins déclarés (mais pas appliqués) au niveau racine — appliqués par module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Firebase : déclaré mais NON appliqué ici — l'app l'appliquera une fois que
    // `google-services.json` sera déposé (voir app/build.gradle.kts).
    alias(libs.plugins.google.services) apply false
}
