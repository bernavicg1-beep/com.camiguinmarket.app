-- Camiguin Market — the cleanup engine.
--
-- Nothing here talks to a storage provider. Postgres decides *what* may be
-- deleted and *when*; an Edge Function (or the Worker) holding the Cloudinary /
-- R2 credentials performs the delete and calls cleanup_confirm(). That keeps
-- every provider secret out of the database and out of the app.
--
-- Pipeline, run in this order by cleanup_daily():
--   cleanup_scan()     classify every live media row and set its delete_after
--   cleanup_notify()   remind the seller once, inside the 7-day grace window
--   cleanup_queue()    freeze rows whose delete_after has passed
--   cleanup_confirm()  (Edge Function) record the provider's result
--
-- Rules (constants live in the views/functions below):
--   * 30 days — a listing nobody has touched for 30 days is stale.
--   * expired — the listing's expires_at has passed, or it is sold/archived/
--               removed.
--   * orphan  — the file has no live listing: never attached (upload abandoned)
--               or its listing was soft-deleted.
--   * 7 days  — grace measured from the moment a file qualified. delete_after is
--               never earlier than that, even for a listing already stale for
--               months, so a seller always gets a week of warning.

-- ---------------------------------------------------------------------------
-- Classification (single source of truth for every pass)
-- ---------------------------------------------------------------------------
create or replace view public.media_cleanup_candidates
with (security_invoker = true)
as
select m.id                as media_id,
       m.product_id,
       m.seller_id,
       m.provider,
       m.storage_key,
       m.public_url,
       m.bytes,
       m.created_at        as media_created_at,
       m.orphaned_at,
       m.delete_after,
       m.queued_at,
       m.deleted_at,
       m.notified_at,
       p.updated_at        as product_updated_at,
       p.expires_at,
       case
         -- Files nobody owns, or whose listing is gone.
         when m.product_id is null then 'orphan'
         when p.id is null then 'orphan'
         when p.deleted_at is not null then 'orphan'
         when p.status in ('archived', 'removed') then 'orphan'
         -- Listing ran out of time.
         when p.expires_at is not null and p.expires_at <= now() then 'expired'
         -- 30 days without the seller touching the listing.
         when p.updated_at <= now() - interval '30 days' then 'stale'
       end                 as reason
from public.product_media m
left join public.products p on p.id = m.product_id
where m.deleted_at is null;

comment on view public.media_cleanup_candidates is
  'Every not-yet-deleted media row with the cleanup reason it qualifies for (NULL = keep).';

grant select on public.media_cleanup_candidates to authenticated;

-- ---------------------------------------------------------------------------
-- 1. scan — classify, start the grace clock, log new candidates
-- ---------------------------------------------------------------------------
create or replace function public.cleanup_scan(
  p_trigger text default 'cron',
  p_actor   uuid default null
)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_job        uuid;
  v_started    timestamptz;
  v_products   bigint;
  v_media      bigint;
  v_candidates bigint;
begin
  insert into public.cleanup_jobs (kind, status, triggered_by, actor_id)
  values ('scan', 'running', p_trigger, p_actor)
  returning id, started_at into v_job, v_started;

  select count(*) into v_products from public.products where deleted_at is null;
  select count(*) into v_media    from public.product_media where deleted_at is null;

  -- Give every qualifying file a grace period and a delete_after. A row that
  -- already has a delete_after keeps the later of the two, so re-scanning can
  -- never pull a deletion forward.
  update public.product_media m
     set orphaned_at  = coalesce(m.orphaned_at, now()),
         grace_until  = coalesce(m.grace_until, coalesce(m.orphaned_at, now()) + interval '7 days'),
         delete_after = greatest(
           coalesce(
             m.delete_after,
             case c.reason
               when 'stale'   then c.product_updated_at + interval '30 days'
               when 'expired' then coalesce(c.expires_at, now()) + interval '7 days'
               else coalesce(m.orphaned_at, now()) + interval '7 days'
             end
           ),
           coalesce(m.orphaned_at, now()) + interval '7 days'
         )
    from public.media_cleanup_candidates c
   where c.media_id = m.id
     and c.reason is not null
     and m.queued_at is null;

  -- Log only what this pass newly flagged: orphaned_at was set by the update
  -- above, so ">= v_started" is exactly the new candidates.
  insert into public.cleanup_audit_logs
    (job_id, media_id, product_id, seller_id, provider, storage_key, public_url,
     action, reason, bytes, detail)
  select v_job,
         c.media_id,
         c.product_id,
         c.seller_id,
         c.provider,
         c.storage_key,
         c.public_url,
         'candidate',
         c.reason,
         c.bytes,
         'grace ends ' || coalesce(c.delete_after, now() + interval '7 days')::text
    from public.media_cleanup_candidates c
   where c.reason is not null
     and c.orphaned_at is not null
     and c.orphaned_at >= v_started;

  get diagnostics v_candidates = row_count;

  update public.cleanup_jobs
     set status = 'succeeded',
         finished_at = now(),
         scanned_products = v_products,
         scanned_media = v_media,
         candidates = v_candidates
   where id = v_job;

  return v_job;
