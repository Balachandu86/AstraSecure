package com.explo.capstone.ux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.Severity
import com.explo.capstone.shared.data.entity.InviteUri
import com.explo.capstone.transport.RedeemInviteResponse
import com.explo.capstone.ui.AstraTheme
import com.explo.capstone.ui.InviteQrScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── ViewModel ───────────────────────────────────────────────────────────────

sealed interface RedeemUiState {
    data object Idle : RedeemUiState
    data object Scanning : RedeemUiState
    data object Submitting : RedeemUiState
    data class Success(val response: RedeemInviteResponse) : RedeemUiState
    data class Failure(val message: String) : RedeemUiState
}

class RedeemViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<RedeemUiState>(RedeemUiState.Idle)
    val state: StateFlow<RedeemUiState> = _state.asStateFlow()

    fun openScanner() { _state.value = RedeemUiState.Scanning }
    fun closeScanner() { _state.value = RedeemUiState.Idle }
    fun resetToIdle() { _state.value = RedeemUiState.Idle }

    /** Accepts either a bare token or a full astrasecure://invite/<token> URI. */
    fun redeem(input: String) {
        val token = InviteUri.decode(input) ?: run {
            _state.value = RedeemUiState.Failure("EMPTY TOKEN")
            return
        }
        _state.value = RedeemUiState.Submitting
        viewModelScope.launch {
            container.inviteRepository.redeem(token)
                .onSuccess { resp ->
                    container.securityEventLog.emit(
                        Severity.INFO, "Invites",
                        "INVITE_REDEEMED // ${resp.missionId}",
                    )
                    container.syncFromServer()
                    _state.value = RedeemUiState.Success(resp)
                }
                .onFailure { e ->
                    _state.value = RedeemUiState.Failure(e.message ?: "REDEMPTION FAILED")
                }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RedeemViewModel(container) as T
    }
}

// ─── Route composable ────────────────────────────────────────────────────────

@Composable
fun RedeemRoute(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val vm: RedeemViewModel = viewModel(factory = RedeemViewModel.Factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is RedeemUiState.Scanning -> InviteQrScanner(
            onScanned = { scanned -> vm.redeem(scanned) },
            onCancel = { vm.closeScanner() },
        )
        else -> RedeemContent(
            state = s,
            onScan = vm::openScanner,
            onSubmit = vm::redeem,
            onClearError = vm::resetToIdle,
            onBack = onBack,
            onDoneAfterSuccess = onBack,
        )
    }
}

// ─── Idle / submitting / success / failure UI ────────────────────────────────

@Composable
private fun RedeemContent(
    state: RedeemUiState,
    onScan: () -> Unit,
    onSubmit: (String) -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    onDoneAfterSuccess: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxSize()
            .background(AstraTheme.Surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = AstraTheme.OnSurface, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "// REDEEM MISSION INVITE",
                style = AstraTheme.Typography.headlineSmall.copy(
                    color = AstraTheme.OnSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        Text(
            "AN INVITE TOKEN GRANTS PROVISIONAL ACCESS TO A MISSION. " +
                "AFTER REDEMPTION YOU REMAIN PENDING UNTIL A CHIEF VERIFIES " +
                "YOUR IDENTITY-KEY FINGERPRINT OUT-OF-BAND.",
            style = AstraTheme.Typography.labelSmall.copy(
                color = Color(0xFFACABAA).copy(0.7f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )

        when (state) {
            is RedeemUiState.Success -> SuccessPanel(state.response, onDone = onDoneAfterSuccess)
            is RedeemUiState.Failure -> FailureBanner(state.message, onClearError)
            else -> {} // Idle / Submitting fall through to the form
        }

        if (state !is RedeemUiState.Success) {
            // Scan-QR primary action
            OutlinedButton(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.Tertiary.copy(0.5f)),
                contentPadding = PaddingValues(vertical = 14.dp),
                enabled = state !is RedeemUiState.Submitting,
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null, tint = AstraTheme.Tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "> SCAN INVITE QR",
                    style = AstraTheme.Typography.labelMedium.copy(
                        color = AstraTheme.Tertiary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }

            // OR — paste path
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(AstraTheme.OutlineVariant.copy(0.3f)))
                Text(
                    "  OR  ",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = Color(0xFFACABAA).copy(0.5f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Box(Modifier.weight(1f).height(1.dp).background(AstraTheme.OutlineVariant.copy(0.3f)))
            }

            Text(
                "PASTE TOKEN",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Primary.copy(0.7f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(AstraTheme.SurfaceContainerLow)
                    .border(1.dp, AstraTheme.OutlineVariant.copy(0.4f))
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    cursorBrush = SolidColor(AstraTheme.Primary),
                    textStyle = AstraTheme.Typography.labelMedium.copy(
                        color = AstraTheme.OnSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        letterSpacing = 1.5.sp,
                    ),
                    decorationBox = { inner ->
                        if (token.isEmpty()) {
                            Text(
                                "XK4F-9N2P-…",
                                style = AstraTheme.Typography.labelMedium.copy(
                                    color = Color(0xFFACABAA).copy(0.4f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                ),
                            )
                        }
                        inner()
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.getText()?.text?.let { token = it.trim() }
                    },
                    shape = RectangleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.OutlineVariant),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    enabled = state !is RedeemUiState.Submitting,
                ) {
                    Text("> PASTE", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onSubmit(token) },
                    enabled = token.isNotBlank() && state !is RedeemUiState.Submitting,
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.Primary, contentColor = AstraTheme.OnPrimary),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (state is RedeemUiState.Submitting) "> REDEEMING…" else "> REDEEM",
                        style = AstraTheme.Typography.labelMedium.copy(
                            color = AstraTheme.OnPrimary,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SuccessPanel(resp: RedeemInviteResponse, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AstraTheme.Tertiary.copy(0.05f))
            .border(1.dp, AstraTheme.Tertiary.copy(0.5f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "// REDEEMED — AWAITING CHIEF CONFIRMATION",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.Tertiary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Text(
            "MISSION  ${resp.missionName.uppercase()}",
            style = AstraTheme.Typography.labelMedium.copy(
                color = AstraTheme.OnSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
        )
        if (!resp.issuerFingerprint.isNullOrBlank()) {
            Text(
                "INVITER FINGERPRINT  ${resp.issuerFingerprint}",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.OnSurface,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                "CONFIRM THIS FINGERPRINT MATCHES THE INVITER OUT-OF-BAND " +
                    "BEFORE TRUSTING THE LINK.",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA).copy(0.7f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        Spacer(Modifier.height(2.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.Tertiary, contentColor = Color(0xFF06281A)),
        ) {
            Text(
                "> RETURN TO MISSIONS",
                style = AstraTheme.Typography.labelMedium.copy(
                    color = Color(0xFF06281A),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

@Composable
private fun FailureBanner(message: String, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AstraTheme.Error.copy(0.08f))
            .border(1.dp, AstraTheme.Error.copy(0.4f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "> $message",
            modifier = Modifier.weight(1f),
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.Error,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        TextButton(onClick = onClear, shape = RectangleShape) {
            Text(
                "DISMISS",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Error.copy(0.8f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}
