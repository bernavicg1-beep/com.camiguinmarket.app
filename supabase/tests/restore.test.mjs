/**
 * The restore path of the storage cleanup: a listing that comes back from the
 * trash keeps its photos.
 *
 * Applies every migration to a real PostgreSQL (PGlite, the WASM build) and
 * checks the trigger in isolation, so it fails if the grace reset, the warning
 * withdrawal or the cycle bump regress. No project, network or credentials.
 *
 *   cd supabase/tests && npm install && npm test
 */
import { readdir, readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { PGlite } from "@electric-sql/pglite";

const here = dirname(fileURLToPath(import.meta.url));
const dir = join(here, "..", "migrations");
const SELLER = "00000000-0000-4000-8000-00000000000b";

const db = await PGlite.create();

// Supabase's own auth schema and roles, stubbed exactly as cleanup.test.mjs does.
await db.exec(`
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  create schema auth;
  create table auth.users (id uuid primary key, email text, raw_app_meta_data jsonb);
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

// Every migration, in order: this suite must run the schema the app really gets.
for (const file of (await readdir(dir)).filter((f) => f.endsWith(".sql")).sort()) {
  await db.exec(await readFile(join(dir, file), "utf8"));
}

let passes = 0;
let failures = 0;

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

async function owner() {
  await db.exec("reset role; set request.jwt.claims = ''");
}

/** Signed in as the seller, with RLS in force — a restore is the seller's action. */
async function asSeller() {
  const claims = JSON.stringify({ sub: SELLER, role: "authenticated" });
  await db.exec(`reset role; set request.jwt.claims = '${claims}'; set role authenticated`);
}

async function one(sql, params) {
  const { rows } = await db.query(sql, params);
  return rows[0];
}
