create table ml_datasets (
    id uuid primary key,
    account_id uuid not null,
    name varchar(255) not null,
    description text,
    source_type varchar(32) not null,
    created_by uuid,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint ck_ml_datasets_source_type check (source_type in ('INTERNAL_TELEMETRY', 'EXTERNAL_IMPORT', 'MIXED'))
);

create index idx_ml_datasets_account_created
    on ml_datasets (account_id, created_at desc);

create table ml_dataset_versions (
    id uuid primary key,
    dataset_id uuid not null,
    account_id uuid not null,
    version integer not null,
    source_type varchar(32) not null,
    status varchar(32) not null,
    split_strategy varchar(32) not null,
    split_config_json text not null,
    feature_config_version varchar(128) not null,
    source_window_start timestamp,
    source_window_end timestamp,
    created_by uuid,
    created_at timestamp not null,
    ready_at timestamp,
    failure_reason text,
    constraint fk_ml_dataset_versions_dataset foreign key (dataset_id) references ml_datasets(id),
    constraint ux_ml_dataset_versions_dataset_version unique (dataset_id, version),
    constraint ck_ml_dataset_versions_source_type check (source_type in ('INTERNAL_TELEMETRY', 'EXTERNAL_IMPORT', 'MIXED')),
    constraint ck_ml_dataset_versions_status check (status in ('DRAFT', 'VALIDATING', 'READY', 'FAILED', 'ARCHIVED')),
    constraint ck_ml_dataset_versions_split_strategy check (split_strategy in ('TIME_BASED', 'DEVICE_BASED', 'RANDOM')),
    constraint ck_ml_dataset_versions_window check (source_window_start is null or source_window_end is null or source_window_end > source_window_start)
);

create index idx_ml_dataset_versions_account_status_created
    on ml_dataset_versions (account_id, status, created_at desc);

create table ml_imported_files (
    id uuid primary key,
    account_id uuid not null,
    dataset_id uuid not null,
    original_filename varchar(255) not null,
    content_hash varchar(128) not null,
    content_type varchar(128) not null,
    size_bytes bigint not null,
    mapping_version varchar(128) not null,
    mapping_json text not null,
    artifact_uri varchar(1000),
    uploaded_by uuid,
    imported_at timestamp not null,
    constraint fk_ml_imported_files_dataset foreign key (dataset_id) references ml_datasets(id),
    constraint ck_ml_imported_files_size check (size_bytes >= 0)
);

create index idx_ml_imported_files_account_imported
    on ml_imported_files (account_id, imported_at desc);

create unique index ux_ml_imported_files_account_hash
    on ml_imported_files (account_id, content_hash);

create table ml_validation_reports (
    id uuid primary key,
    dataset_version_id uuid,
    imported_file_id uuid,
    accepted_rows bigint not null,
    rejected_rows bigint not null,
    warning_count bigint not null,
    rejected_reasons_json text not null,
    mapping_stats_json text not null,
    summary text,
    created_at timestamp not null,
    constraint fk_ml_validation_reports_dataset_version foreign key (dataset_version_id) references ml_dataset_versions(id),
    constraint fk_ml_validation_reports_imported_file foreign key (imported_file_id) references ml_imported_files(id),
    constraint ck_ml_validation_reports_rows check (accepted_rows >= 0 and rejected_rows >= 0 and warning_count >= 0),
    constraint ck_ml_validation_reports_source check (dataset_version_id is not null or imported_file_id is not null)
);

create index idx_ml_validation_reports_dataset_version
    on ml_validation_reports (dataset_version_id, created_at desc);

create index idx_ml_validation_reports_imported_file
    on ml_validation_reports (imported_file_id, created_at desc);

create table ml_dataset_lineage (
    id uuid primary key,
    dataset_version_id uuid not null,
    source_type varchar(32) not null,
    source_reference varchar(1000) not null,
    filter_json text not null,
    selected_devices_json text not null,
    selected_metrics_json text not null,
    source_window_start timestamp,
    source_window_end timestamp,
    content_hash varchar(128),
    created_at timestamp not null,
    constraint fk_ml_dataset_lineage_dataset_version foreign key (dataset_version_id) references ml_dataset_versions(id),
    constraint ck_ml_dataset_lineage_source_type check (source_type in ('INTERNAL_TELEMETRY', 'EXTERNAL_IMPORT', 'MIXED')),
    constraint ck_ml_dataset_lineage_window check (source_window_start is null or source_window_end is null or source_window_end > source_window_start)
);

