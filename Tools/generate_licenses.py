#!/usr/bin/env python3
"""Régénère app/src/main/assets/third_party_licenses.json — l'écran « Mentions et
licences » (Compte & réglages › À propos, guide de mise en vente C11), lisible
hors ligne.

Usage (depuis la racine du dépôt) :

    python3 Tools/generate_licenses.py [--inputs FICHIER] [--check]

- Sans --inputs : lance `./gradlew -q :app:releaseLicenseInputs` (mêmes
  JAVA_HOME et ANDROID_HOME qu'un build), qui écrit
  app/build/licenses/release-components.json : les composants RÉELLEMENT
  empaquetés dans l'APK de release (fichiers .aar et .jar de
  releaseRuntimeClasspath ; ni les BOM ni les modules « racine » Kotlin
  Multiplatform, sans fichier), avec les licences déclarées dans leur POM
  (POM parents compris) — voir gradle/third-party-licenses.gradle.kts.
- --inputs : lit ce fichier au lieu de lancer Gradle.
- --check : ne réécrit rien, échoue si le fichier embarqué n'est plus à jour.

Chaque artefact empaqueté doit appartenir à UN projet de PROJECTS (AndroidX :
un projet par groupe Maven, nommé d'après GROUPES_ANDROIDX). Un artefact non
classé, une licence de POM inconnue ou différente de celle du projet, une
version différente de celle des textes recopiés, ou un texte manquant ARRÊTE
le script : l'écran doit tout montrer.

D'où viennent les textes (texte COMPLET de chaque licence) :
- des artefacts eux-mêmes quand ils l'embarquent : LICENSE.txt des jar AndroidX
  (tous identiques, vérifié), META-INF/LICENSE.txt de checker-qual, avis
  third_party_licenses.json/.txt des AAR de Google (Play services, Billing,
  Firebase Authentication…), dont le contrat de licence du SDK Android et les
  conditions de Play Core et de Play Integrity ;
- sinon de Tools/licenses/ : fichiers LICENSE et NOTICE des projets, recopiés
  À L'IDENTIQUE à la version empaquetée (empreinte git vérifiée ici, provenance
  dans Tools/licenses/README.md).
L'avis NOTICE d'un projet sous Apache 2.0 (§4(d)) est placé EN TÊTE du texte.

Composants compilés DANS d'autres (« Inclus dans … ») :
- code natif de Filament 1.71.5 (libfilament-jni, libgltfio-jni,
  libfilament-utils-jni) : bibliothèques de third_party/ liées d'après les
  CMakeLists Android de Filament à cette version ; quand c'est possible, une
  chaîne caractéristique est cherchée dans les .so (sinon, arrêt) ; Perfetto
  et Abseil, liés seulement sur option, n'y laissent aucune trace (vérifié).
- code tiers de la bibliothèque standard Kotlin 2.4.0 (license/README.md) ;
- avis de tiers que Google fournit DANS ses AAR (third_party_licenses), repris
  tels quels, rattachés à l'AAR qui les fournit.

Sortie : app/src/main/assets/third_party_licenses.json (lu par
ThirdPartyLicenseCatalog.kt ; test : LicensesCatalogTest).
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_INPUTS = os.path.join(REPO, "app", "build", "licenses", "release-components.json")
OUTPUT = os.path.join(REPO, "app", "src", "main", "assets", "third_party_licenses.json")
LICENSES_DIR = os.path.join(REPO, "Tools", "licenses")

# Fichiers de Tools/licenses : empreinte git (`git hash-object`) de la copie À
# L'IDENTIQUE de la source (adresse dans Tools/licenses/README.md).
STORED = {
    "apache-2.0/LICENSE-2.0.txt": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "kotlin/LICENSE.txt": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "kotlin/NOTICE.txt": "80dbbefa483f605b7e69a1458f9f66b277b61988",
    "kotlin/third_party/gwt_license.txt": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "kotlin/third_party/threetenbp_license.txt": "bbed3563e938dd26bdc2daeb5f5cccdd0f03eb61",
    "kotlin/third_party/guava_license.txt": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "kotlin/third_party/boost_LICENSE.txt": "127a5bc39ba030c7cb99cc0aedc4f280ffe27310",
    "kotlinx-coroutines/LICENSE.txt": "9c308d958bf91eafe83f66106ca692ff414a4965",
    "kotlinx-coroutines/NOTICE.txt": "01d81385e4ace2e64601668ea7021ca27cdbe376",
    "kotlinx-serialization/LICENSE.txt": "9c8f3ea0871e0bfe81da0fa6e7c1d7d156dc380e",
    "kotlinx-serialization/NOTICE.txt": "ee516ec09649791af5ff26c0346e748a881cacb7",
    "jetbrains-annotations/LICENSE.txt": "4c4a741bfd6103cc173315b0c699ea93bfe71383",
    "guava/LICENSE": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "gson/LICENSE": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "error-prone/COPYING": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "protobuf/LICENSE": "19b305b00060a774a9180fb916c14b49edb2008f",
    "grpc-java/LICENSE": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "grpc-java/NOTICE.txt": "f70c5620cf75a7c10de39134b889d4c4379b33d2",
    "perfmark/LICENSE": "261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64",
    "perfmark/NOTICE": "04fbb4e692e51e6ca59ae3955171c37b493959c7",
    "okio/LICENSE.txt": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "jspecify/LICENSE": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "kotlin-math/LICENSE": "261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64",
    "sceneview/LICENSE": "261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64",
    "filament/LICENSE": "73774b41cafc7ca9171fa50ea9fd95da343be320",
    "filament/third_party/cgltf/LICENSE": "599d9341a7d681d7f68b614625c0664c949a6a90",
    "filament/third_party/draco/LICENSE": "30109545483a9b9889532c2bd5254c6fee4eb121",
    "filament/third_party/basisu/LICENSE": "94ea880430871f065fc4d9aef92fb5854003bd01",
    "filament/third_party/meshoptimizer/LICENSE": "b673c248b22c9e5a448e07610cb0bd34abcce636",
    "filament/third_party/mikktspace/mikktspace.c-header.txt": "e46eb74e29c92f0cc756687157cc6b61f9871e9d",
    "filament/third_party/stb/LICENSE": "11079e97a6289515527b4bcfe00bad83a59f5557",
    "filament/third_party/libwebp/COPYING": "7a6f99547d4d6b4c5e6d5321acfaeba313e08de3",
    "filament/third_party/libwebp/PATENTS": "caedf607e95ae4c3dc84d057fddcc301cd0e4863",
    "filament/third_party/zstd/LICENSE": "75800288cc243f164b9d130f125e2ffae28f8e39",
    "filament/third_party/smol-v/LICENSE": "594910d42d0c156bf32f88e9c8eedd989731871b",
    "filament/third_party/vkmemalloc/LICENSE.txt": "fcf8b67de1f433634963108bee518f51ddee71f5",
    "filament/third_party/robin-map/LICENSE": "e9c5ae95f36a6b4e4e9937adcc674b155f8011c8",
    "filament/third_party/civetweb/LICENSE.md": "ab049e3e72e7bd04d4bccaf25397fa93dc8353a5",
    "filament/third_party/jsmn/LICENSE": "c84fb2e973dd885ea5fd426aedf6e5a1849feeaa",
    "filament/third_party/spirv-headers/LICENSE": "a63e9c620ee123267192113ab0be3814782ba909",
    "fuel/LICENSE.md": "a9d52cda19360d95260be46dd1ae815692957f9b",
    "result/LICENSE.md": "22aae7508ab2fd1f230d9b83a375d281781abf25",
    "firebase-android-sdk/LICENSE": "d645695673349e3947e8e5ae42332d0ac3164cd7",
    "animal-sniffer/LICENSE": "d69c4852d99c40292a542fa338433f1be0a2c836",
    "ezdxf/LICENSE": "33a77673a9100f1f8553745ac6c924a6b8e58410",
}

# Noms de licence des POM → identifiant. Un nom absent d'ici ARRÊTE le script.
POM_LICENSES = {
    "The Apache Software License, Version 2.0": "Apache-2.0",
    "The Apache License, Version 2.0": "Apache-2.0",
    "Apache License, Version 2.0": "Apache-2.0",
    "Apache 2.0": "Apache-2.0",
    "Apache-2.0": "Apache-2.0",
    "MIT License": "MIT",
    "The MIT License": "MIT",
    "MIT license": "MIT",
    "BSD-3-Clause": "BSD-3-Clause",
    "Android Software Development Kit License": "Android-SDK",
    "Play Core Software Development Kit Terms of Service": "Play-Core",
    "Play Integrity API Terms of Service": "Play-Integrity",
}

# Libellé affiché (jamais traduit : ce sont des noms de licences).
LABELS = {
    "Apache-2.0": "Apache 2.0",
    "MIT": "MIT",
    "BSD-3-Clause": "BSD 3-Clause",
    "Android-SDK": "Android Software Development Kit License",
    "Play-Core": "Play Core Software Development Kit Terms of Service",
    "Play-Integrity": "Play Integrity API Terms of Service",
}

ANDROIDX_URL = "https://android.googlesource.com/platform/frameworks/support"
FIREBASE_URL = "https://github.com/firebase/firebase-android-sdk"
FILAMENT_TP = "https://github.com/google/filament/tree/v1.71.5/third_party/"
ANDROID_SDK_TEXT = ("aar-notice", "com.android.billingclient:billing", "Android SDK Platforms")
# Avis d'un AAR qui ne sont que la licence de son propre projet (même texte) :
# déjà affichés comme licence du projet, pas repris en « composant inclus ».
SELF_NOTICES = {
    "Android-SDK": {"Android SDK Platforms"},
    "Play-Core": {"Play Core Software Development Kit"},
    "Play-Integrity": {"Play Integrity API Terms of Service"},
}

# Noms des groupes AndroidX (un projet par groupe Maven ; un groupe absent
# d'ici prend « AndroidX <dernier segment> »).
ANDROIDX_GROUPS = {
    "androidx.activity": "AndroidX Activity",
    "androidx.annotation": "AndroidX Annotation",
    "androidx.arch.core": "AndroidX Arch Core",
    "androidx.autofill": "AndroidX Autofill",
    "androidx.browser": "AndroidX Browser",
    "androidx.collection": "AndroidX Collection",
    "androidx.compose.animation": "Jetpack Compose Animation",
    "androidx.compose.foundation": "Jetpack Compose Foundation",
    "androidx.compose.material": "Jetpack Compose Material (Icons, Ripple)",
    "androidx.compose.material3": "Jetpack Compose Material 3",
    "androidx.compose.runtime": "Jetpack Compose Runtime",
    "androidx.compose.ui": "Jetpack Compose UI",
    "androidx.concurrent": "AndroidX Concurrent",
    "androidx.core": "AndroidX Core",
    "androidx.credentials": "AndroidX Credentials",
    "androidx.customview": "AndroidX CustomView",
    "androidx.documentfile": "AndroidX DocumentFile",
    "androidx.dynamicanimation": "AndroidX DynamicAnimation",
    "androidx.emoji2": "AndroidX Emoji2",
    "androidx.fragment": "AndroidX Fragment",
    "androidx.graphics": "AndroidX Graphics",
    "androidx.interpolator": "AndroidX Interpolator",
    "androidx.legacy": "AndroidX Legacy",
    "androidx.lifecycle": "AndroidX Lifecycle",
    "androidx.loader": "AndroidX Loader",
    "androidx.localbroadcastmanager": "AndroidX LocalBroadcastManager",
    "androidx.print": "AndroidX Print",
    "androidx.profileinstaller": "AndroidX ProfileInstaller",
    "androidx.savedstate": "AndroidX SavedState",
    "androidx.startup": "AndroidX Startup",
    "androidx.tracing": "AndroidX Tracing",
    "androidx.transition": "AndroidX Transition",
    "androidx.versionedparcelable": "AndroidX VersionedParcelable",
    "androidx.viewpager": "AndroidX ViewPager",
    "androidx.window": "AndroidX WindowManager",
}


def project(pid, name, match, license, texts, url, source=True, main=None, pinned=None):
    """`match` : groupes Maven (« g ») ou artefacts (« g:n ») du projet.
    `license` : identifiant attendu dans les POM de TOUS ses artefacts.
    `texts` : morceaux du texte affiché, dans l'ordre (NOTICE puis LICENSE).
    `url` : adresse du code source (`source=True`) ou, pour un SDK fermé, de
    sa page (`source=False`). `main` : artefact (« g:n ») dont la version est
    affichée. `pinned` : {« g:n » : version} des textes recopiés de
    Tools/licenses — toute autre version arrête le script (textes à mettre à
    jour depuis la nouvelle version)."""
    return dict(id=pid, name=name, match=match, license=license, texts=texts, url=url,
                source=source, main=main, pinned=pinned or {})


def stored(path):
    return ("file", path)


# Projets hors AndroidX (ordre indifférent : l'écran trie par nom).
PROJECTS = [
    project("kotlin-stdlib", "Kotlin Standard Library", ["org.jetbrains.kotlin"], "Apache-2.0",
            [stored("kotlin/NOTICE.txt"), stored("kotlin/LICENSE.txt")],
            "https://github.com/JetBrains/kotlin", main="org.jetbrains.kotlin:kotlin-stdlib",
            pinned={"org.jetbrains.kotlin:kotlin-stdlib": "2.4.0"}),
    project("kotlinx-coroutines", "kotlinx.coroutines", ["org.jetbrains.kotlinx:kotlinx-coroutines-android",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm", "org.jetbrains.kotlinx:kotlinx-coroutines-play-services"],
            "Apache-2.0", [stored("kotlinx-coroutines/NOTICE.txt"), stored("kotlinx-coroutines/LICENSE.txt")],
            "https://github.com/Kotlin/kotlinx.coroutines", main="org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
            pinned={"org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm": "1.9.0"}),
    project("kotlinx-serialization", "kotlinx.serialization", ["org.jetbrains.kotlinx:kotlinx-serialization-core-jvm"],
            "Apache-2.0", [stored("kotlinx-serialization/NOTICE.txt"), stored("kotlinx-serialization/LICENSE.txt")],
            "https://github.com/Kotlin/kotlinx.serialization",
            pinned={"org.jetbrains.kotlinx:kotlinx-serialization-core-jvm": "1.7.3"}),
    project("jetbrains-annotations", "JetBrains Java Annotations", ["org.jetbrains:annotations"], "Apache-2.0",
            [stored("jetbrains-annotations/LICENSE.txt")], "https://github.com/JetBrains/java-annotations",
            pinned={"org.jetbrains:annotations": "23.0.0"}),
    project("guava", "Guava", ["com.google.guava"], "Apache-2.0", [stored("guava/LICENSE")],
            "https://github.com/google/guava", main="com.google.guava:guava",
            pinned={"com.google.guava:guava": "32.1.3-android"}),
    project("gson", "Gson", ["com.google.code.gson:gson"], "Apache-2.0", [stored("gson/LICENSE")],
            "https://github.com/google/gson", pinned={"com.google.code.gson:gson": "2.10.1"}),
    project("error-prone", "Error Prone Annotations", ["com.google.errorprone:error_prone_annotations"], "Apache-2.0",
            [stored("error-prone/COPYING")], "https://github.com/google/error-prone",
            pinned={"com.google.errorprone:error_prone_annotations": "2.26.0"}),
    project("protobuf", "Protocol Buffers (Java Lite)", ["com.google.protobuf:protobuf-javalite"], "BSD-3-Clause",
            [stored("protobuf/LICENSE")], "https://github.com/protocolbuffers/protobuf",
            pinned={"com.google.protobuf:protobuf-javalite": "3.25.1"}),
    project("grpc-java", "gRPC Java", ["io.grpc"], "Apache-2.0",
            [stored("grpc-java/NOTICE.txt"), stored("grpc-java/LICENSE")], "https://github.com/grpc/grpc-java",
            main="io.grpc:grpc-core", pinned={"io.grpc:grpc-core": "1.62.2"}),
    project("perfmark", "PerfMark", ["io.perfmark:perfmark-api"], "Apache-2.0",
            [stored("perfmark/NOTICE"), stored("perfmark/LICENSE")], "https://github.com/perfmark/perfmark",
            pinned={"io.perfmark:perfmark-api": "0.26.0"}),
    project("okio", "Okio", ["com.squareup.okio:okio-jvm"], "Apache-2.0", [stored("okio/LICENSE.txt")],
            "https://github.com/square/okio", pinned={"com.squareup.okio:okio-jvm": "3.4.0"}),
    project("jspecify", "JSpecify", ["org.jspecify:jspecify"], "Apache-2.0", [stored("jspecify/LICENSE")],
            "https://github.com/jspecify/jspecify", pinned={"org.jspecify:jspecify": "1.0.0"}),
    project("javax-inject", "javax.inject (JSR 330)", ["javax.inject:javax.inject"], "Apache-2.0",
            [stored("apache-2.0/LICENSE-2.0.txt")], "https://github.com/javax-inject/javax-inject"),
    project("jsr305", "JSR 305 Annotations", ["com.google.code.findbugs:jsr305"], "Apache-2.0",
            [stored("apache-2.0/LICENSE-2.0.txt")], "https://code.google.com/archive/p/jsr-305/"),
    project("android-annotations", "Android Annotations (com.google.android)", ["com.google.android:annotations"],
            "Apache-2.0", [stored("apache-2.0/LICENSE-2.0.txt")], "https://android.googlesource.com/platform/frameworks/base"),
    project("checker-qual", "Checker Framework Qualifiers", ["org.checkerframework:checker-qual"], "MIT",
            [("artifact-file", "org.checkerframework:checker-qual", "META-INF/LICENSE.txt")],
            "https://github.com/typetools/checker-framework"),
    project("animal-sniffer", "Animal Sniffer Annotations", ["org.codehaus.mojo:animal-sniffer-annotations"], "MIT",
            [stored("animal-sniffer/LICENSE")], "https://github.com/mojohaus/animal-sniffer"),
    project("kotlin-math", "kotlin-math", ["dev.romainguy:kotlin-math-jvm"], "Apache-2.0", [stored("kotlin-math/LICENSE")],
            "https://github.com/romainguy/kotlin-math", pinned={"dev.romainguy:kotlin-math-jvm": "1.8.0"}),
    project("sceneview", "SceneView", ["io.github.sceneview"], "Apache-2.0", [stored("sceneview/LICENSE")],
            "https://github.com/sceneview/sceneview", main="io.github.sceneview:sceneview",
            pinned={"io.github.sceneview:sceneview": "4.22.0"}),
    project("filament", "Filament", ["com.google.android.filament"], "Apache-2.0", [stored("filament/LICENSE")],
            "https://github.com/google/filament", main="com.google.android.filament:filament-android",
            pinned={"com.google.android.filament:filament-android": "1.71.5",
                    "com.google.android.filament:gltfio-android": "1.71.5",
                    "com.google.android.filament:filament-utils-android": "1.71.5"}),
    project("fuel", "Fuel", ["com.github.kittinunf.fuel"], "MIT", [stored("fuel/LICENSE.md")],
            "https://github.com/kittinunf/fuel", main="com.github.kittinunf.fuel:fuel",
            pinned={"com.github.kittinunf.fuel:fuel": "2.3.1"}),
    project("result", "Result", ["com.github.kittinunf.result:result"], "MIT", [stored("result/LICENSE.md")],
            "https://github.com/kittinunf/Result", pinned={"com.github.kittinunf.result:result": "3.1.0"}),
    # Firebase : parties libres (dépôt firebase-android-sdk, même LICENSE).
    project("firebase-common", "Firebase Common", ["com.google.firebase:firebase-common",
            "com.google.firebase:firebase-common-ktx", "com.google.firebase:firebase-components",
            "com.google.firebase:firebase-annotations"], "Apache-2.0", [stored("firebase-android-sdk/LICENSE")],
            FIREBASE_URL, main="com.google.firebase:firebase-common"),
    project("firebase-appcheck", "Firebase App Check", ["com.google.firebase:firebase-appcheck",
            "com.google.firebase:firebase-appcheck-interop"], "Apache-2.0", [stored("firebase-android-sdk/LICENSE")],
            FIREBASE_URL, main="com.google.firebase:firebase-appcheck"),
    project("firestore", "Cloud Firestore", ["com.google.firebase:firebase-firestore",
            "com.google.firebase:firebase-firestore-ktx", "com.google.firebase:firebase-database-collection",
            "com.google.firebase:protolite-well-known-types"], "Apache-2.0", [stored("firebase-android-sdk/LICENSE")],
            FIREBASE_URL, main="com.google.firebase:firebase-firestore"),
    project("firebase-storage", "Cloud Storage for Firebase", ["com.google.firebase:firebase-storage",
            "com.google.firebase:firebase-storage-ktx"], "Apache-2.0", [stored("firebase-android-sdk/LICENSE")],
            FIREBASE_URL, main="com.google.firebase:firebase-storage"),
    project("firebase-encoders", "Firebase Encoders", ["com.google.firebase:firebase-encoders",
            "com.google.firebase:firebase-encoders-json", "com.google.firebase:firebase-encoders-proto"],
            "Apache-2.0", [stored("firebase-android-sdk/LICENSE")], FIREBASE_URL,
            main="com.google.firebase:firebase-encoders"),
    project("datatransport", "Google Data Transport", ["com.google.android.datatransport"], "Apache-2.0",
            [stored("firebase-android-sdk/LICENSE")], FIREBASE_URL + "/tree/main/transport",
            main="com.google.android.datatransport:transport-runtime"),
    # SDK FERMÉS de Google : pas de code source publié ; texte du contrat tiré des AAR.
    project("firebase-auth", "Firebase Authentication", ["com.google.firebase:firebase-auth",
            "com.google.firebase:firebase-auth-ktx", "com.google.firebase:firebase-auth-interop"], "Android-SDK",
            [ANDROID_SDK_TEXT], "https://firebase.google.com/docs/auth", source=False,
            main="com.google.firebase:firebase-auth"),
    project("play-services", "Google Play services", ["com.google.android.gms"], "Android-SDK",
            [ANDROID_SDK_TEXT], "https://developers.google.com/android/guides/overview", source=False,
            main="com.google.android.gms:play-services-basement"),
    project("play-billing", "Google Play Billing Library", ["com.android.billingclient"], "Android-SDK",
            [ANDROID_SDK_TEXT], "https://developer.android.com/google/play/billing", source=False,
            main="com.android.billingclient:billing"),
    project("googleid", "Sign in with Google (googleid)", ["com.google.android.libraries.identity.googleid"],
            "Android-SDK", [ANDROID_SDK_TEXT], "https://developer.android.com/identity/sign-in/credential-manager-siwg",
            source=False),
    project("recaptcha", "reCAPTCHA Enterprise", ["com.google.android.recaptcha"], "Android-SDK",
            [ANDROID_SDK_TEXT], "https://cloud.google.com/recaptcha/docs/instrument-android-apps", source=False),
    project("play-core", "Play Core (common)", ["com.google.android.play:core-common"], "Play-Core",
            [("aar-notice", "com.google.android.play:core-common", "Play Core Software Development Kit")],
            "https://developer.android.com/guide/playcore", source=False),
    project("play-integrity", "Play Integrity", ["com.google.android.play:integrity"], "Play-Integrity",
            [("aar-notice", "com.google.android.play:integrity", "Play Integrity API Terms of Service")],
            "https://developer.android.com/google/play/integrity", source=False),
]

# Ni Maven ni AndroidX : code ou données repris dans l'app elle-même.
APP_EMBEDDED = [
    # Palette ACI (index de couleurs AutoCAD 0-255) de DxfParser.kt, extraite d'ezdxf.
    dict(id="ezdxf", name="ezdxf", license="MIT", texts=[stored("ezdxf/LICENSE")],
         url="https://github.com/mozman/ezdxf", source=True, artifacts=[]),
]


def child(cid, name, parent, license, texts, url, evidence=None):
    """Composant compilé DANS `parent`. `evidence` : liste de (fichier .so,
    chaîne) dont la présence est exigée dans l'AAR du parent."""
    return dict(id=cid, name=name, parent=parent, license=license, texts=texts, url=url, evidence=evidence or [])


