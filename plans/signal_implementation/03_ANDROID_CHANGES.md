# Android App Changes

Every change is scoped to the crypto and transport layers. The ViewModel and UI layers are unaffected. The `MessageRepository` interface signature is preserved — callers do not change.

---

## New files

```
app/src/main/java/com/explo/capstone/
├── crypto/
│   ├── CryptoEngine.kt              (modified)
│   └── signal/
│       ├── AstraSignalProtocolStore.kt   (new)
│       ├── SignalCryptoEngine.kt         (new)
│       └── SignalKeyManager.kt           (new)
├── transport/
│   ├── MessageTransport.kt              (new — interface)
│   ├── SignalServerClient.kt            (new — Retrofit + WebSocket)
│   └── TransportMessage.kt              (new — wire DTOs)
└── shared/
    └── AppContainer.kt                  (modified)
```

---

## `crypto/signal/SignalKeyManager.kt` — key generation and server registration

Handles Signal-specific key material. Called once from `IdentityManager.provisionIdentity()`.

```kotlin
class SignalKeyManager(
    private val store: AstraSignalProtocolStore,
    private val serverClient: SignalServerClient,
) {
    // Called once on provisioning. Generates all Signal keys and registers on server.
    suspend fun provision(userId: String, displayName: String) {
        val identityKeyPair = KeyHelper.generateIdentityKeyPair()
        val registrationId  = KeyHelper.generateRegistrationId(false)
        val signedPreKey    = KeyHelper.generateSignedPreKey(identityKeyPair, id = 1)
        val oneTimePreKeys  = KeyHelper.generatePreKeys(startId = 1, count = 100)

        store.saveIdentityKeyPair(identityKeyPair)
        store.saveRegistrationId(registrationId)
        store.storeSignedPreKey(signedPreKey.id, signedPreKey)
        oneTimePreKeys.forEach { store.storePreKey(it.id, it) }

        serverClient.register(
            userId, displayName, registrationId,
            identityKeyPair.publicKey,
            signedPreKey,
            oneTimePreKeys,
        )
    }

    // Called when server sends keysNeeded event (OPK count < threshold).
    suspend fun replenishPreKeys(startId: Int) {
        val newKeys = KeyHelper.generatePreKeys(startId, count = 100)
        newKeys.forEach { store.storePreKey(it.id, it) }
        serverClient.uploadPreKeys(newKeys)
    }

    // Called every 7 days. Rotates the signed pre-key.
    suspend fun rotateSignedPreKey() {
        val identityKeyPair = store.identityKeyPair
        val newId = store.loadSignedPreKeys().maxOfOrNull { it.id }?.plus(1) ?: 2
        val newSpk = KeyHelper.generateSignedPreKey(identityKeyPair, newId)
        store.storeSignedPreKey(newSpk.id, newSpk)
        serverClient.uploadSignedPreKey(newSpk)
        // Retain previous SPK for 48h (handled in AstraSignalProtocolStore grace-period logic)
    }
}
```

---

## `crypto/signal/AstraSignalProtocolStore.kt` — session state storage

Implements `SignalProtocolStore` (which extends `IdentityKeyStore + SessionStore + PreKeyStore + SignedPreKeyStore`). All state is serialized to `EncryptedSharedPreferences`.

