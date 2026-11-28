// A listing that was restored and then lapses again is warned and queued again.
import assert from "node:assert/strict";
import { asSeller, db, one, owner, photo, product } from "./restore-harness.mjs";

const scan = () => db.query("select public.cleanup_scan('cron', null)");
const notify = () => db.query("select public.cleanup_notify('cron', null)");
const setListing = (sql) => db.query(sql, [product]);

// First lapse: trashed, flagged and warned, then restored, which starts cycle 1.
await asSeller();
await setListing("update public.products set deleted_at = now() where id = $1");
await owner();
await scan();
await notify();
await asSeller();
await setListing("update public.products set deleted_at = null, status = 'active' where id = $1");
await owner();

// Second lapse: archived this time, so the warning must fire again.
await asSeller();
await setListing("update public.products set status = 'archived' where id = $1");
await owner();
await scan();
await notify();

const warned = await one(
  `select count(*)::int as n, array_agg(cleanup_cycle order by cleanup_cycle) as cycles
     from public.cleanup_audit_logs where media_id = $1 and action = 'notified'`,
  [photo],
);
assert.equal(warned.n, 2, "one warning per lapse, not one per file forever");
assert.deepEqual(warned.cycles, [0, 1], "each warning carries its own cycle");

await db.query(
  "update public.product_media set delete_after = now() - interval '1 minute' where id = $1",
  [photo],
);
await db.query("select public.cleanup_queue('cron', null)");
const queued = await one(
  `select count(*)::int as n from public.cleanup_audit_logs
    where media_id = $1 and action = 'queued'`,
  [photo],
);
assert.equal(queued.n, 1, "the second lapse is queued");
const handover = "select count(*)::int as n from public.cleanup_pending_deletions where id = $1";
assert.equal((await one(handover, [photo])).n, 1, "the file is handed to the provider again");

await asSeller();
await setListing("update public.products set status = 'active' where id = $1");
await owner();
const still = await one(
  "select queued_at, deleted_at, cleanup_cycle from public.product_media where id = $1",
  [photo],
);
assert.ok(still.queued_at, "a queued row stays queued");
assert.equal(still.deleted_at, null, "only cleanup_confirm may delete");
assert.equal(still.cleanup_cycle, 1, "a queued row is not given a new cycle");

console.log("ok   a restored listing that lapses again is warned and queued again");
await db.close();
