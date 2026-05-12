# AstraSecure — Kotlin File Summary

> 53 files across 10 modules. Last updated: 2026-05-07.

---

## Root

| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | App entry point. Checks tombstone and identity status to route to provisioning, terminated, or main app; creates NavController and passes AppContainer. |
| `AstraApp.kt` | `Application` singleton. Instantiates `AppContainer` once, loads persisted snapshot on startup, seeds default schema on first launch. |

---

## shared/

| File | Responsibility |
|------|---------------|
| `AppContainer.kt` | Manual dependency injection container. Holds every singleton (repositories, crypto engines, persistence, Signal store). Wires the incoming message stream and OPK replenishment flows at construction. |
| `Models.kt` | All core data classes: `User`, `Mission`, `Channel`, `Message`, `EncryptedDocument`, `Rank`, `ChannelCategory`, `MessageCategory`, `MissionType`, `ClearanceAssignment`, `SecurityEvent`, plus `ColorToken` enum and status enums. |

---

## shared/data/

| File | Responsibility |
|------|---------------|
| `InMemoryStore.kt` | Central reactive store. One `MutableStateFlow` per entity type (missions, channels, messages, documents, ranks, categories, types, clearances). Triggers persistence on mutations (messages and documents are ephemeral). |
| `PersistenceManager.kt` | Serializes/deserializes `StoreSnapshot` to `astra_store.json` via Gson. Handles save and load of all non-ephemeral state. |
| `SeedData.kt` | Defines the 3 system ranks (OBSERVER/OPERATIVE/CHIEF), channel categories, message categories, and mission types. Called by `AstraApp` on first launch or after a restore. |
| `DebugResetHelper.kt` | Debug-only utility. Resets device to pre-provisioning state by clearing tombstone, identity, Keystore keys, store, and persistence file in strict dependency order. |

---

## shared/data/log/

| File | Responsibility |
|------|---------------|
| `SecurityEventLog.kt` | Append-only ring buffer (capped at 100 events). Records severity, source, and text for every security-relevant action. Supports JSON export via Android SAF. |

---

## shared/data/entity/

| File | Responsibility |
|------|---------------|
| `MissionRepository.kt` | Interface + in-memory impl for mission CRUD: `create`, `get`, `updateStatus`, `updatePhase`, `addParticipant`, `delete`, `wipeAll`. Exposes `StateFlow<List<Mission>>`. |
| `ChannelRepository.kt` | Interface + in-memory impl for channel CRUD, scoped to a mission. Supports per-channel clearance overrides. Mission-scoped `StateFlow` per channel set. |
| `MessageRepository.kt` | Signal Protocol messaging layer. Handles Sender Key distribution (SKDM) to channel members, per-channel encryption, metadata padding/delay via `MetadataProcessor`, and lazy key distribution tracking. |
| `DocumentRepository.kt` | Encrypted document vault. Each document gets its own AES-256-GCM Keystore key (via `IdentityManager`). Encrypted bytes stored to disk; lightweight metadata in `SharedPreferences`. Decrypts lazily on retrieve. |

---

## shared/data/schema/

| File | Responsibility |
|------|---------------|
| `RankRepository.kt` | CRUD for clearance ranks. Sorted by level descending. Blocks deletion if rank is assigned to any user. Protects system ranks (OBSERVER/OPERATIVE/CHIEF). |
| `ChannelCategoryRepository.kt` | CRUD for channel categories with color tokens and default view/post clearance levels. Blocks deletion if any channel references the category. |
| `MessageCategoryRepository.kt` | CRUD for message categories (STANDARD, INTELLIGENCE, COMMAND, RESTRICTED) with per-category minimum clearance to send. Blocks deletion if any message uses it. |
| `MissionTypeRepository.kt` | CRUD for mission types (TOP SECRET, CONFIDENTIAL, RESTRICTED) with color tokens and descriptions. Blocks deletion if any mission references it. |
| `ClearanceRepository.kt` | Maps `(userId, missionId) → Rank`. Caches derived `StateFlow` per pair and refreshes on mutation. `assign`/`unassign`/`wipeAll` are the only write paths. |

---

