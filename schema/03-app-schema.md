# 03 — App Schema (and mapping to server)

What lives on the Android device, where it lives, and how each piece corresponds to a server‑side counterpart.

---

## A. Storage tiers on the device

| Tier | Location | Encryption | Survives process restart? | Survives panic wipe? |
|------|----------|------------|---------------------------|----------------------|
| In‑memory store | `InMemoryStore` (StateFlow) | n/a | No (rehydrated from disk on launch) | No |
| Snapshot file | `filesDir/astra_store.json` | none (relies on FS perms) | Yes | No |
| Identity prefs | `EncryptedSharedPreferences("astra_identity")` | AES‑256‑GCM | Yes | No |
| Signal prefs | `EncryptedSharedPreferences("astra_signal")` | AES‑256‑GCM | Yes | No |
| Vault files | `filesDir/vault/{docId}.bin` | per‑doc AES‑256‑GCM (Keystore key) | Yes | No |
| Keystore | Android Keystore aliases `astra_*` | hardware‑backed (StrongBox/TEE) | Yes | No |
| Tombstone prefs | `SharedPreferences("astra_state")` | none | Yes | **Yes** (`terminal=true` is written last) |

---

## B. Where each entity lives

| Entity | StateFlow | Persistence file | Sent to server? |
|--------|-----------|------------------|------------------|
| `User` | n/a (single record) | `astra_identity` prefs | Yes — `RegisterUserRequest` / signal `RegisterRequest` |
| `Mission` | `missions` | `astra_store.json` | Yes — `MissionDto` over `/api/sync` & mutations |
| `Channel` | `channels` | `astra_store.json` | Yes — `ChannelDto` over `/api/sync` & mutations |
| `Message` | `messages` | **never persisted** | Only as **ciphertext** through `/v1/messages` |
| `EncryptedDocument` | `documents` (metadata) | `vault/{id}.bin` + `astra_identity` metadata | **No** |
| `Rank` | `ranks` | `astra_store.json` | Yes — `RankDto` (server‑authoritative via `/api/sync`) |
| `ChannelCategory` | `channelCategories` | `astra_store.json` | Yes — `ChannelCategoryDto` |
| `MessageCategory` | `messageCategories` | `astra_store.json` | Yes — `MessageCategoryDto` |
| `MissionType` | `missionTypes` | `astra_store.json` | Yes — `MissionTypeDto` |
| `ClearanceAssignment` | `clearanceAssignments` | `astra_store.json` | Yes — `ClearanceDto` |
| `SecurityEvent` | n/a (ring buffer) | **never persisted** | No |
| Signal IdentityKeyPair / RegId | n/a | `astra_signal` prefs | Public half only — at register |
| Signal SPK / OPK / Sessions / SK | n/a | `astra_signal` prefs | Public halves of SPK/OPK only; SK distributed peer‑to‑peer in ciphertext |
| JWT | n/a | `astra_signal` prefs | Issued by relay |

---

## C. Field‑by‑field mapping (app ↔ server)

### Mission ↔ MissionDto

| App field (`Mission`) | Server field (`MissionDto`) | Notes |
|-----------------------|-----------------------------|-------|
| `id` | `id` | server‑assigned; offline fallback id `M-LOCAL-{ts}` until sync |
| `name` | `name` | identical |
| `typeId` | `typeId` | both reference `MissionType.id` |
| `status` | `status` | enum on app, string on wire |
| `phase` | `phase` | nullable on both |
| `missionKeyAlias` | `missionKeyAlias` | opaque local Keystore alias on the wire |
| `participantIds` | `participantIds` | identical |
| `createdAtMs` | `createdAtMs` | identical |
| `lastActivityMs` | `lastActivityMs` | server is authoritative |
| `createdBy` | `createdBy` | recorded only; not used for permission checks today |

### Channel ↔ ChannelDto

| App field | Server field | Notes |
|-----------|--------------|-------|
| `id` | `id` | server‑assigned |
| `missionId` | `missionId` | FK to Mission |
| `name`, `description` | `name`, `description` | identical |
| `categoryId` | `categoryId` | FK to ChannelCategory |
| `minClearanceToView` | `minClearanceToView` | override of category default |
| `minClearanceToPost` | `minClearanceToPost` | override of category default |
| `createdAtMs` | `createdAtMs` | identical |
| `createdBy` | `createdBy` | recorded; not consulted by permission code |

### Schema records

| App | Server | Notes |
|-----|--------|-------|
| `Rank` | `RankDto` | `color: ColorToken` ↔ `color: String` |
| `ChannelCategory` | `ChannelCategoryDto` | `accent` enum ↔ string |
| `MessageCategory` | `MessageCategoryDto` | `accent` enum ↔ string |
| `MissionType` | `MissionTypeDto` | `accent` enum ↔ string |
| `ClearanceAssignment` | `ClearanceDto` | identical 3 fields |

