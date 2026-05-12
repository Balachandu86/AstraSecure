# AstraSecure — Functional Polish Plan

**Project:** AstraSecure (BSc Cybersecurity Capstone — LPU 2025-2026)  
**Date:** 2026-05-07  
**Author:** Tejas Khanna  
**Scope:** Resolve UX/functional gaps identified post Signal-protocol integration

---

## Issues to Fix

### Issue 1 — Demo Missions Still Visible

**Root Cause:**  
`AstraApp.kt:25` has a correct `BuildConfig.DEBUG` guard for *fresh installs*, but once a snapshot
exists from a prior DEBUG run, `loadSnapshot()` at line 21 restores all entities unconditionally —
no build-type check. Demo missions therefore persist across re-runs even in release mode.

**Fix:**  
- Add a `DEMO_MISSION_IDS` set to `SeedData.kt` listing all hardcoded demo mission IDs.  
- In `AstraApp.kt`, after `loadSnapshot()` in non-DEBUG builds, call a new
  `SeedData.stripDemoData(store)` that removes any missions, channels, and messages whose IDs are
  in `DEMO_MISSION_IDS`.  
- In DEBUG builds the demo data is preserved as-is.

**Files:** `AstraApp.kt`, `SeedData.kt`

---

### Issue 2 — Profile Button Has No Handler

**Root Cause:**  
`AstraAppShell.kt:140` — the `IconButton` wrapping `Icons.Default.Person` has no `onClick`
handler. It is purely decorative.

**Fix:**  
1. Create `ProfileScreen.kt` — displays callsign, truncated hardware key ID, provisioned date, and
   a per-mission rank table resolved from `ClearanceAssignment` + `Rank` names.  
2. Create `ProfileRoute.kt` — ViewModel loads identity via `IdentityManager` and joins
   `ClearanceAssignment` entries with mission names and rank names.  
3. Add route constant `AstraRoute.PROFILE = "profile"` and `composable` in the nav graph.  
4. Wire `AstraAppShell` profile `IconButton.onClick` to `navController.navigate(AstraRoute.PROFILE)`.

**Files:** `ProfileScreen.kt` (new), `ProfileRoute.kt` (new), `AstraAppShell.kt`, nav graph

---

### Issue 3 — Key Rotation UI Shows AES Concepts Instead of Signal Controls

**Root Cause:**  
`SecurityRoute.kt:54-66` — `rotateKey()` calls `cryptoEngine.rotateMissionKey()` which updates
`Mission.missionKeyAlias`, an AES-era artifact. Signal is now the crypto layer; per-mission AES
keys no longer exist. `SecurityScreen.kt` renders one `> ROTATE` button per mission in an
"ACTIVE MISSION KEYS" section.

**Fix:**  
- Remove the "ACTIVE MISSION KEYS" section from `SecurityScreen.kt`.  
- Remove `SecurityIntent.RotateKey` and `rotateKey(missionId)` from `SecurityRoute.kt`.  
- Add a new **SIGNAL KEY STATUS** section with three rows:  
  - **Identity Key** — first 8 hex chars of public key fingerprint, labeled `HARDWARE BOUND`,
    read-only.  
  - **Signed Pre-Key** — last rotation timestamp from `SignalKeyManager`; `> ROTATE SPK` button
    calls `SignalKeyManager.rotateSignedPreKey()`.  
  - **One-Time Pre-Keys** — remaining OPK count from `AstraSignalProtocolStore`; `> REPLENISH`
    button calls `SignalKeyManager.replenishPreKeys()`.  
- Expose `signedPreKeyRotatedAt: Long` and `oneTimePreKeyCount: Int` in `SecurityUiState`.

**Files:** `SecurityScreen.kt`, `SecurityRoute.kt`, `Models.kt` (remove `missionKeyAlias`)

---

### Issue 4 — No Mission Owner / Channel Owner

**Root Cause:**  
`Models.kt` — `Mission` and `Channel` data classes have no `createdBy` field. Ownership is
untracked; all channel-creation is ungated.

**Fix:**  
1. Add `createdBy: String = ""` to both `Mission` and `Channel`.  
2. Pass `identityManager.currentUserId()` as `createdBy` in `MissionsViewModel.handle(CreateMission)`
   and `ChannelListRoute.createChannel()`.  
3. Surface owner callsign (resolved from user store) on mission cards in `MissionsScreen.kt` and
   channel rows in `ChannelListScreen.kt`.

