package com.explo.capstone.ux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.Severity
import com.explo.capstone.ui.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChannelListViewModel(
    private val missionId: String,
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow<ChannelListUiState>(ChannelListUiState.Loading)
    val state: StateFlow<ChannelListUiState> = _state.asStateFlow()

    init { load() }

    fun reload() { load() }

    private fun load() {
        val mission = container.store.missions.value.find { it.id == missionId }
        if (mission == null) { _state.value = ChannelListUiState.MissionNotFound(missionId); return }

        val type = container.store.missionTypes.value.find { it.id == mission.typeId }
        val channels = container.store.channels.value.filter { it.missionId == missionId }
        val categories = container.store.channelCategories.value
        val ranks = container.store.ranks.value

        val userId = runCatching { container.identityManager.getUserIdentity() }.getOrNull()?.id ?: "user_local"
        val userRank = container.clearanceRepository.clearanceFor(userId, missionId).value

        val sections = categories.mapNotNull { cat ->
            val matching = channels.filter { it.categoryId == cat.id }.map { ch ->
                ChannelRow(
                    channel = ch,
                    canView = (userRank?.level ?: 0) >= ch.minClearanceToView,
                    canPost  = (userRank?.level ?: 0) >= ch.minClearanceToPost,
                    requiredRankName = requiredRankNameFor(ranks, ch.minClearanceToView),
                )
            }
            if (matching.isNotEmpty()) ChannelSection(cat, matching) else null
        }

        val canCreate = (userRank?.level ?: 0) >= 5 || mission.createdBy == userId
        _state.value = ChannelListUiState.Content(mission, type, userRank, sections, categories, canCreateChannel = canCreate)
        container.securityEventLog.emit(Severity.INFO, "ChannelList", "ACCESSED_MISSION // $missionId // ${channels.size} CHANNELS")
    }

    fun handle(intent: ChannelListIntent) {
        when (intent) {
            is ChannelListIntent.Back  -> { /* handled by route */ }
            is ChannelListIntent.Open  -> { /* handled by route */ }
            is ChannelListIntent.ShowNewChannelSheet -> {
                (_state.value as? ChannelListUiState.Content)?.let {
                    _state.value = it.copy(showNewChannelSheet = true)
                }
            }
            is ChannelListIntent.DismissNewChannelSheet -> {
                (_state.value as? ChannelListUiState.Content)?.let {
                    _state.value = it.copy(showNewChannelSheet = false)
                }
            }
            is ChannelListIntent.CreateChannel -> createChannel(intent.name, intent.description, intent.categoryId)
        }
    }

    private fun createChannel(name: String, description: String, categoryId: String) {
        val current = _state.value as? ChannelListUiState.Content ?: return
        val cat = current.categories.find { it.id == categoryId } ?: return
        val creatorId = runCatching { container.identityManager.getUserIdentity()?.id }.getOrNull() ?: ""
        viewModelScope.launch {
            container.channelRepository.create(
                missionId    = missionId,
                name         = name,
                description  = description,
                categoryId   = categoryId,
                minClearanceToView = cat.defaultMinClearanceToView,
                minClearanceToPost = cat.defaultMinClearanceToPost,
                createdBy    = creatorId,
            )
            container.securityEventLog.emit(Severity.INFO, "ChannelList", "CHANNEL_CREATED // $name")
            _state.value = current.copy(showNewChannelSheet = false)
            load() // refresh with new channel
        }
    }

    private fun requiredRankNameFor(ranks: List<com.explo.capstone.shared.Rank>, minLevel: Int): String =
        ranks.filter { it.level >= minLevel }.minByOrNull { it.level }?.name ?: "LVL_$minLevel"

    class Factory(private val missionId: String, private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChannelListViewModel(missionId, container) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListRoute(
    missionId: String, container: AppContainer,
    selectedTab: NavTab, onTabSelect: (NavTab) -> Unit,
    onChannelClick: (String) -> Unit, onBack: () -> Unit,
    onProfileClick: (() -> Unit)? = null,
) {
    val vm: ChannelListViewModel = viewModel(factory = ChannelListViewModel.Factory(missionId, container))
    val state by vm.state.collectAsStateWithLifecycle()
    val mission = (state as? ChannelListUiState.Content)?.mission
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    AstraAppShell(
        selectedTab = selectedTab, onTabSelect = onTabSelect,
        topBarContent = {
            AstraTopBar(
                callsign = mission?.name?.uppercase() ?: "MISSION",
                clearanceLabel = "ID: $missionId",
                onProfileClick = onProfileClick,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AstraTheme.Primary)
                    }
                },
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
                        vm.reload()
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                ChannelListContent(state) { intent ->
                    when (intent) {
                        is ChannelListIntent.Back -> onBack()
                        is ChannelListIntent.Open -> onChannelClick(intent.channelId)
                        else -> vm.handle(intent)
                    }
                }
            }
        }
    }
}
