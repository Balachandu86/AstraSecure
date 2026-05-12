# AstraSecure — Implementation Gap Audit (gaps_1)

**Date:** 2026-05-03  
**Scope:** Full codebase review — Android app (`app/src/main/java`) + relay server (`server/index.js`)  
**Triggered by:** User was able to complete provisioning (callsign entry + key generation) without a server connection and reach the home screen.

---

## How to read this document

| Priority | Meaning |
|----------|---------|
| P0 — Critical | Breaks core functionality or allows identity/message compromise |
| P1 — High | Significant security or reliability gap |
| P2 — Medium | Needs resolution before a real deployment |
| P3 — Low | Hardening / polish items |

Each gap includes: **Root cause** (exact file + line), **Impact**, and **Fix**.

---

## P0 — Critical

---

### GAP-01 — Provisioning succeeds without server registration

**Root cause:**

`SignalKeyManager.kt:78-82` — `provision()` calls `transport.register()` and wraps the result, but **does not throw** on failure:

```kotlin
if (result.isSuccess) {
    Log.i(TAG, "Registered with Signal server successfully")
} else {
    Log.w(TAG, "Server registration failed (will retry): ...")
    // ← no throw, no flag set
}
```

`ProvisioningViewModel.kt:83-91` — Because `signalKeyManager.provision()` returns normally even when the server is unreachable, the outer `runCatching` sees success and emits `_provisioningSuccess`:

```kotlin
runCatching { signalKeyManager.provision(user.id, callsign) }
    .onSuccess {
        _state.value = ProvisioningUiState.Provisioning("IDENTITY COMMITTED")
        _provisioningSuccess.emit(Unit)   // ← fires even if server was never reached
    }
```

`MainActivity.kt:22-28` — On every subsequent launch, `getUserIdentity() != null` is true (identity is stored locally in EncryptedSharedPreferences), so the app always lands on `"app"` regardless of whether server registration ever succeeded.

**Impact:** The user you tested reached the home screen with local keys only. They have no JWT, no server record, and no Signal pre-key bundle published. Every attempt to send or receive a message will silently fail. There is nothing in the UI to indicate this state.

**Fix:**
1. Add a `serverRegistered: Boolean` flag to `AstraSignalProtocolStore` (e.g. `signal_server_registered`).
2. Set it to `true` only in the `result.isSuccess` branch of `SignalKeyManager.provision()`.
3. In `AppContainer.init()`, check the flag and retry registration if it is false.
4. Block navigation to `"app"` in `ProvisioningViewModel` until `result.isSuccess`, showing a "CONNECTING TO SERVER..." state and a retry button on failure.

---

### GAP-02 — JWT is never persisted; app is unauthenticated on every cold start

**Root cause:**

`SignalServerClient.kt:82` — `jwt` is a plain in-memory `var`:

```kotlin
private var jwt: String = ""
```

It is set in `register()` (line 146: `jwt = response.token`) but never written to disk. On every app kill + relaunch, `jwt` resets to `""`.

`SignalServerClient.kt:213-214` — `observeIncoming()` checks this field before opening the WebSocket:

```kotlin
override fun observeIncoming(): Flow<TransportMessage> {
    if (serverUrl.isBlank() || jwt.isBlank()) return emptyFlow()
```

**Impact:** After the very first provisioning session ends, the JWT is gone. Every subsequent launch the WebSocket never opens. No real-time messages are ever received. HTTP endpoints that require `Authorization: Bearer` also fail silently (network calls log warnings but the UI shows no error).

**Fix:** Persist the JWT in `AstraSignalProtocolStore` (or `EncryptedSharedPreferences`) under a key like `signal_jwt`. Load it back in `SignalServerClient` init. Add a refresh path for when the server returns 401 (re-register or re-login endpoint).

---

### GAP-03 — `SignalCryptoEngine` is initialized with `"unknown"` as the local user ID on a fresh install

**Root cause:**

`AppContainer.kt:55` — The engine is created once at application start, before provisioning has run:

```kotlin
val signalCryptoEngine = SignalCryptoEngine(
    signalStore, serverClient,
    identityManager.getUserIdentity()?.id ?: "unknown"   // ← "unknown" on first install
)
```

