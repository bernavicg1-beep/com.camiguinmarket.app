-- ---------------------------------------------------------------------------
-- 02:45 UTC — hand the queued rows to the Edge Function that deletes the bytes.
--
-- pg_cron can only run SQL here, and Postgres must never hold the Cloudinary /
-- R2 keys, so the second job just POSTs to the Edge Function with a shared
-- secret. Both values are read from database settings, so nothing is hardcoded
-- and nothing is scheduled until the owner has set them:
--
--   alter database postgres set app.cleanup_function_url =
--     'https://<project-ref>.supabase.co/functions/v1/cleanup-provider';
--   alter database postgres set app.cleanup_secret = '<random-string>';
--
-- Then run this file again (or just the job below) and keep the same secret in
-- the function's own environment.
do $$
declare
  v_url    text := coalesce(current_setting('app.cleanup_function_url', true), '');
  v_secret text := coalesce(current_setting('app.cleanup_secret', true), '');
begin
  if not exists (select 1 from pg_extension where extname = 'pg_cron') then
    raise notice 'pg_cron unavailable: nothing to schedule for the provider deleter';
    return;
  end if;

  if not exists (select 1 from pg_extension where extname = 'pg_net') then
    raise notice 'pg_net unavailable: install it to call cleanup-provider from the cron';
    return;
  end if;

  if v_url = '' or v_secret = '' then
    raise notice 'set app.cleanup_function_url and app.cleanup_secret to schedule cleanup-provider';
    return;
  end if;

  perform cron.unschedule(j.jobname)
     from cron.job j
    where j.jobname = 'camiguin_cleanup_provider';

  perform cron.schedule(
    'camiguin_cleanup_provider',
    '45 2 * * *',
    format(
      $job$select net.http_post(
              url := %L,
              headers := jsonb_build_object('Content-Type', 'application/json',
                                            'x-cleanup-secret', %L),
              body := '{}'::jsonb,
              timeout_milliseconds := 120000)$job$,
      v_url, v_secret
    )
  );

  raise notice 'pg_cron: camiguin_cleanup_provider scheduled at 02:45 UTC daily';
end;
$$;
