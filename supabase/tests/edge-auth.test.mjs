// Who may call the function at all.
import assert from "node:assert/strict";
import { ENV, authorized, report, test } from "./edge-harness.mjs";

await test("the cron secret and the service key are accepted, nothing else", async () => {
  assert.equal(authorized(new Headers({ "x-cleanup-secret": "cron-secret" }), ENV), true);
  assert.equal(authorized(new Headers({ authorization: "Bearer service-role-key" }), ENV), true);
  assert.equal(authorized(new Headers({ authorization: "Bearer nope" }), ENV), false);
  assert.equal(authorized(new Headers({ "x-cleanup-secret": "nope" }), ENV), false);
  assert.equal(authorized(new Headers(), ENV), false);
  assert.equal(authorized(undefined, ENV), false);
});

await test("a function with no secret configured refuses everyone", async () => {
  const bare = { ...ENV, CLEANUP_SECRET: "", SUPABASE_SERVICE_ROLE_KEY: "" };
  assert.equal(authorized(new Headers({ "x-cleanup-secret": "cron-secret" }), bare), false);
  assert.equal(authorized(new Headers({ authorization: "Bearer service-role-key" }), bare), false);
});

report();
