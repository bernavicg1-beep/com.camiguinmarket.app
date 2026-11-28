/**
 * Runs the Supabase migrations against a real PostgreSQL (PGlite, the WASM
 * build) and checks the cleanup engine and the RLS policies behave.
 *
 *   cd supabase/tests && npm install && npm test
 *
 * No Supabase project, network or credentials are needed. Supabase's own
 * `auth` schema and the anon/authenticated/service_role roles are stood up as
 * small stubs so the migrations run exactly as written.
 */
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { PGlite } from "@electric-sql/pglite";

const here = dirname(fileURLToPath(import.meta.url));
const migrations = [
  "20261128120000_storage_cleanup_schema.sql",
  "20261128120100_storage_cleanup_rls.sql",
  "20261128120200_storage_cleanup_functions.sql",
  "20261128120300_storage_cleanup_cron.sql",
];

const ADMIN = "00000000-0000-4000-8000-00000000000a";
const SELLER_A = "00000000-0000-4000-8000-00000000000b";
const SELLER_B = "00000000-0000-4000-8000-00000000000c";

const db = await PGlite.create();

// --- Supabase stubs ---------------------------------------------------------
await db.exec(`
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;

  create schema auth;

  create table auth.users (
    id uuid primary key,
    email text,
    raw_app_meta_data jsonb
  );

  create function auth.jwt() returns jsonb language sql stable as $$
    select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb
  $$;

  create function auth.uid() returns uuid language sql stable as $$
    select nullif(auth.jwt() ->> 'sub', '')::uuid
  $$;

  grant usage on schema auth to anon, authenticated;
  grant execute on function auth.jwt() to anon, authenticated;
  grant execute on function auth.uid() to anon, authenticated;
`);

for (const file of migrations) {
  const sql = await readFile(join(here, "..", "migrations", file), "utf8");
  await db.exec(sql);
}

// --- helpers ---------------------------------------------------------------
let failures = 0;
let passes = 0;

async function test(name, fn) {
  try {
    await fn();
    passes += 1;
    console.log(`ok   ${name}`);
  } catch (error) {
    failures += 1;
    console.log(`FAIL ${name}\n     ${error.message.split("\n")[0]}`);
  }
}

/**
 * Run queries as a signed-in user (RLS applies; the role is not the owner).
 * The admin's JWT carries app_metadata.role = admin, exactly as Supabase
 * issues it, which is what public.is_admin() reads.
 */
async function as(uid) {
  const claims = { sub: uid, role: "authenticated" };
  if (uid === ADMIN) claims.app_metadata = { role: "admin" };
  await db.exec(`reset role; set request.jwt.claims = '${JSON.stringify(claims)}'`);
  await db.exec("set role authenticated");
}

async function anon() {
  await db.exec("reset role; set request.jwt.claims = ''; set role anon");
}

async function owner() {
  await db.exec("reset role; set request.jwt.claims = ''");
}

async function one(sql, params) {
  const { rows } = await db.query(sql, params);
  return rows[0];
}

async function all(sql, params) {
  const { rows } = await db.query(sql, params);
  return rows;
}

async function rejects(sql, params, why) {
  let threw = false;
  try {
    await db.query(sql, params);
  } catch {
    threw = true;
  }
  assert.ok(threw, `expected a rejection: ${why}`);
}

// --- seed ------------------------------------------------------------------
await owner();

for (const [id, email, role] of [
  [ADMIN, "admin@example.com", "admin"],
  [SELLER_A, "a@example.com", null],
  [SELLER_B, "b@example.com", null],
]) {
  await db.query(
    "insert into auth.users (id, email, raw_app_meta_data) values ($1, $2, $3)",
    [id, email, role ? JSON.stringify({ role }) : "{}"],
  );
}

// seller A: one live listing, one stale listing, one expired listing
const daysAgo = (n) => new Date(Date.now() - n * 86_400_000);

const insertProduct = async (seller, title, extraCols = [], extraValues = []) => {
  const cols = extraCols.length ? `, ${extraCols.join(", ")}` : "";
  const slot = extraValues.map((_, i) => `$${i + 3}`).join(", ");
  const values = extraValues.length ? `, ${slot}` : "";
  const { rows } = await db.query(
    `insert into public.products (seller_id, title, price, contact${cols})
     values ($1, $2, 10.00, '09171234567'${values}) returning id`,
    [seller, title, ...extraValues],
  );
  return rows[0].id;
};

