# MVR Viewer — Android

Portage natif Android (Kotlin + Jetpack Compose) de l'app iOS MVRViewer.
Construit **par étapes** — cette première tranche : ouvrir un `.mvr`, le parser
et lister les projecteurs.

## Ouvrir le projet (Android Studio)

1. Installe **Android Studio** (dernière version stable) — inclut le JDK, le SDK
   Android et l'émulateur.
2. `File > Open…` → sélectionne le dossier **`MVRViewerAndroid`** (celui-ci).
3. Android Studio détecte un projet Gradle, télécharge Gradle 8.9 et **synchronise**.
   - S'il propose de mettre à jour l'AGP / Gradle / Kotlin → **accepte** (l'assistant
     ajuste les versions dans `gradle/libs.versions.toml`).
   - S'il manque le « Gradle wrapper », laisse-le le générer.
4. Crée un **émulateur** : `Device Manager` (icône téléphone) → `Add a virtual device`
   → un Pixel récent, image système **arm64** (API 34/35).
5. Bouble ▶︎ **Run 'app'** → l'app se lance sur l'émulateur.

## Tester

- Écran d'accueil → **« Ouvrir un fichier .mvr »**.
- Pour avoir un `.mvr` dans l'émulateur : glisse-dépose un fichier `.mvr` sur la
  fenêtre de l'émulateur (il atterrit dans *Downloads*), puis choisis-le.
- L'app affiche la liste des projecteurs (ID, GDTF, calque, DMX).

## Structure

- `mvr/MvrParser.kt`, `mvr/MvrModels.kt` — parseur MVR (ZIP + XML), portage de
  `MVRParser.swift` / `MVRScene.swift`.
- `ui/` — écrans Compose (`HomeScreen`, `SceneScreen`, `SceneViewModel`).

## Prochaines étapes (roadmap)

1. ✅ Ouvrir + parser un MVR → liste des projecteurs *(cette tranche)*
2. Vue 3D (SceneView / Filament) chargeant la géométrie (glTF/3DS)
3. Vue plan 2D (Compose Canvas)
4. Géolocalisation + carte (Google Maps / Mapbox) + calibration
5. Édition de patch, DMX, etc.

## Build en ligne de commande (vérifié)

Sans passer par l'UI d'Android Studio :

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
# installer + lancer sur un émulateur démarré :
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -n com.minou.mvrviewer/.MainActivity
```

> ℹ️ Le heap Gradle est réglé à **4 Go** (`gradle.properties`) — nécessaire pour
> la compilation Jetpack Compose (à 2 Go le build rampait ~40 min).

> ⚠️ Le premier build télécharge AGP/Kotlin/Compose (quelques minutes). Ensuite
> ~2 min. Si Android Studio affiche `Unknown command-line option '--jvm-vendor'`,
> c'est un réglage IDE : Settings → Build Tools → Gradle → « Gradle JDK » = un JDK
> concret (jbr-21), pas un mode « Daemon JVM ».
