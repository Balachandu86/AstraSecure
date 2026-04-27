package com.explo.capstone.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── UI State ────────────────────────────────────────────────────────────────

sealed interface ProvisioningUiState {
    data object Probing : ProvisioningUiState
    data class CallsignEntry(val callsign: String, val error: String?) : ProvisioningUiState
    data class Review(val callsign: String, val deviceSecure: Boolean, val strongBoxBacked: Boolean) : ProvisioningUiState
    data class Provisioning(val progressLabel: String) : ProvisioningUiState
    data class Failed(val reason: String) : ProvisioningUiState
}

// ─── Intents ─────────────────────────────────────────────────────────────────

sealed interface ProvisioningIntent {
    data class UpdateCallsign(val callsign: String) : ProvisioningIntent
    data object Advance : ProvisioningIntent
    data object Retry : ProvisioningIntent
}

// ─── Root composable ─────────────────────────────────────────────────────────

/**
 * Owner: Yashwanth
 * Stateless — driven entirely by [ProvisioningUiState]. No top bar, no bottom bar.
 */
@Composable
fun ProvisioningContent(
    state: ProvisioningUiState,
    onIntent: (ProvisioningIntent) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstraTheme.SurfaceDim)
            .systemBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            Text(
                "// ASTRA // SECURE",
                style = AstraTheme.Typography.headlineSmall.copy(
                    color = AstraTheme.Primary,
                    letterSpacing = 4.sp,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "IDENTITY PROVISIONING",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.OnSurface.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                ),
            )

            Spacer(Modifier.height(40.dp))

            when (state) {
                is ProvisioningUiState.Probing -> ProbingView()
                is ProvisioningUiState.CallsignEntry -> {
                    StepIndicator(current = 1, total = 3)
                    Spacer(Modifier.height(32.dp))
                    CallsignEntryView(state, onIntent)
                }
                is ProvisioningUiState.Review -> {
                    StepIndicator(current = 2, total = 3)
                    Spacer(Modifier.height(32.dp))
                    ReviewView(state, onIntent)
                }
                is ProvisioningUiState.Provisioning -> {
                    StepIndicator(current = 3, total = 3)
                    Spacer(Modifier.height(32.dp))
                    ProvisioningProgressView(state)
                }
                is ProvisioningUiState.Failed -> FailedView(state, onIntent)
            }
        }
    }
}

// ─── Step indicator ──────────────────────────────────────────────────────────

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (1..total).forEach { step ->
            val isActive = step == current
            val color = if (isActive) AstraTheme.Primary else AstraTheme.OutlineVariant.copy(alpha = 0.4f)
            Text(
                text = step.toString().padStart(2, '0'),
                style = AstraTheme.Typography.labelMedium.copy(
                    color = color,
                    fontWeight = if (isActive) FontWeight.Black else FontWeight.Normal,
                    fontSize = 12.sp,
                ),
            )
            if (step < total) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(1.dp)
                        .background(AstraTheme.OutlineVariant.copy(alpha = 0.3f))
                )
            }
        }
    }
}

// ─── State views ─────────────────────────────────────────────────────────────

@Composable
private fun ProbingView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            color = AstraTheme.Primary,
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "INITIALIZING SECURE BOOT...",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.OnSurface.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
private fun CallsignEntryView(
    state: ProvisioningUiState.CallsignEntry,
    onIntent: (ProvisioningIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "ASSIGN CALLSIGN",
            style = AstraTheme.Typography.headlineSmall.copy(
                color = AstraTheme.OnSurface,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Your callsign is your permanent operator identifier.\nMust begin with a letter — letters, digits, and _ only.",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.OnSurface.copy(alpha = 0.45f),
                fontSize = 10.sp,
                lineHeight = 15.sp,
            ),
        )
        Spacer(Modifier.height(24.dp))

        AstraInputField(
            value = state.callsign,
            onValueChange = { onIntent(ProvisioningIntent.UpdateCallsign(it)) },
            placeholder = "CALLSIGN (e.g. GHOST_09)",
        )

        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.error,
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Error,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        Spacer(Modifier.height(24.dp))

        val enabled = state.callsign.isNotEmpty()
        Button(
            onClick = { onIntent(ProvisioningIntent.Advance) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RectangleShape,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = AstraTheme.Primary,
                contentColor = AstraTheme.OnPrimary,
                disabledContainerColor = AstraTheme.SurfaceContainerHigh,
                disabledContentColor = AstraTheme.OutlineVariant,
            ),
        ) {
            Text(
                "> CONFIRM_CALLSIGN",
                style = AstraTheme.Typography.labelMedium.copy(
                    color = if (enabled) AstraTheme.OnPrimary else AstraTheme.OutlineVariant,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

@Composable
private fun ReviewView(
    state: ProvisioningUiState.Review,
    onIntent: (ProvisioningIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "DEVICE ATTESTATION",
            style = AstraTheme.Typography.headlineSmall.copy(
                color = AstraTheme.OnSurface,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Review your device security posture before committing identity.",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.OnSurface.copy(alpha = 0.45f),
                fontSize = 10.sp,
                lineHeight = 15.sp,
            ),
        )
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AstraTheme.SurfaceContainerLow)
                .border(1.dp, AstraTheme.OutlineVariant.copy(alpha = 0.2f))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AttestationRow("OPERATOR", state.callsign, ok = true)
                AttestationRow(
                    "SCREEN_LOCK",
                    if (state.deviceSecure) "ACTIVE" else "INACTIVE",
                    ok = state.deviceSecure,
                )
                AttestationRow(
                    "HARDWARE_SEC",
                    if (state.strongBoxBacked) "STRONGBOX" else "TEE",
                    ok = true,
                )
            }
        }

        if (!state.deviceSecure) {
            Spacer(Modifier.height(10.dp))
            Text(
                "> WARN: NO SCREEN LOCK — RECOMMEND ENABLING PIN/BIOMETRIC",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Secondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        Spacer(Modifier.height(24.dp))

        AstraButtonPrimary(
            text = "> PROVISION_IDENTITY",
            onClick = { onIntent(ProvisioningIntent.Advance) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AttestationRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.OnSurface.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val statusColor = if (ok) AstraTheme.Tertiary else AstraTheme.Error
            Icon(
                imageVector = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(12.dp),
            )
            Text(
                value,
                style = AstraTheme.Typography.labelSmall.copy(
                    color = statusColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

@Composable
private fun ProvisioningProgressView(state: ProvisioningUiState.Provisioning) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "PROVISIONING IN PROGRESS",
            style = AstraTheme.Typography.headlineSmall.copy(
                color = AstraTheme.OnSurface,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(24.dp))

        val infiniteTransition = rememberInfiniteTransition(label = "progress")
        val progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "progress_sweep",
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(AstraTheme.SurfaceContainerLowest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(AstraTheme.Primary)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            state.progressLabel,
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.OnSurface.copy(alpha = 0.65f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
private fun FailedView(
    state: ProvisioningUiState.Failed,
    onIntent: (ProvisioningIntent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = AstraTheme.Error,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "PROVISIONING FAILED",
            style = AstraTheme.Typography.headlineSmall.copy(
                color = AstraTheme.Error,
                fontSize = 16.sp,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "> ${state.reason}",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.Error.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        // KEYSTORE_OFFLINE is a device-level problem — no retry possible
        if (state.reason != "KEYSTORE_OFFLINE") {
            Spacer(Modifier.height(24.dp))
            AstraButtonTertiary(
                text = "RETRY",
                onClick = { onIntent(ProvisioningIntent.Retry) },
            )
        }
    }
}
