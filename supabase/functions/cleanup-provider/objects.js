// Delete one queued object wherever it is stored. `fetch` is injected and every
// timeout or refusal becomes a plain result: the caller always gets a report.
import { message, parseJson, safeText, timeoutSignal } from "./http.js";
import { cloudinaryRequest, providerReady, r2Request } from "./providers.js";

// `{ ok: true }` when the bytes are gone (or already were), `{ ok: false, error }`
// when the provider refused, `{ skipped: true }` for a row never to be attempted.
export async function deleteObject(row, env = {}, fetchImpl = globalThis.fetch) {
  if (!row || !row.id) return { skipped: true, error: "row has no id" };
  if (!row.storage_key) return { ok: false, error: "row has no storage_key" };
  // Belt and braces: the view already filters, but nothing may be deleted
  // unless it was queued for deletion.
  if (!row.queued_at) return { skipped: true, error: "row is not queued" };
  if (!providerReady(row.provider, env)) {
    return { ok: false, error: `${row.provider}: credentials are not configured` };
  }

  const request = row.provider === "cloudinary" ? cloudinaryRequest(env, row) : r2Request(env, row);

  let response;
  try {
    response = await fetchImpl(request.url, { ...request.init, signal: timeoutSignal() });
  } catch (error) {
    return { ok: false, error: `network: ${message(error)}` };
  }

  const body = await safeText(response);
  if (response.status === 404) return { ok: true, alreadyGone: true };

  if (!response.ok) {
    return { ok: false, error: `${row.provider} ${response.status}: ${body.slice(0, 300)}` };
  }

  if (row.provider === "cloudinary") {
    const parsed = parseJson(body);
    if (!parsed) return { ok: false, error: `cloudinary: unreadable response ${body.slice(0, 200)}` };
    if (parsed.error) return { ok: false, error: `cloudinary: ${parsed.error.message || "error"}` };
    const outcome = (parsed.deleted || {})[row.storage_key];
    if (outcome === undefined) {
      return { ok: false, error: `cloudinary did not report ${row.storage_key}` };
    }
    // "not_found" means the asset is already gone, which is the outcome we want.
    return { ok: true, alreadyGone: outcome !== "deleted" };
  }

  const parsed = parseJson(body);
  if (parsed && parsed.success === false) {
    const errors = (parsed.errors || []).map((e) => e.message).filter(Boolean).join("; ");
    return { ok: false, error: `r2: ${errors || body.slice(0, 200)}` };
  }
  return { ok: true };
}
