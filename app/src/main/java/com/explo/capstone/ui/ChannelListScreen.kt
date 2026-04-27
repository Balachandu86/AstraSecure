package com.explo.capstone.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
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

data class ChannelSection(val category: ChannelCategory, val channels: List<ChannelRow>)
data class ChannelRow(
    val channel: Channel,
    val canView: Boolean,
    val canPost: Boolean,
    val requiredRankName: String,  // name of lowest rank that meets minClearanceToView
)

sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data class Content(
        val mission: Mission,
        val missionType: MissionType?,
        val userClearance: Rank?,
        val sections: List<ChannelSection>,
        val categories: List<ChannelCategory>,
        val showNewChannelSheet: Boolean = false,
    ) : ChannelListUiState
    data class MissionNotFound(val id: String) : ChannelListUiState
}

sealed interface ChannelListIntent {
    data object Back : ChannelListIntent
    data class Open(val channelId: String) : ChannelListIntent
    data object ShowNewChannelSheet : ChannelListIntent
    data object DismissNewChannelSheet : ChannelListIntent
    data class CreateChannel(val name: String, val description: String, val categoryId: String) : ChannelListIntent
}

fun resolveColorTokenTriple(token: ColorToken): Triple<Color, Color, Color> = when (token) {
    ColorToken.TERTIARY  -> Triple(AstraTheme.SurfaceContainerHighest, AstraTheme.Tertiary, AstraTheme.Tertiary.copy(alpha = 0.2f))
    ColorToken.NEUTRAL   -> Triple(AstraTheme.SurfaceContainerHighest, Color(0xFFACABAA), AstraTheme.OutlineVariant.copy(alpha = 0.4f))
    ColorToken.ERROR     -> Triple(Color(0xFF7F2927), Color(0xFFFF9993), AstraTheme.Error.copy(alpha = 0.4f))
    ColorToken.PRIMARY   -> Triple(AstraTheme.SurfaceContainerHighest, AstraTheme.Primary, AstraTheme.Primary.copy(alpha = 0.2f))
    ColorToken.SECONDARY -> Triple(AstraTheme.SurfaceContainerHighest, AstraTheme.Secondary, AstraTheme.Secondary.copy(alpha = 0.2f))
}

