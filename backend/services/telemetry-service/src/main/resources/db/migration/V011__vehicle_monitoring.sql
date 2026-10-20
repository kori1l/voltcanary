alter table telemetry.positions add column ignition double precision;
alter table telemetry.positions add column fuel_percent double precision;
alter table telemetry.positions add column odometer_km double precision;
create table telemetry.vehicle_monitoring (
    user_id uuid not null,
    device_id uuid not null,
    settings varchar(30000) not null,
    updated_at timestamp with time zone not null,
    primary key (user_id, device_id)
);