# Code natif de Filament 1.71.5 (CMakeLists de android/filament-android,
# android/gltfio-android, android/filament-utils-android et des bibliothèques
# qu'ils lient : libs/gltfio, libs/geometry, libs/viewer, libs/uberz,
# filament/backend — moteur Vulkan compris).
FILAMENT_NATIVE = [
    child("filament-cgltf", "cgltf", "filament", "MIT", [stored("filament/third_party/cgltf/LICENSE")],
          FILAMENT_TP + "cgltf", evidence=[("libgltfio-jni.so", "glTF")]),
    child("filament-draco", "Draco", "filament", "Apache 2.0", [stored("filament/third_party/draco/LICENSE")],
          FILAMENT_TP + "draco", evidence=[("libgltfio-jni.so", "Draco")]),
    child("filament-basisu", "Basis Universal", "filament", "Apache 2.0", [stored("filament/third_party/basisu/LICENSE")],
          FILAMENT_TP + "basisu", evidence=[("libgltfio-jni.so", "basisu")]),
    child("filament-meshoptimizer", "meshoptimizer", "filament", "MIT",
          [stored("filament/third_party/meshoptimizer/LICENSE")], FILAMENT_TP + "meshoptimizer",
          evidence=[("libgltfio-jni.so", "meshopt")]),
    child("filament-mikktspace", "MikkTSpace", "filament", "Zlib",
          [("c-comment", "filament/third_party/mikktspace/mikktspace.c-header.txt")], FILAMENT_TP + "mikktspace"),
    child("filament-stb", "stb", "filament", "Public Domain", [stored("filament/third_party/stb/LICENSE")],
          FILAMENT_TP + "stb", evidence=[("libgltfio-jni.so", "bad zlib header")]),
    child("filament-libwebp", "libwebp", "filament", "BSD 3-Clause",
          [stored("filament/third_party/libwebp/COPYING"), stored("filament/third_party/libwebp/PATENTS")],
          FILAMENT_TP + "libwebp", evidence=[("libgltfio-jni.so", "webp")]),
    child("filament-zstd", "Zstandard", "filament", "BSD 3-Clause", [stored("filament/third_party/zstd/LICENSE")],
          FILAMENT_TP + "zstd", evidence=[("libfilament-jni.so", "ZSTD_"), ("libgltfio-jni.so", "ZSTD_")]),
    child("filament-smol-v", "SMOL-V", "filament", "MIT", [stored("filament/third_party/smol-v/LICENSE")],
          FILAMENT_TP + "smol-v", evidence=[("libfilament-jni.so", "Vulkan")]),
    child("filament-vkmemalloc", "Vulkan Memory Allocator", "filament", "MIT",
          [stored("filament/third_party/vkmemalloc/LICENSE.txt")], FILAMENT_TP + "vkmemalloc",
          evidence=[("libfilament-jni.so", "Vulkan")]),
    child("filament-spirv-headers", "SPIRV-Headers", "filament", "MIT",
          [stored("filament/third_party/spirv-headers/LICENSE")], FILAMENT_TP + "spirv-headers",
          evidence=[("libfilament-jni.so", "Vulkan")]),
    child("filament-robin-map", "robin-map", "filament", "MIT", [stored("filament/third_party/robin-map/LICENSE")],
          FILAMENT_TP + "robin-map"),
    child("filament-civetweb", "CivetWeb", "filament", "MIT", [stored("filament/third_party/civetweb/LICENSE.md")],
          FILAMENT_TP + "civetweb", evidence=[("libfilament-utils-jni.so", "CivetWeb")]),
    child("filament-jsmn", "jsmn", "filament", "MIT", [stored("filament/third_party/jsmn/LICENSE")],
          FILAMENT_TP + "jsmn"),
]

