-- Storage cleanup, part 2: the provider deleter needs the MIME type.
--
-- Cloudinary's Admin API separates images from videos, so the view exposes
-- mime_type. It is appended last: CREATE OR REPLACE VIEW may only add columns at
-- the end, never in the middle.

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
       m.delete_attempts,
       m.mime_type
from public.product_media m
where m.deleted_at is null
  and m.queued_at is not null
order by m.queued_at
limit 200;

grant select on public.cleanup_pending_deletions to authenticated;
