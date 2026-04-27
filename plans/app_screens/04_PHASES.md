# Phased Implementation Roadmap

Six phases. Each phase ends with a working app — never a "broken middle". The order is chosen so dependencies land before consumers.

Per [Q1 in 06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md), there is one contributor (Tejas, with AI assistance), so phases are sequential rather than parallel-by-engineer. Owner labels in the screen specs are credit attribution; they do not gate anyone.

---

## Phase 0 — Foundations (1 sprint)

**Goal:** unblock everything that follows. Lock the schema model. Build the app shell so screens have a place to live.

### Tasks

| Task | Files |
|------|-------|
| Add `androidx-navigation-compose` and `androidx-lifecycle-runtime-compose`; remove `navigation-fragment-ktx` and `navigation-ui-ktx` | [libs.versions.toml](../../gradle/libs.versions.toml), [build.gradle.kts](../../app/build.gradle.kts) |
| Create `AstraApp : Application` and register in manifest | new `MainActivity.kt` package, [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) |
| Create `shared/AppContainer.kt` (DI root) | new |
| Replace `MessageCategory` enum + delete `Classification` proposal; add `Rank`, `ChannelCategory`, `MessageCategory`, `MissionType`, `ClearanceAssignment`, `Channel`, `SecurityEvent`, `ColorToken`, `Severity` records to [Models.kt](../../app/src/main/java/com/explo/capstone/shared/Models.kt) | one diff |
| Update `Mission` and `Message` shapes per [03_DATA_LAYER.md](03_DATA_LAYER.md) (add `typeId`, change `Message.missionId` → `channelId`) | shared |
| Build `InMemoryStore` and the entity + schema repository in-memory implementations | `shared/data/` |
| Build `SeedData.kt` with seed ranks, categories, mission types, missions | `shared/data/` |
| Build `SecurityEventLog` ring buffer | `shared/data/log/` |
| Refactor [MissionsScreen.kt](../../app/src/main/java/com/explo/capstone/ui/MissionsScreen.kt) — drop local `Mission`/`MissionStatus`/`MissionClassification`, extract `AstraTopBar`/`AstraBottomBar`/`NavItem` into `ui/AstraAppShell.kt` | refactor |
| Build `AstraNavGraph` with all 8 routes (provisioning, app/missions, app/missions/{id}, app/missions/{id}/channels/{cid}, app/security, app/tools, app/panic, app/admin) — placeholder content for unbuilt screens | `ux/AstraNavGraph.kt` |
| Build `DegradedBanner` Composable + `DegradedSubsystem` enum | `ui/` |

### Done when

- App builds and runs.
- Bottom-nav cycling works between MISSIONS / SECURITY / TOOLS / PANIC; other tabs render `Text("// ${tab.name}")` placeholders.
- The existing missions list visually matches the design and reads from `MissionRepository` joined with `MissionTypeRepository`.
- No `NotImplementedError` reaches the UI without being caught and shown as DEGRADED.
- All schema records exist with seed data on first launch.

---

## Phase 1 — Identity online (1 sprint)

**Goal:** the app no longer launches into a fake user. Provisioning works end-to-end, including tombstone/terminated logic.

### Tasks

| Task | Files |
|------|-------|
| `IdentityManager.provisionIdentity` real impl with Keystore KeyPairGenerator + StrongBox-backed when available | `identity/` |
| `IdentityManager.getUserIdentity()` reading from `EncryptedSharedPreferences` | `identity/` |
| `IdentityManager.deviceAttestation()` | `identity/` |
| `IdentityManager.writeTombstone()` and `readTombstone()` against plain `SharedPreferences("astra_state")` | `identity/` |
| `ProvisioningViewModel` + `ProvisioningRoute` + 5-state Composable | `ui/` + `ux/` |
| `TerminatedScreen` composable | `ui/` |
| Wire `MainActivity` start logic: tombstone → terminated, else identity check → app/provisioning | `MainActivity.kt` |
| Replace top-bar hardcoded `GHOST_OPS_09` with VM-driven callsign | `ui/` |
| Compose UI tests for provisioning happy path | `androidTest/` |

### Done when

- Fresh install → Provisioning → Missions list with operator's callsign in the top bar.
- App relaunch skips Provisioning.
- Pulling app's data dir off-device shows EncryptedSharedPreferences, not plaintext.
- Manually writing `terminal=true` to `astra_state` prefs and relaunching → TerminatedScreen.

