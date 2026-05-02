# Environment Variables Reference

All configuration for the relay server is read from the `.env` file in the `server/` directory (or from the process environment on platforms like Fly.io and Railway where secrets are injected at deploy time).

The server reads `.env` automatically at startup via `dotenv`. Values set in the actual process environment override `.env` values.

---

## Required variables

### `DATABASE_URL`

PostgreSQL connection string.

| Format | `postgres://USER:PASSWORD@HOST:PORT/DBNAME` |
|---|---|
| Local dev | `postgres://astra_user:secret@localhost:5432/astrasecure` |
| Docker | `postgres://postgres:secret@localhost:5432/astrasecure` |
| Fly.io | Set automatically by `fly postgres attach` |
| Railway | Set automatically when PostgreSQL plugin is added |
| Render | Copy the "Internal Database URL" from the Render PG dashboard |

**Never use the external/public connection string** for a server connecting to its own database — always use the internal/localhost string for lower latency and no egress cost.

---

### `JWT_SECRET`

HMAC-SHA256 key used to sign and verify JWT tokens issued during registration.

| Requirement | At least 32 random bytes (48+ recommended) |
|---|---|
| Generate | `node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"` |
| Example | `a3f8c9...` (96-character hex string) |

**Security rules:**
- Never commit this to git
- Never use the default `change-me-in-production` string in any non-local environment
- Changing this value invalidates all existing tokens — all registered devices will need to re-register
- Use the same value across restarts (the systemd `EnvironmentFile` handles this automatically)

---

## Optional variables

### `PORT`

TCP port the HTTP/HTTPS server listens on.

| Default | `3000` |
|---|---|
| Production HTTP | `80` (requires running as root or using `authbind`) |
| Production HTTPS | `443` (requires running as root or using `authbind`) |
| Preferred pattern | Keep `PORT=3000` and put Nginx/Caddy in front as a reverse proxy |

**Tip:** Running Node.js directly on port 443 requires root privileges or `authbind`. The simpler and more secure pattern is to run Node on port 3000 and put Nginx or Caddy in front to terminate TLS and proxy to port 3000 (see `05_REVERSE_PROXY.md`).

---

### `TLS_CERT`

Absolute path to the TLS certificate file (PEM format).

| Example | `/etc/letsencrypt/live/astra.yourdomain.com/fullchain.pem` |
|---|---|
| Self-signed | `/home/astra/server/certs/server.crt` |

When both `TLS_CERT` and `TLS_KEY` are set, the server starts as `https.createServer` instead of `http.createServer`. The WebSocket endpoint becomes `wss://` automatically.

Omit this variable (leave it blank or absent) for plain HTTP during development.

---

### `TLS_KEY`

Absolute path to the TLS private key file (PEM format). Must match `TLS_CERT`.

| Example | `/etc/letsencrypt/live/astra.yourdomain.com/privkey.pem` |
|---|---|
| Self-signed | `/home/astra/server/certs/server.key` |

Protect this file: `chmod 600 server.key`. The `astra` system user (or whichever user runs the Node process) must be able to read it.

---

## Full `.env` examples

### Local development (emulator)

```env
DATABASE_URL=postgres://astra_user:localpass@localhost:5432/astrasecure
JWT_SECRET=dev-secret-not-for-production
PORT=3000
```

### Production with Let's Encrypt

```env
DATABASE_URL=postgres://astra_user:strong-password@localhost:5432/astrasecure
JWT_SECRET=a3f8c92d1b0e7f4a6c5d8e2f1a9b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3
PORT=443
TLS_CERT=/etc/letsencrypt/live/astra.yourdomain.com/fullchain.pem
TLS_KEY=/etc/letsencrypt/live/astra.yourdomain.com/privkey.pem
```

### Production with self-signed cert (IP-only)

```env
DATABASE_URL=postgres://astra_user:strong-password@localhost:5432/astrasecure
JWT_SECRET=a3f8c92d1b0e7f4a6c5d8e2f1a9b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3
PORT=3000
TLS_CERT=/home/astra/server/certs/server.crt
TLS_KEY=/home/astra/server/certs/server.key
```

### Fly.io / Railway (secrets injected at runtime)

These platforms inject environment variables directly — no `.env` file on disk. Set them via their respective CLI or dashboard:

```bash
# Fly.io
fly secrets set JWT_SECRET=your-secret PORT=3000

# Railway
# Set in the Railway dashboard → Variables tab

# Render
# Set in the Render dashboard → Environment tab
```

On these platforms `DATABASE_URL` is injected automatically when you attach a managed PostgreSQL instance.

---

## PostgreSQL connection pool sizing

The default `pg.Pool` settings are fine for a low-traffic deployment. If you later scale up:

```javascript
// server/index.js — customize Pool if needed
const pool = new Pool({
  connectionString: DB_URL,
  max: 10,              // max simultaneous connections (default: 10)
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 5000,
});
```

For an Oracle Cloud Always Free instance (1 GB RAM), `max: 5` is safer since PostgreSQL defaults limit total connections to 100 and each connection uses ~5–10 MB of RAM.

---

## JWT token lifetime

Tokens are issued with a 30-day expiry (`{ expiresIn: '30d' }`). After expiry, the device must re-register. For a longer-lived deployment, increase this to `'1y'` or `'365d'` in `server/index.js`:

```javascript
const token = jwt.sign({ userId }, JWT_SECRET, { expiresIn: '365d' });
```

For a tighter security posture, reduce it to `'7d'` and implement a refresh endpoint (not currently in scope for this capstone).
