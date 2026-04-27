package com.explo.capstone.shared.data.schema

import com.explo.capstone.shared.ChannelCategory
import com.explo.capstone.shared.ColorToken
import com.explo.capstone.shared.DeleteResult
import com.explo.capstone.shared.EntityRef
import com.explo.capstone.shared.data.InMemoryStore
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

// ─── Interface ───────────────────────────────────────────────────────────────

interface ChannelCategoryRepository {
    val categories: StateFlow<List<ChannelCategory>>
    suspend fun create(
        name: String,
        accent: ColorToken,
        defaultMinClearanceToView: Int,
        defaultMinClearanceToPost: Int,
    ): ChannelCategory
    suspend fun update(
        id: String,
        name: String? = null,
        accent: ColorToken? = null,
        defaultMinClearanceToView: Int? = null,
        defaultMinClearanceToPost: Int? = null,
    )
    suspend fun delete(id: String): DeleteResult
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryChannelCategoryRepository(private val store: InMemoryStore) : ChannelCategoryRepository {

    override val categories: StateFlow<List<ChannelCategory>> = store.channelCategories

    override suspend fun create(
        name: String,
        accent: ColorToken,
        defaultMinClearanceToView: Int,
        defaultMinClearanceToPost: Int,
    ): ChannelCategory {
        val cat = ChannelCategory(
            id = "cc_${UUID.randomUUID().toString().take(8)}",
            name = name.uppercase(),
            accent = accent,
            defaultMinClearanceToView = defaultMinClearanceToView,
            defaultMinClearanceToPost = defaultMinClearanceToPost,
        )
        store.updateChannelCategories { it + cat }
        return cat
    }

    override suspend fun update(
        id: String,
        name: String?,
        accent: ColorToken?,
        defaultMinClearanceToView: Int?,
        defaultMinClearanceToPost: Int?,
    ) {
        store.updateChannelCategories { list ->
            list.map {
                if (it.id == id) it.copy(
                    name = name?.uppercase() ?: it.name,
                    accent = accent ?: it.accent,
                    defaultMinClearanceToView = defaultMinClearanceToView ?: it.defaultMinClearanceToView,
                    defaultMinClearanceToPost = defaultMinClearanceToPost ?: it.defaultMinClearanceToPost,
                ) else it
            }
        }
    }

    override suspend fun delete(id: String): DeleteResult {
        val cat = store.channelCategories.value.find { it.id == id }
        if (cat?.isSystem == true) return DeleteResult.BlockedBy(emptyList())
        val refs = store.channels.value
            .filter { it.categoryId == id }
            .map { ch -> EntityRef("Channel", ch.id, ch.name) }
        if (refs.isNotEmpty()) return DeleteResult.BlockedBy(refs)
        store.updateChannelCategories { list -> list.filter { it.id != id } }
        return DeleteResult.Deleted
    }

    override suspend fun wipeAll() {
        store.updateChannelCategories { emptyList() }
    }
}
