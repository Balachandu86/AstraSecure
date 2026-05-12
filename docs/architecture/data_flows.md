# AstraSecure — Data Flows

All flows described here represent the **target state** of the system.
Deviations from the current implementation are noted inline.

---

## 1. First-time Provisioning

A new operator registers on a fresh device.

```
Device                          Signal Relay              PostgreSQL (REST API)
  │                                  │                           │
  │── 1. Generate EC keypair ────────────────────────────────────────────────▶
  │   (Android Keystore, non-exportable)                         │
  │                                  │                           │
  │── 2. Generate Signal keys ───────────────────────────────────────────────▶
  │   (identity key, reg ID, SPK, 100 OPKs)                      │
  │                                  │                           │
  │── 3. POST /v1/accounts ─────────▶│                           │
  │   { callsign, identityKey,        │                           │
  │     registrationId, spk, opks }   │                           │
  │                                  │── 4. Store keys ─────────▶│
  │                                  │                    users.identity_key
  │◀─ 5. 200 OK { JWT } ────────────│                           │
  │                                  │                           │
  │── 6. POST /api/users ────────────────────────────────────────▶│
  │   { userId, callsign }            │                    INSERT users row
  │◀─ 7. 200 OK ─────────────────────────────────────────────────│
  │                                  │                           │
  │── 8. GET /api/sync ──────────────────────────────────────────▶│
  │                                  │            SELECT schema, missions,
  │◀─ 9. { ranks, categories,  ──────────────────────────────────│
  │        types, missions: [] }      │                           │
  │                                  │                           │
  │── 10. Hydrate InMemoryStore ─────────────────────────────────────────────
  │    Persist astra_store.json       │                           │
  │                                  │                           │
  ▼  App ready (empty mission list)   │                           │
```

**Current gap:** Steps 6–10 do not exist. The app never calls a REST API after provisioning. Schema comes from `SeedData.kt` instead of the server.

---

## 2. App Launch (returning user)

```
Device                          Signal Relay              PostgreSQL (REST API)
  │                                  │                           │
  │── 1. Load UserIdentity ──────────────────────────────────────────────────
  │   from EncryptedSharedPreferences │                           │
  │                                  │                           │
  │── 2. Load astra_store.json ──────────────────────────────────────────────
  │   Hydrate InMemoryStore (offline  │                           │
  │   snapshot — stale but usable)    │                           │
  │                                  │                           │
  │── 3. Open WebSocket ────────────▶│                           │
  │   (for incoming message push)     │                           │
  │                                  │                           │
  │── 4. GET /api/sync ──────────────────────────────────────────▶│
  │                                  │         SELECT all data for userId
  │◀─ 5. { missions, channels,  ─────────────────────────────────│
  │        schema, clearances }       │                           │
  │                                  │                           │
  │── 6. Merge into InMemoryStore ───────────────────────────────────────────
  │   Server wins on conflict         │                           │
  │   Persist updated snapshot        │                           │
  │                                  │                           │
  ▼  App ready (fresh data)           │                           │
```

**Current gap:** Steps 3–6 do not happen. The app only reads from the local snapshot. There is no sync call.

---

## 3. Mission Creation

```
Operator (Client)                                    PostgreSQL (REST API)
  │                                                        │
  │── 1. User taps "Create Mission" ───────────────────────────────────────
  │   Fills name + mission type                            │
  │                                                        │
  │── 2. POST /api/missions ───────────────────────────────▶
  │   { name, typeId, createdBy: userId }                  │
  │                                            INSERT missions
  │                                            INSERT mission_participants
  │                                              (userId, missionId)
  │                                            INSERT clearance_assignments
  │                                              (userId, missionId, rank_chief)
  │◀─ 3. 201 { mission } ──────────────────────────────────│
  │                                                        │
  │── 4. Update InMemoryStore locally ─────────────────────────────────────
  │   (optimistic update already shown in UI)              │
  │                                                        │
  ▼  Mission visible, creator has CHIEF clearance          │
```

