package com.explo.capstone.shared.data.entity

import com.explo.capstone.identity.IdentityManager
import com.explo.capstone.shared.EncryptedDocument
import com.explo.capstone.shared.data.InMemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ─── Interface ───────────────────────────────────────────────────────────────

interface DocumentRepository {
    fun documentsForMission(missionId: String): StateFlow<List<EncryptedDocument>>
    suspend fun store(missionId: String, ownerUserId: String, fileName: String, bytes: ByteArray): Result<EncryptedDocument>
    suspend fun retrieve(documentId: String): Result<ByteArray>
    suspend fun delete(documentId: String)
    suspend fun wipeAll()
}

// ─── Vault implementation ─────────────────────────────────────────────────────

/**
 * Document repository backed by IdentityManager's per-document AES-256-GCM Keystore keys.
 * Each document's encrypted bytes are stored on disk (app filesDir/vault/) and survive
 * process restarts. Metadata is persisted in EncryptedSharedPreferences.
 */
class VaultDocumentRepository(
    private val memStore: InMemoryStore,
    private val identityManager: IdentityManager,
) : DocumentRepository {

    private val missionFlows = mutableMapOf<String, MutableStateFlow<List<EncryptedDocument>>>()

    init {
        // Load persisted vault manifest into the in-memory store on construction.
        // We store lightweight entries (no raw bytes in memory) so large files don't
        // blow the heap; bytes are read lazily in retrieve().
        val existing = identityManager.getVaultEntries().map { entry ->
            EncryptedDocument(
                id = entry.docId,
                missionId = entry.missionId,
                ownerUserId = "",
                encryptedBytes = ByteArray(0), // lazy — bytes live on disk
                fileName = entry.fileName,
                createdAtMs = entry.createdMs,
            )
        }
        if (existing.isNotEmpty()) {
            memStore.updateDocuments { existing }
        }
    }

    override fun documentsForMission(missionId: String): StateFlow<List<EncryptedDocument>> {
        return missionFlows.getOrPut(missionId) {
            MutableStateFlow(memStore.documents.value.filter { it.missionId == missionId })
        }.also { refreshMissionFlows() }
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
        val doc = identityManager.storeDocument(missionId, fileName, bytes)
        // Persist lightweight record (no bytes) in the in-memory store
        val record = doc.copy(encryptedBytes = ByteArray(0))
        memStore.updateDocuments { it + record }
        refreshMissionFlows()
        doc
    }

    override suspend fun retrieve(documentId: String): Result<ByteArray> = runCatching {
        identityManager.retrieveDocument(documentId)
    }

    override suspend fun delete(documentId: String) {
        identityManager.deleteVaultEntry(documentId)
        memStore.updateDocuments { it.filter { d -> d.id != documentId } }
        refreshMissionFlows()
    }

    override suspend fun wipeAll() {
        memStore.documents.value.forEach { runCatching { identityManager.deleteVaultEntry(it.id) } }
        memStore.updateDocuments { emptyList() }
        missionFlows.values.forEach { it.value = emptyList() }
    }
}
