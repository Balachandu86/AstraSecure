# Screen 07 — Panic Wipe

**Visual reference:** [panic_wipe/screen.png](../../../design/stitch_astrasecure_north_star_document/panic_wipe/screen.png) and [panic_wipe/code.html](../../../design/stitch_astrasecure_north_star_document/panic_wipe/code.html).

**Route:** `panic` (PANIC bottom-nav tab).

## Purpose

**Total annihilation.** Per Q3 in [06_OPEN_QUESTIONS.md](../06_OPEN_QUESTIONS.md), the wipe destroys all local mission data, all schema customizations, all encrypted documents, all Keystore aliases (including the user's identity), and writes a terminal tombstone that **renders the app non-functional until reinstall (or OS-level Clear Data)**. The wiped identity is unrecoverable — the device's hardware key is invalidated and a future provisioning produces a brand-new identity with no relationship to the old one.

This screen must be **impossible to trigger accidentally** while remaining accessible in <2 taps when the operator needs it.

## Anatomy

```
[ Top bar (default) ]
[ Alert header — flicker-on warning glyph in error/30 box ]
   "TERMINAL PURGE INITIATED" — 4xl headline, error color, all caps
[ Status row — monospace, error pulse dot ]
   "STATUS: STANDBY  •  ENCRYPTION: AES-XTS-512"
[ Danger-zone description card — error left-border ]
   "IRREVERSIBLE ACTION. ALL APP DATA WILL BE DESTROYED.
    THE DEVICE'S HARDWARE IDENTITY WILL BE INVALIDATED.
    AFTER PURGE, THIS APP MUST BE REINSTALLED OR CLEARED VIA SYSTEM SETTINGS
    BEFORE A NEW IDENTITY CAN BE PROVISIONED. THE WIPED IDENTITY CANNOT BE RECOVERED."
[ 2-up grid: AUTO_REVOKE (REMOTE_SYNC_ACTIVE) | TIME_TO_LOCKDOWN (countdown if set) ]
[ Slide-to-confirm gesture — error fill knob, full-width track ]
[ ABORT_PROTOCOL | (no second confirm button — slider only, see "Anti-accident") ]
[ Bottom system logs (semi-transparent, decorative — desktop only, omit on phone) ]
[ Bottom nav (PANIC active) ]
```

## States

```kotlin
sealed interface PanicUiState {
    data class Standby(val timeToLockdownMs: Long?, val remoteSyncActive: Boolean) : PanicUiState
    data class Confirming(val sliderProgress: Float) : PanicUiState     // 0f..1f
    data class Wiping(val progress: WipeProgress) : PanicUiState
    data object Tombstoned : PanicUiState                                // tombstone written, awaiting uninstall prompt
}

data class WipeProgress(
    val phase: WipePhase,
    val phaseLabel: String,           // "REVOKING_REMOTE_TOKENS"
    val percentage: Int,
)

enum class WipePhase {
    REVOKE_TOKENS,
    OVERWRITE_LOCAL,
    INVALIDATE_KEYS,
    WIPE_SCHEMA,
    FINALIZING,                       // includes tombstone write
}
```

## Intents

```kotlin
sealed interface PanicIntent {
    data class SlideProgress(val progress: Float) : PanicIntent
    data object SlideCommit : PanicIntent       // fired when slider hits ≥0.95
    data object Abort : PanicIntent
}
```

There is no `PurgeButton` intent. The slider is the only way in (see "Anti-accident").

## The slide-to-confirm gesture

```kotlin
@Composable
fun SlideToConfirm(progress: Float, onProgress: (Float) -> Unit, onCommit: () -> Unit) {
    // Track: full-width Box, surface-container-lowest, 1px outline-variant/20 border
    // Knob: 64dp x 64dp, error fill, draggable horizontally
    //   onDrag: clamp to [0, 1], onProgress(progress)
    //   onDragEnd: if progress >= 0.95 → onCommit(); else animate progress → 0
    // Background: error/10 → transparent gradient, fills proportional to progress
}
```

Haptics: light tick at 50% progress, medium impact at 95% threshold, heavy on commit.

## The wipe pipeline

When `SlideCommit` fires:

1. VM transitions to `Wiping(REVOKE_TOKENS, 0)`.
2. **Phase REVOKE_TOKENS:** `identityManager.revokeRemoteTokens()` — no-op for the capstone, but emits a 300ms artificial delay so the UI's phase progress feels honest.
3. **Phase OVERWRITE_LOCAL:** `messageRepository.wipeAll()` → `documentRepository.wipeAll()` → `channelRepository.wipeAll()` → `missionRepository.wipeAll()`. These iterate Keystore-encrypted files and overwrite them before unlinking.
4. **Phase INVALIDATE_KEYS:** `cryptoEngine.invalidateAllKeys()` — removes every Keystore alias matching the app's prefix, including the operator's identity key.
5. **Phase WIPE_SCHEMA:** `rankRepository.wipeAll()` → `channelCategoryRepository.wipeAll()` → `messageCategoryRepository.wipeAll()` → `missionTypeRepository.wipeAll()` → `clearanceRepository.wipeAll()`. Including system-flagged seed records — wipe means wipe.
6. **Phase FINALIZING:** `identityManager.wipeAll()` clears `EncryptedSharedPreferences`. **Last:** `identityManager.writeTombstone()` writes `terminal=true` to plain `SharedPreferences("astra_state")`. This file is intentionally outside the encrypted tier so it survives the wipe of EncryptedSharedPreferences.
7. VM transitions to `Tombstoned`.
8. After 600ms hold (so the user sees `PURGE COMPLETE // TERMINAL`), the activity launches `Intent.ACTION_UNINSTALL_PACKAGE` for the user to confirm uninstall.

Each phase increments percentage in 20% steps with a 200–400ms sub-phase animation. Total perceived wipe time ~3–4 seconds.

## Post-wipe boot semantics

`MainActivity.onCreate` reads the tombstone **before** doing anything else:

```kotlin
val container = (application as AstraApp).container
if (container.identityManager.readTombstone()) {
    setContent { AstraSecureTheme { TerminatedScreen() } }
    return
}
val start = if (container.identityManager.getUserIdentity() != null) "app" else "provisioning"
// ... normal nav graph
```

The `TerminatedScreen` is a sibling of `ProvisioningScreen` but with no inputs. Just:

```
[ Centered, full-screen, error accent ]
> PURGE COMPLETE
> THIS DEVICE IS NO LONGER PROVISIONED.

To use AstraSecure again:
  1. Uninstall this app (recommended), or
  2. Settings → Apps → AstraSecure → Storage → Clear data

[ RECHECK STATE ]   ← only re-reads the tombstone in case OS-level clear just happened
```

The `RECHECK STATE` button re-runs `readTombstone()` and, if false, hot-reloads into `provisioning`. This avoids forcing the user to fully relaunch after Clear Data.

## Failure handling

If any wipe phase throws, the UI **does not roll back** — partial wipes are still wipes. It transitions to `Tombstoned` with an `error` chip `INCOMPLETE_PURGE_BUT_SEALED` and the tombstone is written regardless. This is a hard requirement: a panic action that "fails halfway" must not leave the operator believing data was preserved.

## Visual rules

- Background gets a subtle `error/5` radial gradient (HTML uses `bg-gradient-to-b from-error/5 via-transparent to-error/5`).
- Flicker animation on the warning icon at the top is per DESIGN.md — 200ms ease-in-out, opacity 0.3→0.8→1.
- Slider knob has `shadow-[0_0_20px_rgba(238,125,119,0.3)]` glow — translate via `Modifier.drawBehind` with a soft circle. No `Modifier.shadow` (forbidden by design system).
- During `Wiping`, the entire screen darkens to `surface-container-lowest` and only the phase progress is visible. This is a deliberate "going to sleep" feel.
- During `Tombstoned`, the screen flashes once to white then fades to black before the uninstall prompt — sub-second, but signals finality.

## Module integration

These methods are required:
- `IdentityManager.wipeAll()`, `revokeRemoteTokens()`, `writeTombstone()`, `readTombstone()` (Sandrani).
- `CryptoEngine.invalidateAllKeys()` (Tejas).
- All entity and schema repos: `wipeAll()`.

`InMemoryStore.clear()` is called transitively by repository wipes.

## Anti-accident hardening

Per Q3, the slider is the **only** confirm path. The HTML had a redundant "PURGE ALL DATA" button — drop it. A single deliberate gesture is harder to fat-finger than a tappable button.

The slider must be at >=95% drag distance to commit. Below that, releasing snaps it back to 0 with a cancel tick.

## Owner & branch coordination

| Layer | Package | Credit |
|-------|---------|--------|
| Composables (incl. slider, terminated screen) | `ui/PanicScreen.kt`, `ui/TerminatedScreen.kt` | Yashwanth |
| VM, repository wipeAll() | `ux/PanicViewModel.kt`, repo impls | Ismail |
| Identity wipe, tombstone, revoke | `identity/IdentityManager.kt` | Sandrani |
| Crypto invalidate | `crypto/CryptoEngine.kt` | Tejas |

## Build order

1. Composables — build all 4 states + `SlideToConfirm` + `TerminatedScreen` with hardcoded preview data.
2. Wire `PanicViewModel` against the in-memory repos. Tombstone is faked initially with a `Boolean` in memory (so the dev loop doesn't get stuck terminated).
3. Wire `MainActivity` to honor the tombstone.
4. Land the real `IdentityManager` tombstone API (plain `SharedPreferences`).
5. Land `cryptoEngine.invalidateAllKeys()` and plug it into phase 4.
6. Wire the `Intent.ACTION_UNINSTALL_PACKAGE` chooser as the post-Tombstoned step.

## Done criteria

- [ ] Slider requires a deliberate ~95% drag to commit. A tap-and-release at 0% does nothing.
- [ ] Abort button at any time before Wiping returns to Standby.
- [ ] Once `Wiping` enters, abort is disabled.
- [ ] Each wipe phase is visible to the operator with its label.
- [ ] On completion, every list (`MissionRepository.missions`, all schema repos) is empty.
- [ ] Tombstone is written and `IdentityManager.readTombstone()` returns true.
- [ ] Re-launching the app after wipe lands on `TerminatedScreen`, not on `provisioning`.
- [ ] After OS-level Clear Data, app boots into `provisioning` with no remnants.
- [ ] Provisioning a new identity post-wipe produces a Keystore alias with no relationship to any prior alias (verified by hash comparison in a unit test).
- [ ] Uninstall prompt is launched after `Tombstoned`.
- [ ] Compose UI test: drag slider to 1.0, assert state transitions Wiping → Tombstoned, assert MainActivity routes to TerminatedScreen on next boot (instrumented test with two activity launches).
- [ ] Unit test for VM: assert phase ordering REVOKE_TOKENS → OVERWRITE_LOCAL → INVALIDATE_KEYS → WIPE_SCHEMA → FINALIZING.
