-- AstraSecure Signal relay — PostgreSQL schema
-- Run once: psql -U postgres -d astrasecure -f schema.sql

CREATE TABLE IF NOT EXISTS users (
    user_id            TEXT        PRIMARY KEY,
    display_name       TEXT        NOT NULL,
    registration_id    INTEGER     NOT NULL,
    identity_key       TEXT        NOT NULL,   -- base64 serialized IdentityKey public bytes
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS signed_prekeys (
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    spk_id             INTEGER     NOT NULL,
    spk_public         TEXT        NOT NULL,   -- base64 serialized ECPublicKey
    spk_signature      TEXT        NOT NULL,   -- base64 signature
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, spk_id)
);

CREATE TABLE IF NOT EXISTS one_time_prekeys (
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    opk_id             INTEGER     NOT NULL,
    opk_public         TEXT        NOT NULL,   -- base64 serialized ECPublicKey
    created_at         TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, opk_id)
);

CREATE INDEX IF NOT EXISTS idx_opk_user ON one_time_prekeys(user_id);

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
    ciphertext         TEXT        NOT NULL,   -- base64 serialized Signal ciphertext
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_queue_recipient ON message_queue(recipient_id, id);

CREATE TABLE IF NOT EXISTS channel_members (
    channel_id         TEXT        NOT NULL,
    user_id            TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    joined_at          TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (channel_id, user_id)
);
