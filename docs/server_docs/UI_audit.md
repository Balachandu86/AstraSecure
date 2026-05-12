# UI Production-Readiness Audit — AstraSecure

**Date:** 2026-05-03  
**Scope:** All Compose screens, ViewModels, and route wiring  
**Method:** Static analysis of every UI file against production-readiness criteria:  
— no demo/seed data visible in release  
— no silent failures  
— no misleading status indicators  
— no dead UI elements  
— no layout/rendering defects  

---

## Summary Table

| ID | Priority | Area | Issue | Status |
|----|----------|------|-------|--------|
| UI-01 | **P0** | Schema / Data | Schema not seeded in release — ranks, categories, types all empty | **Fixed** |
| UI-02 | **P0** | Missions | No mission-creation flow; release starts with empty list | **Fixed** |
| UI-03 | **P0** | ChannelList | Every user gets max (CHIEF) rank — access control bypassed | **Fixed** |
| UI-04 | **P0** | Chat | Clearance lookup uses hardcoded `"user_local"` not provisioned UUID | **Fixed** |
| UI-05 | **P0** | Admin | Admin clearance operations hardcoded to `"user_local"` | **Fixed** |
| UI-06 | **P0** | Missions | Dashboard always shows "SECURE CONNECTION" / UPLINK_04 regardless of server state | **Fixed** |
| UI-07 | **P1** | Chat | Composer footer "LINK ACTIVE: 100% SIGNAL" is hardcoded | **Fixed** |
| UI-08 | **P1** | Chat | SessionBanner "SECURE SESSION ESTABLISHED" shows unconditionally before handshake | **Fixed** |
| UI-09 | **P1** | Security | INTEGRITY always shows "LOCAL_ONLY" (amber warning) regardless of actual state | **Fixed** |
| UI-10 | **P1** | Security | Protocol version hardcoded "Signal v3.4" | **Fixed** |
| UI-11 | **P1** | Shell | Profile icon in top bar is a dead tap target (`clickable { }`) | **Fixed** |
| UI-12 | **P1** | Missions | Three fabricated `[SYSTEM]` log lines emitted when event log is empty | **Fixed** |
| UI-13 | **P1** | Chat | ChatViewModel falls back to callsign string as Signal address if identity is null | **Fixed** |
| UI-14 | **P1** | Panic | Panic screen shows "AES-XTS-512" — wrong cipher (app uses AES-256-GCM) | **Fixed** |
| UI-15 | **P1** | Chat | SENT→DELIVERED transitions on a hardcoded 200 ms delay, not a real ACK | **Fixed** |
| UI-16 | **P1** | Tools | Tools footer says "SECURE_LINK_ESTABLISHED" — tool runs entirely locally | **Fixed** |
| UI-17 | **P2** | MissionCard | Button text renders `> > OPEN_MISSION` (double `>` prefix) | **Fixed** |
| UI-18 | **P2** | ChannelList | NewChannelSheet silently does nothing when category list is empty | **Fixed** |
| UI-19 | **P2** | Tools | Top bar shows placeholder callsign "SECURE_TOOLS_V4.0" | **Fixed** |
| UI-20 | **P2** | Shell | AstraTopBar default callsign "GHOST_OPS_09" visible briefly before ViewModel resolves identity | **Fixed** |

---

## Detailed Findings

---

### UI-01 · P0 — Schema not seeded in release builds

**File:** `AstraApp.kt:24`  
```kotlin
} else if (BuildConfig.DEBUG) { SeedData.seed(container.store) }
```

**What breaks:**  
- `RankRepository`, `ChannelCategoryRepository`, `MessageCategoryRepository`, `MissionTypeRepository` are all empty in release.  
- Channel creation in `NewChannelSheet` fails silently (`categoryId = ""` → early return in ViewModel).  
- Chat crashes to `ChatUiState.Error("> NO_MESSAGE_CATEGORIES")` because `messageCategoryRepository.categories.value` is empty.  
- `ChannelListViewModel` rank check produces `userRank = null` → every channel shows LOCKED and "NO CLEARANCE ASSIGNED".  
- Admin Console tabs for ranks, categories, types show empty lists with no way to create the first entry in production.

**Root cause:** `SeedData.seed()` populates both schema rows (ranks, categories, types — which should always exist) and demo rows (missions, channels — which should be debug-only). They are not separated.

**Fix:** Extract schema seeding into a separate function `SeedData.seedSchema(store)` gated by `store.ranks.value.isEmpty()` (idempotent, runs in both debug and release). Keep demo missions/channels inside the existing `BuildConfig.DEBUG` block.

