package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.explo.capstone.shared.Mission
import com.explo.capstone.shared.SecurityEvent
import com.explo.capstone.shared.Severity

// ─── State ───────────────────────────────────────────────────────────────────

data class SignalKeyStatus(
    val identityFingerprint: String,
    val spkRotatedAtLabel: String,
    val opkCount: Int,
)

sealed interface SecurityUiState {
    data object Loading : SecurityUiState
    data class Content(
        val callsign: String, val hardwareKeyId: String, val provisionedLabel: String,
        val deviceSecure: Boolean, val strongBox: Boolean,
        val integrity: String,
        val protocol: String,
        val signalKeyStatus: SignalKeyStatus,
        val events: List<SecurityEvent>,
        val degraded: Set<DegradedSubsystem>,
    ) : SecurityUiState
}

sealed interface SecurityIntent {
    data object Refresh : SecurityIntent
    data object RotateSPK : SecurityIntent
    data object ReplenishOPKs : SecurityIntent
    data object NavigateToPanic : SecurityIntent
    data object NavigateToAdmin : SecurityIntent
    data object ExportLog : SecurityIntent
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun SecurityContent(state: SecurityUiState, onIntent: (SecurityIntent) -> Unit) {
    when (state) {
        is SecurityUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AstraTheme.Primary, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
        is SecurityUiState.Content -> SecurityBody(state, onIntent)
    }
}

@Composable
private fun SecurityBody(state: SecurityUiState.Content, onIntent: (SecurityIntent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (state.degraded.isNotEmpty()) DegradedBanner(state.degraded)
        Spacer(Modifier.height(16.dp))

        // Identity card
        SectionHeader("IDENTITY STATUS")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(AstraTheme.Primary))
            Column(Modifier.weight(1f).background(AstraTheme.SurfaceContainerLow).padding(16.dp)) {
                InfoRow("CALLSIGN", state.callsign, AstraTheme.Primary)
                InfoRow("HARDWARE KEY", state.hardwareKeyId, AstraTheme.Tertiary)
                InfoRow("PROVISIONED", state.provisionedLabel, Color(0xFFACABAA))
            }
        }
        Spacer(Modifier.height(20.dp))

