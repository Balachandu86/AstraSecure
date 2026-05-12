package com.explo.capstone.shared.data.entity

import com.explo.capstone.shared.Channel
import com.explo.capstone.shared.data.InMemoryStore
import com.explo.capstone.transport.AstraApiClient
import com.explo.capstone.transport.toDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ─── Interface ───────────────────────────────────────────────────────────────

interface ChannelRepository {
    fun channelsForMission(missionId: String): StateFlow<List<Channel>>
    suspend fun create(
        missionId: String,
        name: String,
        description: String,
        categoryId: String,
        minClearanceToView: Int? = null,
        minClearanceToPost: Int? = null,
        createdBy: String = "",
    ): Channel
    suspend fun get(channelId: String): Channel?
    suspend fun updateClearance(channelId: String, view: Int, post: Int)
    suspend fun delete(channelId: String)
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryChannelRepository(
    private val store: InMemoryStore,
    private val remote: AstraApiClient? = null,
) : ChannelRepository {

    // Cache derived flows per mission to avoid re-creating on each call
    private val missionFlows = mutableMapOf<String, MutableStateFlow<List<Channel>>>()

    override fun channelsForMission(missionId: String): StateFlow<List<Channel>> {
        return missionFlows.getOrPut(missionId) {
            MutableStateFlow(store.channels.value.filter { it.missionId == missionId })
        }
    }

    private fun refreshMissionFlows() {
        val allChannels = store.channels.value
        for ((missionId, flow) in missionFlows) {
            flow.value = allChannels.filter { it.missionId == missionId }
        }
    }

    override suspend fun create(
        missionId: String,
        name: String,
        description: String,
        categoryId: String,
        minClearanceToView: Int?,
        minClearanceToPost: Int?,
        createdBy: String,
    ): Channel {
        val remote = this.remote
        if (remote != null) {
            val dto = remote.createChannel(
                missionId, name, description, categoryId, minClearanceToView, minClearanceToPost
            ).getOrThrow()
            val channel = dto.toDomain()
            store.updateChannels { it + channel }
            refreshMissionFlows()
            return channel
        }
        // Offline / local-only fallback
        val channel = Channel(
            id = "CH-LOCAL-${System.currentTimeMillis()}",
            missionId = missionId,
            name = name,
            description = description,
            categoryId = categoryId,
            minClearanceToView = minClearanceToView ?: 1,
            minClearanceToPost = minClearanceToPost ?: 1,
            createdAtMs = System.currentTimeMillis(),
            createdBy = createdBy,
        )
        store.updateChannels { it + channel }
        refreshMissionFlows()
        return channel
    }

    override suspend fun get(channelId: String): Channel? =
        store.channels.value.find { it.id == channelId }

    override suspend fun updateClearance(channelId: String, view: Int, post: Int) {
        remote?.updateChannelClearance(channelId, view, post)?.getOrThrow()
        store.updateChannels { list ->
            list.map {
                if (it.id == channelId) it.copy(minClearanceToView = view, minClearanceToPost = post)
                else it
            }
        }
        refreshMissionFlows()
    }

    override suspend fun delete(channelId: String) {
        remote?.deleteChannel(channelId)?.getOrThrow()
        store.updateChannels { list -> list.filter { it.id != channelId } }
        refreshMissionFlows()
    }

    override suspend fun wipeAll() {
        store.updateChannels { emptyList() }
        missionFlows.values.forEach { it.value = emptyList() }
    }
}
