package com.explo.capstone.shared.data.entity

import com.explo.capstone.crypto.CryptoEngine
import com.explo.capstone.metadata.MetadataProcessor
import com.explo.capstone.shared.Message
import com.explo.capstone.shared.data.InMemoryStore
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
