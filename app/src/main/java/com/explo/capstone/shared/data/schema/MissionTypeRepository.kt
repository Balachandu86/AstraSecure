package com.explo.capstone.shared.data.schema

import com.explo.capstone.shared.ColorToken
import com.explo.capstone.shared.DeleteResult
import com.explo.capstone.shared.EntityRef
import com.explo.capstone.shared.MissionType
import com.explo.capstone.shared.data.InMemoryStore
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

// ─── Interface ───────────────────────────────────────────────────────────────

interface MissionTypeRepository {
    val types: StateFlow<List<MissionType>>
    suspend fun create(name: String, accent: ColorToken, description: String): MissionType
    suspend fun update(id: String, name: String? = null, accent: ColorToken? = null, description: String? = null)
    suspend fun delete(id: String): DeleteResult
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryMissionTypeRepository(private val store: InMemoryStore) : MissionTypeRepository {

    override val types: StateFlow<List<MissionType>> = store.missionTypes

    override suspend fun create(name: String, accent: ColorToken, description: String): MissionType {
        val mt = MissionType(
            id = "mt_${UUID.randomUUID().toString().take(8)}",
            name = name.uppercase(),
            accent = accent,
            description = description,
        )
        store.updateMissionTypes { it + mt }
        return mt
    }

    override suspend fun update(id: String, name: String?, accent: ColorToken?, description: String?) {
        store.updateMissionTypes { list ->
            list.map {
                if (it.id == id) it.copy(
                    name = name?.uppercase() ?: it.name,
                    accent = accent ?: it.accent,
                    description = description ?: it.description,
                ) else it
            }
        }
    }

    override suspend fun delete(id: String): DeleteResult {
        val mt = store.missionTypes.value.find { it.id == id }
        if (mt?.isSystem == true) return DeleteResult.BlockedBy(emptyList())
        val refs = store.missions.value
            .filter { it.typeId == id }
            .map { m -> EntityRef("Mission", m.id, m.name) }
        if (refs.isNotEmpty()) return DeleteResult.BlockedBy(refs)
        store.updateMissionTypes { list -> list.filter { it.id != id } }
        return DeleteResult.Deleted
    }

    override suspend fun wipeAll() {
        store.updateMissionTypes { emptyList() }
    }
}
