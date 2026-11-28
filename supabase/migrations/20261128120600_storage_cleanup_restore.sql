-- Storage cleanup, part 2: a listing that comes back takes its files off the
-- chopping block. Without this, a seller who deleted a listing and restored it
-- inside the 7-day grace still lost the photos: they were already orphans and
-- the queue would have collected them.

create or replace function public.reset_media_grace_on_restore()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_reset bigint;
begin
  -- Only a listing that was gone and is now back counts. Everything else keeps
  -- its bookkeeping untouched.
  if not (
    new.deleted_at is null
    and new.status in ('active', 'sold')
    and (old.deleted_at is not null or old.status in ('archived', 'removed'))
  ) then
    return new;
  end if;

  -- Rows already handed to the provider deleter (queued_at) are left alone on
  -- purpose: the Edge Function may be deleting them right now, and only
  -- cleanup_confirm() may decide how that ends. Everything still in grace is
  -- given back to the seller.
  with revived as (
    update public.product_media m
       set orphaned_at   = null,
           grace_until   = null,
           delete_after  = null,
           notified_at   = null,
           notify_count  = 0,
           cleanup_cycle = m.cleanup_cycle + 1
     where m.product_id = new.id
       and m.deleted_at is null
       and m.queued_at is null
    returning m.id, m.product_id, m.seller_id, m.provider, m.storage_key,
              m.public_url, m.bytes, m.cleanup_cycle
  )
  insert into public.cleanup_audit_logs
    (media_id, product_id, seller_id, provider, storage_key, public_url,
     action, reason, bytes, detail, cleanup_cycle)
  select r.id, r.product_id, r.seller_id, r.provider, r.storage_key, r.public_url,
         'restored', null, r.bytes,
         'listing restored to ' || new.status || '; grace reset', r.cleanup_cycle
    from revived r;

  get diagnostics v_reset = row_count;

  if v_reset > 0 then
    raise notice 'restore: % media row(s) kept', v_reset;
  end if;

  return new;
end;
$$;

comment on function public.reset_media_grace_on_restore() is
  'Trigger: a restored listing clears the grace, warning and delete_after of its media and starts a new cleanup cycle.';

-- Revoked like the rest of the engine: it runs from the trigger, never by hand.
revoke all on function public.reset_media_grace_on_restore() from public;
