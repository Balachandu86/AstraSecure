# Data Layer

## Why this exists

Without a data layer, every ViewModel re-implements its own in-memory list, every screen drifts from the others, and panic-wipe has nothing to wipe. The data layer is the single source of truth for entities (Mission, Channel, Message, Document, User) **and** for user-defined schemas (Rank, ChannelCategory, MessageCategory, MissionType).

## Where it lives

```
shared/
├── Models.kt                # data records (no enums for user-customizable concepts)
├── AppContainer.kt          # DI root
└── data/
    ├── entity/
    │   ├── MissionRepository.kt
    │   ├── ChannelRepository.kt
    │   ├── MessageRepository.kt
    │   └── DocumentRepository.kt
    ├── schema/
    │   ├── RankRepository.kt
    │   ├── ChannelCategoryRepository.kt
    │   ├── MessageCategoryRepository.kt
    │   ├── MissionTypeRepository.kt
    │   └── ClearanceRepository.kt        # per-mission rank assignments
    ├── log/
    │   └── SecurityEventLog.kt           # append-only event ring buffer
    ├── InMemoryStore.kt                  # backing store, swappable
    └── SeedData.kt                       # debug-build seeding (see Q9)
```

## The customization model

Per Q8 in [06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md), four concepts are user-editable schemas, not Kotlin enums:

| Schema record | What it represents | Editable in |
|---------------|--------------------|-------------|
| `Rank` | A clearance tier ("OPERATIVE", "CHIEF", "OBSERVER") with a numeric level for gating | Admin Console |
| `ChannelCategory` | A grouping for channels ("COMMAND & CONTROL", "RECON & INTEL") with default clearance gates | Admin Console |
| `MessageCategory` | A sensitivity tag for individual messages ("INTELLIGENCE", "STANDARD", "RESTRICTED") | Admin Console |
| `MissionType` | A mission classification or operation kind ("RECON", "EXTRACTION", "SUSTAINMENT") | Admin Console |

Each record carries a stable `id: String` (UUID) so entities can refer to it without breaking when the user renames it. Renaming a Rank from "OPERATIVE" to "OPERATOR" updates everywhere that displays it; deleting a Rank requires the UI to confirm whether to **reassign** or **block** the deletion if anything still references it (Admin Console enforces this).

## Type definitions (full)

```kotlin
// ─── Existing types (Models.kt) — preserved with extensions ────────────────

enum class MissionStatus { ACTIVE, STANDBY, COMPROMISED, ARCHIVED }
// ↑ Stays an enum. This is a workflow state machine, not a user-defined category.

enum class ColorToken { PRIMARY, TERTIARY, SECONDARY, ERROR, NEUTRAL }
// ↑ Restricted palette so user-customized records can't pick arbitrary colors
//   that violate the design system. Mapped to AstraTheme tokens at render time.

data class User(
    val id: String,
    val hardwareKeyId: String,
    val displayName: String,
    val provisionedAtMs: Long,
)

data class Mission(
    val id: String,
    val name: String,
    val typeId: String,                  // FK → MissionType
    val status: MissionStatus,
    val phase: String?,                  // free-text, optional ("PHASE 4 / EXTRACTION")
    val missionKeyAlias: String,
    val participantIds: List<String> = emptyList(),
    val createdAtMs: Long,
    val lastActivityMs: Long,
)

data class Channel(
    val id: String,
    val missionId: String,
    val name: String,
    val description: String,
    val categoryId: String,              // FK → ChannelCategory
    val minClearanceToView: Int,         // can override category default
    val minClearanceToPost: Int,
    val createdAtMs: Long,
)

data class Message(
    val id: String,
    val channelId: String,               // CHANGED from missionId — messages live in channels
    val senderId: String,
    val categoryId: String,              // FK → MessageCategory
    val encryptedContent: ByteArray,
    val paddedSizeBytes: Int,
    val timestampMs: Long,
) {
    override fun equals(other: Any?): Boolean { /* by id */ }
    override fun hashCode(): Int = id.hashCode()
}

data class EncryptedDocument(
    val id: String,
    val missionId: String,
    val ownerUserId: String,
    val encryptedBytes: ByteArray,
    val fileName: String,
    val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean { /* by id */ }
    override fun hashCode(): Int = id.hashCode()
}

// ─── New schema records ───────────────────────────────────────────────────

data class Rank(
    val id: String,
    val name: String,                    // "OPERATIVE", uppercased by convention
    val level: Int,                      // gating uses this; higher = more access
    val color: ColorToken,
    val isSystem: Boolean = false,       // seed defaults can't be deleted, only edited
)

data class ChannelCategory(
    val id: String,
    val name: String,
    val accent: ColorToken,
    val defaultMinClearanceToView: Int,
    val defaultMinClearanceToPost: Int,
    val isSystem: Boolean = false,
)

data class MessageCategory(
    val id: String,
    val name: String,                    // "STANDARD", "INTELLIGENCE", etc.
    val accent: ColorToken,
    val minClearanceToSend: Int,
    val isSystem: Boolean = false,
)

data class MissionType(
    val id: String,
    val name: String,                    // "RECON", "EXTRACTION", "TOP_SECRET"
    val accent: ColorToken,
    val description: String,
    val isSystem: Boolean = false,
)

data class ClearanceAssignment(
    val userId: String,
    val missionId: String,
    val rankId: String,                  // FK → Rank
)

// ─── Security event log ───────────────────────────────────────────────────

data class SecurityEvent(
    val id: String,
    val tsMs: Long,
    val severity: Severity,
    val source: String,                  // "CryptoEngine", "MessageRepository", etc.
    val text: String,
)

enum class Severity { INFO, WARN, ALERT }
```

