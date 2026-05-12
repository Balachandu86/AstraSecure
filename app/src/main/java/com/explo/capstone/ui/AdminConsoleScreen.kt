package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import com.explo.capstone.shared.*

// ─── Tab enum ────────────────────────────────────────────────────────────────

enum class AdminTab(val label: String) {
    RANKS("RANKS"),
    CHANNEL_CATEGORIES("CHAN_CAT"),
    MESSAGE_CATEGORIES("MSG_CAT"),
    MISSION_TYPES("MISSION_TYPES"),
    CLEARANCE("CLEARANCE"),
}

// ─── UI State ────────────────────────────────────────────────────────────────

data class AdminUiState(
    val activeTab: AdminTab = AdminTab.RANKS,
    val ranks: List<Rank> = emptyList(),
    val channelCategories: List<ChannelCategory> = emptyList(),
    val messageCategories: List<MessageCategory> = emptyList(),
    val missionTypes: List<MissionType> = emptyList(),
    val clearances: List<ClearanceAssignment> = emptyList(),
    val missions: List<Mission> = emptyList(),
    val editSheet: AdminEditSheet? = null,
    val deleteError: AdminDeleteError? = null,
    val localUserId: String = "user_local",
    /** Set after a successful "Generate invite" so the UI can display the
     *  freshly-issued token + QR code inline under the originating mission. */
    val activeInvite: ActiveInvite? = null,
    /** Transient banner for invite-flow errors (issue/confirm/revoke failures). */
    val inviteError: String? = null,
    /** Transient banner for any admin-flow success acknowledgement. */
    val successMessage: String? = null,
)

/** A token + URI bundle the UI displays after a successful issue.
 *  Cleared when the operator dismisses the panel or revokes the token. */
data class ActiveInvite(
    val missionId: String,
    val token: String,
    val uri: String,         // canonical InviteUri.encode(token) — what the QR encodes
    val expiresAtIso: String,
)

data class AdminDeleteError(val message: String, val refs: List<EntityRef>)

sealed interface AdminEditSheet {
    data class EditRank(val existing: Rank?) : AdminEditSheet
    data class EditChannelCategory(val existing: ChannelCategory?) : AdminEditSheet
    data class EditMessageCategory(val existing: MessageCategory?) : AdminEditSheet
    data class EditMissionType(val existing: MissionType?) : AdminEditSheet
}

// ─── Intents ─────────────────────────────────────────────────────────────────

sealed interface AdminIntent {
    data class SelectTab(val tab: AdminTab) : AdminIntent
    data class OpenEditSheet(val sheet: AdminEditSheet) : AdminIntent
    data object DismissEditSheet : AdminIntent
    data object DismissDeleteError : AdminIntent

    data class SaveRank(val id: String?, val name: String, val level: Int, val color: ColorToken) : AdminIntent
    data class DeleteRank(val id: String) : AdminIntent

    data class SaveChannelCategory(val id: String?, val name: String, val accent: ColorToken, val viewClearance: Int, val postClearance: Int) : AdminIntent
    data class DeleteChannelCategory(val id: String) : AdminIntent

    data class SaveMessageCategory(val id: String?, val name: String, val accent: ColorToken, val minClearance: Int) : AdminIntent
    data class DeleteMessageCategory(val id: String) : AdminIntent

    data class SaveMissionType(val id: String?, val name: String, val accent: ColorToken, val description: String) : AdminIntent
    data class DeleteMissionType(val id: String) : AdminIntent

    data class AssignClearance(val userId: String, val missionId: String, val rankId: String) : AdminIntent
    data class UnassignClearance(val userId: String, val missionId: String) : AdminIntent

    /** CHIEF generates a fresh invite token for the mission. */
    data class IssueInvite(val missionId: String) : AdminIntent
    /** CHIEF confirms a PENDING participant after out-of-band SAS verification. */
    data class ConfirmParticipant(val missionId: String, val userId: String) : AdminIntent
    /** CHIEF revokes an unredeemed invite token. */
    data class RevokeInvite(val token: String) : AdminIntent
    /** Dismiss the post-issuance panel without revoking. */
    data object DismissActiveInvite : AdminIntent
    /** Dismiss the inline error banner. */
    data object DismissInviteError : AdminIntent
    /** Dismiss the inline success banner. */
    data object DismissSuccess : AdminIntent
}

