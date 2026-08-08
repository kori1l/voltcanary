alter table ml_dataset_versions
    drop constraint if exists ck_ml_dataset_versions_status;

alter table ml_dataset_versions
    add constraint ck_ml_dataset_versions_status
        check (status in ('DRAFT', 'VALIDATING', 'READY', 'FAILED', 'ARCHIVED', 'DELETED'));

alter table ml_model_versions
    drop constraint if exists ck_ml_model_versions_status;

alter table ml_model_versions
    add constraint ck_ml_model_versions_status
        check (status in ('CANDIDATE', 'APPROVED', 'ACTIVE', 'REJECTED', 'ARCHIVED', 'DELETED'));

alter table ml_training_runs
    drop constraint if exists ck_ml_training_runs_status;

alter table ml_training_runs
    add constraint ck_ml_training_runs_status
        check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED', 'ARCHIVED', 'DELETED'));

alter table ml_dataset_versions
    add column if not exists lifecycle_reason text;

alter table ml_dataset_versions
    add column if not exists lifecycle_actor_id uuid;

alter table ml_dataset_versions
    add column if not exists lifecycle_at timestamp;

alter table ml_model_versions
    add column if not exists lifecycle_reason text;

alter table ml_model_versions
    add column if not exists lifecycle_actor_id uuid;

alter table ml_model_versions
    add column if not exists lifecycle_at timestamp;

alter table ml_training_runs
    add column if not exists lifecycle_reason text;

alter table ml_training_runs
    add column if not exists lifecycle_actor_id uuid;

alter table ml_training_runs
    add column if not exists lifecycle_at timestamp;

alter table ml_feature_snapshots
    add column if not exists lifecycle_status varchar(32) not null default 'ACTIVE';

alter table ml_feature_snapshots
    add column if not exists lifecycle_reason text;

alter table ml_feature_snapshots
    add column if not exists lifecycle_actor_id uuid;

alter table ml_feature_snapshots
    add column if not exists lifecycle_at timestamp;

alter table ml_labels
    add column if not exists deleted_at timestamp;

alter table ml_labels
    add column if not exists deleted_by uuid;

alter table ml_labels
    add column if not exists deletion_reason text;

alter table ml_deployment_policies
    add column if not exists deleted_at timestamp;

alter table ml_deployment_policies
    add column if not exists deleted_by uuid;

alter table ml_deployment_policies
    add column if not exists deletion_reason text;

create index if not exists idx_ml_model_versions_training_run
    on ml_model_versions (training_run_id);

create index if not exists idx_ml_model_versions_dataset_version
    on ml_model_versions (dataset_version_id);

create index if not exists idx_ml_model_versions_feature_snapshot
    on ml_model_versions (feature_snapshot_id);

create index if not exists idx_ml_deployment_policies_model_version
    on ml_deployment_policies (model_version_id);
