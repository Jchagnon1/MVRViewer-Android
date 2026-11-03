# Textes de licence recopiés (écran « Mentions et licences », guide C11)

Lus par `Tools/generate_licenses.py`, qui vérifie l'empreinte git de chaque
fichier (`git hash-object`, table `STORED` du script) : un fichier modifié, ou
d'une autre version que celle empaquetée, arrête le script.

Chaque fichier est une copie À L'IDENTIQUE de la source ci-dessous, à la
version empaquetée dans l'APK de release (relevé du 3 novembre 2026). Les
textes que les artefacts embarquent eux-mêmes (LICENSE.txt des jar AndroidX,
META-INF/LICENSE.txt de checker-qual, avis `third_party_licenses` des AAR de
Google) ne sont PAS recopiés ici : le script les lit dans les artefacts.

| Fichier | Source |
|---|---|
| `apache-2.0/LICENSE-2.0.txt` | https://www.apache.org/licenses/LICENSE-2.0.txt (javax.inject 1, jsr305 3.0.2, com.google.android:annotations 4.1.1.4 : licence Apache 2.0 déclarée dans le POM, aucun fichier de licence publié avec le projet) |
| `kotlin/LICENSE.txt`, `kotlin/NOTICE.txt` | https://github.com/JetBrains/kotlin/tree/v2.4.0/license |
| `kotlin/third_party/*` | https://github.com/JetBrains/kotlin/tree/v2.4.0/license/third_party (code tiers de la bibliothèque standard, d'après `license/README.md` : `kotlin/collections` GWT, `kotlin/time` ThreeTen backport, `UnsignedJVM.kt` Guava, `MathJVM.kt` Boost) |
| `kotlinx-coroutines/LICENSE.txt`, `NOTICE.txt` | https://github.com/Kotlin/kotlinx.coroutines/tree/1.9.0 (`LICENSE.txt`, `license/NOTICE.txt`) |
| `kotlinx-serialization/LICENSE.txt`, `NOTICE.txt` | https://github.com/Kotlin/kotlinx.serialization/tree/v1.7.3 (`LICENSE.txt`, `license/NOTICE.txt`) |
| `jetbrains-annotations/LICENSE.txt` | https://github.com/JetBrains/java-annotations/blob/23.0.0/LICENSE.txt |
| `guava/LICENSE` | https://github.com/google/guava/blob/v32.1.3/LICENSE (aussi failureaccess et listenablefuture, du même dépôt) |
| `gson/LICENSE` | https://github.com/google/gson/blob/gson-parent-2.10.1/LICENSE |
| `error-prone/COPYING` | https://github.com/google/error-prone/blob/v2.26.0/COPYING |
| `protobuf/LICENSE` | https://github.com/protocolbuffers/protobuf/blob/v25.1/LICENSE (protobuf-javalite 3.25.1) |
| `grpc-java/LICENSE`, `NOTICE.txt` | https://github.com/grpc/grpc-java/tree/v1.62.2 |
| `perfmark/LICENSE`, `NOTICE` | https://github.com/perfmark/perfmark/tree/v0.26.0 |
| `okio/LICENSE.txt` | https://github.com/square/okio/blob/parent-3.4.0/LICENSE.txt |
| `jspecify/LICENSE` | https://github.com/jspecify/jspecify/blob/v1.0.0/LICENSE |
| `kotlin-math/LICENSE` | https://github.com/romainguy/kotlin-math/blob/v1.8.0/LICENSE |
| `sceneview/LICENSE` | https://github.com/sceneview/sceneview/blob/v4.22.0/LICENSE |
| `filament/LICENSE` | https://github.com/google/filament/blob/v1.71.5/LICENSE |
| `filament/third_party/<nom>/<fichier>` | https://github.com/google/filament/tree/v1.71.5/third_party (même chemin) |
| `filament/third_party/mikktspace/mikktspace.c-header.txt` | lignes 4 à 22 de https://github.com/google/filament/blob/v1.71.5/third_party/mikktspace/src/mikktspace.c (la licence n'existe que dans ce commentaire) |
| `fuel/LICENSE.md` | https://github.com/kittinunf/fuel/blob/2.3.1/LICENSE.md |
| `result/LICENSE.md` | https://github.com/kittinunf/Result/blob/3.1.0/LICENSE.md |
| `firebase-android-sdk/LICENSE` | https://github.com/firebase/firebase-android-sdk/blob/releases/m157/LICENSE (le dépôt n'a pas d'étiquette de version ; fichier inchangé d'une version à l'autre) |
| `animal-sniffer/LICENSE` | https://github.com/mojohaus/animal-sniffer/blob/a58d59bc17/LICENSE (seule version du fichier, ajouté en 2020, avant la 1.23) |
| `ezdxf/LICENSE` | https://github.com/mozman/ezdxf/blob/v1.4.5/LICENSE (palette ACI de `DxfParser.kt`, extraite d'ezdxf) |

Code natif de Filament 1.71.5 retenu (CMakeLists de `android/filament-android`,
`android/gltfio-android`, `android/filament-utils-android` et des bibliothèques
qu'ils lient, à cette version) : cgltf, Draco, Basis Universal, meshoptimizer,
MikkTSpace, stb, libwebp, Zstandard, SMOL-V, Vulkan Memory Allocator,
SPIRV-Headers, robin-map, CivetWeb, jsmn. Perfetto et Abseil, liés seulement
sur option, ne laissent aucune trace dans les `.so` (le script le vérifie).

Mise à jour d'une dépendance : le script s'arrête et nomme le fichier à
reprendre ; recopiez-le depuis la nouvelle version, puis mettez à jour son
empreinte dans `STORED`, la version épinglée dans `PROJECTS` et cette page.
