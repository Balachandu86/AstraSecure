# Local test — QR invite flow

Goal: stand up the relay on your machine and exercise the full **issue → scan → redeem → confirm** invite flow with two devices.

This document supersedes the older `02_SETUP_GUIDE.md`. It reflects the architecture as of the current `master` branch.

---

## 1. What you're standing up

```
┌─────────────────────┐        ┌──────────────────────┐
│  Android (CHIEF)    │  HTTP  │   relay (Node 20)    │
│  emulator/device A  │◄──────►│   express + ws       │
└─────────────────────┘        │   port 3000          │
┌─────────────────────┐        │                      │
│  Android (operator) │  WSS   │   /v1/websocket      │
│  emulator/device B  │◄──────►│                      │
└─────────────────────┘        └──────────┬───────────┘
                                          │ pg
                                          ▼
                               ┌──────────────────────┐
                               │  postgres:16-alpine  │
                               │  schema auto-applied │
                               │  port 5432           │
                               └──────────────────────┘
```

- Server source: [server/index.js](../../../server/index.js)
- DB schema: [server/schema.sql](../../../server/schema.sql) — applied on every boot by `initSchema()` (idempotent).
- WebSocket pushes `sync_invalidated` frames so the issuer's PENDING list updates the moment an operator redeems an invite.

The QR-invite-relevant endpoints are:

| Method | Path | Purpose |
|---|---|---|
| POST   | `/api/missions/:id/invites` | CHIEF issues a single-use token (TTL hours) |
| GET    | `/api/missions/:id/invites` | CHIEF lists open invites |
| DELETE | `/api/invites/:token` | CHIEF revokes an unredeemed invite |
| POST   | `/api/invites/:token/redeem` | Operator redeems → lands as PENDING |
| POST   | `/api/missions/:id/participants/:userId/confirm` | CHIEF confirms after out-of-band SAS check |

---

## 2. Prerequisites

- Docker Desktop running (Windows: WSL2 backend).
- Android Studio with **two** AVDs configured (or one AVD + one physical device on the same Wi-Fi).
- Camera enabled on at least the device that will *redeem* (it scans the QR).
- A rank in `schema_ranks` with **`level >= 80`** is required to act as CHIEF — the seed data ships one.

---

## 3. Bring the stack up

From [server/](../../../server/):

```powershell
cd server

# Optional but recommended — set a non-default JWT secret
$env:JWT_SECRET = "local-dev-" + [Guid]::NewGuid().ToString("N")

docker compose up --build -d
docker compose ps
docker compose logs -f relay
```

Expected log lines from `relay`:

```
Schema applied.
TLS not configured — plain HTTP (dev only).
AstraSecure server listening on 0.0.0.0:3000
  Health:    http://localhost:3000/health
  Sync:      http://localhost:3000/api/sync
  WebSocket: ws://localhost:3000/v1/websocket
  LAN:       http://<your-lan-ip>:3000
```

Smoke check from the host:

```powershell
curl http://localhost:3000/health
# → {"status":"ok",...}
```

> [server/docker-compose.yml](../../../server/docker-compose.yml) is the only compose file in the repo. It mounts `schema.sql` into Postgres' `docker-entrypoint-initdb.d/`, publishes `5432` for host-side `psql`, sets `restart: unless-stopped`, and reads `JWT_SECRET` from the shell env with a fallback default.

---

## 4. Point the Android app at the relay

The base URL is compiled in via `BuildConfig.SIGNAL_SERVER_URL` — see [app/build.gradle.kts](../../../app/build.gradle.kts):

```kotlin
debug   → "http://10.0.2.2:3000"          // emulator host alias, plain HTTP
release → "https://your.server.host"      // edit before shipping
```

Cleartext to `10.0.2.2` is whitelisted in [network_security_config.xml](../../../app/src/main/res/xml/network_security_config.xml); the rest of the app is TLS-only.

### Device matrix

| Test setup | What to use | Notes |
|---|---|---|
| Two emulators on the same host | Default `10.0.2.2:3000` | Simplest. Both AVDs hit the same loopback. |
| Emulator + physical device | Emulator uses `10.0.2.2`, **device uses your LAN IP** | Override `SIGNAL_SERVER_URL` to `http://<lan-ip>:3000` for the device build, **and** add the LAN IP to `network_security_config.xml` as a cleartext-permitted domain. |
| Two physical devices | Both use `http://<lan-ip>:3000` | Same as above. Phone Wi-Fi must be on the same subnet as the host. |

