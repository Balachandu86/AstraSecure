'use strict';

require('dotenv').config();

const crypto     = require('crypto');
const express    = require('express');
const http       = require('http');
const https      = require('https');
const fs         = require('fs');
const os         = require('os');
const url        = require('url');
const { Pool }   = require('pg');
const jwt        = require('jsonwebtoken');
const { WebSocketServer } = require('ws');

// ─── Config ───────────────────────────────────────────────────────────────────

const PORT       = parseInt(process.env.PORT || '3000', 10);
const HOST       = process.env.HOST || '0.0.0.0';
const JWT_SECRET = process.env.JWT_SECRET || 'change-me-in-production';
const DB_URL     = process.env.DATABASE_URL;

if (!DB_URL) {
  console.error('DATABASE_URL is not set. Copy .env.example to .env and fill it in.');
  process.exit(1);
}

// ─── Database ─────────────────────────────────────────────────────────────────

const pool = new Pool({ connectionString: DB_URL });

pool.on('error', (err) => console.error('PG pool error:', err.message));

async function initSchema() {
  const schema = fs.readFileSync('./schema.sql', 'utf8');
  await pool.query(schema);
  console.log('Schema applied.');
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function genId(prefix) {
  return `${prefix}-${crypto.randomBytes(4).toString('hex').toUpperCase()}`;
}

/**
 * Fingerprint of a base64-encoded identity-key public bytes string.
 * SHA-256, first 8 bytes, formatted as "AAAA-BBBB-CCCC-DDDD".
 * Used for display to humans during invite SAS verification.
 * Returns null on bad input.
 */
function fingerprintFromIdentityKey(b64) {
  if (!b64 || typeof b64 !== 'string') return null;
  try {
    const raw = Buffer.from(b64, 'base64');
    const hash = crypto.createHash('sha256').update(raw).digest();
    const hex = hash.subarray(0, 8).toString('hex').toUpperCase();
    return `${hex.slice(0,4)}-${hex.slice(4,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}`;
  } catch {
    return null;
  }
}

// userId → active WebSocket connection (set up in setupWebSocket)
const connections = new Map();

function pushToUser(userId, payload) {
  const ws = connections.get(userId);
  if (ws && ws.readyState === 1 /* OPEN */) {
    ws.send(JSON.stringify(payload));
  }
}

/**
 * Bump the sync_version for each userId and push a sync_invalidated frame
 * to any connected WebSocket client. Must be called within a transaction
 * (pass the transaction client).
 */
async function bumpVersions(client, userIds, reason) {
  if (!userIds || userIds.length === 0) return;
  const unique = [...new Set(userIds)];
  for (const uid of unique) {
    const row = await client.query(
      `INSERT INTO sync_versions (user_id, version)
       VALUES ($1, 1)
       ON CONFLICT (user_id) DO UPDATE SET version = sync_versions.version + 1
       RETURNING version`,
      [uid],
    );
    const version = row.rows[0]?.version ?? 1;
    pushToUser(uid, { type: 'sync_invalidated', version, reason });
  }
}

/** Returns all non-tombstoned participant user IDs for a mission. */
async function getParticipantIds(client, missionId) {
  const rows = await client.query(
    `SELECT mp.user_id FROM mission_participants mp
     JOIN users u ON u.user_id = mp.user_id
     WHERE mp.mission_id = $1 AND u.tombstoned = FALSE`,
    [missionId],
  );
  return rows.rows.map(r => r.user_id);
}

/** Returns all non-tombstoned user IDs in the DB. Used for schema-change bumps. */
async function getAllUserIds(client) {
  const rows = await client.query(
    'SELECT user_id FROM users WHERE tombstoned = FALSE',
  );
  return rows.rows.map(r => r.user_id);
}

/**
 * Returns the clearance level (schema_ranks.level) for userId on missionId,
 * or 0 if no clearance is assigned.
 */
async function getClearanceLevel(client, userId, missionId) {
  const row = await client.query(
    `SELECT r.level FROM clearance_assignments ca
     JOIN schema_ranks r ON r.id = ca.rank_id
     WHERE ca.user_id = $1 AND ca.mission_id = $2`,
    [userId, missionId],
  );
  return row.rows[0]?.level ?? 0;
}

/**
 * Returns true if userId holds rank_chief on missionId.
 * rank_chief is identified by level=9 OR id='rank_chief' (system rank).
 * We use level >= 9 to catch custom chief-equivalent ranks.
 */
async function isChief(client, userId, missionId) {
  const level = await getClearanceLevel(client, userId, missionId);
  return level >= 9;
}

/** Counts how many chiefs exist on a mission. */
async function countChiefs(client, missionId) {
  const row = await client.query(
    `SELECT COUNT(*) AS c FROM clearance_assignments ca
     JOIN schema_ranks r ON r.id = ca.rank_id
     WHERE ca.mission_id = $1 AND r.level >= 9`,
    [missionId],
  );
  return parseInt(row.rows[0].c, 10);
}

/** Writes an audit event inside an existing transaction. */
async function emitAudit(client, { userId, severity, source, text, missionId = null }) {
  await client.query(
    `INSERT INTO audit_events (user_id, severity, source, text, mission_id)
     VALUES ($1, $2, $3, $4, $5)`,
    [userId, severity, source, text, missionId],
  );
}

/**
 * Seeds the AstraSecure system schema records on first boot.
 * Uses ON CONFLICT DO NOTHING so it is safe to run on every boot.
 */
async function seedSystemData() {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Ranks
    const ranks = [
      { id: 'rank_observer',  name: 'OBSERVER',  level: 1, color: 'NEUTRAL',  is_system: true },
      { id: 'rank_operative', name: 'OPERATIVE', level: 5, color: 'PRIMARY',  is_system: true },
      { id: 'rank_chief',     name: 'CHIEF',     level: 9, color: 'TERTIARY', is_system: true },
    ];
    for (const r of ranks) {
      await client.query(
        `INSERT INTO schema_ranks (id, name, level, color, is_system)
         VALUES ($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING`,
        [r.id, r.name, r.level, r.color, r.is_system],
      );
    }

    // Channel categories
    const channelCats = [
      { id: 'cc_command',   name: 'COMMAND & CONTROL',             accent: 'SECONDARY', view: 9, post: 9 },
      { id: 'cc_recon',     name: 'RECONNAISSANCE & INTELLIGENCE', accent: 'PRIMARY',   view: 5, post: 5 },
      { id: 'cc_logistics', name: 'LOGISTICS & DEPLOYMENT',        accent: 'NEUTRAL',   view: 1, post: 1 },
    ];
    for (const c of channelCats) {
      await client.query(
        `INSERT INTO schema_channel_categories
           (id, name, accent, default_min_clearance_view, default_min_clearance_post, is_system)
         VALUES ($1,$2,$3,$4,$5,TRUE) ON CONFLICT DO NOTHING`,
        [c.id, c.name, c.accent, c.view, c.post],
      );
    }

    // Message categories
    const msgCats = [
      { id: 'mc_command',    name: 'COMMAND',      accent: 'SECONDARY', min: 9 },
      { id: 'mc_intel',      name: 'INTELLIGENCE', accent: 'PRIMARY',   min: 5 },
      { id: 'mc_standard',   name: 'STANDARD',     accent: 'NEUTRAL',   min: 1 },
      { id: 'mc_restricted', name: 'RESTRICTED',   accent: 'ERROR',     min: 9 },
    ];
    for (const m of msgCats) {
      await client.query(
        `INSERT INTO schema_message_categories (id, name, accent, min_clearance_send, is_system)
         VALUES ($1,$2,$3,$4,TRUE) ON CONFLICT DO NOTHING`,
        [m.id, m.name, m.accent, m.min],
      );
    }

    // Mission types
    const missionTypes = [
      { id: 'mt_top_secret',   name: 'TOP SECRET',   accent: 'TERTIARY', desc: 'Maximum sensitivity operations' },
      { id: 'mt_confidential', name: 'CONFIDENTIAL', accent: 'NEUTRAL',  desc: 'Restricted access operations' },
      { id: 'mt_restricted',   name: 'RESTRICTED',   accent: 'ERROR',    desc: 'Compromised or quarantined operations' },
    ];
    for (const t of missionTypes) {
      await client.query(
        `INSERT INTO schema_mission_types (id, name, accent, description, is_system)
         VALUES ($1,$2,$3,$4,TRUE) ON CONFLICT DO NOTHING`,
        [t.id, t.name, t.accent, t.desc],
      );
    }

    await client.query('COMMIT');
    console.log('System seed data applied.');
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('Seed failed:', e.message);
    throw e;
  } finally {
    client.release();
  }
}

// ─── Express app ──────────────────────────────────────────────────────────────

const app = express();
app.use(express.json({ limit: '2mb' }));

// ─── Auth middleware ──────────────────────────────────────────────────────────

function auth(req, res, next) {
  const header = req.headers['authorization'];
  if (!header || !header.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing or malformed Authorization header' });
  }
  try {
    req.user = jwt.verify(header.slice(7), JWT_SECRET);
    next();
  } catch (e) {
    res.status(401).json({ error: 'Invalid or expired token' });
  }
}

/**
 * Auth middleware that additionally checks tombstone status.
 * Returns 410 Gone if the user is tombstoned.
 */
