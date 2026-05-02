package com.explo.capstone.crypto.signal

import android.util.Log
import com.explo.capstone.transport.MessageTransport
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.SecureRandom

/**
 * Owner: Tejas Khanna
 *
 * Generates and manages Signal Protocol key material.
 * Called once from [com.explo.capstone.identity.IdentityManager.provisionIdentity].
 *
 * Key material generated:
 *   - IdentityKeyPair (Curve25519, permanent, stored in AstraSignalProtocolStore)
 *   - RegistrationId (14-bit random int, permanent)
 *   - SignedPreKey (Curve25519, rotated weekly via [rotateSignedPreKey])
 *   - 100 one-time PreKeys (Curve25519, consumed one-per-session, replenished via [replenishPreKeys])
 */
class SignalKeyManager(
    private val store: AstraSignalProtocolStore,
    private val transport: MessageTransport,
) {

    companion object {
        private const val TAG = "SignalKeyManager"
        private const val INITIAL_PREKEY_COUNT = 100
        private const val REPLENISH_BATCH_SIZE = 100
        private const val LOW_PREKEY_THRESHOLD = 10
        private const val INITIAL_SIGNED_PREKEY_ID = 1
        private const val SPK_ROTATION_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }

    /**
     * Full first-time Signal provisioning. Generates all key material, persists
     * private halves locally, and registers public halves with the server.
     * Fails gracefully — if the server is unreachable the keys are still stored
     * locally and registration is retried on the next app launch.
     */
    suspend fun provision(userId: String, displayName: String) {
        if (store.isProvisioned()) {
            Log.d(TAG, "Already provisioned — skipping")
            return
        }

        // 1. Identity key pair (permanent)
        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = generateRegistrationId()

        store.saveIdentityKeyPair(identityKeyPair)
        store.saveRegistrationId(registrationId)

        // 2. Signed pre-key (medium-term, rotated weekly)
        val signedPreKey = generateSignedPreKey(identityKeyPair, INITIAL_SIGNED_PREKEY_ID)
        store.storeSignedPreKey(signedPreKey.id, signedPreKey)

        // 3. One-time pre-keys (consumed once, replenished in batches)
        val oneTimePreKeys = generatePreKeys(startId = 1, count = INITIAL_PREKEY_COUNT)
        oneTimePreKeys.forEach { store.storePreKey(it.id, it) }

        Log.i(TAG, "Signal keys generated — registrationId=$registrationId, ${oneTimePreKeys.size} OPKs")

        // 4. Upload public material to server
        val result = transport.register(
            userId = userId,
            displayName = displayName,
            registrationId = registrationId,
            identityKeyBytes = identityKeyPair.publicKey.serialize(),
            signedPreKeyId = signedPreKey.id,
            signedPreKeyPublicBytes = signedPreKey.keyPair.publicKey.serialize(),
            signedPreKeySignature = signedPreKey.signature,
            oneTimePreKeys = oneTimePreKeys.map { it.id to it.keyPair.publicKey.serialize() },
        )

        if (result.isSuccess) {
            Log.i(TAG, "Registered with Signal server successfully")
        } else {
            Log.w(TAG, "Server registration failed (will retry): ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * Generate and upload a fresh batch of one-time pre-keys.
     * Called when the server reports the OPK count has dropped below [LOW_PREKEY_THRESHOLD].
     */
    suspend fun replenishPreKeys() {
        val existingMax = store.loadSignedPreKeys()
            .mapNotNull { runCatching { it.id }.getOrNull() }
            .maxOrNull() ?: 0
        val newKeys = generatePreKeys(
            startId = (existingMax + 1).coerceAtLeast(store.preKeyCount() + 1),
            count = REPLENISH_BATCH_SIZE,
        )
        newKeys.forEach { store.storePreKey(it.id, it) }

        transport.uploadPreKeys(newKeys.map { it.id to it.keyPair.publicKey.serialize() })
            .onFailure { Log.w(TAG, "OPK replenish upload failed: ${it.message}") }

        Log.i(TAG, "Replenished ${newKeys.size} one-time pre-keys")
    }

    /**
     * Rotate the signed pre-key. Should be called every 7 days.
     * The previous SPK is retained in the store for 48 hours to cover in-flight
     * messages encrypted to the old SPK; callers are responsible for scheduling deletion.
     */
    suspend fun rotateSignedPreKey() {
        val currentIds = store.loadSignedPreKeys().map { it.id }
        val newId = (currentIds.maxOrNull() ?: 0) + 1
        val identityKeyPair = store.getIdentityKeyPair()
        val newSpk = generateSignedPreKey(identityKeyPair, newId)

        store.storeSignedPreKey(newSpk.id, newSpk)
        store.saveLastSpkRotationMs(System.currentTimeMillis())

        transport.uploadSignedPreKey(
            id = newSpk.id,
            publicKeyBytes = newSpk.keyPair.publicKey.serialize(),
            signature = newSpk.signature,
        ).onFailure { Log.w(TAG, "SPK rotation upload failed: ${it.message}") }

        Log.i(TAG, "Signed pre-key rotated — new id=$newId")
    }

    /**
     * Rotate the signed pre-key only if more than 7 days have elapsed since the last rotation.
     * Safe to call on every app foreground — it is a no-op when rotation is not yet due.
     */
    suspend fun rotateSignedPreKeyIfNeeded() {
        val lastRotated = store.getLastSpkRotationMs()
        val now = System.currentTimeMillis()
        if (now - lastRotated >= SPK_ROTATION_INTERVAL_MS) {
            Log.i(TAG, "SPK rotation due (last=${lastRotated}, now=$now) — rotating")
            rotateSignedPreKey()
        } else {
            val daysLeft = (SPK_ROTATION_INTERVAL_MS - (now - lastRotated)) / (24 * 60 * 60 * 1000)
            Log.d(TAG, "SPK rotation not due yet ($daysLeft days remaining)")
        }
    }

    // ─── Private generators ───────────────────────────────────────────────────

    private fun generateRegistrationId(): Int {
        // Signal spec: 14-bit random non-zero integer (1–16380)
        return (SecureRandom().nextInt(16380) + 1)
    }

    private fun generateSignedPreKey(
        identityKeyPair: IdentityKeyPair,
        id: Int,
    ): SignedPreKeyRecord {
        val keyPair = ECKeyPair.generate()
        val signature = identityKeyPair.privateKey.calculateSignature(
            keyPair.publicKey.serialize(),
        )
        return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
    }

    private fun generatePreKeys(startId: Int, count: Int): List<PreKeyRecord> =
        (startId until startId + count).map { id ->
            PreKeyRecord(id, ECKeyPair.generate())
        }
}