create index idx_ml_dataset_lineage_dataset_version
    on ml_dataset_lineage (dataset_version_id);

create table ml_labels (
    id uuid primary key,
    account_id uuid not null,
    dataset_version_id uuid,
    device_id uuid,
    external_device_key varchar(255),
    metric_name varchar(255),
    label_start_at timestamp not null,
    label_end_at timestamp not null,
    label varchar(32) not null,
    source varchar(32) not null,
    reason text,
    created_by uuid,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint fk_ml_labels_dataset_version foreign key (dataset_version_id) references ml_dataset_versions(id),
    constraint ck_ml_labels_label check (label in ('NORMAL', 'ANOMALY', 'DEGRADATION', 'MAINTENANCE', 'UNKNOWN')),
    constraint ck_ml_labels_source check (source in ('MANUAL', 'ALERT_OUTCOME', 'MAINTENANCE_OUTCOME', 'IMPORTED')),
    constraint ck_ml_labels_window check (label_end_at > label_start_at),
    constraint ck_ml_labels_target check (device_id is not null or external_device_key is not null)
);

create index idx_ml_labels_account_dataset
    on ml_labels (account_id, dataset_version_id);

create index idx_ml_labels_device_time
    on ml_labels (device_id, label_start_at, label_end_at);

create table ml_label_audit (
    id uuid primary key,
    label_id uuid not null,
    previous_label varchar(32),
    new_label varchar(32) not null,
    edited_by uuid,
    edited_at timestamp not null,
    reason text,
    constraint fk_ml_label_audit_label foreign key (label_id) references ml_labels(id),
    constraint ck_ml_label_audit_previous check (previous_label is null or previous_label in ('NORMAL', 'ANOMALY', 'DEGRADATION', 'MAINTENANCE', 'UNKNOWN')),
    constraint ck_ml_label_audit_new check (new_label in ('NORMAL', 'ANOMALY', 'DEGRADATION', 'MAINTENANCE', 'UNKNOWN'))
);

create index idx_ml_label_audit_label_edited
    on ml_label_audit (label_id, edited_at desc);

create table ml_feature_snapshots (
    id uuid primary key,
    account_id uuid not null,
    dataset_version_id uuid not null,
    feature_set_version varchar(128) not null,
    code_config_version varchar(128) not null,
    feature_config_json text not null,
    feature_schema_hash varchar(128) not null,
    row_count bigint not null,
    rejected_row_count bigint not null,
    artifact_uri varchar(1000),
    created_at timestamp not null,
    constraint fk_ml_feature_snapshots_dataset_version foreign key (dataset_version_id) references ml_dataset_versions(id),
    constraint ck_ml_feature_snapshots_rows check (row_count >= 0 and rejected_row_count >= 0)
);

create index idx_ml_feature_snapshots_dataset_created
    on ml_feature_snapshots (dataset_version_id, created_at desc);

create index idx_ml_feature_snapshots_dataset_hash
    on ml_feature_snapshots (dataset_version_id, feature_schema_hash);

create table ml_training_runs (
    id uuid primary key,
    account_id uuid not null,
    dataset_version_id uuid not null,
    feature_snapshot_id uuid,
    model_family varchar(32) not null,
    algorithm varchar(128) not null,
    hyperparameters_json text not null,
    status varchar(32) not null,
    metrics_json text,
    artifact_uri varchar(1000),
    logs_summary text,
    failure_reason text,
    created_by uuid,
    created_at timestamp not null,
    started_at timestamp,
    completed_at timestamp,
    constraint fk_ml_training_runs_dataset_version foreign key (dataset_version_id) references ml_dataset_versions(id),
    constraint fk_ml_training_runs_feature_snapshot foreign key (feature_snapshot_id) references ml_feature_snapshots(id),
    constraint ck_ml_training_runs_family check (model_family in ('ANOMALY_DETECTION', 'DEGRADATION_SCORING', 'FORECASTING')),
    constraint ck_ml_training_runs_status check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'))
);

