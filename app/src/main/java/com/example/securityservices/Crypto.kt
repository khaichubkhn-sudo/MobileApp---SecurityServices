package com.example.securityservices

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal framework-only crypto for stored secrets.
 *
 * - App-login password: stored as salted SHA-256 hash (one-way, never reversible).
 *   Forgetting it cannot be recovered — reinstall (which wipes app-private data
 *   because android:allowBackup="false") is the only reset.
 * - Email (SMTP app) password: stored AES/GCM encrypted with a device key in the
 *   AndroidKeyStore, so SharedPreferences never holds it in plain text. The key
 *   never leaves the KeyStore; only IV + ciphertext (Base64) is stored.
 *
 * No third-party libraries on purpose — Android framework only.
 */
object Crypto {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "security_services_key"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val HASH_SALT_BYTES = 16
    private const val ENC_PREFIX = "ENC:v1:"

    // ------------------------------------------------------------ key management

    private fun getOrCreateKey(): SecretKey {
        return try {
            val ks = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: createKey()
        } catch (e: Exception) {
            createKey()
        }
    }

    private fun createKey(): SecretKey {
        return try {
            val keyGen = KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (e: Exception) {
            // Very old / broken KeyStore: fall back to an in-memory key (obfuscation only).
            // Data is still not plain text in prefs, but will not survive process death.
            fallbackKey()
        }
    }

    @Volatile
    private var fallback: SecretKey? = null

    private fun fallbackKey(): SecretKey {
        fallback?.let { return it }
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256, SecureRandom())
        val k = gen.generateKey()
        fallback = k
        return k
    }

    // ------------------------------------------------------------ reversible encryption

    /** Encrypts [plain]; empty input stays empty. Output is "ENC:v1:<base64(iv+cipher)>". */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = iv + cipherBytes
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /** Decrypts values produced by [encrypt]. Returns "" when missing/corrupt. */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(ENC_PREFIX)) {
            // Legacy plain-text value from before encryption was added.
            return stored
        }
        return try {
            val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.DEFAULT)
            if (combined.size <= GCM_IV_BYTES) return ""
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val cipherBytes = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------ one-way password hash

    fun newSalt(): String {
        val salt = ByteArray(HASH_SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    /** Salted SHA-256 hash, hex-encoded. Never store the login password itself. */
    fun hashPassword(password: String, saltBase64: String): String {
        return try {
            val salt = Base64.decode(saltBase64, Base64.DEFAULT)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.update(salt)
            md.update(password.toByteArray(Charsets.UTF_8))
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    fun verifyPassword(password: String, saltBase64: String, expectedHash: String): Boolean {
        if (password.isEmpty() || saltBase64.isEmpty() || expectedHash.isEmpty()) return false
        val actual = hashPassword(password, saltBase64)
        if (actual.length != expectedHash.length) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].code xor expectedHash[i].code)
        return diff == 0
    }
}
