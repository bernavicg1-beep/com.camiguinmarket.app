-- Camiguin Market — Supabase core schema for listings and their media.
--
-- This is the database half of the Storage Monitor / Old Data Cleanup feature.
-- It is additive: the Firebase project keeps running while these tables are
-- filled, so nothing here touches live Firebase data.
--
-- Four tables, as specified:
--   products            a listing (the thing a seller posts)
--   product_media       one row per image/video the listing owns
--   cleanup_jobs        one row per scan / queue / notify pass
--   cleanup_audit_logs  append-only record of every decision about every file
--
-- Storage rules the cleanup engine reads from these tables (see
-- 20261128120200_storage_cleanup_functions.sql for the implementation):
--   orphan   — media with no live product (never attached, or product removed).
--   expired  — media of a product whose `expires_at` has passed.
--   stale    — media of a product not touched in 30 days.
--   grace    — nothing is deleted before 7 days from the moment it qualified.
--
-- Media bytes are deleted by the provider (Cloudinary / R2), never by Postgres,
-- so `product_media.deleted_at` is only set after the provider confirms.

-- gen_random_uuid() is built into PostgreSQL 13+, so no extension is needed.

-- ---------------------------------------------------------------------------
-- products
-- ---------------------------------------------------------------------------
create table if not exists public.products (
  id            uuid primary key default gen_random_uuid(),
  seller_id     uuid not null references auth.users (id) on delete cascade,
  title         text not null check (char_length(btrim(title)) between 1 and 200),
  description   text check (description is null or char_length(description) <= 4000),
  price         numeric(12, 2) not null check (price >= 0),
  contact       text check (contact is null or char_length(contact) <= 60),
  category      text,
  town          text,
  latitude      double precision check (latitude is null or latitude between -90 and 90),
  longitude     double precision check (longitude is null or longitude between -180 and 180),
  stock         integer not null default -1 check (stock >= -1),
  image_url     text,
  views         integer not null default 0 check (views >= 0),
  cart_adds     integer not null default 0 check (cart_adds >= 0),
  status        text not null default 'active'
                check (status in ('active', 'sold', 'archived', 'removed')),
  expires_at    timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  -- Soft delete: the row stays so its media can be cleaned up and audited.
  deleted_at    timestamptz
);

comment on table public.products is
  'A listing. Soft-deleted (deleted_at) so its media can be audited and cleaned.';
comment on column public.products.expires_at is
  'When the listing stops being sellable. NULL means it never expires.';
comment on column public.products.deleted_at is
  'Soft delete. Media of a soft-deleted product is cleaned up after the 7-day grace.';

create index if not exists products_seller_updated_idx
  on public.products (seller_id, updated_at desc);
create index if not exists products_live_idx
  on public.products (created_at desc)
  where deleted_at is null and status = 'active';
create index if not exists products_expiry_idx
  on public.products (expires_at)
  where expires_at is not null and deleted_at is null;

-- ---------------------------------------------------------------------------
-- product_media
-- ---------------------------------------------------------------------------
create table if not exists public.product_media (
  id            uuid primary key default gen_random_uuid(),
  -- NULL means the file was uploaded but never attached to a listing yet.
  product_id    uuid references public.products (id) on delete set null,
  -- Denormalised owner so RLS and the seller dashboard never need a join.
  seller_id     uuid not null references auth.users (id) on delete cascade,
  provider      text not null check (provider in ('cloudinary', 'r2')),
  -- Cloudinary public_id, or the R2 object key (listing_images/<uid>/<file>).
  storage_key   text not null check (char_length(storage_key) between 1 and 512),
  public_url    text not null,
  mime_type     text,
  bytes         bigint not null default 0 check (bytes >= 0),
  position      integer not null default 0 check (position >= 0),
  created_at    timestamptz not null default now(),
  -- Cleanup bookkeeping -----------------------------------------------------
  orphaned_at   timestamptz,
  grace_until   timestamptz,
  delete_after  timestamptz,
  queued_at     timestamptz,
  deleted_at    timestamptz,
  delete_attempts integer not null default 0 check (delete_attempts >= 0),
  last_error    text,
  notified_at   timestamptz,
  notify_count  integer not null default 0 check (notify_count >= 0),
  -- One row per object, whichever provider stores it.
  constraint product_media_object_unique unique (provider, storage_key)
);

