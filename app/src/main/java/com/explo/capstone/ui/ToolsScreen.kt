package com.explo.capstone.ui

import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

enum class ToolMode { ENCODE_DECODE, HASH }
enum class Direction { ENCODE, DECODE }
enum class Algorithm(val label: String, val needsKey: Boolean = false) {
    BASE64("BASE64"), HEX("HEX_STRING"), URL_ENCODE("URL_ENCODE"),
    AES_256_GCM("AES-256-GCM", true),
    SHA_256("SHA-256"), SHA_512("SHA-512"),
}

data class ToolsState(
    val mode: ToolMode = ToolMode.ENCODE_DECODE, val algo: Algorithm = Algorithm.BASE64,
    val direction: Direction = Direction.ENCODE, val input: String = "", val key: String = "",
    val output: String = "", val outputError: Boolean = false, val degraded: Set<DegradedSubsystem> = emptySet(),
)

// Top-level so ToolsRoute can call it without going through the composable
fun executeToolsOp(state: ToolsState): ToolsState {
    if (state.input.isBlank()) return state.copy(output = "> ERROR: EMPTY_INPUT", outputError = true)
    return try {
        val result = when (state.mode) {
            ToolMode.ENCODE_DECODE -> when (state.algo) {
                Algorithm.BASE64 -> if (state.direction == Direction.ENCODE)
                    Base64.encodeToString(state.input.toByteArray(), Base64.NO_WRAP)
                else String(Base64.decode(state.input, Base64.NO_WRAP))
                Algorithm.HEX -> if (state.direction == Direction.ENCODE)
                    state.input.toByteArray().joinToString("") { "%02x".format(it) }
                else String(state.input.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                Algorithm.URL_ENCODE -> if (state.direction == Direction.ENCODE)
                    URLEncoder.encode(state.input, "UTF-8")
                else URLDecoder.decode(state.input, "UTF-8")
                Algorithm.AES_256_GCM -> return state // AES routed through ToolsViewModel
                else -> "> UNSUPPORTED"
            }
            ToolMode.HASH -> {
                val alg = when (state.algo) { Algorithm.SHA_512 -> "SHA-512"; else -> "SHA-256" }
                MessageDigest.getInstance(alg).digest(state.input.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
        }
        state.copy(output = result, outputError = false)
    } catch (e: Exception) {
        state.copy(output = "> ERROR: ${e.message?.uppercase() ?: "UNKNOWN"}", outputError = true)
    }
}

@Composable
fun ToolsContent(
    state: ToolsState,
    onStateChange: (ToolsState) -> Unit,
    onExecute: () -> Unit,
) {
    val algos = when (state.mode) {
        ToolMode.ENCODE_DECODE -> listOf(Algorithm.BASE64, Algorithm.HEX, Algorithm.URL_ENCODE, Algorithm.AES_256_GCM)
        ToolMode.HASH -> listOf(Algorithm.SHA_256, Algorithm.SHA_512)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (state.degraded.isNotEmpty()) DegradedBanner(state.degraded)
        Spacer(Modifier.height(16.dp))

        // Mode tabs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolMode.entries.forEach { mode ->
                val sel = mode == state.mode
                Box(
                    Modifier.weight(1f)
                        .background(if (sel) AstraTheme.Primary.copy(0.15f) else AstraTheme.SurfaceContainerLow)
                        .border(1.dp, if (sel) AstraTheme.Primary.copy(0.3f) else AstraTheme.OutlineVariant.copy(0.15f))
                        .clickable {
                            onStateChange(state.copy(
                                mode = mode,
                                algo = if (mode == ToolMode.HASH) Algorithm.SHA_256 else Algorithm.BASE64,
                                output = "", outputError = false,
                            ))
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode.name.replace("_", "/"),
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = if (sel) AstraTheme.Primary else Color(0xFFACABAA),
                            fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp
                        )
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Algorithm select
        Row(
            Modifier.fillMaxWidth().background(AstraTheme.SurfaceContainerLowest)
                .border(1.dp, AstraTheme.OutlineVariant.copy(0.15f)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            algos.forEach { a ->
                val sel = a == state.algo
                Box(
                    Modifier.weight(1f)
                        .background(if (sel) AstraTheme.Primary.copy(0.12f) else Color.Transparent)
                        .border(1.dp, if (sel) AstraTheme.Primary.copy(0.3f) else Color.Transparent)
                        .clickable { onStateChange(state.copy(algo = a, output = "", outputError = false)) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        a.label,
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = if (sel) AstraTheme.Primary else Color(0xFFACABAA),
                            fontWeight = FontWeight.Bold, fontSize = 9.sp
                        )
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Direction toggle (encode/decode only)
        if (state.mode == ToolMode.ENCODE_DECODE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Direction.entries.forEach { d ->
                    val sel = d == state.direction
                    OutlinedButton(
                        onClick = { onStateChange(state.copy(direction = d, output = "", outputError = false)) },
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, if (sel) AstraTheme.Primary.copy(0.4f) else AstraTheme.OutlineVariant.copy(0.2f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (sel) AstraTheme.Primary.copy(0.1f) else Color.Transparent
                        )
                    ) {
                        Text(
                            d.name,
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = if (sel) AstraTheme.Primary else Color(0xFFACABAA),
                                fontWeight = FontWeight.Bold, fontSize = 10.sp
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Key field (AES only)
        if (state.algo.needsKey) {
            Text("CIPHER KEY", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.key,
                onValueChange = { onStateChange(state.copy(key = it)) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("Enter encryption key...", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.3f))) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AstraTheme.Primary,
                    unfocusedBorderColor = AstraTheme.OutlineVariant.copy(0.2f),
                    cursorColor = AstraTheme.Primary,
                )
            )
            Spacer(Modifier.height(12.dp))
        }

        // Input
        Text("RAW INPUT", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = state.input,
            onValueChange = { onStateChange(state.copy(input = it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            textStyle = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            placeholder = { Text("Enter data to process...", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.3f))) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AstraTheme.Primary,
                unfocusedBorderColor = AstraTheme.OutlineVariant.copy(0.2f),
                cursorColor = AstraTheme.Primary,
            )
        )
        Text(
            "${state.input.length} chars // ${state.input.lines().size} lines",
            style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        )
        Spacer(Modifier.height(12.dp))

        // Execute
        Button(
            onClick = onExecute,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.Primary, contentColor = AstraTheme.OnPrimary)
        ) {
            Text("> EXECUTE_PROCESS", style = AstraTheme.Typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        }
        Spacer(Modifier.height(16.dp))

        // Output
        Text("PROCESSED OUTPUT", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 100.dp)
                .background(AstraTheme.SurfaceContainerLowest)
                .border(1.dp, if (state.outputError) AstraTheme.Error.copy(0.3f) else AstraTheme.OutlineVariant.copy(0.1f))
                .padding(12.dp)
        ) {
            if (state.output.isEmpty()) {
                Text(
                    "[SYSTEM_IDLE] // WAITING_FOR_EXECUTION_COMMAND",
                    style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.3f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                )
            } else {
                Text(
                    state.output,
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = if (state.outputError) AstraTheme.Error else AstraTheme.Tertiary,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Clear
        OutlinedButton(
            onClick = { onStateChange(state.copy(input = "", output = "", key = "", outputError = false)) },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RectangleShape,
            border = BorderStroke(1.dp, AstraTheme.OutlineVariant.copy(0.2f))
        ) {
            Text("> CLEAR ALL", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontWeight = FontWeight.Bold, fontSize = 10.sp))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "> SECURE_LINK_ESTABLISHED // DATA_WIPE_ON_PAUSE",
            style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.3f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        )
        Spacer(Modifier.height(16.dp))
    }
}
