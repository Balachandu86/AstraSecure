package com.explo.capstone.crypto.signal

import android.util.Base64
import android.util.Log
import com.explo.capstone.transport.MessageTransport
import com.explo.capstone.transport.MessageType
import com.explo.capstone.transport.PreKeyBundleResponse
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import java.util.UUID

/**
 * Owner: Tejas Khanna
 *
 * Wraps libsignal's session and group ciphers:
 *   - [encryptForAddress] / [decryptFromAddress]: 1:1 Double Ratchet sessions,
 *     used to deliver [SenderKeyDistributionMessage]s to channel members.
 *   - [encryptForChannel] / [decryptFromChannel]: Sender Key chain per channel,
 *     used for all actual message content (GroupCipher, one-ratchet-per-send).
 *   - [distributeChannelSenderKey]: creates Alice's sender key for a channel and
 *     delivers it to every other member via 1:1 sessions.
 *   - [processSenderKeyDistribution]: stores an incoming SKDM so the local node
 *     can decrypt that sender's future channel messages.
 */
class SignalCryptoEngine(
    private val store: AstraSignalProtocolStore,
    private val transport: MessageTransport,
    private val localUserId: String,
) {

    companion object {
        private const val TAG = "SignalCryptoEngine"
        private const val DEVICE_ID = 1
    }

    private fun getLocalAddress() = SignalProtocolAddress(localUserId, DEVICE_ID)

    // ─── 1:1 session (X3DH + Double Ratchet) ─────────────────────────────────

    /**
     * Encrypt [plaintext] for a single recipient. If no session exists yet,
     * fetches the recipient's pre-key bundle and runs X3DH to establish one.
     * Returns serialised ciphertext and the [MessageType] constant to send
     * over the wire (PREKEY_SIGNAL_MESSAGE for the first message, WHISPER_MESSAGE
     * for subsequent messages in the same session).
     */
    suspend fun encryptForAddress(
        recipientUserId: String,
        plaintext: ByteArray,
    ): Pair<ByteArray, Int> {
        val address = SignalProtocolAddress(recipientUserId, DEVICE_ID)

        if (!store.containsSession(address)) {
            val response = transport.fetchPreKeyBundle(recipientUserId)
            val bundle = response.toPreKeyBundle()
            SessionBuilder(store, getLocalAddress(), address).process(bundle)
        }

        val encrypted = SessionCipher(store, getLocalAddress(), address).encrypt(plaintext)
        val wireType = if (encrypted.type == CiphertextMessage.PREKEY_TYPE) {
            MessageType.PREKEY_SIGNAL_MESSAGE
        } else {
            MessageType.WHISPER_MESSAGE
        }
        return encrypted.serialize() to wireType
    }

    /**
     * Decrypt a 1:1 message from [senderUserId]. [messageType] must be one of
     * [MessageType.PREKEY_SIGNAL_MESSAGE] or [MessageType.WHISPER_MESSAGE].
     */
    fun decryptFromAddress(
        senderUserId: String,
        ciphertext: ByteArray,
        messageType: Int,
    ): ByteArray {
        val address = SignalProtocolAddress(senderUserId, DEVICE_ID)
        val cipher = SessionCipher(store, getLocalAddress(), address)
        return when (messageType) {
            MessageType.PREKEY_SIGNAL_MESSAGE -> cipher.decrypt(PreKeySignalMessage(ciphertext))
            MessageType.WHISPER_MESSAGE       -> cipher.decrypt(SignalMessage(ciphertext))
            else -> throw IllegalArgumentException("Unexpected 1:1 messageType=$messageType")
        }
    }

    // ─── Channel (Sender Key) ─────────────────────────────────────────────────

    /**
     * Encrypt [plaintext] for broadcast to a channel. Each call advances the
     * sender's ratchet — forward secrecy within the channel stream.
     *
     * [distributionId] must be the same UUID used when the sender key was distributed
     * via [distributeChannelSenderKey]. Use [MessageRepository.distributionIdFor] to
     * derive it deterministically from (channelId, senderId).
     */
    fun encryptForChannel(
        senderUserId: String,
        distributionId: UUID,
        plaintext: ByteArray,
    ): ByteArray {
        val sender = SignalProtocolAddress(senderUserId, DEVICE_ID)
        return GroupCipher(store, sender).encrypt(distributionId, plaintext).serialize()
    }

    /**
     * Decrypt a channel message from [senderUserId].
     */
    fun decryptFromChannel(
        senderUserId: String,
        ciphertextBytes: ByteArray,
    ): ByteArray {
        val sender = SignalProtocolAddress(senderUserId, DEVICE_ID)
        return GroupCipher(store, sender).decrypt(ciphertextBytes)
    }

    // ─── Sender key distribution ──────────────────────────────────────────────

    /**
     * Called when the local user joins or creates a channel. Generates a
     * [SenderKeyDistributionMessage] for [aliceUserId] and delivers it to
     * every other member via 1:1 encrypted sessions.
     *
     * Members who receive the SKDM call [processSenderKeyDistribution] so they
     * can decrypt future channel messages sent by Alice.
     */
    suspend fun distributeChannelSenderKey(
        channelId: String,
        aliceUserId: String,
        distributionId: UUID,
        memberIds: List<String>,
    ) {
        val sender = SignalProtocolAddress(aliceUserId, DEVICE_ID)
        val skdm: SenderKeyDistributionMessage = GroupSessionBuilder(store).create(sender, distributionId)

        memberIds.filter { it != aliceUserId }.forEach { memberId ->
            runCatching {
                val (ciphertext, type) = encryptForAddress(memberId, skdm.serialize())
                transport.sendMessage(memberId, channelId, ciphertext, type)
            }.onFailure {
                Log.w(TAG, "SKDM delivery failed for $memberId: ${it.message}")
            }
        }
    }

    /**
     * Called on the receiver side when a [MessageType.PREKEY_SIGNAL_MESSAGE] or
     * [MessageType.WHISPER_MESSAGE] arrives containing an SKDM.
     * Stores the sender key so future channel messages from [senderUserId]
     * can be decrypted.
     */
    fun processSenderKeyDistribution(
        senderUserId: String,
        skdmBytes: ByteArray,
    ) {
        val sender = SignalProtocolAddress(senderUserId, DEVICE_ID)
        val skdm = SenderKeyDistributionMessage(skdmBytes)
        GroupSessionBuilder(store).process(sender, skdm)
        Log.d(TAG, "Processed SKDM from $senderUserId")
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun PreKeyBundleResponse.toPreKeyBundle(): PreKeyBundle {
        val identityKeyBytes  = Base64.decode(identityKey, Base64.DEFAULT)
        val spkPublicBytes    = Base64.decode(signedPreKey.publicKey, Base64.DEFAULT)
        val spkSignatureBytes = Base64.decode(signedPreKey.signature, Base64.DEFAULT)

        val opkId     = oneTimePreKey?.id ?: -1
        val opkPublic = oneTimePreKey?.let { ECPublicKey(Base64.decode(it.publicKey, Base64.DEFAULT)) }

        return PreKeyBundle(
            registrationId,
            DEVICE_ID,
            opkId,
            opkPublic,
            signedPreKey.id,
            ECPublicKey(spkPublicBytes),
            spkSignatureBytes,
            IdentityKey(identityKeyBytes),
            PreKeyBundle.NULL_PRE_KEY_ID,
            KEMKeyPair.generate(KEMKeyType.KYBER_1024).publicKey,
            ByteArray(0),
        )
    }
}