# Liés seulement sur option : absents des .so (aucune trace), donc non affichés.
FILAMENT_ABSENT = [
    ("Perfetto", "perfetto", "lié seulement avec FILAMENT_ENABLE_PERFETTO ; aucune trace dans les .so."),
    ("Abseil", "absl", "lié seulement avec FILAMENT_USE_ABSEIL_LOGGING ; aucune trace dans les .so."),
]

# Code tiers de la bibliothèque standard Kotlin 2.4.0 (license/README.md :
# kotlin/collections, kotlin/time, UnsignedJVM.kt, MathJVM.kt).
KOTLIN_THIRD_PARTY = [
    child("kotlin-gwt", "Google Web Toolkit (kotlin.collections)", "kotlin-stdlib", "Apache 2.0",
          [stored("kotlin/third_party/gwt_license.txt")], "https://github.com/gwtproject/gwt"),
    child("kotlin-threetenbp", "ThreeTen backport (kotlin.time)", "kotlin-stdlib", "BSD 3-Clause",
          [stored("kotlin/third_party/threetenbp_license.txt")], "https://github.com/ThreeTen/threetenbp"),
    child("kotlin-guava", "Guava UnsignedLongs (kotlin.UInt, kotlin.ULong)", "kotlin-stdlib", "Apache 2.0",
          [stored("kotlin/third_party/guava_license.txt")], "https://github.com/google/guava"),
    child("kotlin-boost", "Boost.Math (kotlin.math)", "kotlin-stdlib", "Boost Software License 1.0",
          [stored("kotlin/third_party/boost_LICENSE.txt")], "https://github.com/boostorg/math"),
]