### Message (asymmetric — by design)

| App field (`Message`) | Server field (`QueuedMessageDto`) | Notes |
|-----------------------|------------------------------------|-------|
| `id` (`MSG-…`) | — | client‑local; server uses its own `Long id` |
| — | `id: Long` | server queue id, used for ACK |
| `channelId` | `channelId` | identical |
| `senderId` | `senderId` | identical |
| `categoryId` | — | category lives **inside** the encrypted payload — server never sees it |
| `encryptedContent` | `ciphertext` (b64) | identical bytes |
| `paddedSizeBytes` | — | length‑normalisation metadata, local only |
| `timestampMs` | — | reconstructed locally from arrival |
| — | `messageType: Int` | wire type (Signal) |

### EncryptedDocument

No mapping. Documents are device‑local. The server has no concept of them.

### User registration

| App | Astra API | Signal API |
|-----|-----------|------------|
| `User.displayName` | `RegisterUserRequest.callsign` | `RegisterRequest.displayName` |
| `User.id` | response (server‑assigned) | `RegisterRequest.userId` |
| Signal IK public | — | `RegisterRequest.identityKey` |
| Registration ID | — | `RegisterRequest.registrationId` |
| SPK public + sig | — | `RegisterRequest.signedPreKey` |
| OPK publics (×100) | — | `RegisterRequest.oneTimePreKeys` |
| `hardwareKeyId` | — | — (local‑only) |
| `provisionedAtMs` | — | — (local‑only) |

---

## D. Repositories (read/write surface from UI/ViewModels)

| Repository | Implementation | Source flow | Mutating ops |
|------------|----------------|-------------|--------------|
| `MissionRepository` | `InMemoryMissionRepository` | `missions` | create / updateStatus / updatePhase / addParticipant / delete / wipeAll |
| `ChannelRepository` | `InMemoryChannelRepository` | `channelsForMission(missionId)` | create / updateClearance / delete / wipeAll |
| `MessageRepository` | `SignalMessageRepository` | `messagesForChannel(channelId)` | send / receive / wipeAll |
| `DocumentRepository` | `VaultDocumentRepository` | `documentsForMission(missionId)` | store / retrieve / delete / wipeAll |
| `RankRepository` | `InMemoryRankRepository` | `ranks` | create / update / delete (FK‑guarded) / wipeAll |
| `ChannelCategoryRepository` | `InMemoryChannelCategoryRepository` | `categories` | create / update / delete (FK‑guarded) / wipeAll |
| `MessageCategoryRepository` | `InMemoryMessageCategoryRepository` | `categories` | create / update / delete (FK‑guarded) / wipeAll |
| `MissionTypeRepository` | `InMemoryMissionTypeRepository` | `types` | create / update / delete (FK‑guarded) / wipeAll |
| `ClearanceRepository` | `InMemoryClearanceRepository` | `clearanceFor(userId, missionId)` | assign / unassign / wipeAll |

All FK‑guarded deletes return a `DeleteResult.BlockedBy(refs)` listing the referencing entities.

---

## E. Seeded data (first launch, after wipe, or after empty sync)

Source: [SeedData.kt](../app/src/main/java/com/explo/capstone/shared/data/SeedData.kt). No demo missions are seeded.

- **Ranks:** OBSERVER (1), OPERATIVE (5), CHIEF (9)
- **ChannelCategories:** COMMAND & CONTROL (9/9), RECON & INTEL (5/5), LOGISTICS (1/1)
- **MessageCategories:** COMMAND (9), INTELLIGENCE (5), STANDARD (1), RESTRICTED (9)
- **MissionTypes:** TOP SECRET, CONFIDENTIAL, RESTRICTED

All seeded records have `isSystem = true` and are immutable through normal CRUD.

---

## F. Lifecycle hooks worth knowing

| Trigger | Effect |
|---------|--------|
| Any non‑message/non‑document mutation in `InMemoryStore` | `onChanged` → `PersistenceManager.save()` writes `astra_store.json` |
| Relay WS `sync_invalidated` | App calls `GET /api/sync` and replaces in‑memory state |
| Relay WS `keysNeeded` | App generates new OPKs, calls `PUT /v1/keys` |
| SPK older than 7 days | App rotates SPK, keeps previous for 48h, calls `PUT /v1/keys/signed` |
| Panic | `wipeAll` on every repository → `PersistenceManager.clear()` → `astra_state.terminal = true` |
| App relaunch after panic | Tombstone forces re‑provisioning before any UI |
