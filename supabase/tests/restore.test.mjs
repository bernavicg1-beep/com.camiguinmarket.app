// The fix under test: a listing that comes back from the trash keeps its photos.
import assert from "node:assert/strict";
import { asSeller, db, one, owner, photo, product } from "./restore-harness.mjs";

const scan = () => db.query("select public.cleanup_scan('cron', null)");
const notify = () => db.query("select public.cleanup_notify('cron', null)");

await asSeller();
await db.query("update public.products set deleted_at = now() where id = $1", [product]);
await owner();
await scan();
await notify();

const flagged = await one(
  `select reason, delete_after, notified_at
     from public.media_cleanup_candidates where media_id = $1`,
  [photo],
);
assert.equal(flagged.reason, "orphan", "a soft-deleted listing orphans its media");
assert.ok(flagged.delete_after, "the grace clock started");
assert.ok(flagged.notified_at, "the seller was warned");

// The seller changes their mind and puts the listing back.
await asSeller();
await db.query("update public.products set deleted_at = null, status = 'active' where id = $1", [
  product,
]);
await owner();

const row = await one("select * from public.product_media where id = $1", [photo]);
assert.equal(row.orphaned_at, null, "no longer an orphan");
assert.equal(row.grace_until, null, "no grace running");
assert.equal(row.delete_after, null, "no deletion scheduled");
assert.equal(row.notified_at, null, "the warning was withdrawn");
assert.equal(row.notify_count, 0, "the warning count restarts");
assert.equal(row.cleanup_cycle, 1, "the next lapse starts a new cycle");

const logged = await one(
  `select count(*)::int as n, min(detail) as detail from public.cleanup_audit_logs
    where media_id = $1 and action = 'restored'`,
  [photo],
);
assert.equal(logged.n, 1, "the restore is in the history");
assert.match(logged.detail, /listing restored to active/);

await scan();
const after = await one(
  "select reason from public.media_cleanup_candidates where media_id = $1",
  [photo],
);
assert.equal(after.reason, null, "a later scan keeps the restored file");

console.log("ok   restoring a soft-deleted listing resets the grace on its media");
await db.close();
