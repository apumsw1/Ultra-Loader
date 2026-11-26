CREATE TABLE IF NOT EXISTS activation_keys (
  key_hash TEXT PRIMARY KEY,
  key_hint TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  max_devices INTEGER NOT NULL DEFAULT 1,
  owner_id TEXT NOT NULL DEFAULT 'MAIN',
  purchase_id TEXT,
  paid_amount INTEGER NOT NULL DEFAULT 0,
  duration_minutes INTEGER NOT NULL DEFAULT 0,
  duration_days INTEGER NOT NULL DEFAULT 0,
  plan_id TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  activated_at TEXT NOT NULL DEFAULT '',
  last_reset_at TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_activation_keys_created
ON activation_keys(created_at);

CREATE INDEX IF NOT EXISTS idx_keys_owner
ON activation_keys(owner_id);

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT NOT NULL,
  key_hash TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  created_at TEXT NOT NULL,
  device_name TEXT NOT NULL DEFAULT '',
  device_model TEXT NOT NULL DEFAULT '',
  os_version TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (device_id, key_hash)
);

CREATE INDEX IF NOT EXISTS idx_devices_last_seen
ON devices(last_seen);

CREATE INDEX IF NOT EXISTS idx_devices_key
ON devices(key_hash);

CREATE INDEX IF NOT EXISTS idx_devices_device
ON devices(device_id);

CREATE TABLE IF NOT EXISTS pricing_plans (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  duration_days INTEGER NOT NULL,
  duration_minutes INTEGER NOT NULL,
  price INTEGER NOT NULL,
  is_default INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS subadmin_pricing_overrides (
  subadmin_id TEXT NOT NULL,
  plan_id TEXT NOT NULL,
  price INTEGER NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (subadmin_id, plan_id)
);

CREATE TABLE IF NOT EXISTS subadmins (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  salt TEXT NOT NULL,
  balance INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wallet_transactions (
  id TEXT PRIMARY KEY,
  subadmin_id TEXT NOT NULL,
  amount INTEGER NOT NULL,
  type TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  actor_id TEXT NOT NULL,
  request_id TEXT UNIQUE,
  username_snapshot TEXT NOT NULL DEFAULT '',
  key_hash TEXT NOT NULL DEFAULT '',
  key_hint TEXT NOT NULL DEFAULT '',
  balance_before INTEGER NOT NULL DEFAULT 0,
  balance_after INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wallet_sub
ON wallet_transactions(subadmin_id, created_at);

CREATE TABLE IF NOT EXISTS key_transactions (
  id TEXT PRIMARY KEY,
  request_id TEXT UNIQUE,
  key_hash TEXT NOT NULL,
  key_hint TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  actor_type TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  action_type TEXT NOT NULL,
  plan_id TEXT NOT NULL DEFAULT '',
  plan_name TEXT NOT NULL DEFAULT '',
  previous_duration_minutes INTEGER NOT NULL DEFAULT 0,
  new_duration_minutes INTEGER NOT NULL DEFAULT 0,
  previous_paid_amount INTEGER NOT NULL DEFAULT 0,
  plan_price INTEGER NOT NULL DEFAULT 0,
  charged_amount INTEGER NOT NULL DEFAULT 0,
  new_total_paid INTEGER NOT NULL DEFAULT 0,
  previous_expires_at TEXT NOT NULL DEFAULT '',
  new_expires_at TEXT NOT NULL DEFAULT '',
  balance_before INTEGER NOT NULL DEFAULT 0,
  balance_after INTEGER NOT NULL DEFAULT 0,
  note TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_key_tx_key
ON key_transactions(key_hash, created_at);

CREATE INDEX IF NOT EXISTS idx_key_tx_owner
ON key_transactions(owner_id, created_at);

CREATE TABLE IF NOT EXISTS billing_requests (
  request_id TEXT PRIMARY KEY,
  actor_type TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  operation TEXT NOT NULL,
  key_hash TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'COMPLETED',
  response_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS key_requests (
  request_id TEXT PRIMARY KEY,
  subadmin_id TEXT NOT NULL,
  key_hash TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ul_settings (
  setting_key TEXT PRIMARY KEY,
  setting_value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS app_updates (
  id INTEGER PRIMARY KEY CHECK(id=1),
  version_code INTEGER NOT NULL DEFAULT 1,
  version_name TEXT NOT NULL DEFAULT '1.0',
  download_url TEXT NOT NULL DEFAULT '',
  message TEXT NOT NULL DEFAULT '',
  force_update INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS flash_messages (
  id INTEGER PRIMARY KEY CHECK(id=1),
  title TEXT NOT NULL DEFAULT '',
  message TEXT NOT NULL DEFAULT '',
  active INTEGER NOT NULL DEFAULT 0,
  file_url TEXT NOT NULL DEFAULT '',
  file_name TEXT NOT NULL DEFAULT '',
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
