package com.explo.capstone.ux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.ui.AstraAppShell
import com.explo.capstone.ui.AstraTopBar
import com.explo.capstone.ui.MissionsContent
import com.explo.capstone.ui.MissionsIntent
import com.explo.capstone.ui.NavTab
import kotlinx.coroutines.launch

/**
 * Route composable for the Missions list.
 * Hooks up the VM, collects state, and delegates rendering to [MissionsContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionsRoute(
    container: AppContainer,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    onMissionClick: (String) -> Unit,
    onProfileClick: () -> Unit = {},
    onJoinClick: () -> Unit = {},
) {
    val vm: MissionsViewModel = viewModel(factory = MissionsViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val callsign by vm.callsign.collectAsStateWithLifecycle()
    val clearanceLabel by vm.clearanceLabel.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    AstraAppShell(
        selectedTab = selectedTab,
        onTabSelect = onTabSelect,
        topBarContent = { AstraTopBar(callsign = callsign, clearanceLabel = clearanceLabel, onProfileClick = onProfileClick) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    coroutineScope.launch {
                        isRefreshing = true
                        container.syncFromServer()
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                MissionsContent(
                    state = state,
                    onIntent = { intent ->
                        vm.handle(intent)
                        when (intent) {
                            is MissionsIntent.Open -> onMissionClick(intent.missionId)
                            is MissionsIntent.EmergencyOverride -> onMissionClick(intent.missionId)
                            is MissionsIntent.NavigateToRedeem -> onJoinClick()
                            else -> {}
                        }
                    }
                )
            }
        }
    }
}