To find the LAN IP, look at the relay startup line `LAN: http://<ip>:3000` or run `ipconfig`.

---

## 5. Walk the QR invite flow

### 5.1 Provision two operators

Build & install on **device A** and **device B**, then complete provisioning on each (creates the user, uploads identity key + signed pre-key + 100 one-time pre-keys).

Verify both landed in the DB:

```powershell
docker compose exec db psql -U postgres -d astrasecure -c "SELECT user_id, display_name, is_admin FROM users;"
```

### 5.2 Promote A to CHIEF on a mission

Provisioning seeds an initial mission and grants the first user CHIEF-level clearance. If you need to grant CHIEF manually:

```powershell
docker compose exec db psql -U postgres -d astrasecure
```

```sql
-- find your highest-level rank
SELECT id, name, level FROM schema_ranks ORDER BY level DESC LIMIT 1;

-- assign it to user A on mission M
INSERT INTO clearance_assignments (user_id, mission_id, rank_id)
VALUES ('<userA>', '<missionId>', '<rankId>')
ON CONFLICT (user_id, mission_id) DO UPDATE SET rank_id = EXCLUDED.rank_id;
```

Trigger a sync on device A (pull-to-refresh on the missions screen) so the new clearance lands client-side.

### 5.3 Issue an invite (device A)

1. Open the mission → tap "Invite" / generate QR.
2. Pick the default rank and TTL.
3. App calls `POST /api/missions/:id/invites`. Server returns `{ token, expiresAt }` and the screen renders a QR.

Confirm in the DB:

```sql
SELECT token, mission_id, default_rank, expires_at, redeemed_by
FROM invites
ORDER BY created_at DESC LIMIT 5;
```

### 5.4 Redeem (device B)

1. On the missions screen, choose "Redeem invite" → camera opens.
2. Scan device A's QR. App calls `POST /api/invites/:token/redeem`.
3. Device B should now show the mission as **PENDING** with device A's fingerprint visible.

Server-side audit:

```sql
SELECT severity, source, text, occurred_at
FROM audit_events
WHERE source IN ('Invites','Missions')
ORDER BY occurred_at DESC LIMIT 10;
```

### 5.5 Confirm after SAS (device A)

1. Read the SAS fingerprint shown on device B aloud; device A operator verifies it matches what the server returned.
2. On device A, open the mission's pending list → tap "Confirm" on user B. App calls `POST /api/missions/:id/participants/:userId/confirm`.
3. Server bumps `sync_versions` for both users. Both clients receive `sync_invalidated` over the WebSocket and refetch.
4. Device B's mission row flips from PENDING to ACTIVE.

Final check:

```sql
SELECT mission_id, user_id, status FROM mission_participants
WHERE mission_id = '<missionId>';
-- both rows should now read 'ACTIVE'
```

---

## 6. Common failures & how to triage

| Symptom | Likely cause | Fix |
|---|---|---|
| `ECONNREFUSED` from the app | Relay container not up, or device using wrong host | `docker compose ps`; verify `10.0.2.2` for emulator / LAN IP for physical |
| Issue endpoint returns `403 Forbidden` | Caller's clearance level < 80 on that mission | Grant CHIEF-level rank in `clearance_assignments` and resync |
| Redeem returns `410 Gone` | Token expired, revoked, or already redeemed | Check `invites` row — issue a fresh one |
| Pending list doesn't update on device A | WebSocket not connected | Logs should show `[ws] connected userId=...`. Check `ws://10.0.2.2:3000/v1/websocket` path is reachable |
| Schema looks wrong / missing column | Old volume from a previous build | `docker compose down -v` then `up --build -d` (wipes data) |
| Cleartext blocked in release build | Hitting plain HTTP from a release APK | Use a debug build for local testing; release builds require TLS |

---

## 7. Tearing down

```powershell
docker compose down          # stop, keep DB data
docker compose down -v       # stop and wipe pg_data volume
```

A wipe is the right move whenever you change `schema.sql` in a way that's *not* idempotent — `IF NOT EXISTS` guards it from breaking on restart but won't migrate existing data.