exception
  when others then
    if v_job is not null then
      update public.cleanup_jobs
         set status = 'failed', finished_at = now(), error = sqlerrm
       where id = v_job;
    end if;
    raise;
end;
$$;

comment on function public.cleanup_scan(text, uuid) is
  'Classifies media, starts the 7-day grace clock and logs new candidates. Never deletes.';

-- ---------------------------------------------------------------------------
-- 2. notify — one reminder per file, inside the grace window
-- ---------------------------------------------------------------------------
create or replace function public.cleanup_notify(
  p_trigger text default 'cron',
  p_actor   uuid default null
)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_job      uuid;
  v_notified bigint;
begin
  insert into public.cleanup_jobs (kind, status, triggered_by, actor_id)
  values ('notify', 'running', p_trigger, p_actor)
  returning id into v_job;

  -- Remind while the file is inside its last 7 days of grace. notified_at
  -- (plus the unique audit index) makes this exactly once per file even if the
  -- pass is retried; the app turns one row into one push.
  insert into public.cleanup_audit_logs
    (job_id, media_id, product_id, seller_id, provider, storage_key, public_url,
     action, reason, bytes, detail)
  select v_job,
         c.media_id,
         c.product_id,
         c.seller_id,
         c.provider,
         c.storage_key,
         c.public_url,
         'notified',
         c.reason,
         c.bytes,
         'deleted after ' || c.delete_after::text
    from public.media_cleanup_candidates c
   where c.reason is not null
     and c.queued_at is null
     and c.delete_after is not null
     and c.delete_after > now()
     and c.delete_after <= now() + interval '7 days'
     and c.notified_at is null
  on conflict do nothing;

  get diagnostics v_notified = row_count;

  update public.product_media m
     set notified_at  = now(),
         notify_count = m.notify_count + 1
   where m.id in (select l.media_id
                    from public.cleanup_audit_logs l
                   where l.job_id = v_job
                     and l.action = 'notified'
                     and l.media_id is not null);

  update public.cleanup_jobs
     set status = 'succeeded', finished_at = now(), notified = v_notified
   where id = v_job;

  return v_job;
exception
  when others then
    if v_job is not null then
      update public.cleanup_jobs
         set status = 'failed', finished_at = now(), error = sqlerrm
       where id = v_job;
    end if;
    raise;
end;
$$;

comment on function public.cleanup_notify(text, uuid) is
  'Writes at most one reminder per media row, within the last 7 days of its grace.';

-- ---------------------------------------------------------------------------
-- 3. queue — freeze rows whose delete_after has passed
-- ---------------------------------------------------------------------------
create or replace function public.cleanup_queue(
  p_trigger text default 'cron',
  p_actor   uuid default null
)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_job    uuid;
  v_queued bigint;
  v_bytes  bigint;
