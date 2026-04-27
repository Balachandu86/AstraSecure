# Screen 05 — Secure Tools

**Visual reference:** [secure_tools/screen.png](../../../design/stitch_astrasecure_north_star_document/secure_tools/screen.png) and [secure_tools/code.html](../../../design/stitch_astrasecure_north_star_document/secure_tools/code.html).

**Route:** `tools` (TOOLS bottom-nav tab).

## Purpose

Self-contained encode/decode/hash playground. The mockup leans on a desktop sidebar layout — strip that for phone. The single most useful function for the capstone demo is round-tripping AES-256-GCM via `CryptoEngine` to prove the crypto layer works in isolation, without needing a chat session.

## Anatomy (phone layout)

```
[ Top bar: SECURE_TOOLS_V4.0 / settings icon ]
[ Mode tabs: [ENCODE/DECODE] [HASH] [XOR] [NOTES] ]
[ Tool parameters card — collapsed by default, expandable ]
   ├── Algorithm select
   ├── Cipher key (password field, only visible if algo needs key)
   └── EXECUTE button
[ Raw input area — large textarea, line/char count ]
[ Processed output area — read-only, COPY / CLEAR actions ]
[ Footer: SECURE_LINK_ESTABLISHED // DATA_WIPE_ON_EXIT ]
[ Bottom nav (TOOLS active) ]
```

The desktop's left-sidebar nav becomes a horizontal scrollable mode-tab strip on phone. Drop the "ACTIVE_SESSION" sidebar block — the top bar already shows clearance.

## Modes

| Mode | Algos | Module |
|------|-------|--------|
| ENCODE/DECODE | BASE64_STANDARD, HEX_STRING, URL_ENCODE, AES_256_GCM | Java/Android stdlib + `CryptoEngine` for AES |
| HASH | SHA_256, SHA_512, BLAKE2B | `java.security.MessageDigest` |
| XOR | One-time-pad XOR with provided key | local |
| NOTES | Store/retrieve a single encrypted note via `IdentityManager` | `IdentityManager.storeDocument` with fixed filename `__notes.bin` |

Only ENCODE/DECODE + HASH need to ship for the capstone demo. XOR and NOTES are stretch.

## States

```kotlin
sealed interface ToolsUiState {
    data class Content(
        val mode: ToolMode,
        val algorithm: Algorithm,
        val direction: Direction,            // ENCODE or DECODE
        val key: String,                     // hidden in UI when not needed
        val input: String,
        val output: ToolOutput,
        val securityLevel: SecurityLevel,    // MAX_SECURE if all-local, REDUCED if uses Keystore
        val autoWipe: Boolean,
    ) : ToolsUiState
}

sealed interface ToolOutput {
    data object Idle : ToolOutput              // [SYSTEM_IDLE] // WAITING_FOR_EXECUTION_COMMAND
    data object Working : ToolOutput
    data class Success(val text: String) : ToolOutput
    data class Failure(val reason: String) : ToolOutput
}

enum class ToolMode { ENCODE_DECODE, HASH, XOR, NOTES }
enum class Direction { ENCODE, DECODE }
```

## Intents

```kotlin
sealed interface ToolsIntent {
    data class SetMode(val mode: ToolMode) : ToolsIntent
    data class SetAlgorithm(val algo: Algorithm) : ToolsIntent
    data class SetDirection(val dir: Direction) : ToolsIntent
    data class SetInput(val text: String) : ToolsIntent
    data class SetKey(val key: String) : ToolsIntent
    data object Execute : ToolsIntent
    data object CopyOutput : ToolsIntent
    data object ClearAll : ToolsIntent
}
```

## Auto-wipe behavior

The HTML shows `AUTO_WIPE: ACTIVE`. Implementation:
- `LifecycleEventObserver` on `ON_PAUSE` clears `input`, `output`, `key` from VM state.
- `LifecycleEventObserver` on `ON_DESTROY` does the same plus invalidates any `SecretKey` references held in the VM.
- This is a real security feature, not cosmetic. Test it.

## Module integration

- AES round-trip uses `CryptoEngine` directly (Tejas) — but with a tool-mode key derivation, not a mission key. **This requires a new method:**

```kotlin
// CryptoEngine — proposed
fun encryptWithPassword(password: String, plaintext: ByteArray): ByteArray   // uses PBKDF2 + AES-GCM
fun decryptWithPassword(password: String, ciphertext: ByteArray): ByteArray
```

Add to [01_ARCHITECTURE.md](../01_ARCHITECTURE.md) contract list. Tejas owns; until it lands, AES_256_GCM mode is gated with a `DEGRADED` chip on the algorithm dropdown.

- BASE64, HEX, URL_ENCODE, all hashes — pure stdlib, no module dep, no degradation.

## Visual rules

- Output box is monospace `tertiary` (success) or `error` (failure) — matches HTML.
- Idle text is the literal string `[SYSTEM_IDLE] // WAITING_FOR_EXECUTION_COMMAND` — preserve.
- Line/char count below input updates live, monospace, `outline` color.
- "EXECUTE_PROCESS" button has the `flicker-on` animation (200ms opacity 0.3→1) on each press.
- Algorithm select is a `surface-container-lowest` background, 1px `outline-variant` border, focus border `primary`.

## Owner & branch coordination

| Layer | Owner | Branch |
|-------|-------|--------|
| Composables | Yashwanth | yashwanth |
| VM + route | Ismail | ismail |
| `encryptWithPassword`/`decryptWithPassword` | Tejas | tejas |

This screen is the **lowest-risk** functional screen — it has no chat, no documents, no mission state. Use it as Yashwanth's onboarding screen if the team wants a Phase-2 warmup before tackling the chat surface.

## Build order

1. **Yashwanth** ships `ToolsContent` with all four mode tabs, only ENCODE/DECODE wired.
2. **Ismail** writes `ToolsViewModel`. Implements BASE64, HEX, URL_ENCODE in the VM directly (small enough to inline).
3. **Tejas** lands `encryptWithPassword`/`decryptWithPassword`. AES becomes available.
4. **Ismail** wires HASH algorithms via `MessageDigest`.
5. (Stretch) XOR and NOTES.

## Done criteria

- [ ] BASE64 round-trip: input "hello" → encode → decode → "hello".
- [ ] HEX round-trip likewise.
- [ ] AES round-trip with password: encrypt then decrypt returns original plaintext bytes.
- [ ] AES with wrong password returns `Failure("DECRYPT_INTEGRITY_FAIL")` not a crash.
- [ ] SHA-256 produces correct hex digest for known test vector.
- [ ] Auto-wipe clears state on `ON_PAUSE` (verified via Compose UI test that backgrounds the activity).
- [ ] COPY action puts result on clipboard with `ClipDescription.EXTRA_IS_SENSITIVE = true` (Android 13+).
