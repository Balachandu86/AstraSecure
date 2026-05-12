# AstraSecure — Threat Model

This document is a STRIDE-based threat model for the AstraSecure system. It
covers the **target architecture** (Android client + Signal relay + REST API
+ Postgres) — gaps in the current implementation are noted where relevant.

The audience is the capstone team and any reviewer auditing the design.
This is not a penetration-test report; it is a design-level enumeration of
threats and the controls that mitigate them.

---

## 1. Scope and assumptions

### In scope
- The Android client app
- The Signal relay (existing, third-party-derived)
- The REST API (to be built — Phases 1–6)
- PostgreSQL data at rest and in transit
- The operator's identity material (Keystore-backed)
- The encrypted document vault

### Out of scope
- Physical compromise of an unlocked, unlocked-and-rooted device
- Compromise of Google Play Services / Android OS itself
- Compromise of the device manufacturer's secure element vendor chain
- Side-channel attacks on the Keystore (e.g. cache timing on TEE)
- Network eavesdropping at the operator's ISP (assumed adversarial — TLS handles it)

### Trust assumptions
- The operator's device is in their physical possession most of the time.
- The Android Keystore (StrongBox where available, TEE otherwise) protects
  private keys against extraction even given root.
- TLS 1.3 between client and both servers is correctly configured (cert
  pinning is a Phase 1 hardening, see [backend_stack.md](backend_stack.md)).
- The Signal relay's signing key is stored in an HSM or equivalent in
  production deployment.
- The capstone team's source code repository is not adversarial.

---

## 2. Trust boundaries

```
┌────────────────────────────┐  TB1   ┌──────────────────────┐
│   Android Client process   │◀──────▶│   Signal relay       │
│  (operator's device)       │  TLS   │  (server)            │
│                            │        └──────────────────────┘
│  ┌────────────┐            │  TB2   ┌──────────────────────┐
│  │ Keystore   │            │◀──────▶│   REST API server    │
│  │ (HW-backed)│ TB3        │  TLS   │                      │
│  └────────────┘            │        │  ┌────────────────┐  │
│                            │        │  │  PostgreSQL    │  │
│  ┌────────────┐            │        │  │ TB4            │  │
│  │ EncSP /    │            │        │  └────────────────┘  │
│  │ Vault disk │            │        └──────────────────────┘
│  └────────────┘            │
└────────────────────────────┘
```

| Boundary | What crosses |
|---|---|
| **TB1** Client ↔ Signal relay | Identity public key, pre-keys, encrypted message envelopes, JWT, ack frames |
| **TB2** Client ↔ REST API | JWT, mission/channel/clearance/schema CRUD JSON, audit events |
| **TB3** App process ↔ Keystore | Wrapped key handles only — private bytes never traverse this boundary |
| **TB4** REST API ↔ Postgres | Internal-network only; encrypted channel via TLS; password auth |

---

## 3. STRIDE analysis

For each STRIDE category, threats are tagged with severity:
- **CRITICAL** — directly enables compromise of operator-to-operator confidentiality
- **HIGH** — operational integrity or availability impact at the system level
- **MEDIUM** — degrades single-operator experience or leaks limited metadata
- **LOW** — already well-mitigated, listed for completeness

### S — Spoofing identity

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| S1 | Attacker forges a JWT to impersonate operator | CRITICAL | RS256 signature; signing key only on Signal relay; tokens validated against published JWKS (see [auth.md §5](auth.md)) |
| S2 | Attacker steals a JWT from device disk and uses it from another device | HIGH | EncSP encryption; 24h TTL; `device_id` claim flags mismatch as ALERT (server-side log only — does not block, since legitimate device_id rotation can occur, but flagged for human review) |
| S3 | Attacker registers a new device claiming an existing operator's `userId` | CRITICAL | Signal relay rejects duplicate `POST /v1/users` for an existing `userId`. The only path for "new device, existing user" is panic-wipe + re-provision, which generates a new `userId` |
| S4 | Attacker spoofs identity-key fingerprint in a peer's pre-key bundle | HIGH | Every `PreKeyBundleResponse` is fetched fresh from the relay over TLS; identity key is included; client computes safety number and surfaces it for out-of-band verification (Phase 2 UX) |
| S5 | Attacker spoofs the Signal relay or REST API hostname | HIGH | TLS cert validation; cert pinning planned (Phase 1 hardening); HSTS on server |
| S6 | Internal attacker on the operator's network MITMs TLS | LOW | Cert pinning + system trust store. A device with installed corporate root certs is out of scope per §1 |

