/**
 * Tests for `POST /delete-images`, the endpoint the app calls when a seller
 * deletes a listing, replaces its photos, or deletes their account.
 *
 * The important rule is the admin branch: a normal user may only delete photos
 * inside their own `listing_images/<uid>/` folder, and moderator access is
 * granted only by an `admins/{uid}` document found through the service account.
 *
 * Run with: npm test  (inside `worker/`)
 */

import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import test from "node:test";

import worker from "../index.js";

const PHOTO = "https://worker.example/i";
const URL_TOKEN = { "user-token": "u1", "admin-token": "admin1" };

// A throwaway key so the Worker's RS256 service-account JWT signing works.
const PRIVATE_KEY = generateKeyPairSync("rsa", { modulusLength: 2048 })
  .privateKey.export({ type: "pkcs8", format: "pem" });

function jsonResponse(body) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

/** Stub the Google endpoints the Worker talks to, plus the R2 binding. */
function stub({ admins = [], serviceAccount = true } = {}) {
  const deleted = [];
  const adminLookups = [];

  globalThis.fetch = async (url, init = {}) => {
    const href = String(url);

    if (href.includes("identitytoolkit.googleapis.com")) {
      const { idToken } = JSON.parse(init.body);
      const uid = URL_TOKEN[idToken];
      return jsonResponse({ users: uid ? [{ localId: uid }] : [] });
    }

    if (href.includes("oauth2.googleapis.com/token")) {
      return jsonResponse({ access_token: "stub-access-token", expires_in: 3600 });
    }

    if (href.includes("/documents/admins/")) {
      const uid = href.split("/documents/admins/")[1];
      adminLookups.push(uid);
      if (admins.includes(uid)) return jsonResponse({ name: `admins/${uid}`, fields: {} });
      return new Response('{"error":{"code":404}}', { status: 404 });
    }

    throw new Error(`unexpected fetch: ${href}`);
  };

  const env = {
    FIREBASE_WEB_API_KEY: "test-web-api-key",
    IMAGES: {
      async delete(key) {
        deleted.push(key);
      },
    },
  };

  if (serviceAccount) {
    env.FIREBASE_SERVICE_ACCOUNT = JSON.stringify({
      project_id: "demo-camiguin",
      client_email: "worker@demo-camiguin.iam.gserviceaccount.com",
      private_key: PRIVATE_KEY,
    });
  }

  return { env, deleted, adminLookups };
}

async function call(env, body, { token = "user-token", method = "POST" } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await worker.fetch(
    new Request("https://worker.example/delete-images", {
      method,
      headers,
      body:
        method === "POST"
          ? typeof body === "string"
            ? body
            : JSON.stringify(body)
          : undefined,
    }),
    env,
    { waitUntil() {}, passThroughOnException() {} },
  );

  return { status: response.status, body: await response.json() };
}

test("no ID token is rejected", async () => {
  const { env, deleted } = stub();
  const res = await call(env, { urls: [`${PHOTO}/listing_images/u1/a.jpg`] }, { token: "" });

  assert.equal(res.status, 401);
  assert.deepEqual(res.body, { error: "unauthorized" });
  assert.deepEqual(deleted, []);
});

test("a seller deletes only their own photos", async () => {
  const { env, deleted, adminLookups } = stub();

  const res = await call(env, {
    urls: [
      `${PHOTO}/listing_images/u1/mine.jpg`,
      `${PHOTO}/listing_images/u2/someone-else.jpg`,
      "https://elsewhere.example/not-r2.jpg",
    ],
  });

  assert.deepEqual(res.body, { deleted: 1, skipped: 2 });
  assert.deepEqual(deleted, ["listing_images/u1/mine.jpg"]);
  // The admin lookup ran for the foreign key, and found nothing.
  assert.deepEqual(adminLookups, ["u1"]);
});

test("an admin may delete another seller's photos", async () => {
  const { env, deleted, adminLookups } = stub({ admins: ["admin1"] });

  const res = await call(env, { urls: [`${PHOTO}/listing_images/u2/someone-else.jpg`] }, {
    token: "admin-token",
  });

  assert.deepEqual(res.body, { deleted: 1, skipped: 0 });
  assert.deepEqual(deleted, ["listing_images/u2/someone-else.jpg"]);
  assert.deepEqual(adminLookups, ["admin1"]);
});

test("without the service account nobody is treated as an admin", async () => {
  const { env, deleted } = stub({ admins: ["admin1"], serviceAccount: false });

  const res = await call(env, { urls: [`${PHOTO}/listing_images/u2/someone-else.jpg`] }, {
    token: "admin-token",
  });

  assert.deepEqual(res.body, { deleted: 0, skipped: 1 });
  assert.deepEqual(deleted, []);
});

test("the admin lookup is reused for every foreign key in one call", async () => {
  const { env, adminLookups } = stub({ admins: ["admin1"] });

  await call(
    env,
    {
      urls: [
        `${PHOTO}/listing_images/u2/a.jpg`,
        `${PHOTO}/listing_images/u3/b.jpg`,
      ],
    },
    { token: "admin-token" },
  );

  assert.deepEqual(adminLookups, ["admin1"]);
});

test("one call deletes at most 30 photos", async () => {
  const { env, deleted } = stub();
  const urls = Array.from({ length: 35 }, (_, i) => `${PHOTO}/listing_images/u1/p${i}.jpg`);

  const res = await call(env, { urls });

  assert.deepEqual(res.body, { deleted: 30, skipped: 0 });
  assert.equal(deleted.length, 30);
});

test("invalid JSON and the wrong method are refused", async () => {
  const { env } = stub();

  assert.equal((await call(env, "not json")).status, 400);
  assert.equal((await call(env, { urls: [] }, { method: "GET" })).status, 405);
});
