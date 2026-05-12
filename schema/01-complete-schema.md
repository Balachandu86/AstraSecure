# 01 — Complete Schema (App + Server)

Exhaustive reference. Every entity defined or transmitted by AstraSecure as of 2026-05-09.

---

## 1. Enumerations

| Enum | Values | Used by |
|------|--------|---------|
| `MissionStatus` | `ACTIVE`, `STANDBY`, `COMPROMISED`, `ARCHIVED` | Mission lifecycle |
| `ColorToken` | `PRIMARY`, `TERTIARY`, `SECONDARY`, `ERROR`, `NEUTRAL` | UI colour assignment for schema records |
| `Severity` | `INFO`, `WARN`, `ALERT` | SecurityEvent log |
| `MessageType` (int constants) | `1` PREKEY_SIGNAL_MESSAGE, `2` WHISPER_MESSAGE, `3` SENDER_KEY_DIST, `4` SENDER_KEY_MESSAGE | Wire format on relay server |

Source: [Models.kt](../app/src/main/java/com/explo/capstone/shared/Models.kt), [TransportMessage.kt](../app/src/main/java/com/explo/capstone/transport/TransportMessage.kt)

---

## 2. Identity & User

### `User`
Hardware‑bound operator identity. Persisted in `EncryptedSharedPreferences("astra_identity")`.

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | Server‑assigned user id |
| `hardwareKeyId` | String | Android Keystore alias for the EC key pair |
| `displayName` | String | Callsign |
| `provisionedAtMs` | Long | First provisioning timestamp |

### `DeviceAttestation` (computed, never stored)
| Field | Type |
|-------|------|
| `isDeviceSecure` | Boolean |
| `hasStrongBox` | Boolean |
| `keystoreType` | `"STRONGBOX" \| "TEE"` |
| `hardwareKeyId` | String? |

---

## 3. Operational Entities

### `Mission` *(serializable, persisted)*
| Field | Type | Default |
|-------|------|---------|
| `id` | String | — |
| `name` | String | — |
| `typeId` | String → MissionType | — |
| `status` | MissionStatus | — |
| `phase` | String? | null |
| `missionKeyAlias` | String | "" |
| `participantIds` | List&lt;String&gt; | empty |
| `createdAtMs` | Long | — |
| `lastActivityMs` | Long | — |
| `createdBy` | String (userId) | "" |

### `Channel` *(serializable, persisted)*
| Field | Type | Default |
|-------|------|---------|
| `id` | String | — |
| `missionId` | String → Mission | — |
| `name` | String | — |
| `description` | String | — |
| `categoryId` | String → ChannelCategory | — |
| `minClearanceToView` | Int | (from category) |
| `minClearanceToPost` | Int | (from category) |
| `createdAtMs` | Long | — |
| `createdBy` | String (userId) | "" |

### `Message` *(in‑memory only, never persisted)*
| Field | Type | Notes |
|-------|------|-------|
| `id` | String | `MSG-{uuidPrefix}` |
| `channelId` | String → Channel | |
| `senderId` | String → User | |
| `categoryId` | String → MessageCategory | |
| `encryptedContent` | ByteArray | Signal ciphertext, padded |
| `paddedSizeBytes` | Int | Metadata‑normalisation pad |
| `timestampMs` | Long | |

### `EncryptedDocument` *(metadata in EncryptedSharedPreferences, bytes in `filesDir/vault/{id}.bin`)*
| Field | Type |
|-------|------|
| `id` | String (`DOC-{uuidPrefix}`) |
| `missionId` | String → Mission |
| `ownerUserId` | String → User |
| `encryptedBytes` | ByteArray (AES‑256‑GCM) |
| `fileName` | String |
| `createdAtMs` | Long |

---

## 4. Schema Records (user‑editable taxonomies)

### `Rank`
| Field | Type | Default |
|-------|------|---------|
| `id`, `name` | String | — |
| `level` | Int | gating uses this |
| `color` | ColorToken | — |
| `isSystem` | Boolean | false |

### `ChannelCategory`
| Field | Type |
|-------|------|
| `id`, `name` | String |
| `accent` | ColorToken |
| `defaultMinClearanceToView` | Int |
| `defaultMinClearanceToPost` | Int |
| `isSystem` | Boolean |

### `MessageCategory`
| Field | Type |
|-------|------|
| `id`, `name` | String |
| `accent` | ColorToken |
| `minClearanceToSend` | Int |
| `isSystem` | Boolean |

