package com.explo.capstone.shared.data.entity

import android.util.Log
import com.explo.capstone.crypto.CryptoEngine
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
    suspend fun receive(channelId: String, missionKeyAlias: String, ciphertext: ByteArray, senderId: String = "REMOTE_AGENT", categoryId: String = "mc_standard"): Result<Pair<Message, ByteArray>>
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryMessageRepository(
    private val store: InMemoryStore,
    private val cryptoEngine: CryptoEngine,
    private val metadataProcessor: MetadataProcessor,
) : MessageRepository {

    private val channelFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()

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
        val encrypted = cryptoEngine.encryptMessage(missionKeyAlias, plaintext)
        val padded = metadataProcessor.padMessage(encrypted)
        delay(metadataProcessor.randomizedDelayMs(200, 2000))
        val message = Message(
            id = "MSG-${UUID.randomUUID().toString().take(8).uppercase()}",
            channelId = channelId,
            senderId = senderId,
            categoryId = categoryId,
            encryptedContent = padded,
            paddedSizeBytes = padded.size,
            timestampMs = System.currentTimeMillis(),
        )
        store.updateMessages { it + message }
        refreshChannelFlows()
        message
    }

    // Returns the stored Message paired with the decrypted plaintext bytes.
    // If decryption fails, decrypted bytes are empty and isSuccess is still true
    // but the caller must detect failure via AEADBadTagException being caught
    // inside runCatching — the Result itself will be a failure in that case.
    override suspend fun receive(
        channelId: String,
        missionKeyAlias: String,
        ciphertext: ByteArray,
        senderId: String,
        categoryId: String,
    ): Result<Pair<Message, ByteArray>> = runCatching {
        val decrypted = cryptoEngine.decryptMessage(missionKeyAlias, ciphertext)
        val message = Message(
            id = "MSG-${UUID.randomUUID().toString().take(8).uppercase()}",
            channelId = channelId,
            senderId = senderId,
            categoryId = categoryId,
            encryptedContent = ciphertext,
            paddedSizeBytes = ciphertext.size,
            timestampMs = System.currentTimeMillis(),
        )
        store.updateMessages { it + message }
        refreshChannelFlows()
        message to decrypted
    }

    override suspend fun wipeAll() {
        store.updateMessages { emptyList() }
        channelFlows.values.forEach { it.value = emptyList() }
    }
}

// ─── Signal Protocol implementation ──────────────────────────────────────────

/**
 * Signal-encrypted message repository. Uses Double Ratchet (1:1 sessions) for
 * SKDM delivery and Sender Keys (group cipher) for channel messages.
 *
 * Sender key distribution IDs are derived deterministically from (channelId, senderId)
 * so they survive app restarts. Tracked in a map for clarity.
 */
class SignalMessageRepository(
    private val store: InMemoryStore,
    private val signalCryptoEngine: SignalCryptoEngine,
    private val transport: MessageTransport,
    private val metadataProcessor: MetadataProcessor,
) : MessageRepository {

    companion object { private const val TAG = "SignalMsgRepo" }

    private val channelFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val distributedChannels = mutableSetOf<String>()
    // Members cached per channel so we only fetch once (refreshed on wipe)
    private val channelMembers = mutableMapOf<String, List<String>>()

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
        // Fetch and cache channel members (stale members are fine for a demo; refreshed on wipe)
        if (!channelMembers.containsKey(channelId)) {
            channelMembers[channelId] = transport.getChannelMembers(channelId).getOrNull() ?: emptyList()
        }
        val members = channelMembers[channelId] ?: emptyList()

        // Lazy SKDM distribution on first send to this channel
        if (!distributedChannels.contains(channelId)) {
            val distributionId = distributionIdFor(channelId, senderId)
            runCatching {
                signalCryptoEngine.distributeChannelSenderKey(channelId, senderId, distributionId, members)
            }.onFailure { Log.w(TAG, "SKDM distribution failed: ${it.message}") }
            distributedChannels.add(channelId)
        }

        val distributionId = distributionIdFor(channelId, senderId)
        val encrypted = signalCryptoEngine.encryptForChannel(senderId, distributionId, plaintext)
        val padded = metadataProcessor.padMessage(encrypted)
        delay(metadataProcessor.randomizedDelayMs(200, 2000))

        // Deliver the same sender-key ciphertext to every channel member except self
        members.filter { it != senderId }.forEach { recipientId ->
            transport.sendMessage(recipientId, channelId, padded, MessageType.SENDER_KEY_MESSAGE)
                .onFailure { Log.w(TAG, "Delivery to $recipientId failed: ${it.message}") }
        }

        val message = Message(
            id = "MSG-${UUID.randomUUID().toString().take(8).uppercase()}",
            channelId = channelId,
            senderId = senderId,
            categoryId = categoryId,
            encryptedContent = padded,
            paddedSizeBytes = padded.size,
            timestampMs = System.currentTimeMillis(),
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
    ): Result<Pair<Message, ByteArray>> = runCatching {
        val decrypted = signalCryptoEngine.decryptFromChannel(senderId, ciphertext)
        val message = Message(
            id = "MSG-${UUID.randomUUID().toString().take(8).uppercase()}",
            channelId = channelId,
            senderId = senderId,
            categoryId = categoryId,
            encryptedContent = ciphertext,
            paddedSizeBytes = ciphertext.size,
            timestampMs = System.currentTimeMillis(),
        )
        store.updateMessages { it + message }
        refreshChannelFlows()
        message to decrypted
    }

    override suspend fun wipeAll() {
        store.updateMessages { emptyList() }
        channelFlows.values.forEach { it.value = emptyList() }
        distributedChannels.clear()
        channelMembers.clear()
    }

    private fun distributionIdFor(channelId: String, senderId: String): UUID =
        UUID.nameUUIDFromBytes("$channelId:$senderId".toByteArray())
}
