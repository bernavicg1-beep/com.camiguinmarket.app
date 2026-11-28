/**
 * Who may call the deleter: the shared cleanup secret (the pg_cron job sends it as
 * x-cleanup-secret) or the service-role key, compared in constant time. When
 * neither is configured, every request is refused.
 */

export function authorized(headers, env = {}) {
  const bearer = /^Bearer\s+(.+)$/i.exec(header(headers, "authorization") || "");
  const offered = [header(headers, "x-cleanup-secret"), bearer ? bearer[1].trim() : ""].filter(
    Boolean,
  );
  if (offered.length === 0) return false;

  const accepted = [env.CLEANUP_SECRET, env.SUPABASE_SERVICE_ROLE_KEY].filter(Boolean);
  if (accepted.length === 0) return false;
  return offered.some((value) => accepted.some((allowed) => constantTimeEqual(value, allowed)));
}

/** Case-insensitive read that works for a Headers instance and for a plain object. */
function header(headers, name) {
  if (!headers) return "";
  if (typeof headers.get === "function") return headers.get(name) || "";
  const key = Object.keys(headers).find((k) => k.toLowerCase() === name);
  return key ? String(headers[key]) : "";
}

function constantTimeEqual(a, b) {
  const left = new TextEncoder().encode(String(a));
  const right = new TextEncoder().encode(String(b));
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let i = 0; i < left.length; i += 1) diff |= left[i] ^ right[i];
  return diff === 0;
}
