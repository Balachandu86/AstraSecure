# Signal Protocol Cryptography

Two algorithms power Signal Protocol: **X3DH** (session bootstrap) and **Double Ratchet** (per-message encryption). For multi-user channels, **Sender Keys** sit on top. All three are provided by `libsignal-android 0.58.2`, which is already declared in the project's build.

---

## Part 1 — X3DH (Extended Triple Diffie-Hellman)

X3DH solves the problem of establishing a shared secret between two parties when they are not online simultaneously.

### Keys each user maintains

| Key | Type | Lifetime | Where stored |
|---|---|---|---|
| Identity Key (IK) | EC key pair (Curve25519) | Permanent | Android Keystore (hardware-backed) |
| Signed Pre-Key (SPK) | EC key pair (Curve25519) | ~7 days, then rotated | EncryptedSharedPreferences |
| One-Time Pre-Key (OPK) | EC key pair (Curve25519) | Single use, then deleted | EncryptedSharedPreferences (batch of 100) |

All public components of these keys are uploaded to the server. Private components never leave the device.

### Session establishment — Alice sends to Bob for the first time

**Alice fetches Bob's pre-key bundle from server:**
```
IK_B_pub        Bob's identity key (public)
SPK_B_pub       Bob's signed pre-key (public)
SPK_B_sig       Bob's IK_B signature over SPK_B_pub
OPK_B_pub       One-time pre-key (public) — may be absent if exhausted
```

**Alice generates an ephemeral key pair:**
```
EK_A = Curve25519.generateKeyPair()
```

**Alice verifies SPK_B_sig against IK_B_pub.** If invalid, abort — this detects a server-side MITM.

**Alice computes four DH values:**
```
DH1 = DH(IK_A_priv,  SPK_B_pub)     -- mutual auth: IK_A × SPK_B
DH2 = DH(EK_A_priv,  IK_B_pub)      -- forward secrecy: EK_A × IK_B
DH3 = DH(EK_A_priv,  SPK_B_pub)     -- ephemeral × medium-term
DH4 = DH(EK_A_priv,  OPK_B_pub)     -- ephemeral × one-time (if OPK present)
```

**Shared secret:**
```
SK = HKDF(DH1 || DH2 || DH3 || DH4, salt="AstraSecure X3DH v1", info="session")
```

`SK` becomes the root key of the Double Ratchet session.

**Alice's first message includes (in the PreKeySignalMessage header):**
- `IK_A_pub` — so Bob can recompute DH1, DH2
- `EK_A_pub` — so Bob can recompute DH2, DH3, DH4
- `SPK_B_id` and `OPK_B_id` — so Bob knows which private keys to use

**Bob receives the first message and computes the same DH values (mirrored roles):**
```
DH1 = DH(SPK_B_priv, IK_A_pub)
DH2 = DH(IK_B_priv,  EK_A_pub)
DH3 = DH(SPK_B_priv, EK_A_pub)
DH4 = DH(OPK_B_priv, EK_A_pub)      -- uses the OPK that was consumed
```

Both sides independently arrive at the same `SK`. No secret material was transmitted.

---

## Part 2 — Double Ratchet Algorithm

After X3DH, both parties have the same root key. The Double Ratchet uses this to provide:
- **Forward secrecy** — past message keys are deleted after use; compromising today's key does not expose yesterday's messages
- **Post-compromise security** — the DH ratchet advances with every reply; a compromised key heals itself after the next DH step

### Two ratchets

**Symmetric-key ratchet (KDF chain):** Runs on every message. Each message key is derived from the chain key and immediately discarded. This provides forward secrecy within a sending chain.

```
chain_key[n+1], message_key[n] = KDF(chain_key[n])
message is encrypted with message_key[n]
message_key[n] is deleted immediately after use
```

**DH ratchet:** Advances whenever the direction of the conversation reverses (i.e., Alice sends, Bob replies). Each reply includes a new DH public key. Both parties ratchet the root key:

```
root_key[n+1], chain_key = KDF(root_key[n], DH(new_key_A, new_key_B))
```

This produces a fresh chain key from new ephemeral material, providing post-compromise security.

### libsignal-android classes

```kotlin
// Session state per remote address
val address = SignalProtocolAddress(userId, deviceId = 1)

// Build a session from a fetched pre-key bundle (first contact only)
val bundle = PreKeyBundle(
    registrationId, deviceId,
    preKeyId, preKeyPublic,
    signedPreKeyId, signedPreKeyPublic, signedPreKeySignature,
    identityKey
)
SessionBuilder(signalStore, address).process(bundle)

// Encrypt (after session is established)
val ciphertext: CiphertextMessage = SessionCipher(signalStore, address)
    .encrypt(plaintext)
// ciphertext.type == CiphertextMessage.PREKEY_TYPE (first msg) or WHISPER_TYPE (subsequent)
// ciphertext.serialize() → ByteArray to send

// Decrypt
val cipher = SessionCipher(signalStore, address)
val plaintext: ByteArray = when (message.messageType) {
    1 -> cipher.decrypt(PreKeySignalMessage(ciphertext))
    2 -> cipher.decrypt(SignalMessage(ciphertext))
    else -> throw IllegalArgumentException("Unknown message type")
}
```

---

## Part 3 — Sender Keys (channel/group messaging)

Signal's solution for group channels. Instead of Alice encrypting N times (one per member), each sender maintains a single ratcheting chain per channel. Members hold Alice's sender key and can decrypt any of her channel messages.

### Why Sender Keys for AstraSecure channels

