# 02 — Server Schema

What lives on the server side. AstraSecure speaks to **two** servers: a Signal relay and an Astra application server. Neither one ever sees plaintext message content.

---

## A. Signal Relay Server

Source: [SignalServerClient.kt](../app/src/main/java/com/explo/capstone/transport/SignalServerClient.kt), [TransportMessage.kt](../app/src/main/java/com/explo/capstone/transport/TransportMessage.kt).

### A.1 What the server stores per user

| Bucket | Field(s) | Purpose |
|--------|----------|---------|
| **User record** | `userId`, `displayName`, `registrationId`, `identityKey` (b64 Curve25519) | Public identity directory |
| **Signed PreKey** | `id`, `publicKey`, `signature` | X3DH long‑lived |
| **One‑Time PreKeys** | list of `{id, publicKey}` | X3DH single‑use; refilled when count < 10 |
| **Channel membership** | per `channelId` → `[userId, …]` | Fan‑out target list |
| **Pending message queue** | `{id, senderId, channelId, messageType, ciphertext}` | Awaits client pickup / ACK |
| **JWT auth token** | bearer token | Issued at register; reused by HTTP + WS |

Plaintext message bodies, mission names, channel names, ranks, clearances — **none** of these live on the relay.

### A.2 Endpoints

```
POST   /v1/users                              register
GET    /v1/keys/{userId}                     fetchPreKeyBundle
PUT    /v1/keys                              uploadPreKeys (one-time)
PUT    /v1/keys/signed                       uploadSignedPreKey
POST   /v1/messages/{recipientId}            sendMessage
GET    /v1/messages                          fetchMessages
DELETE /v1/messages                          ack (by id list)
POST   /v1/channels/{id}/members             joinChannel
GET    /v1/channels/{id}/members             listMembers
DELETE /v1/users/{userId}                    panic-wipe a user
WS     /v1/websocket                         push events
```

All HTTP requests carry `Authorization: Bearer {jwt}`.
WebSocket sends auth as the first frame: `{"type":"auth","token":"…"}`.

### A.3 DTOs

```kotlin
RegisterRequest       { userId, displayName, registrationId, identityKey, signedPreKey, oneTimePreKeys }
RegisterResponse      { token }                       // JWT
PreKeyDto             { id, publicKey }               // base64
SignedPreKeyDto       { id, publicKey, signature }    // base64
PreKeyBundleResponse  { userId, registrationId, identityKey, signedPreKey, oneTimePreKey? }
UploadPreKeysRequest  { oneTimePreKeys: [PreKeyDto] }
SendMessageRequest    { senderId, channelId, messageType, ciphertext }
QueuedMessageDto      { id, senderId, channelId, messageType, ciphertext }
FetchMessagesResponse { messages: [QueuedMessageDto] }
AckMessagesRequest    { messageIds: [Long] }
JoinChannelRequest    { userId }
ChannelMembersResponse{ memberIds: [String] }
```

### A.4 Server‑pushed WS events

| Type | Payload | Triggers (client) |
|------|---------|-------------------|
| `message` | `{id, senderId, channelId, messageType, ciphertext}` | `MessageRepository.receive` |
| `keysNeeded` | `{currentCount}` | OPK replenishment |
| `sync_invalidated` | `{version, reason}` | Forces `/api/sync` re‑fetch on Astra API |
| `ack` | `{messageIds}` | (client → server) acknowledgement |

### A.5 Wire `messageType` constants

| Value | Name | Meaning |
|-------|------|---------|
| 1 | `PREKEY_SIGNAL_MESSAGE` | First message in a 1:1 session (X3DH bootstrap) |
| 2 | `WHISPER_MESSAGE` | Subsequent 1:1 (Double Ratchet) |
| 3 | `SENDER_KEY_DIST` | Sender‑Key distribution wrapped in 1:1 |
| 4 | `SENDER_KEY_MESSAGE` | Channel broadcast under group cipher |

---

## B. Astra Application Server

Source: [AstraApiClient.kt](../app/src/main/java/com/explo/capstone/transport/AstraApiClient.kt).

### B.1 What this server stores

This is the **shared organisational state** that must survive across devices and users:

- Users (callsigns, ids)
- Missions (status, phase, type, participants, creator)
- Channels (clearance overrides, category, creator)
- Schema records (Ranks, ChannelCategories, MessageCategories, MissionTypes)
- Clearance assignments (user × mission → rank)
- Versioning metadata (`version`, ETag) for sync

It does **not** store: messages, documents, signal keys, JWTs.

### B.2 Endpoints

```
POST   /api/users                                            registerUser
POST   /api/users/me/tombstone                              tombstone (panic)
GET    /api/sync                       (If-None-Match)      fetch full delta
POST   /api/missions                                        createMission
PUT    /api/missions/{id}                                   updateMission
DELETE /api/missions/{id}                                   deleteMission
POST   /api/missions/{id}/participants                      addParticipant
DELETE /api/missions/{missionId}/participants/{userId}      removeParticipant
POST   /api/channels                                        createChannel
PUT    /api/channels/{id}                                   updateChannel
DELETE /api/channels/{id}                                   deleteChannel
PUT    /api/clearances                                      assignClearance
DELETE /api/clearances/{userId}/{missionId}                 removeClearance
```

### B.3 DTOs (server‑authoritative shape)

```kotlin
SyncResponse {
    version: Long
    etag:    String
    schema:  SyncSchema
    missions:    [MissionDto]
    channels:    [ChannelDto]
    clearances:  [ClearanceDto]
}

SyncSchema {
    ranks, channelCategories, messageCategories, missionTypes
}

MissionDto { id, name, typeId, status, phase?, missionKeyAlias,
             participantIds, createdAtMs, lastActivityMs, createdBy }

ChannelDto { id, missionId, name, description, categoryId,
             minClearanceToView, minClearanceToPost, createdAtMs, createdBy }

RankDto             { id, name, level, color, isSystem }
ChannelCategoryDto  { id, name, accent, defaultMinClearanceToView, defaultMinClearanceToPost, isSystem }
MessageCategoryDto  { id, name, accent, minClearanceToSend, isSystem }
MissionTypeDto      { id, name, accent, description, isSystem }
ClearanceDto        { userId, missionId, rankId }

RegisterUserRequest      { callsign }
CreateMissionRequest     { name, typeId }
UpdateMissionRequest     { status?, phase? }
AddParticipantRequest    { userId }
CreateChannelRequest     { missionId, name, description, categoryId, minClearanceToView?, minClearanceToPost? }
UpdateChannelRequest     { minClearanceToView?, minClearanceToPost? }
AssignClearanceRequest   { userId, missionId, rankId }
```

### B.4 Sync semantics

- `GET /api/sync` is conditional via the `If-None-Match` ETag.
- A successful response replaces the on‑device store contents (after merging seeded system records).
- The relay's `sync_invalidated` WS event is the trigger for clients to re‑GET `/api/sync`.
- `version: Long` is monotonic and lets clients detect stale local state.

---

## C. What the server does **not** know

| Topic | Why |
|-------|-----|
| Plaintext message body | Encrypted with Signal Sender Key on the device before send |
| Document bytes | Stored only in the device vault, never transmitted |
| Per‑document encryption keys | Live in the device Keystore (StrongBox if available) |
| Security event log | Local ring buffer; never uploaded |
| Hardware attestation results | Computed on demand on the device |
| Mission key alias | Local Keystore alias; opaque on the wire |

For how each server field maps back to the on‑device entity, see [03-app-schema.md](03-app-schema.md).