**Current gap:** Step 2 is local only (`missionRepository.create()` writes to `InMemoryStore`). No REST call is made. No CHIEF clearance is auto-assigned (the bug identified earlier).

---

## 4. Adding a Participant + Clearance Assignment

```
Admin (Client)                                       PostgreSQL (REST API)
  │                                                        │
  │── 1. Admin Console → CLEARANCE tab ────────────────────────────────────
  │   Types userId, clicks ADD                             │
  │                                                        │
  │── 2. POST /api/missions/{id}/participants ─────────────▶
  │   { userId }                                           │
  │                                            INSERT mission_participants
  │                                            INSERT clearance_assignments
  │                                              (userId, missionId, rank_observer)
  │◀─ 3. 200 OK ───────────────────────────────────────────│
  │                                                        │
  │── 4. Admin selects a rank for the user ────────────────────────────────
  │                                                        │
  │── 5. PUT /api/clearances ──────────────────────────────▶
  │   { userId, missionId, rankId }                        │
  │                                            UPSERT clearance_assignments
  │◀─ 6. 200 OK ───────────────────────────────────────────│
  │                                                        │
  │── 7. Update InMemoryStore ─────────────────────────────────────────────
  │                                                        │
  ▼  New participant visible with assigned rank             │
```

**Current gap:** Steps 2 and 5 are local only. Changes never reach the server or other devices.

---

## 5. Sending a Message

This flow is the most correct in the current implementation. Signal is used properly.

```
Sender (Client)         Signal Relay            Recipient (Client)
  │                          │                        │
  │── 1. User types msg ──────────────────────────────────────────────────
  │   Selects message category                         │
  │   Clearance check: userLevel >= channel.minPost    │
  │                          │                        │
  │── 2. Encrypt with Sender Key ──────────────────────────────────────────
  │   (libsignal group cipher)│                        │
  │                          │                        │
  │   [If first msg in channel: distribute SKDM first] │
  │── 2a. Encrypt SKDM for each member ────────────────────────────────────
  │       (X3DH + Double Ratchet per recipient)        │
  │── 2b. POST /v1/messages (SKDM envelopes) ─▶        │
  │                          │── deliver ────────────▶ │
  │                          │                  Recipient processes SKDM
  │                          │                  Stores sender key state
  │                          │                        │
  │── 3. POST /v1/messages ──▶                         │
  │   (SENDER_KEY_MESSAGE     │                        │
  │    envelope, encrypted)   │── deliver ────────────▶│
  │                          │                        │
  │◀─ 4. ACK ───────────────│                         │── 5. Decrypt msg
  │                          │── delete envelope ───▶  │    (Sender Key)
  │                          │                        │
  │                          │                        ▼ Message shown in UI
```

**Status:** This flow is correctly implemented end-to-end.

---

## 6. Channel Creation

```
Operator (Client)                                    PostgreSQL (REST API)
  │                                                        │
  │── 1. User opens New Channel sheet ─────────────────────────────────────
  │   Fills name, selects category                         │
  │   Clearance check: userLevel >= OPERATIVE (5)          │
  │                                                        │
  │── 2. POST /api/channels ───────────────────────────────▶
  │   { missionId, name, categoryId,                       │
  │     minClearanceView, minClearancePost }                │
  │                                            INSERT channels
  │                                            (inherits category defaults
  │                                             if not overridden)
  │◀─ 3. 201 { channel } ──────────────────────────────────│
  │                                                        │
  │── 4. Update InMemoryStore ─────────────────────────────────────────────
  │                                                        │
  ▼  Channel visible to operators with sufficient clearance │
```

**Current gap:** Step 2 is local only.

---

## 7. Schema Change (Admin updates a rank/category)

