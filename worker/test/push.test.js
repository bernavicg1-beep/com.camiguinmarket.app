/**
 * Tests for `POST /push` saved-search notifications.
 *
 * The important rule is that a saved-search push is rooted at the
 * authenticated user: the recipient is that user, and the saved search must
 * exist under `savedSearches/{uid}/items/{id}`. No client-supplied recipient
 * is accepted.
 *
 * Everything runs against a stubbed Firestore/FCM, so no credentials or
 * emulator are needed. Run with: npm test  (inside `worker/`)
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

function stub({ hasSavedSearch = true } = {}) {
  const tokenLookups = [];
  const savedLookups = [];
  let fcmMessage = null;

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

    if (href.includes("/documents/savedSearches/")) {
      const path = href.split("/documents/")[1];
      savedLookups.push(path);
      if (hasSavedSearch && path === "savedSearches/u1/items/s1") {
        return jsonResponse({
          name: path,
          fields: { name: { stringValue: "Bike" } },
        });
      }
      return new Response('{"error":{"code":404}}', { status: 404 });
    }

    if (href.includes("/documents/users/u1/tokens")) {
      tokenLookups.push(href);
      return jsonResponse({
        documents: [{ fields: { token: { stringValue: "device-token" } } }],
      });
    }

    if (href.includes("fcm.googleapis.com")) {
      fcmMessage = JSON.parse(init.body);
      return jsonResponse({ name: "projects/demo/messages/1" });
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

  return { env, tokenLookups, savedLookups, fcmMessage: () => fcmMessage };
}

async function call(env, body, { token = "user-token" } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await worker.fetch(
    new Request("https://worker.example/push", {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    }),
    env,
    { waitUntil() {}, passThroughOnException() {} },
  );

  return { status: response.status, body: await response.json() };
}

test("a saved-search push goes to the authenticated user", async () => {
  const { env, tokenLookups, savedLookups, fcmMessage } = stub();

  const res = await call(env, {
    kind: "saved",
    savedSearchId: "s1",
    text: '🔔 3 new listings for "Bike"',
  });

  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { sent: 1 });
  assert.deepEqual(savedLookups, ["savedSearches/u1/items/s1"]);
  assert.equal(tokenLookups.length, 1);
  assert.match(tokenLookups[0], /\/documents\/users\/u1\/tokens$/);

  const message = fcmMessage().message;
  assert.equal(message.token, "device-token");
  assert.equal(message.notification.title, "Saved search 🔔");
  assert.equal(message.notification.body, '🔔 3 new listings for "Bike"');
  assert.deepEqual(message.data, { savedSearchId: "s1", type: "saved_search" });
});

test("a saved-search push without an id is refused", async () => {
  const { env, tokenLookups } = stub();

  const res = await call(env, { kind: "saved", text: "new listings" });

  assert.equal(res.status, 400);
  assert.deepEqual(res.body, { error: "missing_savedSearchId" });
  assert.deepEqual(tokenLookups, []);
});

test("a saved-search push for someone else's or missing search is refused", async () => {
  const { env, tokenLookups } = stub({ hasSavedSearch: false });

  const res = await call(env, {
    kind: "saved",
    savedSearchId: "s1",
    text: "new listings",
  });

  assert.equal(res.status, 404);
  assert.deepEqual(res.body, { error: "not_found" });
  assert.deepEqual(tokenLookups, []);
});
