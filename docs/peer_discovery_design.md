# Peer Discovery — Design Options

How does User A get User B onto a mission **without** typing a 36‑character UUID?

Today: User A reads User B's UUID from a server log and types it into the Admin Console. This is fine for a developer, **unacceptable for a demo, and disqualifying for production**.

This document compares the realistic ways to fix this, against AstraSecure's threat model. The intent is to pick *one primary path* and *one fallback*, not to ship six features.

---

## 1. What "secure" has to mean here

AstraSecure is positioned as a hardened operations tool, not a consumer messenger. That changes which trade‑offs are acceptable:

| Constraint | Implication for discovery |
|------------|----------------------------|
| Operators are vetted, not random | Public global directories are *not* a goal — they're an anti‑goal. |
| Signal Protocol gives end‑to‑end secrecy only if **identity keys are verified** | Discovery must let User A verify User B's identity key, not just learn a UUID. |
| Metadata leakage matters | A lookup endpoint that maps callsigns → UUIDs is a metadata oracle for an attacker with stolen credentials. |
| Threat model includes **device compromise** | Anything stored on the device that lets an adversary impersonate or enumerate must be revocable. |
| Demo must work in a single room with two phones | "Out‑of‑band ceremony" must take seconds, not minutes. |

The "north star" is therefore: **no UUID is ever typed by a human, every contact is bound to a verified identity key, and the server never lets one user enumerate the rest.**

---

## 2. Options at a glance

| # | Approach | UX cost | Security | Demoable today | Remote onboarding |
|---|----------|---------|----------|----------------|-------------------|
| 1 | Callsign directory lookup | ⭐⭐⭐ Lowest | ⚠️ Enumeration risk | Yes | Yes |
| 2 | QR code identity exchange | ⭐⭐ Scan once | ✅ Strong (verifies IK) | Yes (in‑person) | No |
| 3 | NFC tap | ⭐⭐ Tap once | ✅ Strong (proximity) | Hardware permitting | No |
| 4 | Invite token / deep link | ⭐⭐ Paste/click | ✅ Strong (single‑use, scoped) | Yes | Yes |
| 5 | Numeric pairing code (SAS) | ⭐ Type 6 digits | ✅ Strong (interactive) | Yes (both online) | Yes |
| 6 | Admin‑driven enrollment code | ⭐⭐ Type code on first launch | ✅✅ Strongest (org‑gated) | Yes | Yes |
| 7 | Hybrid: invite link **+** QR ceremony | ⭐⭐ One scan or paste | ✅✅ Strongest practical | Yes | Yes |

The body of this document expands each one. Skip to **§10 Recommendation** if you only want the bottom line.

---

## 3. Option 1 — Callsign directory lookup

**What it is.** User B picks a unique callsign at provisioning. User A types `bravo_07`, the server returns User B's UUID and identity‑key fingerprint, the app displays the fingerprint for User A to confirm.

**How it would slot into what exists.** The server already stores `display_name` per user. Add a `UNIQUE` constraint on `users.display_name` (currently absent) and a new endpoint `GET /api/directory/{callsign}` returning `{ userId, identityKey }`. Wire that into the Admin Console "Add Participant" field as a callsign‑first lookup.

**Pros**
- Trivially familiar UX — same model as Signal phone numbers, Slack handles.
- No second device, no proximity required.
- Smallest code change of any option.

**Cons**
- **Enumeration**: any authenticated user can probe `/api/directory/{callsign}` for every plausible callsign and harvest the entire org. Rate limiting helps but does not eliminate it.
- **Squatting**: namespace is global; first registrant gets `cmdr_alpha` forever.
- **Phishing surface**: a typo'd callsign that *resolves* (because someone squatted it) silently maps to an attacker's identity key.
- **Spoofing on reissue**: if a user is removed and a new operator is given the same callsign, history is murky.

**Verdict.** Quick win for demos, **bad fit for production** unless paired with mandatory identity‑key fingerprint confirmation in the UI. Even then, the metadata leak from enumeration is real.

