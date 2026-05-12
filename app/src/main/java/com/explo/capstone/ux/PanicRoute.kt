package com.explo.capstone.ux

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.PackageUtils
import com.explo.capstone.shared.Severity
import com.explo.capstone.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PanicViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<PanicUiState>(PanicUiState.Standby)
    val state: StateFlow<PanicUiState> = _state.asStateFlow()

    fun handle(intent: PanicIntent) {
        when (intent) {
            is PanicIntent.SlideCommit -> executeWipe()
            is PanicIntent.Abort -> _state.value = PanicUiState.Standby
            is PanicIntent.SlideProgress -> {} // handled locally in composable
        }
    }

    private fun executeWipe() {
        viewModelScope.launch {
            val userId = runCatching { container.identityManager.getUserIdentity()?.id }.getOrNull() ?: ""

            val phases: List<Pair<String, suspend () -> Unit>> = listOf(
                "REVOKING_REMOTE_TOKENS" to {
                    // tombstone() triggers the server-side operative_burned WebSocket push to all peers
                    runCatching { container.apiClient.tombstone() }
                    safeRun { container.identityManager.revokeRemoteTokens() }
                    if (userId.isNotEmpty()) container.serverClient.deleteUser(userId)
                },
                "OVERWRITING_LOCAL_DATA" to { container.missionRepository.wipeAll(); container.channelRepository.wipeAll(); container.messageRepository.wipeAll(); container.documentRepository.wipeAll(); container.persistenceManager.clear() },
                "INVALIDATING_KEYS" to { safeRun { container.cryptoEngine.invalidateAllKeys() } },
                "WIPING_SCHEMA" to { container.rankRepository.wipeAll(); container.channelCategoryRepository.wipeAll(); container.messageCategoryRepository.wipeAll(); container.missionTypeRepository.wipeAll(); container.clearanceRepository.wipeAll() },
                "FINALIZING" to { safeRun { container.signalStore.wipeAll() }; safeRun { container.identityManager.wipeAll() }; container.store.clear(); container.securityEventLog.clear(); safeRun { container.identityManager.writeTombstone() } },
            )
            phases.forEachIndexed { i, (label, action) ->
                _state.value = PanicUiState.Wiping(label, ((i + 1) * 100) / phases.size)
                try { action() } catch (_: Exception) { container.securityEventLog.emit(Severity.ALERT, "PanicWipe", "PHASE_FAILED // $label") }
                delay(600) // perceptible phase timing
            }
            _state.value = PanicUiState.Tombstoned
        }
    }

    private fun safeRun(block: () -> Unit) { try { block() } catch (_: NotImplementedError) {} }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PanicViewModel(container) as T
    }
}

@Composable
fun PanicRoute(container: AppContainer, selectedTab: NavTab, onTabSelect: (NavTab) -> Unit) {
    val vm: PanicViewModel = viewModel(factory = PanicViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AstraAppShell(selectedTab = selectedTab, onTabSelect = onTabSelect) { padding ->
        Box(Modifier.padding(padding)) {
            PanicContent(state, onUninstall = {
                PackageUtils.uninstallApp(context)
            }) { vm.handle(it) }
        }
    }
}
