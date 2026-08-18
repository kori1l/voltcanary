create table ml_horizon_label_generation_jobs (
    id uuid primary key,
    account_id uuid not null,
    dataset_version_id uuid not null,
    submitted_by uuid,
    horizon_days integer not null,
    generation_version varchar(128) not null,
    include_preventive_maintenance boolean not null,
    input_lineage_hash varchar(128) not null,
    idempotency_key varchar(128) not null,
    status varchar(32) not null,
    submitted_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    progress_percent integer,
    message text,
    failure_reason text,
    lineage_hash varchar(128),
    generated_labels integer,
    positive_labels integer,
    normal_labels integer,
    maintenance_excluded_labels integer,
    unresolved_labels integer,
    unobservable_rows integer,
    constraint ck_ml_horizon_label_generation_jobs_horizon check (horizon_days > 0),
    constraint ck_ml_horizon_label_generation_jobs_progress check (progress_percent is null or progress_percent between 0 and 100)
);

create index idx_ml_horizon_label_generation_jobs_account_dataset
    on ml_horizon_label_generation_jobs (account_id, dataset_version_id, submitted_at desc);

create index idx_ml_horizon_label_generation_jobs_idempotency
    on ml_horizon_label_generation_jobs (account_id, idempotency_key, status, submitted_at desc);
