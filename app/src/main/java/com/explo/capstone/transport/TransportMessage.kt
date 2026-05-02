package com.explo.capstone.transport

// ─── Message types (mirrors server message_type column) ───────────────────────

object MessageType {
    const val PREKEY_SIGNAL_MESSAGE = 1      // first message to a new address (contains X3DH material)
    const val WHISPER_MESSAGE       = 2      // subsequent messages in an established session
    const val SENDER_KEY_DIST       = 3      // SenderKeyDistributionMessage wrapped in 1:1 session
    const val SENDER_KEY_MESSAGE    = 4      // channel message encrypted with sender key chain
}

// ─── Incoming message from server ────────────────────────────────────────────

data class TransportMessage(
    val id: Long,
    val senderId: String,
    val channelId: String,
    val messageType: Int,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?) = other is TransportMessage && id == other.id
    override fun hashCode() = id.hashCode()
}

// ─── REST request / response DTOs (Gson-serialized) ──────────────────────────

data class RegisterRequest(
    val userId: String,
    val displayName: String,
    val registrationId: Int,
    val identityKey: String,           // base64
    val signedPreKey: SignedPreKeyDto,
    val oneTimePreKeys: List<PreKeyDto>,
)

data class RegisterResponse(
    val token: String,
)

data class PreKeyDto(
    val id: Int,
    val publicKey: String,             // base64
)

data class SignedPreKeyDto(
    val id: Int,
    val publicKey: String,             // base64
    val signature: String,             // base64
)

data class PreKeyBundleResponse(
    val userId: String,
    val registrationId: Int,
    val identityKey: String,           // base64
    val signedPreKey: SignedPreKeyDto,
    val oneTimePreKey: PreKeyDto?,     // null when OPKs exhausted
)

data class UploadPreKeysRequest(
    val oneTimePreKeys: List<PreKeyDto>,
)

data class SendMessageRequest(
    val senderId: String,
    val channelId: String,
    val messageType: Int,
    val ciphertext: String,            // base64
)

data class FetchMessagesResponse(
    val messages: List<QueuedMessageDto>,
)

data class QueuedMessageDto(
    val id: Long,
    val senderId: String,
    val channelId: String,
    val messageType: Int,
    val ciphertext: String,            // base64
)

data class AckMessagesRequest(
    val messageIds: List<Long>,
)

data class ChannelMembersResponse(
    val memberIds: List<String>,
)

data class JoinChannelRequest(
    val userId: String,
)
