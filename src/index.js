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
body{
  margin:0;
  background:#080e19;
  color:#edf4ff;
  font-family:Arial,sans-serif
}
main{
  max-width:900px;
  margin:25px auto;
  padding:14px
}
h1{color:#58d9ff}
.panel{
  background:#121e31;
  border:1px solid #293d5b;
  border-radius:15px;
  padding:18px;
  margin:15px 0
}
input{
  width:100%;
  padding:13px;
  border-radius:8px;
  background:#081321;
  color:white;
  border:1px solid #405574;
  margin:9px 0
}
button{
  padding:11px 15px;
  background:#0788b5;
  color:white;
  border:0;
  border-radius:8px;
  font-weight:bold;
  margin:5px 3px;
  cursor:pointer
}
.secondary{background:#30415b}
.danger{background:#bd3548}
.success{background:#16885d}
.hidden{display:none}
.key{
  background:#0b1728;
  border:1px solid #30415b;
  border-radius:10px;
  padding:14px;
  margin:12px 0;
  overflow-wrap:anywhere
}
.keycode{
  color:#58d9ff;
  font-weight:bold;
  font-size:15px;
  margin-bottom:8px
}
small,.muted{color:#a9bad0}
.status{
  font-weight:bold;
  margin:8px 0
}
pre{
  white-space:pre-wrap;
  overflow-wrap:anywhere;
  background:#081321;
  padding:13px;
  border-radius:8px
}
hr{
  border:0;
  border-top:1px solid #30415b;
  margin:12px 0
}
</style>
</head>

<body>
<main>

<h1>ULTRA LOADER</h1>
<p>Online License Control Center</p>

<section class="panel" id="login">
  <h2>Admin Login</h2>
  <input id="password" type="password" placeholder="Admin password">
  <button id="loginBtn">Sign in</button>
  <p id="loginMsg"></p>
</section>

<section class="panel hidden" id="dashboard">

  <h2>Activation Key Management</h2>

  <p class="muted">Create one activation key with your chosen validity.</p>

  <label for="days">Validity in days</label>
  <input id="days" type="number" min="1" max="3650" value="30">

  <button id="generateBtn">Generate One Key</button>
  <button class="secondary" id="refreshBtn">Refresh</button>
  <button class="secondary" id="logoutBtn">Logout</button>

  <p id="status"></p>

  <div id="newKeyBox" class="hidden">
    <h3>New Activation Key</h3>
    <p class="muted">Save this key now. Copy it before leaving this page.</p>
    <pre id="newKey"></pre>
    <button id="copyKeyBtn">Copy Key</button>
  </div>

  <hr>

  <h3>All Activation Keys</h3>
  <div id="keys">Loading...</div>

</section>

</main>

<script>
let token = sessionStorage.getItem("ul_token") || "";

const el = id => document.getElementById(id);

function escapeHTML(value) {
  return String(value ?? "").replace(/[&<>"']/g, char => ({
    "&":"&amp;",
    "<":"&lt;",
    ">":"&gt;",
    '"':"&quot;",
    "'":"&#39;"
  }[char]));
}

function formatDate(value) {
  if (!value) return "Not activated";

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Unknown";

  return date.toLocaleString();
}

function remainingTime(value) {
  const difference = new Date(value).getTime() - Date.now();

  if (!Number.isFinite(difference) || difference <= 0) {
    return "Expired";
  }

  const days = Math.floor(difference / 86400000);
  const hours = Math.floor((difference % 86400000) / 3600000);
  const minutes = Math.floor((difference % 3600000) / 60000);

  return days + " days, " + hours + " hours, " + minutes + " minutes";
}

async function api(path, method = "GET", body) {
  const response = await fetch("/api" + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      "Authorization": "Bearer " + token
    },
    body: body ? JSON.stringify(body) : undefined
  });

  const data = await response.json();

  if (!response.ok) {
    throw Error(data.error || "Request failed");
  }

  return data;
}

function showDashboard() {
  el("login").classList.add("hidden");
  el("dashboard").classList.remove("hidden");
}

async function login() {
  try {
    const password = el("password").value;

    const response = await fetch("/api/admin/login", {
      method:"POST",
      headers:{"Content-Type":"application/json"},
      body:JSON.stringify({password})
    });

    const data = await response.json();

    if (!response.ok) {
      throw Error(data.error || "Login failed");
    }

    token = data.token;
    sessionStorage.setItem("ul_token", token);

    el("loginMsg").textContent = "";
    showDashboard();
    await loadKeys();

  } catch(error) {
    el("loginMsg").textContent = error.message;
  }
}

async function loadKeys() {
  try {
    el("status").textContent = "Loading keys...";

    const data = await api("/admin/keys");

    el("status").textContent =
      "Total activation keys: " + data.keys.length;

    const container = el("keys");
    container.innerHTML = "";

    if (!data.keys.length) {
      container.textContent = "No activation keys created yet.";
      return;
    }

    data.keys.forEach(k => {
      const key = document.createElement("div");
      key.className = "key";

      let action = "";

      if (k.status === "ACTIVE") {
        action =
          '<button class="danger" data-action="disable" data-key="' +
          escapeHTML(k.key_hint) + '">Disable Key</button>';
      } else if (
        k.status === "REVOKED" &&
        new Date(k.expires_at).getTime() > Date.now()
      ) {
        action =
          '<button class="success" data-action="enable" data-key="' +
          escapeHTML(k.key_hint) + '">Reactivate Key</button>';
      }

      const statusColor =
        k.status === "ACTIVE" ? "#45d69a" :
        k.status === "REVOKED" ? "#ff7373" : "#ffbf69";

      key.innerHTML =
        '<div class="keycode">' + escapeHTML(k.key_hint) + '</div>' +
        '<div class="status" style="color:' + statusColor + '">' +
        'Status: ' + escapeHTML(k.status) + '</div>' +
        '<div>Remaining: ' +
        escapeHTML(remainingTime(k.expires_at)) + '</div>' +
        '<div>Created: ' +
        escapeHTML(formatDate(k.created_at)) + '</div>' +
        '<div>Activated: ' +
        escapeHTML(formatDate(k.activated_at)) + '</div>' +
        '<div>Expires: ' +
        escapeHTML(formatDate(k.expires_at)) + '</div>' +
        action;

      container.appendChild(key);
    });

  } catch(error) {
    el("status").textContent = error.message;
  }
}

async function generate() {
  const days = Number(el("days").value);

  if (!Number.isInteger(days) || days < 1 || days > 3650) {
    el("status").textContent =
      "Enter a validity between 1 and 3650 days.";
    return;
  }

  if (!confirm("Generate ONE activation key for " + days + " days?")) {
    return;
  }

  try {
    el("generateBtn").disabled = true;

    const data = await api(
      "/admin/keys/generate",
      "POST",
      {days}
    );

    el("newKeyBox").classList.remove("hidden");
    el("newKey").textContent =
      data.key + "\\n\\nValidity: " + data.days +
      " days\\nExpires: " + formatDate(data.expiresAt);

    await loadKeys();

  } catch(error) {
    el("status").textContent = error.message;
  } finally {
    el("generateBtn").disabled = false;
  }
}

async function changeKeyStatus(key, revoked) {
  const action = revoked ? "disable" : "reactivate";

  if (!confirm("Are you sure you want to " + action + " this key?")) {
    return;
  }

  try {
    await api("/admin/keys/status", "POST", {
      key,
      revoked
    });

    el("status").textContent =
      revoked ? "Key disabled successfully." :
      "Key reactivated successfully.";

    await loadKeys();

  } catch(error) {
    el("status").textContent = error.message;
  }
}

el("loginBtn").addEventListener("click", login);
el("generateBtn").addEventListener("click", generate);
el("refreshBtn").addEventListener("click", loadKeys);

el("logoutBtn").addEventListener("click", () => {
  sessionStorage.removeItem("ul_token");
  token = "";
  location.reload();
});

el("copyKeyBtn").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(
      el("newKey").textContent.split("\\n")[0]
    );
    el("status").textContent = "Key copied.";
  } catch(error) {
    el("status").textContent = "Copy failed. Please copy manually.";
  }
});

