# Maintenance — Backups, Monitoring, and Updates

---

## Database backups

The relay server stores two classes of data that matter for recovery:

| Data | Criticality | Recovery strategy |
|---|---|---|
| `users` + `signed_prekeys` + `one_time_prekeys` | High | Devices re-register on next launch if lost. Loss means a temporary outage, not permanent data loss. |
| `message_queue` | Low | Messages are ephemeral — they are deleted after delivery ACK. Loss means a device misses messages sent while offline. |
| `channel_members` | Medium | Devices re-join channels on next `ChatViewModel` init. |

**Bottom line:** The server is a stateless relay. Losing the database is inconvenient but not catastrophic — devices self-heal by re-registering and re-joining channels. That said, regular backups are good practice.

### Automated daily backup with pg_dump

```bash
# Create a backup directory
mkdir -p ~/backups

# Write a backup script
nano ~/backup_astrasecure.sh
```

```bash
#!/bin/bash
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE=~/backups/astrasecure_${TIMESTAMP}.sql.gz

pg_dump "postgres://astra_user:YOUR_PASSWORD@localhost:5432/astrasecure" \
  | gzip > "$BACKUP_FILE"

echo "Backup created: $BACKUP_FILE ($(du -sh $BACKUP_FILE | cut -f1))"

# Keep only the last 7 days of backups
find ~/backups -name "*.sql.gz" -mtime +7 -delete
```

```bash
chmod +x ~/backup_astrasecure.sh

# Test it
~/backup_astrasecure.sh
```

Schedule it with cron (runs at 2 AM every day):

```bash
crontab -e
```

Add:

```
0 2 * * * /home/astra/backup_astrasecure.sh >> /home/astra/backups/backup.log 2>&1
```

### Restore from backup

```bash
gunzip -c ~/backups/astrasecure_20260430_020000.sql.gz \
  | psql "postgres://astra_user:YOUR_PASSWORD@localhost:5432/astrasecure"
```

---

## Log management

By default, `journald` retains logs indefinitely and can grow large. Configure a retention limit:

```bash
sudo nano /etc/systemd/journald.conf
```

Add or uncomment:

```ini
[Journal]
SystemMaxUse=500M
MaxRetentionSec=30day
```

```bash
sudo systemctl restart systemd-journald
```

View logs for the last 24 hours:

```bash
sudo journalctl -u astrasecure --since "24 hours ago"
```

---

## Monitoring the server

### Check if the service is healthy

```bash
# Service status
sudo systemctl status astrasecure

# HTTP health check
curl -s http://localhost:3000/health | python3 -m json.tool
# {"status": "ok", "ts": "..."}

# Count active WebSocket connections (approximate — counts established TCP connections to port 3000)
ss -tn state established '( dport = :3000 or sport = :3000 )' | wc -l
```

### Simple uptime monitoring (free)

If the server is public-facing, set up an external uptime monitor so you get alerted if it goes down:

| Service | Free tier | Setup |
|---|---|---|
| **UptimeRobot** | 50 monitors, 5-minute checks | Add `https://your-server/health` as an HTTP monitor |
| **Better Uptime** | 10 monitors | Add health endpoint, get Slack/email alerts |
| **StatusCake** | Unlimited public monitors | Similar to UptimeRobot |

UptimeRobot is the easiest: register at uptimerobot.com, add a new HTTP(s) monitor pointing at `https://your-server/health`, and it emails you when the server goes down.

### Database size monitoring

```bash
psql "postgres://astra_user:password@localhost:5432/astrasecure" -c "
  SELECT
    relname AS table,
    pg_size_pretty(pg_total_relation_size(relid)) AS size,
    n_live_tup AS rows
  FROM pg_stat_user_tables
  ORDER BY pg_total_relation_size(relid) DESC;
"
```

The `message_queue` table should stay near zero rows in normal operation (messages are deleted after delivery ACK). A growing queue indicates devices are not ACKing — investigate Logcat on the Android side.

---

## Updating the server

### Pull latest code

```bash
cd ~/Capstone   # or wherever you cloned the repo
git pull origin master

# Install any new npm dependencies
cd server
npm install --omit=dev

# Restart the service to pick up changes
sudo systemctl restart astrasecure
sudo systemctl status astrasecure
```

### Schema migrations

The `index.js` server re-applies `schema.sql` on every startup using `IF NOT EXISTS` for all `CREATE TABLE` statements. This means:

- **Adding a new table:** Add it to `schema.sql` with `CREATE TABLE IF NOT EXISTS`. It will be created on the next restart.
- **Adding a column to an existing table:** `CREATE TABLE IF NOT EXISTS` will not alter an existing table. You must run an `ALTER TABLE` manually in `psql`, then restart the server.
- **Renaming or dropping a column:** Do the DDL in `psql` manually, update any affected queries in `index.js`, then restart.

Example of adding a column manually:

```bash
psql "postgres://astra_user:password@localhost:5432/astrasecure" -c "
  ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ DEFAULT NOW();
"
sudo systemctl restart astrasecure
```

---

## Rotating the JWT secret

If the JWT secret is compromised, rotate it as follows:

1. Generate a new secret: `node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"`
2. Update `JWT_SECRET` in `~/.env`
3. Restart the server: `sudo systemctl restart astrasecure`
4. **All devices are now logged out** — tokens signed with the old secret are rejected
5. Each device will fail on the next server call (message send or WebSocket connect) and the app will need to re-provision

**Note for capstone:** Re-provisioning requires going through the provisioning flow again. In a production system you would implement a token refresh flow to avoid forcing all users through re-registration.

---

## Renewing the self-signed certificate (if used)

Self-signed certificates generated with the 3-year validity in `03_TLS_CONFIGURATION.md` are valid until 2029. When they expire:

```bash
cd ~/server/certs
# Generate a new cert (same command as initial setup)
openssl req -x509 -newkey rsa:4096 -sha256 -days 1095 -nodes \
  -keyout server.key -out server.crt \
  -subj "/CN=YOUR_SERVER_IP" \
  -addext "subjectAltName=IP:YOUR_SERVER_IP"

sudo systemctl restart astrasecure
```

Then re-compute the pin hash and update `network_security_config.xml` in the Android app (see `03_TLS_CONFIGURATION.md`). All devices need the new APK build.

Let's Encrypt certificates renew automatically via the Certbot systemd timer — no manual action needed.

---

## Quick maintenance checklist

Run this periodically (e.g. before a demo or a graded session):

```bash
# 1. Is the service running?
sudo systemctl is-active astrasecure   # should print: active

# 2. Does the health endpoint respond?
curl -s http://localhost:3000/health

# 3. Any errors in the last 100 log lines?
sudo journalctl -u astrasecure -n 100 | grep -i error

# 4. PostgreSQL running?
sudo systemctl is-active postgresql

# 5. Disk space OK?
df -h /

# 6. How many users registered?
psql "postgres://astra_user:password@localhost:5432/astrasecure" \
  -c "SELECT COUNT(*) AS users FROM users;"

# 7. Message queue backlog (should be near 0)
psql "postgres://astra_user:password@localhost:5432/astrasecure" \
  -c "SELECT COUNT(*) AS queued FROM message_queue;"
```
