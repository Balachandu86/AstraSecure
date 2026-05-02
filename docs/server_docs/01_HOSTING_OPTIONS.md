# Hosting Options — AstraSecure Relay Server

## Minimum hardware requirements

The relay server is a stateless HTTP/WebSocket process backed by PostgreSQL. It never touches plaintext — it only routes opaque ciphertext blobs. Throughput is very low (one database write per message sent). For a capstone demo or small team deployment the hardware floor is minimal.

| Resource | Minimum | Comfortable | Notes |
|---|---|---|---|
| vCPU | 1 | 1–2 | Node.js is single-threaded; one core is enough |
| RAM | 512 MB | 1 GB | PostgreSQL idle ≈ 30–50 MB; Node.js idle ≈ 60–80 MB |
| Storage | 5 GB | 10 GB | Schema is tiny; message queue is ephemeral (rows deleted after delivery ACK) |
| Bandwidth | 1 GB/mo | 5 GB/mo | Ciphertext blobs are ~500 B each; 1 GB handles ~2 million messages |
| OS | Ubuntu 22.04 LTS | Ubuntu 22.04 LTS | Any modern Linux works; instructions below target Ubuntu |

**TL;DR:** The cheapest tier from any provider is more than enough.

---

## Option A — Free tiers (zero cost)

### Oracle Cloud Always Free

The best free option for a persistent server. No credit card required for the Always Free tier.

**What you get:**
- 2 AMD micro VMs (1/8 OCPU, 1 GB RAM each) — **permanently free, never expires**
- Or up to 4 ARM-based Ampere A1 cores + 24 GB RAM pooled across 4 VMs — also free
- 200 GB block storage, 10 GB object storage, 10 TB outbound/month

**Best configuration for AstraSecure:**
- 1 Always Free AMD VM (1 GB RAM) for Node.js + PostgreSQL on the same instance
- Or split: 1 AMD VM for Node.js, 1 AMD VM for PostgreSQL

**Sign up:** https://cloud.oracle.com/free  
**Region tip:** Pick a region near your users. Mumbai or Hyderabad for India-based deployments.

**Limitations:**
- The free VMs are in a shared pool; occasionally unavailable during creation (retry in 5 minutes)
- Requires a credit card for identity verification, but you are **not** charged for Always Free resources

---

### Fly.io Hobby Plan

Fly.io runs Docker containers distributed globally. The free tier is generous and well-suited for a Node.js app.

**What you get (free, no card required to start):**
- 3 shared-CPU VMs (256 MB RAM each)
- 3 GB persistent volumes
- 160 GB outbound bandwidth/month

**Best configuration:**
- 1 Fly app for Node.js relay
- Managed PostgreSQL on Fly (free for 1 instance, 1 GB storage)

**Deploy command (after `flyctl auth login`):**
```bash
cd server
fly launch          # detects Node.js, creates fly.toml
fly postgres create # creates a free managed PG instance
fly postgres attach --app astrasecure-relay
fly deploy
```

Fly sets `DATABASE_URL` automatically after `attach`. Set `JWT_SECRET` via:
```bash
fly secrets set JWT_SECRET=your-secret-here
```

**Limitations:**
- App sleeps after 10 minutes of inactivity on the hobby plan — fine for demos, not for 24/7 use
- Upgrade to the paid plan ($1.94/mo for a dedicated VM) to disable sleep

---

### Render Free Tier

Render offers a free Web Service for Node.js deployments.

**What you get:**
- 1 free web service (512 MB RAM, shared CPU)
- Free PostgreSQL instance (90-day expiry — must upgrade to keep it)

**Deploy steps:**
1. Push the `server/` folder to a GitHub repo (or the whole Capstone repo)
2. Create a new **Web Service** on render.com, point it at the repo
3. Set **Root Directory** to `server`, **Build Command** to `npm install`, **Start Command** to `node index.js`
4. Create a **PostgreSQL** database on Render, copy the internal connection string to `DATABASE_URL`
5. Add `JWT_SECRET` in Environment Variables

**Limitations:**
- Free web service spins down after 15 minutes of inactivity; first request after spin-down takes ~30 seconds
- PostgreSQL free tier expires after 90 days (upgrade to $7/mo to keep)
- Not suitable for production; good for demos and grading

---

### Railway Starter Plan

Railway offers $5 of free credit per month — enough for a low-traffic demo server.

**What you get:**
- $5/mo credit (no card needed to start)
- Node.js + PostgreSQL on the same platform

