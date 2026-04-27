package com.explo.capstone.shared.data.entity

import com.explo.capstone.shared.Mission
import com.explo.capstone.shared.MissionStatus
import com.explo.capstone.shared.data.InMemoryStore
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

// ─── Interface ───────────────────────────────────────────────────────────────

interface MissionRepository {
    val missions: StateFlow<List<Mission>>
    suspend fun create(name: String, typeId: String, missionKeyAlias: String): Mission
    suspend fun get(id: String): Mission?
    suspend fun updateStatus(id: String, status: MissionStatus)
    suspend fun updatePhase(id: String, phase: String?)
    suspend fun delete(id: String)
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryMissionRepository(private val store: InMemoryStore) : MissionRepository {

    override val missions: StateFlow<List<Mission>> = store.missions

    override suspend fun create(name: String, typeId: String, missionKeyAlias: String): Mission {
        val now = System.currentTimeMillis()
        val mission = Mission(
            id = "M-${UUID.randomUUID().toString().take(8).uppercase()}",
            name = name,
            typeId = typeId,
            status = MissionStatus.ACTIVE,
            missionKeyAlias = missionKeyAlias,
            createdAtMs = now,
            lastActivityMs = now,
        )
        store.updateMissions { it + mission }
        return mission
    }

    override suspend fun get(id: String): Mission? =
        store.missions.value.find { it.id == id }

    override suspend fun updateStatus(id: String, status: MissionStatus) {
        store.updateMissions { list ->
            list.map { if (it.id == id) it.copy(status = status, lastActivityMs = System.currentTimeMillis()) else it }
        }
    }

    override suspend fun updatePhase(id: String, phase: String?) {
        store.updateMissions { list ->
            list.map { if (it.id == id) it.copy(phase = phase, lastActivityMs = System.currentTimeMillis()) else it }
        }
    }

    override suspend fun delete(id: String) {
        store.updateMissions { list -> list.filter { it.id != id } }
    }

    override suspend fun wipeAll() {
        store.updateMissions { emptyList() }
    }
}
