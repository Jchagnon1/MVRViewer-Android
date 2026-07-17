// Plugins déclarés (mais pas appliqués) au niveau racine — appliqués par module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
