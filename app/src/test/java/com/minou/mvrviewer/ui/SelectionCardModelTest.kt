package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import com.minou.mvrviewer.sync.PowerResolution
import com.minou.mvrviewer.sync.PowerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fiche de sélection et bannière multi (SPEC-menus-android §3.9, tranche D) : ce
 * qu'elles DISENT, sur les vrais `strings.xml` fr / en — titre « Projecteur — Nom »,
 * détails localisés, conso (« 450 W · 3 votes », « 1 vote », GDTF, aucune), « C'est
 * bon », pluriel de la bannière, « Éditer » de l'univers — et le branchement des
 * deux vues sur la MÊME fiche.
 */
class SelectionCardModelTest {

    private fun CardText.fr(): String = render(TestRes.FR)
    private fun CardText.en(): String = render(TestRes.EN)
    private fun CardText.render(r: TestRes): String = when (this) {
        is CardText.Res -> r.res(id, args)
        is CardText.Plural -> r.plural(pluralName(id), count, *args.toTypedArray())
    }
    private fun pluralName(id: Int): String =
        R.plurals::class.java.fields.first { it.getInt(null) == id }.name

    private fun src(name: String): String =
        listOf(File("src/main/java/com/minou/mvrviewer/ui/$name"), File("app/src/main/java/com/minou/mvrviewer/ui/$name"))
            .first { it.isFile }.readText()

    private fun model(
        id: String? = "101", addresses: List<String> = listOf("1"), spec: String? = "Acme@Wash Zoom.gdtf",
        resolution: PowerResolution = PowerResolution(450, PowerSource.LIBRARY), votes: Int? = 3
    ) = SelectionCardModels.of(
        key = "uuid-101", name = "Wash 101", layer = "Face", fixtureId = id, addresses = addresses,
        spec = spec, resolution = resolution, votes = votes
    )

    // ------------------------------------------------------------------ titre

    @Test fun titreTypeNomEtTitreCourt() {
        val fr = TestRes.FR.get("card_title_fmt", TestRes.FR.get("card_kind_fixture"), "Wash 101")
        val en = TestRes.EN.get("card_title_fmt", TestRes.EN.get("card_kind_fixture"), "Wash 101")
        assertEquals("Projecteur — Wash 101", fr)
        assertEquals("Fixture — Wash 101", en)
        // Titre court (fiche compacte) : le nom sans le genre d'objet, dans les deux langues.
        assertEquals("Wash 101", SelectionCardLayout.shortTitle(fr))
        assertEquals("Wash 101", SelectionCardLayout.shortTitle(en))
        // Le séparateur du titre est celui que retire le titre court.
        assertTrue(TestRes.FR.get("card_title_fmt").contains(SelectionCardModels.TITLE_SEPARATOR))
        assertTrue(TestRes.EN.get("card_title_fmt").contains(SelectionCardModels.TITLE_SEPARATOR))
    }

    // ---------------------------------------------------------------- détails

    @Test fun detailsLocalisesDansLOrdreIos() {
        val m = model(addresses = listOf("1", "513", "1025"))
        assertEquals(listOf("Calque : Face", "N° de projecteur : 101", "Adresse DMX : 1.1, 2.1, 3.1"), m.details.map { it.fr() })
        assertEquals(listOf("Layer: Face", "Fixture number: 101", "DMX address: 1.1, 2.1, 3.1"), m.details.map { it.en() })
        // Adresse déjà « univers.adresse » : gardée telle quelle.
        assertEquals("Adresse DMX : 3.17", model(addresses = listOf("3.17")).details.last().fr())
    }

    @Test fun detailsAbsentsQuandLaDonneeManque() {
        // Ni N° ni adresse : seulement le calque (on n'affiche rien plutôt qu'un tiret).
        val m = model(id = null, addresses = emptyList())
        assertEquals(listOf("Calque : Face"), m.details.map { it.fr() })
        assertEquals(listOf("Calque : Face"), model(id = "  ", addresses = listOf(" ")).details.map { it.fr() })
    }