```
Admin (Client)                                       PostgreSQL (REST API)     Other Clients
  │                                                        │                       │
  │── 1. Admin Console edits rank ─────────────────────────────────────────────────────
  │                                                        │                       │
  │── 2. PUT /api/schema/ranks/{id} ───────────────────────▶                       │
  │   { name, level, color }                               │                       │
  │                                            UPDATE schema_ranks                 │
  │◀─ 3. 200 OK ───────────────────────────────────────────│                       │
  │                                                        │                       │
  │── 4. Update local InMemoryStore ───────────────────────────────────────────────
  │                                                        │                       │
  │                                                        │── push notification ──▶│
  │                                                        │   (or clients poll)    │
  │                                                        │                  5. Re-sync
  │                                                        │                  schema
  │                                                        │                       │
  ▼  Admin sees change immediately                         │                  ▼ Other operators
                                                                             see updated schema
```

**Current gap:** Steps 2–5 do not exist. Admin changes are local to one device only and are lost on reinstall.

---

## 8. Document Vault — Store & Retrieve

```
Operator (Client)                              Device Keystore / Disk
  │                                                   │
  │── 1. Import file via SAF ─────────────────────────────────────────────
  │                                                   │
  │── 2. Generate doc key ────────────────────────────▶
  │   AES-256-GCM key in Keystore                     │
  │   Alias: "vault_{docId}"                          │
  │                                                   │
  │── 3. Encrypt file bytes ──────────────────────────────────────────────
  │   Using doc key from Keystore                     │
  │                                                   │
  │── 4. Write ciphertext to disk ─────────────────────▶
  │   Internal app storage                            │
  │                                                   │
  │── 5. Store metadata in SharedPreferences ──────────▶
  │   { docId, filename, size, missionId }            │
  │                                                   │
  ▼  Document stored                                  │
  │                                                   │
  │── 6. User taps Decrypt & Save ─────────────────────────────────────────
  │                                                   │
  │── 7. Load ciphertext from disk ────────────────────▶
  │◀─ 8. Encrypted bytes ──────────────────────────────│
  │                                                   │
  │── 9. Decrypt via Keystore ─────────────────────────▶
  │◀─ 10. Plaintext bytes ─────────────────────────────│
  │                                                   │
  │── 11. Write to Downloads via SAF ──────────────────────────────────────
  │                                                   │
  ▼  File saved to user-chosen location               │
```

**Status:** This flow is correctly implemented. Vault is intentionally device-local (no server sync — by design for security).

---

## 9. Panic Wipe

```
Operator (Client)         Signal Relay         PostgreSQL (REST API)
  │                            │                      │
  │── 1. Drag to confirm ───────────────────────────────────────────────
  │                            │                      │
  │── 2. POST /v1/accounts/me (DELETE) ─▶             │
  │                     Delete all keys               │
  │                     Close WS connection           │
  │                            │                      │
  │── 3. POST /api/users/me/tombstone ─────────────────▶
  │                            │               SET tombstoned = TRUE
  │                            │               DELETE clearance_assignments
  │                            │               (missions/channels retained
  │                            │                for other participants)
  │◀─ 4. 200 OK ───────────────────────────────────────│
  │                            │                      │
  │── 5. Local wipe (sequential) ───────────────────────────────────────
  │   a. Revoke Keystore keys   │                      │
  │   b. Overwrite EncSP data   │                      │
  │   c. Delete astra_store.json│                      │
  │   d. Write tombstone flag   │                      │
  │                            │                      │
  ▼  App shows TERMINATED screen                       │
  (requires OS-level Clear Data to re-provision)       │
```

**Current gap:** Steps 2 and 3 are not called. Wipe is local only — the server retains the user's registration and keys. Other operators cannot know the device was wiped.

---

## 10. Mission Archival

Soft-delete: missions cannot be hard-deleted via the API. `DELETE` sets `status = ARCHIVED`.
Archived missions remain visible to participants in a separate UI section but are read-only.

