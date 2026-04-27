package com.explo.capstone.shared.data.schema

import com.explo.capstone.shared.ClearanceAssignment
import com.explo.capstone.shared.Rank
import com.explo.capstone.shared.data.InMemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

// ─── Interface ───────────────────────────────────────────────────────────────

interface ClearanceRepository {
    fun clearanceFor(userId: String, missionId: String): StateFlow<Rank?>
    suspend fun assign(userId: String, missionId: String, rankId: String)
    suspend fun unassign(userId: String, missionId: String)
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryClearanceRepository(private val store: InMemoryStore) : ClearanceRepository {

    // Cache derived flows per (userId, missionId)
    private val derivedFlows = mutableMapOf<Pair<String, String>, MutableStateFlow<Rank?>>()

    override fun clearanceFor(userId: String, missionId: String): StateFlow<Rank?> {
        val key = userId to missionId
        return derivedFlows.getOrPut(key) {
            val assignment = store.clearanceAssignments.value
                .find { it.userId == userId && it.missionId == missionId }
            val rank = assignment?.let { a -> store.ranks.value.find { it.id == a.rankId } }
            MutableStateFlow(rank)
        }
    }

    private fun refreshDerivedFlows() {
        for ((key, flow) in derivedFlows) {
            val (userId, missionId) = key
            val assignment = store.clearanceAssignments.value
                .find { it.userId == userId && it.missionId == missionId }
            flow.value = assignment?.let { a -> store.ranks.value.find { it.id == a.rankId } }
        }
    }

    override suspend fun assign(userId: String, missionId: String, rankId: String) {
        store.updateClearanceAssignments { list ->
            // Remove existing assignment for this user+mission, then add new
            list.filter { !(it.userId == userId && it.missionId == missionId) } +
                ClearanceAssignment(userId, missionId, rankId)
        }
        refreshDerivedFlows()
    }

    override suspend fun unassign(userId: String, missionId: String) {
        store.updateClearanceAssignments { list ->
            list.filter { !(it.userId == userId && it.missionId == missionId) }
        }
        refreshDerivedFlows()
    }

    override suspend fun wipeAll() {
        store.updateClearanceAssignments { emptyList() }
        derivedFlows.values.forEach { it.value = null }
    }
}
