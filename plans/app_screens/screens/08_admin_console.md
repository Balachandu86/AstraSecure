# Screen 08 — Admin Console (Customization)

**Visual reference:** None. Synthesized from the design system in [DESIGN.md](../../../design/stitch_astrasecure_north_star_document/astra_obsidian/DESIGN.md). Treat the existing Security Dashboard ([06](06_security_dashboard.md)) as the closest stylistic neighbor — same density, same monospace data treatment.

**Route:** `admin` (entered from a gear icon on the Security Dashboard, **not** as a top-level bottom-nav tab — the bottom nav stays at MISSIONS / SECURITY / TOOLS / PANIC per the existing design).

## Purpose

The single place in the app where the operator customizes:
- **Ranks** — clearance tiers used everywhere gating happens.
- **Channel categories** — the groupings shown on Channel List.
- **Message categories** — the sensitivity tags used in the Chat composer.
- **Mission types** — replaces the old `Classification` enum (TOP_SECRET / CONFIDENTIAL / RESTRICTED).
- **Per-mission clearance assignments** — what rank the operator holds for each mission.

Without this screen, the customization layer in [03_DATA_LAYER.md](../03_DATA_LAYER.md) is dead code. With it, every other screen reads its enums from data the operator can edit at any time.

## Anatomy

```
[ Top bar: ← back | "ADMIN CONSOLE" | help (?) ]
[ Tab strip — sticky horizontal scroll ]
   [ RANKS ] [ CHANNEL CATEGORIES ] [ MESSAGE CATEGORIES ] [ MISSION TYPES ] [ CLEARANCE ]
[ Tab body: list + add button ]
```

Each tab is a list-edit-add surface. They share a Composable: `SchemaListEditor<T>` parameterized over the record type.

## Tab 1 — Ranks

```
[ Header: RANKS // 3 ACTIVE                              [ + NEW RANK ] ]
[ List, sorted by level desc:                                            ]
   ┌─ CHIEF                  LVL 9    ●●●●●●●●●  ▌edit▐                  ]
   │  TERTIARY accent    [ SYSTEM ]                                      ]
   ├─ OPERATIVE             LVL 5    ●●●●●        ▌edit▐                 ]
   │  PRIMARY accent     [ SYSTEM ]                                      ]
   └─ OBSERVER              LVL 1    ●            ▌edit▐                 ]
      NEUTRAL accent     [ SYSTEM ]                                      ]
```

- Tap row → opens edit sheet.
- `[ SYSTEM ]` badge marks seed records (can rename, can change level, **cannot delete**).
- `+ NEW RANK` opens a create sheet: name, level (Int input with stepper), color (one of `ColorToken`).
- Level conflicts (two ranks with same level) → warn with amber chip but don't block; ordering is by level desc, ties broken by name.

### Edit sheet fields (Rank)
- **NAME** — uppercase enforced, regex `^[A-Z][A-Z0-9_ ]{1,15}$`.
- **LEVEL** — integer, 1–99. Stepper `[ - ]  XX  [ + ]`.
- **ACCENT** — color picker as 5 swatches: `PRIMARY` `TERTIARY` `SECONDARY` `ERROR` `NEUTRAL`. Each swatch is a 32dp square in its theme color.
- Footer: `[ DELETE ]` (only enabled if `!isSystem`) | `[ CANCEL ]` | `[ SAVE ]`

## Tab 2 — Channel categories

Same pattern. Edit sheet fields:
- **NAME**
- **ACCENT** (color picker)
- **DEFAULT_MIN_VIEW** — integer, must reference a valid rank level (UI shows current ranks for context)
- **DEFAULT_MIN_POST** — integer, must be ≥ DEFAULT_MIN_VIEW

When the user changes a category's defaults, **existing channels are not retroactively updated** — they keep their per-channel overrides. Show an info chip: `> EDITS APPLY TO NEW CHANNELS ONLY`.

## Tab 3 — Message categories

Edit sheet fields:
- **NAME**
- **ACCENT**
- **MIN_CLEARANCE_TO_SEND** — integer

The composer's category picker reads this list. Categories with `minClearanceToSend > user.clearance` show in the picker as disabled rows with a lock icon.

## Tab 4 — Mission types

Edit sheet fields:
- **NAME**
- **ACCENT**
- **DESCRIPTION** — multi-line text, max 200 chars.

These display as the badge on Mission List items (replacing the old `Classification` chip).

## Tab 5 — Clearance assignments

Different shape — one row per mission, the user picks their rank for each:

```
[ Header: YOUR CLEARANCE PER MISSION                                     ]
[ List:                                                                  ]
   ┌─ Operation Silent Sentinel              [ CHIEF ▾ ]                 ]
   │  ID: OSS-9921 · Active                                              ]
   ├─ Task Force Alpha                       [ OPERATIVE ▾ ]             ]
   │  ID: TFA-0042 · Standby                                             ]
   └─ Vanguard Recon                         [ OBSERVER ▾ ]              ]
      ID: VR-8810 · Compromised                                          ]
```