const liveA = await insertProduct(SELLER_A, "Live fish");
const staleA = await insertProduct(SELLER_A, "Old stock", ["updated_at"], [daysAgo(40)]);
const expiredA = await insertProduct(SELLER_A, "Christmas ham", ["expires_at"], [daysAgo(1)]);
const liveB = await insertProduct(SELLER_B, "B's buko");

// media: one on each, plus an abandoned upload with no product
const media = async (productId, seller, key, bytes = 1000, created = "now()") =>
  (
    await one(
      `insert into public.product_media (product_id, seller_id, provider, storage_key, public_url, bytes, created_at)
       values ($1, $2, 'r2', $3, $4, $5, ${created}) returning id`,
      [productId, seller, key, `https://worker.example/i/listing_images/${seller}/${key}`, bytes],
    )
  ).id;

const mLive = await media(liveA, SELLER_A, "live.jpg");
const mStale = await media(staleA, SELLER_A, "stale.jpg", 2000);
const mExpired = await media(expiredA, SELLER_A, "expired.jpg", 3000);
const mOrphan = await media(null, SELLER_A, "abandoned.jpg", 4000, "now() - interval '3 days'");
const mLiveB = await media(liveB, SELLER_B, "buko.jpg", 5000);

// --- RLS -------------------------------------------------------------------
await test("a seller only sees their own products", async () => {
  await as(SELLER_A);
  const rows = await all("select id from public.products order by title");
  await owner();
  assert.equal(rows.length, 3);
  assert.ok(!rows.some((r) => r.id === liveB));
});

await test("another seller cannot insert a product in someone else's name", async () => {
  await as(SELLER_B);
  await rejects(
    "insert into public.products (seller_id, title, price) values ($1, 'fake', 1)",
    [SELLER_A],
    "seller_id must be the caller",
  );
  await owner();
});

await test("a seller cannot attach media to another seller's product", async () => {
  await as(SELLER_A);
  await rejects(
    `insert into public.product_media (product_id, seller_id, provider, storage_key, public_url)
     values ($1, $2, 'r2', 'steal.jpg', 'https://x/steal.jpg')`,
    [liveB, SELLER_A],
    "product belongs to seller B",
  );
  await owner();
});

await test("a seller cannot move their product to another seller", async () => {
  await as(SELLER_A);
  await rejects(
    "update public.products set seller_id = $1 where id = $2",
    [SELLER_B, liveA],
    "seller_id is checked on update",
  );
  await owner();
});

await test("a seller cannot rewrite cleanup bookkeeping columns", async () => {
  await as(SELLER_A);
  await rejects(
    "update public.product_media set delete_after = null where id = $1",
    [mLive],
    "delete_after is not in the column grant",
  );
  await owner();
});

await test("cleanup history is admin-only and read-only", async () => {
  await as(SELLER_A);
  const rows = await all("select count(*)::int as n from public.cleanup_audit_logs");
  await owner();
  assert.equal(rows[0].n, 0, "a seller sees no audit rows");

  await as(SELLER_A);
  await rejects(
    "insert into public.cleanup_audit_logs (action) values ('candidate')",
    [],
    "no insert grant for authenticated",
  );
  await owner();
});

await test("anonymous visitors are denied", async () => {
  await anon();
  await rejects("select * from public.products", [], "anon has no select grant");
  await owner();
});

// --- engine ----------------------------------------------------------------
const scanA = await one("select public.cleanup_scan('cron', null) as job");

await test("scan flags orphan, expired and stale media, and leaves live media alone", async () => {
  const rows = await all(
    "select media_id, reason from public.media_cleanup_candidates order by media_id",
  );
  const byId = new Map(rows.map((r) => [r.media_id, r.reason]));
  assert.equal(byId.get(mOrphan), "orphan");
  assert.equal(byId.get(mExpired), "expired");
  assert.equal(byId.get(mStale), "stale");
  assert.equal(byId.get(mLive), null);
  assert.equal(byId.get(mLiveB), null);
});

await test("every candidate gets a full 7-day grace", async () => {
  const rows = await all(
    `select id, orphaned_at, delete_after,
            extract(epoch from (delete_after - now())) / 86400 as days
       from public.product_media where orphaned_at is not null`,
  );
  assert.equal(rows.length, 3, "three candidates, not five");
  for (const row of rows) {
    assert.ok(Number(row.days) > 6.9, `grace is 7 days, got ${row.days}`);
    assert.ok(row.orphaned_at);
  }
});