The `AppContainer` singleton is never replaced after provisioning completes. All Signal protocol operations (SKDM distribution, channel encryption, session addressing) use `"unknown"` as the local address for the lifetime of that process.

**Impact:** Signal X3DH session establishment and sender key distribution will use the wrong sender address. On a real multi-device deployment the server would route responses to `"unknown"` rather than the actual user ID, breaking end-to-end delivery.

**Fix:** Pass the userId lazily (via a lambda or `StateFlow<String>`) instead of at construction time, or rebuild `AppContainer` after provisioning completes by having `AstraApp` expose a `resetContainer()` and navigating through a process restart.

---

## P1 — High

---

### GAP-04 — No server re-registration retry mechanism despite inline comment claiming otherwise

**Root cause:**

`SignalKeyManager.kt:81` states `"(will retry)"` but no retry logic exists anywhere in the codebase. `AppContainer.init()` calls `rotateSignedPreKeyIfNeeded()` on start (line 101-105), but only if `signalStore.isProvisioned()` — and `isProvisioned()` checks for a local identity key (`signal_ikp`), not for a server registration flag.

**Impact:** A device provisioned offline is permanently stuck in an unregistered state with no automated recovery path.

**Fix:** See GAP-01 fix — add a `serverRegistered` flag and a retry coroutine in `AppContainer.init()`.

---

### GAP-05 — `POST /v1/users` registration is unauthenticated — identity overwrite possible

**Root cause:**

`server/index.js:72` — No `auth` middleware on the registration endpoint:

```js
app.post('/v1/users', async (req, res) => { ... })
```

The `INSERT ... ON CONFLICT DO UPDATE` pattern on line 87-94 means any caller that knows a target's `userId` (a UUID, but derivable from the app) can overwrite their `identity_key`, `registration_id`, and then upload replacement pre-keys — effectively hijacking the account's pre-key bundle before any session is established.

**Impact:** Pre-key bundle poisoning. An attacker who intercepts or knows a userId can replace the victim's public keys on the server. Any new X3DH session established against the server's key bundle will be encrypted to the attacker's keys, not the victim's.

**Fix:** Require a signed proof-of-possession (sign the registration payload with the Android Keystore private key and include the public key for verification) before accepting a `userId`. Alternatively, make re-registration require the existing JWT.

---

### GAP-06 — `DELETE /v1/messages` (ACK endpoint) has no ownership check

**Root cause:**

`server/index.js:345-360` — Messages are deleted by ID with no check that `req.user.userId` is the recipient:

```js
app.delete('/v1/messages', auth, async (req, res) => {
  const { messageIds } = req.body;
  ...
  await pool.query(
    'DELETE FROM message_queue WHERE id = ANY($1::bigint[])',
    [messageIds],
  );
```

**Impact:** Any authenticated user can delete any message from any other user's queue by guessing or enumerating sequential IDs (see GAP-13). This can be used to permanently destroy unread messages for a target user.

**Fix:** Add `AND recipient_id = $2` to the DELETE query, binding `req.user.userId`.

---

### GAP-07 — WebSocket JWT is passed as a URL query parameter

**Root cause:**

`SignalServerClient.kt:217-219`:

```kotlin
val wsUrl = serverUrl + "/$WS_PATH?token=$jwt"
```

**Impact:** The JWT appears in server access logs, reverse proxy logs, ngrok logs, browser DevTools network tab, and any passive network observer. A 30-day JWT exposed this way can be replayed until expiry.

**Fix:** Open the WebSocket without authentication, then send the JWT in the first text frame (`{ "type": "auth", "token": "..." }`). On the server, hold the connection in an unauthenticated state until the first frame is validated.

---

### GAP-08 — Document vault is in-memory only; intel attachments are lost on process death

**Root cause:**

`IdentityManager.kt:29-31`:

```kotlin
private val docVault = mutableMapOf<String, ByteArray>()
private val docMeta  = mutableMapOf<String, Triple<String, String, Long>>()
```

`storeDocument()` (line 96) writes to `docVault` only. There is no persistence to disk. `retrieveDocument()` (line 136) reads exclusively from `docVault` — if the process was killed between attach and retrieve the document is gone.

**Impact:** Any intel file attached in a chat session is permanently inaccessible after the user backgrounds the app long enough for Android to kill the process. Data loss for a "secure document vault" feature is a critical reliability failure.

