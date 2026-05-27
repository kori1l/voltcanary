alter table sensors add column if not exists metric_mapping varchar(100);
alter table sensors add column if not exists metadata_json text;
alter table sensors add column if not exists updated_at timestamp;

update sensors
set updated_at = created_at
where updated_at is null;

alter table sensors alter column updated_at set not null;

create index if not exists idx_sensors_metric_mapping on sensors (metric_mapping);
