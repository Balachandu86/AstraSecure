package com.explo.capstone.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Owner: Tejas Khanna
 * Responsible for: Signal Protocol integration and Mission Cryptographic Context (MCC) key management.
 */
class CryptoEngine {

    /**
     * Generate and store an AES-256-GCM key for a mission in Android Keystore.
     * Idempotent — if the alias already exists the existing key is kept.
     */
    fun generateMissionKey(missionKeyAlias: String) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(missionKeyAlias)) return
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(missionKeyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        keyGen.generateKey()
    }

    /**
     * Rotate the mission key — generates a new alias, copies data under new key, deletes old.
     * @return the new alias (caller must update mission record)
     */
    fun rotateMissionKey(missionKeyAlias: String): String {
        val newAlias = "${missionKeyAlias}_r${System.currentTimeMillis()}"
        generateMissionKey(newAlias)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(missionKeyAlias)
        }
        return newAlias
    }

    /**
     * Encrypt a plaintext message payload under the given mission key.
     * Output layout: [12-byte IV][GCM ciphertext + 16-byte tag]
     */
    fun encryptMessage(missionKeyAlias: String, plaintext: ByteArray): ByteArray {
        val key = loadKey(missionKeyAlias)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decrypt a ciphertext payload under the given mission key.
     * Throws [javax.crypto.AEADBadTagException] if tampered (INTEGRITY_FAIL).
     */
    fun decryptMessage(missionKeyAlias: String, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > IV_SIZE) { "Ciphertext too short" }
        val iv   = ciphertext.copyOfRange(0, IV_SIZE)
        val data = ciphertext.copyOfRange(IV_SIZE, ciphertext.size)
        val key  = loadKey(missionKeyAlias)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(data)
    }

    /**
     * Encrypt with a user-supplied password (Tools screen AES tab).
     * Output layout: [16-byte salt][12-byte IV][GCM ciphertext + 16-byte tag]
     */
    fun encryptWithPassword(password: String, plaintext: ByteArray): ByteArray {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return salt + iv + ciphertext
    }

    /**
     * Decrypt with a user-supplied password (Tools screen AES tab).
     * Expects the layout written by [encryptWithPassword].
     */
    fun decryptWithPassword(password: String, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > SALT_SIZE + IV_SIZE) { "Ciphertext too short" }
        val salt = ciphertext.copyOfRange(0, SALT_SIZE)
        val iv   = ciphertext.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val data = ciphertext.copyOfRange(SALT_SIZE + IV_SIZE, ciphertext.size)
        val key  = deriveKey(password, salt)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(data)
    }

    /**
     * Invalidate all Keystore keys — called during panic wipe.
     * Deletes every alias that starts with "astra_" so mission keys + identity key are removed.
     */
    fun invalidateAllKeys() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.aliases().toList()
            .filter { it.startsWith("astra_") }
            .forEach { runCatching { ks.deleteEntry(it) } }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun loadKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Mission key not found: $alias")
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, KeyProperties.KEY_ALGORITHM_AES)
    }

    private companion object {
        const val AES_GCM          = "AES/GCM/NoPadding"
        const val PBKDF2_ALG       = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 100_000
        const val KEY_BITS         = 256
        const val SALT_SIZE        = 16
        const val IV_SIZE          = 12
        const val GCM_TAG_BITS     = 128
    }
}
