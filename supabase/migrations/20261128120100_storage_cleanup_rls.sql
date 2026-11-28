-- Camiguin Market — Row Level Security for listings, media and cleanup history.
--
-- Model:
--   * A seller sees and edits only their own products and product_media rows.
--   * An admin (JWT app_metadata.role = 'admin') sees everything, read-only for
--     the cleanup tables and full control over products.
--   * cleanup_jobs and cleanup_audit_logs are never writable by a client: only
--     the SECURITY DEFINER functions and the service role write them.
--   * service_role bypasses RLS, so the scheduled job and the Edge Function that
--     deletes provider objects need no policy at all.
--
-- Set the admin flag once, from the Supabase SQL editor (never from the app):
--   update auth.users set raw_app_meta_data =
--     jsonb_set(coalesce(raw_app_meta_data, '{}'), '{role}', '"admin"')
--   where email = 'you@example.com';

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------
create or replace function public.is_admin()
returns boolean
language sql
stable
as $$
  select coalesce(auth.jwt() -> 'app_metadata' ->> 'role', '') = 'admin';
$$;

comment on function public.is_admin() is
  'True when the caller''s JWT carries app_metadata.role = admin.';

-- Policies are evaluated as the querying role, so that role (and only it) must
-- be able to call the helper. Nothing else needs it.
revoke all on function public.is_admin() from public;
grant execute on function public.is_admin() to authenticated;

-- ---------------------------------------------------------------------------
-- products
-- ---------------------------------------------------------------------------
grant usage on schema public to anon, authenticated;
grant select, insert, update, delete on public.products to authenticated;

alter table public.products enable row level security;

drop policy if exists products_select_own on public.products;
create policy products_select_own on public.products
  for select to authenticated
  using (seller_id = (select auth.uid()) or (select public.is_admin()));

drop policy if exists products_insert_own on public.products;
create policy products_insert_own on public.products
  for insert to authenticated
  with check (seller_id = (select auth.uid()));

-- with check repeats the owner test, so a seller cannot hand a listing to
-- somebody else; the admin policy below is what lets an admin moderate.
drop policy if exists products_update_own on public.products;
create policy products_update_own on public.products
  for update to authenticated
  using (seller_id = (select auth.uid()))
  with check (seller_id = (select auth.uid()));

drop policy if exists products_delete_own on public.products;
create policy products_delete_own on public.products
  for delete to authenticated
  using (seller_id = (select auth.uid()));

drop policy if exists products_admin_all on public.products;
create policy products_admin_all on public.products
  for all to authenticated
  using ((select public.is_admin()))
  with check ((select public.is_admin()));

-- ---------------------------------------------------------------------------
-- product_media
-- ---------------------------------------------------------------------------
grant select, insert, delete on public.product_media to authenticated;

alter table public.product_media enable row level security;

drop policy if exists product_media_select_own on public.product_media;
create policy product_media_select_own on public.product_media
  for select to authenticated
  using (seller_id = (select auth.uid()) or (select public.is_admin()));

-- A seller may register a file only in their own name, and only against their
-- own listing (product_id NULL = upload that has not been attached yet).
drop policy if exists product_media_insert_own on public.product_media;
create policy product_media_insert_own on public.product_media
  for insert to authenticated
  with check (
    seller_id = (select auth.uid())
    and deleted_at is null
    and (product_id is null
         or exists (select 1 from public.products p
                    where p.id = product_id
                      and p.seller_id = (select auth.uid())))
  );

-- Only while the row is still the seller's to manage: once the cleanup engine
-- queues it (queued_at) or the provider confirmed it (deleted_at), the row is
-- frozen for the seller.
drop policy if exists product_media_update_own on public.product_media;
create policy product_media_update_own on public.product_media
  for update to authenticated
  using (seller_id = (select auth.uid()) and queued_at is null and deleted_at is null)
  with check (seller_id = (select auth.uid()) and deleted_at is null);

drop policy if exists product_media_delete_own on public.product_media;
create policy product_media_delete_own on public.product_media
  for delete to authenticated
  using (seller_id = (select auth.uid()) and queued_at is null);

