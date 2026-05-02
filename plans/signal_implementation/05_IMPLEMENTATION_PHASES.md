# Signal Implementation Phases

Six phases. Each ends with a working, testable checkpoint. Server and Android tracks are interleaved — the server must exist before the Android client can register, but server and Android signal code can be built in parallel up to the point of integration.

---

## Phase S0 — Server foundation (2–3 days)

**Goal:** A running PostgreSQL instance and a minimal HTTP server that accepts registrations. No Signal crypto yet — just the plumbing.

### Tasks

| Task | Notes |
|---|---|
| Install PostgreSQL 16 (Docker: `docker run -e POSTGRES_PASSWORD=... postgres:16`) | |
| Run schema from [01_SERVER_ARCHITECTURE.md](01_SERVER_ARCHITECTURE.md) — all five tables | |
| Implement `POST /v1/users` — insert user + keys, return JWT | |
| Implement `GET /health` — returns 200, confirms DB connection | |
| Implement JWT middleware — validates `Authorization: Bearer` on all other routes | |
| Enable TLS on the server (self-signed cert acceptable for dev) | |
| Write a curl/Postman test for the registration endpoint | |

### Done when

- `POST /v1/users` inserts a row into `users`, `signed_prekeys`, and `one_time_prekeys`
- JWT returned by registration is accepted by the auth middleware on subsequent requests
- `GET /health` returns 200

---

## Phase S1 — Pre-key infrastructure (3–4 days)

**Goal:** Server can hand out pre-key bundles. Android app generates and uploads Signal keys on provisioning.

### Tasks — Server

| Task | Notes |
|---|---|
| Implement `GET /v1/keys/{userId}` — atomic OPK pop + return bundle | Use `DELETE ... RETURNING` SQL from [01_SERVER_ARCHITECTURE.md](01_SERVER_ARCHITECTURE.md) |
| Implement `PUT /v1/keys` — bulk OPK upload | |
| Implement `PUT /v1/keys/signed` — SPK upload (rotation) | |
| Implement `GET /v1/keys/count` — return current OPK count for the caller | Needed for client-side replenishment trigger |
| Write integration test: register → fetch own bundle → confirm OPK consumed | |

### Tasks — Android

| Task | Notes |
|---|---|
| Add `AstraSignalProtocolStore.kt` from [03_ANDROID_CHANGES.md](03_ANDROID_CHANGES.md) | Backed by `EncryptedSharedPreferences("astra_signal")` |
| Add `SignalKeyManager.kt` | `provision()` generates all key material and calls `POST /v1/users` |
| Add OkHttp + Retrofit to `libs.versions.toml` and `build.gradle.kts` | |
| Add `SignalServerClient.kt` — stub all endpoints, implement `register()` and `fetchPreKeyBundle()` | |
| Extend `IdentityManager.provisionIdentity()` — call `signalKeyManager.provision()` after Keystore key generation | |
| Extend `AppContainer` — add `signalStore`, `serverClient`, `signalKeyManager` | |
| Add `INTERNET` permission to `AndroidManifest.xml` | |
| Add `network_security_config.xml` with server domain + cert pin | |
| Unit test: `AstraSignalProtocolStore` round-trips identity key pair + session + pre-key | |

### Done when

- Fresh provisioning on a device → server's `users` table has a new row, 100 OPKs stored
- `GET /v1/keys/{userId}` returns a valid bundle, OPK count in DB drops by 1
- A second fetch returns a different OPK; after 100 fetches, `oneTimePreKey` is absent (bundle still valid)

---

## Phase S2 — Session establishment + first message (3–4 days)

**Goal:** Alice can send a `PreKeySignalMessage` to Bob. Bob can decrypt it. No channel yet — plain 1:1 session.

### Tasks — Server

| Task | Notes |
|---|---|
| Implement `POST /v1/messages/{recipientId}` — insert into `message_queue` | |
| Implement `GET /v1/messages` — return all queued messages for the caller | |
| Implement `DELETE /v1/messages` — delete by IDs (delivery ACK) | |
| Implement `POST /v1/channels/{id}/members` + `GET /v1/channels/{id}/members` | |

### Tasks — Android

| Task | Notes |
|---|---|
| Add `MessageTransport.kt` interface | |
| Add `SignalCryptoEngine.kt` — `encryptForAddress()` and `decryptFromAddress()` only | X3DH + `SessionCipher` |
| Implement `SignalServerClient.sendMessage()`, `fetchMessages()`, `acknowledgeMessages()` | |
| Wire `MessageTransport` into `AppContainer` | |
| Add a debug-only test screen (or use existing debug button in `ChatRoute`) to send a raw 1:1 session message to a hardcoded second user ID and decrypt the response | Not shipped to prod |
| Unit test: `SignalCryptoEngine` round-trip with two `AstraSignalProtocolStore` instances in the same process (simulates two devices) | |

### Done when

- Alice's device builds a session with Bob (X3DH), sends a `PreKeySignalMessage` via server
- Bob's device fetches the message, decrypts it, ACKs
- Subsequent message from Alice is `SignalMessage` (not PreKey) — session is now established
- Tamper one byte of the ciphertext → `InvalidMessageException` thrown on Bob's side

---

## Phase S3 — Channel Sender Keys (3–4 days)

**Goal:** Channel messaging works. Alice and Bob can both send and receive in the same channel using Sender Keys. This replaces the current AES-GCM channel path entirely.

### Tasks — Android

