package com.minou.mvrviewer.mvr

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
 * Identifiants GDTF Share persistés, pour rester connecté d'un lancement à
 * l'autre (équivalent du Keychain iOS). Le mot de passe est chiffré AES/GCM
 * avec une clé NON exportable de l'Android Keystore (adossée au matériel) ; on
 * ne stocke jamais le mot de passe en clair. Le nom d'utilisateur est gardé en
 * clair comme simple marqueur (« un compte est enregistré »).
 *
 * L'app capture ces identifiants depuis SON PROPRE champ de connexion, à la
 * demande de l'utilisateur (« rester connecté ») — jamais saisis autrement.
 */
object GdtfCredentialStore {
    private const val PREFS = "mvrviewer.gdtfshare"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"   // "iv:ciphertext" en base64
    private const val KS_ALIAS = "mvrviewer.gdtfshare.key"
    private const val KS = "AndroidKeyStore"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Nom d'utilisateur enregistré (null si aucun) — marqueur non sensible. */
    fun username(ctx: Context): String? = prefs(ctx).getString(KEY_USER, null)

    fun save(ctx: Context, user: String, password: String) {
        runCatching {
            val enc = encrypt(password)
            prefs(ctx).edit().putString(KEY_USER, user).putString(KEY_PASS, enc).apply()
        }
    }

    /** (user, password) déchiffré, ou null si absent/illisible. */
    fun load(ctx: Context): Pair<String, String>? {
        val user = prefs(ctx).getString(KEY_USER, null) ?: return null
        val enc = prefs(ctx).getString(KEY_PASS, null) ?: return null
        val pass = runCatching { decrypt(enc) }.getOrNull() ?: return null
        return user to pass
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
