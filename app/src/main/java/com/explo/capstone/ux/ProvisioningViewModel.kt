package com.explo.capstone.ux

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.explo.capstone.crypto.signal.SignalKeyManager
import com.explo.capstone.identity.IdentityManager
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.transport.AstraApiClient
import com.explo.capstone.ui.ProvisioningIntent
import com.explo.capstone.ui.ProvisioningUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Owner: Ismail Alam
 * Drives the 5-state provisioning flow. All Keystore / IO work dispatched to [Dispatchers.IO].
 *
 * [onProvisioned] is invoked with the new userId once both local keys AND server registration
 * succeed. AppContainer uses this to update SignalCryptoEngine (GAP-03 fix).
 */
class ProvisioningViewModel(
    private val identityManager: IdentityManager,
    private val signalKeyManager: SignalKeyManager,
    private val apiClient: AstraApiClient,
    private val onProvisioned: (userId: String) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<ProvisioningUiState>(ProvisioningUiState.Probing)
    val state: StateFlow<ProvisioningUiState> = _state.asStateFlow()

    // Emits Unit once when provisioning succeeds — route layer handles nav
    private val _provisioningSuccess = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val provisioningSuccess: SharedFlow<Unit> = _provisioningSuccess.asSharedFlow()

    companion object {
        private const val TAG = "ProvisioningVM"
    }

    // Saved after local identity is created so RetryServer can re-use them
    private var pendingUserId: String = ""
    private var pendingCallsign: String = ""

    init {
        probe()
    }

    private fun probe() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { identityManager.deviceAttestation() }
                .onSuccess { _state.value = ProvisioningUiState.CallsignEntry("", null) }
                .onFailure { _state.value = ProvisioningUiState.Failed("KEYSTORE_OFFLINE") }
        }
    }

    fun handle(intent: ProvisioningIntent) {
        when (intent) {
            is ProvisioningIntent.UpdateCallsign -> {
                val current = _state.value as? ProvisioningUiState.CallsignEntry ?: return
                _state.value = current.copy(callsign = intent.callsign.uppercase(), error = null)
            }
            is ProvisioningIntent.Advance -> advance()
            is ProvisioningIntent.Retry -> {
                pendingUserId = ""
                pendingCallsign = ""
                _state.value = ProvisioningUiState.CallsignEntry("", null)
            }
            is ProvisioningIntent.RetryServer -> retryServer()
        }
    }

    private fun advance() {
        when (val s = _state.value) {
            is ProvisioningUiState.CallsignEntry -> {
                val callsign = s.callsign.trim()
                val error = validateCallsign(callsign)
                if (error != null) { _state.value = s.copy(error = error); return }
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { identityManager.deviceAttestation() }
                        .onSuccess { att ->
                            _state.value = ProvisioningUiState.Review(
                                callsign = callsign,
                                deviceSecure = att.isDeviceSecure,
                                strongBoxBacked = att.hasStrongBox,
                            )
                        }
                        .onFailure { _state.value = ProvisioningUiState.Failed("KEYSTORE_OFFLINE") }
                }
            }
            is ProvisioningUiState.Review -> provision(s.callsign)
            else -> {}
        }
    }

    private fun provision(callsign: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = ProvisioningUiState.Provisioning("GENERATING KEY MATERIAL...")
            runCatching { identityManager.provisionIdentity(callsign) }
                .onSuccess { user ->
                    pendingUserId = user.id
                    pendingCallsign = callsign
                    _state.value = ProvisioningUiState.Provisioning("REGISTERING WITH SERVER...")
                    runCatching { signalKeyManager.provision(user.id, callsign) }
                        .onSuccess {
                            // Mirror the user in the REST DB (callsign + admin flag)
                            apiClient.registerUser(callsign)
                                .onFailure { Log.w(TAG, "REST registerUser failed: ${it.message}") }
                            onProvisioned(user.id)
                            _state.value = ProvisioningUiState.Provisioning("IDENTITY COMMITTED")
                            _provisioningSuccess.emit(Unit)
                        }
                        .onFailure { e ->
                            val isServerError = e.message?.startsWith("SERVER_REGISTRATION_FAILED") == true
                            _state.value = ProvisioningUiState.Failed(
                                reason = if (isServerError) "SERVER_UNREACHABLE // CHECK CONNECTION"
                                         else "SIGNAL_PROVISION_FAILED: ${e.message?.take(40) ?: "UNKNOWN"}",
                                canRetryServer = isServerError,
                            )
                        }
                }
                .onFailure { e ->
                    _state.value = ProvisioningUiState.Failed(
                        if (e is NotImplementedError) "PROVISIONING_PENDING_SANDRANI"
                        else "PROVISIONING_FAILED: ${e.message?.take(40) ?: "UNKNOWN"}"
                    )
                }
        }
    }

    private fun retryServer() {
        if (pendingUserId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = ProvisioningUiState.Provisioning("CONNECTING TO SERVER...")
            runCatching { signalKeyManager.retryServerRegistration(pendingUserId, pendingCallsign) }
                .onSuccess {
                    apiClient.registerUser(pendingCallsign)
                        .onFailure { Log.w(TAG, "REST registerUser failed (retry): ${it.message}") }
                    onProvisioned(pendingUserId)
                    _state.value = ProvisioningUiState.Provisioning("IDENTITY COMMITTED")
                    _provisioningSuccess.emit(Unit)
                }
                .onFailure {
                    _state.value = ProvisioningUiState.Failed(
                        reason = "SERVER_UNREACHABLE // CHECK CONNECTION",
                        canRetryServer = true,
                    )
                }
        }
    }

    private fun validateCallsign(callsign: String): String? = when {
        callsign.isEmpty() -> "> CALLSIGN_REQUIRED"
        !callsign.matches(Regex("^[A-Z][A-Z0-9_]{2,15}$")) -> "> ALPHA + DIGITS + UNDERSCORE ONLY"
        else -> null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProvisioningViewModel(
                identityManager = container.identityManager,
                signalKeyManager = container.signalKeyManager,
                apiClient = container.apiClient,
                onProvisioned = container::onIdentityProvisioned,
            ) as T
    }
}
