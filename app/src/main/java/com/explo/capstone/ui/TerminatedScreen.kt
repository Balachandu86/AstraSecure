package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown when [com.explo.capstone.identity.IdentityManager.readTombstone] returns true.
 * No navigation out — this is a terminal state until OS Clear Data is performed.
 */
@Composable
fun TerminatedScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstraTheme.SurfaceDim)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .padding(32.dp)
                .border(1.dp, AstraTheme.Error.copy(alpha = 0.3f))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = AstraTheme.Error,
                modifier = Modifier.size(36.dp),
            )

            Text(
                "// TERMINATED",
                style = AstraTheme.Typography.headlineSmall.copy(
                    color = AstraTheme.Error,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AstraTheme.Error.copy(alpha = 0.25f))
            )

            Text(
                "THIS DEVICE HAS BEEN WIPED.",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.OnSurface.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                ),
                textAlign = TextAlign.Center,
            )

            Text(
                "ALL CRYPTOGRAPHIC MATERIAL HAS BEEN DESTROYED.\nOPERATOR IDENTITY IS NO LONGER VALID.",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.OnSurface.copy(alpha = 0.45f),
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                "> CONTACT COMMAND FOR RE-ASSIGNMENT",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Error.copy(alpha = 0.65f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}
