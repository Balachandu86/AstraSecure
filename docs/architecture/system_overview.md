# AstraSecure — System Overview

## Document map

| Document | Scope |
|---|---|
| [system_overview.md](system_overview.md) | This file — components and responsibilities |
| [data_model.md](data_model.md) | Tables, ownership, what lives where |
| [data_flows.md](data_flows.md) | Step-by-step sequences for each user action |
| [api_contract.md](api_contract.md) | REST endpoint specification |
| [auth.md](auth.md) | JWT lifecycle, validation, revocation |
| [sync_strategy.md](sync_strategy.md) | `/sync` semantics, push invalidation, retries, offline |
| [threat_model.md](threat_model.md) | STRIDE-based threat enumeration |
| [backend_stack.md](backend_stack.md) | Server tech choices, deployment, configuration |

---

## What the app is

AstraSecure is a multi-operator secure messaging platform. Operators are provisioned with hardware-bound identities, assigned to missions, and communicate over end-to-end encrypted channels using the Signal Protocol. An admin layer controls who can see and post in which channel via a rank/clearance system.

---

## High-level component map

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Client                           │
│                                                                 │
│  ┌──────────────┐   ┌──────────────┐   ┌─────────────────────┐ │
│  │   Identity   │   │    Signal    │   │     Local Cache     │ │
│  │   Layer      │   │   Protocol   │   │   (InMemoryStore)   │ │
│  │  (Keystore + │   │   Layer      │   │                     │ │
│  │   EncSP)     │   │  (libsignal) │   │  missions           │ │
│  └──────┬───────┘   └──────┬───────┘   │  channels           │ │
│         │                  │           │  ranks / schema     │ │
│         │           ┌──────┴───────┐   │  clearances         │ │
│         │           │  Encrypted   │   └─────────┬───────────┘ │
│         │           │  Message     │             │             │
│         │           │  Transport   │             │             │
│         │           └──────┬───────┘             │             │
└─────────┼──────────────────┼─────────────────────┼─────────────┘
          │ (HTTPS/JWT)       │ (WSS + HTTPS)        │ (REST API)
          │                  │                      │
┌─────────┼──────────────────┼──────────────────────┼─────────────┐
│         ▼                  ▼                      ▼             │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    Backend Server                       │    │
│  │                                                         │    │
│  │  ┌───────────────────┐    ┌──────────────────────────┐  │    │
│  │  │  Signal Relay     │    │      REST API            │  │    │
│  │  │  (WS + HTTP)      │    │   (missions, channels,   │  │    │
│  │  │                   │    │    schema, clearances,   │  │    │
│  │  │  • Key exchange   │    │    users, audit log)     │  │    │
│  │  │  • Message relay  │    └──────────────┬───────────┘  │    │
│  │  │  • OPK store      │                   │              │    │
│  │  └───────────────────┘                   ▼              │    │
│  │                                ┌──────────────────┐     │    │
│  │                                │    PostgreSQL     │     │    │
│  │                                │                  │     │    │
│  │                                │  users           │     │    │
│  │                                │  missions        │     │    │
│  │                                │  channels        │     │    │
│  │                                │  schema tables   │     │    │
│  │                                │  clearances      │     │    │
│  │                                │  audit_events    │     │    │
│  │                                └──────────────────┘     │    │
│  └─────────────────────────────────────────────────────────┘    │
│                         Backend                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component responsibilities

### Android Client

#### Identity Layer
- Generates and stores the user's EC keypair in the **Android Keystore** (hardware-backed where available)
- Persists `UserIdentity` (id, callsign, key alias) in `EncryptedSharedPreferences`
- Issues per-document AES-256-GCM keys for the vault
- Device attestation (StrongBox vs TEE detection)
- **Never sends private keys off-device**

#### Signal Protocol Layer
- Runs libsignal for all cryptographic operations
- X3DH key agreement + Double Ratchet for 1:1 sessions
- Sender Key protocol for group channels (one encryption per message regardless of group size)
- Manages OPKs, SPKs, and sender key distribution messages (SKDMs)
- Sessions stored in `EncryptedSharedPreferences` — never in Postgres

#### Local Cache (`InMemoryStore`)
- In-memory mirror of server-owned data: missions, channels, ranks, categories, clearances
- Hydrated from the REST API on login and kept in sync on mutations
- Persisted locally as a JSON snapshot for offline resilience (read-only when offline)
- Decrypted message content lives here ephemerally — never persisted to disk

#### UI / UX Layer
- Composable screens driven by ViewModels
- ViewModels read from the local cache and write through to the REST API
- Clearance gating enforced client-side from cached clearance data (server enforces authoritatively)

---

### Backend Server

#### Signal Relay (existing)
Handles everything Signal-protocol-specific:
- `POST /v1/accounts` — register a new user and their identity key
- `PUT /v1/keys` — upload OPKs and SPKs
- `GET /v1/keys/{userId}` — fetch a pre-key bundle to initiate a session
- `POST /v1/messages` — relay an encrypted envelope to a recipient
- WebSocket — real-time push of incoming envelopes
- Ephemeral: messages are deleted from the relay after delivery acknowledgement

The relay has **no knowledge of mission structure, clearances, or plaintext content**.

#### REST API (target state — to be built or extended)
Owns all structural data. Protected by the same JWT issued at Signal registration.

| Endpoint group | Operations |
|---|---|
| `GET /sync` | Single bootstrap call — returns all missions, channels, schema, clearances for the authed user |
| `/missions` | CRUD + add/remove participants |
| `/channels` | CRUD per mission |
| `/clearances` | Assign / unassign rank per user per mission |
| `/schema/ranks` | CRUD for rank definitions |
| `/schema/channel-categories` | CRUD |
| `/schema/message-categories` | CRUD |
| `/schema/mission-types` | CRUD |
| `/audit` | Append security event, paginated read |

#### PostgreSQL
Authoritative source of truth for all non-cryptographic data. See `data_model.md` for schema.

---

## Clear division of concerns

| Concern | Owner | Rationale |
|---|---|---|
| Private keys | Device Keystore only | Never leaves hardware |
| Signal sessions | EncryptedSharedPreferences | Cryptographic state, device-local |
| Encrypted message envelopes | Signal relay (ephemeral) | Deleted after delivery |
| Decrypted message content | Client RAM only | Never persisted anywhere |
| Mission / channel structure | PostgreSQL | Shared state across operators |
| Rank & schema definitions | PostgreSQL | Admin-managed, consistent across devices |
| Clearance assignments | PostgreSQL | Authoritative access control |
| Document vault keys | Device Keystore | Per-document, hardware-bound |
| Document ciphertext | Local disk | Encrypted, device-only |
| Audit log | PostgreSQL + local ring buffer | Server is authoritative; local is UI convenience |

---

## Authentication flow (summary)

1. Device provisions identity → Signal server issues JWT
2. JWT attached to every REST API call and WebSocket upgrade
3. Server validates JWT, extracts `userId`, enforces clearance on sensitive endpoints
4. No separate login screen — provisioning IS authentication

---

## What does NOT belong on the client

- Source-of-truth schema definitions (ranks, categories, types) — these must come from the server
- Authoritative clearance decisions — server must re-validate on message send / channel join
- Other users' identity keys in cleartext — fetched transiently via Signal pre-key API only
