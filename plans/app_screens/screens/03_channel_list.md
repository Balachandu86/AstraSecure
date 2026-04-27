# Screen 03 — Channel List (per Mission)

**Visual reference:** [channel_list/screen.png](../../../design/stitch_astrasecure_north_star_document/channel_list/screen.png) and [channel_list/code.html](../../../design/stitch_astrasecure_north_star_document/channel_list/code.html).

**Route:** `missions/{id}`.

## Purpose

After selecting a mission, the operator sees its channels grouped by category. Channels are the unit of conversation — secure chat happens inside a channel, not a mission. The mission header shows live status and exposes mission-level actions (Console, New Intel).

## Anatomy

```
[ Top bar with back-arrow + mission breadcrumb ]
[ Mission Header card — name, phase, live pulse, [Console] [+ New Intel] ]
[ Section: COMMAND & CONTROL — admin-only, amber accent ]
[ Section: RECONNAISSANCE & INTELLIGENCE — primary accent, 1 active highlighted ]
[ Section: LOGISTICS & DEPLOYMENT — neutral, 3-up grid on wide ]
[ Bottom nav (MISSIONS active) ]
```

The HTML shows desktop with a left sidebar listing operatives. **Drop the sidebar entirely** — there are no presence indicators or last-seen times anywhere in the app per Q5 in [06_OPEN_QUESTIONS.md](../06_OPEN_QUESTIONS.md). The mission header gets just `name`, `phase`, and the action buttons. The "Active Operatives" block from the HTML is removed.

## States

```kotlin
sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data class Content(
        val mission: Mission,
        val phase: String?,                            // "PHASE 4 / EXTRACTION"
        val userClearance: Rank?,                      // null if user has no rank in this mission
        val sections: List<ChannelSection>,            // categories with at least one channel
    ) : ChannelListUiState
    data class MissionNotFound(val id: String) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}

data class ChannelSection(
    val category: ChannelCategory,
    val channels: List<ChannelRow>,
)

data class ChannelRow(
    val channel: Channel,
    val canView: Boolean,                              // user.clearance >= channel.minClearanceToView
    val canPost: Boolean,
    val unreadCount: Int,                              // 0 = no NEW badge
)
```

`Operative` is gone — no presence per Q5.

## Intents

```kotlin
sealed interface ChannelListIntent {
    data object Back : ChannelListIntent
    data class Open(val channelId: String) : ChannelListIntent
    data object NewIntel : ChannelListIntent          // creates a new channel
    data object OpenConsole : ChannelListIntent       // mission-level settings sheet
}
```

## Channel-row visual rules

Three variants, all derived from data — none hardcoded:

| Variant | When | Treatment |
|---------|------|-----------|
| Default | `canView && unreadCount == 0` | `surface-container-low`, hover `surface-container-high` |
| **Unread** | `canView && unreadCount > 0` | `surface-container-highest`, 2px left border `tertiary`, NEW badge with unread count |
| **Locked** | `!canView` | `surface-container-low` at 60% opacity, lock icon right-side, tooltip on tap shows `> REQUIRES ${requiredRank.name} (LVL ${minClearanceToView})` |

The category header's accent color comes from `ChannelCategory.accent` — no hardcoded "amber for command, primary for recon" mapping. The user can edit category accents in the [Admin Console](08_admin_console.md).

## Behaviors

- **Tap channel (canView)** → `missions/{id}/channels/{channelId}` (secure chat).
- **Tap locked channel** → no nav; show inline tooltip with the actual rank name and level required: `> REQUIRES CHIEF (LVL 9)`. Tooltip fades in/out 1.5s. The text is built from the channel's `minClearanceToView` looked up in `RankRepository`.
- **+ New Intel** → opens a bottom-sheet form: name, description, category dropdown (sourced from `ChannelCategoryRepository`), min-clearance-to-view (defaults from category), min-clearance-to-post (defaults from category). On confirm, calls `ChannelRepository.create(...)`. New channel appears in its category section.
- **Console button** → opens a bottom sheet with mission metadata (key alias, participant list, audit log link, "Mark Compromised" destructive action that flips status and re-routes to the panic confirm flow).

## Module integration

- `MissionRepository.get(id)` — for the header.
- `ChannelRepository.channelsForMission(id)` — list source.
- `ChannelCategoryRepository.categories` — joined to group channels into sections.
- `RankRepository.ranks` — joined for the locked-tooltip text and for `+ New Intel` dropdowns.
- `ClearanceRepository.clearanceFor(userId, missionId)` — drives `canView` / `canPost` per channel.
- `ChannelRepository.create(...)` — for "+ New Intel".
- No presence/operatives integration per Q5.

## Owner & branch coordination

| Layer | Owner | Branch |
|-------|-------|--------|
| Composables | Yashwanth | yashwanth |
| VM + route + ChannelRepository | Ismail | ismail |
| `Channel`, `ChannelCategory` in `shared/Models.kt` | Team agreement → Ismail commits | ismail |

## Build order

1. Phase 0 already added `Channel`, `ChannelCategory`, `Rank`, `ClearanceAssignment` to [Models.kt](../../../app/src/main/java/com/explo/capstone/shared/Models.kt) per [03_DATA_LAYER.md](../03_DATA_LAYER.md).
2. Implement `ChannelRepository`, `ChannelCategoryRepository`, `ClearanceRepository` in `shared/data/`.
3. Build `ChannelListContent` Composable with all 3 row variants.
4. Wire `ChannelListViewModel` to combine the four flows (mission, channels, categories, clearance) into `Content`.
5. Register `missions/{id}` route in `AstraNavGraph`.
6. Wire the "+ New Intel" sheet end-to-end as a smoke test of the create-flow.
7. Verify clearance gating: assign yourself OBSERVER (level 1), confirm COMMAND channels are locked; reassign yourself CHIEF (level 9), confirm they unlock.

## Done criteria

- [ ] All 3 channel-row variants visible in previews (default / unread / locked).
- [ ] Sections render with the accent color from `ChannelCategory.accent` — verified by changing a category accent in the Admin Console and watching the section header shift.
- [ ] Tapping a non-locked channel navigates correctly with both path params.
- [ ] Tapping a locked channel shows a tooltip with the actual rank name (sourced from `RankRepository`, not a string literal).
- [ ] "+ New Intel" sheet creates a real channel that persists in `InMemoryStore` for the session.
- [ ] Back navigation returns to missions list.
- [ ] Reassigning your own clearance for the mission (via Admin Console) updates the lock state of channels live, without a navigation round-trip.
- [ ] Compose UI test: open mission, see at least one channel per seed category, tap an unlocked channel and assert nav route, tap a locked channel and assert tooltip text contains the required rank name.
