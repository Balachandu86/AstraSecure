# Cloud Deployment — No VPS (Supabase + Railway / Render / Koyeb)

This guide deploys the AstraSecure relay server entirely on managed cloud platforms — no Linux administration, no SSH, no firewall rules, no TLS certificate setup. Everything runs on free or near-free tiers and gives you a public `https://` URL the Android app can reach from anywhere.

**Stack:**
- **Supabase** — managed PostgreSQL (free, permanent, 500 MB)
- **Railway** — managed Node.js host (easiest, ~$2–3/mo within free $5 credit)
- _Alternatives if you prefer:_ Render (free web service) or Koyeb (free, no sleep)

Both platforms provide HTTPS by default — no certificate work required.

---

## Architecture

```
Android App
    │
    │  https://  (TLS provided by Railway/Render/Koyeb)
    ▼
Node.js relay (Railway / Render / Koyeb)
    │
    │  SSL connection (Supabase enforces TLS on all connections)
    ▼
PostgreSQL 15 (Supabase managed database)
```

---

## Part 1 — Set up Supabase (database)

### 1.1 Create a Supabase project

1. Go to [supabase.com](https://supabase.com) and sign up (GitHub login works)
2. Click **New project**
3. Fill in:
   - **Name:** `astrasecure`
   - **Database password:** choose a strong password — save it, you will need it in a moment
   - **Region:** pick the region closest to your users (Singapore for South/East Asia, Mumbai is not available — Singapore is closest)
4. Click **Create new project** and wait ~2 minutes for provisioning

### 1.2 Get the connection string

1. In the Supabase dashboard, go to **Project Settings** (gear icon, bottom-left) → **Database**
2. Scroll to **Connection string** section
3. Select the **URI** tab
4. You will see something like:
   ```
   postgresql://postgres:[YOUR-PASSWORD]@db.abcdefghijkl.supabase.co:5432/postgres
   ```
5. Replace `[YOUR-PASSWORD]` with the database password you set in step 1.3
6. Copy this string — it becomes `DATABASE_URL` in your server config

> **Important:** Use the **direct connection** string (port **5432**), not the pooled connection (port 6543).
> The relay server uses `FOR UPDATE SKIP LOCKED` in the OPK atomic pop query, which requires
> a direct connection. The transaction-mode pooler on port 6543 does not support this.

### 1.3 Apply the schema

The `index.js` server applies `schema.sql` automatically on first startup using `CREATE TABLE IF NOT EXISTS`. No manual work is needed — the schema will be created when the server first connects.

If you want to inspect or pre-create the tables manually, you can also run the schema through Supabase's SQL Editor:

1. In the Supabase dashboard, click **SQL Editor** (left sidebar)
2. Click **New query**
3. Open `server/schema.sql` from your local project and paste the contents
4. Click **Run** — you should see `Success. No rows returned` for each statement

### 1.4 Verify the database is accessible

In the Supabase dashboard → **Table Editor**, you should see the five tables listed after schema is applied: `users`, `signed_prekeys`, `one_time_prekeys`, `message_queue`, `channel_members`.

---

## Part 2A — Deploy Node.js server on Railway (recommended)

Railway is the easiest path: connect GitHub, set two environment variables, deploy. Takes about 5 minutes.

### 2A.1 Push the server to GitHub

Railway deploys from a GitHub repository. If the Capstone repo is already on GitHub, skip this step.

If not, push it:
```bash
cd /path/to/Capstone
git add .
git commit -m "add signal relay server"
git remote add origin https://github.com/YOUR_USERNAME/Capstone.git
git push -u origin master
```

### 2A.2 Create a Railway project

1. Go to [railway.app](https://railway.app) and sign up (GitHub login recommended)
2. Click **New Project** → **Deploy from GitHub repo**
3. Select your Capstone repository
4. Railway will detect the repo — click **Add variables** before it deploys (next step)

### 2A.3 Set environment variables

In the Railway project, click your service → **Variables** tab → **New Variable**:

| Key | Value |
|---|---|
| `DATABASE_URL` | The Supabase connection string from Part 1.2 |
| `JWT_SECRET` | A long random string (generate below) |
| `PORT` | `3000` |

Generate a JWT secret locally:
```bash
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

### 2A.4 Set the root directory

Because the `server/` folder is inside a larger Android project, you need to tell Railway where the Node.js code lives:

1. Click your service → **Settings** tab
2. Under **Source**, set **Root Directory** to `server`
3. Railway will now run `npm install` and `npm start` from inside `server/`

### 2A.5 Deploy

Click **Deploy** (or it may have already started). Watch the build logs — a successful deploy ends with:

```
Schema applied.
TLS not configured — plain HTTP (dev only).
AstraSecure relay server listening on :3000
```

> Railway terminates TLS at its edge — the Node.js process itself runs HTTP internally, which is correct. The public URL Railway gives you is `https://`.

### 2A.6 Get your public URL

In the Railway dashboard → your service → **Settings** → **Networking** → **Generate Domain**.

Railway gives you a URL like:
```
https://astrasecure-relay-production.up.railway.app
```

Test it:
```
https://astrasecure-relay-production.up.railway.app/health
```

Expected response: `{"status":"ok","ts":"..."}`

---

## Part 2B — Deploy on Render (free alternative)

Render's free web service has no cost at all, though it spins down after 15 minutes of inactivity (first request after spin-down takes ~30 seconds to wake up). Fine for a graded demo.

### Steps

1. Go to [render.com](https://render.com) → **New** → **Web Service**
2. Connect your GitHub repo
3. Configure:
   - **Root Directory:** `server`
   - **Build Command:** `npm install`
   - **Start Command:** `node index.js`
   - **Instance Type:** Free
4. Under **Environment Variables**, add:
   - `DATABASE_URL` → Supabase connection string
   - `JWT_SECRET` → your generated secret
5. Click **Create Web Service**

Render gives you a URL like `https://astrasecure-relay.onrender.com`. Test `/health`.

**Note on spin-down:** To prevent Render from sleeping your service, set up a free UptimeRobot monitor pinging `https://your-service.onrender.com/health` every 5 minutes. This keeps it warm.

---

## Part 2C — Deploy on Koyeb (free, no sleep)

Koyeb's free tier does not sleep — it keeps your service running permanently. This makes it the best free Node.js host when combined with Supabase.

### Steps

1. Go to [koyeb.com](https://www.koyeb.com) and sign up
2. Click **Create App** → **GitHub**
3. Select your Capstone repo
4. Configure the build:
   - **Build type:** Buildpack (auto-detected as Node.js)
   - **Working directory:** `server`
   - **Run command:** `node index.js`
5. Under **Environment variables**, add:
   - `DATABASE_URL` → Supabase connection string
   - `JWT_SECRET` → your generated secret
   - `PORT` → `8000` (Koyeb's default exposed port — also update this in `package.json` if needed, or just set `PORT=8000` and Koyeb handles the rest)
6. **Instance:** Free (nano)
7. Click **Deploy**

Koyeb provides a URL like `https://astrasecure-relay-yourname.koyeb.app`. Test `/health`.

---

## Part 3 — Wire the Android app

All three platforms (Railway, Render, Koyeb) provide `https://` URLs out of the box. No changes to `network_security_config.xml` are needed — the certificates are issued by trusted CAs and Android verifies them automatically.

### 3.1 Update the server URL

Open [app/build.gradle.kts](../../app/build.gradle.kts) and replace the server URL in both build types:

```kotlin
buildTypes {
    debug {
        // Replace with your actual Railway / Render / Koyeb URL
        buildConfigField("String", "SIGNAL_SERVER_URL",
            "\"https://astrasecure-relay-production.up.railway.app\"")
    }
    release {
        buildConfigField("String", "SIGNAL_SERVER_URL",
            "\"https://astrasecure-relay-production.up.railway.app\"")
    }
}
```

### 3.2 Rebuild and test

1. In Android Studio: **Build → Clean Project** then **Build → Rebuild Project**
2. Run the app on your device or emulator
3. Go through the provisioning flow — enter a callsign and confirm

**Verify on Supabase:**
1. Supabase dashboard → **Table Editor** → `users` table
2. You should see your device's user row with `display_name` matching your callsign and 100 rows in `one_time_prekeys`

**Verify on Railway/Render/Koyeb:**
- Check the deployment logs — look for:
  ```
  [register] userId=xxxxxxxx-xxxx-... displayName=ALPHA opks=100
  [ws] connected userId=xxxxxxxx... total=1
  ```

---

## Part 4 — Test messaging between two devices

### 4.1 Provision a second device

On a second phone or a second emulator instance, install the app and provision with a different callsign (e.g. `BRAVO`).

Both devices should now appear in Supabase → `users` table.

### 4.2 Send a message

1. Both devices navigate to the **same Mission → same Channel**
2. Device A (ALPHA) sends a message
3. Device B (BRAVO) should receive it in real time via WebSocket push

Watch the Railway/Render/Koyeb logs:
```
[ws] connected userId=<alice-uuid> total=1
[ws] connected userId=<bob-uuid>   total=2
[register] ... opks=100
```

Watch Supabase → `message_queue` table — rows appear briefly then disappear as devices ACK delivery.

---

## Supabase dashboard — useful views for debugging

| Dashboard section | What to check |
|---|---|
| **Table Editor → users** | Confirm devices have registered |
| **Table Editor → one_time_prekeys** | Should have ~100 rows per user; drops by 1 each time a new session is established |
| **Table Editor → message_queue** | Should stay near 0 in normal operation; a growing queue means WebSocket delivery is broken |
| **Table Editor → channel_members** | Confirm devices have joined channels |
| **Database → Logs** (under Reports) | PostgreSQL query logs — useful if you see `500` errors from the server |

---

## Cost summary

| Component | Platform | Cost |
|---|---|---|
| PostgreSQL | Supabase | **Free** (500 MB, permanent) |
| Node.js server | Railway | **~$2–3/mo** (within $5 free credit) |
| Node.js server | Render | **Free** (sleeps after 15 min) |
| Node.js server | Koyeb | **Free** (no sleep) |
| TLS / HTTPS | Included | **Free** (managed by platform) |
| **Total (Railway)** | | **~$0–3/mo** |
| **Total (Koyeb)** | | **$0/mo** |

---

## Troubleshooting

### `SIGNAL_PROVISION_FAILED: Unable to resolve host`

The server URL in `build.gradle.kts` is wrong or the deployment is not running. Check the deployment logs on Railway/Render/Koyeb and confirm `/health` returns 200.

### `SIGNAL_PROVISION_FAILED: SSL handshake`

Make sure the URL starts with `https://` not `http://`. All three platforms require HTTPS — plain HTTP will be rejected or redirected.

### Schema not created — `relation "users" does not exist`

The server failed to apply `schema.sql` on startup. Check logs for a startup error. Common cause: the Supabase connection string has the wrong password or uses the port 6543 pooler instead of the port 5432 direct connection. Apply the schema manually via the Supabase SQL Editor (paste contents of `server/schema.sql` and run).

### Messages queue up but never deliver to Device B

Device B is not connected via WebSocket. Check:
- Device B is provisioned (row in `users` table)
- Device B has opened the same channel (row in `channel_members`)
- Logcat on Device B filtered by tag `AppContainer` — look for `[ws] connected` log

### Railway: `Build failed — cannot find module`

Set **Root Directory** to `server` in Railway service settings. Without this, Railway looks for `package.json` in the repo root (which is an Android project and has none).

### Render: cold start too slow for demo

Add a free UptimeRobot monitor pinging `/health` every 5 minutes. This prevents the 15-minute inactivity sleep so the server is always warm when the demo runs.
