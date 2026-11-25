/**
 * Camiguin Market — image upload + push Worker (Cloudflare R2 + FCM).
 *
 * POST /upload
 *   Header: Authorization: Bearer <Firebase ID token>
 *   Header: Content-Type: image/jpeg | image/png | image/webp
 *   Body:   photo bytes (max 5 MB)
 *   Response: { "url": "https://<worker>/i/listing_images/<uid>/<file>.jpg" }
 *
 * GET /i/<key>
 *   Return the photo from R2 (public read, long cache).
 *
 * Why verification lives here: R2 credentials never reach the app.
 * The Worker verifies the Firebase ID token and builds the R2 key from the
 * token's `uid`, so a user can only write inside their own folder.
 */

const MAX_BYTES = 5 * 1024 * 1024;
const ALLOWED_TYPES = new Map([
  ["image/jpeg", "jpg"],
  ["image/jpg", "jpg"],
  ["image/png", "png"],
  ["image/webp", "webp"],
]);

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...CORS },
  });
}

// ---------------------------------------------------------------------------
// Best-effort in-isolate rate limiting. Cloudflare Rate Limiting rules are the
// stronger layer; this protects against runaway clients and accidental loops.
// State is per isolate (and per test env's worker instance is shared, so tests
// call resetRateLimits() between cases).
// ---------------------------------------------------------------------------
const RATE_LIMIT_BUCKETS = new Map();

export function resetRateLimits() {
  RATE_LIMIT_BUCKETS.clear();
}

function checkRateLimit(key, limit, windowMs) {
  const buckets = RATE_LIMIT_BUCKETS;
  const now = Date.now();
  const bucket = buckets.get(key);
  if (!bucket || now >= bucket.resetAt) {
    buckets.set(key, { count: 1, resetAt: now + windowMs });
    return { allowed: true, remaining: limit - 1, retryAfter: 0 };
  }
  if (bucket.count >= limit) {
    return { allowed: false, remaining: 0, retryAfter: Math.max(1, Math.ceil((bucket.resetAt - now) / 1000)) };
  }
  bucket.count += 1;
  return { allowed: true, remaining: limit - bucket.count, retryAfter: 0 };
}

function clientIp(request) {
  const cf = request.headers.get("CF-Connecting-IP");
  if (cf) return cf.trim();
  const xff = request.headers.get("X-Forwarded-For");
  if (xff) return xff.split(",")[0].trim();
  return "unknown";
}

function rateLimitedResponse(retryAfter) {
  return new Response(JSON.stringify({ error: "rate_limited" }), {
    status: 429,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Retry-After": String(retryAfter || 60),
      ...CORS,
    },
  });
}

// Rate-limit helper. With uid=null it checks the per-IP bucket (pre-auth);
// with a uid it checks the per-user bucket (post-auth). Returns a 429
// Response when exceeded, otherwise null.
function guardRateLimit(request, scope, uid, uidLimit) {
  if (uid) {
    const r = checkRateLimit(`uid:${scope}:${uid}`, uidLimit || 60, 60000);
    return r.allowed ? null : rateLimitedResponse(r.retryAfter);
  }
  const r = checkRateLimit(`ip:${scope}:${clientIp(request)}`, 60, 60000);
  return r.allowed ? null : rateLimitedResponse(r.retryAfter);
}

/** Verify the Firebase ID token and return the uid (or null when invalid). */
async function verifyIdToken(idToken, env) {
  const apiKey = env.FIREBASE_WEB_API_KEY;
  if (!apiKey) return null;

  const res = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${apiKey}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken }),
    },
  );
  if (!res.ok) return null;

  const data = await res.json();
  const user = data.users && data.users[0];
  return user && user.localId ? user.localId : null;
}

/** The Worker base URL, used for photo links. */
function publicBase(request, env) {
  const configured = (env.PUBLIC_BASE_URL || "").replace(/\/+$/, "");
  if (configured) return configured;
  const url = new URL(request.url);
  return `${url.protocol}//${url.host}`;
}