## crypto/

| File | Responsibility |
|------|---------------|
| `CryptoEngine.kt` | AES-256-GCM encryption for mission payloads and password-protected content. Generates and rotates mission keys in Android Keystore. Invalidates all `astra_*` key aliases on wipe. |

---

## crypto/signal/

| File | Responsibility |
|------|---------------|
| `AstraSignalProtocolStore.kt` | Persistent Signal Protocol store backed by `EncryptedSharedPreferences`. Implements all five sub-stores: identity, session, pre-key, signed pre-key, sender key. Also persists the server JWT for registration recovery. |
| `SignalCryptoEngine.kt` | Wraps libsignal sessions. Provides `encryptForAddress`/`decryptFromAddress` (X3DH + Double Ratchet for 1:1) and `encryptForChannel`/`decryptFromChannel` + `distributeChannelSenderKey` (Sender Key for group channels). |
| `SignalKeyManager.kt` | Full Signal provisioning lifecycle. Generates identity key, registration ID, signed pre-key, and 100 OPKs. Registers with server, rotates SPK weekly, replenishes OPKs when supply drops. Retries registration if offline at provision time. |

---

## identity/

| File | Responsibility |
|------|---------------|
| `IdentityManager.kt` | Hardware-bound user identity via Android Keystore EC keypair. Persists `UserIdentity` in `EncryptedSharedPreferences`. Manages per-document AES-256-GCM key generation for the vault. Performs device attestation (StrongBox vs TEE detection). |

---

## metadata/

| File | Responsibility |
|------|---------------|
| `MetadataProcessor.kt` | Traffic analysis countermeasures. Pads encrypted messages to 256-byte block boundaries. Generates randomized transmission delays (200–2000 ms). Batches outbound messages to obscure traffic patterns. |

---

## transport/

| File | Responsibility |
|------|---------------|
| `MessageTransport.kt` | Interface abstracting the Signal relay server. Operations: register, fetch/upload pre-keys, send/receive, join channels, acknowledge, delete user. Exposes reactive `connectionState` and `keysNeeded` flows. |
| `TransportMessage.kt` | Message type constants (`PREKEY_SIGNAL_MESSAGE`, `WHISPER_MESSAGE`, `SENDER_KEY_DIST`, `SENDER_KEY_MESSAGE`) and all DTO classes for register/fetch/send/acknowledge wire operations. |
| `SignalServerClient.kt` | Retrofit + OkHttp HTTP/WebSocket client. Persists JWT in `AstraSignalProtocolStore`. Drives the `keysNeeded` reactive flow. Degrades gracefully when the server is unreachable or the base URL is blank. |

---

## ui/  *(stateless Composables — driven by UiState)*

