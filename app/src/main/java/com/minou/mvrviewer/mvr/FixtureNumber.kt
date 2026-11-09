package com.minou.mvrviewer.mvr

/**
 * N° de projecteur (`<FixtureID>`) « SANS NUMÉRO » — règle COMMUNE iOS / Android / web
 * (MVRViewerWeb SPEC.md § « N° de projecteur sans numéro » ; vecteurs communs
 * `src/test/resources/port-vectors/numero-projecteur/vectors.json`, copie telle quelle de
 * `MVRViewerWeb/tests/fixtures/numero-projecteur/`, rejoués par FixtureNumberTest).
 *
 * Vectorworks exporte en `<FixtureID>` le MOT de son interface quand un projecteur n'a pas de
 * numéro : « Aucun » en français, « None » en anglais. Ce n'est pas un numéro : sans cette
 * règle, 16 projecteurs « Aucun » faisaient « #Aucun » dans le bandeau « N° en double ».
 *  - bords blancs retirés, puis vide ou marqueur EXACT (casse ignorée) → null = sans numéro ;
 *  - sinon la valeur telle quelle (« 0 », « Aucune », « A12 » sont des numéros).
 *
 * Appliquée À LA LECTURE ([MvrParser]), au N° EFFECTIF (`PatchOverrides.effectiveId` : un patch
 * reçu d'une version d'avant la règle peut encore porter « Aucun ») et au calcul des doublons.
 * Le .mvr n'est JAMAIS réécrit pour autant.
 */
object FixtureNumber {

    /** Marqueurs « sans numéro » connus (Vectorworks FR, EN), comparés sans la casse. */
    val PLACEHOLDERS: List<String> = listOf("Aucun", "None")
    private val placeholderKeys = PLACEHOLDERS.map { it.lowercase() }.toSet()

    /** N° utilisable, ou null s'il est absent, blanc ou un marqueur « sans numéro ». */
    fun normalize(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s.lowercase() in placeholderKeys) return null
        return s
    }

    /**
     * N° portés par PLUSIEURS projecteurs, triés dans l'ordre numérique ([ORDER]). Chaque valeur
     * passe par [normalize] (sans numéro → ignoré) ; la comparaison est EXACTE (la casse compte).
     *
     * PORTÉE VOULUE (identique iOS / web) : l'appelant passe les N° de TOUT le projet — liste
     * filtrée ou non, calques masqués compris. Un doublon est une erreur de patch du show entier.
     */
    fun duplicates(rawIds: Iterable<String?>): List<String> =
        rawIds.mapNotNull { normalize(it) }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
            .sortedWith(ORDER)

    /** Liste du bandeau : « #201, #606 » (texte autour : ressource `patch_duplicate_ids`). */
    fun bannerList(ids: List<String>): String = ids.joinToString(", ") { "#$it" }

    /**
     * Ordre d'affichage des N° : les suites de chiffres comparées par VALEUR (« 9 » avant « 10 »,
     * « B9 » avant « B10 »), le reste sans la casse — l'ordre du web (Intl.Collator numérique)
     * et d'iOS (`.numeric`) sur les N° usuels ; égalité départagée par l'ordre binaire.
     */
    val ORDER: Comparator<String> = Comparator { a, b ->
        val c = naturalCompare(a, b)
        if (c != 0) c else a.compareTo(b)
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (isDigit(a[i]) && isDigit(b[j])) {
                var ie = i; while (ie < a.length && isDigit(a[ie])) ie++
                var je = j; while (je < b.length && isDigit(b[je])) je++
                var ia = i; while (ia < ie - 1 && a[ia] == '0') ia++   // zéros de tête ignorés
                var jb = j; while (jb < je - 1 && b[jb] == '0') jb++
                val la = ie - ia
                val lb = je - jb
                if (la != lb) return la - lb
                for (k in 0 until la) {
                    val d = a[ia + k] - b[jb + k]
                    if (d != 0) return d
                }
                i = ie; j = je
            } else {
                val d = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (d != 0) return d
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
