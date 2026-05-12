# AstraSecure — Polish Implementation Walkthrough

**Date:** 2026-05-07  
**Branch:** master  
**Plan reference:** `docs/ASTRASECURE_POLISH_PLAN.md`

---

## What Was Implemented

### 1 — Demo Missions Snapshot Bleed (FIXED)

**Problem:** `AstraApp.kt:25` guarded demo data correctly on fresh installs, but an existing
snapshot from a prior DEBUG session restored demo missions unconditionally in any build type.

**Changes:**

| File | Change |
|---|---|
| `shared/data/SeedData.kt` | Added `DEMO_MISSION_IDS` set (`"OSS-9921"`, `"TFA-0042"`, `"VR-8810"`, `"SP-3301"`). Added `stripDemoData(store)` which filters out matching missions and their channels. |
| `AstraApp.kt` | After `loadSnapshot()`, if `!BuildConfig.DEBUG`, calls `SeedData.stripDemoData(container.store)`. |

**Result:** Running a release build (or clearing snapshot state) will now show an empty mission
queue instead of the four demo missions. DEBUG builds are unchanged.

---

### 2 — Profile Button Now Navigates (FIXED)

**Problem:** `AstraAppShell.kt` rendered a Person icon with no click handler.

**Changes:**

| File | Change |
|---|---|
| `ui/AstraAppShell.kt` | Added `onProfileClick: (() -> Unit)? = null` parameter to `AstraTopBar`. Profile icon Box is now `clickable` when the callback is non-null; icon tint dims when no callback is wired. |
| `ux/MissionsRoute.kt` | Added `onProfileClick: () -> Unit = {}` parameter, passed to `AstraTopBar`. |
| `ux/AstraNavGraph.kt` | Passes `onProfileClick = { navController.navigate("profile") }` to `MissionsRoute`. Added `composable("profile")` entry that hosts `ProfileRoute`. |
| `ui/ProfileScreen.kt` *(new)* | Displays: operator avatar + callsign banner, Identity section (callsign, truncated hardware key, provisioned date), Mission Clearances section (per-mission rank table sorted by level descending). |
| `ux/ProfileRoute.kt` *(new)* | `ProfileViewModel` reads `IdentityManager.getUserIdentity()`, joins `store.clearanceAssignments` with mission and rank names, emits `ProfileUiState.Content`. `ProfileRoute` composable with back-navigation top bar. |

**Result:** Tapping the Person icon on the Missions screen navigates to a full operator profile
page showing identity metadata and mission rank assignments.

---

### 3 — Key Rotation UI Replaced with Signal Key Controls (FIXED)

**Problem:** `SecurityScreen` showed "ACTIVE MISSION KEYS" with per-mission AES key aliases and
a `> ROTATE` button that called `cryptoEngine.rotateMissionKey()` — an AES-era function.
Signal protocol uses no per-mission AES keys.

**Changes:**

| File | Change |
|---|---|
| `ui/SecurityScreen.kt` | Removed `MissionKeyEntry` data class. Removed `missionKeys` from `SecurityUiState.Content`. Added `SignalKeyStatus(identityFingerprint, spkRotatedAtLabel, opkCount)` data class. Added `signalKeyStatus` field. Removed `SecurityIntent.RotateKey`. Added `SecurityIntent.RotateSPK` and `SecurityIntent.ReplenishOPKs`. Replaced "ACTIVE MISSION KEYS" UI with "SIGNAL KEY STATUS" section showing three rows: identity key fingerprint (read-only, HARDWARE BOUND label), signed pre-key with rotation timestamp + `> ROTATE SPK` action, one-time pre-keys with count + `> REPLENISH` action (count turns red below 10). |
| `ux/SecurityRoute.kt` | Removed `rotateKey()`. Added `buildSignalKeyStatus()` which reads identity key fingerprint from `signalStore.getIdentityKeyPair()`, SPK rotation time from `signalStore.getLastSpkRotationMs()`, OPK count from `signalStore.preKeyCount()`. Added `rotateSPK()` coroutine calling `signalKeyManager.rotateSignedPreKey()`. Added `replenishOPKs()` coroutine calling `signalKeyManager.replenishPreKeys()`. Both refresh state and emit audit log entries. |

**Result:** Security screen now shows cryptographically relevant Signal key state. Operators can
manually trigger a signed pre-key rotation or replenish one-time pre-keys directly from the UI.

---

### 4 — Mission Owner and Channel Owner (IMPLEMENTED)

**Problem:** `Mission` and `Channel` had no `createdBy` field; ownership was untracked.

**Changes:**

| File | Change |
|---|---|
| `shared/Models.kt` | Added `createdBy: String = ""` to `Mission` (defaulted so old snapshots deserialise cleanly). Added `createdBy: String = ""` to `Channel`. |
| `shared/data/entity/MissionRepository.kt` | Added `createdBy: String = ""` to interface and `InMemoryMissionRepository.create()`. Creator is now added to `participantIds` automatically. Added `addParticipant(id, userId)` method. |
| `shared/data/entity/ChannelRepository.kt` | Added `createdBy: String = ""` to interface and `InMemoryChannelRepository.create()`. |
| `ux/MissionsViewModel.kt` | `handle(CreateMission)` now fetches `identityManager.getUserIdentity()?.id` and passes it as `createdBy`. Also fixed `DashboardSummary.encryption` label from `"AES-256"` to `"SIGNAL E2E"`. |
| `ux/ChannelListRoute.kt` | `createChannel()` fetches user ID and passes as `createdBy`. |
| `ui/MissionsScreen.kt` | `MissionListItem` now shows an `OWNER:` row (Person icon + truncated userId) below the status chips when `mission.createdBy` is non-empty. |

**Result:** All newly-created missions and channels record their creator's user ID. The missions
list displays the owner ID on each card.

