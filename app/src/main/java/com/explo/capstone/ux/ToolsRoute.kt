package com.explo.capstone.ux

import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.crypto.CryptoEngine
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.ui.*
import kotlinx.coroutines.flow.*

class ToolsViewModel(private val cryptoEngine: CryptoEngine) : ViewModel() {

    private val _state = MutableStateFlow(ToolsState())
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    fun updateState(newState: ToolsState) {
        _state.value = newState
    }

    fun execute() {
        val s = _state.value
        if (s.input.isBlank()) { _state.value = s.copy(output = "> ERROR: EMPTY_INPUT", outputError = true); return }
        _state.value = when {
            s.algo == Algorithm.AES_256_GCM -> executeAes(s)
            else -> s // non-AES handled inline by ToolsContent via updateState
        }
    }

    // Called when ToolsContent already computed the result for non-AES ops
    fun setOutput(newState: ToolsState) { _state.value = newState }

    fun wipe() {
        _state.value = _state.value.copy(input = "", output = "", key = "", outputError = false)
    }

    private fun executeAes(s: ToolsState): ToolsState {
        if (s.key.isBlank()) return s.copy(output = "> ERROR: KEY_REQUIRED_FOR_AES", outputError = true)
        return try {
            val result = if (s.direction == Direction.ENCODE) {
                val encrypted = cryptoEngine.encryptWithPassword(s.key, s.input.toByteArray())
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            } else {
                val raw = Base64.decode(s.input.trim(), Base64.NO_WRAP)
                String(cryptoEngine.decryptWithPassword(s.key, raw))
            }
            s.copy(output = result, outputError = false)
        } catch (e: Exception) {
            s.copy(output = "> ERROR: ${e.message?.uppercase()?.take(60) ?: "AES_FAILED"}", outputError = true)
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ToolsViewModel(container.cryptoEngine) as T
    }
}

@Composable
fun ToolsRoute(container: AppContainer, selectedTab: NavTab, onTabSelect: (NavTab) -> Unit) {
    val vm: ToolsViewModel = viewModel(factory = ToolsViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    // Wipe plaintext on every pause — sensitive data should not linger in memory
    androidx.lifecycle.compose.LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        vm.wipe()
    }

    AstraAppShell(
        selectedTab = selectedTab, onTabSelect = onTabSelect,
        topBarContent = { AstraTopBar(callsign = "SECURE_TOOLS_V4.0", clearanceLabel = "LOCAL_MODE") },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            ToolsContent(
                state = state,
                onStateChange = { vm.updateState(it) },
                onExecute = {
                    // AES is handled in the VM (needs CryptoEngine); all others execute inline
                    if (state.algo == Algorithm.AES_256_GCM) {
                        vm.execute()
                    } else {
                        vm.setOutput(executeToolsOp(state))
                    }
                },
            )
        }
    }
}