async function handleUpload(request, env) {
  const ipBlock = guardRateLimit(request, "upload", null);
  if (ipBlock) return ipBlock;

  const auth = request.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return json({ error: "unauthorized" }, 401);

  const uid = await verifyIdToken(token, env);
  if (!uid) return json({ error: "unauthorized" }, 401);

  const uidBlock = guardRateLimit(request, "upload", uid, 60);
  if (uidBlock) return uidBlock;

  const contentType = (request.headers.get("Content-Type") || "")
    .split(";")[0]
    .trim()
    .toLowerCase();
  const ext = ALLOWED_TYPES.get(contentType);
  if (!ext) return json({ error: "unsupported_type" }, 415);

  const declared = Number(request.headers.get("Content-Length") || "0");
  if (declared > MAX_BYTES) return json({ error: "too_large" }, 413);

  const body = await request.arrayBuffer();
  if (body.byteLength === 0) return json({ error: "empty_body" }, 400);
  if (body.byteLength > MAX_BYTES) return json({ error: "too_large" }, 413);

  const key = `listing_images/${uid}/${Date.now()}-${crypto.randomUUID().slice(0, 8)}.${ext}`;
  await env.IMAGES.put(key, body, { httpMetadata: { contentType } });

  return json({ url: `${publicBase(request, env)}/i/${key}`, key }, 200);
}

/**
 * POST /delete-images
 *   Header: Authorization: Bearer <Firebase ID token>
 *   Body:   { "urls": ["https://<worker>/i/listing_images/<uid>/<file>.jpg", ...] }
 *   Response: { "deleted": n, "skipped": n }
 *
 * Used by the app when a seller deletes a listing or replaces its photos.
 * Only keys inside `listing_images/<uid>/` are removed, so a user cannot
 * delete another seller's photos; unknown URLs count as skipped.
 *
 * An admin (`admins/{uid}` exists) may also delete photos in any
 * `listing_images/` folder, which is how moderator removals clean up after a
 * listing that belongs to someone else.
 */
async function handleDeleteImages(request, env) {
  const ipBlock = guardRateLimit(request, "delete", null);
  if (ipBlock) return ipBlock;

  const auth = request.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return json({ error: "unauthorized" }, 401);

  const uid = await verifyIdToken(token, env);
  if (!uid) return json({ error: "unauthorized" }, 401);

  const uidBlock = guardRateLimit(request, "delete", uid, 30);
  if (uidBlock) return uidBlock;

  if (!env.IMAGES) return json({ error: "storage_not_configured" }, 503);

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad_json" }, 400);
  }

  const urls = Array.isArray(body.urls) ? body.urls.slice(0, 30).map(String) : [];
  const prefix = `listing_images/${uid}/`;
  let deleted = 0;
  let skipped = 0;

  // Whether the caller is a moderator; only needed for keys outside their own
  // folder, so it is looked up at most once and only when necessary.
  let admin = null;
  const callerIsAdmin = async () => {
    if (admin !== null) return admin;
    admin = false;
    const sa = serviceAccount(env);
    if (!sa) return admin;
    const access = await accessToken(env);
    if (!access) return admin;
    admin = Boolean(await readDoc(sa, access, `admins/${uid}`));
    return admin;
  };

  for (const url of urls) {
    const key = photoKey(url);
    if (!key || (!key.startsWith(prefix) && !(await callerIsAdmin()))) {
      skipped += 1;
      continue;
    }
    try {
      await env.IMAGES.delete(key);
      deleted += 1;
    } catch {
      skipped += 1;
    }
  }

  return json({ deleted, skipped }, 200);
}

async function handleImage(key, env, request, ctx) {
  // Serve repeat views from the Cloudflare edge instead of R2 every time.
  // Cache API may be unavailable in some runtimes; guard so a missing cache
  // never breaks image serving.
  let cache = null;
  try {
    cache = (typeof caches !== "undefined" && caches.default) ? caches.default : null;
  } catch (e) {
    cache = null;
  }

  if (cache) {
    try {
      const cacheKey = new Request(request.url, { method: "GET" });
      const hit = await cache.match(cacheKey);
      if (hit) return hit;
    } catch (e) {
      // ignore cache errors and fall through to R2
    }
  }

  const object = await env.IMAGES.get(key);
  if (!object) return new Response("Not found", { status: 404, headers: CORS });

  const response = new Response(object.body, {
    headers: {
      "Content-Type": object.httpMetadata?.contentType || "application/octet-stream",
      // The key has a timestamp and random suffix, so it never changes.
      "Cache-Control": "public, max-age=31536000, immutable",
      ETag: object.httpEtag,
      ...CORS,
    },
  });

  if (cache && ctx && typeof ctx.waitUntil === "function") {
    try {
      const cacheKey = new Request(request.url, { method: "GET" });
      ctx.waitUntil(cache.put(cacheKey, response.clone()));
    } catch (e) {
      // ignore
    }
  }
  return response;
}


