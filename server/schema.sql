-- AstraSecure — PostgreSQL schema
-- Applied on every server boot via initSchema() (all statements are idempotent).

-- ─── Signal relay tables ──────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    user_id            TEXT        PRIMARY KEY,
    display_name       TEXT        NOT NULL,
    registration_id    INTEGER     NOT NULL,
    identity_key       TEXT        NOT NULL,   -- base64 serialized IdentityKey public bytes
    tombstoned         BOOLEAN     NOT NULL DEFAULT FALSE,
    is_admin           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

-- Add new columns to existing installs that pre-date this migration
ALTER TABLE users ADD COLUMN IF NOT EXISTS tombstoned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin   BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS signed_prekeys (
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    spk_id             INTEGER     NOT NULL,
    spk_public         TEXT        NOT NULL,
    spk_signature      TEXT        NOT NULL,
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, spk_id)
);

CREATE TABLE IF NOT EXISTS one_time_prekeys (
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    opk_id             INTEGER     NOT NULL,
    opk_public         TEXT        NOT NULL,
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, opk_id)
);

CREATE INDEX IF NOT EXISTS idx_opk_user ON one_time_prekeys(user_id);

-- Kyber-1024 KEM pre-key (PQXDH). One signed key per user, rotated weekly with SPK.
-- public_key: base64 KEMPublicKey.serialize() bytes (~1568 raw → ~2092 b64 chars).
-- signature:  base64 Ed25519 signature over public_key, signed by the user's IdentityKeyPair.
CREATE TABLE IF NOT EXISTS kyber_prekeys (
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    key_id             INTEGER     NOT NULL,
    public_key         TEXT        NOT NULL,
    signature          TEXT        NOT NULL,
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id)
);

CREATE TABLE IF NOT EXISTS message_queue (
    id                 BIGSERIAL   PRIMARY KEY,
    recipient_id       TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    sender_id          TEXT        NOT NULL,
    channel_id         TEXT        NOT NULL,
    message_type       SMALLINT    NOT NULL,
    -- 1 = PreKeySignalMessage
    -- 2 = SignalMessage (whisper)
    -- 3 = SenderKeyDistributionMessage
    -- 4 = SenderKeyMessage (channel)
    ciphertext         TEXT        NOT NULL,
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_queue_recipient ON message_queue(recipient_id, id);

CREATE TABLE IF NOT EXISTS channel_members (
    channel_id         TEXT        NOT NULL,
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    joined_at          TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (channel_id, user_id)
);

-- ─── AstraSecure schema tables ────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS schema_ranks (
    id          TEXT    PRIMARY KEY,
    name        TEXT    NOT NULL,
    level       INTEGER NOT NULL CHECK (level BETWEEN 1 AND 99),
    color       TEXT    NOT NULL,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS schema_channel_categories (
    id                         TEXT    PRIMARY KEY,
    name                       TEXT    NOT NULL,
    accent                     TEXT    NOT NULL,
    default_min_clearance_view INTEGER NOT NULL DEFAULT 1,
    default_min_clearance_post INTEGER NOT NULL DEFAULT 1,
    is_system                  BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS schema_message_categories (
    id                 TEXT    PRIMARY KEY,
    name               TEXT    NOT NULL,
    accent             TEXT    NOT NULL,
    min_clearance_send INTEGER NOT NULL DEFAULT 1,
    is_system          BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS schema_mission_types (
    id          TEXT    PRIMARY KEY,
    name        TEXT    NOT NULL,
    accent      TEXT    NOT NULL,
    description TEXT    NOT NULL DEFAULT '',
    is_system   BOOLEAN NOT NULL DEFAULT FALSE
);

-- ─── Mission tables ───────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS missions (
    id                TEXT        PRIMARY KEY,
    name              TEXT        NOT NULL,
    type_id           TEXT        NOT NULL REFERENCES schema_mission_types(id),
    status            TEXT        NOT NULL DEFAULT 'ACTIVE'
                                  CHECK (status IN ('ACTIVE','STANDBY','COMPROMISED','ARCHIVED')),
    phase             TEXT,
    mission_key_alias TEXT        NOT NULL DEFAULT '',
    created_by        TEXT        NOT NULL REFERENCES users(user_id),
    created_at        TIMESTAMPTZ DEFAULT NOW(),
    last_activity_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS mission_participants (
    mission_id  TEXT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    user_id     TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    status      TEXT NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','PENDING')),
    invited_by  TEXT REFERENCES users(user_id),
    invited_at  TIMESTAMPTZ,
    PRIMARY KEY (mission_id, user_id)
);

-- Backfill for installs that pre-date the invite/pending workflow
ALTER TABLE mission_participants
    ADD COLUMN IF NOT EXISTS status     TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','PENDING'));
ALTER TABLE mission_participants
    ADD COLUMN IF NOT EXISTS invited_by TEXT REFERENCES users(user_id);
ALTER TABLE mission_participants
    ADD COLUMN IF NOT EXISTS invited_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_mp_user   ON mission_participants(user_id);
CREATE INDEX IF NOT EXISTS idx_mp_status ON mission_participants(mission_id, status);

-- ─── Invite tokens ────────────────────────────────────────────────────────────
-- Single-use, time-bound tokens that bind a mission membership to a verified
-- identity key on first contact. Issued by a CHIEF, redeemed by an operator,
-- confirmed by the original issuer (or any CHIEF) after out-of-band SAS check.

CREATE TABLE IF NOT EXISTS invites (
    token         TEXT        PRIMARY KEY,
    mission_id    TEXT        NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    issued_by     TEXT        NOT NULL REFERENCES users(user_id),
    default_rank  TEXT        NOT NULL REFERENCES schema_ranks(id),
    expires_at    TIMESTAMPTZ NOT NULL,
    redeemed_by   TEXT        REFERENCES users(user_id),
    redeemed_at   TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_invites_mission ON invites(mission_id);
CREATE INDEX IF NOT EXISTS idx_invites_open    ON invites(mission_id)
    WHERE redeemed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS channels (
    id                 TEXT        PRIMARY KEY,
    mission_id         TEXT        NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    name               TEXT        NOT NULL,
    description        TEXT        NOT NULL DEFAULT '',
    category_id        TEXT        NOT NULL REFERENCES schema_channel_categories(id),
    min_clearance_view INTEGER     NOT NULL DEFAULT 1,
    min_clearance_post INTEGER     NOT NULL DEFAULT 1,
    created_by         TEXT        NOT NULL REFERENCES users(user_id),
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_channels_mission ON channels(mission_id);

CREATE TABLE IF NOT EXISTS clearance_assignments (
    user_id     TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    mission_id  TEXT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    rank_id     TEXT NOT NULL REFERENCES schema_ranks(id),
    PRIMARY KEY (user_id, mission_id)
);

CREATE INDEX IF NOT EXISTS idx_clearance_mission ON clearance_assignments(mission_id);

-- ─── Audit & sync ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS audit_events (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     TEXT        REFERENCES users(user_id),
    severity    TEXT        NOT NULL CHECK (severity IN ('INFO','WARN','ALERT','CRITICAL')),
    source      TEXT        NOT NULL,
    text        TEXT        NOT NULL,
    mission_id  TEXT        REFERENCES missions(id),
    occurred_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_events(user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_mission ON audit_events(mission_id, occurred_at DESC);

-- Per-user monotonic version counter. Bumped on every mutation that affects
-- what GET /api/sync returns for that user. ETag = version number (as string).
CREATE TABLE IF NOT EXISTS sync_versions (
    user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 0
);
