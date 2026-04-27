package com.explo.capstone.shared.data.schema

import com.explo.capstone.shared.ColorToken
import com.explo.capstone.shared.DeleteResult
import com.explo.capstone.shared.EntityRef
import com.explo.capstone.shared.Rank
import com.explo.capstone.shared.data.InMemoryStore
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

// ─── Interface ───────────────────────────────────────────────────────────────

interface RankRepository {
    val ranks: StateFlow<List<Rank>>      // sorted by level desc
    suspend fun create(name: String, level: Int, color: ColorToken): Rank
    suspend fun update(id: String, name: String? = null, level: Int? = null, color: ColorToken? = null)
    suspend fun delete(id: String): DeleteResult
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryRankRepository(private val store: InMemoryStore) : RankRepository {

    override val ranks: StateFlow<List<Rank>> = store.ranks

    override suspend fun create(name: String, level: Int, color: ColorToken): Rank {
        val rank = Rank(
            id = "rank_${UUID.randomUUID().toString().take(8)}",
            name = name.uppercase(),
            level = level,
            color = color,
        )
        store.updateRanks { (it + rank).sortedByDescending { r -> r.level } }
        return rank
    }

    override suspend fun update(id: String, name: String?, level: Int?, color: ColorToken?) {
        store.updateRanks { list ->
            list.map {
                if (it.id == id) it.copy(
                    name = name?.uppercase() ?: it.name,
                    level = level ?: it.level,
                    color = color ?: it.color,
                ) else it
            }.sortedByDescending { r -> r.level }
        }
    }

    override suspend fun delete(id: String): DeleteResult {
        val rank = store.ranks.value.find { it.id == id }
        if (rank?.isSystem == true) return DeleteResult.BlockedBy(emptyList())
        val refs = store.clearanceAssignments.value
            .filter { it.rankId == id }
            .map { ca -> EntityRef("ClearanceAssignment", ca.userId, "Mission: ${ca.missionId}") }
        if (refs.isNotEmpty()) return DeleteResult.BlockedBy(refs)
        store.updateRanks { list -> list.filter { it.id != id } }
        return DeleteResult.Deleted
    }

    override suspend fun wipeAll() {
        store.updateRanks { emptyList() }
    }
}
