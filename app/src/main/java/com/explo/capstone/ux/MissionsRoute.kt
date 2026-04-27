package com.explo.capstone.ux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.ui.AstraAppShell
import com.explo.capstone.ui.AstraTopBar
import com.explo.capstone.ui.MissionsContent
import com.explo.capstone.ui.MissionsIntent
import com.explo.capstone.ui.NavTab

/**
 * Route composable for the Missions list.
 * Hooks up the VM, collects state, and delegates rendering to [MissionsContent].
 */
@Composable
fun MissionsRoute(
    container: AppContainer,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    onMissionClick: (String) -> Unit,
) {
    val vm: MissionsViewModel = viewModel(factory = MissionsViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val callsign by vm.callsign.collectAsStateWithLifecycle()
    val clearanceLabel by vm.clearanceLabel.collectAsStateWithLifecycle()

    AstraAppShell(
        selectedTab = selectedTab,
        onTabSelect = onTabSelect,
        topBarContent = { AstraTopBar(callsign = callsign, clearanceLabel = clearanceLabel) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            MissionsContent(
                state = state,
                onIntent = { intent ->
                    vm.handle(intent)
                    // Handle navigation intents at the route level
                    when (intent) {
                        is MissionsIntent.Open -> onMissionClick(intent.missionId)
                        is MissionsIntent.EmergencyOverride -> onMissionClick(intent.missionId)
                        else -> {}
                    }
                }
            )
        }
    }
}