The existing fields `MessageCategory` (enum) and `Classification` (proposed enum) **do not exist**. They are replaced by the records above. Anywhere the design HTMLs use names like `TOP_SECRET`, those become seed `MissionType` records the user can rename.

## Repository contracts

### Entity repos
```kotlin
interface MissionRepository {
    val missions: StateFlow<List<Mission>>
    suspend fun create(name: String, typeId: String): Mission
    suspend fun get(id: String): Mission?
    suspend fun updateStatus(id: String, status: MissionStatus)
    suspend fun updatePhase(id: String, phase: String?)
    suspend fun delete(id: String)
    suspend fun wipeAll()
}

interface ChannelRepository {
    fun channelsForMission(missionId: String): StateFlow<List<Channel>>
    suspend fun create(missionId: String, name: String, description: String, categoryId: String, minClearanceToView: Int? = null, minClearanceToPost: Int? = null): Channel
    suspend fun get(channelId: String): Channel?
    suspend fun updateClearance(channelId: String, view: Int, post: Int)
    suspend fun delete(channelId: String)
    suspend fun wipeAll()
}

interface MessageRepository {
    fun messagesForChannel(channelId: String): StateFlow<List<Message>>
    suspend fun send(channelId: String, plaintext: String, categoryId: String): Result<Message>
    suspend fun receive(channelId: String, ciphertext: ByteArray): Result<Message>
    suspend fun wipeAll()
}

interface DocumentRepository {
    fun documentsForMission(missionId: String): StateFlow<List<EncryptedDocument>>
    suspend fun store(missionId: String, fileName: String, bytes: ByteArray): Result<EncryptedDocument>
    suspend fun retrieve(documentId: String): Result<ByteArray>
    suspend fun wipeAll()
}
```

### Schema repos
```kotlin
interface RankRepository {
    val ranks: StateFlow<List<Rank>>             // sorted by level desc
    suspend fun create(name: String, level: Int, color: ColorToken): Rank
    suspend fun update(id: String, name: String?, level: Int?, color: ColorToken?)
    suspend fun delete(id: String): DeleteResult // checks references first
    suspend fun wipeAll()
}

interface ChannelCategoryRepository {
    val categories: StateFlow<List<ChannelCategory>>
    suspend fun create(name: String, accent: ColorToken, defaultMinClearanceToView: Int, defaultMinClearanceToPost: Int): ChannelCategory
    suspend fun update(id: String, /* fields */)
    suspend fun delete(id: String): DeleteResult
    suspend fun wipeAll()
}

interface MessageCategoryRepository { /* analogous */ }
interface MissionTypeRepository { /* analogous */ }

interface ClearanceRepository {
    fun clearanceFor(userId: String, missionId: String): StateFlow<Rank?>
    suspend fun assign(userId: String, missionId: String, rankId: String)
    suspend fun unassign(userId: String, missionId: String)
    suspend fun wipeAll()
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data class BlockedBy(val refs: List<EntityRef>) : DeleteResult       // "3 channels still use this category"
}
```

## Clearance gating — the central algorithm

```kotlin
fun canViewChannel(user: User, channel: Channel, clearanceRepo: ClearanceRepository): Boolean {
    val rank = clearanceRepo.clearanceFor(user.id, channel.missionId).value ?: return false
    return rank.level >= channel.minClearanceToView
}

fun canPostInChannel(user: User, channel: Channel, clearanceRepo: ClearanceRepository): Boolean {
    val rank = clearanceRepo.clearanceFor(user.id, channel.missionId).value ?: return false
    return rank.level >= channel.minClearanceToPost
}
```

The Channel List screen filters/locks based on `canViewChannel`. The Chat screen disables the composer based on `canPostInChannel`. The Admin Console is the only place the user can change their own clearance — a deliberate lock-in for the capstone (in real product, an admin role would assign clearance; for the demo, the operator self-elects).

## Seed defaults (debug builds)

`SeedData.kt`, gated by `BuildConfig.DEBUG`, populates on first launch:

