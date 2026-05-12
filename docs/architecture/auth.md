# AstraSecure — Authentication & JWT Lifecycle

This document specifies how operators authenticate to the AstraSecure backend
and how the same JWT is shared between the **Signal relay** (existing) and
the **REST API** (Phase 1+, to be built).

The over-arching principle: **provisioning IS authentication**. There is no
password, no login screen. A device proves its identity by holding a non-exportable
EC private key in the Android Keystore; the server returns a JWT after Signal
registration; that JWT is the operator's bearer token for the rest of the
session.

---

## 1. Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                          Android Client                              │
│                                                                      │
│  Keystore (EC privkey) ──signs──▶ ProvisioningRequest                │
│                                          │                           │
│                                          ▼                           │
│                              POST /v1/users (Signal relay)           │
│                                          │                           │
│                                          ▼                           │
│              ◀──── 200 OK { token: <jwt> } ────                       │
│                                          │                           │
│              EncryptedSharedPreferences  │                           │
│                  ◀ jwt persisted ◀───────┘                           │
│                                                                      │
│  Every subsequent HTTP/WS call includes Authorization: Bearer <jwt>  │
└──────────────────────────────────────────────────────────────────────┘
```

Both servers (Signal relay AND REST API) validate the same JWT against the
same signing key. There is one identity per device, one token per identity.

---

## 2. JWT structure

### Algorithm
**HS256** (HMAC-SHA-256) with a shared `JWT_SECRET` environment variable.

The Phase 0 doc originally specified RS256 + JWKS on the assumption the
REST API would be a separate server from the Signal relay. Because both
are the same Node.js process, a shared symmetric secret is simpler and
equally secure — there is no cross-service trust boundary to bridge.

If the REST API and Signal relay are ever split into separate processes,
switch to RS256: the relay signs with a private key; the REST API validates
with the relay's published public key via JWKS.

### Header
```json
{ "alg": "HS256", "typ": "JWT" }
```

### Payload (claims)

| Claim | Type | Meaning |
|---|---|---|
| `iss` | string | `"https://signal.astrasecure.app"` — issuer URL |
| `sub` | string | The user's UUID (matches `users.id` in Postgres) |
| `iat` | number | Issued-at (Unix seconds) |
| `exp` | number | Expiry — `iat + 24*3600` (24-hour TTL) |
| `nbf` | number | Not-before — `iat` |
| `jti` | string | Unique token ID (UUID) — used for revocation |
| `callsign` | string | Display name at issuance time (server doesn't trust this; uses `sub` lookup) |
| `device_id` | string | Stable device fingerprint — for audit logs and detecting key reuse across devices |
| `is_admin` | boolean | True if `users.is_admin = TRUE`. Server **re-checks** this on admin endpoints rather than trusting the claim, but the claim lets the client gate UI without an extra round-trip |

### Example (decoded)
```json
{
  "iss": "https://signal.astrasecure.app",
  "sub": "U-9F3A1B2C-...",
  "iat": 1715040000,
  "exp": 1715126400,
  "nbf": 1715040000,
  "jti": "T-A1B2C3D4-...",
  "callsign": "ARGUS-7",
  "device_id": "DEV-...",
  "is_admin": false
}
```

### Signature

Signed by the Signal relay's private key. Verified by:
- The Signal relay itself (loopback validation on incoming requests)
- The REST API server (using the relay's published public key, fetched once
  at REST API boot from `https://signal.astrasecure.app/.well-known/jwks.json`)

---

## 3. Issuance

JWTs are issued by exactly **one** endpoint:

### `POST /v1/users` (Signal relay)

Called once per device, during first-time provisioning. The request body
includes the device's identity public key, registration ID, signed pre-key,
and 100 OPKs. The server:

1. Validates the SPK signature against the identity key (proves the device
   holds the private key).
2. Persists `users` row with `id` = client-provided UUID.
3. Persists Signal pre-key material.
4. **Issues a JWT** with the claims above, `exp = iat + 86400`, returns it
   in the response body as `{ "token": "<jwt>" }`.
5. Records `(jti, sub, iat, exp)` in a `jwt_issued` table for revocation
   tracking.

**There is no second `/login` endpoint.** A device that loses its JWT (e.g.
due to a Keystore failure or restored backup) must wipe and re-provision —
it cannot get a new JWT for the same identity, because the Signal relay
will reject duplicate `POST /v1/users` for the same `userId`.

