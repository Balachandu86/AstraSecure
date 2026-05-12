package com.explo.capstone.shared.data.schema

import com.explo.capstone.shared.ClearanceAssignment
import com.explo.capstone.shared.Rank
import com.explo.capstone.shared.data.InMemoryStore
import com.explo.capstone.transport.AstraApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ─── Interface ───────────────────────────────────────────────────────────────

interface ClearanceRepository {
    fun clearanceFor(userId: String, missionId: String): StateFlow<Rank?>
    suspend fun assign(userId: String, missionId: String, rankId: String): Result<Unit>
    suspend fun unassign(userId: String, missionId: String): Result<Unit>
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryClearanceRepository(
    private val store: InMemoryStore,
    private val remote: AstraApiClient? = null,
) : ClearanceRepository {

    // Cache derived flows per (userId, missionId)
    private val derivedFlows = mutableMapOf<Pair<String, String>, MutableStateFlow<Rank?>>()

    // Observe the underlying store flows so that syncFromServer() updates
    // automatically propagate to all holders of a clearanceFor() flow.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch { store.clearanceAssignments.collect { refreshDerivedFlows() } }
        scope.launch { store.ranks.collect { refreshDerivedFlows() } }
    }

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

    override suspend fun assign(userId: String, missionId: String, rankId: String): Result<Unit> {
        // Server is authoritative — if the remote call fails, surface it so the
        // UI can show the error and we don't write a value that the next sync
        // will silently revert.
        remote?.assignClearance(userId, missionId, rankId)?.let { if (it.isFailure) return it }
        store.updateClearanceAssignments { list ->
            // Remove existing assignment for this user+mission, then add new
            list.filter { !(it.userId == userId && it.missionId == missionId) } +
                ClearanceAssignment(userId, missionId, rankId)
        }
        refreshDerivedFlows()
        return Result.success(Unit)
    }

    override suspend fun unassign(userId: String, missionId: String): Result<Unit> {
        remote?.removeClearance(userId, missionId)?.let { if (it.isFailure) return it }
        store.updateClearanceAssignments { list ->
            list.filter { !(it.userId == userId && it.missionId == missionId) }
        }
        refreshDerivedFlows()
        return Result.success(Unit)
    }

    override suspend fun wipeAll() {
        store.updateClearanceAssignments { emptyList() }
        derivedFlows.values.forEach { it.value = null }
    }
}
