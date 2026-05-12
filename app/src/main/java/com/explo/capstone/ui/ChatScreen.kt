package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.explo.capstone.shared.Channel
import com.explo.capstone.shared.ColorToken
import com.explo.capstone.shared.MessageCategory

// ─── State types ─────────────────────────────────────────────────────────────

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Content(
        val channel: Channel,
        val messages: List<ChatItem>,
        val composer: ComposerState,
        val keyRotation: KeyRotationState,
        val degraded: Set<DegradedSubsystem>,
    ) : ChatUiState
    data class Error(val message: String) : ChatUiState
}

sealed interface ChatItem {
    val id: String
    val timestampMs: Long

    data class Incoming(
        override val id: String,
        override val timestampMs: Long,
        val sender: String,
        val plaintext: String,
        val decryptOk: Boolean,
    ) : ChatItem

    data class Outgoing(
        override val id: String,
        override val timestampMs: Long,
        val plaintext: String,
        val deliveryState: DeliveryState,
    ) : ChatItem

    data class System(
        override val id: String,
        override val timestampMs: Long,
        val text: String,
    ) : ChatItem

    data class IntelPacket(
        override val id: String,
        override val timestampMs: Long,
        val sender: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256Prefix: String,
        val documentId: String,
    ) : ChatItem
}

enum class DeliveryState { ENCODING, SENT, DELIVERED, FAILED }

data class ComposerState(
    val text: String = "",
    val attaching: Boolean = false,
    val selectedCategory: MessageCategory,
    val availableCategories: List<MessageCategoryOption>,
    val sendEnabled: Boolean = false,
    val clearanceWarning: String? = null,
)

data class MessageCategoryOption(
    val category: MessageCategory,
    val canSelect: Boolean,
)

data class KeyRotationState(
    val nextRotationMs: Long,
    val rotationIntervalMs: Long,
)

// ─── Intents ─────────────────────────────────────────────────────────────────

sealed interface ChatIntent {
    data object Back : ChatIntent
    data class TextChanged(val text: String) : ChatIntent
    data class CategoryChanged(val categoryId: String) : ChatIntent
    data object Send : ChatIntent
    data object AttachTap : ChatIntent
    data class Decrypt(val packet: ChatItem.IntelPacket) : ChatIntent
    data class Archive(val packet: ChatItem.IntelPacket) : ChatIntent
    data class RetrySend(val outgoingId: String) : ChatIntent
}

// ─── Root composable ─────────────────────────────────────────────────────────

@Composable
fun ChatContent(
    state: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    onInjectIncoming: (() -> Unit)? = null,
) {
    when (state) {
        is ChatUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AstraTheme.Primary, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
        is ChatUiState.Error -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(state.message, style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error, fontFamily = FontFamily.Monospace))
        }
        is ChatUiState.Content -> ChatBody(state, onIntent, onInjectIncoming)
    }
}

// ─── Chat body ────────────────────────────────────────────────────────────────

@Composable
private fun ChatBody(
    state: ChatUiState.Content,
    onIntent: (ChatIntent) -> Unit,
    onInjectIncoming: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize()) {
        if (state.degraded.isNotEmpty()) DegradedBanner(state.degraded)

        // Message stream
        val listState = rememberLazyListState()
        LaunchedEffect(state.messages.size) {
            if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.messages, key = { it.id }) { item ->
                ChatItemView(item, onIntent)
            }
        }

        // Debug inject button (only in DEBUG builds)
        if (onInjectIncoming != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AstraTheme.SurfaceContainerHighest)
                    .clickable { onInjectIncoming() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    "> [DEBUG] INJECT INCOMING MESSAGE",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Secondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                )
            }
        }

        // Composer
        ComposerSection(state.composer, onIntent)
    }
}

// ─── Session banner ───────────────────────────────────────────────────────────

