package com.explo.capstone.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.explo.capstone.shared.ColorToken
import com.explo.capstone.shared.Mission
import com.explo.capstone.shared.MissionStatus
import com.explo.capstone.shared.MissionType
import com.explo.capstone.shared.ParticipantStatus

// ─── UI State ────────────────────────────────────────────────────────────────

data class MissionRow(
    val mission: Mission,
    val type: MissionType,
    val channelCount: Int,
    val lastActivityFormatted: String,
)

data class DashboardSummary(
    val activeLinks: Int,
    val maxLinks: Int,
    val signal: SignalStrength,
    val encryption: String,
    val uplinkId: String,
)

enum class SignalStrength { STABLE, DEGRADED, OFFLINE }

sealed interface MissionsUiState {
    data object Loading : MissionsUiState
    data class Content(
        val missions: List<MissionRow>,
        val summary: DashboardSummary,
        val systemLogs: List<String>,
        val missionTypes: List<MissionType>,
        val degraded: Set<DegradedSubsystem> = emptySet(),
        val showCreateSheet: Boolean = false,
        val localUserId: String = "",
    ) : MissionsUiState
    data class Empty(
        val reason: String,
        val missionTypes: List<MissionType> = emptyList(),
        val showCreateSheet: Boolean = false,
        val localUserId: String = "",
    ) : MissionsUiState
    data class Error(val message: String) : MissionsUiState
}

// ─── Intents ─────────────────────────────────────────────────────────────────

sealed interface MissionsIntent {
    data object Refresh : MissionsIntent
    data class Open(val missionId: String) : MissionsIntent
    data class EmergencyOverride(val missionId: String) : MissionsIntent
    data object NavigateToProfile : MissionsIntent
    data object ShowCreateSheet : MissionsIntent
    data object DismissCreateSheet : MissionsIntent
    data class CreateMission(val name: String, val typeId: String) : MissionsIntent
    /** Navigate to the redeem-invite screen. The redeemer enters a token (paste
     *  or scan) and lands as a PENDING participant on the mission. */
    data object NavigateToRedeem : MissionsIntent
}

// ─── Root composable ─────────────────────────────────────────────────────────

@Composable
fun MissionsContent(
    state: MissionsUiState,
    onIntent: (MissionsIntent) -> Unit,
) {
    when (state) {
        is MissionsUiState.Loading -> LoadingState()
        is MissionsUiState.Content -> ContentState(state, onIntent)
        is MissionsUiState.Empty -> EmptyState(state, onIntent)
        is MissionsUiState.Error -> ErrorState(state.message)
    }
}

// ─── Loading ─────────────────────────────────────────────────────────────────

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = AstraTheme.Primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "> LOADING MISSION QUEUE...",
                style = AstraTheme.Typography.labelMedium.copy(
                    color = AstraTheme.Primary.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                )
            )
        }
    }
}

// ─── Content ─────────────────────────────────────────────────────────────────