// ---------------------------------------------------------------------------
// Push notifications (FCM HTTP v1) — same as R2: the service account never
// reaches the app. The Worker verifies the Firebase ID token, picks the
// recipient from Firestore (never from the client), and sends through FCM.
// ---------------------------------------------------------------------------

function b64url(bytesOrString) {
  let bytes = bytesOrString;
  if (typeof bytesOrString === "string") {
    bytes = new TextEncoder().encode(bytesOrString);
  }
  let binary = "";
  for (const b of new Uint8Array(bytes)) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** "-----BEGIN PRIVATE KEY-----..." → ArrayBuffer (PKCS#8). */
function pemToBuffer(pem) {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const raw = atob(body);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes.buffer;
}

function serviceAccount(env) {
  if (!env.FIREBASE_SERVICE_ACCOUNT) return null;
  try {
    return JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
  } catch {
    return null;
  }
}

// The access token is cached inside the isolate (1 hour lifespan).
let tokenCache = { value: null, expiresAt: 0 };

async function accessToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (tokenCache.value && tokenCache.expiresAt - 60 > now) return tokenCache.value;

  const sa = serviceAccount(env);
  if (!sa) return null;

  const claims = {
    iss: sa.client_email,
    scope:
      "https://www.googleapis.com/auth/datastore " +
      "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const unsigned = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64url(
    JSON.stringify(claims),
  )}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToBuffer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const assertion = `${unsigned}.${b64url(signature)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!res.ok) return null;

  const data = await res.json();
  if (!data.access_token) return null;
  tokenCache = {
    value: data.access_token,
    expiresAt: now + (Number(data.expires_in) || 3600),
  };
  return tokenCache.value;
}

function docUrl(sa, path) {
  return `https://firestore.googleapis.com/v1/projects/${sa.project_id}` +
    `/databases/(default)/documents/${path}`;
}

/** Value of a Firestore REST field (string/integer/double/map/array). */
function fieldValue(field) {
  if (!field) return undefined;
  if (field.stringValue !== undefined) return field.stringValue;
  if (field.integerValue !== undefined) return Number(field.integerValue);
  if (field.doubleValue !== undefined) return Number(field.doubleValue);
  if (field.booleanValue !== undefined) return field.booleanValue;
  if (field.nullValue !== undefined) return null;
  if (field.arrayValue) {
    return (field.arrayValue.values || []).map((v) => fieldValue(v));
  }
  if (field.mapValue) {
    const out = {};
    for (const [k, v] of Object.entries(field.mapValue.fields || {})) {
      out[k] = fieldValue(v);
    }
    return out;
  }
  return undefined;
}

async function readDoc(sa, token, path) {
  const res = await fetch(docUrl(sa, path), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (res.status === 404) return null;
  if (!res.ok) return null;
  const data = await res.json();
  const out = {};
  for (const [k, v] of Object.entries(data.fields || {})) out[k] = fieldValue(v);
  return out;
}

async function readTokens(sa, token, uid) {
  const res = await fetch(docUrl(sa, `users/${uid}/tokens`), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) return [];
  const data = await res.json();
  return (data.documents || [])
    .map((d) => fieldValue((d.fields || {}).token))
    .filter(Boolean);
}

async function deleteToken(sa, token, uid, fcmToken) {
  await fetch(docUrl(sa, `users/${uid}/tokens/${encodeURIComponent(fcmToken)}`), {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
}

/** Persist the verified Telegram chat id + username on the user document. */
async function writeTelegramLink(sa, token, uid, chatId, username) {
  const fields = {};
  const mask = [];
  if (chatId !== null && chatId !== undefined) {
    fields.telegramChatId = { integerValue: String(chatId) };
    mask.push("telegramChatId");
  }
  if (username) {
    fields.telegramUsername = { stringValue: String(username) };
    mask.push("telegramUsername");
  }
  if (mask.length === 0) return;
  const qs = mask.map((f) => `updateMask.fieldPaths=${encodeURIComponent(f)}`).join("&");
  await fetch(`${docUrl(sa, `users/${uid}`)}?${qs}`, {
    method: "PATCH",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ fields }),
  });
}

/** Read a user's stored Telegram chat id (or null). */
async function readTelegramChatId(sa, token, uid) {
  if (!uid) return null;
  const doc = await readDoc(sa, token, `users/${uid}`);
  if (!doc) return null;
  const v = doc.telegramChatId;
  return v === null || v === undefined ? null : v;
}

/** Send a plain-text message through the Telegram Bot API. Best effort. */
async function sendTelegram(env, chatId, text) {
  const botToken = env.TELEGRAM_BOT_TOKEN;
  if (!botToken || !chatId) return;
  try {
    await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ chat_id: chatId, text: String(text).slice(0, 4000) }),
    });
  } catch {
    // Network failure: do not throw; Telegram is a secondary channel.
  }
}

