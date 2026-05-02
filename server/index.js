'use strict';

require('dotenv').config();

const express    = require('express');
const http       = require('http');
const https      = require('https');
const fs         = require('fs');
const url        = require('url');
const { Pool }   = require('pg');
const jwt        = require('jsonwebtoken');
const { WebSocketServer } = require('ws');

// ─── Config ───────────────────────────────────────────────────────────────────

const PORT       = parseInt(process.env.PORT || '3000', 10);
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

// ─── GET /health ──────────────────────────────────────────────────────────────

app.get('/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'ok', ts: new Date().toISOString() });
  } catch (e) {
    res.status(503).json({ status: 'db_error', error: e.message });
  }
});

// ─── POST /v1/users — Register ────────────────────────────────────────────────

app.post('/v1/users', async (req, res) => {
  const { userId, displayName, registrationId, identityKey, signedPreKey, oneTimePreKeys } = req.body;

  if (!userId || !displayName || !registrationId || !identityKey || !signedPreKey) {
    return res.status(400).json({ error: 'Missing required fields' });
  }
  if (!signedPreKey.id || !signedPreKey.publicKey || !signedPreKey.signature) {
    return res.status(400).json({ error: 'Invalid signedPreKey' });
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

// ─── DELETE /v1/users/:userId — Revoke (panic wipe) ──────────────────────────

app.delete('/v1/users/:userId', auth, async (req, res) => {
  const { userId } = req.params;
  if (req.user.userId !== userId) {
    return res.status(403).json({ error: 'Forbidden' });
  }
  try {
    await pool.query('DELETE FROM users WHERE user_id = $1', [userId]);
    console.log(`[revoke] userId=${userId} wiped from server`);
    res.json({ ok: true });
  } catch (e) {
    console.error('[revoke] error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─── GET /v1/keys/:userId — Fetch pre-key bundle ─────────────────────────────

app.get('/v1/keys/:userId', auth, async (req, res) => {
  const { userId } = req.params;

  try {
    // Fetch user + their latest signed pre-key
    const userRows = await pool.query(
      `SELECT u.registration_id, u.identity_key,
              s.spk_id, s.spk_public, s.spk_signature
       FROM users u
       JOIN signed_prekeys s ON s.user_id = u.user_id
       WHERE u.user_id = $1
       ORDER BY s.spk_id DESC
       LIMIT 1`,
      [userId],
    );
    if (userRows.rows.length === 0) {
      return res.status(404).json({ error: 'User not found' });
    }
    const u = userRows.rows[0];

    // Atomically pop the lowest-id OPK
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

    // Notify the key owner if OPK count is now low
    const countRow = await pool.query(
      'SELECT COUNT(*) AS c FROM one_time_prekeys WHERE user_id = $1',
      [userId],
    );
    const remaining = parseInt(countRow.rows[0].c, 10);
    if (remaining < 10) {
      pushToUser(userId, { type: 'keysNeeded', currentCount: remaining });
    }

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

// ─── POST /v1/messages/:recipientId — Send message ───────────────────────────

app.post('/v1/messages/:recipientId', auth, async (req, res) => {
  const { recipientId } = req.params;
  const { channelId, messageType, ciphertext } = req.body;
  const senderId = req.user.userId;

  if (!channelId || !messageType || !ciphertext) {
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

    // Push to recipient if they have an active WebSocket connection
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
    // If recipient doesn't exist yet, still accept the message
    if (e.code === '23503') {
      return res.status(404).json({ error: 'Recipient not registered' });
    }
    res.status(500).json({ error: e.message });
  }
});

// ─── GET /v1/messages — Poll queued messages ─────────────────────────────────

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

// ─── POST /v1/channels/:channelId/members — Join channel ────────────────────

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

// ─── GET /v1/channels/:channelId/members — List members ─────────────────────

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

// ─── WebSocket — real-time push ───────────────────────────────────────────────

// userId → active WebSocket connection
const connections = new Map();

function pushToUser(userId, payload) {
  const ws = connections.get(userId);
  if (ws && ws.readyState === 1 /* OPEN */) {
    ws.send(JSON.stringify(payload));
  }
}

function setupWebSocket(server) {
  const wss = new WebSocketServer({ server, path: '/v1/websocket' });

  wss.on('connection', async (ws, req) => {
    const { query } = url.parse(req.url, true);
    let userId;
    try {
      ({ userId } = jwt.verify(query.token, JWT_SECRET));
    } catch {
      ws.close(4001, 'Invalid token');
      return;
    }

    connections.set(userId, ws);
    console.log(`[ws] connected userId=${userId} total=${connections.size}`);

    // Flush any messages queued while the device was offline
    try {
      const rows = await pool.query(
        `SELECT id, sender_id, channel_id, message_type, ciphertext
         FROM message_queue
         WHERE recipient_id = $1
         ORDER BY id`,
        [userId],
      );
      for (const r of rows.rows) {
        ws.send(JSON.stringify({
          type:        'message',
          id:          r.id,
          senderId:    r.sender_id,
          channelId:   r.channel_id,
          messageType: r.message_type,
          ciphertext:  r.ciphertext,
        }));
      }
    } catch (e) {
      console.error('[ws] flush error:', e.message);
    }

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
}

// ─── Start ────────────────────────────────────────────────────────────────────

async function start() {
  await initSchema();

  let server;
  if (process.env.TLS_CERT && process.env.TLS_KEY) {
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

  server.listen(PORT, () => {
    console.log(`AstraSecure relay server listening on :${PORT}`);
    console.log(`  Health check: http://localhost:${PORT}/health`);
    console.log(`  WebSocket:    ws://localhost:${PORT}/v1/websocket`);
  });
}

start().catch((e) => {
  console.error('Fatal startup error:', e.message);
  process.exit(1);
});
