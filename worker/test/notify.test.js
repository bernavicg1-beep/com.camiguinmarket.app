/**
 * Tests for `POST /notify` (cross-user inbox writes through the Worker).
 *
 * Everything runs against a stubbed Firestore, so no credentials or emulator
 * are needed. Run with: npm test (inside `worker/`).
 */

import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import test from "node:test";

import worker from "../index.js";

const PROJECT = "demo-camiguin";

const PRIVATE_KEY = generateKeyPairSync("rsa", { modulusLength: 2048 })
  .privateKey.export({ type: "pkcs8", format: "pem" });

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function stub() {
  const writes = [];

  globalThis.fetch = async (url, init = {}) => {
    const href = String(url);

    if (href.includes("identitytoolkit.googleapis.com")) {
      const { idToken } = JSON.parse(init.body);
      return jsonResponse({
        users: idToken === "user-token" ? [{ localId: "u1" }] : [],
      });
    }

    if (href.includes("oauth2.googleapis.com/token")) {
      return jsonResponse({ access_token: "stub-access-token", expires_in: 3600 });
    }

    if (href.includes("/documents/notifications/")) {
      writes.push({ url: href, body: JSON.parse(init.body) });
      return jsonResponse({ name: "projects/demo/databases/(default)/documents/notifications/u2/items/x" });
    }

    throw new Error(`unexpected fetch: ${href}`);
  };

  const env = {
    FIREBASE_WEB_API_KEY: "test-web-api-key",
    FIREBASE_SERVICE_ACCOUNT: JSON.stringify({
      project_id: PROJECT,
      client_email: "worker@demo-camiguin.iam.gserviceaccount.com",
      private_key: PRIVATE_KEY,
    }),
  };

  return { env, writes };
}

async function call(env, body, { token = "user-token" } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await worker.fetch(
    new Request("https://worker.example/notify", {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    }),
    env,
    { waitUntil() {}, passThroughOnException() {} },
  );

  return { status: response.status, body: await response.json() };
}

test("notify: missing token is unauthorized", async () => {
  const { env } = stub();
  const res = await call(env, { toUid: "u2", fromUid: "u1", message: "hi" }, { token: "" });
  assert.equal(res.status, 401);
  assert.deepEqual(res.body, { error: "unauthorized" });
});

test("notify: bad token is unauthorized", async () => {
  const { env } = stub();
  const res = await call(env, { toUid: "u2", fromUid: "u1", message: "hi" }, { token: "bad-token" });
  assert.equal(res.status, 401);
  assert.deepEqual(res.body, { error: "unauthorized" });
});

test("notify: fromUid must match the authenticated user", async () => {
  const { env, writes } = stub();
  const res = await call(env, { toUid: "u2", fromUid: "u9", message: "hi" });
  assert.equal(res.status, 403);
  assert.deepEqual(res.body, { error: "forbidden" });
  assert.equal(writes.length, 0);
});

test("notify: self-notification is not allowed via this path", async () => {
  const { env, writes } = stub();
  const res = await call(env, { toUid: "u1", fromUid: "u1", message: "hi" });
  assert.equal(res.status, 400);
  assert.deepEqual(res.body, { error: "self_not_allowed" });
  assert.equal(writes.length, 0);
});

test("notify: missing fields are rejected", async () => {
  const { env, writes } = stub();
  const res = await call(env, { toUid: "u2", fromUid: "u1" });
  assert.equal(res.status, 400);
  assert.deepEqual(res.body, { error: "missing_fields" });
  assert.equal(writes.length, 0);
});

test("notify: writes the inbox doc for a cross-user recipient", async () => {
  const { env, writes } = stub();
  const res = await call(env, {
    toUid: "u2",
    fromUid: "u1",
    fromName: "Alice",
    message: "hello there",
    listingId: "l1",
    listingTitle: "Bike",
  });

  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { ok: true });
  assert.equal(writes.length, 1);
  const w = writes[0];
  assert.match(w.url, /\/documents\/notifications\/u2\/items$/);
  const fields = w.body.fields;
  assert.equal(fields.fromUid.stringValue, "u1");
  assert.equal(fields.fromName.stringValue, "Alice");
  assert.equal(fields.message.stringValue, "hello there");
  assert.equal(fields.listingId.stringValue, "l1");
  assert.equal(fields.listingTitle.stringValue, "Bike");
  assert.equal(fields.read.booleanValue, false);
});
