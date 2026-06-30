create table maintenance_windows_read_model (
    maintenance_window_id uuid primary key,
    account_id uuid not null,
    device_id uuid not null,
    title varchar(255) not null,
    start_at timestamp not null,
    end_at timestamp not null,
    suppress_alerts boolean not null,
    exclude_from_analytics boolean not null,
    exclude_from_ml boolean not null,
    cancelled_at timestamp,
    version bigint not null,
    updated_at timestamp not null
);

create index idx_alert_maintenance_device_time
    on maintenance_windows_read_model (device_id, cancelled_at, start_at, end_at);

create table maintenance_projection_checkpoints (
    source_name varchar(255) primary key,
    checkpoint_at timestamp not null,
    updated_at timestamp not null
);