AstraSecure channels have multiple senders. 1:1 sessions would require per-recipient encryption (O(N) encryptions per send). Sender Keys reduce this to O(1) encrypt + O(N) initial distribution.

### Setup — Alice joins a channel

1. Alice generates a `SenderKeyName(channelId, aliceAddress)` — unique per (channel, sender)
2. Alice creates a new sender key chain:
```kotlin
val skdm: SenderKeyDistributionMessage = SenderKeyGroupCipher(signalStore, senderKeyName)
    .create()
```
3. Alice sends the `SenderKeyDistributionMessage` to each channel member via their 1:1 Signal sessions:
```kotlin
members.forEach { memberId ->
    val address = SignalProtocolAddress(memberId, 1)
    val encrypted = SessionCipher(signalStore, address).encrypt(skdm.serialize())
    // POST /v1/messages/{memberId} with messageType=3
}
```

### Sending a channel message

```kotlin
val senderKeyName = SenderKeyName(channelId, aliceAddress)
val groupCipher = SenderKeyGroupCipher(signalStore, senderKeyName)
val ciphertext = groupCipher.encrypt(plaintext)
// Single ciphertext → POST /v1/messages/{channelId} (server fans out to all members)
// OR: POST /v1/messages/{memberId} for each member (simpler, less server logic)
```

### Receiving a channel message

```kotlin
// First: if receiving a SenderKeyDistributionMessage (messageType=3)
val skdm = SenderKeyDistributionMessage(rawBytes)
SenderKeyGroupCipher(signalStore, SenderKeyName(channelId, senderAddress))
    .process(skdm)                   // stores sender's chain key

// Then: decrypting a channel message
val plaintext = SenderKeyGroupCipher(signalStore, SenderKeyName(channelId, senderAddress))
    .decrypt(ciphertext)
```

---

## Part 4 — SignalProtocolStore implementation

libsignal requires four stores. Each is a Kotlin interface you implement, backed by `EncryptedSharedPreferences`. The `IdentityManager` already wires `EncryptedSharedPreferences` — the Signal store lives in the same pattern.

```
signal/
└── AstraSignalProtocolStore.kt      -- implements SignalProtocolStore
    ├── IdentityKeyStore impl        -- own identity key pair + trusted remote identity keys
    ├── SessionStore impl            -- ratchet session state per (userId, deviceId)
    ├── PreKeyStore impl             -- one-time pre-keys (private halves)
    └── SignedPreKeyStore impl       -- signed pre-key (private half + signature)
```

### Storage layout in EncryptedSharedPreferences

```
Key                              Value
─────────────────────────────────────────────────────────────────
signal_identity_key_pair         base64(IdentityKeyPair.serialize())
signal_registration_id           int
signal_session_{userId}_{dev}    base64(SessionRecord.serialize())
signal_prekey_{id}               base64(PreKeyRecord.serialize())
signal_spk_{id}                  base64(SignedPreKeyRecord.serialize())
signal_trust_{userId}            base64(IdentityKey.serialize())
signal_sender_key_{channel}_{addr}  base64(SenderKeyRecord.serialize())
```

All keys are prefixed `signal_` so `invalidateAllKeys()` + `wipeAll()` can sweep them.

### IdentityKeyStore — critical note

The Signal identity key (`IdentityKeyPair`) is a **Curve25519** key, not the `secp256r1` key currently in Android Keystore. These are different curves. `IdentityManager.generateKeystoreKeyPair()` generates secp256r1 for device attestation/signing. The Signal identity key must be a separate Curve25519 key pair, generated via libsignal and stored in `EncryptedSharedPreferences` (not Keystore, because Keystore does not support Curve25519 on all Android versions).

```kotlin
// Generate Signal identity key (called once during provisioning)
val identityKeyPair: IdentityKeyPair = KeyHelper.generateIdentityKeyPair()
val registrationId: Int = KeyHelper.generateRegistrationId(false)
// Store both in EncryptedSharedPreferences under signal_identity_key_pair + signal_registration_id
```

The existing secp256r1 Keystore key pair continues to serve device attestation. The two keys coexist.

---

## Part 5 — Key lifecycle

### One-time pre-keys
- Generate batch of 100 on provisioning, upload to server
- Private halves stored locally in `PreKeyStore`
- When server reports count < 10 (via WebSocket `keysNeeded` event), generate and upload another 100
- Server deletes public half when consumed; device deletes private half when decryption confirms the session

### Signed pre-key rotation
- Generate a new SPK every 7 days
- Upload to server (replaces existing)
- Keep the previous SPK for 48 hours (for in-flight messages encrypted to the old SPK)
- Delete private half of old SPK after 48h grace period

### Session reset
- If a `PreKeySignalMessage` arrives for an address that already has a session, libsignal automatically re-establishes a fresh session (the remote side re-ran X3DH)
- This is the recovery path after a device reinstall

---

## Security properties provided

| Property | Mechanism |
|---|---|
| Confidentiality | AES-256-CBC + HMAC-SHA256 (Signal wire format) or ChaCha20-Poly1305 (newer libsignal versions) |
| Forward secrecy | Symmetric ratchet: each message key is deleted after use |
| Post-compromise security | DH ratchet: each reply rotates to new ephemeral DH key pair |
| Sender authentication | `IdentityKey` in `PreKeySignalMessage`; libsignal verifies on `decrypt()` |
| Replay protection | Message counter in `SignalMessage`; libsignal rejects out-of-order duplicates |
| MITM resistance | SPK signature verified against IK before session establishment |
| Deniability | DH key agreement provides no non-repudiable proof of message origin |
