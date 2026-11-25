/**
 * Tests for the Telegram connect endpoints (`GET /telegram/login` and
 * `POST /telegram/verify`).
 *
 * The important rule is the hash check: the Worker recomputes
 * HMAC-SHA256 over the sorted `key=value` fields (secret key =
 * SHA-256 of the bot token) and compares it to the `hash` the Telegram
 * widget sends. A tampered payload must be rejected with `invalid_hash`.
 *
 * Run with: npm test  (inside `worker/`)
 */

import assert from "node:assert/strict";
import { createHash, createHmac } from "node:crypto";
import test from "node:test";

import worker, { resetRateLimits } from "../index.js";

const BOT_TOKEN = "123:ABC";
const WEB_API_KEY = "test-web-api-key";

function stubEnv() {
  return {
    FIREBASE_WEB_API_KEY: WEB_API_KEY,
    TELEGRAM_BOT_TOKEN: BOT_TOKEN,
    TELEGRAM_BOT_USERNAME: "TestBot",
  };
}

const CTX = { waitUntil() {}, passThroughOnException() {} };

function makeFetch() {
  globalThis.fetch = async (url, init = {}) => {
    const href = String(url);
    if (href.includes("identitytoolkit.googleapis.com")) {
      const { idToken } = JSON.parse(init.body);
      return new Response(
        JSON.stringify({ users: idToken === "user-token" ? [{ localId: "u1" }] : [] }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    }
    throw new Error(`unexpected fetch: ${href}`);
  };
}

function computeHash(payload) {
  const fields = Object.keys(payload).filter((k) => k !== "hash").sort();
  const dataCheckString = fields.map((k) => `${k}=${payload[k]}`).join("\n");
  const secret = createHash("sha256").update(BOT_TOKEN).digest();
  return createHmac("sha256", secret).update(dataCheckString).digest("hex");
}

async function call(env, { method = "POST", path = "/telegram/verify", token, body } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const init = {
    method,
    headers,
    body: method === "GET" ? undefined : (typeof body === "string" ? body : JSON.stringify(body)),
  };
  const res = await worker.fetch(
    new Request(`https://worker.example${path}`, init),
    env,
    CTX,
  );
  let json = null;
  try { json = await res.json(); } catch { json = null; }
  return { status: res.status, headers: res.headers, body: json, text: null };
}

test.beforeEach(() => resetRateLimits());

test("login page renders the widget", async () => {
  makeFetch();
  const env = stubEnv();
  const res = await worker.fetch(
    new Request("https://worker.example/telegram/login", { method: "GET" }),
    env,
    CTX,
  );
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type") || "", /text\/html/);
  const html = await res.text();
  assert.match(html, /TestBot/);
  assert.match(html, /telegram-widget\.js/);
});

test("login page rejects the wrong method", async () => {
  makeFetch();
  const env = stubEnv();
  const res = await call(env, { method: "POST", path: "/telegram/login" });
  assert.equal(res.status, 405);
});

test("verify without auth is rejected", async () => {
  makeFetch();
  const env = stubEnv();
  const res = await call(env, { token: "", body: {} });
  assert.equal(res.status, 401);
});

test("verify with a missing hash is rejected", async () => {
  makeFetch();
  const env = stubEnv();
  const res = await call(env, {
    token: "user-token",
    body: { id: 1, auth_date: Math.floor(Date.now() / 1000) },
  });
  assert.equal(res.status, 400);
  assert.deepEqual(res.body, { error: "missing_hash" });
});

test("verify accepts a correctly signed payload", async () => {
  makeFetch();
  const env = stubEnv();
  const authDate = Math.floor(Date.now() / 1000);
  const payload = { id: 123, first_name: "Ada", username: "ada", auth_date: authDate };
  payload.hash = computeHash(payload);
  const res = await call(env, { token: "user-token", body: payload });
  assert.equal(res.status, 200);
  assert.equal(res.body.ok, true);
  assert.equal(res.body.uid, "u1");
  assert.equal(res.body.username, "ada");
});

test("verify rejects a tampered payload", async () => {
  makeFetch();
  const env = stubEnv();
  const authDate = Math.floor(Date.now() / 1000);
  const payload = { id: 123, first_name: "Ada", username: "ada", auth_date: authDate };
  payload.hash = computeHash(payload);
  payload.username = "eve"; // tamper after signing
  const res = await call(env, { token: "user-token", body: payload });
  assert.equal(res.status, 403);
  assert.deepEqual(res.body, { error: "invalid_hash" });
});

test("verify rejects an expired auth_date", async () => {
  makeFetch();
  const env = stubEnv();
  const authDate = Math.floor(Date.now() / 1000) - 90000;
  const payload = { id: 123, first_name: "Ada", username: "ada", auth_date: authDate };
  payload.hash = computeHash(payload);
  const res = await call(env, { token: "user-token", body: payload });
  assert.equal(res.status, 403);
  assert.deepEqual(res.body, { error: "expired" });
});

test("verify rejects bad JSON", async () => {
  makeFetch();
  const env = stubEnv();
  const res = await call(env, { token: "user-token", body: "not json" });
  assert.equal(res.status, 400);
});

test("verify rejects the wrong method", async () => {
  makeFetch();
  const env = stubEnv();
  const res = await call(env, { method: "GET", path: "/telegram/verify", token: "user-token" });
  assert.equal(res.status, 405);
});

test("verify is rate limited after 10 calls", async () => {
  makeFetch();
  const env = stubEnv();
  const authDate = Math.floor(Date.now() / 1000);
  let last;
  for (let i = 0; i < 12; i++) {
    const payload = { id: 123, first_name: "Ada", username: "ada", auth_date: authDate };
    payload.hash = computeHash(payload);
    const res = await call(env, { token: "user-token", body: payload });
    last = res;
  }
  assert.equal(last.status, 429);
});
