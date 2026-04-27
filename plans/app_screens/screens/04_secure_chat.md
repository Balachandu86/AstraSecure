# Screen 04 — Secure Chat (per Channel)

**Visual reference:** [secure_chat/screen.png](../../../design/stitch_astrasecure_north_star_document/secure_chat/screen.png) and [secure_chat/code.html](../../../design/stitch_astrasecure_north_star_document/secure_chat/code.html).

**Route:** `missions/{id}/channels/{channelId}`.

## Purpose

The most cryptographically and ux-ically demanding screen. Hosts the end-to-end encrypted message stream, the encode-and-send pipeline, and inline rendering of encrypted intel packets. This screen is the proof-of-concept for the entire `crypto/ + metadata/ + identity/` integration.

## Anatomy

```
[ Top bar: ← back | channel name | key-rotation timer | lock icon ]
[ "Secure Session Established // Protocol 8.4" banner ]
[ Message stream — scrollable, oldest first, bottom-aligned to newest ]
   ├── Timestamp dividers
   ├── Incoming bubble: surface-container-low, left border primary
   ├── Outgoing bubble: surface-container-high, right border tertiary, "Delivered" indicator
   ├── System notification: "Channel Key Rotated // next rotation in 04:59"
   └── Encrypted data packet: full-width card with file metadata, [DECRYPT_AND_VIEW] [ARCHIVE]
[ Composer: [📎] [text input] [🔑] [ENCODE ▶] ]
[ Composer footer: "LEVEL 5 CLEARANCE REQUIRED" | "LINK ACTIVE: 100% SIGNAL" ]
[ Bottom nav (MISSIONS active) ]
```

## States

```kotlin
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
    data class Incoming(override val id: String, override val timestampMs: Long, val sender: String, val plaintext: String, val decryptOk: Boolean) : ChatItem
    data class Outgoing(override val id: String, override val timestampMs: Long, val plaintext: String, val deliveryState: DeliveryState) : ChatItem
    data class System(override val id: String, override val timestampMs: Long, val text: String) : ChatItem
    data class IntelPacket(override val id: String, override val timestampMs: Long, val sender: String, val fileName: String, val sizeBytes: Long, val sha256Prefix: String, val documentId: String) : ChatItem
}

enum class DeliveryState { ENCODING, SENT, DELIVERED, FAILED }

data class ComposerState(
    val text: String,
    val attaching: Boolean,
    val selectedCategory: MessageCategory,             // resolved record, not an enum
    val availableCategories: List<MessageCategoryOption>,
    val sendEnabled: Boolean,                          // false if !canPostInChannel or text empty
    val clearanceWarning: String?,                     // "REQUIRES CHIEF (LVL 9)" if blocked
)

data class MessageCategoryOption(
    val category: MessageCategory,
    val canSelect: Boolean,                            // category.minClearanceToSend <= user clearance
)

data class KeyRotationState(
    val nextRotationMs: Long,        // absolute timestamp
    val rotationIntervalMs: Long,    // e.g. 5 minutes
)
```

## Intents

```kotlin
sealed interface ChatIntent {
    data object Back : ChatIntent
    data class TextChanged(val text: String) : ChatIntent
    data class CategoryChanged(val categoryId: String) : ChatIntent
    data object Send : ChatIntent
    data class Attach(val uri: Uri) : ChatIntent
    data class Decrypt(val packet: ChatItem.IntelPacket) : ChatIntent
    data class Archive(val packet: ChatItem.IntelPacket) : ChatIntent
    data class RetrySend(val outgoingId: String) : ChatIntent
}
```

## The send pipeline

This is the canonical example referenced in [03_DATA_LAYER.md](../03_DATA_LAYER.md). When the user taps ENCODE:

1. VM appends an `Outgoing` item with `DeliveryState.ENCODING` immediately (optimistic).
2. VM calls `messageRepository.send(channelId, plaintext, category)`.
3. Repository:
   a. `cryptoEngine.encryptMessage(missionKeyAlias, plaintext.toByteArray())` — the plaintext is wiped from the in-memory `ComposerState` before this call returns (set to `""`).
   b. `metadataProcessor.padMessage(ciphertext)` — pads to 256-byte boundary.
   c. `delay(metadataProcessor.randomizedDelayMs(200, 2000))` — defeats timing analysis. **The UI must not show "encoding" forever** — show a 2-second max progress bar then SENT regardless of whether the delay has elapsed (the delay is for transport, not display).
   d. Append to `InMemoryStore` and emit on the channel's StateFlow.
4. VM updates the optimistic item to `DeliveryState.SENT`, then `DELIVERED` after 200ms (no transport, this is cosmetic for the capstone).
5. On any failure, item flips to `FAILED` and the user can `RetrySend`.

## Receive pipeline

There is no real network. To exercise decrypt:

- Add a debug-only "INJECT INCOMING" button (visible only in `BuildConfig.DEBUG`) that calls `messageRepository.receive(channelId, fakeCiphertext)`.
- That call runs `cryptoEngine.decryptMessage(...)` and emits the result.
- If decryption fails, the message renders as `[ CIPHERTEXT // INTEGRITY_FAIL ]` in `error` color — the user can long-press to copy the hex of the bad ciphertext for debugging.

## Key rotation