@Composable
private fun ContentState(
    state: MissionsUiState.Content,
    onIntent: (MissionsIntent) -> Unit,
) {
    var briefingDismissed by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Degraded banner
            if (state.degraded.isNotEmpty()) {
                DegradedBanner(state.degraded)
            }

            Spacer(Modifier.height(16.dp))
            DashboardSummaryBar(state.summary)
            Spacer(Modifier.height(20.dp))

            if (!briefingDismissed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(AstraTheme.Tertiary.copy(alpha = 0.4f))
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(AstraTheme.SurfaceContainerLow.copy(alpha = 0.5f))
                            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "> OPERATOR BRIEFING",
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = AstraTheme.Tertiary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    fontSize = 10.sp,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { briefingDismissed = true },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Dismiss briefing",
                                    tint = AstraTheme.Tertiary.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Select an active mission below to access its secure channels, encrypted documents, and operational data. Each mission contains isolated communication channels organized by clearance level.",
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = Color(0xFFACABAA).copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                lineHeight = 15.sp,
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "TAP A MISSION CARD → VIEW CHANNELS → ENTER SECURE CHAT",
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = AstraTheme.Primary,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                            )
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            MissionQueueSection(state.missions, state.localUserId, onIntent)
            Spacer(Modifier.height(24.dp))
            SystemLogsFooter(state.systemLogs)
            Spacer(Modifier.height(120.dp)) // room for FAB stack
        }

        MissionsFabStack(
            onCreate = { onIntent(MissionsIntent.ShowCreateSheet) },
            onJoin = { onIntent(MissionsIntent.NavigateToRedeem) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (state.showCreateSheet) {
        CreateMissionSheet(
            missionTypes = state.missionTypes,
            onDismiss = { onIntent(MissionsIntent.DismissCreateSheet) },
            onCreate = { name, typeId -> onIntent(MissionsIntent.CreateMission(name, typeId)) },
        )
    }
}

// ─── Empty ───────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(state: MissionsUiState.Empty, onIntent: (MissionsIntent) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "> NO MISSIONS IN QUEUE",
                style = AstraTheme.Typography.headlineSmall.copy(
                    color = AstraTheme.Primary,
                    fontSize = 16.sp,
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.reason,
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA),
                    fontSize = 11.sp,
                )
            )
        }

        MissionsFabStack(
            onCreate = { onIntent(MissionsIntent.ShowCreateSheet) },
            onJoin = { onIntent(MissionsIntent.NavigateToRedeem) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (state.showCreateSheet) {
        CreateMissionSheet(
            missionTypes = state.missionTypes,
            onDismiss = { onIntent(MissionsIntent.DismissCreateSheet) },
            onCreate = { name, typeId -> onIntent(MissionsIntent.CreateMission(name, typeId)) },
        )
    }}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateMissionSheet(
    missionTypes: List<MissionType>,
    onDismiss: () -> Unit,
    onCreate: (name: String, typeId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedTypeId by remember { mutableStateOf(missionTypes.firstOrNull()?.id ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AstraTheme.SurfaceContainerLow,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "// NEW MISSION",
                    style = AstraTheme.Typography.headlineSmall.copy(
                        color = AstraTheme.Primary,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Black,
                    ),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = AstraTheme.OutlineVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.15f)))
            Spacer(Modifier.height(20.dp))

            Text(
                "MISSION NAME",
                style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp),
            )
            Spacer(Modifier.height(4.dp))
            TextField(
                value = name,
                onValueChange = { name = it.uppercase(); nameError = null },
                placeholder = { Text("> OPERATION_NAME", style = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.OutlineVariant)) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.OnSurface),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AstraTheme.SurfaceContainerLowest,
                    unfocusedContainerColor = AstraTheme.SurfaceContainerLowest,
                    focusedIndicatorColor = AstraTheme.Primary,
                    unfocusedIndicatorColor = AstraTheme.OutlineVariant,
                    focusedTextColor = AstraTheme.OnSurface,
                    unfocusedTextColor = AstraTheme.OnSurface,
                    cursorColor = AstraTheme.Primary,
                ),
                shape = RectangleShape,
            )
            if (nameError != null) {
                Text(
                    nameError!!,
                    style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error, fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            if (missionTypes.isEmpty()) {
                Text(
                    "> NO MISSION TYPES CONFIGURED — CONTACT ADMIN",
                    style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Secondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                )
            } else {
                Text(
                    "MISSION TYPE",
                    style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp),
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    missionTypes.forEach { type ->
                        val sel = type.id == selectedTypeId
                        val (_, tc, bc) = resolveColorToken(type.accent)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(if (sel) tc.copy(0.1f) else Color.Transparent)
                                .border(1.dp, if (sel) bc else AstraTheme.OutlineVariant.copy(0.15f))
                                .clickable { selectedTypeId = type.id }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).background(if (sel) tc else AstraTheme.OutlineVariant.copy(0.4f), CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                type.name,
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = if (sel) tc else Color(0xFFACABAA),
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 10.sp,
                                ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (name.isBlank()) { nameError = "> MISSION_NAME_REQUIRED"; return@Button }
                    if (!name.matches(Regex("^[A-Z][A-Z0-9_ ]{0,49}$"))) { nameError = "> LETTERS, DIGITS, UNDERSCORE, SPACE ONLY"; return@Button }
                    if (selectedTypeId.isEmpty()) { nameError = "> MISSION_TYPE_REQUIRED"; return@Button }
                    onCreate(name.trim(), selectedTypeId)
                },
                enabled = missionTypes.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.Primary, contentColor = AstraTheme.OnPrimary),
            ) {
                Text("> CREATE_MISSION", style = AstraTheme.Typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
            }
        }
    }
}

