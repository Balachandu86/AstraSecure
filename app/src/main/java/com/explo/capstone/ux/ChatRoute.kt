package com.explo.capstone.ux

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.explo.capstone.shared.Severity
import com.explo.capstone.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    companion object {
        private const val ROTATION_INTERVAL_MS = 5 * 60 * 1000L
    }

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    // Mutable chat item list — owned by this VM, not derived from store
    private val chatItems = mutableListOf<ChatItem>()

    private var nextRotationMs = System.currentTimeMillis() + ROTATION_INTERVAL_MS
    private var missionKeyAlias = ""
    private var callsign = "OPERATOR"
    private var userClearanceLevel = 0

    init {
        viewModelScope.launch { initializeChat() }
        viewModelScope.launch { tickRotationTimer() }
    }

    // ─── Initialization ───────────────────────────────────────────────────────

    private suspend fun initializeChat() {
        val channel = container.store.channels.value.find { it.id == channelId }
            ?: run { _state.value = ChatUiState.Error("> CHANNEL_NOT_FOUND // $channelId"); return }

        val mission = container.store.missions.value.find { it.id == missionId }
            ?: run { _state.value = ChatUiState.Error("> MISSION_NOT_FOUND // $missionId"); return }

        missionKeyAlias = mission.missionKeyAlias
        val degraded = mutableSetOf<DegradedSubsystem>()

        runCatching { container.cryptoEngine.generateMissionKey(missionKeyAlias) }
            .onFailure { degraded.add(DegradedSubsystem.CRYPTO) }

        callsign = runCatching { container.identityManager.getUserIdentity()?.displayName?.uppercase() }
            .getOrElse { null } ?: "OPERATOR"
        userClearanceLevel = container.clearanceRepository.clearanceFor("user_local", missionId).value?.level ?: 0

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
            keyRotation = KeyRotationState(nextRotationMs, ROTATION_INTERVAL_MS),
            degraded = degraded,
        )

        addSystemItem("SECURE SESSION ESTABLISHED // PROTOCOL 3.4")
        container.securityEventLog.emit(Severity.INFO, "Chat", "SESSION_OPENED // $channelId")
    }

    // ─── Key rotation timer ───────────────────────────────────────────────────

    private suspend fun tickRotationTimer() {
        while (true) {
            delay(1000)
            val content = _state.value as? ChatUiState.Content ?: continue
            val now = System.currentTimeMillis()

            if (now >= nextRotationMs) {
                val degraded = content.degraded.toMutableSet()
                runCatching {
                    val newAlias = container.cryptoEngine.rotateMissionKey(missionKeyAlias)
                    missionKeyAlias = newAlias
                    container.store.updateMissions { list ->
                        list.map { if (it.id == missionId) it.copy(missionKeyAlias = newAlias) else it }
                    }
                    degraded.remove(DegradedSubsystem.CRYPTO)
                    container.securityEventLog.emit(Severity.INFO, "Chat", "KEY_ROTATED // $missionKeyAlias")
                }.onFailure {
                    degraded.add(DegradedSubsystem.CRYPTO)
                    container.securityEventLog.emit(Severity.WARN, "Chat", "KEY_ROTATE_FAILED")
                }

                nextRotationMs = now + ROTATION_INTERVAL_MS
                addSystemItem("CHANNEL KEY ROTATED // NEXT ROTATION IN 05:00")
                _state.value = content.copy(
                    messages = chatItems.toList(),
                    keyRotation = KeyRotationState(nextRotationMs, ROTATION_INTERVAL_MS),
                    degraded = degraded,
                )
            } else {
                _state.value = content.copy(
                    keyRotation = KeyRotationState(nextRotationMs, ROTATION_INTERVAL_MS),
                )
            }
        }
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
                senderId = callsign,
                plaintext = text.encodeToByteArray(),
                categoryId = content.composer.selectedCategory.id,
            )
            val deliveryState = if (result.isSuccess) DeliveryState.SENT else DeliveryState.FAILED
            updateOutgoing(outgoingId, deliveryState)
            if (deliveryState == DeliveryState.SENT) {
                delay(200)
                updateOutgoing(outgoingId, DeliveryState.DELIVERED)
            }
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
                senderId = callsign,
                plaintext = item.plaintext.encodeToByteArray(),
                categoryId = content.composer.selectedCategory.id,
            )
            val deliveryState = if (result.isSuccess) DeliveryState.SENT else DeliveryState.FAILED
            updateOutgoing(outgoingId, deliveryState)
            if (deliveryState == DeliveryState.SENT) {
                delay(200)
                updateOutgoing(outgoingId, DeliveryState.DELIVERED)
            }
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
        viewModelScope.launch {
            val fakeText = "REMOTE_SIGNAL_${System.currentTimeMillis() % 10_000}"
            var plaintext = "[ CIPHERTEXT // INTEGRITY_FAIL ]"
            var decryptOk = false

            runCatching {
                val ct = container.cryptoEngine.encryptMessage(missionKeyAlias, fakeText.encodeToByteArray())
                val pt = container.cryptoEngine.decryptMessage(missionKeyAlias, ct)
                plaintext = pt.decodeToString()
                decryptOk = true
            }

            chatItems.add(
                ChatItem.Incoming(
                    id = "in-${System.currentTimeMillis()}",
                    timestampMs = System.currentTimeMillis(),
                    sender = "REMOTE_AGENT",
                    plaintext = plaintext,
                    decryptOk = decryptOk,
                )
            )
            updateChatItems()
        }
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

@Composable
fun ChatRoute(
    channelId: String,
    missionId: String,
    container: AppContainer,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(channelId, missionId, container))
    val state by vm.state.collectAsStateWithLifecycle()
    val decryptedDoc by vm.decryptedDoc.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            val remaining = (content?.keyRotation?.nextRotationMs ?: 0L) - System.currentTimeMillis()
            val mins = (remaining / 60_000L).coerceAtLeast(0L)
            val secs = ((remaining % 60_000L) / 1_000L).coerceAtLeast(0L)
            AstraTopBar(
                titleOverride = content?.channel?.name?.uppercase() ?: "SECURE CHAT",
                callsign = "KEY: %02d:%02d".format(mins, secs),
                clearanceLabel = "E2E_ENCRYPTED",
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