- `KeyRotationState.nextRotationMs` ticks down via a `produceState` collector that re-emits every second.
- When the deadline passes, the VM emits a `ChatItem.System("Channel Key Rotated // next rotation in 05:00")` and resets the counter.
- The actual key rotation is `cryptoEngine.rotateMissionKey(...)` — **this method does not exist yet**. Add to the contract surface and assign to Tejas (see [01_ARCHITECTURE.md](../01_ARCHITECTURE.md)). Until it lands, the rotation is cosmetic-only and a `[DEGRADED]` banner shows.

## Composer details

- `text` is bound to `ComposerState.text`. Empty string disables ENCODE button.
- 4096-character cap; show count once you cross 3500.
- The `🔑` button opens an inline category picker populated from `MessageCategoryRepository.categories`. Each option respects `MessageCategory.minClearanceToSend` against the user's per-mission rank — categories above the user's clearance render with a lock icon and are non-selectable. Default selection is whichever category has the lowest `minClearanceToSend` the user can use (typically STANDARD seed). Selected category shows as a chip above the text field, in `MessageCategory.accent` color.
- If the user lacks `canPostInChannel(channel)` clearance entirely, the entire composer is disabled and replaced with a banner: `> CHANNEL READ-ONLY // REQUIRES ${rank.name} (LVL ${channel.minClearanceToPost})`. ENCODE button is replaced by a lock glyph.
- The `📎` button launches `ActivityResultContracts.GetContent("*/*")`, calls `documentRepository.store(missionId, fileName, bytes)` on result, then sends an `IntelPacket` item referencing the returned `documentId`.

## Decrypt-and-view

`Decrypt(packet)` calls `documentRepository.retrieve(documentId)`. On success:
- If MIME is text-ish (`text/*`, `application/json`), open an in-app full-screen viewer with the plaintext.
- Otherwise, write to a cache file and launch a chooser via `Intent.ACTION_VIEW`. **Cache file must be wiped after the chooser returns** — register a lifecycle observer to call `delete()` on `ON_PAUSE`. Sandrani's domain.

## Visual rules

- All bubbles use sharp corners. Border on the side closer to the bubble's "speaker" — left border for incoming, right border for outgoing.
- Outgoing text is `right`-aligned per HTML.
- Sender callsigns are uppercase monospace, color-keyed: incoming = `primary`, outgoing = `tertiary`.
- Timestamps are 9.sp, `outline` color, monospace.
- Intel packets have a 10x10 dotted background pattern (radial gradient at 5% opacity) — this is in the HTML and must be preserved. Use `Modifier.drawBehind { drawCircle(...) }` looped over a grid, or a SVG-painter wrapper.

## Module integration

- `CryptoEngine.encryptMessage`, `decryptMessage`, `rotateMissionKey` (Tejas).
- `MetadataProcessor.padMessage`, `randomizedDelayMs` (Jatin).
- `IdentityManager.storeDocument`, `retrieveDocument` (Sandrani).
- `MessageRepository.send`, `messagesForChannel`, `receive`.
- `DocumentRepository.store`, `retrieve`.
- `MessageCategoryRepository.categories` — drives composer picker.
- `ClearanceRepository.clearanceFor(userId, missionId)` — drives composer enable/disable and category filtering.

This screen is the **integration bottleneck** of the capstone. Build it last among the screens (Phase 3 in [04_PHASES.md](../04_PHASES.md)), but stub each module so progress isn't gated on any single owner.

## Owner & branch coordination

| Layer | Owner | Branch |
|-------|-------|--------|
| Composables | Yashwanth | yashwanth |
| VM + repos | Ismail | ismail |
| Encrypt/decrypt | Tejas | tejas |
| Pad/jitter | Jatin | jatin |
| Document store | Sandrani | sandrani |

## Build order

1. **Yashwanth** ships `ChatContent` Composable with all `ChatItem` variants, fed by hardcoded preview data.
2. **Ismail** ships `MessageRepository` + `ChatViewModel` against the stubs (returns plaintext as the "ciphertext" until Tejas lands real impl). The DEGRADED // CRYPTO_OFFLINE banner is visible.
3. **Tejas** lands `encryptMessage`/`decryptMessage`. Banner clears for CRYPTO. Round-trip a known plaintext in a unit test.
4. **Jatin** lands `padMessage` + `randomizedDelayMs`. Banner clears for METADATA.
5. **Sandrani** lands `storeDocument`/`retrieveDocument`. Attach + decrypt-and-view becomes functional.
6. **Yashwanth** + **Ismail** finish the composer category picker + retry-on-failure flows.

## Done criteria

- [ ] Send a plaintext message; assert it appears as Outgoing → ENCODING → SENT → DELIVERED.
- [ ] Inject a known ciphertext (debug button); assert it decrypts and renders as Incoming.
- [ ] Inject a tampered ciphertext; assert it renders as INTEGRITY_FAIL and does not crash.
- [ ] Attach a 1MB file; assert intel-packet card appears with correct size.
- [ ] Decrypt-and-view a text packet; assert the plaintext viewer opens.
- [ ] Key rotation timer counts down and emits a System item at zero.
- [ ] Composer text is wiped from state immediately after the encrypt call returns.
- [ ] Composer category picker reflects `MessageCategoryRepository.categories` — adding a new category in the Admin Console shows up here without app restart.
- [ ] Categories above the user's clearance render with a lock and are non-selectable.
- [ ] Setting your rank below `channel.minClearanceToPost` puts the composer into the read-only banner state.
- [ ] No typing indicator anywhere in the UI.
- [ ] Compose UI test for the send happy-path.
- [ ] Unit test for the send pipeline that verifies the ordering of crypto → metadata → store.
