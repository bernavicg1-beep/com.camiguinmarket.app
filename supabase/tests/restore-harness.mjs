// Shared setup for the restore suites: PGlite with every migration applied, the
// Supabase auth stubs, one seller and one live listing with its photo.
import { readdir, readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { PGlite } from "@electric-sql/pglite";

const dir = join(dirname(fileURLToPath(import.meta.url)), "..", "migrations");
const SELLER = "00000000-0000-4000-8000-00000000000b";
export const db = await PGlite.create();

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

// Every migration, in order: these suites run the schema the app really gets.
for (const f of (await readdir(dir)).filter((n) => n.endsWith(".sql")).sort()) {
  await db.exec(await readFile(join(dir, f), "utf8"));
}

export async function owner() {
  await db.exec("reset role; set request.jwt.claims = ''");
}

export function asSeller() {
  const claims = JSON.stringify({ sub: SELLER, role: "authenticated" });
  return db.exec(`reset role; set request.jwt.claims = '${claims}'; set role authenticated`);
}

export async function one(sql, params) {
  const { rows } = await db.query(sql, params);
  return rows[0];
}

await db.query("insert into auth.users (id, email) values ($1, $2)", [SELLER, "a@example.com"]);
export const product = (
  await one(
    `insert into public.products (seller_id, title, price, contact)
     values ($1, 'Restored bangus', 10.00, '09171234567') returning id`,
    [SELLER],
  )
).id;
export const photo = (
  await one(
    `insert into public.product_media
       (product_id, seller_id, provider, storage_key, public_url, bytes)
     values ($1, $2, 'r2', 'restored.jpg', 'https://worker.example/i/l/r.jpg', 6000)
     returning id`,
    [product, SELLER],
  )
).id;
