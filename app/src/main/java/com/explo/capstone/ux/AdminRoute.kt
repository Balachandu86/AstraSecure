package com.explo.capstone.ux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material3.Icon
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

    init { refreshState() }

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
                container.clearanceRepository.assign(intent.userId, intent.missionId, intent.rankId)
                container.securityEventLog.emit(Severity.INFO, "Admin", "CLEARANCE_ASSIGNED // ${intent.missionId}")
                refreshState()
            }
            is AdminIntent.UnassignClearance -> viewModelScope.launch {
                container.clearanceRepository.unassign(intent.userId, intent.missionId)
                container.securityEventLog.emit(Severity.INFO, "Admin", "CLEARANCE_REMOVED // ${intent.missionId}")
                refreshState()
            }
        }
    }

    private fun refreshState() {
        _state.value = _state.value.copy(
            ranks              = container.store.ranks.value,
            channelCategories  = container.store.channelCategories.value,
            messageCategories  = container.store.messageCategories.value,
            missionTypes       = container.store.missionTypes.value,
            clearances         = container.store.clearanceAssignments.value,
            missions           = container.store.missions.value,
        )
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AdminViewModel(container) as T
    }
}

@Composable
fun AdminRoute(container: AppContainer, selectedTab: NavTab, onTabSelect: (NavTab) -> Unit) {
    val vm: AdminViewModel = viewModel(factory = AdminViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    AstraAppShell(
        selectedTab = selectedTab,
        onTabSelect = onTabSelect,
        topBarContent = {
            AstraTopBar(
                titleOverride = "ADMIN CONSOLE",
                callsign = "SCHEMA",
                clearanceLabel = "OPERATOR_ONLY",
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            AdminConsoleContent(state = state, onIntent = vm::handle)
        }
    }
}