### T — Tampering with data

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| T1 | Attacker modifies an encrypted message envelope in transit | LOW | libsignal authenticated encryption (Sender Key for groups, Double Ratchet for 1:1); tampering produces decryption failure, not silent acceptance |
| T2 | Attacker modifies REST API request body in transit | LOW | TLS 1.3 with AEAD ciphers |
| T3 | Compromised relay reorders or replays envelopes | MEDIUM | libsignal sequence numbers; replays detected by recipient; reordering surfaced as gaps in the audit trail |
| T4 | Compromised relay drops envelopes (denial via tampering) | MEDIUM | Acknowledgement model: sender knows server received but not whether recipient received until ACK; UI shows "delivered" only on recipient ACK |
| T5 | Attacker modifies the local `astra_store.json` snapshot to elevate rank | LOW | Client-side clearance check is a UI hint only — server re-validates on every mutating call (see [auth.md §6](auth.md)); the manipulated snapshot grants local UI affordances but no server-honored privilege |
| T6 | Attacker modifies SQL data via the REST API by exploiting parameter injection | HIGH | Parameterised queries via Exposed ORM; no string-concatenated SQL; input validation per endpoint; audit log on every mutation |
| T7 | Attacker tampers with an encrypted vault file on disk | LOW | AES-256-GCM provides integrity; tampering causes decrypt failure on retrieval |
| T8 | Attacker plants a malicious schema record (e.g. rank with level 99999) | MEDIUM | Server-side validation: rank level ∈ `[1, 99]`; `is_admin` re-checked on schema mutations from DB (not JWT claim) |

### R — Repudiation

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| R1 | Operator denies sending a message they sent | MEDIUM | Server-side `audit_events` records `message_sent` events with sender's `userId`, channel ID, and ciphertext digest; 30-day retention |
| R2 | Admin denies making a clearance change | MEDIUM | Every clearance/schema mutation writes an audit row with the actor's `userId` (from JWT) and `previousValue`; immutable rows (no UPDATE allowed) |
| R3 | Operator denies that their device was wiped | LOW | Tombstone is a permanent state in `users.tombstoned`; the audit row recording the tombstone is never deleted |
| R4 | Server-side audit log is itself tampered with | MEDIUM | Phase 7+ hardening: append-only log shipped to a separate write-once store (e.g. cloud bucket with object-lock). Phase 0–6 ships with Postgres-only audit, accepted risk |

