# Simple Local Test — AstraSecure Signal Server

Everything runs in Docker Desktop. You do NOT need Node.js or PostgreSQL installed.

---

## Part 1 — Start the server (do this once per session)

### Step 1 — Open a terminal in the server folder

In Windows Explorer, navigate to `Capstone\server\`.  
Right-click inside the folder → **Open in Terminal** (or open PowerShell and `cd` there).

### Step 2 — Start everything with one command

```powershell
docker compose up --build
```

What this does:
- Pulls a PostgreSQL image and starts the database
- Builds the Node.js server image from the Dockerfile
- Starts the server on port **3000**
- Automatically creates all the database tables (schema.sql)

**First run takes ~1–2 minutes** to download images. After that it is instant.

### Step 3 — Confirm it is running

Look for this line in the terminal output:

```
server-1  | Schema applied.
server-1  | Listening on 0.0.0.0:3000
```

You can also open a browser and go to `http://localhost:3000/health` — you should see:
```json
{ "status": "ok" }
```

### Step 4 — Stop the server when you are done

Press `Ctrl + C` in the terminal, then run:
```powershell
docker compose down
```

> Your database data is saved in a Docker volume (`pgdata`). It will still be there next time you run `docker compose up`.

---

## Part 2 — Connect the Android Emulator

The emulator already works with the default setting. No changes needed.

The debug build in [app/build.gradle.kts](app/build.gradle.kts) line 26 is:
```kotlin
buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://10.0.2.2:3000\"")
```

`10.0.2.2` is a special Android emulator address that automatically points to your laptop's localhost.

**Steps:**
1. Start the server (Part 1 above)
2. Run the app in Android Studio on the emulator
3. Done — it connects to your server

---

## Part 3 — Connect a Physical Device

A physical phone cannot use `10.0.2.2`. It needs your laptop's actual Wi-Fi IP address.

### Step 1 — Find your laptop's Wi-Fi IP

Open PowerShell and run:
```powershell
ipconfig
```

Look for the section called **Wireless LAN adapter Wi-Fi** and find **IPv4 Address**.  
It will look something like: `192.168.1.42`

Write that number down.

### Step 2 — Make sure the phone is on the same Wi-Fi network

Your phone and laptop must be connected to the **same Wi-Fi router/network**.  
If your laptop is on one network and your phone on another, it will not work.

### Step 3 — Update the server URL in the app

Open [app/build.gradle.kts](app/build.gradle.kts) and change line 26:

```kotlin
// BEFORE (emulator only)
buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://10.0.2.2:3000\"")

// AFTER (replace 192.168.1.42 with YOUR actual IP from Step 1)
buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://192.168.1.42:3000\"")
```

> **Note:** This change breaks the emulator. Switch it back to `10.0.2.2` when you want to use the emulator again.

### Step 4 — Allow the app to talk to plain HTTP

Android blocks plain HTTP on physical devices by default. You need to allow it for your IP.

Open [app/src/main/res/xml/network_security_config.xml](app/src/main/res/xml/network_security_config.xml).  
If that file does not exist, create it with this content (replace the IP with yours):

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.1.42</domain>
    </domain-config>
</network-security-config>
```

Then make sure [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) references it.  
Inside the `<application>` tag, add:
```xml
android:networkSecurityConfig="@xml/network_security_config"
```

### Step 5 — Allow port 3000 through Windows Firewall

Docker exposes the port but Windows Firewall may block it. Run this **once** in PowerShell as Administrator:

```powershell
New-NetFirewallRule -DisplayName "AstraSecure Dev" -Direction Inbound -Protocol TCP -LocalPort 3000 -Action Allow
```

### Step 6 — Build and run on the physical device

In Android Studio: **Run → Run 'app'** with your physical device selected.

### Step 7 — Verify from the phone

Open a browser on your phone and go to:
```
http://192.168.1.42:3000/health
```
(use your actual IP)

If you see `{ "status": "ok" }` the app can reach the server.

---

## Quick Reference

| Situation | SIGNAL_SERVER_URL |
|---|---|
| Emulator | `http://10.0.2.2:3000` |
| Physical device on same Wi-Fi | `http://YOUR_LAPTOP_IP:3000` |

| Command | What it does |
|---|---|
| `docker compose up --build` | Start server + database |
| `docker compose down` | Stop everything |
| `docker compose down -v` | Stop and **delete** the database |
| `docker compose logs -f server` | Watch server logs live |

---

## Troubleshooting

**"Schema applied" never appears / server crashes immediately**  
→ The database did not start in time. Run `docker compose down` then `docker compose up --build` again.

**Phone says "connection refused"**  
→ Check Step 5 (firewall). Also confirm the IP in `build.gradle.kts` matches `ipconfig` output exactly.

**Emulator stopped working after I changed the IP**  
→ Change the URL back to `http://10.0.2.2:3000` in `build.gradle.kts` and rebuild.

**Port 3000 already in use**  
→ Something else is using port 3000. Run `docker compose down` first, or change `"3000:3000"` to `"3001:3000"` in `server/docker-compose.yml` and update the URLs accordingly.
