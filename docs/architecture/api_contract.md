# AstraSecure — REST API Contract

This document is the **authoritative specification** for every `/api/*` endpoint
the AstraSecure backend exposes. The Android client and the server team build
against this spec; if behavior diverges, this document wins.

The Signal relay endpoints (`/v1/users`, `/v1/keys`, `/v1/messages`,
`/v1/channels`, `/v1/websocket`) are owned by the existing Signal server and
are **not** part of this contract — see [system_overview.md](system_overview.md)
for that surface.

---

## Conventions

| Aspect | Convention |
|---|---|
| Base URL | `https://<host>/api/` |
| Format | JSON, UTF-8, `Content-Type: application/json` |
| Auth | `Authorization: Bearer <jwt>` on every request — see [auth.md](auth.md) |
| Time | All timestamps are ISO-8601 UTC (`2026-05-09T14:32:11Z`) |
| IDs | Server generates all IDs; client never invents them. Format: `M-{8-hex}` for missions, `C-{8-hex}` for channels, `R-{8-hex}` for ranks, etc. User IDs are UUIDv4 (matches Signal) |
| Versioning | Path prefix `/api/` is implicitly v1. A future v2 would live at `/api/v2/`; v1 stays mounted for at least one release after v2 ships |
| Errors | RFC 7807 problem+json on 4xx/5xx (see Error Format below) |
| ETag | `GET /api/sync` only — strong ETag, see [sync_strategy.md](sync_strategy.md) |

### Error Format

All non-2xx responses use [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807):

```json
{
  "type": "https://astrasecure.app/errors/clearance-denied",
  "title": "Insufficient clearance",
  "status": 403,
  "detail": "User R-9F3A1B2C lacks rank_chief on mission M-A3F9B1C2",
  "instance": "/api/missions/M-A3F9B1C2"
}
```

Standard status codes:

| Code | Meaning |
|---|---|
| 200 | OK |
| 201 | Created (POST that creates a new resource) |
| 204 | No Content (successful DELETE) |
| 304 | Not Modified (sync ETag match) |
| 400 | Malformed request body |
| 401 | Missing or invalid JWT |
| 403 | Authenticated but insufficient clearance |
| 404 | Resource does not exist or caller cannot see it |
| 409 | Conflict (duplicate, version mismatch on optimistic update) |
| 410 | Gone — caller is tombstoned, must re-provision |
| 422 | Validation error (e.g. rank level out of range) |
| 500 | Server error — client should retry with backoff |

`404` deliberately conflates "not found" and "not authorized to see" so the API
does not leak the existence of resources outside the caller's scope.

---

## Endpoint Index

| Group | Endpoints |
|---|---|
| Bootstrap | `GET /sync` |
| Users | `POST /users`, `POST /users/me/tombstone`, `GET /users/{id}` |
| Missions | `GET /missions`, `POST /missions`, `GET /missions/{id}`, `PUT /missions/{id}`, `DELETE /missions/{id}`, `POST /missions/{id}/participants`, `DELETE /missions/{id}/participants/{userId}` |
| Channels | `POST /channels`, `PUT /channels/{id}`, `DELETE /channels/{id}` |
| Clearances | `PUT /clearances`, `DELETE /clearances/{userId}/{missionId}` |
| Schema — Ranks | `GET /schema/ranks`, `POST /schema/ranks`, `PUT /schema/ranks/{id}`, `DELETE /schema/ranks/{id}` |
| Schema — Channel Categories | `GET`, `POST`, `PUT /{id}`, `DELETE /{id}` at `/schema/channel-categories` |
| Schema — Message Categories | `GET`, `POST`, `PUT /{id}`, `DELETE /{id}` at `/schema/message-categories` |
| Schema — Mission Types | `GET`, `POST`, `PUT /{id}`, `DELETE /{id}` at `/schema/mission-types` |
| Audit | `GET /audit`, `POST /audit` |

---

## 1. Bootstrap

### `GET /api/sync`

Single call returning everything the client needs to render the app for the
authed user. See [sync_strategy.md](sync_strategy.md) for caching semantics.

**Request headers**
- `Authorization: Bearer <jwt>` (required)
- `If-None-Match: "<etag>"` (optional — returns 304 if unchanged)

