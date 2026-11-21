alter table telemetry.vehicle_monitoring add column revision bigint not null default 1;
create table telemetry.vehicle_monitoring_fences (
    user_id uuid not null, device_id uuid not null, primary key (user_id, device_id)
);
create table telemetry.vehicle_monitoring_reviews (
    id uuid primary key, user_id uuid not null, device_id uuid not null,
    base_revision bigint not null, before_settings varchar(30000) not null,
    after_settings varchar(30000) not null, digest varchar(64) not null,
    idempotency_key varchar(100) not null, expires_at timestamp with time zone not null,
    applied_revision bigint, unique (user_id, device_id, idempotency_key)
);