---

### UI-02 · P0 — No mission creation flow

**Files:** `MissionsScreen.kt`, `MissionsViewModel.kt`

**What breaks:**  
- `MissionsUiState.Empty` is handled — it shows "No missions assigned. Await further orders." — but there is no FAB, button, or any affordance to create a mission.  
- In release with no seed data, the app is permanently stuck on the empty state with no path forward.  
- Missions are the root container for all channels and communications; without them, 100% of the app's core functionality is unreachable.

**Fix:** Add a `CreateMission` bottom sheet (mirror `NewChannelSheet` design pattern) with fields: name, description, mission type selector, classification. Add a FAB to `MissionsScreen` visible in the `Content` and `Empty` states. Wire through `MissionsViewModel.createMission()` calling `missionRepository.create(...)`.

---

### UI-03 · P0 — ChannelListViewModel gives every user max clearance

**File:** `ChannelListRoute.kt:42`  
```kotlin
val userRank = ranks.maxByOrNull { it.level }  // "Assign user max clearance (CHIEF level 9)"
```

**What breaks:**  
- Every operator sees every channel as accessible regardless of their actual rank.  
- The "YOUR RANK: X (LVL Y)" display in the channel list header always shows the highest rank (CHIEF / level 9).  
- The access control system exists in the data model but is completely bypassed in the UI.

**Fix:**  
```kotlin
val userId = container.identityManager.getUserIdentity()?.id ?: ""
val userRank = container.clearanceRepository.clearanceFor(userId, missionId).value
    ?: ranks.minByOrNull { it.level }  // fallback to lowest rank if no clearance assigned
```

---

### UI-04 · P0 — ChatViewModel clearance lookup uses "user_local"

**File:** `ChatRoute.kt:86`  
```kotlin
userClearanceLevel = container.clearanceRepository.clearanceFor("user_local", missionId).value?.level ?: 0
```

**What breaks:**  
- No clearance entry ever exists for the literal string `"user_local"` (provisioned users have UUID identifiers).  
- Result: `userClearanceLevel = 0` for all operators.  
- All non-zero `minClearanceToPost` channels show the red "CHANNEL READ-ONLY" bar, blocking the composer.  
- Message category picker shows all categories as locked (MIN LVL > 0).  
- The chat screen renders as read-only for all users in practice.

**Fix:** Replace `"user_local"` with the actual provisioned userId:
```kotlin
val userId = container.identityManager.getUserIdentity()?.id ?: ""
userClearanceLevel = container.clearanceRepository.clearanceFor(userId, missionId).value?.level ?: 0
```
If userId is empty (identity not provisioned), navigate back with an error rather than silently opening a broken chat.

---

### UI-05 · P0 — AdminConsoleScreen clearance tab hardcodes "user_local"

**File:** `AdminConsoleScreen.kt:283`  
```kotlin
val userId = "user_local"
```

**What breaks:**  
- All clearance reads and writes in the Admin Console clearance tab target `"user_local"` instead of the logged-in user's UUID.  
- Granting or revoking clearance through this UI has no effect on any actual provisioned identity.

**Fix:** Pass the provisioned userId down from `AdminViewModel` → `AdminUiState` → `AdminConsoleScreen`. Load it via `container.identityManager.getUserIdentity()?.id` in the ViewModel's `init`.

---

### UI-06 · P0 — Missions dashboard always shows "SECURE CONNECTION"

**Files:** `MissionsViewModel.kt:103–105`, `MissionsScreen.kt` (`DashboardSummaryBar`)

```kotlin
signal = if (degraded.isEmpty()) SignalStrength.STABLE else SignalStrength.DEGRADED,
uplinkId = "UPLINK_04",
```

**What breaks:**  
- `degraded` only tracks `CRYPTO`/`IDENTITY`/`METADATA` subsystem failures — it does not include WebSocket connectivity.  
- A device with no network connection (or server down) shows a pulsing green "SECURE CONNECTION" dot and "UPLINK_04".  
- In a security-focused app this is actively deceptive — operators cannot tell whether they are actually connected to the relay.

**Fix:**  
1. Expose `StateFlow<Boolean>` connection state from `SignalServerClient` (already has `isConnected` implied by WebSocket state, just needs to be surfaced via a flow on `MessageTransport`).  
2. `AppContainer` exposes it. `MissionsViewModel` collects it alongside missions.  
3. Set `signal = if (serverConnected && degraded.isEmpty()) SignalStrength.STABLE else SignalStrength.DEGRADED`.  
4. Set `uplinkId` to the actual server URL domain or "UPLINK_OFFLINE" when disconnected.