```kotlin
class AstraSignalProtocolStore(context: Context) : SignalProtocolStore {

    private val prefs: SharedPreferences = buildEncryptedPrefs(context, "astra_signal")

    // ── IdentityKeyStore ──────────────────────────────────────────────────────

    override fun getIdentityKeyPair(): IdentityKeyPair =
        prefs.getString("signal_identity_key_pair", null)
            ?.let { IdentityKeyPair(Base64.decode(it)) }
            ?: error("Signal identity not provisioned")

    fun saveIdentityKeyPair(kp: IdentityKeyPair) =
        prefs.edit().putString("signal_identity_key_pair", Base64.encode(kp.serialize())).apply()

    override fun getLocalRegistrationId(): Int =
        prefs.getInt("signal_registration_id", -1)

    fun saveRegistrationId(id: Int) =
        prefs.edit().putInt("signal_registration_id", id).apply()

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val key = "signal_trust_${address.name}"
        val existing = prefs.getString(key, null)?.let { IdentityKey(Base64.decode(it), 0) }
        prefs.edit().putString(key, Base64.encode(identityKey.serialize())).apply()
        return existing != null && existing != identityKey
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val stored = prefs.getString("signal_trust_${address.name}", null)
            ?: return true                          // trust on first use (TOFU)
        return IdentityKey(Base64.decode(stored), 0) == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        prefs.getString("signal_trust_${address.name}", null)
            ?.let { IdentityKey(Base64.decode(it), 0) }

    // ── SessionStore ──────────────────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val key = "signal_session_${address.name}_${address.deviceId}"
        return prefs.getString(key, null)
            ?.let { SessionRecord(Base64.decode(it)) }
            ?: SessionRecord()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val key = "signal_session_${address.name}_${address.deviceId}"
        prefs.edit().putString(key, Base64.encode(record.serialize())).apply()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        prefs.contains("signal_session_${address.name}_${address.deviceId}")

    override fun deleteSession(address: SignalProtocolAddress) {
        prefs.edit().remove("signal_session_${address.name}_${address.deviceId}").apply()
    }

    override fun deleteAllSessions(name: String) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("signal_session_${name}_") }.forEach(::remove)
        }.apply()
    }

    override fun getSubDeviceSessions(name: String): List<Int> =
        prefs.all.keys
            .filter { it.startsWith("signal_session_${name}_") }
            .mapNotNull { it.removePrefix("signal_session_${name}_").toIntOrNull() }

    // ── PreKeyStore ───────────────────────────────────────────────────────────

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        prefs.getString("signal_prekey_$preKeyId", null)
            ?.let { PreKeyRecord(Base64.decode(it)) }
            ?: throw InvalidKeyIdException("Unknown pre-key $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        prefs.edit().putString("signal_prekey_$preKeyId", Base64.encode(record.serialize())).apply()
    }

    override fun containsPreKey(preKeyId: Int): Boolean =
        prefs.contains("signal_prekey_$preKeyId")

    override fun removePreKey(preKeyId: Int) {
        prefs.edit().remove("signal_prekey_$preKeyId").apply()
    }

    // ── SignedPreKeyStore ─────────────────────────────────────────────────────

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        prefs.getString("signal_spk_$signedPreKeyId", null)
            ?.let { SignedPreKeyRecord(Base64.decode(it)) }
            ?: throw InvalidKeyIdException("Unknown SPK $signedPreKeyId")

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        prefs.edit().putString("signal_spk_$signedPreKeyId", Base64.encode(record.serialize())).apply()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        prefs.contains("signal_spk_$signedPreKeyId")

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        prefs.edit().remove("signal_spk_$signedPreKeyId").apply()
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        prefs.all.entries
            .filter { it.key.startsWith("signal_spk_") }
            .mapNotNull { (_, v) -> runCatching { SignedPreKeyRecord(Base64.decode(v as String)) }.getOrNull() }

    // ── SenderKeyStore ────────────────────────────────────────────────────────

    override fun storeSenderKey(senderKeyName: SenderKeyName, record: SenderKeyRecord) {
        val k = "signal_sender_${senderKeyName.groupId}_${senderKeyName.sender.name}"
        prefs.edit().putString(k, Base64.encode(record.serialize())).apply()
    }

    override fun loadSenderKey(senderKeyName: SenderKeyName): SenderKeyRecord {
        val k = "signal_sender_${senderKeyName.groupId}_${senderKeyName.sender.name}"
        return prefs.getString(k, null)
            ?.let { SenderKeyRecord(Base64.decode(it)) }
            ?: SenderKeyRecord()
    }

    // ── Wipe ─────────────────────────────────────────────────────────────────

    fun wipeAll() {
        // Called from IdentityManager.wipeAll() during panic
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("signal_") }.forEach(editor::remove)
        editor.apply()
    }
}
```

---