begin
  insert into public.cleanup_jobs (kind, status, triggered_by, actor_id)
  values ('queue', 'running', p_trigger, p_actor)
  returning id into v_job;

  -- queued_at freezes the row: the seller can no longer edit it, and
  -- cleanup_pending_deletions starts handing it to the provider deleter.
  with due as (
    update public.product_media m
       set queued_at = now()
     where m.deleted_at is null
       and m.queued_at is null
       and m.delete_after is not null
       and m.delete_after <= now()
    returning m.id, m.product_id, m.seller_id, m.provider, m.storage_key, m.public_url, m.bytes
  )
  insert into public.cleanup_audit_logs
    (job_id, media_id, product_id, seller_id, provider, storage_key, public_url,
     action, reason, bytes)
  select v_job, d.id, d.product_id, d.seller_id, d.provider, d.storage_key, d.public_url,
         'queued', c.reason, d.bytes
    from due d
    left join public.media_cleanup_candidates c on c.media_id = d.id
  on conflict do nothing;

  get diagnostics v_queued = row_count;

  select coalesce(sum(bytes), 0) into v_bytes
    from public.cleanup_audit_logs
   where job_id = v_job and action = 'queued';

  update public.cleanup_jobs
     set status = 'succeeded', finished_at = now(),
         queued = v_queued, bytes_freed = v_bytes
   where id = v_job;

  return v_job;
exception
  when others then
    if v_job is not null then
      update public.cleanup_jobs
         set status = 'failed', finished_at = now(), error = sqlerrm
       where id = v_job;
    end if;
    raise;
end;
$$;

comment on function public.cleanup_queue(text, uuid) is
  'Marks due media as queued for provider deletion. Does not delete any bytes.';