@Composable
private fun SessionBanner() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(AstraTheme.SurfaceContainerLow)
            .border(width = 0.dp, color = Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Lock, null, tint = AstraTheme.Tertiary, modifier = Modifier.size(12.dp))
            Text(
                "SECURE SESSION ESTABLISHED // PROTOCOL 3.4",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Tertiary.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                )
            )
        }
    }
}

// ─── Chat item dispatcher ─────────────────────────────────────────────────────

@Composable
private fun ChatItemView(item: ChatItem, onIntent: (ChatIntent) -> Unit) {
    when (item) {
        is ChatItem.Incoming    -> IncomingBubble(item)
        is ChatItem.Outgoing    -> OutgoingBubble(item, onIntent)
        is ChatItem.System      -> SystemNotification(item)
        is ChatItem.IntelPacket -> IntelPacketCard(item, onIntent)
    }
}

// ─── Incoming bubble ─────────────────────────────────────────────────────────

@Composable
private fun IncomingBubble(item: ChatItem.Incoming) {
    Row(
        Modifier.fillMaxWidth(0.88f),
        verticalAlignment = Alignment.Top,
    ) {
        // Left accent border
        Box(Modifier.width(3.dp).height(IntrinsicSize.Max).background(AstraTheme.Primary))
        Column(
            Modifier
                .background(AstraTheme.SurfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                item.sender.uppercase(),
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            )
            Spacer(Modifier.height(4.dp))
            if (!item.decryptOk) {
                Text(
                    "[ CIPHERTEXT // INTEGRITY_FAIL ]",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Error,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                )
            } else {
                Text(
                    item.plaintext,
                    style = AstraTheme.Typography.bodyMedium.copy(
                        color = AstraTheme.OnSurface,
                        fontSize = 13.sp,
                    )
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                formatTs(item.timestampMs),
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.OutlineVariant,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            )
        }
    }
}

// ─── Outgoing bubble ─────────────────────────────────────────────────────────

@Composable
private fun OutgoingBubble(item: ChatItem.Outgoing, onIntent: (ChatIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .background(AstraTheme.SurfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                "YOU",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Tertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.plaintext,
                style = AstraTheme.Typography.bodyMedium.copy(
                    color = AstraTheme.OnSurface,
                    fontSize = 13.sp,
                )
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTs(item.timestampMs),
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.OutlineVariant,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                )
                val (statusText, statusColor) = when (item.deliveryState) {
                    DeliveryState.ENCODING  -> "ENCODING..." to AstraTheme.Secondary
                    DeliveryState.SENT      -> "SENT" to AstraTheme.Tertiary.copy(0.6f)
                    DeliveryState.DELIVERED -> "DELIVERED" to AstraTheme.Tertiary
                    DeliveryState.FAILED    -> "FAILED" to AstraTheme.Error
                }
                Text(
                    statusText,
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = statusColor,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                )
                if (item.deliveryState == DeliveryState.FAILED) {
                    Text(
                        "> RETRY",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.Secondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.clickable { onIntent(ChatIntent.RetrySend(item.id)) }
                    )
                }
            }
        }
        // Right accent border
        Box(Modifier.width(3.dp).fillMaxHeight().background(AstraTheme.Tertiary))
    }
}

// ─── System notification ──────────────────────────────────────────────────────

@Composable
private fun SystemNotification(item: ChatItem.System) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(AstraTheme.OutlineVariant.copy(0.3f)))
        Text(
            item.text,
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.Tertiary.copy(0.7f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            )
        )
        Box(Modifier.weight(1f).height(1.dp).background(AstraTheme.OutlineVariant.copy(0.3f)))
    }
}

// ─── Intel packet card ────────────────────────────────────────────────────────

