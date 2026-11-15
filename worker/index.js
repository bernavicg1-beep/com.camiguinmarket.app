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
  const auth = request.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return json({ error: "unauthorized" }, 401);

  const uid = await verifyIdToken(token, env);
  if (!uid) return json({ error: "unauthorized" }, 401);

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
  const auth = request.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return json({ error: "unauthorized" }, 401);

  const uid = await verifyIdToken(token, env);
  if (!uid) return json({ error: "unauthorized" }, 401);

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

/** Send to every device of one user; clean up dead tokens. */
async function sendToUser(env, sa, token, uid, title, body, data = {}) {
  if (!uid) return 0;
  const tokens = await readTokens(sa, token, uid);
  let sent = 0;

  for (const fcmToken of tokens) {
    const res = await fetch(
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
 * POST /push
 *   Header: Authorization: Bearer <Firebase ID token>
 *   Body:   { "kind": "order" | "status" | "chat", "orderId"?, "chatId"?, "text"? }
 *
 * The recipient is derived by the Worker from Firestore — never from the
 * client — so a user cannot spam another user.
 */
async function handlePush(request, env) {
  const auth = request.headers.get("Authorization") || "";
  const idToken = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!idToken) return json({ error: "unauthorized" }, 401);

  const uid = await verifyIdToken(idToken, env);
  if (!uid) return json({ error: "unauthorized" }, 401);

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

    // Drop the photos of the listings that are really gone, so R2 does not
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
        bucket: Boolean(env.IMAGES),
        push: Boolean(env.FIREBASE_SERVICE_ACCOUNT),
      });
    }

    if (url.pathname === "/push") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handlePush(request, env);
    }

    if (url.pathname === "/upload") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handleUpload(request, env);
    }

    if (url.pathname === "/delete-images") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handleDeleteImages(request, env);
    }

    if (url.pathname.startsWith("/i/")) {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return json({ error: "method_not_allowed" }, 405);
      }
      return handleImage(decodeURIComponent(url.pathname.slice(3)), env, request, ctx);
    }

    return json({ error: "not_found" }, 404);
  },
};