---

### UI-07 · P1 — Chat composer footer hardcoded "LINK ACTIVE: 100% SIGNAL"

**File:** `ChatScreen.kt:618`  
```kotlin
"LINK ACTIVE: 100% SIGNAL  //  DATA_WIPE_ON_PAUSE"
```

**Fix:** Replace with server connection state. When connected show "LINK ACTIVE"; when disconnected show "LINK OFFLINE // MSGS QUEUED" (or similar). Wire to the same `StateFlow<Boolean>` introduced for UI-06.

---

### UI-08 · P1 — SessionBanner shows before Signal session is established

**File:** `ChatScreen.kt:191–213`, `ChatRoute.kt:139`

The `SessionBanner` ("SECURE SESSION ESTABLISHED // PROTOCOL 3.4") is always rendered as part of `ChatBody`. `addSystemItem("SECURE SESSION ESTABLISHED // PROTOCOL 3.4")` is called unconditionally in `initializeChat()` before any X3DH handshake or SKDM exchange occurs.

**Fix:** The `ChatItem.System` event already captures this message. Remove the static `SessionBanner` composable from `ChatBody` — the system notification in the message stream is sufficient and only appears after the session is actually initialised. Delete `SessionBanner()` call at `ChatScreen.kt:144`.

---

### UI-09 · P1 — Security screen INTEGRITY always "LOCAL_ONLY" (amber)

**File:** `SecurityRoute.kt:49`  
```kotlin
PostureInfoCell("INTEGRITY", "LOCAL_ONLY", AstraTheme.Secondary, ...)
```

**Fix:** Derive from actual server registration state:
```kotlin
val integrityLabel = if (container.signalStore.loadJwt().isNotEmpty()) "SERVER_VERIFIED" else "LOCAL_ONLY"
val integrityColor = if (integrityLabel == "SERVER_VERIFIED") AstraTheme.Tertiary else AstraTheme.Secondary
```

---

### UI-10 · P1 — Protocol version hardcoded "Signal v3.4"

**File:** `SecurityRoute.kt:49`, `_state.value = SecurityUiState.Content(..., "Signal v3.4", ...)`

**Fix:** Replace with `"Signal Protocol"` (no version number) or expose the actual libsignal version from a constant if available. Hardcoding an internal version string will go stale and can cause confusion during security reviews.

---

### UI-11 · P1 — Profile icon is a dead tap target

**File:** `AstraAppShell.kt:136`  
```kotlin
Modifier.clickable { }
```

**Fix:** Either (a) remove the `clickable` modifier entirely until a profile screen exists, or (b) show a `Snackbar("PROFILE // NOT YET ASSIGNED")` to give the user feedback. An invisible tap target with no effect violates basic UX expectations and could confuse users tapping it repeatedly.

---

### UI-12 · P1 — Missions dashboard fabricates system log entries

**File:** `MissionsViewModel.kt:111–116`  
```kotlin
listOf(
    "> [SYSTEM]: MISSION DATA LOADED FROM LOCAL STORE",
    "> [SECURE]: ALL KEYS VERIFIED",
    "> [SIGNAL]: OPERATIONAL LIMITS CLEAR",
)
```

These three strings are emitted whenever `securityEventLog.recent(3)` returns an empty list (i.e., on every cold start before any real events). They appear in the dashboard HUD as if they are real security attestation events.

**Fix:** Remove the fallback entirely. If the log is empty, show nothing (or a subtle `"> [SYSTEM]: NO EVENTS RECORDED"` in a dimmed colour). Real events populate within seconds of app startup.

---

### UI-13 · P1 — ChatViewModel falls back to callsign string as Signal protocol address

**File:** `ChatRoute.kt:85`  
```kotlin
userId = identity?.id ?: callsign   // fall back to callsign if not yet provisioned
```

**What breaks:** If `getUserIdentity()` returns null at chat-open time (e.g. race condition during startup, or a bug in provisioning), the callsign string (e.g. `"GHOST_09"`) is used as the Signal protocol address. The server routes messages by UUID, so this causes all outgoing messages to fail silently and incoming routing to break.

**Fix:** If `identity` is null, immediately emit `ChatUiState.Error("> IDENTITY_NOT_PROVISIONED")` and return from `initializeChat()`. Do not fall back to the callsign.

---

### UI-14 · P1 — Panic screen encryption label is wrong cipher

**File:** `PanicScreen.kt:80`  
```kotlin
"STATUS: STANDBY  •  ENCRYPTION: AES-XTS-512"
```

