# Cloud deploy — QR invite-ready relay

Goal: replicate the local stack on the public internet so two devices on **different networks** can complete the QR invite flow against a TLS-terminated relay.

This document supersedes the older `01_HOSTING_OPTIONS.md` / `08_CLOUD_DEPLOY_NO_VPS.md`. It assumes you have working code on `master` and just want a deploy that's defensible for the capstone demo.

---

## 1. What the relay actually needs

Drawn from [server/index.js](../../../server/index.js) and [server/Dockerfile](../../../server/Dockerfile):

| Requirement | Why |
|---|---|
| Node 20+ runtime, `npm ci --omit=dev` | Stated `engines` in [server/package.json](../../../server/package.json) |
| Postgres 16 reachable via `DATABASE_URL` | Schema auto-applied on boot by `initSchema()` |
| `JWT_SECRET` env var (long random string) | All non-bootstrap routes verify a JWT signed with this |
| `PORT` (default 3000), `HOST=0.0.0.0` | HTTP + WebSocket share one listener |
| **TLS in front of port 3000** | Android release builds reject cleartext; `/v1/websocket` becomes `wss://` |
| Outbound: nothing | Server doesn't call any external API |

The Dockerfile already does the right thing — it's the **only** build artifact you need to deploy.

---

## 2. Recommended target: Fly.io

Why: free Postgres tier, deploys directly from the existing Dockerfile, hands you `https://<app>.fly.dev` with a real cert (no manual TLS setup), gives you a global anycast IP so devices on cellular and Wi-Fi both work.

If your school blocks Fly.io or you prefer a UI, see §4 for Render and §5 for a generic VPS.

### 2.1 One-time setup

```powershell
# install flyctl (PowerShell)
iwr https://fly.io/install.ps1 -useb | iex
fly auth signup    # or: fly auth login
```

### 2.2 Provision Postgres

```powershell
fly postgres create --name astrasecure-db --region <closest-region> --vm-size shared-cpu-1x --initial-cluster-size 1
```

Save the `DATABASE_URL` it prints — but you don't need to copy it manually; `fly postgres attach` (next step) injects it.

### 2.3 Launch the relay

```powershell
cd server
fly launch --no-deploy --copy-config=false --name astrasecure-relay --region <same-region-as-db>
# answer "No" to: setting up a Postgres? (already did)
# answer "No" to: deploy now?
```

`fly launch` writes a [fly.toml](../../../server/fly.toml) into `server/`. Open it and confirm:

```toml
[build]

[env]
  PORT = "3000"
  HOST = "0.0.0.0"

[http_service]
  internal_port = 3000
  force_https = true
  auto_stop_machines = "off"   # keep the WebSocket alive
  auto_start_machines = true
  min_machines_running = 1
```

`auto_stop_machines = "off"` matters: if Fly suspends your machine, idle WebSockets die and the issuer stops getting `sync_invalidated` pushes during a demo.

### 2.4 Wire the secrets

```powershell
fly postgres attach astrasecure-db --app astrasecure-relay
# this sets DATABASE_URL automatically

# 96-char random hex
$secret = -join ((1..48) | ForEach-Object { '{0:x2}' -f (Get-Random -Min 0 -Max 256) })
fly secrets set JWT_SECRET=$secret --app astrasecure-relay
```

### 2.5 Deploy

```powershell
fly deploy --app astrasecure-relay
fly logs --app astrasecure-relay
```

You should see the same startup banner as locally, ending in `AstraSecure server listening on 0.0.0.0:3000`. The schema is applied on the first boot — no manual migration step.

Smoke test:

```powershell
curl https://astrasecure-relay.fly.dev/health
```

---

## 3. Wire the Android app to the cloud relay

Two edits, both required before building a release APK that you can install on a device with no LAN access to your laptop.

### 3.1 Update the base URL

In [app/build.gradle.kts](../../../app/build.gradle.kts), the `release` build type:

```kotlin
buildConfigField("String", "SIGNAL_SERVER_URL", "\"https://astrasecure-relay.fly.dev\"")
```

The `SignalServerClient` rewrites `https://` to `wss://` automatically when opening the WebSocket — no separate WS URL to set.

### 3.2 Update the network security config

[app/src/main/res/xml/network_security_config.xml](../../../app/src/main/res/xml/network_security_config.xml) currently has the production `<domain-config>` block commented out. Uncomment it and set:

