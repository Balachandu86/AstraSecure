package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
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
import com.explo.capstone.shared.ColorToken

// ─── State ───────────────────────────────────────────────────────────────────

data class ProfileMissionClearance(
    val missionId: String,
    val missionName: String,
    val rankName: String,
    val rankLevel: Int,
    val rankColor: ColorToken,
)

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Content(
        val callsign: String,
        val hardwareKeyId: String,
        val provisionedLabel: String,
        val clearances: List<ProfileMissionClearance>,
        val isAdmin: Boolean = false,
    ) : ProfileUiState
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun ProfileContent(
    state: ProfileUiState,
    onSaveCallsign: (String) -> Unit = {},
    onAdminClick: (() -> Unit)? = null,
) {
    when (state) {
        is ProfileUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AstraTheme.Primary, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
        is ProfileUiState.Content -> ProfileBody(state, onSaveCallsign, onAdminClick)
    }
}

@Composable
private fun ProfileBody(
    state: ProfileUiState.Content,
    onSaveCallsign: (String) -> Unit,
    onAdminClick: (() -> Unit)?,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember(state.callsign) { mutableStateOf(state.callsign) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        // Avatar + callsign banner
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(AstraTheme.Primary))
            Row(
                Modifier
                    .weight(1f)
                    .background(AstraTheme.SurfaceContainerLow)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(AstraTheme.Primary.copy(0.12f), CircleShape)
                        .border(1.dp, AstraTheme.Primary.copy(0.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Person, null, tint = AstraTheme.Primary, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.callsign,
                        style = AstraTheme.Typography.headlineSmall.copy(
                            color = AstraTheme.OnSurface,
                            fontSize = 20.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    )
                    Text(
                        "> OPERATOR PROFILE",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.Primary.copy(0.6f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    )
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clickable { isEditing = !isEditing },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Edit, null, tint = AstraTheme.Primary.copy(0.6f), modifier = Modifier.size(18.dp))
                }
            }
        }

        // Inline callsign editor
        if (isEditing) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    AstraInputField(
                        value = editText,
                        onValueChange = { editText = it.uppercase().take(24) },
                        placeholder = "NEW_CALLSIGN",
                    )
                }
                Button(
                    onClick = {
                        val trimmed = editText.trim()
                        if (trimmed.isNotEmpty()) {
                            onSaveCallsign(trimmed)
                            isEditing = false
                        }
                    },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.Primary, contentColor = AstraTheme.OnPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("> SAVE", style = AstraTheme.Typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Identity info card
        ProfileSectionHeader("IDENTITY")
        Column(
            Modifier
                .fillMaxWidth()
                .background(AstraTheme.SurfaceContainerLow)
                .border(1.dp, AstraTheme.OutlineVariant.copy(0.1f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileInfoRow("CALLSIGN", state.callsign, AstraTheme.Primary)
            ProfileInfoRow(
                "HARDWARE KEY",
                state.hardwareKeyId.take(24).uppercase() + if (state.hardwareKeyId.length > 24) "…" else "",
                AstraTheme.Tertiary,
            )
            ProfileInfoRow("PROVISIONED", state.provisionedLabel, Color(0xFFACABAA))
        }
        Spacer(Modifier.height(20.dp))

        // Mission clearances
        ProfileSectionHeader("MISSION CLEARANCES")
        if (state.clearances.isEmpty()) {
            Text(
                "> NO CLEARANCE ASSIGNMENTS",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA).copy(0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.clearances.forEach { clr ->
                    val (_, tc, _) = resolveColorTokenTriple(clr.rankColor)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(AstraTheme.SurfaceContainerLow)
                            .border(1.dp, AstraTheme.OutlineVariant.copy(0.1f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(8.dp).background(tc, CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(
                                clr.missionName.uppercase(),
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = AstraTheme.OnSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                )
                            )
                            Text(
                                "ID: ${clr.missionId}",
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = Color(0xFFACABAA).copy(0.4f),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                clr.rankName,
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = tc,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                )
                            )
                            Text(
                                "LVL ${clr.rankLevel}",
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = tc.copy(0.6f),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            )
                        }
                    }
                }
            }
        }
        if (state.isAdmin && onAdminClick != null) {
            Spacer(Modifier.height(20.dp))
            ProfileSectionHeader("ADMINISTRATION")
            OutlinedButton(
                onClick = onAdminClick,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.Tertiary.copy(0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AstraTheme.Tertiary),
            ) {
                Icon(Icons.Outlined.AdminPanelSettings, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("> ADMIN CONSOLE", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Tertiary, fontWeight = FontWeight.Bold, fontSize = 10.sp))
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun ProfileSectionHeader(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Box(Modifier.width(28.dp).height(1.dp).background(AstraTheme.OutlineVariant))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = AstraTheme.Typography.labelSmall.copy(
                color = Color(0xFFACABAA),
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        )
    }
}

@Composable
private fun ProfileInfoRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = AstraTheme.Typography.labelSmall.copy(
                color = Color(0xFFACABAA).copy(0.6f),
                fontSize = 9.sp,
                letterSpacing = 1.sp,
            )
        )
        Text(
            value,
            style = AstraTheme.Typography.labelSmall.copy(
                color = valueColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        )
    }
}