def fail(message):
    print("ERREUR : " + message, file=sys.stderr)
    sys.exit(1)


def git_blob(data):
    """Empreinte git (`git hash-object`) d'un contenu."""
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def normalize(text, origin):
    text = text.replace("\r\n", "\n").strip("\n")
    if not text.strip():
        fail("licence vide : " + origin)
    return text


def read_stored(rel):
    expected = STORED.get(rel)
    if expected is None:
        fail("Tools/licenses/%s : fichier non déclaré dans STORED" % rel)
    path = os.path.join(LICENSES_DIR, rel)
    if not os.path.exists(path):
        fail("texte de licence manquant : Tools/licenses/" + rel)
    with open(path, "rb") as f:
        data = f.read()
    found = git_blob(data)
    if found != expected:
        fail("Tools/licenses/%s : empreinte %s, attendu %s (fichier modifié ?)" % (rel, found[:8], expected[:8]))
    return data.decode("utf-8")


def strip_c_comment(text, origin):
    """Licence écrite en commentaire C (MikkTSpace) : retire « /** », « */ »
    et le « * » de début de ligne (et les espaces qui suivent) ; les mots ne
    changent pas."""
    lines = text.replace("\r\n", "\n").split("\n")
    if not lines or not lines[0].strip().startswith("/*") or not text.rstrip().endswith("*/"):
        fail("%s : commentaire C introuvable" % origin)
    out = []
    for line in lines:
        body = line.strip()
        if body.startswith("/*"):
            body = body.lstrip("/*")
        elif body.endswith("*/"):
            body = body[:-2]
        if body.startswith("*"):
            body = body[1:]
        out.append(body.strip())
    return "\n".join(out)


