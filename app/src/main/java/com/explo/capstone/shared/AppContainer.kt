package com.explo.capstone.shared

import android.content.Context
import com.explo.capstone.crypto.CryptoEngine
import com.explo.capstone.identity.IdentityManager
import com.explo.capstone.metadata.MetadataProcessor
import com.explo.capstone.shared.data.InMemoryStore
import com.explo.capstone.shared.data.PersistenceManager
import com.explo.capstone.shared.data.StoreSnapshot
import com.explo.capstone.shared.data.entity.*
import com.explo.capstone.shared.data.log.SecurityEventLog
import com.explo.capstone.shared.data.schema.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // ─── Entity repositories ─────────────────────────────────────────────────
    val missionRepository: MissionRepository = InMemoryMissionRepository(store)
    val channelRepository: ChannelRepository = InMemoryChannelRepository(store)
    val messageRepository: MessageRepository = InMemoryMessageRepository(store, cryptoEngine, metadataProcessor)
    val documentRepository: DocumentRepository = InMemoryDocumentRepository(store)

    // ─── Schema repositories ─────────────────────────────────────────────────
    val rankRepository: RankRepository = InMemoryRankRepository(store)
    val channelCategoryRepository: ChannelCategoryRepository = InMemoryChannelCategoryRepository(store)
    val messageCategoryRepository: MessageCategoryRepository = InMemoryMessageCategoryRepository(store)
    val missionTypeRepository: MissionTypeRepository = InMemoryMissionTypeRepository(store)
    val clearanceRepository: ClearanceRepository = InMemoryClearanceRepository(store)

    // ─── Security event log ──────────────────────────────────────────────────
    val securityEventLog = SecurityEventLog()

    // ─── Persistence wiring ──────────────────────────────────────────────────

    init {
        store.onChanged = {
            persistenceScope.launch { persistenceManager.save(buildSnapshot()) }
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
