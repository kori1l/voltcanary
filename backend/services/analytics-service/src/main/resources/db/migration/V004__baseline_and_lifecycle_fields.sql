alter table device_baselines
    add column if not exists metric varchar(100);

alter table device_baselines
    add column if not exists period varchar(100);

alter table device_baselines
    add column if not exists algorithm_version varchar(100);

alter table device_baselines
    add column if not exists mean double precision;

alter table device_baselines
    add column if not exists median double precision;

alter table device_baselines
    add column if not exists p90 double precision;

alter table device_baselines
    add column if not exists min double precision;

alter table device_baselines
    add column if not exists max double precision;

alter table device_baselines
    add column if not exists sample_count integer;

create index if not exists idx_device_baselines_device_metric_calculated_at
    on device_baselines (device_id, metric, calculated_at desc);

alter table anomalies
    add column if not exists status varchar(50) not null default 'ACTIVE';

alter table anomalies
    add column if not exists superseded_at timestamp;

create index if not exists idx_anomalies_device_status_detected_at
    on anomalies (device_id, status, detected_at desc);

alter table recommendations
    add column if not exists status varchar(50) not null default 'ACTIVE';

alter table recommendations
    add column if not exists superseded_at timestamp;

create index if not exists idx_recommendations_device_status_created_at
    on recommendations (device_id, status, created_at desc);