AES-XTS-512 is a disk-encryption mode used in full-disk encryption (FDE). The app uses AES-256-GCM for message encryption and Android Keystore-backed AES for identity keys. This label is factually incorrect in a security-focused context.

**Fix:** Change to `"ENCRYPTION: AES-256-GCM"` to match actual implementation.

---

### UI-15 · P1 — DeliveryState.DELIVERED on 200 ms hardcoded delay

**File:** `ChatRoute.kt:222–226`  
```kotlin
if (deliveryState == DeliveryState.SENT) {
    delay(200)
    updateOutgoing(outgoingId, DeliveryState.DELIVERED)
}
```

**What breaks:** `DELIVERED` is shown 200 ms after the HTTP send completes, regardless of whether the recipient device actually received the message. The relay server returns HTTP 200 on successful queue; `DELIVERED` implies recipient ACK which the current architecture does not implement.

**Fix (minimal):** Remove the `DELIVERED` transition entirely for now — SENT is the honest terminal state. If server-side ACKs are implemented later, `DELIVERED` can be restored. Displaying a false delivery status in a secure messenger is a trust/security concern.

---

### UI-16 · P1 — Tools screen footer claims a secure link is established

**File:** `ToolsScreen.kt:258`  
```kotlin
"> SECURE_LINK_ESTABLISHED // DATA_WIPE_ON_PAUSE"
```

The Tools screen runs entirely locally (Base64, SHA-256, AES-256-GCM with a user-supplied key). There is no server connection on this screen.

**Fix:** Change to `"> LOCAL_ONLY // DATA_WIPE_ON_PAUSE"` to accurately describe the operating mode.

---

### UI-17 · P2 — MissionCard button renders `> > OPEN_MISSION`

**File:** `UiComponents.kt:213`  
```kotlin
AstraButtonPrimary(text = "> $actionText", ...)  // actionText = "> OPEN_MISSION"
```
`AstraButtonPrimary` also uppercases via `text.uppercase()`, so the rendered output is `> > OPEN_MISSION`.

**Fix:** In `MissionsScreen.kt` where `actionText` is set, remove the leading `> `:
```kotlin
actionText = "OPEN_MISSION"  // AstraButtonPrimary already adds ">" semantically via its internal styling
```
Or remove the `"> "` prefix from the `AstraButtonPrimary` implementation and add it only at call sites consistently.

---

### UI-18 · P2 — NewChannelSheet silently does nothing when categories are empty

**File:** `ChannelListScreen.kt:228,300–302`

When `categories` is empty (release build with UI-01 unfixed), `selectedCategoryId = ""`. The CREATE button calls `onCreate(name, "", "")` but `ChannelListViewModel.createChannel()` returns early on `cat = categories.find { it.id == categoryId } ?: return` with no UI feedback.

**Fix:** In `NewChannelSheet`, if `categories.isEmpty()`, replace the category section with:
```
> NO CATEGORIES AVAILABLE — CONTACT ADMIN
```
and disable the CREATE button. Also fix UI-01 so this is not reachable in production.

---

### UI-19 · P2 — Tools top bar shows placeholder version string

**File:** `ToolsRoute.kt:78`  
```kotlin
AstraTopBar(callsign = "SECURE_TOOLS_V4.0", clearanceLabel = "LOCAL_MODE")
```

"SECURE_TOOLS_V4.0" reads as a hardcoded placeholder. It will go stale and looks unprofessional in a release build.

**Fix:** Use the actual `BuildConfig.VERSION_NAME` or a simpler `"SECURE_TOOLS"` without a version number:
```kotlin
AstraTopBar(callsign = "SECURE_TOOLS", clearanceLabel = "LOCAL_MODE")
```

---

### UI-20 · P2 — Default top-bar callsign "GHOST_OPS_09" flickers before identity loads

**File:** `AstraAppShell.kt:79`  
```kotlin
fun DefaultAstraTopBar(callsign: String = "OPERATOR", ...)
fun AstraTopBar(callsign: String = "GHOST_OPS_09", ...)
```

`MissionsViewModel` initializes `_callsign = MutableStateFlow("OPERATOR")` and updates it after an IO call. However, `AstraTopBar` has a default of `"GHOST_OPS_09"` which can appear briefly if a screen uses `AstraTopBar(...)` without providing a callsign before the ViewModel resolves.

**Fix:** Change `AstraTopBar` default from `"GHOST_OPS_09"` to `"OPERATOR"` to match `DefaultAstraTopBar` and `MissionsViewModel`'s initial value. The "GHOST_OPS_09" string is clearly leftover from mockup.

---

## Remediation Plan

