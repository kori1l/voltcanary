alter table telemetry.measurements add column if not exists received_at timestamp;
alter table telemetry.measurements add column if not exists account_id uuid;

update telemetry.measurements
set received_at = created_at
where received_at is null and created_at is not null;

update telemetry.measurements
set received_at = recorded_at
where received_at is null;

alter table telemetry_readings add column if not exists account_id uuid;
alter table telemetry_quality_flags add column if not exists account_id uuid;

create index if not exists idx_measurements_account_device_metric_time
    on telemetry.measurements (account_id, device_id, metric_name, recorded_at);

create index if not exists idx_telemetry_readings_account_device_recorded_at
    on telemetry_readings (account_id, device_id, recorded_at desc);

drop table if exists telemetry_aggregates;
