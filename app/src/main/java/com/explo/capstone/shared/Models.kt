package com.explo.capstone.shared

// ─── Enums ───────────────────────────────────────────────────────────────────

enum class MessageCategory {
    COMMAND,        // High-priority orders
    INTELLIGENCE,   // Recon and intel data
    STANDARD,       // Normal communication
    RESTRICTED      // Classified content, restricted access
}

enum class MissionStatus {
    ACTIVE,
    COMPLETED,
    ARCHIVED
}

// ─── Core data models ─────────────────────────────────────────────────────────

data class User(
    val id: String,
    val hardwareKeyId: String,       // Alias used in Android Keystore
    val displayName: String,
    val missionIds: List<String> = emptyList()
)

data class Mission(
    val id: String,
    val name: String,
    val status: MissionStatus,
    val missionKeyAlias: String,     // Keystore alias for this mission's key
    val participantIds: List<String> = emptyList()
)

data class Message(
    val id: String,
    val missionId: String,
    val senderId: String,
    val category: MessageCategory,
    val encryptedContent: ByteArray,
    val paddedSizeBytes: Int,        // For metadata normalization
    val timestampMs: Long
) {
    // ByteArray requires manual equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

data class EncryptedDocument(
    val id: String,
    val missionId: String,
    val ownerUserId: String,
    val encryptedBytes: ByteArray,
    val fileName: String,
    val createdAtMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedDocument) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
