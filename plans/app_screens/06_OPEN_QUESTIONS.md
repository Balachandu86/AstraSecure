# Open Questions — Decisions

These were the decisions made before Phase 1. Each is now locked. Subsequent doc edits in this folder reflect these answers; if you read something elsewhere that contradicts a decision here, this doc wins.

---

## Q1. `shared/data/` — who edits it?

**DECISION:** No editing-by-folder restriction. A single contributor (Tejas, with AI assistance) implements the entire app. The README's "only edit your assigned module" rule is dropped.

**Implications:**
- The crypto/, identity/, metadata/, ux/, ui/ package split is preserved as a **logical organization** of code by responsibility (and so credit can be attributed in documentation, READMEs, the capstone report, and PR commits).
- There is no Friday PR cycle, no per-engineer branch, no merge-collision risk. Branches can move fast.
- Each module's package still has a `// Owner: <name>` doc comment for credit. The capstone team's contributions are documented but not enforced through process.

---

## Q2. Extending `shared/Models.kt`

**DECISION:** Build all proposed additions. No piecemeal rollout. Phase 0 introduces them in one diff. See [03_DATA_LAYER.md](03_DATA_LAYER.md) for the full type list, including the customization-layer additions from Q8.

---

## Q3. The wipe contract

**DECISION:** Wipe is total annihilation. The wiped identity is **unrecoverable**. Post-wipe, the app is rendered non-functional and requires reinstall (or `Settings → Apps → AstraSecure → Clear Data`) before a new identity can be provisioned.

**Mechanism:**
1. VM orchestrates the four wipe phases (revoke → overwrite → invalidate-keys → finalize).
2. As the final step, the VM writes a `terminal=true` tombstone to a plain (non-encrypted) `SharedPreferences` file. This tombstone is intentionally not removed by `wipeAll()` — it is the terminal marker.
3. After the tombstone is written, the VM triggers `Intent.ACTION_UNINSTALL_PACKAGE` to prompt the user.
4. If the user declines uninstall, the app remains installed but every launch reads the tombstone first and routes to a `TERMINATED` screen with no nav, no buttons except `RECHECK_STATE`. The only way out is OS-level Clear Data or uninstall.
5. After Clear Data, the tombstone is gone, EncryptedSharedPreferences is gone, and the app boots into the normal Provisioning flow with a fresh identity (which has no relationship to the wiped one — Keystore aliases were destroyed).

See [screens/07_panic_wipe.md](screens/07_panic_wipe.md) for the full UI contract.

---

## Q4. Compose Navigation vs Fragments

**DECISION:** Jetpack Compose only. No Fragments anywhere.

- `androidx.navigation:navigation-fragment-ktx` and `navigation-ui-ktx` are removed from [libs.versions.toml](../../gradle/libs.versions.toml) in Phase 0.
- `androidx.navigation:navigation-compose` is added in Phase 0.
- All routes are `composable("...")` entries in `AstraNavGraph`.

---

## Q5. Operative presence (online indicator)

**DECISION:** No presence indicators of any kind. No online dots, no "last seen" lines, no green/grey status pips.

- Channel list: drop the operatives sidebar entirely on phone (already noted) and don't surface it as a sheet either.
- Chat: drop the "GHOST_LEAD is encoding" typing strip entirely (see Q7).
- Mission roster: only renders `participantIds` count, not who-is-online.

This simplifies the UI, removes dishonest UX (since there's no presence backend), and matches the operator's brief.

---

## Q6. AGP 9.1.1 + Kotlin 2.3.20

**Status:** No change required. Versions stay as pinned in [libs.versions.toml](../../gradle/libs.versions.toml).

---

## Q7. Where does the typing indicator come from?

**DECISION:** No typing indicator. The chat UI never renders "X is encoding..." in any build. Drop the strip from the design.

This is a straightforward consequence of Q5 (no presence) and avoids the problem of fabricating signals from a non-existent server.

---

## Q8. `MessageCategory` values vs design — and customization at large

**DECISION:** Both concepts coexist (channel categories are organizational; message categories are sensitivity tags), and **neither is hardcoded.** Ranks, channel categories, message categories, and mission types are all user-customizable schemas managed via a new [Admin Console screen](screens/08_admin_console.md).

**Implications:**
- The `MessageCategory` and `Classification` enums in [Models.kt](../../app/src/main/java/com/explo/capstone/shared/Models.kt) become **data records** (not enums). Stored, edited, deleted by the user.
- Channels are clearance-gated — like Discord admin-only channels. Each channel carries `minClearanceToView` and `minClearanceToPost`. The user's clearance is per-mission (so a user can be CHIEF of one mission and OBSERVER in another).
- The clearance level is an `Int`. Ranks have a name and a level; the level is what the gating logic compares.
- Seed defaults (e.g., the "TOP_SECRET / CONFIDENTIAL / RESTRICTED" classifications, the four channel-category sections shown in the design) are inserted on first launch as **starter records the user can edit or delete**.
- Mission-status workflow states (`ACTIVE / STANDBY / COMPROMISED / ARCHIVED`) **stay** as a code-level enum — they're an internal state machine, not a category. The user's directive was about categories and types; status is neither.

See [03_DATA_LAYER.md](03_DATA_LAYER.md) and [screens/08_admin_console.md](screens/08_admin_console.md) for the full schema model.

---

## Q9. Where do we keep the demo seed data?

**DECISION (deferred but defaulted):** `shared/data/SeedData.kt`, gated by `BuildConfig.DEBUG`. Release ships with empty state. This is consistent with Q8 — seed data now includes seed schema records (default ranks, default channel categories, default message categories, default mission types) plus seed missions/channels.

---

## Q10. CI / branch protection

**DECISION (deferred):** Not blocking implementation. Add a minimal `./gradlew assembleDebug test` GitHub Actions workflow in Phase 5. No branch protection (single contributor, see Q1).
