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
import com.explo.capstone.transport.MessageTransport
import com.explo.capstone.transport.SignalServerClient
import com.explo.capstone.transport.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    val serverClient: MessageTransport = SignalServerClient(BuildConfig.SIGNAL_SERVER_URL)
    val signalCryptoEngine = SignalCryptoEngine(signalStore, serverClient, identityManager.getUserIdentity()?.id ?: "unknown")
    val signalKeyManager = SignalKeyManager(signalStore, serverClient)

    // ─── Entity repositories ─────────────────────────────────────────────────
    val missionRepository: MissionRepository = InMemoryMissionRepository(store)
    val channelRepository: ChannelRepository = InMemoryChannelRepository(store)
    val messageRepository: MessageRepository = SignalMessageRepository(store, signalCryptoEngine, serverClient, metadataProcessor)
    val documentRepository: DocumentRepository = InMemoryDocumentRepository(store)

    // ─── Schema repositories ─────────────────────────────────────────────────
    val rankRepository: RankRepository = InMemoryRankRepository(store)
    val channelCategoryRepository: ChannelCategoryRepository = InMemoryChannelCategoryRepository(store)
    val messageCategoryRepository: MessageCategoryRepository = InMemoryMessageCategoryRepository(store)
    val missionTypeRepository: MissionTypeRepository = InMemoryMissionTypeRepository(store)
    val clearanceRepository: ClearanceRepository = InMemoryClearanceRepository(store)

    // ─── Security event log ──────────────────────────────────────────────────
    val securityEventLog = SecurityEventLog()

    // ─── Decrypted incoming messages (ChatViewModel observes this) ───────────
    private val _incomingDecrypted = MutableSharedFlow<DecryptedIncomingMessage>(
        extraBufferCapacity = 100,
    )
    val incomingDecrypted: SharedFlow<DecryptedIncomingMessage> = _incomingDecrypted.asSharedFlow()

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

        // Check SPK age on start; rotate if it has been > 7 days
        incomingScope.launch {
            if (signalStore.isProvisioned()) {
                runCatching { signalKeyManager.rotateSignedPreKeyIfNeeded() }
                    .onFailure { Log.w("AppContainer", "SPK rotation check failed: ${it.message}") }
            }
        }

        // Listen for incoming messages from Signal server and process them
        incomingScope.launch {
            try {
                (serverClient as? SignalServerClient)?.observeIncoming()?.collect { msg ->
                    when (msg.messageType) {
                        MessageType.PREKEY_SIGNAL_MESSAGE, MessageType.WHISPER_MESSAGE -> {
                            // Decrypt 1:1 session message (contains SKDM)
                            runCatching {
                                val skdmBytes = signalCryptoEngine.decryptFromAddress(msg.senderId, msg.ciphertext, msg.messageType)
                                signalCryptoEngine.processSenderKeyDistribution(msg.senderId, skdmBytes)
                                Log.d("AppContainer", "Processed incoming SKDM from ${msg.senderId}")
                            }.onFailure { Log.w("AppContainer", "SKDM processing failed: ${it.message}") }
                        }
                        MessageType.SENDER_KEY_MESSAGE -> {
                            // Decrypt channel message and broadcast plaintext to any open ChatViewModel
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
                            }.onFailure { Log.w("AppContainer", "Channel message processing failed: ${it.message}") }
                        }
                    }
                    // Acknowledge after processing
                    try {
                        serverClient.acknowledge(listOf(msg.id))
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w("AppContainer", "Incoming message stream error: ${e.message}")
            }
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
    }

    private fun buildSnapshot() = StoreSnapshot(
        missions = store.missions.value,
        channels = store.channels.value,
        ranks = store.ranks.value,
        channelCategories = store.channelCategories.value,
        messageCategories = store.messageCategories.value,
        missionTypes = store.missionTypes.value,
        clearanceAssignments = store.clearanceAssignments.value,
    )
}

data class DecryptedIncomingMessage(
    val channelId: String,
    val senderId: String,
    val plaintextBytes: ByteArray,
    val timestampMs: Long,
    val messageId: String,
)
