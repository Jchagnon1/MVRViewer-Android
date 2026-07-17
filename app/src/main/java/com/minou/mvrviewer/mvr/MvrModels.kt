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
    val layerName: String
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
}

class MvrParseException(message: String) : Exception(message)
