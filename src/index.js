const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization"
};

const enc = new TextEncoder();

const DEFAULT_PRICING_PLANS = [
  { id: "plan_1d", name: "1 Day", durationDays: 1, durationMinutes: 1440, price: 50, sortOrder: 1 },
  { id: "plan_2d", name: "2 Days", durationDays: 2, durationMinutes: 2880, price: 100, sortOrder: 2 },
  { id: "plan_3d", name: "3 Days", durationDays: 3, durationMinutes: 4320, price: 150, sortOrder: 3 },
  { id: "plan_7d", name: "7 Days", durationDays: 7, durationMinutes: 10080, price: 200, sortOrder: 4 },
  { id: "plan_15d", name: "15 Days", durationDays: 15, durationMinutes: 21600, price: 300, sortOrder: 5 },
  { id: "plan_30d", name: "30 Days", durationDays: 30, durationMinutes: 43200, price: 400, sortOrder: 6 }
];

const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: {
    ...CORS,
    "Content-Type": "application/json; charset=utf-8"
  }
});

const nowISO = () => new Date().toISOString();

async function cleanupExpiredDeviceRegistries(db, now = nowISO()) {
  const results = await db.batch([
    db.prepare(`
      UPDATE activation_keys
      SET status = 'EXPIRED'
      WHERE revoked = 0 AND expires_at <= ? AND status <> 'EXPIRED'
    `).bind(now),

    db.prepare(`
      UPDATE activation_keys
      SET status = 'REVOKED'
      WHERE revoked = 1 AND status <> 'REVOKED'
    `),

    db.prepare(`
      DELETE FROM devices
      WHERE key_hash IN (
        SELECT key_hash
        FROM activation_keys
        WHERE expires_at <= ? OR revoked = 1
      )
    `).bind(now)
  ]);

  return Number(results[2]?.meta?.changes || 0);
}

const str = (v, max = 500) => String(v ?? "").trim().slice(0, max);

function b64u(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function unb64u(s) {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  s += "=".repeat((4 - s.length % 4) % 4);
  return Uint8Array.from(atob(s), c => c.charCodeAt(0));
}

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(d)]
    .map(x => x.toString(16).padStart(2, "0"))
    .join("");
}

async function hmac(s, secret) {
  const k = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );

  return new Uint8Array(
    await crypto.subtle.sign("HMAC", k, enc.encode(s))
  );
}

async function tokenFor(payload, secret) {
  const p = b64u(enc.encode(JSON.stringify({
    ...payload,
    exp: Date.now() + 8 * 60 * 60 * 1000
  })));

  return p + "." + b64u(await hmac(p, secret));
}

async function verifyToken(token, secret) {
  try {
    const a = token.split(".");
    if (a.length !== 2) return null;

    const expected = await hmac(a[0], secret);
    const got = unb64u(a[1]);

    if (expected.length !== got.length) return null;

    let diff = 0;
    for (let i = 0; i < expected.length; i++) {
      diff |= expected[i] ^ got[i];
    }

    if (diff) return null;

    const p = JSON.parse(
      new TextDecoder().decode(unb64u(a[0]))
    );

    return p.exp > Date.now() ? p : null;
  } catch {
    return null;
  }
}

function makeKey(customSuffix = "") {
  const suffix = str(customSuffix, 80).replace(/^UL-/i, "").toUpperCase();

  if (suffix) {
    if (!/^[A-Z0-9][A-Z0-9-]{2,79}$/.test(suffix)) {
      throw new Error("Custom key suffix must be 3–80 letters, numbers or hyphens");
    }
    return "UL-" + suffix;
  }

  return "UL-" + [...crypto.getRandomValues(new Uint8Array(18))]
    .map(x => x.toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
}

function durationMinutesFromBody(body) {
  if (body.durationMinutes !== undefined && body.durationMinutes !== null && body.durationMinutes !== "") {
    const minutes = Number(body.durationMinutes);
    if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 3650 * 1440) {
      throw new Error("Validity must be 1 minute to 3650 days");
    }
    return minutes;
  }

  if (body.durationDays !== undefined && body.durationDays !== null && body.durationDays !== "") {
    const days = Number(body.durationDays);
    if (!Number.isSafeInteger(days) || days < 1 || days > 3650) {
      throw new Error("Validity must be 1 to 3650 days");
    }
    return days * 1440;
  }

  if (body.expiresAt !== undefined && body.expiresAt !== null && body.expiresAt !== "") {
    const expiry = Date.parse(body.expiresAt);
    const remaining = expiry - Date.now();
    if (!Number.isFinite(expiry) || remaining < 1 || remaining > 3650 * 86400000) {
      throw new Error("Expiry must be a future date within 3650 days");
    }
    return Math.ceil(remaining / 60000);
  }

  throw new Error("Provide planId, durationMinutes, durationDays, or expiresAt");
}

async function passwordHash(password, salt) {
  const material = await crypto.subtle.importKey(
    "raw",
    enc.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"]
  );

  const bits = await crypto.subtle.deriveBits({
    name: "PBKDF2",
    salt: enc.encode(salt),
    iterations: 100000,
    hash: "SHA-256"
  }, material, 256);

  return [...new Uint8Array(bits)]
    .map(x => x.toString(16).padStart(2, "0"))
    .join("");
}

async function readBody(req) {
  try {
    return await req.json();
  } catch {
    throw new Error("Invalid JSON request");
  }
}

function validDuration(body) {
  const n = Date.now();

  if (body.durationMinutes !== undefined && body.durationMinutes !== null && body.durationMinutes !== "") {
    const m = Number(body.durationMinutes);

    if (!Number.isSafeInteger(m) || m < 1 || m > 3650 * 1440) {
      throw new Error("Validity must be 1 minute to 3650 days");
    }

    return new Date(n + m * 60000).toISOString();
  }

  if (body.durationDays !== undefined && body.durationDays !== null && body.durationDays !== "") {
    const d = Number(body.durationDays);
    if (!Number.isSafeInteger(d) || d < 1 || d > 3650) {
      throw new Error("Validity must be 1 to 3650 days");
    }
    return new Date(n + d * 86400000).toISOString();
  }

  if (body.expiresAt !== undefined && body.expiresAt !== null && body.expiresAt !== "") {
    const t = Date.parse(body.expiresAt);

    if (!Number.isFinite(t) || t <= n || t > n + 3650 * 86400000) {
      throw new Error("Expiry must be a future date within 3650 days");
    }

    return new Date(t).toISOString();
  }

  throw new Error("Provide durationMinutes, durationDays or expiresAt");
}

async function tableInfo(db, table) {
  const r = await db.prepare(`PRAGMA table_info(${table})`).all();
  return r.results || [];
}

async function columns(db, table) {
  const info = await tableInfo(db, table);
  return new Set(info.map(x => x.name));
}

async function ensureColumn(db, table, name, definition) {
  const c = await columns(db, table);

  if (!c.has(name)) {
    await db.prepare(
      `ALTER TABLE ${table} ADD COLUMN ${name} ${definition}`
    ).run();
  }
}

async function migrateDevicesMultiKey(db) {
  const info = await tableInfo(db, "devices");
  const pkCols = info.filter(col => Number(col.pk) > 0);
  if (pkCols.length >= 2) {
    return;
  }

  await db.batch([
    db.prepare(`CREATE TABLE IF NOT EXISTS devices_v2 (
      device_id TEXT NOT NULL,
      key_hash TEXT NOT NULL,
      last_seen TEXT NOT NULL,
      created_at TEXT NOT NULL,
      device_name TEXT NOT NULL DEFAULT '',
      device_model TEXT NOT NULL DEFAULT '',
      os_version TEXT NOT NULL DEFAULT '',
      app_version TEXT NOT NULL DEFAULT '',
      PRIMARY KEY (device_id, key_hash)
    )`),
    db.prepare(`INSERT OR IGNORE INTO devices_v2(
      device_id,key_hash,last_seen,created_at,device_name,device_model,os_version,app_version
    )
    SELECT
      device_id,
      key_hash,
      last_seen,
      created_at,
      COALESCE(device_name,''),
      COALESCE(device_model,''),
      COALESCE(os_version,''),
      COALESCE(app_version,'')
    FROM devices`),
    db.prepare("DROP TABLE devices"),
    db.prepare("ALTER TABLE devices_v2 RENAME TO devices"),
    db.prepare("CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen)"),
    db.prepare("CREATE INDEX IF NOT EXISTS idx_devices_key ON devices(key_hash)"),
    db.prepare("CREATE INDEX IF NOT EXISTS idx_devices_device ON devices(device_id)")
  ]);
}

async function init(db) {
  await db.prepare(`CREATE TABLE IF NOT EXISTS activation_keys (
    key_hash TEXT PRIMARY KEY,
    key_hint TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    revoked INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
  )`).run();

  await db.prepare(`CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    last_seen TEXT NOT NULL,
    created_at TEXT NOT NULL,
    device_name TEXT NOT NULL DEFAULT '',
    device_model TEXT NOT NULL DEFAULT '',
    os_version TEXT NOT NULL DEFAULT '',
    app_version TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (device_id, key_hash)
  )`).run();

  await ensureColumn(db, "devices", "device_name", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "devices", "device_model", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "devices", "os_version", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "devices", "app_version", "TEXT NOT NULL DEFAULT ''");
  await migrateDevicesMultiKey(db);

  await db.prepare(`CREATE TABLE IF NOT EXISTS app_updates (
    id INTEGER PRIMARY KEY CHECK(id=1),
    version_code INTEGER NOT NULL DEFAULT 1,
    version_name TEXT NOT NULL DEFAULT '1.0',
    download_url TEXT NOT NULL DEFAULT '',
    message TEXT NOT NULL DEFAULT '',
    force_update INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
  )`).run();

  await db.prepare(`CREATE TABLE IF NOT EXISTS flash_messages (
    id INTEGER PRIMARY KEY CHECK(id=1),
    title TEXT NOT NULL DEFAULT '',
    message TEXT NOT NULL DEFAULT '',
    active INTEGER NOT NULL DEFAULT 0,
    file_url TEXT NOT NULL DEFAULT '',
    file_name TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`).run();

  await ensureColumn(db, "flash_messages", "title", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "flash_messages", "message", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "flash_messages", "active", "INTEGER NOT NULL DEFAULT 0");
  await ensureColumn(db, "flash_messages", "file_url", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "flash_messages", "file_name", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "flash_messages", "updated_at", "TEXT NOT NULL DEFAULT ''");

  await db.prepare(`
    INSERT OR IGNORE INTO flash_messages(id,title,message,active,file_url,file_name,updated_at)
    VALUES(1,'','',0,'','',?)
  `).bind(nowISO()).run();

  await ensureColumn(db, "activation_keys", "max_devices", "INTEGER NOT NULL DEFAULT 1");
  await ensureColumn(db, "activation_keys", "owner_id", "TEXT NOT NULL DEFAULT 'MAIN'");
  await ensureColumn(db, "activation_keys", "purchase_id", "TEXT");
  await ensureColumn(db, "activation_keys", "paid_amount", "INTEGER NOT NULL DEFAULT 0");
  await ensureColumn(db, "activation_keys", "duration_minutes", "INTEGER NOT NULL DEFAULT 0");
  await ensureColumn(db, "activation_keys", "duration_days", "INTEGER NOT NULL DEFAULT 0");
  await ensureColumn(db, "activation_keys", "plan_id", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "activation_keys", "status", "TEXT NOT NULL DEFAULT 'ACTIVE'");
  await ensureColumn(db, "activation_keys", "activated_at", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "activation_keys", "last_reset_at", "TEXT NOT NULL DEFAULT ''");

  await db.prepare(`CREATE TABLE IF NOT EXISTS pricing_plans (
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
  )`).run();

  await db.prepare(`CREATE TABLE IF NOT EXISTS subadmin_pricing_overrides (
    subadmin_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    price INTEGER NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (subadmin_id, plan_id)
  )`).run();

  await db.prepare(`CREATE TABLE IF NOT EXISTS subadmins (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    salt TEXT NOT NULL,
    balance INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  )`).run();

  await db.prepare(`CREATE TABLE IF NOT EXISTS wallet_transactions (
    id TEXT PRIMARY KEY,
    subadmin_id TEXT NOT NULL,
    amount INTEGER NOT NULL,
    type TEXT NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    actor_id TEXT NOT NULL,
    request_id TEXT UNIQUE,
    created_at TEXT NOT NULL
  )`).run();

  await ensureColumn(db, "wallet_transactions", "username_snapshot", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "wallet_transactions", "key_hash", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "wallet_transactions", "key_hint", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "wallet_transactions", "balance_before", "INTEGER NOT NULL DEFAULT 0");
  await ensureColumn(db, "wallet_transactions", "balance_after", "INTEGER NOT NULL DEFAULT 0");

  await db.prepare(`CREATE TABLE IF NOT EXISTS key_transactions (
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
  )`).run();

  await db.prepare(`CREATE TABLE IF NOT EXISTS billing_requests (
    request_id TEXT PRIMARY KEY,
    actor_type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    key_hash TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'COMPLETED',
    response_json TEXT NOT NULL,
    created_at TEXT NOT NULL
  )`).run();

  await db.prepare(`CREATE TABLE IF NOT EXISTS ul_settings (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL
  )`).run();

  await db.prepare(`
    INSERT OR IGNORE INTO ul_settings(setting_key,setting_value)
    VALUES('key_price','200')
  `).run();

  await db.prepare(`CREATE TABLE IF NOT EXISTS key_requests (
    request_id TEXT PRIMARY KEY,
    subadmin_id TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
  )`).run();

  await db.prepare("CREATE INDEX IF NOT EXISTS idx_keys_owner ON activation_keys(owner_id)").run();
  await db.prepare("CREATE INDEX IF NOT EXISTS idx_devices_key ON devices(key_hash)").run();
  await db.prepare("CREATE INDEX IF NOT EXISTS idx_devices_device ON devices(device_id)").run();
  await db.prepare("CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen)").run();
  await db.prepare("CREATE INDEX IF NOT EXISTS idx_wallet_sub ON wallet_transactions(subadmin_id,created_at)").run();
  await db.prepare("CREATE INDEX IF NOT EXISTS idx_key_tx_key ON key_transactions(key_hash,created_at)").run();
  await db.prepare("CREATE INDEX IF NOT EXISTS idx_key_tx_owner ON key_transactions(owner_id,created_at)").run();

  const seededFlag = await db.prepare(
    "SELECT setting_value FROM ul_settings WHERE setting_key='pricing_seeded_v1'"
  ).first();

  if (!seededFlag) {
    const t = nowISO();
    const stmts = DEFAULT_PRICING_PLANS.map(p =>
      db.prepare(`
        INSERT OR IGNORE INTO pricing_plans(
          id,name,duration_days,duration_minutes,price,is_default,active,sort_order,created_at,updated_at
        ) VALUES(?,?,?,?,?,1,1,?,?,?)
      `).bind(p.id, p.name, p.durationDays, p.durationMinutes, p.price, p.sortOrder, t, t)
    );
    stmts.push(
      db.prepare("INSERT OR IGNORE INTO ul_settings(setting_key,setting_value) VALUES('pricing_seeded_v1','1')")
    );
    await db.batch(stmts);
  }

  const backfillFlag = await db.prepare(
    "SELECT setting_value FROM ul_settings WHERE setting_key='keys_pricing_backfill_v1'"
  ).first();

  if (!backfillFlag) {
    const now = nowISO();
    await db.batch([
      db.prepare(`
        UPDATE activation_keys
        SET status = CASE
          WHEN revoked = 1 THEN 'REVOKED'
          WHEN expires_at <= ? THEN 'EXPIRED'
          ELSE 'ACTIVE'
        END
      `).bind(now),
      db.prepare(`
        UPDATE activation_keys
        SET paid_amount = COALESCE((
          SELECT ABS(w.amount)
          FROM wallet_transactions w
          WHERE w.request_id = activation_keys.purchase_id AND w.amount < 0
          LIMIT 1
        ), paid_amount)
        WHERE paid_amount = 0 AND purchase_id IS NOT NULL AND purchase_id <> ''
      `),
      db.prepare(`
        UPDATE activation_keys
        SET duration_minutes = MAX(1, CAST(ROUND((julianday(expires_at) - julianday(created_at)) * 1440.0) AS INTEGER)),
            duration_days = MAX(1, CAST((MAX(1, ROUND((julianday(expires_at) - julianday(created_at)) * 1440.0)) + 1439) / 1440 AS INTEGER))
        WHERE duration_minutes = 0
      `),
      db.prepare(`
        UPDATE activation_keys
        SET activated_at = COALESCE((
          SELECT MIN(d.created_at) FROM devices d WHERE d.key_hash = activation_keys.key_hash
        ), '')
        WHERE activated_at = ''
      `),
      db.prepare("INSERT OR IGNORE INTO ul_settings(setting_key,setting_value) VALUES('keys_pricing_backfill_v1','1')")
    ]);
  }
}

