# PQXDH + Kyber-1024 Implementation Plan (libsignal 0.93.0)

**Goal:** Upgrade AstraSecure from X3DH (EC-only) to PQXDH (Kyber-1024 + Curve25519). Outcome: end-to-end encrypted messages successfully sent and received using libsignal 0.93.0's hybrid post-quantum handshake, with a single rotated Kyber pre-key per user.

**Approach:** One Kyber-1024 pre-key per user, rotated weekly alongside the SPK (signed-pre-key-style, not one-time-use). Simplest viable PQXDH. Can be extended to per-session one-time Kyber pre-keys later.

**Scope:** 9 files modified across server + client. No new files needed.

---

## Pre-Flight: Verify Current State

Before starting, confirm:
- [ ] `gradle/libs.versions.toml` has `libsignalAndroid = "0.93.0"` (revert if necessary — Kyber was added in libsignal v0.28 so 0.86.5 also has it, but pin to 0.93.0 for this plan)
- [ ] Server runs locally (e.g. `npm start` in `server/`) so schema mutations take effect on next boot
- [ ] You have two Android devices/emulators to test send/receive

If `libs.versions.toml` shows anything other than 0.93.0:

```toml
# gradle/libs.versions.toml — line 24
libsignalAndroid = "0.93.0"
```

---

## Step 1 — Server schema additions (server/schema.sql)

**File:** `server/schema.sql`

**Action:** Append a new table after the existing `one_time_prekeys` block (insert after the `CREATE INDEX IF NOT EXISTS idx_opk_user ON one_time_prekeys(user_id);` line, before the `message_queue` table). Schema is loaded on every server boot via `initSchema()` and `CREATE TABLE IF NOT EXISTS` makes it idempotent.

```sql
-- Kyber-1024 KEM pre-key (PQXDH). One signed key per user, rotated weekly with SPK.
-- public_key is base64-encoded KEMPublicKey.serialize() bytes (~1568 raw bytes → ~2092 b64 chars).
-- signature is base64-encoded Ed25519 signature over public_key bytes, computed by the
-- user's IdentityKeyPair private key.
CREATE TABLE IF NOT EXISTS kyber_prekeys (
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    key_id             INTEGER     NOT NULL,
    public_key         TEXT        NOT NULL,
    signature          TEXT        NOT NULL,
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id)
);
```

**Why `PRIMARY KEY (user_id)` not `(user_id, key_id)`:** We're using the rotated-key (SPK-style) model — exactly one active Kyber key per user at a time. `INSERT ... ON CONFLICT (user_id) DO UPDATE` overwrites on rotation.

**Validation:** After server restart, `\d kyber_prekeys` in `psql` should show the table.

---

## Step 2 — Server endpoint additions (server/index.js)

**File:** `server/index.js`

### 2a. Modify `POST /v1/users` to accept `kyberPreKey`

Locate the existing `POST /v1/users` handler (around line 299). The destructure currently reads:

```js
const { userId, displayName, registrationId, identityKey, signedPreKey, oneTimePreKeys } = req.body;
```

Change to:

```js
const { userId, displayName, registrationId, identityKey, signedPreKey, oneTimePreKeys, kyberPreKey } = req.body;
```

Add validation alongside the existing `signedPreKey` check (after the existing `if (!signedPreKey.id...)` block):

```js
if (!kyberPreKey || !kyberPreKey.id || !kyberPreKey.publicKey || !kyberPreKey.signature) {
  return res.status(400).json({ error: 'Invalid kyberPreKey' });
}
```

Inside the transaction, after the `signed_prekeys` INSERT and before the `one_time_prekeys` loop, add:

```js
await client.query(
  `INSERT INTO kyber_prekeys (user_id, key_id, public_key, signature)
   VALUES ($1, $2, $3, $4)
   ON CONFLICT (user_id) DO UPDATE SET
     key_id     = EXCLUDED.key_id,
     public_key = EXCLUDED.public_key,
     signature  = EXCLUDED.signature`,
  [userId, kyberPreKey.id, kyberPreKey.publicKey, kyberPreKey.signature],
);
```