**Response 200**
```json
{
  "version": 1247,
  "etag": "\"1247\"",
  "schema": {
    "ranks": [ { "id": "rank_chief", "name": "CHIEF", "level": 9, "color": "TERTIARY", "isSystem": true }, ... ],
    "channelCategories": [ ... ],
    "messageCategories": [ ... ],
    "missionTypes": [ ... ]
  },
  "missions": [
    {
      "id": "M-A3F9B1C2",
      "name": "OPERATION SHEPHERD",
      "typeId": "mt_top_secret",
      "status": "ACTIVE",
      "phase": "PHASE 4 / EXTRACTION",
      "missionKeyAlias": "mission_M-A3F9B1C2",
      "participantIds": ["U-...", "U-..."],
      "createdAtMs": 1714867200000,
      "lastActivityMs": 1715040000000,
      "createdBy": "U-..."
    }
  ],
  "channels": [
    {
      "id": "C-9D7E5A1F",
      "missionId": "M-A3F9B1C2",
      "name": "command-bridge",
      "description": "primary command channel",
      "categoryId": "cc_command",
      "minClearanceToView": 9,
      "minClearanceToPost": 9,
      "createdAtMs": 1714867200000,
      "createdBy": "U-..."
    }
  ],
  "clearances": [
    { "userId": "U-...", "missionId": "M-A3F9B1C2", "rankId": "rank_chief" }
  ]
}
```

**Response 304** — body empty, client keeps current snapshot.

**Visibility rule:** the response only includes missions the caller participates
in, channels of those missions, clearances **of those missions only**, and the
full schema (schema is global). Other operators' clearances on the caller's
missions are included so the UI can render team rosters; clearances on
unrelated missions are not.

---

## 2. Users

### `POST /api/users`

Called immediately after the client successfully completes Signal registration.
Mirrors `users.id` and `users.callsign` into the REST DB so missions/clearances
can FK to a user row.

**Request**
```json
{
  "userId": "U-9F3A1B2C-...",
  "callsign": "ARGUS-7"
}
```

The `userId` and `identityKey` already exist on the Signal relay — the REST API
re-reads them via the JWT subject claim rather than trusting the client body.
The request body is therefore minimal (callsign only is required information
the relay does not have); `userId` is included for client clarity but the
server uses `sub` from the JWT.

**Response 201**
```json
{ "userId": "U-...", "callsign": "ARGUS-7", "createdAt": "2026-05-09T14:32:11Z" }
```

**Response 409** — callsign already taken.

### `POST /api/users/me/tombstone`

Called from `PanicRoute` immediately before `DELETE /v1/users/{userId}` on the
Signal relay. Marks the user as terminated so other operators can render their
absence. See flow 9 in [data_flows.md](data_flows.md).

**Request** — empty body.

**Response 200**
```json
{ "userId": "U-...", "tombstonedAt": "2026-05-09T14:32:11Z" }
```

**Side effects (server)**
- `UPDATE users SET tombstoned = TRUE`
- `DELETE FROM clearance_assignments WHERE user_id = ?`
- `DELETE FROM mission_participants WHERE user_id = ?`
- Missions and channels are **retained** — other participants still need them.
- Subsequent JWT validations for this `userId` return **410 Gone**.
- Audit event emitted with severity `ALERT`.
- `sync_invalidated` push to all peers who shared a mission with this user.

### `GET /api/users/{id}`

Lightweight lookup for rendering callsigns of other operators on missions the
caller can see.

**Response 200**
```json
{ "userId": "U-...", "callsign": "ARGUS-7", "tombstoned": false }
```

**Response 404** — caller does not share any mission with this user.

---

## 3. Missions

### `GET /api/missions`

Convenience endpoint for paginated listing. The `/sync` payload is preferred
for steady-state usage; this endpoint exists for admin tooling and pagination.

**Query params:** `?status=ACTIVE`, `?limit=50`, `?cursor=<opaque>`

**Response 200**
```json
{ "items": [ {...mission...} ], "nextCursor": "..." }
```

### `POST /api/missions`

Creates a mission. **The server atomically inserts:**
1. `missions` row
2. `mission_participants` row for the creator
3. `clearance_assignments` row granting `rank_chief` to the creator

This is the single transaction that closes gap **G4** in the implementation
plan — auto-CHIEF-on-creator must not be a client responsibility.

**Request**
```json
{ "name": "OPERATION SHEPHERD", "typeId": "mt_top_secret" }
```

**Response 201** — full mission object including `createdBy` and the creator's
new `rank_chief` clearance reflected in the next `/sync`.

**Errors**
- `422` — `typeId` does not exist
- `403` — caller is not allowed to create missions (currently any authed user
  may create; future iteration may gate behind a global rank)

### `GET /api/missions/{id}`

**Response 200** — mission object. **Response 404** if caller is not a participant.

### `PUT /api/missions/{id}`

Editable fields only: `name`, `status`, `phase`, `typeId`. Caller must hold
`rank_chief` on the mission.

**Request**
```json
{ "name": "...", "status": "ACTIVE", "phase": "PHASE 4 / EXTRACTION", "typeId": "mt_top_secret" }
```

