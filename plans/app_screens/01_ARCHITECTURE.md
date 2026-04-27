# Architecture

## Layering

```
┌─────────────────────────────────────────────────────┐
│  ui/    Composables — stateless, take UiState in,   │  (Yashwanth)
│         emit Intents out. No business logic.        │
├─────────────────────────────────────────────────────┤
│  ux/    ViewModels — own UiState, route Intents to  │  (Ismail)
│         repositories + module APIs, expose StateFlow│
├─────────────────────────────────────────────────────┤
│  shared/data/  Repositories                         │  Shared
│  ├── Entity repos (Mission/Channel/Message/Doc)     │
│  └── Schema repo (Rank/ChannelCategory/             │
│      MessageCategory/MissionType — user-editable)   │
├─────────────────────────────────────────────────────┤
│  crypto/      identity/      metadata/              │  (Tejas, Sandrani, Jatin)
│  Module APIs invoked by ViewModels via DI.          │
└─────────────────────────────────────────────────────┘
```

Per [Q1 in 06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md), per-folder editing rules are dropped — the parenthesized names are credit attribution, not access control. Repositories live under `shared/data/`. The schema layer is what makes ranks, categories, and mission types user-editable rather than hardcoded enums (see [03_DATA_LAYER.md](03_DATA_LAYER.md)).

## State model

Every screen defines exactly three Kotlin types in its file:

```kotlin
// 1. UiState — what the Composable renders
sealed interface FooUiState {
    data object Loading : FooUiState
    data class Content(val foo: Foo, /* ... */) : FooUiState
    data class Error(val message: String, val recoverable: Boolean) : FooUiState
}

// 2. UiEvent — one-shot signals (snackbars, navigation)
sealed interface FooUiEvent {
    data class NavigateTo(val route: String) : FooUiEvent
    data class ShowToast(val text: String) : FooUiEvent
}

// 3. Intent — what the user can do
sealed interface FooIntent {
    data object Refresh : FooIntent
    data class Submit(val payload: String) : FooIntent
}
```

ViewModel exposes:
- `val state: StateFlow<FooUiState>`
- `val events: SharedFlow<FooUiEvent>` (replay = 0, extraBufferCapacity = 8)
- `fun handle(intent: FooIntent)`

The Composable takes `(state, onIntent)` only — never the VM directly. This keeps Composables previewable and testable without instantiating Android lifecycle.

## Dependency injection (no Hilt)

Hilt would pull in KAPT and slow CI. The capstone uses **manual DI via an `AppContainer`**:

```kotlin
// shared/AppContainer.kt
class AppContainer(context: Context) {
    val cryptoEngine = CryptoEngine()
    val identityManager = IdentityManager(context)
    val metadataProcessor = MetadataProcessor()
    val missionRepository = MissionRepository(/* deps */)
    val messageRepository = MessageRepository(/* deps */)
}

// MainActivity holds the container; ViewModels get it via factory
class MissionViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory { /* ... */ }
```

Composables get their VM via `viewModel(factory = ...)` from `androidx.lifecycle.viewmodel.compose`.

Adding Hilt later is non-breaking; this is a deliberate "ship-something-now" choice.

## Module touchpoints (the contract surface)

These are the cross-module symbols every screen depends on.

### `crypto/CryptoEngine` (Tejas)
```kotlin
fun generateMissionKey(missionKeyAlias: String)              // Mission creation
fun rotateMissionKey(missionKeyAlias: String): String        // Returns new alias; old is invalidated
fun encryptMessage(missionKeyAlias: String, plaintext: ByteArray): ByteArray
fun decryptMessage(missionKeyAlias: String, ciphertext: ByteArray): ByteArray
fun encryptWithPassword(password: String, plaintext: ByteArray): ByteArray   // Tools screen
fun decryptWithPassword(password: String, ciphertext: ByteArray): ByteArray
fun invalidateAllKeys()                                      // Panic screen
```

### `identity/IdentityManager` (Sandrani)
```kotlin
fun provisionIdentity(displayName: String): User
fun getUserIdentity(): User?
fun deviceAttestation(): DeviceAttestation                   // Security dashboard
fun storeDocument(missionId: String, fileName: String, fileBytes: ByteArray): EncryptedDocument
fun retrieveDocument(documentId: String): ByteArray
fun revokeRemoteTokens()                                     // Panic phase 1 (no-op in capstone, real impl later)
fun wipeAll()                                                // Clears EncryptedSharedPreferences + identity
fun writeTombstone()                                         // Panic terminal marker (see Q3)
fun readTombstone(): Boolean                                 // MainActivity gate
```

### `metadata/MetadataProcessor` (Jatin)
```kotlin
fun padMessage(encryptedBytes: ByteArray): ByteArray
fun randomizedDelayMs(minMs: Long, maxMs: Long): Long
fun batchMessages(pending: List<Message>, maxBatchSize: Int): List<List<Message>>
```

### `shared/Models.kt`
Per [Q2/Q8 in 06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md), the existing `MessageCategory` enum and the proposed `Classification` enum are **replaced by data records** (rows in the schema repo). `MissionStatus` stays as an enum (it's a workflow state machine, not a category). Existing types `User`, `Mission`, `Message`, `EncryptedDocument` get new fields documented in [03_DATA_LAYER.md](03_DATA_LAYER.md). All schema additions land in one Phase 0 diff — no piecemeal rollout.

## Degraded-mode protocol

Module functions currently `throw NotImplementedError`. The UI must not crash — every VM call site wraps the call:

```kotlin
private suspend fun <T> safeCall(block: () -> T): Result<T> = runCatching { block() }
    .onFailure { if (it is NotImplementedError) /* set DEGRADED banner */ }
```

When degraded, the screen renders its content but with a top banner:
> `[ DEGRADED // CRYPTO_OFFLINE ]` — `surface-container-high` background, `secondary` (amber) text, monospace.

This lets the UI team build screens against stubs without the crypto/identity/metadata work blocking them. As real implementations land, the banner disappears for that subsystem.

## Threading

- All module APIs that touch Keystore or do crypto are blocking. ViewModels invoke them on `Dispatchers.Default` or `Dispatchers.IO` via `viewModelScope.launch(Dispatchers.IO)`.
- Composables stay on Main. `collectAsStateWithLifecycle()` (lifecycle-runtime-compose) is preferred over `collectAsState()` to respect lifecycle.
- One missing dep: add `androidx.lifecycle:lifecycle-runtime-compose` to [libs.versions.toml](../../gradle/libs.versions.toml) — see Phase 1 in [04_PHASES.md](04_PHASES.md).

## Error surfaces

| Source | Where it surfaces |
|--------|-------------------|
| `NotImplementedError` from a module | Top-of-screen DEGRADED banner |
| Validation (empty input, invalid format) | Inline below field, `error` color |
| Keystore unavailable / device not secure | Identity provisioning blocks with full-screen error |
| Crypto failure during send | Per-message status row: `> SEND_FAILED // RETRY` |
| Decryption failure on receive | Render message slot as `[ CIPHERTEXT // INTEGRITY_FAIL ]` in `error` color |

No silent failures. The aesthetic (HUD/tactical) leans into legible failure states — use it.
