-- Camiguin Market — daily automated cleanup.
--
-- pg_cron is available on every Supabase plan. One job, once a day, at 02:15
-- UTC (10:15 AM Philippine time) so sellers wake up to any reminder rather than
-- losing a file overnight.
--
-- The job runs as the database owner, so it bypasses RLS and needs no secret.
-- It only classifies, reminds and queues; the actual object deletion happens in
-- the Edge Function with the Cloudinary / R2 credentials.
--
-- Check it from the SQL editor:
--   select jobid, jobname, schedule, active from cron.job;
--   select * from cron.job_run_details order by start_time desc limit 10;
--   select * from public.cleanup_jobs order by started_at desc limit 10;

do $$
begin
  begin
    create extension if not exists pg_cron;
  exception
    when others then
      raise notice 'pg_cron not available here (%); schedule cleanup_daily() externally', sqlerrm;
  end;

  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    -- Re-running this migration must not create a second job.
    perform cron.unschedule(j.jobname)
       from cron.job j
      where j.jobname in ('camiguin_cleanup_daily', 'camiguin_cleanup_scan',
                          'camiguin_cleanup_queue', 'camiguin_cleanup_notify');

    perform cron.schedule(
      'camiguin_cleanup_daily',
      '15 2 * * *',
      $cron$select public.cleanup_daily('cron', null)$cron$
    );

    raise notice 'pg_cron: camiguin_cleanup_daily scheduled at 02:15 UTC daily';
  else
    raise notice 'pg_cron unavailable: call select public.cleanup_daily() daily instead';
  end if;
end;
$$;
