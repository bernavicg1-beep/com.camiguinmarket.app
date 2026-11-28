/**
 * Camiguin Market — cleanup-provider Edge Function (Deno, Supabase runtime).
 *
 * The last step of the storage cleanup: delete the real object for every row the
 * database already queued, and report each outcome with cleanup_confirm().
 * Postgres never sees a provider secret, and this function never decides whether
 * something may be deleted — it only acts on `cleanup_pending_deletions`.
 *
 * Deploy: supabase functions deploy cleanup-provider --no-verify-jwt
 *
 * Secrets (function environment only): SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY
 * (platform), CLEANUP_SECRET (same value as app.cleanup_secret), and
 * CLOUDINARY_CLOUD_NAME / CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET or
 * R2_ACCOUNT_ID / R2_BUCKET / R2_API_TOKEN.
 */
import { createClient } from "npm:@supabase/supabase-js@2";
import { authorized } from "./auth.js";
import { DEFAULT_CONCURRENCY, DEFAULT_MAX } from "./providers.js";
import { runCleanup } from "./run.js";
import { json, pendingClient } from "./wire.ts";

Deno.serve(async (request: Request): Promise<Response> => {
  const env = Deno.env.toObject();

  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }
  if (!authorized(request.headers, env)) {
    return json({ error: "unauthorized" }, 401);
  }
  if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) {
    return json({ error: "not_configured", detail: "SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY" }, 503);
  }

  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const summary = await runCleanup({
      client: pendingClient(supabase),
      env,
      max: Number(env.CLEANUP_MAX) || DEFAULT_MAX,
      concurrency: Number(env.CLEANUP_CONCURRENCY) || DEFAULT_CONCURRENCY,
      log: (line: string) => console.log(line),
    });
    return json(summary, 200);
  } catch (error) {
    console.error("cleanup-provider failed", error);
    const detail = error instanceof Error ? error.message : String(error);
    return json({ error: "cleanup_failed", detail }, 500);
  }
});
