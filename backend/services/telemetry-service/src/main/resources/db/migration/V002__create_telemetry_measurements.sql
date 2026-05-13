create schema if not exists telemetry;

create table if not exists telemetry.measurements (
    device_id uuid not null,
    source_id varchar(255) not null,
    recorded_at timestamp not null,
    metric_name varchar(100),
    metric_value double precision,
    unit varchar(50),
    quality varchar(50),
    created_at timestamp
);

alter table telemetry.measurements add column if not exists metric_name varchar(100);
alter table telemetry.measurements add column if not exists metric_value double precision;
alter table telemetry.measurements add column if not exists unit varchar(50);
alter table telemetry.measurements add column if not exists quality varchar(50);
alter table telemetry.measurements add column if not exists created_at timestamp;

update telemetry.measurements set metric_name = '_legacy' where metric_name is null;
update telemetry.measurements set metric_value = 0 where metric_value is null;
update telemetry.measurements set quality = 'OK' where quality is null;
update telemetry.measurements set created_at = recorded_at where created_at is null;

alter table telemetry.measurements drop constraint if exists measurements_pkey;

create unique index if not exists uq_measurements_device_source_time_metric
    on telemetry.measurements (device_id, source_id, recorded_at, metric_name);

create index if not exists idx_measurements_device_metric_time
    on telemetry.measurements (device_id, metric_name, recorded_at);
