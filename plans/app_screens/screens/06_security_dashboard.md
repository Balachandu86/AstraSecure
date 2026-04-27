# Screen 06 — Security Dashboard

**Visual reference:** None. The SECURITY tab is in the bottom nav (see [MissionsScreen.kt:598](../../../app/src/main/java/com/explo/capstone/ui/MissionsScreen.kt#L598)) but no design HTML was produced for it. This spec is a synthesis of what the design system implies a "security console" must show.

**Route:** `security`.

## Purpose

Operator's view into the device's cryptographic posture: identity status, key inventory, recent security events, integrity attestations, and quick actions for key rotation. Where Missions is "what am I working on" and Tools is "what can I do offline," Security is "what is the state of my fortress right now."

## Anatomy

```
[ Top bar (default) ]
[ Identity card — callsign, hardware key id, provisioning timestamp ]
[ Posture grid (2-up) ]
   ├── Device secure: ✓ / ✗ + KeyguardManager status
   ├── StrongBox: ✓ / ✗
   ├── E2E protocol: Signal v3.4 | downgraded
   └── Integrity: PASS / FAIL (Play Integrity if available; otherwise ROOT_CHECK_DISABLED)
[ Per-mission rank summary — list of (mission name, your current rank chip with color) ]
[ Active mission keys — list of (mission name, alias, age, "ROTATE NOW") ]
[ Recent security events — last 20, monospace, color-keyed by severity ]
[ Footer actions: [VERIFY ALL] [EXPORT_AUDIT_LOG] [PANIC →] ]
[ Top-bar gear icon → ADMIN CONSOLE ]
[ Bottom nav (SECURITY active) ]
```

## States

```kotlin
sealed interface SecurityUiState {
    data object Loading : SecurityUiState
    data class Content(
        val identity: IdentitySummary,
        val posture: DevicePosture,
        val missionKeys: List<MissionKeyEntry>,
        val events: List<SecurityEvent>,
    ) : SecurityUiState
    data class Error(val message: String) : SecurityUiState
}

data class IdentitySummary(
    val callsign: String,
    val hardwareKeyAlias: String,
    val provisionedAtMs: Long,
    val keystoreBackedBy: String,             // "STRONGBOX" | "TEE" | "SOFTWARE"
)

data class DevicePosture(
    val deviceSecure: Boolean,
    val strongBoxAvailable: Boolean,
    val protocolVersion: String,
    val integrityCheck: IntegrityResult,
)

sealed interface IntegrityResult {
    data object Pass : IntegrityResult
    data class Fail(val reason: String) : IntegrityResult
    data object Disabled : IntegrityResult
}

data class MissionKeyEntry(
    val missionId: String,
    val missionName: String,
    val keyAlias: String,
    val ageMs: Long,
    val needsRotation: Boolean,
)

data class SecurityEvent(
    val tsMs: Long,
    val severity: Severity,           // INFO, WARN, ALERT
    val text: String,
)

enum class Severity { INFO, WARN, ALERT }
```

## Intents

```kotlin
sealed interface SecurityIntent {
    data object Refresh : SecurityIntent
    data class RotateKey(val missionId: String) : SecurityIntent
    data object VerifyAll : SecurityIntent
    data object ExportAuditLog : SecurityIntent
    data object NavigateToPanic : SecurityIntent
}
```

## Behaviors

- **RotateKey** → calls `cryptoEngine.rotateMissionKey(alias)` (proposed; see [04_secure_chat.md](04_secure_chat.md)). On success, prepends a `SecurityEvent.INFO("KEY_ROTATED // ${alias}")`.
- **VerifyAll** → re-runs `IdentityManager.deviceAttestation()` and a key-availability probe against every mission key. Renders a 1-second flicker on each posture row as it confirms.
- **ExportAuditLog** → writes the events list to a Storage Access Framework target via `ActivityResultContracts.CreateDocument("application/json")`. The export is itself encrypted with the user's identity key.
- **NavigateToPanic** → `navController.navigate("panic")`. No special back-stack handling.

## Visual rules

- Identity card: `surface-container-low` background, 2px left border `primary`, monospace key alias.
- Posture grid cells: `surface-container-highest` background, 1px `outline-variant/15` border. PASS uses `tertiary` ✓, FAIL uses `error` ✗, DISABLED uses `secondary` (amber) ⚠.
- Mission key rows: 1-line monospace, age formatted as `4d 12h`, "ROTATE NOW" only shown if age > rotation interval.
- Security events list: monospace, line format `[HH:MM:SS] [SEV] message`. Last 20 entries, oldest at top, newest scrolled into view.

## Module integration

- `IdentityManager.getUserIdentity()` for callsign + key alias.
- `IdentityManager.deviceAttestation()` for posture grid.
- `cryptoEngine.rotateMissionKey()` for ROTATE NOW.
- `MissionRepository.missions` to enumerate keys.
- `RankRepository.ranks` + `ClearanceRepository` joined per mission for the rank-summary section.
- `SecurityEventLog` (append-only ring buffer in `shared/data/log/`) — every module emits to it. Capped at 200 in memory; older entries written to `audit.log.bin` AES-GCM encrypted via `IdentityManager`.

## Owner & branch coordination

| Layer | Owner | Branch |
|-------|-------|--------|
| Composables | Yashwanth | yashwanth |
| VM, posture probe, event log | Ismail | ismail |
| `deviceAttestation`, vault append | Sandrani | sandrani |
| `rotateMissionKey` | Tejas | tejas |

This screen is the **second-to-last** to build (ahead of only secure chat). It depends on Identity provisioning being live and at least the `MissionRepository` flow working.

## Build order

1. **Ismail** writes `SecurityViewModel` against fully stubbed posture (return Pass/Pass/Pass with hardcoded protocol "Signal v3.4").
2. **Yashwanth** ships `SecurityContent` with all four sections rendering.
3. **Sandrani** lands `deviceAttestation()` — posture grid becomes real.
4. **Tejas** lands `rotateMissionKey()` — ROTATE NOW becomes functional.
5. **Ismail** wires `SecurityEventLog` and pipes events from each module's call sites.
6. **Sandrani** + **Ismail** wire ExportAuditLog through SAF.

## Done criteria

- [ ] Identity card shows real callsign and hardware key alias from `IdentityManager`.
- [ ] StrongBox status reflects `KeyguardManager`/keystore probe truthfully — no hardcoded ✓.
- [ ] Tapping "ROTATE NOW" on a mission key calls `cryptoEngine.rotateMissionKey` and a new event appears in the log.
- [ ] VERIFY ALL re-runs probes and updates the grid in <1s perceived.
- [ ] Export audit log produces a non-empty encrypted JSON file at the user-chosen URI.
- [ ] Compose UI test asserts the posture grid renders and tapping rotate updates the event list.