### I — Information disclosure

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| I1 | Plaintext message content stored on the relay | CRITICAL | The relay holds only opaque ciphertext envelopes; deletes them after delivery ACK. No plaintext path through the relay (see [system_overview.md §Backend Server](system_overview.md)) |
| I2 | Plaintext message content stored on the REST API or Postgres | CRITICAL | The REST API has **no message endpoints**. By design, plaintext messages never traverse TB2 |
| I3 | Plaintext message content persisted on disk (decrypted side) | HIGH | Decrypted messages live in client RAM only; never written to `InMemoryStore` snapshot or disk. Repository discards on app backgrounding (Phase 2 hardening: explicit clear) |
| I4 | Identity private key extracted from device | CRITICAL | Keystore non-exportable; StrongBox where available. Out-of-scope threats listed in §1 |
| I5 | Mission membership / clearance leaks via API enumeration | MEDIUM | 404 returned for both "not found" and "not authorized to view" (see [api_contract.md](api_contract.md)); rate limits prevent timing-based enumeration |
| I6 | Other operators' callsigns visible to non-mission-mates | LOW | `GET /api/users/{id}` returns 404 unless caller shares a mission with `id` |
| I7 | Operator's missions list leaks via JWT claims | LOW | JWT contains only `sub`, `is_admin`, `device_id` — no mission membership |
| I8 | Document vault content leaks via OS-level backup | MEDIUM | Vault files are AES-256-GCM encrypted with Keystore-resident keys. AndroidManifest sets `android:allowBackup="false"` (verify in Phase 1) |
| I9 | Notification content leaks plaintext on lock screen | MEDIUM | All notifications use generic content ("New encrypted message"); plaintext is fetched only after the user opens the app |
| I10 | Decrypted document leaks via clipboard / screenshots | LOW | `FLAG_SECURE` set on all activities (Phase 2 hardening); SAF-based export is the only legitimate egress path |
| I11 | OPK exhaustion DoS leaks "this user is offline" metadata | LOW | Server uploads new OPKs proactively when count drops below 10 (see `keysNeeded` push in [SignalServerClient.kt:91](app/src/main/java/com/explo/capstone/transport/SignalServerClient.kt#L91)); offline users still have ≥ 10 cached |
| I12 | Logs / crash reports leak JWT, plaintext, or PII | MEDIUM | OkHttp logging at `Level.BASIC` (no headers/bodies); Phase 2 ProGuard rules redact known sensitive fields; no third-party crash reporter configured |
| I13 | Server admin reads Postgres directly to exfiltrate user metadata | HIGH | Accepted risk: a malicious server operator can see mission/channel structure (this is by design — the relay sees nothing). Mitigated organisationally: server is operated by the team; production deployments would use database-level audit logging and least-privilege DB roles |

### D — Denial of service

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| D1 | Attacker floods `POST /v1/messages` to exhaust relay storage | HIGH | Server queues per-recipient with bounded length; ACK-then-delete keeps storage bounded. Per-JWT rate limits (see [api_contract.md §9](api_contract.md)) |
| D2 | Attacker floods `GET /api/sync` | MEDIUM | 60/min per JWT; 304 returns are cheap |
| D3 | Attacker floods `POST /api/users` to exhaust JTI rows | LOW | Per-IP rate limit at the WAF / reverse proxy layer (Phase 1) |
| D4 | Compromised relay drops all envelopes for a target operator | MEDIUM | No mitigation in scope — DoS by an authoritative server is accepted risk. Operator notices via UI cues (no incoming messages) |
| D5 | Schema invalidation storm — admin makes 1000 schema edits, every client re-syncs 1000 times | MEDIUM | Server-side coalescing of `sync_invalidated` pushes (250 ms debounce per user; see [sync_strategy.md §13](sync_strategy.md)) |
| D6 | Battery drain on client via constant WS reconnect | LOW | Existing OkHttp WS auto-reconnect with exponential backoff; foreground-only socket lifetime |

### E — Elevation of privilege

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| E1 | Operator manipulates client to bypass `min_clearance_post` check | LOW | Server re-validates on every send: relay's `POST /v1/messages` re-checks the sender's clearance against the channel's `min_clearance_post` (Phase 8 hardening — currently the relay does not know about clearances). **Phase 0–7 accepted gap:** the relay is clearance-blind, so a tampered client can send into a channel they should not be able to post in. The audit event still records it as the sender's action. Phase 8 closes this. |
| E2 | Operator forges `is_admin` claim in JWT | CRITICAL | RS256 signature; client cannot mint a token. Server re-reads `users.is_admin` from DB on schema endpoints (see [auth.md §6](auth.md)) |
| E3 | Operator exploits race in `POST /api/missions` to skip auto-CHIEF assignment | HIGH | Single SQL transaction in the create handler atomically inserts mission + participant + clearance (see [api_contract.md §3](api_contract.md)). Failure to commit rolls all three back |
| E4 | Demoted CHIEF retains visibility via stale JWT | LOW | Server re-validates clearance on every mutation; reads (`/sync`) reflect demotion within one push round (≤ a few seconds) |
| E5 | Removed mission participant continues to receive new messages via stale sender keys | MEDIUM | Sender key rotation on participant change (see [sync_strategy.md §10](sync_strategy.md)). The removed user can decrypt messages sent before rotation occurs (a window of exposure between removal and the next message) — accepted risk; documented limitation |
| E6 | Tombstoned user's still-valid JWT (within 24h TTL) is used to access `/api/sync` | LOW | Validation step 7 returns 410 Gone before the request handler runs (see [auth.md §5](auth.md)) |
| E7 | Operator uses panic-wipe to retroactively deny a message they sent | LOW | Audit events survive tombstone; `users.id` is preserved (just flagged tombstoned) so audit foreign keys remain valid |

---

## 4. Critical attack chains

A few high-value attack chains worth tracing end-to-end:

### Chain A: Stolen unlocked device
1. Attacker has physical access to an unlocked device within the JWT TTL window.
2. Attacker can read EncSP-stored JWT (KeyguardManager check is the only gate).
3. Attacker can send messages as the operator and receive new ones until JWT expires (≤ 24h) or a remote tombstone is issued.
4. Attacker **cannot** extract the identity private key (Keystore non-exportable).
5. Attacker **cannot** persist access beyond 24h without being able to re-attest, which requires the Keystore key — they would need to keep the device in possession for that long.

**Mitigation path:** legitimate operator notices loss → uses **another** AstraSecure device (if any) or contacts an admin out-of-band → admin tombstones the user → all tokens for that user fail validation within seconds.

**Residual risk:** up-to-24h read/write window, mitigated by short TTL.

### Chain B: Compromised Signal relay
1. Attacker compromises the relay process.
2. Attacker can: (a) drop envelopes, (b) reorder envelopes, (c) inject envelopes from valid senders' identity keys (no — they don't have private keys), (d) forge JWTs (yes — signing key is on the relay).
3. Forged JWTs would let the attacker authenticate to the **REST API** as any user.
4. This grants the attacker control of mission/channel/clearance data — but **not** plaintext message content (which lives in client RAM only).
5. The attacker can also enumerate user metadata by querying `/api/users` for arbitrary IDs.

**Mitigation:** signing key in HSM; key rotation on incident; deployment-time
separation of relay process from JWT signing service (Phase 8 hardening:
move signing into a separate microservice with its own attestation).

**Residual risk:** structural data and metadata are exposed under relay
compromise. Plaintext content is **not**.

### Chain C: Compromised REST API + Postgres
1. Attacker reads the entire `users`, `missions`, `channels`, `clearances`,
   `audit_events` tables.
2. Attacker learns: operator callsigns, mission names, organisational structure,
   who has what clearance, who messaged when (via audit timestamps).
3. Attacker **cannot** read message content or identity private keys.
4. Attacker **could** issue arbitrary writes if they also control the JWT
   signing path (i.e. own the relay too — but separation of concerns means
   that's a second compromise).

**Mitigation:** least-privilege DB role; encryption at rest; audit log shipped
off-box (Phase 7); database-level audit (Phase 8). For the capstone build,
the team accepts that compromise of the REST API box reveals organisational
metadata — confidentiality of message content remains intact.

---

## 5. Mitigations cross-reference

| Mitigation | Documented in |
|---|---|
| End-to-end encryption (Signal protocol) | [system_overview.md](system_overview.md), [data_flows.md](data_flows.md) flow 5 |
| JWT validation rules | [auth.md §5](auth.md) |
| Tombstone revocation | [auth.md §8b](auth.md), [data_flows.md](data_flows.md) flow 9 |
| Server-side authorization re-checks | [auth.md §6](auth.md), [api_contract.md](api_contract.md) per-endpoint |
| Atomic mission + clearance creation | [api_contract.md §3](api_contract.md) |
| Sender key rotation on removal | [sync_strategy.md §10](sync_strategy.md) |
| Rate limits | [api_contract.md §9](api_contract.md) |
| Vault encryption | [data_flows.md](data_flows.md) flow 8 |
| 404-on-unauthorized | [api_contract.md §Conventions](api_contract.md) |

---

## 6. Phase-staged hardening backlog

Threats accepted-with-future-mitigation, mapped to plan phases:

| # | Threat | Closes in phase |
|---|---|---|
| E1 | Server-side clearance enforcement on send | Phase 8 |
| S5 | TLS cert pinning | Phase 1 backend bring-up |
| I8 | `android:allowBackup="false"` confirmation | Phase 1 |
| I10 | `FLAG_SECURE` on activities | Phase 2 |
| I12 | ProGuard redaction rules | Phase 2 |
| R4 | Off-box audit log shipping | Phase 7 |
| I13 | DB-level audit + least-privilege role | Phase 8 |

---

## 7. Open questions

- **Safety number verification UI** — current spec mentions surfacing safety
  numbers (S4) but no flow exists. Decide whether this lands in Phase 2 or
  is deferred. Recommendation: Phase 2 if MVP requires it for a demo
  scenario; otherwise defer.
- **Replay protection for tombstone** — is `POST /api/users/me/tombstone`
  idempotent? Yes — second call returns 200 with the same `tombstonedAt`.
  Document in [api_contract.md §2](api_contract.md) (currently implied,
  could be explicit).
- **Penetration testing scope** — capstone team should specify whether a
  third-party pentest is part of the deliverable. If so, this document is
  the input.
