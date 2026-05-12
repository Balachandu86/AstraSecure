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
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Metadata for a single vault entry. */
data class VaultEntry(
    val docId: String,
    val missionId: String,
    val fileName: String,
    val createdMs: Long,
)

/**
 * Owner: Sandrani Balachandu
 * Responsible for: Hardware-bound user identity via Android Keystore and encrypted document vault.
 */
class IdentityManager(private val context: Context) {

    // Warm cache: documentId → encrypted payload (12-byte IV + GCM ciphertext)
    private val docCache = mutableMapOf<String, ByteArray>()

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
     * Update the operator's display name (callsign).
     */
    fun updateDisplayName(newName: String) {
        getEncryptedPrefs().edit().putString("display_name", newName).apply()
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
     * Encrypted bytes are persisted to disk so they survive process restarts.
     */
    fun storeDocument(missionId: String, fileName: String, fileBytes: ByteArray): EncryptedDocument {
        val docId = "DOC-${UUID.randomUUID().toString().take(8).uppercase()}"
        val keyAlias = "astra_doc_$docId"
        val createdMs = System.currentTimeMillis()

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

        // Persist encrypted bytes to disk
        vaultFile(docId).also { it.parentFile?.mkdirs() }.writeBytes(payload)

        // Persist metadata: missionId|createdMs|fileName (filename last so | in names is safe)
        getEncryptedPrefs().edit()
            .putString("vault_meta_$docId", "$missionId|$createdMs|$fileName")
            .apply()

        // Warm the cache
        docCache[docId] = payload

        val userId = runCatching { getUserIdentity()?.id }.getOrElse { null } ?: "UNKNOWN"
        return EncryptedDocument(
            id = docId,
            missionId = missionId,
            ownerUserId = userId,
            encryptedBytes = payload,
            fileName = fileName,
            createdAtMs = createdMs,
        )
    }

    /**
     * Retrieve and decrypt a document from the vault.
     * Falls back to disk if the warm cache is cold (e.g. after a process restart).
     */
    fun retrieveDocument(documentId: String): ByteArray {
        val payload = docCache[documentId]
            ?: vaultFile(documentId).takeIf { it.exists() }?.readBytes()?.also { docCache[documentId] = it }
            ?: throw NoSuchElementException("Document $documentId not found in vault")
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

    /** Returns all vault entries from the persisted manifest. */
    fun getVaultEntries(): List<VaultEntry> {
        val prefs = runCatching { getEncryptedPrefs() }.getOrNull() ?: return emptyList()
        return prefs.all.entries
            .filter { it.key.startsWith("vault_meta_") }
            .mapNotNull { (key, value) ->
                val docId = key.removePrefix("vault_meta_")
                // Format: missionId|createdMs|fileName (limit=3 so | in filename is safe)
                val parts = (value as? String)?.split("|", limit = 3) ?: return@mapNotNull null
                if (parts.size < 3) return@mapNotNull null
                VaultEntry(
                    docId = docId,
                    missionId = parts[0],
                    createdMs = parts[1].toLongOrNull() ?: 0L,
                    fileName = parts[2],
                )
            }
    }

    /** Delete a single vault entry from disk, cache, manifest, and Keystore. */
    fun deleteVaultEntry(docId: String) {
        vaultFile(docId).delete()
        docCache.remove(docId)
        runCatching {
            getEncryptedPrefs().edit().remove("vault_meta_$docId").apply()
        }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("astra_doc_$docId")
        }
    }

    /**
     * Revoke remote tokens. The actual server DELETE is called directly by PanicViewModel
     * before this method, so no HTTP work is needed here.
     */
    fun revokeRemoteTokens() {
        // Server-side deletion is handled by PanicViewModel via serverClient.deleteUser()
    }

    /**
     * Wipe all identity data — clears EncryptedSharedPreferences, identity keys,
     * vault files, and Keystore aliases.
     */
    fun wipeAll() {
        val keyAlias = runCatching {
            getEncryptedPrefs().getString("hardware_key_id", null)
        }.getOrNull()

        // Delete all vault files and their per-doc Keystore keys
        val ks = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }.getOrNull()
        getVaultEntries().forEach { entry ->
            vaultFile(entry.docId).delete()
            runCatching { ks?.deleteEntry("astra_doc_${entry.docId}") }
        }
        vaultDir().deleteRecursively()
        docCache.clear()

        context.deleteSharedPreferences("astra_identity")

        if (keyAlias != null) {
            runCatching { ks?.deleteEntry(keyAlias) }
        }

        // Remove the MasterKey so a future fresh install gets a new one
        runCatching { ks?.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS) }
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

    /**
     * Remove the tombstone marker. Only used by the debug reset flow — never call
     * this in production code; the tombstone is intentionally permanent after a wipe.
     */
    fun clearTombstone() {
        context.getSharedPreferences("astra_state", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun vaultDir() = File(context.filesDir, "vault")
    private fun vaultFile(docId: String) = File(vaultDir(), "$docId.bin")

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
