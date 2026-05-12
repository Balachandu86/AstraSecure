package com.explo.capstone.ux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        val user = runCatching { container.identityManager.getUserIdentity() }.getOrNull()
            ?: run { _state.value = ProfileUiState.Loading; return }

        val provisioned = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(user.provisionedAtMs))

        val rankMap = container.store.ranks.value.associateBy { it.id }
        val missionMap = container.store.missions.value.associateBy { it.id }

        val clearances = container.store.clearanceAssignments.value
            .filter { it.userId == user.id }
            .mapNotNull { ca ->
                val mission = missionMap[ca.missionId] ?: return@mapNotNull null
                val rank = rankMap[ca.rankId] ?: return@mapNotNull null
                ProfileMissionClearance(
                    missionId = mission.id,
                    missionName = mission.name,
                    rankName = rank.name,
                    rankLevel = rank.level,
                    rankColor = rank.color,
                )
            }
            .sortedByDescending { it.rankLevel }

        val isAdmin = clearances.any { it.rankLevel >= 9 }

        _state.value = ProfileUiState.Content(
            callsign = user.displayName.uppercase(),
            hardwareKeyId = user.hardwareKeyId,
            provisionedLabel = provisioned,
            clearances = clearances,
            isAdmin = isAdmin,
        )
    }

    fun saveCallsign(newName: String) {
        val trimmed = newName.trim().uppercase()
        if (trimmed.isEmpty()) return
        container.identityManager.updateDisplayName(trimmed)
        load()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(container) as T
    }
}

@Composable
fun ProfileRoute(
    container: AppContainer,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    onBack: () -> Unit,
    onAdminClick: (() -> Unit)? = null,
) {
    val vm: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    AstraAppShell(
        selectedTab = selectedTab,
        onTabSelect = onTabSelect,
        topBarContent = {
            AstraTopBar(
                titleOverride = "OPERATOR PROFILE",
                callsign = (state as? ProfileUiState.Content)?.callsign ?: "OPERATOR",
                clearanceLabel = "IDENTITY",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AstraTheme.Primary)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            ProfileContent(
                state = state,
                onSaveCallsign = { vm.saveCallsign(it) },
                onAdminClick = onAdminClick,
            )
        }
    }
}
