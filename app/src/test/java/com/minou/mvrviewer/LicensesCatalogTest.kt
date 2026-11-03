package com.minou.mvrviewer

import com.minou.mvrviewer.ui.TestRes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * « Mentions et licences » (guide de mise en vente C11) : le catalogue EMBARQUÉ
 * (assets/third_party_licenses.json, régénéré par Tools/generate_licenses.py)
 * n'a aucun texte manquant, couvre les composants qu'on sait empaquetés, se
 * cherche sans tenir compte des accents ; la mise en page ne perd aucun mot ;
 * aucune mention de l'éditeur (Confess Design) ni « © » dans l'interface.
 */
class LicensesCatalogTest {

    private fun dir(vararg candidates: String): File = candidates.map(::File).first { it.isDirectory }
    private val main by lazy { dir("src/main", "app/src/main") }
    private val json by lazy { File(main, "assets/${ThirdPartyLicenseCatalog.ASSET}").readText() }
    private val catalog by lazy { ThirdPartyLicenseCatalog.parse(json) }

    @Test fun aucunTexteManquant() {
        assertEquals(emptyList<String>(), catalog.missingTexts())
        for (c in catalog.components) {
            assertTrue("${c.id} : au moins un texte", c.texts.isNotEmpty())
            for (ref in c.texts) assertTrue("${c.id} : texte ${ref.textId}", !catalog.text(ref.textId).isNullOrBlank())
            for (p in c.includedIn) assertNotNull("${c.id} : parent $p", catalog.component(p))
        }
        assertEquals(catalog.components.size, catalog.components.map { it.id }.toSet().size)
        // Premier niveau : licence nommée et adresse (code source, ou page d'un SDK fermé).
        for (c in catalog.topLevel()) {
            assertNotNull("${c.id} : licence", c.license)
            assertTrue("${c.id} : adresse", c.url.orEmpty().startsWith("https://"))
        }
        val root = JSONObject(json)
        assertEquals("Tools/generate_licenses.py", root.getString("generator"))
    }

