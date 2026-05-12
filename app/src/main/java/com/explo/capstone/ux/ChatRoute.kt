package com.explo.capstone.ux

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.BuildConfig
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.DecryptedIncomingMessage
import com.explo.capstone.shared.Severity
import com.explo.capstone.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Owner: Ismail Alam
 * Responsible for: ChatViewModel driving the secure message stream and send pipeline.
 */
class ChatViewModel(
    private val channelId: String,
    private val missionId: String,
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    // Mutable chat item list — owned by this VM, not derived from store
    private val chatItems = mutableListOf<ChatItem>()

    private var missionKeyAlias = ""
    private var callsign = "OPERATOR"
    // UUID from IdentityManager — used as the Signal protocol address so the server
    // can route messages correctly (the server identifies users by their JWT UUID, not callsign).
    private var userId = ""
    private var userClearanceLevel = 0

    init {
        viewModelScope.launch { initializeChat() }
    }

    // ─── Callsign resolution ──────────────────────────────────────────────────

    private fun resolveCallsign(senderId: String): String =
        (container.userDisplayNames[senderId] ?: senderId.take(8)).uppercase()

    // ─── Initialization ───────────────────────────────────────────────────────

    private suspend fun initializeChat() {
        val channel = container.store.channels.value.find { it.id == channelId }
            ?: run { _state.value = ChatUiState.Error("> CHANNEL_NOT_FOUND // $channelId"); return }

        val mission = container.store.missions.value.find { it.id == missionId }
            ?: run { _state.value = ChatUiState.Error("> MISSION_NOT_FOUND // $missionId"); return }

        missionKeyAlias = mission.missionKeyAlias
        val degraded = mutableSetOf<DegradedSubsystem>()

        val identity = runCatching { container.identityManager.getUserIdentity() }.getOrNull()
        if (identity == null) {
            _state.value = ChatUiState.Error("> IDENTITY_NOT_PROVISIONED // COMPLETE SETUP FIRST")
            return
        }
        callsign = identity.displayName.uppercase()
        userId = identity.id
        userClearanceLevel = container.clearanceRepository.clearanceFor(userId, missionId).value?.level ?: 0

        // Register this device as a channel member so others can send us messages
        runCatching { container.serverClient.joinChannel(channelId) }
            .onFailure { container.securityEventLog.emit(Severity.WARN, "Chat", "JOIN_CHANNEL_FAILED // $channelId") }

        // Restore persisted messages for this channel (plaintext survived in snapshot)
        container.store.messages.value
            .filter { it.channelId == channelId && it.plaintextContent.isNotEmpty() }
            .sortedBy { it.timestampMs }
            .forEach { msg ->
                if (msg.senderId == userId) {
                    chatItems.add(ChatItem.Outgoing(msg.id, msg.timestampMs, msg.plaintextContent, DeliveryState.SENT))
                } else {
                    chatItems.add(ChatItem.Incoming(msg.id, msg.timestampMs, resolveCallsign(msg.senderId), msg.plaintextContent, true))
                }
            }

        // Observe incoming decrypted messages from the server WebSocket
        viewModelScope.launch {
            container.incomingDecrypted
                .collect { incoming: DecryptedIncomingMessage ->
                    if (incoming.channelId != channelId) return@collect
                    val text = runCatching { incoming.plaintextBytes.decodeToString() }
                        .getOrElse { "[ BINARY // ${incoming.plaintextBytes.size}B ]" }
                    chatItems.add(
                        ChatItem.Incoming(
                            id = incoming.messageId,
                            timestampMs = incoming.timestampMs,
                            sender = resolveCallsign(incoming.senderId),
                            plaintext = text,
                            decryptOk = true,
                        )
                    )
                    updateChatItems()
                }
        }

        val categories = container.messageCategoryRepository.categories.value
        val defaultCat = categories.filter { it.minClearanceToSend <= userClearanceLevel }
            .minByOrNull { it.minClearanceToSend }
            ?: categories.firstOrNull()
            ?: run { _state.value = ChatUiState.Error("> NO_MESSAGE_CATEGORIES"); return }

        val canPost = userClearanceLevel >= channel.minClearanceToPost
        val clearanceWarning = if (!canPost) {
            val reqRank = container.store.ranks.value
                .filter { it.level >= channel.minClearanceToPost }
                .minByOrNull { it.level }
            "> CHANNEL READ-ONLY // REQUIRES ${reqRank?.name ?: "LVL_${channel.minClearanceToPost}"} (LVL ${channel.minClearanceToPost})"
        } else null

        _state.value = ChatUiState.Content(
            channel = channel,
            messages = chatItems.toList(),
            composer = ComposerState(
                selectedCategory = defaultCat,
                availableCategories = categories.map { MessageCategoryOption(it, it.minClearanceToSend <= userClearanceLevel) },
                sendEnabled = false,
                clearanceWarning = clearanceWarning,
            ),
            keyRotation = KeyRotationState(0L, 0L),
            degraded = degraded,
        )

        addSystemItem("SECURE SESSION ESTABLISHED // SIGNAL PROTOCOL")
        container.securityEventLog.emit(Severity.INFO, "Chat", "SESSION_OPENED // $channelId")
    }

    // ─── Intent handler ───────────────────────────────────────────────────────

    fun handle(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.Back            -> {}
            is ChatIntent.AttachTap       -> {}
            is ChatIntent.TextChanged     -> updateText(intent.text)
            is ChatIntent.CategoryChanged -> updateCategory(intent.categoryId)
            is ChatIntent.Send            -> sendMessage()
            is ChatIntent.RetrySend       -> retrySend(intent.outgoingId)
            is ChatIntent.Decrypt         -> decryptPacket(intent.packet)
            is ChatIntent.Archive         -> archivePacket(intent.packet)
        }
    }

    // ─── Send pipeline ────────────────────────────────────────────────────────

    private fun sendMessage() {
        val content = _state.value as? ChatUiState.Content ?: return
        val text = content.composer.text.trim()
        if (text.isEmpty()) return

        val outgoingId = "out-${System.currentTimeMillis()}"
        chatItems.add(ChatItem.Outgoing(outgoingId, System.currentTimeMillis(), text, DeliveryState.ENCODING))

        // Wipe composer text immediately before encrypt call
        _state.value = content.copy(
            messages = chatItems.toList(),
            composer = content.composer.copy(text = "", sendEnabled = false),
        )

        viewModelScope.launch {
            val result = container.messageRepository.send(
                channelId = channelId,
                missionKeyAlias = missionKeyAlias,
                senderId = userId,
                plaintext = text.encodeToByteArray(),
                categoryId = content.composer.selectedCategory.id,
            )
            val deliveryState = if (result.isSuccess) DeliveryState.SENT else DeliveryState.FAILED
            updateOutgoing(outgoingId, deliveryState)
            if (result.isFailure) {
                container.securityEventLog.emit(Severity.WARN, "Chat", "SEND_FAILED // ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun retrySend(outgoingId: String) {
        val content = _state.value as? ChatUiState.Content ?: return
        val idx = chatItems.indexOfFirst { it.id == outgoingId }
        val item = chatItems.getOrNull(idx) as? ChatItem.Outgoing ?: return
        chatItems[idx] = item.copy(deliveryState = DeliveryState.ENCODING)
        updateChatItems()

        viewModelScope.launch {
            val result = container.messageRepository.send(
                channelId = channelId,
                missionKeyAlias = missionKeyAlias,
                senderId = userId,
                plaintext = item.plaintext.encodeToByteArray(),
                categoryId = content.composer.selectedCategory.id,
            )
            val deliveryState = if (result.isSuccess) DeliveryState.SENT else DeliveryState.FAILED
            updateOutgoing(outgoingId, deliveryState)
        }
    }

    private fun updateOutgoing(id: String, state: DeliveryState) {
        val idx = chatItems.indexOfFirst { it.id == id }
        val item = chatItems.getOrNull(idx) as? ChatItem.Outgoing ?: return
        chatItems[idx] = item.copy(deliveryState = state)
        updateChatItems()
    }

    // ─── Incoming inject (debug only) ─────────────────────────────────────────

    fun injectIncoming() {
        // Injects a plaintext item directly — tests chat UI rendering without
        // going through Signal crypto (missions from server have no AES keystore key).
        val fakeText = "[ DEBUG_INJECT ] REMOTE_SIGNAL_${System.currentTimeMillis() % 10_000}"
        chatItems.add(
            ChatItem.Incoming(
                id = "in-${System.currentTimeMillis()}",
                timestampMs = System.currentTimeMillis(),
                sender = "REMOTE_AGENT",
                plaintext = fakeText,
                decryptOk = true,
            )
        )
        updateChatItems()
    }

    // ─── Attach document ──────────────────────────────────────────────────────

    fun attachDocument(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val cr = context.contentResolver
                val fileName = cr.query(uri, null, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    if (col >= 0) cursor.getString(col) else "file.bin"
                } ?: "file.bin"

                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot read attachment")

                val doc = container.identityManager.storeDocument(missionId, fileName, bytes)
                val sha = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                    .take(12)

                val item = ChatItem.IntelPacket(
                    id = doc.id,
                    timestampMs = System.currentTimeMillis(),
                    sender = callsign,
                    fileName = doc.fileName,
                    sizeBytes = bytes.size.toLong(),
                    sha256Prefix = sha,
                    documentId = doc.id,
                )
                withContext(Dispatchers.Main) {
                    chatItems.add(item)
                    updateChatItems()
                }
                container.securityEventLog.emit(Severity.INFO, "Chat", "INTEL_ATTACHED // ${doc.fileName}")
            }.onFailure {
                container.securityEventLog.emit(Severity.WARN, "Chat", "ATTACH_FAILED // ${it.message}")
            }
        }
    }

    // ─── Decrypt packet ───────────────────────────────────────────────────────

    private fun decryptPacket(packet: ChatItem.IntelPacket) {
        // Returns decrypted bytes via a shared flow consumed by the route
        viewModelScope.launch {
            runCatching {
                val bytes = container.identityManager.retrieveDocument(packet.documentId)
                _decryptedDoc.value = DecryptedDocEvent(packet.fileName, bytes)
                container.securityEventLog.emit(Severity.INFO, "Chat", "DOC_DECRYPTED // ${packet.documentId}")
            }.onFailure {
                container.securityEventLog.emit(Severity.WARN, "Chat", "DOC_DECRYPT_FAILED // ${it.message}")
            }
        }
    }

    private fun archivePacket(packet: ChatItem.IntelPacket) {
        container.securityEventLog.emit(Severity.INFO, "Chat", "INTEL_ARCHIVED // ${packet.documentId}")
    }

    // ─── Decrypted document event ─────────────────────────────────────────────

    data class DecryptedDocEvent(val fileName: String, val bytes: ByteArray)

    private val _decryptedDoc = MutableStateFlow<DecryptedDocEvent?>(null)
    val decryptedDoc: StateFlow<DecryptedDocEvent?> = _decryptedDoc.asStateFlow()

    fun clearDecryptedDoc() { _decryptedDoc.value = null }

    // ─── State helpers ────────────────────────────────────────────────────────

    private fun updateText(text: String) {
        val content = _state.value as? ChatUiState.Content ?: return
        val canPost = content.composer.clearanceWarning == null
        _state.value = content.copy(
            composer = content.composer.copy(text = text, sendEnabled = canPost && text.isNotEmpty())
        )
    }

    private fun updateCategory(categoryId: String) {
        val content = _state.value as? ChatUiState.Content ?: return
        val cat = content.composer.availableCategories.find { it.category.id == categoryId }?.category ?: return
        _state.value = content.copy(composer = content.composer.copy(selectedCategory = cat))
    }

    private fun addSystemItem(text: String) {
        chatItems.add(ChatItem.System("sys-${System.currentTimeMillis()}", System.currentTimeMillis(), text))
        updateChatItems()
    }

    private fun updateChatItems() {
        val content = _state.value as? ChatUiState.Content ?: return
        _state.value = content.copy(messages = chatItems.toList())
    }

    // ─── Factory ──────────────────────────────────────────────────────────────

    class Factory(
        private val channelId: String,
        private val missionId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(channelId, missionId, container) as T
    }
}

