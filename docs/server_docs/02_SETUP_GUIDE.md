# Server Setup Guide — Ubuntu 22.04 LTS

This guide covers a full bare-metal / VPS setup. The same steps work on any Ubuntu 22.04 instance — DigitalOcean, Hetzner, Oracle Cloud, a local VM, or WSL2 on your dev machine.

By the end you will have:
- PostgreSQL 16 running and schema applied
- Node.js 20 installed
- The AstraSecure relay server running on port 3000
- A `systemd` service that auto-starts on reboot
- UFW firewall configured

---

## 1. Initial server hardening

SSH into your new VPS as root (or the default user, e.g. `ubuntu` on AWS/Oracle).

```bash
# Update packages
apt update && apt upgrade -y

# Create a non-root user (skip if your provider already created one)
adduser astra
usermod -aG sudo astra

# Copy SSH key to the new user (run this on your LOCAL machine)
# ssh-copy-id astra@YOUR_SERVER_IP

# Disable root SSH login (optional but recommended)
sed -i 's/^PermitRootLogin yes/PermitRootLogin no/' /etc/ssh/sshd_config
systemctl restart sshd
```

From here on, run all commands as `astra` (or your non-root user).

---

## 2. Install PostgreSQL 16

Ubuntu 22.04's default apt repo ships PostgreSQL 14. Use the official PostgreSQL apt repo to get 16.

```bash
# Add official PostgreSQL apt repo
sudo apt install -y curl ca-certificates
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
  --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc

sudo sh -c 'echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
  https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
  > /etc/apt/sources.list.d/pgdg.list'

sudo apt update
sudo apt install -y postgresql-16

# Verify installation
psql --version   # should print: psql (PostgreSQL) 16.x
```

### Create the database and user

```bash
# Switch to the postgres system user
sudo -u postgres psql

-- Inside psql shell:
CREATE USER astra_user WITH PASSWORD 'choose-a-strong-password';
CREATE DATABASE astrasecure OWNER astra_user;
GRANT ALL PRIVILEGES ON DATABASE astrasecure TO astra_user;
\q
```

### Verify connection

```bash
psql "postgres://astra_user:choose-a-strong-password@localhost:5432/astrasecure" -c "SELECT version();"
```

You should see a PostgreSQL version string. If it fails, check that PostgreSQL is running:

```bash
sudo systemctl status postgresql
sudo systemctl enable postgresql   # enable auto-start on reboot
```

---

## 3. Install Node.js 20 LTS

Use the NodeSource binary distribution — do not use the Ubuntu default repo (`nodejs` in Ubuntu 22.04 is v12).

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# Verify
node --version    # v20.x.x
npm --version     # 10.x.x
```

---

## 4. Deploy the server code

### Option A — Clone the full Capstone repo

If the Capstone repo is on GitHub:

```bash
cd ~
git clone https://github.com/YOUR_ORG/Capstone.git
cd Capstone/server
```

### Option B — Copy just the server folder

From your **local machine**, use `scp` to copy the server directory:

```bash
# Run this on your LOCAL machine
scp -r /path/to/Capstone/server astra@YOUR_SERVER_IP:~/server
```

Then on the server:

```bash
cd ~/server
```

### Install dependencies

```bash
npm install --omit=dev
```

---

## 5. Configure environment variables

```bash
cp .env.example .env
nano .env    # or: vim .env
```

Fill in the values:

```env
DATABASE_URL=postgres://astra_user:choose-a-strong-password@localhost:5432/astrasecure
JWT_SECRET=paste-a-long-random-string-here
PORT=3000
```

**Generate a secure JWT secret:**

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

Copy the output and paste it as `JWT_SECRET`. This secret signs every JWT token — keep it private and never commit it to git.

### Protect the .env file

```bash
chmod 600 .env
```

---

## 6. Run the server (test)

```bash
node index.js
```

Expected output:

```
Schema applied.
TLS not configured — plain HTTP (dev only).
AstraSecure relay server listening on :3000
  Health check: http://localhost:3000/health
  WebSocket:    ws://localhost:3000/v1/websocket
