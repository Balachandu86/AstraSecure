# TLS Configuration

The AstraSecure relay server must run over TLS in any non-development environment. This protects the transport layer even though the Signal Protocol already encrypts the payload end-to-end — TLS prevents an attacker from observing message metadata (who is talking to whom, timing, message size).

Three options are covered below, in order of preference:

| Scenario | Method |
|---|---|
| VPS with a real domain name | Let's Encrypt (free, auto-renews) |
| VPS with an IP address only | Self-signed certificate |
| Development / local testing | Plain HTTP on `10.0.2.2` (AVD only, already configured) |

---

## Option 1 — Let's Encrypt (recommended for production)

Let's Encrypt issues free DV (domain-validated) certificates trusted by all major browsers and the Android system trust store. This is the right choice if your VPS has a domain name pointing to it.

### Prerequisites

- A domain or subdomain pointing to your VPS IP (A record in DNS, e.g. `astra.yourdomain.com → YOUR_SERVER_IP`)
- Port 80 open (Let's Encrypt HTTP-01 challenge uses it temporarily)
- Port 443 open for HTTPS traffic

### Install Certbot

```bash
sudo apt install -y certbot

# Obtain a certificate (standalone mode — no web server needed)
# This temporarily binds port 80 to complete the challenge
sudo certbot certonly --standalone -d astra.yourdomain.com

# Certificate files will be at:
#   /etc/letsencrypt/live/astra.yourdomain.com/fullchain.pem  (certificate chain)
#   /etc/letsencrypt/live/astra.yourdomain.com/privkey.pem    (private key)
```

### Configure the server

Edit `/home/astra/server/.env`:

```env
PORT=443
TLS_CERT=/etc/letsencrypt/live/astra.yourdomain.com/fullchain.pem
TLS_KEY=/etc/letsencrypt/live/astra.yourdomain.com/privkey.pem
```

The relay server (`index.js`) detects `TLS_CERT` and `TLS_KEY` and switches to `https.createServer` automatically.

Grant the `astra` user read access to the certificate files:

```bash
sudo chmod 755 /etc/letsencrypt/live/
sudo chmod 755 /etc/letsencrypt/archive/
# Or add astra to the ssl-cert group (cleaner approach):
sudo groupadd ssl-cert 2>/dev/null || true
sudo usermod -aG ssl-cert astra
sudo chgrp ssl-cert /etc/letsencrypt/live/ /etc/letsencrypt/archive/
sudo chmod 750 /etc/letsencrypt/live/ /etc/letsencrypt/archive/
sudo find /etc/letsencrypt/archive -name '*.pem' -exec chmod 640 {} \;
```

Allow port 443 in UFW and restart:

```bash
sudo ufw allow 443/tcp
sudo systemctl restart astrasecure
```

Test: `curl https://astra.yourdomain.com/health`

### Auto-renewal

Certbot installs a systemd timer that renews certificates automatically before they expire (every 90 days). Test the renewal process:

```bash
sudo certbot renew --dry-run
```

After renewal the server needs to be restarted to pick up new certificate files. Add a renewal hook:

```bash
sudo nano /etc/letsencrypt/renewal-hooks/deploy/restart-astrasecure.sh
```

```bash
#!/bin/bash
systemctl restart astrasecure
```

```bash
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/restart-astrasecure.sh
```

---

## Option 2 — Self-signed certificate (IP-only VPS)

Use this when you have a VPS IP address but no domain name. Self-signed certificates are not trusted by the Android system store by default, so you must configure the Android app to trust your specific certificate.

### Generate the certificate

Run on the **server**:

```bash
# Create a directory for your certs
mkdir -p ~/server/certs
cd ~/server/certs

# Generate a 4096-bit RSA key and a self-signed cert valid for 3 years
openssl req -x509 -newkey rsa:4096 -sha256 -days 1095 -nodes \
  -keyout server.key \
  -out server.crt \
  -subj "/CN=YOUR_SERVER_IP" \
  -addext "subjectAltName=IP:YOUR_SERVER_IP"

# Restrict permissions
chmod 600 server.key
chmod 644 server.crt
```

Replace `YOUR_SERVER_IP` with your actual IP address (e.g. `142.250.1.100`).

### Configure the server

Edit `.env`:

```env
PORT=443
TLS_CERT=/home/astra/server/certs/server.crt
TLS_KEY=/home/astra/server/certs/server.key
```

Restart:

```bash
sudo systemctl restart astrasecure
```

### Extract the certificate public key hash for Android pinning

The Android app pins the server certificate's public key. Compute the SHA-256 hash:

```bash
openssl x509 -in ~/server/certs/server.crt -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary \
  | base64
```

Copy the output — it looks like `abc123+xyz/...==`. You will need this for the next step.

### Trust the certificate on Android

#### A — Network Security Config (recommended for release builds)

Edit [app/src/main/res/xml/network_security_config.xml](../../app/src/main/res/xml/network_security_config.xml):

```xml
<network-security-config>

    <!-- Self-signed server: trust by certificate pin instead of CA -->
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="false">YOUR_SERVER_IP</domain>
        <pin-set expiration="2028-01-01">
            <pin digest="SHA-256">PASTE_BASE64_HASH_HERE=</pin>
        </pin-set>
        <!-- Also trust the certificate itself as a user-added CA -->
        <trust-anchors>
            <certificates src="@raw/server_cert" />
            <certificates src="system" />
        </trust-anchors>
    </domain-config>

    <base-config cleartextTrafficPermitted="false" />

</network-security-config>
```

Copy `server.crt` to `app/src/main/res/raw/server_cert.crt` (create the `raw/` folder if it doesn't exist):

```bash
# From your local machine:
scp astra@YOUR_SERVER_IP:~/server/certs/server.crt \
  app/src/main/res/raw/server_cert.crt
```

#### B — Debug builds only (quickest for testing)

For the emulator or a physical test device where you control the device, you can add the certificate to the device trust store manually:

```bash
# Push cert to device (physical device via USB, or emulator)
adb push server.crt /sdcard/server.crt

# On the device: Settings → Security → Install from storage → select server.crt
# Select "VPN and apps" as the credential use
```

This is the fastest path for a graded demo where you control the test device.

---

## Option 3 — Plain HTTP for the AVD emulator (already configured)

The debug build is already configured in [app/build.gradle.kts](../../app/build.gradle.kts) and [network_security_config.xml](../../app/src/main/res/xml/network_security_config.xml) to allow cleartext traffic to `10.0.2.2` only. No TLS setup needed for emulator-only development.

**Do not ship a release build with cleartext permitted.**

---

## Updating the Android build config after TLS setup

Once TLS is running, update the release build config field in [app/build.gradle.kts](../../app/build.gradle.kts):

```kotlin
release {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"https://astra.yourdomain.com\"")
    // or for IP-only: "\"https://YOUR_SERVER_IP\""
}
```

And for debug builds pointed at a remote server (not the emulator):

```kotlin
debug {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"https://astra.yourdomain.com\"")
}
```

---

## TLS checklist before demo

- [ ] Server responds to `curl https://your-server/health` without `-k` flag (i.e. certificate is trusted)
- [ ] `network_security_config.xml` domain matches the URL in `build.gradle.kts` exactly
- [ ] `cleartextTrafficPermitted="false"` in `<base-config>`
- [ ] Port 443 open in UFW (`sudo ufw status`)
- [ ] Old port 3000 closed if you switched to 443 (`sudo ufw delete allow 3000/tcp`)
- [ ] Certbot auto-renewal test passes: `sudo certbot renew --dry-run`