## `crypto/signal/SignalCryptoEngine.kt` — message encrypt/decrypt

Wraps `SessionCipher` and `SenderKeyGroupCipher`. This is the class that replaces the AES-GCM message path in `CryptoEngine`.

```kotlin
class SignalCryptoEngine(
    private val store: AstraSignalProtocolStore,
    private val serverClient: SignalServerClient,
) {
    // ── 1:1 session (used for SenderKeyDistributionMessage delivery) ──────────

    suspend fun encryptForAddress(recipientUserId: String, plaintext: ByteArray): Pair<ByteArray, Int> {
        val address = SignalProtocolAddress(recipientUserId, 1)
        if (!store.containsSession(address)) {
            val bundle = serverClient.fetchPreKeyBundle(recipientUserId)
            SessionBuilder(store, address).process(bundle)
        }
        val ciphertext = SessionCipher(store, address).encrypt(plaintext)
        return ciphertext.serialize() to ciphertext.type
    }

    fun decryptFromAddress(senderUserId: String, ciphertext: ByteArray, messageType: Int): ByteArray {
        val address = SignalProtocolAddress(senderUserId, 1)
        val cipher = SessionCipher(store, address)
        return when (messageType) {
            CiphertextMessage.PREKEY_TYPE   -> cipher.decrypt(PreKeySignalMessage(ciphertext))
            CiphertextMessage.WHISPER_TYPE  -> cipher.decrypt(SignalMessage(ciphertext))
            else -> throw IllegalArgumentException("Unknown message type: $messageType")
        }
    }

    // ── Channel (Sender Key) ──────────────────────────────────────────────────

    fun encryptForChannel(channelId: String, senderUserId: String, plaintext: ByteArray): ByteArray {
        val name = SenderKeyName(channelId, SignalProtocolAddress(senderUserId, 1))
        return SenderKeyGroupCipher(store, name).encrypt(plaintext)
    }

    fun decryptFromChannel(channelId: String, senderUserId: String, ciphertext: ByteArray): ByteArray {
        val name = SenderKeyName(channelId, SignalProtocolAddress(senderUserId, 1))
        return SenderKeyGroupCipher(store, name).decrypt(ciphertext)
    }

    // Called when Alice joins a channel: creates her sender key and distributes to members
    suspend fun distributeChannelSenderKey(channelId: String, aliceUserId: String, memberIds: List<String>) {
        val name = SenderKeyName(channelId, SignalProtocolAddress(aliceUserId, 1))
        val skdm: SenderKeyDistributionMessage = SenderKeyGroupCipher(store, name).create()
        memberIds.filter { it != aliceUserId }.forEach { memberId ->
            val (ciphertext, type) = encryptForAddress(memberId, skdm.serialize())
            serverClient.sendMessage(memberId, channelId, ciphertext, messageType = 3)
        }
    }

    fun processSenderKeyDistribution(channelId: String, senderUserId: String, skdmBytes: ByteArray) {
        val name = SenderKeyName(channelId, SignalProtocolAddress(senderUserId, 1))
        val skdm = SenderKeyDistributionMessage(skdmBytes)
        SenderKeyGroupCipher(store, name).process(skdm)
    }
}
```

---

## `CryptoEngine.kt` — modifications

Only the message path changes. Document and password crypto are untouched.

```kotlin
// BEFORE
fun encryptMessage(missionKeyAlias: String, plaintext: ByteArray): ByteArray {
    val key = loadKey(missionKeyAlias)
    // ... AES-GCM
}

// AFTER — remove encryptMessage/decryptMessage entirely from CryptoEngine.
// These are now handled by SignalCryptoEngine.
// CryptoEngine retains:
//   generateMissionKey()     (still used for document encryption)
//   rotateMissionKey()       (still used for manual key rotation UI)
//   encryptWithPassword()    (Tools screen)
//   decryptWithPassword()    (Tools screen)
//   invalidateAllKeys()      (panic wipe — also triggers signalStore.wipeAll())
```

---

## `transport/MessageTransport.kt` — transport interface

