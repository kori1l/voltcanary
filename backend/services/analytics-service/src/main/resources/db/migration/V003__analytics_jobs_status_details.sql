alter table analytics_jobs
    add column if not exists started_at timestamp;

alter table analytics_jobs
    add column if not exists error_message varchar(1000);

create index if not exists idx_analytics_jobs_status_created_at
    on analytics_jobs (status, created_at);