@Composable
private fun IntelPacketCard(item: ChatItem.IntelPacket, onIntent: (ChatIntent) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(AstraTheme.SurfaceContainerLow)
            .border(1.dp, AstraTheme.Primary.copy(0.3f))
    ) {
        // Dotted background pattern (radial dots)
        Box(
            Modifier
                .fillMaxWidth()
                .background(AstraTheme.Primary.copy(alpha = 0.03f))
        )
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Outlined.Description,
                    null,
                    tint = AstraTheme.Primary.copy(0.6f),
                    modifier = Modifier.size(20.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        item.fileName,
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.OnSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                    )
                    Text(
                        "${formatFileSize(item.sizeBytes)} // SHA256: ${item.sha256Prefix}...",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.OutlineVariant,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    )
                }
                Text(
                    item.sender.uppercase(),
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Primary.copy(0.6f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onIntent(ChatIntent.Decrypt(item)) },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AstraTheme.Primary.copy(0.15f),
                        contentColor = AstraTheme.Primary,
                    ),
                ) {
                    Text(
                        "> DECRYPT_AND_VIEW",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.Primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    )
                }
                OutlinedButton(
                    onClick = { onIntent(ChatIntent.Archive(item)) },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.OutlineVariant.copy(0.3f)),
                ) {
                    Text(
                        "> ARCHIVE",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.OutlineVariant,
                            fontSize = 9.sp,
                        )
                    )
                }
            }
        }
    }
}

