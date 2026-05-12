# AstraSecure — Finalization Audit 1
**Date:** 2026-05-05  
**Author:** Claude Code (audit pass)  
**Branch:** master  
**Purpose:** Identify what remains between current codebase state and a complete, honest capstone deliverable — covering pending features, broken flows, and missing failure fallbacks.

---

## 1. Goal of the Application

AstraSecure is a **hardware-backed secure tactical messaging app** for Android, built as a BSc Cybersecurity capstone at Lovely Professional University (Session 2025-2026). The five core guarantees are:

| Guarantee | Mechanism |
|-----------|-----------|
| Hardware-backed identity | EC P-256 via Android Keystore (StrongBox → TEE fallback) |
| Confidentiality | AES-256-GCM per-mission encryption with unique IVs |
| Traffic-analysis resistance | 256-byte message padding + 200–2000 ms random delay |
| Access control | Bell-LaPadula clearance levels enforced at the repository layer |
| Emergency destruction | Panic wipe: key invalidation + data clear + tombstone flag |

The architecture is deliberately layered (UI → ViewModel → Repository → Module → Platform), uses manual DI via `AppContainer`, Jetpack Compose Navigation, and reactive state via `StateFlow`. A Node.js Signal Protocol relay server (`server/index.js`) exists but is **optional** — the app runs in local-only mode when the server is unreachable.

---

## 2. Intended End State

Based on the design doc (`docs/AstraSecure_Project_Report.md`) and the UI/gap audits, a fully complete deliverable satisfies **all ten objectives** from Chapter 4:

1. Hardware-backed provisioning
2. AES-256-GCM message encryption with unique IVs
3. Runtime-editable clearance-based access control
4. Metadata normalisation (padding + timing)
5. Encrypted document vault with per-document key segregation
6. Append-only security event audit log
7. Emergency panic wipe
8. Admin console schema customisation
9. Reactive state via StateFlow
10. Functional validation of all the above

---

## 3. What Is Complete

The following are fully implemented and consistent with the design spec:

- **Provisioning flow** — 5-state machine (Probing → CallsignEntry → Review → Provisioning → Success/Failed), hardware Keystore key generation, Signal key bundle creation and server registration
- **AES-256-GCM encryption** — `CryptoEngine`: per-mission Keystore aliases, `[12B IV | ciphertext | 16B tag]` format, unique IV per message
- **Metadata normalisation** — `MetadataProcessor`: pad to next 256-byte boundary, strip on receipt, random 200–2000 ms suspend delay
- **Clearance-based access** — ranks/clearances enforced at repository layer (not UI-only), runtime-editable via Admin Console
- **Panic wipe** — full sequence: Keystore alias deletion, EncryptedSharedPreferences wipe, tombstone write, navigation to wiped state
- **Admin Console** — runtime CRUD on ranks, categories, mission types; schema seeded on fresh install via `SeedData.seedSchema()`
- **Security event log** — in-memory ring buffer (100 events), all lifecycle events logged (provisioning, rotation, decryption failure, wipe)
- **Signal Protocol crypto layer** — `SignalCryptoEngine`: X3DH session setup, Double Ratchet (1:1), Sender Key distribution (group channels)
- **Signal Protocol store** — `AstraSignalProtocolStore`: all five sub-stores backed by `EncryptedSharedPreferences`
- **Signal key lifecycle** — OPK generation (100 initial), SPK rotation, server registration, JWT persistence
- **Transport layer** — `SignalServerClient`: REST + WebSocket client, JWT auth, graceful offline fallback
- **Reactive data layer** — `InMemoryStore` (9 entity StateFlows) + atomic JSON persistence (`astra_store.json`)
- **All 8 UI screens** — fully composed, wired to ViewModels, degraded-mode indicators active
- **App shell & navigation** — NavHost, bottom tabs, launch routing based on provisioning state and tombstone
- **Previous gap fixes (confirmed in code)** — GAP-01, GAP-02, GAP-03, GAP-20; UI-01 through UI-20 all resolved per UI_audit.md

---

## 4. What Is Still Pending

### 4.1 Feature Gaps