async function auth(req, env) {
  const h = req.headers.get("Authorization") || "";
  const t = h.replace(/^Bearer\s+/i, "");
  return t ? verifyToken(t, env.SESSION_SECRET) : null;
}

async function getPrice(db) {
  const r = await db.prepare(
    "SELECT setting_value FROM ul_settings WHERE setting_key='key_price'"
  ).first();

  return Math.max(0, Number(r?.setting_value ?? 200));
}

async function listEffectivePlans(db, subadminId = null, includeInactive = false) {
  const globalRows = await db.prepare(
    includeInactive
      ? "SELECT * FROM pricing_plans ORDER BY sort_order ASC, duration_minutes ASC"
      : "SELECT * FROM pricing_plans WHERE active=1 ORDER BY sort_order ASC, duration_minutes ASC"
  ).all();

  const plans = globalRows.results || [];
  let overrideMap = new Map();

  if (subadminId) {
    const ov = await db.prepare(
      "SELECT plan_id,price,active FROM subadmin_pricing_overrides WHERE subadmin_id=?"
    ).bind(subadminId).all();
    for (const row of (ov.results || [])) {
      overrideMap.set(row.plan_id, row);
    }
  }

  const out = [];
  for (const p of plans) {
    const o = overrideMap.get(p.id);
    const effectiveActive = o ? (Number(p.active) === 1 && Number(o.active) === 1) : Number(p.active) === 1;
    if (!includeInactive && !effectiveActive) continue;
    const effectivePrice = o ? Math.max(0, Number(o.price)) : Math.max(0, Number(p.price));
    out.push({
      id: p.id,
      name: p.name,
      durationDays: Number(p.duration_days),
      durationMinutes: Number(p.duration_minutes),
      basePrice: Number(p.price),
      price: effectivePrice,
      hasCustomRate: !!o,
      isDefault: Number(p.is_default) === 1,
      active: effectiveActive,
      globalActive: Number(p.active) === 1,
      overrideActive: o ? Number(o.active) === 1 : null,
      sortOrder: Number(p.sort_order),
      updatedAt: p.updated_at
    });
  }
  return out;
}

async function resolveServerPlan(db, body, subadminId = null) {
  const effectivePlans = await listEffectivePlans(db, subadminId, false);
  const requestedPlanId = str(body.planId, 80);

  if (requestedPlanId) {
    const matched = effectivePlans.find(p => p.id === requestedPlanId);
    if (!matched) {
      throw new Error("Selected pricing plan is unavailable or disabled");
    }
    return {
      planId: matched.id,
      planName: matched.name,
      durationDays: matched.durationDays,
      durationMinutes: matched.durationMinutes,
      price: matched.price,
      customExpiresAt: null
    };
  }

  const minutes = durationMinutesFromBody(body);
  const customExpiresAt = (body.expiresAt !== undefined && body.expiresAt !== null && body.expiresAt !== "")
    ? validDuration({ expiresAt: body.expiresAt })
    : null;

  if (effectivePlans.length === 0) {
    throw new Error("No active pricing plans configured");
  }

  const exact = effectivePlans.find(p => p.durationMinutes === minutes);
  if (exact) {
    return {
      planId: exact.id,
      planName: exact.name,
      durationDays: exact.durationDays,
      durationMinutes: exact.durationMinutes,
      price: exact.price,
      customExpiresAt
    };
  }

  const days = Math.max(1, Math.ceil(minutes / 1440));
  const sorted = [...effectivePlans].sort((a, b) => a.durationMinutes - b.durationMinutes);
  const covering = sorted.find(p => p.durationMinutes >= minutes);

  if (covering) {
    return {
      planId: covering.id,
      planName: covering.name,
      durationDays: days,
      durationMinutes: minutes,
      price: covering.price,
      customExpiresAt
    };
  }

  const maxPlan = sorted[sorted.length - 1];
  const blocks = Math.ceil(minutes / maxPlan.durationMinutes);
  return {
    planId: maxPlan.id,
    planName: `${days} Days (Custom)`,
    durationDays: days,
    durationMinutes: minutes,
    price: blocks * maxPlan.price,
    customExpiresAt
  };
}

async function acquireIdempotency(db, requestId, actorType, actorId, operation) {
  const cleanId = str(requestId, 100);
  if (!cleanId) {
    throw new Error("requestId is required for safe billing operation");
  }

  const insertRes = await db.prepare(`
    INSERT OR IGNORE INTO billing_requests(
      request_id,actor_type,actor_id,operation,key_hash,status,response_json,created_at
    ) VALUES(?,?,?,?,'','PENDING','{}',?)
  `).bind(cleanId, actorType, actorId, operation, nowISO()).run();

  if (Number(insertRes.meta?.changes || 0) > 0) {
    return { acquired: true, requestId: cleanId };
  }

  const existing = await db.prepare(
    "SELECT actor_type,actor_id,operation,status,response_json FROM billing_requests WHERE request_id=?"
  ).bind(cleanId).first();

  if (!existing || existing.actor_type !== actorType || existing.actor_id !== actorId || existing.operation !== operation) {
    return {
      acquired: false,
      statusCode: 409,
      payload: { error: "This requestId was already used for another operation" }
    };
  }

  if (existing.status === "PENDING") {
    return {
      acquired: false,
      statusCode: 409,
      payload: { error: "Request is already being processed; retry shortly" }
    };
  }

  let saved = {};
  try {
    saved = JSON.parse(existing.response_json || "{}");
  } catch {
    saved = {};
  }

  return {
    acquired: false,
    statusCode: 200,
    payload: {
      ...saved,
      idempotentReplay: true
    }
  };
}

async function releasePendingIdempotency(db, requestId) {
  if (!requestId) return;
  await db.prepare(
    "DELETE FROM billing_requests WHERE request_id=? AND status='PENDING'"
  ).bind(requestId).run().catch(() => {});
}

async function adminOnly(req, env) {
  const p = await auth(req, env);
  return p?.role === "admin" ? p : null;
}

async function subOnly(req, env, db) {
  const p = await auth(req, env);

  if (p?.role !== "subadmin" || !p.subadminId) return null;

  const a = await db.prepare(
    "SELECT id,username,balance,active FROM subadmins WHERE id=?"
  ).bind(p.subadminId).first();

  return a?.active ? { ...p, account: a } : null;
}

async function adminLogin(req, env) {
  const b = await readBody(req);

  if (!env.ADMIN_PASSWORD || !env.SESSION_SECRET) {
    return json({ error: "Admin secrets are not configured" }, 503);
  }

  if (typeof b.password !== "string" || b.password !== env.ADMIN_PASSWORD) {
    return json({ error: "Incorrect password" }, 401);
  }

  return json({
    ok: true,
    token: await tokenFor({ role: "admin" }, env.SESSION_SECRET)
  });
}

async function listKeys(db, ownerId = null) {
  await cleanupExpiredDeviceRegistries(db, nowISO());

  const sql = `SELECT k.key_hint,k.expires_at,k.revoked,k.created_at,k.max_devices,k.owner_id,
    k.paid_amount,k.duration_minutes,k.duration_days,k.plan_id,k.status,k.activated_at,k.last_reset_at,
    s.username AS creator_username,
    (SELECT COUNT(*) FROM devices d WHERE d.key_hash=k.key_hash) AS device_count,
    COALESCE(NULLIF(k.activated_at,''), (SELECT MIN(d.created_at) FROM devices d WHERE d.key_hash=k.key_hash), '') AS first_activated_at
    FROM activation_keys k LEFT JOIN subadmins s ON s.id=k.owner_id
    ${ownerId ? "WHERE k.owner_id=?" : ""}
    ORDER BY k.created_at DESC LIMIT 1000`;

  const r = ownerId
    ? await db.prepare(sql).bind(ownerId).all()
    : await db.prepare(sql).all();

  return (r.results || []).map(k => ({
    key_hint: k.key_hint,
    expires_at: k.expires_at,
    revoked: k.revoked,
    created_at: k.created_at,
    activated_at: k.first_activated_at || "",
    last_reset_at: k.last_reset_at || "",
    max_devices: k.max_devices,
    device_count: k.device_count,
    owner_id: k.owner_id,
    paid_amount: Number(k.paid_amount || 0),
    duration_minutes: Number(k.duration_minutes || 0),
    duration_days: Number(k.duration_days || 0),
    plan_id: k.plan_id || "",
    creator_type: k.owner_id === "MAIN" ? "MAIN ADMIN" : "SUBADMIN",
    creator_username: k.creator_username || "",
    status: k.revoked
      ? "REVOKED"
      : Date.parse(k.expires_at) > Date.now()
        ? "ACTIVE"
        : "EXPIRED"
  }));
}

async function setRevoked(db, key, revoked, ownerId = null) {
  const hash = await sha256(key);
  const existing = ownerId
    ? await db.prepare("SELECT expires_at FROM activation_keys WHERE key_hash=? AND owner_id=?").bind(hash, ownerId).first()
    : await db.prepare("SELECT expires_at FROM activation_keys WHERE key_hash=?").bind(hash).first();

  if (!existing) return false;

  const nextStatus = revoked
    ? "REVOKED"
    : Date.parse(existing.expires_at) > Date.now()
      ? "ACTIVE"
      : "EXPIRED";

  const sql = ownerId
    ? "UPDATE activation_keys SET revoked=?,status=? WHERE key_hash=? AND owner_id=?"
    : "UPDATE activation_keys SET revoked=?,status=? WHERE key_hash=?";

  const update = ownerId
    ? db.prepare(sql).bind(revoked ? 1 : 0, nextStatus, hash, ownerId)
    : db.prepare(sql).bind(revoked ? 1 : 0, nextStatus, hash);

  if (revoked) {
    const deleteDevices = ownerId
      ? db.prepare(
          "DELETE FROM devices WHERE key_hash IN (SELECT key_hash FROM activation_keys WHERE key_hash=? AND owner_id=?)"
        ).bind(hash, ownerId)
      : db.prepare(
          "DELETE FROM devices WHERE key_hash=?"
        ).bind(hash);

    const results = await db.batch([
      update,
      deleteDevices
    ]);

    return Number(results[0]?.meta?.changes || 0) > 0;
  }

  const result = await update.run();
  return Number(result.meta?.changes || 0) > 0;
}