class Inputs:
    def __init__(self, path):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        self.components = data["components"]
        self.by_key = {}
        for c in self.components:
            key = "%s:%s" % (c["group"], c["name"])
            if key in self.by_key:
                fail("composant en double dans les entrées : " + key)
            c["files"] = sorted(set(c["files"]))
            for f in c["files"]:
                if not os.path.exists(f):
                    fail("fichier d'artefact introuvable : %s (relancez la tâche Gradle)" % f)
            self.by_key[key] = c

    def component(self, key):
        c = self.by_key.get(key)
        if c is None:
            fail("artefact absent de l'APK de release : " + key)
        return c

    def zip_entry(self, key, entry):
        for f in self.component(key)["files"]:
            with zipfile.ZipFile(f) as z:
                if entry in z.namelist():
                    return z.read(entry)
        return None

    def notices(self, key):
        """Avis third_party_licenses d'un AAR de Google : {nom: texte}."""
        for f in self.component(key)["files"]:
            with zipfile.ZipFile(f) as z:
                names = z.namelist()
                if "third_party_licenses.json" not in names:
                    continue
                index = json.loads(z.read("third_party_licenses.json").decode("utf-8"))
                blob = z.read("third_party_licenses.txt")
                out = {}
                for name, span in index.items():
                    start, length = span["start"], span["length"]
                    if start < 0 or length <= 0 or start + length > len(blob):
                        fail("%s : avis « %s » hors du fichier third_party_licenses.txt" % (key, name))
                    out[name] = normalize(blob[start:start + length].decode("utf-8"), "%s/%s" % (key, name))
                return out
        return {}

    def native_libraries(self, key):
        """Bibliothèques .so de l'AAR, toutes architectures : {nom: [contenus]}."""
        out = {}
        for f in self.component(key)["files"]:
            with zipfile.ZipFile(f) as z:
                for n in z.namelist():
                    if n.startswith("jni/") and n.endswith(".so"):
                        out.setdefault(os.path.basename(n), []).append(z.read(n))
        return out


