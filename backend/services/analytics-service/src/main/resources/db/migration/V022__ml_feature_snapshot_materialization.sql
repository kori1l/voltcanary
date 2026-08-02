alter table ml_feature_snapshots
    add column if not exists feature_data_hash varchar(128);

alter table ml_feature_snapshots
    add column if not exists feature_config_hash varchar(128);

alter table ml_feature_snapshots
    add column if not exists immutable_row_reference varchar(1000);

alter table ml_feature_snapshots
    add column if not exists model_family varchar(64);

alter table ml_feature_snapshots
    add column if not exists horizon_days integer;

alter table ml_feature_snapshots
    add column if not exists feature_template_id varchar(128);

alter table ml_feature_snapshots
    add column if not exists feature_template_version varchar(128);

create table if not exists ml_feature_rows (
    id uuid primary key,
    account_id uuid not null,
    feature_snapshot_id uuid not null,
    dataset_version_id uuid not null,
    entity_id varchar(255) not null,
    device_id uuid,
    virtual_device_key varchar(255),
    device_type varchar(128),
    prediction_at timestamp not null,
    horizon_days integer,
    feature_values_json text not null,
    label_values_json text,
    split_assignment varchar(32),
    source_lineage_json text not null,
    quality_flags_json text not null,
    created_at timestamp not null,
    constraint fk_ml_feature_rows_snapshot foreign key (feature_snapshot_id) references ml_feature_snapshots(id),
    constraint fk_ml_feature_rows_dataset_version foreign key (dataset_version_id) references ml_dataset_versions(id),
    constraint ck_ml_feature_rows_target check (device_id is not null or virtual_device_key is not null),
    constraint ck_ml_feature_rows_split check (split_assignment is null or split_assignment in ('TRAIN', 'VALIDATION', 'TEST'))
);

create index if not exists idx_ml_feature_rows_snapshot_prediction
    on ml_feature_rows (feature_snapshot_id, prediction_at);

create index if not exists idx_ml_feature_rows_dataset_entity
    on ml_feature_rows (dataset_version_id, entity_id);
