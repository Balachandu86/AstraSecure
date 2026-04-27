# Screen 02 — Missions List

**Visual reference:** [missions_list/screen.png](../../../design/stitch_astrasecure_north_star_document/missions_list/screen.png) and [missions_list/code.html](../../../design/stitch_astrasecure_north_star_document/missions_list/code.html).

**Current state:** Compose draft at [MissionsScreen.kt](../../../app/src/main/java/com/explo/capstone/ui/MissionsScreen.kt). Looks correct visually; data is hardcoded; no VM wiring; duplicates `MissionStatus` and `Mission` types that already live in `shared/`.

## Purpose

Landing screen of the MISSIONS tab. Shows the operator's mission queue with status, classification, last activity, and a context-aware action button. Entering a mission opens its [Channel List](03_channel_list.md).

## Refactors required (priority — break dependency on hardcoded data)

1. Remove the local `enum class MissionStatus`, `enum class MissionClassification`, and `data class Mission` from `MissionsScreen.kt` (lines 69–88).
2. Use `shared.Mission` and `shared.MissionStatus`. **Replace `MissionClassification` entirely** with the dynamic `MissionType` record (see [03_DATA_LAYER.md](../03_DATA_LAYER.md) and [08_admin_console.md](08_admin_console.md)). Each Mission carries a `typeId: String` referencing a record from `MissionTypeRepository`.
3. Move `sampleMissions` out — it goes into `SeedData.kt` (see [03_DATA_LAYER.md](../03_DATA_LAYER.md)).
4. Extract `AstraTopBar`, `AstraBottomBar`, `NavItem` from this file into `ui/AstraAppShell.kt` so other screens reuse them.
5. Split file: `ui/MissionsContent.kt` (stateless Composables) + `ux/MissionsRoute.kt` (VM hookup, navigation callbacks).

## States

```kotlin
sealed interface MissionsUiState {
    data object Loading : MissionsUiState
    data class Content(
        val missions: List<MissionRow>,                  // each carries its resolved MissionType
        val summary: DashboardSummary,
        val systemLogs: List<String>,
        val degraded: Set<DegradedSubsystem> = emptySet(),
    ) : MissionsUiState
    data class Empty(val reason: String) : MissionsUiState
    data class Error(val message: String) : MissionsUiState
}

data class MissionRow(
    val mission: Mission,
    val type: MissionType,                               // resolved from MissionTypeRepository
    val channelCount: Int,
    val lastActivityFormatted: String,                   // "04m ago"
)

data class DashboardSummary(
    val activeLinks: Int,        // e.g. 14
    val maxLinks: Int,           // e.g. 16
    val signal: SignalStrength,
    val encryption: String,      // "AES-256"
    val uplinkId: String,        // "UPLINK_04"
)

enum class SignalStrength { STABLE, DEGRADED, OFFLINE }
enum class DegradedSubsystem { CRYPTO, IDENTITY, METADATA }
```

## Intents

```kotlin
sealed interface MissionsIntent {
    data object Refresh : MissionsIntent
    data class Open(val missionId: String) : MissionsIntent
    data class EmergencyOverride(val missionId: String) : MissionsIntent  // for COMPROMISED
    data object NavigateToProfile : MissionsIntent
}
```

## Behaviors

- **Open active mission** → navigate to `missions/{id}` (channel list).
- **Open standby mission** → same route; the channel list itself shows reconnect UI.
- **Compromised mission "Emergency Override"** → opens a confirmation sheet, then either escalates (logs + status flip) or jumps to `panic` if the operator confirms full purge.
- **Refresh** is a pull-to-refresh on the outer column. Triggers `MissionRepository.refresh()`. (For the in-memory store this is a no-op + 300ms shimmer for UX.)
- **System logs footer** is generated on the VM side — pulls last 3 entries from a `SystemLogger` (new util in `shared/`, scope-creep-watch: keep it tiny). Logs include real timestamps.

## Animations

Already done in current draft and good to keep:
- Pulse animation on `SECURE CONNECTION UPLINK_04` indicator (`infiniteRepeatable`, 800ms, linear).
- COMPROMISED mission has a 2dp bottom error stripe — pulse this at 1.2s if you want to push it.

Add:
- Mission list items animate in with a `100ms slideIn` from the right when the list first loads (per DESIGN.md "100ms flicker-on" rule).

## Visual fidelity audit

The current draft is mostly correct. To match the HTML exactly:
- Mission ID badge should sit absolutely positioned in the top-right of the card, opacity 0.3 ([code.html:148](../../../design/stitch_astrasecure_north_star_document/missions_list/code.html#L148)). Currently that's done with a Row — fine.
- `> Access Data` button arrow needs the `>` prefix preserved (it is).
- `LAST: 04m ago` — currently uses uppercase `LAST:`. HTML uses `Last:` mixed case. Use uppercase to stay on-system per DESIGN.md.
- The classification badge (currently hardcoded TOP_SECRET / CONFIDENTIAL / RESTRICTED in the draft at [MissionsScreen.kt:503-546](../../../app/src/main/java/com/explo/capstone/ui/MissionsScreen.kt#L503-L546)) is replaced by a `MissionType` chip. The chip's text comes from `MissionType.name` and its color from `MissionType.accent` mapped via `ColorToken`. The hardcoded `when` block goes away.

## Module integration

- `MissionRepository.missions: StateFlow<List<Mission>>` — direct subscription.
- `MissionTypeRepository.types: StateFlow<List<MissionType>>` — joined in the VM to produce `MissionRow`.
- `ChannelRepository.channelsForMission(id)` — counted to fill `MissionRow.channelCount`. Use `.map { it.size }` over the flow; don't load full Channel objects.
- `IdentityManager.getUserIdentity()` for the top-bar callsign (`GHOST_OPS_09` is currently hardcoded at [MissionsScreen.kt:154](../../../app/src/main/java/com/explo/capstone/ui/MissionsScreen.kt#L154)).
- No `crypto/` or `metadata/` calls — this screen is a list view, not a transport surface.

## Owner & branch coordination

| Layer | Owner | Branch |
|-------|-------|--------|
| Composables (refactor existing) | Yashwanth | yashwanth |
| VM, route, summary derivation | Ismail | ismail |
| Repository | Ismail (with team approval to add `shared/data/`) | ismail |

## Build order

1. **Ismail** introduces `MissionRepository` interface + `InMemoryMissionRepository` with seed data. Wraps the existing hardcoded list.
2. **Yashwanth** refactors `MissionsScreen.kt` per the four steps above. UI still passes — just consuming a flow instead of `sampleMissions`.
3. **Ismail** writes `MissionsViewModel` + `MissionsRoute`.
4. **Ismail** wires the route into `AstraNavGraph`. Tapping a mission navigates (channel list will be a placeholder until [Screen 03](03_channel_list.md) lands).
5. **Yashwanth** adds `@Preview` for Loading / Content / Empty / Error / Degraded states.

## Done criteria

- [ ] No `data class Mission` or `enum class MissionStatus` in the `ui/` package.
- [ ] No `sampleMissions` in any Composable.
- [ ] Tapping a mission navigates with the correct route param.
- [ ] Pull-to-refresh works.
- [ ] Top bar callsign comes from `IdentityManager`.
- [ ] All 5 UI states have previews.
- [ ] One Compose UI test asserts that 4 missions render and tapping the first navigates to `missions/OSS-9921`.
