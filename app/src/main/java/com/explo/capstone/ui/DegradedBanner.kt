package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Degraded subsystems ─────────────────────────────────────────────────────

enum class DegradedSubsystem(val label: String) {
    CRYPTO("CRYPTO_OFFLINE"),
    IDENTITY("IDENTITY_OFFLINE"),
    METADATA("METADATA_OFFLINE"),
}

// ─── Degraded banner ─────────────────────────────────────────────────────────

/**
 * Top-of-screen banner shown when one or more subsystems are in degraded mode
 * (i.e. their module functions throw NotImplementedError).
 *
 * Design: surface-container-high background, secondary (amber) monospace text.
 * Per DESIGN.md: HUD/tactical legible failure states.
 */
@Composable
fun DegradedBanner(
    subsystems: Set<DegradedSubsystem>,
    modifier: Modifier = Modifier,
) {
    if (subsystems.isEmpty()) return

    val label = subsystems.joinToString(" + ") { it.label }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AstraTheme.SurfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "[ DEGRADED // $label ]",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.Secondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
        )
    }
}