    @Test fun adresseEditeeDansLePatchRemplaceCellesDuFichier() {
        assertEquals(listOf("1", "513"), SelectionCardModels.addresses(listOf("1", "513"), "1"))
        assertEquals(listOf("2.40"), SelectionCardModels.addresses(listOf("1", "513"), "2.40"))
        assertEquals(listOf("1"), SelectionCardModels.addresses(listOf("1"), null))
        assertEquals(listOf("1.5"), SelectionCardModels.addresses(emptyList(), "1.5"))
    }

    // ------------------------------------------------------------------- conso

    @Test fun consoVotesGdtfEtAucune() {
        val votes = model().power
        assertEquals("450 W · 3 votes", votes.label!!.fr())
        assertEquals("450 W · 3 votes", votes.label!!.en())
        assertTrue(votes.canConfirm)
        // Pluriel porté par le NOMBRE DE VOTES.
        assertEquals("450 W · 1 vote", model(votes = 1).power.label!!.fr())
        assertEquals("450 W · 1 vote", model(votes = 1).power.label!!.en())
        // Consensus sans compte connu : « 1 vote » (comme l'écran Câblage).
        assertEquals("450 W · 1 vote", model(votes = null).power.label!!.fr())
        // GDTF : même libellé qu'iOS (`confidenceLabel`) et que l'écran Câblage.
        val gdtf = model(resolution = PowerResolution(450, PowerSource.GDTF), votes = null).power
        assertEquals("450 W · GDTF", gdtf.label!!.fr())
        assertTrue(gdtf.canConfirm)
        // Aucune valeur : « Conso max : à saisir », pas de « C'est bon ».
        val none = model(resolution = PowerResolution(null, PowerSource.NONE), votes = null).power
        assertNull(none.label)
        assertFalse(none.canConfirm)
        assertEquals("Conso max : à saisir", TestRes.FR.get("card_power_to_enter"))
        assertEquals("Max power: to be entered", TestRes.EN.get("card_power_to_enter"))
        // Valeur connue mais pas de type : rien sur quoi voter.
        assertFalse(model(spec = null).power.canConfirm)
        assertFalse(model(spec = " ").power.canConfirm)
    }

    @Test fun cestBonGardeLooksRightEnAnglais() {
        // §8 D22 : « Done » se confondrait avec « Terminé ».
        assertEquals("C'est bon", TestRes.FR.get("fx_power_confirm"))
        assertEquals("Looks right", TestRes.EN.get("fx_power_confirm"))
        assertEquals("Patch", TestRes.FR.get("patch_title"))
        assertEquals("Patch", TestRes.EN.get("patch_title"))
        assertEquals("Détails", TestRes.FR.get("card_details"))
        assertEquals("Details", TestRes.EN.get("card_details"))
        assertEquals("Fermer", TestRes.FR.get("common_close"))
    }

    // ---------------------------------------------------------------- bannière

    @Test fun banniereAuPluriel() {
        assertEquals("1 projecteur sélectionné", TestRes.FR.plural("sel_fixtures_selected", 1, 1))
        assertEquals("2 projecteurs sélectionnés", TestRes.FR.plural("sel_fixtures_selected", 2, 2))
        assertEquals("1 fixture selected", TestRes.EN.plural("sel_fixtures_selected", 1, 1))
        assertEquals("12 fixtures selected", TestRes.EN.plural("sel_fixtures_selected", 12, 12))
        assertEquals("Vider la sélection", TestRes.FR.get("banner_clear_selection"))
        assertEquals("Clear selection", TestRes.EN.get("banner_clear_selection"))
        // L'ancienne clé sans pluriel et la pastille « N° · nom » de la 3D ont disparu.
        for (r in listOf(TestRes.FR, TestRes.EN)) {
            assertFalse(r.strings.containsKey("sel_fixtures_selected_fmt"))
            assertFalse(r.strings.containsKey("fixture_id_name_fmt"))
        }
    }

    // ------------------------------------------------------ « Éditer » de l'univers

    private fun patch(key: String, id: String?, name: String, start: Int) =
        DmxPatch(key, id, name, "Face", "Mode 1", 1, start, 10, emptyList())

