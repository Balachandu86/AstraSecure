package com.explo.capstone.ux

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.explo.capstone.crypto.CryptoEngine
import com.explo.capstone.identity.IdentityManager
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.data.InMemoryStore
import com.explo.capstone.shared.data.entity.DocumentRepository
import com.explo.capstone.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ─── Encode / hash / AES tools ────────────────────────────────────────────────

class ToolsViewModel(private val cryptoEngine: CryptoEngine) : ViewModel() {

    private val _state = MutableStateFlow(ToolsState())
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    fun updateState(newState: ToolsState) {
        _state.value = newState
    }

    fun execute() {
        val s = _state.value
        if (s.input.isBlank()) { _state.value = s.copy(output = "> ERROR: EMPTY_INPUT", outputError = true); return }
        _state.value = when {
            s.algo == Algorithm.AES_256_GCM -> executeAes(s)
            else -> s // non-AES handled inline by ToolsContent via updateState
        }
    }

    fun setOutput(newState: ToolsState) { _state.value = newState }

    fun wipe() {
        _state.value = _state.value.copy(input = "", output = "", key = "", outputError = false)
    }

    private fun executeAes(s: ToolsState): ToolsState {
        if (s.key.isBlank()) return s.copy(output = "> ERROR: KEY_REQUIRED_FOR_AES", outputError = true)
        return try {
            val result = if (s.direction == Direction.ENCODE) {
                val encrypted = cryptoEngine.encryptWithPassword(s.key, s.input.toByteArray())
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            } else {
                val raw = Base64.decode(s.input.trim(), Base64.NO_WRAP)
                String(cryptoEngine.decryptWithPassword(s.key, raw))
            }
            s.copy(output = result, outputError = false)
        } catch (e: Exception) {
            s.copy(output = "> ERROR: ${e.message?.uppercase()?.take(60) ?: "AES_FAILED"}", outputError = true)
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ToolsViewModel(container.cryptoEngine) as T
    }
}

// ─── Vault ────────────────────────────────────────────────────────────────────

class VaultViewModel(
    private val identityManager: IdentityManager,
    private val store: InMemoryStore,
    private val documentRepository: DocumentRepository,
) : ViewModel() {

    companion object {
        const val MAX_VAULT_FILE_MB = 25
        val MAX_VAULT_FILE_BYTES = MAX_VAULT_FILE_MB * 1024 * 1024
    }

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    // Set by the composable layer; invoked when the user taps DECRYPT → SAF picker opens
    var onRequestDecryptSave: ((docId: String, fileName: String) -> Unit)? = null

    init { refresh() }

    fun refresh() {
        val missions = store.missions.value
        val entries = identityManager.getVaultEntries().map { entry ->
            val missionName = missions.find { it.id == entry.missionId }?.name ?: "UNKNOWN"
            VaultEntryUi(
                docId = entry.docId,
                fileName = entry.fileName,
                missionName = missionName,
                missionId = entry.missionId,
                age = formatAge(entry.createdMs),
            )
        }
        _state.value = _state.value.copy(
            entries = entries,
            missions = missions.map { it.id to it.name },
        )
    }

    fun handle(intent: VaultIntent) {
        when (intent) {
            is VaultIntent.SelectMission -> _state.value = _state.value.copy(selectedMissionId = intent.id)
            is VaultIntent.ImportFile -> {} // handled externally via SAF launcher in the composable
            is VaultIntent.DecryptAndSave -> onRequestDecryptSave?.invoke(intent.docId, intent.fileName)
            is VaultIntent.DeleteEntry -> deleteEntry(intent.docId)
            is VaultIntent.DismissStatus -> _state.value = _state.value.copy(statusMessage = null)
        }
    }

    fun importFile(context: Context, uri: Uri, missionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isWorking = true, statusMessage = null)
            runCatching {
                val fileName = resolveFileName(context, uri)

                // Enforce size cap before reading into memory
                val fileSizeBytes = resolveFileSize(context, uri)
                if (fileSizeBytes != null && fileSizeBytes > MAX_VAULT_FILE_BYTES) {
                    throw IllegalArgumentException(
                        "FILE TOO LARGE: ${fileSizeBytes / (1024 * 1024)}MB // MAX ${MAX_VAULT_FILE_MB}MB"
                    )
                }

                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot read file")

                if (bytes.size > MAX_VAULT_FILE_BYTES) {
                    throw IllegalArgumentException("FILE TOO LARGE: ${bytes.size / (1024 * 1024)}MB // MAX ${MAX_VAULT_FILE_MB}MB")
                }

                val userId = identityManager.getUserIdentity()?.id ?: "UNKNOWN"
                documentRepository.store(missionId, userId, fileName, bytes).getOrThrow()

                // Attempt to delete the original — SAF only grants delete rights for certain
                // locations (e.g. the app's own directories). For shared storage it typically
                // fails silently. We report the outcome so the user knows what happened.
                val originalDeleted = try {
                    context.contentResolver.delete(uri, null, null) > 0
                } catch (_: Exception) { false }

                refresh()
                _state.value = _state.value.copy(
                    isWorking = false,
                    statusMessage = if (originalDeleted)
                        "ENCRYPTED + ORIGINAL DELETED: $fileName"
                    else
                        "ENCRYPTED: $fileName // ORIGINAL NOT DELETED — REMOVE MANUALLY",
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isWorking = false,
                    statusMessage = "> ERROR: ${e.message?.take(60) ?: "IMPORT_FAILED"}",
                )
            }
        }
    }

    private fun resolveFileSize(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            cursor.moveToFirst()
            if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }

    fun saveDecryptedTo(context: Context, docId: String, destUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isWorking = true, statusMessage = null)
            runCatching {
                val bytes = documentRepository.retrieve(docId).getOrThrow()
                context.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                _state.value = _state.value.copy(isWorking = false, statusMessage = "DECRYPTED // FILE SAVED")
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isWorking = false,
                    statusMessage = "> ERROR: ${e.message?.take(50) ?: "DECRYPT_FAILED"}",
                )
            }
        }
    }

    private fun deleteEntry(docId: String) {
        identityManager.deleteVaultEntry(docId)
        refresh()
        _state.value = _state.value.copy(statusMessage = "DELETED: $docId")
    }

    private fun resolveFileName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (idx >= 0) cursor.getString(idx) else null
        } ?: uri.lastPathSegment ?: "document"

    private fun formatAge(createdMs: Long): String {
        val age = System.currentTimeMillis() - createdMs
        return when {
            age < 3_600_000L -> "${age / 60_000}m"
            age < 86_400_000L -> "${age / 3_600_000}h"
            else -> "${age / 86_400_000}d"
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            VaultViewModel(
                identityManager = container.identityManager,
                store = container.store,
                documentRepository = container.documentRepository,
            ) as T
    }
}

