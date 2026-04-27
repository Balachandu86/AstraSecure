# Testing Strategy

Three layers, each with a defined owner and a defined cost ceiling. Don't write tests at the wrong layer.

## Layer 1 — Unit tests (Tejas, Sandrani, Jatin)

**Where:** `app/src/test/java/...` — JVM only, no Android dependencies.

**What:** the deterministic core of each module.

| Module | Tests |
|--------|-------|
| `CryptoEngine` | encrypt then decrypt with known key returns input; encrypt then decrypt with wrong key throws integrity error; AES-GCM with password derives stable key; rotate key invalidates old alias |
| `MetadataProcessor` | `padMessage` always returns a multiple of 256 bytes; padding adds 1–256 bytes (never 0); `randomizedDelayMs` uses `SecureRandom` not `Random.Default`; delays fall in `[min, max]`; `batchMessages` flushes on size and on timeout |
| `IdentityManager` | `provisionIdentity` round-trips; `getUserIdentity` returns null pre-provision; `storeDocument` then `retrieveDocument` returns identical bytes; `wipeAll` clears prefs |
| Repositories | Adding a mission emits on `missions` flow; `wipeAll()` clears all flows |
| Schema repos | Create/update/delete records emit on flow; `delete()` returns `BlockedBy` when references exist; `wipeAll()` removes even `isSystem=true` seed records |
| Clearance gating | `canViewChannel(user, channel)` returns true when `rank.level >= channel.minClearanceToView`; false when rank is null; reassigning rank triggers re-emission on the flow |
| Tombstone | `writeTombstone()` followed by `readTombstone()` returns true; `readTombstone()` is unaffected by `wipeAll()` |

**Cost ceiling:** every module owner writes at least 5 unit tests. Aim for 80%+ coverage on module logic. Don't test framework code.

**Tools:** JUnit 4 (already in [libs.versions.toml](../../gradle/libs.versions.toml)). No Mockito — use simple fakes or sealed types.

## Layer 2 — Compose UI tests (Yashwanth + screen owner)

**Where:** `app/src/androidTest/java/...` — instrumented, runs on emulator.

**What:** one happy-path test per screen. Bonus: one error-path test.

| Screen | Happy-path test |
|--------|-----------------|
| Provisioning | Enter valid callsign → tap PROVISION → assert nav to `app/missions` |
| Missions list | Render 4 seed missions → tap first → assert nav route includes seed mission ID |
| Channel list | Open mission → assert 3 sections render → tap a non-locked channel → assert nav route |
| Secure chat | Type "test" → tap ENCODE → assert outgoing bubble appears with delivery state |
| Secure tools | Select BASE64, type "hello", tap EXECUTE → assert output equals known base64 |
| Security dashboard | Render → tap ROTATE NOW on first mission key → assert event log gets new entry |
| Panic | Drag slider to 1.0 → assert state transitions Wiping → Tombstoned; relaunch activity → assert TerminatedScreen renders |
| Admin console | Create a Rank → assert it appears in a re-rendered ChannelList tooltip; reassign clearance → assert previously-locked channel now unlocks |

**Cost ceiling:** roughly 7 tests + 7 error/edge tests = 14 instrumented tests total. Don't test every possible state; rely on `@Preview` for visual coverage.

**Tools:** `androidx.compose.ui:ui-test-junit4` — needs adding to [libs.versions.toml](../../gradle/libs.versions.toml):
```toml
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
```

**Pattern:** Compose tests must stub the VM. Provide a fake `AppContainer` exposing pre-built repositories with seed data. Don't run the real `IdentityManager` in instrumented tests — keystore is slow and hardware-dependent.

## Layer 3 — Compose previews (every screen)

**Where:** alongside the screen Composable in `ui/`.

**What:** every distinct UI state has a `@Preview`.

```kotlin
@Preview(name = "Loading", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun MissionsContent_Loading_Preview() {
    AstraSecureTheme {
        MissionsContent(state = MissionsUiState.Loading, onIntent = {})
    }
}

// One per state: Loading, Content (4 missions), Empty, Error, Degraded
```

**Cost ceiling:** previews are not real tests but they enforce stateless Composables — if the preview renders, the Composable doesn't depend on framework lifecycle. Yashwanth must produce a preview matrix per screen.

## What we don't test

- `MainActivity` start logic — too thin, manual smoke test.
- The actual Signal Protocol session establishment — Tejas owns. The screens treat the engine as a contract.
- Animations — visual review only.
- Network — there is none.

## Manual QA checklist (before each phase merge)

- [ ] Cold install → provisioning works.
- [ ] Mission list renders, mission tap navigates.
- [ ] Channel list renders, channel tap navigates.
- [ ] Send a message, see it persist after process death (Phase 4+).
- [ ] Toggle airplane mode → no crash, no UI freeze.
- [ ] Rotate device → state preserved.
- [ ] TalkBack reads each screen in a sensible order (Phase 5).
- [ ] Panic-wipe + relaunch → fresh identity required.

## CI

Single workflow: `./gradlew test connectedAndroidTest` on every PR to `main`. Block merge on red.

If `connectedAndroidTest` is too slow for CI, run only `test` on PR and run instrumented nightly.