create index idx_ml_training_runs_account_created
    on ml_training_runs (account_id, created_at desc);

create index idx_ml_training_runs_status_created
    on ml_training_runs (status, created_at);

create table ml_model_versions (
    id uuid primary key,
    account_id uuid,
    model_key varchar(255) not null,
    version integer not null,
    family varchar(32) not null,
    algorithm varchar(128) not null,
    status varchar(32) not null,
    training_run_id uuid,
    dataset_version_id uuid,
    feature_snapshot_id uuid,
    feature_schema_hash varchar(128),
    artifact_uri varchar(1000),
    artifact_hash varchar(128),
    compatible_device_types_json text not null,
    supported_metrics_json text not null,
    evaluation_metrics_json text not null,
    approved_by uuid,
    approved_at timestamp,
    created_at timestamp not null,
    constraint fk_ml_model_versions_training_run foreign key (training_run_id) references ml_training_runs(id),
    constraint fk_ml_model_versions_dataset_version foreign key (dataset_version_id) references ml_dataset_versions(id),
    constraint fk_ml_model_versions_feature_snapshot foreign key (feature_snapshot_id) references ml_feature_snapshots(id),
    constraint ux_ml_model_versions_key_version unique (model_key, version),
    constraint ck_ml_model_versions_family check (family in ('ANOMALY_DETECTION', 'DEGRADATION_SCORING', 'FORECASTING')),
    constraint ck_ml_model_versions_status check (status in ('CANDIDATE', 'APPROVED', 'ACTIVE', 'REJECTED', 'ARCHIVED'))
);

create index idx_ml_model_versions_account_status_created
    on ml_model_versions (account_id, status, created_at desc);

create table ml_deployment_policies (
    id uuid primary key,
    account_id uuid,
    scope varchar(32) not null,
    device_id uuid,
    device_type varchar(128),
    model_version_id uuid,
    mode varchar(32) not null,
    canary_percent integer,
    fallback_enabled boolean not null,
    updated_by uuid,
    updated_at timestamp not null,
    constraint fk_ml_deployment_policies_model_version foreign key (model_version_id) references ml_model_versions(id),
    constraint ck_ml_deployment_policies_scope check (scope in ('SYSTEM', 'ACCOUNT', 'DEVICE_TYPE', 'DEVICE')),
    constraint ck_ml_deployment_policies_mode check (mode in ('SHADOW', 'CANARY', 'ACTIVE', 'DISABLED')),
    constraint ck_ml_deployment_policies_canary check (canary_percent is null or canary_percent between 0 and 100),
    constraint ck_ml_deployment_policies_scope_target check (
        (scope = 'SYSTEM' and account_id is null and device_id is null and device_type is null)
        or (scope = 'ACCOUNT' and account_id is not null and device_id is null and device_type is null)
        or (scope = 'DEVICE_TYPE' and account_id is not null and device_id is null and device_type is not null)
        or (scope = 'DEVICE' and account_id is not null and device_id is not null)
    )
);

create index idx_ml_deployment_policies_account_updated
    on ml_deployment_policies (account_id, updated_at desc);

create unique index ux_ml_deployment_policy_device
    on ml_deployment_policies (device_id);

create index idx_ml_deployment_policy_device_type
    on ml_deployment_policies (account_id, device_type)
;

create index idx_ml_deployment_policy_account
    on ml_deployment_policies (account_id)
;

create table ml_deployment_audit_events (
    id uuid primary key,
    account_id uuid,
    model_version_id uuid,
    policy_id uuid,
    action varchar(64) not null,
    details_json text not null,
    actor_id uuid,
    created_at timestamp not null,
    constraint fk_ml_deployment_audit_model_version foreign key (model_version_id) references ml_model_versions(id),
    constraint fk_ml_deployment_audit_policy foreign key (policy_id) references ml_deployment_policies(id)
);

create index idx_ml_deployment_audit_account_created
    on ml_deployment_audit_events (account_id, created_at desc);