**Fix:** Persist the AES-GCM ciphertext to a private file in `filesDir` keyed by `docId`. The Keystore key survives the app restart so decryption remains possible. Add a `docManifest` EncryptedSharedPreferences entry to track which docs exist.

---

## P2 — Medium

---

### GAP-09 — Panic wipe `revokeRemoteTokens()` is a no-op; JWT survives offline wipe

**Root cause:**

`IdentityManager.kt:152-154`:

```kotlin
fun revokeRemoteTokens() {
    // No-op for capstone — no remote auth tokens to revoke
}
```

If `deleteUser()` (called right after, in `PanicRoute.kt:43-46`) fails due to network unavailability, the server record and a 30-day-valid JWT remain. The local identity is wiped but the server-side account is intact.

**Impact:** An adversary who extracted the JWT before panic wipe can still poll the message queue on the server for 30 days.

**Fix:** Add a dedicated `/v1/tokens/revoke` endpoint that invalidates the JWT server-side (e.g. via a token blocklist table). `revokeRemoteTokens()` must call it. Wipe should queue a pending revocation if offline and execute it on next connect.

---

### GAP-10 — OPK replenishment computes starting ID incorrectly; risk of ID collision

**Root cause:**

`SignalKeyManager.kt:90-96`:

```kotlin
val existingMax = store.loadSignedPreKeys()   // ← SPK list, not OPK list
    .mapNotNull { runCatching { it.id }.getOrNull() }
    .maxOrNull() ?: 0
val newKeys = generatePreKeys(
    startId = (existingMax + 1).coerceAtLeast(store.preKeyCount() + 1),
    ...
)
```

`store.preKeyCount()` returns the **count** of remaining OPKs, not the max ID. If 50 of the original 100 OPKs (IDs 1–100) have been consumed, `preKeyCount()` returns 50, and new keys would start at ID 51 — but IDs 51–100 still exist in the store. Those would be overwritten.

**Impact:** Signal OPK collision causes `InvalidKeyIdException` during session establishment.

**Fix:** Add a `maxPreKeyId(): Int` method to `AstraSignalProtocolStore` that scans `signal_pk_*` keys for the highest numeric suffix, and use that as the floor for new OPK IDs.

---

### GAP-11 — `loadSignedPreKeys()` silently attempts to parse the rotation timestamp as a key record

**Root cause:**

`AstraSignalProtocolStore.kt:167-170` — The filter matches any key starting with `signal_spk_`:

```kotlin
.filter { it.key.startsWith("signal_spk_") }
.mapNotNull { (_, v) -> runCatching { SignedPreKeyRecord(decode(v as String)) }.getOrNull() }
```

The rotation timestamp is stored under `signal_spk_last_rotated_ms` (line 153) — it starts with `signal_spk_` and its value is a `Long`, not a `String`. `v as String` throws `ClassCastException` every time `loadSignedPreKeys()` is called, silently swallowed by `runCatching`.

**Impact:** Functionally benign today (the rotation timestamp is excluded from results). Becomes a correctness bug if `loadSignedPreKeys()` is used to drive rotation logic, and it adds noise to every call. Also causes `replenishPreKeys()` to compute `existingMax` from an incomplete list.

**Fix:** Rename the rotation timestamp key to `signal_meta_spk_last_rotated_ms` (different prefix), or add a secondary filter `it.key != "signal_spk_last_rotated_ms"`.

---

### GAP-12 — Old SPKs accumulate indefinitely; no 48-hour cleanup scheduler

**Root cause:**

`SignalKeyManager.kt:110-111` comment:

```
// The previous SPK is retained in the store for 48 hours to cover in-flight messages
// encrypted to the old SPK; callers are responsible for scheduling deletion.
```

No WorkManager job, coroutine timer, or cleanup call exists anywhere in the codebase.

**Impact:** Over weeks, `loadSignedPreKeys()` returns a growing list. The server always uses the highest SPK ID but the old keys stay in `EncryptedSharedPreferences` indefinitely, consuming storage and leaking historical key material that should be deleted.

**Fix:** Schedule a `CoroutineWorker` (or add to the existing `rotateSignedPreKeyIfNeeded()` call path) that calls `store.removeSignedPreKey(id)` for any SPK older than 48 hours.

