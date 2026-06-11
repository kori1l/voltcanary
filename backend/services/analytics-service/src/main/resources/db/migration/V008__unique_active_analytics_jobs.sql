alter table analytics_jobs
    add column if not exists active_key varchar(50);

update analytics_jobs
set active_key = 'ACTIVE'
where status in ('PENDING', 'RUNNING')
  and active_key is null;

create unique index if not exists uq_analytics_jobs_active_device_type
    on analytics_jobs (device_id, job_type, active_key);
