-- Storage cleanup, part 2: the per-listing cleanup cycle.
--
-- Dedupe was keyed on (media_id, action), so a file could be warned once in its
-- whole life. A seller who deleted a listing, restored it and later let it lapse
-- again got the file purged with no second warning. The cycle is the listing's
-- restore count, so the key is scoped to the current grace period instead.

alter table public.product_media
  add column if not exists cleanup_cycle integer not null default 0;

alter table public.cleanup_audit_logs
  add column if not exists cleanup_cycle integer not null default 0;

comment on column public.product_media.cleanup_cycle is
  'How many times the listing was restored. Resets the grace and re-arms the reminders.';

-- Rows written before this migration keep cycle 0: the original grace period.
drop index if exists cleanup_audit_logs_once_per_action;
create unique index if not exists cleanup_audit_logs_once_per_action
  on public.cleanup_audit_logs (media_id, action, cleanup_cycle)
  where media_id is not null
    and action in ('notified', 'queued', 'deleted');
