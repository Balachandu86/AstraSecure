package com.explo.capstone.ux

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

class SecurityViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<SecurityUiState>(SecurityUiState.Loading)
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val degraded = mutableSetOf<DegradedSubsystem>()
        var callsign = "OPERATOR"; var hwKey = "UNKNOWN"; var provisioned = "NOT_PROVISIONED"

        try { container.identityManager.getUserIdentity()?.let { u ->
            callsign = u.displayName.uppercase(); hwKey = u.hardwareKeyId; provisioned = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(u.provisionedAtMs))
        } } catch (_: NotImplementedError) { degraded.add(DegradedSubsystem.IDENTITY) }

        var deviceSecure = false; var strongBox = false
        try { container.identityManager.deviceAttestation().let { a -> deviceSecure = a.isDeviceSecure; strongBox = a.hasStrongBox } } catch (_: NotImplementedError) { /* already degraded */ }

        val events = container.securityEventLog.events.value
        val integrity = if (container.signalStore.loadJwt().isNotEmpty()) "AUTHENTICATED" else "LOCAL_ONLY"

        val signalKeyStatus = buildSignalKeyStatus()

        _state.value = SecurityUiState.Content(callsign, hwKey, provisioned, deviceSecure, strongBox, integrity, "Signal Protocol", signalKeyStatus, events, degraded)
        container.securityEventLog.emit(Severity.INFO, "Security", "POSTURE_CHECK_COMPLETE")
    }

    private fun buildSignalKeyStatus(): SignalKeyStatus {
        val fingerprint = runCatching {
            val ikp = container.signalStore.getIdentityKeyPair()
            ikp.publicKey.serialize().take(8).joinToString("") { "%02X".format(it) }
        }.getOrElse { "NOT_PROVISIONED" }

        val spkRotatedAtMs = container.signalStore.getLastSpkRotationMs()
        val spkLabel = if (spkRotatedAtMs == 0L) "NEVER" else {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(spkRotatedAtMs))
        }

        val opkCount = container.signalStore.preKeyCount()

        return SignalKeyStatus(fingerprint, spkLabel, opkCount)
    }

    fun rotateSPK() {
        viewModelScope.launch {
            runCatching { container.signalKeyManager.rotateSignedPreKey() }
                .onSuccess { container.securityEventLog.emit(Severity.INFO, "CryptoEngine", "SPK_ROTATED") }
                .onFailure { container.securityEventLog.emit(Severity.WARN, "CryptoEngine", "SPK_ROTATE_FAILED // ${it.message}") }
            refresh()
        }
    }

    fun replenishOPKs() {
        viewModelScope.launch {
            runCatching { container.signalKeyManager.replenishPreKeys() }
                .onSuccess { container.securityEventLog.emit(Severity.INFO, "CryptoEngine", "OPK_REPLENISHED") }
                .onFailure { container.securityEventLog.emit(Severity.WARN, "CryptoEngine", "OPK_REPLENISH_FAILED // ${it.message}") }
            refresh()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SecurityViewModel(container) as T
    }
}

@Composable
fun SecurityRoute(
    container: AppContainer,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    onNavigateToPanic: () -> Unit,
    onNavigateToAdmin: () -> Unit = {},
    onProfileClick: (() -> Unit)? = null,
) {
    val vm: SecurityViewModel = viewModel(factory = SecurityViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val json = container.securityEventLog.exportJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.encodeToByteArray()) }
            container.securityEventLog.emit(Severity.INFO, "Security", "AUDIT_LOG_EXPORTED")
        }
    }

    AstraAppShell(
        selectedTab = selectedTab,
        onTabSelect = onTabSelect,
        topBarContent = {
            AstraTopBar(
                titleOverride = "SECURITY",
                callsign = "POSTURE",
                clearanceLabel = "DASHBOARD",
                onProfileClick = onProfileClick,
                actions = {
                    IconButton(onClick = onNavigateToAdmin) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Admin Console", tint = AstraTheme.Primary)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            SecurityContent(state) { intent ->
                when (intent) {
                    is SecurityIntent.Refresh -> vm.refresh()
                    is SecurityIntent.RotateSPK -> vm.rotateSPK()
                    is SecurityIntent.ReplenishOPKs -> vm.replenishOPKs()
                    is SecurityIntent.NavigateToPanic -> onNavigateToPanic()
                    is SecurityIntent.NavigateToAdmin -> onNavigateToAdmin()
                    is SecurityIntent.ExportLog -> {
                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
                        exportLauncher.launch("astra_audit_$ts.json")
                    }
                }
            }
        }
    }
}
