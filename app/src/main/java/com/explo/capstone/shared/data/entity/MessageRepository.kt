package com.explo.capstone.shared.data.entity

import android.util.Log
import com.explo.capstone.DemoConfig
import com.explo.capstone.crypto.signal.SignalCryptoEngine
import com.explo.capstone.metadata.MetadataProcessor
import com.explo.capstone.shared.Message
import com.explo.capstone.shared.data.InMemoryStore
import com.explo.capstone.transport.MessageTransport
import com.explo.capstone.transport.MessageType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

// ─── Interface ───────────────────────────────────────────────────────────────

interface MessageRepository {
    fun messagesForChannel(channelId: String): StateFlow<List<Message>>
    suspend fun send(channelId: String, missionKeyAlias: String, senderId: String, plaintext: ByteArray, categoryId: String): Result<Message>
    suspend fun receive(channelId: String, missionKeyAlias: String, ciphertext: ByteArray, senderId: String = "REMOTE_AGENT", categoryId: String = "mc_standard", bypassDecryption: Boolean = false): Result<Pair<Message, ByteArray>>
    suspend fun wipeAll()
}

// ─── Signal Protocol implementation ──────────────────────────────────────────

/**
 * Signal-encrypted message repository. Uses Double Ratchet (1:1 sessions) for
 * SKDM delivery and Sender Keys (group cipher) for channel messages.
 *
 * Sender key distribution IDs are derived deterministically from (channelId, senderId)
 * so they survive app restarts. Tracked in a map for clarity.
 *
 * Set [DemoConfig.BYPASS_SIGNAL] = true to skip all Signal crypto and send raw
 * plaintext (MessageType.PLAIN_TEXT) for demo/debugging purposes.
 */
