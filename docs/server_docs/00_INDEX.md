# AstraSecure Relay Server — Documentation Index

The relay server is a stateless Node.js/Express process backed by PostgreSQL. It routes opaque Signal Protocol ciphertext between devices and holds public pre-keys for session establishment. It never sees plaintext.

```
┌─────────────────────────────────────────────────────────────┐
│  Android Device A          Relay Server          Android B  │
│                                                              │
│  ProvisioningVM ─── POST /v1/users ────────►  users table  │
│  SignalKeyMgr   ─── PUT  /v1/keys  ────────►  prekeys table │
│  SignalCryptoE  ─── GET  /v1/keys/{id} ◄───   bundle fetch  │
│  ChatViewModel  ─── POST /v1/messages/{id} ─► queue row     │
│                         WebSocket push  ──────────────────►  │
│                                               ChatViewModel  │
└─────────────────────────────────────────────────────────────┘
```

---

## Documents in this folder

| File | What it covers |
|---|---|
| [01_HOSTING_OPTIONS.md](01_HOSTING_OPTIONS.md) | Hardware requirements, free tiers (Oracle, Fly.io, Render, Railway), pay-as-you-go providers (Hetzner, DigitalOcean, Vultr, Linode), comparison table |
| [02_SETUP_GUIDE.md](02_SETUP_GUIDE.md) | Full step-by-step setup on Ubuntu 22.04: PostgreSQL 16, Node.js 20, deploy server code, configure `.env`, run as systemd service, UFW firewall |
| [03_TLS_CONFIGURATION.md](03_TLS_CONFIGURATION.md) | Let's Encrypt (domain), self-signed cert (IP-only), extracting the pin hash for Android, Network Security Config changes |
| [04_ENVIRONMENT_REFERENCE.md](04_ENVIRONMENT_REFERENCE.md) | Every `.env` variable explained with examples for local dev, Let's Encrypt production, self-signed, and managed cloud platforms |
| [05_REVERSE_PROXY.md](05_REVERSE_PROXY.md) | Caddy (automatic TLS, 3-line config) and Nginx reverse proxy setup for production, WebSocket proxying, TLS 1.2+ cipher config |
| [06_ANDROID_WIRING.md](06_ANDROID_WIRING.md) | Changing the server URL in `build.gradle.kts` for emulator, LAN device, and remote VPS; Windows Firewall; Network Security Config; verifying registration and end-to-end messaging |
| [07_MAINTENANCE.md](07_MAINTENANCE.md) | Daily `pg_dump` backups via cron, log retention, uptime monitoring, updating the server, rotating JWT secret, schema migration notes, maintenance checklist |
| [08_CLOUD_DEPLOY_NO_VPS.md](08_CLOUD_DEPLOY_NO_VPS.md) | **No-VPS deployment** — Supabase (free managed PostgreSQL) + Railway / Render / Koyeb (free Node.js host). Full walkthrough, HTTPS out of the box, no Linux admin required |
| [09_LOCAL_DEPLOY.md](09_LOCAL_DEPLOY.md) | **Local development on Windows 11** — Docker or native PostgreSQL, Node.js, emulator wiring (`10.0.2.2`), physical device via ADB reverse or LAN IP, Windows Firewall, reset/cheat sheet |
| [gaps_1.md](gaps_1.md) | **Implementation gap audit** — 21 gaps across provisioning flow, JWT persistence, server auth, ACK ownership, OPK ID logic, and more. P0–P3 priority ratings with root-cause file:line citations and fixes |
| [UI_audit.md](UI_audit.md) | **UI production-readiness audit** — 20 gaps across schema seeding, access control, misleading status indicators, dead UI elements, and hardcoded demo values. 6 P0 blockers, 10 P1 functional issues, 4 P2 polish items. Includes a 4-sprint remediation plan |

---

## Quickstart paths

### I want to demo on the emulator right now

→ **Windows 11 local setup** — see [09_LOCAL_DEPLOY.md](09_LOCAL_DEPLOY.md) for the full walkthrough (Docker Postgres, npm, emulator and physical device wiring, Windows Firewall, cheat sheet).

Quick version:
1. `docker run -d --name astra-pg -e POSTGRES_PASSWORD=secret -e POSTGRES_DB=astrasecure -p 5432:5432 postgres:16`
2. `cd server && npm install`
3. `cp .env.example .env` → set `DATABASE_URL=postgres://postgres:secret@localhost:5432/astrasecure` and a `JWT_SECRET`
4. `node index.js`
5. The AVD debug URL is already `http://10.0.2.2:3000` — build and run the app

### I want a server online with no Linux admin (recommended)

→ **Supabase + Railway** — see [08_CLOUD_DEPLOY_NO_VPS.md](08_CLOUD_DEPLOY_NO_VPS.md)  
→ Supabase: free PostgreSQL in ~2 minutes. Railway: push GitHub repo, set two env vars, get a `https://` URL  
→ No SSH, no firewall, no TLS setup — everything is managed

### I want a free persistent VPS server online in 30 minutes

→ **Oracle Cloud Always Free** — see [01_HOSTING_OPTIONS.md § Oracle Cloud](01_HOSTING_OPTIONS.md)  
→ Then follow [02_SETUP_GUIDE.md](02_SETUP_GUIDE.md) for the full Ubuntu setup  
→ Then [03_TLS_CONFIGURATION.md](03_TLS_CONFIGURATION.md) if you have a domain, or self-signed otherwise  
→ Then [06_ANDROID_WIRING.md](06_ANDROID_WIRING.md) to point the app at the new server

### I want to deploy on Fly.io right now (free, 5 minutes)

→ See the Fly.io section in [01_HOSTING_OPTIONS.md](01_HOSTING_OPTIONS.md) — three commands deploy the server and managed PostgreSQL automatically.

### I want production-grade TLS with a real domain

→ [03_TLS_CONFIGURATION.md § Let's Encrypt](03_TLS_CONFIGURATION.md) + [05_REVERSE_PROXY.md § Caddy](05_REVERSE_PROXY.md) — the combination gives you automatic certificate renewal and clean WebSocket proxying.

---

## Server code location

```
Capstone/
└── server/
    ├── index.js          Main server — all routes + WebSocket
    ├── schema.sql        PostgreSQL schema (auto-applied on startup)
    ├── package.json      Dependencies: express, pg, jsonwebtoken, ws, dotenv
    └── .env.example      Template — copy to .env and fill in values
```

## Android code locations

| File | Role |
|---|---|
| [transport/SignalServerClient.kt](../../app/src/main/java/com/explo/capstone/transport/SignalServerClient.kt) | HTTP + WebSocket client |
| [transport/MessageTransport.kt](../../app/src/main/java/com/explo/capstone/transport/MessageTransport.kt) | Interface |
| [transport/TransportMessage.kt](../../app/src/main/java/com/explo/capstone/transport/TransportMessage.kt) | DTOs |
| [shared/AppContainer.kt](../../app/src/main/java/com/explo/capstone/shared/AppContainer.kt) | Server client wired here |
| [app/build.gradle.kts](../../app/build.gradle.kts) | `SIGNAL_SERVER_URL` build config field |
| [app/src/main/res/xml/network_security_config.xml](../../app/src/main/res/xml/network_security_config.xml) | Cleartext + cert pin rules |
