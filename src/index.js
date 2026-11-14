
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization"
};

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" }
  });

const encoder = new TextEncoder();

function base64url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function decodeBase64url(value) {
  const normal = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normal + "=".repeat((4 - normal.length % 4) % 4);
  return Uint8Array.from(atob(padded), c => c.charCodeAt(0));
}

async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return [...new Uint8Array(digest)]
    .map(b => b.toString(16).padStart(2, "0"))
    .join("");
}

async function hmacSign(value, secret) {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return new Uint8Array(
    await crypto.subtle.sign("HMAC", key, encoder.encode(value))
  );
}

async function createToken(secret) {
  const payload = base64url(
    encoder.encode(JSON.stringify({
      role: "admin",
      exp: Date.now() + 8 * 60 * 60 * 1000
    }))
  );

  const signature = await hmacSign(payload, secret);
  return payload + "." + base64url(signature);
}

async function verifyToken(token, secret) {
  try {
    const parts = token.split(".");
    if (parts.length !== 2) return false;

    const expected = await hmacSign(parts[0], secret);
    const supplied = decodeBase64url(parts[1]);

    if (expected.length !== supplied.length) return false;

    let difference = 0;
    for (let i = 0; i < expected.length; i++) {
      difference |= expected[i] ^ supplied[i];
    }
    if (difference !== 0) return false;

    const payload = JSON.parse(
      new TextDecoder().decode(decodeBase64url(parts[0]))
    );

    return payload.role === "admin" && payload.exp > Date.now();
  } catch {
    return false;
  }
}

function generateKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(18));
  return "UL-" + [...bytes]
    .map(b => b.toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
}

async function isAdmin(request, env) {
  if (!env.SESSION_SECRET) return false;

  const header = request.headers.get("Authorization") || "";
  const token = header.replace(/^Bearer\s+/i, "");

  return token ? verifyToken(token, env.SESSION_SECRET) : false;
}

