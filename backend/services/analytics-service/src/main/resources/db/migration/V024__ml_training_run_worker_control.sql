alter table ml_training_runs
    add column lease_owner varchar(255);

alter table ml_training_runs
    add column lease_expires_at timestamp;

alter table ml_training_runs
    add column heartbeat_at timestamp;

alter table ml_training_runs
    add column cancellation_requested boolean not null default false;

alter table ml_training_runs
    add column retry_count integer not null default 0;

alter table ml_training_runs
    add column stale_recovered_at timestamp;

alter table ml_training_runs
    add constraint ck_ml_training_runs_retry_count
    check (retry_count >= 0);

create index idx_ml_training_runs_status_lease
    on ml_training_runs (status, lease_expires_at);