| Task | Notes |
|---|---|
| Add `SignalCryptoEngine.encryptForChannel()` and `decryptFromChannel()` | `SenderKeyGroupCipher` |
| Add `SignalCryptoEngine.distributeChannelSenderKey()` | Generates SKDM, wraps in 1:1 session, sends to each member |
| Add `SignalCryptoEngine.processSenderKeyDistribution()` | Called when `messageType=3` arrives |
| Rewire `InMemoryMessageRepository.send()` to use `signalCryptoEngine.encryptForChannel()` | |
| Rewire `InMemoryMessageRepository.receive()` to dispatch by `messageType` (1/2/3) | |
| Launch a background coroutine in `AppContainer` that collects `messageTransport.observeIncoming()` and calls `messageRepository.receive()` for each arriving message | This is what makes incoming messages appear live in the chat UI |
| Call `signalCryptoEngine.distributeChannelSenderKey()` when a user opens a channel for the first time (one-time per channel entry) | Wire into `ChatViewModel` init |
| Unit test: two-device channel message round-trip (both Alice and Bob send, both decrypt) | |

### Tasks — Server (minor)

| Task | Notes |
|---|---|
| Add WebSocket endpoint `wss://.../v1/websocket` | Push new queue rows to connected clients |
| Implement `keysNeeded` push when a user's OPK count < 10 | |

### Done when

- Alice sends a channel message → Bob sees it in real time (WebSocket path)
- Bob sends a reply → Alice sees it in real time
- Both use Sender Keys (single encrypt, not N encryptions)
- All existing chat UI states (send, receive, INTEGRITY_FAIL) continue to work

---

## Phase S4 — Session persistence + key rotation + wipe extension (2–3 days)

**Goal:** Signal sessions survive app restart. Key rotation works. Panic wipe destroys all Signal state.

### Tasks — Android

| Task | Notes |
|---|---|
| Verify `AstraSignalProtocolStore` survives process death — sessions, pre-keys, sender keys all in `EncryptedSharedPreferences` | Already designed this way; integration test to confirm |
| Implement SPK rotation — `SignalKeyManager.rotateSignedPreKey()` called on a 7-day timer or on app foreground if last rotation > 7 days | Store `signal_spk_last_rotated_ms` in prefs |
| Implement OPK replenishment — collect `keysNeeded` WebSocket event, call `signalKeyManager.replenishPreKeys()` | |
| Extend `IdentityManager.wipeAll()` — call `signalStore.wipeAll()` | `wipeAll()` must clear all `signal_` prefixed prefs |
| Extend `PanicViewModel` — add `DELETE /v1/users/{userId}` call to `revokeRemoteTokens()` step | Removes the user's pre-key records from the server so no new sessions can be established with the wiped identity |
| Wire key rotation UI (ROTATE NOW) to `signalCryptoEngine.distributeChannelSenderKey()` — re-distributes a fresh sender key to all channel members | |

### Done when

- Kill app, reopen → can still send and receive (sessions persisted)
- Panic wipe → `signal_` prefs cleared, server user record deleted, `TerminatedScreen` shown
- ROTATE NOW → new sender chain generated, all members receive new SKDM, old chain key deleted

---

## Phase S5 — Hardening + certificate pinning (1–2 days)

**Goal:** Network security is production-grade.

### Tasks

| Task | Notes |
|---|---|
| Certificate pinning: add server's leaf cert SHA-256 to `network_security_config.xml` | Prevents interception even if a rogue CA is installed on the device |
| Trust-on-first-use (TOFU) display: when `isTrustedIdentity()` returns false (key changed), surface a warning in the chat UI — `[ IDENTITY_CHANGED // VERIFY OUT-OF-BAND ]` | Analogous to Signal's safety number change warning |
| Disable TLS 1.0/1.1 on the server — TLS 1.2 minimum, TLS 1.3 preferred | |
| Verify `cleartext traffic permitted = false` in Network Security Config | |
| Verify Logcat emits no plaintext message content (grep for known test strings) | |
| Extend `05_TESTING.md` — add Signal-specific integration tests: session persistence, OPK consumption, SKDM delivery | |

### Done when

- Wireshark capture of device traffic shows only TLS ciphertext — no plaintext
- Modifying the server's cert (MITM simulation) causes the app to refuse the connection
- All tests pass

---

## Dependency on existing app phases

| App Phase | Required before Signal? | Why |
|---|---|---|
| Phase 0 (Foundations, AppContainer, DI) | **Yes — must be complete** | `AppContainer` is where `AstraSignalProtocolStore`, `SignalCryptoEngine`, and `SignalKeyManager` are wired in |
| Phase 1 (IdentityManager, provisioning) | **Yes — must be complete** | `provisionIdentity()` is the trigger for Signal key generation + server registration. Must be real, not a stub |
| Phase 2 (Channels, Admin) | **Yes — must be complete** | Channel IDs exist and are stable before Sender Keys can reference them. Channel membership (who to distribute SKDM to) comes from channel data |
| Phase 3 (ChatViewModel, MessageRepository) | **Yes — must be complete** | `InMemoryMessageRepository` is what gets rewired to Signal. `ChatViewModel` init is where SKDM distribution is triggered |
| Phase 4 (Persistence, EncryptedSharedPreferences wiring) | **Yes — must be complete** | Signal session state (ratchet records, pre-keys, sender keys) must survive app restarts. This requires `EncryptedSharedPreferences` to be working and trusted |
| Phase 5 (Panic wipe) | **Partial — recommended before S4** | Panic wipe should be extended in Signal Phase S4. Build Signal first; extend panic wipe in Phase S4 before final demo |
