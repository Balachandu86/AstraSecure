package com.explo.capstone.ux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.DeleteResult
import com.explo.capstone.shared.Severity
import com.explo.capstone.shared.data.entity.InviteUri
import com.explo.capstone.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owner: Ismail Alam
 * Admin Console ViewModel — drives all 5 schema tabs with live store reads.
 */
class AdminViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init {
        refreshState()
        // Re-pull from the store on every change so sync-driven updates
        // (e.g. a pending participant appearing after a remote redeem)
        // surface in the Admin Console without re-navigating.
        viewModelScope.launch {
            container.store.missions.collect { refreshState() }
        }
        viewModelScope.launch {
            container.store.clearanceAssignments.collect { refreshState() }
        }
    }

    fun handle(intent: AdminIntent) {
        when (intent) {
            is AdminIntent.SelectTab -> _state.value = _state.value.copy(activeTab = intent.tab)
            is AdminIntent.OpenEditSheet -> _state.value = _state.value.copy(editSheet = intent.sheet)
            is AdminIntent.DismissEditSheet -> _state.value = _state.value.copy(editSheet = null)
            is AdminIntent.DismissDeleteError -> _state.value = _state.value.copy(deleteError = null)

            // ── Ranks ────────────────────────────────────────────────────────
            is AdminIntent.SaveRank -> viewModelScope.launch {
                if (intent.id == null) {
                    container.rankRepository.create(intent.name, intent.level, intent.color)
                    container.securityEventLog.emit(Severity.INFO, "Admin", "RANK_CREATED // ${intent.name}")
                } else {
                    container.rankRepository.update(intent.id, intent.name, intent.level, intent.color)
                    container.securityEventLog.emit(Severity.INFO, "Admin", "RANK_UPDATED // ${intent.name}")
                }
                _state.value = _state.value.copy(editSheet = null)
                refreshState()
            }
            is AdminIntent.DeleteRank -> viewModelScope.launch {
                when (val result = container.rankRepository.delete(intent.id)) {
                    is DeleteResult.Deleted -> {
                        container.securityEventLog.emit(Severity.INFO, "Admin", "RANK_DELETED // ${intent.id}")
                        refreshState()
                    }
                    is DeleteResult.BlockedBy -> {
                        val msg = if (result.refs.isEmpty()) "> SYSTEM RANKS CANNOT BE DELETED"
                                  else "> RANK IN USE — REASSIGN CLEARANCES FIRST"
                        _state.value = _state.value.copy(deleteError = AdminDeleteError(msg, result.refs))
                    }
                }
            }

            // ── Channel Categories ───────────────────────────────────────────
            is AdminIntent.SaveChannelCategory -> viewModelScope.launch {
                if (intent.id == null) {
                    container.channelCategoryRepository.create(intent.name, intent.accent, intent.viewClearance, intent.postClearance)
                    container.securityEventLog.emit(Severity.INFO, "Admin", "CHAN_CAT_CREATED // ${intent.name}")
                } else {
                    container.channelCategoryRepository.update(intent.id, intent.name, intent.accent, intent.viewClearance, intent.postClearance)
                    container.securityEventLog.emit(Severity.INFO, "Admin", "CHAN_CAT_UPDATED // ${intent.name}")
                }
                _state.value = _state.value.copy(editSheet = null)
                refreshState()
            }
            is AdminIntent.DeleteChannelCategory -> viewModelScope.launch {
                when (val result = container.channelCategoryRepository.delete(intent.id)) {
                    is DeleteResult.Deleted -> { refreshState() }
                    is DeleteResult.BlockedBy -> {
                        val msg = if (result.refs.isEmpty()) "> SYSTEM CATEGORIES CANNOT BE DELETED"
                                  else "> CATEGORY IN USE — REASSIGN CHANNELS FIRST"
                        _state.value = _state.value.copy(deleteError = AdminDeleteError(msg, result.refs))
                    }
                }
            }

            // ── Message Categories ───────────────────────────────────────────
            is AdminIntent.SaveMessageCategory -> viewModelScope.launch {
                if (intent.id == null) {
                    container.messageCategoryRepository.create(intent.name, intent.accent, intent.minClearance)
                    container.securityEventLog.emit(Severity.INFO, "Admin", "MSG_CAT_CREATED // ${intent.name}")
                } else {
                    container.messageCategoryRepository.update(intent.id, intent.name, intent.accent, intent.minClearance)
                    container.securityEventLog.emit(Severity.INFO, "Admin", "MSG_CAT_UPDATED // ${intent.name}")
                }
                _state.value = _state.value.copy(editSheet = null)
                refreshState()
            }
            is AdminIntent.DeleteMessageCategory -> viewModelScope.launch {
                when (val result = container.messageCategoryRepository.delete(intent.id)) {
                    is DeleteResult.Deleted -> { refreshState() }
                    is DeleteResult.BlockedBy -> {
                        val msg = if (result.refs.isEmpty()) "> SYSTEM CATEGORIES CANNOT BE DELETED"
                                  else "> CATEGORY IN USE BY MESSAGES"
                        _state.value = _state.value.copy(deleteError = AdminDeleteError(msg, result.refs))
                    }
                }
            }

            // ── Mission Types ────────────────────────────────────────────────
            is AdminIntent.SaveMissionType -> viewModelScope.launch {
                if (intent.id == null) {
                    container.missionTypeRepository.create(intent.name, intent.accent, intent.description)
                    container.securityEventLog.emit(Severity.INFO, "Admin", "MISSION_TYPE_CREATED // ${intent.name}")
                } else {
                    container.missionTypeRepository.update(intent.id, intent.name, intent.accent, intent.description)
                    container.securityEventLog.emit(Severity.INFO, "Admin", "MISSION_TYPE_UPDATED // ${intent.name}")
                }
                _state.value = _state.value.copy(editSheet = null)
                refreshState()
            }
            is AdminIntent.DeleteMissionType -> viewModelScope.launch {
                when (val result = container.missionTypeRepository.delete(intent.id)) {
                    is DeleteResult.Deleted -> { refreshState() }
                    is DeleteResult.BlockedBy -> {
                        val msg = if (result.refs.isEmpty()) "> SYSTEM TYPES CANNOT BE DELETED"
                                  else "> TYPE IN USE BY MISSIONS — REASSIGN FIRST"
                        _state.value = _state.value.copy(deleteError = AdminDeleteError(msg, result.refs))
                    }
                }
            }

            // ── Clearance ────────────────────────────────────────────────────
            is AdminIntent.AssignClearance -> viewModelScope.launch {
                val rankName = container.store.ranks.value.find { it.id == intent.rankId }?.name ?: intent.rankId
                val shortUser = intent.userId.take(12).uppercase()
                container.clearanceRepository.assign(intent.userId, intent.missionId, intent.rankId)
                    .onSuccess {
                        container.securityEventLog.emit(Severity.INFO, "Admin", "CLEARANCE_ASSIGNED // ${intent.missionId}")
                        _state.value = _state.value.copy(
                            inviteError = null,
                            successMessage = "CLEARANCE UPDATED — $shortUser → $rankName",
                        )
                    }
                    .onFailure { e ->
                        container.securityEventLog.emit(Severity.WARN, "Admin", "CLEARANCE_ASSIGN_FAILED // ${e.message}")
                        _state.value = _state.value.copy(
                            inviteError = "CLEARANCE ASSIGN FAILED — ${e.message ?: "UNKNOWN"}",
                            successMessage = null,
                        )
                    }
                refreshState()
            }
            is AdminIntent.UnassignClearance -> viewModelScope.launch {
                val shortUser = intent.userId.take(12).uppercase()
                container.clearanceRepository.unassign(intent.userId, intent.missionId)
                    .onSuccess {
                        container.securityEventLog.emit(Severity.INFO, "Admin", "CLEARANCE_REMOVED // ${intent.missionId}")
                        _state.value = _state.value.copy(
                            inviteError = null,
                            successMessage = "CLEARANCE REMOVED — $shortUser",
                        )
                    }
                    .onFailure { e ->
                        container.securityEventLog.emit(Severity.WARN, "Admin", "CLEARANCE_REMOVE_FAILED // ${e.message}")
                        _state.value = _state.value.copy(
                            inviteError = "CLEARANCE REMOVE FAILED — ${e.message ?: "UNKNOWN"}",
                            successMessage = null,
                        )
                    }
                refreshState()
            }
            is AdminIntent.IssueInvite -> viewModelScope.launch {
                val result = container.inviteRepository.issue(intent.missionId)
                result.onSuccess { resp ->
                    container.securityEventLog.emit(Severity.INFO, "Invites", "INVITE_ISSUED // ${intent.missionId}")
                    _state.value = _state.value.copy(
                        activeInvite = ActiveInvite(
                            missionId = resp.missionId,
                            token = resp.token,
                            uri = InviteUri.encode(resp.token),
                            expiresAtIso = resp.expiresAt,
                        ),
                        inviteError = null,
                    )
                }.onFailure { e ->
                    _state.value = _state.value.copy(
                        inviteError = "INVITE FAILED — ${e.message ?: "UNKNOWN"}",
                    )
                }
            }
            is AdminIntent.ConfirmParticipant -> viewModelScope.launch {
                val result = container.inviteRepository.confirm(intent.missionId, intent.userId)
                result.onSuccess {
                    container.securityEventLog.emit(Severity.INFO, "Invites", "PARTICIPANT_CONFIRMED // ${intent.missionId}")
                    container.syncFromServer()
                    refreshState()
                }.onFailure { e ->
                    _state.value = _state.value.copy(
                        inviteError = "CONFIRM FAILED — ${e.message ?: "UNKNOWN"}",
                    )
                }
            }
            is AdminIntent.RevokeInvite -> viewModelScope.launch {
                val result = container.inviteRepository.revoke(intent.token)
                result.onSuccess {
                    container.securityEventLog.emit(Severity.INFO, "Invites", "INVITE_REVOKED")
                    _state.value = _state.value.copy(activeInvite = null, inviteError = null)
                }.onFailure { e ->
                    _state.value = _state.value.copy(
                        inviteError = "REVOKE FAILED — ${e.message ?: "UNKNOWN"}",
                    )
                }
            }
            is AdminIntent.DismissActiveInvite -> {
                _state.value = _state.value.copy(activeInvite = null)
            }
            is AdminIntent.DismissInviteError -> {
                _state.value = _state.value.copy(inviteError = null)
            }
            is AdminIntent.DismissSuccess -> {
                _state.value = _state.value.copy(successMessage = null)
            }
        }
    }

    fun refreshState() {
        val localUserId = runCatching { container.identityManager.getUserIdentity() }.getOrNull()?.id ?: "user_local"
        _state.value = _state.value.copy(
            ranks              = container.store.ranks.value,
            channelCategories  = container.store.channelCategories.value,
            messageCategories  = container.store.messageCategories.value,
            missionTypes       = container.store.missionTypes.value,
            clearances         = container.store.clearanceAssignments.value,
            missions           = container.store.missions.value,
            localUserId        = localUserId,
        )
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AdminViewModel(container) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminRoute(
    container: AppContainer,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    onProfileClick: (() -> Unit)? = null,
) {
    val vm: AdminViewModel = viewModel(factory = AdminViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    AstraAppShell(
        selectedTab = selectedTab,
        onTabSelect = onTabSelect,
        topBarContent = {
            AstraTopBar(
                titleOverride = "ADMIN CONSOLE",
                callsign = "SCHEMA",
                clearanceLabel = "OPERATOR_ONLY",
                onProfileClick = onProfileClick,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    coroutineScope.launch {
                        isRefreshing = true
                        container.syncFromServer()
                        vm.refreshState()
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                AdminConsoleContent(state = state, onIntent = vm::handle)
            }
        }
    }
}
