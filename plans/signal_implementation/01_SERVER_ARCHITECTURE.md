# Server Architecture

The server is a stateless relay. It holds public keys and routes opaque encrypted blobs. It has no cryptographic capability of its own. A single-instance PostgreSQL + HTTP server is sufficient for any tactical deployment size.

## Technology stack

| Layer | Choice | Reason |
|---|---|---|
| Database | PostgreSQL 16 | Atomic OPK consumption, ACID, BSD license, Docker-native |
| Runtime | Any HTTP server (Node.js/Express, Kotlin/Ktor, Python/FastAPI) | API contract is what matters; language is not prescribed |
| Real-time | WebSocket (RFC 6455) | Single persistent connection per device; server pushes queued messages on connect + on new arrival |
| Auth | JWT signed with HMAC-SHA256 | Issued on registration, presented as `Authorization: Bearer <token>` |

## PostgreSQL schema

```sql
-- ─── Users ───────────────────────────────────────────────────────────────────

CREATE TABLE users (
    user_id            TEXT        PRIMARY KEY,           -- matches User.id from IdentityManager
    display_name       TEXT        NOT NULL,
    registration_id    INTEGER     NOT NULL,              -- Signal: random 14-bit int, used in session headers
    identity_key       BYTEA       NOT NULL,              -- serialized IdentityKey public bytes (33 bytes, DJB)
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

-- ─── Signed pre-keys (medium-term, rotated ~weekly) ──────────────────────────

CREATE TABLE signed_prekeys (
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    spk_id             INTEGER     NOT NULL,
    spk_public         BYTEA       NOT NULL,              -- serialized ECPublicKey
    spk_signature      BYTEA       NOT NULL,              -- IK_A signs SPK_A; recipient verifies on session setup
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, spk_id)
);

-- ─── One-time pre-keys (consumed once, then deleted) ─────────────────────────

CREATE TABLE one_time_prekeys (
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    opk_id             INTEGER     NOT NULL,
    opk_public         BYTEA       NOT NULL,              -- serialized ECPublicKey
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, opk_id)
);

-- Refill trigger: server notifies client when count < 10
CREATE INDEX idx_opk_user ON one_time_prekeys(user_id);

-- ─── Message queue (ephemeral relay; deleted after delivery ACK) ──────────────

CREATE TABLE message_queue (
    id                 BIGSERIAL   PRIMARY KEY,
    recipient_id       TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    sender_id          TEXT        NOT NULL,              -- informational; not verified by server
    channel_id         TEXT        NOT NULL,              -- channel the message belongs to
    message_type       SMALLINT    NOT NULL,
    -- 1 = PreKeySignalMessage  (first message, contains X3DH material)
    -- 2 = SignalMessage        (subsequent messages in established session)
    -- 3 = SenderKeyDistributionMessage (channel sender key setup)
    ciphertext         BYTEA       NOT NULL,              -- Signal Protocol serialized ciphertext
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_queue_recipient ON message_queue(recipient_id, id);

-- ─── Channel membership ───────────────────────────────────────────────────────

CREATE TABLE channel_members (
    channel_id         TEXT        NOT NULL,
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    joined_at          TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (channel_id, user_id)
);
```

## REST API

All endpoints except `POST /v1/users` require `Authorization: Bearer <jwt>`.

---

### POST /v1/users — Register identity

Called once during provisioning. Uploads identity key + initial pre-key batch.

**Request body:**
```json
{
  "userId": "string (UUID from IdentityManager)",
  "displayName": "string",
  "registrationId": 12345,
  "identityKey": "base64-encoded IdentityKey public bytes",
  "signedPreKey": {
    "id": 1,
    "publicKey": "base64",
    "signature": "base64"
  },
  "oneTimePreKeys": [
    { "id": 1, "publicKey": "base64" },
    { "id": 2, "publicKey": "base64" }
    // ... batch of 100
  ]
}
```

**Response:** `201 Created` + `{ "token": "jwt" }`

---

### GET /v1/keys/{userId} — Fetch pre-key bundle

