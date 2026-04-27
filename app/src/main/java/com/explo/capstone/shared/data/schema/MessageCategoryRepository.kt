package com.explo.capstone.shared.data.schema

import com.explo.capstone.shared.ColorToken
import com.explo.capstone.shared.DeleteResult
import com.explo.capstone.shared.EntityRef
import com.explo.capstone.shared.MessageCategory
import com.explo.capstone.shared.data.InMemoryStore
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

// ─── Interface ───────────────────────────────────────────────────────────────

interface MessageCategoryRepository {
    val categories: StateFlow<List<MessageCategory>>
    suspend fun create(name: String, accent: ColorToken, minClearanceToSend: Int): MessageCategory
    suspend fun update(id: String, name: String? = null, accent: ColorToken? = null, minClearanceToSend: Int? = null)
    suspend fun delete(id: String): DeleteResult
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryMessageCategoryRepository(private val store: InMemoryStore) : MessageCategoryRepository {

    override val categories: StateFlow<List<MessageCategory>> = store.messageCategories

    override suspend fun create(name: String, accent: ColorToken, minClearanceToSend: Int): MessageCategory {
        val cat = MessageCategory(
            id = "mc_${UUID.randomUUID().toString().take(8)}",
            name = name.uppercase(),
            accent = accent,
            minClearanceToSend = minClearanceToSend,
        )
        store.updateMessageCategories { it + cat }
        return cat
    }

    override suspend fun update(id: String, name: String?, accent: ColorToken?, minClearanceToSend: Int?) {
        store.updateMessageCategories { list ->
            list.map {
                if (it.id == id) it.copy(
                    name = name?.uppercase() ?: it.name,
                    accent = accent ?: it.accent,
                    minClearanceToSend = minClearanceToSend ?: it.minClearanceToSend,
                ) else it
            }
        }
    }

    override suspend fun delete(id: String): DeleteResult {
        val cat = store.messageCategories.value.find { it.id == id }
        if (cat?.isSystem == true) return DeleteResult.BlockedBy(emptyList())
        val refs = store.messages.value
            .filter { it.categoryId == id }
            .map { m -> EntityRef("Message", m.id, m.id) }
        if (refs.isNotEmpty()) return DeleteResult.BlockedBy(refs)
        store.updateMessageCategories { list -> list.filter { it.id != id } }
        return DeleteResult.Deleted
    }

    override suspend fun wipeAll() {
        store.updateMessageCategories { emptyList() }
    }
}