---

## Phase 2 — Channels, Tools, and Admin Console (2 sprints)

**Goal:** breadth. Three screens go live. Admin Console is here because every other screen depends on the schema being editable, not just seeded.

### Sprint 2a tasks

| Task | Files |
|------|-------|
| `ToolsViewModel` + `ToolsRoute` with BASE64, HEX, URL_ENCODE inline | `ux/`, `ui/` |
| `ToolsContent` Composable, all 4 mode tabs (only ENCODE/DECODE wired this sprint) | `ui/` |
| `CryptoEngine.encryptWithPassword`/`decryptWithPassword` | `crypto/` |
| HASH algorithms (SHA-256, SHA-512) via `MessageDigest` in VM | `ux/` |
| Auto-wipe on `ON_PAUSE` for Tools | `ui/` |

### Sprint 2b tasks

| Task | Files |
|------|-------|
| `ChannelListViewModel` + `ChannelListRoute` (joins mission, channels, categories, clearance flows) | `ux/` |
| `ChannelListContent` Composable with all 3 row variants (default/unread/locked) | `ui/` |
| "+ New Intel" bottom sheet → `ChannelRepository.create` | `ui/` + `ux/` |
| Locked-channel tooltip with dynamic rank name from `RankRepository` | `ui/` |
| `AdminViewModel` + `AdminRoute` covering all 5 tabs (Ranks, Channel Categories, Message Categories, Mission Types, Clearance) | `ux/` |
| `AdminConsoleScreen` Composable with `SchemaListEditor<T>` shared component | `ui/` |
| Edit sheets for each schema record type | `ui/` |
| `DeleteResult.BlockedBy` reference-checking on schema repo deletes | `shared/data/schema/` |
| Reassignment flow (stretch — only if time permits) | `ui/` |
| Add Admin Console entry point on Security Dashboard top-bar (gear icon) | `ui/` |

### Done when

- Tap mission → see channels grouped in sections by `ChannelCategory`.
- Tap channel within clearance → navigates (chat is still placeholder).
- Tap channel above clearance → tooltip with required rank name, no nav.
- Create a new channel with the "+ New Intel" sheet → appears in list.
- Tools BASE64/HEX/URL/AES round-trip in both directions; SHA-256 produces correct digest.
- Tools auto-wipes on `ON_PAUSE`.
- Admin Console: create a new Rank, edit a system Rank's name, delete a non-system record, reassign clearance for a mission → all changes visible immediately on other screens (no app restart).

---

## Phase 3 — Secure chat (2 sprints)

**Goal:** the integration screen. All five modules exercised. The capstone's flagship demo.

### Sprint 3a tasks

| Task | Files |
|------|-------|
| `MessageRepository`, `DocumentRepository` interfaces + in-memory impls | `shared/data/entity/` |
| `ChatViewModel` + `ChatRoute` (joins channel, messages, message categories, clearance) | `ux/` |
| `ChatContent` Composable, all `ChatItem` variants | `ui/` |
| `Composer` Composable with attach/category-picker/send | `ui/` |
| Composer category picker reads `MessageCategoryRepository`; clearance gating drives enable/disable | `ui/` + `ux/` |
| Real `CryptoEngine.encryptMessage`/`decryptMessage` (Signal session) | `crypto/` |
| Round-trip unit test for encrypt → decrypt | `test/` |

### Sprint 3b tasks

| Task | Files |
|------|-------|
| `MetadataProcessor.padMessage` + secure random delay | `metadata/` |
| `MetadataProcessor.batchMessages` with flush-on-timeout | `metadata/` |
| Wire pad+jitter into `MessageRepository.send` | `shared/data/entity/` |
| `IdentityManager.storeDocument`/`retrieveDocument` | `identity/` |
| Attach pipeline + intel-packet rendering | `ui/` + `identity/` |
| Decrypt-and-view + cache-wipe on pause | `ui/` |
| Key rotation timer + `cryptoEngine.rotateMissionKey` | `crypto/` + `ui/` |
| Debug-only "INJECT INCOMING" button (gated by `BuildConfig.DEBUG`) | `ux/` |
| Read-only banner when `!canPostInChannel` | `ui/` |

### Done when