#### GAP-08 — Document Vault Has No Disk Persistence *(P1)*
**Location:** `IdentityManager.kt` — `docVault: Map<String, ByteArray>` is a plain in-memory field.  
**Impact:** Any file added to the vault is permanently lost on process death or rotation. The design spec explicitly promises "encrypted document vault with per-document AES-256 keys." The feature as described cannot be validated against the spec.  
**What is needed:**
- A manifest stored in `EncryptedSharedPreferences` (document ID → encrypted file path)
- Each document written to the app's files directory under a Keystore-derived per-document AES-256-GCM key
- On load, manifest read; on wipe, all manifest entries deleted from disk

#### GAP-09 — `revokeRemoteTokens()` Is a No-Op *(P1)*
**Location:** `IdentityManager.kt` — method body is empty or returns immediately.  
**Impact:** Panic wipe does not invalidate the server-side JWT. A captured token remains valid until natural expiry. The wipe sequence appears complete but leaves a live session credential on the server.  
**What is needed:** A `DELETE /v1/users/:userId` call (already implemented server-side) must be called from `revokeRemoteTokens()` before or concurrently with local key destruction.

#### GAP-10 — OPK Replenishment ID Computation Is Wrong *(P1)*
**Location:** `SignalKeyManager.kt` — `replenishPreKeys()` computes the starting OPK ID from the max SPK ID instead of the max OPK ID.  
**Impact:** When OPKs are replenished, new keys may receive IDs that collide with existing OPKs. The server would then have duplicate IDs, causing a pre-key bundle fetch to silently hand the wrong key to a sender, breaking X3DH session setup silently.  
**What is needed:** Query `signalStore.loadPreKeys()`, extract `max { it.id }`, use that +1 as the starting ID for the new batch.

#### GAP-12 — Old Signed Pre-Keys Never Cleaned Up *(P1)*
**Location:** `SignalKeyManager.kt` — `rotateSignedPreKey()` generates and uploads a new SPK but never schedules cleanup of expired ones.  
**Impact:** `EncryptedSharedPreferences` accumulates SPK entries on every weekly rotation. Over months, this inflates storage and leaks rotation frequency metadata locally.  
**What is needed:** After uploading the new SPK, enumerate `signalStore.loadSignedPreKeys()` and delete any whose ID is not the current active one AND whose timestamp is older than 48 hours (to allow in-flight messages to finish decrypting).

#### GAP-21 — Dead Code: `InMemoryMessageRepository` Still in Codebase *(P2)*
**Location:** `app/src/main/java/com/explo/capstone/` — the original AES-GCM message repository class exists alongside `SignalMessageRepository` but is never wired by `AppContainer`.  
**Impact:** No runtime impact. However, it confuses the architecture — two implementations of the same interface, one unused. If a future developer wires it accidentally, messages are encrypted with mission keys instead of Signal Protocol, silently breaking the ratchet.  
**What is needed:** Delete the class, or move it to a `test/` source set if it's needed as a test double.

#### GAP-16 — Release Build Not Minified *(P2)*
**Location:** `app/build.gradle.kts` — `isMinifyEnabled` is `false` for the release variant.  
**Impact:** The release APK contains all class names, field names, and string literals in plaintext. An attacker with APK access can read the full source structure, key alias names, and API routes without any reverse-engineering effort.  
**What is needed:** Enable `isMinifyEnabled = true` and `isShrinkResources = true` for `release`, add `proguard-rules.pro` keep rules for kotlinx.serialization models and libsignal.

#### GAP-17 — Release Server URL Is a Placeholder *(P2)*
**Location:** `app/src/main/res/values/` or `BuildConfig` — base URL contains `"your.server"` literal.  
**Impact:** A developer who builds a release APK without filling in the URL ships a broken app that attempts connections to a non-existent host. No compile-time or runtime assertion catches this.  
**What is needed:** Add a Gradle task that fails the release build if `BASE_URL` contains `"your.server"` or is empty.

### 4.2 Pending Multi-Device Validation

The Signal Protocol implementation (`SignalCryptoEngine`, `AstraSignalProtocolStore`, `SignalKeyManager`) is fully coded but has **never been exercised across two real devices**. This is within the capstone's stated scope boundary ("out of scope"), but the following specific sub-paths are untested:

- X3DH session initiation (Sender sends pre-key message; Receiver decrypts and establishes session)
- Sender Key Distribution Message delivery and processing for a multi-member channel
- OPK consumption from the server's atomic pop endpoint
- Message decryption after a ratchet step (recipient has replied at least once)

