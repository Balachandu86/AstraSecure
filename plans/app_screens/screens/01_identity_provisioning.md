# Screen 01 — Identity Provisioning (First-Launch Onboarding)

**Not in the design HTMLs.** First-launch flow needed because `IdentityManager.getUserIdentity()` returns null on a fresh install and `MainActivity` decides start destination from it ([02_NAVIGATION.md](../02_NAVIGATION.md)).

## Purpose

Generate a hardware-backed Keystore identity, capture a callsign, and bootstrap the encrypted preferences store. This is the only screen that exists outside the app shell (no bottom bar).

## User flow

```
[Launch screen splash w/ spinner: "INITIALIZING SECURE BOOT..."]
        ↓ Keystore probe (StrongBox availability check)
[ Step 1: callsign entry ]
        ↓
[ Step 2: device attestation summary — read-only review ]
        ↓
[ Step 3: provisioning in progress — generates keypair on Default dispatcher ]
        ↓
[ Success → navigate to "missions" (start of app) ]
```

Back-press during provisioning is consumed (no exit mid-flow). Back from step 1 closes the app.

## States

```kotlin
sealed interface ProvisioningUiState {
    data object Probing : ProvisioningUiState
    data class CallsignEntry(val callsign: String, val error: String?) : ProvisioningUiState
    data class Review(val callsign: String, val deviceSecure: Boolean, val strongBoxBacked: Boolean) : ProvisioningUiState
    data class Provisioning(val progressLabel: String) : ProvisioningUiState
    data class Failed(val reason: String) : ProvisioningUiState
}
```

## Visual treatment

- Black `surface-dim` background, no top bar, no bottom bar.
- Single centered content column, max-width ~480dp.
- Step indicator at top: `[ 01 ▌ 02 ▌ 03 ]` — current is `primary`, others `outline-variant`.
- Heavy monospace use for the device-attestation review.
- Primary action is a full-width sharp-cornered button: `> PROVISION_IDENTITY`.

## Sub-components

- `StepIndicator(current: Int, total: Int)`
- `CallsignField` — wraps `AstraInputField` from [UiComponents.kt](../../../app/src/main/java/com/explo/capstone/ui/UiComponents.kt), enforces `^[A-Z][A-Z0-9_]{2,15}$`, uppercases as you type.
- `AttestationRow(label: String, value: String, ok: Boolean)` — two-column row, ok indicator uses `tertiary` ✓ or `error` ✗.
- `ProgressBar` — 1px-height, `primary` fill on `surface-container-lowest` track. No spinner — use a determinate bar tied to provisioning sub-steps.

## Validation

| Rule | Error message |
|------|---------------|
| Empty callsign | `> CALLSIGN_REQUIRED` |
| Doesn't match regex | `> ALPHA + DIGITS + UNDERSCORE ONLY` |
| Already exists locally | `> CALLSIGN_TAKEN` (only meaningful after restore flow exists) |

Errors are inline, monospace, `error` color, below the field.

## Module integration

- Depends on `IdentityManager.provisionIdentity(displayName)`.
- During `Probing`, also calls a new `IdentityManager.deviceAttestation()` (proposed) — returns `(deviceSecure: Boolean, strongBoxBacked: Boolean)`. If unavailable in time, fall back to `KeyguardManager.isDeviceSecure` directly from the VM.
- Failure modes:
  - Keystore unavailable → `Failed("KEYSTORE_OFFLINE")`. No retry — this is a device problem.
  - Provisioning throws `NotImplementedError` → `Failed("PROVISIONING_PENDING_SANDRANI")`. **Show this verbatim during dev** — it's a useful blocker signal.

## Owner & branch coordination

| Layer | Owner | Branch | File |
|-------|-------|--------|------|
| Composable | Yashwanth | yashwanth | `ui/ProvisioningScreen.kt` |
| ViewModel + route | Ismail | ismail | `ux/ProvisioningViewModel.kt`, `ux/ProvisioningRoute.kt` |
| `provisionIdentity()` impl | Sandrani | sandrani | `identity/IdentityManager.kt` |

Yashwanth and Ismail can build the screen against the stub today (degraded banner appears). Sandrani's implementation lifts the banner.

## Build order

1. **Yashwanth** ships a stateless `ProvisioningContent(state, onIntent)` with all 5 visual states + `@Preview` for each.
2. **Ismail** writes the VM with hardcoded happy-path (skip `IdentityManager`, just navigate after a 1s delay). Wires the route in `AstraNavGraph`.
3. **Ismail** swaps the hardcoded path for the real `identityManager.provisionIdentity()` call.
4. **Sandrani** lands the implementation and the screen goes live.

## Done criteria

- [ ] All 5 states render with correct typography and color tokens.
- [ ] Callsign regex enforced before button enables.
- [ ] Provisioning runs on `Dispatchers.IO`, UI never blocks.
- [ ] On success, `popUpTo("provisioning") { inclusive = true }` clears back stack.
- [ ] On failure, retry button returns to step 1 (preserves entered callsign).
- [ ] Compose UI test covers: empty input → button disabled, valid input → reaches Review state.