- Plaintext message round-trips encrypt → pad → store → display.
- Tampered ciphertext renders INTEGRITY_FAIL.
- Attach 1MB file → intel packet appears → decrypt-and-view opens it.
- Pad ensures 256-byte boundaries (asserted in unit test).
- Random delay falls within configured bounds (asserted in unit test).
- Composer plaintext is wiped from VM state immediately after `encryptMessage` returns.
- Categories above the user's clearance show locked in the picker; reducing user's rank in Admin Console flips the composer to the read-only banner.
- No typing indicator anywhere.

---

## Phase 4 — Security dashboard + persistence (1 sprint)

**Goal:** make the device feel like a console. Persistence so missions survive app restart.

### Tasks

| Task | Files |
|------|-------|
| Pipe events from each module's call sites into `SecurityEventLog` | `crypto/`, `identity/`, `metadata/`, `shared/data/` |
| `SecurityViewModel` + `SecurityRoute` | `ux/` |
| `SecurityContent` Composable with all sections | `ui/` |
| ROTATE NOW UI integration | `ui/` + `ux/` |
| Posture grid via `IdentityManager.deviceAttestation` | `ui/` |
| Add kotlinx-serialization dep | gradle |
| Persistence: missions/channels/messages/schema serialize on every mutation | `shared/data/` |
| Export audit log via SAF | `ui/` + `identity/` |
| Add gear icon on Security Dashboard top-bar → Admin Console | `ui/` |

### Done when

- Identity card shows real callsign, key alias, and rank summary across missions.
- Posture grid reflects actual `KeyguardManager.isDeviceSecure` etc.
- ROTATE NOW produces real new alias and emits event.
- App restart preserves missions, channels, messages, schema customizations.
- Export audit log writes a non-empty encrypted JSON file.

---

## Phase 5 — Panic + polish (1 sprint)

**Goal:** ship-ready. The destructive path works end-to-end and the app feels like the design.

### Tasks

| Task | Files |
|------|-------|
| `PanicViewModel` + `PanicRoute` orchestrating 5-phase wipe | `ux/` |
| `PanicContent` Composable + `SlideToConfirm` | `ui/` |
| `IdentityManager.wipeAll` + `revokeRemoteTokens` | `identity/` |
| `CryptoEngine.invalidateAllKeys` | `crypto/` |
| Repository `wipeAll()` impls | `shared/data/` |
| Tombstone write + `Intent.ACTION_UNINSTALL_PACKAGE` launch | `ux/` |
| Animation pass: flicker-on, slide-in, key-rotation pulse | `ui/` |
| Accessibility pass: content descriptions, TalkBack ordering | `ui/` |
| End-to-end Compose UI test for panic-wipe → terminated → clear data → fresh provisioning | `androidTest/` |
| Minimal GitHub Actions workflow (`./gradlew assembleDebug test`) | `.github/workflows/` |

### Done when

- Slide-to-confirm requires deliberate ≥95% drag.
- All five wipe phases visible during execution.
- Post-wipe app launch lands on `TerminatedScreen`, not provisioning.
- After OS Clear Data, fresh provisioning produces a Keystore alias unrelated to the wiped one (assert via hash).
- Uninstall prompt launches after Tombstoned.
- All screens pass TalkBack manual review.
- CI is green.

---

## Cross-phase dependencies (build-order graph)

```
Phase 0 ── unblocks ──► all others
Phase 1 (Identity) ── unblocks ──► Phase 4 (Security dashboard) and Phase 5 (Panic)
Phase 2 (Channels + Tools + Admin) ── unblocks ──► Phase 3 (Chat) — chat needs channel context AND user-customizable categories
Phase 3 (Chat) is the integration test for Phases 0–2 + module impls
Phase 4 ── unblocks ──► Phase 5 (Panic) — panic relies on Identity + Crypto + persistence
```

If any phase slips, the next phase's screen still ships in degraded mode (banner visible). The capstone main branch is never broken.

## Sequencing rationale (why Admin Console is in Phase 2, not later)

The schema customization layer is load-bearing for every screen that displays a category, a rank, or a mission type. If it ships in Phase 5, three earlier screens get refactored twice (once for hardcoded enums, again for dynamic records). Building it in Phase 2 — right after the seed schema lands in Phase 0 — means every later screen is built against the customization layer from day one.

The cost is that Phase 2 is the heaviest sprint. That's acceptable. The alternative is technical debt that compounds.