### 2b. Modify `GET /v1/keys/:userId` to return `kyberPreKey`

Locate the handler (around line 395). Replace the JOINed SELECT query to also pull the Kyber key. Change the first query from:

```js
const userRows = await pool.query(
  `SELECT u.registration_id, u.identity_key,
          s.spk_id, s.spk_public, s.spk_signature
   FROM users u
   JOIN signed_prekeys s ON s.user_id = u.user_id
   WHERE u.user_id = $1
   ORDER BY s.spk_id DESC
   LIMIT 1`,
  [userId],
);
```

To:

```js
const userRows = await pool.query(
  `SELECT u.registration_id, u.identity_key,
          s.spk_id, s.spk_public, s.spk_signature,
          k.key_id AS kyber_id, k.public_key AS kyber_public, k.signature AS kyber_signature
   FROM users u
   JOIN signed_prekeys s ON s.user_id = u.user_id
   JOIN kyber_prekeys  k ON k.user_id = u.user_id
   WHERE u.user_id = $1
   ORDER BY s.spk_id DESC
   LIMIT 1`,
  [userId],
);
```

**Note:** This uses an INNER JOIN on `kyber_prekeys` — so users registered before this migration won't appear in bundle lookups until they re-register with a Kyber key. That's the intended behavior (PQXDH is mandatory after upgrade).

Update the response JSON (around line 437) to add `kyberPreKey`:

```js
res.json({
  userId,
  registrationId: u.registration_id,
  identityKey:    u.identity_key,
  signedPreKey: {
    id:        u.spk_id,
    publicKey: u.spk_public,
    signature: u.spk_signature,
  },
  oneTimePreKey: opk ? { id: opk.opk_id, publicKey: opk.opk_public } : null,
  kyberPreKey: {
    id:        u.kyber_id,
    publicKey: u.kyber_public,
    signature: u.kyber_signature,
  },
});
```

### 2c. Add `PUT /v1/keys/kyber` rotation endpoint

Insert this handler immediately after the existing `PUT /v1/keys/signed` handler (around line 500):

```js
// ─── PUT /v1/keys/kyber — Upload/rotate Kyber pre-key ────────────────────────

app.put('/v1/keys/kyber', auth, async (req, res) => {
  const { id, publicKey, signature } = req.body;
  if (!id || !publicKey || !signature) {
    return res.status(400).json({ error: 'Missing id, publicKey, or signature' });
  }
  try {
    await pool.query(
      `INSERT INTO kyber_prekeys (user_id, key_id, public_key, signature)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id) DO UPDATE SET
         key_id     = EXCLUDED.key_id,
         public_key = EXCLUDED.public_key,
         signature  = EXCLUDED.signature`,
      [req.user.userId, id, publicKey, signature],
    );
    res.json({ ok: true });
  } catch (e) {
    console.error('[uploadKyber] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});
```

### 2d. Update `DELETE /v1/users/:userId` to wipe Kyber keys

Inside the `DELETE /v1/users/:userId` handler's transaction (around line 376), add one line in the cleanup sequence:

```js
await client.query('DELETE FROM one_time_prekeys WHERE user_id = $1', [userId]);
await client.query('DELETE FROM signed_prekeys WHERE user_id = $1', [userId]);
await client.query('DELETE FROM kyber_prekeys WHERE user_id = $1', [userId]);   // NEW
await client.query('DELETE FROM message_queue WHERE recipient_id = $1', [userId]);
await client.query('DELETE FROM channel_members WHERE user_id = $1', [userId]);
```

**Validation:** Restart server. Hit `GET /v1/keys/:userId` for a user who hasn't yet uploaded a Kyber key — should return 404 because of the INNER JOIN. After registration includes Kyber, should return a bundle with `kyberPreKey` field.

---

## Step 3 — Client transport DTOs (TransportMessage.kt)

**File:** `app/src/main/java/com/explo/capstone/transport/TransportMessage.kt`