Partial PATCH semantics: omitted fields are unchanged.

**Response 200** — updated mission.

### `DELETE /api/missions/{id}`

Soft-delete: sets `status = ARCHIVED`. Hard delete is not exposed via the API.
Caller must hold `rank_chief` on the mission.

**Response 204.**

### `POST /api/missions/{id}/participants`

Add a user to a mission. Caller must hold `rank_chief`.

**Request**
```json
{ "userId": "U-..." }
```

**Server side effects (atomic)**
1. Insert `mission_participants` row
2. Insert `clearance_assignments` row with default `rank_observer`

**Response 200** — empty body. Next `/sync` reflects the new participant +
default clearance. The added user receives a `sync_invalidated` push.

**Errors**
- `404` — userId does not exist (or is tombstoned)
- `409` — already a participant

### `DELETE /api/missions/{id}/participants/{userId}`

Remove a user from a mission. Caller must hold `rank_chief`. Caller cannot
remove themselves if they are the **only** chief — 409 with detail `"last_chief"`.

**Server side effects (atomic)**
1. Delete `mission_participants` row
2. Delete `clearance_assignments` row for `(userId, missionId)`
3. The Signal relay is **not** notified — sender keys for that channel will
   continue to deliver until the next SKDM rotation, which the remaining
   members trigger by sending the next message. (See sync_strategy.md
   "Sender key rotation on participant change.")

**Response 204.**

---

## 4. Channels

### `POST /api/channels`

**Request**
```json
{
  "missionId": "M-A3F9B1C2",
  "name": "command-bridge",
  "description": "primary command channel",
  "categoryId": "cc_command",
  "minClearanceToView": 9,
  "minClearanceToPost": 9
}
```

If `minClearanceToView` / `minClearanceToPost` are omitted, server uses the
category defaults from `schema_channel_categories`. Caller must hold a rank on
the mission with `level >= 5` (OPERATIVE) — matches current client gating.

**Response 201** — channel object.

**Errors**
- `403` — caller's clearance on `missionId` is below the create threshold
- `404` — `missionId` does not exist or caller is not a participant
- `422` — `categoryId` does not exist; or view-clearance > post-clearance

### `PUT /api/channels/{id}`

Editable fields: `name`, `description`, `minClearanceToView`, `minClearanceToPost`,
`categoryId`. Caller must hold `rank_chief` on the parent mission.

### `DELETE /api/channels/{id}`

Hard delete. Caller must hold `rank_chief`. **Side effects:**
- `channels` row deleted
- The Signal relay's `/v1/channels/{channelId}/members` registry is also
  cleared (server-to-server call from REST API to Signal relay).
- Sender key state on each member's device is orphaned but harmless.

**Response 204.**

---

## 5. Clearances

### `PUT /api/clearances`

Upsert: assigns or replaces a user's rank on a mission. Caller must hold
`rank_chief` on the mission. **Cannot demote the last chief** — server returns
409 with detail `"last_chief"`.

**Request**
```json
{ "userId": "U-...", "missionId": "M-...", "rankId": "rank_operative" }
```

**Response 200**
```json
{ "userId": "...", "missionId": "...", "rankId": "...", "previousRankId": "rank_observer" }
```

`previousRankId` is informational — useful for audit trails.

### `DELETE /api/clearances/{userId}/{missionId}`

Equivalent to removing the user's rank entirely. Implies they can no longer
view any channel on the mission. Caller must hold `rank_chief`. Same
"last chief" guard applies. Note: this is distinct from removing them as a
participant — leaves them in `mission_participants` but ungated. In practice
the admin UI will favor `DELETE /missions/{id}/participants/{userId}` which
does both.

---

## 6. Schema (Ranks / Categories / Mission Types)

All four schema groups follow the same shape. Endpoints are
`/api/schema/ranks`, `/api/schema/channel-categories`,
`/api/schema/message-categories`, `/api/schema/mission-types`.

**`is_system = true` records cannot be deleted, only renamed.** Server returns
422 with detail `"system_record"` on any DELETE attempt against a system row.

Mutating any schema record triggers a `sync_invalidated` WebSocket push to
**all** authenticated clients (schema is global). See [sync_strategy.md](sync_strategy.md).

### `GET /api/schema/{group}`

**Response 200**
```json
{ "items": [ {...}, {...} ] }
```

### `POST /api/schema/{group}`

**Request — ranks**
```json
{ "name": "ANALYST", "level": 3, "color": "PRIMARY" }
```

**Request — channel-categories**
```json
{
  "name": "OPSEC",
  "accent": "ERROR",
  "defaultMinClearanceToView": 5,
  "defaultMinClearanceToPost": 9
}
```