// ─── Root composable ─────────────────────────────────────────────────────────

@Composable
fun AdminConsoleContent(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // Tab bar
        Row(
            Modifier.fillMaxWidth()
                .background(AstraTheme.SurfaceContainerLow)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 0.dp),
        ) {
            AdminTab.entries.forEach { tab ->
                val sel = tab == state.activeTab
                Box(
                    modifier = Modifier
                        .clickable { onIntent(AdminIntent.SelectTab(tab)) }
                        .background(if (sel) AstraTheme.Primary.copy(0.12f) else Color.Transparent)
                        .border(
                            width = if (sel) 1.dp else 0.dp,
                            color = if (sel) AstraTheme.Primary.copy(0.3f) else Color.Transparent,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = if (sel) AstraTheme.Primary else Color(0xFFACABAA),
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 9.sp, letterSpacing = 1.5.sp,
                        )
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.15f)))

        // Tab content
        when (state.activeTab) {
            AdminTab.RANKS              -> RanksTab(state, onIntent)
            AdminTab.CHANNEL_CATEGORIES -> ChannelCategoriesTab(state, onIntent)
            AdminTab.MESSAGE_CATEGORIES -> MessageCategoriesTab(state, onIntent)
            AdminTab.MISSION_TYPES      -> MissionTypesTab(state, onIntent)
            AdminTab.CLEARANCE          -> ClearanceTab(state, onIntent)
        }
    }

    // Edit sheet overlay
    when (val sheet = state.editSheet) {
        is AdminEditSheet.EditRank             -> RankEditSheet(sheet.existing, onIntent)
        is AdminEditSheet.EditChannelCategory  -> ChannelCategoryEditSheet(sheet.existing, onIntent)
        is AdminEditSheet.EditMessageCategory  -> MessageCategoryEditSheet(sheet.existing, onIntent)
        is AdminEditSheet.EditMissionType      -> MissionTypeEditSheet(sheet.existing, onIntent)
        null -> {}
    }

    // Delete error dialog
    state.deleteError?.let { err ->
        DeleteErrorDialog(err) { onIntent(AdminIntent.DismissDeleteError) }
    }
}

// ─── Shared: SchemaListEditor ─────────────────────────────────────────────────

@Composable
private fun <T> SchemaListEditor(
    items: List<T>,
    getLabel: (T) -> String,
    getSubLabel: (T) -> String,
    getAccent: (T) -> ColorToken,
    isSystem: (T) -> Boolean,
    addLabel: String,
    onAdd: () -> Unit,
    onEdit: (T) -> Unit,
    onDelete: (T) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        items.forEach { item ->
            val (_, tc, _) = resolveColorTokenTriple(getAccent(item))
            val sys = isSystem(item)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .background(AstraTheme.SurfaceContainerLow)
                    .border(1.dp, AstraTheme.OutlineVariant.copy(0.1f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(tc, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            getLabel(item),
                            style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface, fontWeight = FontWeight.Bold, fontSize = 11.sp),
                        )
                        if (sys) {
                            Spacer(Modifier.width(6.dp))
                            Text("SYS", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OutlineVariant, fontSize = 8.sp, fontFamily = FontFamily.Monospace))
                        }
                    }
                    Text(
                        getSubLabel(item),
                        style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                    )
                }
                IconButton(onClick = { onEdit(item) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = AstraTheme.Primary.copy(0.7f), modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = { onDelete(item) },
                    modifier = Modifier.size(32.dp),
                    enabled = !sys,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = if (sys) AstraTheme.OutlineVariant.copy(0.3f) else AstraTheme.Error.copy(0.7f), modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RectangleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.Primary.copy(0.3f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AstraTheme.Primary),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("> $addLabel", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Primary, fontWeight = FontWeight.Bold, fontSize = 10.sp))
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ─── Tab content ─────────────────────────────────────────────────────────────

@Composable
private fun RanksTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    SchemaListEditor(
        items = state.ranks,
        getLabel = { it.name },
        getSubLabel = { "LEVEL ${it.level}" },
        getAccent = { it.color },
        isSystem = { it.isSystem },
        addLabel = "ADD RANK",
        onAdd = { onIntent(AdminIntent.OpenEditSheet(AdminEditSheet.EditRank(null))) },
        onEdit = { onIntent(AdminIntent.OpenEditSheet(AdminEditSheet.EditRank(it))) },
        onDelete = { onIntent(AdminIntent.DeleteRank(it.id)) },
    )
}

