// What counts as gone, and what counts as a refusal to report tomorrow.
import assert from "node:assert/strict";
import { ENV, deleteObject, fakeFetch, ok, report, row, test } from "./edge-harness.mjs";

await test("a cloudinary asset that is already gone counts as deleted", async () => {
  const { fetchImpl } = fakeFetch([ok({ deleted: { "camiguin/products/abc": "not_found" } })]);
  const result = await deleteObject(
    row({ provider: "cloudinary", storage_key: "camiguin/products/abc" }),
    ENV,
    fetchImpl,
  );
  assert.equal(result.ok, true);
  assert.equal(result.alreadyGone, true);
});

await test("a 404 means the bytes are already gone", async () => {
  const { fetchImpl } = fakeFetch([{ status: 404, body: "not found" }]);
  const result = await deleteObject(row(), ENV, fetchImpl);
  assert.equal(result.ok, true);
  assert.equal(result.alreadyGone, true);
});

await test("a provider failure is reported, never swallowed", async () => {
  const { fetchImpl } = fakeFetch([{ status: 500, body: "upstream exploded" }]);
  const result = await deleteObject(row(), ENV, fetchImpl);
  assert.equal(result.ok, false);
  assert.match(result.error, /r2 500/);
  assert.match(result.error, /upstream exploded/);
});

await test("a cloudinary error envelope fails the delete", async () => {
  const { fetchImpl } = fakeFetch([ok({ error: { message: "rate limited" } })]);
  const result = await deleteObject(
    row({ provider: "cloudinary", storage_key: "camiguin/products/abc" }),
    ENV,
    fetchImpl,
  );
  assert.equal(result.ok, false);
  assert.match(result.error, /rate limited/);
});

await test("a network error fails the delete for a retry tomorrow", async () => {
  const fetchImpl = async () => {
    throw new Error("connect timeout");
  };
  const result = await deleteObject(row(), ENV, fetchImpl);
  assert.equal(result.ok, false);
  assert.match(result.error, /network: connect timeout/);
});

report();