async function authFull(req, res, next) {
  const header = req.headers['authorization'];
  if (!header || !header.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing or malformed Authorization header' });
  }
  try {
    req.user = jwt.verify(header.slice(7), JWT_SECRET);
  } catch (e) {
    return res.status(401).json({ error: 'Invalid or expired token' });
  }
  try {
    const row = await pool.query(
      'SELECT tombstoned, is_admin FROM users WHERE user_id = $1',
      [req.user.userId],
    );
    if (!row.rows[0]) return res.status(401).json({ error: 'User not found' });
    if (row.rows[0].tombstoned) return res.status(410).json({ error: 'Device terminated' });
    req.user.isAdmin = row.rows[0].is_admin;
    next();
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
}

// ─── GET /health ──────────────────────────────────────────────────────────────

app.get('/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'ok', ts: new Date().toISOString() });
  } catch (e) {
    res.status(503).json({ status: 'db_error', error: e.message });
  }
});

app.get('/api/health', (req, res) => res.redirect('/health'));

// ─── POST /v1/users — Signal relay registration ────────────────────────────────

app.post('/v1/users', async (req, res) => {
  const { userId, displayName, registrationId, identityKey, signedPreKey, oneTimePreKeys, kyberPreKey } = req.body;

  if (!userId || !displayName || !registrationId || !identityKey || !signedPreKey) {
    return res.status(400).json({ error: 'Missing required fields' });
  }
  if (!signedPreKey.id || !signedPreKey.publicKey || !signedPreKey.signature) {
    return res.status(400).json({ error: 'Invalid signedPreKey' });
  }
  if (!kyberPreKey || !kyberPreKey.id || !kyberPreKey.publicKey || !kyberPreKey.signature) {
    return res.status(400).json({ error: 'Invalid kyberPreKey' });
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    await client.query(
      `INSERT INTO users (user_id, display_name, registration_id, identity_key)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id) DO UPDATE SET
         display_name    = EXCLUDED.display_name,
         registration_id = EXCLUDED.registration_id,
         identity_key    = EXCLUDED.identity_key`,
      [userId, displayName, registrationId, identityKey],
    );

    await client.query(
      `INSERT INTO signed_prekeys (user_id, spk_id, spk_public, spk_signature)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id, spk_id) DO UPDATE SET
         spk_public    = EXCLUDED.spk_public,
         spk_signature = EXCLUDED.spk_signature`,
      [userId, signedPreKey.id, signedPreKey.publicKey, signedPreKey.signature],
    );

    await client.query(
      `INSERT INTO kyber_prekeys (user_id, key_id, public_key, signature)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id) DO UPDATE SET
         key_id     = EXCLUDED.key_id,
         public_key = EXCLUDED.public_key,
         signature  = EXCLUDED.signature`,
      [userId, kyberPreKey.id, kyberPreKey.publicKey, kyberPreKey.signature],
    );

    if (Array.isArray(oneTimePreKeys) && oneTimePreKeys.length > 0) {
      for (const opk of oneTimePreKeys) {
        await client.query(
          `INSERT INTO one_time_prekeys (user_id, opk_id, opk_public)
           VALUES ($1, $2, $3)
           ON CONFLICT (user_id, opk_id) DO NOTHING`,
          [userId, opk.id, opk.publicKey],
        );
      }
    }

    // Initialize sync_versions row
    await client.query(
      `INSERT INTO sync_versions (user_id, version) VALUES ($1, 0) ON CONFLICT DO NOTHING`,
      [userId],
    );

    await client.query('COMMIT');

    const token = jwt.sign({ userId }, JWT_SECRET, { expiresIn: '30d' });
    console.log(`[register] userId=${userId} displayName=${displayName} opks=${oneTimePreKeys?.length ?? 0}`);
    res.status(201).json({ token });

  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[register] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

// ─── DELETE /v1/users/:userId — Panic wipe (Signal relay cleanup) ─────────────
// Removes pre-keys and message queue but keeps the user row so REST API FKs
// (missions.created_by, audit_events.user_id) remain valid.
// Call POST /api/users/me/tombstone BEFORE this to mark the user terminated.

app.delete('/v1/users/:userId', auth, async (req, res) => {
  const { userId } = req.params;
  if (req.user.userId !== userId) {
    return res.status(403).json({ error: 'Forbidden' });
  }
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('DELETE FROM one_time_prekeys WHERE user_id = $1', [userId]);
    await client.query('DELETE FROM signed_prekeys WHERE user_id = $1', [userId]);
    await client.query('DELETE FROM kyber_prekeys WHERE user_id = $1', [userId]);
    await client.query('DELETE FROM message_queue WHERE recipient_id = $1', [userId]);
    await client.query('DELETE FROM channel_members WHERE user_id = $1', [userId]);
    await client.query('COMMIT');
    console.log(`[revoke] userId=${userId} signal keys wiped`);
    res.json({ ok: true });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[revoke] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

// ─── GET /v1/keys/:userId — Fetch pre-key bundle ──────────────────────────────

app.get('/v1/keys/:userId', auth, async (req, res) => {
  const { userId } = req.params;
  try {
    const userRows = await pool.query(
      `SELECT u.registration_id, u.identity_key,
              s.spk_id, s.spk_public, s.spk_signature,
              k.key_id AS kyber_id, k.public_key AS kyber_public, k.signature AS kyber_signature
       FROM users u
       JOIN signed_prekeys s ON s.user_id = u.user_id
       LEFT JOIN kyber_prekeys k ON k.user_id = u.user_id
       WHERE u.user_id = $1 AND u.tombstoned = FALSE
       ORDER BY s.spk_id DESC
       LIMIT 1`,
      [userId],
    );
    if (userRows.rows.length === 0) {
      console.warn(`[fetchBundle] 404 — user not found or has no SPK: userId=${userId}`);
      return res.status(404).json({ error: 'User not found' });
    }
    const u = userRows.rows[0];
    if (!u.kyber_id) {
      console.warn(`[fetchBundle] 409 — user exists but has no Kyber pre-key (re-provision required): userId=${userId}`);
      return res.status(409).json({ error: 'User not PQXDH-provisioned — re-provision required' });
    }

    const opkRows = await pool.query(
      `DELETE FROM one_time_prekeys
       WHERE user_id = $1
         AND opk_id = (
           SELECT opk_id FROM one_time_prekeys
           WHERE user_id = $1
           ORDER BY opk_id
           LIMIT 1
           FOR UPDATE SKIP LOCKED
         )
       RETURNING opk_id, opk_public`,
      [userId],
    );
    const opk = opkRows.rows[0] || null;

    const countRow = await pool.query(
      'SELECT COUNT(*) AS c FROM one_time_prekeys WHERE user_id = $1',
      [userId],
    );
    const remaining = parseInt(countRow.rows[0].c, 10);
    if (remaining < 10) {
      pushToUser(userId, { type: 'keysNeeded', currentCount: remaining });
    }

    console.log(`[fetchBundle] SUCCESS userId=${userId} spk=${u.spk_id} opk=${opk ? opk.opk_id : 'none'} kyber=${u.kyber_id}`);
    res.json({
      userId,
      registrationId: u.registration_id,
      identityKey:    u.identity_key,
      signedPreKey: {
        id:        u.spk_id,
        publicKey: u.spk_public,
        signature: u.spk_signature,
      },
      oneTimePreKey: opk ? { id: opk.opk_id, publicKey: opk.opk_public } : null,
      kyberPreKey: {
        id:        u.kyber_id,
        publicKey: u.kyber_public,
        signature: u.kyber_signature,
      },
    });
  } catch (e) {
    console.error('[fetchBundle] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── PUT /v1/keys — Upload OPKs ───────────────────────────────────────────────

app.put('/v1/keys', auth, async (req, res) => {
  const { oneTimePreKeys } = req.body;
  if (!Array.isArray(oneTimePreKeys) || oneTimePreKeys.length === 0) {
    return res.status(400).json({ error: 'oneTimePreKeys must be a non-empty array' });
  }
  try {
    let uploaded = 0;
    for (const opk of oneTimePreKeys) {
      await pool.query(
        `INSERT INTO one_time_prekeys (user_id, opk_id, opk_public)
         VALUES ($1, $2, $3)
         ON CONFLICT (user_id, opk_id) DO NOTHING`,
        [req.user.userId, opk.id, opk.publicKey],
      );
      uploaded++;
    }
    res.json({ uploaded });
  } catch (e) {
    console.error('[uploadOPK] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── PUT /v1/keys/signed — Upload SPK ────────────────────────────────────────

app.put('/v1/keys/signed', auth, async (req, res) => {
  const { id, publicKey, signature } = req.body;
  if (!id || !publicKey || !signature) {
    return res.status(400).json({ error: 'Missing id, publicKey, or signature' });
  }
  try {
    await pool.query(
      `INSERT INTO signed_prekeys (user_id, spk_id, spk_public, spk_signature)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id, spk_id) DO UPDATE SET
         spk_public    = EXCLUDED.spk_public,
         spk_signature = EXCLUDED.spk_signature`,
      [req.user.userId, id, publicKey, signature],
    );
    res.json({ ok: true });
  } catch (e) {
    console.error('[uploadSPK] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── PUT /v1/keys/kyber — Upload/rotate Kyber pre-key ────────────────────────

app.put('/v1/keys/kyber', auth, async (req, res) => {
  const { id, publicKey, signature } = req.body;
  if (!id || !publicKey || !signature) {
    return res.status(400).json({ error: 'Missing id, publicKey, or signature' });
  }
  try {
    await pool.query(
      `INSERT INTO kyber_prekeys (user_id, key_id, public_key, signature)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id) DO UPDATE SET
         key_id     = EXCLUDED.key_id,
         public_key = EXCLUDED.public_key,
         signature  = EXCLUDED.signature`,
      [req.user.userId, id, publicKey, signature],
    );
    res.json({ ok: true });
  } catch (e) {
    console.error('[uploadKyber] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── GET /v1/keys/count ───────────────────────────────────────────────────────

app.get('/v1/keys/count', auth, async (req, res) => {
  try {
    const row = await pool.query(
      'SELECT COUNT(*) AS c FROM one_time_prekeys WHERE user_id = $1',
      [req.user.userId],
    );
    res.json({ count: parseInt(row.rows[0].c, 10) });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─── POST /v1/messages/:recipientId — Send message ────────────────────────────

app.post('/v1/messages/:recipientId', auth, async (req, res) => {
  const { recipientId } = req.params;
  const { channelId, messageType, ciphertext } = req.body;
  const senderId = req.user.userId;

  if (!channelId || messageType == null || !ciphertext) {
    return res.status(400).json({ error: 'Missing channelId, messageType, or ciphertext' });
  }

  try {
    const result = await pool.query(
      `INSERT INTO message_queue (recipient_id, sender_id, channel_id, message_type, ciphertext)
       VALUES ($1, $2, $3, $4, $5)
       RETURNING id`,
      [recipientId, senderId, channelId, messageType, ciphertext],
    );
    const msgId = result.rows[0].id;

    const wsConn = connections.get(recipientId);
    const wsDelivered = wsConn?.readyState === 1;
    console.log(`[sendMessage] from=${senderId} to=${recipientId} type=${messageType} msgId=${msgId} wsDelivered=${wsDelivered}`);

    pushToUser(recipientId, {
      type: 'message',
      id: msgId,
      senderId,
      channelId,
      messageType,
      ciphertext,
    });

    res.status(202).json({ id: msgId });
  } catch (e) {
    console.error('[sendMessage] error:', e.message);
    if (e.code === '23503') {
      return res.status(404).json({ error: 'Recipient not registered' });
    }
    res.status(500).json({ error: e.message });
  }
});

// ─── GET /v1/messages — Poll queued messages ──────────────────────────────────

app.get('/v1/messages', auth, async (req, res) => {
  try {
    const rows = await pool.query(
      `SELECT id, sender_id, channel_id, message_type, ciphertext
       FROM message_queue
       WHERE recipient_id = $1
       ORDER BY id`,
      [req.user.userId],
    );
    res.json({
      messages: rows.rows.map(r => ({
        id:          r.id,
        senderId:    r.sender_id,
        channelId:   r.channel_id,
        messageType: r.message_type,
        ciphertext:  r.ciphertext,
      })),
    });
  } catch (e) {
    console.error('[fetchMessages] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── DELETE /v1/messages — ACK delivery ──────────────────────────────────────

app.delete('/v1/messages', auth, async (req, res) => {
  const { messageIds } = req.body;
  if (!Array.isArray(messageIds) || messageIds.length === 0) {
    return res.json({ deleted: 0 });
  }
  try {
    const result = await pool.query(
      'DELETE FROM message_queue WHERE id = ANY($1::bigint[])',
      [messageIds],
    );
    res.json({ deleted: result.rowCount });
  } catch (e) {
    console.error('[ackMessages] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── POST /v1/channels/:channelId/members — Join channel ─────────────────────

app.post('/v1/channels/:channelId/members', auth, async (req, res) => {
  const { channelId } = req.params;
  const userId = req.user.userId;
  try {
    await pool.query(
      `INSERT INTO channel_members (channel_id, user_id)
       VALUES ($1, $2)
       ON CONFLICT DO NOTHING`,
      [channelId, userId],
    );
    res.status(201).json({ ok: true });
  } catch (e) {
    console.error('[joinChannel] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── GET /v1/channels/:channelId/members — List members ──────────────────────

app.get('/v1/channels/:channelId/members', auth, async (req, res) => {
  const { channelId } = req.params;
  try {
    const rows = await pool.query(
      'SELECT user_id FROM channel_members WHERE channel_id = $1',
      [channelId],
    );
    res.json({ memberIds: rows.rows.map(r => r.user_id) });
  } catch (e) {
    console.error('[getMembers] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ═══════════════════════════════════════════════════════════════════════════════
// REST API — /api/*
// All routes use authFull which validates JWT + tombstone check.
// ═══════════════════════════════════════════════════════════════════════════════

// ─── /api/users ───────────────────────────────────────────────────────────────

/**
 * POST /api/users
 * Called immediately after Signal registration to mirror the user in the REST DB.
 * First-ever registered user automatically becomes admin.
 */
app.post('/api/users', authFull, async (req, res) => {
  const { callsign } = req.body;
  const userId = req.user.userId;
  if (!callsign || typeof callsign !== 'string' || callsign.trim().length === 0) {
    return res.status(400).json({ error: 'callsign is required' });
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // If no admin exists yet, this user becomes admin
    const adminCount = await client.query(
      'SELECT COUNT(*) AS c FROM users WHERE is_admin = TRUE',
    );
    const makeAdmin = parseInt(adminCount.rows[0].c, 10) === 0;

    await client.query(
      `UPDATE users SET display_name = $1, is_admin = $2 WHERE user_id = $3`,
      [callsign.trim(), makeAdmin, userId],
    );

    // Ensure sync_versions row exists
    await client.query(
      `INSERT INTO sync_versions (user_id, version) VALUES ($1, 0) ON CONFLICT DO NOTHING`,
      [userId],
    );

    await client.query('COMMIT');
    console.log(`[api/users] userId=${userId} callsign=${callsign} admin=${makeAdmin}`);
    res.status(201).json({ userId, callsign: callsign.trim(), isAdmin: makeAdmin });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[api/users] error:', e.message);
    if (e.code === '23505') return res.status(409).json({ error: 'Callsign already taken' });
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/**
 * GET /api/users/:id
 * Returns callsign + tombstone status. Only visible if caller shares a mission with id,
 * or caller IS id.
 */
app.get('/api/users/:id', authFull, async (req, res) => {
  const { id } = req.params;
  const callerId = req.user.userId;

  try {
    // Caller can see themselves, or anyone on a shared mission
    const accessible = callerId === id
      ? true
      : (await pool.query(
          `SELECT 1 FROM mission_participants mp1
           JOIN mission_participants mp2 ON mp1.mission_id = mp2.mission_id
           WHERE mp1.user_id = $1 AND mp2.user_id = $2
           LIMIT 1`,
          [callerId, id],
        )).rows.length > 0;

    if (!accessible) return res.status(404).json({ error: 'Not found' });

    const row = await pool.query(
      'SELECT user_id, display_name, tombstoned FROM users WHERE user_id = $1',
      [id],
    );
    if (!row.rows[0]) return res.status(404).json({ error: 'Not found' });
    const u = row.rows[0];
    res.json({ userId: u.user_id, callsign: u.display_name, tombstoned: u.tombstoned });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/**
 * POST /api/users/me/tombstone
 * Marks the caller as terminated. Call BEFORE DELETE /v1/users/:userId.
 * Cleans up clearances and mission_participants; keeps missions/channels for peers.
 */
app.post('/api/users/me/tombstone', authFull, async (req, res) => {
  const userId = req.user.userId;
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Fetch callsign before any data is removed
    const userRow = await client.query(
      'SELECT display_name FROM users WHERE user_id = $1',
      [userId],
    );
    const callsign = userRow.rows[0]?.display_name ?? 'UNKNOWN';

    // Collect every mission the user participates in to notify peers
    const missionRows = await client.query(
      'SELECT mission_id FROM mission_participants WHERE user_id = $1',
      [userId],
    );
    const missionIds = missionRows.rows.map(r => r.mission_id);

    // Collect all peers who need a sync_invalidated push
    const peerSet = new Set();
    for (const mid of missionIds) {
      const peers = await getParticipantIds(client, mid);
      peers.forEach(p => peerSet.add(p));
    }
    peerSet.delete(userId);

    // Remove clearances and participation
    await client.query('DELETE FROM clearance_assignments WHERE user_id = $1', [userId]);
    await client.query('DELETE FROM mission_participants WHERE user_id = $1', [userId]);

    // Mark tombstoned
    await client.query('UPDATE users SET tombstoned = TRUE WHERE user_id = $1', [userId]);

    // Audit
    await emitAudit(client, { userId, severity: 'ALERT', source: 'Panic', text: 'Device terminated — operator initiated panic wipe' });

    // Bump versions for all peers (triggers sync_invalidated on their clients)
    await bumpVersions(client, [...peerSet], 'participant');

    // Push burned alert to all online peers via WebSocket
    for (const peerId of peerSet) {
      pushToUser(peerId, { type: 'operative_burned', userId, callsign });
    }

    await client.query('COMMIT');
    console.log(`[tombstone] userId=${userId}`);
    res.json({ userId, tombstonedAt: new Date().toISOString() });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[tombstone] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

// ─── /api/sync ────────────────────────────────────────────────────────────────

/**
 * GET /api/sync
 * Returns all data the client needs to render the app.
 * Supports If-None-Match for cheap 304 responses.
 */
app.get('/api/sync', authFull, async (req, res) => {
  const userId = req.user.userId;
  try {
    // Get current version
    const vRow = await pool.query(
      `SELECT COALESCE(version, 0) AS version FROM sync_versions WHERE user_id = $1`,
      [userId],
    );
    const version = vRow.rows[0]?.version ?? 0;
    const etag = `"${version}"`;

    // 304 if client is up to date
    const clientEtag = req.headers['if-none-match'];
    if (clientEtag && clientEtag === etag) {
      return res.status(304).set('ETag', etag).end();
    }

    // Schema (global)
    const [ranks, channelCats, msgCats, missionTypes] = await Promise.all([
      pool.query('SELECT * FROM schema_ranks ORDER BY level DESC'),
      pool.query('SELECT * FROM schema_channel_categories ORDER BY name'),
      pool.query('SELECT * FROM schema_message_categories ORDER BY name'),
      pool.query('SELECT * FROM schema_mission_types ORDER BY name'),
    ]);

    // Missions for this user
    // For each mission the caller participates in, also look up the inviter's
    // identity-key fingerprint so a PENDING caller can run the SAS verification
    // ceremony after an app restart (without re-reading the redeem response).
    const missionRows = await pool.query(
      `SELECT m.*, mp.status AS my_status, mp.invited_by AS my_invited_by,
              inviter.identity_key AS inviter_identity_key
       FROM missions m
       JOIN mission_participants mp ON mp.mission_id = m.id
       LEFT JOIN users inviter ON inviter.user_id = mp.invited_by
       WHERE mp.user_id = $1
       ORDER BY m.last_activity_at DESC`,
      [userId],
    );
    // PENDING participants see the mission summary only — no other operators,
    // no channels, no clearances. Promoted to full visibility on confirmation.
    const activeMissionIds  = missionRows.rows.filter(r => r.my_status === 'ACTIVE' ).map(r => r.id);

    const participantMap = {};
    const pendingMap = {};
    if (activeMissionIds.length > 0) {
      const pRows = await pool.query(
        `SELECT mp.mission_id, mp.user_id, mp.status, mp.invited_by,
                mp.invited_at, u.identity_key, u.display_name
         FROM mission_participants mp
         JOIN users u ON u.user_id = mp.user_id
         WHERE mp.mission_id = ANY($1)`,
        [activeMissionIds],
      );
      for (const r of pRows.rows) {
        if (r.status === 'ACTIVE') {
          if (!participantMap[r.mission_id]) participantMap[r.mission_id] = [];
          participantMap[r.mission_id].push(r.user_id);
        } else if (r.status === 'PENDING') {
          if (!pendingMap[r.mission_id]) pendingMap[r.mission_id] = [];
          pendingMap[r.mission_id].push({
            userId: r.user_id,
            callsign: r.display_name,
            invitedBy: r.invited_by,
            invitedAtMs: r.invited_at ? new Date(r.invited_at).getTime() : 0,
            fingerprint: fingerprintFromIdentityKey(r.identity_key),
          });
        }
      }
    }

    // Channels and clearances are only visible to users active on the mission.
    const channelRows = activeMissionIds.length > 0
      ? (await pool.query(
          'SELECT * FROM channels WHERE mission_id = ANY($1) ORDER BY created_at',
          [activeMissionIds],
        )).rows
      : [];

    const clearanceRows = activeMissionIds.length > 0
      ? (await pool.query(
          'SELECT * FROM clearance_assignments WHERE mission_id = ANY($1)',
          [activeMissionIds],
        )).rows
      : [];

    // Build response
    const toMs = (ts) => ts ? new Date(ts).getTime() : 0;

    res.set('ETag', etag).json({
      version: Number(version),
      etag,
      schema: {
        ranks: ranks.rows.map(r => ({
          id: r.id, name: r.name, level: r.level,
          color: r.color, isSystem: r.is_system,
        })),
        channelCategories: channelCats.rows.map(r => ({
          id: r.id, name: r.name, accent: r.accent,
          defaultMinClearanceToView: r.default_min_clearance_view,
          defaultMinClearanceToPost: r.default_min_clearance_post,
          isSystem: r.is_system,
        })),
        messageCategories: msgCats.rows.map(r => ({
          id: r.id, name: r.name, accent: r.accent,
          minClearanceToSend: r.min_clearance_send,
          isSystem: r.is_system,
        })),
        missionTypes: missionTypes.rows.map(r => ({
          id: r.id, name: r.name, accent: r.accent,
          description: r.description, isSystem: r.is_system,
        })),
      },
      missions: missionRows.rows.map(m => ({
        id: m.id,
        name: m.name,
        typeId: m.type_id,
        status: m.status,
        phase: m.phase ?? null,
        missionKeyAlias: m.mission_key_alias,
        participantIds: participantMap[m.id] ?? [],
        pendingParticipants: pendingMap[m.id] ?? [],
        myStatus: m.my_status,
        // Only populated for PENDING callers — used to display the inviter's
        // fingerprint during the SAS ceremony.
        inviterId: m.my_status === 'PENDING' ? m.my_invited_by : null,
        inviterFingerprint: m.my_status === 'PENDING'
          ? fingerprintFromIdentityKey(m.inviter_identity_key)
          : null,
        createdAtMs: toMs(m.created_at),
        lastActivityMs: toMs(m.last_activity_at),
        createdBy: m.created_by,
      })),
      channels: channelRows.map(c => ({
        id: c.id,
        missionId: c.mission_id,
        name: c.name,
        description: c.description,
        categoryId: c.category_id,
        minClearanceToView: c.min_clearance_view,
        minClearanceToPost: c.min_clearance_post,
        createdAtMs: toMs(c.created_at),
        createdBy: c.created_by,
      })),
      clearances: clearanceRows.map(c => ({
        userId: c.user_id,
        missionId: c.mission_id,
        rankId: c.rank_id,
      })),
    });
  } catch (e) {
    console.error('[sync] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── /api/missions ────────────────────────────────────────────────────────────

/** GET /api/missions — list missions the caller participates in. */
app.get('/api/missions', authFull, async (req, res) => {
  const userId = req.user.userId;
  try {
    const rows = await pool.query(
      `SELECT m.* FROM missions m
       JOIN mission_participants mp ON mp.mission_id = m.id
       WHERE mp.user_id = $1
       ORDER BY m.last_activity_at DESC`,
      [userId],
    );
    res.json({ items: rows.rows });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/**
 * POST /api/missions — create a mission.
 * Atomically inserts: mission + participant entry + rank_chief clearance for creator.
 */
app.post('/api/missions', authFull, async (req, res) => {
  const { name, typeId } = req.body;
  const userId = req.user.userId;

  if (!name || !typeId) {
    return res.status(400).json({ error: 'name and typeId are required' });
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Validate typeId exists
    const typeRow = await client.query('SELECT id FROM schema_mission_types WHERE id = $1', [typeId]);
    if (!typeRow.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(422).json({ error: `Mission type '${typeId}' not found` });
    }

    const missionId = genId('M');
    const now = new Date();

    await client.query(
      `INSERT INTO missions (id, name, type_id, status, created_by, created_at, last_activity_at)
       VALUES ($1, $2, $3, 'ACTIVE', $4, $5, $5)`,
      [missionId, name.trim(), typeId, userId, now],
    );

    await client.query(
      `INSERT INTO mission_participants (mission_id, user_id) VALUES ($1, $2)`,
      [missionId, userId],
    );

    await client.query(
      `INSERT INTO clearance_assignments (user_id, mission_id, rank_id) VALUES ($1, $2, 'rank_chief')`,
      [userId, missionId],
    );

    // Initialize sync_versions row for creator and bump
    await bumpVersions(client, [userId], 'mission');

    await emitAudit(client, { userId, severity: 'INFO', source: 'Missions', text: `Mission created: ${name}`, missionId });

    await client.query('COMMIT');

    const row = await pool.query('SELECT * FROM missions WHERE id = $1', [missionId]);
    console.log(`[missions] created missionId=${missionId} by userId=${userId}`);
    const m = row.rows[0];
    res.status(201).json({
      id: m.id, name: m.name, typeId: m.type_id, status: m.status,
      phase: m.phase, missionKeyAlias: m.mission_key_alias,
      participantIds: [userId], createdAtMs: new Date(m.created_at).getTime(),
      lastActivityMs: new Date(m.last_activity_at).getTime(), createdBy: m.created_by,
    });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[missions/create] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/** GET /api/missions/:id */
app.get('/api/missions/:id', authFull, async (req, res) => {
  const { id } = req.params;
  const userId = req.user.userId;
  try {
    const row = await pool.query(
      `SELECT m.* FROM missions m
       JOIN mission_participants mp ON mp.mission_id = m.id
       WHERE m.id = $1 AND mp.user_id = $2`,
      [id, userId],
    );
    if (!row.rows[0]) return res.status(404).json({ error: 'Not found' });
    res.json(row.rows[0]);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/** PUT /api/missions/:id — update name/status/phase/typeId. Requires rank_chief. */
app.put('/api/missions/:id', authFull, async (req, res) => {
  const { id } = req.params;
  const userId = req.user.userId;
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    if (!(await isChief(client, userId, id))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    const { name, status, phase, typeId } = req.body;
    const valid = ['ACTIVE', 'STANDBY', 'COMPROMISED', 'ARCHIVED'];
    if (status && !valid.includes(status)) {
      await client.query('ROLLBACK');
      return res.status(422).json({ error: `Invalid status. Must be one of: ${valid.join(', ')}` });
    }

    await client.query(
      `UPDATE missions SET
         name             = COALESCE($1, name),
         status           = COALESCE($2, status),
         phase            = COALESCE($3, phase),
         type_id          = COALESCE($4, type_id),
         last_activity_at = NOW()
       WHERE id = $5`,
      [name ?? null, status ?? null, phase ?? null, typeId ?? null, id],
    );

    const participants = await getParticipantIds(client, id);
    await bumpVersions(client, participants, 'mission');
    await emitAudit(client, { userId, severity: 'INFO', source: 'Missions', text: `Mission updated: ${id}`, missionId: id });
    await client.query('COMMIT');

    const row = await pool.query('SELECT * FROM missions WHERE id = $1', [id]);
    res.json(row.rows[0]);
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[missions/update] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/** DELETE /api/missions/:id — soft-archive. Requires rank_chief. */
app.delete('/api/missions/:id', authFull, async (req, res) => {
  const { id } = req.params;
  const userId = req.user.userId;
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    if (!(await isChief(client, userId, id))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    const participants = await getParticipantIds(client, id);
    await client.query(`UPDATE missions SET status = 'ARCHIVED', last_activity_at = NOW() WHERE id = $1`, [id]);
    await bumpVersions(client, participants, 'mission');
    await emitAudit(client, { userId, severity: 'ALERT', source: 'Missions', text: `Mission archived: ${id}`, missionId: id });
    await client.query('COMMIT');
    res.status(204).end();
  } catch (e) {
    await client.query('ROLLBACK');
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/** POST /api/missions/:id/participants — add a user. Requires rank_chief. */
app.post('/api/missions/:id/participants', authFull, async (req, res) => {
  const { id: missionId } = req.params;
  const callerId = req.user.userId;
  const { userId } = req.body;

  if (!userId) return res.status(400).json({ error: 'userId is required' });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    if (!(await isChief(client, callerId, missionId))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    // Check target user exists and is not tombstoned
    const uRow = await client.query(
      'SELECT user_id FROM users WHERE user_id = $1 AND tombstoned = FALSE',
      [userId],
    );
    if (!uRow.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'User not found or tombstoned' });
    }

    // Add participant and assign default observer clearance (atomic)
    try {
      await client.query(
        'INSERT INTO mission_participants (mission_id, user_id) VALUES ($1, $2)',
        [missionId, userId],
      );
    } catch (e) {
      if (e.code === '23505') {
        await client.query('ROLLBACK');
        return res.status(409).json({ error: 'Already a participant' });
      }
      throw e;
    }

    await client.query(
      `INSERT INTO clearance_assignments (user_id, mission_id, rank_id)
       VALUES ($1, $2, 'rank_observer')
       ON CONFLICT DO NOTHING`,
      [userId, missionId],
    );

    const allParticipants = await getParticipantIds(client, missionId);
    await bumpVersions(client, allParticipants, 'participant');
    // Also bump the new user (they may not be in getParticipantIds yet if not active)
    await bumpVersions(client, [userId], 'participant');

    await emitAudit(client, { userId: callerId, severity: 'INFO', source: 'Admin', text: `Participant added: ${userId}`, missionId });
    await client.query('COMMIT');
    res.status(200).json({ ok: true });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[participants/add] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/** DELETE /api/missions/:id/participants/:userId — remove a user. Requires rank_chief. */
app.delete('/api/missions/:id/participants/:userId', authFull, async (req, res) => {
  const { id: missionId, userId: targetId } = req.params;
  const callerId = req.user.userId;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    if (!(await isChief(client, callerId, missionId))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    // Prevent removing the last chief
    const targetLevel = await getClearanceLevel(client, targetId, missionId);
    if (targetLevel >= 9 && (await countChiefs(client, missionId)) <= 1) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'last_chief', detail: 'Cannot remove the last chief from a mission' });
    }

    const remainingParticipants = await getParticipantIds(client, missionId);

    await client.query('DELETE FROM clearance_assignments WHERE user_id = $1 AND mission_id = $2', [targetId, missionId]);
    await client.query('DELETE FROM mission_participants WHERE user_id = $1 AND mission_id = $2', [targetId, missionId]);

    // Bump remaining members + removed user
    await bumpVersions(client, remainingParticipants, 'participant');
    await bumpVersions(client, [targetId], 'participant');

    await emitAudit(client, { userId: callerId, severity: 'ALERT', source: 'Admin', text: `Participant removed: ${targetId}`, missionId });
    await client.query('COMMIT');
    res.status(204).end();
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[participants/remove] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

// ─── /api/invites ─────────────────────────────────────────────────────────────
// Single-use, time-bound invite tokens. CHIEF issues, operator redeems (lands
// in PENDING), CHIEF confirms after out-of-band identity verification (SAS).

const INVITE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // Crockford-ish, no 0/O/1/I/L
const DEFAULT_INVITE_TTL_HOURS = 24;

function genInviteToken() {
  const bytes = crypto.randomBytes(10);
  let token = '';
  for (let i = 0; i < bytes.length; i++) {
    token += INVITE_ALPHABET[bytes[i] % INVITE_ALPHABET.length];
  }
  return token;
}

/**
 * POST /api/missions/:id/invites — issue an invite token. Requires CHIEF.
 * Body: { defaultRank?: string, ttlHours?: number }
 */
app.post('/api/missions/:id/invites', authFull, async (req, res) => {
  const { id: missionId } = req.params;
  const callerId = req.user.userId;
  const defaultRank = req.body?.defaultRank ?? 'rank_observer';
  const ttlHours = Math.max(1, Math.min(168, Number(req.body?.ttlHours ?? DEFAULT_INVITE_TTL_HOURS)));

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    if (!(await isChief(client, callerId, missionId))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    // Validate rank exists
    const rankRow = await client.query('SELECT id FROM schema_ranks WHERE id = $1', [defaultRank]);
    if (!rankRow.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(422).json({ error: `Rank '${defaultRank}' not found` });
    }

    const token = genInviteToken();
    const expiresAt = new Date(Date.now() + ttlHours * 3600_000);

    await client.query(
      `INSERT INTO invites (token, mission_id, issued_by, default_rank, expires_at)
       VALUES ($1, $2, $3, $4, $5)`,
      [token, missionId, callerId, defaultRank, expiresAt],
    );

    await emitAudit(client, {
      userId: callerId, severity: 'INFO', source: 'Invites',
      text: `Invite issued (rank=${defaultRank}, ttl=${ttlHours}h)`, missionId,
    });

    await client.query('COMMIT');
    console.log(`[invites] issued token=${token} mission=${missionId} by=${callerId}`);
    res.status(201).json({
      token,
      missionId,
      defaultRank,
      expiresAt: expiresAt.toISOString(),
    });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[invites/issue] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/**
 * GET /api/missions/:id/invites — list open invites. Requires CHIEF.
 */
app.get('/api/missions/:id/invites', authFull, async (req, res) => {
  const { id: missionId } = req.params;
  const callerId = req.user.userId;
  const client = await pool.connect();
  try {
    if (!(await isChief(client, callerId, missionId))) {
      return res.status(403).json({ error: 'Chief clearance required' });
    }
    const rows = await client.query(
      `SELECT token, default_rank, expires_at, redeemed_by, redeemed_at, revoked_at, created_at
       FROM invites WHERE mission_id = $1
       ORDER BY created_at DESC`,
      [missionId],
    );
    res.json({
      invites: rows.rows.map(r => ({
        token: r.token,
        defaultRank: r.default_rank,
        expiresAt: r.expires_at?.toISOString() ?? null,
        redeemedBy: r.redeemed_by,
        redeemedAt: r.redeemed_at?.toISOString() ?? null,
        revokedAt: r.revoked_at?.toISOString() ?? null,
        createdAt: r.created_at?.toISOString() ?? null,
      })),
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/**
 * DELETE /api/invites/:token — revoke an unredeemed invite. Requires CHIEF on the
 * invite's mission. Already-redeemed invites cannot be revoked (use participant
 * removal instead).
 */
app.delete('/api/invites/:token', authFull, async (req, res) => {
  const { token } = req.params;
  const callerId = req.user.userId;
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const inv = await client.query(
      'SELECT mission_id, redeemed_at, revoked_at FROM invites WHERE token = $1',
      [token],
    );
    if (!inv.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Invite not found' });
    }
    if (inv.rows[0].redeemed_at) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Invite already redeemed' });
    }
    if (inv.rows[0].revoked_at) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Invite already revoked' });
    }
    if (!(await isChief(client, callerId, inv.rows[0].mission_id))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    await client.query(
      'UPDATE invites SET revoked_at = NOW() WHERE token = $1',
      [token],
    );
    await emitAudit(client, {
      userId: callerId, severity: 'INFO', source: 'Invites',
      text: `Invite revoked: ${token}`, missionId: inv.rows[0].mission_id,
    });

    await client.query('COMMIT');
    res.status(204).end();
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[invites/revoke] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/**
 * POST /api/invites/:token/redeem — caller redeems an invite, lands as PENDING
 * participant. Returns mission summary + the issuer's identity-key fingerprint
 * so the redeemer can compare with what the inviter sees.
 */
app.post('/api/invites/:token/redeem', authFull, async (req, res) => {
  const { token } = req.params;
  const callerId = req.user.userId;
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const inv = await client.query(
      `SELECT i.mission_id, i.issued_by, i.default_rank, i.expires_at,
              i.redeemed_at, i.revoked_at, m.name AS mission_name
       FROM invites i JOIN missions m ON m.id = i.mission_id
       WHERE i.token = $1`,
      [token],
    );
    if (!inv.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Invalid invite' });
    }
    const row = inv.rows[0];
    if (row.redeemed_at) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Invite already redeemed' });
    }
    if (row.revoked_at) {
      await client.query('ROLLBACK');
      return res.status(410).json({ error: 'Invite revoked' });
    }
    if (new Date(row.expires_at).getTime() < Date.now()) {
      await client.query('ROLLBACK');
      return res.status(410).json({ error: 'Invite expired' });
    }

    // Caller cannot already be a participant
    const existing = await client.query(
      'SELECT status FROM mission_participants WHERE mission_id = $1 AND user_id = $2',
      [row.mission_id, callerId],
    );
    if (existing.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(409).json({
        error: existing.rows[0].status === 'PENDING'
          ? 'Already pending on this mission'
          : 'Already a participant',
      });
    }

    // Insert as PENDING; do NOT assign clearance until confirmed
    await client.query(
      `INSERT INTO mission_participants (mission_id, user_id, status, invited_by, invited_at)
       VALUES ($1, $2, 'PENDING', $3, NOW())`,
      [row.mission_id, callerId, row.issued_by],
    );

    await client.query(
      'UPDATE invites SET redeemed_by = $1, redeemed_at = NOW() WHERE token = $2',
      [callerId, token],
    );

    // Bump versions for issuer (so they see the pending operator) and redeemer
    await bumpVersions(client, [row.issued_by, callerId], 'invite_redeemed');

    await emitAudit(client, {
      userId: callerId, severity: 'INFO', source: 'Invites',
      text: `Invite redeemed (issuer=${row.issued_by})`, missionId: row.mission_id,
    });

    // Lookup issuer's identity-key fingerprint for the SAS comparison
    const issuerKey = await client.query(
      'SELECT identity_key FROM users WHERE user_id = $1',
      [row.issued_by],
    );
    const issuerIk = issuerKey.rows[0]?.identity_key ?? null;
    const issuerFp = issuerIk ? fingerprintFromIdentityKey(issuerIk) : null;

    await client.query('COMMIT');
    console.log(`[invites] redeemed token=${token} by=${callerId} mission=${row.mission_id}`);
    res.json({
      missionId: row.mission_id,
      missionName: row.mission_name,
      issuedBy: row.issued_by,
      issuerFingerprint: issuerFp,
      defaultRank: row.default_rank,
      status: 'PENDING',
    });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[invites/redeem] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/**
 * POST /api/missions/:id/participants/:userId/confirm — CHIEF confirms a PENDING
 * participant after out-of-band SAS verification. Transitions PENDING -> ACTIVE
 * and assigns the default observer clearance (overrideable later).
 */
app.post('/api/missions/:id/participants/:userId/confirm', authFull, async (req, res) => {
  const { id: missionId, userId: targetId } = req.params;
  const callerId = req.user.userId;
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    if (!(await isChief(client, callerId, missionId))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    const mp = await client.query(
      'SELECT status FROM mission_participants WHERE mission_id = $1 AND user_id = $2',
      [missionId, targetId],
    );
    if (!mp.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Participant not found' });
    }
    if (mp.rows[0].status !== 'PENDING') {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Participant is not pending' });
    }

    await client.query(
      `UPDATE mission_participants SET status = 'ACTIVE'
       WHERE mission_id = $1 AND user_id = $2`,
      [missionId, targetId],
    );

    // Default to observer; CHIEF can re-assign via /api/clearances afterwards.
    await client.query(
      `INSERT INTO clearance_assignments (user_id, mission_id, rank_id)
       VALUES ($1, $2, 'rank_observer')
       ON CONFLICT DO NOTHING`,
      [targetId, missionId],
    );

    const allParticipants = await getParticipantIds(client, missionId);
    await bumpVersions(client, allParticipants, 'participant_confirmed');

    await emitAudit(client, {
      userId: callerId, severity: 'INFO', source: 'Invites',
      text: `Participant confirmed: ${targetId}`, missionId,
    });

    await client.query('COMMIT');
    res.json({ ok: true });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[invites/confirm] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

// ─── /api/channels ────────────────────────────────────────────────────────────

/** POST /api/channels — create a channel. Requires level >= 5 (OPERATIVE) on mission. */
app.post('/api/channels', authFull, async (req, res) => {
  const userId = req.user.userId;
  const { missionId, name, description = '', categoryId, minClearanceToView, minClearanceToPost } = req.body;

  if (!missionId || !name || !categoryId) {
    return res.status(400).json({ error: 'missionId, name, and categoryId are required' });
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const level = await getClearanceLevel(client, userId, missionId);
    if (level < 5) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Operative clearance (level 5) required to create channels' });
    }

    // Resolve category defaults
    const catRow = await client.query(
      'SELECT default_min_clearance_view, default_min_clearance_post FROM schema_channel_categories WHERE id = $1',
      [categoryId],
    );
    if (!catRow.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(422).json({ error: `Category '${categoryId}' not found` });
    }
    const cat = catRow.rows[0];
    const viewLevel = minClearanceToView ?? cat.default_min_clearance_view;
    const postLevel = minClearanceToPost ?? cat.default_min_clearance_post;

    const channelId = genId('C');
    await client.query(
      `INSERT INTO channels (id, mission_id, name, description, category_id, min_clearance_view, min_clearance_post, created_by)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [channelId, missionId, name.trim(), description, categoryId, viewLevel, postLevel, userId],
    );

    const participants = await getParticipantIds(client, missionId);
    await bumpVersions(client, participants, 'mission');
    await emitAudit(client, { userId, severity: 'INFO', source: 'Channels', text: `Channel created: ${name}`, missionId });
    await client.query('COMMIT');

    const row = await pool.query('SELECT * FROM channels WHERE id = $1', [channelId]);
    const c = row.rows[0];
    res.status(201).json({
      id: c.id, missionId: c.mission_id, name: c.name, description: c.description,
      categoryId: c.category_id, minClearanceToView: c.min_clearance_view,
      minClearanceToPost: c.min_clearance_post, createdAtMs: new Date(c.created_at).getTime(),
      createdBy: c.created_by,
    });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[channels/create] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/** PUT /api/channels/:id — update. Requires rank_chief on parent mission. */
app.put('/api/channels/:id', authFull, async (req, res) => {
  const { id } = req.params;
  const userId = req.user.userId;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const chanRow = await client.query('SELECT mission_id FROM channels WHERE id = $1', [id]);
    if (!chanRow.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Channel not found' });
    }
    const missionId = chanRow.rows[0].mission_id;

    if (!(await isChief(client, userId, missionId))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    const { name, description, minClearanceToView, minClearanceToPost, categoryId } = req.body;
    await client.query(
      `UPDATE channels SET
         name               = COALESCE($1, name),
         description        = COALESCE($2, description),
         min_clearance_view = COALESCE($3, min_clearance_view),
         min_clearance_post = COALESCE($4, min_clearance_post),
         category_id        = COALESCE($5, category_id)
       WHERE id = $6`,
      [name ?? null, description ?? null, minClearanceToView ?? null, minClearanceToPost ?? null, categoryId ?? null, id],
    );

    const participants = await getParticipantIds(client, missionId);
    await bumpVersions(client, participants, 'mission');
    await client.query('COMMIT');

    const row = await pool.query('SELECT * FROM channels WHERE id = $1', [id]);
    res.json(row.rows[0]);
  } catch (e) {
    await client.query('ROLLBACK');
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/** DELETE /api/channels/:id — hard delete. Requires rank_chief on parent mission. */
app.delete('/api/channels/:id', authFull, async (req, res) => {
  const { id } = req.params;
  const userId = req.user.userId;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const chanRow = await client.query('SELECT mission_id FROM channels WHERE id = $1', [id]);
    if (!chanRow.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Channel not found' });
    }
    const missionId = chanRow.rows[0].mission_id;

    if (!(await isChief(client, userId, missionId))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    const participants = await getParticipantIds(client, missionId);
    // Also remove from the Signal relay's channel_members table
    await client.query('DELETE FROM channel_members WHERE channel_id = $1', [id]);
    await client.query('DELETE FROM channels WHERE id = $1', [id]);
    await bumpVersions(client, participants, 'mission');
    await emitAudit(client, { userId, severity: 'WARN', source: 'Channels', text: `Channel deleted: ${id}`, missionId });
    await client.query('COMMIT');
    res.status(204).end();
  } catch (e) {
    await client.query('ROLLBACK');
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

// ─── /api/clearances ─────────────────────────────────────────────────────────

/** PUT /api/clearances — assign or update a user's rank on a mission. Requires rank_chief. */
app.put('/api/clearances', authFull, async (req, res) => {
  const callerId = req.user.userId;
  const { userId, missionId, rankId } = req.body;

  if (!userId || !missionId || !rankId) {
    return res.status(400).json({ error: 'userId, missionId, and rankId are required' });
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    if (!(await isChief(client, callerId, missionId))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    // Prevent demoting the last chief
    const currentLevel = await getClearanceLevel(client, userId, missionId);
    const rankRow = await client.query('SELECT level FROM schema_ranks WHERE id = $1', [rankId]);
    if (!rankRow.rows[0]) {
      await client.query('ROLLBACK');
      return res.status(422).json({ error: `Rank '${rankId}' not found` });
    }
    const newLevel = rankRow.rows[0].level;

    if (currentLevel >= 9 && newLevel < 9 && (await countChiefs(client, missionId)) <= 1) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'last_chief', detail: 'Cannot demote the last chief' });
    }

    const prevRow = await client.query(
      'SELECT rank_id FROM clearance_assignments WHERE user_id = $1 AND mission_id = $2',
      [userId, missionId],
    );
    const previousRankId = prevRow.rows[0]?.rank_id ?? null;

    await client.query(
      `INSERT INTO clearance_assignments (user_id, mission_id, rank_id) VALUES ($1, $2, $3)
       ON CONFLICT (user_id, mission_id) DO UPDATE SET rank_id = EXCLUDED.rank_id`,
      [userId, missionId, rankId],
    );

    const participants = await getParticipantIds(client, missionId);
    await bumpVersions(client, participants, 'clearance');
    await emitAudit(client, { userId: callerId, severity: 'INFO', source: 'Admin', text: `Clearance changed: ${userId} → ${rankId}`, missionId });
    await client.query('COMMIT');

    res.json({ userId, missionId, rankId, previousRankId });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('[clearances/assign] error:', e.message);
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

/** DELETE /api/clearances/:userId/:missionId — remove a clearance. Requires rank_chief. */
app.delete('/api/clearances/:userId/:missionId', authFull, async (req, res) => {
  const { userId, missionId } = req.params;
  const callerId = req.user.userId;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    if (!(await isChief(client, callerId, missionId))) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'Chief clearance required' });
    }

    const currentLevel = await getClearanceLevel(client, userId, missionId);
    if (currentLevel >= 9 && (await countChiefs(client, missionId)) <= 1) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'last_chief', detail: 'Cannot remove the last chief clearance' });
    }

    await client.query('DELETE FROM clearance_assignments WHERE user_id = $1 AND mission_id = $2', [userId, missionId]);
    const participants = await getParticipantIds(client, missionId);
    await bumpVersions(client, participants, 'clearance');
    await emitAudit(client, { userId: callerId, severity: 'WARN', source: 'Admin', text: `Clearance removed: ${userId} from ${missionId}`, missionId });
    await client.query('COMMIT');
    res.status(204).end();
  } catch (e) {
    await client.query('ROLLBACK');
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

// ─── /api/schema ─────────────────────────────────────────────────────────────
// Generic CRUD for all four schema groups. Admin-only for mutations.

const SCHEMA_TABLES = {
  ranks:               { table: 'schema_ranks',               prefix: 'R' },
  'channel-categories': { table: 'schema_channel_categories',  prefix: 'CC' },
  'message-categories': { table: 'schema_message_categories',  prefix: 'MC' },
  'mission-types':      { table: 'schema_mission_types',        prefix: 'MT' },
};

// GET /api/schema/:group
Object.keys(SCHEMA_TABLES).forEach(group => {
  app.get(`/api/schema/${group}`, authFull, async (req, res) => {
    const { table } = SCHEMA_TABLES[group];
    try {
      const rows = await pool.query(`SELECT * FROM ${table} ORDER BY name`);
      res.json({ items: rows.rows });
    } catch (e) {
      res.status(500).json({ error: e.message });
    }
  });
});

// POST /api/schema/:group — create a schema record. Admin only.
app.post('/api/schema/ranks', authFull, async (req, res) => {
  if (!req.user.isAdmin) return res.status(403).json({ error: 'Admin required' });
  const { name, level, color } = req.body;
  if (!name || level == null || !color) return res.status(400).json({ error: 'name, level, color required' });
  if (level < 1 || level > 99) return res.status(422).json({ error: 'level must be 1–99' });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const id = genId('R');
    await client.query(
      'INSERT INTO schema_ranks (id, name, level, color) VALUES ($1,$2,$3,$4)',
      [id, name.trim().toUpperCase(), level, color],
    );
    await bumpVersions(client, await getAllUserIds(client), 'schema');
    await client.query('COMMIT');
    const row = await pool.query('SELECT * FROM schema_ranks WHERE id = $1', [id]);
    res.status(201).json(row.rows[0]);
  } catch (e) {
    await client.query('ROLLBACK');
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

app.post('/api/schema/channel-categories', authFull, async (req, res) => {
  if (!req.user.isAdmin) return res.status(403).json({ error: 'Admin required' });
  const { name, accent, defaultMinClearanceToView = 1, defaultMinClearanceToPost = 1 } = req.body;
  if (!name || !accent) return res.status(400).json({ error: 'name and accent required' });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const id = genId('CC');
    await client.query(
      `INSERT INTO schema_channel_categories (id, name, accent, default_min_clearance_view, default_min_clearance_post)
       VALUES ($1,$2,$3,$4,$5)`,
      [id, name.trim(), accent, defaultMinClearanceToView, defaultMinClearanceToPost],
    );
    await bumpVersions(client, await getAllUserIds(client), 'schema');
    await client.query('COMMIT');
    const row = await pool.query('SELECT * FROM schema_channel_categories WHERE id = $1', [id]);
    res.status(201).json(row.rows[0]);
  } catch (e) {
    await client.query('ROLLBACK');
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

app.post('/api/schema/message-categories', authFull, async (req, res) => {
  if (!req.user.isAdmin) return res.status(403).json({ error: 'Admin required' });
  const { name, accent, minClearanceToSend = 1 } = req.body;
  if (!name || !accent) return res.status(400).json({ error: 'name and accent required' });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const id = genId('MC');
    await client.query(
      'INSERT INTO schema_message_categories (id, name, accent, min_clearance_send) VALUES ($1,$2,$3,$4)',
      [id, name.trim(), accent, minClearanceToSend],
    );
    await bumpVersions(client, await getAllUserIds(client), 'schema');
    await client.query('COMMIT');
    const row = await pool.query('SELECT * FROM schema_message_categories WHERE id = $1', [id]);
    res.status(201).json(row.rows[0]);
  } catch (e) {
    await client.query('ROLLBACK');
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

app.post('/api/schema/mission-types', authFull, async (req, res) => {
  if (!req.user.isAdmin) return res.status(403).json({ error: 'Admin required' });
  const { name, accent, description = '' } = req.body;
  if (!name || !accent) return res.status(400).json({ error: 'name and accent required' });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const id = genId('MT');
    await client.query(
      'INSERT INTO schema_mission_types (id, name, accent, description) VALUES ($1,$2,$3,$4)',
      [id, name.trim(), accent, description],
    );
    await bumpVersions(client, await getAllUserIds(client), 'schema');
    await client.query('COMMIT');
    const row = await pool.query('SELECT * FROM schema_mission_types WHERE id = $1', [id]);
    res.status(201).json(row.rows[0]);
  } catch (e) {
    await client.query('ROLLBACK');
    res.status(500).json({ error: e.message });
  } finally {
    client.release();
  }
});

// PUT /api/schema/:group/:id — update. Admin only. System records cannot change is_system.
function makeSchemaUpdate(table) {
  return async (req, res) => {
    if (!req.user.isAdmin) return res.status(403).json({ error: 'Admin required' });
    const { id } = req.params;
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const existing = await client.query(`SELECT * FROM ${table} WHERE id = $1`, [id]);
      if (!existing.rows[0]) {
        await client.query('ROLLBACK');
        return res.status(404).json({ error: 'Not found' });
      }
      // Build dynamic SET clause from allowed body fields
      const allowed = ['name', 'level', 'color', 'accent',
        'default_min_clearance_view', 'default_min_clearance_post',
        'min_clearance_send', 'description'];
      const sets = [];
      const vals = [];
      let idx = 1;
      for (const key of allowed) {
        const camel = key.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
        const val = req.body[key] ?? req.body[camel];
        if (val !== undefined) {
          sets.push(`${key} = $${idx++}`);
          vals.push(val);
        }
      }
      if (sets.length > 0) {
        vals.push(id);
        await client.query(`UPDATE ${table} SET ${sets.join(', ')} WHERE id = $${idx}`, vals);
      }
      await bumpVersions(client, await getAllUserIds(client), 'schema');
      await client.query('COMMIT');
      const row = await pool.query(`SELECT * FROM ${table} WHERE id = $1`, [id]);
      res.json(row.rows[0]);
    } catch (e) {
      await client.query('ROLLBACK');
      res.status(500).json({ error: e.message });
    } finally {
      client.release();
    }
  };
}

app.put('/api/schema/ranks/:id',               authFull, makeSchemaUpdate('schema_ranks'));
app.put('/api/schema/channel-categories/:id',  authFull, makeSchemaUpdate('schema_channel_categories'));
app.put('/api/schema/message-categories/:id',  authFull, makeSchemaUpdate('schema_message_categories'));
app.put('/api/schema/mission-types/:id',       authFull, makeSchemaUpdate('schema_mission_types'));

// DELETE /api/schema/:group/:id — Admin only. System records cannot be deleted.
function makeSchemaDelete(table) {
  return async (req, res) => {
    if (!req.user.isAdmin) return res.status(403).json({ error: 'Admin required' });
    const { id } = req.params;
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const existing = await client.query(`SELECT is_system FROM ${table} WHERE id = $1`, [id]);
      if (!existing.rows[0]) {
        await client.query('ROLLBACK');
        return res.status(404).json({ error: 'Not found' });
      }
      if (existing.rows[0].is_system) {
        await client.query('ROLLBACK');
        return res.status(422).json({ error: 'system_record', detail: 'System records cannot be deleted' });
      }
      try {
        await client.query(`DELETE FROM ${table} WHERE id = $1`, [id]);
      } catch (e) {
        if (e.code === '23503') {
          await client.query('ROLLBACK');
          return res.status(409).json({ error: 'in_use', detail: 'Record is referenced by existing data' });
        }
        throw e;
      }
      await bumpVersions(client, await getAllUserIds(client), 'schema');
      await client.query('COMMIT');
      res.status(204).end();
    } catch (e) {
      await client.query('ROLLBACK');
      res.status(500).json({ error: e.message });
    } finally {
      client.release();
    }
  };
}

app.delete('/api/schema/ranks/:id',               authFull, makeSchemaDelete('schema_ranks'));
app.delete('/api/schema/channel-categories/:id',  authFull, makeSchemaDelete('schema_channel_categories'));
app.delete('/api/schema/message-categories/:id',  authFull, makeSchemaDelete('schema_message_categories'));
app.delete('/api/schema/mission-types/:id',       authFull, makeSchemaDelete('schema_mission_types'));

// ─── /api/audit ───────────────────────────────────────────────────────────────

/** GET /api/audit — paginated audit log scoped to the caller's missions. */
app.get('/api/audit', authFull, async (req, res) => {
  const userId = req.user.userId;
  const limit = Math.min(parseInt(req.query.limit ?? '100', 10), 500);
  const since = req.query.since ? new Date(req.query.since) : null;
  const severity = req.query.severity ?? null;
  const cursor = req.query.cursor ? parseInt(req.query.cursor, 10) : null;

  try {
    const conditions = [
      `(ae.user_id = $1 OR ae.mission_id IN (
         SELECT mission_id FROM mission_participants WHERE user_id = $1
       ) OR ($2 AND ae.mission_id IS NULL))`,
    ];
    const vals = [userId, req.user.isAdmin];
    let i = 3;
    if (since) { conditions.push(`ae.occurred_at >= $${i++}`); vals.push(since); }
    if (severity) { conditions.push(`ae.severity = $${i++}`); vals.push(severity); }
    if (cursor) { conditions.push(`ae.id < $${i++}`); vals.push(cursor); }

    const rows = await pool.query(
      `SELECT ae.* FROM audit_events ae
       WHERE ${conditions.join(' AND ')}
       ORDER BY ae.occurred_at DESC, ae.id DESC
       LIMIT $${i}`,
      [...vals, limit + 1],
    );

    const hasMore = rows.rows.length > limit;
    const items = hasMore ? rows.rows.slice(0, limit) : rows.rows;
    const nextCursor = hasMore ? items[items.length - 1].id : null;

    res.json({
      items: items.map(r => ({
        id: Number(r.id), userId: r.user_id, severity: r.severity,
        source: r.source, text: r.text, missionId: r.mission_id,
        occurredAt: r.occurred_at,
      })),
      nextCursor: nextCursor ? String(nextCursor) : null,
    });
  } catch (e) {
    console.error('[audit/get] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

/** POST /api/audit — append a client-originated audit event. */
app.post('/api/audit', authFull, async (req, res) => {
  const userId = req.user.userId;
  const { severity, source, text } = req.body;
  const valid = ['INFO', 'WARN', 'ALERT', 'CRITICAL'];
  if (!severity || !source || !text) {
    return res.status(400).json({ error: 'severity, source, and text are required' });
  }
  if (!valid.includes(severity)) {
    return res.status(422).json({ error: `severity must be one of: ${valid.join(', ')}` });
  }
  try {
    await pool.query(
      'INSERT INTO audit_events (user_id, severity, source, text) VALUES ($1,$2,$3,$4)',
      [userId, severity, source, text],
    );
    res.status(201).end();
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─── WebSocket — real-time push ───────────────────────────────────────────────

function setupWebSocket(server) {
  const wss = new WebSocketServer({ server, path: '/v1/websocket' });

  wss.on('connection', (ws) => {
    // Auth via first message frame: { type: "auth", token: "<jwt>" }
    // Server closes with 4001 if auth is missing or invalid within 5 seconds.
    let authenticated = false;

    const authTimeout = setTimeout(() => {
      if (!authenticated) {
        ws.close(4001, 'Auth timeout');
      }
    }, 5000);

    ws.once('message', async (data) => {
      clearTimeout(authTimeout);
      let userId;

      try {
        const msg = JSON.parse(data.toString());
        if (msg.type !== 'auth' || !msg.token) {
          ws.close(4001, 'Expected auth frame');
          return;
        }
        const decoded = jwt.verify(msg.token, JWT_SECRET);
        userId = decoded.userId;
      } catch {
        ws.close(4001, 'Invalid token');
        return;
      }

      // Check tombstone
      try {
        const uRow = await pool.query('SELECT tombstoned FROM users WHERE user_id = $1', [userId]);
        if (!uRow.rows[0] || uRow.rows[0].tombstoned) {
          ws.close(4410, 'Device terminated');
          return;
        }
      } catch {
        ws.close(4500, 'Internal error');
        return;
      }

      authenticated = true;
      connections.set(userId, ws);
      console.log(`[ws] connected userId=${userId} total=${connections.size}`);

      // Flush queued messages
      try {
        const rows = await pool.query(
          `SELECT id, sender_id, channel_id, message_type, ciphertext
           FROM message_queue WHERE recipient_id = $1 ORDER BY id`,
          [userId],
        );
        for (const r of rows.rows) {
          ws.send(JSON.stringify({
            type: 'message', id: r.id, senderId: r.sender_id,
            channelId: r.channel_id, messageType: r.message_type, ciphertext: r.ciphertext,
          }));
        }
      } catch (e) {
        console.error('[ws] flush error:', e.message);
      }

      // Ongoing message handler (ACKs + any future client→server frames)
      ws.on('message', async (data) => {
        try {
          const msg = JSON.parse(data.toString());
          if (msg.type === 'ack' && Array.isArray(msg.messageIds) && msg.messageIds.length > 0) {
            await pool.query(
              'DELETE FROM message_queue WHERE id = ANY($1::bigint[])',
              [msg.messageIds],
            );
          }
        } catch (e) {
          console.error('[ws] message error:', e.message);
        }
      });

      ws.on('close', () => {
        connections.delete(userId);
        console.log(`[ws] disconnected userId=${userId} total=${connections.size}`);
      });

      ws.on('error', (e) => console.error(`[ws] error userId=${userId}:`, e.message));
    });

    ws.on('error', (e) => console.error('[ws] pre-auth error:', e.message));
  });
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function getLanIp() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const addr of ifaces) {
      if (addr.family === 'IPv4' && !addr.internal) return addr.address;
    }
  }
  return null;
}

// ─── Start ────────────────────────────────────────────────────────────────────

async function start() {
  await initSchema();
  await seedSystemData();

  let server;
  const scheme = process.env.TLS_CERT && process.env.TLS_KEY ? 'https' : 'http';
  const wsScheme = scheme === 'https' ? 'wss' : 'ws';

  if (scheme === 'https') {
    const tlsOptions = {
      cert: fs.readFileSync(process.env.TLS_CERT),
      key:  fs.readFileSync(process.env.TLS_KEY),
    };
    server = https.createServer(tlsOptions, app);
    console.log('TLS enabled.');
  } else {
    server = http.createServer(app);
    console.log('TLS not configured — plain HTTP (dev only).');
  }

  setupWebSocket(server);

  server.listen(PORT, HOST, () => {
    const lanIp = getLanIp();
    console.log(`AstraSecure server listening on ${HOST}:${PORT}`);
    console.log(`  Health:    ${scheme}://localhost:${PORT}/health`);
    console.log(`  Sync:      ${scheme}://localhost:${PORT}/api/sync`);
    console.log(`  WebSocket: ${wsScheme}://localhost:${PORT}/v1/websocket`);
    if (lanIp && HOST === '0.0.0.0') {
      console.log(`  LAN:       ${scheme}://${lanIp}:${PORT}`);
    }
  });
}

start().catch((e) => {
  console.error('Fatal startup error:', e.message);
  process.exit(1);
});
