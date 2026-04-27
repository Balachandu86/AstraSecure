package com.explo.capstone.shared

import kotlinx.serialization.Serializable

// ─── Workflow enums (internal state machines, NOT user-customizable) ──────────

@Serializable
enum class MissionStatus { ACTIVE, STANDBY, COMPROMISED, ARCHIVED }

// ─── Design-system color token (restricted palette for schema records) ────────

@Serializable
enum class ColorToken { PRIMARY, TERTIARY, SECONDARY, ERROR, NEUTRAL }

// ─── Security event severity ─────────────────────────────────────────────────

enum class Severity { INFO, WARN, ALERT }

// ─── Core entity records ─────────────────────────────────────────────────────

data class User(
    val id: String,
    val hardwareKeyId: String,       // Alias used in Android Keystore
    val displayName: String,
    val provisionedAtMs: Long,
)

@Serializable
data class Mission(
    val id: String,
    val name: String,
    val typeId: String,              // FK → MissionType
    val status: MissionStatus,
    val phase: String? = null,       // free-text, optional ("PHASE 4 / EXTRACTION")
    val missionKeyAlias: String,
    val participantIds: List<String> = emptyList(),
    val createdAtMs: Long,
    val lastActivityMs: Long,
)

@Serializable
data class Channel(
    val id: String,
    val missionId: String,
    val name: String,
    val description: String,
    val categoryId: String,          // FK → ChannelCategory
    val minClearanceToView: Int,     // can override category default
    val minClearanceToPost: Int,
    val createdAtMs: Long,
)

data class Message(
    val id: String,
    val channelId: String,           // messages live in channels, not missions
    val senderId: String,
    val categoryId: String,          // FK → MessageCategory
    val encryptedContent: ByteArray,
    val paddedSizeBytes: Int,        // For metadata normalization
    val timestampMs: Long,
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
    val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedDocument) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

// ─── User-editable schema records ────────────────────────────────────────────

@Serializable
data class Rank(
    val id: String,
    val name: String,                // "OPERATIVE", uppercased by convention
    val level: Int,                  // gating uses this; higher = more access
    val color: ColorToken,
    val isSystem: Boolean = false,   // seed defaults can't be deleted, only edited
)

@Serializable
data class ChannelCategory(
    val id: String,
    val name: String,
    val accent: ColorToken,
    val defaultMinClearanceToView: Int,
    val defaultMinClearanceToPost: Int,
    val isSystem: Boolean = false,
)

@Serializable
data class MessageCategory(
    val id: String,
    val name: String,                // "STANDARD", "INTELLIGENCE", etc.
    val accent: ColorToken,
    val minClearanceToSend: Int,
    val isSystem: Boolean = false,
)

@Serializable
data class MissionType(
    val id: String,
    val name: String,                // "RECON", "EXTRACTION", "TOP_SECRET"
    val accent: ColorToken,
    val description: String,
    val isSystem: Boolean = false,
)

@Serializable
data class ClearanceAssignment(
    val userId: String,
    val missionId: String,
    val rankId: String,              // FK → Rank
)

// ─── Security event log entry ────────────────────────────────────────────────

data class SecurityEvent(
    val id: String,
    val tsMs: Long,
    val severity: Severity,
    val source: String,              // "CryptoEngine", "MessageRepository", etc.
    val text: String,
)

// ─── Deletion result (schema repos) ──────────────────────────────────────────

data class EntityRef(
    val type: String,                // "Channel", "Mission", etc.
    val id: String,
    val name: String,
)

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data class BlockedBy(val refs: List<EntityRef>) : DeleteResult
}
