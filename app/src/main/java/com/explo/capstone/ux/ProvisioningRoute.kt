package com.explo.capstone.ux

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.ui.ProvisioningContent
import com.explo.capstone.ui.ProvisioningUiState

/**
 * Owner: Ismail Alam
 * Wires [ProvisioningViewModel] → [ProvisioningContent].
 * Calls [onProvisioned] on success so the nav layer can pop provisioning and start the app.
 */
@Composable
fun ProvisioningRoute(
    container: AppContainer,
    onProvisioned: () -> Unit,
) {
    val vm: ProvisioningViewModel = viewModel(factory = ProvisioningViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    // Emit once when the Keystore keypair is committed
    LaunchedEffect(Unit) {
        vm.provisioningSuccess.collect { onProvisioned() }
    }

    // Only step 1 (CallsignEntry) allows back — everything else consumes it
    BackHandler(enabled = state !is ProvisioningUiState.CallsignEntry) { }

    ProvisioningContent(state = state, onIntent = vm::handle)
}