async function processKeyUpgradeOrReset(db, {
  action,
  key,
  body,
  ownerFilterId,
  actorType,
  actorId,
  requestId,
  chargeWallet
}) {
  const cleanKey = str(key, 200);
  if (!cleanKey) {
    return { statusCode: 400, payload: { error: "Activation key is required" } };
  }

  const hash = await sha256(cleanKey);
  const existing = ownerFilterId
    ? await db.prepare("SELECT * FROM activation_keys WHERE key_hash=? AND owner_id=?").bind(hash, ownerFilterId).first()
    : await db.prepare("SELECT * FROM activation_keys WHERE key_hash=?").bind(hash).first();

  if (!existing) {
    return { statusCode: 404, payload: { error: ownerFilterId ? "Your key was not found" : "Activation key not found" } };
  }

  if (action === "UPGRADE" && Number(existing.revoked) === 1) {
    return { statusCode: 400, payload: { error: "Revoked keys cannot be upgraded; reset or enable first" } };
  }

  const pricingOwner = chargeWallet
    ? ownerFilterId
    : (existing.owner_id && existing.owner_id !== "MAIN" ? existing.owner_id : null);

  let target;
  try {
    target = await resolveServerPlan(db, body, pricingOwner);
  } catch (e) {
    return { statusCode: 400, payload: { error: e.message || "Invalid duration or pricing plan" } };
  }

  const prevDurationMin = Math.max(0, Number(existing.duration_minutes || 0));
  const prevPaid = Math.max(0, Number(existing.paid_amount || 0));
  const oldExpiryMs = Date.parse(existing.expires_at);
  const nowMs = Date.now();
  const now = new Date(nowMs).toISOString();
  const isStillActive = Number.isFinite(oldExpiryMs) && oldExpiryMs > nowMs && Number(existing.revoked) === 0;

  if (action === "UPGRADE" && isStillActive && prevDurationMin > 0 && target.durationMinutes <= prevDurationMin) {
    return {
      statusCode: 400,
      payload: { error: "Select a longer duration plan to upgrade an active key" }
    };
  }

  const additionalCharge = chargeWallet ? Math.max(0, target.price - prevPaid) : 0;
  const newTotalPaid = Math.max(prevPaid, target.price);

  let newExpiryMs;
  if (target.customExpiresAt) {
    newExpiryMs = Date.parse(target.customExpiresAt);
  } else if (isStillActive) {
    const baselineMin = prevDurationMin > 0
      ? prevDurationMin
      : Math.max(0, Math.ceil((oldExpiryMs - nowMs) / 60000));
    const extraMinutes = Math.max(0, target.durationMinutes - baselineMin);
    newExpiryMs = oldExpiryMs + extraMinutes * 60000;
  } else {
    newExpiryMs = nowMs + target.durationMinutes * 60000;
  }

  const newExpiresAt = new Date(newExpiryMs).toISOString();
  const newDurationMinutes = Math.max(prevDurationMin, target.durationMinutes);
  const newDurationDays = Math.max(1, Math.ceil(newDurationMinutes / 1440));

  let balanceBefore = 0;
  let balanceAfter = 0;
  let subUsername = "";

  if (chargeWallet) {
    const sub = await db.prepare(
      "SELECT id,username,balance,active FROM subadmins WHERE id=?"
    ).bind(actorId).first();
    if (!sub || !sub.active) {
      return { statusCode: 403, payload: { error: "Subadmin account is inactive" } };
    }
    balanceBefore = Number(sub.balance);
    subUsername = sub.username || "";
    if (balanceBefore < additionalCharge) {
      return {
        statusCode: 402,
        payload: {
          error: `Insufficient balance. Required: $${additionalCharge}, Available: $${balanceBefore}`,
          requiredAmount: additionalCharge,
          availableBalance: balanceBefore,
          planPrice: target.price,
          previouslyPaid: prevPaid
        }
      };
    }
    balanceAfter = balanceBefore - additionalCharge;
  }

  const responsePayload = {
    ok: true,
    action,
    key: existing.key_hint,
    planId: target.planId,
    planName: target.planName,
    planPrice: target.price,
    previouslyPaid: prevPaid,
    additionalCharge,
    charged: additionalCharge,
    totalPaid: newTotalPaid,
    previousExpiresAt: existing.expires_at,
    expiresAt: newExpiresAt,
    durationMinutes: newDurationMinutes,
    durationDays: newDurationDays,
    devicesReset: action === "RESET",
    balanceAfter: chargeWallet ? balanceAfter : null
  };

  const txNote = action === "UPGRADE"
    ? `Key upgraded (${target.planName}) — paid $${additionalCharge} diff (new plan $${target.price} - prev paid $${prevPaid})`
    : `Key paid reset (${target.planName}) — paid $${additionalCharge} diff (plan $${target.price} - prev paid $${prevPaid})`;

  const stmts = [];
  if (chargeWallet && additionalCharge > 0) {
    stmts.push(
      db.prepare(`
        UPDATE subadmins
        SET balance = balance - ?, updated_at = ?
        WHERE id = ? AND active = 1 AND balance >= ?
      `).bind(additionalCharge, now, actorId, additionalCharge)
    );
    stmts.push(
      db.prepare(`
        UPDATE activation_keys
        SET expires_at=?, revoked=0, status='ACTIVE', duration_minutes=?, duration_days=?, plan_id=?, paid_amount=?,
            last_reset_at=CASE WHEN ?='RESET' THEN ? ELSE last_reset_at END
        WHERE key_hash=? AND changes()>0
      `).bind(newExpiresAt, newDurationMinutes, newDurationDays, target.planId, newTotalPaid, action, now, hash)
    );
  } else {
    stmts.push(
      db.prepare(`
        UPDATE activation_keys
        SET expires_at=?, revoked=0, status='ACTIVE', duration_minutes=?, duration_days=?, plan_id=?, paid_amount=?,
            last_reset_at=CASE WHEN ?='RESET' THEN ? ELSE last_reset_at END
        WHERE key_hash=?
      `).bind(newExpiresAt, newDurationMinutes, newDurationDays, target.planId, newTotalPaid, action, now, hash)
    );
  }

  stmts.push(
    db.prepare(`
      INSERT INTO key_transactions(
        id,request_id,key_hash,key_hint,owner_id,actor_type,actor_id,action_type,
        plan_id,plan_name,previous_duration_minutes,new_duration_minutes,
        previous_paid_amount,plan_price,charged_amount,new_total_paid,
        previous_expires_at,new_expires_at,balance_before,balance_after,note,created_at
      )
      SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
      WHERE changes()>0
    `).bind(
      crypto.randomUUID(),
      requestId,
      hash,
      existing.key_hint,
      existing.owner_id,
      actorType,
      actorId,
      action,
      target.planId,
      target.planName,
      prevDurationMin,
      newDurationMinutes,
      prevPaid,
      target.price,
      additionalCharge,
      newTotalPaid,
      existing.expires_at,
      newExpiresAt,
      balanceBefore,
      balanceAfter,
      txNote,
      now
    )
  );

  if (chargeWallet && additionalCharge > 0) {
    stmts.push(
      db.prepare(`
        INSERT INTO wallet_transactions(
          id,subadmin_id,amount,type,note,actor_id,request_id,username_snapshot,
          key_hash,key_hint,balance_before,balance_after,created_at
        )
        SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?
        WHERE changes()>0
      `).bind(
        crypto.randomUUID(),
        actorId,
        -additionalCharge,
        action === "UPGRADE" ? "key_upgrade" : "key_reset",
        txNote,
        "SUBADMIN",
        requestId,
        subUsername,
        hash,
        existing.key_hint,
        balanceBefore,
        balanceAfter,
        now
      )
    );
  }

  stmts.push(
    db.prepare(`
      UPDATE billing_requests
      SET status='COMPLETED', key_hash=?, response_json=?
      WHERE request_id=? AND changes()>0
    `).bind(hash, JSON.stringify(responsePayload), requestId)
  );

  if (action === "RESET") {
    stmts.push(
      db.prepare(`
        DELETE FROM devices
        WHERE key_hash=?
          AND EXISTS (SELECT 1 FROM billing_requests WHERE request_id=? AND status='COMPLETED')
      `).bind(hash, requestId)
    );
  }

  const batchResults = await db.batch(stmts);
  if (!(batchResults[0]?.meta?.changes > 0)) {
    return {
      statusCode: chargeWallet && additionalCharge > 0 ? 402 : 409,
      payload: {
        error: chargeWallet && additionalCharge > 0
          ? "Insufficient balance or concurrent update rejected"
          : "Operation failed; retry"
      }
    };
  }

  return { statusCode: 200, payload: responsePayload };
}