-- ---------------------------------------------------------------------------
-- 4. confirm — provider says the object is (not) gone
-- ---------------------------------------------------------------------------
create or replace function public.cleanup_confirm(
  p_media_id uuid,
  p_ok       boolean,
  p_error    text default null,
  p_actor    uuid default null
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_media public.product_media;
begin
  select * into v_media
    from public.product_media
   where id = p_media_id
     and deleted_at is null;

  if not found then
    return false;
  end if;

  if p_ok then
    update public.product_media
       set deleted_at = now(),
           delete_attempts = delete_attempts + 1,
           last_error = null
     where id = p_media_id;

    insert into public.cleanup_audit_logs
      (media_id, product_id, seller_id, provider, storage_key, public_url,
       action, reason, bytes, detail)
    values (p_media_id, v_media.product_id, v_media.seller_id, v_media.provider,
            v_media.storage_key, v_media.public_url, 'deleted', 'manual',
            v_media.bytes, 'confirmed gone by provider')
    on conflict do nothing;
  else
    -- Keep the bytes, back off for a day and let the next pass retry. The
    -- failure is in the history so a stuck key is visible in the admin view.
    update public.product_media
       set delete_attempts = delete_attempts + 1,
           last_error = left(coalesce(p_error, 'unknown provider error'), 500),
           queued_at = null,
           delete_after = now() + interval '1 day'
     where id = p_media_id;

    insert into public.cleanup_audit_logs
      (media_id, product_id, seller_id, provider, storage_key, public_url,
       action, reason, bytes, detail)
    values (p_media_id, v_media.product_id, v_media.seller_id, v_media.provider,
            v_media.storage_key, v_media.public_url, 'failed', 'manual',
            v_media.bytes, left(coalesce(p_error, 'unknown provider error'), 500));
  end if;

  return true;
end;
$$;

comment on function public.cleanup_confirm(uuid, boolean, text, uuid) is
  'Called by the provider deleter: records success (deleted_at) or a failure (retry in 1 day).';

-- ---------------------------------------------------------------------------
-- 5. seller opt-in: "Clean Old Files"
-- ---------------------------------------------------------------------------
create or replace function public.seller_delete_media(p_media_ids uuid[])
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid   uuid := auth.uid();
  v_count bigint;
begin
  if v_uid is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;

  -- A seller asking for deletion waives the grace they were already warned
  -- about, but only for their own rows that are not already queued. The row is
  -- frozen (queued_at) in the same write, so the provider deleter picks it up
  -- on its next run instead of waiting for tomorrow's cron.
  with target as (
    update public.product_media m
       set orphaned_at = coalesce(m.orphaned_at, now()),
           grace_until = now(),
           delete_after = now(),
           queued_at = now()
     where m.id = any(p_media_ids)
       and m.seller_id = v_uid
       and m.deleted_at is null
       and m.queued_at is null
    returning m.id, m.product_id, m.provider, m.storage_key, m.public_url, m.bytes
  ),
  logged as (
    insert into public.cleanup_audit_logs
      (media_id, product_id, seller_id, provider, storage_key, public_url,
       action, reason, bytes, detail)
    select t.id, t.product_id, v_uid, t.provider, t.storage_key, t.public_url,
           'queued', 'seller_request', t.bytes, 'seller pressed Clean Old Files'
      from target t
    on conflict do nothing
    returning 1
  )
  select count(*) into v_count from logged;

  return coalesce(v_count, 0);
end;
$$;

comment on function public.seller_delete_media(uuid[]) is
  'The seller asks for their own files to be deleted now; returns how many were queued.';

-- ---------------------------------------------------------------------------
-- 6. admin trigger + the single daily entry point
-- ---------------------------------------------------------------------------
create or replace function public.admin_run_cleanup(p_step text default 'all')
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
begin
  if not public.is_admin() then
    raise exception 'admin only' using errcode = '42501';
  end if;

  case p_step
    when 'scan'   then perform public.cleanup_scan('admin', v_uid);
    when 'notify' then perform public.cleanup_notify('admin', v_uid);
    when 'queue'  then perform public.cleanup_queue('admin', v_uid);
    when 'all'    then perform public.cleanup_daily('admin', v_uid);
    else raise exception 'unknown cleanup step: %', p_step using errcode = '22023';
  end case;

  return p_step || ' finished';
end;
$$;

comment on function public.admin_run_cleanup(text) is
  'Lets the Storage Monitor run a pass by hand; same code the cron runs.';

create or replace function public.cleanup_daily(
  p_trigger text default 'cron',
  p_actor   uuid default null
)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_scan   uuid;
  v_notify uuid;
  v_queue  uuid;
begin
  v_scan   := public.cleanup_scan(p_trigger, p_actor);
  v_notify := public.cleanup_notify(p_trigger, p_actor);
  v_queue  := public.cleanup_queue(p_trigger, p_actor);

  return format('scan %s, notify %s, queue %s', v_scan, v_notify, v_queue);
end;
$$;

comment on function public.cleanup_daily(text, uuid) is
  'Daily entry point: scan, then notify, then queue. Scheduled by pg_cron.';

-- ---------------------------------------------------------------------------
-- Privileges: the engine runs as the owner; clients only get the two entry
-- points meant for them. Nothing else is callable with the anon/authenticated
-- key, and the cleanup tables stay read-only under RLS.
-- ---------------------------------------------------------------------------
revoke all on function public.cleanup_scan(text, uuid)      from public;
revoke all on function public.cleanup_notify(text, uuid)    from public;
revoke all on function public.cleanup_queue(text, uuid)     from public;
revoke all on function public.cleanup_confirm(uuid, boolean, text, uuid) from public;
revoke all on function public.cleanup_daily(text, uuid)     from public;
revoke all on function public.admin_run_cleanup(text)       from public;
revoke all on function public.seller_delete_media(uuid[])   from public;

revoke all on function public.cleanup_scan(text, uuid)      from anon, authenticated;
revoke all on function public.cleanup_notify(text, uuid)    from anon, authenticated;
revoke all on function public.cleanup_queue(text, uuid)     from anon, authenticated;
revoke all on function public.cleanup_confirm(uuid, boolean, text, uuid) from anon, authenticated;
revoke all on function public.cleanup_daily(text, uuid)     from anon, authenticated;

grant execute on function public.seller_delete_media(uuid[]) to authenticated;
grant execute on function public.admin_run_cleanup(text)     to authenticated;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'grant execute on function public.cleanup_scan(text, uuid) to service_role';
    execute 'grant execute on function public.cleanup_notify(text, uuid) to service_role';
    execute 'grant execute on function public.cleanup_queue(text, uuid) to service_role';
    execute 'grant execute on function public.cleanup_confirm(uuid, boolean, text, uuid) to service_role';
    execute 'grant execute on function public.cleanup_daily(text, uuid) to service_role';
  end if;
end;
$$;
