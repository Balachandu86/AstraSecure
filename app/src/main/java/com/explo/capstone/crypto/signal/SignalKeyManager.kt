package com.explo.capstone.crypto.signal

import android.util.Log
import com.explo.capstone.transport.MessageTransport
import java.io.IOException
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
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
        private const val INITIAL_KYBER_PREKEY_ID = 1
        private const val SPK_ROTATION_INTERVAL_MS   = 7L * 24 * 60 * 60 * 1000 // 7 days
        private const val KYBER_ROTATION_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
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

        // 3.5. Kyber-1024 pre-key (PQXDH — rotated weekly like SPK)
        val kyberPreKey = generateKyberPreKey(identityKeyPair, INITIAL_KYBER_PREKEY_ID)
        store.storeKyberPreKey(kyberPreKey.id, kyberPreKey)
        store.saveLastKyberRotationMs(System.currentTimeMillis())

        Log.i(TAG, "Signal keys generated — registrationId=$registrationId, ${oneTimePreKeys.size} OPKs, Kyber id=${kyberPreKey.id}")

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
            kyberPreKeyId = kyberPreKey.id,
            kyberPreKeyPublicBytes = kyberPreKey.keyPair.publicKey.serialize(),
            kyberPreKeySignature = kyberPreKey.signature,
        )

        if (result.isSuccess) {
            Log.i(TAG, "Registered with Signal server successfully")
        } else {
            throw IOException("SERVER_REGISTRATION_FAILED: ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * Re-uploads public key material to the server using already-generated local keys.
     * Called on startup when local keys exist but server registration never completed
     * (i.e. provisioned offline), and from the provisioning screen retry button.
     */
    suspend fun retryServerRegistration(userId: String, displayName: String) {
        if (!store.isProvisioned()) throw IllegalStateException("Signal keys not provisioned locally")

        val identityKeyPair = store.getIdentityKeyPair()
        val registrationId = store.getLocalRegistrationId()
        val spk = store.loadSignedPreKeys().maxByOrNull { it.id }
            ?: throw IllegalStateException("No signed pre-key found in store")
        val opks = store.loadAllPreKeys()
        val kyber = store.loadKyberPreKeys().maxByOrNull { it.id }
            ?: throw IllegalStateException("No Kyber pre-key found in store")

        val result = transport.register(
            userId = userId,
            displayName = displayName,
            registrationId = registrationId,
            identityKeyBytes = identityKeyPair.publicKey.serialize(),
            signedPreKeyId = spk.id,
            signedPreKeyPublicBytes = spk.keyPair.publicKey.serialize(),
            signedPreKeySignature = spk.signature,
            oneTimePreKeys = opks.map { it.id to it.keyPair.publicKey.serialize() },
            kyberPreKeyId = kyber.id,
            kyberPreKeyPublicBytes = kyber.keyPair.publicKey.serialize(),
            kyberPreKeySignature = kyber.signature,
        )

        if (result.isSuccess) {
            Log.i(TAG, "Server re-registration succeeded for userId=$userId")
        } else {
            throw IOException("SERVER_REGISTRATION_FAILED: ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * Generate and upload a fresh batch of one-time pre-keys.
     * Called when the server reports the OPK count has dropped below [LOW_PREKEY_THRESHOLD].
     */
    suspend fun replenishPreKeys() {
        val existingMax = store.loadAllPreKeys()
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

        // Remove old SPKs that are more than 48 hours old (allow in-flight decryptions to finish)
        val cutoff = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        store.loadSignedPreKeys()
            .filter { it.id != newSpk.id && it.timestamp < cutoff }
            .forEach { store.removeSignedPreKey(it.id) }

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

    /**
     * Rotate the Kyber-1024 pre-key. Should be called every 7 days, in lockstep with SPK.
     * The previous key is retained for 48 hours so in-flight PQXDH handshakes can finish.
     */
    suspend fun rotateKyberPreKey() {
        val currentIds = store.loadKyberPreKeys().map { it.id }
        val newId = (currentIds.maxOrNull() ?: 0) + 1
        val identityKeyPair = store.getIdentityKeyPair()
        val newKyber = generateKyberPreKey(identityKeyPair, newId)

        store.storeKyberPreKey(newKyber.id, newKyber)
        store.saveLastKyberRotationMs(System.currentTimeMillis())

        transport.uploadKyberPreKey(
            id = newKyber.id,
            publicKeyBytes = newKyber.keyPair.publicKey.serialize(),
            signature = newKyber.signature,
        ).onFailure { Log.w(TAG, "Kyber rotation upload failed: ${it.message}") }

        val cutoff = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        store.loadKyberPreKeys()
            .filter { it.id != newKyber.id && it.timestamp < cutoff }
            .forEach { store.removeKyberPreKey(it.id) }

        Log.i(TAG, "Kyber pre-key rotated — new id=$newId")
    }

    /** Rotate the Kyber pre-key only if more than 7 days have elapsed. Safe to call on every launch. */
    suspend fun rotateKyberPreKeyIfNeeded() {
        val lastRotated = store.getLastKyberRotationMs()
        val now = System.currentTimeMillis()
        if (now - lastRotated >= KYBER_ROTATION_INTERVAL_MS) {
            Log.i(TAG, "Kyber rotation due — rotating")
            rotateKyberPreKey()
        } else {
            val daysLeft = (KYBER_ROTATION_INTERVAL_MS - (now - lastRotated)) / (24 * 60 * 60 * 1000)
            Log.d(TAG, "Kyber rotation not due yet ($daysLeft days remaining)")
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

    private fun generateKyberPreKey(identityKeyPair: IdentityKeyPair, id: Int): KyberPreKeyRecord {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        return KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
    }
}