// Best-effort Telegram notification: looks up the recipient's stored chat id
// and sends a message. Never throws.
async function telegramNotify(env, sa, access, uid, text) {
  try {
    const chatId = await readTelegramChatId(sa, access, uid);
    if (chatId) await sendTelegram(env, chatId, text);
  } catch {
    // ignore
  }
}

/** Send to every device of one user; clean up dead tokens. */
async function sendToUser(env, sa, token, uid, title, body, data = {}) {
  if (!uid) return 0;
  let tokens = [];
  try {
    tokens = await readTokens(sa, token, uid);
  } catch {
    tokens = [];
  }
  let sent = 0;

  for (const fcmToken of tokens) {
    let res;
    try {
      res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: fcmToken,
            notification: { title, body },
            data: Object.fromEntries(
              Object.entries(data).map(([k, v]) => [k, String(v)]),
            ),
            android: { priority: "high" },
          },
        }),
      },
    );
    } catch {
      // Transient FCM failure: do not throw; report as not sent.
      continue;
    }
    if (res.ok) {
      sent += 1;
      continue;
    }
    const text = await res.text();
    if (
      text.includes("UNREGISTERED") ||
      text.includes("INVALID_ARGUMENT") ||
      text.includes("NOT_FOUND")
    ) {
      await deleteToken(sa, token, uid, fcmToken);
    }
  }
  return sent;
}

/**
 * POST /notify
 *   Header: Authorization: Bearer <Firebase ID token>
 *   Body:   { "toUid", "fromUid", "fromName", "message", "listingId"?, "listingTitle"? }
 *
 * Writes a notification document into the recipient's inbox from the Worker,
 * using the service account, so the strict client rule (inbox owner == caller)
 * stays in place while cross-user notifications still work.
 */
async function handleNotify(request, env) {
  const ipBlock = guardRateLimit(request, "notify", null);
  if (ipBlock) return ipBlock;

  const auth = request.headers.get("Authorization") || "";
  const idToken = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!idToken) return json({ error: "unauthorized" }, 401);

  const uid = await verifyIdToken(idToken, env);
  if (!uid) return json({ error: "unauthorized" }, 401);

  const uidBlock = guardRateLimit(request, "notify", uid, 60);
  if (uidBlock) return uidBlock;

  const sa = serviceAccount(env);
  if (!sa) return json({ error: "notify_not_configured" }, 503);
  const access = await accessToken(env);
  if (!access) return json({ error: "notify_auth_failed" }, 503);

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad_json" }, 400);
  }

  const toUid = String(body.toUid || "");
  const fromUid = String(body.fromUid || "");
  const fromName = String(body.fromName || "");
  const message = String(body.message || "").slice(0, 200);
  const listingId = String(body.listingId || "");
  const listingTitle = String(body.listingTitle || "");

  if (!toUid || !fromUid || !message) return json({ error: "missing_fields" }, 400);
  if (fromUid !== uid) return json({ error: "forbidden" }, 403);
  // A user may not notify themselves through this path; self notifications
  // stay client-side (allowed by the rules).
  if (toUid === uid) return json({ error: "self_not_allowed" }, 400);

  const res = await fetch(docUrl(sa, `notifications/${toUid}/items`), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${access}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      fields: {
        fromUid: { stringValue: fromUid },
        fromName: { stringValue: fromName },
        message: { stringValue: message },
        listingId: { stringValue: listingId },
        listingTitle: { stringValue: listingTitle },
        createdAt: { integerValue: String(Date.now()) },
        read: { booleanValue: false },
      },
    }),
  });
  if (!res.ok) return json({ error: "write_failed" }, 500);
  return json({ ok: true }, 200);
}