---

### 5 — Rank Assignment: Multi-User (FIXED)

**Problem:** `ClearanceTab` in AdminConsoleScreen only showed and assigned clearance for the
currently logged-in operator — there was no way to assign a rank to another user.

**Changes:**

| File | Change |
|---|---|
| `ui/AdminConsoleScreen.kt` | `ClearanceTab` redesigned as a per-mission accordion. Each mission header shows participant count. When expanded, renders one `ParticipantClearanceRow` per participant in `mission.participantIds`. Each row shows the participant's ID (highlighted "YOU" for the local user), current rank, and an expand-to-change rank picker. `AssignClearance` intent is fired with the participant's userId (not hardcoded to self). Added `AdminIntent.AddParticipant`. Added "Add Participant" input row at the bottom of each expanded mission: text field for userId + `> ADD` button. |
| `ux/AdminRoute.kt` | Added `AdminIntent.AddParticipant` handler which calls `missionRepository.addParticipant()`. |

**Result:** An admin can now see all participants per mission, assign any rank to any participant,
and add new participants by pasting a userId.

---

### 6 — Channel Creation Gated by Rank (FIXED)

**Problem:** The "NEW INTEL" FAB in `ChannelListScreen` was visible and functional for all
participants regardless of rank.

**Changes:**

| File | Change |
|---|---|
| `ui/ChannelListScreen.kt` | Added `canCreateChannel: Boolean = false` to `ChannelListUiState.Content`. The "NEW INTEL" FAB is now rendered only when `canCreateChannel` is true. |
| `ux/ChannelListRoute.kt` | After building state, computes `canCreate = (userRank?.level ?: 0) >= 5 \|\| mission.createdBy == userId`. Passes this as `canCreateChannel`. |

**Result:** Only operators with OPERATIVE rank (level ≥ 5) or the mission owner can create new
channels. OBSERVER-rank users see the channel list but not the create FAB.

---

## What Is Left / Known Gaps

### Not Implemented in This Pass

| Gap | Notes |
|---|---|
| **Owner display uses userId, not callsign** | Mission cards show the raw userId (truncated) for the owner. A future pass should resolve this to the callsign by fetching from the server or caching identity records in `InMemoryStore`. |
| **Profile button only wired on Missions tab** | The `onProfileClick` callback is only passed down through `MissionsRoute`. Routes like `SecurityRoute`, `ToolsRoute`, `ChannelListRoute`, and `ChatRoute` still show the profile icon but it does nothing (dims to 40% opacity as a visual indicator). Wiring these requires adding `onProfileClick` to each route signature. |
| **Participant resolution in ClearanceTab** | The new `ParticipantClearanceRow` shows truncated userIds for non-local users. Resolving these to human-readable callsigns requires a user lookup endpoint or a local cache of known identities (not yet in `InMemoryStore`). |
| **`missionKeyAlias` field still in `Mission` model** | The AES-era `missionKeyAlias` field is kept to avoid breaking `ChatRoute.kt` and `MessageRepository.send()` which still reference it. It now defaults to `""` and is functionally inert for Signal. A full cleanup would remove it from `Mission`, `MessageRepository.send()`, and the `tickRotationTimer()` in `ChatRoute`. |
| **Participant management only via Admin Console** | There's no in-mission UI to invite participants — the only entry point is Admin Console → CLEARANCE → mission expand → ADD. A future pass could add an "Invite" sheet on the mission detail page. |
| **Admin Console navigation gap (Gap D)** | The Admin Console is navigable via the gear icon on the Security screen, but no other app shell surface links to it. CHIEF-rank users have no obvious path to the admin console from the main flow. |
| **Profile callsign editing** | `ProfileScreen` is read-only. `IdentityManager` doesn't currently expose an `updateDisplayName()` method. |

---

## File Change Summary

| File | Status | Description |
|---|---|---|
| `AstraApp.kt` | Modified | stripDemoData on release snapshot load |
| `shared/Models.kt` | Modified | `createdBy` on Mission + Channel, `missionKeyAlias` defaulted |
| `shared/data/SeedData.kt` | Modified | `DEMO_MISSION_IDS` + `stripDemoData()` |
| `shared/data/entity/MissionRepository.kt` | Modified | `createdBy` param, `addParticipant()` |
| `shared/data/entity/ChannelRepository.kt` | Modified | `createdBy` param |
| `ux/MissionsViewModel.kt` | Modified | Pass `createdBy`, fix encryption label |
| `ux/MissionsRoute.kt` | Modified | `onProfileClick` parameter |
| `ux/ChannelListRoute.kt` | Modified | `createdBy` on create, `canCreateChannel` compute |
| `ux/SecurityRoute.kt` | Modified | Signal key status, `rotateSPK()`, `replenishOPKs()` |
| `ux/AdminRoute.kt` | Modified | `AddParticipant` intent handler |
| `ux/AstraNavGraph.kt` | Modified | Profile route + `onProfileClick` on MissionsRoute |
| `ux/ProfileRoute.kt` | **New** | ProfileViewModel + ProfileRoute composable |
| `ui/SecurityScreen.kt` | Modified | `SignalKeyStatus`, Signal key section, new intents |
| `ui/AstraAppShell.kt` | Modified | `onProfileClick` on `AstraTopBar` |
| `ui/AdminConsoleScreen.kt` | Modified | Multi-user ClearanceTab, AddParticipant intent |
| `ui/MissionsScreen.kt` | Modified | Owner chip on mission cards |
| `ui/ChannelListScreen.kt` | Modified | `canCreateChannel` field, gated FAB |
| `ui/ProfileScreen.kt` | **New** | Full operator profile screen |
| `docs/ASTRASECURE_POLISH_PLAN.md` | **New** | Plan document |