**Action:** Add a new DTO and extend two existing ones.

### 3a. Add KyberPreKeyDto

Insert after the existing `SignedPreKeyDto` class (around line 49):

```kotlin
data class KyberPreKeyDto(
    val id: Int,
    val publicKey: String,             // base64 — Kyber-1024 public key (~1568 raw bytes)
    val signature: String,             // base64 — Ed25519 signature over publicKey
)
```

### 3b. Extend RegisterRequest

Find the existing `RegisterRequest` data class (around line 27). Add `kyberPreKey` as the last field:

```kotlin
data class RegisterRequest(
    val userId: String,
    val displayName: String,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val oneTimePreKeys: List<PreKeyDto>,
    val kyberPreKey: KyberPreKeyDto,        // NEW — required for PQXDH
)
```

### 3c. Extend PreKeyBundleResponse

Find `PreKeyBundleResponse` (around line 51). Add `kyberPreKey` as the last field:

```kotlin
data class PreKeyBundleResponse(
    val userId: String,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val oneTimePreKey: PreKeyDto?,
    val kyberPreKey: KyberPreKeyDto,        // NEW — required, never null for PQXDH bundles
)
```

**Note:** `kyberPreKey` is non-null. With the server-side INNER JOIN, every successful bundle fetch will include it.

---

## Step 4 — MessageTransport interface (MessageTransport.kt)

**File:** `app/src/main/java/com/explo/capstone/transport/MessageTransport.kt`

### 4a. Extend register() signature

Find the existing `register()` declaration (line 60). Add three Kyber parameters at the end:

```kotlin
suspend fun register(
    userId: String,
    displayName: String,
    registrationId: Int,
    identityKeyBytes: ByteArray,
    signedPreKeyId: Int,
    signedPreKeyPublicBytes: ByteArray,
    signedPreKeySignature: ByteArray,
    oneTimePreKeys: List<Pair<Int, ByteArray>>,
    kyberPreKeyId: Int,                     // NEW
    kyberPreKeyPublicBytes: ByteArray,      // NEW
    kyberPreKeySignature: ByteArray,        // NEW
): Result<String>
```

### 4b. Add uploadKyberPreKey()

Insert after the existing `uploadSignedPreKey()` declaration:

```kotlin
/** Upload a new Kyber pre-key (called during weekly rotation). */
suspend fun uploadKyberPreKey(
    id: Int,
    publicKeyBytes: ByteArray,
    signature: ByteArray,
): Result<Unit>
```

---

## Step 5 — SignalServerClient (SignalServerClient.kt)

**File:** `app/src/main/java/com/explo/capstone/transport/SignalServerClient.kt`

### 5a. Add Retrofit endpoint for Kyber upload

Inside the `private interface SignalApi` block (around line 28), add after the existing `uploadSignedPreKey`:

```kotlin
@PUT("v1/keys/kyber")
suspend fun uploadKyberPreKey(@Body dto: KyberPreKeyDto)
```

### 5b. Update register() implementation

Find `override suspend fun register(...)` (around line 128). Update the signature to match the new interface (add the three Kyber params) and update the body to pass them through:

```kotlin
override suspend fun register(
    userId: String,
    displayName: String,
    registrationId: Int,
    identityKeyBytes: ByteArray,
    signedPreKeyId: Int,
    signedPreKeyPublicBytes: ByteArray,
    signedPreKeySignature: ByteArray,
    oneTimePreKeys: List<Pair<Int, ByteArray>>,
    kyberPreKeyId: Int,
    kyberPreKeyPublicBytes: ByteArray,
    kyberPreKeySignature: ByteArray,
): Result<String> = runCatching {
    if (serverUrl.isBlank()) return Result.failure(IllegalStateException("No server"))
    val response = api.register(
        RegisterRequest(
            userId = userId,
            displayName = displayName,
            registrationId = registrationId,
            identityKey = b64(identityKeyBytes),
            signedPreKey = SignedPreKeyDto(
                id = signedPreKeyId,
                publicKey = b64(signedPreKeyPublicBytes),
                signature = b64(signedPreKeySignature),
            ),
            oneTimePreKeys = oneTimePreKeys.map { (id, pub) -> PreKeyDto(id, b64(pub)) },
            kyberPreKey = KyberPreKeyDto(
                id = kyberPreKeyId,
                publicKey = b64(kyberPreKeyPublicBytes),
                signature = b64(kyberPreKeySignature),
            ),
        )
    )
    localUserId = userId
    jwt = response.token
    onJwtSaved(jwt)
    _connectionState.value = true
    response.token
}.onFailure { Log.w(TAG, "register failed: ${it.message}") }
```