**Deploy:**
1. Connect GitHub repo at railway.app
2. Add a **PostgreSQL** plugin — `DATABASE_URL` is auto-injected
3. Add `JWT_SECRET` as an environment variable
4. Railway auto-detects `package.json` and runs `npm start`

**Cost estimate:** A 512 MB Node.js service + PostgreSQL idles at about $2–3/mo — comfortably within the $5 free credit.

---

### Google Cloud Free Tier (e2-micro)

Google Cloud's Always Free includes one **e2-micro** instance in specific US regions (us-east1, us-central1, us-west1).

**What you get:**
- 1 e2-micro VM (2 vCPU burst, 1 GB RAM) — **always free in eligible regions**
- 30 GB HDD persistent disk
- 1 GB outbound network/month to most destinations

**Setup:** Standard Ubuntu 22.04 setup (see `02_SETUP_GUIDE.md`). PostgreSQL and Node.js both run on the same instance.

**Limitations:**
- Only free in US regions
- 1 GB egress is low — sufficient for a capstone demo, not production
- Requires a credit card for account creation

---

## Option B — Pay-as-you-go (cheap paid tiers)

These are the best value options when you need guaranteed uptime and no sleep timeouts.

### Hetzner Cloud — Best value in Europe

| Plan | vCPU | RAM | Storage | Price |
|---|---|---|---|---|
| CX11 | 1 AMD | 2 GB | 20 GB SSD | **€3.79/mo** |
| CX21 | 2 AMD | 4 GB | 40 GB SSD | €5.83/mo |

**Recommended:** CX11 — more than enough for AstraSecure.  
**Regions:** Nuremberg, Helsinki, Falkenstein (EU); Hillsboro OR (US); Singapore (Asia).  
**Sign up:** https://hetzner.com/cloud

**Why Hetzner:** Cheapest price-per-GB RAM in the industry. No hidden egress costs within Europe. Great for EU-based teams.

---

### DigitalOcean Droplets

| Plan | vCPU | RAM | Storage | Bandwidth | Price |
|---|---|---|---|---|---|
| Basic Shared | 1 | 512 MB | 10 GB SSD | 500 GB | **$4/mo** |
| Basic Shared | 1 | 1 GB | 25 GB SSD | 1 TB | $6/mo |

**Recommended:** $6/mo 1 GB Droplet.  
**Regions:** Bangalore, Singapore, NYC, London, Frankfurt.  
**Sign up:** https://digitalocean.com  
**Student benefit:** GitHub Student Pack includes $200 DigitalOcean credit.

---

### Vultr Cloud Compute

| Plan | vCPU | RAM | Storage | Bandwidth | Price |
|---|---|---|---|---|---|
| Cloud Compute | 1 | 512 MB | 10 GB NVMe | 0.5 TB | **$2.50/mo** |
| Cloud Compute | 1 | 1 GB | 25 GB NVMe | 1 TB | $5/mo |

**Recommended:** $5/mo 1 GB plan.  
**Regions:** Mumbai, Singapore, Tokyo, Frankfurt, NYC, London.

---

### Linode / Akamai Cloud

| Plan | vCPU | RAM | Storage | Transfer | Price |
|---|---|---|---|---|---|
| Nanode | 1 | 1 GB | 25 GB SSD | 1 TB | **$5/mo** |

**Recommended:** Nanode 1 GB — standard choice, well-documented, good India region (Mumbai).

---

## Comparison at a glance

| Provider | Cost | Always-on | Best for |
|---|---|---|---|
| Oracle Cloud Always Free | **Free** | Yes | Best free option; permanent |
| Fly.io Hobby | Free (sleeps) | No | Quick demos |
| Render Free | Free (sleeps, 90-day PG) | No | Graded demos |
| Railway | ~$2–3/mo (within $5 credit) | Yes | Easy CI/CD |
| Hetzner CX11 | €3.79/mo | Yes | Budget EU production |
| DigitalOcean $6 | $6/mo | Yes | DigitalOcean familiarity |
| Vultr $5 | $5/mo | Yes | Budget global |
| Linode Nanode | $5/mo | Yes | Reliable budget |

**Recommendation for this capstone:**
- **Demo / grading:** Fly.io or Render (free, deploy in < 10 minutes)
- **Persistent demo server:** Oracle Cloud Always Free (free forever, no sleep)
- **Long-term / team use:** Hetzner CX11 or DigitalOcean $6 Droplet
