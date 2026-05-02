# Local Development Deploy — Windows 11

Run the AstraSecure relay server on your Windows machine so the Android emulator (or a physical device on the same LAN) can hit it during development. No VPS, no Linux required.

---

## Prerequisites

| Tool | Minimum version | Check |
|------|----------------|-------|
| Node.js | 18 LTS or 20 LTS | `node --version` |
| npm | ships with Node | `npm --version` |
| PostgreSQL | 15 or 16 | Option A — native; Option B — Docker |
| Docker Desktop (optional) | any recent | easiest Postgres setup |

Download Node.js from [nodejs.org](https://nodejs.org) — choose the LTS installer. Docker Desktop is at [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop).

---

## Step 1 — Start PostgreSQL

Choose one of the two options below. Docker is recommended because it requires zero configuration.

### Option A — Docker (recommended)

```powershell
docker run -d --name astra-pg `
  -e POSTGRES_PASSWORD=secret `
  -e POSTGRES_DB=astrasecure `
  -p 5432:5432 `
  postgres:16
```

The container starts automatically on future Docker Desktop launches. To stop/start manually:

```powershell
docker stop astra-pg
docker start astra-pg
```

Your `DATABASE_URL` will be:
```
postgres://postgres:secret@localhost:5432/astrasecure
```

### Option B — Native PostgreSQL (Windows installer)

1. Download the Windows installer from [postgresql.org/download/windows](https://www.postgresql.org/download/windows) and run it.
2. Accept the default port (5432) and note the `postgres` superuser password you set.
3. Open **pgAdmin** or the **SQL Shell (psql)** shortcut and run:

```sql
CREATE USER astra_user WITH PASSWORD 'devpassword';
CREATE DATABASE astrasecure OWNER astra_user;
GRANT ALL PRIVILEGES ON DATABASE astrasecure TO astra_user;
```

Your `DATABASE_URL` will be:
```
postgres://astra_user:devpassword@localhost:5432/astrasecure
```

---

## Step 2 — Install server dependencies

Open a terminal in the `server/` directory:

```powershell
cd C:\Users\Tejas\AndroidStudioProjects\Capstone\server
npm install
```

---

## Step 3 — Configure environment variables

```powershell
copy .env.example .env
notepad .env
```

Set the values:

```env
DATABASE_URL=postgres://postgres:secret@localhost:5432/astrasecure
JWT_SECRET=any-long-random-string-for-dev
PORT=3000
# Leave TLS_CERT and TLS_KEY blank — plain HTTP is fine for local dev
```

For a proper JWT secret (optional in dev, required in production):

```powershell
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

---

## Step 4 — Start the server

**Development mode** (auto-restarts on file changes, requires Node 18+):

```powershell
npm run dev
```

**Or plain start:**

```powershell
npm start
```

Expected output:

```
Schema applied.
TLS not configured — plain HTTP (dev only).
AstraSecure relay server listening on :3000
  Health check: http://localhost:3000/health
  WebSocket:    ws://localhost:3000/v1/websocket
```

Verify it is up:

```powershell
curl http://localhost:3000/health
# {"status":"ok","ts":"..."}
```

---

## Step 5 — Connect the Android client

### Emulator (AVD)

The emulator maps `10.0.2.2` to the Windows host's loopback. No network changes needed.

In [app/build.gradle.kts](../../app/build.gradle.kts) the debug URL should already be:

```kotlin
debug {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://10.0.2.2:3000\"")
}
```

Build and run the app — it will reach your locally running server.

### Physical Android device (USB or LAN)

**Option A — ADB reverse (USB, simplest)**

Plug in the device and run:

```powershell
adb reverse tcp:3000 tcp:3000
```

This tunnels the device's `localhost:3000` to your machine's port 3000. The `SIGNAL_SERVER_URL` can stay as `http://10.0.2.2:3000` — change it to `http://localhost:3000` for a physical device, or use the ADB reverse trick and keep it the same.

Actually, for ADB reverse the device should use `http://localhost:3000`. Update build config:

```kotlin
debug {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://localhost:3000\"")
}
```

**Option B — LAN IP (Wi-Fi, no USB)**

Both the PC and the physical device must be on the **same Wi-Fi network** (same router, not a "Guest" network — guest networks isolate clients from each other).

---

**B-1. Find your Windows LAN IP**

Open a terminal (does not need to be Administrator) and run:

```powershell
ipconfig
```

Look for the adapter that is connected to your Wi-Fi. The relevant block will be named something like **"Wireless LAN adapter Wi-Fi"**. Read the `IPv4 Address` line:

```
Wireless LAN adapter Wi-Fi:
   IPv4 Address. . . . . . . . . . . : 192.168.1.42   ← this is your LAN IP
   Subnet Mask . . . . . . . . . . . : 255.255.255.0
   Default Gateway . . . . . . . . . : 192.168.1.1
```

If you have multiple adapters (Ethernet + Wi-Fi, Hyper-V virtual adapters, VPN), pick the one whose gateway matches your router's IP (usually `192.168.x.1` or `10.0.x.1`). Ignore any `169.254.x.x` addresses — those are link-local and not routable.

Write down this IP — you will use it in every step below. Example used throughout: `192.168.1.42`.

---

**B-2. Make the server listen on all interfaces**

By default Node.js/Express binds only to `127.0.0.1` (loopback). A device on another network interface cannot reach loopback. You must bind to `0.0.0.0`.

Open `server/.env` and add (or confirm) this line:

```env
HOST=0.0.0.0
```

If `HOST` is not wired up in your server entry point, check `server/index.js` (or `server/src/index.ts`) for the `.listen()` call and make sure it reads:

```js
server.listen(PORT, HOST, () => { ... })
// or, if HOST is not a variable yet:
server.listen(PORT, '0.0.0.0', () => { ... })
```

Restart the server after this change. The startup line should now say something like:

```
AstraSecure relay server listening on 0.0.0.0:3000
```

---

**B-3. Open port 3000 in Windows Firewall (run once as Administrator)**

Open **Windows Terminal** or **PowerShell** with **"Run as administrator"** (right-click → Run as administrator):

```powershell
netsh advfirewall firewall add rule `
  name="AstraSecure Dev" `
  dir=in `
  action=allow `
  protocol=TCP `
  localport=3000
```

Expected output: `Ok.`

**Verify the rule was actually added:**

```powershell
netsh advfirewall firewall show rule name="AstraSecure Dev"
```

You should see `Action: Allow` and `LocalPort: 3000`.

**Test from another machine on the same network** (e.g. open a browser on your phone or another laptop) and navigate to:

```
http://192.168.1.42:3000/health
```

You should get back `{"status":"ok","ts":"..."}`. If this fails, the firewall is still blocking — re-check Step B-2 (server bound to `0.0.0.0`) and that the rule was added with the correct port.

---

**B-4. Allow cleartext HTTP to your LAN IP in the Android app**

Android 9+ blocks unencrypted HTTP to arbitrary hosts by default. The app needs a network security config entry that whitelists your LAN IP (or the whole `192.168.x.x` range) for cleartext traffic.

Open [app/src/main/res/xml/network_security_config.xml](../../app/src/main/res/xml/network_security_config.xml). If it does not exist, create it:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Allow plain HTTP to LAN addresses during development -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.1.42</domain>
    </domain-config>
</network-security-config>
```

Replace `192.168.1.42` with your actual LAN IP. If your LAN IP changes often (DHCP), use a subnet wildcard instead — but note that `<domain>` does not support CIDR notation; the simplest workaround is to reserve a static IP for the PC in your router's DHCP settings.

Then confirm [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) references this file on the `<application>` element:

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

---

**B-5. Update the build config**

Open [app/build.gradle.kts](../../app/build.gradle.kts) and set the debug URL to your LAN IP:

```kotlin
debug {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://192.168.1.42:3000\"")
}
```

Replace `192.168.1.42` with the IP you found in Step B-1.

---

**B-6. Rebuild and run**

In Android Studio:

1. Click **Build → Clean Project** (ensures the new `BuildConfig` value is picked up).
2. Select your physical device in the device dropdown — it must appear over Wi-Fi debug or USB debugging. For pure Wi-Fi deployment, enable **Wireless Debugging** on the device (Settings → Developer options → Wireless debugging) and pair it once via Android Studio's **Device Manager → Pair using Wi-Fi**.
3. Click **Run** (▶).

The app will now send all API calls and WebSocket connections to `http://192.168.1.42:3000`.

---

**B-7. Confirm it is working**

Watch the server terminal while the app launches and completes the provisioning flow. You should see incoming requests logged. If nothing appears, check:

- The device is on the same Wi-Fi network as the PC (not mobile data).
- The LAN IP in the build config matches what `ipconfig` reported.
- The health check from the device's browser (`http://192.168.1.42:3000/health`) returns `{"status":"ok"}`.
- The firewall rule is present (Step B-3 verify command).
- The server is binding to `0.0.0.0`, not `127.0.0.1` (Step B-2).

---

## Step 6 — Verify registration end-to-end

1. Run the app and complete the provisioning flow.
2. Check the server terminal — you should see:

```
[register] userId=xxxxxxxx displayName=YourName opks=100
```

3. Confirm the user was written to the database:

**Docker:**
```powershell
docker exec -it astra-pg psql -U postgres -d astrasecure `
  -c "SELECT user_id, display_name FROM users;"
```

**Native PostgreSQL (psql in PATH):**
```powershell
psql "postgres://astra_user:devpassword@localhost:5432/astrasecure" `
  -c "SELECT user_id, display_name FROM users;"
```

---

## Common issues

### `ECONNREFUSED` — server not reachable from emulator

- Confirm `SIGNAL_SERVER_URL` is `http://10.0.2.2:3000` (not `localhost` or `127.0.0.1`).
- Confirm the server is actually running (`curl http://localhost:3000/health`).

### `password authentication failed`

- The password in `DATABASE_URL` must match what you set in Step 1.
- Docker option: password is `secret`, user is `postgres`.

### `connect ECONNREFUSED 127.0.0.1:5432` (server can't reach Postgres)

- Docker: `docker ps` — confirm `astra-pg` is running. If not: `docker start astra-pg`.
- Native: check Services (`services.msc`) — `postgresql-x64-16` should be Running.

### Physical device can't reach server over LAN

- Check the Windows Firewall rule was added (Step 5, Option B).
- Check both devices are on the same network — guest Wi-Fi networks often isolate clients.
- Try `ping 192.168.1.42` from a laptop on the same network to confirm reachability.

### `MODULE_NOT_FOUND`

Run `npm install` again from inside `server/`.

---

## Stopping and resetting

```powershell
# Stop the server
Ctrl+C

# Stop the Docker Postgres container
docker stop astra-pg

# Wipe and recreate the database (full reset)
docker rm -f astra-pg
docker run -d --name astra-pg `
  -e POSTGRES_PASSWORD=secret `
  -e POSTGRES_DB=astrasecure `
  -p 5432:5432 `
  postgres:16
```

The schema is automatically re-applied the next time `node index.js` starts.

---

## Quick-start cheat sheet

```powershell
# 1. Start Postgres (first time or after restart)
docker start astra-pg

# 2. Start server (auto-reload)
cd C:\Users\Tejas\AndroidStudioProjects\Capstone\server
npm run dev

# 3. Health check
curl http://localhost:3000/health

# 4. Build and run app in Android Studio — emulator hits 10.0.2.2:3000
```
