// One pass over the queue: every attempt ends in cleanup_confirm().
import assert from "node:assert/strict";
import { ENV, fakeFetch, ok, report, row, runCleanup, test } from "./edge-harness.mjs";

await test("each attempt ends in cleanup_confirm with the provider's verdict", async () => {
  const confirmed = [];
  const client = {
    listPending: async () => [
      row({ bytes: 4000 }),
      row({ provider: "cloudinary", storage_key: "camiguin/products/abc", bytes: 900 }),
    ],
    confirm: async (id, okFlag, error) => confirmed.push({ id, ok: okFlag, error }),
  };
  const { fetchImpl, calls } = fakeFetch([
    ok({ success: true, result: {} }),
    { status: 503, body: "cloudinary is down" },
  ]);

  const summary = await runCleanup({ client, env: ENV, fetchImpl });

  assert.equal(calls.length, 2);
  assert.equal(confirmed.length, 2);
  assert.equal(confirmed[0].ok, true);
  assert.equal(confirmed[0].error, null);
  assert.equal(confirmed[1].ok, false);
  assert.match(confirmed[1].error, /cloudinary 503/);
  assert.deepEqual(summary, { scanned: 2, deleted: 1, failed: 1, skipped: 0, bytesFreed: 4000 });
});

await test("a failed confirm does not abort the rest of the pass", async () => {
  const rows = [row(), row(), row()];
  const confirmed = [];
  const client = {
    listPending: async () => rows,
    confirm: async (id, okFlag) => {
      if (id === rows[0].id) throw new Error("rpc unavailable");
      confirmed.push({ id, ok: okFlag });
    },
  };
  const { fetchImpl } = fakeFetch([ok({ success: true })]);

  const summary = await runCleanup({ client, env: ENV, fetchImpl });

  assert.equal(summary.failed, 1, "the unconfirmed row is reported");
  assert.equal(summary.deleted, 2, "the other two were still handled");
  assert.equal(confirmed.length, 2);
});

await test("an empty queue is a quiet no-op", async () => {
  const summary = await runCleanup({
    client: { listPending: async () => [], confirm: async () => {} },
    env: ENV,
    fetchImpl: async () => {
      throw new Error("must not be called");
    },
  });
  assert.deepEqual(summary, { scanned: 0, deleted: 0, failed: 0, skipped: 0, bytesFreed: 0 });
});

report();