---

## 4. Option 2 — QR code identity exchange

**What it is.** User B's app shows a QR encoding `{userId, identityKeyFingerprint}`. User A scans with the camera. The app stores both atomically — the lookup *is* the trust ceremony, because the IK fingerprint is delivered through an unforgeable channel (a screen photographed by a camera in the same room).

**Pros**
- Strongest realistic trust assumption short of in‑person key signing — what Signal/Threema/WhatsApp use for "verify safety number".
- No server‑side directory; the relay never sees a discovery query.
- Works offline; identity exchange and verification are coupled.
- Handles key rotation cleanly: re‑show the QR.

**Cons**
- Requires physical proximity. **Will not work for remote operators.**
- Camera permission, lighting, screen glare in the field.
- For demos: needs two devices in the same room (fine for a presentation, awkward for a single‑laptop screen recording).

**Verdict.** Best **trust ceremony** there is. Should exist regardless of which discovery path is chosen, as the verification step.

---

## 5. Option 3 — NFC tap

**What it is.** Two phones tap; the same payload as the QR is exchanged via NDEF.

**Pros**
- One‑gesture UX, slightly better than QR.
- Same security properties as QR (proximity is the trust anchor).

**Cons**
- Hardware variance — not all Android devices have NFC, and emulators don't.
- Adds a code path that's hard to test in CI.
- Does nothing QR doesn't already do, with strictly worse demo ergonomics.

**Verdict.** Skip unless a deployment target requires it. QR dominates.

---

## 6. Option 4 — Invite token / deep link

**What it is.** A CHIEF on a mission generates a single‑use invite token bound to (mission_id, expiry, optional initial rank). Token is delivered out‑of‑band — Signal, email, printed on a briefing sheet, whatever. The recipient pastes it into the app or clicks a deep link; the app calls `POST /api/invites/{token}/redeem`, the server adds them to the mission and pins the inviter's identity key.

**Pros**
- Solves remote onboarding, which QR cannot.
- Tokens are scoped: "join mission X as OBSERVER", not "you are now a contact".
- Single‑use and TTL‑bound — token‑in‑transit theft has a small window.
- Server never exposes user lookup; the only addressable surface is the token itself.
- Audit trail is clean — every membership has a redemption record.

**Cons**
- Out‑of‑band channel is a soft assumption — if the channel is compromised the attacker can redeem first.
- Mitigation: bind the token to the redeemer's identity key on first contact and require the inviter to confirm a 4‑digit code shown on both screens after redemption. Now interception alone isn't enough.
- One more endpoint, one more table (`invites`).

**Verdict.** Strongest *production* answer. Pairs naturally with QR for in‑person and remains usable when in‑person is impossible.

---

## 7. Option 5 — Numeric pairing code (SAS)

**What it is.** Both users open a "pair" screen at the same time. Each sees a short numeric code derived from a Diffie‑Hellman exchange (Short Authentication String, like Bluetooth/Magic Wormhole). They speak the code aloud or compare on screens. Match → contact added.

**Pros**
- Strong cryptographic guarantee (SAS over ECDH).
- No server directory, no QR, no NFC.
- Used by mature systems (Magic Wormhole, Apple Continuity).

**Cons**
- Requires both users to be online at the same time and coordinated.
- More moving parts than QR for the same in‑person scenario.
- Doesn't fit the asynchronous "I'll send you an invite, redeem when you can" pattern.

**Verdict.** Useful for live remote pairing where QR isn't possible. Niche compared to invite‑token + QR.

---

## 8. Option 6 — Admin‑driven enrollment code

**What it is.** The closest analogue to actual military/enterprise provisioning. An org admin creates a user record on the server in advance, generating a one‑time enrollment code. Issued on a card or read out at briefing. The operator enters it on first launch; it both authenticates them as "this real person" *and* binds their freshly‑generated identity key to that record.

**Pros**
- Strongest org control — random people cannot self‑provision into your system.
- Operator and identity binding happen in the same step.
- No public directory exists at any point.
- Maps cleanly onto AstraSecure's positioning as an *operations* tool, not a social app.

