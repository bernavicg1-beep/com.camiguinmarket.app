import { message } from "./http.js";
import { deleteObject } from "./objects.js";
import { DEFAULT_CONCURRENCY, DEFAULT_MAX, providerReady } from "./providers.js";

/**
 * One pass over the queued rows: delete, then report the result through
 * `client.confirm()` so `cleanup_confirm()` can stamp or retry the row.
 *
 * `client` is a narrow interface so the tests need no Supabase client:
 *   listPending(limit) -> rows
 *   confirm(mediaId, ok, error|null) -> void
 */
export async function runCleanup({
  client,
  env = {},
  fetchImpl = globalThis.fetch,
  max = DEFAULT_MAX,
  concurrency = DEFAULT_CONCURRENCY,
  log = () => {},
} = {}) {
  if (!client || typeof client.listPending !== "function" || typeof client.confirm !== "function") {
    throw new Error("runCleanup needs a client with listPending() and confirm()");
  }

  const rows = (await client.listPending(max)) || [];
  const summary = { scanned: rows.length, deleted: 0, failed: 0, skipped: 0, bytesFreed: 0 };

  await mapLimit(rows, concurrency, async (row) => {
    const result = await deleteObject(row, env, fetchImpl);

    if (result.skipped) {
      summary.skipped += 1;
      log(`skip ${row && row.id}: ${result.error || "not eligible"}`);
      return;
    }

    try {
      await client.confirm(row.id, Boolean(result.ok), result.ok ? null : result.error);
    } catch (error) {
      // The object may be gone but the row was not stamped. It stays queued, and
      // the next run re-deletes idempotently (a missing asset counts as success).
      summary.failed += 1;
      log(`confirm ${row.id} failed: ${message(error)}`);
      return;
    }

    if (result.ok) {
      summary.deleted += 1;
      summary.bytesFreed += Number(row.bytes) || 0;
    } else {
      summary.failed += 1;
      log(`delete ${row.id} (${row.provider}) failed: ${result.error}`);
    }
  });

  return summary;
}

async function mapLimit(items, limit, worker) {
  const width = Math.max(1, Math.min(Number(limit) || 1, items.length || 1));
  let cursor = 0;
  const runners = Array.from({ length: width }, async () => {
    while (cursor < items.length) {
      const index = cursor;
      cursor += 1;
      await worker(items[index], index);
    }
  });
  await Promise.all(runners);
}
