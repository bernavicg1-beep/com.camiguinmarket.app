-- Storage cleanup, part 2: every audit row records the cycle it belongs to.
--
-- The dedupe index is keyed on (media_id, action, cleanup_cycle), so an insert
-- must carry the media's current cycle. Stamping it here instead of editing
-- cleanup_scan/notify/queue/confirm and seller_delete_media keeps those functions
-- exactly as they are, and any future insert gets it right for free.

create or replace function public.audit_cleanup_cycle()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if new.media_id is not null then
    select m.cleanup_cycle into new.cleanup_cycle
      from public.product_media m
     where m.id = new.media_id;
    new.cleanup_cycle := coalesce(new.cleanup_cycle, 0);
  end if;
  return new;
end;
$$;

comment on function public.audit_cleanup_cycle() is
  'Trigger: copies the media row''s cleanup cycle onto its audit entry.';

revoke all on function public.audit_cleanup_cycle() from public;

drop trigger if exists cleanup_audit_logs_cycle on public.cleanup_audit_logs;
create trigger cleanup_audit_logs_cycle
  before insert on public.cleanup_audit_logs
  for each row
  execute function public.audit_cleanup_cycle();
