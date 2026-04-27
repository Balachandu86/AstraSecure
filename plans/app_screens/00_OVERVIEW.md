# AstraSecure // Functional Screens — Master Plan

This folder is the source of truth for building the app's screens as **functional**, wired-up surfaces — not static mockups. Every screen here must read real ViewModel state, route real user actions through the underlying modules (`crypto/`, `identity/`, `metadata/`), and degrade visibly when a dependency is missing.

## Goals

1. **Functional, not cosmetic.** Every button, field, and indicator is hooked to state. A screen with hardcoded sample data is incomplete.
2. **Customizable, not hardcoded.** Ranks, channel categories, message categories, and mission types are user-editable schemas, not Kotlin enums (per Q8 in [06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md)). Channels are clearance-gated like Discord admin channels.
3. **Preserve the design system.** Visual specs in [design/stitch_astrasecure_north_star_document/](../../design/stitch_astrasecure_north_star_document/) and [DESIGN.md](../../design/stitch_astrasecure_north_star_document/astra_obsidian/DESIGN.md) are non-negotiable: 0px radius, tonal stepping, monospace data values, no soft shadows.
4. **Compose-only.** [MainActivity.kt](../../app/src/main/java/com/explo/capstone/MainActivity.kt) sets Compose content. The plan adopts Compose Navigation throughout. No Fragments.
5. **Build behind real cryptography.** No fake-encryption placeholder strings. Until `CryptoEngine` returns real bytes, the UI must show a clear "DEGRADED // CRYPTO_OFFLINE" banner rather than pretending it works.

## Authorship model

The capstone codebase is implemented by a single contributor (Tejas, with AI assistance). The repo's package split — `crypto/`, `identity/`, `metadata/`, `ux/`, `ui/`, `shared/` — is preserved as a **logical organization of code by responsibility**, and each package's `// Owner: <name>` doc comment continues to credit the team member responsible for that subsystem in the original team plan: Tejas (crypto), Sandrani (identity), Jatin (metadata), Ismail (ux), Yashwanth (ui). Per-folder editing rules from the README are dropped (see Q1 in [06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md)).

In the per-screen specs that follow, the "Owner" labels (Yashwanth for UI, Ismail for VM, etc.) indicate **which package the code lives in**, not who edits it. They preserve credit attribution for the capstone report.

## What ships

| # | Screen | Status today | Package home |
|---|--------|--------------|--------------|
| 1 | Identity provisioning (first launch) | Not started | `ui/` + `ux/` |
| 2 | Missions list | Compose draft, hardcoded data ([MissionsScreen.kt](../../app/src/main/java/com/explo/capstone/ui/MissionsScreen.kt)) | `ui/` + `ux/` |
| 3 | Channel list (per mission) | Not started | `ui/` + `ux/` |
| 4 | Secure chat (per channel) | Not started | `ui/` + `ux/` (touches all modules) |
| 5 | Secure tools (encode/decode/hash) | Not started | `ui/` + `ux/` |
| 6 | Security dashboard (SECURITY tab) | Not started | `ui/` + `ux/` |
| 7 | Panic wipe (PANIC tab) | Not started | `ui/` + `ux/` |
| 8 | Admin console (rank + category + mission-type customization) | Not started | `ui/` + `ux/` |

## Doc map

Read these in order before opening a PR:

1. [01_ARCHITECTURE.md](01_ARCHITECTURE.md) — layering, state, DI, where each module plugs in
2. [02_NAVIGATION.md](02_NAVIGATION.md) — Compose Nav graph, app shell, back-stack rules
3. [03_DATA_LAYER.md](03_DATA_LAYER.md) — repositories, schema layer, in-memory store, persistence path
4. Per-screen specs under [screens/](screens/):
   - [01_identity_provisioning.md](screens/01_identity_provisioning.md)
   - [02_missions_list.md](screens/02_missions_list.md)
   - [03_channel_list.md](screens/03_channel_list.md)
   - [04_secure_chat.md](screens/04_secure_chat.md)
   - [05_secure_tools.md](screens/05_secure_tools.md)
   - [06_security_dashboard.md](screens/06_security_dashboard.md)
   - [07_panic_wipe.md](screens/07_panic_wipe.md)
   - [08_admin_console.md](screens/08_admin_console.md)
5. [04_PHASES.md](04_PHASES.md) — sequenced roadmap
6. [05_TESTING.md](05_TESTING.md) — unit / integration / Compose UI tests
7. [06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md) — locked decisions

## Definition of "functional"

A screen is functional when **all** of these hold:

- It reads its state from a `ViewModel` exposing `StateFlow<UiState>` (no hardcoded sample lists in the Composable).
- Every `onClick`/`onValueChange` mutates VM state or invokes a real module call (or surfaces an error if the module throws `NotImplementedError`).
- Loading, empty, error, and content states all render distinctly.
- The screen survives configuration change (rotation, dark/light, process death — `SavedStateHandle` for nav args).
- It has at least one Compose `@Preview` per state.
- It has at least one Compose UI test verifying the primary user flow.
- Where the screen depends on user-defined schema (ranks, categories, mission types), it reads from `SchemaRepository` — never an inline `enum class`.

## Non-goals (for this plan)

- Network transport. There is no server; all state is in-memory + Keystore-backed local storage. A real Signal session establishment is a follow-up, not a screen blocker.
- Push notifications, background sync, foreground services.
- Multi-device account migration, presence/online indicators, typing indicators.
- Tablet / desktop layouts. The HTML mockups show responsive `md:`/`lg:` breakpoints; the Android target is phone-only for the capstone.