### 5c. Add uploadKyberPreKey() implementation

Insert after `uploadSignedPreKey()` (around line 173):

```kotlin
override suspend fun uploadKyberPreKey(id: Int, publicKeyBytes: ByteArray, signature: ByteArray): Result<Unit> =
    runCatching {
        api.uploadKyberPreKey(KyberPreKeyDto(id, b64(publicKeyBytes), b64(signature)))
    }.onFailure { Log.w(TAG, "uploadKyberPreKey failed: ${it.message}") }
```

---

## Step 6 — AstraSignalProtocolStore Kyber persistence (AstraSignalProtocolStore.kt)

**File:** `app/src/main/java/com/explo/capstone/crypto/signal/AstraSignalProtocolStore.kt`

**Action:** Replace the four stub `Kyber*` methods at the bottom (around line 229-245) with real persisted implementations following the same `EncryptedSharedPreferences` pattern as `PreKeyStore`.

### 6a. Add import

Already imported via `import org.signal.libsignal.protocol.state.*` (which covers `KyberPreKeyRecord`). No new imports needed.

### 6b. Replace the KyberPreKeyStore section

Replace this entire block (currently lines 229-245):

```kotlin
    // ─── KyberPreKeyStore ────────────────────────────────────────────────────

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        throw org.signal.libsignal.protocol.InvalidKeyIdException("Kyber not implemented")
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = emptyList()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        // No-op for now
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = false

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, preKeyId: Int, baseKey: org.signal.libsignal.protocol.ecc.ECPublicKey) {
        // No-op
    }
```

With:

```kotlin
    // ─── KyberPreKeyStore ─────────────────────────────────────────────────────
    // Backed by EncryptedSharedPreferences entries keyed "signal_kyber_{id}".

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val raw = prefs.getString("signal_kyber_$kyberPreKeyId", null)
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No kyber pre-key $kyberPreKeyId")
        return KyberPreKeyRecord(decode(raw))
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        prefs.all.entries
            .filter { it.key.startsWith("signal_kyber_") }
            .mapNotNull { (_, v) -> runCatching { KyberPreKeyRecord(decode(v as String)) }.getOrNull() }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        prefs.edit().putString("signal_kyber_$kyberPreKeyId", encode(record.serialize())).apply()
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        prefs.contains("signal_kyber_$kyberPreKeyId")

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        preKeyId: Int,
        baseKey: org.signal.libsignal.protocol.ecc.ECPublicKey,
    ) {
        // For the rotated-key model (one signed Kyber key per user), this is a no-op.
        // libsignal calls this after consuming a one-time Kyber key, but our keys are
        // long-lived and rotated by SignalKeyManager.rotateKyberPreKey() on the weekly cadence.
    }

    fun removeKyberPreKey(id: Int) {
        prefs.edit().remove("signal_kyber_$id").apply()
    }
```

### 6c. Update wipeAll() coverage check

The existing `wipeAll()` (around line 39) already filters by `it.startsWith("signal_")` so it will sweep `signal_kyber_*` keys automatically. No change needed — but verify the filter is unchanged.

---

## Step 7 — SignalKeyManager Kyber generation & rotation (SignalKeyManager.kt)

**File:** `app/src/main/java/com/explo/capstone/crypto/signal/SignalKeyManager.kt`

### 7a. Add imports

At the top, alongside the existing libsignal imports:

```kotlin
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
```

### 7b. Add constants

