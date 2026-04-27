package com.explo.capstone.shared.data.entity

import com.explo.capstone.shared.EncryptedDocument
import com.explo.capstone.shared.data.InMemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

// ─── Interface ───────────────────────────────────────────────────────────────

interface DocumentRepository {
    fun documentsForMission(missionId: String): StateFlow<List<EncryptedDocument>>
    suspend fun store(missionId: String, ownerUserId: String, fileName: String, bytes: ByteArray): Result<EncryptedDocument>
    suspend fun retrieve(documentId: String): Result<ByteArray>
    suspend fun wipeAll()
}

// ─── In-memory implementation ────────────────────────────────────────────────

class InMemoryDocumentRepository(private val memStore: InMemoryStore) : DocumentRepository {

    private val missionFlows = mutableMapOf<String, MutableStateFlow<List<EncryptedDocument>>>()

    override fun documentsForMission(missionId: String): StateFlow<List<EncryptedDocument>> {
        return missionFlows.getOrPut(missionId) {
            MutableStateFlow(memStore.documents.value.filter { it.missionId == missionId })
        }
    }

    private fun refreshMissionFlows() {
        val all = memStore.documents.value
        for ((mid, flow) in missionFlows) {
            flow.value = all.filter { it.missionId == mid }
        }
    }

    override suspend fun store(
        missionId: String,
        ownerUserId: String,
        fileName: String,
        bytes: ByteArray,
    ): Result<EncryptedDocument> = runCatching {
        val doc = EncryptedDocument(
            id = "DOC-${UUID.randomUUID().toString().take(8).uppercase()}",
            missionId = missionId,
            ownerUserId = ownerUserId,
            encryptedBytes = bytes, // placeholder — real encryption later
            fileName = fileName,
            createdAtMs = System.currentTimeMillis(),
        )
        memStore.updateDocuments { it + doc }
        refreshMissionFlows()
        doc
    }

    override suspend fun retrieve(documentId: String): Result<ByteArray> = runCatching {
        memStore.documents.value.find { it.id == documentId }?.encryptedBytes
            ?: throw NoSuchElementException("Document $documentId not found")
    }

    override suspend fun wipeAll() {
        memStore.updateDocuments { emptyList() }
        missionFlows.values.forEach { it.value = emptyList() }
    }
}