def resolve_text(spec, inputs, origin):
    kind = spec[0]
    if kind == "file":
        return normalize(read_stored(spec[1]), spec[1])
    if kind == "c-comment":
        return normalize(strip_c_comment(read_stored(spec[1]), spec[1]), spec[1])
    if kind == "artifact-file":
        data = inputs.zip_entry(spec[1], spec[2])
        if data is None:
            fail("%s : %s introuvable dans %s" % (origin, spec[2], spec[1]))
        return normalize(data.decode("utf-8"), "%s!%s" % (spec[1], spec[2]))
    if kind == "aar-notice":
        text = inputs.notices(spec[1]).get(spec[2])
        if text is None:
            fail("%s : avis « %s » introuvable dans %s" % (origin, spec[2], spec[1]))
        return text
    if kind == "androidx":
        return inputs.androidx_text
    fail("%s : source de texte inconnue %r" % (origin, spec))


def androidx_text(inputs):
    """Texte des jar AndroidX (META-INF/androidx/**/LICENSE.txt) : tous
    identiques, sinon arrêt. Les artefacts plus anciens ne l'embarquent pas :
    même licence, même projet."""
    texts = set()
    for c in inputs.components:
        if not c["group"].startswith("androidx."):
            continue
        for f in c["files"]:
            with zipfile.ZipFile(f) as z:
                for n in z.namelist():
                    if n.startswith("META-INF/androidx/") and n.endswith("/LICENSE.txt"):
                        texts.add(normalize(z.read(n).decode("utf-8"), n))
    if len(texts) != 1:
        fail("AndroidX : %d textes de licence différents dans les jar (attendu : un seul)" % len(texts))
    text = texts.pop()
    if "Apache License" not in text or "Version 2.0" not in text:
        fail("AndroidX : le texte embarqué n'est pas la licence Apache 2.0")
    return text


