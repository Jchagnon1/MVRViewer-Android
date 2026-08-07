package com.minou.mvrviewer.mvr

/**
 * Modèle de scène MVR — portage Kotlin de MVRScene (iOS). Une scène est un
 * ensemble de calques (Layer) contenant des objets (fixtures, trusses…), plus
 * une table de définitions de symboles (Symdef) référencées par UUID.
 *
 * Les transforms 4x4 sont stockées en COLONNE-MAJEUR (16 floats), comme en
 * SceneKit/OpenGL : colonnes 0..2 = base rotation/échelle, colonne 3 = translation.
 */

/** Matrice 4x4 colonne-majeur. */
class Mat4(val m: FloatArray = identityValues()) {
    init { require(m.size == 16) { "Mat4 attend 16 floats" } }
    companion object {
        fun identityValues() = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        val IDENTITY get() = Mat4()
    }
    /** Translation (colonne 3). */
    val translation: FloatArray get() = floatArrayOf(m[12], m[13], m[14])
}

/** Type d'objet de scène (même vocabulaire que le MVR). */
enum class MvrObjectKind(val xml: String) {
    FIXTURE("Fixture"),
    TRUSS("Truss"),
    SCENE_OBJECT("SceneObject"),
    SUPPORT("Support"),
    VIDEO_SCREEN("VideoScreen"),
    PROJECTOR("Projector"),
    UNKNOWN("Unknown");

    companion object {
        fun from(xml: String): MvrObjectKind =
            entries.firstOrNull { it.xml == xml } ?: UNKNOWN
    }
}

/** Référence de géométrie : un fichier (.3ds/.glb) OU un symbole (par UUID). */
sealed interface MvrGeometryRef {
    val transform: Mat4
    data class File(val fileName: String, override val transform: Mat4) : MvrGeometryRef
    data class Symbol(val symdefUuid: String, override val transform: Mat4) : MvrGeometryRef
}

/** Définition de symbole réutilisable (dans AUXData). */
data class MvrSymdef(val items: List<MvrGeometryRef>)

/** Un objet de la scène (projecteur, truss, décor…). */
data class MvrSceneObject(
    val uuid: String?,
    val name: String,
    val kind: MvrObjectKind,
    val transform: Mat4,
    val geometryRefs: List<MvrGeometryRef>,
    val gdtfSpec: String?,
    val gdtfMode: String?,
    val fixtureId: String?,
    val addresses: List<String>,
    val layerName: String,
    /**
     * Nom de la CLASSE Vectorworks (`<Classing>` résolu). Beaucoup d'exports
     * enveloppent le vrai contenu dans des objets nommés génériquement
     * « Géométrie » : c'est alors CE champ, pas `name`, qui porte l'information
     * exploitable (« 00 STAGE - MOTHER GRILL », « 02 LIGHT - WASH »…).
     */
    val className: String? = null
) {
    val isFixture: Boolean get() = kind == MvrObjectKind.FIXTURE
}

/** Un calque. */
data class MvrLayer(val name: String, val objects: List<MvrSceneObject>)

/** La scène complète. */
data class MvrScene(
    val layers: List<MvrLayer>,
    val symdefs: Map<String, MvrSymdef>
) {
    val allObjects: List<MvrSceneObject> get() = layers.flatMap { it.objects }
    val fixtures: List<MvrSceneObject> get() = allObjects.filter { it.isFixture }

    /**
     * Scène PRIVÉE des objets dont la classe est masquée.
     *
     * Filtrer À LA SOURCE, et pas dans chaque moteur de rendu : la vue 3D et la
     * vue plan partent toutes deux de la scène, donc elles héritent du masquage
     * sans qu'on aille plumber un index de classes dans la boucle de
     * construction 3D (groupée par FICHIER, pas par objet, et sensible aux
     * performances — elle a un historique d'ANR). Un seul point de filtrage,
     * donc aucun oubli possible.
     *
     * Un objet SANS classe n'est jamais masqué par ce filtre.
     */
    fun hidingClasses(hidden: Set<String>): MvrScene {
        if (hidden.isEmpty()) return this
        return MvrScene(
            layers.map { layer ->
                MvrLayer(layer.name, layer.objects.filter { o ->
                    val c = o.className
                    c.isNullOrEmpty() || c !in hidden
                })
            },
            symdefs
        )
    }

    /**
     * CLASSES Vectorworks présentes (nom + nombre d'objets), triées par effectif
     * décroissant puis par nom. Pendant exact de `layers` pour le panneau de
     * visibilité. Les objets sans classe n'y figurent pas — rien à activer.
     *
     * ⚠️ Parcourt TOUS les objets : à mémoïser côté UI, jamais à appeler dans
     * une recomposition.
     */
    fun classCounts(): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        for (layer in layers) {
            for (o in layer.objects) {
                val c = o.className
                if (c.isNullOrEmpty()) continue
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }
}

/**
 * Échec de lecture d'un .mvr. `message` reste TECHNIQUE (journal, rapport de
 * diagnostic) ; [messageRes] porte la phrase AFFICHABLE, localisée à l'écran —
 * le parseur n'a pas de `Context` et n'a donc pas à choisir une langue.
 */
class MvrParseException(
    message: String,
    @androidx.annotation.StringRes val messageRes: Int? = null
) : Exception(message)

/** Adresse DMX « univers.adresse » — portage de DMXAddressFormatter (iOS). */
object DmxAddress {
    fun format(raw: String): String {
        val t = raw.trim()
        if (t.contains(".")) return t          // déjà « u.a »
        val abs = t.toIntOrNull() ?: return t
        if (abs <= 0) return t
        val universe = (abs - 1) / 512 + 1
        val address = (abs - 1) % 512 + 1
        return "$universe.$address"
    }
}
