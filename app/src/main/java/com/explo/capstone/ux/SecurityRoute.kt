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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.Severity
import com.explo.capstone.ui.*
import kotlinx.coroutines.flow.*

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

        val missions = container.store.missions.value
        val keys = missions.map { m ->
            val age = System.currentTimeMillis() - m.createdAtMs
            val ageF = when { age < 3_600_000L -> "${age/60_000}m"; age < 86_400_000L -> "${age/3_600_000}h"; else -> "${age/86_400_000}d ${(age%86_400_000)/3_600_000}h" }
            MissionKeyEntry(m.id, m.name, m.missionKeyAlias, ageF)
        }

        val events = container.securityEventLog.events.value

        _state.value = SecurityUiState.Content(callsign, hwKey, provisioned, deviceSecure, strongBox, "Signal v3.4", keys, events, degraded)
        container.securityEventLog.emit(Severity.INFO, "Security", "POSTURE_CHECK_COMPLETE")
    }

    fun rotateKey(missionId: String) {
        try {
            val mission = container.store.missions.value.find { it.id == missionId } ?: return
            val newAlias = container.cryptoEngine.rotateMissionKey(mission.missionKeyAlias)
            container.store.updateMissions { list ->
                list.map { if (it.id == missionId) it.copy(missionKeyAlias = newAlias) else it }
            }
            container.securityEventLog.emit(Severity.INFO, "CryptoEngine", "KEY_ROTATED // $newAlias")
        } catch (_: NotImplementedError) {
            container.securityEventLog.emit(Severity.WARN, "CryptoEngine", "KEY_ROTATE_FAILED // CRYPTO_OFFLINE")
        }
        refresh()
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
                    is SecurityIntent.RotateKey -> vm.rotateKey(intent.missionId)
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