await test("scan logs each new candidate once, even when repeated", async () => {
  const before = await one(
    "select count(*)::int as n from public.cleanup_audit_logs where action = 'candidate'",
  );
  assert.equal(before.n, 3);
  await db.query("select public.cleanup_scan('cron', null)");
  await db.query("select public.cleanup_scan('cron', null)");
  const after = await one(
    "select count(*)::int as n from public.cleanup_audit_logs where action = 'candidate'",
  );
  assert.equal(after.n, 3, "a repeat scan must not re-log candidates");
});

await test("the scan job records what it did", async () => {
  const job = await one("select * from public.cleanup_jobs where id = $1", [scanA.job]);
  assert.equal(job.status, "succeeded");
  assert.equal(job.kind, "scan");
  assert.equal(job.candidates, 3);
  assert.ok(job.scanned_products >= 4);
  assert.ok(job.finished_at);
});

await test("notify reminds each file exactly once", async () => {
  await db.query("select public.cleanup_notify('cron', null)");
  const first = await one(
    "select count(*)::int as n from public.cleanup_audit_logs where action = 'notified'",
  );
  assert.equal(first.n, 3);
  await db.query("select public.cleanup_notify('cron', null)");
  await db.query("select public.cleanup_notify('cron', null)");
  const again = await one(
    "select count(*)::int as n from public.cleanup_audit_logs where action = 'notified'",
  );
  assert.equal(again.n, 3, "no duplicate reminders");
  const counters = await all(
    "select notify_count from public.product_media where notified_at is not null",
  );
  assert.equal(counters.length, 3);
  for (const c of counters) assert.equal(c.notify_count, 1);
});

await test("nothing is queued while the grace is still running", async () => {
  const job = await one("select public.cleanup_queue('cron', null) as id");
  const row = await one("select queued from public.cleanup_jobs where id = $1", [job.id]);
  assert.equal(row.queued, 0);
  const queued = await one(
    "select count(*)::int as n from public.product_media where queued_at is not null",
  );
  assert.equal(queued.n, 0);
});

await test("a file past its delete_after is queued, frozen, and left undeleted until confirmed", async () => {
  await owner();
  await db.query(
    "update public.product_media set delete_after = now() - interval '1 minute' where id = $1",
    [mOrphan],
  );
  const job = await one("select public.cleanup_queue('cron', null) as id");

  const row = await one("select * from public.product_media where id = $1", [mOrphan]);
  assert.ok(row.queued_at, "queued_at is set");
  assert.equal(row.deleted_at, null, "bytes are not deleted by the queue step");

  const logged = await one(
    "select count(*)::int as n from public.cleanup_audit_logs where media_id = $1 and action = 'queued'",
    [mOrphan],
  );
  assert.equal(logged.n, 1);

  const pending = await all("select id from public.cleanup_pending_deletions");
  assert.ok(pending.some((p) => p.id === mOrphan));

  const jobRow = await one("select queued, bytes_freed from public.cleanup_jobs where id = $1", [
    job.id,
  ]);
  assert.equal(jobRow.queued, 1);
  assert.equal(Number(jobRow.bytes_freed), 4000);
});

await test("the provider deleter cannot be run by a seller", async () => {
  await as(SELLER_A);
  await rejects(
    "select public.cleanup_confirm($1, true, null, null)",
    [mOrphan],
    "cleanup_confirm is engine-only",
  );
  await owner();
});

await test("a confirmed delete stamps deleted_at and the audit trail", async () => {
  const ok = await one("select public.cleanup_confirm($1, true, null, null) as ok", [mOrphan]);
  assert.equal(ok.ok, true);
  const row = await one("select * from public.product_media where id = $1", [mOrphan]);
  assert.ok(row.deleted_at);
  const logged = await one(
    "select bytes, action from public.cleanup_audit_logs where media_id = $1 and action = 'deleted'",
    [mOrphan],
  );
  assert.equal(Number(logged.bytes), 4000);
});