drop policy if exists product_media_admin_all on public.product_media;
create policy product_media_admin_all on public.product_media
  for all to authenticated
  using ((select public.is_admin()))
  with check ((select public.is_admin()));

-- Column-level guard: even though the policy above allows an update, a client
-- may only change where a photo sits and which listing it belongs to. The
-- cleanup columns (delete_after, queued_at, deleted_at, bytes, provider,
-- storage_key …) stay under the engine's control.
revoke update on public.product_media from anon, authenticated;
grant update (position, product_id, public_url, mime_type) on public.product_media to authenticated;

-- ---------------------------------------------------------------------------
-- cleanup_jobs / cleanup_audit_logs — read-only, admins only
-- ---------------------------------------------------------------------------
alter table public.cleanup_jobs enable row level security;

drop policy if exists cleanup_jobs_select_admin on public.cleanup_jobs;
create policy cleanup_jobs_select_admin on public.cleanup_jobs
  for select to authenticated
  using ((select public.is_admin()));

revoke all on public.cleanup_jobs from anon, authenticated;
grant select on public.cleanup_jobs to authenticated;

alter table public.cleanup_audit_logs enable row level security;

drop policy if exists cleanup_audit_logs_select_admin on public.cleanup_audit_logs;
create policy cleanup_audit_logs_select_admin on public.cleanup_audit_logs
  for select to authenticated
  using ((select public.is_admin()));

revoke all on public.cleanup_audit_logs from anon, authenticated;
grant select on public.cleanup_audit_logs to authenticated;

-- The seller's own cleanup history ("Clean Old Files" page) is exposed through
-- this view instead, which only ever shows their own rows. security_invoker
-- makes the RLS policies above apply as the caller.
create or replace view public.my_cleanup_history
with (security_invoker = true)
as
select l.id,
       l.media_id,
       l.product_id,
       l.action,
       l.reason,
       l.provider,
       l.storage_key,
       l.public_url,
       l.bytes,
       l.created_at
from public.cleanup_audit_logs l
where l.seller_id = (select auth.uid());

grant select on public.my_cleanup_history to authenticated;

-- ---------------------------------------------------------------------------
-- Storage usage per seller / provider (feeds both dashboards)
-- ---------------------------------------------------------------------------
create or replace view public.storage_usage
with (security_invoker = true)
as
select m.seller_id,
       m.provider,
       count(*)                                              as total_items,
       count(*) filter (where m.product_id is not null
                          and m.deleted_at is null
                          and m.orphaned_at is null)          as live_items,
       count(*) filter (where m.orphaned_at is not null
                          and m.deleted_at is null
                          and m.queued_at is null)            as grace_items,
       count(*) filter (where m.queued_at is not null
                          and m.deleted_at is null)           as queued_items,
       count(*) filter (where m.deleted_at is not null)       as deleted_items,
       coalesce(sum(m.bytes), 0)                              as bytes_total,
       coalesce(sum(m.bytes) filter (where m.deleted_at is null
                                       and m.orphaned_at is null), 0) as bytes_live,
       coalesce(sum(m.bytes) filter (where m.deleted_at is null
                                       and m.orphaned_at is not null), 0) as bytes_reclaimable,
       coalesce(sum(m.bytes) filter (where m.deleted_at is not null), 0)   as bytes_deleted,
       min(m.orphaned_at) filter (where m.deleted_at is null) as oldest_orphan,
       max(m.deleted_at)                                      as last_deleted_at
from public.product_media m
group by m.seller_id, m.provider;

comment on view public.storage_usage is
  'Per-seller, per-provider storage totals. RLS on product_media means a seller sees only their own row and an admin sees every row.';

grant select on public.storage_usage to authenticated;

-- What the provider-deletion Edge Function has to remove, oldest request first.
create or replace view public.cleanup_pending_deletions
with (security_invoker = true)
as
select m.id,
       m.seller_id,
       m.provider,
       m.storage_key,
       m.public_url,
       m.bytes,
       m.delete_after,
       m.queued_at,
       m.delete_attempts
from public.product_media m
where m.deleted_at is null
  and m.queued_at is not null
order by m.queued_at
limit 200;

grant select on public.cleanup_pending_deletions to authenticated;