### `MissionType`
| Field | Type |
|-------|------|
| `id`, `name`, `description` | String |
| `accent` | ColorToken |
| `isSystem` | Boolean |

### `ClearanceAssignment` (join table)
| Field | Type |
|-------|------|
| `userId` | String → User |
| `missionId` | String → Mission |
| `rankId` | String → Rank |

---

## 5. Audit / Diagnostic

### `SecurityEvent` *(in‑memory ring buffer, cap 100)*
| Field | Type |
|-------|------|
| `id` | String (`EVT-{uuidPrefix}`) |
| `tsMs` | Long |
| `severity` | Severity |
| `source` | String |
| `text` | String |

### `EntityRef` / `DeleteResult`
Returned by repositories when deletion is blocked by a foreign key.

---

## 6. Cryptographic Material (device‑local only)

All in `EncryptedSharedPreferences("astra_signal")` unless noted.

| Key prefix | Type | Lifecycle |
|------------|------|-----------|
| `signal_ikp` | IdentityKeyPair (Curve25519) | permanent |
| `signal_reg_id` | Int (14‑bit) | permanent |
| `signal_trust_{name}` | IdentityKey | TOFU per peer |
| `signal_session_{name}_{device}` | SessionRecord | per (peer, device); rotates with ratchet |
| `signal_pk_{id}` | PreKeyRecord (one‑time) | batch of 100; replenish at 10 |
| `signal_spk_{id}` | SignedPreKeyRecord | rotate weekly, 48h grace |
| `signal_spk_last_rotated_ms` | Long | rotation tracker |
| `signal_sk_{distId}_{name}_{device}` | SenderKeyRecord | per (channel, sender) |
| `signal_jwt` | String | bearer token for relay |

Per‑document AES‑256‑GCM keys live in the **Android Keystore** under alias `astra_doc_{docId}` (StrongBox‑backed when available).
The hardware identity key pair lives under `astra_identity_{uuid}`.

---

## 7. Local persistence files

| Path | Format | Contents |
|------|--------|----------|
| `filesDir/astra_store.json` | JSON (`StoreSnapshot`) | Missions, Channels, Ranks, all Categories, MissionTypes, Clearances |
| `EncryptedSharedPreferences("astra_identity")` | AES‑256‑GCM kvp | User identity + vault metadata pointers |
| `EncryptedSharedPreferences("astra_signal")` | AES‑256‑GCM kvp | All Signal protocol material + JWT |
| `filesDir/vault/{docId}.bin` | 12‑byte IV ++ AES‑256‑GCM ciphertext | Encrypted document payloads |
| `SharedPreferences("astra_state")` | plain | `terminal` tombstone marker (survives wipe) |

Notably **not** persisted: `Message`, `SecurityEvent`, in‑memory copies of document bytes.

---

## 8. In‑memory store

`InMemoryStore` exposes a `StateFlow<List<T>>` for each persisted collection plus messages/documents:

```
missions, channels, messages, documents,
ranks, channelCategories, messageCategories,
missionTypes, clearanceAssignments
```

A single `onChanged` callback wired in `AppContainer` triggers `PersistenceManager.save()` for everything **except** messages and documents (which are excluded by design).

---

## 9. Server APIs (overview)

Two servers are addressed:

### Signal relay (`SignalServerClient`)
Message queue + Signal key directory.
```
POST   /v1/users
GET    /v1/keys/{userId}
PUT    /v1/keys
PUT    /v1/keys/signed
POST   /v1/messages/{recipientId}
GET    /v1/messages
DELETE /v1/messages
POST   /v1/channels/{channelId}/members
GET    /v1/channels/{channelId}/members
DELETE /v1/users/{userId}
WS     /v1/websocket
```

### Astra application API (`AstraApiClient`)
Schema + ops state sync, ETag‑driven.
```
POST   /api/users
POST   /api/users/me/tombstone
GET    /api/sync                       (If-None-Match)
POST   /api/missions
PUT    /api/missions/{id}
DELETE /api/missions/{id}
POST   /api/missions/{id}/participants
DELETE /api/missions/{missionId}/participants/{userId}
POST   /api/channels
PUT    /api/channels/{id}
DELETE /api/channels/{id}
PUT    /api/clearances
DELETE /api/clearances/{userId}/{missionId}
```

For the full DTO / wire format see [02-server-schema.md](02-server-schema.md).
For the field‑by‑field correspondence with on‑device entities see [03-app-schema.md](03-app-schema.md).