// ─── Route ────────────────────────────────────────────────────────────────────

@Composable
fun ToolsRoute(
    container: AppContainer,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    onProfileClick: (() -> Unit)? = null,
) {
    val vm: ToolsViewModel = viewModel(factory = ToolsViewModel.Factory(container))
    val vaultVm: VaultViewModel = viewModel(factory = VaultViewModel.Factory(container))

    val state by vm.state.collectAsStateWithLifecycle()
    val vaultState by vaultVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // SAF: open any document for import
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val missionId = vaultState.selectedMissionId ?: return@rememberLauncherForActivityResult
        vaultVm.importFile(context, uri, missionId)
    }

    // SAF: choose where to save the decrypted file
    var pendingDecryptDocId by remember { mutableStateOf<String?>(null) }
    var pendingDecryptFileName by remember { mutableStateOf("") }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val docId = pendingDecryptDocId ?: return@rememberLauncherForActivityResult
        vaultVm.saveDecryptedTo(context, docId, uri)
        pendingDecryptDocId = null
    }

    // Wire the decrypt callback so VaultViewModel can trigger the SAF picker
    vaultVm.onRequestDecryptSave = { docId, fileName ->
        pendingDecryptDocId = docId
        pendingDecryptFileName = fileName
        saveLauncher.launch(fileName)
    }

    // Refresh vault entries whenever mode switches to VAULT
    LaunchedEffect(state.mode) {
        if (state.mode == ToolMode.VAULT) vaultVm.refresh()
    }

    // Wipe plaintext on pause — sensitive data should not linger in memory
    androidx.lifecycle.compose.LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        vm.wipe()
    }

    AstraAppShell(
        selectedTab = selectedTab, onTabSelect = onTabSelect,
        topBarContent = { AstraTopBar(callsign = "SECURE_TOOLS", clearanceLabel = "LOCAL_MODE", onProfileClick = onProfileClick) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            ToolsContent(
                state = state,
                onStateChange = { vm.updateState(it) },
                onExecute = {
                    if (state.algo == Algorithm.AES_256_GCM) vm.execute()
                    else vm.setOutput(executeToolsOp(state))
                },
                vaultState = vaultState,
                onVaultIntent = { intent ->
                    when (intent) {
                        is VaultIntent.ImportFile -> importLauncher.launch(arrayOf("*/*"))
                        else -> vaultVm.handle(intent)
                    }
                },
            )
        }
    }
}
