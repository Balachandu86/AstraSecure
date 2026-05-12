package com.explo.capstone.crypto.signal

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.*
import java.util.UUID

/**
 * Owner: Tejas Khanna
 *
 * Persistent implementation of [SignalProtocolStore].
 * Backs all five sub-stores with a single [EncryptedSharedPreferences] file ("astra_signal").
 *
 * Key naming:
 *   signal_ikp               → serialized IdentityKeyPair
 *   signal_reg_id            → Int registration ID
 *   signal_trust_{name}      → serialized IdentityKey (trusted remote identity)
 *   signal_session_{name}_{device}  → serialized SessionRecord
 *   signal_pk_{id}           → serialized PreKeyRecord
 *   signal_spk_{id}          → serialized SignedPreKeyRecord
 *   signal_sk_{channelId}_{name}_{device} → serialized SenderKeyRecord (by distributionId+sender)
 *
 * All keys are prefixed "signal_" so [wipeAll] can sweep them atomically.
 */
class AstraSignalProtocolStore(context: Context) : SignalProtocolStore {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = buildPrefs(appContext)

    // ─── Wipe ─────────────────────────────────────────────────────────────────

    fun wipeAll() {
        // Clear entries first (in case deleteSharedPreferences is a no-op while the
        // file is held open by this process), then drop the underlying file so the
        // EncryptedSharedPreferences keyset goes with it. Without the file delete,
        // the next launch reopens a file whose keyset references a master key that
        // IdentityManager.wipeAll() is about to remove from the Keystore — and the
        // resulting decryption failure crashes AppContainer construction.
        runCatching {
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("signal_") }.forEach(::remove)
            }.apply()
        }
        runCatching { appContext.deleteSharedPreferences("astra_signal") }
    }

    fun isProvisioned(): Boolean = prefs.contains("signal_ikp")

    // ─── JWT persistence (GAP-02 fix) ────────────────────────────────────────

    fun saveJwt(token: String) {
        prefs.edit().putString("signal_jwt", token).apply()
    }

    fun loadJwt(): String = prefs.getString("signal_jwt", "") ?: ""

    // ─── All OPKs (used for server re-registration) ───────────────────────────

    fun loadAllPreKeys(): List<PreKeyRecord> =
        prefs.all.entries
            .filter { it.key.startsWith("signal_pk_") }
            .mapNotNull { (_, v) -> runCatching { PreKeyRecord(decode(v as String)) }.getOrNull() }

    // ─── IdentityKeyStore ─────────────────────────────────────────────────────

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val raw = prefs.getString("signal_ikp", null)
            ?: error("Signal identity not provisioned — call SignalKeyManager.provision() first")
        return IdentityKeyPair(decode(raw))
    }

    fun saveIdentityKeyPair(kp: IdentityKeyPair) {
        prefs.edit().putString("signal_ikp", encode(kp.serialize())).apply()
    }

    override fun getLocalRegistrationId(): Int = prefs.getInt("signal_reg_id", -1)

    fun saveRegistrationId(id: Int) {
        prefs.edit().putInt("signal_reg_id", id).apply()
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = "signal_trust_${address.name}"
        val existing = prefs.getString(key, null)?.let {
            runCatching { IdentityKey(decode(it)) }.getOrNull()
        }
        prefs.edit().putString(key, encode(identityKey.serialize())).apply()
        return if (existing != null && existing != identityKey) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val stored = prefs.getString("signal_trust_${address.name}", null)
            ?: return true   // trust on first use (TOFU)
        return runCatching { IdentityKey(decode(stored)) }.getOrElse { return false } == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        return prefs.getString("signal_trust_${address.name}", null)
            ?.let { runCatching { IdentityKey(decode(it)) }.getOrNull() }
    }

    // ─── SessionStore ─────────────────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        return prefs.getString(sessionKey(address), null)
            ?.let { runCatching { SessionRecord(decode(it)) }.getOrNull() }
            ?: SessionRecord()
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.mapNotNull { addr ->
            prefs.getString(sessionKey(addr), null)
                ?.let { runCatching { SessionRecord(decode(it)) }.getOrNull() }
        }

    override fun getSubDeviceSessions(name: String): List<Int> =
        prefs.all.keys
            .filter { it.startsWith("signal_session_${name}_") }
            .mapNotNull { it.removePrefix("signal_session_${name}_").toIntOrNull() }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        prefs.edit().putString(sessionKey(address), encode(record.serialize())).apply()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        prefs.contains(sessionKey(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        prefs.edit().remove(sessionKey(address)).apply()
    }

    override fun deleteAllSessions(name: String) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("signal_session_${name}_") }.forEach(::remove)
        }.apply()
    }

    private fun sessionKey(address: SignalProtocolAddress) =
        "signal_session_${address.name}_${address.deviceId}"

    // ─── PreKeyStore ──────────────────────────────────────────────────────────

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val raw = prefs.getString("signal_pk_$preKeyId", null)
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No pre-key $preKeyId")
        return PreKeyRecord(decode(raw))
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        prefs.edit().putString("signal_pk_$preKeyId", encode(record.serialize())).apply()
    }

    override fun containsPreKey(preKeyId: Int): Boolean = prefs.contains("signal_pk_$preKeyId")

    override fun removePreKey(preKeyId: Int) {
        prefs.edit().remove("signal_pk_$preKeyId").apply()
    }

    fun preKeyCount(): Int = prefs.all.keys.count { it.startsWith("signal_pk_") }

    // ─── SPK rotation timestamp ───────────────────────────────────────────────

    fun getLastSpkRotationMs(): Long = prefs.getLong("signal_spk_last_rotated_ms", 0L)

    fun saveLastSpkRotationMs(ms: Long) {
        prefs.edit().putLong("signal_spk_last_rotated_ms", ms).apply()
    }

    // ─── SignedPreKeyStore ────────────────────────────────────────────────────

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val raw = prefs.getString("signal_spk_$signedPreKeyId", null)
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No signed pre-key $signedPreKeyId")
        return SignedPreKeyRecord(decode(raw))
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        prefs.all.entries
            .filter { it.key.startsWith("signal_spk_") }
            .mapNotNull { (_, v) -> runCatching { SignedPreKeyRecord(decode(v as String)) }.getOrNull() }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        prefs.edit().putString("signal_spk_$signedPreKeyId", encode(record.serialize())).apply()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        prefs.contains("signal_spk_$signedPreKeyId")

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        prefs.edit().remove("signal_spk_$signedPreKeyId").apply()
    }

    // ─── SenderKeyStore ───────────────────────────────────────────────────────

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        prefs.edit().putString(senderKeyKey(sender, distributionId), encode(record.serialize())).apply()
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? {
        return prefs.getString(senderKeyKey(sender, distributionId), null)
            ?.let { runCatching { SenderKeyRecord(decode(it)) }.getOrNull() }
    }

    private fun senderKeyKey(sender: SignalProtocolAddress, distributionId: UUID) =
        "signal_sk_${distributionId}_${sender.name}_${sender.deviceId}"

    // ─── KyberPreKeyStore ─────────────────────────────────────────────────────
    // Backed by EncryptedSharedPreferences entries keyed "signal_kyber_{id}".

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val raw = prefs.getString("signal_kyber_$kyberPreKeyId", null)
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No kyber pre-key $kyberPreKeyId")
        return KyberPreKeyRecord(decode(raw))
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        prefs.all.entries
            .filter { it.key.startsWith("signal_kyber_") && !it.key.endsWith("_rotated_ms") }
            .mapNotNull { (_, v) -> runCatching { KyberPreKeyRecord(decode(v as String)) }.getOrNull() }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        prefs.edit().putString("signal_kyber_$kyberPreKeyId", encode(record.serialize())).apply()
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        prefs.contains("signal_kyber_$kyberPreKeyId")

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        preKeyId: Int,
        baseKey: org.signal.libsignal.protocol.ecc.ECPublicKey,
    ) {
        // Rotated-key model: one long-lived Kyber key per user, rotated weekly by
        // SignalKeyManager.rotateKyberPreKey(). No one-time consumption needed here.
    }

    fun removeKyberPreKey(id: Int) {
        prefs.edit().remove("signal_kyber_$id").apply()
    }

    fun getLastKyberRotationMs(): Long = prefs.getLong("signal_kyber_last_rotated_ms", 0L)

    fun saveLastKyberRotationMs(ms: Long) {
        prefs.edit().putLong("signal_kyber_last_rotated_ms", ms).apply()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(s: String): ByteArray =
        Base64.decode(s, Base64.DEFAULT)

    private fun buildPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "astra_signal",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