Inside the `companion object` block (around line 29), add:

```kotlin
private const val INITIAL_KYBER_PREKEY_ID = 1
private const val KYBER_ROTATION_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
```

### 7c. Add a Kyber generator function

Add a new private helper at the bottom of the class (after `generatePreKeys()`, around line 204):

```kotlin
private fun generateKyberPreKey(
    identityKeyPair: IdentityKeyPair,
    id: Int,
): KyberPreKeyRecord {
    val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
    val signature = identityKeyPair.privateKey.calculateSignature(
        keyPair.publicKey.serialize(),
    )
    return KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
}
```

### 7d. Modify provision() to generate and upload Kyber key

Find `provision()` (around line 44). After the existing step 3 (one-time pre-keys generation, line 62-63), insert step 3.5:

```kotlin
        // 3.5. Kyber-1024 pre-key (PQXDH — rotated weekly like SPK)
        val kyberPreKey = generateKyberPreKey(identityKeyPair, INITIAL_KYBER_PREKEY_ID)
        store.storeKyberPreKey(kyberPreKey.id, kyberPreKey)
        store.saveLastKyberRotationMs(System.currentTimeMillis())
```

Then modify the existing `transport.register(...)` call to include the new Kyber arguments. Change the call from its current form to:

```kotlin
        val result = transport.register(
            userId = userId,
            displayName = displayName,
            registrationId = registrationId,
            identityKeyBytes = identityKeyPair.publicKey.serialize(),
            signedPreKeyId = signedPreKey.id,
            signedPreKeyPublicBytes = signedPreKey.keyPair.publicKey.serialize(),
            signedPreKeySignature = signedPreKey.signature,
            oneTimePreKeys = oneTimePreKeys.map { it.id to it.keyPair.publicKey.serialize() },
            kyberPreKeyId = kyberPreKey.id,
            kyberPreKeyPublicBytes = kyberPreKey.keyPair.publicKey.serialize(),
            kyberPreKeySignature = kyberPreKey.signature,
        )
```

Also update the log line to mention Kyber:

```kotlin
        Log.i(TAG, "Signal keys generated — registrationId=$registrationId, ${oneTimePreKeys.size} OPKs, Kyber id=${kyberPreKey.id}")
```

### 7e. Modify retryServerRegistration() to send existing Kyber key

Find `retryServerRegistration()` (around line 91). After loading the SPK, also load the existing Kyber key:

```kotlin
        val kyber = store.loadKyberPreKeys().maxByOrNull { it.id }
            ?: throw IllegalStateException("No Kyber pre-key found in store")
```

Then update the `transport.register(...)` call inside retryServerRegistration to include the Kyber arguments (same shape as above):

```kotlin
        val result = transport.register(
            userId = userId,
            displayName = displayName,
            registrationId = registrationId,
            identityKeyBytes = identityKeyPair.publicKey.serialize(),
            signedPreKeyId = spk.id,
            signedPreKeyPublicBytes = spk.keyPair.publicKey.serialize(),
            signedPreKeySignature = spk.signature,
            oneTimePreKeys = opks.map { it.id to it.keyPair.publicKey.serialize() },
            kyberPreKeyId = kyber.id,
            kyberPreKeyPublicBytes = kyber.keyPair.publicKey.serialize(),
            kyberPreKeySignature = kyber.signature,
        )
```

### 7f. Add rotation functions

Insert after the existing `rotateSignedPreKeyIfNeeded()` (around line 181):