```
Chief (Client)                                       PostgreSQL (REST API)              Other participants
  │                                                        │                                    │
  │── 1. Mission detail → "Archive Mission" ────────────────────────────────────────────────────
  │   Confirm modal                                        │                                    │
  │                                                        │                                    │
  │── 2. DELETE /api/missions/{id} ────────────────────────▶                                    │
  │                                            UPDATE missions SET status = 'ARCHIVED'          │
  │                                            INSERT audit_event (severity=ALERT)              │
  │                                            BUMP sync_versions for all participants          │
  │◀─ 3. 204 No Content ───────────────────────────────────│                                    │
  │                                                        │                                    │
  │── 4. Update InMemoryStore (status = ARCHIVED) ──────────────────────────────────────────────
  │                                                        │                                    │
  │                                                        │── sync_invalidated push ──────────▶│
  │                                                        │   reason: "mission"                │
  │                                                        │                                    │── 5. GET /api/sync
  │                                                        │                                    │   Sees archived status
  │                                                        │                                    │   UI moves mission
  │                                                        │                                    │   to "Archived" tab
  ▼  Mission visible only in archived section, read-only   │                                    ▼
```

**Constraints**
- Caller must hold `rank_chief` on the mission. 403 otherwise.
- Channels of the mission are **not** deleted — they remain queryable for audit
  purposes. New messages cannot be sent (the relay rejects sends to channels of
  archived missions when the server-side clearance check lands in Phase 8).
- Participants and clearances are retained.
- An archived mission cannot be unarchived in Phase 0 spec (deferred — see
  open question in [api_contract.md §11](api_contract.md)).

**Current gap:** This endpoint is not yet implemented. The Android client has
no archive UI today — Phase 4 work.

---

## 11. Channel Deletion

Hard delete. Used when a channel was created in error or its purpose has ended.

```
Chief (Client)                          REST API                 Signal Relay              Mission participants
  │                                        │                           │                          │
  │── 1. Channel settings → Delete ─────────────────────────────────────────────────────────────────
  │   Confirm modal                        │                           │                          │
  │                                        │                           │                          │
  │── 2. DELETE /api/channels/{id} ────────▶│                           │                          │
  │                              DELETE FROM channels WHERE id = ?     │                          │
  │                              INSERT audit_event (severity=WARN)    │                          │
  │                              BUMP sync_versions for participants    │                          │
  │                                        │                           │                          │
  │                                        │── 3. DELETE /v1/admin/channels/{id}/members ────────▶│
  │                                        │   X-Internal-Token         │                          │
  │                                        │                  Clear channel membership            │
  │                                        │◀─ 4. 204 ──────────────────│                          │
  │                                        │                           │                          │
  │◀─ 5. 204 No Content ───────────────────│                           │                          │
  │                                        │                           │                          │
  │── 6. Update InMemoryStore (remove channel) ───────────────────────────────────────────────────
  │                                        │                           │                          │
  │                                        │── sync_invalidated ─────────────────────────────────▶│
  │                                        │   reason: "mission"                                  │
  │                                        │                                                       │── 7. /sync
  │                                        │                                                       │   Channel disappears
  ▼  Channel removed from list             │                           │                          ▼
```

**Notes**
- Members' device-side sender key state for the channel is **orphaned**, not
  actively wiped. It is harmless: there are no envelopes left in the relay
  carrying that channel ID, and the Sender Key cipher cannot decrypt arbitrary
  ciphertext. The orphan keys age out at the next app reinstall.
- If a member sends a message into the deleted channel via a stale client
  (race between sync push and a queued send), the relay rejects with 404.
- Caller must hold `rank_chief` on the parent mission. 403 otherwise.

**Current gap:** Endpoint not implemented. UI lacks a delete-channel affordance.
Phase 4 work.

---

## 12. Participant Removal

Removes a user from a mission. Triggers a sender-key rotation by remaining
members (see [sync_strategy.md §10](sync_strategy.md)).