comment on table public.product_media is
  'One row per stored file. Rows outlive the product so bytes can be traced.';
comment on column public.product_media.orphaned_at is
  'When the file stopped belonging to a live listing (start of the grace period).';
comment on column public.product_media.delete_after is
  'Earliest time the cleanup engine may delete the bytes. Set by cleanup_scan().';
comment on column public.product_media.deleted_at is
  'Set only after the provider confirmed the object is gone (cleanup_confirm).';

create index if not exists product_media_product_idx
  on public.product_media (product_id, position);
create index if not exists product_media_seller_idx
  on public.product_media (seller_id, created_at desc);
create index if not exists product_media_due_idx
  on public.product_media (delete_after)
  where deleted_at is null and delete_after is not null;
create index if not exists product_media_queue_idx
  on public.product_media (queued_at)
  where deleted_at is null and queued_at is not null;
create index if not exists product_media_unattached_idx
  on public.product_media (created_at)
  where product_id is null and deleted_at is null;

-- ---------------------------------------------------------------------------
-- cleanup_jobs
-- ---------------------------------------------------------------------------
create table if not exists public.cleanup_jobs (
  id            uuid primary key default gen_random_uuid(),
  kind          text not null check (kind in ('scan', 'queue', 'notify', 'manual')),
  status        text not null default 'running'
                check (status in ('running', 'succeeded', 'partial', 'failed')),
  triggered_by  text not null default 'cron'
                check (triggered_by in ('cron', 'admin', 'seller', 'system')),
  actor_id      uuid,
  started_at    timestamptz not null default now(),
  finished_at   timestamptz,
  scanned_products integer not null default 0 check (scanned_products >= 0),
  scanned_media    integer not null default 0 check (scanned_media >= 0),
  candidates    integer not null default 0 check (candidates >= 0),
  queued        integer not null default 0 check (queued >= 0),
  notified      integer not null default 0 check (notified >= 0),
  deleted       integer not null default 0 check (deleted >= 0),
  failed        integer not null default 0 check (failed >= 0),
  bytes_freed   bigint not null default 0 check (bytes_freed >= 0),
  error         text
);

comment on table public.cleanup_jobs is
  'One row per cleanup pass, so the admin history shows what ran and what it did.';

create index if not exists cleanup_jobs_started_idx
  on public.cleanup_jobs (started_at desc);

-- ---------------------------------------------------------------------------
-- cleanup_audit_logs (append-only; snapshots survive a deleted media row)
-- ---------------------------------------------------------------------------
create table if not exists public.cleanup_audit_logs (
  id            bigint generated always as identity primary key,
  job_id        uuid references public.cleanup_jobs (id) on delete set null,
  media_id      uuid,
  product_id    uuid,
  seller_id     uuid,
  provider      text,
  storage_key   text,
  public_url    text,
  action        text not null
                check (action in ('candidate', 'notified', 'queued', 'deleted',
                                  'failed', 'skipped', 'restored')),
  reason        text check (reason in ('orphan', 'expired', 'stale',
                                       'seller_request', 'manual')),
  bytes         bigint not null default 0,
  detail        text,
  created_at    timestamptz not null default now()
);

comment on table public.cleanup_audit_logs is
  'Append-only: every candidate, reminder, queue, delete and failure, with copies of the object identity.';

create index if not exists cleanup_audit_logs_created_idx
  on public.cleanup_audit_logs (created_at desc);
create index if not exists cleanup_audit_logs_media_idx
  on public.cleanup_audit_logs (media_id, created_at desc);
create index if not exists cleanup_audit_logs_seller_idx
  on public.cleanup_audit_logs (seller_id, created_at desc);
create index if not exists cleanup_audit_logs_job_idx
  on public.cleanup_audit_logs (job_id);

-- Dedupe: a file is a candidate once, reminded once, queued once and deleted
-- once. Re-running a pass (or a retried cron) cannot write a second row.
create unique index if not exists cleanup_audit_logs_once_per_action
  on public.cleanup_audit_logs (media_id, action)
  where media_id is not null
    and action in ('notified', 'queued', 'deleted');

-- ---------------------------------------------------------------------------
-- updated_at
-- ---------------------------------------------------------------------------
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists products_touch_updated_at on public.products;
create trigger products_touch_updated_at
  before update on public.products
  for each row execute function public.touch_updated_at();
