/**
 * Tests for the cart, rating and moderation kinds on `POST /push`.
 *
 * Everything runs against a stubbed Firestore/FCM. Run with: npm test.
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

function stub({ listingSeller = "seller", admin = false } = {}) {
  const fcm = [];
  const tokenLookups = [];
  globalThis.fetch = async (url, init = {}) => {
    const href = String(url);

    if (href.includes("identitytoolkit.googleapis.com")) {
      const { idToken } = JSON.parse(init.body);
      const map = { "buyer-token": "buyer", "admin-token": "admin" };
      const localId = map[idToken];
      return jsonResponse({ users: localId ? [{ localId }] : [] });
    }

    if (href.includes("oauth2.googleapis.com/token")) {
      return jsonResponse({ access_token: "stub-access-token", expires_in: 3600 });
    }

    if (href.includes("/documents/listings/")) {
      return jsonResponse({
        name: "listings/l1",
        fields: {
          sellerId: { stringValue: listingSeller },
          title: { stringValue: "Bike" },
        },
      });
    }

    if (href.includes("/documents/ratings/")) {
      return jsonResponse({
        name: "ratings/o1",
        fields: {
          buyerId: { stringValue: "buyer" },
          sellerId: { stringValue: "seller" },
          buyerName: { stringValue: "Bea" },
          rating: { integerValue: "5" },
        },
      });
    }

    if (href.includes("/documents/admins/")) {
      return admin
        ? jsonResponse({ name: "admins/admin", fields: {} })
        : new Response('{"error":{"code":404}}', { status: 404 });
    }

    if (href.includes("/documents/users/") && href.endsWith("/tokens")) {
      tokenLookups.push(href.split("/documents/")[1]);
      return jsonResponse({ documents: [{ fields: { token: { stringValue: "device-token" } } }] });
    }

    if (href.includes("fcm.googleapis.com")) {
      fcm.push(JSON.parse(init.body));
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
  return { env, fcm, tokenLookups };
}

async function call(env, body, token) {
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

test("cart: notifies the listing seller", async () => {
  const { env, fcm } = stub();
  const res = await call(env, { kind: "cart", listingId: "l1" }, "buyer-token");
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { sent: 1 });
  assert.equal(fcm.length, 1);
  assert.equal(fcm[0].message.data.type, "cart");
  assert.equal(fcm[0].message.data.listingId, "l1");
});

test("cart: a seller adding their own listing is a no-op", async () => {
  const { env, fcm } = stub({ listingSeller: "buyer" });
  const res = await call(env, { kind: "cart", listingId: "l1" }, "buyer-token");
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { sent: 0 });
  assert.equal(fcm.length, 0);
});

test("rating: the buyer notifies the seller", async () => {
  const { env, fcm } = stub();
  const res = await call(env, { kind: "rating", orderId: "o1" }, "buyer-token");
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { sent: 1 });
  assert.equal(fcm[0].message.data.type, "rating");
});

test("rating: a non-buyer is refused", async () => {
  const { env, fcm } = stub();
  const res = await call(env, { kind: "rating", orderId: "o1" }, "admin-token");
  assert.equal(res.status, 403);
  assert.deepEqual(res.body, { error: "forbidden" });
  assert.equal(fcm.length, 0);
});

test("moderation: an admin notifies the seller", async () => {
  const { env, fcm } = stub({ admin: true });
  const res = await call(
    env,
    { kind: "moderation", sellerId: "seller", title: "Bike" },
    "admin-token",
  );
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { sent: 1 });
  assert.equal(fcm[0].message.data.type, "moderation");
});

test("moderation: a non-admin is refused", async () => {
  const { env, fcm } = stub({ admin: false });
  const res = await call(
    env,
    { kind: "moderation", sellerId: "seller", title: "Bike" },
    "buyer-token",
  );
  assert.equal(res.status, 403);
  assert.deepEqual(res.body, { error: "forbidden" });
  assert.equal(fcm.length, 0);
});

test("moderation: a missing seller is refused", async () => {
  const { env } = stub({ admin: true });
  const res = await call(env, { kind: "moderation", title: "Bike" }, "admin-token");
  assert.equal(res.status, 400);
  assert.deepEqual(res.body, { error: "missing_sellerId" });
});

test("positive path: each kind is delivered to the seller's device", async () => {
  const cart = stub();
  const cartRes = await call(cart.env, { kind: "cart", listingId: "l1" }, "buyer-token");
  assert.deepEqual(cartRes.body, { sent: 1 });
  assert.deepEqual(cart.tokenLookups, ["users/seller/tokens"]);
  assert.equal(cart.fcm[0].message.token, "device-token");

  const rating = stub();
  const ratingRes = await call(rating.env, { kind: "rating", orderId: "o1" }, "buyer-token");
  assert.deepEqual(ratingRes.body, { sent: 1 });
  assert.deepEqual(rating.tokenLookups, ["users/seller/tokens"]);
  assert.equal(rating.fcm[0].message.token, "device-token");

  const mod = stub({ admin: true });
  const modRes = await call(
    mod.env,
    { kind: "moderation", sellerId: "seller", title: "Bike" },
    "admin-token",
  );
  assert.deepEqual(modRes.body, { sent: 1 });
  assert.deepEqual(mod.tokenLookups, ["users/seller/tokens"]);
  assert.equal(mod.fcm[0].message.token, "device-token");
});
