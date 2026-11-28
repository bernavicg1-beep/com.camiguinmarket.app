// The pass must not fire every delete at once.
import assert from "node:assert/strict";
import { ENV, report, row, runCleanup, test } from "./edge-harness.mjs";

await test("the pass honours its concurrency limit", async () => {
  let inFlight = 0;
  let peak = 0;
  const rows = Array.from({ length: 9 }, () => row());
  const confirmed = [];
  const client = {
    listPending: async () => rows,
    confirm: async (id, okFlag) => confirmed.push({ id, ok: okFlag }),
  };
  const fetchImpl = async () => {
    inFlight += 1;
    peak = Math.max(peak, inFlight);
    await new Promise((resolve) => setTimeout(resolve, 5));
    inFlight -= 1;
    return new Response(JSON.stringify({ success: true }), { status: 200 });
  };

  const summary = await runCleanup({ client, env: ENV, fetchImpl, concurrency: 3 });

  assert.equal(summary.deleted, 9);
  assert.equal(confirmed.length, 9);
  assert.ok(peak <= 3, `peak concurrency was ${peak}`);
});

report();