For the capstone demo, this means the "tactical messaging" demo is limited to a single device sending to itself. This is not a bug, but it should be clearly stated in the demo script and report conclusions.

---

## 5. Missing Failure Fallbacks

These are paths where the app either silently swallows an error, crashes, or produces misleading UI with no recovery option.

### 5.1 Server-Side Security Gaps (No Authorization Checks)

#### GAP-05 — Registration Endpoint Unauthenticated *(P1)*
**Location:** `server/index.js` — `POST /v1/users`  
**Failure:** Any caller who knows a target's `userId` can overwrite their identity key and pre-keys on the server. The next X3DH session initiated by a sender will encrypt to the attacker's key. There is no error or warning on the real device.  
**Fallback missing:** No signed proof-of-possession. The endpoint should require the body to be signed by the Keystore private key corresponding to the identity public key being registered.

#### GAP-06 — Message Delete Has No Ownership Check *(P1)*
**Location:** `server/index.js` — `DELETE /v1/messages`  
**Failure:** Any authenticated user can delete any other user's queued messages. The sender sees no error; the recipient simply never receives them.  
**Fallback missing:** SQL query must add `AND recipient_id = $authenticatedUserId`. Currently the query is `DELETE FROM message_queue WHERE id = ANY($1)` with no recipient filter.

#### GAP-13 — Sequential Message Queue IDs *(P1)*
**Location:** `server/index.js` — `message_queue` table uses `BIGSERIAL` primary key.  
**Failure:** IDs are enumerable. An attacker with a valid JWT can iterate over `[id-1, id-2, ...]` to delete or infer the existence of messages belonging to other users.  
**Fallback missing:** Replace `BIGSERIAL` with `gen_random_uuid()` as the primary key.

#### GAP-14 — No Rate Limiting on Any Server Endpoint *(P1)*
**Location:** `server/index.js` — no `express-rate-limit` or equivalent middleware.  
**Failure modes:**
- OPK exhaustion: attacker repeatedly fetches pre-key bundles, draining all OPKs; next legitimate sender gets no key bundle (HTTP 404 with no helpful message to the Android client)
- Registration spam: mass-register fake users to pollute the user table
- Queue flooding: send thousands of messages to a target before ACK
**Fallback missing:** `express-rate-limit` per-IP (registration: 10/hour) and per-JWT (message send: 60/minute, key fetch: 30/minute).

#### GAP-15 — No Signal Key Format Validation on Server *(P2)*
**Location:** `server/index.js` — `PUT /v1/keys` and `POST /v1/users` accept raw base64 blobs.  
**Failure:** Malformed or garbage keys are stored silently. A sender who fetches a corrupt pre-key bundle will fail X3DH locally with an `InvalidKeyException` that has no actionable error message.  
**Fallback missing:** Server should decode the base64 key fields and verify Curve25519 format (32-byte length) and SPK signature before accepting the upload.

### 5.2 Android App Failure Fallbacks

#### GAP-07 — JWT Sent in WebSocket URL Query Parameter *(P1)*
**Location:** `SignalServerClient.kt` — WebSocket connection URL: `wss://host/v1/websocket?token=$jwt`  
**Failure:** The JWT is logged by every proxy, load balancer, and server access log in plaintext. If TLS is terminated at a proxy, the token is visible in server logs in perpetuity.  
**Fallback missing:** Connect without the token in the URL, then send a first-frame authentication message `{"type":"auth","token":"$jwt"}`. Close the socket if the server does not ack within 5 seconds.

#### GAP-18 — No Certificate Pinning *(P1)*
**Location:** `SignalServerClient.kt` — OkHttp client has no `CertificatePinner`.  
**Failure:** If an attacker presents a CA-signed certificate for the server's domain (e.g., via a compromised CA or an intercepting enterprise proxy), TLS succeeds and the app connects to the attacker's server. Pre-key bundles fetched are attacker-controlled; all subsequent Signal sessions are encrypted to the attacker's keys. The user sees no warning.  
**Fallback missing:** Add `CertificatePinner` to the OkHttp builder with the server's leaf certificate SHA-256 pin. Build fails if pin is placeholder.

