package com.minou.mvrviewer

import com.minou.mvrviewer.mvr.SceneModelLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Finitions avant l'envoi sur Google Play (guide C7 et C9), vérifiées dans les
 * fichiers du projet : signature de release lue hors du dépôt, garde qui refuse une
 * release incomplète, version, écran Pro aligné sur les formats réellement lus.
 * (La garde elle-même est prouvée en lançant `:app:bundleRelease` avec et sans
 * keystore.properties ; ce test vérifie qu'elle est en place.)
 */
class ReleaseReadinessTest {

    private fun file(vararg candidates: String): File = candidates.map(::File).first { it.isFile }
    private val gradle by lazy { file("build.gradle.kts", "app/build.gradle.kts").readText() }

    @Test fun versionDeLEnvoi() {
        assertTrue(gradle.contains("versionCode = 21\n"))
        assertTrue(gradle.contains("versionName = \"1.10.0\"\n"))
    }

    @Test fun signatureLueDansKeystorePropertiesIgnoreParGit() {
        assertTrue(gradle.contains("rootProject.file(\"keystore.properties\")"))
        for (k in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) assertTrue(k, gradle.contains("\"$k\""))
        assertTrue(gradle.contains("if (releaseSigningReady) signingConfig = signingConfigs.getByName(\"release\")"))
        val ignore = file("../.gitignore", ".gitignore").readLines().map { it.trim() }
        for (p in listOf("keystore.properties", "*.jks", "*.keystore")) assertTrue("ignoré : $p", p in ignore)
        // Le modèle versionné : les quatre clés, AUCUN secret.
        val example = Properties().apply {
            file("../keystore.properties.example", "keystore.properties.example").reader(Charsets.UTF_8).use { load(it) }
        }
        assertEquals(setOf("storeFile", "storePassword", "keyAlias", "keyPassword"), example.stringPropertyNames())
        assertEquals("A_REMPLACER", example.getProperty("storePassword"))
        assertEquals("A_REMPLACER", example.getProperty("keyPassword"))
        assertFalse("clé rangée hors du dépôt", example.getProperty("storeFile").contains("MVRViewerAndroid"))
    }

    @Test fun gardeDeRelease() {
        // Empaqueter une release sans clé complète ou sans google-services.json échoue,
        // avant toute compilation, avec un message en français ; le debug n'est pas concerné.
        assertTrue(gradle.contains("gradle.taskGraph.whenReady {"))
        for (t in listOf("\"bundleRelease\"", "\"assembleRelease\"", "\"installRelease\"")) assertTrue(t, gradle.contains(t))
        assertFalse(gradle.contains("\"assembleDebug\", ") || gradle.contains("\"bundleDebug\""))
        assertTrue(gradle.contains("keystore.properties est introuvable"))
        assertTrue(gradle.contains("app/google-services.json est introuvable"))
        assertTrue(gradle.contains("if (!project.file(\"google-services.json\").isFile) add("))
    }

    @Test fun signatureDeLAssistantAndroidStudioAcceptee() {
        // L'assistant « Generate Signed App Bundle » (chemin du README et du guide C7)
        // passe la clé par android.injected.signing.* : la garde ne réclame alors plus
        // keystore.properties, mais toujours google-services.json. (Prouvé aussi en
        // lançant `:app:bundleRelease --dry-run` avec ces quatre propriétés : rc = 0.)
        for (p in listOf("store.file", "store.password", "key.alias", "key.password")) {
            assertTrue(p, gradle.contains("\"android.injected.signing.$p\""))
        }
        assertTrue(gradle.contains(
            "injectedSigningProperties.all { !providers.gradleProperty(it).orNull.isNullOrBlank() }"))
        val guard = gradle.substringAfter("fun releasePrerequisiteProblems()").substringBefore("\nandroid {")
        assertTrue(guard.contains("if (injectedSigningReady) {"))
        assertTrue("keystore.properties seulement sans clé de l'assistant",
            guard.indexOf("if (injectedSigningReady)") < guard.indexOf("} else if (!keystorePropertiesFile.isFile) {"))
        // google-services.json reste exigé dans tous les cas (hors du if / else de la clé).
        assertTrue(guard.substringAfterLast("    }\n").contains("if (!project.file(\"google-services.json\").isFile) add("))
        // Le .aab rangé par l'assistant (app/release/) n'est jamais versionné.
        val ignore = file("../.gitignore", ".gitignore").readLines().map { it.trim() }
        assertTrue("app/release/" in ignore)
    }

    @Test fun ecranProSansCollada() {
        val paywall = file("src/main/java/com/minou/mvrviewer/ui/ProPaywall.kt",
            "app/src/main/java/com/minou/mvrviewer/ui/ProPaywall.kt").readText()
        assertFalse("Android n'importe pas le COLLADA", paywall.contains("COLLADA"))
        // Ligne traduite (values/ et values-en/), formats passés en argument.
        assertTrue(paywall.contains("R.string.pro_feature_models_fmt to listOf(SceneModelLoader.SUPPORTED_SUMMARY)"))
        assertEquals("Importer des objets 3D (glTF/GLB, OBJ, FBX, STL, PLY)",
            com.minou.mvrviewer.ui.TestRes.FR.get("pro_feature_models_fmt", SceneModelLoader.SUPPORTED_SUMMARY))
        assertEquals("Import 3D objects (glTF/GLB, OBJ, FBX, STL, PLY)",
            com.minou.mvrviewer.ui.TestRes.EN.get("pro_feature_models_fmt", SceneModelLoader.SUPPORTED_SUMMARY))
        // La liste affichée = les formats que le décodeur sait lire.
        assertEquals("glTF/GLB, OBJ, FBX, STL, PLY", SceneModelLoader.SUPPORTED_SUMMARY)
        assertFalse(SceneModelLoader.SUPPORTED_SUMMARY.contains("COLLADA"))
    }
}
