# Signal Protocol Implementation — Overview

This folder is the source of truth for adding real end-to-end encrypted, forward-secret, two-device messaging to AstraSecure. The existing app encrypts messages correctly on a single device but has no transport and no key exchange. This plan adds both.

## What changes and what stays

| Component | Status after Signal integration |
|---|---|
| `CryptoEngine.encryptMessage` / `decryptMessage` | **Replaced** — delegates to `SessionCipher` (Signal) or `GroupCipher` (channels) |
| `CryptoEngine.encryptWithPassword` / `decryptWithPassword` | **Unchanged** — Tools screen, no Signal involvement |
| `CryptoEngine.generateMissionKey` / `invalidateAllKeys` | **Unchanged** — still used for documents and panic wipe |
| `IdentityManager.provisionIdentity` | **Extended** — also registers Signal identity on server |
| `IdentityManager.wipeAll` | **Extended** — also wipes SignalProtocolStore |
| `MessageRepository.send` / `receive` | **Rewired** — now calls `SignalCryptoEngine` instead of AES-GCM path |
| `MetadataProcessor` | **Unchanged** — padding/jitter/batching apply on top of Signal ciphertext |
| `InMemoryStore` + entity repos | **Unchanged** — local state management untouched |
| Panic wipe flow | **Extended** — Phase 5 wipe adds Signal session + pre-key destruction |
| All UI / ViewModel layers | **Unchanged** — they talk to `MessageRepository`, not to crypto directly |

## Database decision — PostgreSQL over MongoDB

**Decision: PostgreSQL.**

The pre-key server has two operations where correctness is non-negotiable:

1. **One-time pre-key consumption** — a one-time pre-key (OPK) must be handed to exactly one recipient and then deleted. This requires an atomic `SELECT ... RETURNING` + delete in a single transaction. PostgreSQL handles this natively with `DELETE ... RETURNING`. MongoDB requires multi-document transactions which are available but far more complex to reason about correctly.

2. **Message queue ordering** — messages must be delivered in send order. PostgreSQL's `BIGSERIAL` primary key + WAL guarantees strict append order. MongoDB's `_id` ObjectID ordering is unreliable under high concurrency without explicit timestamps.

**Open-source licensing:** PostgreSQL is released under the PostgreSQL License (BSD-like). It is unambiguously open source. MongoDB switched to SSPL in 2018, which restricts commercial and some government deployments. For a military operator that needs to self-host without legal ambiguity, PostgreSQL is the correct choice.

**Self-hosting:** PostgreSQL is available as a standalone binary, a Docker image (`postgres:16`), and is included in every major Linux distribution's package manager. No licensing, no cloud dependency, no call-home.

## What the server does and does not do

**Does:**
- Store user registration records and public identity keys
- Serve pre-key bundles on demand (identity key + signed pre-key + one-time pre-key)
- Accept uploaded messages and queue them for offline recipients
- Deliver queued messages to connected clients over WebSocket

**Does NOT:**
- Hold any private keys
- Decrypt any messages (server only ever sees Signal ciphertext)
- Know message contents, sender, recipient beyond routing IDs
- Store messages after confirmed delivery (queue is ephemeral)
- Authenticate message categories, clearance levels, or mission membership — that logic stays on device

## Doc map

Read in order:

1. [01_SERVER_ARCHITECTURE.md](01_SERVER_ARCHITECTURE.md) — PostgreSQL schema, REST API contracts, WebSocket protocol
2. [02_SIGNAL_CRYPTO.md](02_SIGNAL_CRYPTO.md) — X3DH key agreement, Double Ratchet, Sender Keys for channels, libsignal-android API
3. [03_ANDROID_CHANGES.md](03_ANDROID_CHANGES.md) — Exact file-by-file changes in the Android app
4. [04_END_TO_END_FLOW.md](04_END_TO_END_FLOW.md) — Complete walkthrough of a message from Alice to Bob
5. [05_IMPLEMENTATION_PHASES.md](05_IMPLEMENTATION_PHASES.md) — Ordered build plan with done-when criteria
