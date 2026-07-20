package com.minou.mvrviewer.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session de synchronisation persistée (reconnexion silencieuse d'un lancement à
 * l'autre) — équivalent de l'AccountKeychain iOS. Modelé sur GdtfCredentialStore
 * mais avec des PREFS et un ALIAS de clé DISTINCTS : la session sync ne doit
 * jamais se mélanger aux identifiants GDTF Share.
 *
 * Le secret (mot de passe côté stub local) est chiffré AES/GCM avec une clé non
 * exportable de l'Android Keystore ; jamais stocké en clair. L'e-mail est gardé
 * en clair comme simple marqueur.
 *
 * NB : pour le backend Firebase, c'est le SDK FirebaseAuth qui persiste la
 * session (jeton) — ce store ne sert qu'au stub local.
 */
object SyncCredentialStore {
    private const val PREFS = "mvrviewer.sync.account"
    private const val KEY_EMAIL = "email"
    private const val KEY_SECRET = "secret" // "iv:ciphertext" en base64
    private const val KS_ALIAS = "mvrviewer.sync.account.key"
    private const val KS = "AndroidKeyStore"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun email(ctx: Context): String? = prefs(ctx).getString(KEY_EMAIL, null)

    fun save(ctx: Context, email: String, secret: String) {
        runCatching {
            val enc = encrypt(secret)
            prefs(ctx).edit().putString(KEY_EMAIL, email).putString(KEY_SECRET, enc).apply()
        }
    }

    /** (email, secret) déchiffré, ou null si absent/illisible. */
    fun load(ctx: Context): Pair<String, String>? {
        val email = prefs(ctx).getString(KEY_EMAIL, null) ?: return null
        val enc = prefs(ctx).getString(KEY_SECRET, null) ?: return null
        val secret = runCatching { decrypt(enc) }.getOrNull() ?: return null
        return email to secret
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching { KeyStore.getInstance(KS).apply { load(null) }.deleteEntry(KS_ALIAS) }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(c.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(ct, Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decrypt(enc: String): String {
        val (ivB64, ctB64) = enc.split(":", limit = 2).let { it[0] to it[1] }
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}
