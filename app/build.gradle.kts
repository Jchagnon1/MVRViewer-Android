import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Le plugin google-services N'est PAS appliqué ici : il l'est plus bas, mais
    // SEULEMENT si app/google-services.json est présent (voir en bas du fichier) →
    // un clone sans ce fichier compile quand même (backend LOCAL de démo).
}

// ---------------------------------------------------------------------------
// SIGNATURE DE RELEASE (guide de mise en vente, C7) — README, « Signature de release ».
// La clé d'ENVOI n'est JAMAIS dans le dépôt : son chemin et ses mots de passe sont lus
// dans `keystore.properties`, à la RACINE du projet, ignoré par git (modèle :
// keystore.properties.example). Play App Signing garde la clé de distribution.
// `-PkeystoreProperties=<chemin>` lit un autre fichier (intégration continue, essai
// avec une clé jetable) sans rien écrire dans le dépôt.
// Le debug n'en a pas besoin : seul l'empaquetage d'une RELEASE est refusé, avec un
// message qui dit quoi faire (garde en bas du fichier).
// ---------------------------------------------------------------------------
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
/** Valeur laissée telle quelle depuis le modèle : comptée comme absente. */
val releaseSigningPlaceholder = "A_REMPLACER"
fun expandHome(path: String): String =
    if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path
val keystorePropertiesFile: File = providers.gradleProperty("keystoreProperties").orNull
    ?.takeIf { it.isNotBlank() }?.let { rootProject.file(expandHome(it.trim())) }
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    // Lu en UTF-8 (et non en ISO-8859-1, le défaut des .properties) : un chemin
    // avec accents (« ~/Documents/Clés/… ») reste juste.
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.reader(Charsets.UTF_8).use { load(it) }
}
fun keystoreValue(key: String): String? = keystoreProperties.getProperty(key)?.trim()
    ?.takeIf { it.isNotEmpty() && it != releaseSigningPlaceholder }
val releaseStoreFile: File? = keystoreValue("storeFile")?.let { rootProject.file(expandHome(it)) }
val releaseSigningReady: Boolean = keystorePropertiesFile.isFile &&
    releaseSigningKeys.all { keystoreValue(it) != null } && releaseStoreFile?.isFile == true
// L'assistant d'Android Studio (Build › Generate Signed App Bundle / APK…, celui qui crée
// la clé d'envoi : README, option 1) passe la clé à Gradle par ces quatre propriétés ;
// l'Android Gradle Plugin signe alors lui-même (et dit si le .jks est introuvable) :
// keystore.properties n'est pas requis dans ce cas.
val injectedSigningProperties = listOf(
    "android.injected.signing.store.file", "android.injected.signing.store.password",
    "android.injected.signing.key.alias", "android.injected.signing.key.password"
)
val injectedSigningReady: Boolean =
    injectedSigningProperties.all { !providers.gradleProperty(it).orNull.isNullOrBlank() }

/** Ce qui manque pour empaqueter une release (textes pour la personne qui la lance) ; vide = prêt. */
fun releasePrerequisiteProblems(): List<String> = buildList {
    if (injectedSigningReady) {
        // Clé fournie par l'assistant d'Android Studio : rien à vérifier ici.
    } else if (!keystorePropertiesFile.isFile) {
        add("""
            |Signature de release impossible : keystore.properties est introuvable
            |(${keystorePropertiesFile.path}).
            |  1. Copiez keystore.properties.example en keystore.properties, à la racine du projet
            |     (fichier ignoré par git : ne le committez jamais).
            |  2. Renseignez storeFile (chemin du .jks de la clé d'envoi, rangé HORS du dépôt),
            |     storePassword, keyAlias et keyPassword.
            |  3. Pas encore de clé d'envoi : README.md, section « Signature de release ».
            |Ou signez avec l'assistant d'Android Studio (Build › Generate Signed App Bundle / APK…).
            """.trimMargin())
    } else {
        val missing = releaseSigningKeys.filter { keystoreValue(it) == null }
        if (missing.isNotEmpty()) add(
            "${keystorePropertiesFile.name} (${keystorePropertiesFile.path}) : à renseigner : " +
                missing.joinToString(", ") + " (modèle : keystore.properties.example)."
        )
        val store = releaseStoreFile
        if (store != null && !store.isFile) add(
            "${keystorePropertiesFile.name} : le fichier de clé indiqué par storeFile est introuvable : ${store.path}\n" +
                "  Indiquez le chemin du .jks de la clé d'envoi (absolu, ou ~/…)."
        )
    }
    if (!project.file("google-services.json").isFile) add("""
        |app/google-services.json est introuvable : une release sans lui partirait SANS synchro
        |cloud (backend local de démo).
        |  Téléchargez-le depuis la console Firebase (projet mvrviewermulti, app Android
        |  com.minou.mvrviewer) et déposez-le dans app/ : voir ANDROID_FIREBASE_SETUP.md.
        """.trimMargin())
}

android {
    namespace = "com.minou.mvrviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.minou.mvrviewer"
        minSdk = 28  // requis par SceneView/Filament (vue 3D)
        targetSdk = 36  // exigence Google Play (niveau d'API cible) pour les nouvelles soumissions
        versionCode = 21
        versionName = "1.10.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Créée seulement si keystore.properties est complet ; sinon la release reste
        // sans signature et la garde (en bas du fichier) refuse de l'empaqueter.
        if (releaseSigningReady) create("release") {
            storeFile = releaseStoreFile
            storePassword = keystoreValue("storePassword")
            keyAlias = keystoreValue("keyAlias")
            keyPassword = keystoreValue("keyPassword")
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
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

// « Mentions et licences » (guide C11) : tâche releaseLicenseInputs, lue par
// Tools/generate_licenses.py (composants de l'APK de release et licences de leurs POM).
apply(from = rootProject.file("gradle/third-party-licenses.gradle.kts"))

// GARDE DE RELEASE (guide C7 et C9) : empaqueter une release (bundleRelease,
// assembleRelease…) sans clé d'envoi complète (keystore.properties, ou la clé passée
// par l'assistant d'Android Studio), ou sans app/google-services.json,
// échoue AVANT toute compilation, avec la liste de ce qui manque. Le debug
// (assembleDebug, installDebug) et les tests (testDebugUnitTest) ne sont pas concernés.
val releasePackagingTasks = listOf(
    "bundleRelease", "assembleRelease", "installRelease",
    "packageRelease", "packageReleaseBundle", "signReleaseBundle"
).map { "${project.path}:$it" }.toSet()
gradle.taskGraph.whenReady {
    if (allTasks.none { it.path in releasePackagingTasks }) return@whenReady
    val problems = releasePrerequisiteProblems()
    if (problems.isNotEmpty()) throw GradleException(
        "Release refusée : il manque ce qu'il faut pour la publier.\n\n" +
            problems.joinToString("\n\n") +
            "\n\nLe build debug (./gradlew :app:assembleDebug) n'en a pas besoin."
    )
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
    implementation(libs.billing.ktx)
    debugImplementation(libs.androidx.ui.tooling)
    // Tests unitaires JVM (calcul du zoom caméra, cf. DistanceZoomTest).
    testImplementation("junit:junit:4.13.2")
    // org.json RÉEL sur le classpath de test : l'android.jar des tests unitaires
    // ne fournit que des stubs qui lèvent « not mocked ». Sans cette vraie impl,
    // les round-trips JSON (SectionCodec, PowerCablingCodec) ne peuvent pas tourner.
    testImplementation("org.json:json:20240303")
}