**Cons**
- Requires an admin UI for issuing codes.
- Provisioning friction is higher (someone has to issue the code).
- Not appropriate for ad‑hoc field formation of new missions among already‑provisioned operators — solves a different problem (initial onboarding) than the others (joining a mission once provisioned).

**Verdict.** This is a **complement** to the discovery question, not a substitute for it. It controls **who can have an account at all**; it doesn't control how an existing operator gets pulled into a mission.

---

## 9. Option 7 — Hybrid: Invite link **+** QR verification

This is what every serious system actually ships. The two mechanisms cover each other's weaknesses:

- **In person** → QR scan does discovery and trust verification in one step.
- **Remote** → CHIEF issues an invite token, sends it through any reasonable channel, redeemer pastes it. After redemption, the app shows both sides a 4‑digit confirmation code derived from the redeemer's IK — CHIEF taps "confirm" to commit; without that confirmation the new participant lands in a "pending" state.

**Pros (additive)**
- Covers every realistic onboarding scenario without any global directory.
- The "pending until confirmed" state means a stolen token alone cannot get someone into a mission.
- Compatible with admin‑driven enrollment (Option 6) for the *account creation* step — they solve different problems.

**Cons (additive)**
- Two flows to implement and document.
- Slightly more UI than a single‑mechanism choice.

**Verdict.** Recommended.

---

## 10. Recommendation

Adopt **Option 7 (invite link + QR verification)** as the primary path. Add **Option 6 (admin enrollment codes)** later if you ever expose AstraSecure beyond a closed pilot.

Concretely, for the next milestone:

1. **Drop the raw‑UUID input field.** No UI in the app should ever ask for a UUID. That alone removes the worst of the demo embarrassment.

2. **Implement invite tokens** as the default path:
   - New table `invites(token, mission_id, issued_by, default_rank, expires_at, redeemed_by, redeemed_at)`.
   - `POST /api/missions/:id/invites` (CHIEF only) → returns a short opaque token.
   - `POST /api/invites/:token/redeem` → adds caller as participant, returns the inviter's identity‑key fingerprint.
   - App: a **CHIEF** sees a "Generate invite" button on a mission; tapping shows the token + a copy‑to‑clipboard and a "Show as QR" toggle. The invitee opens the app, taps "Redeem invite", pastes/scans, and lands in the mission in a `PENDING` state.
   - CHIEF sees the pending operator with the redeemer's identity‑key fingerprint and a 4‑digit confirmation derived from it; one tap confirms.

3. **Add the QR fallback** for in‑person sessions:
   - Each operator's profile screen has a "My identity QR" panel — encodes `{userId, identityKey}`, never anything secret.
   - The Admin Console's "Add operator" sheet has a "Scan QR" option that fills in both fields atomically and skips the pending state (the QR scan *is* the verification).

4. **Never expose** a `GET /api/directory/{callsign}`‑style endpoint. Callsigns remain a display label, not an addressable identifier on the wire.

5. **Audit log** every invite issuance and redemption with `mission_id` so the existing audit_events table tells the full story.

The total surface added is one table, three endpoints, two screens, and one camera permission. In return, the demo no longer involves reading a server log out loud.

---

## 11. What this still does not solve

So that future‑you doesn't think this document covered something it didn't:

- **Compromised inviter device.** If a CHIEF's device is taken, an attacker can issue invites. Mitigation lives in §6 of the ideal‑schema document — `MissionStatus.COMPROMISED` should freeze invite issuance.
- **Identity key rotation.** None of the options above re‑verify after a key rotation. A "your peer's identity changed" warning UI is required (Signal calls this a "safety number changed" notification).
- **Cross‑device for the same user.** Discovery between *two operators* is not the same problem as multi‑device support for *one operator*. That is a separate design.
- **Account creation governance.** Invite tokens decide who joins a mission, not who exists in the system at all. If you need to control account creation, that's Option 6.