// ─── Composer ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerSection(composer: ComposerState, onIntent: (ChatIntent) -> Unit) {
    // Top divider
    Box(Modifier.fillMaxWidth().height(1.dp).background(AstraTheme.OutlineVariant.copy(0.15f)))

    if (composer.clearanceWarning != null) {
        // Read-only banner
        Box(
            Modifier
                .fillMaxWidth()
                .background(AstraTheme.Error.copy(0.08f))
                .border(1.dp, AstraTheme.Error.copy(0.2f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Lock, null, tint = AstraTheme.Error, modifier = Modifier.size(14.dp))
                Text(
                    composer.clearanceWarning,
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Error,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                )
            }
        }
        return
    }

    var showCategoryPicker by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(AstraTheme.SurfaceDim)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Selected category chip
        CategoryChip(composer.selectedCategory, onClick = { showCategoryPicker = true })

        // Input row
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Attach
            IconButton(
                onClick = { onIntent(ChatIntent.AttachTap) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.AttachFile,
                    "Attach",
                    tint = AstraTheme.Primary.copy(0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }

            // Text input
            val charCount = composer.text.length
            TextField(
                value = composer.text,
                onValueChange = { if (it.length <= 4096) onIntent(ChatIntent.TextChanged(it)) },
                placeholder = {
                    Text(
                        "> TYPE MESSAGE...",
                        style = AstraTheme.Typography.labelSmall.copy(
                            color = AstraTheme.OutlineVariant.copy(0.5f),
                            fontSize = 11.sp,
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                textStyle = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.OnSurface,
                    fontSize = 12.sp,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AstraTheme.SurfaceContainerLowest,
                    unfocusedContainerColor = AstraTheme.SurfaceContainerLowest,
                    focusedIndicatorColor = AstraTheme.Primary,
                    unfocusedIndicatorColor = AstraTheme.OutlineVariant.copy(0.3f),
                    focusedTextColor = AstraTheme.OnSurface,
                    unfocusedTextColor = AstraTheme.OnSurface,
                    cursorColor = AstraTheme.Primary,
                ),
                shape = androidx.compose.ui.graphics.RectangleShape,
                maxLines = 4,
                suffix = if (charCount > 3500) {
                    { Text("$charCount/4096", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Secondary, fontSize = 9.sp)) }
                } else null,
            )

            // Category key icon
            IconButton(
                onClick = { showCategoryPicker = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Key,
                    "Category",
                    tint = AstraTheme.Tertiary.copy(0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }

            // Send
            Button(
                onClick = { onIntent(ChatIntent.Send) },
                enabled = composer.sendEnabled,
                modifier = Modifier.size(36.dp),
                shape = androidx.compose.ui.graphics.RectangleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AstraTheme.Primary,
                    contentColor = AstraTheme.OnPrimary,
                    disabledContainerColor = AstraTheme.SurfaceContainerHighest,
                    disabledContentColor = AstraTheme.OutlineVariant,
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    "Send",
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Footer status
        Text(
            "E2E_ENCRYPTED  //  DATA_WIPE_ON_PAUSE",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.OutlineVariant.copy(0.5f),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
            )
        )
    }

    if (showCategoryPicker) {
        CategoryPickerSheet(
            options = composer.availableCategories,
            selectedId = composer.selectedCategory.id,
            onSelect = {
                onIntent(ChatIntent.CategoryChanged(it))
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false },
        )
    }
}

// ─── Category chip ────────────────────────────────────────────────────────────

@Composable
private fun CategoryChip(category: MessageCategory, onClick: () -> Unit) {
    val accentColor = resolveAccentColor(category.accent)
    Row(
        modifier = Modifier
            .background(accentColor.copy(0.1f))
            .border(1.dp, accentColor.copy(0.3f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).background(accentColor, shape = androidx.compose.foundation.shape.CircleShape))
        Text(
            category.name.uppercase(),
            style = AstraTheme.Typography.labelSmall.copy(
                color = accentColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        )
        Icon(Icons.Outlined.ArrowDropDown, null, tint = accentColor, modifier = Modifier.size(12.dp))
    }
}

// ─── Category picker sheet ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    options: List<MessageCategoryOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AstraTheme.SurfaceContainerLow,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "─── SELECT CATEGORY",
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.OutlineVariant,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                ),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            options.forEach { option ->
                val accent = resolveAccentColor(option.category.accent)
                val isSelected = option.category.id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) accent.copy(0.1f) else Color.Transparent)
                        .border(1.dp, if (isSelected) accent.copy(0.4f) else AstraTheme.OutlineVariant.copy(0.1f))
                        .then(if (option.canSelect) Modifier.clickable { onSelect(option.category.id) } else Modifier)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(8.dp).background(accent.copy(if (option.canSelect) 1f else 0.3f), shape = androidx.compose.foundation.shape.CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.category.name.uppercase(),
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = if (option.canSelect) accent else AstraTheme.OutlineVariant.copy(0.4f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                            )
                        )
                        Text(
                            "MIN LVL ${option.category.minClearanceToSend}",
                            style = AstraTheme.Typography.labelSmall.copy(
                                color = AstraTheme.OutlineVariant.copy(if (option.canSelect) 0.6f else 0.3f),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        )
                    }
                    if (!option.canSelect) {
                        Icon(Icons.Outlined.Lock, null, tint = AstraTheme.OutlineVariant.copy(0.4f), modifier = Modifier.size(14.dp))
                    }
                    if (isSelected) {
                        Icon(Icons.Outlined.Check, null, tint = accent, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─── Decrypt viewer dialog ────────────────────────────────────────────────────

@Composable
fun DecryptViewerDialog(fileName: String, content: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AstraTheme.SurfaceContainerLow,
        title = {
            Text(
                fileName.uppercase(),
                style = AstraTheme.Typography.labelSmall.copy(
                    color = AstraTheme.Primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            )
        },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(AstraTheme.SurfaceContainerLowest)
                    .border(1.dp, AstraTheme.OutlineVariant.copy(0.2f))
                    .padding(12.dp)
            ) {
                Text(
                    content,
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.OnSurface,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "> CLOSE",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Primary,
                        fontWeight = FontWeight.Bold,
                    )
                )
            }
        },
    )
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun formatTs(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024        -> "${bytes}B"
    bytes < 1_048_576L  -> "${"%.1f".format(bytes / 1024.0)}KB"
    else                -> "${"%.1f".format(bytes / 1_048_576.0)}MB"
}

fun resolveAccentColor(token: ColorToken): Color = when (token) {
    ColorToken.PRIMARY   -> AstraTheme.Primary
    ColorToken.TERTIARY  -> AstraTheme.Tertiary
    ColorToken.SECONDARY -> AstraTheme.Secondary
    ColorToken.ERROR     -> AstraTheme.Error
    ColorToken.NEUTRAL   -> AstraTheme.OnSurface.copy(0.6f)
}
