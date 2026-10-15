package com.minou.mvrviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minou.mvrviewer.R

/**
 * Réglages d'affichage partagés entre les vues d'un même show (fond, couleurs
 * par calque, étiquettes, décor). Équivalent des @State d'affichage de
 * ContentView iOS, hissés au niveau du show.
 */
/**
 * Contenu affiché dans l'étiquette d'un projecteur (comme iOS LabelField).
 *
 * SOCAPEX et DMX_LINE (phase 4 câblage) dépendent de l'état de câblage RUNTIME
 * (hors PlanFixture) : leur texte est résolu par un `cablingText` injecté dans
 * `labelFieldText` / `labelBlocks`. Un projecteur non affecté n'affiche rien pour
 * ces champs (résolveur → null). Ils apparaissent AUTOMATIQUEMENT dans le picker
 * (boucle `LabelContent.entries`).
 */
enum class LabelContent(@androidx.annotation.StringRes val labelRes: Int) {
    ID(R.string.label_field_id), DMX(R.string.label_field_dmx), MODE(R.string.label_field_mode), NAME(R.string.label_field_name),
    SOCAPEX(R.string.label_field_socapex), DMX_LINE(R.string.label_field_dmx_line)
}

/**
 * Mode de coloration des projecteurs en vue PLAN UNIQUEMENT (phase 4 câblage).
 *
 * ⚠️ DISTINCT de `SceneOptions.layerColors`, qui est PARTAGÉ avec la 3D : le
 * détourner colorerait aussi la 3D. LAYER = comportement historique (couleur par
 * calque si `layerColors`, sinon gris neutre) ; SOCAPEX / DMX_LINE colorent chaque
 * projecteur par la couleur de SON distributeur câblage. Non affecté = gris neutre.
 * Libellés/ordre alignés iOS (CablingColorMode : off / soca / dmx).
 */
enum class PlanColorMode(@androidx.annotation.StringRes val labelRes: Int) {
    LAYER(R.string.facet_layer), SOCAPEX(R.string.label_field_socapex), DMX_LINE(R.string.label_field_dmx_line)
}

class SceneOptions {
    var backgroundDark by mutableStateOf(true)
    // Couleur de fond choisie par l'utilisateur, par vue (défauts noir / blanc,
    // comme iOS). Semées depuis BackgroundColorStore et persistées dans SceneScreen.
    var background3D by mutableStateOf(BackgroundColorStore.DEFAULT_3D)
    var background2D by mutableStateOf(BackgroundColorStore.DEFAULT_2D)
    var layerColors by mutableStateOf(true)
    /**
     * Mode de coloration PLAN (calque / Socapex / ligne DMX) — état PLAN-ONLY,
     * volontairement séparé de `layerColors` (partagé 3D). Voir [PlanColorMode].
     */
    var planColorMode by mutableStateOf(PlanColorMode.LAYER)
    var showLabels by mutableStateOf(true)
    var showStructure by mutableStateOf(true)
    var showLegend by mutableStateOf(true)        // légende des calques (vue plan)
    /**
     * Champs AFFICHÉS dans l'étiquette. Plusieurs à la fois : le n° seul ne suffit
     * pas au terrain (« n° + patch »). Ils sont mis en page une LIGNE PAR CHAMP —
     * une étiquette large chevauche ses voisines, une étiquette haute non.
     */
    var labelFields by mutableStateOf(setOf(LabelContent.ID, LabelContent.DMX))
    /**
     * Champs DÉTACHÉS de la pastille commune : chacun devient un bloc autonome,
     * avec son propre placement et son propre décalage déplaçable au doigt (le
     * n° au-dessus de l'icône, le patch en dessous). Les autres restent groupés.
     */
    var labelDetached by mutableStateOf(emptySet<LabelContent>())
    var labelSize by mutableFloatStateOf(1f)     // 0.7 (S) · 1.0 (M) · 1.4 (L)
    var labelOffset by mutableFloatStateOf(1f)    // écart étiquette ↔ projecteur
    /**
     * Option EXPLICITE « masquer les étiquettes quand c'est trop dézoomé »,
     * DÉSACTIVÉE par défaut : par défaut, une étiquette affichée reste visible
     * à tout niveau de zoom. On ne rebranche le seuil de lisibilité que si
     * l'utilisateur le coche (filet pour un très gros show illisible dézoomé).
     */
    var hideLabelsWhenZoomedOut by mutableStateOf(false)
    // Fond satellite géo-référencé (sous le plan / en 3D) — nécessite la
    // calibration GPS. Persisté par projet ; opacité de session (défaut 0.55).
    // Source : IGN, en France seulement (SPEC-satellite-ign.md).
    var showSatellite by mutableStateOf(false)
    var satelliteOpacity by mutableFloatStateOf(0.55f)
    /**
     * Compteur d'ACTIVATIONS par l'utilisateur (outil, menu) : distingue un geste
     * explicite — qui force une nouvelle demande et affiche un Toast en cas d'échec
     * — d'une valeur restaurée avec le projet ou reçue du partage (silencieuses).
     */
    var satelliteActivations by mutableIntStateOf(0)

    /** Bascule du fond satellite par un GESTE de l'utilisateur. */
    fun toggleSatelliteByUser() {
        showSatellite = !showSatellite
        if (showSatellite) satelliteActivations++
    }
    // Taille du marqueur « ma position » en 3D (multiplicateur, S 0.6 · M 1 · L 1.6).
    var gpsMarkerScale by mutableFloatStateOf(1f)
}
