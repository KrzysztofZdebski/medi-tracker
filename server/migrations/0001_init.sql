CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    email_verified_at INTEGER,
    created_at INTEGER NOT NULL,
    kdf TEXT NOT NULL,
    salt_pw BLOB,
    auth_hash BLOB,
    slot1 BLOB,
    slot2 BLOB,
    recovery_auth_hash BLOB,
    active_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE email_codes (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose TEXT NOT NULL,
    code_hash BLOB NOT NULL,
    expires_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, purpose)
);

CREATE TABLE sessions (
    token_hash BLOB PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    last_used_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX sessions_user_id ON sessions(user_id);

CREATE TABLE reset_tokens (
    token_hash BLOB PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL
);

CREATE TABLE backups (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    nonce BLOB,
    ciphertext BLOB,
    updated_at INTEGER
);
