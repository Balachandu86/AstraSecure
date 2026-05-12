# AstraSecure — Data Model & Ownership

## Principle

Every piece of data has exactly one authoritative owner. The client may cache a copy, but the server wins on conflict. Cryptographic material is the exception — it never leaves the device.

---

## PostgreSQL Schema (server owns these)

### `users`
```
users
├── id              TEXT PK          -- matches Signal userId (UUID)
├── callsign        TEXT UNIQUE      -- operator display name
├── identity_key    BYTEA            -- public EC key (mirrors Signal registration)
├── registration_id INT              -- Signal registration ID
├── created_at      TIMESTAMPTZ
└── tombstoned      BOOLEAN DEFAULT FALSE
```
*Tombstoned users cannot receive new sessions. Their history is wiped client-side.*

---

### `schema_ranks`
```
schema_ranks
├── id          TEXT PK              -- e.g. "rank_chief"
├── name        TEXT                 -- e.g. "CHIEF"
├── level       INT                  -- 1–99, used for clearance gating
├── color       TEXT                 -- ColorToken enum value
└── is_system   BOOLEAN              -- system ranks cannot be deleted
```

---

### `schema_channel_categories`
```
schema_channel_categories
├── id                      TEXT PK
├── name                    TEXT
├── accent                  TEXT     -- ColorToken
├── default_min_view        INT      -- default clearance level to view channels in this category
├── default_min_post        INT      -- default clearance level to post
└── is_system               BOOLEAN
```

---

### `schema_message_categories`
```
schema_message_categories
├── id                  TEXT PK
├── name                TEXT
├── accent              TEXT
├── min_clearance_send  INT
└── is_system           BOOLEAN
```

---

### `schema_mission_types`
```
schema_mission_types
├── id          TEXT PK
├── name        TEXT
├── accent      TEXT
├── description TEXT
└── is_system   BOOLEAN
```

---

### `missions`
```
missions
├── id              TEXT PK          -- e.g. "M-A3F9B1C2"
├── name            TEXT
├── type_id         TEXT FK → schema_mission_types.id
├── status          TEXT             -- ACTIVE | SUSPENDED | COMPLETED
├── phase           TEXT NULL
├── mission_key_alias TEXT           -- Keystore alias for mission-level encryption
├── created_by      TEXT FK → users.id
└── created_at      TIMESTAMPTZ
```

---

### `mission_participants`
```
mission_participants
├── mission_id  TEXT FK → missions.id
├── user_id     TEXT FK → users.id
└── PRIMARY KEY (mission_id, user_id)
```

---

### `channels`
```
channels
├── id                  TEXT PK
├── mission_id          TEXT FK → missions.id
├── name                TEXT
├── category_id         TEXT FK → schema_channel_categories.id
├── min_clearance_view  INT
├── min_clearance_post  INT
└── created_at          TIMESTAMPTZ
```

---

### `clearance_assignments`
```
clearance_assignments
├── user_id     TEXT FK → users.id
├── mission_id  TEXT FK → missions.id
├── rank_id     TEXT FK → schema_ranks.id
└── PRIMARY KEY (user_id, mission_id)    -- one rank per user per mission
```

---

### `audit_events`
```
audit_events
├── id          BIGSERIAL PK
├── user_id     TEXT FK → users.id
├── severity    TEXT                 -- INFO | WARN | ALERT | CRITICAL
├── source      TEXT                 -- e.g. "Admin", "Crypto", "Missions"
├── text        TEXT
└── occurred_at TIMESTAMPTZ
```

---

## Signal Relay Storage (ephemeral, relay owns these)

| Data | Lifetime | Notes |
|---|---|---|
| Identity key (public) | Until account deletion | Also mirrored in `users.identity_key` |
| Signed pre-key (SPK) | Until next rotation (~weekly) | Rotated by client, relay stores current only |
| One-time pre-keys (OPKs) | Consumed on session init | Relay deletes after fetch |
| Message envelopes | Until delivery + ACK | Never stored durably |
| Sender key distributions | Until delivered | Part of message envelope |

---

## Device-only Storage (never transmitted)

| Data | Storage | Notes |
|---|---|---|
| EC private key | Android Keystore (hardware) | Non-exportable |
| Signal identity private key | Android Keystore | Non-exportable |
| Signal sessions | EncryptedSharedPreferences | Double Ratchet state |
| OPK private keys | EncryptedSharedPreferences | Consumed on use |
| SPK private key | EncryptedSharedPreferences | Rotated weekly |
| Sender key state | EncryptedSharedPreferences | Per channel |
| Document vault keys | Android Keystore (per-document) | AES-256-GCM |
| Document ciphertext | Internal app storage (disk) | Encrypted at rest |
| Server JWT | EncryptedSharedPreferences | For API auth |

---

## Client Cache (derived from server, local copy)

The `InMemoryStore` holds a working copy of server data. It is:
- Hydrated from `GET /sync` on login
- Updated optimistically on user actions (write → server → confirm)
- Persisted to `astra_store.json` for offline read access
- Never the source of truth — server wins on conflict

| Cached entity | Source | Local persistence |
|---|---|---|
| missions | PostgreSQL | astra_store.json |
| channels | PostgreSQL | astra_store.json |
| schema_ranks | PostgreSQL | astra_store.json |
| schema_channel_categories | PostgreSQL | astra_store.json |
| schema_message_categories | PostgreSQL | astra_store.json |
| schema_mission_types | PostgreSQL | astra_store.json |
| clearance_assignments | PostgreSQL | astra_store.json |
| messages | Signal relay (decrypted) | **Never persisted** |
| documents (decrypted) | Device disk (encrypted) | **RAM only after decryption** |

---

## Why schema (ranks, categories, types) belongs on the server

Currently `SeedData.kt` hardcodes these and seeds them locally. This is wrong because:

1. **Consistency** — if an admin changes a rank name or adds a category, all clients must see it immediately. Local seed means each device has its own version.
2. **Admin control** — the Admin Console only mutates the local store today. Changes made on one device never reach other operators.
3. **Single source of truth** — with local seeding, a wipe-and-restore gives you the hardcoded defaults, not whatever the admin had configured.

The correct model: seed data only runs on the **server** at first boot. Clients fetch it via `/sync`. `SeedData.kt` on the client is removed entirely.
