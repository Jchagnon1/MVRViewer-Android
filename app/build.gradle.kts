plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // SYNCHRO CLOUD — décommenter APRÈS avoir déposé app/google-services.json
    // (app Android `com.minou.mvrviewer` ajoutée au projet Firebase mvrviewermulti).
    // Sans le plist, l'app tourne sur le backend LOCAL de démo (rien ne casse).
    // alias(libs.plugins.google.services)
}

android {
    namespace = "com.minou.mvrviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minou.mvrviewer"
        minSdk = 28  // requis par SceneView/Filament (vue 3D)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

// Kotlin 2.4 : l'ancien kotlinOptions{ jvmTarget } est supprimé → compilerOptions DSL.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services) // .await() sur les Task Firebase
    implementation(libs.sceneview)
    // Firebase (synchro cloud). Les classes SDK compilent SANS le plugin
    // google-services ; sans google-services.json, FirebaseApp ne s'initialise
    // pas → BackendSelector bascule sur le backend LOCAL.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    debugImplementation(libs.androidx.ui.tooling)
}