// ─── Route ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute(
    channelId: String,
    missionId: String,
    container: AppContainer,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    onBack: () -> Unit,
    onProfileClick: (() -> Unit)? = null,
) {
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(channelId, missionId, container))
    val state by vm.state.collectAsStateWithLifecycle()
    val decryptedDoc by vm.decryptedDoc.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    // File picker launcher
    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.attachDocument(context, it) }
    }

    // Show decrypt viewer dialog when a document is decrypted
    decryptedDoc?.let { event ->
        val textContent = runCatching { event.bytes.decodeToString() }.getOrElse { "<binary file — ${event.bytes.size} bytes>" }
        DecryptViewerDialog(
            fileName = event.fileName,
            content = textContent,
            onDismiss = { vm.clearDecryptedDoc() },
        )
    }

    AstraAppShell(
        selectedTab = selectedTab,
        onTabSelect = onTabSelect,
        topBarContent = {
            val content = state as? ChatUiState.Content
            AstraTopBar(
                titleOverride = content?.channel?.name?.uppercase() ?: "SECURE CHAT",
                callsign = "SIGNAL E2E",
                clearanceLabel = "DOUBLE_RATCHET",
                onProfileClick = onProfileClick,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                            tint = AstraTheme.Primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    coroutineScope.launch {
                        isRefreshing = true
                        container.syncFromServer()
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                ChatContent(
                    state = state,
                    onIntent = { intent ->
                        when (intent) {
                            is ChatIntent.Back    -> onBack()
                            is ChatIntent.AttachTap -> attachLauncher.launch("*/*")
                            else                  -> vm.handle(intent)
                        }
                    },
                    onInjectIncoming = if (BuildConfig.DEBUG) ({ vm.injectIncoming() }) else null,
                )
            }
        }
    }
}
