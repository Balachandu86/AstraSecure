# Simple local test

The 5-minute version of [01_LOCAL_TEST.md](01_LOCAL_TEST.md). Use this when you just want the relay running and two emulators talking to it. No QR-flow walkthrough, no troubleshooting tables.

---

## 1. Start the relay

From [server/](../../../server/):

```powershell
cd server
$env:JWT_SECRET = "local-dev-" + [Guid]::NewGuid().ToString("N")  # optional, but better than the default
docker compose up --build -d
docker compose logs -f relay
```

Wait for `AstraSecure server listening on 0.0.0.0:3000`, then `Ctrl+C` to detach from logs (the container stays up).

Sanity check:

```powershell
curl http://localhost:3000/health
```

Should return `{"status":"ok",...}`.

---

## 2. Run two emulators

Open Android Studio → **Device Manager** → start **two** AVDs. The debug build's `SIGNAL_SERVER_URL` is hardcoded to `http://10.0.2.2:3000` (see [app/build.gradle.kts](../../../app/build.gradle.kts)), which both emulators resolve to the host machine — no config needed.

Build & run on each AVD:

```powershell
.\gradlew installDebug
```

(or just hit Run in Android Studio twice, picking a different emulator each time)

Provision both — first user becomes admin / CHIEF on the seeded mission.

---

## 3. Try the invite — token-paste (recommended for two emulators)

QR scanning between two emulator windows is awkward (the AVD camera is a virtual scene, not a screen-share). The app already exposes a token/link path that works perfectly across emulators.

On **emulator A** (the CHIEF):

1. Open the seeded mission → tap **Generate invite**. A QR appears, but you can ignore it.
2. The screen shows the token in monospace, e.g. `TOKEN  XK4F-9N2P-...`. Tap **COPY** (it copies the bare token to A's clipboard).

On **emulator B**:

1. Tap **Redeem invite**. The screen has two paths — ignore "Scan invite QR" and go straight to the **PASTE TOKEN** field.
2. Type the token from A's screen, then tap **REDEEM**.
3. The mission should appear on B as **PENDING**.

Back on **emulator A**:

1. The pending list updates live (WebSocket push).
2. Confirm B → the mission flips to **ACTIVE** on B within a second.

That's the full flow.

> The redeem field also accepts the full `astrasecure://invite/<TOKEN>` URI, not just the bare token — `InviteUri.decode` strips the scheme either way. Useful if you ever wire up a deep-link share intent.

### 3a. Skip the retype with adb

If you'd rather not eyeball the token across two emulator windows, push it directly into B's clipboard from the host:

```powershell
# list devices to get the two AVD serials
adb devices

# replace <token> with what you read from A, <emuB-serial> with B's serial
adb -s <emuB-serial> shell "service call clipboard 2 i32 1 i32 0 i32 0 s16 '<token>'"
```

Then tap **PASTE** in B's redeem screen. (Cleaner alternative: just `adb -s <emuB-serial> shell input text "<token>"` while the field is focused.)

---

## 4. Stop everything

```powershell
docker compose down       # keeps DB data
docker compose down -v    # wipes DB (use after schema edits)
```

---

## 5. If you specifically want to test the QR scan path

The token-paste path in §3 covers the whole server-side flow, but it doesn't exercise the camera + ZXing decoder. To test that:

- **One emulator + one physical phone on the same Wi-Fi** is the cleanest setup. Find your laptop's LAN IP (`ipconfig`), change the `debug` block in [app/build.gradle.kts](../../../app/build.gradle.kts) to `"http://<lan-ip>:3000"`, add that IP as a cleartext-permitted domain in [network_security_config.xml](../../../app/src/main/res/xml/network_security_config.xml), rebuild. Issue on the emulator, scan the QR off your laptop screen with the phone's camera.

- **Lost the token?** Pull the most recent one straight from Postgres:

  ```powershell
  docker compose exec db psql -U postgres -d astrasecure -c "SELECT token FROM invites WHERE redeemed_at IS NULL AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1;"
  ```

---

## 6. Quick DB peek

```powershell
docker compose exec db psql -U postgres -d astrasecure
```

```sql
SELECT user_id, display_name FROM users;
SELECT mission_id, user_id, status FROM mission_participants;
SELECT token, redeemed_by, expires_at FROM invites ORDER BY created_at DESC LIMIT 5;
```

That's it. For anything weirder, jump to [01_LOCAL_TEST.md](01_LOCAL_TEST.md).