```kotlin
interface MessageTransport {
    suspend fun sendMessage(recipientId: String, channelId: String, ciphertext: ByteArray, messageType: Int): Result<Unit>
    fun observeIncoming(): Flow<TransportMessage>
    suspend fun acknowledge(messageIds: List<Long>)
}

data class TransportMessage(
    val id: Long,
    val senderId: String,
    val channelId: String,
    val messageType: Int,
    val ciphertext: ByteArray,
)
```

---

## `transport/SignalServerClient.kt` — HTTP + WebSocket

Uses OkHttp (already transitively available via existing deps) for REST calls and WebSocket connection.

Key responsibilities:
- `register()` — POST /v1/users on provisioning
- `fetchPreKeyBundle()` — GET /v1/keys/{userId}
- `uploadPreKeys()` — PUT /v1/keys
- `sendMessage()` — POST /v1/messages/{recipientId}
- WebSocket — connects on app start, emits to `observeIncoming()` Flow, handles `keysNeeded` events

Add to `libs.versions.toml`:
```toml
okhttp = "4.12.0"
retrofit = "2.11.0"
[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-converter-gson = { module = "com.squareup.retrofit2:converter-gson", version.ref = "retrofit" }
```

---

## `shared/data/entity/MessageRepository.kt` — rewire send/receive

`InMemoryMessageRepository.send()` changes from AES-GCM to Signal channel encryption:

```kotlin
override suspend fun send(
    channelId: String,
    missionKeyAlias: String,   // kept in signature for interface compatibility; ignored
    senderId: String,
    plaintext: ByteArray,
    categoryId: String,
): Result<Message> = runCatching {
    // Signal channel encrypt
    val ciphertext = signalCryptoEngine.encryptForChannel(channelId, senderId, plaintext)
    val padded = metadataProcessor.padMessage(ciphertext)
    delay(metadataProcessor.randomizedDelayMs(200, 2000))

    // Relay to server
    transport.sendMessage(channelId, channelId, padded, messageType = 2)

    val message = Message(/* ... same shape ... */)
    store.updateMessages { it + message }
    refreshChannelFlows()
    message
}
```

`receive()` decrypts using `signalCryptoEngine.decryptFromChannel()`. Incoming messages from the transport's `observeIncoming()` flow are fed into `receive()` automatically by a coroutine launched in `AppContainer`.

---

## `identity/IdentityManager.kt` — extensions

Add to `provisionIdentity()`:
```kotlin
fun provisionIdentity(displayName: String): User {
    // ... existing Keystore key pair generation ...
    // Add: provision Signal keys
    signalKeyManager.provision(user.id, displayName)  // suspend — call from coroutine
    return user
}
```

Add to `wipeAll()`:
```kotlin
fun wipeAll() {
    // ... existing wipe logic ...
    signalStore.wipeAll()   // clears all signal_ prefixed prefs
}
```

---

## `shared/AppContainer.kt` — new singletons

```kotlin
class AppContainer(context: Context) {
    // ... existing fields ...

    // Signal
    val signalStore       = AstraSignalProtocolStore(appContext)
    val serverClient      = SignalServerClient(SERVER_URL, appContext)
    val signalCryptoEngine = SignalCryptoEngine(signalStore, serverClient)
    val signalKeyManager  = SignalKeyManager(signalStore, serverClient)
    val messageTransport: MessageTransport = serverClient

    // Existing messageRepository wired with signalCryptoEngine instead of cryptoEngine for messages
    val messageRepository: MessageRepository = InMemoryMessageRepository(
        store, signalCryptoEngine, metadataProcessor, messageTransport
    )
}
```

`SERVER_URL` should be read from `BuildConfig` (set in `build.gradle.kts` via `buildConfigField`), allowing different values for debug vs release.

---

## `AndroidManifest.xml`

Add internet permission (not currently present):
```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Add network security config for server TLS pinning:
```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">your.server.host</domain>
        <pin-set>
            <pin digest="SHA-256">base64_of_server_cert_public_key</pin>
        </pin-set>
    </domain-config>
</network-security-config>
```
