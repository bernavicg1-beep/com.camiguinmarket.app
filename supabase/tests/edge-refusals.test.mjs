// Rows and environments that must never reach a provider.
import assert from "node:assert/strict";
import { ENV, deleteObject, fakeFetch, ok, providerReady, report, row, test } from "./edge-harness.mjs";

await test("missing credentials never touch the network", async () => {
  const { fetchImpl, calls } = fakeFetch([ok({ success: true })]);
  const result = await deleteObject(row(), { ...ENV, R2_API_TOKEN: "" }, fetchImpl);
  assert.equal(result.ok, false);
  assert.match(result.error, /r2: credentials are not configured/);
  assert.equal(calls.length, 0);
});

await test("a row that was never queued is skipped, not deleted", async () => {
  const { fetchImpl, calls } = fakeFetch([ok({ success: true })]);
  const result = await deleteObject(row({ queued_at: null }), ENV, fetchImpl);
  assert.equal(result.skipped, true);
  assert.equal(calls.length, 0);
});

await test("provider readiness only needs that provider's own keys", async () => {
  assert.equal(providerReady("r2", ENV), true);
  assert.equal(providerReady("cloudinary", ENV), true);
  assert.equal(providerReady("r2", { ...ENV, R2_BUCKET: "" }), false);
  assert.equal(providerReady("cloudinary", { ...ENV, CLOUDINARY_API_SECRET: "" }), false);
  assert.equal(providerReady("s3", ENV), false);
});

report();