async function adminRoutes(req, env, db, path) {
  const admin = await adminOnly(req, env);

  if (!admin) return json({ error: "Unauthorized" }, 401);

  const method = req.method;

  if (path === "/api/admin/flash/upload" && method === "POST") {
    if (!env.GITHUB_TOKEN || !env.GITHUB_REPO) {
      return json({
        error: "GITHUB_TOKEN and GITHUB_REPO secrets are not configured on the Worker; paste a GitHub Releases APK/ZIP URL directly instead."
      }, 503);
    }

    let form;
    try {
      form = await req.formData();
    } catch {
      return json({ error: "Invalid multipart form data" }, 400);
    }

    const file = form.get("file");
    if (!file || typeof file.arrayBuffer !== "function" || !file.name) {
      return json({ error: "Please select an APK or ZIP file" }, 400);
    }

    if (!/\.(apk|zip)$/i.test(file.name)) {
      return json({ error: "Only .apk or .zip files are allowed" }, 400);
    }

    if (file.size <= 0 || file.size > 100 * 1024 * 1024) {
      return json({ error: "File size must be between 1 byte and 100 MB" }, 400);
    }

    const safeName = str(file.name.replace(/[^A-Za-z0-9._-]/g, "_"), 120);
    const repo = str(env.GITHUB_REPO, 120);
    const tag = str(env.GITHUB_RELEASE_TAG || "flash-assets", 80);
    const ghHeaders = {
      "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
      "Accept": "application/vnd.github+json",
      "User-Agent": "UltraLoader-Worker/1.0"
    };

    let releaseRes = await fetch(
      `https://api.github.com/repos/${repo}/releases/tags/${encodeURIComponent(tag)}`,
      { headers: ghHeaders }
    );

    if (releaseRes.status === 404) {
      releaseRes = await fetch(
        `https://api.github.com/repos/${repo}/releases`,
        {
          method: "POST",
          headers: { ...ghHeaders, "Content-Type": "application/json" },
          body: JSON.stringify({
            tag_name: tag,
            name: "Flash Pop-up Assets",
            draft: false,
            prerelease: false
          })
        }
      );
    }

    if (!releaseRes.ok) {
      const errText = await releaseRes.text().catch(() => "");
      return json({
        error: `GitHub release error (HTTP ${releaseRes.status})`,
        detail: errText.slice(0, 300)
      }, 502);
    }

    const release = await releaseRes.json();
    const existingAsset = (release.assets || []).find(a => a.name === safeName);
    if (existingAsset?.id) {
      await fetch(
        `https://api.github.com/repos/${repo}/releases/assets/${existingAsset.id}`,
        { method: "DELETE", headers: ghHeaders }
      );
    }

    const uploadBase = String(release.upload_url || "").replace(/\{.*$/, "");
    if (!uploadBase) {
      return json({ error: "GitHub release upload URL missing" }, 502);
    }

    const contentType = safeName.toLowerCase().endsWith(".apk")
      ? "application/vnd.android.package-archive"
      : "application/zip";

    const uploadRes = await fetch(
      `${uploadBase}?name=${encodeURIComponent(safeName)}`,
      {
        method: "POST",
        headers: {
          ...ghHeaders,
          "Content-Type": contentType
        },
        body: await file.arrayBuffer()
      }
    );

    if (!uploadRes.ok) {
      const errText = await uploadRes.text().catch(() => "");
      return json({
        error: `GitHub asset upload failed (HTTP ${uploadRes.status})`,
        detail: errText.slice(0, 300)
      }, 502);
    }

    const asset = await uploadRes.json();
    return json({
      ok: true,
      fileUrl: asset.browser_download_url,
      fileName: asset.name || safeName
    });
  }

  const b = method === "POST" ? await readBody(req) : {};

  if (path === "/api/admin/keys" && method === "GET") {
    return json({ keys: await listKeys(db) });
  }

  if (path === "/api/admin/devices" && method === "GET") {
    await cleanupExpiredDeviceRegistries(db, nowISO());
    const r = await db.prepare(`SELECT d.device_id,d.device_name,d.device_model,d.os_version,d.app_version,
      d.created_at,d.last_seen,k.key_hint,k.owner_id,k.expires_at,s.username AS creator_username
      FROM devices d LEFT JOIN activation_keys k ON k.key_hash=d.key_hash
      LEFT JOIN subadmins s ON s.id=k.owner_id
      ORDER BY d.last_seen DESC LIMIT 1000`).all();
    return json({ devices: (r.results || []).map(d => ({
      ...d,
      creator_type: d.owner_id === "MAIN" ? "MAIN ADMIN" : d.owner_id ? "SUBADMIN" : "MASTER KEY"
    })) });
  }

  if (path === "/api/admin/keys/generate" && method === "POST") {
    const max = Number(b.maxDevices ?? 1);

    if (!Number.isInteger(max) || max < 1 || max > 99999) {
      return json({ error: "Device limit must be 1–99999" }, 400);
    }

    const requestId = str(b.requestId, 100) || crypto.randomUUID();
    const lock = await acquireIdempotency(db, requestId, "MAIN", "MAIN", "KEY_GENERATE");
    if (!lock.acquired) {
      return json(lock.payload, lock.statusCode);
    }

    try {
      const plan = await resolveServerPlan(db, b, null);
      const key = makeKey(b.customSuffix);
      const hash = await sha256(key);
      const duplicate = await db.prepare(
        "SELECT key_hash FROM activation_keys WHERE key_hash=?"
      ).bind(hash).first();
      if (duplicate) {
        await releasePendingIdempotency(db, requestId);
        return json({ error: "This custom activation key already exists" }, 409);
      }

      const now = nowISO();
      const expiresAt = plan.customExpiresAt || new Date(Date.now() + plan.durationMinutes * 60000).toISOString();
      const payload = {
        ok: true,
        created: 1,
        key,
        expiresAt,
        maxDevices: max,
        planId: plan.planId,
        planName: plan.planName,
        planPrice: plan.price,
        charged: 0,
        paidAmount: plan.price
      };

      await db.batch([
        db.prepare(`INSERT INTO activation_keys(
          key_hash,key_hint,expires_at,revoked,created_at,max_devices,owner_id,purchase_id,
          paid_amount,duration_minutes,duration_days,plan_id,status,activated_at,last_reset_at
        ) VALUES(?,?,?,0,?,?,?,?,?,?,?,?, 'ACTIVE', '', '')`)
          .bind(hash, key, expiresAt, now, max, "MAIN", requestId, plan.price, plan.durationMinutes, plan.durationDays, plan.planId),

        db.prepare(`INSERT INTO key_transactions(
          id,request_id,key_hash,key_hint,owner_id,actor_type,actor_id,action_type,
          plan_id,plan_name,previous_duration_minutes,new_duration_minutes,
          previous_paid_amount,plan_price,charged_amount,new_total_paid,
          previous_expires_at,new_expires_at,balance_before,balance_after,note,created_at
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
          .bind(
            crypto.randomUUID(),
            requestId,
            hash,
            key,
            "MAIN",
            "MAIN",
            "MAIN",
            "PURCHASE",
            plan.planId,
            plan.planName,
            0,
            plan.durationMinutes,
            0,
            plan.price,
            0,
            plan.price,
            "",
            expiresAt,
            0,
            0,
            `Main Admin key generated (${plan.planName}, plan price $${plan.price})`,
            now
          ),

        db.prepare(`
          UPDATE billing_requests
          SET status='COMPLETED', key_hash=?, response_json=?
          WHERE request_id=?
        `).bind(hash, JSON.stringify(payload), requestId)
      ]);

      return json(payload);
    } catch (e) {
      await releasePendingIdempotency(db, requestId);
      const message = e?.message || "Key generation failed";
      return json({ error: message }, /already exists/i.test(message) ? 409 : 400);
    }
  }

  if (path === "/api/admin/keys/upgrade" && method === "POST") {
    const requestId = str(b.requestId, 100) || crypto.randomUUID();
    const lock = await acquireIdempotency(db, requestId, "MAIN", "MAIN", "KEY_UPGRADE");
    if (!lock.acquired) return json(lock.payload, lock.statusCode);

    try {
      const outcome = await processKeyUpgradeOrReset(db, {
        action: "UPGRADE",
        key: b.key,
        body: b,
        ownerFilterId: null,
        actorType: "MAIN",
        actorId: "MAIN",
        requestId,
        chargeWallet: false
      });
      if (outcome.statusCode !== 200) {
        await releasePendingIdempotency(db, requestId);
      }
      return json(outcome.payload, outcome.statusCode);
    } catch (e) {
      await releasePendingIdempotency(db, requestId);
      return json({ error: e.message || "Invalid upgrade request" }, 400);
    }
  }

  if (path === "/api/admin/keys/reset" && method === "POST") {
    const requestId = str(b.requestId, 100) || crypto.randomUUID();
    const lock = await acquireIdempotency(db, requestId, "MAIN", "MAIN", "KEY_RESET");
    if (!lock.acquired) return json(lock.payload, lock.statusCode);

    try {
      const outcome = await processKeyUpgradeOrReset(db, {
        action: "RESET",
        key: b.key,
        body: b,
        ownerFilterId: null,
        actorType: "MAIN",
        actorId: "MAIN",
        requestId,
        chargeWallet: false
      });
      if (outcome.statusCode !== 200) {
        await releasePendingIdempotency(db, requestId);
      }
      return json(outcome.payload, outcome.statusCode);
    } catch (e) {
      await releasePendingIdempotency(db, requestId);
      return json({ error: e.message || "Invalid reset request" }, 400);
    }
  }

  if (path === "/api/admin/keys/status" && method === "POST") {
    if (!b.key || typeof b.revoked !== "boolean") {
      return json({ error: "Key and revoked boolean are required" }, 400);
    }

    if (!await setRevoked(db, str(b.key, 200), b.revoked)) {
      return json({ error: "Activation key not found" }, 404);
    }

    return json({ ok: true, revoked: b.revoked });
  }

  if (path === "/api/admin/keys/delete" && method === "POST") {
    const key = str(b.key, 200);

    if (!key) return json({ error: "Key is required" }, 400);

    const hash = await sha256(key);

    const found = await db.prepare(
      "SELECT key_hash FROM activation_keys WHERE key_hash=?"
    ).bind(hash).first();

    if (!found) return json({ error: "Activation key not found" }, 404);

    // Preserve key_transactions & wallet_transactions history; remove only active key and device bindings.
    await db.batch([
      db.prepare("DELETE FROM devices WHERE key_hash=?").bind(hash),
      db.prepare("DELETE FROM activation_keys WHERE key_hash=?").bind(hash)
    ]);

    return json({ ok: true, deleted: true, historyPreserved: true });
  }

  if (path === "/api/admin/pricing" && method === "GET") {
    const plans = await listEffectivePlans(db, null, true);
    const overrides = await db.prepare(`
      SELECT o.subadmin_id, o.plan_id, o.price, o.active, o.updated_at,
             s.username AS subadmin_username, p.name AS plan_name
      FROM subadmin_pricing_overrides o
      LEFT JOIN subadmins s ON s.id = o.subadmin_id
      LEFT JOIN pricing_plans p ON p.id = o.plan_id
      ORDER BY s.username ASC, p.sort_order ASC
    `).all();
    return json({
      plans,
      subadminRates: overrides.results || []
    });
  }

  if (path === "/api/admin/pricing/plan" && method === "POST") {
    const rawId = str(b.id, 60);
    const name = str(b.name, 80);
    const durationDays = Number(b.durationDays);
    const durationMinutes = b.durationMinutes !== undefined && b.durationMinutes !== null && b.durationMinutes !== ""
      ? Number(b.durationMinutes)
      : durationDays * 1440;
    const price = Number(b.price);
    const active = b.active !== false;
    const sortOrder = Number.isInteger(Number(b.sortOrder)) ? Number(b.sortOrder) : durationDays;

    if (!name) return json({ error: "Plan name is required" }, 400);
    if (!Number.isSafeInteger(durationMinutes) || durationMinutes < 1 || durationMinutes > 3650 * 1440) {
      return json({ error: "Duration must be 1 minute to 3650 days" }, 400);
    }
    const resolvedDays = Number.isSafeInteger(durationDays) && durationDays >= 1
      ? durationDays
      : Math.max(1, Math.ceil(durationMinutes / 1440));
    if (!Number.isSafeInteger(price) || price < 0 || price > 10000000) {
      return json({ error: "Plan price must be 0–10000000" }, 400);
    }

    const now = nowISO();
    const id = rawId || `plan_custom_${resolvedDays}d_${crypto.randomUUID().slice(0, 8)}`;

    await db.prepare(`
      INSERT INTO pricing_plans(
        id,name,duration_days,duration_minutes,price,is_default,active,sort_order,created_at,updated_at
      ) VALUES(?,?,?,?,?,0,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET
        name=excluded.name,
        duration_days=excluded.duration_days,
        duration_minutes=excluded.duration_minutes,
        price=excluded.price,
        active=excluded.active,
        sort_order=excluded.sort_order,
        updated_at=excluded.updated_at
    `).bind(id, name, resolvedDays, durationMinutes, price, active ? 1 : 0, sortOrder, now, now).run();

    return json({
      ok: true,
      plan: { id, name, durationDays: resolvedDays, durationMinutes, price, active, sortOrder, updatedAt: now }
    });
  }

  if (path === "/api/admin/pricing/plan/status" && method === "POST") {
    const id = str(b.id, 60);
    if (!id || typeof b.active !== "boolean") {
      return json({ error: "Plan id and active boolean are required" }, 400);
    }
    const r = await db.prepare(
      "UPDATE pricing_plans SET active=?, updated_at=? WHERE id=?"
    ).bind(b.active ? 1 : 0, nowISO(), id).run();
    if (!(r.meta?.changes > 0)) return json({ error: "Plan not found" }, 404);
    return json({ ok: true, id, active: b.active });
  }

  if (path === "/api/admin/pricing/subadmin" && method === "POST") {
    const subadminId = str(b.subadminId, 80);
    const planId = str(b.planId, 60);
    const remove = b.remove === true;

    if (!subadminId || !planId) {
      return json({ error: "subadminId and planId are required" }, 400);
    }

    const sub = await db.prepare("SELECT id,username FROM subadmins WHERE id=?").bind(subadminId).first();
    if (!sub) return json({ error: "Subadmin not found" }, 404);

    const plan = await db.prepare("SELECT id,name FROM pricing_plans WHERE id=?").bind(planId).first();
    if (!plan) return json({ error: "Pricing plan not found" }, 404);

    if (remove) {
      await db.prepare(
        "DELETE FROM subadmin_pricing_overrides WHERE subadmin_id=? AND plan_id=?"
      ).bind(subadminId, planId).run();
      return json({ ok: true, removed: true });
    }

    const price = Number(b.price);
    const active = b.active !== false;
    if (!Number.isSafeInteger(price) || price < 0 || price > 10000000) {
      return json({ error: "Custom price must be 0–10000000" }, 400);
    }

    const now = nowISO();
    await db.prepare(`
      INSERT INTO subadmin_pricing_overrides(subadmin_id,plan_id,price,active,updated_at)
      VALUES(?,?,?,?,?)
      ON CONFLICT(subadmin_id,plan_id) DO UPDATE SET
        price=excluded.price,
        active=excluded.active,
        updated_at=excluded.updated_at
    `).bind(subadminId, planId, price, active ? 1 : 0, now).run();

    return json({
      ok: true,
      override: {
        subadminId,
        subadminUsername: sub.username,
        planId,
        planName: plan.name,
        price,
        active,
        updatedAt: now
      }
    });
  }

  if (path === "/api/admin/subadmins" && method === "GET") {
    const r = await db.prepare(`SELECT s.id,s.username,s.balance,s.active,s.created_at,
      (SELECT COUNT(*) FROM activation_keys k WHERE k.owner_id=s.id) AS total_keys,
      (SELECT COUNT(*) FROM activation_keys k WHERE k.owner_id=s.id AND k.revoked=0 AND k.expires_at>?) AS active_keys
      FROM subadmins s ORDER BY s.created_at DESC`)
      .bind(nowISO()).all();

    return json({ subadmins: r.results || [] });
  }

  if (path === "/api/admin/subadmins" && method === "POST") {
    const username = str(b.username, 40);
    const password = String(b.password || "");

    if (!/^[A-Za-z0-9_.-]{3,40}$/.test(username) ||
        password.length < 8 || password.length > 200) {
      return json({
        error: "Username must be 3–40 characters; password at least 8 characters"
      }, 400);
    }

    const id = crypto.randomUUID();
    const salt = b64u(crypto.getRandomValues(new Uint8Array(16)));
    const hash = await passwordHash(password, salt);
    const t = nowISO();

    try {
      await db.prepare(
        "INSERT INTO subadmins(id,username,password_hash,salt,balance,active,created_at,updated_at) VALUES(?,?,?,?,?,1,?,?)"
      ).bind(id, username, hash, salt, 0, t, t).run();
    } catch (error) {
      const detail = String(error?.message || error);

      if (/unique|constraint/i.test(detail)) {
        return json({ error: "Username already exists" }, 409);
      }

      return json({
        error: "Subadmin creation failed",
        detail
      }, 500);
    }

    return json({ ok: true, id, username, balance: 0 });
  }

  if (path === "/api/admin/subadmins/balance" && method === "POST") {
    const id = str(b.subadminId, 80);
    const amount = Number(b.amount);
    const type = str(b.type, 10);
    const note = str(b.note, 300);
    const requestId = str(b.requestId, 100) || crypto.randomUUID();

    if (!id || !Number.isSafeInteger(amount) || amount < 1 ||
        amount > 100000000 || !["add", "deduct", "reset"].includes(type)) {
      return json({ error: "Invalid balance operation" }, 400);
    }

    const lock = await acquireIdempotency(db, requestId, "MAIN", "MAIN", `WALLET_${type.toUpperCase()}`);
    if (!lock.acquired) return json(lock.payload, lock.statusCode);

    const a = await db.prepare(
      "SELECT id,username,balance FROM subadmins WHERE id=?"
    ).bind(id).first();

    if (!a) {
      await releasePendingIdempotency(db, requestId);
      return json({ error: "Subadmin not found" }, 404);
    }

    const balanceBefore = Number(a.balance);
    const delta = type === "add"
      ? amount
      : type === "deduct"
        ? -amount
        : -balanceBefore;

    if (balanceBefore + delta < 0) {
      await releasePendingIdempotency(db, requestId);
      return json({ error: "Insufficient subadmin balance" }, 400);
    }

    const balanceAfter = balanceBefore + delta;
    const t = nowISO();
    const outPayload = { ok: true, balance: balanceAfter, delta };

    const results = await db.batch([
      db.prepare(`
        UPDATE subadmins
        SET balance=balance+?,updated_at=?
        WHERE id=? AND balance+?>=0
      `).bind(delta, t, id, delta),

      db.prepare(`
        INSERT INTO wallet_transactions(
          id,subadmin_id,amount,type,note,actor_id,request_id,username_snapshot,
          key_hash,key_hint,balance_before,balance_after,created_at
        )
        SELECT ?,?,?,?,?,?,?,?,'','',?,?,?
        WHERE changes()>0
      `).bind(
        crypto.randomUUID(),
        id,
        delta,
        type,
        note,
        "MAIN",
        requestId,
        a.username || "",
        balanceBefore,
        balanceAfter,
        t
      ),

      db.prepare(`
        UPDATE billing_requests
        SET status='COMPLETED', response_json=?
        WHERE request_id=? AND changes()>0
      `).bind(JSON.stringify(outPayload), requestId)
    ]);

    if (!(results[0]?.meta?.changes > 0)) {
      await releasePendingIdempotency(db, requestId);
      return json({
        error: "Balance update failed; retry"
      }, 409);
    }

    const after = await db.prepare(
      "SELECT balance FROM subadmins WHERE id=?"
    ).bind(id).first();

    return json({
      ok: true,
      balance: after.balance,
      delta
    });
  }

  if (path === "/api/admin/subadmins/status" && method === "POST") {
    const id = str(b.subadminId, 80);

    if (!id || typeof b.active !== "boolean") {
      return json({ error: "Subadmin and active status required" }, 400);
    }

    const r = await db.prepare(
      "UPDATE subadmins SET active=?,updated_at=? WHERE id=?"
    ).bind(b.active ? 1 : 0, nowISO(), id).run();

    return (r.meta?.changes || 0)
      ? json({ ok: true, active: b.active })
      : json({ error: "Subadmin not found" }, 404);
  }

  if (path === "/api/admin/subadmins/password" && method === "POST") {
    const id = str(b.subadminId, 80);
    const password = String(b.password || "");

    if (!id || password.length < 8 || password.length > 200) {
      return json({
        error: "New password must be at least 8 characters"
      }, 400);
    }

    const salt = b64u(crypto.getRandomValues(new Uint8Array(16)));
    const hash = await passwordHash(password, salt);

    const r = await db.prepare(
      "UPDATE subadmins SET password_hash=?,salt=?,updated_at=? WHERE id=?"
    ).bind(hash, salt, nowISO(), id).run();

    return (r.meta?.changes || 0)
      ? json({ ok: true })
      : json({ error: "Subadmin not found" }, 404);
  }

  if (path === "/api/admin/subadmins/delete" && method === "POST") {
    const id = str(b.subadminId, 80);

    if (!id) return json({ error: "Subadmin ID required" }, 400);

    const a = await db.prepare(
      "SELECT id,username FROM subadmins WHERE id=?"
    ).bind(id).first();

    if (!a) return json({ error: "Subadmin not found" }, 404);

    await db.batch([
      db.prepare(`
        UPDATE activation_keys
        SET revoked=1, status='REVOKED'
        WHERE owner_id=?
      `).bind(id),

      db.prepare(`
        DELETE FROM devices
        WHERE key_hash IN (
          SELECT key_hash
          FROM activation_keys
          WHERE owner_id=?
        )
      `).bind(id),

      db.prepare(
        "DELETE FROM subadmin_pricing_overrides WHERE subadmin_id=?"
      ).bind(id)
    ]);

    await db.prepare(
      "UPDATE wallet_transactions SET username_snapshot=? WHERE subadmin_id=? AND username_snapshot=''"
    ).bind(str(a.username, 40), id).run();

    await db.prepare(
      "DELETE FROM subadmins WHERE id=?"
    ).bind(id).run();

    return json({
      ok: true,
      deleted: true,
      keysRevoked: true,
      historyPreserved: true
    });
  }

  if (path === "/api/admin/wallet/transactions" && method === "GET") {
    const id = str(
      new URL(req.url).searchParams.get("subadminId"),
      80
    );

    const r = id
      ? await db.prepare(
          "SELECT * FROM wallet_transactions WHERE subadmin_id=? ORDER BY created_at DESC LIMIT 500"
        ).bind(id).all()
      : await db.prepare(
          "SELECT w.*,COALESCE(s.username,w.username_snapshot,w.subadmin_id) AS username FROM wallet_transactions w LEFT JOIN subadmins s ON s.id=w.subadmin_id ORDER BY w.created_at DESC LIMIT 1000"
        ).all();

    return json({ transactions: r.results || [] });
  }

  if (path === "/api/admin/key-transactions" && method === "GET") {
    const urlObj = new URL(req.url);
    const ownerId = str(urlObj.searchParams.get("subadminId"), 80);
    const keyHint = str(urlObj.searchParams.get("key"), 200);

    let r;
    if (keyHint) {
      const kh = await sha256(keyHint);
      r = await db.prepare(`
        SELECT kt.*, COALESCE(s.username, kt.owner_id) AS owner_name
        FROM key_transactions kt
        LEFT JOIN subadmins s ON s.id = kt.owner_id
        WHERE kt.key_hash=?
        ORDER BY kt.created_at DESC LIMIT 500
      `).bind(kh).all();
    } else if (ownerId) {
      r = await db.prepare(`
        SELECT kt.*, COALESCE(s.username, kt.owner_id) AS owner_name
        FROM key_transactions kt
        LEFT JOIN subadmins s ON s.id = kt.owner_id
        WHERE kt.owner_id=?
        ORDER BY kt.created_at DESC LIMIT 500
      `).bind(ownerId).all();
    } else {
      r = await db.prepare(`
        SELECT kt.*, COALESCE(s.username, kt.owner_id) AS owner_name
        FROM key_transactions kt
        LEFT JOIN subadmins s ON s.id = kt.owner_id
        ORDER BY kt.created_at DESC LIMIT 1000
      `).all();
    }

    return json({ transactions: r.results || [] });
  }

  if (path === "/api/admin/settings/key-price" && method === "GET") {
    return json({ keyPrice: await getPrice(db) });
  }

  if (path === "/api/admin/settings/key-price" && method === "POST") {
    const p = Number(b.keyPrice);

    if (!Number.isSafeInteger(p) || p < 0 || p > 10000000) {
      return json({ error: "Key price must be 0–10000000" }, 400);
    }

    await db.prepare(
      "INSERT INTO ul_settings(setting_key,setting_value) VALUES('key_price',?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value"
    ).bind(String(p)).run();

    return json({ ok: true, keyPrice: p });
  }

  if (path === "/api/admin/update" && method === "GET") {
    const u = await db.prepare(
      "SELECT * FROM app_updates WHERE id=1"
    ).first();

    return json({
      versionCode: u?.version_code || 1,
      versionName: u?.version_name || "1.0",
      downloadUrl: u?.download_url || "",
      message: u?.message || "",
      forceUpdate: !!u?.force_update,
      updatedAt: u?.updated_at || null
    });
  }

  if (path === "/api/admin/update" && method === "POST") {
    const vc = Number(b.versionCode);
    const vn = str(b.versionName, 50);
    const download = str(b.downloadUrl, 1000);
    const message = str(b.message, 2000);
    const force = b.forceUpdate === true;

    let u;

    try {
      u = new URL(download);
    } catch {
      return json({ error: "Invalid APK URL" }, 400);
    }

    if (!Number.isInteger(vc) || vc < 1 || !vn ||
        u.protocol !== "https:" ||
        u.hostname !== "github.com" ||
        !u.pathname.includes("/releases/download/") ||
        !u.pathname.toLowerCase().endsWith(".apk")) {
      return json({
        error: "Use a valid version and direct HTTPS GitHub Releases APK URL"
      }, 400);
    }

    const t = nowISO();

    await db.prepare(`INSERT INTO app_updates(
      id,version_code,version_name,download_url,message,force_update,updated_at
    ) VALUES(1,?,?,?,?,?,?)
    ON CONFLICT(id) DO UPDATE SET
      version_code=excluded.version_code,
      version_name=excluded.version_name,
      download_url=excluded.download_url,
      message=excluded.message,
      force_update=excluded.force_update,
      updated_at=excluded.updated_at`)
      .bind(vc, vn, download, message, force ? 1 : 0, t).run();

    return json({
      ok: true,
      message: "App update settings saved",
      versionCode: vc,
      versionName: vn,
      forceUpdate: force
    });
  }

  if (path === "/api/admin/flash" && method === "GET") {
    const f = await db.prepare(
      "SELECT * FROM flash_messages WHERE id=1"
    ).first();

    return json({
      active: !!f?.active,
      title: f?.title || "",
      message: f?.message || "",
      fileUrl: f?.file_url || "",
      fileName: f?.file_name || "",
      updatedAt: f?.updated_at || null,
      githubUploadConfigured: !!(env.GITHUB_TOKEN && env.GITHUB_REPO)
    });
  }

  if (path === "/api/admin/flash" && method === "POST") {
    const active = b.active === true;
    const title = str(b.title, 200);
    const message = str(b.message, 4000);
    const fileUrl = str(b.fileUrl, 1000);
    let fileName = str(b.fileName, 200);

    if (active && !title && !message) {
      return json({
        error: "Enter a Flash Pop-up title or message before enabling"
      }, 400);
    }

    if (fileUrl) {
      let parsed;
      try {
        parsed = new URL(fileUrl);
      } catch {
        return json({ error: "Invalid file URL" }, 400);
      }

      if (
        parsed.protocol !== "https:" ||
        parsed.hostname !== "github.com" ||
        !parsed.pathname.includes("/releases/download/") ||
        !/\.(apk|zip)$/i.test(parsed.pathname)
      ) {
        return json({
          error: "Use a direct HTTPS GitHub Releases .apk or .zip URL"
        }, 400);
      }

      if (!fileName) {
        fileName = str(
          decodeURIComponent(parsed.pathname.split("/").pop() || ""),
          200
        );
      }
    } else {
      fileName = "";
    }

    const t = nowISO();

    await db.prepare(`INSERT INTO flash_messages(
      id,title,message,active,file_url,file_name,updated_at
    ) VALUES(1,?,?,?,?,?,?)
    ON CONFLICT(id) DO UPDATE SET
      title=excluded.title,
      message=excluded.message,
      active=excluded.active,
      file_url=excluded.file_url,
      file_name=excluded.file_name,
      updated_at=excluded.updated_at`)
      .bind(title, message, active ? 1 : 0, fileUrl, fileName, t).run();

    return json({
      ok: true,
      active,
      title,
      message,
      fileUrl,
      fileName,
      updatedAt: t
    });
  }

  return json({ error: "Not found" }, 404);
}

async function subRoutes(req, env, db, path) {
  if (path === "/api/subadmin/login" && req.method === "POST") {
    const b = await readBody(req);
    const username = str(b.username, 40);
    const password = String(b.password || "");

    const a = await db.prepare(
      "SELECT * FROM subadmins WHERE username=?"
    ).bind(username).first();

    if (!a || !a.active ||
        !await passwordHash(password, a.salt).then(h => h === a.password_hash)) {
      return json({ error: "Invalid username or password" }, 401);
    }

    return json({
      ok: true,
      token: await tokenFor({
        role: "subadmin",
        subadminId: a.id,
        username: a.username
      }, env.SESSION_SECRET)
    });
  }

  const s = await subOnly(req, env, db);

  if (!s) return json({ error: "Unauthorized" }, 401);

  if (path === "/api/subadmin/me" && req.method === "GET") {
    const plans = await listEffectivePlans(db, s.subadminId, false);
    return json({
      username: s.account.username,
      balance: s.account.balance,
      keyPrice: await getPrice(db),
      plans
    });
  }

  if (path === "/api/subadmin/pricing/preview" && req.method === "GET") {
    const u = new URL(req.url);
    const key = str(u.searchParams.get("key"), 200);
    const planId = str(u.searchParams.get("planId"), 60);
    const durationMinutes = u.searchParams.get("durationMinutes");
    const action = str(u.searchParams.get("action") || "UPGRADE", 20).toUpperCase();

    if (!key) return json({ error: "Key is required" }, 400);
    const hash = await sha256(key);
    const existing = await db.prepare(
      "SELECT key_hint,paid_amount,duration_minutes,expires_at,revoked FROM activation_keys WHERE key_hash=? AND owner_id=?"
    ).bind(hash, s.subadminId).first();
    if (!existing) return json({ error: "Your key was not found" }, 404);

    let plan;
    try {
      plan = await resolveServerPlan(db, {
        planId: planId || undefined,
        durationMinutes: durationMinutes ? Number(durationMinutes) : undefined
      }, s.subadminId);
    } catch (e) {
      return json({ error: e.message || "Invalid plan" }, 400);
    }

    const previouslyPaid = Math.max(0, Number(existing.paid_amount || 0));
    const additionalCharge = Math.max(0, plan.price - previouslyPaid);
    const availableBalance = Number(s.account.balance);

    return json({
      ok: true,
      action,
      key: existing.key_hint,
      planId: plan.planId,
      planName: plan.planName,
      planPrice: plan.price,
      previouslyPaid,
      additionalCharge,
      availableBalance,
      sufficientBalance: availableBalance >= additionalCharge
    });
  }

  if (path === "/api/subadmin/keys" && req.method === "GET") {
    return json({ keys: await listKeys(db, s.subadminId) });
  }

  if (path === "/api/subadmin/wallet/transactions" && req.method === "GET") {
    const r = await db.prepare(
      "SELECT amount,type,note,key_hint,balance_before,balance_after,created_at FROM wallet_transactions WHERE subadmin_id=? ORDER BY created_at DESC LIMIT 500"
    ).bind(s.subadminId).all();

    return json({ transactions: r.results || [] });
  }

  if (path === "/api/subadmin/key-transactions" && req.method === "GET") {
    const r = await db.prepare(
      "SELECT * FROM key_transactions WHERE owner_id=? ORDER BY created_at DESC LIMIT 500"
    ).bind(s.subadminId).all();

    return json({ transactions: r.results || [] });
  }

  if (path === "/api/subadmin/keys/generate" && req.method === "POST") {
    const b = await readBody(req);
    const requestId = str(b.requestId, 100);

    if (!requestId) {
      return json({
        error: "requestId is required for safe purchase"
      }, 400);
    }

    const lock = await acquireIdempotency(db, requestId, "SUBADMIN", s.subadminId, "KEY_PURCHASE");
    if (!lock.acquired) {
      return json(lock.payload, lock.statusCode);
    }

    const priorReq = await db.prepare(
      "SELECT key_hash FROM key_requests WHERE request_id=? AND subadmin_id=?"
    ).bind(requestId, s.subadminId).first();
    if (priorReq) {
      await releasePendingIdempotency(db, requestId);
      return json({
        error: "This request was already processed. Refresh your key list."
      }, 409);
    }

    let plan;
    let key;
    let keyHash;
    try {
      plan = await resolveServerPlan(db, b, s.subadminId);
      key = makeKey(b.customSuffix);
      keyHash = await sha256(key);
    } catch (e) {
      await releasePendingIdempotency(db, requestId);
      return json({ error: e.message || "Invalid key details" }, 400);
    }

    const duplicate = await db.prepare(
      "SELECT key_hash FROM activation_keys WHERE key_hash=?"
    ).bind(keyHash).first();
    if (duplicate) {
      await releasePendingIdempotency(db, requestId);
      return json({ error: "This custom activation key already exists" }, 409);
    }

    const price = plan.price;
    const balanceBefore = Number(s.account.balance);
    if (balanceBefore < price) {
      await releasePendingIdempotency(db, requestId);
      return json({
        error: `Insufficient balance. Required: $${price}, Available: $${balanceBefore}`,
        requiredAmount: price,
        availableBalance: balanceBefore
      }, 402);
    }

    const balanceAfter = balanceBefore - price;
    const now = nowISO();
    const expiresAt = plan.customExpiresAt || new Date(Date.now() + plan.durationMinutes * 60000).toISOString();

    const responsePayload = {
      ok: true,
      created: 1,
      key,
      expiresAt,
      planId: plan.planId,
      planName: plan.planName,
      durationMinutes: plan.durationMinutes,
      durationDays: plan.durationDays,
      charged: price,
      paidAmount: price,
      balanceAfter
    };

    try {
      const batchResults = await db.batch([
        db.prepare(
          "UPDATE subadmins SET balance=balance-?,updated_at=? WHERE id=? AND active=1 AND balance>=?"
        ).bind(price, now, s.subadminId, price),

        db.prepare(`
          INSERT INTO activation_keys(
            key_hash,key_hint,expires_at,revoked,created_at,max_devices,owner_id,purchase_id,
            paid_amount,duration_minutes,duration_days,plan_id,status,activated_at,last_reset_at
          )
          SELECT ?,?,?,0,?,1,?,?,?,?,?,?,'ACTIVE','',''
          WHERE changes()>0
        `).bind(
          keyHash, key, expiresAt, now, s.subadminId, requestId,
          price, plan.durationMinutes, plan.durationDays, plan.planId
        ),

        db.prepare(`
          INSERT INTO key_transactions(
            id,request_id,key_hash,key_hint,owner_id,actor_type,actor_id,action_type,
            plan_id,plan_name,previous_duration_minutes,new_duration_minutes,
            previous_paid_amount,plan_price,charged_amount,new_total_paid,
            previous_expires_at,new_expires_at,balance_before,balance_after,note,created_at
          )
          SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
          WHERE changes()>0
        `).bind(
          crypto.randomUUID(),
          requestId,
          keyHash,
          key,
          s.subadminId,
          "SUBADMIN",
          s.subadminId,
          "PURCHASE",
          plan.planId,
          plan.planName,
          0,
          plan.durationMinutes,
          0,
          price,
          price,
          price,
          "",
          expiresAt,
          balanceBefore,
          balanceAfter,
          `New key purchase (${plan.planName}) — charged full plan price $${price}`,
          now
        ),

        db.prepare(`
          INSERT INTO wallet_transactions(
            id,subadmin_id,amount,type,note,actor_id,request_id,username_snapshot,
            key_hash,key_hint,balance_before,balance_after,created_at
          )
          SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?
          WHERE changes()>0
        `).bind(
          crypto.randomUUID(),
          s.subadminId,
          -price,
          "key_purchase",
          `New key purchase (${plan.planName})`,
          "SUBADMIN",
          requestId,
          s.account.username || "",
          keyHash,
          key,
          balanceBefore,
          balanceAfter,
          now
        ),

        db.prepare(`
          INSERT INTO key_requests(request_id,subadmin_id,key_hash,created_at)
          SELECT ?,?,?,?
          WHERE changes()>0
        `).bind(requestId, s.subadminId, keyHash, now),

        db.prepare(`
          UPDATE billing_requests
          SET status='COMPLETED', key_hash=?, response_json=?
          WHERE request_id=? AND changes()>0
        `).bind(keyHash, JSON.stringify(responsePayload), requestId)
      ]);

      if (!(batchResults[0]?.meta?.changes > 0)) {
        await releasePendingIdempotency(db, requestId);
        return json({ error: "Insufficient balance" }, 402);
      }

      return json(responsePayload);
    } catch (e) {
      await releasePendingIdempotency(db, requestId);
      throw e;
    }
  }

  if (path === "/api/subadmin/keys/upgrade" && req.method === "POST") {
    const b = await readBody(req);
    const requestId = str(b.requestId, 100);
    if (!requestId) {
      return json({ error: "requestId is required for safe upgrade" }, 400);
    }

    const lock = await acquireIdempotency(db, requestId, "SUBADMIN", s.subadminId, "KEY_UPGRADE");
    if (!lock.acquired) return json(lock.payload, lock.statusCode);

    try {
      const outcome = await processKeyUpgradeOrReset(db, {
        action: "UPGRADE",
        key: b.key,
        body: b,
        ownerFilterId: s.subadminId,
        actorType: "SUBADMIN",
        actorId: s.subadminId,
        requestId,
        chargeWallet: true
      });
      if (outcome.statusCode !== 200) {
        await releasePendingIdempotency(db, requestId);
      }
      return json(outcome.payload, outcome.statusCode);
    } catch (e) {
      await releasePendingIdempotency(db, requestId);
      return json({ error: e.message || "Invalid upgrade request" }, 400);
    }
  }

  if (path === "/api/subadmin/keys/reset" && req.method === "POST") {
    const b = await readBody(req);
    const requestId = str(b.requestId, 100);
    if (!requestId) {
      return json({ error: "requestId is required for safe reset" }, 400);
    }

    const lock = await acquireIdempotency(db, requestId, "SUBADMIN", s.subadminId, "KEY_RESET");
    if (!lock.acquired) return json(lock.payload, lock.statusCode);

    try {
      const outcome = await processKeyUpgradeOrReset(db, {
        action: "RESET",
        key: b.key,
        body: b,
        ownerFilterId: s.subadminId,
        actorType: "SUBADMIN",
        actorId: s.subadminId,
        requestId,
        chargeWallet: true
      });
      if (outcome.statusCode !== 200) {
        await releasePendingIdempotency(db, requestId);
      }
      return json(outcome.payload, outcome.statusCode);
    } catch (e) {
      await releasePendingIdempotency(db, requestId);
      return json({ error: e.message || "Invalid reset request" }, 400);
    }
  }

  if (path === "/api/subadmin/keys/status" && req.method === "POST") {
    const b = await readBody(req);

    if (!b.key || typeof b.revoked !== "boolean") {
      return json({ error: "Key and revoked boolean required" }, 400);
    }

    if (!await setRevoked(db, str(b.key, 200), b.revoked, s.subadminId)) {
      return json({ error: "Your key was not found" }, 404);
    }

    return json({ ok: true, revoked: b.revoked });
  }

  if (path === "/api/subadmin/keys/delete" && req.method === "POST") {
    const b = await readBody(req);
    const hash = await sha256(str(b.key, 200));

    const found = await db.prepare(
      "SELECT key_hash FROM activation_keys WHERE key_hash=? AND owner_id=?"
    ).bind(hash, s.subadminId).first();

    if (!found) return json({ error: "Your key was not found" }, 404);

    await db.batch([
      db.prepare("DELETE FROM devices WHERE key_hash=?").bind(hash),
      db.prepare(
        "DELETE FROM activation_keys WHERE key_hash=? AND owner_id=?"
      ).bind(hash, s.subadminId)
    ]);

    return json({ ok: true, deleted: true, historyPreserved: true });
  }

  return json({ error: "Not found" }, 404);
}

const ADMIN_PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ULTRA LOADER</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#080e19;color:#edf4ff;font:15px Arial}
main{max-width:1080px;margin:20px auto;padding:14px}
h1{color:#58d9ff}
.panel{background:#121e31;border:1px solid #293d5b;border-radius:14px;padding:16px;margin:14px 0}
input,select,textarea{width:100%;padding:11px;margin:6px 0;background:#081321;color:white;border:1px solid #405574;border-radius:8px}
input[type=checkbox]{width:auto}
label{display:block;margin-top:9px;color:#bcd0e8}
button{padding:9px 13px;margin:4px 4px 4px 0;background:#0788b5;color:white;border:0;border-radius:7px;font-weight:bold;cursor:pointer}
.danger{background:#bd3548}
.secondary{background:#30415b}
.green{background:#16885d}
.warn{background:#b86b11}
.hidden{display:none}
.item{padding:12px;margin:9px 0;background:#0b1728;border:1px solid #30415b;border-radius:9px;overflow-wrap:anywhere}
.muted{color:#a9bad0}
.msg{white-space:pre-wrap;color:#58d9ff}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:10px}
.row-inline{display:grid;grid-template-columns:1.2fr 0.8fr 0.8fr auto auto;gap:8px;align-items:center}
@media(max-width:720px){.row-inline{grid-template-columns:1fr}}
</style>
</head>
<body>
<main>
<h1>ULTRA LOADER</h1>
<p>Online License, Dynamic Pricing &amp; Update Control Center</p>

<section id="login" class="panel">
<h2>Admin Login</h2>
<input id="apass" type="password" placeholder="Admin password">
<button id="adminLoginBtn" type="button" onclick="adminLogin()">Sign in</button>
<p id="loginmsg" class="msg"></p>
<hr>
<h3>Subadmin Login</h3>
<input id="suser" placeholder="Username">
<input id="spass" type="password" placeholder="Password">
<button onclick="subLogin()">Subadmin Sign in</button>
</section>

<section id="admin" class="hidden">
<div class="panel">
<h2>Activation Key Management</h2>
<div class="grid">
<div>
<label>Validity Mode</label>
<select id="mode" onchange="toggleMode('')">
<option value="plan">Dynamic Plan</option>
<option value="duration">Custom Duration</option>
<option value="expiry">Exact expiry</option>
</select>
<select id="planSelect"></select>
<div id="customDurBox" class="hidden">
<input id="days" type="number" min="0" value="30" placeholder="Days">
<input id="hours" type="number" min="0" max="23" value="0" placeholder="Hours">
<input id="minutes" type="number" min="0" max="59" value="0" placeholder="Minutes">
</div>
<input id="expiry" class="hidden" type="datetime-local">
</div>
<div>
<label>Device Limit (1–99999)</label>
<input id="limit" type="number" min="1" max="99999" value="1">
<label>Custom Key Suffix (optional; UL- is added automatically)</label>
<input id="customSuffix" maxlength="80" placeholder="e.g. PREMIUM2026">
<button onclick="makeAdminKey()">Generate Key</button>
<pre id="newkey"></pre>
<p id="keymsg" class="msg"></p>
</div>
</div>
<button class="secondary" onclick="loadKeys()">Refresh Keys</button>
<div id="keys"></div>
</div>

<div class="panel">
<h2>Connected Device Information</h2>
<p class="muted">Multiple keys supported per device. Expired keys have only their own binding automatically removed without affecting other active keys on the same device.</p>
<button class="secondary" onclick="loadDevices()">Refresh Devices</button>
<div id="devices">Loading...</div>
</div>

<div class="panel">
<h2>Subadmin Management</h2>
<div class="grid">
<input id="newuser" placeholder="New username">
<input id="newpass" type="password" placeholder="Password (8+ chars)">
</div>
<button onclick="createSub()">Create Subadmin</button>
<p id="submsg" class="msg"></p>
<div id="subs"></div>
</div>

<div class="panel">
<h2>Price Management Dashboard</h2>
<p class="muted">Edit global duration prices, add custom plans, disable plans, and configure Subadmin-specific pricing rates. Changes save directly to Cloudflare D1 and apply immediately server-side.</p>
<h3>Global Pricing Plans</h3>
<div id="planList">Loading plans...</div>

<h3>Add Custom Duration Plan</h3>
<div class="grid">
<input id="newPlanName" placeholder="Plan Name (e.g. 60 Days VIP)">
<input id="newPlanDays" type="number" min="1" placeholder="Duration (Days)">
<input id="newPlanPrice" type="number" min="0" placeholder="Price ($)">
</div>
<button class="green" onclick="addCustomPlan()">Add Custom Plan</button>

<h3>Subadmin Custom Pricing Rates</h3>
<div class="grid">
<select id="rateSubadmin"></select>
<select id="ratePlan"></select>
<input id="ratePrice" type="number" min="0" placeholder="Custom Price ($)">
</div>
<label><input id="rateActive" type="checkbox" checked> Plan Enabled for Selected Subadmin</label>
<button onclick="saveSubadminRate()">Save Subadmin Rate</button>
<div id="subRatesList"></div>
<p id="pricemsg" class="msg"></p>

<hr>
<h3>Key Pricing &amp; Wallet Ledger History</h3>
<button class="secondary" onclick="loadTransactions()">Wallet Transactions</button>
<button class="secondary" onclick="loadKeyTransactions()">Key Purchase &amp; Upgrade History</button>
<div id="tx"></div>
</div>

<div class="panel">
<h2>App Update Management</h2>
<input id="vc" type="number" placeholder="Version Code">
<input id="vn" placeholder="Version Name">
<input id="url" placeholder="GitHub Release APK URL">
<textarea id="umsg" placeholder="Update message"></textarea>
<label><input id="force" type="checkbox"> Force Update</label>
<button onclick="saveUpdate()">Save Update</button>
<p id="upmsg" class="msg"></p>
</div>

<div class="panel">
<h2>Flash Pop-up Management</h2>
<label><input id="flashActive" type="checkbox"> Enable Flash Pop-up</label>
<input id="flashTitle" placeholder="Flash Pop-up Title">
<textarea id="flashMessage" placeholder="Flash Pop-up Message"></textarea>
<label>Optional File Upload (VPN Update APK or ZIP &rarr; GitHub Releases)</label>
<input id="flashFileInput" type="file" accept=".apk,.zip">
<button class="secondary" type="button" onclick="uploadFlashFile()">Upload APK/ZIP to GitHub Releases</button>
<input id="flashFileUrl" placeholder="Optional GitHub Releases APK/ZIP URL (leave blank for no download)">
<input id="flashFileName" placeholder="Optional File Name (auto-filled)">
<button type="button" onclick="saveFlash()">Save Flash Pop-up</button>
<button class="danger" type="button" onclick="clearFlashFile()">Remove File</button>
<p id="flashmsg" class="msg"></p>
</div>

<button class="secondary" onclick="logout()">Logout</button>
</section>

<section id="subdash" class="hidden">
<div class="panel">
<h2>Subadmin Dashboard</h2>
<p id="wallet" class="msg"></p>
<div id="subPlansBanner" class="muted"></div>
<button class="secondary" onclick="loadSubKeys()">Refresh My Keys</button>
<div id="subkeys"></div>
</div>

<div class="panel">
<h3>Generate New Activation Key</h3>
<label>Validity Mode</label>
<select id="smode" onchange="toggleMode('s')">
<option value="plan">Select Plan</option>
<option value="duration">Custom Duration</option>
<option value="expiry">Exact expiry</option>
</select>
<select id="splanSelect"></select>
<div id="scustomDurBox" class="hidden">
<input id="sdays" type="number" min="0" value="30" placeholder="Days">
<input id="shours" type="number" min="0" max="23" value="0" placeholder="Hours">
<input id="sminutes" type="number" min="0" max="59" value="0" placeholder="Minutes">
</div>
<input id="sexpiry" class="hidden" type="datetime-local">
<label>Custom Key Suffix (optional; UL- is added automatically)</label>
<input id="scustomSuffix" maxlength="80" placeholder="e.g. PREMIUM2026">
<button onclick="makeSubKey()">Purchase &amp; Generate Key</button>
<pre id="snewkey"></pre>
<p id="smsg" class="msg"></p>
</div>

<div class="panel">
<h3>My Financial &amp; Key Transactions</h3>
<button class="secondary" onclick="loadMyTx()">Wallet History</button>
<button class="secondary" onclick="loadMyKeyTx()">Key Purchase / Upgrade History</button>
<div id="mytx"></div>
</div>

<button class="secondary" onclick="logout()">Logout</button>
</section>
</main>

<script>
let token=sessionStorage.getItem("ul_token")||"";
let role=sessionStorage.getItem("ul_role")||"";
let cachedPlans=[];
let cachedSubs=[];

const $=id=>document.getElementById(id);

const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({
  "&":"&amp;",
  "<":"&lt;",
  ">":"&gt;",
  '"':"&quot;",
  "'":"&#39;"
}[c]));

async function request(path,method="GET",body){
  const r=await fetch("/api"+path,{
    method,
    headers:{
      "Content-Type":"application/json",
      "Authorization":"Bearer "+token
    },
    body:body?JSON.stringify(body):undefined
  });

  let d;
  try{ d=await r.json(); }catch{ throw Error("Invalid server response"); }
  if(!r.ok){
    throw Error(d.detail ? d.error + ": " + d.detail : (d.error || "Request failed"));
  }
  return d;
}

function show(){
  $("login").classList.add("hidden");
  $(role==="admin"?"admin":"subdash").classList.remove("hidden");
}

function toggleMode(prefix){
  const m=$(prefix+"mode").value;
  $(prefix+"planSelect").classList.toggle("hidden", m!=="plan");
  $(prefix+"customDurBox").classList.toggle("hidden", m!=="duration");
  $(prefix+"expiry").classList.toggle("hidden", m!=="expiry");
}

async function adminLogin(){
  const button = $("adminLoginBtn");
  const message = $("loginmsg");
  const password = $("apass").value;

  if(!password.trim()){
    message.textContent = "Enter admin password";
    $("apass").focus();
    return;
  }

  if(button) button.disabled = true;
  message.textContent = "Signing in...";

  try{
    const r = await fetch("/api/admin/login",{
      method:"POST",
      headers:{ "Content-Type":"application/json" },
      body:JSON.stringify({password})
    });
    const raw = await r.text();
    let d;
    try{ d = JSON.parse(raw); }catch{ throw Error("Server returned an invalid response (HTTP " + r.status + ")"); }
    if(!r.ok || d.ok !== true || !d.token){
      throw Error(d.error || d.detail || ("Login failed (HTTP " + r.status + ")"));
    }
    saveSession(d.token,"admin");
    await start();
    message.textContent = "";
  }catch(e){
    message.textContent = e?.message || "Admin login failed";
  }finally{
    if(button) button.disabled = false;
  }
}

async function subLogin(){
  const username = $("suser").value.trim();
  const password = $("spass").value;
  const button = document.querySelector('button[onclick="subLogin()"]');
  if(!username){ alert("Enter subadmin username"); $("suser").focus(); return; }
  if(!password){ alert("Enter subadmin password"); $("spass").focus(); return; }
  if(button) button.disabled = true;
  try{
    const d = await request("/subadmin/login","POST",{ username, password });
    if(!d.ok || !d.token) throw Error(d.error || "Subadmin login failed");
    saveSession(d.token,"subadmin");
    await start();
  }catch(e){
    alert(e.message || "Subadmin login failed");
  }finally{
    if(button) button.disabled = false;
  }
}

function saveSession(t,r){
  token=t; role=r;
  sessionStorage.setItem("ul_token",t);
  sessionStorage.setItem("ul_role",r);
}

async function start(){
  show();
  if(role==="admin"){
    await Promise.all([
      loadPricing(),
      loadKeys(),
      loadDevices(),
      loadSubs(),
      loadUpdate(),
      loadFlash()
    ]);
  }else{
    await Promise.all([
      loadWallet(),
      loadSubKeys(),
      loadMyTx()
    ]);
  }
}

function logout(){
  sessionStorage.clear();
  location.reload();
}

function validity(prefix){
  const m=$(prefix+"mode").value;
  if(m==="plan"){
    const planId=$(prefix+"planSelect").value;
    if(!planId) throw Error("Select a pricing plan");
    return {planId};
  }
  if(m==="expiry"){
    let x=new Date($(prefix+"expiry").value);
    if(!$(prefix+"expiry").value||x<=new Date()) throw Error("Choose future expiry");
    return {expiresAt:x.toISOString()};
  }
  let d=Number($(prefix+"days").value);
  let h=Number($(prefix+"hours").value);
  let min=Number($(prefix+"minutes").value);
  let n=d*1440+h*60+min;
  if(!Number.isSafeInteger(n)||n<1||n>3650*1440) throw Error("Validity must be 1 minute–3650 days");
  return {durationMinutes:n};
}

async function copyKey(key){
  try{
    await navigator.clipboard.writeText(String(key));
    alert("Key copied");
  }catch(e){
    const area=document.createElement("textarea");
    area.value=String(key);
    document.body.appendChild(area);
    area.select();
    const ok=document.execCommand("copy");
    area.remove();
    alert(ok?"Key copied":"Copy failed; please copy manually");
  }
}

async function upgradeOrResetKey(key, action, isSubadmin=false){
  const activePlans=(cachedPlans||[]).filter(p=>p.active);
  const hint=activePlans.map(p=>p.durationDays+"d ($"+p.price+")").join(", ");
  const raw=prompt((action==="UPGRADE"?"Upgrade Duration":"Paid Reset")+" — enter days (Plans: "+hint+")","30");
  if(raw===null) return;
  const days=Number(raw);
  if(!Number.isSafeInteger(days)||days<1||days>3650){
    alert("Enter a whole number from 1 to 3650 days");
    return;
  }
  const matchedPlan=activePlans.find(p=>p.durationDays===days);
  const durationMinutes=days*1440;
  if(isSubadmin){
    try{
      const q=await request("/subadmin/pricing/preview?key="+encodeURIComponent(key)+"&action="+action+(matchedPlan?"&planId="+encodeURIComponent(matchedPlan.id):"&durationMinutes="+durationMinutes));
      if(!q.sufficientBalance){
        alert("Insufficient balance! Required diff: $"+q.additionalCharge+" (New plan $"+q.planPrice+" - Previously paid $"+q.previouslyPaid+"). Available: $"+q.availableBalance);
        return;
      }
      if(!confirm((action==="UPGRADE"?"Upgrade key":"Reset key")+" to "+q.planName+"?\\nPreviously paid: $"+q.previouslyPaid+"\\nNew plan price: $"+q.planPrice+"\\nAdditional charge: $"+q.additionalCharge+"\\nBalance after: $"+(q.availableBalance-q.additionalCharge))) return;
    }catch(e){
      alert(e.message);
      return;
    }
  }else{
    if(!confirm((action==="UPGRADE"?"Upgrade this key to ":"Reset this key (clears device bindings) to ")+days+" days?")) return;
  }

  try{
    const route=(isSubadmin?"/subadmin":"/admin")+(action==="UPGRADE"?"/keys/upgrade":"/keys/reset");
    const body={key, requestId:crypto.randomUUID()};
    if(matchedPlan) body.planId=matchedPlan.id; else body.durationMinutes=durationMinutes;
    const res=await request(route,"POST",body);
    alert((action==="UPGRADE"?"Key upgraded":"Key reset")+"! Charged: $"+res.charged+" | New Expiry: "+res.expiresAt);
    if(isSubadmin){
      await Promise.all([loadWallet(), loadSubKeys(), loadMyKeyTx()]);
    }else{
      await loadKeys();
    }
  }catch(e){
    alert(e.message);
  }
}

async function makeAdminKey(){
  try{
    const d=await request(
      "/admin/keys/generate",
      "POST",
      {...validity(""),maxDevices:Number($("limit").value),customSuffix:$("customSuffix").value.trim(),requestId:crypto.randomUUID()}
    );
    $("newkey").textContent=d.key+"\\nExpires: "+d.expiresAt+"\\nPlan: "+d.planName+" ($"+d.planPrice+")";
    $("keymsg").textContent="Created";
    await loadKeys();
  }catch(e){
    $("keymsg").textContent=e.message;
  }
}

async function loadKeys(){
  try{
    const d=await request("/admin/keys");
    $("keys").innerHTML=d.keys.map(k=>\`
      <div class="item">
        <b>\${esc(k.key_hint)}</b><br>
        Status: <b>\${esc(k.status)}</b> | Devices: \${k.device_count}/\${k.max_devices} | Paid: <b>$\${k.paid_amount||0}</b> (\${k.duration_days||0}d)<br>
        Created by: <b>\${esc(k.creator_type)}\${k.creator_username ? " — "+esc(k.creator_username)+" (ID: "+esc(k.owner_id)+")" : k.owner_id === "MAIN" ? "" : " — ID: "+esc(k.owner_id)}</b><br>
        Activated: \${esc(k.activated_at||"Not yet")} | Expiry: \${esc(k.expires_at)}<br>
        <button class="secondary" onclick="copyKey('\${esc(k.key_hint)}')">COPY KEY</button>
        <button onclick="upgradeOrResetKey('\${esc(k.key_hint)}','UPGRADE',false)">UPGRADE KEY</button>
        <button class="warn" onclick="upgradeOrResetKey('\${esc(k.key_hint)}','RESET',false)">RESET KEY</button>
        <button class="danger" onclick="keyStatus('\${esc(k.key_hint)}',true)">Disable</button>
        <button class="green" onclick="keyStatus('\${esc(k.key_hint)}',false)">Enable</button>
        <button class="danger" onclick="deleteKey('\${esc(k.key_hint)}')">Permanently Delete</button>
      </div>
    \`).join("")||"No keys";
  }catch(e){
    $("keymsg").textContent=e.message;
  }
}

async function loadDevices(){
  const target=$("devices");
  if(!target) return;
  try{
    const d=await request("/admin/devices");
    target.innerHTML=(d.devices||[]).map(x=>\`
      <div class="item">
        <b>Device ID:</b> \${esc(x.device_id)}<br>
        <b>Device:</b> \${esc(x.device_name||"Not supplied")} \${esc(x.device_model||"")}<br>
        <b>OS / App:</b> \${esc(x.os_version||"Not supplied")} / \${esc(x.app_version||"Not supplied")}<br>
        <b>Activation Key:</b> \${esc(x.key_hint||"Unknown / Master key")}<br>
        <b>Created by:</b> \${esc(x.creator_type)}\${x.creator_username ? " — "+esc(x.creator_username)+" (ID: "+esc(x.owner_id)+")" : x.owner_id && x.owner_id!=="MAIN" ? " — ID: "+esc(x.owner_id) : ""}<br>
        <b>First connected:</b> \${esc(x.created_at||"")} | <b>Last seen:</b> \${esc(x.last_seen||"")}
      </div>\`).join("")||"No connected devices yet";
  }catch(e){target.textContent=e.message||"Could not load devices";}
}

async function keyStatus(key,revoked){
  try{ await request("/admin/keys/status","POST",{key,revoked}); await loadKeys(); }catch(e){ alert(e.message); }
}

async function deleteKey(key){
  if(!confirm("Permanently delete this key and its linked device records? Financial history is preserved."))return;
  try{ await request("/admin/keys/delete","POST",{key}); await loadKeys(); }catch(e){ alert(e.message); }
}

async function createSub(){
  try{
    await request("/admin/subadmins","POST",{ username:$("newuser").value, password:$("newpass").value });
    $("submsg").textContent="Subadmin created";
    $("newpass").value="";
    await loadSubs();
  }catch(e){
    $("submsg").textContent=e.message;
  }
}

async function loadSubs(){
  try{
    const d=await request("/admin/subadmins");
    cachedSubs=d.subadmins||[];
    const rateSub=$("rateSubadmin");
    if(rateSub){
      rateSub.innerHTML=cachedSubs.map(s=>\`<option value="\${esc(s.id)}">\${esc(s.username)} ($\${s.balance})</option>\`).join("")||"<option value=''>No subadmins</option>";
    }
    $("subs").innerHTML=cachedSubs.map(s=>\`
      <div class="item">
        <b>\${esc(s.username)}</b> | Balance $\${s.balance} | Keys \${s.total_keys} | \${s.active?"Active":"Disabled"}<br>
        <button onclick="walletOp('\${s.id}','add')">Add balance</button>
        <button onclick="walletOp('\${s.id}','deduct')">Deduct</button>
        <button onclick="walletOp('\${s.id}','reset')">Reset balance</button>
        <button onclick="resetPass('\${s.id}')">Reset password</button>
        <button onclick="subStatus('\${s.id}',\${!s.active})">\${s.active?"Disable":"Enable"}</button>
        <button class="danger" onclick="deleteSub('\${s.id}')">Permanently Delete</button>
        <button class="secondary" onclick="subTx('\${s.id}')">Wallet Tx</button>
      </div>
    \`).join("")||"No subadmins";
  }catch(e){
    $("submsg").textContent=e.message;
  }
}

async function walletOp(id,type){
  let amount=type==="reset"?1:Number(prompt("Amount in USD"));
  if(!Number.isInteger(amount)||amount<1)return;
  let note=prompt("Note (optional)")||"";
  try{
    await request("/admin/subadmins/balance","POST",{ subadminId:id, type, amount, note, requestId:crypto.randomUUID() });
    await loadSubs();
  }catch(e){ alert(e.message); }
}

async function resetPass(id){
  let p=prompt("New password (at least 8 characters)");
  if(!p)return;
  try{ await request("/admin/subadmins/password","POST",{ subadminId:id, password:p }); alert("Password reset"); }catch(e){ alert(e.message); }
}

async function subStatus(id,active){
  try{ await request("/admin/subadmins/status","POST",{ subadminId:id, active }); await loadSubs(); }catch(e){ alert(e.message); }
}

async function deleteSub(id){
  if(!confirm("Delete this Subadmin? Their keys will be revoked; financial and key history retained."))return;
  try{ await request("/admin/subadmins/delete","POST",{subadminId:id}); await loadSubs(); }catch(e){ alert(e.message); }
}

async function loadPricing(){
  try{
    const d=await request("/admin/pricing");
    cachedPlans=d.plans||[];
    const opts=cachedPlans.filter(p=>p.active).map(p=>\`<option value="\${esc(p.id)}">\${esc(p.name)} — $\${p.price}</option>\`).join("");
    if($("planSelect")) $("planSelect").innerHTML=opts||"<option value=''>No active plans</option>";
    if($("ratePlan")) $("ratePlan").innerHTML=cachedPlans.map(p=>\`<option value="\${esc(p.id)}">\${esc(p.name)} ($\${p.price})</option>\`).join("");

    $("planList").innerHTML=cachedPlans.map(p=>\`
      <div class="item row-inline">
        <input id="pname_\${esc(p.id)}" value="\${esc(p.name)}" placeholder="Name">
        <input id="pdays_\${esc(p.id)}" type="number" min="1" value="\${p.durationDays}" placeholder="Days">
        <input id="pprice_\${esc(p.id)}" type="number" min="0" value="\${p.price}" placeholder="Price ($)">
        <button onclick="savePlanRow('\${esc(p.id)}')">Save</button>
        <button class="\${p.active?"danger":"green"}" onclick="togglePlanStatus('\${esc(p.id)}',\${!p.active})">\${p.active?"Disable":"Enable"}</button>
      </div>
    \`).join("")||"No pricing plans";

    const rates=d.subadminRates||[];
    $("subRatesList").innerHTML=rates.map(r=>\`
      <div class="item">
        Subadmin: <b>\${esc(r.subadmin_username||r.subadmin_id)}</b> |
        Plan: <b>\${esc(r.plan_name||r.plan_id)}</b> |
        Custom Price: <b>$\${r.price}</b> |
        Status: \${r.active?"Enabled":"Disabled"}
        <button class="danger" onclick="removeSubadminRate('\${esc(r.subadmin_id)}','\${esc(r.plan_id)}')">Reset to Global</button>
      </div>
    \`).join("")||"<p class='muted'>No Subadmin custom overrides set (all Subadmins use global plan prices).</p>";
  }catch(e){
    $("pricemsg").textContent=e.message;
  }
}

async function savePlanRow(id){
  try{
    const name=$("pname_"+id).value.trim();
    const durationDays=Number($("pdays_"+id).value);
    const price=Number($("pprice_"+id).value);
    const existing=cachedPlans.find(p=>p.id===id);
    await request("/admin/pricing/plan","POST",{
      id, name, durationDays, price, active: existing ? existing.active : true
    });
    $("pricemsg").textContent="Saved plan: "+name+" ($"+price+")";
    await loadPricing();
  }catch(e){
    $("pricemsg").textContent=e.message;
  }
}

async function togglePlanStatus(id, active){
  try{
    await request("/admin/pricing/plan/status","POST",{ id, active });
    $("pricemsg").textContent="Plan "+(active?"enabled":"disabled");
    await loadPricing();
  }catch(e){
    $("pricemsg").textContent=e.message;
  }
}

async function addCustomPlan(){
  try{
    const name=$("newPlanName").value.trim();
    const durationDays=Number($("newPlanDays").value);
    const price=Number($("newPlanPrice").value);
    await request("/admin/pricing/plan","POST",{ name, durationDays, price, active:true });
    $("newPlanName").value=""; $("newPlanDays").value=""; $("newPlanPrice").value="";
    $("pricemsg").textContent="Custom plan added: "+name;
    await loadPricing();
  }catch(e){
    $("pricemsg").textContent=e.message;
  }
}

async function saveSubadminRate(){
  try{
    await request("/admin/pricing/subadmin","POST",{
      subadminId:$("rateSubadmin").value,
      planId:$("ratePlan").value,
      price:Number($("ratePrice").value),
      active:$("rateActive").checked
    });
    $("pricemsg").textContent="Subadmin custom rate saved";
    await loadPricing();
  }catch(e){
    $("pricemsg").textContent=e.message;
  }
}

async function removeSubadminRate(subadminId, planId){
  try{
    await request("/admin/pricing/subadmin","POST",{ subadminId, planId, remove:true });
    $("pricemsg").textContent="Subadmin rate reset to global plan price";
    await loadPricing();
  }catch(e){
    $("pricemsg").textContent=e.message;
  }
}

async function loadTransactions(){
  try{
    const d=await request("/admin/wallet/transactions");
    $("tx").innerHTML=d.transactions.map(t=>\`
      <div class="item">
        <b>\${esc(t.username||t.subadmin_id)}</b> | \${esc(t.type)} | $\${t.amount} |
        Bal: $\${t.balance_before||0} &rarr; $\${t.balance_after||0} | \${esc(t.created_at)}<br>
        \${t.key_hint?"Key: "+esc(t.key_hint)+" | ":""}\${esc(t.note)}
      </div>
    \`).join("")||"No transactions";
  }catch(e){ $("tx").textContent=e.message; }
}

async function loadKeyTransactions(){
  try{
    const d=await request("/admin/key-transactions");
    $("tx").innerHTML=d.transactions.map(t=>\`
      <div class="item">
        <b>\${esc(t.action_type)}</b> | Key: <b>\${esc(t.key_hint)}</b> | Owner: \${esc(t.owner_name||t.owner_id)}<br>
        Plan: \${esc(t.plan_name)} ($\${t.plan_price}) | Prev Paid: $\${t.previous_paid_amount} | <b>Charged: $\${t.charged_amount}</b> | Total Paid: $\${t.new_total_paid}<br>
        Expiry: \${esc(t.previous_expires_at||"-")} &rarr; \${esc(t.new_expires_at)} | \${esc(t.created_at)}<br>
        \${esc(t.note)}
      </div>
    \`).join("")||"No key transactions";
  }catch(e){ $("tx").textContent=e.message; }
}

async function subTx(id){
  try{
    const d=await request("/admin/wallet/transactions?subadminId="+encodeURIComponent(id));
    $("tx").innerHTML=d.transactions.map(t=>\`
      <div class="item">
        \${esc(t.type)} | $\${t.amount} | Bal: $\${t.balance_before||0} &rarr; $\${t.balance_after||0} | \${esc(t.created_at)}<br>
        \${t.key_hint?"Key: "+esc(t.key_hint)+" | ":""}\${esc(t.note)}
      </div>
    \`).join("")||"No transactions";
  }catch(e){ alert(e.message); }
}

async function saveUpdate(){
  try{
    await request("/admin/update","POST",{
      versionCode:Number($("vc").value),
      versionName:$("vn").value,
      downloadUrl:$("url").value,
      message:$("umsg").value,
      forceUpdate:$("force").checked
    });
    $("upmsg").textContent="Update settings saved";
  }catch(e){ $("upmsg").textContent=e.message; }
}

async function loadUpdate(){
  try{
    const d=await request("/admin/update");
    $("vc").value=d.versionCode; $("vn").value=d.versionName;
    $("url").value=d.downloadUrl; $("umsg").value=d.message;
    $("force").checked=d.forceUpdate;
  }catch(e){ $("upmsg").textContent=e.message; }
}

async function loadFlash(){
  try{
    const d=await request("/admin/flash");
    $("flashActive").checked=!!d.active; $("flashTitle").value=d.title||"";
    $("flashMessage").value=d.message||""; $("flashFileUrl").value=d.fileUrl||"";
    $("flashFileName").value=d.fileName||"";
  }catch(e){ $("flashmsg").textContent=e.message; }
}

async function saveFlash(){
  try{
    const d=await request("/admin/flash","POST",{
      active:$("flashActive").checked, title:$("flashTitle").value,
      message:$("flashMessage").value, fileUrl:$("flashFileUrl").value,
      fileName:$("flashFileName").value
    });
    $("flashFileUrl").value=d.fileUrl||""; $("flashFileName").value=d.fileName||"";
    $("flashmsg").textContent=d.active ? (d.fileUrl ? "Flash Pop-up enabled with file download" : "Flash Pop-up enabled (message only)") : "Flash Pop-up saved (disabled)";
  }catch(e){ $("flashmsg").textContent=e.message; }
}

function clearFlashFile(){
  $("flashFileInput").value=""; $("flashFileUrl").value=""; $("flashFileName").value="";
  $("flashmsg").textContent="File cleared. Click Save Flash Pop-up to apply.";
}

async function uploadFlashFile(){
  const input=$("flashFileInput");
  const file=input?.files?.[0];
  if(!file){ $("flashmsg").textContent="Please select an .apk or .zip file first"; return; }
  $("flashmsg").textContent="Uploading to GitHub Releases...";
  try{
    const form=new FormData(); form.append("file",file);
    const r=await fetch("/api/admin/flash/upload",{ method:"POST", headers:{ "Authorization":"Bearer "+token }, body:form });
    const d=await r.json();
    if(!r.ok) throw Error(d.detail ? d.error+": "+d.detail : (d.error || "Upload failed"));
    $("flashFileUrl").value=d.fileUrl||""; $("flashFileName").value=d.fileName||"";
    $("flashmsg").textContent="Uploaded to GitHub Releases. Click Save Flash Pop-up to publish.";
  }catch(e){ $("flashmsg").textContent=e.message; }
}

async function loadWallet(){
  const d = await request("/subadmin/me");
  cachedPlans = d.plans || [];
  $("wallet").textContent = "User: " + d.username + " | Available Balance: $" + d.balance;
  $("subPlansBanner").textContent = "Server Pricing: " + (cachedPlans.map(p=>p.name+" $"+p.price).join(" • ") || "None");
  if($("splanSelect")){
    $("splanSelect").innerHTML = cachedPlans.map(p=>\`<option value="\${esc(p.id)}">\${esc(p.name)} — $\${p.price}</option>\`).join("") || "<option value=''>No plans</option>";
  }
}

async function loadSubKeys(){
  try{
    const d=await request("/subadmin/keys");
    $("subkeys").innerHTML=d.keys.map(k=>\`
      <div class="item">
        <b>\${esc(k.key_hint)}</b><br>
        Status: <b>\${esc(k.status)}</b> | Devices: \${k.device_count}/\${k.max_devices} | Paid: <b>$\${k.paid_amount||0}</b> (\${k.duration_days||0}d)<br>
        Activated: \${esc(k.activated_at||"Not yet")} | Expiry: \${esc(k.expires_at)}<br>
        <button class="secondary" onclick="copyKey('\${esc(k.key_hint)}')">COPY KEY</button>
        <button onclick="upgradeOrResetKey('\${esc(k.key_hint)}','UPGRADE',true)">UPGRADE PLAN</button>
        <button class="warn" onclick="upgradeOrResetKey('\${esc(k.key_hint)}','RESET',true)">PAID RESET</button>
        <button onclick="subKeyStatus('\${esc(k.key_hint)}',true)">Disable</button>
        <button onclick="subKeyStatus('\${esc(k.key_hint)}',false)">Enable</button>
        <button class="danger" onclick="subDeleteKey('\${esc(k.key_hint)}')">Delete</button>
      </div>
    \`).join("")||"No keys";
  }catch(e){ $("smsg").textContent=e.message; }
}

async function makeSubKey(){
  try{
    const reqId=crypto.randomUUID();
    const d=await request(
      "/subadmin/keys/generate",
      "POST",
      {...validity("s"),requestId:reqId,customSuffix:$("scustomSuffix").value.trim()}
    );
    $("snewkey").textContent=d.key+"\\nPlan: "+d.planName+"\\nExpires: "+d.expiresAt+"\\nCharged: $"+d.charged+"\\nBalance Left: $"+d.balanceAfter;
    $("smsg").textContent="Key created";
    await Promise.all([loadWallet(), loadSubKeys(), loadMyTx()]);
  }catch(e){ $("smsg").textContent=e.message; }
}

async function subKeyStatus(key,revoked){
  try{ await request("/subadmin/keys/status","POST",{key,revoked}); await loadSubKeys(); }catch(e){ alert(e.message); }
}

async function subDeleteKey(key){
  if(!confirm("Delete this key and its device records? Financial history is preserved."))return;
  try{ await request("/subadmin/keys/delete","POST",{key}); await loadSubKeys(); }catch(e){ alert(e.message); }
}

async function loadMyTx(){
  try{
    const d=await request("/subadmin/wallet/transactions");
    $("mytx").innerHTML=d.transactions.map(t=>\`
      <div class="item">
        <b>\${esc(t.type)}</b> | $\${t.amount} | Bal: $\${t.balance_before||0} &rarr; $\${t.balance_after||0} | \${esc(t.created_at)}<br>
        \${t.key_hint?"Key: "+esc(t.key_hint)+" | ":""}\${esc(t.note)}
      </div>
    \`).join("")||"No transactions";
  }catch(e){ $("mytx").textContent=e.message; }
}

async function loadMyKeyTx(){
  try{
    const d=await request("/subadmin/key-transactions");
    $("mytx").innerHTML=d.transactions.map(t=>\`
      <div class="item">
        <b>\${esc(t.action_type)}</b> | Key: <b>\${esc(t.key_hint)}</b><br>
        Plan: \${esc(t.plan_name)} ($\${t.plan_price}) | Prev Paid: $\${t.previous_paid_amount} | <b>Charged: $\${t.charged_amount}</b> | Total Paid: $\${t.new_total_paid}<br>
        \${esc(t.created_at)} — \${esc(t.note)}
      </div>
    \`).join("")||"No key transactions";
  }catch(e){ $("mytx").textContent=e.message; }
}

if(token&&role){
  start().catch(e=>{ alert(e.message); logout(); });
}
</script>
</body>
</html>`;

export default {
  async scheduled(controller, env, ctx) {
    if (!env.ULTRA_LOADER) {
      throw new Error("D1 database binding ULTRA_LOADER is missing");
    }

    const db = env.ULTRA_LOADER;
    await init(db);

    const deletedDevices = await cleanupExpiredDeviceRegistries(db, nowISO());
    console.log("Expired device registry cleanup completed:", deletedDevices);
  },

  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/" && request.method === "GET") {
      return new Response(ADMIN_PAGE, {
        headers: {
          ...CORS,
          "Content-Type": "text/html;charset=UTF-8"
        }
      });
    }

    if (path === "/api/health" && request.method === "GET") {
      return json({
        ok: true,
        service: "ULTRA LOADER API"
      });
    }

    if (!path.startsWith("/api/")) {
      return json({ error: "Not found" }, 404);
    }

    try {
      if (path === "/api/admin/login" && request.method === "POST") {
        return await adminLogin(request, env);
      }

      if (!env.SESSION_SECRET) {
        return json({ error: "SESSION_SECRET is not configured" }, 503);
      }

      if (!env.ULTRA_LOADER) {
        return json({ error: "D1 database binding ULTRA_LOADER is missing" }, 503);
      }

      const db = env.ULTRA_LOADER;
      await init(db);

      if (path === "/api/subadmin/login" && request.method === "POST") {
        return await subRoutes(request, env, db, path);
      }

      if (path === "/api/update" && request.method === "GET") {
        const current = Number(url.searchParams.get("versionCode") || 0);

        if (!Number.isInteger(current) || current < 0) {
          return json({ error: "Invalid versionCode" }, 400);
        }

        const u = await db.prepare("SELECT * FROM app_updates WHERE id=1").first();

        if (!u) {
          return json({ ok: true, updateAvailable: false, message: "No update published" });
        }

        const available = u.version_code > current && !!u.download_url;

        return json({
          ok: true,
          updateAvailable: available,
          versionCode: u.version_code,
          versionName: u.version_name,
          downloadUrl: u.download_url,
          message: u.message,
          forceUpdate: available && !!u.force_update,
          updatedAt: u.updated_at
        });
      }

      if (path === "/api/flash" && request.method === "GET") {
        const f = await db.prepare("SELECT * FROM flash_messages WHERE id=1").first();
        const active = !!f?.active;
        const fileUrl = str(f?.file_url, 1000);
        const hasFile = active && fileUrl.startsWith("https://");

        return json({
          ok: true,
          active,
          title: f?.title || "",
          message: f?.message || "",
          hasFile,
          fileUrl: hasFile ? fileUrl : "",
          fileName: hasFile ? (f?.file_name || "") : "",
          updatedAt: f?.updated_at || null
        });
      }

      if (path === "/api/pricing" && request.method === "GET") {
        return json({ ok: true, plans: await listEffectivePlans(db, null, false) });
      }

      // ==========================================
      // LICENSE ACTIVATION AND ONLINE VERIFICATION
      // ==========================================

      if (path === "/api/activate" && request.method === "POST") {
        let b;
        try {
          b = await readBody(request);
        } catch {
          return json({ ok: false, status: "ERROR", error: "Invalid JSON request" }, 400);
        }

        const key = str(b.key, 300);
        const device = str(b.deviceId, 160);

        if (!key || !device) {
          return json({ ok: false, status: "ERROR", error: "Activation key and deviceId are required" }, 400);
        }

        const master = !!env.MASTER_KEY && key === env.MASTER_KEY;
        const hash = master ? "MASTER" : await sha256(key);
        const now = nowISO();

        // Automatically transition expired keys to EXPIRED and delete ONLY expired/revoked device rows.
        await cleanupExpiredDeviceRegistries(db, now);

        let expiresAt = null;
        let limit = 99999;
        let durationDays = 0;
        let durationMinutes = 0;
        let paidAmount = 0;
        let activatedAt = now;

        if (!master) {
          const k = await db.prepare(
            "SELECT expires_at,revoked,max_devices,duration_days,duration_minutes,paid_amount,activated_at,status FROM activation_keys WHERE key_hash=?"
          ).bind(hash).first();

          if (!k) {
            return json({ ok: false, status: "INVALID", error: "Invalid activation key" }, 403);
          }

          if (Number(k.revoked) === 1) {
            return json({ ok: false, status: "REVOKED", error: "Activation key has been revoked" }, 403);
          }

          const expiryTime = Date.parse(k.expires_at);
          if (!Number.isFinite(expiryTime)) {
            return json({ ok: false, status: "ERROR", error: "Invalid license expiry data" }, 500);
          }

          if (expiryTime <= Date.now()) {
            await db.batch([
              db.prepare("UPDATE activation_keys SET status='EXPIRED' WHERE key_hash=? AND revoked=0").bind(hash),
              db.prepare("DELETE FROM devices WHERE key_hash=?").bind(hash)
            ]);
            return json({ ok: false, status: "EXPIRED", error: "Activation key expired" }, 403);
          }

          expiresAt = k.expires_at;
          limit = Math.max(1, Number(k.max_devices) || 1);
          durationDays = Number(k.duration_days || 0);
          durationMinutes = Number(k.duration_minutes || 0);
          paidAmount = Number(k.paid_amount || 0);
          activatedAt = k.activated_at || now;
        }

        // Multiple keys are allowed on the same device: check binding ONLY for (device, hash).
        const existing = await db.prepare(
          "SELECT key_hash, created_at FROM devices WHERE device_id=? AND key_hash=?"
        ).bind(device, hash).first();

        if (!existing && !master) {
          const count = await db.prepare(
            "SELECT COUNT(*) AS n FROM devices WHERE key_hash=?"
          ).bind(hash).first();

          if (Number(count?.n || 0) >= limit) {
            return json({ ok: false, status: "DEVICE_LIMIT", error: "Device limit reached" }, 403);
          }
        }

        const deviceName = str(b.deviceName || b.device_name, 120);
        const deviceModel = str(b.deviceModel || b.device_model || b.model, 120);
        const osVersion = str(b.osVersion || b.os_version || b.os, 120);
        const appVersion = str(b.appVersion || b.app_version, 80);

        const stmts = [
          db.prepare(`
            INSERT INTO devices(device_id,key_hash,last_seen,created_at,device_name,device_model,os_version,app_version)
            VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(device_id,key_hash) DO UPDATE SET
              last_seen=excluded.last_seen,
              device_name=CASE WHEN excluded.device_name<>'' THEN excluded.device_name ELSE devices.device_name END,
              device_model=CASE WHEN excluded.device_model<>'' THEN excluded.device_model ELSE devices.device_model END,
              os_version=CASE WHEN excluded.os_version<>'' THEN excluded.os_version ELSE devices.os_version END,
              app_version=CASE WHEN excluded.app_version<>'' THEN excluded.app_version ELSE devices.app_version END
          `).bind(device, hash, now, now, deviceName, deviceModel, osVersion, appVersion)
        ];

        if (!master) {
          stmts.push(
            db.prepare(`
              UPDATE activation_keys
              SET status='ACTIVE',
                  activated_at=CASE WHEN activated_at='' THEN ? ELSE activated_at END
              WHERE key_hash=?
            `).bind(now, hash)
          );
        }

        await db.batch(stmts);

        const check = await db.prepare(
          "SELECT key_hash FROM devices WHERE device_id=? AND key_hash=?"
        ).bind(device, hash).first();

        if (!check) {
          return json({ ok: false, status: "ERROR", error: "Device registration failed; retry" }, 409);
        }

        return json({
          ok: true,
          status: "VALID",
          active: true,
          expiresAt,
          activatedAt,
          durationDays,
          durationMinutes,
          paidAmount,
          licenseType: master ? "MASTER" : "STANDARD",
          deviceLimit: limit
        });
      }

      if (path.startsWith("/api/admin/")) {
        return await adminRoutes(request, env, db, path);
      }

      if (path.startsWith("/api/subadmin/")) {
        return await subRoutes(request, env, db, path);
      }

      return json({ error: "Not found" }, 404);

    } catch (error) {
      return json({
        ok: false,
        status: "ERROR",
        error: "Server error",
        detail: String(error?.message || error)
      }, 500);
    }
  }
};