```kotlin
/**
 * Rotate the Kyber pre-key. Should be called every 7 days, in lockstep with SPK rotation.
 * The previous Kyber key is retained for 48 hours so in-flight PQXDH handshakes
 * encrypted to the old key can still complete.
 */
suspend fun rotateKyberPreKey() {
    val currentIds = store.loadKyberPreKeys().map { it.id }
    val newId = (currentIds.maxOrNull() ?: 0) + 1
    val identityKeyPair = store.getIdentityKeyPair()
    val newKyber = generateKyberPreKey(identityKeyPair, newId)

    store.storeKyberPreKey(newKyber.id, newKyber)
    store.saveLastKyberRotationMs(System.currentTimeMillis())

    transport.uploadKyberPreKey(
        id = newKyber.id,
        publicKeyBytes = newKyber.keyPair.publicKey.serialize(),
        signature = newKyber.signature,
    ).onFailure { Log.w(TAG, "Kyber rotation upload failed: ${it.message}") }

    // Drop old Kyber keys after 48 hours grace period
    val cutoff = System.currentTimeMillis() - 48L * 60 * 60 * 1000
    store.loadKyberPreKeys()
        .filter { it.id != newKyber.id && it.timestamp < cutoff }
        .forEach { store.removeKyberPreKey(it.id) }

    Log.i(TAG, "Kyber pre-key rotated — new id=$newId")
}

/**
 * Rotate the Kyber pre-key only if more than 7 days have elapsed since the last rotation.
 * Safe to call on every app foreground.
 */
suspend fun rotateKyberPreKeyIfNeeded() {
    val lastRotated = store.getLastKyberRotationMs()
    val now = System.currentTimeMillis()
    if (now - lastRotated >= KYBER_ROTATION_INTERVAL_MS) {
        Log.i(TAG, "Kyber rotation due — rotating")
        rotateKyberPreKey()
    } else {
        val daysLeft = (KYBER_ROTATION_INTERVAL_MS - (now - lastRotated)) / (24 * 60 * 60 * 1000)
        Log.d(TAG, "Kyber rotation not due yet ($daysLeft days remaining)")
    }
}
```

### 7g. Add Kyber rotation timestamp to AstraSignalProtocolStore

Back in `AstraSignalProtocolStore.kt`, near the existing `getLastSpkRotationMs()` / `saveLastSpkRotationMs()` pair (around line 178), add:

```kotlin
fun getLastKyberRotationMs(): Long = prefs.getLong("signal_kyber_last_rotated_ms", 0L)

fun saveLastKyberRotationMs(ms: Long) {
    prefs.edit().putLong("signal_kyber_last_rotated_ms", ms).apply()
}
```

---

## Step 8 — SignalCryptoEngine: build PreKeyBundle with Kyber (SignalCryptoEngine.kt)

**File:** `app/src/main/java/com/explo/capstone/crypto/signal/SignalCryptoEngine.kt`

### 8a. Verify imports

The file should already have:
```kotlin
import org.signal.libsignal.protocol.kem.KEMPublicKey
```
If missing, add it.

### 8b. Rewrite toPreKeyBundle() to use the 11-param constructor with the server's Kyber key

Replace the entire `toPreKeyBundle()` function (currently around lines 175-199):

```kotlin
private fun PreKeyBundleResponse.toPreKeyBundle(): PreKeyBundle {
    val identityKeyBytes  = Base64.decode(identityKey, Base64.DEFAULT)
    val spkPublicBytes    = Base64.decode(signedPreKey.publicKey, Base64.DEFAULT)
    val spkSignatureBytes = Base64.decode(signedPreKey.signature, Base64.DEFAULT)

    // Use 0 as the sentinel when no OPK is present — libsignal validates OPK ID
    // (as u32) only when opkPublic is non-null. -1 would overflow the u32 cast.
    val opkId     = oneTimePreKey?.id ?: 0
    val opkPublic = oneTimePreKey?.let { ECPublicKey(Base64.decode(it.publicKey, Base64.DEFAULT)) }

    // Kyber-1024 KEM pre-key (PQXDH). Always present — server INNER JOINs kyber_prekeys
    // so a missing key produces a 404 before we get here.
    val kyberPublicBytes    = Base64.decode(kyberPreKey.publicKey, Base64.DEFAULT)
    val kyberSignatureBytes = Base64.decode(kyberPreKey.signature, Base64.DEFAULT)
    val kyberKey: KEMPublicKey = KEMPublicKey(kyberPublicBytes)

    return PreKeyBundle(
        registrationId,
        DEVICE_ID,
        opkId,
        opkPublic,
        signedPreKey.id,
        ECPublicKey(spkPublicBytes),
        spkSignatureBytes,
        IdentityKey(identityKeyBytes),
        kyberPreKey.id,
        kyberKey,
        kyberSignatureBytes,
    )
}
```

