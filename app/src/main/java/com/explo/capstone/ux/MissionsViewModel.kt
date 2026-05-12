package com.explo.capstone.ux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.Mission
import com.explo.capstone.shared.MissionType
import com.explo.capstone.shared.Severity
import com.explo.capstone.shared.data.entity.ChannelRepository
import com.explo.capstone.shared.data.entity.MissionRepository
import com.explo.capstone.shared.data.log.SecurityEventLog
import com.explo.capstone.shared.data.schema.MissionTypeRepository
import com.explo.capstone.identity.IdentityManager
import com.explo.capstone.transport.MessageTransport
import com.explo.capstone.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Owner: Ismail Alam (credit attribution)
 *
 * Combines MissionRepository + MissionTypeRepository + ChannelRepository
 * to produce [MissionsUiState]. Uses safeCall for degraded-mode protocol.
 */
class MissionsViewModel(
    private val missionRepository: MissionRepository,
    private val missionTypeRepository: MissionTypeRepository,
    private val channelRepository: ChannelRepository,
    private val identityManager: IdentityManager,
    private val securityEventLog: SecurityEventLog,
    private val serverClient: MessageTransport,
) : ViewModel() {

    private val _state = MutableStateFlow<MissionsUiState>(MissionsUiState.Loading)
    val state: StateFlow<MissionsUiState> = _state.asStateFlow()

    // Callsign for the top bar — resolved from IdentityManager
    private val _callsign = MutableStateFlow("OPERATOR")
    val callsign: StateFlow<String> = _callsign.asStateFlow()

    private val _clearanceLabel = MutableStateFlow("LEVEL_??_ENCRYPTED")
    val clearanceLabel: StateFlow<String> = _clearanceLabel.asStateFlow()

    // Degraded subsystems
    private val degraded = mutableSetOf<DegradedSubsystem>()
    private var localUserId: String = ""

    init {
        loadIdentity()
        observeMissions()
    }

    private fun loadIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = safeCall { identityManager.getUserIdentity() }
            result.onSuccess { user ->
                if (user != null) {
                    localUserId = user.id
                    _callsign.value = user.displayName.uppercase()
                    _clearanceLabel.value = "KEY_${user.hardwareKeyId.take(8).uppercase()}"
                }
            }.onFailure {
                degraded.add(DegradedSubsystem.IDENTITY)
            }
        }
    }

    private fun observeMissions() {
        viewModelScope.launch {
            combine(
                missionRepository.missions,
                missionTypeRepository.types,
                serverClient.connectionState,
            ) { missions, types, connected ->
                buildContentState(missions, types, connected)
            }.collect { _state.value = it }
        }
    }

    private fun buildContentState(
        missions: List<Mission>,
        types: List<MissionType>,
        connected: Boolean = serverClient.isConnected,
    ): MissionsUiState {
        if (missions.isEmpty()) {
            return MissionsUiState.Empty(
                reason = "No missions assigned. Await further orders.",
                missionTypes = types,
                localUserId = localUserId,
            )
        }

        val typeMap = types.associateBy { it.id }
        val fallbackType = MissionType("unknown", "UNKNOWN", com.explo.capstone.shared.ColorToken.NEUTRAL, "Unknown type")

        val rows = missions.map { mission ->
            val channels = channelRepository.channelsForMission(mission.id).value
            MissionRow(
                mission = mission,
                type = typeMap[mission.typeId] ?: fallbackType,
                channelCount = channels.size,
                lastActivityFormatted = formatTimeAgo(mission.lastActivityMs),
            )
        }

        val signal = when {
            !connected          -> SignalStrength.OFFLINE
            degraded.isEmpty()  -> SignalStrength.STABLE
            else                -> SignalStrength.DEGRADED
        }

        val summary = DashboardSummary(
            activeLinks = rows.sumOf { it.channelCount },
            maxLinks = rows.sumOf { it.channelCount } + 2,
            signal = signal,
            encryption = "SIGNAL E2E",
            uplinkId = if (connected) "ONLINE" else "OFFLINE",
        )

        val logs = securityEventLog.recent(3).map { evt ->
            "> [${evt.source.uppercase()}]: ${evt.text}"
        }

        return MissionsUiState.Content(
            missions = rows,
            summary = summary,
            systemLogs = logs,
            missionTypes = types,
            degraded = degraded.toSet(),
            localUserId = localUserId,
        )
    }

    fun handle(intent: MissionsIntent) {
        when (intent) {
            is MissionsIntent.Refresh -> {
                securityEventLog.emit(Severity.INFO, "System", "MANUAL REFRESH REQUESTED")
                observeMissions()
            }
            is MissionsIntent.Open -> {
                securityEventLog.emit(Severity.INFO, "System", "MISSION_ACCESS // ${intent.missionId}")
            }
            is MissionsIntent.EmergencyOverride -> {
                securityEventLog.emit(Severity.ALERT, "System", "EMERGENCY_OVERRIDE // ${intent.missionId}")
            }
            is MissionsIntent.NavigateToProfile -> {}
            is MissionsIntent.NavigateToRedeem  -> {} // handled by the route composable
            is MissionsIntent.ShowCreateSheet -> {
                when (val s = _state.value) {
                    is MissionsUiState.Content -> _state.value = s.copy(showCreateSheet = true)
                    is MissionsUiState.Empty   -> _state.value = s.copy(showCreateSheet = true)
                    else -> {}
                }
            }
            is MissionsIntent.DismissCreateSheet -> {
                when (val s = _state.value) {
                    is MissionsUiState.Content -> _state.value = s.copy(showCreateSheet = false)
                    is MissionsUiState.Empty   -> _state.value = s.copy(showCreateSheet = false)
                    else -> {}
                }
            }
            is MissionsIntent.CreateMission -> {
                handle(MissionsIntent.DismissCreateSheet)
                viewModelScope.launch {
                    val creatorId = runCatching { identityManager.getUserIdentity()?.id }.getOrNull() ?: ""
                    runCatching { missionRepository.create(intent.name, intent.typeId, createdBy = creatorId) }
                        .onSuccess { securityEventLog.emit(Severity.INFO, "Missions", "MISSION_CREATED // ${intent.name}") }
                        .onFailure { securityEventLog.emit(Severity.WARN, "Missions", "MISSION_CREATE_FAILED // ${it.message}") }
                }
            }
        }
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    private fun formatTimeAgo(timestampMs: Long): String {
        val diff = System.currentTimeMillis() - timestampMs
        return when {
            diff < 60_000L        -> "${diff / 1000}s ago"
            diff < 3_600_000L     -> "${diff / 60_000}m ago"
            diff < 86_400_000L    -> {
                val hours = diff / 3_600_000L
                val mins = (diff % 3_600_000L) / 60_000L
                "${hours}h ${mins}m ago"
            }
            else                  -> "${diff / 86_400_000L}d ago"
        }
    }

    private suspend fun <T> safeCall(block: () -> T): Result<T> = runCatching { block() }
        .onFailure { if (it is NotImplementedError) { /* degraded mode — handled by caller */ } }

    // ─── Factory ─────────────────────────────────────────────────────────────

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MissionsViewModel(
                missionRepository = container.missionRepository,
                missionTypeRepository = container.missionTypeRepository,
                channelRepository = container.channelRepository,
                identityManager = container.identityManager,
                securityEventLog = container.securityEventLog,
                serverClient = container.serverClient,
            ) as T
        }
    }
}