def classify(inputs):
    """Rattache chaque artefact à un projet ; vérifie licences et versions."""
    projects = {p["id"]: dict(p, artifacts=[]) for p in PROJECTS}
    for key, c in sorted(inputs.by_key.items()):
        group = c["group"]
        owners = [p for p in PROJECTS if key in p["match"] or group in p["match"]]
        if group.startswith("androidx."):
            if owners:
                fail("%s : groupe AndroidX classé ailleurs" % key)
            pid = "androidx-" + group[len("androidx."):].replace(".", "-")
            if pid not in projects:
                name = ANDROIDX_GROUPS.get(group) or "AndroidX " + group.split(".")[-1].capitalize()
                projects[pid] = project(pid, name, [group], "Apache-2.0", [("androidx",)], ANDROIDX_URL,
                                        main="%s:%s" % (group, group.split(".")[-1]))
                projects[pid]["artifacts"] = []
            owners = [projects[pid]]
        if len(owners) != 1:
            fail("artefact %s : %s" % (key, "non classé (ajoutez-le à un projet de PROJECTS)" if not owners
                                       else "classé dans plusieurs projets"))
        p = projects[owners[0]["id"]]
        # Licence déclarée dans le POM (ou ses parents) = licence du projet.
        declared = set()
        for lic in c["licenses"]:
            norm = POM_LICENSES.get((lic.get("name") or "").strip())
            if norm is None:
                fail("%s : licence de POM inconnue « %s » (à classer dans POM_LICENSES)" % (key, lic.get("name")))
            declared.add(norm)
        if declared != {p["license"]}:
            fail("%s : licence(s) du POM %s, attendu %s (projet %s)" % (key, sorted(declared), p["license"], p["id"]))
        pinned = p["pinned"].get(key)
        if pinned is not None and pinned != c["version"]:
            fail("%s : version %s, les textes de Tools/licenses sont ceux de %s (mettez-les à jour, "
                 "puis l'empreinte dans STORED et la provenance dans Tools/licenses/README.md)"
                 % (key, c["version"], pinned))
        p["artifacts"].append(c)
    for p in projects.values():
        if not p["artifacts"]:
            fail("projet %s : aucun artefact dans l'APK de release (retirez-le de PROJECTS)" % p["id"])
        for key in p["pinned"]:
            if key not in {"%s:%s" % (a["group"], a["name"]) for a in p["artifacts"]}:
                fail("projet %s : artefact épinglé %s absent" % (p["id"], key))
    return projects


