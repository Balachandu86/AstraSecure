# 04 — The Ideal Schema (Outcome View)

This document is **not** a technical spec. It describes the schema in terms of the user‑visible capability it would unlock or block. Read it as: *"if we shaped the data this way, what could the app actually do — and what would still be impossible?"*

The current schema and what it enables today is described first. The proposed ideal model follows. Each section ends with a short table: **Enables** vs **Still impossible**.

---

## 1. Where we are today

The product wants to feel like a hardened operations console: missions are run by people who own them, channels have real owners, clearances mean something, and "compromise" has consequences. The schema we have is *almost* shaped for that, but not quite.

What the data records today:

- A mission knows **who created it** (`createdBy`) and a flat list of **who's in it** (`participantIds`).
- A channel knows **who created it** (`createdBy`) and two clearance numbers (view / post).
- A user has a **rank inside a mission** via `ClearanceAssignment`.
- A message carries a **category** that gates whether it can be sent at all.
- Ranks, categories and mission types are **shared taxonomies** maintained on the server.

What the data does **not** record today:

- Whether the creator of a mission is *meant to be in charge* of it.
- Per‑channel membership (any member of the mission with the right clearance can see any channel).
- Any concept of "this person can manage this thing", separate from clearance level.
- Roles inside a channel that differ from roles inside the mission.
- Any history of who did what (audit trail beyond the in‑memory `SecurityEvent` ring buffer).
- Document sharing — encrypted documents are strictly the owner's, no other user can ever decrypt them.

### Capability snapshot (today)

| ✅ Can do | ❌ Can't do |
|-----------|-------------|
| Run any number of missions, each with their own clearance ladder | Make a user "the boss" of the mission they created without manually granting them CHIEF |
| Restrict who can see / post in a channel using clearance levels | Restrict a channel to a **subset** of the mission's members |
| Send categorised messages with their own send‑level gate | Have a user be CHIEF in their own mission but OBSERVER in someone else's, *automatically* |
| Sync taxonomy edits across devices | Show "you created this" in a way that grants any actual power |
| Hold encrypted documents in a per‑user vault | Share a document with another operator |
| Log security events locally | Audit cross‑device: who archived this mission yesterday, from where? |
| Wipe everything on panic | Recover **anything** after panic — the tombstone is final by design |

---

## 2. The ideal schema (outcome‑first)

The shape below is the smallest set of changes that lets the product feel like what users keep asking it to be. It is described in plain language; the technical mapping is intentionally left to a follow‑up.

### 2.1 Mission becomes an *owned* thing

Today `createdBy` is a label. In the ideal model it is a **role**: the creator is the mission's first **administrator**, and a mission always has at least one administrator. Administrators can be added or removed, but the last one cannot leave without nominating a successor.

**Enables**

- "I made it, I run it" works automatically — no manual rank grant required.
- A user can be a CHIEF inside the mission they founded, and a passive OBSERVER inside another, in the same session.
- Multi‑admin missions (co‑leads) without giving the whole world CHIEF on the global ladder.
- Safe mission deletion / archival rules ("only an admin can do this").

**Still impossible**

- Cross‑mission privilege escalation. Being admin of mission A says nothing about mission B.
- Being admin of a mission you were never a member of.

### 2.2 Channel becomes its own membership