**Why this compiles where the previous attempts didn't:**
- The 11-param constructor is the only one in libsignal 0.93.0 (the 8-param EC-only overload was removed).
- `kyberKey` is `KEMPublicKey` (non-null) — matches the constructor's declared type exactly. No Kotlin/Java nullability mismatch.
- `kyberPreKey.id` is a real positive integer (not `NULL_PRE_KEY_ID` / -1), so the Rust JNI's u32 validation passes.

---

## Step 9 — Wire Kyber rotation into AppContainer

**File:** `app/src/main/java/com/explo/capstone/shared/AppContainer.kt`

Find the existing SPK rotation hook (around line 128):

```kotlin
// Check SPK age on start; rotate if it has been > 7 days
incomingScope.launch {
    if (signalStore.isProvisioned()) {
        runCatching { signalKeyManager.rotateSignedPreKeyIfNeeded() }
            .onFailure { Log.w("AppContainer", "SPK rotation check failed: ${it.message}") }
    }
}
```

Replace it with:

```kotlin
// Check SPK + Kyber age on start; rotate if it has been > 7 days
incomingScope.launch {
    if (signalStore.isProvisioned()) {
        runCatching { signalKeyManager.rotateSignedPreKeyIfNeeded() }
            .onFailure { Log.w("AppContainer", "SPK rotation check failed: ${it.message}") }
        runCatching { signalKeyManager.rotateKyberPreKeyIfNeeded() }
            .onFailure { Log.w("AppContainer", "Kyber rotation check failed: ${it.message}") }
    }
}
```

---

## Step 10 — Build & Test Sequence

### 10a. Wipe stale state

Because the on-device EncryptedSharedPreferences contain identities provisioned without Kyber keys, and the server contains user rows without Kyber keys, both sides must be reset on first run:

1. **Server:** drop and recreate the database (or just `TRUNCATE users CASCADE; TRUNCATE message_queue;`) — this also clears `signed_prekeys`, `one_time_prekeys`, `channel_members` via FK cascade.
2. **Each device:** uninstall the app (`adb uninstall com.explo.capstone`) so EncryptedSharedPreferences is wiped.

### 10b. Build

```
./gradlew :app:assembleDebug
```

Expected: success. No compiler errors. If the build fails on `SignalCryptoEngine.kt`, the most likely cause is a missing import — confirm `KEMPublicKey` is imported.

### 10c. Manual test flow (two devices, A and B)

1. Install fresh on device A → run through provisioning → confirm logcat shows `Signal keys generated — ... Kyber id=1` and `Registered with Signal server successfully`.
2. Install fresh on device B → same as above.
3. On the server DB: `SELECT user_id, key_id FROM kyber_prekeys;` should show two rows.
4. On device A: create a mission, create a channel inside that mission, send an invite QR to device B.
5. On device B: scan the QR, redeem the invite.
6. On device A (CHIEF): in Admin Console, assign device B's user a rank that meets the channel's clearance.
7. On device A: open the channel chat and send a message ("hello").
8. **Expected logcat on device A:**
   - `SKDM sent to 1 new member(s) in <channelId>`
   - **No** "integer overflow during conversion of -1 to u32" — this confirms the Kyber bundle is properly formed.
9. **Expected logcat on device B:**
   - `Processed SKDM from <senderA-id>`
   - The "hello" message appears in the chat UI without `INTEGRITY_FAIL`.
10. Reply from B → confirm A also receives.

### 10d. Diagnostic SQL for debugging

```sql
-- Verify a user has all key material
SELECT u.user_id,
       (SELECT COUNT(*) FROM signed_prekeys s WHERE s.user_id = u.user_id) AS spks,
       (SELECT COUNT(*) FROM one_time_prekeys o WHERE o.user_id = u.user_id) AS opks,
       (SELECT COUNT(*) FROM kyber_prekeys k WHERE k.user_id = u.user_id) AS kyber
FROM users u;
```