await test("a failed delete keeps the bytes and retries tomorrow", async () => {
  await db.query(
    "update public.product_media set delete_after = now() - interval '1 minute' where id = $1",
    [mStale],
  );
  await db.query("select public.cleanup_queue('cron', null)");
  await one("select public.cleanup_confirm($1, false, 'r2 503', null) as ok", [mStale]);

  const row = await one("select * from public.product_media where id = $1", [mStale]);
  assert.equal(row.deleted_at, null, "bytes stay while the provider failed");
  assert.equal(row.queued_at, null, "unfrozen for the retry");
  assert.equal(row.delete_attempts, 1);
  assert.equal(row.last_error, "r2 503");
  const days = await one(
    "select extract(epoch from (delete_after - now())) / 86400 as days from public.product_media where id = $1",
    [mStale],
  );
  assert.ok(Number(days.days) > 0.9, "retried a day later");
});

await test("storage_usage reports live vs reclaimable bytes", async () => {
  await as(SELLER_A);
  const row = await one("select * from public.storage_usage");
  await owner();
  assert.equal(row.provider, "r2");
  assert.equal(Number(row.total_items), 4);
  assert.equal(Number(row.deleted_items), 1);
  assert.equal(Number(row.bytes_reclaimable), 2000 + 3000, "stale + expired still held");
  assert.equal(Number(row.bytes_deleted), 4000);
});

await test("a seller only sees their own usage", async () => {
  await as(SELLER_B);
  const rows = await all("select * from public.storage_usage");
  await owner();
  assert.equal(rows.length, 1);
  assert.equal(Number(rows[0].total_items), 1);
  assert.equal(Number(rows[0].bytes_live), 5000);
});

await test("the admin sees every seller's usage and the job history", async () => {
  await as(ADMIN);
  const usage = await all("select * from public.storage_usage");
  const jobs = await all("select id, kind, status from public.cleanup_jobs");
  const audit = await all("select count(*)::int as n from public.cleanup_audit_logs");
  await owner();
  assert.equal(usage.length, 2, `admin should see 2 usage rows, saw ${usage.length}`);
  assert.ok(jobs.length >= 5, `admin should see the job history, saw ${jobs.length}`);
  assert.ok(audit[0].n >= 7, `admin should see the audit trail, saw ${audit[0].n}`);
});

await test("a seller can only delete their own files from Clean Old Files", async () => {
  await as(SELLER_A);
  const queued = await one("select public.seller_delete_media(array[$1::uuid, $2::uuid]) as n", [
    mLive,
    mLiveB,
  ]);
  await owner();
  assert.equal(Number(queued.n), 1, "seller B's file is untouched");

  const mine = await one(
    "select *, grace_until <= now() as waived from public.product_media where id = $1",
    [mLive],
  );
  const theirs = await one("select * from public.product_media where id = $1", [mLiveB]);
  assert.ok(mine.queued_at, "own file queued immediately");
  assert.equal(mine.waived, true, "the seller waived their own grace");
  assert.equal(theirs.queued_at, null);
  assert.equal(theirs.deleted_at, null);

  const reason = await one(
    "select reason, detail from public.cleanup_audit_logs where media_id = $1 and action = 'queued'",
    [mLive],
  );
  assert.equal(reason.reason, "seller_request");
});

await test("admin_run_cleanup runs the daily pass, and only for admins", async () => {
  await as(SELLER_A);
  await rejects("select public.admin_run_cleanup('all')", [], "seller is not an admin");
  await owner();

  await as(ADMIN);
  const out = await one("select public.admin_run_cleanup('all') as out");
  await owner();
  assert.match(out.out, /all finished/);
});

await test("cleanup_daily runs scan, notify and queue in order", async () => {
  const before = await one("select count(*)::int as n from public.cleanup_jobs");
  const out = await one("select public.cleanup_daily('cron', null) as out");
  const after = await one("select count(*)::int as n from public.cleanup_jobs");
  assert.match(out.out, /^scan .*, notify .*, queue /);
  assert.equal(after.n - before.n, 3, "one job row per step");
});

await test("the engine cannot be called with the anon key", async () => {
  await anon();
  await rejects("select public.cleanup_scan('cron', null)", [], "anon has no execute");
  await rejects("select public.cleanup_daily('cron', null)", [], "anon has no execute");
  await owner();
});

await test("soft-deleting a listing turns its media into orphans on the next scan", async () => {
  await as(SELLER_A);
  await db.query("update public.products set deleted_at = now() where id = $1", [liveA]);
  await owner();
  await db.query("select public.cleanup_scan('cron', null)");
  const row = await one(
    "select reason from public.media_cleanup_candidates where media_id = $1",
    [mLive],
  );
  assert.equal(row.reason, "orphan");
});

console.log(`\n${passes} passed, ${failures} failed`);
if (failures) process.exit(1);
await db.close();