// ─── Error ───────────────────────────────────────────────────────────────────

@Composable
private fun ErrorState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "> ERROR",
                style = AstraTheme.Typography.headlineSmall.copy(
                    color = AstraTheme.Error,
                    fontSize = 16.sp,
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Error.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            )
        }
    }
}

// ─── Dashboard summary bar ───────────────────────────────────────────────────

@Composable
private fun DashboardSummaryBar(summary: DashboardSummary) {
    val infiniteTransition = rememberInfiniteTransition(label = "uplink_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val signalColor = when (summary.signal) {
        SignalStrength.STABLE -> AstraTheme.Tertiary
        SignalStrength.DEGRADED -> AstraTheme.Secondary
        SignalStrength.OFFLINE -> AstraTheme.Error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // Left accent strip
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(AstraTheme.Primary)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AstraTheme.SurfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DashStatItem("Active Links", "${summary.activeLinks}", "/${summary.maxLinks}", AstraTheme.Primary)
                DashStatItem("Signal", summary.signal.name, null, signalColor)
                DashStatItem("Encryption", summary.encryption, null, AstraTheme.OnSurface)
            }
            // Uplink status indicator
            Row(
                modifier = Modifier
                    .background(AstraTheme.SurfaceContainerLowest)
                    .border(1.dp, AstraTheme.OutlineVariant.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(AstraTheme.Tertiary.copy(alpha = pulseAlpha), CircleShape)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val uplinkTextStyle = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Tertiary,
                        fontSize = 8.sp,
                        letterSpacing = 0.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("SECURE", style = uplinkTextStyle, softWrap = false, overflow = TextOverflow.Clip)
                    Text("CONNECTION", style = uplinkTextStyle, softWrap = false, overflow = TextOverflow.Clip)
                    Text(
                        summary.uplinkId.uppercase(),
                        style = uplinkTextStyle,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashStatItem(label: String, value: String, suffix: String?, valueColor: Color) {
    Column {
        Text(
            label.uppercase(),
            style = AstraTheme.Typography.labelSmall.copy(
                color = Color(0xFFACABAA).copy(alpha = 0.7f),
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = AstraTheme.Typography.headlineSmall.copy(
                    color = valueColor,
                    fontSize = 17.sp
                )
            )
            if (suffix != null) {
                Text(
                    suffix,
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = Color(0xFFACABAA).copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                )
            }
        }
    }
}

// ─── Mission queue section ───────────────────────────────────────────────────

@Composable
private fun MissionQueueSection(missions: List<MissionRow>, localUserId: String, onIntent: (MissionsIntent) -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(1.dp)
                    .background(AstraTheme.OutlineVariant)
            )
            Text(
                "ACTIVE MISSION QUEUE",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA),
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            )
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            missions.forEach { row ->
                MissionListItem(row, localUserId, onIntent)
            }
        }
    }
}

// ─── Mission list item ───────────────────────────────────────────────────────

