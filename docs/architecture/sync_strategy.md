# AstraSecure — Sync Strategy

This document specifies how the Android client and the AstraSecure REST API
keep server-owned state (missions, channels, schema, clearances) consistent
on each device.

The model is intentionally simple: **server is the source of truth; client
reflects it**. The local `astra_store.json` snapshot is a stale read fallback
for offline mode — never authoritative, never written back to the server in
isolation.

---

## 1. The cached entities

| Entity | Owner | Cached locally as |
|---|---|---|
| `schema_ranks` | Postgres | `InMemoryStore.ranks` |
| `schema_channel_categories` | Postgres | `InMemoryStore.channelCategories` |
| `schema_message_categories` | Postgres | `InMemoryStore.messageCategories` |
| `schema_mission_types` | Postgres | `InMemoryStore.missionTypes` |
| `missions` (caller's only) | Postgres | `InMemoryStore.missions` |
| `channels` (in caller's missions) | Postgres | `InMemoryStore.channels` |
| `clearance_assignments` (in caller's missions) | Postgres | `InMemoryStore.clearances` |

Persisted to disk as `astra_store.json` via `PersistenceManager`. Decrypted
message content is **never** persisted — see flow 5 in [data_flows.md](data_flows.md).

---

## 2. The version counter (per-user)

Every authenticated user has a server-side **monotonic version counter**:

```
sync_versions
├── user_id    TEXT PK FK → users.id
└── version    BIGINT NOT NULL DEFAULT 0
```

The version is bumped **after** the database transaction that mutates state
visible to that user commits successfully. The bump is itself part of the
same transaction (single `UPDATE ... SET version = version + 1`), so versions
and content move together.

**What bumps a user's version?**

| Mutation | Whose version bumps |
|---|---|
| Mission created | creator's version (creator is the only viewer at creation) |
| Mission edited / archived | every participant's version |
| Channel created / edited / deleted | every participant of the parent mission |
| Participant added | the new participant's version + every existing participant's version |
| Participant removed | the removed user's version + remaining participants' versions |
| Clearance assigned / removed | every participant of the mission |
| Schema (rank / category / mission-type) created/edited/deleted | **every** non-tombstoned user's version |
| User tombstoned (self) | the tombstoned user's version + every user who shares any mission |

Implementation: a single SQL stored procedure or service-layer helper
`bump_versions_for(user_ids[])` keeps the bump logic in one place. Applying
it to "every user" for schema changes is the simplest correct option; the
table is small (operators, not consumer scale) so the per-row write is cheap.

**ETag derivation:**
```
ETag = "\"<version>\""    (RFC 7232 strong validator, quoted)
```

---

## 3. The `/sync` endpoint

```
GET /api/sync
Authorization: Bearer <jwt>
If-None-Match: "<version>"        (optional)
```

**Server behavior:**

1. Validate JWT (see [auth.md §5](auth.md)).
2. Load `version = SELECT version FROM sync_versions WHERE user_id = sub`.
3. Parse `If-None-Match`. If the client's version equals the current version,
   return **304 Not Modified** with `ETag: "<version>"` and an empty body.
4. Otherwise, build the full payload (schema + scoped missions/channels/clearances)
   in a single read transaction at a snapshot, and return **200 OK** with
   `ETag: "<version>"`.

**The full read is intentional.** Building a delta-sync system (only the
records that changed since the client's last version) is more complex than
the value it adds at AstraSecure's scale. The full payload for a single
operator is small (~tens of KB even for power users), and gzip on the wire
makes it negligible. We may revisit this if/when the operator count grows
into the tens of thousands.

---

## 4. When the client calls `/sync`

| Trigger | Behavior |
|---|---|
| App launch (cold start) | Always call. If `If-None-Match` returns 304, hydrate from local snapshot. |
| `Lifecycle.Event.ON_RESUME` | Call. 304 in steady state. |
| Receipt of a `sync_invalidated` WebSocket event | Call. The WS hint is the canonical signal something changed. |
| After any successful POST/PUT/DELETE | **Optionally** call (cheap re-validation). The local optimistic update is normally enough. Re-call only if the response includes `"resync": true` (server-issued hint when the response cannot itself convey all transitive effects, e.g. a participant removal that cascades). |
| Manual user action ("Refresh" pull-down) | Call without `If-None-Match` (force refresh). |
| Network restored after offline period | Call. |

The client suppresses redundant calls — if a `/sync` is in flight and another
trigger fires, the second is debounced (waits for the first to complete, then
fires only if a newer trigger occurred while it was pending).

---

## 5. Optimistic updates + rollback

To keep the UI responsive, the client applies a mutation **locally first**,
then sends the request. The repository layer encodes this in a single helper
shape:

```kotlin
suspend fun <T> mutate(
    optimistic: () -> Unit,           // mutate InMemoryStore now
    request: suspend () -> T,         // POST/PUT/DELETE
    onSuccess: (T) -> Unit = {},      // reconcile if response carries server-side fields
    onFailure: () -> Unit,            // rollback the optimistic change
): Result<T>
```

**On 2xx:** keep the local change, apply `onSuccess` (e.g. patch in the
server-generated `id`, `createdAt`).

**On 4xx (except 401/410):** roll back, surface the error message to the user.
The local store is now consistent with the server again.

**On 401:** roll back, mark connection broken, prompt re-attestation
(see [auth.md §7](auth.md)).

**On 410:** roll back, mark device tombstoned, route to `TerminatedScreen`.

**On 5xx / network error:** keep the optimistic change, queue the request for
**bounded retry** (see §7), show a warning banner. If retries exhaust, roll back.

---

## 6. Push invalidation (the WebSocket channel)

Rather than poll, the server pushes a hint when the user's view becomes
stale. This reuses the **existing** Signal WebSocket connection — adding a
second WS just for sync would complicate connection management for negligible
benefit.

### New message type

The server may send (in addition to the existing `message` and `keysNeeded`
types):

```json
{
  "type": "sync_invalidated",
  "version": 1248,
  "reason": "schema" | "mission" | "clearance" | "participant"
}
```

`reason` is informational — useful for selective UI flashes (e.g. "Roster
updated" toast when `reason == "participant"`). Behaviorally, all reasons
trigger the same response: client calls `GET /api/sync`.

### Server emission

A `sync_invalidated` is emitted to user U when U's `sync_versions.version`
bumps **and** U has an active WebSocket. If U is offline, no push is sent —
U will pick up the change at next foreground via `/sync`. The push is
best-effort; loss is tolerable because `/sync` on resume is the safety net.

### Client handling

1. Read `version` from the push.
2. If `version <= local known version`, ignore (out-of-order; rare but possible).
3. Otherwise, fire `/sync` with `If-None-Match: "<local version>"`. The server
   will return 200 with the new payload (and a new ETag).
4. On success, replace `InMemoryStore` contents with the response, persist a
   new `astra_store.json` snapshot.

### Why version-in-the-push?

Two reasons:
- The client can compare cheaply and ignore stale pushes.
- If the WS connection drops between the bump and the push, the next `/sync`
  will pick up exactly the same state — versions are idempotent.

---

## 7. Retry policy

For 5xx and network errors on mutating endpoints:

```
attempt 1: immediate
attempt 2: +2 s   (jittered ±25%)
attempt 3: +5 s
attempt 4: +15 s
attempt 5: +60 s
give up: rollback optimistic change, audit WARN
```

For `/sync` specifically: at most **one** in-flight request per app session
(debounced). On failure, retry follows the same schedule **without** rolling
back local state — the snapshot remains valid until either `/sync` succeeds
or the user explicitly forces refresh.

The retry schedule is implemented in a single OkHttp interceptor wrapping
`AstraApiClient`, so all REST endpoints share the policy.

---

## 8. Conflict resolution

The conflict surface is small because clients only mutate per-user-scoped data
through narrow endpoints. The cases:

| Scenario | Resolution |
|---|---|
| Client A and Client B both edit the same mission name | Last write wins. The losing client's next `/sync` reflects the winning value. |
| Client deletes a channel that another client just edited | First-finalized server transaction wins. Second request returns 404 or 409; client rolls back. |
| Two admins assign different ranks to the same user/mission concurrently | Last write wins; server emits two consecutive `sync_invalidated` pushes. |
| Client believes user X has CHIEF; server has demoted X | Client's clearance check is a UI hint only; server re-validates on every mutating call (returns 403 if X tries to use stale CHIEF). |

We do **not** use ETag-on-mutation for optimistic locking. The server simply
applies the write and bumps the version. This is acceptable because the
authority model treats the operator with most-recent-write as expressing
the latest intent.

For the "demoting last chief" case (and other invariants), the **server**
enforces the rule in the transaction — see [api_contract.md §5](api_contract.md)
for the 409 `"last_chief"` response.

---

## 9. Offline mode

When the device cannot reach the server:

1. `connectionState` flips to `false` (already implemented in `SignalServerClient`).
2. UI shows a persistent banner: *"Working offline — changes will not be saved
   until reconnected."*
3. **Mutations are disabled.** The mutate-helper short-circuits with
   `Result.failure(OfflineException)` rather than queuing writes.
4. **Reads** continue from the `InMemoryStore` (loaded from snapshot at boot).
5. On reconnect: `connectionState` flips to `true`, `/sync` fires, banner
   clears.

**Why no offline write queue?** AstraSecure's authority model and clearance
gating make queued writes dangerous: a write composed offline against
clearance state X and applied online against state X' could violate access
controls in subtle ways. The simpler rule — *mutations require online* —
is honest about the tradeoff and matches the operational expectation of a
field-deployed secure messenger.

Sending **messages** while offline is a separate concern, handled by the
Signal transport layer's local queue. That's an end-to-end-encrypted stream
between operators, not a server-state mutation.

---

## 10. Sender key rotation on participant change

When a user is removed from a mission, the channel's sender keys held by
remaining members are stale (the removed device still has them, though it
can no longer fetch new envelopes from the relay because its membership in
`/v1/channels/{id}/members` is also removed by the server-to-server call
described in [api_contract.md §4](api_contract.md)).

To prevent the removed user from decrypting new traffic if they exfiltrated
their sender-key state before removal, **remaining members rotate sender keys
on next send**. Concretely:

1. The client receives `sync_invalidated` with `reason: "participant"`.
2. After re-syncing, the client compares the new mission roster to the
   pre-sync roster.
3. If anyone was removed from a mission the caller is in, the client marks
   every channel of that mission as **needing SKDM rotation**.
4. On the next message send into a marked channel, the sender generates a
   fresh sender key, distributes a new SKDM to all current members, and uses
   the new key for the message.

The rotation is **not** a synchronous step on the participant-removal endpoint
because it requires per-device cryptographic state that lives in
`EncryptedSharedPreferences`, never on the server. The server's role is to
reliably notify; the client's role is to rotate.

---

## 11. Snapshot persistence

After every successful mutation or `/sync` 200 response, the client writes
a fresh `astra_store.json` to disk via `PersistenceManager.save()`. The
snapshot includes the current `version` so cold starts can call `/sync` with
`If-None-Match` correctly.

Snapshot format (illustrative):
```json
{
  "version": 1247,
  "savedAt": "2026-05-09T14:32:11Z",
  "schema": { ... },
  "missions": [ ... ],
  "channels": [ ... ],
  "clearances": [ ... ]
}
```

The snapshot is **encrypted at rest** via the same EncryptedSharedPreferences
master-key path as identity storage (i.e. wrapped via Android Keystore). It
is wiped during panic — see flow 9 in [data_flows.md](data_flows.md).

---

## 12. What this strategy explicitly does not do

- **No CRDTs.** Last-write-wins is sufficient for the data shapes in scope.
- **No client-side merge of concurrent edits.** Server resolves; client reflects.
- **No delta sync.** Full payload on every change. Revisit at scale.
- **No offline write queue.** Mutations require online state — simpler and safer.
- **No multi-device sync.** One device per identity (Phase 0 constraint).

---

## 13. Open questions

- Should `sync_invalidated` be coalesced server-side? (e.g. five rapid mission
  edits → one push instead of five.) **Default: yes**, with a 250 ms debounce
  per `(user_id)` key. Implementation deferred to Phase 5.
- Should we expose a `GET /api/sync/version` lightweight endpoint for HEAD-style
  checks? **Default: no** — `If-None-Match` on `/sync` already covers it.
- ETag format: just the version vs. `version-userid` vs. opaque hash? **Default:
  just the version**, single-server installation. Revisit when we run replicas.
