create table ml_canonical_metric_rows (
    id uuid primary key,
    account_id uuid not null,
    dataset_version_id uuid not null,
    source_type varchar(32) not null,
    device_id uuid,
    external_device_key varchar(255),
    virtual_device_key varchar(512),
    device_type varchar(128),
    source_id varchar(255),
    sensor_id varchar(255),
    timestamp timestamp not null,
    metric_name varchar(255) not null,
    canonical_metric_name varchar(255) not null,
    numeric_value double precision not null,
    raw_unit varchar(64),
    canonical_unit varchar(64),
    quality_flags text not null,
    metadata_json text not null,
    source_lineage text not null,
    created_at timestamp not null,
    constraint fk_ml_canonical_metric_rows_dataset_version foreign key (dataset_version_id) references ml_dataset_versions(id),
    constraint ck_ml_canonical_metric_rows_source_type check (source_type in ('INTERNAL_TELEMETRY', 'EXTERNAL_IMPORT', 'MIXED')),
    constraint ck_ml_canonical_metric_rows_device_ref check (device_id is not null or external_device_key is not null or virtual_device_key is not null)
);

create index idx_ml_canonical_metric_rows_dataset_time
    on ml_canonical_metric_rows (dataset_version_id, timestamp);

create index idx_ml_canonical_metric_rows_account_metric_time
    on ml_canonical_metric_rows (account_id, canonical_metric_name, timestamp);

create index idx_ml_canonical_metric_rows_device_metric_time
    on ml_canonical_metric_rows (dataset_version_id, device_id, external_device_key, canonical_metric_name, timestamp);
