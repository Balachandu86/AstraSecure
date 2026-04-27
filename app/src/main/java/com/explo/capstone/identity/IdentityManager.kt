package com.explo.capstone.identity

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.explo.capstone.shared.EncryptedDocument
import com.explo.capstone.shared.User
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owner: Sandrani Balachandu
 * Responsible for: Hardware-bound user identity via Android Keystore and encrypted document vault.
 */
class IdentityManager(private val context: Context) {

    // In-memory vault: documentId → encrypted payload (12-byte IV + GCM ciphertext)
    private val docVault = mutableMapOf<String, ByteArray>()
    private val docMeta  = mutableMapOf<String, Triple<String, String, Long>>() // id → (missionId, fileName, createdMs)

    /**
     * First-time setup: create a hardware-backed key pair and persist a User record.
     * @param displayName the user's chosen name
     * @return the provisioned User object
     */
    fun provisionIdentity(displayName: String): User {
        val keyAlias = "astra_identity_${UUID.randomUUID()}"
        generateKeystoreKeyPair(keyAlias)

        val user = User(
            id = UUID.randomUUID().toString(),
            hardwareKeyId = keyAlias,
            displayName = displayName,
            provisionedAtMs = System.currentTimeMillis(),
        )

        getEncryptedPrefs().edit()
            .putString("user_id", user.id)
            .putString("hardware_key_id", user.hardwareKeyId)
            .putString("display_name", user.displayName)
            .putLong("provisioned_at_ms", user.provisionedAtMs)
            .apply()

        return user
    }

    /**
     * Retrieve the current device's User identity.
     */
    fun getUserIdentity(): User? {
        val prefs = runCatching { getEncryptedPrefs() }.getOrNull() ?: return null
        val id = prefs.getString("user_id", null) ?: return null
        return User(
            id = id,
            hardwareKeyId = prefs.getString("hardware_key_id", "") ?: "",
            displayName = prefs.getString("display_name", "") ?: "",
            provisionedAtMs = prefs.getLong("provisioned_at_ms", 0L),
        )
    }

    /**
     * Get device attestation status for the Security Dashboard.
     */
    fun deviceAttestation(): DeviceAttestation {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val hasStrongBox = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val keyAlias = runCatching {
            getEncryptedPrefs().getString("hardware_key_id", null)
        }.getOrNull()

        return DeviceAttestation(
            isDeviceSecure = km.isDeviceSecure,
            hasStrongBox = hasStrongBox,
            keystoreType = if (hasStrongBox) "STRONGBOX" else "TEE",
            hardwareKeyId = keyAlias,
        )
    }

    /**
     * Encrypt and store a document in the local Keystore-backed vault.
     * Each document gets its own AES-256-GCM key under alias "astra_doc_{id}".
     */
    fun storeDocument(missionId: String, fileName: String, fileBytes: ByteArray): EncryptedDocument {
        val docId = "DOC-${UUID.randomUUID().toString().take(8).uppercase()}"
        val keyAlias = "astra_doc_$docId"

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        keyGen.generateKey()

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(keyAlias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(fileBytes)
        val payload = iv + encrypted

        docVault[docId] = payload
        docMeta[docId]  = Triple(missionId, fileName, System.currentTimeMillis())

        val userId = runCatching { getUserIdentity()?.id }.getOrElse { null } ?: "UNKNOWN"
        return EncryptedDocument(
            id = docId,
            missionId = missionId,
            ownerUserId = userId,
            encryptedBytes = payload,
            fileName = fileName,
            createdAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Retrieve and decrypt a document from the vault.
     * Throws [NoSuchElementException] if the document was never stored in this session.
     */
    fun retrieveDocument(documentId: String): ByteArray {
        val payload  = docVault[documentId] ?: throw NoSuchElementException("Document $documentId not in vault")
        val keyAlias = "astra_doc_$documentId"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(keyAlias, null) as? SecretKey
            ?: throw IllegalStateException("Document key not found: $keyAlias")
        val iv   = payload.copyOfRange(0, 12)
        val data = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    /**
     * Revoke remote tokens — no-op for capstone, real impl later.
     */
    fun revokeRemoteTokens() {
        // No-op for capstone — no remote auth tokens to revoke
    }

    /**
     * Wipe all identity data — clears EncryptedSharedPreferences + identity.
     */
    fun wipeAll() {
        val keyAlias = runCatching {
            getEncryptedPrefs().getString("hardware_key_id", null)
        }.getOrNull()

        context.deleteSharedPreferences("astra_identity")

        if (keyAlias != null) {
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(keyAlias)
            }
        }

        // Remove the MasterKey so a future fresh install gets a new one
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }

    /**
     * Write a terminal tombstone marker — the LAST mutation during panic wipe.
     * Written to plain SharedPreferences("astra_state") so it survives identity wipe.
     */
    fun writeTombstone() {
        context.getSharedPreferences("astra_state", Context.MODE_PRIVATE)
            .edit().putBoolean("terminal", true).apply()
    }

    /**
     * Read the tombstone marker — called first on every app launch.
     * @return true if the device has been wiped and should show TerminatedScreen
     */
    fun readTombstone(): Boolean =
        context.getSharedPreferences("astra_state", Context.MODE_PRIVATE)
            .getBoolean("terminal", false)

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun generateKeystoreKeyPair(alias: String) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

        fun spec(strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(strongBox)
                .build()

        // Try StrongBox-backed first; fall back to TEE if unavailable on this device
        try {
            gen.initialize(spec(true))
            gen.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            gen.initialize(spec(false))
            gen.generateKeyPair()
        }
    }

    private fun getEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "astra_identity",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

/**
 * Device attestation status for the Security Dashboard.
 */
data class DeviceAttestation(
    val isDeviceSecure: Boolean,
    val hasStrongBox: Boolean,
    val keystoreType: String,
    val hardwareKeyId: String?,
)