/**
 * POST /push
 *   Header: Authorization: Bearer <Firebase ID token>
 *   Body:   { "kind": "order" | "status" | "chat", "orderId"?, "chatId"?, "text"? }
 *
 * The recipient is derived by the Worker from Firestore — never from the
 * client — so a user cannot spam another user.
 */
async function handlePush(request, env) {
  const ipBlock = guardRateLimit(request, "push", null);
  if (ipBlock) return ipBlock;

  const auth = request.headers.get("Authorization") || "";
  const idToken = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!idToken) return json({ error: "unauthorized" }, 401);

  const uid = await verifyIdToken(idToken, env);
  if (!uid) return json({ error: "unauthorized" }, 401);

  const uidBlock = guardRateLimit(request, "push", uid, 60);
  if (uidBlock) return uidBlock;

  const sa = serviceAccount(env);
  if (!sa) return json({ error: "push_not_configured" }, 503);

  const access = await accessToken(env);
  if (!access) return json({ error: "push_auth_failed" }, 503);

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad_json" }, 400);
  }

  const kind = body.kind;

  if (kind === "saved") {
    const savedSearchId = String(body.savedSearchId || "");
    if (!savedSearchId) return json({ error: "missing_savedSearchId" }, 400);

    const search = await readDoc(sa, access, `savedSearches/${uid}/items/${savedSearchId}`);
    if (!search) return json({ error: "not_found" }, 404);

    const text = String(body.text || "New listings").slice(0, 140);
    let sent = 0;
    try {
      sent = await sendToUser(
        env, sa, access, uid,
        "Saved search 🔔",
        text || "New listings",
        { savedSearchId, type: "saved_search" },
      );
    } catch {
      sent = 0;
    }
    await telegramNotify(env, sa, access, uid, `Saved search 🔔\n${text || "New listings"}`);
    // Always report success so the client does not retry forever; partial
    // delivery is reflected in `sent`.
    return json({ sent }, 200);
  }

  if (kind === "chat") {
    const chatId = String(body.chatId || "");
    if (!chatId) return json({ error: "missing_chatId" }, 400);

    const chat = await readDoc(sa, access, `chats/${chatId}`);
    if (!chat) return json({ error: "not_found" }, 404);

    const participants = Array.isArray(chat.participants) ? chat.participants : [];
    if (!participants.includes(uid)) return json({ error: "forbidden" }, 403);

    const other = participants.find((p) => p !== uid);
    if (!other) return json({ error: "no_recipient" }, 400);

    const names = chat.names || {};
    const text = String(body.text || chat.lastMessage || "").slice(0, 140);
    const sent = await sendToUser(
      env, sa, access, other,
      `💬 ${names[uid] || "Bag-ong mensahe"}`,
      text || "Bag-ong mensahe",
      { chatId, type: "chat" },
    );
    await telegramNotify(env, sa, access, other, `💬 ${names[uid] || "Bag-ong mensahe"}\n${text || "Bag-ong mensahe"}\n${publicBase(request, env)}/chat/${chatId}`);
    return json({ sent }, 200);
  }

  const orderId = String(body.orderId || "");
  if (!orderId) return json({ error: "missing_orderId" }, 400);

  const order = await readDoc(sa, access, `orders/${orderId}`);
  if (!order) return json({ error: "not_found" }, 404);

  if (kind === "order") {
    // Only the buyer can notify the seller about their new order.
    if (order.buyerId !== uid) return json({ error: "forbidden" }, 403);
    const qty = order.quantity || 1;
    const sent = await sendToUser(
      env, sa, access, order.sellerId,
      "Bag-ong order 🛒",
      `${order.buyerName || "Usa ka buyer"} mi-order: ${order.listingTitle} ×${qty}`,
      { orderId, type: "order" },
    );
    await telegramNotify(env, sa, access, order.sellerId, `Bag-ong order 🛒\n${order.buyerName || "Usa ka buyer"} mi-order: ${order.listingTitle} ×${qty}\n${publicBase(request, env)}/order/${orderId}`);
    return json({ sent }, 200);
  }

  if (kind === "status") {
    // Only the seller can notify the buyer about the status.
    if (order.sellerId !== uid) return json({ error: "forbidden" }, 403);
    const action = {
      confirmed: "gikumpirma",
      done: "natapos",
      cancelled: "gikansela",
    }[order.status];
    if (!action) return json({ sent: 0 }, 200);
    const sent = await sendToUser(
      env, sa, access, order.buyerId,
      "Order update 📦",
      `${order.sellerName || "Ang seller"} ${action} ang imong order: ${order.listingTitle}`,
      { orderId, type: "order" },
    );
    await telegramNotify(env, sa, access, order.buyerId, `Order update 📦\n${order.sellerName || "Ang seller"} ${action} ang imong order: ${order.listingTitle}\n${publicBase(request, env)}/order/${orderId}`);
    return json({ sent }, 200);
  }

  return json({ error: "unknown_kind" }, 400);
}

