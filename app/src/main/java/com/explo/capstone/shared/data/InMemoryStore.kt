package com.explo.capstone.shared.data

import com.explo.capstone.shared.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central in-memory backing store.
 * All repository implementations proxy to this.
 * Panic wipe calls [clear] which emits empty lists on every flow.
 */
class InMemoryStore {

    // ─── Entity stores ───────────────────────────────────────────────────────
    private val _missions = MutableStateFlow<List<Mission>>(emptyList())
    val missions: StateFlow<List<Mission>> = _missions.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _documents = MutableStateFlow<List<EncryptedDocument>>(emptyList())
    val documents: StateFlow<List<EncryptedDocument>> = _documents.asStateFlow()

    // ─── Schema stores ───────────────────────────────────────────────────────
    private val _ranks = MutableStateFlow<List<Rank>>(emptyList())
    val ranks: StateFlow<List<Rank>> = _ranks.asStateFlow()

    private val _channelCategories = MutableStateFlow<List<ChannelCategory>>(emptyList())
    val channelCategories: StateFlow<List<ChannelCategory>> = _channelCategories.asStateFlow()

    private val _messageCategories = MutableStateFlow<List<MessageCategory>>(emptyList())
    val messageCategories: StateFlow<List<MessageCategory>> = _messageCategories.asStateFlow()

    private val _missionTypes = MutableStateFlow<List<MissionType>>(emptyList())
    val missionTypes: StateFlow<List<MissionType>> = _missionTypes.asStateFlow()

    private val _clearanceAssignments = MutableStateFlow<List<ClearanceAssignment>>(emptyList())
    val clearanceAssignments: StateFlow<List<ClearanceAssignment>> = _clearanceAssignments.asStateFlow()

    // ─── Persistence hook ────────────────────────────────────────────────────
    // Set by AppContainer after construction; called after every schema/entity mutation.
    var onChanged: (() -> Unit)? = null

    private fun notifyChanged() { onChanged?.invoke() }

    // ─── Mutators ────────────────────────────────────────────────────────────

    fun updateMissions(transform: (List<Mission>) -> List<Mission>) {
        _missions.value = transform(_missions.value); notifyChanged()
    }

    fun updateChannels(transform: (List<Channel>) -> List<Channel>) {
        _channels.value = transform(_channels.value); notifyChanged()
    }

    fun updateMessages(transform: (List<Message>) -> List<Message>) {
        _messages.value = transform(_messages.value)
        // messages not persisted (ephemeral ciphertext; keys rotate)
    }

    fun updateDocuments(transform: (List<EncryptedDocument>) -> List<EncryptedDocument>) {
        _documents.value = transform(_documents.value)
        // documents not persisted (vault bytes survive only in session)
    }

    fun updateRanks(transform: (List<Rank>) -> List<Rank>) {
        _ranks.value = transform(_ranks.value); notifyChanged()
    }

    fun updateChannelCategories(transform: (List<ChannelCategory>) -> List<ChannelCategory>) {
        _channelCategories.value = transform(_channelCategories.value); notifyChanged()
    }

    fun updateMessageCategories(transform: (List<MessageCategory>) -> List<MessageCategory>) {
        _messageCategories.value = transform(_messageCategories.value); notifyChanged()
    }

    fun updateMissionTypes(transform: (List<MissionType>) -> List<MissionType>) {
        _missionTypes.value = transform(_missionTypes.value); notifyChanged()
    }

    fun updateClearanceAssignments(transform: (List<ClearanceAssignment>) -> List<ClearanceAssignment>) {
        _clearanceAssignments.value = transform(_clearanceAssignments.value); notifyChanged()
    }

    // ─── Panic wipe ──────────────────────────────────────────────────────────

    fun clear() {
        _missions.value = emptyList()
        _channels.value = emptyList()
        _messages.value = emptyList()
        _documents.value = emptyList()
        _ranks.value = emptyList()
        _channelCategories.value = emptyList()
        _messageCategories.value = emptyList()
        _missionTypes.value = emptyList()
        _clearanceAssignments.value = emptyList()
        // No notifyChanged() here — wipe deletes the file separately via PersistenceManager.clear()
    }
}