#### GAP-19 — WebSocket ACK Sent Before Decryption Completes *(P1)*
**Location:** `AppContainer.kt` — `startIncomingMessageStream()` calls `acknowledge(msg.id)` before the `signalCryptoEngine.decryptFromChannel()` coroutine returns.  
**Failure:** If decryption throws (corrupt ciphertext, missing session, unknown sender key), the message is ACKed and deleted from the server queue. The message is silently lost with no UI feedback and no retry path.  
**Fallback missing:** Move the `acknowledge()` call to after the decryption `try/catch`. On decryption failure, log a security event, show an in-chat system message ("1 message could not be decrypted"), and do **not** ACK (leave the message in the queue for manual retrieval or discard).

#### GAP-04 — No Persistent Retry for Failed Server Registration *(P1)*
**Location:** `AppContainer.kt` / `SignalKeyManager.kt` — `retryServerRegistration()` is called once at app startup if JWT is missing.  
**Failure:** If the network is unavailable at startup, the retry runs once, fails, and is never rescheduled. The user is provisioned locally but never registers with the server. All outbound messages fail silently with "transport unavailable."  
**Fallback missing:** Schedule a `WorkManager` periodic task (15-minute interval, network-required constraint) that calls `retryServerRegistration()` until JWT is present in the store. Cancel the task on first success.

#### Audit Log — Not Persisted Across Process Death *(P1)*
**Location:** `SecurityRoute.kt` / in-memory event ring buffer.  
**Failure:** The security event log (provisioning events, key rotations, decryption failures, panic wipe) is held only in memory. Any process kill (OOM, force-stop, reboot) permanently erases the log. Post-incident forensics are impossible.  
**Fallback missing:** Append each event as a newline-delimited JSON record to an encrypted file (`astra_audit.log`) stored in the app's files directory, encrypted under a dedicated Keystore alias. On load, read the file into the ring buffer. On wipe, delete the file and key.

#### Provisioning — No Rollback if Signal Key Upload Fails Mid-Way *(P1)*
**Location:** `SignalKeyManager.provision()` — generates identity key, registration ID, 100 OPKs, and SPK, then attempts server registration.  
**Failure:** If the server accepts the identity key registration (step 1) but then rejects the OPK upload (step 2) due to a network drop, the local state is partially provisioned: Keystore key exists, server has identity key but no OPKs. The next app launch sees "already provisioned" locally but the server has no usable key bundle. Callers get a 404 on pre-key bundle fetch.  
**Fallback missing:** Wrap the entire provision sequence in a transaction-like check: if any upload step fails, call `DELETE /v1/users/:userId` to roll back server state, and delete the local Keystore aliases so the user can re-provision cleanly.

#### Channel Sender Key — No Re-Distribution on Member Join *(P2)*
**Location:** `SignalCryptoEngine.kt` — `distributeChannelSenderKey()` is called at channel creation time.  
**Failure:** If a new member is added to a channel after the channel was created, they never receive the existing Sender Key. They can see new messages only after the sender's ratchet advances past their join point. In practice they see nothing, with no UI explanation.  
**Fallback missing:** When a member is added to a channel (in `ChannelRepository.addMember()`), call `distributeChannelSenderKey()` directed specifically at the new member's address.

#### Network — OPK Exhaustion Has No UI Warning *(P2)*
**Location:** `SignalServerClient.kt` + `SignalKeyManager.kt`  
**Failure:** `replenishPreKeys()` is triggered when the server OPK count drops below 10. If the replenishment call fails (network unavailable), the count continues to drop. Once OPKs reach 0, any new sender attempting X3DH gets a 404 from the server, their session initiation fails, and their message is dropped silently.  
**Fallback missing:** Surface an amber warning on the Security Dashboard when OPK count < 5 (server-reported), prompting the user to tap "Replenish Keys." Add a toast/snackbar on replenishment failure.

---

## 6. Server-Side Completeness

The server (`server/index.js`) is structurally complete but has no hardening:

