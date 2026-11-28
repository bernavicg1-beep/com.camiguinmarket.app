/*
 * Camiguin Market — provider deletion for the storage cleanup pipeline.
 *
 * Postgres decides *what* may be deleted (`cleanup_queue`) and records the
 * outcome (`cleanup_confirm`). This module does the part Postgres cannot: call
 * Cloudinary and Cloudflare R2 with credentials that never enter the database,
 * the repo or the app.
 *
 * It is deliberately plain JavaScript with no Deno or Node APIs, and `fetch` is
 * injected, so the exact same file runs in the Supabase Edge Function and under
 * Node in the tests (which never touch the network).
 *
 * Contract with the database:
 *   * only rows from `cleanup_pending_deletions` are attempted — they are
 *     queued, not yet deleted, and already past their grace;
 *   * every attempt ends in `cleanup_confirm(media_id, ok, error)`; that call is
 *     the only thing that sets `deleted_at`, and on failure it keeps the bytes
 *     and retries a day later.
 */

export const DEFAULT_MAX = 200;
export const DEFAULT_CONCURRENCY = 5;
export const TIMEOUT_MS = 15_000;

/** True when the environment has everything a provider needs to delete. */
export function providerReady(provider, env = {}) {
  if (provider === "cloudinary") {
    return Boolean(env.CLOUDINARY_CLOUD_NAME && env.CLOUDINARY_API_KEY && env.CLOUDINARY_API_SECRET);
  }
  if (provider === "r2") {
    return Boolean(env.R2_ACCOUNT_ID && env.R2_BUCKET && env.R2_API_TOKEN);
  }
  return false;
}

/** Cloudinary's Admin API separates images from videos by resource type. */
export function resourceType(row) {
  return String((row && row.mime_type) || "").toLowerCase().startsWith("video/") ? "video" : "image";
}

/**
 * Admin API "delete resources by public ID":
 *   DELETE https://api.cloudinary.com/v1_1/<cloud_name>/resources/<type>/upload
 *   Basic auth with the API key and secret, `public_ids[]` in the body.
 * The response reports each id as "deleted" or "not_found".
 */
export function cloudinaryRequest(env, row) {
  const cloud = encodeURIComponent(env.CLOUDINARY_CLOUD_NAME);
  const body = `public_ids[]=${encodeURIComponent(row.storage_key)}`;
  return {
    url: `https://api.cloudinary.com/v1_1/${cloud}/resources/${resourceType(row)}/upload`,
    init: {
      method: "DELETE",
      headers: {
        Authorization: `Basic ${base64(`${env.CLOUDINARY_API_KEY}:${env.CLOUDINARY_API_SECRET}`)}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body,
    },
  };
}

/**
 * Cloudflare API "delete object":
 *   DELETE /accounts/<account>/r2/buckets/<bucket>/objects/<key>
 * The documentation requires slashes inside the key to stay literal and every
 * other reserved character to be percent-encoded, so a key can never escape its
 * prefix.
 */
export function r2Request(env, row) {
  const key = String(row.storage_key).split("/").map(encodeURIComponent).join("/");
  return {
    url:
      `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(env.R2_ACCOUNT_ID)}` +
      `/r2/buckets/${encodeURIComponent(env.R2_BUCKET)}/objects/${key}`,
    init: {
      method: "DELETE",
      headers: { Authorization: `Bearer ${env.R2_API_TOKEN}` },
    },
  };
}
