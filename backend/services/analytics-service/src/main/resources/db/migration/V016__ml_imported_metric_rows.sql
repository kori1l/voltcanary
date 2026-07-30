create table ml_imported_metric_rows (
    id uuid primary key,
    account_id uuid not null,
    imported_file_id uuid not null,
    external_device_key varchar(255) not null,
    timestamp timestamp not null,
    metric_name varchar(255) not null,
    numeric_value double precision not null,
    unit varchar(64),
    label varchar(32),
    metadata_json text not null,
    source_row_number bigint not null,
    created_at timestamp not null,
    constraint fk_ml_imported_metric_rows_file foreign key (imported_file_id) references ml_imported_files(id),
    constraint ck_ml_imported_metric_rows_label check (label is null or label in ('NORMAL', 'ANOMALY', 'DEGRADATION', 'MAINTENANCE', 'UNKNOWN')),
    constraint ux_ml_imported_metric_rows_file_row unique (imported_file_id, source_row_number)
);

create index idx_ml_imported_metric_rows_file_row
    on ml_imported_metric_rows (imported_file_id, source_row_number);

create index idx_ml_imported_metric_rows_account_metric_time
    on ml_imported_metric_rows (account_id, metric_name, timestamp);