### Sprint 1 — Core Data + Access Control  *(est. 1–2 days)*

Critical path: without schema data, the entire app is non-functional in release.

| # | Task | Files | Notes |
|---|------|-------|-------|
| 1 | **UI-01**: Separate schema seed from demo seed; call schema seed in release | `SeedData.kt`, `AstraApp.kt` | Gate schema seed on `store.ranks.value.isEmpty()` for idempotency |
| 2 | **UI-03**: ChannelListViewModel — use real clearance from identity | `ChannelListRoute.kt:42` | Replace `maxByOrNull` with actual `clearanceFor(userId, missionId)` |
| 3 | **UI-04**: ChatViewModel — use provisioned userId for clearance | `ChatRoute.kt:86` | Also fix null-identity early exit (UI-13) in the same pass |
| 4 | **UI-05**: AdminConsoleScreen — inject provisioned userId | `AdminConsoleScreen.kt:283`, `AdminRoute.kt` | Add `userId` to `AdminUiState` |

### Sprint 2 — Mission Creation  *(est. 1–2 days)*

Without this, release builds are permanently empty.

| # | Task | Files | Notes |
|---|------|-------|-------|
| 5 | **UI-02**: Add CreateMission bottom sheet + FAB to MissionsScreen | `MissionsScreen.kt`, `MissionsViewModel.kt`, `UiComponents.kt` | Copy `NewChannelSheet` pattern; requires mission type selector |

### Sprint 3 — Connectivity Indicators  *(est. 0.5–1 day)*

Deceptive connectivity indicators are a trust/security concern for this app type.

| # | Task | Files | Notes |
|---|------|-------|-------|
| 6 | **UI-06**: Expose WebSocket connection state; wire to dashboard signal indicator | `SignalServerClient.kt`, `MessageTransport.kt`, `AppContainer.kt`, `MissionsViewModel.kt`, `MissionsScreen.kt` | Add `val connectionState: StateFlow<Boolean>` to `MessageTransport` interface |
| 7 | **UI-07**: Wire chat composer footer to connection state | `ChatScreen.kt`, `ChatRoute.kt` | Reuse same flow from Sprint 3 step 6 |
| 8 | **UI-09**: Security INTEGRITY — derive from JWT presence | `SecurityRoute.kt` (SecurityViewModel) | One-liner: `signalStore.loadJwt().isNotEmpty()` |

### Sprint 4 — Label Accuracy + Dead Elements  *(est. 0.5 day)*

Fixes that are each 1–5 lines but important for a security product's credibility.

| # | Task | Files | Notes |
|---|------|-------|-------|
| 9 | **UI-08**: Remove static SessionBanner; session event already in message stream | `ChatScreen.kt:144` | Delete `SessionBanner()` call only |
| 10 | **UI-12**: Remove fabricated system log fallback | `MissionsViewModel.kt:111–116` | Replace with empty list |
| 11 | **UI-14**: Fix panic screen cipher label AES-XTS-512 → AES-256-GCM | `PanicScreen.kt:80` | One-line fix |
| 12 | **UI-15**: Remove false DELIVERED delivery state | `ChatRoute.kt:222–226` | Remove the `delay(200)` + DELIVERED block |
| 13 | **UI-16**: Tools footer LOCAL_ONLY | `ToolsScreen.kt:258` | One-line fix |
| 14 | **UI-17**: Fix double `>` in MissionCard | `MissionsScreen.kt` (actionText arg) | Remove leading `> ` from the actionText literal |
| 15 | **UI-18**: NewChannelSheet empty-category guard | `ChannelListScreen.kt` | Show informative message + disable button |
| 16 | **UI-10**: Remove hardcoded protocol version | `SecurityRoute.kt` | Change to `"Signal Protocol"` |
| 17 | **UI-11**: Profile icon — remove dead clickable or add snackbar | `AstraAppShell.kt:136` | Remove `clickable { }` modifier |
| 18 | **UI-19**: Tools top bar version string | `ToolsRoute.kt:78` | `"SECURE_TOOLS"` |
| 19 | **UI-20**: Default top-bar callsign "GHOST_OPS_09" → "OPERATOR" | `AstraAppShell.kt:79` | One-line fix |

---

## Total Gap Count

| Priority | Count |
|----------|-------|
| P0 (production blocker) | 6 |
| P1 (functional / misleading) | 10 |
| P2 (minor / polish) | 4 |
| **Total** | **20** |

P0 items must be resolved before a demo or release build is tested by anyone outside the team. P1 items should be resolved before any external review or presentation. P2 items can be batched into a cleanup pass.
