const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization"
};
const enc = new TextEncoder();
const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" }
});
const nowISO = () => new Date().toISOString();
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
  return [...new Uint8Array(d)].map(x => x.toString(16).padStart(2, "0")).join("");
}
async function hmac(s, secret) {
  const k = await crypto.subtle.importKey("raw", enc.encode(secret), {name:"HMAC",hash:"SHA-256"}, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, enc.encode(s)));
}
async function tokenFor(payload, secret) {
  const p = b64u(enc.encode(JSON.stringify({...payload, exp:Date.now()+8*60*60*1000})));
  return p + "." + b64u(await hmac(p, secret));
}
async function verifyToken(token, secret) {
  try {
    const a = token.split(".");
    if (a.length !== 2) return null;
    const expected = await hmac(a[0], secret), got = unb64u(a[1]);
    if (expected.length !== got.length) return null;
    let diff = 0;
    for (let i=0;i<expected.length;i++) diff |= expected[i] ^ got[i];
    if (diff) return null;
    const p = JSON.parse(new TextDecoder().decode(unb64u(a[0])));
    return p.exp > Date.now() ? p : null;
  } catch { return null; }
}
function normalizeKey(value) {
  let key = str(value, 200).toUpperCase();
  if (key.startsWith("UL-")) key = key.slice(3);
  if (!/^[A-Z0-9_-]{3,190}$/.test(key)) throw new Error("Key must contain 3–190 letters, numbers, _ or -");
  return "UL-" + key;
}
function makeKey(customValue = "") {
  if (str(customValue, 200)) return normalizeKey(customValue);
  return "UL-" + [...crypto.getRandomValues(new Uint8Array(18))]
    .map(x=>x.toString(16).padStart(2,"0")).join("").toUpperCase();
}

// FIX: Cloudflare Workers PBKDF2 iteration limit is 100000.
async function passwordHash(password, salt) {
  const material = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({
    name:"PBKDF2",
    salt:enc.encode(salt),
    iterations:100000,
    hash:"SHA-256"
  }, material, 256);
  return [...new Uint8Array(bits)].map(x=>x.toString(16).padStart(2,"0")).join("");
}