        // Device posture
        SectionHeader("DEVICE POSTURE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PostureCell("DEVICE SECURE", state.deviceSecure, Modifier.weight(1f))
            PostureCell("STRONGBOX", state.strongBox, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PostureInfoCell("PROTOCOL", state.protocol, AstraTheme.Primary, Modifier.weight(1f))
            PostureInfoCell(
                "INTEGRITY",
                state.integrity,
                if (state.integrity == "AUTHENTICATED") AstraTheme.Tertiary else AstraTheme.Secondary,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))

        // Signal key status
        SectionHeader("SIGNAL KEY STATUS")
        Column(Modifier.fillMaxWidth().background(AstraTheme.SurfaceContainerLow).border(1.dp, AstraTheme.OutlineVariant.copy(0.1f))) {
            // Identity key row
            Row(
                Modifier.fillMaxWidth().border(width = 0.dp, color = Color.Transparent).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Key, null, tint = AstraTheme.Tertiary.copy(0.6f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("IDENTITY KEY", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    Text(state.signalKeyStatus.identityFingerprint, style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Tertiary.copy(0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                }
                Text("HARDWARE BOUND", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Tertiary.copy(0.5f), fontSize = 8.sp, fontFamily = FontFamily.Monospace))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.08f)))
            // Signed pre-key row
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Key, null, tint = AstraTheme.Primary.copy(0.6f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("SIGNED PRE-KEY", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    Text("ROTATED: ${state.signalKeyStatus.spkRotatedAtLabel}", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                }
                Text("> ROTATE SPK", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Secondary, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable { onIntent(SecurityIntent.RotateSPK) })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.08f)))
            // One-time pre-keys row
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Key, null, tint = AstraTheme.Primary.copy(0.4f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("ONE-TIME PRE-KEYS", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    Text(
                        "${state.signalKeyStatus.opkCount} REMAINING",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = if (state.signalKeyStatus.opkCount < 10) AstraTheme.Error else Color(0xFFACABAA).copy(0.5f),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        )
                    )
                }
                Text("> REPLENISH", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable { onIntent(SecurityIntent.ReplenishOPKs) })
            }
        }
        Spacer(Modifier.height(20.dp))

        // Event log
        SectionHeader("SECURITY EVENT LOG")
        Column(
            Modifier.fillMaxWidth().background(AstraTheme.SurfaceContainerLowest).border(1.dp, AstraTheme.OutlineVariant.copy(0.1f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (state.events.isEmpty()) {
                Text("> [SYSTEM]: NO EVENTS RECORDED", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.3f), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
            } else {
                state.events.takeLast(20).forEach { evt ->
                    val sevColor = when (evt.severity) { Severity.INFO -> AstraTheme.Primary.copy(0.6f); Severity.WARN -> AstraTheme.Secondary; Severity.ALERT -> AstraTheme.Error }
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(evt.tsMs))
                    Text("[$ts] [${evt.severity}] ${evt.text}", style = AstraTheme.Typography.labelSmall.copy(color = sevColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // Footer actions
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onIntent(SecurityIntent.Refresh) }, modifier = Modifier.weight(1f).height(44.dp), shape = androidx.compose.ui.graphics.RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.OutlineVariant.copy(0.3f))) {
                Text("> VERIFY ALL", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Primary, fontWeight = FontWeight.Bold, fontSize = 10.sp))
            }
            OutlinedButton(onClick = { onIntent(SecurityIntent.ExportLog) }, modifier = Modifier.weight(1f).height(44.dp), shape = androidx.compose.ui.graphics.RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.OutlineVariant.copy(0.3f))) {
                Text("> EXPORT LOG", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Tertiary, fontWeight = FontWeight.Bold, fontSize = 10.sp))
            }
            Button(onClick = { onIntent(SecurityIntent.NavigateToPanic) }, modifier = Modifier.weight(1f).height(44.dp), shape = androidx.compose.ui.graphics.RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.Error.copy(0.15f), contentColor = AstraTheme.Error)) {
                Text("> PANIC", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error, fontWeight = FontWeight.Bold, fontSize = 10.sp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Box(Modifier.width(28.dp).height(1.dp).background(AstraTheme.OutlineVariant))
        Spacer(Modifier.width(8.dp))
        Text(text, style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), letterSpacing = 3.sp, fontWeight = FontWeight.Bold, fontSize = 10.sp))
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.6f), fontSize = 9.sp, letterSpacing = 1.sp))
        Text(value, style = AstraTheme.Typography.labelSmall.copy(color = valueColor, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun PostureCell(label: String, pass: Boolean, modifier: Modifier) {
    Box(modifier.background(AstraTheme.SurfaceContainerHighest).border(1.dp, AstraTheme.OutlineVariant.copy(0.15f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (pass) "✓" else "✗", style = AstraTheme.Typography.headlineSmall.copy(color = if (pass) AstraTheme.Tertiary else AstraTheme.Error, fontSize = 16.sp))
            Column {
                Text(label, style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
                Text(if (pass) "VERIFIED" else "UNAVAILABLE", style = AstraTheme.Typography.labelSmall.copy(color = if (pass) AstraTheme.Tertiary else AstraTheme.Error, fontSize = 9.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun PostureInfoCell(label: String, value: String, color: Color, modifier: Modifier) {
    Box(modifier.background(AstraTheme.SurfaceContainerHighest).border(1.dp, AstraTheme.OutlineVariant.copy(0.15f)).padding(12.dp)) {
        Column {
            Text(label, style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Text(value, style = AstraTheme.Typography.labelSmall.copy(color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
        }
    }
}