```
Chief (Client)              REST API                Signal Relay            Removed user           Remaining members
  │                            │                          │                       │                       │
  │── 1. Admin Console →       │                          │                       │                       │
  │   Roster → Remove user ────────────────────────────────────────────────────────────────────────────────
  │   Confirm modal             │                          │                       │                       │
  │                            │                          │                       │                       │
  │── 2. DELETE /api/missions/{mid}/participants/{uid} ────▶                       │                       │
  │   Guard: cannot remove last chief                                              │                       │
  │                  BEGIN TRANSACTION                                              │                       │
  │                  DELETE FROM mission_participants                              │                       │
  │                  DELETE FROM clearance_assignments                             │                       │
  │                  INSERT audit_event (severity=ALERT)                           │                       │
  │                  BUMP sync_versions: removed user + remaining participants     │                       │
  │                  COMMIT                                                        │                       │
  │                            │                          │                       │                       │
  │                            │── 3. DELETE /v1/admin/channels/{cid}/members ────▶                       │
  │                            │   for each channel of mission, remove (uid)                              │
  │                            │◀─ 4. 204 (per channel) ───                       │                       │
  │                            │                          │                       │                       │
  │◀─ 5. 204 No Content ───────│                          │                       │                       │
  │                            │                          │                       │                       │
  │── 6. Update InMemoryStore (remove participant + clearance) ─────────────────────────────────────────────
  │                            │                          │                       │                       │
  │                            │── sync_invalidated ──────────────────────────────▶                       │
  │                            │   reason: "participant"                          │                       │
  │                            │                                                  │── 7a. /sync           │
  │                            │                                                  │   Mission disappears  │
  │                            │                                                  │   from their list     │
  │                            │                                                  │                       │
  │                            │── sync_invalidated ──────────────────────────────────────────────────────▶│
  │                            │   reason: "participant"                          │                       │
  │                            │                                                  │                       │── 7b. /sync
  │                            │                                                  │                       │   Roster updates
  │                            │                                                  │                       │   Mark all
  │                            │                                                  │                       │   channels of
  │                            │                                                  │                       │   mission as
  │                            │                                                  │                       │   "needs SKDM
  │                            │                                                  │                       │    rotation"
  │                            │                                                  │                       │
  │                            │                                                  │                       │── 8. On next
  │                            │                                                  │                       │   send into any
  │                            │                                                  │                       │   marked channel,
  │                            │                                                  │                       │   generate fresh
  │                            │                                                  │                       │   sender key,
  │                            │                                                  │                       │   distribute new
  │                            │                                                  │                       │   SKDM to current
  │                            │                                                  │                       │   members only.
  ▼ Roster updated             │                          │                       ▼ User no longer sees   ▼
                                                                                    the mission
```

**Constraints**
- Caller must hold `rank_chief` on the mission. 403 otherwise.
- Caller cannot remove the **last chief** of a mission (would orphan the
  mission). Server returns 409 with detail `"last_chief"`.
- The removed user's existing decrypted message history (held in their device
  RAM) is not retroactively wiped — the security boundary is "no new traffic",
  not "forget what was already seen".
- The window of exposure between removal and SKDM rotation is documented as an
  accepted risk in [threat_model.md §3 E5](threat_model.md).

**Current gap:** Endpoint not implemented. The Admin Console has a roster UI
but the remove action only mutates the local store. Phase 4 work.

---

## 13. Sync Strategy (steady state)

After initial hydration, the client stays in sync through:

```
Trigger                     Action
──────────────────────────────────────────────────────────────────
App foreground              GET /api/sync (full refresh, cheap if ETag matches)
User creates/edits anything POST/PUT → server → update local cache on 2xx
WebSocket message received  Decrypt → append to in-memory message list
OPK count low               PUT /v1/keys (upload new batch)
SPK rotation timer fires    PUT /v1/keys/signed
Admin schema change         Server pushes invalidation → client re-fetches /sync
Participant removed         Server pushes invalidation → client triggers SKDM rotation
```

The local `astra_store.json` snapshot is always a **stale read fallback** for offline mode — never written to independently of a server confirmation.

For full sync semantics (ETag, push channel, retry, conflict, offline rules)
see [sync_strategy.md](sync_strategy.md).