async function readBody(req) {
  try { return await req.json(); } catch { throw new Error("Invalid JSON request"); }
}
function durationMinutes(body) {
  const n = Date.now();
  if (body.durationMinutes !== undefined) {
    const m = Number(body.durationMinutes);
    if (!Number.isSafeInteger(m) || m < 1 || m > 3650*1440) throw new Error("Validity must be 1 minute to 3650 days");
    return m;
  }
  if (body.expiresAt !== undefined) {
    const t = Date.parse(body.expiresAt);
    if (!Number.isFinite(t) || t <= n || t > n+3650*86400000) throw new Error("Expiry must be a future date within 3650 days");
    return Math.max(1, Math.ceil((t-n)/60000));
  }
  throw new Error("Provide durationMinutes or expiresAt");
}
function validDuration(body) {
  if (body.expiresAt !== undefined) {
    const t = Date.parse(body.expiresAt);
    const now = Date.now();
    if (!Number.isFinite(t) || t <= now || t > now+3650*86400000) throw new Error("Expiry must be a future date within 3650 days");
    return new Date(t).toISOString();
  }
  return new Date(Date.now() + durationMinutes(body)*60000).toISOString();
}
function priceForDuration(body) {
  const days = Math.ceil(durationMinutes(body)/1440);
  if (days <= 1) return 50;
  if (days === 2) return 100;
  if (days === 3) return 150;
  if (days <= 7) return 200;
  if (days <= 15) return 300;
  if (days <= 30) return 400;
  return Math.ceil(days/30)*400;
}
async function columns(db, table) {
  const r = await db.prepare(`PRAGMA table_info(${table})`).all();
  return new Set((r.results||[]).map(x=>x.name));
}
async function ensureColumn(db, table, name, definition) {
  const c = await columns(db, table);
  if (!c.has(name)) await db.prepare(`ALTER TABLE ${table} ADD COLUMN ${name} ${definition}`).run();
}
async function init(db) {
  await db.prepare(`CREATE TABLE IF NOT EXISTS activation_keys (
    key_hash TEXT PRIMARY KEY, key_hint TEXT NOT NULL, expires_at TEXT NOT NULL,
    revoked INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL
  )`).run();
  await db.prepare(`CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY, key_hash TEXT NOT NULL, last_seen TEXT NOT NULL, created_at TEXT NOT NULL
  )`).run();
  await db.prepare(`CREATE TABLE IF NOT EXISTS app_updates (
    id INTEGER PRIMARY KEY CHECK(id=1), version_code INTEGER NOT NULL DEFAULT 1,
    version_name TEXT NOT NULL DEFAULT '1.0', download_url TEXT NOT NULL DEFAULT '',
    message TEXT NOT NULL DEFAULT '', force_update INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL
  )`).run();

  await ensureColumn(db, "activation_keys", "max_devices", "INTEGER NOT NULL DEFAULT 1");
  await ensureColumn(db, "activation_keys", "owner_id", "TEXT NOT NULL DEFAULT 'MAIN'");
  await ensureColumn(db, "activation_keys", "purchase_id", "TEXT");
  await ensureColumn(db, "devices", "manufacturer", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "devices", "device_model", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "devices", "android_version", "TEXT NOT NULL DEFAULT ''");
  await ensureColumn(db, "devices", "app_version", "TEXT NOT NULL DEFAULT ''");

  await db.prepare(`CREATE TABLE IF NOT EXISTS subadmins (
    id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
    salt TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
  )`).run();
  await db.prepare(`CREATE TABLE IF NOT EXISTS wallet_transactions (
    id TEXT PRIMARY KEY, subadmin_id TEXT NOT NULL, amount INTEGER NOT NULL,
    type TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', actor_id TEXT NOT NULL,
    request_id TEXT UNIQUE, created_at TEXT NOT NULL
  )`).run();
  await ensureColumn(db, "wallet_transactions", "username_snapshot", "TEXT NOT NULL DEFAULT ''");
  await db.prepare(`CREATE TABLE IF NOT EXISTS ul_settings (
    setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL
  )`).run();
  await db.prepare(`INSERT OR IGNORE INTO ul_settings(setting_key,setting_value) VALUES('key_price','200')`).run();
  await db.prepare(`CREATE TABLE IF NOT EXISTS key_requests (
    request_id TEXT PRIMARY KEY, subadmin_id TEXT NOT NULL, key_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
  )`).run();
  await db.prepare(`CREATE INDEX IF NOT EXISTS idx_keys_owner ON activation_keys(owner_id)`).run();
  await db.prepare(`CREATE INDEX IF NOT EXISTS idx_devices_key ON devices(key_hash)`).run();
  await db.prepare(`CREATE INDEX IF NOT EXISTS idx_wallet_sub ON wallet_transactions(subadmin_id,created_at)`).run();
}
async function auth(req, env) {
  const h = req.headers.get("Authorization") || "";
  const t = h.replace(/^Bearer\s+/i, "");
  return t ? verifyToken(t, env.SESSION_SECRET) : null;
}
async function getPrice(db) {
  const r = await db.prepare("SELECT setting_value FROM ul_settings WHERE setting_key='key_price'").first();
  return Math.max(0, Number(r?.setting_value ?? 200));
}
async function adminOnly(req, env) {
  const p = await auth(req, env);
  return p?.role === "admin" ? p : null;
}
async function subOnly(req, env, db) {
  const p = await auth(req, env);
  if (p?.role !== "subadmin" || !p.subadminId) return null;
  const a = await db.prepare("SELECT id,username,balance,active FROM subadmins WHERE id=?").bind(p.subadminId).first();
  return a?.active ? {...p, account:a} : null;
}
async function adminLogin(req, env) {
  const b = await readBody(req);
  if (!env.ADMIN_PASSWORD || !env.SESSION_SECRET) return json({error:"Admin secrets are not configured"},503);
  if (typeof b.password !== "string" || b.password !== env.ADMIN_PASSWORD) return json({error:"Incorrect password"},401);
  return json({ok:true,token:await tokenFor({role:"admin"},env.SESSION_SECRET)});
}
async function listKeys(db, ownerId = null) {
  const sql = `SELECT k.key_hint,k.expires_at,k.revoked,k.created_at,k.max_devices,k.owner_id,
    (SELECT COUNT(*) FROM devices d WHERE d.key_hash=k.key_hash) AS device_count,
    (SELECT MIN(d.created_at) FROM devices d WHERE d.key_hash=k.key_hash) AS activated_at,
    (SELECT d.device_id FROM devices d WHERE d.key_hash=k.key_hash ORDER BY d.last_seen DESC LIMIT 1) AS device_id,
    (SELECT d.manufacturer FROM devices d WHERE d.key_hash=k.key_hash ORDER BY d.last_seen DESC LIMIT 1) AS manufacturer,
    (SELECT d.device_model FROM devices d WHERE d.key_hash=k.key_hash ORDER BY d.last_seen DESC LIMIT 1) AS device_model,
    (SELECT d.android_version FROM devices d WHERE d.key_hash=k.key_hash ORDER BY d.last_seen DESC LIMIT 1) AS android_version,
    (SELECT d.app_version FROM devices d WHERE d.key_hash=k.key_hash ORDER BY d.last_seen DESC LIMIT 1) AS app_version
    FROM activation_keys k ${ownerId ? "WHERE k.owner_id=?" : ""}
    ORDER BY k.created_at DESC LIMIT 1000`;
  const r = ownerId ? await db.prepare(sql).bind(ownerId).all() : await db.prepare(sql).all();
  return (r.results||[]).map(k=>({
    key_hint:k.key_hint, expires_at:k.expires_at, revoked:k.revoked,
    created_at:k.created_at, activated_at:k.activated_at,
    max_devices:k.max_devices, device_count:k.device_count, owner_id:k.owner_id,
    device_id:k.device_id, manufacturer:k.manufacturer, device_model:k.device_model,
    android_version:k.android_version, app_version:k.app_version,
    status:k.revoked ? "REVOKED" : Date.parse(k.expires_at)>Date.now() ? "ACTIVE" : "EXPIRED"
  }));
}
async function generateKeyRecord(db, body, ownerId, maxDevices = 1, purchaseId = null) {
  const expiresAt = validDuration(body);
  const key = makeKey(body.customKey || ""), hash = await sha256(key), created = nowISO();
  await db.prepare(`INSERT INTO activation_keys(key_hash,key_hint,expires_at,revoked,created_at,max_devices,owner_id,purchase_id)
    VALUES(?,?,?,0,?,?,?,?)`).bind(hash,key,expiresAt,created,maxDevices,ownerId,purchaseId).run();
  return {key,expiresAt,keyHash:hash};
}
async function setRevoked(db, key, revoked, ownerId = null) {
  const hash = await sha256(normalizeKey(key));
  const sql = ownerId
    ? "UPDATE activation_keys SET revoked=? WHERE key_hash=? AND owner_id=?"
    : "UPDATE activation_keys SET revoked=? WHERE key_hash=?";
  const r = ownerId ? await db.prepare(sql).bind(revoked?1:0,hash,ownerId).run()
                    : await db.prepare(sql).bind(revoked?1:0,hash).run();
  return (r.meta?.changes||0)>0;
}