**Files:** `Models.kt`, `MissionsViewModel.kt`, `ChannelListRoute.kt`, `MissionsScreen.kt`,
`ChannelListScreen.kt`

---

### Issue 5 — Rank Assignment Is Self-Only, Not Admin-Assignable

**Root Cause:**  
`AdminConsoleScreen.kt:283-360` `ClearanceTab` shows missions for "CURRENT OPERATOR" and fires
`AssignClearance` only for the logged-in user. No path exists to assign a rank to another user.

**Fix:**  
- Redesign `ClearanceTab` as a mission → participant matrix: for each mission, list all
  `participantIds` with their current rank and a dropdown to change it.  
- `AssignClearance(userId, missionId, rankId)` intent already exists — just needs the target
  `userId` to come from the participant list instead of self.

**Files:** `AdminConsoleScreen.kt`, `AdminRoute.kt`

---

## Gaps Found Beyond the Four Issues

### Gap A — No Participant Management UI

`Mission.participantIds` exists but there is no UI to add a user to a mission after creation. The
creator is implicitly the only participant, making rank assignment useless for multi-user flows.

**Fix:** Add "Add Participant" action to mission detail or admin console (mission owner / CHIEF rank
only). Takes a userId, appends to `participantIds`, creates a default `ClearanceAssignment` at
OBSERVER level.

**Files:** `MissionsScreen.kt` or `AdminConsoleScreen.kt`, `MissionRepository.kt`

---

### Gap B — Channel Creation Is Unguarded

`ChannelListRoute.kt:78-95` — `createChannel()` has no rank check. Any participant of any rank can
create channels.

**Fix:** Gate the create-channel button/FAB behind `userRank.level >= OPERATIVE_LEVEL (5)` or
restrict to mission owner only.

**Files:** `ChannelListRoute.kt`, `ChannelListScreen.kt`

---

### Gap C — `missionKeyAlias` Is a Dead Field

`Mission.missionKeyAlias` was the AES session key alias. With Signal, per-mission AES keys no
longer exist. The field is still in snapshots and seed data.

**Fix:** Remove from `Mission` data class and all creation/snapshot paths. Add a snapshot migration
guard in `loadSnapshot()` to handle old snapshots gracefully (field is nullable/ignored on
deserialisation).

**Files:** `Models.kt`, `MissionsViewModel.kt`, `SeedData.kt`, `AppContainer.kt` (snapshot migration)

---

### Gap D — Admin Console Navigation Is Unclear

`AdminRoute.kt` exists and is wired into the nav graph, but no visible affordance in the app shell
directs users there.

**Fix:** Verify nav entry in `AstraAppShell.kt`; if admin is only reachable via a hidden gesture,
add a visible entry in the nav drawer or tools screen for users with CHIEF-level clearance.

**Files:** `AstraAppShell.kt`, nav graph, `ToolsRoute.kt`

---

## Implementation Order

| Priority | Change | Files Affected |
|---|---|---|
| 1 | Fix demo missions snapshot bleed | `AstraApp.kt`, `SeedData.kt` |
| 2 | Add `createdBy` to Mission + Channel | `Models.kt`, `MissionsViewModel.kt`, `ChannelListRoute.kt`, `MissionsScreen.kt`, `ChannelListScreen.kt` |
| 3 | Replace AES key rotation with Signal key controls | `SecurityScreen.kt`, `SecurityRoute.kt`, `Models.kt` |
| 4 | Profile screen + wire button | `ProfileScreen.kt` (new), `ProfileRoute.kt` (new), `AstraAppShell.kt`, nav graph |
| 5 | Expand ClearanceTab to multi-user assignment | `AdminConsoleScreen.kt` |
| 6 | Add participant management | `MissionsScreen.kt`, `AdminConsoleScreen.kt`, `MissionRepository.kt` |
| 7 | Gate channel creation by rank | `ChannelListRoute.kt`, `ChannelListScreen.kt` |

---

## Notes

- `missionKeyAlias` removal (Gap C) is bundled with Issue 3 since both touch the same AES-era
  concepts in `Models.kt` and `SecurityRoute.kt`.
- Participant management (Gap A) is a prerequisite for multi-user rank assignment (Issue 5) to be
  meaningful in practice — implement them together.
- Profile screen (Issue 2) depends on clearance data being queryable; ensure `ClearanceRepository`
  is accessible from the Profile ViewModel.