Every row should have `spks=1`, `opks > 0`, `kyber=1`. A `kyber=0` row means provisioning happened before this upgrade — wipe and re-provision that device.

---

## Common Failure Modes & Fixes

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Null cannot be a value of a non-null type 'KEMPublicKey'` at compile time | Trying to pass literal `null` to the 11-param constructor without an explicit `KEMPublicKey?` type | We're not passing null in this plan — `kyberKey` is built from real bytes |
| `None of the following candidates is applicable` at compile time | Tried to use an 8-param `PreKeyBundle` constructor (removed in 0.93.0) | Use the 11-param constructor as shown in Step 8 |
| `integer overflow during conversion of -1 to u32` at runtime during `SessionBuilder.process()` | Passing `NULL_PRE_KEY_ID` (-1) alongside a non-null `KEMPublicKey` | We never use NULL_PRE_KEY_ID for the Kyber slot — `kyberPreKey.id` is always ≥ 1 |
| 404 on `GET /v1/keys/:userId` | The recipient registered without a Kyber key (e.g. provisioned before this upgrade) | Wipe and re-provision that device |
| `SKDM delivery failed for ...` followed by `No such kyber pre key record` on receiver | The Kyber key from the server doesn't match the recipient's local store | Receiver was wiped/reinstalled but server still has the old Kyber row — wipe the server-side `kyber_prekeys` row for that user OR re-provision so it overwrites |
| `INTEGRITY_FAIL` on channel message decrypt | Unrelated to Kyber — caused by `metadataProcessor.padMessage(encrypted)` after `GroupCipher.encrypt()` (corrupts MAC) | Confirm no padding is applied to the post-`GroupCipher` ciphertext in `MessageRepository.send()` |

---

## Future Extensions (out of scope for this plan)

- **One-time Kyber pre-keys:** add a second table `one_time_kyber_prekeys` consumed on bundle fetch, like OPKs. Requires changes to how `markKyberPreKeyUsed()` is implemented (currently no-op).
- **Kyber key signature verification on receiver side:** libsignal already does this internally during `SessionBuilder.process()` — verifies the Kyber pre-key's Ed25519 signature against the sender's identity key. No additional work needed.
- **Migration path for pre-PQXDH users:** the current plan requires a wipe. A non-destructive migration would add a `/v1/keys/kyber/lazy-provision` endpoint and have clients upload their Kyber key on first app launch after the update.

---

## Summary of Files Modified

| File | What changes |
|------|---|
| `server/schema.sql` | + `kyber_prekeys` table |
| `server/index.js` | Modify `POST /v1/users`, `GET /v1/keys/:userId`, `DELETE /v1/users/:userId`; add `PUT /v1/keys/kyber` |
| `gradle/libs.versions.toml` | Pin `libsignalAndroid = "0.93.0"` |
| `transport/TransportMessage.kt` | + `KyberPreKeyDto`; extend `RegisterRequest`, `PreKeyBundleResponse` |
| `transport/MessageTransport.kt` | Extend `register()`; add `uploadKyberPreKey()` |
| `transport/SignalServerClient.kt` | Implement new transport methods + Retrofit endpoint |
| `crypto/signal/AstraSignalProtocolStore.kt` | Real `KyberPreKeyStore` implementation; rotation timestamp accessors |
| `crypto/signal/SignalKeyManager.kt` | Generate Kyber in `provision()` + `retryServerRegistration()`; add `rotateKyberPreKey()` / `rotateKyberPreKeyIfNeeded()` |
| `crypto/signal/SignalCryptoEngine.kt` | `toPreKeyBundle()` builds the 11-param `PreKeyBundle` with real Kyber key |
| `shared/AppContainer.kt` | Call `rotateKyberPreKeyIfNeeded()` on startup |

**Total: 9 files. Zero new files. Build + 2-device manual test confirms success.**
