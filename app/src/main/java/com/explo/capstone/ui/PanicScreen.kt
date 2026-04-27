package com.explo.capstone.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ─── State ───────────────────────────────────────────────────────────────────

sealed interface PanicUiState {
    data object Standby : PanicUiState
    data class Wiping(val phase: String, val percentage: Int) : PanicUiState
    data object Tombstoned : PanicUiState
}

sealed interface PanicIntent {
    data class SlideProgress(val progress: Float) : PanicIntent
    data object SlideCommit : PanicIntent
    data object Abort : PanicIntent
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun PanicContent(state: PanicUiState, onIntent: (PanicIntent) -> Unit) {
    when (state) {
        is PanicUiState.Standby -> PanicStandby(onIntent)
        is PanicUiState.Wiping -> PanicWiping(state)
        is PanicUiState.Tombstoned -> PanicTombstoned()
    }
}

@Composable
private fun PanicStandby(onIntent: (PanicIntent) -> Unit) {
    val flicker = rememberInfiniteTransition("warn_flicker")
    val flickerAlpha by flicker.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), "flickA")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .background(Brush.verticalGradient(listOf(AstraTheme.Error.copy(0.04f), Color.Transparent, AstraTheme.Error.copy(0.04f))))
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        // Warning icon
        Box(Modifier.size(64.dp).background(AstraTheme.Error.copy(0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Warning, null, tint = AstraTheme.Error.copy(alpha = flickerAlpha), modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("TERMINAL PURGE", style = AstraTheme.Typography.headlineSmall.copy(color = AstraTheme.Error, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp))
        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(AstraTheme.Error.copy(flickerAlpha), CircleShape))
            Text("STATUS: STANDBY  •  ENCRYPTION: AES-XTS-512", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error.copy(0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
        }
        Spacer(Modifier.height(24.dp))

        // Danger description
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(AstraTheme.Error))
            Column(Modifier.weight(1f).background(AstraTheme.SurfaceContainerLow).padding(16.dp)) {
                val lines = listOf(
                    "IRREVERSIBLE ACTION. ALL APP DATA WILL BE DESTROYED.",
                    "THE DEVICE'S HARDWARE IDENTITY WILL BE INVALIDATED.",
                    "AFTER PURGE, THIS APP MUST BE REINSTALLED OR CLEARED VIA SYSTEM SETTINGS BEFORE A NEW IDENTITY CAN BE PROVISIONED.",
                    "THE WIPED IDENTITY CANNOT BE RECOVERED."
                )
                lines.forEachIndexed { i, line ->
                    Text("> $line", style = AstraTheme.Typography.labelSmall.copy(color = if (i == 0) AstraTheme.Error else AstraTheme.OnSurface.copy(0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                    if (i < lines.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // Wipe targets
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WipeTargetCell("MISSIONS", "ALL DATA", Modifier.weight(1f))
            WipeTargetCell("KEYS", "INVALIDATED", Modifier.weight(1f))
            WipeTargetCell("IDENTITY", "DESTROYED", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))

        // Slide to confirm
        Text("SLIDE TO CONFIRM PURGE", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error.copy(0.6f), fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        SlideToConfirm(onCommit = { onIntent(PanicIntent.SlideCommit) })
        Spacer(Modifier.height(16.dp))

        // Abort
        OutlinedButton(onClick = { onIntent(PanicIntent.Abort) }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RectangleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.OutlineVariant.copy(0.3f))) {
            Text("> ABORT_PROTOCOL", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontWeight = FontWeight.Bold, fontSize = 10.sp))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun WipeTargetCell(label: String, status: String, modifier: Modifier) {
    Box(modifier.background(AstraTheme.SurfaceContainerHighest).border(1.dp, AstraTheme.Error.copy(0.15f)).padding(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Text(status, style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error, fontSize = 9.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun SlideToConfirm(onCommit: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(64.dp).background(AstraTheme.SurfaceContainerLowest).border(1.dp, AstraTheme.OutlineVariant.copy(0.2f))
    ) {
        val trackWidth = constraints.maxWidth.toFloat()
        val knobSize = with(density) { 56.dp.toPx() }
        val maxDrag = trackWidth - knobSize - with(density) { 8.dp.toPx() }

        // Fill
        Box(Modifier.fillMaxHeight().width(with(density) { (trackWidth * progress).toDp() }).background(AstraTheme.Error.copy(progress * 0.15f)))

        // Track label
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(">>  DRAG TO PURGE  >>", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error.copy((1f - progress) * 0.4f), fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace))
        }

        // Knob
        Box(
            Modifier.offset { IntOffset((progress * maxDrag).roundToInt(), 0) }.padding(4.dp).size(56.dp)
                .drawBehind {
                    drawCircle(Color(0xFFEE7D77).copy(alpha = 0.2f), radius = size.minDimension * 0.8f, center = Offset(size.width / 2, size.height / 2))
                }
                .background(AstraTheme.Error, RectangleShape)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        progress = (progress + delta / maxDrag).coerceIn(0f, 1f)
                    },
                    onDragStopped = {
                        if (progress >= 0.95f) onCommit() else progress = 0f
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(">>", style = AstraTheme.Typography.labelMedium.copy(color = Color(0xFF490106), fontWeight = FontWeight.Black, fontSize = 16.sp))
        }
    }
}

@Composable
private fun PanicWiping(state: PanicUiState.Wiping) {
    Box(Modifier.fillMaxSize().background(AstraTheme.SurfaceContainerLowest), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AstraTheme.Error, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(state.phase, style = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.Error, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace))
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { state.percentage / 100f }, modifier = Modifier.width(200.dp).height(4.dp), color = AstraTheme.Error, trackColor = AstraTheme.SurfaceContainerHigh)
            Spacer(Modifier.height(8.dp))
            Text("${state.percentage}%", style = AstraTheme.Typography.headlineSmall.copy(color = AstraTheme.Error, fontSize = 24.sp, fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun PanicTombstoned() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("> PURGE COMPLETE", style = AstraTheme.Typography.headlineSmall.copy(color = AstraTheme.Error, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp))
            Spacer(Modifier.height(4.dp))
            Text("> THIS DEVICE IS NO LONGER PROVISIONED.", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error.copy(0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace), textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Text("To use AstraSecure again:\n  1. Uninstall this app (recommended)\n  2. Settings → Apps → Clear data",
                style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace), textAlign = TextAlign.Start)
        }
    }
}
