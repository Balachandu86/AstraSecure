package com.explo.capstone.shared.data.entity

import com.explo.capstone.shared.Mission
import com.explo.capstone.shared.MissionStatus
import com.explo.capstone.shared.data.InMemoryStore
import com.explo.capstone.transport.AstraApiClient
import com.explo.capstone.transport.toDomain
import kotlinx.coroutines.flow.StateFlow

// ─── Interface ───────────────────────────────────────────────────────────────

interface MissionRepository {
    val missions: StateFlow<List<Mission>>
    suspend fun create(name: String, typeId: String, missionKeyAlias: String = "", createdBy: String = ""): Mission
    suspend fun get(id: String): Mission?
    suspend fun updateStatus(id: String, status: MissionStatus)
    suspend fun updatePhase(id: String, phase: String?)
    suspend fun addParticipant(id: String, userId: String)
    suspend fun delete(id: String)
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryMissionRepository(
    private val store: InMemoryStore,
    private val remote: AstraApiClient? = null,
) : MissionRepository {

    override val missions: StateFlow<List<Mission>> = store.missions

    override suspend fun create(name: String, typeId: String, missionKeyAlias: String, createdBy: String): Mission {
        val remote = this.remote
        if (remote != null) {
            val dto = remote.createMission(name, typeId).getOrThrow()
            val mission = dto.toDomain()
            store.updateMissions { it + mission }
            return mission
        }
        // Offline / local-only fallback — server will be source of truth on next sync
        val now = System.currentTimeMillis()
        val mission = Mission(
            id = "M-LOCAL-${System.currentTimeMillis()}",
            name = name,
            typeId = typeId,
            status = MissionStatus.ACTIVE,
            missionKeyAlias = missionKeyAlias,
            participantIds = if (createdBy.isNotEmpty()) listOf(createdBy) else emptyList(),
            createdAtMs = now,
            lastActivityMs = now,
            createdBy = createdBy,
        )
        store.updateMissions { it + mission }
        return mission
    }

    override suspend fun addParticipant(id: String, userId: String) {
        remote?.addParticipant(id, userId)?.getOrThrow()
        store.updateMissions { list ->
            list.map { m ->
                if (m.id == id && userId !in m.participantIds)
                    m.copy(participantIds = m.participantIds + userId, lastActivityMs = System.currentTimeMillis())
                else m
            }
        }
    }

    override suspend fun get(id: String): Mission? =
        store.missions.value.find { it.id == id }

    override suspend fun updateStatus(id: String, status: MissionStatus) {
        remote?.updateMissionStatus(id, status.name)?.getOrThrow()
        store.updateMissions { list ->
            list.map { if (it.id == id) it.copy(status = status, lastActivityMs = System.currentTimeMillis()) else it }
        }
    }

    override suspend fun updatePhase(id: String, phase: String?) {
        remote?.updateMissionPhase(id, phase)?.getOrThrow()
        store.updateMissions { list ->
            list.map { if (it.id == id) it.copy(phase = phase, lastActivityMs = System.currentTimeMillis()) else it }
        }
    }

    override suspend fun delete(id: String) {
        remote?.deleteMission(id)?.getOrThrow()
        store.updateMissions { list -> list.filter { it.id != id } }
    }

    override suspend fun wipeAll() {
        store.updateMissions { emptyList() }
    }
}
