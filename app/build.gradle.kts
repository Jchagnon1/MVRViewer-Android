plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Le plugin google-services N'est PAS appliqué ici : il l'est plus bas, mais
    // SEULEMENT si app/google-services.json est présent (voir en bas du fichier) →
    // un clone sans ce fichier compile quand même (backend LOCAL de démo).
}

android {
    namespace = "com.minou.mvrviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minou.mvrviewer"
        minSdk = 28  // requis par SceneView/Filament (vue 3D)
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
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

// SYNCHRO CLOUD : on applique le plugin google-services UNIQUEMENT si le fichier
// de config Firebase est présent (déposé par l'utilisateur, cf. ANDROID_FIREBASE_SETUP.md).
// Ainsi l'app bascule AUTOMATIQUEMENT sur Firebase quand le json est là, et
// reste buildable (backend LOCAL) sinon — sans édition manuelle. Le json est
// dans .gitignore (config projet, non versionnée).
if (project.file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
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
    // Tests unitaires JVM (calcul du zoom caméra, cf. DistanceZoomTest).
    testImplementation("junit:junit:4.13.2")
}
