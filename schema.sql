CREATE TABLE IF NOT EXISTS activation_keys (
  key_hash TEXT PRIMARY KEY,
  key_hint TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_activation_keys_created
ON activation_keys(created_at);

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  key_hash TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_last_seen
ON devices(last_seen);