class SignalMessageRepository(
    private val store: InMemoryStore,
    private val signalCryptoEngine: SignalCryptoEngine,
    private val transport: MessageTransport,
    private val metadataProcessor: MetadataProcessor,
) : MessageRepository {

    companion object { private const val TAG = "SignalMsgRepo" }

    private val channelFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    // Tracks which members have already received the SKDM for each channel.
    // Keys: channelId → set of userIds that hold the sender key for this sender.
    // Intentionally NOT cached by member list — we always re-fetch live members so
    // late joiners are included, and only send SKDM to members not yet in this set.
    private val skdmSentTo = mutableMapOf<String, MutableSet<String>>()

    override fun messagesForChannel(channelId: String): StateFlow<List<Message>> {
        return channelFlows.getOrPut(channelId) {
            MutableStateFlow(store.messages.value.filter { it.channelId == channelId })
        }
    }

    private fun refreshChannelFlows() {
        val all = store.messages.value
        for ((channelId, flow) in channelFlows) {
            flow.value = all.filter { it.channelId == channelId }
        }
    }

    override suspend fun send(
        channelId: String,
        missionKeyAlias: String,
        senderId: String,
        plaintext: ByteArray,
        categoryId: String,
    ): Result<Message> = runCatching {
        val plaintextStr = String(plaintext, Charsets.UTF_8)

        if (DemoConfig.BYPASS_SIGNAL) {
            val members = transport.getChannelMembers(channelId).getOrNull() ?: emptyList()
            val recipients = members.filter { it != senderId }
            delay(metadataProcessor.randomizedDelayMs(50, 200))
            recipients.forEach { recipientId ->
                transport.sendMessage(recipientId, channelId, plaintext, MessageType.PLAIN_TEXT)
                    .onFailure { Log.w(TAG, "Plain delivery to $recipientId failed: ${it.message}") }
            }
            val message = Message(
                id = "MSG-${UUID.randomUUID().toString().take(8).uppercase()}",
                channelId = channelId,
                senderId = senderId,
                categoryId = categoryId,
                paddedSizeBytes = plaintext.size,
                timestampMs = System.currentTimeMillis(),
                plaintextContent = plaintextStr,
            )
            store.updateMessages { it + message }
            refreshChannelFlows()
            return@runCatching message
        }

        // Always fetch the live member list — never cache, so late joiners are included.
        val members = transport.getChannelMembers(channelId).getOrNull() ?: emptyList()
        val recipients = members.filter { it != senderId }

        // Distribute SKDM only to members who haven't received it yet.
        // GroupSessionBuilder.create() is idempotent — safe to call every send;
        // it returns the current sender key without resetting the ratchet.
        val alreadySentTo = skdmSentTo.getOrDefault(channelId, emptySet())
        val needsSkdm = recipients.filter { it !in alreadySentTo }
        if (needsSkdm.isNotEmpty()) {
            val distributionId = distributionIdFor(channelId, senderId)
            val reached = runCatching {
                signalCryptoEngine.distributeChannelSenderKey(channelId, senderId, distributionId, needsSkdm)
            }.getOrElse { emptySet() }
            if (reached.isNotEmpty()) {
                skdmSentTo.getOrPut(channelId) { mutableSetOf() }.addAll(reached)
            }
            Log.d(TAG, "SKDM delivered to ${reached.size}/${needsSkdm.size} member(s) in $channelId")
        }

        val distributionId = distributionIdFor(channelId, senderId)
        val encrypted = signalCryptoEngine.encryptForChannel(senderId, distributionId, plaintext)
        // NOTE: padding cannot be applied to libsignal's serialized SenderKeyMessage —
        // GroupCipher.decrypt rejects trailing bytes as a MAC failure. If we want
        // metadata-normalization padding later it must wrap the plaintext (with a
        // length prefix) before encryptForChannel. Transport-timing jitter stays.
        delay(metadataProcessor.randomizedDelayMs(200, 2000))

        // Deliver the ciphertext to every current channel member except the sender.
        recipients.forEach { recipientId ->
            transport.sendMessage(recipientId, channelId, encrypted, MessageType.SENDER_KEY_MESSAGE)
                .onFailure { Log.w(TAG, "Delivery to $recipientId failed: ${it.message}") }
        }

        val message = Message(
            id = "MSG-${UUID.randomUUID().toString().take(8).uppercase()}",
            channelId = channelId,
            senderId = senderId,
            categoryId = categoryId,
            encryptedContent = encrypted,
            paddedSizeBytes = encrypted.size,
            timestampMs = System.currentTimeMillis(),
            plaintextContent = plaintextStr,
        )
        store.updateMessages { it + message }
        refreshChannelFlows()
        message
    }

    override suspend fun receive(
        channelId: String,
        missionKeyAlias: String,
        ciphertext: ByteArray,
        senderId: String,
        categoryId: String,
        bypassDecryption: Boolean,
    ): Result<Pair<Message, ByteArray>> = runCatching {
        val decrypted = if (bypassDecryption) ciphertext else signalCryptoEngine.decryptFromChannel(senderId, ciphertext)
        val plaintextStr = String(decrypted, Charsets.UTF_8)
        val message = Message(
            id = "MSG-${UUID.randomUUID().toString().take(8).uppercase()}",
            channelId = channelId,
            senderId = senderId,
            categoryId = categoryId,
            encryptedContent = if (bypassDecryption) ByteArray(0) else ciphertext,
            paddedSizeBytes = ciphertext.size,
            timestampMs = System.currentTimeMillis(),
            plaintextContent = plaintextStr,
        )
        store.updateMessages { it + message }
        refreshChannelFlows()
        message to decrypted
    }

    override suspend fun wipeAll() {
        store.updateMessages { emptyList() }
        channelFlows.values.forEach { it.value = emptyList() }
        skdmSentTo.clear()
    }

    private fun distributionIdFor(channelId: String, senderId: String): UUID =
        UUID.nameUUIDFromBytes("$channelId:$senderId".toByteArray())
}
