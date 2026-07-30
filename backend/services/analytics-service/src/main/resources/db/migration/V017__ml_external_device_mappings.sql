create table ml_external_device_mappings (
    id uuid primary key,
    account_id uuid not null,
    imported_file_id uuid not null,
    external_device_key varchar(255) not null,
    matched_device_id uuid,
    virtual_device_key varchar(512) not null,
    device_type varchar(128),
    created_at timestamp not null,
    constraint fk_ml_external_device_mappings_file foreign key (imported_file_id) references ml_imported_files(id),
    constraint ux_ml_external_device_mappings_file_key unique (imported_file_id, external_device_key)
);

create index idx_ml_external_device_mappings_file
    on ml_external_device_mappings (imported_file_id, external_device_key);

alter table ml_imported_metric_rows
    add column if not exists matched_device_id uuid;

alter table ml_imported_metric_rows
    add column if not exists virtual_device_key varchar(512);

alter table ml_imported_metric_rows
    add column if not exists device_type varchar(128);