Today every member of a mission with the right clearance can see every channel. In the ideal model a channel has its **own member list**, plus its own admin (defaulting to the channel's creator, who must already be in the mission).

**Enables**

- "Inner circle" channels inside an open mission.
- Channels that outlive their creator: pass admin on, keep members.
- Removing one user from one channel without revoking their mission seat.
- Inviting a user into a single sensitive channel without granting them the whole mission.

**Still impossible**

- Putting a user in a channel they don't have clearance for. Clearance gating still applies on top of explicit membership — both must agree.
- Reading channels you were removed from. Sender keys rotate on member change so historical ciphertext stays unreadable.

### 2.3 Permission becomes a separate axis from clearance

Today *clearance level* is doing two jobs at once: it gates **what you can read** and (implicitly) **what you can manage**. They should be independent.

- **Clearance** = how sensitive the material is that you're allowed to see.
- **Role** = whether you can change the thing (create channel, archive mission, kick member, edit clearance).

A user is CHIEF *because of clearance* and admin *because of ownership* — independently.

**Enables**

- A clerk with low clearance who can still administer the channel list of a logistics mission.
- A high‑clearance observer who can read everything but cannot mutate anything.
- "Read‑only auditor" as a first‑class concept.

**Still impossible**

- Editing things outside the scopes you have a role in. Roles are always scoped to a single mission or channel; no global super‑admin without an explicit grant.

### 2.4 Documents become shareable

Today every document is sealed to one user. In the ideal model a document belongs to a **mission**, with an explicit ACL of operators who can decrypt it. Encryption is per‑recipient (each authorised user gets a wrapped data‑key), so revocation only affects future re‑shares — but a re‑key on revocation is feasible.

**Enables**

- Briefing packets that the whole channel can open.
- Hand‑over of a document when an operator rotates out.
- Mission‑archived documents that survive the original owner leaving.

**Still impossible**

- Decrypting documents you were never granted access to. Server still never sees plaintext or keys.
- Recovering documents after a panic wipe — keys live in the device Keystore by design.

### 2.5 Audit trail becomes durable

Today `SecurityEvent` is a 100‑entry in‑memory ring buffer. In the ideal model administrative actions on missions and channels (create / archive / member change / clearance change / panic intent) emit a signed audit entry that is persisted on the device and synced to the application server, scoped to the mission it concerns.

**Enables**

- "Who archived COMPASS yesterday?" answerable across reboots and devices.
- Compliance / after‑action review of a compromised mission.
- Detection of an admin acting outside their normal pattern.

**Still impossible**

- Reading the *content* of past messages from the audit log. Only the *fact* of an action is recorded, not the payload.
- Tampering retroactively — entries are append‑only and signed; mutation is detectable.

### 2.6 Mission "compromise" gets teeth

Today `MissionStatus.COMPROMISED` is just a label on the mission record. In the ideal model entering COMPROMISED triggers schema‑level effects: sender keys for all the mission's channels are rotated (so any leaked device cannot decrypt new traffic), open document ACLs are frozen, new participants cannot be added, and the audit trail flags every subsequent action.

**Enables**

- An honest "panic for one mission, keep the rest" workflow, instead of all‑or‑nothing device wipe.
- Forensic isolation of a single compromised mission.

**Still impossible**

- Un‑leaking already‑sent messages. Past ciphertext on a captured device stays decryptable on that device — the protection is forward‑secrecy on new traffic.
- Recovering from a device‑wide panic. That action is intentionally terminal.

---

## 3. What the ideal schema does **not** try to do

Some capabilities are out of scope on purpose. Calling them out explicitly so they don't sneak back in via feature creep:

| Capability | Why we are not doing it |
|------------|-------------------------|
| Plaintext message archive on the server | Defeats the entire threat model. The relay must remain ciphertext‑only. |
| Cross‑user message recovery after panic | Panic is a hard guarantee; a recovery path is an attacker's path. |
| Global super‑admin role | One compromised account would own the entire org. Roles must be scoped. |
| Federation / multi‑tenant orgs in the same install | Out of capstone scope. The current single‑org assumption stays. |
| Server‑side document storage | Documents stay on device. The vault is intentionally local. |
| Read‑receipts visible to the relay server | Would let the relay map who‑talks‑to‑who; against the metadata‑minimisation goal. |

---

## 4. Side‑by‑side: today vs. ideal, in plain terms

| Question a user might ask | Today's answer | Ideal answer |
|---------------------------|----------------|--------------|
| "I made this mission — am I in charge of it?" | Only if someone separately gave you CHIEF. | Yes, automatically. You can grant or transfer that. |
| "Can I have a side‑channel with two specific people?" | No, any qualifying mission member can join. | Yes, channel membership is explicit. |
| "Can a low‑clearance ops manager run channel logistics?" | No, the same number gates both. | Yes — admin role is separate from clearance level. |
| "Can I send this briefing PDF to my squad?" | No, documents are sealed to you. | Yes, via per‑recipient ACL. |
| "Who archived this mission?" | Unknown after the app restarts. | Recorded in the durable audit trail. |
| "We think this mission is burned. What now?" | Flip the label to COMPROMISED — nothing else changes. | Keys rotate, ACLs freeze, audit goes loud, other missions continue normally. |
| "Can I recover anything after panic?" | No. | No — and we're keeping it that way. |

---

## 5. Migration impact, in one paragraph

Almost all the new capability above is additive to existing fields rather than destructive. `createdBy` already exists on Mission and Channel; adding an explicit per‑mission and per‑channel role table sits next to today's `ClearanceAssignment` without conflicting with it. Channel membership is the one genuinely new collection. The audit trail is a new entity but server‑side only. Documents need a per‑recipient wrap‑key list, which is the only place existing encrypted blobs would have to be re‑sealed during rollout. The rest is policy code on top of fields that are already on the wire.