```kotlin
// Ranks
listOf(
    Rank("rank_observer", "OBSERVER", level = 1, color = ColorToken.NEUTRAL, isSystem = true),
    Rank("rank_operative", "OPERATIVE", level = 5, color = ColorToken.PRIMARY, isSystem = true),
    Rank("rank_chief", "CHIEF", level = 9, color = ColorToken.TERTIARY, isSystem = true),
)

// Channel categories (matching the design HTML)
listOf(
    ChannelCategory("cc_command", "COMMAND & CONTROL", ColorToken.SECONDARY, defaultMinClearanceToView = 9, defaultMinClearanceToPost = 9, isSystem = true),
    ChannelCategory("cc_recon", "RECONNAISSANCE & INTELLIGENCE", ColorToken.PRIMARY, defaultMinClearanceToView = 5, defaultMinClearanceToPost = 5, isSystem = true),
    ChannelCategory("cc_logistics", "LOGISTICS & DEPLOYMENT", ColorToken.NEUTRAL, defaultMinClearanceToView = 1, defaultMinClearanceToPost = 1, isSystem = true),
)

// Message categories
listOf(
    MessageCategory("mc_command", "COMMAND", ColorToken.SECONDARY, minClearanceToSend = 9, isSystem = true),
    MessageCategory("mc_intel", "INTELLIGENCE", ColorToken.PRIMARY, minClearanceToSend = 5, isSystem = true),
    MessageCategory("mc_standard", "STANDARD", ColorToken.NEUTRAL, minClearanceToSend = 1, isSystem = true),
    MessageCategory("mc_restricted", "RESTRICTED", ColorToken.ERROR, minClearanceToSend = 9, isSystem = true),
)

// Mission types (replacing the old Classification enum)
listOf(
    MissionType("mt_top_secret", "TOP SECRET", ColorToken.TERTIARY, "Maximum sensitivity operations", isSystem = true),
    MissionType("mt_confidential", "CONFIDENTIAL", ColorToken.NEUTRAL, "Restricted access operations", isSystem = true),
    MissionType("mt_restricted", "RESTRICTED", ColorToken.ERROR, "Compromised or quarantined operations", isSystem = true),
)
```

`isSystem = true` records cannot be deleted, only renamed/edited. This prevents the user from making the seed missions un-renderable. Custom records are fully deletable (subject to reference checks).

## In-memory store (Phase 1–2)

`InMemoryStore` holds `MutableStateFlow<List<X>>` per entity and per schema record. Repositories proxy to it. Panic wipe calls `InMemoryStore.clear()` which emits empty lists.

## Persistence (Phase 4)

| Entity | Storage |
|--------|---------|
| `User`, schema records, `ClearanceAssignment` | `EncryptedSharedPreferences` (single JSON blob per type) |
| `Mission`, `Channel` | JSON blob in `EncryptedSharedPreferences` |
| `Message` | per-channel append-only file `context.filesDir/channels/{id}/log.bin`, AES-GCM via `IdentityManager` |
| `EncryptedDocument` | already covered by `IdentityManager.storeDocument` |
| `SecurityEvent` (overflow beyond ring buffer) | `context.filesDir/audit.log.bin` AES-GCM |

Use kotlinx.serialization (added in Phase 4). Don't use Room.

## Data flow for a chat send

```
1. User taps SEND in ChatScreen with category=mc_intel
2. ChatViewModel checks canPostInChannel(user, channel) — fails fast if blocked
3. messageRepository.send(channelId, plaintext, categoryId="mc_intel")
4. Repository:
   a. cryptoEngine.encryptMessage(missionKeyAlias, plaintext.toBytes())
   b. metadataProcessor.padMessage(ciphertext)
   c. delay(metadataProcessor.randomizedDelayMs())
   d. InMemoryStore append Message(...)
   e. SecurityEventLog.emit(INFO, "MessageRepository", "MSG_SENT // ${channelId}")
   f. return Result.success(message)
5. VM updates state.messages with new outgoing message
```

Each step is independently testable.

## Wiping panic-wipe

`PanicViewModel` orchestrates per Q3 in [06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md):
1. `identityManager.revokeRemoteTokens()` (no-op for capstone, but emits a phase delay)
2. `messageRepository.wipeAll()` → `documentRepository.wipeAll()` → `channelRepository.wipeAll()` → `missionRepository.wipeAll()`
3. All schema repos: `wipeAll()` (this includes deleting custom Ranks, custom MissionTypes, etc. — even system seed records)
4. `cryptoEngine.invalidateAllKeys()`
5. `identityManager.wipeAll()` (clears EncryptedSharedPreferences)
6. `identityManager.writeTombstone()` — the **last** mutation, lands a `terminal=true` flag in plain `SharedPreferences("astra_state")`
7. `Intent.ACTION_UNINSTALL_PACKAGE` prompt to the user

On next launch, MainActivity calls `identityManager.readTombstone()` first. If true → render `TerminatedScreen` with no nav. Only OS-level Clear Data removes the tombstone.

## Threading and StateFlow rules

- Repositories expose `StateFlow`, not `Flow` — Compose gets a synchronous initial value.
- All write operations are `suspend` and run on `Dispatchers.IO`.
- No repository holds a reference to a Composable, ViewModel, or Activity. Pass `applicationContext` only into `IdentityManager` and `DocumentRepository`.