---

### GAP-13 — `message_queue` uses sequential `BIGSERIAL` IDs; enumerable by any authenticated user

**Root cause:**

`server/schema.sql:32` — `id BIGSERIAL PRIMARY KEY`. IDs are sequential integers starting at 1.

Combined with GAP-06 (no ownership check on ACK), an authenticated user can iterate message IDs `1, 2, 3...` to delete any message in the queue. Even without the deletion attack, sequential IDs reveal the total message volume handled by the server (traffic analysis).

**Fix:** Replace with `gen_random_uuid()` as the primary key (requires `pgcrypto`), or use a random 64-bit integer (`floor(random() * 9223372036854775807)::bigint`).

---

### GAP-14 — No server-side rate limiting; OPK exhaustion, queue flooding, and registration spam possible

**Root cause:**

`server/index.js` — No middleware for rate limiting on any endpoint. `express.json({ limit: '2mb' })` only caps body size. Relevant endpoints with no throttle:

- `POST /v1/users` — spammable to flood `users` and `signed_prekeys` tables
- `GET /v1/keys/:userId` — each call atomically consumes one OPK; repeated calls exhaust a user's OPK supply
- `POST /v1/messages/:recipientId` — unlimited message queue stuffing

**Fix:** Add `express-rate-limit` (already available in the npm ecosystem) per-IP and per-JWT. Example: max 5 registrations/minute per IP, max 100 messages/minute per JWT, max 20 key fetches/hour per fetching user.

---

### GAP-15 — Server accepts Signal key material without format validation; garbage keys silently stored

**Root cause:**

`server/index.js:73-128` (register) and `241-259` (upload SPK) — The server stores `identityKey`, `signedPreKey.publicKey`, `signedPreKey.signature`, and OPK public keys as raw base64 strings with no validation that they are valid Curve25519 points or that the SPK signature verifies against the identity key.

**Impact:** Any authenticated user can upload a malformed SPK (e.g. all-zero bytes). When another user fetches the pre-key bundle and attempts X3DH, libsignal throws `InvalidKeyException`, session establishment fails, and the sending client has no way to distinguish "server returned bad key" from "network error."

**Fix:** On the server, decode the base64 and verify: (a) identity key and pre-key public bytes are 33 bytes (compressed Curve25519); (b) the SPK signature is 64 bytes and verifies against the identity key using a JS Curve25519 library (e.g. `@signalapp/libsignal-client` Node bindings).

---

## P3 — Low

---

### GAP-16 — Release build has `isMinifyEnabled = false`

**Root cause:**

`app/build.gradle.kts:29`:

```kotlin
release {
    isMinifyEnabled = false
    ...
}
```

**Impact:** The release APK ships with all class names, method names, and string literals intact. Reverse engineering the APK with `jadx` requires no deobfuscation. For a security-focused app handling cryptographic key material and military callsigns, this is significant.

**Fix:** Set `isMinifyEnabled = true` and add ProGuard keep rules for libsignal, Retrofit, and Gson model classes.

---

### GAP-17 — Release build server URL is a placeholder

**Root cause:**

`app/build.gradle.kts:38`:

```kotlin
buildConfigField("String", "SIGNAL_SERVER_URL", "\"https://your.server.host\"")
```

**Impact:** Any release build shipped without changing this line will silently connect to `https://your.server.host`, fail with a DNS error, and appear to work offline (GAP-01 means the user still lands on the home screen).

**Fix:** Make the build fail if the placeholder is detected, e.g. add a Gradle task that asserts `SIGNAL_SERVER_URL` does not contain `your.server`.

---

### GAP-18 — No certificate pinning; MITM possible on untrusted networks

**Root cause:**

`SignalServerClient.kt:88-106` — `OkHttpClient` is built with no `CertificatePinner` or `X509TrustManager` override. Default Android trust store is used.

**Impact:** On any network with a rogue CA (corporate proxy, café Wi-Fi, compromised ISP), an attacker can terminate TLS and inspect registration requests, JWT tokens, and pre-key bundles in plaintext.

**Fix:** Add OkHttp `CertificatePinner` pinning the leaf or intermediate certificate of the relay server. For the local Docker dev server, skip pinning in `DEBUG` builds only.

---