Tapping the rank chip opens a dropdown of all available ranks. Selecting a new rank calls `clearanceRepo.assign(userId, missionId, rankId)`. No confirmation — this is a self-elected demo feature (see [03_DATA_LAYER.md](../03_DATA_LAYER.md)).

A footer button `[ ASSIGN MAX TO ALL ]` quickly sets the user as CHIEF (or whatever the highest rank is) for every mission — useful for the demo so the operator can see all channels.

## Delete handling — `DeleteResult.BlockedBy`

When the user taps DELETE on a non-system record, the repo's `delete()` returns either `Deleted` or `BlockedBy(refs: List<EntityRef>)`. If blocked, render a sheet:

```
> DELETE BLOCKED

This rank is still referenced by:
   • 2 channels (min-clearance gates)
   • 4 message categories (send gates)

[ REASSIGN BEFORE DELETE ]   [ CANCEL ]
```

The `REASSIGN` button opens a follow-up flow that shows each blocking reference with a dropdown to pick a replacement record. Once all references are reassigned, the original delete proceeds.

This is the most fiddly UX in the screen. **Build it last** — start with delete-disabled-on-references and add reassignment flow only if Phase 5 has time.

## States

```kotlin
sealed interface AdminUiState {
    data object Loading : AdminUiState
    data class Content(
        val tab: AdminTab,
        val ranks: List<Rank>,
        val channelCategories: List<ChannelCategory>,
        val messageCategories: List<MessageCategory>,
        val missionTypes: List<MissionType>,
        val clearanceByMission: List<MissionClearanceRow>,
    ) : AdminUiState
    data class Error(val message: String) : AdminUiState
}

enum class AdminTab { RANKS, CHANNEL_CATEGORIES, MESSAGE_CATEGORIES, MISSION_TYPES, CLEARANCE }

data class MissionClearanceRow(
    val mission: Mission,
    val currentRank: Rank?,
)
```

## Intents

```kotlin
sealed interface AdminIntent {
    data class SwitchTab(val tab: AdminTab) : AdminIntent
    data class StartCreate(val tab: AdminTab) : AdminIntent
    data class StartEdit(val tab: AdminTab, val recordId: String) : AdminIntent
    data class SaveRank(val rank: Rank) : AdminIntent          // works for both create & edit
    data class SaveChannelCategory(val cat: ChannelCategory) : AdminIntent
    data class SaveMessageCategory(val cat: MessageCategory) : AdminIntent
    data class SaveMissionType(val mt: MissionType) : AdminIntent
    data class Delete(val tab: AdminTab, val recordId: String) : AdminIntent
    data class Reassign(val fromId: String, val toId: String, val refType: RefType) : AdminIntent
    data class AssignClearance(val missionId: String, val rankId: String) : AdminIntent
    data object AssignMaxToAll : AdminIntent
}
```

## Visual rules

- Tabs: `surface-container-low` background, active tab `surface-container-highest` with `primary` text and 2px bottom border `primary`. Inactive: `on-surface-variant` text.
- List rows: `surface-container-low` with 12dp vertical padding, hover `surface-container-high`. Color swatch on left (8dp x 24dp vertical bar) in the record's accent color.
- Edit sheet: bottom sheet, `surface` background, drag handle, fields stacked vertically with 16dp gap. Save button is full-width `primary`.
- Color picker: 5 swatches in a row, selected swatch has 2px `on-surface` outline.

## Module integration

This screen does not call `crypto/`, `identity/`, or `metadata/` directly — it is purely a CRUD surface over the schema repos. It does emit to `SecurityEventLog` so all changes (rank created/deleted, clearance reassigned) appear in the audit trail.

## Owner & branch coordination

| Layer | Package | Credit |
|-------|---------|--------|
| Composables | `ui/AdminConsoleScreen.kt` | Yashwanth |
| VM + route | `ux/AdminViewModel.kt`, `ux/AdminRoute.kt` | Ismail |
| Schema repos (already in shared/) | `shared/data/schema/` | shared |

## Build order

1. Implement schema repos in `shared/data/schema/` — in-memory only.
2. Wire `AdminViewModel` against the repos.
3. Build the `SchemaListEditor<T>` shared Composable. Specialize it for each tab.
4. Build the per-tab edit sheet.
5. Build the clearance assignment tab last — depends on `MissionRepository` and `RankRepository`.
6. Implement `DeleteResult.BlockedBy` reassignment flow only if time permits in Phase 5.

## Done criteria

- [ ] All 5 tabs render with seed data on first launch.
- [ ] Create a new Rank → it appears in the list and is selectable in the Clearance tab.
- [ ] Edit an existing system Rank's name → the new name shows up on Mission List badges and Chat sender labels.
- [ ] Delete a non-system Rank with no references → record disappears.
- [ ] Delete a Rank with references → blocked sheet appears.
- [ ] Assign yourself CHIEF on a previously-locked channel's mission → re-open the channel list and the channel is no longer locked.
- [ ] All edits emit `SecurityEvent.INFO` entries.
- [ ] Compose UI test: create a Rank, navigate to Channel List, assert the Rank name appears in clearance tooltip.
