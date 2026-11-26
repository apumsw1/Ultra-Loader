import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/index.js";

function createD1Adapter(sqlite) {
  function makePrepared(sql, boundParams = []) {
    return {
      sql,
      boundParams,
      bind(...args) {
        return makePrepared(sql, args);
      },
      async first() {
        const stmt = sqlite.prepare(sql);
        const row = stmt.get(...boundParams);
        return row === undefined ? null : row;
      },
      async all() {
        const stmt = sqlite.prepare(sql);
        const results = stmt.all(...boundParams);
        return { success: true, results };
      },
      async run() {
        const stmt = sqlite.prepare(sql);
        const info = stmt.run(...boundParams);
        return {
          success: true,
          meta: {
            changes: Number(info.changes || 0),
            last_row_id: Number(info.lastInsertRowid || 0)
          }
        };
      }
    };
  }

  return {
    prepare(sql) {
      return makePrepared(sql);
    },
    async batch(statements) {
      const results = [];
      sqlite.exec("BEGIN IMMEDIATE");
      try {
        for (const s of statements) {
          const r = await s.run();
          results.push(r);
        }
        sqlite.exec("COMMIT");
        return results;
      } catch (err) {
        try {
          sqlite.exec("ROLLBACK");
        } catch {}
        throw err;
      }
    }
  };
}

function makeEnv(sqlite) {
  return {
    ULTRA_LOADER: createD1Adapter(sqlite),
    ADMIN_PASSWORD: "admin-secret-password",
    SESSION_SECRET: "session-secret-for-tests-1234567890",
    MASTER_KEY: "MASTER-TEST-KEY"
  };
}

