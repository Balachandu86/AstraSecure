package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
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

// ─── State ───────────────────────────────────────────────────────────────────

data class VaultEntryUi(
    val docId: String,
    val fileName: String,
    val missionName: String,
    val missionId: String,
    val age: String,
)

data class VaultUiState(
    val entries: List<VaultEntryUi> = emptyList(),
    val missions: List<Pair<String, String>> = emptyList(), // id → name
    val selectedMissionId: String? = null,
    val statusMessage: String? = null,
    val isWorking: Boolean = false,
)

sealed interface VaultIntent {
    data class SelectMission(val id: String) : VaultIntent
    data object ImportFile : VaultIntent
    data class DecryptAndSave(val docId: String, val fileName: String) : VaultIntent
    data class DeleteEntry(val docId: String) : VaultIntent
    data object DismissStatus : VaultIntent
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun VaultBody(
    state: VaultUiState,
    onIntent: (VaultIntent) -> Unit,
) {
    // No verticalScroll or fillMaxSize here — VaultBody is always rendered inside
    // ToolsContent's own scrollable Column, so adding another scroll axis crashes Compose.
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(4.dp))

        // Status banner
        state.statusMessage?.let { msg ->
            val isError = msg.startsWith(">")
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (isError) AstraTheme.Error.copy(0.1f) else AstraTheme.Tertiary.copy(0.1f))
                    .border(1.dp, if (isError) AstraTheme.Error.copy(0.3f) else AstraTheme.Tertiary.copy(0.3f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    msg,
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = if (isError) AstraTheme.Error else AstraTheme.Tertiary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "×",
                    style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontSize = 14.sp),
                    modifier = Modifier.clickable { onIntent(VaultIntent.DismissStatus) }.padding(4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Mission selector
        VaultSectionLabel("MISSION CONTEXT")
        if (state.missions.isEmpty()) {
            Text(
                "> NO ACTIVE MISSIONS — CREATE A MISSION FIRST",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA).copy(0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AstraTheme.SurfaceContainerLowest)
                    .border(1.dp, AstraTheme.OutlineVariant.copy(0.15f)),
            ) {
                state.missions.forEach { (id, name) ->
                    val selected = id == state.selectedMissionId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (selected) AstraTheme.Primary.copy(0.08f) else Color.Transparent)
                            .clickable { onIntent(VaultIntent.SelectMission(id)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(if (selected) AstraTheme.Primary else Color(0xFF3A4A5A)),
                        )
                        Text(
                            name.uppercase(),
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = if (selected) AstraTheme.Primary else Color(0xFFACABAA),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Text(
                                "SELECTED",
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = AstraTheme.Primary.copy(0.7f), fontSize = 8.sp, letterSpacing = 1.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Import button
        Button(
            onClick = { onIntent(VaultIntent.ImportFile) },
            enabled = state.selectedMissionId != null && !state.isWorking,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = AstraTheme.Primary,
                contentColor = AstraTheme.OnPrimary,
                disabledContainerColor = AstraTheme.SurfaceContainerHigh,
                disabledContentColor = Color(0xFF4A5A6A),
            ),
        ) {
            if (state.isWorking) {
                CircularProgressIndicator(
                    color = AstraTheme.OnPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("> ENCRYPTING...", style = AstraTheme.Typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
            } else {
                Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.selectedMissionId == null) "> SELECT MISSION FIRST" else "> IMPORT FILE FROM STORAGE",
                    style = AstraTheme.Typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // Size and deletion notes
        Column(
            Modifier
                .fillMaxWidth()
                .background(AstraTheme.SurfaceContainerLowest)
                .border(1.dp, AstraTheme.OutlineVariant.copy(0.1f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "> MAX FILE SIZE: 25 MB  //  ALL FILE TYPES ACCEPTED",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA).copy(0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                "> ORIGINAL FILE: DELETION ATTEMPTED AFTER ENCRYPTION.",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA).copy(0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                "  IF DELETION FAILS (SHARED STORAGE RESTRICTION), YOU MUST DELETE IT MANUALLY.",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Secondary.copy(0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                ),
            )
        }
        Spacer(Modifier.height(20.dp))

        // Vault entries
        VaultSectionLabel("ENCRYPTED VAULT  //  ${state.entries.size} ENTRIES")
        if (state.entries.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(AstraTheme.SurfaceContainerLowest)
                    .border(1.dp, AstraTheme.OutlineVariant.copy(0.1f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "> [VAULT_EMPTY] // NO DOCUMENTS ENCRYPTED",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = Color(0xFFACABAA).copy(0.3f), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.entries.forEach { entry ->
                    VaultEntryCard(entry, onIntent)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Footer
        Text(
            "> VAULT_KEY_SCHEME: AES-256-GCM // PER_DOC_KEYSTORE_ALIAS",
            style = AstraTheme.Typography.labelSmall.copy(
                color = Color(0xFFACABAA).copy(0.25f), fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            ),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun VaultEntryCard(entry: VaultEntryUi, onIntent: (VaultIntent) -> Unit) {
    var showConfirmDelete by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(AstraTheme.SurfaceContainerLow)
            .border(1.dp, AstraTheme.OutlineVariant.copy(0.12f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Shield,
            null,
            tint = AstraTheme.Tertiary.copy(0.5f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.fileName,
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.OnSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
            Text(
                "${entry.missionName.uppercase()} // ${entry.age} ago",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA).copy(0.5f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                entry.docId,
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Primary.copy(0.4f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                ),
            )
        }
        Spacer(Modifier.width(8.dp))
        // Decrypt button
        Row(
            Modifier
                .background(AstraTheme.Tertiary.copy(0.08f))
                .border(1.dp, AstraTheme.Tertiary.copy(0.2f))
                .clickable { onIntent(VaultIntent.DecryptAndSave(entry.docId, entry.fileName)) }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.LockOpen, null, tint = AstraTheme.Tertiary, modifier = Modifier.size(12.dp))
            Text(
                "DECRYPT",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Tertiary, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                ),
            )
        }
        Spacer(Modifier.width(4.dp))
        // Delete button
        if (showConfirmDelete) {
            Row(
                Modifier
                    .background(AstraTheme.Error.copy(0.12f))
                    .border(1.dp, AstraTheme.Error.copy(0.3f))
                    .clickable { onIntent(VaultIntent.DeleteEntry(entry.docId)); showConfirmDelete = false }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "CONFIRM",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Error, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    ),
                )
            }
        } else {
            Icon(
                Icons.Outlined.Delete,
                null,
                tint = AstraTheme.Error.copy(0.5f),
                modifier = Modifier
                    .size(30.dp)
                    .clickable { showConfirmDelete = true }
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun VaultSectionLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Box(Modifier.width(20.dp).height(1.dp).background(AstraTheme.OutlineVariant.copy(0.4f)))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = AstraTheme.Typography.labelSmall.copy(
                color = Color(0xFFACABAA).copy(0.7f),
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
            ),
        )
    }
}
