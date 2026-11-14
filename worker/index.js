/**
 * Camiguin Market — image upload Worker alang sa Cloudflare R2.
 *
 * POST /upload
 *   Header: Authorization: Bearer <Firebase ID token>
 *   Header: Content-Type: image/jpeg | image/png | image/webp
 *   Body:   ang bytes sa litrato (max 5 MB)
 *   Tubag:  { "url": "https://<worker>/i/listing_images/<uid>/<file>.jpg" }
 *
 * GET /i/<key>
 *   Ibalik ang litrato gikan sa R2 (public read, long cache).
 *
 * Ngano dinhi ang verification: ang R2 credentials wala gayud moadto sa app.
 * Ang Worker maoy mo-verify sa Firebase ID token ug mohimo sa R2 key gikan sa
 * `uid` sa token, busa ang usa ka user makasulat ra sa iyang kaugalingong folder.
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

/** I-verify ang Firebase ID token ug ibalik ang uid (o null kon dili balido). */
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

/** Ang base URL sa Worker, para sa mga link sa litrato. */
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

async function handleImage(key, env) {
  const object = await env.IMAGES.get(key);
  if (!object) return new Response("Not found", { status: 404, headers: CORS });

  return new Response(object.body, {
    headers: {
      "Content-Type": object.httpMetadata?.contentType || "application/octet-stream",
      // Ang key dunay timestamp ug random suffix, busa dili na kini mausab.
      "Cache-Control": "public, max-age=31536000, immutable",
      ETag: object.httpEtag,
      ...CORS,
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }

    if (url.pathname === "/health") {
      return json({ ok: true, bucket: Boolean(env.IMAGES) });
    }

    if (url.pathname === "/upload") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      return handleUpload(request, env);
    }

    if (url.pathname.startsWith("/i/")) {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return json({ error: "method_not_allowed" }, 405);
      }
      return handleImage(decodeURIComponent(url.pathname.slice(3)), env);
    }

    return json({ error: "not_found" }, 404);
  },
};
