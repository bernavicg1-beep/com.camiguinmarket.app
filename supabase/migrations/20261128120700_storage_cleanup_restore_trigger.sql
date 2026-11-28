-- Storage cleanup, part 2: fire the restore reset whenever a listing comes back.
drop trigger if exists products_reset_media_grace on public.products;
create trigger products_reset_media_grace
  after update of status, deleted_at on public.products
  for each row
  when (old.deleted_at is distinct from new.deleted_at
        or old.status is distinct from new.status)
  execute function public.reset_media_grace_on_restore();