el("keys").addEventListener("click", event => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;

  const key = button.getAttribute("data-key");
  const action = button.getAttribute("data-action");

  changeKeyStatus(key, action === "disable");
});

if (token) {
  showDashboard();
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
      // Health Check
      if (path === "/api/health" && request.method === "GET") {
        return json({
          ok: true,
          service: "ULTRA LOADER API"
        });
      }

      // Admin Login
      if (path === "/api/admin/login" && request.method === "POST") {
        if (!env.ADMIN_PASSWORD || !env.SESSION_SECRET) {
          return json({
            error: "Admin secrets are not configured"
          }, 503);
        }

        const body = await request.json();

        if (
          typeof body.password !== "string" ||
          body.password !== env.ADMIN_PASSWORD
        ) {
          return json({
            error: "Incorrect password"
          }, 401);
        }

        return json({
          token: await createToken(env.SESSION_SECRET)
        });
      }

      // Public Activation Verification
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

      // All remaining API routes require Admin Login
      if (!(await isAdmin(request, env))) {
        return json({ error: "Unauthorized" }, 401);
      }

      // List Keys
      if (path === "/api/admin/keys" && request.method === "GET") {
        const result = await env.ULTRA_LOADER.prepare(
          `SELECT
             activation_keys.key_hint,
             activation_keys.expires_at,
             activation_keys.revoked,
             activation_keys.created_at,
             (
               SELECT MIN(devices.created_at)
               FROM devices
               WHERE devices.key_hash = activation_keys.key_hash
             ) AS activated_at
           FROM activation_keys
           ORDER BY activation_keys.created_at DESC
           LIMIT 500`
        ).all();

        const keys = (result.results || []).map(k => {
          const expired = Date.parse(k.expires_at) <= Date.now();

          return {
            key_hint: k.key_hint,
            expires_at: k.expires_at,
            created_at: k.created_at,
            activated_at: k.activated_at,
            status: k.revoked
              ? "REVOKED"
              : expired ? "EXPIRED" : "ACTIVE"
          };
        });

        return json({ keys });
      }

      // Generate ONE Key with Custom Validity
      if (
        path === "/api/admin/keys/generate" &&
        request.method === "POST"
      ) {
        const body = await request.json();
        const days = Number(body.days);

        if (
          !Number.isInteger(days) ||
          days < 1 ||
          days > 3650
        ) {
          return json({
            error: "Validity must be between 1 and 3650 days"
          }, 400);
        }

        const now = Date.now();
        const createdAt = new Date(now).toISOString();

        const expiresAt = new Date(
          now + days * 24 * 60 * 60 * 1000
        ).toISOString();

        const key = generateKey();
        const keyHash = await sha256(key);

        await env.ULTRA_LOADER.prepare(
          "INSERT INTO activation_keys (key_hash,key_hint,expires_at,revoked,created_at) VALUES (?,?,?,0,?)"
        ).bind(keyHash, key, expiresAt, createdAt).run();

        return json({
          created: 1,
          key,
          days,
          expiresAt
        });
      }

      // Disable or Reactivate a Key
      if (
        path === "/api/admin/keys/status" &&
        request.method === "POST"
      ) {
        const body = await request.json();

        const key = String(body.key || "").trim();

        if (!key || typeof body.revoked !== "boolean") {
          return json({
            error: "A key and boolean revoked value are required"
          }, 400);
        }

        const keyHash = await sha256(key);

        const result = await env.ULTRA_LOADER.prepare(
          "UPDATE activation_keys SET revoked = ? WHERE key_hash = ?"
        ).bind(body.revoked ? 1 : 0, keyHash).run();

        if (!result.meta || result.meta.changes < 1) {
          return json({
            error: "Activation key not found"
          }, 404);
        }

        return json({
          ok: true,
          revoked: body.revoked
        });
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