Called by Alice before sending her first message to Bob. Returns Bob's public keys for X3DH.

**Response:**
```json
{
  "userId": "string",
  "registrationId": 12345,
  "identityKey": "base64",
  "signedPreKey": {
    "id": 1,
    "publicKey": "base64",
    "signature": "base64"
  },
  "oneTimePreKey": {
    "id": 47,
    "publicKey": "base64"
  }
  // oneTimePreKey may be absent if OPKs are exhausted — session setup proceeds without it
}
```

**Side effect:** the returned OPK is atomically deleted from `one_time_prekeys`:
```sql
DELETE FROM one_time_prekeys
WHERE user_id = $1
AND opk_id = (
    SELECT opk_id FROM one_time_prekeys
    WHERE user_id = $1
    ORDER BY opk_id
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING opk_id, opk_public;
```

This is the operation that requires PostgreSQL's atomic `DELETE ... RETURNING`.

---

### PUT /v1/keys — Replenish one-time pre-keys

Called when the device detects its OPK count on the server has dropped below a threshold (the server reports current count in WebSocket push events).

**Request body:**
```json
{
  "oneTimePreKeys": [
    { "id": 101, "publicKey": "base64" },
    { "id": 102, "publicKey": "base64" }
  ]
}
```

---

### POST /v1/messages/{recipientId} — Send message

Called by Alice to relay a message to Bob. The server enqueues it; Bob's WebSocket connection (or next poll) delivers it.

**Request body:**
```json
{
  "senderId": "alice-uuid",
  "channelId": "channel-uuid",
  "messageType": 1,
  "ciphertext": "base64-encoded Signal ciphertext"
}
```

**Response:** `202 Accepted`

---

### GET /v1/messages — Fetch queued messages (polling fallback)

Used when WebSocket is unavailable. Returns all queued messages for the authenticated user.

**Response:**
```json
{
  "messages": [
    {
      "id": 1001,
      "senderId": "alice-uuid",
      "channelId": "channel-uuid",
      "messageType": 1,
      "ciphertext": "base64"
    }
  ]
}
```

---

### DELETE /v1/messages — Acknowledge delivery

Client calls this after successfully processing a batch. Server deletes the records.

**Request body:**
```json
{ "messageIds": [1001, 1002, 1003] }
```

---

### POST /v1/channels/{channelId}/members — Join channel

Registers the authenticated user as a member of a channel. Used during mission/channel creation.

---

### GET /v1/channels/{channelId}/members — List channel members

Returns user IDs of all channel members. Alice needs this to distribute her `SenderKeyDistributionMessage` to everyone.

**Response:**
```json
{ "memberIds": ["bob-uuid", "charlie-uuid"] }
```

---

## WebSocket protocol

Single connection per device, authenticated by JWT query param:
`wss://server/v1/websocket?token=<jwt>`

**Server → Client push events (JSON):**

```json
// New message arrived while connected
{ "type": "message", "senderId": "...", "channelId": "...", "messageType": 1, "ciphertext": "base64", "id": 1001 }

// OPK count low — device should replenish
{ "type": "keysNeeded", "currentCount": 3 }
```

**Client → Server (ACK only):**
```json
{ "type": "ack", "messageIds": [1001] }
```

The WebSocket carries no message content at the server level — the `ciphertext` field is an opaque byte array the server forwards without inspection.

## Self-hosting deployment

Minimum viable deployment for a tactical environment:

```
┌───────────────────────────────────────────┐
│  Single server (bare metal or VM)         │
│                                           │
│  PostgreSQL 16 (local socket, no TLS)     │
│  Signal relay server (port 8443, TLS)     │
│                                           │
│  TLS cert: self-signed or internal CA     │
│  Firewall: allow inbound 8443 only        │
│  Backup: pg_dump cron, encrypted at rest  │
└───────────────────────────────────────────┘
```

The Android app must trust the server's CA certificate. For a tactical deployment with a private CA, pin the CA cert in the Android Network Security Config (`network_security_config.xml`).

No cloud services. No external DNS required. Devices connect by IP address or internal hostname.
