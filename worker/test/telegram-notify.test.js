/**
 * Tests that `POST /push` sends a secondary Telegram message when the
 * recipient has a stored `telegramChatId`, and skips it otherwise.
 *
 * Run with: npm test  (inside `worker/`)
 */

import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import test from "node:test";

import worker, { resetRateLimits } from "../index.js";

const PROJECT = "demo-camiguin";
const PRIVATE_KEY = generateKeyPairSync("rsa", { modulusLength: 2048 })
  .privateKey.export({ type: "pkcs8", format: "pem" });

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/**
 * Build a stub. Options:
 *   telegramChatId: number|null  -> value returned for users/{uid}.telegramChatId
 *   order: object               -> order document returned for orders/{id}
 *   chat: object                -> chat document returned for chats/{id}
 *   hasSavedSearch: boolean
 */
function stub(opts = {}) {
  const {
    telegramChatId = 999,
    hasSavedSearch = true,
    order = { buyerId: "buyer1", sellerId: "seller1", buyerName: "Buyer", sellerName: "Seller", listingTitle: "Bike", quantity: 2, status: "confirmed" },
    chat = { participants: ["u1", "u2"], names: { u1: "Alice", u2: "Bob" }, lastMessage: "hi" },
  } = opts;

  const telegramCalls = [];
  let fcmMessage = null;

  globalThis.fetch = async (url, init = {}) => {
    const href = String(url);

    if (href.includes("identitytoolkit.googleapis.com")) {
      const { idToken } = JSON.parse(init.body);
      const uid = idToken === "user-token" ? "u1" : idToken === "seller-token" ? "seller1" : idToken === "buyer-token" ? "buyer1" : null;
      return jsonResponse({ users: uid ? [{ localId: uid }] : [] });
    }

    if (href.includes("oauth2.googleapis.com/token")) {
      return jsonResponse({ access_token: "stub-access-token", expires_in: 3600 });
    }

    if (href.includes("/documents/savedSearches/")) {
      const path = href.split("/documents/")[1];
      if (hasSavedSearch && path === "savedSearches/u1/items/s1") {
        return jsonResponse({ name: path, fields: { name: { stringValue: "Bike" } } });
      }
      return new Response('{"error":{"code":404}}', { status: 404 });
    }

    if (href.includes("/documents/orders/")) {
      const id = href.split("/documents/orders/")[1];
      return jsonResponse({
        name: `orders/${id}`,
        fields: {
          buyerId: { stringValue: order.buyerId },
          sellerId: { stringValue: order.sellerId },
          buyerName: { stringValue: order.buyerName },
          sellerName: { stringValue: order.sellerName },
          listingTitle: { stringValue: order.listingTitle },
          quantity: { integerValue: String(order.quantity) },
          status: { stringValue: order.status },
        },
      });
    }

    if (href.includes("/documents/chats/")) {
      const id = href.split("/documents/chats/")[1];
      return jsonResponse({
        name: `chats/${id}`,
        fields: {
          participants: { arrayValue: { values: chat.participants.map((p) => ({ stringValue: p })) } },
          names: { mapValue: { fields: Object.fromEntries(Object.entries(chat.names).map(([k, v]) => [k, { stringValue: v }])) } },
          lastMessage: { stringValue: chat.lastMessage },
        },
      });
    }

    // users/{uid} (telegramChatId lookup) — match before tokens
    if (/\/documents\/users\/[^/]+$/.test(href)) {
      const uid = href.split("/documents/users/")[1];
      if (telegramChatId === null) {
        return new Response('{"error":{"code":404}}', { status: 404 });
      }
      return jsonResponse({
        name: `users/${uid}`,
        fields: { telegramChatId: { integerValue: String(telegramChatId) } },
      });
    }

    if (href.includes("/documents/users/") && href.includes("/tokens")) {
      return jsonResponse({
        documents: [{ fields: { token: { stringValue: "device-token" } } }],
      });
    }

    if (href.includes("fcm.googleapis.com")) {
      fcmMessage = JSON.parse(init.body);
      return jsonResponse({ name: "projects/demo/messages/1" });
    }

    if (href.includes("api.telegram.org")) {
      telegramCalls.push(JSON.parse(init.body));
      return jsonResponse({ ok: true, result: { message_id: 1 } });
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
    TELEGRAM_BOT_TOKEN: "123:ABC",
  };

  return { env, telegramCalls, fcmMessage: () => fcmMessage };
}

async function call(env, body, { token = "user-token" } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await worker.fetch(
    new Request("https://worker.example/push", {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    }),
    env,
    { waitUntil() {}, passThroughOnException() {} },
  );
  return { status: res.status, body: await res.json() };
}

test.beforeEach(() => resetRateLimits());

test("saved push sends a Telegram message when chat id present", async () => {
  const { env, telegramCalls } = stub({ telegramChatId: 777 });
  const res = await call(env, { kind: "saved", savedSearchId: "s1", text: "3 new bikes" });
  assert.equal(res.status, 200);
  assert.equal(telegramCalls.length, 1);
  assert.equal(telegramCalls[0].chat_id, 777);
  assert.match(telegramCalls[0].text, /Saved search/);
  assert.match(telegramCalls[0].text, /3 new bikes/);
});

test("saved push skips Telegram when no chat id", async () => {
  const { env, telegramCalls } = stub({ telegramChatId: null });
  const res = await call(env, { kind: "saved", savedSearchId: "s1", text: "3 new bikes" });
  assert.equal(res.status, 200);
  assert.equal(telegramCalls.length, 0);
});

test("chat push sends a Telegram message to the other participant", async () => {
  const { env, telegramCalls } = stub({ telegramChatId: 555 });
  const res = await call(env, { kind: "chat", chatId: "c1", text: "hello" }, { token: "user-token" });
  assert.equal(res.status, 200);
  assert.equal(telegramCalls.length, 1);
  assert.equal(telegramCalls[0].chat_id, 555);
  assert.match(telegramCalls[0].text, /hello/);
});

test("order push sends a Telegram message to the seller", async () => {
  const { env, telegramCalls } = stub({ telegramChatId: 333 });
  const res = await call(env, { kind: "order", orderId: "o1" }, { token: "buyer-token" });
  assert.equal(res.status, 200);
  assert.equal(telegramCalls.length, 1);
  assert.equal(telegramCalls[0].chat_id, 333);
  assert.match(telegramCalls[0].text, /Bag-ong order/);
});

test("status push sends a Telegram message to the buyer", async () => {
  const { env, telegramCalls } = stub({ telegramChatId: 222 });
  const res = await call(env, { kind: "status", orderId: "o1" }, { token: "seller-token" });
  assert.equal(res.status, 200);
  assert.equal(telegramCalls.length, 1);
  assert.equal(telegramCalls[0].chat_id, 222);
  assert.match(telegramCalls[0].text, /Order update/);
});
