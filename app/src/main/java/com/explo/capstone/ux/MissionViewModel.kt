package com.explo.capstone.ux

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.explo.capstone.shared.Message
import com.explo.capstone.shared.Mission

/**
 * Owner: Ismail Alam
 * Responsible for: ViewModel state management, navigation logic, and user flow coordination.
 *
 * TODO Ismail:
 *  1. Connect ViewModel to CryptoEngine and MetadataProcessor via constructor injection
 *  2. Implement loadMission() to fetch and expose mission data
 *  3. Implement sendMessage() — validate category, encrypt via CryptoEngine, normalise via MetadataProcessor
 *  4. Set up nav_graph.xml with all screen destinations
 *  5. Create MissionListFragment and ChatFragment
 */
class MissionViewModel : ViewModel() {

    private val _currentMission = MutableLiveData<Mission?>()
    val currentMission: LiveData<Mission?> = _currentMission

    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> = _messages

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    /**
     * Load a mission by ID and expose it to the UI.
     */
    fun loadMission(missionId: String) {
        // TODO: fetch from repository layer
        throw NotImplementedError("Ismail: implement loadMission")
    }

    /**
     * Encrypt and send a message in the current mission context.
     */
    fun sendMessage(plaintext: String, categoryOrdinal: Int) {
        // TODO: call CryptoEngine.encryptMessage, then MetadataProcessor.normalize
        throw NotImplementedError("Ismail: implement sendMessage")
    }
}