    @Test fun composantsEmpaquetesPresents() {
        val artifacts = catalog.components.flatMap { it.artifacts }.map { it.substringBeforeLast(':') }.toSet()
        for (a in listOf(
            "com.google.android.filament:filament-android", "com.google.android.filament:gltfio-android",
            "io.github.sceneview:sceneview", "com.google.firebase:firebase-auth", "com.google.firebase:firebase-firestore",
            "com.google.firebase:firebase-storage", "com.android.billingclient:billing", "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm", "androidx.compose.ui:ui-android",
            "androidx.compose.material3:material3-android", "androidx.core:core", "io.grpc:grpc-okhttp",
            "com.google.protobuf:protobuf-javalite", "com.google.android.gms:play-services-basement"
        )) assertTrue("empaqueté : $a", a in artifacts)
        // Licences exactes de quelques projets ; code natif de Filament ; SDK fermés sans « code source ».
        assertEquals("Apache 2.0", catalog.component("filament")?.license)
        assertEquals("BSD 3-Clause", catalog.component("protobuf")?.license)
        assertEquals("MIT", catalog.component("fuel")?.license)
        assertEquals("Android Software Development Kit License", catalog.component("play-billing")?.license)
        assertEquals(false, catalog.component("play-billing")?.sourceAvailable)
        assertEquals(true, catalog.component("filament")?.sourceAvailable)
        val filamentChildren = catalog.children(catalog.component("filament")!!).map { it.id }
        for (id in listOf("filament-draco", "filament-basisu", "filament-cgltf", "filament-zstd", "filament-civetweb"))
            assertTrue(id, id in filamentChildren)
        // Texte complet : la licence Apache 2.0 entière, pas un lien.
        val apache = catalog.text(catalog.component("filament")!!.texts.single().textId)!!
        assertTrue(apache.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(apache.contains("END OF TERMS AND CONDITIONS"))
        // Avis NOTICE en tête (Apache 2.0 §4(d)).
        assertTrue(catalog.text(catalog.component("grpc-java")!!.texts.single().textId)!!.startsWith("Copyright 2014 The gRPC Authors"))
    }

    @Test fun rechercheSansAccentsNiCasse() {
        val top = catalog.displayed("")
        assertTrue(top.all { it.isTopLevel })
        assertEquals(top.map { ThirdPartyLicenseCatalog.fold(it.name) }, top.map { ThirdPartyLicenseCatalog.fold(it.name) }.sorted())
        assertTrue(catalog.displayed("FILAMENT").any { it.id == "filament" })
        // Un composant inclus se trouve par le nom de son parent, avec la mention « Inclus dans ».
        assertTrue(catalog.displayed("filament").any { it.id == "filament-draco" })
        assertTrue(catalog.displayed("mit").all { c ->
            listOf(c.name, c.license.orEmpty()).plus(c.artifacts).plus(catalog.parents(c).map { it.name })
                .any { ThirdPartyLicenseCatalog.fold(it).contains("mit") }
        })
        // Le composant NOMMÉ passe avant les dizaines d'avis que son SDK apporte.
        assertEquals("play-billing", catalog.displayed("billing").first().id)
        assertEquals("filament", catalog.displayed("filament").first().id)
        assertEquals("ecran", ThirdPartyLicenseCatalog.fold("Écran"))
        assertTrue(catalog.displayed("zzz-aucun-composant").isEmpty())
    }

    @Test fun miseEnPageSansPerteDeMots() {
        fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (c in catalog.components.take(40)) for (ref in c.texts) {
            val text = catalog.text(ref.textId)!!
            assertEquals(c.id, words(text), words(LicenseTextLayout.reflow(text)))
        }
        val sample = "Permission is hereby granted, free of charge, to any person\nobtaining a copy.\n\n1. First\n2) Second\n- item\n-----\nEnd"
        assertEquals(
            "Permission is hereby granted, free of charge, to any person obtaining a copy.\n\n1. First\n2) Second\n- item\n-----\nEnd",
            LicenseTextLayout.reflow(sample)
        )
    }

    /** L'éditeur n'est JAMAIS nommé dans l'app (seulement dans les pages légales du site), et aucun « © ». */
    @Test fun aucuneSignatureDEditeur() {
        val all = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()
        assertTrue(all.size > 50)
        for (f in all) assertFalse("${f.name} : Confess", f.readText().contains("Confess", ignoreCase = true))
        // Textes et code de l'INTERFACE : ressources, écrans (ui/), liens légaux et catalogue.
        val ui = all.filter { f ->
            f.extension == "xml" || f.path.contains("/ui/") || f.name in setOf("LegalLinks.kt", "ThirdPartyLicenses.kt")
        }
        for (f in ui) {
            val t = f.readText()
            assertFalse("${f.name} : ©", t.contains("©") || t.contains("&#169;") || t.contains("\\u00a9", ignoreCase = true))
        }
        // Le catalogue lui-même ne nomme pas l'éditeur (les licences de tiers gardent leurs propres « © »).
        assertFalse(json.contains("Confess", ignoreCase = true))
        // En-tête de l'écran : le nom de l'app et « Version … », rien d'autre.
        assertEquals("Version 1.10.0 (21)", TestRes.FR.get("licenses_version_fmt", "1.10.0 (21)"))
        assertEquals("Version 1.10.0 (21)", TestRes.EN.get("licenses_version_fmt", "1.10.0 (21)"))
    }

    @Test fun sourcesDeDonneesEtTextesFrEn() {
        val expected = mapOf(
            "licenses_section_data" to ("Données et services" to "Data and services"),
            "licenses_ign_title" to ("Photographies aériennes en France" to "Aerial photographs in France"),
            "licenses_ign_detail" to ("Source : IGN – BD ORTHO® · Licence Ouverte 2.0 (Etalab)" to
                "Source: IGN – BD ORTHO® · Open Licence 2.0 (Etalab)"),
            "licenses_gdtf_title" to ("Profils de projecteurs" to "Fixture profiles"),
            "licenses_gdtf_detail" to ("GDTF Share, service de VPLT e.V. Les profils GDTF appartiennent à leurs auteurs." to
                "GDTF Share, a service of VPLT e.V. GDTF profiles belong to their authors."),
            "licenses_search" to ("Rechercher un composant" to "Search for a component"),
            "licenses_section_components" to ("Composants logiciels" to "Software components"),
            "licenses_source_code" to ("Code source" to "Source code")
        )
        for ((k, v) in expected) {
            assertEquals("fr $k", v.first, TestRes.FR.get(k))
            assertEquals("en $k", v.second, TestRes.EN.get(k))
        }
        assertEquals("1 composant inclus", TestRes.FR.plural("licenses_children", 1, 1))
        assertEquals("14 included components", TestRes.EN.plural("licenses_children", 14, 14))
        // Sources de données réellement appelées par le code Android : IGN (satellite,
        // France) et GDTF Share (l'ancienne imagerie mondiale : SatelliteRequestTest).
        val code = File(main, "java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val hosts = Regex("https://([a-z0-9.-]+)").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("data.geopf.fr", "cartes.gouv.fr", "gdtf-share.com", "mvrviewer.com", "mvrviewer-app.web.app"), hosts)
    }
}