```xml
<domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="true">astrasecure-relay.fly.dev</domain>
    <pin-set expiration="2027-01-01">
        <pin digest="SHA-256">REPLACE_WITH_BASE64_SHA256_OF_SERVER_PUBKEY=</pin>
    </pin-set>
</domain-config>
```

To compute the pin, against the live deployment:

```powershell
$host = "astrasecure-relay.fly.dev"
openssl s_client -servername $host -connect "${host}:443" </dev/null 2>$null |
  openssl x509 -pubkey -noout |
  openssl pkey -pubin -outform DER |
  openssl dgst -sha256 -binary |
  openssl base64
```

Paste the resulting base64 string into the `<pin digest="SHA-256">` element.

> If you skip the pin, certificate validation still works (Fly's cert is from a public CA), but you lose pinning protection — for a security capstone demo, pin it.

### 3.3 Build & install

```powershell
.\gradlew assembleRelease
# install the APK from app\build\outputs\apk\release\app-release.apk
```

---

## 4. Alternative: Render

Skip the CLI; useful if Fly is blocked or you want a web UI for teammates.

1. Push the repo to GitHub (you already are on `master`).
2. Render dashboard → **New → PostgreSQL** → free plan. Copy the **Internal Database URL**.
3. Open a Render web shell on the DB and run [server/schema.sql](../../../server/schema.sql) (or just deploy the relay first — it'll apply the schema on boot).
4. Render dashboard → **New → Web Service** → "Deploy an existing Docker image or Dockerfile" → pick the repo, set **Root Directory** to `server`.
5. Environment variables:
   - `DATABASE_URL` → paste the internal URL
   - `JWT_SECRET` → a long random string
   - `PORT` → `3000`
   - `HOST` → `0.0.0.0`
6. Deploy. Use the assigned `https://<service>.onrender.com` as your `SIGNAL_SERVER_URL`.

Caveat: Render's free tier sleeps idle services after ~15 minutes. The first request after sleep takes ~30s and any active WebSocket is killed on suspend. Fine for the demo if you warm it up first; not fine for sustained testing.

---

## 5. Alternative: Generic VPS (DigitalOcean, Hetzner, Oracle free tier, EC2)

If you want full control and a static IP, a $5 VPS works identically to your laptop:

```bash
# on the VPS (Ubuntu)
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
git clone <your-repo> astrasecure && cd astrasecure
export JWT_SECRET=$(openssl rand -hex 48)
docker compose up -d --build
```

You then need to put TLS in front of port 3000. The cleanest path is Caddy on the same host:

```caddy
# /etc/caddy/Caddyfile
your-domain.example.com {
    reverse_proxy localhost:3000
}
```

Caddy auto-provisions a Let's Encrypt cert. Point an A record at the VPS, give it ~60s, and the cert is live.

The `/v1/websocket` upgrade works through `reverse_proxy` with no extra config — Caddy handles the `Upgrade: websocket` header by default.

---

## 6. End-to-end verification (do this before the demo)

Repeat §5 of [01_LOCAL_TEST.md](01_LOCAL_TEST.md) — but with **device A on Wi-Fi** and **device B on cellular**, both pointing at the cloud URL. The flow that *must* succeed:

1. Issue invite on A → QR appears.
2. Scan on B → mission appears as PENDING with A's fingerprint.
3. Confirm on A → B flips to ACTIVE within ~1s (WebSocket push).
4. Send a message in a channel from A → B receives it (real-time).

If step 3 takes more than a few seconds, you're hitting the cold-start / idle-suspend problem from §4 — switch off auto-stop on Fly, or pre-warm Render with a `curl /health`.

---

## 7. Cost & teardown

| Host | Idle cost (est.) | Demo-day cost |
|---|---|---|
| Fly.io (1 shared VM + free pg) | $0/mo | $0 if stays in free tier |
| Render (free web + free pg) | $0/mo, sleeps idle | $0 |
| $5 VPS + Caddy | $5/mo | $5 |

Teardown when the capstone is graded:

```powershell
# Fly
fly apps destroy astrasecure-relay
fly apps destroy astrasecure-db

# Render — delete services from the dashboard
# VPS — destroy the droplet/instance
```

Rotate `JWT_SECRET` on every redeploy so old tokens don't carry over between environments.
