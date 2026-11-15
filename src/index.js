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
function makeKey() {
  return "UL-" + [...crypto.getRandomValues(new Uint8Array(18))]
    .map(x=>x.toString(16).padStart(2,"0")).join("").toUpperCase();
}
async function passwordHash(password, salt) {
  const material = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({name:"PBKDF2",salt:enc.encode(salt),iterations:210000,hash:"SHA-256"}, material, 256);
  return [...new Uint8Array(bits)].map(x=>x.toString(16).padStart(2,"0")).join("");
}
async function readBody(req) {
  try { return await req.json(); } catch { throw new Error("Invalid JSON request"); }
}
function validDuration(body) {
  const n = Date.now();
  if (body.durationMinutes !== undefined) {
    const m = Number(body.durationMinutes);
    if (!Number.isSafeInteger(m) || m < 1 || m > 3650*1440) throw new Error("Validity must be 1 minute to 3650 days");
    return new Date(n + m*60000).toISOString();
  }
  if (body.expiresAt !== undefined) {
    const t = Date.parse(body.expiresAt);
    if (!Number.isFinite(t) || t <= n || t > n+3650*86400000) throw new Error("Expiry must be a future date within 3650 days");
    return new Date(t).toISOString();
  }
  throw new Error("Provide durationMinutes or expiresAt");
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

  // Additive migration: existing keys and devices are preserved.
  await ensureColumn(db, "activation_keys", "max_devices", "INTEGER NOT NULL DEFAULT 1");
  await ensureColumn(db, "activation_keys", "owner_id", "TEXT NOT NULL DEFAULT 'MAIN'");
  await ensureColumn(db, "activation_keys", "purchase_id", "TEXT");

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
    (SELECT MIN(d.created_at) FROM devices d WHERE d.key_hash=k.key_hash) AS activated_at
    FROM activation_keys k ${ownerId ? "WHERE k.owner_id=?" : ""}
    ORDER BY k.created_at DESC LIMIT 1000`;
  const r = ownerId ? await db.prepare(sql).bind(ownerId).all() : await db.prepare(sql).all();
  return (r.results||[]).map(k=>({
    key_hint:k.key_hint, expires_at:k.expires_at, revoked:k.revoked,
    created_at:k.created_at, activated_at:k.activated_at,
    max_devices:k.max_devices, device_count:k.device_count, owner_id:k.owner_id,
    status:k.revoked ? "REVOKED" : Date.parse(k.expires_at)>Date.now() ? "ACTIVE" : "EXPIRED"
  }));
}
async function generateKeyRecord(db, body, ownerId, maxDevices = 1, purchaseId = null) {
  const expiresAt = validDuration(body);
  const key = makeKey(), hash = await sha256(key), created = nowISO();
  await db.prepare(`INSERT INTO activation_keys(key_hash,key_hint,expires_at,revoked,created_at,max_devices,owner_id,purchase_id)
    VALUES(?,?,?,0,?,?,?,?)`).bind(hash,key,expiresAt,created,maxDevices,ownerId,purchaseId).run();
  return {key,expiresAt,keyHash:hash};
}
async function setRevoked(db, key, revoked, ownerId = null) {
  const hash = await sha256(key);
  const sql = ownerId
    ? "UPDATE activation_keys SET revoked=? WHERE key_hash=? AND owner_id=?"
    : "UPDATE activation_keys SET revoked=? WHERE key_hash=?";
  const r = ownerId ? await db.prepare(sql).bind(revoked?1:0,hash,ownerId).run()
                    : await db.prepare(sql).bind(revoked?1:0,hash).run();
  return (r.meta?.changes||0)>0;
}
async function adminRoutes(req, env, db, path) {
  const admin = await adminOnly(req,env);
  if (!admin) return json({error:"Unauthorized"},401);
  const method=req.method, b=method==="POST"?await readBody(req):{};

  if (path==="/api/admin/keys" && method==="GET") return json({keys:await listKeys(db)});
  if (path==="/api/admin/keys/generate" && method==="POST") {
    const max=Number(b.maxDevices ?? 1);
    if(!Number.isInteger(max)||max<1||max>99999) return json({error:"Device limit must be 1–99999"},400);
    const k=await generateKeyRecord(db,b,"MAIN",max);
    return json({created:1,key:k.key,expiresAt:k.expiresAt,maxDevices:max});
  }
  if(path==="/api/admin/keys/status"&&method==="POST"){
    if(!b.key||typeof b.revoked!=="boolean")return json({error:"Key and revoked boolean are required"},400);
    if(!await setRevoked(db,str(b.key,200),b.revoked))return json({error:"Activation key not found"},404);
    return json({ok:true,revoked:b.revoked});
  }
  if(path==="/api/admin/keys/delete"&&method==="POST"){
    const key=str(b.key,200); if(!key)return json({error:"Key is required"},400);
    const hash=await sha256(key);
    const found=await db.prepare("SELECT key_hash FROM activation_keys WHERE key_hash=?").bind(hash).first();
    if(!found)return json({error:"Activation key not found"},404);
    await db.batch([
      db.prepare("DELETE FROM devices WHERE key_hash=?").bind(hash),
      db.prepare("DELETE FROM activation_keys WHERE key_hash=?").bind(hash)
    ]);
    return json({ok:true,deleted:true});
  }
  if(path==="/api/admin/subadmins"&&method==="GET"){
    const r=await db.prepare(`SELECT s.id,s.username,s.balance,s.active,s.created_at,
      (SELECT COUNT(*) FROM activation_keys k WHERE k.owner_id=s.id) AS total_keys,
      (SELECT COUNT(*) FROM activation_keys k WHERE k.owner_id=s.id AND k.revoked=0 AND k.expires_at>?) AS active_keys
      FROM subadmins s ORDER BY s.created_at DESC`).bind(nowISO()).all();
    return json({subadmins:r.results||[]});
  }
  if(path==="/api/admin/subadmins"&&method==="POST"){
    const username=str(b.username,40), password=String(b.password||"");
    if(!/^[A-Za-z0-9_.-]{3,40}$/.test(username)||password.length<8||password.length>200)return json({error:"Username must be 3–40 characters; password at least 8 characters"},400);
    const id=crypto.randomUUID(),salt=b64u(crypto.getRandomValues(new Uint8Array(16))),hash=await passwordHash(password,salt),t=nowISO();
    try{await db.prepare("INSERT INTO subadmins(id,username,password_hash,salt,balance,active,created_at,updated_at) VALUES(?,?,?, ?,0,1,?,?)").bind(id,username,hash,salt,t,t).run();}
    catch{return json({error:"Username already exists"},409);}
    return json({ok:true,id,username,balance:0});
  }
  if(path==="/api/admin/subadmins/balance"&&method==="POST"){
    const id=str(b.subadminId,80), amount=Number(b.amount), type=str(b.type,10), note=str(b.note,300);
    if(!id||!Number.isSafeInteger(amount)||amount<1||amount>100000000||!["add","deduct","reset"].includes(type))return json({error:"Invalid balance operation"},400);
    const a=await db.prepare("SELECT id,balance FROM subadmins WHERE id=?").bind(id).first();
    if(!a)return json({error:"Subadmin not found"},404);
    const delta=type==="add"?amount:type==="deduct"?-amount:-Number(a.balance);
    if(Number(a.balance)+delta<0)return json({error:"Insufficient subadmin balance"},400);
    const reqId=crypto.randomUUID(),t=nowISO();
    const upd=await db.prepare("UPDATE subadmins SET balance=balance+?,updated_at=? WHERE id=? AND balance+?>=0").bind(delta,t,id,delta).run();
    if(!(upd.meta?.changes>0))return json({error:"Balance update failed; retry"},409);
    await db.prepare("INSERT INTO wallet_transactions(id,subadmin_id,amount,type,note,actor_id,request_id,created_at) VALUES(?,?,?,?,?,?,?,?)")
      .bind(crypto.randomUUID(),id,delta,type,note,"MAIN",reqId,t).run();
    const after=await db.prepare("SELECT balance FROM subadmins WHERE id=?").bind(id).first();
    return json({ok:true,balance:after.balance});
  }
  if(path==="/api/admin/subadmins/status"&&method==="POST"){
    const id=str(b.subadminId,80); if(!id||typeof b.active!=="boolean")return json({error:"Subadmin and active status required"},400);
    const r=await db.prepare("UPDATE subadmins SET active=?,updated_at=? WHERE id=?").bind(b.active?1:0,nowISO(),id).run();
    return (r.meta?.changes||0)?json({ok:true,active:b.active}):json({error:"Subadmin not found"},404);
  }
  if(path==="/api/admin/subadmins/password"&&method==="POST"){
    const id=str(b.subadminId,80),password=String(b.password||"");
    if(!id||password.length<8||password.length>200)return json({error:"New password must be at least 8 characters"},400);
    const salt=b64u(crypto.getRandomValues(new Uint8Array(16))),hash=await passwordHash(password,salt);
    const r=await db.prepare("UPDATE subadmins SET password_hash=?,salt=?,updated_at=? WHERE id=?").bind(hash,salt,nowISO(),id).run();
    return (r.meta?.changes||0)?json({ok:true}):json({error:"Subadmin not found"},404);
  }
  if(path==="/api/admin/subadmins/delete"&&method==="POST"){
    const id=str(b.subadminId,80); if(!id)return json({error:"Subadmin ID required"},400);
    const a=await db.prepare("SELECT id FROM subadmins WHERE id=?").bind(id).first();
    if(!a)return json({error:"Subadmin not found"},404);
    // Revoke, do not erase keys or device history. Preserve financial audit rows.
    await db.prepare("UPDATE activation_keys SET revoked=1 WHERE owner_id=?").bind(id).run();
    await db.prepare("UPDATE wallet_transactions SET username_snapshot=? WHERE subadmin_id=? AND username_snapshot=''")
      .bind(str((await db.prepare("SELECT username FROM subadmins WHERE id=?").bind(id).first())?.username,40),id).run();
    await db.prepare("DELETE FROM subadmins WHERE id=?").bind(id).run();
    return json({ok:true,deleted:true,keysRevoked:true,historyPreserved:true});
  }
  if(path==="/api/admin/wallet/transactions"&&method==="GET"){
    const id=str(new URL(req.url).searchParams.get("subadminId"),80);
    const r=id
      ? await db.prepare("SELECT * FROM wallet_transactions WHERE subadmin_id=? ORDER BY created_at DESC LIMIT 500").bind(id).all()
      : await db.prepare("SELECT w.*,COALESCE(s.username,w.username_snapshot,w.subadmin_id) AS username FROM wallet_transactions w LEFT JOIN subadmins s ON s.id=w.subadmin_id ORDER BY w.created_at DESC LIMIT 1000").all();
    return json({transactions:r.results||[]});
  }
  if(path==="/api/admin/settings/key-price"&&method==="GET")return json({keyPrice:await getPrice(db)});
  if(path==="/api/admin/settings/key-price"&&method==="POST"){
    const p=Number(b.keyPrice); if(!Number.isSafeInteger(p)||p<0||p>10000000)return json({error:"Key price must be 0–10000000"},400);
    await db.prepare("INSERT INTO ul_settings(setting_key,setting_value) VALUES('key_price',?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value").bind(String(p)).run();
    return json({ok:true,keyPrice:p});
  }
  if(path==="/api/admin/update"&&method==="GET"){
    const u=await db.prepare("SELECT * FROM app_updates WHERE id=1").first();
    return json({versionCode:u?.version_code||1,versionName:u?.version_name||"1.0",downloadUrl:u?.download_url||"",message:u?.message||"",forceUpdate:!!u?.force_update,updatedAt:u?.updated_at||null});
  }
  if(path==="/api/admin/update"&&method==="POST"){
    const vc=Number(b.versionCode),vn=str(b.versionName,50),download=str(b.downloadUrl,1000),message=str(b.message,2000),force=b.forceUpdate===true;
    let u;try{u=new URL(download)}catch{return json({error:"Invalid APK URL"},400)}
    if(!Number.isInteger(vc)||vc<1||!vn||u.protocol!=="https:"||u.hostname!=="github.com"||!u.pathname.includes("/releases/download/")||!u.pathname.toLowerCase().endsWith(".apk"))return json({error:"Use a valid version and direct HTTPS GitHub Releases APK URL"},400);
    const t=nowISO();
    await db.prepare(`INSERT INTO app_updates(id,version_code,version_name,download_url,message,force_update,updated_at) VALUES(1,?,?,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET version_code=excluded.version_code,version_name=excluded.version_name,download_url=excluded.download_url,message=excluded.message,force_update=excluded.force_update,updated_at=excluded.updated_at`)
      .bind(vc,vn,download,message,force?1:0,t).run();
    return json({ok:true,message:"App update settings saved",versionCode:vc,versionName:vn,forceUpdate:force});
  }
  return json({error:"Not found"},404);
}
async function subRoutes(req,env,db,path){
  if(path==="/api/subadmin/login"&&req.method==="POST"){
    const b=await readBody(req),username=str(b.username,40),password=String(b.password||"");
    const a=await db.prepare("SELECT * FROM subadmins WHERE username=?").bind(username).first();
    if(!a||!a.active||!await passwordHash(password,a.salt).then(h=>h===a.password_hash))return json({error:"Invalid username or password"},401);
    return json({ok:true,token:await tokenFor({role:"subadmin",subadminId:a.id,username:a.username},env.SESSION_SECRET)});
  }
  const s=await subOnly(req,env,db); if(!s)return json({error:"Unauthorized"},401);
  if(path==="/api/subadmin/me"&&req.method==="GET")return json({username:s.account.username,balance:s.account.balance,keyPrice:await getPrice(db)});
  if(path==="/api/subadmin/keys"&&req.method==="GET")return json({keys:await listKeys(db,s.subadminId)});
  if(path==="/api/subadmin/wallet/transactions"&&req.method==="GET"){
    const r=await db.prepare("SELECT amount,type,note,created_at FROM wallet_transactions WHERE subadmin_id=? ORDER BY created_at DESC LIMIT 500").bind(s.subadminId).all();
    return json({transactions:r.results||[]});
  }
  if(path==="/api/subadmin/keys/generate"&&req.method==="POST"){
    const b=await readBody(req),requestId=str(b.requestId,100);
    if(!requestId)return json({error:"requestId is required for safe purchase"},400);
    const prior=await db.prepare("SELECT key_hash FROM key_requests WHERE request_id=? AND subadmin_id=?").bind(requestId,s.subadminId).first();
    if(prior)return json({error:"This request was already processed. Refresh your key list; the key is not shown again."},409);
    const price=await getPrice(db);
    // Atomic conditional debit prevents spending below zero.
    const debit=await db.prepare("UPDATE subadmins SET balance=balance-?,updated_at=? WHERE id=? AND active=1 AND balance>=?")
      .bind(price,nowISO(),s.subadminId,price).run();
    if(!(debit.meta?.changes>0))return json({error:"Insufficient balance"},402);
    let generatedHash="";
    try{
      const k=await generateKeyRecord(db,b,s.subadminId,1,requestId);
      generatedHash=k.keyHash;
      await db.batch([
        db.prepare("INSERT INTO key_requests(request_id,subadmin_id,key_hash,created_at) VALUES(?,?,?,?)").bind(requestId,s.subadminId,k.keyHash,nowISO()),
        db.prepare("INSERT INTO wallet_transactions(id,subadmin_id,amount,type,note,actor_id,request_id,created_at) VALUES(?,?,?,?,?,?,?,?)")
          .bind(crypto.randomUUID(),s.subadminId,-price,"key_purchase","Activation key purchase","SUBADMIN",requestId,nowISO())
      ]);
      return json({created:1,key:k.key,expiresAt:k.expiresAt,charged:price});
    }catch(e){
      await db.prepare("DELETE FROM activation_keys WHERE key_hash=? AND owner_id=?").bind(generatedHash,s.subadminId).run().catch(()=>{});
      await db.prepare("UPDATE subadmins SET balance=balance+?,updated_at=? WHERE id=?").bind(price,nowISO(),s.subadminId).run();
      throw e;
    }
  }
  if(path==="/api/subadmin/keys/status"&&req.method==="POST"){
    const b=await readBody(req); if(!b.key||typeof b.revoked!=="boolean")return json({error:"Key and revoked boolean required"},400);
    if(!await setRevoked(db,str(b.key,200),b.revoked,s.subadminId))return json({error:"Your key was not found"},404);
    return json({ok:true,revoked:b.revoked});
  }
  if(path==="/api/subadmin/keys/delete"&&req.method==="POST"){
    const b=await readBody(req),hash=await sha256(str(b.key,200));
    const found=await db.prepare("SELECT key_hash FROM activation_keys WHERE key_hash=? AND owner_id=?").bind(hash,s.subadminId).first();
    if(!found)return json({error:"Your key was not found"},404);
    await db.batch([db.prepare("DELETE FROM devices WHERE key_hash=?").bind(hash),db.prepare("DELETE FROM activation_keys WHERE key_hash=? AND owner_id=?").bind(hash,s.subadminId)]);
    return json({ok:true,deleted:true});
  }
  return json({error:"Not found"},404);
}
const ADMIN_PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ULTRA LOADER</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#080e19;color:#edf4ff;font:15px Arial}main{max-width:1050px;margin:20px auto;padding:14px}h1{color:#58d9ff}.panel{background:#121e31;border:1px solid #293d5b;border-radius:14px;padding:16px;margin:14px 0}input,select,textarea{width:100%;padding:12px;margin:7px 0;background:#081321;color:white;border:1px solid #405574;border-radius:8px}input[type=checkbox]{width:auto}label{display:block;margin-top:9px;color:#bcd0e8}button{padding:10px 13px;margin:4px;background:#0788b5;color:white;border:0;border-radius:7px;font-weight:bold}.danger{background:#bd3548}.secondary{background:#30415b}.green{background:#16885d}.hidden{display:none}.item{padding:12px;margin:9px 0;background:#0b1728;border:1px solid #30415b;border-radius:9px;overflow-wrap:anywhere}.muted{color:#a9bad0}.msg{white-space:pre-wrap;color:#58d9ff}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:10px}
</style></head><body><main><h1>ULTRA LOADER</h1><p>Online License &amp; Update Control Center</p>
<section id="login" class="panel"><h2>Admin Login</h2><input id="apass" type="password" placeholder="Admin password"><button onclick="adminLogin()">Sign in</button><p id="loginmsg" class="msg"></p><hr><h3>Subadmin Login</h3><input id="suser" placeholder="Username"><input id="spass" type="password" placeholder="Password"><button onclick="subLogin()">Subadmin Sign in</button></section>
<section id="admin" class="hidden">
<div class="panel"><h2>Activation Key Management</h2><div class="grid"><div><label>Validity</label><select id="mode"><option value="duration">Duration</option><option value="expiry">Exact expiry</option></select><input id="days" type="number" min="0" value="30" placeholder="Days"><input id="hours" type="number" min="0" max="23" value="0" placeholder="Hours"><input id="minutes" type="number" min="0" max="59" value="0" placeholder="Minutes"><input id="expiry" type="datetime-local"></div><div><label>Device Limit (1–99999)</label><input id="limit" type="number" min="1" max="99999" value="1"><button onclick="makeAdminKey()">Generate Key</button><pre id="newkey"></pre><p id="keymsg" class="msg"></p></div></div><button class="secondary" onclick="loadKeys()">Refresh Keys</button><div id="keys"></div></div>
<div class="panel"><h2>Subadmin Management</h2><div class="grid"><input id="newuser" placeholder="New username"><input id="newpass" type="password" placeholder="Password (8+ chars)"></div><button onclick="createSub()">Create Subadmin</button><p id="submsg" class="msg"></p><div id="subs"></div></div>
<div class="panel"><h2>Key Price &amp; Wallet History</h2><label>Price per key (৳)</label><input id="price" type="number" min="0"><button onclick="savePrice()">Save Key Price</button><button class="secondary" onclick="loadTransactions()">All Transactions</button><div id="tx"></div></div>
<div class="panel"><h2>App Update Management</h2><input id="vc" type="number" placeholder="Version Code"><input id="vn" placeholder="Version Name"><input id="url" placeholder="GitHub Release APK URL"><textarea id="umsg" placeholder="Update message"></textarea><label><input id="force" type="checkbox"> Force Update</label><button onclick="saveUpdate()">Save Update</button><p id="upmsg" class="msg"></p></div>
<button class="secondary" onclick="logout()">Logout</button></section>
<section id="subdash" class="hidden"><div class="panel"><h2>Subadmin Dashboard</h2><p id="wallet" class="msg"></p><button class="secondary" onclick="loadSubKeys()">Refresh My Keys</button><div id="subkeys"></div></div><div class="panel"><h3>Generate Key</h3><select id="smode"><option value="duration">Duration</option><option value="expiry">Exact expiry</option></select><input id="sdays" type="number" min="0" value="30" placeholder="Days"><input id="shours" type="number" min="0" max="23" value="0" placeholder="Hours"><input id="sminutes" type="number" min="0" max="59" value="0" placeholder="Minutes"><input id="sexpiry" type="datetime-local"><button onclick="makeSubKey()">Generate (charged from balance)</button><pre id="snewkey"></pre><p id="smsg" class="msg"></p></div><div class="panel"><h3>My Transactions</h3><button onclick="loadMyTx()">Refresh History</button><div id="mytx"></div></div><button class="secondary" onclick="logout()">Logout</button></section>
</main><script>
let token=sessionStorage.getItem("ul_token")||"",role=sessionStorage.getItem("ul_role")||"";
const $=id=>document.getElementById(id), esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
async function request(path,method="GET",body){const r=await fetch("/api"+path,{method,headers:{"Content-Type":"application/json","Authorization":"Bearer "+token},body:body?JSON.stringify(body):undefined});let d;try{d=await r.json()}catch{throw Error("Invalid server response")}if(!r.ok)throw Error(d.error||"Request failed");return d}
function show(){ $("login").classList.add("hidden");$(role==="admin"?"admin":"subdash").classList.remove("hidden");}
async function adminLogin(){try{const r=await fetch("/api/admin/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({password:$("apass").value})});const d=await r.json();if(!r.ok)throw Error(d.error);saveSession(d.token,"admin");await start()}catch(e){$("loginmsg").textContent=e.message}}
async function subLogin(){try{const r=await fetch("/api/subadmin/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username:$("suser").value,password:$("spass").value})});const d=await r.json();if(!r.ok)throw Error(d.error);saveSession(d.token,"subadmin");await start()}catch(e){$("loginmsg").textContent=e.message}}
function saveSession(t,r){token=t;role=r;sessionStorage.setItem("ul_token",t);sessionStorage.setItem("ul_role",r)}
async function start(){show();if(role==="admin"){await Promise.all([loadKeys(),loadSubs(),loadPrice(),loadUpdate()])}else{await Promise.all([loadWallet(),loadSubKeys(),loadMyTx()])}}
function logout(){sessionStorage.clear();location.reload()}
function validity(prefix){if($(prefix+"mode").value==="expiry"){let x=new Date($(prefix+"expiry").value);if(!$(prefix+"expiry").value||x<=new Date())throw Error("Choose future expiry");return {expiresAt:x.toISOString()}}let d=Number($(prefix+"days").value),h=Number($(prefix+"hours").value),m=Number($(prefix+"minutes").value),n=d*1440+h*60+m;if(!Number.isSafeInteger(n)||n<1||n>3650*1440)throw Error("Validity must be 1 minute–3650 days");return {durationMinutes:n}}
async function makeAdminKey(){try{const d=await request("/admin/keys/generate","POST",{...validity(""),maxDevices:Number($("limit").value)});$("newkey").textContent=d.key+"\\nExpires: "+d.expiresAt;$("keymsg").textContent="Created";await loadKeys()}catch(e){$("keymsg").textContent=e.message}}
async function loadKeys(){try{const d=await request("/admin/keys");$("keys").innerHTML=d.keys.map(k=>\`<div class="item"><b>\${esc(k.key_hint)}</b><br>Status: \${esc(k.status)} | Devices: \${k.device_count}/\${k.max_devices}<br>Expiry: \${esc(k.expires_at)}<br><button class="danger" onclick="keyStatus('\${esc(k.key_hint)}',true)">Disable</button><button class="green" onclick="keyStatus('\${esc(k.key_hint)}',false)">Enable</button><button class="danger" onclick="deleteKey('\${esc(k.key_hint)}')">Permanently Delete</button></div>\`).join("")||"No keys"}catch(e){$("keymsg").textContent=e.message}}
async function keyStatus(key,revoked){try{await request("/admin/keys/status","POST",{key,revoked});await loadKeys()}catch(e){alert(e.message)}}
async function deleteKey(key){if(!confirm("Permanently delete this key and its linked device records?"))return;try{await request("/admin/keys/delete","POST",{key});await loadKeys()}catch(e){alert(e.message)}}
async function createSub(){try{await request("/admin/subadmins","POST",{username:$("newuser").value,password:$("newpass").value});$("submsg").textContent="Subadmin created";$("newpass").value="";await loadSubs()}catch(e){$("submsg").textContent=e.message}}
async function loadSubs(){try{const d=await request("/admin/subadmins");$("subs").innerHTML=d.subadmins.map(s=>\`<div class="item"><b>\${esc(s.username)}</b> | Balance ৳\${s.balance} | Keys \${s.total_keys} | \${s.active?"Active":"Disabled"}<br><button onclick="walletOp('\${s.id}','add')">Add balance</button><button onclick="walletOp('\${s.id}','deduct')">Deduct</button><button onclick="walletOp('\${s.id}','reset')">Reset balance</button><button onclick="resetPass('\${s.id}')">Reset password</button><button onclick="subStatus('\${s.id}',\${!s.active})">\${s.active?"Disable":"Enable"}</button><button class="danger" onclick="deleteSub('\${s.id}')">Permanently Delete</button><button class="secondary" onclick="subTx('\${s.id}')">Transactions</button></div>\`).join("")||"No subadmins"}catch(e){$("submsg").textContent=e.message}}
async function walletOp(id,type){let amount=type==="reset"?1:Number(prompt("Amount in Taka"));if(!Number.isInteger(amount)||amount<1)return;let note=prompt("Note (optional)")||"";try{await request("/admin/subadmins/balance","POST",{subadminId:id,type,amount,note});await loadSubs()}catch(e){alert(e.message)}}
async function resetPass(id){let p=prompt("New password (at least 8 characters)");if(!p)return;try{await request("/admin/subadmins/password","POST",{subadminId:id,password:p});alert("Password reset")}catch(e){alert(e.message)}}
async function subStatus(id,active){try{await request("/admin/subadmins/status","POST",{subadminId:id,active});await loadSubs()}catch(e){alert(e.message)}}
async function deleteSub(id){if(!confirm("Delete this Subadmin? Their keys will be revoked; key and device history retained."))return;try{await request("/admin/subadmins/delete","POST",{subadminId:id});await loadSubs()}catch(e){alert(e.message)}}
async function loadPrice(){try{$("price").value=(await request("/admin/settings/key-price")).keyPrice}catch(e){$("submsg").textContent=e.message}}
async function savePrice(){try{const d=await request("/admin/settings/key-price","POST",{keyPrice:Number($("price").value)});$("submsg").textContent="Key price set to ৳"+d.keyPrice}catch(e){$("submsg").textContent=e.message}}
async function loadTransactions(){try{const d=await request("/admin/wallet/transactions");$("tx").innerHTML=d.transactions.map(t=>\`<div class="item">\${esc(t.username||t.subadmin_id)} | \${esc(t.type)} | ৳\${t.amount} | \${esc(t.created_at)}<br>\${esc(t.note)}</div>\`).join("")||"No transactions"}catch(e){$("tx").textContent=e.message}}
async function subTx(id){try{const d=await request("/admin/wallet/transactions?subadminId="+encodeURIComponent(id));$("tx").innerHTML=d.transactions.map(t=>\`<div class="item">\${esc(t.type)} | ৳\${t.amount} | \${esc(t.created_at)}<br>\${esc(t.note)}</div>\`).join("")||"No transactions"}catch(e){alert(e.message)}}
async function saveUpdate(){try{await request("/admin/update","POST",{versionCode:Number($("vc").value),versionName:$("vn").value,downloadUrl:$("url").value,message:$("umsg").value,forceUpdate:$("force").checked});$("upmsg").textContent="Update settings saved"}catch(e){$("upmsg").textContent=e.message}}
async function loadUpdate(){try{const d=await request("/admin/update");$("vc").value=d.versionCode;$("vn").value=d.versionName;$("url").value=d.downloadUrl;$("umsg").value=d.message;$("force").checked=d.forceUpdate}catch(e){$("upmsg").textContent=e.message}}
async function loadWallet(){const d=await request("/subadmin/me");$("wallet").textContent="User: "+d.username+" | Balance: ৳"+d.balance+" | Price per key: ৳"+d.keyPrice}
async function loadSubKeys(){try{const d=await request("/subadmin/keys");$("subkeys").innerHTML=d.keys.map(k=>\`<div class="item"><b>\${esc(k.key_hint)}</b><br>\${esc(k.status)} | \${k.device_count}/\${k.max_devices} devices | \${esc(k.expires_at)}<br><button onclick="subKeyStatus('\${esc(k.key_hint)}',true)">Disable</button><button onclick="subKeyStatus('\${esc(k.key_hint)}',false)">Enable</button><button class="danger" onclick="subDeleteKey('\${esc(k.key_hint)}')">Delete</button></div>\`).join("")||"No keys"}catch(e){$("smsg").textContent=e.message}}
async function makeSubKey(){try{const reqId=crypto.randomUUID();const d=await request("/subadmin/keys/generate","POST",{...validity("s"),requestId:reqId});$("snewkey").textContent=d.key+"\\nExpires: "+d.expiresAt+"\\nCharged: ৳"+d.charged;await Promise.all([loadWallet(),loadSubKeys()])}catch(e){$("smsg").textContent=e.message}}
async function subKeyStatus(key,revoked){try{await request("/subadmin/keys/status","POST",{key,revoked});await loadSubKeys()}catch(e){alert(e.message)}}
async function subDeleteKey(key){if(!confirm("Delete this key and its device records?"))return;try{await request("/subadmin/keys/delete","POST",{key});await loadSubKeys()}catch(e){alert(e.message)}}
async function loadMyTx(){try{const d=await request("/subadmin/wallet/transactions");$("mytx").innerHTML=d.transactions.map(t=>\`<div class="item">\${esc(t.type)} | ৳\${t.amount} | \${esc(t.created_at)}<br>\${esc(t.note)}</div>\`).join("")||"No transactions"}catch(e){$("mytx").textContent=e.message}}
if(token&&role){start().catch(e=>{alert(e.message);logout()})}
</script></body></html>`;

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null,{headers:CORS});
    const url = new URL(request.url), path = url.pathname;
    if (path === "/" && request.method === "GET") return new Response(ADMIN_PAGE,{headers:{...CORS,"Content-Type":"text/html;charset=UTF-8"}});
    if (path === "/api/health" && request.method === "GET") return json({ok:true,service:"ULTRA LOADER API"});
    if (!path.startsWith("/api/")) return json({error:"Not found"},404);
    try {
      if (path === "/api/admin/login" && request.method === "POST") return await adminLogin(request,env);
      if (!env.SESSION_SECRET) return json({error:"SESSION_SECRET is not configured"},503);
      if (!env.ULTRA_LOADER) return json({error:"D1 database binding ULTRA_LOADER is missing"},503);
      const db=env.ULTRA_LOADER;
      await init(db);

      if(path==="/api/subadmin/login"&&request.method==="POST")return await subRoutes(request,env,db,path);

      if(path==="/api/update"&&request.method==="GET"){
        const current=Number(url.searchParams.get("versionCode")||0);
        if(!Number.isInteger(current)||current<0)return json({error:"Invalid versionCode"},400);
        const u=await db.prepare("SELECT * FROM app_updates WHERE id=1").first();
        if(!u)return json({ok:true,updateAvailable:false,message:"No update published"});
        const available=u.version_code>current&&!!u.download_url;
        return json({ok:true,updateAvailable:available,versionCode:u.version_code,versionName:u.version_name,downloadUrl:u.download_url,message:u.message,forceUpdate:available&&!!u.force_update,updatedAt:u.updated_at});
      }
      if(path==="/api/activate"&&request.method==="POST"){
        const b=await readBody(request),key=str(b.key,300),device=str(b.deviceId,160);
        if(!key||!device)return json({ok:false,error:"Activation key and deviceId are required"},400);
        const master=!!env.MASTER_KEY&&key===env.MASTER_KEY, hash=master?"MASTER":await sha256(key), now=nowISO();
        let expiresAt=null,limit=99999;
        if(!master){
          const k=await db.prepare("SELECT expires_at,revoked,max_devices FROM activation_keys WHERE key_hash=?").bind(hash).first();
          if(!k||k.revoked)return json({ok:false,error:"Invalid or revoked activation key"},403);
          if(Date.parse(k.expires_at)<=Date.now())return json({ok:false,error:"Activation key expired"},403);
          expiresAt=k.expires_at;limit=Math.max(1,Number(k.max_devices)||1);
        }
        const existing=await db.prepare("SELECT key_hash FROM devices WHERE device_id=?").bind(device).first();
        if(existing&&existing.key_hash!==hash)return json({ok:false,error:"This device is already linked to another key"},403);
        if(!existing&&!master){
          const count=await db.prepare("SELECT COUNT(*) AS n FROM devices WHERE key_hash=?").bind(hash).first();
          if(Number(count?.n||0)>=limit)return json({ok:false,error:"Device limit reached"},403);
        }
        // Conditional insert/update: a device ID can never be silently moved to another license.
        const result=await db.prepare(`INSERT INTO devices(device_id,key_hash,last_seen,created_at) VALUES(?,?,?,?)
          ON CONFLICT(device_id) DO UPDATE SET last_seen=excluded.last_seen
          WHERE devices.key_hash=excluded.key_hash`).bind(device,hash,now,now).run();
        const check=await db.prepare("SELECT key_hash FROM devices WHERE device_id=?").bind(device).first();
        if(!check||check.key_hash!==hash)return json({ok:false,error:"Device registration conflict; retry"},409);
        return json({ok:true,active:true,expiresAt,licenseType:master?"MASTER":"STANDARD",deviceLimit:limit});
      }
      if(path.startsWith("/api/admin/"))return await adminRoutes(request,env,db,path);
      if(path.startsWith("/api/subadmin/"))return await subRoutes(request,env,db,path);
      return json({error:"Not found"},404);
    } catch(error) {
      return json({error:"Server error",detail:String(error?.message||error)},500);
    }
  }
};