// ---------------------------------------------------------------------------
// Scheduled cleanup: delete perishable listings after their expiration.
//
// The app already hides expired listings, but the documents stay in Firestore
// until they are removed here. The query only touches documents whose
// `expiresAt` is in the past, so non-perishable listings (expiresAt = 0) and
// older documents without the field are never selected.
// ---------------------------------------------------------------------------

const PURGE_BATCH = 200;
// Bound the work of a single cron run (batches × PURGE_BATCH documents).
const PURGE_MAX_BATCHES = 10;

/** List perishable listings whose expiration has already passed. */
async function queryExpiredListings(sa, token, now, limit) {
  const res = await fetch(
    `https://firestore.googleapis.com/v1/projects/${sa.project_id}` +
      `/databases/(default)/documents:runQuery`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        structuredQuery: {
          from: [{ collectionId: "listings" }],
          where: {
            // Range on a single field: 0 < expiresAt <= now.
            compositeFilter: {
              op: "AND",
              filters: [
                {
                  fieldFilter: {
                    field: { fieldPath: "expiresAt" },
                    op: "GREATER_THAN",
                    value: { integerValue: "0" },
                  },
                },
                {
                  fieldFilter: {
                    field: { fieldPath: "expiresAt" },
                    op: "LESS_THAN_OR_EQUAL",
                    value: { integerValue: String(now) },
                  },
                },
              ],
            },
          },
          limit,
        },
      }),
    },
  );

  if (!res.ok) {
    throw new Error(`runQuery ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }

  const rows = await res.json();
  const out = [];
  for (const row of rows) {
    // Rows with no match come back as `{ readTime }` only.
    if (!row.document) continue;
    const fields = row.document.fields || {};
    out.push({
      id: row.document.name.split("/").pop(),
      isPerishable: fieldValue(fields.isPerishable) === true,
      expiresAt: Number(fieldValue(fields.expiresAt) || 0),
      imageUrl: fieldValue(fields.imageUrl) || "",
      imageUrls: fieldValue(fields.imageUrls) || [],
    });
  }
  return out;
}

async function deleteListing(sa, token, id) {
  const res = await fetch(docUrl(sa, `listings/${id}`), {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  // 404 means it was already removed by another run: that is fine.
  return res.ok || res.status === 404;
}

/** R2 key behind a photo URL: https://host/i/listing_images/<uid>/<file>. */
function photoKey(url) {
  if (!url) return null;
  const at = String(url).indexOf("/i/");
  if (at === -1) return null;
  const key = decodeURIComponent(String(url).slice(at + 3));
  return key.startsWith("listing_images/") ? key : null;
}

/**
 * Delete the photos of listings that are gone. Called only after the document
 * was deleted, so a listing is never left pointing at a missing photo.
 */
async function deletePhotos(env, docs) {
  if (!env.IMAGES) return { photosDeleted: 0, photoFailures: 0 };

  const keys = new Set();
  for (const doc of docs) {
    const single = photoKey(doc.imageUrl);
    if (single) keys.add(single);
    for (const url of doc.imageUrls || []) {
      const key = photoKey(url);
      if (key) keys.add(key);
    }
  }

  let photosDeleted = 0;
  let photoFailures = 0;
  for (const key of keys) {
    try {
      await env.IMAGES.delete(key);
      photosDeleted += 1;
    } catch {
      photoFailures += 1;
    }
  }
  return { photosDeleted, photoFailures };
}

/** Remove every expired perishable listing (bounded per run). */
async function purgeExpiredListings(env) {
  const sa = serviceAccount(env);
  if (!sa) return { deleted: 0, skipped: "no_service_account" };

  const access = await accessToken(env);
  if (!access) return { deleted: 0, skipped: "no_access_token" };

  let deleted = 0;
  let failures = 0;
  let photosDeleted = 0;
  let photoFailures = 0;

  for (let batch = 0; batch < PURGE_MAX_BATCHES; batch++) {
    const now = Date.now();
    const expired = (await queryExpiredListings(sa, access, now, PURGE_BATCH))
      .filter((doc) => doc.isPerishable && doc.expiresAt > 0 && doc.expiresAt <= now);
    if (expired.length === 0) break;

    const results = await Promise.all(
      expired.map((doc) => deleteListing(sa, access, doc.id)),
    );
    const removedDocs = expired.filter((_, index) => results[index]);
    deleted += removedDocs.length;
    failures += expired.length - removedDocs.length;

    // Drop the photos of the listings that are gone, so R2 does not
    // keep orphaned images after the document is deleted.
    const photos = await deletePhotos(env, removedDocs);
    photosDeleted += photos.photosDeleted;
    photoFailures += photos.photoFailures;

    // Stop when nothing could be removed, so a failure cannot spin the loop.
    if (removedDocs.length === 0) break;
    if (expired.length < PURGE_BATCH) break;
  }

  return { deleted, failures, photosDeleted, photoFailures };
}

async function handleTelegramLogin(request, env, url) {
  const ipBlock = guardRateLimit(request, "telegram-login", null);
  if (ipBlock) return ipBlock;

  const botUsername = env.TELEGRAM_BOT_USERNAME || "CamiguinMarketBot";
  const token = url.searchParams.get("token") || "";
  const html = `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Connect Telegram</title>
<style>body{font-family:sans-serif;padding:24px;text-align:center;}h2{margin-bottom:16px;}</style>
</head><body>
<h2>Connect your Telegram account</h2>
<p>Tap the button below and approve the login.</p>
<div id="tg"></div>
<script>
  const TOKEN = ${JSON.stringify(token)};
  function onTelegramAuth(user) {
    fetch('/telegram/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN },
      body: JSON.stringify(user)
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (d && d.ok) { window.Android && window.Android.onVerified(d.username || ''); }
      else { window.Android && window.Android.onError((d && d.error) || 'Verification failed'); }
    }).catch(function (e) { window.Android && window.Android.onError(String(e)); });
  }
</script>
<script async src="https://telegram.org/js/telegram-widget.js?22"
  data-telegram-login="${botUsername.replace(/^@/, '')}"
  data-size="large"
  data-onauth="onTelegramAuth(user)"
  data-request-access="write"></script>
</body></html>`;
  return new Response(html, { status: 200, headers: { 'Content-Type': 'text/html; charset=utf-8' } });
}

async function handleTelegramVerify(request, env) {
  const ipBlock = guardRateLimit(request, "telegram-verify", null);
  if (ipBlock) return ipBlock;

  const auth = request.headers.get('Authorization') || '';
  const idToken = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!idToken) return json({ error: 'unauthorized' }, 401);
  const uid = await verifyIdToken(idToken, env);
  if (!uid) return json({ error: 'unauthorized' }, 401);

  const uidBlock = guardRateLimit(request, "telegram-verify", uid, 10);
  if (uidBlock) return uidBlock;

  const botToken = env.TELEGRAM_BOT_TOKEN;
  if (!botToken) return json({ error: 'telegram_not_configured' }, 503);

  let body;
  try { body = await request.json(); } catch { return json({ error: 'bad_json' }, 400); }

  const hash = String(body.hash || '');
  if (!hash) return json({ error: 'missing_hash' }, 400);

  // Build the data-check string from all fields except hash, sorted.
  const fields = Object.keys(body).filter((k) => k !== 'hash').sort();
  const dataCheckString = fields.map((k) => `${k}=${body[k]}`).join('\n');

  const enc = new TextEncoder();
  const secretKey = await crypto.subtle.digest('SHA-256', enc.encode(botToken));
  const cryptoKey = await crypto.subtle.importKey('raw', secretKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', cryptoKey, enc.encode(dataCheckString));
  const computed = [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');

  if (computed.toLowerCase() !== hash.toLowerCase()) {
    return json({ error: 'invalid_hash' }, 403);
  }

  const authDate = Number(body.auth_date);
  if (!Number.isFinite(authDate) || Math.floor(Date.now() / 1000) - authDate > 86400) {
    return json({ error: 'expired' }, 403);
  }

  const username = body.username || body.first_name || '';
  const chatId = body.id;

  // Persist the verified Telegram chat id so the Worker can push alerts
  // through the Bot API later (best effort; needs the service account).
  const sa = serviceAccount(env);
  if (sa) {
    const access = await accessToken(env);
    if (access) await writeTelegramLink(sa, access, uid, chatId, username).catch(() => {});
  }

  return json({ ok: true, uid, username, chatId });
}

export default {
  /** Cron trigger: delete expired perishable listings. */
  async scheduled(event, env, ctx) {
    ctx.waitUntil(
      purgeExpiredListings(env)
        .then((result) => console.log("purgeExpired", JSON.stringify(result)))
        .catch((error) => console.log("purgeExpired failed", error.message)),
    );
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }

    if (url.pathname === "/health") {
      return json({
        ok: true,
      images: Boolean(env.IMAGES),
      bucket: Boolean(env.IMAGES),
      push: Boolean(env.FIREBASE_SERVICE_ACCOUNT),
      telegram: Boolean(env.TELEGRAM_BOT_TOKEN),
      });
    }

    if (url.pathname === "/push") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handlePush(request, env);
    }

    if (url.pathname === "/notify") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handleNotify(request, env);
    }

    if (url.pathname === "/upload") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handleUpload(request, env);
    }

    if (url.pathname === "/delete-images") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handleDeleteImages(request, env);
    }

    if (url.pathname === "/telegram/login") {
      if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
      return handleTelegramLogin(request, env, url);
    }

    if (url.pathname === "/telegram/verify") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handleTelegramVerify(request, env);
    }

    if (url.pathname.startsWith("/i/")) {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return json({ error: "method_not_allowed" }, 405);
      }
      return handleImage(decodeURIComponent(url.pathname.slice(3)), env, request, ctx);
    }

    // Android App Links: Digital Asset Links file used by the OS to verify
    // that https://<worker>/listing|order|chat URLs open the Camiguin Market app.
    if (url.pathname === "/.well-known/assetlinks.json") {
      return new Response(
        JSON.stringify([
          {
            relation: ["delegate_permission/common.handle_all_urls"],
            target: {
              namespace: "android_app",
              package_name: "com.camiguinmarket.app",
              sha256_cert_fingerprints: [
                "C7:3A:F1:15:85:CF:67:95:18:7B:77:6B:46:F8:F3:12:7F:AD:EC:E5:C7:A0:92:05:19:A5:13:97:E7:F6:2A:95",
              ],
            },
          },
        ]),
        { status: 200, headers: { "Content-Type": "application/json", ...CORS } },
      );
    }

    // Deep-link fallback pages: when the app is not installed (or app links
    // are not yet verified) the browser shows a small page that tries to open
    // the matching custom scheme (camiguinmarket://...) and links to the store.
    const deepLinkMatch = url.pathname.match(/^\/(listing|order|chat)\/([^/]+)\/?$/);
    if (deepLinkMatch) {
      const kind = deepLinkMatch[1];
      const id = decodeURIComponent(deepLinkMatch[2]);
      const scheme = `camiguinmarket://${kind}/${id}`;
      const html = `<!doctype html><html><head><meta charset="utf-8">` +
        `<meta name="viewport" content="width=device-width,initial-scale=1">` +
        `<title>Open in Camiguin Market</title>` +
        `<meta http-equiv="refresh" content="0;url=${scheme}">` +
        `</head><body style="font-family:sans-serif;text-align:center;padding:24px">` +
        `<h2>Opening Camiguin Market…</h2>` +
        `<p>If nothing happens, <a href="${scheme}">tap here</a>.</p>` +
        `</body></html>`;
      return new Response(html, {
        status: 200,
        headers: { "Content-Type": "text/html; charset=utf-8" },
      });
    }

    return json({ error: "not_found" }, 404);
  },
};