```

Test the health endpoint from another terminal (or your local machine):

```bash
curl http://YOUR_SERVER_IP:3000/health
# {"status":"ok","ts":"2026-04-30T..."}
```

Press `Ctrl+C` to stop. The next step sets it up to run permanently.

---

## 7. Run as a systemd service

Create a service unit file so the server restarts automatically on reboot or crash.

```bash
sudo nano /etc/systemd/system/astrasecure.service
```

Paste the following (adjust paths if your user or directory differs):

```ini
[Unit]
Description=AstraSecure Signal Relay Server
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=astra
WorkingDirectory=/home/astra/server
EnvironmentFile=/home/astra/server/.env
ExecStart=/usr/bin/node index.js
Restart=on-failure
RestartSec=5s
StandardOutput=journal
StandardError=journal
SyslogIdentifier=astrasecure

[Install]
WantedBy=multi-user.target
```

Enable and start the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable astrasecure
sudo systemctl start astrasecure

# Check status
sudo systemctl status astrasecure

# Live logs
sudo journalctl -u astrasecure -f
```

If the service fails to start, check logs with `journalctl -u astrasecure -n 50`.

---

## 8. Configure the firewall (UFW)

```bash
# Install UFW if not already present
sudo apt install -y ufw

# Allow SSH (IMPORTANT — do this before enabling UFW or you will lock yourself out)
sudo ufw allow OpenSSH

# Allow the relay server port
sudo ufw allow 3000/tcp      # plain HTTP (dev)
# sudo ufw allow 443/tcp     # HTTPS (production — see 03_TLS_CONFIGURATION.md)

# Enable firewall
sudo ufw enable

# Verify
sudo ufw status
```

Expected output:

```
Status: active

To                         Action      From
--                         ------      ----
OpenSSH                    ALLOW       Anywhere
3000/tcp                   ALLOW       Anywhere
```

---

## 9. Verify end-to-end from an Android emulator

The emulator uses `10.0.2.2` to reach the host machine. If your server is on a **remote VPS**, update the debug build config in [app/build.gradle.kts](../../app/build.gradle.kts):

```kotlin
debug {
    buildConfigField("String", "SIGNAL_SERVER_URL", "\"http://YOUR_SERVER_IP:3000\"")
}
```

Then rebuild the app and run the provisioning flow. After provisioning completes:

1. Check server logs: `sudo journalctl -u astrasecure -f`
2. You should see: `[register] userId=... displayName=... opks=100`
3. Check the database:

```bash
psql "postgres://astra_user:password@localhost:5432/astrasecure" \
  -c "SELECT user_id, display_name FROM users;"
```

---

## 10. Quick reference — useful commands

```bash
# View live server logs
sudo journalctl -u astrasecure -f

# Restart server (e.g. after updating .env or index.js)
sudo systemctl restart astrasecure

# Stop server
sudo systemctl stop astrasecure

# Check database tables
psql "postgres://astra_user:password@localhost:5432/astrasecure" -c "\dt"

# Count users registered
psql "postgres://astra_user:password@localhost:5432/astrasecure" \
  -c "SELECT COUNT(*) FROM users;"

# Count queued messages
psql "postgres://astra_user:password@localhost:5432/astrasecure" \
  -c "SELECT COUNT(*) FROM message_queue;"

# Manually clear the message queue (dangerous — only for debugging)
# psql "..." -c "TRUNCATE message_queue;"
```

---

## Troubleshooting

### `ECONNREFUSED` on port 3000

- Check service is running: `sudo systemctl status astrasecure`
- Check firewall: `sudo ufw status`
- Check that `.env` `PORT` matches the UFW rule

### `password authentication failed for user "astra_user"`

- Re-check the password in `.env` matches what you set in `psql`
- Make sure `DATABASE_URL` uses `localhost`, not `127.0.0.1`, unless `pg_hba.conf` is configured for both

### `FATAL: role "astra_user" does not exist`

You may have run `psql` as the wrong system user. Always run database commands as:
```bash
sudo -u postgres psql
```

### Node.js `MODULE_NOT_FOUND`

Run `npm install` again from inside the `server/` directory.

### WebSocket connections drop immediately

This usually means the JWT in the query param is invalid or expired. Check that the Android app is using the token returned from the registration endpoint and that `JWT_SECRET` has not changed between restarts. The `.env` `EnvironmentFile` in systemd should keep it stable across restarts.
