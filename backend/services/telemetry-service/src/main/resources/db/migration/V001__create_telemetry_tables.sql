create table telemetry_readings (
    id uuid primary key,
    device_id uuid not null,
    source_id varchar(255),
    recorded_at timestamp not null,
    received_at timestamp not null,
    metrics_json text not null
);

create index idx_telemetry_readings_device_recorded_at
    on telemetry_readings (device_id, recorded_at desc);

create table telemetry_quality_flags (
    id uuid primary key,
    reading_id uuid not null references telemetry_readings (id) on delete cascade,
    device_id uuid not null,
    flag varchar(100) not null,
    message text,
    created_at timestamp not null
);

create index idx_telemetry_quality_flags_device_created_at
    on telemetry_quality_flags (device_id, created_at desc);

create table telemetry_aggregates (
    id uuid primary key,
    device_id uuid not null,
    window_start timestamp not null,
    window_end timestamp not null,
    metrics_json text not null
);

create index idx_telemetry_aggregates_device_window
    on telemetry_aggregates (device_id, window_start, window_end);