@Composable
private fun MissionListItem(row: MissionRow, localUserId: String, onIntent: (MissionsIntent) -> Unit) {
    val mission = row.mission

    val borderColor = when (mission.status) {
        MissionStatus.COMPROMISED -> AstraTheme.Error.copy(alpha = 0.5f)
        else -> AstraTheme.OutlineVariant.copy(alpha = 0.2f)
    }
    val statusColor = when (mission.status) {
        MissionStatus.ACTIVE      -> AstraTheme.Tertiary
        MissionStatus.STANDBY     -> AstraTheme.Secondary
        MissionStatus.COMPROMISED -> AstraTheme.Error
        MissionStatus.ARCHIVED    -> Color(0xFFACABAA)
    }
    val statusLabel = when (mission.status) {
        MissionStatus.ACTIVE      -> "ACTIVE STATUS"
        MissionStatus.STANDBY     -> "STANDBY MODE"
        MissionStatus.COMPROMISED -> "COMPROMISED"
        MissionStatus.ARCHIVED    -> "ARCHIVED"
    }
    val statusIcon: ImageVector = when (mission.status) {
        MissionStatus.ACTIVE      -> Icons.Filled.RadioButtonChecked
        MissionStatus.STANDBY     -> Icons.Outlined.PauseCircle
        MissionStatus.COMPROMISED -> Icons.Filled.Report
        MissionStatus.ARCHIVED    -> Icons.Outlined.Archive
    }
    val actionLabel = when (mission.status) {
        MissionStatus.ACTIVE      -> "Access Data"
        MissionStatus.STANDBY     -> "Reconnect"
        MissionStatus.COMPROMISED -> "Emergency Override"
        MissionStatus.ARCHIVED    -> "View Archive"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Left border strip
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(borderColor)
            )
            // Card content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(AstraTheme.SurfaceContainerLow)
                    .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp)
            ) {
                // Mission ID — top right
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "ID: ${mission.id}",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = Color(0xFFACABAA).copy(alpha = 0.3f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }

                // Name + type badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        mission.name.uppercase(),
                        style = AstraTheme.Typography.headlineSmall.copy(
                            color = AstraTheme.OnSurface,
                            fontSize = 15.sp,
                            letterSpacing = 0.5.sp
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    MissionTypeBadge(row.type)
                }

                Spacer(Modifier.height(10.dp))

                // Status / time / channels row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetaChip(statusIcon, statusLabel, statusColor, bold = true)
                    MetaChip(Icons.Outlined.Schedule, "LAST: ${row.lastActivityFormatted}", Color(0xFFACABAA))
                    MetaChip(
                        Icons.Outlined.Hub,
                        "${row.channelCount.toString().padStart(2, '0')} CHANNELS",
                        Color(0xFFACABAA)
                    )
                }
                if (mission.createdBy.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Person, null, tint = Color(0xFFACABAA).copy(0.5f), modifier = Modifier.size(11.dp))
                        val ownerLabel = if (mission.createdBy == localUserId) "YOU"
                                         else mission.createdBy.take(12).uppercase()
                        Text(
                            "OWNER: $ownerLabel",
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = if (mission.createdBy == localUserId) AstraTheme.Primary.copy(0.7f) else Color(0xFFACABAA).copy(0.5f),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Pending banner — replaces normal access controls until a CHIEF
                // confirms this operator (post-invite-redemption flow).
                if (mission.myStatus == ParticipantStatus.PENDING) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(AstraTheme.Secondary.copy(0.06f))
                            .border(1.dp, AstraTheme.Secondary.copy(0.4f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "[ AWAITING CHIEF CONFIRMATION ]",
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = AstraTheme.Secondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        if (!mission.inviterFingerprint.isNullOrBlank()) {
                            Text(
                                "INVITER FINGERPRINT  ${mission.inviterFingerprint}",
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = AstraTheme.OnSurface,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                            Text(
                                "VERIFY THIS MATCHES THE INVITER OUT-OF-BAND",
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = Color(0xFFACABAA).copy(0.6f),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                } else when (mission.status) {
                    MissionStatus.ACTIVE -> Button(
                        onClick = { onIntent(MissionsIntent.Open(mission.id)) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AstraTheme.Primary,
                            contentColor = AstraTheme.OnPrimary
                        )
                    ) {
                        Text(
                            "> ${actionLabel.uppercase()}",
                            style = AstraTheme.Typography.labelMedium.copy(
                                color = AstraTheme.OnPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    MissionStatus.STANDBY -> OutlinedButton(
                        onClick = { onIntent(MissionsIntent.Open(mission.id)) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, AstraTheme.OutlineVariant.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AstraTheme.Primary)
                    ) {
                        Text(
                            "> ${actionLabel.uppercase()}",
                            style = AstraTheme.Typography.labelMedium
                        )
                    }

                    MissionStatus.COMPROMISED -> Button(
                        onClick = { onIntent(MissionsIntent.EmergencyOverride(mission.id)) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AstraTheme.Error,
                            contentColor = Color(0xFF490106)
                        )
                    ) {
                        Text(
                            "> ${actionLabel.uppercase()}",
                            style = AstraTheme.Typography.labelMedium.copy(
                                color = Color(0xFF490106),
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    MissionStatus.ARCHIVED -> OutlinedButton(
                        onClick = { onIntent(MissionsIntent.Open(mission.id)) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, AstraTheme.OutlineVariant.copy(alpha = 0.2f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFACABAA))
                    ) {
                        Text(
                            "> ${actionLabel.uppercase()}",
                            style = AstraTheme.Typography.labelMedium.copy(color = Color(0xFFACABAA))
                        )
                    }
                }
            }
        }
        // Bottom error stripe for COMPROMISED missions
        if (mission.status == MissionStatus.COMPROMISED) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(AstraTheme.Error.copy(alpha = 0.2f))
            )
        }
    }
}

// ─── Mission type badge (replaces ClassificationBadge) ───────────────────────

@Composable
private fun MissionTypeBadge(type: MissionType) {
    val (bg, textColor, bdrColor) = resolveColorToken(type.accent)

    Box(
        modifier = Modifier
            .background(bg)
            .border(1.dp, bdrColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            type.name,
            style = AstraTheme.Typography.labelSmall.copy(
                color = textColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 9.sp
            )
        )
    }
}

/** Resolve a [ColorToken] into (background, text, border) for badges. */
private fun resolveColorToken(token: ColorToken): Triple<Color, Color, Color> = when (token) {
    ColorToken.TERTIARY  -> Triple(AstraTheme.SurfaceContainerHighest, AstraTheme.Tertiary, AstraTheme.Tertiary.copy(alpha = 0.2f))
    ColorToken.NEUTRAL   -> Triple(AstraTheme.SurfaceContainerHighest, Color(0xFFACABAA), AstraTheme.OutlineVariant.copy(alpha = 0.4f))
    ColorToken.ERROR     -> Triple(Color(0xFF7F2927), Color(0xFFFF9993), AstraTheme.Error.copy(alpha = 0.4f))
    ColorToken.PRIMARY   -> Triple(AstraTheme.SurfaceContainerHighest, AstraTheme.Primary, AstraTheme.Primary.copy(alpha = 0.2f))
    ColorToken.SECONDARY -> Triple(AstraTheme.SurfaceContainerHighest, AstraTheme.Secondary, AstraTheme.Secondary.copy(alpha = 0.2f))
}

// ─── Meta chip ───────────────────────────────────────────────────────────────

@Composable
private fun MetaChip(icon: ImageVector, label: String, color: Color, bold: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(
            label,
            style = AstraTheme.Typography.labelSmall.copy(
                color = color,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
        )
    }
}

// ─── System logs footer ──────────────────────────────────────────────────────

@Composable
private fun SystemLogsFooter(logs: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AstraTheme.SurfaceContainerLowest)
            .border(1.dp, AstraTheme.OutlineVariant.copy(alpha = 0.1f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        logs.forEach { line ->
            Text(
                line,
                style = AstraTheme.Typography.labelSmall.copy(
                    color = Color(0xFFACABAA).copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}

// ─── FAB stack: NEW MISSION (primary) + JOIN VIA INVITE (secondary) ──────────

@Composable
private fun MissionsFabStack(
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        JoinViaInviteFab(onClick = onJoin)
        FloatingActionButton(
            onClick = onCreate,
            containerColor = AstraTheme.Primary,
            contentColor = AstraTheme.OnPrimary,
            shape = RectangleShape,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New Mission", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "NEW MISSION",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.OnPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun JoinViaInviteFab(onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = AstraTheme.SurfaceContainerHigh,
        contentColor = AstraTheme.Tertiary,
        shape = RectangleShape,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Join via invite", modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "JOIN",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Tertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                ),
            )
        }
    }
}