const ADMIN_PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ULTRA LOADER Admin</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#080e19;color:#edf4ff;font-family:Arial,sans-serif}
main{max-width:850px;margin:35px auto;padding:16px}
h1{color:#58d9ff}
.panel{background:#121e31;border:1px solid #293d5b;border-radius:15px;padding:20px;margin:15px 0}
input{width:100%;padding:13px;border-radius:8px;background:#081321;color:white;border:1px solid #405574;margin:10px 0}
button{padding:12px 17px;background:#0788b5;color:white;border:0;border-radius:8px;font-weight:bold;margin:5px 3px;cursor:pointer}
.secondary{background:#30415b}
.key{padding:12px;border-bottom:1px solid #30415b;overflow-wrap:anywhere}
small{color:#a9bad0}
pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#081321;padding:12px;border-radius:8px}
.hidden{display:none}
</style>
</head>
<body>
<main>
<h1>ULTRA LOADER</h1>
<p>Online License Control Center</p>

<section class="panel" id="login">
<h2>Admin Login</h2>
<input id="password" type="password" placeholder="Admin password">
<button onclick="login()">Sign in</button>
<p id="loginMsg"></p>
</section>

<section class="panel hidden" id="dashboard">
<h2>Activation Key Management</h2>
<small>Each standard key is valid for 30 days.</small>
<br><br>
<button onclick="generate()">Generate 50 Keys</button>
<button class="secondary" onclick="loadKeys()">Refresh</button>
<button class="secondary" onclick="logout()">Logout</button>
<p id="status"></p>
<pre id="newKeys" class="hidden"></pre>
<div id="keys"></div>
</section>
</main>

<script>
let token = sessionStorage.getItem("ul_token") || "";

async function api(path, method="GET", body) {
  const response = await fetch("/api" + path, {
    method,
    headers: {
      "Content-Type":"application/json",
      "Authorization":"Bearer " + token
    },
    body: body ? JSON.stringify(body) : undefined
  });

  const data = await response.json();
  if (!response.ok) throw Error(data.error || "Request failed");
  return data;
}

async function login() {
  try {
    const password = document.getElementById("password").value;
    const data = await api("/admin/login", "POST", {password});
    token = data.token;
    sessionStorage.setItem("ul_token", token);
    document.getElementById("login").classList.add("hidden");
    document.getElementById("dashboard").classList.remove("hidden");
    loadKeys();
  } catch(e) {
    document.getElementById("loginMsg").textContent = e.message;
  }
}

async function loadKeys() {
  try {
    const data = await api("/admin/keys");
    document.getElementById("status").textContent =
      "Total standard keys: " + data.keys.length;

    document.getElementById("keys").innerHTML =
      data.keys.map(k =>
        '<div class="key">' +
        k.key_hint + ' • ' + k.status +
        ' • Expires: ' + k.expires_at +
        '</div>'
      ).join("") || "No keys created yet.";
  } catch(e) {
    document.getElementById("status").textContent = e.message;
  }
}

async function generate() {
  if (!confirm("Generate 50 new activation keys?")) return;

  try {
    const data = await api("/admin/keys/generate", "POST", {});
    document.getElementById("newKeys").classList.remove("hidden");
    document.getElementById("newKeys").textContent =
      "SAVE THESE KEYS NOW. They are shown only once.\\n\\n" +
      data.keys.join("\\n");
    loadKeys();
  } catch(e) {
    document.getElementById("status").textContent = e.message;
  }
}

function logout() {
  sessionStorage.removeItem("ul_token");
  token = "";
  location.reload();
}

if (token) {
  document.getElementById("login").classList.add("hidden");
  document.getElementById("dashboard").classList.remove("hidden");
  loadKeys();
}
</script>
</body>
</html>`;

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/" && request.method === "GET") {
      return new Response(ADMIN_PAGE, {
        headers: {
          "Content-Type": "text/html;charset=UTF-8",
          ...CORS
        }
      });
    }

    if (!path.startsWith("/api/")) {
      return json({ error: "Not found" }, 404);
    }

    if (!env.ULTRA_LOADER) {
      return json({
        error: "D1 database binding ULTRA_LOADER is missing"
      }, 503);
    }

    try {
      // Public health check
      if (path === "/api/health" && request.method === "GET") {
        return json({ ok: true, service: "ULTRA LOADER API" });
      }

      // Admin login
      if (path === "/api/admin/login" && request.method === "POST") {
        if (!env.ADMIN_PASSWORD || !env.SESSION_SECRET) {
          return json({ error: "Admin secrets are not configured" }, 503);
        }

        const body = await request.json();

        if (
          typeof body.password !== "string" ||
          body.password !== env.ADMIN_PASSWORD
        ) {
          return json({ error: "Incorrect password" }, 401);
        }

        return json({
          token: await createToken(env.SESSION_SECRET)
        });
      }

      // Public activation verification
      if (path === "/api/activate" && request.method === "POST") {
        const body = await request.json();

        const activationKey = String(body.key || "").trim();
        const deviceId = String(body.deviceId || "").trim().slice(0, 160);

        if (!activationKey || !deviceId) {
          return json({
            ok: false,
            error: "Activation key and deviceId are required"
          }, 400);
        }

        const now = new Date().toISOString();
        const isMaster =
          !!env.MASTER_KEY && activationKey === env.MASTER_KEY;

        let expiresAt = null;
        let keyHash = "MASTER";

        if (!isMaster) {
          keyHash = await sha256(activationKey);

          const record = await env.ULTRA_LOADER.prepare(
            "SELECT expires_at, revoked FROM activation_keys WHERE key_hash = ?"
          ).bind(keyHash).first();

          if (!record || record.revoked) {
            return json({
              ok: false,
              error: "Invalid or revoked activation key"
            }, 403);
          }

          if (Date.parse(record.expires_at) <= Date.now()) {
            return json({
              ok: false,
              error: "Activation key expired"
            }, 403);
          }

          expiresAt = record.expires_at;
        }

        await env.ULTRA_LOADER.prepare(
          "INSERT INTO devices (device_id,key_hash,last_seen,created_at) VALUES (?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET key_hash=excluded.key_hash,last_seen=excluded.last_seen"
        ).bind(deviceId, keyHash, now, now).run();

        return json({
          ok: true,
          active: true,
          expiresAt,
          licenseType: isMaster ? "MASTER" : "STANDARD"
        });
      }

      // All remaining routes require admin login
      if (!(await isAdmin(request, env))) {
        return json({ error: "Unauthorized" }, 401);
      }

      // List standard keys
      if (path === "/api/admin/keys" && request.method === "GET") {
        const result = await env.ULTRA_LOADER.prepare(
          "SELECT key_hint, expires_at, revoked FROM activation_keys ORDER BY created_at DESC LIMIT 200"
        ).all();

        const keys = (result.results || []).map(k => ({
          key_hint: k.key_hint,
          expires_at: k.expires_at,
          status: k.revoked
            ? "REVOKED"
            : (Date.parse(k.expires_at) > Date.now() ? "ACTIVE" : "EXPIRED")
        }));

        return json({ keys });
      }

      // Generate 50 keys, each valid for 30 days
      if (path === "/api/admin/keys/generate" && request.method === "POST") {
        const keys = [];
        const now = Date.now();
        const createdAt = new Date(now).toISOString();
        const expiresAt = new Date(
          now + 30 * 24 * 60 * 60 * 1000
        ).toISOString();

        for (let i = 0; i < 50; i++) {
          const key = generateKey();
          const keyHash = await sha256(key);

          await env.ULTRA_LOADER.prepare(
            "INSERT INTO activation_keys (key_hash,key_hint,expires_at,revoked,created_at) VALUES (?,?,?,0,?)"
          ).bind(keyHash, key, expiresAt, createdAt).run();

          keys.push(key);
        }

        return json({ created: keys.length, keys });
      }

      return json({ error: "Not found" }, 404);

    } catch (error) {
      return json({
        error: "Server error",
        detail: String(error.message || error)
      }, 500);
    }
  }
};
