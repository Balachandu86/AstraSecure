package com.explo.capstone.shared.data

import android.content.Context
import com.explo.capstone.shared.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoreSnapshot(
    val missions: List<Mission> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val ranks: List<Rank> = emptyList(),
    val channelCategories: List<ChannelCategory> = emptyList(),
    val messageCategories: List<MessageCategory> = emptyList(),
    val missionTypes: List<MissionType> = emptyList(),
    val clearanceAssignments: List<ClearanceAssignment> = emptyList(),
    val messages: List<Message> = emptyList(),  // plaintext persisted; encryptedContent is @Transient
)

class PersistenceManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val file = File(context.filesDir, "astra_store.json")

    fun save(snapshot: StoreSnapshot) {
        runCatching { file.writeText(json.encodeToString(StoreSnapshot.serializer(), snapshot)) }
    }

    fun load(): StoreSnapshot? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(StoreSnapshot.serializer(), file.readText()) }.getOrNull()
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
