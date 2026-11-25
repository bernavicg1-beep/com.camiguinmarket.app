/**
 * Tests that `POST /telegram/verify` persists the verified Telegram chat id
 * to `users/{uid}` when a service account is configured.
 *
 * Run with: npm test  (inside `worker/`)
 */

import assert from "node:assert/strict";
import { createHash, createHmac, generateKeyPairSync } from "node:crypto";
import test from "node:test";

import worker, { resetRateLimits } from "../index.js";

const BOT_TOKEN = "123:ABC";
const WEB_API_KEY = "test-web-api-key";
const PRIVATE_KEY = generateKeyPairSync("rsa", { modulusLength: 2048 })
  .privateKey.export({ type: "pkcs8", format: "pem" });

function computeHash(payload) {
  const fields = Object.keys(payload).filter((k) => k !== "hash").sort();
  const dataCheckString = fields.map((k) => `${k}=${payload[k]}`).join("\n");
  const secret = createHash("sha256").update(BOT_TOKEN).digest();
  return createHmac("sha256", secret).update(dataCheckString).digest("hex");
}

const CTX = { waitUntil() {}, passThroughOnException() {} };

test.beforeEach(() => resetRateLimits());

test("verify persists telegramChatId when service account present", async () => {
  const patches = [];
  globalThis.fetch = async (url, init = {}) => {
    const href = String(url);
    if (href.includes("identitytoolkit.googleapis.com")) {
      const { idToken } = JSON.parse(init.body);
      return new Response(
        JSON.stringify({ users: idToken === "user-token" ? [{ localId: "u1" }] : [] }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    }
    if (href.includes("oauth2.googleapis.com/token")) {
      return new Response(JSON.stringify({ access_token: "stub-access", expires_in: 3600 }), {
        status: 200, headers: { "Content-Type": "application/json" },
      });
    }
    if (href.includes("/documents/users/u1")) {
      // PATCH target
      patches.push({ url: href, body: JSON.parse(init.body) });
      return new Response(JSON.stringify({ name: "users/u1", fields: {} }), {
        status: 200, headers: { "Content-Type": "application/json" },
      });
    }
    throw new Error(`unexpected fetch: ${href}`);
  };

  const env = {
    FIREBASE_WEB_API_KEY: WEB_API_KEY,
    TELEGRAM_BOT_TOKEN: BOT_TOKEN,
    FIREBASE_SERVICE_ACCOUNT: JSON.stringify({
      project_id: "demo-camiguin",
      client_email: "worker@demo-camiguin.iam.gserviceaccount.com",
      private_key: PRIVATE_KEY,
    }),
  };

  const authDate = Math.floor(Date.now() / 1000);
  const payload = { id: 42, first_name: "Ada", username: "ada", auth_date: authDate };
  payload.hash = computeHash(payload);

  const res = await worker.fetch(
    new Request("https://worker.example/telegram/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer user-token" },
      body: JSON.stringify(payload),
    }),
    env,
    CTX,
  );

  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.ok, true);
  assert.equal(body.chatId, 42);

  // Give the (awaited) persistence a chance — it's awaited in handler, so done.
  assert.equal(patches.length, 1);
  assert.match(patches[0].url, /updateMask\.fieldPaths=telegramChatId/);
  assert.match(patches[0].url, /updateMask\.fieldPaths=telegramUsername/);
  assert.equal(patches[0].body.fields.telegramChatId.integerValue, "42");
  assert.equal(patches[0].body.fields.telegramUsername.stringValue, "ada");
});

test("verify skips persistence when no service account", async () => {
  const patches = [];
  globalThis.fetch = async (url, init = {}) => {
    const href = String(url);
    if (href.includes("identitytoolkit.googleapis.com")) {
      const { idToken } = JSON.parse(init.body);
      return new Response(
        JSON.stringify({ users: idToken === "user-token" ? [{ localId: "u1" }] : [] }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    }
    patches.push(href);
    throw new Error(`unexpected fetch: ${href}`);
  };

  const env = {
    FIREBASE_WEB_API_KEY: WEB_API_KEY,
    TELEGRAM_BOT_TOKEN: BOT_TOKEN,
  };

  const authDate = Math.floor(Date.now() / 1000);
  const payload = { id: 42, first_name: "Ada", username: "ada", auth_date: authDate };
  payload.hash = computeHash(payload);

  const res = await worker.fetch(
    new Request("https://worker.example/telegram/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer user-token" },
      body: JSON.stringify(payload),
    }),
    env,
    CTX,
  );

  assert.equal(res.status, 200);
  // Only identitytoolkit called; no oauth2/patch.
  assert.equal(patches.length, 0);
});