    @Test fun univers_unEditerParProjecteur() {
        // Chevauchement : deux projecteurs au même canal = deux boutons, sans ambiguïté.
        val overlap = listOf(patch("a", "101", "Wash A", 1), patch("b", null, "Spot B", 5))
        assertEquals(listOf(UniverseEditRules.Target("a", "101"), UniverseEditRules.Target("b", "Spot B")),
            UniverseEditRules.targets(overlap))
        // Une même instance listée deux fois (deux adresses) : un seul bouton.
        assertEquals(1, UniverseEditRules.targets(listOf(patch("a", "101", "Wash A", 1), patch("a", "101", "Wash A", 3))).size)
        assertTrue(UniverseEditRules.targets(emptyList()).isEmpty())
        assertEquals("Éditer", TestRes.FR.get("universe_edit"))
        assertEquals("Edit", TestRes.EN.get("universe_edit"))
        assertEquals("Éditer le patch du projecteur 101", TestRes.FR.get("universe_edit_cd_fmt", "101"))
        assertEquals("Edit the patch of fixture 101", TestRes.EN.get("universe_edit_cd_fmt", "101"))
    }

    @Test fun univers_editerOuvreLaFichePatch() {
        val uni = src("DmxUniverseScreen.kt")
        assertTrue(uni.contains("UniverseEditRules.targets(list)"))
        assertTrue(uni.contains("onClick = { onEdit(p.fixtureKey) }"))
        // La clé de l'empreinte retrouve l'objet par la clé d'instance commune.
        assertTrue(uni.contains("scene.fixtures.associateBy { mvrInstanceKey(it) }"))
        val scene = src("SceneScreen.kt")
        assertTrue(scene.contains("onEditFixture = { editFixture = it },\n                    onBack = { closeDestination() }"))
    }

    // ------------------------------------------------- UNE fiche pour les deux vues

    @Test fun memeFicheEtMemeBanniereDansLesDeuxVues() {
        val scene = src("SceneScreen.kt")
        // Construites UNE fois par SceneScreen…
        assertEquals(1, Regex("SelectionCardHost\\(").findAll(scene).count())
        assertEquals(2, Regex("selectionCard = selectionCardHost").findAll(scene).count())
        // … Patch = la fiche patch de CE projecteur ; « C'est bon » = mon vote.
        assertTrue(scene.contains("onPatch = { editFixture = obj }"))
        assertTrue(scene.contains("if (s != null && w != null) power.set(s, w)"))
        // Les deux vues posent la fiche de l'hôte ; la croix désélectionne.
        val d3 = src("Scene3DScreen.kt")
        assertTrue(d3.contains("cardHost3D.card(one, cardStyle3D) { selected.clear() }"))
        assertTrue(d3.contains("cardHost3D.banner(selected.size, cardStyle3D) { selected.clear() }"))
        val plan = src("PlanScreen.kt")
        assertTrue(plan.contains("cardHost.card(one, cardStylePlan) { selected.clear() }"))
        // Plus de pastille de texte en 3D, plus de fiche texte dans le plan.
        assertFalse(d3.contains("selectionPill3DPx"))
        assertFalse(plan.contains("append(f.id?.let { \"#\$it  \" } ?: \"\")"))
        // L'écran Câblage et la fiche partagent le libellé de conso.
        assertTrue(src("CablingScreen.kt").contains("SelectionCardModels.powerLabel(resolution, votes)"))
        // Rien pendant la mesure, en 3D comme dans le plan.
        assertTrue(d3.contains("SelectionCardLayout.presentation(selected.size, measuring = measureMode)"))
    }

    @Test fun crochetAutoSelectDansLesDeuxVues() {
        val hooks = src("DebugHooks.kt")
        assertTrue(hooks.contains("MVR_AUTO_SELECT"))
        assertTrue(hooks.contains("fun takeAutoSelect(): String? = autoSelect.also { autoSelect = null }"))
        assertEquals(2, Regex("takeAutoSelect = \\{ DebugHooks.takeAutoSelect\\(\\) \\}").findAll(src("SceneScreen.kt")).count())
    }
}