def build(inputs_path):
    inputs = Inputs(inputs_path)
    inputs.androidx_text = androidx_text(inputs)
    projects = classify(inputs)

    texts, text_ids = {}, {}

    def text_id_for(text, owner):
        tid = text_ids.get(text)
        if tid is None:
            tid = owner
            if tid in texts:
                fail("identifiant de texte en double : " + tid)
            text_ids[text] = tid
            texts[tid] = text
        return tid

    def compose(specs, origin):
        parts = [resolve_text(s, inputs, origin) for s in specs]
        if not parts:
            fail("%s : aucun texte de licence" % origin)
        return "\n\n".join(parts)

    components = []
    license_text_of = {}
    for pid in sorted(projects):
        p = projects[pid]
        text = compose(p["texts"], pid)
        license_text_of[pid] = text
        arts = sorted(p["artifacts"], key=lambda a: (a["group"], a["name"]))
        versions = {a["version"] for a in arts}
        # Version affichée : celle de l'artefact principal (ou de sa variante
        # Kotlin Multiplatform -android / -jvm), sinon la version commune.
        mains = [p["main"], "%s-android" % p["main"], "%s-jvm" % p["main"]] if p["main"] else []
        main = next((a for m in mains for a in arts if "%s:%s" % (a["group"], a["name"]) == m), None)
        version = main["version"] if main else (versions.pop() if len(versions) == 1 else None)
        components.append({
            "id": pid,
            "name": p["name"],
            "version": version,
            "license": LABELS[p["license"]],
            "url": p["url"],
            "sourceAvailable": p["source"],
            "artifacts": ["%s:%s:%s" % (a["group"], a["name"], a["version"]) for a in arts],
            "includedIn": [],
            "texts": [{"textId": text_id_for(text, pid), "from": []}],
        })

    for e in APP_EMBEDDED:
        text = compose(e["texts"], e["id"])
        components.append({
            "id": e["id"], "name": e["name"], "version": None, "license": e["license"], "url": e["url"],
            "sourceAvailable": e["source"], "artifacts": e["artifacts"], "includedIn": [],
            "texts": [{"textId": text_id_for(text, e["id"]), "from": []}],
        })

    # Code natif de Filament : chaîne caractéristique exigée dans les .so.
    filament_libs = {}
    for a in projects["filament"]["artifacts"]:
        for name, blobs in inputs.native_libraries("%s:%s" % (a["group"], a["name"])).items():
            filament_libs.setdefault(name, []).extend(blobs)
    for name, needle, reason in FILAMENT_ABSENT:
        for lib, blobs in filament_libs.items():
            if any(needle.encode() in b for b in blobs):
                fail("Filament : %s trouvé dans %s — à ajouter à FILAMENT_NATIVE avec sa licence" % (name, lib))
    not_embedded = [{"name": "Filament : " + name, "reason": reason} for name, _, reason in FILAMENT_ABSENT]

    for c in FILAMENT_NATIVE + KOTLIN_THIRD_PARTY:
        for lib, needle in c["evidence"]:
            blobs = filament_libs.get(lib)
            if not blobs:
                fail("%s : bibliothèque %s absente de l'AAR de Filament" % (c["id"], lib))
            if not all(needle.encode() in b for b in blobs):
                fail("%s : « %s » introuvable dans %s (composant retiré ? vérifiez les CMakeLists de Filament)"
                     % (c["id"], needle, lib))
        text = compose(c["texts"], c["id"])
        components.append({
            "id": c["id"], "name": c["name"], "version": None, "license": c["license"], "url": c["url"],
            "sourceAvailable": True, "artifacts": [], "includedIn": [c["parent"]],
            "texts": [{"textId": text_id_for(text, c["id"]), "from": []}],
        })

    # Avis de tiers fournis par Google DANS ses AAR : un composant par nom,
    # rattaché aux projets dont un AAR le fournit ; un nom aux textes
    # différents d'un AAR à l'autre garde chaque texte (avec sa provenance).
    notices = {}
    for pid in sorted(projects):
        for a in sorted(projects[pid]["artifacts"], key=lambda a: (a["group"], a["name"])):
            for name, text in sorted(inputs.notices("%s:%s" % (a["group"], a["name"])).items()):
                if name in SELF_NOTICES.get(projects[pid]["license"], ()) and text == license_text_of[pid]:
                    continue  # la licence du projet lui-même, déjà affichée
                entry = notices.setdefault(name, {"parents": [], "texts": {}})
                if pid not in entry["parents"]:
                    entry["parents"].append(pid)
                froms = entry["texts"].setdefault(text, [])
                if pid not in froms:
                    froms.append(pid)
    used_ids = {c["id"] for c in components}
    for name in sorted(notices):
        entry = notices[name]
        slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
        cid = "notice-" + slug
        n = 2
        while cid in used_ids:
            cid = "notice-%s-%d" % (slug, n)
            n += 1
        used_ids.add(cid)
        variants = sorted(entry["texts"].items(), key=lambda kv: (kv[1], kv[0]))
        components.append({
            "id": cid, "name": name, "version": None, "license": None, "url": None,
            "sourceAvailable": False, "artifacts": [], "includedIn": sorted(entry["parents"]),
            "texts": [{"textId": text_id_for(text, cid if i == 0 else "%s-%d" % (cid, i + 1)),
                       "from": sorted(froms) if len(variants) > 1 else []}
                      for i, (text, froms) in enumerate(variants)],
        })

    # Contrôle final : chaque composant a au moins un texte, tous présents.
    missing = [c["id"] for c in components
               if not c["texts"] or any(t["textId"] not in texts or not texts[t["textId"]].strip() for t in c["texts"])]
    if missing:
        fail("texte de licence manquant pour : " + ", ".join(missing))
    ids = [c["id"] for c in components]
    if len(ids) != len(set(ids)):
        fail("identifiants de composants en double")
    for c in components:
        for parent in c["includedIn"]:
            if parent not in ids:
                fail("%s : parent inconnu %s" % (c["id"], parent))

    data = {
        "format": 1,
        "generator": "Tools/generate_licenses.py",
        "components": sorted(components, key=lambda c: c["id"]),
        "notEmbedded": not_embedded,
        "texts": dict(sorted(texts.items())),
    }
    return json.dumps(data, indent=1, ensure_ascii=False) + "\n"


def run_gradle():
    gradlew = os.path.join(REPO, "gradlew")
    print("Gradle : :app:releaseLicenseInputs…")
    result = subprocess.run([gradlew, "-q", ":app:releaseLicenseInputs"], cwd=REPO)
    if result.returncode != 0:
        fail("la tâche Gradle :app:releaseLicenseInputs a échoué (JAVA_HOME, ANDROID_HOME ?)")
    return DEFAULT_INPUTS


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--inputs", help="release-components.json déjà produit (sinon : Gradle est lancé)")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    inputs = args.inputs or run_gradle()
    if not os.path.isfile(inputs):
        fail("entrées introuvables : " + inputs)
    out = build(inputs)
    rel = os.path.relpath(OUTPUT, REPO)
    if args.check:
        if not os.path.exists(OUTPUT):
            fail(rel + " n'existe pas : lancez le script.")
        with open(OUTPUT, encoding="utf-8") as f:
            if f.read() != out:
                fail(rel + " n'est plus à jour : relancez le script.")
        print(rel + " à jour.")
    else:
        os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
        with open(OUTPUT, "w", encoding="utf-8") as f:
            f.write(out)
        print("Écrit : " + rel)


if __name__ == "__main__":
    main()