### GAP-19 — Incoming WebSocket messages are ACKed before processing; lost on decryption failure

**Root cause:**

`SignalServerClient.kt:242-248` — The ACK frame is sent inside `onMessage()` before the message is handed to `AppContainer` for decryption:

```kotlin
override fun onMessage(webSocket: WebSocket, text: String) {
    runCatching {
        ...
        trySend(msg)       // ← message emitted to flow
        webSocket.send(    // ← ACK sent immediately, same coroutine
            JSONObject().apply { put("type", "ack"); put("messageIds", ...) }.toString()
        )
    }
}
```

`AppContainer` processes the message asynchronously. If decryption throws (e.g. missing session state), the message is already deleted from the server queue and is permanently lost.

**Fix:** Emit the message to the processing flow and ACK only after `AppContainer` confirms successful decryption and delivery (e.g. via a callback or a separate ACK flow that `AppContainer` pushes into).

---

### GAP-20 — `ChatViewModel` falls back to callsign as `userId`, breaking server routing

**Root cause:**

`ChatRoute.kt:85`:

```kotlin
userId = identity?.id ?: callsign   // fall back to callsign if not yet provisioned
```

**Impact:** If `getUserIdentity()` returns null (first launch in the same session before AppContainer is fully initialized), outgoing messages are sent with `senderId = "OPERATOR"` (or whatever callsign was entered). The server JWT identifies the user by UUID, so the sender field in the stored message will be wrong. Worse, the Signal `distributeChannelSenderKey()` call uses this wrong address for the sender key distribution message.

**Fix:** Make `ChatViewModel` fail initialization (show `ChatUiState.Error`) if `getUserIdentity()` returns null, and surface a clear message to the user. Never fall back to a non-UUID for a field used in cryptographic addressing.

---

### GAP-21 — `InMemoryMessageRepository` is dead code but creates confusion

**Root cause:**

`AppContainer.kt:61` wires `SignalMessageRepository` unconditionally. `InMemoryMessageRepository` (`MessageRepository.kt:27-102`) still exists with a different encryption scheme (AES-GCM mission keys instead of Signal sender keys). Nothing instantiates it.

**Impact:** A developer reading the code cannot tell which repository is active without tracing `AppContainer`. More critically, if someone swaps the wiring to `InMemoryMessageRepository` for testing, messages will be AES-GCM encrypted on the sender and Signal-decrypted on the receiver — silently producing garbage plaintext.

**Fix:** Delete `InMemoryMessageRepository` or move it to a `test` source set and annotate clearly.

---

## Summary table

| ID | Area | Priority | Status |
|----|------|----------|--------|
| GAP-01 | Provisioning — no server confirmation gate | P0 | Fixed |
| GAP-02 | JWT not persisted — always offline after cold start | P0 | Fixed |
| GAP-03 | SignalCryptoEngine frozen with "unknown" userId | P0 | Fixed |
| GAP-04 | No re-registration retry despite comment claiming one | P1 | Open |
| GAP-05 | `POST /v1/users` unauthenticated — identity overwrite | P1 | Open |
| GAP-06 | ACK DELETE has no ownership check | P1 | Open |
| GAP-07 | WebSocket JWT in URL query param | P1 | Open |
| GAP-08 | Document vault in-memory only — lost on process death | P1 | Open |
| GAP-09 | `revokeRemoteTokens()` is a no-op | P2 | Open |
| GAP-10 | OPK replenishment ID computation incorrect | P2 | Open |
| GAP-11 | Rotation timestamp parsed as SPK record silently | P2 | Open |
| GAP-12 | Old SPKs never cleaned up | P2 | Open |
| GAP-13 | Sequential message queue IDs enumerable | P2 | Open |
| GAP-14 | No server-side rate limiting | P2 | Open |
| GAP-15 | No Signal key format validation on server | P2 | Open |
| GAP-16 | Release build not minified | P3 | Open |
| GAP-17 | Release server URL is placeholder | P3 | Open |
| GAP-18 | No certificate pinning | P3 | Open |
| GAP-19 | WebSocket messages ACKed before decryption | P3 | Open |
| GAP-20 | ChatViewModel falls back to callsign as userId | P3 | Open |
| GAP-21 | `InMemoryMessageRepository` is dead code | P3 | Open |
