# Supabase — Camiguin Market backend

This folder is the Supabase half of the Storage Monitor & Automated Old Data
Cleanup feature: the schema, the Row Level Security policies and the daily cron.

Decisions this follows (fixed with the owner):

| Piece | Choice |
|---|---|
| Database, auth, realtime | **Supabase** |
| Backend functions / scheduled automation | **Supabase Edge Functions + `pg_cron`** — Appwrite is dropped, it duplicated Supabase |
| Product images | Cloudinary (later migration step) |
| Product videos / large files | Cloudflare R2 (already live through the Worker) |
| Map | **Leaflet + OpenStreetMap**, unchanged — no key, no billing |

## Apply it

Run the four migrations in order, in the Supabase SQL editor or with the CLI:

```bash
supabase link --project-ref <your-project-ref>
supabase db push
```

or paste each file from `migrations/` into **SQL Editor** in this order:

1. `20261128120000_storage_cleanup_schema.sql` — `products`, `product_media`,
   `cleanup_jobs`, `cleanup_audit_logs`
2. `20261128120100_storage_cleanup_rls.sql` — policies, views, grants
3. `20261128120200_storage_cleanup_functions.sql` — the cleanup engine
4. `20261128120300_storage_cleanup_cron.sql` — the daily job

Everything is written to be safe to run twice.

### Make yourself the admin

The cleanup screens are admin-only, and admin means one JWT claim. There is no
way to grant it from the app:

```sql
update auth.users
   set raw_app_meta_data = jsonb_set(coalesce(raw_app_meta_data, '{}'),
                                     '{role}', '"admin"')
 where email = 'your-admin@example.com';
```

Sign out and back in afterwards so the new claim is in the token.

## What the cleanup does

`cleanup_daily()` runs once a day at **02:15 UTC (10:15 AM Philippine time)**
via `pg_cron`, and does three things in order:

| Step | What it does | Deletes bytes? |
|---|---|---|
| `cleanup_scan()` | Classifies every live media row; starts the grace clock; logs new candidates | no |
| `cleanup_notify()` | Writes one reminder per file inside its last 7 days of grace | no |
| `cleanup_queue()` | Freezes rows whose `delete_after` has passed | no |

Deletion is done by `cleanup_confirm(media_id, ok, error)` — called by the Edge
Function that holds the Cloudinary / R2 credentials. Only then is
`product_media.deleted_at` set. **Postgres never deletes an object by itself, and
no provider secret is ever stored in the database.**

Who gets cleaned, and when:

| Reason | When a file qualifies | Earliest deletion |
|---|---|---|
| `orphan` | No live listing: never attached to one, or the listing was soft-deleted / removed / archived | 7 days after it qualified |
| `expired` | The listing's `expires_at` has passed | 7 days after it qualified |
| `stale` | The seller has not touched the listing for **30 days** | 7 days after it qualified |

The 30-day/expiry clock decides *whether* a file qualifies; the **7-day grace**
is measured from the moment it qualified, so nothing is purged without a week of
warning — even a listing that has already been stale for months.

Safeguards:

- **Scanning never deletes.** Three of the four steps only write bookkeeping.
- **One reminder per file.** `product_media.notified_at` plus a unique audit index
  (`cleanup_audit_logs_once_per_action`) make the cron idempotent: retries,
  overlapping runs and manual runs cannot double-notify or double-queue.
- **Failures are retried, not swallowed.** A failed provider delete keeps the
  bytes, records `last_error`, and retries a day later; it shows up in the admin
  history as `failed`.
- **No invented numbers.** `storage_usage` sums the real `bytes` column, which the
  uploader records from the provider's response. There is no hardcoded quota.
- **Nothing is scheduled if `pg_cron` is missing** — the migration raises a notice
  instead of failing silently.

## Dashboard data

| View | Who sees what |
|---|---|
| `storage_usage` | Per seller, per provider: total / live / in-grace / queued / deleted items and bytes. A seller sees only their own row; an admin sees every row. |
| `my_cleanup_history` | The seller's own audit trail for the "Clean Old Files" page. |
| `cleanup_pending_deletions` | What the provider deleter must remove now, oldest first. |
| `cleanup_jobs`, `cleanup_audit_logs` | Admin-only, read-only. Full run history and per-file decisions. |

A seller can delete their own files immediately with
`select public.seller_delete_media(array['<media-id>'])` — that waives their own
grace, freezes the row and queues it in one write. Admin tools call
`select public.admin_run_cleanup('scan' | 'notify' | 'queue' | 'all')`.

## RLS at a glance

- `products` — a seller reads, creates, edits and deletes only rows where
  `seller_id = auth.uid()`; `with check` repeats it so a listing cannot be handed
  to another seller. An admin can do everything.
- `product_media` — same owner rule, and a seller may only attach media to their
  own listing. The cleanup columns (`delete_after`, `queued_at`, `deleted_at`,
  `bytes`, `provider`, `storage_key`) are **not in the column grant**, so even an
  allowed update cannot rewrite them. A row that is queued or deleted is frozen.
- `cleanup_jobs`, `cleanup_audit_logs` — `SELECT` for admins only, no write policy
  at all: only the engine (SECURITY DEFINER) and `service_role` write them.
- `anon` has no access to anything in this schema.
- The engine functions are revoked from `authenticated`; only
  `seller_delete_media()` and `admin_run_cleanup()` are callable by a user.

## Keys

| Key | Where it goes | Never |
|---|---|---|
| `SUPABASE_URL` | Android `BuildConfig` / `.env` | — |
| `SUPABASE_ANON_KEY` | Android `BuildConfig` / `.env` | — |
| `SUPABASE_SERVICE_ROLE_KEY` | GitHub Actions secret and the Edge Function's own environment | in the APK, in the repo, in this chat |

## Tests

The migrations are exercised against a real PostgreSQL (PGlite — the WASM build)
with Supabase's `auth` schema and `anon` / `authenticated` / `service_role` roles
stubbed out, so no project or credentials are needed:

```bash
cd supabase/tests
npm install
npm test
```

25 checks: seller isolation, anon denial, the column grant, product/media
ownership, orphan/expired/stale classification, the 7-day grace, scan and notify
idempotency, queue-then-confirm, retry on provider failure, storage totals per
seller vs admin, seller opt-in, and admin-only engine access. All pass.

## Still to come (not in this change)

1. Edge Function `cleanup-provider`: reads `cleanup_pending_deletions`, deletes the
   Cloudinary / R2 object with the service key, calls `cleanup_confirm()`. It needs
   real credentials, so it is the next step once the Supabase project exists.
2. Push notification for the `notified` rows (the Worker already sends FCM).
3. Copying Firebase `listings` into `products` / `product_media`, and the seller
   "Clean Old Files" and admin "Storage Monitor" screens.