**Request — message-categories**
```json
{ "name": "FLASH", "accent": "ERROR", "minClearanceToSend": 9 }
```

**Request — mission-types**
```json
{ "name": "TRAINING", "accent": "NEUTRAL", "description": "..." }
```

Caller must hold a global admin role. **Open question (see open-questions.md
to be added in Phase 1):** is "global admin" derived from holding `rank_chief`
on any mission, or is it a separate `is_admin` flag on `users`? **Decision for
Phase 0:** the latter — add `users.is_admin BOOLEAN DEFAULT FALSE`. Provisioning
the first user sets it to TRUE.

**Response 201** — created record with server-generated `id`.

**Validation rules**
- Rank `level` must be in `[1, 99]` and unique among non-tombstoned ranks
- Category names must be unique within their group (case-insensitive)
- `color` / `accent` must be one of `PRIMARY | TERTIARY | SECONDARY | ERROR | NEUTRAL`
- `minClearanceTo*` must be >= 1

### `PUT /api/schema/{group}/{id}`

Same body shape as POST. **Response 200.**

### `DELETE /api/schema/{group}/{id}`

**Response 204** on success.

**Response 409** if any non-tombstoned record references this schema row
(e.g. a channel still uses this category). Body lists blockers:

```json
{
  "type": "https://astrasecure.app/errors/in-use",
  "title": "Schema record in use",
  "status": 409,
  "blockers": [
    { "type": "Channel", "id": "C-9D7E5A1F", "name": "command-bridge" }
  ]
}
```

This mirrors the existing client-side `DeleteResult.BlockedBy` pattern in
[Models.kt:153-156](app/src/main/java/com/explo/capstone/shared/Models.kt#L153-L156).

---

## 7. Audit

### `GET /api/audit`

Paginated reverse-chronological log. Caller sees only events scoped to:
- their own user
- missions they participate in (including events authored by other users on
  those missions, e.g. clearance changes)
- global schema events (admins only)

**Query params:** `?limit=100`, `?cursor=<opaque>`, `?since=<iso8601>`,
`?severity=ALERT`

**Response 200**
```json
{
  "items": [
    {
      "id": 4928,
      "userId": "U-...",
      "severity": "ALERT",
      "source": "Admin",
      "text": "Clearance changed: ARGUS-7 → CHIEF on M-A3F9B1C2",
      "missionId": "M-A3F9B1C2",
      "occurredAt": "2026-05-09T14:32:11Z"
    }
  ],
  "nextCursor": "..."
}
```

### `POST /api/audit`

Client-originated audit events (e.g. crypto warnings the device noticed but
the server cannot directly observe). Server stamps `occurred_at` and `user_id`
from the JWT — the client cannot forge these.

**Request**
```json
{
  "severity": "WARN",
  "source": "Crypto",
  "text": "SPK rotation failed once, retried successfully"
}
```

**Response 201** — empty body.

The server itself emits its own audit events on every mutating endpoint —
clients should not POST audit events that mirror server-known mutations.

---

## 8. Health & Diagnostics

### `GET /api/health`

Unauthenticated. Returns 200 + `{"status": "ok"}` when the server is healthy.
Used by the Android client's connection state indicator and by deployment
healthchecks.

---

## 9. Rate limits

Per JWT subject:

| Endpoint group | Limit |
|---|---|
| `/sync` | 60/min (cheap when ETag matches) |
| Mutations (`POST` / `PUT` / `DELETE`) | 30/min |
| `/audit` GET | 30/min |
| `/audit` POST | 60/min |
| Schema mutations | 10/min |

Exceeded → 429 with `Retry-After` header.

---

## 10. What is explicitly **not** in this API

- **Plaintext message content.** Messages travel via the Signal relay only;
  the REST API never sees decrypted content.
- **Identity keys / private key material.** Never transmitted, never stored
  here. The `users.identity_key` mirror in Postgres holds **public** keys only,
  and is read by the Signal relay rather than this API.
- **Session state (Double Ratchet, Sender Key state).** Device-only, never
  synced.
- **Document vault contents.** Documents are device-local by design (see flow
  8 in [data_flows.md](data_flows.md)).

---

## 11. Open questions deferred to Phase 1

- Should mission archival be reversible? (Currently DELETE → ARCHIVED, no UN-ARCHIVE endpoint specified.)
- Pagination opaque-cursor format (suggest base64-encoded `(occurred_at, id)` pair).
- Whether `users.is_admin` should be exposed via `GET /api/users/me` for client UI gating, or kept server-only.
- ETag scheme: monotonic version counter (server-wide) vs per-user version. **Default in [sync_strategy.md](sync_strategy.md): per-user counter** — admin schema changes increment everyone's counter.
