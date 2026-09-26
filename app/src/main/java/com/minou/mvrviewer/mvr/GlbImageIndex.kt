package com.minou.mvrviewer.mvr

/**
 * Résolution des IMAGES EXTERNES d'un `.glb` (`images[].uri`) vers les fichiers
 * image embarqués dans le MVR — pendant Android de `resolveTexture` dans
 * `GLBFastDecoder` iOS.
 *
 * La vue 3D confie les `.glb` à Filament (gltfio), qui lit lui-même les images
 * EMBARQUÉES (`bufferView`) et honore `alphaMode` (MASK/BLEND : feuillages
 * découpés, vitrages). Il ne manque à Filament que les images EXTERNES, qu'on
 * lui fournit par un résolveur `uri → octets`. Or l'`uri` écrite par
 * l'exportateur ne coïncide pas toujours avec le nom de l'entrée zip : espaces
 * encodés (`Arbre%20Chene.png`), sous-dossier (`textures/feuille.png`), casse
 * (`Feuille.PNG` / `feuille.png`). Une clé stricte rate alors la texture, et
 * l'objet retombe sur sa couleur de base : un arbre devient une croix grise.
 *
 * Ordre de résolution : nom de base décodé, nom de base brut, puis les mêmes
 * SANS tenir compte de la casse. Les `data:` URI sont laissées à gltfio.
 *
 * THREAD : immuable après construction, fonction pure — testable en JVM.
 */
class GlbImageIndex(private val images: Map<String, ByteArray>) {

    /** Index par nom de base MINUSCULE, construit à la première demande. */
    private val lower: Map<String, ByteArray> by lazy {
        HashMap<String, ByteArray>(images.size).also { m ->
            for ((k, v) in images) m.putIfAbsent(baseName(k).lowercase(), v)
        }
    }

    val isEmpty: Boolean get() = images.isEmpty()

    fun resolve(uri: String): ByteArray? {
        if (images.isEmpty() || uri.isEmpty() || uri.startsWith("data:", ignoreCase = true)) return null
        val raw = baseName(uri)
        val dec = baseName(percentDecode(uri))
        return images[dec] ?: images[raw]
            ?: lower[dec.lowercase()] ?: lower[raw.lowercase()]
    }

    companion object {
        /** Dernier segment de chemin, séparateurs `/` ET `\` (exports Windows). */
        fun baseName(path: String): String =
            path.substringAfterLast('/').substringAfterLast('\\')

        /**
         * Décodage `%XX` en UTF-8, SANS transformer `+` en espace (ce n'est pas
         * un formulaire : `URLDecoder` casserait « a+b.png »). Une séquence
         * invalide est gardée telle quelle.
         */
        fun percentDecode(s: String): String {
            if (s.indexOf('%') < 0) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi shl 4) or lo)
                        i += 3
                        continue
                    }
                }
                // Paire de substitution (emoji…) : les 2 unités ensemble, sinon
                // chacune donnerait « ? » en UTF-8.
                val n = if (Character.isHighSurrogate(c) && i + 1 < s.length) 2 else 1
                val bytes = s.substring(i, i + n).toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i += n
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