| Item | Status |
|------|--------|
| Registration endpoint | Present, **unauthenticated** (GAP-05) |
| Pre-key bundle fetch (atomic OPK pop) | Present, correct |
| Message send/poll/ACK | Present, **no ownership check on ACK** (GAP-06) |
| WebSocket real-time push | Present, **JWT in URL** (GAP-07) |
| Rate limiting | **Missing** (GAP-14) |
| Key format validation | **Missing** (GAP-15) |
| Sequential IDs | Present, **enumerable** (GAP-13) |
| Channel member endpoints | Present as stubs only (not wired to SKDM distribution) |
| TLS | Handled at reverse-proxy layer (documented) |

---

## 7. Build & Release Hardening

| Item | Status |
|------|--------|
| `isMinifyEnabled` in release | **false** — must be true (GAP-16) |
| `isShrinkResources` in release | **false** — must be true |
| ProGuard rules for kotlinx.serialization | **Missing** |
| ProGuard rules for libsignal | **Missing** |
| Build-time assertion for placeholder server URL | **Missing** (GAP-17) |
| Certificate pinning | **Missing** (GAP-18) |
| `debuggable false` in release build | Needs verification |

---

## 8. Prioritized Fix List

### P0 — Must Fix Before Demo/Submission

None that would block running the demo on a single device. All P0 issues (GAP-01, GAP-02, GAP-03) are confirmed fixed in the current code.

### P1 — Fix Before Capstone Submission

| # | Gap | File | Effort |
|---|-----|------|--------|
| 1 | GAP-19: ACK after decryption | `AppContainer.kt` | 30 min |
| 2 | GAP-09: `revokeRemoteTokens()` calls `DELETE /v1/users` | `IdentityManager.kt` | 1 hr |
| 3 | GAP-10: OPK ID collision in replenishment | `SignalKeyManager.kt` | 30 min |
| 4 | GAP-12: Old SPK cleanup after rotation | `SignalKeyManager.kt` | 1 hr |
| 5 | Audit log persistence to encrypted file | `SecurityRoute.kt` + new util | 2–3 hr |
| 6 | GAP-04: WorkManager retry for server registration | `AppContainer.kt` | 2 hr |
| 7 | GAP-07: Move JWT out of WebSocket URL | `SignalServerClient.kt` | 1 hr |
| 8 | GAP-05: Signed proof-of-possession on registration | `server/index.js` | 2–3 hr |
| 9 | GAP-06: Ownership check on message ACK | `server/index.js` | 30 min |
| 10 | GAP-14: Rate limiting on server | `server/index.js` | 1 hr |

### P2 — Fix If Time Permits

| # | Gap | File | Effort |
|---|-----|------|--------|
| 1 | GAP-08: Document vault disk persistence | `IdentityManager.kt` + new util | 3–4 hr |
| 2 | GAP-18: Certificate pinning | `SignalServerClient.kt` | 1 hr |
| 3 | GAP-21: Delete dead `InMemoryMessageRepository` | source tree | 15 min |
| 4 | GAP-16: Enable minification + ProGuard | `build.gradle.kts` | 1–2 hr |
| 5 | GAP-17: Build-time assertion for server URL | `build.gradle.kts` | 30 min |
| 6 | GAP-13: UUID primary key for message queue | `server/index.js` + schema | 30 min |
| 7 | GAP-15: Key format validation on server | `server/index.js` | 1 hr |
| 8 | Channel re-distribution on member join | `ChannelRepository.kt` | 1 hr |
| 9 | OPK exhaustion warning in Security Dashboard | `SecurityRoute.kt` | 1 hr |
| 10 | Provisioning rollback on partial upload failure | `SignalKeyManager.kt` | 2 hr |

---

## 9. Summary

| Category | Total Items | Done | Pending |
|----------|-------------|------|---------|
| Design objectives (Chapter 4) | 10 | 9 | 1 (doc vault) |
| UI audit issues | 20 | 20 | 0 |
| Code gaps (GAP-01 to GAP-21) | 21 | 6 | 15 |
| Missing failure fallbacks | 10 | 0 | 10 |
| Build hardening items | 7 | 0 | 7 |

**Honest assessment:** The app is a strong capstone prototype. The cryptographic core, access control, metadata normalisation, and panic wipe all work as specified. The main remaining work is defensive programming (error fallbacks, retry paths, dead code removal) and server hardening. None of the pending items would prevent a successful single-device demo, but several (GAP-07, GAP-18, GAP-19) represent genuine security gaps that should be disclosed in the report's limitations section.