@Composable
fun ChannelListContent(state: ChannelListUiState, onIntent: (ChannelListIntent) -> Unit) {
    when (state) {
        is ChannelListUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AstraTheme.Primary, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text("> LOADING CHANNELS...", style = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.Primary.copy(0.6f), fontFamily = FontFamily.Monospace))
            }
        }
        is ChannelListUiState.MissionNotFound -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("> MISSION ${state.id} NOT FOUND", style = AstraTheme.Typography.headlineSmall.copy(color = AstraTheme.Error, fontSize = 16.sp))
        }
        is ChannelListUiState.Content -> ChannelListBody(state, onIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelListBody(state: ChannelListUiState.Content, onIntent: (ChannelListIntent) -> Unit) {
    val m = state.mission
    val statusColor = when (m.status) {
        MissionStatus.ACTIVE      -> AstraTheme.Tertiary
        MissionStatus.STANDBY     -> AstraTheme.Secondary
        MissionStatus.COMPROMISED -> AstraTheme.Error
        MissionStatus.ARCHIVED    -> Color(0xFFACABAA)
    }
    val pulse = rememberInfiniteTransition("pulse")
    val pa by pulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse), "pa")

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            // Header
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(statusColor))
                Column(Modifier.weight(1f).background(AstraTheme.SurfaceContainerLow).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name.uppercase(), style = AstraTheme.Typography.headlineSmall.copy(color = AstraTheme.OnSurface, fontSize = 16.sp, letterSpacing = 1.sp), modifier = Modifier.weight(1f))
                        state.missionType?.let { t ->
                            val (bg, tc, bc) = resolveColorTokenTriple(t.accent)
                            Box(Modifier.background(bg).border(1.dp, bc).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(t.name, style = AstraTheme.Typography.labelSmall.copy(color = tc, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 9.sp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).background(statusColor.copy(alpha = pa), CircleShape))
                            Text(m.status.name, style = AstraTheme.Typography.labelSmall.copy(color = statusColor, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                        }
                        m.phase?.let { Text("// $it", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), fontSize = 10.sp, fontFamily = FontFamily.Monospace)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Shield, null, tint = AstraTheme.Primary.copy(0.5f), modifier = Modifier.size(14.dp))
                        Text(
                            if (state.userClearance != null) "YOUR RANK: ${state.userClearance.name} (LVL ${state.userClearance.level})"
                            else "NO CLEARANCE ASSIGNED",
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = if (state.userClearance != null) AstraTheme.Primary else AstraTheme.Secondary,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Box(Modifier.width(28.dp).height(1.dp).background(AstraTheme.OutlineVariant))
                Spacer(Modifier.width(8.dp))
                Text("SELECT A CHANNEL TO ACCESS SECURE COMMS", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA), letterSpacing = 2.sp, fontWeight = FontWeight.Bold, fontSize = 9.sp))
            }

            state.sections.forEach { sec ->
                val (_, accentText, _) = resolveColorTokenTriple(sec.category.accent)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Box(Modifier.width(3.dp).height(16.dp).background(accentText))
                        Spacer(Modifier.width(8.dp))
                        Text(sec.category.name, style = AstraTheme.Typography.labelSmall.copy(color = accentText, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 10.sp), modifier = Modifier.weight(1f))
                        Text("${sec.channels.size}", style = AstraTheme.Typography.labelSmall.copy(color = accentText.copy(0.5f), fontSize = 10.sp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sec.channels.forEach { row ->
                            var showTip by remember { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (row.canView) AstraTheme.SurfaceContainerLow else AstraTheme.SurfaceContainerLow.copy(0.6f))
                                    .border(1.dp, AstraTheme.OutlineVariant.copy(0.1f))
                                    .clickable { if (row.canView) onIntent(ChannelListIntent.Open(row.channel.id)) else showTip = !showTip }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (row.canView) Icons.Outlined.Tag else Icons.Filled.Lock,
                                    null,
                                    tint = if (row.canView) AstraTheme.Primary.copy(0.6f) else AstraTheme.Secondary.copy(0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(row.channel.name.uppercase(), style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface.copy(if (row.canView) 1f else 0.5f), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp))
                                    Text(row.channel.description, style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(if (row.canView) 0.5f else 0.3f), fontSize = 9.sp), maxLines = 1)
                                    if (showTip && !row.canView) {
                                        Text(
                                            "> REQUIRES ${row.requiredRankName} OR HIGHER",
                                            style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Secondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                                if (row.canView) Icon(Icons.Outlined.ChevronRight, null, tint = AstraTheme.OutlineVariant, modifier = Modifier.size(16.dp))
                                else Text("LOCKED", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Secondary.copy(0.6f), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                "> ${state.sections.sumOf { it.channels.size }} CHANNELS // ${state.sections.sumOf { it.channels.count { r -> r.canView } }} ACCESSIBLE",
                style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(80.dp)) // room for FAB
        }

        // "+ New Intel" FAB
        FloatingActionButton(
            onClick = { onIntent(ChannelListIntent.ShowNewChannelSheet) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = AstraTheme.Primary,
            contentColor = AstraTheme.OnPrimary,
            shape = RectangleShape,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New Intel", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("NEW INTEL", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp))
            }
        }
    }

    // New Channel bottom sheet
    if (state.showNewChannelSheet) {
        NewChannelSheet(
            categories = state.categories,
            onDismiss = { onIntent(ChannelListIntent.DismissNewChannelSheet) },
            onCreate = { name, desc, catId -> onIntent(ChannelListIntent.CreateChannel(name, desc, catId)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChannelSheet(
    categories: List<ChannelCategory>,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, categoryId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf(categories.firstOrNull()?.id ?: "") }
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
                Text("// NEW INTEL CHANNEL", style = AstraTheme.Typography.headlineSmall.copy(color = AstraTheme.Primary, fontSize = 14.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Black))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = AstraTheme.OutlineVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.15f)))
            Spacer(Modifier.height(20.dp))

            Text("CHANNEL NAME", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Spacer(Modifier.height(4.dp))
            AstraInputField(
                value = name,
                onValueChange = { name = it.uppercase(); nameError = null },
                placeholder = "CHANNEL_ALPHA",
            )
            if (nameError != null) {
                Text(nameError!!, style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error, fontSize = 9.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(16.dp))

            Text("DESCRIPTION", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Spacer(Modifier.height(4.dp))
            AstraInputField(
                value = description,
                onValueChange = { description = it },
                placeholder = "Brief intel summary",
            )
            Spacer(Modifier.height(16.dp))

            Text("CATEGORY", style = AstraTheme.Typography.labelSmall.copy(color = Color(0xFFACABAA).copy(0.7f), fontSize = 9.sp, letterSpacing = 1.sp))
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                categories.forEach { cat ->
                    val sel = cat.id == selectedCategoryId
                    val (_, tc, bc) = resolveColorTokenTriple(cat.accent)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (sel) tc.copy(0.1f) else Color.Transparent)
                            .border(1.dp, if (sel) bc else AstraTheme.OutlineVariant.copy(0.15f))
                            .clickable { selectedCategoryId = cat.id }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(if (sel) tc else AstraTheme.OutlineVariant.copy(0.4f), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(cat.name, style = AstraTheme.Typography.labelSmall.copy(color = if (sel) tc else Color(0xFFACABAA), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 10.sp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (name.isBlank()) { nameError = "> CHANNEL_NAME_REQUIRED"; return@Button }
                    if (!name.matches(Regex("^[A-Z][A-Z0-9_ ]{0,29}$"))) { nameError = "> LETTERS, DIGITS, UNDERSCORE, SPACE ONLY"; return@Button }
                    onCreate(name.trim(), description.trim(), selectedCategoryId)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.Primary, contentColor = AstraTheme.OnPrimary),
            ) {
                Text("> CREATE_CHANNEL", style = AstraTheme.Typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
            }
        }
    }
}
