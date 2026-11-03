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

## Signature de release

Google Play n'accepte qu'un `.aab` **signé**. Deux clés entrent en jeu :

- la **clé d'envoi** (*upload key*) : la tienne, elle signe chaque `.aab` envoyé sur
  la Play Console ;
- la **clé de distribution** : gardée par Google (**Play App Signing**, activé à la
  création de l'app). C'est elle qui signe l'app installée chez les utilisateurs.
  Si la clé d'envoi est perdue, le support Play peut la réinitialiser ; la clé de
  distribution, elle, ne quitte jamais Google.

### 1. Créer la clé d'envoi (une seule fois)

Au choix :

- **Android Studio** : *Build › Generate Signed App Bundle / APK…* › *Android App
  Bundle* › *Create new…* ; type RSA 2048 bits, validité 25 ans ou plus, alias
  `upload` (par exemple).
- **Ligne de commande** (`keytool` est fourni avec Android Studio) :

  ```bash
  "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
    -genkeypair -v -keystore ~/Documents/"Clés MVR Viewer"/mvrviewer-upload.jks \
    -alias upload -keyalg RSA -keysize 2048 -validity 10000
  ```

  `keytool` demande les mots de passe et l'identité (nom, organisation, pays).

Puis :

- range le `.jks` **HORS du dépôt** (jamais dans `MVRViewerAndroid/`), et fais-en
  **une copie de secours** ailleurs (clé USB, coffre chiffré) ;
- garde les mots de passe dans ton **gestionnaire de mots de passe**.

### 2. Brancher la clé sur le build

```bash
cp keystore.properties.example keystore.properties
```

puis renseigne dans `keystore.properties` (à la racine, **ignoré par git**, comme
`*.jks` et `*.keystore`) : `storeFile` (chemin du `.jks`, absolu ou `~/…`),
`storePassword`, `keyAlias`, `keyPassword`.

### 3. Construire le `.aab`

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
"$JAVA_HOME/bin/jarsigner" -verify app/build/outputs/bundle/release/app-release.aab   # « jar verified. »
```

Sans `keystore.properties`, l'assistant d'Android Studio marche aussi : *Build ›
Generate Signed App Bundle / APK…* › *Android App Bundle*, choisis le `.jks` (ou crée-le
avec *Create new…*), puis *Create* : il passe la clé à Gradle lui-même
(propriétés `android.injected.signing.*`) et range le `.aab` dans `app/release/`.

Garde-fous (dans `app/build.gradle.kts`) : `bundleRelease`, `assembleRelease` et
`installRelease` (donc aussi `./gradlew build`) **échouent avant toute compilation**,
avec la liste de ce qui manque, si aucune clé n'est fournie (ni par l'assistant
d'Android Studio, ni par un `keystore.properties` complet), si le `.jks` indiqué dans
`keystore.properties` est introuvable, ou si `app/google-services.json` manque (sans lui
l'app partirait sans synchro cloud). Le debug (`assembleDebug`) et les tests
(`testDebugUnitTest`) n'en ont pas besoin.

Pour lire un autre fichier que `keystore.properties` (intégration continue, essai
avec une clé jetable) : `./gradlew :app:bundleRelease -PkeystoreProperties=/chemin/du/fichier`.

## Mentions et licences

L'écran « Mentions et licences » (Compte & réglages › À propos) lit
`app/src/main/assets/third_party_licenses.json`, **jamais écrit à la main** :
après tout changement de dépendance, régénérez-le (Gradle est lancé par le script) :

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
python3 Tools/generate_licenses.py          # écrit le fichier
python3 Tools/generate_licenses.py --check  # échoue s'il n'est plus à jour
```

Le script part des artefacts réellement empaquetés dans l'APK de release
(`./gradlew :app:releaseLicenseInputs`, licences des POM) et échoue si un composant
n'est pas classé ou si un texte de licence manque ; provenance des textes recopiés :
`Tools/licenses/README.md`.