async function api(env, path, { method = "GET", body, token } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const req = new Request(`https://worker.test${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined
  });
  const res = await worker.fetch(req, env);
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = text;
  }
  return { status: res.status, data };
}

test("1. Safe legacy migration preserves existing keys, subadmins, balances and upgrades devices PK", async () => {
  const sqlite = new DatabaseSync(":memory:");
  // Simulate old production schema before multi-key / dynamic pricing migration.
  sqlite.exec(`
    CREATE TABLE activation_keys (
      key_hash TEXT PRIMARY KEY,
      key_hint TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      revoked INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL,
      max_devices INTEGER NOT NULL DEFAULT 1,
      owner_id TEXT NOT NULL DEFAULT 'MAIN',
      purchase_id TEXT
    );
    CREATE TABLE devices (
      device_id TEXT PRIMARY KEY,
      key_hash TEXT NOT NULL,
      last_seen TEXT NOT NULL,
      created_at TEXT NOT NULL
    );
    CREATE TABLE subadmins (
      id TEXT PRIMARY KEY,
      username TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      salt TEXT NOT NULL,
      balance INTEGER NOT NULL DEFAULT 0,
      active INTEGER NOT NULL DEFAULT 1,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );
    CREATE TABLE wallet_transactions (
      id TEXT PRIMARY KEY,
      subadmin_id TEXT NOT NULL,
      amount INTEGER NOT NULL,
      type TEXT NOT NULL,
      note TEXT NOT NULL DEFAULT '',
      actor_id TEXT NOT NULL,
      request_id TEXT UNIQUE,
      created_at TEXT NOT NULL
    );
  `);

  const futureExpiry = new Date(Date.now() + 7 * 86400000).toISOString();
  const createdAt = new Date().toISOString();
  sqlite.prepare(`
    INSERT INTO subadmins(id,username,password_hash,salt,balance,active,created_at,updated_at)
    VALUES('sub_legacy','legacyuser','hash','salt',750,1,?,?)
  `).run(createdAt, createdAt);
  sqlite.prepare(`
    INSERT INTO wallet_transactions(id,subadmin_id,amount,type,note,actor_id,request_id,created_at)
    VALUES('wtx_1','sub_legacy',-200,'key_purchase','Legacy key','SUBADMIN','req_legacy_1',?)
  `).run(createdAt);
  sqlite.prepare(`
    INSERT INTO activation_keys(key_hash,key_hint,expires_at,revoked,created_at,max_devices,owner_id,purchase_id)
    VALUES('hash_legacy_key','UL-LEGACY1',?,0,?,1,'sub_legacy','req_legacy_1')
  `).run(futureExpiry, createdAt);
  sqlite.prepare(`
    INSERT INTO devices(device_id,key_hash,last_seen,created_at)
    VALUES('dev-legacy','hash_legacy_key',?,?)
  `).run(createdAt, createdAt);

  const env = makeEnv(sqlite);
  const pricingRes = await api(env, "/api/pricing");
  assert.equal(pricingRes.status, 200);
  assert.equal(pricingRes.data.plans.length, 6);

  // Verify legacy key paid_amount & device binding were preserved and PK upgraded to composite (device_id, key_hash).
  const keyRow = sqlite.prepare("SELECT paid_amount,status,activated_at,duration_days FROM activation_keys WHERE key_hash='hash_legacy_key'").get();
  assert.equal(keyRow.paid_amount, 200);
  assert.equal(keyRow.status, "ACTIVE");
  assert.equal(keyRow.duration_days, 7);
  assert.ok(keyRow.activated_at);

  const pkInfo = sqlite.prepare("PRAGMA table_info(devices)").all().filter(c => c.pk > 0);
  assert.equal(pkInfo.length, 2);

  const legacySub = sqlite.prepare("SELECT balance FROM subadmins WHERE id='sub_legacy'").get();
  assert.equal(legacySub.balance, 750);
  const legacyDev = sqlite.prepare("SELECT * FROM devices WHERE device_id='dev-legacy' AND key_hash='hash_legacy_key'").get();
  assert.ok(legacyDev);
});

test("2-9. Complete Incremental Pricing, Balance Validation, Idempotency, Reset, Dynamic Rates, Multi-Key & Expiry", async () => {
  const sqlite = new DatabaseSync(":memory:");
  const env = makeEnv(sqlite);

  // Admin Login
  const adminLogin = await api(env, "/api/admin/login", {
    method: "POST",
    body: { password: "admin-secret-password" }
  });
  assert.equal(adminLogin.status, 200);
  const adminToken = adminLogin.data.token;

  // Create two subadmins
  const sub1Res = await api(env, "/api/admin/subadmins", {
    method: "POST",
    token: adminToken,
    body: { username: "reseller1", password: "Password123!" }
  });
  assert.equal(sub1Res.status, 200);
  const sub1Id = sub1Res.data.id;

  const sub2Res = await api(env, "/api/admin/subadmins", {
    method: "POST",
    token: adminToken,
    body: { username: "reseller2", password: "Password123!" }
  });
  assert.equal(sub2Res.status, 200);
  const sub2Id = sub2Res.data.id;

  // Add balance with idempotency check
  const balReqId = "wallet-topup-1";
  const topup1 = await api(env, "/api/admin/subadmins/balance", {
    method: "POST",
    token: adminToken,
    body: { subadminId: sub1Id, type: "add", amount: 1000, note: "Initial deposit", requestId: balReqId }
  });
  assert.equal(topup1.status, 200);
  assert.equal(topup1.data.balance, 1000);

  const topupDup = await api(env, "/api/admin/subadmins/balance", {
    method: "POST",
    token: adminToken,
    body: { subadminId: sub1Id, type: "add", amount: 1000, note: "Initial deposit", requestId: balReqId }
  });
  assert.equal(topupDup.status, 200);
  assert.equal(topupDup.data.idempotentReplay, true);

  const sub1Login = await api(env, "/api/subadmin/login", {
    method: "POST",
    body: { username: "reseller1", password: "Password123!" }
  });
  const sub1Token = sub1Login.data.token;

  // 2a. New key purchase deducts full 7d price ($200) even if client passes fake price=1
  const gen1 = await api(env, "/api/subadmin/keys/generate", {
    method: "POST",
    token: sub1Token,
    body: { planId: "plan_7d", price: 1, charged: 1, balance: 99999, requestId: "gen-key-1" }
  });
  assert.equal(gen1.status, 200);
  assert.equal(gen1.data.charged, 200);
  assert.equal(gen1.data.paidAmount, 200);
  assert.equal(gen1.data.balanceAfter, 800);
  const key7d = gen1.data.key;

  // 2b. Second new key does NOT receive discount from existing key (still charges full $50 for 1d)
  const gen2 = await api(env, "/api/subadmin/keys/generate", {
    method: "POST",
    token: sub1Token,
    body: { planId: "plan_1d", requestId: "gen-key-2" }
  });
  assert.equal(gen2.status, 200);
  assert.equal(gen2.data.charged, 50);
  assert.equal(gen2.data.balanceAfter, 750);
  const key1d = gen2.data.key;

  // 2c. Duplicate requestId on generate returns cached response and does NOT double-deduct
  const gen2Dup = await api(env, "/api/subadmin/keys/generate", {
    method: "POST",
    token: sub1Token,
    body: { planId: "plan_1d", requestId: "gen-key-2" }
  });
  assert.equal(gen2Dup.status, 200);
  assert.equal(gen2Dup.data.idempotentReplay, true);
  assert.equal((await api(env, "/api/subadmin/me", { token: sub1Token })).data.balance, 750);

  // 3. Incremental duration upgrades:
  // 7d (200) -> 15d (300) charges ONLY 100
  const up1 = await api(env, "/api/subadmin/keys/upgrade", {
    method: "POST",
    token: sub1Token,
    body: { key: key7d, planId: "plan_15d", requestId: "upg-1" }
  });
  assert.equal(up1.status, 200);
  assert.equal(up1.data.previouslyPaid, 200);
  assert.equal(up1.data.planPrice, 300);
  assert.equal(up1.data.additionalCharge, 100);
  assert.equal(up1.data.totalPaid, 300);
  assert.equal(up1.data.balanceAfter, 650);

  // Duplicate upgrade requestId does NOT deduct again
  const up1Dup = await api(env, "/api/subadmin/keys/upgrade", {
    method: "POST",
    token: sub1Token,
    body: { key: key7d, planId: "plan_15d", requestId: "upg-1" }
  });
  assert.equal(up1Dup.status, 200);
  assert.equal(up1Dup.data.idempotentReplay, true);
  assert.equal((await api(env, "/api/subadmin/me", { token: sub1Token })).data.balance, 650);

  // 15d (300) -> 30d (400) charges ONLY 100
  const up2 = await api(env, "/api/subadmin/keys/upgrade", {
    method: "POST",
    token: sub1Token,
    body: { key: key7d, planId: "plan_30d", requestId: "upg-2" }
  });
  assert.equal(up2.status, 200);
  assert.equal(up2.data.additionalCharge, 100);
  assert.equal(up2.data.totalPaid, 400);
  assert.equal(up2.data.balanceAfter, 550);

  // Direct 7d (200) -> 30d (400) charges ONLY 200
  const gen7dDirect = await api(env, "/api/subadmin/keys/generate", {
    method: "POST",
    token: sub1Token,
    body: { planId: "plan_7d", requestId: "gen-key-7d-direct" }
  });
  assert.equal(gen7dDirect.data.balanceAfter, 350);
  const upDirect = await api(env, "/api/subadmin/keys/upgrade", {
    method: "POST",
    token: sub1Token,
    body: { key: gen7dDirect.data.key, planId: "plan_30d", requestId: "upg-direct" }
  });
  assert.equal(upDirect.status, 200);
  assert.equal(upDirect.data.additionalCharge, 200);
  assert.equal(upDirect.data.balanceAfter, 150);

  // 1d (50) -> 7d (200) charges ONLY 150
  const up1dTo7d = await api(env, "/api/subadmin/keys/upgrade", {
    method: "POST",
    token: sub1Token,
    body: { key: key1d, planId: "plan_7d", requestId: "upg-1d-7d" }
  });
  assert.equal(up1dTo7d.status, 200);
  assert.equal(up1dTo7d.data.additionalCharge, 150);
  assert.equal(up1dTo7d.data.totalPaid, 200);
  assert.equal(up1dTo7d.data.balanceAfter, 0);

  // 4. Insufficient balance rejection leaves key, expiry, device & balance completely intact
  const prevKey1dExpiry = up1dTo7d.data.expiresAt;
  const failUpgrade = await api(env, "/api/subadmin/keys/upgrade", {
    method: "POST",
    token: sub1Token,
    body: { key: key1d, planId: "plan_15d", requestId: "upg-fail-lowbal" }
  });
  assert.equal(failUpgrade.status, 402);
  assert.equal(failUpgrade.data.requiredAmount, 100);
  assert.equal(failUpgrade.data.availableBalance, 0);

  const subKeysAfterFail = await api(env, "/api/subadmin/keys", { token: sub1Token });
  const key1dRow = subKeysAfterFail.data.keys.find(k => k.key_hint === key1d);
  assert.equal(key1dRow.paid_amount, 200);
  assert.equal(key1dRow.duration_days, 7);
  assert.equal(key1dRow.expires_at, prevKey1dExpiry);

  // 5. Dynamic Pricing: Admin edits 7d price -> $250, adds 60d custom plan ($650), disables 2d plan, sets sub2 custom 7d rate ($160)
  await api(env, "/api/admin/pricing/plan", {
    method: "POST",
    token: adminToken,
    body: { id: "plan_7d", name: "7 Days", durationDays: 7, price: 250, active: true }
  });
  const customPlanRes = await api(env, "/api/admin/pricing/plan", {
    method: "POST",
    token: adminToken,
    body: { name: "60 Days VIP", durationDays: 60, price: 650, active: true }
  });
  assert.equal(customPlanRes.status, 200);
  const custom60dId = customPlanRes.data.plan.id;

  await api(env, "/api/admin/pricing/plan/status", {
    method: "POST",
    token: adminToken,
    body: { id: "plan_2d", active: false }
  });

  await api(env, "/api/admin/pricing/subadmin", {
    method: "POST",
    token: adminToken,
    body: { subadminId: sub2Id, planId: "plan_7d", price: 160, active: true }
  });

  // Topup reseller1 ($200) and verify historical paid_amount ($200) on key1d is preserved despite 7d plan changing to $250!
  await api(env, "/api/admin/subadmins/balance", {
    method: "POST",
    token: adminToken,
    body: { subadminId: sub1Id, type: "add", amount: 200, requestId: "topup-reseller1-200" }
  });
  const keyTxRes = await api(env, `/api/admin/key-transactions?key=${encodeURIComponent(key1d)}`, { token: adminToken });
  assert.equal(keyTxRes.data.transactions.length, 2); // PURCHASE (50) + UPGRADE (150)

  // Upgrade key1d (prev paid $200) -> 15d ($300) charges ONLY $100 (original paid $200 preserved!)
  const preview1dTo15d = await api(
    env,
    `/api/subadmin/pricing/preview?key=${encodeURIComponent(key1d)}&planId=plan_15d`,
    { token: sub1Token }
  );
  assert.equal(preview1dTo15d.data.previouslyPaid, 200);
  assert.equal(preview1dTo15d.data.additionalCharge, 100);

  // Verify subadmin2 custom rate ($160 for 7d) vs disabled plan rejection
  await api(env, "/api/admin/subadmins/balance", {
    method: "POST",
    token: adminToken,
    body: { subadminId: sub2Id, type: "add", amount: 500, requestId: "topup-reseller2-500" }
  });
  const sub2Token = (await api(env, "/api/subadmin/login", {
    method: "POST",
    body: { username: "reseller2", password: "Password123!" }
  })).data.token;

  const sub2DisabledPlan = await api(env, "/api/subadmin/keys/generate", {
    method: "POST",
    token: sub2Token,
    body: { planId: "plan_2d", requestId: "sub2-disabled-plan" }
  });
  assert.equal(sub2DisabledPlan.status, 400);

  const sub2CustomGen = await api(env, "/api/subadmin/keys/generate", {
    method: "POST",
    token: sub2Token,
    body: { planId: "plan_7d", requestId: "sub2-custom-7d" }
  });
  assert.equal(sub2CustomGen.status, 200);
  assert.equal(sub2CustomGen.data.charged, 160);
  assert.equal(sub2CustomGen.data.balanceAfter, 340);

  // Custom 60d plan upgrade test (paid 160 -> 60d 650 requires 490; sub2 has 340 -> 402!)
  const sub2Fail60 = await api(env, "/api/subadmin/keys/upgrade", {
    method: "POST",
    token: sub2Token,
    body: { key: sub2CustomGen.data.key, planId: custom60dId, requestId: "sub2-60d-fail" }
  });
  assert.equal(sub2Fail60.status, 402);

  // 6. Multiple Keys on the Same Device + Paid Reset + Automatic Expiry Isolation
  const actKeyA = await api(env, "/api/activate", {
    method: "POST",
    body: {
      key: key1d,
      deviceId: "ANDROID-DEVICE-99",
      deviceName: "Pixel 8",
      deviceModel: "Pixel 8",
      osVersion: "Android 15",
      appVersion: "3.1"
    }
  });
  assert.equal(actKeyA.status, 200);
  assert.equal(actKeyA.data.status, "VALID");

  const actKeyB = await api(env, "/api/activate", {
    method: "POST",
    body: {
      key: key7d,
      deviceId: "ANDROID-DEVICE-99",
      deviceName: "Pixel 8",
      deviceModel: "Pixel 8",
      osVersion: "Android 15",
      appVersion: "3.1"
    }
  });
  assert.equal(actKeyB.status, 200);
  assert.equal(actKeyB.data.status, "VALID");

  // Both device registrations exist independently for ANDROID-DEVICE-99
  const boundCount = sqlite.prepare("SELECT COUNT(*) AS n FROM devices WHERE device_id='ANDROID-DEVICE-99'").get().n;
  assert.equal(boundCount, 2);

  // Paid Reset on active key1d to 15d ($300): charges ONLY $100 diff, clears ONLY key1d device row (key7d stays bound!),
  // and extends from old expiry rather than losing remaining time.
  const beforeResetExpiryMs = Date.parse(prevKey1dExpiry);
  const paidResetActive = await api(env, "/api/subadmin/keys/reset", {
    method: "POST",
    token: sub1Token,
    body: { key: key1d, planId: "plan_15d", requestId: "paid-reset-active-1" }
  });
  assert.equal(paidResetActive.status, 200);
  assert.equal(paidResetActive.data.additionalCharge, 100);
  assert.equal(paidResetActive.data.devicesReset, true);
  assert.ok(Date.parse(paidResetActive.data.expiresAt) > beforeResetExpiryMs + 7 * 86400000);

  // key1d's device binding cleared, key7d's device binding STILL exists!
  const remainingAfterReset = sqlite.prepare("SELECT COUNT(*) AS n FROM devices WHERE device_id='ANDROID-DEVICE-99'").get().n;
  assert.equal(remainingAfterReset, 1);

  // Re-bind key1d to ANDROID-DEVICE-99, then expire key1d in DB and verify automatic expiry cleanup
  await api(env, "/api/activate", {
    method: "POST",
    body: { key: key1d, deviceId: "ANDROID-DEVICE-99" }
  });
  assert.equal(sqlite.prepare("SELECT COUNT(*) AS n FROM devices WHERE device_id='ANDROID-DEVICE-99'").get().n, 2);

  const pastIso = new Date(Date.now() - 60000).toISOString();
  sqlite.prepare("UPDATE activation_keys SET expires_at=? WHERE key_hint=?").run(pastIso, key1d);

  // Trying to activate expired key1d transitions it to EXPIRED, blocks access, and removes ONLY key1d's device row
  const expiredResp = await api(env, "/api/activate", {
    method: "POST",
    body: { key: key1d, deviceId: "ANDROID-DEVICE-99" }
  });
  assert.equal(expiredResp.status, 403);
  assert.equal(expiredResp.data.status, "EXPIRED");

  const expiredKeyStatus = sqlite.prepare("SELECT status FROM activation_keys WHERE key_hint=?").get(key1d).status;
  assert.equal(expiredKeyStatus, "EXPIRED");

  // key7d on the SAME device is completely unaffected and still VALID!
  assert.equal(sqlite.prepare("SELECT COUNT(*) AS n FROM devices WHERE device_id='ANDROID-DEVICE-99'").get().n, 1);
  const keyBStillValid = await api(env, "/api/activate", {
    method: "POST",
    body: { key: key7d, deviceId: "ANDROID-DEVICE-99" }
  });
  assert.equal(keyBStillValid.status, 200);
  assert.equal(keyBStillValid.data.status, "VALID");

  // Renewing the EXPIRED key1d with Paid Reset computes new validity from NOW, restores ACTIVE status,
  // and charges 0 if already paid >= target plan price (300 - 300 = 0).
  const renewExpired = await api(env, "/api/subadmin/keys/reset", {
    method: "POST",
    token: sub1Token,
    body: { key: key1d, planId: "plan_15d", requestId: "renew-expired-1d" }
  });
  assert.equal(renewExpired.status, 200);
  assert.equal(renewExpired.data.additionalCharge, 0);
  assert.ok(Date.parse(renewExpired.data.expiresAt) > Date.now() + 14 * 86400000);

  // Historical ledger records for key1d remain intact and append-only (PURCHASE, UPGRADE, RESET x2 = 4 records)
  const fullKey1dHistory = await api(env, `/api/admin/key-transactions?key=${encodeURIComponent(key1d)}`, { token: adminToken });
  assert.equal(fullKey1dHistory.data.transactions.length, 4);
});
