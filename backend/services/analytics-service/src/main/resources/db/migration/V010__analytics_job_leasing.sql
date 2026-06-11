alter table analytics_jobs
    add column if not exists locked_at timestamp;

alter table analytics_jobs
    add column if not exists locked_by varchar(255);

create index if not exists idx_analytics_jobs_claim
    on analytics_jobs (status, created_at);

create index if not exists idx_analytics_jobs_stale_running
    on analytics_jobs (status, locked_at);