**Token TTL: 30 days.** (The Phase 0 doc specified 24 hours; the implemented
value is 30 days for usability during the capstone demo cycle. The
re-attestation refresh flow in §7 should still be built to bound the blast
radius of token theft; the TTL can be reduced once refresh lands.)

### Response

```json
{
  "token": "eyJhbGciOiJIUzI1NiIs..."
}
```

The client immediately:
1. Persists the token to `EncryptedSharedPreferences` (key: `signal_jwt`).
2. Calls `POST /api/users` (REST) with the same JWT. The REST server creates
   the mirror row in its own DB.
3. Calls `GET /api/sync` to bootstrap.

---

## 4. Storage on the client

| Where | Why |
|---|---|
| `EncryptedSharedPreferences` (file `astra_identity`, key `signal_jwt`) | At-rest encryption via AndroidX MasterKey (AES-256-GCM, key in Keystore) |
| In-memory cache in `SignalServerClient.jwt` | Hot path — every HTTP request reads it via OkHttp interceptor |

The JWT never appears in:
- Logs (the OkHttp logging interceptor uses `Level.BASIC`, which omits headers
  — see [SignalServerClient.kt:94-95](app/src/main/java/com/explo/capstone/transport/SignalServerClient.kt#L94-L95))
- WebSocket URL query strings (sent in the first frame instead — see
  [SignalServerClient.kt:231-235](app/src/main/java/com/explo/capstone/transport/SignalServerClient.kt#L231-L235))
- The local `astra_store.json` snapshot
- Crash reports (covered by ProGuard rules in Phase 2)

---

## 5. Validation (server-side)

Both Signal relay and REST API run the **same** validation before processing
any authenticated request:

```
1. Parse Authorization header → "Bearer <jwt>"; if missing → 401
2. Verify RS256 signature against the kid-resolved public key   → fail = 401
3. Check exp > now                                              → fail = 401
4. Check nbf <= now                                             → fail = 401
5. Check iss matches expected issuer                            → fail = 401
6. Check jti is NOT in the revocation list                      → fail = 401
7. Look up users WHERE id = sub
   - Not found  → 401
   - tombstoned → 410 Gone
8. Attach (sub, is_admin, jti) to the request context
```

Per-endpoint authorization (clearance gating) happens **after** validation —
see step 8 in the next section.

---

## 6. Authorization (clearance enforcement)

Authentication says "this token is valid"; **authorization** says "this user
may perform this action". The server enforces authorization on every mutating
endpoint:

| Action | Required | Source of truth |
|---|---|---|
| Create mission | authenticated only | — |
| Edit/archive mission | `rank_chief` on that mission | `clearance_assignments` |
| Create channel | rank with `level >= 5` on the parent mission | `clearance_assignments` JOIN `schema_ranks` |
| Edit/delete channel | `rank_chief` on the parent mission | same |
| Add/remove participant | `rank_chief` on the mission | same |
| Assign clearance | `rank_chief` on the mission, **and** cannot demote the last chief | same + count check |
| Create/edit/delete schema records | `users.is_admin = TRUE` | `users` (re-checked from DB, not from JWT claim) |
| Tombstone self | self only (sub == :userId) | — |
| View `/sync` | authenticated | scoped server-side |
| View `/audit` | authenticated; entries scoped server-side | `audit_events` joined with caller's missions |

The `is_admin` claim in the JWT is a **convenience** for the client to render
admin-only UI without a round-trip. The server re-reads `users.is_admin` from
Postgres on every admin endpoint hit, so a token issued before a demotion
(within its 24h TTL) is still rejected with 403.

The Android client's existing client-side gating (e.g. `userLevel >= channel.minPost`)
is now classified as a **UI hint**, not a security boundary — see
[threat_model.md](threat_model.md).

---

## 7. Refresh

There is **no refresh endpoint**. JWTs have a 24-hour TTL; when the token
expires, the client transitions to a degraded read-only mode:

1. Any HTTP call returns 401.
2. Client detects 401, sets `connectionState = false`, shows a banner: *"Session
   expired — go to Settings → Re-attest device to renew."*
3. The user opens Settings → Re-attest. The client calls a new endpoint:

### `POST /v1/sessions/refresh` (Signal relay)

The client signs a server-issued challenge with the device's identity private
key (Keystore-resident, non-exportable). The server verifies the signature
against `users.identity_key`, and if valid, issues a new JWT with the same
claims (and a new `jti`).

**Request**
```json
{
  "userId": "U-...",
  "challenge": "<server-issued nonce, fetched via GET /v1/sessions/challenge>",
  "signature": "<base64 ECDSA signature of challenge>"
}
```

**Response 200**
```json
{ "token": "<new jwt>" }
```

This re-attestation step is what proves the device still holds the private
key — without it, a stolen token would simply renew itself indefinitely.

**Why not silent refresh?** Signal protocol's identity model already requires
the operator to physically hold the device. Forcing a daily UI-visible
re-attestation is a deliberate friction point that bounds the blast radius
of token theft to 24 hours.

**Phase 0 deferral:** the refresh flow is specified here but not implemented
until Phase 6 — earlier phases ship with operators tolerating a daily
re-provision.

---

## 8. Revocation

Three independent mechanisms, in increasing order of severity:

### 8a. JTI deny-list (per-token revocation)
- Server maintains `jwt_revoked` table: `(jti, revoked_at, reason)`.
- Step 6 of validation rejects revoked JTIs.
- Used when: the user changes callsign, an admin force-logs-out a device,
  the server detects suspicious behavior on a single token.
- The deny-list is small because it only holds JTIs whose `exp > now`. A nightly
  job purges expired entries.

### 8b. User tombstone (per-user revocation)
- `POST /api/users/me/tombstone` sets `users.tombstoned = TRUE`.
- Step 7 of validation now returns 410 Gone for **all** tokens with that `sub`,
  regardless of `jti`.
- Used during panic wipe — see flow 9 in [data_flows.md](data_flows.md).

### 8c. Key rotation (system-wide revocation)
- The Signal relay rotates its RS256 signing key by publishing a new `kid`
  in JWKS and signing all new tokens with it.
- During the rotation window (24h, matching JWT TTL), both old and new
  public keys remain in JWKS — old tokens still validate.
- After 24h, the old key is removed; any remaining old token fails validation.
- Used only in the case of a confirmed signing-key compromise. Forces every
  operator to re-attest within 24h.

---

## 9. WebSocket authentication

The Android client sends the JWT as the **first text frame** after opening
the socket (see [SignalServerClient.kt:231-235](app/src/main/java/com/explo/capstone/transport/SignalServerClient.kt#L231-L235)):

```json
{ "type": "auth", "token": "<jwt>" }
```

The server (implemented in [server/index.js](server/index.js) `setupWebSocket`)
handles this via `ws.once('message', ...)`:
1. Validates the first frame is `{type:"auth", token:"..."}`.
2. Verifies the JWT with HS256.
3. Checks the user is not tombstoned.
4. On success: registers the connection, flushes queued messages.
5. On failure: closes with code `4001` (invalid token) or `4410` (tombstoned).

**Previous bug (now fixed):** the original server read the token from `?token=`
query param, but the client never sends one. The first-frame approach is also
more secure since query-param tokens appear in server access logs.

Server-side timeout: socket closed with `4001` if no auth frame arrives
within 5 seconds.

---

## 10. Threats addressed

| Threat | Mitigation |
|---|---|
| Token theft from disk | EncryptedSharedPreferences (AES-256-GCM, key in Keystore) |
| Token theft from memory dump | Token only in process memory while app foregrounded; daily TTL |
| Replay across devices | `device_id` claim + server logs flag mismatches as ALERT |
| Forged tokens | RS256 signature; private key only on the Signal relay |
| Stolen device | 24h TTL + re-attestation requires Keystore key (non-exportable) |
| Compromised user | Tombstone returns 410 across all tokens for that `sub` |
| Compromised signing key | Key rotation via `kid` |

Threats **not** mitigated here (covered elsewhere):
- Compromise of the Signal relay process itself → see [threat_model.md](threat_model.md)
- Plaintext leak via UI screenshot → device-policy concern, out of scope

---

## 11. Open questions

- **JWKS endpoint hosting** — does the REST API fetch JWKS once at boot or
  re-fetch on every `kid` mismatch? Default: cache for 1h, refresh on miss.
- **Clock skew tolerance** — RFC 7519 allows ±60s leeway. Default: 30s.
- **Refresh UX** — should re-attestation be silent (just verify device is unlocked)
  or require a biometric prompt? Default: biometric prompt if available, fall
  back to device-credential.
- **Multi-device in the future** — current spec assumes one device per user.
  Adding a second device would require either a "linked device" Signal flow
  or per-device JWTs with an additional `device_id` index.