@Composable
private fun ChannelCategoriesTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    SchemaListEditor(
        items = state.channelCategories,
        getLabel = { it.name },
        getSubLabel = { "VIEW: ${it.defaultMinClearanceToView}  POST: ${it.defaultMinClearanceToPost}" },
        getAccent = { it.accent },
        isSystem = { it.isSystem },
        addLabel = "ADD CATEGORY",
        onAdd = { onIntent(AdminIntent.OpenEditSheet(AdminEditSheet.EditChannelCategory(null))) },
        onEdit = { onIntent(AdminIntent.OpenEditSheet(AdminEditSheet.EditChannelCategory(it))) },
        onDelete = { onIntent(AdminIntent.DeleteChannelCategory(it.id)) },
    )
}

@Composable
private fun MessageCategoriesTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    SchemaListEditor(
        items = state.messageCategories,
        getLabel = { it.name },
        getSubLabel = { "MIN_CLR_TO_SEND: ${it.minClearanceToSend}" },
        getAccent = { it.accent },
        isSystem = { it.isSystem },
        addLabel = "ADD MSG CATEGORY",
        onAdd = { onIntent(AdminIntent.OpenEditSheet(AdminEditSheet.EditMessageCategory(null))) },
        onEdit = { onIntent(AdminIntent.OpenEditSheet(AdminEditSheet.EditMessageCategory(it))) },
        onDelete = { onIntent(AdminIntent.DeleteMessageCategory(it.id)) },
    )
}

@Composable
private fun MissionTypesTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    SchemaListEditor(
        items = state.missionTypes,
        getLabel = { it.name },
        getSubLabel = { it.description.take(50) },
        getAccent = { it.accent },
        isSystem = { it.isSystem },
        addLabel = "ADD MISSION TYPE",
        onAdd = { onIntent(AdminIntent.OpenEditSheet(AdminEditSheet.EditMissionType(null))) },
        onEdit = { onIntent(AdminIntent.OpenEditSheet(AdminEditSheet.EditMissionType(it))) },
        onDelete = { onIntent(AdminIntent.DeleteMissionType(it.id)) },
    )
}