| File | Responsibility |
|------|---------------|
| `AstraAppShell.kt` | Root scaffold with top bar, bottom nav bar, and content slot. Defines `NavTab` enum (MISSIONS, SECURITY, TOOLS, PANIC) with icons and route strings. |
| `UiComponents.kt` | Design system. `AstraTheme` color tokens, typography (Space Grotesk headings, monospace labels), reusable primitives (`AstraInputField`, `AstraButtonPrimary`, etc.), and `resolveColorTokenTriple`. |
| `DegradedBanner.kt` | Amber warning banner displayed at the top of screens when a subsystem (CRYPTO, IDENTITY, METADATA) is offline or degraded. |
| `ProvisioningScreen.kt` | Stateless provisioning UI. Steps: Probing → CallsignEntry → Review → Provisioning → Failed. Includes step indicator, device attestation rows, and progress bar. |
| `PanicScreen.kt` | Emergency wipe UI. States: standby (drag-to-confirm slider) → wiping (phase progress) → tombstoned (terminal lock). Animated flicker on standby. |
| `TerminatedScreen.kt` | Post-wipe permanent lock screen. Shows lock icon and "TERMINATED" message. Offers uninstall button. Debug-only reset available in development builds. |
| `MissionsScreen.kt` | Missions dashboard. Summary card (active links, signal strength, encryption label), system event log, mission card list, create-mission bottom sheet, empty state. |
| `ChannelListScreen.kt` | Channel list grouped by category. Clearance-gated rows (locked icon + tooltip if user rank too low). New-channel bottom sheet visible to OPERATIVE+ only. |
| `ChatScreen.kt` | Chat view. States: Loading, Content (messages + composer + category picker + key rotation banner + degraded warning), Error. Message variants: Incoming, Outgoing, System, IntelPacket. |
| `AdminConsoleScreen.kt` | Admin console with five tabs: RANKS, CHANNEL CATEGORIES, MESSAGE CATEGORIES, MISSION TYPES, CLEARANCE. Edit/add/delete sheets with referential integrity error dialogs. Clearance tab handles per-mission rank assignment. |
| `ProfileScreen.kt` | User profile screen. Shows callsign (editable), hardware key ID, provisioned timestamp, per-mission clearance list sorted by rank level, admin badge if rank ≥ 9. |
| `SecurityScreen.kt` | Security posture dashboard. Device attestation, identity status, Signal fingerprint, SPK rotation date, OPK count, event log. Actions: rotate SPK, replenish OPKs, export log. |
| `ToolsScreen.kt` | Crypto toolbox. Encode/decode (Base64, Hex, URL), hash (SHA-256, SHA-512), AES-256-GCM encrypt/decrypt with password, vault document import/export. |
| `VaultScreen.kt` | Document vault UI. Lists encrypted files per mission. Import file from SAF, decrypt and save to Downloads, delete entry. Status messages on operation result. |

---

## ux/  *(Routes + ViewModels — stateful, wired to AppContainer)*

| File | Responsibility |
|------|---------------|
| `AstraNavGraph.kt` | Central NavHost. Start destination routing (provisioning → app or terminated). Defines all routes and passes AppContainer to each child route. Debug reset hookup in dev builds. |
| `ProvisioningRoute.kt` | Wires `ProvisioningViewModel` to `ProvisioningContent`. Emits `provisioningSuccess` to trigger navigation into the main app after key generation completes. |
| `ProvisioningViewModel.kt` | 5-state provisioning flow on the IO dispatcher. Calls `IdentityManager.provisionIdentity()` then `SignalKeyManager.provisionKeys()`. Invokes `onProvisioned(userId)` callback on success. |
| `MissionsRoute.kt` | Route composable for the missions list. Wires `MissionsViewModel`, sets top-bar callsign and clearance label, delegates navigation intents (`Open`, `EmergencyOverride`) up to NavGraph. |
| `MissionsViewModel.kt` | Combines `MissionRepository`, `MissionTypeRepository`, `ChannelRepository`, and `MessageTransport.connectionState` into `MissionsUiState`. Tracks callsign, localUserId, and degraded subsystems. Handles mission creation intent. |
| `ChannelListRoute.kt` | Wires `ChannelListViewModel` for a specific mission. Resolves user clearance per channel (can view / can post). Routes to chat on channel tap. |
| `ChatRoute.kt` | `ChatViewModel` drives the live message stream from the decrypted inbound flow. Resolves user clearance, filters available message categories, tracks degraded modes, emits key rotation events. |
| `ProfileRoute.kt` | `ProfileViewModel` loads user identity, formats provisioned date, builds sorted clearance list, determines admin status (rank level ≥ 9). Handles callsign update. |
| `SecurityRoute.kt` | `SecurityViewModel` assembles the security posture state: attestation, Signal fingerprint, SPK rotation timestamp, OPK count. Handles rotate-SPK and replenish-OPKs actions. |
| `ToolsRoute.kt` | `ToolsViewModel` handles the AES encrypt/decrypt path via `CryptoEngine`. Non-AES operations (hash, encode) run inline in the composable. |
| `AdminRoute.kt` | `AdminViewModel` drives all five schema tabs. Full CRUD with referential-integrity blocking and delete-error dialogs. Clearance assign/unassign and add-participant handlers. Emits security log events for every mutation. |
| `PanicRoute.kt` | `PanicViewModel` executes the 5-phase wipe sequence (revoke → overwrite → invalidate keys → wipe schema → finalize) with progress label updates. Writes tombstone on completion. Handles uninstall intent. |
