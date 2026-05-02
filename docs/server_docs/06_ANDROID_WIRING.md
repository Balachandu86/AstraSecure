# Connecting the Android App to the Server

This document covers every change needed in the Android project to point the app at a running server, whether that server is a local emulator host, a LAN machine, or a remote VPS.

---

## How the app discovers the server URL

The server URL is injected at compile time via a `BuildConfig` field defined in [app/build.gradle.kts](../../app/build.gradle.kts):

```kotlin
buildTypes {
    debug {
        buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://10.0.2.2:3000\"")
    }
    release {
        buildConfigField("String", "SIGNAL_SERVER_URL", "\"https://your.server.host\"")
    }
}
```

`AppContainer` reads this at startup:

```kotlin
val serverClient: MessageTransport = SignalServerClient(BuildConfig.SIGNAL_SERVER_URL)
```

You only need to change the string in `build.gradle.kts` and rebuild. No other code changes are required.

---

## Scenario 1 — Android emulator (AVD) talking to localhost

This is the default debug config and requires no changes.

- The AVD uses the special alias `10.0.2.2` to reach the host machine's localhost
- The server must be running on the host machine on port 3000
- Plain HTTP is permitted for `10.0.2.2` in `network_security_config.xml`

**Start the server on your dev machine:**

```bash
cd server
node index.js
```

**Run the app** in the emulator — it will register with `http://10.0.2.2:3000` automatically.

---

## Scenario 2 — Physical device on the same Wi-Fi network

Your phone and development machine must be on the same LAN.

### 1. Find your machine's LAN IP

**Windows:**
```
ipconfig
# Look for "IPv4 Address" under your Wi-Fi adapter, e.g. 192.168.1.105
```

**macOS / Linux:**
```bash
ip addr show   # or: ifconfig
```

### 2. Update the build config

In [app/build.gradle.kts](../../app/build.gradle.kts):

```kotlin
debug {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://192.168.1.105:3000\"")
}
```

### 3. Allow port 3000 through Windows Firewall (Windows only)

On Windows, the Android device cannot reach the development machine until you allow inbound connections on port 3000:

1. Open **Windows Defender Firewall with Advanced Security**
2. Click **Inbound Rules → New Rule**
3. Rule type: **Port** → TCP → Specific port: `3000`
4. Action: **Allow the connection**
5. Profile: **Private**
6. Name: `AstraSecure dev server`

Or via PowerShell (run as Administrator):

```powershell
New-NetFirewallRule -DisplayName "AstraSecure Dev Server" `
  -Direction Inbound -Protocol TCP -LocalPort 3000 -Action Allow
```

### 4. Allow cleartext in Network Security Config

Add your LAN IP to [app/src/main/res/xml/network_security_config.xml](../../app/src/main/res/xml/network_security_config.xml):

```xml
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">10.0.2.2</domain>       <!-- AVD host -->
    <domain includeSubdomains="false">192.168.1.105</domain>   <!-- LAN IP -->
</domain-config>
```

Rebuild and install on device.

---

## Scenario 3 — Remote VPS (HTTP, no TLS)

Useful for a shared demo where the server is on a cloud VM but TLS is not yet configured.

```kotlin
debug {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://YOUR_VPS_IP:3000\"")
}
```

Add the VPS IP to `network_security_config.xml`:

```xml
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">10.0.2.2</domain>
    <domain includeSubdomains="false">YOUR_VPS_IP</domain>
</domain-config>
```

**Note:** Only use this during development. The `<base-config cleartextTrafficPermitted="false">` rule ensures plain HTTP is blocked everywhere else.

---

## Scenario 4 — Remote VPS with TLS (production / graded demo)

This is the target end state. TLS must be set up on the server first (see `03_TLS_CONFIGURATION.md`).

### With a domain name (Let's Encrypt cert)

```kotlin
debug {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"https://astra.yourdomain.com\"")
}
release {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"https://astra.yourdomain.com\"")
}
```

No changes needed to `network_security_config.xml` — the domain will be validated against the Android system trust store, which trusts Let's Encrypt automatically.

To add a certificate pin (hardens against CA compromise):

1. Get the pin hash:
```bash
openssl s_client -connect astra.yourdomain.com:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary \
  | base64
```

2. Add to `network_security_config.xml`:
```xml
<domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="true">astra.yourdomain.com</domain>
    <pin-set expiration="2028-01-01">
        <pin digest="SHA-256">PASTE_HASH_HERE=</pin>
    </pin-set>
</domain-config>
```

### With a self-signed cert (IP-only VPS)

Follow the Android trust instructions in `03_TLS_CONFIGURATION.md` — either add the cert to `@raw/server_cert` or install it on the test device manually.

---

## Verifying the connection from the app

After updating the server URL and rebuilding:

1. Launch the app — go through the provisioning flow (enter a callsign and confirm)
2. On the server, watch the logs:
   ```bash
   sudo journalctl -u astrasecure -f
   ```
   You should see:
   ```
   [register] userId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx displayName=ALPHA opks=100
   [ws] connected userId=xxxxxxxx... total=1
   ```
3. Check the database:
   ```bash
   psql "postgres://astra_user:password@localhost:5432/astrasecure" \
     -c "SELECT user_id, display_name, registration_id FROM users;"
   ```

If provisioning fails, the app shows `SIGNAL_PROVISION_FAILED` on the provisioning screen. Common causes:

| Error | Cause |
|---|---|
| `SIGNAL_PROVISION_FAILED: Failed to connect` | Server not running, wrong IP, port blocked by firewall |
| `SIGNAL_PROVISION_FAILED: cleartext...not permitted` | URL is `http://` but cleartext not allowed for that domain in `network_security_config.xml` |
| `SIGNAL_PROVISION_FAILED: SSL handshake aborted` | Self-signed cert not trusted on device |
| `PROVISIONING_PENDING_SANDRANI` | `IdentityManager.provisionIdentity()` threw `NotImplementedError` — identity module not complete |

---

## Testing messaging between two devices

To test end-to-end messaging you need two provisioned devices (real or emulator) registered against the same server.

**Setup:**
1. Device A: provision with callsign `ALPHA`
2. Device B: provision with callsign `BRAVO`
3. Both devices must be in the **same channel** — navigate to the same Mission → Channel

**First message flow:**
1. Device A opens the channel → `ChatViewModel.init` calls `joinChannel()` on the server
2. Device A sends a message → `SignalMessageRepository.send()` runs:
   - Fetches channel members from server
   - Distributes SKDM to BRAVO via 1:1 X3DH session
   - Encrypts message with Sender Key
   - Posts ciphertext to server → server pushes to BRAVO's WebSocket
3. Device B receives the SKDM (1:1 message), decrypts it, stores sender key
4. Device B receives the channel message, decrypts it, displays it in chat

**What you should see:**
- Device A: message shows `DELIVERED` in the send pipeline
- Device B: message appears in the chat as an `Incoming` bubble from `ALPHA`

**If Device B doesn't receive:**
- Check both devices are connected to the WebSocket: server logs should show `[ws] connected` for both user IDs
- Check Device B is a member of the channel: `SELECT * FROM channel_members WHERE channel_id = 'CHANNEL_UUID';`
- Logcat on Device B: filter by tag `AppContainer` — look for `Processed incoming SKDM` followed by `Channel message processing`