@Composable
private fun ClearanceTab(state: AdminUiState, onIntent: (AdminIntent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "> MISSION CLEARANCE MATRIX — ASSIGN RANKS PER OPERATOR",
            style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(12.dp))
        state.inviteError?.let { err ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AstraTheme.Error.copy(0.08f))
                    .border(1.dp, AstraTheme.Error.copy(0.4f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "> $err",
                    modifier = Modifier.weight(1f),
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Error,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                TextButton(
                    onClick = { onIntent(AdminIntent.DismissInviteError) },
                    shape = RectangleShape,
                ) {
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
            Spacer(Modifier.height(8.dp))
        }
        state.successMessage?.let { msg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AstraTheme.Tertiary.copy(0.08f))
                    .border(1.dp, AstraTheme.Tertiary.copy(0.4f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "> $msg",
                    modifier = Modifier.weight(1f),
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Tertiary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                TextButton(
                    onClick = { onIntent(AdminIntent.DismissSuccess) },
                    shape = RectangleShape,
                ) {
                    Text(
                        "DISMISS",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.Tertiary.copy(0.8f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        state.missions.forEach { mission ->
            val participantIds = mission.participantIds.ifEmpty { listOf(state.localUserId) }
            var missionExpanded by remember(mission.id) { mutableStateOf(false) }

            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .background(AstraTheme.SurfaceContainerLow)
                    .border(1.dp, AstraTheme.OutlineVariant.copy(0.1f)),
            ) {
                // Mission header row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(mission.name.uppercase(), style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface, fontWeight = FontWeight.Bold, fontSize = 11.sp))
                        Text(
                            "${participantIds.size} PARTICIPANT${if (participantIds.size != 1) "S" else ""}",
                            style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                        )
                    }
                    IconButton(onClick = { missionExpanded = !missionExpanded }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (missionExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = "Expand",
                            tint = AstraTheme.Primary.copy(0.7f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (missionExpanded) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.1f)))
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        participantIds.forEach { participantId ->
                            ParticipantClearanceRow(
                                participantId = participantId,
                                isLocalUser = participantId == state.localUserId,
                                missionId = mission.id,
                                ranks = state.ranks,
                                clearances = state.clearances,
                                onIntent = onIntent,
                            )
                        }

                        // Pending participants — operators who redeemed an invite
                        // and are awaiting CHIEF confirmation. Each row shows the
                        // SAS fingerprint that the inviter must verify out-of-band.
                        mission.pendingParticipants.forEach { pending ->
                            PendingParticipantRow(
                                pending = pending,
                                missionId = mission.id,
                                onIntent = onIntent,
                            )
                        }

                        // Single action: generate an invite. The post-issuance panel
                        // appears below for the issuing operator only (state.activeInvite).
                        InviteActionRow(
                            missionId = mission.id,
                            activeInvite = state.activeInvite?.takeIf { it.missionId == mission.id },
                            onIntent = onIntent,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ParticipantClearanceRow(
    participantId: String,
    isLocalUser: Boolean,
    missionId: String,
    ranks: List<com.explo.capstone.shared.Rank>,
    clearances: List<com.explo.capstone.shared.ClearanceAssignment>,
    onIntent: (AdminIntent) -> Unit,
) {
    val assignment = clearances.find { it.userId == participantId && it.missionId == missionId }
    val currentRank = ranks.find { it.id == assignment?.rankId }
    var rankExpanded by remember(participantId, missionId) { mutableStateOf(false) }
    // Staged selection — clicking a rank only stages it; CONFIRM commits.
    // Reset whenever the underlying server-side assignment changes.
    var pendingRankId by remember(participantId, missionId, assignment?.rankId) {
        mutableStateOf<String?>(null)
    }
    val effectiveRankId = pendingRankId ?: assignment?.rankId
    val isDirty = pendingRankId != null && pendingRankId != assignment?.rankId

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isLocalUser) AstraTheme.Primary.copy(0.04f) else Color.Transparent)
            .border(1.dp, if (isLocalUser) AstraTheme.Primary.copy(0.2f) else AstraTheme.OutlineVariant.copy(0.1f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (isLocalUser) "YOU" else participantId.take(12).uppercase(),
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = if (isLocalUser) AstraTheme.Primary else AstraTheme.OnSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    )
                    if (isLocalUser) {
                        Text("(LOCAL)", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Primary.copy(0.5f), fontSize = 8.sp, fontFamily = FontFamily.Monospace))
                    }
                }
                Text(
                    currentRank?.let { "${it.name}  LVL ${it.level}" } ?: "UNASSIGNED",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = if (currentRank != null) AstraTheme.Tertiary else Color(0xFFACABAA).copy(0.5f),
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    )
                )
            }
            IconButton(onClick = { rankExpanded = !rankExpanded }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (rankExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = AstraTheme.Primary.copy(0.6f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (rankExpanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.08f)))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ranks.forEach { rank ->
                    val sel = rank.id == effectiveRankId
                    val isStaged = isDirty && rank.id == pendingRankId
                    val (_, tc, _) = resolveColorTokenTriple(rank.color)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (sel) tc.copy(if (isStaged) 0.15f else 0.08f) else Color.Transparent)
                            .border(1.dp, if (sel) tc.copy(if (isStaged) 0.6f else 0.3f) else AstraTheme.OutlineVariant.copy(0.1f))
                            .clickable { pendingRankId = rank.id }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(tc, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${rank.name}  LVL ${rank.level}",
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = if (sel) tc else Color(0xFFACABAA),
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 10.sp,
                            )
                        )
                        if (isStaged) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                "[ PENDING ]",
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = tc,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Confirm + remove actions row. CONFIRM only enabled when the
                // staged selection differs from the server-side assignment.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            pendingRankId?.let { rid ->
                                onIntent(AdminIntent.AssignClearance(participantId, missionId, rid))
                            }
                        },
                        enabled = isDirty,
                        shape = RectangleShape,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isDirty) AstraTheme.Primary else AstraTheme.OutlineVariant.copy(0.3f),
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (isDirty) "> CONFIRM" else "> NO CHANGES",
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = if (isDirty) AstraTheme.Primary else AstraTheme.OutlineVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                    if (assignment != null) {
                        OutlinedButton(
                            onClick = { onIntent(AdminIntent.UnassignClearance(participantId, missionId)) },
                            shape = RectangleShape,
                            border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.Error.copy(0.5f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                "> REMOVE",
                                style = AstraTheme.Typography.labelSmall.copy(
                                    color = AstraTheme.Error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Pending participant row ─────────────────────────────────────────────────

@Composable
private fun PendingParticipantRow(
    pending: PendingParticipant,
    missionId: String,
    onIntent: (AdminIntent) -> Unit,
) {
    val accent = AstraTheme.Secondary  // amber — work-in-progress
    Column(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(0.05f))
            .border(1.dp, accent.copy(0.3f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (pending.callsign.isNotBlank()) pending.callsign.uppercase() else pending.userId.take(12).uppercase(),
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.OnSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    Text(
                        "[ PENDING ]",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = accent,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
                Text(
                    "FINGERPRINT  ${pending.fingerprint.ifBlank { "—" }}",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = accent.copy(0.85f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Text(
                    "VERIFY OUT-OF-BAND BEFORE CONFIRMING",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = Color(0xFFACABAA).copy(0.6f),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
            OutlinedButton(
                onClick = { onIntent(AdminIntent.ConfirmParticipant(missionId, pending.userId)) },
                shape = RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, accent),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "> CONFIRM",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

// ─── Generate-invite action + post-issuance panel ────────────────────────────

@Composable
private fun InviteActionRow(
    missionId: String,
    activeInvite: ActiveInvite?,
    onIntent: (AdminIntent) -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    if (activeInvite == null) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = { onIntent(AdminIntent.IssueInvite(missionId)) },
                shape = RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.Tertiary.copy(0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "> GENERATE INVITE",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Tertiary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
        return
    }

    // Active invite panel — token + QR + actions. Shown only on the issuing
    // operator's device (driven by state.activeInvite.missionId == this mission).
    Column(
        Modifier
            .fillMaxWidth()
            .background(AstraTheme.Tertiary.copy(0.04f))
            .border(1.dp, AstraTheme.Tertiary.copy(0.4f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "// INVITE ISSUED",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.Tertiary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .background(Color.White)
                    .padding(6.dp),
            ) {
                QrCodeImage(content = activeInvite.uri, sizeDp = 200)
            }
        }
        Text(
            "TOKEN  ${activeInvite.token}",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.OnSurface,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "EXPIRES  ${activeInvite.expiresAtIso}",
            style = AstraTheme.Typography.labelSmall.copy(
                color = Color(0xFFACABAA).copy(0.7f),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(activeInvite.token))
                },
                shape = RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.Primary.copy(0.4f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("> COPY", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Primary, fontSize = 9.sp, fontFamily = FontFamily.Monospace))
            }
            OutlinedButton(
                onClick = { onIntent(AdminIntent.RevokeInvite(activeInvite.token)) },
                shape = RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.Error.copy(0.4f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("> REVOKE", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error, fontSize = 9.sp, fontFamily = FontFamily.Monospace))
            }
            OutlinedButton(
                onClick = { onIntent(AdminIntent.DismissActiveInvite) },
                shape = RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.OutlineVariant),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("> CLOSE", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
            }
        }
    }
}

// ─── Edit sheets ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RankEditSheet(existing: Rank?, onIntent: (AdminIntent) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var levelStr by remember { mutableStateOf(existing?.level?.toString() ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: ColorToken.NEUTRAL) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var levelError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = { onIntent(AdminIntent.DismissEditSheet) }, sheetState = sheetState, containerColor = AstraTheme.SurfaceContainerLow, dragHandle = null) {
        SchemaEditSheetLayout(
            title = if (existing == null) "// ADD RANK" else "// EDIT RANK",
            onDismiss = { onIntent(AdminIntent.DismissEditSheet) },
            onSave = {
                nameError = null; levelError = null
                if (name.isBlank()) { nameError = "> NAME_REQUIRED"; return@SchemaEditSheetLayout }
                val level = levelStr.toIntOrNull()
                if (level == null || level < 1 || level > 99) { levelError = "> MUST BE 1-99"; return@SchemaEditSheetLayout }
                onIntent(AdminIntent.SaveRank(existing?.id, name.uppercase().trim(), level, color))
            },
        ) {
            EditField("NAME", name, nameError, { name = it.uppercase() })
            Spacer(Modifier.height(12.dp))
            EditField("LEVEL (1–99)", levelStr, levelError, { levelStr = it.filter { c -> c.isDigit() } })
            Spacer(Modifier.height(12.dp))
            Text("COLOR", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Spacer(Modifier.height(6.dp))
            ColorTokenPicker(selected = color, onSelect = { color = it })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelCategoryEditSheet(existing: ChannelCategory?, onIntent: (AdminIntent) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var accent by remember { mutableStateOf(existing?.accent ?: ColorToken.NEUTRAL) }
    var viewStr by remember { mutableStateOf(existing?.defaultMinClearanceToView?.toString() ?: "1") }
    var postStr by remember { mutableStateOf(existing?.defaultMinClearanceToPost?.toString() ?: "1") }
    var nameError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = { onIntent(AdminIntent.DismissEditSheet) }, sheetState = sheetState, containerColor = AstraTheme.SurfaceContainerLow, dragHandle = null) {
        SchemaEditSheetLayout(
            title = if (existing == null) "// ADD CHANNEL CATEGORY" else "// EDIT CHANNEL CATEGORY",
            onDismiss = { onIntent(AdminIntent.DismissEditSheet) },
            onSave = {
                nameError = null
                if (name.isBlank()) { nameError = "> NAME_REQUIRED"; return@SchemaEditSheetLayout }
                val view = viewStr.toIntOrNull()?.coerceIn(0, 99) ?: 1
                val post = postStr.toIntOrNull()?.coerceIn(0, 99) ?: 1
                onIntent(AdminIntent.SaveChannelCategory(existing?.id, name.uppercase().trim(), accent, view, post))
            },
        ) {
            EditField("NAME", name, nameError, { name = it.uppercase() })
            Spacer(Modifier.height(12.dp))
            Text("ACCENT COLOR", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Spacer(Modifier.height(6.dp))
            ColorTokenPicker(selected = accent, onSelect = { accent = it })
            Spacer(Modifier.height(12.dp))
            EditField("MIN CLEARANCE TO VIEW", viewStr, null, { viewStr = it.filter { c -> c.isDigit() } })
            Spacer(Modifier.height(12.dp))
            EditField("MIN CLEARANCE TO POST", postStr, null, { postStr = it.filter { c -> c.isDigit() } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageCategoryEditSheet(existing: MessageCategory?, onIntent: (AdminIntent) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var accent by remember { mutableStateOf(existing?.accent ?: ColorToken.NEUTRAL) }
    var minClrStr by remember { mutableStateOf(existing?.minClearanceToSend?.toString() ?: "1") }
    var nameError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = { onIntent(AdminIntent.DismissEditSheet) }, sheetState = sheetState, containerColor = AstraTheme.SurfaceContainerLow, dragHandle = null) {
        SchemaEditSheetLayout(
            title = if (existing == null) "// ADD MESSAGE CATEGORY" else "// EDIT MESSAGE CATEGORY",
            onDismiss = { onIntent(AdminIntent.DismissEditSheet) },
            onSave = {
                nameError = null
                if (name.isBlank()) { nameError = "> NAME_REQUIRED"; return@SchemaEditSheetLayout }
                val minClr = minClrStr.toIntOrNull()?.coerceIn(0, 99) ?: 1
                onIntent(AdminIntent.SaveMessageCategory(existing?.id, name.uppercase().trim(), accent, minClr))
            },
        ) {
            EditField("NAME", name, nameError, { name = it.uppercase() })
            Spacer(Modifier.height(12.dp))
            Text("ACCENT COLOR", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Spacer(Modifier.height(6.dp))
            ColorTokenPicker(selected = accent, onSelect = { accent = it })
            Spacer(Modifier.height(12.dp))
            EditField("MIN CLEARANCE TO SEND", minClrStr, null, { minClrStr = it.filter { c -> c.isDigit() } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionTypeEditSheet(existing: MissionType?, onIntent: (AdminIntent) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var accent by remember { mutableStateOf(existing?.accent ?: ColorToken.NEUTRAL) }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = { onIntent(AdminIntent.DismissEditSheet) }, sheetState = sheetState, containerColor = AstraTheme.SurfaceContainerLow, dragHandle = null) {
        SchemaEditSheetLayout(
            title = if (existing == null) "// ADD MISSION TYPE" else "// EDIT MISSION TYPE",
            onDismiss = { onIntent(AdminIntent.DismissEditSheet) },
            onSave = {
                nameError = null
                if (name.isBlank()) { nameError = "> NAME_REQUIRED"; return@SchemaEditSheetLayout }
                onIntent(AdminIntent.SaveMissionType(existing?.id, name.uppercase().trim(), accent, description.trim()))
            },
        ) {
            EditField("NAME", name, nameError, { name = it.uppercase() })
            Spacer(Modifier.height(12.dp))
            Text("ACCENT COLOR", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Spacer(Modifier.height(6.dp))
            ColorTokenPicker(selected = accent, onSelect = { accent = it })
            Spacer(Modifier.height(12.dp))
            EditField("DESCRIPTION", description, null, { description = it })
        }
    }
}

// ─── Shared sheet scaffold ───────────────────────────────────────────────────

@Composable
private fun SchemaEditSheetLayout(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AstraTheme.Typography.headlineSmall.copy(color = AstraTheme.Primary, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Black))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = AstraTheme.OutlineVariant)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.15f)))
        Spacer(Modifier.height(16.dp))
        content()
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.Primary, contentColor = AstraTheme.OnPrimary),
        ) {
            Text("> SAVE CHANGES", style = AstraTheme.Typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        }
    }
}

// ─── Shared helpers ───────────────────────────────────────────────────────────

@Composable
private fun EditField(label: String, value: String, error: String?, onValueChange: (String) -> Unit) {
    Text(label, style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
    Spacer(Modifier.height(4.dp))
    AstraInputField(value = value, onValueChange = onValueChange, placeholder = label)
    if (error != null) {
        Text(error, style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error, fontSize = 9.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ColorTokenPicker(selected: ColorToken, onSelect: (ColorToken) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorToken.entries.forEach { token ->
            val (_, tc, _) = resolveColorTokenTriple(token)
            val isSel = token == selected
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(tc.copy(if (isSel) 1f else 0.4f), CircleShape)
                    .border(2.dp, if (isSel) AstraTheme.OnSurface else Color.Transparent, CircleShape)
                    .clickable { onSelect(token) },
            )
        }
    }
}

@Composable
private fun DeleteErrorDialog(error: AdminDeleteError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AstraTheme.SurfaceContainerLow,
        title = {
            Text("> DELETE_BLOCKED", style = AstraTheme.Typography.headlineSmall.copy(color = AstraTheme.Error, fontSize = 14.sp, letterSpacing = 2.sp))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(error.message, style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface.copy(0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                if (error.refs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("REFERENCED BY:", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontSize = 9.sp, letterSpacing = 1.sp))
                    error.refs.take(5).forEach { ref ->
                        Text("> ${ref.type}: ${ref.name}", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error.copy(0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                    }
                    if (error.refs.size > 5) Text("> …and ${error.refs.size - 5} more", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("> DISMISS", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Primary, fontWeight = FontWeight.Bold, fontSize = 10.sp))
            }
        },
        shape = RectangleShape,
    )
}
