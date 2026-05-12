package com.explo.capstone.shared

import android.content.Context
import android.util.Log
import com.explo.capstone.BuildConfig
import com.explo.capstone.crypto.CryptoEngine
import com.explo.capstone.crypto.signal.AstraSignalProtocolStore
import com.explo.capstone.crypto.signal.SignalCryptoEngine
import com.explo.capstone.crypto.signal.SignalKeyManager
import com.explo.capstone.identity.IdentityManager
import com.explo.capstone.metadata.MetadataProcessor
import com.explo.capstone.shared.data.InMemoryStore
import com.explo.capstone.shared.data.PersistenceManager
import com.explo.capstone.shared.data.StoreSnapshot
import com.explo.capstone.shared.data.entity.*
import com.explo.capstone.shared.data.log.SecurityEventLog
import com.explo.capstone.shared.data.schema.*
import com.explo.capstone.transport.AstraApiClient
import com.explo.capstone.transport.MessageTransport
import com.explo.capstone.transport.SignalServerClient
import com.explo.capstone.transport.MessageType
import com.explo.capstone.transport.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual DI root — holds singleton instances of all modules and repositories.
 * Created once in [com.explo.capstone.AstraApp] and shared via Application.
 *
 * Adding Hilt later is non-breaking; this is a deliberate "ship-something-now" choice.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    // ─── Backing store ───────────────────────────────────────────────────────
    val store = InMemoryStore()

    // ─── Persistence ─────────────────────────────────────────────────────────
    val persistenceManager = PersistenceManager(appContext)
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Module APIs ─────────────────────────────────────────────────────────
    val cryptoEngine = CryptoEngine()
    val identityManager = IdentityManager(appContext)
    val metadataProcessor = MetadataProcessor()

    // ─── Signal Protocol ─────────────────────────────────────────────────────
    val signalStore = AstraSignalProtocolStore(appContext)
    val serverClient: MessageTransport = SignalServerClient(
        serverUrl = BuildConfig.SIGNAL_SERVER_URL,
        initialJwt = signalStore.loadJwt(),
        onJwtSaved = signalStore::saveJwt,
    )
    val signalCryptoEngine = SignalCryptoEngine(
        store = signalStore,
        transport = serverClient,
        localUserId = identityManager.getUserIdentity()?.id ?: "unknown",
    )
    val signalKeyManager = SignalKeyManager(signalStore, serverClient)

    // ─── REST API client ──────────────────────────────────────────────────────
    val apiClient = AstraApiClient(
        serverUrl = BuildConfig.SIGNAL_SERVER_URL,
        jwtProvider = signalStore::loadJwt,
    )
    @Volatile private var currentSyncEtag: String? = null

    // ─── Entity repositories ─────────────────────────────────────────────────
    val missionRepository: MissionRepository = InMemoryMissionRepository(store, apiClient)
    val channelRepository: ChannelRepository = InMemoryChannelRepository(store, apiClient)
    val messageRepository: MessageRepository = SignalMessageRepository(store, signalCryptoEngine, serverClient, metadataProcessor)
    val documentRepository: DocumentRepository = VaultDocumentRepository(store, identityManager)

    // ─── Schema repositories ─────────────────────────────────────────────────
    val inviteRepository: InviteRepository = RemoteInviteRepository(apiClient)

    val rankRepository: RankRepository = InMemoryRankRepository(store)
    val channelCategoryRepository: ChannelCategoryRepository = InMemoryChannelCategoryRepository(store)
    val messageCategoryRepository: MessageCategoryRepository = InMemoryMessageCategoryRepository(store)
    val missionTypeRepository: MissionTypeRepository = InMemoryMissionTypeRepository(store)
    val clearanceRepository: ClearanceRepository = InMemoryClearanceRepository(store, apiClient)

    // ─── Security event log ──────────────────────────────────────────────────
    val securityEventLog = SecurityEventLog()

    // ─── User display-name directory (userId → callsign) ─────────────────────
    val userDisplayNames: MutableMap<String, String> = mutableMapOf<String, String>().also { map ->
        identityManager.getUserIdentity()?.let { user -> map[user.id] = user.displayName }
    }

    // ─── Decrypted incoming messages (ChatViewModel observes this) ───────────
    private val _incomingDecrypted = MutableSharedFlow<DecryptedIncomingMessage>(
        extraBufferCapacity = 100,
    )
    val incomingDecrypted: SharedFlow<DecryptedIncomingMessage> = _incomingDecrypted.asSharedFlow()

    // ─── Burned operative alerts (AstraNavGraph observes this) ───────────────
    private val _operativeBurnedAlert = MutableSharedFlow<com.explo.capstone.transport.BurnedEvent>(
        extraBufferCapacity = 10,
    )
    val operativeBurnedAlert: SharedFlow<com.explo.capstone.transport.BurnedEvent> = _operativeBurnedAlert.asSharedFlow()

    // ─── Incoming message handler (Signal WebSocket) ──────────────────────────
    private val incomingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Persistence wiring ──────────────────────────────────────────────────

    init {
        store.onChanged = {
            persistenceScope.launch { persistenceManager.save(buildSnapshot()) }
        }

        // Replenish OPKs whenever the server says count is low
        incomingScope.launch {
            serverClient.keysNeeded.collect { count ->
                Log.d("AppContainer", "keysNeeded: count=$count — replenishing OPKs")
                runCatching { signalKeyManager.replenishPreKeys() }
                    .onFailure { Log.w("AppContainer", "OPK replenish failed: ${it.message}") }
            }
        }

        // Re-sync when server invalidates local state (another client mutated shared data)
        incomingScope.launch {
            serverClient.syncInvalidated.collect { version ->
                Log.d("AppContainer", "sync_invalidated version=$version — re-syncing")
                syncFromServer()
            }
        }

        // Alert all screens when a peer initiates a panic wipe
        incomingScope.launch {
            serverClient.operativeBurned.collect { event ->
                securityEventLog.emit(Severity.ALERT, "Security", "OPERATIVE_BURNED // ${event.callsign}")
                _operativeBurnedAlert.tryEmit(event)
            }
        }

        // Check SPK + Kyber age on start; rotate if it has been > 7 days
        incomingScope.launch {
            if (signalStore.isProvisioned()) {
                runCatching { signalKeyManager.rotateSignedPreKeyIfNeeded() }
                    .onFailure { Log.w("AppContainer", "SPK rotation check failed: ${it.message}") }
                runCatching { signalKeyManager.rotateKyberPreKeyIfNeeded() }
                    .onFailure { Log.w("AppContainer", "Kyber rotation check failed: ${it.message}") }
            }
        }

        // On startup: if keys exist but server registration never completed (no JWT persisted),
        // retry registration before opening the WebSocket. This covers the case where
        // provisioning previously succeeded locally but the server was unreachable (GAP-01 fix).
        incomingScope.launch {
            if (signalStore.isProvisioned() && signalStore.loadJwt().isEmpty()) {
                val user = identityManager.getUserIdentity()
                if (user != null) {
                    signalCryptoEngine.updateLocalUserId(user.id)
                    runCatching { signalKeyManager.retryServerRegistration(user.id, user.displayName) }
                        .onSuccess { Log.i("AppContainer", "Startup re-registration succeeded") }
                        .onFailure { Log.w("AppContainer", "Startup re-registration failed: ${it.message}") }
                }
            }
            startIncomingMessageStream()
            syncFromServer()  // hydrate store from server on every launch; noop if no JWT
        }
    }

    /** Called by ProvisioningViewModel after both local keys and server registration succeed. */
    fun onIdentityProvisioned(userId: String) {
        signalCryptoEngine.updateLocalUserId(userId)
        identityManager.getUserIdentity()?.let { user -> userDisplayNames[user.id] = user.displayName }
        incomingScope.launch { startIncomingMessageStream() }
        incomingScope.launch { syncFromServer() }
    }

    /**
     * Fetch [GET /api/sync] and replace the in-memory store with the server's
     * authoritative state. No-ops on 304 (store is already current).
     * Safe to call from any dispatcher.
     */
    suspend fun syncFromServer() {
        if (signalStore.loadJwt().isEmpty()) return
        withContext(Dispatchers.IO) {
            apiClient.sync(currentSyncEtag)
                .onSuccess { response ->
                    if (response == null) {
                        Log.d("AppContainer", "syncFromServer: 304 — already up to date")
                        return@onSuccess
                    }
                    currentSyncEtag = response.etag
                    store.updateRanks { response.schema.ranks.map { it.toDomain() } }
                    store.updateChannelCategories { response.schema.channelCategories.map { it.toDomain() } }
                    store.updateMessageCategories { response.schema.messageCategories.map { it.toDomain() } }
                    store.updateMissionTypes { response.schema.missionTypes.map { it.toDomain() } }
                    store.updateMissions { response.missions.map { it.toDomain() } }
                    store.updateChannels { response.channels.map { it.toDomain() } }
                    store.updateClearanceAssignments { response.clearances.map { it.toDomain() } }
                    Log.i("AppContainer", "syncFromServer: applied version=${response.version}")
                }
                .onFailure { Log.w("AppContainer", "syncFromServer failed: ${it.message}") }
        }
    }

    private suspend fun startIncomingMessageStream() {
        try {
            (serverClient as? SignalServerClient)?.observeIncoming()?.collect { msg ->
                var ack = false
                when (msg.messageType) {
                    MessageType.PREKEY_SIGNAL_MESSAGE, MessageType.WHISPER_MESSAGE -> {
                        runCatching {
                            val skdmBytes = signalCryptoEngine.decryptFromAddress(msg.senderId, msg.ciphertext, msg.messageType)
                            signalCryptoEngine.processSenderKeyDistribution(msg.senderId, skdmBytes)
                            Log.d("AppContainer", "Processed incoming SKDM from ${msg.senderId}")
                        }.onSuccess { ack = true }
                         .onFailure {
                             securityEventLog.emit(com.explo.capstone.shared.Severity.WARN, "Transport", "SKDM_DECRYPT_FAILED // ${msg.senderId}")
                             Log.w("AppContainer", "SKDM processing failed: ${it.message}")
                         }
                    }
                    MessageType.SENDER_KEY_MESSAGE -> {
                        runCatching {
                            val result = (messageRepository as? SignalMessageRepository)?.receive(
                                channelId = msg.channelId,
                                missionKeyAlias = "",
                                ciphertext = msg.ciphertext,
                                senderId = msg.senderId,
                                categoryId = "mc_standard"
                            )
                            val (message, plaintext) = result?.getOrThrow() ?: return@runCatching
                            _incomingDecrypted.tryEmit(
                                DecryptedIncomingMessage(
                                    channelId = msg.channelId,
                                    senderId = msg.senderId,
                                    plaintextBytes = plaintext,
                                    timestampMs = System.currentTimeMillis(),
                                    messageId = message.id,
                                )
                            )
                        }.onSuccess { ack = true }
                         .onFailure {
                             securityEventLog.emit(com.explo.capstone.shared.Severity.WARN, "Transport", "CHANNEL_MSG_DECRYPT_FAILED")
                             Log.w("AppContainer", "Channel message processing failed: ${it.message}")
                         }
                    }
                    MessageType.PLAIN_TEXT -> {
                        runCatching {
                            val message = Message(
                                id = "MSG-${java.util.UUID.randomUUID().toString().take(8).uppercase()}",
                                channelId = msg.channelId,
                                senderId = msg.senderId,
                                categoryId = "mc_standard",
                                paddedSizeBytes = msg.ciphertext.size,
                                timestampMs = System.currentTimeMillis(),
                                plaintextContent = String(msg.ciphertext, Charsets.UTF_8),
                            )
                            store.updateMessages { it + message }
                            _incomingDecrypted.tryEmit(
                                DecryptedIncomingMessage(
                                    channelId = msg.channelId,
                                    senderId = msg.senderId,
                                    plaintextBytes = msg.ciphertext,
                                    timestampMs = System.currentTimeMillis(),
                                    messageId = message.id,
                                )
                            )
                        }.onSuccess { ack = true }
                         .onFailure { Log.w("AppContainer", "Plain message processing failed: ${it.message}") }
                    }
                    else -> ack = true // unknown type — remove from queue
                }
                // Only ACK after successful decryption so failed messages stay in queue
                if (ack) try { serverClient.acknowledge(listOf(msg.id)) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w("AppContainer", "Incoming message stream error: ${e.message}")
        }
    }

    fun loadSnapshot(snapshot: StoreSnapshot) {
        store.updateMissions { snapshot.missions }
        store.updateChannels { snapshot.channels }
        store.updateRanks { snapshot.ranks }
        store.updateChannelCategories { snapshot.channelCategories }
        store.updateMessageCategories { snapshot.messageCategories }
        store.updateMissionTypes { snapshot.missionTypes }
        store.updateClearanceAssignments { snapshot.clearanceAssignments }
        store.updateMessages { snapshot.messages }
    }

    private fun buildSnapshot() = StoreSnapshot(
        missions = store.missions.value,
        channels = store.channels.value,
        ranks = store.ranks.value,
        channelCategories = store.channelCategories.value,
        messageCategories = store.messageCategories.value,
        missionTypes = store.missionTypes.value,
        clearanceAssignments = store.clearanceAssignments.value,
        messages = store.messages.value,
    )
}

data class DecryptedIncomingMessage(
    val channelId: String,
    val senderId: String,
    val plaintextBytes: ByteArray,
    val timestampMs: Long,
    val messageId: String,
)
